-- V129: 全表清理 mat_bom 业务键模糊匹配的脏行
--
-- 起因:
--   V124 仅清理单个料号 (3120012574); 本次扩到全表。
--   根因: uq_mat_bom_row 使用 COALESCE(input_material_no,'') 作为键之一,
--   导致 input_material_no=NULL 与 input_material_no='123' 被视为不同 key,
--   同一 (bom_type, hf_part_no, seq_no) 可以共存 NULL 占位行 + 真实值行 → 脏数据。
--
-- 策略:
--   同 (bom_type='INCOMING', hf_part_no, seq_no, COALESCE(element_name,'')) 组内,
--   若已存在 input_material_no 非空的行（真实值），删除 input_material_no IS NULL 的同键脏行。
--
-- ELEMENT 不动:
--   ELEMENT 行的业务键含 element_name（各元素名不同），NULL input_material_no 是合法独立行。
--   此脚本通过 bom_type='INCOMING' 限制范围，ELEMENT 行不受影响。
--
-- 后续防御:
--   BasicDataImportServiceV5 写入 INCOMING 真实 input_material_no 行前会先 DELETE 同键 NULL 占位行。

DELETE FROM mat_bom AS old_row
WHERE old_row.bom_type = 'INCOMING'
  AND old_row.input_material_no IS NULL
  AND EXISTS (
      SELECT 1 FROM mat_bom AS new_row
      WHERE new_row.bom_type = 'INCOMING'
        AND new_row.hf_part_no = old_row.hf_part_no
        AND new_row.seq_no = old_row.seq_no
        AND COALESCE(new_row.element_name,'') = COALESCE(old_row.element_name,'')
        AND new_row.input_material_no IS NOT NULL
  );

DO $$
DECLARE
    v_incoming_null_count INT;
    v_deleted_hint        TEXT;
BEGIN
    SELECT COUNT(*) INTO v_incoming_null_count
    FROM mat_bom
    WHERE bom_type = 'INCOMING'
      AND input_material_no IS NULL;

    v_deleted_hint := CASE
        WHEN v_incoming_null_count = 0 THEN '全部清理完毕，INCOMING NULL 行归零'
        ELSE '仍有 ' || v_incoming_null_count || ' 行 INCOMING NULL 行（无对应非空行，属于合法占位）'
    END;

    RAISE NOTICE 'V129 cleanup done: %', v_deleted_hint;
END $$;
