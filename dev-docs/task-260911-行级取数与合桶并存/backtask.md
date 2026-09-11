# backtask · task-260911

> 后端只按本文做。与前端唯一的协调物是 `api.md`（本任务**前端零改动**，见 `fronttask.md`）。
> AC 原文在 `需求文档.md ③`，本文**只标编号不复制**。
> **A0 裁决（2026-09-11）**：谓词形态**甲**（集合成员）· 回分键**丙**（新增单点投影方法，不动 `expandMulti`）· 标记**乙**（节点级 + 页签级覆盖）。

---

## 第一组 · 语义图：新增「行级维度」角色

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-9, AC-13 | 迁移：给 `semantic_node_column.roles` 引入新角色（建议名 `ROW_SCOPE`），并给 `CUSTOMER_PART`(QUOTE) 的 `customer_product_no` 打上。<br>⚠️ 实查现状：该节点 5 列 `roles` **全为空 `{}`**；全库现有角色仅 `ROW_KEY`/`PART_NO`/`SORT`/`PART_NAME` 四种，**新角色是加法式的**，不得改动既有值。<br>🚫 **不要动 `QuoteRegistry.java`** —— 那是数据集导入的 sheet 定义，**不是编译器读的图**（此处 `repair-260910` 引错过一次） |
| **B-2** | AC-9 | `SemanticGraphLoader` / `SemanticGraphSnapshot` 能把新角色带进内存图。<br>⚠️ **先确认是否需要改**：`roles` 是数组列，loader 若整体透传则**零改动**——是的话在回报里写明「确认无需改 + 依据」，不要为了有产出而改 |
| **B-3** | AC-3, AC-4, AC-13 | `SemanticCompiler`：见到带 `ROW_SCOPE` 的列时，在 **`LEFT JOIN … ON`** 生成 `AND <别名>.<列> = ANY(:<列>s)` **集合成员**谓词，取代 `repair-260910` 的标量相等谓词。<br>🚨 **绝不许写进 `WHERE`** —— 实测 **128/3970** 明细行客编为空，进 `WHERE` 会让这些卡片整页签 **0 行且不报错**。沿用 `repair-260910 AC-6` 的三态证伪检查器复验 |
| **B-4** | AC-7, AC-9 | 两层覆盖：页签级（`semantic_tab_view_column`）优先于节点级默认。**复用 `SemanticCompiler:746` 已有的两层合并机制（`D-35`），不新造** |

## 第二组 · 绑值：从"逐行标量"改为"整单集合"

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-5** | AC-1, AC-2, AC-8 | `SqlViewExecutor`：新增 `enrichCustomerProductNos`（**复数**）——从 namedParams 已有的 `:quotationId` 一次查出该单**全部** `customer_part_no` 去重集合。<br>**同时废弃** `repair-260910` 加的单数版 `enrichCustomerProductNo` + `queryLineItemCustomerPartNo`（它按 `lineItemId` 逐行反查，正是要消掉的那 N 条 SQL）。<br>⚠️ 仍**不要加进程级缓存**（`customer_part_no` 可被用户改） |
| **B-6** | AC-5 | 🚨 **空值兜底 —— 已由 S2 在 SQL 层实证为「方案能否成立的必要条件」，不是配套守卫**（证据：`证据/S2-判别性反例实证-AC5静默失败面-260911.md`）。<br>**实证结论**：标量谓词下客编为空时 `= NULL` 恒 UNKNOWN ⇒ 该料号所有 `dqcp` 行都不匹配 ⇒ LEFT JOIN **天然产生一行"未匹配"记录**（物料侧有值、客编侧全 NULL），这就是 V1 的 1 行从哪来。<br>**换成集合谓词后这个副产品消失**：集合 `{CP1,CP2,CP3}` 不含 NULL，而该料号的 CP1/CP2/CP3 **会匹配上** ⇒ "未匹配那一行"根本不产生 ⇒ 实测**桶内属于空客编明细行的行数 = 0** ⇒ 分发层挑不到 ⇒ `AC-5` 必红。影响面 **128/3970** 明细行。<br>🚫 **三条已被排除的做法**：往集合塞 NULL 哨兵（PG `= ANY` 对 NULL 元素同样 UNKNOWN）· 加 `OR customer_product_no IS NULL`（那匹配的是表里客编为 NULL 的**数据行**，不是"本明细行没有客编"）· 把"本行客编是否为空"写进谓词（= 行级条件，**又破坏合桶**，回到原点）。<br>✅ **唯一出路 = 分发层构造**：桶内无匹配行时，**必须**基于该料号的任一桶内行构造返回行，并将作用域列（及同源于对端表的其余列）置空。🚫 不得返回 0 行；🚫 不得回退逐行查询（那会重新破坏合桶）。<br>📌 数据上安全：桶内这几行的**物料侧列完全相同**（同一个 `dqm` 行 LEFT JOIN 出来的），差异只在对端表侧的列。 |

## 第三组 · 分发：按行从桶里挑（A0 裁决丙）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-7** | AC-1, AC-3, AC-6, AC-12 | **新增一个单点投影方法**：输入 = 桶里某料号的全部行 + 本明细行的作用域值（客编），输出 = 属于该行的那一行。<br>🚫 **不要改 `expandMulti` 的返回结构** —— 它是 `task-260825` 刚优化过的性能热点，A0 已裁决走丙。<br>"哪一列是作用域列"**从 `builder_config.columns` 的 `sourceNodeKey`/`sourceColumn`/`viewColumn` 三元组读，🚫 不硬编码列名** |
| **B-8** | AC-1, AC-3, AC-6 | 各分发调用方接入 B-7（`ConfigureSnapshotService` 的整单物化路径、`CardSnapshotService.expandForPartSet` 等）。**逐个列出你接入了哪些点**，回报里给清单 |

## 第四组 · 让合桶重新接纳它

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-9** | AC-2, AC-7 | `viewHasNoRowDimension` / `eligibleForQuoteBucket` / `DriverBatchSafetyAuditor`：**撤回** `repair-260910` 为绕开合桶而加的 3 处行维度登记，让这类视图重新判为可合桶。<br>🚨 **撤回的前提是 B-7/B-8 已经能按行挑对** —— 顺序错了就会退回"页签 1 行但客编恒空且不报错"那个静默故障（`repair-260910` 的后端代理正是在这里挡下一次假修复） |
| **B-10** | AC-4, AC-11, AC-13 | 视图重编译（走 `BuilderRecompileService.recompileAndRealign`）。<br>🚦 **属 `CLAUDE.md §3.2` 红线（改共享环境全局配置），只呈报不自行执行**：先跑 `confirm:false` 预览拿影响面数字（`repair-260910` 实测全量 = 22 条快照 / 31 模板 / 125 张单；定向到目标模板可压到个位数），备份后交主线呈报用户批准 |

---

## 🚫 后端明确不做

| 项 | 理由 |
|---|---|
| 物化层 `editRows → row_data` 错位 | 与本任务正交（它在物化层），用户已裁决不登记 |
| BOM 闭包兄弟行串入 | 与本任务根因正交，本机制消不掉 |
| 另两个「产品」视图的模板快照推送 | 用户裁决不做。⚠️ 但 **B-10 会重编译它们的 `sql_template`**，AC-11 要确认其渲染层不变 |
| 存量已冻结单重算 | 沿用 `repair-260910 D-27` 先例 |
| 取数配置器 UI 暴露该标记 | 本期通过迁移写入即可（`roles` 现有值也都是迁移写的，无 UI） |

---

## 自检要求（`CLAUDE.md §6.1`）

- `./mvnw test` 在 **worktree 的 `cpq-backend/`** 跑；**先清产物再构建**
- ⚠️ **不要与其他代理并发跑 maven**（`testing.md §4.2.5`，新晋规则）：`quarkus:dev` 的热重载同样会改写 `target/classes`；按 **mtime 确权**，🚫 不要用 `-Dsurefire.reportsDirectory`（3.5.4 下静默失效）
- ⚠️ 全量 `mvnw test` **永远不可能全绿**（`mat_*` 表本库从未创建，235 处红是既有状态），只对本次相关测试类做绿判
- 🚫 不要 curl 主仓 8081/5174 验自己的改动（共享 dev server 跑的是主仓代码 = 假绿）；用临时端口 + `--noproxy '*'`；判后端健康看业务端点返 **401**，🚫 不用 `/q/health`（恒 404）
- **迁移必须建好即 `git add`**，不要等任务做完再提交（`RECORD` 2026-09-09 有实证：子代理中途被限流中断，迁移落了库文件没进 master，**8081 对所有会话挂掉**）
