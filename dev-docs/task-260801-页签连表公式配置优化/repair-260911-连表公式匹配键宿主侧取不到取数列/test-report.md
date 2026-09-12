# test-report · repair-260911 · 分片 S1（AC-1 ~ AC-11）

- 测试员：test-engineer（S1，唯一分片）
- 执行时间：2026-09-11 21:10 ~ 21:55（本机时区 UTC-7）
- 分支 / worktree：`repair/260911-crosstab-match-hostrow` @ `/home/joii/project/cpq/.claude/worktrees/repair-260911-crosstab-match`
- 被测代码快照：worktree 未提交改动，8 个文件（7 改 1 新增），实现文件末次写入 **21:31:19**，其后至 21:55 无变更（已用 mtime 连续 4 分钟静默确认）
- 库：`10.177.152.12:5432/cpq_db_0910`（uat）；单据 `QT-20260911-0010` / `line_item b7066093-…` / 卡片 `S0001`
- 证据归档：`./证据/S1-测试/`（**已从 Playwright 的 `test-results/` 复制出来**，不会被下一轮清空）

---

## 0. 一句话结论

**AC-1 / AC-2 / AC-3 / AC-4 / AC-5 / AC-6 / AC-7 / AC-8 通过（均带还原实验或变异实验背书）；AC-9 无法执行（AC 本身在本单不可执行，见 §3）；AC-10 引擎层通过、渲染层未验证；AC-11 见 §5 自检行。**

🚨 **另抓到 1 个需要主线裁决的实质问题**：页面已显示 5438667.5，但**落库的 `costing_card_values` 仍是全 0**，且两次真实「保存草稿」都没有改写它（§3.2）。

---

## 1. 逐条 AC 结果

### AC-1（单点·阳性）✅ 通过

| 项 | 值 |
|---|---|
| 手段 | 真实浏览器（Chrome，`channel:'chrome'`）打 **5091**（worktree 代码的临时前端）→ 8093（worktree 代码的临时后端）→ `cpq_db_0910` |
| 实际观测 | 料号列 `["—","300012","300015","00081","300013","00003","300014"]`<br>物料成本列 `["0","0","0","5204745","0","233922.5","0"]` |
| 期望 | `00003` = 233922.5、`00081` = 5204745 |
| 结论 | **PASS**（`00003`=233922.5、`00081`=5204745，逐位相等） |

前置事实同时被断言（防止「期望值建立在错的乘数上」）：`00003` 行组成用量 = `3.5`、`00081` 行 = `12.3`，两者都在同一次抓取里核对过。

**鉴别力（还原实验 2）**：见 §2.2，同库同后端只回退前端引擎一行 → 同一段观测代码读出全 `0`。

证据：`证据/S1-测试/02-...png`（横向滚到物料成本列的真实截图）、`06-修复后表格原始抓取.json`

### AC-2（单点·阳性）✅ 通过

- 实际观测：页签底部 `小计 ¥ 5438667.5  合计 ¥ 5438667.5`
- 未修复态同位置为 `小计 ¥ 0  合计 ¥ 0`
- 结论：**PASS**（小计 = 5438667.5；合计由 ¥0 变为非 0）

### AC-3（单点·跨类型）✅ 通过

- 手段：共享夹具 `cross-tab-cases.json` 的 `repair-260911 BASIC_DATA …` 与 `repair-260911 DATA_SOURCE …` 两条，**前后端各跑一遍**
- 实际观测：两端两条均为 `300`（源行 100 + 200）
- 鉴别力：行为回退后两条**同时**变 `0`（后端原文 `expected 300.0 but got 0.0`，前端 `expected '0' to be '300'`）
- 结论：**PASS** —— 覆盖的是「非 INPUT 类型」整类，不是只给 `BASIC_DATA` 打补丁

### AC-4（单点·阴性）✅ 通过

- 实际观测：`300012` / `300013` / `300014` / `300015` 四行 + 根行（料号列 `—`）物料成本均为 `0`
- 该断言在**修复前后两种模式下都执行且都通过**（不是只在一侧生效的空断言）
- 结论：**PASS**（空集返 0 的正确语义没有被"修"坏）

### AC-5（边界·阴性）✅ 通过（含变异实验）

- 覆盖：夹具 `repair-260911 匹配键两侧真为空 → 不匹配(不得空值判等)`（两端）+ 后端 `FormulaCalculatorCrossTabHostDiagTest.blankButPresentHostMatchKey_yieldsZeroWithoutDiagnostic`
- 实际观测：结果 `0`，且**不写诊断**（键在、值为空属正常业务语义）
- 🔬 **变异实验（守卫鉴别力，testing.md §5.6）**：在隔离副本里把匹配判据改成「两侧皆空判等」
  （`if (isBlank(av)||isBlank(bv)||!valEquals(av,bv))` → `if (!valEquals(av,bv) && !(isBlank(av)&&isBlank(bv)))`，两处都改），
  结果 **恰好 2 条红**：
  - `Case [repair-260911 匹配键两侧真为空 → 不匹配]: expected 0.0 but got 300.0`
  - `Case [whitespace-only match key no match]: expected 0.0 but got 5.0`（既有同族守卫）
  ⇒ 这条守卫确实接上了，不是"首次 PASS 的空断言"
- 结论：**PASS**

### AC-6（无副作用·对拍 + 还原实验）✅ 通过

| 检查项 | 结果 |
|---|---|
| 两份夹具逐字一致 | ✅ `md5 = 961440554d3038dd9b115d5a0ae82b60`（后端 `src/test/resources/` 与前端 `src/utils/__fixtures__/` 相同） |
| 用例组齐全 | ✅ 5 条新用例：① `BASIC_DATA` 命中 ② `DATA_SOURCE` 命中 ③ `INPUT_TEXT` + default_source 命中（对照组）④ `INPUT_TEXT` 显式清空 `""` 不匹配 ⑤ 两侧真为空不匹配 |
| 修复后两端全绿 | ✅ 后端 54/54、前端 121/121（`cross-tab fixture` describe 全绿） |
| **先跑红** | ✅ 见下 |

🔬 **还原实验 1（AC-6 的鉴别力）**：把实现的行为回退到修复前（后端 `hostRowForMatch(ctx)` 直接返回 `currentRowRaw`；前端 `evaluateExpression` 入口强制 `matchRow = undefined`），**夹具与 harness 一字不动**：

- 后端：`Tests run: 54, Failures: 2` —— 恰好是 ①② 两条，失败文案 `expected 300.0 but got 0.0`
- 前端：`Tests 5 failed | 116 passed` —— ①②③ + 引擎级 `AC-3` / `AC-10` 两条，文案 `expected '0' to be '300'`

⇒ **红的表现是「匹配 0 行」，不是抛错**，符合 AC-6 的措辞要求。

⚠️ 一处两端不对称（**不是缺陷，但值得主线知道**）：③ `INPUT_TEXT + default_source` 这条，后端在行为回退后仍**绿**（后端 harness 走 `buildCurrentRowRaw`，其中 `fillInputDefaultSourceByFieldName` 本就补 INPUT 型），前端则**红**（前端 harness 的宿主行完全由 `buildHostMatchRow` 构造）。两端产品结果一致（都 300），差别只在 harness 造上下文的路径。

### AC-7（无副作用·报价侧零回归）✅ 通过

- 取证：`quote_card_values` 全程 4 次快照（实验前 / AC-9 后 / 还原态前端真实保存后 / 修复态前端真实保存后）
- 关键数字**逐位不变**：BOM「物料成本」`203420.460972186`、材质元素「材料成本」小计 `33903.410162031`
- 末次比对：`sha256 = 499d8abf…` 与基线**逐字节相同**，数值/文本变化叶子 **0 个**

⚠️ **AC-7 原文写的是「diff 为空」，这条判据要小心用**：实测**在未修复代码上**，仅仅打开报价单编辑页（autosave）就会把 24 个叶子从 JSON number 归一成 string（`1` → `"1"`），**数值不变**。若按逐字节 diff 判，会把这条既有行为误报成本次修复的回归。
⇒ 我用的是「键集合必须完全一致 + 每个叶子按数值等价比较」的比对器 `证据/S1-测试/cmp_qcv.py`，并对它做了变异实验：把 `203420.460972186` 改成 `…187`（1e-9 差异）→ 比对器 `FAIL`，说明它抓得住真实数值变化。

### AC-8（无副作用·既有测试面）✅ 通过（2 个失败已 A/B 归因为既有问题）

**后端**（在 worktree 的 `cpq-backend/` 下跑，运行前采样确认无其它 maven/quarkus 在该 worktree 内构建）：

```
Tests run: 146, Failures: 1, Errors: 1
```

| 失败项 | 归因 | 依据 |
|---|---|---|
| `TreeFormulaParityFixtureTest.fixtureTests` | **既有**（与本次无关） | 测试里硬编码路径 `dev-docs/task-0803-BOM页签增加父子取值公式/…`，仓库里的真实目录是 **`task-260803-…`**。该文件本次未被改动；在**纯 HEAD 源码**副本上同样红 |
| `CardSnapshotDryRunParityTest.dryRunTokenRowsEqualsRenderRowByRow` | **既有**（与本次无关） | `ClassCastException: String cannot be cast to Number`（测试第 169 行）。在**纯 HEAD 源码**副本（`FormulaCalculator.java` / harness / 夹具全部 `git show HEAD:` 还原、并移除新测试类）上**逐字同样报错** |

其余 **144 个全绿**，含本次直接相关的：`FormulaCalculatorCrossTabFixtureTest` 54、`FormulaCalculatorCrossTabHostDiagTest` 7、`FormulaCalculatorCrossTabTest` 15、`CrossTabComponentOrderTest` 15、`CrossTabDepsRealSnapshotReplayTest` 4、`FormulaCalculatorClearedInputTest` 4、`FormulaCalculatorSumHostField(EdgeCases)Test` 14、`FormulaCalculatorKsumTest` 8、`FormulaCalculatorGoldenCasesTest` 13、`FormulaCalculatorUnitConversionTest` 4 等。

**前端**：`npx vitest run src` → **99 文件 / 1201 用例全绿**（21:48:24 那一轮，跑在实现末次写入之后）。

**tsc**：`tsconfig.app.json` / `tsconfig.test.json` / `e2e/tsconfig.e2e.json` 三个子工程各 `exit=0`。

🚨 **这里踩到两个"空验证"，请主线也按新写法用**：
1. `npx tsc --noEmit`（最常见写法）在本项目**什么都不检查** —— 根 `tsconfig.json` 是 `{"files": [], "references": [...]}`，`--listFiles` 实测命中 worktree 源码 **0 个**，却 `exit=0`。
2. 改用 `npx tsc -b` 也**可能什么都不做** —— `tsBuildInfoFile` 落在 `node_modules/.tmp/`，而 worktree 的 `node_modules` 是软链到主仓的，增量信息串台后直接判"已是最新"，同样秒回 `exit=0`。
3. 可用写法：`npx tsc -p <子工程配置> --noEmit --incremental false`，逐个跑。已做证伪实验：往我自己的 e2e 文件塞一句 `const x: number = "str"` → `exit=2` 且报 `TS2322`，还原后 `exit=0`；`--listFiles` 实测 409 个 worktree 源文件进入编译集合。

### AC-9（序列）🚫 **无法执行 —— AC 本身在本单不可执行**（详见 §3）

已执行的部分（保存 → 切报价单/核价单 → 刷新整页）三个时点观测**完全稳定**：

| 时点 | `00003` | `00081` | tfoot | 「前后端算值不一致」提示 |
|---|---|---|---|---|
| T0 保存前 | 233922.5 | 5204745 | 小计 ¥5438667.5 合计 ¥5438667.5 | 0 次 |
| T1 切走再切回 | 233922.5 | 5204745 | 同上 | 0 次 |
| T2 刷新整页 | 233922.5 | 5204745 | 同上 | 0 次 |

但 AC-9 的第一步「把 `00081` 行组成用量 12.3 改为 10」**做不到**，且 AC-9 的**落库一致**这半条**判定为不满足**（页面 5438667.5 vs 落库 0）。

### AC-10（边界·诊断）⚠️ 引擎层 PASS / 渲染层未验证

- **引擎层通过**：
  - 后端 `FormulaCalculatorCrossTabHostDiagTest.missingHostMatchKey_yieldsZeroAndDiagnostic` 绿 —— 断言 `outDiag.crossTabError` 非空、且同时含源页签名「材质元素」与匹配键名「不存在的字段」
  - 前端 `AC-10：匹配键写了组件里根本不存在的列名 → outDiag 给出可见诊断` 绿；**行为回退后这条会红**（诊断变 `undefined`），说明它接上了
  - 反向守卫也在：键在、值为空 → **不**写诊断（否则 BOM 根行会被 ⚠ 顶掉数值，直接违反 AC-4）
- 🚫 **渲染层（"该列显示可见诊断"）未验证**：要在 UI 上触发它，必须把 `COMP-0016` 的公式匹配字段改成一个不存在的字段名 —— 那是**改共享组件配置 = 全局状态**，本片写入面明令禁止（`testing.md §4.3`）。
  ⇒ 请主线裁决：是接受"引擎层已锁 + 渲染层由既有 `crossTabError` 展示位承担"，还是另开一个只属于本任务的一次性组件来验渲染。

### AC-11（自检证据）✅ 见 §5

---

## 2. 两处必做的还原实验（结果）

### 2.1 还原实验 1 —— AC-6 新夹具用例必须先跑红 ✅

见 AC-6 一节。补充说明**为什么不能用 `git stash`**：worktree 与前后端工程师**共用**，在里面 stash/checkout 会直接抹掉他们在途的改动（并且 `target/` 并发构建会互删 class，`testing.md §4.2.5`）。
⇒ 一律在 scratchpad 的**私有副本**里做，脚本 `mkiso.sh`（rsync 快照 + `git show HEAD:` 定点还原 + 私有 `target/`）。

还原的是**行为**不是整份文件：新夹具 harness 直接调用修复引入的生产方法（后端 `buildCurrentRowRaw` / `buildHostRowForMatch`，前端 `buildHostMatchRow`），整份回退 HEAD 会**编译不过**，那样得到的红是"编译错误"而不是"匹配 0 行"，证不出 AC-6 要的那件事。所以只回退唯一的行为开关点（后端 1 行、前端 1 行）。

### 2.2 还原实验 2 —— AC-1 的页面值 ✅

同一时刻、**同一个库、同一个后端（8093，带修复）**，只把前端引擎回退一行，另起 5093：

| 前端 | 端口 | `00003` | `00081` | tfoot |
|---|---|---|---|---|
| 修复态 | 5091 | **233922.5** | **5204745** | 小计 ¥5438667.5 |
| 行为回退态 | 5093 | **0** | **0** | 小计 ¥0 |

两次用的是**同一个 spec、同一段读取代码**（`R260911_MODE` 只切期望值，不切观测逻辑），所以"修复态绿"不可能是观测手段失灵。

额外一条独立佐证：在主仓 5090/8091（master 代码）上跑 `MODE=after`，硬失败 `Expected 233922.5 / Received 0`；且已核对主仓工作树的 `FormulaCalculator.java` / `formulaEngine.ts` / `QuotationStep2.tsx` / `CardSnapshotService.java` 与 worktree **HEAD 逐字节相同**，所以那次对照等价于"未修复态"。

**副产品结论**：页面上那两个数是**前端算出来的**（把后端修好、只回退前端 → 页面就回到 0）。后端修复的价值在落库那一侧 —— 而落库这条路径目前走不通，见 §3.2。

---

## 3. 🚨 需要主线裁决的两件事

### 3.1 AC-9 的「改组成用量」在本单**不可执行**（阻塞）

**事实（可复跑）**：

1. 页面 DOM：`00081` 行的组成用量单元格是 `<td><span class="qt-ds-value">12.3</span></td>` —— 纯展示。单击、双击后 DOM 不变，单元格内 `input` 数量恒为 `0`。
2. 配置层佐证（只读 SQL）：核价模板『核价通用1』的三个组件字段类型**全是 `BASIC_DATA` / `FORMULA`**，一个 `INPUT_*` 都没有：
   - `COMP-0016 BOM`：生产料号/项次/料号/材料名/工序编号/组成用量/组成用量单位/底数/底数单位/材料损耗率/材料固定损耗量/不良率 = 12 个 `BASIC_DATA`；物料成本 = `FORMULA`
   - `COMP-0017 材质元素`：8 个 `BASIC_DATA` + 元素成本 `FORMULA`
   - `COMP-0018 加工费`：5 个 `BASIC_DATA`
   ⇒ **整张核价卡片没有任何可编辑单元格**，不只是组成用量这一个。
3. 连带后果：`保存草稿` 有脏检查。不改任何东西点它，浮层显示 **「无改动，无需保存」**，且 20 秒内 `/api` 只有一次通知轮询，**0 次写请求**（`updated_at` 未变）。⇒ AC-9 的"保存"这一步同样触发不了。

**我没有做的事**：没有自行把 AC-9 改写成别的可测语义（那属于替用户重新定义验收标准）。

**给主线的三个候选**（我只给分析，不下结论）：
- 甲：改 AC-9 —— 把触发动作换成本单**报价侧**某个真正可编辑的 `INPUT_*` 单元格，序列的其余部分（保存 → 切视图 → 刷新 → 落库一致）不变
- 乙：改 AC-9 —— 去掉"改值"，只保留"保存 → 切视图 → 刷新 → 落库一致"（但见 3.2，这条现在也不满足）
- 丙：给核价通用1 加一个 `INPUT_*` 字段专供验收 —— **改共享组件配置 = 全局状态**，本片无权限，且会污染其它会话

### 3.2 页面已修好，但**落库的 `costing_card_values` 还是全 0**（AC-9 后半条不满足）

**事实（可复跑）**：

| 时点 | 页面显示 | 库里 `costing_card_values` BOM 小计 |
|---|---|---|
| 实验前 | 0 | `0`（sha256 `0e9900ea…`） |
| 修复态前端打开后 | 5438667.5 | `0`（sha 未变） |
| **还原态前端**触发一次**真实保存**（`PUT /draft` 200、浮层「草稿已保存」） | 0 | `0`（sha 未变） |
| **修复态前端**触发一次**真实保存**（`PUT /draft` 200、浮层「草稿已保存」） | 5438667.5 | **`0`（sha 仍是 `0e9900ea…`，逐字节未变）** |

- 为了让脏检查放行，我用的是**本报价单自己的「备注」字段**做脏触发（本单私有、可逆）；实验结束已把备注还原为原始 `NULL`（原值 `NULL` → 实验写入 → UI 只能清成 `''` → 我用带主键 `WHERE` 的单行 `UPDATE` 复位为 `NULL`，执行前先 `SELECT count(*)` 量化 = 1 行）。`total_amount` 全程 `698779.116841532000` 未变，`status` 仍 `DRAFT`。
- ⚠️ 这次"用备注当脏触发"是我为了取得这条信息而做的**替代动作**，不在派工给我的写入面原文里（原文是"改组成用量"，而那个格子不存在）。**据实登记，请主线复核是否接受**。

**我不下结论的部分**：这可能是「保存草稿本来就不重算/不落核价卡片值」的既有设计（修复前也是 0，看不出差别），也可能是本次修复没有覆盖到落库路径。判断需要读实现，那是开发/主线的活。

**用户可见的后果（如果是后者）**：核价单在页面上是对的，但导出 / 提交 / 比对视图 / 下游读 `costing_card_values` 的地方仍可能拿到 0。**建议主线在闸门 B 之前把这条查清。**

---

## 4. 过程中规避掉的坑（写下来是因为下次还会踩）

1. **拿主仓 8091/5090 验 worktree 改动 = 假绿第 3 类**。我另起了 8093 / 5091，并做了**验明正身**：`8093` 的监听进程 cwd 落在我的私有副本、日志里的 JDBC 是 `…/cpq_db_0910`；`5091` 的 vite 进程环境变量 `VITE_API_TARGET=http://localhost:8093`，且实测抓到它的出向 TCP `127.0.0.1:57281 -> 127.0.0.1:8093`。
   ⚠️ 中途差点用"8081 已死 ⇒ 代理只能是 8093"来证明 —— 复核时发现 8081 又活了，这条推理当场作废。**"当前无 X"是瞬时量，不能当安全前提。**
2. **同一 worktree 内与实现工程师并发跑构建会互删 class**（`testing.md §4.2.5`）。所有需要改文件的实验、以及我自己的后端实例，都放在 scratchpad 私有副本里跑（各有独立 `target/`）；只有最终那次定向测试跑在 worktree 里，且运行前采样确认无其它进程在该 worktree 内构建。
3. **`git stash` 做还原实验会抹掉同事在途的工作** —— 换成私有副本 + `git show HEAD:` 定点还原。
4. **`npx tsc --noEmit` / `tsc -b` 在本项目都可能是空验证**（详见 AC-8 一节），我按 `--listFiles` 计数 + 故意塞类型错误两步证明了新写法真的在检查。
5. **整页截图当证据是"恒真的东西"**：BOM 表在横向滚动容器里，物料成本是最右侧列。修复前/修复后的 `fullPage` 截图 **md5 完全相同**（`8da55034…`）—— 看着像证据，其实一个像素都没体现差异。改成"先把滚动容器滚到最右，再截卡片元素"后，两张图 md5 才分开（`4e661a40…` vs `27d4063c…`）。
6. **共享库的全局计数断言**：全部断言都限定到本单本卡片本行（按料号定位，且断言"该料号恰好命中 1 行"），没有任何「共 N 条」「合计为 X」这类跨单据聚合。
7. **项目默认的 `e2e/global-setup.ts` 会 `UPDATE` `cpq_db_0724` 的 `user` 表**（清锁 + `is_first_login=false`）。那是本片写入面之外的全局状态，所以我另写了不挂 globalSetup 的 `e2e/repair260911.config.ts`。
8. **AC-7 用逐字节 diff 会误报**：仅打开页面就会触发 number→string 归一（在**未修复**代码上实测），必须按数值等价比。

---

## 5. 已自检 / 环境与命令

**已自检**：后端定向测试 `Tests run: 146, Failures: 1, Errors: 1`（2 个失败已在纯 HEAD 副本上 A/B 证明为既有，与本次无关），其中 `FormulaCalculatorCrossTabFixtureTest` 54/54 ✅、`FormulaCalculatorCrossTabHostDiagTest` 7/7 ✅；前端 `vitest run src` 99 文件 / 1201 用例全绿 ✅；`tsc -p {app,test,e2e} --noEmit --incremental false` 三个全 `exit=0` ✅（写法已做证伪实验）；探活 前端 `5091 → 200` ✅ / 后端 `8093 → /api/cpq/components 401` ✅（并已验明正身连的是 `cpq_db_0910`）；AC-1/AC-2 的页面实测值 `00003=233922.5`、`00081=5204745`、`小计 5438667.5`（截图 + JSON 原始抓取已归档）。

**复跑命令**（都在 worktree 内）：

```bash
# 后端定向测试
cd <worktree>/cpq-backend
./mvnw -o test -Dtest='FormulaCalculatorCrossTabFixtureTest,FormulaCalculatorCrossTabHostDiagTest,FormulaCalculatorCrossTabTest,...'

# 前端单测 + 类型检查（🚫 不要用 npx tsc --noEmit，它什么都不查）
cd <worktree>/cpq-frontend
npx vitest run src
for p in tsconfig.app.json tsconfig.test.json e2e/tsconfig.e2e.json; do npx tsc -p $p --noEmit --incremental false; done

# 页面验收（MODE=after 断言修复值；MODE=before 断言全 0）
PW_BASE_URL=http://localhost:5091 R260911_MODE=after \
  npx playwright test -c e2e/repair260911.config.ts e2e/repair260911-ac1-ac2-ac4.spec.ts

# 落库取证（只读）
证据/S1-测试/evidence.sh <tag>
# 报价侧零回归比对（数值等价，非逐字节）
python3 证据/S1-测试/cmp_qcv.py <A>.qcv.json <B>.qcv.json
```

**本片新增的测试文件**（都在 `cpq-frontend/e2e/`，未提交，提交由主线统一做）：

| 文件 | 用途 |
|---|---|
| `repair260911.config.ts` | 本片专用 Playwright 配置（**刻意不挂 globalSetup**，见 §4.7） |
| `repair260911-helpers.ts` | 导航 / 取表 / 按料号定位行 / 横向滚动截图 |
| `repair260911-ac1-ac2-ac4.spec.ts` | AC-1 / AC-2 / AC-4，`R260911_MODE` 切 before/after 两套期望 |
| `repair260911-ac9.spec.ts` | AC-9 可执行部分（保存 → 切视图 → 刷新） |
| `repair260911-savetrace.spec.ts` | 追查"保存草稿"为什么不写库（抓浮层 + `/api` 往返） |
| `repair260911-savedirty.spec.ts` | §3.2 的补充实验（用备注弄脏表单 → 真实保存 → 观测落库） |
| `repair260911-probe*.spec.ts` | 4 个只读探针（DOM 侦察，可在合并前删） |

---

## 6. 一次性库待回收清单

**无。** 本片没有建任何库，全程只用 `cpq_db_0910`，且没有执行任何 `DROP` / `TRUNCATE` / 无 `WHERE` 的 `UPDATE`/`DELETE`。

对该库的写入只有三笔，全部限定在本报价单，且已还原：

| # | 写入 | 方式 | 还原情况 |
|---|---|---|---|
| 1 | `quotation.remarks`：`NULL` → 文本 → `''` | 应用 UI（保存草稿） | 已用带主键 `WHERE` 的单行 `UPDATE` 复位为 `NULL`（复核通过） |
| 2 | 两次 `PUT /draft` 引起的重算落库 | 应用 UI | `quote_card_values` 逐字节未变；`costing_card_values` 逐字节未变（见 §3.2） |
| 3 | 打开编辑页触发的 autosave（number→string 归一 24 个叶子） | 应用自动行为，**未修复代码上同样发生** | 数值未变；这是既有行为，不还原 |

## 7. 已知不覆盖

| 项 | 原因 |
|---|---|
| AC-9 的"改组成用量 → 4231500 / 4465422.5" | AC 在本单不可执行（§3.1），**未验证** |
| AC-9 的"与落库值一致" | 判定为**不满足**（§3.2），非"未验证" |
| AC-10 的渲染层（该列显示 ⚠ 诊断） | 触发它必须改共享组件配置 = 全局状态，本片无权限，**未验证** |
| `COMP-0005`（另一个受影响组件） | 库里没有任何在用单据，构造不出非空正向数据（`test.md §4` 已登记） |
| 存量核价单批量重算 | A0 已裁决本期不做 |
| 冷启动验证 | 未触发：本次不改依赖清单 / 启动配置 / 端口 / 迁移；但⚠️ 我在 worktree 里**用软链复用了主仓 `node_modules`**，没有在 worktree 内真正 `npm install` 过 —— 若主线认为这命中 `testing.md §5.1` 的"本任务在 worktree 里装过依赖"，请自行补一次 |
