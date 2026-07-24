--
-- ============================================================================
--  CPQ 系统 — 服务器部署初始化脚本（建表 + 系统种子 + 主数据 + admin）
-- ============================================================================
--
--  生成日期 : 2026-07-19
--  源库快照 : cpq_db @ PostgreSQL 16.13  (Flyway 基线 V343)
--  目标版本 : PostgreSQL 16（已在 PG 16.13 全新空库实测通过，0 错误）
--
--  用途：
--    替代「重放 345 条 Flyway 迁移」的部署方式，一次性建成终态 schema。
--    迁移重放不仅慢且易错，还会漏掉部分由 UI 创建、从未进入迁移文件的配置
--    （典型：costing_bom_tree_config —— 缺失会让核价树渲染直接抛 400）。
--
--  本脚本包含：
--    [1] 161 张表 / 10 视图 / 3 函数 / 8 序列 / 全部索引与约束
--    [2] 系统种子数据 19 张表（见文件末尾清单）
--    [3] 默认管理员 admin / Admin@2026
--    [4] Flyway 基线行 V343（保留将来跑增量迁移的能力）
--
--  本脚本【不】包含（按需求为真·空库）：
--    - 组件 / 模板骨架（component、template、template_component、costing_template…）
--    - 客户、报价单、核价单、产品等全部业务单据数据
--    - V6 基础资料（material_master、material_bom*、element_bom*、unit_price…）
--    - bnf_table_meta —— 应用启动时由 BnfTableMetaSyncer 扫库自动重建，预置反而
--      会把旧库的陈旧登记（44 行指向已不存在的对象）带进新环境
--    - datasource —— 现网该表含测试行 `DELETE FROM customer WHERE 1=1`，已排除
--
--  执行方式：
--    createdb -h <host> -U <user> cpq_db          # 先建空库
--    psql -h <host> -U <user> -d cpq_db -v ON_ERROR_STOP=1 -f cpq-init.sql
--
--  ⚠️ 必须在【全新空库】上执行。脚本不含 DROP，在已有对象的库上会因重名报错。
--
--  ⚠️ 上线后请立即修改 admin 密码。本脚本内置的是开发环境同款口令。
--
-- ============================================================================

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
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


--
-- Name: FUNCTION current_part_version(p_customer_product_no text, p_hf_part_no text); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.current_part_version(p_customer_product_no text, p_hf_part_no text) IS '料号版本管理: 给定 (customer_product_no, hf_part_no) 返回当前激活版本号; orphan/未注册返回 2000';


--
-- Name: get_bom_components(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.get_bom_components(p_material_no text) RETURNS TABLE(js integer, hf_part_no text, material_no text, component_no text)
    LANGUAGE sql STABLE
    AS $$
 WITH RECURSIVE bom_tree AS (
        -- 锚点：第一层数据
        SELECT 1 js,material_no dj,mbi.*
        FROM material_bom_item mbi
        WHERE mbi.material_no = p_material_no
          AND mbi.customer_no = '_GLOBAL_'

        UNION ALL

        -- 递归：逐层向下展开
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


--
-- Name: get_bom_components(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.get_bom_components(p_material_no text, p_customer_no text) RETURNS TABLE(component_no text)
    LANGUAGE sql STABLE
    AS $$
    WITH RECURSIVE bom_tree AS (
        -- 锚点：第一层数据
        SELECT mbi.*
        FROM material_bom_item mbi
        WHERE mbi.material_no = p_material_no
          AND mbi.customer_no = p_customer_no

        UNION ALL

        -- 递归：逐层向下展开
        SELECT child.*
        FROM material_bom_item child
        INNER JOIN bom_tree parent
            ON child.component_no = parent.material_no
           AND child.customer_no  = parent.customer_no
    )
    SELECT distinct bom_tree.component_no
    FROM bom_tree;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;


--
-- Name: annual_discount; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.annual_discount (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    biz_type character varying(20) NOT NULL,
    material_no character varying(20) NOT NULL,
    discount_strategy character varying(50) NOT NULL,
    discount_base numeric(18,6),
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
    CONSTRAINT chk_annual_discount_biz_type CHECK (((biz_type)::text = ANY (ARRAY[('INCOMING'::character varying)::text, ('ASSEMBLY'::character varying)::text, ('FINISHED'::character varying)::text])))
);


--
-- Name: TABLE annual_discount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.annual_discount IS 'V6 §23 年降系数表（业务类型+料号+策略+顺序唯一）';


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
-- Name: TABLE auxiliary_energy; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.auxiliary_energy IS 'V6 §17 辅助设备能耗（按工时/数量摊销至料号工序）';


--
-- Name: COLUMN auxiliary_energy.source; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.auxiliary_energy.source IS '版本来源: IMPORT=导入 / MANUAL=维护页手工编辑 (task-0712 C11)';


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
-- Name: TABLE basic_data_attribute; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.basic_data_attribute IS 'V63: 修复 UI 导入自动生成的 VAR_xxx_X variable_code,重置为目标物理表的真实列名';


--
-- Name: COLUMN basic_data_attribute.importance_level; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.basic_data_attribute.importance_level IS 'v5.1 §6.2 字段重要性：CRITICAL/IMPORTANT/NORMAL';


--
-- Name: COLUMN basic_data_attribute.affects_calculation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.basic_data_attribute.affects_calculation IS 'v5.1 §6.2 字段变更是否触发公式重算';


--
-- Name: COLUMN basic_data_attribute.is_required; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.basic_data_attribute.is_required IS 'V58: 该列是否必填，解析时为空抛 ValidationResult.error';


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
-- Name: TABLE basic_data_change_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.basic_data_change_log IS 'v5.0 §5.4 基础资料变更日志（v1 schema 完整，不写入数据）';


--
-- Name: COLUMN basic_data_change_log.change_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.basic_data_change_log.change_type IS 'DEPRECATED v5.1: 已迁移到 change_source；新行写入时此字段为 NULL';


--
-- Name: COLUMN basic_data_change_log.field_changes; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.basic_data_change_log.field_changes IS 'DEPRECATED v5.1: 历史聚合存储；新写入用 field_name/old_value/new_value 字段级行';


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
-- Name: TABLE basic_data_config; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.basic_data_config IS 'V64: 恢复用户在 UI 误删的 16 个生产 sheet + 5 个旧测试兼容 sheet 的 config 行;
     对没有 attribute 的 sheet 重新 seed 物理列名作 variable_code';


--
-- Name: COLUMN basic_data_config.target_table; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.basic_data_config.target_table IS 'V58: 该 sheet 行写入哪张物理表（mat_part / mat_bom / mat_fee 等）。NULL = sheet 不参与导入';


--
-- Name: COLUMN basic_data_config.target_discriminator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.basic_data_config.target_discriminator IS 'V58: 写入物理表时附加的固定列值（如 {"bom_type":"INCOMING"} 或 {"fee_type":"INCOMING_OTHER"}）';


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
-- Name: TABLE bnf_table_meta; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.bnf_table_meta IS 'BNF path 根节点元数据表；启动时自动同步 information_schema；PathPicker 第二 Tab 数据源';


--
-- Name: COLUMN bnf_table_meta.template_kind; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bnf_table_meta.template_kind IS 'QUOTATION/COSTING/ALL，运营按需调整';


--
-- Name: COLUMN bnf_table_meta.picker_visible; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.bnf_table_meta.picker_visible IS '是否在 PathPicker 里显示';


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
    CONSTRAINT chk_capacity_production_type CHECK (((production_type)::text = ANY (ARRAY[('UNIT'::character varying)::text, ('BATCH'::character varying)::text, ('BATCH_FIXED'::character varying)::text]))),
    CONSTRAINT chk_capacity_system_type CHECK (((system_type)::text = ANY ((ARRAY['QUOTE'::character varying, 'PRICING'::character varying, 'BOTH'::character varying])::text[])))
);


--
-- Name: TABLE capacity; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.capacity IS 'V6 §11 产能表（料号+工序+资源群组+计算版本唯一）';


--
-- Name: COLUMN capacity.currency; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.capacity.currency IS '货币（V225 补；组装加工费等场景用）';


--
-- Name: COLUMN capacity.seq_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.capacity.seq_no IS '项次（§14 组装加工费）';


--
-- Name: COLUMN capacity.version_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.capacity.version_no IS '版本号 默认2000';


--
-- Name: COLUMN capacity.source; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.capacity.source IS '版本来源: IMPORT=导入 / MANUAL=维护页手工编辑 (task-0712 C11)';


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
    CONSTRAINT chk_component_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DISABLED'::character varying)::text]))),
    CONSTRAINT chk_component_type CHECK (((component_type)::text = ANY ((ARRAY['NORMAL'::character varying, 'SUBTOTAL'::character varying, 'EXCEL'::character varying])::text[])))
);


--
-- Name: COLUMN component.data_driver_path; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.component.data_driver_path IS 'Y1.5 行驱动 BNF 路径(可选)。非空时组件展开为 driver 路径返回的 N 行,字段查询自动隐式 JOIN driver 行字段。';


--
-- Name: COLUMN component.tree_config; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.component.tree_config IS '树表配置(纯展示):idField/parentField/defaultExpanded;NULL=非树表';


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
-- Name: TABLE component_sql_view; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.component_sql_view IS '组件级用户自定义 SQL 视图，作为 BNF path 数据源的扩展层';


--
-- Name: COLUMN component_sql_view.sql_view_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.component_sql_view.sql_view_name IS 'BNF 引用名，例如 element_view；同 component 内唯一';


--
-- Name: COLUMN component_sql_view.sql_template; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.component_sql_view.sql_template IS '含命名占位符的 SQL 模板，如 :customerId';


--
-- Name: COLUMN component_sql_view.declared_columns; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.component_sql_view.declared_columns IS '保存时 dry-run 自动提取的列签名 [{name,dataType,nullable}]';


--
-- Name: COLUMN component_sql_view.required_variables; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.component_sql_view.required_variables IS '从 sql_template 中解析出的 :xxx 占位符列表';


--
-- Name: COLUMN component_sql_view.scope; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.component_sql_view.scope IS 'COMPONENT=本组件使用; GLOBAL=可跨组件 BNF $$ 引用';


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
-- Name: TABLE composite_process_def; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.composite_process_def IS '组合工艺字典(铆接/焊接/钎焊等)';


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
-- Name: TABLE config_category; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.config_category IS 'V203: 配置模板下的大类 (1:N, 用户自由扩展)';


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
-- Name: TABLE config_item; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.config_item IS 'V203: 配置模板大类下的明细项 (1:N, 含 default_value)';


--
-- Name: COLUMN config_item.default_value; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.config_item.default_value IS 'LIST_FORMULA 渲染时 per_item_rules 缺项/全分支不命中时的兜底';


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
-- Name: TABLE config_template; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.config_template IS 'V203: LIST_FORMULA 字段类型的配置模板主表 (Phase B)';


--
-- Name: COLUMN config_template.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.config_template.status IS 'DRAFT/PUBLISHED/ARCHIVED — 仅 PUBLISHED 可被组件字段引用';


--
-- Name: costing_bom_tree_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_bom_tree_config (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name text NOT NULL,
    sql_template text NOT NULL,
    is_active boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: costing_element_price; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_element_price (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    version_id uuid NOT NULL,
    element_code character varying(50) NOT NULL,
    costing_price numeric(18,4) NOT NULL,
    market_ref_price numeric(18,4),
    source_url character varying(500),
    source_name character varying(200),
    source_rule text,
    currency character varying(10) DEFAULT 'CNY'::character varying NOT NULL,
    unit character varying(20) DEFAULT 'KG'::character varying NOT NULL,
    discount_rate numeric(5,2),
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE costing_element_price; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.costing_element_price IS '元素价格明细（按版本组织）';


--
-- Name: costing_exchange_rate; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_exchange_rate (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    version_id uuid NOT NULL,
    from_currency character varying(10) NOT NULL,
    to_currency character varying(10) NOT NULL,
    costing_rate numeric(18,6) NOT NULL,
    market_rate numeric(18,6),
    rate_rule text,
    source_url character varying(500),
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE costing_exchange_rate; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.costing_exchange_rate IS '汇率明细（按版本组织）';


--
-- Name: costing_material_price; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_material_price (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    version_id uuid NOT NULL,
    material_no character varying(100) NOT NULL,
    brand_name character varying(100),
    spec character varying(200),
    dimension character varying(200),
    costing_price numeric(18,4) NOT NULL,
    market_ref_price numeric(18,4),
    source_url character varying(500),
    source_name character varying(200),
    source_rule text,
    currency character varying(10) DEFAULT 'CNY'::character varying NOT NULL,
    unit character varying(20) DEFAULT 'KG'::character varying NOT NULL,
    discount_rate numeric(5,2),
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE costing_material_price; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.costing_material_price IS '材料价格明细（按版本组织）';


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
    total_amount numeric(18,4),
    reviewed_by uuid,
    reviewed_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    costing_render jsonb,
    costing_total_amount numeric(18,4),
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
-- Name: costing_part_design_cost; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_part_design_cost (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    hf_part_no character varying(100) NOT NULL,
    design_drawing_no character varying(100),
    version_number character varying(50),
    design_proc_fee numeric(20,10),
    design_material_fee numeric(20,10),
    currency character varying(10) DEFAULT 'CNY'::character varying NOT NULL,
    unit character varying(20) DEFAULT 'KG'::character varying NOT NULL,
    loss_rate numeric(12,8),
    is_active boolean DEFAULT true NOT NULL,
    notes text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    part_version integer DEFAULT 2000 NOT NULL
);


--
-- Name: TABLE costing_part_design_cost; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.costing_part_design_cost IS '设计成本';


--
-- Name: COLUMN costing_part_design_cost.part_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.costing_part_design_cost.part_version IS '料号版本管理: 与 version_number 共存';


--
-- Name: costing_part_element_bom; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_part_element_bom (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    input_material_no character varying(100) NOT NULL,
    seq_no integer NOT NULL,
    element_code character varying(50) NOT NULL,
    composition_pct numeric(12,8) NOT NULL,
    loss_rate numeric(12,8),
    is_active boolean DEFAULT true NOT NULL,
    notes text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    part_version integer DEFAULT 2000 NOT NULL
);


--
-- Name: TABLE costing_part_element_bom; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.costing_part_element_bom IS '元素 BOM（组成含量 + 损耗）';


--
-- Name: COLUMN costing_part_element_bom.part_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.costing_part_element_bom.part_version IS '料号版本管理: 默认 2000';


--
-- Name: costing_part_material_bom; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_part_material_bom (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    hf_part_no character varying(100) NOT NULL,
    seq_no integer NOT NULL,
    input_material_no character varying(100),
    process_no character varying(50),
    process_name character varying(200),
    input_qty numeric(18,6),
    input_unit character varying(20),
    output_qty numeric(18,6),
    output_unit character varying(20),
    output_loss_rate numeric(12,8),
    fixed_loss_qty numeric(18,6),
    loss_rate numeric(12,8),
    is_active boolean DEFAULT true NOT NULL,
    notes text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    part_version integer DEFAULT 2000 NOT NULL
);


--
-- Name: TABLE costing_part_material_bom; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.costing_part_material_bom IS '材料 BOM（产出 + 损耗）';


--
-- Name: COLUMN costing_part_material_bom.part_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.costing_part_material_bom.part_version IS '料号版本管理: 默认 2000';


--
-- Name: costing_part_plating; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_part_plating (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    plating_no character varying(100) NOT NULL,
    version_number character varying(50) NOT NULL,
    seq_no integer NOT NULL,
    element_attr character varying(100),
    plating_area_cm2 numeric(18,6),
    layer_thickness_um numeric(18,6),
    requirement text,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    part_version integer DEFAULT 2000 NOT NULL
);


--
-- Name: TABLE costing_part_plating; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.costing_part_plating IS '成品电镀';


--
-- Name: COLUMN costing_part_plating.part_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.costing_part_plating.part_version IS '料号版本管理: 与 version_number(电镀方案版本号)共存';


--
-- Name: costing_part_plating_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_part_plating_fee (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    hf_part_no character varying(100) NOT NULL,
    plating_plan_code character varying(32),
    plan_version character varying(16),
    plating_process_fee numeric(20,10),
    plating_material_fee numeric(20,10),
    currency character varying(10),
    price_unit character varying(20),
    defect_rate numeric(12,8),
    is_active boolean DEFAULT true NOT NULL,
    notes text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    part_version integer DEFAULT 2000 NOT NULL
);


--
-- Name: TABLE costing_part_plating_fee; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.costing_part_plating_fee IS 'V125: 核价侧电镀费用 (按 partNo, 与报价侧 mat_plating_fee 独立)';


--
-- Name: COLUMN costing_part_plating_fee.part_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.costing_part_plating_fee.part_version IS '料号版本管理: 与 plan_version 共存';


--
-- Name: costing_part_process_cost; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_part_process_cost (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    hf_part_no character varying(100) NOT NULL,
    process_no character varying(50) NOT NULL,
    process_name character varying(200),
    cost_type character varying(30) NOT NULL,
    unit_price numeric(20,10) NOT NULL,
    currency character varying(10) DEFAULT 'CNY'::character varying NOT NULL,
    unit character varying(20) DEFAULT 'KG'::character varying NOT NULL,
    ref_calc_version character varying(50),
    is_active boolean DEFAULT true NOT NULL,
    notes text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    part_version integer DEFAULT 2000 NOT NULL,
    CONSTRAINT chk_process_cost_type CHECK (((cost_type)::text = ANY (ARRAY[('LABOR'::character varying)::text, ('DEPRECIATION'::character varying)::text, ('ENERGY_DEDICATED'::character varying)::text, ('ENERGY_SHARED'::character varying)::text, ('CONSUMABLE'::character varying)::text, ('MATERIAL_PROC'::character varying)::text, ('SEMI_FINISHED_PROC'::character varying)::text, ('POST_PROC'::character varying)::text])))
);


--
-- Name: TABLE costing_part_process_cost; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.costing_part_process_cost IS '工序级单价（8 种成本类型合一）';


--
-- Name: COLUMN costing_part_process_cost.unit_price; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.costing_part_process_cost.unit_price IS 'V93: 扩到 NUMERIC(20,10) - 支持包装工序按 PCS 计的能耗 1e-7 ~ 1e-9 量级单价';


--
-- Name: COLUMN costing_part_process_cost.part_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.costing_part_process_cost.part_version IS '料号版本管理: 默认 2000';


--
-- Name: costing_part_quality_check; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_part_quality_check (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    hf_part_no character varying(100) NOT NULL,
    stage character varying(20) NOT NULL,
    primary_seq_no integer,
    seq_no integer NOT NULL,
    requirement_code character varying(100),
    requirement_desc text,
    scrap_rate numeric(12,8),
    is_active boolean DEFAULT true NOT NULL,
    notes text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    part_version integer DEFAULT 2000 NOT NULL,
    CONSTRAINT chk_qc_stage CHECK (((stage)::text = ANY (ARRAY[('INCOMING'::character varying)::text, ('SEMI_FINISHED'::character varying)::text])))
);


--
-- Name: TABLE costing_part_quality_check; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.costing_part_quality_check IS '质量检验（进料 + 半品 合一）';


--
-- Name: COLUMN costing_part_quality_check.part_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.costing_part_quality_check.part_version IS '料号版本管理: 默认 2000';


--
-- Name: costing_part_tooling_cost; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_part_tooling_cost (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    hf_part_no character varying(100) NOT NULL,
    process_no character varying(50) NOT NULL,
    process_name character varying(200),
    seq_no integer NOT NULL,
    tooling_no character varying(100),
    tooling_unit_cost numeric(20,10) NOT NULL,
    process_count integer,
    cycle_count integer,
    unit_price numeric(20,10),
    currency character varying(10) DEFAULT 'CNY'::character varying NOT NULL,
    unit character varying(20) DEFAULT 'PCS'::character varying NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    notes text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    part_version integer DEFAULT 2000 NOT NULL
);


--
-- Name: TABLE costing_part_tooling_cost; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.costing_part_tooling_cost IS '模具工装成本（含工艺次数、可循环次数）';


--
-- Name: COLUMN costing_part_tooling_cost.part_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.costing_part_tooling_cost.part_version IS '料号版本管理: 默认 2000';


--
-- Name: costing_part_weight; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_part_weight (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    hf_part_no character varying(100) NOT NULL,
    weight_g_per_pcs numeric(20,10) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    notes text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    part_version integer DEFAULT 2000 NOT NULL
);


--
-- Name: TABLE costing_part_weight; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.costing_part_weight IS '料号重量（g/pcs）';


--
-- Name: COLUMN costing_part_weight.part_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.costing_part_weight.part_version IS '料号版本管理: 默认 2000';


--
-- Name: costing_price_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_price_version (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    version_kind character varying(20) NOT NULL,
    version_number character varying(50) NOT NULL,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    notes text,
    is_default boolean DEFAULT false NOT NULL,
    published_at timestamp(6) with time zone,
    published_by uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    CONSTRAINT chk_costing_version_kind CHECK (((version_kind)::text = ANY (ARRAY[('ELEMENT'::character varying)::text, ('MATERIAL'::character varying)::text, ('EXCHANGE'::character varying)::text]))),
    CONSTRAINT chk_costing_version_status CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('ARCHIVED'::character varying)::text])))
);


--
-- Name: TABLE costing_price_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.costing_price_version IS '核价基础数据版本主表（元素/材料/汇率三种共用）';


--
-- Name: costing_sheet; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_sheet (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    quotation_id uuid NOT NULL,
    costing_template_id uuid,
    import_batch_id uuid,
    rows jsonb DEFAULT '[]'::jsonb NOT NULL,
    total_cost numeric(20,4),
    status character varying(20) DEFAULT 'LIVE'::character varying NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: costing_summary; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_summary (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    summary_no character varying(50) NOT NULL,
    hf_part_no character varying(100) NOT NULL,
    element_version_id uuid,
    material_version_id uuid,
    exchange_version_id uuid,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    quote_currency character varying(10) DEFAULT 'USD'::character varying NOT NULL,
    notes text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    computed_at timestamp(6) with time zone,
    published_at timestamp(6) with time zone,
    published_by uuid,
    CONSTRAINT chk_summary_status CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('COMPUTED'::character varying)::text, ('PUBLISHED'::character varying)::text, ('ARCHIVED'::character varying)::text])))
);


--
-- Name: TABLE costing_summary; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.costing_summary IS '核价单主表（每料号每次核价一行，引用 3 个全局基础数据版本）';


--
-- Name: COLUMN costing_summary.element_version_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.costing_summary.element_version_id IS 'V105 起 deprecated. 历史核价单保留以审计当时所用版本; 新核价单为 NULL, 计算走 v_costing_element_price 当前默认 PUBLISHED 版本';


--
-- Name: COLUMN costing_summary.material_version_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.costing_summary.material_version_id IS 'V105 起 deprecated. 历史核价单保留以审计当时所用版本; 新核价单为 NULL, 计算走 v_costing_material_price 当前默认 PUBLISHED 版本';


--
-- Name: COLUMN costing_summary.exchange_version_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.costing_summary.exchange_version_id IS 'V105 起 deprecated. 历史核价单保留以审计当时所用版本; 新核价单为 NULL, 计算走 v_costing_exchange_rate 当前默认 PUBLISHED 版本';


--
-- Name: costing_summary_override; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_summary_override (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    summary_id uuid NOT NULL,
    target_kind character varying(20) NOT NULL,
    target_key character varying(200) NOT NULL,
    field_name character varying(80) NOT NULL,
    override_value numeric(18,6) NOT NULL,
    notes text,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_override_kind CHECK (((target_kind)::text = ANY (ARRAY[('ELEMENT'::character varying)::text, ('MATERIAL'::character varying)::text, ('EXCHANGE'::character varying)::text])))
);


--
-- Name: TABLE costing_summary_override; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.costing_summary_override IS '核价单内对基础数据的用户差量（不写回基础数据）';


--
-- Name: costing_summary_result; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.costing_summary_result (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    summary_id uuid NOT NULL,
    metric_code character varying(80) NOT NULL,
    metric_label character varying(200),
    value numeric(18,6),
    currency character varying(10) DEFAULT 'USD'::character varying NOT NULL,
    formula_used text,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE costing_summary_result; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.costing_summary_result IS '核价计算结果快照（汇总 sheet 各列的值）';


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
-- Name: TABLE customer_tax; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.customer_tax IS 'v5.1 路线 X 客户税率表';


--
-- Name: COLUMN customer_tax.tax_rate; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.customer_tax.tax_rate IS '客户当前生效税率。is_current=true 表示当前生效；历史行 is_current=false 仅供报价快照回溯。';


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
-- Name: TABLE ddl_operation_history; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.ddl_operation_history IS 'v5.1 §3.4 TECH-4: 运行时 ALTER 扩列历史记录 + 生成的 migration 文本（方案 B）';


--
-- Name: COLUMN ddl_operation_history.migration_content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.ddl_operation_history.migration_content IS '生成的 ALTER TABLE SQL 文本，供管理员复制到 git 作正式 migration';


--
-- Name: COLUMN ddl_operation_history.flyway_version_hint; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.ddl_operation_history.flyway_version_hint IS '推荐的 Flyway 版本号（如 V56），根据 flyway_schema_history MAX(version)+1 推算';


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
-- Name: TABLE ddl_operation_lock; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.ddl_operation_lock IS 'v5.1 §3.5 DDL 全局锁：Flyway 扩列运行时 ALTER 互斥';


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
-- Name: TABLE electricity_price; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.electricity_price IS 'V6 §21 电价表（地区+电压等级+峰平谷+生效日期+版本）';


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
-- Name: TABLE element; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.element IS '元素/组成项字典(符号=定价join键, 中文名展示, 元素编号留存)';


--
-- Name: COLUMN element.element_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.element.element_no IS '元素编号(业务主键, 不可改); element_code(符号)为可编辑属性,被引用即锁';


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
    CONSTRAINT chk_element_bom_status CHECK (((bom_status IS NULL) OR ((bom_status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('RELEASED'::character varying)::text, ('OBSOLETE'::character varying)::text])))),
    CONSTRAINT chk_element_bom_system_type CHECK (((system_type)::text = ANY (ARRAY[('QUOTE'::character varying)::text, ('PRICING'::character varying)::text, ('BOTH'::character varying)::text]))),
    CONSTRAINT chk_element_bom_type CHECK (((bom_type)::text = ANY (ARRAY[('MATERIAL'::character varying)::text, ('ASSEMBLY'::character varying)::text])))
);


--
-- Name: TABLE element_bom; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.element_bom IS 'V6 §5 元素BOM主表（characteristic 必填，用作元素维度键）';


--
-- Name: COLUMN element_bom.source; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.element_bom.source IS '版本来源: IMPORT=导入 / MANUAL=维护页手工编辑; 主从 BOM 落主表 (task-0712 C11)';


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
    CONSTRAINT chk_element_bom_item_system_type CHECK (((system_type)::text = ANY (ARRAY[('QUOTE'::character varying)::text, ('PRICING'::character varying)::text, ('BOTH'::character varying)::text])))
);


--
-- Name: TABLE element_bom_item; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.element_bom_item IS 'V6 §6 元素BOM子表（在物料BOM子表基础上加 content 含量字段）';


--
-- Name: COLUMN element_bom_item.hf_part_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.element_bom_item.hf_part_no IS 'V239: 宏丰成品料号 (Q04 Excel 第 1 列)。与 material_no(投入料号) 并存以支持按主件查询元素 BOM。';


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
    CONSTRAINT chk_edp_fetch_status CHECK (((fetch_status)::text = ANY (ARRAY[('SUCCESS'::character varying)::text, ('FAILED'::character varying)::text, ('MANUAL'::character varying)::text])))
);


--
-- Name: TABLE element_daily_price; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.element_daily_price IS 'v5.0 §5.3.3 元素每日价格（v1 仅写 fetch_status=MANUAL 行）';


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
-- Name: TABLE element_price; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.element_price IS 'v5.0 §5.2.4 客户元素价格配置（v1 仅 schema，source_id/fetch_rule_id nullable）';


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
-- Name: TABLE element_price_fetch_rule; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.element_price_fetch_rule IS 'v5.0 §5.3.2 元素价格抓取规则（v1 仅 schema）';


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
-- Name: TABLE element_price_source; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.element_price_source IS 'v5.0 §5.3.1 元素价格来源（v1 仅 schema）';


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
-- Name: TABLE equipment; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.equipment IS 'V6 §15 设备表（替代V2设备折旧成本表，年折旧+工时折旧）';


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
-- Name: TABLE exchange_rate; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.exchange_rate IS 'v5.1 路线 X 客户级汇率表';


--
-- Name: COLUMN exchange_rate.customer_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.exchange_rate.customer_id IS 'NULL 表示全局汇率；非 NULL 表示该客户的协议汇率。EXCHANGE 公式优先匹配客户级。';


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
-- Name: TABLE exchange_rate_v6; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.exchange_rate_v6 IS 'V6 §10 汇率表（设计文档 exchange_rate；避让 V44 exchange_rate 加 _v6 后缀）';


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
-- Name: TABLE fee_config; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.fee_config IS 'V6 §7 商务/物流类费用配置（利润率/税率/运费/清关/保险/银行/其他）';


--
-- Name: COLUMN fee_config.dim_input_material_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.fee_config.dim_input_material_no IS 'v1.2 来料维度（v12_incoming_other/v12_incoming_fixed_fee 组件用），import PR backfill';


--
-- Name: COLUMN fee_config.dim_sub_seq_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.fee_config.dim_sub_seq_no IS 'v1.2 来料子项序号维度，import PR backfill';


--
-- Name: COLUMN fee_config.dim_element_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.fee_config.dim_element_name IS 'v1.2 费用元素名称维度（v12_finished_other/v12_finished_fixed_fee 组件用），import PR backfill';


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
-- Name: TABLE global_variable_change_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.global_variable_change_log IS 'V106: 全局变量行级变更日志. 给"全局变量配置"页变更历史面板查询用';


--
-- Name: COLUMN global_variable_change_log.key_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.global_variable_change_log.key_id IS 'key 列拼接字符串. 单键: "element_code=Cu"; 复合键: "from_currency=CNY;to_currency=USD"';


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
-- Name: TABLE global_variable_definition; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.global_variable_definition IS 'V104: 全局变量注册表; 公式引擎按 code 查 def, 编译 token 为 BNF path 调 resolver';


--
-- Name: COLUMN global_variable_definition.source_view; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.global_variable_definition.source_view IS 'V213: 物理视图/表名. COSTING_VIEW 类型必填, KV_TABLE 类型可空 (走 global_variable_value 单表).';


--
-- Name: COLUMN global_variable_definition.key_columns; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.global_variable_definition.key_columns IS 'JSON 数组. 单键: ["element_code"]; 复合键: ["from_currency","to_currency"]';


--
-- Name: COLUMN global_variable_definition.label_template; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.global_variable_definition.label_template IS 'UI 候选 key 列表的显示文案. 简单写列名即按列拼接; 留空时默认按 key_columns 拼接';


--
-- Name: COLUMN global_variable_definition.value_source_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.global_variable_definition.value_source_type IS 'V190: KV_TABLE = 查 global_variable_value 单表 (轻量配置); COSTING_VIEW = 查 source_view 视图 (核价 3 张)';


--
-- Name: COLUMN global_variable_definition.visibility; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.global_variable_definition.visibility IS 'V190: PUBLIC = 全局变量页可见可编辑; COSTING_INTERNAL = Picker 可选但 UI 列表过滤, 「维护数据」按钮禁用';


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
-- Name: TABLE global_variable_value; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.global_variable_value IS 'V190 全局变量值统一存储 — 替代 process_default_* 等独立物理表; 加变量纯 UI 操作, Java 零改动. 核价 3 张表仍走 source_view, 不存这里';


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
-- Name: COLUMN import_record.metadata; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.import_record.metadata IS 'v1: UI-1/UI-2 决策的 resolutions[] JSON';


--
-- Name: COLUMN import_record.system_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.import_record.system_type IS 'V6 导入区分：QUOTE 报价基础数据 / PRICING 核价基础数据 / OTHER 老链路';


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
-- Name: TABLE import_session; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.import_session IS 'V6 Excel 导入会话主表 (staging 隔离 + 24h TTL)';


--
-- Name: COLUMN import_session.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.import_session.status IS 'PENDING | COMMITTED | CANCELLED | EXPIRED';


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
-- Name: TABLE import_session_decision; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.import_session_decision IS 'V6 导入会话决策表 (BUMP/NO_BUMP/USE_EXCEL/...)';


--
-- Name: COLUMN import_session_decision.decision_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.import_session_decision.decision_type IS 'PART_VERSION | CUSTOMER_CONFLICT | ORPHAN';


--
-- Name: COLUMN import_session_decision.decision_value; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.import_session_decision.decision_value IS 'JSONB: {action, currentVersion?, suggestedVersion?, appliedVersion?, ...}';


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
-- Name: TABLE labor_rate; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.labor_rate IS 'V6 §22 工时单价表（版本+料号+工序+工种唯一）';


--
-- Name: COLUMN labor_rate.source; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.labor_rate.source IS '版本来源: IMPORT=导入 / MANUAL=维护页手工编辑 (task-0712 C11)';


--
-- Name: mat_bom; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_bom (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    bom_type character varying(16) NOT NULL,
    hf_part_no character varying(64) NOT NULL,
    seq_no integer NOT NULL,
    input_material_no character varying(64),
    input_material_name character varying(128),
    loss_rate numeric(12,8),
    gross_qty numeric(20,10),
    net_qty numeric(20,10),
    gross_unit character varying(16),
    net_unit character varying(16),
    output_material_type character varying(64),
    defect_rate numeric(12,8),
    element_name character varying(64),
    composition_pct numeric(12,8),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    part_version integer DEFAULT 2000 NOT NULL,
    child_part_no character varying(64),
    CONSTRAINT chk_mat_bom_bom_type CHECK (((bom_type)::text = ANY (ARRAY[('ELEMENT'::character varying)::text, ('INCOMING'::character varying)::text, ('OUTPUT'::character varying)::text, ('ASSEMBLY'::character varying)::text])))
);


--
-- Name: TABLE mat_bom; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mat_bom IS 'v5.0 §5.1.3 统一 BOM 表（合并来料 + 元素）';


--
-- Name: COLUMN mat_bom.part_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_bom.part_version IS '料号版本管理: 关联 mat_customer_part_mapping.current_version, 默认 2000';


--
-- Name: COLUMN mat_bom.child_part_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_bom.child_part_no IS 'ASSEMBLY 行:子配件 hf_part_no;其他 bom_type 为 NULL';


--
-- Name: CONSTRAINT chk_mat_bom_bom_type ON mat_bom; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON CONSTRAINT chk_mat_bom_bom_type ON public.mat_bom IS 'ASSEMBLY: 组合产品父→子,child_part_no 表达子料号';


--
-- Name: mat_bom_staging; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_bom_staging (
    id uuid DEFAULT gen_random_uuid(),
    bom_type character varying(16) NOT NULL,
    hf_part_no character varying(64) NOT NULL,
    seq_no integer NOT NULL,
    input_material_no character varying(64),
    input_material_name character varying(128),
    loss_rate numeric(12,8),
    gross_qty numeric(20,10),
    net_qty numeric(20,10),
    gross_unit character varying(16),
    net_unit character varying(16),
    output_material_type character varying(64),
    defect_rate numeric(12,8),
    element_name character varying(64),
    composition_pct numeric(12,8),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    part_version integer DEFAULT 2000 NOT NULL,
    staging_id uuid DEFAULT gen_random_uuid() NOT NULL,
    import_session_id uuid NOT NULL
);


--
-- Name: TABLE mat_bom_staging; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mat_bom_staging IS 'V6 导入 staging 表 (mat_bom)，commit 时合并到正式表，cancel 时 CASCADE 清空';


--
-- Name: mat_composite_process; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_composite_process (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    hf_part_no character varying(64) NOT NULL,
    def_code character varying(64) NOT NULL,
    seq_no integer NOT NULL,
    participating_parts jsonb NOT NULL,
    param_values jsonb DEFAULT '{}'::jsonb NOT NULL,
    part_version integer DEFAULT 2000 NOT NULL,
    is_current boolean DEFAULT true NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid
);


--
-- Name: TABLE mat_composite_process; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mat_composite_process IS '组合工艺实例(挂在父料号上)';


--
-- Name: COLUMN mat_composite_process.hf_part_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_composite_process.hf_part_no IS '组合产品父料号(原名 parent_hf_part_no,V166 重命名以对齐 ImplicitJoinRewriter 列名约定; FK to mat_part.part_no)';


--
-- Name: mat_customer_part_mapping; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_customer_part_mapping (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_id uuid NOT NULL,
    customer_part_name character varying(128),
    customer_product_no character varying(64),
    customer_drawing_no character varying(64),
    hf_part_no character varying(64) NOT NULL,
    payment_method character varying(64),
    base_currency character varying(8),
    quote_currency character varying(8),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    current_version integer DEFAULT 2000 NOT NULL
);


--
-- Name: TABLE mat_customer_part_mapping; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mat_customer_part_mapping IS 'v5.0 §5.1.2 跨客户料号对照';


--
-- Name: COLUMN mat_customer_part_mapping.customer_product_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_customer_part_mapping.customer_product_no IS 'V176: 客户对此 hf 的本地编号,可空;不再是业务唯一键 (业务唯一键 = customer_id+hf_part_no)';


--
-- Name: COLUMN mat_customer_part_mapping.current_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_customer_part_mapping.current_version IS '料号版本管理: (customer_product_no, hf_part_no) 维度的当前激活版本, 默认 2000, 每次升版 +1';


--
-- Name: mat_customer_part_mapping_staging; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_customer_part_mapping_staging (
    id uuid DEFAULT gen_random_uuid(),
    customer_id uuid NOT NULL,
    customer_part_name character varying(128),
    customer_product_no character varying(64),
    customer_drawing_no character varying(64),
    hf_part_no character varying(64) NOT NULL,
    payment_method character varying(64),
    base_currency character varying(8),
    quote_currency character varying(8),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    current_version integer DEFAULT 2000 NOT NULL,
    staging_id uuid DEFAULT gen_random_uuid() NOT NULL,
    import_session_id uuid NOT NULL
);


--
-- Name: TABLE mat_customer_part_mapping_staging; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mat_customer_part_mapping_staging IS 'V6 导入 staging 表 (mat_customer_part_mapping)，commit 时合并到正式表，cancel 时 CASCADE 清空';


--
-- Name: mat_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_fee (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_id uuid NOT NULL,
    hf_part_no character varying(64) NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    is_current boolean DEFAULT true NOT NULL,
    fee_type character varying(32) NOT NULL,
    seq_no integer NOT NULL,
    fee_value numeric(20,10),
    fee_ratio numeric(12,8),
    currency character varying(8),
    price_unit character varying(16),
    dim_input_material_no character varying(64),
    dim_input_material_name character varying(128),
    dim_element_name character varying(128),
    dim_assembly_process character varying(64),
    dim_sub_seq_no integer,
    price_floating boolean,
    settlement_rise_ratio numeric(12,8),
    fixed_rise_value numeric(20,10),
    rise_currency character varying(8),
    rise_unit character varying(16),
    reject_rate numeric(12,8),
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    imported_by uuid,
    import_record_id uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    part_version integer DEFAULT 2000 NOT NULL,
    customer_product_no character varying(64),
    CONSTRAINT chk_mat_fee_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DELETED'::character varying)::text]))),
    CONSTRAINT chk_mat_fee_type CHECK (((fee_type)::text = ANY (ARRAY[('INCOMING_FIXED'::character varying)::text, ('INCOMING_OTHER'::character varying)::text, ('FINISHED_FIXED'::character varying)::text, ('FINISHED_OTHER'::character varying)::text, ('ASSEMBLY_PROCESS'::character varying)::text, ('INCOMING_ANNUAL_DOWN'::character varying)::text, ('ASSEMBLY_ANNUAL_DOWN'::character varying)::text, ('ANNUAL_REDUCTION_FACTOR'::character varying)::text, ('MATERIAL_RECYCLE'::character varying)::text, ('ELEMENT_RECYCLE'::character varying)::text, ('COMPONENT_OTHER'::character varying)::text])))
);


--
-- Name: TABLE mat_fee; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mat_fee IS 'v5.0 §5.2.2 统一费用表（含 customer_id + version）';


--
-- Name: COLUMN mat_fee.part_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_fee.part_version IS '料号版本管理: 与旧 version 列共存';


--
-- Name: COLUMN mat_fee.customer_product_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_fee.customer_product_no IS '料号版本管理: 与 hf_part_no 组合成版本主键';


--
-- Name: mat_fee_staging; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_fee_staging (
    id uuid DEFAULT gen_random_uuid(),
    customer_id uuid NOT NULL,
    hf_part_no character varying(64) NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    is_current boolean DEFAULT true NOT NULL,
    fee_type character varying(32) NOT NULL,
    seq_no integer NOT NULL,
    fee_value numeric(20,10),
    fee_ratio numeric(12,8),
    currency character varying(8),
    price_unit character varying(16),
    dim_input_material_no character varying(64),
    dim_input_material_name character varying(128),
    dim_element_name character varying(128),
    dim_assembly_process character varying(64),
    dim_sub_seq_no integer,
    price_floating boolean,
    settlement_rise_ratio numeric(12,8),
    fixed_rise_value numeric(20,10),
    rise_currency character varying(8),
    rise_unit character varying(16),
    reject_rate numeric(12,8),
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    imported_by uuid,
    import_record_id uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    part_version integer DEFAULT 2000 NOT NULL,
    customer_product_no character varying(64),
    staging_id uuid DEFAULT gen_random_uuid() NOT NULL,
    import_session_id uuid NOT NULL
);


--
-- Name: TABLE mat_fee_staging; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mat_fee_staging IS 'V6 导入 staging 表 (mat_fee)，commit 时合并到正式表，cancel 时 CASCADE 清空';


--
-- Name: mat_part; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_part (
    part_no character varying(64) NOT NULL,
    part_name character varying(128),
    specification character varying(128),
    size_info character varying(128),
    category_id uuid,
    unit_weight numeric(20,10),
    weight_unit character varying(16),
    status_code character varying(4) DEFAULT 'Y'::character varying NOT NULL,
    is_pending_category boolean DEFAULT false NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    material_recipe_id uuid,
    product_type character varying(16) DEFAULT 'SIMPLE'::character varying NOT NULL,
    config_fingerprint character varying(64),
    e2e_col_1779193319183 character varying(64) DEFAULT ''::character varying NOT NULL,
    length numeric(18,4),
    width numeric(18,4),
    height numeric(18,4),
    e2e_col_1780391883849 character varying(64) DEFAULT ''::character varying NOT NULL,
    CONSTRAINT chk_mat_part_product_type CHECK (((product_type)::text = ANY (ARRAY[('SIMPLE'::character varying)::text, ('COMPOSITE'::character varying)::text]))),
    CONSTRAINT chk_mat_part_status_code CHECK (((status_code)::text = ANY (ARRAY[('Y'::character varying)::text, ('N'::character varying)::text])))
);


--
-- Name: TABLE mat_part; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mat_part IS 'v5.0 §5.1.1 生产料号主档（含单重）';


--
-- Name: COLUMN mat_part.material_recipe_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_part.material_recipe_id IS '材质配方 FK(选配生成的料号会填;旧料号为 NULL)';


--
-- Name: COLUMN mat_part.product_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_part.product_type IS 'SIMPLE 独立 / COMPOSITE 组合(父料号)';


--
-- Name: COLUMN mat_part.config_fingerprint; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_part.config_fingerprint IS '配置指纹(F2):仅选配料号写;sha256 hex 64 字符';


--
-- Name: COLUMN mat_part.e2e_col_1779193319183; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_part.e2e_col_1779193319183 IS 'Runtime extension by 系统管理员 at 2026-05-19T20:22:02.679812100+08:00';


--
-- Name: COLUMN mat_part.length; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_part.length IS 'V204: 长 (mm). 料号物理属性, 用于公式计算 (体积/面积等).';


--
-- Name: COLUMN mat_part.width; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_part.width IS 'V204: 宽 (mm). 同 length.';


--
-- Name: COLUMN mat_part.height; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_part.height IS 'V204: 高 (mm). 同 length.';


--
-- Name: COLUMN mat_part.e2e_col_1780391883849; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_part.e2e_col_1780391883849 IS 'Runtime extension by 系统管理员 at 2026-06-02T17:18:10.280806137+08:00';


--
-- Name: mat_part_model; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_part_model (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    part_no character varying(64) NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    label character varying(255),
    is_current boolean DEFAULT true NOT NULL,
    glb_url text NOT NULL,
    thumbnail_url text,
    mesh_count integer,
    vertices integer,
    size_kb integer,
    metadata jsonb DEFAULT '{}'::jsonb,
    uploaded_by uuid,
    uploaded_at timestamp(6) with time zone DEFAULT now() NOT NULL
);


--
-- Name: mat_part_source_file; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_part_source_file (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    part_no character varying(64) NOT NULL,
    model_id uuid,
    file_role character varying(32) NOT NULL,
    file_url text NOT NULL,
    file_size_bytes bigint,
    md5_hash character varying(64),
    uploaded_by uuid,
    uploaded_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb,
    CONSTRAINT chk_mpsf_role CHECK (((file_role)::text = ANY (ARRAY[('UGNX_SOURCE'::character varying)::text, ('STP_NEUTRAL'::character varying)::text, ('GLB_RENDER'::character varying)::text, ('GLB_DRACO'::character varying)::text, ('THUMBNAIL'::character varying)::text, ('LOD_LOW'::character varying)::text, ('LOD_HIGH'::character varying)::text, ('OTHER'::character varying)::text])))
);


--
-- Name: mat_part_staging; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_part_staging (
    part_no character varying(64) NOT NULL,
    part_name character varying(128),
    specification character varying(128),
    size_info character varying(128),
    category_id uuid,
    unit_weight numeric(20,10),
    weight_unit character varying(16),
    status_code character varying(4) DEFAULT 'Y'::character varying NOT NULL,
    is_pending_category boolean DEFAULT false NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    staging_id uuid DEFAULT gen_random_uuid() NOT NULL,
    import_session_id uuid NOT NULL
);


--
-- Name: TABLE mat_part_staging; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mat_part_staging IS 'V6 导入 staging 表 (mat_part)，commit 时合并到正式表，cancel 时 CASCADE 清空';


--
-- Name: mat_part_version_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_part_version_log (
    customer_product_no character varying(64),
    hf_part_no character varying(64) NOT NULL,
    version integer NOT NULL,
    content_hash character(32),
    diff_summary jsonb,
    source_excel text,
    source_import_id uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    customer_id uuid NOT NULL
);


--
-- Name: TABLE mat_part_version_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mat_part_version_log IS '料号版本历史登记 — 每个 (customer_product_no, hf_part_no, version) 一条记录, 含指纹与变更摘要';


--
-- Name: COLUMN mat_part_version_log.customer_product_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_part_version_log.customer_product_no IS 'V177: 可空; 仅作历史 cpn 记录, 不参与 PK';


--
-- Name: COLUMN mat_part_version_log.content_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_part_version_log.content_hash IS 'md5(全表行集合 JSON 排序后), 32 字符 hex. S2 阶段上线后补算, S1 阶段留 NULL';


--
-- Name: COLUMN mat_part_version_log.diff_summary; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_part_version_log.diff_summary IS 'JSONB, 形如 {table_name: {added: N, changed: N, deleted: N}}, 由 PartVersionService.computeDiff 生成';


--
-- Name: COLUMN mat_part_version_log.customer_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_part_version_log.customer_id IS 'V177: 客户级版本日志的客户维度 (新 PK 成员); FK → customer.id ON DELETE CASCADE';


--
-- Name: mat_plating_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_plating_fee (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_id uuid NOT NULL,
    hf_part_no character varying(64) NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    is_current boolean DEFAULT true NOT NULL,
    plating_plan_code character varying(32),
    plan_version character varying(16),
    plating_process_fee numeric(20,10),
    plating_material_fee numeric(20,10),
    currency character varying(8),
    price_unit character varying(16),
    defect_rate numeric(12,8),
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    imported_by uuid,
    import_record_id uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    part_version integer DEFAULT 2000 NOT NULL,
    customer_product_no character varying(64),
    CONSTRAINT chk_mat_plating_fee_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DELETED'::character varying)::text])))
);


--
-- Name: TABLE mat_plating_fee; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mat_plating_fee IS 'V125: 报价侧电镀费用 (CUSTOMER + version, 与核价侧 costing_part_plating_fee 独立)';


--
-- Name: COLUMN mat_plating_fee.part_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_plating_fee.part_version IS '料号版本管理: 与旧 version 列共存';


--
-- Name: COLUMN mat_plating_fee.customer_product_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_plating_fee.customer_product_no IS '料号版本管理: 与 hf_part_no 组合成版本主键';


--
-- Name: mat_plating_fee_staging; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_plating_fee_staging (
    id uuid DEFAULT gen_random_uuid(),
    customer_id uuid NOT NULL,
    hf_part_no character varying(64) NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    is_current boolean DEFAULT true NOT NULL,
    plating_plan_code character varying(32),
    plan_version character varying(16),
    plating_process_fee numeric(20,10),
    plating_material_fee numeric(20,10),
    currency character varying(8),
    price_unit character varying(16),
    defect_rate numeric(12,8),
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    imported_by uuid,
    import_record_id uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    part_version integer DEFAULT 2000 NOT NULL,
    customer_product_no character varying(64),
    staging_id uuid DEFAULT gen_random_uuid() NOT NULL,
    import_session_id uuid NOT NULL
);


--
-- Name: TABLE mat_plating_fee_staging; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mat_plating_fee_staging IS 'V6 导入 staging 表 (mat_plating_fee)，commit 时合并到正式表，cancel 时 CASCADE 清空';


--
-- Name: mat_plating_plan; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_plating_plan (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    plan_code character varying(32) NOT NULL,
    version character varying(16) NOT NULL,
    seq_no integer NOT NULL,
    plating_element character varying(64),
    plating_area numeric(20,10),
    coating_thickness numeric(20,10),
    plating_requirement character varying(256),
    imported_by uuid,
    import_record_id uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    part_version integer DEFAULT 2000 NOT NULL
);


--
-- Name: TABLE mat_plating_plan; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mat_plating_plan IS 'V125: 报价侧电镀方案库 (与核价侧 costing_part_plating 独立)';


--
-- Name: COLUMN mat_plating_plan.part_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_plating_plan.part_version IS '料号版本管理: 与旧 version 列(电镀方案版本号)共存';


--
-- Name: mat_plating_plan_staging; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_plating_plan_staging (
    id uuid DEFAULT gen_random_uuid(),
    plan_code character varying(32) NOT NULL,
    version character varying(16) NOT NULL,
    seq_no integer NOT NULL,
    plating_element character varying(64),
    plating_area numeric(20,10),
    coating_thickness numeric(20,10),
    plating_requirement character varying(256),
    imported_by uuid,
    import_record_id uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    part_version integer DEFAULT 2000 NOT NULL,
    staging_id uuid DEFAULT gen_random_uuid() NOT NULL,
    import_session_id uuid NOT NULL
);


--
-- Name: TABLE mat_plating_plan_staging; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mat_plating_plan_staging IS 'V6 导入 staging 表 (mat_plating_plan)，commit 时合并到正式表，cancel 时 CASCADE 清空';


--
-- Name: mat_process; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_process (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    customer_id uuid NOT NULL,
    hf_part_no character varying(64) NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    is_current boolean DEFAULT true NOT NULL,
    seq_no integer NOT NULL,
    process_code character varying(32),
    assembly_process character varying(64),
    sub_seq_no integer,
    component_part_no character varying(64),
    component_name character varying(128),
    supplier_code character varying(32),
    supplier_name character varying(128),
    quantity numeric(20,10),
    quantity_unit character varying(16),
    unit_price numeric(20,10),
    freight numeric(20,10),
    currency character varying(8),
    price_unit character varying(16),
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    imported_by uuid,
    import_record_id uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    part_version integer DEFAULT 2000 NOT NULL,
    customer_product_no character varying(64),
    quotation_line_item_id uuid,
    CONSTRAINT chk_mat_process_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('DELETED'::character varying)::text])))
);


--
-- Name: TABLE mat_process; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mat_process IS 'v5.0 §5.2.1 工艺基础（含 customer_id，BIZ-2 跨客户差异化）';


--
-- Name: COLUMN mat_process.part_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_process.part_version IS '料号版本管理: 与旧 version 列共存 — version=VersionedWriter批次版, part_version=料号版本';


--
-- Name: COLUMN mat_process.customer_product_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_process.customer_product_no IS '料号版本管理: 与 hf_part_no 组合成版本主键. 回填策略: 取 mat_customer_part_mapping 最早一行的 cpn';


--
-- Name: COLUMN mat_process.quotation_line_item_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.mat_process.quotation_line_item_id IS '报价单上下文隔离 ID. NULL=主数据(全局工序，来自数据导入或 existing 无覆盖路径), 非NULL=该 line item 的覆盖工序（同 customer+hf_part_no 可并存多套不互扰）.';


--
-- Name: mat_process_staging; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mat_process_staging (
    id uuid DEFAULT gen_random_uuid(),
    customer_id uuid NOT NULL,
    hf_part_no character varying(64) NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    is_current boolean DEFAULT true NOT NULL,
    seq_no integer NOT NULL,
    process_code character varying(32),
    assembly_process character varying(64),
    sub_seq_no integer,
    component_part_no character varying(64),
    component_name character varying(128),
    supplier_code character varying(32),
    supplier_name character varying(128),
    quantity numeric(20,10),
    quantity_unit character varying(16),
    unit_price numeric(20,10),
    freight numeric(20,10),
    currency character varying(8),
    price_unit character varying(16),
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    imported_by uuid,
    import_record_id uuid,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    part_version integer DEFAULT 2000 NOT NULL,
    customer_product_no character varying(64),
    staging_id uuid DEFAULT gen_random_uuid() NOT NULL,
    import_session_id uuid NOT NULL
);


--
-- Name: TABLE mat_process_staging; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mat_process_staging IS 'V6 导入 staging 表 (mat_process)，commit 时合并到正式表，cancel 时 CASCADE 清空';


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
    CONSTRAINT chk_material_bom_status CHECK (((bom_status IS NULL) OR ((bom_status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('RELEASED'::character varying)::text, ('OBSOLETE'::character varying)::text])))),
    CONSTRAINT chk_material_bom_system_type CHECK (((system_type)::text = ANY (ARRAY[('QUOTE'::character varying)::text, ('PRICING'::character varying)::text, ('BOTH'::character varying)::text]))),
    CONSTRAINT chk_material_bom_type CHECK (((bom_type)::text = ANY (ARRAY[('MATERIAL'::character varying)::text, ('ASSEMBLY'::character varying)::text])))
);


--
-- Name: TABLE material_bom; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.material_bom IS 'V6 §3 物料BOM主表（system_type+customer_no+material_no+bom_version+characteristic 唯一）';


--
-- Name: COLUMN material_bom.source; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.material_bom.source IS '版本来源: IMPORT=导入 / MANUAL=维护页手工编辑; 主从 BOM 落主表 (task-0712 C11)';


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
    CONSTRAINT chk_material_bom_item_system_type CHECK (((system_type)::text = ANY (ARRAY[('QUOTE'::character varying)::text, ('PRICING'::character varying)::text, ('BOTH'::character varying)::text])))
);


--
-- Name: TABLE material_bom_item; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.material_bom_item IS 'V6 §4 物料BOM子表（按 system+customer+主件+特性+项次+组件唯一）';


--
-- Name: COLUMN material_bom_item.bom_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.material_bom_item.bom_version IS 'V293 子表版本号，对齐 material_bom.bom_version，多版本保留 + is_current 标当前';


--
-- Name: COLUMN material_bom_item.rough_weight; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.material_bom_item.rough_weight IS '材料毛重（物料BOM Sheet，V300 前借住 composition_qty）';


--
-- Name: COLUMN material_bom_item.net_weight; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.material_bom_item.net_weight IS '材料净重（物料BOM Sheet，V300 前借住 base_qty）';


--
-- Name: COLUMN material_bom_item.weight_unit; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.material_bom_item.weight_unit IS '重量单位（物料BOM Sheet，V300 前借住 issue_unit）';


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
    production_no character varying(20)
);


--
-- Name: TABLE material_customer_map; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.material_customer_map IS 'V6 §2 料号-客户映射（业务键 material_no+customer_no+customer_product_no）';


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
    production_no character varying(32)
);


--
-- Name: TABLE material_master; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.material_master IS 'V6 §1 料号主表（业务键 material_no UNIQUE）';


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
-- Name: TABLE material_recipe; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.material_recipe IS '材质配方字典(选配抽屉 P2 材质库)';


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
-- Name: TABLE material_recipe_element; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.material_recipe_element IS '材质元素含量(每材质 2-3 元素)';


--
-- Name: COLUMN material_recipe_element.element_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.material_recipe_element.element_no IS '权威元素链(→element.element_no); element_code/name 为随符号锁恒一致的快照';


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
-- Name: TABLE material_version_mgmt; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.material_version_mgmt IS 'V6 §13 料号生效版本管理（核价版本绑定元素/材料/汇率版本）';


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
    CONSTRAINT chk_notification_type CHECK (((type)::text = ANY (ARRAY[('APPROVAL_SUBMITTED'::character varying)::text, ('APPROVAL_APPROVED'::character varying)::text, ('APPROVAL_REJECTED'::character varying)::text, ('APPROVAL_REMINDER'::character varying)::text, ('PASSWORD_RESET'::character varying)::text, ('ROLE_CHANGED'::character varying)::text, ('SYSTEM'::character varying)::text])))
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
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL
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
-- Name: TABLE packaging_consumable; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.packaging_consumable IS 'V6 §20 包装耗材（成品料号+项次+包装耗材唯一）';


--
-- Name: part_no_sequence; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.part_no_sequence (
    prefix character varying(32) NOT NULL,
    next_val bigint DEFAULT 1 NOT NULL
);


--
-- Name: TABLE part_no_sequence; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.part_no_sequence IS '选配料号自增计数器';


--
-- Name: COLUMN part_no_sequence.prefix; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.part_no_sequence.prefix IS '前缀(含尾连字符,如 CFG-AgCu-)';


--
-- Name: COLUMN part_no_sequence.next_val; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.part_no_sequence.next_val IS '下一个流水号;Provider 取后 +1';


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
-- Name: TABLE plating_fee; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.plating_fee IS 'v5.0 §5.2.3 电镀费用 — [V125 deprecated] 已拆分到 mat_plating_fee (报价侧) + costing_part_plating_fee (核价侧). 留作只读历史数据, 新 import 不写此表.';


--
-- Name: plating_plan; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plating_plan (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    plan_code character varying(32) NOT NULL,
    version character varying(16) NOT NULL,
    seq_no integer NOT NULL,
    plating_element character varying(64),
    plating_area numeric(18,4),
    coating_thickness numeric(10,4),
    plating_requirement character varying(256),
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid
);


--
-- Name: TABLE plating_plan; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.plating_plan IS 'v5.0 §5.1.4 电镀方案 — [V125 deprecated] 已拆分到 mat_plating_plan (报价侧) + costing_part_plating (核价侧). 留作只读历史数据.';


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
    CONSTRAINT chk_plating_scheme_system_type CHECK (((system_type)::text = ANY ((ARRAY['QUOTE'::character varying, 'PRICING'::character varying, 'BOTH'::character varying])::text[])))
);


--
-- Name: TABLE plating_scheme; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.plating_scheme IS 'V6 §9 电镀方案表（按方案号+版本+项次唯一）';


--
-- Name: COLUMN plating_scheme.hf_part_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.plating_scheme.hf_part_no IS 'v1.2 料号绑定维度（v12_plating_scheme 组件用），import PR backfill；数据未 backfill 前电镀方案 Tab 显示空（预期行为，同 V141/V142 时情况）';


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
-- Name: TABLE process_master; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.process_master IS 'V6 §8 工序主数据（business key process_no UNIQUE）';


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
-- Name: TABLE product_import_lock; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.product_import_lock IS 'v5.1 §3.4 产品级悲观锁：自适应粒度（料号/客户级）';


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
-- Name: TABLE production_consumable; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.production_consumable IS 'V6 §19 生产耗材（料号+工序+资源群组+项次+耗材料号）';


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
-- Name: TABLE production_energy; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.production_energy IS 'V6 §16 生产设备能耗（料号+工序+设备+计算版本）';


--
-- Name: COLUMN production_energy.unit_price; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.production_energy.unit_price IS '单价(按 price_type 区分: ENERGY 能耗/DEPRECIATION 折旧); scale 12 保留 <1e-6 极小能耗';


--
-- Name: COLUMN production_energy.source; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.production_energy.source IS '版本来源: IMPORT=导入 / MANUAL=维护页手工编辑 (task-0712 C11)';


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
    total_amount numeric(18,4) DEFAULT 0,
    expiry_date date,
    payment_terms text,
    delivery_cycle integer,
    original_amount numeric(18,4) DEFAULT 0,
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
    tax_amount numeric(18,4) DEFAULT 0 NOT NULL,
    customer_template_id uuid,
    import_batch_id uuid,
    referenced_versions jsonb,
    submission_snapshot jsonb,
    costing_card_template_id uuid,
    bound_global_variables_snapshot jsonb DEFAULT '[]'::jsonb NOT NULL,
    CONSTRAINT chk_q_priority CHECK (((priority)::text = ANY (ARRAY[('HIGH'::character varying)::text, ('MEDIUM'::character varying)::text, ('LOW'::character varying)::text]))),
    CONSTRAINT chk_q_stage CHECK (((stage)::text = ANY (ARRAY[('INITIAL_CONTACT'::character varying)::text, ('REQUIREMENT_CONFIRMATION'::character varying)::text, ('QUOTING'::character varying)::text, ('NEGOTIATION'::character varying)::text]))),
    CONSTRAINT chk_q_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'APPROVED'::character varying, 'SENT'::character varying, 'ACCEPTED'::character varying, 'REJECTED'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying, 'COSTING_REJECTED'::character varying])::text[]))),
    CONSTRAINT chk_q_type CHECK (((quote_type)::text = ANY (ARRAY[('STANDARD'::character varying)::text, ('DISCOUNT'::character varying)::text, ('BULK'::character varying)::text])))
);


--
-- Name: COLUMN quotation.referenced_versions; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation.referenced_versions IS 'v5.1 §6.6 漂移检测：DRAFT 报价单引用的基础数据版本快照
     格式：{"mat_process":{"<hfPartNo>|<customerId>":<version>,...},
             "mat_fee":{...}, "plating_fee":{...}, "element_price":{...}}
     Key 为"业务键"（hf_part_no|customer_id），Value 为该业务键当时 is_current=true 行的 version。
     仅 DRAFT 状态下填写；SUBMITTED 后冻结（submit 时不清除，但 refresh-versions 仅限 DRAFT）。';


--
-- Name: COLUMN quotation.submission_snapshot; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation.submission_snapshot IS 'v5.1 §10 快照机制：DRAFT→SUBMITTED 时冻结的全量数据快照
     格式：{ referencedVersions, elementActualPrices, formulaDefinitions, masterDataSnapshot, snapshotAt }';


--
-- Name: COLUMN quotation.bound_global_variables_snapshot; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation.bound_global_variables_snapshot IS 'V212: 报价单 DRAFT→SUBMITTED 时由 SnapshotCollectorService 写入. 数组元素: {code, name, varType, unit, displayOrder, snapshotAt, rows[]}. 快照不可变 (APPROVED/REJECTED 阶段只读); REJECTED→DRAFT→再次提交时覆盖.';


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
-- Name: TABLE quotation_component_sql_snapshot; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.quotation_component_sql_snapshot IS '报价单提交时冻结的 SQL 视图快照；key = componentId::sql_view_name';


--
-- Name: quotation_line_component_data; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quotation_line_component_data (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    line_item_id uuid NOT NULL,
    component_id uuid,
    tab_name character varying(200),
    row_data jsonb DEFAULT '[]'::jsonb,
    subtotal numeric(18,4) DEFAULT 0,
    sort_order integer DEFAULT 0,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    snapshot_rows jsonb,
    snapshot_at timestamp with time zone,
    deleted_row_keys jsonb DEFAULT '[]'::jsonb NOT NULL
);


--
-- Name: COLUMN quotation_line_component_data.snapshot_rows; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation_line_component_data.snapshot_rows IS '加产品整份快照-基础冻结层:整组行 [{driverRow, basicDataValues}]，加产品/从基础刷新时写;渲染读副本(Phase 2)';


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
-- Name: TABLE quotation_line_composite_process; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.quotation_line_composite_process IS '选配-组合工艺 per-quote 实例(按报价行存):line_item × 组合工艺步骤。configure/saveDraft 写入,composite_process_mirror 按 :lineItemId 读渲染。取代废弃的 mat_composite_process。';


--
-- Name: quotation_line_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quotation_line_item (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    quotation_id uuid NOT NULL,
    product_id uuid,
    template_id uuid,
    product_attribute_values jsonb DEFAULT '{}'::jsonb,
    subtotal numeric(18,4) DEFAULT 0,
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
    discount_base_amount numeric(18,4),
    discount_rate_applied numeric(8,4),
    line_discount_amount numeric(18,4),
    line_unit_price numeric(18,4),
    line_final_price numeric(18,4),
    line_total_amount numeric(18,4),
    discount_rule_code character varying(64),
    parent_line_item_id uuid,
    composite_type character varying(16) DEFAULT 'SIMPLE'::character varying NOT NULL,
    quote_card_values jsonb,
    quote_excel_values jsonb,
    costing_card_values jsonb,
    costing_excel_values jsonb,
    card_snapshot_at timestamp with time zone,
    quote_values_at timestamp with time zone,
    CONSTRAINT chk_quotation_line_item_composite_type CHECK (((composite_type)::text = ANY (ARRAY[('SIMPLE'::character varying)::text, ('COMPOSITE'::character varying)::text, ('PART'::character varying)::text])))
);


--
-- Name: COLUMN quotation_line_item.part_version_locked; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation_line_item.part_version_locked IS '料号版本锁定: 该行报价使用的 (customer_product_no, hf_part_no) 版本号. 创建时从 mat_customer_part_mapping.current_version 拷贝, 已发布后锁死.';


--
-- Name: COLUMN quotation_line_item.annual_volume; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation_line_item.annual_volume IS '年用量(用户输入,驱动阶梯折扣)';


--
-- Name: COLUMN quotation_line_item.discount_source; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation_line_item.discount_source IS '优惠金额来源 metric_code(MATERIAL_COST/PROCESS_FEE/.../SUBTOTAL)';


--
-- Name: COLUMN quotation_line_item.discount_base_amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation_line_item.discount_base_amount IS '可优惠金额基数 — Step3 commit 时快照';


--
-- Name: COLUMN quotation_line_item.discount_rate_applied; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation_line_item.discount_rate_applied IS '实际折扣率 %(0-100,V1 由阶梯引擎硬算)';


--
-- Name: COLUMN quotation_line_item.line_discount_amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation_line_item.line_discount_amount IS '单件优惠金额(=base × rate / 100,快照)';


--
-- Name: COLUMN quotation_line_item.line_unit_price; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation_line_item.line_unit_price IS '单价 = line_item.subtotal(快照,防 Step2 后续被改)';


--
-- Name: COLUMN quotation_line_item.line_final_price; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation_line_item.line_final_price IS '优惠后单价(=line_unit_price - line_discount_amount,快照)';


--
-- Name: COLUMN quotation_line_item.line_total_amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation_line_item.line_total_amount IS '行总金额(=annual_volume × line_final_price,快照;Σ即整单total_amount)';


--
-- Name: COLUMN quotation_line_item.discount_rule_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation_line_item.discount_rule_code IS '命中的折扣规则编号(V1 = ANNUAL_VOLUME_STEP_V1)';


--
-- Name: COLUMN quotation_line_item.parent_line_item_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation_line_item.parent_line_item_id IS '组合产品场景:子配件行→父行 id';


--
-- Name: COLUMN quotation_line_item.composite_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation_line_item.composite_type IS 'SIMPLE 独立 / COMPOSITE 组合父 / PART 组合子';


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
-- Name: COLUMN quotation_line_item_snapshot.product_part_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.quotation_line_item_snapshot.product_part_no IS 'Aligned with Product.partNo (was product_sku, V23 missed this table; renamed in V56)';


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
-- Name: TABLE resource_group; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.resource_group IS 'V6 §14 资源群组（以设备为单位的资源编组）';


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
-- Name: TABLE system_config; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_config IS 'v5.1 §5.1 系统配置表：阈值、超时、保留期、业务参数';


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
-- Name: TABLE template; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.template IS 'V62: 撤销 V28 partial unique index,同 (customer_id, category_id) 可并存多个 PUBLISHED 版本';


--
-- Name: COLUMN template.formulas; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.template.formulas IS '模板公式数组（V145，Stage 1）: [{name, expression, data_type, depends_on, description}]。求值优先级：[名称]→template.formulas → col_key → component_field；@名称→global_variable。仅 DRAFT 状态可改；保存时拓扑排序检测循环依赖。';


--
-- Name: COLUMN template.is_default; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.template.is_default IS 'V150: 是否系列默认模板 (从 costing_template.is_default 合并而来)';


--
-- Name: COLUMN template.referenced_variables; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.template.referenced_variables IS 'V150: 引用变量集 (从 costing_template.referenced_variables 合并而来)';


--
-- Name: COLUMN template.sql_views_snapshot; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.template.sql_views_snapshot IS '模板 PUBLISHED 时冻结的 SQL 视图闭包；结构: { "componentId::sql_view_name": { sql_template, declared_columns, required_variables } }';


--
-- Name: COLUMN template.template_sql_views_snapshot; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.template.template_sql_views_snapshot IS '发布时把本模板拥有的所有 template_sql_view 快照到此 JSONB。结构: {"<sql_view_name>": {sqlTemplate, declaredColumns, requiredVariables}}注意: 不要与 template.sql_views_snapshot 混淆，后者是组件 SQL 视图（component_sql_view）的冻结快照。';


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
-- Name: COLUMN template_component.data_driver_path_override; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.template_component.data_driver_path_override IS 'V200: 模板级 driver_path 覆盖 (非 NULL 时 publish 时盖 component.dataDriverPath). 用于 COMPOSITE 模板把 mat_bom→v_composite_child_elements 等场景.';


--
-- Name: COLUMN template_component.fields_override; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.template_component.fields_override IS 'V200: 模板级 fields 覆盖 (非 NULL 时 publish 时盖 component.fields). 用于 COMPOSITE 模板把字段集换成聚合视图列(加 child_part_name 等).';


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
-- Name: TABLE template_global_variable_binding; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.template_global_variable_binding IS 'V212: 模板 ↔ 全局变量 绑定 (PRD §3.7). 模板发布后报价单详情页"引用数据"Tab 据此渲染. 删除模板自动级联, 删除全局变量定义被 RESTRICT 阻拦 (要求先解绑).';


--
-- Name: COLUMN template_global_variable_binding.global_variable_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.template_global_variable_binding.global_variable_code IS '引用 global_variable_definition.code (V104 主键, VARCHAR(64) 业务编码, 非 UUID).';


--
-- Name: COLUMN template_global_variable_binding.display_order; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.template_global_variable_binding.display_order IS '该 GV 在"引用数据"Tab 中卡片渲染顺序, 0-based 升序. createNewDraft 拷贝时原样保留.';


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
-- Name: TABLE template_sql_view; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.template_sql_view IS '模板拥有的 SQL 视图（与 component_sql_view 同构 + 隔离）。引用语法 $view.col，作用域为本模板内部。owner FK 指向 template（产品卡片模板），而非 costing_template（Excel 核价模板）。';


--
-- Name: COLUMN template_sql_view.template_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.template_sql_view.template_id IS '所属产品卡片模板 ID（template.id）。';


--
-- Name: COLUMN template_sql_view.sql_view_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.template_sql_view.sql_view_name IS 'BNF 引用名（如 summary_full），同模板内唯一。小写字母/数字/下划线。';


--
-- Name: COLUMN template_sql_view.sql_template; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.template_sql_view.sql_template IS '含命名占位符的 SQL 模板（如 :customerId / :partVersion）。';


--
-- Name: COLUMN template_sql_view.declared_columns; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.template_sql_view.declared_columns IS '保存时 dry-run 自动提取的列签名。结构：[{name, dataType, nullable}, ...]';


--
-- Name: COLUMN template_sql_view.required_variables; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.template_sql_view.required_variables IS '从 sql_template 中解析出的 :xxx 占位符列表（不含 :hfPartNos）。';


--
-- Name: COLUMN template_sql_view.scope; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.template_sql_view.scope IS '命名空间范围。当前只允许 LOCAL（不支持跨模板 GLOBAL 引用）。';


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
-- Name: TABLE tooling_cost; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.tooling_cost IS 'V6 §18 模具工装成本（料号+工序+项次+模具号唯一）';


--
-- Name: COLUMN tooling_cost.source; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tooling_cost.source IS '版本来源: IMPORT=导入 / MANUAL=维护页手工编辑 (task-0712 C11)';


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
    CONSTRAINT chk_unit_price_life_unit CHECK (((life_unit IS NULL) OR ((life_unit)::text = ANY (ARRAY[('TIMES'::character varying)::text, ('HOURS'::character varying)::text, ('PCS'::character varying)::text, ('DAYS'::character varying)::text])))),
    CONSTRAINT chk_unit_price_system_type CHECK (((system_type)::text = ANY (ARRAY[('QUOTE'::character varying)::text, ('PRICING'::character varying)::text]))),
    CONSTRAINT chk_unit_price_type CHECK (((price_type)::text = ANY ((ARRAY['ELEMENT'::character varying, 'MATERIAL'::character varying, 'COMPONENT'::character varying, 'PART'::character varying, 'CONSUMABLE'::character varying, 'INCOMING_MATERIAL_PROCESS'::character varying, 'INCOMING_MATERIAL_OTHER'::character varying, 'INCOMING_MATERIAL_REDUCTION'::character varying, 'INCOMING_MATERIAL_RECYCLE'::character varying, 'PROCESS'::character varying, 'FINISHED_MATERIAL_OTHER'::character varying, 'COMPONENT_OTHER'::character varying, 'COMPONENT_REDUCTION'::character varying, 'PLATING'::character varying, 'MATERIAL_PRICE'::character varying, 'PACKAGING'::character varying, 'INCOMING_PROCESS'::character varying, 'INCOMING_OTHER'::character varying, 'SELF_PROCESS'::character varying, 'FINISHED_OTHER'::character varying, 'OUTSOURCE_PROCESS'::character varying])::text[])))
);


--
-- Name: TABLE unit_price; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.unit_price IS 'V6 §12 零件单价表（元素/材料/组成件/零件/耗材 5 类）';


--
-- Name: COLUMN unit_price.discount_order; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.unit_price.discount_order IS '年降顺序（§8 来料年降 / §15 组装年降）';


--
-- Name: COLUMN unit_price.item_seq; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.unit_price.item_seq IS '要素项次（§13 组成件其他费用 项次(要素)）';


--
-- Name: COLUMN unit_price.source; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.unit_price.source IS '版本来源: IMPORT=导入 / MANUAL=维护页手工编辑 (task-0712 C11)';


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
-- Name: v_c_summary_agg; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_c_summary_agg AS
 WITH base_parts AS (
         SELECT DISTINCT costing_part_weight.hf_part_no
           FROM public.costing_part_weight
          WHERE (COALESCE(costing_part_weight.is_active, true) = true)
        UNION
         SELECT DISTINCT costing_part_process_cost.hf_part_no
           FROM public.costing_part_process_cost
          WHERE (COALESCE(costing_part_process_cost.is_active, true) = true)
        UNION
         SELECT DISTINCT mat_fee.hf_part_no
           FROM public.mat_fee
          WHERE ((COALESCE(mat_fee.is_current, true) = true) AND ((COALESCE(mat_fee.status, 'ACTIVE'::character varying))::text = 'ACTIVE'::text))
        ), pkg_agg AS (
         SELECT costing_part_process_cost.hf_part_no,
            sum(costing_part_process_cost.unit_price) AS packaging_fee
           FROM public.costing_part_process_cost
          WHERE (((costing_part_process_cost.cost_type)::text = 'CONSUMABLE'::text) AND ((COALESCE(costing_part_process_cost.process_name, ''::character varying))::text ~~ '%包装%'::text) AND (COALESCE(costing_part_process_cost.is_active, true) = true))
          GROUP BY costing_part_process_cost.hf_part_no
        ), ifix_agg AS (
         SELECT mat_fee.hf_part_no,
            sum(mat_fee.fee_value) AS incoming_fixed_fee
           FROM public.mat_fee
          WHERE (((mat_fee.fee_type)::text = 'INCOMING_FIXED'::text) AND (COALESCE(mat_fee.is_current, true) = true) AND ((COALESCE(mat_fee.status, 'ACTIVE'::character varying))::text = 'ACTIVE'::text))
          GROUP BY mat_fee.hf_part_no
        ), outsource_agg AS (
         SELECT costing_part_process_cost.hf_part_no,
            sum(costing_part_process_cost.unit_price) AS outsource_fee
           FROM public.costing_part_process_cost
          WHERE (((costing_part_process_cost.cost_type)::text = 'POST_PROC'::text) AND (COALESCE(costing_part_process_cost.is_active, true) = true))
          GROUP BY costing_part_process_cost.hf_part_no
        ), ffix_agg AS (
         SELECT mat_fee.hf_part_no,
            sum(
                CASE
                    WHEN ((mat_fee.dim_element_name)::text ~~ '%运费%'::text) THEN mat_fee.fee_value
                    ELSE (0)::numeric
                END) AS freight_fee,
            sum(
                CASE
                    WHEN ((mat_fee.dim_element_name)::text ~~ '%清关%'::text) THEN mat_fee.fee_value
                    ELSE (0)::numeric
                END) AS customs_fee
           FROM public.mat_fee
          WHERE (((mat_fee.fee_type)::text = 'FINISHED_FIXED'::text) AND (COALESCE(mat_fee.is_current, true) = true) AND ((COALESCE(mat_fee.status, 'ACTIVE'::character varying))::text = 'ACTIVE'::text))
          GROUP BY mat_fee.hf_part_no
        )
 SELECT p.hf_part_no,
    COALESCE(pkg.packaging_fee, (0)::numeric) AS packaging_fee,
    COALESCE(ifix.incoming_fixed_fee, (0)::numeric) AS incoming_fixed_fee,
    COALESCE(out_.outsource_fee, (0)::numeric) AS outsource_fee,
    COALESCE(ff.freight_fee, (0)::numeric) AS freight_fee,
    COALESCE(ff.customs_fee, (0)::numeric) AS customs_fee,
    'CNY'::character varying(10) AS currency_label,
    'KG'::character varying(10) AS weight_unit_label
   FROM ((((base_parts p
     LEFT JOIN pkg_agg pkg ON (((pkg.hf_part_no)::text = (p.hf_part_no)::text)))
     LEFT JOIN ifix_agg ifix ON (((ifix.hf_part_no)::text = (p.hf_part_no)::text)))
     LEFT JOIN outsource_agg out_ ON (((out_.hf_part_no)::text = (p.hf_part_no)::text)))
     LEFT JOIN ffix_agg ff ON (((ff.hf_part_no)::text = (p.hf_part_no)::text)));


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
-- Name: VIEW v_composite_child_materials; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON VIEW public.v_composite_child_materials IS 'V337: 第二分支(自指兜底)反连接判据从 component_no=material_no 修正为 material_no=material_no(是否已有以本产品为宿主的 material_bom_item 行)，修正 Bug1(component_no 改存 recipe.code)后暴露的重复行问题。列/连接与 V322 相同，仅此一处谓词修正。';


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
-- Name: v_costing_element_price; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_costing_element_price AS
 SELECT e.id,
    e.element_code,
    e.costing_price,
    e.market_ref_price,
    e.source_url,
    e.source_name,
    e.source_rule,
    e.currency,
    e.unit,
    e.discount_rate,
    e.sort_order,
    e.element_code AS element_name
   FROM (public.costing_element_price e
     JOIN public.costing_price_version v ON ((v.id = e.version_id)))
  WHERE (((v.version_kind)::text = 'ELEMENT'::text) AND ((v.status)::text = 'PUBLISHED'::text) AND (v.is_default = true));


--
-- Name: v_costing_exchange_rate; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_costing_exchange_rate AS
 SELECT r.id,
    r.from_currency,
    r.to_currency,
    r.costing_rate,
    r.market_rate,
    r.rate_rule,
    r.source_url,
    r.sort_order
   FROM (public.costing_exchange_rate r
     JOIN public.costing_price_version v ON ((v.id = r.version_id)))
  WHERE (((v.version_kind)::text = 'EXCHANGE'::text) AND ((v.status)::text = 'PUBLISHED'::text) AND (v.is_default = true));


--
-- Name: v_costing_material_price; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_costing_material_price AS
 SELECT m.id,
    m.material_no,
    m.brand_name,
    m.spec,
    m.dimension,
    m.costing_price,
    m.market_ref_price,
    m.source_url,
    m.source_name,
    m.source_rule,
    m.currency,
    m.unit,
    m.discount_rate,
    m.sort_order,
    m.material_no AS input_material_no
   FROM (public.costing_material_price m
     JOIN public.costing_price_version v ON ((v.id = m.version_id)))
  WHERE (((v.version_kind)::text = 'MATERIAL'::text) AND ((v.status)::text = 'PUBLISHED'::text) AND (v.is_default = true));


--
-- Name: v_costing_summary_full; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_costing_summary_full AS
 WITH bom_expanded AS (
         SELECT m.hf_part_no,
            m.input_qty AS bom_input_qty,
            NULLIF(m.output_qty, (0)::numeric) AS bom_output_qty,
            (COALESCE(m.loss_rate, (0)::numeric) / 100.0) AS bom_loss_rate,
            COALESCE(m.fixed_loss_qty, (0)::numeric) AS bom_fixed_loss_qty,
            eb.element_code,
            (COALESCE(eb.composition_pct, (0)::numeric) / 100.0) AS elem_pct,
            (COALESCE(eb.loss_rate, (0)::numeric) / 100.0) AS elem_loss_rate,
            COALESCE(cep.costing_price, (0)::numeric) AS elem_price,
            (COALESCE(cep.discount_rate, (0)::numeric) / 100.0) AS elem_discount,
            COALESCE(cmp.costing_price, (0)::numeric) AS mat_price,
            (COALESCE(cmp.discount_rate, (0)::numeric) / 100.0) AS mat_discount,
                CASE
                    WHEN (m.input_qty >= (0)::numeric) THEN 'NORMAL'::text
                    ELSE 'RECYCLE'::text
                END AS bom_kind
           FROM (((public.costing_part_material_bom m
             LEFT JOIN public.costing_part_element_bom eb ON ((((eb.input_material_no)::text = (m.input_material_no)::text) AND (COALESCE(eb.is_active, true) = true))))
             LEFT JOIN public.v_costing_element_price cep ON (((cep.element_code)::text = (eb.element_code)::text)))
             LEFT JOIN public.v_costing_material_price cmp ON (((cmp.material_no)::text = (m.input_material_no)::text)))
          WHERE (COALESCE(m.is_active, true) = true)
        ), bom_priced AS (
         SELECT bom_expanded.hf_part_no,
            bom_expanded.bom_input_qty,
            bom_expanded.bom_output_qty,
            bom_expanded.bom_loss_rate,
            bom_expanded.bom_fixed_loss_qty,
            bom_expanded.elem_loss_rate,
            bom_expanded.bom_kind,
            ((bom_expanded.elem_price * bom_expanded.elem_pct) + (bom_expanded.mat_price * (
                CASE
                    WHEN (bom_expanded.element_code IS NULL) THEN 1
                    ELSE 0
                END)::numeric)) AS unit_price,
            (((bom_expanded.elem_price * bom_expanded.elem_pct) * bom_expanded.elem_discount) + ((bom_expanded.mat_price * bom_expanded.mat_discount) * (
                CASE
                    WHEN (bom_expanded.element_code IS NULL) THEN 1
                    ELSE 0
                END)::numeric)) AS unit_price_recycle
           FROM bom_expanded
        ), material_aggs AS (
         SELECT bom_priced.hf_part_no,
            sum(
                CASE
                    WHEN (bom_priced.bom_kind = 'NORMAL'::text) THEN (((abs(bom_priced.bom_input_qty) / NULLIF(bom_priced.bom_output_qty, (0)::numeric)) * ((1)::numeric + bom_priced.bom_loss_rate)) * bom_priced.unit_price)
                    ELSE (0)::numeric
                END) AS pure_material_cost,
            sum(
                CASE
                    WHEN (bom_priced.bom_kind = 'RECYCLE'::text) THEN ((abs(bom_priced.bom_input_qty) / NULLIF(bom_priced.bom_output_qty, (0)::numeric)) * bom_priced.unit_price_recycle)
                    ELSE (0)::numeric
                END) AS recycle_cost,
            sum(
                CASE
                    WHEN (bom_priced.bom_kind = 'NORMAL'::text) THEN (((((abs(bom_priced.bom_input_qty) / NULLIF(bom_priced.bom_output_qty, (0)::numeric)) * ((1)::numeric + bom_priced.bom_loss_rate)) * bom_priced.elem_loss_rate) * bom_priced.unit_price) + (bom_priced.bom_fixed_loss_qty * bom_priced.unit_price))
                    ELSE (0)::numeric
                END) AS material_loss_cost
           FROM bom_priced
          GROUP BY bom_priced.hf_part_no
        ), process_costs AS (
         SELECT pc_1.hf_part_no,
            sum(
                CASE
                    WHEN ((pc_1.cost_type)::text = 'MATERIAL_PROC'::text) THEN pc_1.unit_price
                    ELSE (0)::numeric
                END) AS incoming_process_fee,
            sum(
                CASE
                    WHEN ((pc_1.cost_type)::text = ANY (ARRAY[('LABOR'::character varying)::text, ('DEPRECIATION'::character varying)::text, ('ENERGY_DEDICATED'::character varying)::text, ('ENERGY_SHARED'::character varying)::text, ('CONSUMABLE'::character varying)::text, ('SEMI_FINISHED_PROC'::character varying)::text, ('POST_PROC'::character varying)::text])) THEN pc_1.unit_price
                    ELSE (0)::numeric
                END) AS process_fee_base
           FROM public.costing_part_process_cost pc_1
          WHERE (COALESCE(pc_1.is_active, true) = true)
          GROUP BY pc_1.hf_part_no
        ), plating_data AS (
         SELECT pf.hf_part_no,
            sum(COALESCE(pf.plating_process_fee, (0)::numeric)) AS plating_process_fee,
            sum(COALESCE(pf.plating_material_fee, (0)::numeric)) AS plating_material_fee,
            avg(COALESCE(pf.defect_rate, (0)::numeric)) AS plating_defect_rate
           FROM public.plating_fee pf
          WHERE (COALESCE(pf.is_current, true) = true)
          GROUP BY pf.hf_part_no
        ), weight_data AS (
         SELECT costing_part_weight.hf_part_no,
            costing_part_weight.weight_g_per_pcs
           FROM public.costing_part_weight
          WHERE (COALESCE(costing_part_weight.is_active, true) = true)
        ), exchange_data AS (
         SELECT v_costing_exchange_rate.costing_rate AS exchange_rate_to_usd
           FROM public.v_costing_exchange_rate
          WHERE (((v_costing_exchange_rate.from_currency)::text = 'CNY'::text) AND ((v_costing_exchange_rate.to_currency)::text = 'USD'::text))
         LIMIT 1
        ), fee_ratios AS (
         SELECT f.hf_part_no,
            sum(
                CASE
                    WHEN ((f.dim_element_name)::text ~~ '%管理%'::text) THEN COALESCE(f.fee_ratio, (0)::numeric)
                    ELSE (0)::numeric
                END) AS mgmt_fee_ratio,
            sum(
                CASE
                    WHEN ((f.dim_element_name)::text ~~ '%财务%'::text) THEN COALESCE(f.fee_ratio, (0)::numeric)
                    ELSE (0)::numeric
                END) AS finance_fee_ratio,
            sum(
                CASE
                    WHEN ((f.dim_element_name)::text ~~ '%利润%'::text) THEN COALESCE(f.fee_ratio, (0)::numeric)
                    ELSE (0)::numeric
                END) AS profit_ratio,
            sum(
                CASE
                    WHEN ((f.dim_element_name)::text ~~ '%税%'::text) THEN COALESCE(f.fee_ratio, (0)::numeric)
                    ELSE (0)::numeric
                END) AS tax_ratio
           FROM public.mat_fee f
          WHERE (((f.fee_type)::text = 'FINISHED_OTHER'::text) AND (COALESCE(f.is_current, true) = true))
          GROUP BY f.hf_part_no
        ), incoming_other AS (
         SELECT f.hf_part_no,
            sum(COALESCE(f.fee_ratio, (0)::numeric)) AS incoming_other_total_ratio
           FROM public.mat_fee f
          WHERE (((f.fee_type)::text = 'INCOMING_OTHER'::text) AND (COALESCE(f.is_current, true) = true))
          GROUP BY f.hf_part_no
        ), agg_old AS (
         SELECT s.id AS summary_id,
            s.summary_no,
            s.hf_part_no,
            s.status,
            s.quote_currency,
            s.element_version_id,
            s.material_version_id,
            s.exchange_version_id,
            max(
                CASE
                    WHEN ((r.metric_code)::text = 'MATERIAL_COST'::text) THEN r.value
                    ELSE NULL::numeric
                END) AS metric_material_cost,
            max(
                CASE
                    WHEN ((r.metric_code)::text = 'PROCESS_FEE'::text) THEN r.value
                    ELSE NULL::numeric
                END) AS metric_processing_cost
           FROM (public.costing_summary s
             LEFT JOIN public.costing_summary_result r ON ((r.summary_id = s.id)))
          GROUP BY s.id, s.summary_no, s.hf_part_no, s.status, s.quote_currency, s.element_version_id, s.material_version_id, s.exchange_version_id
        )
 SELECT a.summary_id,
    a.summary_no,
    a.hf_part_no,
    (row_number() OVER (PARTITION BY a.hf_part_no ORDER BY a.summary_no))::integer AS line_seq,
    a.status,
        CASE a.status
            WHEN 'PUBLISHED'::text THEN '是'::text
            ELSE '否'::text
        END AS is_published_label,
    a.quote_currency,
    'KG'::character varying(10) AS weight_unit,
    ev.version_number AS element_version_number,
    mv.version_number AS material_version_number,
    xv.version_number AS exchange_version_number,
    a.metric_material_cost AS material_cost,
    a.metric_processing_cost AS processing_cost,
    ma.pure_material_cost,
    ma.recycle_cost,
    ma.material_loss_cost,
    pc.incoming_process_fee,
    (ma.pure_material_cost * io.incoming_other_total_ratio) AS incoming_other_fee,
    (pc.process_fee_base * ((1)::numeric + COALESCE(( SELECT bom_priced.bom_loss_rate
           FROM bom_priced
          WHERE ((bom_priced.hf_part_no)::text = (a.hf_part_no)::text)
         LIMIT 1), (0)::numeric))) AS process_fee_total,
    pld.plating_process_fee,
    pld.plating_material_fee,
    pld.plating_defect_rate,
    (0)::numeric AS outsource_fee_total,
    w.weight_g_per_pcs AS unit_weight_g,
    e.exchange_rate_to_usd,
    fr.mgmt_fee_ratio,
    fr.finance_fee_ratio,
    fr.profit_ratio,
    fr.tax_ratio
   FROM ((((((((((agg_old a
     LEFT JOIN material_aggs ma ON (((ma.hf_part_no)::text = (a.hf_part_no)::text)))
     LEFT JOIN process_costs pc ON (((pc.hf_part_no)::text = (a.hf_part_no)::text)))
     LEFT JOIN plating_data pld ON (((pld.hf_part_no)::text = (a.hf_part_no)::text)))
     LEFT JOIN weight_data w ON (((w.hf_part_no)::text = (a.hf_part_no)::text)))
     LEFT JOIN exchange_data e ON (true))
     LEFT JOIN fee_ratios fr ON (((fr.hf_part_no)::text = (a.hf_part_no)::text)))
     LEFT JOIN incoming_other io ON (((io.hf_part_no)::text = (a.hf_part_no)::text)))
     LEFT JOIN public.costing_price_version ev ON ((ev.id = a.element_version_id)))
     LEFT JOIN public.costing_price_version mv ON ((mv.id = a.material_version_id)))
     LEFT JOIN public.costing_price_version xv ON ((xv.id = a.exchange_version_id)));


--
-- Name: v_part_material_recipe; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_part_material_recipe AS
 SELECT mp.part_no AS hf_part_no,
    mp.material_recipe_id AS recipe_id,
    mr.code,
    mr.symbol,
    mr.name,
    mr.spec_label,
    mr.recipe_type
   FROM (public.mat_part mp
     LEFT JOIN public.material_recipe mr ON ((mr.id = mp.material_recipe_id)));


--
-- Name: v_q_part_info_merged; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_q_part_info_merged AS
 SELECT 'PART'::character varying AS source_type,
    m.hf_part_no,
    m.customer_part_name,
    m.customer_product_no,
    m.customer_drawing_no,
    m.payment_method,
    m.base_currency,
    m.quote_currency,
    er.rate AS exchange_rate,
    p.unit_weight,
    p.weight_unit
   FROM ((public.mat_customer_part_mapping m
     LEFT JOIN public.mat_part p ON (((p.part_no)::text = (m.hf_part_no)::text)))
     LEFT JOIN public.exchange_rate er ON (((er.customer_id = m.customer_id) AND ((er.from_currency)::text = (m.base_currency)::text) AND ((er.to_currency)::text = (m.quote_currency)::text) AND (er.is_current = true))));


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
-- Name: TABLE variable_label; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.variable_label IS 'V149: 视图列中文标签字典. Excel 列编辑器/公式 [col_key] 引用的 SSOT. 渐进式注册: 未命名字段前端回退到 raw path 并引导用户起名.';


--
-- Name: COLUMN variable_label.variable_path; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.variable_label.variable_path IS '视图列路径, 形如 v_c_summary_agg.packaging_fee. 全局唯一.';


--
-- Name: COLUMN variable_label.category; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.variable_label.category IS '业务分类, 当前 5 类: 成本汇总 / 费用比率 / 物料属性 / 单位标签 / 汇率';


--
-- Name: COLUMN variable_label.data_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.variable_label.data_type IS 'DECIMAL / INTEGER / PERCENT / STRING / DATE, 引导前端格式化展示';


--
-- Name: COLUMN variable_label.source_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.variable_label.source_type IS 'VIEW_COLUMN (来自 SQL 视图列, 当前唯一支持) / CONSTANT / DERIVED (预留)';


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
-- Name: costing_element_price costing_element_price_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_element_price
    ADD CONSTRAINT costing_element_price_pkey PRIMARY KEY (id);


--
-- Name: costing_exchange_rate costing_exchange_rate_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_exchange_rate
    ADD CONSTRAINT costing_exchange_rate_pkey PRIMARY KEY (id);


--
-- Name: costing_material_price costing_material_price_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_material_price
    ADD CONSTRAINT costing_material_price_pkey PRIMARY KEY (id);


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
-- Name: costing_part_design_cost costing_part_design_cost_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_design_cost
    ADD CONSTRAINT costing_part_design_cost_pkey PRIMARY KEY (id);


--
-- Name: costing_part_element_bom costing_part_element_bom_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_element_bom
    ADD CONSTRAINT costing_part_element_bom_pkey PRIMARY KEY (id);


--
-- Name: costing_part_material_bom costing_part_material_bom_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_material_bom
    ADD CONSTRAINT costing_part_material_bom_pkey PRIMARY KEY (id);


--
-- Name: costing_part_plating_fee costing_part_plating_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_plating_fee
    ADD CONSTRAINT costing_part_plating_fee_pkey PRIMARY KEY (id);


--
-- Name: costing_part_plating costing_part_plating_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_plating
    ADD CONSTRAINT costing_part_plating_pkey PRIMARY KEY (id);


--
-- Name: costing_part_process_cost costing_part_process_cost_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_process_cost
    ADD CONSTRAINT costing_part_process_cost_pkey PRIMARY KEY (id);


--
-- Name: costing_part_quality_check costing_part_quality_check_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_quality_check
    ADD CONSTRAINT costing_part_quality_check_pkey PRIMARY KEY (id);


--
-- Name: costing_part_tooling_cost costing_part_tooling_cost_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_tooling_cost
    ADD CONSTRAINT costing_part_tooling_cost_pkey PRIMARY KEY (id);


--
-- Name: costing_part_weight costing_part_weight_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_weight
    ADD CONSTRAINT costing_part_weight_pkey PRIMARY KEY (id);


--
-- Name: costing_price_version costing_price_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_price_version
    ADD CONSTRAINT costing_price_version_pkey PRIMARY KEY (id);


--
-- Name: costing_sheet costing_sheet_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_sheet
    ADD CONSTRAINT costing_sheet_pkey PRIMARY KEY (id);


--
-- Name: costing_sheet costing_sheet_quotation_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_sheet
    ADD CONSTRAINT costing_sheet_quotation_id_key UNIQUE (quotation_id);


--
-- Name: costing_summary_override costing_summary_override_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_summary_override
    ADD CONSTRAINT costing_summary_override_pkey PRIMARY KEY (id);


--
-- Name: costing_summary costing_summary_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_summary
    ADD CONSTRAINT costing_summary_pkey PRIMARY KEY (id);


--
-- Name: costing_summary_result costing_summary_result_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_summary_result
    ADD CONSTRAINT costing_summary_result_pkey PRIMARY KEY (id);


--
-- Name: costing_summary costing_summary_summary_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_summary
    ADD CONSTRAINT costing_summary_summary_no_key UNIQUE (summary_no);


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
-- Name: mat_bom mat_bom_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_bom
    ADD CONSTRAINT mat_bom_pkey PRIMARY KEY (id);


--
-- Name: mat_bom_staging mat_bom_staging_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_bom_staging
    ADD CONSTRAINT mat_bom_staging_pkey PRIMARY KEY (staging_id);


--
-- Name: mat_composite_process mat_composite_process_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_composite_process
    ADD CONSTRAINT mat_composite_process_pkey PRIMARY KEY (id);


--
-- Name: mat_customer_part_mapping mat_customer_part_mapping_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_customer_part_mapping
    ADD CONSTRAINT mat_customer_part_mapping_pkey PRIMARY KEY (id);


--
-- Name: mat_customer_part_mapping_staging mat_customer_part_mapping_staging_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_customer_part_mapping_staging
    ADD CONSTRAINT mat_customer_part_mapping_staging_pkey PRIMARY KEY (staging_id);


--
-- Name: mat_fee mat_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_fee
    ADD CONSTRAINT mat_fee_pkey PRIMARY KEY (id);


--
-- Name: mat_fee_staging mat_fee_staging_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_fee_staging
    ADD CONSTRAINT mat_fee_staging_pkey PRIMARY KEY (staging_id);


--
-- Name: mat_part_model mat_part_model_part_no_version_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_part_model
    ADD CONSTRAINT mat_part_model_part_no_version_key UNIQUE (part_no, version);


--
-- Name: mat_part_model mat_part_model_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_part_model
    ADD CONSTRAINT mat_part_model_pkey PRIMARY KEY (id);


--
-- Name: mat_part mat_part_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_part
    ADD CONSTRAINT mat_part_pkey PRIMARY KEY (part_no);


--
-- Name: mat_part_source_file mat_part_source_file_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_part_source_file
    ADD CONSTRAINT mat_part_source_file_pkey PRIMARY KEY (id);


--
-- Name: mat_part_staging mat_part_staging_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_part_staging
    ADD CONSTRAINT mat_part_staging_pkey PRIMARY KEY (staging_id);


--
-- Name: mat_part_version_log mat_part_version_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_part_version_log
    ADD CONSTRAINT mat_part_version_log_pkey PRIMARY KEY (customer_id, hf_part_no, version);


--
-- Name: mat_plating_fee mat_plating_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_plating_fee
    ADD CONSTRAINT mat_plating_fee_pkey PRIMARY KEY (id);


--
-- Name: mat_plating_fee_staging mat_plating_fee_staging_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_plating_fee_staging
    ADD CONSTRAINT mat_plating_fee_staging_pkey PRIMARY KEY (staging_id);


--
-- Name: mat_plating_plan mat_plating_plan_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_plating_plan
    ADD CONSTRAINT mat_plating_plan_pkey PRIMARY KEY (id);


--
-- Name: mat_plating_plan_staging mat_plating_plan_staging_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_plating_plan_staging
    ADD CONSTRAINT mat_plating_plan_staging_pkey PRIMARY KEY (staging_id);


--
-- Name: mat_process mat_process_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_process
    ADD CONSTRAINT mat_process_pkey PRIMARY KEY (id);


--
-- Name: mat_process_staging mat_process_staging_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_process_staging
    ADD CONSTRAINT mat_process_staging_pkey PRIMARY KEY (staging_id);


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
-- Name: plating_plan plating_plan_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plating_plan
    ADD CONSTRAINT plating_plan_pkey PRIMARY KEY (id);


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
-- Name: CONSTRAINT uq_bda_config_var ON basic_data_attribute; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON CONSTRAINT uq_bda_config_var ON public.basic_data_attribute IS 'V57: variable_code unique within same config, can be reused across configs (e.g. HF_PART_NO)';


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
-- Name: costing_element_price uq_costing_element_per_version; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_element_price
    ADD CONSTRAINT uq_costing_element_per_version UNIQUE (version_id, element_code);


--
-- Name: costing_material_price uq_costing_material_per_version; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_material_price
    ADD CONSTRAINT uq_costing_material_per_version UNIQUE (version_id, material_no);


--
-- Name: costing_part_plating_fee uq_costing_part_plating_fee; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_plating_fee
    ADD CONSTRAINT uq_costing_part_plating_fee UNIQUE (hf_part_no, plating_plan_code, plan_version, part_version);


--
-- Name: costing_part_weight uq_costing_part_weight; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_weight
    ADD CONSTRAINT uq_costing_part_weight UNIQUE (hf_part_no, part_version);


--
-- Name: costing_exchange_rate uq_costing_rate_per_version; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_exchange_rate
    ADD CONSTRAINT uq_costing_rate_per_version UNIQUE (version_id, from_currency, to_currency);


--
-- Name: costing_price_version uq_costing_version_kind_no; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_price_version
    ADD CONSTRAINT uq_costing_version_kind_no UNIQUE (version_kind, version_number);


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
-- Name: CONSTRAINT uq_da_host_var ON derived_attribute; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON CONSTRAINT uq_da_host_var ON public.derived_attribute IS 'V57: variable_code unique within same host_sheet, can be reused across sheets';


--
-- Name: costing_part_design_cost uq_design_cost; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_design_cost
    ADD CONSTRAINT uq_design_cost UNIQUE (hf_part_no, design_drawing_no, version_number, part_version);


--
-- Name: datasource_param uq_ds_param_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.datasource_param
    ADD CONSTRAINT uq_ds_param_code UNIQUE (datasource_id, param_code);


--
-- Name: costing_part_element_bom uq_element_bom; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_element_bom
    ADD CONSTRAINT uq_element_bom UNIQUE (input_material_no, seq_no, element_code, part_version);


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
-- Name: mat_composite_process uq_mat_composite_process; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_composite_process
    ADD CONSTRAINT uq_mat_composite_process UNIQUE (hf_part_no, seq_no, part_version);


--
-- Name: costing_part_material_bom uq_material_bom; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_material_bom
    ADD CONSTRAINT uq_material_bom UNIQUE (hf_part_no, seq_no, part_version);


--
-- Name: costing_summary_override uq_override; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_summary_override
    ADD CONSTRAINT uq_override UNIQUE (summary_id, target_kind, target_key, field_name);


--
-- Name: costing_part_plating uq_plating; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_plating
    ADD CONSTRAINT uq_plating UNIQUE (plating_no, version_number, seq_no, part_version);


--
-- Name: costing_part_process_cost uq_process_cost; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_process_cost
    ADD CONSTRAINT uq_process_cost UNIQUE (hf_part_no, process_no, cost_type, part_version);


--
-- Name: product_process uq_product_process; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_process
    ADD CONSTRAINT uq_product_process UNIQUE (product_id, process_id);


--
-- Name: costing_part_quality_check uq_qc; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_quality_check
    ADD CONSTRAINT uq_qc UNIQUE (hf_part_no, stage, primary_seq_no, seq_no, part_version);


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
-- Name: costing_summary_result uq_result; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_summary_result
    ADD CONSTRAINT uq_result UNIQUE (summary_id, metric_code);


--
-- Name: sel_part_signature uq_sel_part_signature; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sel_part_signature
    ADD CONSTRAINT uq_sel_part_signature UNIQUE (customer_no, structure_version, config_fingerprint);


--
-- Name: template_global_variable_binding uq_tgvb_template_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.template_global_variable_binding
    ADD CONSTRAINT uq_tgvb_template_code UNIQUE (template_id, global_variable_code);


--
-- Name: costing_part_tooling_cost uq_tooling; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_part_tooling_cost
    ADD CONSTRAINT uq_tooling UNIQUE (hf_part_no, process_no, seq_no, part_version);


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

CREATE INDEX idx_annual_discount_material ON public.annual_discount USING btree (material_no, biz_type);


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
-- Name: idx_costing_element_version; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_costing_element_version ON public.costing_element_price USING btree (version_id);


--
-- Name: idx_costing_exchange_version; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_costing_exchange_version ON public.costing_exchange_rate USING btree (version_id);


--
-- Name: idx_costing_material_version; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_costing_material_version ON public.costing_material_price USING btree (version_id);


--
-- Name: idx_costing_order_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_costing_order_quotation ON public.costing_order USING btree (quotation_id);


--
-- Name: idx_costing_order_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_costing_order_status ON public.costing_order USING btree (status);


--
-- Name: idx_costing_part_plating_fee_part; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_costing_part_plating_fee_part ON public.costing_part_plating_fee USING btree (hf_part_no);


--
-- Name: idx_costing_sheet_quotation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_costing_sheet_quotation ON public.costing_sheet USING btree (quotation_id);


--
-- Name: idx_costing_sheet_template; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_costing_sheet_template ON public.costing_sheet USING btree (costing_template_id);


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
-- Name: idx_costing_version_kind_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_costing_version_kind_status ON public.costing_price_version USING btree (version_kind, status);


--
-- Name: idx_covo_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_covo_order ON public.costing_order_version_override USING btree (costing_order_id);


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
-- Name: idx_design_cost_part; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_design_cost_part ON public.costing_part_design_cost USING btree (hf_part_no);


--
-- Name: idx_dsp_datasource; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dsp_datasource ON public.datasource_param USING btree (datasource_id);


--
-- Name: idx_electricity_price_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_electricity_price_lookup ON public.electricity_price USING btree (region, effective_date DESC);


--
-- Name: idx_element_bom_input; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_element_bom_input ON public.costing_part_element_bom USING btree (input_material_no);


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
-- Name: idx_mat_bom_child_part_no; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_bom_child_part_no ON public.mat_bom USING btree (child_part_no) WHERE (child_part_no IS NOT NULL);


--
-- Name: idx_mat_bom_type_part; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_bom_type_part ON public.mat_bom USING btree (bom_type, hf_part_no, seq_no);


--
-- Name: idx_mat_composite_process_part_version; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_composite_process_part_version ON public.mat_composite_process USING btree (hf_part_no, part_version);


--
-- Name: idx_mat_fee_current; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_fee_current ON public.mat_fee USING btree (is_current);


--
-- Name: idx_mat_fee_cust_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_fee_cust_type ON public.mat_fee USING btree (customer_id, fee_type, hf_part_no, version);


--
-- Name: idx_mat_part_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_part_category ON public.mat_part USING btree (category_id, status_code);


--
-- Name: idx_mat_part_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_part_pending ON public.mat_part USING btree (is_pending_category);


--
-- Name: idx_mat_part_product_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_part_product_type ON public.mat_part USING btree (product_type);


--
-- Name: idx_mat_part_recipe; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_part_recipe ON public.mat_part USING btree (material_recipe_id);


--
-- Name: idx_mat_part_version_log_customer_hf; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_part_version_log_customer_hf ON public.mat_part_version_log USING btree (customer_id, hf_part_no, version DESC);


--
-- Name: idx_mat_part_version_log_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_part_version_log_hash ON public.mat_part_version_log USING btree (content_hash) WHERE (content_hash IS NOT NULL);


--
-- Name: idx_mat_part_version_log_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_part_version_log_lookup ON public.mat_part_version_log USING btree (customer_product_no, hf_part_no, version DESC);


--
-- Name: idx_mat_plating_fee_curr; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_plating_fee_curr ON public.mat_plating_fee USING btree (is_current);


--
-- Name: idx_mat_plating_fee_cust; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_plating_fee_cust ON public.mat_plating_fee USING btree (customer_id, hf_part_no, version);


--
-- Name: idx_mat_process_current; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_process_current ON public.mat_process USING btree (is_current);


--
-- Name: idx_mat_process_cust_part; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_process_cust_part ON public.mat_process USING btree (customer_id, hf_part_no, version);


--
-- Name: idx_mat_process_cust_part_lid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_process_cust_part_lid ON public.mat_process USING btree (customer_id, hf_part_no, quotation_line_item_id) WHERE (is_current = true);


--
-- Name: idx_mat_process_line_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mat_process_line_item ON public.mat_process USING btree (quotation_line_item_id) WHERE (quotation_line_item_id IS NOT NULL);


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
-- Name: idx_material_bom_part; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_material_bom_part ON public.costing_part_material_bom USING btree (hf_part_no);


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
-- Name: idx_mpm_current; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mpm_current ON public.mat_part_model USING btree (part_no, is_current) WHERE (is_current = true);


--
-- Name: idx_mpm_partno; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mpm_partno ON public.mat_part_model USING btree (part_no);


--
-- Name: idx_mpsf_model; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mpsf_model ON public.mat_part_source_file USING btree (model_id) WHERE (model_id IS NOT NULL);


--
-- Name: idx_mpsf_part; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mpsf_part ON public.mat_part_source_file USING btree (part_no);


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
-- Name: idx_override_summary; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_override_summary ON public.costing_summary_override USING btree (summary_id);


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
-- Name: idx_plating_no; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plating_no ON public.costing_part_plating USING btree (plating_no);


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
-- Name: idx_process_cost_part; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_process_cost_part ON public.costing_part_process_cost USING btree (hf_part_no);


--
-- Name: idx_process_cost_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_process_cost_type ON public.costing_part_process_cost USING btree (cost_type);


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
-- Name: idx_qc_part; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_qc_part ON public.costing_part_quality_check USING btree (hf_part_no, stage);


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
-- Name: idx_result_summary; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_result_summary ON public.costing_summary_result USING btree (summary_id);


--
-- Name: idx_sel_part_signature_quote; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sel_part_signature_quote ON public.sel_part_signature USING btree (quote_part_no);


--
-- Name: idx_sel_tiv_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sel_tiv_item ON public.sel_template_item_value USING btree (item_id);


--
-- Name: idx_summary_part; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_summary_part ON public.costing_summary USING btree (hf_part_no);


--
-- Name: idx_summary_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_summary_status ON public.costing_summary USING btree (status);


--
-- Name: idx_sysconf_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sysconf_category ON public.system_config USING btree (category);


--
-- Name: idx_tc_template; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tc_template ON public.template_component USING btree (template_id);


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
-- Name: idx_tooling_part; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tooling_part ON public.costing_part_tooling_cost USING btree (hf_part_no);


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
-- Name: ix_import_session_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_session_customer ON public.import_session USING btree (customer_id);


--
-- Name: ix_import_session_status_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_import_session_status_expires ON public.import_session USING btree (status, expires_at);


--
-- Name: ix_mat_bom_staging_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mat_bom_staging_session ON public.mat_bom_staging USING btree (import_session_id);


--
-- Name: ix_mat_customer_part_mapping_staging_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mat_customer_part_mapping_staging_session ON public.mat_customer_part_mapping_staging USING btree (import_session_id);


--
-- Name: ix_mat_fee_staging_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mat_fee_staging_session ON public.mat_fee_staging USING btree (import_session_id);


--
-- Name: ix_mat_part_staging_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mat_part_staging_session ON public.mat_part_staging USING btree (import_session_id);


--
-- Name: ix_mat_plating_fee_staging_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mat_plating_fee_staging_session ON public.mat_plating_fee_staging USING btree (import_session_id);


--
-- Name: ix_mat_plating_plan_staging_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mat_plating_plan_staging_session ON public.mat_plating_plan_staging USING btree (import_session_id);


--
-- Name: ix_mat_process_staging_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mat_process_staging_session ON public.mat_process_staging USING btree (import_session_id);


--
-- Name: uq_annual_discount; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_annual_discount ON public.annual_discount USING btree (biz_type, material_no, discount_strategy, discount_order);


--
-- Name: uq_auxiliary_energy; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_auxiliary_energy ON public.auxiliary_energy USING btree (material_no, process_no, COALESCE(calc_version, ''::character varying));


--
-- Name: uq_bdc_sheet_name_kind; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_bdc_sheet_name_kind ON public.basic_data_config USING btree (sheet_name, template_kind) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: INDEX uq_bdc_sheet_name_kind; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON INDEX public.uq_bdc_sheet_name_kind IS 'V94: 同 sheet_name 在不同 template_kind (QUOTATION/COSTING/BOTH) 下可有独立配置';


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
-- Name: uq_costing_version_default; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_costing_version_default ON public.costing_price_version USING btree (version_kind) WHERE ((is_default = true) AND ((status)::text = 'PUBLISHED'::text));


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
-- Name: uq_eps_name_url; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_eps_name_url ON public.element_price_source USING btree (source_name, COALESCE(source_url, ''::character varying));


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
-- Name: uq_mat_bom_row; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_mat_bom_row ON public.mat_bom USING btree (bom_type, hf_part_no, seq_no, COALESCE(input_material_no, ''::character varying), COALESCE(element_name, ''::character varying), part_version);


--
-- Name: uq_mat_cust_part_per_hf; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_mat_cust_part_per_hf ON public.mat_customer_part_mapping USING btree (customer_id, hf_part_no);


--
-- Name: INDEX uq_mat_cust_part_per_hf; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON INDEX public.uq_mat_cust_part_per_hf IS 'V176: 客户对 hf 的附属属性表唯一性 — 同客户内,1 hf 只能有 1 行 mapping';


--
-- Name: uq_mat_fee_current; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_mat_fee_current ON public.mat_fee USING btree (customer_id, hf_part_no, part_version, fee_type, seq_no, COALESCE(dim_input_material_no, ''::character varying), COALESCE(dim_input_material_name, ''::character varying), COALESCE(dim_element_name, ''::character varying), COALESCE(dim_assembly_process, ''::character varying), COALESCE(dim_sub_seq_no, '-1'::integer)) WHERE (is_current = true);


--
-- Name: uq_mat_part_fingerprint; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_mat_part_fingerprint ON public.mat_part USING btree (config_fingerprint) WHERE (config_fingerprint IS NOT NULL);


--
-- Name: uq_mat_plating_fee_current; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_mat_plating_fee_current ON public.mat_plating_fee USING btree (customer_id, hf_part_no, part_version, COALESCE(plating_plan_code, ''::character varying), COALESCE(plan_version, ''::character varying)) WHERE (is_current = true);


--
-- Name: uq_mat_plating_plan_row; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_mat_plating_plan_row ON public.mat_plating_plan USING btree (plan_code, version, seq_no, part_version);


--
-- Name: uq_mat_process_current; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_mat_process_current ON public.mat_process USING btree (customer_id, hf_part_no, part_version, seq_no, COALESCE(sub_seq_no, '-1'::integer), COALESCE(quotation_line_item_id, '00000000-0000-0000-0000-000000000000'::uuid)) WHERE (is_current = true);


--
-- Name: uq_mat_process_row; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_mat_process_row ON public.mat_process USING btree (customer_id, hf_part_no, part_version, version, seq_no, sub_seq_no);


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
-- Name: uq_plating_plan_row; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_plating_plan_row ON public.plating_plan USING btree (plan_code, version, seq_no);


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
-- Name: ux_cbt_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_cbt_active ON public.costing_bom_tree_config USING btree (is_active) WHERE is_active;


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
-- Name: costing_element_price costing_element_price_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_element_price
    ADD CONSTRAINT costing_element_price_version_id_fkey FOREIGN KEY (version_id) REFERENCES public.costing_price_version(id) ON DELETE CASCADE;


--
-- Name: costing_exchange_rate costing_exchange_rate_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_exchange_rate
    ADD CONSTRAINT costing_exchange_rate_version_id_fkey FOREIGN KEY (version_id) REFERENCES public.costing_price_version(id) ON DELETE CASCADE;


--
-- Name: costing_material_price costing_material_price_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_material_price
    ADD CONSTRAINT costing_material_price_version_id_fkey FOREIGN KEY (version_id) REFERENCES public.costing_price_version(id) ON DELETE CASCADE;


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
-- Name: costing_sheet costing_sheet_costing_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_sheet
    ADD CONSTRAINT costing_sheet_costing_template_id_fkey FOREIGN KEY (costing_template_id) REFERENCES public.costing_template(id);


--
-- Name: costing_sheet costing_sheet_quotation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_sheet
    ADD CONSTRAINT costing_sheet_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES public.quotation(id) ON DELETE CASCADE;


--
-- Name: costing_summary costing_summary_element_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_summary
    ADD CONSTRAINT costing_summary_element_version_id_fkey FOREIGN KEY (element_version_id) REFERENCES public.costing_price_version(id);


--
-- Name: costing_summary costing_summary_exchange_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_summary
    ADD CONSTRAINT costing_summary_exchange_version_id_fkey FOREIGN KEY (exchange_version_id) REFERENCES public.costing_price_version(id);


--
-- Name: costing_summary costing_summary_material_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_summary
    ADD CONSTRAINT costing_summary_material_version_id_fkey FOREIGN KEY (material_version_id) REFERENCES public.costing_price_version(id);


--
-- Name: costing_summary_override costing_summary_override_summary_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_summary_override
    ADD CONSTRAINT costing_summary_override_summary_id_fkey FOREIGN KEY (summary_id) REFERENCES public.costing_summary(id) ON DELETE CASCADE;


--
-- Name: costing_summary_result costing_summary_result_summary_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.costing_summary_result
    ADD CONSTRAINT costing_summary_result_summary_id_fkey FOREIGN KEY (summary_id) REFERENCES public.costing_summary(id) ON DELETE CASCADE;


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
-- Name: mat_composite_process fk_mat_composite_process_part; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_composite_process
    ADD CONSTRAINT fk_mat_composite_process_part FOREIGN KEY (hf_part_no) REFERENCES public.mat_part(part_no);


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
-- Name: mat_bom mat_bom_hf_part_no_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_bom
    ADD CONSTRAINT mat_bom_hf_part_no_fkey FOREIGN KEY (hf_part_no) REFERENCES public.mat_part(part_no);


--
-- Name: mat_bom_staging mat_bom_staging_import_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_bom_staging
    ADD CONSTRAINT mat_bom_staging_import_session_id_fkey FOREIGN KEY (import_session_id) REFERENCES public.import_session(id) ON DELETE CASCADE;


--
-- Name: mat_composite_process mat_composite_process_def_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_composite_process
    ADD CONSTRAINT mat_composite_process_def_code_fkey FOREIGN KEY (def_code) REFERENCES public.composite_process_def(code);


--
-- Name: mat_customer_part_mapping mat_customer_part_mapping_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_customer_part_mapping
    ADD CONSTRAINT mat_customer_part_mapping_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: mat_customer_part_mapping mat_customer_part_mapping_hf_part_no_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_customer_part_mapping
    ADD CONSTRAINT mat_customer_part_mapping_hf_part_no_fkey FOREIGN KEY (hf_part_no) REFERENCES public.mat_part(part_no);


--
-- Name: mat_customer_part_mapping_staging mat_customer_part_mapping_staging_import_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_customer_part_mapping_staging
    ADD CONSTRAINT mat_customer_part_mapping_staging_import_session_id_fkey FOREIGN KEY (import_session_id) REFERENCES public.import_session(id) ON DELETE CASCADE;


--
-- Name: mat_fee mat_fee_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_fee
    ADD CONSTRAINT mat_fee_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: mat_fee mat_fee_hf_part_no_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_fee
    ADD CONSTRAINT mat_fee_hf_part_no_fkey FOREIGN KEY (hf_part_no) REFERENCES public.mat_part(part_no);


--
-- Name: mat_fee_staging mat_fee_staging_import_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_fee_staging
    ADD CONSTRAINT mat_fee_staging_import_session_id_fkey FOREIGN KEY (import_session_id) REFERENCES public.import_session(id) ON DELETE CASCADE;


--
-- Name: mat_part mat_part_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_part
    ADD CONSTRAINT mat_part_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.product_category(id);


--
-- Name: mat_part mat_part_material_recipe_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_part
    ADD CONSTRAINT mat_part_material_recipe_id_fkey FOREIGN KEY (material_recipe_id) REFERENCES public.material_recipe(id) ON DELETE SET NULL;


--
-- Name: mat_part_source_file mat_part_source_file_model_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_part_source_file
    ADD CONSTRAINT mat_part_source_file_model_id_fkey FOREIGN KEY (model_id) REFERENCES public.mat_part_model(id) ON DELETE CASCADE;


--
-- Name: mat_part_staging mat_part_staging_import_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_part_staging
    ADD CONSTRAINT mat_part_staging_import_session_id_fkey FOREIGN KEY (import_session_id) REFERENCES public.import_session(id) ON DELETE CASCADE;


--
-- Name: mat_part_version_log mat_part_version_log_customer_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_part_version_log
    ADD CONSTRAINT mat_part_version_log_customer_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id) ON DELETE CASCADE;


--
-- Name: mat_plating_fee mat_plating_fee_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_plating_fee
    ADD CONSTRAINT mat_plating_fee_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: mat_plating_fee mat_plating_fee_hf_part_no_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_plating_fee
    ADD CONSTRAINT mat_plating_fee_hf_part_no_fkey FOREIGN KEY (hf_part_no) REFERENCES public.mat_part(part_no);


--
-- Name: mat_plating_fee_staging mat_plating_fee_staging_import_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_plating_fee_staging
    ADD CONSTRAINT mat_plating_fee_staging_import_session_id_fkey FOREIGN KEY (import_session_id) REFERENCES public.import_session(id) ON DELETE CASCADE;


--
-- Name: mat_plating_plan_staging mat_plating_plan_staging_import_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_plating_plan_staging
    ADD CONSTRAINT mat_plating_plan_staging_import_session_id_fkey FOREIGN KEY (import_session_id) REFERENCES public.import_session(id) ON DELETE CASCADE;


--
-- Name: mat_process mat_process_customer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_process
    ADD CONSTRAINT mat_process_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customer(id);


--
-- Name: mat_process mat_process_hf_part_no_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_process
    ADD CONSTRAINT mat_process_hf_part_no_fkey FOREIGN KEY (hf_part_no) REFERENCES public.mat_part(part_no);


--
-- Name: mat_process_staging mat_process_staging_import_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mat_process_staging
    ADD CONSTRAINT mat_process_staging_import_session_id_fkey FOREIGN KEY (import_session_id) REFERENCES public.import_session(id) ON DELETE CASCADE;


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

ALTER TABLE ONLY public.plating_fee
    ADD CONSTRAINT plating_fee_hf_part_no_fkey FOREIGN KEY (hf_part_no) REFERENCES public.mat_part(part_no);


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

ALTER TABLE ONLY public.quotation_line_item
    ADD CONSTRAINT quotation_line_item_costing_summary_id_fkey FOREIGN KEY (costing_summary_id) REFERENCES public.costing_summary(id) ON DELETE SET NULL;


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
-- Data for Name: annual_discount; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.annual_discount (id, biz_type, material_no, discount_strategy, discount_base, discount_order, discount_ratio, fixed_discount_value, currency, unit, discount_times, created_at, updated_at, created_by, updated_by) FROM stdin;
\.


--
-- Data for Name: department; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.department (id, code, name, sort_order, status, created_at, parent_id) FROM stdin;
27bfb7f2-c462-4545-9406-be358f7056f7	SALES_DEPT_1	销售一部	1	ACTIVE	2026-04-14 03:00:23.7324+00	\N
54101f8b-519b-4b69-9547-9c0da7aed6d8	SALES_DEPT_2	销售二部	2	ACTIVE	2026-04-14 03:00:23.7324+00	\N
dcbfebe4-4bc2-4bd5-bde9-57a490cc3734	SALES_DEPT_3	销售三部	3	ACTIVE	2026-04-14 03:00:23.7324+00	\N
\.


--
-- Data for Name: region; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.region (id, code, name, sort_order, status, created_at) FROM stdin;
a1bda3b4-18ac-41c7-93ba-8bdc040b4f6c	CENTRAL_CHINA	华中	4	ACTIVE	2026-04-14 03:00:23.7324+00
291e4353-912f-4ee0-bb04-32ded311ea4f	EAST_CHINA	华东	2	ACTIVE	2026-04-14 03:00:23.7324+00
8a0ddd5f-bf41-4004-a755-acf1179cb0c5	NORTH_CHINA	华北	3	ACTIVE	2026-04-14 03:00:23.7324+00
2ad5b874-f011-4cd4-aabe-1065046c5524	SOUTH_CHINA	华南	1	ACTIVE	2026-04-14 03:00:23.7324+00
\.


--
-- Data for Name: user; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."user" (id, username, full_name, email, password_hash, role, region_id, department_id, status, is_first_login, initial_password_expires_at, failed_login_attempts, locked_until, created_at, updated_at) FROM stdin;
d1e1147c-a639-4156-aeac-9f938a65ad05	admin	系统管理员	admin@cpq-system.com	$2a$12$l4j0Nc./XgVkj/KEeHnmFOkv3fu1k0riBvS/Kz9Lt.EZvbk3KocQe	SYSTEM_ADMIN	\N	\N	ACTIVE	f	\N	0	\N	2026-04-14 03:00:23.7324+00	2026-07-15 09:37:15.636479+00
\.


--
-- Data for Name: approval_rule; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.approval_rule (id, rule_type, approver_id, match_field, match_value_id, priority, created_at) FROM stdin;
\.


--
-- Data for Name: auxiliary_energy; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.auxiliary_energy (id, material_no, material_name, specification, dimension, process_no, process_name, amortize_basis, working_hours, total_hours, non_production_energy_price, currency, unit, conversion_rate, calc_version, created_at, updated_at, created_by, updated_by, is_current, production_no, system_type, source) FROM stdin;
\.


--
-- Data for Name: basic_data_config; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.basic_data_config (id, sheet_name, sheet_index, header_row_index, data_start_row_index, description, parent_config_id, join_columns, sort_order, status, created_at, updated_at, target_table, target_discriminator, template_kind) FROM stdin;
c9651c50-3b65-4952-97bd-c548de4ea3c5	单重	13	1	2	\N	\N	[]	0	ACTIVE	2026-04-29 01:26:41.129219+00	2026-04-29 01:26:41.129219+00	mat_part	\N	BOTH
26760bbe-6235-460a-a4ca-b88ba2945248	元素BOM	3	1	2	\N	\N	[]	0	ACTIVE	2026-04-29 01:26:31.230007+00	2026-04-29 01:26:31.230007+00	mat_bom	{"bom_type": "ELEMENT"}	BOTH
480c784f-60da-4213-ab90-1f5384a4048d	来料年降	0	1	2	来料年降(CUSTOMER)	\N	[]	190	ACTIVE	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	mat_fee	{"fee_type": "INCOMING_ANNUAL_DOWN"}	BOTH
3830f5e3-b4cb-4fd0-8762-4d97a6a59535	组装加工费年降	0	1	2	组装加工费年降(CUSTOMER)	\N	[]	210	ACTIVE	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	mat_fee	{"fee_type": "ASSEMBLY_ANNUAL_DOWN"}	BOTH
7aabce5c-c9b2-41d1-9270-685651cf6854	年降系数	0	1	2	年降系数(CUSTOMER)	\N	[]	220	ACTIVE	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	mat_fee	{"fee_type": "ANNUAL_REDUCTION_FACTOR"}	BOTH
bd76770f-d5bb-46a9-9718-1bb632ea76da	料号主档	0	1	2	旧测试兼容:mat_part(GLOBAL)	\N	[]	310	ACTIVE	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	mat_part	\N	BOTH
ee8e437c-2cd2-4e1f-a58c-20bc4f7d1cb7	BOM清单	0	1	2	旧测试兼容:mat_bom(GLOBAL)	\N	[]	320	ACTIVE	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	mat_bom	\N	BOTH
bfda7921-615d-43dd-8347-ced1f7112731	组成件BOM	0	1	2	旧测试兼容:mat_process(CUSTOMER)	\N	[]	330	ACTIVE	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	mat_process	\N	BOTH
473853a9-cb30-42ca-bcef-822829f78992	费用清单	0	1	2	旧测试兼容:mat_fee(CUSTOMER)	\N	[]	340	ACTIVE	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	mat_fee	\N	BOTH
7344ec4a-f3fc-489e-b5bd-f5bbf96a141d	客户料号映射	0	1	2	旧测试兼容:mat_customer_part_mapping	\N	[]	350	ACTIVE	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	mat_customer_part_mapping	\N	BOTH
ddd191d3-e515-4371-be64-bbd9ecef990f	组成件BOM及单价	8	1	2	\N	\N	[]	0	ACTIVE	2026-04-29 01:26:34.192299+00	2026-04-29 01:26:34.192299+00	mat_process	\N	BOTH
89997608-0f41-49a9-99f9-6221fd35a875	来料固定加工费	4	1	2	\N	\N	[]	0	ACTIVE	2026-04-29 01:26:31.500036+00	2026-04-29 01:26:31.500036+00	mat_fee	{"fee_type": "INCOMING_FIXED"}	BOTH
3beb8382-5ec8-4116-861f-93477300cafc	组装加工费	9	1	2	\N	\N	[]	0	ACTIVE	2026-04-29 01:26:37.62856+00	2026-04-29 01:26:37.62856+00	mat_fee	{"fee_type": "ASSEMBLY_PROCESS"}	BOTH
5341c659-a256-432c-9019-d2007242d9d5	客户料号与宏丰料号的关系	1	1	2	\N	\N	[]	0	ACTIVE	2026-04-29 01:26:29.965439+00	2026-04-29 01:34:24.984586+00	mat_customer_part_mapping	\N	BOTH
1e67f052-bab0-4843-ae25-814e180759e7	成品固定加工费	6	1	2	\N	\N	[]	0	ACTIVE	2026-04-29 01:26:32.882975+00	2026-04-29 01:26:32.882975+00	mat_fee	{"fee_type": "FINISHED_FIXED"}	BOTH
81c9b0b6-67d3-4ae0-9b4c-3cc46b94d22c	来料其他费用	5	1	2	 [V94] 限定到核价导入入口 (template_kind=COSTING)	\N	[]	0	ACTIVE	2026-04-29 01:26:31.975636+00	2026-05-06 08:37:40.128301+00	mat_fee	{"fee_type": "INCOMING_OTHER"}	COSTING
de3240ab-f932-4f9d-8f52-4283b6d114a9	核价-元素价格(默认版本)	0	1	2	核价基础数据：元素价格（自动锁定到默认 PUBLISHED 版本）— 不带料号谓词，按 element_code 查	\N	[]	10	ACTIVE	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	v_costing_element_price	\N	COSTING
273f3019-0a83-4808-b2a2-9bf3d88262f7	核价-材料价格(默认版本)	0	1	2	核价基础数据：材料价格（自动锁定默认版本）— 按 material_no 查	\N	[]	11	ACTIVE	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	v_costing_material_price	\N	COSTING
a099b527-a5fd-41a6-bc2f-1cec035f8491	来料BOM	2	1	2	核价基础数据 4.0 版「来料BOM」sheet 导入映射 (V92 改路由到 costing_part_material_bom)。组成用量可为负数(边角料/回收)。	\N	[]	0	ACTIVE	2026-04-29 01:26:30.475791+00	2026-05-06 06:50:56.611077+00	costing_part_material_bom	\N	COSTING
cb737a06-7c69-4d8f-b7f0-73b510352ae3	成品其他费用	7	1	2	 [V94] 限定到核价导入入口 (template_kind=COSTING)	\N	[]	0	ACTIVE	2026-04-29 01:26:33.433111+00	2026-05-06 08:37:40.128301+00	mat_fee	{"fee_type": "FINISHED_OTHER"}	COSTING
c8fc519b-1f87-4ac8-b022-b94479bb1310	材料固定加工费	0	1	2	V118: 报价侧 sheet 别名「材料固定加工费」, 物理表 mat_fee + fee_type=INCOMING_FIXED, 等价于「来料固定加工费」	\N	[]	0	ACTIVE	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	mat_fee	{"fee_type": "INCOMING_FIXED"}	QUOTATION
ed6e3a65-42c1-440a-a380-79ef3eb3cb22	材料其他费用	0	1	2	V118: 报价侧 sheet 别名「材料其他费用」, 物理表 mat_fee + fee_type=INCOMING_OTHER, 等价于「来料其他费用」	\N	[]	402	ACTIVE	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	mat_fee	{"fee_type": "INCOMING_OTHER"}	QUOTATION
baff34e9-0179-49e4-b81c-6225e9b17f98	材料回收折扣	0	1	2	V118: 报价侧「材料回收折扣」, 物理表 mat_fee + fee_type=MATERIAL_RECYCLE; fee_ratio 字段存折扣百分比	\N	[]	100	ACTIVE	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	mat_fee	{"fee_type": "MATERIAL_RECYCLE"}	QUOTATION
6b04aeeb-84c2-42bf-a20b-42e86ba7d244	电镀方案	11	1	2	 [V94] 限定到核价导入入口 (template_kind=COSTING) [V125] target 切到 costing_part_plating	\N	[]	0	ACTIVE	2026-04-29 01:26:36.407644+00	2026-05-07 12:12:39.334863+00	costing_part_plating	\N	COSTING
e64f5ba2-fcaf-4e2b-a7c4-7aab96daa973	电镀费用	12	1	2	 [V125] kind=QUOTATION, target=mat_plating_fee	\N	[]	0	ACTIVE	2026-04-29 01:26:36.832818+00	2026-05-07 12:12:39.334863+00	mat_plating_fee	\N	QUOTATION
1171806d-f555-46df-8ed6-5d26271fbb0e	电镀费用	112	1	2	[V125] kind=COSTING, target=costing_part_plating_fee, 复制自 电镀费用	\N	[]	0	ACTIVE	2026-05-07 12:12:39.334863+00	2026-05-07 12:12:39.334863+00	costing_part_plating_fee	\N	COSTING
673f0dad-8c0a-4418-8617-96d9a4ae432f	核价-汇率(默认版本)	0	1	2	核价基础数据：汇率（自动锁定默认版本）— 按 from_currency + to_currency 查	\N	[]	12	ACTIVE	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	v_costing_exchange_rate	\N	COSTING
8abc1a92-2bc1-4b4d-b916-a04f42d36be7	成品其他费用	401	1	2	报价单基础数据 V3 版「成品其他费用」(V94 新建, template_kind=QUOTATION, 与核价版同名共存)。	\N	[]	401	ACTIVE	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	mat_fee	{"fee_type": "FINISHED_OTHER"}	QUOTATION
9a9932c7-3a1c-4c4d-bb62-b2d54c2a78d7	来料其他费用	402	1	2	报价单基础数据 V3 版「来料其他费用」(V94 新建, template_kind=QUOTATION, 与核价版同名共存)。	\N	[]	402	ACTIVE	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	mat_fee	{"fee_type": "INCOMING_OTHER"}	QUOTATION
cb7d212b-6bdb-4798-8a24-9fdee86b069b	核价-料号质量检验	0	1	2	核价料号级：质量检验（INCOMING/SEMI_FINISHED 鉴别）— 自动按 hf_part_no	\N	[]	24	ACTIVE	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	costing_part_quality_check	\N	COSTING
c44589f2-0c2a-40da-aad4-473f78c33c0b	来料BOM	404	1	2	报价单基础数据 V3 版「来料BOM」(V94 新建, template_kind=QUOTATION, target=mat_bom, 与核价版同名共存)。	\N	[]	404	ACTIVE	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	mat_bom	{"bom_type": "INCOMING"}	QUOTATION
6c1eebc1-bfb6-405f-93d6-7ae2ce3216e6	核价-料号设计成本	0	1	2	核价料号级：设计成本 — 自动按 hf_part_no	\N	[]	26	ACTIVE	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	costing_part_design_cost	\N	COSTING
094dedfc-758f-444c-9375-4d80d858a09a	来料回收折扣	0	1	2	V119: 报价侧「来料回收折扣」 — Excel 实际 sheet 名 (V118 误注册成「材料回收折扣」, 此处补正确名); 物理表 mat_fee + fee_type=MATERIAL_RECYCLE; fee_ratio 字段存折扣百分比	\N	[]	100	ACTIVE	2026-05-07 10:58:00.643135+00	2026-05-07 10:58:00.643135+00	mat_fee	{"fee_type": "MATERIAL_RECYCLE"}	QUOTATION
ce553849-1938-4084-ba5e-684b54852123	电镀方案	403	1	2	报价单基础数据 V3 版「电镀方案」(V94 新建, template_kind=QUOTATION, 与核价版同名共存)。 [V125] target 切到 mat_plating_plan	\N	[]	403	ACTIVE	2026-05-06 08:37:40.128301+00	2026-05-07 12:12:39.334863+00	mat_plating_plan	\N	QUOTATION
7fc4865d-d106-42e3-8712-a1da9d2efc40	核价汇总	200	1	2	核价汇总: 每料号 x summary 一行,9 个成本指标 PIVOT 横向。视图: v_costing_summary_full	\N	[]	200	ACTIVE	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	v_costing_summary_full	\N	COSTING
03727943-3c6f-48c0-89a3-75f85c712ece	核价-模具工装成本	0	1	2	核价料号级：模具工装成本 — 自动按当前产品料号注入 hf_part_no	\N	[]	21	ACTIVE	2026-05-05 11:45:27.027202+00	2026-05-06 01:19:24.591637+00	costing_part_tooling_cost	\N	COSTING
c7ceefeb-acf7-48f1-8452-f53a2f01116e	核价-工序成本(8类)	0	1	2	核价料号级：工序级单价 8 类（cost_type 鉴别）— 自动按当前产品料号注入 hf_part_no	\N	[]	20	ACTIVE	2026-05-05 11:45:27.027202+00	2026-05-06 01:19:24.591637+00	costing_part_process_cost	\N	COSTING
2d1cb78f-442b-421b-9c77-85c7df699d3d	核价-来料BOM	0	1	2	核价料号级：材料 BOM — 自动按当前产品料号注入 hf_part_no	\N	[]	22	ACTIVE	2026-05-05 11:45:27.027202+00	2026-05-06 01:19:24.591637+00	costing_part_material_bom	\N	COSTING
4432b3b5-fe3b-4c11-b1b8-078abafc95fa	核价-来料与元素BOM	0	1	2	核价料号级：元素 BOM（按 input_material_no 维度，**不会**自动注入 hf_part_no）	\N	[]	23	ACTIVE	2026-05-05 11:45:27.027202+00	2026-05-06 01:19:24.591637+00	costing_part_element_bom	\N	COSTING
15b60523-ca1a-42b5-912e-790918d1c87a	核价-单重	0	1	2	核价料号级：重量 g/pcs — 自动按 hf_part_no	\N	[]	27	ACTIVE	2026-05-05 11:45:27.027202+00	2026-05-06 01:19:24.591637+00	costing_part_weight	\N	COSTING
1d0b619f-b5db-4cef-9a34-625f7d17d493	核价-电镀方案	0	1	2	核价料号级：电镀（按 plating_no + version 维度，**不会**自动注入 hf_part_no）	\N	[]	25	ACTIVE	2026-05-05 11:45:27.027202+00	2026-05-06 01:19:24.591637+00	costing_part_plating	\N	COSTING
f21d41b6-af67-4642-bf54-c33169b9e9d9	人工成本（单价）	301	1	2	核价基础数据 4.0 版「人工成本（单价）」sheet 导入映射。物理表 costing_part_process_cost, 鉴别 cost_type=LABOR。	\N	[]	301	ACTIVE	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	costing_part_process_cost	{"cost_type": "LABOR"}	COSTING
5cbaaaed-a029-493f-b6de-4cfbf9a24393	设备折旧成本	302	1	2	核价基础数据 4.0 版「设备折旧成本」sheet 导入映射。物理表 costing_part_process_cost, 鉴别 cost_type=DEPRECIATION。	\N	[]	302	ACTIVE	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	costing_part_process_cost	{"cost_type": "DEPRECIATION"}	COSTING
3b32f861-bc33-431d-93fc-5c9a1e6cf149	生产设备能耗成本	303	1	2	核价基础数据 4.0 版「生产设备能耗成本」sheet 导入映射。物理表 costing_part_process_cost, 鉴别 cost_type=ENERGY_DEDICATED。	\N	[]	303	ACTIVE	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	costing_part_process_cost	{"cost_type": "ENERGY_DEDICATED"}	COSTING
02b300d8-7d3e-46b8-8171-16407c68c0ee	辅助设备能耗成本	304	1	2	核价基础数据 4.0 版「辅助设备能耗成本」sheet 导入映射。物理表 costing_part_process_cost, 鉴别 cost_type=ENERGY_SHARED。	\N	[]	304	ACTIVE	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	costing_part_process_cost	{"cost_type": "ENERGY_SHARED"}	COSTING
08602f0d-23a3-411b-8fe3-662981825bed	耗材与包装材料	305	1	2	核价基础数据 4.0 版「耗材与包装材料」sheet 导入映射。物理表 costing_part_process_cost, 鉴别 cost_type=CONSUMABLE。	\N	[]	305	ACTIVE	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	costing_part_process_cost	{"cost_type": "CONSUMABLE"}	COSTING
cce210eb-335c-4584-994b-7b3229aae619	元素回收折扣	405	1	2	V134: 报价侧「元素回收折扣」, 物理表 mat_fee + fee_type=ELEMENT_RECYCLE; fee_ratio 字段存回收折扣百分比（toDecimalPercent 入库，视图已 x100 显示）	\N	[]	105	ACTIVE	2026-05-08 09:07:54.602727+00	2026-05-08 09:07:54.602727+00	mat_fee	{"fee_type": "ELEMENT_RECYCLE"}	QUOTATION
2acc46db-efad-46f4-ae69-59ddb2105a9a	组成件其他费用	406	1	2	V139: 2.0 版 Excel「组成件其他费用」sheet, 物理表 mat_fee + fee_type=COMPONENT_OTHER; 存储工序级组件费用项(包装费/运费/单价/加工费等)	\N	[]	106	ACTIVE	2026-05-09 05:03:31.312193+00	2026-05-09 05:03:31.312193+00	mat_fee	{"fee_type": "COMPONENT_OTHER"}	QUOTATION
eecfa1a5-f903-41fb-8044-03f15f3acf2a	来料与元素BOM	501	1	2	V143: 5.0 版核价「来料与元素BOM」sheet, 物理表 costing_part_element_bom; 列A=input_material_no(业务键)	\N	[]	501	ACTIVE	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	costing_part_element_bom	\N	COSTING
4a745dcf-7a2b-42de-9929-cb0eda525b2d	模具工装成本	502	1	2	V143: 5.0 版核价「模具工装成本」sheet, 物理表 costing_part_tooling_cost; G=seq_no, H=tooling_no(模具台账), J=tooling_unit_cost, K=process_count(寿命), L=cycle_count(单循环产量), M=unit_price	\N	[]	502	ACTIVE	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	costing_part_tooling_cost	\N	COSTING
5f6e5f76-8b48-4aac-b477-61e1139253a2	生产耗材	503	1	2	V143: 5.0 版核价「生产耗材」sheet, 物理表 costing_part_process_cost[CONSUMABLE]; V142 视图按 process_name NOT LIKE '%包装%' 过滤	\N	[]	503	ACTIVE	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	costing_part_process_cost	{"cost_type": "CONSUMABLE"}	COSTING
b35e5d9b-b226-454c-a863-fe6b6c367972	包装材料	504	1	2	V143: 5.0 版核价「包装材料」sheet, 物理表 costing_part_process_cost[CONSUMABLE]; 需工序名称(F列)含"包装"字样供 V142 视图 v_c_packaging_merged 过滤	\N	[]	504	ACTIVE	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	costing_part_process_cost	{"cost_type": "CONSUMABLE"}	COSTING
ded337e3-9262-4bc3-9bc9-01eb8f5e8107	来料加工费	505	1	2	V143: 5.0 版核价「来料加工费」sheet, 物理表 costing_part_process_cost[MATERIAL_PROC]; B=项次 → process_no(业务区分键)	\N	[]	505	ACTIVE	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	costing_part_process_cost	{"cost_type": "MATERIAL_PROC"}	COSTING
18b0f7e0-3eba-45c2-86d2-30b33c5818f1	来料其他固定费用	506	1	2	V143: 5.0 版核价「来料其他固定费用」sheet, 物理表 mat_fee[INCOMING_FIXED]; B=seq_no(一级), G=dim_sub_seq_no(二级), H=dim_element_name, I=fee_value	\N	[]	506	ACTIVE	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	mat_fee	{"fee_type": "INCOMING_FIXED"}	COSTING
0227c720-a6fe-498c-bfeb-b80970bbbce2	成品加工费&组装费	507	1	2	V143: 5.0 版核价「成品加工费&组装费」sheet, 物理表 costing_part_process_cost[SEMI_FINISHED_PROC]; J=不良率拒收率(%) → notes 文本存储	\N	[]	507	ACTIVE	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	costing_part_process_cost	{"cost_type": "SEMI_FINISHED_PROC"}	COSTING
19f2bc23-ca5e-4297-a85a-283c4e4fcb6e	成品其他比例费用	508	1	2	V143: 5.0 版核价「成品其他比例费用」sheet, 物理表 mat_fee[FINISHED_OTHER]; E=seq_no, G=dim_element_name(必填, V131防御), H=fee_ratio(toDecimalPercent入库)	\N	[]	508	ACTIVE	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	mat_fee	{"fee_type": "FINISHED_OTHER"}	COSTING
1b0bda96-e8ca-4611-9072-6f76d2874fdf	成品其他固定费用	509	1	2	V143: 5.0 版核价「成品其他固定费用」sheet, 物理表 mat_fee[FINISHED_FIXED]; E=seq_no, F=dim_element_name, G=fee_value	\N	[]	509	ACTIVE	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	mat_fee	{"fee_type": "FINISHED_FIXED"}	COSTING
404aec01-831c-4937-b7ed-f6fc78de93bd	电镀成本	510	1	2	V143: 5.0 版核价「电镀成本」sheet, 物理表 costing_part_plating_fee; H=defect_rate toDecimalPercent入库	\N	[]	510	ACTIVE	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	costing_part_plating_fee	\N	COSTING
74e02284-08b3-42df-9193-50f0c382b331	其他外加工成本	511	1	2	V143: 5.0 版核价「其他外加工成本」sheet, 物理表 costing_part_process_cost[POST_PROC]; B=process_no, D=unit_price(外加工费用)	\N	[]	511	ACTIVE	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	costing_part_process_cost	{"cost_type": "POST_PROC"}	COSTING
dc819072-9e21-4e95-bb06-ea55ac0c61d1	人工成本(单价)	521	1	2	V143: 5.0 版核价「人工成本(单价)」(半角括号), 物理表 costing_part_process_cost[LABOR]; 与 V89「人工成本（单价）」(全角)并存共两份配置	\N	[]	521	ACTIVE	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	costing_part_process_cost	{"cost_type": "LABOR"}	COSTING
311825c4-bc43-44d3-902c-6d95406f51e0	选配-材质字典	0	1	2	材质字典(spec V_NN 实施)— material_recipe	\N	[]	310	ACTIVE	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	material_recipe	\N	QUOTATION
90390eb2-f863-4d8f-b12f-8b7ab3a45537	选配-材质元素含量	0	1	2	材质元素含量(spec V_NN 实施)— material_recipe_element	\N	[]	311	ACTIVE	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	material_recipe_element	\N	QUOTATION
aa232879-b6cd-4cc3-ad6e-d395df9ccc44	选配-组合工艺字典	0	1	2	组合工艺字典(spec V_NN 实施)— composite_process_def	\N	[]	320	ACTIVE	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	composite_process_def	\N	QUOTATION
fd3e3895-28c8-46f0-b4e8-dffd1183ef8f	选配-组合工艺实例	0	1	2	组合工艺实例(spec V_NN 实施)— mat_composite_process	\N	[]	321	ACTIVE	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	mat_composite_process	\N	QUOTATION
1ca5b254-d86f-4698-8504-d5f9646ecd44	选配-装配 BOM	0	1	2	组合产品父子关系(mat_bom + bom_type=ASSEMBLY)	\N	[]	322	ACTIVE	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	mat_bom	{"bom_type": "ASSEMBLY"}	QUOTATION
cbd60f9d-7ba8-4c7b-ac80-82d769172b15	选配-工序默认单价	0	1	2	工序默认单价(spec V_NN 实施)— process_default_cost	\N	[]	330	ACTIVE	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	process_default_cost	\N	BOTH
7473c0d9-35c3-40f2-90dd-c26737eb0d08	罗克韦尔-成本汇总	0	1	2	V175: 罗克韦尔客户专属 5 项成本聚合视图 (按 hf_part_no 单行)	\N	[]	400	ACTIVE	2026-05-14 05:17:02.209332+00	2026-05-14 05:17:02.209332+00	v_q_rockwell_costs	\N	QUOTATION
8a1ce500-5181-4001-9001-100000000001	西门子-一类料号成本汇总	0	0	1	\N	\N	[]	410	ACTIVE	2026-05-15 08:15:33.302266+00	2026-05-15 08:15:33.302266+00	v_q_siemens_class1_costs	\N	QUOTATION
209dde9a-d8c5-4e6c-b2eb-02fa0d87aeae	选配-料号材质	0	1	2	料号绑定的材质字典(v_part_material_recipe 视图). 暴露 hf_part_no 让组件路径能按料号收窄.	\N	[]	90	ACTIVE	2026-05-17 03:19:59.793876+00	2026-05-17 03:19:59.793876+00	v_part_material_recipe	\N	QUOTATION
cbbe11ce-2b6a-4081-9b8a-7908b3a7b1b4	选配组合-子件元素	0	1	2	选配组合产品父级 hfPartNo 聚合所有子件元素行	\N	[]	0	ACTIVE	2026-05-18 08:47:00.181124+00	2026-05-18 08:47:00.181124+00	v_composite_child_elements	\N	QUOTATION
f82c2b7d-e8b5-4185-a213-16bf625a3708	选配组合-子件工序	0	1	2	选配组合产品父级 hfPartNo 聚合所有子件 mat_process 工序行	\N	[]	0	ACTIVE	2026-05-18 08:47:00.181124+00	2026-05-18 08:47:00.181124+00	v_composite_child_processes	\N	QUOTATION
477d1a8b-b95c-43be-9b0a-25cf546837a7	选配组合-子件材质	0	1	2	选配组合产品父级 hfPartNo 聚合所有子件材质	\N	[]	0	ACTIVE	2026-05-18 08:47:00.181124+00	2026-05-18 08:47:00.181124+00	v_composite_child_materials	\N	QUOTATION
f1a726c9-7295-47bc-8c84-d5d976d01989	选配组合-子件单重	0	1	2	选配组合产品父级 hfPartNo 聚合所有子件单重	\N	[]	0	ACTIVE	2026-05-18 08:47:00.181124+00	2026-05-18 08:47:00.181124+00	v_composite_child_weights	\N	QUOTATION
\.


--
-- Data for Name: basic_data_attribute; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.basic_data_attribute (id, config_id, column_letter, column_title, variable_code, variable_label, data_type, status, sort_order, created_at, updated_at, importance_level, affects_calculation, is_required) FROM stdin;
009bbdc2-7d8b-47ef-b495-fd3fb512072e	e64f5ba2-fcaf-4e2b-a7c4-7aab96daa973	H	不良率	defect_rate	不良率	VALUE	ACTIVE	80	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
0120fd0a-9acf-404f-a813-0f104bf9e8cc	ddd191d3-e515-4371-be64-bbd9ecef990f	H	供应商编号	supplier_code	供应商编号	VALUE	ACTIVE	80	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
012758a8-a98a-4f29-9a29-a0fc2ea5a7be	3b32f861-bc33-431d-93fc-5c9a1e6cf149	E	工序编号	process_no	工序编号	IDENTIFIER	ACTIVE	5	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	IMPORTANT	t	t
039a55e0-80e3-4216-9137-4f03d61e9957	2d1cb78f-442b-421b-9c77-85c7df699d3d	M	是否有效	is_active	是否有效 (无 Excel 列, DB 字段)	VALUE	ACTIVE	13	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
03daca00-c2e3-4dc4-8fd2-71fd431ecf82	b35e5d9b-b226-454c-a863-fe6b6c367972	F	工序名称	process_name	工序名称	VALUE	ACTIVE	6	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
04768dcd-2c93-4a10-8a38-9e98be8437fb	c7ceefeb-acf7-48f1-8452-f53a2f01116e	H	取用的计算版本	ref_calc_version	取用的计算版本 · Excel J	VALUE	ACTIVE	8	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
048f8412-ba85-4c62-85fa-5b44d96d81f2	1e67f052-bab0-4843-ae25-814e180759e7	C	值	fee_value	值	VALUE	ACTIVE	30	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
052c613b-3a13-48fe-9718-056ab22b2479	ed6e3a65-42c1-440a-a380-79ef3eb3cb22	G	值	fee_value	值	VALUE	ACTIVE	7	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
0592573d-e45b-4565-9116-7864e9ec892d	ce553849-1938-4084-ba5e-684b54852123	B	版本	version	版本	IDENTIFIER	ACTIVE	2	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	CRITICAL	t	t
05ca2ba0-725a-40d4-98bd-888b84592a2f	3b32f861-bc33-431d-93fc-5c9a1e6cf149	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	IMPORTANT	t	t
05d0f228-8954-410c-a226-90b3417485df	aa232879-b6cd-4cc3-ad6e-d395df9ccc44	D	参数 schema	param_schema	参数schema	VALUE	ACTIVE	40	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	NORMAL	f	f
065cfe39-13d0-47b0-8cfa-b08b1c1555ec	9a9932c7-3a1c-4c4d-bb62-b2d54c2a78d7	B	项次	seq_no	项次	IDENTIFIER	ACTIVE	2	2026-05-06 08:37:40.128301+00	2026-05-15 08:39:18.899763+00	CRITICAL	t	f
06a013c7-ac4c-49c9-b77a-1219e3ae68a8	de3240ab-f932-4f9d-8f52-4283b6d114a9	C	市场参考价	market_ref_price	市场参考价	VALUE	ACTIVE	3	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	f	f
06e9f2d1-e9d0-4828-a66f-6f8431dbab5f	15b60523-ca1a-42b5-912e-790918d1c87a	A	宏丰料号	hf_part_no	宏丰料号 · Excel A	IDENTIFIER	ACTIVE	1	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
07ff8f28-5a09-4f73-8095-44dd48584f0c	b35e5d9b-b226-454c-a863-fe6b6c367972	H	币种	currency	币种	VALUE	ACTIVE	8	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
095616d7-4d8c-4271-8db7-9ec7bfb75e26	eecfa1a5-f903-41fb-8044-03f15f3acf2a	H	损耗率(%)	loss_rate	损耗率(%)	VALUE	ACTIVE	8	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
09e3d1f6-3fcc-4403-98d1-d693bce26e44	7aabce5c-c9b2-41d1-9270-685651cf6854	C	年降系数(%/年)	fee_ratio	年降系数(%/年)	VALUE	ACTIVE	30	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
0a1f2a64-c3ae-4e8d-99de-14ee09a1a3c0	7473c0d9-35c3-40f2-90dd-c26737eb0d08	B	材料损耗成本	material_loss_cost	材料损耗成本	VALUE	ACTIVE	20	2026-05-14 05:17:02.209332+00	2026-05-14 05:17:02.209332+00	CRITICAL	t	f
0b82d9d8-0f4b-4b46-a520-469e6df2fc2f	eecfa1a5-f903-41fb-8044-03f15f3acf2a	G	组成含量(%)	composition_pct	组成含量(%)	VALUE	ACTIVE	7	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	t	f
0d26685e-615c-47b6-afc9-053c976b76e5	03727943-3c6f-48c0-89a3-75f85c712ece	B	工序编号	process_no	工序编号 · Excel E	IDENTIFIER	ACTIVE	2	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
0d59d354-e66a-4487-bf4e-74217282b0a4	03727943-3c6f-48c0-89a3-75f85c712ece	F	单个模具/工装成本	tooling_unit_cost	单个模具/工装成本 · Excel I	VALUE	ACTIVE	6	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
0dc4f52d-3b37-4afd-9717-bddf17d9f78b	6c1eebc1-bfb6-405f-93d6-7ae2ce3216e6	B	设计图编号	design_drawing_no	设计图编号	IDENTIFIER	ACTIVE	2	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
0deb7fc9-56fd-4ce1-8ff6-39a2a6c6e36e	ce553849-1938-4084-ba5e-684b54852123	H	电镀面积	plating_area	电镀面积	VALUE	ACTIVE	8	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	IMPORTANT	t	f
0f95e9c6-7f56-47f2-9bcf-9ff76603654f	5cbaaaed-a029-493f-b6de-4cfbf9a24393	K	是否有效	is_active	是否有效	VALUE	ACTIVE	11	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
0f9df36f-5d35-4f52-9fb0-44bc60c25587	6b04aeeb-84c2-42bf-a20b-42e86ba7d244	G	电镀要求	plating_requirement	电镀要求	VALUE	ACTIVE	7	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	NORMAL	f	f
102dad3d-d823-4507-b609-6dbff94ed3af	dc819072-9e21-4e95-bb06-ea55ac0c61d1	H	币种	currency	币种	VALUE	ACTIVE	8	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
10f995a7-7230-48ba-94ca-5e05a994ed86	1171806d-f555-46df-8ed6-5d26271fbb0e	F	货币	currency__costing	货币	VALUE	ACTIVE	60	2026-05-07 12:12:39.334863+00	2026-05-07 12:12:39.334863+00	NORMAL	f	f
118f5a47-a5dc-45eb-9218-b8d5538f2a26	aa232879-b6cd-4cc3-ad6e-d395df9ccc44	A	工艺代码	code	工艺代码	IDENTIFIER	ACTIVE	10	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	CRITICAL	f	t
11cc81e4-0de9-4c40-999e-a584c00d0c71	5f6e5f76-8b48-4aac-b477-61e1139253a2	F	工序名称	process_name	工序名称	VALUE	ACTIVE	6	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
11d516cb-c631-4456-ad57-c4b72137aae7	5f6e5f76-8b48-4aac-b477-61e1139253a2	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
1224b2c8-f1c1-47ce-903c-b138211e14ec	673f0dad-8c0a-4418-8617-96d9a4ae432f	B	核价货币	to_currency	核价货币	IDENTIFIER	ACTIVE	2	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	f	f
124a7b3a-cefd-43c1-9238-1de3354d7f0b	dc819072-9e21-4e95-bb06-ea55ac0c61d1	E	工序编号	process_no	工序编号	IDENTIFIER	ACTIVE	5	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
12ae1506-df38-4841-b626-e1168a01c436	08602f0d-23a3-411b-8fe3-662981825bed	I	计量单位	unit	计量单位	VALUE	ACTIVE	9	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
144853e0-e0ac-4b6b-a7fa-5c2ddaa8023b	26760bbe-6235-460a-a4ca-b88ba2945248	K	净用量单位	net_unit	净用量单位	VALUE	ACTIVE	110	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
15256b4f-d7aa-405d-842e-ade9024c7d08	7fc4865d-d106-42e3-8712-a1da9d2efc40	C	规格	specification	规格	VALUE	ACTIVE	3	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
15ce6ad1-1444-402a-a9c9-70f9cb137b4f	eecfa1a5-f903-41fb-8044-03f15f3acf2a	F	元素代码	element_code	元素代码	IDENTIFIER	ACTIVE	6	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
1642a7f0-9245-4c25-a0c4-6651c6224c6c	bfda7921-615d-43dd-8347-ced1f7112731	F	组成件料号	component_part_no	组成件料号	VALUE	ACTIVE	6	2026-05-09 03:16:23.645395+00	2026-05-09 03:16:23.645395+00	NORMAL	f	f
1678c190-28cd-4417-98a2-df58134aaff1	a099b527-a5fd-41a6-bc2f-1cec035f8491	K	底数	output_qty	底数(产出)	VALUE	ACTIVE	11	2026-05-06 06:50:56.611077+00	2026-05-06 06:50:56.611077+00	IMPORTANT	t	f
17199678-4a31-41d8-975a-9743bee49304	7fc4865d-d106-42e3-8712-a1da9d2efc40	D	尺寸	size_info	尺寸	VALUE	ACTIVE	4	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
172fd3f4-14b7-40d4-bbe8-fd4022e9d961	4a745dcf-7a2b-42de-9929-cb0eda525b2d	J	单个模具或工装成本	tooling_unit_cost	单套模具成本	VALUE	ACTIVE	10	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	t	f
178d6f8d-5372-4c87-8cf5-04c3e5cda1d0	ded337e3-9262-4bc3-9bc9-01eb8f5e8107	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
1800f958-4ce8-4046-9297-051a1819e199	81c9b0b6-67d3-4ae0-9b4c-3cc46b94d22c	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	CRITICAL	t	t
185f7516-d823-4be5-9229-0d541498c08a	6c1eebc1-bfb6-405f-93d6-7ae2ce3216e6	C	版本	version_number	版本	IDENTIFIER	ACTIVE	3	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
199437fe-ff3f-484f-914f-93a8767400bd	8a1ce500-5181-4001-9001-100000000001	I	外购件成本	purchase_cost	外购件成本	VALUE	ACTIVE	90	2026-05-15 08:15:33.302266+00	2026-05-15 08:15:33.302266+00	NORMAL	t	f
1a0121d9-39bc-4288-8a96-dc0fb7fca89e	9a9932c7-3a1c-4c4d-bb62-b2d54c2a78d7	I	货币	currency	货币	VALUE	ACTIVE	9	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	NORMAL	f	f
1ac2d25f-edc6-4acb-bc7f-bb1c0a2cf321	03727943-3c6f-48c0-89a3-75f85c712ece	K	计量单位	unit	计量单位 · Excel N	VALUE	ACTIVE	11	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
1b5249c0-0b66-46c5-b815-c66cbc488f16	8a1ce500-5181-4001-9001-100000000001	J	管理费	mgmt_fee	管理费	VALUE	ACTIVE	100	2026-05-15 08:15:33.302266+00	2026-05-15 08:15:33.302266+00	NORMAL	t	f
1c46acf5-e75f-4d0a-8596-71500db2c5d4	b35e5d9b-b226-454c-a863-fe6b6c367972	I	计量单位	unit	计量单位	VALUE	ACTIVE	9	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
1c4fff92-c7ab-441b-9378-4f514be7e799	5341c659-a256-432c-9019-d2007242d9d5	E	付款方式	payment_method	付款方式	VALUE	ACTIVE	50	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
1c73740d-fd1f-44a2-8fe5-cbb145c4601f	2acc46db-efad-46f4-ae69-59ddb2105a9a	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-09 05:03:31.312193+00	2026-05-09 05:03:31.312193+00	CRITICAL	f	t
1cdd8b16-b28b-4940-9b7e-64d3e39f701d	1d0b619f-b5db-4cef-9a34-625f7d17d493	C	项次	seq_no	项次 · 电镀方案 Excel C	VALUE	ACTIVE	3	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
1dc3315f-239b-47e1-aba6-84c8cdd8e07f	ddd191d3-e515-4371-be64-bbd9ecef990f	O	计价单位	price_unit	计价单位	VALUE	ACTIVE	150	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
1e164724-51f8-4477-8d28-b9451cc93e3c	ce553849-1938-4084-ba5e-684b54852123	I	镀层厚度	coating_thickness	镀层厚度	VALUE	ACTIVE	9	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	CRITICAL	t	t
1e5dcb51-3e31-4a25-92c6-ecb8b2f73023	2d1cb78f-442b-421b-9c77-85c7df699d3d	E	工序名称	process_name	工序名称 · Excel H	VALUE	ACTIVE	5	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
1f045914-8caa-4793-9ead-b3168d24002c	1e67f052-bab0-4843-ae25-814e180759e7	D	比例(%)	fee_ratio	比例(%)	VALUE	ACTIVE	40	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
1fbb5350-40a9-480a-b31c-167e92bd2021	480c784f-60da-4213-ab90-1f5384a4048d	D	投入料号名称	dim_input_material_name	投入料号名称	VALUE	ACTIVE	40	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
205b0d6c-f6b5-4dae-a6b6-dfdb496c980c	6b04aeeb-84c2-42bf-a20b-42e86ba7d244	C	项次	seq_no	项次	IDENTIFIER	ACTIVE	3	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	CRITICAL	t	t
2088f5bd-ef8b-4ced-ae91-7dc1f07b9a02	7aabce5c-c9b2-41d1-9270-685651cf6854	B	年降顺序	dim_sub_seq_no	年降顺序	VALUE	ACTIVE	20	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
2144b13e-beb0-4533-aea0-92499fbdfefe	cb7d212b-6bdb-4798-8a24-9fdee86b069b	C	一级序号	primary_seq_no	一级序号	VALUE	ACTIVE	3	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
22558c2b-5afc-4f10-8878-7b1d40744b8b	311825c4-bc43-44d3-902c-6d95406f51e0	A	材质代码	code	材质代码	IDENTIFIER	ACTIVE	10	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	CRITICAL	f	t
235f9de8-17cb-46a2-a679-601296a7d17d	e64f5ba2-fcaf-4e2b-a7c4-7aab96daa973	G	价格单位	price_unit	价格单位	VALUE	ACTIVE	70	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
24b0b5fb-0399-4b48-b0d2-d536c5d44e28	b35e5d9b-b226-454c-a863-fe6b6c367972	G	包装成本单价	unit_price	包装成本单价	VALUE	ACTIVE	7	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	t	t
24fc24aa-0d9d-469a-86bd-31f37b929b60	74e02284-08b3-42df-9193-50f0c382b331	B	工序编号	process_no	工序编号	IDENTIFIER	ACTIVE	2	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
2517b418-eef7-4c91-91c8-26232c686f44	18b0f7e0-3eba-45c2-86d2-30b33c5818f1	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
25769b8b-c9ce-49b4-a506-0001f46e2df2	90390eb2-f863-4d8f-b12f-8b7ab3a45537	A	元素代码	element_code	元素代码	IDENTIFIER	ACTIVE	10	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	CRITICAL	f	t
259e5705-02bb-4b2b-bc7c-6cfc8f69ea54	81c9b0b6-67d3-4ae0-9b4c-3cc46b94d22c	I	要素名称	dim_element_name	要素名称	VALUE	ACTIVE	9	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	IMPORTANT	t	t
262aeb8a-d1e7-475a-a85e-b25a205277fe	f21d41b6-af67-4642-bf54-c33169b9e9d9	F	工序名称	process_name	工序名称	VALUE	ACTIVE	6	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
26a01aac-d4ac-4825-803a-f690d2b15d4b	bd76770f-d5bb-46a9-9718-1bb632ea76da	B	料号名称	part_name	料号名称	VALUE	ACTIVE	20	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
281988f2-e1df-4358-8284-48441bd5d9c5	5341c659-a256-432c-9019-d2007242d9d5	C	客户图号	customer_drawing_no	客户图号	VALUE	ACTIVE	30	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
283f650c-843b-4115-96eb-5b99cf68c527	ded337e3-9262-4bc3-9bc9-01eb8f5e8107	G	加工费	unit_price	加工费	VALUE	ACTIVE	7	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	t	f
28b2a894-2fbc-4b8a-bf84-b6d55b239fa3	c7ceefeb-acf7-48f1-8452-f53a2f01116e	D	成本类型	cost_type	成本类型 (内部 discriminator,无 Excel 列)	IDENTIFIER	ACTIVE	4	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	CRITICAL	t	t
28b8ab98-30c3-445a-9e79-c2078dfb1fd3	5f6e5f76-8b48-4aac-b477-61e1139253a2	H	币种	currency	币种	VALUE	ACTIVE	8	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
28e5ceec-657e-4f18-b76d-8d5551160666	a099b527-a5fd-41a6-bc2f-1cec035f8491	C	来料料号	input_material_no	来料料号	IDENTIFIER	ACTIVE	3	2026-05-06 06:50:56.611077+00	2026-05-06 06:50:56.611077+00	IMPORTANT	t	f
28ec1cf3-4a4c-422c-a30c-a601a9cf71a5	26760bbe-6235-460a-a4ca-b88ba2945248	B	投入料号	input_material_no	投入料号	VALUE	ACTIVE	20	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
29b9d0b4-98b2-435a-91a4-ddc250e048e0	c44589f2-0c2a-40da-aad4-473f78c33c0b	J	不良率(%)	defect_rate	不良率(%)	VALUE	ACTIVE	10	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	NORMAL	f	f
29ca5ee1-fb14-4d2a-ae78-bb6402e4ff78	89997608-0f41-49a9-99f9-6221fd35a875	C	投入料号	dim_input_material_no	投入料号	VALUE	ACTIVE	30	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
2aba0c15-2d51-4b33-85e2-94010bceeeed	3830f5e3-b4cb-4fd0-8762-4d97a6a59535	F	单次固定年降值	fee_value	单次固定年降值	VALUE	ACTIVE	60	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
2ad86367-4678-4c67-b67b-80be2e9c92b7	5f6e5f76-8b48-4aac-b477-61e1139253a2	K	是否有效	is_active	是否有效	VALUE	ACTIVE	11	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
2be2502c-7395-4eb3-9608-ab2cf1bd3d0d	ddd191d3-e515-4371-be64-bbd9ecef990f	K	组成单位	quantity_unit	组成单位	VALUE	ACTIVE	110	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
2bea8afa-9d9f-4990-8f9c-1086ffdf0433	c44589f2-0c2a-40da-aad4-473f78c33c0b	C	投入料号	input_material_no	投入料号	IDENTIFIER	ACTIVE	3	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	IMPORTANT	t	f
2c43e890-62c7-4026-8bda-9a672d45fdde	4a745dcf-7a2b-42de-9929-cb0eda525b2d	L	单循环产量	cycle_count	单循环产量	VALUE	ACTIVE	12	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
2c6d5656-06d7-40f2-9d80-5d99f94a6063	c44589f2-0c2a-40da-aad4-473f78c33c0b	F	材料毛重	gross_qty	材料毛重	VALUE	ACTIVE	6	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	IMPORTANT	t	f
2cbadd64-b385-4dc6-a0a4-9698a5c7ea6d	2acc46db-efad-46f4-ae69-59ddb2105a9a	G	组成件名称	dim_input_material_name	组成件名称	VALUE	ACTIVE	6	2026-05-09 05:03:31.312193+00	2026-05-09 05:03:31.312193+00	NORMAL	f	f
2cbbe37b-3708-4fee-9d0d-3c6a408df9f9	08602f0d-23a3-411b-8fe3-662981825bed	K	是否有效	is_active	是否有效	VALUE	ACTIVE	11	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
2e8e8ab0-9806-4ccc-bce0-23049dcdad71	c8fc519b-1f87-4ac8-b022-b94479bb1310	M	涨幅单位	rise_unit	涨幅单位	VALUE	ACTIVE	130	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
2ea87e85-d348-4c7e-94ce-b259b5f1b02b	3b32f861-bc33-431d-93fc-5c9a1e6cf149	J	取用的计算版本	ref_calc_version	取用的计算版本	VALUE	ACTIVE	10	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
2f444787-31d4-46ea-ac76-e59ad4977409	480c784f-60da-4213-ab90-1f5384a4048d	C	投入料号	dim_input_material_no	投入料号	VALUE	ACTIVE	30	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
2ff066f2-e82b-4d51-b57d-422b6909351e	7473c0d9-35c3-40f2-90dd-c26737eb0d08	A	材料成本	material_cost	材料成本	VALUE	ACTIVE	10	2026-05-14 05:17:02.209332+00	2026-05-14 05:17:02.209332+00	CRITICAL	t	f
30850498-ea82-40d6-a835-8bacc74422d1	7fc4865d-d106-42e3-8712-a1da9d2efc40	M	材料损耗成本	material_loss_cost	材料损耗成本	VALUE	ACTIVE	13	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
30aa8c0c-200b-460e-99de-2c9d3cb5d249	2d1cb78f-442b-421b-9c77-85c7df699d3d	C	来料料号	input_material_no	来料料号 · Excel C	IDENTIFIER	ACTIVE	3	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
3193eda0-5d82-4944-8682-137231e9b89d	c8fc519b-1f87-4ac8-b022-b94479bb1310	I	是否随材料价格波动	price_floating	是否随材料价格波动	VALUE	ACTIVE	90	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
31b8acdf-ebdd-4229-9cf9-649ec2d7b14a	dc819072-9e21-4e95-bb06-ea55ac0c61d1	J	取用的计算版本	ref_calc_version	取用的计算版本	VALUE	ACTIVE	10	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
31fcef11-d238-41bf-b83a-51afc49ed842	8a1ce500-5181-4001-9001-100000000001	C	回收成本	recycle_cost	回收成本	VALUE	ACTIVE	30	2026-05-15 08:15:33.302266+00	2026-05-15 08:15:33.302266+00	NORMAL	t	f
31fd5d22-9a5f-4b65-9f34-cec5054f41fb	bd76770f-d5bb-46a9-9718-1bb632ea76da	D	尺寸信息	size_info	尺寸信息	VALUE	ACTIVE	40	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
325fca7b-ceae-4f8c-b84b-03ddcc3cf7ee	6b04aeeb-84c2-42bf-a20b-42e86ba7d244	E	电镀面积	plating_area	电镀面积(cm²)	VALUE	ACTIVE	5	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	IMPORTANT	t	f
32d2f04e-c50d-4834-ae50-eecf51c258f9	ed6e3a65-42c1-440a-a380-79ef3eb3cb22	D	投入料号名称	dim_input_material_name	投入料号名称	VALUE	ACTIVE	4	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
33365d68-db32-4426-840e-3f7e3ad088f4	2d1cb78f-442b-421b-9c77-85c7df699d3d	G	组成用量单位	input_unit	组成用量单位 · Excel J	VALUE	ACTIVE	7	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
3473bfdd-8563-46ba-b592-4295a5506c56	3830f5e3-b4cb-4fd0-8762-4d97a6a59535	E	年降系数(%)	fee_ratio	年降系数(%)	VALUE	ACTIVE	50	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
3482cbcf-56ff-4bbd-871f-0ea80e75f117	7fc4865d-d106-42e3-8712-a1da9d2efc40	Q	利润	profit	利润	VALUE	ACTIVE	17	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
3493c644-9479-43e1-9c43-9160effaf0df	ddd191d3-e515-4371-be64-bbd9ecef990f	F	组成件料号	component_part_no	组成件料号	VALUE	ACTIVE	60	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
353b3056-5e2e-4903-b3bc-f9b3f030da79	2acc46db-efad-46f4-ae69-59ddb2105a9a	D	组装工序	dim_assembly_process	组装工序	IDENTIFIER	ACTIVE	3	2026-05-09 05:03:31.312193+00	2026-05-09 05:03:31.312193+00	IMPORTANT	f	f
35d3e003-8323-4ab4-9512-f4974073b7e1	7aabce5c-c9b2-41d1-9270-685651cf6854	F	计价单位	price_unit	计价单位	VALUE	ACTIVE	60	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
367c91eb-ecab-490b-a210-ad5e8a265695	2d1cb78f-442b-421b-9c77-85c7df699d3d	L	不良率（%）	loss_rate	不良率（%） · Excel O	VALUE	ACTIVE	12	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
37f2343c-5733-4c9b-98c5-daf4be2e26ac	480c784f-60da-4213-ab90-1f5384a4048d	F	年降系数(%)	fee_ratio	年降系数(%)	VALUE	ACTIVE	60	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
39551e8d-851f-4c79-9370-86821dd5b310	b35e5d9b-b226-454c-a863-fe6b6c367972	K	是否有效	is_active	是否有效	VALUE	ACTIVE	11	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
395bd2bc-aa56-43e6-b977-bde8ea7463a3	273f3019-0a83-4808-b2a2-9bf3d88262f7	B	品名	brand_name	品名	IDENTIFIER	ACTIVE	2	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	f	f
3968db4f-95a1-4705-bfa9-10265e698f1d	03727943-3c6f-48c0-89a3-75f85c712ece	C	工序名称	process_name	工序名称 · Excel F	VALUE	ACTIVE	3	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
39724df5-5255-4139-82b0-92d0ab10077d	c7ceefeb-acf7-48f1-8452-f53a2f01116e	E	单价	unit_price	单价 · Excel G	VALUE	ACTIVE	5	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
3abd2141-baf5-4c7a-861b-1f2281f128af	1171806d-f555-46df-8ed6-5d26271fbb0e	G	价格单位	price_unit__costing	价格单位	VALUE	ACTIVE	70	2026-05-07 12:12:39.334863+00	2026-05-07 12:12:39.334863+00	NORMAL	f	f
3b2a693a-7ff4-438a-9947-92465ed2b651	ed6e3a65-42c1-440a-a380-79ef3eb3cb22	J	计价单位	price_unit	计价单位	VALUE	ACTIVE	10	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
3b469025-e4f7-4bc8-bd4c-8a889b26ad0c	1e67f052-bab0-4843-ae25-814e180759e7	E	货币	currency	货币	VALUE	ACTIVE	50	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
3bb0907a-76b2-443f-a067-92ccf8766862	5f6e5f76-8b48-4aac-b477-61e1139253a2	J	取用的耗材版本	ref_calc_version	取用的耗材版本	VALUE	ACTIVE	10	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
3beb800f-64cb-4c8d-a498-d97ed54d8da2	7fc4865d-d106-42e3-8712-a1da9d2efc40	H	元素价格版本	element_version_number	元素价格版本	IDENTIFIER	ACTIVE	8	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
3c03fb85-b913-4a64-923a-70284d33be03	02b300d8-7d3e-46b8-8171-16407c68c0ee	G	非生产能耗单价	unit_price	非生产能耗单价	VALUE	ACTIVE	7	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	IMPORTANT	t	t
3c47d184-b7e6-49db-8da1-905a3b086ff9	81c9b0b6-67d3-4ae0-9b4c-3cc46b94d22c	C	来料料号	dim_input_material_no	来料料号	IDENTIFIER	ACTIVE	3	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	IMPORTANT	t	f
3c50eda6-61b3-40d8-bad8-be618b7d4a55	3beb8382-5ec8-4116-861f-93477300cafc	E	货币	currency	货币	VALUE	ACTIVE	50	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
3c74cffe-9f9b-40f9-ac8a-1667eed287f4	2d1cb78f-442b-421b-9c77-85c7df699d3d	D	工序编号	process_no	工序编号 · Excel G	IDENTIFIER	ACTIVE	4	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
3cf0452d-5df9-40b0-bc8a-b4836d9c158f	7fc4865d-d106-42e3-8712-a1da9d2efc40	F	核价版本编号	summary_no	核价版本编号	IDENTIFIER	ACTIVE	6	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
3d7d411e-bea0-4e2c-89c8-81a411c71e5c	0227c720-a6fe-498c-bfeb-b80970bbbce2	F	工序名称	process_name	工序名称	VALUE	ACTIVE	6	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
3e4c69d2-0bac-4c68-9091-1605b66a4d25	8abc1a92-2bc1-4b4d-b916-a04f42d36be7	E	比例(%)	fee_ratio	比例(%)	VALUE	ACTIVE	5	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	IMPORTANT	t	f
3e6e337e-7896-4360-bec0-3bd217178575	cce210eb-335c-4584-994b-7b3229aae619	C	投入料号名称	dim_input_material_name	投入料号名称	IDENTIFIER	ACTIVE	3	2026-05-08 09:07:54.602727+00	2026-05-08 09:07:54.602727+00	NORMAL	f	f
3e7644e7-1486-450d-9dfb-8351e1447b2d	03727943-3c6f-48c0-89a3-75f85c712ece	I	模具工装成本单价	unit_price	模具工装成本单价 · Excel L (自动= I/J/K)	VALUE	ACTIVE	9	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
3e91e83d-226f-4dff-8d5e-6cd4f079455d	c7ceefeb-acf7-48f1-8452-f53a2f01116e	A	宏丰料号	hf_part_no	宏丰料号 · Excel A	IDENTIFIER	ACTIVE	1	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
3ebeabb0-6013-4ee2-9b93-030bff8c555b	e64f5ba2-fcaf-4e2b-a7c4-7aab96daa973	C	方案版本号	plan_version	方案版本号	VALUE	ACTIVE	30	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
3ec8d090-7c7d-4ca8-86a9-3a0c1da63d37	89997608-0f41-49a9-99f9-6221fd35a875	B	项次	seq_no	项次	VALUE	ACTIVE	20	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
3f49a2ef-bd2e-4a2e-85d5-48434d900e65	baff34e9-0179-49e4-b81c-6225e9b17f98	C	投料号	dim_input_material_no	投料号	VALUE	ACTIVE	3	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
3f768222-cea6-4446-b452-717b8518ae89	8abc1a92-2bc1-4b4d-b916-a04f42d36be7	F	货币	currency	货币	VALUE	ACTIVE	6	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	NORMAL	f	f
3ffbb310-a3b2-4381-a57b-d0e5ca2fb8d9	74e02284-08b3-42df-9193-50f0c382b331	C	工序名称	process_name	工序名称	VALUE	ACTIVE	3	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
418e314f-f768-40d0-8343-92f832cd37ca	baff34e9-0179-49e4-b81c-6225e9b17f98	D	投料号名称	dim_input_material_name	投料号名称	VALUE	ACTIVE	4	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
41ae9f67-6500-4c77-99c0-f6a4b7b60375	5cbaaaed-a029-493f-b6de-4cfbf9a24393	F	工序名称	process_name	工序名称	VALUE	ACTIVE	6	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
41ce956a-3252-41f3-bf6c-a74e09e1681d	c8fc519b-1f87-4ac8-b022-b94479bb1310	F	比例(%)	fee_ratio	比例(%)	VALUE	ACTIVE	60	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
436cbf95-b107-488d-a00f-feaca011bba8	8abc1a92-2bc1-4b4d-b916-a04f42d36be7	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	CRITICAL	t	t
448db587-b7e3-402c-a0eb-db0fbd94176d	f21d41b6-af67-4642-bf54-c33169b9e9d9	H	币种	currency	币种	VALUE	ACTIVE	8	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
448fc309-7f19-4af5-a87b-eac4ce44bb16	c9651c50-3b65-4952-97bd-c548de4ea3c5	A	料号	part_no	料号	IDENTIFIER	ACTIVE	10	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	t
4585bab6-ac53-45cd-8637-616d098f378b	90390eb2-f863-4d8f-b12f-8b7ab3a45537	B	元素名称	element_name	元素名称	VALUE	ACTIVE	20	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	IMPORTANT	f	t
45d4f2b5-0323-4742-abbe-5667252514a2	7fc4865d-d106-42e3-8712-a1da9d2efc40	I	材料价格版本	material_version_number	材料价格版本	IDENTIFIER	ACTIVE	9	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
468daaf0-a425-4e2d-9c19-6c5849d708da	3830f5e3-b4cb-4fd0-8762-4d97a6a59535	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	10	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	t
46952113-dcd1-4750-8636-39c4ae9744d0	7fc4865d-d106-42e3-8712-a1da9d2efc40	R	税费	tax	税费	VALUE	ACTIVE	18	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
473db22c-cd63-47db-a740-c87227325652	2d1cb78f-442b-421b-9c77-85c7df699d3d	I	底数单位	output_unit	底数单位 · Excel L	VALUE	ACTIVE	9	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
47693e52-ce8d-4de5-94dc-d55ec971edc0	7473c0d9-35c3-40f2-90dd-c26737eb0d08	E	其他成本	other_cost	其他成本	VALUE	ACTIVE	50	2026-05-14 05:17:02.209332+00	2026-05-14 05:17:02.209332+00	NORMAL	t	f
47832763-d62a-4ee4-8045-a0788d498812	f21d41b6-af67-4642-bf54-c33169b9e9d9	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	IMPORTANT	t	t
479185f1-89c3-4cb3-bfb8-15b5c308866c	2d1cb78f-442b-421b-9c77-85c7df699d3d	B	项次	seq_no	项次 · Excel B	VALUE	ACTIVE	2	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
48a192dc-826d-4464-a79e-2c07650f19e8	f21d41b6-af67-4642-bf54-c33169b9e9d9	J	取用的计算版本	ref_calc_version	取用的计算版本	VALUE	ACTIVE	10	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
4a7b9b5e-cfe4-4db7-bcd7-1f797496e382	90390eb2-f863-4d8f-b12f-8b7ab3a45537	C	默认含量(%)	default_pct	默认含量	VALUE	ACTIVE	30	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	CRITICAL	t	t
4ad172dc-c4c3-4d0f-b6fc-c30060c1777b	273f3019-0a83-4808-b2a2-9bf3d88262f7	I	折扣率%	discount_rate	折扣率%	VALUE	ACTIVE	9	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	f	f
4ade744a-7820-4a05-aaf0-6f603e39ae21	19f2bc23-ca5e-4297-a85a-283c4e4fcb6e	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
4b3ff4ee-3582-4c21-9eeb-2d47da3c535f	7fc4865d-d106-42e3-8712-a1da9d2efc40	V	币种	quote_currency	币种	VALUE	ACTIVE	21	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
4b6a9182-cd1c-483f-9082-04100c15a016	3830f5e3-b4cb-4fd0-8762-4d97a6a59535	B	项次	seq_no	项次	VALUE	ACTIVE	20	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
4c5aa97b-6d70-4b15-8328-166b996ac8cb	4a745dcf-7a2b-42de-9929-cb0eda525b2d	F	工序名称	process_name	工序名称	VALUE	ACTIVE	6	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
4cca382c-e627-4cee-9a03-78275093ca56	0227c720-a6fe-498c-bfeb-b80970bbbce2	G	加工费	unit_price	加工费	VALUE	ACTIVE	7	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	t	f
4d16db8f-e7b6-43fe-b3f6-ea154942f6ac	18b0f7e0-3eba-45c2-86d2-30b33c5818f1	G	二级项次	dim_sub_seq_no	二级项次	IDENTIFIER	ACTIVE	7	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
4d33f7e7-e006-4db9-babe-fe6520c38abf	26760bbe-6235-460a-a4ca-b88ba2945248	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	10	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	t
4d954055-f224-46f7-a1ba-ebe4ee270b1d	c9651c50-3b65-4952-97bd-c548de4ea3c5	B	单重(g/pcs)	unit_weight	单重(g/pcs)	VALUE	ACTIVE	20	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	t
4ef8f4df-e6d8-4f01-b485-2ef4a019db8b	26760bbe-6235-460a-a4ca-b88ba2945248	C	投入料号名称	input_material_name	投入料号名称	VALUE	ACTIVE	30	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
4f12026b-977d-4dde-84b6-bd173653f541	4432b3b5-fe3b-4c11-b1b8-078abafc95fa	E	损耗率（%）	loss_rate	损耗率（%） · Excel H	VALUE	ACTIVE	5	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
4fa02411-6e81-4510-9ac2-13dea397df0c	bfda7921-615d-43dd-8347-ced1f7112731	E	项次(2)	sub_seq_no	子项次	IDENTIFIER	ACTIVE	5	2026-05-09 03:16:23.645395+00	2026-05-09 03:16:23.645395+00	IMPORTANT	f	f
50e8df3e-00e4-4cc2-8935-28860d96d9b1	f21d41b6-af67-4642-bf54-c33169b9e9d9	K	是否有效	is_active	是否有效	VALUE	ACTIVE	11	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
5184791d-d3c2-4eb4-a9c7-a32d9f67998f	cb7d212b-6bdb-4798-8a24-9fdee86b069b	B	阶段	stage	阶段 (INCOMING/SEMI_FINISHED)	IDENTIFIER	ACTIVE	2	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
519d7f8a-3987-4b35-9d7b-814bc8966fc1	273f3019-0a83-4808-b2a2-9bf3d88262f7	C	规格	spec	规格	IDENTIFIER	ACTIVE	3	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	f	f
51ec2d58-4964-432e-afb0-8540896c6b22	de3240ab-f932-4f9d-8f52-4283b6d114a9	D	货币	currency	货币	IDENTIFIER	ACTIVE	4	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	f	f
51f9770d-fd77-4c8c-bfea-3a3132cc2ebd	c44589f2-0c2a-40da-aad4-473f78c33c0b	G	材料净重	net_qty	材料净重	VALUE	ACTIVE	7	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	IMPORTANT	t	f
5233d6bb-5873-4dc4-9e59-8d821fc3d3f9	cbd60f9d-7ba8-4c7b-ac80-82d769172b15	B	默认单价	unit_price	默认单价	VALUE	ACTIVE	20	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	CRITICAL	t	t
533b0227-55a6-4569-b6c2-da9ba471dc07	1b0bda96-e8ca-4611-9072-6f76d2874fdf	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
53addc13-0959-4fac-8ad5-14d5c20a9d96	c44589f2-0c2a-40da-aad4-473f78c33c0b	H	重量单位	gross_unit	重量单位	VALUE	ACTIVE	8	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	NORMAL	f	f
544589cd-3113-4044-a64f-d691eead2cf4	7473c0d9-35c3-40f2-90dd-c26737eb0d08	C	加工费	process_fee	加工费	VALUE	ACTIVE	30	2026-05-14 05:17:02.209332+00	2026-05-14 05:17:02.209332+00	CRITICAL	t	f
55139164-28a1-4082-b698-a3b37dba7e6c	cb737a06-7c69-4d8f-b7f0-73b510352ae3	E	项次	seq_no	项次	IDENTIFIER	ACTIVE	5	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	CRITICAL	t	t
55635598-a9b1-4ad0-8647-665903f51266	273f3019-0a83-4808-b2a2-9bf3d88262f7	A	材料料号	material_no	材料料号	IDENTIFIER	ACTIVE	1	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	f	f
55fbb406-9455-4405-a57c-8e1cc7a757c6	bd76770f-d5bb-46a9-9718-1bb632ea76da	F	重量单位	weight_unit	重量单位	VALUE	ACTIVE	60	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
56bcc0ba-1b5c-4ff4-8126-491b24d9fbe7	c8fc519b-1f87-4ac8-b022-b94479bb1310	K	材料固定的涨幅值	fixed_rise_value	材料固定的涨幅值	VALUE	ACTIVE	110	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
56f8b63b-8abc-4793-8997-447d3561d688	3b32f861-bc33-431d-93fc-5c9a1e6cf149	I	计量单位	unit	计量单位	VALUE	ACTIVE	9	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
57d55d22-357c-4d0a-acac-8fc2efe13614	311825c4-bc43-44d3-902c-6d95406f51e0	B	化学符号	symbol	化学符号	VALUE	ACTIVE	20	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	IMPORTANT	f	t
58282f52-c9ef-410b-84e5-d8a452e4c6da	6b04aeeb-84c2-42bf-a20b-42e86ba7d244	D	电镀元素名称	plating_element	电镀元素名称	VALUE	ACTIVE	4	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	IMPORTANT	t	f
58b8914e-e47d-460e-802a-6896d6a5dd0a	c44589f2-0c2a-40da-aad4-473f78c33c0b	E	产出料号类型	output_material_type	产出料号类型	VALUE	ACTIVE	5	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	NORMAL	f	f
593bbbcf-7bdc-4488-b70f-c363d1b6abec	1d0b619f-b5db-4cef-9a34-625f7d17d493	A	方案编号	plating_no	方案编号 · 电镀方案 Excel A	IDENTIFIER	ACTIVE	1	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
594a077b-c54e-45a3-859d-158db9127f6e	26760bbe-6235-460a-a4ca-b88ba2945248	F	组成含量(%)	composition_pct	组成含量(%)	VALUE	ACTIVE	60	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
59b8d6a6-4df1-426d-86f3-c4ebb425b0c6	ddd191d3-e515-4371-be64-bbd9ecef990f	B	项次	seq_no	项次	VALUE	ACTIVE	20	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
5a136d12-c2ab-4f55-8146-5a5d43a4c5fa	1e67f052-bab0-4843-ae25-814e180759e7	B	项次	seq_no	项次	VALUE	ACTIVE	20	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
5a54eabd-65db-4c6c-a884-da862569ccc6	baff34e9-0179-49e4-b81c-6225e9b17f98	E	回收折扣(%)	fee_ratio	回收折扣	VALUE	ACTIVE	5	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	IMPORTANT	t	f
5a74dc23-33f6-417b-b0f6-27ceec545f7d	26760bbe-6235-460a-a4ca-b88ba2945248	D	项次	seq_no	项次	VALUE	ACTIVE	40	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
5a7e2c67-4645-4f67-a637-f681e67e7ddb	89997608-0f41-49a9-99f9-6221fd35a875	K	材料固定的涨幅值	fixed_rise_value	材料固定的涨幅值	VALUE	ACTIVE	110	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
5b26a35b-543a-4973-bee3-2cfe05204cc8	ed6e3a65-42c1-440a-a380-79ef3eb3cb22	C	投入料号	dim_input_material_no	投入料号	IDENTIFIER	ACTIVE	3	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	IMPORTANT	f	f
5b305864-7dbe-4fd2-beff-1cb4b6473258	8a1ce500-5181-4001-9001-100000000001	E	材料损耗成本	material_loss_cost	材料损耗成本	VALUE	ACTIVE	50	2026-05-15 08:15:33.302266+00	2026-05-15 08:15:33.302266+00	NORMAL	t	f
5e44c43a-23b1-418e-85ee-5be7e474c2b3	1d0b619f-b5db-4cef-9a34-625f7d17d493	E	电镀面积（cm2）	plating_area_cm2	电镀面积（cm2） · 电镀方案 Excel E	VALUE	ACTIVE	5	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
5e943d32-1472-4582-ba86-2f72e68b8744	bfda7921-615d-43dd-8347-ced1f7112731	D	组装工序	assembly_process	组装工序	VALUE	ACTIVE	4	2026-05-09 03:16:23.645395+00	2026-05-09 03:16:23.645395+00	IMPORTANT	f	f
5ed43b3a-e713-44ca-a792-c482f66bf992	0227c720-a6fe-498c-bfeb-b80970bbbce2	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
5f23619e-8b94-452e-90a9-8242464dee9d	89997608-0f41-49a9-99f9-6221fd35a875	H	计价单位	price_unit	计价单位	VALUE	ACTIVE	80	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
5f6484f2-898d-41dd-89dc-560a14113d3b	5cbaaaed-a029-493f-b6de-4cfbf9a24393	H	币种	currency	币种	VALUE	ACTIVE	8	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
5fe8a410-1972-43d1-a99e-c9ce5f0a7f16	480c784f-60da-4213-ab90-1f5384a4048d	I	计价单位	price_unit	计价单位	VALUE	ACTIVE	90	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
603b15be-a42d-4883-bce4-bf0a1e11375d	8abc1a92-2bc1-4b4d-b916-a04f42d36be7	G	计价单位	price_unit	计价单位	VALUE	ACTIVE	7	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	NORMAL	f	f
60ae2b74-91bf-4223-b2e4-db4e1a5b9266	4a745dcf-7a2b-42de-9929-cb0eda525b2d	G	项次	seq_no	项次	IDENTIFIER	ACTIVE	7	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
6147df8f-3b09-4c00-a9c9-b828718253ce	5341c659-a256-432c-9019-d2007242d9d5	F	基础货币	base_currency	基础货币	VALUE	ACTIVE	60	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
62050452-db0f-4ce7-8ef9-512f85a231f8	2d1cb78f-442b-421b-9c77-85c7df699d3d	F	组成用量	input_qty	组成用量 · Excel I	VALUE	ACTIVE	6	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
62301cc7-428f-4293-9dd7-5020e8f8da72	5f6e5f76-8b48-4aac-b477-61e1139253a2	G	耗材成本单价	unit_price	耗材成本单价	VALUE	ACTIVE	7	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	t	t
6260e125-c76f-4adb-874b-f20b29bb54b3	7fc4865d-d106-42e3-8712-a1da9d2efc40	G	核价版本名称	element_version_label	核价版本名称	IDENTIFIER	ACTIVE	7	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
62efd8fa-5448-450f-bab0-226cc6951c13	03727943-3c6f-48c0-89a3-75f85c712ece	A	宏丰料号	hf_part_no	宏丰料号 · Excel A	IDENTIFIER	ACTIVE	1	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
63a43ba7-04e1-4b8e-8985-9190293c1e97	404aec01-831c-4937-b7ed-f6fc78de93bd	F	货币	currency	货币	VALUE	ACTIVE	6	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
64d16b15-490c-4de0-8ffb-027626351fd9	8abc1a92-2bc1-4b4d-b916-a04f42d36be7	C	要素名称	dim_element_name	要素名称	VALUE	ACTIVE	3	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	IMPORTANT	t	t
65379394-94ef-446e-b6bd-6f36730d0758	02b300d8-7d3e-46b8-8171-16407c68c0ee	I	计量单位	unit	计量单位	VALUE	ACTIVE	9	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
65a1b092-5348-4ae0-82c2-6ee60df1aa35	ddd191d3-e515-4371-be64-bbd9ecef990f	I	供应商名称	supplier_name	供应商名称	VALUE	ACTIVE	90	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
662ea0d2-72ad-438d-9676-e4eec627bf4c	1171806d-f555-46df-8ed6-5d26271fbb0e	C	方案版本号	plan_version__costing	方案版本号	VALUE	ACTIVE	30	2026-05-07 12:12:39.334863+00	2026-05-07 12:12:39.334863+00	NORMAL	f	f
66b020df-9ca0-4607-914b-4a5657f8240f	8a1ce500-5181-4001-9001-100000000001	B	来料加工费	incoming_proc_fee	来料加工费	VALUE	ACTIVE	20	2026-05-15 08:15:33.302266+00	2026-05-15 08:15:33.302266+00	NORMAL	t	f
6786a503-5e51-452a-a8b5-f7c8a80ddf49	5341c659-a256-432c-9019-d2007242d9d5	A	客户料号名称	customer_part_name	客户料号名称	VALUE	ACTIVE	10	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
67a11141-e68f-493a-85b5-33c1d6a504c7	de3240ab-f932-4f9d-8f52-4283b6d114a9	A	元素代码	element_code	元素代码	IDENTIFIER	ACTIVE	1	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	f	f
67dc384d-d4c8-475c-8c53-7ca9299a141f	1b0bda96-e8ca-4611-9072-6f76d2874fdf	H	币种	currency	币种	VALUE	ACTIVE	8	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
69681af3-3087-4376-ae96-34ec05f796e4	2acc46db-efad-46f4-ae69-59ddb2105a9a	F	组成件料号	dim_input_material_no	组成件料号	VALUE	ACTIVE	5	2026-05-09 05:03:31.312193+00	2026-05-09 05:03:31.312193+00	NORMAL	f	f
697e84b8-273e-414b-96b7-646e7abfcc9c	a099b527-a5fd-41a6-bc2f-1cec035f8491	N	材料固定损耗量	fixed_loss_qty	材料固定损耗量	VALUE	ACTIVE	14	2026-05-06 06:50:56.611077+00	2026-05-06 06:50:56.611077+00	NORMAL	f	f
69bd97cf-128b-47f2-95e3-0c3d3f99f027	0227c720-a6fe-498c-bfeb-b80970bbbce2	H	币种	currency	币种	VALUE	ACTIVE	8	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
69c0a5ed-c2e7-4f9b-a26e-33f637f0c26b	74e02284-08b3-42df-9193-50f0c382b331	F	单位	unit	单位	VALUE	ACTIVE	6	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
6a259196-1ec5-4ac8-b971-183ccd22d13e	cb7d212b-6bdb-4798-8a24-9fdee86b069b	H	是否有效	is_active	是否有效	VALUE	ACTIVE	8	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
6a5c19e6-da82-485b-b6f7-0fbdb4adccf1	0227c720-a6fe-498c-bfeb-b80970bbbce2	E	工序编号	process_no	工序编号	IDENTIFIER	ACTIVE	5	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
6b291984-ebfb-4f4c-9466-c0da138f2ec1	4a745dcf-7a2b-42de-9929-cb0eda525b2d	O	计量单位	unit	计量单位	VALUE	ACTIVE	15	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
6c3ff497-c80f-483d-ae55-f5b22be1f619	273f3019-0a83-4808-b2a2-9bf3d88262f7	H	单位	unit	单位	IDENTIFIER	ACTIVE	8	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	f	f
6d39315b-dd0d-4411-9c6b-2d399ff20f96	ed6e3a65-42c1-440a-a380-79ef3eb3cb22	H	比例(%)	fee_ratio	比例(%)	VALUE	ACTIVE	8	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	IMPORTANT	t	f
6d8d54ab-5af3-482c-9048-8918de428c67	02b300d8-7d3e-46b8-8171-16407c68c0ee	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	IMPORTANT	t	t
6dc41995-d136-4604-a1be-6b80b7ce31da	5f6e5f76-8b48-4aac-b477-61e1139253a2	I	计量单位	unit	计量单位	VALUE	ACTIVE	9	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
6dd432cb-9a89-4cf7-bff9-41c31731e52c	90390eb2-f863-4d8f-b12f-8b7ab3a45537	D	可调下限(%)	min_pct	可调下限	VALUE	ACTIVE	40	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	NORMAL	f	f
6de94ec3-5fb5-4f69-a2b8-d820280886d9	4a745dcf-7a2b-42de-9929-cb0eda525b2d	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
6e6c648e-891b-4085-9bdf-daf072119c32	2acc46db-efad-46f4-ae69-59ddb2105a9a	E	组件项次	dim_sub_seq_no	组件项次	IDENTIFIER	ACTIVE	4	2026-05-09 05:03:31.312193+00	2026-05-09 05:03:31.312193+00	IMPORTANT	f	f
6eb1d923-e092-453e-9fd8-dbdfd6eb7e35	3b32f861-bc33-431d-93fc-5c9a1e6cf149	K	是否有效	is_active	是否有效	VALUE	ACTIVE	11	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
6f0c8d31-1071-42da-9605-a2ed21f85ada	e64f5ba2-fcaf-4e2b-a7c4-7aab96daa973	F	货币	currency	货币	VALUE	ACTIVE	60	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
6ffe6c68-2c85-4f32-a0fb-a45506dafcc7	6b04aeeb-84c2-42bf-a20b-42e86ba7d244	F	镀层厚度	coating_thickness	镀层厚度(μm)	VALUE	ACTIVE	6	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	CRITICAL	t	t
7081dd37-f026-4b55-b4a9-3895f44a303f	ddd191d3-e515-4371-be64-bbd9ecef990f	G	组成件名称	component_name	组成件名称	VALUE	ACTIVE	70	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
70b9bbf2-ee7d-4ee8-966f-67601bcb9fa3	7fc4865d-d106-42e3-8712-a1da9d2efc40	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
70c61ddd-4a50-4634-8285-24485b03a28f	3b32f861-bc33-431d-93fc-5c9a1e6cf149	H	币种	currency	币种	VALUE	ACTIVE	8	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
7102915a-0eab-4448-b6e3-5927185cf8db	1b0bda96-e8ca-4611-9072-6f76d2874fdf	G	费用	fee_value	费用	VALUE	ACTIVE	7	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	f	f
71dddae9-5d6e-4a12-ac1a-57fc41ae30f5	03727943-3c6f-48c0-89a3-75f85c712ece	J	币种	currency	币种 · Excel M	VALUE	ACTIVE	10	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
72202e8a-cfeb-4878-83b3-44bc52b35776	ed6e3a65-42c1-440a-a380-79ef3eb3cb22	I	货币	currency	货币	VALUE	ACTIVE	9	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
72242812-4e2d-4f34-8ea6-febcf3a26120	baff34e9-0179-49e4-b81c-6225e9b17f98	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	IMPORTANT	f	t
729f1cee-4891-4325-a5bf-96e5cb84aca3	02b300d8-7d3e-46b8-8171-16407c68c0ee	H	币种	currency	币种	VALUE	ACTIVE	8	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
72a3df5c-fd53-4ad8-829f-d8ddecfb20e4	18b0f7e0-3eba-45c2-86d2-30b33c5818f1	K	计价单位	price_unit	计价单位	VALUE	ACTIVE	11	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
735cb7f8-d6ed-4710-baa6-8f50d1b04ca6	26760bbe-6235-460a-a4ca-b88ba2945248	G	损耗率%	loss_rate	损耗率%	VALUE	ACTIVE	70	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
7384b450-7398-4d6a-89a1-06a54ad3a264	e64f5ba2-fcaf-4e2b-a7c4-7aab96daa973	E	电镀材料费	plating_material_fee	电镀材料费	VALUE	ACTIVE	50	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
7437c009-d125-419a-93d5-31e499317d76	4a745dcf-7a2b-42de-9929-cb0eda525b2d	E	工序编号	process_no	工序编号	IDENTIFIER	ACTIVE	5	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
74c7e7ac-050b-444b-b804-52c02a0b2ec3	26760bbe-6235-460a-a4ca-b88ba2945248	E	元素	element_name	元素	VALUE	ACTIVE	50	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
755b69d1-6346-4f34-bea0-d873d6591762	8abc1a92-2bc1-4b4d-b916-a04f42d36be7	B	项次	seq_no	项次	IDENTIFIER	ACTIVE	2	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	CRITICAL	t	t
7591e343-e51c-478a-82f8-7fcc5622920d	eecfa1a5-f903-41fb-8044-03f15f3acf2a	E	项次	seq_no	项次	IDENTIFIER	ACTIVE	5	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
76fbbcf6-b02b-48f5-b04e-e7dc62a74113	90390eb2-f863-4d8f-b12f-8b7ab3a45537	E	可调上限(%)	max_pct	可调上限	VALUE	ACTIVE	50	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	NORMAL	f	f
770785ef-3cf1-4abf-acf9-f7ce31d434a0	1171806d-f555-46df-8ed6-5d26271fbb0e	H	不良率	defect_rate__costing	不良率	VALUE	ACTIVE	80	2026-05-07 12:12:39.334863+00	2026-05-07 12:12:39.334863+00	NORMAL	f	f
7834a6f5-1081-465f-89cb-cb1e3e74d0e8	273f3019-0a83-4808-b2a2-9bf3d88262f7	E	核价单价	costing_price	核价单价	VALUE	ACTIVE	5	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	t	f
78707ceb-bf7c-49d9-8c47-56a449c5a5e6	c8fc519b-1f87-4ac8-b022-b94479bb1310	G	货币	currency	货币	VALUE	ACTIVE	70	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
799296f5-e0f5-464d-a857-11f049a08a9d	02b300d8-7d3e-46b8-8171-16407c68c0ee	E	工序编号	process_no	工序编号	IDENTIFIER	ACTIVE	5	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	IMPORTANT	t	t
7999e36c-6e53-4145-bd26-8537b3a6ae76	cb7d212b-6bdb-4798-8a24-9fdee86b069b	G	报废率%	scrap_rate	报废率%	VALUE	ACTIVE	7	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
79c01168-e0c8-4b2e-9bd0-84316a5cfdfa	404aec01-831c-4937-b7ed-f6fc78de93bd	D	电镀加工费	plating_process_fee	电镀加工费	VALUE	ACTIVE	4	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	f	f
7a2b6be6-2b49-4151-8d59-3ad6d58122ee	89997608-0f41-49a9-99f9-6221fd35a875	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	10	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	t
7a408cec-037c-422e-aadf-cde2cefa1351	7aabce5c-c9b2-41d1-9270-685651cf6854	E	货币	currency	货币	VALUE	ACTIVE	50	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
7b071d0a-5713-4c7a-a067-4c03954ad8e9	7fc4865d-d106-42e3-8712-a1da9d2efc40	K	是否生效	is_published_label	是否生效	VALUE	ACTIVE	11	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
7b5eb248-f7ce-4341-aaba-4712158184a1	a099b527-a5fd-41a6-bc2f-1cec035f8491	I	组成用量	input_qty	组成用量(可负)	VALUE	ACTIVE	9	2026-05-06 06:50:56.611077+00	2026-05-06 06:50:56.611077+00	IMPORTANT	t	f
7bbabb26-9194-4061-9f71-0c26d2d4e3b3	6c1eebc1-bfb6-405f-93d6-7ae2ce3216e6	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
7cf76280-7c15-4d84-845b-386a5085452c	1e67f052-bab0-4843-ae25-814e180759e7	F	计价单位	price_unit	计价单位	VALUE	ACTIVE	60	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
7df80257-a8c2-46bf-8ebe-adc2f94c3a19	1b0bda96-e8ca-4611-9072-6f76d2874fdf	I	计价单位	price_unit	计价单位	VALUE	ACTIVE	9	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
7f466695-8541-4c84-8ce8-0bf156fb4042	9a9932c7-3a1c-4c4d-bb62-b2d54c2a78d7	C	投入料号	dim_input_material_no	投入料号	IDENTIFIER	ACTIVE	3	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	IMPORTANT	f	f
7f92ac41-3781-4dd1-a8d9-16c24f0afca5	9a9932c7-3a1c-4c4d-bb62-b2d54c2a78d7	D	投入料号名称	dim_input_material_name	投入料号名称	VALUE	ACTIVE	4	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	NORMAL	f	f
7fd5f5cb-f9d7-40c1-8309-df5c9f6d4e8d	bfda7921-615d-43dd-8347-ced1f7112731	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-09 03:16:23.645395+00	2026-05-09 03:16:23.645395+00	CRITICAL	f	t
80c3bb9a-58a8-4017-9d8b-e7070024006d	7aabce5c-c9b2-41d1-9270-685651cf6854	D	单次固定年降金额	fee_value	单次固定年降金额	VALUE	ACTIVE	40	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
81258dd0-7e23-41fe-9263-637bb958f68b	02b300d8-7d3e-46b8-8171-16407c68c0ee	J	取用的计算版本	ref_calc_version	取用的计算版本	VALUE	ACTIVE	10	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
815cdfd0-38ac-4495-94d7-0c54f1ca82e9	cce210eb-335c-4584-994b-7b3229aae619	F	回收折扣(%)	fee_ratio	回收折扣	VALUE	ACTIVE	6	2026-05-08 09:07:54.602727+00	2026-05-08 09:07:54.602727+00	IMPORTANT	t	f
81655b54-35b7-4704-af72-8c00319fea5e	4432b3b5-fe3b-4c11-b1b8-078abafc95fa	F	是否有效	is_active	是否有效 (无 Excel 列, DB 字段)	VALUE	ACTIVE	6	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
816b7f48-95d1-4f0a-bb04-09126f675693	bd76770f-d5bb-46a9-9718-1bb632ea76da	C	规格	specification	规格	VALUE	ACTIVE	30	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
8197750f-2702-4a9d-b563-195e7f84ef61	1e67f052-bab0-4843-ae25-814e180759e7	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	10	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	t
8367af02-780a-4886-8e73-b3c7b09ce1c7	bd76770f-d5bb-46a9-9718-1bb632ea76da	A	宏丰料号	part_no	宏丰料号	IDENTIFIER	ACTIVE	10	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	t
83fe17cd-8e94-446f-8f79-eec41b147a5f	e64f5ba2-fcaf-4e2b-a7c4-7aab96daa973	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	10	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	t
849d70ee-ad29-4608-88a4-e8a6b86ab14e	03727943-3c6f-48c0-89a3-75f85c712ece	L	是否有效	is_active	是否有效 · Excel O	VALUE	ACTIVE	12	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
84ee61dd-ffeb-408e-bade-a13df9abdaec	b35e5d9b-b226-454c-a863-fe6b6c367972	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
858327b6-e992-4087-9daf-02be3b1d9807	cce210eb-335c-4584-994b-7b3229aae619	B	投入料号	dim_input_material_no	投入料号	IDENTIFIER	ACTIVE	2	2026-05-08 09:07:54.602727+00	2026-05-08 09:07:54.602727+00	IMPORTANT	f	f
88f7d617-3107-4621-a273-f2e9ff652079	c44589f2-0c2a-40da-aad4-473f78c33c0b	D	投入料号名称	input_material_name	投入料号名称	VALUE	ACTIVE	4	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	NORMAL	f	f
89ec085d-325f-4527-a494-371512c1d794	7fc4865d-d106-42e3-8712-a1da9d2efc40	E	项次	line_seq	项次	VALUE	ACTIVE	5	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
8ae0fb7c-ec10-4eed-866a-004df70ec2bb	ce553849-1938-4084-ba5e-684b54852123	D	电镀元素名称	plating_element	电镀元素名称	VALUE	ACTIVE	4	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	IMPORTANT	t	f
8ae4841a-69cc-4797-9907-1410d0db3ba8	1171806d-f555-46df-8ed6-5d26271fbb0e	E	电镀材料费	plating_material_fee__costing	电镀材料费	VALUE	ACTIVE	50	2026-05-07 12:12:39.334863+00	2026-05-07 12:12:39.334863+00	NORMAL	f	f
8afffce3-4bd5-483f-b77a-483a785ac460	a099b527-a5fd-41a6-bc2f-1cec035f8491	M	来料损耗率(%)	loss_rate	来料损耗率(%)	VALUE	ACTIVE	13	2026-05-06 06:50:56.611077+00	2026-05-06 06:50:56.611077+00	IMPORTANT	t	f
8b147b0f-0a7b-400c-b184-3369ec8f5768	6b04aeeb-84c2-42bf-a20b-42e86ba7d244	A	方案编号	plan_code	方案编号	IDENTIFIER	ACTIVE	1	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	CRITICAL	t	t
8b9baf6a-e5d5-4460-9457-a8b598f2c382	7473c0d9-35c3-40f2-90dd-c26737eb0d08	D	运费	freight_fee	运费	VALUE	ACTIVE	40	2026-05-14 05:17:02.209332+00	2026-05-14 05:17:02.209332+00	NORMAL	t	f
8c02f833-c1de-4f2f-af9f-e69ba687aecc	f21d41b6-af67-4642-bf54-c33169b9e9d9	G	人工标准单价	unit_price	人工标准单价	VALUE	ACTIVE	7	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	IMPORTANT	t	t
8c28f3e4-01d1-444b-81ad-7bf764e35efb	0227c720-a6fe-498c-bfeb-b80970bbbce2	J	不良率·拒收率(%)	notes	不良率·拒收率(%)	VALUE	ACTIVE	10	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
8c759c08-0847-4a05-93c8-fc3d615caa32	ed6e3a65-42c1-440a-a380-79ef3eb3cb22	E	项次(内)	dim_sub_seq_no	项次(内)	IDENTIFIER	ACTIVE	5	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	IMPORTANT	f	f
8d6956af-8481-47ef-8989-c47150275559	ddd191d3-e515-4371-be64-bbd9ecef990f	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	10	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	t
8f530873-84b6-4768-a5d2-30d718dcf115	6c1eebc1-bfb6-405f-93d6-7ae2ce3216e6	H	损耗率%	loss_rate	损耗率%	VALUE	ACTIVE	8	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
8fd50031-645d-4466-b6f5-391a8f2fa76c	0227c720-a6fe-498c-bfeb-b80970bbbce2	I	计量单位	unit	计量单位	VALUE	ACTIVE	9	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
8febaa12-8581-459c-86f0-3b4952f6087d	74e02284-08b3-42df-9193-50f0c382b331	E	币种	currency	币种	VALUE	ACTIVE	5	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
90cafc33-2c1a-4320-a6b0-21f88a965047	4432b3b5-fe3b-4c11-b1b8-078abafc95fa	D	组成含量（%）	composition_pct	组成含量（%） · Excel G	VALUE	ACTIVE	4	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
90eee4aa-1d56-4428-b02d-9b48a9118bab	18b0f7e0-3eba-45c2-86d2-30b33c5818f1	H	要素名称	dim_element_name	要素名称	VALUE	ACTIVE	8	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	t	f
91bd0ecd-83b9-487c-b08a-b9414bf477fa	bd76770f-d5bb-46a9-9718-1bb632ea76da	E	单重	unit_weight	单重	VALUE	ACTIVE	50	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	t
932ccf35-59ab-4aa3-9bc5-3529a633de57	3beb8382-5ec8-4116-861f-93477300cafc	C	组装工序	dim_assembly_process	组装工序	VALUE	ACTIVE	30	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
9353b26a-cd9e-41ba-aa07-5228d3f9498f	2acc46db-efad-46f4-ae69-59ddb2105a9a	M	值	fee_value	值	VALUE	ACTIVE	8	2026-05-09 05:03:31.312193+00	2026-05-09 05:03:31.312193+00	CRITICAL	t	t
9382b73a-3031-4539-8444-d558fa1053f5	6c1eebc1-bfb6-405f-93d6-7ae2ce3216e6	G	计量单位	unit	计量单位	VALUE	ACTIVE	7	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
93c1bfc7-53db-4a5b-bffd-cfacbf08290c	7fc4865d-d106-42e3-8712-a1da9d2efc40	B	品名	product_name	品名	VALUE	ACTIVE	2	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
94be76c9-371f-4801-9a2f-60e606b4a281	7fc4865d-d106-42e3-8712-a1da9d2efc40	T	其他外加工成本	other_outsource_cost	其他外加工成本	VALUE	ACTIVE	20	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
94c96189-b28a-4b30-88c6-6fb8686d0815	89997608-0f41-49a9-99f9-6221fd35a875	I	是否随材料价格波动	price_floating	是否随材料价格波动	VALUE	ACTIVE	90	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
94ff787f-987a-4567-8cda-a7f79b92f4eb	26760bbe-6235-460a-a4ca-b88ba2945248	J	净用量	net_qty	净用量	VALUE	ACTIVE	100	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
955d42ce-b9ff-47f5-b7f6-1a8c916dc848	c7ceefeb-acf7-48f1-8452-f53a2f01116e	I	是否有效	is_active	是否有效 · Excel K	VALUE	ACTIVE	9	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
95a663e3-127d-4924-9f28-c254c08d48fa	4a745dcf-7a2b-42de-9929-cb0eda525b2d	P	是否有效	is_active	是否有效	VALUE	ACTIVE	16	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
95af6b2c-e123-40ee-a510-d404ce7a82be	ed6e3a65-42c1-440a-a380-79ef3eb3cb22	B	项次	seq_no	项次	IDENTIFIER	ACTIVE	2	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	CRITICAL	t	t
969d662f-c713-47e5-bcc8-ab271818ff5e	2d1cb78f-442b-421b-9c77-85c7df699d3d	K	材料固定损耗量	fixed_loss_qty	材料固定损耗量 · Excel N	VALUE	ACTIVE	11	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
96c0b630-868a-480b-a605-46d3830fde72	094dedfc-758f-444c-9375-4d80d858a09a	C	投料号	dim_input_material_no	投料号	VALUE	ACTIVE	3	2026-05-07 10:58:00.643135+00	2026-05-07 10:58:00.643135+00	NORMAL	f	f
9725aecb-53f0-4a98-a192-b38e2869f5e2	311825c4-bc43-44d3-902c-6d95406f51e0	E	配方类型	recipe_type	配方类型	VALUE	ACTIVE	50	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	NORMAL	f	f
978a425c-23b4-4a74-a8c2-5e424e14a88c	08602f0d-23a3-411b-8fe3-662981825bed	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	IMPORTANT	t	t
97c87837-85e2-4454-8349-46763f3f3222	4432b3b5-fe3b-4c11-b1b8-078abafc95fa	A	来料料号	input_material_no	来料料号 · Excel A	IDENTIFIER	ACTIVE	1	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
98611a3d-8c9a-44c4-b821-51f4d061133d	2acc46db-efad-46f4-ae69-59ddb2105a9a	L	要素名称	dim_element_name	要素名称	IDENTIFIER	ACTIVE	7	2026-05-09 05:03:31.312193+00	2026-05-14 06:06:43.127791+00	IMPORTANT	f	f
993fbeab-42c0-47fc-a5dd-b459ec743f42	1b0bda96-e8ca-4611-9072-6f76d2874fdf	E	项次	seq_no	项次	IDENTIFIER	ACTIVE	5	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	t	t
998335a0-4a12-4073-b8e2-02dea595fcc1	26760bbe-6235-460a-a4ca-b88ba2945248	I	毛用量单位	gross_unit	毛用量单位	VALUE	ACTIVE	90	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
99b8ab17-6b0e-47b9-be85-4480c14c2635	aa232879-b6cd-4cc3-ad6e-d395df9ccc44	C	图标	icon	图标	VALUE	ACTIVE	30	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	NORMAL	f	f
99f5fb04-807b-409b-b5aa-66342af70a47	89997608-0f41-49a9-99f9-6221fd35a875	F	比例(%)	fee_ratio	比例(%)	VALUE	ACTIVE	60	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
9ac609bc-c3d5-43ec-ac88-e65cbcd665a0	cbd60f9d-7ba8-4c7b-ac80-82d769172b15	C	币种	currency	币种	VALUE	ACTIVE	30	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	NORMAL	f	f
9b6428ab-643d-43ff-8d77-bac99aa86f47	480c784f-60da-4213-ab90-1f5384a4048d	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	10	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	t
9bb84c89-75e3-4177-a955-6c3457119623	08602f0d-23a3-411b-8fe3-662981825bed	E	工序编号	process_no	工序编号	IDENTIFIER	ACTIVE	5	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	IMPORTANT	t	t
9bf6080f-33f6-4d80-a42f-73c4fbe45c0d	094dedfc-758f-444c-9375-4d80d858a09a	D	投料号名称	dim_input_material_name	投料号名称	VALUE	ACTIVE	4	2026-05-07 10:58:00.643135+00	2026-05-07 10:58:00.643135+00	NORMAL	f	f
9c23b35e-69bb-4219-b915-9c2fa92ddf9a	209dde9a-d8c5-4e6c-b2eb-02fa0d87aeae	E	规格标签	spec_label	规格标签	VALUE	ACTIVE	4	2026-05-17 03:19:59.793876+00	2026-05-17 03:19:59.793876+00	NORMAL	f	f
9cc1e1c5-d340-4d5c-91ab-cd3bde30efc1	209dde9a-d8c5-4e6c-b2eb-02fa0d87aeae	B	材质代码	code	材质代码	IDENTIFIER	ACTIVE	1	2026-05-17 03:19:59.793876+00	2026-05-17 03:19:59.793876+00	NORMAL	f	f
9cd14af2-2090-4816-a409-8522f924d11f	de3240ab-f932-4f9d-8f52-4283b6d114a9	F	折扣率%	discount_rate	折扣率%	VALUE	ACTIVE	6	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	f	f
9cd82364-0ea4-4a31-9f32-ab23e569a9a2	a099b527-a5fd-41a6-bc2f-1cec035f8491	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-06 06:50:56.611077+00	2026-05-06 06:50:56.611077+00	CRITICAL	t	t
9cfc52b4-da64-4832-83a9-43607577e269	cb7d212b-6bdb-4798-8a24-9fdee86b069b	E	要件编号	requirement_code	要件编号	IDENTIFIER	ACTIVE	5	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
9d270963-b37a-47a2-82bd-636e552c711d	3b32f861-bc33-431d-93fc-5c9a1e6cf149	G	生产能耗单价	unit_price	生产能耗单价	VALUE	ACTIVE	7	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	IMPORTANT	t	t
9d648858-1f22-44b5-9eca-8ddcbe49761b	1171806d-f555-46df-8ed6-5d26271fbb0e	B	电镀方案编码	plating_plan_code__costing	电镀方案编码	VALUE	ACTIVE	20	2026-05-07 12:12:39.334863+00	2026-05-07 12:12:39.334863+00	NORMAL	f	f
9d85ca11-8407-452f-b8f7-faa6c1dd5392	3b32f861-bc33-431d-93fc-5c9a1e6cf149	F	工序名称	process_name	工序名称	VALUE	ACTIVE	6	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
9d8b00fb-9b96-4bbd-84b7-f71481a6244f	3beb8382-5ec8-4116-861f-93477300cafc	B	项次	seq_no	项次	VALUE	ACTIVE	20	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
9dc459cd-ce33-481c-bdfd-6aeb986526f8	ddd191d3-e515-4371-be64-bbd9ecef990f	N	货币	currency	货币	VALUE	ACTIVE	140	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
9df18816-ba4f-410b-91b3-b79ebaf9b161	9a9932c7-3a1c-4c4d-bb62-b2d54c2a78d7	E	项次(内)	dim_sub_seq_no	项次(内)	IDENTIFIER	ACTIVE	5	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	IMPORTANT	f	f
9e429d6d-9177-4795-b75d-ce9416e97eb2	4a745dcf-7a2b-42de-9929-cb0eda525b2d	K	寿命(次)	process_count	寿命(次)	VALUE	ACTIVE	11	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	f	f
9ebf9ee8-6034-4efb-b1cd-4ae7282972b0	89997608-0f41-49a9-99f9-6221fd35a875	G	货币	currency	货币	VALUE	ACTIVE	70	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
9ec1a40a-3032-4a17-9e80-532136035cbc	c8fc519b-1f87-4ac8-b022-b94479bb1310	E	值	fee_value	值	VALUE	ACTIVE	50	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
9ece5d7d-8ad6-46e2-ada1-229c4812138d	03727943-3c6f-48c0-89a3-75f85c712ece	H	单循环产量	cycle_count	单循环产量 · Excel K	VALUE	ACTIVE	8	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
9f78efb6-fd7b-4bae-85dc-02edf06c8aed	cb737a06-7c69-4d8f-b7f0-73b510352ae3	H	比例(%)	fee_ratio	比例(%)	VALUE	ACTIVE	8	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	CRITICAL	t	t
9f911b8c-a7cf-400d-99dc-190a25b0a354	c8fc519b-1f87-4ac8-b022-b94479bb1310	J	材料结算涨幅比例(%)	settlement_rise_ratio	材料结算涨幅比例(%)	VALUE	ACTIVE	100	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
a0ececdd-f249-40a1-9d17-07e6dc664dbe	cce210eb-335c-4584-994b-7b3229aae619	D	项次	seq_no	项次	IDENTIFIER	ACTIVE	4	2026-05-08 09:07:54.602727+00	2026-05-08 09:07:54.602727+00	IMPORTANT	f	t
a1314be7-d509-478f-a5f9-d1d3ed22a011	209dde9a-d8c5-4e6c-b2eb-02fa0d87aeae	A	HF 料号	hf_part_no	HF 料号	IDENTIFIER	ACTIVE	0	2026-05-17 03:19:59.793876+00	2026-05-17 03:19:59.793876+00	NORMAL	f	f
a13ca760-6990-4483-8126-ef1ad7e76848	1d0b619f-b5db-4cef-9a34-625f7d17d493	F	镀层厚度（μm）	layer_thickness_um	镀层厚度（μm） · 电镀方案 Excel F	VALUE	ACTIVE	6	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
a191145d-620b-4250-bf46-e1f56782aa38	03727943-3c6f-48c0-89a3-75f85c712ece	E	模具台账/工装编号	tooling_no	模具台账/工装编号 · Excel H	IDENTIFIER	ACTIVE	5	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
a1a4d104-d24b-4ed6-bedf-661b0c6d404a	480c784f-60da-4213-ab90-1f5384a4048d	H	货币	currency	货币	VALUE	ACTIVE	80	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
a20e1f22-4f11-4f10-9f6d-1a10825e0b40	81c9b0b6-67d3-4ae0-9b4c-3cc46b94d22c	D	品名	dim_input_material_name	品名	VALUE	ACTIVE	4	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	NORMAL	f	f
a23d7891-a95b-444f-997b-fa16611605f0	5341c659-a256-432c-9019-d2007242d9d5	D	宏丰料号	hf_part_no	宏丰料号	VALUE	ACTIVE	40	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	t
a2a3d962-5476-48d1-9e11-392758bed65f	18b0f7e0-3eba-45c2-86d2-30b33c5818f1	I	费用	fee_value	费用	VALUE	ACTIVE	9	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	f	f
a2d01ad5-986d-4826-8688-df1b7ba6e8fa	bd76770f-d5bb-46a9-9718-1bb632ea76da	G	状态	status_code	状态	VALUE	ACTIVE	70	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
a33c22d1-f669-4705-a0d7-a8775638249c	ddd191d3-e515-4371-be64-bbd9ecef990f	D	组装工序	assembly_process	组装工序	VALUE	ACTIVE	40	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
a35c85f0-650f-492d-83be-b4c8c6e2d594	81c9b0b6-67d3-4ae0-9b4c-3cc46b94d22c	G	二级项次	dim_sub_seq_no	二级项次	IDENTIFIER	ACTIVE	7	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	IMPORTANT	t	t
a35f8def-4c16-4d76-9193-5263eb5b191a	a099b527-a5fd-41a6-bc2f-1cec035f8491	B	项次	seq_no	项次	IDENTIFIER	ACTIVE	2	2026-05-06 06:50:56.611077+00	2026-05-14 05:51:08.35662+00	CRITICAL	t	f
a376f3f0-cd9f-45f8-9df3-f30cd6be28ae	7fc4865d-d106-42e3-8712-a1da9d2efc40	S	电镀成本	plating_cost	电镀成本	VALUE	ACTIVE	19	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
a392d1d0-f6fd-4a7e-bd25-5493dd428472	404aec01-831c-4937-b7ed-f6fc78de93bd	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
a410409d-7ffe-4ada-af51-ca4eb46f562d	1d0b619f-b5db-4cef-9a34-625f7d17d493	G	电镀要求	requirement	电镀要求 · 电镀方案 Excel G	VALUE	ACTIVE	7	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
a4758c22-ad30-4663-ad05-19e34d3eb195	209dde9a-d8c5-4e6c-b2eb-02fa0d87aeae	F	配方类型	recipe_type	配方类型	VALUE	ACTIVE	5	2026-05-17 03:19:59.793876+00	2026-05-17 03:19:59.793876+00	NORMAL	f	f
a4d999ca-94f8-422a-9f58-503b3c77450b	de3240ab-f932-4f9d-8f52-4283b6d114a9	E	计量单位	unit	计量单位	IDENTIFIER	ACTIVE	5	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	f	f
a63bfa14-4358-4436-bd62-45a0170ed457	404aec01-831c-4937-b7ed-f6fc78de93bd	H	不良率(%)	defect_rate	不良率(%)	VALUE	ACTIVE	8	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
a6961e13-6267-412f-b9e4-adf27a29a3d7	a099b527-a5fd-41a6-bc2f-1cec035f8491	G	工序编号	process_no	工序编号	VALUE	ACTIVE	7	2026-05-06 06:50:56.611077+00	2026-05-06 06:50:56.611077+00	IMPORTANT	t	f
a6a541d0-ba0c-4f20-b270-cda4a96f6ca6	08602f0d-23a3-411b-8fe3-662981825bed	J	取用的耗材版本	ref_calc_version	取用的耗材版本	VALUE	ACTIVE	10	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
a7876ebf-cff8-4da4-97ed-c4525821c21e	3beb8382-5ec8-4116-861f-93477300cafc	F	计价单位	price_unit	计价单位	VALUE	ACTIVE	60	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
a800f1aa-d3bd-40ad-8680-a92e6f2ced72	15b60523-ca1a-42b5-912e-790918d1c87a	B	单重（g/pcs）	weight_g_per_pcs	单重（g/pcs） · Excel B	VALUE	ACTIVE	2	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
a9217654-a0b5-4cc6-86be-9426987bac2d	ded337e3-9262-4bc3-9bc9-01eb8f5e8107	C	来料料号	input_material_no	来料料号	VALUE	ACTIVE	3	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
aaa6874c-2d5f-4610-b50e-abb371361abd	ed6e3a65-42c1-440a-a380-79ef3eb3cb22	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	CRITICAL	t	t
aae28964-4de0-474a-bcc3-523ddc003f7c	7aabce5c-c9b2-41d1-9270-685651cf6854	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	10	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	t
ab264ccb-3bb3-43b7-866f-4a327571fcc4	4432b3b5-fe3b-4c11-b1b8-078abafc95fa	C	元素代码	element_code	元素代码 · Excel F	IDENTIFIER	ACTIVE	3	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
abeee408-c570-4d20-a307-c940a59ca4b0	311825c4-bc43-44d3-902c-6d95406f51e0	C	材质名称	name	材质名称	VALUE	ACTIVE	30	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	IMPORTANT	f	t
ad0b562b-8be6-4dcb-a060-2e236971370d	2d1cb78f-442b-421b-9c77-85c7df699d3d	A	宏丰料号	hf_part_no	宏丰料号 · Excel A	IDENTIFIER	ACTIVE	1	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
af0a3488-d3f2-47d4-a9ed-61277021ab88	8a1ce500-5181-4001-9001-100000000001	K	利润	profit	利润	VALUE	ACTIVE	110	2026-05-15 08:15:33.302266+00	2026-05-15 08:15:33.302266+00	NORMAL	t	f
af7282f2-c257-4416-83d4-3f9502db1f16	9a9932c7-3a1c-4c4d-bb62-b2d54c2a78d7	G	值	fee_value	值	VALUE	ACTIVE	7	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	NORMAL	f	f
af761a8b-087b-40c3-b540-19acb97c7c1d	08602f0d-23a3-411b-8fe3-662981825bed	G	耗材成本单价	unit_price	耗材成本单价	VALUE	ACTIVE	7	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	IMPORTANT	t	t
afb346e5-080d-48dc-8a87-3e963f888189	273f3019-0a83-4808-b2a2-9bf3d88262f7	D	尺寸	dimension	尺寸	IDENTIFIER	ACTIVE	4	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	f	f
b01d11f8-82f3-49d6-bc17-b25e2e9d591d	7fc4865d-d106-42e3-8712-a1da9d2efc40	O	管理费	management_cost	管理费	VALUE	ACTIVE	15	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
b0211fd6-74b1-4103-be3f-69da047eb48b	bfda7921-615d-43dd-8347-ced1f7112731	G	组成件名称	component_name	组成件名称	VALUE	ACTIVE	7	2026-05-09 03:16:23.645395+00	2026-05-09 03:16:23.645395+00	NORMAL	f	f
b05757bc-61a4-4823-a8bf-bae24700d2d4	404aec01-831c-4937-b7ed-f6fc78de93bd	C	版本编号	plan_version	版本编号	IDENTIFIER	ACTIVE	3	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	f	f
b08adb78-02ed-4fc3-b45f-b081650f86be	ded337e3-9262-4bc3-9bc9-01eb8f5e8107	H	币种	currency	币种	VALUE	ACTIVE	8	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
b0bd1080-41b5-460a-9c02-91c0d46e2758	f21d41b6-af67-4642-bf54-c33169b9e9d9	E	工序编号	process_no	工序编号	IDENTIFIER	ACTIVE	5	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	IMPORTANT	t	t
b0d241e5-9ab7-4235-841c-be97c1eba1ea	5cbaaaed-a029-493f-b6de-4cfbf9a24393	G	折旧单价	unit_price	折旧单价	VALUE	ACTIVE	7	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	IMPORTANT	t	t
b1c280f3-b8cd-47d9-9094-60f0a743ad82	cb737a06-7c69-4d8f-b7f0-73b510352ae3	G	要素名称	dim_element_name	要素名称	VALUE	ACTIVE	7	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	IMPORTANT	t	t
b4f4bcb3-891f-42c9-aafb-2ebdb397012f	89997608-0f41-49a9-99f9-6221fd35a875	M	涨幅单位	rise_unit	涨幅单位	VALUE	ACTIVE	130	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
b5b7d8e3-a778-493f-bc60-dff5b7dd66a5	2d1cb78f-442b-421b-9c77-85c7df699d3d	J	来料损耗率（%）	output_loss_rate	来料损耗率（%） · Excel M	VALUE	ACTIVE	10	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
b5e64a60-8267-46af-b8a3-f90ef0a2ef15	03727943-3c6f-48c0-89a3-75f85c712ece	D	项次	seq_no	项次 · Excel G	VALUE	ACTIVE	4	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
b6b99cd9-2014-4f9c-8baf-f8c37cc5aebf	c8fc519b-1f87-4ac8-b022-b94479bb1310	L	货币(涨幅)	rise_currency	货币(涨幅)	VALUE	ACTIVE	120	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
b7207012-0c60-4b0f-82bf-4becd177a332	5cbaaaed-a029-493f-b6de-4cfbf9a24393	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	IMPORTANT	t	t
b74fe681-6f79-4c3f-b37c-6c868abd219e	1171806d-f555-46df-8ed6-5d26271fbb0e	A	宏丰料号	hf_part_no__costing	宏丰料号	IDENTIFIER	ACTIVE	10	2026-05-07 12:12:39.334863+00	2026-05-07 12:12:39.334863+00	NORMAL	f	f
b76c734c-d4a0-421e-9243-d236f1f4fe2e	404aec01-831c-4937-b7ed-f6fc78de93bd	E	电镀材料费	plating_material_fee	电镀材料费	VALUE	ACTIVE	5	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	f	f
b8b2b36e-4f34-4559-ab93-7e88ebc7e991	26760bbe-6235-460a-a4ca-b88ba2945248	H	毛用量	gross_qty	毛用量	VALUE	ACTIVE	80	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
b91fbee3-8faf-491f-b8a7-b884657bec18	8a1ce500-5181-4001-9001-100000000001	L	总成本	total_cost	总成本	VALUE	ACTIVE	120	2026-05-15 08:15:33.302266+00	2026-05-15 08:15:33.302266+00	NORMAL	t	f
ba07bb72-ec51-4fe3-9f7c-16c0e1f2dec3	c8fc519b-1f87-4ac8-b022-b94479bb1310	D	投入料号名称	dim_input_material_name	投入料号名称	VALUE	ACTIVE	40	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
ba7b4eb8-9718-4eaa-90ab-63067c1ae7ad	209dde9a-d8c5-4e6c-b2eb-02fa0d87aeae	C	化学符号	symbol	化学符号	VALUE	ACTIVE	2	2026-05-17 03:19:59.793876+00	2026-05-17 03:19:59.793876+00	NORMAL	f	f
baa4e3fc-f2f1-4e31-ad16-054d76e58a6a	ddd191d3-e515-4371-be64-bbd9ecef990f	E	组成件项次	sub_seq_no	组成件项次	VALUE	ACTIVE	50	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
bc092dd5-837c-49f5-8f3f-7ed979b3453f	cce210eb-335c-4584-994b-7b3229aae619	E	元素	dim_element_name	元素	IDENTIFIER	ACTIVE	5	2026-05-08 09:07:54.602727+00	2026-05-08 09:07:54.602727+00	IMPORTANT	f	f
bc459d62-94d6-400b-a6a2-8fe378a7c0ad	8a1ce500-5181-4001-9001-100000000001	H	电镀料费	plating_material_fee	电镀料费	VALUE	ACTIVE	80	2026-05-15 08:15:33.302266+00	2026-05-15 08:15:33.302266+00	NORMAL	t	f
bcedd937-784b-4f66-a199-865c154a2204	8a1ce500-5181-4001-9001-100000000001	G	电镀加工费	plating_process_fee	电镀加工费	VALUE	ACTIVE	70	2026-05-15 08:15:33.302266+00	2026-05-15 08:15:33.302266+00	NORMAL	t	f
bd750d94-af28-4079-bac9-9f4e33e54f8c	6b04aeeb-84c2-42bf-a20b-42e86ba7d244	B	版本	version	版本	IDENTIFIER	ACTIVE	2	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	CRITICAL	t	t
bec665b2-6cd0-42c9-9e1d-34fb9ff291f9	dc819072-9e21-4e95-bb06-ea55ac0c61d1	F	工序名称	process_name	工序名称	VALUE	ACTIVE	6	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
bf1a79d7-5050-4b77-a29f-73ade441f6b9	ded337e3-9262-4bc3-9bc9-01eb8f5e8107	I	计量单位	unit	计量单位	VALUE	ACTIVE	9	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
bf8622e0-9c73-4492-93aa-0bdece34a166	dc819072-9e21-4e95-bb06-ea55ac0c61d1	I	计量单位	unit	计量单位	VALUE	ACTIVE	9	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
c055088d-0e5b-4aad-af2b-c64e7f11a645	4a745dcf-7a2b-42de-9929-cb0eda525b2d	N	币种	currency	币种	VALUE	ACTIVE	14	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
c076939b-d3c0-43ca-960f-01b293ac3b80	c8fc519b-1f87-4ac8-b022-b94479bb1310	H	计价单位	price_unit	计价单位	VALUE	ACTIVE	80	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
c1e836fd-e873-41af-926b-9b0625de2b76	1171806d-f555-46df-8ed6-5d26271fbb0e	D	电镀加工费	plating_process_fee__costing	电镀加工费	VALUE	ACTIVE	40	2026-05-07 12:12:39.334863+00	2026-05-07 12:12:39.334863+00	NORMAL	f	f
c1fc6fe0-4c66-4ef7-91db-968627b63875	3beb8382-5ec8-4116-861f-93477300cafc	G	拒收率	reject_rate	拒收率	VALUE	ACTIVE	70	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
c2576653-c2cf-49d2-9ac9-b8a445235363	094dedfc-758f-444c-9375-4d80d858a09a	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-07 10:58:00.643135+00	2026-05-07 10:58:00.643135+00	IMPORTANT	f	t
c2e59c6f-6dec-479c-a757-f5a6535d6667	b35e5d9b-b226-454c-a863-fe6b6c367972	E	工序编号	process_no	工序编号	IDENTIFIER	ACTIVE	5	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
c394bc53-dc87-4da6-aaff-3c9cc662616c	b35e5d9b-b226-454c-a863-fe6b6c367972	J	取用的耗材版本	ref_calc_version	取用的耗材版本	VALUE	ACTIVE	10	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
c3f3e86c-73bd-45e7-9bda-59af37819a20	ed6e3a65-42c1-440a-a380-79ef3eb3cb22	F	要素名称	dim_element_name	要素名称	VALUE	ACTIVE	6	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	IMPORTANT	t	t
c46b2ae9-37cc-45aa-9d67-8b7c0aa199bc	311825c4-bc43-44d3-902c-6d95406f51e0	D	规格标签	spec_label	规格标签	VALUE	ACTIVE	40	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	NORMAL	f	f
c51a78bb-40c4-457d-85a0-1c58482016d6	3830f5e3-b4cb-4fd0-8762-4d97a6a59535	H	计价单位	price_unit	计价单位	VALUE	ACTIVE	80	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
c51e9b65-7fad-4750-92a2-fd3275b839fb	baff34e9-0179-49e4-b81c-6225e9b17f98	B	序号	seq_no	序号	VALUE	ACTIVE	2	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
c5cab548-3cb7-412b-9938-419efaa86538	1d0b619f-b5db-4cef-9a34-625f7d17d493	D	电镀元素名称	element_attr	电镀元素名称 · 电镀方案 Excel D	VALUE	ACTIVE	4	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
c6ddae6a-dd63-4500-b9e0-d194ef6c1bdb	18b0f7e0-3eba-45c2-86d2-30b33c5818f1	B	一级项次	seq_no	一级项次	IDENTIFIER	ACTIVE	2	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	t	t
c79abd2e-aa78-41c7-8f90-c1acefa9b897	c44589f2-0c2a-40da-aad4-473f78c33c0b	B	项次	seq_no	项次	IDENTIFIER	ACTIVE	2	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	CRITICAL	t	t
c7ac9e70-93f3-4331-9b8a-b8977f921076	15b60523-ca1a-42b5-912e-790918d1c87a	C	是否有效	is_active	是否有效	VALUE	ACTIVE	3	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
c80d47dc-bb21-408c-9b55-bb58e06e91b0	eecfa1a5-f903-41fb-8044-03f15f3acf2a	A	来料料号	input_material_no	来料料号	IDENTIFIER	ACTIVE	1	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
c8a62045-9c02-4c56-ad16-dad8f4650297	90390eb2-f863-4d8f-b12f-8b7ab3a45537	F	是否锁定	is_locked	是否锁定	VALUE	ACTIVE	60	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	NORMAL	f	f
c9859ee3-e5d6-4af3-ac75-91d4391de86d	3beb8382-5ec8-4116-861f-93477300cafc	D	组装加工费	fee_value	组装加工费	VALUE	ACTIVE	40	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
ca46f6e5-4ee7-4b06-9fe5-a7dad4d9458a	3830f5e3-b4cb-4fd0-8762-4d97a6a59535	G	货币	currency	货币	VALUE	ACTIVE	70	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
ca83dbf9-53b0-4499-ada7-33976868aac4	5341c659-a256-432c-9019-d2007242d9d5	B	客户产品编号	customer_product_no	客户产品编号	IDENTIFIER	ACTIVE	20	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	t
ca9f3b0b-098c-415a-ae62-91b8c4a03992	c8fc519b-1f87-4ac8-b022-b94479bb1310	B	项次	seq_no	项次	VALUE	ACTIVE	20	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
cadfcc8b-0a92-4a77-ab1c-8e8737eb210d	7fc4865d-d106-42e3-8712-a1da9d2efc40	L	材料成本	material_cost	材料成本	VALUE	ACTIVE	12	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
cb450ae1-89c1-4972-9fa0-5b38846d1779	8a1ce500-5181-4001-9001-100000000001	A	纯材料成本	pure_material_cost	纯材料成本	VALUE	ACTIVE	10	2026-05-15 08:15:33.302266+00	2026-05-15 08:15:33.302266+00	NORMAL	t	f
cc507a9b-e321-46ef-9692-66ad3e59297f	bfda7921-615d-43dd-8347-ced1f7112731	I	组成单位	quantity_unit	组成单位	VALUE	ACTIVE	9	2026-05-09 03:16:23.645395+00	2026-05-09 03:16:23.645395+00	NORMAL	f	f
cd7f7ac3-0783-4d96-ba3c-c93d303553c6	89997608-0f41-49a9-99f9-6221fd35a875	E	值	fee_value	值	VALUE	ACTIVE	50	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
cd88c172-8706-4e21-8977-462eab18410a	89997608-0f41-49a9-99f9-6221fd35a875	D	投入料号名称	dim_input_material_name	投入料号名称	VALUE	ACTIVE	40	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
cfc2d0ae-e469-4764-beed-e9a31896476f	19f2bc23-ca5e-4297-a85a-283c4e4fcb6e	G	要素名称	dim_element_name	要素名称	VALUE	ACTIVE	7	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	t	t
d0f1326b-3926-4407-b461-49cae5b96589	ded337e3-9262-4bc3-9bc9-01eb8f5e8107	B	项次	process_no	项次	IDENTIFIER	ACTIVE	2	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
d1391cfd-3019-4d06-8e03-eb6d9476da8a	08602f0d-23a3-411b-8fe3-662981825bed	F	工序名称	process_name	工序名称	VALUE	ACTIVE	6	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
d1d5e78e-4fd7-466e-9318-1dfc2a0c9793	2acc46db-efad-46f4-ae69-59ddb2105a9a	N	货币	currency	货币	VALUE	ACTIVE	9	2026-05-09 05:03:31.312193+00	2026-05-09 05:03:31.312193+00	NORMAL	f	f
d227afd0-4bea-483a-99a6-28e5d2e02bad	1b0bda96-e8ca-4611-9072-6f76d2874fdf	F	要素名称	dim_element_name	要素名称	VALUE	ACTIVE	6	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	f	f
d23d5ce6-08e8-4cd6-945a-583972c8d038	7fc4865d-d106-42e3-8712-a1da9d2efc40	P	财务费	finance_cost	财务费	VALUE	ACTIVE	16	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
d296470c-d21b-4d18-92c2-556fa938ad8a	ddd191d3-e515-4371-be64-bbd9ecef990f	C	工序编号	process_code	工序编号	VALUE	ACTIVE	30	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
d32b93f7-a26b-44eb-8b9f-deae7b3c6ef8	e64f5ba2-fcaf-4e2b-a7c4-7aab96daa973	D	电镀加工费	plating_process_fee	电镀加工费	VALUE	ACTIVE	40	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
d3346efc-7079-480c-bf3c-4107da884ee7	bfda7921-615d-43dd-8347-ced1f7112731	H	组成数量	quantity	组成数量	VALUE	ACTIVE	8	2026-05-09 03:16:23.645395+00	2026-05-09 03:16:23.645395+00	NORMAL	t	f
d369bb51-d174-429b-9c57-794e3b971f82	bfda7921-615d-43dd-8347-ced1f7112731	C	工序编号	process_code	工序编号	VALUE	ACTIVE	3	2026-05-09 03:16:23.645395+00	2026-05-09 03:16:23.645395+00	IMPORTANT	f	f
d3fc1c08-ccf5-4fa7-a27b-f67f55bd929e	9a9932c7-3a1c-4c4d-bb62-b2d54c2a78d7	H	比例(%)	fee_ratio	比例(%)	VALUE	ACTIVE	8	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	IMPORTANT	t	f
d44f526b-bcd6-4c52-8ed4-9b8efd7f382b	a099b527-a5fd-41a6-bc2f-1cec035f8491	J	组成用量单位	input_unit	组成用量单位	VALUE	ACTIVE	10	2026-05-06 06:50:56.611077+00	2026-05-06 06:50:56.611077+00	NORMAL	f	f
d495c7e5-33f5-45a3-b275-803a182682cc	cb7d212b-6bdb-4798-8a24-9fdee86b069b	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
d4f2017f-5d99-4a06-ba18-921906f1aef0	c44589f2-0c2a-40da-aad4-473f78c33c0b	I	损耗率(%)	loss_rate	损耗率(%)	VALUE	ACTIVE	9	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	NORMAL	f	f
d53e929d-bd37-4452-a665-53562b93bf41	3beb8382-5ec8-4116-861f-93477300cafc	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	10	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	t
d5a9d572-1bda-4b32-bdc0-27353471a31e	8a1ce500-5181-4001-9001-100000000001	D	材料成本	material_cost	材料成本	VALUE	ACTIVE	40	2026-05-15 08:15:33.302266+00	2026-05-15 08:15:33.302266+00	NORMAL	t	f
d667d07c-2f2c-4107-b99a-23169f2d2cec	c8fc519b-1f87-4ac8-b022-b94479bb1310	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	10	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	t
d6da24ab-6d50-42e8-be38-a968d30fd349	cb737a06-7c69-4d8f-b7f0-73b510352ae3	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	CRITICAL	t	t
d7c2ca85-13bf-4cc7-85ea-64e3ee85108f	5341c659-a256-432c-9019-d2007242d9d5	G	报价货币	quote_currency	报价货币	VALUE	ACTIVE	70	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
d931b508-4b2c-4a87-8d2d-1f2ba768a13b	f21d41b6-af67-4642-bf54-c33169b9e9d9	I	计量单位	unit	计量单位	VALUE	ACTIVE	9	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
d960cf68-a651-44b2-bfda-8bff046820bf	cbd60f9d-7ba8-4c7b-ac80-82d769172b15	A	工序代码	process_code	工序代码	IDENTIFIER	ACTIVE	10	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	CRITICAL	f	t
d9c962ba-9db0-47e2-bccc-303b7b2b0538	9a9932c7-3a1c-4c4d-bb62-b2d54c2a78d7	F	要素名称	dim_element_name	要素名称	VALUE	ACTIVE	6	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	IMPORTANT	t	t
d9d8c08e-eaeb-4665-a0d9-e17c7fe571b5	404aec01-831c-4937-b7ed-f6fc78de93bd	G	计价单位	price_unit	计价单位	VALUE	ACTIVE	7	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
da11ae7b-46b3-401f-a43e-2719fe018f53	673f0dad-8c0a-4418-8617-96d9a4ae432f	D	参考汇率	market_rate	参考汇率	VALUE	ACTIVE	4	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	f	f
da131354-db0c-4403-8ca9-83b18094bbd0	9a9932c7-3a1c-4c4d-bb62-b2d54c2a78d7	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	CRITICAL	t	t
da986938-7dc4-459c-a925-3257c8b92eb2	ddd191d3-e515-4371-be64-bbd9ecef990f	L	单价	unit_price	单价	VALUE	ACTIVE	120	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
db130e85-b967-4334-b991-ee6538cefb2c	81c9b0b6-67d3-4ae0-9b4c-3cc46b94d22c	J	比例(%)	fee_ratio	比例(%)	VALUE	ACTIVE	10	2026-05-06 06:40:38.792093+00	2026-05-06 06:40:38.792093+00	CRITICAL	t	t
db62fc67-dbf8-4821-8ad9-87d0ce79f801	a099b527-a5fd-41a6-bc2f-1cec035f8491	H	工序名称	process_name	工序名称	VALUE	ACTIVE	8	2026-05-06 06:50:56.611077+00	2026-05-06 06:50:56.611077+00	NORMAL	f	f
dbf2c810-5bc6-47f0-9ad5-8e956be10758	c7ceefeb-acf7-48f1-8452-f53a2f01116e	G	计量单位	unit	计量单位 · Excel I	VALUE	ACTIVE	7	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
dcb438f6-f877-4036-a9c7-58155bcd2ccf	19f2bc23-ca5e-4297-a85a-283c4e4fcb6e	H	比例(%)	fee_ratio	比例(%)	VALUE	ACTIVE	8	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	t	f
dd1e95e0-b62f-4d02-92c7-9264d46afec4	ddd191d3-e515-4371-be64-bbd9ecef990f	M	运费	freight	运费	VALUE	ACTIVE	130	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
de01d2bd-950e-4147-a389-e6c3f1c6d123	89997608-0f41-49a9-99f9-6221fd35a875	L	货币(涨幅)	rise_currency	货币(涨幅)	VALUE	ACTIVE	120	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
de2ef59b-c13d-4fcf-836e-6f528201e281	02b300d8-7d3e-46b8-8171-16407c68c0ee	K	是否有效	is_active	是否有效	VALUE	ACTIVE	11	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
df68fc2b-3575-4071-9823-678571441333	c8fc519b-1f87-4ac8-b022-b94479bb1310	C	投入料号	dim_input_material_no	投入料号	VALUE	ACTIVE	30	2026-05-07 09:18:46.001079+00	2026-05-07 09:18:46.001079+00	NORMAL	f	f
e06d9b1e-12c4-4121-8685-538618c78924	5cbaaaed-a029-493f-b6de-4cfbf9a24393	J	取用的计算版本	ref_calc_version	取用的计算版本	VALUE	ACTIVE	10	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
e2065f3b-9487-4440-8fc2-475d82618859	03727943-3c6f-48c0-89a3-75f85c712ece	G	寿命（次）	process_count	寿命（次） · Excel J	VALUE	ACTIVE	7	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
e332175b-e733-491f-a2ac-511e9dffd84a	094dedfc-758f-444c-9375-4d80d858a09a	B	序号	seq_no	序号	VALUE	ACTIVE	2	2026-05-07 10:58:00.643135+00	2026-05-07 10:58:00.643135+00	NORMAL	f	f
e3ec0ce9-0601-41ee-9c89-4755707132ad	c44589f2-0c2a-40da-aad4-473f78c33c0b	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	CRITICAL	t	t
e40b3137-eaa3-42f8-944e-937db12a8569	18b0f7e0-3eba-45c2-86d2-30b33c5818f1	C	来料料号	dim_input_material_no	来料料号	VALUE	ACTIVE	3	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
e48dc4e4-257b-4b29-8e47-df1700675884	673f0dad-8c0a-4418-8617-96d9a4ae432f	C	核价汇率	costing_rate	核价汇率	VALUE	ACTIVE	3	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	t	f
e48f3123-0e80-4c70-b64b-2bb8d83e2bf8	9a9932c7-3a1c-4c4d-bb62-b2d54c2a78d7	J	计价单位	price_unit	计价单位	VALUE	ACTIVE	10	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	NORMAL	f	f
e4bdf79a-4847-4fba-bf56-6a3123918644	de3240ab-f932-4f9d-8f52-4283b6d114a9	B	核价单价	costing_price	核价单价	VALUE	ACTIVE	2	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	t	f
e5b6b20f-ad9b-40d9-a26d-99fdded41371	bfda7921-615d-43dd-8347-ced1f7112731	B	项次	seq_no	项次	IDENTIFIER	ACTIVE	2	2026-05-09 03:16:23.645395+00	2026-05-14 05:56:19.198887+00	IMPORTANT	f	f
e6a19cfe-73d8-4e9b-ac6c-95b4991a0149	1d0b619f-b5db-4cef-9a34-625f7d17d493	H	是否有效	is_active	是否有效	VALUE	ACTIVE	8	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
e6a32891-4f22-4e03-9bde-8e7941ef1768	480c784f-60da-4213-ab90-1f5384a4048d	G	单次固定年降值	fee_value	单次固定年降值	VALUE	ACTIVE	70	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
e6d531a5-ca31-48cc-b026-15c89a1055ae	480c784f-60da-4213-ab90-1f5384a4048d	B	项次	seq_no	项次	VALUE	ACTIVE	20	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
e8865f94-77a3-42ed-82a6-5c4e58c2f43a	19f2bc23-ca5e-4297-a85a-283c4e4fcb6e	E	项次	seq_no	项次	IDENTIFIER	ACTIVE	5	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	t	t
e9cc7a5a-d862-40aa-821a-b02fd57ebf03	a099b527-a5fd-41a6-bc2f-1cec035f8491	L	底数单位	output_unit	底数单位	VALUE	ACTIVE	12	2026-05-06 06:50:56.611077+00	2026-05-06 06:50:56.611077+00	NORMAL	f	f
ea28f8f8-d6d6-4060-bb16-e4b67f6b0b25	74e02284-08b3-42df-9193-50f0c382b331	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
ea778ff9-cdea-4797-99a2-f06179fcbadf	4a745dcf-7a2b-42de-9929-cb0eda525b2d	H	模具台账	tooling_no	模具台账	VALUE	ACTIVE	8	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
ea914393-9a6f-4b53-b5f7-4b78ef133a5e	cb7d212b-6bdb-4798-8a24-9fdee86b069b	F	要件描述	requirement_desc	要件描述	VALUE	ACTIVE	6	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
eb4ff6e8-d141-4480-be28-ee2bd40d356f	7fc4865d-d106-42e3-8712-a1da9d2efc40	N	加工费	processing_cost	加工费	VALUE	ACTIVE	14	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
eb68a30c-d1d8-497e-9028-27e397370398	cb7d212b-6bdb-4798-8a24-9fdee86b069b	D	序号	seq_no	序号	VALUE	ACTIVE	4	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
eb958d28-70e0-434c-9ce3-9a26562ff43d	ce553849-1938-4084-ba5e-684b54852123	J	电镀要求	plating_requirement	电镀要求	VALUE	ACTIVE	10	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	NORMAL	f	f
eba9b497-3380-4880-8469-2027a7336038	4432b3b5-fe3b-4c11-b1b8-078abafc95fa	B	项次	seq_no	项次 · Excel E	VALUE	ACTIVE	2	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
ebb9c6df-34c9-4bdd-bde7-d77b8bf46484	6c1eebc1-bfb6-405f-93d6-7ae2ce3216e6	I	是否有效	is_active	是否有效	VALUE	ACTIVE	9	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
eca100d3-6b35-4ed7-830d-c54d7504075c	c7ceefeb-acf7-48f1-8452-f53a2f01116e	C	工序名称	process_name	工序名称 · Excel F	VALUE	ACTIVE	3	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
ecb2fd60-4958-4f57-abd0-ae95cb584168	ce553849-1938-4084-ba5e-684b54852123	C	项次	seq_no	项次	IDENTIFIER	ACTIVE	3	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	CRITICAL	t	t
ecee15a7-f301-4ee1-986d-38815c57d26f	4a745dcf-7a2b-42de-9929-cb0eda525b2d	M	模具工装成本单价	unit_price	模具工装成本单价	VALUE	ACTIVE	13	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	f	f
ef170d88-b7f8-4e29-94c7-a382e391c589	18b0f7e0-3eba-45c2-86d2-30b33c5818f1	J	币种	currency	币种	VALUE	ACTIVE	10	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
ef81f719-d865-44bc-b869-e0a2ba731912	3830f5e3-b4cb-4fd0-8762-4d97a6a59535	D	年降顺序	dim_sub_seq_no	年降顺序	VALUE	ACTIVE	40	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
ef97be18-8eae-4abb-bee1-78ce4e2ed6be	ce553849-1938-4084-ba5e-684b54852123	A	方案编号	plan_code	方案编号	IDENTIFIER	ACTIVE	1	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	CRITICAL	t	t
f06f0519-2303-434c-ac99-a48ff1933abf	c7ceefeb-acf7-48f1-8452-f53a2f01116e	B	工序编号	process_no	工序编号 · Excel E	IDENTIFIER	ACTIVE	2	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
f0adcc94-5475-482e-800d-73da1f4a4dca	404aec01-831c-4937-b7ed-f6fc78de93bd	B	电镀方案编号	plating_plan_code	电镀方案编号	IDENTIFIER	ACTIVE	2	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	f	f
f162db6c-f56e-4768-b2ae-f9b1a3a36486	1d0b619f-b5db-4cef-9a34-625f7d17d493	B	版本	version_number	版本 · 电镀方案 Excel B	IDENTIFIER	ACTIVE	2	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	t
f1db58eb-5caa-49b2-a838-56c7ac0f5103	02b300d8-7d3e-46b8-8171-16407c68c0ee	F	工序名称	process_name	工序名称	VALUE	ACTIVE	6	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
f213530b-e2a0-4eb4-aabd-59a9ddf2212e	6c1eebc1-bfb6-405f-93d6-7ae2ce3216e6	D	设计加工费	design_proc_fee	设计加工费	VALUE	ACTIVE	4	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
f2a6acd0-b464-4a16-9205-98d891794a14	6c1eebc1-bfb6-405f-93d6-7ae2ce3216e6	E	设计材料费	design_material_fee	设计材料费	VALUE	ACTIVE	5	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
f2bd48d2-7402-489a-9230-cd163217c4a6	7fc4865d-d106-42e3-8712-a1da9d2efc40	J	汇率价格版本	exchange_version_number	汇率价格版本	IDENTIFIER	ACTIVE	10	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
f2ef2a0b-7f34-48ad-8c91-633a9601cdf2	ddd191d3-e515-4371-be64-bbd9ecef990f	J	组成数量	quantity	组成数量	VALUE	ACTIVE	100	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
f332827d-5186-4df3-9b94-4dca6c66c606	5cbaaaed-a029-493f-b6de-4cfbf9a24393	E	工序编号	process_no	工序编号	IDENTIFIER	ACTIVE	5	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	IMPORTANT	t	t
f3b35dfa-ce1d-4d32-8d87-e27922c8c672	5cbaaaed-a029-493f-b6de-4cfbf9a24393	I	计量单位	unit	计量单位	VALUE	ACTIVE	9	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
f3f7fb61-6f6e-432a-bd3c-7e17e476e7e7	89997608-0f41-49a9-99f9-6221fd35a875	J	材料结算涨幅比例(%)	settlement_rise_ratio	材料结算涨幅比例(%)	VALUE	ACTIVE	100	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
f43766bd-ddfe-4c48-9244-f76a90b95ae2	273f3019-0a83-4808-b2a2-9bf3d88262f7	G	货币	currency	货币	IDENTIFIER	ACTIVE	7	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	f	f
f447f1b1-cca2-4578-8896-c7ca34b1593c	dc819072-9e21-4e95-bb06-ea55ac0c61d1	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
f4d89916-1ab1-455c-8277-be2fe1d5438e	c7ceefeb-acf7-48f1-8452-f53a2f01116e	F	币种	currency	币种 · Excel H	VALUE	ACTIVE	6	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
f5279b8a-ee42-4d05-8c20-64499ef960cf	8abc1a92-2bc1-4b4d-b916-a04f42d36be7	D	值	fee_value	值	VALUE	ACTIVE	4	2026-05-06 08:37:40.128301+00	2026-05-06 08:37:40.128301+00	NORMAL	f	f
f52de439-3711-46f7-9c95-17a07474d275	81c9b0b6-67d3-4ae0-9b4c-3cc46b94d22c	B	一级项次	seq_no	一级项次	IDENTIFIER	ACTIVE	2	2026-05-06 06:40:38.792093+00	2026-05-15 08:39:03.645148+00	CRITICAL	t	f
f52e6237-08f9-4f6d-ae9b-4e9763939ca7	e64f5ba2-fcaf-4e2b-a7c4-7aab96daa973	B	电镀方案编码	plating_plan_code	电镀方案编码	VALUE	ACTIVE	20	2026-04-29 01:42:45.400825+00	2026-04-29 01:42:45.400825+00	NORMAL	f	f
f5bb7c14-cc70-41ea-bb50-6962803c9c8e	7fc4865d-d106-42e3-8712-a1da9d2efc40	W	计量单位	weight_unit	计量单位	VALUE	ACTIVE	22	2026-05-05 12:33:32.180651+00	2026-05-05 12:33:32.180651+00	NORMAL	f	f
f6eaac23-f5ed-4c73-a059-d6c214ee2ef2	5f6e5f76-8b48-4aac-b477-61e1139253a2	E	工序编号	process_no	工序编号	IDENTIFIER	ACTIVE	5	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	CRITICAL	t	t
f82c7e0f-1bfc-4cbf-b346-f6b598e56fad	dc819072-9e21-4e95-bb06-ea55ac0c61d1	G	人工标准单价	unit_price	人工标准单价	VALUE	ACTIVE	7	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	t	t
f84543cc-7075-4e3e-a6d6-1bdf2f839881	8a1ce500-5181-4001-9001-100000000001	F	加工费	process_fee	加工费	VALUE	ACTIVE	60	2026-05-15 08:15:33.302266+00	2026-05-15 08:15:33.302266+00	NORMAL	t	f
f98b5ee0-5830-4f36-9599-f91da22f9566	209dde9a-d8c5-4e6c-b2eb-02fa0d87aeae	D	材质名称	name	材质名称	VALUE	ACTIVE	3	2026-05-17 03:19:59.793876+00	2026-05-17 03:19:59.793876+00	NORMAL	f	f
fa3ae605-7a55-4fc2-a280-8abc22d37c4a	2acc46db-efad-46f4-ae69-59ddb2105a9a	O	计价单位	price_unit	计价单位	VALUE	ACTIVE	10	2026-05-09 05:03:31.312193+00	2026-05-09 05:03:31.312193+00	NORMAL	f	f
fa4e8011-1298-49fe-83d1-dbbb817f59f7	273f3019-0a83-4808-b2a2-9bf3d88262f7	F	市场参考价	market_ref_price	市场参考价	VALUE	ACTIVE	6	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	f	f
fa86ce7e-9372-432c-b26d-730fee44f851	cce210eb-335c-4584-994b-7b3229aae619	A	宏丰料号	hf_part_no	宏丰料号	IDENTIFIER	ACTIVE	1	2026-05-08 09:07:54.602727+00	2026-05-08 09:07:54.602727+00	IMPORTANT	f	t
fab76ec3-54dc-4290-9709-1c22cea1deb4	3830f5e3-b4cb-4fd0-8762-4d97a6a59535	C	组装工序	dim_assembly_process	组装工序	VALUE	ACTIVE	30	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
fb39f7cb-c8f2-4801-9201-a6786034cc7a	6c1eebc1-bfb6-405f-93d6-7ae2ce3216e6	F	币种	currency	币种	VALUE	ACTIVE	6	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
fb4330a9-02fe-4a31-9be3-b335eb57e75d	2acc46db-efad-46f4-ae69-59ddb2105a9a	B	项次	seq_no	项次	IDENTIFIER	ACTIVE	2	2026-05-09 05:03:31.312193+00	2026-05-09 05:03:31.312193+00	IMPORTANT	f	t
fc08f108-d08c-4e61-bb23-d6a038b39b7f	673f0dad-8c0a-4418-8617-96d9a4ae432f	A	原货币	from_currency	原货币	IDENTIFIER	ACTIVE	1	2026-05-05 11:45:27.027202+00	2026-05-05 11:45:27.027202+00	NORMAL	f	f
fccd7e7f-7000-452a-b86b-35c890049e83	480c784f-60da-4213-ab90-1f5384a4048d	E	年降顺序	dim_sub_seq_no	年降顺序	VALUE	ACTIVE	50	2026-04-29 01:48:56.81694+00	2026-04-29 01:48:56.81694+00	NORMAL	f	f
fd03e6c2-8bff-4f2d-82ff-3166ab34e744	08602f0d-23a3-411b-8fe3-662981825bed	H	币种	currency	币种	VALUE	ACTIVE	8	2026-05-06 06:06:00.835758+00	2026-05-06 06:06:00.835758+00	NORMAL	f	f
fd3f440f-e618-49c5-9b55-e313c401d46a	094dedfc-758f-444c-9375-4d80d858a09a	E	回收折扣(%)	fee_ratio	回收折扣	VALUE	ACTIVE	5	2026-05-07 10:58:00.643135+00	2026-05-07 10:58:00.643135+00	IMPORTANT	t	f
fd46670c-af34-4bab-b8f5-532755779684	2d1cb78f-442b-421b-9c77-85c7df699d3d	H	底数	output_qty	底数 · Excel K	VALUE	ACTIVE	8	2026-05-06 01:19:24.591637+00	2026-05-06 01:19:24.591637+00	NORMAL	f	f
fd70a346-b5c7-479d-bb9d-1d1f7ad6756c	aa232879-b6cd-4cc3-ad6e-d395df9ccc44	B	工艺名称	name	工艺名称	VALUE	ACTIVE	20	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	IMPORTANT	f	t
ff3087d9-3c79-44f9-b037-c488f0c1d77d	74e02284-08b3-42df-9193-50f0c382b331	D	外加工费用	unit_price	外加工费用	VALUE	ACTIVE	4	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	IMPORTANT	t	f
ffb09922-ac1f-489d-8f07-326bd4c68a0e	dc819072-9e21-4e95-bb06-ea55ac0c61d1	K	是否有效	is_active	是否有效	VALUE	ACTIVE	11	2026-05-09 11:29:29.707176+00	2026-05-09 11:29:29.707176+00	NORMAL	f	f
\.


--
-- Data for Name: basic_data_change_log; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.basic_data_change_log (id, table_name, record_id, business_key, change_type, field_changes, version_before, version_after, import_record_id, changed_by, changed_at, remarks, created_at, updated_at, created_by, updated_by, field_name, old_value, new_value, customer_id, hf_part_no, importance, affects_calculation, change_source, note) FROM stdin;
\.


--
-- Data for Name: bnf_table_meta; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.bnf_table_meta (table_name, is_view, template_kind, display_name, picker_visible, last_synced) FROM stdin;
\.


--
-- Data for Name: capacity; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.capacity (id, material_no, material_name, specification, dimension, process_no, process_name, resource_group_no, resource_group_name, production_type, fixed_lead_time, variable_time, variable_time_batch, capacity_unit, default_defect_rate, cost_type, fixed_cost, cost_ratio, annual_discount_factor, calc_version, is_effective, created_at, updated_at, created_by, updated_by, currency, seq_no, version_no, is_current, system_type, production_no, source) FROM stdin;
\.


--
-- Data for Name: comparison_tag; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.comparison_tag (id, code, label, group_name, group_sort_order, tag_sort_order, is_builtin, status, description, created_at, updated_at) FROM stdin;
3d183ec2-b185-49cb-b840-491ed63084d0	OVERHEAD_COST	管理费用	其他费用	3	1	t	ARCHIVED	\N	2026-04-23 07:37:13.30961+00	2026-05-07 07:41:53.412278+00
3e13c5d5-ad69-4957-b181-ffddca780159	UNIT_TOTAL_COST	单位总成本	汇总	9	1	t	ACTIVE	\N	2026-04-23 07:37:13.30961+00	2026-04-23 07:37:13.30961+00
406a9d98-f8c6-4230-a81b-1237042a5bc1	MATERIAL_COST_TOTAL	总材料成本	材料成本维度	1	99	t	ARCHIVED	\N	2026-04-23 07:37:13.30961+00	2026-05-07 07:41:53.412278+00
4106b250-75b8-47f1-83c7-a1ad8544f8c4	FINANCE_FEE	财务费	加价	20	20	t	ACTIVE	财务费. 来源 Excel 模板列 K = 加价基数 × 财务费比例	2026-05-07 07:41:53.412278+00	2026-05-07 07:41:53.412278+00
4344a696-e645-41a0-a945-f525ed0643e0	SETUP_COST	设置成本	加工费维度	2	3	t	ARCHIVED	\N	2026-04-23 07:37:13.30961+00	2026-05-07 07:41:53.412278+00
467eadc8-011e-4002-9d52-8e9e5eefe9de	TOTAL_CNY_KG	总成本(CNY/KG)	总成本	30	10	t	ACTIVE	总成本 CNY/KG. 来源 Excel 模板列 P = 加价基数 + 管理费 + 财务费 + 利润 + 税费	2026-05-07 07:41:53.412278+00	2026-05-07 07:41:53.412278+00
4fcd6216-fac0-4a87-91e0-b25793faeccd	TOTAL_USD_KG	总成本(USD/KG)	总成本	30	30	t	ACTIVE	总成本 USD/KG. 来源 Excel 模板列 T = P × 全局变量[CNY→USD 汇率]	2026-05-07 07:41:53.412278+00	2026-05-07 07:41:53.412278+00
51952b38-a732-4d50-9aa2-4f6ebb09f350	CUSTOM_COST	自定义费用	其他费用	3	99	t	ARCHIVED	\N	2026-04-23 07:37:13.30961+00	2026-05-07 07:41:53.412278+00
63c8ac18-4a0f-4b6c-bb0b-a16b07aa8c05	PACKAGING_COST	包装费	其他费用	3	2	t	ARCHIVED	\N	2026-04-23 07:37:13.30961+00	2026-05-07 07:41:53.412278+00
67c66bb0-0d4f-4d3b-9af3-fc9a310eb9a4	PROFIT	利润	加价	20	30	t	ACTIVE	利润. 来源 Excel 模板列 M = 加价基数 × 利润比例	2026-05-07 07:41:53.412278+00	2026-05-07 07:41:53.412278+00
8283e695-b498-4ce1-8ea6-e35f0e00943a	TOTAL	总价	汇总	9	2	t	ACTIVE	\N	2026-04-23 07:37:13.30961+00	2026-04-23 07:37:13.30961+00
84de5cc6-005b-44f6-ac3e-3eb7813a2da2	MATERIAL_COST_AG	Ag材料成本	材料成本维度	1	1	t	ARCHIVED	\N	2026-04-23 07:37:13.30961+00	2026-05-07 07:41:53.412278+00
874909e0-b042-455c-ae78-3e48a771ce06	PLATING_COST	电镀成本	成本明细	10	40	t	ACTIVE	料号电镀成本. 来源 Excel 模板列 E = (电镀加工费 + 电镀材料费) × (1+不良率)	2026-05-07 07:41:53.412278+00	2026-05-07 07:41:53.412278+00
a47f6cc0-1eb1-4bba-a2e4-e8fbbdbc1d3f	MGMT_FEE	管理费	加价	20	10	t	ACTIVE	管理费. 来源 Excel 模板列 I = 加价基数(B+C+D+E+F) × 管理费比例	2026-05-07 07:41:53.412278+00	2026-05-07 07:41:53.412278+00
abe20ab3-1f49-406d-9e0e-2cb62c7a5fba	LABOR_COST	人工成本	加工费维度	2	2	t	ARCHIVED	\N	2026-04-23 07:37:13.30961+00	2026-05-07 07:41:53.412278+00
b3af18d8-0f79-4792-8177-59278b433313	OUTSOURCE_COST	其他外加工成本	成本明细	10	50	t	ACTIVE	其他外加工成本. 来源 Excel 模板列 F (当前占位 0, 后续业务扩展)	2026-05-07 07:41:53.412278+00	2026-05-07 07:41:53.412278+00
bb0961e7-af09-4bba-a8bc-dcef112f6c85	MATERIAL_COST	材料成本	成本明细	10	10	t	ACTIVE	料号材料成本汇总. 来源 Excel 模板列 B = 纯材料 + 来料加工费 + 来料其他费用 - 回收成本	2026-05-07 07:41:53.412278+00	2026-05-07 07:41:53.412278+00
bd29cf17-2513-40d5-ba8c-ca21c6395e88	PROCESS_FEE	加工费	成本明细	10	30	t	ACTIVE	料号加工费. 来源 Excel 模板列 D = ∑(各工序成本 × (1+不良率))	2026-05-07 07:41:53.412278+00	2026-05-07 07:41:53.412278+00
bf83b3a2-d1e9-489c-b0c7-c33e8c9b6a79	PROCESSING_COST	加工费	加工费维度	2	1	t	ARCHIVED	\N	2026-04-23 07:37:13.30961+00	2026-05-07 07:41:53.412278+00
cbcb27a5-edfe-4de9-b11e-0606d34a660d	TAX	税费	加价	20	40	t	ACTIVE	税费. 来源 Excel 模板列 O = 加价基数 × 税费比例	2026-05-07 07:41:53.412278+00	2026-05-07 07:41:53.412278+00
d47291e8-62bd-43af-80a9-559d20f27431	MATERIAL_COST_CU	Cu材料成本	材料成本维度	1	2	t	ARCHIVED	\N	2026-04-23 07:37:13.30961+00	2026-05-07 07:41:53.412278+00
d6ae1209-80e8-4f97-a550-43b81492e777	TOTAL_CNY_PCS	总成本(CNY/PCS)	总成本	30	20	t	ACTIVE	总成本 CNY/PCS. 来源 Excel 模板列 R = P / 1000 / 单重(g/pcs)	2026-05-07 07:41:53.412278+00	2026-05-07 07:41:53.412278+00
e85ed5c9-c344-4728-a575-d969f5aa0b4b	TOTAL_USD_PCS	总成本(USD/PCS)	总成本	30	40	t	ACTIVE	总成本 USD/PCS. 来源 Excel 模板列 U = T / 1000 / 单重	2026-05-07 07:41:53.412278+00	2026-05-07 07:41:53.412278+00
f373e8db-c780-4838-a803-f6dc2f195ba9	MATERIAL_LOSS	材料损耗成本	成本明细	10	20	t	ACTIVE	料号材料损耗成本. 来源 Excel 模板列 C = ∑(BOM × 来料损耗率 × 价格) + ∑(固定损耗 × 价格)	2026-05-07 07:41:53.412278+00	2026-05-07 07:41:53.412278+00
\.


--
-- Data for Name: component_directory; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.component_directory (id, parent_id, name, sort_order, created_at) FROM stdin;
\.


--
-- Data for Name: component; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.component (id, directory_id, name, code, column_count, fields, formulas, status, created_at, updated_at, component_type, data_driver_path, row_key_fields, tree_config, bom_recursive_expand, excel_columns) FROM stdin;
\.


--
-- Data for Name: component_sql_view; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.component_sql_view (id, component_id, sql_view_name, sql_template, declared_columns, required_variables, scope, status, description, created_by, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: composite_process_def; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.composite_process_def (id, code, name, icon, description, param_schema, sort_order, status, created_at) FROM stdin;
1e788dca-e3b0-4411-b655-8b4683124d46	RIVET	铆接	🔩	将两个配件通过铆钉压接固定	[{"id": "pressure", "type": "number", "unit": "kN", "label": "铆接压力", "placeholder": "如 5.0"}, {"id": "height", "type": "number", "unit": "mm", "label": "铆钉高度", "placeholder": "如 3.2"}]	10	ACTIVE	2026-05-13 12:47:21.051432+00
3488e276-f11e-4db3-a23b-f072d974376c	PRESS_FIT	压配合	🗜️	通过过盈配合将配件压入固定	[{"id": "force", "type": "number", "unit": "kN", "label": "压入力", "placeholder": "如 12"}, {"id": "fit", "type": "text", "unit": "", "label": "配合公差", "placeholder": "如 H7/r6"}]	60	ACTIVE	2026-05-13 12:47:21.051432+00
413d0971-4549-4410-9d72-7a59645e6d29	ULTRASONIC_WELD	超声波焊接	〰️	利用超声波振动将配件熔合	[{"id": "amplitude", "type": "number", "unit": "μm", "label": "振幅", "placeholder": "如 30"}, {"id": "weld_time", "type": "number", "unit": "ms", "label": "焊接时间", "placeholder": "如 500"}]	50	ACTIVE	2026-05-13 12:47:21.051432+00
5de8e8d0-819b-4b3a-a54c-e0c357537af8	RESISTANCE_WELD	电阻焊	⚡	通过电阻加热实现配件熔合	[{"id": "current", "type": "number", "unit": "kA", "label": "焊接电流", "placeholder": "如 8.0"}, {"id": "time", "type": "number", "unit": "ms", "label": "焊接时间", "placeholder": "如 80"}]	20	ACTIVE	2026-05-13 12:47:21.051432+00
74b85325-20be-4d59-8af2-10c48fce693f	BRAZING	钎焊	🔥	使用钎料在低于母材熔点下连接配件	[{"id": "temp", "type": "number", "unit": "°C", "label": "钎焊温度", "placeholder": "如 650"}, {"id": "material", "type": "text", "unit": "", "label": "钎料材质", "placeholder": "如 银基钎料"}]	40	ACTIVE	2026-05-13 12:47:21.051432+00
d7bdfa31-dc4e-4710-855c-da9934505027	LASER_WELD	激光焊	🔆	使用激光束对配件进行精密焊接	[{"id": "power", "type": "number", "unit": "W", "label": "激光功率", "placeholder": "如 200"}, {"id": "speed", "type": "number", "unit": "mm/s", "label": "焊接速度", "placeholder": "如 50"}]	30	ACTIVE	2026-05-13 12:47:21.051432+00
\.


--
-- Data for Name: config_template; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.config_template (id, code, name, description, status, created_at, updated_at, created_by, published_at) FROM stdin;
\.


--
-- Data for Name: config_category; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.config_category (id, template_id, code, name, sort_order, status, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: config_item; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.config_item (id, category_id, code, name, default_value, sort_order, status, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: costing_bom_tree_config; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_bom_tree_config (id, name, sql_template, is_active, created_at, updated_at) FROM stdin;
5088a09f-03b1-47c4-83b2-7d544b633771	BOMV2	WITH RECURSIVE bom AS (\n  SELECT\n    p::text                                        AS root_no,\n    p::text                                        AS material_no,\n    (SELECT bv.bom_version::text\n       FROM material_bom_item bv\n      WHERE bv.material_no = p\n        AND bv.customer_no = '_GLOBAL_'\n        AND bv.system_type = 'PRICING'\n        AND :versionFilter(bv.is_current, bv.bom_version, bv.material_no)\n      LIMIT 1)                                     AS bom_version,\n    NULL::text                                     AS parent_no,\n    p::text                                        AS node_path\n  FROM unnest(:production_part_nos) AS p\n\n  UNION ALL\n\n  SELECT\n    b.root_no,\n    ch.component_no::text                          AS material_no,\n    (SELECT bv.bom_version::text\n       FROM material_bom_item bv\n      WHERE bv.material_no = ch.component_no\n        AND bv.customer_no = '_GLOBAL_'\n        AND bv.system_type = 'PRICING'\n        AND :versionFilter(bv.is_current, bv.bom_version, bv.material_no)\n      LIMIT 1)                                     AS bom_version,\n    ch.material_no::text                           AS parent_no,\n    (b.node_path || '/' || ch.component_no)::text  AS node_path\n  FROM material_bom_item ch\n  JOIN bom b ON ch.material_no = b.material_no\n  WHERE ch.customer_no  = '_GLOBAL_'\n    AND ch.system_type  = 'PRICING'\n    AND :versionFilter(ch.is_current, ch.bom_version, ch.material_no)\n    AND ch.component_no IS NOT NULL\n) CYCLE material_no SET is_cyc USING cyc_path\nSELECT root_no, material_no, bom_version, parent_no, node_path\nFROM bom	t	2026-07-04 00:51:35.448579+00	2026-07-14 09:35:37.204311+00
\.


--
-- Data for Name: costing_price_version; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_price_version (id, version_kind, version_number, status, notes, is_default, published_at, published_by, created_at, updated_at, created_by) FROM stdin;
\.


--
-- Data for Name: costing_element_price; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_element_price (id, version_id, element_code, costing_price, market_ref_price, source_url, source_name, source_rule, currency, unit, discount_rate, sort_order, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: costing_exchange_rate; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_exchange_rate (id, version_id, from_currency, to_currency, costing_rate, market_rate, rate_rule, source_url, sort_order, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: costing_material_price; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_material_price (id, version_id, material_no, brand_name, spec, dimension, costing_price, market_ref_price, source_url, source_name, source_rule, currency, unit, discount_rate, sort_order, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: customer; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.customer (id, name, code, level, industry, region, address, accumulated_amount, credit_limit, payment_method, remarks, status, version, created_at, updated_at, industry_code, product_category_id) FROM stdin;
\.


--
-- Data for Name: product_category; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.product_category (id, code, name, description, parent_id, status, sort_order, created_at, updated_at) FROM stdin;
b9576df8-24bf-42b7-b5a7-58bda3a023d2	DEFAULT	默认分类	\N	\N	ACTIVE	999	2026-04-23 07:37:13.30961+00	2026-04-23 07:37:13.30961+00
\.


--
-- Data for Name: template; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.template (id, template_series_id, name, version, category, description, usage_note, product_attributes, subtotal_formula, components_snapshot, status, created_by, published_at, created_at, updated_at, excel_view_config, customer_id, category_id, template_kind, formulas, is_default, referenced_variables, sql_views_snapshot, template_sql_views_snapshot) FROM stdin;
\.


--
-- Data for Name: quotation; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.quotation (id, quotation_number, customer_id, name, contact_id, contact_name, contact_phone, contact_email, project_name, opportunity_id, sales_rep_id, quote_type, priority, stage, expected_close_date, status, total_amount, expiry_date, payment_terms, delivery_cycle, original_amount, system_discount_rate, final_discount_rate, discount_adjustment_reason, is_manually_adjusted, source_quotation_id, assigned_approver_id, snapshot_customer_name, snapshot_customer_level, snapshot_customer_region, snapshot_customer_industry, snapshot_customer_address, created_at, updated_at, remarks, tax_rate, tax_amount, customer_template_id, import_batch_id, referenced_versions, submission_snapshot, costing_card_template_id, bound_global_variables_snapshot) FROM stdin;
\.


--
-- Data for Name: costing_order; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_order (id, quotation_id, submitted_by, entered_costing_at, created_at, costing_order_number, status, reject_reason, frozen_dto, total_amount, reviewed_by, reviewed_at, updated_at, costing_render, costing_total_amount) FROM stdin;
\.


--
-- Data for Name: costing_order_version_override; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_order_version_override (id, costing_order_id, component_id, part_no, view_version, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: costing_part_design_cost; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_part_design_cost (id, hf_part_no, design_drawing_no, version_number, design_proc_fee, design_material_fee, currency, unit, loss_rate, is_active, notes, created_at, updated_at, part_version) FROM stdin;
\.


--
-- Data for Name: costing_part_element_bom; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_part_element_bom (id, input_material_no, seq_no, element_code, composition_pct, loss_rate, is_active, notes, created_at, updated_at, part_version) FROM stdin;
\.


--
-- Data for Name: costing_part_material_bom; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_part_material_bom (id, hf_part_no, seq_no, input_material_no, process_no, process_name, input_qty, input_unit, output_qty, output_unit, output_loss_rate, fixed_loss_qty, loss_rate, is_active, notes, created_at, updated_at, part_version) FROM stdin;
\.


--
-- Data for Name: costing_part_plating; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_part_plating (id, plating_no, version_number, seq_no, element_attr, plating_area_cm2, layer_thickness_um, requirement, is_active, created_at, updated_at, part_version) FROM stdin;
\.


--
-- Data for Name: costing_part_plating_fee; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_part_plating_fee (id, hf_part_no, plating_plan_code, plan_version, plating_process_fee, plating_material_fee, currency, price_unit, defect_rate, is_active, notes, created_at, updated_at, part_version) FROM stdin;
\.


--
-- Data for Name: costing_part_process_cost; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_part_process_cost (id, hf_part_no, process_no, process_name, cost_type, unit_price, currency, unit, ref_calc_version, is_active, notes, created_at, updated_at, part_version) FROM stdin;
\.


--
-- Data for Name: costing_part_quality_check; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_part_quality_check (id, hf_part_no, stage, primary_seq_no, seq_no, requirement_code, requirement_desc, scrap_rate, is_active, notes, created_at, updated_at, part_version) FROM stdin;
\.


--
-- Data for Name: costing_part_tooling_cost; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_part_tooling_cost (id, hf_part_no, process_no, process_name, seq_no, tooling_no, tooling_unit_cost, process_count, cycle_count, unit_price, currency, unit, is_active, notes, created_at, updated_at, part_version) FROM stdin;
\.


--
-- Data for Name: costing_part_weight; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_part_weight (id, hf_part_no, weight_g_per_pcs, is_active, notes, created_at, updated_at, part_version) FROM stdin;
\.


--
-- Data for Name: costing_template; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_template (id, series_id, name, is_default, version, status, description, columns, referenced_variables, created_by, published_at, created_at, updated_at, linked_template_id) FROM stdin;
\.


--
-- Data for Name: costing_sheet; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_sheet (id, quotation_id, costing_template_id, import_batch_id, rows, total_cost, status, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: costing_summary; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_summary (id, summary_no, hf_part_no, element_version_id, material_version_id, exchange_version_id, status, quote_currency, notes, created_at, updated_at, created_by, computed_at, published_at, published_by) FROM stdin;
\.


--
-- Data for Name: costing_summary_override; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_summary_override (id, summary_id, target_kind, target_key, field_name, override_value, notes, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: costing_summary_result; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.costing_summary_result (id, summary_id, metric_code, metric_label, value, currency, formula_used, sort_order, created_at) FROM stdin;
\.


--
-- Data for Name: cpq_feature_group; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.cpq_feature_group (id, code, name, description, category, status, erp_ref_code, extra_attrs, created_by, created_at, updated_by, updated_at) FROM stdin;
\.


--
-- Data for Name: cpq_feature_field; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.cpq_feature_field (id, group_id, code, name, sort_order, data_type, assign_mode, is_required, default_value, min_value, max_value, code_length, decimal_places, data_source_ref, partno_prefix, partno_suffix, extra_attrs, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: cpq_feature_value; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.cpq_feature_value (id, field_id, code, label, description, sort_order, partno_include, is_active, extra_attrs, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: customer_contact; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.customer_contact (id, customer_id, name, role, phone, email, wechat, is_primary, created_at) FROM stdin;
\.


--
-- Data for Name: customer_excel_template; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.customer_excel_template (id, name, customer_id, description, header_row_index, data_start_row_index, sheet_index, part_no_column, excel_columns, sample_file_name, created_by, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: customer_lead; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.customer_lead (id, lead_code, source_type, share_token, contact_name, contact_phone, contact_email, company_name, note, status, reviewed_by, reviewed_at, review_action, bound_customer_id, review_note, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: internal_material; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.internal_material (id, material_no, name, specification, size, status_code, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: customer_material_mapping; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.customer_material_mapping (id, customer_id, customer_part_no, material_id, created_at) FROM stdin;
\.


--
-- Data for Name: customer_tax; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.customer_tax (id, customer_id, tax_rate, effective_date, expiry_date, is_current, description, created_at, updated_at, created_by, updated_by) FROM stdin;
\.


--
-- Data for Name: datasource; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.datasource (id, code, name, type, status, description, sql_query, sql_result_column, api_url, api_method, api_headers, api_body_template, api_result_path, api_timeout_seconds, created_by, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: datasource_param; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.datasource_param (id, datasource_id, param_order, param_code, param_name, source_type, system_param_code, is_required, description, created_at) FROM stdin;
\.


--
-- Data for Name: ddl_operation_history; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.ddl_operation_history (id, table_name, column_name, data_type, default_value, importance, affects_calculation, status, error_message, migration_content, flyway_version_hint, created_by, created_by_name, created_at) FROM stdin;
\.


--
-- Data for Name: ddl_operation_lock; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.ddl_operation_lock (lock_key, locked_by, locked_at, expires_at, operation_desc, created_at, updated_at, created_by, updated_by) FROM stdin;
\.


--
-- Data for Name: derived_attribute; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.derived_attribute (id, host_sheet_id, variable_code, variable_label, data_type, computation_type, computation, status, sort_order, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: electricity_price; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.electricity_price (id, region, voltage_level, price_type, time_range, price, unit, effective_date, expire_date, version_no, created_at, updated_at, created_by, updated_by, is_current) FROM stdin;
\.


--
-- Data for Name: element; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.element (id, element_code, element_name, element_no, status, created_at, updated_at) FROM stdin;
05f9e391-434d-45e2-8b6c-0a0a80658a66	Mn	锰	10010	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
065f994d-bb5e-4e3d-b59b-b1524fd222cd	223	223	10030	ACTIVE	2026-07-10 00:06:47.354907+00	2026-07-10 00:06:47.354907+00
0ae74036-a9cb-4eee-b2e3-cfd87b4712af	Ag	银	10001	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
169130cc-a317-4b66-9445-91737a43cc7b	ZnO	氧化锌	10095	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
176636d6-cb04-4923-96fd-b79bbf780fce	Ni36	铁镍合金Ni36	10032	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
1ad257d4-24ab-427f-83aa-89111d9660cb	Ni	镍	10005	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
1ea80aa9-64e2-4e33-93c8-52d59251f3f9	DC04	冷轧钢DC04	10026	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
1ec2d259-4ee1-4004-af58-25bb1097839e	Pd	钯	10021	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
20b824f6-f2ba-4d2a-82d4-48838221416b	Cu	铜	10002	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
3258b287-76a6-4a76-8a5c-2a6b73a4d4a4	P	磷	10057	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
35337306-d2ef-4ba1-8447-7ccc25ba837e	191	191	10031	ACTIVE	2026-07-10 00:06:47.354907+00	2026-07-10 00:06:47.354907+00
358b6722-d7a2-47a8-b205-ea695e98498b	W	钨	10013	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
3ed69ee8-9a5c-4dd8-a4d3-4d231dd115b1	258	258	10061	ACTIVE	2026-07-10 00:06:47.354907+00	2026-07-10 00:06:47.354907+00
5530892c-4f8a-4037-82d5-b3cb63387ea1	CdO	氧化镉	90002	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-09 08:41:36.858831+00
59c7d730-d140-4ffa-bc8e-572cf97c675f	Zn	锌	10003	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
5d934fdf-fb9f-4131-9e91-7aab288f2f83	304	304不锈钢	10042	ACTIVE	2026-07-10 00:06:47.354907+00	2026-07-10 00:06:47.354907+00
6366c5f2-8a3e-4911-9a6e-487c723b002a	Ir	铱	10059	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
642fe4cd-b702-4914-af6b-9d0306dac553	206	206	10034	ACTIVE	2026-07-10 00:06:47.354907+00	2026-07-10 00:06:47.354907+00
69f22693-78cc-48d4-a410-001d4ca52a9b	SnO2	二氧化锡	10064	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
752e67aa-92d3-4d2f-a701-d551cc7f966f	C	碳	10012	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
86f33a41-5042-4613-9c53-f0175acfd435	Sn	锡	10004	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
8db4d62d-d5c0-49b0-a48c-f9777ca14345	430	430不锈钢	10044	ACTIVE	2026-07-10 00:06:47.354907+00	2026-07-10 00:06:47.354907+00
8f1fe9da-0ed6-4b74-a9f5-6a227a1d90ec	721	721	10033	ACTIVE	2026-07-10 00:06:47.354907+00	2026-07-10 00:06:47.354907+00
acf53daa-9a22-4fb1-8364-6995f9abbd31	Ni42	铁镍合金Ni42	10029	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
af5fdef2-faf2-4e3e-aeed-fb4e5fe70118	Ce	铈	10018	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
b1790235-778f-4e0e-bf33-734a972255d0	H70	黄铜H70	10069	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
bd3c0458-9e5b-4591-a556-703c23acc7a2	Cd	镉	10017	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
c0168c74-d4b9-493f-ad7f-faca9fe934f0	WC	碳化钨	10024	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
c0d3494d-7f01-4569-af88-addf65a7ca45	316	316不锈钢	10028	ACTIVE	2026-07-10 00:06:47.354907+00	2026-07-10 00:06:47.354907+00
c4b20fc9-0cee-43db-bf35-b70020322f35	In	铟	10019	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
c7873810-dffc-4576-aab3-76a8f8e4c08d	Fe	铁	10006	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
cb06e804-8bd6-4f57-b3cd-d457e1117885	Be	铍	10009	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
cd9ee3eb-c014-4a16-b0d3-fdcbdaef8e52	301	301不锈钢	10039	ACTIVE	2026-07-10 00:06:47.354907+00	2026-07-10 00:06:47.354907+00
d73362f5-e57a-42c7-a288-0db427f8cf11	不锈钢	不锈钢	10027	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
e660cfc8-0547-44b3-a140-baa8b659cf6f	Au	金	90001	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-09 08:41:36.858831+00
ee581364-d869-46d5-96b6-04834c25358a	Pt	铂	10058	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
f7c9ed55-c81d-4083-aef9-f4629985d6c2	Cr	铬	10014	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
fd8c2996-2cf9-45ac-ae85-6656b4194034	Si	硅	10011	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
fefacfec-eb9b-4d10-b7e9-98bd07fe278d	Al	铝	10007	ACTIVE	2026-07-09 08:41:36.858831+00	2026-07-10 00:06:47.354907+00
\.


--
-- Data for Name: element_bom; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.element_bom (id, system_type, customer_no, bom_type, bom_status, plant, valid_from, valid_to, material_no, characteristic, batch_qty, production_unit, created_at, updated_at, created_by, updated_by, is_current, production_no, material_part_no, source) FROM stdin;
\.


--
-- Data for Name: element_bom_item; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.element_bom_item (id, system_type, customer_no, material_no, characteristic, component_no, part_no, effective_datetime, expire_datetime, operation_no, operation_seq, seq_no, issue_unit, composition_qty, base_qty, component_usage_type, feature_mgmt, content, upper_limit_pct, lower_limit_pct, scrap_batch, scrap_rate, defect_rate, fixed_scrap, issue_location, issue_storage, fas_group, plug_position, ref_rd_center, is_optional, wo_expand_option, is_purchase_replace, component_lead_time, main_substitute, attached_part, ecn_no, use_qty_formula, qty_formula, scrap_rate_type, is_backflush, is_customer_supply, recovery_discount, recovery_currency, recovery_unit, created_at, updated_at, created_by, updated_by, hf_part_no, is_current, production_no, material_part_no) FROM stdin;
\.


--
-- Data for Name: element_price_source; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.element_price_source (id, source_name, source_url, source_type, description, status, created_at, updated_at, created_by, updated_by) FROM stdin;
\.


--
-- Data for Name: element_daily_price; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.element_daily_price (id, element_name, source_id, price_date, raw_price, raw_high, raw_low, raw_open, raw_close, currency, price_unit, fetch_status, fetch_error, fetched_at, manually_filled_by, created_at, updated_at, created_by, updated_by) FROM stdin;
\.


--
-- Data for Name: element_price_fetch_rule; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.element_price_fetch_rule (id, rule_name, rule_code, rule_definition, description, status, created_at, updated_at, created_by, updated_by) FROM stdin;
\.


--
-- Data for Name: element_price; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.element_price (id, customer_id, element_name, version, is_current, source_id, fetch_rule_id, premium_price, currency, price_unit, status, imported_by, import_record_id, created_at, updated_at, created_by, updated_by) FROM stdin;
\.


--
-- Data for Name: equipment; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.equipment (id, equipment_no, equipment_name, equipment_type, resource_group_no, resource_group_name, workshop, original_amount, residual_value, depreciation_method, depreciation_years, annual_available_hours, production_calendar, purchase_date, annual_depreciation, hourly_depreciation, currency, status, created_at, updated_at, created_by, updated_by) FROM stdin;
\.


--
-- Data for Name: exchange_rate; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.exchange_rate (id, customer_id, from_currency, to_currency, rate, effective_date, is_current, source, created_at, updated_at, created_by, updated_by) FROM stdin;
\.


--
-- Data for Name: exchange_rate_v6; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.exchange_rate_v6 (id, version_no, base_currency, target_currency, rate, ref_rate, ref_fetch_rule, ref_source_url, effective_date, expire_date, created_at, updated_at, created_by, updated_by, is_current) FROM stdin;
\.


--
-- Data for Name: fee_config; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.fee_config (id, system_type, biz_type, fee_no, fee_name, material_no, customer_no, region, charge_basis, value, ratio, currency, unit, effective_date, expire_date, pricing_version_no, created_at, updated_at, created_by, updated_by, dim_input_material_no, dim_sub_seq_no, dim_element_name, is_current) FROM stdin;
\.


--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) FROM stdin;
1	343	<< Flyway Baseline >>	BASELINE	<< Flyway Baseline >>	\N	postgres	2026-07-20 02:32:31.25819	0	t
\.


--
-- Data for Name: global_variable_change_log; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.global_variable_change_log (id, var_code, key_id, action, old_value, new_value, changed_by, changed_by_name, note, changed_at) FROM stdin;
\.


--
-- Data for Name: global_variable_definition; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.global_variable_definition (code, name, var_type, source_view, key_columns, value_column, label_template, unit, description, sort_order, is_active, created_at, updated_at, value_source_type, visibility) FROM stdin;
COST_ELEMENT	元素价格	LOOKUP_TABLE	\N	["key"]	value_number	\N	CNY	元素价格	100	t	2026-05-21 11:36:35.643804+00	2026-05-21 11:36:35.643804+00	KV_TABLE	PUBLIC
ELEM_PRICE	元素核价价格	LOOKUP_TABLE	v_costing_element_price	["element_code"]	costing_price	element_code	CNY/KG	元素核价价格表 (按元素代码查), 来源 costing_element_price 当前发布版本	10	t	2026-05-07 01:33:50.444862+00	2026-05-07 01:35:00.881229+00	COSTING_VIEW	COSTING_INTERNAL
EXCHANGE_RATE	核价汇率	LOOKUP_TABLE	v_costing_exchange_rate	["from_currency", "to_currency"]	costing_rate	from_currency:to_currency	-	汇率管理表 (按源币种→目标币种查), 来源 costing_exchange_rate 当前发布版本	30	t	2026-05-07 01:33:50.444862+00	2026-05-07 01:35:00.881229+00	COSTING_VIEW	COSTING_INTERNAL
MAT_PRICE	材料核价价格	LOOKUP_TABLE	v_costing_material_price	["material_no"]	costing_price	material_no	CNY/KG	材料核价价格表 (按料号查), 来源 costing_material_price 当前发布版本	20	t	2026-05-07 01:33:50.444862+00	2026-05-07 01:35:00.881229+00	COSTING_VIEW	COSTING_INTERNAL
PROCESS_DEFAULT_PRICE	工序默认单价	LOOKUP_TABLE	process_default_cost	["process_code"]	unit_price	process_code	CNY	工序默认单价 — 按 process_code 查;选配抽屉 P3 + 选配料号产品卡片 COMP-CFG-PROCESS 组件单价列均通过此变量动态取数. 数据源 process_default_cost 表(spec 2026-05-13 V_NN 实施)	200	t	2026-05-13 08:50:31.865282+00	2026-05-13 08:50:31.865282+00	KV_TABLE	PUBLIC
PROCESS_DEFAULT_YIELD	工序默认成材率	LOOKUP_TABLE	process_default_yield	["process_code"]	yield_rate	process_code	%	按工序代码查询默认成材率 (0~100), 用于 工序列表「成材率」字段空值时的回退取值; 用户在报价单手填后即覆盖	110	t	2026-05-17 12:04:39.493342+00	2026-05-17 12:04:39.493342+00	KV_TABLE	PUBLIC
\.


--
-- Data for Name: global_variable_value; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.global_variable_value (var_code, key_id, key_values, value_number, value_text, note, updated_at) FROM stdin;
\.


--
-- Data for Name: import_mapping_template; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.import_mapping_template (id, name, excel_template_id, template_id, column_mappings, created_by, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: import_record; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.import_record (id, quotation_id, customer_id, excel_template_id, mapping_template_id, mapping_snapshot, original_file_name, original_file_path, total_rows, success_rows, matched_rows, unmatched_rows, import_status, error_detail, imported_by, created_at, template_id, config_snapshot, costing_template_id, customer_template_id, costing_template_snapshot, customer_template_snapshot, import_batch_id, metadata, system_type) FROM stdin;
\.


--
-- Data for Name: import_session; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.import_session (id, customer_id, user_id, status, source_excel, created_at, expires_at, committed_at) FROM stdin;
\.


--
-- Data for Name: import_session_decision; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.import_session_decision (import_session_id, decision_type, decision_key, decision_value) FROM stdin;
\.


--
-- Data for Name: industry; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.industry (id, code, name, status, version, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: labor_rate; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.labor_rate (id, version_no, material_no, process_no, process_name, labor_grade, standard_labor_rate, currency, unit, effective_date, created_at, updated_at, created_by, updated_by, is_current, production_no, system_type, source) FROM stdin;
\.


--
-- Data for Name: material_recipe; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.material_recipe (id, code, symbol, name, spec_label, recipe_type, sort_order, status, created_at, updated_at, created_by, updated_by) FROM stdin;
00c0778a-4f02-4c4f-bcc2-4bcaffc01ba2	00131	DC03	DC03	\N	locked	131	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
01e59734-c875-47d0-991a-335ce540c9a4	00220	WZHF27-15	WZHF27-15	\N	locked	220	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
02097b82-34bb-41c1-a51a-09fbaffba85d	00147	QSn65-01	QSn65-01	\N	locked	147	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
04802c85-4159-4afc-8852-f800ffe86ad4	00103	AgCuNi/Tu1	AgCuNi/Tu1	\N	locked	103	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
04866efb-8112-4d5a-96b4-88a911a6bdf4	00165	AgCdO15/Ag/BZn15-20	AgCdO15/Ag/BZn15-20	\N	locked	165	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
04fff2b3-dcff-452c-9042-3acbe635a1ab	00206	WZHF34-05	WZHF34-05	\N	locked	206	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
072f0885-665b-4638-8bd7-70e2492d2c5d	00232	WZHF 37-08	WZHF 37-08	\N	locked	232	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
079ce89b-1280-491a-a09f-d064e83119b5	00004	AgC5	AgC5	\N	locked	4	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
082e402c-b398-4bde-a56a-3fbe8310621f	00241	WZHF 27-40	WZHF 27-40	\N	locked	241	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
086086f0-665f-4063-a031-9ce9109eb57c	00080	PtW	PtW	\N	locked	80	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
08938737-5928-4dc2-a54a-bc45d1f3482e	00169	Cu/301/Cu	Cu/301/Cu	\N	locked	169	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
08d9078b-e819-48bd-b18b-d2485ca972cf	00233	WZHF37-08	WZHF37-08	\N	locked	233	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
09256355-10f5-4002-a9fc-d48c38d7bcf9	00065	Ag60Cu26Zn14	Ag60Cu26Zn14	\N	locked	65	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
0ceca828-0eba-4199-9a82-db3fba1e466c	00140	CuZn33	CuZn33	\N	locked	140	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
0d8b783b-ea24-4519-b691-f2b99f684805	00170	AgSnO2(12)/BZn18-10	AgSnO2(12)/BZn18-10	\N	locked	170	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
0ea290f2-54e3-4e86-82a7-a3df1404fa33	00028	AgSnO2(12)/Ag/Ag15CuP	AgSnO2(12)/Ag/Ag15CuP	\N	locked	28	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
0ff32a6e-740b-4914-84c4-d47f91b791ca	00205	WZHF26-06	WZHF26-06	\N	locked	205	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
10cae77e-ed6e-45fb-8627-b3646504cff4	00127	Cu/304	Cu/304	\N	locked	127	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
1179d297-0719-45f5-a662-fb4aaa25e50d	00217	WZHF19-75	WZHF19-75	\N	locked	217	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
16845148-5711-4da0-8515-f6efae71b93b	00171	Cu/304/Cu	Cu/304/Cu	\N	locked	171	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
1a3ab7f1-545b-4660-9fd2-cfe0add4e3b2	00180	NC110	NC110	\N	locked	180	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
1aaca26d-bf24-4ff7-ac32-9a0142064841	00163	QSi3-1	QSi3-1	\N	locked	163	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
1c6e3b46-7b55-408a-a1cd-fbfc21505f99	00116	AgNi12/Cu/AgNi12	AgNi12/Cu/AgNi12	\N	locked	116	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
1c909bf8-9f1a-4391-9166-f5297c8f596d	00006	AgNi10	AgNi10	\N	locked	6	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
1d86a091-a53c-4289-b0b4-c15e4b0f16bb	00126	Cu2001P	Cu2001P	\N	locked	126	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
1ea2f76b-6516-4bf9-aef7-9f88a1f9bec5	00229	WZHF20-70	WZHF20-70	\N	locked	229	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
1fa6836b-59ba-4368-a3f9-8fbf36ef0179	00030	MAE12-1（Ⅰ）	MAE12-1（Ⅰ）	\N	locked	30	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
207b1953-b9d2-42d7-aca2-72ebbb10f89d	00252	电极丝（铜线）	电极丝（铜线）	\N	locked	252	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
20f31339-c48d-4430-b6d7-91ca942040e1	00091	Ag/H62	Ag/H62	\N	locked	91	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
215ecc09-baf7-4e6f-901f-3519b5ccc15e	00228	WZHF26-08	WZHF26-08	\N	locked	228	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
2179fbb1-502e-422b-a339-5e13a801b053	00253	301	301	\N	locked	253	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
21f06530-86b1-4a97-a093-f9b41344f80a	00179	CuSn9P	CuSn9P	\N	locked	179	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
221ad76d-8c8e-4fec-a6df-25b333f8b22b	00050	AgWC12C3/AgNi30	AgWC12C3/AgNi30	\N	locked	50	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
224ba8d5-fc33-4bd8-87d7-b0ec83ab348d	00069	CuNi20	CuNi20	\N	locked	69	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
23566e36-e350-48e8-a693-cd51cabaeef8	00119	AgNi30/Cu	AgNi30/Cu	\N	locked	119	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
25bf4fad-56b4-41db-bb4c-a4deadf0b40d	00046	AgWC12C3	AgWC12C3	\N	locked	46	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
25ef4515-bddf-43f2-8b34-8df446b565cd	CuZn70	CuZn	铜锌合金	70/30	locked	100	ACTIVE	2026-05-30 06:19:32.06574+00	2026-05-30 06:19:32.065744+00	\N	\N
26f774ec-c405-4e40-9762-34ab4c558a75	00112	AgNi10/BZn18-18	AgNi10/BZn18-18	\N	locked	112	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
29994e54-4073-4237-89f4-8f980e7278f5	99900	Cu	TU1条	\N	locked	100	ACTIVE	2026-07-16 11:37:17.077685+00	2026-07-16 11:37:17.077694+00	\N	\N
29a4c540-c221-495d-b131-bcf21c2b2753	991	H65带	H65带	\N	locked	100	ACTIVE	2026-07-10 02:27:03.277854+00	2026-07-10 06:45:37.437787+00	\N	\N
2a4147d1-8d39-429d-b0a7-2b12e83f0901	00026	AgNi40	AgNi40	\N	locked	26	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
2a7f544c-812d-4aa5-a655-b5b8db12b3ab	00188	AgC4Ni2	AgC4Ni2	\N	locked	188	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
2d7db319-c7cc-404c-badc-56f1feed6ace	00088	Ag/Cu/1008	Ag/Cu/1008	\N	locked	88	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
2d8b5ea4-fc86-4edb-a853-9f91805facba	00027	AgSnO2(12)	AgSnO2(12)	\N	locked	27	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
2da9837d-b8af-4c3c-8b10-0b1637bf05b4	00215	WZHF24-04	WZHF24-04	\N	locked	215	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
2e1b8093-6237-45cc-9877-6649fca2fcb1	00054	AgWC22C3/AgNi30	AgWC22C3/AgNi30	\N	locked	54	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
2e7f85ec-a646-461d-8439-12a8ffaf88f1	00184	AgNi12/Cu	AgNi12/Cu	\N	locked	184	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
2ebd1418-de5e-45a1-9728-050200677fce	00178	AgCu3/CuZn10	AgCu3/CuZn10	\N	locked	178	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
2ed68b6c-895a-480b-85d8-53bef5fef746	00029	SIO12-1	SIO12-1	\N	locked	29	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
3058edf5-7083-4548-bce9-4bd922de3148	00053	AgWC20C1	AgWC20C1	\N	locked	53	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
30d5da05-6193-4b75-b861-ff7bbafa3d35	00022	AgNi30	AgNi30	\N	locked	22	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
3104c8a8-c662-4ce5-94bc-b185db63c80a	00056	AgWC27C3/AgNi30	AgWC27C3/AgNi30	\N	locked	56	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
3453f390-1ca3-4708-a70f-cad33f66ba39	00062	Cu93P7	Cu93P7	\N	locked	62	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
35240c60-796f-415a-bf6b-c9785fad00b7	00236	WZHF23-70	WZHF23-70	\N	locked	236	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
371b3ee1-c69f-4416-9b89-526d612c8c5b	00058	AgZnO10	AgZnO10	\N	locked	58	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
37e9db3c-0a2c-4366-b9dc-7ec075b8f6a6	00018	AgNi(0.15)	AgNi(0.15)	\N	locked	18	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
37fbd185-0e80-4362-b753-d9f03ab0ed3e	00117	AgNi10/Cu/1008	AgNi10/Cu/1008	\N	locked	117	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
37fc0e2b-bca1-4bb7-b319-42f7190faff8	00111	AgNi10/BZn15-202	AgNi10/BZn15-202	\N	locked	111	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
388dac1e-b577-4143-8f64-cb5ce2464366	00024	AgNi30C3	AgNi30C3	\N	locked	24	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
3ade50a0-d8fe-41ab-8895-e860b49b957d	00081	铂铱冲件	铂铱冲件	\N	locked	81	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
3d6d585a-30ab-49a5-a72f-faabf1169779	00123	AgZnO12/Cu	AgZnO12/Cu	\N	locked	123	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
3e51fc21-d6ed-4b69-86c8-6ca6f11c79b2	00198	AgNi20-C17530R-HT	AgNi20-C17530R-HT	\N	locked	198	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
3ec26d54-dce6-4d78-a631-806ceb98b155	00159	C7025	C7025	\N	locked	159	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
3f94721b-e651-4e4d-9eb1-0c9b226fa1ea	00194	AgCe/TAg0.03/AgCe	AgCe/TAg0.03/AgCe	\N	locked	194	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
40cab825-c406-4546-8701-d7d0e8767b32	00239	WZHF28-30	WZHF28-30	\N	locked	239	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
40e773a7-5395-41e9-ad6c-ac52c062362f	00039	AgW30	AgW30	\N	locked	39	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
41a041e4-a333-4631-8010-465cf189b797	00193	AgNi(0.2)/C10500/AgNi(0.2)	AgNi(0.2)/C10500/AgNi(0.2)	\N	locked	193	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
4501a11e-b579-4d3c-ba13-6267ed9295e2	00156	H90	H90	\N	locked	156	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
463ecc78-be69-4bf3-87a4-6e844bd79102	AgNi90	AgNi	银镍合金	90/10	editable	30	ACTIVE	2026-07-09 09:09:47.243173+00	2026-07-09 09:09:47.243173+00	\N	\N
473a3e03-6628-4563-ac1f-32aa02e882a9	00005	AgNi25C2	AgNi25C2	\N	locked	5	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
478d4236-b1c1-429f-8f17-7888738c41c9	00060	CuW	CuW	\N	locked	60	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
47d91e77-b53c-4465-b537-7dd553ff558a	00213	WZHF 26-11	WZHF 26-11	\N	locked	213	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
4857fec3-6b03-4a94-a6ca-035af40402a3	00003	AgC4	AgC4	\N	locked	3	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
49263f58-52c4-4f7b-ac18-045bcb8086a8	00142	H70	H70	\N	locked	142	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
49d6c4ae-6208-4ed5-8f8b-88cce50fc34a	00073	AgWC55	AgWC55	\N	locked	73	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
4a35a3cd-cbb2-4b5e-a91a-1e3a2d379dc1	00064	AgCuZn	AgCuZn	\N	locked	64	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
4ba9a5ee-c452-4844-9081-43edb75a9449	00129	Cu/Al	Cu/Al	\N	locked	129	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
4c2e81f1-0cd9-45c3-8a39-5caecb0ce3c5	00092	Ag/H85	Ag/H85	\N	locked	92	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
4d2517bd-a294-4538-b064-3a19a0ffef42	00128	Cu/304L(BMC-103)	Cu/304L(BMC-103)	\N	locked	128	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
4d77e48d-6c02-4a23-a922-210224b38429	00231	WZHF28-55	WZHF28-55	\N	locked	231	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
4e584ed1-0684-4fc8-a95f-78ebb9112539	00227	WZHF26-35	WZHF26-35	\N	locked	227	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
4f1f3c23-f4c9-46d2-97cc-9915d8dc8339	AgCu85	AgCu	银铜合金	85/15	locked	10	ACTIVE	2026-07-09 09:09:47.243173+00	2026-07-09 09:09:47.243173+00	\N	\N
4f3e7743-67fd-4cbe-8cbf-a3ce475c4d26	00221	WZHF25-05	WZHF25-05	\N	locked	221	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
4f87a69a-4553-4b07-aaca-30e38cb0cca6	00219	WZHF39-25	WZHF39-25	\N	locked	219	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
504788da-b59b-4df1-a0b0-5a5281344c0b	00186	304/Cu/304	304/Cu/304	\N	locked	186	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
50b4a9f5-60d7-48fe-ab6e-9e42bea0bc46	00122	AgSnO2(4)/Cu/Fe	AgSnO2(4)/Cu/Fe	\N	locked	122	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
523a3093-96e4-4961-a8d9-410da5513c18	00015	AgCdO17	AgCdO17	\N	locked	15	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
5306d5b5-e27e-4957-89e4-91e5b646ceb7	00195	AgCu3/TU1	AgCu3/TU1	\N	locked	195	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
55a6f998-2148-4883-88d2-96b7b59f7da8	00190	AgNi15/Ag15CuP	AgNi15/Ag15CuP	\N	locked	190	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
5673c733-818f-4da4-85db-0d9b95698537	00152	AgNi10/H65	AgNi10/H65	\N	locked	152	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
5806deb5-639f-4d0f-867b-12e281b25761	00020	AgNi15	AgNi15	\N	locked	20	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
59420dd7-6f66-404e-863b-7c15c9382829	00164	6J40	6J40	\N	locked	164	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
5af07f24-5515-4375-8363-27c11c61c663	00155	AgCdO10/CuSn6	AgCdO10/CuSn6	\N	locked	155	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
5b05ad35-bc1c-4868-b74a-49a3678a5641	00172	AgNi0.3/C73500	AgNi0.3/C73500	\N	locked	172	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
5bae09f1-6d8b-4a20-9c08-7e7cb0c98022	00226	WZHF28-35	WZHF28-35	\N	locked	226	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
5bebf631-6bda-4046-b40b-541de375da23	00202	WZHF39-110	WZHF39-110	\N	locked	202	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
5bfc6195-435c-4718-a31a-2eaf2b5241b8	00033	AgSn02(14.5)	AgSn02(14.5)	\N	locked	33	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
5d5c1959-866c-4ecc-a7d1-d544f7d3b813	00144	H85	H85	\N	locked	144	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
5e2eb7b0-cf41-4ecb-99fc-6413b3ef73f4	00137	H65	H65	\N	locked	137	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
602ae8bd-4780-4372-a192-811fa7292a28	00055	AgWC27C3	AgWC27C3	\N	locked	55	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
60a70cdf-b79f-47b3-90ea-f6cf365e6c29	00049	AgWC1C3	AgWC1C3	\N	locked	49	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
6140a2e2-90ff-443f-8a36-60f2c090e460	00105	AgNi10/T2	AgNi10/T2	\N	locked	105	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
64cfd1f5-a898-437f-b45c-4aae0cd5eca5	00009	Ag50CuZn	Ag50CuZn	\N	locked	9	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
6a62285c-6ab7-4edb-980d-8e1cdd9f7c84	00209	WZHF38-10	WZHF38-10	\N	locked	209	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
6fbdb13d-2be6-4b05-aeb3-36586ea5cb0e	00153	304	304	\N	locked	153	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
6fcd84cc-b9ab-430b-9144-50d64e7e3138	00134	NiFe	NiFe	\N	locked	134	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
6ffc6475-d62e-48ab-983d-f1e720fb00be	00212	WZHF 22-70	WZHF 22-70	\N	locked	212	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
70028291-48f2-4355-a1dd-f906266d67eb	00066	HFHG-Ag65-3	HFHG-Ag65-3	\N	locked	66	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
7060d935-8631-4fe1-8b95-59c86397537e	00072	AgWC70	AgWC70	\N	locked	72	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
71e29340-a5b0-4306-ab75-45a5afaa864b	00084	Cr20Ni30	Cr20Ni30	\N	locked	84	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
726c4189-1c74-4b2f-a272-d4001088a69a	00141	H63	H63	\N	locked	141	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
72b8d22f-355b-4435-9afc-4bb3efa53a2d	00218	WZHF28-17	WZHF28-17	\N	locked	218	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
75b601b7-6262-4f10-8de6-a26a0b883d24	00148	CuSn6	CuSn6	\N	locked	148	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
76d085ba-a3fe-41b3-b4be-d7b233917aa6	00099	AgCd012/Cu/AgCd012	AgCd012/Cu/AgCd012	\N	locked	99	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
76dcd768-2d42-423f-96b3-2d3aca9f7871	00096	AgCdO/Cu/1008	AgCdO/Cu/1008	\N	locked	96	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
7d25a390-6d06-44e3-bddc-d311c67fde08	00068	AgSnO2In2O3(12)	AgSnO2In2O3(12)	\N	locked	68	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
7d75e34f-ff63-444a-8447-fa35f955fc41	00086	Ag/Cu/Ag	Ag/Cu/Ag	\N	locked	86	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
7deacf50-6871-49e1-b5ae-d8feb2ef6544	00071	AgWC80	AgWC80	\N	locked	71	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
7dfbd222-23ad-4678-a161-bdd0783a90ff	00149	QSn8-0.3	QSn8-0.3	\N	locked	149	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
7e205fb4-17e6-48f6-8067-f6c07eab3b2b	00051	AgWC22C3	AgWC22C3	\N	locked	51	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
7fe8f0e6-848d-4b5e-9b99-883b6e97578d	00237	WZHF39-60	WZHF39-60	\N	locked	237	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
7ff9c307-1779-4959-92f8-d3de57324c63	00208	WZHF22-09	WZHF22-09	\N	locked	208	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
801bf1a5-fa78-4841-b77e-a278b74bd3ec	00021	AgNi20	AgNi20	\N	locked	21	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
80aaf9bc-f509-48be-ab85-fc6da2ff3c5e	00107	AgNi(0.15)/T2	AgNi(0.15)/T2	\N	locked	107	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
80e9483c-13c5-4226-bd31-ddc28da6f299	00094	Ag/Ni	Ag/Ni	\N	locked	94	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
820f7125-7c66-4d93-b45b-d7df98cff7d6	00139	Ag/H65	Ag/H65	\N	locked	139	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
8269b56a-7e10-4d92-89b7-c233efe2a11e	00146	QSn6.5-0.1	QSn6.5-0.1	\N	locked	146	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
8293ddfe-6803-4f89-b2d3-4c0e302dce9f	00102	AgCu10	AgCu10	\N	locked	102	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
83312bcb-f91f-4f8d-b152-79b04bd68790	00012	AgCdO12	AgCdO12	\N	locked	12	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
84c8c932-2c45-4083-97ca-3965f52a1c7f	00251	AgNi30C-T	AgNi30C-T	\N	locked	251	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
862bcfc5-9b87-4771-b5e1-0a0132445fd3	00234	WZHF39-20	WZHF39-20	\N	locked	234	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
86f5fcc2-08f4-417f-9260-3527e3205bad	00014	AgCdO15-T	AgCdO15-T	\N	locked	14	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
8858ed1b-d923-4ca6-945c-5cb30c3b13a4	00138	C2801	C2801	\N	locked	138	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
89b4ddae-0d45-4eb2-8941-7cfb2c2db28b	00048	AgWC12C2	AgWC12C2	\N	locked	48	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
8b9976de-2140-4aed-a1ca-901c821c8d8c	00247	WZHF 33-95	WZHF 33-95	\N	locked	247	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
8ddd2ea7-3478-4d5e-9504-d219e6c537a9	00032	AgSnO2(14.5)	AgSnO2(14.5)	\N	locked	32	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
8e47252c-6e97-4c52-ac16-d61f1b07cfbd	992	AgNi11#-Ⅰ	AgNi11#-Ⅰ	\N	locked	100	ACTIVE	2026-07-10 02:27:18.50938+00	2026-07-10 06:45:37.437787+00	\N	\N
8eef5043-6e48-4de4-a089-f92ba6cd94bb	00151	铁镀铜	铁镀铜	\N	locked	151	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
8fcab6c8-8b22-4aa9-9a72-9b37c205766f	00001	Ag	Ag	\N	locked	1	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
9243d1fd-6112-4812-94f1-c9892aa96616	00007	Ag15CuP	Ag15CuP	\N	locked	7	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
925db127-74c0-4a93-9635-1b6588f40a56	00182	AIMgSi05(EN 6101-B T6)	AIMgSi05(EN 6101-B T6)	\N	locked	182	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
9284fcd5-b572-43c2-9611-8476af12c469	00077	AgCu50	AgCu50	\N	locked	77	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
95767ffd-a20c-4ccd-a2b4-daebe24fbf28	00185	Cu/316L/Cu	Cu/316L/Cu	\N	locked	185	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
95b39158-f761-4963-9021-787b9840c66f	00240	WZHF27-40	WZHF27-40	\N	locked	240	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
96727d1f-6578-41d6-9c5e-023533f9efc2	00035	Ag-15.5%SnInO	Ag-15.5%SnInO	\N	locked	35	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
97a4fe12-d697-4165-8ea9-4e71a3729f42	00106	AgNi10/H70	AgNi10/H70	\N	locked	106	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
98251345-ad9b-481e-bd3c-e9aab0c9458a	00095	Ag/TU1	Ag/TU1	\N	locked	95	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
986182be-ecff-455c-8afd-82a551d9dec6	00158	C17200	C17200	\N	locked	158	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
9a6de57f-f32e-4234-a572-0a732b0283d5	00036	AgSnO2(8)In2O3(4)	AgSnO2(8)In2O3(4)	\N	locked	36	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
9b42208e-2799-4bdc-9a62-85adb62fd3a4	00008	Ag25CuP	Ag25CuP	\N	locked	8	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
9bc05d51-8d4b-4f2d-b984-ae1413a46366	00115	AgNi10/Cu/Ag	AgNi10/Cu/Ag	\N	locked	115	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
9c766e9f-cb00-481e-b960-1243a7d28ee0	00057	AgWC30	AgWC30	\N	locked	57	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
9c818733-49b6-4ef1-bf9f-cc655a9a1e05	00010	AgC4(石墨烯)	AgC4(石墨烯)	\N	locked	10	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
9c84a821-338f-48cf-8864-721ef45eae47	00052	AgWC22C3/Ag	AgWC22C3/Ag	\N	locked	52	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
9cf9e3e0-d2d2-448e-a98a-364d71285d5d	00203	WZHF28-11	WZHF28-11	\N	locked	203	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
9d6c39c0-b8dd-4bbe-b6a2-fdfb56b4fa5e	00183	AlMgSi05	AlMgSi05	\N	locked	183	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
9dc08f52-216a-418b-aa2d-4b2eb639e25d	00087	Ag3/TU2	Ag3/TU2	\N	locked	87	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
a06721f7-c777-4b29-8a0b-be7e38c628ab	00041	AgW55	AgW55	\N	locked	41	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
a2e564a8-52ae-48b6-8a8f-d6cceb7e9797	00121	AgSnO2(12)/Cu/CuNi20	AgSnO2(12)/Cu/CuNi20	\N	locked	121	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
a6012647-8186-4690-b929-5ed0f37a7a0e	00025	AgNi30C3/Ag	AgNi30C3/Ag	\N	locked	25	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
a69b7794-1595-4b95-99b3-9cf7b91ff723	00079	AgWC16C2	AgWC16C2	\N	locked	79	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
a69f4a27-1f03-4bb4-95a8-1db52da8c37d	00101	AgCdO15/Cu/AgCdO15	AgCdO15/Cu/AgCdO15	\N	locked	101	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
a9a41a22-386f-4a77-9b6e-a59c8d9a7c0f	00089	AgNi015/Cu/CuNi	AgNi015/Cu/CuNi	\N	locked	89	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
a9e72304-6bf3-4e26-a2de-615cd59f9fea	00002	AgC3	AgC3	\N	locked	2	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
ab751fb9-ea98-4480-bf5d-52b7219020fb	00085	AgCuONiO/Cu/AgCuONiO	AgCuONiO/Cu/AgCuONiO	\N	locked	85	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
abab7bc3-ac6a-4209-9948-d44b72c1ccf1	00011	AgCdO10	AgCdO10	\N	locked	11	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
ac019f92-eb6c-4c00-b5b7-a8a265f00337	00125	T3	T3	\N	locked	125	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
ac56bb7e-169e-4bdd-aded-3ac3508931d9	00168	301/Cu/301	301/Cu/301	\N	locked	168	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
ac6b7eb4-8bfc-46df-9093-99d94649d48c	00173	EN573 Aw-1050A H14	EN573 Aw-1050A H14	\N	locked	173	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
ada1078a-d216-430b-9e4b-d44af9f6fc83	00162	1Cr18Ni9（12Cr18Ni9）	1Cr18Ni9（12Cr18Ni9）	\N	locked	162	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
ae89a548-4960-4ab8-b0ab-e8ce799c58b5	00150	DCO3镀铜	DCO3镀铜	\N	locked	150	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
af572e7c-ae19-4f7c-9e98-53876faf1629	00175	Al	Al	\N	locked	175	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
b0299f7e-c7b9-4ded-b39b-c3548e87b9af	00214	WZHF26-11	WZHF26-11	\N	locked	214	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
b041f1fe-dc7c-4ad8-bfec-092688140761	00017	AgCu70	AgCu70	\N	locked	17	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
b1c47317-53e4-4751-87cd-57f03a28e22a	00130	Cu/Ni	Cu/Ni	\N	locked	130	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
b2476256-f6a1-4ed1-b812-dce8e8a075cd	00161	HSn90-2	HSn90-2	\N	locked	161	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
b26d00c9-a14c-42ef-a71a-b8dd652ce5ad	00078	AgCu28	AgCu28	\N	locked	78	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
b3b3c9bd-f9bb-49a8-837d-089f1099028e	00199	WZHF29-78	WZHF29-78	\N	locked	199	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
b4651b23-541e-4c3b-bb01-9d2da76fe51f	00097	AgCdO10/TU1	AgCdO10/TU1	\N	locked	97	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
b5bbb100-c88e-4009-85de-9205e4878b1d	00244	WZHF20-11	WZHF20-11	\N	locked	244	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
b6cb1c61-8d87-4670-b5b5-d775a036fecc	00249	WZHF26-72	WZHF26-72	\N	locked	249	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
b6f8d5af-765d-4508-983d-95a621a83e22	00210	WZHF27-50	WZHF27-50	\N	locked	210	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
b7b57363-777c-4d44-bf83-269e16481248	00200	WZHF27-80	WZHF27-80	\N	locked	200	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
b8017b7e-0e27-4533-a1ee-5fbd1ea03cdc	00074	AgPd30	AgPd30	\N	locked	74	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
b80b544c-971a-4fab-b029-c084ea9a38ca	00191	AgSnO(2)12/蒙乃尔合金400	AgSnO(2)12/蒙乃尔合金400	\N	locked	191	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
b853b7f1-62c6-4092-a5e3-3a45730b52f9	00174	Al 5052-H32	Al 5052-H32	\N	locked	174	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
bb199939-d7ea-4b58-aacc-d02b7accbcc2	00114	AgNi10-T/Cu/1008	AgNi10-T/Cu/1008	\N	locked	114	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
bcc5ac62-0d3c-49da-b22b-64dba5f33859	00216	WZHF28-15	WZHF28-15	\N	locked	216	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
c25863a4-a380-4f44-86d5-ad9d2253546e	00040	AgW50	AgW50	\N	locked	40	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
c3659b23-f0fc-4647-bd19-2f9b9af2482d	00224	WZHF29-130	WZHF29-130	\N	locked	224	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
c3846945-a01c-4270-be6d-bb662d90c8e4	00207	WZHF 34-05	WZHF 34-05	\N	locked	207	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
c3a7f6e2-1c05-4397-8575-8bf7fbfdc61c	00090	Ag/Cu/	Ag/Cu/	\N	locked	90	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
c3d523be-2083-44eb-8f9f-9a2beeec198a	00075	AgSnO2	AgSnO2	\N	locked	75	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
c3f96784-fa3a-4486-ba67-053381f07cee	00070	AgW45	AgW45	\N	locked	70	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
c4e5d948-6b80-49d9-902f-c5dd62f8be40	00235	Ni42	Ni42	\N	locked	235	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
c6a4eef7-a258-41be-8e89-03001e70c4d5	00110	AgNi15/BZn15-20	AgNi15/BZn15-20	\N	locked	110	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
c7689fee-e57e-4a3b-b4b9-fb6e8b18eaa2	00059	CuW60	CuW60	\N	locked	59	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
c85016e3-8868-4cdd-940b-aea9ba393c11	00132	DC03+LC	DC03+LC	\N	locked	132	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
c90cd236-96a0-46ab-a497-c48feeff5eb6	00177	32A	32A	\N	locked	177	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
c974375f-9074-4cc9-be57-7520e46cca17	00160	ST37-2G	ST37-2G	\N	locked	160	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
c9ef3375-1615-4c86-9a34-90abcb0c5ff9	00196	银合金	银合金	\N	locked	196	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
ca010d51-bc48-4634-99ee-ff38a4acc5c5	00013	AgCdO15	AgCdO15	\N	locked	13	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
ca513ca6-09e0-4542-9fe8-4a02d8ccfbf9	00204	WZHF 28-11	WZHF 28-11	\N	locked	204	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
cd0ab947-8989-4b17-90aa-e49f654c89f9	00225	WZHF26-6B	WZHF26-6B	\N	locked	225	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
ce785606-3d18-440a-9cb4-0b71c1604b99	00037	AgSnO2(9)In2O3(4)-T	AgSnO2(9)In2O3(4)-T	\N	locked	37	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
cf645337-94a6-409d-a6b9-6a64c7ff33ea	00038	AgW40	AgW40	\N	locked	38	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
d2a75675-a7b2-40d8-a613-aade64910aca	00154	316	316	\N	locked	154	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
d32f58c4-eecf-4627-9755-b7c3d25218e2	00098	AgCd10/Cu/Fe	AgCd10/Cu/Fe	\N	locked	98	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
d39cddd6-4307-4af5-a20b-a22f27be180b	99901	Cu	Cu/Ag/Cu带	\N	locked	100	ACTIVE	2026-07-16 11:38:28.871431+00	2026-07-16 11:38:28.871441+00	\N	\N
d6a5a889-9747-454f-9657-eeb5614927fc	00109	AgNi10/BFe0.5	AgNi10/BFe0.5	\N	locked	109	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
d81ef824-54c4-4a30-b73c-bf252e22f3cf	00211	WZHF22-70	WZHF22-70	\N	locked	211	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
d997cd5d-1b3a-4f0e-b183-8b0a5c636622	00043	AgW65	AgW65	\N	locked	43	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
da518708-17f2-463d-9d66-f90ba452ebaf	00250	WZHF30-05	WZHF30-05	\N	locked	250	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
db7035d1-35be-4317-88e9-faa27f1ad245	00176	63A	63A	\N	locked	176	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
df5faf4a-af39-49c6-8b48-3db9da7acdea	00135	630	630	\N	locked	135	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
dfa9d0ac-e171-47a1-aaea-67874b076ad5	00120	AgSnO2(12)/Cu/Ni	AgSnO2(12)/Cu/Ni	\N	locked	120	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
e06baf34-9b51-4fc7-a31e-dd9311c990bc	00254	430	430	\N	locked	254	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
e0e21942-8da0-43d5-b746-022ed9aa3c22	AgNi95	AgNi	银镍合金	95/5	editable	40	ACTIVE	2026-07-09 09:09:47.243173+00	2026-07-09 09:09:47.243173+00	\N	\N
e147f3a3-b61f-4190-96d5-e0394d3250d3	00246	WZHF19-06	WZHF19-06	\N	locked	246	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
e173de29-769e-4562-b29a-36c572a192df	00108	AgNi0.15/Fe	AgNi0.15/Fe	\N	locked	108	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
e21aa8e8-7220-46c5-a435-4020903d9b9f	00222	WZHF38-15	WZHF38-15	\N	locked	222	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
e232eadd-495a-4ce2-8ddd-b3885cb96db9	00192	AgC2/Cu/Fe	AgC2/Cu/Fe	\N	locked	192	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
e263e33b-bb13-4303-9de7-b14b64bc9545	00143	H68	H68	\N	locked	143	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
e30e20d3-3d4c-49da-9701-b78430b1826c	00047	AgWC12C3/Ag	AgWC12C3/Ag	\N	locked	47	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
e3b4f409-ad50-4ac2-afdd-ea1e968814f4	00045	AgW80	AgW80	\N	locked	45	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
e3fbaf89-e884-4cf7-abd7-0f48deb357c7	00187	316l/Cu/316L	316l/Cu/316L	\N	locked	187	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
e4d52f6d-d0f0-47f1-8df3-0b7a9e11c9b5	00181	AIMgSi05	AIMgSi05	\N	locked	181	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
e5cdd7dc-9b10-41e4-a7be-beb58e0fa6aa	00093	Ag/H90	Ag/H90	\N	locked	93	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
e71bcd07-fe25-4ee1-9763-66eee5f2093f	00197	AgNi10/Ag15CuP	AgNi10/Ag15CuP	\N	locked	197	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
e7731aee-68c3-471c-9198-4f206c9a8f02	00082	AgWC26C1	AgWC26C1	\N	locked	82	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
e7b5fb28-234d-492c-9430-5d0330ae726a	00063	Ag基焊膏	Ag基焊膏	\N	locked	63	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
e92dba85-510a-442c-a219-c04ae6cc1afd	00189	Ag/Cu/CuNi44	Ag/Cu/CuNi44	\N	locked	189	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
ea6ac467-244e-4998-a30f-564eefae4d44	00083	AgNi25	AgNi25	\N	locked	83	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
eab16559-b2ce-44e0-8020-678337e308e7	00076	AgSnO2In2O3(14.5)	AgSnO2In2O3(14.5)	\N	locked	76	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
eb56bf6e-2b22-44cc-a697-cf7fca68854a	AgNi70	AgNi	银镍合金	75/25	locked	100	ACTIVE	2026-05-30 06:18:22.114664+00	2026-05-30 06:18:22.114674+00	\N	\N
ec7dd7e6-5535-4aa4-803d-57bc1cf88e62	00118	AgNi15/T2	AgNi15/T2	\N	locked	118	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
ec83b09c-ec49-4c99-b2eb-d7c09b4cee84	00243	WZHF25-25	WZHF25-25	\N	locked	243	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
ecb009d4-4ada-4d61-8855-deecdd368f9b	00166	Ni	Ni	\N	locked	166	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
eeb9955e-8594-42c5-b3f8-b1111cbb4611	00201	WZHF24-21	WZHF24-21	\N	locked	201	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
efb0d8a7-1618-432e-9d96-fa0033e4795b	00034	AgSnO2(15)	AgSnO2(15)	\N	locked	34	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
efb6b982-64d9-4310-a3d2-520ef7eb1240	00145	H85-Y2	H85-Y2	\N	locked	145	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
f042901e-4404-4db7-9089-41c70d81819f	00016	AgCdOⅦ(I.O)	AgCdOⅦ(I.O)	\N	locked	16	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
f0553d14-0107-4d88-8ded-705e82121170	00100	AgCdO/Cu/430	AgCdO/Cu/430	\N	locked	100	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
f1d384a3-d3e6-4a77-8234-141b16f1d0ee	00104	AgCuO(10)/Cu	AgCuO(10)/Cu	\N	locked	104	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
f26b91bf-60a4-47a8-a177-17176752dd9f	00067	AgSnO2(9)In2O3(4)	AgSnO2(9)In2O3(4)	\N	locked	67	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
f3b84e29-a53b-4a3f-8998-ac3b73956b2a	00223	WZHF29-140	WZHF29-140	\N	locked	223	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
f3e9d8ef-b988-40a7-a9e7-583be73cbb51	00124	BZn18-26	BZn18-26	\N	locked	124	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
f61dad71-318d-43f1-9f28-5968f2ad2b02	00113	CuNi15	CuNi15	\N	locked	113	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
f6c56d18-f81c-4de8-834a-ba9e9fa34b4c	00238	316L/B30	316L/B30	\N	locked	238	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
f772bdfe-d1ea-4da9-afaa-1cfe4d3b7c45	00061	HFHG-173050-3	HFHG-173050-3	\N	locked	61	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
f820b6d8-a2aa-433d-bc90-f47ae483de09	00167	AgZnO10/Cu/AgZnO10	AgZnO10/Cu/AgZnO10	\N	locked	167	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
f9b98afe-64b0-4438-8070-2980a33d6deb	00019	AgNi0.15	AgNi0.15	\N	locked	19	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
fa1b8a50-5127-4ad4-822c-3b501d196452	00042	AgW60	AgW60	\N	locked	42	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
fb1dd366-7e70-456e-af68-9608bd3f58c7	00248	WZHF33-95	WZHF33-95	\N	locked	248	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
fb3e8e85-78d8-47e4-aa23-ed4952913a0c	00133	DC03-C390	DC03-C390	\N	locked	133	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
fb9508d6-399a-43be-8874-121a32af86a8	AgCu90	AgCu	银铜合金	90/10	locked	20	ACTIVE	2026-07-09 09:09:47.243173+00	2026-07-09 09:09:47.243173+00	\N	\N
fbef0766-aac7-4117-b0ed-2196804bd8c0	00245	WZHF26-07	WZHF26-07	\N	locked	245	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
fc442726-a06f-4a9d-bc96-aafcf1f22df2	00157	AgNi10/H90	AgNi10/H90	\N	locked	157	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
fce0165c-28a0-4186-817a-c6d9e75fa69c	00031	AgSnO2(14)	AgSnO2(14)	\N	locked	31	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
fcfe35f1-1ef2-4234-9bf0-6b5836c9217b	00136	H62	H62	\N	locked	136	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
fe34c3da-34c7-4f38-8e7a-9950b2cf385d	00044	AgW70	AgW70	\N	locked	44	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
fee7a8bc-66ea-475a-bfee-0f88fdb73875	00230	WZHF39-40	WZHF39-40	\N	locked	230	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
ff9901f2-e25e-47bc-a364-4752d673f821	00023	AgNi30C3/AgNi20	AgNi30C3/AgNi20	\N	locked	23	ACTIVE	2026-07-10 00:06:47.045017+00	2026-07-10 06:45:37.437787+00	\N	\N
\.


--
-- Data for Name: mat_part; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_part (part_no, part_name, specification, size_info, category_id, unit_weight, weight_unit, status_code, is_pending_category, created_at, updated_at, created_by, updated_by, material_recipe_id, product_type, config_fingerprint, e2e_col_1779193319183, length, width, height, e2e_col_1780391883849) FROM stdin;
\.


--
-- Data for Name: mat_bom; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_bom (id, bom_type, hf_part_no, seq_no, input_material_no, input_material_name, loss_rate, gross_qty, net_qty, gross_unit, net_unit, output_material_type, defect_rate, element_name, composition_pct, created_at, updated_at, created_by, updated_by, part_version, child_part_no) FROM stdin;
\.


--
-- Data for Name: mat_bom_staging; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_bom_staging (id, bom_type, hf_part_no, seq_no, input_material_no, input_material_name, loss_rate, gross_qty, net_qty, gross_unit, net_unit, output_material_type, defect_rate, element_name, composition_pct, created_at, updated_at, created_by, updated_by, part_version, staging_id, import_session_id) FROM stdin;
\.


--
-- Data for Name: mat_composite_process; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_composite_process (id, hf_part_no, def_code, seq_no, participating_parts, param_values, part_version, is_current, created_at, created_by) FROM stdin;
\.


--
-- Data for Name: mat_customer_part_mapping; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_customer_part_mapping (id, customer_id, customer_part_name, customer_product_no, customer_drawing_no, hf_part_no, payment_method, base_currency, quote_currency, created_at, updated_at, created_by, updated_by, current_version) FROM stdin;
\.


--
-- Data for Name: mat_customer_part_mapping_staging; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_customer_part_mapping_staging (id, customer_id, customer_part_name, customer_product_no, customer_drawing_no, hf_part_no, payment_method, base_currency, quote_currency, created_at, updated_at, created_by, updated_by, current_version, staging_id, import_session_id) FROM stdin;
\.


--
-- Data for Name: mat_fee; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_fee (id, customer_id, hf_part_no, version, is_current, fee_type, seq_no, fee_value, fee_ratio, currency, price_unit, dim_input_material_no, dim_input_material_name, dim_element_name, dim_assembly_process, dim_sub_seq_no, price_floating, settlement_rise_ratio, fixed_rise_value, rise_currency, rise_unit, reject_rate, status, imported_by, import_record_id, created_at, updated_at, created_by, updated_by, part_version, customer_product_no) FROM stdin;
\.


--
-- Data for Name: mat_fee_staging; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_fee_staging (id, customer_id, hf_part_no, version, is_current, fee_type, seq_no, fee_value, fee_ratio, currency, price_unit, dim_input_material_no, dim_input_material_name, dim_element_name, dim_assembly_process, dim_sub_seq_no, price_floating, settlement_rise_ratio, fixed_rise_value, rise_currency, rise_unit, reject_rate, status, imported_by, import_record_id, created_at, updated_at, created_by, updated_by, part_version, customer_product_no, staging_id, import_session_id) FROM stdin;
\.


--
-- Data for Name: mat_part_model; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_part_model (id, part_no, version, label, is_current, glb_url, thumbnail_url, mesh_count, vertices, size_kb, metadata, uploaded_by, uploaded_at) FROM stdin;
\.


--
-- Data for Name: mat_part_source_file; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_part_source_file (id, part_no, model_id, file_role, file_url, file_size_bytes, md5_hash, uploaded_by, uploaded_at, metadata) FROM stdin;
\.


--
-- Data for Name: mat_part_staging; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_part_staging (part_no, part_name, specification, size_info, category_id, unit_weight, weight_unit, status_code, is_pending_category, created_at, updated_at, created_by, updated_by, staging_id, import_session_id) FROM stdin;
\.


--
-- Data for Name: mat_part_version_log; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_part_version_log (customer_product_no, hf_part_no, version, content_hash, diff_summary, source_excel, source_import_id, created_at, created_by, customer_id) FROM stdin;
\.


--
-- Data for Name: mat_plating_fee; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_plating_fee (id, customer_id, hf_part_no, version, is_current, plating_plan_code, plan_version, plating_process_fee, plating_material_fee, currency, price_unit, defect_rate, status, imported_by, import_record_id, created_at, updated_at, created_by, updated_by, part_version, customer_product_no) FROM stdin;
\.


--
-- Data for Name: mat_plating_fee_staging; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_plating_fee_staging (id, customer_id, hf_part_no, version, is_current, plating_plan_code, plan_version, plating_process_fee, plating_material_fee, currency, price_unit, defect_rate, status, imported_by, import_record_id, created_at, updated_at, created_by, updated_by, part_version, customer_product_no, staging_id, import_session_id) FROM stdin;
\.


--
-- Data for Name: mat_plating_plan; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_plating_plan (id, plan_code, version, seq_no, plating_element, plating_area, coating_thickness, plating_requirement, imported_by, import_record_id, created_at, updated_at, created_by, updated_by, part_version) FROM stdin;
\.


--
-- Data for Name: mat_plating_plan_staging; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_plating_plan_staging (id, plan_code, version, seq_no, plating_element, plating_area, coating_thickness, plating_requirement, imported_by, import_record_id, created_at, updated_at, created_by, updated_by, part_version, staging_id, import_session_id) FROM stdin;
\.


--
-- Data for Name: mat_process; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_process (id, customer_id, hf_part_no, version, is_current, seq_no, process_code, assembly_process, sub_seq_no, component_part_no, component_name, supplier_code, supplier_name, quantity, quantity_unit, unit_price, freight, currency, price_unit, status, imported_by, import_record_id, created_at, updated_at, created_by, updated_by, part_version, customer_product_no, quotation_line_item_id) FROM stdin;
\.


--
-- Data for Name: mat_process_staging; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.mat_process_staging (id, customer_id, hf_part_no, version, is_current, seq_no, process_code, assembly_process, sub_seq_no, component_part_no, component_name, supplier_code, supplier_name, quantity, quantity_unit, unit_price, freight, currency, price_unit, status, imported_by, import_record_id, created_at, updated_at, created_by, updated_by, part_version, customer_product_no, staging_id, import_session_id) FROM stdin;
\.


--
-- Data for Name: material_bom; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.material_bom (id, system_type, customer_no, bom_type, bom_version, bom_status, plant, valid_from, valid_to, material_no, characteristic, batch_qty, production_unit, created_at, updated_at, created_by, updated_by, is_current, production_no, source) FROM stdin;
\.


--
-- Data for Name: material_bom_item; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.material_bom_item (id, system_type, customer_no, material_no, characteristic, seq_no, component_no, part_no, effective_datetime, expire_datetime, operation_no, operation_seq, item_seq, issue_unit, composition_qty, base_qty, component_usage_type, feature_mgmt, upper_limit_pct, lower_limit_pct, scrap_batch, scrap_rate, fixed_scrap, issue_location, issue_storage, fas_group, plug_position, ref_rd_center, is_optional, wo_expand_option, is_purchase_replace, component_lead_time, main_substitute, attached_part, ecn_no, use_qty_formula, qty_formula, scrap_rate_type, is_backflush, is_customer_supply, defect_rate, calc_type, recovery_discount, recovery_currency, recovery_unit, created_at, updated_at, created_by, updated_by, is_current, bom_version, rough_weight, net_weight, weight_unit, production_no) FROM stdin;
\.


--
-- Data for Name: material_customer_map; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.material_customer_map (id, material_no, customer_no, customer_name, customer_material_name, customer_product_no, customer_drawing_no, seq_no, payment_method, base_currency, quote_currency, exchange_rate, created_at, updated_at, created_by, updated_by, system_type, production_no) FROM stdin;
\.


--
-- Data for Name: material_master; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.material_master (id, material_no, material_name, specification, dimension, old_material_no, material_type, usage_property, unit_weight, standard_unit, created_at, updated_at, created_by, updated_by, material_recipe_id, config_fingerprint, production_no) FROM stdin;
\.


--
-- Data for Name: material_recipe_element; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.material_recipe_element (id, recipe_id, element_code, element_name, default_pct, min_pct, max_pct, is_locked, sort_order, created_at, element_no) FROM stdin;
009733da-6d5d-4ac6-bb27-36926b6b2d6c	7ff9c307-1779-4959-92f8-d3de57324c63	Ni42	铁镍合金Ni42	40.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10029
00b041fc-3012-488b-9cd3-435cc67a4d17	a9e72304-6bf3-4e26-a2de-615cd59f9fea	C	碳	3.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10012
00c7b66e-68e7-47c7-8b28-5cfb98ba8bbd	55a6f998-2148-4883-88d2-96b7b59f7da8	Ni	镍	14.2900	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
00e04b1c-aabd-463f-86e7-28d39c2bbff2	ff9901f2-e25e-47bc-a364-4752d673f821	Ni	镍	30.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10005
01a5fdc7-7684-460c-abf2-c8f02b0fd96e	ac56bb7e-169e-4bdd-aded-3ac3508931d9	Cu	铜	84.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
023f2d80-d3e7-4a2b-afb2-8e15c47f6ea5	6a62285c-6ab7-4edb-980d-8e1cdd9f7c84	721	721	40.9000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10033
02504709-0260-4d8b-be84-dac3e9d77cea	5d5c1959-866c-4ecc-a7d1-d544f7d3b813	Zn	锌	15.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10003
02694aea-b7a2-49c9-a992-c6132f34fce3	c6a4eef7-a258-41be-8e89-03001e70c4d5	Cu	铜	52.0293	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
02ab631c-6e48-4721-bbf6-421e5eca941f	2a4147d1-8d39-429d-b0a7-2b12e83f0901	Ni	镍	40.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10005
02d4090f-a117-4c3b-835a-fb653c52d119	25bf4fad-56b4-41db-bb4c-a4deadf0b40d	C	碳	1.8500	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10012
03301fa4-63ba-4c50-ab09-a97473526f3b	4f3e7743-67fd-4cbe-8cbf-a3ce475c4d26	Cu	铜	39.8000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
035ea22b-a99b-42e6-ad98-355c92b14853	6140a2e2-90ff-443f-8a36-60f2c090e460	Ni	镍	1.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
03b7dd75-5d5e-435d-a198-66fcb4ecb784	10cae77e-ed6e-45fb-8627-b3646504cff4	304	304不锈钢	76.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10042
03d53aaf-01a1-4c78-97c4-7712ab610dd8	04fff2b3-dcff-452c-9042-3acbe635a1ab	721	721	33.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10033
042688f1-c173-4cda-b7f7-28f4daedae95	b26d00c9-a14c-42ef-a71a-b8dd652ce5ad	Ag	银	72.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
045f8868-6e77-4fbf-acf5-29a1877f7d4f	fce0165c-28a0-4186-817a-c6d9e75fa69c	Ag	银	89.0900	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
046c7961-98e8-4aac-b62a-c89089dae5bb	c3f96784-fa3a-4486-ba67-053381f07cee	Ag	银	55.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
0486c0c0-6f28-4969-a9d6-a63ee9e6e671	26f774ec-c405-4e40-9762-34ab4c558a75	Ag	银	18.1685	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
05b60890-f732-459b-8071-216c5818943d	89b4ddae-0d45-4eb2-8941-7cfb2c2db28b	C	碳	1.2000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10012
05ba6913-a43b-4ce6-a7a0-4fe3ce807ea1	1c6e3b46-7b55-408a-a1cd-fbfc21505f99	Ag	银	29.0300	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
05f902a8-844f-4599-95ff-df9d8e2884ee	f3e9d8ef-b988-40a7-a9e7-583be73cbb51	Cu	铜	65.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
060ed2f0-42be-465c-91c5-02a91af5e5fc	1179d297-0719-45f5-a662-fb4aaa25e50d	258	258	50.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10061
062e2b0f-e6d8-4a8a-884e-6c979f0e6260	da518708-17f2-463d-9d66-f90ba452ebaf	Ni42	铁镍合金Ni42	32.9000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10029
065bd84b-c5f3-4cc6-af24-af6a42d9c027	3d6d585a-30ab-49a5-a72f-faabf1169779	Zn	锌	3.3273	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10003
06611322-bba4-418d-be2e-ece5a2689120	bcc5ac62-0d3c-49da-b22b-64dba5f33859	Ni36	铁镍合金Ni36	44.1000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10032
067e7dbd-1e15-40b8-957e-de7af98e805b	1c909bf8-9f1a-4391-9166-f5297c8f596d	Ni	镍	10.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
0694cd5d-55ab-45d3-82e4-d1a799d4bcc8	fee7a8bc-66ea-475a-bfee-0f88fdb73875	Ni36	铁镍合金Ni36	33.2000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10032
06a46102-02e5-4096-aa0e-cc0d1aa3c4a8	76dcd768-2d42-423f-96b3-2d3aca9f7871	Fe	铁	8.4548	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10006
079e9102-fbc2-4d66-bdfe-69c4e02a4ca9	f820b6d8-a2aa-433d-bc90-f47ae483de09	Ag	银	23.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
07a33504-7ee1-47cf-8517-479c875b57cf	80e9483c-13c5-4226-bd31-ddc28da6f299	Ag	银	6.6107	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
07c582bb-bf55-4e9f-aee0-5beb14a249d1	e21aa8e8-7220-46c5-a435-4020903d9b9f	Cu	铜	13.4000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
085de4f6-1235-43c0-b1bc-9fea79ce47fd	55a6f998-2148-4883-88d2-96b7b59f7da8	Cu	铜	4.8100	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
08a08048-5a0a-4125-90f1-51696ba1d994	da518708-17f2-463d-9d66-f90ba452ebaf	Cu	铜	37.9000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
093bada9-060d-4101-bd0e-3b400e2182fe	df5faf4a-af39-49c6-8b48-3db9da7acdea	不锈钢	不锈钢	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10027
09e5cb9b-9597-4e08-a69a-c02b634b0a63	862bcfc5-9b87-4771-b5e1-0a0132445fd3	Ni36	铁镍合金Ni36	46.1000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10032
0a43af55-e78a-4bdf-8d86-d2269fa1256b	ce785606-3d18-440a-9cb4-0b71c1604b99	Ag	银	89.9000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
0a4bcc99-1f7d-47f1-a359-681965d297b2	7fe8f0e6-848d-4b5e-9b99-883b6e97578d	721	721	44.3000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10033
0bcfde5f-c4d6-41bb-a880-73b4fddc7478	862bcfc5-9b87-4771-b5e1-0a0132445fd3	721	721	46.2000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10033
0ca30aab-daa9-479a-b429-85b9863bee7c	5306d5b5-e27e-4957-89e4-91e5b646ceb7	Cu	铜	66.7000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
0cf24de0-971e-417c-99a2-da993421d541	96727d1f-6578-41d6-9c5e-023533f9efc2	Ag	银	88.2000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
0d3715e6-d51b-40c2-86d4-a82998ddf043	fce0165c-28a0-4186-817a-c6d9e75fa69c	Sn	锡	10.9100	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10004
0e12c6ab-21c6-4972-9744-03d456c9bc63	463ecc78-be69-4bf3-87a4-6e844bd79102	Ni	镍	10.0000	5.0000	15.0000	f	2	2026-07-09 09:09:47.243173+00	10005
0e13dfdc-d204-4735-8f01-262b28568b5d	086086f0-665f-4063-a031-9ce9109eb57c	Pt	铂	91.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10058
0e627e92-80e8-45a8-a0b7-eee2796541d3	ec83b09c-ec49-4c99-b2eb-d7c09b4cee84	Ni36	铁镍合金Ni36	29.7000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10032
0e6aaa84-b6a0-4ef2-8829-126a821479de	00c0778a-4f02-4c4f-bcc2-4bcaffc01ba2	DC04	冷轧钢DC04	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10026
0eb6a97c-484a-4dbf-a167-9e407e48fc47	f26b91bf-60a4-47a8-a177-17176752dd9f	Sn	锡	6.9100	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10004
0f12ca32-6b48-435c-b507-ef5058eed40e	4f3e7743-67fd-4cbe-8cbf-a3ce475c4d26	223	223	18.4000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10030
0f367efd-bc61-4edf-beb7-a13c63eb92fa	29a4c540-c221-495d-b131-bcf21c2b2753	Cu		100.0000	\N	\N	t	1	2026-07-10 02:27:03.278588+00	10002
0f677550-2290-4a50-9059-f58f57787f80	b6cb1c61-8d87-4670-b5b5-d775a036fecc	206	206	36.3000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10034
0f93083c-2714-4731-80a5-778b256cf110	dfa9d0ac-e171-47a1-aaea-67874b076ad5	Sn	锡	3.4037	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10004
0fa742d0-b1e9-4a81-808a-dfd8cc89e67c	76dcd768-2d42-423f-96b3-2d3aca9f7871	Cd	镉	2.7200	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10017
10be6218-1786-46ff-949f-8444ae89a7d9	cd0ab947-8989-4b17-90aa-e49f654c89f9	Ni	镍	31.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
111d3b71-760f-4398-9235-1a02842a084f	7deacf50-6871-49e1-b5ae-d8feb2ef6544	Ag	银	24.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
1314ad62-f6b6-4d51-ba88-922152d9136a	59420dd7-6f66-404e-863b-7c15c9382829	Mn	锰	2.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10010
1323db5e-1297-46e3-9c07-38272f6da633	c7689fee-e57e-4a3b-b4b9-fb6e8b18eaa2	Cu	铜	40.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
13292f25-c1b7-4997-9b4d-2a1191f68099	f61dad71-318d-43f1-9f28-5968f2ad2b02	Cu	铜	85.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
139c4e16-f3c0-46ab-8d21-4ccec9a35a37	21f06530-86b1-4a97-a093-f9b41344f80a	Cu	铜	91.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
143baa1c-30b7-467a-a7f9-3709be7aa939	01e59734-c875-47d0-991a-335ce540c9a4	Ni36	铁镍合金Ni36	48.3000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10032
15006cb5-6174-4d25-a615-2b872dbed1a1	1aaca26d-bf24-4ff7-ac32-9a0142064841	Si	硅	1.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10011
15d4f51c-5f1b-4136-b066-34df5c5ec5fb	04866efb-8112-4d5a-96b4-88a911a6bdf4	Ag	银	50.5000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
16012e3c-1598-44ef-880c-594c39e448bd	e232eadd-495a-4ce2-8ddd-b3885cb96db9	C	碳	0.3775	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10012
16fe8883-0e75-4087-ad12-235559c6afd9	01e59734-c875-47d0-991a-335ce540c9a4	223	223	40.6000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10030
177123f3-2033-4752-85d5-1d62e6b97cbf	76d085ba-a3fe-41b3-b4be-d7b233917aa6	Cd	镉	3.2727	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10017
17f17cd0-69cc-4493-8b5b-06e3cf1ed172	4f3e7743-67fd-4cbe-8cbf-a3ce475c4d26	Ni36	铁镍合金Ni36	41.8000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10032
19be3c09-24e6-40fe-8e85-228f032845bf	371b3ee1-c69f-4416-9b89-526d612c8c5b	Zn	锌	8.5100	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10003
19f50402-2ec1-46e6-ae30-a803c9a8603f	10cae77e-ed6e-45fb-8627-b3646504cff4	Cu	铜	24.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
1ad90955-202d-4131-81d5-080d2d9ec4bb	3104c8a8-c662-4ce5-94bc-b185db63c80a	WC	碳化钨	27.7105	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10024
1aff30db-8b7e-4c95-8a67-6cd4ecd8d6cd	b2476256-f6a1-4ed1-b812-dce8e8a075cd	Cu	铜	90.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
1b26426f-eb50-4c03-82a4-93a6fe41261b	c3d523be-2083-44eb-8f9f-9a2beeec198a	Sn	锡	9.5500	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10004
1b4e1b92-74ee-45f3-a278-36d1689c2624	e3b4f409-ad50-4ac2-afdd-ea1e968814f4	Ag	银	20.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
1b9c92e6-21ec-4d4b-aed5-47759dcc0163	c3659b23-f0fc-4647-bd19-2f9b9af2482d	Ni42	铁镍合金Ni42	22.8000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10029
1c22f2ba-bd17-4770-b0a7-a5b6bc3fab58	ce785606-3d18-440a-9cb4-0b71c1604b99	SnO2	二氧化锡	10.1000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10064
1cf771db-a53e-4af8-8730-9b52f42ff1c9	97a4fe12-d697-4165-8ea9-4e71a3729f42	Ni	镍	3.2300	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
1d477687-55e0-4ba3-a995-9108e1b051d2	9bc05d51-8d4b-4f2d-b984-ae1413a46366	Cu	铜	1.2967	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
1debe0af-bb22-465d-b063-660cdff31a85	2a7f544c-812d-4aa5-a655-b5b8db12b3ab	C	碳	3.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10012
1e542c30-32a9-48e3-af16-282cc68534d3	f0553d14-0107-4d88-8ded-705e82121170	Cu	铜	49.8935	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10002
1e898986-032c-4437-a5c5-f5263b63f37f	5bae09f1-6d8b-4a20-9c08-7e7cb0c98022	Ni36	铁镍合金Ni36	41.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10032
1ec4654a-19c0-4c05-af0c-25403c94878a	a9a41a22-386f-4a77-9b6e-a59c8d9a7c0f	Ni	镍	6.5179	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
1f204a21-9b31-461f-a540-f0552cc0053e	95b39158-f761-4963-9021-787b9840c66f	206	206	41.1000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10034
1f6a714e-bb60-496b-8278-dddff86dfa20	41a041e4-a333-4631-8010-465cf189b797	Cu	铜	66.2258	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
205f7767-3b5e-47cf-8c6c-de7ed3077b73	f6c56d18-f81c-4de8-834a-ba9e9fa34b4c	316	316不锈钢	94.4000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10028
20833f44-e477-4cf9-83ea-41fff4b46d4c	9cf9e3e0-d2d2-448e-a98a-364d71285d5d	Ni36	铁镍合金Ni36	41.9000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10032
21686393-c18c-4d55-8f90-36e633998f78	c3a7f6e2-1c05-4397-8575-8bf7fbfdc61c	Ag	银	24.3431	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
227c5a92-5744-4a76-860c-fe971e0b0e7c	2ebd1418-de5e-45a1-9728-050200677fce	Zn	锌	7.2165	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10003
228db876-703e-4fd5-a890-4ec2d3b453c9	f3e9d8ef-b988-40a7-a9e7-583be73cbb51	Ni	镍	15.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
22bcbe4c-fccb-4304-acab-ff127872b6e8	9243d1fd-6112-4812-94f1-c9892aa96616	Cu	铜	80.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
231446e6-314c-4a10-a6bc-402636dbea2c	a69b7794-1595-4b95-99b3-9cf7b91ff723	C	碳	1.2207	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10012
23f60d33-54e4-4b1a-9788-796b2d6946db	4d77e48d-6c02-4a23-a922-210224b38429	Ni36	铁镍合金Ni36	44.2000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10032
24863342-e632-4c6d-b347-f6b217ef4218	b6f8d5af-765d-4508-983d-95a621a83e22	Ni36	铁镍合金Ni36	46.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10032
2529f772-489d-4fcc-9073-19f47049c1b9	3ade50a0-d8fe-41ab-8895-e860b49b957d	Pt	铂	91.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10058
257710e8-6654-409c-b6e6-918758838100	f0553d14-0107-4d88-8ded-705e82121170	Ag	银	34.6236	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
25ad6588-cea7-4f5b-bb95-0bd8dbc5a306	7060d935-8631-4fe1-8b95-59c86397537e	WC	碳化钨	71.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10024
264b1df8-de74-49a0-a286-86e8ae63ca84	08d9078b-e819-48bd-b18b-d2485ca972cf	721	721	37.2000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10033
2685d12d-c3fc-43d3-898f-ab20f8dbfe56	5673c733-818f-4da4-85db-0d9b95698537	Ag	银	15.2132	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
26d58594-2533-4d5c-b658-426500f483c2	4501a11e-b579-4d3c-ba13-6267ed9295e2	Cu	铜	90.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
28bf7a20-439c-460b-831b-6a1e547d1a7c	5bfc6195-435c-4718-a31a-2eaf2b5241b8	Sn	锡	12.6300	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10004
295b4e78-c8ee-4ad6-b8b2-50825653efda	1d86a091-a53c-4289-b0b4-c15e4b0f16bb	Cu	铜	70.0000	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10002
29d274db-f06f-41f4-b285-0b463a8d0ac2	59420dd7-6f66-404e-863b-7c15c9382829	Cu	铜	58.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
2a2acaad-7e17-40a3-9f6c-64dd840e9590	c974375f-9074-4cc9-be57-7520e46cca17	DC04	冷轧钢DC04	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10026
2a3ecbc8-182f-479d-b349-61f2fe9df688	c7689fee-e57e-4a3b-b4b9-fb6e8b18eaa2	W	钨	60.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10013
2a5e8a46-f442-40a9-b8a6-654fed32821c	a69f4a27-1f03-4bb4-95a8-1db52da8c37d	Ag	银	30.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
2a6967a5-aa63-43c3-aac6-93f7bef4eca2	50b4a9f5-60d7-48fe-ab6e-9e42bea0bc46	Fe	铁	10.8870	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10006
2b5b8686-548a-459e-8740-dafa05a7695c	e263e33b-bb13-4303-9de7-b14b64bc9545	Cu	铜	68.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
2baf71db-f3bf-4bb7-8f93-e95948cae87d	f1d384a3-d3e6-4a77-8234-141b16f1d0ee	Ag	银	30.9597	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
2bcff4d1-0dde-4b7f-8a7d-61dbe1805e46	8ddd2ea7-3478-4d5e-9504-d219e6c537a9	Ag	银	87.7446	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
2bdf0c12-11e4-48b1-9e56-c9070a20e522	ff9901f2-e25e-47bc-a364-4752d673f821	Ag	银	69.4292	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
2be5e264-e5da-4b02-8be0-a737a656b3e0	37fc0e2b-bca1-4bb7-b319-42f7190faff8	Cu	铜	47.3432	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
2c5ad2f5-a68b-471d-9d88-e04be9beae10	efb6b982-64d9-4310-a3d2-520ef7eb1240	Cu	铜	85.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
2c90510e-876d-4ed6-90c0-2799b9f0e4fd	72b8d22f-355b-4435-9afc-4bb3efa53a2d	206	206	45.1000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10034
2d6bfa0a-cc95-4c50-a856-17f3bf14bfd2	efb0d8a7-1618-432e-9d96-fa0033e4795b	Ag	银	88.8800	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
2df4ccdf-8d3d-4e13-8f15-c18908be62dc	e21aa8e8-7220-46c5-a435-4020903d9b9f	721	721	43.9000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10033
2e08cef1-ef79-4de1-8fa4-066940847c5a	95b39158-f761-4963-9021-787b9840c66f	Fe	铁	17.7000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10006
2e0e329e-d4ea-4530-a4fd-78a254956725	25bf4fad-56b4-41db-bb4c-a4deadf0b40d	Ag	银	86.1500	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
2e1097b9-9544-4ee2-9fb8-ba7c037778c4	221ad76d-8c8e-4fec-a6df-25b333f8b22b	WC	碳化钨	13.6016	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10024
2f512cd7-8943-49ee-9f9a-d30b3378a3d6	9243d1fd-6112-4812-94f1-c9892aa96616	Ag	银	15.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
2fe5bd06-79fd-4135-a087-cd6af036c83a	40cab825-c406-4546-8701-d7d0e8767b32	Ni	镍	24.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
30a9d8df-0a75-4692-bba1-464a11434e7d	76dcd768-2d42-423f-96b3-2d3aca9f7871	Cu	铜	58.6153	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
310b0386-beea-4776-964b-fb81cb51547b	478d4236-b1c1-429f-8f17-7888738c41c9	W	钨	50.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10013
31409273-189f-4b03-86fc-c7105d7abcdd	60a70cdf-b79f-47b3-90ea-f6cf365e6c29	WC	碳化钨	11.8000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10024
314af4a9-633e-4995-960f-4ca31c0c7a91	fe34c3da-34c7-4f38-8e7a-9950b2cf385d	W	钨	71.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10013
317cdf31-fd59-4aff-8726-a6bc42f191c6	b2476256-f6a1-4ed1-b812-dce8e8a075cd	Zn	锌	10.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10003
321eeb4c-5927-478d-ac74-cedc40291c52	820f7125-7c66-4d93-b45b-d7df98cff7d6	Ag	银	8.2000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
3289675b-d097-4d08-aa11-91d5532d7437	2e7f85ec-a646-461d-8439-12a8ffaf88f1	Cu	铜	75.8864	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
329f254b-a9df-4e58-933d-b71bc41e8933	83312bcb-f91f-4f8d-b152-79b04bd68790	Cd	镉	10.9033	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10017
33599d48-f4fe-4ae6-a593-e138e5e1b873	a2e564a8-52ae-48b6-8a8f-d6cceb7e9797	SnO2	二氧化锡	3.1700	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10064
335f21cf-5ac8-4db5-93fd-1d4b814fdea5	fb9508d6-399a-43be-8874-121a32af86a8	Cu	铜	10.0000	\N	\N	t	2	2026-07-09 09:09:47.243173+00	10002
33a3e76d-9417-4bef-8905-7163469162bc	2d7db319-c7cc-404c-badc-56f1feed6ace	Cu	铜	55.9925	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10002
3483a2d4-87e6-44ac-b368-87f1832032dc	4a35a3cd-cbb2-4b5e-a91a-1e3a2d379dc1	Zn	锌	17.8200	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10003
34d52e51-e0bc-4a69-bb99-34af523f2022	6a62285c-6ab7-4edb-980d-8e1cdd9f7c84	Ni36	铁镍合金Ni36	39.7000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10032
368e3961-d78b-431c-adee-c60c8c33222c	4857fec3-6b03-4a94-a6ca-035af40402a3	C	碳	3.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10012
36918db9-169e-456b-be3e-1ca7f8f8f242	2a4147d1-8d39-429d-b0a7-2b12e83f0901	Ag	银	60.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
36b0745f-e041-4151-837c-bdc2187046c4	ea6ac467-244e-4998-a30f-564eefae4d44	Ag	银	75.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
36da4560-3b9c-430a-8ff6-e9744033ff71	1a3ab7f1-545b-4660-9fd2-cfe0add4e3b2	Cu	铜	94.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
372c6bce-82dd-46b2-a38b-6d735a67b23f	abab7bc3-ac6a-4209-9948-d44b72c1ccf1	Ag	银	90.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
37fd7947-a3ca-4c6f-82f6-608cdacecd6d	9284fcd5-b572-43c2-9611-8476af12c469	Ag	银	50.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
38fbb0e0-b6b9-4a83-ac5e-4a83a67327d1	5673c733-818f-4da4-85db-0d9b95698537	Ni	镍	1.6904	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10005
3912bce3-200b-4e43-adda-e1b6e3ae3e16	1ea2f76b-6516-4bf9-aef7-9f88a1f9bec5	191	191	49.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10031
39b01a9a-f1bd-4717-aa25-019a8fdf3fee	3f94721b-e651-4e4d-9eb1-0c9b226fa1ea	Cu	铜	68.9500	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
3b1ad7b8-7fec-4325-a428-82caa008aa2c	5bebf631-6bda-4046-b40b-541de375da23	Ni36	铁镍合金Ni36	52.9000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10032
3b7333d3-c6a2-4385-a295-5ef3ce17d65a	1fa6836b-59ba-4368-a3f9-8fbf36ef0179	Ag	银	88.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
3bd34984-2123-4c19-b88f-4a3091a05058	b0299f7e-c7b9-4ded-b39b-c3548e87b9af	Cu	铜	14.9000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
3c6c8d04-df6e-4315-9ecd-29e7feb8c455	fc442726-a06f-4a9d-bc96-aafcf1f22df2	Ag	银	1.9500	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
3cd2e175-b4a4-4979-a35c-86f70771b630	7060d935-8631-4fe1-8b95-59c86397537e	Ag	银	28.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
3d8e2179-66e3-44d2-9979-83113d769773	47d91e77-b53c-4465-b537-7dd553ff558a	223	223	40.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10030
3e0fd3d9-5af2-4062-83c7-d835d202a882	4c2e81f1-0cd9-45c3-8a39-5caecb0ce3c5	Ag	银	18.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
3e87aebe-f4db-49f2-ade7-d77c8c0f1386	b5bbb100-c88e-4009-85de-9205e4878b1d	Cu	铜	15.2000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
3eb8d004-8d54-4352-a9cb-3af2bb4f2cfc	ea6ac467-244e-4998-a30f-564eefae4d44	Ni	镍	25.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
3eddf425-ba2b-4ae1-982f-ba60c5867d1d	5b05ad35-bc1c-4868-b74a-49a3678a5641	Ag	银	68.1054	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
3f704b3d-a27e-4035-abc5-228869893e8e	eeb9955e-8594-42c5-b3f8-b1111cbb4611	Ni36	铁镍合金Ni36	30.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10032
3f8fa1c8-1460-4666-9836-b9296bd6e5d2	2ebd1418-de5e-45a1-9728-050200677fce	Cu	铜	65.7835	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
3fc14c35-e197-40fe-bd37-37699dffe9b2	e147f3a3-b61f-4190-96d5-e0394d3250d3	Ni42	铁镍合金Ni42	33.3000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10029
3fc5febe-1b3f-45cb-9cfe-e577289cbe7a	b80b544c-971a-4fab-b029-c084ea9a38ca	Ni	镍	32.2466	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
3ffed055-a2d7-4990-a042-6f49317d7cd5	2a7f544c-812d-4aa5-a655-b5b8db12b3ab	Ni	镍	1.8900	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
40300e5c-ee3e-4542-b806-6517cfa8a0f9	50b4a9f5-60d7-48fe-ab6e-9e42bea0bc46	Ag	银	31.9657	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
40431203-7b0b-421f-aff5-3c89c7fc86f4	37e9db3c-0a2c-4366-b9dc-7ec075b8f6a6	Ni	镍	0.1500	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10005
408116eb-5158-4fc1-bfd8-bb0a2c49b436	fa1b8a50-5127-4ad4-822c-3b501d196452	Ag	银	40.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
4099de56-30d8-4978-a98b-11b25c7667ad	2ebd1418-de5e-45a1-9728-050200677fce	Ag	银	27.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
414d58fb-38c2-4676-9d6c-e14d5039d72f	04802c85-4159-4afc-8852-f800ffe86ad4	Ni	镍	0.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10005
42d4e60c-c3ad-440f-919b-adcbf703f10a	7ff9c307-1779-4959-92f8-d3de57324c63	Cu	铜	19.2000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
4306b398-8c56-40ed-8fc1-4fecd5eb4e55	f0553d14-0107-4d88-8ded-705e82121170	430	430不锈钢	9.7432	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10044
4342ac4f-cca2-4e73-9269-5af3ac19725b	37fbd185-0e80-4362-b753-d9f03ab0ed3e	Cu	铜	72.5346	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
4364a3b0-2648-4b68-ae8c-977611a8a0f4	ca513ca6-09e0-4542-9fe8-4a02d8ccfbf9	206	206	41.7000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10034
44c69f1a-bc56-4e24-b99d-d94c7254d70a	3f94721b-e651-4e4d-9eb1-0c9b226fa1ea	Ni	镍	0.1600	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
44f01df1-3c2a-450f-97ea-80f07d1267b6	f820b6d8-a2aa-433d-bc90-f47ae483de09	Cu	铜	74.4500	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
46dc9115-0284-48c5-a5d6-daa2739c47bd	079ce89b-1280-491a-a09f-d064e83119b5	Ag	银	96.0369	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
472822fd-c6b9-427c-b873-9a71d11e3827	35240c60-796f-415a-bf6b-c9785fad00b7	206	206	50.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10034
48341b46-eae6-44df-909a-706054ca01e7	95767ffd-a20c-4ccd-a2b4-daebe24fbf28	Cu	铜	40.3000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
484a8625-b731-4d97-9cce-fac00c72be0b	a69f4a27-1f03-4bb4-95a8-1db52da8c37d	Cd	镉	5.2941	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10017
48889404-4574-481a-a186-516e01a312f3	6fcd84cc-b9ab-430b-9144-50d64e7e3138	Fe	铁	50.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10006
48d20e5a-7673-4824-8ea9-d04aaf308c85	7d25a390-6d06-44e3-bddc-d311c67fde08	In	铟	4.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10019
48dd806e-bce6-40b9-a994-94071b27651e	a2e564a8-52ae-48b6-8a8f-d6cceb7e9797	Cu	铜	68.5901	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
48fa0222-ab1b-44cd-b36f-0b7bd993d35f	83312bcb-f91f-4f8d-b152-79b04bd68790	Ag	银	89.0967	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
4945dfb3-8451-4c24-92dd-001d845b3b62	f3b84e29-a53b-4a3f-8998-ac3b73956b2a	721	721	78.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10033
49a424f8-4ade-4b43-900d-74d4ebc071a5	4ba9a5ee-c452-4844-9081-43edb75a9449	Cu	铜	93.6000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
49bd6d94-bd25-40f9-890c-387059ce5e92	0ff32a6e-740b-4914-84c4-d47f91b791ca	206	206	33.8000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10034
4b896365-8a21-4042-9531-69b427de6dab	ca513ca6-09e0-4542-9fe8-4a02d8ccfbf9	Cu	铜	16.4000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
4bc2b170-f689-4628-9cc7-808b832a8cec	9284fcd5-b572-43c2-9611-8476af12c469	Cu	铜	50.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
4c05658f-f83f-483d-b08f-9cd65bdbb809	bb199939-d7ea-4b58-aacc-d02b7accbcc2	Ag	银	33.6777	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
4c41fb51-10fb-495f-8e2c-72c5583aa153	9c84a821-338f-48cf-8864-721ef45eae47	Ag	银	76.8571	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
4c6b0345-3e91-40b9-abf7-6acf303ec3fc	5af07f24-5515-4375-8363-27c11c61c663	Cu	铜	45.7522	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
4c98de27-d5c5-4c0f-87af-bd756383d643	224ba8d5-fc33-4bd8-87d7-b0ec83ab348d	Ni	镍	20.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10005
4da3e416-d6cb-4774-8705-9d206772d2ad	da518708-17f2-463d-9d66-f90ba452ebaf	721	721	29.2000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10033
4ee66b6d-177f-4eee-a478-50f8973b6bf8	215ecc09-baf7-4e6f-901f-3519b5ccc15e	Ni36	铁镍合金Ni36	48.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10032
4fa00d12-45d6-4aea-8aae-5512ecd14b1d	80aaf9bc-f509-48be-ab85-fc6da2ff3c5e	Ni	镍	0.0023	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
50023f7f-699d-4496-aee0-86bd1581961d	6ffc6475-d62e-48ab-983d-f1e720fb00be	Ni42	铁镍合金Ni42	50.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10029
50adb329-e9d1-4c70-bf08-c199f4287ba5	80e9483c-13c5-4226-bd31-ddc28da6f299	Ni	镍	93.3893	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
50ae3a8a-3014-4a72-8315-6739bc57a2e2	ac019f92-eb6c-4c00-b5b7-a8a265f00337	Cu	铜	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
50be7782-5c90-4f43-a1b0-f75ce80a9419	41a041e4-a333-4631-8010-465cf189b797	Ni	镍	0.0676	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
50c11be8-ef25-4ed6-bd19-f030d0e0ba98	086086f0-665f-4063-a031-9ce9109eb57c	Ir	铱	9.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10059
50da5df3-8c00-4275-be47-2d25c042fb53	2e1b8093-6237-45cc-9877-6649fca2fcb1	WC	碳化钨	22.8966	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10024
5134355c-ceaf-49e9-a102-c6222bd06fce	16845148-5711-4da0-8515-f6efae71b93b	Cu	铜	11.9000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
51d212e0-0a28-4e65-bcd2-85be673fe6c9	5673c733-818f-4da4-85db-0d9b95698537	Zn	锌	29.0838	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10003
53539625-ffc5-460c-9fb4-bfa4669029a0	072f0885-665b-4638-8bd7-70e2492d2c5d	721	721	37.2000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10033
5357475b-29aa-491c-9600-ba4264a5aa5d	6140a2e2-90ff-443f-8a36-60f2c090e460	Ag	银	9.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
53893216-8416-4e51-ad3e-b873a59fa1f7	bb199939-d7ea-4b58-aacc-d02b7accbcc2	Ni	镍	5.2786	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10005
548b4972-311c-4f75-8619-bbcc4e468458	dfa9d0ac-e171-47a1-aaea-67874b076ad5	Ag	银	29.5459	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
548e1dfc-e835-4541-8898-a408c778a436	f1d384a3-d3e6-4a77-8234-141b16f1d0ee	Cu	铜	69.0403	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
54a5e2ec-1bc0-4d58-9fbe-55f131d0db68	9dc08f52-216a-418b-aa2d-4b2eb639e25d	Ag	银	20.3000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
54e98143-c72a-4264-9339-24a69f00b21a	72b8d22f-355b-4435-9afc-4bb3efa53a2d	Cu	铜	9.7000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
54f1b6cd-825e-41f8-b500-02ddf8d837dd	9c818733-49b6-4ef1-bf9f-cc655a9a1e05	C	碳	2.8184	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10012
55361165-cb09-46c4-9e05-b5dc7c1292ae	fb3e8e85-78d8-47e4-aa23-ed4952913a0c	DC04	冷轧钢DC04	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10026
55f2ccbe-0ffe-4112-9ddf-24dfe7b9f14c	b3b3c9bd-f9bb-49a8-837d-089f1099028e	206	206	50.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10034
5790df6d-591e-412d-8bae-a32b7ff8157d	fbef0766-aac7-4117-b0ed-2196804bd8c0	Cu	铜	28.8000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
588285e8-e22b-4767-8ff4-ad9608ba8ccd	efb6b982-64d9-4310-a3d2-520ef7eb1240	Zn	锌	15.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10003
58905f00-4317-4a8f-a7a2-929f3479cb37	4f87a69a-4553-4b07-aaca-30e38cb0cca6	Cu	铜	8.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10002
58e320ed-8f36-4726-8f11-3286b355dfee	1c909bf8-9f1a-4391-9166-f5297c8f596d	Ag	银	90.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
59c45ffa-a30f-475f-95d2-b26891964797	602ae8bd-4780-4372-a192-811fa7292a28	WC	碳化钨	27.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10024
5aa8397d-231e-46ca-840e-5419d5806388	9c766e9f-cb00-481e-b960-1243a7d28ee0	WC	碳化钨	29.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10024
5b024dab-4cd7-4894-9393-f07ec8df77af	b7b57363-777c-4d44-bf83-269e16481248	Ni36	铁镍合金Ni36	50.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10032
5b6514bc-1d38-4325-bf18-2ec713ad1a37	01e59734-c875-47d0-991a-335ce540c9a4	Cu	铜	11.1000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
5bbc6873-1328-45f8-b7bd-6990f80baf8b	986182be-ecff-455c-8afd-82a551d9dec6	Be	铍	2.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10009
5c3b64be-046c-499a-b421-ae4cf9fe029a	a2e564a8-52ae-48b6-8a8f-d6cceb7e9797	Ag	银	23.2399	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
5d0ee879-c206-455a-92c6-dc279ae91b00	9d6c39c0-b8dd-4bbe-b6a2-fdfb56b4fa5e	Al	铝	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10007
5df2332e-c2f4-433b-954a-26582825f8a5	6ffc6475-d62e-48ab-983d-f1e720fb00be	223	223	50.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10030
5e70a414-d8db-49c4-a956-900315ff8ed0	478d4236-b1c1-429f-8f17-7888738c41c9	Cu	铜	50.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
5eec432e-22ae-4eb1-af3c-1bc425ed9166	221ad76d-8c8e-4fec-a6df-25b333f8b22b	Ag	银	86.3984	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
5f1583a8-e771-4fc2-85c2-d5e18a7f7e18	d6a5a889-9747-454f-9657-eeb5614927fc	Ag	银	21.3973	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
5f7f3d71-7125-4ce2-9d62-956522d4f2f1	b1c47317-53e4-4751-87cd-57f03a28e22a	Ni	镍	50.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10005
60b09ea9-e212-4935-806c-2ab381002c10	d32f58c4-eecf-4627-9755-b7c3d25218e2	Cu	铜	73.7365	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
61d130d6-34c8-470e-b169-896865741273	2e1b8093-6237-45cc-9877-6649fca2fcb1	Ag	银	77.1034	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
62393ffa-66b0-4fbb-a580-a0aa29a221e5	b6f8d5af-765d-4508-983d-95a621a83e22	Ni	镍	8.2000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10005
62ff85ed-9dcf-44bb-a6f0-87d46c9c22c5	3104c8a8-c662-4ce5-94bc-b185db63c80a	Ag	银	72.2895	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
63196e6b-6411-49a5-91eb-dc1370cf3aae	04866efb-8112-4d5a-96b4-88a911a6bdf4	Cu	铜	39.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
63d5daa8-0933-40f0-b54b-9bdc5549c9e2	ec7dd7e6-5535-4aa4-803d-57bc1cf88e62	Ag	银	1.1688	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
640ff044-b3df-405a-be79-1553f6f007a8	079ce89b-1280-491a-a09f-d064e83119b5	C	碳	3.9631	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10012
64f408be-8b72-402b-97dd-6fab1e1ea9fc	fe34c3da-34c7-4f38-8e7a-9950b2cf385d	Ag	银	28.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
6573870b-4ca7-4275-afa4-fb4bb5ae9292	08d9078b-e819-48bd-b18b-d2485ca972cf	Cu	铜	24.8000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
6598f391-2394-4abb-9238-03afea60a3b1	41a041e4-a333-4631-8010-465cf189b797	Ag	银	33.6390	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10001
666cb5df-f5f9-49e0-b597-db8af1c627b3	b6f8d5af-765d-4508-983d-95a621a83e22	223	223	45.8000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10030
671a4639-0e0d-4566-84ab-0e75176eb505	8293ddfe-6803-4f89-b2d3-4c0e302dce9f	Ag	银	78.3100	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
6759a267-fd4e-4805-b827-6ce3a2abcccd	c3f96784-fa3a-4486-ba67-053381f07cee	W	钨	45.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10013
67a73688-4b44-4519-b468-c25eca9de696	4857fec3-6b03-4a94-a6ca-035af40402a3	Ag	银	97.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
67da5110-d1a2-4c93-8568-d970f110ab99	f9b98afe-64b0-4438-8070-2980a33d6deb	Ag	银	99.8500	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
67fc38f7-829c-4001-9679-6b4442e2cc72	c85016e3-8868-4cdd-940b-aea9ba393c11	DC04	冷轧钢DC04	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10026
68dffb54-6fc5-4f45-b06c-ff13cfb3c594	7e205fb4-17e6-48f6-8067-f6c07eab3b2b	C	碳	1.8000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10012
69339a02-a7a6-418d-bfd4-bf0dc85613b2	8858ed1b-d923-4ca6-945c-5cb30c3b13a4	Cu	铜	62.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
699ebb57-72fe-4e6e-853e-1413e305b4d1	08938737-5928-4dc2-a54a-bc45d1f3482e	301	301不锈钢	78.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10039
6a29fea3-2cef-4a10-bc25-c66073a5e654	4c2e81f1-0cd9-45c3-8a39-5caecb0ce3c5	Cu	铜	69.7000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
6a44c39f-e2b0-4777-915d-cee16244a0b7	25bf4fad-56b4-41db-bb4c-a4deadf0b40d	WC	碳化钨	12.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10024
6a4730d5-4667-40f5-a0ab-28711503bd37	0d8b783b-ea24-4519-b691-f2b99f684805	Cu	铜	34.8864	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10002
6b43ae9c-ed4e-40d2-822a-3ae0b3bd63c0	efb0d8a7-1618-432e-9d96-fa0033e4795b	SnO2	二氧化锡	11.1200	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10064
6b48eb1a-fdc9-4afb-a576-65e8aa7eaf00	64cfd1f5-a898-437f-b45c-4aae0cd5eca5	Zn	锌	16.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10003
6b4ab71f-7d0c-4c6b-bc28-3b4080b07e66	ca010d51-bc48-4634-99ee-ff38a4acc5c5	Ag	银	86.2800	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
6b56e2e4-642b-400d-8323-c71500f1c709	49d6c4ae-6208-4ed5-8f8b-88cce50fc34a	WC	碳化钨	55.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10024
6b5d5528-70ce-4c64-b1d6-d888f6e16258	75b601b7-6262-4f10-8de6-a26a0b883d24	Sn	锡	6.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10004
6b8dc031-6c79-4ab3-a4d0-76b3b35eba72	50b4a9f5-60d7-48fe-ab6e-9e42bea0bc46	SnO2	二氧化锡	1.3300	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10064
6bc4c57a-182d-430c-81a9-05d98b082337	523a3093-96e4-4961-a8d9-410da5513c18	Ag	银	84.9000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
6bd8a155-42bd-48b3-a4c2-41364f0820b1	e92dba85-510a-442c-a219-c04ae6cc1afd	Cu	铜	70.7000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
6bf3c8da-845e-4972-af71-00df68259afd	2da9837d-b8af-4c3c-8b10-0b1637bf05b4	Cu	铜	48.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
6c6f7e0c-946b-476c-9fc5-a76c99b476a2	c6a4eef7-a258-41be-8e89-03001e70c4d5	Zn	锌	24.1564	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10003
6cdd184f-422d-490f-b47e-c41356c4fbc9	49263f58-52c4-4f7b-ac18-045bcb8086a8	Cu	铜	70.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
6d5440e9-7c78-4554-828c-0c5ca7bbb5df	f26b91bf-60a4-47a8-a177-17176752dd9f	In	铟	4.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10019
6dd3ce1d-b8bb-40f5-82be-dab038b3d129	e173de29-769e-4562-b29a-36c572a192df	Ni	镍	0.0305	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
6e13faeb-5d82-45e0-881b-56f7cfe6e868	db7035d1-35be-4317-88e9-faa27f1ad245	Al	铝	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10007
6e49c66d-4375-4e99-964d-f990b0d33cb8	fbef0766-aac7-4117-b0ed-2196804bd8c0	Ni36	铁镍合金Ni36	45.9000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10032
6e701358-a138-4bb9-aae6-137be3bbd771	37fc0e2b-bca1-4bb7-b319-42f7190faff8	Ag	银	13.9128	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
6ea9c42e-32cb-48b1-8613-88937e78b6c2	215ecc09-baf7-4e6f-901f-3519b5ccc15e	Cu	铜	21.8000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
6f631d92-7c9f-483f-950d-90e0bfe934c3	3453f390-1ca3-4708-a70f-cad33f66ba39	Cu	铜	93.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
6f76c76e-df35-4112-a2fa-1c94e9a22c28	5b05ad35-bc1c-4868-b74a-49a3678a5641	Ni	镍	0.2049	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
6f7bdea1-6fe1-4351-b730-f9b6e88bce5e	7e205fb4-17e6-48f6-8067-f6c07eab3b2b	WC	碳化钨	22.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10024
6fdf37f6-77e4-4bca-bc47-01ad4f8c632d	89b4ddae-0d45-4eb2-8941-7cfb2c2db28b	Ag	银	88.8000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
70752201-3205-4d1c-93ee-f6c653912a2f	d997cd5d-1b3a-4f0e-b183-8b0a5c636622	W	钨	66.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10013
71a212e0-dd04-4997-89d6-e41e4dc57085	76d085ba-a3fe-41b3-b4be-d7b233917aa6	Cu	铜	72.7273	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
71a9e76b-5132-43fe-a7e1-651178aa908e	23566e36-e350-48e8-a693-cd51cabaeef8	Ni	镍	10.0714	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
727e30b0-7ecd-44cd-ae69-7c764d05207b	dfa9d0ac-e171-47a1-aaea-67874b076ad5	Cu	铜	44.5923	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
72b22e53-b11b-4962-8f16-2f6f273d942e	801bf1a5-fa78-4841-b77e-a278b74bd3ec	Ni	镍	20.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
72eab5bc-528d-4b4a-bd58-e6efe303b2e1	7fe8f0e6-848d-4b5e-9b99-883b6e97578d	Fe	铁	12.6000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10006
72f21c01-6f54-4a27-8d83-9e6d6dce2cf3	20f31339-c48d-4430-b6d7-91ca942040e1	Ag	银	7.7700	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
734399ab-1fc9-4440-ad18-7f7d95c8126b	55a6f998-2148-4883-88d2-96b7b59f7da8	Ag	银	80.9000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
73911908-7962-4a30-b94a-8034d409c93b	40cab825-c406-4546-8701-d7d0e8767b32	Ni36	铁镍合金Ni36	38.1000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10032
73c1279d-230b-413c-b492-afbfb16df339	bcc5ac62-0d3c-49da-b22b-64dba5f33859	Cu	铜	12.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
7413b6b8-8331-4b8a-bb65-ab4c8999807d	463ecc78-be69-4bf3-87a4-6e844bd79102	Ag	银	90.0000	85.0000	95.0000	f	1	2026-07-09 09:09:47.243173+00	10001
74bc41d1-d0a9-4f42-88d2-dbc06722d5b0	e7b5fb28-234d-492c-9430-5d0330ae726a	Ag	银	13.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
752183af-4d5f-49d5-a8e1-48bbd4888e59	4d2517bd-a294-4538-b064-3a19a0ffef42	304	304不锈钢	91.9000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10042
75529d0c-1c16-46c8-a048-76c46d19647e	5673c733-818f-4da4-85db-0d9b95698537	Cu	铜	54.0126	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
75804c7f-39b1-4695-8139-82e6559eb63b	4e584ed1-0684-4fc8-a95f-78ebb9112539	Ni36	铁镍合金Ni36	40.3000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10032
75bbe519-fbd5-4f9e-9922-40cc5ea0f0e4	30d5da05-6193-4b75-b861-ff7bbafa3d35	Ag	银	70.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
75f641e0-5ab0-488a-9d18-4b41c1d04fd2	35240c60-796f-415a-bf6b-c9785fad00b7	Ni42	铁镍合金Ni42	50.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10029
764effc2-78a2-42d5-a674-d252a38100df	b041f1fe-dc7c-4ad8-bfec-092688140761	Ag	银	30.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
7766f87b-bb0e-4bf7-8b37-fd1148486295	082e402c-b398-4bde-a56a-3fbe8310621f	Fe	铁	17.7000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10006
77f14a00-21e2-46f8-b7db-fbeadd37b6f1	a9a41a22-386f-4a77-9b6e-a59c8d9a7c0f	Ag	银	36.0177	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
781685a8-3116-4c1b-81ff-e95a5d1f0597	eab16559-b2ce-44e0-8020-678337e308e7	Ag	银	85.1500	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
78dffb11-6edf-4444-95de-9dccd366ca10	fcfe35f1-1ef2-4234-9bf0-6b5836c9217b	Zn	锌	38.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10003
78ff2c3c-2c44-499e-9273-43d925cbb983	04802c85-4159-4afc-8852-f800ffe86ad4	Cu	铜	76.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
79b676be-f413-4813-895d-f2396f222f48	e3b4f409-ad50-4ac2-afdd-ea1e968814f4	W	钨	80.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10013
79ea1b85-0728-491d-bedd-738b4c1c9804	ca010d51-bc48-4634-99ee-ff38a4acc5c5	Cd	镉	13.7200	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10017
79f9e18f-5123-4de5-9a9a-3d45ac3f2004	7d25a390-6d06-44e3-bddc-d311c67fde08	SnO2	二氧化锡	8.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10064
7a0249b3-dc7a-405b-b7a3-df15711faeb2	082e402c-b398-4bde-a56a-3fbe8310621f	Ni36	铁镍合金Ni36	41.2000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10032
7a323d04-41b2-4675-ab33-a6b70c0ebf5e	ff9901f2-e25e-47bc-a364-4752d673f821	C	碳	0.5708	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10012
7a40f575-b264-4e36-af65-9417ce575861	d32f58c4-eecf-4627-9755-b7c3d25218e2	Ag	银	9.6832	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
7b0610a0-64b1-456f-aa49-f5a7fb6be683	64cfd1f5-a898-437f-b45c-4aae0cd5eca5	Ag	银	50.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
7b28bd83-e295-4ef0-b10d-32512f325c41	96727d1f-6578-41d6-9c5e-023533f9efc2	Sn	锡	11.8000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10004
7b4dddcb-5460-46d6-8a3e-52d4eb2768d1	1ea2f76b-6516-4bf9-aef7-9f88a1f9bec5	Ni42	铁镍合金Ni42	50.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10029
7bbcba89-6cea-4c7c-8b61-c0222791d7ee	3d6d585a-30ab-49a5-a72f-faabf1169779	Ag	银	24.4000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
7bc0d2f9-1a67-4f21-8dcb-d164f135c917	af572e7c-ae19-4f7c-9e98-53876faf1629	Al	铝	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10007
7cb65a80-d242-41ee-8b1c-27bc40d49803	dfa9d0ac-e171-47a1-aaea-67874b076ad5	Ni	镍	22.4581	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10005
7d5d1e6a-620b-46e9-956d-4c9fe38b32d7	bb199939-d7ea-4b58-aacc-d02b7accbcc2	Cu	铜	52.3337	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10002
7e00d9a6-185b-4ab0-b00b-310408814558	40e773a7-5395-41e9-ad6c-ac52c062362f	Ag	银	70.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
7e4c8fd1-76b2-47fc-b599-c6c4f9c929b5	02097b82-34bb-41c1-a51a-09fbaffba85d	Sn	锡	6.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10004
7e76ae9d-2426-477b-a17b-ed2ad9341ae9	2e7f85ec-a646-461d-8439-12a8ffaf88f1	Ag	银	21.2200	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
7e8d8a5c-5002-4a77-ba18-4397fc9c8597	072f0885-665b-4638-8bd7-70e2492d2c5d	Cu	铜	24.8000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10002
7f12e949-d5ef-44c0-9d84-fdd335227073	9c766e9f-cb00-481e-b960-1243a7d28ee0	Ag	银	71.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
7f57813a-b249-4d97-8c1b-ada9a46ff4b6	c25863a4-a380-4f44-86d5-ad9d2253546e	Ag	银	50.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
80030559-65a1-47f7-b81e-667f343b1e93	3ec26d54-dce6-4d78-a631-806ceb98b155	Ni	镍	5.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
803e4687-3074-40d2-ac47-66f0ad7b3263	37e9db3c-0a2c-4366-b9dc-7ec075b8f6a6	Ag	银	99.8500	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
80c9d1cb-fe3d-4d60-9aac-bdc33f313355	a9a41a22-386f-4a77-9b6e-a59c8d9a7c0f	Cu	铜	57.4644	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
814225a2-c698-4c65-9bab-57bd26a50c5e	f0553d14-0107-4d88-8ded-705e82121170	Cd	镉	5.7397	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10017
81a1a1ab-6b56-4e11-b578-2e7df7d71873	5d5c1959-866c-4ecc-a7d1-d544f7d3b813	Cu	铜	85.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
828a3df1-875e-4ff5-91a6-e25cf5a26e3c	4ba9a5ee-c452-4844-9081-43edb75a9449	Al	铝	6.4000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10007
8350482c-37ee-4ffa-99d3-3c78c25bd7e2	b4651b23-541e-4c3b-bb01-9d2da76fe51f	Cu	铜	83.1220	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10002
83a3dad1-cffc-47ba-8652-4a2878cd3c83	08d9078b-e819-48bd-b18b-d2485ca972cf	Ni36	铁镍合金Ni36	38.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10032
84fe5924-800a-430d-8541-a2183a3473bc	f6c56d18-f81c-4de8-834a-ba9e9fa34b4c	Cu	铜	5.6000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
85047f2c-c194-485a-a369-ef80431eee12	1d86a091-a53c-4289-b0b4-c15e4b0f16bb	P	磷	11.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10057
85d3201a-c5e8-4b01-ab9a-c8eb0c686cb6	fb9508d6-399a-43be-8874-121a32af86a8	Ag	银	90.0000	\N	\N	t	1	2026-07-09 09:09:47.243173+00	10001
86647aa1-671e-45f3-98e7-2bb4f2933e64	e0e21942-8da0-43d5-b746-022ed9aa3c22	Ag	银	95.0000	90.0000	98.0000	f	1	2026-07-09 09:09:47.243173+00	10001
869caca0-c1af-408a-b6ca-1571f608d4d0	fee7a8bc-66ea-475a-bfee-0f88fdb73875	721	721	45.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10033
86e501dc-a6b3-42a4-ac5a-b6c174be83df	b3b3c9bd-f9bb-49a8-837d-089f1099028e	Ni36	铁镍合金Ni36	50.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10032
8709b719-4783-4488-a75e-27984bd59353	f042901e-4404-4db7-9089-41c70d81819f	Ag	银	90.8449	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
87bf94ee-af54-4d87-bd76-e2d94aafc739	4a35a3cd-cbb2-4b5e-a91a-1e3a2d379dc1	Cu	铜	50.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
87d5e7e1-716a-4c41-a17a-c97b43091a25	e7731aee-68c3-471c-9198-4f206c9a8f02	Ag	银	73.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
8837390d-3569-46e0-b8ac-59c1394b5fb5	7fe8f0e6-848d-4b5e-9b99-883b6e97578d	Ni36	铁镍合金Ni36	43.1000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10032
88a5a444-f404-4eea-aa13-eb619e7e1000	25ef4515-bddf-43f2-8b34-8df446b565cd	Cu	铜	70.0000	\N	\N	t	1	2026-05-30 06:19:32.065976+00	10002
88eb9805-e0a2-472a-8e60-c2a8b0f85960	9b42208e-2799-4bdc-9a62-85adb62fd3a4	Cu	铜	70.0100	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
893cb3e5-9ff5-4a7a-94a5-6f4e3b9a3be2	5bfc6195-435c-4718-a31a-2eaf2b5241b8	Ag	银	87.3700	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
894fc408-e4dc-48c0-85b2-da94d6223ad0	b5bbb100-c88e-4009-85de-9205e4878b1d	223	223	42.3000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10030
89e0d890-94f3-46d6-80f9-5b9d53be38db	7ff9c307-1779-4959-92f8-d3de57324c63	206	206	40.3000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10034
8a9ed878-ddfa-4b20-a0a8-a020f4f311a3	b8017b7e-0e27-4533-a1ee-5fbd1ea03cdc	Ag	银	70.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
8bd203bc-e12a-4d07-868d-98a49f36af0e	862bcfc5-9b87-4771-b5e1-0a0132445fd3	Cu	铜	7.7000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10002
8c30605f-b3cc-47b6-aa4d-86a15b4761e1	6a62285c-6ab7-4edb-980d-8e1cdd9f7c84	Cu	铜	19.4000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
8c647472-1339-4de5-badf-ff72c0f49939	8eef5043-6e48-4de4-a089-f92ba6cd94bb	DC04	冷轧钢DC04	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10026
8c653722-1700-4fc7-8286-6807ac8bc747	ae89a548-4960-4ab8-b0ab-e8ce799c58b5	DC04	冷轧钢DC04	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10026
8c6d245b-1f80-435f-acaf-08e18506d924	8293ddfe-6803-4f89-b2d3-4c0e302dce9f	Cu	铜	21.6900	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
8dc9d55e-1017-4738-b408-0279e0fe1bfa	5e2eb7b0-cf41-4ecb-99fc-6413b3ef73f4	Zn	锌	35.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10003
8e4d0d3f-1788-497a-87dc-31ad318acbaa	9a6de57f-f32e-4234-a572-0a732b0283d5	In	铟	3.1300	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10019
8f062a17-518a-47fd-99cf-546b00aacc75	20f31339-c48d-4430-b6d7-91ca942040e1	Cu	铜	57.1826	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
8f329cd6-2c98-47a3-8e51-154ffca1933a	9b42208e-2799-4bdc-9a62-85adb62fd3a4	P	磷	5.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10057
8f8625fd-9b54-4811-9346-c5abb0020298	f9b98afe-64b0-4438-8070-2980a33d6deb	Ni	镍	0.1500	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10005
8fb19113-f34e-4a97-b4b6-15971b1df6e9	5af07f24-5515-4375-8363-27c11c61c663	Sn	锡	2.9204	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10004
9009fac8-9cdc-4ed8-b340-4f8dba8ada71	e5cdd7dc-9b10-41e4-a7be-beb58e0fa6aa	Cu	铜	84.1410	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
9051165a-171e-4323-a872-d880783b4f73	4e584ed1-0684-4fc8-a95f-78ebb9112539	223	223	40.1000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10030
905a7e0d-e904-4a05-9317-a4adac8d17b1	c3846945-a01c-4270-be6d-bb662d90c8e4	Cu	铜	37.9000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
9079a585-d61c-42eb-82ad-93265f6902c4	e7b5fb28-234d-492c-9430-5d0330ae726a	Cu	铜	86.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
90e1dbde-b2c8-436f-91ad-0c590528a736	26f774ec-c405-4e40-9762-34ab4c558a75	Cu	铜	44.6952	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10002
90fbba60-4ce1-44b7-89d5-d5cab4315ff8	4501a11e-b579-4d3c-ba13-6267ed9295e2	Zn	锌	10.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10003
9145c817-32f2-4f09-83f5-6e7d0db5a4e9	95767ffd-a20c-4ccd-a2b4-daebe24fbf28	316	316不锈钢	59.7000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10028
919c8c2a-6bbc-4f35-8952-6ef01499e117	5af07f24-5515-4375-8363-27c11c61c663	Cd	镉	4.5219	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10017
91eb9397-649d-4f90-97f7-b54a40e5a421	37fc0e2b-bca1-4bb7-b319-42f7190faff8	Ni	镍	16.7633	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
9205d906-8b1d-4035-a169-75b42e73afab	7dfbd222-23ad-4678-a161-bdd0783a90ff	Sn	锡	8.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10004
92a461f1-7c3e-4155-b4b2-6b9d705bcd13	3e51fc21-d6ed-4b69-86c8-6ca6f11c79b2	Ag	银	14.4516	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
92f1fbf9-030a-4494-8e6a-b0b38829eb52	e0e21942-8da0-43d5-b746-022ed9aa3c22	Ni	镍	5.0000	2.0000	10.0000	f	2	2026-07-09 09:09:47.243173+00	10005
934a078b-9d10-484c-9a83-8786da0dab4a	2a7f544c-812d-4aa5-a655-b5b8db12b3ab	Ag	银	94.6100	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
9372a299-9a77-4a24-8243-f2068e3916af	0ea290f2-54e3-4e86-82a7-a3df1404fa33	Ag	银	84.8100	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
94ad4c75-4aef-4a59-9991-66da3873c8b3	2ed68b6c-895a-480b-85d8-53bef5fef746	Sn	锡	12.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10004
94bc2fd4-c52e-44fb-9b72-a921c6095f06	e71bcd07-fe25-4ee1-9763-66eee5f2093f	Ni	镍	10.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
954d2751-c4b5-47e7-95c8-7c3bb18829f4	70028291-48f2-4355-a1dd-f906266d67eb	Ag	银	38.6150	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
9561aab4-95a1-4343-9a1d-b975c0954c1f	ab751fb9-ea98-4480-bf5d-52b7219020fb	Cu	铜	42.4828	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
95637a87-1369-4885-84d4-49d4caae635b	5806deb5-639f-4d0f-867b-12e281b25761	Ag	银	85.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
9564f802-28b9-43c4-9b71-26cd04e771f8	1179d297-0719-45f5-a662-fb4aaa25e50d	Ni42	铁镍合金Ni42	50.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10029
95ac03d2-e896-4d5d-8b7b-01b4a3055912	4c2e81f1-0cd9-45c3-8a39-5caecb0ce3c5	Zn	锌	12.3000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10003
95aec40a-86e3-4ca4-9707-3f889fb741cf	207b1953-b9d2-42d7-aca2-72ebbb10f89d	Cu	铜	60.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
9613d31f-51e1-40f4-b166-5086006e5ef9	76dcd768-2d42-423f-96b3-2d3aca9f7871	Ag	银	30.2099	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10001
97148491-c259-49ea-b945-6da8b8d2444c	ab751fb9-ea98-4480-bf5d-52b7219020fb	Ag	银	57.1574	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
9757a7aa-62af-4909-908f-671630ef1902	726c4189-1c74-4b2f-a272-d4001088a69a	Cu	铜	65.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
9761deb0-ce66-44cb-9b49-a214bddf12c5	ec83b09c-ec49-4c99-b2eb-d7c09b4cee84	Fe	铁	40.6000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10006
97ba8ff3-f3a8-4942-bca8-42bda1ab5cdf	2da9837d-b8af-4c3c-8b10-0b1637bf05b4	Ni36	铁镍合金Ni36	37.9000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10032
97d430d8-cd70-4c08-a695-4afae23f77e6	9243d1fd-6112-4812-94f1-c9892aa96616	P	磷	5.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10057
97de025d-e7a7-473e-b96a-7800f3ea8bc0	a69f4a27-1f03-4bb4-95a8-1db52da8c37d	Cu	铜	64.7059	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
981df8b0-f48a-41f1-bbe5-6a17fed8b049	1fa6836b-59ba-4368-a3f9-8fbf36ef0179	Sn	锡	12.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10004
991a88ea-ad9c-4b49-8081-231b7fd83cf1	801bf1a5-fa78-4841-b77e-a278b74bd3ec	Ag	银	80.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
9950d070-82e6-4779-be2b-0025d967e086	986182be-ecff-455c-8afd-82a551d9dec6	Cu	铜	98.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
99774f87-9f99-48c2-9a92-ef6264fd8814	37fc0e2b-bca1-4bb7-b319-42f7190faff8	Zn	锌	21.9807	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10003
99b21431-2cdc-40fd-a8d3-c4db3ef58591	473a3e03-6628-4563-ac1f-32aa02e882a9	Ni	镍	23.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
99b7331d-ee20-474e-8b77-029661009d85	3058edf5-7083-4548-bce9-4bd922de3148	Ag	银	77.4286	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
9a2342e2-8516-4e1a-a9ac-fec198998858	eab16559-b2ce-44e0-8020-678337e308e7	In	铟	10.8500	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10019
9a4fe871-4b08-462a-8503-0b59f5a32cca	726c4189-1c74-4b2f-a272-d4001088a69a	Zn	锌	35.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10003
9a8c7055-928d-48f2-82d1-5b1f1b006a65	371b3ee1-c69f-4416-9b89-526d612c8c5b	Ag	银	91.4900	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
9ab56ad9-e15d-4ec0-a202-4ef8c39722db	d81ef824-54c4-4a30-b73c-bf252e22f3cf	223	223	50.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10030
9b2e32d2-01d3-46bb-9924-9b1576d5fee7	ac56bb7e-169e-4bdd-aded-3ac3508931d9	301	301不锈钢	16.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10039
9b6d28a8-8e05-4873-8781-6673bda6dadd	47d91e77-b53c-4465-b537-7dd553ff558a	Ni36	铁镍合金Ni36	44.6000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10032
9bb6291d-ea1b-497a-9b00-50ce6a279ef7	d39cddd6-4307-4af5-a20b-a22f27be180b	10002	Cu	100.0000	\N	\N	t	1	2026-07-16 11:38:28.872107+00	\N
9bf59323-f61a-45ca-8a1f-d2cc26618cf8	a06721f7-c777-4b29-8a0b-be7e38c628ab	Ag	银	43.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
9c11d6f5-d85c-49f5-9ccb-b6e8cbd9545f	b5bbb100-c88e-4009-85de-9205e4878b1d	Ni42	铁镍合金Ni42	42.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10029
9c2ef900-f078-49a7-add5-ddca17f9a905	bcc5ac62-0d3c-49da-b22b-64dba5f33859	206	206	43.9000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10034
9ca5f817-575f-4cd1-9649-eaa0e9a42feb	2d7db319-c7cc-404c-badc-56f1feed6ace	Ag	银	19.1570	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
9ce29fce-18c5-4887-889c-03c9c5b98392	5bae09f1-6d8b-4a20-9c08-7e7cb0c98022	206	206	40.9000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10034
9cf23d01-c0b8-42c4-8517-860dd8392d58	e232eadd-495a-4ce2-8ddd-b3885cb96db9	Cu	铜	63.4290	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10002
9cf9c037-8e23-45ef-ae91-9f60d504b99c	16845148-5711-4da0-8515-f6efae71b93b	304	304不锈钢	88.1000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10042
9d139980-f8e7-48c6-92a0-3689bd47f855	9b42208e-2799-4bdc-9a62-85adb62fd3a4	Ag	银	24.9900	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
9d1f6289-2049-4cfe-9c72-84e4881fbab5	71e29340-a5b0-4306-ab75-45a5afaa864b	Ni	镍	30.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
9d6369de-47c3-4f8b-9868-e71827c1c849	7d25a390-6d06-44e3-bddc-d311c67fde08	Ag	银	88.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
9e3a25e5-54e9-4016-8bd3-8eddf8debd9b	97a4fe12-d697-4165-8ea9-4e71a3729f42	Ag	银	29.0700	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
9e60a9bc-684c-4edd-ad4c-0c28f7df8efd	86f5fcc2-08f4-417f-9260-3527e3205bad	Cd	镉	12.5025	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10017
9e6113b9-2720-4f76-8f18-3b492831edf4	e5cdd7dc-9b10-41e4-a7be-beb58e0fa6aa	Ag	银	6.5100	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
9f126983-a0df-45a5-b957-5df1f98f4a03	3ade50a0-d8fe-41ab-8895-e860b49b957d	Ir	铱	9.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10059
9f1ed389-a565-4cd0-8046-7fd33ff55df6	30d5da05-6193-4b75-b861-ff7bbafa3d35	Ni	镍	30.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10005
9f5c9e3b-8a2c-46dd-af40-8c304f346f74	c25863a4-a380-4f44-86d5-ad9d2253546e	W	钨	50.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10013
a041852a-e610-413e-bfc7-55240f2e8638	1d86a091-a53c-4289-b0b4-c15e4b0f16bb	Sn	锡	15.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10004
a0466a4c-7da5-44c3-ba42-773c7d1d141a	37fbd185-0e80-4362-b753-d9f03ab0ed3e	Fe	铁	19.3154	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10006
a0714b27-a605-4157-a835-35181749d105	b80b544c-971a-4fab-b029-c084ea9a38ca	Cu	铜	41.0411	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10002
a0929a8e-8144-4d3b-b79c-9e12ead5a7e8	26f774ec-c405-4e40-9762-34ab4c558a75	Ni	镍	16.3850	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10005
a0f4d578-fdca-45b6-88bd-346237af0e57	20f31339-c48d-4430-b6d7-91ca942040e1	Zn	锌	35.0474	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10003
a19a31c9-6cb5-4834-acfb-80a0ab286fe3	a6012647-8186-4690-b929-5ed0f37a7a0e	Ag	银	67.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
a2a1b019-1a79-4e31-95b6-7c301b642bf2	fa1b8a50-5127-4ad4-822c-3b501d196452	W	钨	60.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10013
a30cc109-ca28-44f8-8f66-bf3b02d42c7f	b6cb1c61-8d87-4670-b5b5-d775a036fecc	430	430不锈钢	27.3000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10044
a338749d-2f5b-410d-b5e7-719e1140a698	c3a7f6e2-1c05-4397-8575-8bf7fbfdc61c	Cu	铜	69.6043	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10002
a3624b4c-1ab6-46f2-acc7-c90bd9204e0c	59420dd7-6f66-404e-863b-7c15c9382829	Ni	镍	40.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
a368cd83-868d-47a2-98ec-fee04864b92f	ecb009d4-4ada-4d61-8855-deecdd368f9b	Ni	镍	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10005
a3ab0d29-9636-4633-ae7c-eccf1d3903ae	7e205fb4-17e6-48f6-8067-f6c07eab3b2b	Ag	银	76.2000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
a45a0472-c6e2-4a02-9808-f1861bcf8125	4f1f3c23-f4c9-46d2-97cc-9915d8dc8339	Ag	银	85.0000	\N	\N	t	1	2026-07-09 09:09:47.243173+00	10001
a4e75fc1-df3c-4422-9321-b454fa92b5ce	76d085ba-a3fe-41b3-b4be-d7b233917aa6	Ag	银	24.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
a54987b2-1af1-494e-ac6e-c3733324cf1f	f61dad71-318d-43f1-9f28-5968f2ad2b02	Ni	镍	15.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
a5dd08e7-8dd6-4416-a954-a585773836d7	84c8c932-2c45-4083-97ca-3965f52a1c7f	C	碳	2.5000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10012
a673a307-91cb-4f10-8af6-85bab01b682d	388dac1e-b577-4143-8f64-cb5ce2464366	Ag	银	71.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
a7746c73-6471-4480-b811-aa24ca7e54b2	b0299f7e-c7b9-4ded-b39b-c3548e87b9af	Ni36	铁镍合金Ni36	44.6000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10032
a7b54a8e-f05d-43ec-93ec-2b7bdc26aa7a	95b39158-f761-4963-9021-787b9840c66f	Ni36	铁镍合金Ni36	41.2000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10032
a7d17a33-c4ef-4d0e-be51-9595b2651e68	c3d523be-2083-44eb-8f9f-9a2beeec198a	Ag	银	90.4500	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
a9393299-b746-434e-a63c-626b2d9da1a5	a6012647-8186-4690-b929-5ed0f37a7a0e	Ni	镍	30.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
a9b6a484-e4ef-4eb2-a3cd-799136037bd3	84c8c932-2c45-4083-97ca-3965f52a1c7f	Ag	银	68.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
a9c8db53-bcdf-431a-b674-7fc1c9d04342	f820b6d8-a2aa-433d-bc90-f47ae483de09	ZnO	氧化锌	2.5500	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10095
aa273f8a-3e15-4b79-ad2b-f63769076a1b	4f87a69a-4553-4b07-aaca-30e38cb0cca6	Ni36	铁镍合金Ni36	46.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10032
aa8906f7-321f-41f2-82e4-493c887c79a0	0d8b783b-ea24-4519-b691-f2b99f684805	SnO2	二氧化锡	7.8136	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10064
aaec1bfd-0cba-499a-87ed-47d9a8ec99aa	b8017b7e-0e27-4533-a1ee-5fbd1ea03cdc	Pd	钯	30.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10021
aaf36378-a899-4215-8303-4b74354cad00	b0299f7e-c7b9-4ded-b39b-c3548e87b9af	223	223	40.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10030
aaf7b36e-8aa9-42d0-adb9-3ed08e83c162	e173de29-769e-4562-b29a-36c572a192df	Fe	铁	79.6421	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10006
ab346556-300c-47a1-a45d-ab1cf838dac1	072f0885-665b-4638-8bd7-70e2492d2c5d	Ni36	铁镍合金Ni36	38.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10032
ad16354b-6764-45a5-b703-90ac0ef48929	3f94721b-e651-4e4d-9eb1-0c9b226fa1ea	Ag	银	30.8900	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
adf4a262-27bd-4144-8adc-2061a59f8f5e	cf645337-94a6-409d-a6b9-6a64c7ff33ea	Ag	银	60.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
ae2ddea3-ceb3-4cfc-86a9-7465c97cc137	9cf9e3e0-d2d2-448e-a98a-364d71285d5d	Cu	铜	16.4000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
aeac3338-f434-463b-ab9e-3ae1f73da0cb	eb56bf6e-2b22-44cc-a697-cf7fca68854a	Ni	镍	25.0000	\N	\N	t	2	2026-05-30 06:18:22.115397+00	10005
aef6e58b-ce93-4109-afa2-7f613bf26c80	d6a5a889-9747-454f-9657-eeb5614927fc	Fe	铁	0.3811	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10006
b03f5563-fe55-4040-b373-776c084bc68f	d2a75675-a7b2-40d8-a613-aade64910aca	316	316不锈钢	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10028
b218d660-b5ab-459c-be5c-e069e26543f7	9c84a821-338f-48cf-8864-721ef45eae47	WC	碳化钨	21.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10024
b22c86d8-68a4-4468-921d-103e6df5b50c	082e402c-b398-4bde-a56a-3fbe8310621f	206	206	41.1000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10034
b2852082-61a7-401c-b0e3-6a54bbb18a58	c3846945-a01c-4270-be6d-bb662d90c8e4	721	721	29.2000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10033
b2fbc81d-e63f-46c3-baf6-6d7ce76802b4	f3b84e29-a53b-4a3f-8998-ac3b73956b2a	Ni36	铁镍合金Ni36	22.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10032
b37f1e49-e8db-4415-805c-2d62f2a55d3e	09256355-10f5-4002-a9fc-d48c38d7bcf9	Cu	铜	26.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10002
b3e51909-dabb-472f-82c3-7dae4e643df5	f26b91bf-60a4-47a8-a177-17176752dd9f	Ag	银	89.0900	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
b4d044d9-c460-4be9-a805-a34cc9dd0896	e21aa8e8-7220-46c5-a435-4020903d9b9f	Ni36	铁镍合金Ni36	42.7000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10032
b5757c32-f529-4f07-901c-8d8186e319ba	25ef4515-bddf-43f2-8b34-8df446b565cd	Zn	锌	30.0000	\N	\N	t	2	2026-05-30 06:19:32.06606+00	10003
b599a8bc-06c4-49ad-87c3-7708b85a7170	eab16559-b2ce-44e0-8020-678337e308e7	Sn	锡	4.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10004
b59bdd37-5a63-40f4-bfaa-2108b57b86a9	04fff2b3-dcff-452c-9042-3acbe635a1ab	Ni36	铁镍合金Ni36	27.2000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10032
b6105cf7-64a2-4376-a77c-67432ecd6275	80aaf9bc-f509-48be-ab85-fc6da2ff3c5e	Ag	银	1.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
b6e01c50-5154-4c93-941d-a546d0287251	ada1078a-d216-430b-9e4b-d44af9f6fc83	不锈钢	不锈钢	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10027
b6e9e944-9c48-4cd2-92dc-0f0c11496ef8	8b9976de-2140-4aed-a1ca-901c821c8d8c	Ni42	铁镍合金Ni42	48.7000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10029
b72782bc-3667-4df7-8449-9988b8dc7d05	b853b7f1-62c6-4092-a5e3-3a45730b52f9	Al	铝	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10007
b74efd99-2d84-43a1-b425-4a0733ba9d31	40cab825-c406-4546-8701-d7d0e8767b32	206	206	37.9000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10034
b7b281f5-1482-430c-9b8e-cea0f2faced0	e71bcd07-fe25-4ee1-9763-66eee5f2093f	Ag	银	90.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
b8168658-ca76-45fa-9bfc-dc3a8a65d88d	e3fbaf89-e884-4cf7-abd7-0f48deb357c7	316	316不锈钢	27.2000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10028
b81bb028-595c-4400-ba0f-063d500fab47	9dc08f52-216a-418b-aa2d-4b2eb639e25d	Cu	铜	79.7000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
b83fc98d-986a-4101-9c42-ed426e86869e	e30e20d3-3d4c-49da-9701-b78430b1826c	C	碳	1.4397	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10012
b8a1fcbc-481c-4486-aae4-d8b80305064b	207b1953-b9d2-42d7-aca2-72ebbb10f89d	Zn	锌	40.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10003
b8ac4271-e324-40d2-ba27-330bb2024e06	72b8d22f-355b-4435-9afc-4bb3efa53a2d	Ni36	铁镍合金Ni36	45.2000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10032
b910ff75-bd89-4f0a-a4ac-4fd9a62ed81f	e06baf34-9b51-4fc7-a31e-dd9311c990bc	430	430不锈钢	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10044
b9755429-e30f-478f-8afb-f41b1f8a38d9	47d91e77-b53c-4465-b537-7dd553ff558a	Cu	铜	14.9000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10002
b977a609-c1e1-4b83-ab24-9c59e98a41cb	504788da-b59b-4df1-a0b0-5a5281344c0b	304	304不锈钢	36.4000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10042
b9c4c51c-d137-429c-8062-cd1d90258b4c	e147f3a3-b61f-4190-96d5-e0394d3250d3	223	223	33.3000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10030
ba2e33a1-2ee9-487c-b7e1-35323352cf3b	ec7dd7e6-5535-4aa4-803d-57bc1cf88e62	Cu	铜	98.6312	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
ba62fc35-e5ac-4094-ae0b-c3d8aec1d389	2d7db319-c7cc-404c-badc-56f1feed6ace	Fe	铁	24.8505	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10006
ba8f17cf-ccd3-4416-a5c9-2e7cdab91593	602ae8bd-4780-4372-a192-811fa7292a28	C	碳	3.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10012
baf79d63-7878-4d86-a367-184d8eb04fcc	fb1dd366-7e70-456e-af68-9608bd3f58c7	721	721	51.3000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10033
bb5d2ec8-d1dc-4b90-be33-c2765fcf0e91	8858ed1b-d923-4ca6-945c-5cb30c3b13a4	Zn	锌	38.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10003
bd0c2046-54b9-4291-958d-48e029c392bb	40e773a7-5395-41e9-ad6c-ac52c062362f	W	钨	30.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10013
bd2209de-b1d7-41b2-ad68-312cbcc7266d	9bc05d51-8d4b-4f2d-b984-ae1413a46366	Ni	镍	87.0333	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
bd63fc6e-b078-454d-88ed-82b2ceffb17a	c6a4eef7-a258-41be-8e89-03001e70c4d5	Ni	镍	17.7873	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
bdbbe36e-0e9e-4195-a6a1-fae4581c48a4	504788da-b59b-4df1-a0b0-5a5281344c0b	Cu	铜	63.6000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
be5e288e-e818-42c3-9368-44733f04a224	3058edf5-7083-4548-bce9-4bd922de3148	WC	碳化钨	22.5714	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10024
bed4cfeb-b499-4f0d-8865-e46d8141d639	ca513ca6-09e0-4542-9fe8-4a02d8ccfbf9	Ni36	铁镍合金Ni36	41.9000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10032
bf250fd3-4b79-40fb-9ca8-e5f8a1129256	fee7a8bc-66ea-475a-bfee-0f88fdb73875	Fe	铁	21.8000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10006
c0691b6d-0f7a-4be0-9c60-98f9e631ee36	e3fbaf89-e884-4cf7-abd7-0f48deb357c7	Cu	铜	72.8000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
c082ed10-81a7-4e3d-8fe0-d1c12e60492f	d6a5a889-9747-454f-9657-eeb5614927fc	Cu	铜	63.6481	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10002
c084e936-c007-40c9-93b7-81ad02e891b5	37fbd185-0e80-4362-b753-d9f03ab0ed3e	Ni	镍	0.9454	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10005
c0bdd9f5-049e-4058-a19d-c7b1529c3961	5bae09f1-6d8b-4a20-9c08-7e7cb0c98022	Ni	镍	18.1000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
c0ca2769-54ed-4b81-8274-1c57964f9d7d	21f06530-86b1-4a97-a093-f9b41344f80a	Sn	锡	9.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10004
c0cec40e-816b-4534-aac4-41c931de42a1	3453f390-1ca3-4708-a70f-cad33f66ba39	P	磷	7.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10057
c0df974b-0c22-4872-bb71-a8a331f6cc57	0d8b783b-ea24-4519-b691-f2b99f684805	Ag	银	57.3000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
c1014ca5-bfba-487e-9039-de38a0b248b7	a6012647-8186-4690-b929-5ed0f37a7a0e	C	碳	3.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10012
c1ad9f0e-9ade-4e15-a455-b2bd28732d76	e92dba85-510a-442c-a219-c04ae6cc1afd	Ag	银	29.3000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
c1ec4f6e-fcfc-4773-825d-635b8bd54802	d32f58c4-eecf-4627-9755-b7c3d25218e2	Cd	镉	0.8719	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10017
c20daf8a-a997-458b-b44f-00d13672f33d	8e47252c-6e97-4c52-ac16-d61f1b07cfbd	Ag		100.0000	\N	\N	t	1	2026-07-10 02:27:18.509548+00	10001
c22131d6-e868-46b4-bd47-ef50130ce10b	5b05ad35-bc1c-4868-b74a-49a3678a5641	Cu	铜	31.6897	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10002
c279a5d1-86e4-4211-82c8-71f775936e8b	6fbdb13d-2be6-4b05-aeb3-36586ea5cb0e	304	304不锈钢	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10042
c2955ec1-49f9-44c2-ba57-2aa31f795571	1a3ab7f1-545b-4660-9fd2-cfe0add4e3b2	Ni	镍	6.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10005
c299f489-fd5f-427c-81d6-845e30c770b8	2d8b5ea4-fc86-4edb-a853-9f91805facba	SnO2	二氧化锡	9.1674	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10064
c37e540b-41ad-473e-857d-1a72aa481f46	b6cb1c61-8d87-4670-b5b5-d775a036fecc	Ni36	铁镍合金Ni36	36.4000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10032
c3b70ba9-85a2-4041-8b7d-fc6766bc7709	d997cd5d-1b3a-4f0e-b183-8b0a5c636622	Ag	银	33.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
c3c31ec4-8492-4e53-b80f-b0e7689189c8	98251345-ad9b-481e-bd3c-e9aab0c9458a	Cu	铜	99.6474	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
c3db72b8-f893-49f0-8ef4-f877a730c6cf	388dac1e-b577-4143-8f64-cb5ce2464366	C	碳	1.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10012
c3ebe5ee-0552-4a5d-b477-8ef74ec0ec70	9a6de57f-f32e-4234-a572-0a732b0283d5	Ag	银	90.3700	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
c3f440b8-8767-4e84-b9bd-c3009d28c3dc	473a3e03-6628-4563-ac1f-32aa02e882a9	Ag	银	76.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
c3fc1018-ce10-41b5-8a67-ed7ebebf593c	02097b82-34bb-41c1-a51a-09fbaffba85d	Cu	铜	93.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
c438b8d0-469c-4107-aa50-9c601814f6a8	b26d00c9-a14c-42ef-a71a-b8dd652ce5ad	Cu	铜	28.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
c49934a2-e11e-483b-bdbd-58e26b4ee628	e232eadd-495a-4ce2-8ddd-b3885cb96db9	Ag	银	18.4985	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
c4b6d8d7-7f81-486f-8260-2437b810e937	8269b56a-7e10-4d92-89b7-c233efe2a11e	Cu	铜	93.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
c4e45c33-0205-4cd8-b0db-35c91322ceec	60a70cdf-b79f-47b3-90ea-f6cf365e6c29	C	碳	1.7000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10012
c5933a3e-697c-47b6-af43-e9793aa41d65	2179fbb1-502e-422b-a339-5e13a801b053	301	301不锈钢	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10039
c6de607b-3aa9-4094-8fa3-5a47d6fe1067	cd0ab947-8989-4b17-90aa-e49f654c89f9	Ni36	铁镍合金Ni36	45.9000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10032
c80c9171-5f8f-46b9-abab-e3c0f67d23c0	4d77e48d-6c02-4a23-a922-210224b38429	Ni	镍	6.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
c8442cd1-e7ac-4f70-a67d-a82606e4348b	fc442726-a06f-4a9d-bc96-aafcf1f22df2	Cu	铜	88.0500	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10002
c8ce6e9c-7002-4a4a-82f2-6dc77a2f9506	84c8c932-2c45-4083-97ca-3965f52a1c7f	Ni	镍	29.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
c9013e7d-c1ec-4756-8033-705fbc885bd9	4d77e48d-6c02-4a23-a922-210224b38429	206	206	49.3000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10034
c98c259e-f172-47fc-abd1-794517764ff2	6140a2e2-90ff-443f-8a36-60f2c090e460	Cu	铜	90.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
c9d5c12d-8eba-48ab-adb9-645428f0681f	3ec26d54-dce6-4d78-a631-806ceb98b155	Si	硅	1.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10011
ca092717-7071-4d6e-9292-e484c4b2187d	1aaca26d-bf24-4ff7-ac32-9a0142064841	Ni	镍	3.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
ca461091-ceb3-455c-85e1-7cd47a7e3304	26f774ec-c405-4e40-9762-34ab4c558a75	Zn	锌	20.7513	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10003
caed3806-a2d4-48a6-99a0-659a1d7debbc	d81ef824-54c4-4a30-b73c-bf252e22f3cf	Ni42	铁镍合金Ni42	50.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10029
cb71c187-7eb5-44ca-9976-e14847d51ab0	b041f1fe-dc7c-4ad8-bfec-092688140761	Cu	铜	70.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
ccd1d133-64ea-4034-9263-af8db1cb3416	fc442726-a06f-4a9d-bc96-aafcf1f22df2	Zn	锌	9.7833	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10003
cd356296-129e-4dee-926e-e6e689c3b2fa	8ddd2ea7-3478-4d5e-9504-d219e6c537a9	Sn	锡	12.2554	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10004
cdd94caa-d791-441c-96bf-e605ce4e0e0a	70028291-48f2-4355-a1dd-f906266d67eb	Cu	铜	61.3850	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
ce1bd2a5-d2d9-4568-9b05-56eb7744327f	1c6e3b46-7b55-408a-a1cd-fbfc21505f99	Ni	镍	3.9586	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
ce43b2b0-c3c4-4657-bef4-8eaff45ec805	e5cdd7dc-9b10-41e4-a7be-beb58e0fa6aa	Zn	锌	9.3490	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10003
ce66829f-0e49-48e9-bc1f-a8efc63b6ce9	7deacf50-6871-49e1-b5ae-d8feb2ef6544	WC	碳化钨	76.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10024
ce930d18-1b1e-4294-b201-0759d195e763	9c818733-49b6-4ef1-bf9f-cc655a9a1e05	Ag	银	97.1816	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
cf16dd0e-2060-4130-a9b7-4865e473c020	c3a7f6e2-1c05-4397-8575-8bf7fbfdc61c	Ni	镍	6.0526	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10005
d024c466-7d6e-4be8-aa90-b08bad53647d	d6a5a889-9747-454f-9657-eeb5614927fc	Ni	镍	14.5735	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10005
d02bcf35-4959-48f1-a3e3-be277a745301	50b4a9f5-60d7-48fe-ab6e-9e42bea0bc46	Cu	铜	55.8173	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
d031a59e-b2c3-4951-9c6d-9e6fbaabe790	925db127-74c0-4a93-9635-1b6588f40a56	Al	铝	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10007
d03ec2b9-5724-454a-a86d-1ce56a310283	a69b7794-1595-4b95-99b3-9cf7b91ff723	Ag	银	82.7793	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
d0a5ba13-6181-4754-bada-6b9b14503412	b7b57363-777c-4d44-bf83-269e16481248	223	223	50.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10030
d0ba62d7-0d77-4a56-a8c0-04fa55c0e945	9c84a821-338f-48cf-8864-721ef45eae47	C	碳	1.6429	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10012
d0f9fafb-0989-4a26-9194-86f668a35670	04802c85-4159-4afc-8852-f800ffe86ad4	Ag	银	23.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
d18e00bc-e62a-412f-8a12-dce3fb1c3eb7	98251345-ad9b-481e-bd3c-e9aab0c9458a	Ag	银	0.3526	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
d25c851f-8d51-4d89-b35e-434e6ef3995d	4e584ed1-0684-4fc8-a95f-78ebb9112539	Ni	镍	19.6000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10005
d2ec02a0-4da5-4f36-80a5-455fcd3117d2	7d75e34f-ff63-444a-8447-fa35f955fc41	Ag	银	26.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
d3bef71e-6c5b-4a31-a629-667f8c34eee6	f042901e-4404-4db7-9089-41c70d81819f	Cd	镉	9.1551	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10017
d3c84204-0cac-433c-b067-934f8fe34078	820f7125-7c66-4d93-b45b-d7df98cff7d6	Zn	锌	32.1300	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10003
d3d6c59f-172a-4ce6-95f2-085fb6b9cc1c	e7731aee-68c3-471c-9198-4f206c9a8f02	WC	碳化钨	26.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10024
d4dcde6b-f34c-45f0-ba81-de8f6b4b4bfc	c6a4eef7-a258-41be-8e89-03001e70c4d5	Ag	银	6.0270	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
d5942981-26db-401c-82aa-d799e6a97e95	0ff32a6e-740b-4914-84c4-d47f91b791ca	Ni36	铁镍合金Ni36	33.9000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10032
d5aaca20-ccc2-43f3-8bef-276578d5f660	e232eadd-495a-4ce2-8ddd-b3885cb96db9	Fe	铁	17.6950	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10006
d6ff331f-d87d-4f3d-b05c-fd5d9b6e4e08	7dfbd222-23ad-4678-a161-bdd0783a90ff	Cu	铜	92.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
d7022fbc-4e17-480f-8b37-2dd7924cc392	3ec26d54-dce6-4d78-a631-806ceb98b155	Cu	铜	94.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
d796d4be-d42d-470c-863a-94bf5b3e605f	b1c47317-53e4-4751-87cd-57f03a28e22a	Cu	铜	50.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
d7b79c3d-8142-44ed-a8e1-3c8dde4c9961	9bc05d51-8d4b-4f2d-b984-ae1413a46366	Ag	银	11.6700	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
d7c16242-9286-46c1-bfc0-9ac9bbfbdcee	37fbd185-0e80-4362-b753-d9f03ab0ed3e	Ag	银	7.2046	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
d81bacec-f0fa-4eaf-8035-5f149dbdd339	c3846945-a01c-4270-be6d-bb662d90c8e4	Ni36	铁镍合金Ni36	32.9000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10032
d857b7b1-dcd2-4133-a8f4-9306401fbaed	2d8b5ea4-fc86-4edb-a853-9f91805facba	Ag	银	90.8326	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
d9c0bb36-95e6-4baa-b823-6f3c0db69f89	4f87a69a-4553-4b07-aaca-30e38cb0cca6	721	721	46.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10033
d9cb1884-3af5-42c9-a363-6d037ab1bf1e	224ba8d5-fc33-4bd8-87d7-b0ec83ab348d	Cu	铜	80.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
da6a5793-e775-4ef5-bdda-65b9e558db89	fb1dd366-7e70-456e-af68-9608bd3f58c7	Ni42	铁镍合金Ni42	48.7000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10029
da6fe8db-3de2-402b-86f6-91a46e941349	3e51fc21-d6ed-4b69-86c8-6ca6f11c79b2	Ni	镍	3.6000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
da80aa7c-8a78-4492-b551-b91769a5ee19	1aaca26d-bf24-4ff7-ac32-9a0142064841	Cu	铜	96.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
dabdce13-f4ed-427d-b57a-4f3690599527	c9ef3375-1615-4c86-9a34-90abcb0c5ff9	Ag	银	24.6700	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
db636ca9-9cc5-4516-b620-92f8161f41e4	5e2eb7b0-cf41-4ecb-99fc-6413b3ef73f4	Cu	铜	65.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
db691a0b-de29-4419-9848-d786727d097a	cf645337-94a6-409d-a6b9-6a64c7ff33ea	W	钨	40.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10013
db95ab44-9e45-48b2-8473-bd76f0a827cb	fc442726-a06f-4a9d-bc96-aafcf1f22df2	Ni	镍	0.2167	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
dc488067-8288-4479-97df-6b9edfd34aa0	ec83b09c-ec49-4c99-b2eb-d7c09b4cee84	206	206	29.7000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10034
dc981286-4bf3-4072-a693-6abe25cc0e7a	602ae8bd-4780-4372-a192-811fa7292a28	Ag	银	70.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
dca7c919-93b2-4736-bbdb-a2a84ec0728c	0ceca828-0eba-4199-9a82-db3fba1e466c	Zn	锌	35.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10003
dcfc09e8-fdb9-4580-8e46-07d15c7224db	a69b7794-1595-4b95-99b3-9cf7b91ff723	WC	碳化钨	16.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10024
dddf99fb-9b48-43f0-8d6a-908e1d265e98	04fff2b3-dcff-452c-9042-3acbe635a1ab	Cu	铜	39.8000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
de261155-1fbb-4347-9c44-87bafe54beca	0ea290f2-54e3-4e86-82a7-a3df1404fa33	SnO2	二氧化锡	15.1900	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10064
de3b045a-b15e-4d90-bf3d-4e11c5a3a65c	1c6e3b46-7b55-408a-a1cd-fbfc21505f99	Cu	铜	67.0114	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
de54ffce-86d3-4faa-9915-7fef10af5330	49d6c4ae-6208-4ed5-8f8b-88cce50fc34a	Ag	银	45.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
de8bfd59-6d3a-4fc3-9875-cf44dad26e34	86f5fcc2-08f4-417f-9260-3527e3205bad	Ag	银	87.4975	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
deb39cd2-bb2d-431d-84a4-d2fd9997d3c8	b80b544c-971a-4fab-b029-c084ea9a38ca	Ag	银	23.5068	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
dedc79d1-6eea-416b-a309-ba48ee70abb7	41a041e4-a333-4631-8010-465cf189b797	Ce	铈	0.0676	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10018
e0069589-277e-4d2c-99b0-96dc5cea7962	eeb9955e-8594-42c5-b3f8-b1111cbb4611	Ni	镍	38.5000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
e016ca19-a031-48b6-98f2-46c9e2521af1	8269b56a-7e10-4d92-89b7-c233efe2a11e	Sn	锡	6.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10004
e056622f-e8c0-4227-97e4-203ce196b3cf	5af07f24-5515-4375-8363-27c11c61c663	Ag	银	46.8055	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
e0660c7f-18b0-4ec8-958e-68d495c78d57	ab751fb9-ea98-4480-bf5d-52b7219020fb	Ni	镍	0.3598	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
e0a0302f-0a12-4470-bbff-54c72b99d26b	ec7dd7e6-5535-4aa4-803d-57bc1cf88e62	Ni	镍	0.2000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
e0b52fbd-bc1c-4527-967b-7bef8cf422be	eb56bf6e-2b22-44cc-a697-cf7fca68854a	Ag	银	75.0000	\N	\N	t	1	2026-05-30 06:18:22.115149+00	10001
e15e639c-9e5c-477f-9db9-4d462175a2be	b4651b23-541e-4c3b-bb01-9d2da76fe51f	Ag	银	15.1900	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
e1d42d28-45ca-49d6-bf48-f9cef1777393	7d75e34f-ff63-444a-8447-fa35f955fc41	Cu	铜	73.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
e230eb89-248c-4463-bffe-0df703d33bf6	4f1f3c23-f4c9-46d2-97cc-9915d8dc8339	Cu	铜	15.0000	\N	\N	t	2	2026-07-09 09:09:47.243173+00	10002
e3d05597-626c-4faa-a12c-ef61326acf22	f772bdfe-d1ea-4da9-afaa-1cfe4d3b7c45	Ag	银	24.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
e4c76988-f3a5-4f0f-b7ae-ca761dc23d53	820f7125-7c66-4d93-b45b-d7df98cff7d6	Cu	铜	59.6700	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10002
e4eea4c2-916f-4e6c-921a-6f48589b0624	ac6b7eb4-8bfc-46df-9093-99d94649d48c	Al	铝	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10007
e550ec55-b0eb-4308-bd52-b7f4a52e4c1c	89b4ddae-0d45-4eb2-8941-7cfb2c2db28b	WC	碳化钨	10.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10024
e57ae81f-b5dd-49fb-b141-9ccbab475340	9a6de57f-f32e-4234-a572-0a732b0283d5	SnO2	二氧化锡	6.5000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10064
e68e2db7-a982-40d1-a29c-4f5e52582494	a9e72304-6bf3-4e26-a2de-615cd59f9fea	Ag	银	97.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
e6a33422-f583-4ce8-878a-2b281752eecf	b4651b23-541e-4c3b-bb01-9d2da76fe51f	Cd	镉	1.6880	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10017
e6d1c533-aca3-4410-8e8c-d698b4a8d2bc	e30e20d3-3d4c-49da-9701-b78430b1826c	Ag	银	86.5603	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
e736a2d7-abbe-40a3-894c-dcdee2fe2ecd	e7731aee-68c3-471c-9198-4f206c9a8f02	C	碳	1.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10012
e75cebbe-5d40-43c7-a12a-e690639339ba	08938737-5928-4dc2-a54a-bc45d1f3482e	Cu	铜	22.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
e800fbc0-b7f8-47fd-b34e-7391280c7801	3e51fc21-d6ed-4b69-86c8-6ca6f11c79b2	Cu	铜	81.9484	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
e83b4fbb-ec86-4bd4-bf57-48360eb97b85	f772bdfe-d1ea-4da9-afaa-1cfe4d3b7c45	301	301不锈钢	76.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10039
e854b418-e3be-4f79-b1a4-cb193b451cd6	abab7bc3-ac6a-4209-9948-d44b72c1ccf1	Cd	镉	10.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10017
e8a1dba3-724d-4eeb-90de-a991036aeb03	d32f58c4-eecf-4627-9755-b7c3d25218e2	Fe	铁	15.7084	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10006
e8f4486e-802e-4d2a-87d5-8fa742038600	0ff32a6e-740b-4914-84c4-d47f91b791ca	Cu	铜	32.3000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
e94bfa33-8caa-4f6d-a3b0-3444f0a07f33	388dac1e-b577-4143-8f64-cb5ce2464366	Ni	镍	28.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10005
e94f1105-f50f-44d7-aa5d-a53f26cd408e	09256355-10f5-4002-a9fc-d48c38d7bcf9	Ag	银	60.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
e9928a77-60e2-4b4f-bef1-2b014c05f0bd	c3659b23-f0fc-4647-bd19-2f9b9af2482d	721	721	77.2000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10033
e99d87de-3bbf-42dc-995d-75172adbad90	64cfd1f5-a898-437f-b45c-4aae0cd5eca5	Cu	铜	34.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
e9b46f2f-6065-47b9-832b-bc94d454ef31	eeb9955e-8594-42c5-b3f8-b1111cbb4611	223	223	31.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10030
e9db4723-5d42-47db-a456-26a5d59cfc69	5306d5b5-e27e-4957-89e4-91e5b646ceb7	Ag	银	33.3000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
ea2fc6a5-7cad-4ea2-9dd4-e23a18c862c4	4d2517bd-a294-4538-b064-3a19a0ffef42	Cu	铜	8.1000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
ea610833-09e5-4a39-9a56-f6f803f4492c	473a3e03-6628-4563-ac1f-32aa02e882a9	C	碳	1.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10012
ea82d1b2-d292-491c-b4f2-675a336c3bee	49263f58-52c4-4f7b-ac18-045bcb8086a8	Zn	锌	30.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10003
eaac9036-fbf0-48bd-9793-bafc64ae025b	e263e33b-bb13-4303-9de7-b14b64bc9545	Zn	锌	32.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10003
eb5bc2b8-67fc-4e48-a012-77ff17efcae7	5806deb5-639f-4d0f-867b-12e281b25761	Ni	镍	15.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
ebbbd9e2-7731-48dd-9c99-f4f571f306ce	1d86a091-a53c-4289-b0b4-c15e4b0f16bb	Ni	镍	4.0000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10005
ec5fa991-6f2a-484a-bee4-4dfddf6c6428	71e29340-a5b0-4306-ab75-45a5afaa864b	Cr	铬	20.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10014
ec730004-3ac3-453e-9958-13df68ac6697	8fcab6c8-8b22-4aa9-9a72-9b37c205766f	Ag	银	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
ee28bdf0-8c15-4709-85ae-a46272fd3531	8b9976de-2140-4aed-a1ca-901c821c8d8c	721	721	51.3000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10033
eea3b74c-2435-463b-abf9-c321a9aa7e6f	bb199939-d7ea-4b58-aacc-d02b7accbcc2	Fe	铁	8.7100	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10006
eee3208f-4956-4821-980c-5baae6754d00	c4e5d948-6b80-49d9-902f-c5dd62f8be40	Ni42	铁镍合金Ni42	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10029
ef99c5e0-7379-4f08-a088-47e4046a91c7	e4d52f6d-d0f0-47f1-8df3-0b7a9e11c9b5	Al	铝	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10007
effc8f53-4a27-4cc6-9c30-c9e918bc3afe	e173de29-769e-4562-b29a-36c572a192df	Ag	银	20.3274	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
f0a1d7ae-2d7d-4a16-a635-b14148443ca3	23566e36-e350-48e8-a693-cd51cabaeef8	Cu	铜	66.4286	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
f0b779a6-522c-4fad-a28d-9d107115c9ca	c9ef3375-1615-4c86-9a34-90abcb0c5ff9	Cu	铜	75.3300	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
f1ae9133-1e1e-4da4-afab-b3beb3cb421e	a06721f7-c777-4b29-8a0b-be7e38c628ab	W	钨	56.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10013
f1e58c85-5b07-4871-8b7f-f796bf29351f	6fcd84cc-b9ab-430b-9144-50d64e7e3138	Ni	镍	50.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
f21ac2d8-e2f8-4738-b493-8d8c8d336efa	60a70cdf-b79f-47b3-90ea-f6cf365e6c29	Ag	银	86.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
f281f1d9-d5d1-4795-875c-60a2dd8166cf	2e7f85ec-a646-461d-8439-12a8ffaf88f1	Ni	镍	2.8936	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10005
f291eb04-fe96-4078-984c-d521a9cfb74d	80aaf9bc-f509-48be-ab85-fc6da2ff3c5e	Cu	铜	98.4977	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
f2a78ff8-87ce-40de-a304-662731d440de	04866efb-8112-4d5a-96b4-88a911a6bdf4	Cd	镉	10.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10017
f3d94bb0-a152-41bb-a9d7-6d3119bfa8b7	5bebf631-6bda-4046-b40b-541de375da23	721	721	47.1000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10033
f3e38872-46e4-46a0-a9cc-c448f2305563	9cf9e3e0-d2d2-448e-a98a-364d71285d5d	206	206	41.7000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10034
f54a0a22-59aa-4e2d-a0f2-897060006f32	97a4fe12-d697-4165-8ea9-4e71a3729f42	H70	黄铜H70	67.7000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10069
f58f3e0d-382b-4980-b845-785add97b255	29994e54-4073-4237-89f4-8f980e7278f5	10002	Cu	100.0000	\N	\N	t	1	2026-07-16 11:37:17.078547+00	\N
f5a8b181-0282-4e41-b661-a96d159dc813	3d6d585a-30ab-49a5-a72f-faabf1169779	Cu	铜	72.2727	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10002
f6040fef-d2c1-4ef0-ae8c-d1501e60448b	75b601b7-6262-4f10-8de6-a26a0b883d24	Cu	铜	93.5000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
f626d8a7-6729-4e37-b3aa-96163d812649	523a3093-96e4-4961-a8d9-410da5513c18	Cd	镉	15.1000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10017
f6b0bb4e-e6af-4f70-b9a4-635b4b93d834	2da9837d-b8af-4c3c-8b10-0b1637bf05b4	223	223	14.1000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10030
f70d0c38-0497-4ac3-b173-a9a18a975e6f	a2e564a8-52ae-48b6-8a8f-d6cceb7e9797	Ni	镍	5.0000	\N	\N	t	4	2026-07-10 00:06:47.045017+00	10005
f809cff5-fdd6-4451-8cb7-17124cd74933	fcfe35f1-1ef2-4234-9bf0-6b5836c9217b	Cu	铜	62.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
f81c1d4a-22a7-4952-ad6b-d0ee01c4e6d6	4a35a3cd-cbb2-4b5e-a91a-1e3a2d379dc1	Ag	银	32.1800	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10001
f85133f2-25c7-4818-b3d7-db8f2d00f1f6	23566e36-e350-48e8-a693-cd51cabaeef8	Ag	银	23.5000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10001
f9c7cee8-e663-4f57-9f57-f987a383742d	cd0ab947-8989-4b17-90aa-e49f654c89f9	223	223	22.6000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10030
fa1947d9-a393-4111-ad16-dcd0da4ce243	f3e9d8ef-b988-40a7-a9e7-583be73cbb51	Zn	锌	20.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10003
fa63c0ae-1411-4591-bc5b-82ebe1e2fa5d	e147f3a3-b61f-4190-96d5-e0394d3250d3	Cu	铜	33.4000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10002
fb4f67a7-26f2-42c3-8449-baae4eefd822	0ceca828-0eba-4199-9a82-db3fba1e466c	Cu	铜	65.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10002
fbc4a06e-6456-404d-8261-900c2fe05431	2ed68b6c-895a-480b-85d8-53bef5fef746	Ag	银	88.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10001
fc6f61ed-f2bc-46b1-ad77-89bc70930caf	71e29340-a5b0-4306-ab75-45a5afaa864b	Fe	铁	50.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10006
fcc380e3-097c-4100-9bed-cd3747a25d87	b80b544c-971a-4fab-b029-c084ea9a38ca	Sn	锡	3.2055	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10004
fd1dea84-2ff7-4d30-8a79-1d2fa365638c	e30e20d3-3d4c-49da-9701-b78430b1826c	WC	碳化钨	12.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10024
fda2c15d-3ca4-4757-aa6d-3e274c77dfd9	c90cd236-96a0-46ab-a497-c48feeff5eb6	Al	铝	100.0000	\N	\N	t	1	2026-07-10 00:06:47.045017+00	10007
fe8059bc-81c3-4004-b6a3-5ea6283b9e5a	fbef0766-aac7-4117-b0ed-2196804bd8c0	223	223	25.3000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10030
fee07ccf-a1b3-4bda-92f2-0865d9f2e33d	215ecc09-baf7-4e6f-901f-3519b5ccc15e	223	223	30.2000	\N	\N	t	3	2026-07-10 00:06:47.045017+00	10030
ffc39698-86ff-45c6-a438-c1fe42017d85	09256355-10f5-4002-a9fc-d48c38d7bcf9	Zn	锌	14.0000	\N	\N	t	2	2026-07-10 00:06:47.045017+00	10003
\.


--
-- Data for Name: material_version_mgmt; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.material_version_mgmt (id, material_no, customer_no, material_name, specification, dimension, seq_no, pricing_version_no, pricing_version_name, element_price_version, material_price_version, exchange_rate_version, is_effective, effective_date, expire_date, created_at, updated_at, created_by, updated_by, is_current) FROM stdin;
\.


--
-- Data for Name: model_config; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.model_config (id, subject_type, subject_key, version, is_current, label, glb_url, thumbnail_url, mesh_count, vertices, size_kb, metadata, uploaded_by, uploaded_at) FROM stdin;
\.


--
-- Data for Name: model_config_file; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.model_config_file (id, model_config_id, file_role, file_url, file_size_bytes, md5_hash, uploaded_at) FROM stdin;
\.


--
-- Data for Name: notification; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.notification (id, recipient_id, type, title, content, link, related_type, related_id, is_read, read_at, created_at) FROM stdin;
\.


--
-- Data for Name: operation_log; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.operation_log (id, operator_id, operation_type, target_type, target_id, summary, created_at) FROM stdin;
\.


--
-- Data for Name: packaging_consumable; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.packaging_consumable (id, material_no, material_name, specification, dimension, seq_no, consumable_no, consumable_name, usage_qty, usage_unit, packaging_level, packaging_version, created_at, updated_at, created_by, updated_by, is_current) FROM stdin;
\.


--
-- Data for Name: part_no_sequence; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.part_no_sequence (prefix, next_val) FROM stdin;
CFG-AgCdO-	3
CFG-AgCu-	36
CFG-AgNi-	179
CFG-AgPd-	2
CFG-AgSnO₂-	3
CFG-AgW-	2
CFG-AuAg-	2
CFG-COMBO-	30
CFG-CuCr-	1
\.


--
-- Data for Name: password_reset_token; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.password_reset_token (id, user_id, token_hash, expires_at, used_at, created_at) FROM stdin;
\.


--
-- Data for Name: plating_fee; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.plating_fee (id, customer_id, hf_part_no, version, is_current, plating_plan_code, plan_version, plating_process_fee, plating_material_fee, currency, price_unit, defect_rate, status, imported_by, import_record_id, created_at, updated_at, created_by, updated_by) FROM stdin;
\.


--
-- Data for Name: plating_plan; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.plating_plan (id, plan_code, version, seq_no, plating_element, plating_area, coating_thickness, plating_requirement, created_at, updated_at, created_by, updated_by) FROM stdin;
\.


--
-- Data for Name: plating_scheme; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.plating_scheme (id, scheme_no, scheme_version, seq_no, plating_element, plating_method, surface_area, plating_area, plating_thickness, plating_requirement, density, element_usage, element_usage_unit, effective_date, expire_date, created_at, updated_at, created_by, updated_by, source_url, source_name, fetch_rule, hf_part_no, is_current, system_type) FROM stdin;
\.


--
-- Data for Name: pricing_strategy; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.pricing_strategy (id, customer_id, name, type, base_discount, min_order_amount, effective_date, expiration_date, priority, status, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: pricing_rule; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.pricing_rule (id, strategy_id, rule_type, threshold_amount, discount_rate, sort_order, created_at) FROM stdin;
\.


--
-- Data for Name: process; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.process (id, code, name, description, category, is_required, sort_order, status, created_at) FROM stdin;
03ad2f6e-8f0a-4471-9374-c4975e532466	MRO-AS-0003	螺栓连接	螺栓紧固连接	ASSEMBLY	f	3	DISABLED	2026-04-14 03:00:25.097592+00
05064c36-e03e-4d3a-a152-43a26771a3c0	MRO-IN-0001	尺寸检测	尺寸精度检测	INSPECTION	t	1	DISABLED	2026-04-14 03:00:25.097592+00
081376a8-e0ec-44b0-ac2f-bfaa31f833fc	MRO-HT-0002	回火	金属回火处理	HEAT_TREATMENT	f	2	DISABLED	2026-04-14 03:00:25.097592+00
15b8a03f-43a9-4fce-8f91-879b9fa70521	K1	分条	\N	SURFACE_TREATMENT	f	100	ACTIVE	2026-05-20 01:00:20.467427+00
1a7caf9c-4b73-4ed2-a0f7-915f788fb242	MRO-HT-0003	正火	金属正火处理	HEAT_TREATMENT	f	3	DISABLED	2026-04-14 03:00:25.097592+00
1c1e5333-a3ac-4529-87b1-968ede183a92	K10	长管质量处理	\N	SURFACE_TREATMENT	f	100	ACTIVE	2026-05-20 01:02:52.486377+00
26851da6-525d-4d29-bd0b-215e5e6efd5f	MRO-MC-0002	车削	车床车削加工	MACHINING	f	2	DISABLED	2026-04-14 03:00:25.097592+00
2dab7538-b668-4197-8830-934d35da08d6	MRO-LP-0004	阳极氧化	铝合金阳极氧化	SURFACE_TREATMENT	f	4	DISABLED	2026-04-14 03:00:25.097592+00
3022e067-aad0-492f-acc6-3de610a80125	MRO-IN-0002	外观检测	外观质量检测	INSPECTION	f	2	DISABLED	2026-04-14 03:00:25.097592+00
3120c88f-b417-4902-bd5e-55f86e152a3b	K7	酸洗	\N	SURFACE_TREATMENT	f	100	ACTIVE	2026-05-20 01:02:21.779673+00
3a80eed7-550d-48eb-82d9-85eaf3f67fc0	MRO-LP-0003	抛光	表面抛光处理	SURFACE_TREATMENT	f	3	DISABLED	2026-04-14 03:00:25.097592+00
3fef7ca1-ab25-425f-a3a0-fdff26f02458	K9	冷拔	\N	SURFACE_TREATMENT	f	100	ACTIVE	2026-05-20 01:02:38.669088+00
42dc9336-1666-4683-ad3a-053ff7ad40af	MRO-LP-0002	喷涂	表面喷涂处理	SURFACE_TREATMENT	f	2	DISABLED	2026-04-14 03:00:25.097592+00
43b4e1e3-36e2-42b4-b285-44bb01ea8f19	K6	正火/退火	\N	SURFACE_TREATMENT	f	100	ACTIVE	2026-05-20 01:02:11.269021+00
4d858f25-7cdd-4513-a904-482f214c1c08	K13	外协工序1	\N	SURFACE_TREATMENT	f	100	ACTIVE	2026-05-20 01:03:21.178688+00
53ec80eb-bc31-4271-9c4e-1151ea4cc3d8	MRO-HT-0004	退火	金属退火处理	HEAT_TREATMENT	f	4	DISABLED	2026-04-14 03:00:25.097592+00
6db6d730-ee27-41c4-91c7-dbca754a9014	K3	正火/退火	\N	SURFACE_TREATMENT	f	100	ACTIVE	2026-05-20 01:00:42.956347+00
6de2bc65-710c-467f-b166-31ed129914a6	MRO-IN-0004	强度测试	材料强度测试	INSPECTION	f	4	DISABLED	2026-04-14 03:00:25.097592+00
744012b3-f0a7-430a-b286-acb50bef69e4	MRO-PK-0001	包装入库	标准包装入库	PACKAGING	t	1	DISABLED	2026-04-14 03:00:25.097592+00
7712664a-23bd-4cd5-a890-a0bd546d9ab9	MRO-IN-0003	功能测试	功能性能测试	INSPECTION	f	3	DISABLED	2026-04-14 03:00:25.097592+00
814022d7-4433-4ade-a99f-8904844a4b74	MRO-PK-0002	防护包装	特殊防护包装	PACKAGING	f	2	DISABLED	2026-04-14 03:00:25.097592+00
82601fe4-9728-4256-bae6-d40395fe2869	K5	冷拔	\N	SURFACE_TREATMENT	f	100	ACTIVE	2026-05-20 01:01:05.365444+00
83fbc96f-b7fa-4a07-92a4-21bd4f17a65e	K4	酸洗	\N	SURFACE_TREATMENT	f	100	ACTIVE	2026-05-20 01:00:52.675569+00
8981fe85-146e-4b00-baf3-92b2870d5308	K2	焊接	\N	SURFACE_TREATMENT	f	100	ACTIVE	2026-05-20 01:00:31.728757+00
8aa242ad-a408-49c5-9ab9-e0a147c78fce	MRO-PK-0003	标识标签	产品标识标签粘贴	PACKAGING	f	3	DISABLED	2026-04-14 03:00:25.097592+00
8befd280-47a4-4bb1-84a3-c3683508fadb	MRO-MC-0003	铣削	铣床铣削加工	MACHINING	f	3	DISABLED	2026-04-14 03:00:25.097592+00
8ef8f447-d3a8-4a5b-9d46-ab5980c1b83b	Z350	Z350	V186 自动注册 — 来自 mat_process 实存但未注册的 orphan code, 请补充名称/分类	MACHINING	f	9999	ACTIVE	2026-05-17 12:28:36.535657+00
93123691-fe59-489b-b66b-33680c0f426e	MRO-AS-0002	部件装配	部件组装	ASSEMBLY	f	2	DISABLED	2026-04-14 03:00:25.097592+00
934bceaa-8f4b-40f8-aff5-c86afb2ab682	MRO-MC-0001	CNC加工	数控加工中心加工	MACHINING	f	1	DISABLED	2026-04-14 03:00:25.097592+00
98285111-f3fa-45b4-b25f-2f02168842b8	MRO-LP-0005	磷化	钢铁磷化处理	SURFACE_TREATMENT	f	5	DISABLED	2026-04-14 03:00:25.097592+00
a41bea28-f030-4b1c-8ed1-99ab1273ad22	MRO-MC-0004	钻孔攻丝	钻孔及攻丝加工	MACHINING	f	4	DISABLED	2026-04-14 03:00:25.097592+00
a64b77ed-5011-40ca-bb2d-c9f98f953d22	K11	切短管	\N	SURFACE_TREATMENT	f	100	ACTIVE	2026-05-20 01:03:03.021483+00
b42ac12f-ac04-44b3-8e80-161ac0899e7d	MRO-LP-0001	电镀	金属表面电镀处理	SURFACE_TREATMENT	f	1	ACTIVE	2026-04-14 03:00:25.097592+00
ba68cc8e-9112-4c40-ad01-5a869135fb6a	K12	短管质量处理	\N	SURFACE_TREATMENT	f	100	ACTIVE	2026-05-20 01:03:13.125406+00
c2c88597-96c9-4a7d-9e2a-df57701cb801	MRO-HT-0001	淬火	金属淬火处理	HEAT_TREATMENT	f	1	DISABLED	2026-04-14 03:00:25.097592+00
cbdd41d0-f33f-4f10-8ae4-d60bdcda0da6	MRO-AS-0001	总装配	产品总装配	ASSEMBLY	f	1	DISABLED	2026-04-14 03:00:25.097592+00
cdd7d65e-285c-4ae5-b6c1-8e4cae39d8b0	MRO-AS-0004	焊接装配	焊接组装	ASSEMBLY	f	4	DISABLED	2026-04-14 03:00:25.097592+00
d7d4b1b6-37a2-4080-a371-884c73dd9a45	MRO-IN-0005	气密性检测	密封气密性检测	INSPECTION	f	5	DISABLED	2026-04-14 03:00:25.097592+00
ed168da7-841c-4f6b-918a-63fe4a18d677	Z029	Z029	V186 自动注册 — 来自 mat_process 实存但未注册的 orphan code, 请补充名称/分类	MACHINING	f	9999	ACTIVE	2026-05-17 12:28:36.535657+00
f52c447b-b001-4632-b104-c520a997598e	K14	外协工序2	\N	SURFACE_TREATMENT	f	100	ACTIVE	2026-05-20 01:03:29.27997+00
fce24059-e1fa-4941-a4b8-af8b36383dda	MRO-MC-0005	磨削	磨床精密磨削	MACHINING	f	5	DISABLED	2026-04-14 03:00:25.097592+00
\.


--
-- Data for Name: process_master; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.process_master (id, process_no, process_name, process_category, is_outsource, standard_currency, standard_unit, default_defect_rate, created_at, updated_at, created_by, updated_by) FROM stdin;
0a231a64-4c02-4331-aa2e-9bb1a21625ec	MRO-IN-0002	外观检测	INSPECTION	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
1e947115-39d3-4916-8e97-437c32ecaae2	MRO-PK-0003	标识标签	PACKAGING	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
265674be-6948-4421-9ab2-15106dc4b335	K6	正火/退火	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
2aafff35-647e-4925-a1a5-f47af4e9b1bb	MRO-LP-0004	阳极氧化	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
30108b81-4de6-4f4c-9442-95756578501d	MRO-AS-0004	焊接装配	ASSEMBLY	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
37d527ff-e679-4a0e-a8f5-8292ee225171	MRO-MC-0003	铣削	MACHINING	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
39857c74-4654-4a32-87cc-518d0b4c3ffe	K14	外协工序2	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
41b89798-2c81-4483-9718-9f4191230aba	K1	分条	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
42e967d0-deb9-441b-bd00-37bd12544c09	K10	长管质量处理	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
494bef21-1ef1-4634-8009-70ac95b52252	Z350	焊接	MACHINING	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
4ab9745b-136f-4f38-a220-2ce1d828f390	MRO-MC-0002	车削	MACHINING	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
4df409a4-a253-4546-8cec-e55ae049523f	MRO-MC-0004	钻孔攻丝	MACHINING	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
5a733d34-b204-4849-aba2-87a1205fc036	K2	焊接2	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
5b711e15-eed4-4aed-8d9c-964925851cfd	MRO-PK-0001	包装入库	PACKAGING	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
5fae69a0-1162-44cb-bb92-b0cc86955c0d	MRO-AS-0001	总装配	ASSEMBLY	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
617a2815-2815-42ae-a130-a6de229753d1	MRO-LP-0001	电镀	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
63cc49df-9998-4a2b-875d-81b0a3930b1c	K4	酸洗	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
6606cc00-8813-442e-9a6c-57068ce95eed	K11	切短管	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
6a01aa64-0874-4429-a5b0-2479d82c5770	MRO-MC-0001	CNC加工	MACHINING	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
75601c42-cbe4-4f75-bd09-6a1a164e8c62	MRO-LP-0005	磷化	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
778e6241-9a05-4ddd-a88f-340dd9291ce1	MRO-IN-0001	尺寸检测	INSPECTION	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
828d81e7-20b5-47de-942d-5a4945b9540c	MRO-IN-0003	功能测试	INSPECTION	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
8972c7cd-f12d-4cc4-af64-6290f425dca8	MRO-LP-0002	喷涂	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
91c0917a-ebe2-48d2-b908-0e84e083d6e3	MRO-LP-0003	抛光	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
9fe9ff25-5d2f-447e-9cef-6e2941603053	MRO-AS-0002	部件装配	ASSEMBLY	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
a2b944a1-94a6-4087-97e7-fe45adc4904c	K7	酸洗	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
a481cbba-3066-4f15-b0da-7654a7d84e12	MRO-AS-0003	螺栓连接	ASSEMBLY	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
a7bf9f86-e155-4b89-b785-644479bb3434	MRO-HT-0001	淬火	HEAT_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
ae0157cf-d0a9-4557-905f-fef7cd131861	TP10	测试工序10	\N	\N	\N	\N	\N	2026-07-14 00:28:00.88415+00	2026-07-14 00:28:00.88415+00	\N	\N
b0875824-a2d6-4d58-add1-cdfbd4692aec	K9	冷拔	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
b44a916f-dfd1-418d-836b-08940882ecf9	MRO-HT-0004	退火	HEAT_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
b4698365-0f66-40cc-9acf-5fa9b3dc9b4d	MRO-IN-0004	强度测试	INSPECTION	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
b48c02da-8546-4fea-8fdb-4aec3d5f2ced	Z029	Z029	MACHINING	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
b5c60e00-c2f8-44c9-bae5-39a540e288fd	MRO-HT-0003	正火	HEAT_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
b6e8bb8d-3f22-4a14-b1b5-97d128d6ee02	K3	正火/退火	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
d571b408-d90b-4b87-81ef-04736ee7f0fd	MRO-HT-0002	回火	HEAT_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
d9121491-a314-4ef6-99be-a781c2eb2ed0	MRO-PK-0002	防护包装	PACKAGING	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
dba26730-dcae-402b-bd5d-f94282846ba4	MRO-MC-0005	磨削	MACHINING	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
dcdc0137-2335-468c-85b3-cc645d34ac2e	TP20	测试工序20	\N	\N	\N	\N	\N	2026-07-14 00:28:00.88415+00	2026-07-14 00:28:00.88415+00	\N	\N
e1c29685-61a3-457c-b55c-5d7c7abe981a	MRO-IN-0005	气密性检测	INSPECTION	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
ef8ce62a-daeb-453f-ad67-08e8074ac89e	K5	冷拔	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
fbe59888-50e9-4f23-bfcb-f8646086031e	K12	短管质量处理	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
fd0772a7-908d-4fa6-ae2e-8cece6f58709	K13	外协工序1	SURFACE_TREATMENT	\N	\N	\N	\N	2026-05-28 09:33:55.483203+00	2026-05-28 09:33:55.483203+00	\N	\N
\.


--
-- Data for Name: product; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.product (id, name, part_no, category, specification, status, tags, external_id, last_synced_at, created_at, updated_at, drawing_no, dimension, material, category_id) FROM stdin;
\.


--
-- Data for Name: product_config_template; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.product_config_template (id, code, name, category, base_part_no, base_model_id, base_model_version, base_model_snapshot_at, description, show_price, metadata, status, version, created_at, updated_at, created_by, updated_by) FROM stdin;
\.


--
-- Data for Name: product_config_option; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.product_config_option (id, template_id, code, label, option_type, data_type, assign_mode, is_required, default_value, min_value, max_value, partno_prefix, partno_suffix, sort_order, description, metadata, source_feature_field_id, source_feature_snapshot_at, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: product_config_option_value; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.product_config_option_value (id, option_id, code, label, description, price_delta, sort_order, partno_include, is_active, feature_type, attributes, tags, geometry_ref, sub_model_part_no, attach_mode, attach_position, replace_base_mesh, source_feature_value_id, source_feature_snapshot_at, local_only, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: product_config_3d_rule; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.product_config_3d_rule (id, option_value_id, action, target_mesh, params, sort_order, created_at) FROM stdin;
\.


--
-- Data for Name: product_config_constraint; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.product_config_constraint (id, template_id, constraint_type, trigger_expr, affected_expr, message, severity, sort_order, is_active, created_at) FROM stdin;
\.


--
-- Data for Name: product_config_instance; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.product_config_instance (id, instance_code, template_id, template_version, name, customer_id, customer_lead_id, user_id, share_token, selected_values, config_fingerprint, computed_total_price, base_price, status, linked_quotation_id, linked_at, linked_by, generated_part_no, generated_quotation_id, generated_line_item_id, expires_at, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: product_config_instance_history; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.product_config_instance_history (id, instance_id, action, actor_user_id, before_snapshot, after_snapshot, note, created_at) FROM stdin;
\.


--
-- Data for Name: product_config_share; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.product_config_share (id, instance_id, share_type, share_token, shared_by, shared_to_user_id, shared_to_email, expires_at, access_count, last_accessed_at, can_modify, status, revoked_at, revoked_by, revoke_reason, created_at) FROM stdin;
\.


--
-- Data for Name: product_config_share_access; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.product_config_share_access (id, share_id, accessed_at, ip, user_agent, action) FROM stdin;
\.


--
-- Data for Name: product_config_template_version; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.product_config_template_version (id, template_id, version, label, status, snapshot, change_summary, created_by, created_at, published_at, archived_at) FROM stdin;
\.


--
-- Data for Name: product_config_value_reference; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.product_config_value_reference (id, option_value_id, ref_type, ref_code, qty, unit, note, metadata, sort_order, is_active, created_at, updated_at, created_by) FROM stdin;
\.


--
-- Data for Name: product_import_lock; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.product_import_lock (id, customer_id, part_no, granularity, locked_by, import_record_id, locked_at, last_heartbeat_at, expires_at, status, released_at, released_by, release_reason, created_at, updated_at, created_by, updated_by) FROM stdin;
\.


--
-- Data for Name: product_process; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.product_process (id, product_id, process_id, created_at, sort_order, is_required) FROM stdin;
\.


--
-- Data for Name: product_template_binding; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.product_template_binding (id, product_id, process_ids, process_ids_hash, template_id, is_default, created_at) FROM stdin;
\.


--
-- Data for Name: production_consumable; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.production_consumable (id, material_no, material_name, specification, dimension, process_no, process_name, resource_group_no, seq_no, consumable_no, consumable_name, usage_qty, life_qty, life_unit, usage_unit, consumable_version, created_at, updated_at, created_by, updated_by, is_current) FROM stdin;
\.


--
-- Data for Name: production_energy; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.production_energy (id, material_no, material_name, specification, dimension, process_no, process_name, equipment_no, batch_size, round_step, working_hours, currency, unit, conversion_rate, calc_version, created_at, updated_at, created_by, updated_by, is_current, production_no, system_type, price_type, unit_price, source) FROM stdin;
\.


--
-- Data for Name: quotation_approval; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.quotation_approval (id, quotation_id, approver_id, action, comment, acted_at, created_at) FROM stdin;
\.


--
-- Data for Name: quotation_comparison_config; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.quotation_comparison_config (id, quotation_id, bucket, columns, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: quotation_component_sql_snapshot; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.quotation_component_sql_snapshot (quotation_id, sql_view_key, sql_template, declared_columns, required_variables, frozen_at) FROM stdin;
\.


--
-- Data for Name: quotation_line_item; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.quotation_line_item (id, quotation_id, product_id, template_id, product_attribute_values, subtotal, system_discount_rate, final_discount_rate, discount_adjustment_reason, is_manually_adjusted, sort_order, created_at, customer_part_no, excel_view_snapshot, product_name_snapshot, product_part_no_snapshot, costing_summary_id, part_version_locked, annual_volume, discount_source, discount_base_amount, discount_rate_applied, line_discount_amount, line_unit_price, line_final_price, line_total_amount, discount_rule_code, parent_line_item_id, composite_type, quote_card_values, quote_excel_values, costing_card_values, costing_excel_values, card_snapshot_at, quote_values_at) FROM stdin;
\.


--
-- Data for Name: quotation_line_component_data; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.quotation_line_component_data (id, line_item_id, component_id, tab_name, row_data, subtotal, sort_order, created_at, snapshot_rows, snapshot_at, deleted_row_keys) FROM stdin;
\.


--
-- Data for Name: quotation_line_composite_process; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.quotation_line_composite_process (id, line_item_id, def_code, seq_no, participating_parts, param_values, created_at) FROM stdin;
\.


--
-- Data for Name: quotation_line_item_snapshot; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.quotation_line_item_snapshot (id, line_item_id, product_part_no, product_category, product_specification, created_at) FROM stdin;
\.


--
-- Data for Name: quotation_line_process; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.quotation_line_process (id, line_item_id, process_id, process_no) FROM stdin;
\.


--
-- Data for Name: quotation_view_structure; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.quotation_view_structure (id, quotation_id, view_kind, structure, created_at) FROM stdin;
\.


--
-- Data for Name: quotation_withdraw_request; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.quotation_withdraw_request (id, quotation_id, requested_by, reason, status, decided_by, decided_at, decision_note, created_at) FROM stdin;
\.


--
-- Data for Name: quote_customer_code; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.quote_customer_code (customer_no, code, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: quote_material_no_seq; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.quote_material_no_seq (customer_code, year_month, last_serial) FROM stdin;
\.


--
-- Data for Name: resource_group; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.resource_group (id, group_no, group_name, group_type, seq_no, process_no, process_name, workshop, equipment_id, description, effective_date, expire_date, created_at, updated_at, created_by, updated_by) FROM stdin;
\.


--
-- Data for Name: sel_param_type; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.sel_param_type (code, name, value_mode, data_source_key, persist_handler_key, sort_order) FROM stdin;
ELEMENT	元素含量	adjust	\N	ELEMENT_OVERRIDE	2
MATERIAL	材质	single	MATERIAL_RECIPE	MATERIAL_RECIPE_BIND	1
PROCESS	工序	multi	V6_PROCESS_MASTER	PROCESS_LIST	3
\.


--
-- Data for Name: sel_part_signature; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.sel_part_signature (id, customer_no, structure_version, config_fingerprint, config_signature_text, quote_part_no, product_type, created_at) FROM stdin;
\.


--
-- Data for Name: sel_template; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.sel_template (id, name, status, version, created_at, updated_at, product_category_id) FROM stdin;
\.


--
-- Data for Name: sel_template_item; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.sel_template_item (id, template_id, param_type_code, enabled, sort_order) FROM stdin;
\.


--
-- Data for Name: sel_template_item_value; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.sel_template_item_value (id, item_id, allowed_value_key) FROM stdin;
\.


--
-- Data for Name: system_config; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.system_config (config_key, config_value, default_value, data_type, category, description, modifiable_by, created_at, updated_at, created_by, updated_by) FROM stdin;
business.gross_margin_block_min	0.05	0.05	NUMBER	business	毛利率阻止提交阈值	SALES_MANAGER	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
business.gross_margin_warning_min	0.15	0.15	NUMBER	business	updated by test	SALES_MANAGER	2026-04-27 02:37:57.003247+00	2026-07-15 09:36:30.48135+00	\N	00000000-0000-0000-0000-000000000099
element_price.fetch_alert_consecutive_failures	3	3	NUMBER	element_price	连续失败告警阈值	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
element_price.fetch_cron	0 0 8 * * ?	0 0 8 * * ?	STRING	element_price	抓取定时任务cron	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
element_price.fetch_retry_count	3	3	NUMBER	element_price	抓取重试次数	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
element_price.fetch_timeout_seconds	30	30	NUMBER	element_price	抓取单源超时	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
import.ddl_lock_timeout_seconds	300	300	NUMBER	import	DDL 全局锁超时	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
import.draft_save_debounce_ms	500	500	NUMBER	import	草稿保存防抖	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
import.preview_response_timeout_seconds	30	30	NUMBER	import	预览响应超时	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
import.product_lock_downgrade_threshold	100	100	NUMBER	import	锁降级阈值	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
import.product_lock_heartbeat_seconds	30	30	NUMBER	import	锁心跳间隔	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
import.product_lock_timeout_seconds	300	300	NUMBER	import	updated by test	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-07-15 09:36:30.604862+00	\N	00000000-0000-0000-0000-000000000099
retention.change_log_years	5	5	NUMBER	retention	变更日志保留年数	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
retention.element_daily_price_years	0	0	NUMBER	retention	元素每日价格保留年数（0=永久）	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
retention.original_excel_months	12	12	NUMBER	retention	原始 Excel 保留月数	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
validation.allowed_currencies	["USD","CNY","EUR","HKD","JPY","RMB"]	["USD","CNY","EUR","HKD","JPY","RMB"]	JSON	validation	允许货币代码	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-28 06:16:43.120389+00	\N	\N
validation.allowed_units	["KG","G","PCS","M","CM","MM"]	["KG","G","PCS","M","CM","MM"]	JSON	validation	允许单位代码	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
validation.assembly_reject_rate_max	0.3	0.3	NUMBER	validation	组装报废率上限	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
validation.completeness_threshold	0.8	0.8	NUMBER	validation	元素价格抓取数据完整度阈值（v2启用）	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
validation.composition_tolerance	0.01	0.01	NUMBER	validation	元素 BOM 含量合计容差	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-07-15 09:36:30.548637+00	\N	00000000-0000-0000-0000-000000000099
validation.defect_rate_max	0.3	0.3	NUMBER	validation	不良率上限	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
validation.import_max_rows	2000	2000	NUMBER	validation	单次导入硬上限	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
validation.loss_rate_max	0.5	0.5	NUMBER	validation	损耗率上限	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
validation.price_rise_max	1.0	1.0	NUMBER	validation	涨价比例上限	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
validation.price_rise_min	-0.5	-0.5	NUMBER	validation	涨价比例下限	SYSTEM_ADMIN	2026-04-27 02:37:57.003247+00	2026-04-27 02:37:57.003247+00	\N	\N
\.


--
-- Data for Name: template_component; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.template_component (id, template_id, component_id, tab_name, sort_order, created_at, preset_rows, formula_assignments, data_driver_path_override, fields_override) FROM stdin;
\.


--
-- Data for Name: template_global_variable_binding; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.template_global_variable_binding (id, template_id, global_variable_code, display_order, created_at) FROM stdin;
\.


--
-- Data for Name: template_sql_view; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.template_sql_view (id, template_id, sql_view_name, sql_template, declared_columns, required_variables, scope, status, description, created_by, created_at, updated_at) FROM stdin;
\.


--
-- Data for Name: tooling_cost; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.tooling_cost (id, material_no, material_name, specification, dimension, process_no, process_name, seq_no, tooling_no, tooling_unit_cost, tool_life, cycle_output, tooling_unit_price, currency, unit, is_effective, conversion_rate, created_at, updated_at, created_by, updated_by, is_current, production_no, system_type, calc_version, source) FROM stdin;
\.


--
-- Data for Name: unit_price; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.unit_price (id, system_type, price_type, version_no, code, name, specification, dimension, finished_material_no, operation_no, cost_type, seq_no, plating_scheme_no, pricing_price, cost_ratio, market_ref_price, currency, unit, conversion_rate, recovery_discount, life_qty, life_unit, supplier_no, supplier_name, customer_no, customer_name, data_type, source_url, source_name, fetch_rule, premium_fee, fetched_price, fetch_time, effective_date, expire_date, base_value, is_fluctuate_with_material, material_increase_ratio, material_fixed_increase, defect_rate, created_at, updated_at, created_by, updated_by, discount_order, item_seq, is_current, production_no, source) FROM stdin;
\.


--
-- Data for Name: variable_label; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.variable_label (id, variable_path, display_name, category, data_type, unit, description, example_value, source_type, status, created_at, updated_at, created_by, updated_by) FROM stdin;
089f1cc8-b8bb-47df-84ab-de9454a9c6ea	v_c_summary_agg.packaging_fee	包装材料费源	成本汇总	DECIMAL	¥	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
1b183784-9c1d-416d-afec-6a030c0d8e3d	v_costing_summary_full.plating_process_fee	电镀加工费	成本汇总	DECIMAL	¥	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
20c0111f-57b4-4bd8-aa20-c7557be3ac0d	v_costing_summary_full.finance_fee_ratio	财务费比例	费用比率	PERCENT	%	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
472ec8ae-d23e-41a4-8253-7d9f45bf1c6c	v_costing_summary_full.plating_material_fee	电镀材料费	成本汇总	DECIMAL	¥	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
4872a323-7e89-4d2c-ac25-6751de822176	v_c_summary_agg.outsource_fee	外加工成本源	成本汇总	DECIMAL	¥	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
68cc2a8b-b731-420e-a968-d848bdb6986f	v_c_summary_agg.customs_fee	清关费	成本汇总	DECIMAL	¥	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
71c293ff-07c5-48c2-acde-496195cbb428	v_costing_summary_full.incoming_other_fee	来料其他比例费用	成本汇总	DECIMAL	¥	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
7c03dc48-b876-4b29-82c4-147fe8e22f4c	v_c_summary_agg.incoming_fixed_fee	来料其他固定费用	成本汇总	DECIMAL	¥	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
8242ce8d-adc4-4387-9475-b8dcbd4275eb	v_costing_summary_full.pure_material_cost	纯材料成本	成本汇总	DECIMAL	¥	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
8598a5c8-33bf-43fc-b821-69589bf8f2b0	v_costing_summary_full.recycle_cost	回收成本	成本汇总	DECIMAL	¥	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
a42abb89-b988-4e25-b305-cd3e8484a3e5	v_costing_summary_full.profit_ratio	利润比例	费用比率	PERCENT	%	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
b9a56fdb-6260-4a19-b947-e47cd1022951	v_costing_summary_full.unit_weight_g	单重(g/pcs)	物料属性	DECIMAL	g	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
bc4b0467-3508-4834-aacd-775cd8585604	v_costing_summary_full.tax_ratio	税费比例	费用比率	PERCENT	%	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
bfbc8ebb-c609-4c9f-ba82-4ee45f82a523	v_c_summary_agg.weight_unit_label	计量单位	单位标签	STRING	\N	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
d45599c8-ae7b-491b-884b-8515b30abcd7	v_c_summary_agg.currency_label	币种	单位标签	STRING	\N	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
d77faea4-6767-4c8f-a3c5-d5a1096d501a	v_costing_summary_full.material_loss_cost	材料损耗成本源	成本汇总	DECIMAL	¥	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
d82642f4-70b0-4dd9-9524-02d9ea07bf7c	v_costing_summary_full.mgmt_fee_ratio	管理费比例	费用比率	PERCENT	%	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
e38fdb22-2b4c-4260-9f94-173aaf039e3e	v_c_summary_agg.freight_fee	运费	成本汇总	DECIMAL	¥	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
e5b86fcb-f061-4535-ae8f-cecf90dd4ad9	v_costing_summary_full.exchange_rate_to_usd	核价汇率(CNY到USD)	汇率	DECIMAL	\N	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
e82786b4-ba0f-4e7c-9eac-780bbd0a2928	v_costing_summary_full.incoming_process_fee	来料加工费	成本汇总	DECIMAL	¥	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
ecb78a88-1f71-4882-ada4-5d137623cb2e	v_costing_summary_full.plating_defect_rate	电镀不良率	费用比率	PERCENT	%	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
ffecc21f-5b03-4fb0-991a-c056f7b65a46	v_costing_summary_full.process_fee_total	加工费源	成本汇总	DECIMAL	¥	\N	\N	VIEW_COLUMN	ACTIVE	2026-05-11 07:02:44.988559+00	2026-05-11 07:02:44.988559+00	\N	\N
\.


--
-- Name: component_code_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.component_code_seq', 1, false);


--
-- Name: costing_order_number_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.costing_order_number_seq', 1, false);


--
-- Name: cpq_feature_field_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.cpq_feature_field_id_seq', 1, false);


--
-- Name: cpq_feature_group_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.cpq_feature_group_id_seq', 1, false);


--
-- Name: cpq_feature_value_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.cpq_feature_value_id_seq', 1, false);


--
-- Name: customer_code_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.customer_code_seq', 1, false);


--
-- Name: quotation_number_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.quotation_number_seq', 1, false);


--
-- Name: quote_customer_code_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.quote_customer_code_seq', 1, false);

--
-- ============================================================================
--  部署自检 —— 执行完请跑一遍，四项都对上才算成功
-- ============================================================================
--
-- 注意：本脚本上方由 pg_dump 生成的部分把 search_path 置空了，
--       自检查询必须先恢复，否则会报 relation "user" does not exist。
SET search_path = public;

\echo ''
\echo '=== CPQ 部署自检 ==='

-- [1] 对象计数：期望 表=161  视图=10  函数=3
SELECT (SELECT count(*) FROM pg_tables  WHERE schemaname='public') AS "表",
       (SELECT count(*) FROM pg_views   WHERE schemaname='public') AS "视图",
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
         WHERE n.nspname='public')                                 AS "函数";

-- [2] 非空表数：期望 20（19 张种子表 + flyway_schema_history 基线行）
--     用真实 count(*) 统计，不用 pg_stat_user_tables.n_live_tup ——
--     后者是统计信息、刚导完数据时尚未刷新，会报出偏小的假值。
SELECT count(*) AS "非空表数(期望20)" FROM (
  SELECT (xpath('/row/c/text()',
           query_to_xml('SELECT count(*) c FROM public.'||quote_ident(relname),
                        false, true, '')))[1]::text::int AS n
    FROM pg_stat_user_tables) s
 WHERE n > 0;

-- [3] 管理员：期望 admin / SYSTEM_ADMIN / ACTIVE
SELECT username AS "账号", role AS "角色", status AS "状态" FROM "user";

-- [4] 核价树配置：期望 1 行（缺失会导致核价树渲染抛 400）
SELECT count(*) AS "核价树active配置(期望1)"
  FROM costing_bom_tree_config WHERE is_active;

-- [5] Flyway 基线：期望 version=343 / type=BASELINE
SELECT version AS "基线版本", type AS "类型", success AS "成功"
  FROM flyway_schema_history;

\echo '=== 自检结束 ==='

--
-- ============================================================================
--  种子数据清单（19 张表）
-- ============================================================================
--   系统配置类
--     system_config              25 行  硬依赖：缺 key 时 SystemConfigService 直接抛 404
--     basic_data_config          73 行  Excel 导入的 sheet 配置，空表则导入静默跳过
--     basic_data_attribute      504 行  跟随上表的字段级配置
--     costing_bom_tree_config     1 行  硬依赖：核价树递归 SQL，不在任何迁移文件里
--     comparison_tag             24 行  内置比对标签（is_builtin）
--     variable_label             22 行  视图列中文标签
--     global_variable_definition  6 行  全局变量定义（下游 FK 父表）
--     sel_param_type              3 行  选配参数类型，与 Java handler key 强耦合
--     composite_process_def       6 行  组合工序定义（下游 FK 父表）
--     part_no_sequence            9 行  料号段（代码有 ON CONFLICT 懒创建，预置更稳）
--   组织架构类
--     region                      4 行  华南/华东/华北/华中
--     department                  3 行  销售一~三部
--     product_category            1 行  DEFAULT 默认分类（报价 Step1 无分类会禁用「下一步」）
--     user                        1 行  admin / Admin@2026
--   主数据类
--     element                    39 行  元素主表
--     process                    41 行  工序
--     process_master             43 行  工序主档
--     material_recipe           263 行  材质配方（task-0708 材质库成果）
--     material_recipe_element   632 行  配方元素明细
-- ============================================================================
--  脚本结束
-- ============================================================================
