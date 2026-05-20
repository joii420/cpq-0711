-- V184: 工序默认成材率全局变量 — 配合 INPUT_NUMBER 字段的 default_basic_data_path 能力
--
-- 业务规则:
--   选配产品 工序列表 的「成材率」列 (V182 设为 INPUT_NUMBER, 默认 100) 升级为
--   「默认从全局变量取值, 用户仍可手填覆盖」 — 行值空时按 process_code 查
--   process_default_yield 表; 用户在报价单输入即覆盖, 覆盖值只活在当前报价单草稿里
--   (不回写主数据).
--
-- 决策依据 (2026-05-17 与用户确认):
--   1. 用 LOOKUP_TABLE (key=process_code) 而非 SCALAR — 和工序默认单价 V173 对称
--   2. 用户覆盖只活在当前报价单草稿 — 不回写 mat_process.yield_rate / 不回写全局变量
--   3. 种子值用 100 (= 无成材率损耗) — 保持 V182 之前行为不变, 上线后由 PRICING_MANAGER
--      到「全局变量配置」页按需调整 p1~p9 各工序的真实默认值
--
-- 落地步骤:
--   1. 创建 process_default_yield 表 (process_code PK, yield_rate, updated_at)
--   2. 注册到 basic_data_config 让 BNF 路径可识别
--   3. 注册全局变量 PROCESS_DEFAULT_YIELD (已存在则跳过)
--   4. Seed 9 行 p1~p9 默认成材率 (统一 100)
--   5. UPDATE COMP-CFG-PROCESS 组件: 给「成材率」字段加 default_basic_data_path +
--      default_global_variable_code (保留 field_type=INPUT_NUMBER 不变 — 仍可手填)
--   6. 同步选配产品标准报价模板 b1d2e3f4 的 components_snapshot

-- ── Step 1: 创建工序默认成材率表 ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS process_default_yield (
    process_code  VARCHAR(64)    PRIMARY KEY,
    yield_rate    DECIMAL(7,4)   NOT NULL,
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_pdy_yield_rate_range CHECK (yield_rate > 0 AND yield_rate <= 100)
);
COMMENT ON TABLE process_default_yield IS
    '工序默认成材率表 — INPUT_NUMBER 字段 default_basic_data_path 通过 PROCESS_DEFAULT_YIELD 全局变量按 process_code 取默认值';
COMMENT ON COLUMN process_default_yield.process_code IS '工序代码, 与 mat_process.process_code 关联';
COMMENT ON COLUMN process_default_yield.yield_rate   IS '默认成材率 (百分比 0~100, 例 95 = 95%)';
COMMENT ON COLUMN process_default_yield.updated_at   IS '最后更新时间';

-- ── Step 2: 注册到 basic_data_config (供 BNF path resolver 识别) ──────────────
INSERT INTO basic_data_config (
    sheet_name,
    sheet_index,
    header_row_index,
    data_start_row_index,
    description,
    join_columns,
    sort_order,
    status,
    template_kind
)
SELECT
    'process_default_yield',
    0,
    1,
    2,
    '工序默认成材率 — BNF 变量 PROCESS_DEFAULT_YIELD 的数据源',
    '[]'::jsonb,
    0,
    'ACTIVE',
    'BOTH'
WHERE NOT EXISTS (
    SELECT 1 FROM basic_data_config WHERE sheet_name = 'process_default_yield'
);

-- ── Step 3: 注册全局变量 PROCESS_DEFAULT_YIELD ───────────────────────────────
INSERT INTO global_variable_definition (
    code,
    name,
    var_type,
    source_view,
    key_columns,
    value_column,
    label_template,
    unit,
    description,
    sort_order,
    is_active
)
VALUES (
    'PROCESS_DEFAULT_YIELD',
    '工序默认成材率',
    'LOOKUP_TABLE',
    'process_default_yield',
    '["process_code"]'::jsonb,
    'yield_rate',
    'process_code',
    '%',
    '按工序代码查询默认成材率 (0~100), 用于 工序列表「成材率」字段空值时的回退取值; 用户在报价单手填后即覆盖',
    110,
    true
)
ON CONFLICT (code) DO NOTHING;

-- ── Step 4: Seed 9 行工序默认成材率 p1~p9 ────────────────────────────────────
-- 统一 100 = 无损耗 (保持 V182 之前行为不变), 由 PRICING_MANAGER 按需调整
INSERT INTO process_default_yield (process_code, yield_rate) VALUES
    ('p1', 100.0000),
    ('p2', 100.0000),
    ('p3', 100.0000),
    ('p4', 100.0000),
    ('p5', 100.0000),
    ('p6', 100.0000),
    ('p7', 100.0000),
    ('p8', 100.0000),
    ('p9', 100.0000)
ON CONFLICT (process_code) DO NOTHING;

-- ── Step 5: 给 COMP-CFG-PROCESS 的「成材率」字段绑全局变量默认值 ─────────────
-- 保留 field_type=INPUT_NUMBER (用户仍可手填); 新增 default_basic_data_path +
-- default_global_variable_code 两个元数据
UPDATE component
SET fields = '[
  {"name":"序号","field_type":"BASIC_DATA","basic_data_path":"mat_process.seq_no","content":"","is_amount":false,"is_subtotal":false},
  {"name":"工序名称","notes":"复用 mat_process.component_name 列, 选配场景写工序中文名","field_type":"BASIC_DATA","basic_data_path":"mat_process.component_name","content":"","is_amount":false,"is_subtotal":false},
  {"name":"单价","notes":"动态查 PROCESS_DEFAULT_PRICE by process_code","field_type":"BASIC_DATA","basic_data_path":"process_default_cost.unit_price","global_variable_code":"PROCESS_DEFAULT_PRICE","content":"","is_amount":true,"is_subtotal":false},
  {"name":"成材率","notes":"V184: 默认从 PROCESS_DEFAULT_YIELD 按 process_code 查; 用户输入即覆盖, 覆盖值只活在当前报价单草稿","field_type":"INPUT_NUMBER","content":"","default_basic_data_path":"process_default_yield.yield_rate","default_global_variable_code":"PROCESS_DEFAULT_YIELD","is_amount":false,"is_subtotal":false},
  {"name":"小计","notes":"累加公式: 上道工序小计 / 成材率 * 100 + 单价 (第一道工序 fallback 到元素 Tab 小计)","field_type":"FORMULA","is_amount":true,"is_subtotal":true,"formula_tokens":[
    {"type":"previous_row_subtotal","fallback_component_code":"COMP-CFG-ELEMENT-BOM"},
    {"type":"operator","value":"/","label":"÷"},
    {"type":"field","value":"成材率"},
    {"type":"operator","value":"*","label":"×"},
    {"type":"number","value":"100"},
    {"type":"operator","value":"+","label":"+"},
    {"type":"field","value":"单价"}
  ]}
]'::jsonb,
    updated_at = NOW()
WHERE id = '0a436b6c-0b42-4381-a2da-7f901901968f';

-- ── Step 6: 同步 template b1d2e3f4 的 components_snapshot ────────────────────
DO $$
DECLARE
    v_snapshot JSONB;
    v_idx INT;
    v_comp_code TEXT;
    v_new_process_fields JSONB := (SELECT fields FROM component WHERE id = '0a436b6c-0b42-4381-a2da-7f901901968f');
BEGIN
    SELECT components_snapshot INTO v_snapshot
    FROM template WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163';

    IF v_snapshot IS NULL THEN
        RAISE NOTICE 'V184: template b1d2e3f4 无 components_snapshot, 跳过 snapshot 同步';
        RETURN;
    END IF;

    FOR v_idx IN 0 .. jsonb_array_length(v_snapshot) - 1 LOOP
        v_comp_code := v_snapshot -> v_idx ->> 'componentCode';
        IF v_comp_code IS NULL THEN
            v_comp_code := v_snapshot -> v_idx ->> 'component_code';
        END IF;

        IF v_comp_code = 'COMP-CFG-PROCESS' THEN
            v_snapshot := jsonb_set(v_snapshot, ARRAY[v_idx::text, 'fields'], v_new_process_fields);
        END IF;
    END LOOP;

    UPDATE template
    SET components_snapshot = v_snapshot, updated_at = NOW()
    WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163';

    RAISE NOTICE 'V184: template b1d2e3f4 components_snapshot 已同步 COMP-CFG-PROCESS (含成材率默认值绑定)';
END $$;

-- ── Step 7: 自检 ─────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_table_exists BOOLEAN;
    v_var_registered BOOLEAN;
    v_field_bound BOOLEAN;
    v_snapshot_synced BOOLEAN;
    v_seed_count INT;
BEGIN
    SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'process_default_yield')
        INTO v_table_exists;
    SELECT EXISTS (SELECT 1 FROM global_variable_definition WHERE code = 'PROCESS_DEFAULT_YIELD')
        INTO v_var_registered;
    SELECT fields::text LIKE '%default_basic_data_path%' INTO v_field_bound
        FROM component WHERE id = '0a436b6c-0b42-4381-a2da-7f901901968f';
    SELECT components_snapshot::text LIKE '%default_basic_data_path%' INTO v_snapshot_synced
        FROM template WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163';
    SELECT COUNT(*) INTO v_seed_count FROM process_default_yield;

    IF NOT v_table_exists THEN
        RAISE EXCEPTION 'V184 自检失败: process_default_yield 表未创建';
    END IF;
    IF NOT v_var_registered THEN
        RAISE EXCEPTION 'V184 自检失败: PROCESS_DEFAULT_YIELD 全局变量未注册';
    END IF;
    IF NOT v_field_bound THEN
        RAISE EXCEPTION 'V184 自检失败: COMP-CFG-PROCESS 成材率字段未绑定 default_basic_data_path';
    END IF;
    IF NOT v_snapshot_synced THEN
        RAISE EXCEPTION 'V184 自检失败: template snapshot 未同步 default_basic_data_path';
    END IF;
    IF v_seed_count < 9 THEN
        RAISE EXCEPTION 'V184 自检失败: process_default_yield seed 数据不足 9 行 (实际 %)', v_seed_count;
    END IF;

    RAISE NOTICE 'V184 自检通过: 工序默认成材率全局变量已就位 (% 行 seed)', v_seed_count;
END $$;
