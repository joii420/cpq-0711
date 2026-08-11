# test-report.md · 最终交付验收报告

> 任务：公式计算保留 12 位、最终显示最多 9 位
> 最终状态：**功能与精度验收 PASS，已交付 master**（提交 `df592eab72cbf27cfdd025add93a9f103d916f52`）
> 例外：TC-077 性能 A/B 因不具备固定 master/feature 双部署、同一只读快照和五轮交替环境，按测试规则保持 **BLOCKED**，未用噪声数据放行。
> 历史说明：本文后半部保留 Stage B/E 的失败与返修过程；这些中间结论均已被本节最终证据取代。

## 0. 最终结论（2026-08-11）

### 0.1 交付门禁

| 门禁 | 结果 | 最终证据 |
|---|---|---|
| 后端编译 | PASS | 861 个 main、450 个 test source 编译成功 |
| 后端输出/并发 focused | PASS | `QuotationOutputPrecisionHttpContractTest` + `EnsureCardValuesEditConcurrencyTest`：5/5，0 skip |
| 后端关键精度/生命周期/并发 | PASS | 任务重点 9 类：37/37，0 failure/error/skip；最终复跑 BUILD SUCCESS |
| 差异修复回归 | PASS | `ExcelColumnResolverTest` 8/8；`EffKeyNodeIdAlignmentTest` 4/4；结构整数与 decimal string 断言分离 |
| 前端单测 | PASS | 95 files、1147 tests、0 skip |
| 前端类型/构建 | PASS | 四套 TypeScript 0 错误；Vite 3385 modules，build exit 0 |
| Playwright | PASS | exact-5：5/5、0 skip、2.9m；SIMPLE/COMPOSITE/三视图/12位对账阻断与恢复均通过 |
| DB/Flyway | PASS | V385 唯一且 success=t；21 列全部 numeric(26,12)；最大合法值/超界回滚通过 |
| 性能/N+1 | PASS/BLOCKED | TC-078 N=32/2N=64 均 12 SQL；TC-079 字符串快照 +388 bytes、完整响应；TC-077 按规则 BLOCKED |
| 文档治理 | PASS | main-api 已移除 `refresh-versions`，回写 task-0810 全局契约及 P1~P4、P1-18a~e 覆盖矩阵；PRD v4.6/V385 已更新 |

### 0.2 全量 Maven 基线判定

feature 全量命令实际执行完毕，但仓库全量基线本身不是绿色：

| 运行 | Tests | Failures | Errors | Skipped | 判定 |
|---|---:|---:|---:|---:|---|
| feature | 2412 | 166 | 164 | 70 | BUILD FAILURE；任务重点 37 条全部通过 |
| master detached baseline（有效第二轮） | 2306 | 151 | 443 | 32 | BUILD FAILURE；使用同一 DB/schema，并仅忽略 master 未知的 future V385 |

第一次 master 基线因 Flyway 无法解析已应用 V385，Quarkus 未启动并级联 1272 skip，已判无效。第二轮先用 `quarkus.flyway.ignore-migration-patterns=*:future` 做启动探针，确认 Flyway 校验和 Quarkus 启动成功后才执行全量。

结构化 testcase 对比显示：共同失败/错误 312 条；master 失败而 feature 已通过 241 条；feature 初始独有 6 条共同测试失败。6 条均是测试断言未适配新契约：3 条把 Excel `display_format.decimals` 结构整数夹具误改成 BigDecimal，3 条仍对 decimal-string TextNode 调 `decimalValue()`。仅修正测试后，Excel 8/8、EffKey 4/4、任务关键 37/37 全绿。`DataLoaderTest` 的 4 个 `sqlViewExecutor=null` error 在 master/feature 同样复现，不属于本任务新增。

因此本任务结论是：**仓库全量仍有存量红灯，但没有未解释的 task-0810 精度/并发回归**。不得把上表描述为“全量测试通过”。

### 0.3 TC-086 skip inventory

| 分类 | 数量 | 原因与判定 |
|---|---:|---|
| 共享 DB / 固定 fixture Assumptions | 53 | 分布于 26 个存量 suite，取决于真实模板、driver 行和固定报价；非本任务新增 |
| PerformanceTest | 13 | 未开启显式 `cpq.run.perf` 开关 |
| live scan | 2 | 未开启显式 `RUN_LIVE_DB_SCAN` 开关 |
| 环境/RBAC disabled | 1 | `ComponentCycleConcurrentSaveTest` 的显式环境限制 |
| 历史资源 disabled | 1 | `MiscEdgeTest` 引用已退役资源 |
| task-0810 重点测试 | 0 | 9 类 37 条、Playwright exact-5、前端 1147 条均 0 skip |

`FormulaGoldenTest` 保留 `expectedSource=pending` 的 dormant 分支，但本轮 golden 数据无 pending，因此实际 0 skip。新增 `EnsureCardValuesEditConcurrencyTest` 自建并清理完整 fixture，无 Assumptions，实际 1/1 PASS。

### 0.4 AC-1～AC-20

| AC | 结果 | 核心证据 |
|---|---|---|
| AC-1～5 | PASS | 双端 12/9/HALF_UP 常量与 `1/3`、`10/3*3`、正负边界黄金用例 |
| AC-6 | PASS | 大金额 decimal string 经 API、DRAFT、DB/JSON、重开逐字一致；三视图显示 9 位 |
| AC-7 | PASS | V385、21 列 numeric(26,12)、JPA 映射、容量和事务回滚 |
| AC-8～9 | PASS | 后端新增精度链禁 Double；前端禁精度 number；历史 numeric 无损解析；新快照写 string |
| AC-10～11 | PASS | 报价/核价/详情三视图一致；HTML/打印源/邮件/Excel 输出契约与 STRING 单元格测试 |
| AC-12～14 | PASS | 三个 `__amount_total__` 路径保留 12 位；升版总额 12 位；amt-002/003 双端 golden |
| AC-15 | PASS | 基础取数 8/12 位不被显示规则压缩，数量/费率业务 scale 不变且传输为 string |
| AC-16 | PASS | 非 DRAFT GET/快照/比较读取 md5/xmin 与结构计数不变，零 UPDATE/INSERT |
| AC-17 | PASS | DRAFT 保存/重算/并发 ensure-edit 按 12 位稳定，事务锁阻止丢更新 |
| AC-18 | PASS | null、空值、除零、非法公式、全角运算符保持父任务语义 |
| AC-19 | PASS（差异基线） | task 重点 37/37、前端 1147/1147；全量存量红灯已做 master testcase 差异并修清 feature 独有共同失败 |
| AC-20 | PASS | Playwright exact-5：SIMPLE/COMPOSITE、保存重开、三视图、对账阻断/恢复，加载中归零 |

---

## 历史阶段记录（已被 §0 最终结论取代）

## 1. 执行环境与待测版本

| 项 | 记录 |
|---|---|
| worktree | `/home/joii/project/codex-cpq-task-0810` |
| 分支 | `feat/task-0810-formula-precision-12-display-9` |
| HEAD | `b3cf4872eb4ee4e0a40c16dfd23fbf1ab6799ced` |
| 后端实现 diff hash | `561ce4a4cc2546093349488ab9f77a9a1dc4bcce05efc826dc1413e56e48415a` |
| 整体待测快照 hash（创建报告前） | `c264f870dd999e980d4dfafe1e4ec442a54f3fd82a2e1a596d290b64e5ef47b6` |
| 后端文件规模 | 已跟踪改动 91 个；未跟踪新增 10 个；已跟踪 diff 为 862 insertions / 530 deletions |
| Flyway | `V385__task0810_formula_scale12.sql`；当前前序最高为 V384 |
| 数据库 | 自动化 test profile：`cpq_db`；联调默认 profile：`cpq_db_0724`，实际连库待阶段 D/E 确认 |

diff hash 计算口径：`git diff --binary HEAD` 加全部未跟踪文件的 SHA-256 清单；后端 hash 仅纳入 `cpq-backend/`，后续每阶段复核，避免同 worktree 前端并行改动干扰后端待测身份。

> 失效声明：主审在阶段 B 发现真实缺陷并将后端退回返修。上述 HEAD/hash 只用于定位本轮失败版本，不再是可继续执行 C~F 的有效待测基线。

### 1.1 最终返修版本重新冻结（2026-08-10）

| 项 | 最终待测记录 |
|---|---|
| 分支 | `feat/task-0810-formula-precision-12-display-9` |
| HEAD | `b3cf4872eb4ee4e0a40c16dfd23fbf1ab6799ced` |
| 最终后端 diff hash | `771a0abd56dba9b5da69050c041d9a7a71080fd9eedba26b6d5e9404e3986bf4` |
| 后端文件规模 | 已跟踪改动 97 个；未跟踪新增 12 个；已跟踪 diff 为 999 insertions / 579 deletions |
| 开发自检 | compile exit 0；广域 12 类 101/0/0/0；NonDraft 1/0/0/0；最终增量 12/0/0/0；CpqPathParser 1/0/0/0；Flyway 启动至 V385 |
| 静态基础 | `git diff --check` exit 0；阶段 B 由测试工程师独立复扫 |

hash 已按开发方交接的精确命令独立复算并逐字一致：

```bash
cd /home/joii/project/codex-cpq-task-0810
{ git diff --binary -- cpq-backend; git ls-files --others --exclude-standard -- cpq-backend | sort | while read f; do printf "%s\0" "$f"; sha256sum "$f"; done; } | sha256sum
```

中间返修 hash `438e01687ef01d1deddf920ac6aed44ba933ac6e08265214b3ff9bb41f3bde34` 在补充 `CpqPathParser` 与价格/策略 Excel STRING 修复后失效，不作为测试基线。

## 2. 阶段 A · 文档与版本冻结

### 2.1 状态

| 检查项 | 结果 | 证据 |
|---|---|---|
| 分支/HEAD | PASS | `git branch --show-current`、`git rev-parse HEAD` |
| 后端 diff hash | PASS | `561ce4a4...415a` |
| 后端文件清单 | PASS | `git diff --name-only HEAD -- cpq-backend` + `git ls-files --others --exclude-standard -- cpq-backend` |
| 迁移号 | PASS（静态） | V385 唯一文件，V384 为前序最高；数据库 history 留阶段 D |
| API DTO/validator inventory | PASS（静态） | 见 §2.3；动态契约留阶段 E |

### 2.2 后端文件 inventory

已跟踪改动分组：

| 分组 | 主要文件 |
|---|---|
| 精度基础设施 | `PrecisionPolicy.java`、`NumberFormatUtil.java`、`DecimalJexl.java` |
| 公式/JEXL | `FormulaCalculationService.java`、`FormulaEngine.java`、`FormulaCalculator.java`、`TemplateFormulaService.java`、`SafeArithmetic.java`、`TabJoinPlanEvaluator.java`、条件求值器 |
| 报价/核价/快照 | `QuotationService.java`、`CardSnapshotService.java`、`ConfigureSnapshotService.java`、`CostingFreezeService.java`、`CostingVersionService.java`、`CostingSubtotalUtil.java`、`RowDataMaterializer.java`、`ComponentDataEffectiveRows.java` |
| 折扣/价格调整 | `LineDiscountService.java`、`MaterialVersionUpgradeService.java`、`PriceReconciler.java`、review/revision/job entities |
| API/DTO | `SaveDraftRequest.java`、`QuotationResource.java`、`FormulaEvaluateResource.java`、基础资料/元素价格/策略/客户/价格调整 DTO |
| 导出 | `QuotationExportService.java`、`ComparisonExportService.java`、`ExcelViewService.java` |
| JPA 21 列 | `Quotation.java`、`QuotationLineItem.java`、`QuotationLineComponentData.java`、`CostingOrder.java`、4 个价格调整实体 |
| 测试 | 42 个既有后端测试文件改动，覆盖 precision、formula、snapshot、cross-tab、Excel、costing 等 |

未跟踪新增 10 个文件：

```text
cpq-backend/src/main/java/com/cpq/common/DecimalJacksonCustomizer.java
cpq-backend/src/main/java/com/cpq/common/DecimalJexlArithmetic.java
cpq-backend/src/main/java/com/cpq/common/DecimalRequestValidator.java
cpq-backend/src/main/java/com/cpq/common/DecimalStringDeserializer.java
cpq-backend/src/main/resources/db/migration/V385__task0810_formula_scale12.sql
cpq-backend/src/test/java/com/cpq/common/DecimalJacksonCustomizerTest.java
cpq-backend/src/test/java/com/cpq/common/DecimalRequestContractTest.java
cpq-backend/src/test/java/com/cpq/common/FormulaAmountColumnMappingTest.java
cpq-backend/src/test/java/com/cpq/quotation/service/NonDraftPrecisionReadOnlyTest.java
cpq-backend/src/test/java/com/cpq/quotation/service/QuotationEmailPrecisionTest.java
```

完整 91 个已跟踪文件清单由以下命令冻结，原始输出保留在本次执行记录：

```bash
git -C /home/joii/project/codex-cpq-task-0810 diff --name-only HEAD -- cpq-backend
```

### 2.3 API DTO/validator inventory

| 分类 | 实际入口/字段 |
|---|---|
| 报价草稿 | `SaveDraftRequest.finalDiscountRate`；lineItem `subtotal`、`discountBaseAmount`、`discountRateApplied`、`lineDiscountAmount`、`lineUnitPrice`、`lineFinalPrice`、`lineTotalAmount`；component `subtotal` |
| 动态报价/Excel | `QuotationResource`: quote-card-edit `value/rowData`，reconcile frontend/backend values/inputs，dry-run columns，Excel cell value，draft 内 productAttributeValues/quoteExcelValues/rowData/compositeProcesses.paramValues |
| 公式 | `FormulaEvaluateResource`: bindings、driverRow |
| 价格调整 | `PriceAdjustSettingsDTO.subtotalGuardThreshold`、`PutStrategyRequest.costDiffThreshold` |
| 定价策略 | `CreatePricingStrategyRequest`: baseDiscount、minOrderAmount、rule thresholdAmount/discountRate |
| 元素价格 | `CreatePriceRequest.price`、`UpdatePriceRequest.price`、`StrategyUpsertRequest.factor/premium` |
| 基础资料/配置 | material unitWeight、process defaultDefectRate、part unitWeightGrams、recipe default/min/maxPct、customer creditLimit |
| 公共保护 | `DecimalStringDeserializer` 拒绝 JSON number；`DecimalRequestValidator` 检查动态 Map 和嵌套 JSON numeric token |

## 3. 阶段执行汇总

| 阶段 | 状态 | P0/P1 | 说明 |
|---|---|---|---|
| A 文档/版本冻结 | 已完成但基线失效 | 0/0 | 旧 hash 仅作缺陷定位；返修后必须重跑 A |
| B 零浮点静态门禁 | FAIL（执行到首批阻断项即暂停） | P0×2 已由测试工程师复现；主审共确认 6 项真实缺陷 | 后端准入撤回 |
| C 后端精度定向 | BLOCKED / 未执行 | - | 依赖 B，通过新 hash 的 B 后才执行 |
| D DB/Flyway/容量 | BLOCKED / 未执行 | - | 未对旧 diff 浪费执行 |
| E API/JSON | BLOCKED / 未执行 | - | 未对旧 diff 浪费执行 |
| F 业务边界/冻结/导出 | BLOCKED / 未执行 | - | 未对旧 diff 浪费执行 |
| G~J 前端/联调/性能/治理 | 不在本轮 | - | 禁止执行 |

## 4. 用例结果

### 4.1 阶段 B 静态门禁部分结果

执行命令：

```bash
rg -n '\.doubleValue\(\)|Double\.parseDouble|Map<String,\s*Double>|\.asDouble\(\)' \
  cpq-backend/src/main/java/com/cpq/common \
  cpq-backend/src/main/java/com/cpq/engine \
  cpq-backend/src/main/java/com/cpq/formula \
  cpq-backend/src/main/java/com/cpq/quotation \
  cpq-backend/src/main/java/com/cpq/costing \
  cpq-backend/src/main/java/com/cpq/priceadjust \
  cpq-backend/src/main/java/com/cpq/configure \
  cpq-backend/src/main/java/com/cpq/template
```

| 用例 | 结果 | 关键证据 |
|---|---|---|
| TC-010 后端零浮点 | FAIL | `ComparisonViewService.java:421,429` 对快照 subtotal/subtotalByColumn 调 `JsonNode.asDouble()` 后再 `BigDecimal.valueOf(...)` |
| TC-016 Excel 禁 double NUMERIC | FAIL | `ExcelViewService.java:1011` 对 precision≤15 的 BigDecimal 调 `num.doubleValue()` 并写 NUMERIC |
| CardSnapshot/ComponentData 复核 | 本两文件未复现实际 Map<String,Double> 承载 | 当前声明和 put 值均为 BigDecimal；命中来自 task-0801 旧注释，注释本身已与新契约不符 |

判定说明：

1. `ComparisonViewService` 两处是精度敏感 subtotal/列小计，明确经过 IEEE-754；同时仅处理 `isNumber()`，无法消费本任务新写 decimal string，违反 FR-3、FR-6、AC-8、AC-9。
2. `ExcelViewService` 的“15 位以内允许 NUMERIC”是旧父任务豁免，已被本任务 D-10/FR-10 明确否决；所有精度敏感计算值必须文本单元格，违反 AC-8、AC-11。
3. 主审独立审查共确认 6 项真实缺陷；本报告不臆测其余 4 项细节，待返修交接时附完整缺陷清单并逐项纳入新 hash 的 B 复测。

按 P0 规则，发现首批真实违规后停止 C~F。未运行任何 Maven 测试、SQL/Flyway、API 或导出动态测试，因此没有 Tests run 统计可填。

## 5. 缺陷清单

| 编号 | 严重级 | 状态 | 复现位置 | 影响 |
|---|---|---|---|---|
| BE-P0-01 | P0 | 已复现，退回返修 | `cpq-backend/src/main/java/com/cpq/costing/service/ComparisonViewService.java:421,429` | 比较视图 subtotal/列小计丢精度，且新 decimal string 快照可能不被读取 |
| BE-P0-02 | P0 | 已复现，退回返修 | `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java:1011` | Excel 精度计算值仍经 double/NUMERIC，违反精度优先及文本单元格契约 |
| BE-P1-01 | P1 | 待返修清理 | CardSnapshotService、ComponentDataEffectiveRows、ConfigureSnapshotService 中 task-0801 旧 Map<String,Double>/double 注释 | 注释与当前 BigDecimal 契约冲突，易导致后续误判/回归 |

后端准入撤回：等待主线通知返修完成后的新 HEAD/diff hash。收到后从阶段 A 重新冻结，不沿用本报告旧 PASS。

## 6. AC 中间对照

当前 AC-8、AC-9、AC-11 明确未达成；其余 AC 未执行/未判定。本报告不在返修和前端联调完成前宣称任何 AC 最终达成。

## 7. 最终返修版本复测（hash `771a0abd...6bf4`）

### 7.1 阶段 A 重新冻结

| 检查项 | 结果 | 证据 |
|---|---|---|
| 分支/HEAD | PASS | `feat/task-0810-formula-precision-12-display-9` / `b3cf4872eb4ee4e0a40c16dfd23fbf1ab6799ced` |
| 后端 diff hash | PASS | 按 §1.1 命令独立复算为 `771a0abd56dba9b5da69050c041d9a7a71080fd9eedba26b6d5e9404e3986bf4`，与开发交接一致 |
| 文件规模 | PASS | 已跟踪 97，未跟踪 12；999 insertions / 579 deletions |
| diff 健康 | PASS | `git diff --check` exit 0 |
| 开发自检交接 | PASS（交接证据） | compile 0；101+1+12+1 均 0 failure/error/skip；测试工程师动态复跑尚未开始 |

### 7.2 阶段 B 静态门禁结果

| 用例 | 结果 | 关键证据 |
|---|---|---|
| TC-010 禁浮点转换 | PASS（已执行部分） | 定义范围内 `.asDouble()`、`.doubleValue()`、`Map<String,Double>` 生产转换 0；剩余 `DoubleNode` 均为说明旧风险的注释，Double/Float 生产分支需逐项分类 |
| TC-016/040 Excel STRING | FAIL | `ComparisonExportService.writeValue` 接受任意 `Number`；`ComparisonExportServiceTest` 用整数 number 并断言 `getNumericCellValue()` |
| TC-012/018/083 API string-only | FAIL | `ComparisonExportRequest.Cell.quote/costing` 为 `Object`；`POST /api/cpq/quotations/{id}/comparison/export` 未做 decimal-string 校验，可接受 JSON number |

新增缺陷：

| 编号 | 严重级 | 状态 | 复现位置 | 影响 |
|---|---|---|---|---|
| BE-P0-03 | P0 | 已复现，退回返修 | `ComparisonExportRequest.java:19-22`、`ComparisonExportService.java:82-96`、`CostingSheetResource.java:35-43`、`ComparisonExportServiceTest.java:24-45` | 比较导出精度值可由 JSON number/Java Number 进入后端；旧测试还要求 POI NUMERIC，违反 AC-6、AC-8、AC-11 与 FR-7/FR-10 |

### 7.3 原 6 项缺陷的 TC 映射与强制复测断言

| 缺陷 | 映射 TC | 修复后必须保留的动态断言 |
|---|---|---|
| comparison subtotal string/legacy numeric | TC-023~025、TC-035、TC-054 | 同一 `subtotal/subtotalByColumn` 分别以新 decimal string 与历史 numeric 输入，逐字得到 BigDecimal；比较视图/导出同值，不得忽略 string 节点 |
| 真实结构整数键误拒绝 | TC-018~019、TC-028、TC-083 | 含 `序号`、`_项次`、积分 `annualVolume` 的真实 draft payload 2xx；非积分结构值和任一精度 JSON number 均 400，事务零部分写 |
| Excel 精度值走 double/NUMERIC | TC-016、TC-040~041、TC-050 | POI 读取报价 Excel、Excel View、比较导出：计算精度格为 STRING 且最多 9 位；原始 decimal 格为 STRING 且 12 位原文不被压缩 |
| Template row JEXL 数值字面量 | TC-014 J3、TC-015 | 真实 `evalRowExpression` 的 `0.1+0.2` 为 BigDecimal `0.3`；另测 1/3、函数、大金额和负数，节点按 12 位 |
| `ElementOverride.pct` 接受 JSON number | TC-018、TC-052、TC-083 | 实际配置端点 `pct` decimal string 成功并精确回读；JSON number 返回 400，message 含字段/原值，无部分写 |
| 非 DRAFT comparison metadata 读时插表 | TC-053~055 | 删除 `quotation_view_structure` 后对 APPROVED/frozen 单据调用 get/meta/comparison/export；quotation/line/component 的 xmin+md5 与 structure count 前后完全一致，SQL 无 UPDATE/INSERT |

映射审查结论：现有 86 条 TC 可承载全部 6 项，但不能仅以反射/私有方法单测判通过。端点级 HTTP 400、POI CellType、真实结构键 payload 和 comparison structure 零写入证据均为强制交付物。

判定：阶段 B FAIL，后端准入再次撤回。按 P0 规则未执行最终 hash 的 Maven 动态复跑、SQL/Flyway、API 或导出测试，C-F 全部停止。修复后必须产生新 hash，并从阶段 A/B 重新执行；不得沿用本节 PASS。

## 8. BE-P0-03 修复后第三轮复测

### 8.1 阶段 A 最终冻结

| 检查项 | 结果 | 证据 |
|---|---|---|
| 分支/HEAD | PASS | `feat/task-0810-formula-precision-12-display-9` / `b3cf4872eb4ee4e0a40c16dfd23fbf1ab6799ced` |
| 后端 diff hash | PASS | 测试工程师按 §1.1 命令独立复算 `84444c2bb8f5281702aa97645c2c8250a7926c147fc71a48bcbf0fadbf203827`，与开发交接一致 |
| 文件规模 | PASS | 已跟踪改动 100 个；未跟踪新增 12 个；1076 insertions / 605 deletions |
| diff 健康 | PASS | `git diff --check` exit 0 |
| 开发自检交接 | PASS（交接证据） | `test-compile` PASS；DTO/POI 6/6；HTTP 5/5；0 failure/error/skip |

### 8.2 阶段 B 静态门禁

| 检查项 | 结果 | 证据 |
|---|---|---|
| 精度链禁用转换 | PASS | 定义范围 `.asDouble()`、`.doubleValue()`、`Double.parseDouble`、`Map<String,Double>` 生产命中 0 |
| BE-P0-01 comparison 解析 | PASS（静态） | subtotal/subtotalByColumn 同时使用 `JsonNode.decimalValue()` 与 `new BigDecimal(text)`，无 double 中转 |
| BE-P0-02/03 Excel STRING | PASS（静态） | 报价/Excel View/比较导出精度值均调用 string overload；`ComparisonExportService.writeValue` 仅接收 BigDecimal |
| comparison export API | PASS（静态） | `Cell.quote/costing` 为 BigDecimal 且使用 `DecimalStringDeserializer`；不再有 Object/Number 旁路 |
| Template JEXL / ElementOverride | PASS（静态） | row JEXL decimal literal 与 `ElementOverride.pct` string-only 注解存在 |
| non-DRAFT 零写 | PASS（静态） | ensure 路径有 DRAFT guard；零写测试指纹包含 `quotation_view_structure` count |
| 旧契约注释 | PASS | 精度服务的 `Map<String,Double>`/转回 double 旧注释及导出 6 位/仅 >15 位说明已清理 |

阶段 B 结论：PASS，可以进入 C。所有静态 PASS 仍需由 C-F 的动态证据确认，不能单独用于最终 AC 放行。

### 8.3 阶段 C 后端精度定向

第一批命令覆盖 12 类：`PrecisionPolicyTest`、`NumberFormatUtilTest`、`DecimalJacksonCustomizerTest`、`DecimalRequestContractTest`、`FormulaAmountColumnMappingTest`、`FormulaEngineTest`、`FormulaCalculationTest`、`FormulaCalculatorGoldenCasesTest`、`FormulaGoldenTest`、`TemplateFormulaServicePrecisionTest`、`ExcelViewServicePrecisionTest`、`ComparisonViewServicePrecisionTest`。

结果：**Tests run: 101, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS**。覆盖集中策略、显示边界、Jackson string 契约、21 列映射、formula golden、Template/Excel JEXL、comparison string/legacy numeric。

阶段 C 暂未最终判定：继续补跑 J5 `TabJoinPlanEvaluator/SafeArithmetic` 与 J6 `CostingSheetService` 独立矩阵，以及 BL-0159/0160 对应定向测试。

补跑结果：

- J5/J6：`TabJoinPlanEvaluatorEvalTest` + `CostingSheetServicePrecisionTest`，15/0/0/0，BUILD SUCCESS。
- BL-0159/0160 小计组：`CardSnapshotAmountTotalTest`、`CardSnapshotSubtotalTest`、`ComponentDataEffectiveRowsTest`、`ComponentDataEffectiveRowsDiscountTest`、`ComponentSubtotalColumnKeyTest`、`FormulaCalculatorKsumTest`、`FormulaCalculatorMultiSubtotalTest`、`SubtotalUnitConversionTest`，29/0/0/0，BUILD SUCCESS。
- 阶段 C 当前累计 145 tests，0 failure，0 error，0 skip。

覆盖缺口：`test.md` 的 P0 TC-042/044 明确要求 `0.083825536789` 并对三个 `__amount_total__` 登记点验证 12 位尾数且可识别 4 位截断；当前测试源码无该值，`CardSnapshotAmountTotalTest` 使用整数 10/20，`ComponentDataEffectiveRowsTest` 仅有 6 位小数 `0.224474`。现有测试能识别部分 4 位截断，但不能证明三条生产路径均保留 12 位。阶段 C 暂不判 PASS，暂停 D-F，等待补充最小自动化后重新冻结 hash。

### 8.4 TC-042/044 首轮补测版本失效记录

测试工程师与开发方独立复算 hash 均为 `28d24df3367ba8b3208a886a8b7893bb41cf4fcc1f2c4f5311e2549fe3f943ae`。该版本新增 PASS1、backfill、EffectiveRows 三生产路径的 `0.083825536789` 完整值断言，并由测试工程师重跑阶段 C 合并组：**148/0/0/0，BUILD SUCCESS**。

但开发自审发现 TC-042 明确要求“两金额列和含 12 位尾数”，首轮三条新增测试均以单金额列直接得到目标值，只证明单值传递，未证明两列求和过程无中间 4 位截断。因此该 hash 的 A/B/C 结果仅作历史定位，不用于继续 D-F；等待两金额列补强后的新 hash，从 A/B/C 重新执行。

## 9. 最终两金额列基线

### 9.1 阶段 A/B

| 检查项 | 结果 | 证据 |
|---|---|---|
| HEAD/hash | PASS | `b3cf4872eb4ee4e0a40c16dfd23fbf1ab6799ced` / `67dfc4bcd704fc8fcf382b1b89fb228f96e8992a66516f5245ac082a8a10d9b2`，开发与测试独立复算一致 |
| 文件规模 | PASS | 已跟踪 100；未跟踪 12；1173 insertions / 605 deletions |
| diff/static | PASS | `git diff --check` 0；精度范围禁用转换 0 |
| TC-042/044 测试结构 | PASS | PASS1、backfill、EffectiveRows 均使用两个 is_amount 列 `0.040000000001`、`0.043825536788`；和为 `0.083825536789`，并反证 `0.0838`；backfill 另覆盖 cid/code/tab 三前缀 |

### 9.2 阶段 C

测试工程师在最终 hash 上重跑 22 类合并组，覆盖集中策略、Jackson/API DTO、21 列映射、六 JEXL、formula golden、K/SUM/多小计、折扣、单位换算和三条 `__amount_total__` 路径。

结果：**Tests run: 148, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS**。阶段 C PASS，可以进入 D。

### 9.3 阶段 D DB/Flyway/容量

| 检查项 | 结果 | 证据 |
|---|---|---|
| V385 唯一/成功 | PASS | 文件系统仅 `V385__task0810_formula_scale12.sql`；test DB `flyway_schema_history` version=385 仅 1 行，description=`task0810 formula scale12`，success=t |
| 迁移行为 | PASS | SQL 仅 8 个 ALTER TABLE、21 个 `ALTER COLUMN ... TYPE numeric(26,12)`；无 UPDATE/INSERT/DELETE |
| information_schema | PASS | 需求列出的 21 个实际列全部 `data_type=numeric, precision=26, scale=12` |
| JPA 映射 | PASS | `FormulaAmountColumnMappingTest` 已包含在阶段 C，21 个字段全部 precision=26/scale=12 |
| 最大合法容量 | PASS | 显式事务内以 21 个真实列类型创建隔离 TEMP 表；`99999999999999.999999999999` 写入后 `legal_columns_matched=21` |
| 超界/零部分写 | PASS | 内层事务尝试 `100000000000000.000000000000`，捕获 `numeric_value_out_of_range`；同语句合法字段与溢出字段指纹均保持 `1.000000000000`；外层 ROLLBACK |

容量测试未修改任何持久业务行：TEMP 表为 `ON COMMIT DROP`，命令结尾显式 `ROLLBACK`。阶段 D PASS，可以进入 E。

### 9.4 阶段 E API/JSON 端点契约审查

进入 E 前再次独立复算 backend diff hash：`67dfc4bcd704fc8fcf382b1b89fb228f96e8992a66516f5245ac082a8a10d9b2`，与阶段 A-D 基线一致。

已执行的动态测试：

- `DecimalRequestContractTest`：**4/0/0/0**。证明公共 ObjectMapper/DTO/动态 Map validator 的局部契约，但测试方式为 mapper、反射及源码 marker inventory，不是 P1-P4 端点 HTTP 证据。
- `CostingComparisonResourceTest`：**5/0/0/0**。其中 comparison export decimal string 请求返回 200；`quote` JSON number 返回 400，错误体包含 `rows[0].cells.MATERIAL.quote` 和原值。

按 `test.md` TC-082/TC-083 审核后，Stage E 判定为 **FAIL（P0）**。矩阵至少包含 49 条命名路由（P1 22、P2 2、P3 8、P4 17），另有 P4 配置实际写端点；当前没有一条路由具备完整的“原始 string/null 响应 + 结构整数 number + 逐类 number=400 + 路径/原值 + 零部分写”证据。comparison export 仅为部分覆盖。

| 分组 | 结论 | 端点到现有/缺失测试证据 |
|---|---|---|
| P1 报价主链 22 条 | **22/22 缺完整证据** | P1-07、P1-08、P1-15、P1-16、P1-17、P1-18a~e 在测试源码中连路由引用都没有；其余只有既有 CRUD、生命周期或夹具测试，没有 12 位金额/数量/费率 JSON string 类型断言，也没有 P1-04/08/09/13/17 及条件写端点逐精度字段 number=400、字段路径/原值和 DB 零部分写断言。 |
| P2 公式求值 2 条 | **2/2 缺完整证据** | P2-01 的 `FormulaEvaluateResourceTest` 使用 `anyOf(equalTo(7), equalTo("7"), equalTo(7.0F), ...)`，明确容许 JSON number，且未测 bindings/driverRow number=400；P2-02 batch-evaluate 没有 HTTP 测试。 |
| P3 Excel/导出 8 条 | **8/8 缺完整证据** | P3-04 export/html、P3-07 export-excel-view 无测试路由引用；其余为旧 smoke 或 service 直测。P3-02/03 缺计算输入逐字段 number=400；文件 HTTP 响应未解析验证最多 9 位和 POI STRING。`QuotationEmailPrecisionTest` 直调 service/MockMailbox，可作为邮件内容测试，但不能替代 P3-08 HTTP 契约。 |
| P4 核价/比较/价格调整 17 条 | **17/17 缺完整证据** | costing-orders 4 条、price-revisions 2 条、reviews 5 条、jobs 3 条均无测试路由引用。comparison GET/view 旧测试只断言结构键，不断言 decimal JSON token 为 string。comparison export 正向 HTTP 只断言 200/MIME，未读取工作簿；负向仅覆盖 `quote` 一个字段。 |
| P4 阈值/元素价格等配置写端点 | **缺动态 HTTP 证据** | `DecimalRequestContractTest` 只证明 DTO deserializer/validator；没有实际 URL、权限、事务、成功回读或失败零写证据，不能满足 TC-083。 |

新增阻断缺陷：

| 编号 | 严重级 | 状态 | 复现位置 | 影响 |
|---|---|---|---|---|
| BE-P0-04 | P0 | 已确认，退回补测 | `test.md` TC-082/083；`DecimalRequestContractTest`；`CostingComparisonResourceTest`；P1-P4 现有 HTTP 测试 | API string-only 契约没有逐端点动态证据；旧 P2-01 断言甚至继续允许 number 响应，无法证明 AC-6/FR-7。 |

返修验收条件：

1. 建立“矩阵行 -> `@QuarkusTest` 类/方法 -> 原始响应 artifact -> 结果”清单，P1-01~18e、P2-01/02、P3-01~08、P4 17 条及配置实际写端点不得缺行。
2. 每条响应递归断言精度语义节点为 JSON string/null，结构整数为 number；不能只断言 HTTP 200 或结构键存在。
3. 每个含精度字段的 POST/PUT 按金额、数量、费率、公式值/动态 Map/嵌套快照逐类注入 JSON number，分别断言 400、message 含完整字段路径和原值；写前后记录行数及业务行 md5/xmin，证明事务零部分写。
4. P2-01 删除允许 `7`/`7.0F` 的宽松断言，精确断言 string；P2-02 保留批量单项错误隔离与顺序不变。
5. 文件端点必须从真实 HTTP 响应读取 HTML/PDF/Excel；Excel 用 POI 断言精度计算格 `CellType.STRING` 且显示最多 9 位。

按 P0 门禁规则，阶段 F 及最终后端交付验收暂停；不得以阶段 C 的 service/unit PASS 或静态 DTO inventory 替代 Stage E。
