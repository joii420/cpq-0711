# Codex 项目接手规范

本项目后续由 Codex 接手开发时，必须完整遵循 [`CLAUDE.md`](./CLAUDE.md) 中的项目开发规范。

## 唯一规范来源

- `CLAUDE.md` 是本项目开发流程、架构约束、任务文档、测试、验收和收尾规则的唯一来源。
- 不复制或改写其中的详细条款，避免 Claude 与 Codex 两套规范发生漂移。
- 开始新会话时，按 `CLAUDE.md` 规定的顺序读取项目态势、历史记录和待办信息。

## Codex 执行约定

- 遵循 `CLAUDE.md` 的路径确认、A/B 分流、A0/A/B 闸门、用户裁决、worktree、子代理、测试分片、主线亲验和收尾提交要求。
- 遵循其中的不可逆操作红线；需要用户裁决的范围、方案和豁免，不自行决定。
- Claude 专用 hook 不会自动在 Codex 中运行。遇到 hook 负责的检查时，Codex 必须执行等价的脚本或命令，并在进度和最终汇报中说明结果。
- 规则分册、`docs/RECORD.md`、`dev-docs/INDEX.md`、`docs/BACKLOG.md` 以及任务目录文档的维护要求照常有效。
- 任何“完成”声明都必须包含 `CLAUDE.md` 要求的“已自检”声明和可复核证据。

## Hook 对照

Claude hook 脚本位于 `.claude/hooks/`。Codex 接手时按需人工或命令行执行对应检查，尤其是：

- `check-decisions.sh`：核对方案决策记录；
- `check-selfcheck.sh`：核对自检声明和验证证据；
- `guard-redline.sh`：检查不可逆操作红线；
- `session-brief.sh`：读取会话开局摘要；
- `pre-compact.sh`：上下文压缩前的落盘与重锚检查。

