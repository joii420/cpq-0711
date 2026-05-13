-- V166__fix_mat_composite_process_column_and_fk.sql
-- 修正 V165 留下的两处问题:
--   I-2 (重要): 列名 parent_hf_part_no → hf_part_no
--     原因: ImplicitJoinRewriter 自动按 hf_part_no 注入谓词;
--     parent_hf_part_no 不会被识别,会触发 AP-22 多行误聚合
--   I-1 (重要): 增加 FK 约束 hf_part_no → mat_part(part_no)
--     原因: 与 mat_bom / mat_process / mat_fee / mat_plating_* 族表约定一致

-- 1) 列重命名(PostgreSQL 自动级联现有索引与 UNIQUE 约束的列引用)
ALTER TABLE mat_composite_process
    RENAME COLUMN parent_hf_part_no TO hf_part_no;

-- 2) 索引重命名(从 *_parent 改为 *_part_version 以反映新列名,可选但建议)
ALTER INDEX IF EXISTS idx_mat_composite_process_parent
    RENAME TO idx_mat_composite_process_part_version;

-- 3) 增加 FK 到 mat_part(part_no), 与族表约定一致
ALTER TABLE mat_composite_process
    ADD CONSTRAINT fk_mat_composite_process_part
        FOREIGN KEY (hf_part_no) REFERENCES mat_part(part_no);

-- 4) 列注释更新
COMMENT ON COLUMN mat_composite_process.hf_part_no IS
    '组合产品父料号(原名 parent_hf_part_no,V166 重命名以对齐 ImplicitJoinRewriter 列名约定; FK to mat_part.part_no)';
