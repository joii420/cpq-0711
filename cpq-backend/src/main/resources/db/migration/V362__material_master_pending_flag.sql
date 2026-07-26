-- ① 加列 + 部分索引
ALTER TABLE material_master ADD COLUMN pending_quotation_id UUID NULL;
CREATE INDEX ix_mm_pending ON material_master(pending_quotation_id) WHERE pending_quotation_id IS NOT NULL;
COMMENT ON COLUMN material_master.pending_quotation_id IS
  'repair-0726：该行由哪张未核准报价单新建（导入期=importRecordId，建单后=quotationId）；NULL=已生效的正式料号';

-- ② 存量迁回（暂存表 → 正表）
--    去重：同一 material_no 可能在多张单的暂存表里各有一行（暂存表唯一键是 quotation_id+material_no），
--    PG 不允许一条 INSERT 对同一冲突键命中两次 → 必须 DISTINCT ON，取最早 created_at 的单作归属。
INSERT INTO material_master (material_no, material_name, specification, dimension, old_material_no,
                             material_type, usage_property, unit_weight, standard_unit, production_no,
                             pending_quotation_id, created_at, updated_at, updated_by)
SELECT DISTINCT ON (material_no)
       material_no, material_name, specification, dimension, old_material_no,
       material_type, usage_property, unit_weight, standard_unit, production_no,
       quotation_id, NOW(), NOW(), updated_by
FROM pending_material_master_staging
ORDER BY material_no, created_at
ON CONFLICT (material_no) DO UPDATE SET
  -- 与 promoteStaging 走的 upsertByMaterialNo(preserveDescriptive=true) 口径逐列对齐：
  --   name/type = COALESCE(现值, 新值)；其余列 = COALESCE(新值, 现值)
  material_name   = COALESCE(material_master.material_name, EXCLUDED.material_name),
  material_type   = COALESCE(material_master.material_type, EXCLUDED.material_type),
  specification   = COALESCE(EXCLUDED.specification,   material_master.specification),
  dimension       = COALESCE(EXCLUDED.dimension,       material_master.dimension),
  old_material_no = COALESCE(EXCLUDED.old_material_no, material_master.old_material_no),
  usage_property  = COALESCE(EXCLUDED.usage_property,  material_master.usage_property),
  unit_weight     = COALESCE(EXCLUDED.unit_weight,     material_master.unit_weight),
  standard_unit   = COALESCE(EXCLUDED.standard_unit,   material_master.standard_unit),
  production_no   = COALESCE(EXCLUDED.production_no,   material_master.production_no),
  updated_at      = NOW(),
  updated_by      = EXCLUDED.updated_by;
  -- ⚠️ 故意不写 pending_quotation_id：正表已存在的行（老正式料号）不得被降级为 pending

-- ③ 退役暂存表
DROP TABLE pending_material_master_staging;
