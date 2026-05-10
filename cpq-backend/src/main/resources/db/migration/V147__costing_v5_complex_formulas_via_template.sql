-- ============================================================
-- V147: Stage 4 — 复杂聚合公式 (纯材料成本 / 回收成本 / 材料损耗成本)
--
-- 目标:
--   1. 创建 v_c_raw_bom_priced 视图：来料BOM × 元素BOM × 元素价 × 材料价 四表合并，
--      每行含 unit_price / unit_price_recycle / bom_kind / elem_loss_rate / fixed_loss_qty 等，
--      供 SUM_OVER 聚合行内公式直接引用。
--
--   2. 注册组件 COMP-V5-RAW-BOM-PRICED（data_driver_path = v_c_raw_bom_priced）。
--      status=ACTIVE, component_type=NORMAL。
--      作为公式底层数据源（隐藏 Tab 用途）。
--
--   3. UPDATE template.formulas（模板 77decd71-c6cd-498a-9d8d-f47adfb024da）：
--      - 新增 "纯材料成本" / "回收成本" 两条 SUM_OVER 公式（V146 原用 col_key fallback）
--      - 替换 "材料损耗成本"（原 "[C_LOSS]" 占位）为真实 SUM_OVER 表达式
--      - 更新 "材料成本" 依赖：[B_PURE]/[B_RECYCLE] → [纯材料成本]/[回收成本]
--      总公式数: 15 条（V146 13 条 + 新增纯材料成本/回收成本 = 15，材料损耗成本原地替换）
--
--   4. V111 SQL 视图 v_costing_summary_full 保留不动（fallback 兜底）。
--
-- 字段单位对齐 V111 bom_expanded（小数）:
--   loss_rate         : 小数 e.g. 0.003  (V142 v_c_raw_bom_merged 已 ×100 显示%，此视图直接取底层表)
--   elem_pct_decimal  : 小数 e.g. 0.9978 (composition_pct 底层表存小数)
--   elem_loss_rate    : 小数
--   elem_discount     : 小数
--   mat_discount      : 小数
--
-- V111 三大聚合公式对照（所有系数已 /100 还原为小数）:
--   pure_material_cost  = SUM WHEN input_qty>=0: ABS(input_qty)/NULLIF(output_qty,0)*(1+loss_rate)*unit_price
--   recycle_cost        = SUM WHEN input_qty<0 : ABS(input_qty)/NULLIF(output_qty,0)*unit_price_recycle
--      unit_price_recycle = elem_price*elem_pct_decimal*elem_discount
--                         + mat_price*mat_discount*(element_code IS NULL ? 1 : 0)
--   material_loss_cost  = SUM WHEN input_qty>=0: ABS(input_qty)/NULLIF(output_qty,0)*(1+loss_rate)*elem_loss_rate*unit_price
--                                              + fixed_loss_qty*unit_price
-- ============================================================

-- ════════════════════════════════════════════════════════════════════════════
-- A. 创建 v_c_raw_bom_priced 视图
--    不 CASCADE（v_costing_summary_full 有独立数据来源，不依赖此视图）
-- ════════════════════════════════════════════════════════════════════════════

DROP VIEW IF EXISTS v_c_raw_bom_priced;

CREATE VIEW v_c_raw_bom_priced AS
SELECT
    m.hf_part_no,
    m.seq_no,
    m.input_material_no,
    m.process_no,
    m.process_name,
    m.input_qty,
    m.output_qty,
    COALESCE(m.loss_rate, 0)         AS loss_rate,           -- 小数 (与 V111 bom_expanded 单位一致)
    COALESCE(m.fixed_loss_qty, 0)    AS fixed_loss_qty,
    eb.element_code,
    COALESCE(eb.composition_pct, 0)  AS elem_pct_decimal,    -- 小数
    COALESCE(eb.loss_rate, 0)        AS elem_loss_rate,       -- 小数
    COALESCE(cep.costing_price, 0)   AS elem_price,
    COALESCE(cep.discount_rate, 0)   AS elem_discount,        -- 小数
    COALESCE(cmp.costing_price, 0)   AS mat_price,
    COALESCE(cmp.discount_rate, 0)   AS mat_discount,         -- 小数
    -- unit_price: 有元素分布时 = elem_price * elem_pct_decimal；纯材料时 = mat_price
    (CASE WHEN eb.element_code IS NULL
          THEN COALESCE(cmp.costing_price, 0)
          ELSE COALESCE(cep.costing_price, 0) * COALESCE(eb.composition_pct, 0)
     END) AS unit_price,
    -- unit_price_recycle（回收单价，含折扣）
    (CASE WHEN eb.element_code IS NULL
          THEN COALESCE(cmp.costing_price, 0) * COALESCE(cmp.discount_rate, 0)
          ELSE COALESCE(cep.costing_price, 0) * COALESCE(eb.composition_pct, 0)
               * COALESCE(cep.discount_rate, 0)
     END) AS unit_price_recycle,
    -- bom_kind 供 SUM_OVER WHERE 谓词过滤
    (CASE WHEN m.input_qty >= 0 THEN 'NORMAL' ELSE 'RECYCLE' END) AS bom_kind
FROM costing_part_material_bom m
LEFT JOIN costing_part_element_bom eb
    ON eb.input_material_no = m.input_material_no
   AND COALESCE(eb.is_active, true) = true
LEFT JOIN v_costing_element_price cep
    ON cep.element_code = eb.element_code
LEFT JOIN v_costing_material_price cmp
    ON cmp.material_no = m.input_material_no
WHERE COALESCE(m.is_active, true) = true;

COMMENT ON VIEW v_c_raw_bom_priced IS
    'V147: 来料BOM × 元素BOM × 元素价 × 材料价 四表合并展开视图。'
    '每行含 unit_price / unit_price_recycle / bom_kind / elem_loss_rate / fixed_loss_qty。'
    '专供 SUM_OVER 模板公式聚合（纯材料成本/回收成本/材料损耗成本）。'
    '所有比例/损耗字段均为小数（与 V111 bom_expanded 单位一致，不含 ×100 转换）。';

-- ════════════════════════════════════════════════════════════════════════════
-- B. 注册组件 COMP-V5-RAW-BOM-PRICED
--    幂等：ON CONFLICT (code) DO UPDATE
-- ════════════════════════════════════════════════════════════════════════════

INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT
    gen_random_uuid(),
    d.id,
    '核价V5-来料BOM含定价',
    'COMP-V5-RAW-BOM-PRICED',
    10,
    $JSON$[
      {"name":"序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_priced.seq_no"},
      {"name":"来料料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_priced.input_material_no"},
      {"name":"输入数量","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_priced.input_qty"},
      {"name":"产出数量","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_priced.output_qty"},
      {"name":"损耗率(小数)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_priced.loss_rate"},
      {"name":"固定损耗量","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_priced.fixed_loss_qty"},
      {"name":"元素代码","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_priced.element_code"},
      {"name":"元素含量(小数)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_priced.elem_pct_decimal"},
      {"name":"元素损耗率(小数)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_priced.elem_loss_rate"},
      {"name":"投料单价","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":true,"basic_data_path":"v_c_raw_bom_priced.unit_price"}
    ]$JSON$::jsonb,
    '[]'::jsonb,
    'ACTIVE',
    'NORMAL',
    'v_c_raw_bom_priced',
    now(),
    now()
FROM component_directory d
WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE
    SET fields           = EXCLUDED.fields,
        data_driver_path = EXCLUDED.data_driver_path,
        column_count     = EXCLUDED.column_count,
        updated_at       = now();

DO $$
DECLARE v_cnt INT;
BEGIN
    SELECT COUNT(*) INTO v_cnt FROM component WHERE code = 'COMP-V5-RAW-BOM-PRICED';
    IF v_cnt = 0 THEN
        RAISE EXCEPTION 'V147 B FAIL: COMP-V5-RAW-BOM-PRICED 注册失败（component_directory 可能不存在）';
    END IF;
    RAISE NOTICE 'V147 B OK: COMP-V5-RAW-BOM-PRICED 组件已注册 (data_driver_path=v_c_raw_bom_priced)';
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- C. UPDATE template.formulas — 完整重建为 15 条
--
-- 变更说明（对比 V146 13 条）：
--   + 新增 "纯材料成本" (SUM_OVER WHERE input_qty > 0)
--   + 新增 "回收成本"   (SUM_OVER WHERE input_qty < 0)
--   ~ 替换 "材料损耗成本" expression: "[C_LOSS]" → SUM_OVER 真实聚合
--   ~ 修改 "材料成本" expression: [B_PURE]/[B_RECYCLE] → [纯材料成本]/[回收成本]
--   = 其余 10 条不变
--
-- 拓扑顺序（被依赖在前）:
--   纯材料成本, 回收成本, 材料损耗成本  (SUM_OVER 叶节点)
--   材料成本 (依赖 纯材料成本, 回收成本, B_PROC/B_OTHER/B_FIX col_key fallback)
--   包装材料费, 加工费, 电镀成本, 其他外加工成本  (col_key fallback 叶节点)
--   加价基数  (依赖 材料成本/材料损耗成本/包装材料费/加工费/电镀成本/其他外加工成本)
--   管理费, 财务费, 利润, 税费  (依赖 加价基数)
--   总成本(CNY/KG)  (依赖以上所有)
--   总成本(USD/PCS) (依赖 总成本(CNY/KG))
-- ════════════════════════════════════════════════════════════════════════════

DO $$
DECLARE
    v_template_id  UUID := '77decd71-c6cd-498a-9d8d-f47adfb024da'::uuid;
    v_status       TEXT;
    v_new_formulas JSONB;
BEGIN
    SELECT status INTO v_status FROM template WHERE id = v_template_id;
    IF v_status IS NULL THEN
        RAISE EXCEPTION 'V147: template % 不存在', v_template_id;
    END IF;
    RAISE NOTICE 'V147 C: 更新 template % (status=%) formulas → 15 条', v_template_id, v_status;

    v_new_formulas := $FORMULAS$[
  {
    "name": "纯材料成本",
    "expression": "SUM_OVER([COMP-V5-RAW-BOM-PRICED] WHERE input_qty > 0, ABS(input_qty)/NULLIF(output_qty,0)*(1+loss_rate)*unit_price)",
    "data_type": "DECIMAL(18,4)",
    "depends_on": ["COMP-V5-RAW-BOM-PRICED"],
    "description": "纯材料成本 B_PURE = SUM(NORMAL行: |iq|/oq*(1+loss_rate)*unit_price)。对标 V111 material_aggs.pure_material_cost。loss_rate/unit_price 均为小数单位。"
  },
  {
    "name": "回收成本",
    "expression": "SUM_OVER([COMP-V5-RAW-BOM-PRICED] WHERE input_qty < 0, ABS(input_qty)/NULLIF(output_qty,0)*unit_price_recycle)",
    "data_type": "DECIMAL(18,4)",
    "depends_on": ["COMP-V5-RAW-BOM-PRICED"],
    "description": "回收成本 B_RECYCLE = SUM(RECYCLE行: |iq|/oq*unit_price_recycle)。unit_price_recycle 含折扣。对标 V111 material_aggs.recycle_cost。"
  },
  {
    "name": "材料损耗成本",
    "expression": "SUM_OVER([COMP-V5-RAW-BOM-PRICED] WHERE input_qty > 0, ABS(input_qty)/NULLIF(output_qty,0)*(1+loss_rate)*elem_loss_rate*unit_price+fixed_loss_qty*unit_price)",
    "data_type": "DECIMAL(18,4)",
    "depends_on": ["COMP-V5-RAW-BOM-PRICED"],
    "description": "材料损耗成本 C = SUM(NORMAL行: |iq|/oq*(1+lr)*elem_loss_rate*单价 + fixed_loss_qty*单价)。对标 V111 material_aggs.material_loss_cost。"
  },
  {
    "name": "材料成本",
    "expression": "[纯材料成本]+[B_PROC]+[B_OTHER]+[B_FIX]-[回收成本]",
    "data_type": "DECIMAL(18,4)",
    "depends_on": ["纯材料成本","回收成本","B_PROC","B_OTHER","B_FIX"],
    "description": "材料成本 B = 纯材料成本+来料加工费+来料其他比例费+来料固定费-回收成本。B_PROC/B_OTHER/B_FIX 走 col_key fallback 取 v_costing_summary_full。"
  },
  {
    "name": "包装材料费",
    "expression": "[D_PKG]",
    "data_type": "DECIMAL(18,4)",
    "depends_on": ["D_PKG"],
    "description": "包装材料费 D = v_c_summary_agg.packaging_fee (CONSUMABLE 中包装工序费合计)"
  },
  {
    "name": "加工费",
    "expression": "[E_PROC]",
    "data_type": "DECIMAL(18,4)",
    "depends_on": ["E_PROC"],
    "description": "加工费 E = v_costing_summary_full.process_fee_total"
  },
  {
    "name": "电镀成本",
    "expression": "([L_EPROC]+[L_EMAT])*(1+[L_EDEF])",
    "data_type": "DECIMAL(18,4)",
    "depends_on": ["L_EPROC","L_EMAT","L_EDEF"],
    "description": "电镀成本 L = (电镀加工费 + 电镀材料费) * (1 + 不良率)"
  },
  {
    "name": "其他外加工成本",
    "expression": "[M_OUT]",
    "data_type": "DECIMAL(18,4)",
    "depends_on": ["M_OUT"],
    "description": "其他外加工成本 M = v_c_summary_agg.outsource_fee (POST_PROC 工序费合计)"
  },
  {
    "name": "加价基数",
    "expression": "[材料成本]+[材料损耗成本]+[包装材料费]+[加工费]+[电镀成本]+[其他外加工成本]",
    "data_type": "DECIMAL(18,4)",
    "depends_on": ["材料成本","材料损耗成本","包装材料费","加工费","电镀成本","其他外加工成本"],
    "description": "加价基数 BASE = B+C+D+E+L+M，管理/财务/利润/税费的计算基数"
  },
  {
    "name": "管理费",
    "expression": "[加价基数]*[F_MGMT]",
    "data_type": "DECIMAL(18,4)",
    "depends_on": ["加价基数","F_MGMT"],
    "description": "管理费 F = 加价基数 x 管理费比例 (v_costing_summary_full.mgmt_fee_ratio)"
  },
  {
    "name": "财务费",
    "expression": "[加价基数]*[G_FIN]",
    "data_type": "DECIMAL(18,4)",
    "depends_on": ["加价基数","G_FIN"],
    "description": "财务费 G = 加价基数 x 财务费比例 (v_costing_summary_full.finance_fee_ratio)"
  },
  {
    "name": "利润",
    "expression": "[加价基数]*[H_PROFIT]",
    "data_type": "DECIMAL(18,4)",
    "depends_on": ["加价基数","H_PROFIT"],
    "description": "利润 H = 加价基数 x 利润比例 (v_costing_summary_full.profit_ratio)"
  },
  {
    "name": "税费",
    "expression": "[加价基数]*[I_TAX]",
    "data_type": "DECIMAL(18,4)",
    "depends_on": ["加价基数","I_TAX"],
    "description": "税费 I = 加价基数 x 税费比例 (v_costing_summary_full.tax_ratio)"
  },
  {
    "name": "总成本(CNY/KG)",
    "expression": "[材料成本]+[材料损耗成本]+[包装材料费]+[加工费]+[管理费]+[财务费]+[利润]+[税费]+[J]+[K]+[电镀成本]+[其他外加工成本]",
    "data_type": "DECIMAL(18,4)",
    "depends_on": ["材料成本","材料损耗成本","包装材料费","加工费","管理费","财务费","利润","税费","J","K","电镀成本","其他外加工成本"],
    "description": "总成本(CNY/KG) N = B+C+D+E+F+G+H+I+J+K+L+M，J/K 走 col_key fallback 取运费/清关费"
  },
  {
    "name": "总成本(USD/PCS)",
    "expression": "[总成本(CNY/KG)]/1000*[Q_WT]*[Q_RATE]",
    "data_type": "DECIMAL(18,8)",
    "depends_on": ["总成本(CNY/KG)","Q_WT","Q_RATE"],
    "description": "总成本(USD/PCS) Q = N / 1000 * 单重(g/pcs) * 汇率(CNY->USD)"
  }
]$FORMULAS$::jsonb;

    UPDATE template
    SET formulas   = v_new_formulas,
        updated_at = now()
    WHERE id = v_template_id;

    RAISE NOTICE 'V147 C OK: template % formulas 已更新为 % 条（3 条 SUM_OVER + 12 条原有）',
        v_template_id, jsonb_array_length(v_new_formulas);
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- D. 验证
-- ════════════════════════════════════════════════════════════════════════════

DO $$
DECLARE
    v_template_id   UUID := '77decd71-c6cd-498a-9d8d-f47adfb024da'::uuid;
    v_formula_count INT;
    v_has_pure      BOOLEAN;
    v_has_recycle   BOOLEAN;
    v_has_loss      BOOLEAN;
    v_pure_expr     TEXT;
    v_recycle_expr  TEXT;
    v_loss_expr     TEXT;
    v_view_exists   BOOLEAN;
    v_comp_exists   BOOLEAN;
BEGIN
    -- 1. 视图存在
    SELECT EXISTS (
        SELECT 1 FROM pg_views WHERE viewname = 'v_c_raw_bom_priced'
    ) INTO v_view_exists;
    IF v_view_exists THEN
        RAISE NOTICE 'V147 CHECK-1 OK: 视图 v_c_raw_bom_priced 已创建';
    ELSE
        RAISE WARNING 'V147 CHECK-1 FAIL: 视图 v_c_raw_bom_priced 不存在';
    END IF;

    -- 2. 组件存在
    SELECT EXISTS (
        SELECT 1 FROM component WHERE code = 'COMP-V5-RAW-BOM-PRICED'
    ) INTO v_comp_exists;
    IF v_comp_exists THEN
        RAISE NOTICE 'V147 CHECK-2 OK: 组件 COMP-V5-RAW-BOM-PRICED 已注册';
    ELSE
        RAISE WARNING 'V147 CHECK-2 FAIL: 组件 COMP-V5-RAW-BOM-PRICED 未找到';
    END IF;

    -- 3. 公式条数 >= 15
    SELECT jsonb_array_length(formulas)
    INTO v_formula_count
    FROM template WHERE id = v_template_id;
    IF COALESCE(v_formula_count, 0) >= 15 THEN
        RAISE NOTICE 'V147 CHECK-3 OK: formulas 条数=%', v_formula_count;
    ELSE
        RAISE WARNING 'V147 CHECK-3 WARN: formulas 条数=%, 期望 >= 15', v_formula_count;
    END IF;

    -- 4. 三条 SUM_OVER 公式存在且表达式含 SUM_OVER
    SELECT
        BOOL_OR(f->>'name' = '纯材料成本'),
        BOOL_OR(f->>'name' = '回收成本'),
        BOOL_OR(f->>'name' = '材料损耗成本')
    INTO v_has_pure, v_has_recycle, v_has_loss
    FROM template, jsonb_array_elements(formulas) f
    WHERE id = v_template_id;

    SELECT f->>'expression' INTO v_pure_expr
    FROM template, jsonb_array_elements(formulas) f
    WHERE id = v_template_id AND f->>'name' = '纯材料成本';

    SELECT f->>'expression' INTO v_recycle_expr
    FROM template, jsonb_array_elements(formulas) f
    WHERE id = v_template_id AND f->>'name' = '回收成本';

    SELECT f->>'expression' INTO v_loss_expr
    FROM template, jsonb_array_elements(formulas) f
    WHERE id = v_template_id AND f->>'name' = '材料损耗成本';

    IF v_has_pure AND v_pure_expr ILIKE '%SUM_OVER%' THEN
        RAISE NOTICE 'V147 CHECK-4a OK: 纯材料成本 = SUM_OVER 聚合';
    ELSE
        RAISE WARNING 'V147 CHECK-4a FAIL: 纯材料成本 公式缺失或不含 SUM_OVER';
    END IF;

    IF v_has_recycle AND v_recycle_expr ILIKE '%SUM_OVER%' THEN
        RAISE NOTICE 'V147 CHECK-4b OK: 回收成本 = SUM_OVER 聚合';
    ELSE
        RAISE WARNING 'V147 CHECK-4b FAIL: 回收成本 公式缺失或不含 SUM_OVER';
    END IF;

    IF v_has_loss AND v_loss_expr ILIKE '%SUM_OVER%' THEN
        RAISE NOTICE 'V147 CHECK-4c OK: 材料损耗成本 = SUM_OVER 聚合';
    ELSE
        RAISE WARNING 'V147 CHECK-4c FAIL: 材料损耗成本 公式缺失或不含 SUM_OVER';
    END IF;

    RAISE NOTICE '════════════════════════════════════════════════════════════';
    RAISE NOTICE 'V147 完成。自检指引:';
    RAISE NOTICE '  1. touch 任意 .java 文件 → 等待 Quarkus 重启 + Flyway 自动执行 V147';
    RAISE NOTICE '  2. GET  /api/cpq/templates/77decd71-c6cd-498a-9d8d-f47adfb024da/formulas';
    RAISE NOTICE '     → 应看到 15 条公式，其中纯材料成本/回收成本/材料损耗成本 expression 含 SUM_OVER';
    RAISE NOTICE '  3. POST /api/cpq/templates/77decd71-c6cd-498a-9d8d-f47adfb024da/formulas/纯材料成本/evaluate';
    RAISE NOTICE '     body: {"partNo":"3100080003"}';
    RAISE NOTICE '     → 期望值与 SELECT pure_material_cost FROM v_costing_summary_full WHERE hf_part_no=''3100080003'' 一致 (差异 <= 0.01%%)';
    RAISE NOTICE '  4. 同样测试 回收成本 / 材料损耗成本';
    RAISE NOTICE '  5. MAP 链式聚合 / 公式宏: TODO (时间紧跳过)';
    RAISE NOTICE '════════════════════════════════════════════════════════════';
END $$;
