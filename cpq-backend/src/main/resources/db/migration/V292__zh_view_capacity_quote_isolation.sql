-- V292: zh_view 读 capacity 补 system_type='QUOTE' 隔离（防 PRICING 产能行混入报价组合工艺渲染 → AP-22 重复行）。
-- 背景：capacity 自 V290 起含 system_type；P08 写 PRICING_DEFAULT(is_current=true) 行，
--   zh_view 原 `FROM capacity WHERE is_current`（无 system_type / 无 resource_group_no 过滤）会同时返回
--   QUOTE_ASSEMBLY 与 PRICING_DEFAULT 两组 current 行 → 报价侧 zh_view 渲染重复/串号。
-- zh_view 属报价 组合工艺(QUOTE) 渲染族，capacity 读取语义应为 system_type='QUOTE'。
-- 幂等：仅当尚未含 system_type 过滤时注入。

UPDATE component_sql_view
   SET sql_template = regexp_replace(
         sql_template,
         'from\s+capacity\s+where\s+is_current',
         'from capacity where is_current and system_type = ''QUOTE''',
         'gi')
 WHERE sql_view_name = 'zh_view'
   AND sql_template NOT ILIKE '%system_type%';
