# test-report · repair-260916 小计列无法选为字段引用

> 本文件目前只含 **S-C 片**（AC-14~17），S-A / S-B 部分由主线合并。
> 本次无接口契约变更，无需回写 `dev-docs/main-api.md`；导出包版本仍为 1.1（D-15），公式写法变化见 `api.md`。

## S-C（一次性库私有写片 · `cpq_db_rp0916d`）

- 执行窗口：2026-09-17T11:42:42Z ~ 12:27Z（UTC）；分支检查点 `1119e5d2`（分叉点 `2ccdc5d9`）；master 侧 `repair-260916-master-ab`（detached `d04ff40f`）
- 栈：分支栈 = 本 worktree 后端 8293 + vite 5293；master 栈 = master 树后端 8293 + vite 5293；两栈均连 `cpq_db_rp0916d`，未同时开
- 连库确权：后端以 `ApplicationName` 标记，`pg_stat_activity` 中该实例连接 6 条全部在 `cpq_db_rp0916d`（`out-stack/identity-*.txt`）；另比对报价单总数：5293/8293 = 199 = rp0916d，8081 = 202 = cpq_db_0724
- 证据根目录：`证据/测试/S-C/`（`out-C1`~`out-C6`、`out-stack`、`探测`、`SB复跑-branch`）

### 结果总表

| AC | 用例 | 结论 | 关键证据 |
|---|---|---|---|
| AC-14 a~e、g | T-C1 | ✅ 通过 | 夹具两份 sha256 均为 `4fc65244…a946d`（与主线告知一致）；独立重算核对 0 失败；前端 vitest 41/41，后端 `TabJoinExcelSharedFixtureTest` 15/15；14 条用例三方（后端 / 前端纯函数 / 前端 buildExcelSnapshot）数值全部相等 → `out-C1/两端对拍表.md` |
| AC-14 f | T-C2 | ✅ 通过 | 200 / 400（文案逐字相等）/ 200；对照组「两个裸明细跨行键」→ 400，证明第三步 200 有判别力 → `out-C2/*.json`、`out-C2/run.log` |
| AC-15 ① | T-C3 | ✅ 通过 | `git diff --name-only master...HEAD -- …/db/migration` 空；工作区无未提交/未跟踪迁移；阳性对照区间可输出 V445；库内 flyway 最高 445（前后两次） |
| AC-15 ② | T-C3 | ✅ 通过 | 一次性库 8 列文字 = 4.3 原文；before/after 整列对象逐字相同；开发库只读参照同样一致 → `out-C3/log-before.txt`、`log-after.txt` |
| AC-15 ③ | T-C4（spec `AC-15③`） | ✅ 通过 | 编辑页 Excel 视图 `1.978941064 / 0.317766357 / 1.804589425`；打开前后库存值不变 → `out-C4/AC-15③-*.png`、`AC-15③-表格文字.json` |
| AC-15 ④ | T-C4（spec `AC-15④`）+ T-C5 compare | ✅ 通过 | 两栈后端重算三列逐字相同：`0.003407173 / 0 / 39.547708538`（与③不同，属 D-13 / BL-0304 已知问题）→ `out-C4/AC-15④-*`、`out-C5/AB对照-AC15④-AC16④⑤.md` |
| AC-16 ①②③ | T-C5 run | ✅ 通过 | v1.1 导入 createdCount=10，ex1 三列与包内逐字相同；导出 bundleVersion=1.1、三列不变、10 个组件；再导入三列不变 → `out-C5/导入v1.1-*`、`导出-fromv1.1.json`、`再导入-*` |
| AC-16 ④ | T-C5 run / ab master / compare | ⚠️ 见说明 | 两栈都返回 400/400，都做了整包回滚（目录组件数 0→0）。报错文字**只差导入时自动生成的组件编号**（分支 `COMP-2649`，master `COMP-2664`）；把编号屏蔽后逐字相同。严格逐字比较不成立，请主线裁定 AC 口径 |
| AC-16 ⑤ | T-C4（spec `AC-16⑤`）两栈 | ✅ 通过 | 两栈一致：v1.0 出现「导入包是旧格式(bundleVersion 1.0)，不含取数配置器信息」，v1.1 无 → `out-C4/AC-16⑤-{branch,master}-{v1.0,v1.1}-导入预览.png` |
| AC-17 | T-C6 | ✅ 通过（附说明） | 见下 |

### AC-17 明细

| 项 | 命令 | 退出码 | 时间（UTC） | 结果 |
|---|---|---|---|---|
| tsc | `cd cpq-frontend && npx tsc -b` | 0 | 12:24:02~ | 0 错误（`out-C6/tsc.txt`） |
| 本任务前端测试（按分叉点识别 7 个文件） | `npx vitest run <7 files>` | 0 | ~12:24 | 7 files / 319 tests passed（`out-C6/vitest.txt`） |
| 本任务后端测试 | `DB_NAME=cpq_db_rp0916d ./mvnw test -Dtest=Repair260916H85FormulaTest,SafeArithmeticDecimalLiteralTest,TabJoinExcelSharedFixtureTest,TabJoinSubtotalSuffixTest` | 0 | ~12:24:29 结束 | 1+5+15+12=33，0 失败 0 跳过；surefire 报告为本轮新生成（`out-C6/mvn.txt`） |
| 无迁移 | 同 AC-15① | — | 12:24 / 12:26 | 通过 |
| 改动前端文件经临时 vite 取回 | curl 5293 | — | 12:25:22 | 6 个文件均 200（`out-C6/前端改动文件-200.txt`） |
| 后端业务端点 401 | curl 8293 | — | 11:43 | 分支后端 401，经 5293 代理也是 401（`out-stack/`） |
| S-B spec 复跑（分支栈） | `SB_CMD` | 0 | 11:56:50~12:02:08 | 19 passed；产物已复制到 `SB复跑-branch/`（61 个文件） |
| S-C spec | `npx playwright test -c e2e/repair260916-sc.config.ts` | 分支 0（第 2 轮）；master 0 | 12:06:20~12:07:00；12:16:24~12:16:44 | 分支 3 passed；master 2 passed、1 skipped（AC-15③ 只在分支跑） |
| tabjoin-formula-drawer A/B | 两栈各跑一次 | 1 / 1 | 12:07:21~12:08:59；12:16:44~12:18:21 | 两栈都是 2 failed，失败项与原因相同，无新增失败（见说明②） |
| quotation-flow A/B（仅对照） | 两栈各跑一次 | 1 / 1 | 12:08:59~12:11:56；12:18:21~12:21:16 | 两栈都是 4 failed，失败项与原因逐项相同（`out-C6/AB对照.md`） |
| 冷启动 | — | — | — | 未验证（由主线按 §5.1 判定） |

### 说明与未查明项

1. **AC-16④ 的逐字口径**：报错文字里带有导入时自动生成的组件编号，编号按全局序列分配，两次导入必然不同（S-B 复跑在两次导入之间也消耗了序号）。两栈状态码相同，屏蔽编号后文字逐字相同。是否按「屏蔽编号后逐字」判通过，请主线裁定。
2. **tabjoin-formula-drawer 的 A/B 没有判别力**：两栈都倒在同一处——「应能看到组件卡片 COMP-0088」，而 `cpq_db_rp0916d` 和 `cpq_db_0724` 里都没有 COMP-0088（已只读查询确认）。这个 spec 的断言在两栈上都没执行到，「无新增失败」只能说明两边一样红，**证明不了抽屉功能没有回归**。
3. **quotation-flow**：失败原因两栈相同：
   - 2 条「编辑态 Step1 下一步应可点」
   - 1 条 30s 超时
   - 1 条未设置 `PW_PRECISION_SEED_QUOTATION_NO`

   与 DEC-0008 的已知基线同型。它在一次性库新建了 4 张草稿单 `QT-20260917-0900~0903`。
4. **执行中的脚本问题（均已修正，照实记录）**：
   - **T-C2 证据写错目录（已归位）**：`lib.mjs` 用 URL 编码的路径定位证据目录，T-C2 的 5 个 json 起初写进了 `dev-docs/` 下一个 URL 编码名的目录。已改用 `fileURLToPath`，文件移回 `out-C2/`，误建的空目录已用 `rmdir` 删除。
   - **T-C5 run 跑了 3 次**：第 1 次路径错误，没造数；第 2 次因组件接口的 `excelColumns` 是 JSON 字符串而报错，此时已建 v10/v11 目录并导入了 v1.1；第 3 次用 `RESUME=1` 复用这两个目录续跑，v1.1 导入结论取第 2 次的原始提交响应（`导入v1.1-commit.json`）。
   - **S-C spec 分支栈跑了 2 轮**：
     - 第 1 轮 AC-16⑤ 在第二个包处 20s 内找不到前缀目录。两次探测都复现不出来，原因未查明。
     - 第 2 轮先等列表加载 3s，并加了诊断分支（诊断没被触发），通过。
     - 第 1 轮日志：`out-C6/sc-spec-branch-第1轮.log`。
   - **T-C3 after 第 1 次误报**：没限定组件范围，把 T-C5 导入的 ex1 副本（6 列）也算进去，得 14 列、判失败。已限定为 4.3 所列 3 个组件后重跑并通过。第 1 次日志：`out-C3/log-after-第1次-未限定组件.txt`。
   - **T-C1 的「连库确权」不适用**：`TabJoinExcelSharedFixtureTest` 是纯单元测试，不连库，所以连接采样为空，脚本据此判了 FAIL。这不是产品问题；该类的 surefire 结果为 15/15。
   - **stack.sh stop 第一次没停掉分支栈**：`setsid` 会再 fork 一次，脚本记录的 PID 不是进程组号，于是停止失败且没有报错。已按监听进程的进程组号（java 543207、vite 544175，均为本片 11:43Z 所起，工作目录在本 worktree）停止；stack.sh 已改为记录进程组号。master 栈用修正后的脚本正常停止。
   - **两次采样误报**：11:56 与 12:02 的 `pgrep -af "node.*[p]laywright test"` 匹配到本 shell 自身的命令行，列出的 PID 都是本 shell，并非其他 playwright 进程；之后改为只看 node 进程。
5. **环境副作用（照实记录）**：
   - `playwright.config.ts` 的 globalSetup 共执行 5 次（S-B 复跑 1 次、两栈 × 两个 A/B spec 共 4 次），每次都对 `cpq_db_0724` 的 user 表做既有的解锁 UPDATE。
   - 为核对 vite 代理指向，11:45Z 在 8081 用 admin 做过 1 次 API 登录。
   - 两棵树的 `cpq-frontend/e2e/.auth/*.json` 被 globalSetup 重写；master 树的 `e2e/test-results`、`e2e/report` 也被本次运行重写。
   - S-B 复跑覆盖了 `证据/测试/S-B/` 下的文件（工作区现为 M 状态），**恢复由主线做**。
6. **本片新增或改动的测试资产**：
   - `cpq-frontend/e2e/repair260916-excel-migration.spec.ts`、`repair260916-sc.config.ts`：testMatch 已扩为同时匹配探测 spec。
   - `cpq-frontend/e2e/repair260916-sc-probe.spec.ts`：只读探测，是否保留由主线定。
   - `证据/测试/S-C/` 下的脚本。

### 一次性库内造数（`cpq_db_rp0916d`，未清理）

- 目录 `RP0916C-导入v10`（0 个组件）、`RP0916C-导入v11`（10 个组件）、`RP0916C-再导入v12`（10 个组件）
- 草稿模板 `RP0916C-模板校验`：T-C2 结束时已删除，残留 0
- S-B 复跑造的目录 `RP0916B-E2E` 与 5 个组件：已由其清理逻辑删除（残留 0）
- quotation-flow 新建草稿单 `QT-20260917-0900`~`0903`
- admin 在本库的解锁 UPDATE（stack.sh，每次起后端 1 次，共 2 次）

### 待回收清单（不执行，等用户批准）

| 库名 | 创建时间 | 归属片 | 状态 | 回收命令 |
|---|---|---|---|---|
| `cpq_db_rp0916c` | 2026-09-17T06:37Z | S-C（D-15 前） | 已停用，可回收 | `DROP DATABASE cpq_db_rp0916c;` |
| `cpq_db_rp0916d` | 2026-09-17（D-15 后，主线克隆） | S-C | 已跑完；AC-16④ 口径待主线裁定，裁定前建议保留 | `DROP DATABASE cpq_db_rp0916d;` |

S-C 已结束，两栈已停，8293/5293 已释放（12:25:26Z 采样）。
