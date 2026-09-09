-- ============================================================================
-- task-260907 · 第二段 · B-3 —— 13 张带版本主表 + 13 张 _history 各加「来源报价单 id」列
--
-- 服务的 AC：AC-8（主表该组新行的来源报价单 id = 本次报价单 id）
--
-- ⛔⛔ 本文件**尚未取迁移号、尚未应用**。落库前必须由主线：
--   ① 取号 = max(db/migration 目录最大号, 共享库 flyway_schema_history 最大号) + 1，落库那一刻实取；
--   ② 从 db/migration-pending-260907/ **移动**到 db/migration/ 并改名
--      V<号>__task260907_ds_quote_source_quotation_id.sql。
--   ⚠️ 本文件必须排在 `_record` 建表迁移**之后**（两者无强依赖，但按 B-1→B-3 顺序编号更好读）。
--
-- 🚨 三条硬约束（改本文件前必读）
-- 1) 该列**绝不能声明成 ColumnDef**（D-28）：
--    · DatasetSheetParser:73 遍历 spec.persistedColumns()，每个 label 都必须出现在 Excel 表头，
--      缺一个整张 sheet 进 missingHeaders 拒收 ⇒ 所有**存量报价 Excel** 当场导不进去；
--    · 它的值来自「哪张报价单回填的」，不来自 Excel。
--    ⇒ 只能进 SheetDef 的**系统列**列表（与 SYSTEM_COLUMNS / VERSION_COLUMNS 同类），
--      见 SheetDef.SOURCE_QUOTATION_COLUMN + expectedTableColumns(boolean)。
-- 2) 该列**不参与 row_fingerprint** —— 它不是 ColumnDef，DatasetFingerprints.columnsOf(sheet)
--    只取 sheet.comparedColumns()，天然不会纳入。⚠️ 否则「同样的数据换一张单回填」会被判 UPGRADED，
--    产生虚假升版。
-- 3) 该列**只对报价侧生效**。核价两套（ds_cost_basic_* / ds_cost_detail_*）一列不加 ——
--    DatasetSchemaSelfCheck 要求列集**完全相等**，给核价加了它们的 Registry 不声明 ⇒
--    「多出未声明的列」⇒ **核价侧服务当场起不来**。
--
-- 🚫 只新增列，不删改任何既有列（CLAUDE.md §3.2）。列可空 —— 存量行本来就没有来源单。
-- ============================================================================

ALTER TABLE ds_quote_material_bom            ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_material_bom_history    ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_element_bom             ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_element_bom_history     ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_incoming_fixed_fee      ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_incoming_fixed_fee_history ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_incoming_other_fee      ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_incoming_other_fee_history ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_incoming_recovery       ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_incoming_recovery_history ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_self_process_fee        ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_self_process_fee_history ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_finished_other_fee      ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_finished_other_fee_history ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_sub_component_fee       ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_sub_component_fee_history ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_assembly_fee            ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_assembly_fee_history    ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_assembly_fee_annual     ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_assembly_fee_annual_history ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_plating_fee             ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_plating_fee_history     ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_incoming_annual         ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_incoming_annual_history ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_annual_discount         ADD COLUMN source_quotation_id uuid;
ALTER TABLE ds_quote_annual_discount_history ADD COLUMN source_quotation_id uuid;

COMMENT ON COLUMN ds_quote_material_bom.source_quotation_id IS '来源报价单 id（核价通过回填升版时写入；导入/维护端写入的行为 NULL）。不是 ColumnDef、不进 row_fingerprint';
COMMENT ON COLUMN ds_quote_material_bom_history.source_quotation_id IS '归档时主表该行的来源报价单 id（追溯：这个历史版本由哪张单产生）';
COMMENT ON COLUMN ds_quote_element_bom.source_quotation_id IS '来源报价单 id（核价通过回填升版时写入；导入/维护端写入的行为 NULL）。不是 ColumnDef、不进 row_fingerprint';
COMMENT ON COLUMN ds_quote_element_bom_history.source_quotation_id IS '归档时主表该行的来源报价单 id（追溯：这个历史版本由哪张单产生）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee.source_quotation_id IS '来源报价单 id（核价通过回填升版时写入；导入/维护端写入的行为 NULL）。不是 ColumnDef、不进 row_fingerprint';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee_history.source_quotation_id IS '归档时主表该行的来源报价单 id（追溯：这个历史版本由哪张单产生）';
COMMENT ON COLUMN ds_quote_incoming_other_fee.source_quotation_id IS '来源报价单 id（核价通过回填升版时写入；导入/维护端写入的行为 NULL）。不是 ColumnDef、不进 row_fingerprint';
COMMENT ON COLUMN ds_quote_incoming_other_fee_history.source_quotation_id IS '归档时主表该行的来源报价单 id（追溯：这个历史版本由哪张单产生）';
COMMENT ON COLUMN ds_quote_incoming_recovery.source_quotation_id IS '来源报价单 id（核价通过回填升版时写入；导入/维护端写入的行为 NULL）。不是 ColumnDef、不进 row_fingerprint';
COMMENT ON COLUMN ds_quote_incoming_recovery_history.source_quotation_id IS '归档时主表该行的来源报价单 id（追溯：这个历史版本由哪张单产生）';
COMMENT ON COLUMN ds_quote_self_process_fee.source_quotation_id IS '来源报价单 id（核价通过回填升版时写入；导入/维护端写入的行为 NULL）。不是 ColumnDef、不进 row_fingerprint';
COMMENT ON COLUMN ds_quote_self_process_fee_history.source_quotation_id IS '归档时主表该行的来源报价单 id（追溯：这个历史版本由哪张单产生）';
COMMENT ON COLUMN ds_quote_finished_other_fee.source_quotation_id IS '来源报价单 id（核价通过回填升版时写入；导入/维护端写入的行为 NULL）。不是 ColumnDef、不进 row_fingerprint';
COMMENT ON COLUMN ds_quote_finished_other_fee_history.source_quotation_id IS '归档时主表该行的来源报价单 id（追溯：这个历史版本由哪张单产生）';
COMMENT ON COLUMN ds_quote_sub_component_fee.source_quotation_id IS '来源报价单 id（核价通过回填升版时写入；导入/维护端写入的行为 NULL）。不是 ColumnDef、不进 row_fingerprint';
COMMENT ON COLUMN ds_quote_sub_component_fee_history.source_quotation_id IS '归档时主表该行的来源报价单 id（追溯：这个历史版本由哪张单产生）';
COMMENT ON COLUMN ds_quote_assembly_fee.source_quotation_id IS '来源报价单 id（核价通过回填升版时写入；导入/维护端写入的行为 NULL）。不是 ColumnDef、不进 row_fingerprint';
COMMENT ON COLUMN ds_quote_assembly_fee_history.source_quotation_id IS '归档时主表该行的来源报价单 id（追溯：这个历史版本由哪张单产生）';
COMMENT ON COLUMN ds_quote_assembly_fee_annual.source_quotation_id IS '来源报价单 id（核价通过回填升版时写入；导入/维护端写入的行为 NULL）。不是 ColumnDef、不进 row_fingerprint';
COMMENT ON COLUMN ds_quote_assembly_fee_annual_history.source_quotation_id IS '归档时主表该行的来源报价单 id（追溯：这个历史版本由哪张单产生）';
COMMENT ON COLUMN ds_quote_plating_fee.source_quotation_id IS '来源报价单 id（核价通过回填升版时写入；导入/维护端写入的行为 NULL）。不是 ColumnDef、不进 row_fingerprint';
COMMENT ON COLUMN ds_quote_plating_fee_history.source_quotation_id IS '归档时主表该行的来源报价单 id（追溯：这个历史版本由哪张单产生）';
COMMENT ON COLUMN ds_quote_incoming_annual.source_quotation_id IS '来源报价单 id（核价通过回填升版时写入；导入/维护端写入的行为 NULL）。不是 ColumnDef、不进 row_fingerprint';
COMMENT ON COLUMN ds_quote_incoming_annual_history.source_quotation_id IS '归档时主表该行的来源报价单 id（追溯：这个历史版本由哪张单产生）';
COMMENT ON COLUMN ds_quote_annual_discount.source_quotation_id IS '来源报价单 id（核价通过回填升版时写入；导入/维护端写入的行为 NULL）。不是 ColumnDef、不进 row_fingerprint';
COMMENT ON COLUMN ds_quote_annual_discount_history.source_quotation_id IS '归档时主表该行的来源报价单 id（追溯：这个历史版本由哪张单产生）';
