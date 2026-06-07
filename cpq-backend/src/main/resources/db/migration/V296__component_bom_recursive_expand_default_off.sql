-- 需求变更(2026-06-06)：核价 BOM 递归展开默认改为关闭(勾选才递归)。
-- 存量组件一并置 false（用户确认：彻底默认关），列默认值改 false。
-- 影响：现有核价单下次快照重算时，未手动勾选的组件从 BOM 树变回普通单料号渲染。
UPDATE component SET bom_recursive_expand = false WHERE bom_recursive_expand IS DISTINCT FROM false;
ALTER TABLE component ALTER COLUMN bom_recursive_expand SET DEFAULT false;
