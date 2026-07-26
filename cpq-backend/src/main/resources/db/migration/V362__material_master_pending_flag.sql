-- V362: repair-0726 —— material_master 行级 pending 标记（替代 pending_material_master_staging 暂存机制）
-- spec: dev-docs/task-0708-导入报价单和导入核价单的数据落库规则澄清/repair-0726-BOM中料件类投入料号没有落库/{需求说明.md §12.1, backtask.md §2}

-- ① 加列 + 部分索引
ALTER TABLE material_master ADD COLUMN pending_quotation_id UUID NULL;
CREATE INDEX ix_material_master_pending ON material_master(pending_quotation_id) WHERE pending_quotation_id IS NOT NULL;
COMMENT ON COLUMN material_master.pending_quotation_id IS
  'repair-0726：该行由哪张未核准报价单新建（导入期=importRecordId，建单后=quotationId）；NULL=已生效的正式料号';

-- ② 存量迁回（暂存表 → 正表）
--    去重策略：DISTINCT ON 取最早 created_at 的行「整行胜出」，同 material_no 的后续行整行丢弃
--      （注意：这与 promoteStaging 的逐行 upsert 跨单归并语义并不等价——后者是逐列 COALESCE 链）。
--      实测当前数据集 3 组重复（S-3120014539/S-80011/W-1001 各 3 行）跨单取值完全相同，故本次迁移无损；
--      ON CONFLICT 分支的逐列 COALESCE 方向才是与 upsertByMaterialNo(preserveDescriptive=true) 对齐的部分。
INSERT INTO material_master (material_no, material_name, specification, dimension, old_material_no,
                             material_type, usage_property, unit_weight, standard_unit, production_no,
                             pending_quotation_id, created_at, updated_at, updated_by)
SELECT DISTINCT ON (material_no)
       material_no, material_name, specification, dimension, old_material_no,
       material_type, usage_property, unit_weight, standard_unit, production_no,
       quotation_id, created_at, NOW(), updated_by
FROM pending_material_master_staging
ORDER BY material_no, created_at, quotation_id
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
  updated_by      = EXCLUDED.updated_by
  -- ⚠️ 故意不写 pending_quotation_id：正表已存在的行（老正式料号）不得被降级为 pending
  ;

-- ③ 退役暂存表
DROP TABLE IF EXISTS pending_material_master_staging;
