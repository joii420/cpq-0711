# 接口文档 · 元素单价维护与价格策略（task-0722）

> **权威依据**：`需求说明.md` §11（尤其 §11.24 交付物对照矩阵）、`元素价格策略-原型图.html`（v9 定稿）。
> **配套**：`backtask.md`（后端任务）、`fronttask.md`（前端任务）、`单价字段配置规则.md`（组件配置）。
> 本文是前后端唯一契约。任一方需要改字段，**先改本文并知会对方**，不得单边改。

---

## 0. 通用约定

| 项 | 约定 |
|----|------|
| 统一前缀 | `/api/cpq/element-price`（元素列表增强除外，见 §4.2） |
| 响应信封 | **裸 DTO / 裸数组，无 `{code,data,msg}` 包装**（对齐现有 `ElementResource` 等既有端点） |
| 鉴权 | `Authorization: Bearer <token>`；方法级 `@RoleAllowed`，见 §9 权限矩阵 |
| 错误 | 抛 `BusinessException(status, message)`，由既有全局 mapper 转成 `{"message":"..."}`；`400` 参数非法 / `404` 不存在 / `409` 唯一冲突 |
| **边界状态码细则**（2026-07-22 测试用例评审补定） | ① **路径参数** `{id}` 指向不存在的记录 → `404`；② **请求体/查询参数**里的引用型 ID（如 `sourceId`）指向不存在或非启用记录 → **`400`**（属参数校验失败，不是资源不存在）；③ 字段超长（如 `sourceName` > 128）→ `400` 并提示具体字段与上限；④ `customerNo` **大小写敏感**，按字面精确匹配（`_GLOBAL_` 必须全大写）；⑤ 元素例外**不允许**指向已停用（`status<>'ACTIVE'`）的元素 → `400` |
| 日期 | `LocalDate` 序列化为 `"2026-07-22"`；时间戳 `OffsetDateTime` 为 ISO-8601 |
| 金额 | `BigDecimal`，**保留 4 位小数**（对齐 `cpq-decimal-display-policy`：计算列 4 位） |
| 分页 | 请求 `page`（0 基）+ `size`；响应 `{content:[], totalElements, page, size}` |
| 元素标识 | **一律用元素符号 `elementCode`**（Ag / Cu / 301），不是元素编号。`element_daily_price.element_name` 列存的就是符号，命名历史遗留，勿混淆 |
| 客户标识 | **一律用 `customerNo`（VARCHAR）**，取值为真实客户编码（`CUST-1269`）或字面量 `_GLOBAL_`。**不用 customerId(UUID)**（§11.11.4） |

---

## 1. 价格源

### 1.1 列表

```
GET /api/cpq/element-price/sources?status=&keyword=
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | String | 否 | `ACTIVE` / `DISABLED`；不传=全部 |
| keyword | String | 否 | 模糊匹配 源名称 / 网址 / 备注 |

**响应** `200` — `PriceSourceDTO[]`

```json
[
  { "id":"...", "sourceName":"上海有色网", "sourceUrl":"https://www.smm.cn/",
    "sourceType":"MANUAL", "description":"现货均价", "status":"ACTIVE",
    "createdAt":"2026-07-01T09:00:00+08:00", "updatedAt":"2026-07-01T09:00:00+08:00" }
]
```

> **排序**：启用优先 → `updated_at` 倒序。
> **下拉专用**：策略页与导入抽屉的源下拉传 `status=ACTIVE`，**只列启用态**（§11.13.1）。

### 1.2 新建

```
POST /api/cpq/element-price/sources
```

**请求** `PriceSourceUpsertRequest`

```json
{ "sourceName":"上海有色网", "sourceUrl":"https://www.smm.cn/", "description":"现货均价", "status":"ACTIVE" }
```

| 字段 | 类型 | 必填 | 校验 |
|------|------|------|------|
| sourceName | String(128) | ✅ | 非空 |
| sourceUrl | String(256) | ❌ | — |
| description | String | ❌ | — |
| status | String(16) | ❌ | `ACTIVE`(默认) / `DISABLED` |

> `sourceType` **不由前端传**，后端固定写 `MANUAL`（本期不做自动抓取，§11.13）。

**响应** `200` — `PriceSourceDTO`
**错误** `409` — `"源名称 + 网址 已存在"`（命中 `uq_eps_name_url`）

### 1.3 编辑

```
PUT /api/cpq/element-price/sources/{id}
```

请求同 1.2。**响应** `200` — `PriceSourceDTO`；`404` 不存在；`409` 撞唯一键。

### 1.4 停用 / 启用

```
POST /api/cpq/element-price/sources/{id}/status
```

**请求** `{ "status": "DISABLED" }`（或 `ACTIVE` 改回启用）
**响应** `200` — `PriceSourceDTO`

> **不提供物理删除**（§11.13.1）。停用后：不可被新策略/新导入选用；历史价格照常显示；**存量策略继续按原样取价**。

---

## 2. 价格导入

### 2.1 下载模板

```
GET /api/cpq/element-price/import-template
```

**响应** `200` — `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
`Content-Disposition: attachment; filename="元素价格导入模板.xlsx"`

表头固定 4 列：`元素符号*` / `单价*` / `货币` / `计价单位`。附一行示例数据 + 一行填写说明。

### 2.2 导入

```
POST /api/cpq/element-price/import
Content-Type: multipart/form-data
```

| 部件 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | 文件 | ✅ | `.xlsx` / `.xls`，≤ 5MB |
| sourceId | UUID | ✅ | 必须是 `ACTIVE` 的源，否则 `400` |
| priceDate | String | ✅ | `yyyy-MM-dd`；本批所有价格记为该日 |

**响应** `200` — `PriceImportResultDTO`

```json
{
  "sourceId":"...", "sourceName":"上海有色网", "priceDate":"2026-07-22",
  "operatorName":"张三", "elapsedMs":412,
  "createdCount":3, "updatedCount":2, "failedCount":1,
  "rows":[
    {"rowNo":2,"elementCode":"Ag","price":5820.0000,"currency":"CNY","priceUnit":"元/kg",
     "result":"UPDATED","message":"原值 5795.0000 → 5820.0000"},
    {"rowNo":4,"elementCode":"Ni","price":132.8000,"currency":"CNY","priceUnit":"元/kg",
     "result":"CREATED","message":null},
    {"rowNo":7,"elementCode":"Auu","price":452.0000,"currency":"CNY","priceUnit":"元/g",
     "result":"FAILED","message":"元素符号「Auu」在元素管理中不存在"}
  ]
}
```

**`result` 枚举**：`CREATED` / `UPDATED` / `FAILED`

**🔒 事务边界（§11.3.2，强制）**
- **逐行独立处理，失败行不阻断其他行入库**。上例 5 行成功即 5 行落库，`FAILED` 行只是不写。
- **严禁**实现为"任一行失败即整批回滚"。
- 校验规则见 §11.3.1：元素符号必须在 `element` 表存在**且 `status='ACTIVE'`**；单价必填且 > 0；货币/单位可空，空则取 `CNY` / `元/kg`。
- 重复导入（同 源+日期+元素）**直接覆盖**并更新 `updated_by`/`updated_at`（§11.16）。
- 落库时 `fetch_status='IMPORT'`（新增枚举值，见 backtask B1）。

**错误** `400` — 文件为空 / 格式非法 / 超过 5MB / 表头不匹配 / `sourceId` 非启用态。

---

## 3. 价格表查询

### 3.1 明细

```
GET /api/cpq/element-price/prices?sourceId=&from=&to=&keyword=&page=0&size=20
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sourceId | UUID | 否 | 不传=全部源 |
| from / to | String | 否 | `yyyy-MM-dd`；默认最近 30 天 |
| keyword | String | 否 | 模糊匹配 元素符号 / 中文名 |

**响应** `200` — 分页 `ElementPriceRowDTO`

```json
{ "content":[
    {"elementCode":"Ag","elementName":"银","priceDate":"2026-07-21",
     "sourceId":"...","sourceName":"上海有色网","sourceStatus":"ACTIVE",
     "price":5820.0000,"currency":"CNY","priceUnit":"元/kg",
     "operatorName":"张三","updatedAt":"2026-07-22T09:12:00+08:00"}
  ], "totalElements":1284, "page":0, "size":20 }
```

> 排序：`price_date` 倒序 → `element_code` 升序。

### 3.2 矩阵

```
GET /api/cpq/element-price/prices/matrix?sourceId=&from=&to=&keyword=
```

> **`sourceId` 必填**（§11.14）：同一天多个源各有各的价，一格放不下。缺失返 `400`。
> 日期跨度 **> 90 天返 `400`**：`"矩阵视图日期跨度最长 90 天，请收窄区间"`。前端默认给最近 30 天。

**响应** `200` — `PriceMatrixDTO`

```json
{
  "sourceId":"...", "sourceName":"上海有色网",
  "dates":["2026-07-16","2026-07-17","2026-07-18"],
  "rows":[
    {"elementCode":"Ag","elementName":"银","prices":[5740.0000,5762.0000,null]},
    {"elementCode":"Cu","elementName":"铜","prices":[73.8000,74.0500,74.1000]}
  ]
}
```

> `prices` 与 `dates` **等长、按下标对齐**；某天无记录为 `null`（前端渲染「—」，**不补零**）。

### 3.3 导出

```
GET /api/cpq/element-price/prices/export?<同 3.1 全部筛选参数>
GET /api/cpq/element-price/prices/matrix/export?<同 3.2 全部筛选参数>
```

**响应** `200` — xlsx 附件。导出内容 = **当前筛选的全量结果**（不分页）。

---

## 4. 元素侧增强

### 4.1 某元素的各源最新价（元素抽屉用）

```
GET /api/cpq/element-price/latest-by-source?elementCode=Cu
```

**响应** `200` — `ElementLatestPriceDTO[]`

```json
[
  {"sourceId":"...","sourceName":"上海有色网","sourceStatus":"ACTIVE",
   "price":74.5000,"currency":"CNY","priceUnit":"元/kg","priceDate":"2026-07-21"},
  {"sourceId":"...","sourceName":"某停用源","sourceStatus":"DISABLED",
   "price":73.1000,"currency":"CNY","priceUnit":"元/kg","priceDate":"2026-05-28"}
]
```

> 每个**有过价格记录**的源返回一行（取该源下 `price_date` 最大的一条）。
> 已停用的源**照常返回**，前端按 `sourceStatus` 置灰并标「源已停用」（§11.14A）。
> 无任何记录返回 `[]`，前端显示空态文案。

### 4.2 元素列表增强（改造既有端点）

```
GET /api/cpq/elements?keyword=          ← 既有端点，本期扩展响应
```

`ElementDTO` **新增一个字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| lastModifiedAt | OffsetDateTime | **最后修改时间** = `MAX(element.updated_at, 该元素所有价格记录的 updated_at)` |

> **实现口径（§11.14B）**：查询时取较大值，**不反写 `element.updated_at`**。
> **排序变更**：启用优先 → `lastModifiedAt` 倒序（原为 启用优先 → 改时倒序 → 建时倒序）。
> 既有字段 `createdAt` / `updatedAt` **保留不动**（其它调用方可能在用），前端列表只是不再展示它们。

---

## 5. 价格策略

### 5.1 读取某客户的全部策略

```
GET /api/cpq/element-price/strategies?customerNo=CUST-1269
```

> `customerNo` 传 `_GLOBAL_` 即读全局（核价成本口径）策略。

**响应** `200` — `StrategyBundleDTO`

```json
{
  "customerNo":"CUST-1269",
  "default": {
    "id":"...", "sourceId":"...", "sourceName":"上海有色网",
    "method":"AVG", "windowNum":30, "windowUnit":"DAY",
    "factor":1.0500, "premium":2.0000,
    "updatedAt":"2026-07-22T10:15:00+08:00", "updatedByName":"张三"
  },
  "exceptions": [
    {"id":"...", "elementCode":"Ag", "elementName":"银",
     "sourceId":"...", "sourceName":"上海有色网",
     "method":"LATEST", "windowNum":null, "windowUnit":null,
     "factor":1.0000, "premium":0.0000,
     "updatedAt":"2026-07-22T10:15:00+08:00", "updatedByName":"张三"}
  ]
}
```

> 未配默认策略时 `default` 为 `null`；无例外时 `exceptions` 为 `[]`。

**枚举**

| 字段 | 取值 | 说明 |
|------|------|------|
| method | `LATEST` / `AVG` / `MAX` / `MIN` | 最新一条价 / 窗口内平均 / 窗口内最高 / 窗口内最低（§11.7） |
| windowUnit | `DAY` / `WEEK` / `MONTH` / `YEAR` | 滚动区间（§11.9） |

> **`method='LATEST'` 时 `windowNum`/`windowUnit` 必须为 `null`**；其余三种 `windowNum` 必须 > 0。后端与 DB CHECK 双重校验。

### 5.2 保存客户级默认策略（新建或覆盖）

```
PUT /api/cpq/element-price/strategies/default
```

**请求** `StrategyUpsertRequest`

```json
{ "customerNo":"CUST-1269", "sourceId":"...", "method":"AVG",
  "windowNum":30, "windowUnit":"DAY", "factor":1.05, "premium":2.00 }
```

| 字段 | 必填 | 校验 |
|------|------|------|
| customerNo | ✅ | 真实客户编码或 `_GLOBAL_`；真实编码须在 `customer` 表存在 |
| sourceId | ✅ | 必须 `ACTIVE`，否则 `400` |
| method | ✅ | 四选一 |
| windowNum / windowUnit | 条件 | 见上方 LATEST 规则 |
| factor | ❌ | 默认 `1`，须 > 0 |
| premium | ❌ | 默认 `0`，可为负 |

**响应** `200` — `StrategyDTO`
**副作用**：**同事务**写一条 `element_price_strategy_log`（`action=CREATE` 或 `UPDATE`）。

### 5.3 元素级例外

```
POST   /api/cpq/element-price/strategies/exceptions        新建
PUT    /api/cpq/element-price/strategies/exceptions/{id}    修改
DELETE /api/cpq/element-price/strategies/exceptions/{id}    删除
```

新建/修改请求 = §5.2 的 `StrategyUpsertRequest` **加一个必填字段** `elementCode`。

**响应** `200` — `StrategyDTO`；`DELETE` 返 `204`。
**错误** `409` — `"该客户的元素「Ag」已存在例外配置"`（命中 `uq_eps_cust_elem`）。
**副作用**：三个操作**同事务**各写一条 log（`CREATE`/`UPDATE`/`DELETE`；`DELETE` 存**删除前**快照）。

---

## 6. 策略试算

```
POST /api/cpq/element-price/strategies/simulate
```

**请求**

```json
{
  "customerNo":"CUST-1269",
  "baseDate":"2026-07-22",
  "draft": { "default": {...}, "exceptions": [...] }
}
```

> `draft` **可选**。传了则**按草稿试算**（支持"改了还没保存就先试算"，§11.14C）；不传则按库中已存策略试算。
> `draft` 结构与 §5.1 响应的 `default`/`exceptions` 同构（`id` 可为 null）。

**响应** `200` — `SimulateRowDTO[]`

```json
[
  {"elementCode":"Ag","elementName":"银","hitRule":"EXCEPTION",
   "sourceName":"上海有色网","method":"LATEST",
   "rawValue":5820.0000,"factor":1.0000,"premium":0.0000,
   "finalPrice":5820.0000,"sampleDays":1,"hasPrice":true},
  {"elementCode":"Sn","elementName":"锡","hitRule":"DEFAULT",
   "sourceName":"上海有色网","method":"AVG",
   "rawValue":null,"factor":1.0500,"premium":2.0000,
   "finalPrice":null,"sampleDays":0,"hasPrice":false}
]
```

| 字段 | 说明 |
|------|------|
| hitRule | `EXCEPTION`（元素例外） / `DEFAULT`（客户默认） |
| rawValue | 取值结果（× 系数 + 加价**之前**） |
| finalPrice | 最终单价 = `rawValue × factor + premium`；无价时 `null` |
| sampleDays | 参与计算的**有价天数**（`LATEST` 恒为 1 或 0） |
| hasPrice | `false` 时前端整行标黄、最终单价显示「无价」 |

> **只读、不落库**（§11.14C）。返回全部**已配到策略**的启用元素；未被任何策略覆盖的元素不返回。

---

## 7. 策略变更历史

```
GET /api/cpq/element-price/strategies/history
    ?customerNo=CUST-1269&elementCode=&from=&to=&changedBy=&page=0&size=20
```

| 参数 | 必填 | 说明 |
|------|------|------|
| customerNo | ✅ | 支持 `_GLOBAL_` |
| elementCode | 否 | 传值=只看该元素例外；传 `__DEFAULT__`=只看客户级默认；不传=全部 |
| from / to | 否 | 变更时间区间 |
| changedBy | 否 | 变更用户姓名，模糊匹配 |

**响应** `200` — 分页 `StrategyHistoryDTO`

```json
{ "content":[
  { "id":"...", "changedAt":"2026-07-22T10:15:00+08:00", "changedByName":"张三",
    "targetLabel":"客户级默认策略", "elementCode":null, "action":"UPDATE",
    "changes":[
      {"field":"factor","fieldLabel":"系数","oldValue":"1.00","newValue":"1.05"},
      {"field":"premium","fieldLabel":"加价","oldValue":"0.00","newValue":"2.00"}
    ],
    "snapshot":{"sourceName":"上海有色网","method":"AVG","windowNum":30,"windowUnit":"DAY","factor":1.05,"premium":2.00}
  }
], "totalElements":6, "page":0, "size":20 }
```

**关键约定**

- **差异由后端算好**（对比同一 `(customerNo, elementCode)` 的上一条 log 快照），前端只渲染 `changes` 数组，**不做比对逻辑**。
- `changes` **只含真正变化的字段**，未变字段不出现（§11.14F.3）。
- `action=CREATE` 时 `changes` 为空数组，前端改用 `snapshot` 展示全量配置。
- `action=DELETE` 时 `snapshot` 是**删除前**的完整配置，`changes` 为空。
- `targetLabel` 由后端拼好：`"客户级默认策略"` 或 `"元素例外 · Ag 银"`，前端直接显示。
- **只读，无写接口**（§11.24 D 节：不做回滚）。

---

## 8. DTO 一览

| DTO | 用于 |
|-----|------|
| `PriceSourceDTO` / `PriceSourceUpsertRequest` | §1 |
| `PriceImportResultDTO` / `PriceImportRowDTO` | §2.2 |
| `ElementPriceRowDTO` | §3.1 |
| `PriceMatrixDTO` / `PriceMatrixRowDTO` | §3.2 |
| `ElementLatestPriceDTO` | §4.1 |
| `ElementDTO`（扩展 `lastModifiedAt`） | §4.2 |
| `StrategyBundleDTO` / `StrategyDTO` / `StrategyUpsertRequest` | §5 |
| `SimulateRequest` / `SimulateRowDTO` | §6 |
| `StrategyHistoryDTO` / `StrategyChangeDTO` | §7 |

---

## 9. 权限矩阵（§11.17 沿用所在页面现有权限，不新增权限点）

| 端点组 | 读 | 写 |
|--------|----|----|
| §1 价格源 | `SALES_MANAGER` `PRICING_MANAGER` `SYSTEM_ADMIN` | 同左 |
| §2 价格导入 | — | `SALES_MANAGER` `PRICING_MANAGER` `SYSTEM_ADMIN` |
| §3 价格表 | `SALES_MANAGER` `PRICING_MANAGER` `SYSTEM_ADMIN` | — |
| §4.1 各源最新价 | 同上 | — |
| §4.2 元素列表 | 维持既有（四角色可读） | 维持既有（`SYSTEM_ADMIN` 可写） |
| §5 策略 / §6 试算 / §7 历史 | `SALES_REP` `SALES_MANAGER` `PRICING_MANAGER` `SYSTEM_ADMIN` | 同左 |

> §1~§4 对齐「主数据维护」页权限；§5~§7 对齐「定价管理」页权限。

---

## 10. 前后端联调检查表

- [ ] 所有响应为**裸 DTO**，前端不要解 `{code,data}` 信封
- [ ] `customerNo` 全链路用字符串，`_GLOBAL_` 可正常穿透（不要在任何一层转 UUID）
- [ ] `method=LATEST` 时前端不传 `windowNum`/`windowUnit`（传了后端返 `400`）
- [ ] 导入失败行不影响其他行：造一个含错元素符号的文件，确认成功行**确已入库**
- [ ] 矩阵接口 `sourceId` 缺失返 `400`、跨度 > 90 天返 `400`，前端有对应提示
- [ ] `prices` 数组与 `dates` 等长对齐，`null` 渲染为「—」而非 0
- [ ] 历史接口 `changes` 为空且 `action=CREATE` 时，前端走「展示 snapshot 全量」分支
- [ ] 元素列表 `lastModifiedAt` 在导入价格后更新，但 `updatedAt` 不变
