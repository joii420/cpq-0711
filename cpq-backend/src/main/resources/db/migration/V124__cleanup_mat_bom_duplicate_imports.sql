-- V124: 清理 mat_bom 重复导入累积的脏数据
--
-- 起因:
--   报价单 QT-20260507-1357 「来料BOM」tab 显示 4 行 (Excel 实际只 2 行).
--   DB 里 mat_bom partNo=3120012574 bom_type='INCOMING' 4 行:
--     04-28 第一批: seq=1/2, input_material_no=NULL  ← 早期 V5 import (Excel 投入料号没填)
--     05-07 第二批: seq=1/2, input_material_no=123/234  ← 用户填了投入料号后再次 import
--   两次都 INSERT (没 UPDATE) — V5 import 缺幂等性, 累积脏数据.
--
-- 业务键 unique 约束 uq_mat_bom_row 包含 COALESCE(input_material_no, ''):
--   (bom_type, hf_part_no, seq_no, COALESCE(imat,''), COALESCE(elem,''))
--   NULL 跟 '123' 被视为不同 key → 两次 import 都不冲突.
--
-- 清理策略:
--   同 (bom_type, hf_part_no, seq_no) 内, 若已存在 input_material_no 非空的行 (新版),
--   则删除 input_material_no IS NULL 的同键行 (旧版脏数据).
--
-- 注意: 不动 element_name 维度 (ELEMENT 行需要 element_name 区分多元素 → 该列必有值, 不会误删).

DELETE FROM mat_bom AS old_row
WHERE old_row.input_material_no IS NULL
  AND EXISTS (
      SELECT 1 FROM mat_bom AS new_row
      WHERE new_row.bom_type = old_row.bom_type
        AND new_row.hf_part_no = old_row.hf_part_no
        AND new_row.seq_no = old_row.seq_no
        AND COALESCE(new_row.element_name,'') = COALESCE(old_row.element_name,'')
        AND new_row.input_material_no IS NOT NULL
  );

-- 输出删除行数 (info)
DO $$
DECLARE v_remaining INT;
BEGIN
    SELECT COUNT(*) INTO v_remaining FROM mat_bom WHERE bom_type='INCOMING' AND hf_part_no='3120012574';
    RAISE NOTICE 'V124: partNo=3120012574 INCOMING 剩余行数 = % (期望 2)', v_remaining;
END $$;
