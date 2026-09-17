# test · repair-260916 小计列无法选为字段引用

> 用例**只从 `问题说明.md` ⑥ 的 AC 原文派生**（`testing.md §1`）。
> 测试工程师**禁止读**：`cpq-frontend/src/pages/**`、`cpq-frontend/src/utils/**`、`cpq-frontend/src/components/**`、`cpq-backend/src/main/**`（`__fixtures__` 目录与 `api.md` §5 列出的函数签名除外）。信息不够就停下来报告缺什么。
> 可读：`问题说明.md`、`api.md`、本文件、`原型图/`、`证据/`、`docs/rules/`、`docs/E2E测试方法.md`、既有 E2E spec（`cpq-frontend/e2e/`）。

## §1 测试方案概述

| 层级 | 内容 | 片 |
|---|---|---|
| 纯计算（node 脚本 / vitest / 后端单测执行） | 真实数据夹具计算（AC-11）、计算规则不变（AC-12）、存量往返（AC-13） | S-A |
| E2E（Playwright，组件管理公式抽屉） | 点选、写法、颜色、报错、搜索、原型对齐（AC-1~AC-10） | S-B |
| 一次性库上的接口 + E2E | 前后端对拍与保存校验（AC-14）、存量迁移（AC-15）、旧包导入（AC-16）、结案全量（AC-17） | S-C |

AC-11⑤ 的真机部分由**主线亲验**（一次性库上），不派给测试片。

## §2 AC 可追溯矩阵

| AC | 覆盖它的测试 | 层级 | 分片 | 验收证据形式（归档到 `证据/测试/`） |
|---|---|---|---|---|
| AC-1 | T-B1 | E2E + 接口读 | S-B | 截图 + `GET /components/{id}` 原始 JSON |
| AC-2 | T-B2 | E2E + 接口读 | S-B | 截图 + 原始 JSON |
| AC-3 | T-B3（序列：保存→关→开→原样保存→刷新→开） | E2E + 接口读 | S-B | 每一步截图 + 两次保存的 JSON 与逐字段 diff 输出 |
| AC-4 | T-B4 | E2E | S-B | 截图 + 列表单元格文字 |
| AC-5 | T-B5a / b / c | E2E | S-B | 截图（红块 + 提示）+ 刷新后公式列表截图 |
| AC-6 | T-B6a / b / c / d | E2E + 接口读 | S-B | 截图 + b 的 JSON |
| AC-7 | T-B7 | E2E | S-B | 截图 |
| AC-8 | T-B8a / b | E2E | S-B | 悬停提示截图 + 保存结果截图 + b 的 JSON |
| AC-9 | T-B9 | E2E | S-B | 四次插入的文字输出 |
| AC-10 | T-B10 | E2E 截图 + 主线逐屏比对 | S-B | 抽屉整屏截图（状态 A / F 对应） |
| AC-18 | T-B11（Excel 组件抽屉：两列保存 + 重开） | E2E + 接口读 | S-B | 截图 + `excelColumns` 原始 JSON |
| AC-11 | T-A1（前端：node 脚本调用 `api.md` §5 函数，夹具 `证据/离线判决/`）+ T-A2（执行后端 B-6 测试类并摘录输出值） | 单元 | S-A | 脚本原始输出 + surefire 报告摘录；真机部分见主线亲验记录 |
| AC-12 | T-A3（两端 `cross-tab-cases.json` 测试执行 + `git diff --stat master -- <两引擎文件>` + 既有测试断言改动审阅） | 单元 + 仓库检查 | S-A | 命令原始输出 |
| AC-13 | T-A4（node 脚本：开发库 5 组件公式只读 + 共享后端 `tab-defs` 接口；本分支函数 vs master 函数） | 只读脚本 | S-A | 脚本输出（16 处逐项 + 其余 token 比对结果 + 实际数量） |
| AC-14 | T-C1（a~e：两端执行共享夹具测试 + `sha256sum`）+ T-C2（f：一次性库上的模板保存接口） | 单元 + 接口 | S-C | 命令输出 + 接口原始响应 |
| AC-15 | T-C3（①~④ 一次性库 SQL + 迁移重跑）+ T-C4（⑤ 临时前后端连一次性库的 E2E 截图；⑥ 接口） | 接口 + E2E | S-C | SQL 输出 + 截图 + 接口原始响应 |
| AC-16 | T-C5（一次性库导入 v1.0 / v1.1 → 导出 → 再导入；④ 与 master 的 A/B） | 接口 | S-C | 原始响应 + 库内文字查询输出 + A/B 对照表 |
| AC-17 | T-C6（结案前全量：`tsc -b`、本任务前端测试、本任务后端测试（一次性库）、S-B 新 spec 复跑、`tabjoin-formula-drawer.spec.ts` A/B、`quotation-flow.spec.ts` A/B） | 汇总 | S-C | 命令原始输出 + A/B 对照表 |

每条 AC 恰好属于一片（S-A：AC-11~13；S-B：AC-1~10、AC-18；S-C：AC-14~17）。

## §3 分片计划

| 片 | 写入面 | 档位 | 库 | 造数前缀 / 隔离 | 解锁条件 |
|---|---|---|---|---|---|
| **S-A** | 无（只读开发库 + 纯计算） | 只读片 | 读 `cpq_db_0724`（只 `SELECT`）；读共享后端 8081 的 `tab-defs` | 无造数；脚本与输出放 `证据/测试/S-A/` | 前端 F-1、F-2 与后端 B-6 均报完成 |
| **S-B** | 开发库里本片自建的一个组件目录及其组件 | 私有写片 | `cpq_db_0724`（经共享后端 8081；前端用 worktree 临时端口） | 目录名 `RP0916B-E2E`，组件名按 ⑥ 6.1（`RP0916宿主` 等，只在该目录内）；`finally` 删除本片建的目录与组件；**只断言本目录内对象** | 前端 F-1~F-5 报完成 |
| **S-C** | 一次性库（整库私有） | 私有写片（独立库） | `cpq_db_rp0916c`（主线开工时从 `cpq_db_0724` 克隆） | 导入目录 `RP0916C-导入v10` / `RP0916C-导入v11` / `RP0916C-再导入v12`；草稿模板 `RP0916C-模板校验`；临时前后端连一次性库 | 后端 B-1~B-5 与前端 F-6~F-8 均报完成；**含 Playwright 的步骤（T-C4、T-C6）须等 S-B 全部跑完** |

- **没有 `S-全局` 片**：本任务没有用例改用户启停、权限、发布态（S-C 的发布动作只发生在一次性库里）、系统开关。
- ⚠️ **Playwright 无跨进程互斥**（决策台账 `playwright.config.ts` 条）：S-B 与 S-C 的 Playwright 步骤**不得同时跑**；开跑前 `pgrep -f "node.*[p]laywright test"` 采样确认无其他进程，并记录采样时刻。
- ⚠️ 所有 E2E 的 `global-setup.ts` 都会写 `cpq_db_0724` 的 `user` 表（既有行为，不属本任务新增的全局写）。
- 🚫 共库片（S-A、S-B）不许写全局计数断言（`testing.md §4.5`）。

## §4 环境与自检口径

- 共享前端 5174 / 后端 8081 服务的是**主工作区代码**：
  - S-B：在 worktree 起**临时端口**的 vite（`/api` 代理到 8081；本片不依赖后端改动），Playwright 的 `baseURL` 指向临时端口；
  - S-C：在 worktree 起临时后端（临时端口，`DB_NAME=cpq_db_rp0916c`）+ 临时 vite（代理到该临时后端）。
  - 声明端口 5174 / 8081 留给主线，🚫 不要占用、不要重启。
- 🚫 不许在 `cpq_db_test` 或 `cpq_db_0724` 上启动含本分支迁移的后端或跑后端测试（`testing.md §4.3`）。后端测试一律 `DB_NAME=cpq_db_rp0916c`。
- 🚫 一次性库的 `DROP` 属红线，**不执行**，写进 `test-report.md` 的「待回收清单」。失败片的库保留。
- Playwright 选择器坑先读 memory 所记同类问题（antd v6 无 `.ant-drawer-content`、两字按钮带空格等，见 `docs/E2E测试方法.md` 与既有 spec 写法）。
- 断言前先断言「结果非空」并打印实际值（`testing.md §3`）；新写的守卫性断言做一次证伪实验（`§4.4`）。
- 证据产物**复制到** `证据/测试/<片>/` 并随任务提交；Playwright 默认输出目录会被下一轮清空，不算证据。

## §5 允许调整的既有断言清单

本返修改变「文字 → token」的对应关系，下列既有单测的**输入文字或期望回显文字**需要调整。允许的调整**只有两种**：
① 把输入文字里引用小计列的 `[页签.列]` 改为 `[页签.列(小计)]`；
② 把期望回显里 `component_subtotal` 对应的文字加上 `(小计)`。
**期望的 token 结构与数值一律不许改**；清单外的既有断言一条都不许改（AC-12③）。

| 文件 | 用例（以当前 master 为准） | 预计调整 |
|---|---|---|
| `cpq-frontend/src/pages/component/formulaSerialize.test.ts` | `[COMP_RL.金额] round-trips`（约 `:397`） | ①② |
| 同上 | `[COMP_RL.金额] + [COMP_INV.金额] → TWO component_subtotal tokens …`（约 `:610`） | ① |
| 同上 | `tokensToDrawerExpression renders component_subtotal using token-own fields even with EMPTY tabDefs`（约 `:650`） | ② |
| 同上 | 该文件中其他断言「行级函数表达式里不带后缀的小计列 → `component_subtotal`」的用例（如有） | ① |
| `cpq-frontend/src/pages/quotation/buildExcelSnapshot.test.ts` | `A 列 [来料.材料成本] → component_subtotal → componentSubtotals[来料#材料成本]=10（非 0）`（约 `:555`） | ①（期望值 `10` 不变） |
| 后端 `TabJoin*Test` | 预计无 | — |

实现工程师若发现清单外还有必须改的断言 → **停下报告主线**，由主线裁决是否补进本清单。

## §6 全局状态登记

| 片 | 会动的全局状态 | 还原方式 |
|---|---|---|
| S-A | 无 | — |
| S-B | 无（只建删本片目录与组件） | `finally` 删除 |
| S-C | 无（全部在一次性库内） | 一次性库整体进待回收清单 |

## §7 基线与 A/B

- `tabjoin-formula-drawer.spec.ts`：本分支与 master 各跑一次（同一环境、同一时段），对比通过/失败清单；新增失败需逐条归因。
- `quotation-flow.spec.ts`：按决策台账（`DEC-0008`）该 spec 在 master 上本就全红，只做 A/B 对照，不作通过要求。
- AC-16④：同一旧包分别在 master（临时后端连一次性库）与本分支导入，比对提示文字。
