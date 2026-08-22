-- V392__task260819_reset_fake_assert_status.sql
-- 清掉 V388 种子里写死的假 assert_status（主线裁决 D-44，2026-08-21）。
--
-- 根因：V388 给 19 条边直接硬编码 assert_status='PASS'，但 SemanticGraphValidator 当时没有任何
-- 回写路径——PASS 只是种子文本，不代表任何校验真的跑过。实测证据：4 条 MANY_TO_ONE 边 PASS 但
-- assert_sample_rows 为空（断言压根没跑）；3 条 assert_sample_rows=4（<30）却是 PASS，违反 D-32
-- 的 THIN 判据。
--
-- 本迁移把 assert_status 全部重置为 'NA'（= 未校验，语义等价于"NULL"——列本身是 NOT NULL
-- DEFAULT 'NA'，不能存字面 SQL NULL）、assert_sample_rows 清空，不留假绿。真实值由：
--   ① POST/PUT /edges 保存时自动重算并写回（SemanticGraphService#recomputeAssertStatus）
--   ② 管理员随时可调 POST /config/semantic-graph/revalidate 全量重算
-- 两条路径产出，不再由种子文本伪装。

UPDATE semantic_edge
   SET assert_status = 'NA', assert_sample_rows = NULL, updated_at = now()
 WHERE status = 'ACTIVE';
