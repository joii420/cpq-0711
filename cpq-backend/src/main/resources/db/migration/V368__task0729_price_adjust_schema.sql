-- =============================================================================
-- task-0729 · B1：客户价格调整策略与价格版本 —— 表结构与迁移
-- =============================================================================
-- 依据：dev-docs/task-0729-客户价格调整策略和价格版本/backtask.md §1 B1
--       + 需求说明.md §11.10 数据模型 + §11.15.6 数据模型补充 + §11.17（E14）
--
-- 范围：新增 12 张表 + 3 处加列（quotation_line_item / quotation_line_component_data
--       各加 row_version；component 加 element_code_field / element_price_field /
--       element_currency_field）。
--
-- 全局纪律（backtask B1 / 需求说明 §11.15.6 末尾）：
--   客户维度一律 customer_no VARCHAR，禁用 UUID 外键（对齐 task-0722 §11.11.4 约束，
--   与 element_price_strategy.customer_no 同宽 VARCHAR(64)）。
--   本迁移不做的事：不碰 element_price_strategy / element_daily_price /
--   element_price_source（task-0722 既有）；不为「是否优先使用客户价格调整策略」
--   预留任何列；row_version 是原生 SQL 自带乐观锁列，不是 JPA @Version（§11.15.5.2）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) customer_price_adjust_strategy —— 客户调价策略主体，一客户一条
-- -----------------------------------------------------------------------------
CREATE TABLE customer_price_adjust_strategy (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_no          VARCHAR(64)  NOT NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT true,
    cycle_type           VARCHAR(20)  NOT NULL DEFAULT 'MONTHLY_DAY',
    cycle_weekday        SMALLINT,                 -- 1-7（WEEKLY / MONTHLY_NTH_WEEK 用）
    cycle_day_of_month   SMALLINT,                 -- 1-31（MONTHLY_DAY 用）
    cycle_nth_week       SMALLINT,                 -- 1-5（MONTHLY_NTH_WEEK 用）
    execute_time         TIME         NOT NULL DEFAULT '09:00:00',
    material_scope_mode  VARCHAR(20)  NOT NULL DEFAULT 'ALL',
    -- E13：成本差额金额预警线，不是百分比、不是毛利率
    cost_diff_threshold  NUMERIC(18,4) NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by           UUID,
    updated_by           UUID,
    CONSTRAINT uq_cpas_customer UNIQUE (customer_no),
    CONSTRAINT chk_cpas_cycle_type CHECK (cycle_type IN ('DAILY','WEEKLY','MONTHLY_DAY','MONTHLY_NTH_WEEK')),
    CONSTRAINT chk_cpas_scope_mode CHECK (material_scope_mode IN ('ALL','SPECIFIED'))
);
COMMENT ON TABLE customer_price_adjust_strategy IS 'task-0729 客户调价策略主体（屏 1）。一客户一条。';

-- -----------------------------------------------------------------------------
-- 2) customer_price_adjust_material —— 策略指定料号清单（策略 × 销售料号）
-- -----------------------------------------------------------------------------
CREATE TABLE customer_price_adjust_material (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id  UUID NOT NULL REFERENCES customer_price_adjust_strategy(id) ON DELETE CASCADE,
    material_no  VARCHAR(50) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cpam_strategy_material UNIQUE (strategy_id, material_no)
);
COMMENT ON TABLE customer_price_adjust_material IS 'task-0729 策略指定料号清单，仅 material_scope_mode=SPECIFIED 时生效。';

-- -----------------------------------------------------------------------------
-- 3) customer_price_adjust_element —— 策略参与调价元素清单（策略 × 元素编码）
-- -----------------------------------------------------------------------------
CREATE TABLE customer_price_adjust_element (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id  UUID NOT NULL REFERENCES customer_price_adjust_strategy(id) ON DELETE CASCADE,
    element_code VARCHAR(32) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cpae_strategy_element UNIQUE (strategy_id, element_code)
);
COMMENT ON TABLE customer_price_adjust_element IS 'task-0729 策略参与调价元素清单。为空/全无价 → 不生成版本（E14-10）。';

-- -----------------------------------------------------------------------------
-- 4) customer_price_adjust_strategy_log —— 策略变更审计（断链 9）
-- -----------------------------------------------------------------------------
CREATE TABLE customer_price_adjust_strategy_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id     UUID NOT NULL REFERENCES customer_price_adjust_strategy(id) ON DELETE CASCADE,
    customer_no     VARCHAR(64) NOT NULL,
    change_type     VARCHAR(30) NOT NULL,   -- STRATEGY|MATERIAL_SCOPE|ELEMENT_LIST|COMPARISON_COLUMN
    summary         VARCHAR(500),
    before_snapshot JSONB,
    after_snapshot  JSONB,
    changed_by      UUID,
    changed_by_name VARCHAR(100),
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_cpasl_change_type CHECK (change_type IN ('STRATEGY','MATERIAL_SCOPE','ELEMENT_LIST','COMPARISON_COLUMN'))
);
CREATE INDEX idx_cpasl_strategy_time ON customer_price_adjust_strategy_log (strategy_id, changed_at DESC);
COMMENT ON TABLE customer_price_adjust_strategy_log IS 'task-0729 调价策略变更审计。四类变更（周期/料号范围/元素清单/比对列）均记录。';

-- -----------------------------------------------------------------------------
-- 5) element_price_version —— 元素价格版本（批次）
-- -----------------------------------------------------------------------------
CREATE TABLE element_price_version (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_no    VARCHAR(64) NOT NULL,
    version_no     VARCHAR(20) NOT NULL,       -- V + YYMMDD + 两位当日流水
    base_date      DATE        NOT NULL,
    -- 🔒 只有两态：PENDING(待处理) / SUPERSEDED(已被新版取代)。不含"生效/驳回"，那是料号级（§11.3.3）
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    trigger_type   VARCHAR(20) NOT NULL,       -- SCHEDULED|MANUAL
    scheduled_slot TIMESTAMPTZ,                -- 该次周期点的计划执行时刻；MANUAL 触发为 NULL
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID,
    CONSTRAINT chk_epv_status CHECK (status IN ('PENDING','SUPERSEDED')),
    CONSTRAINT chk_epv_trigger_type CHECK (trigger_type IN ('SCHEDULED','MANUAL')),
    -- 幂等 + 天然补跑：同一周期点重复扫描插入冲突即跳过。PG 中 NULL 不参与唯一约束，
    -- 手动生成 scheduled_slot=NULL 天然不受此约束限制（同日可多次手动生成）。
    CONSTRAINT uq_epv_customer_slot UNIQUE (customer_no, scheduled_slot),
    CONSTRAINT uq_epv_customer_version UNIQUE (customer_no, version_no)
);
-- 🔒 强约束补1：同客户同时只允许一个待处理版本（部分唯一索引，不只靠应用层裁决 27）
CREATE UNIQUE INDEX uq_epv_customer_pending ON element_price_version (customer_no) WHERE status = 'PENDING';
CREATE INDEX idx_epv_customer_created ON element_price_version (customer_no, created_at DESC);
COMMENT ON TABLE element_price_version IS 'task-0729 元素价格版本批次（屏 1）。版本本身不谈生效，生效的是料号指针（§11.3.3）。';

-- -----------------------------------------------------------------------------
-- 6) element_price_version_item —— 版本明细（版本 × 元素，报价核价共用一组价，E12）
-- -----------------------------------------------------------------------------
CREATE TABLE element_price_version_item (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id              UUID NOT NULL REFERENCES element_price_version(id) ON DELETE CASCADE,
    element_code            VARCHAR(32) NOT NULL,
    current_price           NUMERIC(20,6),      -- 本版价；NULL = 该元素本期彻底无价（no_price=true 且从无历史价）
    previous_price          NUMERIC(20,6),      -- 上一版价（用于涨跌幅展示）
    change_rate             NUMERIC(12,6),       -- 涨跌幅（相对上一版）
    currency                VARCHAR(10),         -- 缺口 B6：现行 mc_view 同时输出货币列，函数须返回
    price_unit              VARCHAR(20),
    no_price                BOOLEAN NOT NULL DEFAULT false,  -- 本期无价（含"沿用上一版"与"彻底无价"两类）
    inherited_from_previous BOOLEAN NOT NULL DEFAULT false,  -- true=本期无价但沿用了上一版价（§11.3.2.1）
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_epvi_version_element UNIQUE (version_id, element_code)
);
CREATE INDEX idx_epvi_version ON element_price_version_item (version_id);
COMMENT ON TABLE element_price_version_item IS 'task-0729 版本明细：版本 × 元素一组价（E12 报价核价共用，不分侧）。';

-- -----------------------------------------------------------------------------
-- 7) material_price_version_ref —— 料号版本指针（客户 × 销售料号 → 当前生效版本）
--    🔥 取价函数的热路径：UNIQUE 索引自带 INCLUDE(version_id) 做索引覆盖，避免回表。
-- -----------------------------------------------------------------------------
CREATE TABLE material_price_version_ref (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_no  VARCHAR(64) NOT NULL,
    material_no  VARCHAR(50) NOT NULL,
    version_id   UUID NOT NULL REFERENCES element_price_version(id),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_mpvr_customer_material ON material_price_version_ref (customer_no, material_no) INCLUDE (version_id);
COMMENT ON TABLE material_price_version_ref IS 'task-0729 料号版本指针（仅 QUOTE 侧维度，裁决 40）。f_material_element_price 的热路径查询表。';

-- -----------------------------------------------------------------------------
-- 8) material_price_review —— 料号审核记录
-- -----------------------------------------------------------------------------
CREATE TABLE material_price_review (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id           UUID NOT NULL REFERENCES element_price_version(id),
    -- 该料号升版前的指针版本（用于「当前版本 → 目标版本」展示；审核已处理后指针会移动，
    -- 故须在生成待办时快照一份，不能用当时的实时指针反查历史）。
    previous_version_id  UUID REFERENCES element_price_version(id),
    customer_no          VARCHAR(64) NOT NULL,    -- 冗余，避免跨客户待办池每行 JOIN version
    material_no          VARCHAR(50) NOT NULL,
    template_series_id   UUID,                    -- 冗余（§11.5.4），预算时一次解析并固化
    basis_quotation_id   UUID,                     -- 判断依据单（版本生成时刻锁定，E11-6）
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',   -- PENDING|APPROVED|REJECTED|VOIDED
    -- E14-3 新增：预算是否算完，READY 之前不可审核（服务端兜底 409 REVIEW_BUDGET_NOT_READY）
    budget_status        VARCHAR(20) NOT NULL DEFAULT 'QUEUED',    -- QUEUED|COMPUTING|READY|FAILED
    budget_error         TEXT,
    -- 冗余汇总列供待办池列表直读，免 JOIN、免 N+1（§11.5.3）
    breached_count       INTEGER NOT NULL DEFAULT 0,
    amber_count          INTEGER NOT NULL DEFAULT 0,
    missing_count        INTEGER NOT NULL DEFAULT 0,
    stale_count          INTEGER NOT NULL DEFAULT 0,
    column_count         INTEGER NOT NULL DEFAULT 0,
    reviewed_by          UUID,
    reviewed_at          TIMESTAMPTZ,
    review_comment       TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_mpr_version_material UNIQUE (version_id, material_no),
    CONSTRAINT chk_mpr_status CHECK (status IN ('PENDING','APPROVED','REJECTED','VOIDED')),
    CONSTRAINT chk_mpr_budget_status CHECK (budget_status IN ('QUEUED','COMPUTING','READY','FAILED'))
);
CREATE INDEX idx_mpr_status_version ON material_price_review (status, version_id);
CREATE INDEX idx_mpr_customer_status ON material_price_review (customer_no, status);
COMMENT ON TABLE material_price_review IS 'task-0729 料号审核记录（屏 3/4）。rowRed = breached_count > 0（硬约束 19）。';

-- -----------------------------------------------------------------------------
-- 9) material_price_review_column —— 逐比对列预算明细
-- -----------------------------------------------------------------------------
CREATE TABLE material_price_review_column (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id        UUID NOT NULL REFERENCES material_price_review(id) ON DELETE CASCADE,
    column_id        VARCHAR(50) NOT NULL,
    column_label     VARCHAR(200),
    threshold        NUMERIC(20,6),
    sort_order       INTEGER NOT NULL DEFAULT 0,
    quote_current    NUMERIC(20,6),
    quote_adjusted   NUMERIC(20,6),
    costing_current  NUMERIC(20,6),
    costing_adjusted NUMERIC(20,6),
    diff_current     NUMERIC(20,6),
    diff_adjusted    NUMERIC(20,6),
    -- RED=差异<0；MISSING=任一侧取不到值(计入breached)；AMBER=0≤差异<threshold；
    -- STALE=componentId/metric因模板改版失效(不计breached/amber)；NORMAL=其余（E13）
    status           VARCHAR(20) NOT NULL,
    missing_side     VARCHAR(20),    -- QUOTE|COSTING|BOTH（status=MISSING 时说明缺哪一侧）
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_mprc_review_column UNIQUE (review_id, column_id),
    CONSTRAINT chk_mprc_status CHECK (status IN ('RED','AMBER','NORMAL','MISSING','STALE'))
);
CREATE INDEX idx_mprc_review ON material_price_review_column (review_id);
COMMENT ON TABLE material_price_review_column IS 'task-0729 料号审核逐比对列预算明细。屏3汇总标记与屏4抽屉明细均读此表，逐位一致（裁决41）。';

-- -----------------------------------------------------------------------------
-- 10) quotation_price_revision —— 报价单 R 版本（一期一版 + 部分唯一索引锁定唯一初版）
-- -----------------------------------------------------------------------------
CREATE TABLE quotation_price_revision (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quotation_id           UUID NOT NULL REFERENCES quotation(id) ON DELETE CASCADE,
    revision_no            VARCHAR(20) NOT NULL,     -- R + YYMMDD + 两位当日流水（按单+日期计数）
    based_version_id       UUID REFERENCES element_price_version(id),  -- 初版（D6）留空
    -- 初版定型标记（§11.10.6）：未定型时 snapshot 留 NULL，渲染取当前值；首次升版时物化 + 置 true
    sealed                 BOOLEAN NOT NULL DEFAULT false,
    upgraded_material_nos  JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- 整单快照 · 双侧（F3）：报价 + 核价 + driver 行，三者要么都 NULL（未定型）要么都物化（已定型）
    quote_card_values      JSONB,
    costing_card_values    JSONB,
    snapshot_rows          JSONB,
    quote_total_amount     NUMERIC(20,6),
    first_effective_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 裁决 30「一期一版」的物理保证
    CONSTRAINT uq_qpr_quotation_based_version UNIQUE (quotation_id, based_version_id)
);
-- 🔒 D6：PG 中 NULL 不参与普通唯一约束，故"每单只能一条初版"须另加部分唯一索引
CREATE UNIQUE INDEX uq_qpr_quotation_initial ON quotation_price_revision (quotation_id) WHERE based_version_id IS NULL;
CREATE INDEX idx_qpr_quotation ON quotation_price_revision (quotation_id, first_effective_at);
COMMENT ON TABLE quotation_price_revision IS 'task-0729 报价单 R 版本轨迹（屏 7）。初版 based_version_id 留空 + sealed 定型标记（§11.10.6）。';

-- -----------------------------------------------------------------------------
-- 11) comparison_column_config —— 比对列配置（维度 = 客户 × 模板系列，非按客户一份）
-- -----------------------------------------------------------------------------
CREATE TABLE comparison_column_config (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_no         VARCHAR(64) NOT NULL,
    template_series_id  UUID NOT NULL,      -- 对应 template.template_series_id，非独立表主键，不加 FK
    columns             JSONB NOT NULL DEFAULT '[]'::jsonb,  -- 复用 task-0717 ColumnDef schema
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID,
    CONSTRAINT uq_ccc_customer_series UNIQUE (customer_no, template_series_id)
);
COMMENT ON TABLE comparison_column_config IS
'task-0729 比对列配置（§11.5.4 改判裁决43）。维度=客户×模板系列，不是按客户一份。
唯一写入口=屏1价格调整策略Tab；屏4审核抽屉只读。⚠️ 不要沿用 customer_comparison_config 这个名字。';

-- -----------------------------------------------------------------------------
-- 12) material_price_update_job / material_price_update_job_item —— 批次 + 明细
--     参照既有前例 QuoteImportService（ManagedExecutor + 进度增量写库 + REQUIRES_NEW 独立提交）
-- -----------------------------------------------------------------------------
CREATE TABLE material_price_update_job (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_no    VARCHAR(64) NOT NULL,
    version_id     UUID REFERENCES element_price_version(id),
    version_no     VARCHAR(20),
    triggered_by   UUID,
    triggered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    status         VARCHAR(20) NOT NULL DEFAULT 'RUNNING',  -- RUNNING|SUCCESS|PARTIAL|FAILED|STALE
    total_count    INTEGER NOT NULL DEFAULT 0,
    success_count  INTEGER NOT NULL DEFAULT 0,
    failed_count   INTEGER NOT NULL DEFAULT 0,
    conflict_count INTEGER NOT NULL DEFAULT 0,
    stale_count    INTEGER NOT NULL DEFAULT 0,
    finished_at    TIMESTAMPTZ,
    notified       BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_mpuj_status CHECK (status IN ('RUNNING','SUCCESS','PARTIAL','FAILED','STALE'))
);
CREATE INDEX idx_mpuj_customer_time ON material_price_update_job (customer_no, triggered_at DESC);
COMMENT ON TABLE material_price_update_job IS 'task-0729 更新批次（屏 6 + 常驻更新任务页）。';

CREATE TABLE material_price_update_job_item (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id        UUID NOT NULL REFERENCES material_price_update_job(id) ON DELETE CASCADE,
    quotation_id  UUID NOT NULL REFERENCES quotation(id),
    material_no   VARCHAR(50) NOT NULL,
    line_item_id  UUID,
    status        VARCHAR(20) NOT NULL DEFAULT 'WAITING',  -- WAITING|RUNNING|SUCCESS|FAILED|CONFLICT|STALE
    error_code    VARCHAR(50),
    error_message TEXT,
    diff_value    NUMERIC(20,6),      -- L3 守卫用：后端旧价重算 vs li.subtotal 的差异
    retry_count   INTEGER NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_mpuji_status CHECK (status IN ('WAITING','RUNNING','SUCCESS','FAILED','CONFLICT','STALE'))
);
CREATE INDEX idx_mpuji_job_status ON material_price_update_job_item (job_id, status);
CREATE INDEX idx_mpuji_quotation ON material_price_update_job_item (quotation_id);
COMMENT ON TABLE material_price_update_job_item IS 'task-0729 更新批次明细：报价单 × 料号。三种非成功态语义见 api.md §3.3。';

-- =============================================================================
-- 加列 3 处
-- =============================================================================

-- F1 并发控制（🔒 不是 JPA @Version，见需求说明.md §11.15.5.2）：
-- 这两张表的 snapshot_rows / row_data 现存写入口 100% 是原生 SQL，JPA @Version 对这些路径完全不生效。
-- 写入方必须自己带条件：UPDATE ... SET ..., row_version = row_version + 1
--                       WHERE id = :id AND row_version = :seen
-- 受影响行数 = 0 → 判为「冲突」态（裁决 19 三态之一），不覆盖、可重试。
ALTER TABLE quotation_line_item           ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE quotation_line_component_data ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;

-- 组件三个角色字段（🔒 组件级，不进 fields 的 config JSON，绕开 AP-44 17 点联动）
-- 与既有 part_no_field / part_name_field / sort_field / row_key_fields 平级。
-- 保存期校验：sqlTemplate 检测到取价函数 → 前两项必填，否则拒绝保存（B7 实现，本迁移只加列）。
ALTER TABLE component ADD COLUMN element_code_field     VARCHAR(100);
ALTER TABLE component ADD COLUMN element_price_field    VARCHAR(100);
ALTER TABLE component ADD COLUMN element_currency_field VARCHAR(100);

COMMENT ON COLUMN quotation_line_item.row_version IS
'task-0729 F1 并发控制列。原生 SQL 自带乐观锁，非 JPA @Version（§11.15.5.2）。';
COMMENT ON COLUMN quotation_line_component_data.row_version IS
'task-0729 F1 并发控制列。原生 SQL 自带乐观锁，非 JPA @Version（§11.15.5.2）。S3 写 snapshot_rows 必须带此条件。';
COMMENT ON COLUMN component.element_code_field IS 'task-0729 元素编码列（拿它的值匹配 element_price_version_item.element_code）。组件级角色字段，不进 fields config JSON。';
COMMENT ON COLUMN component.element_price_field IS 'task-0729 元素单价列（S3 改它、S4 只清它）。组件级角色字段。';
COMMENT ON COLUMN component.element_currency_field IS 'task-0729 货币列（可空）。组件级角色字段。';
