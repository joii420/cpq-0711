-- ============================================================
-- CPQ 内网数据库增量更新 · 2026-08-04
-- ------------------------------------------------------------
-- 适用对象: 用 deploy/cpq-init-empty-navicat.sql 建库(结构停在 Flyway V365)
--           且已录入业务数据的内网库。本脚本把**表结构**补齐到 V378。
--
-- 覆盖范围: 仅表结构 —— 建表 / 加列 / 改列宽 / 改约束 / 建索引 / 建函数。
--           对应 Flyway V366~V378 里的全部 DDL。
--
-- 明确不含(按 2026-08-04 确认): 业务配置数据同步。即 V366/V369/V370/V372/
--           V373/V374 对 component.fields、component_sql_view.sql_template 的
--           UPDATE, V375/V376 的公式稳定 id 回填, 以及 V372/V377 的
--           DELETE FROM unit_price。这些需要时再单独出脚本。
--           ⚠️ 因此执行完本脚本后, 新表结构就位但「调价」相关的组件/视图配置
--              仍是旧的; 若内网要启用 task-0729 调价功能, 还需另行同步配置。
--
-- 唯一例外: 第 5 节 annual_discount 里有一条 UPDATE(INCOMING -> FINISHED)。
--           它不是业务配置同步, 而是新 CHECK 约束能建立的前提 —— 旧值
--           'INCOMING' 不在新枚举内, 不改则 ADD CONSTRAINT 必然失败。
--           属等价改名, 不丢数据。详见该节注释。
--
-- 幂等性:   第 1~4、6 节可安全重复执行。
--           ⚠️ 第 5 节(annual_discount)含 RENAME COLUMN, **只能执行一次**,
--              重复执行会报 column "biz_type" does not exist —— 这是预期的,
--              说明该节已生效过。
--
-- 执行前建议: 先备份库(至少备份 annual_discount 与 quotation 系列表),
--             并逐条跑一遍下面的「执行前自检」确认目标库确实处于 V365 状态。
--
-- Navicat 导入: 右键库 -> 运行 SQL 文件 -> 选本文件 -> 勾选"遇到错误时停止"。
-- 说明: 全文不使用 DO $$ 匿名块, 幂等靠 IF NOT EXISTS / DROP IF EXISTS 实现,
--       函数体是全文唯一的美元引号且置于末尾, 与建库脚本同一规避思路。
-- ============================================================


-- ============================================================
-- 执行前自检(先单独跑这几条, 确认目标库状态符合预期再执行下文)
-- ============================================================
-- 1) 确认 annual_discount 仍是旧结构(应返回 biz_type 一行; 若返回 0 行说明第 5 节已跑过)
--    SELECT column_name FROM information_schema.columns
--     WHERE table_name='annual_discount' AND column_name IN ('biz_type','discount_type');
--
-- 2) ⚠️ 关键: 确认 annual_discount 存量数据在新 7 维唯一键下不撞键。
--    新唯一键 = (system_type, discount_type, material_no, customer_no, target_no, version_no, discount_order),
--    存量行迁移后 system_type 恒 'QUOTE'、version_no 恒 '2000'、customer_no/target_no 恒 NULL,
--    故实际退化为按 (discount_type, material_no, discount_order) 判重。
--    下面这条**必须返回 0 行**, 否则第 5 节建唯一索引会失败, 需先人工清理重复:
--    SELECT CASE WHEN biz_type='INCOMING' THEN 'FINISHED' ELSE biz_type END AS new_type,
--           material_no, discount_order, count(*)
--      FROM annual_discount
--     GROUP BY 1,2,3 HAVING count(*) > 1;
--
-- 3) 确认待建的 14 张表尚不存在(应返回 0)
--    SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN
--    ('customer_price_adjust_strategy','customer_price_adjust_material','customer_price_adjust_element',
--     'customer_price_adjust_strategy_log','element_price_version','element_price_version_item',
--     'material_price_version_ref','material_price_review','material_price_review_column',
--     'quotation_price_revision','comparison_column_config','material_price_update_job',
--     'material_price_update_job_item','price_adjust_settings');


-- ============================================================
-- 第 1 节 · V367: 金额列加宽 numeric(18,4) -> numeric(20,6)
-- ------------------------------------------------------------
-- 纯扩宽, 不丢数据, 可重复执行。
-- ============================================================

ALTER TABLE public.quotation                     ALTER COLUMN total_amount          TYPE numeric(20,6);
ALTER TABLE public.quotation                     ALTER COLUMN original_amount       TYPE numeric(20,6);
ALTER TABLE public.quotation                     ALTER COLUMN tax_amount            TYPE numeric(20,6);
ALTER TABLE public.quotation_line_item           ALTER COLUMN subtotal              TYPE numeric(20,6);
ALTER TABLE public.quotation_line_item           ALTER COLUMN discount_base_amount  TYPE numeric(20,6);
ALTER TABLE public.quotation_line_item           ALTER COLUMN line_unit_price       TYPE numeric(20,6);
ALTER TABLE public.quotation_line_item           ALTER COLUMN line_final_price      TYPE numeric(20,6);
ALTER TABLE public.quotation_line_item           ALTER COLUMN line_discount_amount  TYPE numeric(20,6);
ALTER TABLE public.quotation_line_item           ALTER COLUMN line_total_amount     TYPE numeric(20,6);
ALTER TABLE public.quotation_line_component_data ALTER COLUMN subtotal              TYPE numeric(20,6);
ALTER TABLE public.costing_order                 ALTER COLUMN total_amount          TYPE numeric(20,6);
ALTER TABLE public.costing_order                 ALTER COLUMN costing_total_amount  TYPE numeric(20,6);


-- ============================================================
-- 第 2 节 · V368: 调价体系新表(13 张) + 已有表加列
-- ------------------------------------------------------------
-- 建表语句已把主键/唯一约束内联, 配合 IF NOT EXISTS 天然幂等。
-- ============================================================

CREATE TABLE IF NOT EXISTS public.comparison_column_config (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_no character varying(64) NOT NULL,
    template_series_id uuid NOT NULL,
    columns jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by uuid,
    CONSTRAINT comparison_column_config_pkey PRIMARY KEY (id),
    CONSTRAINT uq_ccc_customer_series UNIQUE (customer_no, template_series_id)
);

CREATE TABLE IF NOT EXISTS public.customer_price_adjust_strategy (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_no character varying(64) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    cycle_type character varying(20) DEFAULT 'MONTHLY_DAY'::character varying NOT NULL,
    cycle_weekday smallint,
    cycle_day_of_month smallint,
    cycle_nth_week smallint,
    execute_time time without time zone DEFAULT '09:00:00'::time without time zone NOT NULL,
    material_scope_mode character varying(20) DEFAULT 'ALL'::character varying NOT NULL,
    cost_diff_threshold numeric(18,4) DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    CONSTRAINT chk_cpas_cycle_type CHECK (((cycle_type)::text = ANY ((ARRAY['DAILY'::character varying, 'WEEKLY'::character varying, 'MONTHLY_DAY'::character varying, 'MONTHLY_NTH_WEEK'::character varying])::text[]))),
    CONSTRAINT chk_cpas_scope_mode CHECK (((material_scope_mode)::text = ANY ((ARRAY['ALL'::character varying, 'SPECIFIED'::character varying])::text[]))),
    CONSTRAINT customer_price_adjust_strategy_pkey PRIMARY KEY (id),
    CONSTRAINT uq_cpas_customer UNIQUE (customer_no)
);

CREATE TABLE IF NOT EXISTS public.element_price_version (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_no character varying(64) NOT NULL,
    version_no character varying(20) NOT NULL,
    base_date date NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    trigger_type character varying(20) NOT NULL,
    scheduled_slot timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    CONSTRAINT chk_epv_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SUPERSEDED'::character varying])::text[]))),
    CONSTRAINT chk_epv_trigger_type CHECK (((trigger_type)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'MANUAL'::character varying])::text[]))),
    CONSTRAINT element_price_version_pkey PRIMARY KEY (id),
    CONSTRAINT uq_epv_customer_slot UNIQUE (customer_no, scheduled_slot),
    CONSTRAINT uq_epv_customer_version UNIQUE (customer_no, version_no)
);

CREATE TABLE IF NOT EXISTS public.customer_price_adjust_element (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    strategy_id uuid NOT NULL,
    element_code character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT customer_price_adjust_element_pkey PRIMARY KEY (id),
    CONSTRAINT uq_cpae_strategy_element UNIQUE (strategy_id, element_code),
    CONSTRAINT customer_price_adjust_element_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES public.customer_price_adjust_strategy(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS public.customer_price_adjust_material (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    strategy_id uuid NOT NULL,
    material_no character varying(50) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT customer_price_adjust_material_pkey PRIMARY KEY (id),
    CONSTRAINT uq_cpam_strategy_material UNIQUE (strategy_id, material_no),
    CONSTRAINT customer_price_adjust_material_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES public.customer_price_adjust_strategy(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS public.customer_price_adjust_strategy_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    strategy_id uuid NOT NULL,
    customer_no character varying(64) NOT NULL,
    change_type character varying(30) NOT NULL,
    summary character varying(500),
    before_snapshot jsonb,
    after_snapshot jsonb,
    changed_by uuid,
    changed_by_name character varying(100),
    changed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_cpasl_change_type CHECK (((change_type)::text = ANY ((ARRAY['STRATEGY'::character varying, 'MATERIAL_SCOPE'::character varying, 'ELEMENT_LIST'::character varying, 'COMPARISON_COLUMN'::character varying])::text[]))),
    CONSTRAINT customer_price_adjust_strategy_log_pkey PRIMARY KEY (id),
    CONSTRAINT customer_price_adjust_strategy_log_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES public.customer_price_adjust_strategy(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS public.element_price_version_item (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    version_id uuid NOT NULL,
    element_code character varying(32) NOT NULL,
    current_price numeric(20,6),
    previous_price numeric(20,6),
    change_rate numeric(12,6),
    currency character varying(10),
    price_unit character varying(20),
    no_price boolean DEFAULT false NOT NULL,
    inherited_from_previous boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT element_price_version_item_pkey PRIMARY KEY (id),
    CONSTRAINT uq_epvi_version_element UNIQUE (version_id, element_code),
    CONSTRAINT element_price_version_item_version_id_fkey FOREIGN KEY (version_id) REFERENCES public.element_price_version(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS public.material_price_review (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    version_id uuid NOT NULL,
    previous_version_id uuid,
    customer_no character varying(64) NOT NULL,
    material_no character varying(50) NOT NULL,
    template_series_id uuid,
    basis_quotation_id uuid,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    budget_status character varying(20) DEFAULT 'QUEUED'::character varying NOT NULL,
    budget_error text,
    breached_count integer DEFAULT 0 NOT NULL,
    amber_count integer DEFAULT 0 NOT NULL,
    missing_count integer DEFAULT 0 NOT NULL,
    stale_count integer DEFAULT 0 NOT NULL,
    column_count integer DEFAULT 0 NOT NULL,
    reviewed_by uuid,
    reviewed_at timestamp with time zone,
    review_comment text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_mpr_budget_status CHECK (((budget_status)::text = ANY ((ARRAY['QUEUED'::character varying, 'COMPUTING'::character varying, 'READY'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT chk_mpr_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'VOIDED'::character varying])::text[]))),
    CONSTRAINT material_price_review_pkey PRIMARY KEY (id),
    CONSTRAINT uq_mpr_version_material UNIQUE (version_id, material_no),
    CONSTRAINT material_price_review_previous_version_id_fkey FOREIGN KEY (previous_version_id) REFERENCES public.element_price_version(id),
    CONSTRAINT material_price_review_version_id_fkey FOREIGN KEY (version_id) REFERENCES public.element_price_version(id)
);

CREATE TABLE IF NOT EXISTS public.material_price_update_job (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_no character varying(64) NOT NULL,
    version_id uuid,
    version_no character varying(20),
    triggered_by uuid,
    triggered_at timestamp with time zone DEFAULT now() NOT NULL,
    status character varying(20) DEFAULT 'RUNNING'::character varying NOT NULL,
    total_count integer DEFAULT 0 NOT NULL,
    success_count integer DEFAULT 0 NOT NULL,
    failed_count integer DEFAULT 0 NOT NULL,
    conflict_count integer DEFAULT 0 NOT NULL,
    stale_count integer DEFAULT 0 NOT NULL,
    finished_at timestamp with time zone,
    notified boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_mpuj_status CHECK (((status)::text = ANY ((ARRAY['RUNNING'::character varying, 'SUCCESS'::character varying, 'PARTIAL'::character varying, 'FAILED'::character varying, 'STALE'::character varying])::text[]))),
    CONSTRAINT material_price_update_job_pkey PRIMARY KEY (id),
    CONSTRAINT material_price_update_job_version_id_fkey FOREIGN KEY (version_id) REFERENCES public.element_price_version(id)
);

CREATE TABLE IF NOT EXISTS public.material_price_version_ref (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_no character varying(64) NOT NULL,
    material_no character varying(50) NOT NULL,
    version_id uuid NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT material_price_version_ref_pkey PRIMARY KEY (id),
    CONSTRAINT material_price_version_ref_version_id_fkey FOREIGN KEY (version_id) REFERENCES public.element_price_version(id)
);

CREATE TABLE IF NOT EXISTS public.quotation_price_revision (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    quotation_id uuid NOT NULL,
    revision_no character varying(20) NOT NULL,
    based_version_id uuid,
    sealed boolean DEFAULT false NOT NULL,
    upgraded_material_nos jsonb DEFAULT '[]'::jsonb NOT NULL,
    quote_card_values jsonb,
    costing_card_values jsonb,
    snapshot_rows jsonb,
    quote_total_amount numeric(20,6),
    first_effective_at timestamp with time zone DEFAULT now() NOT NULL,
    last_updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT quotation_price_revision_pkey PRIMARY KEY (id),
    CONSTRAINT uq_qpr_quotation_based_version UNIQUE (quotation_id, based_version_id),
    CONSTRAINT quotation_price_revision_based_version_id_fkey FOREIGN KEY (based_version_id) REFERENCES public.element_price_version(id),
    CONSTRAINT quotation_price_revision_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES public.quotation(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS public.material_price_review_column (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    review_id uuid NOT NULL,
    column_id character varying(50) NOT NULL,
    column_label character varying(200),
    threshold numeric(20,6),
    sort_order integer DEFAULT 0 NOT NULL,
    quote_current numeric(20,6),
    quote_adjusted numeric(20,6),
    costing_current numeric(20,6),
    costing_adjusted numeric(20,6),
    diff_current numeric(20,6),
    diff_adjusted numeric(20,6),
    status character varying(20) NOT NULL,
    missing_side character varying(20),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_mprc_status CHECK (((status)::text = ANY ((ARRAY['RED'::character varying, 'AMBER'::character varying, 'NORMAL'::character varying, 'MISSING'::character varying, 'STALE'::character varying])::text[]))),
    CONSTRAINT material_price_review_column_pkey PRIMARY KEY (id),
    CONSTRAINT uq_mprc_review_column UNIQUE (review_id, column_id),
    CONSTRAINT material_price_review_column_review_id_fkey FOREIGN KEY (review_id) REFERENCES public.material_price_review(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS public.material_price_update_job_item (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    job_id uuid NOT NULL,
    quotation_id uuid NOT NULL,
    material_no character varying(50) NOT NULL,
    line_item_id uuid,
    status character varying(20) DEFAULT 'WAITING'::character varying NOT NULL,
    error_code character varying(50),
    error_message text,
    diff_value numeric(20,6),
    retry_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_mpuji_status CHECK (((status)::text = ANY ((ARRAY['WAITING'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'CONFLICT'::character varying, 'STALE'::character varying])::text[]))),
    CONSTRAINT material_price_update_job_item_pkey PRIMARY KEY (id),
    CONSTRAINT material_price_update_job_item_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.material_price_update_job(id) ON DELETE CASCADE,
    CONSTRAINT material_price_update_job_item_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES public.quotation(id)
);

-- ---- 2.2 已有表加列 ----
-- 乐观锁版本号(调价回写并发控制)
ALTER TABLE public.quotation_line_item           ADD COLUMN IF NOT EXISTS row_version bigint NOT NULL DEFAULT 0;
ALTER TABLE public.quotation_line_component_data ADD COLUMN IF NOT EXISTS row_version bigint NOT NULL DEFAULT 0;

-- 组件元素角色字段(标记组件里哪列是元素代码/单价/币种, 供调价链路定位)
ALTER TABLE public.component ADD COLUMN IF NOT EXISTS element_code_field     character varying(100);
ALTER TABLE public.component ADD COLUMN IF NOT EXISTS element_price_field    character varying(100);
ALTER TABLE public.component ADD COLUMN IF NOT EXISTS element_currency_field character varying(100);

-- ---- 2.3 索引 ----
CREATE INDEX IF NOT EXISTS idx_cpasl_strategy_time ON public.customer_price_adjust_strategy_log USING btree (strategy_id, changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_epv_customer_created ON public.element_price_version USING btree (customer_no, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_epvi_version ON public.element_price_version_item USING btree (version_id);
CREATE INDEX IF NOT EXISTS idx_mpr_customer_status ON public.material_price_review USING btree (customer_no, status);
CREATE INDEX IF NOT EXISTS idx_mpr_status_version ON public.material_price_review USING btree (status, version_id);
CREATE INDEX IF NOT EXISTS idx_mprc_review ON public.material_price_review_column USING btree (review_id);
CREATE INDEX IF NOT EXISTS idx_mpuj_customer_time ON public.material_price_update_job USING btree (customer_no, triggered_at DESC);
CREATE INDEX IF NOT EXISTS idx_mpuji_job_status ON public.material_price_update_job_item USING btree (job_id, status);
CREATE INDEX IF NOT EXISTS idx_mpuji_quotation ON public.material_price_update_job_item USING btree (quotation_id);
CREATE INDEX IF NOT EXISTS idx_qpr_quotation ON public.quotation_price_revision USING btree (quotation_id, first_effective_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_epv_customer_pending ON public.element_price_version USING btree (customer_no) WHERE ((status)::text = 'PENDING'::text);
CREATE UNIQUE INDEX IF NOT EXISTS uq_mpvr_customer_material ON public.material_price_version_ref USING btree (customer_no, material_no) INCLUDE (version_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_qpr_quotation_initial ON public.quotation_price_revision USING btree (quotation_id) WHERE (based_version_id IS NULL);


-- ============================================================
-- 第 3 节 · V378: 调价系统参数表(单行, id=1)
-- ------------------------------------------------------------
-- 种子行是系统参数默认值(非业务数据): 阈值 0.01 与后端
-- MaterialVersionUpgradeService.DEFAULT_SUBTOTAL_GUARD_THRESHOLD 同值,
-- 缺此行则 findById(1) 取不到、L3 守卫不可用。ON CONFLICT 保证幂等。
-- ============================================================

CREATE TABLE IF NOT EXISTS public.price_adjust_settings (
    id smallint DEFAULT 1 NOT NULL,
    subtotal_guard_threshold numeric(20,6) DEFAULT 0.01 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by uuid,
    CONSTRAINT chk_pas_singleton CHECK ((id = 1)),
    CONSTRAINT chk_pas_threshold_nonneg CHECK ((subtotal_guard_threshold >= (0)::numeric)),
    CONSTRAINT pk_price_adjust_settings PRIMARY KEY (id)
);

INSERT INTO public.price_adjust_settings (id, subtotal_guard_threshold)
VALUES (1, 0.01)
ON CONFLICT (id) DO NOTHING;


-- ============================================================
-- 第 4 节 · V371: notification 类型枚举扩容
-- ------------------------------------------------------------
-- 新约束是旧约束的超集(仅新增 2 个取值), 存量数据必然通过。
-- 先 DROP IF EXISTS 再 ADD, 可重复执行。
-- ============================================================

ALTER TABLE public.notification DROP CONSTRAINT IF EXISTS chk_notification_type;
ALTER TABLE public.notification ADD CONSTRAINT chk_notification_type CHECK (
    (type)::text = ANY (ARRAY[
        'APPROVAL_SUBMITTED'::character varying,
        'APPROVAL_APPROVED'::character varying,
        'APPROVAL_REJECTED'::character varying,
        'APPROVAL_REMINDER'::character varying,
        'PASSWORD_RESET'::character varying,
        'ROLE_CHANGED'::character varying,
        'SYSTEM'::character varying,
        'PRICE_ADJUST_JOB_SUMMARY'::character varying,
        'PRICE_ADJUST_QUOTATION_REVIEW'::character varying
    ]::text[])
);


-- ============================================================
-- 第 5 节 · V377: annual_discount 年降单表化改造
-- ------------------------------------------------------------
-- ⚠️⚠️ 本节只能执行一次(含 RENAME COLUMN)。重复执行会在第一条 RENAME 处
--      报 column "biz_type" does not exist, 那说明本节已生效, 跳过即可。
--
-- 执行前请务必先跑「执行前自检」第 2 条确认不撞键。
--
-- 改造内容: 判别列 biz_type -> discount_type 并细化取值; 新增 9 列
-- (客户维度 / 版本化 / pending 隔离); 删除 2 个无用列; 唯一键由 4 维扩到 7 维。
-- ============================================================

-- 5.1 判别列改名 + 取值细化
ALTER TABLE public.annual_discount DROP CONSTRAINT IF EXISTS chk_annual_discount_biz_type;
ALTER TABLE public.annual_discount RENAME COLUMN biz_type TO discount_type;
ALTER TABLE public.annual_discount ALTER COLUMN discount_type TYPE character varying(30);

-- 存量等价改名(不是业务数据同步, 是下面 ADD CONSTRAINT 的前提):
-- annual_discount 历史上唯一写入方恒写 biz_type='INCOMING', 在新模型里一律
-- 属整单级年降 -> FINISHED。不改则新 CHECK 约束会因存量值非法而建立失败。
UPDATE public.annual_discount SET discount_type = 'FINISHED' WHERE discount_type = 'INCOMING';

ALTER TABLE public.annual_discount ADD CONSTRAINT chk_annual_discount_type
    CHECK (discount_type IN ('INCOMING_MATERIAL', 'ASSEMBLY_PROCESS', 'FINISHED'));

-- 5.2 新增列
-- system_type / version_no / is_current 对存量行先用 DEFAULT 填充再摘掉 DEFAULT,
-- 保证 NOT NULL 成立且新写入方必须显式给值(与其余 V6 版本化表口径一致)。
ALTER TABLE public.annual_discount ADD COLUMN IF NOT EXISTS system_type          character varying(10) NOT NULL DEFAULT 'QUOTE';
ALTER TABLE public.annual_discount ALTER COLUMN system_type DROP DEFAULT;
ALTER TABLE public.annual_discount ADD COLUMN IF NOT EXISTS customer_no          character varying(20);
ALTER TABLE public.annual_discount ADD COLUMN IF NOT EXISTS target_no            character varying(30);
ALTER TABLE public.annual_discount ADD COLUMN IF NOT EXISTS seq_no               integer;
ALTER TABLE public.annual_discount ADD COLUMN IF NOT EXISTS version_no           character varying(20) NOT NULL DEFAULT '2000';
ALTER TABLE public.annual_discount ALTER COLUMN version_no DROP DEFAULT;
ALTER TABLE public.annual_discount ADD COLUMN IF NOT EXISTS is_current           boolean NOT NULL DEFAULT true;
ALTER TABLE public.annual_discount ADD COLUMN IF NOT EXISTS pending_quotation_id uuid;
ALTER TABLE public.annual_discount ADD COLUMN IF NOT EXISTS pending_supersedes   uuid[];

-- 5.3 删除被取代 / 从未写入的列
ALTER TABLE public.annual_discount DROP COLUMN IF EXISTS discount_strategy;
ALTER TABLE public.annual_discount DROP COLUMN IF EXISTS discount_base;

-- 5.4 唯一键重建(4 维 -> 7 维)
DROP INDEX IF EXISTS uq_annual_discount;
CREATE UNIQUE INDEX uq_annual_discount ON public.annual_discount USING btree (
    system_type,
    discount_type,
    material_no,
    COALESCE(customer_no, ''::character varying),
    COALESCE(target_no, ''::character varying),
    version_no,
    COALESCE(discount_order, 0)
);

-- 5.5 pending 部分索引
CREATE INDEX IF NOT EXISTS ix_annual_discount_pending
    ON public.annual_discount USING btree (pending_quotation_id) WHERE (pending_quotation_id IS NOT NULL);

-- 注: 旧索引 idx_annual_discount_material 无需处理 —— PostgreSQL 的 RENAME COLUMN
--     会自动更新索引定义, 它已随 5.1 从 (material_no, biz_type) 变为 (material_no, discount_type)。


-- ============================================================
-- 第 6 节 · V369: 料号级元素取价函数
-- ------------------------------------------------------------
-- CREATE OR REPLACE, 可重复执行。函数体已去中文注释(纯 ASCII)。
-- 依赖第 2 节建的 material_price_version_ref / element_price_version_item, 故置于其后。
-- ============================================================

CREATE OR REPLACE FUNCTION public.f_material_element_price(p_customer_no text, p_base_date date)
RETURNS TABLE(material_no character varying, element_code character varying, unit_price numeric, currency character varying, price_unit character varying)
    LANGUAGE sql STABLE
    AS $$
WITH pointers AS (
    SELECT material_no, version_id
      FROM material_price_version_ref
     WHERE customer_no = p_customer_no
),
versioned AS (
    SELECT p.material_no, i.element_code, i.current_price AS unit_price,
           i.currency, i.price_unit
      FROM pointers p
      JOIN element_price_version_item i ON i.version_id = p.version_id
     WHERE i.current_price IS NOT NULL
),
candidate_materials AS (
    SELECT material_no FROM material_bom_item
     WHERE customer_no IN (p_customer_no, '_GLOBAL_') AND is_current = true
    UNION
    SELECT material_no FROM element_bom_item
     WHERE customer_no IN (p_customer_no, '_GLOBAL_') AND is_current = true
),
realtime AS (
    SELECT cm.material_no, f.element_code, f.unit_price, f.currency, f.price_unit
      FROM candidate_materials cm
      CROSS JOIN f_customer_element_price(p_customer_no, p_base_date) f
)
SELECT v.material_no, v.element_code, v.unit_price, v.currency, v.price_unit
  FROM versioned v
UNION ALL
SELECT r.material_no, r.element_code, r.unit_price, r.currency, r.price_unit
  FROM realtime r
  LEFT JOIN versioned v2
    ON v2.material_no = r.material_no AND v2.element_code = r.element_code
 WHERE v2.material_no IS NULL;
$$;


-- ============================================================
-- 执行后自检(逐条核对期望值)
-- ============================================================
-- 1) 新表 14 张全部就位 -- 期望 14
--    SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN
--    ('customer_price_adjust_strategy','customer_price_adjust_material','customer_price_adjust_element',
--     'customer_price_adjust_strategy_log','element_price_version','element_price_version_item',
--     'material_price_version_ref','material_price_review','material_price_review_column',
--     'quotation_price_revision','comparison_column_config','material_price_update_job',
--     'material_price_update_job_item','price_adjust_settings');
--
-- 2) 金额列已加宽 -- 期望 12 行全部是 20 / 6
--    SELECT table_name, column_name, numeric_precision, numeric_scale FROM information_schema.columns
--     WHERE (table_name, column_name) IN (('quotation','total_amount'),('quotation','original_amount'),
--       ('quotation','tax_amount'),('quotation_line_item','subtotal'),('quotation_line_item','discount_base_amount'),
--       ('quotation_line_item','line_unit_price'),('quotation_line_item','line_final_price'),
--       ('quotation_line_item','line_discount_amount'),('quotation_line_item','line_total_amount'),
--       ('quotation_line_component_data','subtotal'),('costing_order','total_amount'),
--       ('costing_order','costing_total_amount')) ORDER BY 1,2;
--
-- 3) annual_discount 新结构 -- 期望有 discount_type 无 biz_type, 且 9 个新列齐全
--    SELECT column_name FROM information_schema.columns WHERE table_name='annual_discount' ORDER BY 1;
--
-- 4) 新函数就位 -- 期望 1
--    SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
--     WHERE n.nspname='public' AND p.proname='f_material_element_price';
--
-- 5) 系统参数种子行 -- 期望 1 行, 阈值 0.010000
--    SELECT id, subtotal_guard_threshold FROM price_adjust_settings;
--
-- 6) notification 约束已扩容 -- 期望结果里含 PRICE_ADJUST_JOB_SUMMARY
--    SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='chk_notification_type';
--
-- 7) 全库表数 -- 期望 143 (= 建库时 129 + 本次 14)
--    SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';
