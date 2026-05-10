-- V113: 给报价单 Excel 视图复制核价 Excel 视图模板 (v1.2) 的完整 30 列配置
--
-- 起因:
--   报价单 QT-20260507-1347 的 customer_template_id = df6bd329 (QUOTATION 模板「施耐德专属1」)
--   df6bd329 上只有空 DRAFT Excel 模板 15dedbb4 (0 列), 报价单 Excel 视图无内容可渲染
--
-- 操作:
--   1. 把 cd58c933 (核价 Excel 模板 v1.2 PUBLISHED 30 列) 的 columns / referenced_variables 整体复制到 15dedbb4
--   2. 改名 "报价单Excel视图模板（完整公式版）"
--   3. status DRAFT → PUBLISHED, 对齐源版本 v1.2
--   4. is_default 已经是 true (无需改)
--
-- 反查链路 (LinkedExcelView 入口):
--   报价单 mainTab=quote, viewType=excel
--   → quotation.customer_template_id = df6bd329
--   → costing_template WHERE linked_template_id=df6bd329 AND status='PUBLISHED'
--   → 优先 is_default=true → 命中 15dedbb4 → 渲染 30 列
--
-- 唯一性约束: 同 linked_template_id 内 PUBLISHED + is_default=true 唯一
--   df6bd329 上当前没有其他 PUBLISHED 模板, 直接发布 15dedbb4 不冲突.

UPDATE costing_template
SET
    name                 = '报价单Excel视图模板（完整公式版）',
    columns              = (SELECT columns              FROM costing_template WHERE id = 'cd58c933-119f-40f2-9a79-a571bb8cebf0'),
    referenced_variables = (SELECT referenced_variables FROM costing_template WHERE id = 'cd58c933-119f-40f2-9a79-a571bb8cebf0'),
    status               = 'PUBLISHED',
    version              = 'v1.2',
    description          = 'V113: 基于「核价Excel视图模板（完整公式版）」v1.2 复制 30 列; 报价单 Excel 视图渲染入口 (linked_template = QUOTATION「施耐德专属1」 df6bd329); 用户可派生新草稿编辑',
    published_at         = now(),
    updated_at           = now()
WHERE id = '15dedbb4-135b-4014-b0cc-4af511ec861d';
