-- V319__element_no_as_business_key.sql
-- 元素主表管理（task-0709 · B1）：element_no(元素编号) 升为不可改业务主键。
-- element_code(符号) 保留 UNIQUE，仍不可重复；"被引用即锁"在应用层校验（未引用可改但须唯一）。

-- 1) 给无编号的元素补号（Au/CdO 等 V317 seed 元素，element_no 当前为 NULL）。
--    用保留段 90000+ 避免与 Excel 10000 段冲突；子查询按 element_code 排序保证确定性。
WITH missing AS (
  SELECT id, row_number() OVER (ORDER BY element_code) AS rn
  FROM element WHERE element_no IS NULL
)
UPDATE element e SET element_no = (90000 + m.rn)::text
FROM missing m WHERE e.id = m.id;

-- 2) element_no 升为 NOT NULL + UNIQUE 业务主键
ALTER TABLE element ALTER COLUMN element_no SET NOT NULL;
ALTER TABLE element ADD CONSTRAINT uq_element_no UNIQUE (element_no);
COMMENT ON COLUMN element.element_no IS '元素编号(业务主键, 不可改); element_code(符号)为可编辑属性,被引用即锁';
