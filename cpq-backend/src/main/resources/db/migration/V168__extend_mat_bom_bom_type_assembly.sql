-- V168__extend_mat_bom_bom_type_assembly.sql
-- 给 mat_bom 添加 ASSEMBLY bom_type 以表达组合产品的"父→子配件"关系
-- 新增列: child_part_no (ASSEMBLY 行的子料号; 其他 bom_type 行为 NULL)
--
-- 注意: 实际约束名为 chk_mat_bom_type (含 INCOMING/ELEMENT 两值)
--       同时兼容任务描述中的 chk_mat_bom_bom_type (IF EXISTS 安全删除)

ALTER TABLE mat_bom DROP CONSTRAINT IF EXISTS chk_mat_bom_bom_type;
ALTER TABLE mat_bom DROP CONSTRAINT IF EXISTS chk_mat_bom_type;

ALTER TABLE mat_bom
    ADD CONSTRAINT chk_mat_bom_bom_type
        CHECK (bom_type IN ('ELEMENT','INCOMING','OUTPUT','ASSEMBLY'));

ALTER TABLE mat_bom
    ADD COLUMN IF NOT EXISTS child_part_no VARCHAR(64) NULL;

CREATE INDEX IF NOT EXISTS idx_mat_bom_child_part_no
    ON mat_bom(child_part_no)
    WHERE child_part_no IS NOT NULL;

COMMENT ON CONSTRAINT chk_mat_bom_bom_type ON mat_bom IS
    'ASSEMBLY: 组合产品父→子,child_part_no 表达子料号';
COMMENT ON COLUMN mat_bom.child_part_no IS
    'ASSEMBLY 行:子配件 hf_part_no;其他 bom_type 为 NULL';
