# 接口契约 · task-260908-取数配置器优化

## 结论：**本次无接口契约变更**

没有新增端点、没有删除端点，**没有任何端点的 HTTP 方法 / 路径 / 请求体字段 / 响应体字段 / 错误码发生变化**。

按 `task-docs.md §2.5`，这类任务可跳过 `main-api.md` 回写，但须在 `test-report.md` 写明「本次无契约变更，无需回写 main-api.md」。

---

## 为什么不改（逐个端点的判定依据）

**🚫 留空槽等于文档未完成，所以下面把「凭什么断定它不变」写清楚，而不是只写一句「无变更」。**

| 端点 | 本次是否改 | 判定依据 |
|---|---|---|
| `GET /api/cpq/config/semantic-graph/field-tree` | **契约不变，响应内容会变** | 见下方专节 |
| `POST /api/cpq/components/{id}/builder/compile` | 不变 | 请求体是 `BuilderConfig`（列清单），响应是 `CompileResponse`（sql + grain + columns）。新增边只改变**生成的 SQL 文本内容**，不改变这两个 DTO 的任何字段 |
| `POST /api/cpq/components/{id}/builder/preview` | 不变 | 同上，只是执行结果的行内容多了一列值 |
| `POST /api/cpq/components/{id}/builder/inspect` | 不变 | 体检项来自 `runInspectChecks`，本次不新增/不删除任何检查项 |
| `POST /api/cpq/components/{id}/builder/save` | 不变 | F-2 的「行键列必选」是**前端交互约束**，🚫 本次**不**在后端加对应校验（见下方专节） |
| `GET /api/cpq/components/{id}` · `POST/PUT` 组件保存 | 不变 | F-1 只改前端渲染。`elementCurrencyField` 仍在请求体与响应体中（`D-4`） |
| `/api/cpq/config/semantic-graph/nodes` · `/edges` 等写端点 | **不使用** | 本次的节点与边**通过 Flyway 迁移落库**，不走管理端点。理由：迁移可随代码进 master、可重放、可审计；走端点写的数据只存在于某一个库里 |

---

## `field-tree` 响应内容的变化（不是契约变化，但会影响调用方）

结构完全不变，**新增边导致同一个请求的响应里多出若干字段项**：

```
GET /api/cpq/config/semantic-graph/field-tree?dialect=QUOTE&tabType=BOM&variantKey=
```

`groups[]` 中锚点自己那个 `groupKind === 'MAIN'` 的组，其 `fields[]` 会**多出**：

| 新增字段项 | `sourceNodeKey` | `sourceColumn` | `displayName` | `roles` | `lookupLib` |
|---|---|---|---|---|---|
| 材料名 | `MAT_NAME_LK` | `material_name` | 材料名 | `["PART_NAME"]` | 物料（查名） |
| 生产料号（仅 QUOTE 的 BOM 源） | `MAT_PROD_LK` | `production_no` | 生产料号 | `[]` | 物料（生产料号） |

三条前端需要知道的性质：

1. **它们出现在 MAIN 组内，不是独立分组** —— 由 `FieldTreeBuilder.syntheticLookupFields` 内联产生（AC-7）。前端**不需要为此加任何分支**，现有渲染逻辑（`renderGroup` + `lookupLib` 徽标 `:1123`）已经能正确显示。
2. **`groups[].fields[]` 数量增加** ⇒ 面板顶部「共 N 个字段」的计数会变大。这是预期的，🚫 不要把它当回归。
3. **COALESCE 多源只出现一次** —— 物料BOM 的「材料名」虽由两条边产生，但 `syntheticLookupFields:515-517` 按 `coalesceGroup` 去重，只用 `fallback_order` 最小的节点（`MAT_NAME_LK`）代表。所以面板上是**一个**「材料名」而不是两个。

---

## 为什么「行键列必选」不加后端校验

F-2 要求行键列自动带出且不可移除。**本次刻意只做前端约束，不在 `save` 端点加「必须包含全部 ROW_KEY 列」的校验。**

三条理由：

1. **存量会被打死**：现存已保存的 `builder_config` 中有相当一部分没有输出全部行键列。加了校验，这些组件**一打开就再也保存不了**（改任何一个字都被 400 拦），而它们的数据是好的。
2. **同型病刚发生过**：`task-260904` 第二批的教训是「料号列校验在 `ComponentService`（保存期）与 `BuilderService.runInspectChecks`（保存前体检）各有一处执行点，只改下游 ⇒ 上游恒 400、下游放行永不生效」。要加校验就得**同时改两处**，那是另一个规模的改动，不在本期范围。
3. **风险不对称**：前端约束失效的后果是「用户又能删行键列了」（回到今天的状态）；后端校验写错的后果是「整批存量组件无法保存」。

📌 若后续认为必须有后端兜底，作为独立条目进 `BACKLOG.md`，**并同时改上述两个执行点**。
