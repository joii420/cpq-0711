-- task-0806 · 模板发布全量冻结（P0 架构级缺陷修复）
--
-- 背景：docs/三大核心模块基线.md:178 写明 snapshot 存在的理由——组件管理改字段后，
-- 老模板的历史报价单不能反向受影响。但代码实现（ComponentService.update:733 每次保存组件
-- 就无条件调 TemplateService.refreshSnapshotsByComponent）绕过了这条基线，且 component 实体
-- 18 个渲染配置字段中 rowKeyFields/sortField/element*Field/columnCount 这 6 个从未进过任何
-- snapshot、渲染期靠直读活表兜底——本迁移是"全量冻结"改造的地基（B1+B2）。
--
-- 本迁移做两件事：
--   1. 建表 template_component_snapshot —— 已发布模板每个页签的完整生效配置（18 个渲染配置字段全冻）
--   2. operation_log 加 details jsonb 列（加法式，供 admin 后门审计用）
--
-- 🔄 2026-08-07 B20（D16 推翻原 D3）：原第 3 步「存量对齐」（按当前活配置为所有已发布/归档
-- 模板一次性回填快照 + 重生成 components_snapshot jsonb）已删除，不做存量迁移——
-- 用户口径「都是测试数据，重要的是机制」：未经审阅的现状不该被固化成基线，且省掉迁移脚本
-- 自身正确性的验证负担。改为「不迁移存量，业务逻辑修正后由用户手工重新发布模板即可」：
--   - V382 执行后本表行数 = 0（新建空表）；
--   - 过渡期（模板 PUBLISHED/ARCHIVED 但尚未按新语义重新发布，即快照零行）由
--     PublishedTemplateReader 显式识别为「未冻结」，不报错、不读活表，见该类 + D17；
--   - archive() 若发现无快照会自动补冻一份再归档（D18，归档是终态，没法重新发布）；
--   - 详见 dev-docs/task-0806-模板发布全量冻结/需求文档.md D16~D19。

-- ============================================================================
-- B1: 新表 template_component_snapshot
-- ============================================================================

CREATE TABLE template_component_snapshot (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id             uuid NOT NULL REFERENCES template(id) ON DELETE CASCADE,
    template_component_id   uuid NOT NULL,          -- 溯源，刻意不建 FK（快照与活表脱钩）
    component_id            uuid NOT NULL,          -- 溯源，刻意不建 FK（组件停用/删除不动摇已发布模板）
    sort_order              integer NOT NULL,

    -- 来自 template_component（模板级，已冻结）
    tab_name                varchar(200),
    preset_rows             jsonb NOT NULL DEFAULT '[]',
    formula_assignments     jsonb NOT NULL DEFAULT '{}',

    -- 来自 component（内容层，18 个渲染配置字段中的 component 侧；本次新补 6 个见下方注释）
    component_name          varchar(200),
    component_code          varchar(100),
    component_type          varchar(20)  NOT NULL DEFAULT 'NORMAL',
    column_count            integer      NOT NULL DEFAULT 0,   -- ② 新补
    fields                  jsonb        NOT NULL DEFAULT '[]',
    formulas                jsonb        NOT NULL DEFAULT '[]',
    excel_columns           jsonb        NOT NULL DEFAULT '[]',
    data_driver_path        text,
    tree_config             jsonb,
    bom_recursive_expand    boolean      NOT NULL DEFAULT false,
    tab_type                varchar(30),
    part_no_field           varchar(100),
    part_name_field         varchar(100),
    row_key_fields          jsonb,                  -- ② 新补
    sort_field              varchar(120),           -- ② 新补
    element_code_field      varchar(100),           -- ② 新补
    element_price_field     varchar(100),           -- ② 新补
    element_currency_field  varchar(100),           -- ② 新补

    frozen_at               timestamptz  NOT NULL DEFAULT now(),

    -- 实测踩坑（2026-08-07，跑 cpq_db 测试库 ./mvnw test 时发现）：backtask.md 原设计
    -- UNIQUE (template_id, sort_order) 假设「同一模板内 sort_order 天然唯一」不成立——
    -- 测试库有 13 个 PUBLISHED 模板存在同 sort_order 的两个 tc（如「电镀费用」与「报价小计」
    -- 均 sort_order=7，疑似历史 fixture/迁移脚本未递增 SUBTOTAL 页签的 sort_order 所致）。
    -- 改用 (template_id, template_component_id) 作为唯一键：template_component_id 是来自
    -- template_component 表的真实主键，天然保证每个 tc 恰好一行快照，且不依赖 sort_order
    -- 取值质量。AP-40「同 componentId 多 tc 反向污染」的消灭效果不受影响——新架构下
    -- 写入路径是按 tcs 列表逐行直接落盘（persistSnapshotRows），不存在「按部分 key 反查 tc」
    -- 这一步，AP-40 的具体触发路径（歧义 firstResult()）已随 refreshSnapshotsByComponent
    -- 整体退役而结构性消失，故约束键换成更稳健的 template_component_id 不影响这一收益。
    CONSTRAINT uq_tcs_template_tc UNIQUE (template_id, template_component_id)
);

COMMENT ON TABLE template_component_snapshot IS
    'task-0806：已发布模板的全量渲染配置冻结（一行 = 一个模板的一个页签的完整生效配置）。'
    '写入只发生在 TemplateService.publish()；读取只经 PublishedTemplateReader；'
    '读不到就报错，禁止回落活表 component / template_component。';
COMMENT ON COLUMN template_component_snapshot.template_component_id IS '溯源用，刻意不建 FK（快照与活表脱钩），但组成本表的业务唯一键 uq_tcs_template_tc';
COMMENT ON COLUMN template_component_snapshot.component_id IS '溯源用，刻意不建 FK——组件被停用/删除不该动摇已发布模板';

CREATE INDEX idx_tcs_template         ON template_component_snapshot(template_id);
CREATE INDEX idx_tcs_component        ON template_component_snapshot(component_id);
CREATE INDEX idx_tcs_template_tabtype ON template_component_snapshot(template_id, tab_type);
CREATE INDEX idx_tcs_template_driver  ON template_component_snapshot(template_id)
                                       WHERE data_driver_path IS NOT NULL AND data_driver_path <> '';

-- ============================================================================
-- B2: operation_log 加 details jsonb 列（加法式，不影响 CustomerService 等既有写入方）
-- ============================================================================

-- IF NOT EXISTS：B20 改造时本迁移在 dev 库已先跑过一次旧版本（当时 details 列已建好）；
-- 手工清库只删了 flyway_schema_history 的版本行 + DROP 本文件新建的快照表，特意保留
-- 已存在的 operation_log.details 列不动（无害，见需求文档 D16 决策记录）。若不加
-- IF NOT EXISTS，重跑本迁移会因列已存在而报错。全新环境（该列本就不存在）行为不变。
ALTER TABLE operation_log ADD COLUMN IF NOT EXISTS details jsonb;
COMMENT ON COLUMN operation_log.details IS
    'task-0806：结构化 diff（改前改后），供 admin 后门审计用（TEMPLATE_SNAPSHOT_FORCE_REFRESH / '
    'TEMPLATE_TC_DELETE / TEMPLATE_OVERRIDE_PROMOTE）。可空，不影响既有写入方。';

-- ============================================================================
-- B21（2026-08-07，缺口一）：PUBLISHED / ARCHIVED 模板的 template.components_snapshot
-- jsonb 一并清空 —— 两份快照必须同生同灭，否则「未冻结」信号会被陈旧 jsonb 架空
-- ============================================================================
--
-- 实测复现（templateId=70f1b149-b0d9-4cb1-9245-6c3cee1bc3af 罗克韦尔模板1）：本迁移上半段
-- 只清空了新表 template_component_snapshot（起步 0 行，不回填，D16），但 template 表的
-- components_snapshot 列——那是【改造前最后一次 publish() 留下的旧 jsonb】——原样留着。
-- 渲染链路里一大批读取点（CardSnapshotService.buildCardValues / loadComponentsSnapshot 等）
-- 用原生 SQL 直读 template.components_snapshot，完全不经过 PublishedTemplateReader 网关：
-- 见到非空 jsonb 就当"已发布"，拿它搭出一副页签骨架，再向新表（0 行）要内容——
-- 产出 HTTP 200 + N 个页签但 0 数据 0 字段定义的空壳，D17"请重新发布"的提示信号被架空，
-- 用户看不到任何异常，以为系统坏了。
--
-- 清空后两份快照恒同步：「0 行 + NULL」是唯一、诚实、可被 PublishedTemplateReader 识别的
-- "未冻结"态；后续任何一次 publish()/archive() 都会用同一份 persistSnapshotRows 产出的
-- rows 同时重建两侧（見 TemplateService:228-231/347-348/517-518），不会再走出这一步产生的
-- 分叉。选 NULL 而非 '[]'：全仓库现存的 "componentsSnapshot != null && !isBlank()" 判据
-- （ExcelViewService/CardSnapshotService/ConfigureSnapshotService/QuotationService 等
-- 十余处）都已经把 NULL 当作"尚未冻结"的既有信号（对从未发布过的草稿模板即是如此），
-- 复用同一套信号是改动面最小的选择；'[]' 反而会被这些判据误读成"已发布但恰好 0 个页签"，
-- 在 buildCardValues 等路径产出的是一份没有任何"失败/待重新发布"标记的、貌似合法的空结构，
-- 比 NULL 更容易被误当成正常状态。
UPDATE template SET components_snapshot = NULL WHERE status IN ('PUBLISHED', 'ARCHIVED');
