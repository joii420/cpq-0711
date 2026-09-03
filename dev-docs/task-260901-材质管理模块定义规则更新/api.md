# 接口契约 · 材质管理模块定义规则更新

> 前后端并行开发期间，**本文件是两边唯一的协调物**。改动本文件必须同步通知在跑的子代理（`task-docs.md §4`）。
> 基址 `/api/cpq`，鉴权沿用现有（未登录返 401）。

---

## 0. 破坏性变更总览（前端必读）

| # | 变更 | 影响 |
|---|---|---|
| **BC-1** | `MaterialRecipeDTO.elements` **移除**，改为 `configs[]`，元素挂在每个 config 下 | `MaterialRecipeEditDrawer.tsx` / `materialRecipeService.ts` 必须同步改。**旧字段不保留兼容别名** —— 消费方全在本次范围内，留兼容别名只会让两套结构长期并存 |
| **BC-2** | `MaterialRecipeUpsertRequest.elements` **移除**，改为 `composition[]`（材质的元素组成） | 新建/编辑材质时定的是「有哪些元素」，**含量一律经配置端点维护** |
| **BC-2b** | `MaterialRecipeLite.elementCodes` 的**语义源变了**：不再是「取任一配置推导」，而是材质自己的 `material_recipe_composition` | 值形状不变（字符串数组），但 0 配置的材质**现在也有值**，前端不能再假设「无配置 ⇒ 无元素」 |
| **BC-3** | `ExistingPartMaterialDTO.elements` 语义变为「该料号所用**配置**的元素」，新增 `configNo` | `MaterialRecipeSuggestDrawer` / 选配既有料号回显 |
| **BC-4** | 导入接口的**请求体不变**（仍是 multipart `file`），但**只接受新 4 列单表模板**；旧两 sheet 文件返 400 | `MaterialRecipeManagement.tsx` 的导入抽屉文案与错误展示 |
| **BC-5** | `MaterialImportReportDTO` 新增 `createdElements[]` / `createdConfigs[]`，`skipped[]` 新增两种 `reason` | 导入报告 UI |

---

## 1. 数据结构

```ts
// 材质的元素组成项
interface CompositionItem {
  elementNo: string;    // '10001' —— 权威元素链
  elementCode: string;  // 'Ag'    —— 服务端从 element 主表回填的快照
  elementName: string;  // '银'    —— 同上
  sortOrder: number;    // 决定配置矩阵的列顺序
}

// 材质列表项
interface MaterialRecipeLite {
  id: string; code: string;            // '00006'
  symbol: string; name: string;        // 材质名 / 名称
  recipeType: 'locked'|'editable'|'partial';
  allowCustomContent: boolean;         // ★新增
  elementCodes: string[];              // ★新增 元素组成的符号，按 sortOrder；来源是 material_recipe_composition
                                       //   ⚠️ 与配置无关：0 配置的材质照样有值（BC-2b）
  configCount: number;                 // ★新增 ACTIVE 配置数；0 = 未配置含量
  status: 'ACTIVE'|'INACTIVE';
  sortOrder: number; createdAt: string; updatedAt: string;
}

// 含量配置
interface MaterialRecipeConfig {
  id: string;
  configNo: string;                    // '00006-01'
  seq: number;                         // 1,2,3… 发号水位
  remark: string | null;
  status: 'ACTIVE'|'INACTIVE';
  elements: MaterialRecipeElement[];
  totalPct: string;                    // 合计，100 制、12 位小数字符串，如 '100.000000000000'
  createdAt: string;
}

interface MaterialRecipeElement {
  elementNo: string;                   // '10001'
  elementCode: string;                 // 'Ag'
  elementName: string;                 // '银'
  defaultPct: string;                  // ★字符串传输：'90.000000000000'（100 制、12 位）
  minPct: string | null; maxPct: string | null; isLocked: boolean; sortOrder: number;
}

// 材质详情
interface MaterialRecipeDetail extends MaterialRecipeLite {
  specLabel: string | null;
  composition: CompositionItem[];      // ★新增 材质的元素组成（矩阵列的权威来源）
  compositionEditable: boolean;        // ★新增 false = 该材质已有 ACTIVE 配置，元素组成只读（M-0b）
  configs: MaterialRecipeConfig[];     // ★取代原 elements；默认只含 ACTIVE，见 GET 参数
}
```

🚨 **所有含量字段一律用 `string` 传输，禁止用 JS `number`。**
`defaultPct` 是 `numeric(16,12)`，`90.000000000000` 走 JS `number` 会丢尾数且无法区分 `12.345678901200` 与 `12.3456789012`。前端展示与回填都按字符串处理，只在需要比较时用 decimal 工具（沿用 `precision.ts`）。

---

## 2. 端点

### 2.1 材质（既有，契约有变）

| 方法 | 路径 | 变更 |
|---|---|---|
| `GET` | `/material-recipes?keyword=&status=&page=&size=` | 响应项加 `allowCustomContent` / `elementCodes` / `configCount` |
| `GET` | `/material-recipes/{id}?includeInactiveConfigs=false` | ★ 响应 `elements` → `configs`；新增查询参数（默认 `false` 只返 ACTIVE 配置） |
| `POST` | `/material-recipes` | ★**建材质与建配置合成一次调用**：请求移除 `elements`，改为 `configs: [{remark?, elements:[{elementNo, defaultPct}]}]`（**必填，至少 1 组**）<br>⚠️ **字段名统一为 `defaultPct`**（与 §2.2 的配置端点一致）—— 初稿这里误写成 `pct`，2026-09-02 更正。🚫 **`pct` 只在 §2.4 的选配请求里使用**（那是既有代码的字段名，不动），材质与配置两侧的写入一律 `defaultPct`+ `allowCustomContent`（可选，默认 `false`）。<br>🚫 **请求体不含 `composition`** —— 元素组成由服务端从 `configs` 推导（各组元素种类须相同，取第 1 组的元素与顺序），这与导入侧 M-5b 是同一条规则。<br>整个请求**要么全成要么全不成**（一个事务）：任一组不合法则材质、组成、配置都不落库，**且不消耗材质编号** |
| `PUT` | `/material-recipes/{id}` | 编辑态**不带配置**：接 `symbol` / `name` / `specLabel` / `recipeType` / `allowCustomContent` / `composition` / **`sortOrder` / `status`**。<br>⚠️ **`sortOrder` 与 `status` 是 2026-09-02 补上的**：材质编辑抽屉自 `task-0708` 起一直在编辑这两项，**`status` 更是把材质改回「启用」的唯一入口**。初稿的字段清单漏了它们，后端若不接收会让「改状态」**静默失效**（前端发了、没报错、也没生效）。`code` 仍只读。`composition` **仅当该材质无 ACTIVE 配置时可变更**；有配置时传了与现值不同的 `composition` → 409 `COMPOSITION_LOCKED`（传相同值视为未改，放行）。配置的增删改一律走 §2.2 的配置端点 |
| `DELETE` | `/material-recipes/{id}` | 不变（软删材质） |

**新增错误码**

| HTTP | `code` | 何时 | 文案 |
|---|---|---|---|
| 409 | `RECIPE_SYMBOL_DUPLICATED` | 新建/改名后与另一条 ACTIVE 材质同名 | `材质名已被材质 {code} 使用。材质名即材质身份，不允许重名` |
| 400 | `RECIPE_SYMBOL_TOO_LONG` | 材质名 > 32 字符 | `材质名最多 32 字符，当前 {n} 字符` |
| 409 | `CUSTOM_CONTENT_NEEDS_CONFIG` | 材质无任何 ACTIVE 配置时把 `allowCustomContent` 置 true | `该材质尚未配置任何含量，无法开启自定义含量` |
| 409 | `COMPOSITION_LOCKED` | 该材质已有 ACTIVE 配置，却要改 `composition` | `该材质已有 {n} 条含量配置，元素组成不可修改。换元素组成请新建材质，或先删除全部含量配置` |
| 400 | `COMPOSITION_EMPTY` | `composition` 为空数组，或 `POST` 的 `configs` 为空 / 某组 `elements` 为空 | `材质必须至少有一个元素` |
| 400 | `COMPOSITION_INCONSISTENT_ACROSS_CONFIGS` | `POST` 时各组的元素种类集合不全相同 | `配方{i} 与 配方{j} 的元素种类不同（配方{i}={..}，配方{j}={..}）。同一材质下各配方必须使用相同的元素`。**与导入侧的 `同一材质内各组元素组成不一致(...)` 是同一判据**（M-0a） |
| 409 | `CONFIG_DUPLICATED_IN_REQUEST` | `POST` 的两组配方内容逐值相同 | `配方{i} 与 配方{j} 的含量完全相同，请删除其中一组`（判据同 M-4） |
| 400 | `COMPOSITION_ELEMENT_DUPLICATED` | 同一 `elementNo` 在 `composition` 里出现多次 | `元素重复：{elementNo}` |

### 2.2 含量配置（全新）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/material-recipes/{id}/configs?includeInactive=false` | 列该材质的配置，按 `seq` 升序 |
| `POST` | `/material-recipes/{id}/configs` | 新建配置。`configNo` **由服务端生成，请求体不得携带** |
| `PUT` | `/material-recipes/{id}/configs/{configId}` | 修改配置的 `remark` 与 `elements`。`configNo` / `seq` 不可改 |
| `DELETE` | `/material-recipes/{id}/configs/{configId}` | **软删**：`status → INACTIVE`。幂等（已 INACTIVE 再删返 200） |

**请求体（POST / PUT 同形）**

```json
{
  "remark": "客户 A 专用档次",
  "elements": [
    { "elementNo": "10001", "defaultPct": "75" },
    { "elementNo": "10005", "defaultPct": "25" }
  ]
}
```
> 元素以 **`elementNo`** 为准（`task-260709` B2 已确立 `element_no` 是权威元素链）。`elementCode` / `elementName` 由服务端从 `element` 主表回填，**请求体传了也忽略**。
>
> 🚨 **`elements` 的元素集合必须与该材质的 `composition` 逐个相等** —— 多了、少了都返 400 `CONFIG_ELEMENT_SET_MISMATCH`。
> 前端在这一层**不给用户选元素的机会**（元素行按 `composition` 预填且只读，用户只填含量，见 AC-14），所以这条校验在正常路径上不会触发；它防的是绕过前端直接打接口。
>
> `defaultPct` 接受去掉尾随零的写法（`"75"` 与 `"75.000000000000"` 等价，服务端按 `BigDecimal` 解析）。

**响应**：`MaterialRecipeConfig`

**错误码**

| HTTP | `code` | 何时 | 文案 |
|---|---|---|---|
| 400 | `CONFIG_SUM_NOT_ONE` | 合计偏离 100 超过容差 2（= 0~1 制的 0.02） | `含量合计必须为 1，实际 {x.xx}` |
| 400 | `CONFIG_PCT_ILLEGAL` | 单个含量 ≤ 0 或 > 100 | `含量必须大于 0 且不超过 100：{elementCode}` |
| 400 | `CONFIG_ELEMENT_SET_MISMATCH` | 元素集合与该材质的**元素组成**不相等（多了、少了都算） | 多了：`{elementCode} 不在该材质的元素组成（{组成}）中`；少了：`缺少元素 {elementCode}，该材质的元素组成是 {组成}` |
| 409 | `CONFIG_DUPLICATED` | 与某条 ACTIVE 配置逐值相同（判据见 `需求文档.md` M-4） | `该含量配比与已有配置 {configNo} 完全相同` |
| 404 | `ELEMENT_NOT_FOUND` | `elementNo` 在 `element` 主表查无 | `元素编号不存在：{elementNo}` |

### 2.3 导入（既有，语义有变）

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/material-recipes/import` | multipart，字段名 `file`。请求形状不变 |
| `GET` | `/material-recipes/import/template` | 返回 **单 sheet 4 列** xlsx（`材质 / 组号 / 元素符号 / 含量`）+ 2 行示例。导入侧**读第一个工作表、按表头识别，不依赖 sheet 名** |

**响应 `MaterialImportReportDTO`**

```ts
interface MaterialImportReport {
  totalRows: number;                 // 读到的数据行数（不含表头、不含全空行）
  recipesCreated: number;            // ★重命名（原 materialsUpserted，语义由 upsert 改为 created）
                                     //   只统计真正落库的材质；被整体跳过的材质不计、也不消耗编号
  configsCreated: number;            // ★新增
  configsSkippedAsDuplicate: number; // ★新增 内容已存在而跳过的配置组数
  elementRowsInserted: number;
  createdElements: Array<{           // ★新增 本次自动建档的元素，供业务复核
    elementNo: string; elementCode: string; elementName: string; sourceRow: number; sourceRecipe: string;
  }>;
  createdConfigs: Array<{            // ★新增 新增明细
    recipeCode: string; recipeSymbol: string; configNo: string; summary: string; recipeIsNew: boolean;
  }>;
  skipped: Array<{ sheet: string; row: number|null; reason: string; raw: string }>;
  skippedRowCount: number;
  durationMs: number;
}
```

**`skipped[].reason` 取值全集**（前端按原文展示，不做映射）

| reason | 触发 | 承接自 |
|---|---|---|
| `含量非法` | 单行含量非数字 / ≤0 / >1 | repair-260901 E-6 |
| `含量合计≠1(实际x.xx)` | 一组配置的 Σ 偏离 1 超 0.02 | repair-260901 E-5 |
| `元素组合与该材质的元素组成不一致` | ★新增，M-0 校验（**已存在的材质**：某一组与材质元素组成不相等） | 新规则 1 |
| `同一材质内各组元素组成不一致(组X={..} 组Y={..})` | ★新增，M-5b 校验（**新建材质**：同一文件内该材质各组互不一致 ⇒ **整个材质跳过、不发号**） | 新规则 1 + D11 |
| `材质名超长（最多 32 字符）` | ★新增 | AC-24 |
| `材质名对应多条材质记录，请先在材质管理页处理` | ★新增，M-6 防御分支 | AC-28 |
| `元素符号为空` | 元素符号列为空 | 既有 |

**导入整体失败（400，不是 skipped）**

| `code` | 何时 | 文案 |
|---|---|---|
| `IMPORT_TEMPLATE_OUTDATED` | 检测到旧两 sheet 结构（含工作表「材质编号」或「材质对应元素」） | `导入模板格式已更新，请下载新模板。新模板为单个工作表、4 列：材质 / 组号 / 元素符号 / 含量` |
| `IMPORT_HEADER_INVALID` | 表头不是 `材质 / 组号 / 元素符号 / 含量` | `表头不符合模板要求，请下载新模板` |
| `IMPORT_FILE_EMPTY` | 上传空文件 | `上传文件为空` |

> ⚠️ 文件表头正确但**无数据行**不算失败：返 200，报告全 0，前端按空态展示（AC-23）。

### 2.4 选配（既有，请求有变）

`ConfigureProductRequest` 的材质部分：

```ts
{
  recipeCode: string;          // 既有，材质编号
  configNo?: string;           // ★新增 选标准配置时必填
  elements?: Array<{           // 既有，自定义含量时必填
    elementCode: string; pct: string;   // ★pct 由 number 改为 string
  }>;
}
```

**互斥规则**：`configNo` 与 `elements` **必须恰好给一个**。

| HTTP | `code` | 何时 | 文案 |
|---|---|---|---|
| 400 | `MATERIAL_SOURCE_AMBIGUOUS` | 两个都给或都不给 | `请选择标准配置或自定义含量之一` |
| 403 | `CUSTOM_CONTENT_NOT_ALLOWED` | 给了 `elements` 但材质 `allowCustomContent=false` | `该材质不支持自定义含量` |
| 400 | `CUSTOM_CONTENT_SUM_NOT_ONE` | 自定义含量合计偏离 1 超容差 | `含量合计必须为 1，实际 {x.xx}` |
| 400 | `CUSTOM_CONTENT_ELEMENT_UNKNOWN` | 自定义的 `elementCode` 不在材质元素组成中 | `元素未在材质中定义：{elementCode}` |
| 409 | `RECIPE_HAS_NO_CONFIG` | 材质无任何 ACTIVE 配置 | `该材质尚未配置含量` |

**选配的材质候选**（既有端点，响应加字段）：`configCount` 与 `allowCustomContent` 随候选项一起返回，前端据此灰显与禁用（AC-17 / AC-18）。

---

## 2.5 错误码怎么传、前端怎么拿（2026-09-02 实证更正）

> ⚠️ **本节初稿写反了。** 我当时据前端「`ApiResponse.code` 是 int，`buildApiError` 只取 message」的观察，
> 写成「前端拿不到字符串码、只能按文案分支」。**那个观察只对了一半** —— 顶层 `code` 确实是 int，
> 但 `buildApiError` 同时把**信封的 `data` 整个**传了出去（`cpq-frontend/src/services/api.ts:32`）：
>
> ```ts
> err.payload = error?.response?.data?.data ?? null;   // 信封.data，与成功侧 response.data 同层级
> ```

**约定的错误响应形状**（沿用 `ComponentElementBindingRequiredException` 的既有惯例）：

```json
{
  "code": 400,
  "message": "配方1 与 配方2 的元素种类不同（…）。同一材质下各配方必须使用相同的元素",
  "data": { "code": "COMPOSITION_INCONSISTENT_ACROSS_CONFIGS" }
}
```

⇒ **前端可以按码分支**：`err.payload?.code === 'COMPOSITION_LOCKED'`，不必匹配文案。
⇒ **接口层测试**直接读响应原文，字符串码在 body 里。
⇒ 🚨 **但文案仍是契约的一部分，后端不得自行润色措辞**：多条 AC（AC-9 / AC-10 / AC-31 / AC-34）的可观测断言就是那句文案的逐字内容；
> 且导入报告的 `skipped[].reason` **只有文案、没有码**，前端展示与测试断言都只能靠它。

---

## 3. 不改的接口（明确列出，避免子代理误改）

| 端点 | 为什么不改 |
|---|---|
| `/material-recipes/{id}/parts`、`/bind-parts`、`/unbind-parts`、`/search-parts`、`/suggest-bindings`、`/confirm-bindings` | 料号绑定挂在**材质**层，与配置层无关。`material_master.material_recipe_id` 实测 1890 行全 NULL，本次不引入配置维度的绑定 |
| `/elements` 全部端点（`ElementService`） | 元素主表 CRUD 不变。其「被引用数」与「符号锁」按 `element_no` 聚合 `material_recipe_element`，**元素行改挂配置后计数仍然正确**（实测这两处查询不含 `recipe_id`），但必须写回归用例锁住 |
| 107 个组件 SQL 视图 | 只用 `mr.code` 与 `mr.name`，材质层这两列本次零改动 |
