-- task-0723 阶段 7：表改名 `_drop`（可逆软删除）
--
-- ⚠️⚠️⚠️ 本文件待技术总监三重验证（pg_stat 探针）后启用 —— 不要直接 touch java 触发应用 ⚠️⚠️⚠️
-- 见 dev-docs/task-0723-废弃业务与表清洗/backtask.md B7.3 + B8：
--   1. 静态审计（本 wave 已做：/usr/bin/grep -a + codegraph_impact，见交付说明）
--   2. 运行时探针（技术总监执行）：pg_stat_reset() → 跑一遍报价单/核价单/V6导入/模板渲染全量业务动作
--      → 查 pg_stat_user_tables.seq_scan+idx_scan，只有恒为 0 的表才真正安全改名
--   3. 应用本迁移后回归（技术总监执行）：touch java 重启 → 重跑同一套业务动作 → Quarkus 日志零
--      `relation "xxx" does not exist` → 跑 E2E quotation-flow.spec.ts A/B 同型对比
--
-- 版本号 V361 是本次编写时的占位（基于 flyway_schema_history MAX=359 +1，V360 为本任务阶段 5 文件占用）。
-- 应用前必须重新核对当前 MAX 版本号，避免与并发 worktree（尤其 task-0722 update-0724）撞号。
--
-- ⚠️⚠️⚠️ 重要偏离说明（技术总监务必先读）⚠️⚠️⚠️
-- backtask.md 原候选清单含 `costing_template`，本文件已将其排除！
-- 静态审计发现 costing/service/CostingSheetService.java#buildComparison()（task-0717 比对视图，
-- 活功能，backtask 明确要求保留）在方法体内直接原生 Panache 查询：
--   CostingTemplate.find("linkedTemplateId = ?1 AND status = 'PUBLISHED' ...", linkedTplId)
-- 即：比对视图用 costing_template.linked_template_id 反查历史 Excel 列定义（column/comparison_tag
-- 配置来源），是 buildComparison() 的合法调用路径的一部分，并非死代码残留。若改名 costing_template，
-- 该活功能会在遇到匹配的 linkedTemplateId 时抛 `relation "costing_template" does not exist`。
-- backtask 文档把 costing_template 判定为"17/17 零绑定"是指"0 张报价单以其为主模板"，
-- 不等于"0 运行时代码路径查询该表"——这是文档审计口径与代码实际引用的落差，已按 B8 第 1 重
-- 静态审计规则处理："发现残留消费方 → 该表暂不改名，在交付说明里列出"。
-- 技术总监如需确认，第 2 重 pg_stat 探针请把 costing_template 也纳入观测（原 B8 探针 SQL 已含此表），
-- 预期会看到非 0 scan（一旦有报价单/核价单打开比对视图）。
-- CostingTemplate.java 实体类本次保留未删；仅删除了它的管理端 CRUD 层
-- （CostingTemplateService/CostingTemplateResource/CostingTemplateDTO/CreateCostingTemplateRequest，
-- 对应孤儿页 /costing-templates 已下线，纯管理端 UI 与 buildComparison 的只读查询无关）。

-- ── mat_* V44（18 张，含 staging）────────────────────────────────────────────────────────────
ALTER TABLE mat_bom                          RENAME TO mat_bom_drop;
ALTER TABLE mat_bom_staging                  RENAME TO mat_bom_staging_drop;
ALTER TABLE mat_composite_process            RENAME TO mat_composite_process_drop;
ALTER TABLE mat_customer_part_mapping        RENAME TO mat_customer_part_mapping_drop;
ALTER TABLE mat_customer_part_mapping_staging RENAME TO mat_customer_part_mapping_staging_drop;
ALTER TABLE mat_fee                          RENAME TO mat_fee_drop;
ALTER TABLE mat_fee_staging                  RENAME TO mat_fee_staging_drop;
ALTER TABLE mat_part                         RENAME TO mat_part_drop;
ALTER TABLE mat_part_model                   RENAME TO mat_part_model_drop;
ALTER TABLE mat_part_source_file             RENAME TO mat_part_source_file_drop;
ALTER TABLE mat_part_staging                 RENAME TO mat_part_staging_drop;
ALTER TABLE mat_part_version_log             RENAME TO mat_part_version_log_drop;
ALTER TABLE mat_plating_fee                  RENAME TO mat_plating_fee_drop;
ALTER TABLE mat_plating_fee_staging          RENAME TO mat_plating_fee_staging_drop;
ALTER TABLE mat_plating_plan                 RENAME TO mat_plating_plan_drop;
ALTER TABLE mat_plating_plan_staging         RENAME TO mat_plating_plan_staging_drop;
ALTER TABLE mat_process                      RENAME TO mat_process_drop;
ALTER TABLE mat_process_staging              RENAME TO mat_process_staging_drop;

-- ⚠️ mat_process / mat_fee 额外风险点（技术总监请核实）：
-- com.cpq.versioning.query.VersioningQueryResource / VersioningQueryService（/api/cpq/versioning/*）
-- 动态按 tableName 参数原生查询 mat_process / mat_fee / plating_fee 三张表（历史版本对比/回看端点），
-- backtask.md / api.md 均未提及此 Resource 的存废，本 wave 未改动它。若该端点仍有前端调用方，
-- pg_stat 探针预期会看到 mat_process/mat_fee 非 0 scan——按 B8 规则，这两张表届时也应排除本批次改名，
-- 需先确认 VersioningQueryResource 的存废再处理（超出本次 backtask B1~B7 范围，请先与需求方确认）。

-- ── 旧核价引擎（16 张）────────────────────────────────────────────────────────────────────────
ALTER TABLE costing_element_price      RENAME TO costing_element_price_drop;
ALTER TABLE costing_exchange_rate      RENAME TO costing_exchange_rate_drop;
ALTER TABLE costing_material_price     RENAME TO costing_material_price_drop;
ALTER TABLE costing_price_version      RENAME TO costing_price_version_drop;
ALTER TABLE costing_part_design_cost   RENAME TO costing_part_design_cost_drop;
ALTER TABLE costing_part_element_bom   RENAME TO costing_part_element_bom_drop;
ALTER TABLE costing_part_material_bom  RENAME TO costing_part_material_bom_drop;
ALTER TABLE costing_part_plating       RENAME TO costing_part_plating_drop;
ALTER TABLE costing_part_plating_fee   RENAME TO costing_part_plating_fee_drop;
ALTER TABLE costing_part_process_cost  RENAME TO costing_part_process_cost_drop;
ALTER TABLE costing_part_quality_check RENAME TO costing_part_quality_check_drop;
ALTER TABLE costing_part_tooling_cost  RENAME TO costing_part_tooling_cost_drop;
ALTER TABLE costing_part_weight        RENAME TO costing_part_weight_drop;
ALTER TABLE costing_summary            RENAME TO costing_summary_drop;
ALTER TABLE costing_summary_override   RENAME TO costing_summary_override_drop;
ALTER TABLE costing_summary_result     RENAME TO costing_summary_result_drop;

-- ── 旧核价明细表（1）+ SchemaContext 电镀方案表（1）───────────────────────────────────────────
-- 注：costing_template 已排除，理由见文件顶部"重要偏离说明"。
ALTER TABLE costing_sheet    RENAME TO costing_sheet_drop;
ALTER TABLE plating_plan     RENAME TO plating_plan_drop;

-- ⚠️ plating_plan（3 行冻结）vs plating_fee（V6 活表）别搞混——只改 plating_plan，plating_fee 保留。
-- ⚠️ 改名后同样 touch java 重启清缓存。
-- 本任务不做真 DROP（`_drop` 表留在库里观察；真删是后续独立批次）。
