-- task-260911 · B-1（AC-9 / AC-13）：语义图新增「行级维度」角色 ROW_SCOPE
--
-- 【它是什么】标记某个语义节点列「参与构成对端表的行粒度，且取值因报价明细行而异」。
--   编译器见到它 → 在 LEFT JOIN … ON 上生成 <别名>.<列> = ANY(:<列>s) 集合成员谓词；
--   分发层见到它 → 把该列纳入回分依据，每个明细行从合桶结果里挑属于自己的那一行。
--   ⇒ 「取数口径依赖行」与「整单只查一次（合桶）」同时成立。
--
-- 【加法式】全库现有角色只有 ROW_KEY / PART_NO / SORT / PART_NAME 四种；本迁移只对
--   CUSTOMER_PART(QUOTE).customer_product_no 这一列 array_append，既有值一个都不动。
--   未打该标记的列行为逐字节不变（AC-7）。
--
-- 【幂等】NOT ('ROW_SCOPE' = ANY(roles)) 守卫 —— 重复执行不会追加出 {ROW_SCOPE,ROW_SCOPE}。
--
-- ⚠️ semantic_node_column 的任何 UPDATE 都会让该行在 MVCC 下换页（堆顺序变）。
--    SemanticGraphLoader 已按 ORDER BY (nodeId, sortOrder) 加载（repair-260908 修），
--    故本次 UPDATE 不会像 V433 那样打乱取数配置器里的字段顺序。

UPDATE semantic_node_column c
SET roles      = array_append(c.roles, 'ROW_SCOPE'),
    updated_by = 'task-260911',
    updated_at = now()
FROM semantic_node n
WHERE c.node_id = n.id
  AND n.node_key = 'CUSTOMER_PART'
  AND n.dialect  = 'QUOTE'
  AND c.db_column = 'customer_product_no'
  AND NOT ('ROW_SCOPE' = ANY(c.roles));
