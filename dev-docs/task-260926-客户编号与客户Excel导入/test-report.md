# 测试报告 · 客户编号与客户 Excel 导入

> 执行日期：2026-09-27  
> worktree：`task-260926-customer-code-import`  
> 测试库：`cpq_db_test`（仅使用本任务私有前缀，未清库、未删除共享数据）

## 结果摘要

| 分片 | 结果 | 证据 |
|---|---|---|
| S-API-1 | **7/7 PASS** | `CustomerCodeApiTest`：AC-1~AC-6、AC-15 |
| S-API-2 | **AC-7~AC-14 全部 PASS** | `证据/测试/S-API-2/contract_runner.py`，run-id `r4` |
| S-回归 | 编译与静态检查 PASS；旧 API 集成/E2E BLOCKED | 本地无长期服务；旧测试夹具会删除共享数据 |

## 可复核命令

```bash
./mvnw -q -DskipTests compile
./mvnw -q -DskipTests test-compile
./mvnw -q -Dcpq.security.rbac.enabled=false -Dtest=CustomerCodeApiTest test
NODE_PATH=/home/joii/project/cpq/cpq-frontend/node_modules \
  node /home/joii/project/cpq/cpq-frontend/node_modules/typescript/bin/tsc \
  --noEmit -p cpq-frontend/tsconfig.json
```

Excel 黑盒测试在随机本地端口 `55410` 的 test profile 后端执行：

```bash
NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost \
python3 dev-docs/task-260926-客户编号与客户Excel导入/证据/测试/S-API-2/contract_runner.py \
  --fixtures /tmp/imp-260926-sapi2-r4 \
  --evidence-dir dev-docs/task-260926-客户编号与客户Excel导入/证据/测试/S-API-2 \
  --base-url http://127.0.0.1:55410/api/cpq \
  --token test-disabled-rbac
```

输出：AC-7 模板 15 列通过；AC-8 合法 2 行返回 2/2/2；AC-9 已有编号整份拒绝；AC-11 文件内重复报告第 2、3 行；AC-12 聚合 8 个错误且 0/0/0；AC-13 非 xlsx/损坏/错表头均 400；AC-14 空表头 0/0/0。

## 过程修正记录

1. 测试夹具改用 test 库真实有效主数据：行业 `0001`、产品分类 `000000`。
2. 修正导入第二阶段重复登记手填编号导致合法行误报冲突的问题。
3. 文件内重复同时报告首次行和当前行，满足 AC-11 的错误定位契约。
4. 测试夹具支持 run-id，避免重复执行时依赖清理数据库。

## 未执行/阻塞

- 未执行旧 `CustomerResourceTest` 集成类：其 `@BeforeEach` 会删除 `Test Customer%` 数据，不符合本任务共享库禁删约束。
- 未执行长期前端 dev server/E2E：本地没有 8080/8081/5173 服务；前端 TypeScript 检查已通过。
