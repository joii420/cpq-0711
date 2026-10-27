# 前端任务分解

> 前端仅改组件 Bundle 导入的文件持有和 HTTP 请求体。加解密判断、令牌和外部服务调用全部属于后端。
> 实现必须同时阅读 `需求文档.md` 的 AC 原文和 `api.md § 5`；有冲突以 AC 原文为准并报主线。

## 原型判定

本任务不新增页面、抽屉、控件、工具栏动作，不改布局、控件顺序和用户主流程；只把同一个“选文件 → 预览 → 提交”流程的数据传输从 JSON body 改为 multipart。属于 `frontend.md § 1.3` 的纯逻辑／取数改动，**不产出 HTML 原型**。

## 任务清单

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **F-1** | AC-4, AC-13 | 将 `ComponentImportDrawer` 的选中态从解析后的 bundle 对象改为原始 `File`；保留现有文件名展示、策略选择、预览、提交和 reset 语义；不再用 `FileReader`/`JSON.parse` 作为上传前置条件 |
| **F-2** | AC-4, AC-7, AC-13 | 将 `componentService.importPreview` 和 `importCommit` 的入参改为 `File`，每次创建新 `FormData`并以字段名 `file` 发送；查询参数及响应解析不变；不手工写 multipart boundary |
| **F-3** | AC-4, AC-9, AC-13 | 预览或提交失败时继续使用全局 API 错误 message 展示，保留当前选中文件以便重试；成功后保持既有通知、刷新和关闭行为 |
| **F-4** | AC-12 | 全工程检查前端不出现 `CRYPTO_API_TOKEN`、`file.encryption.server`、`/v1/inspect`、`/v1/decrypt` 或直连 `172.16.18.45:9502` 的代码；浏览器只与 CPQ API 通信 |

## 实现约束

- `beforeUpload` 仍返回 `false`，阻止 Ant Design Upload 自动上传；真正上传发生在“预览”和“提交”按钮。
- 同一个 `File` 对象可被两次预览和一次提交重用，但每次 HTTP 请求必须新建 `FormData`。
- 选中文件时不再保证立即得到组件数；组件数和 bundle 合法性以后端预览响应为准。
- 不改抽屉宽度、表格列、按钮顺序、禁用规则、冲突策略和两个 ignore 开关。
- 不修改其他 Excel 导入页面；它们已经使用 multipart，后端透明处理即可。

## 前端自检

1. `cd cpq-frontend && npx tsc -b` 返回 0。
2. `cd cpq-frontend && npm test -- --run` 中组件导入 service／抽屉用例全绿。
3. 改动的 `.tsx` 和 `.ts` 模块在 worktree 临时 Vite 端口返回 HTTP 200；不得用主工作区 5174 端口作为修改生效的证据。
4. 跑 `test.md` 的 S-UI 分片；临时前端栈必须先预热页面，E2E 时间盒 30 分钟。
5. 回报必须列出实际命令、退出码、用例数和未验证项，不写“应该没问题”。
