-- ============================================================================
-- task-260907 · 第二段 · B-1 —— 13 张 ds_quote_* 带版本主表各配一张 `_record` 快照表
--
-- 依据：dev-docs/task-260907-报价导入建单切ds新表/task-260907-record层与核价回填/
--         需求文档.md（AC-1 / AC-2 / AC-3 / AC-4）+ backtask.md B-1
-- 服务的 AC：AC-1（表建成且与主表同构）· AC-3（extend_column）· AC-4（element_price）
--
-- 🚫 本迁移只新增表，不删改任何既有表（CLAUDE.md §3.2）。
--
-- ⛔⛔ 本文件**尚未取迁移号、尚未应用**。落库前必须由主线做两件事：
--   ① 取号 = max(db/migration 目录最大号, 共享库 flyway_schema_history 最大号) + 1，
--      **在落库那一刻实取**（两个都不能单独信；至少四条线在抢同一个移动靶）；
--   ② 把本文件从 db/migration-pending-260907/ **移动**到 db/migration/ 并改名成
--      V<号>__task260907_ds_quote_record_tables.sql。
--   放在 migration-pending-260907/ 而不是 migration/ 是刻意的：Flyway 的
--   quarkus.flyway.locations=db/migration 会**递归**扫描该目录，占位名 `V___` 无法解析成
--   版本号 ⇒ 放进去会让所有人的服务启动失败。
--
-- ✅ customer_no 已由上游 `dev-docs/task-260907-报价侧加客户维度/` 的 A0-1 裁决：
--    varchar(20) NOT NULL、无默认值、取值口径 = customer.code。本文件已按此建列。
--    ⚠️ **但上游那条主表 DDL 截至本文件写成时尚未落库**（实测 0 张带版本表已有 customer_no）
--    ⇒ 本文件的应用时序仍受 backtask 硬时序 1 约束，见 README.md。
--
-- 【表结构约定】（与 _history 范式对齐）
--   id                   bigserial PK    —— 本表自增
--   quotation_id         uuid NOT NULL   —— 来源报价单
--   origin_id            bigint 可空     —— 拍快照时主表那一行的 id（A0-1 主锚）
--                                          可空：报价单里手工新增的行在主表没有对应行
--   base_row_fingerprint char(64) 可空   —— 拍快照时主表那一行的整行指纹（A0-1 兜底锚）
--   base_version_no      integer NOT NULL DEFAULT 0 —— 拍快照时主表该组 version_no；无该组则 0
--   <主表全部业务列>       类型与可空性逐列与主表一致（AC-1②）
--   customer_no          varchar(20) NOT NULL —— 客户编号（= customer.code），与主表逐字一致（D-27）
--   extend_column        jsonb           —— 主表对不齐的字段（自定义列/公式列/常量列），
--                                          D-5：不回填、不参与升版与比对
--   element_price        numeric(26,12)  —— 仅物料BOM / 物料与元素BOM 两张（S-3 / AC-4），
--                                          🚫 不进主表（D-6）
--   系统列                与主表一致：source / created_at / created_by / updated_at / updated_by
--
-- 🚫 **不建 version_no / row_fingerprint** —— `_record` 是报价单快照，不是版本化主数据；
--    升版判定一律由 VersionedGroupWriter 在主表上做（AC-9）。
-- 🚫 **免版本三表不建 `_record`**（ds_quote_material / ds_quote_customer_part /
--    ds_quote_plating_scheme）—— 用户原话「这三张无版本的表定义的就是不需要回填的」（D-8）。
--    判据是「有 version_no 列」，不是写死表名（表会增）。
-- ============================================================================

-- ── ds_quote_material_bom_record ──────────────────────────────
CREATE TABLE ds_quote_material_bom_record (
    id                        bigserial      PRIMARY KEY,
    quotation_id              uuid           NOT NULL,
    origin_id                 bigint,
    base_row_fingerprint      char(64),
    base_version_no           integer        NOT NULL DEFAULT 0,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    input_material_no         varchar(128),
    unit_weight               numeric(26,12),
    output_material_type      varchar(128),
    component_qty             numeric(26,12),
    gross_weight              numeric(26,12),
    net_weight                numeric(26,12),
    weight_unit               varchar(128),
    material_ratio            numeric(26,12),
    loss_rate                 numeric(26,12),
    defect_rate               numeric(26,12),
    extend_column             jsonb,
    element_price             numeric(26,12),
    -- 上游 task-260907-报价侧加客户维度 A0-1 已裁决（2026-09-07 主线复核实测转达）：
    --   customer_no varchar(20) NOT NULL，无默认值，取值口径 = customer.code（如 CUST-0001）。
    --   与 ds_quote_material_bom.customer_no 的列定义**逐字一致**（D-27）。
    --   ⚠️ 上游那条 DDL 落库前，主表尚无此列；本表有、主表无是允许的过渡态
    --      —— `_record` 由本段自己写入，不经 VersionedGroupWriter。
    customer_no               varchar(20)    NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'QUOTE_DRAFT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_quote_material_bom_record_quotation ON ds_quote_material_bom_record (quotation_id);
CREATE INDEX idx_quote_material_bom_record_axis_quotation ON ds_quote_material_bom_record (customer_no, material_no, quotation_id);
COMMENT ON TABLE ds_quote_material_bom_record IS '报价单快照（_record）· 由 saveDraft 覆盖式写入，核价通过时按列级 patch 回填主表';
COMMENT ON COLUMN ds_quote_material_bom_record.origin_id IS '拍快照时主表那一行的 id（A0-1 主锚；手工新增行为 NULL）';
COMMENT ON COLUMN ds_quote_material_bom_record.base_row_fingerprint IS '拍快照时主表那一行的 row_fingerprint（A0-1 跨版兜底锚）';
COMMENT ON COLUMN ds_quote_material_bom_record.base_version_no IS '拍快照时主表该轴值组的 version_no；主表当时无该组则 0';
COMMENT ON COLUMN ds_quote_material_bom_record.extend_column IS '主表对不齐的字段（自定义列/公式列/常量列）。D-5：不回填、不参与升版与比对';
COMMENT ON COLUMN ds_quote_material_bom_record.element_price IS '建单时刻的元素实时价快照（S-3）。D-6：不进主表';

-- ── ds_quote_element_bom_record ──────────────────────────────
CREATE TABLE ds_quote_element_bom_record (
    id                        bigserial      PRIMARY KEY,
    quotation_id              uuid           NOT NULL,
    origin_id                 bigint,
    base_row_fingerprint      char(64),
    base_version_no           integer        NOT NULL DEFAULT 0,
    material_no               varchar(128)   NOT NULL,
    material_part_no          varchar(128),
    item_seq                  integer,
    element_code              varchar(128),
    content_pct               numeric(26,12),
    loss_rate                 numeric(26,12),
    gross_usage               numeric(26,12),
    gross_usage_unit          varchar(128),
    net_usage                 numeric(26,12),
    net_usage_unit            varchar(128),
    recovery_discount         numeric(26,12),
    recovery_qty              varchar(128),
    extend_column             jsonb,
    element_price             numeric(26,12),
    -- 上游 task-260907-报价侧加客户维度 A0-1 已裁决（2026-09-07 主线复核实测转达）：
    --   customer_no varchar(20) NOT NULL，无默认值，取值口径 = customer.code（如 CUST-0001）。
    --   与 ds_quote_element_bom.customer_no 的列定义**逐字一致**（D-27）。
    --   ⚠️ 上游那条 DDL 落库前，主表尚无此列；本表有、主表无是允许的过渡态
    --      —— `_record` 由本段自己写入，不经 VersionedGroupWriter。
    customer_no               varchar(20)    NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'QUOTE_DRAFT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_quote_element_bom_record_quotation ON ds_quote_element_bom_record (quotation_id);
CREATE INDEX idx_quote_element_bom_record_axis_quotation ON ds_quote_element_bom_record (customer_no, material_no, quotation_id);
COMMENT ON TABLE ds_quote_element_bom_record IS '报价单快照（_record）· 由 saveDraft 覆盖式写入，核价通过时按列级 patch 回填主表';
COMMENT ON COLUMN ds_quote_element_bom_record.origin_id IS '拍快照时主表那一行的 id（A0-1 主锚；手工新增行为 NULL）';
COMMENT ON COLUMN ds_quote_element_bom_record.base_row_fingerprint IS '拍快照时主表那一行的 row_fingerprint（A0-1 跨版兜底锚）';
COMMENT ON COLUMN ds_quote_element_bom_record.base_version_no IS '拍快照时主表该轴值组的 version_no；主表当时无该组则 0';
COMMENT ON COLUMN ds_quote_element_bom_record.extend_column IS '主表对不齐的字段（自定义列/公式列/常量列）。D-5：不回填、不参与升版与比对';
COMMENT ON COLUMN ds_quote_element_bom_record.element_price IS '建单时刻的元素实时价快照（S-3）。D-6：不进主表';

-- ── ds_quote_incoming_fixed_fee_record ──────────────────────────────
CREATE TABLE ds_quote_incoming_fixed_fee_record (
    id                        bigserial      PRIMARY KEY,
    quotation_id              uuid           NOT NULL,
    origin_id                 bigint,
    base_row_fingerprint      char(64),
    base_version_no           integer        NOT NULL DEFAULT 0,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    input_material_no         varchar(128),
    base_value                numeric(26,12),
    ratio_pct                 numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    follow_material_price     boolean,
    material_increase_ratio   numeric(26,12),
    material_increase_value   numeric(26,12),
    increase_currency         varchar(128),
    increase_unit             varchar(128),
    extend_column             jsonb,
    -- 上游 task-260907-报价侧加客户维度 A0-1 已裁决（2026-09-07 主线复核实测转达）：
    --   customer_no varchar(20) NOT NULL，无默认值，取值口径 = customer.code（如 CUST-0001）。
    --   与 ds_quote_incoming_fixed_fee.customer_no 的列定义**逐字一致**（D-27）。
    --   ⚠️ 上游那条 DDL 落库前，主表尚无此列；本表有、主表无是允许的过渡态
    --      —— `_record` 由本段自己写入，不经 VersionedGroupWriter。
    customer_no               varchar(20)    NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'QUOTE_DRAFT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_quote_incoming_fixed_fee_record_quotation ON ds_quote_incoming_fixed_fee_record (quotation_id);
CREATE INDEX idx_quote_incoming_fixed_fee_record_axis_quotation ON ds_quote_incoming_fixed_fee_record (customer_no, material_no, quotation_id);
COMMENT ON TABLE ds_quote_incoming_fixed_fee_record IS '报价单快照（_record）· 由 saveDraft 覆盖式写入，核价通过时按列级 patch 回填主表';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee_record.origin_id IS '拍快照时主表那一行的 id（A0-1 主锚；手工新增行为 NULL）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee_record.base_row_fingerprint IS '拍快照时主表那一行的 row_fingerprint（A0-1 跨版兜底锚）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee_record.base_version_no IS '拍快照时主表该轴值组的 version_no；主表当时无该组则 0';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee_record.extend_column IS '主表对不齐的字段（自定义列/公式列/常量列）。D-5：不回填、不参与升版与比对';

-- ── ds_quote_incoming_other_fee_record ──────────────────────────────
CREATE TABLE ds_quote_incoming_other_fee_record (
    id                        bigserial      PRIMARY KEY,
    quotation_id              uuid           NOT NULL,
    origin_id                 bigint,
    base_row_fingerprint      char(64),
    base_version_no           integer        NOT NULL DEFAULT 0,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    input_material_no         varchar(128),
    element_item_seq          integer,
    element_name              varchar(256),
    value                     numeric(26,12),
    ratio_pct                 numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    extend_column             jsonb,
    -- 上游 task-260907-报价侧加客户维度 A0-1 已裁决（2026-09-07 主线复核实测转达）：
    --   customer_no varchar(20) NOT NULL，无默认值，取值口径 = customer.code（如 CUST-0001）。
    --   与 ds_quote_incoming_other_fee.customer_no 的列定义**逐字一致**（D-27）。
    --   ⚠️ 上游那条 DDL 落库前，主表尚无此列；本表有、主表无是允许的过渡态
    --      —— `_record` 由本段自己写入，不经 VersionedGroupWriter。
    customer_no               varchar(20)    NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'QUOTE_DRAFT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_quote_incoming_other_fee_record_quotation ON ds_quote_incoming_other_fee_record (quotation_id);
CREATE INDEX idx_quote_incoming_other_fee_record_axis_quotation ON ds_quote_incoming_other_fee_record (customer_no, material_no, quotation_id);
COMMENT ON TABLE ds_quote_incoming_other_fee_record IS '报价单快照（_record）· 由 saveDraft 覆盖式写入，核价通过时按列级 patch 回填主表';
COMMENT ON COLUMN ds_quote_incoming_other_fee_record.origin_id IS '拍快照时主表那一行的 id（A0-1 主锚；手工新增行为 NULL）';
COMMENT ON COLUMN ds_quote_incoming_other_fee_record.base_row_fingerprint IS '拍快照时主表那一行的 row_fingerprint（A0-1 跨版兜底锚）';
COMMENT ON COLUMN ds_quote_incoming_other_fee_record.base_version_no IS '拍快照时主表该轴值组的 version_no；主表当时无该组则 0';
COMMENT ON COLUMN ds_quote_incoming_other_fee_record.extend_column IS '主表对不齐的字段（自定义列/公式列/常量列）。D-5：不回填、不参与升版与比对';

-- ── ds_quote_incoming_recovery_record ──────────────────────────────
CREATE TABLE ds_quote_incoming_recovery_record (
    id                        bigserial      PRIMARY KEY,
    quotation_id              uuid           NOT NULL,
    origin_id                 bigint,
    base_row_fingerprint      char(64),
    base_version_no           integer        NOT NULL DEFAULT 0,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    input_material_no         varchar(128),
    recovery_discount         numeric(26,12),
    recovery_value            numeric(26,12),
    recovery_source           varchar(256),
    extend_column             jsonb,
    -- 上游 task-260907-报价侧加客户维度 A0-1 已裁决（2026-09-07 主线复核实测转达）：
    --   customer_no varchar(20) NOT NULL，无默认值，取值口径 = customer.code（如 CUST-0001）。
    --   与 ds_quote_incoming_recovery.customer_no 的列定义**逐字一致**（D-27）。
    --   ⚠️ 上游那条 DDL 落库前，主表尚无此列；本表有、主表无是允许的过渡态
    --      —— `_record` 由本段自己写入，不经 VersionedGroupWriter。
    customer_no               varchar(20)    NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'QUOTE_DRAFT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_quote_incoming_recovery_record_quotation ON ds_quote_incoming_recovery_record (quotation_id);
CREATE INDEX idx_quote_incoming_recovery_record_axis_quotation ON ds_quote_incoming_recovery_record (customer_no, material_no, quotation_id);
COMMENT ON TABLE ds_quote_incoming_recovery_record IS '报价单快照（_record）· 由 saveDraft 覆盖式写入，核价通过时按列级 patch 回填主表';
COMMENT ON COLUMN ds_quote_incoming_recovery_record.origin_id IS '拍快照时主表那一行的 id（A0-1 主锚；手工新增行为 NULL）';
COMMENT ON COLUMN ds_quote_incoming_recovery_record.base_row_fingerprint IS '拍快照时主表那一行的 row_fingerprint（A0-1 跨版兜底锚）';
COMMENT ON COLUMN ds_quote_incoming_recovery_record.base_version_no IS '拍快照时主表该轴值组的 version_no；主表当时无该组则 0';
COMMENT ON COLUMN ds_quote_incoming_recovery_record.extend_column IS '主表对不齐的字段（自定义列/公式列/常量列）。D-5：不回填、不参与升版与比对';

-- ── ds_quote_self_process_fee_record ──────────────────────────────
CREATE TABLE ds_quote_self_process_fee_record (
    id                        bigserial      PRIMARY KEY,
    quotation_id              uuid           NOT NULL,
    origin_id                 bigint,
    base_row_fingerprint      char(64),
    base_version_no           integer        NOT NULL DEFAULT 0,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    input_material_no         varchar(128),
    operation_item_seq        integer,
    operation_no              varchar(128),
    value                     numeric(26,12),
    ratio_pct                 numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    extend_column             jsonb,
    -- 上游 task-260907-报价侧加客户维度 A0-1 已裁决（2026-09-07 主线复核实测转达）：
    --   customer_no varchar(20) NOT NULL，无默认值，取值口径 = customer.code（如 CUST-0001）。
    --   与 ds_quote_self_process_fee.customer_no 的列定义**逐字一致**（D-27）。
    --   ⚠️ 上游那条 DDL 落库前，主表尚无此列；本表有、主表无是允许的过渡态
    --      —— `_record` 由本段自己写入，不经 VersionedGroupWriter。
    customer_no               varchar(20)    NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'QUOTE_DRAFT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_quote_self_process_fee_record_quotation ON ds_quote_self_process_fee_record (quotation_id);
CREATE INDEX idx_quote_self_process_fee_record_axis_quotation ON ds_quote_self_process_fee_record (customer_no, material_no, quotation_id);
COMMENT ON TABLE ds_quote_self_process_fee_record IS '报价单快照（_record）· 由 saveDraft 覆盖式写入，核价通过时按列级 patch 回填主表';
COMMENT ON COLUMN ds_quote_self_process_fee_record.origin_id IS '拍快照时主表那一行的 id（A0-1 主锚；手工新增行为 NULL）';
COMMENT ON COLUMN ds_quote_self_process_fee_record.base_row_fingerprint IS '拍快照时主表那一行的 row_fingerprint（A0-1 跨版兜底锚）';
COMMENT ON COLUMN ds_quote_self_process_fee_record.base_version_no IS '拍快照时主表该轴值组的 version_no；主表当时无该组则 0';
COMMENT ON COLUMN ds_quote_self_process_fee_record.extend_column IS '主表对不齐的字段（自定义列/公式列/常量列）。D-5：不回填、不参与升版与比对';

-- ── ds_quote_finished_other_fee_record ──────────────────────────────
CREATE TABLE ds_quote_finished_other_fee_record (
    id                        bigserial      PRIMARY KEY,
    quotation_id              uuid           NOT NULL,
    origin_id                 bigint,
    base_row_fingerprint      char(64),
    base_version_no           integer        NOT NULL DEFAULT 0,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    element_name              varchar(256),
    value                     numeric(26,12),
    ratio_pct                 numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    extend_column             jsonb,
    -- 上游 task-260907-报价侧加客户维度 A0-1 已裁决（2026-09-07 主线复核实测转达）：
    --   customer_no varchar(20) NOT NULL，无默认值，取值口径 = customer.code（如 CUST-0001）。
    --   与 ds_quote_finished_other_fee.customer_no 的列定义**逐字一致**（D-27）。
    --   ⚠️ 上游那条 DDL 落库前，主表尚无此列；本表有、主表无是允许的过渡态
    --      —— `_record` 由本段自己写入，不经 VersionedGroupWriter。
    customer_no               varchar(20)    NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'QUOTE_DRAFT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_quote_finished_other_fee_record_quotation ON ds_quote_finished_other_fee_record (quotation_id);
CREATE INDEX idx_quote_finished_other_fee_record_axis_quotation ON ds_quote_finished_other_fee_record (customer_no, material_no, quotation_id);
COMMENT ON TABLE ds_quote_finished_other_fee_record IS '报价单快照（_record）· 由 saveDraft 覆盖式写入，核价通过时按列级 patch 回填主表';
COMMENT ON COLUMN ds_quote_finished_other_fee_record.origin_id IS '拍快照时主表那一行的 id（A0-1 主锚；手工新增行为 NULL）';
COMMENT ON COLUMN ds_quote_finished_other_fee_record.base_row_fingerprint IS '拍快照时主表那一行的 row_fingerprint（A0-1 跨版兜底锚）';
COMMENT ON COLUMN ds_quote_finished_other_fee_record.base_version_no IS '拍快照时主表该轴值组的 version_no；主表当时无该组则 0';
COMMENT ON COLUMN ds_quote_finished_other_fee_record.extend_column IS '主表对不齐的字段（自定义列/公式列/常量列）。D-5：不回填、不参与升版与比对';

-- ── ds_quote_sub_component_fee_record ──────────────────────────────
CREATE TABLE ds_quote_sub_component_fee_record (
    id                        bigserial      PRIMARY KEY,
    quotation_id              uuid           NOT NULL,
    origin_id                 bigint,
    base_row_fingerprint      char(64),
    base_version_no           integer        NOT NULL DEFAULT 0,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    sub_component_no          varchar(128),
    supplier_no               varchar(128),
    supplier_name             varchar(256),
    element_item_seq          integer,
    element_name              varchar(256),
    value                     numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    extend_column             jsonb,
    -- 上游 task-260907-报价侧加客户维度 A0-1 已裁决（2026-09-07 主线复核实测转达）：
    --   customer_no varchar(20) NOT NULL，无默认值，取值口径 = customer.code（如 CUST-0001）。
    --   与 ds_quote_sub_component_fee.customer_no 的列定义**逐字一致**（D-27）。
    --   ⚠️ 上游那条 DDL 落库前，主表尚无此列；本表有、主表无是允许的过渡态
    --      —— `_record` 由本段自己写入，不经 VersionedGroupWriter。
    customer_no               varchar(20)    NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'QUOTE_DRAFT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_quote_sub_component_fee_record_quotation ON ds_quote_sub_component_fee_record (quotation_id);
CREATE INDEX idx_quote_sub_component_fee_record_axis_quotation ON ds_quote_sub_component_fee_record (customer_no, material_no, quotation_id);
COMMENT ON TABLE ds_quote_sub_component_fee_record IS '报价单快照（_record）· 由 saveDraft 覆盖式写入，核价通过时按列级 patch 回填主表';
COMMENT ON COLUMN ds_quote_sub_component_fee_record.origin_id IS '拍快照时主表那一行的 id（A0-1 主锚；手工新增行为 NULL）';
COMMENT ON COLUMN ds_quote_sub_component_fee_record.base_row_fingerprint IS '拍快照时主表那一行的 row_fingerprint（A0-1 跨版兜底锚）';
COMMENT ON COLUMN ds_quote_sub_component_fee_record.base_version_no IS '拍快照时主表该轴值组的 version_no；主表当时无该组则 0';
COMMENT ON COLUMN ds_quote_sub_component_fee_record.extend_column IS '主表对不齐的字段（自定义列/公式列/常量列）。D-5：不回填、不参与升版与比对';

-- ── ds_quote_assembly_fee_record ──────────────────────────────
CREATE TABLE ds_quote_assembly_fee_record (
    id                        bigserial      PRIMARY KEY,
    quotation_id              uuid           NOT NULL,
    origin_id                 bigint,
    base_row_fingerprint      char(64),
    base_version_no           integer        NOT NULL DEFAULT 0,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    assembly_operation        varchar(128),
    assembly_fee              numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    defect_rate               numeric(26,12),
    extend_column             jsonb,
    -- 上游 task-260907-报价侧加客户维度 A0-1 已裁决（2026-09-07 主线复核实测转达）：
    --   customer_no varchar(20) NOT NULL，无默认值，取值口径 = customer.code（如 CUST-0001）。
    --   与 ds_quote_assembly_fee.customer_no 的列定义**逐字一致**（D-27）。
    --   ⚠️ 上游那条 DDL 落库前，主表尚无此列；本表有、主表无是允许的过渡态
    --      —— `_record` 由本段自己写入，不经 VersionedGroupWriter。
    customer_no               varchar(20)    NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'QUOTE_DRAFT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_quote_assembly_fee_record_quotation ON ds_quote_assembly_fee_record (quotation_id);
CREATE INDEX idx_quote_assembly_fee_record_axis_quotation ON ds_quote_assembly_fee_record (customer_no, material_no, quotation_id);
COMMENT ON TABLE ds_quote_assembly_fee_record IS '报价单快照（_record）· 由 saveDraft 覆盖式写入，核价通过时按列级 patch 回填主表';
COMMENT ON COLUMN ds_quote_assembly_fee_record.origin_id IS '拍快照时主表那一行的 id（A0-1 主锚；手工新增行为 NULL）';
COMMENT ON COLUMN ds_quote_assembly_fee_record.base_row_fingerprint IS '拍快照时主表那一行的 row_fingerprint（A0-1 跨版兜底锚）';
COMMENT ON COLUMN ds_quote_assembly_fee_record.base_version_no IS '拍快照时主表该轴值组的 version_no；主表当时无该组则 0';
COMMENT ON COLUMN ds_quote_assembly_fee_record.extend_column IS '主表对不齐的字段（自定义列/公式列/常量列）。D-5：不回填、不参与升版与比对';

-- ── ds_quote_assembly_fee_annual_record ──────────────────────────────
CREATE TABLE ds_quote_assembly_fee_annual_record (
    id                        bigserial      PRIMARY KEY,
    quotation_id              uuid           NOT NULL,
    origin_id                 bigint,
    base_row_fingerprint      char(64),
    base_version_no           integer        NOT NULL DEFAULT 0,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    assembly_operation        varchar(128),
    discount_seq              integer,
    discount_rate             numeric(26,12),
    fixed_discount_value      numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    discount_times            integer,
    extend_column             jsonb,
    -- 上游 task-260907-报价侧加客户维度 A0-1 已裁决（2026-09-07 主线复核实测转达）：
    --   customer_no varchar(20) NOT NULL，无默认值，取值口径 = customer.code（如 CUST-0001）。
    --   与 ds_quote_assembly_fee_annual.customer_no 的列定义**逐字一致**（D-27）。
    --   ⚠️ 上游那条 DDL 落库前，主表尚无此列；本表有、主表无是允许的过渡态
    --      —— `_record` 由本段自己写入，不经 VersionedGroupWriter。
    customer_no               varchar(20)    NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'QUOTE_DRAFT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_quote_assembly_fee_annual_record_quotation ON ds_quote_assembly_fee_annual_record (quotation_id);
CREATE INDEX idx_quote_assembly_fee_annual_record_axis_quotation ON ds_quote_assembly_fee_annual_record (customer_no, material_no, quotation_id);
COMMENT ON TABLE ds_quote_assembly_fee_annual_record IS '报价单快照（_record）· 由 saveDraft 覆盖式写入，核价通过时按列级 patch 回填主表';
COMMENT ON COLUMN ds_quote_assembly_fee_annual_record.origin_id IS '拍快照时主表那一行的 id（A0-1 主锚；手工新增行为 NULL）';
COMMENT ON COLUMN ds_quote_assembly_fee_annual_record.base_row_fingerprint IS '拍快照时主表那一行的 row_fingerprint（A0-1 跨版兜底锚）';
COMMENT ON COLUMN ds_quote_assembly_fee_annual_record.base_version_no IS '拍快照时主表该轴值组的 version_no；主表当时无该组则 0';
COMMENT ON COLUMN ds_quote_assembly_fee_annual_record.extend_column IS '主表对不齐的字段（自定义列/公式列/常量列）。D-5：不回填、不参与升版与比对';

-- ── ds_quote_plating_fee_record ──────────────────────────────
CREATE TABLE ds_quote_plating_fee_record (
    id                        bigserial      PRIMARY KEY,
    quotation_id              uuid           NOT NULL,
    origin_id                 bigint,
    base_row_fingerprint      char(64),
    base_version_no           integer        NOT NULL DEFAULT 0,
    material_no               varchar(128)   NOT NULL,
    plating_scheme_no         varchar(128),
    plating_version           varchar(128),
    plating_process_fee       numeric(26,12),
    plating_material_fee      numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    defect_rate               numeric(26,12),
    extend_column             jsonb,
    -- 上游 task-260907-报价侧加客户维度 A0-1 已裁决（2026-09-07 主线复核实测转达）：
    --   customer_no varchar(20) NOT NULL，无默认值，取值口径 = customer.code（如 CUST-0001）。
    --   与 ds_quote_plating_fee.customer_no 的列定义**逐字一致**（D-27）。
    --   ⚠️ 上游那条 DDL 落库前，主表尚无此列；本表有、主表无是允许的过渡态
    --      —— `_record` 由本段自己写入，不经 VersionedGroupWriter。
    customer_no               varchar(20)    NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'QUOTE_DRAFT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_quote_plating_fee_record_quotation ON ds_quote_plating_fee_record (quotation_id);
CREATE INDEX idx_quote_plating_fee_record_axis_quotation ON ds_quote_plating_fee_record (customer_no, material_no, quotation_id);
COMMENT ON TABLE ds_quote_plating_fee_record IS '报价单快照（_record）· 由 saveDraft 覆盖式写入，核价通过时按列级 patch 回填主表';
COMMENT ON COLUMN ds_quote_plating_fee_record.origin_id IS '拍快照时主表那一行的 id（A0-1 主锚；手工新增行为 NULL）';
COMMENT ON COLUMN ds_quote_plating_fee_record.base_row_fingerprint IS '拍快照时主表那一行的 row_fingerprint（A0-1 跨版兜底锚）';
COMMENT ON COLUMN ds_quote_plating_fee_record.base_version_no IS '拍快照时主表该轴值组的 version_no；主表当时无该组则 0';
COMMENT ON COLUMN ds_quote_plating_fee_record.extend_column IS '主表对不齐的字段（自定义列/公式列/常量列）。D-5：不回填、不参与升版与比对';

-- ── ds_quote_incoming_annual_record ──────────────────────────────
CREATE TABLE ds_quote_incoming_annual_record (
    id                        bigserial      PRIMARY KEY,
    quotation_id              uuid           NOT NULL,
    origin_id                 bigint,
    base_row_fingerprint      char(64),
    base_version_no           integer        NOT NULL DEFAULT 0,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    input_material_no         varchar(128),
    discount_seq              integer,
    discount_rate             numeric(26,12),
    fixed_discount_value      numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    discount_times            integer,
    extend_column             jsonb,
    -- 上游 task-260907-报价侧加客户维度 A0-1 已裁决（2026-09-07 主线复核实测转达）：
    --   customer_no varchar(20) NOT NULL，无默认值，取值口径 = customer.code（如 CUST-0001）。
    --   与 ds_quote_incoming_annual.customer_no 的列定义**逐字一致**（D-27）。
    --   ⚠️ 上游那条 DDL 落库前，主表尚无此列；本表有、主表无是允许的过渡态
    --      —— `_record` 由本段自己写入，不经 VersionedGroupWriter。
    customer_no               varchar(20)    NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'QUOTE_DRAFT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_quote_incoming_annual_record_quotation ON ds_quote_incoming_annual_record (quotation_id);
CREATE INDEX idx_quote_incoming_annual_record_axis_quotation ON ds_quote_incoming_annual_record (customer_no, material_no, quotation_id);
COMMENT ON TABLE ds_quote_incoming_annual_record IS '报价单快照（_record）· 由 saveDraft 覆盖式写入，核价通过时按列级 patch 回填主表';
COMMENT ON COLUMN ds_quote_incoming_annual_record.origin_id IS '拍快照时主表那一行的 id（A0-1 主锚；手工新增行为 NULL）';
COMMENT ON COLUMN ds_quote_incoming_annual_record.base_row_fingerprint IS '拍快照时主表那一行的 row_fingerprint（A0-1 跨版兜底锚）';
COMMENT ON COLUMN ds_quote_incoming_annual_record.base_version_no IS '拍快照时主表该轴值组的 version_no；主表当时无该组则 0';
COMMENT ON COLUMN ds_quote_incoming_annual_record.extend_column IS '主表对不齐的字段（自定义列/公式列/常量列）。D-5：不回填、不参与升版与比对';

-- ── ds_quote_annual_discount_record ──────────────────────────────
CREATE TABLE ds_quote_annual_discount_record (
    id                        bigserial      PRIMARY KEY,
    quotation_id              uuid           NOT NULL,
    origin_id                 bigint,
    base_row_fingerprint      char(64),
    base_version_no           integer        NOT NULL DEFAULT 0,
    material_no               varchar(128)   NOT NULL,
    discount_seq              integer,
    discount_rate             numeric(26,12),
    fixed_discount_value      numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    discount_times            integer,
    extend_column             jsonb,
    -- 上游 task-260907-报价侧加客户维度 A0-1 已裁决（2026-09-07 主线复核实测转达）：
    --   customer_no varchar(20) NOT NULL，无默认值，取值口径 = customer.code（如 CUST-0001）。
    --   与 ds_quote_annual_discount.customer_no 的列定义**逐字一致**（D-27）。
    --   ⚠️ 上游那条 DDL 落库前，主表尚无此列；本表有、主表无是允许的过渡态
    --      —— `_record` 由本段自己写入，不经 VersionedGroupWriter。
    customer_no               varchar(20)    NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'QUOTE_DRAFT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_quote_annual_discount_record_quotation ON ds_quote_annual_discount_record (quotation_id);
CREATE INDEX idx_quote_annual_discount_record_axis_quotation ON ds_quote_annual_discount_record (customer_no, material_no, quotation_id);
COMMENT ON TABLE ds_quote_annual_discount_record IS '报价单快照（_record）· 由 saveDraft 覆盖式写入，核价通过时按列级 patch 回填主表';
COMMENT ON COLUMN ds_quote_annual_discount_record.origin_id IS '拍快照时主表那一行的 id（A0-1 主锚；手工新增行为 NULL）';
COMMENT ON COLUMN ds_quote_annual_discount_record.base_row_fingerprint IS '拍快照时主表那一行的 row_fingerprint（A0-1 跨版兜底锚）';
COMMENT ON COLUMN ds_quote_annual_discount_record.base_version_no IS '拍快照时主表该轴值组的 version_no；主表当时无该组则 0';
COMMENT ON COLUMN ds_quote_annual_discount_record.extend_column IS '主表对不齐的字段（自定义列/公式列/常量列）。D-5：不回填、不参与升版与比对';

