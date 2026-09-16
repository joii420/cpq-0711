# 前端任务分解 · task-260915 组件导出导入往返保真

> 🚫 **只按本文件做。** AC 原文在 `需求文档.md`，本文件只标 AC 编号不复制原文。
> 接口契约以 `api.md` 为准。**契约若有疑问，停下报主线，不要自己猜或自己改后端。**

---

## 本次前端改动面

**只改一个文件**：`cpq-frontend/src/pages/component/ComponentImportDrawer.tsx`（现 367 行）。

**不新增页面、不新增弹层、不改布局结构** —— 只在既有的 `Descriptions` 与表格里增加展示项，复用现有 `Tag` / `Alert` / `Table` 模式。
⇒ 按 `docs/rules/frontend.md §1.3` 判定**不触发 HTML 原型图要求**。

⚠️ 若实现时发现必须新增独立区块或改变布局结构，**停下报主线补原型图**，不要自行扩大改动。

---

## F-1 · 导入预览展示「配置器坐标」解析结果

**服务的 AC**：AC-12, AC-17

后端 `ComponentPlan` 新增 `builderCoord` 字段（契约见 `api.md`），取值 `NOT_BUILDER` / `RESOLVED` / `UNRESOLVABLE`。

1. 在组件计划表格（`planColumns`，现约 `:187` 附近有 `SQL视图` 列）后增加一列「配置器」，宽度 100，居中
2. 用 `Tag` 渲染，配色与既有 `UNRESOLVABLE: { color: 'red' }` 风格一致：
   - `RESOLVED` → 绿色 Tag，文案「可解析」
   - `UNRESOLVABLE` → **橙色**（`orange`）Tag，文案「需重绑」，`Tooltip` 显示后端给的原因
   - `NOT_BUILDER` → 不渲染 Tag，显示 `—`
3. 🚫 **`UNRESOLVABLE` 不得阻断提交** —— 它不进 `blockers`，不参与 `canCommit` 判定，**不要**照抄公式绑定那边 `unresolvableBlock` 的拦截逻辑（现约 `:151`）。这两个 `UNRESOLVABLE` 是不同语义的同名值，**别混用**。

> ⚠️ 这是本任务前端最容易写错的一点：文件里已有一个 `UNRESOLVABLE` 会拦提交（公式绑定的），新加的这个**不拦**。

---

## F-2 · 老包版本提示

**服务的 AC**：AC-10, AC-11

既有 `Descriptions` 已展示 `bundle 版本`（现约 `:278`）。

1. 当 `preview.bundleVersion` 不是 `"1.1"` 时，在该项值后面追加一个橙色 `Tag`「旧格式」
2. 同时在预览区顶部显示一条 `Alert type="warning"`：说明旧格式包不含取数配置器信息，取数配置器建的树页签组件可能导入失败或导入后无法编辑，建议在源库升级后重新导出
3. 🚫 **不要阻止提交** —— 老包仍然可以导入（AC-10）

---

## F-3 · 前端强制自检

**服务的 AC**：全部

按 `docs/rules/frontend.md` 跑完并在回报里逐条附**命令与其输出**：

- `npx tsc --noEmit` **0 错误**（⚠️ 要在 worktree 内跑，且 `node_modules` 需软链；见 `CLAUDE.md` 记载的 worktree 前端自检坑）
- 导入抽屉页面在 dev server 能正常打开（附 HTTP 状态码）
- 用 `素材/用户原始导出包-bundleVersion1.0.json` 实际走一遍预览，**附截图或 DOM 断言**，确认「旧格式」提示出现
- 🚫 **不许只写「已自检 ✅」** —— 没有命令输出的自检声明视为未自检

---

## 回报要求

1. 每个 `F-x` 逐项回报：改了哪些行、实测结果
2. 途中发现契约与实际响应对不上 → **停下报主线**，不要自己改后端，也不要在前端兜底掩盖
3. 🚫 不要提交 git，不要合并分支 —— 由主线统一处理
