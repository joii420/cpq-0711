-- V98: 创建「核价模板组件」目录 + 14 个 NORMAL 组件 + 1 个 SUBTOTAL 总公式组件 + 1 个 COSTING 模板
--
-- 来源: data/template/核价系统功能基础数据功能结构所需字段（4.0版）.xlsx
--
-- 目的: 让核价员在报价单/核价单页面看到 14 个核价 sheet 对应的 14 个组件 tab,
--       手填或带入数据, 总公式组件自动汇总成 总成本(CNY/KG)。
--
-- 组件清单 (14 个 NORMAL):
--   COMP-V4-RAW-BOM           来料BOM
--   COMP-V4-ELEMENT-BOM       元素BOM (来料与元素 BOM)
--   COMP-V4-PROCESS-COST      工序成本(合并 4 类: 人工/折旧/生产能耗/辅助能耗)
--   COMP-V4-TOOLING           模具工装成本
--   COMP-V4-CONSUMABLE        耗材包装
--   COMP-V4-INCOMING-FEE      来料加工费
--   COMP-V4-INCOMING-OTHER    来料其他费用
--   COMP-V4-FINISHED-FEE      成品加工费&组装费
--   COMP-V4-FINISHED-OTHER    成品其他费用
--   COMP-V4-PLATING-SCHEME    电镀方案
--   COMP-V4-PLATING-COST      电镀成本
--   COMP-V4-OUTSOURCE         其他外加工成本
--   COMP-V4-WEIGHT            单重
--   COMP-V4-EXCHANGE-RATE     汇率
--
-- SUBTOTAL 1 个:
--   COMP-V4-TOTAL-CNY         总公式(CNY) — 引用上面 8 个核心成本组件的 subtotal 做总成本汇总
--
-- 模板 1 个 (COSTING, DRAFT):
--   核价-完整公式版-组件版 v1.0 — 16 个 tab (14 NORMAL + 1 SUBTOTAL + 占位料号属性)

-- ============================================================
-- Step 1: 创建「核价模板组件」目录
-- ============================================================
DO $$
DECLARE
    v_dir_id UUID;
BEGIN
    -- 防重复
    SELECT id INTO v_dir_id FROM component_directory WHERE name = '核价模板组件' AND parent_id IS NULL;
    IF v_dir_id IS NULL THEN
        v_dir_id := gen_random_uuid();
        INSERT INTO component_directory (id, parent_id, name, sort_order, created_at)
        VALUES (v_dir_id, NULL, '核价模板组件', 100, now());
        RAISE NOTICE 'V98: 创建目录「核价模板组件」 id=%', v_dir_id;
    ELSE
        RAISE NOTICE 'V98: 目录「核价模板组件」已存在 id=%, 复用', v_dir_id;
    END IF;

    -- 把 dir_id 缓存到临时表 (后面 INSERT 用)
    DROP TABLE IF EXISTS _tmp_v98_dir;
    CREATE TEMP TABLE _tmp_v98_dir AS SELECT v_dir_id AS id;
END $$;

-- ============================================================
-- Step 2: 14 个 NORMAL 组件 (sheet 对应)
-- ============================================================

-- ── 1. 来料BOM ──────────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status, component_type, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价-来料BOM', 'COMP-V4-RAW-BOM', 7,
$JSON$[
    {"name":"来料料号","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false,"notes":"v4 来料BOM 列 C"},
    {"name":"组成用量","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false,"notes":"v4 列 I; 边角料填负数"},
    {"name":"底数","field_type":"INPUT_NUMBER","content":"1","is_amount":false,"is_subtotal":false,"notes":"v4 列 K"},
    {"name":"来料损耗率(%)","field_type":"INPUT_NUMBER","content":"0","is_amount":false,"is_subtotal":false,"notes":"v4 列 M"},
    {"name":"不良率(%)","field_type":"INPUT_NUMBER","content":"0","is_amount":false,"is_subtotal":false,"notes":"v4 列 O"},
    {"name":"单价(CNY/KG)","field_type":"INPUT_NUMBER","content":"","is_amount":true,"is_subtotal":false,"notes":"从 元素核价/材料核价 带入或手填"},
    {"name":"行小计","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"= 组成用量÷底数×(1+不良率%/100)×单价"}
]$JSON$::jsonb,
$JSON$[
    {"name":"行小计","result_type":"AMOUNT","expression":[
        {"type":"field","label":"组成用量","value":"组成用量"},
        {"type":"operator","label":"÷","value":"/"},
        {"type":"field","label":"底数","value":"底数"},
        {"type":"operator","label":"×","value":"*"},
        {"type":"bracket_open","label":"(","value":"("},
        {"type":"number","label":"1","value":"1"},
        {"type":"operator","label":"+","value":"+"},
        {"type":"field","label":"不良率(%)","value":"不良率(%)"},
        {"type":"operator","label":"÷","value":"/"},
        {"type":"number","label":"100","value":"100"},
        {"type":"bracket_close","label":")","value":")"},
        {"type":"operator","label":"×","value":"*"},
        {"type":"field","label":"单价(CNY/KG)","value":"单价(CNY/KG)"}
    ]}
]$JSON$::jsonb,
'ACTIVE','NORMAL',now(),now()
FROM _tmp_v98_dir d
WHERE NOT EXISTS (SELECT 1 FROM component WHERE code = 'COMP-V4-RAW-BOM');

-- ── 2. 元素BOM ──────────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status, component_type, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价-元素BOM', 'COMP-V4-ELEMENT-BOM', 6,
$JSON$[
    {"name":"来料料号","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"元素代码","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"组成含量(%)","field_type":"INPUT_NUMBER","content":"0","is_amount":false,"is_subtotal":false},
    {"name":"损耗率(%)","field_type":"INPUT_NUMBER","content":"0","is_amount":false,"is_subtotal":false},
    {"name":"元素单价(CNY/KG)","field_type":"INPUT_NUMBER","content":"","is_amount":true,"is_subtotal":false},
    {"name":"行小计","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"= 含量÷100×元素价×(1+损耗率%/100)"}
]$JSON$::jsonb,
$JSON$[
    {"name":"行小计","result_type":"AMOUNT","expression":[
        {"type":"field","label":"组成含量(%)","value":"组成含量(%)"},
        {"type":"operator","label":"÷","value":"/"},
        {"type":"number","label":"100","value":"100"},
        {"type":"operator","label":"×","value":"*"},
        {"type":"field","label":"元素单价(CNY/KG)","value":"元素单价(CNY/KG)"},
        {"type":"operator","label":"×","value":"*"},
        {"type":"bracket_open","label":"(","value":"("},
        {"type":"number","label":"1","value":"1"},
        {"type":"operator","label":"+","value":"+"},
        {"type":"field","label":"损耗率(%)","value":"损耗率(%)"},
        {"type":"operator","label":"÷","value":"/"},
        {"type":"number","label":"100","value":"100"},
        {"type":"bracket_close","label":")","value":")"}
    ]}
]$JSON$::jsonb,
'ACTIVE','NORMAL',now(),now()
FROM _tmp_v98_dir d
WHERE NOT EXISTS (SELECT 1 FROM component WHERE code = 'COMP-V4-ELEMENT-BOM');

-- ── 3. 工序成本 (合并 4 类) ──────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status, component_type, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价-工序成本', 'COMP-V4-PROCESS-COST', 7,
$JSON$[
    {"name":"工序号","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"工序名称","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"人工标准单价","field_type":"INPUT_NUMBER","content":"0","is_amount":true,"is_subtotal":false,"notes":"v4 人工成本 sheet"},
    {"name":"折旧单价","field_type":"INPUT_NUMBER","content":"0","is_amount":true,"is_subtotal":false,"notes":"v4 设备折旧 sheet"},
    {"name":"生产能耗单价","field_type":"INPUT_NUMBER","content":"0","is_amount":true,"is_subtotal":false,"notes":"v4 生产设备能耗 sheet"},
    {"name":"辅助能耗单价","field_type":"INPUT_NUMBER","content":"0","is_amount":true,"is_subtotal":false,"notes":"v4 辅助设备能耗 sheet"},
    {"name":"工序加工费","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"= 4 项单价之和"}
]$JSON$::jsonb,
$JSON$[
    {"name":"工序加工费","result_type":"AMOUNT","expression":[
        {"type":"field","label":"人工标准单价","value":"人工标准单价"},
        {"type":"operator","label":"+","value":"+"},
        {"type":"field","label":"折旧单价","value":"折旧单价"},
        {"type":"operator","label":"+","value":"+"},
        {"type":"field","label":"生产能耗单价","value":"生产能耗单价"},
        {"type":"operator","label":"+","value":"+"},
        {"type":"field","label":"辅助能耗单价","value":"辅助能耗单价"}
    ]}
]$JSON$::jsonb,
'ACTIVE','NORMAL',now(),now()
FROM _tmp_v98_dir d
WHERE NOT EXISTS (SELECT 1 FROM component WHERE code = 'COMP-V4-PROCESS-COST');

-- ── 4. 模具工装成本 ─────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status, component_type, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价-模具工装成本', 'COMP-V4-TOOLING', 7,
$JSON$[
    {"name":"工序号","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"工艺次数","field_type":"INPUT_NUMBER","content":"1","is_amount":false,"is_subtotal":false,"notes":"项次"},
    {"name":"模具编号","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"单个模具成本","field_type":"INPUT_NUMBER","content":"0","is_amount":true,"is_subtotal":false},
    {"name":"寿命(次)","field_type":"INPUT_NUMBER","content":"1","is_amount":false,"is_subtotal":false},
    {"name":"单循环产量","field_type":"INPUT_NUMBER","content":"1","is_amount":false,"is_subtotal":false},
    {"name":"模具单价","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"= 单个成本÷寿命÷单循环产量"}
]$JSON$::jsonb,
$JSON$[
    {"name":"模具单价","result_type":"AMOUNT","expression":[
        {"type":"field","label":"单个模具成本","value":"单个模具成本"},
        {"type":"operator","label":"÷","value":"/"},
        {"type":"field","label":"寿命(次)","value":"寿命(次)"},
        {"type":"operator","label":"÷","value":"/"},
        {"type":"field","label":"单循环产量","value":"单循环产量"}
    ]}
]$JSON$::jsonb,
'ACTIVE','NORMAL',now(),now()
FROM _tmp_v98_dir d
WHERE NOT EXISTS (SELECT 1 FROM component WHERE code = 'COMP-V4-TOOLING');

-- ── 5. 耗材包装 ────────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status, component_type, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价-耗材包装', 'COMP-V4-CONSUMABLE', 3,
$JSON$[
    {"name":"工序号","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"工序名称","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"耗材成本单价","field_type":"INPUT_NUMBER","content":"0","is_amount":true,"is_subtotal":true,"notes":"按 PCS/KG 计"}
]$JSON$::jsonb,
'[]'::jsonb,
'ACTIVE','NORMAL',now(),now()
FROM _tmp_v98_dir d
WHERE NOT EXISTS (SELECT 1 FROM component WHERE code = 'COMP-V4-CONSUMABLE');

-- ── 6. 来料加工费 ───────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status, component_type, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价-来料加工费', 'COMP-V4-INCOMING-FEE', 3,
$JSON$[
    {"name":"项次","field_type":"INPUT_NUMBER","content":"1","is_amount":false,"is_subtotal":false},
    {"name":"来料料号","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"加工费","field_type":"INPUT_NUMBER","content":"0","is_amount":true,"is_subtotal":true,"notes":"v4 来料加工费 sheet 列 G; 内部生产料号通过来料工序加工费计算"}
]$JSON$::jsonb,
'[]'::jsonb,
'ACTIVE','NORMAL',now(),now()
FROM _tmp_v98_dir d
WHERE NOT EXISTS (SELECT 1 FROM component WHERE code = 'COMP-V4-INCOMING-FEE');

-- ── 7. 来料其他费用 ─────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status, component_type, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价-来料其他费用', 'COMP-V4-INCOMING-OTHER', 5,
$JSON$[
    {"name":"一级项次","field_type":"INPUT_NUMBER","content":"1","is_amount":false,"is_subtotal":false},
    {"name":"来料料号","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"二级项次","field_type":"INPUT_NUMBER","content":"1","is_amount":false,"is_subtotal":false},
    {"name":"要素名称","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false,"notes":"如管理费/财务费"},
    {"name":"比例(%)","field_type":"INPUT_NUMBER","content":"0","is_amount":false,"is_subtotal":true,"notes":"百分比, 如 0.8 表示 0.8%"}
]$JSON$::jsonb,
'[]'::jsonb,
'ACTIVE','NORMAL',now(),now()
FROM _tmp_v98_dir d
WHERE NOT EXISTS (SELECT 1 FROM component WHERE code = 'COMP-V4-INCOMING-OTHER');

-- ── 8. 成品加工费&组装费 ────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status, component_type, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价-成品加工费', 'COMP-V4-FINISHED-FEE', 5,
$JSON$[
    {"name":"工序号","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"工序名称","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"加工费","field_type":"INPUT_NUMBER","content":"0","is_amount":true,"is_subtotal":false},
    {"name":"不良率(%)","field_type":"INPUT_NUMBER","content":"0","is_amount":false,"is_subtotal":false},
    {"name":"行小计","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"= 加工费×(1+不良率%/100)"}
]$JSON$::jsonb,
$JSON$[
    {"name":"行小计","result_type":"AMOUNT","expression":[
        {"type":"field","label":"加工费","value":"加工费"},
        {"type":"operator","label":"×","value":"*"},
        {"type":"bracket_open","label":"(","value":"("},
        {"type":"number","label":"1","value":"1"},
        {"type":"operator","label":"+","value":"+"},
        {"type":"field","label":"不良率(%)","value":"不良率(%)"},
        {"type":"operator","label":"÷","value":"/"},
        {"type":"number","label":"100","value":"100"},
        {"type":"bracket_close","label":")","value":")"}
    ]}
]$JSON$::jsonb,
'ACTIVE','NORMAL',now(),now()
FROM _tmp_v98_dir d
WHERE NOT EXISTS (SELECT 1 FROM component WHERE code = 'COMP-V4-FINISHED-FEE');

-- ── 9. 成品其他费用 ─────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status, component_type, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价-成品其他费用', 'COMP-V4-FINISHED-OTHER', 3,
$JSON$[
    {"name":"项次","field_type":"INPUT_NUMBER","content":"1","is_amount":false,"is_subtotal":false},
    {"name":"要素名称","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false,"notes":"管理费/财务费/利润/税费"},
    {"name":"比例(%)","field_type":"INPUT_NUMBER","content":"0","is_amount":false,"is_subtotal":true,"notes":"4 行的比例%总和将供总公式调用 (1+总和/100)"}
]$JSON$::jsonb,
'[]'::jsonb,
'ACTIVE','NORMAL',now(),now()
FROM _tmp_v98_dir d
WHERE NOT EXISTS (SELECT 1 FROM component WHERE code = 'COMP-V4-FINISHED-OTHER');

-- ── 10. 电镀方案 ────────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status, component_type, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价-电镀方案', 'COMP-V4-PLATING-SCHEME', 7,
$JSON$[
    {"name":"方案编号","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"版本","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"项次","field_type":"INPUT_NUMBER","content":"1","is_amount":false,"is_subtotal":false},
    {"name":"电镀元素","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"电镀面积(cm²)","field_type":"INPUT_NUMBER","content":"0","is_amount":false,"is_subtotal":false},
    {"name":"镀层厚度(μm)","field_type":"INPUT_NUMBER","content":"0","is_amount":false,"is_subtotal":false},
    {"name":"密度(g/cm³)","field_type":"INPUT_NUMBER","content":"0","is_amount":false,"is_subtotal":true,"notes":"参考数据, subtotal 仅占位"}
]$JSON$::jsonb,
'[]'::jsonb,
'ACTIVE','NORMAL',now(),now()
FROM _tmp_v98_dir d
WHERE NOT EXISTS (SELECT 1 FROM component WHERE code = 'COMP-V4-PLATING-SCHEME');

-- ── 11. 电镀成本 ────────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status, component_type, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价-电镀成本', 'COMP-V4-PLATING-COST', 6,
$JSON$[
    {"name":"方案编号","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"版本","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"电镀加工费","field_type":"INPUT_NUMBER","content":"0","is_amount":true,"is_subtotal":false},
    {"name":"电镀材料费","field_type":"INPUT_NUMBER","content":"0","is_amount":true,"is_subtotal":false,"notes":"v4 行 89: ∑(各元素电镀重量×元素单价)"},
    {"name":"不良率(%)","field_type":"INPUT_NUMBER","content":"0","is_amount":false,"is_subtotal":false},
    {"name":"电镀成本","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"v4 行 90: =(加工费+材料费)×(1+不良率%/100)"}
]$JSON$::jsonb,
$JSON$[
    {"name":"电镀成本","result_type":"AMOUNT","expression":[
        {"type":"bracket_open","label":"(","value":"("},
        {"type":"field","label":"电镀加工费","value":"电镀加工费"},
        {"type":"operator","label":"+","value":"+"},
        {"type":"field","label":"电镀材料费","value":"电镀材料费"},
        {"type":"bracket_close","label":")","value":")"},
        {"type":"operator","label":"×","value":"*"},
        {"type":"bracket_open","label":"(","value":"("},
        {"type":"number","label":"1","value":"1"},
        {"type":"operator","label":"+","value":"+"},
        {"type":"field","label":"不良率(%)","value":"不良率(%)"},
        {"type":"operator","label":"÷","value":"/"},
        {"type":"number","label":"100","value":"100"},
        {"type":"bracket_close","label":")","value":")"}
    ]}
]$JSON$::jsonb,
'ACTIVE','NORMAL',now(),now()
FROM _tmp_v98_dir d
WHERE NOT EXISTS (SELECT 1 FROM component WHERE code = 'COMP-V4-PLATING-COST');

-- ── 12. 其他外加工成本 ──────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status, component_type, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价-其他外加工', 'COMP-V4-OUTSOURCE', 3,
$JSON$[
    {"name":"工序号","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false,"notes":"如委外焊接"},
    {"name":"工序名称","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"外加工费用","field_type":"INPUT_NUMBER","content":"0","is_amount":true,"is_subtotal":true}
]$JSON$::jsonb,
'[]'::jsonb,
'ACTIVE','NORMAL',now(),now()
FROM _tmp_v98_dir d
WHERE NOT EXISTS (SELECT 1 FROM component WHERE code = 'COMP-V4-OUTSOURCE');

-- ── 13. 单重 ────────────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status, component_type, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价-单重', 'COMP-V4-WEIGHT', 1,
$JSON$[
    {"name":"单重(g/pcs)","field_type":"INPUT_NUMBER","content":"0","is_amount":false,"is_subtotal":true}
]$JSON$::jsonb,
'[]'::jsonb,
'ACTIVE','NORMAL',now(),now()
FROM _tmp_v98_dir d
WHERE NOT EXISTS (SELECT 1 FROM component WHERE code = 'COMP-V4-WEIGHT');

-- ── 14. 汇率 ────────────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status, component_type, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价-汇率', 'COMP-V4-EXCHANGE-RATE', 3,
$JSON$[
    {"name":"基础货币","field_type":"FIXED_VALUE","content":"CNY","is_amount":false,"is_subtotal":false},
    {"name":"核价货币","field_type":"FIXED_VALUE","content":"USD","is_amount":false,"is_subtotal":false},
    {"name":"核价汇率","field_type":"INPUT_NUMBER","content":"0.138","is_amount":false,"is_subtotal":true,"notes":"v4 汇率管理表 默认 CNY→USD"}
]$JSON$::jsonb,
'[]'::jsonb,
'ACTIVE','NORMAL',now(),now()
FROM _tmp_v98_dir d
WHERE NOT EXISTS (SELECT 1 FROM component WHERE code = 'COMP-V4-EXCHANGE-RATE');

-- ============================================================
-- Step 3: 1 个 SUBTOTAL 总公式组件
--   总成本(CNY/KG) = (来料BOM小计 + 工序加工费 + 模具单价 + 耗材单价 + 来料加工费 +
--                    成品加工费 + 电镀成本 + 外加工费用)
--                  × (1 + 成品其他费用比例%总和 ÷ 100)
-- ============================================================
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status, component_type, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价-总公式(CNY)', 'COMP-V4-TOTAL-CNY', 0,
'[]'::jsonb,
$JSON$[
    {"name":"总成本(CNY/KG)","result_type":"AMOUNT","expression":[
        {"type":"bracket_open","label":"(","value":"("},
        {"type":"component_subtotal","label":"来料BOM·行小计","value":"行小计","tab_name":"行小计","component_code":"COMP-V4-RAW-BOM"},
        {"type":"operator","label":"+","value":"+"},
        {"type":"component_subtotal","label":"工序成本·工序加工费","value":"工序加工费","tab_name":"工序加工费","component_code":"COMP-V4-PROCESS-COST"},
        {"type":"operator","label":"+","value":"+"},
        {"type":"component_subtotal","label":"模具工装·模具单价","value":"模具单价","tab_name":"模具单价","component_code":"COMP-V4-TOOLING"},
        {"type":"operator","label":"+","value":"+"},
        {"type":"component_subtotal","label":"耗材包装·耗材成本单价","value":"耗材成本单价","tab_name":"耗材成本单价","component_code":"COMP-V4-CONSUMABLE"},
        {"type":"operator","label":"+","value":"+"},
        {"type":"component_subtotal","label":"来料加工费·加工费","value":"加工费","tab_name":"加工费","component_code":"COMP-V4-INCOMING-FEE"},
        {"type":"operator","label":"+","value":"+"},
        {"type":"component_subtotal","label":"成品加工费·行小计","value":"行小计","tab_name":"行小计","component_code":"COMP-V4-FINISHED-FEE"},
        {"type":"operator","label":"+","value":"+"},
        {"type":"component_subtotal","label":"电镀成本·电镀成本","value":"电镀成本","tab_name":"电镀成本","component_code":"COMP-V4-PLATING-COST"},
        {"type":"operator","label":"+","value":"+"},
        {"type":"component_subtotal","label":"其他外加工·外加工费用","value":"外加工费用","tab_name":"外加工费用","component_code":"COMP-V4-OUTSOURCE"},
        {"type":"bracket_close","label":")","value":")"},
        {"type":"operator","label":"×","value":"*"},
        {"type":"bracket_open","label":"(","value":"("},
        {"type":"number","label":"1","value":"1"},
        {"type":"operator","label":"+","value":"+"},
        {"type":"component_subtotal","label":"成品其他费用·比例(%)","value":"比例(%)","tab_name":"比例(%)","component_code":"COMP-V4-FINISHED-OTHER"},
        {"type":"operator","label":"÷","value":"/"},
        {"type":"number","label":"100","value":"100"},
        {"type":"bracket_close","label":")","value":")"}
    ]}
]$JSON$::jsonb,
'ACTIVE','SUBTOTAL',now(),now()
FROM _tmp_v98_dir d
WHERE NOT EXISTS (SELECT 1 FROM component WHERE code = 'COMP-V4-TOTAL-CNY');

-- ============================================================
-- Step 4: 创建核价模板「核价-完整公式版-组件版 v1.0」并绑定 15 个组件
-- ============================================================
DO $$
DECLARE
    v_template_id UUID;
    v_series_id   UUID := gen_random_uuid();
    v_default_cat UUID := 'b9576df8-24bf-42b7-b5a7-58bda3a023d2';
    -- component IDs (按 code 查)
    rec RECORD;
    v_sort INTEGER := 0;
BEGIN
    -- 防重复
    IF EXISTS (SELECT 1 FROM template WHERE name = '核价-完整公式版-组件版' AND template_kind = 'COSTING') THEN
        RAISE NOTICE 'V98: 模板「核价-完整公式版-组件版」已存在, 跳过';
        RETURN;
    END IF;

    v_template_id := gen_random_uuid();
    INSERT INTO template (
        id, template_series_id, name, version, category, category_id, customer_id,
        description, usage_note, product_attributes, subtotal_formula,
        components_snapshot, status, template_kind, created_at, updated_at
    ) VALUES (
        v_template_id, v_series_id, '核价-完整公式版-组件版', 'v1.0', NULL, v_default_cat, NULL,
        'V98 创建。基于 v4 Excel 全部 sheet 配置 14 个 NORMAL 组件 + 1 个 SUBTOTAL 总公式组件。' ||
        '在「核价模板组件」目录下。总公式 (CNY/KG) 引用 8 个核心成本组件的 subtotal + ' ||
        '成品其他费用比例总和。',
        '核价员在每个 tab 录入或带入数据, 总公式自动计算 (CNY/KG)。',
        '[]'::jsonb,
        '[]'::jsonb,
        NULL,
        'DRAFT',
        'COSTING',
        now(), now()
    );

    -- 按 code 顺序绑定 15 个组件
    FOR rec IN SELECT * FROM (VALUES
        ('COMP-V4-RAW-BOM',         '来料BOM'),
        ('COMP-V4-ELEMENT-BOM',     '元素BOM'),
        ('COMP-V4-PROCESS-COST',    '工序成本'),
        ('COMP-V4-TOOLING',         '模具工装'),
        ('COMP-V4-CONSUMABLE',      '耗材包装'),
        ('COMP-V4-INCOMING-FEE',    '来料加工费'),
        ('COMP-V4-INCOMING-OTHER',  '来料其他费用'),
        ('COMP-V4-FINISHED-FEE',    '成品加工费'),
        ('COMP-V4-FINISHED-OTHER',  '成品其他费用'),
        ('COMP-V4-PLATING-SCHEME',  '电镀方案'),
        ('COMP-V4-PLATING-COST',    '电镀成本'),
        ('COMP-V4-OUTSOURCE',       '其他外加工'),
        ('COMP-V4-WEIGHT',          '单重'),
        ('COMP-V4-EXCHANGE-RATE',   '汇率'),
        ('COMP-V4-TOTAL-CNY',       '总公式(CNY)')
    ) AS t(code, tab)
    LOOP
        INSERT INTO template_component (id, template_id, component_id, tab_name, sort_order, created_at)
        SELECT gen_random_uuid(), v_template_id, c.id, rec.tab, v_sort, now()
        FROM component c
        WHERE c.code = rec.code;
        v_sort := v_sort + 1;
    END LOOP;

    RAISE NOTICE 'V98: 已创建模板 id=%, 绑定 15 个组件', v_template_id;
END $$;

DROP TABLE IF EXISTS _tmp_v98_dir;
