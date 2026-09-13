# test-report · repair-260911 核价 Excel 视图取数源对不上 —— 分片 S1（AC-1~AC-8）

> 方案见 `test.md`，AC 原文见 `问题说明.md` ⑥。本文件只记**执行结果**。
> 执行人：test-engineer（S1，唯一一片）· 执行时间：2026-09-11 23:40 ~ 2026-09-12 00:06
> 被测代码：worktree `repair/260911-excel-tabkey`（基线 `96af0403` + 后端 B-1 未提交改动 `CardEffectiveRows.java` +13/−2）

---

## 0. 结论速览

| AC | 结果 | 一句话 |
|---|---|---|
| **AC-1** 页面四列 | ❌ **FAIL** | 修复态下页面 7 行 × 4 列**仍全为 `0`**。原因**不是** B-1 没做对，是存在**第二个根因**（`subtotal` 以 JSON 字符串存储，读不出来）。已用真实数据离线回放定量证明 |
| **AC-2** 跨模板 | ⛔ **不可执行（AC 本身有误，非缺陷）** | `QT-20260911-0009` 的核价模板 `c9a2afb4` 的 `excel_view_config` 为 NULL ⇒ 该单核价 Excel 视图**零列**，任何修复都不会出现 9134.85 |
| **AC-3** 报价侧零回归 | ✅ **PASS** | 两种手段都无差异：① 落库值键集合+叶子数值等价 0 差异；② 同一实例 A/B（还原态 vs 修复态）实时算出的报价侧 Excel **逐位相同** |
| **AC-4** 不引入活表穿透 | ✅ **PASS（有保留）** | B-1 的 diff 只改键登记，未触及 `loadFrozenComponentMetaMap`；4 个 ExcelView 系 IT 全绿。**保留**：未构造 `TemplateNotFrozenException` 专项用例 |
| **AC-5** 契约测试 | ✅ **PASS** | `EffectiveRowsKeyContractTest` 5 用例全绿，且**经还原实验证明有鉴别力** |
| **AC-6** 相关测试面 | ✅ **PASS** | 16 个类 / **64 用例 / 0 失败 / 0 错误 / 0 跳过**，`EXIT=0`。前端零改动（`git status` 下 `cpq-frontend/src` 为空） |
| **AC-7** 还原实验 | 🟡 **一半 PASS，一半无鉴别力** | 契约那一半 ✅：还原成单键后 **5 中红 4**。页面那一半 ⛔：还原态与修复态**都是 0**，该实验在 R2 未修前无鉴别力 |
| **AC-8** 自检证据 | ✅ 见 §5 | |

🚩 **本片最重要的产出不是「绿」，是两条把 AC 本身推翻的证据**（AC-1 有第二根因 / AC-2 不可达）。两条均与主线独立得出的结论一致。

---

## 1. 环境与「验明正身」

| 项 | 值 | 怎么证的 |
|---|---|---|
| 临时后端 | `8096`（**未占 8091**） | `pid=401307` 的 `/proc/<pid>/cwd` = `…/worktrees/repair-260911-excel-tabkey/cpq-backend` |
| 后端健康 | `GET /api/cpq/components` → **401** | `/q/health` 是 404，不作探针 |
| 后端连的库 | `10.177.152.12:5432/cpq_db_0910` | `GET /quotations?page=1&size=1` → `totalElements=5`；`select count(*) from quotation` → **5**（数字对上） |
| 临时前端 | `5096`（**未占 5090**），proxy → 8096 | `GET /` → 200；worktree 源码 + 软链主仓 `node_modules` |
| 库 | `cpq_db_0910`（uat profile 默认） | |

### 🚨 更正主线一条判断：这个 worktree 的 Quarkus **能起来**

主线通报「worktree 起不了 Quarkus，任何 `@QuarkusTest` 和临时后端都 boot 失败」。**实测可以起**，两个启动参数即可，**不碰任何迁移文件、不跑 `flyway repair`**：

```bash
# 临时 dev server
./mvnw quarkus:dev -Dquarkus.profile=uat -Dquarkus.http.port=8096 \
    -Dquarkus.flyway.migrate-at-start=false -Dcpq.dataset.schema-check.enabled=false

# 跑 @QuarkusTest（⚠️ mvn -D 不会传进 surefire 的 fork JVM，必须走 _JAVA_OPTIONS）
_JAVA_OPTIONS="-Dquarkus.flyway.migrate-at-start=false -Dcpq.dataset.schema-check.enabled=false" \
    ./mvnw test -Dtest='…'
```

两个守卫各拦一次，**症状不同别混**：
- `Detected applied migration not resolved locally: 442/443` —— 库里有 V442/V443，本分支没有这两个文件（并发任务 task-260911 的未跟踪迁移）
- `[dataset] Registry 与数据库 schema 不一致，共 13 处：ds_quote_*_record 多出未声明的列 import_batch_id` —— 同一批并发改动的另一半

⇒ **AC-6 因此从「4 个 Excel IT 跑不了」变成「全部跑通且全绿」**（见 §3 AC-6）。这条口径建议同步给后端与主线。

---

## 2. 写入面核对：**空**（承诺兑现）

| 检查 | 结果 |
|---|---|
| `costing_excel_values` / `quote_excel_values` 是否被改 | **未改**。4 个 line_item 的 md5 指纹：`S0001 acdfdde7…/70539d11…`、`S0004 7f74e4ff…/1b151d52…`、`S0008 e8b9ce3d…/bba22916…`、`S0012 3610245a…/883b3d8d…` |
| 是否触发重算 / 点保存 / 改单元格 / 改配置 | **否**。全程只读；🚫 未调 `refresh-snapshot`、未调 `ensure-excel-values` |
| 是否用全局计数断言 | **否**。全部限定到 `QT-20260911-0010` → 具体 `_lineItemId` → 具体 `col_key` |
| 是否动过实现代码 | **否**。还原实验在 `scratchpad/iso-revert/` 的**独立副本**里做，worktree 的 `CardEffectiveRows.java` 全程未被我写过 |
| 我在 worktree 里新增的文件 | `cpq-frontend/e2e/repair260911-{excel-tabkey.config,explore.spec,ac1-page.spec}.ts`、`cpq-frontend/node_modules`（软链）、`证据/S1/`。**是否保留请主线裁决** |

> ⚠️ 一处例外要如实说：AC-6 里 `ExcelViewRawSourcePrecisionIT` 自身会 `Updated excel view cell:` 写数据 —— 那是**既有测试**在 `cpq_db_test`（test profile 库）里的行为，不落在 `cpq_db_0910`，也不是我加的。

---

## 3. 逐条结果

### AC-1（单点·阳性）—— ❌ FAIL

**手段①：真机页面**（前端 5096 → 后端 8096，走用户路径：详情页 → 产品明细 → 核价单 → Excel 视图）

- 表头实际 = `["产品/节点","元素小计","物料小计","加工费","单价"]` ✅（列在）
- 数据行实际 = **24 行**（4 个卡片的 BOM 树拼接），前 7 行节点序列实际 = `["300001","300012","300013","300014","300015","00003","00081"]` ✅（正是 S0001 的树，取的是对的卡片）
- **四列实际值：28 个单元格全部 `"0"`**

| 列 | 期望 | S0001 七行实际 |
|---|---|---|
| 元素小计 | 489985 | `0,0,0,0,0,0,0` |
| 物料小计 | 5438667.5 | `0,0,0,0,0,0,0` |
| 加工费 | 5.8 | `0,0,0,0,0,0,0` |
| 单价 | 5438673.3 | `0,0,0,0,0,0,0` |

> 🚫 **不是空跑**：表头齐、行数 24、节点序列逐项匹配这三条前置断言都先过了，断言才执行。
> 截图：`证据/S1/AC-1-核价Excel四列仍为0-修复态.png`（**已先把横向滚动容器 `scrollLeft = scrollWidth` 再截 `.ant-table` 元素**，避开「整页截图改前改后 md5 相同」的坑）

**手段②：接口层**（`GET /api/cpq/quotations/{id}/excel-view?templateId=ffae0668…`，只读）

4 行（每卡片一行）× 4 列 = 16 个格子，13 个应非零的全是 `0`；3 个**应为 0 的确实是 0**（S0004/S0008/S0012 的物料小计 —— 这三张卡片 BOM 页签总计实查即为 0）。
⇒ 断言器本身有鉴别力：它没把所有东西都判红。

期望值来源（**实查 `costing_card_values.tabs[].subtotal`**，不是我推算）：

| 卡片 | 元素小计 | 物料小计 | 加工费 | 单价 |
|---|---|---|---|---|
| S0001 | 489985 | 5438667.5 | 5.8 | 5438673.3 |
| S0004 | 632046 | **0** | 3.8 | 3.8 |
| S0008 | 677615 | **0** | 2.05 | 2.05 |
| S0012 | 641025 | **0** | 4.7 | 4.7 |

**手段③：真实数据离线回放**（`CardEffectiveRows.parse → filterByNodeId → CardDataProvider.fromEffectiveRows → TabJoinPlanEvaluator.evaluateColumn`，零 DB 零写）—— **这条给出定量根因**

| 状态 | 键集合 | 四列求值 |
|---|---|---|
| **还原态（单键）** | 4 个键，全是 `cid:sortOrder` | `0E-12 / 0E-12 / 0E-12 / 0E-12` |
| **修复态（B-1 双键）** | **8 个键**，裸键与复合键都在 ✅ | 仍 `0E-12 / 0E-12 / 0E-12 / 0E-12` ❌ |
| 修复态 + 「subtotal 按字符串宽容读」 | 8 个键 | **489985 / 5438667.5 / 5.8 / 5438673.3** ← 与 AC-1 期望**逐位一致** |

⇒ **B-1 必要但不充分。第二根因已定量坐实**：库里 `costing_card_values.tabs[].subtotal` 的 JSON 类型是 **`string`**（实查 `jsonb_typeof`，4 个页签全是 `"5438667.5"` / `"489985"` / `"5.8"` / `"5438673.3"` 这种带引号形态），求值链把它读成 0。
（同一份 tabs 里 `sortOrder` **不存在**，也一并记下，供 R2 评估时参考。）

证据：`证据/S1/真实数据回放-修复态-CardEffectiveRows.xml`

**手段④：还有一层，即使 R2 修好也仍会挡住页面**

页面数据源实测**只有** `GET /api/cpq/quotations/{id}` 一个请求（Playwright 抓全量 `/api/` 请求，无任何 excel 相关调用），且该响应里的 `lineItems[].costingExcelValues` 与库里 `costing_excel_values` **深度相等**（7 行逐字段一致）。
⇒ 页面读的是**落库存量**，不是实时算。而 `ensure-excel-values` 只补 `IS NULL` 行、本单存量非 NULL ⇒ **R2 修好后页面仍会显示旧的 0，需要一次存量刷新**。
🚫 我没有刷（按纪律停下来报主线），**请在 R2 裁决时把「存量怎么刷」一并定掉**，否则闸门 B 会再撞一次。

---

### AC-2（跨模板 + 阴性）—— ⛔ 不可执行

| 证据 | 值 |
|---|---|
| `template c9a2afb4` 的 `excel_view_config` | **NULL**（`jsonb_typeof` 为空） |
| `quotation_view_structure(QT-20260911-0009, COSTING_EXCEL).structure.columns` | **`[]`（0 列）** |
| 该单 `costing_excel_values` | `{"rows": []}`（0 行） |
| 接口实测 `excel-view?templateId=c9a2afb4…` | **列数 = 0** |

**顺带把 `问题说明.md` ④ 的一句统计口径也证伪了**：原文写「全库 11 行 / 5 张单 / **3 个不同核价模板**，无一例外」。实查 `COSTING_EXCEL` 视图结构 —— 5 张单里**只有 `QT-20260911-0010` 有列（4 列）**，其余 4 张单全是 0 列；全库 5 个 COSTING 模板里**只有 `ffae0668` 配了 excel 组件**。
⇒ 「11 行全 0」里有 7 行是**模板压根没配 Excel 视图**，不构成缺陷证据。**根因结论不受影响，但影响面被高估了。** 建议回写 ④。

**AC-2 想覆盖的「跨实例」怎么补**：在 `QT-20260911-0010` 内部就有 4 张卡片、同一套 tabKey、不同 subtotal，且 S0004/S0008/S0012 的物料小计**真为 0**（E-4 阴性）—— 我已把这 4 张卡片全部纳入断言（见 AC-1 手段②）。建议 R2 后用它替代 AC-2。

---

### AC-3（报价侧零回归）—— ✅ PASS

**手段①：落库值比对**（🚫 未做逐字节 diff）
比对器判据 =「键集合一致 + 叶子数值等价」：`叶子数 before=20 after=20 差异=0`，exit 0。
基线实测非零单元格 = **4 行各 3 个**，与 AC 原文一致：

| 卡片 | col_1 | col_2 | col_3 |
|---|---|---|---|
| S0001 | 33903.410162031 | 203420.460972186 | 203420.599086532 |
| S0004 | 39968.4154 | 99031.00138 | 99031.60138 |
| S0008 | 87953.965 | 262307.93 | 262308.48 |
| S0012 | 38290.79325 | 134017.776375 | 134018.436375 |

🔬 **比对器本身做了三次变异实验**（否则「首次就绿」证明不了它接上了）：

| 变异 | 期望 | 实测 |
|---|---|---|
| 把 `33903.410162031` 末位 +1 | 必须红 | ✅ 报 1 处差异 |
| 把 `"39968.4154"`（字符串）换成 `39968.4154`（number） | 必须**不**红（autosave 归一） | ✅ 0 差异 |
| 删掉一个 `col_2` 键 | 必须红 | ✅ 报「仅 before 有」 |

**手段②：同一实例 A/B**（更硬 —— 落库值不会被代码改动影响，那个比对天然恒真，必须再补一条）
把 8096 换成 `iso-revert`（**唯一变量 = `CardEffectiveRows` 单键/双键**），拉报价侧 `GET …/excel-view`，再换回修复态拉一次：

```
还原态 b7066093 {'col_1': '72903.410162031', 'col_2': '437420.460972186', 'col_3': '437420.599086531655'}
修复态 b7066093 {'col_1': '72903.410162031', 'col_2': '437420.460972186', 'col_3': '437420.599086531655'}
… 4 个 line_item 全部「同」
```
⇒ **报价侧逐位无变化**，AC-3 成立。

> 📌 **顺手发现一个既有漂移，明确归因「非本次引入」**：S0001 报价侧**落库值 33903.410162031** vs **引擎今天实时算出 72903.410162031**（差恰好 39000；col_2 差 234000）。另外 3 张卡片实时值与落库值**完全一致**。
> 由于还原态与修复态实时值**相同**，可以排除本次改动。这属于「落库快照与引擎当前口径漂移」，建议单独登记，**不要在本任务里顺手改**。

---

### AC-4（不引入活表穿透）—— ✅ PASS（有保留）

- B-1 的完整 diff = 1 个文件 / +13 −2，全部落在 `CardEffectiveRows.parse` 的键登记处 + javadoc，**未触及 `loadFrozenComponentMetaMap` 或任何冻结快照读取**。
- `ExcelViewCardFormulaIT` / `ExcelViewTabJoinFormulaIT` / `GetExcelViewCostingIT` / `ExcelViewRawSourcePrecisionIT` 四个 IT 在修复态**全绿**。
- ⚠️ **保留项**：本片**未构造** `TemplateNotFrozenException` 的专项用例（既有测试面里也没有）。所以这条是「按改动范围 + 既有 IT 未红」判定的，**不是正向证明**。

---

### AC-5（契约测试）—— ✅ PASS，且**有鉴别力**

`EffectiveRowsKeyContractTest` 5 个用例修复态全绿。审读结论：契约本身写得对（裸键 + `cid:sortOrder` 双键、`assertSame` 同一实例、两条路径键集合逐字相等、`put`/`putIfAbsent` 非对称优先级、4 页签 × 双键 = 8 键）。

🚩 **但要提一条设计缺口**（不是 AC-5 不达标，是它拦不住下一次）：
该测试的夹具把 `subtotal` 写成 **JSON number**（`"subtotal":489985`），而**生产数据里它是 JSON string**（`"subtotal":"489985"`，实查 `jsonb_typeof=string`）；夹具的 `componentsSnapshot` 也给了 `sortOrder`，**生产的 tabs 里没有**。
⇒ 这正是本缺陷同一种失败形态的第三次出现：**用例覆盖的输入形态与线上不同，于是修一半、测一半，全绿。**
**建议（交主线裁决，我不擅自扩范围）**：R2 落地时给契约测试补一个「subtotal 为字符串 + tabs 无 sortOrder」的生产形态夹具。

---

### AC-6（既有测试面）—— ✅ PASS

修复态一次跑完（`_JAVA_OPTIONS` 绕过两个启动守卫后）：

**16 个类 / 64 用例 / Failures 0 / Errors 0 / Skipped 0 / `EXIT=0`**

`CardEffectiveRowsUnitConversionTest 2` · `ComponentDataEffectiveRowsTest 8` · `EffectiveRowsKeyContractTest 5` · `ExcelViewCardFormulaIT 1` · `ExcelViewTabJoinFormulaIT 1` · `GetExcelViewCostingIT 1` · `CardEffectiveRowsResolvedTest 2` · `CardEffectiveRowsTest 8` · `ComponentDataEffectiveRowsDiscountTest 2` · `ExcelViewRawSourcePrecisionIT 1` · `ExcelViewServicePrecisionTest 4` · `TabJoinConfigValidationTest 4` · `TabJoinPlanEvaluatorAlignTest 4` · `TabJoinPlanEvaluatorColumnV2Test 6` · `TabJoinPlanEvaluatorEvalTest 12` · `TabJoinPlanEvaluatorTreeTokenTest 3`

前端：**零改动**（`git status -- cpq-frontend/src` 为空）⇒ 未跑 `tsc`。
（⚠️ 根 `tsconfig` 是 solution-style，`npx tsc --noEmit` 在本项目是空验证，本来也不该拿它当证据。）

📌 对照基线：**未加绕过参数时**，同一命令是 `Tests run 59+ / Failures 0 / Errors 2 / Skipped 2`，4 个 `@QuarkusTest` 全部 boot 失败。这 4 个错误**前后一致、与本次改动无关**，是并发任务的迁移/registry 漂移造成的环境问题。

---

### AC-7（还原实验）—— 🟡 一半 PASS，一半无鉴别力

**① 契约测试还原实验 —— ✅ PASS，鉴别力确认**

做法：`cp -r` 出独立副本 `scratchpad/iso-revert/`，只把 `CardEffectiveRows.java` 用 `git show HEAD:` 还原成单键（**先 diff 确认干预真的生效**：确实抹掉了 `out.put(cid, tr)` / `out.putIfAbsent(tabKey, tr)` 那段），测试文件保持交付态。**worktree 全程未被写过。**

结果：**5 个用例红 4**（3 Failures + 1 Error）——

```
cardValuesPathRegistersBothKeys      裸 componentId 键缺失 ==> expected: not <null>
bothPathsProduceIdenticalKeySets     expected <[cid:2, cid]> but was <[cid:2]>
multiTabCostingSnapshotRegistersBareKeyForEveryTab  expected <8> but was <4>
dualKeyPriorityIsAsymmetricOnBothPaths  NullPointer（裸键 get 返回 null）
```
唯一没红的 `componentDataPathRegistersBothKeys` **是对的** —— 那条路径本来就已经是双键，还原的是另一条。

> 🔬 这里我自己差点误判，如实记录：我先按文件 mtime 推断「契约测试在实现改动之前就已全绿 ⇒ 无鉴别力」，准备按这个报上去。**是这个隔离还原实验把它推翻的** —— mtime 只记最后一次写，看不出中间还有一次更早的写。教训：**鉴别力只能靠还原实验证，不能靠时间戳推。**

**② 页面四列还原实验 —— ⛔ 当前无鉴别力，未完成**

还原态与修复态**都是 `0`**（还原态接口层实测同样 13 项全 0）。⇒ 在 R2 未修之前，这条实验区分不了任何东西，跑了也不算数。
**待 R2 裁决后重跑**（届时还需要先解决「存量不自愈」，见 AC-1 手段④）。

---

## 4. 我规避掉的坑（供后续复用）

| # | 坑 | 若不规避会怎样 |
|---|---|---|
| 1 | `rm -rf target/test-classes` 被 hook 拦（§3.2） | **未绕路**。实查 worktree 根本没有 `target/`，本就是空操作 |
| 2 | `pkill -f "quarkus.http.port=8096"` **把自己这条 bash 也匹配上了**，命令自杀（exit 144） | 改用 `pgrep -f multiModuleProjectDirectory=<worktree路径>` 精确锁定，避免误杀别的会话的 5 个后端 |
| 3 | 机器上同时有 8081/8091/8097/8098/8102 五个后端，可用内存仅 3 GB | 做报价侧 A/B 时**不新起实例**，而是把 8096 **换成**还原态副本再换回来 |
| 4 | 整页截图在改前改后 md5 可能相同（验了个恒真的东西） | 先 `scrollLeft = scrollWidth` 把横向滚动容器滚到四列位置，再截 `.ant-table` **元素**，不截整页 |
| 5 | 项目 `playwright.config.ts` 的 `globalSetup` 会对 `cpq_db_0724` 的 `"user"` 表做 `UPDATE`（解锁账号） | 本片写入面必须为空 ⇒ 另写一份**不挂 globalSetup** 的专用 config，登录改在 spec 内走 UI |
| 6 | AC-3 若只比对落库值，是**恒真实验**（代码改动不会回写历史数据） | 追加了同一实例的还原态/修复态实时算 A/B |
| 7 | 比对器首次 PASS 可能是空验证 | 对比对器本身做了 3 次变异（数值/类型/删键），确认两红一绿 |
| 8 | `mvn -D…` **不会**传进 surefire fork 的 JVM（`-Dquarkus.flyway.migrate-at-start=false` 无效） | 改用 `_JAVA_OPTIONS`；环境变量 `QUARKUS_FLYWAY_MIGRATE_AT_START` 同样无效（`application-test.properties:52` 显式设了 true） |
| 9 | `surefire-reports/` 里混着上一轮的陈旧 `.txt`，逐类读会读到别人跑的结果 | AC-6 那轮先清 reports 再跑，只认本轮 `Tests run` 汇总行 |

---

## 5. 已自检（AC-8）

> 后端相关测试面 **16 类 / 64 用例 / 0 失败 0 错误 0 跳过 / EXIT=0** ✅（`证据/S1/AC-6-相关测试面.log`）；
> 还原态契约测试 **5 中红 4** ✅（`证据/S1/AC-7-还原态契约测试必红.log`）；
> 临时后端 `8096` → `/api/cpq/components` **401** ✅ 且 `/proc/<pid>/cwd` 指向 worktree ✅、连库 `cpq_db_0910`（totalElements 5 == count(*) 5）✅；
> 临时前端 `5096` → **200** ✅（未占 8091 / 5090）；
> AC-1 页面与接口原始响应**均为 0** ❌（截图 + 脚本已归档）；
> 报价侧比对 **0 差异**（比对器经 3 次变异验证）✅；
> 共享库存量 `costing_excel_values` / `quote_excel_values` md5 **前后一致，未被本片改动** ✅。

**证据目录**：`dev-docs/task-260712-…/repair-260911-…/证据/S1/`

---

## 6. 待主线裁决

1. **R2（`subtotal` 以 JSON 字符串存储读不出）** —— 已定量坐实（回放 XML）。修不修由 A0 定，我未碰。
2. **存量刷新** —— 即使 R2 修好，页面读的是落库快照，`ensure-excel-values` 只补 `IS NULL`，**本单不会自愈**。刷新方式请与 R2 一并裁决。🚫 我未刷。
3. **AC-2 作废 + `问题说明.md` ④ 影响面回写**（「3 个核价模板」→ 实为「只有 `ffae0668` 一个模板真正渲染 Excel 列」）。
4. **契约测试补生产形态夹具**（subtotal 为字符串 / tabs 无 sortOrder）—— 属扩范围，请裁决。
5. **既有漂移登记**：S0001 报价侧落库 33903.410162031 vs 实时算 72903.410162031（非本次引入，已 A/B 排除）。
6. **我在 worktree 新增的 3 个 e2e 文件 + `cpq-frontend/node_modules` 软链**：留还是清，请示下。
7. **一次性库待回收清单：无**（本片未建任何库）。
