# 回报 · 测试 S-C · D-22 六个测试类按改后 AC 重写

> 分片 **S-C**，造数前缀 **`T260910C-`**。工作目录 `/home/joii/project/cpq/.claude/worktrees/task-260910-sel-ds-tables`，分支 `feat/task-260910-sel-ds-tables`。
> 🚫 未 `git commit`；🚫 未 `cd` 出 worktree；🚫 未执行任何 §3.2 红线操作。
> 📖 断言来源：**主仓** `dev-docs/task-260910-选配切ds新表与已有料号绑定/` 的 `需求文档.md §③` AC 原文 + 裁决 D-14 / D-19 / D-21 / D-22 + `test.md §3` 追溯矩阵。🚫 未读任何被点名禁止的实现目录。

---

## 0. 一句话结论

**D-14 的核心反转（带版本表直写主表）已实测达成、断言已全部反转并通过**；
但 **6 条依赖 `_record` 或卡片渲染的断言在 `@QuarkusTest` 层已不可验** —— 根因是**本片前一轮已登记的「限制A」在 D-14 之后第一次暴露到 `_record` 这一层**（详见 §6，**这是本轮最重要的发现，需主线裁定改验哪一层**）。

---

## 1. 改前 / 改后断言对照（逐类）

### 1.1 `RecordOnlyWriteAcTest` → **`MainTableDirectWriteAcTest`**（AC-10 · AC-13）

| # | 改前（方案②） | 改后 | 依据 |
|---|---|---|---|
| AC-10① | `assertEquals(0L, mainM)` —— `ds_quote_material_bom` 主表**零新增**（`:100`） | 主表**恰好 1 行**，且逐列断：`input_material_no='AgCu90'` · `output_material_type='RECIPE'` · `material_ratio='100.000000000000'` · `version_no=1` · `source='MANUAL'` · `customer_no`=本单客户 · `row_fingerprint` 非空 | 需求文档 AC-10①（D-14 反转） |
| AC-10② | `assertEquals(0L, mainE)` —— `ds_quote_element_bom` 主表**零新增**（`:103`） | 主表**恰好 2 行**（`Ag` 90 / `Cu` 10），每行 `version_no=1` · `source='MANUAL'` | AC-10② |
| AC-10③ | 免版本两表各 1 行（注释写「D-7：它们没有 `_record` 对应物，仍直写」） | 断言不变，注释改为「口径不变」；**位置前移**为「证明提交真的落库」的前置 | AC-10③ |
| AC-10④ | `_record` 有行 + `quotation_id` 反向核对 | **保留**，另加**投影前置闸门**（见 §6） | AC-10④ |
| AC-10⑤ | 无 | 🆕 `_record.source` 值域**只有 `'QUOTE_DRAFT'`**（D-18）+ 选配的 `version_no` 起始与导入侧 `min(version_no)` 一致（只读对照，实测导入侧 =1） | AC-10⑤ + D-18 |
| AC-13 | `assertTrue(recordAt < snapshotAt)` —— `ds-record` 日志**早于** snapshot 侧；两侧标记抓不全就**硬失败在「标记未知」** | **方向反转**：① `_record` 在**本次请求结束后**就有行；② 每行 `origin_id` **非空**，且 `JOIN ds_quote_material_bom ON id=origin_id` 命中行数 **== `_record` 行数**；日志时序降为**辅助**（两侧都抓到才判 `snapshotAt < recordAt`，抓不到就如实打印「未取得日志证据」） | AC-13（D-21 反转） |

> 🔑 **AC-13 为什么把判据从日志换成 `origin_id`**：旧版恒失败在「拿不到 `snapshotLines` 侧日志标记原文」（本片禁读 `com.cpq.configure.**`），一条业务结论都给不出。
> 而 AC 原文的第二个合取项（「本次请求结束后 `_record` 就有行、`origin_id` 非空」）**正是 D-21 A/B/A 实验的判别量**（B 阶段 `record_rows=0`）⇒ 现在它是硬断言，且新增的「`origin_id` 能 JOIN 上主表」是「投影**晚于**物化」的构造性证据（主表行不先存在，`origin_id` 无从认领）。**判据变强，不是变松。**
> 🚫 旧注释里「权威判据是 FT-5」已删 —— FT-5 随 D-14 在 `test.md §4` 整条作废。

### 1.2 `CardRenderFromRecordAcTest` → **`CardRenderFromMainTableAcTest`**（AC-11）

| # | 改前 | 改后 | 依据 |
|---|---|---|---|
| 前提 | `assertEquals(0L, mainM)` —— **主表必须 0 行**，「本条才在验只靠 `_record` 也能渲染」（`:73`） | `assertEquals(1L, mainM)` —— **主表必须有边**，这才是 AC-11 的达成路径 | AC-11「达成路径 = 主表有数据 ⇒ 树骨架能递归」 |
| 前提 | `assertNonEmpty(recM)` —— `_record` 必须有行 | **降为诊断打印** —— 改后 AC-11 原文**一个字都没提 `_record`**，`_record` 行数归 AC-10④ 判 | D-14 / D-8·D-9 作废 |
| ② | 只断「页签数据里能找到 100」 | 🆕 **树形两层**：页签文本须同时含**根层**（新铸料号）与**子层**（`AgCu90`）；再断占比 100 | AC-11②（新增「树形两层」） |
| ①③ | 两页签非空 / `Ag 90` · `Cu 10` | 不变（失败信息的归因改成「树骨架没递归出主表的边」，🚫 不再是「`_record` patch 合并没接上」） | AC-11①③ |

### 1.3 `RecordRatioAcTest` → **`MaterialRatioAcTest`**（AC-14）

| # | 改前 | 改后 | 依据 |
|---|---|---|---|
| 主断言 | `_record.material_ratio` = `70.000000000000` / `30.000000000000`，且**不许为 NULL** | 改断**主表** `ds_quote_material_bom.material_ratio` = `70.000000000000` / `30.000000000000`，且 🆕 **占比要落在对的材质上**（`AgCu90`→70、`00006`→30；只断「有 70 有 30」时两值互换也会绿） | `test.md §3` AC-14 关键断言（D-10 降级 + D-19 裁决后已更新为「改验主表侧」） |
| 附带 | `assertEquals(0L, mainM)`（方案② 不变量） | `assertEquals(2, mb.size())` —— 主表 2 行 | D-14 |
| `_record` | 断言 2 行 + ratio 非 NULL | 行数断言**保留**（2 行）；**ratio 改为诊断打印、不断言** | 见 §7 冲突 ① |

### 1.4 `RecordDedupAcTest`（AC-16）—— 类名保留，断言**一条未改**

复核结论：D-14 反转的是「主表写不写」，AC-16 断的是「`_record` 自身不许有同内容重复行」，**两者正交**，本类**没有任何「主表 0 行」类断言**。
只做了三处非断言改动：① 类注释加 D-22 复核说明（并指出 D-14 后 `_record` 的角色 = 回填数据来源 ⇒ **这条 AC 更重要而不是失效**）；② 把「选配提交本身已写 `_record`（D-7）」改成「建单末尾投影（D-14 口径）」；③ 零件品名带上本轮 `RUN_C`（防上一轮料号被指纹复用）。

### 1.5 `BindExistingMaterialAcTest`（AC-18 · AC-19）—— 类名保留，断言**一条未改**

复核结论：S-7 绑定路径按定义**一行 BOM 都不写**，AC-18④「主表与 `_record` 均零新增」在两种设计下都成立；且本类一律用 **before/after 差值**断，🚫 不是「绝对 0 行」—— D-14 后主表本来就可能有该料号的既有行。只加了一段 D-22 复核说明。

### 1.6 `Task260910CBase`（基座）

- 类注释整体改写：方案② 背景 → D-14 终态 + 用户原话 + 2734/18/8 的实测依据。
- `requireRecordLayer` / `ensureLineTemplate` 的 javadoc 去掉 D-7 / D-8 措辞。
- 🆕 `assertFreshlyMinted(res, ac)` —— **指纹复用假绿防线**（主线点名的第二类假读数）：提交返 200 但 `fingerprintMatched=true` 时一个新料号都没铸，此时查「主表有行」会被**存量行**骗过。AC-10/11/13/14 全部先过这一关，且取不到该字段时**硬失败**。
- 🆕 `freshPartName(tag)` —— 零件品名带 `RUN_C`，让指纹 `PART=` 段每轮唯一。
- 🆕 `requireCardDataMaterialized(fx, ac)` —— **投影前置闸门**（见 §6）。
- 🆕 `mainMbomRows` / `mainEbomRows` / `dump` —— 主表逐列读取（一律按 `material_no = 本片自铸料号` 收窄，🚫 无全局计数）。

---

## 2. 类名改动

| 旧名 | 新名 | 理由 |
|---|---|---|
| `RecordOnlyWriteAcTest` | **`MainTableDirectWriteAcTest`** | 旧名直译方案②「只写 `_record`」，与现行「直写主表」**正相反** |
| `CardRenderFromRecordAcTest` | **`CardRenderFromMainTableAcTest`** | 旧名说渲染数据源是 `_record`；D-14 后渲染**不再读 `_record`**，达成路径是主表的边 |
| `RecordRatioAcTest` | **`MaterialRatioAcTest`** | 旧名绑死 `_record` 侧；本条 AC 已改验主表侧，且不再限定某一张表 |
| `RecordDedupAcTest` | 不改 | 名副其实：仍在验 `_record` 去重 |
| `BindExistingMaterialAcTest` | 不改 | 名副其实 |
| `Task260910CBase` | 不改 | 中性名 |

三个新类的类注释首段都写明「原为方案② 而写，D-14 作废后按 D-22 改写」+ 裁决编号 + 旧断言原文，🚫 未留下与代码矛盾的名字或注释。

---

## 3. 实际执行结果（authoritative run = `run3`）

**归档**：`dev-docs/task-260910-选配切ds新表与已有料号绑定/证据/S-C-D22-重写后执行日志-run3.log`（55 KB 全量输出，🚫 不在会被下一轮清空的临时目录里）。

`Tests run: 9, Failures: 7, Errors: 0, Skipped: 0`

| 类 / 用例 | 结果 | 实际输出（节选原文） |
|---|---|---|
| **`MainTableDirectWriteAcTest.ac10…`** | ⛔ 止于 AC-10④ 闸门；**①②③ 全部通过** | `[AC-10③] ds_quote_material=1 ds_quote_customer_part=1`<br>`[AC-10①] … [1][AgCu90][RECIPE][100.000000000000][1][MANUAL][T2609bde037a7][690c88e5…f015c0]`<br>`[AC-10②] … [1][Ag][90.000000000000][1][MANUAL] / [2][Cu][10.000000000000][1][MANUAL]`<br>`[AC-10④ 投影前置] 报价行=1 行 / template_id=[(NULL)] / quotation_line_component_data=0 条` ⇒ 判【未验证 · 环境前置未满足】 |
| **`…ac13…`** | ⛔ 止于投影闸门；**前置通过** | `[AC-13 前置] 主表 ds_quote_material_bom = 1 行`<br>`[AC-13 投影前置] 报价行=1 行 / template_id=[(NULL)] / quotation_line_component_data=0 条` |
| **`MaterialRatioAcTest.ac14…`** | ⛔ 止于附带闸门；**AC-14① 主断言全部通过** | `[AC-14①] … [1][AgCu90][RECIPE][70.000000000000][1][MANUAL]…`<br>`… [2][00006][RECIPE][30.000000000000][1][MANUAL]…`<br>`[AC-14①] 主表落库 (input_material_no → material_ratio) = [AgCu90, 00006] → [70.000000000000, 30.000000000000]` |
| **`CardRenderFromMainTableAcTest.ac11…`** | ⛔ 【未验证】（限制A） | `[AC-11 前提] 主表 ds_quote_material_bom=1 行 / …_record=0 行`（主表前提 ✅）<br>`[AC-11 物化] 起点=0 / GET 详情后=0 / PUT draft(200)后=0 / POST ensure-card-values(200)后=0`<br>`[AC-11 夹具] template_id=a2228dae-… 卡片组件数据=0 条` |
| **`RecordDedupAcTest`（2 条）** | ⛔ 止于投影闸门 | `[AC-16 前置 投影前置] 报价行=2 行 / template_id=[(NULL),(NULL)] / compData=0 条`（**夹具本身成立**：同料号 2 个 line item 造出来了）<br>`[AC-16 二次触发前置 投影前置] 报价行=1 行 / … compData=0 条` |
| **`BindExistingMaterialAcTest.ac18_bindWritesExactlyTwoRows`** | ✅ **PASS** | `[AC-18 基线] customer_part=0 material=1 mbom=2 ebom=2 mbom_rec=0 ebom_rec=0 signature=0 line_item=0`<br>`[AC-18] 响应 fingerprintMatched=false reusedHfPartNos=[] productType=SIMPLE`<br>`[AC-18②] 本单报价行料号 = [T260910C-BINDf07e0]`<br>`[AC-18①] 新增 customer_part 行 = [T26093411141a][T260910C-BIND-01-f07e0][T260910C-BINDf07e0][MANUAL]` |
| **`…ac19_duplicateProductNoRejected`** | ✅ **PASS** | `[AC-19] 第 2 次同编号提交 status=409 body={"code":409,"message":"该编号已存在，请从产品库添加","data":{"code":"CUSTOMER_PRODUCT_NO_TAKEN"},…}`<br>`[AC-19] 解析出的业务码 = CUSTOMER_PRODUCT_NO_TAKEN`<br>`[AC-19] 该编号在 ds_quote_customer_part 的行数 = 1` |
| **`…ac18_6_cardRendersExistingBom`** | ⛔ 【未验证】（同限制A） | `[AC-18⑥] 生效 template_id=a2228dae-…`；`[AC-18⑥] 本单卡片组件数据 0 条，页签总行数 0` |

**编译**：`./mvnw -o -q test-compile` → `COMPILE_EXIT=0`，6 个 `.class` 全部产出（`BindExistingMaterialAcTest / CardRenderFromMainTableAcTest / MainTableDirectWriteAcTest / MaterialRatioAcTest / RecordDedupAcTest / Task260910CBase`）。

---

## 4. 构建产物隔离方式 + 有没有撞到假故障

**用的是第 ② 种：隔离副本。**
```
rsync -a --delete --exclude 'target/' \
  <worktree>/cpq-backend/  /tmp/claude-1000/<session>/scratchpad/iso-sc/cpq-backend/
# 所有 mvnw 命令都在 iso-sc/cpq-backend 下跑，target/ 完全私有
```
- 每次改完 worktree 里的测试源码 → 重新 `rsync` → 在 iso-sc 里跑。
- **没有撞到任何 target 冲突的假故障**：4 次 `mvnw` 调用全程 0 次 `Tests run: 0` / 0 次 `bad class file` / 0 次 `NoSuchFileException` / 0 次 `cannot find symbol`。
- 采样时刻 **17:38 本地**：`ps -ef | grep -a '[m]aven'` 只有主仓 `cpq-backend` 的 `quarkus:dev`（8081，PID 19938/20503），worktree 内无 maven 进程 —— 但隔离照做（该采样是瞬时量，另一路后端随时可能起）。
- 🚫 未跑任何宽匹配杀进程命令；🚫 未占 8081 / 5174（测试用 `@QuarkusTest` 的随机测试端口）。

---

## 5. 实确认的连库结果

**连的是 `cpq_db_test`**，两路独立证据：

1. **运行时日志原文**（`run1.log:80/81`，不是读配置文件）：
   ```
   DEBUG [io.qua.agr.run.DataSources] Started datasource <default> connected to
     jdbc:postgresql://10.177.152.12:5432/cpq_db_test?sslmode=disable
   INFO  [org.fly.cor.FlywayExecutor] Database: jdbc:postgresql://10.177.152.12:5432/cpq_db_test (PostgreSQL 16.13)
   ```
2. **`psql \conninfo`**：`Database | cpq_db_test`，`Host | 10.177.152.12`，`Backend PID 2849474`。

📌 **两库基线差异已作为归因项检查过**（主线点名的必查项）：`T260907-物料BOM` / `T260907-物料与元素BOM` 两个组件在 `cpq_db_test` 与 `cpq_db_0724` **都存在、`component_sql_view` 都各 1 段** ⇒ 限制A **不能**简单归因成「测试库缺视图」。`_record.source` 值域两库一致（`cpq_db_test` 实测 `QUOTE_DRAFT` 9567 行、只此一种，坐实 D-18）。主表 source 分布实测 `IMPORT 2734 / MANUAL 7 / QUOTE_BACKFILL 7`，与 D-14 的裁决依据同型。

---

## 6. 🚨 本轮最重要的发现：限制A 在 D-14 之后暴露到 `_record` 层（需主线裁定）

**现象**：本片 6 条断言（AC-10④⑤ / AC-13 / AC-14 附带 / AC-16 ×2 / AC-11 / AC-18⑥）全部卡在同一处 —— 本单 `quotation_line_component_data = 0 条`。

**证据链（全部来自运行日志原文，非推断）**：
1. `[ds-record] quotation=… 命中 1 个轴值但无组件数据，跳过` —— 投影**跑了**，但被空 `compData` 早退。
2. `[cardvalues-sentinel] quote build 失败 line=… → 落失败哨兵` —— 卡片值构建**抛异常被吞**，于是落失败哨兵、不产出组件数据。
3. 三条服务端物化入口 `GET /quotations/{id}` · `PUT /{id}/draft` · `POST /{id}/ensure-card-values` **全返 200**，`compData` 始终 0 条。
4. 已排除两个混淆项：① `template_id` 已由 `ensureLineTemplate` 补成含两个目标页签的 PUBLISHED 模板 `a2228dae-…`（BL-0202 排除）；② 挂了产品分类的夹具（`referenceCategoryId()`）与没挂的**表现完全相同**，我为此把本片**全部**夹具都改成挂分类，仍是 0 条。
5. 开到 `TRACE`（`com.cpq.quotation.service` / `com.cpq.component`）也**没有堆栈** —— 异常在实现里被 catch 吞掉，测试层看不到根因。

**为什么这是 D-14 之后才暴露的**：方案② 下 `SelDsQuoteWriter` **直写 `_record`**（前一轮日志：`[ds-record][direct] … 整组覆盖 —— 删 0 行、写 1 行`），与 `compData` 无关 ⇒ 那时 `_record` 必有行，限制A 只影响卡片渲染那两条。
D-14 回退掉直写路径后，**`_record` 只剩「建单末尾投影」这一条来源**，于是限制A 一次性吃掉了 6 条断言。

**我做了什么（🚫 没有改松任何断言）**：新增 `requireCardDataMaterialized()` **硬失败**闸门，把这类失败从「AC 失败 / 产品缺陷」里**分离出来**，失败信息明确写 **「⛔【未验证 · 环境前置未满足】—— 不是「通过」，也不是被测功能的结论」** + 完整归因线索。刻意**不 skip**（skip 在 surefire 汇总里长得和通过一样）。

**需要主线裁定的两件事**：
1. **`[cardvalues-sentinel] quote build 失败` 的根因是什么** —— 它既可能是隔离夹具缺某个前置（价格策略 / 模板绑定 / 数据源），也可能是**真实缺陷**。定位它要读 `CardSnapshotService`，那是后端的活，🚫 我不越界，也 🚫 不据此改断言。
2. **这 6 条改到哪一层验**：
   - **AC-13** 的 live 证据后端已在 **D-21 做过 A/B/A 还原实验（干预已证生效）** —— 可否直接采信为 AC-13 的达成证据？
   - **AC-11 / AC-18⑥** 的字面观测点本来就是浏览器，权威判据是 `cpq-frontend/e2e/t260910c-sc.spec.ts`（本片未跑 E2E，不在本轮派工范围）。
   - **AC-10④⑤ / AC-14 附带 / AC-16** 需要一个能真正物化卡片的环境（dev server + `cpq_db_0724`，属主线亲验范畴）。

---

## 7. 「AC 在当前实现下过不了 / AC 本身不可执行」—— 逐条上报，🚫 未改松断言

### ① 🔴 `需求文档.md` AC-14① 原文 与 D-19 裁决**直接矛盾**（要求文档回写）

- **AC-14① 原文**：「`ds_quote_material_bom_record` 出现 2 行，`material_ratio` 分别为 `70.000000000000` 与 `30.000000000000`（🚫 **不许为 NULL** —— 当前实测该列填充率仅 12.2%）」
- **D-19 裁决**：「`_record.material_ratio` 为空：**接受现状，不改 `syncRecords`**（用户选「甲」）」；D-10 把补投影降为优化项，**B-16 本期不做、登记 BACKLOG**。
- ⇒ 按 AC 原文断**必红，而红的原因是用户已明确接受的现状**，不是实现缺陷。
- **我的处理**：按 `test.md §3` 矩阵的更新口径（「本条改验**主表侧**」）断主表，把 `_record.material_ratio` 的实际值**打印成诊断**（🚫 不断言、🚫 不隐瞒），并在类注释里写明冲突。
- 👉 **请主线裁定**：把 `需求文档.md` AC-14① 回写成主表口径，还是另有安排。

### ② 🟡 `需求文档.md` AC-14 的「填充率 12.2%」与两库实查都不符（沿用前一轮已报的口径偏差）

`cpq_db_test` 实测 `ds_quote_material_bom_record.material_ratio` 非空率远低于 12.2%，`ds_quote_material_bom`（主表）则接近 97%。对结论无影响（目标行改动前确实为 NULL），仅登记。

### ③ 🟡 AC-16② 的「同上」字面不可执行（前一轮已报，本轮复核仍成立）

AC-16② 写「`ds_quote_element_bom_record` **同上**」，但只读查证 `information_schema.columns`：该表**没有 `input_material_no` 列**（粒度列是 `element_code`，另有 `material_part_no`）。基座 `grainColumn()` 按该表**实际行粒度**取 `element_code`，已在代码注释与本节双重登记，🚫 不沉默解释成别的意思。

### ④ 🟡 AC-10⑤「与导入建单逐字同型」本片只做了只读值域对照

完整的 FT-6（真跑一次**导入建单**、逐列 diff）写入面覆盖十几张 `ds_quote_*` + `_record` + `quotation_line_*`，按 `test.md §1` 属 **S-全局 片**（与 AC-15 / AC-23 同）。本片只做了 `_record.source` 值域 + `version_no` 起始两项只读对照，**🚫 不声称做过逐列 diff**。

---

## 8. 待回收清单（🚫 我没有删，交主线）

**本轮我自己的造数：零残留**（实测，采样 17:47 本地 / 00:47 UTC）：
```
ds_quote_material_bom   source='TEST' AND material_no LIKE 'T260910C%'  → 0
ds_quote_material       material_no LIKE 'T260910C%'                    → 0
本轮新铸料号（10[45]x-2609*）在 ds_quote_material / _material_bom / _record → 0 / 0 / 0
customer code LIKE 'T2609%' AND created_at::date = current_date(UTC)     → 0
```
基座的 `@AfterEach` + 父类 `restoreFixtures` 每条用例都打印了清理行数，日志里可逐条复核。

**但库里有 2 行是「前一轮 S-C」的残留（🚫 不是本轮的，也 🚫 不是我该删的）**：

| 表 | 行 | 时间 |
|---|---|---|
| `ds_quote_customer_part` | `T260954a4a04a / T260910C-AC16C-f48c7 / 1029-2609000001 / MANUAL` | 2026-09-10 12:15 UTC |
| `ds_quote_customer_part` | `T2609633af33e / T260910C-AC16C-d9069 / 1034-2609000001 / MANUAL` | 2026-09-10 12:16 UTC |
| `customer` | `T260954a4a04a` / `T2609633af33e`（name = `T260902-客户-C-ac16b`） | 同上 |

📌 形态与前一轮回报登记的**「submit 产生 `costing_order` ⇒ 父类 `DELETE FROM quotation` 撞 FK ⇒ 整段清理事务回滚」**完全吻合（两行都来自 `C-ac16b` 那条会 submit 的用例）。
基座已有的 `clearSubmitArtifacts()` 正是为此而加，本轮**没有再产生同型残留**。
🚦 这几行要不要清、怎么清，**交主线按 `CLAUDE.md §3.2` 三步前置裁定**，🚫 我没有执行任何 DELETE。

**另有一批与本任务无关的基线数据，仅登记不动**：`ds_quote_element_bom` 有 19 行 `source='TEST'`，全部属 `T260907R-*` 料号（task260907r 的夹具），🚫 未碰。

---

## 9. 自检声明

- 6 个类**全部编译通过**（`mvnw -o -q test-compile` → exit 0，6 个 `.class` 均产出）✅
- 实际跑过 4 轮，authoritative run（run3）结果已逐条贴原文，🚫 无「✅ 通过」式空结论 ✅
- 连库实证 = `cpq_db_test`（运行时 datasource 日志 + `psql \conninfo` 双证据）✅
- 构建产物隔离 = scratchpad 隔离副本；0 次 target 冲突假故障 ✅
- 指纹复用假绿：4 条新铸类用例全部先断 `fingerprintMatched=false`，实测响应 `fingerprintMatched:false, reusedHfPartNos:[]` ✅
- 分片纪律：全部断言按 `material_no=本片自铸料号` 或 `quotation_id=本片自造单` 收窄，**无一条全局计数断言**；🚫 未读未改别片数据；🚫 未用 `S0011` 组装加工费那类会被基线污染的形态 ✅
- 🚫 未执行 `git commit`；🚫 未 `cd` 出 worktree；🚫 未跑任何 §3.2 红线操作；🚫 未跑宽匹配杀进程；🚫 未占 8081 / 5174 ✅
- 🚫 **未为让测试变绿放宽任何一条断言** —— 过不了的一律以「未验证」或「已上报的文档冲突」的名义**硬失败 + 如实登记** ✅
