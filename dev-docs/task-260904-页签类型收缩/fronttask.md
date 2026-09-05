# 前端任务分解 · task-260904-页签类型收缩

> 只按本文件做。AC 原文在 `需求文档.md §③`，此处只标编号不复制原文。接口契约以 `api.md` 为准。
> 🎨 **原型图是验收基准**：`原型图/` 下的 HTML 定稿后 1:1 还原，偏差必须逐条报出。

## 阶段划分

同后端：**第一阶段**（F-1 ~ F-6）不动 `tabType` 字段本身；**第二阶段**（F-7）随删列，需用户单独批准。

---

## 第一阶段

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **F-1** | AC-1, AC-2, AC-3, AC-19 | `SqlViewBuilderTab.tsx:1020` 的页签类型 `<Select>` 与费用类才出现的「数据来源」`<Select>` **合并为一个「数据源」下拉**：选项来自 `fieldTree.availableSources`，`label` 显示数据源名（如「物料BOM」「自制加工费」），选中后把该项的 `(tabType, variantKey)` 原样回传给 `fetchFieldTree` / `compile`。<br>🚫 **前端不再持有 6 个页签类型的硬编码常量**（现有 `TAB_TYPES` / `TAB_TYPE_LABEL` 随之清理） |
| **F-2** | AC-1, AC-2, AC-3 | 按 `availableSources[].semantic` 分支渲染：<br>· `TREE` → 显示树形只读提示（该页签不参与回填）<br>· `MATERIAL_ELEMENT` → 显示价格策略原子组（`groupKind='PRICE'`）<br>· `null` → 两者都不显示<br>🚫 **不得按 label / sourceKey 硬编码判断语义** |
| **F-3** | AC-1, AC-2 | 数据源选中后，其语义（BOM 树 / 材质元素 / 普通）以**只读回显**呈现，用户不可改 |
| **F-4** | AC-15 | `ComponentManagement.tsx:768` 的页签类型纯展示：值改为由组件绑定的数据源推导；`:1467` 的「需配置料号列或名称列」校验文案随 B-11 的新判定口径调整 |
| **F-5** | AC-6, AC-7 | 加叶子弹层新增两个拒绝态的展示：`LEAF_PART_NOT_IN_MASTER`（点名料号 + 提示去建档）、`LEAF_CYCLE_DETECTED`（**展示环路径**）。两者都不得只弹一个通用的"操作失败" |
| **F-6** | AC-10 | 状态连续性：切走到别的 Tab 再切回、刷新整页、重新打开组件后，数据源选择与已选列均正确保留，无 JS 报错 |

## 第二阶段（需用户单独批准后才能做）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **F-7** | AC-16 | 移除前端对组件 `tabType` 字段的全部引用（`types.ts` / `componentDraft.ts` / `ComponentPalette.tsx` / `FieldConfigTable.tsx` / `FormulaBuilder.tsx` / `TabJoinFormulaDrawer.tsx` / `crossTabText.ts` 及相关测试） |

---

## 涉及的页面（= 原型图份数，`frontend.md §1.3`）

| # | 页面 / 状态 | 原型 | 由哪些 F-x 覆盖 |
|---|---|---|---|
| 1 | 取数配置器「取数配置」Tab | `原型图/取数配置Tab.html` | F-1, F-2, F-3, F-6 |
| 2 | 组件管理（页签类型展示位） | `原型图/组件管理.html` | F-4 |
| 3 | 加叶子弹层的拒绝态 | `原型图/加叶子拒绝态.html` | F-5 |

---

## 强制自检（`frontend.md` + `CLAUDE.md` §6.1）

- [ ] `tsc` 0 错误
- [ ] 页面在 dev server 返 200；F12 Console 0 报错
- [ ] **逐屏比对实现与原型图**，偏差逐条列出（只允许"组件库能力所限的等价实现"）
- [ ] 原型份数 = 本任务动到的页面数（3 份 + `index.html` 导航）
- [ ] 空数据 / 禁用态 / 最长文案三种状态都画了、也都对上了
- [ ] 「完成」宣告必须带 §6.1 的「已自检」声明行
