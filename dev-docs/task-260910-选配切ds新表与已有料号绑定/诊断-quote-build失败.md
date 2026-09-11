# 诊断 · 「`quote build 失败` ⇒ `quotation_line_component_data` 0 条」

> 诊断人：后端（backend-engineer）｜日期 2026-09-10｜🚫 只诊断，未改任何实现文件、未 `git commit`
> 工作区：`/home/joii/project/cpq/.claude/worktrees/task-260910-sel-ds-tables`（worktree 里**一次 maven 都没跑**）
> 构建隔离：整份源码 `rsync --exclude target/` 到 scratchpad 副本 `…/scratchpad/iso-diag/cpq-backend`，4 次 maven 全部在副本里跑
> —— 0 次 `Tests run: 0` / 0 次 `bad class file` / 0 次 `cannot find symbol`，**没有任何读数需要因 target 冲突作废**。
> （主线两次更正已确认 `-Dmaven.build.dir=` 在本项目不生效，本次**从未使用**该参数。）
> 证据原始日志：`证据/诊断-quote-build失败/01~04*.log` + 两个诊断用例源码

---

## 1. 结论一句话

**测试库配置缺失 —— 更准确地说是「夹具前置缺失」，不是测试库、也不是本次任务引入的真 bug。**

**判据（单一变量，A/B 已证干预生效）**：
`quotation.customer_template_id` 为 `NULL` 时，`ds_quote_*` 主表照常写入，但
① `ConfigureSnapshotService.snapshotLines` 在 `loadDriverComponents` 返 0 个组件处**静默 `return`（零日志、零异常）** ⇒ `quotation_line_component_data` 恒 0 条；
② `CardSnapshotService.buildCardValues` 第一行守卫 `templateId == null` **直接 `return null`（未抛异常）** ⇒ 落失败哨兵、打 `[cardvalues-sentinel] quote build 失败`。
`S-C` 的夹具（`task260902/SelConfigAcTestBase#newFixture`）的 `INSERT INTO quotation` **从来没有写 `customer_template_id`**。

**两库同样失败、两库同样修好** ⇒ 库不是变量（见 §3 Q2）。

⚠️ 但附带挖出**两个真实缺陷候选**，需主线裁决（§7）：
- **P1**：失败哨兵**粘死** —— 哨兵一旦落库，`ensureCardValues` 的 `IS NULL` 谓词永不再选中该行；用户事后补绑模板也**不会自愈**（A/B 的 B 阶段实测：compData 已从 0 变 14 条，`quote_card_values` 仍是 `{"tabs": [], "__cardValueFailed": true}`）。而 `customerTemplateId` 在建单接口里是**可选字段**（`CreateQuotationRequest.java:32` 注释原文：「留空则后续在报价单 Step2 中由用户手工选择」）⇒ 「先选配加产品、后选模板」这个顺序在产品上是允许的。
- **P2**：可观测性空洞 —— 上述①处是**整条链路唯一的岔路口，却一个字都不打**（`ConfigureSnapshotService.java:288-289`）。`CreateQuotationMaterializer#checkMaterializeOutcome` 那道三元判据只覆盖导入/建单链路，且它**刻意**把「driver 组件 0 个」判为合法 ⇒ 选配链路上这个故障形态**没有任何信号**。

---

## 2. 🔴 最关键的产出：**被吞的异常不存在**

`[cardvalues-sentinel] quote build 失败` **不是**异常被 catch 吞掉，它是**守卫子句正常返回 null**。
这就是「开到 TRACE 也没有堆栈」的原因 —— 没有堆栈，因为没有异常。

### 2.1 只读证据（S-C 自己的 run3 全量日志，55 KB）

`证据/S-C-D22-重写后执行日志-run3.log` 逐项计数：

| 日志标记 | 出处 | 次数 |
|---|---|---|
| `[cardvalues-sentinel] quote build 失败` | `CardSnapshotService.java:741` | **2** |
| `[card-snapshot] safeCall降级` | `CardSnapshotService.java:5050`（**唯一的吞异常点**） | **0** |
| `[card-snapshot] buildCardValues failed` | `CardSnapshotService.java:2511`（`buildCardValues` 自己的 catch） | **0** |
| `[add-snapshot] … 整单重快照失败(已降级)` | `ConfigureSnapshotService.java:163` | **0** |
| `ERROR [` 级任意行 | —— | **0** |

⇒ 三个「如果真抛了异常就一定会打」的点**全部 0 次**。

### 2.2 临时插桩证据（A/B 实验同一条用例，插桩后重跑）

在**隔离副本**里临时加了两处（已还原，见 §6）：
- `buildCardValues` 守卫子句处 `LOG.warnf(new RuntimeException("DIAG-STACK"), …)` 打逐条件 + 栈
- `safeCall` 的 catch 里加 `LOG.errorf(e, "[DIAG-SWALLOW] … class=%s", e.getClass().getName())`

实跑结果（`证据/…/04-临时插桩-守卫子句vs吞异常.log`）：

```
DIAG-SWALLOW 出现次数 = 0
DIAG-GUARD   出现次数 = 2
```

```
:105 WARN [ConfigureSnapshotService] [DIAG-SNAPLINES] quotation=2af0d215-… customerTemplateId=null driverComps=0 lines=1
:106 WARN [ConfigureSnapshotService] [DIAG-SNAPLINES] quotation=2af0d215-… driver 组件 0 个 ⇒ **静默 return，零日志、零异常**，本单不会产出任何 quotation_line_component_data
:216 WARN [CardSnapshotService]      [DIAG-GUARD] buildCardValues 守卫子句返回 null（**未抛异常**）: li=387481d8-… liNull=false idNull=false templateIdNull=true: java.lang.RuntimeException: DIAG-STACK
	at com.cpq.quotation.service.CardSnapshotService.buildCardValues(CardSnapshotService.java:2427)
	at com.cpq.quotation.service.CardSnapshotService.lambda$snapshotNewLinesCardValuesCore$0(CardSnapshotService.java:700)
	at com.cpq.quotation.service.CardSnapshotService.safeCall(CardSnapshotService.java:5053)
	at com.cpq.quotation.service.CardSnapshotService.snapshotNewLinesCardValuesCore(CardSnapshotService.java:700)
	at com.cpq.quotation.service.CardSnapshotService.snapshotNewLinesCardValuesBatch(CardSnapshotService.java:674)
	…
	at com.cpq.quotation.service.CardSnapshotService.ensureCardValuesDetailed(CardSnapshotService.java:1699)
:309 WARN [CardSnapshotService]      [cardvalues-sentinel] quote build 失败 line=387481d8-… → 落失败哨兵
```

`templateIdNull=true`，`liNull=false`，`idNull=false` ⇒ **命中的就是 `templateId == null` 这一条**，
而 `DIAG-SWALLOW` 一次都没打 ⇒ `safeCall` 的 catch **从头到尾没进过**。

### 2.3 代码原文（两个静默点）

`cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java:2426`
```java
if (li == null || li.id == null || templateId == null) return null;
```
调用处 `:700` 传的是 `q.customerTemplateId`（**报价单级**，不是 `quotation_line_item.template_id`）。

`cpq-backend/src/main/java/com/cpq/configure/service/ConfigureSnapshotService.java:288-289`
```java
List<DriverComp> comps = self.loadDriverComponents(quotationId);
if (comps.isEmpty()) return;          // ← 零日志、零异常
```
`loadDriverComponents`（同文件 `:1051-1055`）：
```java
List<Object[]> qRows = em.createNativeQuery(
        "SELECT customer_template_id, (SELECT status FROM template WHERE id = q.customer_template_id) "
                + "FROM quotation q WHERE q.id = :q") …
if (qRows.isEmpty() || qRows.get(0) == null || qRows.get(0)[0] == null) return List.of();
```

### 2.4 ⚖️ 对 S-A（O-2「属预期」）与 S-C（「build 失败」）分歧的裁定

**两片都只说对一半，但 S-A 的机制解释是对的。**

| | 说法 | 裁定 |
|---|---|---|
| **S-A** | 裸 API 夹具压根不产生 compData ⇒ 投影跳过属预期 | ✅ **机制正确**。但它没解释为什么会「压根不产生」—— 真正的开关是 `quotation.customer_template_id`，不是「裸 API」这个笼统说法（见 §3 Q5：裸 API 夹具 **先绑模板再 configure**，一样能产出 14 条 compData + `_record` 1/2 行） |
| **S-C** | 日志有 `quote build 失败 → 落失败哨兵` ⇒ 有异常被吞 | ⚠️ **日志真实存在**（run3 第 108/169 行，主线要的核验：**有**），但「有异常被吞」这一步**推断错了** —— 是守卫子句返 null，不是 catch 吞异常 |

⇒ 主线给的两条分支里，**第二条成立**：「这 6 条断言是夹具选型/前置问题，应换验证路径或补夹具前置，而不是修 bug」。
🚫 但**不能**照 S-A 的口径结案成「裸 API 验不了」—— 夹具补一行就能验（§5）。

---

## 3. Q1~Q4 逐条回答

### Q1 · 被吞的异常是什么？

**不存在被吞的异常。** 完整证据见 §2（只读日志计数 + 临时插桩 + 代码原文 + 调用栈）。
`[cardvalues-sentinel]` 的触发条件是 `quoteVals.get(li.id) == null`（`CardSnapshotService.java:740`），而 `buildCardValues` 有**两条**返 null 的路径：守卫子句（无异常）与 catch（有异常且必打日志）。本次命中的是**前者**。

### Q2 · 在 `cpq_db_0724` 上能复现吗？—— **能，逐字同型**

同一份 payload、同一个模板、同一个诊断用例，只换 `DB_NAME`：

| | `cpq_db_test`（`01-*.log`） | `cpq_db_0724`（`02-*.log`，`DB_NAME=cpq_db_0724`） |
|---|---|---|
| 连库实证 | `Database: jdbc:postgresql://10.177.152.12:5432/cpq_db_test` | `Database: jdbc:postgresql://10.177.152.12:5432/cpq_db_0724` |
| 提交 | `status=200`，新铸 `1077-2609000001` | `status=200`，新铸 `1004-2609000001` |
| `quotation.customer_template_id` | `(NULL)` | `(NULL)` |
| 主表 `ds_quote_material_bom` | **1 行** | **1 行** |
| A 阶段 compData（GET/PUT/POST 三入口全 200） | **0 / 0 / 0** | **0 / 0 / 0** |
| 干预（只 UPDATE 本单 `customer_template_id=a2228dae…`） | 自检回读 = `a2228dae…` | 自检回读 = `a2228dae…` |
| B 阶段 compData | **14 条** | **14 条** |

⇒ **库不是变量。** 「测试库 0 条 / dev 库有数据」这个表面矛盾的真实成因是**报价单的来路不同**：
- UI 向导建单 → 前端调 `match-customer-quote` 拿到 `customerTemplateId` 再随 `POST /quotations` 传入 → `QuotationService.create:319-322` 落库 ⇒ 主线亲验的单都有模板；
- 裸 SQL 夹具建单 → 从不写该列。

库级只读相关性（两库同型，进一步坐实）：
```sql
SELECT q.customer_template_id IS NULL AS tpl_null, count(DISTINCT q.id), count(cd.id)
FROM quotation q JOIN quotation_line_item li ON li.quotation_id=q.id
LEFT JOIN quotation_line_component_data cd ON cd.line_item_id=li.id GROUP BY 1;
-- cpq_db_test : f|90|53426    t|11|2
-- cpq_db_0724 : f|109|53762   t|9|2
```

### Q3 · 是哪项配置差异？—— **不是库间配置差异；S-D 的「缺模板」线索是另一个缺陷**

主线转来的 S-D 线索（`cpq_db_test` 没有 `45cc0267…（正泰测试模板1）`）**经实查为真，但与 S-C 无关**：

```sql
SELECT id, name, status, (SELECT count(*) FROM template_component tc WHERE tc.template_id=t.id)
FROM template t WHERE t.id='a2228dae-…' OR t.id::text LIKE '45cc0267%';
-- cpq_db_test : a2228dae-7a54-4921-a66f-da4eed41a6c1 | 报价模板 · ds 原生 v1.0 | PUBLISHED | 14
-- cpq_db_0724 : a2228dae-7a54-4921-a66f-da4eed41a6c1 | 报价模板 · ds 原生 v1.0 | PUBLISHED | 14
--               45cc0267-a340-43ba-80e1-3de17af47443 | 正泰测试模板1        | PUBLISHED |  6
```

⇒ ① `45cc0267` 确实只在 `0724`（S-D 的 AC-24 受它影响，属**另一条**问题）；
② S-C 的 `ensureLineTemplate` 选中的是 **`a2228dae`**（`03-*.log:101` 与 `01/02-*.log:126-127` 逐字打印），它**两库都在、都是 PUBLISHED、都挂 14 个组件**，且在两库都能物化出 14 条 compData ⇒ **「测试库缺模板」解释不了 S-C 的 0 条**。
③ 至于「为什么日志不是更直白的『模板不存在』」：因为压根没走到查模板那一步 —— `templateId` 本身就是 `null`，守卫子句在第一行就返回了（§2.3）。

**S-C 那道 🩹 补丁补错了列**（重要）：`Task260910CBase#ensureLineTemplate:346-348` 打的是
`UPDATE quotation_line_item SET template_id=…`，而卡片物化链路读的是 `quotation.customer_template_id`。
实测（`03-*.log:257`）：**`quotation_line_item.template_id` 保持 `(NULL)` 的同时，compData 照样 14 条** ⇒
**BL-0202（选配行 `template_id` 恒 NULL）与本现象无因果关系**，S-C 把它当混淆项排除是对的，但排除动作没命中真正的变量。

其余对比维度（主线点名要看的）一并只读核过，**均不构成本现象的成因**：`component_sql_view` 42 vs 99 段、`template_component` 78 vs 146 行、`v_compat_*` 0 vs 3 张 —— 因为 `a2228dae` 这个模板及其 14 个组件在两库都完整，且两库跑出**同样的 0 条 / 同样的 14 条**。

### Q4 · 是本次任务引入的吗？—— **不是**

| 证据 | 原文 |
|---|---|
| 根因涉及的两个实现文件**本次任务一行未改** | `git status --porcelain \| grep 'CardSnapshotService\|ConfigureSnapshotService'` → **空**；`git diff --stat` 对这两个文件 → **空** |
| `buildCardValues` 的 `templateId == null` 守卫 | `TZ=UTC git log -S… --date=iso-local` → `b235926b 2026-06-01 07:43:00 +0000` |
| `loadDriverComponents` 以 `quotation.customer_template_id` 为唯一入口 | 现行判据 `a89e099c 2026-08-07 13:04:06 +0000`；**更早的版本同样以它为 JOIN 键**（`git show a89e099c^:…` 原文 `JOIN template_component tc ON tc.template_id = q.customer_template_id`）⇒ 该依赖至少存在到 2026-08-07 之前 |
| 夹具 `SelConfigAcTestBase#newFixture`（不写 `customer_template_id`） | `dcbcaba6 2026-09-03 03:28:47 +0000  test(task-260902): …` |
| 既有同型登记 | `task260907r/SelectionChainRegressionAcTest.java:417-441` 已有「三跳定位」并打印 `quotation.customer_template_id` / `line_item.template_id`，注释里写着「与 ds 原生模板逐项对照（后者能物化出 **14 行**）」—— 与本次实测的 14 条逐字吻合 |

**S-C 关于「D-14 之后才暴露」的推测成立，但要改一个字**：D-14 撤掉 `DsRecordDirectWriter`（已实查：`quotation/service/dsrecord/` 下已无该文件）后，`_record` 只剩投影一条来源，于是**这个早就存在的前置缺失第一次吃掉了 6 条断言**。
⇒ 它是**被暴露**，不是**被引入**。

### Q5 · （追加）夹具补上前置之后是什么样？

`DiagPreBoundTemplateTest`：**在 configure 之前**就把本单 `customer_template_id` 绑成 `a2228dae…`，然后只调一次选配提交、**不再调任何物化入口**（`03-*.log:254-267`）：

```
[DIAG2 前置] quotation=ac9375ff-… customer_template_id=a2228dae-7a54-4921-a66f-da4eed41a6c1
[DIAG2] 提交 status=200      [DIAG2] 新铸料号=1081-2609000001
   · quotation_line_item.template_id = [(NULL)]          ← BL-0202 仍在，但无影响
   · quotation_line_component_data = 14 条
   · 主表 ds_quote_material_bom = 1 行
   · _record mbom / ebom = 1 / 2
   · quote_card_values = [{"tabs": [{"tabName": "来料固定加工费", "baseRows": [], … }]}]   ← 真值，不是哨兵
   · 页签 T260907-物料 → 1 行 / T260907-物料BOM → 2 行 / T260907-物料与元素BOM → 2 行 / T260907-自制加工费 → 1 行
   · _record mbom 明细 = [AgCu90 100.000000000000 16837]
   · _record ebom 明细 = [Ag AgCu90 18210] [Cu AgCu90 18211]
```

---

## 4. 「真 bug」还是「配置缺失」—— 为什么我判后者（含反向自检）

主线点名要求「两种可能都要认真对待」，逐条过：

| 假设 | 证伪/证实 |
|---|---|
| ① 测试库缺某项配置 | **部分成立但不是主因**：`45cc0267` 确实只在 0724（影响 S-D），但 S-C 用的 `a2228dae` 两库都在；换库不改变结果（Q2） |
| ② 真 bug，被 dev 库存量掩盖 | **不成立于「compData 0 条」这一现象**：0724 用同一夹具同样 0 条 ⇒ 掩盖它的不是「dev 库存量」，是「UI 建单会填 `customer_template_id`」 |
| ③ 夹具前置缺失 | ✅ **成立**：A/B 单一变量、干预自检回读、两库同型、`configure` 前绑好即全通 |

🚫 我**没有**用「测试环境问题所以忽略」结案 —— 相反，本次从这条线上挖出了 §7 的两个产品侧缺陷候选（失败哨兵粘死 + 岔路口零日志），其中 P1 是**用户可见**的。

---

## 5. 若按「配置缺失」处置：缺什么、怎么补、补完那 6 条能不能验

**缺的一项**：夹具建单时 `quotation.customer_template_id` 未绑（`task260902/SelConfigAcTestBase.java:181-192` 的 `INSERT INTO quotation` 无该列）。

**怎么补（🚫 我不选，列给主线）**：
- **甲**：在 `Task260910CBase`（S-C 自己的基座）里把 🩹 `ensureLineTemplate` 的 `UPDATE quotation_line_item SET template_id` **改成/追加** `UPDATE quotation SET customer_template_id`，并且**挪到 `configure` 之前**调用。命中面 = 本单 1 行。改动面最小、只影响 S-C 一片。
- **乙**：在共享基座 `SelConfigAcTestBase#newFixture` 增一个可选入参「绑定报价模板」，默认不绑（保持既有各片行为逐字不变），S-C 显式传 `a2228dae…`。改动面大一档（共享基座，其它片会读到新签名），但一次解决所有片。
- **丙**：不补夹具，这 6 条改到 E2E / dev 环境层验（S-C 自己在 §6 提的方案）。

**补完能验哪几条**（依据 Q5 实测，逐条）：

| AC | 补前 | 补后（实测依据） | 判定 |
|---|---|---|---|
| AC-10④ `_record` 有行 + `quotation_id` 反向核对 | 前置不成立 | `_record` 1/2 行，`quotation_id` = 本单 | ✅ **可验** |
| AC-10⑤ `source` 值域 / `version_no` 起始 | 部分只读已做 | `_record` 有行后可全查 | ✅ **可验** |
| AC-13 `_record` 本次请求结束即有行 + `origin_id` 非空且能 JOIN 主表 | 前置不成立 | `origin_id` = `16837 / 18210 / 18211`（**非空**），且**不调任何额外入口**、`configure` 返回后就有 | ✅ **可验，且正是 D-21 要的构造性证据** |
| AC-14 附带（`_record` 2 行 + ratio 诊断） | 前置不成立 | 本次单材质场景下 `_record.material_ratio = 100.000000000000`（**非 NULL**） | ✅ 可验。⚠️ 顺带一条：S-C 报的「D-19 已接受 `_record.material_ratio` 为空」在这条链路上**未复现**——投影把 ratio 带出来了。双材质 70/30 场景是否同样带出来，**我没验**（未在本次范围） |
| AC-16 ①② `_record` 去重 | 前置不成立（且 AC-16① 另被 500 阻塞） | `_record` 有行 ⇒ 去重断言可执行 | ⚠️ **AC-16① 仍被 S-C 缺陷①（`v_compat_material_master` 不存在 → 500）独立阻塞**，那是另一件事 |
| AC-11 / AC-18⑥ 两页签渲染 | 0 条 | 「物料BOM」**2 行且为树形两层**（根 `1004-2609000001` / 子 `AgCu90`，见 `02-*.log:319`）、「物料与元素BOM」**2 行 `Ag 90` / `Cu 10`**（`:321`） | ✅ 后端面**可验**；🚫 仍不能替代 UI（AP-31/AP-50 族），权威判据仍是 `t260910c-sc.spec.ts` |

---

## 6. 临时调试代码已删除的复验证据

**worktree 里从未插桩**（插桩只发生在 scratchpad 隔离副本）：

```
$ cd <worktree> && git status --porcelain | grep -a "Diag\|DIAG"
(空)
$ git diff --stat -- .../CardSnapshotService.java .../ConfigureSnapshotService.java
(空)
```

隔离副本还原后与 worktree 原文**逐字节一致**：

```
quotation/service/CardSnapshotService.java        iso=bf7dfde3e73a9f74b263539cfe469b6b  wt=bf7dfde3e73a9f74b263539cfe469b6b  SAME
configure/service/ConfigureSnapshotService.java   iso=3be7280576d4cdd02bd8bbc0e828c6cd  wt=3be7280576d4cdd02bd8bbc0e828c6cd  SAME
$ grep -ran "DIAG-GUARD\|DIAG-SWALLOW\|DIAG-SNAPLINES" <iso>/cpq-backend/src/main/java/ | wc -l
0
```

两个诊断用例（`DiagCompDataRootCauseTest` / `DiagPreBoundTemplateTest`）**只存在于 scratchpad 副本**，
源码已作为证据归档成 `.java.txt`（不可编译进任何工程），worktree 的 `src/test` 里没有它们。

---

## 7. 发现但没动的问题（🚫 未修，交主线裁决）

### P1 🔴 失败哨兵粘死，事后补绑模板不自愈（**用户可见**）

**机制**：`quote_card_values` 落 `{"tabs": [], "__cardValueFailed": true}` 之后，`ensureCardValues` 的选行谓词是 `... IS NULL`（`CardSnapshotService.java:740` 上方注释原文：「`ensureCardValues` 的 `IS NULL` 谓词下次不再重选该行(自愈、不无限重算)」）⇒ 非 NULL 的哨兵**永不被重选**。

**实测**（`02-*.log:311 / 327`，A/B 的 B 阶段）：
```
[DIAG B] compData = 14 条  ← 干预后（模板已补绑）
[DIAG B] quote_card_values 前 300 字 = [{"tabs": [], "__cardValueFailed": true}]   ← 仍是哨兵
```

**可达性**：`CreateQuotationRequest.java:32` 原文「**留空则后续在报价单 Step2 中由用户手工选择**」⇒ 建单时不绑模板是**产品允许**的状态。在该状态下走选配加产品，主表照写、哨兵落库；之后用户在 Step2 选了模板，卡片仍是失败态。
⚠️ **我没有验到 UI 层**：`saveDraft` 的 D-1 失效（内容真变了才置 NULL）可能在用户后续编辑时把它解锁 —— 这一条**未验证**，需主线/前端确认「先加产品后选模板」在真实 UI 里能否走通、以及走通后卡片是否自愈。

**库内同型存量（只读，`cpq_db_0724`）**：
```
QT-20260908-0612 | 2026-09-08 | CUST-0004 正泰 | 6 行明细 | compData 0 | {"tabs": [], "__cardValueFailed": true}
QT-20260907-0580 | 2026-09-08 | CUST-0004 正泰 | 6 行明细 | compData 0 | {"tabs": [], "__cardValueFailed": true}
```
（两单 `customer_template_id` 与 `product_category_id` 皆 NULL。🚫 我**无法**确认它们是用户建的还是脚本建的，不据此下「线上已发生」的结论。）

### P2 🟡 选配物化链路的唯一岔路口零日志

`ConfigureSnapshotService.java:289` `if (comps.isEmpty()) return;` —— 无日志、无异常、无 warning。
`CreateQuotationMaterializer#checkMaterializeOutcome`（`:210` 起）那道「明细行>0 且 driver 组件>0 且 compData==0」三元判据**刻意**把「driver 组件 0 个」判为合法边界，且只挂在导入/建单链路；`ConfigureProductResource#configureProduct`（`:70-89`）的两段物化各自 `catch (Exception ignore)`。
⇒ 整条选配链路对本故障形态**零信号**，这正是本次三个测试片各自绕了半天的直接原因。

### P3 🟡 `_record` 清理缺口（既有登记的复现）

`ds_quote_*_record` 无 `quotation_id` 外键 ⇒ 删报价单不级联。我的诊断用例没有登记 `recordQuotationIds`，因此留下孤儿行（见 §8）。
📌 与 S-C 已登记的同一缺口；共享基座 `SelConfigAcTestBase#restoreFixtures` 不认识这两张表。

### P4 🟡 S-C 的 🩹 `ensureLineTemplate` 补错列（会误导下一个人）

`Task260910CBase.java:327-353` 的 javadoc 写着「🔬 2026-09-10 实测：`template_id` 为 NULL 时本单 `quotation_line_component_data` 物化 **0 条**」——
**相关性是真的，因果是反的**：实测把 `line_item.template_id` 留 NULL、只绑 `quotation.customer_template_id`，compData 照样 14 条（`03-*.log:257-258`）。这段注释若不改，下一个人还会顺着 BL-0202 查。
（🚫 我没有改 S-C 的文件 —— 那是别人的产出。）

### P5 🔵 S-C 缺陷①（`v_compat_material_master` 不存在 → 500）与本诊断**正交**

它阻塞 AC-16① 的夹具前置，与 compData 0 条是两件独立的事。本次未碰。

---

## 8. 待回收清单（🚫 我没有删，交主线按 §3.2 三步前置裁定）

**影响面已量化（只读 `count(*)` 实查）**：

| 库 | 对象 | 行数 | 来路 |
|---|---|---|---|
| `cpq_db_test` | `ds_quote_material_bom_record` where `quotation_id='ac9375ff-7cc4-4509-9cbf-6e243fb80a63'` | **1** | 我的 `DiagPreBoundTemplateTest`（报价单本体已被 `@AfterEach` 删除，`_record` 无 FK 未级联） |
| `cpq_db_test` | `ds_quote_element_bom_record` 同上 `quotation_id` | **2** | 同上 |
| `cpq_db_0724` | —— | **0** | 已核：customer / quotation / `ds_quote_customer_part` / `ds_quote_material` / `ds_quote_material_bom` / `ds_quote_element_bom` 对本次三个料号与两个客户**全部 0 行** |

清理 SQL（**只呈报，未执行**；命中面 = 3 行，可恢复性 = 不可恢复，但它们是本次诊断的纯造数，无业务价值）：
```sql
DELETE FROM ds_quote_material_bom_record  WHERE quotation_id = 'ac9375ff-7cc4-4509-9cbf-6e243fb80a63';
DELETE FROM ds_quote_element_bom_record   WHERE quotation_id = 'ac9375ff-7cc4-4509-9cbf-6e243fb80a63';
```

**其它（非本次产生，仅登记）**：S-C 前一轮的 2 行 `ds_quote_customer_part` + 2 个 `customer`（其回报 §8 已列）；`ds_quote_element_bom` 19 行 `source='TEST'` 属 `T260907R-*`。

**scratchpad 隔离副本**（会话结束即可弃，无需人工清）：
`/tmp/claude-1000/-home-joii-project-cpq/54527263-0bc3-4b06-b80f-f4e6682000f1/scratchpad/iso-diag/`

---

## 9. 自检声明

- 构建：隔离副本 `./mvnw -o -q test-compile` → **exit 0**；4 次 `mvnw test` 全部 `Tests run` 有数（1/1/1/1），**0 次假故障**
- 连库：两库均以**运行时 Flyway 日志原文**确证（`Database: jdbc:postgresql://…/cpq_db_test` 与 `…/cpq_db_0724`），🚫 不靠读配置
- 端口：临时端口 `8123 / 8124 / 8125 / 8126`（`@QuarkusTest` 各自随机 test-port，未占 `8081 / 5174`）；🚫 未跑任何宽匹配杀进程命令
- 干预生效自检：每次 UPDATE 后**回读**该列并打印（`01-*.log:127`、`02-*.log:128`、`03-*.log:101`）
- 指纹复用假绿：三次运行均新铸料号（`1077- / 1004- / 1081-2609000001`），🚫 无 `fingerprintMatched=true` 路径
- **N+1 自检：本次为纯诊断，未新增/改动任何生产代码循环体 ⇒ 无 N+1 面 ✅**
- 红线：🚫 未执行 `DROP` / `TRUNCATE` / 无 `WHERE` 的 `DELETE`/`UPDATE`；所有 UPDATE 命中面 = `WHERE id = <本单>`（1 行），所有 DELETE 均由既有基座 `@AfterEach` 按 `quotation_id` / `customer_no` / `source='MANUAL'` 收窄
- 🚫 未 `git commit`；🚫 未 `cd` 出 worktree 改任何代码文件；本报告与证据写在主仓 `dev-docs/`（主线明确指定的路径）
- 🚫 未自行决定修法 —— §5 三个候选、§7 五条发现全部**只列不选**
