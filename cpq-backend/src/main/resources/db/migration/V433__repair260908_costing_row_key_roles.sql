-- =============================================================================
-- repair-260908 · B-2：核价侧行键（ROW_KEY 角色）修正
-- 服务 AC-R4 ~ AC-R8（问题说明.md §⑥）
--
-- 【为什么改】语义图种子（task-260819）是按**表的物理唯一约束**推 ROW_KEY 的，
--   而核价侧「物理唯一键」与「业务行身份」并不重合：
--     · 物料BOM 的「工序编号 / 使用特性」是**行的属性**，不是行的身份
--     · 电镀成本的「电镀方案编号 / 版本编号」同上
--     · 费用类**少标**了「要素名称」—— 同一料号下有多条不同要素的费用，不带它区分不开
--
-- 【为什么现在才改】task-260908 F-2 之前，行键列靠用户自己从字段面板拖（配错了可以不拖）；
--   F-2 之后行键列**自动带出且禁用不可移除** ⇒ 配置错误被放大到每个新配的组件。
--
-- 【范围三条硬约束】
--   1. WHERE 同时限定 dialect IN ('COST_BASIC','COST_DETAIL') 与 node_key
--      ⇒ 报价方言（QUOTE）一行都不动（AC-R5 反向钉住）。
--   2. 只增删 ROW_KEY **一个**角色，用数组增删（array_remove / ||），
--      🚫 不做 roles='{...}' 整体赋值 —— 那会把同行的 PART_NO / SORT 一起冲掉（AC-R6 反向钉住）。
--   3. 🚫 不动 component.row_key_fields（裁决 A0-3）—— 存量组件行键不自动变，
--      需用户重新保存组件才生效，这是知情选择（AC-R7 反向钉住）。
--
-- 【幂等】加角色前判 NOT ('ROW_KEY' = ANY(roles))，删角色前判 'ROW_KEY' = ANY(roles)。
--   本迁移可能在多个 worktree 的 mvnw test 中被重放，必须可重复执行。
--
-- 【非 DDL】纯 UPDATE，无 DROP / TRUNCATE / 无 WHERE 的写操作。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- ① 物料BOM：去掉「工序编号」「使用特性」的 ROW_KEY
--    目标行键 = 生产料号 | 组成料号
-- -----------------------------------------------------------------------------
UPDATE semantic_node_column c
   SET roles = array_remove(c.roles, 'ROW_KEY')
  FROM semantic_node n
 WHERE n.id = c.node_id
   AND n.dialect IN ('COST_BASIC', 'COST_DETAIL')
   AND n.node_key = 'MATERIAL_BOM'
   AND c.db_column IN ('operation_no', 'usage_characteristic')
   AND 'ROW_KEY' = ANY(c.roles);

-- -----------------------------------------------------------------------------
-- ② 成品比例费用：加「要素名称」
--    目标行键 = 生产料号 | 要素名称（两方言原来都缺）
-- -----------------------------------------------------------------------------
UPDATE semantic_node_column c
   SET roles = c.roles || '{ROW_KEY}'::text[]
  FROM semantic_node n
 WHERE n.id = c.node_id
   AND n.dialect IN ('COST_BASIC', 'COST_DETAIL')
   AND n.node_key = 'FINISHED_RATIO_FEE'
   AND c.db_column = 'element_name'
   AND NOT ('ROW_KEY' = ANY(c.roles));

-- -----------------------------------------------------------------------------
-- ③ 成品其他固定费用：加「要素名称」
--    COST_BASIC 原本**已经有**，靠上面的 NOT(... = ANY) 守卫自动跳过 ⇒ 只有 COST_DETAIL 被改。
--    （顺带修掉一处既有的两方言不一致，见 问题说明.md §③ 的 📌）
-- -----------------------------------------------------------------------------
UPDATE semantic_node_column c
   SET roles = c.roles || '{ROW_KEY}'::text[]
  FROM semantic_node n
 WHERE n.id = c.node_id
   AND n.dialect IN ('COST_BASIC', 'COST_DETAIL')
   AND n.node_key = 'FINISHED_FIXED_FEE'
   AND c.db_column = 'element_name'
   AND NOT ('ROW_KEY' = ANY(c.roles));

-- -----------------------------------------------------------------------------
-- ④ 来料其他费用 / ⑤ 来料其他固定费用：加「要素名称」
--    目标行键 = 生产料号 | 来料料号 | 要素名称
-- -----------------------------------------------------------------------------
UPDATE semantic_node_column c
   SET roles = c.roles || '{ROW_KEY}'::text[]
  FROM semantic_node n
 WHERE n.id = c.node_id
   AND n.dialect IN ('COST_BASIC', 'COST_DETAIL')
   AND n.node_key IN ('INCOMING_OTHER_FEE', 'INCOMING_OTHER_FIXED_FEE')
   AND c.db_column = 'element_name'
   AND NOT ('ROW_KEY' = ANY(c.roles));

-- -----------------------------------------------------------------------------
-- ⑥ 电镀成本：去掉「电镀方案编号」「版本编号」
--    目标行键 = 生产料号。（该节点当前只存在于 COST_DETAIL；WHERE 仍写两方言，
--    因为 COST_BASIC 没有匹配行 ⇒ 天然 no-op，将来补种也自动覆盖。）
-- -----------------------------------------------------------------------------
UPDATE semantic_node_column c
   SET roles = array_remove(c.roles, 'ROW_KEY')
  FROM semantic_node n
 WHERE n.id = c.node_id
   AND n.dialect IN ('COST_BASIC', 'COST_DETAIL')
   AND n.node_key = 'PLATING_COST'
   AND c.db_column IN ('plating_scheme_no', 'plating_version')
   AND 'ROW_KEY' = ANY(c.roles);

-- -----------------------------------------------------------------------------
-- ⑦ 模具工装成本：去掉「模具台账/工装编号」
--    目标行键 = 生产料号 | 工序编号
-- -----------------------------------------------------------------------------
UPDATE semantic_node_column c
   SET roles = array_remove(c.roles, 'ROW_KEY')
  FROM semantic_node n
 WHERE n.id = c.node_id
   AND n.dialect IN ('COST_BASIC', 'COST_DETAIL')
   AND n.node_key = 'TOOLING'
   AND c.db_column = 'tooling_no'
   AND 'ROW_KEY' = ANY(c.roles);
