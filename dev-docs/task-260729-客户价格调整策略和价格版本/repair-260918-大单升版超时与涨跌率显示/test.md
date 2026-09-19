# test · repair-260918 大单升版超时与涨跌率显示（v2 · 2026-09-18 按评审方案 A 修订）

> 用例**只从 `问题说明.md` ⑥ 的 AC 原文派生**。测试工程师**禁止读实现代码**（`cpq-backend/src/main/`、`cpq-frontend/src/`），只读 `问题说明.md` / 本文件 / `api.md`；信息不足就停下报主线，不许翻实现补齐。
> 例外：本文件 §3.2 点名的**注入缝 / 调用入口**（类名 + 方法签名）可以直接用，不需要读实现。

## 1. 分片计划

| 片 | 写入面 | 环境 | 造数前缀 / 隔离 | 时间盒 | 认领的 AC |
|---|---|---|---|---|---|
| **S-BE** | **私有写**：自建测试客户、价格策略、价格版本、报价单副本、更新任务 / 明细、审核行；🚫 不碰库里既有客户 / 版本 / 报价单 / 批次的数据（只读） | worktree 的 `cpq-backend/` 下 `./mvnw test`（`@QuarkusTest`，默认连 `cpq_db_test`；测试 profile 的 `cpq.price-adjust.startup-recovery.enabled=false`） | 客户编码 / 名称、报价单备注、策略名一律带前缀 **`RP0918A`**；只断言自己造的数据；`finally` 按自建 id 清理（🚫 不许按条件批量删）；调 `recoverJobs` / `resumeBudgets` **只许传自建的 id** | **150 分钟**（超时即停，交回未完成清单） | AC-3, AC-5, AC-6, AC-7, AC-8, AC-9, AC-10, AC-11, AC-12, AC-13, AC-14, AC-15, AC-17, AC-18, AC-19, AC-20, AC-21, AC-22, AC-23, AC-24, AC-25 |
| **S-UI** | **零业务写入**：只读开发库既有数据 + 用 Playwright `page.route` 改写接口响应 | worktree 的 `cpq-frontend/` 起**临时端口** vite（如 5294，`/api` 代理到共享 8081，只读）；🚫 不占 5174 | 无造数；⚠️ `e2e/global-setup.ts` 会写 `cpq_db_0724` 的 `user` 表（既有基建，决策台账已登记）⇒ 开跑前必须 `pgrep -f "node.*[p]laywright test"` 确认没有别的 Playwright 在跑 | **30 分钟**（超时即停，交回卡在哪一步 + 失败原文 + 判断是用例问题还是实现问题，由主线亲验接手）；AC-26 追加 10 分钟 | AC-1, AC-2, AC-4, AC-16, AC-26 |

- 两片**写入面不相交**：S-BE 只写 `cpq_db_test` 的自建数据；S-UI 不写业务数据（开发库只读）⇒ 可并行。无 `S-全局` 片。
- 🚫 S-BE 不许与后端实现代理**同时**在同一个 worktree 里跑 maven（`testing.md §4.2.5`）。开跑前确认没有别的 maven / `quarkus:dev` 在写该 worktree 的 `target/`。
- 🚫 任何临时后端必须带 `-Dquarkus.scheduler.enabled=false -Dcpq.price-adjust.startup-recovery.enabled=false`：定时扫描每分钟会替别的客户生成版本；启动收尾会把 8081 正在跑的批次判成中断。
- 本任务**无迁移**。

**解锁**：S-BE 在后端报完成后开跑（不等前端）；S-UI 在前端报完成后开跑（AC-1/2 只依赖前端格式化；AC-4/16 用接口 mock）。

## 2. AC 可追溯矩阵

| AC | 覆盖它的测试 | 层级 | 分片 | 验收证据形式（须归档到本任务 `证据/测试/`，随任务提交） |
|---|---|---|---|---|
| AC-1 | T-UI-1 | E2E（真实数据只读） | S-UI | 版本明细抽屉截图 + 该格文本与颜色断言输出 |
| AC-2 | T-UI-2 | E2E（真实数据只读） | S-UI | 元素矩阵格 / 审核抽屉「为什么变」两张截图 + 文本断言输出 |
| AC-3 | T-BE-3 | 接口（生成版本后查库） | S-BE | 库中 `change_rate` 原值打印 |
| AC-4 | T-UI-4 | E2E（`page.route` 注入 5 个取值 × 3 处页面） | S-UI | 15 格的文本 + 颜色断言输出，关键 3 格截图 |
| AC-5 | T-BE-5 | 接口 | S-BE | 生成新版本前后，既有版本明细 `change_rate` 全量对比输出 |
| AC-6 | T-BE-6 | 接口（≥1000 行私有单） | S-BE | 审核详情接口耗时、依据单行 `adjustedComputed=true` 与 `quoteSubtotalAdjusted` 数值原文 |
| AC-7 | T-BE-7 | 接口 | S-BE | 调审核详情前后：`quotation_price_revision` 行数 / 各行 `last_updated_at`、各产品行 `subtotal` 与 `quote_card_values` md5 对比输出 |
| AC-8 | T-BE-8 | 接口（序列） | S-BE | T₀、失败明细重试耗时、行小计与审核详情「调整后小计」逐位比较输出 |
| AC-9 | T-BE-9 | 接口（序列） | S-BE | 执行中抓到 `running=1` 的一次 `GET /jobs/{id}` 原始响应；7 条明细最终状态；T₀ 与批次耗时；本期记录行数与 `upgraded_material_nos`；`[perf] revision-write … kind=CURRENT` 日志行数 |
| AC-10 | T-BE-10 | 接口（序列 · 两张私有大单） | S-BE | 两张单各自的本期 / 初版记录行数与 `revision_no`、`kind=CURRENT` 日志行数、耗时对 T₀ 的比值 |
| AC-11 | T-BE-11 | 接口 | S-BE | 未通过行 4 列 md5 前后对比；通过行小计 vs 审核详情「调整后小计」 |
| AC-12 | T-BE-12 | 集成（按口径从原表独立计算期望值） | S-BE | 解析后深比较结果（不等时打印第一处差异路径） |
| AC-13 | T-BE-13 | 集成 | S-BE | 四行 `[perf] upgrade … sql=` 日志原文 + `[perf] revision-write … ms=` 原文 |
| AC-14 | T-BE-14 | 集成（mock 注入） | S-BE | 明细状态 / `errorCode` / `errorMessage` 原文；重试后状态、行小计、本期记录 |
| AC-15 | T-BE-15 | 集成（mock 注入） | S-BE | 三种注入下明细 `errorCode` / `errorMessage` 原文，单条重试路径的批次计数前后 |
| AC-16 | T-UI-16 | E2E（`page.route` 注入执行中 → 完成） | S-UI | 执行中截图（「执行中 1」「等待」数值）+ 完成后 30 秒内 Network 请求计数 |
| AC-17 | T-BE-17 | 集成（mock 注入） | S-BE | 库列 `budget_error` 与审核列表接口 `budgetError` 字段原文（D-8 起接口必有该字段）、`budgetStatus`；重算后状态 |
| AC-18 | T-BE-18 | 集成（序列 · mock 注入慢试算） | S-BE | V1 循环退出后 V1 名下 `status=PENDING` 审核数（须为 0）、停止日志原文、V2 预算进度 |
| AC-19 | T-BE-19 | 集成（小单改动前后对比） | S-BE | 四项数值 / 快照三列比较输出 |
| AC-20 | T-BE-20 | 接口（序列 · 单条重试首次升版） | S-BE | 该单 `quotation_price_revision` 全部行（`revision_no` / `based_version_id` / `sealed`）+ 两份快照中该行的比较输出 |
| AC-21 | T-BE-21 | 集成（造「中断」现场后调 `recoverJobs`） | S-BE | 调用前后明细状态 / `errorCode` / `errorMessage`、本期记录 `upgraded_material_nos`、批次状态与计数 |
| AC-22 | T-BE-22 | 集成（mock 抛 `Error`） | S-BE | 批次状态、明细状态、ERROR 日志行原文 |
| AC-23 | T-BE-23 | 集成（预置前 k 个已处理后调 `resumeBudgets`） | S-BE | 前 k 个审核行 `updated_at` 前后对比；续跑后「既无审核行、指针也未指向本版本」的料号数 |
| AC-24 | T-BE-24 | 接口（序列） | S-BE | 批量重试前后各明细状态与重试次数，执行中抓到的一次 `running` |
| AC-26 | T-UI-26 | E2E（`page.route` 注入审核列表：一条 `FAILED` + `budgetError`、一条 `FAILED` + null） | S-UI | 悬停提示文本断言输出 + 截图；null 那条无提示、「重算」可见 |
| AC-25 | T-BE-25 | 集成（开关关闭时调 `runOnStartup`） | S-BE | 调用前后自建 `RUNNING` 批次与明细状态、自建版本审核行数（须完全不变） |

每条 AC 恰好属于一片，无重无漏（S-BE 21 条 + S-UI 5 条 = 26 条；AC-26 为 2026-09-18 开发中裁决 D-8 新增）。

> ⚠️ AC-6 / 8 / 9 / 10 / 11 / 20 / 21 / 23 的**原文含开发环境真实单据 / 真实操作**。测试片在 `cpq_db_test` 用**私有数据**验证同样的可观测断言；**真实环境那一遍由主线亲验完成**（不可分片、不可派，见 §6）。

## 3. 用例要点（按 AC 的可观测断言写，不写实现细节）

### 3.1 造数（S-BE）

- **大单**：以 `cpq_db_test` 里 1845 行的 `QT-20260909-0629` 为模板，走报价单复制接口 `POST /api/cpq/quotations/{id}/copy` 复制出私有副本（备注带 `RP0918A`）；复制不可行时报主线，🚫 不许直接改原单。需要两张大单时复制两次。
- **小单 / 同模板对照**（AC-13）：两张单须**同一模板、同一组件结构**，只是行数不同（12 行 / ≥1000 行）—— 可复制大单后删掉自己副本里的多余行得到小单。
- **T₀**：在私有小单上对一个料号走一次完整的「通过 → 批次完成」，耗时记为 T₀。
- **客户 / 策略 / 版本**：自建前缀客户；价格策略元素只勾银；用策略的「系数 / 加价」制造两次生成之间的价差（🚫 不许改全局元素日价）。
- 断言前先断言「结果非空」，并打印实际值（`testing.md §3`）。

### 3.2 注入缝 / 调用入口（主线点名，可直接用）

| 用途 | 类与方法 | 怎么用 |
|---|---|---|
| AC-14 分组写本期快照失败 | `com.cpq.priceadjust.service.CurrentPeriodRevisionWriter#write(UUID quotationId, UUID targetVersionId, java.util.Collection<String> materialNos)` | `QuarkusMock` 让它抛 `RuntimeException`（只在分组路径那次调用时抛；重试那次恢复真实行为） |
| AC-15 / AC-17 / AC-18 / AC-22 执行或试算异常 / 变慢 | `com.cpq.priceadjust.service.MaterialVersionUpgradeService#upgrade`（既有公开方法，含 `dryRun` 参数的各重载） | ① 异常链外层任意异常包 `jakarta.transaction.RollbackException("ARJUNA016102: The transaction is not active! …")` ⇒ 超时文案；② 根因为 `java.sql.SQLException`（SQLState `57014`）⇒ 超时文案；③ `IllegalStateException("boom")` ⇒ `UNEXPECTED_ERROR` 且信息以 `IllegalStateException` 开头；④ AC-18：`dryRun=true` 时 sleep 若干秒再调真实方法，制造「V1 预算仍在进行」的窗口；⑤ AC-22：抛 `java.lang.AssertionError`（是 `Error` 不是 `Exception`） |
| AC-21 中断收尾 | `com.cpq.priceadjust.service.PriceAdjustStartupRecovery#recoverJobs(java.util.Collection<UUID> jobIds)` | 自造一个批次：≥1 条 `SUCCESS`（真实升版完成、但本期记录里**没有**它 —— 用 AC-14 的注入先制造出「行成功、快照没写」再把明细状态改回 `SUCCESS`，或直接走分组路径时在写快照前中断）、≥1 条 `RUNNING`、≥1 条 `WAITING`，明细 `updated_at` 早于测试开始时刻，批次 `status=RUNNING`；然后只传这个批次 id 调用 |
| AC-23 预算续跑 | `com.cpq.priceadjust.service.PriceAdjustStartupRecovery#resumeBudgets(java.util.Collection<UUID> versionIds)` | 自建版本，让预算只处理前 k 个料号（如 mock 在第 k+1 个抛出并中止循环，或预先建好前 k 个的审核行），记下前 k 个审核行 `updated_at`，再只传该版本 id 调用 |
| AC-25 开关 | `com.cpq.priceadjust.service.PriceAdjustStartupRecovery#runOnStartup()` | 测试 profile 开关为 `false`；先造一个 `RUNNING` 自建批次与一个未跑完的自建版本，调用后断言两者都没被动过。🚫 不许用开关为 `true` 的 profile 调它（会处理测试库里所有人的批次） |

### 3.3 S-UI 要点

- AC-1 / AC-2 读开发库真实数据：客户 `CUST-0004`、版本 `V26091802`、元素银。断言**文本**（`+0.0035%`）与**颜色**（`rgb(207, 19, 34)`）。
- AC-4：对三个页面的接口响应用 `page.route` 把银的 `changeRate` 分别改成 `0.000000034611` / `-0.000000034611` / `0` / `-0.0125` / `null`，逐一断言文本与颜色（负值绿 `rgb(56, 158, 13)`；`0%` 与 `—` 无红绿色）。
- AC-16：用 `page.route` 让 `GET /api/cpq/price-adjust/jobs/{id}` 先返回 `status=RUNNING, total=3, success=1, running=1`（期望「执行中 1」「等待 1」），再返回 `status=SUCCESS, success=3, running=0`；之后 30 秒内对该路径的请求次数必须为 0。布局以 `原型图/进度抽屉.html` 为准。
- AC-26（D-8 新增）：在「价格调整审核」页（`/pricing/reviews`）用 `page.route` 改写审核列表响应，放两条 `budgetStatus=FAILED` 的行：一条 `budgetError="预算试算超时（超过 60 秒）"`、一条 `budgetError=null`；悬停第一条的红色「预算失败」标签 ⇒ 提示文本逐字相等；悬停第二条 ⇒ 无提示；两条的「重算」链接都可见（🚫 不点）。
- 🚫 选择器卡住不许无限重试 —— 到时间盒就交回（`subagents.md §2 f`）。

## 4. 冷启动

本任务无新迁移、无新依赖、无新端口 ⇒ 不触发 `testing.md §5.1` 的冷启动条件（跳过留痕于此）。新增配置项有默认值，不需要任何人手工配置。

## 5. 待回收清单

S-BE 只在 `cpq_db_test` 里写自建数据并在 `finally` 清理，不建一次性库 ⇒ 预期「无」。若实际建了库，写进 `test-report.md` 的待回收清单，🚫 不许自行 `DROP`。

## 6. 主线亲验计划（开发环境 · 真实数据 · D-6 许可）

合并后 8081 热重载即触发启动收尾与预算续跑（`V26091802` 补跑）。亲验顺序：

1. **T₀**：通过 `0028-2609000056`（`QT-20260913-0860`，12 行）→ 记 T₀。
2. AC-1、AC-2（版本明细 / 元素矩阵 / 审核抽屉的涨跌显示），AC-5（查库）。
3. AC-6 + AC-7（打开 `PERF600-B00007` 审核抽屉，前后 md5 对比）。
4. AC-8 + AC-20（先开 `PERF600-B00432` 抽屉记 X，再单条重试失败明细；查 `QT-20260911-0842` 的两条版本记录）。
5. AC-9 + AC-11 + AC-16（批量通过 7 个料号，过程中截进度抽屉；前后 md5）。
6. AC-10（批量通过 3 个 `PERFHOT-*`）。
7. AC-23（合并重启后 10 分钟内 `V26091802` 已处理料号数的增量；日志无「预算试算超时」）。
8. AC-26：开发库若出现真实「预算失败」料号（如补跑中超时），在真实页面悬停核对；否则以 S-UI 结果为准，闸门 B 如实说明。
9. **AC-21** 需要在批次执行中重启 8081 —— 属 §3.2「重置共享 dev server 状态」，**届时单独向用户申请批准**（报影响面：当时在跑的请求与批次），未获批则只以 S-BE 的测试结果为准并在闸门 B 如实说明。
