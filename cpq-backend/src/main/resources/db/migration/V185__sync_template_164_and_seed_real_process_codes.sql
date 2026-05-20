-- V185: 补 V184 三处遗漏
--
-- 故障现象 (2026-05-17 用户反馈, QT-20260517-1381 草稿):
--   选配产品工序列表 成材率列空白, 单价列也是 — (—).
--
-- 根因:
--   1. V183 把 b1d2e3f4...163「选配产品标准报价模板」拆成 163(组合产品)+164(单一产品),
--      该报价单使用 164, 但 V184 只同步了 163 的 components_snapshot — 164 仍持 V182 配置(无 default_basic_data_path)
--   2. V184 把 COMP-CFG-PROCESS 成材率字段 content 从 "100" 改成 "" — 当全局变量查不到 key 时丢失静态兜底
--   3. process_default_yield 只 seed 了 p1~p9 (与 V173 PROCESS_DEFAULT_PRICE 对称),
--      但 mat_process 实际 process_code 是 Z350/Z029/MRO-AS-0001~0004 等业务编码 → 查表 miss → 列空
--      (此问题在 V182/V173 单价路径上也存在, 但属另一遗留 bug, 本次顺手只补 yield 表的 seed)
--
-- 修复:
--   1. 恢复 COMP-CFG-PROCESS 成材率字段 content="100" 作静态兜底
--   2. 同步 template 164 的 components_snapshot (复用 V184 的 jsonb_set 模式)
--   3. 把 mat_process 表中所有 distinct process_code 自动 seed 进 process_default_yield (默认 100)
--      — 之后用户在「全局变量配置」页可逐工序调整真实成材率

-- ── Step 1: 恢复 COMP-CFG-PROCESS 成材率字段的 content 静态兜底 ──────────────
UPDATE component
SET fields = '[
  {"name":"序号","field_type":"BASIC_DATA","basic_data_path":"mat_process.seq_no","content":"","is_amount":false,"is_subtotal":false},
  {"name":"工序名称","notes":"复用 mat_process.component_name 列, 选配场景写工序中文名","field_type":"BASIC_DATA","basic_data_path":"mat_process.component_name","content":"","is_amount":false,"is_subtotal":false},
  {"name":"单价","notes":"动态查 PROCESS_DEFAULT_PRICE by process_code","field_type":"BASIC_DATA","basic_data_path":"process_default_cost.unit_price","global_variable_code":"PROCESS_DEFAULT_PRICE","content":"","is_amount":true,"is_subtotal":false},
  {"name":"成材率","notes":"V184: 默认从 PROCESS_DEFAULT_YIELD 按 process_code 查; V185: content=100 静态兜底 (DB 查不到 key 时按 100 算); 用户输入即覆盖, 覆盖值只活在当前报价单草稿","field_type":"INPUT_NUMBER","content":"100","default_basic_data_path":"process_default_yield.yield_rate","default_global_variable_code":"PROCESS_DEFAULT_YIELD","is_amount":false,"is_subtotal":false},
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

-- ── Step 2: 同步 templates 163 + 164 的 components_snapshot ──────────────────
DO $$
DECLARE
    v_tpl_id UUID;
    v_snapshot JSONB;
    v_idx INT;
    v_comp_code TEXT;
    v_new_fields JSONB := (SELECT fields FROM component WHERE id = '0a436b6c-0b42-4381-a2da-7f901901968f');
BEGIN
    FOR v_tpl_id IN
        SELECT unnest(ARRAY[
            'b1d2e3f4-cf63-4163-8163-000000000163'::uuid,
            'b1d2e3f4-cf63-4163-8163-000000000164'::uuid
        ])
    LOOP
        SELECT components_snapshot INTO v_snapshot FROM template WHERE id = v_tpl_id;
        IF v_snapshot IS NULL THEN
            RAISE NOTICE 'V185: template % 无 components_snapshot, 跳过', v_tpl_id;
            CONTINUE;
        END IF;
        FOR v_idx IN 0 .. jsonb_array_length(v_snapshot) - 1 LOOP
            v_comp_code := v_snapshot -> v_idx ->> 'componentCode';
            IF v_comp_code IS NULL THEN
                v_comp_code := v_snapshot -> v_idx ->> 'component_code';
            END IF;
            IF v_comp_code = 'COMP-CFG-PROCESS' THEN
                v_snapshot := jsonb_set(v_snapshot, ARRAY[v_idx::text, 'fields'], v_new_fields);
            END IF;
        END LOOP;
        UPDATE template SET components_snapshot = v_snapshot, updated_at = NOW() WHERE id = v_tpl_id;
        RAISE NOTICE 'V185: template % components_snapshot 已同步 COMP-CFG-PROCESS', v_tpl_id;
    END LOOP;
END $$;

-- ── Step 3: 把 mat_process 中真实使用的 process_code 自动 seed 进 process_default_yield ──
-- 默认 100 (= 无损耗), 由 PRICING_MANAGER 在「全局变量配置」页按需调整
INSERT INTO process_default_yield (process_code, yield_rate)
SELECT DISTINCT process_code, 100.0000
FROM mat_process
WHERE process_code IS NOT NULL AND process_code <> ''
ON CONFLICT (process_code) DO NOTHING;

-- ── Step 4: 自检 ─────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_content_100 BOOLEAN;
    v_tpl163_synced BOOLEAN;
    v_tpl164_synced BOOLEAN;
    v_seed_count INT;
    v_real_codes_covered INT;
BEGIN
    SELECT fields::text LIKE '%"content": "100"%' INTO v_content_100
        FROM component WHERE id = '0a436b6c-0b42-4381-a2da-7f901901968f';
    SELECT components_snapshot::text LIKE '%default_basic_data_path%' INTO v_tpl163_synced
        FROM template WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163';
    SELECT components_snapshot::text LIKE '%default_basic_data_path%' INTO v_tpl164_synced
        FROM template WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000164';
    SELECT COUNT(*) INTO v_seed_count FROM process_default_yield;
    -- 验证 mat_process 实际 codes 100% 被覆盖
    SELECT COUNT(*) INTO v_real_codes_covered
        FROM (SELECT DISTINCT process_code FROM mat_process
              WHERE process_code IS NOT NULL AND process_code <> '') AS m
        WHERE EXISTS (SELECT 1 FROM process_default_yield p WHERE p.process_code = m.process_code);

    IF NOT v_content_100 THEN
        RAISE EXCEPTION 'V185 自检失败: COMP-CFG-PROCESS 成材率字段 content 未恢复为 100';
    END IF;
    IF NOT v_tpl163_synced OR NOT v_tpl164_synced THEN
        RAISE EXCEPTION 'V185 自检失败: template 163/164 components_snapshot 未同步 default_basic_data_path';
    END IF;
    IF v_seed_count < 9 THEN
        RAISE EXCEPTION 'V185 自检失败: process_default_yield 行数 % < 9', v_seed_count;
    END IF;

    RAISE NOTICE 'V185 自检通过: content=100 已恢复, template 163/164 已同步, process_default_yield % 行 (实际 mat_process codes 被覆盖 % 个)',
        v_seed_count, v_real_codes_covered;
END $$;
