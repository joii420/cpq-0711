-- V182: 选配产品标准报价模板 — 工序 / 元素 Tab 配置改造
--
-- 业务规则(2026-05-17):
--   1. 工序 Tab 列: 序号 / 工序名称 / 单价 / 成材率(默认100) / 小计
--      小计公式: 本工序小计 = 上道工序小计 ÷ 本工序成材率 × 100 + 本工序单价
--      第一道工序的"上道工序小计" = 元素 Tab 的小计 (跨组件 fallback)
--   2. 元素 Tab 列: 序号 / 元素 / 含量 / 单位 / 单价 / 小计
--      小计公式: = 含量(%) / 100 × 单价
--      (旧版含损耗率, 本期按用户要求精简)
--
-- 引擎能力依赖: previous_row_subtotal token (2026-05-17 同期扩展 formulaEngine).
--   行 0 时 ProductCard 不传 previousRowSubtotal → 走 token 的 fallback_component_code
--   取跨组件 subtotal; 行 N 时按 row_index 顺序求值后传上一行 is_subtotal 字段值.
--
-- 受影响组件:
--   - COMP-CFG-PROCESS         (0a436b6c-0b42-4381-a2da-7f901901968f)
--   - COMP-CFG-ELEMENT-BOM     (dae85db8-cf47-44df-890d-516625a598da)
-- 同步 template.components_snapshot:
--   - 选配产品标准报价模板 (b1d2e3f4-cf63-4163-8163-000000000163)

-- ── 1. 工序组件 ──────────────────────────────────────────────────────────

UPDATE component
SET fields = '[
  {"name":"序号","field_type":"BASIC_DATA","basic_data_path":"mat_process.seq_no","content":"","is_amount":false,"is_subtotal":false},
  {"name":"工序名称","notes":"复用 mat_process.component_name 列, 选配场景写工序中文名","field_type":"BASIC_DATA","basic_data_path":"mat_process.component_name","content":"","is_amount":false,"is_subtotal":false},
  {"name":"单价","notes":"动态查 PROCESS_DEFAULT_PRICE by process_code","field_type":"BASIC_DATA","basic_data_path":"process_default_cost.unit_price","global_variable_code":"PROCESS_DEFAULT_PRICE","content":"","is_amount":true,"is_subtotal":false},
  {"name":"成材率","notes":"用户输入百分比, 默认 100","field_type":"INPUT_NUMBER","content":"100","is_amount":false,"is_subtotal":false},
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

-- ── 2. 元素组件 ──────────────────────────────────────────────────────────

UPDATE component
SET fields = '[
  {"name":"序号","field_type":"BASIC_DATA","basic_data_path":"mat_bom[bom_type=''ELEMENT''].seq_no","content":"","is_amount":false,"is_subtotal":false},
  {"name":"元素","field_type":"BASIC_DATA","basic_data_path":"mat_bom[bom_type=''ELEMENT''].element_name","content":"","is_amount":false,"is_subtotal":false},
  {"name":"含量","notes":"百分比形式(75 = 75%); 公式中 / 100 转小数","field_type":"BASIC_DATA","basic_data_path":"mat_bom[bom_type=''ELEMENT''].composition_pct","content":"","is_amount":false,"is_subtotal":false},
  {"name":"单位","notes":"从元素价格表 ELEM_PRICE 动态查","field_type":"BASIC_DATA","basic_data_path":"v_costing_element_price.unit","global_variable_code":"ELEM_PRICE","content":"","is_amount":false,"is_subtotal":false},
  {"name":"单价","notes":"动态查 ELEM_PRICE by element_code","field_type":"BASIC_DATA","basic_data_path":"v_costing_element_price.costing_price","global_variable_code":"ELEM_PRICE","content":"","is_amount":true,"is_subtotal":false},
  {"name":"小计","notes":"= 含量(%) / 100 × 单价","field_type":"FORMULA","is_amount":true,"is_subtotal":true,"formula_tokens":[
    {"type":"field","value":"含量"},
    {"type":"operator","value":"/","label":"÷"},
    {"type":"number","value":"100"},
    {"type":"operator","value":"*","label":"×"},
    {"type":"field","value":"单价"}
  ]}
]'::jsonb,
    updated_at = NOW()
WHERE id = 'dae85db8-cf47-44df-890d-516625a598da';

-- ── 3. template.components_snapshot 同步 ─────────────────────────────────
-- 选配产品标准报价模板 b1d2e3f4 的 snapshot 是 array, 找到 COMP-CFG-PROCESS / COMP-CFG-ELEMENT-BOM
-- 这两个元素并替换其 fields. 用 jsonb_set 按数组下标更新.

DO $$
DECLARE
    v_snapshot JSONB;
    v_idx INT;
    v_comp_code TEXT;
    v_new_process_fields JSONB := (SELECT fields FROM component WHERE id = '0a436b6c-0b42-4381-a2da-7f901901968f');
    v_new_element_fields JSONB := (SELECT fields FROM component WHERE id = 'dae85db8-cf47-44df-890d-516625a598da');
BEGIN
    SELECT components_snapshot INTO v_snapshot
    FROM template WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163';

    IF v_snapshot IS NULL THEN
        RAISE NOTICE 'V182: template b1d2e3f4 无 components_snapshot, 跳过 snapshot 同步';
        RETURN;
    END IF;

    FOR v_idx IN 0 .. jsonb_array_length(v_snapshot) - 1 LOOP
        v_comp_code := v_snapshot -> v_idx ->> 'componentCode';
        IF v_comp_code IS NULL THEN
            v_comp_code := v_snapshot -> v_idx ->> 'component_code';
        END IF;

        IF v_comp_code = 'COMP-CFG-PROCESS' THEN
            v_snapshot := jsonb_set(v_snapshot, ARRAY[v_idx::text, 'fields'], v_new_process_fields);
        ELSIF v_comp_code = 'COMP-CFG-ELEMENT-BOM' THEN
            v_snapshot := jsonb_set(v_snapshot, ARRAY[v_idx::text, 'fields'], v_new_element_fields);
        END IF;
    END LOOP;

    UPDATE template
    SET components_snapshot = v_snapshot, updated_at = NOW()
    WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163';

    RAISE NOTICE 'V182: template b1d2e3f4 components_snapshot 已同步 COMP-CFG-PROCESS / COMP-CFG-ELEMENT-BOM';
END $$;

-- ── 4. 自检 ──────────────────────────────────────────────────────────────

DO $$
DECLARE
    v_process_has_chengcailv BOOLEAN;
    v_process_has_prev_token BOOLEAN;
    v_element_has_dpw BOOLEAN;
    v_snapshot_synced BOOLEAN;
BEGIN
    -- 注: jsonb::text 转换会在 key/value 之间插空格(如 "name": "X"), LIKE 模式同样含空格
    SELECT fields::text LIKE '%成材率%' INTO v_process_has_chengcailv
    FROM component WHERE id = '0a436b6c-0b42-4381-a2da-7f901901968f';
    SELECT fields::text LIKE '%previous_row_subtotal%' INTO v_process_has_prev_token
    FROM component WHERE id = '0a436b6c-0b42-4381-a2da-7f901901968f';
    SELECT fields::text LIKE '%"name": "单位"%' INTO v_element_has_dpw
    FROM component WHERE id = 'dae85db8-cf47-44df-890d-516625a598da';
    SELECT components_snapshot::text LIKE '%previous_row_subtotal%' INTO v_snapshot_synced
    FROM template WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163';

    IF NOT v_process_has_chengcailv OR NOT v_process_has_prev_token THEN
        RAISE EXCEPTION 'V182 自检失败: 工序组件未含成材率字段或 previous_row_subtotal token';
    END IF;
    IF NOT v_element_has_dpw THEN
        RAISE EXCEPTION 'V182 自检失败: 元素组件未含单位字段';
    END IF;
    IF NOT v_snapshot_synced THEN
        RAISE EXCEPTION 'V182 自检失败: template snapshot 未同步 previous_row_subtotal';
    END IF;
    RAISE NOTICE 'V182 自检通过: 工序累加公式 + 元素单位列均已配置, template snapshot 同步完成';
END $$;
