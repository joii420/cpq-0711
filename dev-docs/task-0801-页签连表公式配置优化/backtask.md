# 后端任务文档 · task-0801 页签连表公式配置优化

> 版本：v1.0 / 2026-08-01 · 技术总监下发
> 需求基线：同目录 `需求说明.md`（§11 澄清纪要）；接口契约：同目录 `api.md`

---

## 0. 一句话结论

**本期后端零代码改动。** 但后端工程师**不是不进场** —— 你的任务是「守住不变性 + 出具证据 + 登记债务」，共 4 项（B1~B4），预计 0.5 天。

---

## 1. 为什么零改动（结论与证据，请自行复核一遍再接受）

需求原稿曾把「`TabJoinPlanEvaluator` 及 dry-run 端点」并列为「试算配套、保留不动」，技术总监核查后判定该表述**误导**，纠正如下：

| # | 判断 | 证据（请自行复核） |
|---|---|---|
| 1 | `TabJoinPlanEvaluator` 是 **Excel 视图正式渲染链路**组件，与试算无关 | `ExcelViewService.java:50` 注入；`codegraph_callers TabJoinPlanEvaluator` 仅此 1 个调用方 |
| 2 | 前端本次只是**停止调用** 3 个端点，端点本身不动 | 见 `api.md` §1 |
| 3 | `CardSnapshotService.dryRunTokenRows` **不可删** | `CardSnapshotDryRunParityTest` 断言「试算逐行值 == 渲染逐行值」（保护的是**渲染**正确性）；`QuotePendingScopeOpenWhitelistTest:62` 把 `dryRunTokenRows` 列在 pending 域开放白名单（安全语义） |
| 4 | 公式保存不走独立端点 | 抽屉 `onSave` → `ComponentManagement` → 既有组件保存链路 |

> 📌 **复核纪律**：不要因为「文档说零改动」就跳过复核。若你复核出与上述不一致的事实（例如发现某端点其实还有别的前端调用方、或某测试实际已失效），**立即上报技术总监**，不要自行扩大或收缩范围。参见记忆教训：agent 报的结论两个方向都要对照实验。

---

## 2. 后端红线（本期绝对不做）

- ❌ **不得删除**任何 dry-run 端点（`/dry-run`、`/dry-run-token`）及 `/sample-cards`
- ❌ **不得改动** `TabJoinPlanEvaluator` / `ExcelViewService` / `CardSnapshotService` / `ComponentSampleCardService`
- ❌ **不得**给端点加 `@Deprecated`（澄清 C4 已明确否决该选项——本期只保留，标记留给日后统一清理）
- ❌ **不得**新增 Flyway 迁移（本期无 DB 变更；共享库的迁移历史是移动靶，无谓改动会连累其他会话）
- ❌ **不得**碰 `cpq-backend/` 下任何文件——若你的 PR 里出现后端文件 diff，即为越界

---

## 3. 任务清单

### B1 · 独立复核「零改动」结论并出具证据

**做什么**：按 §1 表格逐条复核，输出一份简短复核报告（贴在验收回复里即可，不必新建文件）。

**必须包含的命令与期望输出**：

```bash
# ① TabJoinPlanEvaluator 的真实调用方（期望：ExcelViewService，且不含任何 dry-run 专属类）
/usr/bin/grep -rn "TabJoinPlanEvaluator" cpq-backend/src/main/java/

# ② 3 个端点的定义位置仍在（期望：ComponentTabJoinResource 中 3 处 @Path 命中）
/usr/bin/grep -n "@Path" cpq-backend/src/main/java/com/cpq/component/resource/ComponentTabJoinResource.java

# ③ 前端确无残留调用（期望：零命中）
/usr/bin/grep -rn "sampleCardsByComponent\|dryRunByComponent\|dryRunToken" cpq-frontend/src
```

> ⚠️ 本环境 `grep` 是 `ugrep -I`，会把中文注释多的大源文件**静默判为二进制返空**。凡据 grep 空结果下「无引用」结论，**必须用 `/usr/bin/grep -a` 复核一次**（已知教训，见记忆 `cpq-grep-ugrep-binary-pitfall`）。

**完成判据**：三条命令输出与期望一致，且你**认可**零改动结论（如不认可，上报而非自行动手）。

---

### B2 · 前端改动合并前后的后端回归验证

**做什么**：在前端工程师完成后（技术总监会通知），跑一次后端相关测试，确认后端未被误伤。

```bash
cd cpq-backend
./mvnw test -Dtest='TabJoinPlanEvaluator*Test,CardSnapshotDryRunParityTest,QuotePendingScopeOpenWhitelistTest' -q
```

**完成判据**：全部 `BUILD SUCCESS`，无 failure / error。

> ⚠️ **worktree 纪律**：若在 worktree 里跑，`mvnw` 在 `cpq-backend/` 下、不在仓库根；且必须在**当前 worktree 的** `cpq-backend` 里跑，不要 `cd` 到主仓 —— 否则测的是另一棵树，得到假绿（已知教训 `cpq-worktree-maven-test-tree`）。
> ⚠️ 后端测试走 `test` profile（`10.177.152.12:5432/cpq_db`），与 dev 库 `cpq_db_0724` **不是同一个库**，这是预期行为，不要"顺手改配置对齐"。

---

### B3 · 端点可用性证明（接口不变性）

**做什么**：确认 3 个"停调"端点在前端改完后仍然存活、鉴权正常。

```bash
# 期望 401（应用在跑 + 鉴权正常）。405/404/500 都算失败。
# 注意：本机 shell 常设 http_proxy，探本机服务必须加 --noproxy '*'
for p in tab-defs sample-cards; do
  curl -s --noproxy '*' -o /dev/null -w "$p → %{http_code}\n" \
    "http://localhost:8081/api/cpq/components/00000000-0000-0000-0000-000000000000/$p"
done
curl -s --noproxy '*' -X POST -o /dev/null -w "dry-run → %{http_code}\n" \
  -H 'Content-Type: application/json' -d '{}' \
  "http://localhost:8081/api/cpq/components/00000000-0000-0000-0000-000000000000/dry-run"
```

> 后端未装 `smallrye-health`，`/q/health` 返 404 —— **它不是健康探针**，别拿它判断服务死活。判后端健康看业务端点返 401。

**完成判据**：三个端点均返 401（而非 404/500）。

---

### B4 · 登记 BACKLOG 债务条目

**做什么**：在项目根 `BACKLOG.md` 的 **P2** 区新增一条（格式对齐现有条目）：

```markdown
### [BL-00XX] 两个 dry-run 端点自 2026-08-01 起无前端调用方，待统一清理
- **优先级**：P2
- **来源**：task-0801 页签连表公式配置优化（澄清 C4）
- **状态**：TODO（未排期）
- **登记日期**：2026-08-01
- **背景**：task-0801 移除了公式抽屉的试算功能，`POST /components/{id}/dry-run`、
  `POST /components/{id}/dry-run-token`、`GET /components/{id}/sample-cards` 三个端点
  **前端已全部停调**，后端按裁决原样保留（不删、不标 @Deprecated）。
- **⚠️ 清理前必读**：`dry-run-token` 背后的 `CardSnapshotService.dryRunTokenRows` 挂着
  `CardSnapshotDryRunParityTest`（断言「试算逐行值 == 渲染逐行值」，实际保护**渲染路径**正确性），
  且被 `QuotePendingScopeOpenWhitelistTest` 列入 pending 域开放白名单。**删端点前必须先给渲染路径
  补等价的 parity 断言**，否则会静默削弱渲染侧保障。
- **范围**：确认无其他消费方后，删端点 + `ComponentSampleCardService` 对应方法，并保留/改写 parity 测试。
- **依赖**：无。**预估规模**：S
- **验收要点**：①端点删除后全工程零引用；②渲染路径的 parity 保障不弱于清理前。
```

**BL 编号**：取 `BACKLOG.md` 现有最大编号 +1（登记前先 `grep -o 'BL-[0-9]*' BACKLOG.md | sort -u | tail -3` 确认，避免与并发会话撞号）。

**完成判据**：条目写入 `BACKLOG.md`，编号不与现有冲突。

---

## 4. 提交纪律

- 本任务**只允许**改 `BACKLOG.md` 一个文件（B4）。其余全是验证动作，不产生 diff。
- 提交只 `git add BACKLOG.md`，**严禁 `git add -A`**（多会话并发，会夹带他人改动 —— 已连续踩过两次的坑）。
- 提交后用 `git show --stat` 自查，确认只有预期文件。

---

## 5. 交付清单

| # | 交付物 | 形式 |
|---|---|---|
| B1 | 零改动结论复核报告（3 条命令输出 + 你的结论） | 验收回复正文 |
| B2 | 3 个测试类的 `BUILD SUCCESS` 输出 | 验收回复正文 |
| B3 | 3 个端点 401 的 curl 输出 | 验收回复正文 |
| B4 | `BACKLOG.md` 新条目 | 一次提交 |

**验收由技术总监执行**，会独立重跑 B1/B3 的命令做交叉验证——请确保你贴的输出是真实运行结果。
