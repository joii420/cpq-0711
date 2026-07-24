# 5 · 公式与 Excel 列

> **来源**：`配置方法论-合并版.md`（公式章节 + §11 字段类型联动协议 AP-44 + Excel 列配置）+ `反模式.md AP-37/AP-44`。
> **状态**：🚧 骨架待填。

## 5.1 公式引擎

- 公式存 `component.formulas` JSONB；字段 `field_type=FORMULA` + `formula_name` 指向。
- token 类型：`field` / `b_field` / `component_subtotal` / `cross_tab_ref`(match + predicate + targetExpr) / `operator` / `number` / `bracket` / `__amount_total__`。
- `cross_tab_ref`：跨页签引用（source=组件id，match=[{a,b}] 行匹配，agg=SUM/NONE）。
- KSUM 限制：不允许嵌套跨页签引用 / KSUM 套 KSUM。

## 5.2 🚨 字段类型联动协议（AP-44，17 检查点）

- 任何 `field_type` 改动/新增跨 **17 个协议检查点**（约 13 文件）：前端 enrich / normalizeFieldType / cache key / 渲染分支 / computeAllFormulas / 所有 ProductCard callsite / 详情页 ReadonlyProductCard / 后端校验 / 路径采集 / 公式 token / refreshSnapshotsByComponent / useDriverExpansions / QuotationStep2。
- 漏一处 = 静默失败（不报错，UI 渲染不对）。
- 强制 SOP：写前 grep 全工程列清单 → 写中对照 checklist → 写完跑 E2E 双 spec + 报价/核价/详情三视图。

## 5.3 Excel 列配置

- `component_type=EXCEL` 组件：`excel_columns` JSONB 列定义。
- （来源 配置方法论-合并版 Excel 章节。）
