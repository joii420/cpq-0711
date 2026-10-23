# 前端任务：自制加工费工序名称字段

## 结论

前端不新增业务代码。`SqlViewBuilderTab` 已按后端 `GET /field-tree` 的 `groups[].fields[]` 原样渲染字段树；后端新增字段后即可显示、拖拽和保存。

## 任务分解

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| F-1 | AC-1、AC-4 | 用真实字段树响应确认「工序名称」出现在「工序编号」之后，且未选择时不改变既有列。 |
| F-2 | AC-5 | 执行 `npx tsc --noEmit`，确认无需前端源码改动。 |

## 不做

- 不在前端按 `SELF_PROCESS_FEE` 硬编码字段。
- 不在前端拼接 SQL 或 process_master 查询。
