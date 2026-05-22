-- V212: 模板绑定全局变量 + 报价单引用数据快照
--
-- 实现 PRD §3.7 "模板绑定全局变量 + 报价单引用数据 Tab"
--
-- 两件事:
--   1. 新建 template_global_variable_binding 关联表 (模板 ↔ 全局变量, M:N)
--   2. quotation 表加 bound_global_variables_snapshot JSONB 列 (提交时快照)
--
-- 字段约定 (基于 V104 真实 schema):
--   - global_variable_definition 主键是 code VARCHAR(64), 非 UUID
--   - FK 直接引用 code (业务编码可读, 利于 JSONB 快照对账)
--   - is_active 是 BOOLEAN (不是 status='ACTIVE')
--   - var_type ∈ {LOOKUP_TABLE, SCALAR} (不是 source_kind)
--
-- 设计依据:
--   - docs/architecture/ADR-002-template-gv-binding.md
--   - docs/三大核心模块基线.md (不动 component / template 主流程)
--   - 不建视图, 不动 ImplicitJoinRewriter, 隔离边界保证不触 AP-31 / AP-44

-- ============================================================
-- 第一部分: template_global_variable_binding 关联表
-- ============================================================

CREATE TABLE IF NOT EXISTS template_global_variable_binding (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id            UUID         NOT NULL
        REFERENCES template(id) ON DELETE CASCADE,
    global_variable_code   VARCHAR(64)  NOT NULL
        REFERENCES global_variable_definition(code) ON DELETE RESTRICT,
    display_order          INT          NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_tgvb_template_code UNIQUE (template_id, global_variable_code)
);

COMMENT ON TABLE template_global_variable_binding IS
    'V212: 模板 ↔ 全局变量 绑定 (PRD §3.7). 模板发布后报价单详情页"引用数据"Tab 据此渲染. '
    '删除模板自动级联, 删除全局变量定义被 RESTRICT 阻拦 (要求先解绑).';

COMMENT ON COLUMN template_global_variable_binding.global_variable_code IS
    '引用 global_variable_definition.code (V104 主键, VARCHAR(64) 业务编码, 非 UUID).';

COMMENT ON COLUMN template_global_variable_binding.display_order IS
    '该 GV 在"引用数据"Tab 中卡片渲染顺序, 0-based 升序. createNewDraft 拷贝时原样保留.';

-- 索引: 按 template_id + display_order 升序查询是高频路径 (列绑定列表 / 渲染卡片)
CREATE INDEX IF NOT EXISTS idx_tgvb_template_order
    ON template_global_variable_binding (template_id, display_order);

-- 索引: 反向查询某 GV 被哪些模板引用 (admin 端排查"能否停用此 GV"用)
CREATE INDEX IF NOT EXISTS idx_tgvb_global_variable_code
    ON template_global_variable_binding (global_variable_code);

-- ============================================================
-- 第二部分: quotation.bound_global_variables_snapshot 列
-- ============================================================
--
-- 现状: V54 已在 quotation 表加了 submission_snapshot JSONB (整体快照).
-- 本次新增独立列, 不并入 submission_snapshot, 理由:
--   1. 独立列粒度可独立 GIN 索引 / EXPLAIN 跟踪
--   2. 回滚时 DROP COLUMN 不影响 V54 既有快照内容
--   3. 渲染路径不需 parse 整个 submission_snapshot, 减少前端 payload
--
-- 默认 '[]'::jsonb 而非 NULL:
--   - 简化前端判空 (永远是数组, 长度判断即可)
--   - 历史报价单 (V212 之前提交) 默认为空数组, 前端识别为"提交时未生成快照"
ALTER TABLE quotation
    ADD COLUMN IF NOT EXISTS bound_global_variables_snapshot JSONB
        NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN quotation.bound_global_variables_snapshot IS
    'V212: 报价单 DRAFT→SUBMITTED 时由 SnapshotCollectorService 写入. '
    '数组元素: {code, name, varType, unit, displayOrder, snapshotAt, rows[]}. '
    '快照不可变 (APPROVED/REJECTED 阶段只读); REJECTED→DRAFT→再次提交时覆盖.';

-- 不加 GIN 索引: 该列查询路径只有"按 quotation_id 直读整个 JSONB",
-- 不会跨行按 JSONB 内部字段过滤, GIN 索引收益为零、写放大开销不必要.
