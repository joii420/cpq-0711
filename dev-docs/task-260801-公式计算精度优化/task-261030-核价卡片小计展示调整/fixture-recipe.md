# 夹具配方：让前端真的算出产品小计（task-261030）

| 项 | 值 |
|---|---|
| 作者 | 前端工程师（2026-10-30） |
| 服务 | AC-1 / AC-3 / AC-4 / AC-6 / AC-7 / AC-10 的页面级用例能否成立 |
| 依据 | 前端源码静态阅读 + 一次性页面自证（§6）。`文件:行` 为本 worktree（候选）版本；master 上 `QuotationStep2.tsx` 自 `:3127` 起约后移 8 行，其余文件行号相同或只差金额区几行 |
| 读者 | 测试员（不读实现，按本文改夹具即可） |

---

## 0. 一句话结论：CAL-1 失败的根因

现夹具（`e2e/task261030-costing-subtotal.fixtures.ts` 的 `cardValues()`）把 `basicDataValues` 的键写成了**字段名**（`{"核价金额": "1717.135540717"}`）。
前端按 **`{` + 字段的 `basicDataPath` + `}`** 查这个键（`QuotationStep2.tsx:716-724` 调 `bnfDriverLookupKey`，定义在 `useDriverExpansions.ts:467-480`）。
键对不上 ⇒ BASIC_DATA 单元格取不到值 ⇒ 编辑页格子「加载中…」、详情页「—」、页签合计 0、产品小计 0。

**改一处即可**（配方 A，§2）：

```diff
- basicDataValues: { [spec.fieldName]: spec.amount }
+ basicDataValues: { [`{${spec.view}.amount}`]: spec.amount }   // 即 "{$T261030_COST_view.amount}"、"{$T261030_COST_quote_view.amount}"
```

已用同一套接口夹具在本 worktree 页面上自证：改键前 5 处全是 `0`（复现你们的现象），改键后 5 处全部出数（§6）。

---

## 1. 产品小计是怎么算出来的（5 处共用）

1. 卡片的页签 / 字段来自**结构**：
   - 编辑页报价侧、详情页两侧、核价管理页 → `quoteCardStructure` / `costingCardStructure`
   - **编辑页核价侧例外**：来自 `GET /templates/{costingCardTemplateId}` 的 `componentsSnapshot`
2. 每个页签的「行」：
   - 有快照驱动行时 = 快照 `baseRows`。`buildSnapshotExpansions`（`QuotationStep2.tsx:2311-2411`）把 `costingCardValues` / `quoteCardValues` 的 `tabs[].baseRows` 转成驱动行，**按 `componentId` 对齐结构页签**（`:2331`），行号缺 `productPartNo` 的整行跳过（`:2325-2326`）。
   - 没有快照驱动行时 = 页签自身的 `rows`；由结构组装时为 `[{}]`（`enrichComponentData.ts:310-311`，编辑页核价侧 `QuotationStep2.tsx:4400-4407`）。
3. 每行每个 `isSubtotal` 列的值：
   - BASIC_DATA → `basicDataValues["{basicDataPath}"]`（`QuotationStep2.tsx:716-731`；结果行 `:1474-1478`）
   - FIXED_VALUE → 行里该键为空时补字段默认值 `content`（`fillFixedDefaults` `:1893-1909`；`computeAllFormulas` `:771-776`）
4. 页签小计 = 各行 `isSubtotal` 列之和：
   - 编辑页：`computeTabSubtotalsByColumn` `:1915-1984`，再经 `buildCrossTabRows` → `subtotalsFromResolvedRows` `:1840-1871` 回填（`ProductCard` 内 `:3190-3238`）
   - 详情页 / 核价管理页：`ReadonlyProductCard.tsx:462-533`
5. 产品小计：
   - 没有 `SUBTOTAL` 类型组件、行上没有 `subtotalFormula`（编辑页加载时从不给行填 `subtotalFormula`，`QuotationWizard.tsx:641-720`）时，等于**各 NORMAL 页签小计之和**（`QuotationStep2.tsx:2199-2208`）。
   - 页签里**一个 `isSubtotal` 列 + 一行驱动行** ⇒ 产品小计 = 那一个金额。

`batch-expand` / `batch-evaluate`：快照模式下都不需要。
- 配方 A 会触发 `POST /formulas/batch-evaluate`（BASIC_DATA 路径预热），回 `{"results":[]}` 即可，不影响出数。
- 配方 B 一次都不触发。
- `batch-expand` 两种配方都不触发（前提：每行两侧卡片值都非空，见 §2 第 4 条）。

---

## 2. 配方 A（推荐）：BASIC_DATA + 快照驱动行（只需把现夹具的键改对）

与现夹具 §2.4 / §2.5 的形状**完全一致**，差别只有 `basicDataValues` 的键。必须同时满足：

| # | 约束 | 违反时的症状 | 依据 |
|---|---|---|---|
| A-1 | `basicDataValues` 的键 = `"{" + basicDataPath + "}"`，逐字一致（前后空白会被去掉，外层已有 `{}` 不会重复包） | 格子「加载中…」/「—」，小计 0 | `QuotationStep2.tsx:716-724`；`useDriverExpansions.ts:467-480` |
| A-2 | 卡片值 `tabs[].componentId` = 结构 `tabs[].componentId` = 核价模板 `componentsSnapshot[].componentId`（编辑页核价侧用后者） | 该页签无驱动行 → 小计 0 | `QuotationStep2.tsx:2329-2332` |
| A-3 | 行 `productPartNo` 非空 | 快照驱动行整行跳过；BASIC_DATA 取值分支被 `partNo` 挡住 | `:2325-2326`、`:716` |
| A-4 | **每一行**的 `quoteCardValues` 非空；核价侧 `costingCardValues` 非空（失败变体除外） | 报价侧任一行缺 → 整页改走实时 `batch-expand`（夹具回空 → 0）；编辑页打开时还会调 `ensure-card-values` | 编辑页 `QuotationStep2.tsx:4631`、`:4638-4645`；详情 / 核价管理 `ReadonlyProductCard.tsx:383`；`cardValuesWarm.ts:20-30` |
| A-5 | `quoteCardStructure.templateId` = 行 `templateId`，且 `tabs` 非空 | 走 `GET /templates/{templateId}` 现拉（夹具有则仍能出数，但多一条依赖） | `QuotationWizard.tsx:749-762`；`ReadonlyProductCard.tsx:321-330` |
| A-6 | 编辑页核价侧：`GET /templates/{costingCardTemplateId}` 的 `componentsSnapshot` 字段写 snake_case：`field_type:"BASIC_DATA"`、`is_subtotal:true`、`basic_data_path` 与结构 `basicDataPath` **同一字符串** | 编辑页核价格子「加载中…」 | `QuotationStep2.tsx:4375-4392`（`buildField`）、`:4404`（`data_driver_path`） |
| A-7 | 不放 `componentType:"SUBTOTAL"` 的页签；字段只有一个 `isSubtotal:true` 列 | 产品小计改走 SUBTOTAL 公式 | `:2174-2186` |
| A-8 | 金额一律字符串（G-1） | — | `precisionValue` / `toDecimal` 不收 JS number |

`baseRows` 只放 1 行；放多行时产品小计 = 各行之和。`driverRow` 内容不参与计算，可保留 `{amount: …}`。

**五处各自的取数分支**（同一份数据）：

| 处 | 页签结构来源 | 行来源 | 小计计算位置 |
|---|---|---|---|
| ① 编辑页「📝 报价单 · 📋 产品卡片」 | `quoteCardStructure` → `buildComponentDataFromStructure`（`QuotationWizard.tsx:749-762`） | `quoteCardValues` 快照驱动行（`QuotationStep2.tsx:4631`、`:4663`） | `ProductCard` `:3190-3238` → 金额区 `:4134-4160` |
| ② 编辑页「📊 核价单 · 📋 产品卡片」 | `GET /templates/{costingCardTemplateId}`.`componentsSnapshot`（`:4334-4356`、`:4371-4420`） | `costingCardValues` 快照驱动行（`:4638`、`:4666`） | 同上 |
| ③ 详情页「报价单 · 产品卡片」 | `quoteCardStructure`（`ReadonlyProductCard.tsx:321-330`） | `quoteCardValues` 快照（`:383`、`:394-397`） | `:462-543` |
| ④ 详情页「核价单 · 产品卡片」 | `costingCardStructure`（`:291-302`，不发网络请求） | `costingCardValues` 快照 | 同上 |
| ⑤ 核价管理页「核价单 · 产品卡片」 | `frozenDto` 里的 `costingCardStructure` | `costingRender[lineId].costingCardValues`（覆盖 frozen 行值，`CostingReviewPage.tsx:62-83`） | 同 ④ |

---

## 3. 配方 B（备选）：FIXED_VALUE 默认值，不依赖快照驱动行

配方 A 仍不出数时用它定位：B 出数说明问题在快照对齐，B 也不出数说明问题在结构 / 模板。

- 结构字段：`fieldType:"FIXED_VALUE"`、`defaultValue:"<金额>"`、`isSubtotal:true`、`isAmount:true`，**不要** `basicDataPath` / `dataDriverPath`。`defaultValue` 映射到 `content`（`enrichComponentData.ts:275`）。
- 核价模板 `componentsSnapshot` 字段：`field_type:"FIXED_VALUE"`、`content:"1717.135540717"`、`is_subtotal:true`（`QuotationStep2.tsx:4378`）。
- 卡片值：`"{\"tabs\":[]}"`。它是合法的「算完、结果为空」：不触发 warm（`cardValuesWarm.ts:20-30`），也不触发 `batch-expand`。
- 行 `componentData: []`，或 `rowData` 里不含该字段键。
  - 行里有非空同名键时，FIXED_VALUE 以行值为准（`:1904`）。
  - 编辑页核价侧的行取自同行 `componentData` 中同 `componentId` 的条目（`:4400-4407`），所以报价、核价两侧的 `componentId` 必须不同（现夹具已不同）。

---

## 4. 页面④的两种「没有核价数据」变体（报价侧数据不动）

| 变体 | 夹具 | 候选（本 worktree）实测 | master 预期（**按代码推导，未在 master 实跑**） |
|---|---|---|---|
| ① 没有核价结构 | `costingCardTemplateId:null`、`costingCardStructure:null`、行 `costingCardValues:null`、`costingExcelValues:null`（现夹具 `NO_COSTING_STRUCTURE` 即此形态） | 金额区 `- (VAT) -`，主体「暂无组件数据」 | 主体「暂无组件数据」。金额区「产品小计 ¥ 888.123456789」「外币小计 124.676903837 USD」：组件为空，`computeProductSubtotal` 退回行 `subtotal`（`QuotationStep2.tsx:2222-2223`） |
| ② 生成失败 | 保持 `costingCardTemplateId` / `costingCardStructure` 非空，只把行 `costingCardValues` 换成 `{"tabs":[],"__cardValueFailed":true,"__errorMsg":"核价渲染失败: …"}`（现夹具 `COSTING_FAILED` 即此形态） | 金额区 `- (VAT) -`，主体「核价数据生成失败：核价渲染失败: T261030_COST_mock」 | ⚠️ **不是 888.123456789**。结构非空 ⇒ 组件非空 ⇒ 小计照常由结构现算，不读行 `subtotal`。快照 `tabs:[]` 没有驱动行：配方 A 得 `¥ 0` / `0 USD`；配方 B 得 `¥ 1717.135540717` / `241.055611185 USD`（来自默认值） |

⚠️ 给主线：`需求文档.md` ① 第 1 条写「生成失败时……仍显示一个金额——它是报价侧的产品小计」。按代码，这只在**结构也为空**时成立。生成失败的真实形态（结构在、值是哨兵）下，master 显示的是「按结构现算出的非核价数值」（通常为 0），不是报价侧小计。
这不影响候选侧验收：两种变体候选都是 `-`，且金额区不含 `888.123456789` / `1717.135540717`。但 CAL-2 若断言「master 失败变体显示 888.123456789」会假红，应改成「master 显示的不是 `-`」。

---

## 5. 核价管理页「核价单据总价」与页面入口文案

### 5.1「核价单据总价」出现条件（`ProductDetailViews.tsx:386`：`!!coid && mainTab === 'costing' && viewType === 'card'`）

| 条件 | 现状 | 依据 |
|---|---|---|
| `coid` | `CostingReviewPage` 恒传路由参数 `coid` | `CostingReviewPage.tsx:84`、`:260-267` |
| `mainTab === 'costing'` | **初值是 `'quote'`**：`CostingReviewPage` 不传 `initialMainTab` | `ProductDetailViews.tsx:75`；`CostingReviewPage.tsx:260-267` |
| `viewType === 'card'` | 初值 `'card'` | `ProductDetailViews.tsx:76` |
| 接口 | 只要 `GET /api/cpq/costing-orders/{coid}`；`frozenDto` 解析失败时整块换成「此单为旧版本，无冻结明细」 | `CostingReviewPage.tsx:97-110`、`:259-274` |

**找不到的原因（已实测）**：进页时停在「报价单」视图，该行不渲染（计数 0）。必须先点「核价单」分段才出现。实测文本：`核价单据总价：¥1717.135540717`。

定位注意：标签文本节点是「核价单据总价：」（带全角冒号），金额在嵌套的 `<strong>` 里。
- `getByText('核价单据总价', { exact: true })` 命中 0。
- 请用 `getByText(/核价单据总价/)`，或取其父节点 `innerText`。

### 5.2 用户可见入口文案

| 页面 | 操作 | 文案 |
|---|---|---|
| 编辑页 `/quotations/{id}/edit` | 进第 2 步 | 底部右侧主按钮「下一步」（步骤条标题「选择客户 / 添加产品 / 优惠策略 / 交易条款 / 提交审批」不可点）。第 1 步「下一步」可用的条件：名称与报价模板非空，夹具已满足 |
| 编辑页第 2 步 | 报价 / 核价 / 比对 | 分段控件「📝 报价单」「📊 核价单」「📈 比对视图」（文案含 emoji；`hasText: '核价单'` 可命中） |
| 编辑页第 2 步 | 卡片 / Excel | 「视图:」后的分段控件「📋 产品卡片」「📑 Excel 视图」（默认「产品卡片」） |
| 详情页 `/quotations/{id}`、核价管理页 `/costing-orders/{coid}/review` | 报价 / 核价 / 比对 | 「产品明细」卡片内分段控件「报价单」「核价单」「比对视图」（无 emoji，默认「报价单」） |
| 同上 | 卡片 / Excel | 「产品卡片」「Excel 视图」（默认「产品卡片」） |

---

## 6. 自证记录（一次性，用完即停）

- **环境**：本 worktree 临时起 Vite `:5291`（`/api` 代理指向死端口，全部由 Playwright `page.route` 应答）。Windows Chrome headless + playwright-core，系统管理员角色，1280 宽。脚本与原始输出都在 scratchpad：
  - 脚本：`C:\Users\Administrator\AppData\Local\Temp\claude\--wsl-localhost-Ubuntu-home-joii-project-cpq\f9d82a80-3439-4eb7-b5c4-d9dc1c14b2c4\scratchpad\fe\recipe-verify.mjs`
  - 原始输出：同目录 `verify-BROKEN.json` / `verify-BASIC_DATA.json` / `verify-FIXED_VALUE.json`
- **夹具**：照抄现夹具的 ID / 接口 / 形状。`BROKEN` = 现夹具原样（键为字段名），`BASIC_DATA` = 配方 A，`FIXED_VALUE` = 配方 B。三次 `unhandled` 均为空。
- **金额区文本**（`.qt-subtotal-bar(-multi)` 的 innerText，空白折叠）：

| 处 | BROKEN（现夹具） | 配方 A | 配方 B |
|---|---|---|---|
| ① 编辑·报价 | `0 USD (VAT) 0 USD`；格子「加载中」1；页签合计 0 | `888.123456789 USD (VAT) 1003.579506172 USD`；页签合计 `888.123456789`；「加载中」0 | 同配方 A |
| ② 编辑·核价 | 全 0；「加载中」1 | `1717.135540717 CNY (VAT) 1940.36316101 CNY 241.055611185 USD (VAT) 272.392840639 USD`；页签合计 `1717.135540717` | 同配方 A |
| ③ 详情·报价 | `0 USD (VAT) 0 USD` | `888.123456789 USD (VAT) 1003.579506172 USD` | 同配方 A |
| ④ 详情·核价 | 全 0 | 同 ② | 同 ② |
| ④-① 无核价结构 | `- (VAT) -` | `- (VAT) -` | `- (VAT) -` |
| ④-② 生成失败 | `- (VAT) -` | `- (VAT) -` | `- (VAT) -` |
| ⑤ 核价管理·初始（报价单视图） | 「核价单据总价」计数 0 | 同左；金额区 `888.123456789 USD (VAT) 1003.579506172 USD` | 同配方 A |
| ⑤ 核价管理·核价单 | 全 0；`核价单据总价：¥1717.135540717` | 同 ②；`核价单据总价：¥1717.135540717` | 同配方 A |

- **未验证**：
  - 上表是**候选代码**的渲染。master 上的计算链（`computeProductSubtotal` 及以上各函数）与候选逐字相同（本任务 diff 不碰），但金额区是旧格式（`产品小计 CNY …` / `外币小计 … USD`）。master 未实跑。
  - AC-3（CNY）、AC-10（保存后）、Excel / 比对视图本轮没跑。
- **控制台**：三种夹具都是同样 12 条 `error` 级告警，全部是 antd 弃用告警与 `<form>` 嵌套告警，与夹具无关（AC-9 由 F-7 处理）。
