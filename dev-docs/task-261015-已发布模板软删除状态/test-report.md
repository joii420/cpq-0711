# 测试执行报告

## 验收状态

- 用户验收通过（2026-10-15）；任务已合并 master `0c78a2c8` 并交付。

## 执行环境

- 后端测试 profile：`cpq_db_test`（专用测试库）；未使用 `DB_NAME` 覆盖到共享开发库。
- 迁移：由 Quarkus/Flyway 启动自动执行，日志确认 schema version `451`，未手工执行 SQL。
- 前端：worktree 内 Vite/TypeScript 构建与 Vitest。
- 测试夹具：后端使用 `TMPLDEL-261015-S1-` 前缀、事务回滚；未清理或改写共享业务数据。

## 结果

| 分片/命令 | 结果 | 证据 |
|---|---|---|
| 后端编译 `./mvnw -q -DskipTests compile` | 通过 | Maven 退出码 0 |
| S1/S2 `./mvnw -q -Dtest=TemplateSoftDeleteContractTest test` | 通过 | `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`；覆盖 PUBLISHED→DELETED、重复删除、默认/显式列表、冻结快照读取、新 DRAFT、历史 DRAFT 保存、新引用拒绝 |
| 前端 `npm run build` | 通过 | `tsc -b` 与 `vite build` 完成；仅保留既有 chunk size warning |
| 前端 `npm test -- --run` | 通过 | `Test Files 113 passed (113)`；`Tests 1448 passed (1448)` |
| 前端软删除契约测试 | 通过 | 新增 4 条状态/权限动作断言包含在上述全量结果内 |
| `git diff --check` | 通过 | 无空白错误 |
| `.claude/hooks/check-decisions.sh` | 通过 | 16 条生效决策，STALE 0 |
| `.claude/hooks/check-selfcheck.sh` / `guard-redline.sh` | 通过 | 无违规输出 |

## AC 对照

- AC-1/4/6/7/8/10：后端事务契约测试与冻结读取测试覆盖；删除只更新状态，快照/引用保留。
- AC-2/11：报价新建、切换、复制和产品模板绑定代码路径统一只接受 `PUBLISHED`；客户匹配/自动默认仍只选 `PUBLISHED`。
- AC-3/9/12：后端默认列表与 SYSTEM_ADMIN 历史查询门禁、前端状态/按钮/只读告警和新模板入口已实现；前端契约测试覆盖状态动作谓词。

## 未执行项与边界

- 未在共享开发库执行删除或手工迁移；本报告未执行真实浏览器登录 E2E，用户已在已合并主线、真实角色会话完成列表筛选、详情只读和创建新模板流程验收并确认通过。
- 既有 `TemplateResourceTest` 在无登录会话下的 baseline 401 不作为本任务失败证据；本任务未改变该鉴权前置条件。

