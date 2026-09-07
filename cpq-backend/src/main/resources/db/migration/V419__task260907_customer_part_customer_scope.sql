-- V419__task260907_customer_part_customer_scope.sql
-- task-260907 · B-1 补丁（AC-2，用户 2026-09-07 裁决「加客户谓词过滤」）
--
-- 给 CUSTOMER_PART 节点加 fixed_predicate：customer_no = :customerCode。
-- SemanticCompiler#ensureLeftJoin 会把它拼进 LEFT JOIN 的 ON 子句，并把 customerCode
-- 登记进 requiredVariables：
--     LEFT JOIN ds_quote_customer_part dqcp
--            ON dqcp.material_no = dqm.material_no AND dqcp.customer_no = :customerCode
--
-- 🚫 **不动 V418**（已应用共享库且已合入 master，改名/改号/改内容撞 CLAUDE.md §3.2「契约销毁」红线），
--    因此本次用一条新迁移做 UPDATE，而不是回头改那一行 INSERT。
--
-- ⚠️⚠️ **它解决不了本任务实测到的那一例行放大，别当成「已修复」** ⚠️⚠️
--   2026-09-07 实测（cpq_db_0724）：
--       id=4456  T260907-M1  CUST-0004  customer_product_no=T260907-CP1
--       id=4457  T260907-M1  CUST-0004  customer_product_no=T260907-CP2
--       ⇒ ds_quote_material 49 行 --LEFT JOIN--> 50 行
--   这**两行是同一个客户**，加了客户谓词照样都在，49→50 的放大依然存在。
--   根因不是「跨客户串号」，而是表的唯一键 uq_ds_quote_customer_part(customer_no, customer_product_no)
--   本身就允许「一个 material_no 挂多个客户产品编号」——**1:N 是合法业务，不是脏数据**。
--   ⇒ 客户谓词消掉的是**跨客户**那一半（客户 A 的物料带出客户 B 的客户料号），
--     同客户多产品编号那一半仍然会放大。用户 2026-09-07 已知情裁决按此实现。
--
-- 🚦 行为变更（不是副作用，是这条裁决的已知代价）：
--   「主件」页签**一旦拖了客户料号侧的列**，其 requiredVariables 就多出 customerCode。
--   未拖客户料号列的存量组件不受影响 —— LOOKUP 边只在被选列引用时才落 JOIN
--   （SemanticCompiler#resolveColumn → ensureLeftJoin），产物与改动前逐字相同。

UPDATE semantic_node
   SET fixed_predicate = 'customer_no = :customerCode',
       note = note || E'\n【V419 / AC-2】按客户隔离：fixed_predicate = customer_no = :customerCode。'
              || '⚠️ 只消跨客户那一半；同一客户下一个 material_no 挂多个 customer_product_no 仍会放大行数'
              || '（表的唯一键就是 (customer_no, customer_product_no)，1:N 合法）。',
       updated_at = now(),
       updated_by = 'task-260907'
 WHERE node_key = 'CUSTOMER_PART'
   AND dialect  = 'QUOTE';

-- 影响面：1 行（semantic_node 的 (node_key, dialect) 是唯一约束）。
-- 可恢复性：把 fixed_predicate 置回 NULL 即还原，无数据丢失。
