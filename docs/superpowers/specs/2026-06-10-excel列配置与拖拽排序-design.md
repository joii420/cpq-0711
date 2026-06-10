# EXCEL 列配置收敛 + 字段/列拖拽排序 — 设计方案

- **日期**：2026-06-10
- **状态**：设计确认完成，可进 writing-plans
- **范围**：纯前端 2 文件（`ComponentManagement.tsx` 的 `ExcelColumnPanel` + `FieldConfigTable.tsx`），后端不动
- **相关基线**：`docs/反模式.md` AP-44（FieldConfigTable 协议敏感）、`docs/组件管理字段配置指南.md`、`docs/三大核心模块基线.md`
- **前序**：承接 2026-06-10「组件管理公式统一」重构（EXCEL 组件 + ExcelColumnPanel 由该重构 Task 5.1 引入）

---

## 1. 背景与问题

「组件管理公式统一」重构后，EXCEL 组件的列配置面板（`ExcelColumnPanel`）的「来源/公式」下拉提供 5 个选项（组件字段 / 变量 / 产品属性 / 固定值 / 页签连表公式），但**只有「页签连表公式」有配置入口**（`isFormula` 分支开 `TabJoinFormulaDrawer`）；选其余 4 种都没有任何地方填值或选目标 —— 即「固定值」无处填常量、「变量/组件字段/产品属性」无处选目标。

经确认：EXCEL 组件已与具体模板/报价脱钩，「产品属性 / 组件字段」这类上下文相关来源在独立组件层面语义不清且实际不需要；用户只需要 **固定值 + 页签连表公式** 两种。同时希望列定义行可**拖拽调序**，并把同样的拖拽体验用到字段配置表。

---

## 2. 决策清单（已逐项确认）

| # | 议题 | 决策 |
|---|------|------|
| 1 | EXCEL 列来源类型 | **只留 `FIXED_VALUE` + `TAB_JOIN_FORMULA`**，砍掉 `COMPONENT_FIELD` / `VARIABLE` / `PRODUCT_ATTRIBUTE`（仅删前端下拉选项）|
| 2 | 固定值形态 | **整列同一常量 + 纯文本输入框**，绑 `col.fixed_value`（后端 `ExcelViewService:390 col.get("fixed_value")` 原样渲染）|
| 3 | 新增列默认来源 | 由 `COMPONENT_FIELD` 改为 **`FIXED_VALUE`** |
| 4 | 保存校验 | **宽松**：允许固定值留空 / 公式未配，空配置列渲染空白，**不拦截保存** |
| 5 | 排序交互 | **拖拽排序**（`@dnd-kit/sortable` 已装 `^10.0.0`），非 ↑↓ 按钮 |
| 6 | 排序应用范围 | **A. EXCEL 列**（ExcelColumnPanel 行）+ **B. 字段配置表字段行**（FieldConfigTable）；C 公式列表 / D 组件卡片 / E SQL 视图列表 **本次不做** |
| 7 | 后端 | **不动**：FIXED_VALUE / TAB_JOIN_FORMULA 已能正确渲染；砍掉的 3 种留在后端 switch 是无害死分支（EXCEL 组件本次新引入，几乎无用旧来源的存量列）|

---

## 3. 详细设计

### 3.1 Part A — ExcelColumnPanel（`ComponentManagement.tsx`）

现状：AntD `<Table>`，列「来源/公式」用 `Select`（5 选项）+ `isFormula = source_type==='TAB_JOIN_FORMULA' || !!col.formula` 决定是否显示公式按钮。

改造：
1. **来源 `Select` 选项收敛为 2**：`{ label:'固定值', value:'FIXED_VALUE' }`、`{ label:'页签连表公式', value:'TAB_JOIN_FORMULA' }`。
2. **新增列默认** `source_type: 'FIXED_VALUE'`（原 `'COMPONENT_FIELD'`）；`value={col.source_type ?? 'FIXED_VALUE'}`。
3. **配置区按 source_type 切换**（同一「来源/公式」单元格内）：
   - `FIXED_VALUE` → `Input`（size small），值绑 `col.fixed_value`，`onChange` 写 `update(idx, { fixed_value })`；占位「固定值（留空则空白）」。
   - `TAB_JOIN_FORMULA` → 保留现有「配置公式」按钮 + 表达式预览（开 `TabJoinFormulaDrawer`，`componentType='EXCEL'`，保存 `kind:'excel'` 写 `col.formula`）。
4. **切换来源时清理互斥字段**（避免脏数据）：切到 FIXED_VALUE 时不强制清 `formula`（无害，渲染按 source_type 走），但建议切换时把另一种的值置空（FIXED_VALUE→清 `formula`；TAB_JOIN_FORMULA→清 `fixed_value`），保持单元格语义干净。
5. **拖拽排序**（见 §3.3 通用模式）：拖动重排 `excelColumns` 数组顺序 = Excel 列渲染顺序，保存写回 `excel_columns` JSON。

### 3.2 Part B — FieldConfigTable 字段行（`FieldConfigTable.tsx`）

现状：AntD `<Table dataSource={fields} columns={columns}>`，通过 `onChange(newFields)` 回传给父组件随组件保存。

改造：
1. **拖拽排序**（见 §3.3）：拖动重排 `fields` 数组顺序 = 渲染时该组件页签的列先后；`onChange(reordered)` 走现有保存路径持久化。
2. **`sort_order` 同步**（实现期核实）：若快照/后端按 `field.sort_order` 排序而非数组下标，则重排时同步重写各字段 `sort_order = index`；若按数组下标（`sort_order` 仅缺省回填），重排数组即可。

### 3.3 通用拖拽模式（A/B 共用）

基于 AntD `<Table>` + `@dnd-kit`（`core` + `sortable` + `utilities` 均已装）的标准可排序行：
- 用 `DndContext`（`closestCenter`，`PointerSensor`/`KeyboardSensor`）包裹 `<Table>`，`SortableContext`（`verticalListSortingStrategy`）传入行 key 列表。
- 自定义 `components.body.row` = 一个 `useSortable` 行组件（`rowKey` 取稳定 id：EXCEL 列用 `col_key`，字段行用 `field.key`），含一个**拖拽手柄列**（`☰`/`HolderOutlined`，`{...listeners}`）。
- `onDragEnd`：用 `arrayMove(list, fromIndex, toIndex)` 重排，`fromIndex/toIndex` 按 `active.id`/`over.id` 在当前数组里 `findIndex`（**稳定 id 映射，非渲染下标**），回写数组。
- 拖拽手柄独立列，不影响行内其它输入控件交互。

> 抽成一个可复用的 `SortableTable`/`useSortableRows` 小工具供两处共用（A/B 行为一致、减少重复），或两处各自内联——实现期择简。

---

## 4. 实现期需核实（不影响方案形态）

1. 后端 Excel 视图渲染**按 `excel_columns` 数组顺序**出列；若依赖单独的 `sort` 字段，则拖动时同步重排 `sort`。
2. FieldConfigTable 保存确实按 `fields` 数组顺序 / `sort_order` 持久化（决定是否需同步重写 `sort_order`）。
3. AntD Table 行内已有可编辑输入控件时，dnd 手柄需限定在手柄列、`PointerSensor` 设激活距离（如 `activationConstraint: { distance: 5 }`）避免点输入框误触发拖拽。

---

## 5. 不在本次范围（YAGNI）

- 砍掉的 3 种来源类型在后端 switch 的清理（无害死分支，留着兼容历史）。
- 公式列表 / 组件卡片 / SQL 视图列表 的拖拽排序（决策 6：C/D/E 不做；组件卡片排序涉及 `sortOrder` 跨前后端持久化，单独评估）。
- 固定值的类型化/格式化/条件化（决策 2：纯文本足够）。
- 任何后端改动。

---

## 6. 验收要点

1. EXCEL 列「来源/公式」下拉只剩「固定值 / 页签连表公式」；新增列默认固定值。
2. 选固定值出现文本框、填的常量在报价/核价 Excel 视图整列每行原样渲染；留空渲染空白、保存不被拦截。
3. 选页签连表公式仍弹 `TabJoinFormulaDrawer` 正常配置（行为不变）。
4. EXCEL 列行、字段配置表字段行均可**拖拽调序**，顺序持久化并反映到渲染列序。
5. `tsc 0`；两文件 Vite 200。
6. **AP-44**：FieldConfigTable 字段行重排属协议级改动 → 跑 E2E 双 spec（`quotation-flow` + `composite-product-flow`），`'加载中' final=0`、关键 Tab 截图（composite 受既有 `composite_child_elements_mirror.unit_weight` 缺列 bug 影响，按既定结论看 SIMPLE 通过 + 截图）。
