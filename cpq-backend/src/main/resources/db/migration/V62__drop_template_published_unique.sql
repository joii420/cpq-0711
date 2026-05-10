-- V62: 撤销 V28 引入的 partial unique index
-- 原因: V28 约束(同 (customer_id, category_id) 仅一个 PUBLISHED 模板)与 PRD 多版本设计不一致。
-- PRD.md L644 / L744 明确:
--   - 每次发布在 Template 表中新建一条独立记录,而非更新原有记录
--   - 同一 template_series_id 可存在多条 PUBLISHED/ARCHIVED 记录
--   - 模板归档由用户主动操作,有依赖检查
--
-- V28 约束打破多版本设计 — 同 series 升级 v1.0→v1.1 必须把旧版降到 ARCHIVED 才能避免约束冲突,
-- 但 PRD 期望旧版 v1.0 保持 PUBLISHED 直到用户主动归档(避免正在使用 v1.0 的报价单立即失效)。
-- Ref: 2026-04-28 与 PM 对齐,确认这是 v4 阶段实施时的过度约束,回归 PRD 主文档语义。

DROP INDEX IF EXISTS uq_template_general_published;
DROP INDEX IF EXISTS uq_template_customer_published;

COMMENT ON TABLE template IS
    'V62: 撤销 V28 partial unique index,同 (customer_id, category_id) 可并存多个 PUBLISHED 版本';
