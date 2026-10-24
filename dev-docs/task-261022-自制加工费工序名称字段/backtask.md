# 后端任务：自制加工费工序名称字段

## 任务分解

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| B-1 | AC-1、AC-2 | 新增 Flyway 迁移：登记 `process_master` 查名节点及 `process_name` 列；增加 `SELF_PROCESS_FEE -> process_master` 的 LOOKUP 边和 `operation_no = process_no` 连接键。 |
| B-2 | AC-1、AC-2、AC-4 | 调整字段树查名字段排序，使该边生成的「工序名称」紧跟锚点「工序编号」，不改变其他数据源字段顺序。 |
| B-3 | AC-2、AC-3 | 用现有 `SemanticCompiler` 的 LEFT JOIN 路径编译并预览，确认 process_name 输出且未命中时保留左表行。 |
| B-4 | AC-5 | 补充后端测试，覆盖字段树顺序、SQL JOIN/列投影、未匹配工序的 LEFT JOIN 语义和存量路径回归。 |

## 执行结果

| 编号 | 状态 | 结果 |
|---|---|---|
| B-1 | ✅ | Flyway `V452` 新增 `PROCESS_NAME_LK` 薄查名节点及 `SELF_PROCESS_FEE` LOOKUP 边，连接键为 `operation_no -> process_no`。 |
| B-2 | ✅ | `FieldTreeBuilder` 按 LOOKUP 左连接键定位插入点，「工序名称」紧跟「工序编号」；既有材料名仍紧跟投入料号。 |
| B-3 | ✅ | 编译 SQL 生成 `LEFT JOIN process_master` 并投影 `process_name`；不启用 join-key fallback。 |
| B-4 | ✅ | 定向单元/集成测试、迁移验证、前端 TypeScript 检查与浏览器只读验收均通过，详见 `test-report.md`。 |

## N+1 约束

本次查名由编译期单条 SQL 的 LEFT JOIN 生成，不在行循环中查询 `process_master`；迁移和测试不得引入 N+1。

已自检：生成 SQL 仅增加一次集合级 `LEFT JOIN process_master`，未增加逐行查询或循环查库。
