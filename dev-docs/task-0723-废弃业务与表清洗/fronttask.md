# 前端任务文档 · 废弃业务与表清洗（task-0723）

> 隶属：`dev-docs/task-0723-废弃业务与表清洗`
> 契约以同目录 `api.md` 为准；审计事实以 `需求说明.md` §4 为准。
> 本任务前端以**删入口 / 删死代码**为主，无新增页面。后端是主战场。
> 开发在独立 worktree 分支进行。技术总监负责验收与合并，不代写代码。

---

## F0 · 开工前必读

### 前端在本任务里做什么

| 阶段 | 前端动作 |
|---|---|
| 1 | 摘「V5 增强导入」按钮（止血，1 行） |
| 2 | 删漂移横幅 + 刷新按钮 + mock；删料号版本抽屉 + 孤儿页 |
| 3 | 删 V5 导入向导 + import-session 相关组件/service |
| 4 | 删旧核价 5 个孤儿页 + service + 路由 |
| 5/6/7 | 无前端改动（纯后端 DDL）；但阶段 2/3/4 触碰 `QuotationStep2.tsx` → **强制 E2E** |

### 两条排查纪律

1. **`/usr/bin/grep -a`**，不用裸 `grep`（ugrep 把中文大文件静默当二进制返空，记忆 `cpq-grep-ugrep-binary-pitfall`）。
2. **删前端入口 → 后端删端点，先前端后后端**，避免"按钮还在但端点已 404"的中间态。同一分支内按阶段推进即可自然满足。

### 已核实的现状

| # | 事实 | 位置 |
|---|---|---|
| 1 | 「V5 增强导入」按钮 | `pages/importconfig/ImportHistoryList.tsx:146`（`:34` 注释、`:241` 向导挂载） |
| 2 | 漂移横幅 + 刷新按钮 | `QuotationStep2.tsx:3508`（横幅）/ `:3438`（刷新）/ `QuotationWizard.tsx:1205`（刷新） |
| 3 | 漂移 mock + 假数据 | `services/quotationDriftService.ts`（`MOCK_DRIFT_RESULT` + `VITE_USE_MOCK_DRIFT`） |
| 4 | 料号版本孤儿页 `/part-versions` | `router/index.tsx:137` → `pages/partversion/PartVersionPage.tsx`（0 navigate 入口） |
| 5 | 版本抽屉 | `components/PartVersionDrawer.tsx` / `pages/quotation/PartVersionDecisionList.tsx` / `services/partVersionService.ts` |
| 6 | 旧核价 5 孤儿页（菜单 0 命中，仅手敲 URL 可达） | `/costing-templates`→`CostingTemplateList`+`CostingTemplateConfig`；`/costing-part-data`→`CostingPartDataPage`；`/element-price-center`→`ElementPriceCenterPage`；`/part-versions`→`PartVersionPage` |
| 7 | V5/import-session 前端 | `BasicDataImportV5Wizard.tsx` / `BasicDataImportV5ToQuotation.tsx` / `OrphanRowsSection.tsx` / `CustomerConflictSection.tsx` / `services/importSessionService.ts` |
| 8 | `/costing-summary`（菜单「核价管理」）→ `CostingOrderListPage`（**财务工作台，活功能，保留**） | `MainLayout.tsx:74` / `router` |

---

## F1 · 阶段 1 · 摘「V5 增强导入」按钮（止血）

**文件**：`pages/importconfig/ImportHistoryList.tsx`

删除：
- `:146` 按钮本体「V5 增强导入」
- `:241` 向导挂载 `<BasicDataImportV5Wizard .../>`（若该向导仅此处用，随 F4 删除组件）
- `:34` 相关注释与 import

> 这是最快止血——切断唯一能往 `mat_*` 写脏数据的活跃 UI 入口。按钮无角色门恒显，任何销售点一下就写废弃表。

---

## F2 · 阶段 2 · 漂移横幅 + 刷新按钮 + mock 下线

### 删除

| 文件 | 删除内容 |
|---|---|
| `QuotationStep2.tsx` | `:3508` 附近漂移横幅渲染分支（`driftDetection?.hasDrift && (...)`）；`:3498-3499` 横幅文案拼接；`:3438` `refreshVersions` 调用与按钮 |
| `QuotationWizard.tsx` | `:1205` `quotationDriftService.refreshVersions(...)` 调用与相关 UI |
| `services/quotationDriftService.ts` | **整个文件删除**（含 `MOCK_DRIFT_RESULT` / `VITE_USE_MOCK_DRIFT` / `getMockDriftResult` / `refreshVersions`） |
| `types/quotation-drift.ts` | 确认无其他消费方后删除 |

### 与后端对齐

后端 `QuotationDTO.driftDetection` 字段会被删除或恒空（backtask B1）。前端删掉所有读 `driftDetection` 的地方，不要留悬空引用。

> ⚠️ `QuotationStep2.tsx` 是**协议级文件**（CLAUDE.md E2E 强制清单）→ **本任务强制 E2E**（F6）。

---

## F3 · 阶段 2 · 料号版本族 UI 下线

### 删除

| 文件 | 处置 |
|---|---|
| `pages/partversion/PartVersionPage.tsx` | 删除（孤儿页，`/part-versions` 0 navigate 入口） |
| `router/index.tsx:137` | 删 `part-versions` 路由项 + 对应 import |
| `components/PartVersionDrawer.tsx` | 删除 |
| `pages/quotation/PartVersionDecisionList.tsx` | 确认仅版本族用后删除 |
| `services/partVersionService.ts` | 删除 |
| `QuotationStep2.tsx` / `QuotationWizard.tsx` | 删报价内切版本入口（约 6 处，grep `PartVersion`/`partVersion` 定位） |

### 核查

```bash
/usr/bin/grep -a -rn "PartVersion\|partVersion\|part-version" cpq-frontend/src/
# 期望：删完 0 命中（除 part_version_locked 若在类型里作为字段名可保留）
```

---

## F4 · 阶段 3 · V5 / import-session 前端退役

### 删除

| 文件 | 处置 |
|---|---|
| `pages/quotation/BasicDataImportV5Wizard.tsx` | 删除 |
| `pages/quotation/BasicDataImportV5ToQuotation.tsx` | 删除（若 `QuotationCreateForm` 从中抽出且仍用，仅删 import-session 相关部分——见下） |
| `pages/quotation/OrphanRowsSection.tsx` | 删除（import-session 孤儿行 UI） |
| `pages/quotation/CustomerConflictSection.tsx` | 删除（import-session 客户冲突 UI） |
| `services/importSessionService.ts` | 删除 |
| `types/import-v6.ts` 中 import-session 相关类型 | 按需清理（保留 V6 正式导入类型） |

### ⚠️ QuotationCreateForm 边界核查

`QuotationCreateForm.tsx:2` 注释「从 BasicDataImportV5ToQuotation.tsx 抽出」——说明它可能仍在用。
- 用 `codegraph_impact` / `/usr/bin/grep -a` 确认 `QuotationCreateForm` 是否是活的报价创建入口
- **若是活的 → 保留 `QuotationCreateForm`**，只删它对 import-session 的依赖（若有）
- 不要因为名字带 V5 就误删活的创建表单

### 保留

- `QuoteBasicDataImportV6Drawer.tsx`（V6 正式导入，活功能）
- V6 导入相关 service/types

---

## F5 · 阶段 4 · 旧核价孤儿页下线

### 删除

| 文件 | 路由 |
|---|---|
| `pages/costing/CostingTemplateList.tsx` | `router:124` `costing-templates` |
| `pages/costing/CostingTemplateConfig.tsx` | `router:125` `costing-templates/:id` |
| `pages/costingpart/CostingPartDataPage.tsx` | `router:126` `costing-part-data` |
| `pages/element-price/ElementPriceCenterPage.tsx` | `router:132` `element-price-center`（若 task-0722 update-0724 已删则跳过） |
| `services/costingTemplateService.ts` | — |
| `router/index.tsx` | 删上述 4 条路由项 + 对应 import |

### ⚠️ 保留（活功能，误删即事故）

| 保留 | 原因 |
|---|---|
| `pages/quotation/CostingSheetView.tsx` | 虽 `costing-sheet` 后端端点下线，但先确认前端是否已不引用；若已 0 引用则可删，**删前 grep 核查** |
| 菜单 `/costing-summary` → `CostingOrderListPage` | 财务核价工作台，286 单活跃 |
| 比对视图相关页面（task-0717 交付） | 活功能 |

> ⚠️ **命名撞名**：删 `CostingTemplate*`（旧核价模板，17/17 零绑定）；**保留** `CostingOrder*`（财务工作台）。名字都带 costing，别连坐。

### 核查

```bash
/usr/bin/grep -a -rn "CostingTemplateList\|CostingTemplateConfig\|CostingPartDataPage\|costing-templates\|costing-part-data" cpq-frontend/src/
# 期望删完 0 命中
```

---

## F6 · 强制自检（缺任一项 = 未完成）

### TypeScript
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json     # 必须 0 错误
```
> 删了一批页面/service，会暴露悬空 import。逐个清干净，别留 `import X from '已删文件'`。

### Vite transform（每个改动的 `.tsx` 都要 200）
```bash
for f in \
  src/pages/importconfig/ImportHistoryList.tsx \
  src/pages/quotation/QuotationStep2.tsx \
  src/pages/quotation/QuotationWizard.tsx \
  src/router/index.tsx ; do
  printf '%s -> ' "$f"
  curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' "http://localhost:5174/$f"
done
```
> ⚠️ `--noproxy '*'` 必加（本机 http_proxy 返 502）；`tsc` 不覆盖 Vite 解析阶段错误，两步都跑。

### E2E —— 强制项（因为动了 `QuotationStep2.tsx`）
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
**判定 = A/B 同型对比，新增失败数 0，不是全绿。**
- 干净 master 已知恒 3 失败（夹具单缺产品分类，记忆 `task0712-update071501-category-axis`）
- 先在干净 master 跑一次记录基线，再在本分支跑，**逐条比对失败用例名**，不得把基线失败误归因为本次改动
- 同时确认 `'加载中' final count = 0`

### 下线路由不可达（手工）
浏览器直接访问以下 URL，应不可达（404 或空白）：
```
/part-versions
/costing-templates
/costing-part-data
/element-price-center（若本任务负责删）
```

### 保留功能巡查（回归）
```
报价单：新建 → 打开 → Step2 渲染正常（8 Tab 无「加载中」残留）→ 导出
核价单：财务工作台 /costing-summary 打开正常
比对视图：打开一张单的比对 Tab 正常
V6 导入：QuoteBasicDataImportV6Drawer 能正常导入
```

### 交付说明必含这一行
> "TS 0 错误 ✅；4 个改动 `.tsx` → Vite 200 ✅；E2E A/B 同型新增失败=0（基线3/本分支3，用例名逐条一致）✅；`'加载中' final count=0` ✅；下线路由 4 个全不可达 ✅；`PartVersion`/`ImportSession`/`BasicDataImportV5`/`CostingTemplate`/`quotationDriftService` 全工程 0 命中（`/usr/bin/grep -a`）✅；报价/核价/比对/V6导入四路回归正常 ✅"

**没有这一行的"完成"= 未完成。**

---

## 任务清单与阶段依赖

| 阶段 | 前端任务 | 规模 | 后端配对 |
|---|---|:--:|---|
| 1 | F1 摘 V5 按钮 | XS | 无（后端端点阶段3删） |
| 2 | F2 漂移UI下线 / F3 版本族UI下线 | M | B1/B3 |
| 3 | F4 V5/import-session 前端退役 | M | B5 |
| 4 | F5 旧核价孤儿页下线 | S | B6 |
| — | F6 自检 + E2E | M | — |

**建议顺序**：F1 → F2 → F3 → F4 → F5 → F6。
每阶段前端删完入口后，与后端确认对应端点在同分支已删（避免中间态），最后统一跑 F6。
