-- V320__material_recipe_element_add_element_no.sql
-- 材质元素明细加权威元素链 element_no（task-0709 · B2）。
-- element_code/element_name 继续作快照保留（符号锁保证被引用元素符号不变→快照恒一致，
-- 不动选配/定价/渲染的 element_code 读取，影响面最小）。

ALTER TABLE material_recipe_element ADD COLUMN IF NOT EXISTS element_no VARCHAR(32);

-- 存量回填：按当前 element_code 反查 element 主表拿 element_no。
-- 不设 NOT NULL（历史脏 element_code 可能反查不到，留 NULL 容错）。
UPDATE material_recipe_element mre
   SET element_no = e.element_no
  FROM element e
 WHERE e.element_code = mre.element_code
   AND mre.element_no IS NULL;

CREATE INDEX IF NOT EXISTS idx_mre_element_no ON material_recipe_element(element_no);
COMMENT ON COLUMN material_recipe_element.element_no IS '权威元素链(→element.element_no); element_code/name 为随符号锁恒一致的快照';
