# task-260907 · 前端任务分解

> **前端只按本文做。** AC 原文在 `需求文档.md` §3，接口契约在 `api.md`，视觉基准在 `原型图/` —— 本文**只标 AC 编号不复制原文**。
> **开工前必读**：`docs/rules/frontend.md`（§1.1 抽屉替代弹窗 / §1.2 工具栏动作 + `enabledWhen` / §1.3 原型 1:1 还原 / 强制自检）。

---

## 0. 三条不可越界

1. 🚨 **`DatasetImportDrawer.tsx` 一个字节不改。** 实测它被两处共用：`QuotationList.tsx:328`（本任务要摘）和 `DatasetPartListTab.tsx:185`（**基础资料维护，不能碰**）。<br>⚠️ **主线原在 F-9 里写过一个不存在的文件名 `SheetPartDrawer`** —— 那是 `task-260902` 交接说明里的**计划命名**，落地后实际叫 `DatasetSheetDrawer.tsx` / `PartCostingDrawer.tsx`。已订正，别再照旧名找。本任务**新建**一个建单专用抽屉，🚫 不要在它身上加 `mode` / `requireCustomer` 之类的开关 —— 那等于把两条路径的演进绑死。
2. 🚫 **视觉基准是 `原型图/`，不是审美，也不是「差不多」。** 允许的偏差只有一类：组件库能力所限的等价实现，且要在回报里列出来。原型没画到的地方**停下来问**，那是需求缺口。
3. 🚫 **不要执行 `git commit`**，提交由主线统一做。
4. ⏸ **F-1（摘旧按钮）标 P2，排在 S-5（11 页签模板）落地之后**，与后端 B-10 同批。<br>**理由**：新模板未到位前，新链路建出的单渲染不出费用类页签；此时摘掉旧入口，用户只剩一条没通的路。<br>📌 D-24 之后 S-7 不在本期 ⇒ 旧模板与存量单仍在，**晚做代价接近零**。<br>✅ **F-2（新按钮接新抽屉）不受此约束** —— 它是加法，两个按钮并存期正是我们要的过渡态。<br>🚫 其余 8 个前端项正常并行。

---

## 1. 报价单列表页

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **F-1** ⏸**P2** | AC-13 | ⏸ **排在 S-5 模板落地之后**（与后端 B-10 同批，见 §0 第 4 条）。`QuotationList.tsx`：移除「从基础数据导入」按钮（`:279` 一带）及其 `basicImportOpen` 状态、`QuoteBasicDataImportV6Drawer` 的 import 与渲染（`:~320`）。<br>工具栏最终顺序照 `原型图/01-报价单列表.html`：`[搜索框] [导入历史] [导入报价数据] [新建报价单]`。<br>⚠️ **顺手确认 `canImportQuoteDataset` 权限判据没被误删** —— 两个按钮同处一个 toolbar，摘一个容易连带 |
| **F-2** | AC-13, AC-19 | 「导入报价数据」按钮改为打开**新的建单抽屉**（F-3），不再是 `DatasetImportDrawer`。<br>权限判据改按 `api.md §1` 的角色（`SALES_REP`/`SALES_MANAGER`/`SYSTEM_ADMIN`），🚫 不再用 `PRICING_MANAGER`。无权限时**禁用但可见 + hover tooltip 说明原因**（`frontend.md §1.2`），文案照 `原型图/01-报价单列表.html` 的禁用态 |

---

## 2. 建单专用导入抽屉（新建）

**文件**：`src/pages/quotation/QuotationDatasetImportDrawer.tsx`（放报价单目录下，与 `QuoteBasicDataImportV6Drawer.tsx` 同级 —— 它是被替代者，形态可参考）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **F-3** | AC-1, AC-2 | **Step 1 · 选客户 + 上传**，照 `原型图/02-导入抽屉-Step1-选客户上传.html`：<br>① 客户 `Select`（**必选**），默认值取列表页传入的 `defaultCustomerId`（可为空，让用户自选）；<br>② 未选客户时**上传按钮禁用 + tooltip「请先选择客户」**（原型已画禁用态）；<br>③ 选好客户 + 选好文件后调 `POST /dataset/quote/quotation-import`（`multipart`，字段 `customerId` + `file`）。<br>🚫 **前端不许自己放行空客户** —— 后端也校验，但前端禁用是第一道（AC-2 断言两者都成立） |
| **F-4** | AC-5, AC-20 | **导入中 · 轮询**，照 `原型图/03-导入抽屉-导入中.html`：<br>① 拿到 `importRecordId` 后轮询 `GET /dataset/quote/quotation-import/{recordId}`；<br>② 渲染 `progress.{done,total,current}` 为进度条 + 「正在处理：物料与元素BOM」文案；<br>③ **允许用户关闭抽屉**（AC-5）—— 关闭后置位标志，轮询完成不再自动跳转，避免「已关闭却被跳走」；<br>④ 轮询间隔与超时上限由实现定，但**必须有上限**并在超时后给出可重试入口，🚫 不许无限轮询 |
| **F-5** | AC-4 | **校验失败态**，照 `原型图/04-导入抽屉-校验失败.html`：<br>`status=FAILED` 时渲染 `errors[]` 表格（sheet / 行号 / 列名 / 值 / 原因），**全部错误滚动可见**，顶部标明「本次未写入任何数据」。<br>⚠️ 错误可能上百条 —— 表格要能滚动，🚫 不要只显示第一条 |
| **F-6** | AC-1 | **Step 2 · 选模板与分类**，照 `原型图/05-导入抽屉-Step2-选模板建单.html`：<br>① 进入 Step 2 时调 `GET /templates/auto-defaults?customerId=...` 自动带出报价模板 / 核价模板 / 产品分类；<br>② **只在首次进入时带出** —— 用户手改后「上一步/下一步」来回切不许覆盖他的修改（V6 抽屉已有此行为，照抄）；<br>③ 报价单名称输入框，必填 |
| **F-7** | AC-1, AC-20 | **建单 + 物化轮询**，照 `原型图/06-导入抽屉-建单物化中.html`：<br>① 调 `POST /dataset/quote/create-quotation`；<br>② 响应 `materializing=true` ⇒ 进入「正在计算」只读态，轮询既有 `POST /quotations/{id}/ensure-card-values`；<br>🚫 **必须据 `materializing` 字段判断要不要轮询**，不许靠 `cardValuesReady==false` 猜（分不清「真失败」和「还没开始算」）；<br>③ 物化完成 → `navigate('/quotations/{id}/edit?...')`；<br>④ 提供**重试**入口：`quotationId` 已创建时**不重新 POST create-quotation**，直接重进轮询 |
| **F-8** | AC-18 | **空态与极值**，照 `原型图/07-导入抽屉-空态与极值.html`：<br>① 所选客户在 `ds_quote_customer_part` 中 0 行 ⇒ Step 2 给明确提示「该客户在本次导入数据中没有客户料号，建单后将没有产品行」，**允许继续**（AC-18 要求建单成功、明细行为 0，不是白屏不是 500）；<br>② 最长文案：客户名 / 文件名 / 料号 / sheet 名超长时截断 + `title` 悬停全文，🚫 不许撑破布局 |

---

## 3. 零改动确认（不是没活，是要验证「确实没被连累」）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **F-9** | AC-15 | **维护页签零回归确认。** 本项不产出新代码，产出的是回归证据。<br>🚨 **判据已于 2026-09-07 改写**（原写「这几个文件 `git diff` 为空」，**该判据已失效**）：并发任务 `task-260907-移除料号核价功能` 的闸门 A0 裁决要把 4 个公共件 **`git mv`** 出去 —— `master-data/part-costing/{EditableSheetTable.tsx, SheetPartListTab.tsx, types.ts, api.ts}` → `master-data/shared/`（其中 `api.ts` 改名 `sheetApiFactory.ts`），并删掉 `part-costing/` 目录。主线已实查确认：这 4 个文件现确在 `part-costing/`，dataset 侧有 **6 处 import** 指过去（`dataset/types.ts:11` · `DatasetSheetDrawer.tsx:22,23` · `dataset/api.ts:9,10` · `DatasetPartListTab.tsx:16,17` · `PlatingSchemeTab.tsx:20`）。⇒ 对方合并后 `git diff` **必然非空，且原因与本任务无关**。<br>**改用 A/B 内容对照**（对方 AC-16 同款判据，已确认可复用）：四个公共件的 diff **只允许三类** —— ① import 路径 ② 文件头注释 ③ `sheetApiFactory.ts` 里 legacy 段整体删除（`BASE` / legacy 实例 / `listParts`·`getSheets`·`getOverview`·`getRows`·`getVersions`·`saveRows`·`lookup` 七个具名导出 / `PartSortBy`）。**函数体 / JSX / props 定义 / 类型字段名 / 样式一个字节不改**；尤其 `createSheetApi` 工厂函数体零改动 ⇒ 它生成的 URL 形状不变 ⇒ 三个 dataset 页签的契约零变更。出现第四类差异即判失败并报主线。<br>**手工回归清单**（与文件路径无关，照跑）：【基础资料维护】→ 报价数据 tab：列表分页/排序/过滤 · 点行开抽屉 · 切 tab · 切版本 · 编辑保存（三态 `UNCHANGED`/`UPGRADED`/`CREATED`）· 该页自己的导入 · 【导入历史】页能列出 `system_type=DATASET_QUOTE` 的新记录。<br>⚠️ **本任务自己不许动这 4 个公共件**，也不许动 `DatasetImportDrawer.tsx` / `DatasetPartListTab.tsx`（§0 第 1 条）|
| **F-10** | AC-9, AC-10 | **编辑与提交链路零改动确认。** 本项不写代码，产出证据：<br>① AC-9 序列 —— 用新建的报价单在「物料BOM」页签改一格 → 保存 → **切走再切回** → **刷新页面**，三个时点值一致且为改后值，页签合计同步；<br>② AC-10 —— 提交核价后 `status=SUBMITTED`、`submission_snapshot` 非空、`quotation_component_sql_snapshot` 落 11 段 SQL。<br>📌 这两条走的全是既有链路（`QuotationStep2.tsx` / `saveDraft` 三数组协议 / 提交冻结），本任务一行不改 —— **但「没改」不等于「还能用」**：模板全换成配置器组件后，编辑链路是否仍正常，只能实测。<br>⚠️ 若发现必须改 `QuotationStep2.tsx`，**停下报主线**（`fronttask.md §4` 的前提不成立） |

---

## 4. 为什么这些没做（避免留空槽）

| 未做项 | 理由 |
|---|---|
| 不改 `QuotationStep2.tsx` / `ReadonlyProductCard.tsx` / `useDriverExpansions.ts` | 本任务**不动渲染层**。新模板的页签由取数配置器生成的 `$view` 驱动，走的是既有 DATA_SOURCE 渲染路径，无新 `field_type` ⇒ **不触发 AP-44 字段类型联动协议**。<br>⚠️ 若开发中发现必须改这三个文件之一，**停下报主线** —— 那意味着「渲染层零改动」这个前提不成立，范围要重估 |
| 不删 `QuoteBasicDataImportV6Drawer.tsx` 文件本身 | 只摘引用（N-7 口径：代码保留）。保留文件便于对照其两步式状态机 |
| 不做导入历史页改动 | 新导入写的 `import_record.system_type='DATASET_QUOTE'` 已被该页现有逻辑覆盖（`task-260902` 已验证）。**F-9 的回归清单里顺带看一眼该页能列出新记录** |
| 不做 E2E | 归测试工程师（`test.md` §4），不在前端任务里 |

---

## 5. 强制自检（回报里必须带这一行，没有 = 未完成）

```
tsc --noEmit 0 错误 ✅；<改动的每个页面> dev server 200 ✅；无红色遮罩 ✅；
逐屏比对原型图 7 份，偏差 N 处（逐条列出，只允许「组件库能力所限的等价实现」）✅
```

⚠️ **共享 5174 端口服务的是主工作区，不是 worktree。** worktree 内自检要：软链 `node_modules` + 另起临时端口的 vite + `grep` 内容确证改动确实生效（`RECORD.md` 有实证教训）。

---

## 6. 反向覆盖自查（每个 F-x 都指回了 AC）

| F-x | 指回 |
|---|---|
| F-1 | AC-13 |
| F-2 | AC-13, AC-19 |
| F-3 | AC-1, AC-2 |
| F-4 | AC-5, AC-20 |
| F-5 | AC-4 |
| F-6 | AC-1 |
| F-7 | AC-1, AC-20 |
| F-8 | AC-18 |
| F-9 | AC-15 |
| F-10 | AC-9, AC-10 |

✅ 10 项全部有指向，无超范围项。
