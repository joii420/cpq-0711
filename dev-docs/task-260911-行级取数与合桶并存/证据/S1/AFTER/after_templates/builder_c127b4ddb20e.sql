SELECT
  dqad.material_no AS hf_part_no,
  dqad.material_no AS "_年降系数_销售料号",
  dqad.discount_seq AS "_年降系数_年降顺序",
  dqad.discount_rate AS "_年降系数_年降系数（%/年）",
  dqad.fixed_discount_value AS "_年降系数_单次固定年降金额",
  dqad.currency AS "_年降系数_货币",
  dqad.pricing_unit AS "_年降系数_计价单位",
  dqad.discount_times AS "_年降系数_降价次数"
FROM ds_quote_annual_discount dqad
WHERE dqad.material_no = ANY(:total_material_no) AND dqad.customer_no = :customerCode
ORDER BY dqad.material_no, dqad.discount_seq
