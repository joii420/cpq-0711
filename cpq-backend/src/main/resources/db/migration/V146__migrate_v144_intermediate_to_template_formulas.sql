-- ============================================================
-- V146: 把 V144 配的可见 FORMULA 列迁移为 template.formulas
--       目标模板: id = 77decd71-c6cd-498a-9d8d-f47adfb024da (DRAFT)
--
-- 迁移策略:
--   - 13 个可见 FORMULA 列（B/C/D/E/L/M/BASE/F/G/H/I/N/Q）迁移为模板公式
--   - 18 个隐藏 VARIABLE 中间列（B_PURE/B_PROC/...）保留在 excel_view_config
--     仍由 LinkedExcelView 从 v_costing_summary_full / v_c_summary_agg 取值
--   - 模板公式里用 [col_key] 引用隐藏列；TemplateFormulaService.resolveColKeyFallback
--     Stage 2 会从 excel_view_config VARIABLE 列的 variable_path 取实际值
--   - 保留 V111 SQL 视图 v_costing_summary_full 作为 fallback（不删除）
--
-- 无法迁移（标 TODO Stage 4，需要 SUM_OVER 聚合）:
--   - B_PURE (纯材料成本): SUM_OVER([来料BOM] WHERE ..., input_qty/output_qty*unit_price)
--   - B_RECYCLE (回收成本): SUM_OVER([回收BOM] WHERE ..., ...)
--   - C_LOSS (材料损耗成本): 按损耗率计算
--   上述 3 列目前仍从 v_costing_summary_full 视图取值（VARIABLE 列保持不变）
--
-- 已知正确答案 (partNo=3100080003):
--   材料成本(B)      = 4892.484
--   加工费(E)        = 4.3369
--   管理费(F)        = 30.43178959
--   总成本(CNY/KG)   = 6043.410233
--   总成本(USD/PCS)  = 1.667981224
--
-- 公式依赖拓扑顺序（被依赖在前）:
--   B C D E L M → BASE → F G H I → N → Q
-- ============================================================

DO $$
DECLARE
    v_template_id UUID := '77decd71-c6cd-498a-9d8d-f47adfb024da'::uuid;
    v_status      TEXT;
    v_formulas    JSONB;
BEGIN
    -- 验证模板存在（不限状态：V146 是数据迁移脚本，允许对 PUBLISHED 模板直接写 formulas）
    SELECT status INTO v_status FROM template WHERE id = v_template_id;
    IF v_status IS NULL THEN
        RAISE EXCEPTION 'V146: template % 不存在', v_template_id;
    END IF;
    RAISE NOTICE 'V146: template % 当前状态=%, 直接写入 formulas（迁移脚本不受 DRAFT 限制）', v_template_id, v_status;

    -- 构建 formulas JSONB 数组（13 条，按拓扑依赖顺序排列）
    -- 注意：[B_PURE][B_PROC] 等方括号引用会走 Stage 2 col_key fallback
    --       从 excel_view_config 中对应 VARIABLE 列的 variable_path 取值
    v_formulas := $FORMULAS$[
  {
    "name": "材料成本",
    "expression": "[B_PURE]+[B_PROC]+[B_OTHER]+[B_FIX]-[B_RECYCLE]",
    "data_type": "DECIMAL(18,4)",
    "depends_on": ["B_PURE","B_PROC","B_OTHER","B_FIX","B_RECYCLE"],
    "description": "材料成本 B = 纯材料成本 + 来料加工费 + 来料其他比例费 + 来料固定费 - 回收成本"
  },
  {
    "name": "材料损耗成本",
    "expression": "[C_LOSS]",
    "data_type": "DECIMAL(18,4)",
    "depends_on": ["C_LOSS"],
    "description": "材料损耗成本 C = v_costing_summary_full.material_loss_cost (直接引用视图聚合值)"
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
    "description": "总成本(CNY/KG) N = B+C+D+E+F+G+H+I+J+K+L+M，其中 J/K 走 col_key fallback 取运费/清关费"
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
    SET formulas   = v_formulas,
        updated_at = now()
    WHERE id = v_template_id;

    RAISE NOTICE 'V146 OK: template % formulas 已写入 % 条公式',
        v_template_id, jsonb_array_length(v_formulas);
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- 验证
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_template_id   UUID := '77decd71-c6cd-498a-9d8d-f47adfb024da'::uuid;
    v_formula_count INT;
    v_has_total     BOOLEAN := false;
    v_has_base      BOOLEAN := false;
    v_has_usd       BOOLEAN := false;
BEGIN
    SELECT jsonb_array_length(formulas)
    INTO v_formula_count
    FROM template
    WHERE id = v_template_id;

    -- 检查关键公式
    SELECT EXISTS (
        SELECT 1 FROM template, jsonb_array_elements(formulas) f
        WHERE id = v_template_id AND f->>'name' = '总成本(CNY/KG)'
    ) INTO v_has_total;

    SELECT EXISTS (
        SELECT 1 FROM template, jsonb_array_elements(formulas) f
        WHERE id = v_template_id AND f->>'name' = '加价基数'
    ) INTO v_has_base;

    SELECT EXISTS (
        SELECT 1 FROM template, jsonb_array_elements(formulas) f
        WHERE id = v_template_id AND f->>'name' = '总成本(USD/PCS)'
    ) INTO v_has_usd;

    IF v_formula_count IS NULL OR v_formula_count < 13 THEN
        RAISE WARNING 'V146 CHECK FAIL: formulas 条数=%, 期望>=13', v_formula_count;
    ELSE
        RAISE NOTICE 'V146 CHECK-1 OK: formulas 条数=%', v_formula_count;
    END IF;

    IF v_has_total THEN
        RAISE NOTICE 'V146 CHECK-2 OK: 公式「总成本(CNY/KG)」存在';
    ELSE
        RAISE WARNING 'V146 CHECK-2 FAIL: 缺少公式「总成本(CNY/KG)」';
    END IF;

    IF v_has_base THEN
        RAISE NOTICE 'V146 CHECK-3 OK: 公式「加价基数」存在';
    ELSE
        RAISE WARNING 'V146 CHECK-3 FAIL: 缺少公式「加价基数」';
    END IF;

    IF v_has_usd THEN
        RAISE NOTICE 'V146 CHECK-4 OK: 公式「总成本(USD/PCS)」存在';
    ELSE
        RAISE WARNING 'V146 CHECK-4 FAIL: 缺少公式「总成本(USD/PCS)」';
    END IF;

    RAISE NOTICE 'V146 完成: 13 条模板公式已写入 template.formulas (template_id=%)', v_template_id;
    RAISE NOTICE 'V146 TODO Stage4: B_PURE(纯材料成本)/B_RECYCLE(回收成本)/C_LOSS(材料损耗) 仍从 SQL 视图取值，待 SUM_OVER 聚合实现后替换';
END $$;
