-- V65: Y1.5 行驱动 — 给 component 加 data_driver_path
--
-- 语义:
--   * 非空 → 该组件以此 BNF 路径作为"行驱动",查询返回 N 行,组件展开为 N 行
--   * 字段路径在求值时,会自动把 driver 行的同名列作为隐式 AND 谓词注入到字段路径的 WHERE 子句中
--     (隐式 JOIN — 字段可跨 sheet,只要目标表存在该列)
--
-- 例子:
--   data_driver_path = 'mat_bom[bom_type=''INCOMING'']'
--   字段 path = 'mat_part.unit_weight'
--   driver 行 hf_part_no='P1', input_material_no='C001'
--   → 字段实际查询 'mat_part[hf_part_no=''P1''].unit_weight'
--     (input_material_no 不在 mat_part 列里,自动跳过注入)

ALTER TABLE component ADD COLUMN IF NOT EXISTS data_driver_path TEXT NULL;

COMMENT ON COLUMN component.data_driver_path IS
    'Y1.5 行驱动 BNF 路径(可选)。非空时组件展开为 driver 路径返回的 N 行,字段查询自动隐式 JOIN driver 行字段。';
