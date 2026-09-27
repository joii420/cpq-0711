-- ============================================================
-- CPQ 全空项目数据库初始化脚本 (Navicat 可直接执行)
-- ------------------------------------------------------------
-- 生成: 2026-09-09   源库: 10.177.152.12:5432/cpq_db_0724 (PostgreSQL 16.13)
-- 方式: pg_dump --schema-only 整体重生成 + 逐条重新应用 Navicat 兼容规则
-- 取代: deploy/cpq-init-empty-navicat.sql (旧脚本原地保留, 仅作历史追溯)
--
-- 内容:
--   * 251 张表 / 29 个视图 / 106 个序列 / 6 个函数
--   * Flyway 基线 = 439
--   * 种子数据 703 行: admin 用户 1 + Flyway 基线 1 + 系统配置 701
--     (price_adjust_settings / sel_param_type / costing_bom_tree_config / semantic_* 语义图)
--     —— 均为 Flyway 迁移生成的系统配置, 不含任何业务数据
--
-- ------------------------------------------------------------
-- 刻意不建的对象 (源库有, 本脚本无, 共 13 个)
-- ------------------------------------------------------------
-- A) 3 个已退役的兼容视图 —— V439 已 DROP, 源库里的残留是回滚演练遗留物:
--      v_compat_material_master / v_compat_material_bom_item / v_compat_element_bom_item
--
-- B) 10 张人工排障备份表 —— 历次修复留下的现场快照, 本就不该进建库脚本:
--      _bak_bl0098_20260803              _bak_component_formulas_20260612
--      bak_task260901_b0                 bl0092_orphan_backup_20260802
--      component_sql_view_backup_260903  flyway_dup_backup_20260803
--      mcm_pending_backup_20260801       zz_d3_bk_cd / zz_d3_bk_li / zz_d3_bk_q
--    已核实: 这 10 张表无任何存活视图/规则依赖, 无进出外键, 无生产代码引用。
--
-- 🚨 唯一一处「排除会让测试变红」的连带影响, 看到红请勿当回归去查:
--    component_sql_view_backup_260903 被 cpq-backend 的
--    src/test/java/com/cpq/task260819v9/V9ZeroRegressionTest.java 引用
--    (driftAgainstBaseline() 里 SELECT id FROM component_sql_view_backup_260903,
--     并 assertFalse(list.isEmpty()) 断言它非空)。
--    该表由 V410__task260903_compat_views.sql 用 CREATE TABLE IF NOT EXISTS + INSERT
--    从当时的存量数据现建, 而本脚本基线已是 439 ⇒ V410 不会重放 ⇒ 表不存在。
--    ⇒ V9ZeroRegressionTest 在本脚本建出的新库上会红, **属预期, 不是回归**。
--    (注: 即使把该表按空表建出来, 上述 assertFalse 一样会红 —— 它断言的是"有备份行",
--     而备份行只能来自 V410 当时的存量数据, 全空库里根本无从产生。)
--    其余 9 张表: 全工程 grep 过 *.java / *.sql / *.ts / *.tsx / *.xml / *.properties,
--    除建库脚本自身外**无任何测试或迁移引用**, 排除它们不产生任何连带影响。
--
-- 用法(Navicat):
--   1) 先新建一个空库(UTF8), 例: CREATE DATABASE cpq_db_new ENCODING 'UTF8';
--   2) 在该库上打开本文件, 直接"运行"
--   3) 跑完按文件末尾的自检 SQL 逐条核对
--
-- Navicat 兼容口径(踩坑沉淀, 改本文件时勿破坏):
--   1) 全文无 COPY 流式装载段(psql 客户端协议, GUI 跑不了) —— 数据一律 INSERT
--   2) 全文无 psql 元命令(restrict / unrestrict / connect / echo 等反斜杠开头的命令)
--   3) 函数体用单引号 AS '...' 而非美元引用 —— 朴素切分器会被美元引用切碎
--   4) 函数体内 0 个非 ASCII 字符(中文注释与 TAB 已剔除) —— 含中文的函数体在 Navicat 必失败
--   5) 函数区整体置于文件末尾 —— 把唯一风险点隔离在最后
--   6) 全文 0 个美元引用符(含注释与数据) —— 不给按它配对的解析器留永不闭合的块
--   7) 已剔除 pg_dump 18.x 产出的 PG17+ 专有会话参数(PG16 服务端不认)
-- ============================================================

--
-- PostgreSQL database dump
--


-- Dumped from database version 16.13 (Debian 16.13-1.pgdg13+1)
-- Dumped by pg_dump version 18.6 (Ubuntu 18.6-0ubuntu0.26.04.1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET search_path = public;
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA IF NOT EXISTS public;














SET default_tablespace = '';

SET default_table_access_method = heap;

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
    working_hours numeric(24,12),
    total_hours numeric(24,12),
    non_production_energy_price numeric(24,12),
    currency character varying(10),
    unit character varying(20),
    conversion_rate numeric(24,12),
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
    fixed_lead_time numeric(24,12),
    variable_time numeric(24,12),
    variable_time_batch numeric(24,12),
    capacity_unit character varying(20),
    default_defect_rate numeric(18,12),
    cost_type character varying(20),
    fixed_cost numeric(24,12),
    cost_ratio numeric(18,12),
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
    CONSTRAINT chk_capacity_system_type CHECK (((system_type)::text = ANY (ARRAY[('QUOTE'::character varying)::text, ('PRICING'::character varying)::text, ('BOTH'::character varying)::text])))
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
    CONSTRAINT chk_component_type CHECK (((component_type)::text = ANY (ARRAY[('NORMAL'::character varying)::text, ('SUBTOTAL'::character varying)::text, ('EXCEL'::character varying)::text])))
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
    builder_config jsonb,
    builder_version integer,
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
    CONSTRAINT chk_co_status CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text, ('WITHDRAWN'::character varying)::text])))
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
-- Name: ds_cost_basic_element_bom; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_element_bom (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    material_part_no character varying(128),
    item_seq integer,
    element_code character varying(128),
    content_pct numeric(26,12),
    loss_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_basic_element_bom_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_element_bom_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    material_part_no character varying(128),
    item_seq integer,
    element_code character varying(128),
    content_pct numeric(26,12),
    loss_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_basic_element_bom_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_element_bom_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_element_bom_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_element_bom_history_id_seq OWNED BY public.ds_cost_basic_element_bom_history.id;


--
-- Name: ds_cost_basic_element_bom_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_element_bom_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_element_bom_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_element_bom_id_seq OWNED BY public.ds_cost_basic_element_bom.id;


--
-- Name: ds_cost_basic_finished_fixed_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_finished_fixed_fee (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    element_name character varying(256),
    fee numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_basic_finished_fixed_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_finished_fixed_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    element_name character varying(256),
    fee numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_basic_finished_fixed_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_finished_fixed_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_finished_fixed_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_finished_fixed_fee_history_id_seq OWNED BY public.ds_cost_basic_finished_fixed_fee_history.id;


--
-- Name: ds_cost_basic_finished_fixed_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_finished_fixed_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_finished_fixed_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_finished_fixed_fee_id_seq OWNED BY public.ds_cost_basic_finished_fixed_fee.id;


--
-- Name: ds_cost_basic_finished_ratio_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_finished_ratio_fee (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    element_name character varying(256),
    ratio_pct numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_basic_finished_ratio_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_finished_ratio_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    element_name character varying(256),
    ratio_pct numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_basic_finished_ratio_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_finished_ratio_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_finished_ratio_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_finished_ratio_fee_history_id_seq OWNED BY public.ds_cost_basic_finished_ratio_fee_history.id;


--
-- Name: ds_cost_basic_finished_ratio_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_finished_ratio_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_finished_ratio_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_finished_ratio_fee_id_seq OWNED BY public.ds_cost_basic_finished_ratio_fee.id;


--
-- Name: ds_cost_basic_incoming_other_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_incoming_other_fee (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    incoming_material_no character varying(128),
    element_item_seq integer,
    element_name character varying(256),
    ratio_pct numeric(26,12),
    fee numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_basic_incoming_other_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_incoming_other_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    incoming_material_no character varying(128),
    element_item_seq integer,
    element_name character varying(256),
    ratio_pct numeric(26,12),
    fee numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_basic_incoming_other_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_incoming_other_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_incoming_other_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_incoming_other_fee_history_id_seq OWNED BY public.ds_cost_basic_incoming_other_fee_history.id;


--
-- Name: ds_cost_basic_incoming_other_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_incoming_other_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_incoming_other_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_incoming_other_fee_id_seq OWNED BY public.ds_cost_basic_incoming_other_fee.id;


--
-- Name: ds_cost_basic_incoming_other_fixed_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_incoming_other_fixed_fee (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    incoming_material_no character varying(128),
    element_item_seq integer,
    element_name character varying(256),
    fee numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_basic_incoming_other_fixed_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_incoming_other_fixed_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    incoming_material_no character varying(128),
    element_item_seq integer,
    element_name character varying(256),
    fee numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_basic_incoming_other_fixed_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_incoming_other_fixed_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_incoming_other_fixed_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_incoming_other_fixed_fee_history_id_seq OWNED BY public.ds_cost_basic_incoming_other_fixed_fee_history.id;


--
-- Name: ds_cost_basic_incoming_other_fixed_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_incoming_other_fixed_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_incoming_other_fixed_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_incoming_other_fixed_fee_id_seq OWNED BY public.ds_cost_basic_incoming_other_fixed_fee.id;


--
-- Name: ds_cost_basic_incoming_process_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_incoming_process_fee (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    incoming_material_no character varying(128),
    process_fee numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    loss_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_basic_incoming_process_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_incoming_process_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    incoming_material_no character varying(128),
    process_fee numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    loss_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_basic_incoming_process_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_incoming_process_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_incoming_process_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_incoming_process_fee_history_id_seq OWNED BY public.ds_cost_basic_incoming_process_fee_history.id;


--
-- Name: ds_cost_basic_incoming_process_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_incoming_process_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_incoming_process_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_incoming_process_fee_id_seq OWNED BY public.ds_cost_basic_incoming_process_fee.id;


--
-- Name: ds_cost_basic_material; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_material (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    material_name character varying(128),
    specification character varying(128),
    dimension character varying(128),
    old_material_no character varying(128),
    unit_weight numeric(26,12),
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    material_type character varying(128)
);


--
-- Name: ds_cost_basic_material_bom; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_material_bom (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    component_no character varying(128),
    operation_no character varying(128),
    usage_characteristic character varying(128),
    component_qty numeric(26,12),
    component_qty_unit character varying(128),
    base_qty numeric(26,12),
    base_qty_unit character varying(128),
    material_loss_rate numeric(26,12),
    material_fixed_loss numeric(26,12),
    defect_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_basic_material_bom_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_material_bom_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    component_no character varying(128),
    operation_no character varying(128),
    usage_characteristic character varying(128),
    component_qty numeric(26,12),
    component_qty_unit character varying(128),
    base_qty numeric(26,12),
    base_qty_unit character varying(128),
    material_loss_rate numeric(26,12),
    material_fixed_loss numeric(26,12),
    defect_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_basic_material_bom_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_material_bom_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_material_bom_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_material_bom_history_id_seq OWNED BY public.ds_cost_basic_material_bom_history.id;


--
-- Name: ds_cost_basic_material_bom_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_material_bom_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_material_bom_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_material_bom_id_seq OWNED BY public.ds_cost_basic_material_bom.id;


--
-- Name: ds_cost_basic_material_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_material_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_material_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_material_id_seq OWNED BY public.ds_cost_basic_material.id;


--
-- Name: ds_cost_basic_outsourced_process; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_outsourced_process (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    outsourced_fee numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_basic_outsourced_process_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_outsourced_process_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    outsourced_fee numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_basic_outsourced_process_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_outsourced_process_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_outsourced_process_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_outsourced_process_history_id_seq OWNED BY public.ds_cost_basic_outsourced_process_history.id;


--
-- Name: ds_cost_basic_outsourced_process_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_outsourced_process_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_outsourced_process_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_outsourced_process_id_seq OWNED BY public.ds_cost_basic_outsourced_process.id;


--
-- Name: ds_cost_basic_process_assembly_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_process_assembly_fee (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    process_fee numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    defect_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_basic_process_assembly_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_basic_process_assembly_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    process_fee numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    defect_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_basic_process_assembly_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_process_assembly_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_process_assembly_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_process_assembly_fee_history_id_seq OWNED BY public.ds_cost_basic_process_assembly_fee_history.id;


--
-- Name: ds_cost_basic_process_assembly_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_basic_process_assembly_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_basic_process_assembly_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_basic_process_assembly_fee_id_seq OWNED BY public.ds_cost_basic_process_assembly_fee.id;


--
-- Name: ds_cost_detail_auxiliary_energy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_auxiliary_energy (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    auxiliary_energy_price numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_auxiliary_energy_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_auxiliary_energy_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    auxiliary_energy_price numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_auxiliary_energy_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_auxiliary_energy_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_auxiliary_energy_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_auxiliary_energy_history_id_seq OWNED BY public.ds_cost_detail_auxiliary_energy_history.id;


--
-- Name: ds_cost_detail_auxiliary_energy_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_auxiliary_energy_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_auxiliary_energy_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_auxiliary_energy_id_seq OWNED BY public.ds_cost_detail_auxiliary_energy.id;


--
-- Name: ds_cost_detail_capacity; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_capacity (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    labor_std_price numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_capacity_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_capacity_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    labor_std_price numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_capacity_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_capacity_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_capacity_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_capacity_history_id_seq OWNED BY public.ds_cost_detail_capacity_history.id;


--
-- Name: ds_cost_detail_capacity_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_capacity_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_capacity_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_capacity_id_seq OWNED BY public.ds_cost_detail_capacity.id;


--
-- Name: ds_cost_detail_consumable; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_consumable (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    consumable_price numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_consumable_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_consumable_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    consumable_price numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_consumable_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_consumable_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_consumable_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_consumable_history_id_seq OWNED BY public.ds_cost_detail_consumable_history.id;


--
-- Name: ds_cost_detail_consumable_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_consumable_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_consumable_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_consumable_id_seq OWNED BY public.ds_cost_detail_consumable.id;


--
-- Name: ds_cost_detail_depreciation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_depreciation (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    depreciation_price numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_depreciation_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_depreciation_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    depreciation_price numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_depreciation_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_depreciation_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_depreciation_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_depreciation_history_id_seq OWNED BY public.ds_cost_detail_depreciation_history.id;


--
-- Name: ds_cost_detail_depreciation_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_depreciation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_depreciation_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_depreciation_id_seq OWNED BY public.ds_cost_detail_depreciation.id;


--
-- Name: ds_cost_detail_element_bom; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_element_bom (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    material_part_no character varying(128),
    item_seq integer,
    element_code character varying(128),
    content_pct numeric(26,12),
    loss_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_element_bom_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_element_bom_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    material_part_no character varying(128),
    item_seq integer,
    element_code character varying(128),
    content_pct numeric(26,12),
    loss_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_element_bom_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_element_bom_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_element_bom_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_element_bom_history_id_seq OWNED BY public.ds_cost_detail_element_bom_history.id;


--
-- Name: ds_cost_detail_element_bom_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_element_bom_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_element_bom_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_element_bom_id_seq OWNED BY public.ds_cost_detail_element_bom.id;


--
-- Name: ds_cost_detail_finished_fixed_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_finished_fixed_fee (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    element_name character varying(256),
    fee numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_finished_fixed_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_finished_fixed_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    element_name character varying(256),
    fee numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_finished_fixed_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_finished_fixed_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_finished_fixed_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_finished_fixed_fee_history_id_seq OWNED BY public.ds_cost_detail_finished_fixed_fee_history.id;


--
-- Name: ds_cost_detail_finished_fixed_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_finished_fixed_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_finished_fixed_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_finished_fixed_fee_id_seq OWNED BY public.ds_cost_detail_finished_fixed_fee.id;


--
-- Name: ds_cost_detail_finished_ratio_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_finished_ratio_fee (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    element_name character varying(256),
    ratio_pct numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_finished_ratio_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_finished_ratio_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    element_name character varying(256),
    ratio_pct numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_finished_ratio_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_finished_ratio_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_finished_ratio_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_finished_ratio_fee_history_id_seq OWNED BY public.ds_cost_detail_finished_ratio_fee_history.id;


--
-- Name: ds_cost_detail_finished_ratio_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_finished_ratio_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_finished_ratio_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_finished_ratio_fee_id_seq OWNED BY public.ds_cost_detail_finished_ratio_fee.id;


--
-- Name: ds_cost_detail_incoming_other_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_incoming_other_fee (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    incoming_material_no character varying(128),
    element_item_seq integer,
    element_name character varying(256),
    ratio_pct numeric(26,12),
    fee numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_incoming_other_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_incoming_other_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    incoming_material_no character varying(128),
    element_item_seq integer,
    element_name character varying(256),
    ratio_pct numeric(26,12),
    fee numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_incoming_other_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_incoming_other_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_incoming_other_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_incoming_other_fee_history_id_seq OWNED BY public.ds_cost_detail_incoming_other_fee_history.id;


--
-- Name: ds_cost_detail_incoming_other_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_incoming_other_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_incoming_other_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_incoming_other_fee_id_seq OWNED BY public.ds_cost_detail_incoming_other_fee.id;


--
-- Name: ds_cost_detail_incoming_other_fixed_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_incoming_other_fixed_fee (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    incoming_material_no character varying(128),
    element_item_seq integer,
    element_name character varying(256),
    fee numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_incoming_other_fixed_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_incoming_other_fixed_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    incoming_material_no character varying(128),
    element_item_seq integer,
    element_name character varying(256),
    fee numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_incoming_other_fixed_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_incoming_other_fixed_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_incoming_other_fixed_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_incoming_other_fixed_fee_history_id_seq OWNED BY public.ds_cost_detail_incoming_other_fixed_fee_history.id;


--
-- Name: ds_cost_detail_incoming_other_fixed_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_incoming_other_fixed_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_incoming_other_fixed_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_incoming_other_fixed_fee_id_seq OWNED BY public.ds_cost_detail_incoming_other_fixed_fee.id;


--
-- Name: ds_cost_detail_incoming_process_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_incoming_process_fee (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    incoming_material_no character varying(128),
    process_fee numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    loss_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_incoming_process_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_incoming_process_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    incoming_material_no character varying(128),
    process_fee numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    loss_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_incoming_process_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_incoming_process_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_incoming_process_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_incoming_process_fee_history_id_seq OWNED BY public.ds_cost_detail_incoming_process_fee_history.id;


--
-- Name: ds_cost_detail_incoming_process_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_incoming_process_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_incoming_process_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_incoming_process_fee_id_seq OWNED BY public.ds_cost_detail_incoming_process_fee.id;


--
-- Name: ds_cost_detail_material; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_material (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    material_name character varying(128),
    specification character varying(128),
    dimension character varying(128),
    old_material_no character varying(128),
    unit_weight numeric(26,12),
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    material_type character varying(128)
);


--
-- Name: ds_cost_detail_material_bom; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_material_bom (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    component_no character varying(128),
    operation_no character varying(128),
    usage_characteristic character varying(128),
    component_qty numeric(26,12),
    component_qty_unit character varying(128),
    base_qty numeric(26,12),
    base_qty_unit character varying(128),
    material_loss_rate numeric(26,12),
    material_fixed_loss numeric(26,12),
    defect_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_material_bom_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_material_bom_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    item_seq integer,
    component_no character varying(128),
    operation_no character varying(128),
    usage_characteristic character varying(128),
    component_qty numeric(26,12),
    component_qty_unit character varying(128),
    base_qty numeric(26,12),
    base_qty_unit character varying(128),
    material_loss_rate numeric(26,12),
    material_fixed_loss numeric(26,12),
    defect_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_material_bom_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_material_bom_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_material_bom_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_material_bom_history_id_seq OWNED BY public.ds_cost_detail_material_bom_history.id;


--
-- Name: ds_cost_detail_material_bom_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_material_bom_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_material_bom_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_material_bom_id_seq OWNED BY public.ds_cost_detail_material_bom.id;


--
-- Name: ds_cost_detail_material_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_material_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_material_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_material_id_seq OWNED BY public.ds_cost_detail_material.id;


--
-- Name: ds_cost_detail_outsourced_process; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_outsourced_process (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    outsourced_fee numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_outsourced_process_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_outsourced_process_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    outsourced_fee numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_outsourced_process_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_outsourced_process_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_outsourced_process_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_outsourced_process_history_id_seq OWNED BY public.ds_cost_detail_outsourced_process_history.id;


--
-- Name: ds_cost_detail_outsourced_process_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_outsourced_process_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_outsourced_process_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_outsourced_process_id_seq OWNED BY public.ds_cost_detail_outsourced_process.id;


--
-- Name: ds_cost_detail_packaging; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_packaging (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    packaging_price numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_packaging_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_packaging_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    packaging_price numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_packaging_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_packaging_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_packaging_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_packaging_history_id_seq OWNED BY public.ds_cost_detail_packaging_history.id;


--
-- Name: ds_cost_detail_packaging_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_packaging_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_packaging_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_packaging_id_seq OWNED BY public.ds_cost_detail_packaging.id;


--
-- Name: ds_cost_detail_plating_cost; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_plating_cost (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    plating_scheme_no character varying(128),
    plating_version character varying(128),
    plating_process_fee numeric(26,12),
    plating_material_fee numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    defect_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_plating_cost_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_plating_cost_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    plating_scheme_no character varying(128),
    plating_version character varying(128),
    plating_process_fee numeric(26,12),
    plating_material_fee numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    defect_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_plating_cost_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_plating_cost_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_plating_cost_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_plating_cost_history_id_seq OWNED BY public.ds_cost_detail_plating_cost_history.id;


--
-- Name: ds_cost_detail_plating_cost_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_plating_cost_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_plating_cost_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_plating_cost_id_seq OWNED BY public.ds_cost_detail_plating_cost.id;


--
-- Name: ds_cost_detail_plating_scheme; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_plating_scheme (
    id bigint NOT NULL,
    scheme_no character varying(128) NOT NULL,
    scheme_version character varying(128) NOT NULL,
    item_seq integer NOT NULL,
    plating_element character varying(256),
    plating_area numeric(26,12),
    coating_thickness numeric(26,12),
    plating_requirement character varying(256),
    density numeric(26,12),
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_plating_scheme_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_plating_scheme_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_plating_scheme_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_plating_scheme_id_seq OWNED BY public.ds_cost_detail_plating_scheme.id;


--
-- Name: ds_cost_detail_process_assembly_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_process_assembly_fee (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    process_fee numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    defect_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_process_assembly_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_process_assembly_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    process_fee numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    defect_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_process_assembly_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_process_assembly_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_process_assembly_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_process_assembly_fee_history_id_seq OWNED BY public.ds_cost_detail_process_assembly_fee_history.id;


--
-- Name: ds_cost_detail_process_assembly_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_process_assembly_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_process_assembly_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_process_assembly_fee_id_seq OWNED BY public.ds_cost_detail_process_assembly_fee.id;


--
-- Name: ds_cost_detail_production_energy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_production_energy (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    production_energy_price numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_production_energy_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_production_energy_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    production_energy_price numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_production_energy_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_production_energy_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_production_energy_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_production_energy_history_id_seq OWNED BY public.ds_cost_detail_production_energy_history.id;


--
-- Name: ds_cost_detail_production_energy_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_production_energy_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_production_energy_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_production_energy_id_seq OWNED BY public.ds_cost_detail_production_energy.id;


--
-- Name: ds_cost_detail_tooling; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_tooling (
    id bigint NOT NULL,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    item_seq integer,
    tooling_no character varying(128),
    tooling_cost numeric(26,12),
    tooling_life integer,
    cycle_output integer,
    tooling_unit_price numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_cost_detail_tooling_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_cost_detail_tooling_history (
    id bigint NOT NULL,
    origin_id bigint,
    production_no character varying(128) NOT NULL,
    operation_no character varying(128),
    item_seq integer,
    tooling_no character varying(128),
    tooling_cost numeric(26,12),
    tooling_life integer,
    cycle_output integer,
    tooling_unit_price numeric(26,12),
    currency character varying(128),
    unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32)
);


--
-- Name: ds_cost_detail_tooling_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_tooling_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_tooling_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_tooling_history_id_seq OWNED BY public.ds_cost_detail_tooling_history.id;


--
-- Name: ds_cost_detail_tooling_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_cost_detail_tooling_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_cost_detail_tooling_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_cost_detail_tooling_id_seq OWNED BY public.ds_cost_detail_tooling.id;


--
-- Name: ds_quote_annual_discount; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_annual_discount (
    id bigint NOT NULL,
    material_no character varying(128) NOT NULL,
    discount_seq integer,
    discount_rate numeric(26,12),
    fixed_discount_value numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    discount_times integer,
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_annual_discount_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_annual_discount_history (
    id bigint NOT NULL,
    origin_id bigint,
    material_no character varying(128) NOT NULL,
    discount_seq integer,
    discount_rate numeric(26,12),
    fixed_discount_value numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    discount_times integer,
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_annual_discount_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_annual_discount_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_annual_discount_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_annual_discount_history_id_seq OWNED BY public.ds_quote_annual_discount_history.id;


--
-- Name: ds_quote_annual_discount_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_annual_discount_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_annual_discount_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_annual_discount_id_seq OWNED BY public.ds_quote_annual_discount.id;


--
-- Name: ds_quote_annual_discount_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_annual_discount_record (
    id bigint NOT NULL,
    quotation_id uuid NOT NULL,
    origin_id bigint,
    base_row_fingerprint character(64),
    base_version_no integer DEFAULT 0 NOT NULL,
    material_no character varying(128) NOT NULL,
    discount_seq integer,
    discount_rate numeric(26,12),
    fixed_discount_value numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    discount_times integer,
    extend_column jsonb,
    customer_no character varying(20) NOT NULL,
    source character varying(16) DEFAULT 'QUOTE_DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_quote_annual_discount_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_annual_discount_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_annual_discount_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_annual_discount_record_id_seq OWNED BY public.ds_quote_annual_discount_record.id;


--
-- Name: ds_quote_assembly_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_assembly_fee (
    id bigint NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    assembly_operation character varying(128),
    assembly_fee numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    defect_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_assembly_fee_annual; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_assembly_fee_annual (
    id bigint NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    assembly_operation character varying(128),
    discount_seq integer,
    discount_rate numeric(26,12),
    fixed_discount_value numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    discount_times integer,
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_assembly_fee_annual_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_assembly_fee_annual_history (
    id bigint NOT NULL,
    origin_id bigint,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    assembly_operation character varying(128),
    discount_seq integer,
    discount_rate numeric(26,12),
    fixed_discount_value numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    discount_times integer,
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_assembly_fee_annual_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_assembly_fee_annual_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_assembly_fee_annual_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_assembly_fee_annual_history_id_seq OWNED BY public.ds_quote_assembly_fee_annual_history.id;


--
-- Name: ds_quote_assembly_fee_annual_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_assembly_fee_annual_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_assembly_fee_annual_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_assembly_fee_annual_id_seq OWNED BY public.ds_quote_assembly_fee_annual.id;


--
-- Name: ds_quote_assembly_fee_annual_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_assembly_fee_annual_record (
    id bigint NOT NULL,
    quotation_id uuid NOT NULL,
    origin_id bigint,
    base_row_fingerprint character(64),
    base_version_no integer DEFAULT 0 NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    assembly_operation character varying(128),
    discount_seq integer,
    discount_rate numeric(26,12),
    fixed_discount_value numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    discount_times integer,
    extend_column jsonb,
    customer_no character varying(20) NOT NULL,
    source character varying(16) DEFAULT 'QUOTE_DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_quote_assembly_fee_annual_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_assembly_fee_annual_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_assembly_fee_annual_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_assembly_fee_annual_record_id_seq OWNED BY public.ds_quote_assembly_fee_annual_record.id;


--
-- Name: ds_quote_assembly_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_assembly_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    assembly_operation character varying(128),
    assembly_fee numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    defect_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_assembly_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_assembly_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_assembly_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_assembly_fee_history_id_seq OWNED BY public.ds_quote_assembly_fee_history.id;


--
-- Name: ds_quote_assembly_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_assembly_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_assembly_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_assembly_fee_id_seq OWNED BY public.ds_quote_assembly_fee.id;


--
-- Name: ds_quote_assembly_fee_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_assembly_fee_record (
    id bigint NOT NULL,
    quotation_id uuid NOT NULL,
    origin_id bigint,
    base_row_fingerprint character(64),
    base_version_no integer DEFAULT 0 NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    assembly_operation character varying(128),
    assembly_fee numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    defect_rate numeric(26,12),
    extend_column jsonb,
    customer_no character varying(20) NOT NULL,
    source character varying(16) DEFAULT 'QUOTE_DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_quote_assembly_fee_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_assembly_fee_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_assembly_fee_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_assembly_fee_record_id_seq OWNED BY public.ds_quote_assembly_fee_record.id;


--
-- Name: ds_quote_customer_part; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_customer_part (
    id bigint NOT NULL,
    customer_no character varying(20) NOT NULL,
    customer_part_name character varying(256),
    customer_product_no character varying(128) NOT NULL,
    customer_drawing_no character varying(128),
    material_no character varying(128),
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_quote_customer_part_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_customer_part_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_customer_part_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_customer_part_id_seq OWNED BY public.ds_quote_customer_part.id;


--
-- Name: ds_quote_element_bom; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_element_bom (
    id bigint NOT NULL,
    material_no character varying(128) NOT NULL,
    material_part_no character varying(128),
    item_seq integer,
    element_code character varying(128),
    content_pct numeric(26,12),
    loss_rate numeric(26,12),
    gross_usage numeric(26,12),
    gross_usage_unit character varying(128),
    net_usage numeric(26,12),
    net_usage_unit character varying(128),
    recovery_discount numeric(26,12),
    recovery_qty character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_element_bom_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_element_bom_history (
    id bigint NOT NULL,
    origin_id bigint,
    material_no character varying(128) NOT NULL,
    material_part_no character varying(128),
    item_seq integer,
    element_code character varying(128),
    content_pct numeric(26,12),
    loss_rate numeric(26,12),
    gross_usage numeric(26,12),
    gross_usage_unit character varying(128),
    net_usage numeric(26,12),
    net_usage_unit character varying(128),
    recovery_discount numeric(26,12),
    recovery_qty character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_element_bom_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_element_bom_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_element_bom_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_element_bom_history_id_seq OWNED BY public.ds_quote_element_bom_history.id;


--
-- Name: ds_quote_element_bom_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_element_bom_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_element_bom_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_element_bom_id_seq OWNED BY public.ds_quote_element_bom.id;


--
-- Name: ds_quote_element_bom_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_element_bom_record (
    id bigint NOT NULL,
    quotation_id uuid NOT NULL,
    origin_id bigint,
    base_row_fingerprint character(64),
    base_version_no integer DEFAULT 0 NOT NULL,
    material_no character varying(128) NOT NULL,
    material_part_no character varying(128),
    item_seq integer,
    element_code character varying(128),
    content_pct numeric(26,12),
    loss_rate numeric(26,12),
    gross_usage numeric(26,12),
    gross_usage_unit character varying(128),
    net_usage numeric(26,12),
    net_usage_unit character varying(128),
    recovery_discount numeric(26,12),
    recovery_qty character varying(128),
    extend_column jsonb,
    element_price numeric(26,12),
    customer_no character varying(20) NOT NULL,
    source character varying(16) DEFAULT 'QUOTE_DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_quote_element_bom_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_element_bom_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_element_bom_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_element_bom_record_id_seq OWNED BY public.ds_quote_element_bom_record.id;


--
-- Name: ds_quote_finished_other_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_finished_other_fee (
    id bigint NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    element_name character varying(256),
    value numeric(26,12),
    ratio_pct numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_finished_other_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_finished_other_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    element_name character varying(256),
    value numeric(26,12),
    ratio_pct numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_finished_other_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_finished_other_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_finished_other_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_finished_other_fee_history_id_seq OWNED BY public.ds_quote_finished_other_fee_history.id;


--
-- Name: ds_quote_finished_other_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_finished_other_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_finished_other_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_finished_other_fee_id_seq OWNED BY public.ds_quote_finished_other_fee.id;


--
-- Name: ds_quote_finished_other_fee_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_finished_other_fee_record (
    id bigint NOT NULL,
    quotation_id uuid NOT NULL,
    origin_id bigint,
    base_row_fingerprint character(64),
    base_version_no integer DEFAULT 0 NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    element_name character varying(256),
    value numeric(26,12),
    ratio_pct numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    extend_column jsonb,
    customer_no character varying(20) NOT NULL,
    source character varying(16) DEFAULT 'QUOTE_DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_quote_finished_other_fee_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_finished_other_fee_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_finished_other_fee_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_finished_other_fee_record_id_seq OWNED BY public.ds_quote_finished_other_fee_record.id;


--
-- Name: ds_quote_incoming_annual; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_incoming_annual (
    id bigint NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    discount_seq integer,
    discount_rate numeric(26,12),
    fixed_discount_value numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    discount_times integer,
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_incoming_annual_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_incoming_annual_history (
    id bigint NOT NULL,
    origin_id bigint,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    discount_seq integer,
    discount_rate numeric(26,12),
    fixed_discount_value numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    discount_times integer,
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_incoming_annual_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_incoming_annual_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_incoming_annual_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_incoming_annual_history_id_seq OWNED BY public.ds_quote_incoming_annual_history.id;


--
-- Name: ds_quote_incoming_annual_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_incoming_annual_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_incoming_annual_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_incoming_annual_id_seq OWNED BY public.ds_quote_incoming_annual.id;


--
-- Name: ds_quote_incoming_annual_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_incoming_annual_record (
    id bigint NOT NULL,
    quotation_id uuid NOT NULL,
    origin_id bigint,
    base_row_fingerprint character(64),
    base_version_no integer DEFAULT 0 NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    discount_seq integer,
    discount_rate numeric(26,12),
    fixed_discount_value numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    discount_times integer,
    extend_column jsonb,
    customer_no character varying(20) NOT NULL,
    source character varying(16) DEFAULT 'QUOTE_DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_quote_incoming_annual_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_incoming_annual_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_incoming_annual_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_incoming_annual_record_id_seq OWNED BY public.ds_quote_incoming_annual_record.id;


--
-- Name: ds_quote_incoming_fixed_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_incoming_fixed_fee (
    id bigint NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    base_value numeric(26,12),
    ratio_pct numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    follow_material_price boolean,
    material_increase_ratio numeric(26,12),
    material_increase_value numeric(26,12),
    increase_currency character varying(128),
    increase_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_incoming_fixed_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_incoming_fixed_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    base_value numeric(26,12),
    ratio_pct numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    follow_material_price boolean,
    material_increase_ratio numeric(26,12),
    material_increase_value numeric(26,12),
    increase_currency character varying(128),
    increase_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_incoming_fixed_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_incoming_fixed_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_incoming_fixed_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_incoming_fixed_fee_history_id_seq OWNED BY public.ds_quote_incoming_fixed_fee_history.id;


--
-- Name: ds_quote_incoming_fixed_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_incoming_fixed_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_incoming_fixed_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_incoming_fixed_fee_id_seq OWNED BY public.ds_quote_incoming_fixed_fee.id;


--
-- Name: ds_quote_incoming_fixed_fee_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_incoming_fixed_fee_record (
    id bigint NOT NULL,
    quotation_id uuid NOT NULL,
    origin_id bigint,
    base_row_fingerprint character(64),
    base_version_no integer DEFAULT 0 NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    base_value numeric(26,12),
    ratio_pct numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    follow_material_price boolean,
    material_increase_ratio numeric(26,12),
    material_increase_value numeric(26,12),
    increase_currency character varying(128),
    increase_unit character varying(128),
    extend_column jsonb,
    customer_no character varying(20) NOT NULL,
    source character varying(16) DEFAULT 'QUOTE_DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_quote_incoming_fixed_fee_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_incoming_fixed_fee_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_incoming_fixed_fee_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_incoming_fixed_fee_record_id_seq OWNED BY public.ds_quote_incoming_fixed_fee_record.id;


--
-- Name: ds_quote_incoming_other_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_incoming_other_fee (
    id bigint NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    element_item_seq integer,
    element_name character varying(256),
    value numeric(26,12),
    ratio_pct numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_incoming_other_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_incoming_other_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    element_item_seq integer,
    element_name character varying(256),
    value numeric(26,12),
    ratio_pct numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_incoming_other_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_incoming_other_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_incoming_other_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_incoming_other_fee_history_id_seq OWNED BY public.ds_quote_incoming_other_fee_history.id;


--
-- Name: ds_quote_incoming_other_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_incoming_other_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_incoming_other_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_incoming_other_fee_id_seq OWNED BY public.ds_quote_incoming_other_fee.id;


--
-- Name: ds_quote_incoming_other_fee_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_incoming_other_fee_record (
    id bigint NOT NULL,
    quotation_id uuid NOT NULL,
    origin_id bigint,
    base_row_fingerprint character(64),
    base_version_no integer DEFAULT 0 NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    element_item_seq integer,
    element_name character varying(256),
    value numeric(26,12),
    ratio_pct numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    extend_column jsonb,
    customer_no character varying(20) NOT NULL,
    source character varying(16) DEFAULT 'QUOTE_DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_quote_incoming_other_fee_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_incoming_other_fee_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_incoming_other_fee_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_incoming_other_fee_record_id_seq OWNED BY public.ds_quote_incoming_other_fee_record.id;


--
-- Name: ds_quote_incoming_recovery; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_incoming_recovery (
    id bigint NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    recovery_discount numeric(26,12),
    recovery_value numeric(26,12),
    recovery_source character varying(256),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_incoming_recovery_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_incoming_recovery_history (
    id bigint NOT NULL,
    origin_id bigint,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    recovery_discount numeric(26,12),
    recovery_value numeric(26,12),
    recovery_source character varying(256),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_incoming_recovery_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_incoming_recovery_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_incoming_recovery_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_incoming_recovery_history_id_seq OWNED BY public.ds_quote_incoming_recovery_history.id;


--
-- Name: ds_quote_incoming_recovery_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_incoming_recovery_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_incoming_recovery_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_incoming_recovery_id_seq OWNED BY public.ds_quote_incoming_recovery.id;


--
-- Name: ds_quote_incoming_recovery_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_incoming_recovery_record (
    id bigint NOT NULL,
    quotation_id uuid NOT NULL,
    origin_id bigint,
    base_row_fingerprint character(64),
    base_version_no integer DEFAULT 0 NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    recovery_discount numeric(26,12),
    recovery_value numeric(26,12),
    recovery_source character varying(256),
    extend_column jsonb,
    customer_no character varying(20) NOT NULL,
    source character varying(16) DEFAULT 'QUOTE_DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_quote_incoming_recovery_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_incoming_recovery_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_incoming_recovery_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_incoming_recovery_record_id_seq OWNED BY public.ds_quote_incoming_recovery_record.id;


--
-- Name: ds_quote_material; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_material (
    id bigint NOT NULL,
    material_no character varying(128) NOT NULL,
    material_name character varying(128),
    specification character varying(128),
    dimension character varying(128),
    old_material_no character varying(128),
    unit_weight numeric(26,12),
    production_no character varying(128),
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    material_type character varying(128),
    category_code character varying(128),
    customer_no character varying(20) NOT NULL
);


--
-- Name: ds_quote_material_bom; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_material_bom (
    id bigint NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    unit_weight numeric(26,12),
    output_material_type character varying(128),
    component_qty numeric(26,12),
    gross_weight numeric(26,12),
    net_weight numeric(26,12),
    weight_unit character varying(128),
    material_ratio numeric(26,12),
    loss_rate numeric(26,12),
    defect_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_material_bom_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_material_bom_history (
    id bigint NOT NULL,
    origin_id bigint,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    unit_weight numeric(26,12),
    output_material_type character varying(128),
    component_qty numeric(26,12),
    gross_weight numeric(26,12),
    net_weight numeric(26,12),
    weight_unit character varying(128),
    material_ratio numeric(26,12),
    loss_rate numeric(26,12),
    defect_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_material_bom_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_material_bom_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_material_bom_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_material_bom_history_id_seq OWNED BY public.ds_quote_material_bom_history.id;


--
-- Name: ds_quote_material_bom_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_material_bom_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_material_bom_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_material_bom_id_seq OWNED BY public.ds_quote_material_bom.id;


--
-- Name: ds_quote_material_bom_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_material_bom_record (
    id bigint NOT NULL,
    quotation_id uuid NOT NULL,
    origin_id bigint,
    base_row_fingerprint character(64),
    base_version_no integer DEFAULT 0 NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    unit_weight numeric(26,12),
    output_material_type character varying(128),
    component_qty numeric(26,12),
    gross_weight numeric(26,12),
    net_weight numeric(26,12),
    weight_unit character varying(128),
    material_ratio numeric(26,12),
    loss_rate numeric(26,12),
    defect_rate numeric(26,12),
    extend_column jsonb,
    element_price numeric(26,12),
    customer_no character varying(20) NOT NULL,
    source character varying(16) DEFAULT 'QUOTE_DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_quote_material_bom_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_material_bom_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_material_bom_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_material_bom_record_id_seq OWNED BY public.ds_quote_material_bom_record.id;


--
-- Name: ds_quote_material_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_material_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_material_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_material_id_seq OWNED BY public.ds_quote_material.id;


--
-- Name: ds_quote_plating_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_plating_fee (
    id bigint NOT NULL,
    material_no character varying(128) NOT NULL,
    plating_scheme_no character varying(128),
    plating_version character varying(128),
    plating_process_fee numeric(26,12),
    plating_material_fee numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    defect_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_plating_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_plating_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    material_no character varying(128) NOT NULL,
    plating_scheme_no character varying(128),
    plating_version character varying(128),
    plating_process_fee numeric(26,12),
    plating_material_fee numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    defect_rate numeric(26,12),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_plating_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_plating_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_plating_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_plating_fee_history_id_seq OWNED BY public.ds_quote_plating_fee_history.id;


--
-- Name: ds_quote_plating_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_plating_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_plating_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_plating_fee_id_seq OWNED BY public.ds_quote_plating_fee.id;


--
-- Name: ds_quote_plating_fee_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_plating_fee_record (
    id bigint NOT NULL,
    quotation_id uuid NOT NULL,
    origin_id bigint,
    base_row_fingerprint character(64),
    base_version_no integer DEFAULT 0 NOT NULL,
    material_no character varying(128) NOT NULL,
    plating_scheme_no character varying(128),
    plating_version character varying(128),
    plating_process_fee numeric(26,12),
    plating_material_fee numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    defect_rate numeric(26,12),
    extend_column jsonb,
    customer_no character varying(20) NOT NULL,
    source character varying(16) DEFAULT 'QUOTE_DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_quote_plating_fee_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_plating_fee_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_plating_fee_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_plating_fee_record_id_seq OWNED BY public.ds_quote_plating_fee_record.id;


--
-- Name: ds_quote_plating_scheme; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_plating_scheme (
    id bigint NOT NULL,
    scheme_no character varying(128) NOT NULL,
    scheme_version character varying(128) NOT NULL,
    item_seq integer NOT NULL,
    plating_element character varying(256),
    price_source_url character varying(512),
    price_source_name character varying(256),
    price_fetch_rule character varying(256),
    plating_area numeric(26,12),
    coating_thickness numeric(26,12),
    plating_requirement character varying(256),
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    customer_no character varying(20) NOT NULL
);


--
-- Name: ds_quote_plating_scheme_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_plating_scheme_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_plating_scheme_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_plating_scheme_id_seq OWNED BY public.ds_quote_plating_scheme.id;


--
-- Name: ds_quote_record_stale; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_record_stale (
    id bigint NOT NULL,
    quotation_id uuid NOT NULL,
    reason character varying(32) NOT NULL,
    detail text,
    detected_at timestamp with time zone DEFAULT now() NOT NULL,
    detected_by character varying(64),
    cleared_at timestamp with time zone
);


--
-- Name: ds_quote_record_stale_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_record_stale_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_record_stale_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_record_stale_id_seq OWNED BY public.ds_quote_record_stale.id;


--
-- Name: ds_quote_self_process_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_self_process_fee (
    id bigint NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    operation_item_seq integer,
    operation_no character varying(128),
    value numeric(26,12),
    ratio_pct numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_self_process_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_self_process_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    operation_item_seq integer,
    operation_no character varying(128),
    value numeric(26,12),
    ratio_pct numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_self_process_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_self_process_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_self_process_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_self_process_fee_history_id_seq OWNED BY public.ds_quote_self_process_fee_history.id;


--
-- Name: ds_quote_self_process_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_self_process_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_self_process_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_self_process_fee_id_seq OWNED BY public.ds_quote_self_process_fee.id;


--
-- Name: ds_quote_self_process_fee_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_self_process_fee_record (
    id bigint NOT NULL,
    quotation_id uuid NOT NULL,
    origin_id bigint,
    base_row_fingerprint character(64),
    base_version_no integer DEFAULT 0 NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    input_material_no character varying(128),
    operation_item_seq integer,
    operation_no character varying(128),
    value numeric(26,12),
    ratio_pct numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    extend_column jsonb,
    customer_no character varying(20) NOT NULL,
    source character varying(16) DEFAULT 'QUOTE_DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_quote_self_process_fee_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_self_process_fee_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_self_process_fee_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_self_process_fee_record_id_seq OWNED BY public.ds_quote_self_process_fee_record.id;


--
-- Name: ds_quote_sub_component_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_sub_component_fee (
    id bigint NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    sub_component_no character varying(128),
    supplier_no character varying(128),
    supplier_name character varying(256),
    element_item_seq integer,
    element_name character varying(256),
    value numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_sub_component_fee_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_sub_component_fee_history (
    id bigint NOT NULL,
    origin_id bigint,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    sub_component_no character varying(128),
    supplier_no character varying(128),
    supplier_name character varying(256),
    element_item_seq integer,
    element_name character varying(256),
    value numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    version_no integer NOT NULL,
    row_fingerprint character(64) NOT NULL,
    source character varying(16) DEFAULT 'IMPORT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64),
    archived_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(32),
    customer_no character varying(20) NOT NULL,
    source_quotation_id uuid
);


--
-- Name: ds_quote_sub_component_fee_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_sub_component_fee_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_sub_component_fee_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_sub_component_fee_history_id_seq OWNED BY public.ds_quote_sub_component_fee_history.id;


--
-- Name: ds_quote_sub_component_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_sub_component_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_sub_component_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_sub_component_fee_id_seq OWNED BY public.ds_quote_sub_component_fee.id;


--
-- Name: ds_quote_sub_component_fee_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ds_quote_sub_component_fee_record (
    id bigint NOT NULL,
    quotation_id uuid NOT NULL,
    origin_id bigint,
    base_row_fingerprint character(64),
    base_version_no integer DEFAULT 0 NOT NULL,
    material_no character varying(128) NOT NULL,
    item_seq integer,
    sub_component_no character varying(128),
    supplier_no character varying(128),
    supplier_name character varying(256),
    element_item_seq integer,
    element_name character varying(256),
    value numeric(26,12),
    currency character varying(128),
    pricing_unit character varying(128),
    extend_column jsonb,
    customer_no character varying(20) NOT NULL,
    source character varying(16) DEFAULT 'QUOTE_DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64),
    updated_at timestamp with time zone,
    updated_by character varying(64)
);


--
-- Name: ds_quote_sub_component_fee_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ds_quote_sub_component_fee_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ds_quote_sub_component_fee_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ds_quote_sub_component_fee_record_id_seq OWNED BY public.ds_quote_sub_component_fee_record.id;


--
-- Name: electricity_price; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.electricity_price (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    region character varying(50) NOT NULL,
    voltage_level character varying(20),
    price_type character varying(20) NOT NULL,
    time_range character varying(50),
    price numeric(24,12) NOT NULL,
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
    CONSTRAINT chk_element_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])))
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
    composition_qty numeric(24,12),
    base_qty numeric(24,12),
    component_usage_type character varying(100),
    feature_mgmt character varying(20),
    content numeric(24,12),
    upper_limit_pct numeric(18,12),
    lower_limit_pct numeric(18,12),
    scrap_batch numeric(24,12),
    scrap_rate numeric(18,12),
    defect_rate numeric(18,12),
    fixed_scrap numeric(24,12),
    issue_location character varying(50),
    issue_storage character varying(50),
    fas_group character varying(20),
    plug_position character varying(50),
    ref_rd_center character varying(50),
    is_optional boolean,
    wo_expand_option character varying(20),
    is_purchase_replace boolean,
    component_lead_time numeric(24,12),
    main_substitute character varying(20),
    attached_part character varying(20),
    ecn_no character varying(30),
    use_qty_formula boolean,
    qty_formula character varying(500),
    scrap_rate_type character varying(20),
    is_backflush boolean,
    is_customer_supply boolean,
    recovery_discount numeric(18,12),
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
    raw_price numeric(26,12),
    raw_high numeric(26,12),
    raw_low numeric(26,12),
    raw_open numeric(26,12),
    raw_close numeric(26,12),
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
    CONSTRAINT chk_edp_fetch_status CHECK (((fetch_status)::text = ANY (ARRAY[('SUCCESS'::character varying)::text, ('FAILED'::character varying)::text, ('MANUAL'::character varying)::text, ('IMPORT'::character varying)::text])))
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
    CONSTRAINT chk_edpl_action CHECK (((action)::text = ANY (ARRAY[('CREATE'::character varying)::text, ('UPDATE'::character varying)::text, ('DELETE'::character varying)::text])))
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
    premium_price numeric(26,12),
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
    factor numeric(18,12) DEFAULT 1 NOT NULL,
    premium numeric(26,12) DEFAULT 0 NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by uuid,
    CONSTRAINT chk_eps2_factor CHECK ((factor > (0)::numeric)),
    CONSTRAINT chk_eps2_method CHECK (((method)::text = ANY (ARRAY[('LATEST'::character varying)::text, ('AVG'::character varying)::text, ('MAX'::character varying)::text, ('MIN'::character varying)::text]))),
    CONSTRAINT chk_eps2_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DISABLED'::character varying)::text]))),
    CONSTRAINT chk_eps2_unit CHECK (((window_unit IS NULL) OR ((window_unit)::text = ANY (ARRAY[('DAY'::character varying)::text, ('WEEK'::character varying)::text, ('MONTH'::character varying)::text, ('YEAR'::character varying)::text])))),
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
    CONSTRAINT chk_epsl_action CHECK (((action)::text = ANY (ARRAY[('CREATE'::character varying)::text, ('UPDATE'::character varying)::text, ('DELETE'::character varying)::text])))
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
    current_price numeric(26,12),
    previous_price numeric(26,12),
    change_rate numeric(18,12),
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
    rate numeric(24,12) NOT NULL,
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
    rate numeric(22,12) NOT NULL,
    ref_rate numeric(22,12),
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
    value numeric(24,12),
    ratio numeric(18,12),
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
-- Name: flyway_schema_history; Type: TABLE; Schema: public; Owner: -
--

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
    success boolean NOT NULL
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
    standard_labor_rate numeric(24,12) NOT NULL,
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
    composition_qty numeric(24,12),
    base_qty numeric(24,12),
    component_usage_type character varying(100),
    feature_mgmt character varying(20),
    upper_limit_pct numeric(18,12),
    lower_limit_pct numeric(18,12),
    scrap_batch numeric(24,12),
    scrap_rate numeric(18,12),
    fixed_scrap numeric(24,12),
    issue_location character varying(50),
    issue_storage character varying(50),
    fas_group character varying(20),
    plug_position character varying(50),
    ref_rd_center character varying(50),
    is_optional boolean,
    wo_expand_option character varying(20),
    is_purchase_replace boolean,
    component_lead_time numeric(24,12),
    main_substitute character varying(20),
    attached_part character varying(20),
    ecn_no character varying(30),
    use_qty_formula boolean,
    qty_formula character varying(500),
    scrap_rate_type character varying(20),
    is_backflush boolean,
    is_customer_supply boolean,
    defect_rate numeric(18,12),
    calc_type character varying(20),
    recovery_discount numeric(18,12),
    recovery_currency character varying(10),
    recovery_unit character varying(20),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    is_current boolean DEFAULT true NOT NULL,
    bom_version character varying(20),
    rough_weight numeric(26,12) DEFAULT NULL::numeric,
    net_weight numeric(26,12) DEFAULT NULL::numeric,
    weight_unit character varying(20),
    production_no character varying(32),
    pending_quotation_id uuid,
    pending_supersedes uuid[],
    material_ratio numeric(24,12),
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
    exchange_rate numeric(22,12),
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
    unit_weight numeric(24,12),
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
    allow_custom_content boolean DEFAULT false NOT NULL,
    CONSTRAINT chk_material_recipe_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text]))),
    CONSTRAINT chk_material_recipe_type CHECK (((recipe_type)::text = ANY (ARRAY[('locked'::character varying)::text, ('editable'::character varying)::text, ('partial'::character varying)::text])))
);


--
-- Name: material_recipe_composition; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_recipe_composition (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    recipe_id uuid NOT NULL,
    element_no character varying(32) NOT NULL,
    element_code character varying(32) NOT NULL,
    element_name character varying(64) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: material_recipe_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_recipe_config (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    recipe_id uuid NOT NULL,
    config_no character varying(80) NOT NULL,
    seq integer NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    remark character varying(255),
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    CONSTRAINT chk_mrc_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))
);


--
-- Name: material_recipe_element; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_recipe_element (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    recipe_id uuid,
    element_code character varying(32) NOT NULL,
    element_name character varying(64) NOT NULL,
    default_pct numeric(16,12) NOT NULL,
    min_pct numeric(16,12),
    max_pct numeric(16,12),
    is_locked boolean DEFAULT false NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    element_no character varying(32),
    config_id uuid,
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
    CONSTRAINT chk_mc_subject CHECK (((subject_type)::text = ANY (ARRAY[('SALES_PART'::character varying)::text, ('MATERIAL'::character varying)::text])))
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
    CONSTRAINT chk_mcf_role CHECK (((file_role)::text = ANY (ARRAY[('GLB'::character varying)::text, ('THUMBNAIL'::character varying)::text, ('OTHER'::character varying)::text])))
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
    usage_qty numeric(24,12) NOT NULL,
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
    plating_process_fee numeric(26,12),
    plating_material_fee numeric(26,12),
    currency character varying(8),
    price_unit character varying(16),
    defect_rate numeric(18,12),
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
    surface_area numeric(24,12) NOT NULL,
    plating_area numeric(24,12),
    plating_thickness numeric(24,12) NOT NULL,
    plating_requirement character varying(200),
    density numeric(24,12),
    element_usage numeric(24,12) NOT NULL,
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
    CONSTRAINT chk_plating_scheme_system_type CHECK (((system_type)::text = ANY (ARRAY[('QUOTE'::character varying)::text, ('PRICING'::character varying)::text, ('BOTH'::character varying)::text])))
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
    default_defect_rate numeric(18,12),
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
    usage_qty numeric(24,12),
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
    batch_size numeric(24,12),
    round_step numeric(24,12),
    working_hours numeric(24,12),
    currency character varying(10),
    unit character varying(20),
    conversion_rate numeric(24,12),
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
    user_data_version integer DEFAULT 0 NOT NULL,
    CONSTRAINT chk_q_priority CHECK (((priority)::text = ANY (ARRAY[('HIGH'::character varying)::text, ('MEDIUM'::character varying)::text, ('LOW'::character varying)::text]))),
    CONSTRAINT chk_q_stage CHECK (((stage)::text = ANY (ARRAY[('INITIAL_CONTACT'::character varying)::text, ('REQUIREMENT_CONFIRMATION'::character varying)::text, ('QUOTING'::character varying)::text, ('NEGOTIATION'::character varying)::text]))),
    CONSTRAINT chk_q_status CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('SUBMITTED'::character varying)::text, ('APPROVED'::character varying)::text, ('SENT'::character varying)::text, ('ACCEPTED'::character varying)::text, ('REJECTED'::character varying)::text, ('EXPIRED'::character varying)::text, ('CANCELLED'::character varying)::text, ('COSTING_REJECTED'::character varying)::text]))),
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
    CONSTRAINT chk_qa_action CHECK (((action)::text = ANY (ARRAY[('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text, ('WITHDRAWN'::character varying)::text, ('COSTING_APPROVED'::character varying)::text, ('COSTING_REJECTED'::character varying)::text])))
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
    process_no character varying(20),
    seq_no integer
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
-- Name: sel_product_no; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sel_product_no (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_no character varying(20) NOT NULL,
    customer_product_no character varying(100) NOT NULL,
    customer_product_name character varying(200),
    quote_part_no character varying(50) NOT NULL,
    quotation_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid
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
-- Name: semantic_edge; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.semantic_edge (
    id uuid NOT NULL,
    from_node_id uuid NOT NULL,
    to_node_id uuid NOT NULL,
    edge_kind character varying(20) NOT NULL,
    cardinality character varying(20) NOT NULL,
    fallback_order integer,
    coalesce_group character varying(40),
    assert_status character varying(10) DEFAULT 'NA'::character varying NOT NULL,
    assert_sample_rows bigint,
    note text,
    created_by character varying(80),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_by character varying(80),
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    fallback_to_join_key boolean DEFAULT false NOT NULL,
    CONSTRAINT chk_card CHECK (((cardinality)::text = ANY ((ARRAY['MANY_TO_ONE'::character varying, 'ONE_TO_MANY'::character varying])::text[])))
);


--
-- Name: semantic_edge_key; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.semantic_edge_key (
    id uuid NOT NULL,
    edge_id uuid NOT NULL,
    left_column character varying(120) NOT NULL,
    right_column character varying(120) NOT NULL,
    seq integer DEFAULT 0 NOT NULL
);


--
-- Name: semantic_node; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.semantic_node (
    id uuid NOT NULL,
    node_key character varying(80) NOT NULL,
    display_name character varying(200) NOT NULL,
    short_name character varying(40) NOT NULL,
    node_kind character varying(20) NOT NULL,
    physical_table character varying(120),
    scope character varying(20) DEFAULT 'NONE'::character varying NOT NULL,
    anchor_expr character varying(200),
    grain_columns text[] DEFAULT '{}'::text[] NOT NULL,
    fixed_predicate text,
    func_signature text,
    discriminator text,
    source_handler character varying(120),
    dialect character varying(20) DEFAULT 'QUOTE'::character varying NOT NULL,
    note text,
    created_by character varying(80),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_by character varying(80),
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL
);


--
-- Name: semantic_node_column; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.semantic_node_column (
    id uuid NOT NULL,
    node_id uuid NOT NULL,
    db_column character varying(120) NOT NULL,
    display_name character varying(200) NOT NULL,
    data_type character varying(20) NOT NULL,
    is_code boolean DEFAULT false NOT NULL,
    roles text[] DEFAULT '{}'::text[] NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_by character varying(80),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_by character varying(80),
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL
);


--
-- Name: semantic_tab_view; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.semantic_tab_view (
    id uuid NOT NULL,
    tab_type character varying(40) NOT NULL,
    variant_key character varying(40) DEFAULT ''::character varying NOT NULL,
    variant_label character varying(80),
    anchor_node_id uuid NOT NULL,
    switches text[] DEFAULT '{}'::text[] NOT NULL,
    dialect character varying(20) DEFAULT 'QUOTE'::character varying NOT NULL,
    created_by character varying(80),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_by character varying(80),
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL
);


--
-- Name: semantic_tab_view_column; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.semantic_tab_view_column (
    id uuid NOT NULL,
    view_id uuid NOT NULL,
    column_id uuid NOT NULL,
    roles text[] DEFAULT '{}'::text[] NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_by character varying(80),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_by character varying(80),
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL
);


--
-- Name: semantic_tab_view_node; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.semantic_tab_view_node (
    id uuid NOT NULL,
    view_id uuid NOT NULL,
    node_id uuid NOT NULL,
    role character varying(10) NOT NULL,
    add_dims text[] DEFAULT '{}'::text[] NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_by character varying(80),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_by character varying(80),
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    CONSTRAINT chk_role CHECK (((role)::text = ANY ((ARRAY['MAIN'::character varying, 'AUX'::character varying])::text[])))
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
    tooling_unit_cost numeric(24,12),
    tool_life bigint,
    cycle_output numeric(24,12),
    tooling_unit_price numeric(22,12) NOT NULL,
    currency character varying(10),
    unit character varying(20),
    is_effective boolean,
    conversion_rate numeric(24,12),
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
    pricing_price numeric(24,12),
    cost_ratio numeric(18,12),
    market_ref_price numeric(24,12),
    currency character varying(10),
    unit character varying(20),
    conversion_rate numeric(24,12),
    recovery_discount numeric(18,12),
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
    premium_fee numeric(24,12),
    fetched_price numeric(24,12),
    fetch_time timestamp(6) with time zone,
    effective_date date,
    expire_date date,
    base_value numeric(24,12),
    is_fluctuate_with_material boolean,
    material_increase_ratio numeric(18,12),
    material_fixed_increase numeric(24,12),
    defect_rate numeric(18,12),
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
    CONSTRAINT chk_unit_price_type CHECK (((price_type)::text = ANY (ARRAY[('ELEMENT'::character varying)::text, ('MATERIAL'::character varying)::text, ('COMPONENT'::character varying)::text, ('PART'::character varying)::text, ('CONSUMABLE'::character varying)::text, ('INCOMING_MATERIAL_PROCESS'::character varying)::text, ('INCOMING_MATERIAL_OTHER'::character varying)::text, ('INCOMING_MATERIAL_REDUCTION'::character varying)::text, ('INCOMING_MATERIAL_RECYCLE'::character varying)::text, ('PROCESS'::character varying)::text, ('FINISHED_MATERIAL_OTHER'::character varying)::text, ('COMPONENT_OTHER'::character varying)::text, ('COMPONENT_REDUCTION'::character varying)::text, ('PLATING'::character varying)::text, ('MATERIAL_PRICE'::character varying)::text, ('PACKAGING'::character varying)::text, ('INCOMING_PROCESS'::character varying)::text, ('INCOMING_OTHER'::character varying)::text, ('SELF_PROCESS'::character varying)::text, ('FINISHED_OTHER'::character varying)::text, ('OUTSOURCE_PROCESS'::character varying)::text])))
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
    NULL::uuid AS quotation_line_item_id,
    ebi.material_part_no
   FROM ((( SELECT 'QUOTE'::character varying(10) AS system_type,
            e.customer_no,
            (e.material_no)::character varying(20) AS material_no,
            '2000'::character varying(100) AS characteristic,
            (e.element_code)::character varying(20) AS component_no,
            e.item_seq AS seq_no,
            (e.content_pct)::numeric(24,12) AS content,
            (e.material_no)::character varying(20) AS hf_part_no,
            true AS is_current,
            (e.material_part_no)::character varying(32) AS material_part_no
           FROM public.ds_quote_element_bom e) ebi
     LEFT JOIN ( SELECT (m.material_no)::character varying(20) AS material_no,
            (m.material_name)::character varying(100) AS material_name
           FROM ( SELECT DISTINCT ON (ds_quote_material.material_no) ds_quote_material.material_no,
                    ds_quote_material.material_name,
                    ds_quote_material.customer_no
                   FROM public.ds_quote_material
                  ORDER BY ds_quote_material.material_no, ds_quote_material.customer_no) m) mm ON (((mm.material_no)::text = (ebi.material_no)::text)))
     LEFT JOIN public.customer c ON (((c.code)::text = (ebi.customer_no)::text)))
  WHERE (((ebi.system_type)::text = 'QUOTE'::text) AND (ebi.hf_part_no IS NOT NULL) AND (ebi.is_current = true) AND ((ebi.characteristic)::text = ( SELECT max((ebi2.characteristic)::text) AS max
           FROM ( SELECT 'QUOTE'::character varying(10) AS system_type,
                    e.customer_no,
                    (e.material_no)::character varying(20) AS material_no,
                    '2000'::character varying(100) AS characteristic,
                    (e.material_part_no)::character varying(32) AS material_part_no
                   FROM public.ds_quote_element_bom e) ebi2
          WHERE (((ebi2.system_type)::text = (ebi.system_type)::text) AND ((ebi2.customer_no)::text = (ebi.customer_no)::text) AND ((ebi2.material_no)::text = (ebi.material_no)::text) AND (NOT ((ebi2.material_part_no)::text IS DISTINCT FROM (ebi.material_part_no)::text))))));


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
   FROM (((( SELECT 'QUOTE'::character varying(10) AS system_type,
            b.customer_no,
            (b.material_no)::character varying(20) AS material_no,
            (b.output_material_type)::character varying(100) AS characteristic,
            b.item_seq AS seq_no,
            (b.input_material_no)::character varying(20) AS component_no,
            (mrx.symbol)::character varying(100) AS component_usage_type,
            true AS is_current
           FROM (public.ds_quote_material_bom b
             LEFT JOIN public.material_recipe mrx ON (((mrx.code)::text = (b.input_material_no)::text)))) asy
     LEFT JOIN ( SELECT (m.material_no)::character varying(20) AS material_no,
            (m.material_name)::character varying(100) AS material_name,
            (m.specification)::character varying(100) AS specification,
            (m.material_type)::character varying(50) AS material_type
           FROM ( SELECT DISTINCT ON (ds_quote_material.material_no) ds_quote_material.material_no,
                    ds_quote_material.material_name,
                    ds_quote_material.specification,
                    ds_quote_material.material_type,
                    ds_quote_material.customer_no
                   FROM public.ds_quote_material
                  ORDER BY ds_quote_material.material_no, ds_quote_material.customer_no) m) mm ON (((mm.material_no)::text = (asy.component_no)::text)))
     LEFT JOIN public.material_recipe mr ON (((mr.code)::text = (asy.component_no)::text)))
     LEFT JOIN public.customer c ON (((c.code)::text = (asy.customer_no)::text)))
  WHERE (((asy.system_type)::text = 'QUOTE'::text) AND ((asy.characteristic)::text IS DISTINCT FROM 'ASSEMBLY'::text) AND ((asy.characteristic)::text IS DISTINCT FROM 'OUTSOURCED'::text) AND (asy.is_current = true))
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
   FROM ( SELECT (m.material_no)::character varying(20) AS material_no,
            (m.material_name)::character varying(100) AS material_name,
            (m.specification)::character varying(100) AS specification,
            (m.material_type)::character varying(50) AS material_type
           FROM ( SELECT DISTINCT ON (ds_quote_material.material_no) ds_quote_material.material_no,
                    ds_quote_material.material_name,
                    ds_quote_material.specification,
                    ds_quote_material.material_type,
                    ds_quote_material.customer_no
                   FROM public.ds_quote_material
                  ORDER BY ds_quote_material.material_no, ds_quote_material.customer_no) m) mm
  WHERE (NOT (EXISTS ( SELECT 1
           FROM public.ds_quote_material_bom asy2
          WHERE (((asy2.output_material_type)::text IS DISTINCT FROM 'ASSEMBLY'::text) AND ((asy2.material_no)::text = (mm.material_no)::text)))));


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
     LEFT JOIN ( SELECT (m.material_no)::character varying(20) AS material_no,
            (m.material_name)::character varying(100) AS material_name
           FROM ( SELECT DISTINCT ON (ds_quote_material.material_no) ds_quote_material.material_no,
                    ds_quote_material.material_name,
                    ds_quote_material.customer_no
                   FROM public.ds_quote_material
                  ORDER BY ds_quote_material.material_no, ds_quote_material.customer_no) m) mm ON (((mm.material_no)::text = (up.finished_material_no)::text)))
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
   FROM (((( SELECT 'QUOTE'::character varying(10) AS system_type,
            b.customer_no,
            (b.material_no)::character varying(20) AS material_no,
            (b.output_material_type)::character varying(100) AS characteristic,
            b.item_seq AS seq_no,
            (b.input_material_no)::character varying(20) AS component_no,
            NULL::character varying(20) AS operation_no,
            true AS is_current
           FROM public.ds_quote_material_bom b) asy
     LEFT JOIN ( SELECT (m.material_no)::character varying(20) AS material_no,
            (m.material_name)::character varying(100) AS material_name
           FROM ( SELECT DISTINCT ON (ds_quote_material.material_no) ds_quote_material.material_no,
                    ds_quote_material.material_name,
                    ds_quote_material.customer_no
                   FROM public.ds_quote_material
                  ORDER BY ds_quote_material.material_no, ds_quote_material.customer_no) m) mm ON (((mm.material_no)::text = (asy.component_no)::text)))
     LEFT JOIN public.process_master pm ON (((pm.process_no)::text = (asy.operation_no)::text)))
     LEFT JOIN public.customer c ON (((c.code)::text = (asy.customer_no)::text)))
  WHERE (((asy.system_type)::text = 'QUOTE'::text) AND ((asy.characteristic)::text = 'ASSEMBLY'::text) AND (asy.is_current = true) AND (asy.operation_no IS NOT NULL));


--
-- Name: v_ds_cost_basic_element_bom_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_basic_element_bom_all AS
 SELECT e.id,
    e.production_no,
    e.material_part_no,
    e.item_seq,
    e.element_code,
    e.content_pct,
    e.loss_rate,
    e.version_no,
    e.row_fingerprint,
    e.source,
    e.created_at,
    e.created_by,
    e.updated_at,
    e.updated_by,
    true AS is_current,
    mm.material_no AS sales_material_no
   FROM (public.ds_cost_basic_element_bom e
     LEFT JOIN LATERAL ( SELECT (m.material_no)::character varying(20) AS material_no
           FROM public.ds_quote_material m
          WHERE ((m.production_no)::text = (e.production_no)::text)
          ORDER BY m.material_no
         LIMIT 1) mm ON (true))
UNION ALL
 SELECT h.id,
    h.production_no,
    h.material_part_no,
    h.item_seq,
    h.element_code,
    h.content_pct,
    h.loss_rate,
    h.version_no,
    h.row_fingerprint,
    h.source,
    h.created_at,
    h.created_by,
    h.updated_at,
    h.updated_by,
    false AS is_current,
    mm.material_no AS sales_material_no
   FROM (public.ds_cost_basic_element_bom_history h
     LEFT JOIN LATERAL ( SELECT (m.material_no)::character varying(20) AS material_no
           FROM public.ds_quote_material m
          WHERE ((m.production_no)::text = (h.production_no)::text)
          ORDER BY m.material_no
         LIMIT 1) mm ON (true));


--
-- Name: v_ds_cost_basic_finished_fixed_fee_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_basic_finished_fixed_fee_all AS
 SELECT ds_cost_basic_finished_fixed_fee.id,
    ds_cost_basic_finished_fixed_fee.production_no,
    ds_cost_basic_finished_fixed_fee.item_seq,
    ds_cost_basic_finished_fixed_fee.element_name,
    ds_cost_basic_finished_fixed_fee.fee,
    ds_cost_basic_finished_fixed_fee.currency,
    ds_cost_basic_finished_fixed_fee.pricing_unit,
    ds_cost_basic_finished_fixed_fee.version_no,
    ds_cost_basic_finished_fixed_fee.row_fingerprint,
    ds_cost_basic_finished_fixed_fee.source,
    ds_cost_basic_finished_fixed_fee.created_at,
    ds_cost_basic_finished_fixed_fee.created_by,
    ds_cost_basic_finished_fixed_fee.updated_at,
    ds_cost_basic_finished_fixed_fee.updated_by,
    true AS is_current
   FROM public.ds_cost_basic_finished_fixed_fee
UNION ALL
 SELECT ds_cost_basic_finished_fixed_fee_history.id,
    ds_cost_basic_finished_fixed_fee_history.production_no,
    ds_cost_basic_finished_fixed_fee_history.item_seq,
    ds_cost_basic_finished_fixed_fee_history.element_name,
    ds_cost_basic_finished_fixed_fee_history.fee,
    ds_cost_basic_finished_fixed_fee_history.currency,
    ds_cost_basic_finished_fixed_fee_history.pricing_unit,
    ds_cost_basic_finished_fixed_fee_history.version_no,
    ds_cost_basic_finished_fixed_fee_history.row_fingerprint,
    ds_cost_basic_finished_fixed_fee_history.source,
    ds_cost_basic_finished_fixed_fee_history.created_at,
    ds_cost_basic_finished_fixed_fee_history.created_by,
    ds_cost_basic_finished_fixed_fee_history.updated_at,
    ds_cost_basic_finished_fixed_fee_history.updated_by,
    false AS is_current
   FROM public.ds_cost_basic_finished_fixed_fee_history;


--
-- Name: v_ds_cost_basic_finished_ratio_fee_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_basic_finished_ratio_fee_all AS
 SELECT ds_cost_basic_finished_ratio_fee.id,
    ds_cost_basic_finished_ratio_fee.production_no,
    ds_cost_basic_finished_ratio_fee.item_seq,
    ds_cost_basic_finished_ratio_fee.element_name,
    ds_cost_basic_finished_ratio_fee.ratio_pct,
    ds_cost_basic_finished_ratio_fee.version_no,
    ds_cost_basic_finished_ratio_fee.row_fingerprint,
    ds_cost_basic_finished_ratio_fee.source,
    ds_cost_basic_finished_ratio_fee.created_at,
    ds_cost_basic_finished_ratio_fee.created_by,
    ds_cost_basic_finished_ratio_fee.updated_at,
    ds_cost_basic_finished_ratio_fee.updated_by,
    true AS is_current
   FROM public.ds_cost_basic_finished_ratio_fee
UNION ALL
 SELECT ds_cost_basic_finished_ratio_fee_history.id,
    ds_cost_basic_finished_ratio_fee_history.production_no,
    ds_cost_basic_finished_ratio_fee_history.item_seq,
    ds_cost_basic_finished_ratio_fee_history.element_name,
    ds_cost_basic_finished_ratio_fee_history.ratio_pct,
    ds_cost_basic_finished_ratio_fee_history.version_no,
    ds_cost_basic_finished_ratio_fee_history.row_fingerprint,
    ds_cost_basic_finished_ratio_fee_history.source,
    ds_cost_basic_finished_ratio_fee_history.created_at,
    ds_cost_basic_finished_ratio_fee_history.created_by,
    ds_cost_basic_finished_ratio_fee_history.updated_at,
    ds_cost_basic_finished_ratio_fee_history.updated_by,
    false AS is_current
   FROM public.ds_cost_basic_finished_ratio_fee_history;


--
-- Name: v_ds_cost_basic_incoming_other_fee_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_basic_incoming_other_fee_all AS
 SELECT ds_cost_basic_incoming_other_fee.id,
    ds_cost_basic_incoming_other_fee.production_no,
    ds_cost_basic_incoming_other_fee.item_seq,
    ds_cost_basic_incoming_other_fee.incoming_material_no,
    ds_cost_basic_incoming_other_fee.element_item_seq,
    ds_cost_basic_incoming_other_fee.element_name,
    ds_cost_basic_incoming_other_fee.ratio_pct,
    ds_cost_basic_incoming_other_fee.fee,
    ds_cost_basic_incoming_other_fee.version_no,
    ds_cost_basic_incoming_other_fee.row_fingerprint,
    ds_cost_basic_incoming_other_fee.source,
    ds_cost_basic_incoming_other_fee.created_at,
    ds_cost_basic_incoming_other_fee.created_by,
    ds_cost_basic_incoming_other_fee.updated_at,
    ds_cost_basic_incoming_other_fee.updated_by,
    true AS is_current
   FROM public.ds_cost_basic_incoming_other_fee
UNION ALL
 SELECT ds_cost_basic_incoming_other_fee_history.id,
    ds_cost_basic_incoming_other_fee_history.production_no,
    ds_cost_basic_incoming_other_fee_history.item_seq,
    ds_cost_basic_incoming_other_fee_history.incoming_material_no,
    ds_cost_basic_incoming_other_fee_history.element_item_seq,
    ds_cost_basic_incoming_other_fee_history.element_name,
    ds_cost_basic_incoming_other_fee_history.ratio_pct,
    ds_cost_basic_incoming_other_fee_history.fee,
    ds_cost_basic_incoming_other_fee_history.version_no,
    ds_cost_basic_incoming_other_fee_history.row_fingerprint,
    ds_cost_basic_incoming_other_fee_history.source,
    ds_cost_basic_incoming_other_fee_history.created_at,
    ds_cost_basic_incoming_other_fee_history.created_by,
    ds_cost_basic_incoming_other_fee_history.updated_at,
    ds_cost_basic_incoming_other_fee_history.updated_by,
    false AS is_current
   FROM public.ds_cost_basic_incoming_other_fee_history;


--
-- Name: v_ds_cost_basic_incoming_other_fixed_fee_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_basic_incoming_other_fixed_fee_all AS
 SELECT ds_cost_basic_incoming_other_fixed_fee.id,
    ds_cost_basic_incoming_other_fixed_fee.production_no,
    ds_cost_basic_incoming_other_fixed_fee.item_seq,
    ds_cost_basic_incoming_other_fixed_fee.incoming_material_no,
    ds_cost_basic_incoming_other_fixed_fee.element_item_seq,
    ds_cost_basic_incoming_other_fixed_fee.element_name,
    ds_cost_basic_incoming_other_fixed_fee.fee,
    ds_cost_basic_incoming_other_fixed_fee.currency,
    ds_cost_basic_incoming_other_fixed_fee.pricing_unit,
    ds_cost_basic_incoming_other_fixed_fee.version_no,
    ds_cost_basic_incoming_other_fixed_fee.row_fingerprint,
    ds_cost_basic_incoming_other_fixed_fee.source,
    ds_cost_basic_incoming_other_fixed_fee.created_at,
    ds_cost_basic_incoming_other_fixed_fee.created_by,
    ds_cost_basic_incoming_other_fixed_fee.updated_at,
    ds_cost_basic_incoming_other_fixed_fee.updated_by,
    true AS is_current
   FROM public.ds_cost_basic_incoming_other_fixed_fee
UNION ALL
 SELECT ds_cost_basic_incoming_other_fixed_fee_history.id,
    ds_cost_basic_incoming_other_fixed_fee_history.production_no,
    ds_cost_basic_incoming_other_fixed_fee_history.item_seq,
    ds_cost_basic_incoming_other_fixed_fee_history.incoming_material_no,
    ds_cost_basic_incoming_other_fixed_fee_history.element_item_seq,
    ds_cost_basic_incoming_other_fixed_fee_history.element_name,
    ds_cost_basic_incoming_other_fixed_fee_history.fee,
    ds_cost_basic_incoming_other_fixed_fee_history.currency,
    ds_cost_basic_incoming_other_fixed_fee_history.pricing_unit,
    ds_cost_basic_incoming_other_fixed_fee_history.version_no,
    ds_cost_basic_incoming_other_fixed_fee_history.row_fingerprint,
    ds_cost_basic_incoming_other_fixed_fee_history.source,
    ds_cost_basic_incoming_other_fixed_fee_history.created_at,
    ds_cost_basic_incoming_other_fixed_fee_history.created_by,
    ds_cost_basic_incoming_other_fixed_fee_history.updated_at,
    ds_cost_basic_incoming_other_fixed_fee_history.updated_by,
    false AS is_current
   FROM public.ds_cost_basic_incoming_other_fixed_fee_history;


--
-- Name: v_ds_cost_basic_incoming_process_fee_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_basic_incoming_process_fee_all AS
 SELECT ds_cost_basic_incoming_process_fee.id,
    ds_cost_basic_incoming_process_fee.production_no,
    ds_cost_basic_incoming_process_fee.item_seq,
    ds_cost_basic_incoming_process_fee.incoming_material_no,
    ds_cost_basic_incoming_process_fee.process_fee,
    ds_cost_basic_incoming_process_fee.currency,
    ds_cost_basic_incoming_process_fee.unit,
    ds_cost_basic_incoming_process_fee.loss_rate,
    ds_cost_basic_incoming_process_fee.version_no,
    ds_cost_basic_incoming_process_fee.row_fingerprint,
    ds_cost_basic_incoming_process_fee.source,
    ds_cost_basic_incoming_process_fee.created_at,
    ds_cost_basic_incoming_process_fee.created_by,
    ds_cost_basic_incoming_process_fee.updated_at,
    ds_cost_basic_incoming_process_fee.updated_by,
    true AS is_current
   FROM public.ds_cost_basic_incoming_process_fee
UNION ALL
 SELECT ds_cost_basic_incoming_process_fee_history.id,
    ds_cost_basic_incoming_process_fee_history.production_no,
    ds_cost_basic_incoming_process_fee_history.item_seq,
    ds_cost_basic_incoming_process_fee_history.incoming_material_no,
    ds_cost_basic_incoming_process_fee_history.process_fee,
    ds_cost_basic_incoming_process_fee_history.currency,
    ds_cost_basic_incoming_process_fee_history.unit,
    ds_cost_basic_incoming_process_fee_history.loss_rate,
    ds_cost_basic_incoming_process_fee_history.version_no,
    ds_cost_basic_incoming_process_fee_history.row_fingerprint,
    ds_cost_basic_incoming_process_fee_history.source,
    ds_cost_basic_incoming_process_fee_history.created_at,
    ds_cost_basic_incoming_process_fee_history.created_by,
    ds_cost_basic_incoming_process_fee_history.updated_at,
    ds_cost_basic_incoming_process_fee_history.updated_by,
    false AS is_current
   FROM public.ds_cost_basic_incoming_process_fee_history;


--
-- Name: v_ds_cost_basic_material_bom_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_basic_material_bom_all AS
 SELECT ds_cost_basic_material_bom.id,
    ds_cost_basic_material_bom.production_no,
    ds_cost_basic_material_bom.item_seq,
    ds_cost_basic_material_bom.component_no,
    ds_cost_basic_material_bom.operation_no,
    ds_cost_basic_material_bom.usage_characteristic,
    ds_cost_basic_material_bom.component_qty,
    ds_cost_basic_material_bom.component_qty_unit,
    ds_cost_basic_material_bom.base_qty,
    ds_cost_basic_material_bom.base_qty_unit,
    ds_cost_basic_material_bom.material_loss_rate,
    ds_cost_basic_material_bom.material_fixed_loss,
    ds_cost_basic_material_bom.defect_rate,
    ds_cost_basic_material_bom.version_no,
    ds_cost_basic_material_bom.row_fingerprint,
    ds_cost_basic_material_bom.source,
    ds_cost_basic_material_bom.created_at,
    ds_cost_basic_material_bom.created_by,
    ds_cost_basic_material_bom.updated_at,
    ds_cost_basic_material_bom.updated_by,
    true AS is_current
   FROM public.ds_cost_basic_material_bom
UNION ALL
 SELECT ds_cost_basic_material_bom_history.id,
    ds_cost_basic_material_bom_history.production_no,
    ds_cost_basic_material_bom_history.item_seq,
    ds_cost_basic_material_bom_history.component_no,
    ds_cost_basic_material_bom_history.operation_no,
    ds_cost_basic_material_bom_history.usage_characteristic,
    ds_cost_basic_material_bom_history.component_qty,
    ds_cost_basic_material_bom_history.component_qty_unit,
    ds_cost_basic_material_bom_history.base_qty,
    ds_cost_basic_material_bom_history.base_qty_unit,
    ds_cost_basic_material_bom_history.material_loss_rate,
    ds_cost_basic_material_bom_history.material_fixed_loss,
    ds_cost_basic_material_bom_history.defect_rate,
    ds_cost_basic_material_bom_history.version_no,
    ds_cost_basic_material_bom_history.row_fingerprint,
    ds_cost_basic_material_bom_history.source,
    ds_cost_basic_material_bom_history.created_at,
    ds_cost_basic_material_bom_history.created_by,
    ds_cost_basic_material_bom_history.updated_at,
    ds_cost_basic_material_bom_history.updated_by,
    false AS is_current
   FROM public.ds_cost_basic_material_bom_history;


--
-- Name: v_ds_cost_basic_outsourced_process_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_basic_outsourced_process_all AS
 SELECT ds_cost_basic_outsourced_process.id,
    ds_cost_basic_outsourced_process.production_no,
    ds_cost_basic_outsourced_process.operation_no,
    ds_cost_basic_outsourced_process.outsourced_fee,
    ds_cost_basic_outsourced_process.currency,
    ds_cost_basic_outsourced_process.unit,
    ds_cost_basic_outsourced_process.version_no,
    ds_cost_basic_outsourced_process.row_fingerprint,
    ds_cost_basic_outsourced_process.source,
    ds_cost_basic_outsourced_process.created_at,
    ds_cost_basic_outsourced_process.created_by,
    ds_cost_basic_outsourced_process.updated_at,
    ds_cost_basic_outsourced_process.updated_by,
    true AS is_current
   FROM public.ds_cost_basic_outsourced_process
UNION ALL
 SELECT ds_cost_basic_outsourced_process_history.id,
    ds_cost_basic_outsourced_process_history.production_no,
    ds_cost_basic_outsourced_process_history.operation_no,
    ds_cost_basic_outsourced_process_history.outsourced_fee,
    ds_cost_basic_outsourced_process_history.currency,
    ds_cost_basic_outsourced_process_history.unit,
    ds_cost_basic_outsourced_process_history.version_no,
    ds_cost_basic_outsourced_process_history.row_fingerprint,
    ds_cost_basic_outsourced_process_history.source,
    ds_cost_basic_outsourced_process_history.created_at,
    ds_cost_basic_outsourced_process_history.created_by,
    ds_cost_basic_outsourced_process_history.updated_at,
    ds_cost_basic_outsourced_process_history.updated_by,
    false AS is_current
   FROM public.ds_cost_basic_outsourced_process_history;


--
-- Name: v_ds_cost_basic_process_assembly_fee_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_basic_process_assembly_fee_all AS
 SELECT ds_cost_basic_process_assembly_fee.id,
    ds_cost_basic_process_assembly_fee.production_no,
    ds_cost_basic_process_assembly_fee.operation_no,
    ds_cost_basic_process_assembly_fee.process_fee,
    ds_cost_basic_process_assembly_fee.currency,
    ds_cost_basic_process_assembly_fee.unit,
    ds_cost_basic_process_assembly_fee.defect_rate,
    ds_cost_basic_process_assembly_fee.version_no,
    ds_cost_basic_process_assembly_fee.row_fingerprint,
    ds_cost_basic_process_assembly_fee.source,
    ds_cost_basic_process_assembly_fee.created_at,
    ds_cost_basic_process_assembly_fee.created_by,
    ds_cost_basic_process_assembly_fee.updated_at,
    ds_cost_basic_process_assembly_fee.updated_by,
    true AS is_current
   FROM public.ds_cost_basic_process_assembly_fee
UNION ALL
 SELECT ds_cost_basic_process_assembly_fee_history.id,
    ds_cost_basic_process_assembly_fee_history.production_no,
    ds_cost_basic_process_assembly_fee_history.operation_no,
    ds_cost_basic_process_assembly_fee_history.process_fee,
    ds_cost_basic_process_assembly_fee_history.currency,
    ds_cost_basic_process_assembly_fee_history.unit,
    ds_cost_basic_process_assembly_fee_history.defect_rate,
    ds_cost_basic_process_assembly_fee_history.version_no,
    ds_cost_basic_process_assembly_fee_history.row_fingerprint,
    ds_cost_basic_process_assembly_fee_history.source,
    ds_cost_basic_process_assembly_fee_history.created_at,
    ds_cost_basic_process_assembly_fee_history.created_by,
    ds_cost_basic_process_assembly_fee_history.updated_at,
    ds_cost_basic_process_assembly_fee_history.updated_by,
    false AS is_current
   FROM public.ds_cost_basic_process_assembly_fee_history;


--
-- Name: v_ds_cost_detail_auxiliary_energy_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_auxiliary_energy_all AS
 SELECT ds_cost_detail_auxiliary_energy.id,
    ds_cost_detail_auxiliary_energy.production_no,
    ds_cost_detail_auxiliary_energy.operation_no,
    ds_cost_detail_auxiliary_energy.auxiliary_energy_price,
    ds_cost_detail_auxiliary_energy.currency,
    ds_cost_detail_auxiliary_energy.unit,
    ds_cost_detail_auxiliary_energy.version_no,
    ds_cost_detail_auxiliary_energy.row_fingerprint,
    ds_cost_detail_auxiliary_energy.source,
    ds_cost_detail_auxiliary_energy.created_at,
    ds_cost_detail_auxiliary_energy.created_by,
    ds_cost_detail_auxiliary_energy.updated_at,
    ds_cost_detail_auxiliary_energy.updated_by,
    true AS is_current
   FROM public.ds_cost_detail_auxiliary_energy
UNION ALL
 SELECT ds_cost_detail_auxiliary_energy_history.id,
    ds_cost_detail_auxiliary_energy_history.production_no,
    ds_cost_detail_auxiliary_energy_history.operation_no,
    ds_cost_detail_auxiliary_energy_history.auxiliary_energy_price,
    ds_cost_detail_auxiliary_energy_history.currency,
    ds_cost_detail_auxiliary_energy_history.unit,
    ds_cost_detail_auxiliary_energy_history.version_no,
    ds_cost_detail_auxiliary_energy_history.row_fingerprint,
    ds_cost_detail_auxiliary_energy_history.source,
    ds_cost_detail_auxiliary_energy_history.created_at,
    ds_cost_detail_auxiliary_energy_history.created_by,
    ds_cost_detail_auxiliary_energy_history.updated_at,
    ds_cost_detail_auxiliary_energy_history.updated_by,
    false AS is_current
   FROM public.ds_cost_detail_auxiliary_energy_history;


--
-- Name: v_ds_cost_detail_capacity_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_capacity_all AS
 SELECT ds_cost_detail_capacity.id,
    ds_cost_detail_capacity.production_no,
    ds_cost_detail_capacity.operation_no,
    ds_cost_detail_capacity.labor_std_price,
    ds_cost_detail_capacity.currency,
    ds_cost_detail_capacity.unit,
    ds_cost_detail_capacity.version_no,
    ds_cost_detail_capacity.row_fingerprint,
    ds_cost_detail_capacity.source,
    ds_cost_detail_capacity.created_at,
    ds_cost_detail_capacity.created_by,
    ds_cost_detail_capacity.updated_at,
    ds_cost_detail_capacity.updated_by,
    true AS is_current
   FROM public.ds_cost_detail_capacity
UNION ALL
 SELECT ds_cost_detail_capacity_history.id,
    ds_cost_detail_capacity_history.production_no,
    ds_cost_detail_capacity_history.operation_no,
    ds_cost_detail_capacity_history.labor_std_price,
    ds_cost_detail_capacity_history.currency,
    ds_cost_detail_capacity_history.unit,
    ds_cost_detail_capacity_history.version_no,
    ds_cost_detail_capacity_history.row_fingerprint,
    ds_cost_detail_capacity_history.source,
    ds_cost_detail_capacity_history.created_at,
    ds_cost_detail_capacity_history.created_by,
    ds_cost_detail_capacity_history.updated_at,
    ds_cost_detail_capacity_history.updated_by,
    false AS is_current
   FROM public.ds_cost_detail_capacity_history;


--
-- Name: v_ds_cost_detail_consumable_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_consumable_all AS
 SELECT ds_cost_detail_consumable.id,
    ds_cost_detail_consumable.production_no,
    ds_cost_detail_consumable.operation_no,
    ds_cost_detail_consumable.consumable_price,
    ds_cost_detail_consumable.currency,
    ds_cost_detail_consumable.unit,
    ds_cost_detail_consumable.version_no,
    ds_cost_detail_consumable.row_fingerprint,
    ds_cost_detail_consumable.source,
    ds_cost_detail_consumable.created_at,
    ds_cost_detail_consumable.created_by,
    ds_cost_detail_consumable.updated_at,
    ds_cost_detail_consumable.updated_by,
    true AS is_current
   FROM public.ds_cost_detail_consumable
UNION ALL
 SELECT ds_cost_detail_consumable_history.id,
    ds_cost_detail_consumable_history.production_no,
    ds_cost_detail_consumable_history.operation_no,
    ds_cost_detail_consumable_history.consumable_price,
    ds_cost_detail_consumable_history.currency,
    ds_cost_detail_consumable_history.unit,
    ds_cost_detail_consumable_history.version_no,
    ds_cost_detail_consumable_history.row_fingerprint,
    ds_cost_detail_consumable_history.source,
    ds_cost_detail_consumable_history.created_at,
    ds_cost_detail_consumable_history.created_by,
    ds_cost_detail_consumable_history.updated_at,
    ds_cost_detail_consumable_history.updated_by,
    false AS is_current
   FROM public.ds_cost_detail_consumable_history;


--
-- Name: v_ds_cost_detail_depreciation_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_depreciation_all AS
 SELECT ds_cost_detail_depreciation.id,
    ds_cost_detail_depreciation.production_no,
    ds_cost_detail_depreciation.operation_no,
    ds_cost_detail_depreciation.depreciation_price,
    ds_cost_detail_depreciation.currency,
    ds_cost_detail_depreciation.unit,
    ds_cost_detail_depreciation.version_no,
    ds_cost_detail_depreciation.row_fingerprint,
    ds_cost_detail_depreciation.source,
    ds_cost_detail_depreciation.created_at,
    ds_cost_detail_depreciation.created_by,
    ds_cost_detail_depreciation.updated_at,
    ds_cost_detail_depreciation.updated_by,
    true AS is_current
   FROM public.ds_cost_detail_depreciation
UNION ALL
 SELECT ds_cost_detail_depreciation_history.id,
    ds_cost_detail_depreciation_history.production_no,
    ds_cost_detail_depreciation_history.operation_no,
    ds_cost_detail_depreciation_history.depreciation_price,
    ds_cost_detail_depreciation_history.currency,
    ds_cost_detail_depreciation_history.unit,
    ds_cost_detail_depreciation_history.version_no,
    ds_cost_detail_depreciation_history.row_fingerprint,
    ds_cost_detail_depreciation_history.source,
    ds_cost_detail_depreciation_history.created_at,
    ds_cost_detail_depreciation_history.created_by,
    ds_cost_detail_depreciation_history.updated_at,
    ds_cost_detail_depreciation_history.updated_by,
    false AS is_current
   FROM public.ds_cost_detail_depreciation_history;


--
-- Name: v_ds_cost_detail_element_bom_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_element_bom_all AS
 SELECT e.id,
    e.production_no,
    e.material_part_no,
    e.item_seq,
    e.element_code,
    e.content_pct,
    e.loss_rate,
    e.version_no,
    e.row_fingerprint,
    e.source,
    e.created_at,
    e.created_by,
    e.updated_at,
    e.updated_by,
    true AS is_current,
    mm.material_no AS sales_material_no
   FROM (public.ds_cost_detail_element_bom e
     LEFT JOIN LATERAL ( SELECT (m.material_no)::character varying(20) AS material_no
           FROM public.ds_quote_material m
          WHERE ((m.production_no)::text = (e.production_no)::text)
          ORDER BY m.material_no
         LIMIT 1) mm ON (true))
UNION ALL
 SELECT h.id,
    h.production_no,
    h.material_part_no,
    h.item_seq,
    h.element_code,
    h.content_pct,
    h.loss_rate,
    h.version_no,
    h.row_fingerprint,
    h.source,
    h.created_at,
    h.created_by,
    h.updated_at,
    h.updated_by,
    false AS is_current,
    mm.material_no AS sales_material_no
   FROM (public.ds_cost_detail_element_bom_history h
     LEFT JOIN LATERAL ( SELECT (m.material_no)::character varying(20) AS material_no
           FROM public.ds_quote_material m
          WHERE ((m.production_no)::text = (h.production_no)::text)
          ORDER BY m.material_no
         LIMIT 1) mm ON (true));


--
-- Name: v_ds_cost_detail_finished_fixed_fee_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_finished_fixed_fee_all AS
 SELECT ds_cost_detail_finished_fixed_fee.id,
    ds_cost_detail_finished_fixed_fee.production_no,
    ds_cost_detail_finished_fixed_fee.item_seq,
    ds_cost_detail_finished_fixed_fee.element_name,
    ds_cost_detail_finished_fixed_fee.fee,
    ds_cost_detail_finished_fixed_fee.currency,
    ds_cost_detail_finished_fixed_fee.pricing_unit,
    ds_cost_detail_finished_fixed_fee.version_no,
    ds_cost_detail_finished_fixed_fee.row_fingerprint,
    ds_cost_detail_finished_fixed_fee.source,
    ds_cost_detail_finished_fixed_fee.created_at,
    ds_cost_detail_finished_fixed_fee.created_by,
    ds_cost_detail_finished_fixed_fee.updated_at,
    ds_cost_detail_finished_fixed_fee.updated_by,
    true AS is_current
   FROM public.ds_cost_detail_finished_fixed_fee
UNION ALL
 SELECT ds_cost_detail_finished_fixed_fee_history.id,
    ds_cost_detail_finished_fixed_fee_history.production_no,
    ds_cost_detail_finished_fixed_fee_history.item_seq,
    ds_cost_detail_finished_fixed_fee_history.element_name,
    ds_cost_detail_finished_fixed_fee_history.fee,
    ds_cost_detail_finished_fixed_fee_history.currency,
    ds_cost_detail_finished_fixed_fee_history.pricing_unit,
    ds_cost_detail_finished_fixed_fee_history.version_no,
    ds_cost_detail_finished_fixed_fee_history.row_fingerprint,
    ds_cost_detail_finished_fixed_fee_history.source,
    ds_cost_detail_finished_fixed_fee_history.created_at,
    ds_cost_detail_finished_fixed_fee_history.created_by,
    ds_cost_detail_finished_fixed_fee_history.updated_at,
    ds_cost_detail_finished_fixed_fee_history.updated_by,
    false AS is_current
   FROM public.ds_cost_detail_finished_fixed_fee_history;


--
-- Name: v_ds_cost_detail_finished_ratio_fee_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_finished_ratio_fee_all AS
 SELECT ds_cost_detail_finished_ratio_fee.id,
    ds_cost_detail_finished_ratio_fee.production_no,
    ds_cost_detail_finished_ratio_fee.item_seq,
    ds_cost_detail_finished_ratio_fee.element_name,
    ds_cost_detail_finished_ratio_fee.ratio_pct,
    ds_cost_detail_finished_ratio_fee.version_no,
    ds_cost_detail_finished_ratio_fee.row_fingerprint,
    ds_cost_detail_finished_ratio_fee.source,
    ds_cost_detail_finished_ratio_fee.created_at,
    ds_cost_detail_finished_ratio_fee.created_by,
    ds_cost_detail_finished_ratio_fee.updated_at,
    ds_cost_detail_finished_ratio_fee.updated_by,
    true AS is_current
   FROM public.ds_cost_detail_finished_ratio_fee
UNION ALL
 SELECT ds_cost_detail_finished_ratio_fee_history.id,
    ds_cost_detail_finished_ratio_fee_history.production_no,
    ds_cost_detail_finished_ratio_fee_history.item_seq,
    ds_cost_detail_finished_ratio_fee_history.element_name,
    ds_cost_detail_finished_ratio_fee_history.ratio_pct,
    ds_cost_detail_finished_ratio_fee_history.version_no,
    ds_cost_detail_finished_ratio_fee_history.row_fingerprint,
    ds_cost_detail_finished_ratio_fee_history.source,
    ds_cost_detail_finished_ratio_fee_history.created_at,
    ds_cost_detail_finished_ratio_fee_history.created_by,
    ds_cost_detail_finished_ratio_fee_history.updated_at,
    ds_cost_detail_finished_ratio_fee_history.updated_by,
    false AS is_current
   FROM public.ds_cost_detail_finished_ratio_fee_history;


--
-- Name: v_ds_cost_detail_incoming_other_fee_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_incoming_other_fee_all AS
 SELECT ds_cost_detail_incoming_other_fee.id,
    ds_cost_detail_incoming_other_fee.production_no,
    ds_cost_detail_incoming_other_fee.item_seq,
    ds_cost_detail_incoming_other_fee.incoming_material_no,
    ds_cost_detail_incoming_other_fee.element_item_seq,
    ds_cost_detail_incoming_other_fee.element_name,
    ds_cost_detail_incoming_other_fee.ratio_pct,
    ds_cost_detail_incoming_other_fee.fee,
    ds_cost_detail_incoming_other_fee.version_no,
    ds_cost_detail_incoming_other_fee.row_fingerprint,
    ds_cost_detail_incoming_other_fee.source,
    ds_cost_detail_incoming_other_fee.created_at,
    ds_cost_detail_incoming_other_fee.created_by,
    ds_cost_detail_incoming_other_fee.updated_at,
    ds_cost_detail_incoming_other_fee.updated_by,
    true AS is_current
   FROM public.ds_cost_detail_incoming_other_fee
UNION ALL
 SELECT ds_cost_detail_incoming_other_fee_history.id,
    ds_cost_detail_incoming_other_fee_history.production_no,
    ds_cost_detail_incoming_other_fee_history.item_seq,
    ds_cost_detail_incoming_other_fee_history.incoming_material_no,
    ds_cost_detail_incoming_other_fee_history.element_item_seq,
    ds_cost_detail_incoming_other_fee_history.element_name,
    ds_cost_detail_incoming_other_fee_history.ratio_pct,
    ds_cost_detail_incoming_other_fee_history.fee,
    ds_cost_detail_incoming_other_fee_history.version_no,
    ds_cost_detail_incoming_other_fee_history.row_fingerprint,
    ds_cost_detail_incoming_other_fee_history.source,
    ds_cost_detail_incoming_other_fee_history.created_at,
    ds_cost_detail_incoming_other_fee_history.created_by,
    ds_cost_detail_incoming_other_fee_history.updated_at,
    ds_cost_detail_incoming_other_fee_history.updated_by,
    false AS is_current
   FROM public.ds_cost_detail_incoming_other_fee_history;


--
-- Name: v_ds_cost_detail_incoming_other_fixed_fee_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_incoming_other_fixed_fee_all AS
 SELECT ds_cost_detail_incoming_other_fixed_fee.id,
    ds_cost_detail_incoming_other_fixed_fee.production_no,
    ds_cost_detail_incoming_other_fixed_fee.item_seq,
    ds_cost_detail_incoming_other_fixed_fee.incoming_material_no,
    ds_cost_detail_incoming_other_fixed_fee.element_item_seq,
    ds_cost_detail_incoming_other_fixed_fee.element_name,
    ds_cost_detail_incoming_other_fixed_fee.fee,
    ds_cost_detail_incoming_other_fixed_fee.currency,
    ds_cost_detail_incoming_other_fixed_fee.pricing_unit,
    ds_cost_detail_incoming_other_fixed_fee.version_no,
    ds_cost_detail_incoming_other_fixed_fee.row_fingerprint,
    ds_cost_detail_incoming_other_fixed_fee.source,
    ds_cost_detail_incoming_other_fixed_fee.created_at,
    ds_cost_detail_incoming_other_fixed_fee.created_by,
    ds_cost_detail_incoming_other_fixed_fee.updated_at,
    ds_cost_detail_incoming_other_fixed_fee.updated_by,
    true AS is_current
   FROM public.ds_cost_detail_incoming_other_fixed_fee
UNION ALL
 SELECT ds_cost_detail_incoming_other_fixed_fee_history.id,
    ds_cost_detail_incoming_other_fixed_fee_history.production_no,
    ds_cost_detail_incoming_other_fixed_fee_history.item_seq,
    ds_cost_detail_incoming_other_fixed_fee_history.incoming_material_no,
    ds_cost_detail_incoming_other_fixed_fee_history.element_item_seq,
    ds_cost_detail_incoming_other_fixed_fee_history.element_name,
    ds_cost_detail_incoming_other_fixed_fee_history.fee,
    ds_cost_detail_incoming_other_fixed_fee_history.currency,
    ds_cost_detail_incoming_other_fixed_fee_history.pricing_unit,
    ds_cost_detail_incoming_other_fixed_fee_history.version_no,
    ds_cost_detail_incoming_other_fixed_fee_history.row_fingerprint,
    ds_cost_detail_incoming_other_fixed_fee_history.source,
    ds_cost_detail_incoming_other_fixed_fee_history.created_at,
    ds_cost_detail_incoming_other_fixed_fee_history.created_by,
    ds_cost_detail_incoming_other_fixed_fee_history.updated_at,
    ds_cost_detail_incoming_other_fixed_fee_history.updated_by,
    false AS is_current
   FROM public.ds_cost_detail_incoming_other_fixed_fee_history;


--
-- Name: v_ds_cost_detail_incoming_process_fee_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_incoming_process_fee_all AS
 SELECT ds_cost_detail_incoming_process_fee.id,
    ds_cost_detail_incoming_process_fee.production_no,
    ds_cost_detail_incoming_process_fee.item_seq,
    ds_cost_detail_incoming_process_fee.incoming_material_no,
    ds_cost_detail_incoming_process_fee.process_fee,
    ds_cost_detail_incoming_process_fee.currency,
    ds_cost_detail_incoming_process_fee.unit,
    ds_cost_detail_incoming_process_fee.loss_rate,
    ds_cost_detail_incoming_process_fee.version_no,
    ds_cost_detail_incoming_process_fee.row_fingerprint,
    ds_cost_detail_incoming_process_fee.source,
    ds_cost_detail_incoming_process_fee.created_at,
    ds_cost_detail_incoming_process_fee.created_by,
    ds_cost_detail_incoming_process_fee.updated_at,
    ds_cost_detail_incoming_process_fee.updated_by,
    true AS is_current
   FROM public.ds_cost_detail_incoming_process_fee
UNION ALL
 SELECT ds_cost_detail_incoming_process_fee_history.id,
    ds_cost_detail_incoming_process_fee_history.production_no,
    ds_cost_detail_incoming_process_fee_history.item_seq,
    ds_cost_detail_incoming_process_fee_history.incoming_material_no,
    ds_cost_detail_incoming_process_fee_history.process_fee,
    ds_cost_detail_incoming_process_fee_history.currency,
    ds_cost_detail_incoming_process_fee_history.unit,
    ds_cost_detail_incoming_process_fee_history.loss_rate,
    ds_cost_detail_incoming_process_fee_history.version_no,
    ds_cost_detail_incoming_process_fee_history.row_fingerprint,
    ds_cost_detail_incoming_process_fee_history.source,
    ds_cost_detail_incoming_process_fee_history.created_at,
    ds_cost_detail_incoming_process_fee_history.created_by,
    ds_cost_detail_incoming_process_fee_history.updated_at,
    ds_cost_detail_incoming_process_fee_history.updated_by,
    false AS is_current
   FROM public.ds_cost_detail_incoming_process_fee_history;


--
-- Name: v_ds_cost_detail_material_bom_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_material_bom_all AS
 SELECT ds_cost_detail_material_bom.id,
    ds_cost_detail_material_bom.production_no,
    ds_cost_detail_material_bom.item_seq,
    ds_cost_detail_material_bom.component_no,
    ds_cost_detail_material_bom.operation_no,
    ds_cost_detail_material_bom.usage_characteristic,
    ds_cost_detail_material_bom.component_qty,
    ds_cost_detail_material_bom.component_qty_unit,
    ds_cost_detail_material_bom.base_qty,
    ds_cost_detail_material_bom.base_qty_unit,
    ds_cost_detail_material_bom.material_loss_rate,
    ds_cost_detail_material_bom.material_fixed_loss,
    ds_cost_detail_material_bom.defect_rate,
    ds_cost_detail_material_bom.version_no,
    ds_cost_detail_material_bom.row_fingerprint,
    ds_cost_detail_material_bom.source,
    ds_cost_detail_material_bom.created_at,
    ds_cost_detail_material_bom.created_by,
    ds_cost_detail_material_bom.updated_at,
    ds_cost_detail_material_bom.updated_by,
    true AS is_current
   FROM public.ds_cost_detail_material_bom
UNION ALL
 SELECT ds_cost_detail_material_bom_history.id,
    ds_cost_detail_material_bom_history.production_no,
    ds_cost_detail_material_bom_history.item_seq,
    ds_cost_detail_material_bom_history.component_no,
    ds_cost_detail_material_bom_history.operation_no,
    ds_cost_detail_material_bom_history.usage_characteristic,
    ds_cost_detail_material_bom_history.component_qty,
    ds_cost_detail_material_bom_history.component_qty_unit,
    ds_cost_detail_material_bom_history.base_qty,
    ds_cost_detail_material_bom_history.base_qty_unit,
    ds_cost_detail_material_bom_history.material_loss_rate,
    ds_cost_detail_material_bom_history.material_fixed_loss,
    ds_cost_detail_material_bom_history.defect_rate,
    ds_cost_detail_material_bom_history.version_no,
    ds_cost_detail_material_bom_history.row_fingerprint,
    ds_cost_detail_material_bom_history.source,
    ds_cost_detail_material_bom_history.created_at,
    ds_cost_detail_material_bom_history.created_by,
    ds_cost_detail_material_bom_history.updated_at,
    ds_cost_detail_material_bom_history.updated_by,
    false AS is_current
   FROM public.ds_cost_detail_material_bom_history;


--
-- Name: v_ds_cost_detail_outsourced_process_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_outsourced_process_all AS
 SELECT ds_cost_detail_outsourced_process.id,
    ds_cost_detail_outsourced_process.production_no,
    ds_cost_detail_outsourced_process.operation_no,
    ds_cost_detail_outsourced_process.outsourced_fee,
    ds_cost_detail_outsourced_process.currency,
    ds_cost_detail_outsourced_process.unit,
    ds_cost_detail_outsourced_process.version_no,
    ds_cost_detail_outsourced_process.row_fingerprint,
    ds_cost_detail_outsourced_process.source,
    ds_cost_detail_outsourced_process.created_at,
    ds_cost_detail_outsourced_process.created_by,
    ds_cost_detail_outsourced_process.updated_at,
    ds_cost_detail_outsourced_process.updated_by,
    true AS is_current
   FROM public.ds_cost_detail_outsourced_process
UNION ALL
 SELECT ds_cost_detail_outsourced_process_history.id,
    ds_cost_detail_outsourced_process_history.production_no,
    ds_cost_detail_outsourced_process_history.operation_no,
    ds_cost_detail_outsourced_process_history.outsourced_fee,
    ds_cost_detail_outsourced_process_history.currency,
    ds_cost_detail_outsourced_process_history.unit,
    ds_cost_detail_outsourced_process_history.version_no,
    ds_cost_detail_outsourced_process_history.row_fingerprint,
    ds_cost_detail_outsourced_process_history.source,
    ds_cost_detail_outsourced_process_history.created_at,
    ds_cost_detail_outsourced_process_history.created_by,
    ds_cost_detail_outsourced_process_history.updated_at,
    ds_cost_detail_outsourced_process_history.updated_by,
    false AS is_current
   FROM public.ds_cost_detail_outsourced_process_history;


--
-- Name: v_ds_cost_detail_packaging_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_packaging_all AS
 SELECT ds_cost_detail_packaging.id,
    ds_cost_detail_packaging.production_no,
    ds_cost_detail_packaging.operation_no,
    ds_cost_detail_packaging.packaging_price,
    ds_cost_detail_packaging.currency,
    ds_cost_detail_packaging.unit,
    ds_cost_detail_packaging.version_no,
    ds_cost_detail_packaging.row_fingerprint,
    ds_cost_detail_packaging.source,
    ds_cost_detail_packaging.created_at,
    ds_cost_detail_packaging.created_by,
    ds_cost_detail_packaging.updated_at,
    ds_cost_detail_packaging.updated_by,
    true AS is_current
   FROM public.ds_cost_detail_packaging
UNION ALL
 SELECT ds_cost_detail_packaging_history.id,
    ds_cost_detail_packaging_history.production_no,
    ds_cost_detail_packaging_history.operation_no,
    ds_cost_detail_packaging_history.packaging_price,
    ds_cost_detail_packaging_history.currency,
    ds_cost_detail_packaging_history.unit,
    ds_cost_detail_packaging_history.version_no,
    ds_cost_detail_packaging_history.row_fingerprint,
    ds_cost_detail_packaging_history.source,
    ds_cost_detail_packaging_history.created_at,
    ds_cost_detail_packaging_history.created_by,
    ds_cost_detail_packaging_history.updated_at,
    ds_cost_detail_packaging_history.updated_by,
    false AS is_current
   FROM public.ds_cost_detail_packaging_history;


--
-- Name: v_ds_cost_detail_plating_cost_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_plating_cost_all AS
 SELECT ds_cost_detail_plating_cost.id,
    ds_cost_detail_plating_cost.production_no,
    ds_cost_detail_plating_cost.plating_scheme_no,
    ds_cost_detail_plating_cost.plating_version,
    ds_cost_detail_plating_cost.plating_process_fee,
    ds_cost_detail_plating_cost.plating_material_fee,
    ds_cost_detail_plating_cost.currency,
    ds_cost_detail_plating_cost.pricing_unit,
    ds_cost_detail_plating_cost.defect_rate,
    ds_cost_detail_plating_cost.version_no,
    ds_cost_detail_plating_cost.row_fingerprint,
    ds_cost_detail_plating_cost.source,
    ds_cost_detail_plating_cost.created_at,
    ds_cost_detail_plating_cost.created_by,
    ds_cost_detail_plating_cost.updated_at,
    ds_cost_detail_plating_cost.updated_by,
    true AS is_current
   FROM public.ds_cost_detail_plating_cost
UNION ALL
 SELECT ds_cost_detail_plating_cost_history.id,
    ds_cost_detail_plating_cost_history.production_no,
    ds_cost_detail_plating_cost_history.plating_scheme_no,
    ds_cost_detail_plating_cost_history.plating_version,
    ds_cost_detail_plating_cost_history.plating_process_fee,
    ds_cost_detail_plating_cost_history.plating_material_fee,
    ds_cost_detail_plating_cost_history.currency,
    ds_cost_detail_plating_cost_history.pricing_unit,
    ds_cost_detail_plating_cost_history.defect_rate,
    ds_cost_detail_plating_cost_history.version_no,
    ds_cost_detail_plating_cost_history.row_fingerprint,
    ds_cost_detail_plating_cost_history.source,
    ds_cost_detail_plating_cost_history.created_at,
    ds_cost_detail_plating_cost_history.created_by,
    ds_cost_detail_plating_cost_history.updated_at,
    ds_cost_detail_plating_cost_history.updated_by,
    false AS is_current
   FROM public.ds_cost_detail_plating_cost_history;


--
-- Name: v_ds_cost_detail_process_assembly_fee_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_process_assembly_fee_all AS
 SELECT ds_cost_detail_process_assembly_fee.id,
    ds_cost_detail_process_assembly_fee.production_no,
    ds_cost_detail_process_assembly_fee.operation_no,
    ds_cost_detail_process_assembly_fee.process_fee,
    ds_cost_detail_process_assembly_fee.currency,
    ds_cost_detail_process_assembly_fee.unit,
    ds_cost_detail_process_assembly_fee.defect_rate,
    ds_cost_detail_process_assembly_fee.version_no,
    ds_cost_detail_process_assembly_fee.row_fingerprint,
    ds_cost_detail_process_assembly_fee.source,
    ds_cost_detail_process_assembly_fee.created_at,
    ds_cost_detail_process_assembly_fee.created_by,
    ds_cost_detail_process_assembly_fee.updated_at,
    ds_cost_detail_process_assembly_fee.updated_by,
    true AS is_current
   FROM public.ds_cost_detail_process_assembly_fee
UNION ALL
 SELECT ds_cost_detail_process_assembly_fee_history.id,
    ds_cost_detail_process_assembly_fee_history.production_no,
    ds_cost_detail_process_assembly_fee_history.operation_no,
    ds_cost_detail_process_assembly_fee_history.process_fee,
    ds_cost_detail_process_assembly_fee_history.currency,
    ds_cost_detail_process_assembly_fee_history.unit,
    ds_cost_detail_process_assembly_fee_history.defect_rate,
    ds_cost_detail_process_assembly_fee_history.version_no,
    ds_cost_detail_process_assembly_fee_history.row_fingerprint,
    ds_cost_detail_process_assembly_fee_history.source,
    ds_cost_detail_process_assembly_fee_history.created_at,
    ds_cost_detail_process_assembly_fee_history.created_by,
    ds_cost_detail_process_assembly_fee_history.updated_at,
    ds_cost_detail_process_assembly_fee_history.updated_by,
    false AS is_current
   FROM public.ds_cost_detail_process_assembly_fee_history;


--
-- Name: v_ds_cost_detail_production_energy_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_production_energy_all AS
 SELECT ds_cost_detail_production_energy.id,
    ds_cost_detail_production_energy.production_no,
    ds_cost_detail_production_energy.operation_no,
    ds_cost_detail_production_energy.production_energy_price,
    ds_cost_detail_production_energy.currency,
    ds_cost_detail_production_energy.unit,
    ds_cost_detail_production_energy.version_no,
    ds_cost_detail_production_energy.row_fingerprint,
    ds_cost_detail_production_energy.source,
    ds_cost_detail_production_energy.created_at,
    ds_cost_detail_production_energy.created_by,
    ds_cost_detail_production_energy.updated_at,
    ds_cost_detail_production_energy.updated_by,
    true AS is_current
   FROM public.ds_cost_detail_production_energy
UNION ALL
 SELECT ds_cost_detail_production_energy_history.id,
    ds_cost_detail_production_energy_history.production_no,
    ds_cost_detail_production_energy_history.operation_no,
    ds_cost_detail_production_energy_history.production_energy_price,
    ds_cost_detail_production_energy_history.currency,
    ds_cost_detail_production_energy_history.unit,
    ds_cost_detail_production_energy_history.version_no,
    ds_cost_detail_production_energy_history.row_fingerprint,
    ds_cost_detail_production_energy_history.source,
    ds_cost_detail_production_energy_history.created_at,
    ds_cost_detail_production_energy_history.created_by,
    ds_cost_detail_production_energy_history.updated_at,
    ds_cost_detail_production_energy_history.updated_by,
    false AS is_current
   FROM public.ds_cost_detail_production_energy_history;


--
-- Name: v_ds_cost_detail_tooling_all; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_ds_cost_detail_tooling_all AS
 SELECT ds_cost_detail_tooling.id,
    ds_cost_detail_tooling.production_no,
    ds_cost_detail_tooling.operation_no,
    ds_cost_detail_tooling.item_seq,
    ds_cost_detail_tooling.tooling_no,
    ds_cost_detail_tooling.tooling_cost,
    ds_cost_detail_tooling.tooling_life,
    ds_cost_detail_tooling.cycle_output,
    ds_cost_detail_tooling.tooling_unit_price,
    ds_cost_detail_tooling.currency,
    ds_cost_detail_tooling.unit,
    ds_cost_detail_tooling.version_no,
    ds_cost_detail_tooling.row_fingerprint,
    ds_cost_detail_tooling.source,
    ds_cost_detail_tooling.created_at,
    ds_cost_detail_tooling.created_by,
    ds_cost_detail_tooling.updated_at,
    ds_cost_detail_tooling.updated_by,
    true AS is_current
   FROM public.ds_cost_detail_tooling
UNION ALL
 SELECT ds_cost_detail_tooling_history.id,
    ds_cost_detail_tooling_history.production_no,
    ds_cost_detail_tooling_history.operation_no,
    ds_cost_detail_tooling_history.item_seq,
    ds_cost_detail_tooling_history.tooling_no,
    ds_cost_detail_tooling_history.tooling_cost,
    ds_cost_detail_tooling_history.tooling_life,
    ds_cost_detail_tooling_history.cycle_output,
    ds_cost_detail_tooling_history.tooling_unit_price,
    ds_cost_detail_tooling_history.currency,
    ds_cost_detail_tooling_history.unit,
    ds_cost_detail_tooling_history.version_no,
    ds_cost_detail_tooling_history.row_fingerprint,
    ds_cost_detail_tooling_history.source,
    ds_cost_detail_tooling_history.created_at,
    ds_cost_detail_tooling_history.created_by,
    ds_cost_detail_tooling_history.updated_at,
    ds_cost_detail_tooling_history.updated_by,
    false AS is_current
   FROM public.ds_cost_detail_tooling_history;


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
-- Name: ds_cost_basic_element_bom id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_element_bom ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_element_bom_id_seq'::regclass);


--
-- Name: ds_cost_basic_element_bom_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_element_bom_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_element_bom_history_id_seq'::regclass);


--
-- Name: ds_cost_basic_finished_fixed_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_finished_fixed_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_finished_fixed_fee_id_seq'::regclass);


--
-- Name: ds_cost_basic_finished_fixed_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_finished_fixed_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_finished_fixed_fee_history_id_seq'::regclass);


--
-- Name: ds_cost_basic_finished_ratio_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_finished_ratio_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_finished_ratio_fee_id_seq'::regclass);


--
-- Name: ds_cost_basic_finished_ratio_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_finished_ratio_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_finished_ratio_fee_history_id_seq'::regclass);


--
-- Name: ds_cost_basic_incoming_other_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_incoming_other_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_incoming_other_fee_id_seq'::regclass);


--
-- Name: ds_cost_basic_incoming_other_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_incoming_other_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_incoming_other_fee_history_id_seq'::regclass);


--
-- Name: ds_cost_basic_incoming_other_fixed_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_incoming_other_fixed_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_incoming_other_fixed_fee_id_seq'::regclass);


--
-- Name: ds_cost_basic_incoming_other_fixed_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_incoming_other_fixed_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_incoming_other_fixed_fee_history_id_seq'::regclass);


--
-- Name: ds_cost_basic_incoming_process_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_incoming_process_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_incoming_process_fee_id_seq'::regclass);


--
-- Name: ds_cost_basic_incoming_process_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_incoming_process_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_incoming_process_fee_history_id_seq'::regclass);


--
-- Name: ds_cost_basic_material id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_material ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_material_id_seq'::regclass);


--
-- Name: ds_cost_basic_material_bom id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_material_bom ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_material_bom_id_seq'::regclass);


--
-- Name: ds_cost_basic_material_bom_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_material_bom_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_material_bom_history_id_seq'::regclass);


--
-- Name: ds_cost_basic_outsourced_process id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_outsourced_process ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_outsourced_process_id_seq'::regclass);


--
-- Name: ds_cost_basic_outsourced_process_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_outsourced_process_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_outsourced_process_history_id_seq'::regclass);


--
-- Name: ds_cost_basic_process_assembly_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_process_assembly_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_process_assembly_fee_id_seq'::regclass);


--
-- Name: ds_cost_basic_process_assembly_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_process_assembly_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_basic_process_assembly_fee_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_auxiliary_energy id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_auxiliary_energy ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_auxiliary_energy_id_seq'::regclass);


--
-- Name: ds_cost_detail_auxiliary_energy_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_auxiliary_energy_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_auxiliary_energy_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_capacity id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_capacity ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_capacity_id_seq'::regclass);


--
-- Name: ds_cost_detail_capacity_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_capacity_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_capacity_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_consumable id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_consumable ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_consumable_id_seq'::regclass);


--
-- Name: ds_cost_detail_consumable_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_consumable_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_consumable_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_depreciation id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_depreciation ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_depreciation_id_seq'::regclass);


--
-- Name: ds_cost_detail_depreciation_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_depreciation_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_depreciation_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_element_bom id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_element_bom ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_element_bom_id_seq'::regclass);


--
-- Name: ds_cost_detail_element_bom_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_element_bom_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_element_bom_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_finished_fixed_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_finished_fixed_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_finished_fixed_fee_id_seq'::regclass);


--
-- Name: ds_cost_detail_finished_fixed_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_finished_fixed_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_finished_fixed_fee_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_finished_ratio_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_finished_ratio_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_finished_ratio_fee_id_seq'::regclass);


--
-- Name: ds_cost_detail_finished_ratio_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_finished_ratio_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_finished_ratio_fee_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_incoming_other_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_incoming_other_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_incoming_other_fee_id_seq'::regclass);


--
-- Name: ds_cost_detail_incoming_other_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_incoming_other_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_incoming_other_fee_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_incoming_other_fixed_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_incoming_other_fixed_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_incoming_other_fixed_fee_id_seq'::regclass);


--
-- Name: ds_cost_detail_incoming_other_fixed_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_incoming_other_fixed_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_incoming_other_fixed_fee_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_incoming_process_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_incoming_process_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_incoming_process_fee_id_seq'::regclass);


--
-- Name: ds_cost_detail_incoming_process_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_incoming_process_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_incoming_process_fee_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_material id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_material ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_material_id_seq'::regclass);


--
-- Name: ds_cost_detail_material_bom id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_material_bom ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_material_bom_id_seq'::regclass);


--
-- Name: ds_cost_detail_material_bom_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_material_bom_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_material_bom_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_outsourced_process id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_outsourced_process ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_outsourced_process_id_seq'::regclass);


--
-- Name: ds_cost_detail_outsourced_process_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_outsourced_process_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_outsourced_process_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_packaging id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_packaging ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_packaging_id_seq'::regclass);


--
-- Name: ds_cost_detail_packaging_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_packaging_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_packaging_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_plating_cost id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_plating_cost ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_plating_cost_id_seq'::regclass);


--
-- Name: ds_cost_detail_plating_cost_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_plating_cost_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_plating_cost_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_plating_scheme id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_plating_scheme ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_plating_scheme_id_seq'::regclass);


--
-- Name: ds_cost_detail_process_assembly_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_process_assembly_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_process_assembly_fee_id_seq'::regclass);


--
-- Name: ds_cost_detail_process_assembly_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_process_assembly_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_process_assembly_fee_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_production_energy id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_production_energy ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_production_energy_id_seq'::regclass);


--
-- Name: ds_cost_detail_production_energy_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_production_energy_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_production_energy_history_id_seq'::regclass);


--
-- Name: ds_cost_detail_tooling id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_tooling ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_tooling_id_seq'::regclass);


--
-- Name: ds_cost_detail_tooling_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_tooling_history ALTER COLUMN id SET DEFAULT nextval('public.ds_cost_detail_tooling_history_id_seq'::regclass);


--
-- Name: ds_quote_annual_discount id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_annual_discount ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_annual_discount_id_seq'::regclass);


--
-- Name: ds_quote_annual_discount_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_annual_discount_history ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_annual_discount_history_id_seq'::regclass);


--
-- Name: ds_quote_annual_discount_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_annual_discount_record ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_annual_discount_record_id_seq'::regclass);


--
-- Name: ds_quote_assembly_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_assembly_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_assembly_fee_id_seq'::regclass);


--
-- Name: ds_quote_assembly_fee_annual id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_assembly_fee_annual ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_assembly_fee_annual_id_seq'::regclass);


--
-- Name: ds_quote_assembly_fee_annual_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_assembly_fee_annual_history ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_assembly_fee_annual_history_id_seq'::regclass);


--
-- Name: ds_quote_assembly_fee_annual_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_assembly_fee_annual_record ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_assembly_fee_annual_record_id_seq'::regclass);


--
-- Name: ds_quote_assembly_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_assembly_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_assembly_fee_history_id_seq'::regclass);


--
-- Name: ds_quote_assembly_fee_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_assembly_fee_record ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_assembly_fee_record_id_seq'::regclass);


--
-- Name: ds_quote_customer_part id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_customer_part ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_customer_part_id_seq'::regclass);


--
-- Name: ds_quote_element_bom id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_element_bom ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_element_bom_id_seq'::regclass);


--
-- Name: ds_quote_element_bom_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_element_bom_history ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_element_bom_history_id_seq'::regclass);


--
-- Name: ds_quote_element_bom_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_element_bom_record ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_element_bom_record_id_seq'::regclass);


--
-- Name: ds_quote_finished_other_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_finished_other_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_finished_other_fee_id_seq'::regclass);


--
-- Name: ds_quote_finished_other_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_finished_other_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_finished_other_fee_history_id_seq'::regclass);


--
-- Name: ds_quote_finished_other_fee_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_finished_other_fee_record ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_finished_other_fee_record_id_seq'::regclass);


--
-- Name: ds_quote_incoming_annual id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_annual ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_incoming_annual_id_seq'::regclass);


--
-- Name: ds_quote_incoming_annual_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_annual_history ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_incoming_annual_history_id_seq'::regclass);


--
-- Name: ds_quote_incoming_annual_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_annual_record ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_incoming_annual_record_id_seq'::regclass);


--
-- Name: ds_quote_incoming_fixed_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_fixed_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_incoming_fixed_fee_id_seq'::regclass);


--
-- Name: ds_quote_incoming_fixed_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_fixed_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_incoming_fixed_fee_history_id_seq'::regclass);


--
-- Name: ds_quote_incoming_fixed_fee_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_fixed_fee_record ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_incoming_fixed_fee_record_id_seq'::regclass);


--
-- Name: ds_quote_incoming_other_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_other_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_incoming_other_fee_id_seq'::regclass);


--
-- Name: ds_quote_incoming_other_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_other_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_incoming_other_fee_history_id_seq'::regclass);


--
-- Name: ds_quote_incoming_other_fee_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_other_fee_record ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_incoming_other_fee_record_id_seq'::regclass);


--
-- Name: ds_quote_incoming_recovery id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_recovery ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_incoming_recovery_id_seq'::regclass);


--
-- Name: ds_quote_incoming_recovery_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_recovery_history ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_incoming_recovery_history_id_seq'::regclass);


--
-- Name: ds_quote_incoming_recovery_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_recovery_record ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_incoming_recovery_record_id_seq'::regclass);


--
-- Name: ds_quote_material id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_material ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_material_id_seq'::regclass);


--
-- Name: ds_quote_material_bom id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_material_bom ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_material_bom_id_seq'::regclass);


--
-- Name: ds_quote_material_bom_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_material_bom_history ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_material_bom_history_id_seq'::regclass);


--
-- Name: ds_quote_material_bom_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_material_bom_record ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_material_bom_record_id_seq'::regclass);


--
-- Name: ds_quote_plating_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_plating_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_plating_fee_id_seq'::regclass);


--
-- Name: ds_quote_plating_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_plating_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_plating_fee_history_id_seq'::regclass);


--
-- Name: ds_quote_plating_fee_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_plating_fee_record ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_plating_fee_record_id_seq'::regclass);


--
-- Name: ds_quote_plating_scheme id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_plating_scheme ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_plating_scheme_id_seq'::regclass);


--
-- Name: ds_quote_record_stale id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_record_stale ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_record_stale_id_seq'::regclass);


--
-- Name: ds_quote_self_process_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_self_process_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_self_process_fee_id_seq'::regclass);


--
-- Name: ds_quote_self_process_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_self_process_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_self_process_fee_history_id_seq'::regclass);


--
-- Name: ds_quote_self_process_fee_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_self_process_fee_record ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_self_process_fee_record_id_seq'::regclass);


--
-- Name: ds_quote_sub_component_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_sub_component_fee ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_sub_component_fee_id_seq'::regclass);


--
-- Name: ds_quote_sub_component_fee_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_sub_component_fee_history ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_sub_component_fee_history_id_seq'::regclass);


--
-- Name: ds_quote_sub_component_fee_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_sub_component_fee_record ALTER COLUMN id SET DEFAULT nextval('public.ds_quote_sub_component_fee_record_id_seq'::regclass);


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
-- Name: customer_price_adjust_material customer_price_adjust_material_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_price_adjust_material
    ADD CONSTRAINT customer_price_adjust_material_pkey PRIMARY KEY (id);


--
-- Name: customer_price_adjust_strategy_log customer_price_adjust_strategy_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_price_adjust_strategy_log
    ADD CONSTRAINT customer_price_adjust_strategy_log_pkey PRIMARY KEY (id);


--
-- Name: customer_price_adjust_strategy customer_price_adjust_strategy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_price_adjust_strategy
    ADD CONSTRAINT customer_price_adjust_strategy_pkey PRIMARY KEY (id);


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
-- Name: ds_cost_basic_element_bom_history ds_cost_basic_element_bom_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_element_bom_history
    ADD CONSTRAINT ds_cost_basic_element_bom_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_element_bom ds_cost_basic_element_bom_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_element_bom
    ADD CONSTRAINT ds_cost_basic_element_bom_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_finished_fixed_fee_history ds_cost_basic_finished_fixed_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_finished_fixed_fee_history
    ADD CONSTRAINT ds_cost_basic_finished_fixed_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_finished_fixed_fee ds_cost_basic_finished_fixed_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_finished_fixed_fee
    ADD CONSTRAINT ds_cost_basic_finished_fixed_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_finished_ratio_fee_history ds_cost_basic_finished_ratio_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_finished_ratio_fee_history
    ADD CONSTRAINT ds_cost_basic_finished_ratio_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_finished_ratio_fee ds_cost_basic_finished_ratio_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_finished_ratio_fee
    ADD CONSTRAINT ds_cost_basic_finished_ratio_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_incoming_other_fee_history ds_cost_basic_incoming_other_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_incoming_other_fee_history
    ADD CONSTRAINT ds_cost_basic_incoming_other_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_incoming_other_fee ds_cost_basic_incoming_other_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_incoming_other_fee
    ADD CONSTRAINT ds_cost_basic_incoming_other_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_incoming_other_fixed_fee_history ds_cost_basic_incoming_other_fixed_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_incoming_other_fixed_fee_history
    ADD CONSTRAINT ds_cost_basic_incoming_other_fixed_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_incoming_other_fixed_fee ds_cost_basic_incoming_other_fixed_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_incoming_other_fixed_fee
    ADD CONSTRAINT ds_cost_basic_incoming_other_fixed_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_incoming_process_fee_history ds_cost_basic_incoming_process_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_incoming_process_fee_history
    ADD CONSTRAINT ds_cost_basic_incoming_process_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_incoming_process_fee ds_cost_basic_incoming_process_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_incoming_process_fee
    ADD CONSTRAINT ds_cost_basic_incoming_process_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_material_bom_history ds_cost_basic_material_bom_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_material_bom_history
    ADD CONSTRAINT ds_cost_basic_material_bom_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_material_bom ds_cost_basic_material_bom_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_material_bom
    ADD CONSTRAINT ds_cost_basic_material_bom_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_material ds_cost_basic_material_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_material
    ADD CONSTRAINT ds_cost_basic_material_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_outsourced_process_history ds_cost_basic_outsourced_process_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_outsourced_process_history
    ADD CONSTRAINT ds_cost_basic_outsourced_process_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_outsourced_process ds_cost_basic_outsourced_process_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_outsourced_process
    ADD CONSTRAINT ds_cost_basic_outsourced_process_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_process_assembly_fee_history ds_cost_basic_process_assembly_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_process_assembly_fee_history
    ADD CONSTRAINT ds_cost_basic_process_assembly_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_basic_process_assembly_fee ds_cost_basic_process_assembly_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_basic_process_assembly_fee
    ADD CONSTRAINT ds_cost_basic_process_assembly_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_auxiliary_energy_history ds_cost_detail_auxiliary_energy_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_auxiliary_energy_history
    ADD CONSTRAINT ds_cost_detail_auxiliary_energy_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_auxiliary_energy ds_cost_detail_auxiliary_energy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_auxiliary_energy
    ADD CONSTRAINT ds_cost_detail_auxiliary_energy_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_capacity_history ds_cost_detail_capacity_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_capacity_history
    ADD CONSTRAINT ds_cost_detail_capacity_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_capacity ds_cost_detail_capacity_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_capacity
    ADD CONSTRAINT ds_cost_detail_capacity_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_consumable_history ds_cost_detail_consumable_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_consumable_history
    ADD CONSTRAINT ds_cost_detail_consumable_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_consumable ds_cost_detail_consumable_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_consumable
    ADD CONSTRAINT ds_cost_detail_consumable_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_depreciation_history ds_cost_detail_depreciation_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_depreciation_history
    ADD CONSTRAINT ds_cost_detail_depreciation_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_depreciation ds_cost_detail_depreciation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_depreciation
    ADD CONSTRAINT ds_cost_detail_depreciation_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_element_bom_history ds_cost_detail_element_bom_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_element_bom_history
    ADD CONSTRAINT ds_cost_detail_element_bom_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_element_bom ds_cost_detail_element_bom_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_element_bom
    ADD CONSTRAINT ds_cost_detail_element_bom_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_finished_fixed_fee_history ds_cost_detail_finished_fixed_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_finished_fixed_fee_history
    ADD CONSTRAINT ds_cost_detail_finished_fixed_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_finished_fixed_fee ds_cost_detail_finished_fixed_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_finished_fixed_fee
    ADD CONSTRAINT ds_cost_detail_finished_fixed_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_finished_ratio_fee_history ds_cost_detail_finished_ratio_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_finished_ratio_fee_history
    ADD CONSTRAINT ds_cost_detail_finished_ratio_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_finished_ratio_fee ds_cost_detail_finished_ratio_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_finished_ratio_fee
    ADD CONSTRAINT ds_cost_detail_finished_ratio_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_incoming_other_fee_history ds_cost_detail_incoming_other_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_incoming_other_fee_history
    ADD CONSTRAINT ds_cost_detail_incoming_other_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_incoming_other_fee ds_cost_detail_incoming_other_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_incoming_other_fee
    ADD CONSTRAINT ds_cost_detail_incoming_other_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_incoming_other_fixed_fee_history ds_cost_detail_incoming_other_fixed_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_incoming_other_fixed_fee_history
    ADD CONSTRAINT ds_cost_detail_incoming_other_fixed_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_incoming_other_fixed_fee ds_cost_detail_incoming_other_fixed_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_incoming_other_fixed_fee
    ADD CONSTRAINT ds_cost_detail_incoming_other_fixed_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_incoming_process_fee_history ds_cost_detail_incoming_process_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_incoming_process_fee_history
    ADD CONSTRAINT ds_cost_detail_incoming_process_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_incoming_process_fee ds_cost_detail_incoming_process_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_incoming_process_fee
    ADD CONSTRAINT ds_cost_detail_incoming_process_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_material_bom_history ds_cost_detail_material_bom_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_material_bom_history
    ADD CONSTRAINT ds_cost_detail_material_bom_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_material_bom ds_cost_detail_material_bom_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_material_bom
    ADD CONSTRAINT ds_cost_detail_material_bom_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_material ds_cost_detail_material_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_material
    ADD CONSTRAINT ds_cost_detail_material_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_outsourced_process_history ds_cost_detail_outsourced_process_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_outsourced_process_history
    ADD CONSTRAINT ds_cost_detail_outsourced_process_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_outsourced_process ds_cost_detail_outsourced_process_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_outsourced_process
    ADD CONSTRAINT ds_cost_detail_outsourced_process_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_packaging_history ds_cost_detail_packaging_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_packaging_history
    ADD CONSTRAINT ds_cost_detail_packaging_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_packaging ds_cost_detail_packaging_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_packaging
    ADD CONSTRAINT ds_cost_detail_packaging_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_plating_cost_history ds_cost_detail_plating_cost_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_plating_cost_history
    ADD CONSTRAINT ds_cost_detail_plating_cost_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_plating_cost ds_cost_detail_plating_cost_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_plating_cost
    ADD CONSTRAINT ds_cost_detail_plating_cost_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_plating_scheme ds_cost_detail_plating_scheme_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_plating_scheme
    ADD CONSTRAINT ds_cost_detail_plating_scheme_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_process_assembly_fee_history ds_cost_detail_process_assembly_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_process_assembly_fee_history
    ADD CONSTRAINT ds_cost_detail_process_assembly_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_process_assembly_fee ds_cost_detail_process_assembly_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_process_assembly_fee
    ADD CONSTRAINT ds_cost_detail_process_assembly_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_production_energy_history ds_cost_detail_production_energy_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_production_energy_history
    ADD CONSTRAINT ds_cost_detail_production_energy_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_production_energy ds_cost_detail_production_energy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_production_energy
    ADD CONSTRAINT ds_cost_detail_production_energy_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_tooling_history ds_cost_detail_tooling_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_tooling_history
    ADD CONSTRAINT ds_cost_detail_tooling_history_pkey PRIMARY KEY (id);


--
-- Name: ds_cost_detail_tooling ds_cost_detail_tooling_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_cost_detail_tooling
    ADD CONSTRAINT ds_cost_detail_tooling_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_annual_discount_history ds_quote_annual_discount_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_annual_discount_history
    ADD CONSTRAINT ds_quote_annual_discount_history_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_annual_discount ds_quote_annual_discount_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_annual_discount
    ADD CONSTRAINT ds_quote_annual_discount_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_annual_discount_record ds_quote_annual_discount_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_annual_discount_record
    ADD CONSTRAINT ds_quote_annual_discount_record_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_assembly_fee_annual_history ds_quote_assembly_fee_annual_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_assembly_fee_annual_history
    ADD CONSTRAINT ds_quote_assembly_fee_annual_history_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_assembly_fee_annual ds_quote_assembly_fee_annual_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_assembly_fee_annual
    ADD CONSTRAINT ds_quote_assembly_fee_annual_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_assembly_fee_annual_record ds_quote_assembly_fee_annual_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_assembly_fee_annual_record
    ADD CONSTRAINT ds_quote_assembly_fee_annual_record_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_assembly_fee_history ds_quote_assembly_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_assembly_fee_history
    ADD CONSTRAINT ds_quote_assembly_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_assembly_fee ds_quote_assembly_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_assembly_fee
    ADD CONSTRAINT ds_quote_assembly_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_assembly_fee_record ds_quote_assembly_fee_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_assembly_fee_record
    ADD CONSTRAINT ds_quote_assembly_fee_record_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_customer_part ds_quote_customer_part_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_customer_part
    ADD CONSTRAINT ds_quote_customer_part_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_element_bom_history ds_quote_element_bom_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_element_bom_history
    ADD CONSTRAINT ds_quote_element_bom_history_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_element_bom ds_quote_element_bom_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_element_bom
    ADD CONSTRAINT ds_quote_element_bom_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_element_bom_record ds_quote_element_bom_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_element_bom_record
    ADD CONSTRAINT ds_quote_element_bom_record_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_finished_other_fee_history ds_quote_finished_other_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_finished_other_fee_history
    ADD CONSTRAINT ds_quote_finished_other_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_finished_other_fee ds_quote_finished_other_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_finished_other_fee
    ADD CONSTRAINT ds_quote_finished_other_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_finished_other_fee_record ds_quote_finished_other_fee_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_finished_other_fee_record
    ADD CONSTRAINT ds_quote_finished_other_fee_record_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_incoming_annual_history ds_quote_incoming_annual_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_annual_history
    ADD CONSTRAINT ds_quote_incoming_annual_history_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_incoming_annual ds_quote_incoming_annual_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_annual
    ADD CONSTRAINT ds_quote_incoming_annual_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_incoming_annual_record ds_quote_incoming_annual_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_annual_record
    ADD CONSTRAINT ds_quote_incoming_annual_record_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_incoming_fixed_fee_history ds_quote_incoming_fixed_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_fixed_fee_history
    ADD CONSTRAINT ds_quote_incoming_fixed_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_incoming_fixed_fee ds_quote_incoming_fixed_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_fixed_fee
    ADD CONSTRAINT ds_quote_incoming_fixed_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_incoming_fixed_fee_record ds_quote_incoming_fixed_fee_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_fixed_fee_record
    ADD CONSTRAINT ds_quote_incoming_fixed_fee_record_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_incoming_other_fee_history ds_quote_incoming_other_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_other_fee_history
    ADD CONSTRAINT ds_quote_incoming_other_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_incoming_other_fee ds_quote_incoming_other_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_other_fee
    ADD CONSTRAINT ds_quote_incoming_other_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_incoming_other_fee_record ds_quote_incoming_other_fee_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_other_fee_record
    ADD CONSTRAINT ds_quote_incoming_other_fee_record_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_incoming_recovery_history ds_quote_incoming_recovery_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_recovery_history
    ADD CONSTRAINT ds_quote_incoming_recovery_history_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_incoming_recovery ds_quote_incoming_recovery_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_recovery
    ADD CONSTRAINT ds_quote_incoming_recovery_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_incoming_recovery_record ds_quote_incoming_recovery_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_incoming_recovery_record
    ADD CONSTRAINT ds_quote_incoming_recovery_record_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_material_bom_history ds_quote_material_bom_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_material_bom_history
    ADD CONSTRAINT ds_quote_material_bom_history_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_material_bom ds_quote_material_bom_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_material_bom
    ADD CONSTRAINT ds_quote_material_bom_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_material_bom_record ds_quote_material_bom_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_material_bom_record
    ADD CONSTRAINT ds_quote_material_bom_record_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_material ds_quote_material_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_material
    ADD CONSTRAINT ds_quote_material_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_plating_fee_history ds_quote_plating_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_plating_fee_history
    ADD CONSTRAINT ds_quote_plating_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_plating_fee ds_quote_plating_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_plating_fee
    ADD CONSTRAINT ds_quote_plating_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_plating_fee_record ds_quote_plating_fee_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_plating_fee_record
    ADD CONSTRAINT ds_quote_plating_fee_record_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_plating_scheme ds_quote_plating_scheme_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_plating_scheme
    ADD CONSTRAINT ds_quote_plating_scheme_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_record_stale ds_quote_record_stale_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_record_stale
    ADD CONSTRAINT ds_quote_record_stale_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_self_process_fee_history ds_quote_self_process_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_self_process_fee_history
    ADD CONSTRAINT ds_quote_self_process_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_self_process_fee ds_quote_self_process_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_self_process_fee
    ADD CONSTRAINT ds_quote_self_process_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_self_process_fee_record ds_quote_self_process_fee_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_self_process_fee_record
    ADD CONSTRAINT ds_quote_self_process_fee_record_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_sub_component_fee_history ds_quote_sub_component_fee_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_sub_component_fee_history
    ADD CONSTRAINT ds_quote_sub_component_fee_history_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_sub_component_fee ds_quote_sub_component_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_sub_component_fee
    ADD CONSTRAINT ds_quote_sub_component_fee_pkey PRIMARY KEY (id);


--
-- Name: ds_quote_sub_component_fee_record ds_quote_sub_component_fee_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ds_quote_sub_component_fee_record
    ADD CONSTRAINT ds_quote_sub_component_fee_record_pkey PRIMARY KEY (id);


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
-- Name: element_price_version_item element_price_version_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price_version_item
    ADD CONSTRAINT element_price_version_item_pkey PRIMARY KEY (id);


--
-- Name: element_price_version element_price_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price_version
    ADD CONSTRAINT element_price_version_pkey PRIMARY KEY (id);


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
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


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
-- Name: material_price_review_column material_price_review_column_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_review_column
    ADD CONSTRAINT material_price_review_column_pkey PRIMARY KEY (id);


--
-- Name: material_price_review material_price_review_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_review
    ADD CONSTRAINT material_price_review_pkey PRIMARY KEY (id);


--
-- Name: material_price_update_job_item material_price_update_job_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_update_job_item
    ADD CONSTRAINT material_price_update_job_item_pkey PRIMARY KEY (id);


--
-- Name: material_price_update_job material_price_update_job_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_update_job
    ADD CONSTRAINT material_price_update_job_pkey PRIMARY KEY (id);


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
-- Name: material_recipe_composition material_recipe_composition_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_recipe_composition
    ADD CONSTRAINT material_recipe_composition_pkey PRIMARY KEY (id);


--
-- Name: material_recipe_config material_recipe_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_recipe_config
    ADD CONSTRAINT material_recipe_config_pkey PRIMARY KEY (id);


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
-- Name: price_adjust_settings pk_price_adjust_settings; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.price_adjust_settings
    ADD CONSTRAINT pk_price_adjust_settings PRIMARY KEY (id);


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
-- Name: quotation_price_revision quotation_price_revision_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_price_revision
    ADD CONSTRAINT quotation_price_revision_pkey PRIMARY KEY (id);


--
-- Name: quotation quotation_quotation_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation
    ADD CONSTRAINT quotation_quotation_number_key UNIQUE (quotation_number);


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
-- Name: sel_product_no sel_product_no_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sel_product_no
    ADD CONSTRAINT sel_product_no_pkey PRIMARY KEY (id);


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
-- Name: semantic_edge semantic_edge_from_node_id_to_node_id_edge_kind_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_edge
    ADD CONSTRAINT semantic_edge_from_node_id_to_node_id_edge_kind_key UNIQUE (from_node_id, to_node_id, edge_kind);


--
-- Name: semantic_edge_key semantic_edge_key_edge_id_seq_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_edge_key
    ADD CONSTRAINT semantic_edge_key_edge_id_seq_key UNIQUE (edge_id, seq);


--
-- Name: semantic_edge_key semantic_edge_key_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_edge_key
    ADD CONSTRAINT semantic_edge_key_pkey PRIMARY KEY (id);


--
-- Name: semantic_edge semantic_edge_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_edge
    ADD CONSTRAINT semantic_edge_pkey PRIMARY KEY (id);


--
-- Name: semantic_node_column semantic_node_column_node_id_db_column_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_node_column
    ADD CONSTRAINT semantic_node_column_node_id_db_column_key UNIQUE (node_id, db_column);


--
-- Name: semantic_node_column semantic_node_column_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_node_column
    ADD CONSTRAINT semantic_node_column_pkey PRIMARY KEY (id);


--
-- Name: semantic_node semantic_node_node_key_dialect_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_node
    ADD CONSTRAINT semantic_node_node_key_dialect_key UNIQUE (node_key, dialect);


--
-- Name: semantic_node semantic_node_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_node
    ADD CONSTRAINT semantic_node_pkey PRIMARY KEY (id);


--
-- Name: semantic_tab_view_column semantic_tab_view_column_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_tab_view_column
    ADD CONSTRAINT semantic_tab_view_column_pkey PRIMARY KEY (id);


--
-- Name: semantic_tab_view_column semantic_tab_view_column_view_id_column_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_tab_view_column
    ADD CONSTRAINT semantic_tab_view_column_view_id_column_id_key UNIQUE (view_id, column_id);


--
-- Name: semantic_tab_view_node semantic_tab_view_node_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_tab_view_node
    ADD CONSTRAINT semantic_tab_view_node_pkey PRIMARY KEY (id);


--
-- Name: semantic_tab_view_node semantic_tab_view_node_view_id_node_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_tab_view_node
    ADD CONSTRAINT semantic_tab_view_node_view_id_node_id_key UNIQUE (view_id, node_id);


--
-- Name: semantic_tab_view semantic_tab_view_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_tab_view
    ADD CONSTRAINT semantic_tab_view_pkey PRIMARY KEY (id);


--
-- Name: semantic_tab_view semantic_tab_view_tab_type_variant_key_dialect_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_tab_view
    ADD CONSTRAINT semantic_tab_view_tab_type_variant_key_dialect_key UNIQUE (tab_type, variant_key, dialect);


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
-- Name: comparison_column_config uq_ccc_customer_series; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comparison_column_config
    ADD CONSTRAINT uq_ccc_customer_series UNIQUE (customer_no, template_series_id);


--
-- Name: costing_order uq_co_number; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_order
    ADD CONSTRAINT uq_co_number UNIQUE (costing_order_number);


--
-- Name: material_recipe_element uq_config_element; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_recipe_element
    ADD CONSTRAINT uq_config_element UNIQUE (config_id, element_code);


--
-- Name: costing_order_version_override uq_covo; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_order_version_override
    ADD CONSTRAINT uq_covo UNIQUE (costing_order_id, component_id, part_no);


--
-- Name: customer_price_adjust_element uq_cpae_strategy_element; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_price_adjust_element
    ADD CONSTRAINT uq_cpae_strategy_element UNIQUE (strategy_id, element_code);


--
-- Name: customer_price_adjust_material uq_cpam_strategy_material; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_price_adjust_material
    ADD CONSTRAINT uq_cpam_strategy_material UNIQUE (strategy_id, material_no);


--
-- Name: customer_price_adjust_strategy uq_cpas_customer; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_price_adjust_strategy
    ADD CONSTRAINT uq_cpas_customer UNIQUE (customer_no);


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
-- Name: element_price_version_item uq_epvi_version_element; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.element_price_version_item
    ADD CONSTRAINT uq_epvi_version_element UNIQUE (version_id, element_code);


--
-- Name: import_mapping_template uq_excel_template_mapping; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.import_mapping_template
    ADD CONSTRAINT uq_excel_template_mapping UNIQUE (excel_template_id, template_id);


--
-- Name: material_price_review uq_mpr_version_material; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_review
    ADD CONSTRAINT uq_mpr_version_material UNIQUE (version_id, material_no);


--
-- Name: material_price_review_column uq_mprc_review_column; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_review_column
    ADD CONSTRAINT uq_mprc_review_column UNIQUE (review_id, column_id);


--
-- Name: material_recipe_config uq_mrc_config_no; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_recipe_config
    ADD CONSTRAINT uq_mrc_config_no UNIQUE (config_no);


--
-- Name: material_recipe_config uq_mrc_recipe_seq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_recipe_config
    ADD CONSTRAINT uq_mrc_recipe_seq UNIQUE (recipe_id, seq);


--
-- Name: material_recipe_composition uq_mrcomp_recipe_element; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_recipe_composition
    ADD CONSTRAINT uq_mrcomp_recipe_element UNIQUE (recipe_id, element_no);


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
-- Name: quotation_line_component_data uq_qlcd_line_component; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_line_component_data
    ADD CONSTRAINT uq_qlcd_line_component UNIQUE (line_item_id, component_id);


--
-- Name: quotation_price_revision uq_qpr_quotation_based_version; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation_price_revision
    ADD CONSTRAINT uq_qpr_quotation_based_version UNIQUE (quotation_id, based_version_id);


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
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


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
-- Name: idx_ds_cost_basic_element_bom_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_element_bom_axis_ver ON public.ds_cost_basic_element_bom USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_element_bom_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_element_bom_history_axis_ver ON public.ds_cost_basic_element_bom_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_finished_fixed_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_finished_fixed_fee_axis_ver ON public.ds_cost_basic_finished_fixed_fee USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_finished_fixed_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_finished_fixed_fee_history_axis_ver ON public.ds_cost_basic_finished_fixed_fee_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_finished_ratio_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_finished_ratio_fee_axis_ver ON public.ds_cost_basic_finished_ratio_fee USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_finished_ratio_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_finished_ratio_fee_history_axis_ver ON public.ds_cost_basic_finished_ratio_fee_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_incoming_other_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_incoming_other_fee_axis_ver ON public.ds_cost_basic_incoming_other_fee USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_incoming_other_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_incoming_other_fee_history_axis_ver ON public.ds_cost_basic_incoming_other_fee_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_incoming_other_fixed_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_incoming_other_fixed_fee_axis_ver ON public.ds_cost_basic_incoming_other_fixed_fee USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_incoming_other_fixed_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_incoming_other_fixed_fee_history_axis_ver ON public.ds_cost_basic_incoming_other_fixed_fee_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_incoming_process_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_incoming_process_fee_axis_ver ON public.ds_cost_basic_incoming_process_fee USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_incoming_process_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_incoming_process_fee_history_axis_ver ON public.ds_cost_basic_incoming_process_fee_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_material_bom_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_material_bom_axis_ver ON public.ds_cost_basic_material_bom USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_material_bom_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_material_bom_history_axis_ver ON public.ds_cost_basic_material_bom_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_outsourced_process_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_outsourced_process_axis_ver ON public.ds_cost_basic_outsourced_process USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_outsourced_process_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_outsourced_process_history_axis_ver ON public.ds_cost_basic_outsourced_process_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_process_assembly_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_process_assembly_fee_axis_ver ON public.ds_cost_basic_process_assembly_fee USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_basic_process_assembly_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_basic_process_assembly_fee_history_axis_ver ON public.ds_cost_basic_process_assembly_fee_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_auxiliary_energy_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_auxiliary_energy_axis_ver ON public.ds_cost_detail_auxiliary_energy USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_auxiliary_energy_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_auxiliary_energy_history_axis_ver ON public.ds_cost_detail_auxiliary_energy_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_capacity_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_capacity_axis_ver ON public.ds_cost_detail_capacity USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_capacity_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_capacity_history_axis_ver ON public.ds_cost_detail_capacity_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_consumable_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_consumable_axis_ver ON public.ds_cost_detail_consumable USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_consumable_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_consumable_history_axis_ver ON public.ds_cost_detail_consumable_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_depreciation_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_depreciation_axis_ver ON public.ds_cost_detail_depreciation USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_depreciation_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_depreciation_history_axis_ver ON public.ds_cost_detail_depreciation_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_element_bom_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_element_bom_axis_ver ON public.ds_cost_detail_element_bom USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_element_bom_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_element_bom_history_axis_ver ON public.ds_cost_detail_element_bom_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_finished_fixed_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_finished_fixed_fee_axis_ver ON public.ds_cost_detail_finished_fixed_fee USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_finished_fixed_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_finished_fixed_fee_history_axis_ver ON public.ds_cost_detail_finished_fixed_fee_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_finished_ratio_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_finished_ratio_fee_axis_ver ON public.ds_cost_detail_finished_ratio_fee USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_finished_ratio_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_finished_ratio_fee_history_axis_ver ON public.ds_cost_detail_finished_ratio_fee_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_incoming_other_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_incoming_other_fee_axis_ver ON public.ds_cost_detail_incoming_other_fee USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_incoming_other_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_incoming_other_fee_history_axis_ver ON public.ds_cost_detail_incoming_other_fee_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_incoming_other_fixed_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_incoming_other_fixed_fee_axis_ver ON public.ds_cost_detail_incoming_other_fixed_fee USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_incoming_other_fixed_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_incoming_other_fixed_fee_history_axis_ver ON public.ds_cost_detail_incoming_other_fixed_fee_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_incoming_process_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_incoming_process_fee_axis_ver ON public.ds_cost_detail_incoming_process_fee USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_incoming_process_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_incoming_process_fee_history_axis_ver ON public.ds_cost_detail_incoming_process_fee_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_material_bom_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_material_bom_axis_ver ON public.ds_cost_detail_material_bom USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_material_bom_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_material_bom_history_axis_ver ON public.ds_cost_detail_material_bom_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_outsourced_process_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_outsourced_process_axis_ver ON public.ds_cost_detail_outsourced_process USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_outsourced_process_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_outsourced_process_history_axis_ver ON public.ds_cost_detail_outsourced_process_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_packaging_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_packaging_axis_ver ON public.ds_cost_detail_packaging USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_packaging_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_packaging_history_axis_ver ON public.ds_cost_detail_packaging_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_plating_cost_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_plating_cost_axis_ver ON public.ds_cost_detail_plating_cost USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_plating_cost_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_plating_cost_history_axis_ver ON public.ds_cost_detail_plating_cost_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_process_assembly_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_process_assembly_fee_axis_ver ON public.ds_cost_detail_process_assembly_fee USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_process_assembly_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_process_assembly_fee_history_axis_ver ON public.ds_cost_detail_process_assembly_fee_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_production_energy_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_production_energy_axis_ver ON public.ds_cost_detail_production_energy USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_production_energy_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_production_energy_history_axis_ver ON public.ds_cost_detail_production_energy_history USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_tooling_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_tooling_axis_ver ON public.ds_cost_detail_tooling USING btree (production_no, version_no);


--
-- Name: idx_ds_cost_detail_tooling_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_cost_detail_tooling_history_axis_ver ON public.ds_cost_detail_tooling_history USING btree (production_no, version_no);


--
-- Name: idx_ds_quote_annual_discount_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_annual_discount_axis_ver ON public.ds_quote_annual_discount USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_annual_discount_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_annual_discount_history_axis_ver ON public.ds_quote_annual_discount_history USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_assembly_fee_annual_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_assembly_fee_annual_axis_ver ON public.ds_quote_assembly_fee_annual USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_assembly_fee_annual_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_assembly_fee_annual_history_axis_ver ON public.ds_quote_assembly_fee_annual_history USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_assembly_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_assembly_fee_axis_ver ON public.ds_quote_assembly_fee USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_assembly_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_assembly_fee_history_axis_ver ON public.ds_quote_assembly_fee_history USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_customer_part_customer_no; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_customer_part_customer_no ON public.ds_quote_customer_part USING btree (customer_no);


--
-- Name: idx_ds_quote_element_bom_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_element_bom_axis_ver ON public.ds_quote_element_bom USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_element_bom_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_element_bom_history_axis_ver ON public.ds_quote_element_bom_history USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_finished_other_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_finished_other_fee_axis_ver ON public.ds_quote_finished_other_fee USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_finished_other_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_finished_other_fee_history_axis_ver ON public.ds_quote_finished_other_fee_history USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_incoming_annual_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_incoming_annual_axis_ver ON public.ds_quote_incoming_annual USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_incoming_annual_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_incoming_annual_history_axis_ver ON public.ds_quote_incoming_annual_history USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_incoming_fixed_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_incoming_fixed_fee_axis_ver ON public.ds_quote_incoming_fixed_fee USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_incoming_fixed_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_incoming_fixed_fee_history_axis_ver ON public.ds_quote_incoming_fixed_fee_history USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_incoming_other_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_incoming_other_fee_axis_ver ON public.ds_quote_incoming_other_fee USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_incoming_other_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_incoming_other_fee_history_axis_ver ON public.ds_quote_incoming_other_fee_history USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_incoming_recovery_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_incoming_recovery_axis_ver ON public.ds_quote_incoming_recovery USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_incoming_recovery_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_incoming_recovery_history_axis_ver ON public.ds_quote_incoming_recovery_history USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_material_bom_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_material_bom_axis_ver ON public.ds_quote_material_bom USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_material_bom_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_material_bom_history_axis_ver ON public.ds_quote_material_bom_history USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_plating_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_plating_fee_axis_ver ON public.ds_quote_plating_fee USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_plating_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_plating_fee_history_axis_ver ON public.ds_quote_plating_fee_history USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_record_stale_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_record_stale_quotation ON public.ds_quote_record_stale USING btree (quotation_id);


--
-- Name: idx_ds_quote_self_process_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_self_process_fee_axis_ver ON public.ds_quote_self_process_fee USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_self_process_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_self_process_fee_history_axis_ver ON public.ds_quote_self_process_fee_history USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_sub_component_fee_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_sub_component_fee_axis_ver ON public.ds_quote_sub_component_fee USING btree (material_no, version_no);


--
-- Name: idx_ds_quote_sub_component_fee_history_axis_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ds_quote_sub_component_fee_history_axis_ver ON public.ds_quote_sub_component_fee_history USING btree (material_no, version_no);


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
-- Name: idx_mrc_recipe; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mrc_recipe ON public.material_recipe_config USING btree (recipe_id, seq);


--
-- Name: idx_mrcomp_recipe; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mrcomp_recipe ON public.material_recipe_composition USING btree (recipe_id, sort_order);


--
-- Name: idx_mre_config; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mre_config ON public.material_recipe_element USING btree (config_id);


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
-- Name: idx_qlp_line_seq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qlp_line_seq ON public.quotation_line_process USING btree (line_item_id, seq_no);


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
-- Name: idx_quote_annual_discount_record_axis_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_annual_discount_record_axis_quotation ON public.ds_quote_annual_discount_record USING btree (customer_no, material_no, quotation_id);


--
-- Name: idx_quote_annual_discount_record_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_annual_discount_record_quotation ON public.ds_quote_annual_discount_record USING btree (quotation_id);


--
-- Name: idx_quote_assembly_fee_annual_record_axis_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_assembly_fee_annual_record_axis_quotation ON public.ds_quote_assembly_fee_annual_record USING btree (customer_no, material_no, quotation_id);


--
-- Name: idx_quote_assembly_fee_annual_record_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_assembly_fee_annual_record_quotation ON public.ds_quote_assembly_fee_annual_record USING btree (quotation_id);


--
-- Name: idx_quote_assembly_fee_record_axis_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_assembly_fee_record_axis_quotation ON public.ds_quote_assembly_fee_record USING btree (customer_no, material_no, quotation_id);


--
-- Name: idx_quote_assembly_fee_record_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_assembly_fee_record_quotation ON public.ds_quote_assembly_fee_record USING btree (quotation_id);


--
-- Name: idx_quote_element_bom_record_axis_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_element_bom_record_axis_quotation ON public.ds_quote_element_bom_record USING btree (customer_no, material_no, quotation_id);


--
-- Name: idx_quote_element_bom_record_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_element_bom_record_quotation ON public.ds_quote_element_bom_record USING btree (quotation_id);


--
-- Name: idx_quote_finished_other_fee_record_axis_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_finished_other_fee_record_axis_quotation ON public.ds_quote_finished_other_fee_record USING btree (customer_no, material_no, quotation_id);


--
-- Name: idx_quote_finished_other_fee_record_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_finished_other_fee_record_quotation ON public.ds_quote_finished_other_fee_record USING btree (quotation_id);


--
-- Name: idx_quote_incoming_annual_record_axis_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_incoming_annual_record_axis_quotation ON public.ds_quote_incoming_annual_record USING btree (customer_no, material_no, quotation_id);


--
-- Name: idx_quote_incoming_annual_record_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_incoming_annual_record_quotation ON public.ds_quote_incoming_annual_record USING btree (quotation_id);


--
-- Name: idx_quote_incoming_fixed_fee_record_axis_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_incoming_fixed_fee_record_axis_quotation ON public.ds_quote_incoming_fixed_fee_record USING btree (customer_no, material_no, quotation_id);


--
-- Name: idx_quote_incoming_fixed_fee_record_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_incoming_fixed_fee_record_quotation ON public.ds_quote_incoming_fixed_fee_record USING btree (quotation_id);


--
-- Name: idx_quote_incoming_other_fee_record_axis_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_incoming_other_fee_record_axis_quotation ON public.ds_quote_incoming_other_fee_record USING btree (customer_no, material_no, quotation_id);


--
-- Name: idx_quote_incoming_other_fee_record_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_incoming_other_fee_record_quotation ON public.ds_quote_incoming_other_fee_record USING btree (quotation_id);


--
-- Name: idx_quote_incoming_recovery_record_axis_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_incoming_recovery_record_axis_quotation ON public.ds_quote_incoming_recovery_record USING btree (customer_no, material_no, quotation_id);


--
-- Name: idx_quote_incoming_recovery_record_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_incoming_recovery_record_quotation ON public.ds_quote_incoming_recovery_record USING btree (quotation_id);


--
-- Name: idx_quote_material_bom_record_axis_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_material_bom_record_axis_quotation ON public.ds_quote_material_bom_record USING btree (customer_no, material_no, quotation_id);


--
-- Name: idx_quote_material_bom_record_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_material_bom_record_quotation ON public.ds_quote_material_bom_record USING btree (quotation_id);


--
-- Name: idx_quote_plating_fee_record_axis_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_plating_fee_record_axis_quotation ON public.ds_quote_plating_fee_record USING btree (customer_no, material_no, quotation_id);


--
-- Name: idx_quote_plating_fee_record_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_plating_fee_record_quotation ON public.ds_quote_plating_fee_record USING btree (quotation_id);


--
-- Name: idx_quote_self_process_fee_record_axis_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_self_process_fee_record_axis_quotation ON public.ds_quote_self_process_fee_record USING btree (customer_no, material_no, quotation_id);


--
-- Name: idx_quote_self_process_fee_record_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_self_process_fee_record_quotation ON public.ds_quote_self_process_fee_record USING btree (quotation_id);


--
-- Name: idx_quote_sub_component_fee_record_axis_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_sub_component_fee_record_axis_quotation ON public.ds_quote_sub_component_fee_record USING btree (customer_no, material_no, quotation_id);


--
-- Name: idx_quote_sub_component_fee_record_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_quote_sub_component_fee_record_quotation ON public.ds_quote_sub_component_fee_record USING btree (quotation_id);


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
-- Name: idx_semantic_edge_from; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_semantic_edge_from ON public.semantic_edge USING btree (from_node_id);


--
-- Name: idx_semantic_edge_to; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_semantic_edge_to ON public.semantic_edge USING btree (to_node_id);


--
-- Name: idx_semantic_tvc_view; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_semantic_tvc_view ON public.semantic_tab_view_column USING btree (view_id);


--
-- Name: idx_semantic_tvn_view; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_semantic_tvn_view ON public.semantic_tab_view_node USING btree (view_id);


--
-- Name: idx_spn_part_no; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_spn_part_no ON public.sel_product_no USING btree (quote_part_no);


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

CREATE UNIQUE INDEX uq_co_active ON public.costing_order USING btree (quotation_id) WHERE ((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('APPROVED'::character varying)::text]));


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
-- Name: uq_ds_cost_basic_material; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_ds_cost_basic_material ON public.ds_cost_basic_material USING btree (production_no);


--
-- Name: uq_ds_cost_detail_material; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_ds_cost_detail_material ON public.ds_cost_detail_material USING btree (production_no);


--
-- Name: uq_ds_cost_detail_plating_scheme; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_ds_cost_detail_plating_scheme ON public.ds_cost_detail_plating_scheme USING btree (scheme_no, scheme_version, item_seq);


--
-- Name: uq_ds_quote_customer_part; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_ds_quote_customer_part ON public.ds_quote_customer_part USING btree (customer_no, customer_product_no);


--
-- Name: uq_ds_quote_material; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_ds_quote_material ON public.ds_quote_material USING btree (customer_no, material_no);


--
-- Name: uq_ds_quote_plating_scheme; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_ds_quote_plating_scheme ON public.ds_quote_plating_scheme USING btree (scheme_no, scheme_version, item_seq);


--
-- Name: uq_ds_quote_record_stale_open; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_ds_quote_record_stale_open ON public.ds_quote_record_stale USING btree (quotation_id) WHERE (cleared_at IS NULL);


--
-- Name: uq_edge_fallback; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_edge_fallback ON public.semantic_edge USING btree (from_node_id, coalesce_group, fallback_order) WHERE ((fallback_order IS NOT NULL) AND (coalesce_group IS NOT NULL) AND ((status)::text = 'ACTIVE'::text));


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
-- Name: uq_spn_cust_prod; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_spn_cust_prod ON public.sel_product_no USING btree (customer_no, customer_product_no);


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
-- Name: material_recipe_element fk_mre_config; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_recipe_element
    ADD CONSTRAINT fk_mre_config FOREIGN KEY (config_id) REFERENCES public.material_recipe_config(id) ON DELETE CASCADE;


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
-- Name: material_price_review_column material_price_review_column_review_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_review_column
    ADD CONSTRAINT material_price_review_column_review_id_fkey FOREIGN KEY (review_id) REFERENCES public.material_price_review(id) ON DELETE CASCADE;


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
-- Name: material_price_update_job material_price_update_job_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_update_job
    ADD CONSTRAINT material_price_update_job_version_id_fkey FOREIGN KEY (version_id) REFERENCES public.element_price_version(id);


--
-- Name: material_price_version_ref material_price_version_ref_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_price_version_ref
    ADD CONSTRAINT material_price_version_ref_version_id_fkey FOREIGN KEY (version_id) REFERENCES public.element_price_version(id);


--
-- Name: material_recipe_composition material_recipe_composition_recipe_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_recipe_composition
    ADD CONSTRAINT material_recipe_composition_recipe_id_fkey FOREIGN KEY (recipe_id) REFERENCES public.material_recipe(id) ON DELETE CASCADE;


--
-- Name: material_recipe_config material_recipe_config_recipe_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_recipe_config
    ADD CONSTRAINT material_recipe_config_recipe_id_fkey FOREIGN KEY (recipe_id) REFERENCES public.material_recipe(id) ON DELETE CASCADE;


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
-- Name: quotation quotation_sales_rep_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quotation
    ADD CONSTRAINT quotation_sales_rep_id_fkey FOREIGN KEY (sales_rep_id) REFERENCES public."user"(id);


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
-- Name: semantic_edge semantic_edge_from_node_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_edge
    ADD CONSTRAINT semantic_edge_from_node_id_fkey FOREIGN KEY (from_node_id) REFERENCES public.semantic_node(id);


--
-- Name: semantic_edge_key semantic_edge_key_edge_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_edge_key
    ADD CONSTRAINT semantic_edge_key_edge_id_fkey FOREIGN KEY (edge_id) REFERENCES public.semantic_edge(id) ON DELETE CASCADE;


--
-- Name: semantic_edge semantic_edge_to_node_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_edge
    ADD CONSTRAINT semantic_edge_to_node_id_fkey FOREIGN KEY (to_node_id) REFERENCES public.semantic_node(id);


--
-- Name: semantic_node_column semantic_node_column_node_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_node_column
    ADD CONSTRAINT semantic_node_column_node_id_fkey FOREIGN KEY (node_id) REFERENCES public.semantic_node(id) ON DELETE CASCADE;


--
-- Name: semantic_tab_view semantic_tab_view_anchor_node_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_tab_view
    ADD CONSTRAINT semantic_tab_view_anchor_node_id_fkey FOREIGN KEY (anchor_node_id) REFERENCES public.semantic_node(id);


--
-- Name: semantic_tab_view_column semantic_tab_view_column_column_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_tab_view_column
    ADD CONSTRAINT semantic_tab_view_column_column_id_fkey FOREIGN KEY (column_id) REFERENCES public.semantic_node_column(id) ON DELETE CASCADE;


--
-- Name: semantic_tab_view_column semantic_tab_view_column_view_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_tab_view_column
    ADD CONSTRAINT semantic_tab_view_column_view_id_fkey FOREIGN KEY (view_id) REFERENCES public.semantic_tab_view(id) ON DELETE CASCADE;


--
-- Name: semantic_tab_view_node semantic_tab_view_node_node_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_tab_view_node
    ADD CONSTRAINT semantic_tab_view_node_node_id_fkey FOREIGN KEY (node_id) REFERENCES public.semantic_node(id);


--
-- Name: semantic_tab_view_node semantic_tab_view_node_view_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semantic_tab_view_node
    ADD CONSTRAINT semantic_tab_view_node_view_id_fkey FOREIGN KEY (view_id) REFERENCES public.semantic_tab_view(id) ON DELETE CASCADE;


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
-- Name: template_component_snapshot template_component_snapshot_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template_component_snapshot
    ADD CONSTRAINT template_component_snapshot_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.template(id) ON DELETE CASCADE;


--
-- Name: template_component template_component_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template_component
    ADD CONSTRAINT template_component_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.template(id) ON DELETE CASCADE;


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


-- ============================================================
-- 种子数据(系统配置 + admin 用户 + Flyway 基线)
-- ============================================================

-- ---- user: 仅 admin / SYSTEM_ADMIN / ACTIVE ----
-- 密码 Admin@2026(bcrypt cost=12, 由源库 cpq_db_0724 原样搬运, 未重新生成)
-- is_first_login=false + initial_password_expires_at=NULL => 首次登录不强制改密
INSERT INTO public."user" (id, username, full_name, email, password_hash, role, region_id,
                            department_id, status, is_first_login, initial_password_expires_at,
                            failed_login_attempts, locked_until, created_at, updated_at)
VALUES ('d1e1147c-a639-4156-aeac-9f938a65ad05', 'admin', '系统管理员', 'admin@cpq-system.com',
        '$2a$12$CRHCBgs/EBRKF8rZtSY34.YiJIe6uU0EquS8DUA3q97Rf2oSS0RCW',
        'SYSTEM_ADMIN', NULL, NULL, 'ACTIVE', false, NULL, 0, NULL, now(), now());

-- ---- price_adjust_settings: 1 行(Flyway 迁移种子) ----
INSERT INTO public.price_adjust_settings (id, subtotal_guard_threshold, updated_at, updated_by, subtotal_guard_enabled) VALUES (1, 0.010000, '2026-09-09 09:45:45.158589+00', NULL, false);

-- ---- sel_param_type: 3 行(Flyway 迁移种子) ----
INSERT INTO public.sel_param_type (code, name, value_mode, data_source_key, persist_handler_key, sort_order) VALUES ('MATERIAL', '材质', 'single', 'MATERIAL_RECIPE', 'MATERIAL_RECIPE_BIND', 1);
INSERT INTO public.sel_param_type (code, name, value_mode, data_source_key, persist_handler_key, sort_order) VALUES ('ELEMENT', '元素含量', 'adjust', NULL, 'ELEMENT_OVERRIDE', 2);
INSERT INTO public.sel_param_type (code, name, value_mode, data_source_key, persist_handler_key, sort_order) VALUES ('PROCESS', '工序', 'multi', 'V6_PROCESS_MASTER', 'PROCESS_LIST', 3);

-- ---- costing_bom_tree_config: 3 行(Flyway 迁移种子) ----
INSERT INTO public.costing_bom_tree_config (id, name, sql_template, is_active, created_at, updated_at, usage) VALUES ('82612f2b-558a-4b13-b052-af08100573ac', '核价BOM树-PRICING口径v1(versionFilter 版本感知)', 'WITH RECURSIVE bom AS (
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
FROM bom', true, '2026-07-27 01:13:36.230598+00', '2026-07-27 01:13:36.230598+00', 'COSTING');
INSERT INTO public.costing_bom_tree_config (id, name, sql_template, is_active, created_at, updated_at, usage) VALUES ('537d2267-ccf2-4453-bc30-a9cee741e153', '基础核价BOM树-COST_BASIC-v1', '-- 基础核价 BOM 树骨架（usage=COST_BASIC，task-260909 B-9）
--
-- 三列的料号空间不同，改之前先看清楚：
--   root_no                 = 销售料号（种子原值，恒不翻译）
--   material_no / parent_no = 生产料号（翻译后）
--   node_path               = 生产料号拼接
-- root_no 必须留在销售料号空间：BomTreeRenderService 的 rootToLineItemIds 按
-- quotation_line_item.product_part_no_snapshot（销售料号）建键，CostingTreeGrouping 又按本 SQL
-- 输出的 root_no 分组 —— 输出成生产料号会让该卡片的料号集合变成空集，整张卡片一行都渲不出来，
-- 而且不报任何错。
--
-- 客户隔离只在种子处发生一次（子查询里的 customer_no 等值条件）：核价三张基础表本身没有
-- customer_no 维度，销售到生产的翻译是全树唯一的客户接触点。
--
-- 注意：版本宏的名字不要出现在注释里 —— VersionFilterMacro 是在【原文】上扫描的（不屏蔽注释），
-- 注释里写一次就会被当成一处真实宏去解析实参，直接把整段 SQL 解坏。
WITH RECURSIVE bom AS (
  -- 种子：本单成品的销售料号 → 生产料号，整棵树里唯一一次翻译
  SELECT
    s.sales_no                                     AS root_no,
    s.production_no::text                          AS material_no,
    (SELECT bv.version_no::text
       FROM v_ds_cost_basic_material_bom_all bv
      WHERE bv.production_no = s.production_no
        AND :versionFilter(bv.is_current, bv.version_no::text, bv.production_no)
      LIMIT 1)                                     AS bom_version,
    NULL::text                                     AS parent_no,
    s.production_no::text                          AS node_path
  FROM (
    SELECT DISTINCT p::text AS sales_no, dqm.production_no
      FROM unnest(:production_part_nos) AS p
      JOIN ds_quote_material dqm
        ON dqm.material_no = p
       AND dqm.customer_no = :customerCode
     WHERE dqm.production_no IS NOT NULL
  ) s

  UNION ALL

  -- 递归体：父 = production_no，子 = component_no（核价 BOM 全程生产料号，无客户维度）
  -- bom_version 取【这条边所属 BOM 的版本】= ch.version_no，与版本宏的版本维度（按父件
  -- production_no 分档）同源；不要改成"子件自己那张 BOM 的版本"，那会让版本列与版本切换的
  -- 作用对象对不上。
  SELECT
    b.root_no,
    ch.component_no::text                          AS material_no,
    ch.version_no::text                            AS bom_version,
    ch.production_no::text                         AS parent_no,
    (b.node_path || ''/'' || ch.component_no)::text  AS node_path
  FROM v_ds_cost_basic_material_bom_all ch
  JOIN bom b ON ch.production_no = b.material_no
  WHERE :versionFilter(ch.is_current, ch.version_no::text, ch.production_no)
    AND ch.component_no IS NOT NULL
) CYCLE material_no SET is_cyc USING cyc_path
SELECT root_no, material_no, bom_version, parent_no, node_path
FROM bom
', true, '2026-09-09 18:24:47.323567+00', '2026-09-10 00:32:41.239176+00', 'COST_BASIC');
INSERT INTO public.costing_bom_tree_config (id, name, sql_template, is_active, created_at, updated_at, usage) VALUES ('d6defaa0-354f-4e92-8e89-4bc8454888c3', '报价BOM树-QUOTE口径v1', 'WITH RECURSIVE bom AS (
  SELECT p::text AS root_no, p::text AS material_no,
    NULL::text AS bom_version,
    NULL::text AS parent_no, p::text AS node_path,
    COALESCE(:customerCode::varchar,
      (SELECT bc.customer_no FROM ds_quote_material_bom bc WHERE bc.material_no=p ORDER BY bc.customer_no LIMIT 1)) AS _cust
  FROM unnest(:production_part_nos) AS p
  UNION ALL
  SELECT b.root_no, ch.input_material_no::text,
    NULL::text,
    ch.material_no::text, (b.node_path||''/''||ch.input_material_no)::text, b._cust
  FROM ds_quote_material_bom ch JOIN bom b ON ch.material_no=b.material_no AND ch.customer_no=b._cust
  WHERE ch.input_material_no IS NOT NULL
) CYCLE material_no SET is_cyc USING cyc_path
SELECT root_no, material_no, bom_version, parent_no, node_path FROM bom', true, '2026-07-27 01:13:36.230598+00', '2026-09-09 17:59:16.237803+00', 'QUOTE');

-- ---- semantic_node: 57 行(Flyway 迁移种子) ----
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('a5a429c2-cbd6-55aa-9de2-edaf75ff72c0', 'MATERIAL', '物料', '物料', 'SHEET', 'ds_quote_material', 'FULL', 'dqm.material_no', '{}', NULL, NULL, NULL, NULL, 'QUOTE', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('fd01d644-d2ab-561a-ac26-c50cf8124f31', 'MATERIAL_BOM', '物料BOM', '物料BOM', 'SHEET', 'ds_quote_material_bom', 'FULL', 'dqmb.material_no', '{input_material_no}', NULL, NULL, NULL, NULL, 'QUOTE', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('e77545bb-55a9-5a52-be7f-b7be932020ea', 'ELEMENT_BOM', '物料与元素BOM', '物料与元素BOM', 'SHEET', 'ds_quote_element_bom', 'FULL', 'dqeb.material_no', '{material_part_no,element_code}', NULL, NULL, NULL, NULL, 'QUOTE', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('38f4367d-ae0f-5a8e-850e-d0e56506a709', 'INCOMING_FIXED_FEE', '来料固定加工费', '来料固定加工费', 'SHEET', 'ds_quote_incoming_fixed_fee', 'FULL', 'dqiff.material_no', '{input_material_no}', NULL, NULL, NULL, NULL, 'QUOTE', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('2e300dc7-c63c-56f4-97e6-b739b5730501', 'INCOMING_OTHER_FEE', '来料其他费用', '来料其他费用', 'SHEET', 'ds_quote_incoming_other_fee', 'FULL', 'dqiof.material_no', '{input_material_no}', NULL, NULL, NULL, NULL, 'QUOTE', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('6bdfd52b-1146-58a1-aef3-2b54383649cf', 'INCOMING_RECOVERY', '来料回收折扣', '来料回收折扣', 'SHEET', 'ds_quote_incoming_recovery', 'FULL', 'dqir.material_no', '{input_material_no}', NULL, NULL, NULL, NULL, 'QUOTE', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('75ccb22f-1b39-5382-902d-bb48a6d10303', 'SELF_PROCESS_FEE', '自制加工费', '自制加工费', 'SHEET', 'ds_quote_self_process_fee', 'FULL', 'dqspf.material_no', '{input_material_no}', NULL, NULL, NULL, NULL, 'QUOTE', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('0fe87f1b-6706-56a4-9289-17df2b6aae5e', 'FINISHED_OTHER_FEE', '成品其他费用', '成品其他费用', 'SHEET', 'ds_quote_finished_other_fee', 'FULL', 'dqfof.material_no', '{element_name}', NULL, NULL, NULL, NULL, 'QUOTE', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('ebfadcce-598f-54ca-9cb0-ea8871cd3f7a', 'SUB_COMPONENT_FEE', '组成件其他费用', '组成件其他费用', 'SHEET', 'ds_quote_sub_component_fee', 'FULL', 'dqscf.material_no', '{sub_component_no,supplier_no,supplier_name}', NULL, NULL, NULL, NULL, 'QUOTE', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('2368a39c-75f2-542a-942c-916e34da8e70', 'ASSEMBLY_FEE', '组装加工费', '组装加工费', 'SHEET', 'ds_quote_assembly_fee', 'FULL', 'dqaf.material_no', '{assembly_operation}', NULL, NULL, NULL, NULL, 'QUOTE', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('4dfd7565-b6cf-56d6-b3ff-b98eaeca18ae', 'PLATING_FEE', '电镀费用', '电镀费用', 'SHEET', 'ds_quote_plating_fee', 'FULL', 'dqpf.material_no', '{plating_scheme_no,plating_version}', NULL, NULL, NULL, NULL, 'QUOTE', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('801e3e6f-a628-55a6-a9f9-324fbba25d22', 'PLATING_SCHEME', '电镀方案', '电镀方案', 'SHEET', 'ds_quote_plating_scheme', 'NONE', 'dqps.scheme_no', '{}', NULL, NULL, NULL, NULL, 'QUOTE', '孤儿节点：不挂任何页签（无轴列、也不是费用），仅登记以保持 45 张主表的完整可见性', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('7a85578d-ab5e-5b2e-9ea7-0da8d4a6c864', 'MATERIAL', '物料', '物料', 'SHEET', 'ds_cost_basic_material', 'FULL', 'dcbm.production_no', '{}', NULL, NULL, NULL, NULL, 'COST_BASIC', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('b9a3018d-6536-5340-b54d-39069f8dfd61', 'MATERIAL_BOM', '物料BOM', '物料BOM', 'SHEET', 'v_ds_cost_basic_material_bom_all', 'FULL', 'vdcbmba.production_no', '{component_no,operation_no,usage_characteristic}', NULL, NULL, NULL, NULL, 'COST_BASIC', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('531951d0-cb82-5925-a127-706821bfc8d1', 'ELEMENT_BOM', '物料与元素BOM', '物料与元素BOM', 'SHEET', 'v_ds_cost_basic_element_bom_all', 'FULL', 'vdcbeba.production_no', '{material_part_no,element_code}', NULL, NULL, NULL, NULL, 'COST_BASIC', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('d5764c1d-f2a7-510a-8853-2cf84feb4463', 'INCOMING_PROCESS_FEE', '来料加工费', '来料加工费', 'SHEET', 'v_ds_cost_basic_incoming_process_fee_all', 'FULL', 'vdcbipfa.production_no', '{incoming_material_no}', NULL, NULL, NULL, NULL, 'COST_BASIC', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('5f0373f8-500c-5c37-8cad-da453bfd0250', 'INCOMING_OTHER_FEE', '来料其他费用', '来料其他费用', 'SHEET', 'v_ds_cost_basic_incoming_other_fee_all', 'FULL', 'vdcbiofa.production_no', '{incoming_material_no}', NULL, NULL, NULL, NULL, 'COST_BASIC', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('1032a3fe-5e51-5a9c-ade8-14d73706237c', 'INCOMING_OTHER_FIXED_FEE', '来料其他固定费用', '来料其他固定费用', 'SHEET', 'v_ds_cost_basic_incoming_other_fixed_fee_all', 'FULL', 'vdcbioffa.production_no', '{incoming_material_no}', NULL, NULL, NULL, NULL, 'COST_BASIC', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('4fc0c31b-e623-56c3-986d-5d0a5af60181', 'PROCESS_ASSEMBLY_FEE', '加工费&组装费', '加工费&组装费', 'SHEET', 'v_ds_cost_basic_process_assembly_fee_all', 'FULL', 'vdcbpafa.production_no', '{operation_no}', NULL, NULL, NULL, NULL, 'COST_BASIC', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('3d0bc5c6-200a-5a24-b505-f36df668cd02', 'OUTSOURCED_PROCESS', '其他外加工成本', '其他外加工成本', 'SHEET', 'v_ds_cost_basic_outsourced_process_all', 'FULL', 'vdcbopa.production_no', '{operation_no}', NULL, NULL, NULL, NULL, 'COST_BASIC', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('026543e0-4815-54c5-a69e-78884c73c26f', 'FINISHED_RATIO_FEE', '成品其他比例费用', '成品其他比例费用', 'SHEET', 'v_ds_cost_basic_finished_ratio_fee_all', 'FULL', 'vdcbfrfa.production_no', '{}', NULL, NULL, NULL, NULL, 'COST_BASIC', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('c3a6e4fe-8488-5c76-874a-c410da2913fc', 'FINISHED_FIXED_FEE', '成品其他固定费用', '成品其他固定费用', 'SHEET', 'v_ds_cost_basic_finished_fixed_fee_all', 'FULL', 'vdcbfffa.production_no', '{element_name}', NULL, NULL, NULL, NULL, 'COST_BASIC', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('35d18fcf-cf27-573c-9cf7-2c9b2bf9e13a', 'MATERIAL', '物料', '物料', 'SHEET', 'ds_cost_detail_material', 'FULL', 'dcdm.production_no', '{}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('bce970df-428e-55d0-b799-07853b82aa5c', 'MATERIAL_BOM', '物料BOM', '物料BOM', 'SHEET', 'v_ds_cost_detail_material_bom_all', 'FULL', 'vdcdmba.production_no', '{component_no,operation_no,usage_characteristic}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('52784f35-2b0f-5aa1-a0f8-a6f0346eb025', 'ELEMENT_BOM', '物料与元素BOM', '物料与元素BOM', 'SHEET', 'v_ds_cost_detail_element_bom_all', 'FULL', 'vdcdeba.production_no', '{material_part_no,element_code}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('b44cbbe9-11a8-52d4-b82d-f7b81d0a7cb4', 'CAPACITY', '产能', '产能', 'SHEET', 'v_ds_cost_detail_capacity_all', 'FULL', 'vdcdca.production_no', '{operation_no}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('fe9b5007-844b-5551-8c03-8ac017ad1f2a', 'DEPRECIATION', '设备折旧成本', '设备折旧成本', 'SHEET', 'v_ds_cost_detail_depreciation_all', 'FULL', 'vdcdda.production_no', '{operation_no}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('191da8dc-59f4-56de-8541-170e792c253e', 'PRODUCTION_ENERGY', '生产设备能耗', '生产设备能耗', 'SHEET', 'v_ds_cost_detail_production_energy_all', 'FULL', 'vdcdpea.production_no', '{operation_no}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('c9780071-c1ed-5cd8-8dc6-b852a0defe39', 'AUXILIARY_ENERGY', '辅助设备能耗', '辅助设备能耗', 'SHEET', 'v_ds_cost_detail_auxiliary_energy_all', 'FULL', 'vdcdaea.production_no', '{operation_no}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('3d4988fb-c7ac-51ee-ba37-7dfa61e18148', 'TOOLING', '模具工装成本', '模具工装成本', 'SHEET', 'v_ds_cost_detail_tooling_all', 'FULL', 'vdcdta.production_no', '{operation_no,tooling_no}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('06aa16e3-ef35-5a60-8132-8d2257834dc4', 'CONSUMABLE', '生产耗材BOM', '生产耗材BOM', 'SHEET', 'v_ds_cost_detail_consumable_all', 'FULL', 'vdcdca.production_no', '{operation_no}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('ce1f43ff-c1b3-554a-b7e0-7d60cf70ddbb', 'PACKAGING', '包装材料BOM', '包装材料BOM', 'SHEET', 'v_ds_cost_detail_packaging_all', 'FULL', 'vdcdpa.production_no', '{operation_no}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('548027b5-43af-5c2b-969a-cc51abd5ca5c', 'INCOMING_PROCESS_FEE', '来料加工费', '来料加工费', 'SHEET', 'v_ds_cost_detail_incoming_process_fee_all', 'FULL', 'vdcdipfa.production_no', '{incoming_material_no}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('0547bede-2926-5518-bcbc-927bcad086b2', 'INCOMING_OTHER_FEE', '来料其他费用', '来料其他费用', 'SHEET', 'v_ds_cost_detail_incoming_other_fee_all', 'FULL', 'vdcdiofa.production_no', '{incoming_material_no}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('345562dc-5eb3-5789-aa24-2d01b3c32f7d', 'INCOMING_OTHER_FIXED_FEE', '来料其他固定费用', '来料其他固定费用', 'SHEET', 'v_ds_cost_detail_incoming_other_fixed_fee_all', 'FULL', 'vdcdioffa.production_no', '{incoming_material_no}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('a42b6cd0-9fde-5616-9566-8be8c50e870b', 'PROCESS_ASSEMBLY_FEE', '加工费&组装费', '加工费&组装费', 'SHEET', 'v_ds_cost_detail_process_assembly_fee_all', 'FULL', 'vdcdpafa.production_no', '{operation_no}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('8238839a-e1e7-5e8f-b315-c8e976cebd1f', 'OUTSOURCED_PROCESS', '其他外加工成本', '其他外加工成本', 'SHEET', 'v_ds_cost_detail_outsourced_process_all', 'FULL', 'vdcdopa.production_no', '{operation_no}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('3a0fdb6d-86c9-5838-a4cf-ec25f08f7b38', 'PLATING_COST', '电镀成本', '电镀成本', 'SHEET', 'v_ds_cost_detail_plating_cost_all', 'FULL', 'vdcdpca.production_no', '{plating_scheme_no,plating_version}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('8b480f2d-8a0e-5824-a417-34d53a04b916', 'FINISHED_RATIO_FEE', '成品其他比例费用', '成品其他比例费用', 'SHEET', 'v_ds_cost_detail_finished_ratio_fee_all', 'FULL', 'vdcdfrfa.production_no', '{}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('ff7b6f55-70c2-59f3-a1ea-f3f021e3d49a', 'FINISHED_FIXED_FEE', '成品其他固定费用', '成品其他固定费用', 'SHEET', 'v_ds_cost_detail_finished_fixed_fee_all', 'FULL', 'vdcdfffa.production_no', '{}', NULL, NULL, NULL, NULL, 'COST_DETAIL', NULL, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('3369e20a-fcca-5a9c-adf0-70a1a108daa9', 'PLATING_SCHEME', '电镀方案', '电镀方案', 'SHEET', 'ds_cost_detail_plating_scheme', 'NONE', 'dcdps.scheme_no', '{}', NULL, NULL, NULL, NULL, 'COST_DETAIL', '孤儿节点：不挂任何页签（无轴列、也不是费用），仅登记以保持 45 张主表的完整可见性', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'QUOTE_MATERIAL_BRIDGE', '报价物料（料号桥）', '料号桥', 'LOOKUP', 'ds_quote_material', 'NONE', NULL, '{}', NULL, NULL, NULL, NULL, 'COST_BASIC', 'S-24/D-76：核价侧轴是生产料号，报价单行给的是销售料号 —— 本节点把两者接起来。LOOKUP ⇒ 编译成 LEFT JOIN：桥里没有对应行时返回 0 行/NULL，**不抛异常**（AC-112）。⚠️ 两表无外键，是文本列匹配；production_no 为空是正常业务状态（报价时生产料号可能还没定，后期维护且可改），表现为「未关联核价数据」', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'QUOTE_MATERIAL_BRIDGE', '报价物料（料号桥）', '料号桥', 'LOOKUP', 'ds_quote_material', 'NONE', NULL, '{}', NULL, NULL, NULL, NULL, 'COST_DETAIL', 'S-24/D-76：核价侧轴是生产料号，报价单行给的是销售料号 —— 本节点把两者接起来。LOOKUP ⇒ 编译成 LEFT JOIN：桥里没有对应行时返回 0 行/NULL，**不抛异常**（AC-112）。⚠️ 两表无外键，是文本列匹配；production_no 为空是正常业务状态（报价时生产料号可能还没定，后期维护且可改），表现为「未关联核价数据」', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4', 'ANNUAL_DISCOUNT', '年降系数', '年降系数', 'SHEET', 'ds_quote_annual_discount', 'FULL', 'dqad.material_no', '{discount_seq}', NULL, NULL, NULL, NULL, 'QUOTE', 'task-260907 B-2（F-4）：独立数据源。🚫 不挂成「物料」的 AUX —— 与物料是 1:N（带 discount_seq 档次），挂上去会把物料 47 行放大成 47×N（AC-11④）。', 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('9901a057-2883-5893-9e89-c6232a0997fe', 'ASSEMBLY_FEE_ANNUAL', '组装加工费年降', '组装加工费年降', 'SHEET', 'ds_quote_assembly_fee_annual', 'FULL', 'dqafa.material_no', '{assembly_operation,discount_seq}', NULL, NULL, NULL, NULL, 'QUOTE', 'task-260907 B-2（F-4）：独立数据源，同上 1:N 理由。', 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('817fe5f7-66df-5551-b85c-762cf908281b', 'INCOMING_ANNUAL', '来料年降', '来料年降', 'SHEET', 'ds_quote_incoming_annual', 'FULL', 'dqia.material_no', '{input_material_no,discount_seq}', NULL, NULL, NULL, NULL, 'QUOTE', 'task-260907 B-2（F-4）：独立数据源，同上 1:N 理由。', 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('613b7811-0156-5ec9-b851-40a1579d0734', 'CUSTOMER_PART', '客户料号', '客户料号', 'SHEET', 'ds_quote_customer_part', 'NONE', NULL, '{}', 'customer_no = :customerCode', NULL, NULL, NULL, 'QUOTE', 'task-260907 B-1（F-1）：客户料号表。不作为页签锚点，只以 AUX 挂在 QUOTE/主件 上，经 MATERIAL 的 LOOKUP 边 LEFT JOIN 带出（物料为主，用户 2026-09-07 裁决）。
【V419 / AC-2】按客户隔离：fixed_predicate = customer_no = :customerCode。⚠️ 只消跨客户那一半；同一客户下一个 material_no 挂多个 customer_product_no 仍会放大行数（表的唯一键就是 (customer_no, customer_product_no)，1:N 合法）。', 'seed', '2026-09-07 02:41:01.285968', 'task-260907', '2026-09-07 03:19:37.194248', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('ddc7fafa-0fd3-5c47-a86c-e3f8b86b0f76', 'FUNC_ELEMENT_PRICE', '价格策略 f_material_element_price', '价格策略', 'FUNCTION', NULL, 'NONE', NULL, '{}', NULL, 'f_material_element_price(:customerCode, :priceBaseDate)', NULL, NULL, 'QUOTE', '别名固定为 cep（AC-1 铁律）；不是 f_customer_element_price。repair-260909 起三方言都挂：原注「核价侧语义对不上，不硬接」说过头了——真实卡点只是【连接键不在核价视图里】，已由 v_ds_cost_*_element_bom_all.sales_material_no 桥接解决。用户 2026-09-09 裁决 A0-1：核价与报价对同一 (客户,料号,元素) 必须给出同一个数。', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-09 02:34:26.116236', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('26090801-0000-4000-8000-000000000001', 'MAT_NAME_LK', '物料（查名）', '物料', 'LOOKUP', 'ds_quote_material', 'NONE', NULL, '{}', NULL, NULL, NULL, NULL, 'QUOTE', 'task-260908 B-1：报价侧材料名查名（瘦节点，只声明 material_name 一列）', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('26090801-0000-4000-8000-000000000002', 'MAT_PROD_LK', '物料（生产料号）', '物料', 'LOOKUP', 'ds_quote_material', 'NONE', NULL, '{}', NULL, NULL, NULL, NULL, 'QUOTE', 'task-260908 B-1：报价 BOM 的生产料号查名。与 MAT_NAME_LK 同表但【连接键不同】，必须独立成节点', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('26090801-0000-4000-8000-000000000003', 'RECIPE_NAME_LK', '材质（查名）', '材质', 'LOOKUP', 'material_recipe', 'NONE', NULL, '{}', NULL, NULL, NULL, NULL, 'QUOTE', 'task-260908 B-1：材质符号作材料名（COALESCE 第二分支）', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('26090801-0000-4000-8000-000000000004', 'MAT_NAME_LK', '物料（查名）', '物料', 'LOOKUP', 'ds_cost_basic_material', 'NONE', NULL, '{}', NULL, NULL, NULL, NULL, 'COST_BASIC', 'task-260908 B-1：基础核价材料名查名', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('26090801-0000-4000-8000-000000000005', 'RECIPE_NAME_LK', '材质（查名）', '材质', 'LOOKUP', 'material_recipe', 'NONE', NULL, '{}', NULL, NULL, NULL, NULL, 'COST_BASIC', 'task-260908 B-1：基础核价材质查名', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('26090801-0000-4000-8000-000000000006', 'MAT_NAME_LK', '物料（查名）', '物料', 'LOOKUP', 'ds_cost_detail_material', 'NONE', NULL, '{}', NULL, NULL, NULL, NULL, 'COST_DETAIL', 'task-260908 B-1：明细核价材料名查名', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('26090801-0000-4000-8000-000000000007', 'RECIPE_NAME_LK', '材质（查名）', '材质', 'LOOKUP', 'material_recipe', 'NONE', NULL, '{}', NULL, NULL, NULL, NULL, 'COST_DETAIL', 'task-260908 B-1：明细核价材质查名', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('26090901-0000-4000-8000-000000000001', 'FUNC_ELEMENT_PRICE', '价格策略 f_material_element_price', '价格策略', 'FUNCTION', NULL, 'NONE', NULL, '{}', NULL, 'f_material_element_price(:customerCode, :priceBaseDate)', NULL, NULL, 'COST_BASIC', 'repair-260909：别名固定为 cep。按 A0-1（用户 2026-09-09 裁决）核价与报价必须对同一 (客户,料号,元素) 给出同一个数 ⇒ 走与报价侧完全相同的函数与双键；销售料号由 v_ds_cost_basic_element_bom_all.sales_material_no 桥接（LATERAL LIMIT 1）', NULL, '2026-09-09 02:34:26.116236', NULL, '2026-09-09 02:34:26.116236', 'ACTIVE');
INSERT INTO public.semantic_node (id, node_key, display_name, short_name, node_kind, physical_table, scope, anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator, source_handler, dialect, note, created_by, created_at, updated_by, updated_at, status) VALUES ('26090901-0000-4000-8000-000000000002', 'FUNC_ELEMENT_PRICE', '价格策略 f_material_element_price', '价格策略', 'FUNCTION', NULL, 'NONE', NULL, '{}', NULL, 'f_material_element_price(:customerCode, :priceBaseDate)', NULL, NULL, 'COST_DETAIL', 'repair-260909：同 COST_BASIC，桥接列在 v_ds_cost_detail_element_bom_all.sales_material_no', NULL, '2026-09-09 02:34:26.116236', NULL, '2026-09-09 02:34:26.116236', 'ACTIVE');

-- ---- semantic_node_column: 366 行(Flyway 迁移种子) ----
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('72d3b838-096a-56f1-8cfd-fe72a9c689ef', '613b7811-0156-5ec9-b851-40a1579d0734', 'customer_no', '客户编号', 'TEXT', true, '{}', 0, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('9284c444-6fa2-52f9-890c-f78bf2028893', '613b7811-0156-5ec9-b851-40a1579d0734', 'customer_part_name', '客户料号名称', 'TEXT', false, '{}', 1, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('b781bb7d-c65b-5215-9fc6-cfb6a521881a', '613b7811-0156-5ec9-b851-40a1579d0734', 'customer_product_no', '客户产品编号', 'TEXT', true, '{}', 2, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d5f3e1db-2333-5f81-a79a-5e2dd94065c9', '613b7811-0156-5ec9-b851-40a1579d0734', 'customer_drawing_no', '客户图号', 'TEXT', false, '{}', 3, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('89c52cb1-cf40-56a5-bcfa-f19bf581baca', '613b7811-0156-5ec9-b851-40a1579d0734', 'material_no', '销售料号', 'TEXT', true, '{}', 4, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('223c8f1e-b4af-5b49-8800-7deab6b84f08', '95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4', 'material_no', '销售料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('321d07b8-1151-54b5-b58a-51ef14989109', '95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4', 'discount_seq', '年降顺序', 'NUMBER', false, '{ROW_KEY}', 1, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('6415984d-16d3-5aac-82c5-323880fe1b35', '95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4', 'discount_rate', '年降系数（%/年）', 'NUMBER', false, '{}', 2, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f8b1b2fa-5b88-5cbf-955a-53e534114078', '95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4', 'fixed_discount_value', '单次固定年降金额', 'NUMBER', false, '{}', 3, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('9ccac41e-d914-5649-9610-d07ab3e41277', '95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4', 'currency', '货币', 'TEXT', false, '{}', 4, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('12c457f5-7eec-549e-a78e-9cf69eea694b', '95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4', 'pricing_unit', '计价单位', 'TEXT', false, '{}', 5, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('07642560-908c-54ac-9ea9-24aa3897aff6', '95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4', 'discount_times', '降价次数', 'NUMBER', false, '{}', 6, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('18c7b822-b28f-543e-983a-b34a2a2bbde9', '9901a057-2883-5893-9e89-c6232a0997fe', 'material_no', '销售料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('bea226b4-16dc-5315-823a-2b35053a6115', '9901a057-2883-5893-9e89-c6232a0997fe', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d7354193-1286-550f-a3ed-1cbdab52efc8', '9901a057-2883-5893-9e89-c6232a0997fe', 'assembly_operation', '组装工序', 'TEXT', true, '{ROW_KEY}', 2, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ae2d5779-f796-5862-9571-73fb56a2bc8f', '9901a057-2883-5893-9e89-c6232a0997fe', 'discount_seq', '年降顺序', 'NUMBER', false, '{ROW_KEY}', 3, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('a1be9f5a-c036-55a5-b9f2-631867f6d2b9', '9901a057-2883-5893-9e89-c6232a0997fe', 'discount_rate', '年降系数（%）', 'NUMBER', false, '{}', 4, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('56cff7de-b5ac-549c-a22d-2f8948b12a58', '9901a057-2883-5893-9e89-c6232a0997fe', 'fixed_discount_value', '单次固定年降值', 'NUMBER', false, '{}', 5, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('c6cb4378-5507-5302-84e6-161cd30e7bf6', '9901a057-2883-5893-9e89-c6232a0997fe', 'currency', '货币', 'TEXT', false, '{}', 6, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('88856aab-76a4-5b34-b64c-99316e304c59', '9901a057-2883-5893-9e89-c6232a0997fe', 'pricing_unit', '计价单位', 'TEXT', false, '{}', 7, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('5d3ec16d-d9ca-587e-ad92-9797b4731a89', '9901a057-2883-5893-9e89-c6232a0997fe', 'discount_times', '降价次数', 'NUMBER', false, '{}', 8, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('dff33dc3-3781-5b63-bb32-ce0b6026843c', '817fe5f7-66df-5551-b85c-762cf908281b', 'material_no', '销售料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('308a55a2-eb8f-5e99-a405-203b087e1e63', '817fe5f7-66df-5551-b85c-762cf908281b', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('baf24fad-ef31-5e3c-b566-583bf7340f1b', '817fe5f7-66df-5551-b85c-762cf908281b', 'input_material_no', '投入料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 2, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('93f1b572-a72a-5416-8070-d1630ef062e2', '817fe5f7-66df-5551-b85c-762cf908281b', 'discount_seq', '年降顺序', 'NUMBER', false, '{ROW_KEY}', 3, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ac27a3fd-85f4-5f66-977c-142d6f21c8fc', '817fe5f7-66df-5551-b85c-762cf908281b', 'discount_rate', '年降系数（%）', 'NUMBER', false, '{}', 4, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('c80e533f-049c-5192-b22a-7fc477be80d7', '817fe5f7-66df-5551-b85c-762cf908281b', 'fixed_discount_value', '单次固定年降值', 'NUMBER', false, '{}', 5, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('cd0dc13c-c8a5-5560-a4d8-656bf43d7ded', '817fe5f7-66df-5551-b85c-762cf908281b', 'currency', '货币', 'TEXT', false, '{}', 6, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('e2a60b10-6e94-55a8-b382-82eb1877c318', '817fe5f7-66df-5551-b85c-762cf908281b', 'pricing_unit', '计价单位', 'TEXT', false, '{}', 7, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('e0831a7c-ae66-50e8-ad0c-1d8eacda112a', '817fe5f7-66df-5551-b85c-762cf908281b', 'discount_times', '降价次数', 'NUMBER', false, '{}', 8, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('26090802-0000-4000-8000-000000000001', '26090801-0000-4000-8000-000000000001', 'material_name', '材料名', 'TEXT', false, '{PART_NAME}', 0, NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('26090802-0000-4000-8000-000000000002', '26090801-0000-4000-8000-000000000002', 'production_no', '生产料号', 'TEXT', false, '{}', 0, NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('26090802-0000-4000-8000-000000000003', '26090801-0000-4000-8000-000000000003', 'symbol', '材料名', 'TEXT', false, '{PART_NAME}', 0, NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('26090802-0000-4000-8000-000000000004', '26090801-0000-4000-8000-000000000004', 'material_name', '材料名', 'TEXT', false, '{PART_NAME}', 0, NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('26090802-0000-4000-8000-000000000005', '26090801-0000-4000-8000-000000000005', 'symbol', '材料名', 'TEXT', false, '{PART_NAME}', 0, NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('26090802-0000-4000-8000-000000000006', '26090801-0000-4000-8000-000000000006', 'material_name', '材料名', 'TEXT', false, '{PART_NAME}', 0, NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('26090802-0000-4000-8000-000000000007', '26090801-0000-4000-8000-000000000007', 'symbol', '材料名', 'TEXT', false, '{PART_NAME}', 0, NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ae67400f-c820-5f59-8fbe-d3f37117bc3d', 'b9a3018d-6536-5340-b54d-39069f8dfd61', 'operation_no', '工序编号', 'TEXT', true, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('03a33fc5-7f56-5691-b7ad-de4404a51303', 'b9a3018d-6536-5340-b54d-39069f8dfd61', 'usage_characteristic', '使用特性', 'TEXT', true, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('17d5dfb6-7fa2-5d1c-99de-953dad550a38', 'bce970df-428e-55d0-b799-07853b82aa5c', 'operation_no', '工序编号', 'TEXT', true, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('cf946325-28bc-542f-bf21-5320bd452f9c', 'bce970df-428e-55d0-b799-07853b82aa5c', 'usage_characteristic', '使用特性', 'TEXT', true, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d311a78c-7951-56a5-bc6f-96b441395040', '026543e0-4815-54c5-a69e-78884c73c26f', 'element_name', '要素名称', 'TEXT', false, '{ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('67bd43b7-32e6-5a68-bc7b-9507af6f53ae', '8b480f2d-8a0e-5824-a417-34d53a04b916', 'element_name', '要素名称', 'TEXT', false, '{ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('682a1433-9c94-56ee-a8cf-ec0bad1da636', 'ff7b6f55-70c2-59f3-a1ea-f3f021e3d49a', 'element_name', '要素名称', 'TEXT', false, '{ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('7879c6c9-91ac-597c-8ce6-b0f87f765cef', '5f0373f8-500c-5c37-8cad-da453bfd0250', 'element_name', '要素名称', 'TEXT', false, '{ROW_KEY}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('6e478381-53b1-525e-9c3b-ce7dc55b8dc3', '1032a3fe-5e51-5a9c-ade8-14d73706237c', 'element_name', '要素名称', 'TEXT', false, '{ROW_KEY}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('e1e2a4bb-61a1-5d7a-8aca-1a9899a85cbf', '0547bede-2926-5518-bcbc-927bcad086b2', 'element_name', '要素名称', 'TEXT', false, '{ROW_KEY}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('51167f67-4b4d-586a-9815-b442ab25668d', '345562dc-5eb3-5789-aa24-2d01b3c32f7d', 'element_name', '要素名称', 'TEXT', false, '{ROW_KEY}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('681f2ce8-8901-5938-8a73-cec9a6ca676d', '3a0fdb6d-86c9-5838-a4cf-ec25f08f7b38', 'plating_scheme_no', '电镀方案编号', 'TEXT', true, '{}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('6f865d0f-90f1-5f89-b3a0-8b80559ce1b3', '3a0fdb6d-86c9-5838-a4cf-ec25f08f7b38', 'plating_version', '版本编号', 'TEXT', true, '{}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('3bb89a7b-58d5-5373-aefe-0add5fb944a8', '3d4988fb-c7ac-51ee-ba37-7dfa61e18148', 'tooling_no', '模具台账/工装编号', 'TEXT', true, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('9a81d15b-48f2-50b9-9757-050b53389c3f', 'a5a429c2-cbd6-55aa-9de2-edaf75ff72c0', 'material_no', '销售料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('bed9d611-c082-5eb4-b90d-dc757a77e071', 'a5a429c2-cbd6-55aa-9de2-edaf75ff72c0', 'category_code', '产品分类', 'TEXT', true, '{}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d604fbb3-adaf-5ea6-aea4-d7ce6836f7d2', 'a5a429c2-cbd6-55aa-9de2-edaf75ff72c0', 'material_name', '品名', 'TEXT', false, '{}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('31f576ec-862c-51dd-b841-180a36591263', 'a5a429c2-cbd6-55aa-9de2-edaf75ff72c0', 'specification', '规格', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('4209d6c9-f39f-5b4a-aae7-ef643419edd9', 'a5a429c2-cbd6-55aa-9de2-edaf75ff72c0', 'dimension', '尺寸', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('7cd00ed0-0f91-5c5a-bc27-24ec865eba80', 'a5a429c2-cbd6-55aa-9de2-edaf75ff72c0', 'old_material_no', '旧料号', 'TEXT', true, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('38b4c3cc-6a9b-5608-ab13-46943f43e3c2', 'a5a429c2-cbd6-55aa-9de2-edaf75ff72c0', 'unit_weight', '单重', 'NUMBER', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('967a0c1c-90e2-5e18-ae36-d6459a188f26', 'a5a429c2-cbd6-55aa-9de2-edaf75ff72c0', 'production_no', '生产料号', 'TEXT', true, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('8187d3e5-9b8b-5a97-9f5d-bfba4c647322', 'a5a429c2-cbd6-55aa-9de2-edaf75ff72c0', 'material_type', '类型', 'TEXT', false, '{}', 8, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('8c9f0582-ddaf-53fb-b05a-ae57d9efb3de', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', 'material_no', '销售料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('b12e0df5-51ce-5ea8-9c13-3e0823ac7f1c', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('0f48df5c-3db3-5708-a7a8-468ee83086ab', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', 'input_material_no', '投入料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d4deac04-8eeb-5f37-b552-669211368117', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', 'unit_weight', '单重', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('bf0b7049-18ce-5017-ada2-073bb19cb4c0', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', 'output_material_type', '产出料号类型', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('4cae2f7e-cb47-53e7-acc1-c6191661b022', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', 'component_qty', '组成数量', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d13e581f-d790-535d-b945-4614aa8e716f', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', 'gross_weight', '材料毛重', 'NUMBER', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('878073c2-34f9-5c42-9153-5bebef5ba3fb', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', 'net_weight', '材料净重', 'NUMBER', false, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('c62c8d68-d90e-562a-a620-e25ac36c7b08', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', 'weight_unit', '重量单位', 'TEXT', false, '{}', 8, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('07362c23-8620-5550-9c7c-1c4e0473b79d', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', 'material_ratio', '材料占比（%）', 'NUMBER', false, '{}', 9, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('9539abe3-90cc-5fe2-a10c-1293bd4d9ac3', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', 'loss_rate', '损耗率（%）', 'NUMBER', false, '{}', 10, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('187431fe-52d7-5171-81df-02cc8a2234bd', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', 'defect_rate', '不良率（%）', 'NUMBER', false, '{}', 11, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('b1350c85-696c-5414-a7c9-2290c90bdd39', 'e77545bb-55a9-5a52-be7f-b7be932020ea', 'material_no', '销售料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('1e68a2c1-4628-510a-8103-37eaefa0b638', 'e77545bb-55a9-5a52-be7f-b7be932020ea', 'material_part_no', '材质料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('79a8e46e-13ab-5912-ab77-1015cd24babe', 'e77545bb-55a9-5a52-be7f-b7be932020ea', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('84a6d536-1cfe-5ffe-ac3c-c244e60be008', 'e77545bb-55a9-5a52-be7f-b7be932020ea', 'element_code', '元素', 'TEXT', true, '{ROW_KEY}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d6db85ca-6ef7-5cd1-be24-69b92adf1eb9', 'e77545bb-55a9-5a52-be7f-b7be932020ea', 'content_pct', '组成含量（%）', 'NUMBER', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('32e07d1a-2833-5630-a921-e439b97db9af', 'e77545bb-55a9-5a52-be7f-b7be932020ea', 'loss_rate', '损耗率%', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('57c620aa-3e3a-556e-ab61-382ea779ed12', 'e77545bb-55a9-5a52-be7f-b7be932020ea', 'gross_usage', '毛用量', 'NUMBER', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('1f1770fd-bccb-55e0-94c6-5da6168b46f5', 'e77545bb-55a9-5a52-be7f-b7be932020ea', 'gross_usage_unit', '毛用量单位', 'TEXT', false, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('9e8c9bd9-1d1a-532b-9aec-4eda0cd210c2', 'e77545bb-55a9-5a52-be7f-b7be932020ea', 'net_usage', '净用量', 'NUMBER', false, '{}', 8, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('6cae932e-e04e-5324-8e09-8675b26d46ae', 'e77545bb-55a9-5a52-be7f-b7be932020ea', 'net_usage_unit', '净用量单位', 'TEXT', false, '{}', 9, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('0a1b3a95-0535-550f-9916-19078e3e894e', 'e77545bb-55a9-5a52-be7f-b7be932020ea', 'recovery_discount', '回收折扣(%)', 'NUMBER', false, '{}', 10, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('2e4c5127-be43-5750-b38c-4dc38554455a', 'e77545bb-55a9-5a52-be7f-b7be932020ea', 'recovery_qty', '回收量', 'TEXT', false, '{}', 11, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('785c9323-b0aa-51c9-8aed-783639d53530', '38f4367d-ae0f-5a8e-850e-d0e56506a709', 'material_no', '销售料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('7bf5ef30-ecd0-5586-a4b8-3073e6b27eed', '38f4367d-ae0f-5a8e-850e-d0e56506a709', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('04c830f5-7184-5592-bb71-4b790b9522f6', '38f4367d-ae0f-5a8e-850e-d0e56506a709', 'input_material_no', '投入料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ca6a13e7-1952-5932-b76b-ecc6b9f21d8b', '38f4367d-ae0f-5a8e-850e-d0e56506a709', 'base_value', '基准值', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f484d2c3-4a17-5b12-8796-db94d6a334eb', '38f4367d-ae0f-5a8e-850e-d0e56506a709', 'ratio_pct', '比例（%）', 'NUMBER', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('b6079a8d-0211-5e8b-b13d-97626d89e08c', '38f4367d-ae0f-5a8e-850e-d0e56506a709', 'currency', '货币', 'TEXT', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('45df9958-0c96-5feb-abea-fee4aa28fe70', '38f4367d-ae0f-5a8e-850e-d0e56506a709', 'pricing_unit', '计价单位', 'TEXT', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('3efe7e6e-c56d-5e08-a7fd-13e4ba0f9975', '38f4367d-ae0f-5a8e-850e-d0e56506a709', 'follow_material_price', '是否随材料价格波动', 'TEXT', false, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('8518d941-e475-5457-9142-ddd6786ec875', '38f4367d-ae0f-5a8e-850e-d0e56506a709', 'material_increase_ratio', '材料结算涨幅比例（%）', 'NUMBER', false, '{}', 8, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d6ce38c7-45c9-5ab4-b079-97b24e8ff694', '38f4367d-ae0f-5a8e-850e-d0e56506a709', 'material_increase_value', '材料固定的涨幅值', 'NUMBER', false, '{}', 9, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('78a80cc7-95ee-5499-a10b-4ca493de8ac8', '38f4367d-ae0f-5a8e-850e-d0e56506a709', 'increase_currency', '涨幅货币', 'TEXT', false, '{}', 10, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('51b378c2-3e89-5a99-9a1e-ea90d85fa671', '38f4367d-ae0f-5a8e-850e-d0e56506a709', 'increase_unit', '涨幅单位', 'TEXT', false, '{}', 11, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('c4823df6-aac1-5c60-a11e-dc334f52915c', '2e300dc7-c63c-56f4-97e6-b739b5730501', 'material_no', '销售料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('2766e9bf-d723-5534-b0d8-839262621c69', '2e300dc7-c63c-56f4-97e6-b739b5730501', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d6442489-d85f-5b90-a8c9-4723dfa7538d', '2e300dc7-c63c-56f4-97e6-b739b5730501', 'input_material_no', '投入料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('72376660-9c9f-574e-b92e-867f2e0a7100', '2e300dc7-c63c-56f4-97e6-b739b5730501', 'element_item_seq', '要素项次', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('42f0f6ad-faa0-5b72-a4ab-a0fb9de102f6', '2e300dc7-c63c-56f4-97e6-b739b5730501', 'element_name', '要素名称', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d23625ec-19bf-555e-93e0-1eeb37852ee3', '2e300dc7-c63c-56f4-97e6-b739b5730501', 'value', '值', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('3c4bf355-be93-5d8b-9027-3e3c82a077e2', '2e300dc7-c63c-56f4-97e6-b739b5730501', 'ratio_pct', '比例（%）', 'NUMBER', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('34f136df-0a9a-546a-a878-51812b389845', '2e300dc7-c63c-56f4-97e6-b739b5730501', 'currency', '货币', 'TEXT', false, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f56239bd-4eab-54c5-a2be-fbc189c8be49', '2e300dc7-c63c-56f4-97e6-b739b5730501', 'pricing_unit', '计价单位', 'TEXT', false, '{}', 8, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('7bec1b73-7d42-53e4-9494-a7a91872108f', '6bdfd52b-1146-58a1-aef3-2b54383649cf', 'material_no', '销售料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('62e88f51-4f31-5ad9-9ec7-1b93382fc813', '6bdfd52b-1146-58a1-aef3-2b54383649cf', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('90a47432-7138-57b1-b39d-395962b40dd5', '6bdfd52b-1146-58a1-aef3-2b54383649cf', 'input_material_no', '投入料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('e4d0b517-0271-5206-b426-06e7c0923c67', '6bdfd52b-1146-58a1-aef3-2b54383649cf', 'recovery_discount', '回收折扣（%）', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('9be71b88-8b43-5bcf-ba67-fce290cd6365', '6bdfd52b-1146-58a1-aef3-2b54383649cf', 'recovery_value', '回收值', 'NUMBER', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f4e84fdb-3c42-574a-be13-48235c82751c', '6bdfd52b-1146-58a1-aef3-2b54383649cf', 'recovery_source', '回收来源', 'TEXT', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('87f7e045-1b2b-508e-a6f7-d6c111c16834', '75ccb22f-1b39-5382-902d-bb48a6d10303', 'material_no', '销售料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ea57370d-bfc4-5a08-9903-b9a8a976d506', '75ccb22f-1b39-5382-902d-bb48a6d10303', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('3328bc98-6aa8-5d5e-8dde-c9cb32577aa9', '75ccb22f-1b39-5382-902d-bb48a6d10303', 'input_material_no', '投入料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('87ae8ec6-a020-5475-a343-a937c1d82b80', '75ccb22f-1b39-5382-902d-bb48a6d10303', 'operation_item_seq', '工序项次', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d357971e-da2c-53ba-90df-807adffdde78', '75ccb22f-1b39-5382-902d-bb48a6d10303', 'operation_no', '工序编号', 'TEXT', true, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('5b818a70-6f52-553d-97cb-4e73ae061e03', '75ccb22f-1b39-5382-902d-bb48a6d10303', 'value', '值', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('76aa146d-a483-59dd-91a1-204cf443ac7d', '75ccb22f-1b39-5382-902d-bb48a6d10303', 'ratio_pct', '比例（%）', 'NUMBER', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('8b3b54e3-d91a-52ab-bdc5-fb60e222688f', '75ccb22f-1b39-5382-902d-bb48a6d10303', 'currency', '货币', 'TEXT', false, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d4da33cd-7133-5743-94b1-adc08f093799', '75ccb22f-1b39-5382-902d-bb48a6d10303', 'pricing_unit', '计价单位', 'TEXT', false, '{}', 8, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('0d16e17a-0adc-5b20-b566-7e1571a83924', '0fe87f1b-6706-56a4-9289-17df2b6aae5e', 'material_no', '销售料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('5e381eb0-9fde-5daa-9b24-e89f78425ad7', '0fe87f1b-6706-56a4-9289-17df2b6aae5e', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('85822026-915d-5dee-8be3-a8abd5bb8d81', '0fe87f1b-6706-56a4-9289-17df2b6aae5e', 'element_name', '要素名称', 'TEXT', true, '{ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('bd541e22-83bf-52c6-bc77-f311592a9bcd', '0fe87f1b-6706-56a4-9289-17df2b6aae5e', 'value', '值', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('fb8893c3-547c-5f2b-a73f-894e6a9b6507', '0fe87f1b-6706-56a4-9289-17df2b6aae5e', 'ratio_pct', '比例（%）', 'NUMBER', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('5234f1e7-72cf-535f-a343-73b9f87e0538', '0fe87f1b-6706-56a4-9289-17df2b6aae5e', 'currency', '货币', 'TEXT', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('19be518d-4d7b-5d78-9d7e-7e5661fc82b4', '0fe87f1b-6706-56a4-9289-17df2b6aae5e', 'pricing_unit', '计价单位', 'TEXT', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('e3e84d58-4747-521e-ba5f-df9099ca718c', 'ebfadcce-598f-54ca-9cb0-ea8871cd3f7a', 'material_no', '销售料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('402ab1f2-d6c8-53f6-8539-4d32ced0c9e0', 'ebfadcce-598f-54ca-9cb0-ea8871cd3f7a', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('eb367a26-a1ab-59e1-80ca-1d1446689901', 'ebfadcce-598f-54ca-9cb0-ea8871cd3f7a', 'sub_component_no', '组成件料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('983eb01f-7929-5a7e-8ff7-3078115d9134', 'ebfadcce-598f-54ca-9cb0-ea8871cd3f7a', 'supplier_no', '供应商编号', 'TEXT', true, '{ROW_KEY}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('e881bfb5-78f5-59f1-a554-6bd2884553e1', 'ebfadcce-598f-54ca-9cb0-ea8871cd3f7a', 'supplier_name', '供应商名称', 'TEXT', true, '{ROW_KEY}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('4286de37-e219-5713-a955-3749dd6bf32a', 'ebfadcce-598f-54ca-9cb0-ea8871cd3f7a', 'element_item_seq', '要素项次', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('c8f4d703-460c-5ad3-a38c-ce931e3ddc03', 'ebfadcce-598f-54ca-9cb0-ea8871cd3f7a', 'element_name', '要素名称', 'TEXT', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('4db5ad12-002c-521d-9ecf-7786a1166081', 'ebfadcce-598f-54ca-9cb0-ea8871cd3f7a', 'value', '值', 'NUMBER', false, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d1c34f62-6a08-5b01-8758-dc778e73cc5c', 'ebfadcce-598f-54ca-9cb0-ea8871cd3f7a', 'currency', '货币', 'TEXT', false, '{}', 8, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('cefec2fc-c2de-5806-8752-c4a4b67a5e48', 'ebfadcce-598f-54ca-9cb0-ea8871cd3f7a', 'pricing_unit', '计价单位', 'TEXT', false, '{}', 9, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('5b05e5aa-1214-525a-a920-bac8ac19ce0f', '2368a39c-75f2-542a-942c-916e34da8e70', 'material_no', '销售料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('40162286-a079-5df5-8dad-c6963511ee0c', '2368a39c-75f2-542a-942c-916e34da8e70', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('6b822507-1ee9-5c21-97ac-e8935fb983fb', '2368a39c-75f2-542a-942c-916e34da8e70', 'assembly_operation', '组装工序', 'TEXT', true, '{ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('c7aaf5ad-3966-57f5-9d12-0e1799ef638e', '2368a39c-75f2-542a-942c-916e34da8e70', 'assembly_fee', '组装加工费', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('272492a8-e3d2-5d01-a371-1fa5fb48ff7f', '2368a39c-75f2-542a-942c-916e34da8e70', 'currency', '货币', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('0cc19dd6-fd2b-515b-b8c5-9e58dc7d233c', '2368a39c-75f2-542a-942c-916e34da8e70', 'pricing_unit', '计价单位', 'TEXT', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('12fdae7d-8fd3-5b36-aa4f-83da04d10e2a', '2368a39c-75f2-542a-942c-916e34da8e70', 'defect_rate', '拒收率/不良率（%）', 'NUMBER', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('35bdb49d-d447-5457-9b7e-13b177d1466b', '4dfd7565-b6cf-56d6-b3ff-b98eaeca18ae', 'material_no', '销售料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('016ae617-1846-5956-89db-fd6b17bd9a63', '4dfd7565-b6cf-56d6-b3ff-b98eaeca18ae', 'plating_scheme_no', '电镀方案编号', 'TEXT', true, '{ROW_KEY}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('7d8015c5-934f-57fd-8ef2-2109a26587d1', '4dfd7565-b6cf-56d6-b3ff-b98eaeca18ae', 'plating_version', '版本编号', 'TEXT', true, '{ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('8dcfa858-8c73-56ea-923b-efa628c5e91c', '4dfd7565-b6cf-56d6-b3ff-b98eaeca18ae', 'plating_process_fee', '电镀加工费', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('b0cc28e8-dfcf-5ef5-9a6d-8d3cb839a1ab', '4dfd7565-b6cf-56d6-b3ff-b98eaeca18ae', 'plating_material_fee', '电镀材料费', 'NUMBER', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('0384d64f-d037-5e65-b400-dbfc2c76d917', '4dfd7565-b6cf-56d6-b3ff-b98eaeca18ae', 'currency', '货币', 'TEXT', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('e7719755-b6fe-570a-8523-1fee18b2a2ff', '4dfd7565-b6cf-56d6-b3ff-b98eaeca18ae', 'pricing_unit', '计价单位', 'TEXT', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('b98982e2-f23d-5a6c-85be-3ab2d1e59609', '4dfd7565-b6cf-56d6-b3ff-b98eaeca18ae', 'defect_rate', '不良率（%）', 'NUMBER', false, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('8d991089-d2a6-5129-898d-92e339d57889', '801e3e6f-a628-55a6-a9f9-324fbba25d22', 'scheme_no', '方案编号', 'TEXT', true, '{PART_NO}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('b2d8b9b6-6bd1-5a42-bcea-a609a4b069b3', '801e3e6f-a628-55a6-a9f9-324fbba25d22', 'scheme_version', '版本', 'TEXT', false, '{}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f2390ba9-3123-5599-a0de-74c86e825c8f', '801e3e6f-a628-55a6-a9f9-324fbba25d22', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('822798e4-356c-5efd-bc67-874d552b45fd', '801e3e6f-a628-55a6-a9f9-324fbba25d22', 'plating_element', '电镀元素名称', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('4956109f-955d-56e1-9e51-dbaa8eaf01fa', '801e3e6f-a628-55a6-a9f9-324fbba25d22', 'price_source_url', '元素单价来源网站网址', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('0cf3a145-9e98-5336-b643-812f32a4a3e9', '801e3e6f-a628-55a6-a9f9-324fbba25d22', 'price_source_name', '元素单价来源网站名称', 'TEXT', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('2069c594-9ebc-5b7e-89fb-4cf33c2998c0', '801e3e6f-a628-55a6-a9f9-324fbba25d22', 'price_fetch_rule', '元素单价抓取规则', 'TEXT', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('bc4eb313-ab3f-50da-9d52-2b7292f9f64c', '801e3e6f-a628-55a6-a9f9-324fbba25d22', 'plating_area', '电镀面积（cm2）', 'NUMBER', false, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('55187615-ca8c-565b-a173-12dd9b98e79c', '801e3e6f-a628-55a6-a9f9-324fbba25d22', 'coating_thickness', '镀层厚度（μm）', 'NUMBER', false, '{}', 8, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('a7ec1f84-0551-56ff-a713-8b4af7f32157', '801e3e6f-a628-55a6-a9f9-324fbba25d22', 'plating_requirement', '电镀要求', 'TEXT', false, '{}', 9, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('70d0eb6d-a55f-53db-b682-f5c5659c6fab', '7a85578d-ab5e-5b2e-9ea7-0da8d4a6c864', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('e58f5bc9-30a0-51fd-a30c-55fd5bc5d023', '7a85578d-ab5e-5b2e-9ea7-0da8d4a6c864', 'material_name', '品名', 'TEXT', false, '{}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('94870fcb-4583-5526-8634-e2731622c2a6', '7a85578d-ab5e-5b2e-9ea7-0da8d4a6c864', 'specification', '规格', 'TEXT', false, '{}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('50f4a7be-901a-55de-9fb7-7dc7e1024b29', '7a85578d-ab5e-5b2e-9ea7-0da8d4a6c864', 'dimension', '尺寸', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('87391ab5-8601-589d-8abf-6f07f567d37d', '7a85578d-ab5e-5b2e-9ea7-0da8d4a6c864', 'old_material_no', '旧料号', 'TEXT', true, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('87e8860c-794a-5fb0-84f8-14cc74b6c1a4', '7a85578d-ab5e-5b2e-9ea7-0da8d4a6c864', 'unit_weight', '单重', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('053f5861-8c27-5bf9-804e-0ff6071f0991', '7a85578d-ab5e-5b2e-9ea7-0da8d4a6c864', 'material_type', '类型', 'TEXT', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('33197d7e-3f70-5b9c-bfb2-38cc6ae4def9', 'b9a3018d-6536-5340-b54d-39069f8dfd61', 'production_no', '生产料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('227e885c-e783-5777-bf19-e3ad9e810d14', 'b9a3018d-6536-5340-b54d-39069f8dfd61', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('c860ae42-b1a4-5b04-a004-4182a1c93f3c', 'b9a3018d-6536-5340-b54d-39069f8dfd61', 'component_no', '组成料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('7090b899-57ca-5894-a963-3a0c7fec45ec', 'b9a3018d-6536-5340-b54d-39069f8dfd61', 'component_qty', '组成用量', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('a248317a-a231-5ce4-b9f8-d02582b9e1cb', 'b9a3018d-6536-5340-b54d-39069f8dfd61', 'component_qty_unit', '组成用量单位', 'TEXT', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('fdf0e4a2-8fcc-532b-a2c3-08d425860451', 'b9a3018d-6536-5340-b54d-39069f8dfd61', 'base_qty', '底数', 'NUMBER', false, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('91153225-8623-591a-8430-020bfba2ea3e', 'b9a3018d-6536-5340-b54d-39069f8dfd61', 'base_qty_unit', '底数单位', 'TEXT', false, '{}', 8, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d6e29e64-071d-5253-84c4-d2b37854d675', 'b9a3018d-6536-5340-b54d-39069f8dfd61', 'material_loss_rate', '材料损耗率（%）', 'NUMBER', false, '{}', 9, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('321d456b-501b-5045-8225-714be7f16c79', 'b9a3018d-6536-5340-b54d-39069f8dfd61', 'material_fixed_loss', '材料固定损耗量', 'NUMBER', false, '{}', 10, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('dd075405-38f9-5bdc-a0ee-b7dbb576069e', 'b9a3018d-6536-5340-b54d-39069f8dfd61', 'defect_rate', '不良率（%）', 'NUMBER', false, '{}', 11, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('c353aec5-f780-5e25-a9e2-b0ef5481cbe0', '531951d0-cb82-5925-a127-706821bfc8d1', 'production_no', '生产料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('c5400d71-aac8-548d-b949-6ef9fe37793e', '531951d0-cb82-5925-a127-706821bfc8d1', 'material_part_no', '材质料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('960d5ce1-e893-51f3-a76c-5301d4b161a6', '531951d0-cb82-5925-a127-706821bfc8d1', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('676be589-73a3-52e6-b9a3-aca01e775586', '531951d0-cb82-5925-a127-706821bfc8d1', 'element_code', '元素代码', 'TEXT', true, '{ROW_KEY}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d4b614c1-d647-5f86-b616-1e7a114850ce', '531951d0-cb82-5925-a127-706821bfc8d1', 'content_pct', '组成含量（%）', 'NUMBER', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('2ba6928a-fddf-5ec3-be91-e92c45043da1', '531951d0-cb82-5925-a127-706821bfc8d1', 'loss_rate', '损耗率（%）', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('33ed6c07-0b70-5a9a-88f4-19a855c369b3', 'd5764c1d-f2a7-510a-8853-2cf84feb4463', 'production_no', '生产料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('cd9c5958-d250-5d95-8f1b-be98cce97ba0', 'd5764c1d-f2a7-510a-8853-2cf84feb4463', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('3c0d3270-55ba-56e0-a020-472b2edbf41a', 'd5764c1d-f2a7-510a-8853-2cf84feb4463', 'incoming_material_no', '来料料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('74ea80e1-afc2-5bde-b1d0-8aeddb61859e', 'd5764c1d-f2a7-510a-8853-2cf84feb4463', 'process_fee', '加工费', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('fc728748-7d44-5ca0-b474-166df5cc91b7', 'd5764c1d-f2a7-510a-8853-2cf84feb4463', 'currency', '币种', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d3f1ee23-f18c-51f3-884e-3dc65032c5dc', 'd5764c1d-f2a7-510a-8853-2cf84feb4463', 'unit', '计量单位', 'TEXT', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ec9cadd0-c7bf-5645-a32d-e8156cf73a61', 'd5764c1d-f2a7-510a-8853-2cf84feb4463', 'loss_rate', '损耗（%）', 'NUMBER', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('dc01fdc1-30e5-50ad-954e-6434d468cfc4', '5f0373f8-500c-5c37-8cad-da453bfd0250', 'production_no', '生产料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('91587ccc-1380-5d80-ba44-dae4883e97f0', '5f0373f8-500c-5c37-8cad-da453bfd0250', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('0143b941-490f-5ac2-844c-22768131ee22', '5f0373f8-500c-5c37-8cad-da453bfd0250', 'incoming_material_no', '来料料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('6d863f5c-41ff-531c-8a9a-83c8b145d157', '5f0373f8-500c-5c37-8cad-da453bfd0250', 'element_item_seq', '要素项次', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('7454d9db-796a-515f-bd63-3e03a91aec33', '5f0373f8-500c-5c37-8cad-da453bfd0250', 'ratio_pct', '比例（%）', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('c83c6a82-63bb-578f-8c48-8b22831ae4d7', '5f0373f8-500c-5c37-8cad-da453bfd0250', 'fee', '费用', 'NUMBER', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f6a4d400-8a47-5c6b-9df8-e3e55aa7108d', '1032a3fe-5e51-5a9c-ade8-14d73706237c', 'production_no', '生产料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f666b3d6-a5a1-51ac-9791-ddc9aa8b2cbc', '1032a3fe-5e51-5a9c-ade8-14d73706237c', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('dfece444-5560-5b2b-a543-0c57a1522c69', '1032a3fe-5e51-5a9c-ade8-14d73706237c', 'incoming_material_no', '来料料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ffb8c849-ef6c-5af5-af09-6c3a625a0bca', '1032a3fe-5e51-5a9c-ade8-14d73706237c', 'element_item_seq', '要素项次', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f748cf21-f272-56d0-afbb-bb663653af50', '1032a3fe-5e51-5a9c-ade8-14d73706237c', 'fee', '费用', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('7d3c636f-a876-561e-a2ad-d612da9310f1', '1032a3fe-5e51-5a9c-ade8-14d73706237c', 'currency', '币种', 'TEXT', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('21532747-5c3b-5ba9-977e-358792d96f08', '1032a3fe-5e51-5a9c-ade8-14d73706237c', 'pricing_unit', '计价单位', 'TEXT', false, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('043cda47-757f-5d8e-abc1-2ce65aa061d6', '4fc0c31b-e623-56c3-986d-5d0a5af60181', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('83088d49-c0bb-55df-8a23-2b1697d62216', '4fc0c31b-e623-56c3-986d-5d0a5af60181', 'operation_no', '工序编号', 'TEXT', true, '{ROW_KEY}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('790c2ad2-e9ca-5eeb-9676-0f8567355a62', '4fc0c31b-e623-56c3-986d-5d0a5af60181', 'process_fee', '加工费', 'NUMBER', false, '{}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('712bcda0-fe91-519a-833c-6cfe546942bb', '4fc0c31b-e623-56c3-986d-5d0a5af60181', 'currency', '币种', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('662ab5d7-2498-5bcc-a236-384a41e5af2d', '4fc0c31b-e623-56c3-986d-5d0a5af60181', 'unit', '计量单位', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('818f7576-9f1b-50fb-831d-e2b6539f3d66', '4fc0c31b-e623-56c3-986d-5d0a5af60181', 'defect_rate', '不良率/拒收率（%）', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('9c55df1c-f61d-5a5a-8ffc-44e2c92cf2b9', '3d0bc5c6-200a-5a24-b505-f36df668cd02', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ed2e8171-91ae-5f89-a10a-822236ca680d', '3d0bc5c6-200a-5a24-b505-f36df668cd02', 'operation_no', '工序编号', 'TEXT', true, '{ROW_KEY}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('cba1a1ba-53f3-5fad-b2ac-c184b46672a8', '3d0bc5c6-200a-5a24-b505-f36df668cd02', 'outsourced_fee', '外加工费用', 'NUMBER', false, '{}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ce8e5dcb-69b1-5ca0-acd2-cdf3c037936f', '3d0bc5c6-200a-5a24-b505-f36df668cd02', 'currency', '币种', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('76b9dd6e-c0e0-5e5a-bc0d-6dcee760fc3d', '3d0bc5c6-200a-5a24-b505-f36df668cd02', 'unit', '单位', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('fef06e93-ef4a-5fe0-b9a3-c72c4b738b3c', '026543e0-4815-54c5-a69e-78884c73c26f', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('42d7d900-2c39-596c-a5fa-32cc94d1a7b1', '026543e0-4815-54c5-a69e-78884c73c26f', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('40c76317-9dc3-5de3-aeb8-c43079e00628', '026543e0-4815-54c5-a69e-78884c73c26f', 'ratio_pct', '比例（%）', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('59869045-87ab-5fd5-a06b-14069cca0f03', 'c3a6e4fe-8488-5c76-874a-c410da2913fc', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('09bffc2c-8ff5-58d9-b3ff-c0436645ddc6', 'c3a6e4fe-8488-5c76-874a-c410da2913fc', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('a072f2f1-43f5-5bf3-8240-089721e92d60', 'c3a6e4fe-8488-5c76-874a-c410da2913fc', 'element_name', '要素名称', 'TEXT', true, '{ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ca9d7997-9e77-5f23-8a45-ef1253dc2f8e', 'c3a6e4fe-8488-5c76-874a-c410da2913fc', 'fee', '费用', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('8844bad5-d52f-5085-b17b-9ff4378974fc', 'c3a6e4fe-8488-5c76-874a-c410da2913fc', 'currency', '币种', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('9700267d-82bd-5bb0-a88f-573e3511da37', 'c3a6e4fe-8488-5c76-874a-c410da2913fc', 'pricing_unit', '计价单位', 'TEXT', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('7e344318-f76f-5871-8493-09780aed3f0d', '35d18fcf-cf27-573c-9cf7-2c9b2bf9e13a', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('951aeb04-efe0-532b-961f-c929814fccfd', '35d18fcf-cf27-573c-9cf7-2c9b2bf9e13a', 'material_name', '品名', 'TEXT', false, '{}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('81c8a15f-8e51-5dbc-a3a7-74028ce8d631', '35d18fcf-cf27-573c-9cf7-2c9b2bf9e13a', 'specification', '规格', 'TEXT', false, '{}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('a7d8d40f-6139-550c-bb63-b67cee9fedf8', '35d18fcf-cf27-573c-9cf7-2c9b2bf9e13a', 'dimension', '尺寸', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('21b11325-cafc-5b30-a5f9-03d5f771d134', '35d18fcf-cf27-573c-9cf7-2c9b2bf9e13a', 'old_material_no', '旧料号', 'TEXT', true, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ed6fdf8a-1418-5072-b707-350cb099f536', '35d18fcf-cf27-573c-9cf7-2c9b2bf9e13a', 'unit_weight', '单重', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('bcce0e97-2587-5751-876a-82bc280891d8', '35d18fcf-cf27-573c-9cf7-2c9b2bf9e13a', 'material_type', '类型', 'TEXT', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('34c699d4-7ab3-5b3c-9f25-59f24aa2c653', 'bce970df-428e-55d0-b799-07853b82aa5c', 'production_no', '生产料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('4ea065c8-cd2e-50c8-9ba4-e52878c7b635', 'bce970df-428e-55d0-b799-07853b82aa5c', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('6b20cc69-aa3b-55c7-9041-5cac86d4f83f', 'bce970df-428e-55d0-b799-07853b82aa5c', 'component_no', '组成料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f38cd56c-e37a-5825-9d0a-6bbab0e5b6b4', 'bce970df-428e-55d0-b799-07853b82aa5c', 'component_qty', '组成用量', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('1a298c05-f602-5841-832b-60ff9dda5c8f', 'bce970df-428e-55d0-b799-07853b82aa5c', 'component_qty_unit', '组成用量单位', 'TEXT', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('5d8d4723-2655-54cc-9336-654e071dfec6', 'bce970df-428e-55d0-b799-07853b82aa5c', 'base_qty', '底数', 'NUMBER', false, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('6a9d5c5e-b4aa-58f5-ba44-0f69f4517adc', 'bce970df-428e-55d0-b799-07853b82aa5c', 'base_qty_unit', '底数单位', 'TEXT', false, '{}', 8, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('6c15dad1-a333-55c8-b3a0-fc0e86cd9179', 'bce970df-428e-55d0-b799-07853b82aa5c', 'material_loss_rate', '材料损耗率（%）', 'NUMBER', false, '{}', 9, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('eb8806cb-e403-5905-a755-7b6f9e31de53', 'bce970df-428e-55d0-b799-07853b82aa5c', 'material_fixed_loss', '材料固定损耗量', 'NUMBER', false, '{}', 10, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('345f5657-e717-599e-b676-5f39cb35a6b9', 'bce970df-428e-55d0-b799-07853b82aa5c', 'defect_rate', '不良率（%）', 'NUMBER', false, '{}', 11, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('66759e15-86c6-5e97-a15b-9dbc828eb385', '52784f35-2b0f-5aa1-a0f8-a6f0346eb025', 'production_no', '生产料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('71ccaa15-c3a8-56f8-bb3e-7e1008668bc5', '52784f35-2b0f-5aa1-a0f8-a6f0346eb025', 'material_part_no', '材质料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ca94f84a-1c68-55d6-b33d-b2e8815662db', '52784f35-2b0f-5aa1-a0f8-a6f0346eb025', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('0da5365d-f413-5cda-a2c0-2b2d649453ae', '52784f35-2b0f-5aa1-a0f8-a6f0346eb025', 'element_code', '元素代码', 'TEXT', true, '{ROW_KEY}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('be352792-a4e0-5872-a0d6-214eb8d7ea4e', '52784f35-2b0f-5aa1-a0f8-a6f0346eb025', 'content_pct', '组成含量（%）', 'NUMBER', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('183d001e-6b42-53fa-8f82-a358b3ec8339', '52784f35-2b0f-5aa1-a0f8-a6f0346eb025', 'loss_rate', '损耗率（%）', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ebd61e99-0a39-5b22-823a-d68cf87e713b', 'b44cbbe9-11a8-52d4-b82d-f7b81d0a7cb4', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('da4c7e95-c943-559a-981b-af6fb52a04fa', 'b44cbbe9-11a8-52d4-b82d-f7b81d0a7cb4', 'operation_no', '工序编号', 'TEXT', true, '{ROW_KEY}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('9b7f996c-a4c1-5c04-b217-15c8dbe739f7', 'b44cbbe9-11a8-52d4-b82d-f7b81d0a7cb4', 'labor_std_price', '人工标准单价', 'NUMBER', false, '{}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('4a675fab-0f93-505a-b9bc-d9bbdb0ff0a5', 'b44cbbe9-11a8-52d4-b82d-f7b81d0a7cb4', 'currency', '币种', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('0e65c4d3-972a-52d4-8ce5-5b951fb643cd', 'b44cbbe9-11a8-52d4-b82d-f7b81d0a7cb4', 'unit', '计量单位', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('baf9274b-f9d5-5495-b39b-f509e84155c1', 'fe9b5007-844b-5551-8c03-8ac017ad1f2a', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('3ee3d90a-1a9f-5b60-a172-2161c9d7f016', 'fe9b5007-844b-5551-8c03-8ac017ad1f2a', 'operation_no', '工序编号', 'TEXT', true, '{ROW_KEY}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f3dc2dbf-9a1d-586f-907d-9f41b7c8758e', 'fe9b5007-844b-5551-8c03-8ac017ad1f2a', 'depreciation_price', '折旧单价', 'NUMBER', false, '{}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('8b3c15d4-2b55-5661-a514-ac12c927f94b', 'fe9b5007-844b-5551-8c03-8ac017ad1f2a', 'currency', '币种', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('e6281c79-8ad1-517f-b28e-92918e2f35c1', 'fe9b5007-844b-5551-8c03-8ac017ad1f2a', 'unit', '计量单位', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('b0e7e45c-bf02-5206-94e9-e466a3973e94', '191da8dc-59f4-56de-8541-170e792c253e', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('169df0a3-381c-54fd-8d1d-718194839bb5', '191da8dc-59f4-56de-8541-170e792c253e', 'operation_no', '工序编号', 'TEXT', true, '{ROW_KEY}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('2eca7014-1c24-5830-8fc8-9e7879d32275', '191da8dc-59f4-56de-8541-170e792c253e', 'production_energy_price', '生产能耗单价', 'NUMBER', false, '{}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('c5d74982-28ff-540d-96fa-fcc75362ee87', '191da8dc-59f4-56de-8541-170e792c253e', 'currency', '币种', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('24652112-bc68-55c6-a262-cd017560f481', '191da8dc-59f4-56de-8541-170e792c253e', 'unit', '计量单位', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('b775a9e2-4b6e-5c6d-b8fc-aa5499edd783', 'c9780071-c1ed-5cd8-8dc6-b852a0defe39', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('41906e7f-68c3-5928-b473-c64d621f2732', 'c9780071-c1ed-5cd8-8dc6-b852a0defe39', 'operation_no', '工序编号', 'TEXT', true, '{ROW_KEY}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('53d741eb-4c50-57e5-87cc-45e0376e6906', 'c9780071-c1ed-5cd8-8dc6-b852a0defe39', 'auxiliary_energy_price', '非生产能耗单价', 'NUMBER', false, '{}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d9fda306-17d1-5595-bbbc-e75941c41d8f', 'c9780071-c1ed-5cd8-8dc6-b852a0defe39', 'currency', '币种', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d6107790-492d-5a8d-b9c0-d1f8310c4c16', 'c9780071-c1ed-5cd8-8dc6-b852a0defe39', 'unit', '计量单位', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('18582d80-1bc1-54b4-ac10-8f06727d6ca6', '3d4988fb-c7ac-51ee-ba37-7dfa61e18148', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('7a0c1dbe-2e8b-500f-8713-d32cb2badd72', '3d4988fb-c7ac-51ee-ba37-7dfa61e18148', 'operation_no', '工序编号', 'TEXT', true, '{ROW_KEY}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('1123f025-fc84-5e4e-adcc-4872c1a7fd98', '3d4988fb-c7ac-51ee-ba37-7dfa61e18148', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('e07716ad-30c4-5227-9a70-217bce4afe96', '3d4988fb-c7ac-51ee-ba37-7dfa61e18148', 'tooling_cost', '单个模具/工装成本', 'NUMBER', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('12e96a58-7e51-5fb9-9146-498a0f29ae74', '3d4988fb-c7ac-51ee-ba37-7dfa61e18148', 'tooling_life', '寿命（次）', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('4d999f61-7c3e-5088-85af-147bd9b182af', '3d4988fb-c7ac-51ee-ba37-7dfa61e18148', 'cycle_output', '单循环产量', 'NUMBER', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('3228e934-f16b-5f4c-9792-5cea65413709', '3d4988fb-c7ac-51ee-ba37-7dfa61e18148', 'tooling_unit_price', '模具工装成本单价', 'NUMBER', false, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('5a7937b4-53d6-580f-af66-6eb45b372b12', '3d4988fb-c7ac-51ee-ba37-7dfa61e18148', 'currency', '币种', 'TEXT', false, '{}', 8, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('bd822591-91b1-5bb4-89c4-193eacc6e819', '3d4988fb-c7ac-51ee-ba37-7dfa61e18148', 'unit', '计量单位', 'TEXT', false, '{}', 9, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ddc03a8d-92f1-5b1a-87ec-4edc861034d3', '06aa16e3-ef35-5a60-8132-8d2257834dc4', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('87379242-3db9-5884-a40e-02e0f22a3e19', '06aa16e3-ef35-5a60-8132-8d2257834dc4', 'operation_no', '工序编号', 'TEXT', true, '{ROW_KEY}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('6ab46d54-4f4f-503e-88bf-636be00d240e', '06aa16e3-ef35-5a60-8132-8d2257834dc4', 'consumable_price', '耗材成本单价', 'NUMBER', false, '{}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('251ab230-de88-5ff6-9c50-54f9d2c26315', '06aa16e3-ef35-5a60-8132-8d2257834dc4', 'currency', '币种', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('740affd5-0dfc-541e-ba3a-b9319e90579c', '06aa16e3-ef35-5a60-8132-8d2257834dc4', 'unit', '计量单位', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('4a94decb-0545-5339-b99e-1ce24ea8a871', 'ce1f43ff-c1b3-554a-b7e0-7d60cf70ddbb', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('2a90d8d0-6aca-5009-80e0-9f59df6cef36', 'ce1f43ff-c1b3-554a-b7e0-7d60cf70ddbb', 'operation_no', '工序编号', 'TEXT', true, '{ROW_KEY}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('01cde6b8-5157-566e-992a-41545c121926', 'ce1f43ff-c1b3-554a-b7e0-7d60cf70ddbb', 'packaging_price', '包装成本单价', 'NUMBER', false, '{}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d7c13aca-97b3-5414-8718-dbed005f95f4', 'ce1f43ff-c1b3-554a-b7e0-7d60cf70ddbb', 'currency', '币种', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f3dc849e-8848-5b75-ad0a-977f476aac69', 'ce1f43ff-c1b3-554a-b7e0-7d60cf70ddbb', 'unit', '计量单位', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('85f0858a-bdd2-559e-9f02-412f0a02f4ad', '548027b5-43af-5c2b-969a-cc51abd5ca5c', 'production_no', '生产料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('9f3caa56-318b-52c1-a752-5d70dfa4893d', '548027b5-43af-5c2b-969a-cc51abd5ca5c', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('e215948e-e957-569c-b5d5-44a2d537a3cd', '548027b5-43af-5c2b-969a-cc51abd5ca5c', 'incoming_material_no', '来料料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ec29bc23-3037-5ac0-aaf2-188882fa4cab', '548027b5-43af-5c2b-969a-cc51abd5ca5c', 'process_fee', '加工费', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ff1f973a-3e1b-5f2c-bb0c-3417a717a9a4', '548027b5-43af-5c2b-969a-cc51abd5ca5c', 'currency', '币种', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('46ca8bb2-756f-5404-b2ed-99f999c3b4fe', '548027b5-43af-5c2b-969a-cc51abd5ca5c', 'unit', '计量单位', 'TEXT', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('64c5bda4-5852-5f3f-92eb-dccb0f8b3911', '548027b5-43af-5c2b-969a-cc51abd5ca5c', 'loss_rate', '损耗（%）', 'NUMBER', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('7d7591f9-2438-5946-833f-ee31bd9c3891', '0547bede-2926-5518-bcbc-927bcad086b2', 'production_no', '生产料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('b0804531-4779-5175-b84b-e2ccfbf536d0', '0547bede-2926-5518-bcbc-927bcad086b2', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ddc397cd-02e3-5513-aaf9-aa4e890e81b5', '0547bede-2926-5518-bcbc-927bcad086b2', 'incoming_material_no', '来料料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('162295d7-b10d-593e-b38e-84522ab0e0d3', '0547bede-2926-5518-bcbc-927bcad086b2', 'element_item_seq', '要素项次', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('d254db53-1ff8-5e90-b982-09bbb31220be', '0547bede-2926-5518-bcbc-927bcad086b2', 'ratio_pct', '比例（%）', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('86f3fc1f-582d-5ad6-9842-9a4e036e3bc8', '0547bede-2926-5518-bcbc-927bcad086b2', 'fee', '费用', 'NUMBER', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('48510aac-6604-5ce1-8648-213df4b45076', '345562dc-5eb3-5789-aa24-2d01b3c32f7d', 'production_no', '生产料号', 'TEXT', true, '{ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f3236f3a-589a-569f-98b0-cfcf9b872d33', '345562dc-5eb3-5789-aa24-2d01b3c32f7d', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f1b5f1a8-6c78-571d-bf5e-f3c83210b7f9', '345562dc-5eb3-5789-aa24-2d01b3c32f7d', 'incoming_material_no', '来料料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('11c07e09-c9d1-5b06-b874-045e17f07879', '345562dc-5eb3-5789-aa24-2d01b3c32f7d', 'element_item_seq', '要素项次', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('25a469d2-5f8c-5432-bb44-0602c5aef251', '345562dc-5eb3-5789-aa24-2d01b3c32f7d', 'fee', '费用', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('79ef2ed5-48ef-5b7b-b81c-09517512c3c5', '345562dc-5eb3-5789-aa24-2d01b3c32f7d', 'currency', '币种', 'TEXT', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('8bc3dfae-67b3-5303-8832-0104cb807f20', '345562dc-5eb3-5789-aa24-2d01b3c32f7d', 'pricing_unit', '计价单位', 'TEXT', false, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('aabe042d-2e80-51df-841a-90168651aab0', 'a42b6cd0-9fde-5616-9566-8be8c50e870b', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('1d2531f7-63d1-5a1a-bbb0-ef335d00e3cd', 'a42b6cd0-9fde-5616-9566-8be8c50e870b', 'operation_no', '工序编号', 'TEXT', true, '{ROW_KEY}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('974cf66b-b04f-5e02-8505-65a0dacd1d42', 'a42b6cd0-9fde-5616-9566-8be8c50e870b', 'process_fee', '加工费', 'NUMBER', false, '{}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('8c59af46-e8d4-577b-8c68-04efe3841ab5', 'a42b6cd0-9fde-5616-9566-8be8c50e870b', 'currency', '币种', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('06be9ca3-8f39-55e4-a7a3-23b3d31a0e40', 'a42b6cd0-9fde-5616-9566-8be8c50e870b', 'unit', '计量单位', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('1704e975-80a4-5c6b-86c6-a6dcd2ef4d54', 'a42b6cd0-9fde-5616-9566-8be8c50e870b', 'defect_rate', '不良率/拒收率（%）', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('eb5ef383-f350-52df-822c-7def701c461d', '8238839a-e1e7-5e8f-b315-c8e976cebd1f', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('240083a3-7204-56b3-8b5c-5a060e6fa244', '8238839a-e1e7-5e8f-b315-c8e976cebd1f', 'operation_no', '工序编号', 'TEXT', true, '{ROW_KEY}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('9dc1ddd5-919e-519d-8991-cb342e716b70', '8238839a-e1e7-5e8f-b315-c8e976cebd1f', 'outsourced_fee', '外加工费用', 'NUMBER', false, '{}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('37b137c0-f36d-5583-a30b-f234ee28242e', '8238839a-e1e7-5e8f-b315-c8e976cebd1f', 'currency', '币种', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('e273f266-49a3-5354-b332-c07e5961414d', '8238839a-e1e7-5e8f-b315-c8e976cebd1f', 'unit', '单位', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('3a8deda1-6c9e-5fdb-90a0-0860fdf11a20', '3a0fdb6d-86c9-5838-a4cf-ec25f08f7b38', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('9a4928f9-59bd-5e6f-b8ba-d0772e6ec220', '3a0fdb6d-86c9-5838-a4cf-ec25f08f7b38', 'plating_process_fee', '电镀加工费', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('2ad7d6d8-2de8-526a-986a-f50dc8b41aec', '3a0fdb6d-86c9-5838-a4cf-ec25f08f7b38', 'plating_material_fee', '电镀材料费', 'NUMBER', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('b1064c5e-c24a-519c-846a-f4f40081c4e3', '3a0fdb6d-86c9-5838-a4cf-ec25f08f7b38', 'currency', '货币', 'TEXT', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('4bf198bc-e7fe-5673-a0b2-c3dfd5d5d51a', '3a0fdb6d-86c9-5838-a4cf-ec25f08f7b38', 'pricing_unit', '计价单位', 'TEXT', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('0b97b690-a1e7-5b09-ab71-8224d0d4914c', '3a0fdb6d-86c9-5838-a4cf-ec25f08f7b38', 'defect_rate', '不良率（%）', 'NUMBER', false, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('632bcb42-7839-5c6a-9e18-f603c348a1be', '8b480f2d-8a0e-5824-a417-34d53a04b916', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f75bd8c4-afd9-5085-b240-d9cc90005aa8', '8b480f2d-8a0e-5824-a417-34d53a04b916', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('efba0fd4-e07e-5686-b6fd-51cd1d36cc62', '8b480f2d-8a0e-5824-a417-34d53a04b916', 'ratio_pct', '比例（%）', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('9474847b-3942-5e07-90eb-0029e532020c', 'ff7b6f55-70c2-59f3-a1ea-f3f021e3d49a', 'production_no', '生产料号', 'TEXT', true, '{PART_NO,ROW_KEY}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('7b8531e1-593f-5f5a-b097-32e3b63ab0b3', 'ff7b6f55-70c2-59f3-a1ea-f3f021e3d49a', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('a45f824d-2823-5de7-b8b0-f5ca0cf23b50', 'ff7b6f55-70c2-59f3-a1ea-f3f021e3d49a', 'fee', '费用', 'NUMBER', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('80c9bd78-a0f1-534e-bfbc-4aa1ce282425', 'ff7b6f55-70c2-59f3-a1ea-f3f021e3d49a', 'currency', '币种', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('3a8c8414-9034-5ffd-aabc-96c78e2ad5c3', 'ff7b6f55-70c2-59f3-a1ea-f3f021e3d49a', 'pricing_unit', '计价单位', 'TEXT', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('37da6290-c5ee-579b-90f0-54ffbe6c710f', '3369e20a-fcca-5a9c-adf0-70a1a108daa9', 'scheme_no', '方案编号', 'TEXT', true, '{PART_NO}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('a5c61328-0959-5cbf-a666-864156586e7c', '3369e20a-fcca-5a9c-adf0-70a1a108daa9', 'scheme_version', '版本', 'TEXT', false, '{}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('9e506caa-1e0e-5465-8629-8c5074720350', '3369e20a-fcca-5a9c-adf0-70a1a108daa9', 'item_seq', '项次', 'NUMBER', false, '{SORT}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('0d4dcc9b-5ce4-5ca3-b008-bdd7e7d8d660', '3369e20a-fcca-5a9c-adf0-70a1a108daa9', 'plating_element', '电镀元素名称', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('9db0ad91-b8e5-5c0d-9145-e7728a48a90d', '3369e20a-fcca-5a9c-adf0-70a1a108daa9', 'plating_area', '电镀面积（cm2）', 'NUMBER', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('eb66e6d9-99db-586c-b66e-2dbeb402d137', '3369e20a-fcca-5a9c-adf0-70a1a108daa9', 'coating_thickness', '镀层厚度（μm）', 'NUMBER', false, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('86716a90-5d5f-5fec-bae7-677669675365', '3369e20a-fcca-5a9c-adf0-70a1a108daa9', 'plating_requirement', '电镀要求', 'TEXT', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('694a6456-da63-59ec-a94f-cd88a7e1a28d', '3369e20a-fcca-5a9c-adf0-70a1a108daa9', 'density', '密度（g/cm3)', 'NUMBER', false, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('dc00425a-b781-5fd5-8974-3bd75994e8f1', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'material_no', '销售料号', 'TEXT', true, '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('08e65fef-1f91-5cc4-a77e-7c621cc917a5', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'category_code', '产品分类', 'TEXT', true, '{}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('e564757d-577b-58ae-a3cc-9430acb51de3', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'material_name', '品名', 'TEXT', false, '{PART_NAME}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('58001e6a-4d4e-50b9-b87b-d0ac7d078082', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'specification', '规格', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f0ee1eb5-4d64-54c3-bbd5-6376b53eae8d', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'dimension', '尺寸', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('abd2e2a0-5244-5dc3-9aab-5a03d9bb7b43', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'old_material_no', '旧料号', 'TEXT', true, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('11fa6620-e399-5e03-b41d-4c1d29f143c0', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'unit_weight', '单重', 'NUMBER', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('224b35ec-a60d-5eb8-bd3e-5163cc32902d', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'production_no', '生产料号', 'TEXT', true, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('9138a96f-1b4d-5642-b622-bc806261361d', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'material_type', '类型', 'TEXT', false, '{}', 8, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('8bf4bb40-9b73-5977-a3f2-de268d7f93d6', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'material_no', '销售料号', 'TEXT', true, '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('fb9818de-f9bd-5423-a45d-b97c6729b6df', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'category_code', '产品分类', 'TEXT', true, '{}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('92c9627a-f8c1-55cd-8c6a-37efa04a1995', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'material_name', '品名', 'TEXT', false, '{PART_NAME}', 2, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('dee148c1-88f4-574f-8f0b-947f91584f7b', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'specification', '规格', 'TEXT', false, '{}', 3, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('335a760f-fcfc-58bf-bad2-c92abf6d4bc6', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'dimension', '尺寸', 'TEXT', false, '{}', 4, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ee2f1da7-b7ae-50a4-bb18-a03fdfb2efb5', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'old_material_no', '旧料号', 'TEXT', true, '{}', 5, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f7f6bf07-0689-581f-b19c-042ffcbff01e', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'unit_weight', '单重', 'NUMBER', false, '{}', 6, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ed3cc621-5255-59af-976b-a40b269ed039', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'production_no', '生产料号', 'TEXT', true, '{}', 7, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('76ed4d40-a5c9-5710-b2cf-eb76e333d3bc', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'material_type', '类型', 'TEXT', false, '{}', 8, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('7bdcd73f-4f2a-5709-b01f-231ee23bac31', 'ddc7fafa-0fd3-5c47-a86c-e3f8b86b0f76', 'unit_price', '元素单价', 'MONEY', false, '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('03d405ad-2f2a-5a8d-8582-85efc3311c81', 'ddc7fafa-0fd3-5c47-a86c-e3f8b86b0f76', 'currency', '货币', 'TEXT', false, '{}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('26090902-0000-4000-8000-000000000001', '26090901-0000-4000-8000-000000000001', 'unit_price', '元素单价', 'MONEY', false, '{}', 0, NULL, '2026-09-09 02:34:26.116236', NULL, '2026-09-09 02:34:26.116236', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('26090902-0000-4000-8000-000000000002', '26090901-0000-4000-8000-000000000001', 'currency', '货币', 'TEXT', false, '{}', 1, NULL, '2026-09-09 02:34:26.116236', NULL, '2026-09-09 02:34:26.116236', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('26090902-0000-4000-8000-000000000003', '26090901-0000-4000-8000-000000000002', 'unit_price', '元素单价', 'MONEY', false, '{}', 0, NULL, '2026-09-09 02:34:26.116236', NULL, '2026-09-09 02:34:26.116236', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('26090902-0000-4000-8000-000000000004', '26090901-0000-4000-8000-000000000002', 'currency', '货币', 'TEXT', false, '{}', 1, NULL, '2026-09-09 02:34:26.116236', NULL, '2026-09-09 02:34:26.116236', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('26090903-0000-4000-8000-000000000001', '531951d0-cb82-5925-a127-706821bfc8d1', 'sales_material_no', '销售料号', 'TEXT', false, '{}', 6, NULL, '2026-09-09 02:34:26.116236', NULL, '2026-09-09 02:34:26.116236', 'ACTIVE');
INSERT INTO public.semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('26090903-0000-4000-8000-000000000002', '52784f35-2b0f-5aa1-a0f8-a6f0346eb025', 'sales_material_no', '销售料号', 'TEXT', false, '{}', 6, NULL, '2026-09-09 02:34:26.116236', NULL, '2026-09-09 02:34:26.116236', 'ACTIVE');

-- ---- semantic_edge: 78 行(Flyway 迁移种子) ----
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000001', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', '26090801-0000-4000-8000-000000000001', 'LOOKUP', 'MANY_TO_ONE', 1, 'PART_NAME', 'NA', NULL, 'task-260908 B-2：QUOTE MATERIAL_BOM → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000002', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', '26090801-0000-4000-8000-000000000003', 'LOOKUP', 'MANY_TO_ONE', 2, 'PART_NAME', 'NA', NULL, 'task-260908 B-2：QUOTE MATERIAL_BOM → RECIPE_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000003', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', '26090801-0000-4000-8000-000000000002', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：QUOTE MATERIAL_BOM → MAT_PROD_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000004', 'a5a429c2-cbd6-55aa-9de2-edaf75ff72c0', '26090801-0000-4000-8000-000000000001', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：QUOTE MATERIAL → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000005', 'e77545bb-55a9-5a52-be7f-b7be932020ea', '26090801-0000-4000-8000-000000000003', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：QUOTE ELEMENT_BOM → RECIPE_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000006', '95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4', '26090801-0000-4000-8000-000000000001', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：QUOTE ANNUAL_DISCOUNT → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000007', '2368a39c-75f2-542a-942c-916e34da8e70', '26090801-0000-4000-8000-000000000001', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：QUOTE ASSEMBLY_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000008', '9901a057-2883-5893-9e89-c6232a0997fe', '26090801-0000-4000-8000-000000000001', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：QUOTE ASSEMBLY_FEE_ANNUAL → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000009', '0fe87f1b-6706-56a4-9289-17df2b6aae5e', '26090801-0000-4000-8000-000000000001', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：QUOTE FINISHED_OTHER_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000010', '817fe5f7-66df-5551-b85c-762cf908281b', '26090801-0000-4000-8000-000000000001', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：QUOTE INCOMING_ANNUAL → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000011', '38f4367d-ae0f-5a8e-850e-d0e56506a709', '26090801-0000-4000-8000-000000000001', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：QUOTE INCOMING_FIXED_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000012', '2e300dc7-c63c-56f4-97e6-b739b5730501', '26090801-0000-4000-8000-000000000001', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：QUOTE INCOMING_OTHER_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('903b6985-6447-55a2-ac67-6781a26ea82b', 'bce970df-428e-55d0-b799-07853b82aa5c', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'd1e1147c-a639-4156-aeac-9f938a65ad05', '2026-09-06 18:25:58.288814', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000013', '6bdfd52b-1146-58a1-aef3-2b54383649cf', '26090801-0000-4000-8000-000000000001', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：QUOTE INCOMING_RECOVERY → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('e61dde42-6ab3-53d2-b566-dd01e0ed32f4', 'a5a429c2-cbd6-55aa-9de2-edaf75ff72c0', '613b7811-0156-5ec9-b851-40a1579d0734', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'B-1：物料 → 客户料号，按 material_no 左连。物料为主（用户 2026-09-07 裁决，推翻早前「以客户料号作为主表」）。没有客户料号的物料行照常出现、客户料号列为空（AC-6①）。', 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000014', '4dfd7565-b6cf-56d6-b3ff-b98eaeca18ae', '26090801-0000-4000-8000-000000000001', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：QUOTE PLATING_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000015', '75ccb22f-1b39-5382-902d-bb48a6d10303', '26090801-0000-4000-8000-000000000001', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：QUOTE SELF_PROCESS_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000016', 'ebfadcce-598f-54ca-9cb0-ea8871cd3f7a', '26090801-0000-4000-8000-000000000001', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：QUOTE SUB_COMPONENT_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000017', 'b9a3018d-6536-5340-b54d-39069f8dfd61', '26090801-0000-4000-8000-000000000004', 'LOOKUP', 'MANY_TO_ONE', 1, 'PART_NAME', 'NA', NULL, 'task-260908 B-2：COST_BASIC MATERIAL_BOM → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000018', 'b9a3018d-6536-5340-b54d-39069f8dfd61', '26090801-0000-4000-8000-000000000005', 'LOOKUP', 'MANY_TO_ONE', 2, 'PART_NAME', 'NA', NULL, 'task-260908 B-2：COST_BASIC MATERIAL_BOM → RECIPE_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000019', '7a85578d-ab5e-5b2e-9ea7-0da8d4a6c864', '26090801-0000-4000-8000-000000000004', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_BASIC MATERIAL → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000020', '531951d0-cb82-5925-a127-706821bfc8d1', '26090801-0000-4000-8000-000000000005', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_BASIC ELEMENT_BOM → RECIPE_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('9e8646a8-106a-56f1-b5f2-af96190f9e9a', 'fe9b5007-844b-5551-8c03-8ac017ad1f2a', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('00ad3374-9fb7-5683-96c2-cb46640e99a3', 'c3a6e4fe-8488-5c76-874a-c410da2913fc', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('347dfc1e-d428-5996-85e1-d2d475cf69b4', '548027b5-43af-5c2b-969a-cc51abd5ca5c', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('c00ff53b-6662-50cb-b251-c61ff025efa3', '3d4988fb-c7ac-51ee-ba37-7dfa61e18148', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('2a905de6-7194-5553-9280-d606c9db65a3', '52784f35-2b0f-5aa1-a0f8-a6f0346eb025', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000021', 'c3a6e4fe-8488-5c76-874a-c410da2913fc', '26090801-0000-4000-8000-000000000004', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_BASIC FINISHED_FIXED_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000022', '026543e0-4815-54c5-a69e-78884c73c26f', '26090801-0000-4000-8000-000000000004', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_BASIC FINISHED_RATIO_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000023', '5f0373f8-500c-5c37-8cad-da453bfd0250', '26090801-0000-4000-8000-000000000004', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_BASIC INCOMING_OTHER_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000024', '1032a3fe-5e51-5a9c-ade8-14d73706237c', '26090801-0000-4000-8000-000000000004', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_BASIC INCOMING_OTHER_FIXED_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000025', 'd5764c1d-f2a7-510a-8853-2cf84feb4463', '26090801-0000-4000-8000-000000000004', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_BASIC INCOMING_PROCESS_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000026', '3d0bc5c6-200a-5a24-b505-f36df668cd02', '26090801-0000-4000-8000-000000000004', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_BASIC OUTSOURCED_PROCESS → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000027', '4fc0c31b-e623-56c3-986d-5d0a5af60181', '26090801-0000-4000-8000-000000000004', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_BASIC PROCESS_ASSEMBLY_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000028', 'bce970df-428e-55d0-b799-07853b82aa5c', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', 1, 'PART_NAME', 'NA', NULL, 'task-260908 B-2：COST_DETAIL MATERIAL_BOM → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000029', 'bce970df-428e-55d0-b799-07853b82aa5c', '26090801-0000-4000-8000-000000000007', 'LOOKUP', 'MANY_TO_ONE', 2, 'PART_NAME', 'NA', NULL, 'task-260908 B-2：COST_DETAIL MATERIAL_BOM → RECIPE_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000030', '35d18fcf-cf27-573c-9cf7-2c9b2bf9e13a', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL MATERIAL → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('4bced0a0-af25-52c1-8cf9-b09eee85216e', 'ce1f43ff-c1b3-554a-b7e0-7d60cf70ddbb', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000031', '52784f35-2b0f-5aa1-a0f8-a6f0346eb025', '26090801-0000-4000-8000-000000000007', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL ELEMENT_BOM → RECIPE_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000032', 'c9780071-c1ed-5cd8-8dc6-b852a0defe39', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL AUXILIARY_ENERGY → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000033', 'b44cbbe9-11a8-52d4-b82d-f7b81d0a7cb4', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL CAPACITY → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000034', '06aa16e3-ef35-5a60-8132-8d2257834dc4', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL CONSUMABLE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000035', 'fe9b5007-844b-5551-8c03-8ac017ad1f2a', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL DEPRECIATION → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000036', 'ff7b6f55-70c2-59f3-a1ea-f3f021e3d49a', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL FINISHED_FIXED_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000037', '8b480f2d-8a0e-5824-a417-34d53a04b916', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL FINISHED_RATIO_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000038', '0547bede-2926-5518-bcbc-927bcad086b2', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL INCOMING_OTHER_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000039', '345562dc-5eb3-5789-aa24-2d01b3c32f7d', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL INCOMING_OTHER_FIXED_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000040', '548027b5-43af-5c2b-969a-cc51abd5ca5c', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL INCOMING_PROCESS_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000041', '8238839a-e1e7-5e8f-b315-c8e976cebd1f', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL OUTSOURCED_PROCESS → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000042', 'ce1f43ff-c1b3-554a-b7e0-7d60cf70ddbb', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL PACKAGING → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000043', '3a0fdb6d-86c9-5838-a4cf-ec25f08f7b38', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL PLATING_COST → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000044', 'a42b6cd0-9fde-5616-9566-8be8c50e870b', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL PROCESS_ASSEMBLY_FEE → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('1a0b8b69-23d6-593a-8924-cf7774dd228c', '7a85578d-ab5e-5b2e-9ea7-0da8d4a6c864', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('7cb23abf-828c-58b1-a0c5-22523a5dc339', 'b9a3018d-6536-5340-b54d-39069f8dfd61', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('2b92049a-1899-544b-94e5-adfe5e6ac64a', '345562dc-5eb3-5789-aa24-2d01b3c32f7d', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('18efe81e-efb7-5eb0-846a-978e24585409', '35d18fcf-cf27-573c-9cf7-2c9b2bf9e13a', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('9a4cbfdc-7243-56db-bcdb-210c9b5e9387', 'b44cbbe9-11a8-52d4-b82d-f7b81d0a7cb4', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('c3925dda-d8a8-5175-b939-fc95ea2b769a', '3d0bc5c6-200a-5a24-b505-f36df668cd02', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('334a93b6-9095-500b-9b5b-d7bb9e4d6233', '0547bede-2926-5518-bcbc-927bcad086b2', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('92e6247a-2870-508e-a0f6-cc9e60dc5174', '191da8dc-59f4-56de-8541-170e792c253e', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('11a21a3f-c77e-5a0e-842e-3fab1271b5a1', '06aa16e3-ef35-5a60-8132-8d2257834dc4', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000045', '191da8dc-59f4-56de-8541-170e792c253e', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL PRODUCTION_ENERGY → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090803-0000-4000-8000-000000000046', '3d4988fb-c7ac-51ee-ba37-7dfa61e18148', '26090801-0000-4000-8000-000000000006', 'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'task-260908 B-2：COST_DETAIL TOOLING → MAT_NAME_LK', NULL, '2026-09-08 01:42:08.536602', NULL, '2026-09-08 01:42:08.536602', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090904-0000-4000-8000-000000000001', '531951d0-cb82-5925-a127-706821bfc8d1', '26090901-0000-4000-8000-000000000001', 'PRICE', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'repair-260909：COST_BASIC ELEMENT_BOM → FUNC_ELEMENT_PRICE（双键：element_code + sales_material_no）', NULL, '2026-09-09 02:34:26.116236', NULL, '2026-09-09 02:34:26.116236', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('26090904-0000-4000-8000-000000000002', '52784f35-2b0f-5aa1-a0f8-a6f0346eb025', '26090901-0000-4000-8000-000000000002', 'PRICE', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, 'repair-260909：COST_DETAIL ELEMENT_BOM → FUNC_ELEMENT_PRICE（双键：element_code + sales_material_no）', NULL, '2026-09-09 02:34:26.116236', NULL, '2026-09-09 02:34:26.116236', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('5b1bfc30-551b-511c-bb2b-42becb609a06', 'e77545bb-55a9-5a52-be7f-b7be932020ea', 'ddc7fafa-0fd3-5c47-a86c-e3f8b86b0f76', 'PRICE', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL, '双条件 JOIN；cep.material_no 必须与 hf_part_no 表达式逐字一致（AC-1⑤）', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('8686698f-d890-5e15-97b9-d8a18b76efb4', '531951d0-cb82-5925-a127-706821bfc8d1', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('47e07ef5-7485-56c2-8f9c-8c07f43e0711', 'd5764c1d-f2a7-510a-8853-2cf84feb4463', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('01f12c99-1ee4-5347-a0de-1a05007159e6', '4fc0c31b-e623-56c3-986d-5d0a5af60181', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('1faa9af2-05ca-5e4b-8daa-40670c45f338', '026543e0-4815-54c5-a69e-78884c73c26f', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('afdb3ad4-d177-5d64-800c-9fcd94d011d3', 'c9780071-c1ed-5cd8-8dc6-b852a0defe39', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('9a96a06d-a39d-5fd7-ac3c-f4d1c238d678', '5f0373f8-500c-5c37-8cad-da453bfd0250', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('3da99248-fafc-5aee-8929-492d4520eb13', '1032a3fe-5e51-5a9c-ade8-14d73706237c', 'ab8fdf38-9036-5d60-ba32-e50fa19c1d7a', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('85224486-bf5e-5488-bad7-3781eb3dbda7', 'a42b6cd0-9fde-5616-9566-8be8c50e870b', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('8fe61eec-f1c1-5dea-bb72-99e3b70fecc4', '8238839a-e1e7-5e8f-b315-c8e976cebd1f', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('ab6cdf06-54d7-5fca-9857-f788b5d5baa0', '8b480f2d-8a0e-5824-a417-34d53a04b916', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('9577a41b-e34c-5f31-9c6d-598911fbdb72', '3a0fdb6d-86c9-5838-a4cf-ec25f08f7b38', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);
INSERT INTO public.semantic_edge (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group, assert_status, assert_sample_rows, note, created_by, created_at, updated_by, updated_at, status, fallback_to_join_key) VALUES ('b891f7cf-be63-56f4-b1e2-f7263f6ae3fa', 'ff7b6f55-70c2-59f3-a1ea-f3f021e3d49a', 'ab6300cc-e3e7-5e69-bf2c-c86e55d9d3b0', 'NARROW', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-04 17:58:23.485924', 'ACTIVE', false);

-- ---- semantic_edge_key: 95 行(Flyway 迁移种子) ----
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000001', '26090803-0000-4000-8000-000000000001', 'input_material_no', 'material_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000002', '26090803-0000-4000-8000-000000000002', 'input_material_no', 'code', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000003', '26090803-0000-4000-8000-000000000003', 'material_no', 'material_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000004', '26090803-0000-4000-8000-000000000004', 'material_no', 'material_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000005', '26090803-0000-4000-8000-000000000005', 'material_part_no', 'code', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000006', '26090803-0000-4000-8000-000000000006', 'material_no', 'material_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000007', '26090803-0000-4000-8000-000000000007', 'material_no', 'material_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000008', '26090803-0000-4000-8000-000000000008', 'material_no', 'material_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000009', '26090803-0000-4000-8000-000000000009', 'material_no', 'material_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000010', '26090803-0000-4000-8000-000000000010', 'input_material_no', 'material_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000011', '26090803-0000-4000-8000-000000000011', 'input_material_no', 'material_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000012', '26090803-0000-4000-8000-000000000012', 'input_material_no', 'material_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000013', '26090803-0000-4000-8000-000000000013', 'input_material_no', 'material_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000014', '26090803-0000-4000-8000-000000000014', 'material_no', 'material_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000015', '26090803-0000-4000-8000-000000000015', 'input_material_no', 'material_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000016', '26090803-0000-4000-8000-000000000016', 'sub_component_no', 'material_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000017', '26090803-0000-4000-8000-000000000017', 'component_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000018', '26090803-0000-4000-8000-000000000018', 'component_no', 'code', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000019', '26090803-0000-4000-8000-000000000019', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000020', '26090803-0000-4000-8000-000000000020', 'material_part_no', 'code', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000021', '26090803-0000-4000-8000-000000000021', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000022', '26090803-0000-4000-8000-000000000022', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000023', '26090803-0000-4000-8000-000000000023', 'incoming_material_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000024', '26090803-0000-4000-8000-000000000024', 'incoming_material_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000025', '26090803-0000-4000-8000-000000000025', 'incoming_material_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000026', '26090803-0000-4000-8000-000000000026', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000027', '26090803-0000-4000-8000-000000000027', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000028', '26090803-0000-4000-8000-000000000028', 'component_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000029', '26090803-0000-4000-8000-000000000029', 'component_no', 'code', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000030', '26090803-0000-4000-8000-000000000030', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000031', '26090803-0000-4000-8000-000000000031', 'material_part_no', 'code', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000032', '26090803-0000-4000-8000-000000000032', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('ca2dc3ea-6638-5b84-baed-02b50132d793', '1a0b8b69-23d6-593a-8924-cf7774dd228c', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('fa8e2b4b-12c1-5ce1-a24f-3a9fc64e0cbe', '7cb23abf-828c-58b1-a0c5-22523a5dc339', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('201b7bea-f360-5d54-8377-154b54cad189', '8686698f-d890-5e15-97b9-d8a18b76efb4', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('6974afd6-8045-5aff-ae77-9a6b2c8fee0f', '47e07ef5-7485-56c2-8f9c-8c07f43e0711', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('4e733e76-78b4-51b4-8d4d-8369008c4330', '9a96a06d-a39d-5fd7-ac3c-f4d1c238d678', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('39be2fea-94e5-574f-a896-4caaf0271b05', '3da99248-fafc-5aee-8929-492d4520eb13', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('5275f81a-242b-5d38-a8f4-bb28d79de3c9', '01f12c99-1ee4-5347-a0de-1a05007159e6', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('f403a1a5-525c-5dfe-b42c-d8d282a78846', 'c3925dda-d8a8-5175-b939-fc95ea2b769a', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('81cdacec-873e-5598-86c2-4cd845daa439', '1faa9af2-05ca-5e4b-8daa-40670c45f338', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('97b8e149-28e7-5deb-a6fe-3ce5cbcc3e64', '00ad3374-9fb7-5683-96c2-cb46640e99a3', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('72f25a8a-ae77-5bd0-b485-819d773476b1', '18efe81e-efb7-5eb0-846a-978e24585409', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('461834b2-871a-5edf-94f8-ae80738aa2c1', '903b6985-6447-55a2-ac67-6781a26ea82b', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('03693348-38ad-5741-9118-9796fb6a75e7', '2a905de6-7194-5553-9280-d606c9db65a3', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('47ae9cd6-454b-578b-9e6b-524d2a11e9ac', '9a4cbfdc-7243-56db-bcdb-210c9b5e9387', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('b00d4dd6-f865-5d3f-9840-fb4611be269b', '9e8646a8-106a-56f1-b5f2-af96190f9e9a', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('c305ef6c-2552-5cc1-b4f0-17a94d7435d9', '92e6247a-2870-508e-a0f6-cc9e60dc5174', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('f230de69-4590-529e-8af6-40ade08ff88a', 'afdb3ad4-d177-5d64-800c-9fcd94d011d3', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('975eb625-4b66-5646-9d13-f5f4c0bd81e4', 'c00ff53b-6662-50cb-b251-c61ff025efa3', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('6ba677aa-7a29-581c-b009-5703f96f3519', '11a21a3f-c77e-5a0e-842e-3fab1271b5a1', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('f238af5c-c62e-5dcc-98fd-a53579d00b4e', '4bced0a0-af25-52c1-8cf9-b09eee85216e', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('b611212f-83fd-5fa0-919c-050bc7f2aa46', '347dfc1e-d428-5996-85e1-d2d475cf69b4', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('75107ebe-4ae3-52cc-934c-d2ae85e81c24', '334a93b6-9095-500b-9b5b-d7bb9e4d6233', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('19ea5914-2756-50f8-bc0e-5a03385f4bae', '2b92049a-1899-544b-94e5-adfe5e6ac64a', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('2138579b-a87f-5073-9d64-bd145e197348', '85224486-bf5e-5488-bad7-3781eb3dbda7', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('c4361071-1de6-5f12-9d79-3fd68867b6a0', '8fe61eec-f1c1-5dea-bb72-99e3b70fecc4', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('33773b0a-2789-5e91-b2c7-29fd81d2400a', '9577a41b-e34c-5f31-9c6d-598911fbdb72', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('2e54b8c7-8487-533e-82e0-47208597a39c', 'ab6cdf06-54d7-5fca-9857-f788b5d5baa0', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('05d69102-2c8d-5ea1-8c32-a9007e495ba6', 'b891f7cf-be63-56f4-b1e2-f7263f6ae3fa', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('8fe18c2f-3d9b-5a7d-a817-84ea8ccc2762', '5b1bfc30-551b-511c-bb2b-42becb609a06', 'element_code', 'element_code', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('8d31087a-b15a-5a9a-bc45-2a02fd221efe', '5b1bfc30-551b-511c-bb2b-42becb609a06', 'material_no', 'material_no', 1);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000033', '26090803-0000-4000-8000-000000000033', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000034', '26090803-0000-4000-8000-000000000034', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000035', '26090803-0000-4000-8000-000000000035', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000036', '26090803-0000-4000-8000-000000000036', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000037', '26090803-0000-4000-8000-000000000037', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000038', '26090803-0000-4000-8000-000000000038', 'incoming_material_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000039', '26090803-0000-4000-8000-000000000039', 'incoming_material_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000040', '26090803-0000-4000-8000-000000000040', 'incoming_material_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000041', '26090803-0000-4000-8000-000000000041', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000042', '26090803-0000-4000-8000-000000000042', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000043', '26090803-0000-4000-8000-000000000043', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000044', '26090803-0000-4000-8000-000000000044', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('5cf48966-d02d-5968-8659-7bce73965d01', 'e61dde42-6ab3-53d2-b566-dd01e0ed32f4', 'material_no', 'material_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000045', '26090803-0000-4000-8000-000000000045', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090804-0000-4000-8000-000000000046', '26090803-0000-4000-8000-000000000046', 'production_no', 'production_no', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090805-0000-4000-8000-000000000001', '26090803-0000-4000-8000-000000000001', 'customer_no', 'customer_no', 1);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090805-0000-4000-8000-000000000003', '26090803-0000-4000-8000-000000000003', 'customer_no', 'customer_no', 1);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090805-0000-4000-8000-000000000004', '26090803-0000-4000-8000-000000000004', 'customer_no', 'customer_no', 1);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090805-0000-4000-8000-000000000006', '26090803-0000-4000-8000-000000000006', 'customer_no', 'customer_no', 1);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090805-0000-4000-8000-000000000007', '26090803-0000-4000-8000-000000000007', 'customer_no', 'customer_no', 1);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090805-0000-4000-8000-000000000008', '26090803-0000-4000-8000-000000000008', 'customer_no', 'customer_no', 1);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090805-0000-4000-8000-000000000009', '26090803-0000-4000-8000-000000000009', 'customer_no', 'customer_no', 1);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090805-0000-4000-8000-000000000010', '26090803-0000-4000-8000-000000000010', 'customer_no', 'customer_no', 1);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090805-0000-4000-8000-000000000011', '26090803-0000-4000-8000-000000000011', 'customer_no', 'customer_no', 1);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090805-0000-4000-8000-000000000012', '26090803-0000-4000-8000-000000000012', 'customer_no', 'customer_no', 1);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090805-0000-4000-8000-000000000013', '26090803-0000-4000-8000-000000000013', 'customer_no', 'customer_no', 1);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090805-0000-4000-8000-000000000014', '26090803-0000-4000-8000-000000000014', 'customer_no', 'customer_no', 1);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090805-0000-4000-8000-000000000015', '26090803-0000-4000-8000-000000000015', 'customer_no', 'customer_no', 1);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090805-0000-4000-8000-000000000016', '26090803-0000-4000-8000-000000000016', 'customer_no', 'customer_no', 1);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090905-0000-4000-8000-000000000001', '26090904-0000-4000-8000-000000000001', 'element_code', 'element_code', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090905-0000-4000-8000-000000000002', '26090904-0000-4000-8000-000000000001', 'sales_material_no', 'material_no', 1);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090905-0000-4000-8000-000000000003', '26090904-0000-4000-8000-000000000002', 'element_code', 'element_code', 0);
INSERT INTO public.semantic_edge_key (id, edge_id, left_column, right_column, seq) VALUES ('26090905-0000-4000-8000-000000000004', '26090904-0000-4000-8000-000000000002', 'sales_material_no', 'material_no', 1);

-- ---- semantic_tab_view: 48 行(Flyway 迁移种子) ----
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('9c738931-1713-5201-93b2-939d5e18c03e', '主件', '', NULL, 'a5a429c2-cbd6-55aa-9de2-edaf75ff72c0', '{}', 'QUOTE', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('26be1386-d7ab-56e9-aa7f-aed74d1deac8', '零件', '', NULL, 'fd01d644-d2ab-561a-ac26-c50cf8124f31', '{}', 'QUOTE', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('28a6b98e-1a8c-5ffb-afd6-a33605eb7aae', '外购件', '', NULL, 'fd01d644-d2ab-561a-ac26-c50cf8124f31', '{}', 'QUOTE', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('f4ac17fa-c95f-5cac-88e9-dbf2c99ddd45', '材质元素', '', NULL, 'e77545bb-55a9-5a52-be7f-b7be932020ea', '{}', 'QUOTE', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('398db056-80d4-5ba5-b77f-f283f92d940b', '费用类', 'INCOMING_FIXED_FEE', '来料固定加工费', '38f4367d-ae0f-5a8e-850e-d0e56506a709', '{}', 'QUOTE', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('b5bab1e4-a611-593d-9c67-256a8650f99b', '费用类', 'INCOMING_OTHER_FEE', '来料其他费用', '2e300dc7-c63c-56f4-97e6-b739b5730501', '{}', 'QUOTE', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('da7cb8ed-748e-5690-a248-0eb975182b07', '费用类', 'INCOMING_RECOVERY', '来料回收折扣', '6bdfd52b-1146-58a1-aef3-2b54383649cf', '{}', 'QUOTE', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('fa05a4bf-6040-5409-b1df-960a2a70b950', '费用类', 'SELF_PROCESS_FEE', '自制加工费', '75ccb22f-1b39-5382-902d-bb48a6d10303', '{}', 'QUOTE', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('be21d699-41e9-5091-8f5f-dbd51c0e7b48', '费用类', 'FINISHED_OTHER_FEE', '成品其他费用', '0fe87f1b-6706-56a4-9289-17df2b6aae5e', '{}', 'QUOTE', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('c34b8832-17cf-5aed-9cd9-e329329e19e8', '费用类', 'SUB_COMPONENT_FEE', '组成件其他费用', 'ebfadcce-598f-54ca-9cb0-ea8871cd3f7a', '{}', 'QUOTE', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('8c50c7bf-2469-55f7-8119-bed1481d12f4', '费用类', 'ASSEMBLY_FEE', '组装加工费', '2368a39c-75f2-542a-942c-916e34da8e70', '{}', 'QUOTE', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('374bd2ca-ed20-57b5-a1b9-04cc10afe988', '费用类', 'PLATING_FEE', '电镀费用', '4dfd7565-b6cf-56d6-b3ff-b98eaeca18ae', '{}', 'QUOTE', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('e4b30ebd-fd20-567b-8604-a6b16475459f', '主件', '', NULL, '7a85578d-ab5e-5b2e-9ea7-0da8d4a6c864', '{}', 'COST_BASIC', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('d74a7794-4c33-5e95-937e-c2baf536ad14', '零件', '', NULL, 'b9a3018d-6536-5340-b54d-39069f8dfd61', '{}', 'COST_BASIC', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('18293719-a46d-540a-a9e1-9522acb06303', '外购件', '', NULL, 'b9a3018d-6536-5340-b54d-39069f8dfd61', '{}', 'COST_BASIC', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('1584453d-6f8c-50cb-b0cf-570ff1f4b9bb', '材质元素', '', NULL, '531951d0-cb82-5925-a127-706821bfc8d1', '{}', 'COST_BASIC', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('878dd7db-87c3-5df3-a555-cf0d3c9af1f4', '费用类', 'INCOMING_OTHER_FEE', '来料其他费用', '5f0373f8-500c-5c37-8cad-da453bfd0250', '{}', 'COST_BASIC', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('86df3954-bfe6-5a0c-a1e4-df46222fda9e', '费用类', 'INCOMING_OTHER_FIXED_FEE', '来料其他固定费用', '1032a3fe-5e51-5a9c-ade8-14d73706237c', '{}', 'COST_BASIC', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('539f80ff-af46-5459-b5aa-dc6fd804a80a', '费用类', 'PROCESS_ASSEMBLY_FEE', '加工费&组装费', '4fc0c31b-e623-56c3-986d-5d0a5af60181', '{}', 'COST_BASIC', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('8328dd9e-c7cc-5e6f-96d2-3bad6dbd92a3', '费用类', 'OUTSOURCED_PROCESS', '其他外加工成本', '3d0bc5c6-200a-5a24-b505-f36df668cd02', '{}', 'COST_BASIC', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('d7424283-0220-5ed9-ac8e-edeead5140d6', '费用类', 'FINISHED_RATIO_FEE', '成品其他比例费用', '026543e0-4815-54c5-a69e-78884c73c26f', '{}', 'COST_BASIC', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('e17e487c-39f3-578f-b4c9-433487c19acb', '费用类', 'FINISHED_FIXED_FEE', '成品其他固定费用', 'c3a6e4fe-8488-5c76-874a-c410da2913fc', '{}', 'COST_BASIC', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('578a1f56-bb18-50b0-be0e-3f2259e0b10e', '主件', '', NULL, '35d18fcf-cf27-573c-9cf7-2c9b2bf9e13a', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('ee527fae-1e4c-5f11-b36f-d0021ca0534c', '零件', '', NULL, 'bce970df-428e-55d0-b799-07853b82aa5c', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('29d308cf-ba6f-5e67-af97-8609e0e22fa8', '外购件', '', NULL, 'bce970df-428e-55d0-b799-07853b82aa5c', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('76b7dd2a-d4f4-5152-ba9f-9fae6991a5b9', '材质元素', '', NULL, '52784f35-2b0f-5aa1-a0f8-a6f0346eb025', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('5c746c46-625d-5c68-ac3c-9cf5537deea6', '费用类', 'CAPACITY', '产能', 'b44cbbe9-11a8-52d4-b82d-f7b81d0a7cb4', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('14de6b71-d8ce-58d6-b738-0f1414c2e609', '费用类', 'DEPRECIATION', '设备折旧成本', 'fe9b5007-844b-5551-8c03-8ac017ad1f2a', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('7f177c7c-a71e-57d9-9320-66bc5fd1e7f8', '费用类', 'PRODUCTION_ENERGY', '生产设备能耗', '191da8dc-59f4-56de-8541-170e792c253e', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('0ceb55cd-ce5b-54ee-84b0-8408bfe7b47d', '费用类', 'AUXILIARY_ENERGY', '辅助设备能耗', 'c9780071-c1ed-5cd8-8dc6-b852a0defe39', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('91effb3f-5bbb-55f3-95b5-4262a39cd05f', '费用类', 'TOOLING', '模具工装成本', '3d4988fb-c7ac-51ee-ba37-7dfa61e18148', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('ae4e65ca-aa44-52f5-baec-f04162d2170c', '费用类', 'CONSUMABLE', '生产耗材BOM', '06aa16e3-ef35-5a60-8132-8d2257834dc4', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('725fc648-0b1b-5b51-9dc0-361f867f5329', '费用类', 'PACKAGING', '包装材料BOM', 'ce1f43ff-c1b3-554a-b7e0-7d60cf70ddbb', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('8c1ec4ad-fc61-58c5-8b9d-f76de11c7732', '费用类', 'INCOMING_PROCESS_FEE', '来料加工费', '548027b5-43af-5c2b-969a-cc51abd5ca5c', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('9d255d89-6a8c-5972-b9f2-ae72c5e81c03', '费用类', 'INCOMING_OTHER_FEE', '来料其他费用', '0547bede-2926-5518-bcbc-927bcad086b2', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('1c06f298-420a-5df2-9f83-440070fddbb3', '费用类', 'INCOMING_OTHER_FIXED_FEE', '来料其他固定费用', '345562dc-5eb3-5789-aa24-2d01b3c32f7d', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('2b5fabaf-c206-5522-b921-a008560e2750', '费用类', 'PROCESS_ASSEMBLY_FEE', '加工费&组装费', 'a42b6cd0-9fde-5616-9566-8be8c50e870b', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('c5c79a79-94b8-512d-92f3-22f0ecabca54', '费用类', 'OUTSOURCED_PROCESS', '其他外加工成本', '8238839a-e1e7-5e8f-b315-c8e976cebd1f', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('65da6871-95fc-5aaa-985f-cdfdbc37bc27', '费用类', 'PLATING_COST', '电镀成本', '3a0fdb6d-86c9-5838-a4cf-ec25f08f7b38', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('647aefe0-5c35-505c-9cab-ea2181f83b64', '费用类', 'FINISHED_RATIO_FEE', '成品其他比例费用', '8b480f2d-8a0e-5824-a417-34d53a04b916', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('d771adae-74ba-5a92-9403-1bc0a436bd6f', 'BOM', '', NULL, 'fd01d644-d2ab-561a-ac26-c50cf8124f31', '{}', 'QUOTE', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-05 02:04:09.34888', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('2ed644ce-833b-5220-88bb-6aa57d26183a', 'BOM', '', NULL, 'b9a3018d-6536-5340-b54d-39069f8dfd61', '{}', 'COST_BASIC', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-05 02:04:09.34888', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('919bdd88-8ee1-5240-992f-fb4d45e31a82', 'BOM', '', NULL, 'bce970df-428e-55d0-b799-07853b82aa5c', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', 'seed', '2026-09-05 02:04:09.34888', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('0333f809-218f-51fe-9e06-71757738654e', '费用类', 'INCOMING_PROCESS_FEE', '来料加工费', 'd5764c1d-f2a7-510a-8853-2cf84feb4463', '{}', 'COST_BASIC', 'seed', '2026-09-03 23:12:25.660569', 'b59-test', '2026-09-09 02:47:50.45418', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('18a2cf8b-eb95-508b-b0ad-20a1a0644d37', '费用类', 'FINISHED_FIXED_FEE', '成品其他固定费用', 'ff7b6f55-70c2-59f3-a1ea-f3f021e3d49a', '{}', 'COST_DETAIL', 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('f9034845-1358-5009-9fc7-b6ed32c6b608', '费用类', 'ANNUAL_DISCOUNT', '年降系数', '95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4', '{}', 'QUOTE', 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('7b9409fa-8bc2-574e-9a0b-c632006c2cad', '费用类', 'ASSEMBLY_FEE_ANNUAL', '组装加工费年降', '9901a057-2883-5893-9e89-c6232a0997fe', '{}', 'QUOTE', 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_tab_view (id, tab_type, variant_key, variant_label, anchor_node_id, switches, dialect, created_by, created_at, updated_by, updated_at, status) VALUES ('0b55d63e-f1ac-5264-9a20-ac91794af055', '费用类', 'INCOMING_ANNUAL', '来料年降', '817fe5f7-66df-5551-b85c-762cf908281b', '{}', 'QUOTE', 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');

-- ---- semantic_tab_view_node: 50 行(Flyway 迁移种子) ----
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('70ce2d3b-1aa2-5b0a-8c9e-c21812d6c4d7', '9c738931-1713-5201-93b2-939d5e18c03e', 'a5a429c2-cbd6-55aa-9de2-edaf75ff72c0', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('dcf66d0b-6704-51b8-a489-6a41979b4131', '26be1386-d7ab-56e9-aa7f-aed74d1deac8', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ae687299-7e1c-5b13-af60-89282e6580f7', '28a6b98e-1a8c-5ffb-afd6-a33605eb7aae', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('42fc288f-9714-5037-be4b-95f31e183fd4', 'd771adae-74ba-5a92-9403-1bc0a436bd6f', 'fd01d644-d2ab-561a-ac26-c50cf8124f31', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('8fe1f626-eacc-5d60-988b-a0c0521cfb64', 'f4ac17fa-c95f-5cac-88e9-dbf2c99ddd45', 'e77545bb-55a9-5a52-be7f-b7be932020ea', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('38921742-f391-53b9-8d10-0326bf7cc189', 'f4ac17fa-c95f-5cac-88e9-dbf2c99ddd45', 'ddc7fafa-0fd3-5c47-a86c-e3f8b86b0f76', 'AUX', '{}', 1, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('79212755-98a0-5d93-81cd-2668bbba3d5e', '398db056-80d4-5ba5-b77f-f283f92d940b', '38f4367d-ae0f-5a8e-850e-d0e56506a709', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('bc276ebc-5ece-50fb-a3ed-87190b976ed9', 'b5bab1e4-a611-593d-9c67-256a8650f99b', '2e300dc7-c63c-56f4-97e6-b739b5730501', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('c76615a8-253c-5d66-8d57-5dd350a78b56', 'da7cb8ed-748e-5690-a248-0eb975182b07', '6bdfd52b-1146-58a1-aef3-2b54383649cf', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('0857020c-7357-5431-8ea7-bbcd9a1e4bba', 'fa05a4bf-6040-5409-b1df-960a2a70b950', '75ccb22f-1b39-5382-902d-bb48a6d10303', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('256d8acb-84f6-5617-94d8-8ba323a04a9f', 'be21d699-41e9-5091-8f5f-dbd51c0e7b48', '0fe87f1b-6706-56a4-9289-17df2b6aae5e', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('4909dc52-dbb2-5caa-a302-7021edee7e78', 'c34b8832-17cf-5aed-9cd9-e329329e19e8', 'ebfadcce-598f-54ca-9cb0-ea8871cd3f7a', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('0b78f70b-1ce8-5e1a-8398-60a321d662e6', '8c50c7bf-2469-55f7-8119-bed1481d12f4', '2368a39c-75f2-542a-942c-916e34da8e70', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('28c00665-1760-5a3d-a7f5-c88920e78f23', '374bd2ca-ed20-57b5-a1b9-04cc10afe988', '4dfd7565-b6cf-56d6-b3ff-b98eaeca18ae', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('5382e5f4-629d-50f4-906c-85dbe4c945b5', 'e4b30ebd-fd20-567b-8604-a6b16475459f', '7a85578d-ab5e-5b2e-9ea7-0da8d4a6c864', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('e278d413-7f5d-5a6f-96ff-255db2d539c9', 'd74a7794-4c33-5e95-937e-c2baf536ad14', 'b9a3018d-6536-5340-b54d-39069f8dfd61', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('1c74e129-f856-5ff6-a976-bcb06ba84ec7', '18293719-a46d-540a-a9e1-9522acb06303', 'b9a3018d-6536-5340-b54d-39069f8dfd61', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('76c1a237-73e4-547f-909f-ba16e2e3dff4', '2ed644ce-833b-5220-88bb-6aa57d26183a', 'b9a3018d-6536-5340-b54d-39069f8dfd61', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('4fcd2fc1-d2c0-5512-a1a9-615d876db6fe', '1584453d-6f8c-50cb-b0cf-570ff1f4b9bb', '531951d0-cb82-5925-a127-706821bfc8d1', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('08dd9f5a-f946-5899-8bd5-829a16b17b38', '0333f809-218f-51fe-9e06-71757738654e', 'd5764c1d-f2a7-510a-8853-2cf84feb4463', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('98814cbd-2793-5474-9807-fa1a5f951b5e', '878dd7db-87c3-5df3-a555-cf0d3c9af1f4', '5f0373f8-500c-5c37-8cad-da453bfd0250', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ab5ae7f5-3c04-57d9-b1de-c94af83d155f', '86df3954-bfe6-5a0c-a1e4-df46222fda9e', '1032a3fe-5e51-5a9c-ade8-14d73706237c', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('f4d7a836-2bd3-582b-bbb6-468f7a8f3d3f', '539f80ff-af46-5459-b5aa-dc6fd804a80a', '4fc0c31b-e623-56c3-986d-5d0a5af60181', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('b9edf537-db99-50b7-81ab-4fbc6fc97963', '8328dd9e-c7cc-5e6f-96d2-3bad6dbd92a3', '3d0bc5c6-200a-5a24-b505-f36df668cd02', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('cbb13389-cb52-5f33-9ad3-2d9bbcb95e87', 'd7424283-0220-5ed9-ac8e-edeead5140d6', '026543e0-4815-54c5-a69e-78884c73c26f', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('21680662-dbc8-5e4b-93c0-cdd2a1160c84', 'e17e487c-39f3-578f-b4c9-433487c19acb', 'c3a6e4fe-8488-5c76-874a-c410da2913fc', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('16559411-2e90-5062-9b2f-8e44a6aa43f9', '578a1f56-bb18-50b0-be0e-3f2259e0b10e', '35d18fcf-cf27-573c-9cf7-2c9b2bf9e13a', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('eceb2edf-1fb7-5237-bb6c-5c489f49040e', 'ee527fae-1e4c-5f11-b36f-d0021ca0534c', 'bce970df-428e-55d0-b799-07853b82aa5c', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('4abe7d23-ed26-537b-9f86-6b01c810a186', '29d308cf-ba6f-5e67-af97-8609e0e22fa8', 'bce970df-428e-55d0-b799-07853b82aa5c', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('20f37ab3-94c1-5ff8-8aac-5e5b581dab3c', '919bdd88-8ee1-5240-992f-fb4d45e31a82', 'bce970df-428e-55d0-b799-07853b82aa5c', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('98e22ec8-b731-5143-bd0b-5a5f25196084', '76b7dd2a-d4f4-5152-ba9f-9fae6991a5b9', '52784f35-2b0f-5aa1-a0f8-a6f0346eb025', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('ec2b40b4-c986-5661-937c-1f2767e6f5eb', '5c746c46-625d-5c68-ac3c-9cf5537deea6', 'b44cbbe9-11a8-52d4-b82d-f7b81d0a7cb4', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('dd4d3cc9-ba75-5308-9265-692dd7df1591', '14de6b71-d8ce-58d6-b738-0f1414c2e609', 'fe9b5007-844b-5551-8c03-8ac017ad1f2a', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('88209373-4350-5b46-b116-7d824159bf1e', '7f177c7c-a71e-57d9-9320-66bc5fd1e7f8', '191da8dc-59f4-56de-8541-170e792c253e', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('627c8988-bdf6-574b-b70a-c7fed5a25126', '0ceb55cd-ce5b-54ee-84b0-8408bfe7b47d', 'c9780071-c1ed-5cd8-8dc6-b852a0defe39', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('61bb473e-b25d-5b44-920f-106ceea23967', '91effb3f-5bbb-55f3-95b5-4262a39cd05f', '3d4988fb-c7ac-51ee-ba37-7dfa61e18148', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('a3daa797-695e-58c5-abc1-1dbb8b89b7cf', 'ae4e65ca-aa44-52f5-baec-f04162d2170c', '06aa16e3-ef35-5a60-8132-8d2257834dc4', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('7691cb5a-6eca-50c6-8a12-0d1fe7d947a9', '725fc648-0b1b-5b51-9dc0-361f867f5329', 'ce1f43ff-c1b3-554a-b7e0-7d60cf70ddbb', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('0d0b9a59-5d2f-5c2b-982c-cce84eb84ea7', '8c1ec4ad-fc61-58c5-8b9d-f76de11c7732', '548027b5-43af-5c2b-969a-cc51abd5ca5c', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('bead19e8-7fd6-5b7b-bd0b-dcd14dd40f53', '9d255d89-6a8c-5972-b9f2-ae72c5e81c03', '0547bede-2926-5518-bcbc-927bcad086b2', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('90ba17dc-1e09-5288-b414-ad3d8811ee42', '1c06f298-420a-5df2-9f83-440070fddbb3', '345562dc-5eb3-5789-aa24-2d01b3c32f7d', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('41cb6681-6f59-59e7-803f-f6ec1b19c219', '2b5fabaf-c206-5522-b921-a008560e2750', 'a42b6cd0-9fde-5616-9566-8be8c50e870b', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('e9c14f9d-164a-567c-9e72-2e45f8c16252', 'c5c79a79-94b8-512d-92f3-22f0ecabca54', '8238839a-e1e7-5e8f-b315-c8e976cebd1f', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('2c4ad26c-6269-5770-9864-1499f2a975ae', '65da6871-95fc-5aaa-985f-cdfdbc37bc27', '3a0fdb6d-86c9-5838-a4cf-ec25f08f7b38', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('a168b1a6-b959-5f7e-8cc2-271090d51e7a', '647aefe0-5c35-505c-9cab-ea2181f83b64', '8b480f2d-8a0e-5824-a417-34d53a04b916', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('564bc52b-82ff-56fb-afa0-57375f6f8d74', '18a2cf8b-eb95-508b-b0ad-20a1a0644d37', 'ff7b6f55-70c2-59f3-a1ea-f3f021e3d49a', 'MAIN', '{}', 0, 'seed', '2026-09-03 23:12:25.660569', NULL, '2026-09-03 23:12:25.660569', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('cf8001e2-80b8-5aab-b573-8957af8ec650', 'f9034845-1358-5009-9fc7-b6ed32c6b608', '95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4', 'MAIN', '{}', 0, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('28bf1913-8699-5a05-bf77-3dc27f9ed05f', '7b9409fa-8bc2-574e-9a0b-c632006c2cad', '9901a057-2883-5893-9e89-c6232a0997fe', 'MAIN', '{}', 0, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('8b4096eb-31e4-5d4b-84cd-f6c4dd146855', '0b55d63e-f1ac-5264-9a20-ac91794af055', '817fe5f7-66df-5551-b85c-762cf908281b', 'MAIN', '{}', 0, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');
INSERT INTO public.semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by, created_at, updated_by, updated_at, status) VALUES ('3301945e-a403-561f-b0b2-8f19d86df343', '9c738931-1713-5201-93b2-939d5e18c03e', '613b7811-0156-5ec9-b851-40a1579d0734', 'AUX', '{}', 1, 'seed', '2026-09-07 02:41:01.285968', NULL, '2026-09-07 02:41:01.285968', 'ACTIVE');

-- ---- flyway_schema_history: 基线 439 ----
-- 必须是 439(源库当前最高版本)。基线号偏低会让 Quarkus 启动时重放中间迁移,
-- 其中含非幂等语句(CREATE TABLE 无 IF NOT EXISTS / RENAME COLUMN / DROP CONSTRAINT 无 IF EXISTS),
-- 会因"表已存在 / 列不存在"直接启动失败。
INSERT INTO public.flyway_schema_history
  (installed_rank, version, description, type, script, checksum, installed_by, installed_on,
   execution_time, success)
VALUES (1, '439', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', NULL,
        'baseline', now(), 0, true);


-- ============================================================
-- 函数区(整体置于文件末尾; 函数体已改单引号且全 ASCII)
-- ============================================================

CREATE FUNCTION public.current_part_version(p_customer_product_no text, p_hf_part_no text) RETURNS integer
    LANGUAGE plpgsql STABLE
    AS '
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
';

CREATE FUNCTION public.f_customer_element_price(p_customer_no text, p_base_date date) RETURNS TABLE(element_code character varying, unit_price numeric, currency character varying, price_unit character varying)
    LANGUAGE sql STABLE
    AS '
WITH def AS (
    SELECT * FROM element_price_strategy
     WHERE customer_no = p_customer_no AND element_code IS NULL AND status = ''ACTIVE''
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
            AND x.status = ''ACTIVE''
      LEFT JOIN def d ON TRUE
     WHERE e.status = ''ACTIVE''
       AND (x.id IS NOT NULL OR d.id IS NOT NULL)
),
win AS (
    SELECT eff.*,
           CASE WHEN eff.method = ''LATEST'' THEN NULL
                ELSE (p_base_date - (eff.window_num || '' '' ||
                       CASE eff.window_unit
                            WHEN ''DAY''   THEN ''day''   WHEN ''WEEK'' THEN ''week''
                            WHEN ''MONTH'' THEN ''month'' ELSE ''year'' END)::interval)::date
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
          WHEN ''LATEST'' THEN (
              SELECT dp.raw_price FROM element_daily_price dp
               WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
                 AND dp.raw_price IS NOT NULL AND dp.price_date <= p_base_date
               ORDER BY dp.price_date DESC LIMIT 1)
          WHEN ''AVG'' THEN (
              SELECT AVG(dp.raw_price) FROM element_daily_price dp
               WHERE dp.element_name = w.element_code AND dp.source_id = w.source_id
                 AND dp.raw_price IS NOT NULL
                 AND dp.price_date BETWEEN w.win_from AND p_base_date)
          WHEN ''MAX'' THEN (
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
';

CREATE FUNCTION public.f_material_element_price(p_customer_no text, p_base_date date, p_pending_quotation_id uuid) RETURNS TABLE(material_no character varying, element_code character varying, unit_price numeric, currency character varying, price_unit character varying)
    LANGUAGE sql STABLE
    AS '
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
     WHERE customer_no IN (p_customer_no, ''_GLOBAL_'')
       AND (is_current = true OR pending_quotation_id = p_pending_quotation_id)
    UNION
    SELECT material_no FROM element_bom_item
     WHERE customer_no IN (p_customer_no, ''_GLOBAL_'')
       AND (is_current = true OR pending_quotation_id = p_pending_quotation_id)


    UNION
    SELECT material_no FROM ds_quote_material_bom
     WHERE customer_no = p_customer_no
    UNION
    SELECT material_no FROM ds_quote_element_bom
     WHERE customer_no = p_customer_no
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
';

CREATE FUNCTION public.f_material_element_price(p_customer_no text, p_base_date date) RETURNS TABLE(material_no character varying, element_code character varying, unit_price numeric, currency character varying, price_unit character varying)
    LANGUAGE sql STABLE
    AS '
    SELECT * FROM f_material_element_price(p_customer_no, p_base_date, NULL::uuid);
';

CREATE FUNCTION public.get_bom_components(p_material_no text) RETURNS TABLE(js integer, hf_part_no text, material_no text, component_no text)
    LANGUAGE sql STABLE
    AS '
 WITH RECURSIVE bom_tree AS (
        SELECT 1 js,material_no dj,mbi.*
        FROM material_bom_item mbi
        WHERE mbi.material_no = p_material_no
          AND mbi.customer_no = ''_GLOBAL_''

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
';

CREATE FUNCTION public.get_bom_components(p_material_no text, p_customer_no text) RETURNS TABLE(component_no text)
    LANGUAGE sql STABLE
    AS '
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
';


-- ============================================================
-- 导入后自检(逐条核对期望值)
-- ============================================================
-- SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'; -- 期望 251
-- SELECT count(*) FROM information_schema.views  WHERE table_schema='public';                             -- 期望 29
-- SELECT count(*) FROM information_schema.sequences WHERE sequence_schema='public';                       -- 期望 106
-- SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public';    -- 期望 6
-- SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
--   WHERE n.nspname='public' AND c.relname ~ '^v_compat_';                                              -- 期望 0
-- SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'
--   AND c.relkind='r' AND (c.relname ~ '^(_bak|bak_|zz_|tmp_|temp_)'
--        OR c.relname ~ '(_backup|_bak)(_[0-9]+)?' || chr(36) || ''
--        OR c.relname ~ '[0-9]{6,8}' || chr(36) || '');                                     -- 期望 0(无人工备份表)
-- SELECT max(version::numeric) FROM flyway_schema_history;                                               -- 期望 439
-- SELECT count(*) FROM "user";                                                                           -- 期望 1
-- SELECT username, role, status, is_first_login FROM "user";                       -- 期望 admin/SYSTEM_ADMIN/ACTIVE/f
-- SELECT count(*) FROM price_adjust_settings;                                                            -- 期望 1
-- SELECT count(*) FROM sel_param_type;                                                                   -- 期望 3
-- SELECT count(*) FROM costing_bom_tree_config;                                                          -- 期望 3
-- SELECT 'node',count(*) FROM semantic_node UNION ALL SELECT 'col',count(*) FROM semantic_node_column
--   UNION ALL SELECT 'edge',count(*) FROM semantic_edge UNION ALL SELECT 'key',count(*) FROM semantic_edge_key
--   UNION ALL SELECT 'view',count(*) FROM semantic_tab_view UNION ALL SELECT 'vnode',count(*) FROM semantic_tab_view_node;
--                                                                    -- 期望 57 / 366 / 78 / 95 / 48 / 50
-- 登录: admin / Admin@2026
