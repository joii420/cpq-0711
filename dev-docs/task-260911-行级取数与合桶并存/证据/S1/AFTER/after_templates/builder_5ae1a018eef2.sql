SELECT
  dqspf.material_no AS hf_part_no,
  dqspf.input_material_no AS "_自制加工费_投入料号",
  dqspf.material_no AS "_自制加工费_销售料号",
  dqspf.item_seq AS "_自制加工费_项次"
FROM ds_quote_self_process_fee dqspf
WHERE dqspf.material_no = ANY(:total_material_no) AND dqspf.customer_no = :customerCode
ORDER BY dqspf.material_no, dqspf.input_material_no, dqspf.item_seq
