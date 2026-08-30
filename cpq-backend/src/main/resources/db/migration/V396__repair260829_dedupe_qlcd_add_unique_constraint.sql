-- repair-260829 B-7：saveDraft 批量路径曾静默重复写入 quotation_line_component_data
-- （问题说明.md ④4.9/⑤B-7——同一批数据被写两次，相隔 14 秒，后写的那批 tab_name/sort_order 缺失）。
-- 立项期实测（cpq_db_0724，2026-08-29）：全库 39,268 行中恰 6 组 (line_item_id, component_id)
-- 重复，全部集中在单张单 QT-20260807-0145 的 lineItem 76c33527-12a7-4610-a5ae-6d3fc83d9187。
--
-- ① 清理存量重复：每组保留「tab_name 非空」的那条；若组内多条皆非空（或皆为空），退化为保留
--    created_at 较早者。用窗口函数一次性通用处理，不写死具体 id/lineItem（其它环境如另有同类
--    脏数据，同一逻辑同样适用；本库实测该 DELETE 恰命中 6 行，已用 SELECT 预演见 test-report.md）。
WITH ranked AS (
    SELECT id,
           line_item_id,
           component_id,
           count(*) OVER (PARTITION BY line_item_id, component_id) AS grp_cnt,
           ROW_NUMBER() OVER (
               PARTITION BY line_item_id, component_id
               ORDER BY
                   CASE WHEN tab_name IS NULL OR tab_name = '' THEN 1 ELSE 0 END ASC,
                   created_at ASC,
                   id ASC
           ) AS rn
    FROM quotation_line_component_data
)
DELETE FROM quotation_line_component_data
WHERE id IN (SELECT id FROM ranked WHERE grp_cnt > 1 AND rn > 1);

-- ② 加唯一约束，防止同类重复写入再次静默发生（此后同 (line_item_id, component_id) 二次写入会
--    直接 DB 报错，而不是静默多一行——repair-260829 B-6 UPSERT 路径依赖本约束保证「结构判定」
--    不会因误判自己撞自己，见 QuotationService#payloadComponentIdSet 的调用方 AC-23）。
ALTER TABLE quotation_line_component_data
    ADD CONSTRAINT uq_qlcd_line_component UNIQUE (line_item_id, component_id);
