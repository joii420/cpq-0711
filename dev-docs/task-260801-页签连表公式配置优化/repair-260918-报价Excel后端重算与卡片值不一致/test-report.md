# test-report · repair-260918 报价 Excel 视图后端重算改读卡片值

> 汇总各测试片回报（原文见 `证据/回报-S1/S2/S3-260918.md`、`证据/回报-后端-260918.md`）与主线亲验（`证据/亲验/亲验记录.md`）。
> 被测：分支 `fix/repair-260918-quote-excel-card-source`（基于 master `ca950dfc`），运行包 `cpq-backend/target/quarkus-app/`（2026-09-18 22:15:29 PDT 构建，测试全程未替换）；库 `cpq_db_0724`（接口 / 界面）、`cpq_db_test`（后端测试类）。

## 1. 逐条 AC 结果

| AC | 片 | 测试结果 | 主线亲验 | 关键实测 |
|---|---|---|---|---|
| AC-1 | S1 T1.1 | ✅ | ✅ | 0881 本分支 `1.978941064 / 0.317766357 / 1.804589425` = 判定；master `0.003407173 / 0 / 39.547708538` |
| AC-2 | S1 T1.2 | ✅ | ✅ | 135 格后端 = 判定 135，null 0 格；E-5 `0872 col_2 = 0`；E-6 八格 = 判定（非存值 0）；0864 缺页签 8 格返回 `0`（D-6） |
| AC-3 | S1 T1.3 | ✅ | ✅ | `COMP-2269`/`COMP-2509` 75 格本分支与 master 逐字相同 |
| AC-4 | S2 T2.1 | ✅ | ✅ | 卡片值 NULL / 失败标记（及补测 `{"tabs":[]}`、`{}`、真实 tabs + 失败标记）→ 三列 `null`；试算 `value=null` + 固定文案；多行单只第 2 行空、其余 = 判定；「空白串」「JSON 解析失败」jsonb 列无法构造，由后端单测 `CardValuesUsableTest` 覆盖 |
| AC-5 | S2 T2.2 | ✅ | ✅ | 存值空时导出 = 判定（差 0）；master 导出草稿纸值；卡片值也空 → 单元格空 |
| AC-6 | S1 T1.4 | ✅ | ✅ | 三个试算入口 = 判定；②③ `errors=[]`，① 响应无该字段（D-6） |
| AC-7 | S1 T1.5 | ✅ | ✅（master 对照） | 本分支 1 行 / 4 行均 6 条 SQL；master 1 行 6 条、4 行 12 条（Hibernate SQL 日志口径） |
| AC-8 | S2 T2.3 | ✅（D-5 口径，11/11） | ✅ | V1 `…/1.705315785` → 改加工费 50 → V2 `…/1.715775438`（= 判定、col_3 上升、col_1/col_2 不变）；失效 `null`；恢复 = 判定。原文口径另跑一轮：2 条因基准失真判红，与 D-5 所述一致 |
| AC-9 | S3 T3.1 | ✅（证伪轮按预期硬失败） | ✅ | 页面三次（首次 / 切回再切 / 刷新后）均 `1.978941064 / 0.317766357 / 1.804589425`；非 GET 仅登录；前后库值逐字相同 |
| AC-10 | S1 T1.6 | ✅ | ✅（读 diff） | 三个核价测试类 1/1、1/1、6/6 通过，运行数与 master 基线相同；核价分支代码未改。`GetExcelViewCostingIT` 夹具补了核价模板 id（断言未改，master / 本分支 A/B 均通过） |
| AC-11 | S1 T1.7 | ✅ | ✅ | 固定清单 md5 前后均 `67839fdf3a9adb20a631c9c1529e8af2`（49 行） |
| AC-12 | S2 T2.4 | ✅ | ✅ | 卡片值与存值都空 → 补算后存值仍 NULL；master 写入草稿纸值；先补卡片值再补存值 → 存值 = 判定 |
| AC-13 | S1 T1.8 + 主线 | ✅ | ✅ | 无迁移、前端 `src/` 零改动；启动 ERROR 0；401 探活；N+1 自检声明 9 处循环；主线亲跑 8 个测试类 24/24 通过 |

**覆盖缺口**：无。

## 2. 开发期裁决与口径变更（均经用户确认）

- `D-5` AC-8 基准改为「置空补算后作 V1」；`D-6` AC-6 只对 ②③ 查 `errors`、AC-2 缺页签格期望 `0`；`D-7` 模板快照为空时照样求值不做换算（附带 409 → 200）。已同步 `问题说明.md` / `api.md` / `backtask.md` / `test.md`。受影响的已完成片无需重跑（S1 本就按澄清口径判定；S2 的 D-5 口径轮已跑）。
- 派工单里临时后端启动命令写错（`-D` 须在 `-jar` 前），后端工程师发现，已更正并通知各片。

## 3. 既有问题（master 上同样存在，非本次引入）

- 后端 B-7 基线：`QuotationOutputResourceTest` 9 个 401、`CardSnapshotAmountTotalTest` 1 个、`QuotePendingScopeOpenWhitelistTest` 2 个，master / 本分支结果相同。
- 导出 Excel 视图的表头是 `col_key` 而非列标题、数据单元格为字符串（改前即如此；该接口前端无入口）。
- S2 运行期间日志出现一行 `ERROR Log4j API could not find a logging provider.`（导出 xlsx 时，Apache POI 所用 log4j 无实现），导出结果正常，未查成因。
- 后端回报 §5 列出的既有 N+1 隐患（`ensureExcelValuesBatch` 预取缺空列表、模板公式逐行取等），本任务未动，待用户决定是否登记。

## 4. 待回收清单

| 对象 | 状态 |
|---|---|
| 一次性库 | **无**（本任务未建） |
| 测试复制单（S2 共 14 张、主线亲验 1 张） | 均已 `DELETE`，按 id 与名称前缀 `RP0918-` 复查计数为 0 |
| 临时服务（8318 / 8319 / 8328 / 8338 / 5338 / 8091 / 5090） | 均已停止 |
| 主线 A/B 用只读副本 `.claude/worktrees/rp0918-master-ab`（detached `ca950dfc`） | 已 `git worktree remove`（2026-09-18 23:0x） |
| 特性 worktree `.claude/worktrees/repair-260918-quote-excel-card-source` | 已 `git worktree remove`；其中被 `.gitignore` 忽略的 30 份 `.log` 证据已先复制回主工作区并强制提交 |
| 特性分支 `fix/repair-260918-quote-excel-card-source`（tip `244a9d67`，已并入 master） | **待用户执行 `git branch -d fix/repair-260918-quote-excel-card-source`** |

## 5. 契约回写

`dev-docs/main-api.md` 已回写 6 个端点的取数口径与新错误文案（来源标记 `repair-260918`，2026-09-18）。
