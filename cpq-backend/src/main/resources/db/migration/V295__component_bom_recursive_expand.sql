-- 核价 BOM 递归展开 组件级开关（默认开=保 P1 现状）。仅核价侧生效。
-- 与语义不同的 tree_config（组件数据自带 idField/parentField 树）正交。
ALTER TABLE component
  ADD COLUMN IF NOT EXISTS bom_recursive_expand BOOLEAN NOT NULL DEFAULT true;
