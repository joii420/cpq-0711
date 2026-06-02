-- 组件树表配置(纯展示):{ "idField": "...", "parentField": "...", "defaultExpanded": true }
-- NULL = 非树表(平铺渲染,行为不变)。
ALTER TABLE component ADD COLUMN IF NOT EXISTS tree_config jsonb;

COMMENT ON COLUMN component.tree_config IS
  '树表配置(纯展示):idField/parentField/defaultExpanded;NULL=非树表';
