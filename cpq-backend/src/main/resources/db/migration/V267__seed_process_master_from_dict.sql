-- V267: 选配落库迁 V6 Phase 2 — 回填 process_master（工序名主数据）
--
-- 背景（docs/选配V6迁移诊断方案.md B-5 + AP-53 续 6 Phase 2）：
-- 工序 Tab 渲染视图 composite_child_processes_mirror 用
--   LEFT JOIN process_master pm ON pm.process_no = asy.operation_no
--   COALESCE(pm.process_name, asy.operation_no) AS assembly_process
-- 取工序中文名。但 process_master 为空（0 行）→ 工序名全部回退成工序代码（B-5）。
--
-- 工序"字典" process（41 行，含 code/name/category）是选配/导入工序的来源，
-- operation_no 即 process.code。此处把字典回填到 process_master，使导入 + 选配
-- 写入的工序行（material_bom_item.operation_no）都能 JOIN 出工序名。
-- 幂等：ON CONFLICT(process_no) DO NOTHING，重复迁移安全。

INSERT INTO process_master (process_no, process_name, process_category)
SELECT code, name, category
FROM process
ON CONFLICT (process_no) DO NOTHING;
