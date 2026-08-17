# fronttask · 核价导入停用四个 Sheet

> 上游依据：本目录 `需求文档.md`（FR-8 / AC-11）。接口契约无变化，见 `api.md`。

---

## 1. 本任务前端改动面判定

**前端有改动，但仅限文案，不涉及组件结构、状态管理、接口调用。**

之所以仍要写这份文档（而不是"前端无改动"一句带过）：前端硬编码了三处「24 Sheet」口径与一句仅适用于已停用 Sheet 的说明。后端收敛到 20 Sheet 后，这些文案会变成**持续误导业务的错误口径**（业务照着"24 Sheet"去准备 Excel、照着说明去填客户对应关系页，填了却不生效）。文案在这里不是装饰，是业务的唯一可见契约。

---

## 2. 页面/组件清单

| 文件 | 组件 | 改动性质 |
|---|---|---|
| `cpq-frontend/src/pages/master-data/PricingBasicDataImportDrawer.tsx` | 核价基础数据导入抽屉 | 文案 ×4 处 |

无其他前端文件改动。已核查：全前端仅此一个文件出现这些字符串。

---

## 3. 逐处改动点

| # | 行（现状） | 现内容 | 改为 |
|---|---|---|---|
| F-1 | 52 | `/** 下载 24 Sheet 空模板（task-0728 · A4）。 */` | `/** 下载 20 Sheet 空模板（task-0728 · A4；task-0812 停用 4 个 Sheet 后由 24 收敛为 20）。 */` |
| F-2 | 116 | `title="核价基础数据导入 (V6 · 24 Sheet)"` | `title="核价基础数据导入 (V6 · 20 Sheet)"` |
| F-3 | 142 | `核价基础数据为全局数据，无客户上下文。\`宏丰-客户料号对应关系\` Sheet 的 customer_no 从 Excel 行读取。` | `核价基础数据为全局数据，无客户上下文。请使用下载的最新模板（20 Sheet）导入。` |
| F-4 | 159 | `hint="24 Sheet 核价基础数据 / 单文件"` | `hint="20 Sheet 核价基础数据 / 单文件"` |

### F-3 的措辞理由

原句的**全部信息量**都在讲「宏丰-客户料号对应关系」这个已停用 Sheet 的 customer_no 取值方式 —— 停用后整句失效。但"核价基础数据为全局数据、无客户上下文"这半句仍然成立且有价值（它解释了为什么这个导入抽屉不要求先选客户），因此保留前半句、替换后半句为引导用最新模板的提示（承接需求文档 R-3：存量旧模板的 4 页会被静默跳过，需要一处告知）。

---

## 4. 交互流程

**零变化。** 上传 → 提交 → 结果表格渲染，全流程不动。

唯一可观测差异：结果表格 `dataSource={result.sheetResults}` 的行数由 24 变 20（后端收敛，前端零改动即自动生效）。表格列定义（`Sheet` / 成功比 / 错误）、`rowKey="sheetName"` 均不动。

---

## 5. 状态管理与缓存 key

**零变化。** 本组件只有 `fileList` / `result` / `submitting` / `downloading` 四个局部 `useState`，无全局 store、无缓存 key、无 driver expansion、无公式缓存。

---

## 6. 调用哪些接口

见 `api.md`：

- **API-1** 核价基础数据导入 —— 请求与响应结构均不变，前端零适配
- **API-2** 核价空模板下载 —— 返回 blob，前端零适配

**前端不需要按 Sheet 名做任何判断或过滤** —— 不得在前端硬编码停用清单去过滤 `sheetResults`。停用是后端单一权威，前端只渲染后端给什么。

---

## 7. 边界与空态

| 场景 | 期望 |
|---|---|
| 用**旧模板（24 Sheet）**导入 | 正常导入，结果表格 20 行，那 4 页不出现、不报错（需求文档 D-4） |
| 用**新模板（20 Sheet）**导入 | 正常导入，结果表格 20 行 |
| 导入部分失败 | 现有 `PARTIAL` 分支不变 |
| 结果为空 / 接口异常 | 现有 `message.error` 分支不变 |

---

## 8. 自检项

1. `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → **0 错误**
2. `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/master-data/PricingBasicDataImportDrawer.tsx` → **200**
   - ⚠️ 共享 dev server(5174) 服务的是**主工作区**代码，不是 worktree。worktree 内自检需软链 `node_modules` + 另起临时端口，或在合并后于主工作区复验（见 `cpq-worktree-frontend-selfcheck`）
3. 全文件搜索确认残留为零：`/usr/bin/grep -a -n "24 Sheet\|宏丰-客户料号对应关系" PricingBasicDataImportDrawer.tsx` → **无输出**
4. **是否触发 E2E**：**否**。本次未触碰 `useDriverExpansions.ts` / `usePathFormulaCache.ts` / `QuotationStep2.tsx` / `QuotationWizard.tsx` / `ReadonlyProductCard.tsx` / `BulkImportPartsDrawer.tsx` / `component/types.ts` / `component/FieldConfigTable.tsx` 中任何一个，也不涉及字段类型变动（AP-44 未命中），故不强制跑 Playwright
5. 人工目视：打开「主数据维护 → 核价基础数据导入」抽屉，确认标题与上传提示均显示 20 Sheet，说明文字已更新

---

## 9. Task 列表（逐项可勾选）

- [ ] **T-F1** 改 4 处文案（F-1 ~ F-4）
- [ ] **T-F2** `tsc --noEmit` 0 错误
- [ ] **T-F3** Vite transform 该文件 200
- [ ] **T-F4** grep 确认 "24 Sheet" 与 "宏丰-客户料号对应关系" 残留为零
- [ ] **T-F5** 抽屉目视复验（截图留档，供 `test-report.md` 引用）
