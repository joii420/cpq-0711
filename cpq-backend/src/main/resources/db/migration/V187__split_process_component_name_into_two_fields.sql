-- V187: COMP-CFG-PROCESS 拆「工序名称」→「工序代码 + 材料/元素」
--
-- 背景 (2026-05-17 QT-20260517-1381 现象暴露):
--   V182 工序组件「工序名称」字段绑 mat_process.component_name, 但 component_name 实际
--   存的是材料/元素名 (铜件/银点/焊膏), 不是工序中文名 — 同一 process_code (如 Z350)
--   会展开 3 行各显示一个材料, 列标签语义不符.
--
-- 修复方案 (用户拍板 — 拆成两列):
--   原: 序号 / 工序名称(=component_name) / 单价 / 成材率 / 小计
--   新: 序号 / 工序代码(=process_code) / 材料/元素(=component_name) / 单价 / 成材率 / 小计
--   driver path 不变 (mat_process 仍 1:1 展开), 只改字段配置 + 标签语义.
--
-- 影响面验证:
--   - 小计公式 formula_tokens 只引用「成材率」「单价」, 不引用「工序名称」 → 公式不变.
--   - 字段 name 含 `/` 字符: V182 既有字段 name 都是简单中文, 此处「材料/元素」是首例.
--     已确认 React/JSX 渲染按 string 处理不会特殊解析; 公式 token 不引用该字段所以无需 escape.

UPDATE component
SET fields = '[
  {"name":"序号","field_type":"BASIC_DATA","basic_data_path":"mat_process.seq_no","content":"","is_amount":false,"is_subtotal":false},
  {"name":"工序代码","notes":"V187: 绑 mat_process.process_code, 与 PROCESS_DEFAULT_PRICE / PROCESS_DEFAULT_YIELD 的 key 列对应","field_type":"BASIC_DATA","basic_data_path":"mat_process.process_code","content":"","is_amount":false,"is_subtotal":false},
  {"name":"材料/元素","notes":"V187: 绑 mat_process.component_name, 显示工序内的材料/元素拆分行 (铜件/银点/焊膏 等)","field_type":"BASIC_DATA","basic_data_path":"mat_process.component_name","content":"","is_amount":false,"is_subtotal":false},
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

-- 同步 templates 163 + 164 的 components_snapshot (复用 V185 的循环模式)
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
            RAISE NOTICE 'V187: template % 无 components_snapshot, 跳过', v_tpl_id;
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
        RAISE NOTICE 'V187: template % components_snapshot 已同步拆字段', v_tpl_id;
    END LOOP;
END $$;

-- 自检
DO $$
DECLARE
    v_has_process_code BOOLEAN;
    v_has_material BOOLEAN;
    v_no_old_label BOOLEAN;
    v_tpl163_synced BOOLEAN;
    v_tpl164_synced BOOLEAN;
    v_formula_intact BOOLEAN;
BEGIN
    SELECT fields::text LIKE '%"name": "工序代码"%' INTO v_has_process_code
        FROM component WHERE id = '0a436b6c-0b42-4381-a2da-7f901901968f';
    SELECT fields::text LIKE '%"name": "材料/元素"%' INTO v_has_material
        FROM component WHERE id = '0a436b6c-0b42-4381-a2da-7f901901968f';
    SELECT fields::text NOT LIKE '%"name": "工序名称"%' INTO v_no_old_label
        FROM component WHERE id = '0a436b6c-0b42-4381-a2da-7f901901968f';
    -- 公式 token 仍含 成材率 + 单价 (拆字段不影响公式)
    SELECT fields::text LIKE '%"value": "成材率"%' AND fields::text LIKE '%"value": "单价"%'
        INTO v_formula_intact
        FROM component WHERE id = '0a436b6c-0b42-4381-a2da-7f901901968f';
    SELECT components_snapshot::text LIKE '%"name": "工序代码"%' INTO v_tpl163_synced
        FROM template WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163';
    SELECT components_snapshot::text LIKE '%"name": "工序代码"%' INTO v_tpl164_synced
        FROM template WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000164';

    IF NOT v_has_process_code OR NOT v_has_material THEN
        RAISE EXCEPTION 'V187 自检失败: COMP-CFG-PROCESS 未拆出「工序代码」+「材料/元素」字段';
    END IF;
    IF NOT v_no_old_label THEN
        RAISE EXCEPTION 'V187 自检失败: 旧「工序名称」字段未被移除';
    END IF;
    IF NOT v_formula_intact THEN
        RAISE EXCEPTION 'V187 自检失败: 小计公式 token 引用的成材率/单价丢失';
    END IF;
    IF NOT v_tpl163_synced OR NOT v_tpl164_synced THEN
        RAISE EXCEPTION 'V187 自检失败: template 163/164 components_snapshot 未同步';
    END IF;

    RAISE NOTICE 'V187 自检通过: 工序组件已拆为 工序代码 + 材料/元素 两列, 公式 token 完好, 双 template snapshot 已同步';
END $$;
