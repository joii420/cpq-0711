-- task-0806 · 模板发布全量冻结（P0 架构级缺陷修复）
--
-- 背景：docs/三大核心模块基线.md:178 写明 snapshot 存在的理由——组件管理改字段后，
-- 老模板的历史报价单不能反向受影响。但代码实现（ComponentService.update:733 每次保存组件
-- 就无条件调 TemplateService.refreshSnapshotsByComponent）绕过了这条基线，且 component 实体
-- 18 个渲染配置字段中 rowKeyFields/sortField/element*Field/columnCount 这 6 个从未进过任何
-- snapshot、渲染期靠直读活表兜底——本迁移是"全量冻结"改造的地基（B1+B2+B3）。
--
-- 本迁移做三件事（同一批，保证两份数据从第一天起同源）：
--   1. 建表 template_component_snapshot —— 已发布模板每个页签的完整生效配置（18 个渲染配置字段全冻）
--   2. operation_log 加 details jsonb 列（加法式，供 admin 后门审计用）
--   3. 存量对齐：按当前活组件配置为所有 PUBLISHED + ARCHIVED 模板重建一次完整快照，
--      并按新表重新生成 template.components_snapshot jsonb（保证两份同源）

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

ALTER TABLE operation_log ADD COLUMN details jsonb;
COMMENT ON COLUMN operation_log.details IS
    'task-0806：结构化 diff（改前改后），供 admin 后门审计用（TEMPLATE_SNAPSHOT_FORCE_REFRESH / '
    'TEMPLATE_TC_DELETE / TEMPLATE_OVERRIDE_PROMOTE）。可空，不影响既有写入方。';

-- ============================================================================
-- B3: 存量对齐（幂等：先删后插）+ jsonb 重生成（覆盖 PUBLISHED + ARCHIVED）
-- ============================================================================

DELETE FROM template_component_snapshot
 WHERE template_id IN (SELECT id FROM template WHERE status IN ('PUBLISHED', 'ARCHIVED'));

-- 按 tc 逐行插入（不得按 componentId 聚合——AP-40 教训：同 componentId 多 tc 实例必须
-- 各自独立处理，唯一约束 (template_id, template_component_id) 保证每个 tc 恰好一行）。
-- COALESCE 保留 override 优先语义（fields_override / data_driver_path_override 当前全 NULL，
-- 但语义必须对，否则将来谁用 promote-override-to-component 之外的路径写了一个就静默丢）。
INSERT INTO template_component_snapshot (
    template_id, template_component_id, component_id, sort_order,
    tab_name, preset_rows, formula_assignments,
    component_name, component_code, component_type, column_count,
    fields, formulas, excel_columns, data_driver_path,
    tree_config, bom_recursive_expand, tab_type, part_no_field, part_name_field,
    row_key_fields, sort_field,
    element_code_field, element_price_field, element_currency_field)
SELECT tc.template_id, tc.id, tc.component_id, tc.sort_order,
       tc.tab_name,
       COALESCE(tc.preset_rows, '[]'::jsonb),
       COALESCE(tc.formula_assignments, '{}'::jsonb),
       c.name, c.code, c.component_type, c.column_count,
       COALESCE(tc.fields_override, c.fields),                                   -- override 优先
       c.formulas, c.excel_columns,
       COALESCE(NULLIF(tc.data_driver_path_override, ''), c.data_driver_path),   -- override 优先
       c.tree_config, c.bom_recursive_expand, c.tab_type, c.part_no_field, c.part_name_field,
       c.row_key_fields, c.sort_field,
       c.element_code_field, c.element_price_field, c.element_currency_field
  FROM template_component tc
  JOIN component c ON c.id = tc.component_id
  JOIN template  t ON t.id = tc.template_id
 WHERE t.status IN ('PUBLISHED', 'ARCHIVED');

-- 按新表重新生成 components_snapshot jsonb（与 TemplateService.publish() 的 18 键 entry
-- 形状逐字段一致——PostgreSQL jsonb 按内部规则（键长度优先）规范化输出顺序，与构造时的键
-- 插入顺序无关，故这里的 jsonb_build_object 参数顺序不影响与 Java 端输出的等价性，
-- 只要键集合 + 值一致即可，AC-2 的"键顺序一致"由 jsonb 类型本身保证）。
UPDATE template t
   SET components_snapshot = sub.snap
  FROM (
    SELECT tcs.template_id,
           jsonb_agg(
             jsonb_build_object(
               'id', tcs.template_component_id,
               'componentId', tcs.component_id,
               'componentName', tcs.component_name,
               'componentCode', tcs.component_code,
               'componentType', tcs.component_type,
               'excelColumns', tcs.excel_columns,
               'tabName', tcs.tab_name,
               'sortOrder', tcs.sort_order,
               'fields', tcs.fields,
               'formulas', tcs.formulas,
               'preset_rows', tcs.preset_rows,
               'data_driver_path', tcs.data_driver_path,
               'tree_config', tcs.tree_config,
               'formula_assignments', tcs.formula_assignments,
               'tab_type', tcs.tab_type,
               'part_no_field', tcs.part_no_field,
               'part_name_field', tcs.part_name_field,
               'bom_recursive_expand', tcs.bom_recursive_expand
             ) ORDER BY tcs.sort_order
           ) AS snap
      FROM template_component_snapshot tcs
     GROUP BY tcs.template_id
  ) sub
 WHERE t.id = sub.template_id
   AND t.status IN ('PUBLISHED', 'ARCHIVED');
