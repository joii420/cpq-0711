-- V349: task-0721 报价数据版本升级(B1) —— pending 列地基
-- spec: dev-docs/task-0721-报价升版逻辑/{需求说明.md §4.3 规则一/二, backtask.md B1}
--
-- 目标：7 张版本化 V6 表 + 占号表 material_customer_map 加 pending_quotation_id，
-- 使报价侧导入的数据可以先落 is_current=false + pending_quotation_id=本单（延迟生效），
-- 核价通过时再回填升版（B5，后续波次）。本迁移只加列+索引，不做存量数据回填/清理
-- （需求方 §6 已确认全清重导，存量 pending_quotation_id 默认 NULL = 已生效，语义安全）。
--
-- pending_supersedes：pending 行点名它将取代的旧 current 行 id（供 B3 视图改写"遮蔽"用，
-- 见 backtask B3.1 步骤 3）；占号表 material_customer_map 无历史概念（直接覆盖式 upsert），
-- 不需要 supersedes。

-- ── pending_quotation_id：7 张版本化表 + 占号表 ──────────────────────────
ALTER TABLE unit_price            ADD COLUMN pending_quotation_id uuid;
ALTER TABLE material_bom          ADD COLUMN pending_quotation_id uuid;
ALTER TABLE material_bom_item     ADD COLUMN pending_quotation_id uuid;
ALTER TABLE element_bom           ADD COLUMN pending_quotation_id uuid;
ALTER TABLE element_bom_item      ADD COLUMN pending_quotation_id uuid;
ALTER TABLE capacity              ADD COLUMN pending_quotation_id uuid;
ALTER TABLE plating_scheme        ADD COLUMN pending_quotation_id uuid;
ALTER TABLE material_customer_map ADD COLUMN pending_quotation_id uuid;

-- ── pending_supersedes：仅 7 张版本化表 ─────────────────────────────────
ALTER TABLE unit_price        ADD COLUMN pending_supersedes uuid[];
ALTER TABLE material_bom      ADD COLUMN pending_supersedes uuid[];
ALTER TABLE material_bom_item ADD COLUMN pending_supersedes uuid[];
ALTER TABLE element_bom       ADD COLUMN pending_supersedes uuid[];
ALTER TABLE element_bom_item  ADD COLUMN pending_supersedes uuid[];
ALTER TABLE capacity          ADD COLUMN pending_supersedes uuid[];
ALTER TABLE plating_scheme    ADD COLUMN pending_supersedes uuid[];

-- ── 部分索引：物化期 pending 感知改写高频按 pending_quotation_id 过滤 ────
CREATE INDEX ix_unit_price_pending        ON unit_price(pending_quotation_id)        WHERE pending_quotation_id IS NOT NULL;
CREATE INDEX ix_material_bom_pending      ON material_bom(pending_quotation_id)      WHERE pending_quotation_id IS NOT NULL;
CREATE INDEX ix_material_bom_item_pending ON material_bom_item(pending_quotation_id) WHERE pending_quotation_id IS NOT NULL;
CREATE INDEX ix_element_bom_pending       ON element_bom(pending_quotation_id)       WHERE pending_quotation_id IS NOT NULL;
CREATE INDEX ix_element_bom_item_pending  ON element_bom_item(pending_quotation_id)  WHERE pending_quotation_id IS NOT NULL;
CREATE INDEX ix_capacity_pending          ON capacity(pending_quotation_id)          WHERE pending_quotation_id IS NOT NULL;
CREATE INDEX ix_plating_scheme_pending    ON plating_scheme(pending_quotation_id)    WHERE pending_quotation_id IS NOT NULL;
CREATE INDEX ix_mcm_pending               ON material_customer_map(pending_quotation_id) WHERE pending_quotation_id IS NOT NULL;
