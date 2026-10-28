# 子代理原始回报

> 按 `docs/rules/subagents.md` §1⑥，先逐字落盘，再进行分析与回流。

## 2026-10-27 · frontend · 实现期契约拦截

> 发现独立测试代理新增的 `cpq-frontend/src/utils/task261027RoleDisplayPrecision.test.ts` 与 AC/F-1 契约不一致：测试把角色上限当作 `formatNumber/resolveDecimals` 的第三/第二个位置参数传入；fronttask 明确要求扩展 `DecimalSpec` 的可选最大显示位数，当前实现为 `spec.maxDisplayDecimals`。我不会越界修改该测试；建议测试代理改为 `{..., maxDisplayDecimals: limit}`，否则该文件 3 个角色上限用例会假红。

## 2026-10-27 · backend · 阶段回报

> 后端 B-1~B-7 实现已落 worktree，纯编译通过；我方 5 个定向纯单测类共 18 tests 全绿。Resource 已从 SessionHelper 单次取角色并显式下传 HTML/PDF/报价Excel/Excel视图/比对Excel/邮件，旧服务签名保留默认9位；普通 JSON/保存/快照未动。独立测试代理现可执行 S-UNIT 后端片；我未修改 `cpq-backend/src/test/java/com/cpq/task261027rdp/`。

## 2026-10-27 · frontend · 独立测试第二次契约拦截

> 独立前端测试已把上限改进 DecimalSpec，位置参数冲突已消失；但 `task261027RoleDisplayPrecision.test.ts` 当前仍有 5 个 AC-9 假红：用 `1.234567891500` 断言 `formatNumber(...,{isComputed:false})` 必须逐字保留尾零，实际既有通用格式化会规范化为 `1.2345678915`。AC-9要求“不套销售4位上限/保持现有显示”，并不改变既有去尾零行为。建议测试改为断言销售上限前后相同，或用无尾零原值。我的相关 11 文件定向测试 167/167 通过，tsc通过。

## 2026-10-27 · frontend · 阶段自检

> 前端实现当前完成并通过：`npx tsc -b`（当前状态）0 输出成功；`npm test` 115 files / 1470 tests 全过；`npm run build` 3441 modules transformed，构建成功（仅既有 >500kB chunk warning）。独立测试代理的 AC-9 断言已修正，8/8 已纳入全量通过。未运行 Playwright/共享服务，按任务边界留给测试路和主线隔离验收。正在做最终文件/边界清单，不会提交。

## 2026-10-27 · backend · 最终回报（逐字）

> 后端 B-1～B-8 已实现，未提交、未打包、未新增迁移。
>
> ## 实现摘要
>
> - **B-1 / AC-1、2、3、6**
>   - 新增纯函数角色策略：销售代表/销售经理 4 位，定价经理/系统管理员 9 位。
>   - `NumberFormatUtil` 增加可选上限重载，计算值取“显式位数、全局 9 位、角色上限”的最小值。
>   - 保留旧签名；非计算值忽略角色上限。
>   - 保持 `HALF_UP`、去尾零和负数边界语义。
>
> - **B-2 / AC-2、5、10**
>   - `QuotationResource` 在 HTML、PDF、报价 Excel、Excel 视图导出和邮件发送入口中，从当前会话解析角色一次并显式传递上限。
>   - 未增加 body/query/header 参数，权限注解和响应契约未变。
>
> - **B-3 / AC-3、5、6、9**
>   - HTML/PDF 和报价 Excel 的金额、单价、折扣金额、小计、税额及合计应用角色上限。
>   - 税率、折扣率、属性值、原始行 JSON 保持既有精度。
>   - 报价 Excel 计算金额仍使用精度安全的字符串单元格。
>
> - **B-4 / AC-3、5、9**
>   - Excel 视图的 `FORMULA`、`CARD_FORMULA`、`TAB_JOIN_FORMULA` 结果按角色格式化。
>   - `EXCEL_FORMULA` 保留真实公式单元格，通过数字格式应用角色/显式位数上限。
>   - 原始和取数列未改。
>
> - **B-5 / AC-5、10**
>   - 比对 Excel 由 `CostingSheetResource` 读取服务端会话角色。
>   - 报价值和核价值统一应用 4/9 位上限，请求 DTO 未变化。
>
> - **B-6 / AC-5、10**
>   - 邮件服务显式接收显示上限，并同时传给 HTML 和可选 Excel 附件。
>   - 收件人校验、仅 APPROVED 可发送及既有 `SENT` 状态流转未改。
>
> - **B-7 / AC-7、8**
>   - 角色处理仅位于输出边界。
>   - 未改 DTO、实体、保存、重算、快照、对账、历史单读取及计算 12 位口径。
>   - 未新增查询或循环内会话/用户查询。
>
> - **B-8 / AC-1～3、5～11**
>   - 补充角色策略、通用格式化、报价 Excel、Excel 视图及比对 Excel单测。
>   - 回写六个输出端点及精度矩阵到 `dev-docs/main-api.md`。
>   - 独立测试代理的纯单元契约测试已执行；其 Quarkus 输出流测试仅编译，未连接共享数据库执行。
>
> ## 实际改动文件
>
> 生产代码：
>
> - `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/main/java/com/cpq/common/NumberFormatUtil.java`
> - `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/main/java/com/cpq/quotation/service/QuotationDisplayPrecisionPolicy.java`
> - `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java`
> - `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/main/java/com/cpq/quotation/service/QuotationExportService.java`
> - `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java`
> - `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/main/java/com/cpq/quotation/service/QuotationEmailService.java`
> - `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/main/java/com/cpq/costing/resource/CostingSheetResource.java`
> - `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/main/java/com/cpq/costing/service/ComparisonExportService.java`
>
> 后端测试：
>
> - `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/test/java/com/cpq/common/NumberFormatUtilTest.java`
> - `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/test/java/com/cpq/costing/ComparisonExportServiceTest.java`
> - `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/test/java/com/cpq/quotation/service/ExcelViewServicePrecisionTest.java`
> - `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/test/java/com/cpq/quotation/service/QuotationDisplayPrecisionPolicyTest.java`
> - `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/test/java/com/cpq/quotation/service/QuotationExportRolePrecisionTest.java`
>
> 文档：
>
> - `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/dev-docs/main-api.md`
>
> 未修改独立测试代理目录及任何前端文件。
>
> ## 命令与精确结果
>
> 1. 编译：
>
> ```text
> ./mvnw -DskipTests compile
> Compiling 1031 source files
> BUILD SUCCESS
> Total time: 10.073 s
> 返回码：0
> ```
>
> 2. 首轮定向测试：
>
> ```text
> Tests run: 18, Failures: 0, Errors: 1, Skipped: 0
> 返回码：1
> ```
>
> 发现纯策略对 `null` 角色执行 switch 导致 NPE；已补兼容回退为既有 9 位。生产入口仍由现有鉴权和四角色白名单保护。
>
> 3. 修复后定向测试：
>
> ```text
> Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
> BUILD SUCCESS
> Total time: 19.465 s
> 返回码：0
> ```
>
> 4. 最终 clean 定向构建：
>
> ```text
> ./mvnw clean -Dtest=NumberFormatUtilTest,QuotationDisplayPrecisionPolicyTest,ExcelViewServicePrecisionTest,ComparisonExportServiceTest,QuotationExportRolePrecisionTest test
> Compiling 1031 main source files
> Compiling 813 test source files
> Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
> BUILD SUCCESS
> Total time: 15.630 s
> 返回码：0
> ```
>
> 5. 独立测试代理纯单元契约：
>
> ```text
> ./mvnw -Dtest=com.cpq.task261027rdp.Task261027RoleDisplayPrecisionUnitTest test
> Compiling 1031 main source files
> Compiling 814 test source files
> Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
> BUILD SUCCESS
> Total time: 12.776 s
> 返回码：0
> ```
>
> 最终共执行 **21 个互不重复的定向测试，全部通过**。
>
> 6. 格式与越界检查：
>
> ```text
> git diff --check
> 返回码：0，无输出
> ```
>
> 迁移、DTO、实体、`QuotationService`、backfill 范围差异检查：
>
> ```text
> 返回码：0，无输出
> ```
>
> ## 未验证项
>
> 因没有一次性隔离数据库，且明确禁止连接共享开发/测试库，以下未执行：
>
> - 四种真实会话下的 HTTP 200/401/权限联调。
> - 六种实际输出及 MockMailbox 附件的端到端解析。
> - 保存前后数据库值、快照、修改时间逐字比较。
> - 连续退出登录切换角色验证。
> - 实际 SQL 数量采样。
>
> 独立测试代理的 `Task261027RoleDisplayPrecisionOutputFlowTest` 已通过 `testCompile`，但未运行。未使用共享 dev server，也未启动 Quarkus。
>
> ## 过程中规避掉的坑
>
> - 没有把角色信息隐藏进通用格式化器或服务层 HTTP 上下文。
> - 没有把税率、折扣率、利润快照、原始行 JSON 一并压成 4 位。
> - 没有把 Excel 计算金额经 `double` 写入而损失精度。
> - 没有把 `EXCEL_FORMULA` 改成静态文本，保留公式行为，仅控制显示格式。
> - 没有新增查询或把会话解析放入产品行、组件行、单元格循环。
> - 没有修改迁移、数据库、计算 12 位、保存或快照链路。
> - 没有执行部署、打包或提交。
>
> **已自检：改动范围、构建、21 个定向测试、格式检查和零迁移检查均已核对；上述集成项明确保留为未验证。**

## 前端最终报告（原文）

前端 F-1～F-7 已完成，未提交 commit。

实现对应关系：

- F-1 / AC-1、2、3、6：新增报价角色纯函数与 Hook，销售代表/经理映射 4 位，定价经理/管理员映射 9 位；`DecimalSpec.maxDisplayDecimals` 仅约束 `isComputed=true`，继续 HALF_UP、去尾零。
- F-2 / AC-1、2、4：报价列表、Wizard Step2/3、产品卡片、页签小计/合计、详情汇总及快照计算值接入角色上限；`ComponentCell` 编辑/只读调用均显式传参。
- F-3 / AC-3、4、9：编辑/只读 Excel 及旧 Excel 入口接入；计算百分比列受角色上限，原始列、输入、汇率、税率、利润和基础资料不受影响。
- F-4 / AC-2、3、4、6：`ComparisonBoard` 获取上限后传入 `ComparisonTable` 和纯格式化函数；报价值、核价值、差异值统一显示口径，阈值判定仍使用原始 decimal string。
- F-5 / AC-5：未修改任何导出/发送请求、请求体或 service，未向服务端传角色或显示位数。
- F-6 / AC-7、8：未修改 `formatFormulaResult`、`formatProductCardSubtotal`、`formatQuotationTotal`、草稿 payload、快照、对账或缓存；角色仅同步派生并触发文本重渲染。
- F-7 / AC-9～11：补充角色映射、格式化边界、共享单元格、Excel、比选测试。

实际改动文件：

- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/utils/formatNumber.ts`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/utils/formatNumber.test.ts`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/quotationResultDisplayScale.ts`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/quotationResultDisplayScale.test.ts`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/QuotationList.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/QuotationWizard.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/QuotationStep2.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/QuotationStep3.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/ProductDetailViews.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/components/ComponentCell.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/components/ComponentCell.readonlyPrecision.test.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/components/SnapshotTab.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/ExcelView.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/LinkedExcelView.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/ReadonlyExcelView.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/excelCellFormat.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/excelCellFormat.test.ts`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/ComparisonBoard.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/ComparisonTable.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/comparisonMapping.ts`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/comparisonMapping.test.ts`

验证结果：

- `npx tsc -b`：退出 0，无错误输出。
- 定向测试：11 个文件、167 条测试全部通过。
- `npm test`：`115 passed`，`1470 passed`，退出 0。
- `npm run build`：退出 0；`3441 modules transformed`，`✓ built in 1.26s`。仅有既有的大 chunk 警告。
- 新增纯函数及测试定向 ESLint：退出 0。
- 全部改动文件 ESLint：因报价大文件既有 lint 债务未通过，输出 `547 problems (533 errors, 14 warnings)`；典型为原有 `any`、未使用导入及 JSX-in-try，并非本任务类型/构建失败。
- `git diff --check -- cpq-frontend/src`：退出 0，无输出。
- 未安装依赖、未启动开发服务器、未运行全局 Playwright。

未验证项：

- 未做四角色浏览器连续切换、页面 `加载中…=0`、控制台错误计数和保存前后网络响应逐字对比。
- 未运行本任务 E2E；按分工由测试路和主线在隔离栈执行。
- 后端导出、邮件附件及权限行为不在本前端分工内验证。

过程中规避掉的坑：

- 通用 `formatNumber` 未导入认证 store，报价外调用保持最多 9 位。
- 角色 Hook 只订阅原始 `user?.role`，未增加 effect、本地角色 state 或无意义 memo。
- 未把 4 位显示文本写入计算、保存、快照、缓存或对账链路。
- 输入、基础数据、税率、汇率、利润徽标及客户累计金额未误套角色上限。
- 未修改 `profitBadge.ts`、导出 service、后端、任务文档或其他代理的 E2E。
- 共享 worktree 中后端、测试和文档改动均为其他代理产出，未覆盖或回退。

已自检：实现、类型检查、全量前端单测、生产构建和 diff 检查均有上述可复核证据；浏览器/E2E 明确标记为未验证。

## 独立测试用例设计（原文）

用例设计完成（等待主线审核/解锁；未执行 S-UNIT 或 S-FLOW）。

一、新文件（均在指定 worktree，未改业务实现、未 commit）
1. /home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/test/java/com/cpq/task261027rdp/Task261027RoleDisplayPrecisionUnitTest.java
2. /home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/test/java/com/cpq/task261027rdp/Task261027RoleDisplayPrecisionOutputFlowTest.java
3. /home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/utils/task261027RoleDisplayPrecision.test.ts
4. /home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/e2e/task261027-role-display-precision-flow.spec.ts
5. /home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/e2e/task261027-role-display-precision.config.ts

二、测试编号 → AC → 可观测断言 → 真实前置数据
S-UNIT（不连库、不造数）：
- T261027_RDP_UNIT_BE_01 + FE_01 → AC-3 → 显式2位在4/9上限均为1.23；显式6位在4位上限为1.2346、9位上限为1.234568 → 纯 BigDecimal/decimal string `1.2345678915`。
- T261027_RDP_UNIT_BE_02 + FE_02 → AC-6 → 12.500000000000→12.5；1.23444→1.2344；1.23445→1.2345；负边界同为HALF_UP；-0.00001→0 → 无 DB。
- T261027_RDP_UNIT_BE_03 + FE_03(5个展开用例) + FE_04 → AC-9 → 用户输入/汇率/税率/利润快照/基础资料取数在 `isComputed=false` 时，加 `maxDisplayDecimals=4` 前后结果相等；计算值受4位限制；省略角色上限继续9位 → 无 DB。已按两轮主线审查修正：角色上限走 DecimalSpec.maxDisplayDecimals；AC-9 比较角色上限前后不变量，不误把既有去尾零当回归。

S-FLOW 后端真实输出（@QuarkusTest；只可在一次性库运行）：
- T-07a → AC-5 → SALES_MANAGER 真实 session 调 HTML/PDF/报价Excel/Excel视图/比对Excel/send，逐个解析 HTML、xlsx 字符串单元格、MockMailbox HTML/Excel 附件，均含1.2346、不含1.234567892/1.2345678915；GET和DB金额/JSONB快照前后逐字相等。
- T-07b → AC-5 → SYSTEM_ADMIN 同六输出均含1.234567892、不含1.2346/原值；保存值不变。
- T-10a → AC-10 → 未登录 GET + 六输出状态码精确 `[401,401,401,401,401,401,401]`。
- T-10b → AC-10 → SALES_MANAGER 在 query/body/header 伪造 SYSTEM_ADMIN、displayDecimals=9，HTML和比对Excel仍为1.2346。
- 真实数据：测试自行创建 `T261027_RDP_OUTPUT_SM/ADMIN` 两账号、`T261027_RDP_FLOW_OUTPUT_` 客户/模板/两张 APPROVED 报价；每张关系型金额、quote_card_values、quote_excel_values均含1.2345678915；结束只按精确ID/前缀删除自己的报价、客户、模板、用户。若开跑前同子前缀非0则硬失败，不自动扩大清理。

S-FLOW 页面真实会话（Playwright，6 tests）：
- T-04 → AC-1/4 → SALES_REP、SALES_MANAGER 分别在报价列表、编辑公式格、产品小计、报价汇总、详情公式格/小计/汇总显示1.2346；GET中目标原string非空。
- T-05 → AC-2/4 → PRICING_MANAGER、SYSTEM_ADMIN 同入口显示1.234567892；GET原string非空。
- T-06 → AC-4 → 四角色逐一进入Excel视图/比对视图，真实表格非空、目标显示按4/9位、所有入口“加载中”和可见spinner最终0、无红色Alert/路由错误遮罩。
- T-08 → AC-7 → SALES_MANAGER仅把报价名称改为前缀文本并保存；GET中目标canonical string数量不变；DB original/total、行金额、quote_card_values/quote_excel_values逐字相等；finally恢复原名称。
- T-09 → AC-8 → 同一浏览器 SALES_REP→真实logout→SYSTEM_ADMIN→真实logout→SALES_REP，公式格4/9/4恢复；每步DB updated_at、金额、快照全对象相等。
- T-10 → AC-10 → 匿名 GET+六输出逐一401；销售经理伪造query/body/header仍输出4位。
- 真实前置：主线在一次性库提供4个互异的 `T261027_RDP_` 账号；一张四角色均按既有权限可查看/编辑的 DRAFT 报价，quotationNo和partNo均以 `T261027_RDP_FLOW_` 开头，名称非空；公式格、产品小计、报价总额、Excel视图和比对视图都真实含canonical `1.2345678915`。spec beforeAll用SQL硬验至少1条产品行且DB快照非空含目标值，避免空跑。
- T-11 → AC-11 → 不另写恒真断言；收集为解锁后真实命令：前端tsc/定向Vitest/build/报价主链Playwright，后端角色精度/导出定向测试与构建，并记录测试数、失败数、同轮基线新增失败=0。

三、已执行的静态收集（不连库、不启动服务）
- `./mvnw -DskipTests test-compile`：exit 0，BUILD SUCCESS；1031 main sources + 814 test sources；11.781s；0 compile error（仅既有 deprecated/unchecked warnings）。
- `npx eslint src/utils/task261027RoleDisplayPrecision.test.ts e2e/task261027-role-display-precision-flow.spec.ts e2e/task261027-role-display-precision.config.ts`：exit 0，0 errors。
- 定向 `npx tsc --ignoreConfig --noEmit ...`：exit 0，0 errors。
- `npx vitest list src/utils/task261027RoleDisplayPrecision.test.ts`：exit 0，收集8 tests（AC-9语义矩阵展开为5条）。
- 专用 Playwright config `--list`：exit 0，精确收集6 tests / 1 file；无 globalSetup、无 webServer，workers=1，系统Chrome，globalTimeout=30分钟，显式拒绝5174/8081和cpq_db/cpq_db_0724/cpq_db_test，要求一次性库名 `cpq_t261027_rdp_*`。
- 5个新文件 whitespace check：均无输出。

四、尚未执行/未验证
- S-UNIT 两条执行命令未跑，等待主线审核后解锁：
  `cd .../cpq-backend && ./mvnw -Dtest=Task261027RoleDisplayPrecisionUnitTest test`
  `cd .../cpq-frontend && npx vitest run src/utils/task261027RoleDisplayPrecision.test.ts`
- S-FLOW 后端未跑，等待主线提供一次性 DB 环境变量：
  `DB_HOST=<host> DB_PORT=<port> DB_NAME=cpq_t261027_rdp_<run> DB_USERNAME=<user> DB_PASSWORD=<pwd> ./mvnw -Dtest=Task261027RoleDisplayPrecisionOutputFlowTest test`
- S-FLOW Playwright 未跑，等待一次性库、独立前后端端口、4账号与专用报价环境变量；开跑前必须先预热目标详情页，再计30分钟。Browser plugin本会话不可用；按技能约束仅在主线明确解锁后使用仓库Playwright。
- AC-11全回归未验证。

五、过程中规避掉的坑
- 没用默认 playwright.config/globalSetup，避免它更新共享 cpq_db_0724 用户表。
- 专用配置模块加载即拒绝共享端口/固有库/非前缀库；账号、报价号、料号、UUID不合规直接硬失败。
- 没读 backend src/main、frontend非测试实现或实现diff；只读任务三文档、测试目录、pom/package/测试配置。公开 DecimalSpec 调用形态来自主线审用例回流，不是实现反推。
- AC-9 不再逐字要求尾零，改为“加角色上限前后不变量”；避免5条假红。
- 共库全局计数全部避免；DB断言只按指定quotation UUID/本前缀对象。
- 输出夹具账号子前缀使用 `T261027_RDP_OUTPUT_`，避免与主线提供的四个页面账号碰撞。
- 未运行默认Playwright、未启动服务、未连接5174/8081/共享库、未执行清库/DROP、未修改admin/alice/bob、未提交commit。

已自检：工作区/分支坐标正确；只新增5个测试文件；后端 test-compile BUILD SUCCESS（1031+814）；前端定向ESLint/TypeScript 0错误；Vitest收集8条、Playwright收集6条；S-UNIT/S-FLOW均明确未验证。

## 独立测试最终报告（原文）

用例设计与代码已完成，已向主线提交完整覆盖映射、真实前置数据、文件路径和命令证据。

静态检查结果：

- 后端 `test-compile`：BUILD SUCCESS，1031 个主源码、814 个测试源码。
- 前端定向 ESLint、TypeScript：0 错误。
- Vitest：收集 8 条用例。
- Playwright：专用隔离配置收集 6 条用例，无默认 globalSetup。
- S-UNIT、S-FLOW 均未执行，等待主线审核解锁。
- 未连接数据库或共享端口，未启动服务，未提交 commit。

已自检：仅新增 5 个自有测试文件；未读取受限业务实现，未修改业务代码或其他工程师测试。

## 独立测试 S-UNIT 执行回报（原文）

S-UNIT 执行回报（仅执行已解锁两条；S-FLOW 未执行）

一、后端 S-UNIT
命令：
`cd /home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend && ./mvnw -Dtest=Task261027RoleDisplayPrecisionUnitTest test`

精确结果：
- 返回码：0
- `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`
- `BUILD SUCCESS`
- Surefire 测试耗时：`0.043 s`
- Maven Total time：`13.008 s`
- 工具墙钟：`13.69675369 s`
- 编译计数：1031 main sources、814 test sources
- 通过断言：AC-3 显式2/6位与4/9上限取小；AC-6 尾零/HALF_UP/负数/负零；AC-9 非计算值忽略角色上限且三参默认仍9位。

二、前端 S-UNIT
命令：
`cd /home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend && npx vitest run src/utils/task261027RoleDisplayPrecision.test.ts`

精确结果：
- 返回码：0
- `Test Files 1 passed (1)`
- `Tests 8 passed (8)`
- Vitest Duration：`129ms`（transform 30ms、import 42ms、tests 4ms）
- 工具墙钟：`0.425966381 s`
- 8条构成为：AC-3 1条、AC-6 1条、AC-9五类非计算语义展开5条、默认9位/计算值受限1条。

三、汇总
- 本轮共执行：11 tests
- 通过：11
- 失败：0
- 错误：0
- 跳过：0
- S-UNIT：全绿
- S-FLOW：未验证（未运行 Quarkus 输出流、未连数据库、未启动服务、未运行 Playwright）

四、过程中规避掉的坑
- 开跑前采样确认指定 worktree 无 Maven/Quarkus/Vitest/Vite/Playwright 进程，避免同 target 并发构建导致陈旧报告或 class 被互删。
- 后端只点名纯 JUnit 类；没有运行 `Task261027RoleDisplayPrecisionOutputFlowTest`，未触发 Quarkus/数据库。
- 前端只点名单个 Vitest 文件；未运行默认 Playwright/globalSetup。
- 未读取业务实现、未修改任何文件、未连接5174/8081或共享库、未提交 commit。

已自检：两条获批命令均在指定绝对 worktree 内执行；后端3/3、前端8/8，总计11/11通过；S-FLOW明确未验证。

## 独立测试 S-UNIT 最终简报（原文）

S-UNIT 已执行完成：

- 后端：3/3 通过，0 failures，0 errors，耗时 13.008s。
- 前端：8/8 通过，1 个测试文件，Vitest 耗时 129ms。
- 合计：11/11 通过。
- S-FLOW 未执行；未连接数据库、未启动服务或 Playwright。
- 未修改文件，未提交 commit。

已自检：仅运行主线解锁的两条 S-UNIT 命令，结果已完整回传。

## 前端主线审查修复报告（原文）

两项主线审查问题均已修复，未提交 commit。

修复内容：

- 报价详情由 `QuotationDetail` 订阅最小原始值 `user?.role`，显式向共享视图传入 4/9。
- `CostingReviewPage` 显式传固定 9。
- `ProductDetailViews`、`ComparisonBoard` 不再订阅认证 store，只消费显式 prop。
- 增加纯策略 `quotationSurfaceDisplayScale`：
  - 报价详情、SALES 比选保留传入的 4/9。
  - 独立核价、FINANCE 比选无条件返回 9，即使误传 4 也不会降精度。
- `SnapshotTab.elementActualPrices.price` 移除角色上限，抽为角色无关的 `formatElementActualPrice`，保持原最多 9 位显示。

本轮实际改动文件：

- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/QuotationDetail.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/CostingReviewPage.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/ProductDetailViews.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/ComparisonBoard.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/QuotationStep2.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/components/SnapshotTab.tsx`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/quotationResultDisplayScale.ts`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/quotationResultDisplayScale.test.ts`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/snapshotDisplay.ts`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend/src/pages/quotation/snapshotDisplay.test.ts`

验证结果：

- 定向 Vitest：`6 passed`，`77 passed`，退出 0。
- `npm test`：`116 passed`，`1473 passed`，退出 0。
- `npx tsc -b`：退出 0，无错误输出。
- `npm run build`：退出 0；`3442 modules transformed`，`✓ built in 1.06s`。仅既有大 chunk 警告。
- 新增策略/快照函数定向 ESLint：退出 0。
- `git diff --check -- cpq-frontend/src`：退出 0，无输出。

未验证项：

- 未运行 Playwright、未启动共享服务。
- 四角色浏览器切换、FINANCE 页面实机展示及加载/控制台检查仍由测试路和主线隔离栈验证。

已自检：报价入口仍按角色 4/9；独立核价和 FINANCE 始终 9；元素实际单价快照不再受销售 4 位限制；通用格式化器仍不读取认证 store，保存、快照数据、对账及导出请求契约未改变。

## 独立测试 S-FLOW 执行前拦截（原文）

执行前拦截：当前 `Task261027RoleDisplayPrecisionOutputFlowTest.@AfterAll` 无条件清理本前缀，与本轮“失败保留现场”冲突。为遵守 testing.md 和本轮指令，我将只改自有测试夹具的清理门闩（4 tests 全成功才清；任一失败跳过 cleanup 并打印保留提示），不改任何断言/业务实现，然后执行你给的唯一命令。

## 独立测试后端 S-FLOW 执行回报（原文）

后端 S-FLOW 执行结果（失败后已停止，未改/未重跑）

执行前：同 worktree 无 Maven/Quarkus 进程。fixture cleanup 门闩改动后定向 `./mvnw -DskipTests test-compile`：BUILD SUCCESS，1031 main + 814 test sources，Maven 15.306s / wall 17.084829924s。

唯一执行命令（workdir=`/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend`）：
`DB_HOST=10.177.152.12 DB_PORT=5432 DB_NAME=cpq_t261027_rdp_1027a DB_USERNAME=postgres DB_PASSWORD=joii5231 ./mvnw -Dtest=Task261027RoleDisplayPrecisionOutputFlowTest test`

结果：四个业务测试方法 4/4 通过，但 `@AfterAll` 清理报 1 error，故 Maven/Surefire 总体失败。Surefire：Tests run 5, Failures 0, Errors 1, Skipped 0, elapsed 15.01s（XML 15.007s）；方法耗时 2.719s / 2.557s / 0.069s / 0.491s。命令长输出被执行器截断，Maven total/wall 未能可靠恢复，未臆测。

业务断言实际证据：
- SALES_MANAGER：GET 200 且 canonical `1.2345678915`；HTML/PDF/报价 Excel/Excel view/对比 Excel/发送 6 个输出均 200，所有文本/工作簿/邮件 HTML+xlsx 附件解析命中 `1.2346` 且不含 `1.234567892`；发送日志 html body=`display=1.2346`；输出前后持久化串相等。
- SYSTEM_ADMIN：同组 GET+六输出均 200；命中 `1.234567892` 且不含 `1.2346`；发送日志 html body=`display=1.234567892`；输出前后持久化串相等。
- 未登录 GET+六输出实际状态列表精确为 `[401,401,401,401,401,401,401]`。
- SALES_MANAGER 通过 query/body/header 伪造 SYSTEM_ADMIN/9：HTML 与 comparison 均 200，但仍解析为 `1.2346`，伪造未生效。
- 启动使用随机端口 55903；隔离库 Flyway 454→455，DatasetSchemaSelfCheck 97表/1784列通过，语义图加载通过。

失败原文：
`org.hibernate.exception.ConstraintViolationException: JDBC exception executing SQL [ERROR: update or delete on table "user" violates foreign key constraint "operation_log_operator_id_fkey" on table "operation_log" Detail: Key (id)=(36f14683-5bbd-403f-9260-8fd995004ec9) is still referenced from table "operation_log".] [DELETE FROM "user" WHERE username LIKE ?]`
位置：`Task261027RoleDisplayPrecisionOutputFlowTest.java:181`，suiteTearDown。

失败后仅对该隔离库做只读精确计数：users=2，quotations=0，customers=1，templates=1，operation_logs_for_users=1。说明 4/4 成功触发清理后，两张报价先被 API 删除；后续同一事务因 operation_log FK 回滚，用户/客户/模板/日志保留。没有扩大清理，也没有再次运行。下一次同库会被 BeforeAll 前缀门闩拒绝；需主线决定新克隆库，或授权测试夹具先按 `operator_id IN (本前缀用户)` 精确删 operation_log 再删用户。

未验证：前端 Playwright S-FLOW 仍未解锁，未执行。

过程中规避掉的坑：未用默认 Playwright/globalSetup；未连 5174/8081 或其他库；未 DROP/清库；失败后未修改断言/生产代码、未提交；当前无同 worktree Maven/Quarkus 进程。`git status` 中并行业务改动均未触碰。

已自检：工作路径、DB 名、唯一命令、测试报告时间戳、进程、现场只读计数均复核；独立测试期间未读取业务实现。

## 独立测试后端 S-FLOW 最终简报（原文）

后端 S-FLOW 已执行并停止：

- 4 个业务测试方法全部通过。
- 套件清理因 `operation_log_operator_id_fkey` 外键失败。
- Surefire：5 tests，0 failures，1 error，0 skipped，15.01s。
- 隔离库现场：用户 2、报价 0、客户 1、模板 1、关联操作日志 1。
- 未修改断言或生产代码，未重跑、未提交。
- 前端 Playwright 仍未解锁、未执行。

完整原始结果、失败信息、实际输出值和自检声明已回传主线。

## 独立测试后端 S-FLOW 修复复跑报告（原文）

后端 S-FLOW 修复与复跑最终报告

一、仅修改自有测试夹具
文件：`/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/test/java/com/cpq/task261027rdp/Task261027RoleDisplayPrecisionOutputFlowTest.java`
- 保留“仅 4/4 成功才清理；任一失败保留现场”门闩。
- 成功清理事务最前增加：`DELETE operation_log WHERE operator_id IN (SELECT id FROM "user" WHERE username LIKE :userPrefix)`，`:userPrefix=T261027_RDP_OUTPUT_%`。
- 之后仍按 customerId+`T261027_RDP_FLOW_%`、templateId+同前缀、username `T261027_RDP_OUTPUT_%` 精确删除；业务断言未改。

二、上一轮残留精确恢复（仅隔离库 `cpq_t261027_rdp_1027a`）
先只读列出：
- users 2：ADMIN `36f14683-5bbd-403f-9260-8fd995004ec9`、SM `2d4ad6df-d836-4d0a-808a-4a5273001038`
- operation_log 1：`43c5b02f-cf87-47bb-9798-6949d8cf43b8`，operator=ADMIN
- customer 1：`735b4ed0-ef7f-41ea-b1fb-849f3abe2700`
- template 1：`e8ec75fd-1770-482b-9d81-d0a12a80d8f4`
单事务精确删除计数：operation_log=1、该客户关联 customer_contact=1、customer=1、template=1、user=2；事务内断言四类 operation_log/user/customer/template 均 0，COMMIT 成功。无 DROP、无扩大条件。

三、静态编译
命令：`./mvnw -DskipTests test-compile`
结果：BUILD SUCCESS；1031 main + 814 test sources；Maven 12.531s，wall 13.214915342s。随后确认同 worktree 无 Maven/Quarkus 进程。

四、唯一获批 S-FLOW 重跑
workdir：`.../task-261027-role-display-precision/cpq-backend`
命令：`DB_HOST=10.177.152.12 DB_PORT=5432 DB_NAME=cpq_t261027_rdp_1027a DB_USERNAME=postgres DB_PASSWORD=joii5231 ./mvnw -Dtest=Task261027RoleDisplayPrecisionOutputFlowTest test`
结果：BUILD SUCCESS；Tests run 4, Failures 0, Errors 0, Skipped 0；Surefire 14.32s，Maven total 33.596s，工具 wall 合计约 30.924s。随机端口 55705；库已在 v455，无新迁移。

业务证据：
- T-07a SALES_MANAGER：GET canonical `1.2345678915`；HTML/PDF/quote Excel/Excel view/comparison Excel/send 六输出均 200，解析后的 HTML、xlsx、邮件 HTML+xlsx 均为 `1.2346`，不含 9 位显示值；邮件日志 `display=1.2346`；输出前后 DB 串一致。
- T-07b SYSTEM_ADMIN：同六输出均 200，解析为 `1.234567892`，不含 4 位显示值；邮件日志 `display=1.234567892`；输出前后 DB 串一致。
- T-10a 未登录 GET+六输出实际状态精确 `[401,401,401,401,401,401,401]`。
- T-10b SALES_MANAGER 通过 query/body/header 伪造 SYSTEM_ADMIN/9：HTML、comparison 均 200，但仍为 `1.2346`。

五、成功清理后只读计数
- operation_logs=0
- users=0
- customers=0
- templates=0
- quotations=0（额外核对）
Surefire 报告 mtime `2026-10-27 18:51:08.958927585 -0700`；当前无同 worktree Maven/Quarkus；`git diff --check` 通过。

未验证：前端 Playwright S-FLOW 仍未解锁，未执行。

过程中规避掉的坑：先精确清掉测试用户关联 operation_log，避免再次触发用户 FK；残留恢复前先只读列 ID/计数；所有删除在一个事务内且失败会回滚；未使用共享服务、默认 Playwright/globalSetup、其他数据库、DROP/全表清。

已自检：只改自有测试夹具，未读/改业务实现或业务断言，未提交；路径、DB、命令、报告时间戳、进程及清理后计数均复核。

## 独立测试后端 S-FLOW 复跑最终简报（原文）

后端 S-FLOW 已修复夹具并复跑成功：

- 4 tests，0 failures，0 errors，0 skipped。
- BUILD SUCCESS；Surefire 14.32s，Maven 33.596s。
- 销售经理显示 `1.2346`，系统管理员显示 `1.234567892`。
- 未登录七个请求均为 401；伪造角色不能突破四位限制。
- 清理后 operation_log、用户、客户、模板、报价均为 0。
- 仅修改自有测试清理顺序，业务断言和生产代码未改。
- 前端 Playwright 尚未执行。

完整命令、删除计数、业务证据及自检结果已回传主线。

## 独立测试前端 S-FLOW 首跑原始报告（原文）

前端 Playwright S-FLOW 首跑原始报告（失败后已停止）

环境/进程：
- Browser plugin 不可用，按 frontend-testing-debugging 技能及任务指定使用仓库 Playwright。
- 执行前 `pgrep -af 'node.*[p]laywright test'` 无其他 Playwright。
- 前端 `http://127.0.0.1:15175`，后端 `http://127.0.0.1:18082`，DB 仅 `cpq_t261027_rdp_1027a`；未连接 5174/8081。

执行命令：严格使用你给出的全量环境变量 + `npx playwright test -c e2e/task261027-role-display-precision.config.ts`，workdir 为指定 worktree/cpq-frontend。

结果：`1 failed, 5 did not run`，串行首用例失败后停止；总 wall 约 147.2s。首用例输出标记 `(7.4s)`，并同时报告 `Test timeout of 150000ms exceeded`。
- FAIL T-04 / AC-1、AC-4
- 未运行 T-05/T-06/T-08/T-09/T-10

精确失败：
`Error: SALES_REP 编辑页产品小计 必须非空显示 1.2346；实际文本=CNY 11.2346`
`Expected value: "1.2346"`
`Received array: ["11.2346"]`
位置：`task261027-role-display-precision-flow.spec.ts:190`，由 `assertListEditAndDetail` line 253 调用。

定位证据：
- 错误上下文 DOM 与截图证明 selector 精确命中紫色“产品小计” `.qt-subtotal-value`，不是错误节点或文本拼接。
- 同一页面目标公式单元格实际 `1.2346`，表内“小计”=`1.2346`、“合计”=`1.2346`，但卡片底部“产品小计”=`CNY 11.2346`。
- 只读 DB：quotation original_amount/total_amount，以及 line subtotal/unit/final/total 全部仍为 `1.234567891500`；quote_card_values 与 quote_excel_values 均仍含 canonical `1.2345678915`。因此页面产品小计与关系型 line subtotal 明确不一致。
- 报价名称已由主线确认还原；该失败发生在任何保存用例之前。

页面健康：失败页面正常渲染；进入该产品卡后 `assertSettled` 已通过，即可见“加载中”、spin/button loading、红色 Alert、路由错误遮罩均为 0。命令仅有 Node `NO_COLOR/FORCE_COLOR` 警告，无应用控制台错误输出。截图：`cpq-frontend/e2e/test-results/task261027-role-display-precision-flow/task261027-role-display-pr-88473-辑、产品卡、详情均显示-1-2346，GET-原值不变/test-failed-1.png`；error-context 同目录。

判断：当前证据排除 selector 问题；夹具关系型产品小计也是目标原值，因此更符合产品小计页面业务呈现问题（额外出现 10），违反 AC-1。为避免放宽 AC 硬断言，我未修改测试、未重跑后续用例。原计划进一步只读检查 quote_card_values 是否含合法额外 10，但命令在收到你的状态消息时未 dispatch，未产生任何变更。

自有 e2e 文件改动：无，本轮没有修改 `e2e/task261027-*`。
当前进程：Playwright 已结束。生产代码/夹具/服务均未修改，未运行 Maven/构建，未提交。

已自检：命令、端口、DB、报告、DOM/截图、关系型金额原值及进程均复核；未读取非测试前端实现。

## 独立测试前端 S-FLOW 首跑简报（原文）

前端 S-FLOW 首跑已回传：

- T-04 失败，后续 5 个串行用例未运行。
- 公式格、表内小计/合计均为 `1.2346`，真实产品小计却为 `CNY 11.2346`。
- DB 金额列仍全部为 `1.234567891500`，排除选择器误命中。
- 页面无加载残留、错误 Alert 或路由遮罩。
- 未修改任何 E2E 或生产文件，未重跑、未运行 Maven/构建。
- Playwright 已结束，夹具和服务保留。

## 独立测试前端 S-FLOW 第二阶段报告（原文）

前端 Playwright S-FLOW 第二阶段报告（失败后已停止，服务/夹具保留）

A. 夹具修正后的第一次重跑
- T-04 仍失败，但新红点为测试选择器假红：详情整页 `body` 中存在 AC-9 应保留的非计算“净用量”原值 `1.2345678915`，而报价汇总本身已正确为 `1.2346`。
- 仅修改自有 `e2e/task261027-role-display-precision-flow.spec.ts`：移除编辑页重复的整页 body 断言；详情报价汇总从 body 收窄到 `page.getByText(/^报价总金额[：:]/).first()`。公式格、产品小计、列表总额、详情汇总的 4/9 位硬断言均保留，未放宽。
- `npx eslint e2e/task261027-role-display-precision-flow.spec.ts` exit 0；无其他 Playwright。

B. 完全相同环境/命令再次完整重跑
结果：1 passed / 1 failed / 4 did not run，总 43.4s。
- PASS T-04 / AC-1、AC-4，19.4s：SALES_REP + SALES_MANAGER 的列表、编辑公式格、编辑产品小计、详情公式格、详情产品小计、详情报价总金额均命中 `1.2346` 且目标计算表面不泄漏 `1.2345678915`；GET canonical string 保留。各页面 assertSettled 通过。
- FAIL T-05 / AC-2、AC-4，22.9s：PRICING_MANAGER 登录成功、真实角色正确，但访问 `/quotations?keyword=T261027_RDP_FLOW_Q001` 被前端权限页直接拦截，页面显示“无权访问 / 您的角色没有该功能的访问权限”，所以列表行 count=0，尚未进入 9 位显示断言。
- 未运行：T-06 / T-08 / T-09 / T-10（serial 在 T-05 失败后停止）。

精确失败：
`Error: PRICING_MANAGER 报价列表必须唯一命中私有报价`
locator `tr` hasText `T261027_RDP_FLOW_Q001`，expected 1, received 0，timeout 20000ms；位置 spec line 246，T-05 line 351。
截图：`cpq-frontend/e2e/test-results/task261027-role-display-precision-flow/task261027-role-display-pr-9f991-列表、编辑、产品卡、详情均显示-1-234567892/test-failed-1.png`；DOM error-context 同目录，明确是 Unauthorized 页面，不是选择器/空列表。

判断：这是权限/可达入口前置，不是显示精度 selector。AC-2/AC-4 要求 PRICING_MANAGER 查看同单并覆盖列表等入口，但 AC-10 又禁止扩大原权限；当前真实角色直接被 `/quotations` 路由拒绝。需要主线确认/提供该角色原本允许的报价 UI 入口，或判定权限实现与本任务 AC 冲突。我未改权限、账号、生产代码，也未跳过角色或放宽断言。

存储/现场：失败后只读复核：name=`T261027_RDP_FLOW_ROLE_PRECISION`，quotationNo=`T261027_RDP_FLOW_Q001`，status=DRAFT；quotation original/total 与 line subtotal/unit/final/total 均为 `1.234567891500`；card/excel snapshot 均仍含 canonical raw。没有保存用例执行，名称未被改动。当前无 Playwright 进程。

页面健康/控制台：T-04 所有加载中/spin/button loading/红色 Alert/路由错误遮罩断言通过；命令仅有 Node NO_COLOR/FORCE_COLOR 警告，未输出应用控制台错误。T-05 是明确权限页。

实际改动：仅上述自有 e2e selector 收窄；定向 ESLint 与 diff whitespace 检查通过。未运行 Maven/构建，未改生产代码或夹具，未连接共享端口，未提交。

未验证：PRICING_MANAGER/SYSTEM_ADMIN 9 位页面、Excel/比对四角色、保存不变、4/9/4 切换、401/伪造，因 serial T-05 权限阻塞。

已自检：命令、独立端口/DB、测试报告、截图/DOM、存储原值、名称与进程均复核；未读取非测试前端实现。

## 独立测试前端 S-FLOW 第二阶段简报（原文）

重跑结果已回传主线：

- T-04 通过：销售代表、销售经理所有目标页面均显示 `1.2346`，GET 原值保留。
- T-05 失败：定价经理访问报价列表时直接进入“无权访问”页面，未进入 9 位精度断言。
- 总计：1 passed、1 failed、4 did not run，43.4s。
- 仅修正了自有测试的宽泛 `body` 选择器假红，ESLint 通过。
- DB 名称、金额和快照原值均未改变；无 Playwright 残留进程。
- 未修改生产代码、权限、夹具或业务断言。

## 独立测试前端 S-FLOW 最新重跑报告（原文）

前端 Playwright 最新重跑报告（失败后已停止）

测试脚本调整：
- 新增公共 `assertDetailAndRaw`，精确断言详情公式格、产品小计、报价总金额及 GET raw。
- PRICING_MANAGER 通过 `assertDetailOnly` 仅走允许的 `/quotations/:id`；SYSTEM_ADMIN 仍走列表+编辑+详情。
- T-06 未改：四角色均从详情进入 Excel/比对。
- 之前 body 假红修正保留。未放宽 4/9 位、raw、加载或权限断言。
- 定向 ESLint exit 0；执行前无 Playwright。

完全相同隔离环境/命令结果：`1 passed / 1 failed / 4 did not run`，总 3.3m。
1. PASS T-04，19.7s：SALES_REP/SALES_MANAGER 列表、编辑公式/产品小计、详情公式/产品小计/报价总金额均 `1.2346`，GET raw 保留，加载/错误状态断言通过。
2. FAIL T-05，报告方法时长 25.5s，同时测试框架报告 150000ms timeout：
   - PRICING_MANAGER 的允许详情流程已完整通过（否则不会进入随后的 SYSTEM_ADMIN 步骤）：公式格、产品小计、报价总金额均 `1.234567892`，GET 保留 raw。
   - SYSTEM_ADMIN 登录后访问 `/quotations?keyword=T261027_RDP_FLOW_Q001`，`tr` 精确行 count 20s 内为 0；尚未进入管理员 9 位列表/编辑/详情断言。
   - 失败截图是纯白页，error-context 没有应用 DOM，不是“无数据”表格，也不是前一次的 Unauthorized 页。截图：`cpq-frontend/e2e/test-results/task261027-role-display-precision-flow/task261027-role-display-pr-3ba88-及管理员列表-编辑-详情均显示-1-234567892/test-failed-1.png`。
   - Playwright trace.zip 为 77,486 bytes 但 central directory 不完整，无法解压（测试 timeout/收尾期间写坏），因此无可用 console trace。
3. T-06/T-08/T-09/T-10 因 serial 未运行。

失败后健康检查（显式 NO_PROXY）：前端根 200、前端 quotations 路由 HTTP 200、后端匿名专用报价端点 401，说明结束后隔离服务可用。失败瞬间纯白，更像瞬时页面/开发栈渲染中断或管理员列表运行时空白，当前证据不足以归为 selector/数据过滤；未擅自重跑或修改断言。

存储：本轮仅查看，无保存用例执行；上一轮已复核 name=`T261027_RDP_FLOW_ROLE_PRECISION`、quotationNo=`T261027_RDP_FLOW_Q001`，quotation/line 六个金额均 `1.234567891500`，card/excel snapshot 含 raw。本轮没有写操作。

进程：本 worktree Playwright 已结束。测试后看到另一 worktree `/task-261019-quotation-element-price/... playwright ... --list`，未干预；本轮开跑前 pgrep 为空，因此不构成开跑并发。

实际修改仍仅自有 `e2e/task261027-role-display-precision-flow.spec.ts`；未改生产代码/夹具/权限，未运行 Maven/构建，未提交。

已自检：入口契约、ESLint、命令、独立端口/DB、截图/DOM、服务健康、进程均复核；未读取非测试前端实现。

## 独立测试前端 S-FLOW 最新重跑简报（原文）

最新重跑结果已回传：

- T-04 通过：销售两角色完整 4 位流程。
- T-05 中定价经理允许的详情流程已通过，三处均显示 `1.234567892`，GET 原值保留。
- 随后管理员列表导航出现纯白页，导致行定位失败；1 failed、4 did not run。
- 测试结束后隔离前端 200、后端匿名报价端点 401；失败 trace 未完整写入，无法解压。
- 已按约定停止，未再次重跑或修改业务代码、权限和夹具。

## 独立测试前端 S-FLOW 纯白复现报告（原文）

同类纯白再次复现，已按指令停止并保留证据；本轮未改测试。

执行前：`pgrep -af 'node.*[p]laywright test'` 为空。使用完全相同隔离环境/完整命令。

结果：T-04 FAIL，后续 5 did not run；Playwright 方法行显示 25.1s，并同时报告 `Test timeout of 150000ms exceeded`；工具 wall 约 160.1s。

精确失败：
`Error: 编辑步骤 1 必须可进入产品卡片步骤`
`getByRole('button', { name: /下一步/ }).first()` expected enabled，20s 内 element(s) not found；spec `openStep2` line 212。

同类纯白证据：
- 失败截图为全白页面，没有侧栏、路由错误页或业务 DOM：`cpq-frontend/e2e/test-results/task261027-role-display-precision-flow/task261027-role-display-pr-88473-辑、产品卡、详情均显示-1-2346，GET-原值不变/test-failed-1.png`。
- error-context Page snapshot 无应用 DOM，仅记录 locator 失败。
- 失败后显式 NO_PROXY 健康检查：frontend root=200，backend anonymous quote=401，服务结束后正常。
- 当前无 Playwright 进程。

现场/存储：本轮在 T-04 查看流程中失败，未进入保存用例；只读复核 name=`T261027_RDP_FLOW_ROLE_PRECISION`、quotationNo=`T261027_RDP_FLOW_Q001`、status=DRAFT；quotation original/total 和 line subtotal/unit/final/total 全部仍为 `1.234567891500`。

本轮实际改动：无。保留上一轮已审的自有 e2e 入口/selector 修正，没有修改生产代码、夹具、权限或断言；未运行 Maven/构建，未提交。

结论：这是第二次可复现的开发栈/浏览器导航纯白中断，出现位置从上轮 SYSTEM_ADMIN list 变为本轮 SALES_REP edit navigation，且服务随后健康，不呈现稳定业务页面缺陷。按你的明确规则不再重跑。

未验证：本轮完整 6 用例均未形成通过结论（T-04 中途失败，T-05/T-06/T-08/T-09/T-10 未运行）；可引用此前成功轮次的局部证据，但本轮不冒充全绿。

已自检：前置进程、完全相同命令、截图/DOM、服务健康、DB 原值和结束进程均复核；未读取/修改非测试实现。

## 独立测试前端 S-FLOW 纯白复现简报（原文）

同类纯白导航中断已再次复现并停止：

- T-04 在编辑页导航后全白，找不到“下一步”按钮。
- 后续 5 个用例未运行。
- 失败后前端返回 200、后端匿名报价返回 401，服务恢复正常。
- 报价名称及所有金额列仍为原始值 `1.234567891500`。
- 本轮未修改测试、生产代码、夹具或权限，未再次重跑。
- 完整证据和自检结果已回传主线。

## 独立测试前端 S-FLOW T-08 修正并发拦截（原文）

T-08 已按授权改为 projectName（关联项目）真实脏标记，首次 PUT 200 后才还原，GET/raw/calculationState 与精确还原断言齐全，AggregateError 保留主失败+还原失败；定向 ESLint exit 0。执行前又发现另一 worktree Playwright PID 73152（task-261019 S0），故尚未启动本任务、未干预该进程。等它结束后立即跑 15176 完整 6 用例。

## 独立测试前端 S-FLOW 并发等待更新（原文）

并发 PID 73152 仍为活动状态 `Sl`，已运行约 4m53s（非僵尸）。我继续不干预、不跨 worktree 并发；请协调该分片结束后通知，或我将持续轮询。

## 独立测试前端 S-FLOW AC-7 回写缺陷报告（原文）

重要：本轮已结束，但中途并发，整轮不能作为最终证据；同时 T-08 真实保存已把专用报价头金额改写，夹具现已污染，必须先恢复再决定是否重跑。

受并发污染轮结果：T-04/T-05/T-06 pass；T-08 首次 projectName PUT 确实成功，GET projectName=`T261027_RDP_FLOW_SAVE_TEXT`，但 canonical raw 精确字符串数量从 8 降到 6，断言失败；finally 再次 PUT 已把 projectName 精确还原为空。T-09/T-10 未运行。另一 worktree PID 76231 在本轮中途启动，故本轮标记非最终。

失败后对专用 DB 的精确只读证据：
- name=`T261027_RDP_FLOW_ROLE_PRECISION`
- project_name=`''`（finally 已还原）
- updated_at=`2026-10-28 02:38:36.236764+00`
- quotation.original_amount=`1.234567892000`（此前 `1.234567891500`）
- quotation.total_amount=`1.234567892000`（此前 `1.234567891500`）
- line subtotal/unit/final/total 仍全为 `1.234567891500`
- quote_card_values / quote_excel_values 仍含 raw（position 11682 / 22）

这精确解释 GET raw count 8→6：报价头 original/total 两个字段在保存无关 projectName 后被 9 位舍入并回写，而行金额/快照没变。虽然整轮受浏览器并发污染不能充当最终绿/红验收，但另一 worktree 不连接该专用 DB；该 DB 写变更是本任务自身两次 PUT 产生的诊断证据，符合 AC-7 所防的“显示舍入回写”风险。请主线先按精确 ID 将 q.original_amount/q.total_amount 恢复为 `1.234567891500`（并确认是否恢复 updated_at 基线），或决定业务修复；在恢复前我不会重跑，避免在污染夹具上得出假结论。

当前对方 PID 76231 仍运行，我未干预。未改生产代码。

## 独立测试前端 S-FLOW AC-7 回写缺陷简报（原文）

本轮已暂停并回报关键证据：

- 中途出现其他 worktree Playwright，因此整轮不能作为最终验收。
- T-08 真实保存后，报价头 `original_amount/total_amount` 从 `1.234567891500` 被回写为 `1.234567892000`。
- 项目名已由 finally 恢复为空；行金额及 JSON 快照仍保留原值。
- 当前夹具已污染，未继续重跑或写入，等待主线恢复数据或安排业务修复。

## 后端 AC-7 修复期环境拦截（原文）

重要实证：按你“可安全执行后端定向测试”的通知，我运行 SaveDraftIncrementalProtocolTest；Quarkus test 实际连接 jdbc:postgresql://10.177.152.12:5432/cpq_db_test，启动时发现 schema V454，并自动应用了源码已存在的 V455（task261026 quotation line profit snapshot），现在库为 V455。不是本任务新增迁移，我未手工改库/清理/回退；测试本身 @TestTransaction 14/14 通过。请主线记录该环境变化并判断是否需协调。

## 独立测试前端 S-FLOW 生产预览报告（原文）

稳定生产预览 `15176` Playwright 报告（新测试定位失败，已停止；未改文件）

环境：Browser plugin 不可用，使用仓库 Playwright。执行前无其他 Playwright。仅 `PW_BASE_URL` 从 15175 改为 `http://127.0.0.1:15176`，其余环境/命令完全相同。

结果：2 passed / 1 failed / 3 did not run，总 3.2m。
- PASS T-04，12.6s：SALES_REP/SALES_MANAGER 列表、编辑公式与产品小计、详情公式/产品小计/报价总金额均 `1.2346`，GET raw 保留，loading/error overlay=0。
- PASS T-05，8.2s：PRICING_MANAGER 允许的详情公式/产品小计/报价总金额均 `1.234567892`、GET raw；SYSTEM_ADMIN 列表+编辑+详情 9 位均通过。生产预览未再纯白。
- FAIL T-06，22.2s（同时框架报告 150000ms timeout）：SALES_REP Excel 视图已真实渲染数据行 `T261027_RDP_FLOW_PART001 | 1.2346 | 1.2346`，但测试 `excelTable.locator('tbody tr').first()` 命中 AntD 自动插入的隐藏测量行 `<tr aria-hidden="true" class="ant-table-measure-row">`，`toBeVisible()` 20s 失败。
- T-08/T-09/T-10 因 serial 未运行。

证据：
- DOM snapshot 明确存在可见真实数据 row：`T261027_RDP_FLOW_PART001 1.2346 1.2346`，Excel radio checked；`assertSettled` 已先通过，加载中/spinner/红色 Alert/路由遮罩均 0。
- 精确失败截图：`cpq-frontend/e2e/test-results/task261027-role-display-precision-flow/task261027-role-display-pr-845d5-Excel-比对视图口径一致且所有入口加载中最终为-0/test-failed-1.png`；error-context 同目录。
- 这是稳定的测试 selector 假红，不是业务空表；建议后续获授权时改为 `tbody tr:not(.ant-table-measure-row):visible`（或按可见 row/料号定位），不改变精度断言。本轮“不要改测试”约束下未修改、未重跑。

存储只读复核：name=`T261027_RDP_FLOW_ROLE_PRECISION`、quotationNo=`T261027_RDP_FLOW_Q001`、status=DRAFT；quotation original/total 与 line subtotal/unit/final/total 均 `1.234567891500`。未执行保存用例，无写入。

页面/控制台：生产预览 T-04/T-05 全程正常，无纯白；命令仅输出 Node NO_COLOR/FORCE_COLOR warning，无应用 console/page error 输出。当前无 Playwright 进程。

实际改动：本轮 0；未改测试/生产代码/夹具/权限，未运行 Maven/构建，未提交。

未验证：T-06 后三角色 Excel/比对、T-08 保存不变、T-09 4/9/4、T-10 401/伪造。

已自检：命令、15176 生产预览、独立后端/DB、报告、截图/DOM、存储值和进程均复核；未读取非测试实现。

## 独立测试前端 S-FLOW 生产预览简报（原文）

稳定生产预览验证结果已回传：

- T-04、T-05 通过，4 位与 9 位角色显示均符合预期。
- T-06 因测试选择器命中 AntD 隐藏测量行而失败；可见真实数据行实际为 `1.2346`。
- 总计 2 passed、1 failed、3 did not run。
- DB 原始值均保持 `1.234567891500`。
- 本轮遵照要求未修改测试或生产代码，未重跑。

## 独立测试前端 S-FLOW 并发拦截（原文）

selector 已按授权修正且定向 ESLint exit 0；但执行前检查发现另一 worktree Playwright 正在运行：PID 68306，`task-261019-quotation-element-price/... playwright test -c playwright.task261019.config.ts --grep task-261019 S0`。我未干预/kill，也尚未启动本任务，以免系统 Chrome 并发污染。等该进程结束即可立即跑 15176 完整 6 用例；若主线确认允许跨 worktree 并发，请明确解锁。

## 独立测试前端 S-FLOW T-08 失败报告（原文）

15176 完整重跑最新结果（T-08 新失败，已停止并保留）

结果：3 passed / 1 failed / 2 did not run，总 3.7m。
- PASS T-04 13.4s：销售两角色 4 位、列表/编辑/详情、GET raw。
- PASS T-05 8.2s：PRICING_MANAGER 详情 + SYSTEM_ADMIN 列表/编辑/详情 9 位、GET raw。
- PASS T-06 12.0s：四角色 Excel/比对视图真实 PART_NO 可见行均按 4/9 位；loading/spinner/error overlay 均 0。新 selector 修正有效。
- FAIL T-08 36.3s（框架同时报告 150000ms timeout）：点击“保存草稿”后 15s 内未捕获预期 `PUT /api/cpq/quotations/{id}/draft` 响应。
- T-09/T-10 serial 未运行。

T-08 精确证据：
- `saveQuotationName` 已进入 Step2，产品卡可见，“保存草稿”按钮可见、active/cursor=pointer；页面产品小计正确 `CNY 1.2346`，无纯白。
- `page.waitForResponse` 匹配 PUT + `/quotations/26102730-.../draft` 15s 超时。
- 最终 stack 指到 line 393 的 `finally` 还原调用；因为 try 中首次保存失败后 finally 又调用同 helper，第二次 timeout 覆盖了首次异常。
- 只读 DB 证明没有任何保存发生：name 仍 `T261027_RDP_FLOW_ROLE_PRECISION`，updated_at 仍 `2026-10-28 01:55:44.514346+00`；quotation original/total 与 line subtotal/unit/final/total 全为 `1.234567891500`。
- 因此现场无需恢复，目标金额/raw 没被回写，但 AC-7 的“真实保存成功后仍不变”尚未验证。
- 既有 E2E 公共约定确认保存端点确为 PUT `/quotations/{id}/draft`，监听路径不是过时假设。

截图：`cpq-frontend/e2e/test-results/task261027-role-display-precision-flow/task261027-role-display-pr-fdd37-本并保存，不把-4-位显示回写到-GET、金额列或快照/test-failed-1.png`；error-context 同目录。截图正常渲染 Step2 和 active 保存按钮。

判断：不是 selector/纯白问题；按钮点击未观测到保存请求。当前证据不足以区分“Step1 名称变更未进入可保存状态/校验静默阻止”与保存动作业务问题。建议下一步在自有测试中临时捕获 click 前后 request/requestfailed/console/toast，且避免 finally 二次异常覆盖首次错误；本轮未获该扩展授权，未修改或重跑。

本轮测试改动仅已授权的 Excel/比对可见 PART_NO 行 selector；定向 ESLint 通过。未改生产代码/夹具/权限，未运行 Maven/构建，未提交；当前无本任务 Playwright 进程。

已自检：15176、完整环境、逐用例报告、截图/DOM、DB name/updated_at/raw、端点公共测试约定和进程均复核；未读取非测试实现。

## 独立测试前端 S-FLOW T-08 失败简报（原文）

15176 生产预览最新结果：

- T-04、T-05、T-06 通过，4/9 位及四角色 Excel/比对视图验证成功。
- T-08 失败：点击可见且启用的“保存草稿”后，未观察到预期 PUT `/draft` 请求。
- DB 名称、更新时间和所有原始金额均未改变，因此无需恢复，但 AC-7 尚未完成验证。
- T-09、T-10 未运行。
- 总计 3 passed、1 failed、2 did not run。
- 完整证据和自检结果已回传主线。

## Backend AC-7 fix report

AC-7 最小修复已完成，未提交、未部署。

## 改动文件

- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`
- `/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-backend/src/test/java/com/cpq/quotation/service/SaveDraftIncrementalProtocolTest.java`

未修改独立测试代理文件、DTO、实体、schema 或迁移。

## 两条 saveDraft 路径逻辑

- 新增 `hasLineMutations` 判定：
  - 增量协议 `added/modified/removed` 全空：视为单头 patch。
  - 增量协议存在真实行增删改：维持原重算。
  - 旧全量协议即使 `lineItems=[]`，仍代表删除全部行，维持原重算。
- 批量路径与逐行路径统一调用 `recalculateDraftTotals(..., true)`：
  - `flush` 后 `SELECT sum(subtotal)`。
  - `originalAmount` 保持既有 9 位报价总额边界。
  - `totalAmount` 继续基于未提前舍入的行合计计算，未引入二次舍入变化。
- 纯 `projectName` 等非金额单头修改：
  - 批量/逐行处理均不进入。
  - 不执行行合计查询，也不触碰 `originalAmount/totalAmount`。
  - `userDataVersion` 仍按既有保存协议递增。
- 折扣变化：
  - 保存前用 `BigDecimal.compareTo` 判断折扣是否真正改变。
  - 无行变化时保留 `originalAmount` 的完整原精度。
  - 仅按新折扣和既有 `QUOTATION_TOTAL_SCALE` 重算 `totalAmount`。
- 折扣数值未变但 scale 不同，不会无意义重写金额。

## 新增用例结果

新增 5 个 Quarkus 集成断言：

- 批量路径 header-only：`1.234567891500` 两个头金额逐字保留。
- 逐行路径 header-only：`1.234567891500` 两个头金额逐字保留。
- 批量路径真实行金额变化：仍从整单行小计聚合并刷新金额。
- 逐行路径真实行金额变化：同批量路径口径。
- 折扣单独变化：`originalAmount` 逐字不变，`totalAmount` 正确重算。

单类定向结果：

```text
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
Time elapsed: 10.07 s
返回码：0
```

## 构建与 clean 回归

编译：

```text
./mvnw -DskipTests compile
Compiling 1031 source files
BUILD SUCCESS
Total time: 8.421 s
返回码：0
```

clean 定向回归：

```text
./mvnw clean -Dtest=SaveDraftIncrementalProtocolTest,NumberFormatUtilTest,QuotationDisplayPrecisionPolicyTest,ExcelViewServicePrecisionTest,ComparisonExportServiceTest,QuotationExportRolePrecisionTest,Task261027RoleDisplayPrecisionUnitTest test
Compiling 1031 main source files
Compiling 814 test source files
Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 30.053 s
返回码：0
```

格式及越界检查：

```text
git diff --check
返回码：0
```

迁移、DTO、实体差异检查为空。测试结束后未残留 Maven、Surefire 或 Quarkus Java 进程。

## 测试库记录

首次定向启动连接规范允许的 `cpq_db_test`：

```text
Current version of schema "public": 454
Migrating schema "public" to version "455 - task261026 quotation line profit snapshot"
Successfully applied 1 migration
```

V455 是源码已有迁移，不是本任务新增。主线已确认无需回退；后续 clean 测试输出：

```text
Current version of schema "public": 455
Schema "public" is up to date. No migration necessary.
```

未操作其他数据库。

## 未验证

未再次运行完整真实 HTTP 保存流程；本轮验证落在真实 Quarkus 服务层、测试数据库事务和原生落库文本断言。独立测试代理可在主线验收时重跑其完整 AC-7 HTTP 场景。

**已自检：两条 saveDraft 路径、header-only、line-change、discount-change、clean 构建、35 个定向测试、diff 和越界范围均已核对。**

## S-FLOW frontend final report

S-FLOW 前端最终报告：15176 生产预览完整 6/6 通过

执行前：
- Browser plugin 不可用，使用仓库 Playwright。
- `pgrep -af 'node.*[p]laywright test'` 无输出；执行期间未观测到其他 Playwright，结束后亦无输出。
- DB 前置：projectName=`''`；quotation original/total、line subtotal/unit/final/total 均 `1.234567891500`。

命令（workdir=`/home/joii/project/cpq/.claude/worktrees/task-261027-role-display-precision/cpq-frontend`）：
`NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost PW_BASE_URL=http://127.0.0.1:15176 PW_BACKEND_URL=http://127.0.0.1:18082 PW_DB=cpq_t261027_rdp_1027a PW_DB_HOST=10.177.152.12 PW_DB_USER=postgres PW_DB_PASS=joii5231 PW_RDP_QUOTATION_ID=26102730-0000-4000-8000-000000000001 PW_RDP_QUOTATION_NO=T261027_RDP_FLOW_Q001 PW_RDP_PART_NO=T261027_RDP_FLOW_PART001 PW_RDP_TAB_NAME=材质元素 PW_RDP_FORMULA_FIELD=元素小计 PW_RDP_USER_SALES_REP=T261027_RDP_SALES_REP PW_RDP_PWD_SALES_REP=Admin@2026 PW_RDP_USER_SALES_MANAGER=T261027_RDP_SALES_MANAGER PW_RDP_PWD_SALES_MANAGER=Admin@2026 PW_RDP_USER_PRICING_MANAGER=T261027_RDP_PRICING_MANAGER PW_RDP_PWD_PRICING_MANAGER=Admin@2026 PW_RDP_USER_SYSTEM_ADMIN=T261027_RDP_SYSTEM_ADMIN PW_RDP_PWD_SYSTEM_ADMIN=Admin@2026 npx playwright test -c e2e/task261027-role-display-precision.config.ts`

总结果：`6 passed (50.0s)`，0 failed / 0 skipped / 0 did not run。

逐用例：
1. T-04 PASS，13.8s：SALES_REP、SALES_MANAGER 的列表、编辑公式格/产品小计、详情公式格/产品小计/报价总金额均 `1.2346`，不泄漏计算 raw；GET 保留 canonical `1.2345678915`。
2. T-05 PASS，8.7s：PRICING_MANAGER 从允许详情入口，SYSTEM_ADMIN 从列表+编辑+详情入口；公式格、产品小计、报价汇总均 `1.234567892`；GET raw 保留。
3. T-06 PASS，11.3s：四角色详情内 Excel/比对视图均唯一命中可见 PART_NO 行；销售两角色 `1.2346`，定价经理/管理员 `1.234567892`，raw 不泄漏；每个入口 loading 文本、spin/button loading、红色 Alert、路由错误遮罩最终均为 0。
4. T-08 PASS，7.7s：SALES_MANAGER 修改外层 `projectName` 为 `T261027_RDP_FLOW_SAVE_TEXT`，首次真实 PUT `/draft`=200；GET projectName 确认写入，canonical raw 数量前后相同，DB `calculationState`（报价头金额、行金额、card/excel JSON 快照）逐字等于保存前。finally 将 projectName 精确恢复为空，再次 PUT=200，并由 GET 确认恢复。
5. T-09 PASS，6.1s：同一浏览器 SALES_REP→SYSTEM_ADMIN→SALES_REP，公式格依次 `1.2346 → 1.234567892 → 1.2346`；每步完整 DB snapshot（含 updated_at、金额、快照）等于序列前 baseline，纯查看不写库。
6. T-10 PASS，1.2s：匿名 GET+HTML/PDF/quote Excel/Excel view/comparison/send 实际状态精确 `[401,401,401,401,401,401,401]`；SALES_MANAGER 用 query/body/header 伪造 SYSTEM_ADMIN/9，HTML 与 comparison 均 200，但仍含 `1.2346` 且不含 `1.234567892`。

最终 DB 只读证据：
`T261027_RDP_FLOW_ROLE_PRECISION | projectName='' | T261027_RDP_FLOW_Q001 | DRAFT | quotation original=1.234567891500 | total=1.234567891500 | line subtotal/unit/final/total=1.234567891500 | card raw position=11682 | excel raw position=22`
即 T-08 保存与还原后，夹具名称/项目名、报价头、行金额、快照全部恢复/保留精确原值。

页面/控制台：生产预览未再出现纯白；所有页面加载与错误遮罩硬断言通过。命令仅输出 Node `NO_COLOR`/`FORCE_COLOR` warning，无应用错误输出。成功轮未配置截图附件，未虚构截图证据。

测试修正：自有 E2E 已使用角色允许入口、详情汇总精确 selector、AntD 非 measure 可见 PART_NO 行、projectName 真脏标记及不覆盖主异常的恢复逻辑；定向 ESLint此前 exit 0，本轮 `git diff --check` exit 0。

失败/未测项：本次六项无失败、无未测；未覆盖其他浏览器/移动视口（本任务未要求）。未运行 Maven或重新构建，生产 build/后端定向由主线已有证据覆盖。

已自检：独立 worktree、15176/18082、一次性 DB、进程并发、命令输出、逐用例时长、HTTP 断言、最终 DB 与夹具恢复、diff whitespace 均复核；未改生产代码、未提交。

## S-FLOW frontend final notification

15176 生产预览完整 S-FLOW 已通过：

- 6 passed，0 failed，耗时 50.0s。
- T-04/T-05：销售角色 4 位、定价经理/管理员 9 位。
- T-06：四角色 Excel/比对视图及加载状态通过。
- T-08：projectName 两次 PUT 均为 200；GET、金额及快照逐字不变，项目名最终恢复为空。
- T-09：角色切换显示 `4 → 9 → 4`，DB 不变。
- T-10：七个匿名请求均 401；伪造角色仍输出 4 位。
- 最终报价头、行金额及快照均保留 `1.234567891500`。
- 无并发 Playwright、无生产代码修改、未提交。

完整命令、逐用例时长、HTTP/DB 证据及自检已回传主线。
