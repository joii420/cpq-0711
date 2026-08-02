-- =============================================================================
-- task-0729 · B2：取价函数 f_material_element_price + 8 个视图迁移（通道 A）
-- =============================================================================
-- 依据：backtask.md B2 + 需求说明.md §11.15.4（通道 A 取价函数与视图迁移）
--
-- 【函数】f_material_element_price(p_customer_no TEXT, p_base_date DATE)
--   RETURNS TABLE(material_no, element_code, unit_price, currency, price_unit)
--   不改 f_customer_element_price 签名（硬约束 11）；三条硬要求：
--     ① 必须有 fallback 分支：(customer_no, material_no) 有指针 -> 取该版明细；
--        无指针，或指针存在但该元素未落在版本明细里（不在策略参与清单 / 无价）
--        -> 回落 f_customer_element_price 的实时算逻辑（同一函数体内按 (料号,元素) 粒度合并，
--        比"整料号二选一"更细，天然覆盖"策略清单外元素"场景）。
--     ② 必须返回 currency（缺口 B6：mc_view 现行同时输出 cep.unit_price / cep.currency）。
--     ③ material_no 候选集：报价侧 BOM/元素数据挂在真实 customer_no 下；核价侧（COMP-0049
--        对应的 wl_ys_bom_view）BOM/元素数据是全局主档，customer_no='_GLOBAL_'（已实测核实，
--        见 V366 迁移说明 + 本迁移开发期查证）。故候选集须同时覆盖 p_customer_no 与 '_GLOBAL_'
--        两个 customer_no 取值，否则核价侧全部落空回退不到任何 material_no。
--
-- 【JOIN 键铁律】（§11.15.4.2，最容易静默失败的一处）
--   JOIN 键必须与该视图 hf_part_no 的输出表达式逐字一致：
--     平铺形态：ON cep.material_no = ebi.material_no
--     闭包形态：ON cep.material_no = COALESCE(cl.root_no, ebi.material_no)
--   两种形态在报价侧 7 个 mc_view 中并存（COMP-0021 平铺 / 其余 6 个闭包），
--   核价侧 wl_ys_bom_view（COMP-0049）平铺。逐视图按各自 hf_part_no 表达式手工改写，
--   不做统一字符串替换（本迁移由脚本从 DB 现网 sql_template 原文生成，逐字节 diff 验证过，
--   仅改了「函数名」+「补一行 AND material_no 匹配」，其余字符逐字不动）。
--
-- 【可逆】每个视图的原 sql_template 逐字保留在文末注释块，回滚参考。
-- 【幂等】所有 UPDATE 均带 sql_template NOT LIKE '%f_material_element_price%' 判断，可安全重跑。
-- 【重启纪律】改视图后必须重启 Quarkus —— ImplicitJoinRewriter.tableColumnsCache /
--   SqlViewExecutor.customerCodeCache 是进程级缓存（CLAUDE.md「视图重建后必须重启」）。
-- =============================================================================

CREATE OR REPLACE FUNCTION f_material_element_price(
    p_customer_no TEXT,
    p_base_date   DATE
) RETURNS TABLE (
    material_no  VARCHAR,
    element_code VARCHAR,
    unit_price   NUMERIC,
    currency     VARCHAR,
    price_unit   VARCHAR
) LANGUAGE sql STABLE AS $fn$
WITH pointers AS (              -- 该客户已升版的料号 -> 当前指针指向的版本
    SELECT material_no, version_id
      FROM material_price_version_ref
     WHERE customer_no = p_customer_no
),
versioned AS (                  -- 有指针的料号：直接读版本明细（权威快照，不重算）。
                                 -- current_price IS NULL 的行（该元素本期彻底无价且从无历史价）
                                 -- 不出现在这里，下面 realtime 分支会按元素级兜底补上（§11.3.2.1 第三行）。
    SELECT p.material_no, i.element_code, i.current_price AS unit_price,
           i.currency, i.price_unit
      FROM pointers p
      JOIN element_price_version_item i ON i.version_id = p.version_id
     WHERE i.current_price IS NOT NULL
),
candidate_materials AS (        -- 候选 material_no 全集：覆盖真实客户（报价侧 BOM/元素挂真实
                                 -- customer_no）与 '_GLOBAL_'（核价侧 BOM/元素主档全局共享，
                                 -- 客户维度只体现在元素价格策略上，不体现在 BOM 结构上）。
    SELECT material_no FROM material_bom_item
     WHERE customer_no IN (p_customer_no, '_GLOBAL_') AND is_current = true
    UNION
    SELECT material_no FROM element_bom_item
     WHERE customer_no IN (p_customer_no, '_GLOBAL_') AND is_current = true
),
realtime AS (                   -- fallback：候选料号 × 全部实时算价元素（不改 f_customer_element_price 签名）
    SELECT cm.material_no, f.element_code, f.unit_price, f.currency, f.price_unit
      FROM candidate_materials cm
      CROSS JOIN f_customer_element_price(p_customer_no, p_base_date) f
)
SELECT v.material_no, v.element_code, v.unit_price, v.currency, v.price_unit
  FROM versioned v
UNION ALL
SELECT r.material_no, r.element_code, r.unit_price, r.currency, r.price_unit
  FROM realtime r
  LEFT JOIN versioned v2
    ON v2.material_no = r.material_no AND v2.element_code = r.element_code
 WHERE v2.material_no IS NULL;   -- 该 (料号,元素) 已由版本明细给出的不重复给实时价
$fn$;

COMMENT ON FUNCTION f_material_element_price(TEXT, DATE) IS
'task-0729 料号级取价核心表函数。按 (customer_no, material_no) 查版本指针：有则取该版本明细价
（含"沿用上一版"的 inherited_from_previous 行，current_price 非空即返回）；无指针的料号、或
指针存在但该元素未落在版本明细里（不在策略参与清单 / 该元素本期彻底无价），均回落
f_customer_element_price(p_customer_no, p_base_date) 的实时算逻辑（未改其签名）。
候选料号集合覆盖 customer_no IN (p_customer_no, ''_GLOBAL_'')，因核价侧 BOM/元素主档挂在
''_GLOBAL_'' 下（客户维度只体现在元素价格策略取价，不体现在 BOM 结构本身）。
JOIN 本函数时，material_no 匹配键必须与调用方视图的 hf_part_no 输出表达式逐字一致
（平铺形态用 ebi.material_no；BOM 闭包形态用 COALESCE(cl.root_no, ebi.material_no)），
详见 dev-docs/rule-0724-组件模板配置/3-SQL视图.md JOIN 键铁律。';

-- -----------------------------------------------------------------------------
-- COMP-0021（mc_view） · hf_part_no 形态：FLAT（ebi.material_no）
-- -----------------------------------------------------------------------------
UPDATE component_sql_view csv
   SET sql_template = $tpl$-- 材料成本(材质元素, 平铺契约; 材质逐行取本行 material_part_no; 元素单价接客户价格策略)
SELECT
  ebi.material_no AS hf_part_no,
  ebi.material_no AS _销售料号,
  COALESCE(mr.name, mm2.material_name) AS _材质,
  ebi.seq_no AS _项次,
  ebi.component_no AS _元素,
  ebi.content AS _组成含量,
  ebi.scrap_rate AS _损耗率,
  ebi.composition_qty AS _毛重,
  ebi.issue_unit AS _毛用量单位,
  cep.unit_price AS 元素单价
FROM element_bom_item ebi
  LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
  LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
  LEFT JOIN f_material_element_price(:customerCode, :priceBaseDate) cep
         ON cep.element_code = ebi.component_no
         AND cep.material_no = ebi.material_no
WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
ORDER BY ebi.material_no, ebi.material_part_no, ebi.seq_no
$tpl$,
       updated_at = now()
  FROM component c
 WHERE c.id = csv.component_id
   AND c.code = 'COMP-0021'
   AND csv.sql_template NOT LIKE '%f_material_element_price%';

-- 【COMP-0021 回滚参考】迁移前 sql_template 原文（逐字保留，回滚时整段替回即可）：
-- -- 材料成本(材质元素, 平铺契约; 材质逐行取本行 material_part_no; 元素单价接客户价格策略)
-- SELECT
--   ebi.material_no AS hf_part_no,
--   ebi.material_no AS _销售料号,
--   COALESCE(mr.name, mm2.material_name) AS _材质,
--   ebi.seq_no AS _项次,
--   ebi.component_no AS _元素,
--   ebi.content AS _组成含量,
--   ebi.scrap_rate AS _损耗率,
--   ebi.composition_qty AS _毛重,
--   ebi.issue_unit AS _毛用量单位,
--   cep.unit_price AS 元素单价
-- FROM element_bom_item ebi
--   LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
--   LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
--   LEFT JOIN f_customer_element_price(:customerCode, :priceBaseDate) cep
--          ON cep.element_code = ebi.component_no
-- WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
-- ORDER BY ebi.material_no, ebi.material_part_no, ebi.seq_no

-- -----------------------------------------------------------------------------
-- COMP-0027（mc_view） · hf_part_no 形态：CLOSURE（COALESCE(cl.root_no, ebi.material_no)）
-- -----------------------------------------------------------------------------
UPDATE component_sql_view csv
   SET sql_template = $tpl$-- 材料成本(材质元素, 平铺契约; 料号列=材质料号 material_part_no(料号铁律); 元素单价接客户价格策略)
-- task-0726 BOM 闭包: hf_part_no 输出「闭包根料号」, 使子件(如 S-80011)的材质也进入本页签。
-- ⚠️ 铁律1: 白名单表(element_bom_item)必须在顶层 FROM。写成 FROM bom_closure JOIN element_bom_item
--          会让 QuotePendingRewriter 主位表探测退化选中 CTE base case 里的表, 锚点注入致
--          "each UNION query must have the same number of columns"。
-- ⚠️ 铁律2: 必须 LEFT JOIN + COALESCE 兜底 —— 无任何 BOM 行的单料产品不在闭包里, 否则页签整个空白。
WITH RECURSIVE bom_closure AS (
  SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl
  FROM material_bom_item b
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
  UNION ALL
  SELECT c.root_no, b.component_no, c.lvl + 1
  FROM bom_closure c
    JOIN material_bom_item b ON b.material_no = c.node_no
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
    AND c.lvl < 10
)
SELECT
  COALESCE(cl.root_no, ebi.material_no) AS hf_part_no,
  ebi.material_part_no AS _料号,
  COALESCE(mr.name, mm2.material_name) AS _材质,
  ebi.seq_no AS _项次,
  ebi.component_no AS _元素,
  ebi.content AS _组成含量,
  ebi.scrap_rate AS _损耗率,
  ebi.composition_qty AS _毛重,
  ebi.issue_unit AS _毛用量单位,
  ebi.material_no AS _归属料号,
  cep.unit_price AS 元素单价
FROM element_bom_item ebi
  LEFT JOIN bom_closure cl ON cl.node_no = ebi.material_no
  LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
  LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
  LEFT JOIN f_material_element_price(:customerCode, :priceBaseDate) cep
         ON cep.element_code = ebi.component_no
         AND cep.material_no = COALESCE(cl.root_no, ebi.material_no)
WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
ORDER BY COALESCE(cl.lvl, 0), ebi.material_no, ebi.material_part_no, ebi.seq_no
$tpl$,
       updated_at = now()
  FROM component c
 WHERE c.id = csv.component_id
   AND c.code = 'COMP-0027'
   AND csv.sql_template NOT LIKE '%f_material_element_price%';

-- 【COMP-0027 回滚参考】迁移前 sql_template 原文（逐字保留，回滚时整段替回即可）：
-- -- 材料成本(材质元素, 平铺契约; 料号列=材质料号 material_part_no(料号铁律); 元素单价接客户价格策略)
-- -- task-0726 BOM 闭包: hf_part_no 输出「闭包根料号」, 使子件(如 S-80011)的材质也进入本页签。
-- -- ⚠️ 铁律1: 白名单表(element_bom_item)必须在顶层 FROM。写成 FROM bom_closure JOIN element_bom_item
-- --          会让 QuotePendingRewriter 主位表探测退化选中 CTE base case 里的表, 锚点注入致
-- --          "each UNION query must have the same number of columns"。
-- -- ⚠️ 铁律2: 必须 LEFT JOIN + COALESCE 兜底 —— 无任何 BOM 行的单料产品不在闭包里, 否则页签整个空白。
-- WITH RECURSIVE bom_closure AS (
--   SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl
--   FROM material_bom_item b
--   WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
--   UNION ALL
--   SELECT c.root_no, b.component_no, c.lvl + 1
--   FROM bom_closure c
--     JOIN material_bom_item b ON b.material_no = c.node_no
--   WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
--     AND c.lvl < 10
-- )
-- SELECT
--   COALESCE(cl.root_no, ebi.material_no) AS hf_part_no,
--   ebi.material_part_no AS _料号,
--   COALESCE(mr.name, mm2.material_name) AS _材质,
--   ebi.seq_no AS _项次,
--   ebi.component_no AS _元素,
--   ebi.content AS _组成含量,
--   ebi.scrap_rate AS _损耗率,
--   ebi.composition_qty AS _毛重,
--   ebi.issue_unit AS _毛用量单位,
--   ebi.material_no AS _归属料号,
--   cep.unit_price AS 元素单价
-- FROM element_bom_item ebi
--   LEFT JOIN bom_closure cl ON cl.node_no = ebi.material_no
--   LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
--   LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
--   LEFT JOIN f_customer_element_price(:customerCode, :priceBaseDate) cep
--          ON cep.element_code = ebi.component_no
-- WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
-- ORDER BY COALESCE(cl.lvl, 0), ebi.material_no, ebi.material_part_no, ebi.seq_no

-- -----------------------------------------------------------------------------
-- COMP-0049（wl_ys_bom_view） · hf_part_no 形态：FLAT（ebi.material_no）
-- -----------------------------------------------------------------------------
UPDATE component_sql_view csv
   SET sql_template = $tpl$select
  ebi.material_no        as material_no,
  ebi.material_no        as hf_part_no,
  ebi.material_part_no   as material_part_no,
  coalesce(mr.name, mi.material_name) as material_name,
  mi.specification       as specification,
  mi.dimension           as dimension,
  ebi.seq_no             as seq_no,
  ebi.component_no       as component_no,
  ebi.content            as content,
  ebi.scrap_rate         as scrap_rate,
  cep.unit_price         as 元素单价
from element_bom_item ebi
left join material_master mi on mi.material_no = ebi.material_part_no
left join material_recipe mr on mr.code = ebi.material_part_no
left join f_material_element_price(:customerCode, :priceBaseDate) cep
       on cep.element_code = ebi.component_no
      and cep.material_no  = ebi.material_no
where ebi.system_type = 'PRICING'
  and ebi.is_current = true
order by ebi.seq_no
$tpl$,
       updated_at = now()
  FROM component c
 WHERE c.id = csv.component_id
   AND c.code = 'COMP-0049'
   AND csv.sql_template NOT LIKE '%f_material_element_price%';

-- 【COMP-0049 回滚参考】迁移前 sql_template 原文（逐字保留，回滚时整段替回即可）：
-- select
--   ebi.material_no        as material_no,
--   ebi.material_no        as hf_part_no,
--   ebi.material_part_no   as material_part_no,
--   coalesce(mr.name, mi.material_name) as material_name,
--   mi.specification       as specification,
--   mi.dimension           as dimension,
--   ebi.seq_no             as seq_no,
--   ebi.component_no       as component_no,
--   ebi.content            as content,
--   ebi.scrap_rate         as scrap_rate,
--   cep.unit_price         as 元素单价
-- from element_bom_item ebi
-- left join material_master mi on mi.material_no = ebi.material_part_no
-- left join material_recipe mr on mr.code = ebi.material_part_no
-- left join f_customer_element_price(:customerCode, :priceBaseDate) cep
--        on cep.element_code = ebi.component_no
-- where ebi.system_type = 'PRICING'
--   and ebi.is_current = true
-- order by ebi.seq_no

-- -----------------------------------------------------------------------------
-- COMP-0090（mc_view） · hf_part_no 形态：CLOSURE（COALESCE(cl.root_no, ebi.material_no)）
-- -----------------------------------------------------------------------------
UPDATE component_sql_view csv
   SET sql_template = $tpl$-- 材料成本(材质元素, 平铺契约: hf_part_no + :customerCode; 元素单价接客户价格策略)
-- 「料号」列 = 所属销售料号(ebi.material_no)：闭包后主件与子件的材质行同页签共存，靠本列区分归属
--   (task-0726 §3.8.4)；它必须同时在 rowKeyFields 里，否则撞键 → 编辑串行/末值塌缩。
-- 材质料号(material_part_no) 模板无此列 → 只作 JOIN 键 + ORDER BY 键，不出可见列(料号铁律 §3.4)。
-- task-0726 BOM 闭包: hf_part_no 输出「闭包根料号」, 使子件(如 S-80011)的材质也进入本页签。
-- ⚠️ 铁律1(task-0726 §3.8.3): 白名单表必须在顶层 FROM，不能写成 FROM bom_closure JOIN <白名单表>，
--          否则 QuotePendingRewriter 主位表探测退化选中 CTE base case 的表 → 锚点注入致
--          "each UNION query must have the same number of columns"。
-- ⚠️ 铁律2: 必须 LEFT JOIN bom_closure + COALESCE 兜底 —— 无任何 BOM 行的单料产品不在闭包里，
--          写成 INNER JOIN 或直取 cl.root_no 会让这类产品的页签整个空白。
WITH RECURSIVE bom_closure AS (
  SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl
  FROM material_bom_item b
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
  UNION            -- ⚠️ 用 UNION 去重，不能用 UNION ALL：同一对父子常并存多条 BOM 边
                   --    (RECIPE + ASSEMBLY 各一条) → UNION ALL 会生出重复路径
  SELECT c.root_no, b.component_no, c.lvl + 1
  FROM bom_closure c
    JOIN material_bom_item b ON b.material_no = c.node_no
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
    AND c.lvl < 10                          -- 深度上限，防环
), bom_closure_d AS (
  -- 🔒 (root,node) 唯一化：多条边/多层路径到达同一子件时只保留一行(取最浅层)。
  --    漏这层 → 平铺页签行数成倍复制、页签小计翻倍(实测 4 行→8 行)。
  SELECT root_no, node_no, MIN(lvl) AS lvl FROM bom_closure GROUP BY root_no, node_no
)
SELECT
  COALESCE(cl.root_no, ebi.material_no) AS hf_part_no,
  ebi.material_no AS _料号,
  COALESCE(mr.name, mm2.material_name) AS _材质,   -- 逐行取自本行 material_part_no(一号多材质铁律)
  ebi.seq_no AS _项次,
  ebi.component_no AS _元素,
  ebi.content AS _组成含量,
  ebi.scrap_rate AS _损耗率,
  ebi.composition_qty AS _毛重,
  ebi.issue_unit AS _毛用量单位,
  cep.unit_price AS 元素单价                        -- 价格策略列: 别名逐字、不加 _
FROM element_bom_item ebi
  LEFT JOIN bom_closure_d cl ON cl.node_no = ebi.material_no
  LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
  LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
  LEFT JOIN f_material_element_price(:customerCode, :priceBaseDate) cep
         ON cep.element_code = ebi.component_no     -- LEFT JOIN + 元素符号键
         AND cep.material_no = COALESCE(cl.root_no, ebi.material_no)
WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
ORDER BY COALESCE(cl.lvl, 0), ebi.material_no, ebi.material_part_no, ebi.seq_no
$tpl$,
       updated_at = now()
  FROM component c
 WHERE c.id = csv.component_id
   AND c.code = 'COMP-0090'
   AND csv.sql_template NOT LIKE '%f_material_element_price%';

-- 【COMP-0090 回滚参考】迁移前 sql_template 原文（逐字保留，回滚时整段替回即可）：
-- -- 材料成本(材质元素, 平铺契约: hf_part_no + :customerCode; 元素单价接客户价格策略)
-- -- 「料号」列 = 所属销售料号(ebi.material_no)：闭包后主件与子件的材质行同页签共存，靠本列区分归属
-- --   (task-0726 §3.8.4)；它必须同时在 rowKeyFields 里，否则撞键 → 编辑串行/末值塌缩。
-- -- 材质料号(material_part_no) 模板无此列 → 只作 JOIN 键 + ORDER BY 键，不出可见列(料号铁律 §3.4)。
-- -- task-0726 BOM 闭包: hf_part_no 输出「闭包根料号」, 使子件(如 S-80011)的材质也进入本页签。
-- -- ⚠️ 铁律1(task-0726 §3.8.3): 白名单表必须在顶层 FROM，不能写成 FROM bom_closure JOIN <白名单表>，
-- --          否则 QuotePendingRewriter 主位表探测退化选中 CTE base case 的表 → 锚点注入致
-- --          "each UNION query must have the same number of columns"。
-- -- ⚠️ 铁律2: 必须 LEFT JOIN bom_closure + COALESCE 兜底 —— 无任何 BOM 行的单料产品不在闭包里，
-- --          写成 INNER JOIN 或直取 cl.root_no 会让这类产品的页签整个空白。
-- WITH RECURSIVE bom_closure AS (
--   SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl
--   FROM material_bom_item b
--   WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
--   UNION            -- ⚠️ 用 UNION 去重，不能用 UNION ALL：同一对父子常并存多条 BOM 边
--                    --    (RECIPE + ASSEMBLY 各一条) → UNION ALL 会生出重复路径
--   SELECT c.root_no, b.component_no, c.lvl + 1
--   FROM bom_closure c
--     JOIN material_bom_item b ON b.material_no = c.node_no
--   WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
--     AND c.lvl < 10                          -- 深度上限，防环
-- ), bom_closure_d AS (
--   -- 🔒 (root,node) 唯一化：多条边/多层路径到达同一子件时只保留一行(取最浅层)。
--   --    漏这层 → 平铺页签行数成倍复制、页签小计翻倍(实测 4 行→8 行)。
--   SELECT root_no, node_no, MIN(lvl) AS lvl FROM bom_closure GROUP BY root_no, node_no
-- )
-- SELECT
--   COALESCE(cl.root_no, ebi.material_no) AS hf_part_no,
--   ebi.material_no AS _料号,
--   COALESCE(mr.name, mm2.material_name) AS _材质,   -- 逐行取自本行 material_part_no(一号多材质铁律)
--   ebi.seq_no AS _项次,
--   ebi.component_no AS _元素,
--   ebi.content AS _组成含量,
--   ebi.scrap_rate AS _损耗率,
--   ebi.composition_qty AS _毛重,
--   ebi.issue_unit AS _毛用量单位,
--   cep.unit_price AS 元素单价                        -- 价格策略列: 别名逐字、不加 _
-- FROM element_bom_item ebi
--   LEFT JOIN bom_closure_d cl ON cl.node_no = ebi.material_no
--   LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
--   LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
--   LEFT JOIN f_customer_element_price(:customerCode, :priceBaseDate) cep
--          ON cep.element_code = ebi.component_no     -- LEFT JOIN + 元素符号键
-- WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
-- ORDER BY COALESCE(cl.lvl, 0), ebi.material_no, ebi.material_part_no, ebi.seq_no

-- -----------------------------------------------------------------------------
-- COMP-0102（mc_view） · hf_part_no 形态：CLOSURE（COALESCE(cl.root_no, ebi.material_no)）
-- -----------------------------------------------------------------------------
UPDATE component_sql_view csv
   SET sql_template = $tpl$-- 材料成本(材质元素, 平铺契约: hf_part_no + :customerCode; 元素单价接客户价格策略)
-- 「销售料号」= 所属销售料号(ebi.material_no)：闭包后主件与子件的材质行同页签共存，靠本列区分归属
--   (task-0726 §3.8.4)；它必须同时在 rowKeyFields 里，否则撞键 → 编辑串行/末值塌缩。
-- 「料号」= 材质料号(ebi.material_part_no) = 本页签语义层级的料号(§3.4 第二步)。
-- task-0726 BOM 闭包: hf_part_no 输出「闭包根料号」, 使子件的数据也进入本页签。
-- ⚠️ 铁律1(§3.8.3): 白名单表必须在顶层 FROM，不能写成 FROM bom_closure JOIN <白名单表>，
--          否则 QuotePendingRewriter 主位表探测退化选中 CTE base case 的表 → 锚点注入报
--          "each UNION query must have the same number of columns"。
-- ⚠️ 铁律2: 必须 LEFT JOIN bom_closure_d + COALESCE 兜底 —— 无任何 BOM 行的单料产品不在闭包里，
--          写成 INNER JOIN 或直取 cl.root_no 会让这类产品的页签整个空白。
WITH RECURSIVE bom_closure AS (
  SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl
  FROM material_bom_item b
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
  UNION            -- ⚠️ 用 UNION 去重，不能用 UNION ALL：同一对父子常并存多条 BOM 边
                   --    (RECIPE + ASSEMBLY 各一条) → UNION ALL 会生出重复路径
  SELECT c.root_no, b.component_no, c.lvl + 1
  FROM bom_closure c
    JOIN material_bom_item b ON b.material_no = c.node_no
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
    AND c.lvl < 10                          -- 深度上限，防环
), bom_closure_d AS (
  -- 🔒 (root,node) 唯一化：多条边/多层路径到达同一子件时只保留一行(取最浅层)。
  --    漏这层 → 平铺页签行数成倍复制、页签小计翻倍(task-0726 §3.8.3 铁律3)。
  SELECT root_no, node_no, MIN(lvl) AS lvl FROM bom_closure GROUP BY root_no, node_no
)
SELECT
  COALESCE(cl.root_no, ebi.material_no) AS hf_part_no,
  ebi.material_no AS _销售料号,
  ebi.material_part_no AS _料号,                   -- 材质料号(partNoField)
  COALESCE(mr.name, mm2.material_name) AS _料件,   -- 材质名：逐行取自本行 material_part_no
  ebi.component_usage_type AS _产出类型,
  ebi.seq_no AS _项次,
  ebi.component_no AS _元素,
  ebi.content AS _组成含量,
  ebi.scrap_rate AS _损耗率,
  ebi.composition_qty AS _毛重,
  ebi.issue_unit AS _毛用量单位,
  cep.unit_price AS 元素单价,                       -- 价格策略列：别名逐字、不加 _
  cep.currency AS 货币
FROM element_bom_item ebi
  LEFT JOIN bom_closure_d cl ON cl.node_no = ebi.material_no
  LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
  LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
  LEFT JOIN f_material_element_price(:customerCode, :priceBaseDate) cep
         ON cep.element_code = ebi.component_no     -- LEFT JOIN + 元素符号键(硬约束 1/2)
         AND cep.material_no = COALESCE(cl.root_no, ebi.material_no)
WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
ORDER BY COALESCE(cl.lvl, 0), ebi.material_no, ebi.material_part_no, ebi.seq_no
$tpl$,
       updated_at = now()
  FROM component c
 WHERE c.id = csv.component_id
   AND c.code = 'COMP-0102'
   AND csv.sql_template NOT LIKE '%f_material_element_price%';

-- 【COMP-0102 回滚参考】迁移前 sql_template 原文（逐字保留，回滚时整段替回即可）：
-- -- 材料成本(材质元素, 平铺契约: hf_part_no + :customerCode; 元素单价接客户价格策略)
-- -- 「销售料号」= 所属销售料号(ebi.material_no)：闭包后主件与子件的材质行同页签共存，靠本列区分归属
-- --   (task-0726 §3.8.4)；它必须同时在 rowKeyFields 里，否则撞键 → 编辑串行/末值塌缩。
-- -- 「料号」= 材质料号(ebi.material_part_no) = 本页签语义层级的料号(§3.4 第二步)。
-- -- task-0726 BOM 闭包: hf_part_no 输出「闭包根料号」, 使子件的数据也进入本页签。
-- -- ⚠️ 铁律1(§3.8.3): 白名单表必须在顶层 FROM，不能写成 FROM bom_closure JOIN <白名单表>，
-- --          否则 QuotePendingRewriter 主位表探测退化选中 CTE base case 的表 → 锚点注入报
-- --          "each UNION query must have the same number of columns"。
-- -- ⚠️ 铁律2: 必须 LEFT JOIN bom_closure_d + COALESCE 兜底 —— 无任何 BOM 行的单料产品不在闭包里，
-- --          写成 INNER JOIN 或直取 cl.root_no 会让这类产品的页签整个空白。
-- WITH RECURSIVE bom_closure AS (
--   SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl
--   FROM material_bom_item b
--   WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
--   UNION            -- ⚠️ 用 UNION 去重，不能用 UNION ALL：同一对父子常并存多条 BOM 边
--                    --    (RECIPE + ASSEMBLY 各一条) → UNION ALL 会生出重复路径
--   SELECT c.root_no, b.component_no, c.lvl + 1
--   FROM bom_closure c
--     JOIN material_bom_item b ON b.material_no = c.node_no
--   WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
--     AND c.lvl < 10                          -- 深度上限，防环
-- ), bom_closure_d AS (
--   -- 🔒 (root,node) 唯一化：多条边/多层路径到达同一子件时只保留一行(取最浅层)。
--   --    漏这层 → 平铺页签行数成倍复制、页签小计翻倍(task-0726 §3.8.3 铁律3)。
--   SELECT root_no, node_no, MIN(lvl) AS lvl FROM bom_closure GROUP BY root_no, node_no
-- )
-- SELECT
--   COALESCE(cl.root_no, ebi.material_no) AS hf_part_no,
--   ebi.material_no AS _销售料号,
--   ebi.material_part_no AS _料号,                   -- 材质料号(partNoField)
--   COALESCE(mr.name, mm2.material_name) AS _料件,   -- 材质名：逐行取自本行 material_part_no
--   ebi.component_usage_type AS _产出类型,
--   ebi.seq_no AS _项次,
--   ebi.component_no AS _元素,
--   ebi.content AS _组成含量,
--   ebi.scrap_rate AS _损耗率,
--   ebi.composition_qty AS _毛重,
--   ebi.issue_unit AS _毛用量单位,
--   cep.unit_price AS 元素单价,                       -- 价格策略列：别名逐字、不加 _
--   cep.currency AS 货币
-- FROM element_bom_item ebi
--   LEFT JOIN bom_closure_d cl ON cl.node_no = ebi.material_no
--   LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
--   LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
--   LEFT JOIN f_customer_element_price(:customerCode, :priceBaseDate) cep
--          ON cep.element_code = ebi.component_no     -- LEFT JOIN + 元素符号键(硬约束 1/2)
-- WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
-- ORDER BY COALESCE(cl.lvl, 0), ebi.material_no, ebi.material_part_no, ebi.seq_no

-- -----------------------------------------------------------------------------
-- COMP-0122（mc_view） · hf_part_no 形态：CLOSURE（COALESCE(cl.root_no, ebi.material_no)）
-- -----------------------------------------------------------------------------
UPDATE component_sql_view csv
   SET sql_template = $tpl$-- 材料成本(材质元素, 平铺契约: hf_part_no + :customerCode; 元素单价接客户价格策略)
-- 「销售料号」= 所属销售料号(ebi.material_no)：闭包后主件与子件的材质行同页签共存，靠本列区分归属
--   (task-0726 §3.8.4)；它必须同时在 rowKeyFields 里，否则撞键 → 编辑串行/末值塌缩。
-- 「料号」= 材质料号(ebi.material_part_no) = 本页签语义层级的料号(§3.4 第二步)。
-- task-0726 BOM 闭包: hf_part_no 输出「闭包根料号」, 使子件的数据也进入本页签。
-- ⚠️ 铁律1(§3.8.3): 白名单表必须在顶层 FROM，不能写成 FROM bom_closure JOIN <白名单表>，
--          否则 QuotePendingRewriter 主位表探测退化选中 CTE base case 的表 → 锚点注入报
--          "each UNION query must have the same number of columns"。
-- ⚠️ 铁律2: 必须 LEFT JOIN bom_closure_d + COALESCE 兜底 —— 无任何 BOM 行的单料产品不在闭包里，
--          写成 INNER JOIN 或直取 cl.root_no 会让这类产品的页签整个空白。
WITH RECURSIVE bom_closure AS (
  SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl
  FROM material_bom_item b
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
  UNION            -- ⚠️ 用 UNION 去重，不能用 UNION ALL：同一对父子常并存多条 BOM 边
                   --    (RECIPE + ASSEMBLY 各一条) → UNION ALL 会生出重复路径
  SELECT c.root_no, b.component_no, c.lvl + 1
  FROM bom_closure c
    JOIN material_bom_item b ON b.material_no = c.node_no
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
    AND c.lvl < 10                          -- 深度上限，防环
), bom_closure_d AS (
  -- 🔒 (root,node) 唯一化：多条边/多层路径到达同一子件时只保留一行(取最浅层)。
  --    漏这层 → 平铺页签行数成倍复制、页签小计翻倍(task-0726 §3.8.3 铁律3)。
  SELECT root_no, node_no, MIN(lvl) AS lvl FROM bom_closure GROUP BY root_no, node_no
)
SELECT
  COALESCE(cl.root_no, ebi.material_no) AS hf_part_no,
  ebi.material_no AS _销售料号,
  ebi.material_part_no AS _料号,                   -- 材质料号(partNoField)
  COALESCE(mr.name, mm2.material_name) AS _料件,   -- 材质名：逐行取自本行 material_part_no
  ebi.component_usage_type AS _产出类型,
  ebi.seq_no AS _项次,
  ebi.component_no AS _元素,
  ebi.content AS _组成含量,
  ebi.scrap_rate AS _损耗率,
  ebi.composition_qty AS _毛重,
  ebi.issue_unit AS _毛用量单位,
  cep.unit_price AS 元素单价,                       -- 价格策略列：别名逐字、不加 _
  cep.currency AS 货币
FROM element_bom_item ebi
  LEFT JOIN bom_closure_d cl ON cl.node_no = ebi.material_no
  LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
  LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
  LEFT JOIN f_material_element_price(:customerCode, :priceBaseDate) cep
         ON cep.element_code = ebi.component_no     -- LEFT JOIN + 元素符号键(硬约束 1/2)
         AND cep.material_no = COALESCE(cl.root_no, ebi.material_no)
WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
ORDER BY COALESCE(cl.lvl, 0), ebi.material_no, ebi.material_part_no, ebi.seq_no
$tpl$,
       updated_at = now()
  FROM component c
 WHERE c.id = csv.component_id
   AND c.code = 'COMP-0122'
   AND csv.sql_template NOT LIKE '%f_material_element_price%';

-- 【COMP-0122 回滚参考】迁移前 sql_template 原文（逐字保留，回滚时整段替回即可）：
-- -- 材料成本(材质元素, 平铺契约: hf_part_no + :customerCode; 元素单价接客户价格策略)
-- -- 「销售料号」= 所属销售料号(ebi.material_no)：闭包后主件与子件的材质行同页签共存，靠本列区分归属
-- --   (task-0726 §3.8.4)；它必须同时在 rowKeyFields 里，否则撞键 → 编辑串行/末值塌缩。
-- -- 「料号」= 材质料号(ebi.material_part_no) = 本页签语义层级的料号(§3.4 第二步)。
-- -- task-0726 BOM 闭包: hf_part_no 输出「闭包根料号」, 使子件的数据也进入本页签。
-- -- ⚠️ 铁律1(§3.8.3): 白名单表必须在顶层 FROM，不能写成 FROM bom_closure JOIN <白名单表>，
-- --          否则 QuotePendingRewriter 主位表探测退化选中 CTE base case 的表 → 锚点注入报
-- --          "each UNION query must have the same number of columns"。
-- -- ⚠️ 铁律2: 必须 LEFT JOIN bom_closure_d + COALESCE 兜底 —— 无任何 BOM 行的单料产品不在闭包里，
-- --          写成 INNER JOIN 或直取 cl.root_no 会让这类产品的页签整个空白。
-- WITH RECURSIVE bom_closure AS (
--   SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl
--   FROM material_bom_item b
--   WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
--   UNION            -- ⚠️ 用 UNION 去重，不能用 UNION ALL：同一对父子常并存多条 BOM 边
--                    --    (RECIPE + ASSEMBLY 各一条) → UNION ALL 会生出重复路径
--   SELECT c.root_no, b.component_no, c.lvl + 1
--   FROM bom_closure c
--     JOIN material_bom_item b ON b.material_no = c.node_no
--   WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
--     AND c.lvl < 10                          -- 深度上限，防环
-- ), bom_closure_d AS (
--   -- 🔒 (root,node) 唯一化：多条边/多层路径到达同一子件时只保留一行(取最浅层)。
--   --    漏这层 → 平铺页签行数成倍复制、页签小计翻倍(task-0726 §3.8.3 铁律3)。
--   SELECT root_no, node_no, MIN(lvl) AS lvl FROM bom_closure GROUP BY root_no, node_no
-- )
-- SELECT
--   COALESCE(cl.root_no, ebi.material_no) AS hf_part_no,
--   ebi.material_no AS _销售料号,
--   ebi.material_part_no AS _料号,                   -- 材质料号(partNoField)
--   COALESCE(mr.name, mm2.material_name) AS _料件,   -- 材质名：逐行取自本行 material_part_no
--   ebi.component_usage_type AS _产出类型,
--   ebi.seq_no AS _项次,
--   ebi.component_no AS _元素,
--   ebi.content AS _组成含量,
--   ebi.scrap_rate AS _损耗率,
--   ebi.composition_qty AS _毛重,
--   ebi.issue_unit AS _毛用量单位,
--   cep.unit_price AS 元素单价,                       -- 价格策略列：别名逐字、不加 _
--   cep.currency AS 货币
-- FROM element_bom_item ebi
--   LEFT JOIN bom_closure_d cl ON cl.node_no = ebi.material_no
--   LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
--   LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
--   LEFT JOIN f_customer_element_price(:customerCode, :priceBaseDate) cep
--          ON cep.element_code = ebi.component_no     -- LEFT JOIN + 元素符号键(硬约束 1/2)
-- WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
-- ORDER BY COALESCE(cl.lvl, 0), ebi.material_no, ebi.material_part_no, ebi.seq_no

-- -----------------------------------------------------------------------------
-- COMP-0130（mc_view） · hf_part_no 形态：CLOSURE（COALESCE(cl.root_no, ebi.material_no)）
-- -----------------------------------------------------------------------------
UPDATE component_sql_view csv
   SET sql_template = $tpl$-- 材料成本(材质元素, 平铺契约: hf_part_no + :customerCode; 元素单价接客户价格策略)
-- 「销售料号」= 所属销售料号(ebi.material_no)：闭包后主件与子件的材质行同页签共存，靠本列区分归属
--   (task-0726 §3.8.4)；它必须同时在 rowKeyFields 里，否则撞键 → 编辑串行/末值塌缩。
-- 「料号」= 材质料号(ebi.material_part_no) = 本页签语义层级的料号(§3.4 第二步)。
-- task-0726 BOM 闭包: hf_part_no 输出「闭包根料号」, 使子件的数据也进入本页签。
-- ⚠️ 铁律1(§3.8.3): 白名单表必须在顶层 FROM，不能写成 FROM bom_closure JOIN <白名单表>，
--          否则 QuotePendingRewriter 主位表探测退化选中 CTE base case 的表 → 锚点注入报
--          "each UNION query must have the same number of columns"。
-- ⚠️ 铁律2: 必须 LEFT JOIN bom_closure_d + COALESCE 兜底 —— 无任何 BOM 行的单料产品不在闭包里，
--          写成 INNER JOIN 或直取 cl.root_no 会让这类产品的页签整个空白。
WITH RECURSIVE bom_closure AS (
  SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl
  FROM material_bom_item b
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
  UNION            -- ⚠️ 用 UNION 去重，不能用 UNION ALL：同一对父子常并存多条 BOM 边
                   --    (RECIPE + ASSEMBLY 各一条) → UNION ALL 会生出重复路径
  SELECT c.root_no, b.component_no, c.lvl + 1
  FROM bom_closure c
    JOIN material_bom_item b ON b.material_no = c.node_no
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
    AND c.lvl < 10                          -- 深度上限，防环
), bom_closure_d AS (
  -- 🔒 (root,node) 唯一化：多条边/多层路径到达同一子件时只保留一行(取最浅层)。
  --    漏这层 → 平铺页签行数成倍复制、页签小计翻倍(task-0726 §3.8.3 铁律3)。
  SELECT root_no, node_no, MIN(lvl) AS lvl FROM bom_closure GROUP BY root_no, node_no
)
SELECT
  COALESCE(cl.root_no, ebi.material_no) AS hf_part_no,
  ebi.material_no AS _销售料号,
  ebi.material_part_no AS _料号,                   -- 材质料号(partNoField)
  COALESCE(mr.name, mm2.material_name) AS _料件,   -- 材质名：逐行取自本行 material_part_no
  ebi.component_usage_type AS _产出类型,
  ebi.seq_no AS _项次,
  ebi.component_no AS _元素,
  ebi.content AS _组成含量,
  ebi.scrap_rate AS _损耗率,
  ebi.composition_qty AS _毛重,
  ebi.issue_unit AS _毛用量单位,
  cep.unit_price AS 元素单价,                       -- 价格策略列：别名逐字、不加 _
  cep.currency AS 货币
FROM element_bom_item ebi
  LEFT JOIN bom_closure_d cl ON cl.node_no = ebi.material_no
  LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
  LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
  LEFT JOIN f_material_element_price(:customerCode, :priceBaseDate) cep
         ON cep.element_code = ebi.component_no     -- LEFT JOIN + 元素符号键(硬约束 1/2)
         AND cep.material_no = COALESCE(cl.root_no, ebi.material_no)
WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
ORDER BY COALESCE(cl.lvl, 0), ebi.material_no, ebi.material_part_no, ebi.seq_no
$tpl$,
       updated_at = now()
  FROM component c
 WHERE c.id = csv.component_id
   AND c.code = 'COMP-0130'
   AND csv.sql_template NOT LIKE '%f_material_element_price%';

-- 【COMP-0130 回滚参考】迁移前 sql_template 原文（逐字保留，回滚时整段替回即可）：
-- -- 材料成本(材质元素, 平铺契约: hf_part_no + :customerCode; 元素单价接客户价格策略)
-- -- 「销售料号」= 所属销售料号(ebi.material_no)：闭包后主件与子件的材质行同页签共存，靠本列区分归属
-- --   (task-0726 §3.8.4)；它必须同时在 rowKeyFields 里，否则撞键 → 编辑串行/末值塌缩。
-- -- 「料号」= 材质料号(ebi.material_part_no) = 本页签语义层级的料号(§3.4 第二步)。
-- -- task-0726 BOM 闭包: hf_part_no 输出「闭包根料号」, 使子件的数据也进入本页签。
-- -- ⚠️ 铁律1(§3.8.3): 白名单表必须在顶层 FROM，不能写成 FROM bom_closure JOIN <白名单表>，
-- --          否则 QuotePendingRewriter 主位表探测退化选中 CTE base case 的表 → 锚点注入报
-- --          "each UNION query must have the same number of columns"。
-- -- ⚠️ 铁律2: 必须 LEFT JOIN bom_closure_d + COALESCE 兜底 —— 无任何 BOM 行的单料产品不在闭包里，
-- --          写成 INNER JOIN 或直取 cl.root_no 会让这类产品的页签整个空白。
-- WITH RECURSIVE bom_closure AS (
--   SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl
--   FROM material_bom_item b
--   WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
--   UNION            -- ⚠️ 用 UNION 去重，不能用 UNION ALL：同一对父子常并存多条 BOM 边
--                    --    (RECIPE + ASSEMBLY 各一条) → UNION ALL 会生出重复路径
--   SELECT c.root_no, b.component_no, c.lvl + 1
--   FROM bom_closure c
--     JOIN material_bom_item b ON b.material_no = c.node_no
--   WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
--     AND c.lvl < 10                          -- 深度上限，防环
-- ), bom_closure_d AS (
--   -- 🔒 (root,node) 唯一化：多条边/多层路径到达同一子件时只保留一行(取最浅层)。
--   --    漏这层 → 平铺页签行数成倍复制、页签小计翻倍(task-0726 §3.8.3 铁律3)。
--   SELECT root_no, node_no, MIN(lvl) AS lvl FROM bom_closure GROUP BY root_no, node_no
-- )
-- SELECT
--   COALESCE(cl.root_no, ebi.material_no) AS hf_part_no,
--   ebi.material_no AS _销售料号,
--   ebi.material_part_no AS _料号,                   -- 材质料号(partNoField)
--   COALESCE(mr.name, mm2.material_name) AS _料件,   -- 材质名：逐行取自本行 material_part_no
--   ebi.component_usage_type AS _产出类型,
--   ebi.seq_no AS _项次,
--   ebi.component_no AS _元素,
--   ebi.content AS _组成含量,
--   ebi.scrap_rate AS _损耗率,
--   ebi.composition_qty AS _毛重,
--   ebi.issue_unit AS _毛用量单位,
--   cep.unit_price AS 元素单价,                       -- 价格策略列：别名逐字、不加 _
--   cep.currency AS 货币
-- FROM element_bom_item ebi
--   LEFT JOIN bom_closure_d cl ON cl.node_no = ebi.material_no
--   LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
--   LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
--   LEFT JOIN f_customer_element_price(:customerCode, :priceBaseDate) cep
--          ON cep.element_code = ebi.component_no     -- LEFT JOIN + 元素符号键(硬约束 1/2)
-- WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
-- ORDER BY COALESCE(cl.lvl, 0), ebi.material_no, ebi.material_part_no, ebi.seq_no

-- -----------------------------------------------------------------------------
-- COMP-0133（mc_view） · hf_part_no 形态：CLOSURE（COALESCE(cl.root_no, ebi.material_no)）
-- -----------------------------------------------------------------------------
UPDATE component_sql_view csv
   SET sql_template = $tpl$-- ============================================================================
-- 组件：材料成本（施耐德-1）   SQL 视图名：mc_view   驱动路径：$mc_view
-- requiredVariables: ["customerCode", "priceBaseDate"]   scope: COMPONENT
--
-- 2026-07-28 修订：只改 `_产出类型` 一处（原绑 ebi.component_usage_type 恒为空），
--                  其余逐字未动。详见 dev-docs/rule-0724-组件模板配置/报价侧.md §7.2.1。
-- ============================================================================
-- 材料成本(材质元素, 平铺契约: hf_part_no + :customerCode; 元素单价接客户价格策略)
-- 「销售料号」= 所属销售料号(ebi.material_no)：闭包后主件与子件的材质行同页签共存，靠本列区分归属
--   (task-0726 §3.8.4)；它必须同时在 rowKeyFields 里，否则撞键 → 编辑串行/末值塌缩。
-- 「料号」= 材质料号(ebi.material_part_no) = 本页签语义层级的料号(§3.4 第二步)。
-- task-0726 BOM 闭包: hf_part_no 输出「闭包根料号」, 使子件的数据也进入本页签。
-- ⚠️ 铁律1(§3.8.3): 白名单表必须在顶层 FROM，不能写成 FROM bom_closure JOIN <白名单表>，
--          否则 QuotePendingRewriter 主位表探测退化选中 CTE base case 的表 → 锚点注入报
--          "each UNION query must have the same number of columns"。
-- ⚠️ 铁律2: 必须 LEFT JOIN bom_closure_d + COALESCE 兜底 —— 无任何 BOM 行的单料产品不在闭包里，
--          写成 INNER JOIN 或直取 cl.root_no 会让这类产品的页签整个空白。
WITH RECURSIVE bom_closure AS (
  SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl
  FROM material_bom_item b
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
  UNION            -- ⚠️ 用 UNION 去重，不能用 UNION ALL：同一对父子常并存多条 BOM 边
                   --    (RECIPE + ASSEMBLY 各一条) → UNION ALL 会生出重复路径
  SELECT c.root_no, b.component_no, c.lvl + 1
  FROM bom_closure c
    JOIN material_bom_item b ON b.material_no = c.node_no
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
    AND c.lvl < 10                          -- 深度上限，防环
), bom_closure_d AS (
  -- 🔒 (root,node) 唯一化：多条边/多层路径到达同一子件时只保留一行(取最浅层)。
  --    漏这层 → 平铺页签行数成倍复制、页签小计翻倍(task-0726 §3.8.3 铁律3)。
  SELECT root_no, node_no, MIN(lvl) AS lvl FROM bom_closure GROUP BY root_no, node_no
)
SELECT
  COALESCE(cl.root_no, ebi.material_no) AS hf_part_no,
  ebi.material_no AS _销售料号,
  ebi.material_part_no AS _料号,                   -- 材质料号(partNoField)
  COALESCE(mr.name, mm2.material_name) AS _料件,   -- 材质名：逐行取自本行 material_part_no
  -- 「产出类型」= 物料BOM 的「产出料号类型」(MaterialBomMergeHandler:148 → material_bom_item.component_usage_type)。
  -- ⚠️ 不在 element_bom_item 上：该表的同名列无任何写入路径，绑它恒为空。
  -- ⚠️ 必须用相关标量子查询而非 LEFT JOIN：material_bom_item 是版本化表(§3.6)，
  --    且同一对父子常并存 RECIPE+ASSEMBLY 多条边(§3.8.3 铁律3) → JOIN 会让材料成本行倍增。
  (SELECT t.component_usage_type
     FROM material_bom_item t
    WHERE t.system_type  = 'QUOTE' AND t.is_current
      AND t.customer_no  = :customerCode
      AND t.material_no  = ebi.material_no
      AND t.component_no = ebi.material_part_no
    ORDER BY CASE WHEN t.characteristic = 'RECIPE' THEN 0 ELSE 1 END, t.characteristic
    LIMIT 1) AS _产出类型,
  ebi.seq_no AS _项次,
  ebi.component_no AS _元素,
  ebi.content AS _组成含量,
  ebi.scrap_rate AS _损耗率,
  ebi.composition_qty AS _毛重,
  ebi.issue_unit AS _毛用量单位,
  cep.unit_price AS 元素单价,                       -- 价格策略列：别名逐字、不加 _
  cep.currency AS 货币
FROM element_bom_item ebi
  LEFT JOIN bom_closure_d cl ON cl.node_no = ebi.material_no
  LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
  LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
  LEFT JOIN f_material_element_price(:customerCode, :priceBaseDate) cep
         ON cep.element_code = ebi.component_no     -- LEFT JOIN + 元素符号键(硬约束 1/2)
         AND cep.material_no = COALESCE(cl.root_no, ebi.material_no)
WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
ORDER BY COALESCE(cl.lvl, 0), ebi.material_no, ebi.material_part_no, ebi.seq_no
$tpl$,
       updated_at = now()
  FROM component c
 WHERE c.id = csv.component_id
   AND c.code = 'COMP-0133'
   AND csv.sql_template NOT LIKE '%f_material_element_price%';

-- 【COMP-0133 回滚参考】迁移前 sql_template 原文（逐字保留，回滚时整段替回即可）：
-- -- ============================================================================
-- -- 组件：材料成本（施耐德-1）   SQL 视图名：mc_view   驱动路径：$mc_view
-- -- requiredVariables: ["customerCode", "priceBaseDate"]   scope: COMPONENT
-- --
-- -- 2026-07-28 修订：只改 `_产出类型` 一处（原绑 ebi.component_usage_type 恒为空），
-- --                  其余逐字未动。详见 dev-docs/rule-0724-组件模板配置/报价侧.md §7.2.1。
-- -- ============================================================================
-- -- 材料成本(材质元素, 平铺契约: hf_part_no + :customerCode; 元素单价接客户价格策略)
-- -- 「销售料号」= 所属销售料号(ebi.material_no)：闭包后主件与子件的材质行同页签共存，靠本列区分归属
-- --   (task-0726 §3.8.4)；它必须同时在 rowKeyFields 里，否则撞键 → 编辑串行/末值塌缩。
-- -- 「料号」= 材质料号(ebi.material_part_no) = 本页签语义层级的料号(§3.4 第二步)。
-- -- task-0726 BOM 闭包: hf_part_no 输出「闭包根料号」, 使子件的数据也进入本页签。
-- -- ⚠️ 铁律1(§3.8.3): 白名单表必须在顶层 FROM，不能写成 FROM bom_closure JOIN <白名单表>，
-- --          否则 QuotePendingRewriter 主位表探测退化选中 CTE base case 的表 → 锚点注入报
-- --          "each UNION query must have the same number of columns"。
-- -- ⚠️ 铁律2: 必须 LEFT JOIN bom_closure_d + COALESCE 兜底 —— 无任何 BOM 行的单料产品不在闭包里，
-- --          写成 INNER JOIN 或直取 cl.root_no 会让这类产品的页签整个空白。
-- WITH RECURSIVE bom_closure AS (
--   SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl
--   FROM material_bom_item b
--   WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
--   UNION            -- ⚠️ 用 UNION 去重，不能用 UNION ALL：同一对父子常并存多条 BOM 边
--                    --    (RECIPE + ASSEMBLY 各一条) → UNION ALL 会生出重复路径
--   SELECT c.root_no, b.component_no, c.lvl + 1
--   FROM bom_closure c
--     JOIN material_bom_item b ON b.material_no = c.node_no
--   WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
--     AND c.lvl < 10                          -- 深度上限，防环
-- ), bom_closure_d AS (
--   -- 🔒 (root,node) 唯一化：多条边/多层路径到达同一子件时只保留一行(取最浅层)。
--   --    漏这层 → 平铺页签行数成倍复制、页签小计翻倍(task-0726 §3.8.3 铁律3)。
--   SELECT root_no, node_no, MIN(lvl) AS lvl FROM bom_closure GROUP BY root_no, node_no
-- )
-- SELECT
--   COALESCE(cl.root_no, ebi.material_no) AS hf_part_no,
--   ebi.material_no AS _销售料号,
--   ebi.material_part_no AS _料号,                   -- 材质料号(partNoField)
--   COALESCE(mr.name, mm2.material_name) AS _料件,   -- 材质名：逐行取自本行 material_part_no
--   -- 「产出类型」= 物料BOM 的「产出料号类型」(MaterialBomMergeHandler:148 → material_bom_item.component_usage_type)。
--   -- ⚠️ 不在 element_bom_item 上：该表的同名列无任何写入路径，绑它恒为空。
--   -- ⚠️ 必须用相关标量子查询而非 LEFT JOIN：material_bom_item 是版本化表(§3.6)，
--   --    且同一对父子常并存 RECIPE+ASSEMBLY 多条边(§3.8.3 铁律3) → JOIN 会让材料成本行倍增。
--   (SELECT t.component_usage_type
--      FROM material_bom_item t
--     WHERE t.system_type  = 'QUOTE' AND t.is_current
--       AND t.customer_no  = :customerCode
--       AND t.material_no  = ebi.material_no
--       AND t.component_no = ebi.material_part_no
--     ORDER BY CASE WHEN t.characteristic = 'RECIPE' THEN 0 ELSE 1 END, t.characteristic
--     LIMIT 1) AS _产出类型,
--   ebi.seq_no AS _项次,
--   ebi.component_no AS _元素,
--   ebi.content AS _组成含量,
--   ebi.scrap_rate AS _损耗率,
--   ebi.composition_qty AS _毛重,
--   ebi.issue_unit AS _毛用量单位,
--   cep.unit_price AS 元素单价,                       -- 价格策略列：别名逐字、不加 _
--   cep.currency AS 货币
-- FROM element_bom_item ebi
--   LEFT JOIN bom_closure_d cl ON cl.node_no = ebi.material_no
--   LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
--   LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
--   LEFT JOIN f_customer_element_price(:customerCode, :priceBaseDate) cep
--          ON cep.element_code = ebi.component_no     -- LEFT JOIN + 元素符号键(硬约束 1/2)
-- WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
-- ORDER BY COALESCE(cl.lvl, 0), ebi.material_no, ebi.material_part_no, ebi.seq_no

-- =============================================================================
-- 自检提示（改完必须做，非本迁移脚本自动执行）：
--   1. touch 一个 java 文件强制 Quarkus 重启（进程级缓存）。
--   2. curl 一个含料号的 expand-driver 端点，确认返回单值元素单价（不是数组 / 不是 NULL）。
--   3. 至少验证 1 个平铺视图 + 1 个闭包视图，比对迁移前后取到的元素单价数值一致（回归）。
-- =============================================================================
