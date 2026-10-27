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

## 边界

- 未连接真实加解密服务 `http://172.16.18.45:9502`，真实网络连通性和真实密文兼容性仍需部署环境联调；本轮使用本机可控 fake 验证协议与调用顺序。
- 未修改开发库，未生成或更新 `deploy/` 部署包。

已自检：主代理已独立完成后端契约、20 操作矩阵、生产包、冷启动、浏览器端到端、前端生产构建、页面截图检查、数据库残留与端口残留检查，结果均符合本任务验收口径。
