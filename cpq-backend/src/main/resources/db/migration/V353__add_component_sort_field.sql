-- task-0722: 组件级「行排序列」配置。
-- 多行页签(如 其他费用/电镀费用/自制加工费)按此字段对 driver 行做数字感知升序排列,
-- 让 项次/序号 之类的列稳定按数字正序显示 —— 修视图 ORDER BY 在报价单 pending 改写管线下不生效,
-- 故在快照组装层按本字段排序(见 ConfigureSnapshotService)。
-- 值 = 本组件 fields[].name 之一; null = 保持 driver 返回序(不排)。
ALTER TABLE component ADD COLUMN IF NOT EXISTS sort_field varchar(120);
COMMENT ON COLUMN component.sort_field IS
  '多行页签行排序字段(fields[].name之一);设置后快照按该字段数字感知升序排列;null=保持driver返回序';
