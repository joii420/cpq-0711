# backtask · repair-260910

> 后端只按本文做。与前端唯一的协调物是 `api.md`。
> AC 原文在 `问题说明.md ⑥`，本文**只标编号不复制**。
> 🚨 本任务是**协议级改动**：`问题说明.md §5.3` 的检查点清单是强制的，**写代码中逐项勾掉，没勾完不算写完**。

---

## 第一组 · 取数维度（`:customerProductNo`）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-1, AC-2, AC-4 | `SqlViewExecutor.java` 新增 `enrichCustomerProductNo(Map<String,Object> namedParams)`：若已含 `customerProductNo` 则不覆盖；否则从 `namedParams` 的 `lineItemId` 反查 `quotation_line_item.customer_part_no` 补入。**与既有 `enrichCustomerCode` / `enrichPriceBaseDate` 逐行同构**（同款「读 namedParams 而非 RuntimeContext」模式）。在 `:349/:350` 与 `:424/:425` 两处调用点并列调用。<br>⚠️ **不要加进程级缓存** —— `customer_part_no` 可被用户改，不同于 `customer.code` 与 `created_at` 那两个不可变量 |
| **B-2** | AC-1, AC-2, AC-3, AC-4, AC-6 | `SemanticCompiler.java`：CUSTOMER_PART 参与 JOIN 时，在 **`LEFT JOIN … ON`** 子句追加 `AND <别名>.customer_product_no = :customerProductNo`。<br>🚨 **绝不许写进 `WHERE`** —— 实测 112/3949 明细行 `customer_part_no` 为空，写 `WHERE` 会让这些卡片整页签 0 行且不报错（`SqlViewExecutor:111-122` 记载的 `repair-260908` B-2 静默失败形态）。挂 `ON` 上时谓词恒 UNKNOWN ⇒ 左表行仍在、客编列为空，这正是 AC-2 要的行为 |
| **B-3** | AC-7 | `BuilderService.bindLiterals()`：`/preview` 走字面量替换、不经 enrich ⇒ 补绑 `:customerProductNo`。**预览无 lineItem 上下文**，缺省语义须使 AC-7 成立（返回非空行、不报未绑定参数） |
| **B-4** | AC-7 | 另三条**不经 enrich** 的旁路逐条补绑（出处：`SqlViewExecutor:118-121` 的 javadoc 已点名）：`QuoteViewValidationService` · `CostingTreeSqlValidator` · `SqlViewValidator.bindWithNullPlaceholders`（保存期 dry-run） |
| **B-5** | AC-1, AC-6 | 走 `ComponentSqlViewService.snapshotForComponents()` 重生成组件 `221dc766-8ab6-4d95-82c0-08cc03e6267d` 的视图。<br>🚨 **不是** `POST /config-center/refresh-all-snapshots` —— 那个方法碰的是 `template_component_snapshot` 与 `template.components_snapshot`，**不碰 `sql_views_snapshot`**（`TemplateService.forceRealignSnapshots:1096`，`INDEX.md` 已记载此坑致过一次返工），且它自己写的 `updated_at = now()` 让「更新时间变了」对「有没有写对表」**恒为真**，是假信号 |

---

## 第二组 · 行身份 A 层（确定性排序）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-6** | AC-9, AC-10 | `SemanticCompiler.java`：生成的 `ORDER BY` 末尾追加 tie-breaker —— 锚点表及参与 JOIN 各表的主键 `id`（两张表均已实查确认 `PRIMARY KEY (id)`）。消除堆物理序漂移（`问题说明.md §4.2` 有实证：返回序 `14527,14451,21319,…` ≠ id 序） |

---

## 第三组 · 行身份 C 层（稳定行 id）

> 🚨 这一组是本次风险最高的部分。`问题说明.md §5.3` 的**后端 12 个主文件**全部在这一组的影响面内。

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-7** | AC-8, AC-9, AC-10 | 视图额外产出系统列 `__row_uid`（锚点表 `id` + 各 JOIN 对端表 `id` 拼接）；展开层把它**提升为顶层系统列**，与既有 `__nodeId` **同槽位同机制**（参照 `QuotationTreeService:454-456` 与 `QuotationStep2.tsx:2196-2199` 的 `__sys` 注入先例）。<br>⚠️ 手工新增行（`_origin:'manual'`）无源表主键 ⇒ `__row_uid` 为空，**保持现有 `row_index` 口径不变**（AC-13） |
| **B-8** | AC-8~AC-13 | 行身份消费点逐个改造，**12 个后端主文件按 `§5.3` 清单逐项勾**：<br>`DeletedRowKeys.java`（`rowFingerprint` / `keepMask` / `isDeleted` 增加 `__row_uid` 维度，**与既有 `nodeId` 维度同款叠加**，见 `DeletedRowKeysTest.keepMaskWithNodeId_*` 三个现成用例的模式）· `FormulaCalculator`（`buildRawRowKeys` / `uniquifyRowKeys` / `computeRowKey`）· `RowKeyUniquenessService` · `CardSnapshotService` · `RowDataMaterializer` · `QuotationTreeService` · `QuoteBackfillCollector` / `QuoteBackfillPreviewService` · `DsRecordProjector` / `DsMainTableReader` / `DsBackfillCollector` · `ConfigureSnapshotService` |
| **B-9** | AC-8 | 组件 `221dc766` 的 `row_key_fields` 由 `["销售料号"]` 改为 `["销售料号","客户产品编号"]`（B 层）。<br>⚠️ 这是**配置数据**改动，走组件管理的正常写入路径或迁移，不要手工 UPDATE 绕过校验 |

---

## 存量兼容（贯穿 B-7/B-8，不单列编号但必须做）

| 项 | 口径 |
|---|---|
| `buildLegacyRowKeySets` 现有**三档**回退 | **原样保留，一行不动**。`__row_uid` 新键作为**第四档、优先匹配** |
| 存量墓碑（无 `__row_uid`） | 必须仍能命中原行 —— 这是 **AC-11** 的判据 |
| 树页签（`__nodeId` 口径） | 行为不变 —— **AC-12**。`EffKeyNodeIdAlignmentTest` 的四条断言必须保持绿 |

---

## 🚫 后端明确不做

| 项 | 理由 |
|---|---|
| 改写存量单的冻结快照 | 用户 2026-09-10 裁决不做（`问题说明.md ⑥` 末节） |
| 修 `builder_a71947b68d50` / `builder_7277969cc41c` | 用户裁决本期只修 `221dc766`；结案时登 BACKLOG |
| 给 `ds_quote_customer_part` 加唯一约束 / 清数据 / 改导入 | 违反 `E-4`（一对多在主数据层合法） |
| 删那 3 条 MANUAL 测试造数 | `DELETE` 属 `CLAUDE.md §3.2` 红线；且它们是 AC-1 的回归夹具。结案时进待回收清单呈报等批 |

---

## 自检要求（`CLAUDE.md §6.1`：没有「已自检」声明的「完成」= 未完成）

- `./mvnw test` 在 **worktree 的 `cpq-backend/`** 里跑（不是主仓 —— 主仓跑会测错树报假绿）
- ⚠️ `mvnw test` 走 `test` profile → `cpq_db_test`（`application-test.properties:32`，`task-260909` 批次 0 改的），**不再直写共享开发库**
- ⚠️ 全量 `mvnw test` **永远不可能全绿**：`mat_*` 表在本库从未创建，25 个测试夹具、235 处红是既有状态，**不是本次引入，也不得当作验收门槛**。只对**本次相关**的测试类做绿判
- 迁移（如有）：`success=t` 实查
