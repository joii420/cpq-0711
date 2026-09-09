# 接口契约 · repair-260909

## 结论：**无接口契约变更**

没有新增/删除端点，**没有任何端点的 HTTP 方法 / 路径 / 请求体字段 / 响应体字段 / 错误码发生变化**。
⇒ 按 `task-docs.md §2.5` 跳过 `main-api.md` 回写，但须在 `test-report.md` 写明「本次无契约变更」。

## 逐个判定（不留空槽）

| 端点 | 是否改 | 判定依据 |
|---|---|---|
| `GET /config/semantic-graph/field-tree` | **契约不变，响应内容会多一个组** | 见下方专节 |
| `POST …/builder/compile` | **契约不变，生成的 SQL 会变** | B-1 改的是 `joinClause` 的拼法。响应结构（`sql` / `declaredColumns` / `requiredVariables`）不变；核价侧 `requiredVariables` 会**新增 `customerCode` / `priceBaseDate`** —— 这是**值的变化不是结构变化**（该字段本就是动态集合）|
| `POST …/builder/preview` · `/inspect` · `/save` | 不变 | 同上 |
| 组件保存相关端点 | 不变 | 不动 `component.row_key_fields`，不动 `builder_config` 的结构 |

## `field-tree` 响应的变化（不是契约变化，但调用方要知道）

**结构完全不变**，变的是**核价两方言会多返回一个组**：

```
groups[] 新增一个 { groupKind: 'PRICE', groupName: '价格策略', fields: [...] }
```

三条性质：
1. **前端无需任何改动** —— `SqlViewBuilderTab` 的 PRICE 分支已存在且已被报价侧跑通
2. **报价侧的 groups 逐字不变**（`AC-R2`）
3. 🚫 若发现报价侧多出**第二个** PRICE 组，那是缺陷不是预期（`task-260904 B-23` 同族）

## `compile` 产物里 `requiredVariables` 的变化（本次唯一的"行为可见"变化）

核价侧组件一旦选入价格列，编译产物的 `requiredVariables` 会从
`{total_material_no}` 变成 `{total_material_no, customerCode, priceBaseDate}`。

⚠️ **这需要渲染链路能绑上 `customerCode`** —— 实测 `SqlViewExecutor.enrichCustomerCode()`
**与方言无关**（只判 `namedParams` 里有没有 `customerId`，有就查出 code 塞进去）⇒ 能绑，**不需要改渲染链路**。
🚨 但这条是**推理**，必须在 `test-report.md` 里附核价侧真实渲染的实测（`AC-P3`），
🚫 不许只凭读代码宣布「能绑」。
