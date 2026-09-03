# task-260902 · 接口契约

> 本文件是**前后端唯一协调物**（`task-docs.md §6`）：前端只按 `fronttask.md` 做、后端只按 `backtask.md` 做，两边互相看不见，靠本文件对齐。
> 契约有变更时，四处同步：本文件 → `fronttask.md` → `backtask.md` → `test.md`。

---

## 0. 变更总览

| 端点 | 变化 | 服务的 AC |
|---|---|---|
| `POST /api/cpq/configure-product/quotations/{quotationId}` | **请求结构大改**（三层模型），响应加 2 字段 | AC-1~AC-20 主链路 |
| `GET /api/cpq/quotations/configure/check-product-no` | 🆕 **新增**：客户产品编号占用校验 | AC-1, AC-2 |
| `GET /api/cpq/material-recipes` | 复用（`task-260901` 已交付），前端用作材质选择器数据源 | AC-18 |
| `GET /api/cpq/material-recipes/{id}/configs` | 复用（`task-260901` 已交付），含量配置下拉 | AC-6 |
| `GET /api/cpq/sel-param-types/PROCESS/candidates` | 复用，工序选择器数据源 | AC-19, AC-20 |
| `GET /api/cpq/quotations/configure/search-parts` | 复用，已有零件选择 | AC-11 |
| `GET /api/cpq/quotations/configure/outsourced-parts` | 🆕 **新增**：外购件候选 | AC-5, AC-16 |
| `POST /api/cpq/configure-product/lookup-fingerprint` | 请求结构随 `PartRequest` 同步改 | AC-7~AC-10 |

---

## 1. 主端点：提交选配

`POST /api/cpq/configure-product/quotations/{quotationId}`

### 1.1 请求（`ConfigureProductRequest`）

```jsonc
{
  "productType": "SIMPLE",              // 既有，未变
  "tempId": "uuid",                     // 既有，未变
  "customerProductNo": "CP-NEW-001",    // 🆕 必填，AC-1/AC-2
  "customerProductName": "高压接触器动触头组件",  // 🆕 选填
  "parts": [ /* PartRequest，见 1.2 */ ],
  "compositeProcesses": [ /* 既有，未变 */ ]
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `customerProductNo` | string | ✅ | 🆕 客户产品编号。**后端必须校验占用**：已存在于 **`sel_product_no`（新表，见 §4.6）或 `material_customer_map`** 时返 **409 `CUSTOMER_PRODUCT_NO_TAKEN`**（AC-2）。⚠️ 并发下须捕获唯一约束异常映射成同一个 409，不得漏成 500（AC-24）。前端虽已在步骤 1 拦截，后端仍须拦 —— 前端是体验，后端是正确性 |
| `customerProductName` | string | ❌ | 🆕 落 **`sel_product_no.customer_product_name`**（🚫 不写 mcm，见 §4.6） |

### 1.2 `PartRequest` —— 本次改动的核心

```jsonc
{
  "name": "动触头",                   // 语义变更：原为"配件 1"这类占位名，现为零件品名（AC-3）
  "partType": "PART",                // 🆕 "PART" | "OUTSOURCED"
  "partMode": "new",                 // 🔄 值域改名: "custom"→"new"，"existing" 不变
  "spec": "φ12×3",                   // 🆕 规格 → material_master.specification
  "dimension": "12×8×3",             // 🆕 尺寸 → material_master.dimension
  "unitWeightGrams": "10",           // 既有，语义收紧为「零件总重」（AC-3）
  "materials": [                     // 🆕 取代单值 recipeCode（AC-3/AC-4）
    {
      "recipeCode": "00006",
      "configNo": "00006-01",        // task-260901 交付的含量配置编号
      "ratio": "70",                 // 🆕 材质占比 % → material_bom_item.material_ratio
      "elements": null               // 自定义含量时填，与 configNo 互斥（AC-6）
    },
    { "recipeCode": "00123", "configNo": "00123-01", "ratio": "30", "elements": null }
  ],
  "processNos": ["Z100", "Z101"],    // 既有。⚠️ 数组顺序 = 工艺顺序，后端须原样落 seq_no（AC-19）
  "outsourcedPartNo": null,          // 🆕 partType=OUTSOURCED 时必填
  "existingHfPartNo": null,          // 既有，partMode=existing 时必填
  "quotationLineItemId": "uuid",     // 既有，未变
  "quantity": 1                      // 既有，未变
}
```

#### 🔄 与现状的字段级差异

| 现状 | 本次 | 处理方式 |
|---|---|---|
| `recipeCode`（单值 string） | `materials[]`（数组） | **加法式**：新增 `materials`，`recipeCode` 保留但标 `@Deprecated`。后端解析优先级：`materials` 非空 → 用它；否则回落 `recipeCode` 单材质（`ratio` 默认 `100`）。⚠️ 实测 `sel_part_signature` **0 行**、`material_recipe_id` **1890 行全 NULL（取数 2026-09-02，仍为 0）** ⇒ custom 路径从未跑通，**无存量 payload 需要兼容**，此回落仅为并发分支安全 |
| `elements`（PartRequest 级，单组） | `materials[i].elements`（每材质一组） | 下沉一层。老字段保留同上 |
| `configNo`（`task-260901` 刚加的单值） | `materials[i].configNo` | 同上下沉。**已与材质会话确认为加法式扩展，不破坏其已落地语义** |
| `partMode: "custom"` | `partMode: "new"` | 后端**两个值都接受**（`"custom"` 视为 `"new"`），前端只发 `"new"` |

#### ⚠️ 校验规则（后端强制，前端同步实现为体验层）

| 规则 | 错误码 | HTTP | AC |
|---|---|---|---|
| `customerProductNo` 已被占用 | `CUSTOMER_PRODUCT_NO_TAKEN` | 409 | AC-2 |
| `partType=PART` + `partMode=new` 时 `materials` 为空 | `PART_HAS_NO_MATERIAL` | 400 | AC-14 |
| `materials[].ratio` 合计 ≠ 100 | `MATERIAL_RATIO_SUM_INVALID` | 400 | AC-4 |
| 同一 part 内 `recipeCode` 重复 | `MATERIAL_DUPLICATED` | 400 | AC-17 |
| 材质无 ACTIVE 含量配置 | `RECIPE_HAS_NO_CONFIG` | 409 | AC-5 |
| 材质 `allow_custom_content=false` 却传了 `elements` | `RECIPE_CUSTOM_NOT_ALLOWED` | 403 | AC-6 |
| `partType=OUTSOURCED` 且 `outsourcedPartNo` 为空 | `OUTSOURCED_PART_REQUIRED` | 400 | AC-5 |
| 零件 `unitWeightGrams` ≤ 0 或缺失 | `PART_WEIGHT_REQUIRED` | 400 | AC-3 |
| 品名/规格/尺寸含指纹分隔符（`\|` `=` `,` `:`） | `PART_TEXT_INVALID_CHAR` | 400 | §4.3 |
| **`materials[i]` 的 `configNo` 与 `elements` 两个都给或都不给** | `MATERIAL_SOURCE_AMBIGUOUS` | 400 | AC-6, AC-21 |
| 品名/规格/尺寸超过 **100 字符**（`material_master` 三列均为 `varchar(100)`） | `PART_TEXT_TOO_LONG` | 400 | AC-23 |

🚨 **`MATERIAL_SOURCE_AMBIGUOUS` 的作用域随 `materials[]` 下沉到「每个 material」**（A 轮遗漏，评审 P2-15）。
现状 `PartRequest.configNo` 的 javadoc 明写该互斥规则，并配了一个 `@JsonIgnore boolean materialResolved` 幂等标志，注释原文：

> 解析必须发生在指纹计算之前，且要幂等 —— **物化之后 `configNo` 与 `elements` 会同时非空，再跑一次互斥校验就会误报** `MATERIAL_SOURCE_AMBIGUOUS`。

⇒ `materialResolved` **也必须下沉到 material 级**。`lookupFingerprint` 与 `configure` 各会调一次 `prepareMaterialSelection`，不下沉则第二次调用必误报 400。

🚨 **`MATERIAL_RATIO_SUM_INVALID` 的错误响应必须带实际合计值**，前端要把它显示出来（AC-4 断言「提示写出实际合计 90%」，不是形容词）：
```jsonc
{ "code": "MATERIAL_RATIO_SUM_INVALID", "message": "材质占比合计为 90%，需要正好 100%",
  "detail": { "actualSum": "90", "expected": "100" } }
```

🚨 **合计判等必须用 `BigDecimal.compareTo`**，不得用 `double`/`equals`。AC-15b 是证伪对照组：`0.000000000001 + 99.999999999998 + 0.000000000001` 在浮点下等于 `99.99999999999999`，浮点实现会错误拒绝这个合法输入。

### 1.3 响应（`ConfigureProductResponse`）

```jsonc
{
  "lineItems": [ /* 既有 */ ],
  "fingerprintMatched": true,        // 既有
  "reusedHfPartNos": ["HF-SZXM-2509-0007"],  // 既有
  "productType": "SIMPLE",           // 既有
  "reusedProductInfo": {             // 🆕 命中复用时带出销售产品信息（AC-7 状态 C）
    "hfPartNo": "HF-SZXM-2509-0007",
    "partName": "动触头", "specification": "φ12×3", "dimension": "12×8×3",
    "unitWeight": "10",
    "materials": [ { "recipeCode": "00006", "name": "AgNi10", "ratio": "70" } ],
    "firstCreatedAt": "2026-09-01T10:00:00Z",
    "lastQuotedPrice": "12.4800"     // 可空
  },
  "structureVersion": "v2"           // 🆕 本次指纹结构版本，便于前端与排查对账
}
```

---

## 2. 新增端点

### 2.1 客户产品编号占用校验

`GET /api/cpq/quotations/configure/check-product-no?customerNo={code}&productNo={no}`

```jsonc
// 未占用
{ "taken": false }
// 已占用（AC-2 要求前端展示这些信息 + 提供跳转入口）
{ "taken": true, "hfPartNo": "HF-SZXM-2508-0031", "createdAt": "2026-08-14" }
```
> 前端在步骤 1 的输入框 **debounce 400ms** 后调用；**不阻塞输入**，只驱动提示与「下一步」禁用态。

### 2.2 外购件候选

`GET /api/cpq/quotations/configure/outsourced-parts?keyword={kw}&page=1&size=20`

```jsonc
{ "total": 1, "items": [ { "materialNo": "TEST-Q13-CODE", "materialName": "组成件1",
                           "specification": null, "unitWeight": null } ] }
```

**判据（闸门 A0 已裁决）**：`WHERE material_master.material_type = '外购件'`。

🚨 **该判据依赖 A0 的另一项裁决落地**：现状 `ConfigureProductService:388` 把**材质名**（`recipe.symbol`）写进 `material_type`，A0 已裁决**归位为料号类型**（选配写入改 `'零件'`）。若不改，本端点判据不成立。见 `backtask.md` B-9。

⚠️ **实测当前库该条件只命中 1 条**（`material_type` 分布：零件 1848 / NULL 40 / 外购件 1（取数 2026-09-02，共享库会漂移））。**返回 0 条是正常业务状态**，前端必须渲染空态而非「加载中…」（AC-16，AP-31 族）。

---

## 3. 复用的既有端点（本次不改，列出以免前端重复造）

🚨 **响应包装格式不统一 —— 前端按错格式解析会直接失败**（2026-09-02 实调 8081 确认，A 轮未验证仅凭文档声称）：

| 端点 | 顶层结构 | 取数写法 |
|---|---|---|
| `GET /material-recipes` | **裸数组** `[{...}]` | `res.data`（axios 后直接是数组）|
| `GET /material-recipes/{id}/configs` | **裸数组** `[{...}]` | 同上 |
| `GET /sel-param-types` | **包装** `{code, message, data:[...]}` | `res.data.data` |

⇒ 同一后端两种格式，**不要假设统一**。F-6 用前两个、工序选择器用第三个，写法不同。

| 端点 | 用途 | 备注 |
|---|---|---|
| `GET /api/cpq/material-recipes?keyword=&status=ACTIVE` | 材质选择器（AC-18） | ✅ **已实调确认**字段齐全：<br>`code` `symbol` `name` `status` `sortOrder` `recipeType` `specLabel`<br>🎯 **`configCount`**（实测 `00006` = `1`）· 🎯 **`allowCustomContent`**（实测 `false`）—— F-6 灰显依赖这两个，**均存在**<br>📌 附带可用：`elementCodes: ["Ag","Ni"]`（元素组成，可直接显示）<br>⚠️ 列表接口里 `composition` / `configs` / `boundPartsCount` **均为 null**，要拿这些得调详情端点 |
| `GET /api/cpq/material-recipes/{id}/configs` | 含量配置下拉（AC-6） | ✅ **已实调确认**结构：<br>`configNo`（如 `00006-01`）· `seq` · `status` · `remark` · **`totalPct`**（实测 `100.000000000000`，注意是 12 位小数，显示前须走 F-12 去零）<br>**`elements[]`** 每项含 `elementNo`(`10001`) / `elementCode`(`Ag`) / `elementName`(`银`) / `defaultPct`<br>📌 `elementCode`/`elementName` 走 `task-260901` 交付的**权威元素链**（`LEFT JOIN element ON element_no`），脏数据会自动显示正确 |
| `GET /api/cpq/sel-param-types/PROCESS/candidates` | 工序选择器 | 数据源 `process_master`。⚠️ 实测仅 4 条，且由业务在「主数据维护→工序」页自行维护 |
| `GET /api/cpq/quotations/configure/search-parts` | 已有零件选择（AC-11） | 既有 |
| `GET /api/cpq/quotations/{id}/existing-products` | 「从产品库添加」入口 | AC-2 的跳转目标、AC-12 的验证入口 |

---

## 4. 指纹契约（`sel_part_signature.config_signature_text`）

**结构版本 `v1` → `v2`**（A0 裁决；实测该表 **0 行**，升版无存量失配风险）。
`STRUCTURE_VERSION` 是 `computeSimple` 与 `computeComposite` **共用常量** ⇒ 两种结构必须同时定义。

### 4.1 SIMPLE 的 v2 结构

```
v2|CUST=<客户码>|PART=<len>:<品名><len>:<规格><len>:<尺寸>|WEIGHT=<总重>|MAT=<材质码:占比(元素码:含量,…)>,…|PRC=<工序码,…>
```

| token | 组成 | 排序 | 依据 |
|---|---|---|---|
| `PART=` | 🆕 品名/规格/尺寸，**长度前缀编码** | — | AC-8 同族 |
| `WEIGHT=` | 🆕 零件总重 | — | AC-8 |
| `MAT=` | 🔄 `材质码:占比(元素码:含量,…)` | 按材质码排序 | AC-9 占比进 / **AC-10 配方编号不进** |
| `ELE=` | ⛔ **v2 中删除** | — | 见 4.3 |
| `PRC=` | 工序码 | **排序**（保持现状） | **AC-19 顺序不进 / AC-20 重复次数仍进** |

### 4.2 COMPOSITE 的 v2 结构

```
v2|CUST=<客户码>|COMBO=<子件料号:数量>,…|CPROC=<组合工序码,…>
```

⚠️ **`CPROC=` 只存在于 COMPOSITE**（`computeComposite`），SIMPLE 串里**没有**这个 token。
A 轮 api.md 把 `CPROC=` 画进了 SIMPLE 串，是错的（评审 P1-8）。COMBO/CPROC 的组成与排序**本任务不改**，仅因共用 `STRUCTURE_VERSION` 而一并升到 v2。

### 4.3 🚨 `PART=` 必须用长度前缀，不能用 `/` 分隔

```
PART=3:动触头5:φ12×36:12×8×3        ← 正确：<字符数>:<内容> 依次拼接
PART=动触头/φ12×3/12×8×3            ← ❌ A 轮写法，有碰撞风险
```

**理由（实测）**：
1. `SalesFingerprintCalculator.assertNoDelimiter`（`:180-190`）守的是 `| = , : ∅` 五个字符，**不含 `/`**。
2. 实查 `material_recipe.symbol` 含 `/` 的有 **74 条**（`AgZnO12/Cu`、`AgNi10/Ag15CuP`…），品名含 `/` 的料号也真实存在（`AgNi10/Cu触点`）⇒ **本业务文本带 `/` 是常态**。
3. 该类的类注释已点名这种事故形态：「工序码 `["a","b,c"]` 与 `["a,b","c"]` 都渲染成 `PRC=a,b,c`，造成两个不同选配**复用同一报价料号的静默错价**」。

⇒ 品名 `A/B` + 规格 `C` 与 品名 `A` + 规格 `B/C` 在 `/` 方案下渲染出**同一个 `PART=`** → 同一料号 → **静默错价**。

**另**：品名/规格/尺寸是**自由文本用户输入**，含 `|` `=` `,` `:` 时 `assertNoDelimiter` 会抛 `IllegalArgumentException` → 用户拿到 500。
⇒ `api.md §1.2` 已补错误码 **`PART_TEXT_INVALID_CHAR`（400）**，B-5 须显式捕获并转换。

### 4.4 `PART=` / `WEIGHT=` 的注入方式（🚫 不走槽位机制）

`renderToken` 是 `switch(paramTypeCode)` + `default: throw`，token 集合由 `sel_param_type`（**实测恰好 3 行**：`MATERIAL`/`ELEMENT`/`PROCESS`，且 `SelParamCandidateService` 用 `switch` 硬匹配 = 封闭枚举）驱动。

⇒ **`PART=` / `WEIGHT=` 既不是 `sel_param_type` 成员，也不进 `renderToken` 的 case**。
**本任务定为：作为固定前缀 token 由 `computeSimple` 显式接参拼接**，🚫 **不新增 `sel_param_type` 行**（那会连带迁移种子 + `SelParamCandidateService` + `sel_template.allowed_value_key` + 选配模板管理页，而 `fronttask.md` 已明确不做该页）。

### 4.5 `ELE=` 的去向（一次**声明式**的契约删除）

v1 的 `ELE=<元素码:含量,…>` 在 v2 中**删除**，元素含量折进 `MAT=` 的括号内按材质分组。

⚠️ **连带影响**：`projectEnabledParams` 的 javadoc 写明「MATERIAL / ELEMENT **恒为槽位**（防坍缩底线）」，且 `EnabledParam` 不变量要求「每 paramTypeCode 至多一项」。移除 ELEMENT 槽位后 B-5 必须**重新论证防坍缩底线**：多材质下 `MAT=` 自身已含元素，坍缩风险从「元素丢失」变为「某个材质整组丢失」，守卫应改为**断言 `materials[]` 非空且每项元素非空**。

---

## 4.6 🆕 新表 `sel_product_no`（客户产品编号 ↔ 销售料号，多对一）

```sql
sel_product_no(
  id, customer_no, customer_product_no, customer_product_name,
  quote_part_no,        -- 销售料号（多对一的「一」侧）
  quotation_id,         -- 来源报价单，可空
  created_at/updated_at/created_by/updated_by
)
UNIQUE (customer_no, customer_product_no)   -- 编号不重（AC-2）
INDEX  (quote_part_no)                       -- 一料号多编号的反查（AC-12b）
```

**为什么不复用 `material_customer_map`**（影响面调查结论）：`uq_mcm_quote_no` 同时是 `upsertQuote` 的 **ON CONFLICT target** 与**跨客户串号检测**的载体，且 4 个组件 SQL 视图的 JOIN 不含编号维度会产生**重复渲染行**。详见 `backtask.md` B-16。

⇒ 「从产品库添加」列表 = `material_customer_map`（导入来的）**并** `sel_product_no`（选配来的），`source` 按**来源表**判定。

---

## 5. 本次不改的契约（写明理由，非留空槽）

| 对象 | 为什么不改 |
|---|---|
| `POST /refresh-snapshot` | 快照刷新与选配结构无关 |
| `CompositeProcessRequest` | 组合工序的 `defCode` / `participatingPartIndexes` / `params` 语义未变；仅前端交互改为选择器 + 有序列表，契约不动 |
| `GET /composite-processes` | 候选源未变 |
| 报价单渲染侧全部端点 | 三层模型只改**写入**结构，读侧仍走 `v_composite_child_materials` / `v_composite_child_elements`。⚠️ 但多材质后前者返回**行数**从 1 变 N，属数据变化非契约变化，回归见 `test.md` |
