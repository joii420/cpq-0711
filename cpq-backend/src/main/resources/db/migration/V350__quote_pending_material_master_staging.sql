-- V350: task-0721 报价数据版本升级(B9) —— material_master 主档暂存表（方案甲）
-- spec: dev-docs/task-0721-报价升版逻辑/{需求说明.md §4.3 规则一「主档」, backtask.md B9}
--
-- 背景：material_master 五处写入点（Q18单重/Q02/Q04/Q13/MaterialBomMergeHandler）现状是
-- 无条件、立即 upsert 进 material_master（零 pending 感知），与 7 张版本化表"延迟生效"的
-- 设计不对称——即便报价单尚未核价通过，其导入产生的主档名称/规格/单重变更也已全局可见。
-- 本表挂报价侧导入的主档变更暂存，核价通过时（B5 同事务）覆盖式 upsert 进 material_master，
-- 报价单删除/重导时清理暂存（与 7 张版本化表 pending 行同生命周期）。
--
-- 唯一键 (quotation_id, material_no)：同一报价单对同一料号的多次登记（如 Q04 登记名称、
-- MaterialBomMergeHandler 又登记类型）在暂存表内合并为一行（MaterialMasterRepository 的
-- upsert 方法按列分别 COALESCE 合并，见 stageXxx 方法）。

CREATE TABLE pending_material_master_staging (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    quotation_id        uuid NOT NULL,
    material_no         varchar(20) NOT NULL,
    material_name       varchar(100),
    specification       varchar(100),
    dimension           varchar(100),
    old_material_no     varchar(50),
    material_type       varchar(50),
    usage_property      varchar(50),
    unit_weight         numeric(18,6),
    standard_unit       varchar(20),
    production_no       varchar(32),
    created_at          timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_by          uuid,
    CONSTRAINT uq_pmm_staging_quotation_material UNIQUE (quotation_id, material_no)
);

CREATE INDEX ix_pmm_staging_quotation ON pending_material_master_staging(quotation_id);
