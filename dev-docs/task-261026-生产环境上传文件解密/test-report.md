# 生产环境上传文件解密测试报告

## 1. 结论

- 测试日期：2026-10-26～2026-10-27（America/Los_Angeles）。
- 代码头部基线：`be663211`，分支 `feat/task-261026-file-decryption`，独立 worktree 执行；本轮测试设施修正保留为未提交工作树变更。
- 结论：AC-1～AC-13 自动化验证通过；S-CONTRACT、S-PRIVATE、S-UI、冷启动、最终组合回归以及 R1～R5 证伪均达到 `test.md` 要求。
- 冷启动：已跑 ✅。JVM `-Dfile.encryption.server` 覆盖、默认配置启动、非生产零外呼、生产明文仅 inspect、生产密文 inspect→decrypt 均已在独立进程验证。
- 最终后端组合回归：29 tests，0 failure，0 error，0 skipped，`BUILD SUCCESS`。
- 前端 E2E：1 test passed；R4 恢复后再次独立执行，1 test passed，并重新生成成功 trace、截图和请求摘要。
- 静态直接断言调用点：后端 154、Playwright 39，合计 193。该数字不含循环产生的运行时重复断言；20 入口逐行断言实际会执行多次。
- 未发现业务实现缺陷；执行中出现的红灯均为测试夹具、测试传输或测试证据设施问题，修正后全量复验通过。
- 真实服务 `http://172.16.18.45:9502` 的连通性及真实天锐密文解密结果：**未验证**。本轮只连接本机可控 fake service，未向真实地址发请求。
- 本轮未部署、未生成部署包、未修改或连接开发库；数据库仅使用 `cpq_db_test`。

## 2. 执行命令与原始结果

所有 Maven 命令都在 `cpq-backend/` 串行执行，未并发写 `target/`。以下类名均为显式点名，不是空跑。

### 2.1 S-CONTRACT

```bash
DB_NAME=cpq_db_test ./mvnw -B \
  -Dtest=Task261026TestProfilePassThroughTest,Task261026DevProfilePassThroughTest,Task261026CustomProfilePassThroughTest,Task261026ProdCryptoContractTest,Task261026ProdUnauthorizedTest,Task261026UploadSurfaceGuardTest,Task261026TwentyOperationRuntimeTest \
  test -l ../dev-docs/task-261026-生产环境上传文件解密/证据/测试/S-CONTRACT/run-20261026-3/maven.log
```

最终结果：14 tests，0 failure，0 error，0 skipped，`BUILD SUCCESS`，1:06。

原始证据：

- `证据/测试/S-CONTRACT/run-20261026-3/maven.log`
- 前两次红灯保留在 `run-20261026-1/`、`run-20261026-2/`，未覆盖最终证据。

### 2.2 S-PRIVATE

```bash
DB_NAME=cpq_db_test ./mvnw -B \
  -Dtest=Task261026CustomerEncryptedImportTest,Task261026CustomerPlainImportDevTest,Task261026EncryptedDatasetImportTest,Task261026ComponentBundleContractTest,Task261026MissingTokenFailureTest,Task261026InvalidServerFailureTest,Task261026ConnectionFailureTest,Task261026TimeoutFailureTest,Task261026UpstreamWriteFailureTest,Task261026HttpSizeBoundaryTest,Task261026ModelAtomicityTest \
  test -l ../dev-docs/task-261026-生产环境上传文件解密/证据/测试/S-PRIVATE/run-20261026-5-final/maven.log
```

最终结果：15 tests，0 failure，0 error，0 skipped，`BUILD SUCCESS`，2:29。11 个点名类均有实际测试；Surefire XML 已归档。

原始证据：

- `证据/测试/S-PRIVATE/run-20261026-5-final/maven.log`
- `证据/测试/S-PRIVATE/run-20261026-5-final/surefire-reports/`
- 前四轮和定向复跑保留在 `run-20261026-1/`～`run-20261026-4-size-targeted/`。

### 2.3 可运行包与冷启动

```bash
DB_NAME=cpq_db_test ./mvnw -B package -DskipTests \
  -l ../dev-docs/task-261026-生产环境上传文件解密/证据/测试/S-CONTRACT/package-runner/maven.log
```

结果：`BUILD SUCCESS`，20.000s。

```bash
T261026_BACKEND_PORT=18451 \
T261026_FAKE_PORT=19502 \
T261026_DB_NAME=cpq_db_test \
T261026_JDBC_URL='jdbc:postgresql://10.177.152.12:5432/cpq_db_test' \
T261026_DB_USERNAME='<test-user>' \
T261026_DB_PASSWORD='<redacted>' \
T261026_EVIDENCE_DIR='../dev-docs/task-261026-生产环境上传文件解密/证据/测试/S-CONTRACT/cold-start' \
bash src/test/scripts/task261026-file-crypto-cold-start.sh
```

结果：退出码 0，`AC-1/2/6/12/13 cold-start harness completed`。账号口令不进入报告或普通日志。

核心观测：

- 非生产：观测器阳性对照先捕获 1 次 `/v1/inspect`，复位后应用上传外呼严格为 0。
- 生产默认配置：完成独立冷启动，不向默认真实地址发送上传请求。
- 生产 JVM override：实际参数为 `http://127.0.0.1:19502/`；明文请求恰为 1 次 `/v1/inspect`、0 次 decrypt；密文请求顺序恰为 `/v1/inspect`、`/v1/decrypt`，没有重复 path。
- 密文两次请求的原 body 均为 32 bytes，SHA-256 均为 `76dd1113076c833a31aed6d9ae4d914a3b70e5fbdb21cddea81c7be7d2e41368`，文件名为 `override%20encrypted.xlsx`，Bearer 匹配且 requestId 相同。
- 非生产明文与生产 `inspect=false` 的 HTTP code、响应 shape、表头关键值完全一致；两者均进入真实 Excel 解析器并返回非空数据。
- 日志哨兵阳性对照能命中；应用日志和错误响应对 token、Authorization、原密文、解密正文、上游错误正文均为 0 命中。

原始证据：`证据/测试/S-CONTRACT/cold-start/20261027T061333Z/`。

### 2.4 S-UI

独立栈：fake `19505`、backend `18454`、Vite `15177`；Vite 预热后执行：

```bash
PW_BASE_URL=http://127.0.0.1:15177 \
PW_BACKEND_URL=http://127.0.0.1:18454 \
FILE_CRYPTO_FAKE_URL=http://127.0.0.1:19505 \
PW_EVIDENCE_DIR='../dev-docs/task-261026-生产环境上传文件解密/证据/测试/S-UI/run-20261026-3' \
PW_OUTPUT_DIR='../dev-docs/task-261026-生产环境上传文件解密/证据/测试/S-UI/run-20261026-3/playwright-output' \
npx playwright test --config=e2e/task261026-file-crypto.config.ts
```

结果：1 passed，50.0s，自然退出。三次浏览器请求均为 multipart：preview(SKIP)、preview(RENAME)、commit(RENAME)；fake 共捕获 6 次上游请求，严格为三组 inspect→decrypt。三组原 body 均为 45 bytes 且 SHA-256 相同，文件名、Bearer、组内 requestId 均正确。提交后组件 API 中唯一 code 恰为 1 条，关闭、切页、刷新并展开私有目录后仍可见。

原始证据：

- `证据/测试/S-UI/run-20261026-3/playwright.log`
- `证据/测试/S-UI/run-20261026-3/AC-4-trace.zip`
- `证据/测试/S-UI/run-20261026-3/AC-4-final-component.png`
- `证据/测试/S-UI/run-20261026-3/AC-4-request-summary.json`

### 2.5 最终组合回归

```bash
DB_NAME=cpq_db_test ./mvnw -B \
  -Dtest=Task261026EncryptedDatasetImportTest,Task261026ComponentBundleContractTest,Task261026ConnectionFailureTest,Task261026CustomProfilePassThroughTest,Task261026CustomerEncryptedImportTest,Task261026CustomerPlainImportDevTest,Task261026DevProfilePassThroughTest,Task261026HttpSizeBoundaryTest,Task261026InvalidServerFailureTest,Task261026MissingTokenFailureTest,Task261026ModelAtomicityTest,Task261026ProdCryptoContractTest,Task261026ProdUnauthorizedTest,Task261026TestProfilePassThroughTest,Task261026TimeoutFailureTest,Task261026TwentyOperationRuntimeTest,Task261026UploadSurfaceGuardTest,Task261026UpstreamWriteFailureTest \
  test -l ../dev-docs/task-261026-生产环境上传文件解密/证据/测试/final-backend-regression/run-2/maven.log
```

结果：18 个点名类，29 tests，0 failure，0 error，0 skipped，`BUILD SUCCESS`，3:25。原始日志及 18 份 Surefire XML 位于 `证据/测试/final-backend-regression/run-2/`。

## 3. AC 结果与关键断言

| AC | 结果 | 关键验证 |
|---|---|---|
| AC-1 | 通过 | `test`、`dev`、自定义非 prod profile 外呼严格为 0；同一 fake 的生产阳性对照证明观测有效。 |
| AC-2 | 通过 | prod 明文只发 1 次 inspect，decrypt 为 0；冷启动结果相同。 |
| AC-3 | 通过 | 加密客户 Excel、加密 dataset Excel 均先 inspect→decrypt，再产生非空真实业务写入；只落明文业务值并精确清理。 |
| AC-4 | 通过 | Bundle preview(SKIP)→策略变化后 preview(RENAME)→commit→关闭/切页/刷新；两个 preview 响应分别返回当次策略，唯一组件计数为 1。 |
| AC-5 | 通过 | GLB 与 thumbnail 分别 inspect→decrypt，两个文件位 requestId 独立；下载字节、明文 size/MD5 正确；第二文件失败时数据库版本和存储文件均不新增。 |
| AC-6 | 通过 | 默认 `file.encryption.server=http://172.16.18.45:9502` 静态守卫通过；JVM override 冷启动命中本地 fake，尾斜杠规范化且无重复 path。 |
| AC-7 | 通过 | 原始 body size/SHA、`application/octet-stream`、Bearer、中文加空格文件名 `%E7...%20014...xlsx`、inspect/decrypt 共用 requestId 均通过。 |
| AC-8 | 通过 | `isEncrypted=true` 兼容并解密；`encrypted=false` 只 inspect；字段缺失和畸形 JSON 失败关闭。 |
| AC-9 | 通过 | token 缺失、非法 server、连接失败、约 30s 超时、4xx/5xx、非法响应全部失败关闭；写库入口成功记录不新增，模型文件/版本无残留。 |
| AC-10 | 通过 | 空文件在 inspect 前返回 400 且 fake 计数为 0；104,792,064-byte 限制内文件完整进入 inspect/decrypt；104,857,601-byte 文件由 HTTP 层精确返回 413，fake 计数为 0。 |
| AC-11 | 通过 | 先断言矩阵精确 20 行，再逐个运行 20 个活跃操作并验证各文件位 inspect/decrypt；模型两个文件位 requestId 不同；retired 入口返回 410 且无外呼。 |
| AC-12 | 通过 | fake 记录脱敏；日志扫描阳性对照命中，应用日志/客户端错误信封对六类敏感哨兵 0 命中。 |
| AC-13 | 通过 | 非 prod 与 prod `inspect=false` 的代表性明文 A/B 响应 shape/关键值一致；真实客户导入非空；组件 JSON body 兼容与无权请求零外呼通过。 |

断言计数证据：`证据/测试/cleanup/assertion-site-count.log`。

## 4. 首轮红灯与测试设施修正

这些红灯均保留原始日志，未覆盖；修正只发生在测试代码、fixture 或测试脚本，没有据此降低 AC，也没有修改生产实现。

1. S-CONTRACT run-1：RestAssured multipart 将中文文件名替换成 `?`；`test` profile 前置认证导致 401；结构守卫的方法截取没有覆盖复杂注解，前端扫描误读 PNG。改用测试侧原始 UTF-8 multipart、显式 test profile、结构化候选方法扫描和文本扩展名白名单。run-2 仅剩全限定 `@jakarta.ws.rs.Path` 未识别，补齐后 run-3 14/14 通过。
2. S-PRIVATE run-1：既有 dataset 模板新增 `计价单位` 且类型值域变化，最小 Excel fixture 不再合法；大文件首版由 RestAssured 整体载入并在 413 时出现 broken pipe。fixture 按当前公开模板生成最小合法数据，大文件改为稀疏临时文件和 HTTP/1.1 `Expect: 100-continue` 流式发送；最终 15/15 通过。
3. S-UI run-1：Chromium 对流式 multipart 不提供 `postDataBuffer()`，但 fake 已捕获原始字节；run-2 刷新后私有目录默认折叠，节点存在但 hidden；全程 trace 使失败 teardown 悬挂。改为浏览器侧断言 multipart URL/content-type、fake 侧断言原始字节，刷新后显式展开目录，并在测试体内手工 start/stop trace；run-3 1/1 通过且自然退出。
4. 最终后端组合首轮：业务服务约 30s 超时正确，但测试客户端也在 30s 先抛 `SocketTimeoutException`，导致无法读取后端失败信封。仅将该测试客户端 read timeout 调整为 45s，未改变生产 timeout；定向复验和组合 run-2 均通过。

## 5. R1～R5 证伪

| 实验 | 变异已生效与预期红 | 恢复后复验 |
|---|---|---|
| R1 非 prod 进入 inspect | 非 prod 零外呼断言红：expected 0，actual 1。 | 绝对路径恢复；同用例 1/1、BUILD SUCCESS。 |
| R2 `isEncrypted=true` 当明文 | 旧字段用例红：预期 200，实际密文被解析并返回 400。 | 绝对路径恢复；同用例 1/1、BUILD SUCCESS。 |
| R3 移除一个入口统一处理 | 20 入口矩阵第 01 行红：inspect expected 1，actual 0。 | 绝对路径恢复；完整矩阵 1/1、BUILD SUCCESS。 |
| R4 组件 preview 改 JSON | 网络断言精确红：期望 `multipart/form-data; boundary=`，实际 `application/json`。 | 绝对路径恢复；独立栈重新执行完整 S-UI，1 passed (13.1s)，并重新生成成功 trace/截图/摘要。 |
| R5 日志写入哨兵 | 专用阳性对照命中并以非零门禁结果证明会报红。 | 同一搜索在应用日志和错误响应为 0 命中。 |

证据分别位于 `证据/测试/R1/`～`证据/测试/R5/`。R4 恢复后证据为 `R4/restored-green/playwright.log`、`AC-4-trace.zip`、`AC-4-final-component.png`、`AC-4-request-summary.json`；不是用变异前结果替代恢复复验。

## 6. 隔离、清理与待验证项

- 数据库：只使用 `cpq_db_test`。收尾以只读事务扫描 `public` schema 的 text/varchar/char/json/jsonb 列，`T261026-CRYPTO-` 命中总数为 0。证据：`证据/测试/cleanup/database-prefix-scan.log`。
- 进程/端口：独立端口 `18451`～`18455`、`19502`～`19506`、`15175`～`15178` 均无监听；本 worktree 无测试进程。证据：`证据/测试/cleanup/runtime-residual-scan.log`。
- 临时文件：`/tmp/T261026-CRYPTO-COLD-*`、`/tmp/T261026-CRYPTO-MODEL-*`、`/tmp/T261026-CRYPTO-SIZE-*` 均为 0。
- 变异恢复：`cpq-backend/src/main` 与 `cpq-frontend/src` 无 diff；`git diff --check` 通过。证据：`证据/测试/cleanup/worktree-selfcheck.log`。
- 无恢复库、无残留测试数据、无临时进程、无待回收项。
- 真实 `http://172.16.18.45:9502` 仍未联调；真实服务鉴权、网络 ACL、真实密文算法兼容性应在具备受控凭证与网络条件后单独验收。

## 7. 自检声明

已自检：13 条 AC 均有可复核的正常证据；R1～R5 均先证明变异生效、取得预期红灯、绝对路径恢复并复验为绿；最终组合不是空跑；测试数据、文件、进程和端口已核验无残留；收尾时没有保留生产代码变更，没有修改开发库或 `deploy/`，没有执行提交。
