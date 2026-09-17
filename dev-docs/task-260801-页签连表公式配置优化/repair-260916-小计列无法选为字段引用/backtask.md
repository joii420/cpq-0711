# backtask · repair-260916 小计列无法选为字段引用

> **只按本文件做后端**。验收标准以 `问题说明.md` ⑥ 的 **AC 原文**为准，本文件与 AC 有出入时以 AC 为准并报告主线。
> 写法规则、Excel 求值口径、存量改写规则的**唯一出处**是 `问题说明.md` ⑤ 5.1 / 5.3 / 5.4 与 `api.md`。

## 任务分解

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-14, AC-15 | `com.cpq.quotation.service.tabjoin.TabJoinPlanEvaluator`：`parseTok`（`:28`）识别 `(小计)` 后缀 —— 与带列名的 `(总计)` 同义（取 `provider.subtotalOfColumn`）；`[页签(小计)]` 无列名 → 抛 `IllegalArgumentException`，消息 `「(小计)」要写在列名后面，如 [页签.列(小计)]；整页签合计请写 [页签(总计)]`。确认 `evalRow`（`:190`）、`hasBareDetail`（`:130`）、`evaluateColumn`（`:290-316`）三处按新 `total` 判定工作。**不改任何既有计算口径**（5.3 五条规则即现行实现） |
| **B-2** | AC-14 | `ExcelViewService.validateTabJoinConfig`（`:1380`）：识别 `(小计)`；带 `(小计)` 的引用参与「页签已声明」校验、不参与「明细跨行键类」校验；无列名 → `BusinessException(400, "页签连表公式列 " + col_key + " 的「(小计)」要写在列名后面，如 [页签.列(小计)]")` |
| ~~**B-3**~~ | ~~AC-15~~ | **D-15 撤销**：删除 `V446__repair260916_tabjoin_subtotal_suffix.sql` 及只为它存在的测试；删除前按 `git-worktree.md §4.5` 确认没有 cwd 在本 worktree 的 java 进程，删除后 `mvnw clean` 确认 `target/classes/db/migration` 下无 V446。以下为原内容，仅留痕：| 新 Flyway 迁移 `V{N}__repair260916_tabjoin_subtotal_suffix.sql`：按 `问题说明.md` 5.4 改写存量 Excel 连表公式文字。要求：① **建文件前实查最新版本号**（`ls cpq-backend/src/main/resources/db/migration \| sort -V \| tail -3` + 查 `flyway_schema_history`），本文档写作时最新为 `V445`；② 纯 SQL / PL-pgSQL，按字面量 `replace()`，不用正则；③ 幂等（重复执行第二次改写 0 行）；④ 覆盖 `component.excel_columns`、`template.excel_view_config`（旧数组形态 + `column_overrides`）、`template_component_snapshot.excel_columns`、`customer_excel_template.excel_columns` 四处；⑤ 引用组件不存在 → 该列原样保留 + `RAISE NOTICE`；⑥ 只改 `expression` 文本，**不动其他键、不改列顺序** |
| ~~**B-4**~~ | ~~AC-16~~ | **D-15 撤销**：删除 `TabJoinSubtotalSuffixRewriter`、`ComponentImportService` 的导入改写、`Repair260916LegacyBundleImportTest`、`TabJoinSubtotalSuffixRewriterTest`；`ComponentExportBundle.bundleVersion` 与 `BUNDLE_VERSION_CURRENT` 恢复 `1.1`；`legacyBundleTreeHint` 恢复原样 ⇒ 这些文件相对 master 应为 0 行差异。`src/test/resources/repair-260916/bundle-v1.*.json` 若无测试再引用则删除。以下为原内容，仅留痕：| 导入兼容：`ComponentImportService` 在写入包内组件的 `excelColumns` 之前，对 `bundleVersion` 低于 `1.2` 的包按 5.4 同一规则改写（被引用组件的「勾了小计的列」取**包内同组件**的字段定义；`tabKey` 形态处理同 5.4）；`ComponentExportBundle.bundleVersion` 与 `ComponentImportService.BUNDLE_VERSION_CURRENT` 同步升为 `1.2`；`legacyBundleTreeHint`（现 `:630`）的判定改为「版本低于 1.1」，使 1.1 包的行为与改动前一致。🚫 不给既有 DTO 字段加 `@JsonInclude`（决策台账 `ComponentExportBundle` 条） |
| **B-5** | AC-14 | 共享对拍夹具 `src/test/resources/tabjoin-excel-cases.json` **由后端定稿**（前端 F-8 逐字节拷贝）+ 读取它的后端测试（逐用例调 `TabJoinPlanEvaluator.evaluateColumn`，断言等于期望值）。用例至少覆盖 AC-14 a~e；页签行数据取 `QT-20260916-0881` 物料页签真实值（见 AC-14 前置），另加一个不勾小计的数值列。夹具格式写进 `api.md` §4 |
| **B-6** | AC-11 | 后端回归测试：用 `证据/离线判决/snap.jsonl` 中 `COMP-0002`「非银点类材料成本公式」与 `QT-20260916-0881` 行值（同 `证据/离线判决/evalh85.mts` 的数据），把公式中「来料固定加工费·加工费」一项分别构造为 `component_subtotal` 与 `cross_tab_ref(NONE, match=销售料号+料号)`，经 `FormulaCalculator` 计算 H85（料号 `00144`）；断言分别为 `0.463735546` 与 `0.212585104`（9 位显示口径）。夹具从真实文件读取，🚫 不手搓（`change-protocol.md §2` 步骤 1） |
| **B-8** | AC-14 | （D-10）`SafeArithmetic.divide`：操作数为 `Double` / `Float` 时先按 `new BigDecimal(v.toString())` 转精确数再交给 `PrecisionPolicy`，不再抛错；共享夹具 `tabjoin-excel-cases.json` **追加 AC-14g 用例**（`[物料.材料成本(小计)] / 1.13`、`[物料.X] / 1.13`，期望值按 12 位计算口径），追加后通知主线（前端需重新拷贝） |
| **B-7** | AC-17 | 自测：改动涉及的既有后端测试全部通过；新增覆盖 B-1、B-2、B-4 的测试；N+1 自检声明 |

## 协议检查点（来自 `问题说明.md` 5.2，后端部分）

- [ ] P13 `parseTok` 及三个调用点 → B-1
- [ ] P14 `validateTabJoinConfig` → B-2
- [ ] ~~P15 迁移 → B-3~~（D-15 撤销，确认已删除）
- [ ] ~~P16 导入改写 + 版本号 + 旧格式提示判定 → B-4~~（D-15 撤销，确认已恢复）
- [ ] P24 `SafeArithmetic.divide` → B-8
- [ ] P18 共享夹具 → B-5
- [ ] 全仓再扫一遍：`/usr/bin/grep -arn "(总计)" cpq-backend/src/main/java`，确认没有第 5 处按文字后缀判小计的地方（有则停下报告，例如 `ComponentSampleCardService`、`TokenMappabilityValidator` 的文案）

## 不改的文件（及原因）

| 文件 | 为什么不改 |
|---|---|
| `FormulaCalculator.java` | 页签公式存 token，计算规则不变（AC-12②） |
| `FormulaRefRemapper.java` | 只重映射 `tabs[].tabKey`，不涉及公式文字写法 |
| `ComponentService.java` 的 token 校验 | token 结构不变 |
| 所有接口的路径 / 请求 / 响应结构 | 不变（见 `api.md` §1） |

## 数据库与测试环境（重要）

- 🚦 **本分支含新迁移**。按 `testing.md §4.3`：🚫 **不许在共享测试库 `cpq_db_test` 或开发库 `cpq_db_0724` 上跑会触发 Flyway 的测试或服务**。
- 主线指定：**一次性库** `cpq_db_rp0916d`（2026-09-17 D-15 后由主线重新从 `cpq_db_0724` 克隆，不含 V446；旧库 `cpq_db_rp0916c` 已应用 V446，**不再使用**，进待回收清单），跑测试与临时后端一律 `DB_NAME=cpq_db_rp0916d`。
- 一次性库的回收（`DROP`）属红线，**不要执行**，由主线在闸门 B 报用户。
- 临时后端用临时端口（不占 8081）。

## 自检口径

- worktree 内 `cd cpq-backend && DB_NAME=cpq_db_rp0916d ./mvnw test -Dtest='<本任务相关类>'` 全部通过（先清 `target/`，确认没有别的 maven / quarkus:dev 在写同一 `target/`）；
- 临时后端（临时端口、`DB_NAME=cpq_db_rp0916d`）启动无错误、`No migration necessary`（本任务无迁移）；业务端点返回 401；
- N+1 自检：B-3 为单条迁移；B-4 改写为纯内存（包内数据），不得在循环里查库。
