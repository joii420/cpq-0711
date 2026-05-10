-- V116: 给客户「施耐德」配置一套完整的报价模板 (18 个 sheet 组件 + SUBTOTAL 总公式 + QUOTATION 模板)
--
-- 来源:
--   data/template/报价系统功能基础数据功能结构所需字段（1.0版）.xlsx 共 18 个 sheet
--
-- 设计:
--   - 组件目录「报价模板组件」(b3e77cf6 是核价模板组件, 这里新建独立目录)
--   - 18 个 sheet → 18 个 NORMAL 组件 (COMP-Q-*); 与核价 14 个组件对应的部分直接复用其 fields/formulas
--     (通过 SELECT FROM component 子查询拷贝, 保证公式同步), 4 个报价独有组件单独定义
--   - 1 个 SUBTOTAL 总公式组件 COMP-Q-TOTAL (报价单汇总, 与核价 TOTAL-CNY 同结构)
--   - 1 个 QUOTATION 业务模板「施耐德标准报价模板 v1.0」, 关联 19 个组件 + 客户施耐德
--
-- 数据来源映射 (sheet → 复用的核价组件):
--   元素单价              → 全局变量 ELEM_PRICE 已有, 不建组件 (作为公式取值, 非展示项)
--   客户料号关系          → 新建 COMP-Q-CUSTOMER-MAPPING (核价无)
--   来料BOM               → 复用 COMP-V4-RAW-BOM
--   元素BOM               → 复用 COMP-V4-ELEMENT-BOM
--   元素回收折扣          → 新建 COMP-Q-ELEMENT-RECYCLE (mat_bom 已有 element 行, 复用)
--   材料固定加工费        → 复用 COMP-V4-INCOMING-FEE
--   材料其他费用          → 复用 COMP-V4-INCOMING-OTHER
--   材料年降              → 新建 COMP-Q-MATERIAL-DECREASE (报价独有)
--   材料回收折扣          → 新建 COMP-Q-MATERIAL-RECYCLE
--   成品固定加工费        → 复用 COMP-V4-FINISHED-FEE
--   成品其他费用          → 复用 COMP-V4-FINISHED-OTHER
--   组成件BOM及零部件     → 复用 COMP-V4-TOOLING (tooling 表覆盖组成件)
--   组装加工费            → 复用 COMP-V4-FINISHED-FEE 同表 (assembly_process)
--   组装加工费年降        → 新建 COMP-Q-ASSEMBLY-DECREASE
--   产品电镀(方案)        → 复用 COMP-V4-PLATING-SCHEME
--   产品电镀(关联)        → 复用 COMP-V4-PLATING-COST
--   单重                  → 复用 COMP-V4-WEIGHT
--   年降系数              → 新建 COMP-Q-ANNUAL-DECREASE (产品级年降, 报价独有)

-- ════════════════════════════════════════════════════════════════
-- 1. 创建组件目录「报价模板组件」
-- ════════════════════════════════════════════════════════════════
INSERT INTO component_directory (id, name, parent_id, sort_order, created_at)
SELECT 'c1d2e3f4-0001-4001-8001-000000000001'::uuid, '报价模板组件', NULL, 80, now()
WHERE NOT EXISTS (
    SELECT 1 FROM component_directory WHERE id = 'c1d2e3f4-0001-4001-8001-000000000001'::uuid
       OR name = '报价模板组件'
);

-- ════════════════════════════════════════════════════════════════
-- 2. 13 个组件: 直接复用核价侧已配置的 fields / formulas / data_driver_path
-- ════════════════════════════════════════════════════════════════
INSERT INTO component (id, code, name, component_type, directory_id, fields, formulas, data_driver_path, status, column_count, created_at, updated_at)
SELECT
    gen_random_uuid(),
    map.q_code,
    map.q_name,
    'NORMAL',
    'c1d2e3f4-0001-4001-8001-000000000001'::uuid,
    c.fields,
    c.formulas,
    c.data_driver_path,
    'ACTIVE',
    jsonb_array_length(c.fields),
    now(),
    now()
FROM (VALUES
    ('COMP-V4-RAW-BOM',         'COMP-Q-RAW-BOM',           '来料BOM'),
    ('COMP-V4-ELEMENT-BOM',     'COMP-Q-ELEMENT-BOM',       '元素BOM'),
    ('COMP-V4-INCOMING-FEE',    'COMP-Q-MATERIAL-FEE',      '材料固定加工费'),
    ('COMP-V4-INCOMING-OTHER',  'COMP-Q-MATERIAL-OTHER',    '材料其他费用'),
    ('COMP-V4-FINISHED-FEE',    'COMP-Q-ASSEMBLY-FEE',      '组装加工费'),
    ('COMP-V4-FINISHED-OTHER',  'COMP-Q-FINISHED-OTHER',    '成品其他费用'),
    ('COMP-V4-TOOLING',         'COMP-Q-COMPONENT-BOM',     '组成件BOM及零部件'),
    ('COMP-V4-PLATING-SCHEME',  'COMP-Q-PLATING-SCHEME',    '产品电镀方案'),
    ('COMP-V4-PLATING-COST',    'COMP-Q-PLATING-COST',      '产品电镀成本'),
    ('COMP-V4-WEIGHT',          'COMP-Q-WEIGHT',            '单重'),
    ('COMP-V4-PROCESS-COST',    'COMP-Q-PROCESS-COST',      '工序成本'),
    ('COMP-V4-CONSUMABLE',      'COMP-Q-CONSUMABLE',        '耗材包装'),
    ('COMP-V4-OUTSOURCE',       'COMP-Q-OUTSOURCE',         '其他外加工')
) AS map(src_code, q_code, q_name)
JOIN component c ON c.code = map.src_code
ON CONFLICT (code) DO UPDATE SET
    fields           = EXCLUDED.fields,
    formulas         = EXCLUDED.formulas,
    data_driver_path = EXCLUDED.data_driver_path,
    column_count     = EXCLUDED.column_count,
    updated_at       = now();

-- ════════════════════════════════════════════════════════════════
-- 3. 5 个报价独有组件 (核价无对应)
-- ════════════════════════════════════════════════════════════════

-- 3.1 客户料号映射 (CUSTOMER-MAPPING)
INSERT INTO component (id, code, name, component_type, directory_id, fields, formulas, data_driver_path, status, column_count, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    'COMP-Q-CUSTOMER-MAPPING',
    '客户料号关系',
    'NORMAL',
    'c1d2e3f4-0001-4001-8001-000000000001'::uuid,
    $JSON$[
        {"name":"客户料号编码","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_customer_part_mapping.customer_part_no"},
        {"name":"客户产品编码","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_customer_part_mapping.customer_part_name"},
        {"name":"客户图号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_customer_part_mapping.customer_drawing_no"},
        {"name":"宏丰料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_customer_part_mapping.hf_part_no"},
        {"name":"贸易方式","field_type":"INPUT_TEXT","content":"全包","is_amount":false,"is_subtotal":false,"notes":"全包/半包/料号"},
        {"name":"基础币种","field_type":"INPUT_TEXT","content":"RMB","is_amount":false,"is_subtotal":false},
        {"name":"报价币种","field_type":"INPUT_TEXT","content":"USD","is_amount":false,"is_subtotal":false},
        {"name":"汇率","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_costing_exchange_rate.costing_rate","global_variable_code":"EXCHANGE_RATE","notes":"动态查表; ImplicitJoinRewriter 按基础/报价币种隐式 JOIN"}
    ]$JSON$::jsonb,
    '[]'::jsonb,
    'mat_customer_part_mapping',
    'ACTIVE', 8, now(), now())
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, formulas = EXCLUDED.formulas, data_driver_path = EXCLUDED.data_driver_path, updated_at = now();

-- 3.2 元素回收折扣 (ELEMENT-RECYCLE) — 来源 mat_bom 已有 ELEMENT 行的 defect_rate / 损耗
INSERT INTO component (id, code, name, component_type, directory_id, fields, formulas, data_driver_path, status, column_count, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    'COMP-Q-ELEMENT-RECYCLE',
    '元素回收折扣',
    'NORMAL',
    'c1d2e3f4-0001-4001-8001-000000000001'::uuid,
    $JSON$[
        {"name":"宏丰料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].hf_part_no"},
        {"name":"投料号名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].input_material_name"},
        {"name":"序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].seq_no"},
        {"name":"元素","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].element_name"},
        {"name":"回收折扣(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].defect_rate","notes":"DB 存小数, 公式中 ×(1-折扣)"}
    ]$JSON$::jsonb,
    '[]'::jsonb,
    'mat_bom[bom_type=''ELEMENT'']',
    'ACTIVE', 5, now(), now())
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, formulas = EXCLUDED.formulas, data_driver_path = EXCLUDED.data_driver_path, updated_at = now();

-- 3.3 材料年降 (MATERIAL-DECREASE) — 报价独有
INSERT INTO component (id, code, name, component_type, directory_id, fields, formulas, data_driver_path, status, column_count, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    'COMP-Q-MATERIAL-DECREASE',
    '材料年降',
    'NORMAL',
    'c1d2e3f4-0001-4001-8001-000000000001'::uuid,
    $JSON$[
        {"name":"序号","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false},
        {"name":"投料号","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
        {"name":"投料号名称","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
        {"name":"年降顺序","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false},
        {"name":"年降系数(%)","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false,"notes":"按年应用的降本系数"},
        {"name":"本次固定年降值","field_type":"INPUT_NUMBER","content":"","is_amount":true,"is_subtotal":false},
        {"name":"币种","field_type":"INPUT_TEXT","content":"RMB","is_amount":false,"is_subtotal":false},
        {"name":"计价单位","field_type":"INPUT_TEXT","content":"PCS","is_amount":false,"is_subtotal":false},
        {"name":"报价次数","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false}
    ]$JSON$::jsonb,
    '[]'::jsonb,
    NULL,
    'ACTIVE', 9, now(), now())
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, formulas = EXCLUDED.formulas, data_driver_path = EXCLUDED.data_driver_path, updated_at = now();

-- 3.4 材料回收折扣 (MATERIAL-RECYCLE) — 单材料级回收
INSERT INTO component (id, code, name, component_type, directory_id, fields, formulas, data_driver_path, status, column_count, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    'COMP-Q-MATERIAL-RECYCLE',
    '材料回收折扣',
    'NORMAL',
    'c1d2e3f4-0001-4001-8001-000000000001'::uuid,
    $JSON$[
        {"name":"宏丰料号","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
        {"name":"序号","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false},
        {"name":"投料号","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
        {"name":"投料号名称","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
        {"name":"回收折扣(%)","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false}
    ]$JSON$::jsonb,
    '[]'::jsonb,
    NULL,
    'ACTIVE', 5, now(), now())
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, formulas = EXCLUDED.formulas, data_driver_path = EXCLUDED.data_driver_path, updated_at = now();

-- 3.5 组装加工费年降 (ASSEMBLY-DECREASE)
INSERT INTO component (id, code, name, component_type, directory_id, fields, formulas, data_driver_path, status, column_count, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    'COMP-Q-ASSEMBLY-DECREASE',
    '组装加工费年降',
    'NORMAL',
    'c1d2e3f4-0001-4001-8001-000000000001'::uuid,
    $JSON$[
        {"name":"序号","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false},
        {"name":"装配类型","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false,"notes":"焊接/铆接 等"},
        {"name":"年降顺序","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false},
        {"name":"年降系数(%)","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false},
        {"name":"本次固定年降值","field_type":"INPUT_NUMBER","content":"","is_amount":true,"is_subtotal":false},
        {"name":"币种","field_type":"INPUT_TEXT","content":"RMB","is_amount":false,"is_subtotal":false},
        {"name":"计价单位","field_type":"INPUT_TEXT","content":"PCS","is_amount":false,"is_subtotal":false},
        {"name":"报价次数","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false}
    ]$JSON$::jsonb,
    '[]'::jsonb,
    NULL,
    'ACTIVE', 8, now(), now())
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, formulas = EXCLUDED.formulas, data_driver_path = EXCLUDED.data_driver_path, updated_at = now();

-- 3.6 年降系数 (ANNUAL-DECREASE) — 产品级年降
INSERT INTO component (id, code, name, component_type, directory_id, fields, formulas, data_driver_path, status, column_count, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    'COMP-Q-ANNUAL-DECREASE',
    '年降系数',
    'NORMAL',
    'c1d2e3f4-0001-4001-8001-000000000001'::uuid,
    $JSON$[
        {"name":"宏丰料号","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
        {"name":"年降顺序","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false},
        {"name":"年降系数(%/年)","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false},
        {"name":"本次固定年降金额","field_type":"INPUT_NUMBER","content":"","is_amount":true,"is_subtotal":false},
        {"name":"币种","field_type":"INPUT_TEXT","content":"RMB","is_amount":false,"is_subtotal":false},
        {"name":"计价单位","field_type":"INPUT_TEXT","content":"PCS","is_amount":false,"is_subtotal":false},
        {"name":"报价次数","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false}
    ]$JSON$::jsonb,
    '[]'::jsonb,
    NULL,
    'ACTIVE', 7, now(), now())
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, formulas = EXCLUDED.formulas, data_driver_path = EXCLUDED.data_driver_path, updated_at = now();

-- ════════════════════════════════════════════════════════════════
-- 4. SUBTOTAL 总公式组件 COMP-Q-TOTAL
--    引用 13 个核价基础组件 + 4 个报价独有组件的小计
-- ════════════════════════════════════════════════════════════════
INSERT INTO component (id, code, name, component_type, directory_id, fields, formulas, data_driver_path, status, column_count, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    'COMP-Q-TOTAL',
    '报价单总成本',
    'SUBTOTAL',
    'c1d2e3f4-0001-4001-8001-000000000001'::uuid,
    $JSON$[
        {"name":"报价总成本","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"= 各组件小计累加 - 年降扣减"}
    ]$JSON$::jsonb,
    $JSON$[{
        "name":"报价总成本","result_type":"AMOUNT","expression":[
            {"type":"component_subtotal","label":"来料BOM·行小计","value":"行小计","tab_name":"行小计","component_code":"COMP-Q-RAW-BOM"},
            {"type":"operator","label":"+","value":"+"},
            {"type":"component_subtotal","label":"工序成本·工序加工费","value":"工序加工费","tab_name":"工序加工费","component_code":"COMP-Q-PROCESS-COST"},
            {"type":"operator","label":"+","value":"+"},
            {"type":"component_subtotal","label":"组成件BOM·模具单价","value":"模具单价","tab_name":"模具单价","component_code":"COMP-Q-COMPONENT-BOM"},
            {"type":"operator","label":"+","value":"+"},
            {"type":"component_subtotal","label":"耗材包装·耗材成本单价","value":"耗材成本单价","tab_name":"耗材成本单价","component_code":"COMP-Q-CONSUMABLE"},
            {"type":"operator","label":"+","value":"+"},
            {"type":"component_subtotal","label":"材料加工费","value":"加工费","tab_name":"加工费","component_code":"COMP-Q-MATERIAL-FEE"},
            {"type":"operator","label":"+","value":"+"},
            {"type":"component_subtotal","label":"组装加工费·行小计","value":"行小计","tab_name":"行小计","component_code":"COMP-Q-ASSEMBLY-FEE"},
            {"type":"operator","label":"+","value":"+"},
            {"type":"component_subtotal","label":"电镀成本·电镀成本","value":"电镀成本","tab_name":"电镀成本","component_code":"COMP-Q-PLATING-COST"},
            {"type":"operator","label":"+","value":"+"},
            {"type":"component_subtotal","label":"其他外加工","value":"外加工费用","tab_name":"外加工费用","component_code":"COMP-Q-OUTSOURCE"}
        ]
    }]$JSON$::jsonb,
    NULL,
    'ACTIVE', 1, now(), now())
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, formulas = EXCLUDED.formulas, updated_at = now();

-- ════════════════════════════════════════════════════════════════
-- 5. QUOTATION 业务模板「施耐德标准报价模板 v1.0」
-- ════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_tpl_id UUID := gen_random_uuid();
    v_series UUID := gen_random_uuid();   -- 新 series 区别于「施耐德专属1」
    v_dir_id UUID := 'c1d2e3f4-0001-4001-8001-000000000001'::uuid;
    v_sort INT := 0;
    v_comp RECORD;
BEGIN
    -- 已存在则跳过
    IF EXISTS (SELECT 1 FROM template WHERE name = '施耐德标准报价模板' AND template_kind = 'QUOTATION'
               AND customer_id = '8de8f8b0-041c-4af1-aeb5-139fbb5484ca' AND status = 'PUBLISHED') THEN
        RAISE NOTICE 'V116: 模板已存在, 跳过创建';
        RETURN;
    END IF;

    INSERT INTO template (id, template_series_id, name, version, status, template_kind, customer_id, description, created_at, updated_at, published_at)
    VALUES (
        v_tpl_id,
        v_series,
        '施耐德标准报价模板',
        'v1.0',
        'PUBLISHED',
        'QUOTATION',
        '8de8f8b0-041c-4af1-aeb5-139fbb5484ca',
        'V116 创建. 基于报价系统功能基础数据 (1.0版) 18 sheet 配置 18 NORMAL 组件 + 1 SUBTOTAL 总公式组件; 关联客户施耐德, 已发布.',
        now(), now(), now()
    );

    -- 关联 18 个 NORMAL 组件 + 1 SUBTOTAL, 按业务顺序
    FOR v_comp IN
        SELECT c.id, c.name FROM component c, (VALUES
            ('COMP-Q-CUSTOMER-MAPPING', 1),
            ('COMP-Q-RAW-BOM',           2),
            ('COMP-Q-ELEMENT-BOM',       3),
            ('COMP-Q-ELEMENT-RECYCLE',   4),
            ('COMP-Q-MATERIAL-FEE',      5),
            ('COMP-Q-MATERIAL-OTHER',    6),
            ('COMP-Q-MATERIAL-DECREASE', 7),
            ('COMP-Q-MATERIAL-RECYCLE',  8),
            ('COMP-Q-PROCESS-COST',      9),
            ('COMP-Q-COMPONENT-BOM',    10),
            ('COMP-Q-CONSUMABLE',       11),
            ('COMP-Q-ASSEMBLY-FEE',     12),
            ('COMP-Q-ASSEMBLY-DECREASE',13),
            ('COMP-Q-FINISHED-OTHER',   14),
            ('COMP-Q-PLATING-SCHEME',   15),
            ('COMP-Q-PLATING-COST',     16),
            ('COMP-Q-OUTSOURCE',        17),
            ('COMP-Q-WEIGHT',           18),
            ('COMP-Q-ANNUAL-DECREASE',  19),
            ('COMP-Q-TOTAL',            20)
        ) AS ord(code, sort_idx)
        WHERE c.code = ord.code
        ORDER BY ord.sort_idx
    LOOP
        INSERT INTO template_component (id, template_id, component_id, tab_name, sort_order, created_at)
        VALUES (gen_random_uuid(), v_tpl_id, v_comp.id, v_comp.name, v_sort, now());
        v_sort := v_sort + 1;
    END LOOP;

    -- 写 components_snapshot (publish 时冻结)
    UPDATE template
    SET components_snapshot = (
        SELECT jsonb_agg(jsonb_build_object(
            'id', tc.id,
            'componentId', tc.component_id,
            'tabName', tc.tab_name,
            'sortOrder', tc.sort_order,
            'componentCode', c.code,
            'componentName', c.name,
            'componentType', c.component_type,
            'data_driver_path', c.data_driver_path,
            'fields', c.fields,
            'formulas', c.formulas
        ) ORDER BY tc.sort_order)
        FROM template_component tc
        JOIN component c ON c.id = tc.component_id
        WHERE tc.template_id = v_tpl_id
    ),
    updated_at = now()
    WHERE id = v_tpl_id;

    RAISE NOTICE 'V116: 已创建模板 % 关联 % 个组件', v_tpl_id, v_sort;
END $$;
