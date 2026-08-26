# api.md · 取数配置器（task-260819）

> 本文件是**前后端唯一协调物**。两个子代理互相看不见，只共享这一份。
> 🚫 契约变更必须走 `task-docs.md §4`「开工后 AC/契约变更」四步 —— 尤其是**主动通知在跑的子代理**，
> 它们在派出时读过一次本文件，之后改了**不会知道也不会重读**。
> 合并前须按 `task-docs.md §2.5` 回写 `dev-docs/main-api.md`。

---

## 0. 一个架构决策（先定死，否则两边各写一个编译器）

🚨 **编译器只在后端，前端不实现任何一份 SQL 生成逻辑。**

D-21 要求「生成的 SQL」右侧常驻、随拖拽实时刷新。这看起来像该在前端算（零延迟），但：

| | 前端也实现一份 | 只在后端（本方案） |
|---|---|---|
| 铁律（`QuotePendingRewriter` 可识别形状 / `is_current` 只进顶层 WHERE / 闭包三铁律） | 要写两遍 | 一处 |
| 方言参数化（D-19） | 要写两遍 | 一处 |
| 实时面板与最终落库的 SQL 是否**逐字相同** | **不保证** —— 两份实现必然漂移 | 恒等 |

本项目在「同一份知识两处实现」上已有实证教训（AP-40 / AP-41 / D-33 的 `main2` 漏统计都是同源）。
**因此：前端拖拽 → 300ms debounce → 调 `POST .../builder/compile` → 拿到 SQL 文本直接显示。**
面板显示的 SQL 与保存落库的 `sql_template` **来自同一次编译，逐字相同**（AC-49 断言②的前提）。

---

## 1. 语义图（D-27 · 一期只做端点，UI 在二期）

基路径 `/api/cpq/config/semantic-graph`。

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| `GET` | `/` | **全部 4 角色** | 读全图。`SALES_REP` / `SALES_MANAGER` / `PRICING_MANAGER` / `SYSTEM_ADMIN` 返回**内容完全相同**（AC-56 断言③） |
| `GET` | `/field-tree?tabType=&variantKey=&selectedConfig=` | 全部 4 角色 | 配置器左侧字段面板的数据源（含**两层 roles** 合并结果）。响应形状见 §1.4 —— 🔴 **不是扁平节点数组** |
| `POST` | `/nodes` `/edges` `/tab-views` | `SYSTEM_ADMIN` | 新增。非超管一律 **403**，且库中数据逐行不变（AC-56 断言①） |
| `PUT` | `/nodes/{id}` `/edges/{id}` `/tab-views/{id}` | `SYSTEM_ADMIN` | 修改 |
| `DELETE` | `/nodes/{id}` `/edges/{id}` `/tab-views/{id}` | `SYSTEM_ADMIN` | 删除。被引用时由**库层外键**拒绝（AC-54） |
| `POST` | `/validate` | `SYSTEM_ADMIN` | **干跑**四道校验，不写库。给管理页「校验并保存」用 |

### 1.1 `GET /` 响应

```jsonc
{
  "graphVersion": 15,                     // 每次成功保存 +1
  "updatedAt": "2026-08-20T14:22:03",
  "updatedBy": "张工",
  "nodes": [{
    "id": "uuid", "nodeKey": "ELEMENT_BOM_ITEM", "displayName": "物料与元素BOM",
    "shortName": "元素BOM",                // Sheet 简称，视图列名 _<简称>_<列名> 的构成部分（D-13）
                                          // ⚠️ 取法开工前定死、定后不可改（改了等于改全部绑定路径）
    "nodeKind": "SHEET",                  // SHEET | LOOKUP | FUNCTION
    "physicalTable": "element_bom_item",
    "scope": "FULL",                      // FULL = customer_no + is_current + system_type
    "anchorExpr": "ebi.material_no",
    "grainColumns": ["material_part_no", "component_no"],
    "discriminator": "price_type='INCOMING_MATERIAL_PROCESS'",  // 判别式，AC-43 要求展示
    "fixedPredicate": null,               // 查名维表用：customer_no = :customerCode
    "funcSignature": null,                // FUNCTION 用
    "dialect": "QUOTE",
    "sourceHandler": "Q04ElementBomHandler",   // 对账断言用（AC-36）
    "columns": [{
      "id": "uuid", "dbColumn": "component_no", "displayName": "元素",
      "dataType": "TEXT", "isCode": true,
      "roles": ["ROW_KEY"],               // ← 节点级默认（D-35）
      "sortOrder": 2
    }],
    "usedBy": ["材质元素 · 主源"],
    "orphanReason": null                  // 非空 = 孤儿 Sheet 及其原因
  }],
  "edges": [{
    "id": "uuid", "fromNodeId": "uuid", "toNodeId": "uuid",
    "edgeKind": "LOOKUP",                 // GRAIN | SUB | SAME | JOIN | LOOKUP | PRICE
    "cardinality": "MANY_TO_ONE",
    "keys": [{"seq": 0, "leftColumn": "material_part_no", "rightColumn": "code"}],
    "fallbackOrder": 0,                   // 多源 COALESCE 顺序；同 from 下唯一
    "coalesceGroup": "ebi_name",          // 同组 = 一条逻辑边（边数按此合并计数 = 19）
    "assertStatus": "PASS",               // PASS | FAIL | THIN | NA
    "assertSampleRows": 128,              // ← THIN 判据，见 §1.3
    "usedByTabs": ["材质元素"]
  }],
  "tabViews": [{
    "id": "uuid", "tabType": "费用类",
    "variantKey": "INCOMING_FIXED",       // ← D-34 分立建模
    "variantLabel": "来料固定加工费",
    "anchorNodeId": "uuid", "switches": ["CLOSURE"], "dialect": "QUOTE",
    "nodes": [{"nodeId": "uuid", "role": "MAIN", "addDims": []}],
    "columnRoles": [{"columnId": "uuid", "roles": ["PART_NO","ROW_KEY"]}]  // ← 页签级覆盖（D-35）
  }]
}
```

### 1.2 写端点的请求与响应

请求体即上述对应片段（不含 `id` 时为新增）。**成功** `200` 返回新的 `graphVersion`；**校验不过** `400`：

```jsonc
{
  "code": "SEMANTIC_VALIDATION_FAILED",
  "failedCheck": "EDGE_CARDINALITY",      // PHYSICAL_EXISTENCE | EDGE_CARDINALITY | PATH_UNIQUENESS | HANDLER_RECONCILE
  "message": "边基数断言未通过：material_recipe.code 有 3 个值重复",
  "detail": {
    "edgeId": "uuid", "targetTable": "material_recipe", "rightColumn": "code",
    "duplicates": [{"value": "AgNi11#-Ⅰ", "count": 2}, {"value": "CuW70", "count": 2}],
    "assertionSql": "SELECT code, count(*) FROM material_recipe GROUP BY 1 HAVING count(*) > 1",
    "suggestion": "改成 ONE_TO_MANY，或补一组连接键把粒度收窄到唯一"
  },
  "checks": [ {"check":"PHYSICAL_EXISTENCE","status":"PASS"}, {"check":"EDGE_CARDINALITY","status":"FAIL"},
              {"check":"PATH_UNIQUENESS","status":"SKIPPED"}, {"check":"HANDLER_RECONCILE","status":"SKIPPED"} ]
}
```

### 1.3 四道校验的固定次序与阻断性

| 序 | 校验 | 阻断 | 备注 |
|---|---|---|---|
| ① | `PHYSICAL_EXISTENCE` 表/列在 `information_schema` 存在 | **是** | **必须最先跑** —— 表列都不存在时，后三道的断言 SQL 根本写不出来 |
| ② | `EDGE_CARDINALITY` 多对一边的右侧键唯一 | **是** | 见下方盲区处理 |
| ③ | `PATH_UNIQUENESS` 从 anchor 可达且路径唯一 | **是** | 与编译期的 AC-10 成对 |
| ④ | `HANDLER_RECONCILE` 节点列 ⇄ `Q*Handler` 实际 put 的列 | 否，**告警** | 另一侧是代码、改代码要发版，拦住等于让图被待发版的 handler 卡死 |

🚨 **② 的固有盲区必须显式处理**（D-32 实测）：样本量过小时基数断言**必然通过**。
实测 `INCOMING_MATERIAL_RECYCLE` 全库仅 1 行，任何基数声明都能过 —— 而写 `bom_view` 的人显然不放心，他加了 `ORDER BY seq_no LIMIT 1`。
**规定**：目标表在当前收窄条件下行数 `< 30` 时，`assertStatus` 返回 **`THIN`**（不是 `PASS`），响应 `200` 但带 `warnings`，管理页显示「证据不足：样本仅 N 行，该断言不构成保证」。

### 1.4 `GET /field-tree` 响应（🔴 2026-08-21 补 · 三方形状不一致，此处裁决）

**背景**：后端首版返回扁平 `List<NodeDTO>`，而前端与测试都按 `{groups:[...]}` 实现 —— 形状完全不同，联调必炸。

**裁决：采用 `{groups:[...]}`**。判据：① 字段面板 UI 本身就是**按 Sheet 分组折叠**的（原型即如此）；② `conflict` 是**组级**标记（D-08 要求「与已选冲突的整组置灰」），挂在扁平列上表达不出来；③ 本端点的定位是「配置器字段面板的数据源」，不是通用图查询 —— 通用查询用 `GET /`。

```jsonc
{
  "tabType": "费用类", "variantKey": "INCOMING_FIXED",
  "anchorDesc": "来料费用 按成品料号收窄",     // 配方条的锚点说明行
  "availableTabTypes": ["主件","材质元素","零件","外购件","费用类","BOM"],
  "variants": [{"key":"INCOMING_FIXED","label":"来料固定加工费","view":"ll_view · 现网 13 个组件"}],
  "switches": ["CLOSURE"],
  "groups": [{
    "groupKey": "INCOMING_FIXED",
    "groupName": "来料固定加工费",
    "groupKind": "MAIN",              // MAIN | GRAIN | SUB | SAME | JOIN | LOOKUP | PRICE
    "dims": ["投入料号"],              // 该组的展开维度，纯展示
    "conflict": false,                // ← 组级：true = 与已选列冲突，整组置灰（AC-16）
    "conflictReason": null,           // conflict=true 时必须给可读原因（用户不写 SQL）
    "fields": [{
      "sourceNodeKey": "INCOMING_FIXED", "sourceColumn": "base_value",
      "displayName": "基准值", "dataType": "MONEY",
      "roles": ["ROW_KEY"],           // 两层 roles 合并后的结果（节点级默认 + 页签级覆盖，D-35）
      "viewColumn": "_来料加工_基准值", // (Sheet,列) 纯函数，前端只读展示不得自行拼接（AC-11）
      "lookupLib": null,              // 查名库名，如「物料主档」；非查名列为 null
      "isCore": false                 // 价格策略原子组的核心列标记（删它整组走，AC-21）
    }]
  }]
}
```

🚦 **`conflict` 只有带 `selectedConfig`（当前已选列的 JSON）查询参数时才计算**；不带时恒 `false`。
🚦 **`isCore` 后端必须给** —— 前端据此区分「删元素单价整组走」与「删货币仅自身走」。缺了它前端会退化成按字段名正则猜（原型里的脆弱写法），已明确废弃。

### 1.5 请求体与错误信封的统一口径（🔴 2026-08-21 补）

**① `POST /compile` 与 `POST /inspect` 的请求体 = `builder_config` 对象本身**（§2.1 的结构，不再外套一层）。
**② `POST /preview` 的请求体 = `builder_config` + 预览参数**：

```jsonc
{ /* ...§2.1 builder_config 全部字段（含 switches.includeChildParts）... */,
  "customerCode": "罗克韦尔",
  "partNo": "S-3120014539"
}
```

🚫 **不要传顶层 `includeChildParts`** —— 闭包开关的唯一位置是 **`switches.includeChildParts`**（在 config 里面）。
> 🔴 **2026-08-21 更正**：本节原示例里列了一个顶层 `includeChildParts`，那是**我写错的**。后端 `PreviewRequest`
> 虽然声明了同名字段，但编译器实际读的是 `BuilderConfig.includeChildParts()` → `switches.get(...)`，
> **顶层那个从未被读取**。按原示例传的人，闭包开关会**静默失效**（预览结果不含子件，且不报任何错）。

**③ 🚨 响应一律「裸体」，不套 `ApiResponse{code,message,data}` 信封。**

成功响应的字段直接在根（`sql` / `rowCount` / `groups`…），错误响应的 `code`/`failedCheck`/`detail` 也直接在根（§1.2、§2.5 的示例即为准）。

> **为什么单独写这一条**：后端首版套了项目惯用的 `ApiResponse` 信封，靠读测试文件才发现不一致并改正；而前端的 `buildApiError` 目前读的是 `error.response.data.data`（假设有信封）—— **两边正好相反**，不统一就会出现「后端返回了结构化错误、前端只显示一句 message」的静默降级。
> 📌 **前端需据此改 `buildApiError` 的读取路径**（`error.response.data` 而非 `.data.data`）。🚫 不要去改全站公共的 `api.ts`，只在本任务的 service 层处理。

---

## 2. 取数配置器（builder）

基路径 `/api/cpq/components/{componentId}/builder`。角色：`SYSTEM_ADMIN` + `PRICING_MANAGER`（与组件管理现有口径一致，开工前由后端复核并在 `backtask.md` 记录实测结论）。

| 方法 | 路径 | 说明 | 服务的 AC |
|---|---|---|---|
| `GET` | `/` | 读 `builder_config` + `builder_version` + 过期标记（响应体见 §2.1a） | AC-34 |
| `POST` | `/compile` | 由 `builder_config` 编译出 SQL，**不落库**。右侧实时面板用 | AC-49、AC-9、AC-11 |
| `POST` | `/preview` | 真实预览：执行编译产物，只读连接 + `LIMIT 50` + 5s 超时 | AC-26 ~ AC-28 |
| `POST` | `/inspect` | 保存前体检（阻断项 + 告警项） | AC-13、AC-17 ~ AC-19、AC-29、AC-30 |
| `PUT` | `/` | **一体化保存事务** | AC-2、AC-12、AC-22、AC-31 |
| `POST` | `/detach` | 转为手写 SQL，**不可逆** | AC-33 |

### 2.1 `builder_config` JSONB（唯一权威，D-14 的 `builder_version` 与它同存）

```jsonc
{
  "builderVersion": 1,
  "tabType": "费用类",
  "variantKey": "INCOMING_FIXED",          // D-34；无 variants 的页签为 null
  "switches": {"includeChildParts": false},
  "columns": [{
    "sourceNodeKey": "INCOMING_FIXED",     // 取自哪个节点
    "sourceColumn": "base_value",          // 该节点的哪一列
    "fieldName": "加工费",                  // 用户起的显示名（D-12：字段名 = 显示）
    "viewColumn": "_来料加工_基准值",        // 系统生成，用户不碰（D-12/D-13：列名 = 来源）
    "fieldType": "INPUT_NUMBER",
    "isAmount": true, "inSubtotal": true
  }],
  "priceStrategy": null                    // 价格策略原子组，见 §2.4
}
```

🚫 `viewColumn` **由后端按 `(Sheet简称, 列名)` 纯函数生成**，前端只读显示、不得自行拼接（AC-11 断言：改字段名后 `viewColumn` 与 SQL 别名**纹丝不动**）。

### 2.1a `GET /` 响应（🔴 2026-08-20 补：原文只写「+ 过期标记」没定字段名 —— 留白处三方各填一套，是并行开发的典型裂缝）

```jsonc
{
  "builderConfig": { /* §2.1 的结构 */ } | null,   // null = 尚未用配置器配过（NEW 或 LEGACY 两种情况）
  "builderVersion": 1 | null,
  "viewState": "NEW",                // 🔴 2026-08-21 新增，三态见下表
  "isLegacyHandwritten": false,      // == (viewState === 'LEGACY_HANDWRITTEN')，保留兼容
  "isStale": false,                  // == (builderVersion < currentCompilerVersion)
  "currentCompilerVersion": 3        // 当前编译器版本，前端据此渲染过期提醒条（AC-34）
}
```

🚦 **字段名以本节为准。**

### 🔴 `viewState` 三态（2026-08-21 修正 —— 原定义把三态压成两态，导致配置器对所有组件都进不去）

| viewState | 判据 | 前端该显示 |
|---|---|---|
| **`NEW`** | 该组件**没有任何 `component_sql_view` 行** | ✅ **空白配置器，可直接开始配** |
| **`LEGACY_HANDWRITTEN`** | 有 `sql_view` 行、但 `builder_config` 为 NULL | 引导页「存量手写视图，不支持接管」（N-3 / AC-32） |
| **`BUILDER`** | `builder_config` 非空 | 回填已有配置 |

🚨 **原定义 `isLegacyHandwritten == (builderConfig === null)` 是错的** —— 它让 `NEW` 与 `LEGACY_HANDWRITTEN` 无从区分。
后端严格照此实现（`if (view == null || view.builderConfig == null) isLegacyHandwritten = true`），于是**全新组件也被判成存量手写**、前端弹引导页 → **配置器对任何组件都进不去**。
这是主线的契约定义错误，不是实现错误。

### 2.2 `POST /compile` 响应

```jsonc
{ "sql": "WITH RECURSIVE bom_closure AS (...)\nSELECT ...",
  "declaredColumns": ["hf_part_no", "_来料加工_基准值"],
  "requiredVariables": ["customerCode"],
  "grain": ["投入料号"],                      // 当前行粒度，前端粒度条显示（AC-15）
  "rewriterCompatible": true,                // 铁律自检：TABLE_TOKEN 正则回扫命中 ≥1（AC-9）
  "warnings": [] }
```

编译失败（如路径歧义）返回 `400`：

```jsonc
{ "code": "COMPILE_PATH_AMBIGUOUS",
  "message": "从「材质元素」锚点到「物料主档」存在两条路径，编译器不猜",
  "paths": [["物料与元素BOM","物料主档"], ["物料与元素BOM","物料BOM","物料主档"]],
  "suggestion": "在页签视图上显式指定 preferredPath，或删掉其中一条边" }
```

### 2.3 `POST /preview` 响应（含 0 行诊断，AC-27 / AC-28）

```jsonc
{ "rowCount": 2, "columns": ["hf_part_no","_来料加工_基准值"],
  "rows": [{"hf_part_no":"S-3120014539","_来料加工_基准值":12.5}],
  "elapsedMs": 340,
  "diagnostics": [
    {"level":"WARN","code":"COLUMN_ALL_NULL","column":"_来料加工_比例",
     "message":"整列 2/2 行全为 NULL —— 疑似绑错列；该 Sheet 实测值落在「基准值」，pricing_price 恒空"}
  ] }
```

**0 行时** `rowCount: 0` + `diagnostics` 必须给**可操作**诊断（哪一层收窄把行滤没了），🚫 不许只返回空表格。

### 2.3a `POST /inspect` 响应（🔴 2026-08-21 补 —— 原文只有请求体和一句文字说明，**响应体从未定义**，导致前后端各填一套、前端运行时白屏）

```jsonc
{
  "blocked": false,          // true = 有 ERR 项，保存会被拒
  "items": [                 // ⚠️ 字段名是 items 不是 checks
    { "level": "ERR",  "code": "MISSING_IDENTITY_COLUMN", "message": "料号列与名称列至少配一个" },
    { "level": "WARN", "code": "DUPLICATE_FIELD_NAME",    "message": "字段名「项次」重复，不阻断保存" }
  ]
}
```

`level` 取值：`ERR`（阻断）/ `WARN`（告警）。前端只渲染这两类，全通过时显示一行「检查通过」（F-8 / D-22）。

### 2.4 `PUT /` 一体化保存（AC-2：三件套由**同一次**保存原子产出）

单事务内完成：① `component_sql_view`（`sql_template` / `declared_columns` / `builder_config` / `builder_version`）
② `component.fields`（字段名 + 绑定路径 + 类型 + 金额/小计）③ 组件级属性（`tab_type` / `part_no_field` / `part_name_field` / `row_key_fields` / `sort_field`）
④ 价格策略三项绑定回填 ⑤ `refreshSnapshotsByComponent` 刷模板 snapshot（**必须按 `sortOrder` 精确匹配** —— AP-40）。

任一步失败整体回滚。响应 `200` 返回新的 `builderVersion` 与受影响的模板数。

**请求体（🔴 2026-08-21 补完整示例 —— 原文只说「需带 `confirmedImpact`」，没写清是扁平还是嵌套，实测三方填了两套）**：

```jsonc
// ✅ 正确：扁平 —— builder_config 的字段与 confirmedImpact **平级**
{
  "builderVersion": 1,
  "tabType": "费用类",
  "variantKey": "INCOMING_FIXED",
  "switches": { "includeChildParts": false },
  "columns": [ /* ... */ ],
  "priceStrategy": null,
  "confirmedImpact": false        // ← 与上面这些字段同层，不是另一个对象
}

// ❌ 错误：不要包一层
{ "builderConfig": { "tabType": "...", ... }, "confirmedImpact": false }
```

🚦 **三个写端点的请求体形状一致**，都是「裸 `builder_config`」：
`POST /compile` 与 `POST /inspect` = 纯 config；`PUT /` = config **+ 平级的 `confirmedImpact`**；
`POST /preview` = config **+ 平级的 `customerCode` / `partNo` / `includeChildParts``（§1.5 ②）。
后端对应 `SaveRequest extends BuilderConfig`（继承，不是持有），所以 JSON 一定是扁平的。

⚠️ **包成 `{"builderConfig":{...}}` 的后果很隐蔽**：后端会把 `tabType` / `variantKey` 读成 `null`，
然后报一个与真因毫不相干的错（比如「页签视图不存在」），排查时很容易往错误方向走。

**删除列**时请求需带 `confirmedImpact: true`，否则返回 `409 + 影响面清单`（AC-31）。

### 2.5 错误码总表

| HTTP | code | 触发 |
|---|---|---|
| 400 | `SEMANTIC_VALIDATION_FAILED` | 语义图四道校验 ①②③ 任一不过 |
| 400 | `COMPILE_PATH_AMBIGUOUS` | 路径歧义（AC-10） |
| 400 | `COMPILE_GRAIN_CONFLICT` | 粒度冲突保存期兜底（AC-17） |
| 400 | `INSPECT_BLOCKED` | 体检阻断项（标识列缺失 AC-30 / 金额小计不成对 / 粗粒度列勾小计 AC-18） |
| 403 | `ROLE_REQUIRED` | 写语义图非 `SYSTEM_ADMIN`（AC-56） |
| 409 | `IMPACT_CONFIRM_REQUIRED` | 删除列未确认影响面（AC-31） |
| 409 | `FK_STILL_REFERENCED` | 删节点仍被边/页签引用（AC-54，由库层外键翻译而来） |
| 500 | — | 🚫 **不允许**：以上场景一律给结构化 4xx，不得漏成 500 |

---

## 3. 前端要点（避免踩已知坑）

- 探本机服务一律加 `--noproxy '*'`（本机 `http_proxy` 会让 `localhost` 走代理返 502）
- 判后端健康看业务端点返 **401**，不要用 `/q/health`（未装 smallrye-health，恒 404）
- `/compile` 用 **300ms debounce**，拖拽连续操作不要每帧发请求
- 切换「页签类型」或「数据来源」= 换主源 Sheet，已选列全部失效 → 必须二次确认再清空（D-34）
