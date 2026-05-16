-- V180: 创建「西门子一类料号报价模板」
-- 数据来源：复制罗克韦尔 v1.1 (3cf5a331-74cf-4b68-8cf8-525bbe8cf390) 的产品卡片相关字段
-- 改写：name / customer_id / template_series_id / version / is_default / excel_view_config

INSERT INTO template (
  id,
  template_series_id,
  name,
  version,
  category,
  description,
  usage_note,
  product_attributes,
  subtotal_formula,
  components_snapshot,
  status,
  created_by,
  published_at,
  created_at,
  updated_at,
  excel_view_config,
  customer_id,
  category_id,
  template_kind,
  formulas,
  is_default,
  referenced_variables
)
SELECT
  '9b1ce500-5181-4001-9001-100000000002'::uuid,
  '9b1ce500-5181-4002-9002-100000000002'::uuid,
  '西门子一类料号报价模板',
  'v1.0',
  category,
  '西门子第一类料号 - 沿用罗克韦尔产品卡片组件 + 西门子专属 Excel 公式（10 项 metric：纯材料/来料加工/回收/材料/损耗/加工/电镀/管理/利润/总成本）',
  '银点=焊接组件；外购件管理费 0.05 / 材料管理费 0.08 / 利润比例 0.10 / 回收折扣 0.95 为占位常量（见 v_q_siemens_class1_costs 视图注释）',
  product_attributes,
  subtotal_formula,
  components_snapshot,
  'PUBLISHED',
  created_by,
  NOW(),
  NOW(),
  NOW(),
  $json$[
    {"col_key":"H_PURE","title":"纯材料成本(中间)","hidden":true,"visible":false,"col_name":"纯材料成本(中间)","source_type":"VARIABLE","variable_path":"v_q_siemens_class1_costs.pure_material_cost"},
    {"col_key":"H_INCPROC","title":"来料加工费(中间)","hidden":true,"visible":false,"col_name":"来料加工费(中间)","source_type":"VARIABLE","variable_path":"v_q_siemens_class1_costs.incoming_proc_fee"},
    {"col_key":"H_RECYCLE","title":"回收成本(中间)","hidden":true,"visible":false,"col_name":"回收成本(中间)","source_type":"VARIABLE","variable_path":"v_q_siemens_class1_costs.recycle_cost"},
    {"col_key":"H_MAT","title":"材料成本(中间)","hidden":true,"visible":false,"col_name":"材料成本(中间)","source_type":"VARIABLE","variable_path":"v_q_siemens_class1_costs.material_cost"},
    {"col_key":"H_LOSS","title":"材料损耗成本(中间)","hidden":true,"visible":false,"col_name":"材料损耗成本(中间)","source_type":"VARIABLE","variable_path":"v_q_siemens_class1_costs.material_loss_cost"},
    {"col_key":"H_PROC","title":"加工费(中间)","hidden":true,"visible":false,"col_name":"加工费(中间)","source_type":"VARIABLE","variable_path":"v_q_siemens_class1_costs.process_fee"},
    {"col_key":"H_PLATE","title":"电镀加工费(中间)","hidden":true,"visible":false,"col_name":"电镀加工费(中间)","source_type":"VARIABLE","variable_path":"v_q_siemens_class1_costs.plating_process_fee"},
    {"col_key":"H_MGMT","title":"管理费(中间)","hidden":true,"visible":false,"col_name":"管理费(中间)","source_type":"VARIABLE","variable_path":"v_q_siemens_class1_costs.mgmt_fee"},
    {"col_key":"H_PROFIT","title":"利润(中间)","hidden":true,"visible":false,"col_name":"利润(中间)","source_type":"VARIABLE","variable_path":"v_q_siemens_class1_costs.profit"},
    {"col_key":"H_TOTAL","title":"总成本(中间)","hidden":true,"visible":false,"col_name":"总成本(中间)","source_type":"VARIABLE","variable_path":"v_q_siemens_class1_costs.total_cost"},
    {"col_key":"A","title":"宏丰料号","hidden":false,"visible":true,"col_name":"宏丰料号","col_index":"A","source_type":"VARIABLE","variable_path":"{hf_part_no}"},
    {"col_key":"B","title":"纯材料成本","hidden":false,"visible":true,"col_name":"纯材料成本","col_index":"B","source_type":"FORMULA","formula":"=[H_PURE]","comparison_tag":"PURE_MATERIAL_COST"},
    {"col_key":"C","title":"来料加工费","hidden":false,"visible":true,"col_name":"来料加工费","col_index":"C","source_type":"FORMULA","formula":"=[H_INCPROC]","comparison_tag":"INCOMING_PROC_FEE"},
    {"col_key":"D","title":"回收成本","hidden":false,"visible":true,"col_name":"回收成本","col_index":"D","source_type":"FORMULA","formula":"=[H_RECYCLE]","comparison_tag":"RECYCLE_COST"},
    {"col_key":"E","title":"材料成本","hidden":false,"visible":true,"col_name":"材料成本","col_index":"E","source_type":"FORMULA","formula":"=[H_MAT]","comparison_tag":"MATERIAL_COST"},
    {"col_key":"F","title":"材料损耗成本","hidden":false,"visible":true,"col_name":"材料损耗成本","col_index":"F","source_type":"FORMULA","formula":"=[H_LOSS]","comparison_tag":"MATERIAL_LOSS"},
    {"col_key":"G","title":"加工费","hidden":false,"visible":true,"col_name":"加工费","col_index":"G","source_type":"FORMULA","formula":"=[H_PROC]","comparison_tag":"PROCESS_FEE"},
    {"col_key":"H","title":"电镀成本","hidden":false,"visible":true,"col_name":"电镀成本","col_index":"H","source_type":"FORMULA","formula":"=[H_PLATE]","comparison_tag":"PLATING_PROC_FEE"},
    {"col_key":"I","title":"管理费","hidden":false,"visible":true,"col_name":"管理费","col_index":"I","source_type":"FORMULA","formula":"=[H_MGMT]","comparison_tag":"MGMT_FEE"},
    {"col_key":"J","title":"利润","hidden":false,"visible":true,"col_name":"利润","col_index":"J","source_type":"FORMULA","formula":"=[H_PROFIT]","comparison_tag":"PROFIT"},
    {"col_key":"K","title":"总成本RMB","hidden":false,"visible":true,"col_name":"总成本RMB","col_index":"K","source_type":"FORMULA","formula":"=[H_TOTAL]","comparison_tag":"TOTAL_COST"},
    {"col_key":"L","title":"总成本USD","hidden":false,"visible":true,"col_name":"总成本USD","col_index":"L","source_type":"FORMULA","formula":"=[K]/6.9755","comparison_tag":"TOTAL_USD"},
    {"col_key":"M","title":"总成本EUR","hidden":false,"visible":true,"col_name":"总成本EUR","col_index":"M","source_type":"FORMULA","formula":"=[K]/8.3436","comparison_tag":"TOTAL_EUR"}
  ]$json$::jsonb,
  '9aee3d9d-1b4d-4698-9af6-34bd9979d887'::uuid,
  category_id,
  template_kind,
  formulas,
  true,
  referenced_variables
FROM template
WHERE id = '3cf5a331-74cf-4b68-8cf8-525bbe8cf390';

-- 自检
DO $$
DECLARE
  v_id          UUID;
  v_name        VARCHAR;
  v_customer    UUID;
  v_col_cnt     INTEGER;
  v_comp_count  INTEGER;
BEGIN
  SELECT id, name, customer_id,
         jsonb_array_length(excel_view_config),
         jsonb_array_length(COALESCE(components_snapshot, '[]'::jsonb))
  INTO v_id, v_name, v_customer, v_col_cnt, v_comp_count
  FROM template WHERE id = '9b1ce500-5181-4001-9001-100000000002';

  IF v_id IS NULL THEN
    RAISE EXCEPTION '西门子模板创建失败';
  END IF;
  RAISE NOTICE '西门子模板创建成功: id=%, name=%, customer_id=%, excel_cols=%, components=%',
    v_id, v_name, v_customer, v_col_cnt, v_comp_count;
END $$;
