# test-report · 移除主数据维护「料号核价」

- **执行人**：测试代理
- **执行日期**：2026-09-07
- **分支 / worktree**：`feat/task-260907-remove-part-costing` @ `.claude/worktrees/task-260907-remove-part-costing`
- **方案文件**：`test.md`（判据以 `需求文档.md §③` AC 原文为准；AC-3 / AC-5 / AC-7 / AC-14 已按主线 2026-09-07 的修正执行）

---

## 0. 一句话结论

**17 条 AC 中，我负责的 15 条里 13 条达成、1 条以「AC 措辞与既有实现冲突」失败（AC-2）、1 条部分未达成（AC-17 文档回写有缺口）。AC-12 / AC-13 归主线亲验，本报告不覆盖。**

🚨 **另有一件必须先看的事：§10.1 —— 共享库上残留 2 行脏数据，我无权自行复原，等你裁决。**
   起因是我的测试代码在「改回原值」后只打印不回读，谎报了一次「已复原」；已修，并顺带挖出一个
   **`dataset` 维护侧的疑似缺陷：项次 10→11 能存，11→10 后端判「数据无变化」静默丢弃**（A/B 已证非本次引入）。

🚨 **本轮所有「绿」都配了反向对照或还原实验** —— 删除类任务里，「不存在」是最容易被环境伪装出来的结果，详见每条的「归因」一行。

---

## 1. 测试环境（A/B 双侧同时在跑，这是本轮判据成立的前提）

| | A 侧 = 改动前（master） | B 侧 = 改动后（本分支） |
|---|---|---|
| 前端 | `http://localhost:5174`（主工作区共享 dev server） | `http://localhost:5177`（worktree 临时 vite，跑完已停） |
| 后端 | `http://localhost:8081`（主工作区共享） | `http://localhost:8099`（worktree 冷启动，跑完已停） |
| 库 | **同一个** `10.177.152.12:5432/cpq_db_0724` | 同左 |

🚫 **全程未停、未占用共享的 5174 / 8081**（它们保留给主线亲验）。收工探针：`8081 → 401`、`5174 → 200`、`8099 → 000`、`5177 → 000`。

⚠️ **一处必须写明的不对称**：`P_before` 跑在 5174/8081，`P_after` 跑在 5177/8099。两侧代码不同是本来就要测的，但**基础设施也不同**。缓解措施 = P_after 若出现 P_before 没有的失败，就单独回 5174/8081 做同型对照再归因。实际上 **P_after 未出现任何新增失败**，该风险未被触发。

---

## 2. AC 逐条结果

| AC | 结果 | 证据位置 |
|---|---|---|
| AC-1 页签集合 | ✅ | §3 T-1 |
| AC-2 默认落点 | ⚠️ **部分**：落点/表格/无红屏全过；「无 console.error」不达标（**非本次引入**，A/B 已证） | §3 T-2 |
| AC-3 导入入口行为 | ✅ | §3 T-3 |
| AC-4 维护端点下线 | ✅ | §3 T-4 + §4 A/B 表 |
| AC-5 导入端点下线 | ✅ | §3 T-5 + §4 A/B 表 |
| AC-6 代码物理删除 | ✅ | §5 T-6 |
| AC-7 零残留引用 | ⚠️ **实现侧全清（0/0/0），`e2e/` 余 18 处**，全部是判据本体或 AC 词表误伤 → 需主线裁决 | §5 T-7 |
| AC-8a 基础核价读写序列 | ✅ | §3 T-8a |
| AC-8b 详细核价读写序列 | ✅ | §3 T-8b |
| AC-8c 电镀方案只读形态 | ✅ | §3 T-8c |
| AC-9 切换与刷新 | ✅ | §3 T-9 |
| AC-10 后端冷启动 | ✅（**临时端口 8099**，声明端口 8081 未验，见偏差说明） | §5 T-10 |
| AC-11 角色边界 | ✅ | §3 T-11 |
| AC-12 V6 数据零变化 | 🔒 主线亲验 | — |
| AC-13 核价渲染零回归 | 🔒 主线亲验 | — |
| AC-14 构建与类型 | ✅（含证伪实验） | §5 T-14 |
| AC-15 E2E 不新增失败 | ✅ **差集为空集**（强形式，不必动用「被删用例」豁免） | §6 |
| AC-16 公共件内容零变化 | ⚠️ **一处第四类改动**（`EditableSheetTable` 的 `lookupFn`），不可避免 + 已加运行时守卫 | §7 |
| AC-17 文档回写 | ⚠️ **部分**：`main-api.md` / `BACKLOG.md` / `INDEX.md` 达成；`RECORD.md` 与 `INDEX.md` 的「按代码文件反查」行**未回写** | §5 T-17 |

---

## 3. E2E 执行结果（新增 spec `cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts`）

**最终轮：10 passed / 1 failed（5.5m）**，日志 `newspec-run2.log`，截图 25 张已归档到
`dev-docs/task-260907-移除料号核价功能/证据/e2e/`（🚫 不是 `test-results/` —— 那里下一轮开跑就被清空，等于没有证据）。

### T-1 / AC-1 ✅ 页签集合

```
[T-1] 实际页签 = ["材质","元素","工序","基础核价","详细核价","电镀方案"]
```
断言：集合逐字相等 + 不含被移除页签。截图 `01-T1-AC1-六页签.png`。
**归因**：绿是「功能对了」。用例先断言「读到的页签数 > 0」再断言「不含」，排除了「一个都没读到 ⇒ not.toContain 恒真」的空跑。

### T-2 / AC-2 ⚠️ 默认落点 —— 三分之二达成

```
[T-2] aria-selected=true 的页签 = "材质"                       ✅
[T-2] 材质页首屏行数=20；库 material_recipe 总行数=264            ✅（非空正向结果）
[T-2] pageerror = []                                          ✅
[T-2] console.error = [
  "Warning: [antd: Tabs] `destroyInactiveTabPane` is deprecated…",
  "Warning: [antd: Modal] `destroyOnClose` is deprecated…",
  "Warning: [antd: Drawer] `width` is deprecated…" ]           ❌ AC-2 原文要求「无 console.error」
```

**A/B 归因（testing.md §4.1.5：确定性复现 ≠ 缺陷）**：同一探针在 **master(5174) 默认落点上就有 2 条**同族告警（Tabs / Drawer）。本分支多出的第 3 条 `[antd: Modal] destroyOnClose` 来自**材质页组件**，该组件本任务一个字节没改 —— 它只是因为默认落点改成「材质」而提前渲染。
⇒ **三条都是 antd 6.3.5 的弃用告警，不是本次引入的缺陷**；但 AC-2 原文写的是「无 console.error」，字面上无法满足。
🚫 **我刻意没有加白名单把它变绿**（那是降级断言）。请主线裁决：改 AC（收窄成「无 pageerror + console.error 无新增」）还是改代码（把 3 个弃用 prop 换成新名）。

### T-3 / AC-3 ✅ 导入入口行为（判据已按主线修正重写）

```
[T-3] 「基础核价」导入抽屉标题 = "导入核价数据·基础核价"          ← ① 反向对照：入口仍在且可点开
[T-3] 「基础核价」上传阶段发出的导入类请求 = ["POST /dataset/cost-basic/import"]   ← ②
[T-3] 「详细核价」导入抽屉标题 = "导入核价数据·详细核价"
[T-3] 「详细核价」上传阶段发出的导入类请求 = ["POST /dataset/cost-detail/import"]  ← ②
[T-3] 本次共抓到 12 条 /api/cpq 请求；旧端点命中 = []              ← ③
电镀方案只读提示逐字保留                                          ← ④
```
**归因**：③「旧端点一次都没出现」这种否定断言最容易空跑，所以先断言「整轮抓到的 /api/cpq 请求数 > 0」（实测 12 条）证明观察通道确实接上了，再断言旧端点命中为 0。②同理：先断言「导入类请求数 > 0」再断言 URL。
📌 上传用的是**故意构造的非法 xlsx**（`Buffer.from('not-a-real-xlsx')`）—— 整份拒收语义保证请求发得出去但一行都不写进共享库。

### T-4 / AC-4 ✅ 维护端点下线 + 反向对照

```
[T-4] GET /pricing-basic-data/parts  -> 404  body={"code":404,"message":"Not found"}
[T-4] GET /pricing-basic-data/sheets -> 404  body={"code":404,"message":"Not found"}
[T-4 反向对照] GET /dataset/cost-basic/parts -> 200
              data.total=11, items[0].axisValue="2120011658" …（data 非 null）
```
**归因**：反向对照**先执行、先断言**。它 200 且 `data` 非空，证明后端在正常服务 ⇒ 上面两条 404 才是「端点被删」的证据，而不是「进程没起来」。

### T-5 / AC-5 ✅ 导入端点下线 —— 主判据是 A/B 变化量

```
[T-5·B 本分支] POST /v6/pricing          -> 405
[T-5·B 本分支] GET  /v6/pricing/template -> 404
[T-5·B 本分支] POST /v6/zzz-not-a-path   -> 405   ← 阳性对照
[T-5·A master ] POST /v6/pricing         -> 401   ← 主判据：401 → 405
[T-5·A master ] POST /v6/zzz-not-a-path  -> 405
[T-5 反向对照] GET /v6/{实查 recordId} -> 200，data.importRecordId 回填、status=SUCCESS
```
**归因**：405 这个**绝对值不是证据** —— 乱打 `/v6/zzz-not-a-path` 在 master 上本来就是 405（`/{recordId}` 模板的既有兜底）。用例内置了一条**阳性对照断言**：master 上 `/pricing`(401) 与 `/zzz`(405) 的状态码必须不同，否则该观察通道分辨不出「端点存不存在」，A/B 判据自动作废。两者确实不同 ⇒ 「401 → 405」是端点被删的证据。
`recordId` 每轮实查（本轮 `12014545-…`，与写报告时的 `417b8558-…` 不同 —— 正说明没写死）。

### T-8a / AC-8a ✅ 基础核价读写序列（本任务最大风险点）

```
[T8a] 库中 3120014539 当前版本行数 = {"物料BOM":8,"元素BOM":7,"装配工序费":4}   ← 前置实查，非写死
[T8a] sheet「物料BOMv7 · 8」        UI 行数=8，库期望=8
[T8a] sheet「物料与元素BOMv1 · 7」  UI 行数=7，库期望=7
[T8a] sheet「加工费&组装费v1 · 4」  UI 行数=4，库期望=4
[T8a] AC 名 → 实际 tab 名映射 = ["物料BOM→物料BOMv7 · 8","元素BOM→物料与元素BOMv1 · 7","装配工序费→加工费&组装费v1 · 4"]
[T8a] 改值："10" -> 11 → 保存提示 = ["已升版至 v8"] → 关抽屉重开 → 读回 = "11" ✅
[T8a] 已复原为 "10"   ← 🚨 **这行是谎报**，实际没写回去，见 §10.1
```
截图 `04~08`。**中间态**（三个 sheet 都出真实行）与**最终态**（值已持久化）都断言了。
🚨 **AC-8a 的判据本身达成，但步骤 8「改回原值」没有真的生效** —— 用例当时只打印不回读，属我的测试代码缺陷，
   已修（见 §10.1），残留数据已量化上报，等主线裁决。
**归因**：三个 sheet 的期望行数**当场查库**，且前置断言「期望行数 > 0，为 0 就硬失败并要求换夹具」——排除「挑了个没数据的料号 ⇒ 表格空 ⇒ 渲染正常全过」的空跑。UI 行数与库行数逐一相等，不是「> 0 就算过」。

### T-8b / AC-8b ✅ 详细核价读写序列

```
[T8b] 库中当前版本行数 = {"物料BOM":8,"产能":4,"设备折旧":4}
[T8b] 「物料BOMv1 · 8」8=8 ／「产能v1 · 4」4=4 ／「设备折旧成本v1 · 4」4=4
[T8b] 改值："10" -> 11 → ["已升版至 v2"] → 重开读回 "11" ✅
[T8b] 已复原为 "10"   ← 🚨 同上，见 §10.1
```
截图 `09~13`。归因同 T-8a，**包括步骤 8 复原未生效那一条**（§10.1）。

### T-8c / AC-8c ✅ 电镀方案只读形态

```
[T-8c] 库 ds_cost_detail_plating_scheme = 2 行（前置：为 0 则硬失败）
[T-8c] 数据集下拉默认值 = "报价" → 切「详细核价」
[T-8c] UI 行数=2，库=2                                  ← ①
       说明文案逐字命中「电镀方案为导入维护，如需修改请通过「导入报价数据」/「导入核价数据」重新导入」 ← ②
[T-8c] 工具栏按钮 = ["刷新"]                             ← ③ 无 新增/编辑/删除/保存
[T-8c] 点击前后表内 input 数 = 0 -> 0                    ← ④ 点单元格不进编辑态
```
截图 `14`。**归因**：④ 是「点了之后不出现某物」的否定断言，靠 ① 的「表格确有 2 行、且行可点」保证它不是在一张空表上空跑。

### T-16b / AC-16 守卫 ✅（**我自己加的，不在原 test.md 里**）

```
[T-16b] 「物料BOMv8 · 8」表内 antd Select 单元格数 = 8
[T-16b] 下拉候选数 = 8，样例 = ["TP10 · 测试工序10","TP20 · 测试工序20","Z002 · 包装","Z008 · 成品清洗","Z053 · 铣割"]
```
截图 `15`。**为什么加它**见 §7 —— `EditableSheetTable` 的 `lookupFn` 默认值随 legacy `lookup` 一起被删，漏传时**不会报错，只会静默变成空下拉**。
**归因**：断言的是「候选**非空**」而不是「下拉能打开」—— 回归后的样子恰恰是「能打开但恒空」。

### T-9 / AC-9 ✅ 切换与刷新

```
材质(默认) → 详细核价 → 工序 → 材质，每次 aria-selected 与目标一致、内容区非空、无「加载中…」
[T-9] 刷新后选中页签 = "材质"   ← activeTab 不持久化，保持现状
```
截图 `16~19`（各页签内容区前 120 字已打印在日志里，可复核确实是该页签的内容而非上一个页签的残留）。

### T-11 / AC-11 ✅ 角色边界（PRICING_MANAGER）

```
[T-11] 以 PRICING_MANAGER = t260903_pm 登录
[T-11] 左侧菜单「主数据维护」命中数 = 2                      ← ①
[T-11] 看到的页签 = ["材质","元素","工序","基础核价","详细核价","电镀方案"]  ← ② 同 AC-1
[T-11] 抽屉「物料BOM」行数=2 input=22 保存按钮=1              ← ③
```
截图 `20`。**归因**：③ 之前先断言「列表行数 > 0」「抽屉 sheet tab 数 > 0」「该 sheet 行数 > 0」，逐层排除空跑；并且**先切到确实有数据的 sheet 再断言保存按钮**（`product-hub-readonly.spec.ts:365` 记着这个坑：默认 sheet 无数据时本来就没有可保存的东西，不切 tab 就断言会把「默认 tab 无数据」误报成「被改成只读了」）。

---

## 4. 端点 A/B 对照总表（curl，未鉴权，同一时刻两侧同一请求）

| 请求 | master(8081) | 本分支(8099) | 判定 |
|---|---|---|---|
| `GET /api/cpq/pricing-basic-data/parts?page=1&size=1` | 401 | **404** | AC-4 端点已下线 |
| `GET /api/cpq/pricing-basic-data/sheets` | 401 | **404** | AC-4 端点已下线 |
| `GET /api/cpq/basic-data-import/v6/pricing/template` | 401 | **404** | AC-5 端点已下线 |
| `POST /api/cpq/basic-data-import/v6/pricing` | 401 | **405** | **AC-5 主判据：401 → 405** |
| `POST /api/cpq/basic-data-import/v6/zzz-not-a-path` | 405 | 405 | 🔁 阳性对照：405 绝对值**不是**证据 |
| `GET /api/cpq/dataset/cost-basic/parts?page=0&size=1` | 401 | 401 | 🔁 反向对照：保留端点两侧都活 |
| `GET /api/cpq/basic-data-import/v6/{recordId}` | 401 | 401 | 🔁 反向对照：**删的是方法不是整个类** |
| `GET /api/cpq/components` | 401 | 401 | 🔁 进程存活对照 |

**归因**：如果 8099 没起来，整列会是 `000`，而不是一半 401 一半 404/405 —— 进程级假绿由这张表本身排除。

原始输出：`证据/shell-ac-evidence.md`。

---

## 5. Shell 类 AC

脚本 `证据/verify-shell-ac.sh`（可重跑），本轮输出 `证据/shell-ac-evidence.md`。

### T-6 / AC-6 ✅ 代码物理删除 + 反向对照

```
✅ GONE:   cpq-backend/src/main/java/com/cpq/basicdata/v6/maintenance
✅ GONE:   cpq-frontend/src/pages/master-data/part-costing
✅ GONE:   cpq-frontend/src/pages/master-data/PricingBasicDataImportDrawer.tsx
-- 🔁 反向对照 --
✅ EXISTS: shared/EditableSheetTable.tsx, shared/SheetPartListTab.tsx, shared/types.ts, shared/sheetApiFactory.ts
✅ EXISTS: cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing
```
**归因**：只验「不存在」会被「路径写错」骗过去 —— 反向对照的 5 条同形式 `test -e` 全部命中，证明这套判断本身是有效的。

### T-7 / AC-7 ⚠️ 零残留引用 —— 实现侧全清，`e2e/` 余 18 处

**① 还原实验（先做，否则整轮是白测）**
```
插哨兵后命中 19 行 → 撤哨兵后命中 18 行 → 差值 = 1  ✅ 扫描确实生效
```
（哨兵刻意放在 `cpq-frontend/e2e/.__ac7_falsify_sentinel.ts` 而不是按 test.md §5 原稿改 `src/main.tsx` + `git checkout` —— 那时前端代理可能正在改该文件，`git checkout` 会连人家未提交的改动一起打掉。）

**② 正式扫描**（范围已按主线修正排除 `db/migration/`）
```
cpq-backend/src/main/java      0   ✅
cpq-backend/src/test/java      0   ✅
cpq-frontend/src               0   ✅
cpq-frontend/e2e              18   ⚠️
```

**18 处的逐条归类（🚫 我没有为了凑 0 而删掉判据）**：

| 处数 | 位置 | 性质 |
|---|---|---|
| 3 | 我的 spec T-4：`GET /api/cpq/pricing-basic-data/parts` / `/sheets` 的**请求路径与断言** | **AC-4 的判据本体**，删掉就没法验「端点已下线」 |
| 3 | 我的 spec T-4 的 console.log 与断言消息（同上路径） | 同上，是失败时的可读性 |
| 1 | 我的 spec T-3：`u.includes('/pricing-basic-data')` 网络过滤 | **AC-3③ 的判据本体** |
| 3 | 我的 spec T-1：`not.toContain('料号核价')` 及其上下文 | **AC-1 原文点名的反向断言本体** |
| 1 | `dataset-maintenance.spec.ts:84` `const REMOVED_TAB = '料号核价'` | 同上（我已把该文件里所有其它字面量改写掉，只留这一处常量） |
| 5 | 我的 spec 头部与截图归档路径里的**任务目录名** `task-260907-移除料号核价功能` | 任务目录就叫这个名字，改不了 |
| 1 | `repair0805-three-views.spec.ts:263` 注释「该料号核价侧…」 | **词表误伤**（是「该料号／核价侧」的分词巧合，不是页签名）；且 `test.md §4.3` 明令此文件不改 |
| 1 | `dataset-maintenance.spec.ts` 注释里引用我的 spec 文件名 | 已通过改文件名消除大部分（见下） |

🚩 **一个我自己造出来又自己消掉的坑**：新 spec 我最初命名为 `task260907-remove-part-costing.spec.ts`，**文件名本身含 `part-costing`**，会让 AC-7 永远无法归零。已 `git mv` 改名为 **`task260907-remove-legacy-costing-tab.spec.ts`**。

🚦 **请主线裁决**：AC-7 的词表与 AC-1 / AC-3 / AC-4 的判据本体**互斥** —— 要验「`/pricing-basic-data` 返 404」就必须在 e2e 里写出这个字符串。建议按已有的两次先例（`db/migration/`、`dev-docs/`）再排除一类：**本任务自己的验收用例文件**。理由同源：删除类验收用例必须引用被删对象的标识，才能证明它被删了。

### T-10 / AC-10 ✅ 后端冷启动

```
① mvnw -q clean（清 target/classes，防「删了 java 源文件但旧 class 还在」的陈旧产物假绿）
② quarkus:dev -Dquarkus.http.port=8099 → 第 24s 就绪
Successfully validated 400 migrations (execution time 00:00.088s)
Current version of schema "public": 417 … Schema "public" is up to date
UnsatisfiedResolutionException = 0 ／ DeploymentException = 0 ／ ClassNotFoundException = 0
GET /api/cpq/components -> 401   GET /api/cpq/health -> 200
```

⚠️ **与 AC-10 原文的一处偏差，必须记在这里**：AC-10 写「先停掉已有 8081 进程」再冷启。**我没有停 8081** —— 那是全会话共享的 dev server，主线亲验与用户验收都靠它，停它属于 `CLAUDE.md §3.2`「环境销毁」。
⇒ 「应用能在**它自己声称的端口 8081** 上绑起来」这一条**我证明不了**，归主线亲验（`testing.md §5`：临时端口跑绿 ≠ 声明端口能绑）。脚本 `证据/coldstart-temp-backend.sh` 可直接复用。

### T-14 / AC-14 ✅ 构建与类型（含证伪实验）

**① 先证伪，再采信**（主线交代的通则：凡把某条命令当判据，先证明它真的会红）
故意把 `PlatingSchemeTab.tsx:20` 的 import 改成不存在的 `'../NOPE_DOES_NOT_EXIST/types'`：

| 命令 | exit | 输出 | 判定 |
|---|---|---|---|
| `npx tsc --noEmit`（AC-14 **原**判据） | **0** | **0 行** | 🚨 **空验证** —— 我独立复现了主线的结论 |
| `npx tsc --noEmit -p tsconfig.app.json` | **2** | `TS2307: Cannot find module '../NOPE_DOES_NOT_EXIST/types'` | ✅ 有牙 |
| `npx tsc -b` | **2** | 同上 | ✅ 有牙 |

还原后 `grep NOPE_DOES_NOT_EXIST = 0`，工作树只剩本任务本应有的那一行 import 改动。

**② 正式检查（全绿）**
```
npx tsc --noEmit -p tsconfig.app.json   → exit 0
npx tsc --noEmit -p tsconfig.test.json  → exit 0
npm run build                           → exit 0（built in 1.19s；仅 chunk 体积告警，非错误）
./mvnw -q clean compile                 → exit 0
./mvnw -q test-compile                  → exit 0
```
📌 `test-compile` 通过，说明后端删的 3 个测试类与改过的 `MasterDataLayoutEndpointsTest` / `DatasetQuoteAndRegressionAcTest` 都能编译。
🚫 **我没有跑 `mvnw test`** —— 它直接写共享开发库 `cpq_db_0724`（`CLAUDE.md` 的 profile 表），本轮刻意回避；后端单测归后端代理与主线。

### T-17 / AC-17 ⚠️ 文档回写 —— 有缺口

```
main-api.md  'basic-data-import/v6/pricing'   = 0   ✅ 期望 0
main-api.md  '核价基础数据导入（同步）' 小节    = 0   ✅ 期望 0
🔁 反向对照 'basic-data-import/v6/quote'       = 2   ✅ 期望 ≥1（证明删的是一节不是整章）
docs/BACKLOG.md  'BL-0214'                     = 1   ✅
dev-docs/INDEX.md 'task-260907-移除料号核价功能' = 1   ⚠️ 见下
docs/RECORD.md   本任务条目                    = 0   ❌
```

**两处未达成，都不在我的职权内**（`RECORD.md` 由主线统一回写）：
1. ❌ **`docs/RECORD.md` 没有本任务的 `[2026-09-07]` 条目**。现有的 `[2026-09-07]` 是**另一条任务线**（取数配置器）的记录。
2. ⚠️ **`dev-docs/INDEX.md` 里本任务只有一行占位**（`:29`），且是由 `task-260907-取数配置器补齐` 会话**代为登记**的，状态仍写「立项中 · 待闸门 A」。AC-17 还要求「『按代码文件反查』表中 `MasterDataHubPage.tsx` 一行已更新为 6 页签」——**未见更新**（`:245` 仍是 task-260728 的旧描述，`:334` 的 task-260902 行仍列着已删除的 `PartCostingTab.tsx` / `PartCostingDrawer.tsx`）。

---

## 6. AC-15 · E2E 套件不新增失败 ✅（差集为**空集**）

### 6.1 spec 处置

**整份删除 4 个**（`git rm`，可 `git checkout master -- <path>` 完整恢复；共 315 行）：
`tc0712-part-costing-smoke` / `tc0712-edit-flow` / `tc0712-roles` / `verify260902-ac42`

**删除前先跑了一遍**（这是「被有意删除的用例」的唯一来源）：

| spec | P_before 结果 | 通过用例名 |
|---|---|---|
| `tc0712-part-costing-smoke` | 0 passed / 1 failed | （无） |
| `tc0712-edit-flow` | 0 passed / 1 failed | （无） |
| `tc0712-roles` | 0 passed / 3 failed | （无） |
| `verify260902-ac42` | **1 passed** | `AC-42 · 料号核价页签结构取证` |

🔎 **顺带查清的一件事**：3 个 `tc0712-*` spec **早已是死代码** —— 它们把前端地址**硬编码成 `http://localhost:5176`**（task-0712 当年的临时服务，早就没了），所以在 master 上本来就 0 通过。删掉它们对差集判据零影响。

**改造 5 个**：`dataset-maintenance` / `dataset-plating-scheme` / `product-hub-fs` / `product-hub-edit-fs` / `product-hub-readonly`（逐处改动见 §8）。

### 6.2 差集计算

| # | spec › 用例 | P_before | P_after |
|---|---|---|---|
| 1 | dataset-maintenance › TE-01 | ✅ | ✅ |
| 2 | dataset-plating-scheme › TH-01 | ✅ | ✅ |
| 3 | dataset-plating-scheme › TH-03 | ✅ | ✅ |
| 4 | product-hub-fs › FS-1a | ✅ | ✅ |
| 5 | product-hub-fs › FS-2 | ✅ | ✅ |
| 6 | product-hub-fs › FS-3 | ✅ | ✅ |
| 7 | product-hub-edit-fs › FS-1 | ✅ | ✅ |
| 8 | product-hub-edit-fs › FS-2 | ✅ | ✅ |
| 9 | product-hub-edit-fs › FS-3 | ✅ | ✅ |
| 10 | product-hub-edit-fs › FS-4 | ✅ | ✅ |
| 11 | product-hub-readonly › E2E-01 | ✅ | ✅ |
| 12 | product-hub-readonly › E2E-02 | ✅ | ✅ |
| 13 | product-hub-readonly › E2E-03 | ✅ | ✅ |

```
P_before (13) − P_after (13) − {被删用例} = ∅        ✅ AC-15 达成
```
而且是**强形式**：差集在扣减「被删用例」之前就已经是空集。

各 spec 汇总（P_before → P_after）：

| spec | P_before | P_after |
|---|---|---|
| dataset-maintenance | 1 passed / 9 failed（10 条） | 1 passed / 8 failed（9 条，TR-01 已删） |
| dataset-plating-scheme | 2 passed / 2 failed | 2 passed / 2 failed |
| product-hub-fs | 3 passed | 3 passed |
| product-hub-edit-fs | 4 passed | 4 passed |
| product-hub-readonly | 3 passed / 1 failed / 13 did not run | 3 passed / 1 failed / 13 did not run |

### 6.3 既存失败的归因（🚫 别把它们算成本次回归）

- **`dataset-maintenance` 的 8 条 / `dataset-plating-scheme` 的 2 条**：改动前就红，两类根因 ——
  ① **夹具口径不一致**（`TEST-DS-3120014539` 与带版本表的轴不一致，spec 里 `assertTemplateAxisMismatchBlocked()` 主动抛「阻塞：模板口径待修」）；
  ② 🚨 **antd 6.3.5 选择器腐化**（见 §9），这两个 spec 里仍在用已消失的 `.ant-drawer-content` / `.ant-select-selection-item`。
- **`product-hub-readonly` 的 E2E-04 + 13 did not run**：E2E-04（销售产品总数 == 库 `count(*)`）在改动前就红（共享库行数漂移）；该文件 `test.describe.configure({ mode: 'serial' })`，一条失败后面全部级联跳过。**两侧完全一致**。
  ⚠️ 副作用：我改造的 **E2E-09 在两侧都属于「did not run」**，所以它**没有 P_before 基线**，AC-15 的差集覆盖不到它。E2E-09 的改造是否有效，改由 `product-hub-fs › FS-1a` 与 `product-hub-edit-fs › FS-4` 背书（同一改法、同一载体、两侧都真跑且都通过）。
- **主线特意提醒的 `DatasetQuoteAndRegressionAcTest`**：那是后端 JUnit，不在本节的 Playwright 差集里，本轮我未运行（见 T-14 说明）。

### 6.4 一条侧面证据：公共件迁移后核价侧仍可编辑

`FS-1a` / `FS-4` 是「拿一个已知可编辑的抽屉去撞 `assertReadOnly`，它必须抛错」的阳性对照。改造后载体换成**基础核价**抽屉，两条**都通过**：

```
P_before：[FS-1a] 核价抽屉      input=96 保存=1     （载体 = 已删的核价维护页签 / S-3120014539）
P_after ：[FS-1a] 基础核价抽屉  input=88 保存=1     （载体 = 基础核价 / 3120014539 物料BOM）
```
input 数 96→88 是**换了载体导致的列数不同**，不是回归；关键不变量「有编辑控件 + 保存按钮 = 1」两侧一致。

---

## 7. AC-16 · 公共件内容零变化 —— ⚠️ 一处「第四类改动」

我按 AC-16 要求逐 hunk 审阅了 `git diff -M -C master -- .../master-data/`：

| 文件 | 改动类别 | 判定 |
|---|---|---|
| `shared/types.ts` | 只有文件头与字段注释文字 | ✅ 第 2 类 |
| `shared/SheetPartListTab.tsx` | 只有文件头与 prop 注释文字 | ✅ 第 2 类 |
| `shared/sheetApiFactory.ts` | `createSheetApi` **函数体一个字节未改**；删掉的是 7 个 legacy 具名导出 + `legacy` 实例 + `BASE` + `PartSortBy`；`LookupFn` 由 `typeof lookup` 改为等价的显式签名 | ✅ 第 3 类（AC-16 明确允许） |
| **`shared/EditableSheetTable.tsx`** | import 路径 ✅ + 注释 ✅ + **`MasterSelectCell` 的 `lookupFn` prop 定义与函数体** | ❌ **第 4 类** |

```diff
- const { value, currentLabel, master, onPick, lookupFn = lookup } = props;
+ const { value, currentLabel, master, onPick, lookupFn } = props;
- const r = await lookupFn(master, kw);
+ const r = (await lookupFn?.(master, kw)) ?? { items: [] };
```

**结论：这处第四类改动不可避免** —— 那个默认值指向的正是 AC-6 要求删掉的 legacy `lookup` 导出。**AC-16 与 AC-6 在这一点上互斥。**

**风险与我的处置**：漏传 `lookupFn` 时**不会报错，只会静默变成空下拉**。我为此加了 **T-16b 运行时守卫**（§3），实测下拉返回 8 个非空候选。
（主线另行裁决把该 prop 改成必填，让 TS 在编译期也拦一道 —— 编译期 + 运行时两层互补。）

🚦 **请主线在闸门 B 汇报里明写这一条**：AC-16 字面上未 100% 达成，是 AC 之间的冲突，不是实现越界。

---

## 8. 5 个 spec 的改造清单

| spec | 改了什么 | 服务的 AC |
|---|---|---|
| `dataset-maintenance.spec.ts` | `HUB_TABS` 7→6 项；TE-01 断言改 6 项集合 + 反向断言；**TR-01 / AC-42 整块删除**（被测对象已不存在）；把注释里的字面量改写掉，只留 `REMOVED_TAB` 一处常量 | AC-1、AC-15 |
| `dataset-plating-scheme.spec.ts` | `HUB_TABS_7` → `HUB_TABS_6`；TH-01 断言 7→6；「电镀方案在最后」改用 `tabs[tabs.length-1]`（🚫 不写死下标，下次加减页签才不会静默指错） | AC-1、AC-15 |
| `product-hub-fs.spec.ts` | FS-1a 载体：已删页签 → **基础核价**；选择器 `getByText(...).first()` → `getByRole('tab',{exact})`；料号 `HERO` → 新增 `COST_HERO='3120014539'` | AC-8a（轻量）、AC-15 |
| `product-hub-edit-fs.spec.ts` | FS-4 / RG-5 同上；失败消息补第三种归因（夹具漂移 / 对照失效 / 产品缺陷） | AC-8a（轻量）、AC-15 |
| `product-hub-readonly.spec.ts` | E2E-09 反向断言载体换基础核价 + `COST_HERO`；文件头注释里的已删目录名改写 | **AC-11**、AC-15 |

🚨 **一个只换页签、不换料号就会踩的坑**（我实查后避开了）：本 spec 主角 `HERO = 'S-3120014539'` 是**销售料号**（`ds_quote_*` 轴），而基础核价/详细核价的轴是**生产料号**。实测 `S-3120014539` 在 `ds_cost_basic_material_bom` / `ds_cost_detail_material_bom` 里**各 0 行**，`3120014539` 各 8 行。沿用 `HERO` 会让抽屉全空 ⇒ 阳性对照失效，而且**会伪装成「公共件被改坏了」这种产品缺陷**。

---

## 9. 🚨 过程中规避掉的坑（写下来，下一个人一定会再踩）

### 9.1 antd 6.3.5 选择器腐化 —— 差点被我报成产品缺陷

第一轮 P_after 我的新 spec **7 failed**，失败形态全是 `抽屉没打开` / `locator.textContent: Timeout`，**长得和「删除把页面弄坏了」一模一样**。

按 `testing.md §4.1.5`「报缺陷前先问：页面呈现与底层数据是否自洽」，我写了一个只打印 DOM 事实的探针，结果：

```
PROBE .ant-drawer count          = 1     ← 抽屉其实开着
PROBE .ant-drawer-content count  = 0     ← 🚨 这个类名在 antd 6 没了
PROBE role=dialog count          = 1
PROBE drawer title = "基础核价 · 3120014539主料1 ｜ 3.5×3.5×0.6"
PROBE drawer tabs  = ["物料BOMv7 · 8","物料与元素BOMv1 · 7", … "加工费&组装费v1 · 4", …]
PROBE .ant-select-selection-item = 0     ← 🚨 同样没了，选中值在 .ant-select 的 innerText 里
```

⇒ **抽屉、数据、行数徽标全都是好的**，坏的是我从 `dataset-maintenance.spec.ts` 抄来的选择器。改成 `.ant-drawer` / 读 `.ant-select` innerText 后 **7 failed → 1 failed**（只剩 §3 T-2 那条 AC 措辞问题）。

📌 **这解释了 `dataset-maintenance` / `dataset-plating-scheme` 在 master 上的一部分既存失败** —— 它们仍在用老类名。**修它们不属于本任务范围**，已登记在这里。

### 9.2 我给自己造的 AC-7 死结

新 spec 最初叫 `task260907-remove-part-costing.spec.ts` —— **文件名本身含 `part-costing`**，AC-7 永远归不了零。已改名 `task260907-remove-legacy-costing-tab.spec.ts`。
**教训**：删除类任务里，**验收用例自己的命名也在扫描范围内**。

### 9.3 sheet 名对不上（已上报，主线已采纳进 AC）

AC-8a/8b 用的是**库表口径**的名字，UI 页签名并不逐字相同（`元素BOM` → UI 叫 `物料与元素BOM`；`装配工序费` → UI 叫 `加工费&组装费`；`设备折旧` → UI 叫 `设备折旧成本`）。
我**没有自行改 AC**，做法是别名映射 + **把实际命中的 tab 名打印出来**，免得读报告的人以为用例偷换了对象。而且 UI tab 文本带行数徽标（`物料BOMv7 · 8`），精确相等匹配也会失败 —— 用 `startsWith` 才匹配得上。

### 9.4 AC-7 还原实验的做法我改了一处

`test.md §5` 原稿是「往 `cpq-frontend/src/main.tsx` 追加一行 → 扫描 → `git checkout` 还原」。
我改成在 `cpq-frontend/e2e/` 下建一个临时哨兵文件再删。**理由**：写方案时前端代理可能正在改 `main.tsx`，`git checkout` 会连人家未提交的改动一起打掉。

### 9.5 `product-hub-readonly` 是 serial 模式

`test.describe.configure({ mode: 'serial' })` —— 一条失败，后面 13 条全部「did not run」。做 AC-15 差集时如果只看「passed 数」而不看用例名，会把级联跳过误算成回归。

---

## 10. 本轮改变了哪些共享状态（`testing.md §4.3` 登记项）

| 动作 | 范围 | 是否已还原 |
|---|---|---|
| AC-8a 改值保存 | `ds_cost_basic_material_bom`（料号 `3120014539`）：v7 → v8 | 🚨 **未还原，见 §10.1** |
| AC-8b 改值保存 | `ds_cost_detail_material_bom`（同料号）：v1 → v2 | 🚨 **未还原，见 §10.1** |
| AC-3 上传非法文件 | `POST /dataset/{ds}/import` ×2 | ✅ 整份拒收语义，**一行未写库**；可能新增 2 条失败态 `import_record` |
| global-setup | `UPDATE "user" SET locked_until=NULL … WHERE username IN ('admin','alice','bob')` | 既有行为（解锁，不改启停用态） |

### 10.1 🚨 停下来报告：共享库上残留 2 行脏数据，我无权自行复原

**现象**：AC-8a / AC-8b 的「改回原值」在 `finally` 里执行了，用例日志也打印了「已复原为 10」，
**但值根本没写回去** —— 我在收工核对时查库才发现。

**根因方向（🚫 不下结论，根因定位归开发/主线）**：复原保存的 PUT **返回 200 但内容是**

```
PUT /api/cpq/dataset/cost-basic/parts/3120014539/sheets/MATERIAL_BOM/rows
  -> 200 {"code":200,"message":"success",
          "data":{"result":"UNCHANGED","versionNo":8,"rowCount":8,"message":"数据无变化，未升版"}}
PUT /api/cpq/dataset/cost-detail/parts/3120014539/sheets/MATERIAL_BOM/rows
  -> 200 {"code":200,"message":"success",
          "data":{"result":"UNCHANGED","versionNo":2,"rowCount":8,"message":"数据无变化，未升版"}}
```

⇒ **`item_seq`（项次）10 → 11 能存下并升版，反方向 11 → 10 却被判定为「数据无变化」，编辑被静默丢弃。**
这个不对称性发生在 `task-260902` 的 dataset 维护写入链路上，**本任务一个字节都没改它**；
我用 master 侧的 8081 + 5174（改动前环境）复原时同样是 UNCHANGED，两侧行为一致 ⇒ **不是本次改动引入的**。

**影响面（只读手段量化，§3.2 第 1 步）**：

| 表 | id | 料号 | 版本 | `item_seq` 现值 | 应为 |
|---|---|---|---|---|---|
| `ds_cost_basic_material_bom` | `6853` | `3120014539` | 8 | **11** | 10 |
| `ds_cost_detail_material_bom` | `15` | `3120014539` | 2 | **11** | 10 |

**共 2 行，各 1 个字段**。其余列逐字未变（已用 `EXCEPT` 双向比对全列确认，差异只有这一处）。

**可恢复性（§3.2 第 2 步）**：**可恢复** —— 原值仍在历史表里（`ds_cost_basic_material_bom_history` v7 与
`ds_cost_detail_material_bom_history` v1 各 1 行 `item_seq=10`），随时可查可回填。
**业务影响判断**：`item_seq` 是项次/排序序号，同组其余行是 20/30/…，`11` 仍排第一 ⇒ **渲染顺序不变**。

**🚦 请主线裁决（§3.2 第 3 步：我没有批准权）**：
1. 用 SQL 直改回 10 —— 但那会**绕过版本化写入器**，`row_fingerprint` 会与内容不一致；且属写共享库操作，需要你批准；
2. 或判定「项次 11 vs 10 无业务影响」，接受现状并登记；
3. 顺带：**「改回原值被判 UNCHANGED」本身值得单独立项**（`dataset` 维护侧的字段级变更检测遗漏了 `item_seq`），
   它会让用户「改了项次、点了保存、没有报错、但没存上」。

**我已经做的两件事**：
- 🚫 **没有**用 SQL 去改共享库（那是红线，我停下来报告）；
- ✅ **修掉了测试代码里的真缺陷** —— `finally` 的复原段原来只打印「已复原」从不回读校验。
  现在它会捕获 PUT 响应体、关抽屉重开**回读实际值**，对不上就 `console.error` 大声报，
  并在日志里写明「测试代理不得 SQL 直改，停下来报主线」。**谎报「已复原」这件事不会再发生。**

🚫 **本轮无任何 `DROP` / `TRUNCATE` / `DELETE` / 清库 / `rm -rf` / `git reset`**。删 4 个 spec 用的是 `git rm`（暂存删除，可完整恢复），未 `git commit`。
🚫 **未跑 `mvnw test`**（它直写共享库）。
🚫 **未停、未占用共享的 5174 / 8081**；临时的 5177 / 8099 已全部停掉并复验共享服务完好。

---

## 11. 未做 / 未验证（🚫 不写「应该没问题」）

| 项 | 状态 | 归属 |
|---|---|---|
| AC-12 V6 数据零变化 | **未验证** | 🔒 主线亲验（跨合并动作，不属子代理生命周期） |
| AC-13 核价单渲染零回归 | **未验证** | 🔒 主线亲验 |
| 「应用能在**声明端口 8081** 上启动」 | **未验证**（我只在临时 8099 上验了冷启动） | 主线亲验，见 §5 T-10 偏差说明 |
| `mvnw test`（后端单测全量） | **未运行**（直写共享库，本轮刻意回避） | 后端代理 / 主线 |
| `product-hub-readonly › E2E-09`（我改造过的那条） | **两侧都 did not run**，无基线 | 见 §6.3；由 FS-1a / FS-4 侧面背书 |
| `dataset-maintenance` / `dataset-plating-scheme` 的既存失败 | **未修**（antd v6 选择器腐化 + 夹具口径），范围外 | 建议另立任务，见 §12 |
| `docs/RECORD.md` 本任务条目 | **缺** | 主线（我不写 RECORD.md） |
| `dev-docs/INDEX.md`「按代码文件反查」`MasterDataHubPage.tsx` 行 | **未更新为 6 页签** | 主线 / B-7 |

---

## 12. 需要主线裁决的 4 件事

1. **AC-2 的「无 console.error」字面不可达** —— 3 条 antd 6.3.5 弃用告警，A/B 已证改动前就有同族 2 条、多出的第 3 条来自本任务未改的材质页组件。改 AC 还是改代码？
2. **AC-7 与 AC-1/AC-3/AC-4 判据互斥** —— 建议按已有两次先例（`db/migration/`、`dev-docs/`）再排除「本任务自己的验收用例文件」。当前 `e2e/` 余 18 处，实现侧已全清 0/0/0。
3. **AC-16 有一处不可避免的第四类改动**（`EditableSheetTable.lookupFn`），与 AC-6 互斥。已加运行时守卫 T-16b。闸门 B 汇报建议明写。
4. **既存失败要不要单独立项** —— `dataset-maintenance`(8) / `dataset-plating-scheme`(2) 的既存红里，有一类根因是 **antd 6.3.5 把 `.ant-drawer-content` / `.ant-select-selection-item` 两个类名去掉了**，与本任务无关但会持续污染 E2E 信号。

---

## 13. 产物索引（全部为绝对路径下的相对位置）

```
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts    新增，11 条用例
cpq-frontend/e2e/{dataset-maintenance,dataset-plating-scheme,
                  product-hub-fs,product-hub-edit-fs,
                  product-hub-readonly}.spec.ts                  改造 5 个
cpq-frontend/e2e/{tc0712-part-costing-smoke,tc0712-edit-flow,
                  tc0712-roles,verify260902-ac42}.spec.ts        git rm 4 个

dev-docs/task-260907-移除料号核价功能/证据/
  ├── shell-ac-evidence.md          T-6 / T-7 / T-17 / 端点 A/B 表 / T-10 冷启动 原始输出
  ├── verify-shell-ac.sh            可重跑：T-6 / T-7（含还原实验）/ T-14 / T-16 / T-17
  ├── coldstart-temp-backend.sh     可重跑：T-10 临时端口冷启动
  └── e2e/                          25 张验收截图（🚫 不在 test-results/，不会被下一轮清空）
```
