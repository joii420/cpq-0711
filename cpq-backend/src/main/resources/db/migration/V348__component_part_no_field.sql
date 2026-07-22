-- V348: task-0721 补录 —— 组件级「料号列 / 料号名称列」显式标识
-- spec: dev-docs/task-0721-报价侧树状结构与页签类型属性/{需求说明.md §4.3 规则一,api.md §1}
--
-- 立项时用户已提出「料号列/料号名称」表述，初版文档遗漏，导致类型判定/候选料号采集
-- 只能靠字段名启发式猜测（如字段名含"料号"）。补两个组件级配置列：
--   part_no_field   —— 该页签哪个字段是料号列（该组件 fields[].name 中的值）
--   part_name_field —— 该页签哪个字段是料号名称列（可空）
--
-- 树页签（tab_type=BOM）料号取系统列 __hfPartNo，两列均可不配；
-- 非树页签（材质元素/零件/外购件/主件）保存期强制要求 part_no_field（应用层校验，非 DB 约束，
-- 因存量组件在校验生效前可能尚未配置，不能用 NOT NULL 卡死存量数据）。
ALTER TABLE component ADD COLUMN part_no_field VARCHAR(100);
ALTER TABLE component ADD COLUMN part_name_field VARCHAR(100);
