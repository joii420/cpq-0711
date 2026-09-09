# V6 老表引用面清单 —— 后端与 DB 侧（B-1~B-4 / D-1~D-2）

- **任务**：`task-260909-V6老表引用面审计`
- **产出人**：backend-engineer（B-1 B-2 B-3 B-4 D-1 D-2）
- **实测日期**：2026-09-09
- **代码基线**：worktree `.claude/worktrees/task-260909-v6-audit`，分支 `feat/task-260909-v6-legacy-audit`
- **DB**：`10.177.152.12:5432/cpq_db_0724`（共享 dev 库，**全程只读，零 DML/DDL**）
- **服务的 AC**：AC-1 AC-2 AC-3 AC-4（生产侧）AC-5 AC-6 AC-7 AC-8 AC-9 AC-13

> 📌 **行号基线声明（必读）**
> 本清单全部 `文件:行` 基于 worktree `feat/task-260909-v6-legacy-audit` @ **`25b106e2`**（= 与 master 的分支点，
> `git merge-base master HEAD`；HEAD 提交 `docs(task-260909-审计): 闸门 A 放行 —— AC-7 补强传递闭包 + BACKLOG 登记`）。
>
> ⚠️ **master 上同名文件的行号可能不同，且差值会随其它会话推进变大。**
> 实测（2026-09-09）：`cpq-backend/src/main/java/com/cpq/configure/service/ConfigureProductService.java`
> **worktree 2166 行 / master 2186 行，差 20 行**；本清单的 `W41 :1831`
> （`parentHfPartNo = quoteAllocator.mintAndRegister(salesCtx.customerNo, salesCtx.yyMm);`）
> 在 master 上是 **`:1851`** —— 两行内容逐字节相同，**不是谁算错，是两棵树不是同一份代码**。
>
> ⇒ **退役任务照本清单定位时，先 `git log` 确认自己所在的树**；若已在 master，用**符号名 / 语句内容**定位，
> 不要直接跳行号。
>
> 📌 **`文件:行` 记法**：形如 `basicdata/v6/pricing/P01:71` 的缩写（路径 + 类名缩写 + 行号）与
> `com/cpq/basicdata/v6/pricing/P01ElementPricingPriceHandler.java:71` 等价 ——
> 路径前缀 `cpq-backend/src/main/java/com/cpq/` 全清单统一省略。
- **不在本文件**：前端引用面（F-1/F-2）、测试代码逐条明细（S-1）、写共享库的测试识别（S-2，本文件 §9 只给指针）

---

## §0 量具口径（先定义，否则后面所有数字不可复核）

### 0.1 检索工具

全程 `/usr/bin/grep -a`。本机 `grep` 是一个 shell function，实为 `ugrep 7.8.4` 带 `-I`（跳过二进制），
可能把中文注释密集的大源文件静默判为二进制返空。

```
$ type grep
grep is a function
grep () { ... exec -a ugrep "$_cc_bin" -G --ignore-files --hidden -I --exclude-dir=.git ... }
$ grep --version | head -1
ugrep 7.8.4 x86_64-pc-linux-gnu +sse2; -P:pcre2jit; ...
```

**对照实验（同一 pattern，裸 `grep` vs `/usr/bin/grep -a`，范围 `cpq-backend/src/main/java`）**：

| 表 | 裸 `grep` 文件数 | `/usr/bin/grep -a` 文件数 | 差 |
|---|---|---|---|
| `material_master` | 59 | 59 | 0 |
| `material_bom_item` | 37 | 37 | 0 |
| `element_bom_item` | 24 | 24 | 0 |
| `unit_price` | 59 | 59 | 0 |
| `material_customer_map` | 26 | 26 | 0 |

归一化路径前缀（`ugrep` 不输出 `./`）后 `diff` 为空 ⇒ **本次无差异**。
⚠️ 这不证明 ugrep 安全，只证明**本次这批文件**没被误判。口径仍固定为 `/usr/bin/grep -a`。

### 0.2 扫描范围与「基线数字」的出处

`任务.md §①` 的 main / test 命中数是 **`cpq-backend/src/main/java` 与 `cpq-backend/src/test/java` 的裸 `\b<表名>\b` 文件数**。已逐表复核，**10 张全部逐字吻合**：

```
material_master 59/58 · material_bom_item 37/50 · element_bom_item 24/35 · material_bom 15/23
element_bom 13/12 · unit_price 59/60 · capacity 22/17 · plating_scheme 17/9
annual_discount 12/9 · material_customer_map 26/32     ← main/test，与 §① 完全一致
```

⚠️ **本清单把 `main/resources`（Flyway 迁移脚本）单列**，不混进引用面：迁移脚本是**历史 DDL**，
不是运行期业务引用，退役时的处理方式完全不同（迁移文件属 §3.2 红线「契约销毁」，**永不改名不删除**）。
`main/resources` 的裸命中数为：`material_master` 48 / `material_bom_item` 53 / `element_bom_item` 45 /
`material_bom` 13 / `element_bom` 10 / `unit_price` 80 / `capacity` 17 / `plating_scheme` 13 /
`annual_discount` 4 / `material_customer_map` 12。

### 0.3 「引用点」的定义

一个**引用点 = 一处语句 / 一处调用 / 一处声明**，以其**首行**的 `文件:行` 标识。
同一条跨多行拼接的 native SQL 只算一个点（否则字符串续行会把数字灌水到不可读）。

### 0.4 七个引用通道（🚨 只 grep SQL 字符串会漏掉 5 个）

| # | 通道 | 形态 | 静态 grep 能否发现 |
|---|---|---|---|
| C1 | native SQL 字面表名 | `"FROM material_master ..."` | ✅ 能 |
| C2 | JPA 实体映射 | `@Table(name="material_master")` + Panache 静态方法 | ⚠️ 需要先知道实体类名 |
| C3 | **写入器动态表名** | `writer.writeVersionedGroups("unit_price", ...)` | ⚠️ 只能靠 `"<表名>"` 引号字面量 |
| C4 | **表名清单 + 循环拼 SQL** | `for (String t : B8_PENDING_TABLES) em.createNativeQuery("DELETE FROM " + t + ...)` | 🚫 **完全抓不到** |
| C5 | **声明式 registry 的 JOIN 源** | `nameCol(..., "material_master", "production_no", "material_name")` → 运行期 `LEFT JOIN` | 🚫 **完全抓不到** |
| C6 | **masterType → 表名映射** | `DsMasterTables.DEFS.put("material", new Def("material_master", ...))` → `FROM ` + 变量 | 🚫 **完全抓不到** |
| **C7** | 🚨 **写入器方法调用（表名不在调用点）** | `writer.upsert(p)` / `masterRepo.upsertByMaterialNo(...)` / `writer.writeVersionedGroup(new VersionedGroupSpec(g.table, …))` | 🚫 **完全抓不到**（= T-5d，见 §0.6；**必须从写入器方法反向枚举调用点**） |

⇒ 本清单的检索 pattern 是 **`(FROM|JOIN|INTO|UPDATE|TABLE)\s+<表名>\b`（忽略大小写）∪ `"<表名>"`（精确引号字面量）**，
再对 C4/C5/C6 三个通道**逐个人工追踪**（见 §3 写点汇总、§6 配置层），
并对 **C7 从写入器方法反向枚举调用点**（见 §0.6 T-5d）。

### 0.5 T-1 同名列消歧（AC-3）

`pg_attribute` 全库扫描（`relkind IN ('r','v','m','p')`，`public` schema）：

```sql
SELECT a.attname, c.relname, c.relkind FROM pg_attribute a
JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND a.attnum>0 AND NOT a.attisdropped AND c.relkind IN ('r','v','m','p')
  AND a.attname IN (<10 张表名>);
-- 唯一输出：
unit_price | production_energy | r
```

⇒ **10 张表里只有 `unit_price` 存在同名列**（`production_energy.unit_price`）。
其余 **9 张经 `pg_attribute` 全库扫描，无同名列**（逐表在各自小节复述一次，见 §2）。


### 0.6 🚨 T-5 表名以字符串常量拼进 SQL —— 正则「表名位」检索**永远抓不到**（主线 2026-09-09 追加）

**与 T-1 的危险方向相反，因此更致命**：
T-1（`unit_price` 同名列）让数字**虚高** —— 虚高长得像坏消息，有人会去核。
T-5 让数字**归零** —— 而「0 引用」正是退役决策想看到的答案，**它长得像好消息，没人会质疑**。

#### 实证（`cpq-backend/src/main/java`，`/usr/bin/grep -a`）

| 表 | 「表名位」命中<br>`(FROM\|JOIN\|INTO\|UPDATE)\s+<表>` 文件数 | 裸 `\b<表>\b` 文件数 | 真实情况 |
|---|---|---|---|
| `annual_discount` | **0** | 12 | 被 **5 个**动态 SQL 消费点读写（T5-1/5/6/7/8） |
| `element_bom` | **0** | 13 | 被 **8 个**动态 SQL 消费点读写（T5-1~T5-8 全部） |
| `plating_scheme` | **0** | 17 | 被 **8 个**动态 SQL 消费点读写（T5-1~T5-8 全部） |
| `capacity` | 1 | 22 | 另有 **8 个**动态 SQL 消费点（T5-1~T5-8 全部） |
| `material_bom` | 2 | 15 | 另有 **8 个**动态 SQL 消费点（T5-1~T5-8 全部） |
| `material_customer_map` | 11 | 26 | 另有 **6 个**（T5-1~T5-6；不在 T5-7/T5-8 两份白名单） |
| `material_master` | 16 | 59 | 另有 **2 个**（T5-3 读 / T5-6 写）+ C5/C6 两个非 SQL 通道 |

> 📌 上表右列是**我自己逐表核出的**（按各常量清单成员 × 各消费点交叉），**不是照抄任何转述**。逐表明细见本节末「T-5 对每张表的影响标注」。

源码里**一次都没有出现** `DELETE FROM annual_discount` 这样的字面量，但这张表 13 行里的 12 行 pending
确确实实会被 `QuotationService.cleanupPendingV6Data` 删掉。

#### T-5 家族全量（**8 个消费点 × 6 份常量清单**，逐个实测确认，未照抄任何转述）

**① 常量清单（表名字面量所在处）**

| 清单 | 位置 | 成员数 | 成员 |
|---|---|---|---|
| `QuotationService.B8_PENDING_TABLES` | `:2322-2324` | 9 | `unit_price` `material_bom` `material_bom_item` `element_bom` `element_bom_item` `capacity` `plating_scheme` `annual_discount` `material_customer_map` |
| `QuoteBackfillService.PENDING_TABLES` | `:33-35` | **8** | 上述 9 项**减 `annual_discount`** |
| `PendingHygieneService.PENDING_TABLES` | `:56-58` | **8** | 同上（另有 `MATERIAL_MASTER` 常量 `:61` 单独处理，`allManagedTables()` `:145` 合成 9 项） |
| `QuoteImportService.PENDING_TABLES` | `:300-302` | 9 | 同 `B8`（注释明确「`material_master` 故意不在这张清单里」——删除须走引用守卫） |
| `V6QuotationCommitService.PENDING_TABLES` | `:152-154` | **10** | 上述 9 项 **+ `material_master`**（注释：「过户是同列名 UPDATE 可以并列 `material_master`，删除不行」） |
| `QuotePendingRewriter.WHITELIST_TABLES` | `:79-84` | 8 + 2 | 8 张老表 + `v_compat_material_bom_item` / `v_compat_element_bom_item` |
| `VersionedV6Writer.ALLOWED_TABLES` | `:58-63` | 8（本批） | `unit_price` `capacity` `plating_scheme` `element_bom` `element_bom_item` `material_bom` `material_bom_item` `annual_discount`（另含 5 张不在本批的表） |

**② 消费点（拼接执行处）**

| # | 位置 | SQL 形态 | 动作 | 表清单 | 张数 |
|---|---|---|---|---|---|
| **T5-1** | `QuotationService.java:2328` | `"DELETE FROM " + table + " WHERE pending_quotation_id = :qid"` | **W** | `B8_PENDING_TABLES` | 9 |
| **T5-2** | `QuoteBackfillService.java:172` | `"DELETE FROM " + table + " WHERE pending_quotation_id = :qid"` | **W** | `PENDING_TABLES` | 8 |
| **T5-3** | `PendingHygieneService.java:153` | `"SELECT count(*) FROM " + table + " x WHERE …"` | R | `allManagedTables()` | 9 |
| **T5-4** | `PendingHygieneService.java:162` | `"DELETE FROM " + table + " x WHERE …"` | **W** | `PENDING_TABLES` | 8 |
| **T5-5** | `QuoteImportService.java:282` | `"DELETE FROM " + table + " WHERE pending_quotation_id = :pq"` | **W** | `PENDING_TABLES` | 9 |
| **T5-6** | 🚨 `V6QuotationCommitService.java:139` | `"UPDATE " + table + " SET pending_quotation_id = :qid WHERE pending_quotation_id = :rid"` | **W** | `PENDING_TABLES` | **10（含 `material_master`）** |
| **T5-7** | `QuotePendingRewriter.java:370 / :372` | `"(SELECT … FROM " + table + " t WHERE …"` / `"… NOT EXISTS (SELECT 1 FROM " + table + " p …"` | R | `WHITELIST_TABLES` | 8 + 2 视图 |
| **T5-8** | `VersionedV6Writer.java` **11 处**：`:415` `:439` `:853` `:878` `:972` `:985`（SELECT）· `:507`（`UPDATE … SET is_current = FALSE`）· `:516`（DELETE）· `:1058` `:1103` `:1133`（INSERT） | 多种 | **RW** | `ALLOWED_TABLES` | 8 |

> 🚨 **T5-6 是本次追查新增的一个写点**：`repointPendingOwnership()` 把 **10 张表**（含 `material_master`）
> 的 `pending_quotation_id` 从 `importRecordId` 过户到 `quotationId`。
> 入口链路：`QuotationImportResource.java:227 commit = commitService.createQuotation(req, userId)`
> （已实测核实；`:201` 那处是 Javadoc 不是调用）→ `V6QuotationCommitService.createQuotation():37` → `:139`。
> 我在第一版清单里把它记成了「pending 表清单成员」，**没有单独立为写点** —— 已补入 §3.2（W32）。
>
> ⚠️ **注意三份清单故意不等长**（9 / 8 / 10），源码注释逐条说明了原因：
> 删除须走引用守卫 ⇒ `material_master` 不入删除清单；过户是同列名 UPDATE ⇒ 可并入。
> **退役时若「顺手对齐成一样长」会引入真 bug**（源码注释原话：「两清单不该"顺手对齐成一样长"」）。


#### 🚨 T-5d · 表名只出现在「写入记账 / 日志 / 指标」调用里，**真正的写入调用点完全不含表名**（主线回流补录，2026-09-09）

**来源**：测试代理 AC-11 反向抽样抓到 4 个我清单里 0 命中的真写点，主线实测复核属实。
**我已逐个复核行号并顺该路径重扫全部 10 张表**（下方数字是我自己核的，未照抄）。

##### 证据（4 个文件同一形态，行号为我实测）

| 文件 | 写入调用（**不含表名**） | 记账调用（**表名在这里**） |
|---|---|---|
| `basicdata/v6/pricing/P16IncomingOtherRatioFeeHandler.java` | `:52 writer.upsert(p);` | `:54 result.recordWrite("unit_price", 1);` |
| `basicdata/v6/pricing/P17IncomingOtherFixedFeeHandler.java` | `:54 writer.upsert(p);` | `:56 result.recordWrite("unit_price", 1);` |
| `basicdata/v6/pricing/P19FinishedOtherRatioFeeHandler.java` | `:51 writer.upsert(p);` | `:53 result.recordWrite("unit_price", 1);` |
| `basicdata/v6/pricing/P20FinishedOtherFixedFeeHandler.java` | `:53 writer.upsert(p);` | `:55 result.recordWrite("unit_price", 1);` |

四者均 `@Inject UnitPriceWriter writer;`（各文件 `:19`），并在 `:45` 用
`UnitPriceWriter.newRow("PRICING", PricingPriceType.…, costType, …)` 造行 —— **`newRow` 也不含表名**。
实际 INSERT 在 `UnitPriceWriter.java:24`（W29）。

##### 为什么 T-5a/b/c 的检索路径抓不到它们

前三种形态里，表名**作为参数出现在写入调用点本身**（`writer.writeVersionedGroups("unit_price", …)`），
所以「扫字面量 → 追消费点」能找回来。

T-5d 里 **字面量与写入是两条独立语句**：
🚫 **顺字面量找，会追到 `SheetImportResult.recordWrite`（一个记账器），而不是写入器 —— 方向是错的。**

⇒ **必须补第四条检索路径：从写入器方法（`upsert` / `persist` / `writeVersionedGroup*` / `batchUpdate` / `flip*` / `delete*`）反向枚举调用点**，而不是只从表名字面量正向找。

##### 我按 T-5d 路径重扫 10 张表的结果（**写入调用点不含表名**的全部站点）

| 写入器方法 | 调用点 | 写的表 | 站点数 |
|---|---|---|---|
| `UnitPriceWriter.upsert(UnitPrice)` | `P16:52` `P17:54` `P19:51` `P20:53` | `unit_price` | **4** ← 本次补录 |
| `MaterialMasterRepository.upsertByMaterialNo(...)` | `P05CustomerMapHandler:54` `P06MaterialBomHandler:188` `P24UnitWeightHandler:44` `QuoteBackfillService:80` | `material_master` | 4 |
| `MaterialMasterRepository.upsertBatchNameType(...)` | `Q02:135` `Q04:88` `Q06:131` `Q07:121` `Q09:148` `Q13:119` `Q17:131` `MaterialBomMergeHandler:191` `P06:182` | `material_master` | 9 |
| `MaterialMasterRepository.upsertBatchWithWeight(...)` | `Q18UnitWeightHandler:52` | `material_master` | 1 |
| `MaterialMasterRepository.flipPending(...)` | `QuoteBackfillService:77` | `material_master` | 1 |
| `MaterialMasterRepository.deletePendingWithGuard(...)` | `QuotationService:2332` · **`QuoteImportService:285`** ← 本次补录 | `material_master` | 2 |
| `MaterialMasterRepository.deleteOrphanPendingWithGuard()` | `PendingHygieneService:123` | `material_master` | 1 |
| `MaterialCustomerMapRepository.upsert(...)` | `P05CustomerMapHandler:66` | `material_customer_map` | 1 |
| `MaterialCustomerMapRepository.upsertQuoteBatch(...)` | `Q02CustomerMapHandler:193` | `material_customer_map` | 1 |
| `MaterialCustomerMapRepository.deleteQuoteMappingsByCustomerNo(...)` | `Q02CustomerMapHandler:67` | `material_customer_map` | 1 |
| `MaterialCustomerMapRepository.deleteQuotePendingMappingsByCustomerNo(...)` | `Q02CustomerMapHandler:66` | `material_customer_map` | 1 |
| `QuoteMaterialNoAllocator.mintAndRegister(...)` | `MaterialNoResolver:90` · **`ConfigureProductService:428`** · **`ConfigureProductService:1831`** ← 后两处本次补录 | `material_customer_map` | 3 |
| `QuoteMaterialNoAllocator.ensureRegistered(...)` | `MaterialNoResolver:71` | `material_customer_map` | 1 |
| `ElementRecoveryDiscountRepository.batchUpdate(...)` | `Q05ElementRecoveryHandler:82` | `element_bom_item` | 1 |
| 🚨 **`VersionedV6Writer.writeVersionedMasterDetail(spec.master.masterTable, …, g.table, …)`** | **`QuoteBackfillService:97`** ← 本次补录 | 表名来自 `QuoteTableAxis.Spec` 变量 ⇒ **8 张受管表** | 1 |
| 🚨 **`VersionedV6Writer.writeVersionedGroup(new VersionedGroupSpec(g.table, …))`** | **`QuoteBackfillService:101`** ← 本次补录 | `g.table` 变量 ⇒ **8 张受管表** | 1 |

> 🚨 **`QuoteBackfillService:97` / `:101` 是最严重的两处**：它们是**核价回填真正改数据的主路径**
> （`executeRebuild()`），表名 100% 来自变量 `g.table` / `spec.master.masterTable`，
> **调用点、方法签名、整个文件的该行附近都没有任何表名字面量** —— T-5a/b/c 三条路径全部失效。
> 我第一版清单里只记了同文件 `:172` 的 pending 清理 DELETE，**漏了这条回填写入主路径**。

##### T-5d 的边界（我核过但**不属于**本批 10 张表的，列出以免下一个人重查）

| 站点 | 写的表 | 为何不算 |
|---|---|---|
| `dataset/importer/DatasetImportService.java:243 plainWriter.upsert(spec, …)` | `ds_*` 新表 | `PlainTableWriter` + `SheetDef`，新体系 |
| `seltemplate/resource/SelTemplateResource.java:48 templateService.upsert(req)` | 选配模板表 | 不相关 |
| `P03ExchangeRateHandler:94` | `exchange_rate_v6` | 不在本批 10 张 |
| `P11AuxiliaryEnergyHandler:65` | `auxiliary_energy` | 不在本批 10 张 |
| `P09EquipmentDepreciationHandler:79` / `P10ProductionEnergyHandler:78` | `production_energy` | 不在本批 10 张（且是 T-1 同名列的来源表） |
| `P12ToolingCostHandler:143` | `tooling_cost` | 不在本批 10 张 |
| `P08CapacityHandler:133` | `labor_rate` | 不在本批 10 张 |

`QuoteMaterialNoAllocator` **不写 `material_master`**（实测 `grep -naE "materialMaster|masterRepo|upsert"` 该文件 0 命中相关写入）—— 它只写 `material_customer_map`。

##### 顺 T-5d 路径还捞出一个**读**点（非写点，但同样是三条路径都看不到的）

`modelconfig/service/ModelConfigService.java:304`：
```java
MaterialCustomerMap.<MaterialCustomerMap>find("materialNo in ?1", salesPartNos).list()
```
**Panache 静态查询，整行无表名字面量**，靠「实体类符号反查」才找到。已补入 §2.10。


#### T-5 对每张表的影响标注（AC-13 口径）

| 表 | 表名位命中（文件数） | 是否受 T-5 影响 | 复核结论 |
|---|---|---|---|
| `material_master` | 16 | ✅ T-5a/b/c（T5-3 T5-6）+ **T-5d ×18 站点** + C5/C6 | **严重低估**（C5 `nameCol` 58 行 + C6 `DsMasterTables` + 18 个无表名写入调用点，全不可见） |
| `material_bom_item` | 9 | ✅ T5-1~T5-8 全部 + **T-5d（W39）** | 低估 |
| `element_bom_item` | 4 | ✅ T5-1~T5-8 全部 + **T-5d（W39 + `batchUpdate` ×1）** | 低估 |
| `material_bom` | 2 | ✅ T5-1~T5-8 全部 + **T-5d（W39 主表侧）** | 低估 |
| `element_bom` | **0** | ✅ **T5-1~T5-8 全部 + T-5d（W39 主表侧）** | **0 不代表无引用** —— 8 个动态消费点 + 4 个 handler 调用点 + W39 变量驱动回填 |
| `unit_price` | 2 | ✅ T5-1~T5-8 全部 + **T-5d ×5（W35~W38 + W39）** | 低估；⚠️ **同一张表被 T-1 拉高、被 T-5 压低，两个方向同时失真**（见 §0.5 / §2.6） |
| `capacity` | 1 | ✅ T5-1~T5-8 全部 + **T-5d（W39）** | **1 不代表只有 1 处** —— 8 个动态消费点 + 4 个 handler 调用点 + W39 |
| `plating_scheme` | **0** | ✅ **T5-1~T5-8 全部 + T-5d（W39）** | **0 不代表无引用** —— 8 个动态消费点 + 4 个 handler 调用点 + W39 |
| `annual_discount` | **0** | ✅ **T5-1 / T5-5 / T5-6 / T5-7 / T5-8（5 个）+ T-5d（W39，`ANNUAL_DISCOUNT` 是 `QuoteTableAxis` 受管 Spec）** | **0 不代表无引用**；⚠️ 但**不在** T5-2 / T5-4 两个清理清单 —— 「13 行里 12 行 pending」的成因（§10 R2） |
| `material_customer_map` | 11 | ✅ T5-1~T5-6（6 个）+ **T-5d ×7 站点**（占号 `mintAndRegister` ×3 / `ensureRegistered` ×1 / upsert ×2 / delete ×2） | 低估；⚠️ **占号写入全部无表名** |

#### 反向证明：T-5 的正则确实能命中（AC-13）

对同一 pattern 换一张已知有字面量的表：
```
/usr/bin/grep -rnaEi "(FROM|JOIN|INTO|UPDATE|TABLE)[[:space:]]+material_master\b" main/java  → 20 行 / 16 文件
/usr/bin/grep -rnaEi "(FROM|JOIN|INTO|UPDATE|TABLE)[[:space:]]+material_customer_map\b" main/java → 23 行 / 11 文件
```
⇒ pattern 本身有效。`element_bom` / `plating_scheme` / `annual_discount` 的 0 是**真的没有字面量**，
**不是**「真的没有引用」。

#### 检索策略（本清单据此执行）

三条路径缺一不可，**只用第 1 条会把 3 张活表判成可退役**：

1. 表名位正则 `(FROM|JOIN|INTO|UPDATE|TABLE)\s+<表>\b`
2. JPA 实体 / Panache 反查（`@Table(name=…)` → 实体类名 → 静态方法调用）
3. 🚨 **字符串常量集合 + 消费点追踪**：`"<表名>"` 引号字面量 → 定位 `List.of` / `Set.of` / `static final String` →
   再 grep `"… " + <变量> +` / `String.format` 找拼接执行点


---

## §1 汇总表（AC-1）

| # | 表 | DB 行数（实测） | **表名位命中<br>（文件数）** | **受 T-5 影响** | 生产引用点 | 生产文件数 | 其中写点 | 最后写入时间 | pending 行 | 阻塞级别 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `material_master` | **48** | 16 | ✅ 低估（含 T-5d） | 110 | 35 | **13** | 2026-09-09 09:44:15 | 2 | 🔴 高（活读 + 活写 + 2 个 `v_ds_cost_*_all` 依赖） |
| 2 | `material_bom_item` | **85** | 9 | ✅ 低估（含 T-5d） | 54 | 22 | 8 | 2026-09-04 00:35:15 | 0 | 🔴 高（`v_compat_*` 一阶 + composite 二阶 + 配置层直连） |
| 3 | `element_bom_item` | **64** | 4 | ✅ 低估（含 T-5d） | 41 | 18 | 8 | 2026-09-04 00:35:15 | 0 | 🔴 高（`v_compat_*` 一阶 + composite 二阶） |
| 4 | `material_bom` | **429** | 2 | ✅ 低估（含 T-5d） | 25 | 13 | 5 | 2026-09-04 00:35:15 | 0 | 🟡 中（无视图依赖，仅写入器 + pending 清理） |
| 5 | `element_bom` | **667** | **0** | 🚨 **0 ≠ 无引用**（含 T-5d） | 19 | 11 | 5 | 2026-09-04 00:35:15 | 0 | 🟡 中（同上） |
| 6 | `unit_price` | **185** | 2 | ✅ 低估（含 T-5d；且受 T-1 反向虚高） | **76 + 4 个 T-5d 站点** | **34 + 4** | **26** | 2026-09-09 09:44:06 | 0 | 🔴 高（`v_composite_child_processes` 一阶依赖） |
| 7 | `capacity` | **22** | 1 | 🚨 **1 ≠ 只有 1 处**（含 T-5d） | 24 | 13 | 6 | 2026-09-09 09:44:06 | 0 | 🟡 中 |
| 8 | `plating_scheme` | **12** | **0** | 🚨 **0 ≠ 无引用**（含 T-5d） | 25 | 13 | 5 | 2026-08-13 06:53:25 | 0 | 🟢 低 |
| 9 | `annual_discount` | **13** | **0** | 🚨 **0 ≠ 无引用**（含 T-5d） | 12 | 8 | 4 | 2026-09-08 01:10:53 | **12** | 🟡 中（13 行里 12 行是 pending） |
| 10 | `material_customer_map` | **61** | 11 | ✅ 低估（含 T-5d） | 31 **+ 2 个 T-5d 站点** | 16 **+ 1** | **12** | 2026-09-09 09:44:15 | 0 | 🔴 高（占号唯一性机制的载体） |

> 🚨 **「表名位命中」这一列不可单独作为退役判据**（T-5，见 §0.6）：`element_bom` / `plating_scheme` /
> `annual_discount` 三张表该列为 **0**，实际各被 5~7 个动态 SQL 消费点读写。**「生产引用点」列已含 T-5 通道。**
>
> 行数复核 SQL（一次 `UNION ALL`，与 `任务.md §①` 逐字一致）：
> `material_master|48 material_bom_item|85 element_bom_item|64 material_bom|429 element_bom|667 unit_price|185 capacity|22 plating_scheme|12 annual_discount|13 material_customer_map|61`

🚨 **`material_master` / `unit_price` / `capacity` / `material_customer_map` 的 `max(updated_at)` 都是 2026-09-09 09:44** ——
与 `任务.md §①` 记录的「`mvnw test` 打共享 dev 库」时间窗吻合。**这四张表今天还在被写**（见 §9）。

### 测试代码引用（AC-4，明细归 S-1）

| 表 | 裸 `\b..\b` 文件数 | 作表名文件数 | 引号字面量文件数 | 并集文件数 |
|---|---|---|---|---|
| `material_master` | 58 | 46 | 7 | 51 |
| `material_bom_item` | 50 | 35 | 19 | 42 |
| `element_bom_item` | 35 | 16 | 8 | 22 |
| `material_bom` | 23 | 12 | 7 | 15 |
| `element_bom` | 12 | 6 | 4 | 9 |
| `unit_price` | 60 | 37 | 17 | 46 |
| `capacity` | 17 | 8 | 6 | 11 |
| `plating_scheme` | 9 | 3 | 4 | 6 |
| `annual_discount` | 9 | 4 | 3 | 7 |
| `material_customer_map` | 32 | 26 | 5 | 28 |

裸命中数与 `任务.md §①` test 列**逐字一致**；消歧后并集均**不高于**裸数（符合 AC-4「允许下降不允许上升」）。

---

## §2 逐表引用点（B-1 / AC-1 AC-2 AC-3）

> 六字段：`文件:行` · 读写 · 业务场景 · 活死 · 迁移目标 · 阻塞原因

### 2.1 `material_master`（48 行）

> **同名列**：⚠️ 本表**不适用**「无同名列」结论 —— 它本身没有同名列，`pg_attribute` 全库扫描 0 命中；
> 唯一存在同名列的是 `unit_price`（见 §2.6）。

#### 生产代码引用

| 文件:行 | R/W | 业务场景 | 活/死 | 迁移目标 | 阻塞原因 |
|---|---|---|---|---|---|
| `basicdata/v6/entity/MaterialMaster.java:12` | — | JPA 实体映射（`@Table`），Panache 基类 | 活 | 需新建 `DsQuoteMaterial` 实体或改 `@Table` | 实体名被 5 个文件 import |
| `basicdata/v6/repository/MaterialMasterRepository.java:34` | R | `maxNineLeadingMaterialNo()` — 9 开头料号发号上界 | **死**（`codegraph_callers` 0） | 无需迁 | 无 |
| `…MaterialMasterRepository.java:40` | R | `lockForMaterialNoGeneration()` 发号排他锁 | **死**（0 caller，含测试） | 无需迁 | 无 |
| `…MaterialMasterRepository.java:98 / 200 / 263` | W | `upsertByMaterialNo` / `upsertBatchNameType` / `upsertBatchWithWeight` — 导入 + 回填补 stub | 活（main 4 / 9 / 1 处调用） | `ds_quote_material`（`material_no,material_name,specification,dimension,old_material_no,unit_weight,production_no,material_type,customer_no`） | `pending_quotation_id` 无对应列 |
| `…MaterialMasterRepository.java:326` | W | `flipPending()` — 核价通过后 pending 料号转正 | 活（`QuoteBackfillService:77`） | **无对应**（`ds_quote_material` 无 pending 概念） | 🔴 pending 占号语义整体缺失 |
| `…MaterialMasterRepository.java:346` | W | `deletePendingWithGuard()` — 报价单删除时回收料号（带引用守卫） | 活（`QuotationService:2332` + `PendingHygieneService`） | **无对应** | 🔴 同上 |
| `…MaterialMasterRepository.java:377` | W | `deleteOrphanPendingWithGuard()` — 孤儿 pending 体检清理（BL-0092） | 活（`PendingHygieneService:123`） | **无对应** | 🔴 同上 |
| `…MaterialMasterRepository.java:414` | R | `listPending()` — 守卫拦下条数统计 | 活（main 3 处） | **无对应** | 🔴 同上 |
| `…MaterialMasterRepository.java:21` | R | `findByMaterialNo()` | **死**（0 main caller，2 test 文件） | 无需迁 | 无 |
| `basicdata/v6/service/MaterialMasterCrudService.java:39/51/59/70/74/83` | RW | 物料主档 CRUD（Panache `count/findById/persist/delete`）→ `MaterialMasterResource` | 活（REST 入口在列） | `ds_quote_material` 全列 | 老表独有 5 列（见下） |
| `basicdata/v6/service/PartTypeInferenceService.java:281` | R | `loadMasterFallback()` — 料号类型推断兜底 | 活（`:236` 调用） | `ds_quote_material.material_type` | 无 |
| `basicdata/v6/service/MaterialNoResolver.java`（import） | R | 导入期料号解析（`resolve()` main 19 处） | 活 | `ds_quote_material` | 无 |
| `basicdata/v6/pricing/P13ProductionConsumableHandler.java:70` | R | 核价「生产耗材」页签：`material_no → production_no` 桥 | 活 | `ds_quote_material.production_no` | 无 |
| `basicdata/v6/pricing/P05:63` `P06:183,190` `P24:47` | W | 核价导入 `recordWrite("material_master", …)` 写入计数 | 活 | 随各自 upsert 迁 | 无 |
| `basicdata/v6/quote/MaterialBomMergeHandler.java:150`、`Q02:212`、`Q04:61`、`Q06:96`、`Q07:91`、`Q09:116`、`Q13:84`、`Q17:105`、`Q18:40` | W | 报价导入 9 个 handler 的写入计数（实际写由 `MaterialMasterRepository.upsert*` 承担） | 活 | 同上 | 无 |
| `quotation/service/QuotationService.java:3535 / 3568` | R | `loadLineItems()` — 打开报价单读料号品名/规格/尺寸 | 活 | `ds_quote_material.{material_name,specification,dimension}` | 无 |
| `quotation/service/QuotationService.java:2332` | W | `cleanupPendingV6Data()` — 报价单删除回收料号 | 活 | **无对应** | 🔴 pending |
| `quotation/service/CustomerPartCandidateService.java:70`（`listCandidates`）/`:169`（`listCandidatesV6`） | R | 报价向导 Step1「客户料号候选」下拉 | 活（`QuotationResource:147`） | `ds_quote_material` + `ds_quote_customer_part` | 无 |
| `quotation/service/backfill/BackfillLabelResolver.java:209` | R | 核价回填差异摘要的料号中文名 | 活 | `ds_quote_material.material_name` | 无 |
| `quotation/service/backfill/QuoteBackfillCollector.java:830` | R | `collectNewMaterialStubs()` — ADD 行新料号判存 | 活 | `ds_quote_material.material_no` | 无 |
| `quotation/service/PendingHygieneService.java:61` | — | 常量 `MATERIAL_MASTER`（清理清单单列） | 活 | 随 pending 机制走 | 🔴 pending |
| `quotation/snapshot/SnapshotCollectorService.java:255` | R | `collectMasterDataSnapshot()` — 报价单提交冻结主数据快照 | 活（`:112`） | `ds_quote_material`（缺 `standard_unit`，见下） | 🟡 `standard_unit AS unit` 无对应列 |
| `configure/service/MaterialRecipeService.java:167/209/220/269/287/321/417/548/679` | RW | 材质配方绑定：列表计数 / 分页 / **绑定**(`:269` UPDATE) / **解绑**(`:287` UPDATE) / 搜索 / 已有件材质 / 建议 / 确认绑定 | 活（`MaterialRecipeResource` 入口） | **无对应**（`ds_quote_material` 无 `material_recipe_id`） | 🔴 材质配方外键缺失 |
| `configure/resource/ConfigureSearchResource.java:76` | R | 选配「已有件搜索」 | 活（REST 入口自身） | `ds_quote_material` | 无 |
| `configure/service/ConfigureProductService.java:277 / 1236` | — | 注释（`unit_weight from material_master` / 已移除实现的历史说明） | 死（纯注释） | — | 无 |
| `configure/dto/MaterialRecipeDTO.java:51` | — | 注释（`partCount` 来源说明） | 死（纯注释） | — | 无 |
| `priceadjust/service/PriceAdjustStrategyService.java:203` | R | 价格调整策略「物料列表」 `LEFT JOIN material_master` | 活（`PriceAdjustStrategyResource:66`） | `ds_quote_material` | 无 |
| `existingproduct/service/ExistingProductService.java:124` | R | 已有产品抽屉 `LEFT JOIN material_master` | 活 ⏳ **在途** | 对方 D-3 硬约束：**必须走 `v_compat_material_master`，禁止直连 `ds_quote_material`** | ⏳ 归 `task-260909-已有产品抽屉数据源收敛` B-1 |
| `dataset/support/DsMasterTables.java:24` | R | **C6 通道**：`GET lookup/{masterType}` 的 `material` → `material_master`；实际 SQL 在 `DatasetMaintenanceService.lookup():686`（`FROM ` + 变量表名） | 活 | `ds_quote_material.{material_no,material_name}` | 无 |
| `dataset/registry/CostBasicRegistry.java:84-86,99-101,114-116,128-130,154-156,166-168`（18 行） | R | **C5 通道**：核价「基础」数据集名称列，运行期 `LEFT JOIN material_master ON production_no` 带出品名/规格/尺寸 | 活（`DatasetMaintenanceService.buildNameJoins():551`） | `ds_quote_material.{material_name,specification,dimension}` + `production_no` 桥 | 无 |
| `dataset/registry/CostDetailRegistry.java:82-84,96-98,110-112,124-126,138-140,157-159,171-173,187-189,202-204,217-219,231-233,270-272,282-284`（39 行） | R | **C5 通道**：核价「详细」数据集名称列，同上 | 活 | 同上 | 无 |
| `dataset/registry/QuoteRegistry.java:219` | R | **C5 通道**：报价数据集「组成件名称」`LEFT JOIN material_master ON material_no` | 活 | `ds_quote_material.material_name` | 无 |
| `basicdata/v6/parser/SheetImportResult.java:14` | — | 注释（写入计数示例） | 死（纯注释） | — | 无 |
| `basicdata/v6/quote/Q02CustomerMapHandler.java:25`、`basicdata/v6/pricing/P22PlatingCostHandler.java:31` | — | 注释 | 死（纯注释） | — | 无 |
| `basicdata/v6/service/V6QuotationCommitService.java:154` | — | `PENDING_TABLES` 清单成员（10 表字面量，含 `material_master`） | 活 | 随 pending 机制走 | 🔴 pending |

🚨 **C5/C6 是本次最大的发现**：`material_master` 在**新体系**（dataset 维护端 / lookup 端点）里被
**运行期动态拼表名**读取 —— `nameCol(..., "material_master", ...)` 共 **58 行声明**，加上
`DsMasterTables` 的 `material` 映射。这两处**任何 `FROM material_master` 的 grep 都看不到**，
退役时最容易漏。

#### 列级迁移建议（AC-9）

**老表独有 5 列的实测非空行数**（`SELECT count(*) FILTER (WHERE <col> IS NOT NULL) FROM material_master`，共 48 行）：

| 列 | 非空行数 | 结论 |
|---|---|---|
| `standard_unit` | **0** | 无数据，可直接放弃 |
| `usage_property` | **0** | 无数据，可直接放弃 |
| `config_fingerprint` | **0** | 无数据，可直接放弃 |
| `material_recipe_id` | **1** | 🔴 有数据 + 有活代码（`MaterialRecipeService` 绑定/解绑 UPDATE）⇒ **`ds_quote_material` 无此列，必须先补列或改宿主** |
| `pending_quotation_id` | **2** | 🔴 有数据 + 6 处活写点 |

**结论（AC-9 要求逐字给出）**：
> `ds_quote_material` 的列是 `id, material_no, material_name, specification, dimension, old_material_no,
> unit_weight, production_no, source, created_at, created_by, updated_at, updated_by, material_type,
> category_code, customer_no` —— **无 pending 概念**（只有 `source`，⚠️ **实测更正：也没有 `version_no` 列**，
> `任务.md` AC-9 写的「仅 `version_no`/`source`」中 `version_no` 不成立，`ds_quote_material` 只有 `source`）
> ⇒ **`pending_quotation_id` 无对应列**。

其余可迁列一一对应：`material_no→material_no` · `material_name→material_name` · `specification→specification` ·
`dimension→dimension` · `old_material_no→old_material_no` · `material_type→material_type` ·
`unit_weight→unit_weight` · `production_no→production_no`。

---

### 2.2 `material_bom_item`（85 行）

> **经 `pg_attribute` 全库扫描，无同名列。**

| 文件:行 | R/W | 业务场景 | 活/死 | 迁移目标 | 阻塞原因 |
|---|---|---|---|---|---|
| `basicdata/v6/entity/MaterialBomItem.java:15` | — | JPA 实体映射 | 活 | `ds_quote_material_bom` | 无 |
| `basicdata/v6/repository/MaterialBomItemRepository.java:22` | R | `findByParent()` | **死**（`codegraph_callers` 0，main/test 均 0） | 无需迁 | 无 |
| `…MaterialBomItemRepository.java:37` | R | `queryItems()` — 物料 BOM 维护页分页 | 活（`MaterialBomQueryService:57` → `MaterialBomQueryResource`） | `ds_quote_material_bom` | 无 |
| `…MaterialBomItemRepository.java:63` | R | `findDistinctCustomerNos()` — 维护页客户下拉 | 活 | `ds_quote_material_bom.customer_no` | 无 |
| `…MaterialBomItemRepository.java:77` | R | `findDistinctMaterialNos()` — 维护页料号下拉 | 活 | `ds_quote_material_bom.material_no` | 无 |
| `basicdata/v6/repository/MaterialMasterRepository.java:348 / 380` | R | pending 料号删除守卫的 `NOT EXISTS` 引用检查 | 活 | 随 pending 机制走 | 🔴 pending |
| `basicdata/v6/pricing/P06MaterialBomHandler.java:137 / 164` | W | 核价导入「物料 BOM」页签写子表 | 活 | `ds_quote_material_bom` / `ds_cost_*_material_bom` | 无 |
| `basicdata/v6/quote/MaterialBomMergeHandler.java:221 / 250` | W | 报价导入「物料 BOM」合并写子表 | 活 | `ds_quote_material_bom` | 无 |
| `basicdata/v6/versioning/VersionedV6Writer.java:61 / 67` | W | **C3/C4 通道**：`ALLOWED_TABLES` 白名单 + `SYSTEM_TYPE_SCOPED` 集合 | 活 | 新体系写入器 | 无 |
| `builder/service/BuilderService.java:499` | R | `queryCustomerRootMaterialNos()` — 取数配置器 dry-run 的客户根料号 | 活（`:465`） | `ds_quote_material_bom.material_no` | 无 |
| `builder/compiler/SemanticCompiler.java:1505 / 1510` | R | `closureCte()` 内的递归 BOM 闭包 CTE | **死**（见 §4） | 无需迁 | 无 |
| `datasource/sqlview/QuotePendingRewriter.java:80` | R | `WHITELIST_TABLES` — 组件 SQL 的 pending 可见性改写白名单 | 活（11 个文件消费该类） | 新体系 `source_quotation_id` 语义 | 🔴 pending 可见性机制 |
| `datasource/sqlview/QuotePendingRewriter.java:110` | — | `COMPAT_VIEW_TO_TABLE`：`v_compat_material_bom_item → material_bom_item` 归一化（写路径回物理表） | 活 | 退役即失效 | 🔴 与 `v_compat_*` 强绑 |
| `datasource/sqlview/QuotePendingRewriter.java:119 / 461 / 464 / 526` | — | 注释 | 死（纯注释） | — | 无 |
| `quotation/service/QuotationService.java:699` | R | `saveDraft()` — 取工序号集合 | 活 | `ds_quote_material_bom.operation_no`（⚠️ 新表无该列，需确认） | 🟡 列缺失待确认 |
| `quotation/service/QuotationService.java:3320` | R | `processBatchStage1()` — `unnest` 批量按 partNo 匹配工序 | 活 | 同上 | 🟡 同上 |
| `quotation/service/QuotationService.java:3533` | R | `loadLineItems()` 的 `EXISTS` 子查询（判有无 BOM） | 活 | `ds_quote_material_bom` | 无 |
| `quotation/service/CostingVersionService.java:111` | R | `listVersionOptions()` — 核价单版本切换的候选版本 | 活 | `ds_quote_material_bom.version_no` | 无 |
| `quotation/service/backfill/QuoteTableAxis.java:99 / 130 / 139 / 144 / 158` | RW | 回填轴规格（`Spec MATERIAL_BOM_ITEM`）+ 表清单 + 轴取值分支 | 活 | 新体系轴 | 🔴 回填机制 |
| `quotation/service/backfill/BackfillLabelResolver.java:28 / 146` | R | 差异摘要标签「BOM 组成」+ 名称解析分支 | 活 | — | 无 |
| `quotation/service/backfill/QuoteBackfillCollector.java:499 / 566 / 803 / 821` | R | 主子表判定 / 子表名分支（4 处硬编码） | 活 | 新体系 | 🔴 回填机制 |
| `quotation/service/backfill/QuoteBackfillColumnMapper.java:119` | — | 注释（指向上面 4 处硬编码） | 死（纯注释） | — | 无 |
| `quotation/service/backfill/QuoteBackfillService.java:34` | W | `PENDING_TABLES` → `cleanupPending():172` 动态 `DELETE`（**C4**） | 活 | — | 🔴 pending |
| `quotation/service/PendingHygieneService.java:57` | W | `PENDING_TABLES` → `deleteOrphans():162` 动态 `DELETE`（**C4**） | 活 | — | 🔴 pending |
| `quotation/service/QuotationService.java:2323` | W | `B8_PENDING_TABLES` → `:2328` 动态 `DELETE`（**C4**） | 活 | — | 🔴 pending |
| `basicdata/v6/quote/QuoteImportService.java:301` | W | 报价导入的 pending 表清单 | 活 | — | 🔴 pending |
| `basicdata/v6/service/V6QuotationCommitService.java:153` | W | 提交期 pending 表清单 | 活 | — | 🔴 pending |
| `configure/service/ConfigureProductService.java:1242 / 1246 / 1249 / 1252` | W | `backfillV6MaterialsForCustomer()` — 选配「已有件换客户」时 `INSERT … SELECT` 复制 BOM | 活（`:382`） | `ds_quote_material_bom` | 无 |
| `configure/service/ConfigureProductService.java:1737` | R | `listOutsourcedParts()` — 外购件列表 | 活 | `ds_quote_material_bom` | 无 |
| `configure/service/ConfigureSnapshotService.java:1125` | R | `resolveCompositeChildren()` — 组合产品子件解析 | **死**（`codegraph_callers` 0） | 无需迁 | 无 |

**列级迁移**：`material_no→material_no` · `component_no→input_material_no` · `seq_no/item_seq→item_seq` ·
`composition_qty→component_qty` · `rough_weight→gross_weight` · `net_weight→net_weight` ·
`weight_unit→weight_unit` · `material_ratio→material_ratio` · `defect_rate→defect_rate` ·
`bom_version→version_no` · `customer_no→customer_no`。
**无对应列**：`operation_no` / `operation_seq` / `calc_type` / `characteristic` / `part_no` / `system_type` /
`is_current` / `pending_quotation_id` / `pending_supersedes` 以及 SAP 风格的 20 余个属性列
（`issue_unit` `fas_group` `plug_position` `ecn_no` …）—— `ds_quote_material_bom` 只有 22 列，老表 58 列。

---

### 2.3 `element_bom_item`（64 行）

> **经 `pg_attribute` 全库扫描，无同名列。**

| 文件:行 | R/W | 业务场景 | 活/死 | 迁移目标 | 阻塞原因 |
|---|---|---|---|---|---|
| `basicdata/v6/entity/ElementBomItem.java:15` | — | JPA 实体映射 | 活 | `ds_quote_element_bom` | 无 |
| `basicdata/v6/repository/ElementRecoveryDiscountRepository.java:39` | **W** | `updateOne()` — 单行回收折扣 UPDATE | **死**（`codegraph_callers` 0；仅 1 个测试文件用） | `ds_quote_element_bom.recovery_discount` | 无（可直接删） |
| `…ElementRecoveryDiscountRepository.java:91` | R | `countCurrentMatches()` — 回填前命中计数 | 活（main 1 处） | `ds_quote_element_bom` | 无 |
| `…ElementRecoveryDiscountRepository.java:140` | **W** | `batchUpdate()` — 报价导入 Q05「来料回收折扣」批量 UPDATE。⚠️ 唯一调用点 `Q05ElementRecoveryHandler.java:82 repo.batchUpdate(...)` **不含表名**（**T-5d**） | 活 | `ds_quote_element_bom.recovery_discount` | 无 |
| `…ElementRecoveryDiscountRepository.java:166` | R | `batchUpdate` 内 `NOT EXISTS` 去重子查询 | 活 | 同上 | 无 |
| `basicdata/v6/pricing/P07ElementBomHandler.java:92 / 114` | W | 核价导入「元素 BOM」写子表 | 活 | `ds_cost_*_element_bom` | 无 |
| `basicdata/v6/quote/Q04ElementBomHandler.java:106 / 128` | W | 报价导入「元素 BOM」写子表 | 活 | `ds_quote_element_bom` | 无 |
| `basicdata/v6/quote/Q05ElementRecoveryHandler.java:94` | W | Q05 写入计数 | 活 | 同上 | 无 |
| `basicdata/v6/versioning/VersionedV6Writer.java:60 / 67` | W | `ALLOWED_TABLES` + `SYSTEM_TYPE_SCOPED` | 活 | — | 无 |
| `datasource/sqlview/QuotePendingRewriter.java:81 / 111` | R | pending 白名单 + `v_compat_element_bom_item` 归一化 | 活 | — | 🔴 与 `v_compat_*` 强绑 |
| `quotation/snapshot/SnapshotCollectorService.java:274 / 276` | R | 提交冻结时收集元素构成快照（含 `MAX(characteristic)` 子查询） | 活（`:112`） | `ds_quote_element_bom` | 🟡 `characteristic` 无对应列 |
| `quotation/service/backfill/QuoteTableAxis.java:112 / 131 / 139 / 144 / 158` | RW | 回填轴 `Spec ELEMENT_BOM_ITEM` + 表清单 | 活 | — | 🔴 回填机制 |
| `quotation/service/backfill/BackfillLabelResolver.java:29 / 155` | R | 标签「材质元素构成」+ 名称解析 | 活 | — | 无 |
| `quotation/service/backfill/QuoteBackfillCollector.java:566 / 808` | R | 主子表 / 子表名分支 | 活 | — | 🔴 回填机制 |
| `quotation/service/backfill/QuoteBackfillService.java:34`、`PendingHygieneService.java:57`、`QuotationService.java:2323`、`QuoteImportService.java:301`、`V6QuotationCommitService.java:153` | W | 5 处 pending 表清单（→ **C4** 动态 DELETE） | 活 | — | 🔴 pending |
| `configure/service/MaterialRecipeService.java:466 / 474 / 549` | R | 已有件材质元素读取 + `MAX(characteristic)` 子查询 + 建议绑定 | 活 | `ds_quote_element_bom` | 🟡 `characteristic` |
| `configure/service/ConfigureProductService.java:1225 / 1227 / 1229 / 1230` | W | `backfillV6MaterialsForCustomer()` `INSERT … SELECT` 复制元素 BOM | 活（`:382`） | `ds_quote_element_bom` | 无 |

**列级迁移**：`material_no→material_no` · `material_part_no→material_part_no` · `seq_no→item_seq` ·
`component_no→element_code` · `content→content_pct` · `recovery_discount→recovery_discount` ·
`customer_no→customer_no`。
**无对应列**：`characteristic`（版本轴，新表用 `version_no`）· `hf_part_no` · `system_type` · `is_current` ·
`pending_quotation_id` · `pending_supersedes` · 及 30 余个 SAP 属性列。
`ds_quote_element_bom` 独有 `loss_rate/gross_usage/net_usage/recovery_qty` —— 老表没有，属新增维度。

---

### 2.4 `material_bom`（429 行）—— BOM 主表

> **经 `pg_attribute` 全库扫描，无同名列。**

| 文件:行 | R/W | 业务场景 | 活/死 | 迁移目标 | 阻塞原因 |
|---|---|---|---|---|---|
| `basicdata/v6/entity/MaterialBom.java:17` | — | JPA 实体映射 | 活 | **无对应表**（新体系不拆主子） | 🔴 模型差异 |
| `basicdata/v6/repository/MaterialBomRepository.java:13` | R | `findOne()` | **死**（`codegraph_callers` 0） | 无需迁 | 无 |
| `…MaterialBomRepository.java:22` | R | `findLatestCharacteristic()` | **死**（`codegraph_callers` 0） | 无需迁 | 无 |
| ⇒ **整个 `MaterialBomRepository` 是死类**（2 个方法都 0 caller） | | | **死** | | |
| `basicdata/v6/repository/MaterialMasterRepository.java:351 / 384` | R | pending 删除守卫 `NOT EXISTS` | 活 | — | 🔴 pending |
| `basicdata/v6/pricing/P06MaterialBomHandler.java:136 / 163` | W | 核价导入写 BOM 主表（`writeVersionedMasterDetails`） | 活 | 无对应（主表信息并入子表） | 🟡 |
| `basicdata/v6/quote/MaterialBomMergeHandler.java:220 / 249` | W | 报价导入写 BOM 主表 | 活 | 同上 | 🟡 |
| `basicdata/v6/versioning/VersionedV6Writer.java:61 / 67` | W | `ALLOWED_TABLES` + `SYSTEM_TYPE_SCOPED` | 活 | — | 无 |
| `datasource/sqlview/QuotePendingRewriter.java:80` | R | pending 白名单 | 活 | — | 🔴 pending |
| `quotation/service/backfill/QuoteTableAxis.java:110 / 139` | W | `MasterSpec("material_bom","bom_version",…)` 主表规格 | 活 | — | 🔴 回填机制 |
| `quotation/service/backfill/QuoteBackfillService.java:34`、`PendingHygieneService.java:57`、`QuotationService.java:2323`、`QuoteImportService.java:301`、`V6QuotationCommitService.java:153` | W | pending 表清单（→ **C4**） | 活 | — | 🔴 pending |
| `configure/service/ConfigureProductService.java:1260 / 1264 / 1267 / 1270` | W | `backfillV6MaterialsForCustomer()` `INSERT … SELECT` 复制 BOM 主表 | 活 | 无对应 | 🟡 |

**列级迁移**：新体系**取消了 BOM 主表**（`ds_quote_material_bom` 是扁平单表，版本走 `version_no` +
`ds_quote_material_bom_history` / `_record`）。主表的 `bom_type` / `characteristic` / `batch_qty` /
`production_unit` / `valid_from` / `valid_to` **全部无对应列** —— 退役前须确认这些字段是否还有业务消费方。

---

### 2.5 `element_bom`（667 行）—— 元素 BOM 主表

> **经 `pg_attribute` 全库扫描，无同名列。**
> 🚨 **`(FROM|JOIN|INTO|UPDATE)\s+element_bom\b` 在 `main/java` 命中 0 —— 这是 T-5 的典型形态（§0.6）。**
> **0 不代表无引用**：复核后本表被 **8 个动态 SQL 消费点全部**（T5-1~T5-8）读写。反向证明见 §7 Z1。
> 本表**唯一**的访问通道是 C3（写入器动态表名）+ C4（pending 清单循环）。

| 文件:行 | R/W | 业务场景 | 活/死 | 迁移目标 | 阻塞原因 |
|---|---|---|---|---|---|
| `basicdata/v6/entity/ElementBom.java:14` | — | JPA 实体映射 | 活（无 Panache 调用点，仅供 Hibernate 建模） | **无对应表** | 🔴 模型差异 |
| `basicdata/v6/pricing/P07ElementBomHandler.java:91 / 113` | W | 核价导入写元素 BOM 主表 | 活 | 无对应 | 🟡 |
| `basicdata/v6/quote/Q04ElementBomHandler.java:105 / 127` | W | 报价导入写元素 BOM 主表 | 活 | 无对应 | 🟡 |
| `basicdata/v6/versioning/VersionedV6Writer.java:60 / 67` | W | `ALLOWED_TABLES` + `SYSTEM_TYPE_SCOPED` | 活 | — | 无 |
| `datasource/sqlview/QuotePendingRewriter.java:81` | R | pending 白名单 | 活 | — | 🔴 pending |
| `quotation/service/backfill/QuoteTableAxis.java:122 / 139` | W | `MasterSpec("element_bom","characteristic",…)` | 活 | — | 🔴 回填机制 |
| `quotation/service/backfill/QuoteBackfillService.java:34`、`PendingHygieneService.java:57`、`QuotationService.java:2323`、`QuoteImportService.java:301`、`V6QuotationCommitService.java:153` | W | pending 表清单（→ **C4**） | 活 | — | 🔴 pending |

**列级迁移**：同 `material_bom` —— 新体系无元素 BOM 主表概念，全部字段无对应列。
🚨 **667 行是 10 张表里最多的**，但引用面最窄（19 点 / 11 文件）—— 典型的「数据多、代码少」，
退役风险在**数据归属**不在代码改动。

---

### 2.6 `unit_price`（185 行）—— 🚨 **T-1 消歧重点表**

#### 同名消歧（AC-3）

| 项 | 值 |
|---|---|
| 裸 `/usr/bin/grep -rlaE "\bunit_price\b" main/java` | **59 个文件**（`任务.md §①` 基线） |
| 严格作表名 `(FROM\|JOIN\|INTO\|UPDATE)\s+unit_price\b` | 2 个文件 |
| **本清单口径**（作表名 ∪ 写入器/清单引号字面量，剔除同名列/同名字段） | **34 个文件 / 76 个引用点** |

**34 < 59 ✅**（AC-3 第 ③ 条）

**② 显式排除项**：
1. **同名列**：`production_energy.unit_price`（`pg_attribute` 唯一命中）—— 声明处 `basicdata/v6/entity/ProductionEnergy.java:66` `@Column(name="unit_price")`。
2. **同名字段 / 同名 SQL 输出列**（7 个文件，共 11 行，已逐个剔除）：

| 排除的文件 | 行 | 实际含义 |
|---|---|---|
| `basicdata/v6/entity/ProductionEnergy.java` | 66 | `production_energy` 的**列** |
| `basicdata/v6/pricing/P09EquipmentDepreciationHandler.java` | 33, 63 | 折旧写入的 `CONTENT` **列名** |
| `basicdata/v6/pricing/P10ProductionEnergyHandler.java` | 33, 62 | 生产能耗写入的 `CONTENT` **列名** |
| `builder/compiler/FieldTreeBuilder.java` | 440 | `col.dbColumn` 判定，**输出列名** |
| `builder/service/BuilderService.java` | 914 | `col.sourceColumn` 判定，**输出列名** |
| `component/service/ElementBindingDerivation.java` | 80 | `findAliasedOutputColumn(…, "unit_price")`，**视图输出列名** |
| `template/service/TemplateFormulaService.java` | 495, 496, 519 | 公式调试的 **row map key** |

#### 生产代码引用（作表名）

| 文件:行 | R/W | 业务场景 | 活/死 | 迁移目标 | 阻塞原因 |
|---|---|---|---|---|---|
| `basicdata/v6/entity/UnitPrice.java:16` | — | JPA 实体映射 | 活 | 按 `cost_type` 分裂到 6 张 `ds_quote_*_fee` | 🔴 一表拆多表 |
| `basicdata/v6/repository/UnitPriceRepository.java:14` | R | `findOne()` | **死**（`codegraph_callers` 0）⇒ **整类死** | 无需迁 | 无 |
| `basicdata/v6/service/UnitPriceWriter.java:24` | **W** | `INSERT INTO unit_price (…)` — 单价写入 | 活 | 分裂目标同上 | 🔴 |
| 🚨 `basicdata/v6/pricing/P16:52` `P17:54` `P19:51` `P20:53` | **W** | `writer.upsert(p)` —— 核价「来料/成品 其它比例费·固定费」4 个 handler。**写入调用点不含表名**，字面量只在旁边 `recordWrite("unit_price",1)` 记账里（**T-5d**，W35~W38） | 活 | 按 `cost_type` 分裂目标同上 | 🔴 |
| 🚨 `quotation/service/backfill/QuoteBackfillService.java:97 / :101` | **W** | `executeRebuild()` —— 核价回填写入主路径，表名来自 `g.table` / `spec.master.masterTable` **变量**（**T-5d 最纯形态**，W39） | 活 | 新体系 `_history`/`_record` | 🔴 B-B 版本 flip 机制 |
| `basicdata/v6/pricing/P01:71` `P02:72` `P13:98` `P14:68` `P15:74` `P18:69` `P22:120` `P23:68`、`FinishedOtherMergeHandler:106`、`IncomingOtherMergeHandler:117` | W | 核价导入 10 个 handler 写单价（`writeVersionedGroups("unit_price", …)`，**C3**） | 活 | `ds_cost_basic_*` / `ds_cost_detail_*` | 🔴 |
| `basicdata/v6/quote/Q06:140,150` `Q07:129,139` `Q09:156,167` `Q10:98,108` `Q11:76,86` `Q13:127,137` `Q17:139,149` | W | 报价导入 7 个 handler 写单价（**C3**） | 活 | `ds_quote_self_process_fee` / `_incoming_*` / `_finished_other_fee` / `_plating_fee` / `_sub_component_fee` | 🔴 |
| `basicdata/v6/versioning/VersionedV6Writer.java:59` | W | `ALLOWED_TABLES`（注意：`unit_price` **不在** `SYSTEM_TYPE_SCOPED`） | 活 | — | 无 |
| `configure/service/ConfigureProductService.java:287` | R | `computeSimpleSignature()` — 选配指纹取工序序号 | 活（`:248`/`:417`） | `ds_quote_self_process_fee.{operation_no,item_seq}` | 无 |
| `configure/service/ConfigureProductService.java:1047 / 1055 / 1057` | R | `backfillProcessesForNewCustomer()` — 换客户时判存 + 复制工序 | 活（`:389`） | 同上 | 无 |
| `configure/service/ConfigureProductService.java:1079` | **W** | 同上方法内 `writeVersionedGroup("unit_price", …)` 回填自制加工费 | 活 | `ds_quote_self_process_fee` | 🔴 |
| `configure/service/ConfigureProductService.java:1334 / 1381` | **W** | `insertProcessUnitPriceV6()` — 选配下单写工序单价 | 活（`:1879`） | 同上 | 🔴 |
| `datasource/sqlview/QuotePendingRewriter.java:80` | R | pending 白名单 | 活 | — | 🔴 pending |
| `quotation/service/backfill/QuoteTableAxis.java:65 / 127 / 139 / 144 / 159` | RW | 回填轴 `Spec UNIT_PRICE` | 活 | — | 🔴 回填机制 |
| `quotation/service/backfill/BackfillLabelResolver.java:30 / 159` | R | 标签「单价」+ 名称解析 | 活 | — | 无 |
| `quotation/service/backfill/QuoteBackfillCollector.java:822` | R | 差异摘要取 `groupKeyAxis.code` | 活 | — | 无 |
| `quotation/service/backfill/QuoteBackfillService.java:34`、`PendingHygieneService.java:57`、`QuotationService.java:2323`、`QuoteImportService.java:301`、`V6QuotationCommitService.java:153` | W | pending 表清单（→ **C4**） | 活 | — | 🔴 pending |

**列级迁移**：`unit_price` 是**一表多态**（`price_type` × `cost_type` 决定语义），
新体系已按语义拆成 `ds_quote_self_process_fee` / `ds_quote_incoming_fixed_fee` / `ds_quote_incoming_other_fee` /
`ds_quote_finished_other_fee` / `ds_quote_plating_fee` / `ds_quote_sub_component_fee` / `ds_quote_assembly_fee`。
对应关系：`finished_material_no→material_no` · `operation_no→operation_no` · `seq_no/item_seq→item_seq` ·
`pricing_price→value` · `cost_ratio→ratio_pct` · `currency→currency` · `unit→pricing_unit` ·
`version_no→version_no` · `customer_no→customer_no`。
**无对应列**：`price_type` `cost_type`（拆表后由表名承载）· `plating_scheme_no` · `market_ref_price` ·
`supplier_no/name` · `source_url/name/fetch_rule` · `premium_fee` · `fetched_price/time` ·
`base_value` · `is_fluctuate_with_material` · `material_increase_ratio` · `material_fixed_increase` ·
`life_qty/unit` · `recovery_discount` · `is_current` · `pending_quotation_id` · `pending_supersedes`。
🚨 老表 52 列，7 张目标表各 ~19 列 —— **列级映射需逐 `cost_type` 单独确认，不能一刀切**。

---

### 2.7 `capacity`（22 行）

> **经 `pg_attribute` 全库扫描，无同名列。**

| 文件:行 | R/W | 业务场景 | 活/死 | 迁移目标 | 阻塞原因 |
|---|---|---|---|---|---|
| `basicdata/v6/entity/Capacity.java:14` | — | JPA 实体映射 | 活 | `ds_cost_detail_capacity` / `ds_quote_assembly_fee` | 🔴 一表拆二 |
| `basicdata/v6/pricing/P08CapacityHandler.java:104 / 107` | W | 核价导入「工时产能」（**C3**） | 活 | `ds_cost_detail_capacity` | 无 |
| `basicdata/v6/quote/Q14AssemblyProcessFeeHandler.java:103 / 105 / 113 / 115` | W | 报价导入「组装加工费」（**C3**） | 活 | `ds_quote_assembly_fee` | 无 |
| `basicdata/v6/versioning/VersionedV6Writer.java:59 / 68` | W | `ALLOWED_TABLES` + `SYSTEM_TYPE_SCOPED` | 活 | — | 无 |
| `configure/service/ConfigureProductService.java:299` | R | `computeSimpleSignature()` — 选配指纹取工序 | 活 | `ds_quote_assembly_fee.assembly_operation` | 无 |
| `configure/service/ConfigureProductService.java:1443` | W | `insertCompositeProcessCapacityV6()` — 组合工艺写产能 | 活 | `ds_quote_assembly_fee` | 无 |
| `datasource/sqlview/QuotePendingRewriter.java:81` | R | pending 白名单 | 活 | — | 🔴 pending |
| `quotation/service/backfill/QuoteTableAxis.java:76 / 128 / 140 / 144 / 158` | RW | 回填轴 `Spec CAPACITY` | 活 | — | 🔴 回填机制 |
| `quotation/service/backfill/BackfillLabelResolver.java:31 / 166` | R | 标签「工时产能」 | 活 | — | 无 |
| `quotation/service/backfill/QuoteBackfillService.java:35`、`PendingHygieneService.java:58`、`QuotationService.java:2324`、`QuoteImportService.java:302`、`V6QuotationCommitService.java:154` | W | pending 表清单（→ **C4**） | 活 | — | 🔴 pending |

**列级迁移**：`production_no→production_no` · `process_no→operation_no` · `currency→currency` ·
`version_no→version_no`。`ds_cost_detail_capacity` 只有 14 列，老表 34 列 ——
`fixed_lead_time` / `variable_time` / `variable_time_batch` / `capacity_unit` / `resource_group_*` /
`production_type` / `default_defect_rate` / `annual_discount_factor` / `calc_version` **全部无对应列**。
🚨 `ds_cost_detail_capacity` 的 `labor_std_price` 在老表里没有对应 —— **两表语义并非同一件事**，
不是列缺失而是**模型不同**，迁移前须业务确认。

---

### 2.8 `plating_scheme`（12 行）

> **经 `pg_attribute` 全库扫描，无同名列。**
> 🚨 **`(FROM|JOIN|INTO|UPDATE)\s+plating_scheme\b` 在 `main/java` 命中 0 —— T-5 形态（§0.6）。**
> **0 不代表无引用**：复核后被 **8 个动态 SQL 消费点全部**（T5-1~T5-8）读写 + 4 个 `VersionedV6Writer` handler 调用点。反向证明见 §7 Z2。
> 注：`unit_price.plating_scheme_no` **不构成同名列**（`_no` 后缀，`\b` 边界不匹配）。

| 文件:行 | R/W | 业务场景 | 活/死 | 迁移目标 | 阻塞原因 |
|---|---|---|---|---|---|
| `basicdata/v6/entity/PlatingScheme.java:15` | — | JPA 实体映射 | 活 | `ds_quote_plating_scheme` / `ds_cost_detail_plating_scheme` | 无 |
| `basicdata/v6/pricing/P21PlatingSchemeHandler.java:86 / 88 / 99 / 100` | W | 核价导入「电镀方案」（**C3**） | 活 | `ds_cost_detail_plating_scheme` | 无 |
| `basicdata/v6/quote/Q16PlatingSchemeHandler.java:79 / 81 / 92 / 93` | W | 报价导入「电镀方案」（**C3**） | 活 | `ds_quote_plating_scheme` | 无 |
| `basicdata/v6/versioning/VersionedV6Writer.java:59 / 68` | W | `ALLOWED_TABLES` + `SYSTEM_TYPE_SCOPED` | 活 | — | 无 |
| `datasource/sqlview/QuotePendingRewriter.java:81` | R | pending 白名单 | 活 | — | 🔴 pending |
| `quotation/service/backfill/QuoteTableAxis.java:85 / 129 / 140 / 144` | RW | 回填轴 `Spec PLATING_SCHEME` | 活 | — | 🔴 回填机制 |
| `quotation/service/backfill/BackfillLabelResolver.java:32 / 171` | R | 标签「电镀方案」 | 活 | — | 无 |
| `quotation/service/backfill/QuoteBackfillCollector.java:272 / 384` | R | `isGlobalShared = "plating_scheme".equals(table)` — 全局共享标记 | 活 | 新体系需同等语义 | 🟡 全局共享语义 |
| `quotation/service/backfill/QuoteBackfillService.java:35`、`PendingHygieneService.java:58`、`QuotationService.java:2324`、`QuoteImportService.java:302`、`V6QuotationCommitService.java:154` | W | pending 表清单（→ **C4**） | 活 | — | 🔴 pending |

**列级迁移**：`scheme_no→scheme_no` · `scheme_version→scheme_version` · `seq_no→item_seq` ·
`plating_element→plating_element` · `plating_area→plating_area` · `plating_thickness→coating_thickness` ·
`plating_requirement→plating_requirement` · `density→density`（仅 `ds_cost_detail_plating_scheme` 有）·
`source_url→price_source_url` · `source_name→price_source_name` · `fetch_rule→price_fetch_rule`（仅 `ds_quote_plating_scheme` 有）。
**无对应列**：`plating_method` · `surface_area` · `element_usage` / `element_usage_unit` ·
`effective_date` / `expire_date` · `hf_part_no` · `is_current` · `system_type` · `pending_*`。
🟢 **最低风险表**（12 行、最后写入 2026-08-13、无视图依赖、无 pending 行）。

---

### 2.9 `annual_discount`（13 行，其中 **12 行是 pending**）

> **经 `pg_attribute` 全库扫描，无同名列。**
> 🚨 **`(FROM|JOIN|INTO|UPDATE)\s+annual_discount\b` 在 `main/java` 命中 0 —— T-5 形态（§0.6）。**
> **0 不代表无引用**：复核后被 **5 个动态 SQL 消费点**（T5-1 T5-5 T5-6 T5-7 T5-8）读写；
> ⚠️ 但**不在** T5-2 / T5-4 两个清理清单里 —— 这正是「13 行里 12 行是 pending」的成因（§10 R2）。

| 文件:行 | R/W | 业务场景 | 活/死 | 迁移目标 | 阻塞原因 |
|---|---|---|---|---|---|
| `basicdata/v6/entity/AnnualDiscount.java:18` | — | JPA 实体映射 | 活 | `ds_quote_annual_discount` / `ds_quote_incoming_annual` / `ds_quote_assembly_fee_annual` | 🔴 按 `discount_type` 拆三表 |
| `basicdata/v6/quote/AnnualDiscountWriter.java:40` | W | 常量 `TABLE = "annual_discount"`，`:90`/`:102` 写入（**C3**） | 活（3 个 handler 注入） | 同上 | 🔴 |
| `basicdata/v6/quote/Q08IncomingAnnualDiscountHandler.java:51-53` | W | 报价导入「来料年降」（`discount_type=INCOMING_MATERIAL`） | 活 | `ds_quote_incoming_annual` | 无 |
| `basicdata/v6/quote/Q15AssemblyAnnualDiscountHandler.java:69-71` | W | 报价导入「组装年降」（`ASSEMBLY_PROCESS`） | 活 | `ds_quote_assembly_fee_annual` | 无 |
| `basicdata/v6/quote/Q19AnnualDiscountHandler.java:48-50` | W | 报价导入「成品年降」（`FINISHED`） | 活 | `ds_quote_annual_discount` | 无 |
| `basicdata/v6/versioning/VersionedV6Writer.java:63 / 70` | W | `ALLOWED_TABLES` + `SYSTEM_TYPE_SCOPED` | 活 | — | 无 |
| `datasource/sqlview/QuotePendingRewriter.java:82` | R | pending 白名单 | 活 | — | 🔴 pending |
| `quotation/service/backfill/QuoteTableAxis.java:93 / 132 / 140 / 145` | RW | 回填轴 `Spec ANNUAL_DISCOUNT` | 活 | — | 🔴 回填机制 |
| `quotation/service/QuotationService.java:2324`、`QuoteImportService.java:302`、`V6QuotationCommitService.java:154` | W | pending 表清单（→ **C4**） | 活 | — | 🔴 pending |

⚠️ **注意**：`annual_discount` **不在** `QuoteBackfillService.PENDING_TABLES`（`:34-35`）
也**不在** `PendingHygieneService.PENDING_TABLES`（`:57-58`），**只在** `QuotationService.B8_PENDING_TABLES`（`:2324`）。
⇒ **核价回填成功后 `annual_discount` 的 pending 行不会被清理**，只在删单时清 ——
这解释了「13 行里 12 行是 pending」。**这是一个已存在的数据卫生缺口，本任务只记录不修**（见 §10）。

**列级迁移**：`material_no→material_no` · `discount_order→discount_seq` · `discount_ratio→discount_rate` ·
`fixed_discount_value→fixed_discount_value` · `currency→currency` · `unit→pricing_unit` ·
`discount_times→discount_times` · `version_no→version_no` · `customer_no→customer_no`。
**无对应列**：`discount_type`（拆表后由表名承载）· `target_no` · `seq_no` · `system_type` · `is_current` · `pending_*`。

---

### 2.10 `material_customer_map`（61 行）

> **经 `pg_attribute` 全库扫描，无同名列。**

| 文件:行 | R/W | 业务场景 | 活/死 | 迁移目标 | 阻塞原因 |
|---|---|---|---|---|---|
| `basicdata/v6/entity/MaterialCustomerMap.java:12` | — | JPA 实体映射 | 活 | `ds_quote_customer_part` | 无 |
| `basicdata/v6/repository/MaterialCustomerMapRepository.java:36` | W | `upsert()` — 核价导入客户映射 | 活（main 7 处） | `ds_quote_customer_part` | 无 |
| `…MaterialCustomerMapRepository.java:128` | W | `upsertBatch()` | **死**（`codegraph_callers` 0） | 无需迁 | 无 |
| `…MaterialCustomerMapRepository.java:160`（`upsertQuote` 208/217 的 SQL） | W | `upsertQuote()` — 报价侧单行 upsert | **死**（0 main caller；4 个 caller **全在测试**） | 无需迁 | 无 |
| `…MaterialCustomerMapRepository.java:219`（`upsertQuoteBatch` 297 的 SQL） | W | `upsertQuoteBatch()` — 报价侧批量 upsert | 活（main 1 处） | `ds_quote_customer_part` | 无 |
| `…MaterialCustomerMapRepository.java:324` | W | `upsertQuoteBatch` 内第 2 段 | 活 | 同上 | 无 |
| `…MaterialCustomerMapRepository.java:375` | **W** | `deleteByCustomerNo()` — 按客户整清 | **死**（`codegraph_callers` 0） | 无需迁 | 无 |
| `…MaterialCustomerMapRepository.java:387` | **W** | `deleteQuoteMappingsByCustomerNo()` | 活（main 1 处） | `ds_quote_customer_part` | 无 |
| `…MaterialCustomerMapRepository.java:400` | **W** | `deleteQuotePendingMappingsByCustomerNo()` | 活（main 1 处） | **无对应**（无 pending 概念） | 🔴 pending |
| `basicdata/v6/repository/MaterialMasterRepository.java:354 / 388` | R | pending 料号删除守卫 `NOT EXISTS` | 活 | — | 🔴 pending |
| `basicdata/v6/service/QuoteMaterialNoAllocator.java:57` | **W** | `mintAndRegister()` — **占号**：发新料号并登记客户归属 | 活 | `ds_quote_customer_part` | 🔴 **全局唯一占号语义**（跨客户串号防护） |
| `basicdata/v6/service/QuoteMaterialNoAllocator.java:91 / 96` | **W/R** | `ensureRegistered()` — 补登记 + 跨客户冲突检查 | 活 | 同上 | 🔴 同上 |
| 🚨 `basicdata/v6/service/V6QuotationCommitService.java:75` | R | `createQuotation()` — `SELECT DISTINCT customer_product_no, material_no FROM material_customer_map`。**入口是新 dataset 的「由批次建单」**：`dataset/quotation/QuotationImportResource.java:227 commitService.createQuotation(req, userId)`（已实测核实；`:201` 是 Javadoc 不是调用）⇒ **新体系仍在读老表** | 活 | `ds_quote_customer_part.{customer_product_no,material_no}` | 无 |
| 🚨 `basicdata/v6/service/V6QuotationCommitService.java:139` | **W** | `repointPendingOwnership()` —— `"UPDATE " + table + " SET pending_quotation_id = :qid …"` 对 **10 张表**过户（**T5-6 / W32**） | 活 | **无对应** | 🔴 pending |
| `basicdata/v6/quote/Q02CustomerMapHandler.java:207`、`basicdata/v6/pricing/P05CustomerMapHandler.java:76` | W | 导入写入计数 | 活 | — | 无 |
| `quotation/snapshot/SnapshotCollectorService.java:316` | R | 提交冻结时收集客户映射快照（`JOIN customer`） | 活 | `ds_quote_customer_part` | 无 |
| `quotation/service/QuotationService.java:3514` | R | `loadLineItems()` — 打开报价单读客户产品号 | 活 | `ds_quote_customer_part.customer_product_no` | 无 |
| `quotation/service/CustomerPartCandidateService.java:71 / 79 / 170` | R | 报价向导 Step1 客户料号候选 | 活 | 同上 | 无 |
| `quotation/service/backfill/QuoteBackfillService.java:145` | **W** | `flipMaterialCustomerMap()` — 核价通过后占号转正（`UPDATE … pending_quotation_id = NULL`） | 活（`:85`） | **无对应** | 🔴 pending |
| `quotation/service/backfill/QuoteBackfillService.java:35`、`PendingHygieneService.java:58`、`QuotationService.java:2324`、`QuoteImportService.java:302`、`V6QuotationCommitService.java:154` | W | pending 表清单（→ **C4**） | 活 | — | 🔴 pending |
| `priceadjust/service/PriceAdjustStrategyService.java:203` | R | 价格调整策略物料列表主表 | 活 | `ds_quote_customer_part` | 无 |
| `existingproduct/service/ExistingProductService.java:123 / 180` | R | 已有产品抽屉主表 + `mcm2` 子查询 | 活 ⏳ **在途** | 对方 B-1 将**删掉这一支** | ⏳ 归 `task-260909-已有产品抽屉数据源收敛` B-1 |
| `configure/service/ConfigureProductService.java:1569` | R | `findProductNoOwner()` — 客户产品号占用检查 | 活（`checkProductNo` → REST） | `ds_quote_customer_part` | 无 |
| 🚨 `modelconfig/service/ModelConfigService.java:304` | R | `MaterialCustomerMap.<MaterialCustomerMap>find("materialNo in ?1", salesPartNos).list()` —— 3D 模型配置按销售料号反查客户映射。**Panache 静态查询，整行无表名字面量**（T-5d 同族，靠实体符号反查才发现） | 活 | `ds_quote_customer_part.material_no` | 无 |
| 🚨 `configure/service/ConfigureProductService.java:428`（SIMPLE）· `:1831`（COMPOSITE） | **W** | `quoteAllocator.mintAndRegister(...)` —— 选配下单**占号**（W41，**T-5d**） | 活 | `ds_quote_customer_part` | 🔴 全局唯一占号语义 |

**列级迁移**：`customer_no→customer_no` · `customer_material_name→customer_part_name` ·
`customer_product_no→customer_product_no` · `customer_drawing_no→customer_drawing_no` ·
`material_no→material_no`。
**无对应列**：`customer_name`（可 JOIN `customer` 取）· `seq_no` · `payment_method` ·
`base_currency` / `quote_currency` / `exchange_rate` · `system_type` · `production_no` ·
**`pending_quotation_id`**。
🚨 **`ds_quote_customer_part` 只有 11 列，老表 19 列** —— 币种/汇率/付款方式三列在新表**完全缺失**，
若还有业务消费方，退役会静默丢功能。

---

## §3 全部写点汇总（B-3 / AC-6）

> ⚠️ **必须覆盖 Panache / JPQL / 动态表名路径** —— 纯 grep SQL 字符串会漏掉 C3/C4 两个通道，
> 那正是本表 30 个写点里 22 个的所在。

### 3.1 AC-6 点名的 5 处（逐条核对，全部在列 ✅）

| AC-6 要求 | 实测位置 | 核对 |
|---|---|---|
| `QuotationService.java:2332` DELETE `material_master` | `:2332 int deleted = materialMasterRepository.deletePendingWithGuard(quotationId);` | ✅ 行号逐字吻合 |
| `QuoteBackfillService.java:77` UPDATE（`flipPending`） | `:77 materialMasterRepo.flipPending(quotationId);` | ✅ |
| `QuoteBackfillService.java:80` INSERT/UPDATE（`upsertByMaterialNo`） | `:80 materialMasterRepo.upsertByMaterialNo(e.getKey(), …);` | ✅ |
| `PendingHygieneService.java:123` DELETE | `:123 int mmDeleted = dryRun ? 0 : materialMasterRepository.deleteOrphanPendingWithGuard();` | ✅ |
| `ElementRecoveryDiscountRepository.java:39` 与 `:140` UPDATE `element_bom_item` | `:39 "UPDATE element_bom_item SET recovery_discount = :rd …"`（`updateOne`）／ `:140 "UPDATE element_bom_item AS ebi SET recovery_discount = v.rd …"`（`batchUpdate`） | ✅ ⚠️ **`:39` 的 `updateOne` 是死代码**（0 生产调用点，仅测试用） |

### 3.2 穷举表（**41 个写点**）

> ⚠️ **W35~W38 是主线回流 T-5d 后补录的 4 处**（测试代理 AC-11 反向抽样抓到，我复核了行号）；
> **W39 / W40 / W41 是我顺 T-5d 路径重扫 10 张表时另外捞到的 3 处**（主线点名 4 处，实际 7 处）。
> 其中 **W39 是核价回填的写入主路径**，是本次补录里最严重的一条。
>
> ⚠️ **W32 / W33 / W34 是主线追加 T-5 后二次追查补入的** —— 第一版清单把 `V6QuotationCommitService.PENDING_TABLES` 与
> `QuoteImportService.PENDING_TABLES` 只记成「清单成员」，**漏了它们各自的拼接执行点**。这正是 T-5 的典型失败形态。

| # | 位置 | 动作 | 目标表 | 通道 | 活/死 | 业务场景 |
|---|---|---|---|---|---|---|
| W1 | `MaterialMasterRepository.java:98` | INSERT…ON CONFLICT | `material_master` | C1 | 活 | `upsertByMaterialNo` 单行 |
| W2 | `MaterialMasterRepository.java:200` | INSERT…ON CONFLICT | `material_master` | C1 | 活 | `upsertBatchNameType` 批量 |
| W3 | `MaterialMasterRepository.java:263` | INSERT…ON CONFLICT | `material_master` | C1 | 活 | `upsertBatchWithWeight` 批量 |
| W4 | `MaterialMasterRepository.java:296/303` | INSERT | `material_master` | C1 | **死**（0 caller） | `upsertBatchMaterialNoOnly` |
| W5 | `MaterialMasterRepository.java:326` | UPDATE | `material_master` | C1 | 活 | `flipPending` 转正 |
| W6 | `MaterialMasterRepository.java:346` | DELETE（带守卫） | `material_master` | C1 | 活 | `deletePendingWithGuard` 删单回收 |
| W7 | `MaterialMasterRepository.java:377` | DELETE（带守卫） | `material_master` | C1 | 活 | `deleteOrphanPendingWithGuard` 孤儿清理 |
| W8 | `MaterialMasterCrudService.java:57/69/83` | Panache persist / 字段赋值 / delete | `material_master` | **C2** | 活 | 物料主档 CRUD（🚫 grep SQL 抓不到） |
| W9 | `QuotationService.java:2332` | DELETE（经 W6） | `material_master` | C1 | 活 | 报价单删除 |
| W10 | `QuoteBackfillService.java:77` | UPDATE（经 W5） | `material_master` | C1 | 活 | 核价通过转正 |
| W11 | `QuoteBackfillService.java:80` | UPSERT（经 W1） | `material_master` | C1 | 活 | ADD 行补 stub |
| W12 | `PendingHygieneService.java:123` | DELETE（经 W7） | `material_master` | C1 | 活 | 孤儿 pending 体检清理 |
| W13 | `MaterialRecipeService.java:269` | UPDATE | `material_master` | C1 | 活 | 材质配方**绑定** |
| W14 | `MaterialRecipeService.java:287` | UPDATE | `material_master` | C1 | 活 | 材质配方**解绑** |
| W15 | `MaterialRecipeService.java:679` | UPDATE | `material_master` | C1 | 活 | 建议绑定**确认** |
| W16 | 🚨 `QuotationService.java:2328`（**T5-1**） | `"DELETE FROM " + table + " WHERE pending_quotation_id = :qid"` | **9 张表**（`B8_PENDING_TABLES` `:2323-2324`） | **C4** | 活 | 报价单删除清 pending |
| W17 | 🚨 `QuoteBackfillService.java:172`（**T5-2**） | `"DELETE FROM " + table + " WHERE pending_quotation_id = :qid"` | **8 张表**（`PENDING_TABLES` `:34-35`，**不含 `annual_discount`**） | **C4** | 活 | 核价通过后清 pending 草稿 |
| W18 | 🚨 `PendingHygieneService.java:162`（**T5-4**） | `"DELETE FROM " + table + " x " …` | **8 张表**（`PENDING_TABLES` `:57-58`） | **C4** | 活 | 孤儿 pending 清理（BL-0092） |
| W19 | `QuoteBackfillService.java:145` | UPDATE | `material_customer_map` | C1 | 活 | 占号 pending→approved |
| W20 | `MaterialCustomerMapRepository.java:36` | INSERT…ON CONFLICT | `material_customer_map` | C1 | 活 | `upsert` 核价侧 |
| W21 | `MaterialCustomerMapRepository.java:160` | INSERT…ON CONFLICT | `material_customer_map` | C1 | **死**（仅测试） | `upsertQuote` |
| W22 | `MaterialCustomerMapRepository.java:219/324` | INSERT…ON CONFLICT | `material_customer_map` | C1 | 活 | `upsertQuoteBatch` |
| W23 | `MaterialCustomerMapRepository.java:375` | DELETE | `material_customer_map` | C1 | **死**（0 caller） | `deleteByCustomerNo` |
| W24 | `MaterialCustomerMapRepository.java:387` | DELETE | `material_customer_map` | C1 | 活 | 按客户清报价映射 |
| W25 | `MaterialCustomerMapRepository.java:400` | DELETE | `material_customer_map` | C1 | 活 | 清本单 pending 映射 |
| W26 | `QuoteMaterialNoAllocator.java:57 / 91` | INSERT | `material_customer_map` | C1 | 活 | **占号**（`mintAndRegister` / `ensureRegistered`） |
| W27 | `ElementRecoveryDiscountRepository.java:39` | UPDATE | `element_bom_item` | C1 | **死**（仅测试） | `updateOne` |
| W28 | `ElementRecoveryDiscountRepository.java:140` | UPDATE…FROM(VALUES) | `element_bom_item` | C1 | 活 | Q05 回收折扣批量写 |
| W29 | `UnitPriceWriter.java:24` | INSERT | `unit_price` | C1 | 活 | 单价直写 |
| W30 | 🚨 **`VersionedV6Writer`**（`writeVersionedGroup(s)` / `writeVersionedMasterDetail(s)`），调用点 **25 处** | flip `is_current=false` + INSERT 新版本 | `unit_price`(17) `material_bom`(4) `material_bom_item`(4) `element_bom`(4) `element_bom_item`(4) `capacity`(3) `plating_scheme`(4) `annual_discount`(2) | **C3** | 活 | 导入 / 选配下单的**主写入路径** |
| W31 | `ConfigureProductService.java:1225 / 1242 / 1260` | INSERT…SELECT | `element_bom_item` / `material_bom_item` / `material_bom` | C1 | 活 | 选配「已有件换客户」复制 BOM |
| **W32** | 🚨 `V6QuotationCommitService.java:139` | `"UPDATE " + table + " SET pending_quotation_id = :qid WHERE pending_quotation_id = :rid"` | **10 张表**（`PENDING_TABLES:152-154`，**含 `material_master`**） | **C4 / T5-6** | 活 | `repointPendingOwnership()` —— 导入建单时把 pending 归属从 `importRecordId` **过户**到 `quotationId`。入口：`QuotationImportResource.java:227 → createQuotation():37 → :139` |
| **W33** | 🚨 `QuoteImportService.java:282` | `"DELETE FROM " + table + " WHERE pending_quotation_id = :pq"` | **9 张表**（`PENDING_TABLES:300-302`） | **C4 / T5-5** | 活 | `clearPreviousPending()` —— 重新导入同一暂存单时清掉上一轮 pending（BL-0072） |
| **W34** | 🚨 `VersionedV6Writer.java:507`（`UPDATE … SET is_current = FALSE`）· `:516`（DELETE）· `:1058` `:1103` `:1133`（INSERT） | 动态表名 | **8 张表**（`ALLOWED_TABLES:58-63`） | **C3 / T5-8** | 活 | W30 的**物理执行点** —— 25 个 handler 调用最终落到这 5 条拼接 SQL |
| **W35** | `P16IncomingOtherRatioFeeHandler.java:52` `writer.upsert(p)` | INSERT（经 W29） | `unit_price` | **T-5d** | 活 | 核价导入「来料其它比例费」。表名只在 `:54 recordWrite("unit_price",1)` 记账里 |
| **W36** | `P17IncomingOtherFixedFeeHandler.java:54` `writer.upsert(p)` | INSERT（经 W29） | `unit_price` | **T-5d** | 活 | 核价导入「来料其它固定费」（记账在 `:56`） |
| **W37** | `P19FinishedOtherRatioFeeHandler.java:51` `writer.upsert(p)` | INSERT（经 W29） | `unit_price` | **T-5d** | 活 | 核价导入「成品其它比例费」（记账在 `:53`） |
| **W38** | `P20FinishedOtherFixedFeeHandler.java:53` `writer.upsert(p)` | INSERT（经 W29） | `unit_price` | **T-5d** | 活 | 核价导入「成品其它固定费」（记账在 `:55`） |
| **W39** | 🚨 `QuoteBackfillService.java:97` `writer.writeVersionedMasterDetail(spec.master.masterTable, …, g.table, …)` · `:101` `writer.writeVersionedGroup(new VersionedGroupSpec(g.table, …))` | flip + INSERT 新版本 | **8 张受管表**（表名 100% 来自 `QuoteTableAxis.Spec` / `g.table` 变量） | **T-5d（最纯形态）** | 活 | `executeRebuild()` —— **核价回填真正改数据的主路径**。调用点与整段代码无任何表名字面量 |
| **W40** | `QuoteImportService.java:285` `materialMasterRepo.deletePendingWithGuard(pendingQuotationId)` | DELETE（经 W6） | `material_master` | **T-5d** | 活 | `clearPreviousPending()` —— 重导同一暂存单时回收上轮 pending 料号（第 3 个调用点） |
| **W41** | `ConfigureProductService.java:428` · `:1831` `quoteAllocator.mintAndRegister(salesCtx.customerNo, salesCtx.yyMm)` | INSERT（经 W26） | `material_customer_map` | **T-5d** | 活 | 选配下单**占号**（SIMPLE `:428` / COMPOSITE `:1831`）。注释点名须与 V6 落库同事务 |

> **W30 的 25 个调用点**（`writer.*("<表名>", …)`）：
> `unit_price` — `FinishedOtherMergeHandler:106` `IncomingOtherMergeHandler:117` `P01:71` `P02:72` `P13:98`
> `P14:68` `P15:74` `P18:69` `P22:120` `P23:68` `Q06:140,150` `Q07:129,139` `Q09:156,167` `Q10:98,108`
> `Q11:76,86` `Q13:127,137` `Q17:139,149` `ConfigureProductService:1079,1334,1381`
> `material_bom` + `material_bom_item` — `P06:136,137,163,164` `MaterialBomMergeHandler:220,221,249,250`
> `element_bom` + `element_bom_item` — `P07:91,92,113,114` `Q04:105,106,127,128`
> `capacity` — `P08:104` `Q14:103,113` `ConfigureProductService:1443`
> `plating_scheme` — `P21:86,99` `Q16:79,92`
> `annual_discount` — `AnnualDiscountWriter:90,102`（3 个 handler Q08/Q15/Q19 注入）

🚨 **W16/W17/W18/W32/W33/W34 六处属 T-5a/b/c**（表名来自 `List<String>` / `Set<String>` 常量、SQL 字符串拼接）；
🚨 **W35~W41 七处属 T-5d**（写入调用点完全不含表名，必须从写入器方法反向枚举，见 §0.6）。
⇒ **41 个写点里 13 个是纯字面量正则找不到的**，占 32%。
`/usr/bin/grep -rnaE "(FROM|JOIN|INTO|UPDATE)\s+annual_discount"` 在 `main/java` **命中 0**，
但 `annual_discount` 的 12 行 pending 数据**确实会被 W16 删掉**。

---

## §4 死代码清单（B-2 / AC-5）

**判定纪律**：**双证据**才算「死」—— ① 注释自述 **且/或** ② `codegraph_callers` 实测 0 调用点。
🚫 只有注释不算（注释滞后于现实正是本任务的起因）。

| # | 位置 | 涉及的老表 | 证据①（注释） | 证据②（实测调用点） | 判定 |
|---|---|---|---|---|---|
| D1 | `builder/compiler/SemanticCompiler.java:1497-1502` `closureCte()` | `material_bom_item`（`:1505` `:1510`） | ✅ `🛑 停用（task-260819 B-5，D-50）：A 机制…本方法不再被 compile 的任何路径调用` | ✅ `codegraph_callers("closureCte")` → **No callers found**；`/usr/bin/grep -rna "closureCte"` 全工程 4 处命中，**3 处是注释**（`SemanticCompiler:23` `:387` `BuilderService:493`）+ **1 处是声明本身**（`:1502`），**0 处调用** | **死** |
| D2 | `configure/service/ConfigureSnapshotService.java:1114` `resolveCompositeChildren()` | `material_bom_item`（`:1125`） | — | ✅ `codegraph_callers` → **No callers found**；grep 全工程仅 1 处 = 声明自身 | **死**（无注释自述，靠实测发现） |
| D3 | `basicdata/v6/repository/MaterialBomRepository.java:13` `findOne()` | `material_bom` | — | ✅ `codegraph_callers("findOne")` → No callers（聚合了 `MaterialBomRepository:13` + `UnitPriceRepository:14` 两个同名符号，两者均 0） | **死** |
| D4 | `basicdata/v6/repository/MaterialBomRepository.java:22` `findLatestCharacteristic()` | `material_bom` | — | ✅ `codegraph_callers` → No callers | **死** |
| D5 | ⇒ **`MaterialBomRepository` 整类死**（仅有的 2 个方法都 0 caller） | `material_bom` | — | ✅ 同 D3/D4 | **死** |
| D6 | `basicdata/v6/repository/UnitPriceRepository.java:14` `findOne()` | `unit_price` | — | ✅ 同 D3 | **死** |
| D7 | ⇒ **`UnitPriceRepository` 整类死**（唯一方法 0 caller） | `unit_price` | — | ✅ | **死** |
| D8 | `basicdata/v6/repository/MaterialBomItemRepository.java:22` `findByParent()` | `material_bom_item` | — | ✅ `codegraph_callers("findByParent")` → No callers；main/test 均 0 | **死** |
| D9 | `basicdata/v6/repository/MaterialMasterRepository.java:21` `findByMaterialNo()` | `material_master` | — | ✅ `codegraph_callers` → No callers（2 个测试文件里的同名调用属其它类） | **死** |
| D10 | `basicdata/v6/repository/MaterialMasterRepository.java:31` `maxNineLeadingMaterialNo()` | `material_master` | — | ✅ `codegraph_callers` → No callers | **死** |
| D11 | `basicdata/v6/repository/MaterialMasterRepository.java:40` `lockForMaterialNoGeneration()` | `material_master` | — | ✅ `codegraph_callers` → No callers；main/test 均 0 | **死** |
| D12 | `basicdata/v6/repository/MaterialMasterRepository.java:296/303` `upsertBatchMaterialNoOnly()` | `material_master`（**写点** W4） | — | ✅ `codegraph_callers` → No callers（明确聚合了 2 个重载） | **死**（生产），2 个测试文件在用 |
| D13 | `basicdata/v6/repository/MaterialCustomerMapRepository.java:128` `upsertBatch()` | `material_customer_map`（**写点**） | — | ✅ `codegraph_callers` → No callers | **死** |
| D14 | `basicdata/v6/repository/MaterialCustomerMapRepository.java:208/217` `upsertQuote()` | `material_customer_map`（**写点** W21） | — | ✅ `codegraph_callers` → 4 个 caller，**全部在 `src/test`**（`MaterialCustomerMapUpsertBatchSqlCountTest:134`、`MaterialCustomerMapRepositoryTest:75/88/101`） | **生产死 / 测试活** |
| D15 | `basicdata/v6/repository/MaterialCustomerMapRepository.java:373` `deleteByCustomerNo()` | `material_customer_map`（**写点** W23） | — | ✅ `codegraph_callers` → No callers | **死** |
| D16 | `basicdata/v6/repository/ElementRecoveryDiscountRepository.java:36` `updateOne()` | `element_bom_item`（**写点** W27，AC-6 点名） | — | ✅ `codegraph_callers` → No callers；main 0 文件 / test 1 文件 | **生产死 / 测试活** |
| D17 | `basicdata/v6/service/MaterialNoResolver.java:100` `resolveMatchOnly()` | `material_master` | — | ✅ `codegraph_callers` → No callers；main 0 / test 1 | **生产死 / 测试活** |
| D18 | 纯注释引用（不构成运行期依赖，退役时可直接改文字）：`SheetImportResult.java:14` · `Q02CustomerMapHandler.java:25` · `P22PlatingCostHandler.java:31` · `MaterialRecipeDTO.java:51` · `ConfigureProductService.java:277`/`:1236` · `QuotePendingRewriter.java:119`/`:461`/`:464`/`:526` · `QuoteBackfillColumnMapper.java:119` · `SemanticCompiler.java:23`/`:387` · `BuilderService.java:493` | 多表 | 自明 | — | **注释** |

> ⚠️ **D14 / D16 / D17 / D12 是「生产死、测试活」**：
> 删掉这些方法会让对应测试编译失败。它们同时也解释了「为什么 `mvnw test` 会往共享库写老表」——
> **测试是这些写点在 2026-09 唯一的调用方**。

---

## §5 视图依赖传递闭包（D-1 / AC-7）

### 5.1 递归 CTE（`lvl < 5`）

```sql
WITH RECURSIVE base AS (
  SELECT c.oid AS tbl_oid, c.relname AS tbl FROM pg_class c
  JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='public' AND c.relname IN (<10 张表>)
), dep AS (
  SELECT DISTINCT 1 AS lvl, b.tbl AS root_tbl, v.relname AS view_name, v.oid AS view_oid, b.tbl AS via
  FROM base b
  JOIN pg_depend d ON d.refobjid = b.tbl_oid
  JOIN pg_rewrite r ON r.oid = d.objid
  JOIN pg_class v ON v.oid = r.ev_class
  WHERE d.classid='pg_rewrite'::regclass AND v.relkind IN ('v','m') AND v.oid <> b.tbl_oid
  UNION ALL
  SELECT dp.lvl+1, dp.root_tbl, v2.relname, v2.oid, dp.view_name
  FROM dep dp
  JOIN pg_depend d2 ON d2.refobjid = dp.view_oid
  JOIN pg_rewrite r2 ON r2.oid = d2.objid
  JOIN pg_class v2 ON v2.oid = r2.ev_class
  WHERE d2.classid='pg_rewrite'::regclass AND v2.relkind IN ('v','m')
    AND v2.oid <> dp.view_oid AND dp.lvl < 5
)
SELECT view_name, lvl, root_tbl, via FROM dep GROUP BY 1,2,3,4 ORDER BY 1,2,3;
```

### 5.2 结果：**8 个视图全部在列**（阶数与 AC-7 表逐字一致 ✅）

| 视图 | 阶 | 经由 / 依赖的老表 | 依赖到的列（`pg_depend.refobjsubid`） |
|---|---|---|---|
| `v_compat_material_master` | **1** | ← `material_master` | 全部 18 列（`config_fingerprint, created_at, created_by, dimension, id, material_name, material_no, material_recipe_id, material_type, old_material_no, pending_quotation_id, production_no, specification, standard_unit, unit_weight, updated_at, updated_by, usage_property`） |
| `v_compat_material_bom_item` | **1** | ← `material_bom_item` | 全部 58 列 |
| `v_compat_element_bom_item` | **1** | ← `element_bom_item` | 全部 54 列 |
| `v_ds_cost_basic_element_bom_all` | **1** | ← `material_master`（LATERAL 桥） | `material_no`, `production_no` |
| `v_ds_cost_detail_element_bom_all` | **1** | ← `material_master`（LATERAL 桥） | `material_no`, `production_no` |
| `v_composite_child_processes` | **1** | ← `unit_price` | `cost_type, customer_no, finished_material_no, is_current, operation_no, system_type` |
| `v_composite_child_processes` | **2** | ← 经 `v_compat_material_bom_item` | `characteristic, component_no, customer_no, is_current, material_no, operation_no, seq_no, system_type` |
| `v_composite_child_processes` | **2** | ← 经 `v_compat_material_master` | `material_name, material_no` |
| `v_composite_child_materials` | **2** | ← 经 `v_compat_material_bom_item` | `characteristic, component_no, component_usage_type, customer_no, is_current, material_no, seq_no, system_type` |
| `v_composite_child_materials` | **2** | ← 经 `v_compat_material_master` | `material_name, material_no, material_type, specification` |
| `v_composite_child_elements` | **2** | ← 经 `v_compat_element_bom_item` | `characteristic, component_no, content, customer_no, hf_part_no, is_current, material_no, material_part_no, seq_no, system_type` |
| `v_composite_child_elements` | **2** | ← 经 `v_compat_material_master` | `material_name, material_no` |

⚠️ **`v_composite_child_processes` 同时是一阶和二阶**，三条依赖路径全部列出（AC-7 要求「两条路径都要列」，实测是**三条**）。

### 5.3 一阶查询漏项实证（为什么必须递归）

同一批 10 张表，**只做一阶** `pg_depend` JOIN：

```
v_compat_element_bom_item
v_compat_material_bom_item
v_compat_material_master
v_composite_child_processes
v_ds_cost_basic_element_bom_all
v_ds_cost_detail_element_bom_all
```

⇒ **6 个**。递归版 **8 个**。**漏掉的正是 `v_composite_child_materials` 与 `v_composite_child_elements`** ——
与 `任务.md` AC-7 的预警逐字吻合。

### 5.4 退役影响

- **`DROP TABLE material_master`** 会同时打掉 `v_compat_material_master`（一阶）、
  **`v_ds_cost_basic_element_bom_all` / `v_ds_cost_detail_element_bom_all`**（一阶，且这两个是
  `semantic_node.physical_table` 的取数源，见 §7）、以及 3 个 `v_composite_child_*`（二阶）。
- **`v_compat_*` 与 `v_composite_child_*` 互斥**：想保 composite 就不能退 compat。
- 🚫 **本节只列出，不改动任何视图**（AC-15 边界）。

---

## §6 配置层扫描（D-2 / AC-8 AC-13）

### 6.1 16 个配置列逐个命中数

> 扫描口径：`\y<表名>\y`（PG 词边界）对 9 张表；`unit_price` 单列并给「作表名」口径。

| # | 来源（表.列） | 该列总行数 | 9 表命中 | `unit_price` 裸命中 | `unit_price` 作表名 | `v_compat_*` 命中 | 备注 |
|---|---|---|---|---|---|---|---|
| 1 | `component_sql_view.sql_template` | 42 | **0** | 12 | **0** | 0 | 🚨 见 §6.2 反向证明 A |
| 2 | `component_sql_view.builder_config` | 42 | **0** | 15 | **0** | 0 | 同上 |
| 3 | `template_sql_view.sql_template` | **0**（空表） | **0** | 0 | 0 | 0 | 表本身 0 行 |
| 4 | `template.sql_views_snapshot` | 17 | **0** | 9 | **0** | 0 | |
| 5 | `template.template_sql_views_snapshot` | 17 | **0** | 0 | 0 | 0 | |
| 6 | `template.components_snapshot` | 17 | **0** | 0 | 0 | 0 | |
| 7 | `template_component_snapshot.data_driver_path` | 78 | **0** | 0 | 0 | 0 | |
| 8 | `template_component.data_driver_path_override` | 78 | **0** | 0 | 0 | 0 | |
| 9 | `component.data_driver_path` | 107 | **0** | 0 | 0 | 0 | 实际值形如 `$builder_a2e1bb386be1` / `$test_pricebase_view` |
| 10 | `component.tree_config` | 107 | **0** | 0 | 0 | 0 | |
| 11 | `quotation_component_sql_snapshot.sql_template` | 583 | **0** | 47 | **0** | 0 | |
| 12 | **`costing_bom_tree_config.sql_template`** | 2 | **1** | 0 | 0 | **1** | 🚨 见 §6.3 |
| 13 | `datasource.sql_query` | **0**（空表） | **0** | 0 | 0 | 0 | 表本身 0 行 |
| 14 | `global_variable_definition.source_view` | **0**（空表） | **0** | 0 | 0 | 0 | 表本身 0 行 |
| 15 | `semantic_node.physical_table` | 57 | **0** | 0 | 0 | 0 | 🚨 见 §6.4（**间接**依赖） |
| 16 | `semantic_node.anchor_expr` | 57 | **0** | 0 | 0 | 0 | |

**合计**：配置层对 10 张老表的**直接**命中 = **2 条**，都在 `costing_bom_tree_config.sql_template`。

### 6.2 反向证明 A —— `component_sql_view` 的 0 不是「量具坏了」（AC-13）

同一 pattern 打到备份表 `component_sql_view_backup_260903`：

```sql
SELECT count(*) AS 总行,
       count(*) FILTER (WHERE coalesce(sql_template,'') ~
         '\y(material_master|material_bom_item|element_bom_item|material_bom|element_bom|capacity|plating_scheme|annual_discount|material_customer_map)\y') AS 命中9表
FROM component_sql_view_backup_260903;
-- 135 | 135
```

⇒ **135 行全部命中**。量具完好。
⇒ 现网 `component_sql_view` 的 **0 是「已迁走」**（V411 改写 + 取数配置器接管），**不是「本来就干净」**。
补充证据：现网 42 行的 `FROM` 表分布已全是新表 ——
`ds_quote_element_bom(15) ds_quote_material(12) ds_quote_material_bom(8) ds_quote_self_process_fee(3)
v_ds_cost_basic_material_bom_all(2) ds_cost_basic_material(2) …`

### 6.3 反向证明 B —— `costing_bom_tree_config` 的 1 是真的（AC-8 点名项）

```sql
SELECT id, substring(sql_template from '(?i)from[[:space:]]+[a-z_0-9]+') FROM costing_bom_tree_config;
82612f2b-558a-4b13-b052-af08100573ac | FROM material_bom_item
d6defaa0-354f-4e92-8e89-4bc8454888c3 | FROM v_compat_material_bom_item
```

| 命中 | 是否 AC-8 点名 | 说明 |
|---|---|---|
| `d6defaa0…` → `FROM v_compat_material_bom_item` | ✅ AC-8 明确要求包含 | 经兼容视图，**二级依赖** |
| `82612f2b…` → **`FROM material_bom_item`** | ⚠️ **AC-8 基线之外的新发现** | **直连老表**，V411 未改写到 |

🚨 **`costing_bom_tree_config` 共 2 行，两行都指向 `material_bom_item`（一行直连、一行经兼容视图）**——
这张表是**配置层唯一还挂着老表的地方**，退役前必须两行都改。

### 6.4 `semantic_node.physical_table` 的 0 是**间接依赖**，不是无依赖（AC-13 补充）

`physical_table` 的 57 行取值全是新表/新视图名，对 10 张老表 0 直接命中。**但其中两个取值是**：

```
v_ds_cost_basic_element_bom_all
v_ds_cost_detail_element_bom_all
```

而 §5 已实测这两个视图 **一阶 LATERAL JOIN `material_master`**（`material_no` / `production_no` 桥）。
⇒ **`semantic_node` 对 `material_master` 存在经视图的二级依赖，字符串扫描永远看不到**。
这条与 `任务.md §①` 的「`v_ds_cost_*_all` 禁止动」是同一件事的两个侧面。

### 6.5 `unit_price` 在配置层的 T-1 消歧（AC-3 在配置层的落实）

配置层 `unit_price` 裸命中 83 行（12+15+9+47），**作表名 0 行**。
实际形态（`component_sql_view.sql_template` 抽样）：

```
b.element_code AS "_物料与元素BOM_元素",
  cep.unit_price AS "元素单价"
FROM ds_quote_…
```

⇒ 全部是 **`customer_element_price` 别名 `cep` 的输出列**，不是表名。

---

## §7 全部 0 命中的反向证明汇总（AC-13）

| # | 0 值出现处 | 反向证明（同一 pattern 打到已知存在的引用点） | 结论 |
|---|---|---|---|
| Z1 | `element_bom` 在 `main/java` 作表名 **0**（**T-5**，§0.6） | 同一 pattern `(FROM\|JOIN\|INTO\|UPDATE\|TABLE)\s+<t>\b` 对 `material_master` → **20 行命中**；对 `material_customer_map` → **23 行命中** ⇒ pattern 有效 | **「0 个字面量」是真的，「0 个引用」是假的** —— 实际被 T5-1~T5-8 全部 8 个消费点读写 |
| Z2 | `plating_scheme` 在 `main/java` 作表名 **0**（**T-5**） | 同上 | 同 Z1：**8 个动态消费点全部** + 4 个 `VersionedV6Writer` handler 调用点（引号字面量口径 25 个引用点） |
| Z3 | `annual_discount` 在 `main/java` 作表名 **0**（**T-5**） | 同上 | 同 Z1：5 个动态消费点（T5-1/5/6/7/8）+ `AnnualDiscountWriter.TABLE` 常量；⚠️ 不在 T5-2/T5-4 清理清单 |
| Z4 | `component_sql_view.sql_template` / `.builder_config` 对 9 表 **0** | §6.2：同 pattern 在 `component_sql_view_backup_260903` 命中 **135/135** | 0 = 已迁走（V411 + 取数配置器），**不是本来就干净** |
| Z5 | `template.sql_views_snapshot` 对 9 表 **0**，`v_compat_*` 也 **0** | 同一条 SQL 里 `costing_bom_tree_config` 命中 1（9 表）+ 1（`v_compat`）⇒ 两个 pattern 都能打中 | 0 是真的 |
| Z6 | `quotation_component_sql_snapshot.sql_template`（583 行）对 9 表 **0** | 同上；且 `FROM` 表分布实测全为 `ds_quote_*`（`ds_quote_material` 94 / `ds_quote_material_bom` 94 / `ds_quote_element_bom` 47 …） | 0 是真的：报价单快照已全量走新表 |
| Z7 | `component.data_driver_path`（107 行）对 10 表 **0** | 抽样实际取值：`$builder_a2e1bb386be1` `$builder_b25b6fa6e0f0` `$test_pricebase_view` ⇒ 该列存的是 `$view` 引用不是表名，**pattern 天然打不中，属口径问题不是漏检** | 0 是真的，但**不代表无依赖** —— 依赖藏在 `component_sql_view.sql_template`（Z4） |
| Z8 | `semantic_node.physical_table` 对 10 表 **0** | 该列 57 行取值可枚举（已全量列出），其中 2 个是 `v_ds_cost_*_element_bom_all` | ⚠️ **0 但有间接依赖**，见 §6.4 |
| Z9 | `template_sql_view` / `datasource` / `global_variable_definition` 命中 **0** | `SELECT count(*)` 实测：**三张表各 0 行** | 0 的原因是**表为空**，不是 pattern 失效 |
| Z10 | `unit_price` 在配置层「作表名」**0** | 同 pattern 的「裸」口径命中 83 行，抽样确认全部是 `cep.unit_price` 输出列 | 0 是真的（T-1 消歧成功） |
| Z11 | `MaterialBomItemRepository.findByParent` 等 12 个方法 `codegraph_callers` **0** | 同一工具对 `upsertQuote` 返回 **4 个 caller**（全在 test）⇒ 工具能返回非空结果 | 0 是真的 |

---

## §8 迁移建议总览（B-4 / AC-9）

### 8.1 可迁 / 不可迁分类

| 老表 | 主要目标 | 可直接迁 | **不可迁（缺什么）** |
|---|---|---|---|
| `material_master` | `ds_quote_material` | 8 列一一对应 | `pending_quotation_id`（**无对应列**，2 行有值，6 处活写点）· `material_recipe_id`（**无对应列**，1 行有值，3 处活 UPDATE）· `standard_unit`/`usage_property`/`config_fingerprint`（**无对应列**，但 **0 行有值 ⇒ 可直接放弃**） |
| `material_bom_item` | `ds_quote_material_bom` | 11 列 | `operation_no`/`operation_seq`/`calc_type`/`characteristic`/`system_type`/`is_current`/`pending_*` + 20 余 SAP 属性列 |
| `element_bom_item` | `ds_quote_element_bom` | 7 列 | `characteristic`/`hf_part_no`/`system_type`/`is_current`/`pending_*` + 30 余 SAP 属性列 |
| `material_bom` | **无对应表** | — | 🔴 新体系取消 BOM 主表；`bom_type`/`characteristic`/`batch_qty`/`production_unit`/`valid_from`/`valid_to` 全部无处安放 |
| `element_bom` | **无对应表** | — | 🔴 同上（667 行，本批最大数据量） |
| `unit_price` | 7 张 `ds_quote_*_fee` | 9 列（需逐 `cost_type` 确认） | `price_type`/`cost_type`（由表名承载）· `market_ref_price`/`supplier_*`/`source_*`/`premium_fee`/`fetched_*`/`base_value`/`is_fluctuate_with_material`/`material_increase_*`/`life_*`/`recovery_discount`/`plating_scheme_no` 共 17 列 |
| `capacity` | `ds_cost_detail_capacity` + `ds_quote_assembly_fee` | 4 列 | 🔴 **模型不同**（新表核心列 `labor_std_price` 老表没有）；`fixed_lead_time`/`variable_time*`/`capacity_unit`/`resource_group_*`/`production_type`/`default_defect_rate`/`annual_discount_factor` 无对应 |
| `plating_scheme` | `ds_quote_plating_scheme` + `ds_cost_detail_plating_scheme` | 11 列 | `plating_method`/`surface_area`/`element_usage*`/`effective_date`/`expire_date`/`hf_part_no` |
| `annual_discount` | 按 `discount_type` 拆 3 张 `ds_quote_*_annual*` | 9 列 | `discount_type`（由表名承载）· `target_no`/`seq_no`/`system_type`/`is_current`/`pending_*` |
| `material_customer_map` | `ds_quote_customer_part` | 5 列 | 🔴 `base_currency`/`quote_currency`/`exchange_rate`/`payment_method`（**币种汇率付款方式在新表完全缺失**）· `seq_no`/`system_type`/`production_no`/`pending_quotation_id` |

### 8.2 三个**结构性**阻塞（不是列缺失，是机制缺失）

| 阻塞 | 涉及写点 | 说明 |
|---|---|---|
| 🔴 **B-A：pending 占号机制** | W5 W6 W7 W9~W12 W16 W17 W18 W19 W25 W26，以及所有 `pending_quotation_id` 列 | `ds_quote_*` 体系**只有 `source` / `source_quotation_id`，没有 `pending_quotation_id` + `pending_supersedes` 这套「草稿行 → 转正 / 回收」的两阶段语义**。整个 `PendingHygieneService` / `B8_PENDING_TABLES` / `QuoteBackfillService.flipPending` / `QuoteMaterialNoAllocator` 都建在这套语义上 |
| 🔴 **B-B：`is_current` 版本 flip 机制** | W28 W30（25 个调用点） | `VersionedV6Writer` 的核心动作是「旧版 flip `is_current=false` + 插新版」。`ds_quote_*` 用 `version_no` + `_history` / `_record` 表，**不是同一套模型** |
| 🔴 **B-C：兼容视图 ↔ 回填写回路径** | `QuotePendingRewriter.COMPAT_VIEW_TO_TABLE` | 读路径走 `v_compat_*`，写路径必须归一化回物理表（UNION 视图不可更新）。`v_compat_*` 一旦退役，`QuoteBackfillCollector:172` 的 `QuoteTableAxis.of(...) == null → continue` 守卫会让回填**静默降级成「不回填」**（代码注释已明确警告，守卫测试 `CompatViewBackfillGuardTest`） |

### 8.3 建议的退役排序（仅建议，不执行）

1. **先删死代码**（§4 的 D1~D17，17 处）—— 零风险，直接缩小引用面。
2. `plating_scheme`（12 行、无视图依赖、最后写入 2026-08-13）—— 🟢 最先。
3. `annual_discount`（13 行，但**先修 §10 的 R2 缺口**）。
4. `capacity` —— 需先确认 `labor_std_price` 的语义归属。
5. `material_bom` / `element_bom`（主表，需先确认主表字段无消费方）。
6. `unit_price` / `material_bom_item` / `element_bom_item` —— 卡在 B-B + B-C。
7. `material_customer_map` / `material_master` —— 卡在 B-A，且 `material_master` 还卡在 C5/C6 两个动态通道 + 2 个 `v_ds_cost_*_all` 视图。

---

## §9 会写共享 dev 库的测试（AC-14 归 S-2，本节只给后端侧证据指针）

后端侧实测到的**间接证据**（不是 AC-14 要求的清单本身）：

| 表 | `max(updated_at)` |
|---|---|
| `material_master` | **2026-09-09 09:44:15** |
| `material_customer_map` | **2026-09-09 09:44:15** |
| `unit_price` | **2026-09-09 09:44:06** |
| `capacity` | **2026-09-09 09:44:06** |
| `annual_discount` | 2026-09-08 01:10:53 |
| `material_bom_item` / `element_bom_item` / `material_bom` / `element_bom` | 2026-09-04 00:35:15 |
| `plating_scheme` | 2026-08-13 06:53:25 |

⇒ 与 `任务.md §①`「2026-09-09 09:43–09:44 共 18 行 `material_master` UPDATE」时间窗吻合，
且**范围比 `material_master` 一张表更广**（至少 4 张表在同一分钟被写）。
🚫 **本任务全程未执行 `mvnw test`**（会写共享库，属 §3.2「环境销毁」）。


---

## §9.5 面向用户的文案里硬编码了老表名（**非引用点 · 但属退役必改**）

> **主线亲验回流补录（2026-09-09）**：两处由主线点名、我复核行号属实，并顺该线索**扫出第 3 处**。

### 为什么单独成节

这些**不是功能引用点** —— 退役后代码不会报错、不会抛异常、测试不会红。
但**退役后系统会主动指导用户去用一张已经不存在的表**，或在报错时点名一张不存在的表。

分类上它介于「引用面」与「文档滞后」之间：
🚫 挂进 §3 写点表会误导（它不写库），🚫 完全不记又会在退役后变成线上误导文案。

### 第 1 类 · **祈使型**（主动指导用户去用这些表）—— 🔴 退役后必然误导

| 文件:行 | 触发场景 | 文案原文（节选） |
|---|---|---|
| `component/service/SqlViewValidator.java:106` | 组件 SQL 视图 dry-run 校验命中 **V44/V76 废弃表** 时返回的失败消息（错误码 `SQL_VIEW_DEPRECATED_TABLE`） | `"请改用对应 V6 表（material_master / material_bom_item / element_bom_item / fee_config / unit_price / plating_scheme 等）。"` |
| `template/util/BnfPathLinter.java:107` | 模板 BNF 路径 lint 命中废弃表前缀时给出的 `suggestion`（`PUBLISHED` 为 ERROR，`DRAFT` 降 WARN） | `"新建本模板 SQL 视图，SQL 使用对应 V6 表（material_bom_item / element_bom_item / fee_config / unit_price / plating_scheme 等），路径改为 $<view>.…"` |

🚨 **这两处的讽刺性**：它们的**存在目的**就是把用户从「上一代废弃表」（V44 `mat_*` / V76 `costing_part_*`）
引导到「当前正确的表」。一旦本批 10 张 V6 表退役，**这两条建议本身就变成新的 AP-53** ——
系统会拿着一份过期的推荐清单，把用户导向下一批不存在的表。
⇒ 退役时**必须同步改成 `ds_quote_*` / `ds_cost_*`**，否则等于在错误提示里埋了下一轮事故。

> 📌 注意：这两个文件里被**禁用**的 token 清单（`FORBIDDEN_TABLE_TOKENS` / `DEPRECATED_TABLE_PREFIXES`）
> 装的是 V44/V76 老表，**不是本批 10 张**。本批 10 张出现在**推荐侧**，不在禁用侧。

### 第 2 类 · **诊断型**（错误消息里点名表，用户可见）—— 🟡 退役后表述失真

| 文件:行 | 触发场景 | 文案原文 |
|---|---|---|
| `basicdata/v6/quote/Q05ElementRecoveryHandler.java:90` | 报价导入「来料回收折扣」逐行匹配失败时的**行级错误**，经 `result.recordError(...)` → `SheetImportResult.errors` 返回用户（`SheetImportResult.java:26-27`，同时 `failedRows++`） | `"未匹配 element_bom_item (material_no=%s, material_part_no=%s, component_no=%s) - 请先导入物料与元素BOM"` |

⇒ 用户看到的是一个**技术表名**；退役后该表名不再存在，提示会指向一个不存在的对象。建议顺带改为业务语义措辞。

### 第 3 类 · **日志型**（运维可见，非终端用户）—— 🟢 退役时顺手改，不改也只是日志失真

| 文件:行 | 类型 | 说明 |
|---|---|---|
| `quotation/service/QuotationService.java:2335` | `LOG.warnf` | `cleanupPendingV6Data: material_master pending 引用守卫拦下 %d 条…` |
| `quotation/service/PendingHygieneService.java:129` | `LOG.warnf` | `[BL-0092] material_master 仍有 %d 条孤儿 pending 被引用守卫拦下…` |
| `basicdata/v6/quote/QuoteImportService.java:288` | `Log.warnf` | `clearPreviousPending: material_master pending 引用守卫拦下 %d 条…` |
| `basicdata/v6/service/V6QuotationCommitService.java:92` | `Log.infof` | `V6 commit: hfPairs (来自 material_customer_map) = %d 个` |
| `configure/service/ConfigureProductService.java:1081` | `System.out.printf` | `[configure backfill] … backfilled %d unit_price rows`。⚠️ 顺带一提：这里用的是 `System.out` 不是日志框架 |

### 我扫过但**没有**命中的同类载体（列出以免下一个人重查）

| 载体 | 检索式 | 命中 |
|---|---|---|
| `@Schema` / OpenAPI `description` | `grep -rnaE "@Schema\|description[[:space:]]*="` ∩ 10 表名 | **0** |
| `src/main/resources` 下非迁移文件（i18n / 配置 / 文案） | `grep -rlaE "\b(10 表名)\b" resources` 减 `db/migration` | **0** |
| `throw new *Exception(...)` 同行含表名 | `grep -rnaE "throw new [A-Za-z]*Exception"` ∩ 10 表名 | **0** |

> **反向证明（AC-13）**：同一「祈使文案」检索式
> `grep -rnaE '"[^"]*(请|应改|改用|须|建议|不允许|禁用)[^"]*"'` ∩ 10 表名 → **命中 2 行**
> （`SqlViewValidator:106` / `Q05ElementRecoveryHandler:90`）⇒ 检索式有效，上表三个 0 可采信。
>
> ⚠️ **但 `BnfPathLinter:107` 未被该式捕获** —— 它的祈使词落在 `suggestion` 变量而不在同一字符串字面量内。
> **这本身就是「文案类检索必须多式并用」的实证**：只靠祈使词正则会漏掉三处里的一处（33%）。
> 本节的三处是「祈使词式 ∪ 主线点名 ∪ 日志/异常关键字式」三路并集的结果。

### 退役时的动作清单

| 优先级 | 处理 |
|---|---|
| 🔴 必改 | `SqlViewValidator.java:106` · `BnfPathLinter.java:107` —— 推荐表清单换成 `ds_quote_*` / `ds_cost_*` |
| 🟡 建议改 | `Q05ElementRecoveryHandler.java:90` —— 改业务语义措辞 |
| 🟢 可选 | 第 3 类 5 处日志文案 |


---

## §10 发现但未动的问题

| # | 问题 | 证据 | 建议 | 归属 |
|---|---|---|---|---|
| R1 | ⏳ **在途引用点**：`ExistingProductService.java:123`（读 `material_customer_map`）与 `:124`（`LEFT JOIN v_compat_material_master`） | §2.1 / §2.10 已标注 | 对方 B-1 合并后刷新这两行（AC-10） | `task-260909-已有产品抽屉数据源收敛` B-1 —— **本任务只记录未改动** |
| R2 | 🚨 **`annual_discount` 的 pending 行不会被回填清理** | `QuoteBackfillService.PENDING_TABLES:34-35` 与 `PendingHygieneService.PENDING_TABLES:57-58` **均不含 `annual_discount`**，只有 `QuotationService.B8_PENDING_TABLES:2324` 含它；实测 13 行里 **12 行 `pending_quotation_id IS NOT NULL`** | 三份清单应同源。`PendingHygieneService.inspect()` 的 `unmanagedTables` 告警（BL-0092）**恰好就是为发现这类漂移设计的**，但 `annual_discount` 因为在 `B8` 里、不在 `PendingHygiene` 里，是**反向漏项**，现有告警抓不到 | 未定；建议登记 BACKLOG |
| R3 | 🚨 **`costing_bom_tree_config` 有一行直连老表** | `82612f2b-558a-4b13-b052-af08100573ac` → `FROM material_bom_item`（另一行已走 `v_compat_*`） | V411 表名替换的漏项。退役前必须改，否则核价 BOM 树直接断链 | 未定；配置数据修正，非代码 |
| R4 | ⚠️ **AC-9 原文的一处事实性偏差** | AC-9 写「`ds_quote_material` 无 pending 概念（仅 `version_no`/`source`）」。实测 `ds_quote_material` 的列里**没有 `version_no`**，只有 `source`（另有 `category_code` / `customer_no`） | **结论不受影响**（`pending_quotation_id` 确实无对应列），但 AC 原文的括号内描述需更正 | 报主线裁决 |
| R5 | ⚠️ **`nameCol` / `DsMasterTables` 两个动态通道无任何守卫** | `DatasetMaintenanceService.buildNameJoins():551` 用 `SqlIdent.of(src.table())` 直接拼表名；`lookup():686` 同理。表名来自 Java 常量，**没有白名单校验、没有「表不存在」的编译期检查** | 退役 `material_master` 后这两处会在**运行期**才报错（`relation does not exist`），且只在用户点开对应下拉/名称列时才暴露。建议退役前先给 registry 加一个启动期表存在性自检 | 未定；建议登记 BACKLOG |
| R6 | ⚠️ **`MaterialBomRepository` / `UnitPriceRepository` 是整类死代码** | §4 D5 / D7 | 两个类各只有 1~2 个方法且全部 0 caller，可整类删 | 未定（本任务不改代码） |
| R7 | ℹ️ **hook 误拦只读 grep** | 本次审计中 `/usr/bin/grep -naE "DELETE FROM ..."` 被 `CLAUDE.md §3.2` hook 判为「无 WHERE 的 DELETE」拦截 —— 但那是**只读检索**不是 DML | 若后续审计类任务频繁撞到，可考虑 hook 区分「命令是 grep/rg」 | 报主线 |

---

## §11 自检声明

- **零写入自检**：本任务全程只执行 `SELECT` / `information_schema` / `pg_catalog` 查询与 `grep`，
  **未执行任何 INSERT / UPDATE / DELETE / CREATE / DROP / ALTER / TRUNCATE**，未运行 `mvnw test`。
- **N+1 自检**：**本次零代码改动**，无新增/修改的循环体 ⇒ **N+1 不适用**。
  零改动判据（**主线 2026-09-09 更正：`任务.md` AC-15 原文的 `git diff --stat master -- …` 在本仓不可用** ——
  本仓有十余个并发会话在推 master，那条命令会把别人的提交算进来）：
  ```bash
  MB=$(git merge-base master HEAD); git diff --stat "$MB"..HEAD -- cpq-backend/src cpq-frontend/src
  ```
  **实测（2026-09-09）**：
  ```
  merge-base = 25b106e297bfae26cc542df8105d80e7c478c567
  git diff --stat "$MB"..HEAD -- cpq-backend/src cpq-frontend/src   → （空输出）
  git status --porcelain -- cpq-backend/src cpq-frontend/src        → （空输出）
  ```
  ⇒ **本任务对 `cpq-backend/src` / `cpq-frontend/src` 零改动 ✅**（未提交项只有三份 `清单-*.md`）。
- **量具自检**：全程 `/usr/bin/grep -a`；裸 `grep` 仅用于 §0.1 对照实验，结果已记录（本次无差异）。
- **T-5 自检**（主线追加）：三条检索路径全部执行 —— ① 表名位正则 ② JPA/Panache 反查 ③ 字符串常量集合 + 消费点追踪。
  第 ③ 条查实 **6 份常量清单 / 8 个消费点**（§0.6），比转述的「5 处」多 3 处，其中 **W32 是新增写点**。
  每张表的「表名位命中数」旁均已标注是否受 T-5 影响（§1 汇总表新增两列 + §0.6 专表）。
- **T-5d 自检**（主线回流补录）：新增第 ④ 条检索路径 —— **从写入器方法反向枚举调用点**
  （`upsert` / `upsertBy*` / `upsertBatch*` / `flip*` / `delete*` / `batchUpdate` / `mintAndRegister` /
  `ensureRegistered` / `writeVersionedGroup(s)` / `writeVersionedMasterDetail(s)`，逐个跑 `.<方法>(`）。
  复核了主线点名的 4 处（W35~W38，行号我自己核的：P16:52 / P17:54 / P19:51 / P20:53），
  并**另捞出 3 处**（W39 W40 W41），其中 **W39 = 核价回填写入主路径**。
  写点 34 → **41**，其中 **13 处（32%）是纯字面量正则找不到的**。
  另经实体符号反查捞到 1 个读点（`ModelConfigService:304`）。
- 🚫 **本轮未使用任何规避 hook 的 pattern 改写**（上轮 R7 报备的做法已按指示停止）；本轮无 hook 拦截发生。
- **文案类自检**（主线亲验回流补录）：面向用户的文案 / 异常消息 / `suggestion`·`hint`·`message` 类字符串
  按**三路并集**扫描（祈使词正则 ∪ 日志·异常关键字正则 ∪ 主线点名），得 **3 处用户可见 + 5 处日志**，
  另有 3 类载体（`@Schema`/`resources`/`throw new *Exception` 同行）实测 **0 命中且已配反向证明** → §9.5。
  ⚠️ 其中 `BnfPathLinter:107` **单靠祈使词正则抓不到**（祈使词在变量不在字面量），
  已作为「文案类检索必须多式并用」的实证写入 §9.5。
- **行号基线自检**：`git merge-base master HEAD` = `25b106e2`；实测 `ConfigureProductService.java`
  worktree 2166 行 / master 2186 行（差 20），`W41 :1831`(worktree) ≡ `:1851`(master) 逐字节相同 →
  已在文首立**行号基线声明**，并说明 `P01:71` 缩写记法的等价展开。
- **调用链自检**：活死判定全部用 `codegraph_callers`（索引状态：2363 文件 / 50214 节点 / 107177 边），
  未用 grep 估；每个「死」判定均附实测 0 调用点。
