-- V363: costing_bom_tree_config 双侧 BOM 树递归 SQL 配置种子(COSTING + QUOTE 各一条 active)
-- spec: docs/RECORD.md [2026-07-25] 树递归SQL配置 / [2026-07-26] 本次登记
--
-- 背景: 该表自建表起全库无任何 INSERT 迁移种子, 配置只活在 DB 里 —— 这正是 task-0725
--       根因③(「costing_bom_tree_config 表空缺 usage=QUOTE 配置, 环境重建即丢」)。
--       cpq_db_0724 由空 schema 建库后该表 0 行, 报价单 BOM 页签会落「未配置生效的报价树
--       递归 SQL」哨兵。本迁移把双侧配置纳入版本控制, 新库/部署环境自带, 不再依赖人工补录。
--
-- 幂等: 每条都带 WHERE NOT EXISTS(该 usage 已有 active 行) 守卫 —— 已有配置的库(如手工
--       配过的旧库)重复应用时不插入、不覆盖, 也不与唯一索引
--       uq_bom_tree_config_active_per_usage (usage) WHERE is_active 冲突。
--
-- 契约(CostingTreeSqlValidator): 必须引用 :production_part_nos(text[]), 输出列必须逐字含
--       root_no / material_no / bom_version / parent_no / node_path 五列。
--
-- ⚠️ sql_template 内禁写 SQL 注释里带 :production_part_nos/:pq/:__vfPart/:__vfVer 的 token
--    (BomTreeRenderService.queryRecursive 的 TREE_PARAM 参数绑定按出现顺序配对; task-0725 T1
--     的 SqlTextMask 已屏蔽注释, 但配置侧仍按"不在模板里写这些 token 的注释"从严处理)。

-- ── 核价侧(usage=COSTING) ──────────────────────────────────────────────
-- 口径: material_bom_item + system_type='PRICING' + customer_no='_GLOBAL_' + :versionFilter 宏。
-- 为什么带 :versionFilter 宏: 这是 task-0713 核价单「版本切换」作用到 BOM 主树的唯一通道 ——
--   BomTreeRenderService.queryRecursive 对含宏的模板调 VersionFilterMacro.expandForExecution
--   展开成 :__vfPart/:__vfVer 双数组谓词(命中 override 取指定版本, 未命中退化为 is_current)。
--   改成纯 is_current 会让主树恒取当前版本, 版本切换只剩页签 $view 生效。
-- ⚠️ 代价(已知并接受): CostingTreeSqlValidator 保存期 dry-run 只把 :production_part_nos 替换成
--   ARRAY[]::text[], 不展开宏, 故这条 SQL 无法经 API/UI 保存(必被判「递归 SQL 无法执行」)。
--   要改它只能走迁移或直连 DB。这是选型时的显式取舍, 不是缺陷。

INSERT INTO costing_bom_tree_config (name, "usage", is_active, sql_template)
SELECT '核价BOM树-PRICING口径v1(versionFilter 版本感知)', 'COSTING', true, $tree$WITH RECURSIVE bom AS (
  SELECT
    p::text                                        AS root_no,
    p::text                                        AS material_no,
    (SELECT bv.bom_version::text
       FROM material_bom_item bv
      WHERE bv.material_no = p
        AND bv.customer_no = '_GLOBAL_'
        AND bv.system_type = 'PRICING'
        AND :versionFilter(bv.is_current, bv.bom_version, bv.material_no)
      LIMIT 1)                                     AS bom_version,
    NULL::text                                     AS parent_no,
    p::text                                        AS node_path
  FROM unnest(:production_part_nos) AS p

  UNION ALL

  SELECT
    b.root_no,
    ch.component_no::text                          AS material_no,
    (SELECT bv.bom_version::text
       FROM material_bom_item bv
      WHERE bv.material_no = ch.component_no
        AND bv.customer_no = '_GLOBAL_'
        AND bv.system_type = 'PRICING'
        AND :versionFilter(bv.is_current, bv.bom_version, bv.material_no)
      LIMIT 1)                                     AS bom_version,
    ch.material_no::text                           AS parent_no,
    (b.node_path || '/' || ch.component_no)::text  AS node_path
  FROM material_bom_item ch
  JOIN bom b ON ch.material_no = b.material_no
  WHERE ch.customer_no  = '_GLOBAL_'
    AND ch.system_type  = 'PRICING'
    AND :versionFilter(ch.is_current, ch.bom_version, ch.material_no)
    AND ch.component_no IS NOT NULL
) CYCLE material_no SET is_cyc USING cyc_path
SELECT root_no, material_no, bom_version, parent_no, node_path
FROM bom$tree$
WHERE NOT EXISTS (
  SELECT 1 FROM costing_bom_tree_config WHERE "usage" = 'COSTING' AND is_active
);

-- ── 报价侧(usage=QUOTE) ────────────────────────────────────────────────
-- 口径: material_bom_item + system_type='QUOTE' + is_current, 客户维度靠 _cust 自根传播
--   (TREE_PARAM 只注入 production_part_nos/__vfPart/__vfVer/pq, 框架不注入 :customerCode,
--    故根节点取该料号 QUOTE 侧 customer_no 字母序首个并沿递归向下传, 见 JOIN ... AND ch.customer_no=b._cust)。
-- 不带 :versionFilter 宏: 版本切换是核价侧专属能力(报价侧提交后 frozen), 报价树恒取 is_current;
--   顺带这条 SQL 因此可以正常经 API/UI 编辑保存。
-- 已知遗留(task-0721 follow-up, 非本迁移引入): 跨客户同成品仍按 customer_no 字母序取根客户,
--   彻底解决需框架注入 :customerCode。

INSERT INTO costing_bom_tree_config (name, "usage", is_active, sql_template)
SELECT '报价BOM树-QUOTE口径v1', 'QUOTE', true, $tree$WITH RECURSIVE bom AS (
  SELECT p::text AS root_no, p::text AS material_no,
    (SELECT bv.bom_version::text FROM material_bom_item bv WHERE bv.material_no=p AND bv.system_type='QUOTE' AND bv.is_current LIMIT 1) AS bom_version,
    NULL::text AS parent_no, p::text AS node_path,
    (SELECT bc.customer_no FROM material_bom_item bc WHERE bc.material_no=p AND bc.system_type='QUOTE' AND bc.is_current ORDER BY bc.customer_no LIMIT 1) AS _cust
  FROM unnest(:production_part_nos) AS p
  UNION ALL
  SELECT b.root_no, ch.component_no::text,
    (SELECT bv.bom_version::text FROM material_bom_item bv WHERE bv.material_no=ch.component_no AND bv.system_type='QUOTE' AND bv.is_current LIMIT 1),
    ch.material_no::text, (b.node_path||'/'||ch.component_no)::text, b._cust
  FROM material_bom_item ch JOIN bom b ON ch.material_no=b.material_no AND ch.customer_no=b._cust
  WHERE ch.system_type='QUOTE' AND ch.is_current AND ch.component_no IS NOT NULL
) CYCLE material_no SET is_cyc USING cyc_path
SELECT root_no, material_no, bom_version, parent_no, node_path FROM bom$tree$
WHERE NOT EXISTS (
  SELECT 1 FROM costing_bom_tree_config WHERE "usage" = 'QUOTE' AND is_active
);
