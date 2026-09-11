# api · repair-260910

> 前后端并行开发时**唯一的协调物**。两侧都以本文为准。

---

## 1. HTTP 端点契约：**零变更**

本次**不新增、不删除、不修改任何 HTTP 端点的方法 / 路径 / 请求参数 / 响应形状 / 错误码**。

**为什么不改**（按规则，零改动也要写清判定依据，不许留空槽）：

- 症状 1 的修复全部发生在 **SQL 生成与参数绑定层**（`SemanticCompiler` 生成谓词、`SqlViewExecutor` 补绑参数）。
  对调用方而言，`expand-driver` 的**请求不变、响应形状不变**，只是**行数从 N 变成 1** —— 那是数据正确性，不是契约。
- 症状 2 的修复走的是**已有端点** `POST /quotations/{qid}/line-items/{lid}/components/{componentId}/delete-driver-row`
  （`QuotationResource.java:1042`）。参数 `effKey` / `fp` 的**名字与类型都不变**，变的是两侧**计算它们的口径**（见 §3）。

⇒ 本次**无需回写 `dev-docs/main-api.md`**（`task-docs.md §2.5`：方法/路径/参数/响应/错误码全未变者可跳过，但须在 `test-report.md` 里写明）。

---

## 2. 🆕 展开结果新增顶层系统列 `__row_uid`

这是本次**唯一的前后端数据契约变更**。

| 项 | 约定 |
|---|---|
| **名称** | `__row_uid` |
| **位置** | 展开行的**顶层系统列**，与既有 `__nodeId` / `__parentId` / `__lvl` **同槽位**（不在 `driverRow` 内部） |
| **类型** | `string \| null` |
| **取值** | 锚点表 `id` + 各参与 JOIN 对端表 `id` 拼接（两张表均已实查确认 `PRIMARY KEY (id)`） |
| **为空的情形** | ① 手工新增行（`_origin:'manual'`，无源表主键）；② 存量快照（改动前保存的行） |
| **为空时的行为** | **两侧都必须回退到现有口径**，🚫 不许因为它为空就把行判成"无身份"：<br>· 手工行 → 现有 `row_index` 口径（AC-13）<br>· 存量行 → `buildLegacyRowKeySets` 三档回退（AC-11） |

**前端消费位置**（后端据此确认注入槽位正确）：
`QuotationStep2.tsx:2196-2199` 的 `__sys` 注入块 —— 现有代码是「带 `__nodeId` 就注入 `__sys`，纯数据驱动」，
`__row_uid` 按同一模式并列加入。

---

## 3. `effKey` / `fp` 的计算口径变更（前后端必须逐字节等价）

端点参数名不变，但**怎么算出来**两侧同时改。**这是本次最容易静默失配的地方**：
口径不一致不会报错、不会编译失败，只会表现为「删不掉」或「删错行」——也就是本次要修的那个 bug 本身。

| 口径项 | 变更 |
|---|---|
| `fp`（行指纹） | 增加 `__row_uid` 维度，**与既有 `nodeId` 维度同款叠加**。前端 `deletedRows.ts:31` ↔ 后端 `DeletedRowKeys.java:102` |
| `effKey`（唯一行键） | `__row_uid` 非空时**用它消歧**，取代按数组位置的 `#N`。前端 `useCardSnapshots.ts:330` ↔ 后端 `FormulaCalculator.buildRawRowKeys:1478` / `uniquifyRowKeys:1507` |
| **顺序纪律** | 加前缀 → 再消歧，两侧顺序必须一致（前端 `useCardSnapshots.ts:327-328` 注释已写明此约定）。顺序错了两侧算不出同一结果 |
| **存量回退** | 现有三档 `buildLegacyRowKeySets` **原样保留**，新键作为**第四档、优先匹配** |

🚨 **验收这一项的现成手段**：仓库里已有前后端逐字节一致性专项用例 ——
后端 `EffKeyNodeIdAlignmentTest`（含 `formulaResultsRowKey_and_materializerEffKey_areByteIdentical`）、
前端 `rowKeyParityQt0068.repair0805.test.ts`。**两侧改完这两个必须仍绿**（AC-14）。

---

## 4. 🆕 运行时占位符 `:customerProductNo`（后端内部契约，前端不感知）

| 项 | 约定 |
|---|---|
| **占位符** | `:customerProductNo` |
| **值来源** | `quotation_line_item.customer_part_no`（实查：该列有值率 **3837/3949**） |
| **补全方式** | `SqlViewExecutor.enrichCustomerProductNo()`，从 `namedParams` 已有的 `lineItemId` 反查。与 `enrichCustomerCode` / `enrichPriceBaseDate` 同款模式 |
| **不经 enrich 的四条旁路**（必须各自补绑） | `BuilderService.bindLiterals()`（`/preview`）· `QuoteViewValidationService` · `CostingTreeSqlValidator` · `SqlViewValidator.bindWithNullPlaceholders`（保存期 dry-run） |
| 🚨 **摆放位置** | 只许出现在 **`LEFT JOIN … ON`**，🚫 **绝不许进 `WHERE`**。理由见 `backtask.md B-2`（112 行空值会被整页签打成 0 行且不报错） |

⚠️ **不要照搬 `:customerCode` 的"硬阻断"处理**：`customerCode` 未绑定时硬阻断是对的（客户一定有值），
而 `customerProductNo` **本来就允许为空**（112/3949）—— 对它硬阻断会把 AC-2 打死。
