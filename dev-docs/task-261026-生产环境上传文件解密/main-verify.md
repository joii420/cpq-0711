# 主线亲验记录

- 亲验日期：2026-10-26（America/Los_Angeles）
- 亲验基线：`50eadb7b`（实现提交 `be663211`）
- 环境：任务 worktree；后端测试库 `cpq_db_test`；后端/加解密 fake/Vite 分别使用隔离端口 `18472/19522/15192`

## 结果

1. 后端生产契约与 20 个上传/导入操作矩阵：`7 tests`，0 failure，0 error，0 skipped，`BUILD SUCCESS`。证据：`证据/主线亲验/backend/contract-and-20-operations.log`。
2. 后端生产包：跳过测试后重新完整构建，`BUILD SUCCESS`。证据：`证据/主线亲验/backend/package.log`。
3. 冷启动：完成非生产环境零外呼、生产默认配置启动、JVM `-Dfile.encryption.server` 覆盖、明文仅 inspect、密文 inspect→decrypt、上游错误脱敏校验；结果文件为 `证据/主线亲验/cold-start/20261027T064756Z/result.txt`。
4. 浏览器端到端：加密组件 Bundle 的预览、切换冲突策略后重新预览、提交、关闭/重开/刷新闭环 `1/1` 通过（13.2s）。浏览器发出 3 次 multipart；fake 捕获 3 组 `/v1/inspect`、`/v1/decrypt`，同组 requestId 相同且鉴权全部匹配。证据：`证据/主线亲验/S-UI/playwright.log`、`AC-4-request-summary.json`、`AC-4-trace.zip`。
5. 前端生产构建：TypeScript 与 Vite 构建通过，3439 modules transformed。证据：`证据/主线亲验/S-UI/frontend-build.log`。
6. 人工查看 `证据/主线亲验/S-UI/AC-4-final-component.png`，页面中测试目录展开并显示解密后导入的组件；随后 E2E finally 清理成功。
7. 清理核验：`component=0`、`component_directory=0`，本轮使用的 5 个隔离端口均无监听。证据：`证据/主线亲验/database-residual.txt`、`runtime-residual.txt`。

## 合并后主线复验

- 复验日期：2026-10-27（America/Los_Angeles）
- 复验基线：`1c71348c`（任务已快进合并到 `master`）
- 环境：主线工作区；后端测试库 `cpq_db_test`；最终 UI 栈使用隔离端口 `18476/19526/15196`

1. 后端全部 18 个任务测试类：`29 tests`，0 failure，0 error，0 skipped，`BUILD SUCCESS`。证据：`证据/主线合并后/backend/maven.log`。
2. 后端生产包：`mvn -DskipTests package` 通过。证据：`证据/主线合并后/backend/package.log`。
3. 前端生产构建：`npm run build` 通过，3441 modules transformed。证据：`证据/主线合并后/frontend/build.log`。
4. 合并后冷启动：非生产零外呼、生产默认配置、JVM override、明文 inspect、密文 inspect→decrypt 及错误脱敏全部通过。证据：`证据/主线合并后/cold-start/20261027T065656Z/result.txt`。
5. 最终主线 UI：`1/1` 通过（20.2s）；3 次浏览器 multipart 请求对应 3 组严格的 `/v1/inspect`→`/v1/decrypt`，6 次上游请求的 Bearer 鉴权全部匹配，关闭、重开与刷新后组件仍可见。证据：`证据/主线合并后/S-UI/root-final/`。
6. 主线首次 UI 复验暴露测试预热依赖另一未提交 UI 改动中被挤压的搜索框，生产功能尚未执行；随后将预热改为标题可见校验，并兼容目录/分组折叠态。中间失败和修复后通过证据保留在`证据/主线合并后/S-UI/`。
7. N+1 复核：新增文件名编码循环仅遍历字节；各上传入口不在循环内增加仓储查询；模型双文件回滚最多两个文件且不访问数据库，未引入 N+1。
8. 清理核验：`T261026-CRYPTO-` 全库文本列扫描命中 0；端口 `18476/19526/15196`、本轮进程与临时文件残留均为 0。证据：`证据/主线合并后/S-UI/root-final/database-prefix-scan.log`、`runtime-residual-scan.log`。

## 边界

- 未连接真实加解密服务 `http://172.16.18.45:9502`，真实网络连通性和真实密文兼容性仍需部署环境联调；本轮使用本机可控 fake 验证协议与调用顺序。
- 未修改开发库，未生成或更新 `deploy/` 部署包。

已自检：主代理已独立完成后端契约、20 操作矩阵、生产包、冷启动、浏览器端到端、前端生产构建、页面截图检查、N+1 复核、数据库残留与端口残留检查；合并到 `master` 后再次完成 29/29 后端测试、前后端构建、冷启动和 1/1 UI 闭环，结果均符合本任务验收口径。
