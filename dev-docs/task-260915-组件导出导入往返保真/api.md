# 接口契约 · task-260915 组件导出导入往返保真

> 本次**不新增端点、不改路径、不改请求参数**。只对既有 3 个端点的**响应体**做**纯增量**扩展。
> 回写 `dev-docs/main-api.md` 的时机见 `task-docs.md §2.5`（测试完成后、合并 master 前）。

---

## 涉及的既有端点（路径实查 `ComponentDirectoryResource.java`）

| 方法 | 路径 | 用途 | 本次是否改 |
|---|---|---|---|
| `GET` | `/api/cpq/component-directories/{id}/export` | 导出目录组件包 | ✅ 响应体增量 |
| `POST` | `/api/cpq/component-directories/{id}/import` | 导入**预览**（只读，不落库） | ✅ 响应体增量 |
| `POST` | `/api/cpq/component-directories/{id}/import/commit` | 导入**提交** | ⬜ 响应结构不变，行为变（恢复更多字段） |

既有 query 参数 `conflictPolicy`（默认 `RENAME`）、`ignoreMissingDeps`、`ignoreUnboundFormulas` **全部不变**。

---

## 一、`GET /{id}/export` 响应体增量

### 变更 1：`bundleVersion`

```diff
- "bundleVersion": "1.0"
+ "bundleVersion": "1.1"
```

### 变更 2：`components[]` 增加 5 个字段

```jsonc
{
  "code": "COMP-0002",
  "name": "物料",
  // …既有字段不变…
  "treeConfig":            null,          // 新增 ← component.tree_config (JSONB)，源为空则 null
  "bomRecursiveExpand":    true,          // 新增 ← component.bom_recursive_expand (boolean)
  "elementCodeField":      "元素编号",     // 新增 ← component.element_code_field
  "elementPriceField":     "元素单价",     // 新增 ← component.element_price_field
  "elementCurrencyField":  null           // 新增 ← component.element_currency_field
}
```

### 变更 3：`components[].sqlViews[]` 增加 3 个字段

```jsonc
{
  "sqlViewName": "builder_aecf34a08407",
  "sqlTemplate": "-- 树契约: …",
  // …既有字段不变…
  "builderConfig": {                      // 新增 ← component_sql_view.builder_config (JSONB)
    "tabType": "BOM",
    "variantKey": "",
    "dialect": "QUOTE",
    "axisScope": "…",
    "switches": [],
    "priceStrategy": null,
    "builderVersion": 1,
    "columns": [ /* … */ ]
  },
  "builderVersion": 1,                    // 新增 ← component_sql_view.builder_version (integer)
  "status": "ACTIVE"                      // 新增 ← component_sql_view.status
}
```

> ⚠️ `builderConfig` **原样透传 JSONB**，不做任何裁剪、规范化或重排键序 —— AC-14 要求二次往返逐字段相等。
> ⚠️ `builderConfig` 顶层也有一个 `builderVersion`，与视图列 `builder_version` 是**两个不同来源**。两个都要带，不要合并、不要假设它们相等。

### 不变项

`checksum` 的**计算方式不变**（对 `source` + `components` + `dependencies` 的实际对象序列化）。新字段自动纳入计算，**不需要改 `computeChecksum`**。
⇒ 1.1 包的 checksum 与 1.0 包不可比，这是预期行为（内容本来就不同）。

---

## 二、`POST /{id}/import` （预览）响应体增量

`ImportPreviewResult.components[]`（`ComponentPlan`）增加一项，与既有 `formulaBinding` **并列**：

```jsonc
{
  "code": "COMP-0002",
  "action": "CREATE",
  "sqlViewCount": 1,
  "formulaBinding": [ /* 既有，不变 */ ],
  "builderCoord": {                       // 新增
    "status": "RESOLVED",                 // RESOLVED | UNRESOLVABLE | NOT_BUILDER
    "message": null                       // UNRESOLVABLE 时给人话原因，其余为 null
  }
}
```

| `status` | 含义 | 前端呈现（见 `fronttask.md` F-1） |
|---|---|---|
| `NOT_BUILDER` | 该组件没有 builder 视图（`builderConfig` 为 null） | 显示 `—` |
| `RESOLVED` | 三段坐标在目标库 `semantic_tab_view` 能解析到 | 绿色 Tag「可解析」 |
| `UNRESOLVABLE` | 解析不到，`message` 说明缺哪个坐标 | **橙色** Tag「需重绑」+ Tooltip |

### 🚫 三条硬约束

1. **`UNRESOLVABLE` 不进 `blockers`，不让 `canCommit` 变 `false`**（AC-17：如实报出但不阻断导入）
2. ⚠️ 本字段的 `UNRESOLVABLE` 与 `formulaBinding[].status` 的 `UNRESOLVABLE` 是**同名不同义**的两个值：后者**会**拦提交，前者**不会**。前端不要复用同一套拦截逻辑
3. 预览是**只读**的 —— 新增的解析逻辑**绝不许写库**

### 老包（`bundleVersion` ≠ `1.1`）

`builderCoord.status` 一律为 `NOT_BUILDER`（包里根本没有 `builderConfig` 可解析）。
前端另按 F-2 依 `preview.bundleVersion` 给出「旧格式」提示，**不靠这个字段判断包版本**。

---

## 三、`POST /{id}/import/commit` （提交）

**响应结构不变**（`ImportCommitResult`，含 `createdItems[]` 等）。行为变化：

1. 恢复 8 个此前丢失的字段（清单见 `需求文档.md §②`）
2. 树 token 校验改用双判据，与组件新建/更新同口径
3. 校验失败的错误文案按 `backtask.md` B-5 改写

### 错误响应

| 场景 | HTTP | 文案要求 |
|---|---|---|
| 1.1 包 + 真正的非树页签用了 `tree_ref` | 400 | 保持现有文案不变 |
| **1.0 老包 + builder 树页签用了 `tree_ref`** | 400 | **必须同时包含**「导入包是旧格式」与「请在源库升级后重新导出」两层信息（AC-11） |
| 其他既有错误 | 不变 | 不变 |

错误响应的外层结构（`ApiResponse` + `GlobalExceptionMapper` 映射）**不变**，仍是 `code=400` + `message`。

---

## 四、兼容性声明

| 方向 | 结论 |
|---|---|
| **新后端 + 老包（1.0）** | ✅ 支持。新字段全部按 null 处理，行为与改动前一致；仅 builder 树页签 + `tree_ref` 的组件会拒绝，且给新文案 |
| **新后端 + 新包（1.1）** | ✅ 完整恢复 |
| **老后端 + 新包（1.1）** | ⚠️ **未适配，不保证**。老后端的 Jackson 反序列化会忽略未知字段（不报错），退化为 1.0 行为。本期**不为此写代码、不写测试**，仅在此声明 |
| **前端 + 老后端** | ✅ 前端对 `builderCoord` 缺失做可选处理（`builderCoord?.status`），不渲染即可，不得抛错 |

---

## 五、为什么不新增端点

考虑过给"往返一致性比对"单开一个端点（便于自动化验收），**否决**：

- 验收比对用 SQL 直查两个目录即可（见 `test.md`），不需要生产代码承载
- 新增只为测试服务的生产端点，会成为长期维护负担，且是后续"谁在调它"说不清的来源
