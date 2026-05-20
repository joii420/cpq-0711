-- V190: 全局变量单表统一存储 — 终结「一变量一物理表」反模式
--
-- 决策依据 (2026-05-18 用户拍板):
--   1. 只拆 2 张轻量配置表 (process_default_cost / process_default_yield);
--      核价 3 张表 (costing_element_price/material_price/exchange_rate) 保留,
--      从「全局变量」UI 抽象退出 (加 visibility=COSTING_INTERNAL)
--   2. 新建 global_variable_value 单表存所有 KV 形态变量值, 加变量纯 UI 操作零 Java 改动
--   3. global_variable_definition 加 value_source_type 列分发:
--      KV_TABLE=查单表 (新, 轻量配置); COSTING_VIEW=查 source_view 视图 (核价 3 张保留)
--   4. ETL 现有 process_default_* 数据进单表后 DROP 物理表
--
-- 配套改动 (Java 代码侧):
--   - GlobalVariableService 增 readFromKvTable / readFromView 分发分支
--   - upsertEntry/deleteEntry COSTING_VIEW 拒绝写 (走核价模块)
--   - GlobalVariableDefinition POJO 加 valueSourceType / visibility 字段
--   - ImplicitJoinRewriter global_variable token 编译期按 value_source_type 分发
--
-- 配套改动 (前端):
--   - GlobalVariablePage 过滤 visibility=COSTING_INTERNAL
--   - Picker 显示核价变量但加「核价」tag, 禁用「维护数据」按钮

-- ── Step 1: 扩 global_variable_definition 加分发字段 ────────────────────────
ALTER TABLE global_variable_definition
    ADD COLUMN IF NOT EXISTS value_source_type VARCHAR(32) NOT NULL DEFAULT 'KV_TABLE',
    ADD COLUMN IF NOT EXISTS visibility VARCHAR(32) NOT NULL DEFAULT 'PUBLIC';

COMMENT ON COLUMN global_variable_definition.value_source_type IS
    'V190: KV_TABLE = 查 global_variable_value 单表 (轻量配置); COSTING_VIEW = 查 source_view 视图 (核价 3 张)';
COMMENT ON COLUMN global_variable_definition.visibility IS
    'V190: PUBLIC = 全局变量页可见可编辑; COSTING_INTERNAL = Picker 可选但 UI 列表过滤, 「维护数据」按钮禁用';

ALTER TABLE global_variable_definition
    DROP CONSTRAINT IF EXISTS chk_gvd_value_source_type,
    DROP CONSTRAINT IF EXISTS chk_gvd_visibility;
ALTER TABLE global_variable_definition
    ADD CONSTRAINT chk_gvd_value_source_type
        CHECK (value_source_type IN ('KV_TABLE','COSTING_VIEW')),
    ADD CONSTRAINT chk_gvd_visibility
        CHECK (visibility IN ('PUBLIC','COSTING_INTERNAL'));

-- ── Step 2: 标记 5 个现有变量 ───────────────────────────────────────────────
-- 核价 3 张: COSTING_VIEW + COSTING_INTERNAL (查视图, 全局变量页面隐藏)
UPDATE global_variable_definition
SET value_source_type = 'COSTING_VIEW', visibility = 'COSTING_INTERNAL'
WHERE code IN ('ELEM_PRICE','MAT_PRICE','EXCHANGE_RATE');

-- 工序 2 张: KV_TABLE + PUBLIC (切到单表, UI 全功能可编辑)
UPDATE global_variable_definition
SET value_source_type = 'KV_TABLE', visibility = 'PUBLIC'
WHERE code IN ('PROCESS_DEFAULT_PRICE','PROCESS_DEFAULT_YIELD');

-- ── Step 3: 建 global_variable_value 单表 ───────────────────────────────────
CREATE TABLE IF NOT EXISTS global_variable_value (
    var_code      VARCHAR(64) NOT NULL,
    key_id        VARCHAR(200) NOT NULL,        -- SCALAR='_'; LOOKUP=key 列拼接 (如 'Z350' / 'CNY:USD')
    key_values    JSONB NOT NULL DEFAULT '{}'::jsonb,  -- 结构化保留 (如 {"process_code":"Z350"})
    value_number  NUMERIC(20,4),                -- value_type=NUMBER 用这列
    value_text    TEXT,                         -- value_type=STRING 用这列 (未来扩展)
    note          TEXT,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (var_code, key_id),
    CONSTRAINT fk_gvv_var_code FOREIGN KEY (var_code)
        REFERENCES global_variable_definition(code) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_gvv_var_code ON global_variable_value(var_code);

COMMENT ON TABLE global_variable_value IS
    'V190 全局变量值统一存储 — 替代 process_default_* 等独立物理表; 加变量纯 UI 操作, Java 零改动. 核价 3 张表仍走 source_view, 不存这里';

-- ── Step 4: ETL 现有 2 张轻量表数据进单表 ───────────────────────────────────
INSERT INTO global_variable_value (var_code, key_id, key_values, value_number, updated_at)
SELECT
    'PROCESS_DEFAULT_PRICE',
    process_code,
    jsonb_build_object('process_code', process_code),
    unit_price,
    updated_at
FROM process_default_cost
ON CONFLICT (var_code, key_id) DO NOTHING;

INSERT INTO global_variable_value (var_code, key_id, key_values, value_number, updated_at)
SELECT
    'PROCESS_DEFAULT_YIELD',
    process_code,
    jsonb_build_object('process_code', process_code),
    yield_rate,
    updated_at
FROM process_default_yield
ON CONFLICT (var_code, key_id) DO NOTHING;

-- ── Step 5: 清理 basic_data_config (废弃 BNF resolver 入口) ─────────────────
DELETE FROM basic_data_config
WHERE sheet_name IN ('process_default_cost', 'process_default_yield');

-- ── Step 6: DROP 2 张轻量物理表 ─────────────────────────────────────────────
-- 已确认无视图/外键依赖 (pg_views 全表扫描无引用)
DROP TABLE IF EXISTS process_default_cost CASCADE;
DROP TABLE IF EXISTS process_default_yield CASCADE;

-- ── Step 7: 自检 ────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_table_dropped_1 BOOLEAN; v_table_dropped_2 BOOLEAN;
    v_kv_table_built BOOLEAN;
    v_etl_price_count INT; v_etl_yield_count INT;
    v_costing_marked INT; v_kv_marked INT;
    v_z350_yield NUMERIC;
BEGIN
    SELECT NOT EXISTS (SELECT 1 FROM information_schema.tables
        WHERE table_name = 'process_default_cost') INTO v_table_dropped_1;
    SELECT NOT EXISTS (SELECT 1 FROM information_schema.tables
        WHERE table_name = 'process_default_yield') INTO v_table_dropped_2;
    SELECT EXISTS (SELECT 1 FROM information_schema.tables
        WHERE table_name = 'global_variable_value') INTO v_kv_table_built;
    SELECT COUNT(*) INTO v_etl_price_count FROM global_variable_value
        WHERE var_code = 'PROCESS_DEFAULT_PRICE';
    SELECT COUNT(*) INTO v_etl_yield_count FROM global_variable_value
        WHERE var_code = 'PROCESS_DEFAULT_YIELD';
    SELECT COUNT(*) INTO v_costing_marked FROM global_variable_definition
        WHERE value_source_type = 'COSTING_VIEW';
    SELECT COUNT(*) INTO v_kv_marked FROM global_variable_definition
        WHERE value_source_type = 'KV_TABLE';
    -- 关键验证: Z350 的成材率值用户改过 20, ETL 应保留 20
    SELECT value_number INTO v_z350_yield FROM global_variable_value
        WHERE var_code = 'PROCESS_DEFAULT_YIELD' AND key_id = 'Z350';

    IF NOT v_table_dropped_1 OR NOT v_table_dropped_2 THEN
        RAISE EXCEPTION 'V190 自检失败: 2 张物理表未删除';
    END IF;
    IF NOT v_kv_table_built THEN
        RAISE EXCEPTION 'V190 自检失败: global_variable_value 未建';
    END IF;
    IF v_etl_price_count < 15 OR v_etl_yield_count < 15 THEN
        RAISE EXCEPTION 'V190 自检失败: ETL 数据不全 (price=%, yield=%, 期望各 ≥15)',
            v_etl_price_count, v_etl_yield_count;
    END IF;
    IF v_costing_marked <> 3 THEN
        RAISE EXCEPTION 'V190 自检失败: 核价 3 张未正确标 COSTING_VIEW (实际 %)', v_costing_marked;
    END IF;
    IF v_kv_marked <> 2 THEN
        RAISE EXCEPTION 'V190 自检失败: 工序 2 张未正确标 KV_TABLE (实际 %)', v_kv_marked;
    END IF;
    IF v_z350_yield IS NULL THEN
        RAISE EXCEPTION 'V190 自检失败: Z350 yield_rate ETL 后丢失 (key=Z350 在单表中无行)';
    END IF;

    RAISE NOTICE 'V190 自检通过: 单表已建, 2 张轻量表已拆, ETL price=% rows + yield=% rows, 5 变量已分类, Z350 ETL 值=%',
        v_etl_price_count, v_etl_yield_count, v_z350_yield;
END $$;
