# test.md · 核价导入停用四个 Sheet（P01/P02/P04/P05）· 测试用例

- 对应文档：`需求文档.md`（验收唯一标准，AC-1~AC-12）/ `backtask.md`（T-B1~T-B5）/ `fronttask.md`（T-F1~T-F5）/ `api.md`（两端点契约声明"无变更"）
- 编写日期：2026-08-12　修订日期：2026-08-12（主线审核后按 3 处补测 + 5 项开放问题裁决修订）　编写人：cpq-tester（同期编写，闸门 A 前主线审核；开发中）
- 环境：分支 `feat/task-260812-核价导入停用四个Sheet`（worktree `/home/joii/project/cpq/.claude/worktrees/task-0812-disable-4-sheets`），基于 commit **`f551f37e`**（= 本任务的"停用前"基线 commit，A/B 对照一律以它为 A 侧）
- **A/B 对照库**：`10.177.152.12:5432/cpq_db`（test profile，`./mvnw test` 默认走这个库）。**严禁**用含导入副作用的用例污染 dev 库 `cpq_db_0724`（共享，有并发会话）；本文档所有"落库/导入"类用例默认在 test 库跑集成测试，仅模板下载类只读用例可选在 dev 库 8081 端点上跑。
- 用例字段：**编号｜对应 AC-x/FR-x｜前置数据｜步骤｜期望结果｜实际结果（留空待填）｜优先级**（P0 = 阻断合并 / P1 = 合并前必须过 / P2 = 时间不够可延后，需在 `test-report.md` 显式登记未跑原因）

---

## 0. 方法论声明（先读）

1. **"没误伤"重于"停用生效"**：本任务代码改动是纯删除（两个 `List` 摘 4 项），最大风险不是"删漏了"，而是"删的时候手滑动了顺序 / 动了不该动的行"（需求文档 §5.3 明确禁止重排）。因此本文档把 **A/B 逐 Sheet 对照**（REG 组）与**落库零写入的 SQL 证据**（IMP 组）列为 P0，权重高于"模板变 20 个 Sheet"这类正向验证。
2. **基线是移动靶**（`BL-0151`：文档写 159F/393E，2026-08-12 实测已是 159F/403E）。AC-10 的"不新增 failure/error"**必须同一轮实测 A 侧（commit `f551f37e`）与 B 侧（本分支 HEAD）**，不得引用历史文档里的固定数字，也不得跨会话复用旧基线（数据在变、测试在变）。
3. **测试数据只用程序化构造，不依赖任何历史 Excel 文件**（详见 §1）。**2026-08-12 主线已核实根因**：这批 xlsx **不是沙箱读取限制**——`git show HEAD:<path>` 取出的 git 对象本身就是非 zip 字节（前 4 字节 `87 7d 1c a3`，不是 zip 魔数 `50 4b 03 04`），即**以非 zip 状态提交进 git 的**；全仓 76 个 xlsx/xls 里 38 个合法、38 个非法，非法清单中有文件明确取名"（加密）罗克韦尔 导入测试.xlsx"，高度怀疑这批是被 WPS/Excel 加密过的历史文件。`PricingVersioningImportE2ETest.PRICING_FILE` 正是非法批次之一，**POI 在任何分支的 JVM 里同样打不开它**——这是**先于本次改动就存在的环境缺陷**，与本任务无关，不在本任务修复范围（主线已决定另行评估是否立项，见 `test-report.md` 收尾要求）。**处置**：§1.2 候选真实文件已作废，§1.1 程序化夹具是 IMP/REG/VER 组**唯一**权威数据源；`PricingVersioningImportE2ETest` 两个 `@Order` 用例预期在 A、B 两侧**同样**因夹具损坏而失败，属已知红，AC-10 判定时必须显式扣除、不得算作本次改动新增的 failure。
4. **执行阶段纪律（主线 2026-08-12 明确）**：本文档当前仅供审核，**不执行**——后端仍在开发中，待交付后由主线单独通知进场；执行时所有导入类用例只在 test 库 `cpq_db`（test profile）跑，绝不碰 dev 库 `cpq_db_0724`；`mvnw` 必须在 worktree `/home/joii/project/cpq/.claude/worktrees/task-0812-disable-4-sheets/cpq-backend` 下跑，不要 cd 主仓（历史踩过"测错树报假绿"的坑，见记忆 `cpq-worktree-maven-test-tree`）；跑不动/被阻塞/拿不到证据的用例如实标 BLOCKED + 原因，不得虚报通过。

---

## 1. 测试数据

### 1.1 主夹具（权威数据源，AC-3/4/5/6/7/8/9 都基于它）—— 程序化构造，闭环可控

**不依赖任何"找到的"历史 Excel 文件**，而是仿照本仓库已有的 `PricingTemplateServiceTest.generatedTemplate_importsCleanly()`（用 POI 在内存里拼 workbook 再喂给 `importService.importExcel`）与 `P01P02PricingPriceVersioningTest` 的 `elementRow()`/`materialRow()` 行构造手法，在**一次性测试辅助方法**（建议命名 `Task0812FixtureBuilder`，放 `cpq-backend/src/test/java/com/cpq/basicdata/v6/pricing/`，用完可删或保留供回归复用）里拼一个**恰好 24 个 Sheet**的 `XSSFWorkbook`：

- **20 个"保留" Sheet**：Sheet 名取自 `PricingHandlerCatalog`（B 侧 HEAD）或直接复用 A 侧 `catalog.all()` 的 20 个（P03/P06~P24，见需求文档 §5.3 第二序列），每个 Sheet 表头行 = 对应 handler 的 `templateHeaders()`，写 **1 行**最小合法数据（必填列取值参考 `PricingTemplateServiceTest.requiredKeyColumnsAreResolvable()` 第 138~161 行列出的必填键清单；非必填列留空或填占位值）。
- **4 个"停用" Sheet**（用于证明"有数据也不会被写入"）：
  - `元素核价价格表`：一行，列同 `P01P02PricingPriceVersioningTest.elementRow()`（元素代码=`T0812-P01-EL`／元素价格版本=`V1`／核价单价=`1.234`／市场参考价=`9.9`／...）
  - `材料核价价格表`：一行，列同 `materialRow()`（材料料号=`T0812-P02-MT`／...）
  - `核价版本`：一行，销售料号=`T0812-P04-SKU`，核价版本编号=`V1`（其余列若有则留空）
  - `宏丰-客户料号对应关系`：一行，销售料号=`T0812-P05-SKU-ONLY`（**刻意取一个不出现在其余 20 个 Sheet 任何列里的全新料号**，让 AC-7 的判定不必做"差集"这种脏活——直接断言这个料号导入前后在 `material_master` 里都不存在即可）/客户编号=`T0812-CUST`/客户产品编号=`CP-0812`

  这 4 个 Sheet 的最小必填列同样取自 `PricingTemplateServiceTest` 第 138~142 行的 `required` map。

- 该 workbook 序列化为 `byte[]`，供 IMP/REG/VER 组用例反复使用（同一份夹具，A 侧、B 侧各喂一次）。

**为什么不用"找到的"历史文件**：程序化构造夹具零依赖、可重复、内容 100% 可控，是本任务全部用例的**唯一**数据源。历史真实文件已证伪，见 §1.2。

### 1.2 候选真实文件 —— 已证伪，不作数据源 ⚠️

**2026-08-12 主线核实结论**：`docs/table/核价测试数据/` 下这批候选文件（含 `PricingVersioningImportE2ETest.PRICING_FILE` 引用的 `核价系统功能基础数据功能结构所需字段（6.0版） .xlsx`）在 **git 提交里本身就是非 zip 字节**（疑似 WPS/Excel 加密导出），**不是本沙箱的读取限制**，POI 在任何分支的真实 JVM 进程里同样无法打开。全仓 76 个 xlsx/xls 中 38 个非法（含一份显式命名"（加密）罗克韦尔 导入测试.xlsx"的文件，佐证加密猜测）。

**结论**：**不要再花时间探查这批文件是否含 P01/P02/P04/P05 数据行**——读不出来，探查无意义。原计划的"双重验证"（真实文件 + 程序化夹具互证）计划取消，§1.1 程序化夹具是唯一权威数据源。`PricingVersioningImportE2ETest` 依赖这批损坏文件的两个 `@Order` 用例，预期在 A 侧（`f551f37e`）和 B 侧（本分支 HEAD）**同样失败**，UT-04 的 A/B 对照必须把这两个方法识别为"两侧同源已知红"，不计入"新增 failure"。这批文件损坏是**先于本任务、且超出本任务范围**的环境缺陷，只在 `test-report.md` 末尾单列"本任务外发现"记录（含 38/76 统计口径与该 E2E 夹具文件名），不在本任务修。

### 1.3 A 侧（停用前）获取方法

```bash
# 在独立 worktree 或 git worktree add 一份 f551f37e 快照，跑 A 侧的 mvn test / importExcel(相同夹具)
git worktree add /tmp/claude-.../t0812-baseline f551f37e
cd /tmp/claude-.../t0812-baseline/cpq-backend
./mvnw test -Dtest='Pricing*Test,P0*Test' -q   # A 侧基线
```
或在本 worktree 内 `git stash` 掉本任务改动跑一遍（改完必须 `git stash pop` 恢复，注意 §8 已知坑位 3 的共享 dev server 约束不适用于 `mvnw test`，因为它是独立进程连 test 库，不占用 8081）。

---

## 2. 用例索引

| 编号 | 标题 | AC/FR | 优先级 | 方法 |
|---|---|---|---|---|
| TPL-01 | 模板 Sheet 数=20 且名称/顺序逐字比对 | AC-1 | **P0** | POI |
| TPL-02 | 模板不含 4 个已停用 Sheet 名 | AC-1 | **P0** | POI |
| TPL-03 | 20 个保留 Sheet 表头逐列 A/B diff | AC-2 | **P0** | POI + A/B |
| TPL-04 | 模板下载权限矩阵：4 角色全部 200 | api.md API-2 | P1 | HTTP/反射 |
| TPL-05 | 模板下载未登录 401 | api.md API-2 | P1 | HTTP |
| IMP-01 | §1.1 夹具导入：sheetResults 长度=18（合并条目口径）且不含 4 个停用名 | AC-3/FR-5 | **P0** | 集成 |
| IMP-02 | totalSuccessRows/totalFailedRows 正确排除 4 个停用 Sheet 的行 | AC-3/FR-5 | **P0** | 集成 |
| IMP-03 | `unit_price` ELEMENT/MATERIAL_PRICE 计数导入前后不变 | AC-4 | **P0** | SQL |
| IMP-04 | `material_version_mgmt` 行数与 max(updated_at) 不变 | AC-5 | **P0** | SQL |
| IMP-05a | `material_customer_map`：夹具全新 (material_no,customer_no) 不建档 | AC-6 | **P0** | SQL |
| IMP-05b | `material_customer_map`：存量行被"覆盖"也不被 upsert（品名/客户名称等列不变） | AC-6 | **P0** | SQL |
| IMP-06 | `material_master`（P05 专属料号）四列保持导入前状态（应仍不存在） | AC-7 | **P0** | SQL |
| IMP-07 | 边界：仅 4 个停用 Sheet 有数据、其余 20 页空 | AC-3 边界 | P1 | 集成 |
| IMP-08 | 异常：停用 Sheet 内数据格式错误不产生任何错误/不影响 status | AC-3/FR-5 边界 | P1 | 集成 |
| VER-01 | 同夹具连导两次，其余 20 Sheet 相关表版本列不升版 | AC-9 | **P0** | SQL |
| VER-02 | 改动未停用 Sheet 一行数据后第三次导入，正常升版（排除假阳性） | AC-9 反例 | P1 | SQL |
| VER-03 | 连导两次，4 个停用表始终零增量（非"漏改"） | AC-9 延伸/AC-4,5 | P1 | SQL |
| REG-01 | A/B 对照：其余 18 条结果条目的 successRows/totalRows/错误数逐条相等 | AC-8 | **P0** | 集成 A/B |
| REG-02 | A/B 对照边界：注入一行数据错误（如"计算类型"缺失），两侧错误明细一致 | AC-8 边界 | P1 | 集成 A/B |
| UT-01 | `P01P02PricingPriceVersioningTest` 全绿（4 方法） | AC-10/FR-7 | **P0** | mvn test |
| UT-02 | `PricingTemplateServiceTest` 收敛后全绿 | AC-10 | **P0** | mvn test |
| UT-03 | "漏登记必红"护栏仍生效（临时注释 p13 验证变红后撤销） | AC-10 | **P0** | mvn test |
| UT-04 | `mvn test` 全量 A/B：不新增 failure/error | AC-10 | **P0** | mvn test A/B |
| UT-05 | 核价侧其余 handler 专项版本化单测保持全绿 | AC-10/FR-9 回归 | P1 | mvn test |
| CODE-01 | `git diff --stat` 对 A 侧不含 4 个 Handler 类文件 | AC-12 | **P0** | git |
| CODE-02 | `orderedHandlers()`/`catalog.all()` 顺序逐位断言（只删不排，未被顺手重排） | AC-8 守卫补强/需求文档 §5.3 | **P0** | 反射/文本比对 |
| CODE-03 | 无新增 Flyway 迁移文件 | 需求文档 D-8 | P1 | git |
| FE-01 | `tsc --noEmit` 0 错误 | AC-11 | **P0** | tsc |
| FE-02 | 改动 tsx 文件 Vite transform 200 | AC-11 | P1 | curl |
| FE-03 | grep "24 Sheet"/"宏丰-客户料号对应关系" 残留为零 | AC-11 | **P0** | grep |
| FE-04 | 人工目视：抽屉标题/上传提示显示 20 Sheet | AC-11 | P1 | 人工 |
| FE-05 | 前端实传旧模板（24 Sheet 含 4 停用页数据）→ 结果表 18 行 | AC-11/FR-8 串联 | P1 | 人工+集成 |
| AUTH-01 | 导入端点 `@RoleAllowed` 值仍 = {SALES_MANAGER, SYSTEM_ADMIN}（反射） | api.md API-1 | **P0** | 反射 |
| AUTH-02 | 导入端点 SALES_MANAGER 身份可成功导入 | api.md API-1 | P1 | 集成/HTTP |
| AUTH-03 | 导入端点 SYSTEM_ADMIN 身份可成功导入 | api.md API-1 | P1 | 集成/HTTP |
| AUTH-04 | 导入端点非授权角色（SALES_REP/PRICING_MANAGER）403 | api.md API-1 | P1 | HTTP |
| AUTH-05 | 导入端点未登录 401 | api.md API-1 | P1 | HTTP |
| AUTH-06 | 导入端点 file 为空 400 | api.md API-1 | P2 | HTTP |
| AUTH-07 | 导入端点上传损坏文件 500，消息前缀不变（回归） | api.md API-1 | P2 | HTTP |
| RG-01 | 报价侧导入（`QuoteImportService`）不受影响 | 需求文档 §2.2 不做项 | **P0** | mvn test |
| RG-02 | 导入抽屉整体交互（上传→提交→结果表）流程零变化 | fronttask §4 | P1 | 人工 |
| RG-03 | 导入耗时不增加（预期略降） | backtask §6 | P2 | 计时 |
| RG-04 | `cp_view` 读 `material_customer_map` 的报价侧模板渲染无新异常 | 需求文档 R-2 | P1 | 人工探查 |
| CONC-01 | 并发发起两次相同文件导入，无重复升版/脏数据 | 探索性 | P2 | 并发 |
| CONC-02 | 导入进行中并发请求模板下载，互不阻塞 | 探索性 | P2 | 并发 |
| EDGE-01 | 空文件（0 字节）上传，既有错误处理分支不变（回归） | 回归 | P2 | HTTP |
| EDGE-02 | 文件仅含 1 个 Sheet 且恰为已停用 Sheet | 边界 | P2 | 集成 |
| EDGE-03 | 文件恰好只含 20 个未停用 Sheet（"新常态"模板） | 边界/AC-3 反向 | P1 | 集成 |

共 **48** 条用例（初版原有 45 条——初版收尾时曾误报为 38 条，此处更正；本轮修订净增 3 条：CODE-02、CODE-03 为新增，IMP-05 拆为 IMP-05a/IMP-05b）。

---

## 3. 详细用例

### G1 · 模板下载（AC-1 / AC-2）

#### TPL-01 · 模板 Sheet 数=20 且名称/顺序逐字比对 ★
- **前置数据**：无（空模板生成不依赖数据）
- **步骤**：
  1. B 侧调 `templateService.generateTemplate()`（或 `GET /api/cpq/basic-data-import/v6/pricing/template`）
  2. 用 POI 打开返回的 xlsx，`wb.getNumberOfSheets()`
  3. 按下标 0~19 取 `wb.getSheetAt(i).getSheetName()`，与需求文档 §5.3 第二序列（`p03,p06,p07,p08,p09,p10,p11,p12,p13,p14,p15,p16,p17,p18,p19,p20,p21,p22,p23,p24` 对应的 `sheetName()`）逐个比对
- **期望结果**：`getNumberOfSheets()==20`；20 个名称与顺序逐字相等
- **实际结果**：
- **优先级**：P0

#### TPL-02 · 模板不含 4 个已停用 Sheet 名
- **前置数据**：同 TPL-01 的返回 xlsx
- **步骤**：`wb.getSheet("元素核价价格表")` / `"材料核价价格表"` / `"核价版本"` / `"宏丰-客户料号对应关系"` 四次调用
- **期望结果**：均返回 `null`
- **优先级**：P0

#### TPL-03 · 20 个保留 Sheet 表头逐列 A/B diff ★
- **前置数据**：A 侧（`f551f37e`）生成一份模板存档；B 侧（HEAD）生成一份模板
- **步骤**：对 B 侧 20 个 Sheet 中的每一个，取表头行（第 1 行所有列），与 A 侧同名 Sheet 的表头行逐列比较
- **期望结果**：差异为空（列名、顺序、列数完全一致）
- **优先级**：P0（这是"没误伤"最直接的证据——如果哪个 handler 的 `templateHeaders()` 被误改，这里会先报警）

#### TPL-04 · 模板下载权限矩阵：4 角色全部 200
- **前置数据**：4 个测试角色账号（`SELECT username, role FROM "user" WHERE role IN ('SALES_REP','SALES_MANAGER','PRICING_MANAGER','SYSTEM_ADMIN')`，test 库取一个代表账号），或退化为反射检查
- **步骤**（二选一，反射更快更稳，建议作为主方法，HTTP 作为可选加强）：
  - 方法 A（反射，推荐）：读取 `BasicDataImportV6Resource#pricingTemplate` 方法上 `@RoleAllowed` 注解的 `value()`，断言 `Set.of("SALES_REP","SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN")` 与 A 侧（`f551f37e`）同方法的注解值集合相等
  - 方法 B（真实 HTTP）：分别用 4 个角色账号登录拿 session cookie，`curl --noproxy '*' -b cookie ... /api/cpq/basic-data-import/v6/pricing/template` 各一次
- **期望结果**：4 个角色均 200（或反射断言集合相等）
- **优先级**：P1

#### TPL-05 · 模板下载未登录 401
- **步骤**：`curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/basic-data-import/v6/pricing/template`（无 cookie）
- **期望结果**：401
- **优先级**：P1
- **备注**：4 个已知角色已覆盖 `@RoleAllowed` 全部允许值，系统当前无第 5 个角色可构造"角色不匹配的 403"，故模板下载端点无 403 用例（枚举法证明覆盖完整，非漏测）

---

### G2 · 导入停用生效（AC-3~AC-7, FR-1~FR-5）

#### IMP-01 · §1.1 夹具导入：sheetResults 长度=18 且不含 4 个停用名 ★（2026-08-12 主线口径更正：条目数≠Sheet数）
- **口径更正**：`sheetResults` 条目数**不等于** Sheet 数——P16+P17、P19+P20 各走一个合并 bean，每对只产出 1 条结果（`PricingImportService.java:107,124`）。停用前=20 循环项+2 合并项=**22** 条（既有行为，非本次引入）；停用后=16 循环项+2 合并项=**18** 条。
- **前置数据**：§1.1 程序化夹具（24 Sheet，4 个停用页各 1 行非空数据）
- **步骤**：
  1. `importService.importExcel(fileName, fixtureStream, testUserId)`
  2. 检查返回 `ImportResultDTO.sheetResults`
  3. **更稳健的写法（推荐，不硬编码绝对数字）**：额外用 A 侧（`f551f37e`）对同一夹具跑一次，断言 `B侧size() == A侧size() - 4`，避免未来再合并/拆分 Sheet 时用例被误报
- **期望结果**：`sheetResults.size() == 18`（= 16 循环项 + 2 合并项）；相对 A 侧 22 条恰好减少 4；`sheetResults` 中不存在 `sheetName` 精确等于或 `contains` 这 4 个名字的元素（注意 P16/P17、P19/P20 合并 bean 的报告名含 `(合并)` 后缀，用 `contains` 判定，见 `PricingTemplateServiceTest` 第 210~215 行现成写法）
- **优先级**：P0

#### IMP-02 · totalSuccessRows/totalFailedRows 正确排除 4 个停用 Sheet 的行
- **前置数据**：同 IMP-01
- **步骤**：对比"18 个 sheetResults 各自 successRows 之和"与 `out.totalSuccessRows`
- **期望结果**：两者相等（即 4 个停用 Sheet 的 1 行×4 完全没被计入 `totalSuccessRows`）
- **优先级**：P0

#### IMP-03 · `unit_price` ELEMENT/MATERIAL_PRICE 计数导入前后不变 ★
- **前置数据**：同 IMP-01；导入前记录基线 `SELECT price_type, count(*) FROM unit_price WHERE system_type='PRICING' AND price_type IN ('ELEMENT','MATERIAL_PRICE') GROUP BY price_type`
- **步骤**：执行 IMP-01 导入后，重跑同一 SQL；另外显式确认 `SELECT count(*) FROM unit_price WHERE code='T0812-P01-EL'` / `code='T0812-P02-MT'` 均为 0（夹具里特意造的两个"应该被拒绝写入"的 code）
- **期望结果**：分组计数导入前后完全相等；两个夹具 code 在表中均查不到
- **优先级**：P0

#### IMP-04 · `material_version_mgmt` 行数与 max(updated_at) 不变
- **前置数据**：导入前 `SELECT count(*), max(updated_at) FROM material_version_mgmt`
- **步骤**：执行 IMP-01 导入后重跑同一 SQL；另外确认 `SELECT count(*) FROM material_version_mgmt WHERE material_no='T0812-P04-SKU'` = 0
- **期望结果**：行数与 `max(updated_at)` 均不变；夹具专属料号查不到
- **优先级**：P0

#### IMP-05a · `material_customer_map`：夹具全新 (material_no,customer_no) 不建档
- **前置数据**：导入前 `SELECT count(*), max(updated_at) FROM material_customer_map`
- **步骤**：执行 IMP-01 导入后重跑；另外确认 `SELECT count(*) FROM material_customer_map WHERE material_no='T0812-P05-SKU-ONLY' AND customer_no='T0812-CUST'` = 0
- **期望结果**：全表行数不变；夹具专属 (material_no, customer_no) 组合查不到
- **优先级**：P0

#### IMP-05b · `material_customer_map`：存量行被"覆盖"也不被 upsert ★（主线补测 2026-08-12）
- **为什么必须补**：IMP-05a 只证明"全新料号不建档"，没有覆盖 AC-6 原文"被导入文件覆盖到的那些行 `updated_at` 不变"——如果 P05 停用后代码里仍残留半条 upsert 路径（只更新已存在行、不新建），IMP-05a 抓不到，必须单独用一条"库里已有、文件里也出现"的行来验证它**真的没被碰**。
- **前置数据**：
  1. 导入前从 test 库 `material_customer_map` 里挑一条现存行（或专门 seed 一条），记录其 `material_no`/`customer_no`/所有业务列原值/`updated_at`；记为 `(MNO, CNO)`
  2. 把 §1.1 夹具的"宏丰-客户料号对应关系"页**追加第二行**：`销售料号=MNO`、`客户编号=CNO`，其余列（客户产品编号/客户料号名称等）故意填成与库里**不同**的新值，制造"如果 upsert 被执行就会被覆盖"的场景
- **步骤**：执行导入后，重新查这一行的全部业务列 + `updated_at`
- **期望结果**：该行所有业务列与 `updated_at` **逐字节等于导入前记录的原值**（完全没被碰，包括没有"部分列被更新"这种半吊子情况）
- **优先级**：P0

#### IMP-06 · `material_master`（P05 专属料号）四列保持导入前状态 ★
- **前置数据**：夹具中 `宏丰-客户料号对应关系` 页的销售料号 `T0812-P05-SKU-ONLY` 按 §1.1 设计**不出现在其余 20 个 Sheet 的任何列**（构造时需在 `Task0812FixtureBuilder` 里显式校验：grep 生成的 workbook 全部单元格，确认该字符串只出现 1 次，避免夹具本身写错波及其他 Sheet）
- **步骤**：导入前后各查一次 `SELECT material_no, name, spec, dimension, old_material_no, updated_at FROM material_master WHERE material_no = 'T0812-P05-SKU-ONLY'`
- **期望结果**：导入前后该行**均不存在**（0 rows）——证明 P05 停用后，只在 P05 出现的料号完全没有被 `upsertBatchNameType` 建档
- **优先级**：P0
- **备注**：这是 AC-7 的"干净"验证方式，避免对真实历史文件做"差集"这种脆弱操作（真实文件里销售料号可能同时出现在多个 Sheet，差集结果依赖具体数据、不稳定）

#### IMP-07 · 边界：仅 4 个停用 Sheet 有数据、其余 20 页空
- **前置数据**：变体夹具——保留 24 个 Sheet（含表头），但只有 4 个停用页各写 1 行数据，其余 20 页仅表头无数据行
- **步骤**：导入
- **期望结果**：`sheetResults.size()==18`，全部 `totalRows==0` / `successRows==0` / `failedRows==0`；`status=="SUCCESS"`；无任何写入（复用 IMP-03~06 的零写入断言）
- **优先级**：P1

#### IMP-08 · 异常：停用 Sheet 内数据格式错误不产生任何错误/不影响 status
- **前置数据**：变体夹具——`元素核价价格表` 页的"元素代码"列留空（本应触发必填校验错误）、`宏丰-客户料号对应关系` 页"客户编号"留空，其余 20 页保持 IMP-07 式的空数据
- **步骤**：导入，检查 `out.status`、18 个 `sheetResults` 的 `errors[]`
- **期望结果**：`status=="SUCCESS"`；18 个 `sheetResults` 全部 `errors` 为空或 null；**不存在**任何提及"元素代码"/"客户编号"必填校验失败的错误——证明这 4 页是"完全不解析"而不是"解析了但吞掉了校验错误"
- **优先级**：P1

---

### G3 · 版本升级语义 / 幂等（AC-9）

#### VER-01 · 同夹具连导两次，其余 20 Sheet 相关表版本列不升版 ★
- **前置数据**：§1.1 夹具
- **步骤**：
  1. 第一次导入，记录 `unit_price`（非 ELEMENT/MATERIAL_PRICE 的 price_type）、`material_bom`、`element_bom`、`capacity` 等表中夹具涉及料号的 `max(version_no)` / `max(calc_version)`
  2. 用**同一份**夹具字节流第二次导入
  3. 重跑同一批 SQL
- **期望结果**：版本列在两次导入之间不变（不升版），且各表 `is_current=true` 行数不因第二次导入翻倍
- **优先级**：P0

#### VER-02 · 改动未停用 Sheet 一行数据后第三次导入，正常升版（排除假阳性）
- **前置数据**：VER-01 之后的库状态
- **步骤**：把夹具中"物料BOM"页某行的一个内容列（如单价/比例）改一个新值，第三次导入
- **期望结果**：该组版本号从 VER-01 的值 +1（正常升版），证明"不升版"不是因为整条链路失效，而是内容真的没变
- **优先级**：P1

#### VER-03 · 连导两次，4 个停用表始终零增量
- **前置数据**：VER-01 步骤 1/2 之间
- **步骤**：两次导入之间分别查 `unit_price`(ELEMENT/MATERIAL_PRICE)/`material_version_mgmt`/`material_customer_map`/`material_master`(P05 专属料号) 的行数
- **期望结果**：两次之间这 4 张表相关行数均恒为 0（不是"停用了但某条代码路径漏删导致仍写入一次"）
- **优先级**：P1

---

### G4 · A/B 回归对照（AC-8）

#### REG-01 · A/B 对照：其余 18 条结果条目的 successRows/totalRows/错误数逐条相等 ★
- **前置数据**：§1.1 夹具；A 侧 = commit `f551f37e`（§1.3 方法获取），B 侧 = 本分支 HEAD
- **口径**：A 侧 `sheetResults` 共 22 条（20 循环项 + 2 合并项），B 侧共 18 条（16 循环项 + 2 合并项）；对照对象是 A 侧 22 条里"停用后仍保留"的 18 条（即剔除 P01/P02/P04/P05 对应的 4 条循环结果，2 个合并条目 A/B 都在，直接对照）
- **步骤**：
  1. A 侧对夹具跑一次导入，记录这 18 条保留结果条目各自 `successRows/totalRows/failedRows`
  2. B 侧对**同一份**夹具跑一次导入，记录同样 18 条结果条目的三个数值
  3. 逐条 diff（按 `sheetName` 对齐，合并条目的 `sheetName` 含 `(合并)` 后缀，用精确匹配即可，两侧命名不变）
- **期望结果**：18 条结果条目的三个数值 A/B 完全相等
- **优先级**：P0（本任务最核心的回归证据）

#### REG-02 · A/B 对照边界：注入一行数据错误，两侧错误明细一致
- **前置数据**：REG-01 夹具变体——"物料BOM"页故意留空必填列"计算类型"
- **步骤**：同 REG-01 流程，额外比对该 Sheet 的 `errors[]`（`rowNo`/`column`/`message`）
- **期望结果**：A/B 两侧该 Sheet 的错误明细逐字段相等
- **优先级**：P1

---

### G5 · Handler / 单测护栏（AC-10, FR-7）

#### UT-01 · `P01P02PricingPriceVersioningTest` 全绿
- **步骤**：`./mvnw test -Dtest=P01P02PricingPriceVersioningTest`
- **期望结果**：4 个测试方法全部通过（`p01_firstImport...` / `p01_reimportSameValue...` / `p01_reimportChangedPrice...` / 对应 p02 三个），证明 Handler 落库逻辑保留完好，未被误删
- **优先级**：P0

#### UT-02 · `PricingTemplateServiceTest` 收敛后全绿
- **步骤**：`./mvnw test -Dtest=PricingTemplateServiceTest`
- **期望结果**：4 个测试方法全部通过；`sheetsMatchHandlerRegistry` 断言 handler 数=20；`catalogCoversEveryPricingHandlerBean` 用 `DISABLED_HANDLERS` 常量排除 4 个后 bean 数仍断言=24（bean 还在，只是不进 catalog）
- **优先级**：P0

#### UT-03 · "漏登记必红"护栏仍生效 ★
- **前置数据**：B 侧代码
- **步骤**：
  1. 临时把 `PricingHandlerCatalog.all()` 里的 `p13` 从 `List.of(...)` 中注释掉（不动 `orderedHandlers()`）
  2. 跑 `catalogCoversEveryPricingHandlerBean`
  3. 断言完成后**立即撤销**这处临时改动，重新跑一次确认恢复
- **期望结果**：步骤 2 该测试必须变红（报"P13IncomingProcessFeeHandler 未登记进 PricingHandlerCatalog"类消息）；步骤 3 撤销后测试变绿
- **优先级**：P0（backtask.md T-B4a 明确要求的自检，验证"降级防护"未把护栏改废）

#### UT-04 · `mvn test` 全量 A/B：不新增 failure/error ★
- **前置数据**：A 侧 `f551f37e`（§1.3）、B 侧 HEAD，**同一次会话内连续跑**（避免共享 test 库并发写入互相污染基线）
- **步骤**：
  1. A 侧：`./mvnw test 2>&1 | tail -30`，记录 `Tests run / Failures / Errors` 汇总数
  2. B 侧：同样跑一次，记录同样汇总数
  3. 用 `surefire-reports/*.txt` 逐类对比，列出 B 侧相对 A 侧**新增**的 failure/error 类名+方法名（如果有）
- **期望结果**：B 侧 failure/error 总数 **≤** A 侧；新增列表为空。**不得**以"B 侧全绿"为通过口径（已知 `BL-0151` 存量红是移动靶）
- **优先级**：P0

#### UT-05 · 核价侧其余 handler 专项版本化单测保持全绿
- **步骤**：`./mvnw test -Dtest='P03ExchangeRateVersioningTest,P08CapacityHandlerTest,P08LaborRateVersioningTest,P09P10ProductionEnergyVersioningTest,P11AuxiliaryEnergyVersioningTest,P12ToolingCostVersioningTest,UnitPriceFeeVersioningTest,PricingMergeVersioningTest'`（类名以实际存在为准，执行前先 `find` 确认）
- **期望结果**：与 A 侧结果一致（全绿或与 A 侧红的地方完全相同）
- **优先级**：P1

---

### G6 · 代码级验证（AC-12）

#### CODE-01 · `git diff --stat` 对 A 侧不含 4 个 Handler 类文件 ★
- **步骤**：`git diff f551f37e --stat -- cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/`
- **期望结果**：输出列表中**不包含** `P01ElementPricingPriceHandler.java` / `P02MaterialPricingPriceHandler.java` / `P04PricingVersionHandler.java` / `P05CustomerMapHandler.java`
- **优先级**：P0

#### CODE-02 · `orderedHandlers()`/`catalog.all()` 顺序逐位断言 ★（主线补测 2026-08-12，P0 最重要）
- **为什么必须补**：需求文档 §5.3 明令"只删不排"——`orderedHandlers()` 是**多表写入依赖序**（料号→关系→汇率→BOM主→BOM子→单价→其余），重排会改变 `material_master` 等共写表 upsert 的先后结果。**REG-01 的 A/B 逐 Sheet 对照抓不住顺序错乱**：§1.1 夹具每个 Sheet 只有 1 行，`material_master` 的 COALESCE-补空语义根本不会因为写入顺序不同而产生可观察差异——顺序被误改，REG-01 仍然全绿。必须单独、直接断言顺序本身。
- **前置数据**：无（比对的是代码结构，不依赖数据）
- **步骤**：
  1. 反射调用 `PricingImportService#orderedHandlers()`（私有方法可用 `Method.setAccessible(true)` 或改测试可见性），取返回的 `List<SheetHandler>`，映射为 `sheetName()` 序列（比对名称序列更稳，不依赖 `@Inject` 字段名/类型）
  2. 断言该序列**逐位等于**：`单重, 汇率管理表, 物料BOM, 物料与元素BOM, 产能, 设备折旧成本, 生产设备能耗, 辅助设备能耗, 模具工装成本, 生产耗材BOM, 包装材料BOM, 来料加工费, 加工费&组装费, 电镀方案, 电镀成本, 其他外加工成本`（对应需求文档 §5.3 第一序列 `p24,p03,p06,p07,p08,p09,p10,p11,p12,p13,p14,p15,p18,p21,p22,p23` 的 `sheetName()`）
  3. 同法断言 `PricingHandlerCatalog.all()` 的 `sheetName()` 序列逐位等于需求文档 §5.3 第二序列（20 项，= TPL-01 用的同一份序列）
- **期望结果**：两个序列均逐位相等，长度分别为 16 和 20
- **优先级**：P0

#### CODE-03 · 无新增 Flyway 迁移文件
- **前置数据**：需求文档 D-8 明确"不需要迁移"
- **步骤**：`git diff --name-only f551f37e..HEAD -- cpq-backend/src/main/resources/db/migration`
- **期望结果**：输出为空
- **优先级**：P1
- **备注**：这条守的是"开发过程中有人觉得'加个迁移清一下存量数据更干净'就顺手加了"——那会直接违反需求文档 §2.2 的不做项，且共享 Flyway 历史被并发会话动过版本号，多一个迁移就是一颗雷（`cpq-shared-flyway-history-churn`）

---

### G7 · 前端（AC-11）

#### FE-01 · `tsc --noEmit` 0 错误
- **步骤**：`cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
- **期望结果**：0 错误
- **优先级**：P0

#### FE-02 · 改动 tsx 文件 Vite transform 200
- **步骤**：`curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/master-data/PricingBasicDataImportDrawer.tsx`
- **期望结果**：200
- **优先级**：P1
- **备注**：共享 5174 服务主工作区代码；worktree 内自检需按 `cpq-worktree-frontend-selfcheck` 手法或合并后于主工作区复验

#### FE-03 · grep "24 Sheet"/"宏丰-客户料号对应关系" 残留为零
- **步骤**：`/usr/bin/grep -a -n "24 Sheet\|宏丰-客户料号对应关系" cpq-frontend/src/pages/master-data/PricingBasicDataImportDrawer.tsx`
- **期望结果**：无输出
- **优先级**：P0

#### FE-04 · 人工目视：抽屉标题/上传提示显示 20 Sheet
- **步骤**：打开「主数据维护 → 核价基础数据导入」抽屉
- **期望结果**：标题含 "20 Sheet"，上传提示 hint 含 "20 Sheet"，说明文字已按 fronttask.md F-3 改为引导用最新模板，无 "宏丰-客户料号对应关系" 字样
- **优先级**：P1

#### FE-05 · 前端实传旧模板（24 Sheet 含 4 停用页数据）→ 结果表 18 行
- **前置数据**：§1.1 程序化夹具落盘为 `.xlsx` 文件（**不使用**已证伪的 §1.2 候选真实文件）
- **步骤**：在抽屉里上传该文件并提交
- **期望结果**：结果表格渲染 18 行（`rowKey="sheetName"`），不出现 4 个停用 Sheet 行，无报错提示
- **优先级**：P1

---

### G8 · 权限与错误码（导入端点，api.md API-1）

#### AUTH-01 · `@RoleAllowed` 值仍 = {SALES_MANAGER, SYSTEM_ADMIN}（反射）★
- **步骤**：反射读取 `BasicDataImportV6Resource#importPricing` 的 `@RoleAllowed.value()`，与 A 侧同方法比对
- **期望结果**：两侧集合相等 = `{"SALES_MANAGER","SYSTEM_ADMIN"}`
- **优先级**：P0
- **备注（主线裁决 2026-08-12）**：本条是 AUTH-02~04 的主要证据来源，必跑不可省——本次改动不触碰鉴权逻辑，投入角色注入基础设施的成本与风险不对称，故 AUTH-02~04 降级为"反射覆盖为主，手工可选"，不是漏测省略。

#### AUTH-02 · SALES_MANAGER 身份可成功导入
- **步骤**：以 SALES_MANAGER 测试账号（或直接 service 层调用 + `ctx.importedBy` 设为该角色用户 id）导入 §1.1 夹具
- **期望结果**：200，`status=="SUCCESS"`
- **优先级**：P1
- **备注（主线裁决 2026-08-12）**：AUTH-01 的反射结果已是主要证据；本条允许**降级为 service 层直调**（绕过 HTTP 层角色过滤，只验证"角色在白名单内时业务逻辑本身能跑通"），不强求走真实登录+session cookie 的 HTTP 全链路。若时间充裕，手工用真实测试账号在浏览器/Postman 走一遍作为加强，非必须。

#### AUTH-03 · SYSTEM_ADMIN 身份可成功导入
- 同 AUTH-02，角色换 SYSTEM_ADMIN
- **优先级**：P1
- **备注（主线裁决 2026-08-12）**：同 AUTH-02，反射覆盖为主，手工可选。

#### AUTH-04 · 非授权角色 403
- **步骤**：以 SALES_REP 或 PRICING_MANAGER 账号调用导入端点
- **期望结果**：403，响应体 `{"success":false,"code":403,"message":"无权限访问"}` 风格不变
- **优先级**：P1
- **备注（主线裁决 2026-08-12）**：反射覆盖为主（AUTH-01 已证明白名单只有 SALES_MANAGER/SYSTEM_ADMIN，故 SALES_REP/PRICING_MANAGER 必然落在 `RoleFilter.java:76-81` 的 403 分支，逻辑上可从 AUTH-01 推出）；手工真实账号验证一次作为加强，非必须。

#### AUTH-05 · 未登录 401
- **步骤**：`curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8081/api/cpq/basic-data-import/v6/pricing`（无 cookie，无 body）
- **期望结果**：401
- **优先级**：P1

#### AUTH-06 · file 为空 400
- **步骤**：以授权角色调用，不带 `file` 字段
- **期望结果**：400
- **优先级**：P2

#### AUTH-07 · 上传损坏文件 500，消息前缀不变（回归）
- **步骤**：上传一个非 xlsx 的随意文件
- **期望结果**：500，消息前缀 `核价基础数据导入失败: `（api.md 明确"不变"，用于确认本次改动未影响异常处理路径）
- **优先级**：P2

---

### G9 · 回归面（其余功能不受影响）

#### RG-01 · 报价侧导入不受影响 ★
- **步骤**：`./mvnw test -Dtest=PricingVersioningImportE2ETest#step3_quoteImport_regression_failedRowsZero`（若整条 E2E 因夹具/环境跑不全，至少单独跑这一个 `@Order(3)` 方法验证报价侧回归）
- **期望结果**：`unmatchedRows==0`，`status=="SUCCESS"`
- **优先级**：P0

#### RG-02 · 导入抽屉整体交互流程零变化
- **步骤**：人工过一遍上传→提交→结果表渲染→（构造一个部分失败场景）PARTIAL 分支展示
- **期望结果**：与停用前行为一致，仅结果表行数由 A 侧 22 变 B 侧 18
- **优先级**：P1

#### RG-03 · 导入耗时不增加
- **步骤**：A/B 各对 §1.1 夹具导入计时 3 次取中位数
- **期望结果**：B 侧耗时 ≤ A 侧（backtask.md §6："预期略降"，无硬性阈值，如实记录即可，不设通过/不通过门槛）
- **优先级**：P2（**主线裁决 2026-08-12**：不设量化阈值，保持"如实记录不设门槛"口径——纯删除调用不可能实质劣化，设阈值反而会被共享库噪声制造假红）

#### RG-04 · `cp_view` 读 `material_customer_map` 的报价侧模板渲染无新异常
- **前置数据**：需求文档 R-2 提到 15 条 `component_sql_view` 配置读 `material_customer_map` 输出 `_客户料号名称`/`_客户产品编号`/`_汇率`
- **步骤**：打开一个用到这类组件的报价单/模板渲染页面（或直接调用对应组件的数据查询接口），观察是否有新增 500/异常
- **期望结果**：无新增异常（该表本身数据未被清空，只是核价侧新增行的入口关闭，历史数据仍可查）；三列在没有核价侧新数据覆盖的客户维度上可能为空，这是已知、已裁定接受的行为（D-9/R-2），**不是本用例要抓的 bug**——本用例只抓"异常/报错"这一类回归
- **优先级**：P1

---

### G10 · 并发/重复提交（探索性，非本次改动直接目标）

#### CONC-01 · 并发发起两次相同文件导入，无重复升版/脏数据
- **步骤**：用 `ManagedExecutor`/线程池并发触发两次 `importExcel(同一夹具)`
- **期望结果**：两次各自 `sheetResults.size()==18`；最终各表 `is_current=true` 行每组唯一（不因并发产生同组两个 current）
- **优先级**：P2
- **备注**：`orderedHandlers()` 每个 Sheet 走 `REQUIRES_NEW` 独立事务（backtask.md §4 "不变"），本用例只为确认停用摘除调用点没有改变这个既有并发特性，不要求引入新并发保护

#### CONC-02 · 导入进行中并发请求模板下载，互不阻塞
- **步骤**：导入耗时较长时（可把 §1.1 夹具的保留 Sheet 行数临时放大以拉长耗时），另开一个请求打模板下载端点
- **期望结果**：两者互不阻塞，模板下载正常返回 20 Sheet
- **优先级**：P2

---

### G11 · 边界

#### EDGE-01 · 空文件（0 字节）上传，既有错误处理分支不变（回归）
- **步骤**：上传 0 字节文件
- **期望结果**：与停用前行为一致（大概率仍是 400 或 500，具体以 A 侧实测为准，本用例只验证"没变"）
- **优先级**：P2

#### EDGE-02 · 文件仅含 1 个 Sheet 且恰为已停用 Sheet
- **前置数据**：workbook 只有 `元素核价价格表` 一个 Sheet（其余 19 个不存在，含 P03/P06 等未停用页也不存在）
- **步骤**：导入
- **期望结果**：`sheetResults.size()==18`（16 循环项 + 2 合并项，全部命中 `wb.getSheet(...)==null`/合并双 Sheet 皆 null 分支），全部 `totalRows==0`（见 `PricingImportService.java:130-131` 的 null 分支 + `:92-95`/`:109-112` 的合并双 Sheet 取空 List 分支），不因为"文件里唯一的 Sheet 恰好是被停用的"而报整体性错误（如"无有效 Sheet"）
- **优先级**：P2

#### EDGE-03 · 文件恰好只含 20 个未停用 Sheet（"新常态"模板）
- **前置数据**：TPL-01 生成的 20 Sheet 空模板，或 §1.1 夹具去掉 4 个停用页
- **步骤**：导入
- **期望结果**：`sheetResults.size()==18`，与 IMP-01 使用 24 Sheet 夹具的结果（18 条保留结果部分）完全一致——证明"文件里根本没有这 4 页"和"文件里有这 4 页但被跳过"两种输入，导入侧行为等价
- **优先级**：P1

---

## 4. 回归清单（收尾前必查，逐条勾选进 test-report.md）

- [ ] REG-01（A/B 20 Sheet 逐项相等）—— 本任务最核心证据，**不可省**
- [ ] CODE-02（`orderedHandlers()`/`catalog.all()` 顺序逐位断言）—— REG-01 抓不住的"顺序被重排"，**不可省**
- [ ] UT-01 / UT-05（handler 落库单测全绿）—— 证明"存储逻辑保留"不是文档空话
- [ ] UT-04（mvn test 全量 A/B 不新增红，注意扣除 `PricingVersioningImportE2ETest` 两侧同源的已知红）
- [ ] RG-01（报价侧回归）
- [ ] IMP-03/IMP-04/IMP-05a/IMP-05b/IMP-06（4 张表零写入 + 存量行不被覆盖 SQL 证据）
- [ ] CODE-01（4 个 Handler 文件零改动）
- [ ] CODE-03（无新增 Flyway 迁移）
- [ ] TPL-03（20 个保留 Sheet 表头 A/B diff）
- [ ] FE-01/FE-03（前端 tsc + 文案残留扫描）

---

## 5. 开放问题 —— 主线 2026-08-12 裁决记录

1. ~~环境限制~~ **【已裁决，非本任务范围】**：根因不是沙箱读取限制，是这批 xlsx 在 git 里本身就是非 zip 字节（疑似 WPS/Excel 加密导出），JVM/POI 同样打不开，详见 §0.3 / §1.2。**处置**：§1.2 候选真实文件作废，§1.1 程序化夹具为唯一数据源；`PricingVersioningImportE2ETest` 两个依赖损坏夹具的 `@Order` 用例预期 A/B 两侧同样失败，UT-04 判定时显式扣除不计入新增 failure；该 xlsx 损坏问题本身作为"本任务外发现"记录进 `test-report.md`（含 38/76 合法率统计 + 该 E2E 夹具文件名），是否立项修复由主线另行决定，本任务不修。
2. ~~AC-6 存量行覆盖如何验证~~ **【已裁决，已补测】**：不依赖真实文件，改用"库里 seed 一条存量行 + 夹具文件里同一 (material_no,customer_no) 但业务列填不同值"的构造手法直接验证"存量行也不被覆盖"，见 **IMP-05b**（IMP-05 已拆分为 IMP-05a 全新料号不建档 + IMP-05b 存量行不被覆盖）。
3. **AUTH-02/03/04 角色模拟【已裁决】**：采纳退化方案。AUTH-01（反射验证 `@RoleAllowed` 注解值未变）P0 必跑；AUTH-02~04 降级为"反射覆盖为主 + service 层直调/手工验证为辅助加强，非必须"，已在各用例备注里注明是主线裁决而非省略，理由：本次不触碰鉴权逻辑，投入产出不对称。
4. **UT-05 类名未核实【维持开放，执行阶段处理】**：需求文档 §3 引用的类名（`P09P10ProductionEnergyVersioningTest` 等）来自 backtask.md 转述，尚未逐一 `find` 核实文件是否存在、方法名拼写是否准确。执行阶段第一步须先 `find cpq-backend/src/test -iname 'P0*VersioningTest.java' -o -iname 'UnitPriceFeeVersioningTest.java' -o -iname 'PricingMergeVersioningTest.java'` 拿到准确列表再跑（注意用 `/usr/bin/grep -a` 而非本环境的 `grep`=`ugrep -I`，避免中文注释多的源文件被误判二进制返空）。
5. **RG-03 无量化阈值【已裁决】**：不设阈值，如实记录即可，保持 P2。纯删除调用不可能实质劣化，设阈值反而会被共享 test 库的并发噪声制造假红。
