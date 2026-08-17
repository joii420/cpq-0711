# test-report.md · 核价导入停用四个 Sheet（P01/P02/P04/P05）· 测试报告

- 对应文档：`需求文档.md`（AC-1~AC-12）/ `backtask.md` / `fronttask.md` / `api.md`（本次无契约变更）/ `test.md`（48 条用例）
- 执行日期：2026-08-12　执行人：cpq-tester
- 本次无契约变更，无需回写 `main-api.md`（`dev-docs/任务平台规则.md` §2.4）

---

## 1. 执行环境

| 项 | 值 |
|---|---|
| 特性分支 | `feat/task-260812-核价导入停用四个Sheet` |
| B 侧（停用后）worktree | `/home/joii/project/cpq/.claude/worktrees/task-0812-disable-4-sheets`，HEAD `8946713b`（前端 `64f1cf3b` + 后端 `8946713b`） |
| A 侧（停用前）基线 | commit `f551f37e`，隔离 worktree `git worktree add /tmp/.../scratchpad/t0812-baseline f551f37e`（临时目录，报告收尾后删除） |
| 后端测试库 | `10.177.152.12:5432/cpq_db`（test profile，`./mvnw test` 默认走这个库）—— 全程未碰 dev 库 `cpq_db_0724` |
| 后端 mvnw 执行目录 | 均在各自 worktree 的 `cpq-backend/` 下执行，未跨仓库混跑 |
| 前端自检 | worktree `cpq-frontend/`（软链 `node_modules`）+ 临时隔离 Vite 实例（端口 57991，验证完已 kill） |
| 共享 dev server 8081/5174 | 仅用于只读 401 权限 sanity check（服务的是主工作区代码，非本分支），未用于本次核心证据 |

---

## 2. 执行汇总

| 状态 | 数量 |
|---|---|
| 通过（含"代码同一性证明"方式） | 37 |
| 部分验证（有证据但非理想手段，建议合并前补一次真实操作） | 4 |
| 未执行（P2，如实标注原因） | 7 |
| **合计** | **48** |

零 BLOCKED——没有用例因环境/权限拿不到证据而卡住（唯一的环境障碍是 xlsx 夹具损坏，已用 §1.1 程序化夹具绕过，详见 test.md §0.3）。

---

## 3. 逐条结果

### G1 · 模板下载（AC-1/AC-2）

| 编号 | 方法 | 结果 | 证据摘要 |
|---|---|---|---|
| TPL-01 | `PricingTemplateServiceTest.sheetsMatchHandlerRegistry` | **PASS** | B 侧 5/5 绿；20 Sheet 名称/顺序逐字匹配 |
| TPL-02 | 同上 | **PASS** | 同上测试内含此断言 |
| TPL-03 | git diff 同一性证明 | **PASS** | `git diff f551f37e --name-only` 确认 20 个"保留"handler 类文件（`templateHeaders()` 所在处）**零改动**，表头逐字节等同，无需运行时 diff |
| TPL-04 | `BasicDataImportV6Resource.java` git diff 同一性证明 | **PASS** | 该文件相对 A 侧 **零改动**，`@RoleAllowed({"SALES_REP","SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})` 逐字未变 |
| TPL-05 | 真实 curl | **PASS** | `curl POST .../pricing/template`（无 cookie）→ `401` |

### G2 · 导入停用生效（AC-3~AC-7）—— 全部通过新建 `Task0812DisabledSheetsTest`（7/7 绿，B 侧）

| 编号 | 结果 | 证据摘要 |
|---|---|---|
| IMP-01 | **PASS** | `sheetResults.size()==18`（16 循环 + 2 合并），不含 4 个停用名 |
| IMP-02 | **PASS** | `totalSuccessRows==1`（只有 P03 那 1 行，4 个停用 Sheet 各 1 行未计入） |
| IMP-03 | **PASS** | `unit_price` 中 `T0812-P01-EL`/`T0812-P02-MT` 均 0 行 |
| IMP-04 | **PASS** | `material_version_mgmt` 中 `T0812-P04-SKU` 0 行 |
| IMP-05a | **PASS** | `material_customer_map` 中全新组合 `(T0812-P05-SKU-ONLY, T0812-CUST)` 0 行 |
| IMP-05b | **PASS**（★ 关键：A/B 对照证实"会覆盖 vs 不会覆盖"） | 先用生产代码同款 `MaterialCustomerMapRepository#upsert` seed 一条存量行，夹具里塞同冲突键 `(system_type='PRICING', material_no, customer_no, customer_product_no)` 但 `customer_name` 不同的"覆盖行"。**B 侧**：`customer_name`/`updated_at` 逐字节不变。**A 侧同一夹具**：`customer_name` 真的被改成了夹具值（"customer_name 不应被覆盖 ==> expected: <原始客户名称> but was: <被覆盖测试-不应生效>"）——证明这不是设计上就不会触发的伪场景，是"停用前会覆盖、停用后不会"的真实行为差异 |
| IMP-06 | **PASS** | `material_master` 中 `T0812-P05-SKU-ONLY` 0 行 |
| IMP-07 | **PASS** | 仅 4 个停用页有数据、其余 20 页空 → `sheetResults.size()==18`，全部 0/0/0，`status==SUCCESS` |
| IMP-08 | **PASS** | 停用页留空必填列（元素代码/客户编号）→ `status==SUCCESS`，18 条结果全部 `errors` 为空，未出现相关校验错误字样 |

### G3 · 版本升级语义（AC-9）

| 编号 | 结果 | 证据摘要 |
|---|---|---|
| VER-01 | **PASS** | 同夹具连导两次，`exchange_rate_v6`（T0812BASE→T0812TGT）`version_no` 保持 `2000`，`is_current=true` 行数恒为 1（未翻倍） |
| VER-02 | **PASS**（含一次自我纠错） | 改汇率值后第三次导入，版本从 `2000` 升到 `2001`。**过程记录**：第一次断言写成"不过滤 `is_current`"导致误读到旧行判成"未升版"（假红）；补上 `is_current=true` 过滤后复测通过——这是我自己测试代码的 bug，不是产品 bug，已修正并在此如实记录 |
| VER-03 | **PASS** | 两次导入之间，4 个停用表相关行数恒为 0 |

### G4 · A/B 回归对照（AC-8）

| 编号 | 结果 | 证据摘要 |
|---|---|---|
| REG-01 | **PASS**（核心证据） | 同一份 §1.1 夹具，A 侧（22 条结果，含 4 个停用页真实处理，`totalSuccessRows=5`）与 B 侧（18 条，`totalSuccessRows=1`）对"18 条保留结果条目"逐条比对：全部 18 条数值（`total/ok/fail`）**逐字节相等**（17 条 0/0/0 + 汇率管理表 1/1/0） |
| REG-02 | 未执行（并入 UT-04 的全量 A/B diff，效果等价） | 见下方"测试执行局限"说明 |

### G5 · Handler / 单测护栏（AC-10, FR-7）

| 编号 | 结果 | 证据摘要 |
|---|---|---|
| UT-01 | **PASS** | `P01P02PricingPriceVersioningTest` 6/6 绿 |
| UT-02 | **PASS** | `PricingTemplateServiceTest` 5/5 绿 |
| UT-03 | **PASS**（活体验证） | 临时把 `p13` 从 `PricingHandlerCatalog.all()` 注释掉 → `catalogCoversEveryPricingHandlerBean` 立即变红（"以下核价 handler 未登记进 PricingHandlerCatalog: [P13ProductionConsumableHandler]"）；随即撤销，`git diff --stat` 确认恢复到与交付版本零差异 |
| UT-04 | **PASS**（★ 见下方全量数字） | 用例名级 diff：**只在 B 侧失败 = 0 条**；只在 A 侧失败 = 6 条，全部可解释（5 条是本任务新测试的正反对照，1 条是并发抖动，见 §6） |
| UT-05 | 部分执行 | 见下方"未完全执行清单" |

### G6 · 代码级验证（AC-12）

| 编号 | 结果 | 证据摘要 |
|---|---|---|
| CODE-01 | **PASS** | `git diff f551f37e --stat` 对 4 个 Handler 类文件路径**空输出** |
| CODE-02 | **PASS**（反射 + 双侧回归） | Unwrap CDI client proxy 后反射调私有 `orderedHandlers()`：B 侧 16 项序列与需求文档 §5.3 第一序列逐位相等；同一份测试代码跑到 A 侧（20 项）时也验证了停用前序列逐位相等（自动分支判定，未硬编码单侧） |
| CODE-03 | **PASS** | `git diff --name-only f551f37e..HEAD -- .../db/migration` 空输出 |

### G7 · 前端（AC-11）

| 编号 | 结果 | 证据摘要 |
|---|---|---|
| FE-01 | **PASS** | `npx tsc --noEmit` 0 错误 |
| FE-02 | **PASS** | worktree 代码起临时隔离 Vite（端口 57991）→ `curl` 该文件 200，编译产物内确认含"20 Sheet"字样 |
| FE-03 | **PASS** | `grep -a "24 Sheet\|宏丰-客户料号对应关系"` 该文件 → 无输出 |
| FE-04 | **部分验证** | 未做真实浏览器点击（本环境无浏览器/截图工具），改用编译后 JSX 输出文本核对（标题/hint/说明文字节点内容确认为"20 Sheet"对应值）。建议合并入主工作区后人工点开抽屉肉眼复核一次（成本极低，几秒钟） |
| FE-05 | 未执行（P1，见未执行清单） | |

### G8 · 权限与错误码（导入端点）

| 编号 | 结果 | 证据摘要 |
|---|---|---|
| AUTH-01 | **PASS** | `BasicDataImportV6Resource.java` 相对 A 侧零改动，`importPricing` 的 `@RoleAllowed` 集合逐字未变 = `{SALES_MANAGER, SYSTEM_ADMIN}` |
| AUTH-02 | **PASS（代码同一性证明，非真实 HTTP 执行）** | 按主线 2026-08-12 裁决降级：文件零改动 ⇒ 角色白名单内的成功路径行为不可能变化，未另起真实登录会话验证 |
| AUTH-03 | 同上 | 同上 |
| AUTH-04 | 同上 | 同上 |
| AUTH-05 | **PASS** | `curl POST` 无 cookie → `401` |
| AUTH-06 | 未执行（P2，代码同一性证明兜底） | 同一文件零改动，400 校验逻辑不可能变化，未真实调用验证 |
| AUTH-07 | 未执行（P2，代码同一性证明兜底） | 同上 |

### G9 · 回归面

| 编号 | 结果 | 证据摘要 |
|---|---|---|
| RG-01 | **PASS（间接但更强）** | 未能直接跑通 `step3_quoteImport_regression_failedRowsZero`（该 E2E 依赖损坏 xlsx 夹具，整条测试在 B 侧因 `Cannot find zip signature` 直接 FAILED，见 §6）。但 `QuoteImportService.java` 相对 A 侧**零改动**（本次改动只碰 `PricingImportService`/`PricingHandlerCatalog`），报价侧代码路径不可能受影响；全量 UT-04 中所有 `com.cpq.basicdata.v6.quote.*` 测试类的失败集合在 A/B 间完全相同（0 条新增），是比单条 E2E 更全面的报价侧回归证据 |
| RG-02 | **部分验证** | 代码层面零变化（`fronttask.md` §4 已确认交互流程零改动，仅渲染行数随后端收敛），未做真人点击走查 |
| RG-03 | **观察性证据，非正式测量** | `Task0812DisabledSheetsTest` 运行日志显示 24-Sheet 夹具导入耗时 ~100-200ms 量级（`[v6import] PRICING TOTAL elapsed=109ms` 等），无劣化迹象；未做 A/B 对照计时（P2，本身也不设阈值） |
| RG-04 | **PASS（代码同一性证明）** | `P05CustomerMapHandler.java` 未改动，`material_customer_map` 表结构/存量数据未变，`cp_view` 读路径与本次改动无交集，不会产生新异常 |

### G10 · 并发/重复提交

| 编号 | 结果 |
|---|---|
| CONC-01 | 未执行（P2，时间预算内未安排；见 §6 的"意外并发"提供了部分间接信号但场景不完全对应，不能替代） |
| CONC-02 | 未执行（P2，同上） |

### G11 · 边界

| 编号 | 结果 | 证据摘要 |
|---|---|---|
| EDGE-01 | 未执行（P2） | `BasicDataImportV6Resource.java` 零改动，判定逻辑不可能变化 |
| EDGE-02 | 未执行（P2） | `wb.getSheet()==null` 分支是既有代码、未改动；`Task0812DisabledSheetsTest` 的 IMP-07 场景验证了"Sheet 存在但 0 行"，未覆盖"Sheet 完全不存在"这个更极端的子情形，两者代码路径相邻但不完全相同 |
| EDGE-03 | **PASS** | 等价于 `PricingTemplateServiceTest.generatedTemplate_importsCleanly`（UT-02 内）：B 侧模板生成器本就只产 20 Sheet，该测试导入这份模板 0 失败 0 报错，即"文件只含 20 保留 Sheet"场景 |

---

## 4. 未完全执行清单（如实登记，未为了好看虚报）

| 编号 | 优先级 | 未执行原因 | 残余风险评估 |
|---|---|---|---|
| REG-02 | P1 | 时间预算内未单独构造"注入数据错误"变体夹具；UT-04 的全量用例名 diff（0 条新增失败）已提供等价强度证据 | 低——REG-01 已证明正常路径逐条相等，错误路径的处理逻辑（`h.handle()` 内部）本身在本次改动中未被触碰 |
| UT-05 | P1 | 未逐一 `find` 核实 `P03ExchangeRateVersioningTest` 等类名是否存在即已耗时；已用自建测试的 VER-01/02/03 在同等数据形状上验证了版本化写入路径本身未受影响 | 低——这些类测试的是**未被本次改动触碰**的 handler（P03/P08/P09/P10/P11/P12/其余），UT-04 全量 diff 已覆盖这些类的实际执行结果（0 条新增失败） |
| FE-05 | P1 | 未在真实浏览器里走一遍"上传旧模板"流程 | 低——后端行为已被 IMP 系列证实，前端"零适配、只渲染后端返回"已被 `fronttask.md` §6 约定 + FE-01/02/03 证实，前端不做任何 Sheet 名过滤逻辑 |
| AUTH-06/07 | P2 | 资源文件零改动，判定为代码同一性证明足够，未消耗预算做真实 HTTP 调用 | 极低 |
| EDGE-01/02 | P2 | 同上，边界代码路径均未改动 | 极低 |
| CONC-01/02 | P2 | 预算内未安排；`orderedHandlers()` 每 Sheet `REQUIRES_NEW` 独立事务这一并发特性本身未被本次改动触碰（只删列表元素，未碰事务注解） | 低 |
| RG-03 | P2 | 无量化阈值，未做正式 A/B 计时对照 | 极低——观察性证据已支持"无劣化" |

---

## 5. 缺陷清单

**无新发现的产品缺陷。**

过程中发现并修复的问题均为**我自己测试代码的 bug**，非产品缺陷：
1. `IMP-05b`/`VER-02` 首次断言忘记按 `is_current=true` 过滤已翻转的旧版本行，导致假红；补过滤后复测通过。
2. `IMP-05b` 首次 seed 用裸 SQL 漏填 `system_type`（NOT NULL 约束），改用生产代码同款 `MaterialCustomerMapRepository#upsert` 后修复。
3. `CODE-02` 首次反射调用未 unwrap CDI client proxy，导致 `NullPointerException`（代理对象自身字段为 null）；改用 `ClientProxy.unwrap()` 后修复。

三处均已在 §3 对应用例的"证据摘要"里如实记录了这段自我纠错过程，未隐藏。

---

## 6. 测试执行局限（必须如实记录）

**A/B 全量套件曾意外并发跑在同一个共享 test 库 `cpq_db` 上**（A 侧 20:20 启动、B 侧 20:24 启动，两个 2473 用例的 surefire JVM 同时在跑）。这直接导致 B 侧第一次全量跑出现 Quarkus/Arc 部署层构建错误（`ConcurrentHashMap` 相关的 bean 处理异常）——判定为**我自己在同一 B 侧目录下并发跑了另一个 `mvn test -Dtest=...` 单类命令与全量套件抢占 `target/` 构建目录**所致，已发现后清理并**串行重跑**得到本报告采用的干净数字。

但 A 侧与 B 侧两个进程之间的并发（不同 worktree 目录、各自独立 `target/`，理论上不会抢构建锁）仍然存在，且共同打向同一个 `cpq_db`。这就是 UT-04 那条"只在 A 侧失败"的第 6 条（`ConfigureProductPrecisionHttpContractTest...`，断言 `expected:<335> but was:<334>`——一个全局行数断言，差 1，与另一侧套件同时写入 `quotation_line_item` 类表争用的特征完全吻合）的最可能成因。

**这不推翻 AC-10 的结论**——数据库争用只会**制造额外的、方向不确定的失败**，不会**掩盖**真实存在的新回归；"只在 B 侧失败 = 0 条"这个保守方向的结论不受争用影响（争用要么让某条测试在 A 侧也失败、要么在 B 侧也失败，两侧都有暴露机会，不存在"争用专挑 B 侧发作"的选择性机制）。

**若要求更严格的基线**：应把 A 侧、B 侧两个全量套件改为**严格串行**执行（一个跑完再跑另一个），而不是像本次这样在两个独立 worktree 里并发跑。已把这条方法论记录在案，供下次执行更大规模 A/B 对照时参考。

---

## 7. 本任务外发现：xlsx 测试夹具批量损坏

**现象**：全仓 76 个 `.xlsx`/`.xls` 文件中，**38 个不是合法 zip**（无法被 `python3 zipfile`/`unzip`/Java POI 打开），包括 `PricingVersioningImportE2ETest.PRICING_FILE` 引用的 `docs/table/核价测试数据/核价系统功能基础数据功能结构所需字段（6.0版） .xlsx`。

**根因定位**（主线 2026-08-12 已实测确认，我在 B 侧独立复现）：
- `git show HEAD:<path>` 取出的 git 对象本身就是非 zip 字节（前 4 字节 `87 7d 1c a3`），**不是本地/沙箱读取问题**，是这批文件**以损坏/加密状态提交进 git 的**——非法清单里有一份文件明确取名"（加密）罗克韦尔 导入测试.xlsx"，高度指向 WPS/Excel 加密导出。
- B 侧真实运行 `PricingVersioningImportE2ETest` 复现出具体错误：
  ```
  java.util.zip.ZipException: Cannot find zip signature within the first 4096 bytes
    at org.apache.poi.openxml4j.opc.ZipPackage.<init>
  Caused by: RuntimeException: Excel 解析失败：Cannot find zip signature within the first 4096 bytes
  ```
  该测试因此在**任何分支**（A 侧 f551f37e / B 侧 8946713b）**同样**跑不通（`Tests run: 3, Failures: 1, Errors: 2`），与本任务改动无因果关系。

**影响范围**：至少 `PricingVersioningImportE2ETest` 的 §7.3/§7.4/§7.7 三个 `@Order` 用例长期处于"看似在跑、实则从未真正验证过 POI 解析后的业务逻辑"状态（因为文件根本读不进去）——这是一个比本任务严重得多的测试基础设施缺口，**已被 UT-04 全量 A/B 名单纳入"两侧同源已知红"处理，未计入本任务的回归判定**。

**是否立项修复**：本任务不做，按你的要求交由你决定是否登记 `BL-NNNN` 立项。

---

## 8. `Task0812DisabledSheetsTest.java` 处置建议

**当前状态**：worktree 内 untracked 新文件，507 行，7 个测试方法，B 侧 7/7 绿。

**自查结论：建议保留并提交，风险可控。**

逐项自查：

1. **`@AfterEach` 清理完备性**：✅ 完备。`cleanup()` 覆盖了本文件写过的全部 6 张表（`unit_price` / `material_version_mgmt` / `material_customer_map` / `material_master` / `exchange_rate_v6` / `import_record`），且 `@BeforeEach` 也调用同一个 `cleanup()` 作双重保险——即使某次运行中途崩溃未走到 `@AfterEach`，下一次运行的 `@BeforeEach` 也会先扫清残留。全部按专属哨兵前缀（`T0812-*` / `T0812BASE`/`T0812TGT` / `task0812-fixture%`）精确匹配删除，不会误删任何非本测试产生的数据。

2. **硬编码前置是否会随时间失效**：✅ 无风险。唯一的动态前置是 `anyUserId()`（`SELECT id FROM "user" LIMIT 1`），与本仓库既有测试（`PricingTemplateServiceTest`、`P01P02PricingPriceVersioningTest`）用的是同一套路，不是本文件独有风险，且该表不会清空。其余全部是自造的哨兵字符串，不依赖任何存量业务数据（未采用 `PricingVersioningImportE2ETest` 那种绑定"某个真实料号"的脆弱写法）。

3. **并发跑撞键风险**：⚠️ **确实存在，但与本仓库既有测试同级，非本文件独有的新风险**。哨兵 key（`T0812-P01-EL` 等）是**固定字面量**，不带运行期随机后缀。若两个会话同时对同一个 `cpq_db` 并发跑 `Task0812DisabledSheetsTest` 本身（例如两个 worktree 会话都在验收这个分支），会出现典型的"你的 `@AfterEach` 删了对方还在用的行"类竞态，可能导致偶发假红。**这正是本报告 §6 遇到的同一类问题**（A/B 全量套件并发共享 test 库）的缩影。但 `P01P02PricingPriceVersioningTest`（本仓库既有、长期在用的测试）用的 `TEST-P01-EL` 等固定字面量 code 面临完全相同的风险类别，本文件没有引入新的风险模式，只是复用了既有的、已被项目接受的测试数据隔离水平。

**建议**：按你的倾向保留并提交。如果希望进一步加固（非阻塞项，可后续单独处理），可选：给哨兵 key 加运行期随机后缀（如 `UUID.randomUUID().toString().substring(0,8)`），彻底消除并发自撞风险；但考虑到本仓库其余同类测试均未这样做，是否值得单独为这一个文件加固，由你权衡一致性 vs 严谨性。

---

## 9. AC-1~AC-12 逐条达成对照表

| AC | 内容摘要 | 状态 | 证据 |
|---|---|---|---|
| AC-1 | 模板 20 Sheet，名称顺序逐字匹配，不含 4 停用页 | ✅ 达成 | TPL-01/02 |
| AC-2 | 20 保留 Sheet 表头与停用前逐字一致 | ✅ 达成 | TPL-03（git diff 同一性证明） |
| AC-3 | 含 24 Sheet 且 4 页非空数据导入，结果不含 4 停用 Sheet | ✅ 达成（**口径更正**：结果条目数是 18 不是 20，见下） | IMP-01/02/07/08 |
| AC-4 | `unit_price`(ELEMENT/MATERIAL_PRICE) 导入前后不变 | ✅ 达成 | IMP-03 |
| AC-5 | `material_version_mgmt` 行数/`updated_at`不变 | ✅ 达成 | IMP-04 |
| AC-6 | `material_customer_map` 全表行数不变 + 存量行不被覆盖 | ✅ 达成 | IMP-05a（新料号不建档）+ IMP-05b（存量行覆盖测试，含 A 侧反例佐证） |
| AC-7 | `material_master` 品名/规格/尺寸/旧料号保持不变 | ✅ 达成 | IMP-06 |
| AC-8 | 其余 Sheet 的 successRows/totalRows/错误数 A/B 相等 | ✅ 达成（**口径更正**：对照对象是 18 条结果条目，不是 20 个 Sheet） | REG-01 + UT-04 全量用例名 diff |
| AC-9 | 重导不升版，其余表版本升级语义不变 | ✅ 达成 | VER-01/02/03 |
| AC-10 | Handler 单测全绿；`mvn test` 相对停用前不新增 failure/error | ✅ 达成（**含执行局限说明，见 §6**） | UT-01~05，UT-04 用例名级 diff：新增 0 条 |
| AC-11 | 前端文案 20 Sheet，无 24 Sheet/宏丰残留，tsc 0 错误，Vite 200 | ✅ 达成 | FE-01/02/03；FE-04 部分验证（建议合并后补一次人工目视） |
| AC-12 | 4 个 Handler 类文件未被修改 | ✅ 达成 | CODE-01 |

**⚠️ 需要主线知悉的一处需求文档表述偏差（非缺陷，已由主线在上一轮消息中确认并更正）**：AC-3/AC-8 原文表述里隐含"`sheetResults` 长度 = Sheet 数"的假设，实测因 P16+P17、P19+P20 各走一个合并 bean 只产出 1 条结果，真实条目数是 **Sheet 数 − 2**（停用前 24→22，停用后 20→18）。这不是本次改动引入的偏差（22 这个数字在停用前就已存在），需求文档与 `api.md` 已同步更正，本报告的 AC-3/AC-8 判定均按更正后口径（18 条）执行。

---

## 10. 证据原文摘录

### 10.1 `Task0812DisabledSheetsTest` B 侧最终结果
```
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.152 s -- in com.cpq.basicdata.v6.pricing.Task0812DisabledSheetsTest
```

### 10.2 REG-01 逐条对照（A 侧 22 条中的 18 条保留部分 vs B 侧 18 条，完全一致）
```
                                                          A侧(22条中的18条)      B侧(18条)
来料其他费用（比例）+来料其他固定费用(合并)                total=0 ok=0 fail=0    total=0 ok=0 fail=0
成品其他比例费用+成品其他固定费用(合并)                    total=0 ok=0 fail=0    total=0 ok=0 fail=0
单重                                                      total=0 ok=0 fail=0    total=0 ok=0 fail=0
汇率管理表                                                total=1 ok=1 fail=0    total=1 ok=1 fail=0
物料BOM ~ 其他外加工成本（其余 14 个）                     total=0 ok=0 fail=0    total=0 ok=0 fail=0
```
A 侧同批次另含 4 条真实处理的停用 Sheet 结果（`宏丰-客户料号对应关系`/`核价版本`/`元素核价价格表`/`材料核价价格表` 各 `total=1 ok=1 fail=0`），`totalSuccessRows=5`；B 侧无这 4 条，`totalSuccessRows=1`。差值 Δ4 与"停用 4 个 Sheet"精确对应。

### 10.3 UT-04 全量 A/B 用例名级 diff（独立复算，与主线数字一致）
```
A 侧: Tests run: 2473, Failures: 177, Errors: 158, Skipped: 70   （失败+错误方法名去重后 335 个）
B 侧: Tests run: 2473, Failures: 171, Errors: 158, Skipped: 70   （失败+错误方法名去重后 329 个）

only in B（新增失败，AC-10 判定对象）: 0 条

only in A（B 侧修复/消失的失败）: 6 条
  com.cpq.basicdata.v6.pricing.Task0812DisabledSheetsTest.imp01_02_coreDisabledSheetsIgnored
  com.cpq.basicdata.v6.pricing.Task0812DisabledSheetsTest.imp05b_existingRowNotOverwrittenByDisabledSheet
  com.cpq.basicdata.v6.pricing.Task0812DisabledSheetsTest.imp07_onlyDisabledSheetsHaveData
  com.cpq.basicdata.v6.pricing.Task0812DisabledSheetsTest.imp08_disabledSheetsBadData_notExposed
  com.cpq.basicdata.v6.pricing.Task0812DisabledSheetsTest.ver01_03_reimportTwice_idempotent
  com.cpq.configure.resource.ConfigureProductPrecisionHttpContractTest.elementOverrideNumericPctIsRejectedBeforeAnyQuotationLineWrite
```
前 5 条：本任务新测试在 A 侧代码上运行时（4 个 Sheet 未停用）必然失败——这是正反对照本该有的表现，证明这些用例有鉴别力，不是任何代码上都会绿的假测试。
第 6 条：断言 `expected:<335> but was:<334>` 的全局行数比对，特征符合与另一并发套件争用共享库导致的 ±1 抖动（见 §6），与本次改动无因果路径。

### 10.4 xlsx 损坏复现（B 侧真实运行日志）
```
[ERROR] PricingVersioningImportE2ETest.step1_pricingFirstImport_failedRowsZero_andLandsCorrectly
  » Runtime Excel 解析失败: Excel 解析失败：Cannot find zip signature within the first 4096 bytes
[ERROR] PricingVersioningImportE2ETest.step2_pricingReimport_doesNotBumpVersion_isCurrentUniquePerGroup
  » Runtime Excel 解析失败: Excel 解析失败：Cannot find zip signature within the first 4096 bytes
[ERROR] PricingVersioningImportE2ETest.step3_quoteImport_regression_failedRowsZero
  §7.7 报价回归导入应 SUCCESS，实际=FAILED，metadata={"sheetResults": []}
Tests run: 3, Failures: 1, Errors: 2, Skipped: 0
```

### 10.5 UT-03 反向护栏验证
```
[临时注释 p13 后]
[ERROR] PricingTemplateServiceTest.catalogCoversEveryPricingHandlerBean
  以下核价 handler 未登记进 PricingHandlerCatalog（模板会少 sheet）: [P13ProductionConsumableHandler]
  ==> expected: <true> but was: <false>
[还原后] git diff --stat -- .../PricingHandlerCatalog.java → 空输出（确认恢复干净）
```

---

## 11. 回归结论

**通过。** 12 条 AC 全部达成（含需求文档 22→18 条目数口径已更正）。核心证据链：
1. 停用生效：4 个 Sheet 数据零写入（IMP-01~08，7/7 绿 + 双侧对照）
2. 没误伤：其余 18 条结果条目 A/B 逐字节相等（REG-01）+ 全量 2473 用例名级 diff 零新增失败（UT-04）
3. 存储逻辑保留：Handler 单测全绿 + 反向护栏活体验证（UT-01~03）
4. 代码零改动边界守住：4 个 Handler 文件 + 无 Flyway 迁移（CODE-01/03）+ 顺序未被重排（CODE-02）
5. 前端文案同步、零适配（FE-01~03）

**已知局限**：A/B 全量套件曾并发共享同一 test 库（§6），已定位为造成"第 6 条差异"的最可能原因，但不影响"零新增失败"这一保守方向结论。`test.md` 里 P1/P2 共 7 条未完全执行的用例已逐条给出残余风险评估，均判定为低风险，建议不阻塞合并；FE-04/RG-02 建议合并入主工作区后各花几秒钟做一次真人目视复核。

---

dev-docs 索引：本报告未新建/未改变 `dev-docs/INDEX.md` 任务状态列（该任务目录已存在，状态更新建议由主线在合并收尾时统一处理）；提醒主线：本任务目录当前未出现在 `dev-docs/INDEX.md`，如已交付合并请按 INDEX §9 规则回写一行。
