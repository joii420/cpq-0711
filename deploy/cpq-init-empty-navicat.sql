-- ============================================================
-- CPQ 测试数据库 · 全空 Schema + 仅 admin 用户
-- ------------------------------------------------------------
-- 生成: 2026-07-24  源库 cpq_db (task-0723 废弃表清理后的活表结构)
-- 同步: 2026-07-26  已增量同步至 Flyway V362(基线号同步上调为 362), 与 cpq_db_0724 表/列全等
--       · V362: material_master 增列 pending_quotation_id + 部分索引 ix_material_master_pending
--       · V362: 退役暂存表 pending_material_master_staging(连同其 pkey/唯一约束/索引一并删除)
--       · V363: 补 costing_bom_tree_config 双侧递归 SQL 种子(COSTING/QUOTE 各 1 条 active)
--                基线仍停在 362 —— V363 幂等, 连 Quarkus 时会再跑一次并自愈(脚本漏配也能补回)
-- 同步: 2026-07-31  已增量同步 V364 / V365 两处加列(同 V363 处理: 基线仍停在 362, 两者均
--                   ADD COLUMN IF NOT EXISTS, 连 Quarkus 时重跑幂等自愈)
--       · V364: quotation 增列 product_category_id uuid(建单时的产品分类, 不追溯客户改绑)
--       · V365: material_bom_item 增列 material_ratio numeric(18,6)(材质占比, 小数口径 0.3=30%,
--                仅材质行 characteristic='RECIPE' 有值)
--       · 内网无 Flyway 时, 这两列的等价增量脚本见 deploy/2026-07-31-quotation-product-category-and-material-ratio.sql
-- 同步: 2026-08-04  已增量同步 V366~V378 的全部**结构**变更, 与 cpq_db_0724 表/列/索引/
--                   约束/函数/视图六维全等(逐库 pg_dump 归一化比对, 唯一差异是函数体中文注释)。
--       ⚠️ 基线号一并上调 362 -> 378(不是"仍停在 362")。原因: V368 含 CREATE TABLE(无
--          IF NOT EXISTS)、V377 含 RENAME COLUMN, 均**非幂等**, 基线不上调则连 Quarkus 时
--          会重放 V366~V378 并因"表已存在 / 列不存在"启动失败。这与 V363~V365 那批
--          "幂等可重放"的处理方式不同, 勿照抄上面几行的做法。
--       · V367: quotation/quotation_line_item/quotation_line_component_data/costing_order
--                共 12 个金额列 numeric(18,4) -> numeric(20,6)
--       · V368: 新增 13 张 task-0729 调价体系表(customer_price_adjust_* / element_price_version* /
--                material_price_* / quotation_price_revision / comparison_column_config);
--                quotation_line_item + quotation_line_component_data 各加 row_version(乐观锁);
--                component 加 element_code_field / element_price_field / element_currency_field
--       · V369: 新增函数 f_material_element_price(text, date)(料号级元素取价, 版本指针优先 + 实时兜底)
--       · V371: notification 的 chk_notification_type 扩 2 个取值(PRICE_ADJUST_JOB_SUMMARY /
--                PRICE_ADJUST_QUOTATION_REVIEW)
--       · V377: annual_discount 年降单表化 —— biz_type 改名 discount_type 且取值细化为三 Sheet,
--                新增 9 列(客户维度/版本化/pending 隔离), 删 discount_strategy + discount_base,
--                唯一键由 4 维扩到 7 维
--       · V378: 新增 price_adjust_settings(单行系统参数表, 含 id=1 种子行)
--       · ⚠️ 本次**只同步结构**, 未含 V366/V369/V370/V372~V376 对 component.fields、
--          component_sql_view.sql_template、公式稳定 id 的业务配置 UPDATE(空库无这些行, 无意义)。
--          已建库的内网环境如需补结构, 用等价增量脚本 deploy/0804-dbupdate.sql
-- 同步: 2026-08-09  已增量同步 V379~V384 的全部**结构**变更, 与 cpq_db_0724(已跑到 V384)
--                   表/列/索引/约束六维全等。基线号 378 -> 384(同 V368 的理由: V382 含
--                   CREATE TABLE 无 IF NOT EXISTS、V384 含 DROP CONSTRAINT 无 IF EXISTS,
--                   非幂等, 基线不上调则连 Quarkus 时重放会启动失败)。
--       · V379: 纯数据回填(quotation_view_structure 元素角色字段), 空库无行 —— 本脚本不含
--       · V380: customer_price_adjust_strategy.enabled 默认值 true -> false(客户调价策略默认关闭)
--       · V381: material_price_review 加 warn_code/warn_message/warn_diff;
--                material_price_update_job_item 加 warn_code/warn_message;
--                各加一个部分索引 idx_mpr_warn_code / idx_mpuji_warn_code
--                (L3 口径守卫由"拦截"改"告警", 告警落在用户已在看的两张表上)
--       · V382: 新增表 template_component_snapshot(task-0806 模板发布全量冻结, 18 个渲染配置
--                字段全冻; pkey + uq_tcs_template_tc + 4 索引 + template_id 外键 ON DELETE CASCADE);
--                operation_log 加 details jsonb(结构化 diff, 供 admin 后门审计)。
--                V382 末尾的 UPDATE template SET components_snapshot=NULL 是存量清理, 空库无行 —— 不含
--       · V383: price_adjust_settings 加 subtotal_guard_enabled boolean NOT NULL DEFAULT false
--                (S0 口径守卫总开关, 默认关闭 = 跳过守卫, 性能优先)
--       · V384: material_price_update_job_item 的 chk_mpuji_status 扩 'SKIPPED';
--                material_price_update_job 加 skipped_count integer NOT NULL DEFAULT 0
--       · ⚠️ 同 2026-08-04: 只同步结构, 不含业务配置/存量数据 UPDATE(空库无行)。
--          已建库的内网环境如需从 V378 补到 V384, 用等价增量脚本 deploy/0809-dbupdate.sql
--       · 说明: 本脚本全文不带 COMMENT ON(与 pg_dump 源一致的既有风格), 故 V381~V384 的
--          列注释一并省略 —— 注释无运行时语义, 不影响结构等价性
-- 同步: 2026-08-11  已增量同步 V385 的全部结构变更, 基线号 384 -> 385。
--       · V385: quotation / quotation_line_item / quotation_line_component_data / costing_order /
--                material_price_review / material_price_review_column / quotation_price_revision /
--                material_price_update_job_item 共 8 张表、21 个计算金额列
--                numeric(20,6) -> numeric(26,12), 保持 14 位整数容量不变。
--       · 仅放大精度, 不重算、不截断历史值, 不新增表/列/索引/约束。
--          已建库的内网环境如需从 V384 补到 V385, 用等价增量脚本 deploy/0811-dbupdate.sql
-- 内容: 143 业务活表 + flyway_schema_history + 3 活视图 + 5 函数 + 1 个 admin 用户
--       + 2 条 BOM 树递归 SQL 配置(唯一的业务配置种子, 见文件末尾)
--       + 1 条 price_adjust_settings 系统参数种子行(id=1, 阈值 0.01, 守卫开关 false)
-- 不含: task-0723 的 _drop 废弃表/视图、Flyway 历史迁移记录(仅留 1 行 baseline)、业务数据
--       (报价单/料号/BOM/模板等一律不含)
-- admin 登录: 用户名 admin  /  密码 Admin@2026
-- ------------------------------------------------------------
-- Navicat 导入步骤:
--   1. 新建数据库 (名称任意, 编码 UTF8)
--   2. 右键该库 -> 运行 SQL 文件 -> 选本文件 -> 勾选"遇到错误时停止"
--   3. 跑完执行文件末尾的自检 SQL
-- 说明: 函数体已去中文注释(纯 ASCII)并移至文件末尾,规避 Navicat 函数解析中断
-- ============================================================

--
-- PostgreSQL database dump
--


-- Dumped from database version 16.13 (Debian 16.13-1.pgdg13+1)
-- Dumped by pg_dump version 18.4 (Ubuntu 18.4-0ubuntu0.26.04.1)

SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: current_part_version(text, text); Type: FUNCTION; Schema: public; Owner: -
--



--
-- Name: f_customer_element_price(text, date); Type: FUNCTION; Schema: public; Owner: -
--



--
-- Name: f_material_element_price(text, date); Type: FUNCTION; Schema: public; Owner: -
--



--
-- Name: get_bom_components(text); Type: FUNCTION; Schema: public; Owner: -
--



--
-- Name: get_bom_components(text, text); Type: FUNCTION; Schema: public; Owner: -
--



SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: _bak_component_formulas_20260612; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public._bak_component_formulas_20260612 (
    id uuid,
    formulas jsonb,
    backed_up_at timestamp with time zone
);


--
-- Name: annual_discount; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.annual_discount (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    discount_type character varying(30) NOT NULL,
    material_no character varying(20) NOT NULL,
    discount_order integer NOT NULL,
    discount_ratio numeric(10,4),
    fixed_discount_value numeric(18,6),
    currency character varying(10),
    unit character varying(20),
    discount_times integer,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    system_type character varying(10) NOT NULL,
    customer_no character varying(20),
    target_no character varying(30),
    seq_no integer,
    version_no character varying(20) NOT NULL,
    is_current boolean DEFAULT true NOT NULL,
    pending_quotation_id uuid,
    pending_supersedes uuid[],
    CONSTRAINT chk_annual_discount_type CHECK (((discount_type)::text = ANY ((ARRAY['INCOMING_MATERIAL'::character varying, 'ASSEMBLY_PROCESS'::character varying, 'FINISHED'::character varying])::text[])))
);


--
-- Name: approval_rule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.approval_rule (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    rule_type character varying(20) NOT NULL,
    approver_id uuid,
    match_field character varying(20),
    match_value_id uuid,
    priority integer DEFAULT 100 NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_ar_field CHECK (((match_field IS NULL) OR ((match_field)::text = ANY (ARRAY[('REGION'::character varying)::text, ('DEPARTMENT'::character varying)::text])))),
    CONSTRAINT chk_ar_type CHECK (((rule_type)::text = ANY (ARRAY[('FIXED'::character varying)::text, ('DYNAMIC'::character varying)::text])))
);


--
-- Name: auxiliary_energy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auxiliary_energy (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    material_no character varying(20) NOT NULL,
    material_name character varying(100),
    specification character varying(100),
    dimension character varying(100),
    process_no character varying(20) NOT NULL,
    process_name character varying(50),
    amortize_basis character varying(20),
    working_hours numeric(18,6),
    total_hours numeric(18,6),
    non_production_energy_price numeric(18,6),
    currency character varying(10),
    unit character varying(20),
    conversion_rate numeric(18,6),
    calc_version character varying(20),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    is_current boolean DEFAULT true NOT NULL,
    production_no character varying(32),
    system_type character varying(16) DEFAULT 'PRICING'::character varying,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    CONSTRAINT chk_auxiliary_energy_amortize CHECK (((amortize_basis IS NULL) OR ((amortize_basis)::text = ANY (ARRAY[('HOURS'::character varying)::text, ('QTY'::character varying)::text]))))
);


--
-- Name: basic_data_attribute; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.basic_data_attribute (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    config_id uuid NOT NULL,
    column_letter character varying(10) NOT NULL,
    column_title character varying(200) NOT NULL,
    variable_code character varying(100) NOT NULL,
    variable_label character varying(200) NOT NULL,
    data_type character varying(20) DEFAULT 'VALUE'::character varying NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    importance_level character varying(16) DEFAULT 'NORMAL'::character varying NOT NULL,
    affects_calculation boolean DEFAULT false NOT NULL,
    is_required boolean DEFAULT false NOT NULL,
    CONSTRAINT chk_bda_importance_level CHECK (((importance_level)::text = ANY (ARRAY[('CRITICAL'::character varying)::text, ('IMPORTANT'::character varying)::text, ('NORMAL'::character varying)::text])))
);


--
-- Name: basic_data_change_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.basic_data_change_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    table_name character varying(64) NOT NULL,
    record_id uuid NOT NULL,
    business_key jsonb,
    change_type character varying(16),
    field_changes jsonb,
    version_before integer,
    version_after integer,
    import_record_id uuid,
    changed_by uuid NOT NULL,
    changed_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    remarks text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    field_name character varying(64),
    old_value text,
    new_value text,
    customer_id uuid,
    hf_part_no character varying(64),
    importance character varying(16),
    affects_calculation boolean,
    change_source character varying(32),
    note text,
    CONSTRAINT chk_bdcl_change_type CHECK (((change_type)::text = ANY (ARRAY[('CREATE'::character varying)::text, ('UPDATE'::character varying)::text, ('NEW_VERSION'::character varying)::text, ('SOFT_DELETE'::character varying)::text]))),
    CONSTRAINT chk_bdcl_importance CHECK (((importance IS NULL) OR ((importance)::text = ANY (ARRAY[('CRITICAL'::character varying)::text, ('IMPORTANT'::character varying)::text, ('NORMAL'::character varying)::text])))),
    CONSTRAINT chk_bdcl_source CHECK (((change_source IS NULL) OR ((change_source)::text = ANY (ARRAY[('V5_IMPORT'::character varying)::text, ('MANUAL_EDIT'::character varying)::text, ('SYSTEM_INIT'::character varying)::text, ('SYNC'::character varying)::text]))))
);


--
-- Name: basic_data_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.basic_data_config (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    sheet_name character varying(200) NOT NULL,
    sheet_index integer DEFAULT 0 NOT NULL,
    header_row_index integer DEFAULT 1 NOT NULL,
    data_start_row_index integer DEFAULT 2 NOT NULL,
    description text,
    parent_config_id uuid,
    join_columns jsonb DEFAULT '[]'::jsonb NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    target_table character varying(64),
    target_discriminator jsonb,
    template_kind character varying(20) DEFAULT 'BOTH'::character varying NOT NULL,
    CONSTRAINT chk_bdc_template_kind CHECK (((template_kind)::text = ANY (ARRAY[('QUOTATION'::character varying)::text, ('COSTING'::character varying)::text, ('BOTH'::character varying)::text])))
);


--
-- Name: bnf_table_meta; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bnf_table_meta (
    table_name character varying(120) NOT NULL,
    is_view boolean NOT NULL,
    template_kind character varying(20) DEFAULT 'ALL'::character varying,
    display_name character varying(200),
    picker_visible boolean DEFAULT true,
    last_synced timestamp(6) without time zone DEFAULT now() NOT NULL
);


--
-- Name: capacity; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.capacity (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    material_no character varying(20) NOT NULL,
    material_name character varying(100),
    specification character varying(100),
    dimension character varying(100),
    process_no character varying(20) NOT NULL,
    process_name character varying(50),
    resource_group_no character varying(20) NOT NULL,
    resource_group_name character varying(50),
    production_type character varying(20) NOT NULL,
    fixed_lead_time numeric(18,6),
    variable_time numeric(18,6),
    variable_time_batch numeric(18,6),
    capacity_unit character varying(20),
    default_defect_rate numeric(10,4),
    cost_type character varying(20),
    fixed_cost numeric(18,6),
    cost_ratio numeric(10,4),
    annual_discount_factor numeric(10,4),
    calc_version character varying(20),
    is_effective boolean,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    currency character varying(10),
    seq_no integer,
    version_no integer,
    is_current boolean DEFAULT true NOT NULL,
    system_type character varying(10) NOT NULL,
    production_no character varying(32),
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    pending_quotation_id uuid,
    pending_supersedes uuid[],
    CONSTRAINT chk_capacity_production_type CHECK (((production_type)::text = ANY (ARRAY[('UNIT'::character varying)::text, ('BATCH'::character varying)::text, ('BATCH_FIXED'::character varying)::text]))),
    CONSTRAINT chk_capacity_system_type CHECK (((system_type)::text = ANY ((ARRAY['QUOTE'::character varying, 'PRICING'::character varying, 'BOTH'::character varying])::text[])))
);


--
-- Name: comparison_column_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.comparison_column_config (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_no character varying(64) NOT NULL,
    template_series_id uuid NOT NULL,
    columns jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by uuid
);


--
-- Name: comparison_tag; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.comparison_tag (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(80) NOT NULL,
    label character varying(200) NOT NULL,
    group_name character varying(100) NOT NULL,
    group_sort_order integer DEFAULT 0 NOT NULL,
    tag_sort_order integer DEFAULT 0 NOT NULL,
    is_builtin boolean DEFAULT false NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    description text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: component; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.component (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    directory_id uuid,
    name character varying(200) NOT NULL,
    code character varying(100) NOT NULL,
    column_count integer DEFAULT 0 NOT NULL,
    fields jsonb DEFAULT '[]'::jsonb NOT NULL,
    formulas jsonb DEFAULT '[]'::jsonb NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    component_type character varying(20) DEFAULT 'NORMAL'::character varying NOT NULL,
    data_driver_path text,
    row_key_fields jsonb,
    tree_config jsonb,
    bom_recursive_expand boolean DEFAULT false NOT NULL,
    excel_columns jsonb DEFAULT '[]'::jsonb NOT NULL,
    tab_type character varying(16),
    part_no_field character varying(100),
    part_name_field character varying(100),
    sort_field character varying(120),
    element_code_field character varying(100),
    element_price_field character varying(100),
    element_currency_field character varying(100),
    CONSTRAINT chk_component_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DISABLED'::character varying)::text]))),
    CONSTRAINT chk_component_type CHECK (((component_type)::text = ANY ((ARRAY['NORMAL'::character varying, 'SUBTOTAL'::character varying, 'EXCEL'::character varying])::text[])))
);


--
-- Name: component_code_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.component_code_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: component_directory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.component_directory (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    parent_id uuid,
    name character varying(200) NOT NULL,
    sort_order integer DEFAULT 0,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: component_sql_view; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.component_sql_view (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    component_id uuid NOT NULL,
    sql_view_name character varying(80) NOT NULL,
    sql_template text NOT NULL,
    declared_columns jsonb DEFAULT '[]'::jsonb NOT NULL,
    required_variables text[] DEFAULT '{}'::text[] NOT NULL,
    scope character varying(20) DEFAULT 'COMPONENT'::character varying NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    description text,
    created_by uuid,
    created_at timestamp(6) without time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) without time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_csv_scope CHECK (((scope)::text = ANY (ARRAY[('COMPONENT'::character varying)::text, ('GLOBAL'::character varying)::text]))),
    CONSTRAINT chk_csv_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])))
);


--
-- Name: composite_process_def; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.composite_process_def (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(128) NOT NULL,
    icon character varying(8),
    description text,
    param_schema jsonb DEFAULT '[]'::jsonb NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_composite_process_def_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])))
);


--
-- Name: config_category; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.config_category (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    template_id uuid NOT NULL,
    code character varying(50) NOT NULL,
    name character varying(200) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT config_category_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])))
);


--
-- Name: config_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.config_item (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    category_id uuid NOT NULL,
    code character varying(50) NOT NULL,
    name character varying(200) NOT NULL,
    default_value character varying(500),
    sort_order integer DEFAULT 0 NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT config_item_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])))
);


--
-- Name: config_template; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.config_template (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(50) NOT NULL,
    name character varying(200) NOT NULL,
    description text,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    published_at timestamp(6) with time zone,
    CONSTRAINT config_template_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('ARCHIVED'::character varying)::text])))
);


--
-- Name: costing_bom_tree_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_bom_tree_config (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name text NOT NULL,
    sql_template text NOT NULL,
    is_active boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    usage character varying(16) DEFAULT 'COSTING'::character varying NOT NULL
);


--
-- Name: costing_order; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_order (
    id uuid NOT NULL,
    quotation_id uuid NOT NULL,
    submitted_by uuid,
    entered_costing_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    costing_order_number character varying(64) NOT NULL,
    status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    reject_reason text,
    frozen_dto jsonb,
    total_amount numeric(26,12),
    reviewed_by uuid,
    reviewed_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    costing_render jsonb,
    costing_total_amount numeric(26,12),
    CONSTRAINT chk_co_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'WITHDRAWN'::character varying])::text[])))
);


--
-- Name: costing_order_number_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.costing_order_number_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: costing_order_version_override; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_order_version_override (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    costing_order_id uuid NOT NULL,
    component_id uuid NOT NULL,
    part_no character varying(40) NOT NULL,
    view_version character varying(40) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: costing_template; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_template (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    series_id uuid NOT NULL,
    name character varying(200) NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    version character varying(20) DEFAULT 'v1.0'::character varying NOT NULL,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    description text,
    columns jsonb DEFAULT '[]'::jsonb NOT NULL,
    referenced_variables jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_by uuid,
    published_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    linked_template_id uuid
);


--
-- Name: cpq_feature_field; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cpq_feature_field (
    id bigint NOT NULL,
    group_id bigint NOT NULL,
    code character varying(40) NOT NULL,
    name character varying(255) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    data_type character varying(20) NOT NULL,
    assign_mode character varying(20) NOT NULL,
    is_required boolean DEFAULT false NOT NULL,
    default_value character varying(255),
    min_value character varying(40),
    max_value character varying(40),
    code_length integer,
    decimal_places integer,
    data_source_ref character varying(80),
    partno_prefix character varying(20),
    partno_suffix character varying(20),
    extra_attrs jsonb,
    created_at timestamp(6) without time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) without time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_cpq_ff_assign_mode CHECK (((assign_mode)::text = ANY (ARRAY[('MANUAL'::character varying)::text, ('SELECT'::character varying)::text, ('COMPUTED'::character varying)::text]))),
    CONSTRAINT chk_cpq_ff_data_type CHECK (((data_type)::text = ANY (ARRAY[('STRING'::character varying)::text, ('NUMBER'::character varying)::text, ('DATE'::character varying)::text, ('BOOLEAN'::character varying)::text])))
);


--
-- Name: cpq_feature_field_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.cpq_feature_field_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: cpq_feature_field_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.cpq_feature_field_id_seq OWNED BY public.cpq_feature_field.id;


--
-- Name: cpq_feature_group; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cpq_feature_group (
    id bigint NOT NULL,
    code character varying(40) NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    category character varying(80),
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    erp_ref_code character varying(40),
    extra_attrs jsonb,
    created_by character varying(64),
    created_at timestamp(6) without time zone DEFAULT now() NOT NULL,
    updated_by character varying(64),
    updated_at timestamp(6) without time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_cpq_fg_status CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('ACTIVE'::character varying)::text, ('ARCHIVED'::character varying)::text])))
);


--
-- Name: cpq_feature_group_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.cpq_feature_group_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: cpq_feature_group_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.cpq_feature_group_id_seq OWNED BY public.cpq_feature_group.id;


--
-- Name: cpq_feature_value; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cpq_feature_value (
    id bigint NOT NULL,
    field_id bigint NOT NULL,
    code character varying(40) NOT NULL,
    label character varying(255) NOT NULL,
    description text,
    sort_order integer DEFAULT 0 NOT NULL,
    partno_include boolean DEFAULT true NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    extra_attrs jsonb,
    created_at timestamp(6) without time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) without time zone DEFAULT now() NOT NULL
);


--
-- Name: cpq_feature_value_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.cpq_feature_value_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: cpq_feature_value_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.cpq_feature_value_id_seq OWNED BY public.cpq_feature_value.id;


--
-- Name: customer; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(200) NOT NULL,
    code character varying(50) NOT NULL,
    level character varying(20) DEFAULT 'STANDARD'::character varying NOT NULL,
    industry character varying(100),
    region character varying(100),
    address text,
    accumulated_amount numeric(18,4) DEFAULT 0 NOT NULL,
    credit_limit numeric(18,4),
    payment_method character varying(100),
    remarks text,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    version integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    industry_code character varying(50),
    product_category_id uuid,
    CONSTRAINT chk_customer_level CHECK (((level)::text = ANY (ARRAY[('DIAMOND'::character varying)::text, ('VIP'::character varying)::text, ('GOLD'::character varying)::text, ('SILVER'::character varying)::text, ('STANDARD'::character varying)::text]))),
    CONSTRAINT chk_customer_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])))
);


--
-- Name: customer_code_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.customer_code_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: customer_contact; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_contact (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_id uuid NOT NULL,
    name character varying(200) NOT NULL,
    role character varying(50),
    phone character varying(20) NOT NULL,
    email character varying(200),
    wechat character varying(100),
    is_primary boolean DEFAULT false NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: customer_excel_template; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_excel_template (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(300) NOT NULL,
    customer_id uuid NOT NULL,
    description text,
    header_row_index integer DEFAULT 1 NOT NULL,
    data_start_row_index integer DEFAULT 2 NOT NULL,
    sheet_index integer DEFAULT 0 NOT NULL,
    part_no_column character varying(200) NOT NULL,
    excel_columns jsonb DEFAULT '[]'::jsonb NOT NULL,
    sample_file_name character varying(500),
    created_by uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: customer_lead; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_lead (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    lead_code character varying(40) NOT NULL,
    source_type character varying(32) NOT NULL,
    share_token character varying(64),
    contact_name character varying(128) NOT NULL,
    contact_phone character varying(40) NOT NULL,
    contact_email character varying(128),
    company_name character varying(255),
    note text,
    status character varying(20) DEFAULT 'PENDING_REVIEW'::character varying NOT NULL,
    reviewed_by uuid,
    reviewed_at timestamp(6) with time zone,
    review_action character varying(32),
    bound_customer_id uuid,
    review_note text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_lead_review_action CHECK (((review_action IS NULL) OR ((review_action)::text = ANY (ARRAY[('BIND_EXISTING'::character varying)::text, ('CREATE_NEW'::character varying)::text, ('REJECT'::character varying)::text])))),
    CONSTRAINT chk_lead_status CHECK (((status)::text = ANY (ARRAY[('PENDING_REVIEW'::character varying)::text, ('CONVERTED'::character varying)::text, ('REJECTED'::character varying)::text])))
);


--
-- Name: customer_material_mapping; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_material_mapping (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_id uuid NOT NULL,
    customer_part_no character varying(200) NOT NULL,
    material_id uuid NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: customer_price_adjust_element; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_price_adjust_element (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    strategy_id uuid NOT NULL,
    element_code character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: customer_price_adjust_material; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_price_adjust_material (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    strategy_id uuid NOT NULL,
    material_no character varying(50) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: customer_price_adjust_strategy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_price_adjust_strategy (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_no character varying(64) NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
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
    CONSTRAINT chk_cpas_scope_mode CHECK (((material_scope_mode)::text = ANY ((ARRAY['ALL'::character varying, 'SPECIFIED'::character varying])::text[])))
);


--
-- Name: customer_price_adjust_strategy_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_price_adjust_strategy_log (
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
    CONSTRAINT chk_cpasl_change_type CHECK (((change_type)::text = ANY ((ARRAY['STRATEGY'::character varying, 'MATERIAL_SCOPE'::character varying, 'ELEMENT_LIST'::character varying, 'COMPARISON_COLUMN'::character varying])::text[])))
);


--
-- Name: customer_tax; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_tax (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_id uuid NOT NULL,
    tax_rate numeric(10,4) NOT NULL,
    effective_date date NOT NULL,
    expiry_date date,
    is_current boolean DEFAULT true NOT NULL,
    description text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid
);


--
-- Name: datasource; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.datasource (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(100) NOT NULL,
    name character varying(200) NOT NULL,
    type character varying(10) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    description text,
    sql_query text,
    sql_result_column character varying(100),
    api_url character varying(1000),
    api_method character varying(10),
    api_headers jsonb DEFAULT '[]'::jsonb,
    api_body_template text,
    api_result_path character varying(500),
    api_timeout_seconds integer DEFAULT 5,
    created_by uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_ds_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DISABLED'::character varying)::text]))),
    CONSTRAINT chk_ds_type CHECK (((type)::text = ANY (ARRAY[('SQL'::character varying)::text, ('API'::character varying)::text])))
);


--
-- Name: datasource_param; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.datasource_param (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    datasource_id uuid NOT NULL,
    param_order integer NOT NULL,
    param_code character varying(100) NOT NULL,
    param_name character varying(200) NOT NULL,
    source_type character varying(20) NOT NULL,
    system_param_code character varying(50),
    is_required boolean DEFAULT true NOT NULL,
    description text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_param_source CHECK (((source_type)::text = ANY (ARRAY[('USER_FIELD'::character varying)::text, ('SYSTEM_PARAM'::character varying)::text])))
);


--
-- Name: ddl_operation_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ddl_operation_history (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    table_name character varying(64) NOT NULL,
    column_name character varying(64) NOT NULL,
    data_type character varying(64) NOT NULL,
    default_value text NOT NULL,
    importance character varying(16) DEFAULT 'NORMAL'::character varying NOT NULL,
    affects_calculation boolean DEFAULT false NOT NULL,
    status character varying(16) NOT NULL,
    error_message text,
    migration_content text NOT NULL,
    flyway_version_hint character varying(32),
    created_by uuid NOT NULL,
    created_by_name character varying(128),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_ddl_importance CHECK (((importance)::text = ANY (ARRAY[('CRITICAL'::character varying)::text, ('IMPORTANT'::character varying)::text, ('NORMAL'::character varying)::text]))),
    CONSTRAINT chk_ddl_status CHECK (((status)::text = ANY (ARRAY[('SUCCESS'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: ddl_operation_lock; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ddl_operation_lock (
    lock_key character varying(64) NOT NULL,
    locked_by uuid NOT NULL,
    locked_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    operation_desc text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid
);


--
-- Name: department; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.department (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(50) NOT NULL,
    name character varying(100) NOT NULL,
    sort_order integer DEFAULT 0,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    parent_id uuid,
    CONSTRAINT chk_department_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DISABLED'::character varying)::text])))
);


--
-- Name: derived_attribute; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.derived_attribute (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    host_sheet_id uuid NOT NULL,
    variable_code character varying(100) NOT NULL,
    variable_label character varying(200) NOT NULL,
    data_type character varying(20) DEFAULT 'VALUE'::character varying NOT NULL,
    computation_type character varying(30) NOT NULL,
    computation jsonb NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: electricity_price; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.electricity_price (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    region character varying(50) NOT NULL,
    voltage_level character varying(20),
    price_type character varying(20) NOT NULL,
    time_range character varying(50),
    price numeric(18,6) NOT NULL,
    unit character varying(20),
    effective_date date NOT NULL,
    expire_date date,
    version_no character varying(20),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    is_current boolean DEFAULT true NOT NULL
);


--
-- Name: element; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.element (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    element_code character varying(32) NOT NULL,
    element_name character varying(64) NOT NULL,
    element_no character varying(32) NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_element_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))
);


--
-- Name: element_bom; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.element_bom (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    system_type character varying(10) NOT NULL,
    customer_no character varying(20) NOT NULL,
    bom_type character varying(20) NOT NULL,
    bom_status character varying(20),
    plant character varying(20),
    valid_from date,
    valid_to date,
    material_no character varying(20) NOT NULL,
    characteristic character varying(100) NOT NULL,
    batch_qty character varying(100),
    production_unit character varying(100),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    is_current boolean DEFAULT true NOT NULL,
    production_no character varying(32),
    material_part_no character varying(32),
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    pending_quotation_id uuid,
    pending_supersedes uuid[],
    CONSTRAINT chk_element_bom_status CHECK (((bom_status IS NULL) OR ((bom_status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('RELEASED'::character varying)::text, ('OBSOLETE'::character varying)::text])))),
    CONSTRAINT chk_element_bom_system_type CHECK (((system_type)::text = ANY (ARRAY[('QUOTE'::character varying)::text, ('PRICING'::character varying)::text, ('BOTH'::character varying)::text]))),
    CONSTRAINT chk_element_bom_type CHECK (((bom_type)::text = ANY (ARRAY[('MATERIAL'::character varying)::text, ('ASSEMBLY'::character varying)::text])))
);


--
-- Name: element_bom_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.element_bom_item (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    system_type character varying(10) NOT NULL,
    customer_no character varying(20) NOT NULL,
    material_no character varying(20) NOT NULL,
    characteristic character varying(100) NOT NULL,
    component_no character varying(20),
    part_no character varying(20),
    effective_datetime timestamp(6) with time zone,
    expire_datetime timestamp(6) with time zone,
    operation_no character varying(20),
    operation_seq character varying(20),
    seq_no integer,
    issue_unit character varying(20),
    composition_qty numeric(18,6),
    base_qty numeric(18,6),
    component_usage_type character varying(100),
    feature_mgmt character varying(20),
    content numeric(18,6),
    upper_limit_pct numeric(10,4),
    lower_limit_pct numeric(10,4),
    scrap_batch numeric(18,6),
    scrap_rate numeric(10,4),
    defect_rate numeric(10,4),
    fixed_scrap numeric(18,6),
    issue_location character varying(50),
    issue_storage character varying(50),
    fas_group character varying(20),
    plug_position character varying(50),
    ref_rd_center character varying(50),
    is_optional boolean,
    wo_expand_option character varying(20),
    is_purchase_replace boolean,
    component_lead_time numeric(18,6),
    main_substitute character varying(20),
    attached_part character varying(20),
    ecn_no character varying(30),
    use_qty_formula boolean,
    qty_formula character varying(500),
    scrap_rate_type character varying(20),
    is_backflush boolean,
    is_customer_supply boolean,
    recovery_discount numeric(10,4),
    recovery_currency character varying(10),
    recovery_unit character varying(20),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    hf_part_no character varying(20),
    is_current boolean DEFAULT true NOT NULL,
    production_no character varying(32),
    material_part_no character varying(32),
    pending_quotation_id uuid,
    pending_supersedes uuid[],
    CONSTRAINT chk_element_bom_item_system_type CHECK (((system_type)::text = ANY (ARRAY[('QUOTE'::character varying)::text, ('PRICING'::character varying)::text, ('BOTH'::character varying)::text])))
);


--
-- Name: element_daily_price; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.element_daily_price (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    element_name character varying(64) NOT NULL,
    source_id uuid,
    price_date date NOT NULL,
    raw_price numeric(18,4),
    raw_high numeric(18,4),
    raw_low numeric(18,4),
    raw_open numeric(18,4),
    raw_close numeric(18,4),
    currency character varying(8),
    price_unit character varying(16),
    fetch_status character varying(16) DEFAULT 'MANUAL'::character varying NOT NULL,
    fetch_error text,
    fetched_at timestamp(6) with time zone,
    manually_filled_by uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    CONSTRAINT chk_edp_fetch_status CHECK (((fetch_status)::text = ANY ((ARRAY['SUCCESS'::character varying, 'FAILED'::character varying, 'MANUAL'::character varying, 'IMPORT'::character varying])::text[])))
);


--
-- Name: element_daily_price_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.element_daily_price_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    price_id uuid,
    element_name character varying(64) NOT NULL,
    source_id uuid,
    price_date date NOT NULL,
    action character varying(16) NOT NULL,
    snapshot jsonb NOT NULL,
    changed_at timestamp with time zone DEFAULT now() NOT NULL,
    changed_by uuid,
    changed_by_name character varying(100),
    CONSTRAINT chk_edpl_action CHECK (((action)::text = ANY ((ARRAY['CREATE'::character varying, 'UPDATE'::character varying, 'DELETE'::character varying])::text[])))
);


--
-- Name: element_price; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.element_price (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_id uuid NOT NULL,
    element_name character varying(64) NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    is_current boolean DEFAULT true NOT NULL,
    source_id uuid,
    fetch_rule_id uuid,
    premium_price numeric(18,4),
    currency character varying(8),
    price_unit character varying(16),
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    imported_by uuid,
    import_record_id uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    CONSTRAINT chk_element_price_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DELETED'::character varying)::text])))
);


--
-- Name: element_price_fetch_rule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.element_price_fetch_rule (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    rule_name character varying(128) NOT NULL,
    rule_code character varying(64) NOT NULL,
    rule_definition jsonb,
    description text,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    CONSTRAINT chk_epfr_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DISABLED'::character varying)::text])))
);


--
-- Name: element_price_source; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.element_price_source (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    source_name character varying(128) NOT NULL,
    source_url character varying(256),
    source_type character varying(16) DEFAULT 'MANUAL'::character varying NOT NULL,
    description text,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    CONSTRAINT chk_eps_source_type CHECK (((source_type)::text = ANY (ARRAY[('HTML_SCRAPE'::character varying)::text, ('API'::character varying)::text, ('MANUAL'::character varying)::text]))),
    CONSTRAINT chk_eps_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DISABLED'::character varying)::text])))
);


--
-- Name: element_price_strategy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.element_price_strategy (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_no character varying(64) NOT NULL,
    element_code character varying(64),
    source_id uuid NOT NULL,
    method character varying(16) NOT NULL,
    window_num integer,
    window_unit character varying(8),
    factor numeric(10,4) DEFAULT 1 NOT NULL,
    premium numeric(18,4) DEFAULT 0 NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by uuid,
    CONSTRAINT chk_eps2_factor CHECK ((factor > (0)::numeric)),
    CONSTRAINT chk_eps2_method CHECK (((method)::text = ANY ((ARRAY['LATEST'::character varying, 'AVG'::character varying, 'MAX'::character varying, 'MIN'::character varying])::text[]))),
    CONSTRAINT chk_eps2_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying])::text[]))),
    CONSTRAINT chk_eps2_unit CHECK (((window_unit IS NULL) OR ((window_unit)::text = ANY ((ARRAY['DAY'::character varying, 'WEEK'::character varying, 'MONTH'::character varying, 'YEAR'::character varying])::text[])))),
    CONSTRAINT chk_eps2_window CHECK (((((method)::text = 'LATEST'::text) AND (window_num IS NULL) AND (window_unit IS NULL)) OR (((method)::text <> 'LATEST'::text) AND (window_num > 0) AND (window_unit IS NOT NULL))))
);


--
-- Name: element_price_strategy_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.element_price_strategy_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    strategy_id uuid,
    customer_no character varying(64) NOT NULL,
    element_code character varying(64),
    action character varying(16) NOT NULL,
    snapshot jsonb NOT NULL,
    changed_at timestamp with time zone DEFAULT now() NOT NULL,
    changed_by uuid,
    changed_by_name character varying(100),
    CONSTRAINT chk_epsl_action CHECK (((action)::text = ANY ((ARRAY['CREATE'::character varying, 'UPDATE'::character varying, 'DELETE'::character varying])::text[])))
);


--
-- Name: element_price_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.element_price_version (
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
    CONSTRAINT chk_epv_trigger_type CHECK (((trigger_type)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'MANUAL'::character varying])::text[])))
);


--
-- Name: element_price_version_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.element_price_version_item (
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
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: equipment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.equipment (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    equipment_no character varying(30) NOT NULL,
    equipment_name character varying(100) NOT NULL,
    equipment_type character varying(50),
    resource_group_no character varying(20) NOT NULL,
    resource_group_name character varying(50),
    workshop character varying(50),
    original_amount numeric(18,2) NOT NULL,
    residual_value numeric(18,2),
    depreciation_method character varying(30) NOT NULL,
    depreciation_years numeric(10,2),
    annual_available_hours numeric(18,2) NOT NULL,
    production_calendar character varying(50),
    purchase_date date,
    annual_depreciation numeric(18,6),
    hourly_depreciation numeric(18,6),
    currency character varying(10),
    status character varying(20),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    CONSTRAINT chk_equipment_depreciation_method CHECK (((depreciation_method)::text = ANY (ARRAY[('STRAIGHT_LINE'::character varying)::text, ('SUM_YEARS'::character varying)::text, ('DOUBLE_DECLINING'::character varying)::text, ('UNITS'::character varying)::text]))),
    CONSTRAINT chk_equipment_status CHECK (((status IS NULL) OR ((status)::text = ANY (ARRAY[('IN_USE'::character varying)::text, ('IDLE'::character varying)::text, ('SCRAPPED'::character varying)::text]))))
);


--
-- Name: exchange_rate; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.exchange_rate (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_id uuid,
    from_currency character varying(8) NOT NULL,
    to_currency character varying(8) NOT NULL,
    rate numeric(18,6) NOT NULL,
    effective_date date NOT NULL,
    is_current boolean DEFAULT true NOT NULL,
    source character varying(64),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid
);


--
-- Name: exchange_rate_v6; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.exchange_rate_v6 (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    version_no character varying(20) NOT NULL,
    base_currency character varying(10) NOT NULL,
    target_currency character varying(10) NOT NULL,
    rate numeric(18,8) NOT NULL,
    ref_rate numeric(18,8),
    ref_fetch_rule character varying(200),
    ref_source_url character varying(500),
    effective_date date,
    expire_date date,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    is_current boolean DEFAULT true NOT NULL
);


--
-- Name: fee_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fee_config (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    system_type character varying(10) NOT NULL,
    biz_type character varying(30) NOT NULL,
    fee_no character varying(30) NOT NULL,
    fee_name character varying(100) NOT NULL,
    material_no character varying(20),
    customer_no character varying(20),
    region character varying(50),
    charge_basis character varying(20),
    value numeric(18,6),
    ratio numeric(10,4),
    currency character varying(10),
    unit character varying(20),
    effective_date date,
    expire_date date,
    pricing_version_no character varying(20),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    dim_input_material_no character varying(20),
    dim_sub_seq_no integer,
    dim_element_name character varying(100),
    is_current boolean DEFAULT true NOT NULL,
    CONSTRAINT chk_fee_config_biz_type CHECK (((biz_type)::text = ANY (ARRAY[('PROFIT'::character varying)::text, ('TAX'::character varying)::text, ('FREIGHT'::character varying)::text, ('CUSTOMS'::character varying)::text, ('INSURANCE'::character varying)::text, ('BANK'::character varying)::text, ('OTHER'::character varying)::text]))),
    CONSTRAINT chk_fee_config_charge_basis CHECK (((charge_basis IS NULL) OR ((charge_basis)::text = ANY (ARRAY[('RATE'::character varying)::text, ('FIXED'::character varying)::text, ('PER_UNIT'::character varying)::text, ('PER_KG'::character varying)::text])))),
    CONSTRAINT chk_fee_config_system_type CHECK (((system_type)::text = ANY (ARRAY[('QUOTE'::character varying)::text, ('PRICING'::character varying)::text])))
);


--
-- Name: global_variable_change_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.global_variable_change_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    var_code character varying(64) NOT NULL,
    key_id character varying(200) NOT NULL,
    action character varying(20) NOT NULL,
    old_value numeric(20,10),
    new_value numeric(20,10),
    changed_by uuid,
    changed_by_name character varying(100),
    note text,
    changed_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_gvcl_action CHECK (((action)::text = ANY (ARRAY[('INSERT'::character varying)::text, ('UPDATE'::character varying)::text, ('DELETE'::character varying)::text])))
);


--
-- Name: global_variable_definition; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.global_variable_definition (
    code character varying(64) NOT NULL,
    name character varying(100) NOT NULL,
    var_type character varying(20) DEFAULT 'LOOKUP_TABLE'::character varying NOT NULL,
    source_view character varying(100),
    key_columns jsonb DEFAULT '[]'::jsonb NOT NULL,
    value_column character varying(100) NOT NULL,
    label_template character varying(200),
    unit character varying(20),
    description text,
    sort_order integer DEFAULT 0,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    value_source_type character varying(32) DEFAULT 'KV_TABLE'::character varying NOT NULL,
    visibility character varying(32) DEFAULT 'PUBLIC'::character varying NOT NULL,
    CONSTRAINT chk_gvd_value_source_type CHECK (((value_source_type)::text = ANY (ARRAY[('KV_TABLE'::character varying)::text, ('COSTING_VIEW'::character varying)::text]))),
    CONSTRAINT chk_gvd_var_type CHECK (((var_type)::text = ANY (ARRAY[('LOOKUP_TABLE'::character varying)::text, ('SCALAR'::character varying)::text]))),
    CONSTRAINT chk_gvd_visibility CHECK (((visibility)::text = ANY (ARRAY[('PUBLIC'::character varying)::text, ('COSTING_INTERNAL'::character varying)::text])))
);


--
-- Name: global_variable_value; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.global_variable_value (
    var_code character varying(64) NOT NULL,
    key_id character varying(200) NOT NULL,
    key_values jsonb DEFAULT '{}'::jsonb NOT NULL,
    value_number numeric(20,4),
    value_text text,
    note text,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: import_mapping_template; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_mapping_template (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(300) NOT NULL,
    excel_template_id uuid NOT NULL,
    template_id uuid NOT NULL,
    column_mappings jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_by uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: import_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_record (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    quotation_id uuid,
    customer_id uuid,
    excel_template_id uuid,
    mapping_template_id uuid,
    mapping_snapshot jsonb,
    original_file_name character varying(500) NOT NULL,
    original_file_path character varying(1000),
    total_rows integer,
    success_rows integer,
    matched_rows integer,
    unmatched_rows integer,
    import_status character varying(20) NOT NULL,
    error_detail jsonb,
    imported_by uuid NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    template_id uuid,
    config_snapshot jsonb,
    costing_template_id uuid,
    customer_template_id uuid,
    costing_template_snapshot jsonb,
    customer_template_snapshot jsonb,
    import_batch_id uuid,
    metadata jsonb,
    system_type character varying(20),
    CONSTRAINT chk_ir_status CHECK (((import_status)::text = ANY (ARRAY[('SUCCESS'::character varying)::text, ('PARTIAL'::character varying)::text, ('FAILED'::character varying)::text, ('COMPLETED'::character varying)::text, ('PROCESSING'::character varying)::text])))
);


--
-- Name: import_session; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_session (
    id uuid NOT NULL,
    customer_id uuid NOT NULL,
    user_id uuid,
    status text DEFAULT 'PENDING'::text NOT NULL,
    source_excel text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    expires_at timestamp(6) with time zone DEFAULT (now() + '24:00:00'::interval) NOT NULL,
    committed_at timestamp(6) with time zone
);


--
-- Name: import_session_decision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.import_session_decision (
    import_session_id uuid NOT NULL,
    decision_type text NOT NULL,
    decision_key text NOT NULL,
    decision_value jsonb NOT NULL
);


--
-- Name: industry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.industry (
    id uuid NOT NULL,
    code character varying(50) NOT NULL,
    name character varying(100) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    version integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: internal_material; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.internal_material (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    material_no character varying(100) NOT NULL,
    name character varying(200) NOT NULL,
    specification character varying(500),
    size character varying(200),
    status_code character varying(10) DEFAULT 'Y'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_im_status CHECK (((status_code)::text = ANY (ARRAY[('Y'::character varying)::text, ('N'::character varying)::text])))
);


--
-- Name: labor_rate; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.labor_rate (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    version_no character varying(20) NOT NULL,
    material_no character varying(20),
    process_no character varying(20) NOT NULL,
    process_name character varying(50),
    labor_grade character varying(30),
    standard_labor_rate numeric(18,6) NOT NULL,
    currency character varying(10),
    unit character varying(20),
    effective_date date,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    is_current boolean DEFAULT true NOT NULL,
    production_no character varying(32),
    system_type character varying(16) DEFAULT 'PRICING'::character varying,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL
);


--
-- Name: material_bom; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_bom (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    system_type character varying(10) NOT NULL,
    customer_no character varying(20) NOT NULL,
    bom_type character varying(20) NOT NULL,
    bom_version character varying(20) NOT NULL,
    bom_status character varying(20),
    plant character varying(20),
    valid_from date,
    valid_to date,
    material_no character varying(20) NOT NULL,
    characteristic character varying(100),
    batch_qty character varying(100),
    production_unit character varying(100),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    is_current boolean DEFAULT true NOT NULL,
    production_no character varying(32),
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    pending_quotation_id uuid,
    pending_supersedes uuid[],
    CONSTRAINT chk_material_bom_status CHECK (((bom_status IS NULL) OR ((bom_status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('RELEASED'::character varying)::text, ('OBSOLETE'::character varying)::text])))),
    CONSTRAINT chk_material_bom_system_type CHECK (((system_type)::text = ANY (ARRAY[('QUOTE'::character varying)::text, ('PRICING'::character varying)::text, ('BOTH'::character varying)::text]))),
    CONSTRAINT chk_material_bom_type CHECK (((bom_type)::text = ANY (ARRAY[('MATERIAL'::character varying)::text, ('ASSEMBLY'::character varying)::text])))
);


--
-- Name: material_bom_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_bom_item (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    system_type character varying(10) NOT NULL,
    customer_no character varying(20) NOT NULL,
    material_no character varying(20) NOT NULL,
    characteristic character varying(100),
    seq_no integer,
    component_no character varying(20),
    part_no character varying(20),
    effective_datetime timestamp(6) with time zone,
    expire_datetime timestamp(6) with time zone,
    operation_no character varying(20),
    operation_seq character varying(20),
    item_seq integer,
    issue_unit character varying(20),
    composition_qty numeric(18,6),
    base_qty numeric(18,6),
    component_usage_type character varying(100),
    feature_mgmt character varying(20),
    upper_limit_pct numeric(10,4),
    lower_limit_pct numeric(10,4),
    scrap_batch numeric(18,6),
    scrap_rate numeric(10,4),
    fixed_scrap numeric(18,6),
    issue_location character varying(50),
    issue_storage character varying(50),
    fas_group character varying(20),
    plug_position character varying(50),
    ref_rd_center character varying(50),
    is_optional boolean,
    wo_expand_option character varying(20),
    is_purchase_replace boolean,
    component_lead_time numeric(18,6),
    main_substitute character varying(20),
    attached_part character varying(20),
    ecn_no character varying(30),
    use_qty_formula boolean,
    qty_formula character varying(500),
    scrap_rate_type character varying(20),
    is_backflush boolean,
    is_customer_supply boolean,
    defect_rate numeric(10,4),
    calc_type character varying(20),
    recovery_discount numeric(10,4),
    recovery_currency character varying(10),
    recovery_unit character varying(20),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    is_current boolean DEFAULT true NOT NULL,
    bom_version character varying(20),
    rough_weight numeric(20,6) DEFAULT NULL::numeric,
    net_weight numeric(20,6) DEFAULT NULL::numeric,
    weight_unit character varying(20),
    production_no character varying(32),
    pending_quotation_id uuid,
    pending_supersedes uuid[],
    material_ratio numeric(18,6),
    CONSTRAINT chk_material_bom_item_system_type CHECK (((system_type)::text = ANY (ARRAY[('QUOTE'::character varying)::text, ('PRICING'::character varying)::text, ('BOTH'::character varying)::text])))
);


--
-- Name: material_customer_map; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_customer_map (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    material_no character varying(20) NOT NULL,
    customer_no character varying(20) NOT NULL,
    customer_name character varying(100),
    customer_material_name character varying(100),
    customer_product_no character varying(50),
    customer_drawing_no character varying(50),
    seq_no integer,
    payment_method character varying(50),
    base_currency character varying(10),
    quote_currency character varying(10),
    exchange_rate numeric(18,8),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    system_type character varying(20) NOT NULL,
    production_no character varying(20),
    pending_quotation_id uuid
);


--
-- Name: material_master; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_master (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    material_no character varying(20) NOT NULL,
    material_name character varying(100),
    specification character varying(100),
    dimension character varying(100),
    old_material_no character varying(50),
    material_type character varying(50),
    usage_property character varying(50),
    unit_weight numeric(18,6),
    standard_unit character varying(20),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    material_recipe_id uuid,
    config_fingerprint character varying(80),
    production_no character varying(32),
    pending_quotation_id uuid
);


--
-- Name: material_price_review; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_price_review (
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
    warn_code character varying(50),
    warn_message text,
    warn_diff numeric(26,12),
    CONSTRAINT chk_mpr_budget_status CHECK (((budget_status)::text = ANY ((ARRAY['QUEUED'::character varying, 'COMPUTING'::character varying, 'READY'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT chk_mpr_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'VOIDED'::character varying])::text[])))
);


--
-- Name: material_price_review_column; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_price_review_column (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    review_id uuid NOT NULL,
    column_id character varying(50) NOT NULL,
    column_label character varying(200),
    threshold numeric(20,6),
    sort_order integer DEFAULT 0 NOT NULL,
    quote_current numeric(26,12),
    quote_adjusted numeric(26,12),
    costing_current numeric(26,12),
    costing_adjusted numeric(26,12),
    diff_current numeric(26,12),
    diff_adjusted numeric(26,12),
    status character varying(20) NOT NULL,
    missing_side character varying(20),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_mprc_status CHECK (((status)::text = ANY ((ARRAY['RED'::character varying, 'AMBER'::character varying, 'NORMAL'::character varying, 'MISSING'::character varying, 'STALE'::character varying])::text[])))
);


--
-- Name: material_price_update_job; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_price_update_job (
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
    skipped_count integer DEFAULT 0 NOT NULL,
    CONSTRAINT chk_mpuj_status CHECK (((status)::text = ANY ((ARRAY['RUNNING'::character varying, 'SUCCESS'::character varying, 'PARTIAL'::character varying, 'FAILED'::character varying, 'STALE'::character varying])::text[])))
);


--
-- Name: material_price_update_job_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_price_update_job_item (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    job_id uuid NOT NULL,
    quotation_id uuid NOT NULL,
    material_no character varying(50) NOT NULL,
    line_item_id uuid,
    status character varying(20) DEFAULT 'WAITING'::character varying NOT NULL,
    error_code character varying(50),
    error_message text,
    diff_value numeric(26,12),
    retry_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    warn_code character varying(50),
    warn_message text,
    CONSTRAINT chk_mpuji_status CHECK (((status)::text = ANY (ARRAY['WAITING'::text, 'RUNNING'::text, 'SUCCESS'::text, 'FAILED'::text, 'CONFLICT'::text, 'STALE'::text, 'SKIPPED'::text])))
);


--
-- Name: material_price_version_ref; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_price_version_ref (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_no character varying(64) NOT NULL,
    material_no character varying(50) NOT NULL,
    version_id uuid NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: material_recipe; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_recipe (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(64) NOT NULL,
    symbol character varying(32) NOT NULL,
    name character varying(128),
    spec_label character varying(64),
    recipe_type character varying(16) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    CONSTRAINT chk_material_recipe_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text]))),
    CONSTRAINT chk_material_recipe_type CHECK (((recipe_type)::text = ANY (ARRAY[('locked'::character varying)::text, ('editable'::character varying)::text, ('partial'::character varying)::text])))
);


--
-- Name: material_recipe_element; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_recipe_element (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    recipe_id uuid NOT NULL,
    element_code character varying(32) NOT NULL,
    element_name character varying(64) NOT NULL,
    default_pct numeric(8,4) NOT NULL,
    min_pct numeric(8,4),
    max_pct numeric(8,4),
    is_locked boolean DEFAULT false NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    element_no character varying(32),
    CONSTRAINT chk_recipe_element_range CHECK ((((is_locked = true) AND (min_pct IS NULL) AND (max_pct IS NULL)) OR ((is_locked = false) AND (min_pct IS NOT NULL) AND (max_pct IS NOT NULL) AND (min_pct <= max_pct))))
);


--
-- Name: material_version_mgmt; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_version_mgmt (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    material_no character varying(20) NOT NULL,
    customer_no character varying(20),
    material_name character varying(100),
    specification character varying(100),
    dimension character varying(100),
    seq_no integer NOT NULL,
    pricing_version_no character varying(20) NOT NULL,
    pricing_version_name character varying(50),
    element_price_version character varying(20),
    material_price_version character varying(20),
    exchange_rate_version character varying(20),
    is_effective boolean DEFAULT true NOT NULL,
    effective_date date,
    expire_date date,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    is_current boolean DEFAULT true NOT NULL
);


--
-- Name: model_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.model_config (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    subject_type character varying(20) NOT NULL,
    subject_key character varying(64) NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    is_current boolean DEFAULT true NOT NULL,
    label character varying(255),
    glb_url text NOT NULL,
    thumbnail_url text,
    mesh_count integer,
    vertices integer,
    size_kb integer,
    metadata jsonb DEFAULT '{}'::jsonb,
    uploaded_by uuid,
    uploaded_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_mc_subject CHECK (((subject_type)::text = ANY ((ARRAY['SALES_PART'::character varying, 'MATERIAL'::character varying])::text[])))
);


--
-- Name: model_config_file; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.model_config_file (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    model_config_id uuid NOT NULL,
    file_role character varying(20) NOT NULL,
    file_url text NOT NULL,
    file_size_bytes bigint,
    md5_hash character varying(64),
    uploaded_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_mcf_role CHECK (((file_role)::text = ANY ((ARRAY['GLB'::character varying, 'THUMBNAIL'::character varying, 'OTHER'::character varying])::text[])))
);


--
-- Name: notification; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notification (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    recipient_id uuid NOT NULL,
    type character varying(50) NOT NULL,
    title character varying(500) NOT NULL,
    content text,
    link character varying(500),
    related_type character varying(50),
    related_id uuid,
    is_read boolean DEFAULT false NOT NULL,
    read_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_notification_type CHECK (((type)::text = ANY ((ARRAY['APPROVAL_SUBMITTED'::character varying, 'APPROVAL_APPROVED'::character varying, 'APPROVAL_REJECTED'::character varying, 'APPROVAL_REMINDER'::character varying, 'PASSWORD_RESET'::character varying, 'ROLE_CHANGED'::character varying, 'SYSTEM'::character varying, 'PRICE_ADJUST_JOB_SUMMARY'::character varying, 'PRICE_ADJUST_QUOTATION_REVIEW'::character varying])::text[])))
);


--
-- Name: operation_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.operation_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    operator_id uuid NOT NULL,
    operation_type character varying(50) NOT NULL,
    target_type character varying(50) NOT NULL,
    target_id uuid,
    summary text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    details jsonb
);


--
-- Name: packaging_consumable; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.packaging_consumable (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    material_no character varying(20) NOT NULL,
    material_name character varying(100),
    specification character varying(100),
    dimension character varying(100),
    seq_no integer NOT NULL,
    consumable_no character varying(30) NOT NULL,
    consumable_name character varying(100),
    usage_qty numeric(18,6) NOT NULL,
    usage_unit character varying(20),
    packaging_level character varying(20),
    packaging_version character varying(20),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    is_current boolean DEFAULT true NOT NULL,
    CONSTRAINT chk_packaging_consumable_level CHECK (((packaging_level IS NULL) OR ((packaging_level)::text = ANY (ARRAY[('INNER'::character varying)::text, ('MIDDLE'::character varying)::text, ('OUTER'::character varying)::text, ('PALLET'::character varying)::text]))))
);


--
-- Name: part_no_sequence; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.part_no_sequence (
    prefix character varying(32) NOT NULL,
    next_val bigint DEFAULT 1 NOT NULL
);


--
-- Name: password_reset_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.password_reset_token (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    token_hash character varying(255) NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    used_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: plating_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plating_fee (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_id uuid NOT NULL,
    hf_part_no character varying(64) NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    is_current boolean DEFAULT true NOT NULL,
    plating_plan_code character varying(32),
    plan_version character varying(16),
    plating_process_fee numeric(18,4),
    plating_material_fee numeric(18,4),
    currency character varying(8),
    price_unit character varying(16),
    defect_rate numeric(10,4),
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    imported_by uuid,
    import_record_id uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    CONSTRAINT chk_plating_fee_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DELETED'::character varying)::text])))
);


--
-- Name: plating_scheme; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plating_scheme (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    scheme_no character varying(20) NOT NULL,
    scheme_version character varying(20) NOT NULL,
    seq_no integer NOT NULL,
    plating_element character varying(20) NOT NULL,
    plating_method character varying(30) NOT NULL,
    surface_area numeric(18,6) NOT NULL,
    plating_area numeric(18,6),
    plating_thickness numeric(18,6) NOT NULL,
    plating_requirement character varying(200),
    density numeric(18,6),
    element_usage numeric(18,6) NOT NULL,
    element_usage_unit character varying(20),
    effective_date date,
    expire_date date,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    source_url character varying(500),
    source_name character varying(100),
    fetch_rule character varying(200),
    hf_part_no character varying(20),
    is_current boolean DEFAULT true NOT NULL,
    system_type character varying(10) NOT NULL,
    pending_quotation_id uuid,
    pending_supersedes uuid[],
    CONSTRAINT chk_plating_scheme_system_type CHECK (((system_type)::text = ANY ((ARRAY['QUOTE'::character varying, 'PRICING'::character varying, 'BOTH'::character varying])::text[])))
);


--
-- Name: price_adjust_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.price_adjust_settings (
    id smallint DEFAULT 1 NOT NULL,
    subtotal_guard_threshold numeric(20,6) DEFAULT 0.01 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by uuid,
    subtotal_guard_enabled boolean DEFAULT false NOT NULL,
    CONSTRAINT chk_pas_singleton CHECK ((id = 1)),
    CONSTRAINT chk_pas_threshold_nonneg CHECK ((subtotal_guard_threshold >= (0)::numeric))
);


--
-- Name: pricing_rule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pricing_rule (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    strategy_id uuid NOT NULL,
    rule_type character varying(30) DEFAULT 'BULK_DISCOUNT'::character varying NOT NULL,
    threshold_amount numeric(18,4) NOT NULL,
    discount_rate numeric(5,2) NOT NULL,
    sort_order integer DEFAULT 0,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_pr_discount_rate CHECK (((discount_rate >= (0)::numeric) AND (discount_rate <= (100)::numeric)))
);


--
-- Name: pricing_strategy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pricing_strategy (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_id uuid NOT NULL,
    name character varying(200) NOT NULL,
    type character varying(20) DEFAULT 'DISCOUNT'::character varying NOT NULL,
    base_discount numeric(5,2) DEFAULT 100 NOT NULL,
    min_order_amount numeric(18,4) DEFAULT 0 NOT NULL,
    effective_date date,
    expiration_date date,
    priority integer DEFAULT 1 NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_ps_base_discount CHECK (((base_discount >= (0)::numeric) AND (base_discount <= (100)::numeric))),
    CONSTRAINT chk_ps_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('EXPIRED'::character varying)::text, ('DISABLED'::character varying)::text])))
);


--
-- Name: process; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.process (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(50) NOT NULL,
    name character varying(200) NOT NULL,
    description text,
    category character varying(30) NOT NULL,
    is_required boolean DEFAULT false NOT NULL,
    sort_order integer DEFAULT 0,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_process_category CHECK (((category)::text = ANY (ARRAY[('SURFACE_TREATMENT'::character varying)::text, ('MACHINING'::character varying)::text, ('HEAT_TREATMENT'::character varying)::text, ('ASSEMBLY'::character varying)::text, ('INSPECTION'::character varying)::text, ('PACKAGING'::character varying)::text]))),
    CONSTRAINT chk_process_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DISABLED'::character varying)::text])))
);


--
-- Name: process_master; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.process_master (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    process_no character varying(20) NOT NULL,
    process_name character varying(50) NOT NULL,
    process_category character varying(30),
    is_outsource boolean,
    standard_currency character varying(10),
    standard_unit character varying(20),
    default_defect_rate numeric(10,4),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid
);


--
-- Name: product; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(200) NOT NULL,
    part_no character varying(100) NOT NULL,
    category character varying(30) NOT NULL,
    specification character varying(500),
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    tags jsonb DEFAULT '[]'::jsonb,
    external_id character varying(200),
    last_synced_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    drawing_no character varying(200),
    dimension character varying(200),
    material character varying(200),
    category_id uuid,
    CONSTRAINT chk_product_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])))
);


--
-- Name: product_category; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_category (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(50) NOT NULL,
    name character varying(100) NOT NULL,
    description text,
    parent_id uuid,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: product_config_3d_rule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_config_3d_rule (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    option_value_id uuid NOT NULL,
    action character varying(32) NOT NULL,
    target_mesh character varying(128),
    params jsonb DEFAULT '{}'::jsonb NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_pc3d_action CHECK (((action)::text = ANY (ARRAY[('SHOW_MESH'::character varying)::text, ('HIDE_MESH'::character varying)::text, ('REPLACE_MATERIAL'::character varying)::text, ('SWAP_MESH'::character varying)::text, ('TRANSFORM_MESH'::character varying)::text])))
);


--
-- Name: product_config_constraint; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_config_constraint (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    template_id uuid NOT NULL,
    constraint_type character varying(32) NOT NULL,
    trigger_expr jsonb NOT NULL,
    affected_expr jsonb NOT NULL,
    message text,
    severity character varying(16) DEFAULT 'ERROR'::character varying NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_pcc_severity CHECK (((severity)::text = ANY (ARRAY[('ERROR'::character varying)::text, ('WARN'::character varying)::text, ('INFO'::character varying)::text]))),
    CONSTRAINT chk_pcc_type CHECK (((constraint_type)::text = ANY (ARRAY[('REQUIRES'::character varying)::text, ('EXCLUDES'::character varying)::text, ('IMPLIES'::character varying)::text, ('HIDES'::character varying)::text, ('NUMERIC_RANGE'::character varying)::text])))
);


--
-- Name: product_config_instance; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_config_instance (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    instance_code character varying(40) NOT NULL,
    template_id uuid NOT NULL,
    template_version integer,
    name character varying(128),
    customer_id uuid,
    customer_lead_id uuid,
    user_id uuid,
    share_token character varying(64),
    selected_values jsonb DEFAULT '{}'::jsonb NOT NULL,
    config_fingerprint character varying(64),
    computed_total_price numeric(18,4),
    base_price numeric(18,4),
    status character varying(16) DEFAULT 'DRAFT'::character varying NOT NULL,
    linked_quotation_id uuid,
    linked_at timestamp(6) with time zone,
    linked_by uuid,
    generated_part_no character varying(64),
    generated_quotation_id uuid,
    generated_line_item_id uuid,
    expires_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_pci_status CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('SUBMITTED'::character varying)::text, ('LINKED'::character varying)::text, ('EXPIRED'::character varying)::text])))
);


--
-- Name: product_config_instance_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_config_instance_history (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    instance_id uuid NOT NULL,
    action character varying(32) NOT NULL,
    actor_user_id uuid,
    before_snapshot jsonb,
    after_snapshot jsonb,
    note text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: product_config_option; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_config_option (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    template_id uuid NOT NULL,
    code character varying(64) NOT NULL,
    label character varying(128) NOT NULL,
    option_type character varying(32) NOT NULL,
    data_type character varying(20),
    assign_mode character varying(20),
    is_required boolean DEFAULT true NOT NULL,
    default_value character varying(128),
    min_value character varying(40),
    max_value character varying(40),
    partno_prefix character varying(20),
    partno_suffix character varying(20),
    sort_order integer DEFAULT 0 NOT NULL,
    description text,
    metadata jsonb DEFAULT '{}'::jsonb,
    source_feature_field_id bigint,
    source_feature_snapshot_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_pco_option_type CHECK (((option_type)::text = ANY (ARRAY[('EXCLUSIVE'::character varying)::text, ('MULTI_SELECT'::character varying)::text, ('NUMERIC'::character varying)::text, ('TEXT'::character varying)::text, ('COLOR'::character varying)::text])))
);


--
-- Name: product_config_option_value; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_config_option_value (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    option_id uuid NOT NULL,
    code character varying(64) NOT NULL,
    label character varying(128) NOT NULL,
    description text,
    price_delta numeric(18,4) DEFAULT 0 NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    partno_include boolean DEFAULT true NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    feature_type character varying(40),
    attributes jsonb,
    tags text[],
    geometry_ref jsonb,
    sub_model_part_no character varying(64),
    attach_mode character varying(20),
    attach_position jsonb,
    replace_base_mesh boolean DEFAULT false,
    source_feature_value_id bigint,
    source_feature_snapshot_at timestamp(6) with time zone,
    local_only boolean DEFAULT false NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: product_config_share; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_config_share (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    instance_id uuid NOT NULL,
    share_type character varying(32) NOT NULL,
    share_token character varying(64) NOT NULL,
    shared_by uuid,
    shared_to_user_id uuid,
    shared_to_email character varying(128),
    expires_at timestamp(6) with time zone,
    access_count integer DEFAULT 0 NOT NULL,
    last_accessed_at timestamp(6) with time zone,
    can_modify boolean DEFAULT false NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    revoked_at timestamp(6) with time zone,
    revoked_by uuid,
    revoke_reason text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_pcs_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('EXPIRED'::character varying)::text, ('REVOKED'::character varying)::text]))),
    CONSTRAINT chk_pcs_type CHECK (((share_type)::text = ANY (ARRAY[('CUSTOMER_SELF'::character varying)::text, ('INTERNAL'::character varying)::text, ('PUBLIC_PRESET'::character varying)::text])))
);


--
-- Name: product_config_share_access; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_config_share_access (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    share_id uuid NOT NULL,
    accessed_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    ip character varying(64),
    user_agent text,
    action character varying(255)
);


--
-- Name: product_config_template; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_config_template (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(128) NOT NULL,
    category character varying(80),
    base_part_no character varying(64),
    base_model_id uuid,
    base_model_version integer,
    base_model_snapshot_at timestamp(6) with time zone,
    description text,
    show_price boolean DEFAULT true NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(16) DEFAULT 'DRAFT'::character varying NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    CONSTRAINT chk_pct_status CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('ARCHIVED'::character varying)::text])))
);


--
-- Name: product_config_template_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_config_template_version (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    template_id uuid NOT NULL,
    version integer NOT NULL,
    label character varying(64),
    status character varying(16) NOT NULL,
    snapshot jsonb NOT NULL,
    change_summary text,
    created_by uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    published_at timestamp(6) with time zone,
    archived_at timestamp(6) with time zone
);


--
-- Name: product_config_value_reference; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_config_value_reference (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    option_value_id uuid NOT NULL,
    ref_type character varying(32) NOT NULL,
    ref_code character varying(80) NOT NULL,
    qty character varying(40),
    unit character varying(20),
    note text,
    metadata jsonb DEFAULT '{}'::jsonb,
    sort_order integer DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    CONSTRAINT chk_pcvr_ref_type CHECK (((ref_type)::text = ANY (ARRAY[('MATERIAL'::character varying)::text, ('PROCESS'::character varying)::text, ('COMPONENT'::character varying)::text, ('COST_ITEM'::character varying)::text, ('GLOBAL_VAR'::character varying)::text])))
);


--
-- Name: product_import_lock; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_import_lock (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_id uuid NOT NULL,
    part_no character varying(64),
    granularity character varying(16) NOT NULL,
    locked_by uuid NOT NULL,
    import_record_id uuid,
    locked_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    last_heartbeat_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    released_at timestamp(6) with time zone,
    released_by uuid,
    release_reason character varying(32),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    CONSTRAINT chk_pil_granularity CHECK (((granularity)::text = ANY (ARRAY[('PART_LEVEL'::character varying)::text, ('CUSTOMER_LEVEL'::character varying)::text]))),
    CONSTRAINT chk_pil_partno_consistency CHECK (((((granularity)::text = 'CUSTOMER_LEVEL'::text) AND (part_no IS NULL)) OR (((granularity)::text = 'PART_LEVEL'::text) AND (part_no IS NOT NULL)))),
    CONSTRAINT chk_pil_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('RELEASED'::character varying)::text, ('EXPIRED'::character varying)::text])))
);


--
-- Name: product_process; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_process (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    product_id uuid NOT NULL,
    process_id uuid NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    is_required boolean DEFAULT false NOT NULL
);


--
-- Name: product_template_binding; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_template_binding (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    product_id uuid NOT NULL,
    process_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    process_ids_hash character varying(64) NOT NULL,
    template_id uuid NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: production_consumable; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.production_consumable (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    material_no character varying(20) NOT NULL,
    material_name character varying(100),
    specification character varying(100),
    dimension character varying(100),
    process_no character varying(20) NOT NULL,
    process_name character varying(50),
    resource_group_no character varying(20) NOT NULL,
    seq_no integer NOT NULL,
    consumable_no character varying(30) NOT NULL,
    consumable_name character varying(100),
    usage_qty numeric(18,6),
    life_qty bigint,
    life_unit character varying(20),
    usage_unit character varying(20),
    consumable_version character varying(20),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    is_current boolean DEFAULT true NOT NULL,
    CONSTRAINT chk_production_consumable_life_unit CHECK (((life_unit IS NULL) OR ((life_unit)::text = ANY (ARRAY[('TIMES'::character varying)::text, ('PCS'::character varying)::text, ('HOURS'::character varying)::text]))))
);


--
-- Name: production_energy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.production_energy (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    material_no character varying(20) NOT NULL,
    material_name character varying(100),
    specification character varying(100),
    dimension character varying(100),
    process_no character varying(20) NOT NULL,
    process_name character varying(50),
    equipment_no character varying(30),
    batch_size numeric(18,6),
    round_step numeric(18,6),
    working_hours numeric(18,6),
    currency character varying(10),
    unit character varying(20),
    conversion_rate numeric(18,6),
    calc_version character varying(20),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    is_current boolean DEFAULT true NOT NULL,
    production_no character varying(32),
    system_type character varying(16) DEFAULT 'PRICING'::character varying,
    price_type character varying(24),
    unit_price numeric(24,12),
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL
);


--
-- Name: quotation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quotation (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    quotation_number character varying(50) NOT NULL,
    customer_id uuid NOT NULL,
    name character varying(500) NOT NULL,
    contact_id uuid,
    contact_name character varying(200),
    contact_phone character varying(50),
    contact_email character varying(200),
    project_name character varying(500),
    opportunity_id character varying(200),
    sales_rep_id uuid NOT NULL,
    quote_type character varying(20) DEFAULT 'STANDARD'::character varying,
    priority character varying(10) DEFAULT 'MEDIUM'::character varying,
    stage character varying(30) DEFAULT 'INITIAL_CONTACT'::character varying,
    expected_close_date date,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    total_amount numeric(26,12) DEFAULT 0,
    expiry_date date,
    payment_terms text,
    delivery_cycle integer,
    original_amount numeric(26,12) DEFAULT 0,
    system_discount_rate numeric(5,2) DEFAULT 100,
    final_discount_rate numeric(5,2) DEFAULT 100,
    discount_adjustment_reason text,
    is_manually_adjusted boolean DEFAULT false,
    source_quotation_id uuid,
    assigned_approver_id uuid,
    snapshot_customer_name character varying(200),
    snapshot_customer_level character varying(20),
    snapshot_customer_region character varying(100),
    snapshot_customer_industry character varying(100),
    snapshot_customer_address text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    remarks text,
    tax_rate numeric(5,2) DEFAULT 0 NOT NULL,
    tax_amount numeric(26,12) DEFAULT 0 NOT NULL,
    customer_template_id uuid,
    import_batch_id uuid,
    referenced_versions jsonb,
    submission_snapshot jsonb,
    costing_card_template_id uuid,
    bound_global_variables_snapshot jsonb DEFAULT '[]'::jsonb NOT NULL,
    product_category_id uuid,
    CONSTRAINT chk_q_priority CHECK (((priority)::text = ANY (ARRAY[('HIGH'::character varying)::text, ('MEDIUM'::character varying)::text, ('LOW'::character varying)::text]))),
    CONSTRAINT chk_q_stage CHECK (((stage)::text = ANY (ARRAY[('INITIAL_CONTACT'::character varying)::text, ('REQUIREMENT_CONFIRMATION'::character varying)::text, ('QUOTING'::character varying)::text, ('NEGOTIATION'::character varying)::text]))),
    CONSTRAINT chk_q_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'APPROVED'::character varying, 'SENT'::character varying, 'ACCEPTED'::character varying, 'REJECTED'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying, 'COSTING_REJECTED'::character varying])::text[]))),
    CONSTRAINT chk_q_type CHECK (((quote_type)::text = ANY (ARRAY[('STANDARD'::character varying)::text, ('DISCOUNT'::character varying)::text, ('BULK'::character varying)::text])))
);


--
-- Name: quotation_approval; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quotation_approval (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    quotation_id uuid NOT NULL,
    approver_id uuid NOT NULL,
    action character varying(20) NOT NULL,
    comment text,
    acted_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_qa_action CHECK (((action)::text = ANY ((ARRAY['APPROVED'::character varying, 'REJECTED'::character varying, 'WITHDRAWN'::character varying, 'COSTING_APPROVED'::character varying, 'COSTING_REJECTED'::character varying])::text[])))
);


--
-- Name: quotation_comparison_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quotation_comparison_config (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    quotation_id uuid NOT NULL,
    bucket character varying(16) NOT NULL,
    columns jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: quotation_component_sql_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quotation_component_sql_snapshot (
    quotation_id uuid NOT NULL,
    sql_view_key character varying(200) NOT NULL,
    sql_template text NOT NULL,
    declared_columns jsonb DEFAULT '[]'::jsonb NOT NULL,
    required_variables text[] DEFAULT '{}'::text[] NOT NULL,
    frozen_at timestamp(6) without time zone DEFAULT now() NOT NULL
);


--
-- Name: quotation_line_component_data; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quotation_line_component_data (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    line_item_id uuid NOT NULL,
    component_id uuid,
    tab_name character varying(200),
    row_data jsonb DEFAULT '[]'::jsonb,
    subtotal numeric(26,12) DEFAULT 0,
    sort_order integer DEFAULT 0,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    snapshot_rows jsonb,
    snapshot_at timestamp with time zone,
    deleted_row_keys jsonb DEFAULT '[]'::jsonb NOT NULL,
    row_version bigint DEFAULT 0 NOT NULL
);


--
-- Name: quotation_line_composite_process; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quotation_line_composite_process (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    line_item_id uuid NOT NULL,
    def_code character varying(50) NOT NULL,
    seq_no integer,
    participating_parts jsonb,
    param_values jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: quotation_line_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quotation_line_item (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    quotation_id uuid NOT NULL,
    product_id uuid,
    template_id uuid,
    product_attribute_values jsonb DEFAULT '{}'::jsonb,
    subtotal numeric(26,12) DEFAULT 0,
    system_discount_rate numeric(5,2) DEFAULT 100,
    final_discount_rate numeric(5,2) DEFAULT 100,
    discount_adjustment_reason text,
    is_manually_adjusted boolean DEFAULT false,
    sort_order integer DEFAULT 0,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    customer_part_no character varying(200),
    excel_view_snapshot jsonb,
    product_name_snapshot character varying(500),
    product_part_no_snapshot character varying(200),
    costing_summary_id uuid,
    part_version_locked integer DEFAULT 2000 NOT NULL,
    annual_volume integer,
    discount_source character varying(32),
    discount_base_amount numeric(26,12),
    discount_rate_applied numeric(8,4),
    line_discount_amount numeric(26,12),
    line_unit_price numeric(26,12),
    line_final_price numeric(26,12),
    line_total_amount numeric(26,12),
    discount_rule_code character varying(64),
    parent_line_item_id uuid,
    composite_type character varying(16) DEFAULT 'SIMPLE'::character varying NOT NULL,
    quote_card_values jsonb,
    quote_excel_values jsonb,
    costing_card_values jsonb,
    costing_excel_values jsonb,
    card_snapshot_at timestamp with time zone,
    quote_values_at timestamp with time zone,
    deleted_tree_nodes jsonb,
    row_version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_quotation_line_item_composite_type CHECK (((composite_type)::text = ANY (ARRAY[('SIMPLE'::character varying)::text, ('COMPOSITE'::character varying)::text, ('PART'::character varying)::text])))
);


--
-- Name: quotation_line_item_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quotation_line_item_snapshot (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    line_item_id uuid NOT NULL,
    product_part_no character varying(100),
    product_category character varying(30),
    product_specification character varying(500),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: quotation_line_process; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quotation_line_process (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    line_item_id uuid NOT NULL,
    process_id uuid,
    process_no character varying(20)
);


--
-- Name: quotation_number_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.quotation_number_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: quotation_price_revision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quotation_price_revision (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    quotation_id uuid NOT NULL,
    revision_no character varying(20) NOT NULL,
    based_version_id uuid,
    sealed boolean DEFAULT false NOT NULL,
    upgraded_material_nos jsonb DEFAULT '[]'::jsonb NOT NULL,
    quote_card_values jsonb,
    costing_card_values jsonb,
    snapshot_rows jsonb,
    quote_total_amount numeric(26,12),
    first_effective_at timestamp with time zone DEFAULT now() NOT NULL,
    last_updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: quotation_view_structure; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quotation_view_structure (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    quotation_id uuid NOT NULL,
    view_kind text NOT NULL,
    structure jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT quotation_view_structure_view_kind_check CHECK ((view_kind = ANY (ARRAY['QUOTE_CARD'::text, 'QUOTE_EXCEL'::text, 'COSTING_CARD'::text, 'COSTING_EXCEL'::text])))
);


--
-- Name: quotation_withdraw_request; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quotation_withdraw_request (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    quotation_id uuid NOT NULL,
    requested_by uuid NOT NULL,
    reason text NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    decided_by uuid,
    decided_at timestamp(6) with time zone,
    decision_note text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: quote_customer_code; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quote_customer_code (
    customer_no character varying(20) NOT NULL,
    code character(4) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: quote_customer_code_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.quote_customer_code_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: quote_material_no_seq; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quote_material_no_seq (
    customer_code character(4) NOT NULL,
    year_month character(4) NOT NULL,
    last_serial integer DEFAULT 0 NOT NULL
);


--
-- Name: region; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.region (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(50) NOT NULL,
    name character varying(100) NOT NULL,
    sort_order integer DEFAULT 0,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_region_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DISABLED'::character varying)::text])))
);


--
-- Name: resource_group; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.resource_group (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    group_no character varying(20) NOT NULL,
    group_name character varying(50) NOT NULL,
    group_type character varying(30),
    seq_no integer,
    process_no character varying(20),
    process_name character varying(50),
    workshop character varying(50),
    equipment_id character varying(50),
    description character varying(200),
    effective_date date,
    expire_date date,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    CONSTRAINT chk_resource_group_type CHECK (((group_type IS NULL) OR ((group_type)::text = ANY (ARRAY[('MACHINE'::character varying)::text, ('PLATING'::character varying)::text, ('ASSEMBLY'::character varying)::text, ('TEST'::character varying)::text]))))
);


--
-- Name: sel_param_type; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sel_param_type (
    code character varying(30) NOT NULL,
    name character varying(50) NOT NULL,
    value_mode character varying(20) NOT NULL,
    data_source_key character varying(50),
    persist_handler_key character varying(50),
    sort_order integer DEFAULT 0 NOT NULL
);


--
-- Name: sel_part_signature; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sel_part_signature (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_no character varying(50) NOT NULL,
    structure_version character varying(10) NOT NULL,
    config_fingerprint character(64) NOT NULL,
    config_signature_text text NOT NULL,
    quote_part_no character varying(32) NOT NULL,
    product_type character varying(16) DEFAULT 'SIMPLE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: sel_template; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sel_template (
    id uuid NOT NULL,
    name character varying(100) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    version integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    product_category_id uuid NOT NULL
);


--
-- Name: sel_template_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sel_template_item (
    id uuid NOT NULL,
    template_id uuid NOT NULL,
    param_type_code character varying(30) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL
);


--
-- Name: sel_template_item_value; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sel_template_item_value (
    id uuid NOT NULL,
    item_id uuid NOT NULL,
    allowed_value_key character varying(100) NOT NULL
);


--
-- Name: system_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_config (
    config_key character varying(128) NOT NULL,
    config_value text NOT NULL,
    default_value text NOT NULL,
    data_type character varying(16) NOT NULL,
    category character varying(32) NOT NULL,
    description text,
    modifiable_by character varying(32) DEFAULT 'SYSTEM_ADMIN'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    CONSTRAINT chk_config_category CHECK (((category)::text = ANY (ARRAY[('validation'::character varying)::text, ('import'::character varying)::text, ('retention'::character varying)::text, ('element_price'::character varying)::text, ('business'::character varying)::text]))),
    CONSTRAINT chk_config_data_type CHECK (((data_type)::text = ANY (ARRAY[('STRING'::character varying)::text, ('NUMBER'::character varying)::text, ('BOOLEAN'::character varying)::text, ('JSON'::character varying)::text]))),
    CONSTRAINT chk_config_key_format CHECK (((config_key)::text ~ '^[a-z0-9_]+\.[a-z0-9_]+$'::text))
);


--
-- Name: template; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.template (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    template_series_id uuid NOT NULL,
    name character varying(200) NOT NULL,
    version character varying(20),
    category character varying(30),
    description text,
    usage_note text,
    product_attributes jsonb DEFAULT '[]'::jsonb,
    subtotal_formula jsonb DEFAULT '[]'::jsonb,
    components_snapshot jsonb,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    created_by uuid,
    published_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    excel_view_config jsonb,
    customer_id uuid,
    category_id uuid,
    template_kind character varying(20) DEFAULT 'QUOTATION'::character varying NOT NULL,
    formulas jsonb DEFAULT '[]'::jsonb NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    referenced_variables jsonb DEFAULT '[]'::jsonb,
    sql_views_snapshot jsonb,
    template_sql_views_snapshot jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT chk_template_category CHECK (((category IS NULL) OR ((category)::text = ANY (ARRAY[('STANDARD_PARTS'::character varying)::text, ('CUSTOM_PARTS'::character varying)::text, ('RAW_MATERIALS'::character varying)::text])))),
    CONSTRAINT chk_template_kind CHECK (((template_kind)::text = ANY (ARRAY[('QUOTATION'::character varying)::text, ('COSTING'::character varying)::text]))),
    CONSTRAINT chk_template_status CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('ARCHIVED'::character varying)::text])))
);


--
-- Name: template_component; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.template_component (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    template_id uuid NOT NULL,
    component_id uuid NOT NULL,
    tab_name character varying(200),
    sort_order integer DEFAULT 0,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    preset_rows jsonb DEFAULT '[]'::jsonb NOT NULL,
    formula_assignments jsonb DEFAULT '{}'::jsonb NOT NULL,
    data_driver_path_override text,
    fields_override jsonb
);


--
-- Name: template_component_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.template_component_snapshot (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    template_id uuid NOT NULL,
    template_component_id uuid NOT NULL,
    component_id uuid NOT NULL,
    sort_order integer NOT NULL,
    tab_name character varying(200),
    preset_rows jsonb DEFAULT '[]'::jsonb NOT NULL,
    formula_assignments jsonb DEFAULT '{}'::jsonb NOT NULL,
    component_name character varying(200),
    component_code character varying(100),
    component_type character varying(20) DEFAULT 'NORMAL'::character varying NOT NULL,
    column_count integer DEFAULT 0 NOT NULL,
    fields jsonb DEFAULT '[]'::jsonb NOT NULL,
    formulas jsonb DEFAULT '[]'::jsonb NOT NULL,
    excel_columns jsonb DEFAULT '[]'::jsonb NOT NULL,
    data_driver_path text,
    tree_config jsonb,
    bom_recursive_expand boolean DEFAULT false NOT NULL,
    tab_type character varying(30),
    part_no_field character varying(100),
    part_name_field character varying(100),
    row_key_fields jsonb,
    sort_field character varying(120),
    element_code_field character varying(100),
    element_price_field character varying(100),
    element_currency_field character varying(100),
    frozen_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: template_global_variable_binding; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.template_global_variable_binding (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    template_id uuid NOT NULL,
    global_variable_code character varying(64) NOT NULL,
    display_order integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: template_sql_view; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.template_sql_view (
    id uuid NOT NULL,
    template_id uuid NOT NULL,
    sql_view_name character varying(80) NOT NULL,
    sql_template text NOT NULL,
    declared_columns jsonb DEFAULT '[]'::jsonb NOT NULL,
    required_variables text[] DEFAULT '{}'::text[] NOT NULL,
    scope character varying(20) DEFAULT 'LOCAL'::character varying NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    description text,
    created_by uuid,
    created_at timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT template_sql_view_scope_check CHECK (((scope)::text = 'LOCAL'::text)),
    CONSTRAINT template_sql_view_status_check CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])))
);


--
-- Name: tooling_cost; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tooling_cost (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    material_no character varying(20) NOT NULL,
    material_name character varying(100),
    specification character varying(100),
    dimension character varying(100),
    process_no character varying(20) NOT NULL,
    process_name character varying(50),
    seq_no integer NOT NULL,
    tooling_no character varying(30) NOT NULL,
    tooling_unit_cost numeric(18,6),
    tool_life bigint,
    cycle_output numeric(18,6),
    tooling_unit_price numeric(18,8) NOT NULL,
    currency character varying(10),
    unit character varying(20),
    is_effective boolean,
    conversion_rate numeric(18,6),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    is_current boolean DEFAULT true NOT NULL,
    production_no character varying(32),
    system_type character varying(16) DEFAULT 'PRICING'::character varying,
    calc_version character varying(32),
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL
);


--
-- Name: unit_price; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.unit_price (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    system_type character varying(10) NOT NULL,
    price_type character varying(40) NOT NULL,
    version_no character varying(20) NOT NULL,
    code character varying(30) NOT NULL,
    name character varying(100),
    specification character varying(100),
    dimension character varying(100),
    finished_material_no character varying(20),
    operation_no character varying(20),
    cost_type character varying(20),
    seq_no integer,
    plating_scheme_no character varying(20),
    pricing_price numeric(18,6),
    cost_ratio numeric(10,4),
    market_ref_price numeric(18,6),
    currency character varying(10),
    unit character varying(20),
    conversion_rate numeric(18,6),
    recovery_discount numeric(10,4),
    life_qty bigint,
    life_unit character varying(20),
    supplier_no character varying(20),
    supplier_name character varying(100),
    customer_no character varying(20),
    customer_name character varying(100),
    data_type character varying(20),
    source_url character varying(500),
    source_name character varying(100),
    fetch_rule character varying(200),
    premium_fee numeric(18,6),
    fetched_price numeric(18,6),
    fetch_time timestamp(6) with time zone,
    effective_date date,
    expire_date date,
    base_value numeric(18,6),
    is_fluctuate_with_material boolean,
    material_increase_ratio numeric(10,4),
    material_fixed_increase numeric(18,6),
    defect_rate numeric(10,4),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    discount_order integer,
    item_seq integer,
    is_current boolean DEFAULT true NOT NULL,
    production_no character varying(32),
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    pending_quotation_id uuid,
    pending_supersedes uuid[],
    CONSTRAINT chk_unit_price_life_unit CHECK (((life_unit IS NULL) OR ((life_unit)::text = ANY (ARRAY[('TIMES'::character varying)::text, ('HOURS'::character varying)::text, ('PCS'::character varying)::text, ('DAYS'::character varying)::text])))),
    CONSTRAINT chk_unit_price_system_type CHECK (((system_type)::text = ANY (ARRAY[('QUOTE'::character varying)::text, ('PRICING'::character varying)::text]))),
    CONSTRAINT chk_unit_price_type CHECK (((price_type)::text = ANY ((ARRAY['ELEMENT'::character varying, 'MATERIAL'::character varying, 'COMPONENT'::character varying, 'PART'::character varying, 'CONSUMABLE'::character varying, 'INCOMING_MATERIAL_PROCESS'::character varying, 'INCOMING_MATERIAL_OTHER'::character varying, 'INCOMING_MATERIAL_REDUCTION'::character varying, 'INCOMING_MATERIAL_RECYCLE'::character varying, 'PROCESS'::character varying, 'FINISHED_MATERIAL_OTHER'::character varying, 'COMPONENT_OTHER'::character varying, 'COMPONENT_REDUCTION'::character varying, 'PLATING'::character varying, 'MATERIAL_PRICE'::character varying, 'PACKAGING'::character varying, 'INCOMING_PROCESS'::character varying, 'INCOMING_OTHER'::character varying, 'SELF_PROCESS'::character varying, 'FINISHED_OTHER'::character varying, 'OUTSOURCE_PROCESS'::character varying])::text[])))
);


--
-- Name: user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."user" (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    username character varying(100) NOT NULL,
    full_name character varying(200) NOT NULL,
    email character varying(200) NOT NULL,
    password_hash character varying(255) NOT NULL,
    role character varying(30) NOT NULL,
    region_id uuid,
    department_id uuid,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    is_first_login boolean DEFAULT true NOT NULL,
    initial_password_expires_at timestamp(6) with time zone,
    failed_login_attempts integer DEFAULT 0 NOT NULL,
    locked_until timestamp(6) with time zone,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_user_role CHECK (((role)::text = ANY (ARRAY[('SALES_REP'::character varying)::text, ('SALES_MANAGER'::character varying)::text, ('PRICING_MANAGER'::character varying)::text, ('SYSTEM_ADMIN'::character varying)::text]))),
    CONSTRAINT chk_user_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])))
);


--
-- Name: v_composite_child_elements; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_composite_child_elements AS
 SELECT ebi.hf_part_no,
    ebi.material_no AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    0 AS child_seq,
    ebi.seq_no,
    ebi.component_no AS element_name,
    ebi.content AS composition_pct,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM ((public.element_bom_item ebi
     LEFT JOIN public.material_master mm ON (((mm.material_no)::text = (ebi.material_no)::text)))
     LEFT JOIN public.customer c ON (((c.code)::text = (ebi.customer_no)::text)))
  WHERE (((ebi.system_type)::text = 'QUOTE'::text) AND (ebi.hf_part_no IS NOT NULL) AND (ebi.is_current = true) AND ((ebi.characteristic)::text = ( SELECT max((ebi2.characteristic)::text) AS max
           FROM public.element_bom_item ebi2
          WHERE (((ebi2.system_type)::text = (ebi.system_type)::text) AND ((ebi2.customer_no)::text = (ebi.customer_no)::text) AND ((ebi2.material_no)::text = (ebi.material_no)::text)))));


--
-- Name: v_composite_child_materials; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_composite_child_materials AS
 SELECT asy.material_no AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, mr.name, asy.component_no) AS child_part_name,
    asy.seq_no AS child_seq,
    mr.id AS recipe_id,
    asy.component_no AS material_code,
    mr.symbol AS chemical_symbol,
    COALESCE(asy.component_usage_type, mm.material_type, mr.name, mm.material_name) AS material_name,
    COALESCE(mm.specification, mr.spec_label, asy.component_usage_type) AS spec_label,
    COALESCE(asy.component_usage_type, mr.recipe_type) AS recipe_type,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM (((public.material_bom_item asy
     LEFT JOIN public.material_master mm ON (((mm.material_no)::text = (asy.component_no)::text)))
     LEFT JOIN public.material_recipe mr ON (((mr.code)::text = (asy.component_no)::text)))
     LEFT JOIN public.customer c ON (((c.code)::text = (asy.customer_no)::text)))
  WHERE (((asy.system_type)::text = 'QUOTE'::text) AND ((asy.characteristic)::text IS DISTINCT FROM 'ASSEMBLY'::text) AND (asy.is_current = true))
UNION ALL
 SELECT mm.material_no AS hf_part_no,
    mm.material_no AS child_hf_part_no,
    COALESCE(mm.material_name, mm.material_no) AS child_part_name,
    0 AS child_seq,
    NULL::uuid AS recipe_id,
    NULL::character varying AS material_code,
    NULL::character varying AS chemical_symbol,
    COALESCE(mm.material_type, mm.material_name) AS material_name,
    mm.specification AS spec_label,
    mm.material_type AS recipe_type,
    NULL::uuid AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM public.material_master mm
  WHERE (NOT (EXISTS ( SELECT 1
           FROM public.material_bom_item asy2
          WHERE (((asy2.system_type)::text = 'QUOTE'::text) AND ((asy2.characteristic)::text IS DISTINCT FROM 'ASSEMBLY'::text) AND (asy2.is_current = true) AND ((asy2.material_no)::text = (mm.material_no)::text)))));


--
-- Name: v_composite_child_processes; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_composite_child_processes AS
 SELECT up.finished_material_no AS hf_part_no,
    up.finished_material_no AS child_hf_part_no,
    COALESCE(mm.material_name, up.finished_material_no) AS child_part_name,
    0 AS child_seq,
    row_number() OVER (PARTITION BY up.finished_material_no, c.id ORDER BY up.operation_no) AS seq_no,
    up.operation_no AS process_code,
    COALESCE(pm.process_name, up.operation_no) AS assembly_process,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM (((( SELECT DISTINCT unit_price.customer_no,
            unit_price.finished_material_no,
            unit_price.operation_no
           FROM public.unit_price
          WHERE (((unit_price.system_type)::text = 'QUOTE'::text) AND (unit_price.is_current = true) AND ((unit_price.cost_type)::text = ANY (ARRAY[('自制加工费'::character varying)::text, ('组装加工费'::character varying)::text, ('来料加工费'::character varying)::text])) AND (unit_price.operation_no IS NOT NULL) AND (unit_price.finished_material_no IS NOT NULL))) up
     LEFT JOIN public.material_master mm ON (((mm.material_no)::text = (up.finished_material_no)::text)))
     LEFT JOIN public.process_master pm ON (((pm.process_no)::text = (up.operation_no)::text)))
     LEFT JOIN public.customer c ON (((c.code)::text = (up.customer_no)::text)))
UNION ALL
 SELECT asy.material_no AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, asy.component_no) AS child_part_name,
    asy.seq_no AS child_seq,
    row_number() OVER (PARTITION BY asy.material_no, c.id, asy.component_no ORDER BY asy.seq_no, asy.operation_no) AS seq_no,
    asy.operation_no AS process_code,
    COALESCE(pm.process_name, asy.operation_no) AS assembly_process,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM (((public.material_bom_item asy
     LEFT JOIN public.material_master mm ON (((mm.material_no)::text = (asy.component_no)::text)))
     LEFT JOIN public.process_master pm ON (((pm.process_no)::text = (asy.operation_no)::text)))
     LEFT JOIN public.customer c ON (((c.code)::text = (asy.customer_no)::text)))
  WHERE (((asy.system_type)::text = 'QUOTE'::text) AND ((asy.characteristic)::text = 'ASSEMBLY'::text) AND (asy.is_current = true) AND (asy.operation_no IS NOT NULL));


--
-- Name: variable_label; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.variable_label (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    variable_path character varying(200) NOT NULL,
    display_name character varying(100) NOT NULL,
    category character varying(50) NOT NULL,
    data_type character varying(20),
    unit character varying(20),
    description text,
    example_value character varying(100),
    source_type character varying(20) DEFAULT 'VIEW_COLUMN'::character varying NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid
);


--
-- Name: cpq_feature_field id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cpq_feature_field ALTER COLUMN id SET DEFAULT nextval('public.cpq_feature_field_id_seq'::regclass);


--
-- Name: cpq_feature_group id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cpq_feature_group ALTER COLUMN id SET DEFAULT nextval('public.cpq_feature_group_id_seq'::regclass);


--
-- Name: cpq_feature_value id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cpq_feature_value ALTER COLUMN id SET DEFAULT nextval('public.cpq_feature_value_id_seq'::regclass);


--
-- Name: annual_discount annual_discount_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.annual_discount
    ADD CONSTRAINT annual_discount_pkey PRIMARY KEY (id);


--
-- Name: approval_rule approval_rule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_rule
    ADD CONSTRAINT approval_rule_pkey PRIMARY KEY (id);


--
-- Name: auxiliary_energy auxiliary_energy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auxiliary_energy
    ADD CONSTRAINT auxiliary_energy_pkey PRIMARY KEY (id);


--
-- Name: basic_data_attribute basic_data_attribute_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.basic_data_attribute
    ADD CONSTRAINT basic_data_attribute_pkey PRIMARY KEY (id);


--
-- Name: basic_data_change_log basic_data_change_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.basic_data_change_log
    ADD CONSTRAINT basic_data_change_log_pkey PRIMARY KEY (id);


--
-- Name: basic_data_config basic_data_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.basic_data_config
    ADD CONSTRAINT basic_data_config_pkey PRIMARY KEY (id);


--
-- Name: bnf_table_meta bnf_table_meta_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bnf_table_meta
    ADD CONSTRAINT bnf_table_meta_pkey PRIMARY KEY (table_name);


--
-- Name: capacity capacity_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.capacity
    ADD CONSTRAINT capacity_pkey PRIMARY KEY (id);


--
-- Name: comparison_column_config comparison_column_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comparison_column_config
    ADD CONSTRAINT comparison_column_config_pkey PRIMARY KEY (id);


--
-- Name: comparison_column_config uq_ccc_customer_series; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comparison_column_config
    ADD CONSTRAINT uq_ccc_customer_series UNIQUE (customer_no, template_series_id);


--
-- Name: comparison_tag comparison_tag_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comparison_tag
    ADD CONSTRAINT comparison_tag_code_key UNIQUE (code);


--
-- Name: comparison_tag comparison_tag_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comparison_tag
    ADD CONSTRAINT comparison_tag_pkey PRIMARY KEY (id);


--
-- Name: component component_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.component
    ADD CONSTRAINT component_code_key UNIQUE (code);


--
-- Name: component_directory component_directory_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.component_directory
    ADD CONSTRAINT component_directory_pkey PRIMARY KEY (id);


--
-- Name: component component_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.component
    ADD CONSTRAINT component_pkey PRIMARY KEY (id);


--
-- Name: component_sql_view component_sql_view_component_id_sql_view_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.component_sql_view
    ADD CONSTRAINT component_sql_view_component_id_sql_view_name_key UNIQUE (component_id, sql_view_name);


--
-- Name: component_sql_view component_sql_view_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.component_sql_view
    ADD CONSTRAINT component_sql_view_pkey PRIMARY KEY (id);


--
-- Name: composite_process_def composite_process_def_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.composite_process_def
    ADD CONSTRAINT composite_process_def_code_key UNIQUE (code);


--
-- Name: composite_process_def composite_process_def_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.composite_process_def
    ADD CONSTRAINT composite_process_def_pkey PRIMARY KEY (id);


--
-- Name: config_category config_category_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.config_category
    ADD CONSTRAINT config_category_pkey PRIMARY KEY (id);


--
-- Name: config_item config_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.config_item
    ADD CONSTRAINT config_item_pkey PRIMARY KEY (id);


--
-- Name: config_template config_template_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.config_template
    ADD CONSTRAINT config_template_pkey PRIMARY KEY (id);


--
-- Name: costing_bom_tree_config costing_bom_tree_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_bom_tree_config
    ADD CONSTRAINT costing_bom_tree_config_pkey PRIMARY KEY (id);


--
-- Name: costing_order costing_order_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_order
    ADD CONSTRAINT costing_order_pkey PRIMARY KEY (id);


--
-- Name: costing_order_version_override costing_order_version_override_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_order_version_override
    ADD CONSTRAINT costing_order_version_override_pkey PRIMARY KEY (id);


--
-- Name: costing_template costing_template_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_template
    ADD CONSTRAINT costing_template_pkey PRIMARY KEY (id);


--
-- Name: cpq_feature_field cpq_feature_field_group_id_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cpq_feature_field
    ADD CONSTRAINT cpq_feature_field_group_id_code_key UNIQUE (group_id, code);


--
-- Name: cpq_feature_field cpq_feature_field_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cpq_feature_field
    ADD CONSTRAINT cpq_feature_field_pkey PRIMARY KEY (id);


--
-- Name: cpq_feature_group cpq_feature_group_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cpq_feature_group
    ADD CONSTRAINT cpq_feature_group_code_key UNIQUE (code);


--
-- Name: cpq_feature_group cpq_feature_group_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cpq_feature_group
    ADD CONSTRAINT cpq_feature_group_pkey PRIMARY KEY (id);


--
-- Name: cpq_feature_value cpq_feature_value_field_id_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cpq_feature_value
    ADD CONSTRAINT cpq_feature_value_field_id_code_key UNIQUE (field_id, code);


--
-- Name: cpq_feature_value cpq_feature_value_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cpq_feature_value
    ADD CONSTRAINT cpq_feature_value_pkey PRIMARY KEY (id);


--
-- Name: customer customer_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer
    ADD CONSTRAINT customer_code_key UNIQUE (code);


--
-- Name: customer_contact customer_contact_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_contact
    ADD CONSTRAINT customer_contact_pkey PRIMARY KEY (id);


--
-- Name: customer_excel_template customer_excel_template_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_excel_template
    ADD CONSTRAINT customer_excel_template_pkey PRIMARY KEY (id);


--
-- Name: customer_lead customer_lead_lead_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_lead
    ADD CONSTRAINT customer_lead_lead_code_key UNIQUE (lead_code);


--
-- Name: customer_lead customer_lead_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_lead
    ADD CONSTRAINT customer_lead_pkey PRIMARY KEY (id);


--
-- Name: customer_material_mapping customer_material_mapping_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_material_mapping
    ADD CONSTRAINT customer_material_mapping_pkey PRIMARY KEY (id);


--
-- Name: customer customer_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer
    ADD CONSTRAINT customer_pkey PRIMARY KEY (id);


--
-- Name: customer_price_adjust_element customer_price_adjust_element_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_price_adjust_element
    ADD CONSTRAINT customer_price_adjust_element_pkey PRIMARY KEY (id);


--
-- Name: customer_price_adjust_element uq_cpae_strategy_element; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_price_adjust_element
    ADD CONSTRAINT uq_cpae_strategy_element UNIQUE (strategy_id, element_code);


--
-- Name: customer_price_adjust_material customer_price_adjust_material_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_price_adjust_material
    ADD CONSTRAINT customer_price_adjust_material_pkey PRIMARY KEY (id);


--
-- Name: customer_price_adjust_material uq_cpam_strategy_material; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_price_adjust_material
    ADD CONSTRAINT uq_cpam_strategy_material UNIQUE (strategy_id, material_no);


--
-- Name: customer_price_adjust_strategy customer_price_adjust_strategy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_price_adjust_strategy
    ADD CONSTRAINT customer_price_adjust_strategy_pkey PRIMARY KEY (id);


--
-- Name: customer_price_adjust_strategy uq_cpas_customer; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_price_adjust_strategy
    ADD CONSTRAINT uq_cpas_customer UNIQUE (customer_no);


--
-- Name: customer_price_adjust_strategy_log customer_price_adjust_strategy_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_price_adjust_strategy_log
    ADD CONSTRAINT customer_price_adjust_strategy_log_pkey PRIMARY KEY (id);


--
-- Name: customer_tax customer_tax_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_tax
    ADD CONSTRAINT customer_tax_pkey PRIMARY KEY (id);


--
-- Name: datasource datasource_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.datasource
    ADD CONSTRAINT datasource_code_key UNIQUE (code);


--
-- Name: datasource_param datasource_param_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.datasource_param
    ADD CONSTRAINT datasource_param_pkey PRIMARY KEY (id);


--
-- Name: datasource datasource_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.datasource
    ADD CONSTRAINT datasource_pkey PRIMARY KEY (id);


--
-- Name: ddl_operation_history ddl_operation_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ddl_operation_history
    ADD CONSTRAINT ddl_operation_history_pkey PRIMARY KEY (id);


--
-- Name: ddl_operation_lock ddl_operation_lock_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ddl_operation_lock
    ADD CONSTRAINT ddl_operation_lock_pkey PRIMARY KEY (lock_key);


--
-- Name: department department_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.department
    ADD CONSTRAINT department_code_key UNIQUE (code);


--
-- Name: department department_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.department
    ADD CONSTRAINT department_pkey PRIMARY KEY (id);


--
-- Name: derived_attribute derived_attribute_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.derived_attribute
    ADD CONSTRAINT derived_attribute_pkey PRIMARY KEY (id);


--
-- Name: electricity_price electricity_price_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.electricity_price
    ADD CONSTRAINT electricity_price_pkey PRIMARY KEY (id);


--
-- Name: element_bom_item element_bom_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_bom_item
    ADD CONSTRAINT element_bom_item_pkey PRIMARY KEY (id);


--
-- Name: element_bom element_bom_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_bom
    ADD CONSTRAINT element_bom_pkey PRIMARY KEY (id);


--
-- Name: element_daily_price_log element_daily_price_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_daily_price_log
    ADD CONSTRAINT element_daily_price_log_pkey PRIMARY KEY (id);


--
-- Name: element_daily_price element_daily_price_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_daily_price
    ADD CONSTRAINT element_daily_price_pkey PRIMARY KEY (id);


--
-- Name: element element_element_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element
    ADD CONSTRAINT element_element_code_key UNIQUE (element_code);


--
-- Name: element element_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element
    ADD CONSTRAINT element_pkey PRIMARY KEY (id);


--
-- Name: element_price_fetch_rule element_price_fetch_rule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price_fetch_rule
    ADD CONSTRAINT element_price_fetch_rule_pkey PRIMARY KEY (id);


--
-- Name: element_price_fetch_rule element_price_fetch_rule_rule_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price_fetch_rule
    ADD CONSTRAINT element_price_fetch_rule_rule_code_key UNIQUE (rule_code);


--
-- Name: element_price element_price_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price
    ADD CONSTRAINT element_price_pkey PRIMARY KEY (id);


--
-- Name: element_price_source element_price_source_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price_source
    ADD CONSTRAINT element_price_source_pkey PRIMARY KEY (id);


--
-- Name: element_price_strategy_log element_price_strategy_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price_strategy_log
    ADD CONSTRAINT element_price_strategy_log_pkey PRIMARY KEY (id);


--
-- Name: element_price_strategy element_price_strategy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price_strategy
    ADD CONSTRAINT element_price_strategy_pkey PRIMARY KEY (id);


--
-- Name: element_price_version element_price_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price_version
    ADD CONSTRAINT element_price_version_pkey PRIMARY KEY (id);


--
-- Name: element_price_version uq_epv_customer_slot; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price_version
    ADD CONSTRAINT uq_epv_customer_slot UNIQUE (customer_no, scheduled_slot);


--
-- Name: element_price_version uq_epv_customer_version; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price_version
    ADD CONSTRAINT uq_epv_customer_version UNIQUE (customer_no, version_no);


--
-- Name: element_price_version_item element_price_version_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price_version_item
    ADD CONSTRAINT element_price_version_item_pkey PRIMARY KEY (id);


--
-- Name: element_price_version_item uq_epvi_version_element; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price_version_item
    ADD CONSTRAINT uq_epvi_version_element UNIQUE (version_id, element_code);


--
-- Name: equipment equipment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.equipment
    ADD CONSTRAINT equipment_pkey PRIMARY KEY (id);


--
-- Name: exchange_rate exchange_rate_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exchange_rate
    ADD CONSTRAINT exchange_rate_pkey PRIMARY KEY (id);


--
-- Name: exchange_rate_v6 exchange_rate_v6_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exchange_rate_v6
    ADD CONSTRAINT exchange_rate_v6_pkey PRIMARY KEY (id);


--
-- Name: fee_config fee_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fee_config
    ADD CONSTRAINT fee_config_pkey PRIMARY KEY (id);


--
-- Name: global_variable_change_log global_variable_change_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.global_variable_change_log
    ADD CONSTRAINT global_variable_change_log_pkey PRIMARY KEY (id);


--
-- Name: global_variable_definition global_variable_definition_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.global_variable_definition
    ADD CONSTRAINT global_variable_definition_pkey PRIMARY KEY (code);


--
-- Name: global_variable_value global_variable_value_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.global_variable_value
    ADD CONSTRAINT global_variable_value_pkey PRIMARY KEY (var_code, key_id);


--
-- Name: import_mapping_template import_mapping_template_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_mapping_template
    ADD CONSTRAINT import_mapping_template_pkey PRIMARY KEY (id);


--
-- Name: import_record import_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_record
    ADD CONSTRAINT import_record_pkey PRIMARY KEY (id);


--
-- Name: import_session_decision import_session_decision_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_session_decision
    ADD CONSTRAINT import_session_decision_pkey PRIMARY KEY (import_session_id, decision_type, decision_key);


--
-- Name: import_session import_session_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_session
    ADD CONSTRAINT import_session_pkey PRIMARY KEY (id);


--
-- Name: industry industry_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.industry
    ADD CONSTRAINT industry_code_key UNIQUE (code);


--
-- Name: industry industry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.industry
    ADD CONSTRAINT industry_pkey PRIMARY KEY (id);


--
-- Name: internal_material internal_material_material_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.internal_material
    ADD CONSTRAINT internal_material_material_no_key UNIQUE (material_no);


--
-- Name: internal_material internal_material_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.internal_material
    ADD CONSTRAINT internal_material_pkey PRIMARY KEY (id);


--
-- Name: labor_rate labor_rate_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.labor_rate
    ADD CONSTRAINT labor_rate_pkey PRIMARY KEY (id);


--
-- Name: material_bom_item material_bom_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_bom_item
    ADD CONSTRAINT material_bom_item_pkey PRIMARY KEY (id);


--
-- Name: material_bom material_bom_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_bom
    ADD CONSTRAINT material_bom_pkey PRIMARY KEY (id);


--
-- Name: material_customer_map material_customer_map_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_customer_map
    ADD CONSTRAINT material_customer_map_pkey PRIMARY KEY (id);


--
-- Name: material_master material_master_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_master
    ADD CONSTRAINT material_master_pkey PRIMARY KEY (id);


--
-- Name: material_price_review material_price_review_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_review
    ADD CONSTRAINT material_price_review_pkey PRIMARY KEY (id);


--
-- Name: material_price_review uq_mpr_version_material; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_review
    ADD CONSTRAINT uq_mpr_version_material UNIQUE (version_id, material_no);


--
-- Name: material_price_review_column material_price_review_column_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_review_column
    ADD CONSTRAINT material_price_review_column_pkey PRIMARY KEY (id);


--
-- Name: material_price_review_column uq_mprc_review_column; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_review_column
    ADD CONSTRAINT uq_mprc_review_column UNIQUE (review_id, column_id);


--
-- Name: material_price_update_job material_price_update_job_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_update_job
    ADD CONSTRAINT material_price_update_job_pkey PRIMARY KEY (id);


--
-- Name: material_price_update_job_item material_price_update_job_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_update_job_item
    ADD CONSTRAINT material_price_update_job_item_pkey PRIMARY KEY (id);


--
-- Name: material_price_version_ref material_price_version_ref_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_version_ref
    ADD CONSTRAINT material_price_version_ref_pkey PRIMARY KEY (id);


--
-- Name: material_recipe material_recipe_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_recipe
    ADD CONSTRAINT material_recipe_code_key UNIQUE (code);


--
-- Name: material_recipe_element material_recipe_element_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_recipe_element
    ADD CONSTRAINT material_recipe_element_pkey PRIMARY KEY (id);


--
-- Name: material_recipe material_recipe_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_recipe
    ADD CONSTRAINT material_recipe_pkey PRIMARY KEY (id);


--
-- Name: material_version_mgmt material_version_mgmt_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_version_mgmt
    ADD CONSTRAINT material_version_mgmt_pkey PRIMARY KEY (id);


--
-- Name: model_config_file model_config_file_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_config_file
    ADD CONSTRAINT model_config_file_pkey PRIMARY KEY (id);


--
-- Name: model_config model_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_config
    ADD CONSTRAINT model_config_pkey PRIMARY KEY (id);


--
-- Name: model_config model_config_subject_type_subject_key_version_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_config
    ADD CONSTRAINT model_config_subject_type_subject_key_version_key UNIQUE (subject_type, subject_key, version);


--
-- Name: notification notification_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT notification_pkey PRIMARY KEY (id);


--
-- Name: operation_log operation_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_log
    ADD CONSTRAINT operation_log_pkey PRIMARY KEY (id);


--
-- Name: packaging_consumable packaging_consumable_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.packaging_consumable
    ADD CONSTRAINT packaging_consumable_pkey PRIMARY KEY (id);


--
-- Name: part_no_sequence part_no_sequence_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.part_no_sequence
    ADD CONSTRAINT part_no_sequence_pkey PRIMARY KEY (prefix);


--
-- Name: password_reset_token password_reset_token_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_token
    ADD CONSTRAINT password_reset_token_pkey PRIMARY KEY (id);


--
-- Name: password_reset_token password_reset_token_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_token
    ADD CONSTRAINT password_reset_token_token_hash_key UNIQUE (token_hash);


--
-- Name: plating_fee plating_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plating_fee
    ADD CONSTRAINT plating_fee_pkey PRIMARY KEY (id);


--
-- Name: plating_scheme plating_scheme_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plating_scheme
    ADD CONSTRAINT plating_scheme_pkey PRIMARY KEY (id);


--
-- Name: price_adjust_settings pk_price_adjust_settings; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.price_adjust_settings
    ADD CONSTRAINT pk_price_adjust_settings PRIMARY KEY (id);


--
-- Name: pricing_rule pricing_rule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pricing_rule
    ADD CONSTRAINT pricing_rule_pkey PRIMARY KEY (id);


--
-- Name: pricing_strategy pricing_strategy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pricing_strategy
    ADD CONSTRAINT pricing_strategy_pkey PRIMARY KEY (id);


--
-- Name: process process_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.process
    ADD CONSTRAINT process_code_key UNIQUE (code);


--
-- Name: process_master process_master_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.process_master
    ADD CONSTRAINT process_master_pkey PRIMARY KEY (id);


--
-- Name: process process_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.process
    ADD CONSTRAINT process_pkey PRIMARY KEY (id);


--
-- Name: product_category product_category_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_category
    ADD CONSTRAINT product_category_code_key UNIQUE (code);


--
-- Name: product_category product_category_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_category
    ADD CONSTRAINT product_category_pkey PRIMARY KEY (id);


--
-- Name: product_config_3d_rule product_config_3d_rule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_3d_rule
    ADD CONSTRAINT product_config_3d_rule_pkey PRIMARY KEY (id);


--
-- Name: product_config_constraint product_config_constraint_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_constraint
    ADD CONSTRAINT product_config_constraint_pkey PRIMARY KEY (id);


--
-- Name: product_config_instance_history product_config_instance_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_instance_history
    ADD CONSTRAINT product_config_instance_history_pkey PRIMARY KEY (id);


--
-- Name: product_config_instance product_config_instance_instance_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_instance
    ADD CONSTRAINT product_config_instance_instance_code_key UNIQUE (instance_code);


--
-- Name: product_config_instance product_config_instance_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_instance
    ADD CONSTRAINT product_config_instance_pkey PRIMARY KEY (id);


--
-- Name: product_config_option product_config_option_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_option
    ADD CONSTRAINT product_config_option_pkey PRIMARY KEY (id);


--
-- Name: product_config_option product_config_option_template_id_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_option
    ADD CONSTRAINT product_config_option_template_id_code_key UNIQUE (template_id, code);


--
-- Name: product_config_option_value product_config_option_value_option_id_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_option_value
    ADD CONSTRAINT product_config_option_value_option_id_code_key UNIQUE (option_id, code);


--
-- Name: product_config_option_value product_config_option_value_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_option_value
    ADD CONSTRAINT product_config_option_value_pkey PRIMARY KEY (id);


--
-- Name: product_config_share_access product_config_share_access_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_share_access
    ADD CONSTRAINT product_config_share_access_pkey PRIMARY KEY (id);


--
-- Name: product_config_share product_config_share_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_share
    ADD CONSTRAINT product_config_share_pkey PRIMARY KEY (id);


--
-- Name: product_config_share product_config_share_share_token_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_share
    ADD CONSTRAINT product_config_share_share_token_key UNIQUE (share_token);


--
-- Name: product_config_template product_config_template_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_template
    ADD CONSTRAINT product_config_template_code_key UNIQUE (code);


--
-- Name: product_config_template product_config_template_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_template
    ADD CONSTRAINT product_config_template_pkey PRIMARY KEY (id);


--
-- Name: product_config_template_version product_config_template_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_template_version
    ADD CONSTRAINT product_config_template_version_pkey PRIMARY KEY (id);


--
-- Name: product_config_template_version product_config_template_version_template_id_version_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_template_version
    ADD CONSTRAINT product_config_template_version_template_id_version_key UNIQUE (template_id, version);


--
-- Name: product_config_value_reference product_config_value_reference_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_value_reference
    ADD CONSTRAINT product_config_value_reference_pkey PRIMARY KEY (id);


--
-- Name: product_import_lock product_import_lock_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_import_lock
    ADD CONSTRAINT product_import_lock_pkey PRIMARY KEY (id);


--
-- Name: product product_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product
    ADD CONSTRAINT product_pkey PRIMARY KEY (id);


--
-- Name: product_process product_process_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_process
    ADD CONSTRAINT product_process_pkey PRIMARY KEY (id);


--
-- Name: product product_sku_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product
    ADD CONSTRAINT product_sku_key UNIQUE (part_no);


--
-- Name: product_template_binding product_template_binding_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_template_binding
    ADD CONSTRAINT product_template_binding_pkey PRIMARY KEY (id);


--
-- Name: production_consumable production_consumable_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_consumable
    ADD CONSTRAINT production_consumable_pkey PRIMARY KEY (id);


--
-- Name: production_energy production_energy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.production_energy
    ADD CONSTRAINT production_energy_pkey PRIMARY KEY (id);


--
-- Name: quotation_approval quotation_approval_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_approval
    ADD CONSTRAINT quotation_approval_pkey PRIMARY KEY (id);


--
-- Name: quotation_comparison_config quotation_comparison_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_comparison_config
    ADD CONSTRAINT quotation_comparison_config_pkey PRIMARY KEY (id);


--
-- Name: quotation_component_sql_snapshot quotation_component_sql_snapshot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_component_sql_snapshot
    ADD CONSTRAINT quotation_component_sql_snapshot_pkey PRIMARY KEY (quotation_id, sql_view_key);


--
-- Name: quotation_line_component_data quotation_line_component_data_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_line_component_data
    ADD CONSTRAINT quotation_line_component_data_pkey PRIMARY KEY (id);


--
-- Name: quotation_line_composite_process quotation_line_composite_process_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_line_composite_process
    ADD CONSTRAINT quotation_line_composite_process_pkey PRIMARY KEY (id);


--
-- Name: quotation_line_item quotation_line_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_line_item
    ADD CONSTRAINT quotation_line_item_pkey PRIMARY KEY (id);


--
-- Name: quotation_line_item_snapshot quotation_line_item_snapshot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_line_item_snapshot
    ADD CONSTRAINT quotation_line_item_snapshot_pkey PRIMARY KEY (id);


--
-- Name: quotation_line_process quotation_line_process_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_line_process
    ADD CONSTRAINT quotation_line_process_pkey PRIMARY KEY (id);


--
-- Name: quotation quotation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation
    ADD CONSTRAINT quotation_pkey PRIMARY KEY (id);


--
-- Name: quotation quotation_quotation_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation
    ADD CONSTRAINT quotation_quotation_number_key UNIQUE (quotation_number);


--
-- Name: quotation_price_revision quotation_price_revision_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_price_revision
    ADD CONSTRAINT quotation_price_revision_pkey PRIMARY KEY (id);


--
-- Name: quotation_price_revision uq_qpr_quotation_based_version; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_price_revision
    ADD CONSTRAINT uq_qpr_quotation_based_version UNIQUE (quotation_id, based_version_id);


--
-- Name: quotation_view_structure quotation_view_structure_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_view_structure
    ADD CONSTRAINT quotation_view_structure_pkey PRIMARY KEY (id);


--
-- Name: quotation_withdraw_request quotation_withdraw_request_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_withdraw_request
    ADD CONSTRAINT quotation_withdraw_request_pkey PRIMARY KEY (id);


--
-- Name: quote_customer_code quote_customer_code_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quote_customer_code
    ADD CONSTRAINT quote_customer_code_code_key UNIQUE (code);


--
-- Name: quote_customer_code quote_customer_code_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quote_customer_code
    ADD CONSTRAINT quote_customer_code_pkey PRIMARY KEY (customer_no);


--
-- Name: quote_material_no_seq quote_material_no_seq_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quote_material_no_seq
    ADD CONSTRAINT quote_material_no_seq_pkey PRIMARY KEY (customer_code, year_month);


--
-- Name: region region_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.region
    ADD CONSTRAINT region_code_key UNIQUE (code);


--
-- Name: region region_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.region
    ADD CONSTRAINT region_pkey PRIMARY KEY (id);


--
-- Name: resource_group resource_group_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resource_group
    ADD CONSTRAINT resource_group_pkey PRIMARY KEY (id);


--
-- Name: sel_param_type sel_param_type_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sel_param_type
    ADD CONSTRAINT sel_param_type_pkey PRIMARY KEY (code);


--
-- Name: sel_part_signature sel_part_signature_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sel_part_signature
    ADD CONSTRAINT sel_part_signature_pkey PRIMARY KEY (id);


--
-- Name: sel_template_item sel_template_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sel_template_item
    ADD CONSTRAINT sel_template_item_pkey PRIMARY KEY (id);


--
-- Name: sel_template_item sel_template_item_template_id_param_type_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sel_template_item
    ADD CONSTRAINT sel_template_item_template_id_param_type_code_key UNIQUE (template_id, param_type_code);


--
-- Name: sel_template_item_value sel_template_item_value_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sel_template_item_value
    ADD CONSTRAINT sel_template_item_value_pkey PRIMARY KEY (id);


--
-- Name: sel_template sel_template_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sel_template
    ADD CONSTRAINT sel_template_pkey PRIMARY KEY (id);


--
-- Name: sel_template sel_template_product_category_uk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sel_template
    ADD CONSTRAINT sel_template_product_category_uk UNIQUE (product_category_id);


--
-- Name: system_config system_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_config
    ADD CONSTRAINT system_config_pkey PRIMARY KEY (config_key);


--
-- Name: template_component template_component_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template_component
    ADD CONSTRAINT template_component_pkey PRIMARY KEY (id);


--
-- Name: template_component_snapshot template_component_snapshot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template_component_snapshot
    ADD CONSTRAINT template_component_snapshot_pkey PRIMARY KEY (id);


--
-- Name: template_global_variable_binding template_global_variable_binding_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template_global_variable_binding
    ADD CONSTRAINT template_global_variable_binding_pkey PRIMARY KEY (id);


--
-- Name: template template_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template
    ADD CONSTRAINT template_pkey PRIMARY KEY (id);


--
-- Name: template_sql_view template_sql_view_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template_sql_view
    ADD CONSTRAINT template_sql_view_pkey PRIMARY KEY (id);


--
-- Name: tooling_cost tooling_cost_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tooling_cost
    ADD CONSTRAINT tooling_cost_pkey PRIMARY KEY (id);


--
-- Name: unit_price unit_price_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.unit_price
    ADD CONSTRAINT unit_price_pkey PRIMARY KEY (id);


--
-- Name: basic_data_attribute uq_bda_config_var; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.basic_data_attribute
    ADD CONSTRAINT uq_bda_config_var UNIQUE (config_id, variable_code);


--
-- Name: product_template_binding uq_binding; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_template_binding
    ADD CONSTRAINT uq_binding UNIQUE (product_id, process_ids_hash, template_id);


--
-- Name: costing_order uq_co_number; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_order
    ADD CONSTRAINT uq_co_number UNIQUE (costing_order_number);


--
-- Name: costing_order_version_override uq_covo; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_order_version_override
    ADD CONSTRAINT uq_covo UNIQUE (costing_order_id, component_id, part_no);


--
-- Name: customer_material_mapping uq_customer_part_no; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_material_mapping
    ADD CONSTRAINT uq_customer_part_no UNIQUE (customer_id, customer_part_no);


--
-- Name: derived_attribute uq_da_host_var; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.derived_attribute
    ADD CONSTRAINT uq_da_host_var UNIQUE (host_sheet_id, variable_code);


--
-- Name: datasource_param uq_ds_param_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.datasource_param
    ADD CONSTRAINT uq_ds_param_code UNIQUE (datasource_id, param_code);


--
-- Name: element uq_element_no; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element
    ADD CONSTRAINT uq_element_no UNIQUE (element_no);


--
-- Name: import_mapping_template uq_excel_template_mapping; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_mapping_template
    ADD CONSTRAINT uq_excel_template_mapping UNIQUE (excel_template_id, template_id);


--
-- Name: product_process uq_product_process; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_process
    ADD CONSTRAINT uq_product_process UNIQUE (product_id, process_id);


--
-- Name: quotation_comparison_config uq_qcc_quotation_bucket; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_comparison_config
    ADD CONSTRAINT uq_qcc_quotation_bucket UNIQUE (quotation_id, bucket);


--
-- Name: quotation_view_structure uq_quotation_view_structure; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_view_structure
    ADD CONSTRAINT uq_quotation_view_structure UNIQUE (quotation_id, view_kind);


--
-- Name: material_recipe_element uq_recipe_element; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_recipe_element
    ADD CONSTRAINT uq_recipe_element UNIQUE (recipe_id, element_code);


--
-- Name: sel_part_signature uq_sel_part_signature; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sel_part_signature
    ADD CONSTRAINT uq_sel_part_signature UNIQUE (customer_no, structure_version, config_fingerprint);


--
-- Name: template_component_snapshot uq_tcs_template_tc; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template_component_snapshot
    ADD CONSTRAINT uq_tcs_template_tc UNIQUE (template_id, template_component_id);


--
-- Name: template_global_variable_binding uq_tgvb_template_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template_global_variable_binding
    ADD CONSTRAINT uq_tgvb_template_code UNIQUE (template_id, global_variable_code);


--
-- Name: template_sql_view uq_tsv_template_view_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template_sql_view
    ADD CONSTRAINT uq_tsv_template_view_name UNIQUE (template_id, sql_view_name);


--
-- Name: user user_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT user_email_key UNIQUE (email);


--
-- Name: user user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT user_pkey PRIMARY KEY (id);


--
-- Name: user user_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT user_username_key UNIQUE (username);


--
-- Name: variable_label variable_label_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.variable_label
    ADD CONSTRAINT variable_label_pkey PRIMARY KEY (id);


--
-- Name: variable_label variable_label_variable_path_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.variable_label
    ADD CONSTRAINT variable_label_variable_path_key UNIQUE (variable_path);


--
-- Name: idx_annual_discount_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_annual_discount_material ON public.annual_discount USING btree (material_no, discount_type);


--
-- Name: idx_auxiliary_energy_process; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_auxiliary_energy_process ON public.auxiliary_energy USING btree (process_no);


--
-- Name: idx_bda_config; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bda_config ON public.basic_data_attribute USING btree (config_id);


--
-- Name: idx_bda_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bda_status ON public.basic_data_attribute USING btree (status);


--
-- Name: idx_bdc_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bdc_parent ON public.basic_data_config USING btree (parent_config_id);


--
-- Name: idx_bdc_target_table; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bdc_target_table ON public.basic_data_config USING btree (target_table) WHERE (target_table IS NOT NULL);


--
-- Name: idx_bdc_template_kind; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bdc_template_kind ON public.basic_data_config USING btree (template_kind);


--
-- Name: idx_bdcl_cust_field; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bdcl_cust_field ON public.basic_data_change_log USING btree (customer_id, hf_part_no, table_name, field_name, changed_at DESC);


--
-- Name: idx_bdcl_import; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bdcl_import ON public.basic_data_change_log USING btree (import_record_id);


--
-- Name: idx_bdcl_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bdcl_source ON public.basic_data_change_log USING btree (change_source, changed_at DESC);


--
-- Name: idx_bdcl_table_rec; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bdcl_table_rec ON public.basic_data_change_log USING btree (table_name, record_id, changed_at DESC);


--
-- Name: idx_bdcl_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bdcl_user ON public.basic_data_change_log USING btree (changed_by, changed_at DESC);


--
-- Name: idx_binding_default; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_binding_default ON public.product_template_binding USING btree (product_id, process_ids_hash) WHERE (is_default = true);


--
-- Name: idx_binding_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_binding_hash ON public.product_template_binding USING btree (product_id, process_ids_hash);


--
-- Name: idx_binding_product; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_binding_product ON public.product_template_binding USING btree (product_id);


--
-- Name: idx_capacity_current; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_capacity_current ON public.capacity USING btree (material_no, process_no, resource_group_no) WHERE (is_current = true);


--
-- Name: idx_capacity_process; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_capacity_process ON public.capacity USING btree (process_no);


--
-- Name: idx_capacity_resource_grp; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_capacity_resource_grp ON public.capacity USING btree (resource_group_no);


--
-- Name: idx_cet_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cet_customer ON public.customer_excel_template USING btree (customer_id);


--
-- Name: idx_cmm_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cmm_customer ON public.customer_material_mapping USING btree (customer_id);


--
-- Name: idx_cmm_part_no; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cmm_part_no ON public.customer_material_mapping USING btree (customer_id, customer_part_no);


--
-- Name: idx_comparison_tag_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_comparison_tag_group ON public.comparison_tag USING btree (group_name);


--
-- Name: idx_comparison_tag_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_comparison_tag_status ON public.comparison_tag USING btree (status);


--
-- Name: idx_component_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_component_code ON public.component USING btree (code);


--
-- Name: idx_component_directory; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_component_directory ON public.component USING btree (directory_id);


--
-- Name: idx_composite_process_def_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_composite_process_def_status ON public.composite_process_def USING btree (status, sort_order);


--
-- Name: idx_config_category_template; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_config_category_template ON public.config_category USING btree (template_id);


--
-- Name: idx_config_item_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_config_item_category ON public.config_item USING btree (category_id);


--
-- Name: idx_config_template_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_config_template_status ON public.config_template USING btree (status);


--
-- Name: idx_contact_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contact_customer ON public.customer_contact USING btree (customer_id);


--
-- Name: idx_costing_order_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_costing_order_quotation ON public.costing_order USING btree (quotation_id);


--
-- Name: idx_costing_order_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_costing_order_status ON public.costing_order USING btree (status);


--
-- Name: idx_costing_template_linked_template; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_costing_template_linked_template ON public.costing_template USING btree (linked_template_id);


--
-- Name: idx_costing_template_series; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_costing_template_series ON public.costing_template USING btree (series_id);


--
-- Name: idx_costing_template_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_costing_template_status ON public.costing_template USING btree (status);


--
-- Name: idx_covo_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_covo_order ON public.costing_order_version_override USING btree (costing_order_id);


--
-- Name: idx_cpasl_strategy_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cpasl_strategy_time ON public.customer_price_adjust_strategy_log USING btree (strategy_id, changed_at DESC);


--
-- Name: idx_cpq_ff_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cpq_ff_group ON public.cpq_feature_field USING btree (group_id);


--
-- Name: idx_cpq_fg_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cpq_fg_category ON public.cpq_feature_group USING btree (category);


--
-- Name: idx_cpq_fg_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cpq_fg_status ON public.cpq_feature_group USING btree (status);


--
-- Name: idx_cpq_fv_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cpq_fv_active ON public.cpq_feature_value USING btree (is_active);


--
-- Name: idx_cpq_fv_field; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cpq_fv_field ON public.cpq_feature_value USING btree (field_id);


--
-- Name: idx_csv_component_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_csv_component_id ON public.component_sql_view USING btree (component_id);


--
-- Name: idx_csv_scope_global; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_csv_scope_global ON public.component_sql_view USING btree (scope) WHERE ((scope)::text = 'GLOBAL'::text);


--
-- Name: idx_customer_lead_phone; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_customer_lead_phone ON public.customer_lead USING btree (contact_phone);


--
-- Name: idx_customer_lead_share; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_customer_lead_share ON public.customer_lead USING btree (share_token) WHERE (share_token IS NOT NULL);


--
-- Name: idx_customer_lead_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_customer_lead_status ON public.customer_lead USING btree (status);


--
-- Name: idx_customer_level; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_customer_level ON public.customer USING btree (level);


--
-- Name: idx_customer_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_customer_name ON public.customer USING btree (name);


--
-- Name: idx_customer_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_customer_status ON public.customer USING btree (status);


--
-- Name: idx_customer_tax_cust; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_customer_tax_cust ON public.customer_tax USING btree (customer_id, effective_date DESC);


--
-- Name: idx_da_host_sheet; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_da_host_sheet ON public.derived_attribute USING btree (host_sheet_id);


--
-- Name: idx_da_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_da_status ON public.derived_attribute USING btree (status);


--
-- Name: idx_ddl_history_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ddl_history_status ON public.ddl_operation_history USING btree (status, created_at DESC);


--
-- Name: idx_ddl_history_table; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ddl_history_table ON public.ddl_operation_history USING btree (table_name, created_at DESC);


--
-- Name: idx_department_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_department_parent ON public.department USING btree (parent_id);


--
-- Name: idx_dsp_datasource; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dsp_datasource ON public.datasource_param USING btree (datasource_id);


--
-- Name: idx_edp_elem_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_edp_elem_date ON public.element_daily_price USING btree (element_name, price_date DESC);


--
-- Name: idx_edp_source_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_edp_source_date ON public.element_daily_price USING btree (source_id, price_date DESC);


--
-- Name: idx_edpl_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_edpl_target ON public.element_daily_price_log USING btree (element_name, COALESCE((source_id)::text, ''::text), price_date, changed_at DESC);


--
-- Name: idx_edpl_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_edpl_time ON public.element_daily_price_log USING btree (changed_at DESC);


--
-- Name: idx_electricity_price_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_electricity_price_lookup ON public.electricity_price USING btree (region, effective_date DESC);


--
-- Name: idx_element_bom_item_comp; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_element_bom_item_comp ON public.element_bom_item USING btree (component_no);


--
-- Name: idx_element_bom_item_hf_part_no; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_element_bom_item_hf_part_no ON public.element_bom_item USING btree (hf_part_no);


--
-- Name: idx_element_bom_item_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_element_bom_item_parent ON public.element_bom_item USING btree (customer_no, material_no, characteristic);


--
-- Name: idx_element_bom_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_element_bom_lookup ON public.element_bom USING btree (customer_no, material_no);


--
-- Name: idx_element_daily_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_element_daily_name ON public.element_daily_price USING btree (element_name, price_date DESC);


--
-- Name: idx_element_price_curr; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_element_price_curr ON public.element_price USING btree (is_current);


--
-- Name: idx_element_price_cust; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_element_price_cust ON public.element_price USING btree (customer_id, element_name, version);


--
-- Name: idx_eps2_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_eps2_customer ON public.element_price_strategy USING btree (customer_no);


--
-- Name: idx_epsl_cust_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_epsl_cust_time ON public.element_price_strategy_log USING btree (customer_no, changed_at DESC);


--
-- Name: idx_epsl_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_epsl_target ON public.element_price_strategy_log USING btree (customer_no, COALESCE(element_code, ''::character varying), changed_at DESC);


--
-- Name: idx_epv_customer_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_epv_customer_created ON public.element_price_version USING btree (customer_no, created_at DESC);


--
-- Name: idx_epvi_version; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_epvi_version ON public.element_price_version_item USING btree (version_id);


--
-- Name: idx_equipment_group_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_equipment_group_status ON public.equipment USING btree (resource_group_no, status);


--
-- Name: idx_exchange_rate_cust; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_exchange_rate_cust ON public.exchange_rate USING btree (customer_id, from_currency, to_currency, effective_date DESC);


--
-- Name: idx_exchange_rate_v6_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_exchange_rate_v6_lookup ON public.exchange_rate_v6 USING btree (base_currency, target_currency, effective_date DESC);


--
-- Name: idx_fee_config_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fee_config_customer ON public.fee_config USING btree (customer_no);


--
-- Name: idx_fee_config_dim_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fee_config_dim_material ON public.fee_config USING btree (material_no, dim_input_material_no) WHERE (dim_input_material_no IS NOT NULL);


--
-- Name: idx_fee_config_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fee_config_lookup ON public.fee_config USING btree (biz_type, system_type, effective_date DESC);


--
-- Name: idx_fee_config_material; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fee_config_material ON public.fee_config USING btree (material_no);


--
-- Name: idx_gvcl_var_code_changed_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_gvcl_var_code_changed_at ON public.global_variable_change_log USING btree (var_code, changed_at DESC);


--
-- Name: idx_gvcl_var_code_key_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_gvcl_var_code_key_id ON public.global_variable_change_log USING btree (var_code, key_id, changed_at DESC);


--
-- Name: idx_gvv_var_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_gvv_var_code ON public.global_variable_value USING btree (var_code);


--
-- Name: idx_im_material_no; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_im_material_no ON public.internal_material USING btree (material_no);


--
-- Name: idx_im_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_im_status ON public.internal_material USING btree (status_code);


--
-- Name: idx_import_record_batch; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_import_record_batch ON public.import_record USING btree (import_batch_id);


--
-- Name: idx_import_record_metadata_gin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_import_record_metadata_gin ON public.import_record USING gin (metadata);


--
-- Name: idx_imt_excel_template; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_imt_excel_template ON public.import_mapping_template USING btree (excel_template_id);


--
-- Name: idx_ir_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ir_customer ON public.import_record USING btree (customer_id);


--
-- Name: idx_ir_imported_by; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ir_imported_by ON public.import_record USING btree (imported_by);


--
-- Name: idx_ir_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ir_quotation ON public.import_record USING btree (quotation_id);


--
-- Name: idx_labor_rate_process; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_labor_rate_process ON public.labor_rate USING btree (process_no, version_no);


--
-- Name: idx_line_item_costing_summary; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_line_item_costing_summary ON public.quotation_line_item USING btree (costing_summary_id);


--
-- Name: idx_material_bom_item_comp; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_bom_item_comp ON public.material_bom_item USING btree (component_no);


--
-- Name: idx_material_bom_item_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_bom_item_parent ON public.material_bom_item USING btree (customer_no, material_no, characteristic);


--
-- Name: idx_material_bom_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_bom_lookup ON public.material_bom USING btree (customer_no, material_no, bom_version);


--
-- Name: idx_material_bom_valid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_bom_valid ON public.material_bom USING btree (material_no, valid_from, valid_to);


--
-- Name: idx_material_customer_map_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_customer_map_customer ON public.material_customer_map USING btree (customer_no);


--
-- Name: idx_material_customer_map_prod; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_customer_map_prod ON public.material_customer_map USING btree (customer_product_no);


--
-- Name: idx_material_master_recipe; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_master_recipe ON public.material_master USING btree (material_recipe_id);


--
-- Name: idx_material_master_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_master_type ON public.material_master USING btree (material_type);


--
-- Name: idx_material_recipe_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_recipe_status ON public.material_recipe USING btree (status, sort_order);


--
-- Name: idx_material_version_mgmt_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_version_mgmt_lookup ON public.material_version_mgmt USING btree (material_no, customer_no, is_effective);


--
-- Name: idx_model_config_file_config; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_model_config_file_config ON public.model_config_file USING btree (model_config_id);


--
-- Name: idx_model_config_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_model_config_lookup ON public.model_config USING btree (subject_type, subject_key, is_current);


--
-- Name: idx_mpr_customer_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mpr_customer_status ON public.material_price_review USING btree (customer_no, status);


--
-- Name: idx_mpr_status_version; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mpr_status_version ON public.material_price_review USING btree (status, version_id);


--
-- Name: idx_mpr_warn_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mpr_warn_code ON public.material_price_review USING btree (warn_code, updated_at DESC) WHERE (warn_code IS NOT NULL);


--
-- Name: idx_mprc_review; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mprc_review ON public.material_price_review_column USING btree (review_id);


--
-- Name: idx_mpuj_customer_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mpuj_customer_time ON public.material_price_update_job USING btree (customer_no, triggered_at DESC);


--
-- Name: idx_mpuji_job_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mpuji_job_status ON public.material_price_update_job_item USING btree (job_id, status);


--
-- Name: idx_mpuji_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mpuji_quotation ON public.material_price_update_job_item USING btree (quotation_id);


--
-- Name: idx_mpuji_warn_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mpuji_warn_code ON public.material_price_update_job_item USING btree (warn_code, updated_at DESC) WHERE (warn_code IS NOT NULL);


--
-- Name: idx_mre_element_no; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mre_element_no ON public.material_recipe_element USING btree (element_no);


--
-- Name: idx_notification_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_created ON public.notification USING btree (created_at);


--
-- Name: idx_notification_recipient_read; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_recipient_read ON public.notification USING btree (recipient_id, is_read);


--
-- Name: idx_oplog_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_oplog_created ON public.operation_log USING btree (created_at);


--
-- Name: idx_oplog_operator; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_oplog_operator ON public.operation_log USING btree (operator_id);


--
-- Name: idx_oplog_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_oplog_type ON public.operation_log USING btree (operation_type);


--
-- Name: idx_packaging_consumable_consumable; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_packaging_consumable_consumable ON public.packaging_consumable USING btree (consumable_no);


--
-- Name: idx_pc3d_value; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pc3d_value ON public.product_config_3d_rule USING btree (option_value_id);


--
-- Name: idx_pcc_template; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pcc_template ON public.product_config_constraint USING btree (template_id);


--
-- Name: idx_pci_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pci_customer ON public.product_config_instance USING btree (customer_id, status) WHERE (customer_id IS NOT NULL);


--
-- Name: idx_pci_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pci_expires ON public.product_config_instance USING btree (expires_at) WHERE (expires_at IS NOT NULL);


--
-- Name: idx_pci_fingerprint; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pci_fingerprint ON public.product_config_instance USING btree (config_fingerprint);


--
-- Name: idx_pci_linked_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pci_linked_quotation ON public.product_config_instance USING btree (linked_quotation_id) WHERE (linked_quotation_id IS NOT NULL);


--
-- Name: idx_pci_share; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pci_share ON public.product_config_instance USING btree (share_token) WHERE (share_token IS NOT NULL);


--
-- Name: idx_pci_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pci_status ON public.product_config_instance USING btree (status);


--
-- Name: idx_pci_template; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pci_template ON public.product_config_instance USING btree (template_id);


--
-- Name: idx_pci_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pci_user ON public.product_config_instance USING btree (user_id, status) WHERE (user_id IS NOT NULL);


--
-- Name: idx_pcih_instance; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pcih_instance ON public.product_config_instance_history USING btree (instance_id);


--
-- Name: idx_pco_src_field; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pco_src_field ON public.product_config_option USING btree (source_feature_field_id) WHERE (source_feature_field_id IS NOT NULL);


--
-- Name: idx_pco_template; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pco_template ON public.product_config_option USING btree (template_id);


--
-- Name: idx_pcov_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pcov_active ON public.product_config_option_value USING btree (is_active);


--
-- Name: idx_pcov_option; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pcov_option ON public.product_config_option_value USING btree (option_id);


--
-- Name: idx_pcov_src_value; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pcov_src_value ON public.product_config_option_value USING btree (source_feature_value_id) WHERE (source_feature_value_id IS NOT NULL);


--
-- Name: idx_pcs_instance; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pcs_instance ON public.product_config_share USING btree (instance_id);


--
-- Name: idx_pcs_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pcs_status ON public.product_config_share USING btree (status);


--
-- Name: idx_pcs_token; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pcs_token ON public.product_config_share USING btree (share_token);


--
-- Name: idx_pcsa_share; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pcsa_share ON public.product_config_share_access USING btree (share_id);


--
-- Name: idx_pct_base_model; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pct_base_model ON public.product_config_template USING btree (base_model_id) WHERE (base_model_id IS NOT NULL);


--
-- Name: idx_pct_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pct_category ON public.product_config_template USING btree (category);


--
-- Name: idx_pct_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pct_status ON public.product_config_template USING btree (status);


--
-- Name: idx_pctv_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pctv_status ON public.product_config_template_version USING btree (status);


--
-- Name: idx_pctv_template; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pctv_template ON public.product_config_template_version USING btree (template_id);


--
-- Name: idx_pcvr_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pcvr_code ON public.product_config_value_reference USING btree (ref_code);


--
-- Name: idx_pcvr_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pcvr_type ON public.product_config_value_reference USING btree (ref_type);


--
-- Name: idx_pcvr_value; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pcvr_value ON public.product_config_value_reference USING btree (option_value_id);


--
-- Name: idx_pil_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pil_expires ON public.product_import_lock USING btree (expires_at, status);


--
-- Name: idx_pil_import_rec; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pil_import_rec ON public.product_import_lock USING btree (import_record_id);


--
-- Name: idx_pil_locked_by; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pil_locked_by ON public.product_import_lock USING btree (locked_by, status);


--
-- Name: idx_plating_fee_curr; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plating_fee_curr ON public.plating_fee USING btree (is_current);


--
-- Name: idx_plating_fee_cust; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plating_fee_cust ON public.plating_fee USING btree (customer_id, hf_part_no, version);


--
-- Name: idx_plating_scheme_element; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plating_scheme_element ON public.plating_scheme USING btree (plating_element);


--
-- Name: idx_plating_scheme_hf_part_no; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plating_scheme_hf_part_no ON public.plating_scheme USING btree (hf_part_no) WHERE (hf_part_no IS NOT NULL);


--
-- Name: idx_pp_product; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pp_product ON public.product_process USING btree (product_id);


--
-- Name: idx_pr_strategy; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pr_strategy ON public.pricing_rule USING btree (strategy_id);


--
-- Name: idx_process_master_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_process_master_category ON public.process_master USING btree (process_category);


--
-- Name: idx_product_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_product_category ON public.product USING btree (category);


--
-- Name: idx_product_category_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_product_category_id ON public.product USING btree (category_id);


--
-- Name: idx_product_category_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_product_category_parent ON public.product_category USING btree (parent_id);


--
-- Name: idx_product_category_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_product_category_status ON public.product_category USING btree (status);


--
-- Name: idx_product_part_no; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_product_part_no ON public.product USING btree (part_no);


--
-- Name: idx_product_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_product_status ON public.product USING btree (status);


--
-- Name: idx_production_consumable_process; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_production_consumable_process ON public.production_consumable USING btree (process_no, consumable_no);


--
-- Name: idx_production_energy_equipment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_production_energy_equipment ON public.production_energy USING btree (equipment_no);


--
-- Name: idx_production_energy_process; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_production_energy_process ON public.production_energy USING btree (process_no);


--
-- Name: idx_prt_user_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_prt_user_expires ON public.password_reset_token USING btree (user_id, expires_at);


--
-- Name: idx_ps_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ps_customer ON public.pricing_strategy USING btree (customer_id);


--
-- Name: idx_q_approver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_q_approver ON public.quotation USING btree (assigned_approver_id);


--
-- Name: idx_q_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_q_customer ON public.quotation USING btree (customer_id);


--
-- Name: idx_q_sales_rep; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_q_sales_rep ON public.quotation USING btree (sales_rep_id);


--
-- Name: idx_q_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_q_status ON public.quotation USING btree (status);


--
-- Name: idx_qa_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qa_quotation ON public.quotation_approval USING btree (quotation_id);


--
-- Name: idx_qcc_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qcc_quotation ON public.quotation_comparison_config USING btree (quotation_id);


--
-- Name: idx_qcss_quotation_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qcss_quotation_id ON public.quotation_component_sql_snapshot USING btree (quotation_id);


--
-- Name: idx_qlcd_line; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qlcd_line ON public.quotation_line_component_data USING btree (line_item_id);


--
-- Name: idx_qlcp_line_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qlcp_line_item ON public.quotation_line_composite_process USING btree (line_item_id);


--
-- Name: idx_qli_discount_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qli_discount_source ON public.quotation_line_item USING btree (discount_source) WHERE (discount_source IS NOT NULL);


--
-- Name: idx_qli_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qli_quotation ON public.quotation_line_item USING btree (quotation_id);


--
-- Name: idx_qpr_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qpr_quotation ON public.quotation_price_revision USING btree (quotation_id, first_effective_at);


--
-- Name: idx_quotation_costing_card_template; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quotation_costing_card_template ON public.quotation USING btree (costing_card_template_id);


--
-- Name: idx_quotation_import_batch; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quotation_import_batch ON public.quotation USING btree (import_batch_id);


--
-- Name: idx_quotation_line_item_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quotation_line_item_parent ON public.quotation_line_item USING btree (parent_line_item_id);


--
-- Name: idx_quotation_referenced_versions; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quotation_referenced_versions ON public.quotation USING gin (referenced_versions);


--
-- Name: idx_quotation_submission_snapshot; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quotation_submission_snapshot ON public.quotation USING gin (submission_snapshot);


--
-- Name: idx_qvs_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qvs_quotation ON public.quotation_view_structure USING btree (quotation_id);


--
-- Name: idx_qwr_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qwr_quotation ON public.quotation_withdraw_request USING btree (quotation_id);


--
-- Name: idx_qwr_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qwr_status ON public.quotation_withdraw_request USING btree (status);


--
-- Name: idx_recipe_element_recipe; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_recipe_element_recipe ON public.material_recipe_element USING btree (recipe_id, sort_order);


--
-- Name: idx_resource_group_process; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_resource_group_process ON public.resource_group USING btree (process_no);


--
-- Name: idx_resource_group_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_resource_group_type ON public.resource_group USING btree (group_type);


--
-- Name: idx_sel_part_signature_quote; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sel_part_signature_quote ON public.sel_part_signature USING btree (quote_part_no);


--
-- Name: idx_sel_tiv_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sel_tiv_item ON public.sel_template_item_value USING btree (item_id);


--
-- Name: idx_sysconf_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sysconf_category ON public.system_config USING btree (category);


--
-- Name: idx_tc_template; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tc_template ON public.template_component USING btree (template_id);


--
-- Name: idx_tcs_component; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tcs_component ON public.template_component_snapshot USING btree (component_id);


--
-- Name: idx_tcs_template; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tcs_template ON public.template_component_snapshot USING btree (template_id);


--
-- Name: idx_tcs_template_driver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tcs_template_driver ON public.template_component_snapshot USING btree (template_id) WHERE ((data_driver_path IS NOT NULL) AND (data_driver_path <> ''::text));


--
-- Name: idx_tcs_template_tabtype; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tcs_template_tabtype ON public.template_component_snapshot USING btree (template_id, tab_type);


--
-- Name: idx_template_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_template_category ON public.template USING btree (category_id);


--
-- Name: idx_template_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_template_customer ON public.template USING btree (customer_id);


--
-- Name: idx_template_kind; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_template_kind ON public.template USING btree (template_kind);


--
-- Name: idx_template_series; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_template_series ON public.template USING btree (template_series_id);


--
-- Name: idx_template_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_template_status ON public.template USING btree (status);


--
-- Name: idx_tgvb_global_variable_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tgvb_global_variable_code ON public.template_global_variable_binding USING btree (global_variable_code);


--
-- Name: idx_tgvb_template_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tgvb_template_order ON public.template_global_variable_binding USING btree (template_id, display_order);


--
-- Name: idx_tooling_cost_process; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tooling_cost_process ON public.tooling_cost USING btree (process_no, tooling_no);


--
-- Name: idx_tsv_template; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tsv_template ON public.template_sql_view USING btree (template_id);


--
-- Name: idx_unit_price_current; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_unit_price_current ON public.unit_price USING btree (finished_material_no, operation_no) WHERE (is_current = true);


--
-- Name: idx_unit_price_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_unit_price_customer ON public.unit_price USING btree (customer_no);


--
-- Name: idx_unit_price_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_unit_price_lookup ON public.unit_price USING btree (price_type, code, currency);


--
-- Name: idx_unit_price_supplier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_unit_price_supplier ON public.unit_price USING btree (supplier_no);


--
-- Name: idx_user_department; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_department ON public."user" USING btree (department_id);


--
-- Name: idx_user_region; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_region ON public."user" USING btree (region_id);


--
-- Name: idx_user_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_role ON public."user" USING btree (role);


--
-- Name: idx_user_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_status ON public."user" USING btree (status);


--
-- Name: idx_variable_label_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_variable_label_category ON public.variable_label USING btree (category, status);


--
-- Name: idx_variable_label_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_variable_label_status ON public.variable_label USING btree (status);


--
-- Name: ix_annual_discount_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_annual_discount_pending ON public.annual_discount USING btree (pending_quotation_id) WHERE (pending_quotation_id IS NOT NULL);


--
-- Name: ix_capacity_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_capacity_pending ON public.capacity USING btree (pending_quotation_id) WHERE (pending_quotation_id IS NOT NULL);


--
-- Name: ix_element_bom_item_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_element_bom_item_pending ON public.element_bom_item USING btree (pending_quotation_id) WHERE (pending_quotation_id IS NOT NULL);


--
-- Name: ix_element_bom_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_element_bom_pending ON public.element_bom USING btree (pending_quotation_id) WHERE (pending_quotation_id IS NOT NULL);


--
-- Name: ix_import_session_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_session_customer ON public.import_session USING btree (customer_id);


--
-- Name: ix_import_session_status_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_session_status_expires ON public.import_session USING btree (status, expires_at);


--
-- Name: ix_material_bom_item_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_material_bom_item_pending ON public.material_bom_item USING btree (pending_quotation_id) WHERE (pending_quotation_id IS NOT NULL);


--
-- Name: ix_material_bom_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_material_bom_pending ON public.material_bom USING btree (pending_quotation_id) WHERE (pending_quotation_id IS NOT NULL);


--
-- Name: ix_material_master_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_material_master_pending ON public.material_master USING btree (pending_quotation_id) WHERE (pending_quotation_id IS NOT NULL);


--
-- Name: ix_mcm_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mcm_pending ON public.material_customer_map USING btree (pending_quotation_id) WHERE (pending_quotation_id IS NOT NULL);


--
-- Name: ix_plating_scheme_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_plating_scheme_pending ON public.plating_scheme USING btree (pending_quotation_id) WHERE (pending_quotation_id IS NOT NULL);


--
-- Name: ix_unit_price_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_unit_price_pending ON public.unit_price USING btree (pending_quotation_id) WHERE (pending_quotation_id IS NOT NULL);


--
-- Name: uq_annual_discount; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_annual_discount ON public.annual_discount USING btree (system_type, discount_type, material_no, COALESCE(customer_no, ''::character varying), COALESCE(target_no, ''::character varying), version_no, COALESCE(discount_order, 0));


--
-- Name: uq_auxiliary_energy; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_auxiliary_energy ON public.auxiliary_energy USING btree (material_no, process_no, COALESCE(calc_version, ''::character varying));


--
-- Name: uq_bdc_sheet_name_kind; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_bdc_sheet_name_kind ON public.basic_data_config USING btree (sheet_name, template_kind) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: uq_bom_tree_config_active_per_usage; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_bom_tree_config_active_per_usage ON public.costing_bom_tree_config USING btree (usage) WHERE is_active;


--
-- Name: uq_capacity; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_capacity ON public.capacity USING btree (system_type, material_no, process_no, resource_group_no, COALESCE(calc_version, ''::character varying));


--
-- Name: uq_co_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_co_active ON public.costing_order USING btree (quotation_id) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying])::text[]));


--
-- Name: uq_config_category_tpl_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_config_category_tpl_code ON public.config_category USING btree (template_id, code);


--
-- Name: uq_config_item_cat_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_config_item_cat_code ON public.config_item USING btree (category_id, code);


--
-- Name: uq_config_template_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_config_template_code ON public.config_template USING btree (code);


--
-- Name: uq_costing_template_default; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_costing_template_default ON public.costing_template USING btree (linked_template_id) WHERE (is_default = true);


--
-- Name: uq_customer_tax_eff; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_customer_tax_eff ON public.customer_tax USING btree (customer_id, effective_date);


--
-- Name: uq_electricity_price; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_electricity_price ON public.electricity_price USING btree (region, COALESCE(voltage_level, ''::character varying), price_type, effective_date, COALESCE(version_no, ''::character varying));


--
-- Name: uq_element_bom_item; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_element_bom_item ON public.element_bom_item USING btree (system_type, customer_no, material_no, COALESCE(material_part_no, ''::character varying), characteristic, COALESCE(seq_no, 0), COALESCE(component_no, ''::character varying), COALESCE(part_no, ''::character varying));


--
-- Name: uq_element_bom_v6; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_element_bom_v6 ON public.element_bom USING btree (system_type, customer_no, material_no, COALESCE(material_part_no, ''::character varying), characteristic);


--
-- Name: uq_element_daily; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_element_daily ON public.element_daily_price USING btree (element_name, COALESCE((source_id)::text, ''::text), price_date);


--
-- Name: uq_element_price_curr; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_element_price_curr ON public.element_price USING btree (customer_id, element_name) WHERE (is_current = true);


--
-- Name: uq_element_price_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_element_price_ver ON public.element_price USING btree (customer_id, element_name, version);


--
-- Name: uq_eps2_cust_elem; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_eps2_cust_elem ON public.element_price_strategy USING btree (customer_no, COALESCE(element_code, ''::character varying));


--
-- Name: uq_eps_name_url; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_eps_name_url ON public.element_price_source USING btree (source_name, COALESCE(source_url, ''::character varying));


--
-- Name: uq_epv_customer_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_epv_customer_pending ON public.element_price_version USING btree (customer_no) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: uq_equipment_no; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_equipment_no ON public.equipment USING btree (equipment_no);


--
-- Name: uq_exchange_rate_full; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_exchange_rate_full ON public.exchange_rate USING btree (COALESCE(customer_id, '00000000-0000-0000-0000-000000000000'::uuid), from_currency, to_currency, effective_date);


--
-- Name: uq_exchange_rate_v6; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_exchange_rate_v6 ON public.exchange_rate_v6 USING btree (version_no, base_currency, target_currency);


--
-- Name: uq_fee_config; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_fee_config ON public.fee_config USING btree (system_type, biz_type, fee_no, COALESCE(material_no, ''::character varying), COALESCE(customer_no, ''::character varying), COALESCE(region, ''::character varying), COALESCE(effective_date, '1900-01-01'::date), COALESCE(pricing_version_no, ''::character varying));


--
-- Name: uq_labor_rate; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_labor_rate ON public.labor_rate USING btree (version_no, process_no, COALESCE(material_no, ''::character varying), COALESCE(labor_grade, ''::character varying));


--
-- Name: uq_material_bom_item; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_material_bom_item ON public.material_bom_item USING btree (system_type, customer_no, material_no, COALESCE(characteristic, ''::character varying), COALESCE(bom_version, ''::character varying), COALESCE(seq_no, 0), COALESCE(component_no, ''::character varying), COALESCE(part_no, ''::character varying));


--
-- Name: uq_material_bom_v6; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_material_bom_v6 ON public.material_bom USING btree (system_type, customer_no, material_no, bom_version, COALESCE(characteristic, ''::character varying));


--
-- Name: uq_material_master_fingerprint; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_material_master_fingerprint ON public.material_master USING btree (config_fingerprint) WHERE (config_fingerprint IS NOT NULL);


--
-- Name: uq_material_master_no; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_material_master_no ON public.material_master USING btree (material_no);


--
-- Name: uq_material_version_mgmt; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_material_version_mgmt ON public.material_version_mgmt USING btree (material_no, COALESCE(customer_no, ''::character varying), seq_no, pricing_version_no);


--
-- Name: uq_mcm_composite; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_mcm_composite ON public.material_customer_map USING btree (system_type, material_no, customer_no, customer_product_no) NULLS NOT DISTINCT;


--
-- Name: uq_mcm_quote_cust_prod; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_mcm_quote_cust_prod ON public.material_customer_map USING btree (system_type, customer_no, customer_product_no) WHERE (((system_type)::text = 'QUOTE'::text) AND (customer_product_no IS NOT NULL));


--
-- Name: uq_mcm_quote_no; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_mcm_quote_no ON public.material_customer_map USING btree (material_no) WHERE ((system_type)::text = 'QUOTE'::text);


--
-- Name: uq_model_config_current; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_model_config_current ON public.model_config USING btree (subject_type, subject_key) WHERE is_current;


--
-- Name: uq_mpvr_customer_material; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_mpvr_customer_material ON public.material_price_version_ref USING btree (customer_no, material_no) INCLUDE (version_id);


--
-- Name: uq_packaging_consumable; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_packaging_consumable ON public.packaging_consumable USING btree (material_no, seq_no, consumable_no);


--
-- Name: uq_pil_active_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_pil_active_customer ON public.product_import_lock USING btree (customer_id) WHERE (((status)::text = 'ACTIVE'::text) AND (part_no IS NULL));


--
-- Name: uq_pil_active_part; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_pil_active_part ON public.product_import_lock USING btree (customer_id, part_no) WHERE (((status)::text = 'ACTIVE'::text) AND (part_no IS NOT NULL));


--
-- Name: uq_plating_fee_current; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_plating_fee_current ON public.plating_fee USING btree (customer_id, hf_part_no, plating_plan_code, plan_version) WHERE (is_current = true);


--
-- Name: uq_plating_scheme; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_plating_scheme ON public.plating_scheme USING btree (system_type, scheme_no, scheme_version, seq_no);


--
-- Name: uq_process_master_no; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_process_master_no ON public.process_master USING btree (process_no);


--
-- Name: uq_production_consumable; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_production_consumable ON public.production_consumable USING btree (material_no, process_no, resource_group_no, seq_no, consumable_no);


--
-- Name: uq_production_energy; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_production_energy ON public.production_energy USING btree (system_type, material_no, process_no, COALESCE(price_type, ''::character varying), COALESCE(equipment_no, ''::character varying), COALESCE(calc_version, ''::character varying));


--
-- Name: uq_qpr_quotation_initial; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_qpr_quotation_initial ON public.quotation_price_revision USING btree (quotation_id) WHERE (based_version_id IS NULL);


--
-- Name: uq_qwr_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_qwr_pending ON public.quotation_withdraw_request USING btree (quotation_id) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: uq_resource_group_no; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_resource_group_no ON public.resource_group USING btree (group_no);


--
-- Name: uq_tooling_cost; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_tooling_cost ON public.tooling_cost USING btree (system_type, material_no, process_no, seq_no, tooling_no, COALESCE(calc_version, ''::character varying));


--
-- Name: uq_unit_price; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_unit_price ON public.unit_price USING btree (system_type, price_type, COALESCE(cost_type, ''::character varying), version_no, code, COALESCE(customer_no, ''::character varying), COALESCE(supplier_no, ''::character varying), COALESCE(finished_material_no, ''::character varying), COALESCE(operation_no, ''::character varying), COALESCE(seq_no, 0), COALESCE(discount_order, 0), COALESCE(item_seq, 0), COALESCE(effective_date, '1900-01-01'::date));


--
-- Name: approval_rule approval_rule_approver_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval_rule
    ADD CONSTRAINT approval_rule_approver_id_fkey FOREIGN KEY (approver_id) REFERENCES public."user"(id);


--
-- Name: basic_data_attribute basic_data_attribute_config_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.basic_data_attribute
    ADD CONSTRAINT basic_data_attribute_config_id_fkey FOREIGN KEY (config_id) REFERENCES public.basic_data_config(id) ON DELETE CASCADE;


--
-- Name: basic_data_config basic_data_config_parent_config_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.basic_data_config
    ADD CONSTRAINT basic_data_config_parent_config_id_fkey FOREIGN KEY (parent_config_id) REFERENCES public.basic_data_config(id);


--
-- Name: component component_directory_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.component
    ADD CONSTRAINT component_directory_id_fkey FOREIGN KEY (directory_id) REFERENCES public.component_directory(id);


--
-- Name: component_directory component_directory_parent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.component_directory
    ADD CONSTRAINT component_directory_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.component_directory(id);


--
-- Name: component_sql_view component_sql_view_component_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.component_sql_view
    ADD CONSTRAINT component_sql_view_component_id_fkey FOREIGN KEY (component_id) REFERENCES public.component(id) ON DELETE CASCADE;


--
-- Name: config_category config_category_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.config_category
    ADD CONSTRAINT config_category_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.config_template(id) ON DELETE CASCADE;


--
-- Name: config_item config_item_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.config_item
    ADD CONSTRAINT config_item_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.config_category(id) ON DELETE CASCADE;


--
-- Name: costing_order costing_order_quotation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_order
    ADD CONSTRAINT costing_order_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES public.quotation(id);


--
-- Name: costing_order_version_override costing_order_version_override_costing_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_order_version_override
    ADD CONSTRAINT costing_order_version_override_costing_order_id_fkey FOREIGN KEY (costing_order_id) REFERENCES public.costing_order(id);


--
-- Name: costing_template costing_template_linked_template_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_template
    ADD CONSTRAINT costing_template_linked_template_fk FOREIGN KEY (linked_template_id) REFERENCES public.template(id) ON DELETE SET NULL;


--
-- Name: cpq_feature_field cpq_feature_field_group_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cpq_feature_field
    ADD CONSTRAINT cpq_feature_field_group_id_fkey FOREIGN KEY (group_id) REFERENCES public.cpq_feature_group(id) ON DELETE CASCADE;


--
-- Name: cpq_feature_value cpq_feature_value_field_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cpq_feature_value
    ADD CONSTRAINT cpq_feature_value_field_id_fkey FOREIGN KEY (field_id) REFERENCES public.cpq_feature_field(id) ON DELETE CASCADE;


--
-- Name: customer_contact customer_contact_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_contact
    ADD CONSTRAINT customer_contact_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: customer_excel_template customer_excel_template_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_excel_template
    ADD CONSTRAINT customer_excel_template_created_by_fkey FOREIGN KEY (created_by) REFERENCES public."user"(id);


--
-- Name: customer_excel_template customer_excel_template_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_excel_template
    ADD CONSTRAINT customer_excel_template_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: customer_material_mapping customer_material_mapping_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_material_mapping
    ADD CONSTRAINT customer_material_mapping_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: customer_material_mapping customer_material_mapping_material_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_material_mapping
    ADD CONSTRAINT customer_material_mapping_material_id_fkey FOREIGN KEY (material_id) REFERENCES public.internal_material(id);


--
-- Name: customer_price_adjust_element customer_price_adjust_element_strategy_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_price_adjust_element
    ADD CONSTRAINT customer_price_adjust_element_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES public.customer_price_adjust_strategy(id) ON DELETE CASCADE;


--
-- Name: customer_price_adjust_material customer_price_adjust_material_strategy_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_price_adjust_material
    ADD CONSTRAINT customer_price_adjust_material_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES public.customer_price_adjust_strategy(id) ON DELETE CASCADE;


--
-- Name: customer_price_adjust_strategy_log customer_price_adjust_strategy_log_strategy_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_price_adjust_strategy_log
    ADD CONSTRAINT customer_price_adjust_strategy_log_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES public.customer_price_adjust_strategy(id) ON DELETE CASCADE;


--
-- Name: customer_tax customer_tax_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_tax
    ADD CONSTRAINT customer_tax_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: datasource datasource_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.datasource
    ADD CONSTRAINT datasource_created_by_fkey FOREIGN KEY (created_by) REFERENCES public."user"(id);


--
-- Name: datasource_param datasource_param_datasource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.datasource_param
    ADD CONSTRAINT datasource_param_datasource_id_fkey FOREIGN KEY (datasource_id) REFERENCES public.datasource(id) ON DELETE CASCADE;


--
-- Name: department department_parent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.department
    ADD CONSTRAINT department_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.department(id);


--
-- Name: derived_attribute derived_attribute_host_sheet_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.derived_attribute
    ADD CONSTRAINT derived_attribute_host_sheet_id_fkey FOREIGN KEY (host_sheet_id) REFERENCES public.basic_data_config(id) ON DELETE CASCADE;


--
-- Name: element_daily_price element_daily_price_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_daily_price
    ADD CONSTRAINT element_daily_price_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.element_price_source(id);


--
-- Name: element_price element_price_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price
    ADD CONSTRAINT element_price_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: element_price element_price_fetch_rule_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price
    ADD CONSTRAINT element_price_fetch_rule_id_fkey FOREIGN KEY (fetch_rule_id) REFERENCES public.element_price_fetch_rule(id);


--
-- Name: element_price element_price_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price
    ADD CONSTRAINT element_price_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.element_price_source(id);


--
-- Name: element_price_strategy element_price_strategy_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price_strategy
    ADD CONSTRAINT element_price_strategy_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.element_price_source(id);


--
-- Name: element_price_version_item element_price_version_item_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price_version_item
    ADD CONSTRAINT element_price_version_item_version_id_fkey FOREIGN KEY (version_id) REFERENCES public.element_price_version(id) ON DELETE CASCADE;


--
-- Name: exchange_rate exchange_rate_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exchange_rate
    ADD CONSTRAINT exchange_rate_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: global_variable_value fk_gvv_var_code; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.global_variable_value
    ADD CONSTRAINT fk_gvv_var_code FOREIGN KEY (var_code) REFERENCES public.global_variable_definition(code) ON DELETE CASCADE;


--
-- Name: material_master fk_material_master_recipe; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_master
    ADD CONSTRAINT fk_material_master_recipe FOREIGN KEY (material_recipe_id) REFERENCES public.material_recipe(id) ON DELETE SET NULL;


--
-- Name: import_mapping_template import_mapping_template_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_mapping_template
    ADD CONSTRAINT import_mapping_template_created_by_fkey FOREIGN KEY (created_by) REFERENCES public."user"(id);


--
-- Name: import_mapping_template import_mapping_template_excel_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_mapping_template
    ADD CONSTRAINT import_mapping_template_excel_template_id_fkey FOREIGN KEY (excel_template_id) REFERENCES public.customer_excel_template(id);


--
-- Name: import_mapping_template import_mapping_template_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_mapping_template
    ADD CONSTRAINT import_mapping_template_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.template(id);


--
-- Name: import_record import_record_costing_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_record
    ADD CONSTRAINT import_record_costing_template_id_fkey FOREIGN KEY (costing_template_id) REFERENCES public.costing_template(id);


--
-- Name: import_record import_record_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_record
    ADD CONSTRAINT import_record_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: import_record import_record_customer_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_record
    ADD CONSTRAINT import_record_customer_template_id_fkey FOREIGN KEY (customer_template_id) REFERENCES public.template(id);


--
-- Name: import_record import_record_excel_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_record
    ADD CONSTRAINT import_record_excel_template_id_fkey FOREIGN KEY (excel_template_id) REFERENCES public.customer_excel_template(id);


--
-- Name: import_record import_record_imported_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_record
    ADD CONSTRAINT import_record_imported_by_fkey FOREIGN KEY (imported_by) REFERENCES public."user"(id);


--
-- Name: import_record import_record_mapping_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_record
    ADD CONSTRAINT import_record_mapping_template_id_fkey FOREIGN KEY (mapping_template_id) REFERENCES public.import_mapping_template(id);


--
-- Name: import_record import_record_quotation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_record
    ADD CONSTRAINT import_record_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES public.quotation(id);


--
-- Name: import_record import_record_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_record
    ADD CONSTRAINT import_record_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.template(id);


--
-- Name: import_session import_session_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_session
    ADD CONSTRAINT import_session_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: import_session_decision import_session_decision_import_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_session_decision
    ADD CONSTRAINT import_session_decision_import_session_id_fkey FOREIGN KEY (import_session_id) REFERENCES public.import_session(id) ON DELETE CASCADE;


--
-- Name: material_price_review material_price_review_previous_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_review
    ADD CONSTRAINT material_price_review_previous_version_id_fkey FOREIGN KEY (previous_version_id) REFERENCES public.element_price_version(id);


--
-- Name: material_price_review material_price_review_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_review
    ADD CONSTRAINT material_price_review_version_id_fkey FOREIGN KEY (version_id) REFERENCES public.element_price_version(id);


--
-- Name: material_price_review_column material_price_review_column_review_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_review_column
    ADD CONSTRAINT material_price_review_column_review_id_fkey FOREIGN KEY (review_id) REFERENCES public.material_price_review(id) ON DELETE CASCADE;


--
-- Name: material_price_update_job material_price_update_job_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_update_job
    ADD CONSTRAINT material_price_update_job_version_id_fkey FOREIGN KEY (version_id) REFERENCES public.element_price_version(id);


--
-- Name: material_price_update_job_item material_price_update_job_item_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_update_job_item
    ADD CONSTRAINT material_price_update_job_item_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.material_price_update_job(id) ON DELETE CASCADE;


--
-- Name: material_price_update_job_item material_price_update_job_item_quotation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_update_job_item
    ADD CONSTRAINT material_price_update_job_item_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES public.quotation(id);


--
-- Name: material_price_version_ref material_price_version_ref_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_version_ref
    ADD CONSTRAINT material_price_version_ref_version_id_fkey FOREIGN KEY (version_id) REFERENCES public.element_price_version(id);


--
-- Name: material_recipe_element material_recipe_element_recipe_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_recipe_element
    ADD CONSTRAINT material_recipe_element_recipe_id_fkey FOREIGN KEY (recipe_id) REFERENCES public.material_recipe(id) ON DELETE CASCADE;


--
-- Name: model_config_file model_config_file_model_config_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_config_file
    ADD CONSTRAINT model_config_file_model_config_id_fkey FOREIGN KEY (model_config_id) REFERENCES public.model_config(id) ON DELETE CASCADE;


--
-- Name: notification notification_recipient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT notification_recipient_id_fkey FOREIGN KEY (recipient_id) REFERENCES public."user"(id);


--
-- Name: operation_log operation_log_operator_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_log
    ADD CONSTRAINT operation_log_operator_id_fkey FOREIGN KEY (operator_id) REFERENCES public."user"(id);


--
-- Name: password_reset_token password_reset_token_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_token
    ADD CONSTRAINT password_reset_token_user_id_fkey FOREIGN KEY (user_id) REFERENCES public."user"(id);


--
-- Name: plating_fee plating_fee_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plating_fee
    ADD CONSTRAINT plating_fee_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: plating_fee plating_fee_hf_part_no_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--



--
-- Name: pricing_rule pricing_rule_strategy_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pricing_rule
    ADD CONSTRAINT pricing_rule_strategy_id_fkey FOREIGN KEY (strategy_id) REFERENCES public.pricing_strategy(id) ON DELETE CASCADE;


--
-- Name: pricing_strategy pricing_strategy_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pricing_strategy
    ADD CONSTRAINT pricing_strategy_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: product product_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product
    ADD CONSTRAINT product_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.product_category(id);


--
-- Name: product_category product_category_parent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_category
    ADD CONSTRAINT product_category_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.product_category(id);


--
-- Name: product_config_3d_rule product_config_3d_rule_option_value_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_3d_rule
    ADD CONSTRAINT product_config_3d_rule_option_value_id_fkey FOREIGN KEY (option_value_id) REFERENCES public.product_config_option_value(id) ON DELETE CASCADE;


--
-- Name: product_config_constraint product_config_constraint_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_constraint
    ADD CONSTRAINT product_config_constraint_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.product_config_template(id) ON DELETE CASCADE;


--
-- Name: product_config_instance_history product_config_instance_history_instance_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_instance_history
    ADD CONSTRAINT product_config_instance_history_instance_id_fkey FOREIGN KEY (instance_id) REFERENCES public.product_config_instance(id) ON DELETE CASCADE;


--
-- Name: product_config_instance product_config_instance_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_instance
    ADD CONSTRAINT product_config_instance_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.product_config_template(id);


--
-- Name: product_config_option product_config_option_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_option
    ADD CONSTRAINT product_config_option_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.product_config_template(id) ON DELETE CASCADE;


--
-- Name: product_config_option_value product_config_option_value_option_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_option_value
    ADD CONSTRAINT product_config_option_value_option_id_fkey FOREIGN KEY (option_id) REFERENCES public.product_config_option(id) ON DELETE CASCADE;


--
-- Name: product_config_share_access product_config_share_access_share_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_share_access
    ADD CONSTRAINT product_config_share_access_share_id_fkey FOREIGN KEY (share_id) REFERENCES public.product_config_share(id) ON DELETE CASCADE;


--
-- Name: product_config_share product_config_share_instance_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_share
    ADD CONSTRAINT product_config_share_instance_id_fkey FOREIGN KEY (instance_id) REFERENCES public.product_config_instance(id) ON DELETE CASCADE;


--
-- Name: product_config_template_version product_config_template_version_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_template_version
    ADD CONSTRAINT product_config_template_version_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.product_config_template(id) ON DELETE CASCADE;


--
-- Name: product_config_value_reference product_config_value_reference_option_value_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_config_value_reference
    ADD CONSTRAINT product_config_value_reference_option_value_id_fkey FOREIGN KEY (option_value_id) REFERENCES public.product_config_option_value(id) ON DELETE CASCADE;


--
-- Name: product_import_lock product_import_lock_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_import_lock
    ADD CONSTRAINT product_import_lock_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: product_process product_process_process_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_process
    ADD CONSTRAINT product_process_process_id_fkey FOREIGN KEY (process_id) REFERENCES public.process(id);


--
-- Name: product_process product_process_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_process
    ADD CONSTRAINT product_process_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.product(id);


--
-- Name: product_template_binding product_template_binding_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_template_binding
    ADD CONSTRAINT product_template_binding_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.product(id);


--
-- Name: product_template_binding product_template_binding_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_template_binding
    ADD CONSTRAINT product_template_binding_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.template(id);


--
-- Name: quotation_approval quotation_approval_approver_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_approval
    ADD CONSTRAINT quotation_approval_approver_id_fkey FOREIGN KEY (approver_id) REFERENCES public."user"(id);


--
-- Name: quotation_approval quotation_approval_quotation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_approval
    ADD CONSTRAINT quotation_approval_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES public.quotation(id);


--
-- Name: quotation_component_sql_snapshot quotation_component_sql_snapshot_quotation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_component_sql_snapshot
    ADD CONSTRAINT quotation_component_sql_snapshot_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES public.quotation(id) ON DELETE CASCADE;


--
-- Name: quotation quotation_costing_card_template_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation
    ADD CONSTRAINT quotation_costing_card_template_fk FOREIGN KEY (costing_card_template_id) REFERENCES public.template(id) ON DELETE SET NULL;


--
-- Name: quotation quotation_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation
    ADD CONSTRAINT quotation_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: quotation quotation_customer_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation
    ADD CONSTRAINT quotation_customer_template_id_fkey FOREIGN KEY (customer_template_id) REFERENCES public.template(id);


--
-- Name: quotation_line_component_data quotation_line_component_data_line_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_line_component_data
    ADD CONSTRAINT quotation_line_component_data_line_item_id_fkey FOREIGN KEY (line_item_id) REFERENCES public.quotation_line_item(id) ON DELETE CASCADE;


--
-- Name: quotation_line_composite_process quotation_line_composite_process_line_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_line_composite_process
    ADD CONSTRAINT quotation_line_composite_process_line_item_id_fkey FOREIGN KEY (line_item_id) REFERENCES public.quotation_line_item(id) ON DELETE CASCADE;


--
-- Name: quotation_line_item quotation_line_item_costing_summary_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--



--
-- Name: quotation_line_item quotation_line_item_parent_line_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_line_item
    ADD CONSTRAINT quotation_line_item_parent_line_item_id_fkey FOREIGN KEY (parent_line_item_id) REFERENCES public.quotation_line_item(id) ON DELETE CASCADE;


--
-- Name: quotation_line_item quotation_line_item_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_line_item
    ADD CONSTRAINT quotation_line_item_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.product(id);


--
-- Name: quotation_line_item quotation_line_item_quotation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_line_item
    ADD CONSTRAINT quotation_line_item_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES public.quotation(id) ON DELETE CASCADE;


--
-- Name: quotation_line_item_snapshot quotation_line_item_snapshot_line_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_line_item_snapshot
    ADD CONSTRAINT quotation_line_item_snapshot_line_item_id_fkey FOREIGN KEY (line_item_id) REFERENCES public.quotation_line_item(id) ON DELETE CASCADE;


--
-- Name: quotation_line_item quotation_line_item_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_line_item
    ADD CONSTRAINT quotation_line_item_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.template(id);


--
-- Name: quotation_line_process quotation_line_process_line_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_line_process
    ADD CONSTRAINT quotation_line_process_line_item_id_fkey FOREIGN KEY (line_item_id) REFERENCES public.quotation_line_item(id) ON DELETE CASCADE;


--
-- Name: quotation_line_process quotation_line_process_process_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_line_process
    ADD CONSTRAINT quotation_line_process_process_id_fkey FOREIGN KEY (process_id) REFERENCES public.process(id);


--
-- Name: quotation_line_process quotation_line_process_process_no_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_line_process
    ADD CONSTRAINT quotation_line_process_process_no_fkey FOREIGN KEY (process_no) REFERENCES public.process_master(process_no);


--
-- Name: quotation quotation_sales_rep_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation
    ADD CONSTRAINT quotation_sales_rep_id_fkey FOREIGN KEY (sales_rep_id) REFERENCES public."user"(id);


--
-- Name: quotation_price_revision quotation_price_revision_based_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_price_revision
    ADD CONSTRAINT quotation_price_revision_based_version_id_fkey FOREIGN KEY (based_version_id) REFERENCES public.element_price_version(id);


--
-- Name: quotation_price_revision quotation_price_revision_quotation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_price_revision
    ADD CONSTRAINT quotation_price_revision_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES public.quotation(id) ON DELETE CASCADE;


--
-- Name: quotation_view_structure quotation_view_structure_quotation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_view_structure
    ADD CONSTRAINT quotation_view_structure_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES public.quotation(id) ON DELETE CASCADE;


--
-- Name: quotation_withdraw_request quotation_withdraw_request_quotation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_withdraw_request
    ADD CONSTRAINT quotation_withdraw_request_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES public.quotation(id);


--
-- Name: sel_template_item sel_template_item_param_type_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sel_template_item
    ADD CONSTRAINT sel_template_item_param_type_code_fkey FOREIGN KEY (param_type_code) REFERENCES public.sel_param_type(code);


--
-- Name: sel_template_item sel_template_item_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sel_template_item
    ADD CONSTRAINT sel_template_item_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.sel_template(id) ON DELETE CASCADE;


--
-- Name: sel_template_item_value sel_template_item_value_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sel_template_item_value
    ADD CONSTRAINT sel_template_item_value_item_id_fkey FOREIGN KEY (item_id) REFERENCES public.sel_template_item(id) ON DELETE CASCADE;


--
-- Name: template template_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template
    ADD CONSTRAINT template_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.product_category(id);


--
-- Name: template_component template_component_component_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template_component
    ADD CONSTRAINT template_component_component_id_fkey FOREIGN KEY (component_id) REFERENCES public.component(id);


--
-- Name: template_component template_component_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template_component
    ADD CONSTRAINT template_component_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.template(id) ON DELETE CASCADE;


--
-- Name: template_component_snapshot template_component_snapshot_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template_component_snapshot
    ADD CONSTRAINT template_component_snapshot_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.template(id) ON DELETE CASCADE;


--
-- Name: template template_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template
    ADD CONSTRAINT template_created_by_fkey FOREIGN KEY (created_by) REFERENCES public."user"(id);


--
-- Name: template template_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template
    ADD CONSTRAINT template_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: template_global_variable_binding template_global_variable_binding_global_variable_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template_global_variable_binding
    ADD CONSTRAINT template_global_variable_binding_global_variable_code_fkey FOREIGN KEY (global_variable_code) REFERENCES public.global_variable_definition(code) ON DELETE RESTRICT;


--
-- Name: template_global_variable_binding template_global_variable_binding_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template_global_variable_binding
    ADD CONSTRAINT template_global_variable_binding_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.template(id) ON DELETE CASCADE;


--
-- Name: template_sql_view template_sql_view_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template_sql_view
    ADD CONSTRAINT template_sql_view_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.template(id) ON DELETE CASCADE;


--
-- Name: user user_department_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT user_department_id_fkey FOREIGN KEY (department_id) REFERENCES public.department(id);


--
-- Name: user user_region_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT user_region_id_fkey FOREIGN KEY (region_id) REFERENCES public.region(id);


--
-- PostgreSQL database dump complete
--


-- ============================================================
-- 函数(纯 ASCII,放文件末尾隔离解析风险)
-- ============================================================

CREATE FUNCTION public.current_part_version(p_customer_product_no text, p_hf_part_no text) RETURNS integer
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE
    v_ver INT;
BEGIN
    IF p_customer_product_no IS NULL OR p_hf_part_no IS NULL THEN
        RETURN 2000;
    END IF;

    SELECT current_version INTO v_ver
    FROM mat_customer_part_mapping
    WHERE customer_product_no = p_customer_product_no
      AND hf_part_no = p_hf_part_no
    LIMIT 1;

    RETURN COALESCE(v_ver, 2000);
END;
$$;

CREATE FUNCTION public.f_customer_element_price(p_customer_no text, p_base_date date) RETURNS TABLE(element_code character varying, unit_price numeric, currency character varying, price_unit character varying)
    LANGUAGE sql STABLE
    AS $$
WITH def AS (
    SELECT * FROM element_price_strategy
     WHERE customer_no = p_customer_no AND element_code IS NULL AND status = 'ACTIVE'
     LIMIT 1
),
eff AS (
    SELECT e.element_code,
           CASE WHEN x.id IS NOT NULL THEN x.source_id   ELSE d.source_id   END AS source_id,
           CASE WHEN x.id IS NOT NULL THEN x.method      ELSE d.method      END AS method,
           CASE WHEN x.id IS NOT NULL THEN x.window_num  ELSE d.window_num  END AS window_num,
           CASE WHEN x.id IS NOT NULL THEN x.window_unit ELSE d.window_unit END AS window_unit,
           CASE WHEN x.id IS NOT NULL THEN x.factor      ELSE d.factor      END AS factor,
           CASE WHEN x.id IS NOT NULL THEN x.premium     ELSE d.premium     END AS premium
      FROM element e
      LEFT JOIN element_price_strategy x
             ON x.customer_no  = p_customer_no
            AND x.element_code = e.element_code
            AND x.status = 'ACTIVE'
      LEFT JOIN def d ON TRUE
     WHERE e.status = 'ACTIVE'
       AND (x.id IS NOT NULL OR d.id IS NOT NULL)
),
win AS (
    SELECT eff.*,
           CASE WHEN eff.method = 'LATEST' THEN NULL
                ELSE (p_base_date - (eff.window_num || ' ' ||
                       CASE eff.window_unit
                            WHEN 'DAY'   THEN 'day'   WHEN 'WEEK' THEN 'week'
                            WHEN 'MONTH' THEN 'month' ELSE 'year' END)::interval)::date
           END AS win_from
      FROM eff
)
SELECT w.element_code,
       ROUND(agg.raw_value * w.factor + w.premium, 4) AS unit_price,
       agg.currency,
       agg.price_unit
  FROM win w
  CROSS JOIN LATERAL (
      SELECT
        CASE w.method
          WHEN 'LATEST' THEN (
              SELECT dp.raw_price FROM element_daily_price dp
               WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
                 AND dp.raw_price IS NOT NULL AND dp.price_date <= p_base_date
               ORDER BY dp.price_date DESC LIMIT 1)
          WHEN 'AVG' THEN (
              SELECT AVG(dp.raw_price) FROM element_daily_price dp
               WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
                 AND dp.raw_price IS NOT NULL
                 AND dp.price_date BETWEEN w.win_from AND p_base_date)
          WHEN 'MAX' THEN (
              SELECT MAX(dp.raw_price) FROM element_daily_price dp
               WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
                 AND dp.raw_price IS NOT NULL
                 AND dp.price_date BETWEEN w.win_from AND p_base_date)
          ELSE (
              SELECT MIN(dp.raw_price) FROM element_daily_price dp
               WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
                 AND dp.raw_price IS NOT NULL
                 AND dp.price_date BETWEEN w.win_from AND p_base_date)
        END AS raw_value,
        (SELECT dp.currency   FROM element_daily_price dp
          WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
            AND dp.price_date <= p_base_date
          ORDER BY dp.price_date DESC LIMIT 1) AS currency,
        (SELECT dp.price_unit FROM element_daily_price dp
          WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
            AND dp.price_date <= p_base_date
          ORDER BY dp.price_date DESC LIMIT 1) AS price_unit
  ) agg
 WHERE agg.raw_value IS NOT NULL;
$$;

CREATE FUNCTION public.f_material_element_price(p_customer_no text, p_base_date date) RETURNS TABLE(material_no character varying, element_code character varying, unit_price numeric, currency character varying, price_unit character varying)
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

CREATE FUNCTION public.get_bom_components(p_material_no text) RETURNS TABLE(js integer, hf_part_no text, material_no text, component_no text)
    LANGUAGE sql STABLE
    AS $$
 WITH RECURSIVE bom_tree AS (
        SELECT 1 js,material_no dj,mbi.*
        FROM material_bom_item mbi
        WHERE mbi.material_no = p_material_no
          AND mbi.customer_no = '_GLOBAL_'

        UNION ALL

        SELECT js+1 js,dj,child.*
        FROM material_bom_item child
        INNER JOIN bom_tree parent
            ON  child.material_no  = parent.component_no
           AND child.customer_no  = parent.customer_no
    )
    SELECT 
		 js, dj hf_part_no ,material_no, component_no
    FROM bom_tree;
$$;

CREATE FUNCTION public.get_bom_components(p_material_no text, p_customer_no text) RETURNS TABLE(component_no text)
    LANGUAGE sql STABLE
    AS $$
    WITH RECURSIVE bom_tree AS (
        SELECT mbi.*
        FROM material_bom_item mbi
        WHERE mbi.material_no = p_material_no
          AND mbi.customer_no = p_customer_no

        UNION ALL

        SELECT child.*
        FROM material_bom_item child
        INNER JOIN bom_tree parent
            ON child.component_no = parent.material_no
           AND child.customer_no  = parent.customer_no
    )
    SELECT distinct bom_tree.component_no
    FROM bom_tree;
$$;


-- ============================================================
-- 唯一保留用户: admin (密码 Admin@2026)
-- ============================================================
INSERT INTO public."user" VALUES ('d1e1147c-a639-4156-aeac-9f938a65ad05', 'admin', '系统管理员', 'admin@cpq-system.com', '$2a$12$EtcUJKvqc0YASZzLfC2I8elvqLrXYh10FlcxUBG1TqF3i6AxnqrKm', 'SYSTEM_ADMIN', NULL, NULL, 'ACTIVE', false, NULL, 0, NULL, '2026-04-14 03:00:23.7324+00', '2026-07-22 01:31:19.552186+00');


-- ============================================================
-- BOM 树递归 SQL 配置 (双侧各 1 条 active) —— 与 Flyway V363 同源
-- ------------------------------------------------------------
-- 该表全库原无 INSERT 迁移种子, 配置只活在 DB 里, 是 task-0725 根因③(环境重建即丢)。
-- 此处随建库脚本带上, 使「导完脚本尚未连 Quarkus」的库也已可渲染 BOM 树。
-- 幂等: WHERE NOT EXISTS 守卫; 与 V363 重复应用互不冲突(基线停在 362, V363 仍会跑并自愈)。
-- 口径: COSTING = system_type PRICING + customer_no _GLOBAL_ + versionFilter 宏(版本感知,
--       走 API 保存会被 dry-run 校验打回, 只能经迁移/直连改);
--       QUOTE = system_type QUOTE + is_current + 客户口径自根传播。
-- 注: 此处用标准单引号转义而非美元引号, 规避 Navicat 解析中断(与文件末尾函数体同一考量)。
-- ============================================================
INSERT INTO public.costing_bom_tree_config (name, "usage", is_active, sql_template)
SELECT '核价BOM树-PRICING口径v1(versionFilter 版本感知)', 'COSTING', true, 'WITH RECURSIVE bom AS (
  SELECT
    p::text                                        AS root_no,
    p::text                                        AS material_no,
    (SELECT bv.bom_version::text
       FROM material_bom_item bv
      WHERE bv.material_no = p
        AND bv.customer_no = ''_GLOBAL_''
        AND bv.system_type = ''PRICING''
        AND :versionFilter(bv.is_current, bv.bom_version, bv.material_no)
      LIMIT 1)                                     AS bom_version,
    NULL::text                                     AS parent_no,
    p::text                                        AS node_path
  FROM unnest(:production_part_nos) AS p

  UNION ALL

  SELECT
    b.root_no,
    ch.component_no::text                          AS material_no,
    (SELECT bv.bom_version::text
       FROM material_bom_item bv
      WHERE bv.material_no = ch.component_no
        AND bv.customer_no = ''_GLOBAL_''
        AND bv.system_type = ''PRICING''
        AND :versionFilter(bv.is_current, bv.bom_version, bv.material_no)
      LIMIT 1)                                     AS bom_version,
    ch.material_no::text                           AS parent_no,
    (b.node_path || ''/'' || ch.component_no)::text  AS node_path
  FROM material_bom_item ch
  JOIN bom b ON ch.material_no = b.material_no
  WHERE ch.customer_no  = ''_GLOBAL_''
    AND ch.system_type  = ''PRICING''
    AND :versionFilter(ch.is_current, ch.bom_version, ch.material_no)
    AND ch.component_no IS NOT NULL
) CYCLE material_no SET is_cyc USING cyc_path
SELECT root_no, material_no, bom_version, parent_no, node_path
FROM bom'
WHERE NOT EXISTS (
  SELECT 1 FROM public.costing_bom_tree_config WHERE "usage" = 'COSTING' AND is_active
);

INSERT INTO public.costing_bom_tree_config (name, "usage", is_active, sql_template)
SELECT '报价BOM树-QUOTE口径v1', 'QUOTE', true, 'WITH RECURSIVE bom AS (
  SELECT p::text AS root_no, p::text AS material_no,
    (SELECT bv.bom_version::text FROM material_bom_item bv WHERE bv.material_no=p AND bv.system_type=''QUOTE'' AND bv.is_current LIMIT 1) AS bom_version,
    NULL::text AS parent_no, p::text AS node_path,
    (SELECT bc.customer_no FROM material_bom_item bc WHERE bc.material_no=p AND bc.system_type=''QUOTE'' AND bc.is_current ORDER BY bc.customer_no LIMIT 1) AS _cust
  FROM unnest(:production_part_nos) AS p
  UNION ALL
  SELECT b.root_no, ch.component_no::text,
    (SELECT bv.bom_version::text FROM material_bom_item bv WHERE bv.material_no=ch.component_no AND bv.system_type=''QUOTE'' AND bv.is_current LIMIT 1),
    ch.material_no::text, (b.node_path||''/''||ch.component_no)::text, b._cust
  FROM material_bom_item ch JOIN bom b ON ch.material_no=b.material_no AND ch.customer_no=b._cust
  WHERE ch.system_type=''QUOTE'' AND ch.is_current AND ch.component_no IS NOT NULL
) CYCLE material_no SET is_cyc USING cyc_path
SELECT root_no, material_no, bom_version, parent_no, node_path FROM bom'
WHERE NOT EXISTS (
  SELECT 1 FROM public.costing_bom_tree_config WHERE "usage" = 'QUOTE' AND is_active
);

-- ============================================================
-- 调价系统参数 (单行, id=1) —— 与 Flyway V378 同源
-- ------------------------------------------------------------
-- price_adjust_settings 有 CHECK(id=1) 单例约束, 服务层无条件 findById(1);
-- 缺此行则 L3 升版口径守卫取不到阈值。0.01 与后端
-- MaterialVersionUpgradeService.DEFAULT_SUBTOTAL_GUARD_THRESHOLD 同值。
-- 幂等: ON CONFLICT DO NOTHING, 与 V378 重复应用互不冲突。
-- 注(V383): subtotal_guard_enabled 不在 INSERT 列清单里, 走列默认值 false ——
--           守卫默认关闭(升版跳过 S0 旧价重算, 性能优先), 需要时由系统管理员在界面打开。
-- ============================================================
INSERT INTO public.price_adjust_settings (id, subtotal_guard_threshold)
VALUES (1, 0.01)
ON CONFLICT (id) DO NOTHING;


-- ============================================================
-- Flyway 基线记录 (V385)
-- 新库带此 baseline: 连 Quarkus 时 flyway 跳过 V1~V385 历史重放, 只跑 V386+ 新迁移
-- ⚠️ 本脚本的表结构已含 V385, 基线号必须 >= 385。
--    与 V363~V365 那批"幂等可重放、基线仍停 362"的处理**不同**: V368 含 CREATE TABLE
--    (无 IF NOT EXISTS)、V377 含 RENAME COLUMN、V382 含 CREATE TABLE(无 IF NOT EXISTS)、
--    V384 含 DROP CONSTRAINT(无 IF EXISTS), 均非幂等 —— 基线若低于 384, Quarkus 启动
--    会重放它们并因"表已存在 / 列 biz_type 不存在 / 约束不存在"而失败。
-- ============================================================
CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp(6) without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL,
    CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank)
);
CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);
INSERT INTO public.flyway_schema_history
  (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)
VALUES (1, '385', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', NULL, 'baseline', now(), 0, true);


-- ============================================================
-- 导入后自检(逐条核对期望值)
-- ============================================================
-- SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';  -- 期望 144
-- SELECT count(*) FROM information_schema.views  WHERE table_schema='public';                              -- 期望 3
-- SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public';     -- 期望 5
-- SELECT version FROM flyway_schema_history;                                                               -- 期望 385
-- SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND numeric_precision=26
--   AND numeric_scale=12 AND ((table_name='quotation' AND column_name IN ('total_amount','original_amount','tax_amount'))
--    OR (table_name='quotation_line_item' AND column_name IN ('subtotal','discount_base_amount','line_unit_price','line_final_price','line_discount_amount','line_total_amount'))
--    OR (table_name='quotation_line_component_data' AND column_name='subtotal')
--    OR (table_name='costing_order' AND column_name IN ('total_amount','costing_total_amount'))
--    OR (table_name='material_price_review' AND column_name='warn_diff')
--    OR (table_name='material_price_review_column' AND column_name IN ('quote_current','quote_adjusted','costing_current','costing_adjusted','diff_current','diff_adjusted'))
--    OR (table_name='quotation_price_revision' AND column_name='quote_total_amount')
--    OR (table_name='material_price_update_job_item' AND column_name='diff_value'));                        -- 期望 21
-- SELECT id, subtotal_guard_threshold, subtotal_guard_enabled FROM price_adjust_settings;                  -- 期望 1 行, 1 / 0.010000 / f
-- SELECT count(*) FROM template_component_snapshot;                                                        -- 期望 0(空表, 首次 publish 模板时才写入)
-- SELECT username, role FROM "user";                                                                       -- 期望仅 admin / SYSTEM_ADMIN
-- SELECT count(*) FROM "user";                                                                             -- 期望 1
-- SELECT "usage", is_active, length(sql_template) FROM costing_bom_tree_config ORDER BY "usage";           -- 期望 COSTING/QUOTE 各 1 行 t，长度 1586/1063
-- SELECT md5(sql_template) FROM costing_bom_tree_config WHERE "usage"='COSTING' AND is_active;             -- 期望 784e51ed0f9584261d97b388924b2f2c
-- SELECT md5(sql_template) FROM costing_bom_tree_config WHERE "usage"='QUOTE'   AND is_active;             -- 期望 0b8e458ad3a4ce544c78020e03ba850b
