# 字段宽度设置 — 设计文档

- 日期：2026-06-17
- 范围：组件管理「页签字段配置」新增字段宽度设置 + 就地预览；报价单/核价单的详情页、编辑页按宽度渲染列宽。
- 方案选型：固定像素 + 预设档位（档位仅作 UI 快捷，最终只存像素值 = 方案 A）。

## 1. 背景与目标

当前报价单 / 核价单中，组件以手写 `<table className="qt-cost-table">` 渲染，每个字段是表格里的一列，列头为 `<th>{field.label || field.name}</th>`，**目前字段列未设任何宽度**，列宽完全由浏览器按内容自适应，导致无法控制各列显示宽度。

目标：让用户在组件管理的字段配置中为每个字段设置展示宽度，并能就地预览效果，宽度在报价单 / 核价单的详情页与编辑页统一生效。

## 2. 关键决策（已与用户确认）

| 项 | 决策 |
|---|---|
| 宽度形式 | 固定像素值 + 预设档位（档位是 UI 快捷，只存像素） |
| 编辑位置 | 字段配置表格内新增一列「宽度」，就地编辑 |
| 默认值 | 未设置（空）的字段一律按 **120px** 渲染 |
| 预设档位 | 窄=80 / 中=120 / 宽=200；并允许直接填任意像素值 |
| 预览形式 | 字段配置表**下方**一条横向迷你预览条，按各字段宽度排成模拟列头 |
| 生效范围 | 报价单 + 核价单，**详情页 + 编辑页** |
| 字段类型 | 所有字段类型一视同仁（FORMULA / LIST_FORMULA / DATA_SOURCE / INPUT / 固定值 等） |
| 系统列 | 料号 / 父料号 / 版本 / 操作列等系统列**不在范围内**，保持现状 |
| 不做 | Excel 视图、列表页不改；不做"按档位全局联动"（只存 px） |

## 3. 数据模型

- `cpq-frontend/src/pages/component/types.ts` 的 `FieldItem` 新增可选字段：
  ```ts
  /** 报价单/核价单渲染时该字段列的展示宽度(px)。空 = 默认 120。 */
  width?: number;
  ```
- 空值语义：`undefined` / `null` / `0` 视为未设置 → 渲染按 **120px**。
- 落库：随 `component.fields` 的 JSONB 一起持久化。
- 传播链路（**关键，须逐点确认新键不被丢弃**）：
  `component.fields` → 模板 snapshot → `enrichComponentData` → `ProductCard` / `ReadonlyProductCard` 渲染。
  - 若 `enrichComponentData` 等环节是整对象透传（`{...field}`），`width` 自动跟随；
  - 若是显式挑选字段映射，则必须显式补 `width`。
  - 参照 AP-44 纪律：虽然 `width` 不是 `field_type`，但它是一个**新的字段配置键**，需保证 enrich / normalize / snapshot 全链路不丢。

## 4. 编辑入口：FieldConfigTable 新增「宽度」列

- 文件：`cpq-frontend/src/pages/component/FieldConfigTable.tsx`
- 在字段配置表格中新增一列「宽度」，每行渲染一个控件组：
  - `InputNumber`，后缀 `px`，绑定 `field.width`，留空表示用默认 120。
  - 紧邻三个快捷按钮：**窄(80) / 中(120) / 宽(200)**，点击把对应值写入该行 `width`。
  - 通过现有 `updateField(key, { width })` 更新。
- 留空允许：用户清空输入框即恢复"未设置 → 默认 120"。

## 5. 就地预览：字段配置表下方横向预览条

- 位置：`FieldConfigTable` 渲染的表格**下方**。
- 内容：遍历当前 `fields`，按 `field.width || 120` 横向排成一行模拟"列头"，每格：
  - 宽度 = `width || 120` px；
  - 显示字段名（`label || name`，空名占位）+ 宽度数值。
- 实时性：随字段配置 state 变化即时重渲染（纯受控渲染，无需额外请求）。
- 目的：让用户一眼看出各列宽度比例与整体排布，接近报价单真实列排列。

## 6. 渲染落点（报价单 + 核价单；详情页 + 编辑页）

经核查，编辑页报价单与核价单是**同一个 `ProductCard` 组件**，靠 `cardSide='QUOTE'/'COSTING'` 区分；详情页是 `ReadonlyProductCard`。两处字段表头同款。

- **编辑页**：`cpq-frontend/src/pages/quotation/QuotationStep2.tsx:2179`
  字段 `<th>` → `style={{ width: w, minWidth: w }}`，其中 `w = field.width || 120`。报价单 / 核价单共用此处，一改两生效。
- **详情页**：`cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx:449`
  字段 `<th>` → 同样 `style={{ width: w, minWidth: w }}`。
- 系统列（料号 / 父料号 / 版本 / 操作列）保持现状，不加 / 不改宽度。

> 说明：手写 `<table>` 下，给 `<th>` 设 `width` + `minWidth` 即可约束列宽；如发现内容撑列需进一步控制，可在实现阶段配合 `table-layout` / `td` 处理，但默认先用 `<th>` 宽度落地，避免过度改动。

## 7. 不在范围内

- Excel 视图（`ExcelView.tsx`）、各列表页、系统列宽度均不改。
- 不做"修改某档位像素即全局联动所有字段"的能力（方案 A 已排除，只存独立 px）。

## 8. 验证

- 前端编译自检：
  - `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误。
  - 对改动的 `.tsx`（FieldConfigTable / QuotationStep2 / ReadonlyProductCard）逐一 `curl` Vite → HTTP 200。
- 协议级 E2E（因触碰 `QuotationStep2.tsx` / `ReadonlyProductCard.tsx` / `component/types.ts` / `FieldConfigTable.tsx`，属 AP-44 / 渲染协议相关）：
  - 跑 `quotation-flow.spec.ts`，必须 `passed`、`'加载中' final count = 0`、各 Tab 渲染正常 —— 证明新增 `width` 字段键未破坏 enrich / snapshot 链路。
- 功能验收：
  - 字段配置改宽度 → 预览条实时变化；
  - 报价单编辑页 / 详情页、核价单编辑页 / 详情页对应列宽按设置生效；
  - 未设置宽度的存量字段按 120px 渲染。

## 9. 开发流程

- 按 CLAUDE.md：用隔离 worktree 分支开发，默认走 subagent-driven-development，完成后用户确认 → 自动合并清理。
- RECORD.md 记录本次改动。
