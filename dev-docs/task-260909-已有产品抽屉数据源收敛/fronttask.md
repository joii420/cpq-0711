# 前端任务分解 · task-260909 已有产品抽屉数据源收敛

> 执行方：`cpq-frontend`。契约以 `api.md` 为准，AC 原文在 `需求文档.md §③`（本文件**只标编号不复制原文**）。
> 🎨 **视觉基准是 `原型图/` 下的 HTML，不是你的想象**。闸门 A 已定稿，实现须 1:1 还原，偏差逐条报。
> 🚫 遇 `CLAUDE.md §3.2` 不可逆操作红线立即停下报主线。

---

## F-1 · 移除 3D 预览区

**服务的 AC**：AC-7, AC-8, AC-11, AC-12

> AC-8 / AC-11 / AC-12 是本项的**副作用边界**：删预览区不许连累 3D 管理页（AC-8）、
> 不许破坏「加入报价单」下游（AC-11）、改完布局后空态仍须是 AntD 标准空态（AC-12）。
> 🚫 这三条不是「别人的回归」—— 是**你这一刀切下去必须自己保住的东西**。

**文件**：`cpq-frontend/src/pages/quotation/AddProductModal.tsx`

**改法**：

1. 删除 `renderPreviewBox()` / `renderPreviewCap()` 两个函数及其在 JSX 中的挂载点
2. 删除相关 state：`preview` / `previewLoading` / `zoomHint`
3. 删除拉取 3D 的 `useEffect`（随 `activeRow` 切换调 `modelConfigService.current` 的那个，含 `AbortController`）
4. 删除 `import { modelConfigService }` 与 `import type { ModelConfigDTO }`
5. 抽屉布局：原左右分栏改为**表格占满全宽**。🚫 **不要调整抽屉宽度**（预览区腾出的空间正好给新增的图号列）

⚠️ `activeRow` state **保留** —— 它还承担行选中态。只是不再用于驱动 3D 预览。

🚫 **不要动** `cpq-frontend/src/services/modelConfigService.ts`、`types/modelConfig.ts`、`pages/config/ModelConfigManagement.tsx` —— 3D 模型管理功能本身不在本次范围内（AC-8 会回归它）。

---

## F-2 · 表格列调整

**服务的 AC**：AC-3, AC-4, AC-4b, AC-5, AC-5b, AC-6, AC-10, AC-13

**文件**：同 F-1

**目标列构成**（从左到右，共 7 列）：

| # | 列标题 | dataIndex | 说明 |
|---|---|---|---|
| 1 | 来源 | `source` | Tag 渲染，逻辑不变：`CONFIGURED`→紫色「选配」(+`·单件`/`·组合`)，否则灰色「已有」 |
| 2 | 客户产品编号 | `customerProductNo` | **渲染逻辑整段保留不动**（含「等 N 个」Tag 与三条实现纪律） |
| 3 | **客户图号** | `customerDrawingNo` | 🆕 新增，空值渲染 `—` |
| 4 | 客户物料名 | `customerMaterialName` | 空值渲染 `—` |
| 5 | 销售料号 | `materialNo` | 不变 |
| 6 | 品名 | `productName` | 空值渲染 `—` |
| 7 | 规格 | `spec` | 空值渲染 `—` |

🚨 **第 2 列的「等 N 个」渲染逻辑一个字都不要改** —— 那段带三条实现纪律的注释（回退链 / 单编号视觉零变化 / 标记必须活过列宽截断）是 task-260902 AC-12b⑤-b 的交付物，AC-13 仍在验它。

---

## F-3 · 类型定义同步

**服务的 AC**：AC-4, AC-7

**文件**：`cpq-frontend/src/types/existingProduct.ts`

**改法**：

1. 删除 `has3d: boolean;` 与 `thumbnailUrl?: string | null;`
2. 新增 `customerDrawingNo?: string | null;`
3. 更新 `productName` / `customerMaterialName` 的注释——它们不再同源，语义见 `api.md §1.3`
4. 更新文件头那行注释：`// 数据源 material_customer_map…` **已经过期**，改为单表 `ds_quote_customer_part`

⚠️ 必须与后端 `ExistingProductDTO`（`B-3`）严格一致。🚫 你不要去改后端文件。

---

## F-4 · 订正过期注释

**服务的 AC**：AC-17

**文件**：`cpq-frontend/src/services/quotationService.ts`（约 272 行）

`listExistingProducts` 的 javadoc 现写「数据源 material_customer_map」，**已过期**。改为说明单表 `ds_quote_customer_part` + `source` 标签语义。

> 本任务的根因之一就是「注释描述的是上一个时代」，顺手订正是防止同一个病复发。

---

## F-5 · 前端自检

**服务的 AC**：全部前端 AC 的前置

按 `docs/rules/frontend.md` 跑完整自检，**「完成」宣告必须带「已自检」一行**（`CLAUDE.md §6.1`），至少含：

- [ ] `npx tsc --noEmit` **0 错误**（🚫 别用 grep 内容猜，`tsc` 才查真实文件）
- [ ] 页面能打开且**无红色遮罩**：抽屉打开 → 列表渲染 → 翻页
- [ ] 逐屏比对实现与 `原型图/`，偏差逐条列进回报（只允许「组件库能力所限的等价实现」这一类）
- [ ] F12 Network 确认**无** `/model-configs/current` 请求（连续切 5 行）
- [ ] 空态不是红色遮罩、不是永久「加载中…」占位（AP-31 族）

⚠️ **worktree 前端自检的坑**（`cpq-worktree-frontend-selfcheck` 教训）：共享的 5174 服务的是**主工作区**代码，不是你的 worktree。要验 worktree 的改动必须软链 `node_modules` + 另起临时端口的 vite，🚫 别对着 5174 验完就说通过。

⚠️ 探本机服务一律加 `--noproxy '*'`（本机 shell 常设 `http_proxy=127.0.0.1:7890`，直连 localhost 会走代理返 502）。

---

## 反向覆盖检查

| 任务 | 指回的 AC |
|---|---|
| F-1 | AC-7, AC-8, AC-11, AC-12 |
| F-2 | AC-3, AC-4, AC-4b, AC-5, AC-5b, AC-6, AC-10, AC-13 |
| F-3 | AC-4, AC-7 |
| F-4 | AC-17 |
| F-5 | 全部前端 AC 的证据来源 |

✅ 每项均可指回至少一条 AC，无超范围项。

---

## 🚫 本次前端明确不改的

| 文件 | 为什么不改 |
|---|---|
| `ConfigureProductDrawer.tsx`（选配添加抽屉） | 实测不消费 3D 字段（grep 零命中），与本次改动无交集。仅做冒烟回归 |
| `pages/config/ModelConfigManagement.tsx` | 3D 模型管理功能不在范围内，AC-8 会回归它 |
| `services/modelConfigService.ts` / `types/modelConfig.ts` | 同上，端点与类型都还有别的消费方 |
| `QuotationStep2.tsx` / `QuotationWizard.tsx` | 「加入报价单」下游链路不改，AC-11 做回归 |
| 抽屉宽度 / `PAGE_SIZE` | 移除预览区已腾出足够宽度；分页大小 20 不变 |
