# 交接文档：报价单 driver 默认行可永久删除（待实现）

> 创建：2026-06-17
> 用途：新会话据此从头执行"driver 默认行可永久删除"特性。前序设计/评审/计划已完成并提交，**实现代码 0 行**（worktree 已丢弃）。

## 1. 当前状态（master HEAD = `cefba1a`）

- **已完成并合并的相邻特性（无需再动，仅作上下文）**：报价单「复制」支持模板选择 + 跨模板用户输入迁移。列表页 + 详情页复制都先弹 `CopyQuotationDrawer`。相关：`QuotationService.copy(id, templateId)`、`CopyQuotationDrawer.tsx`、`QuotationList.tsx`、`QuotationDetail.tsx`。
- **本特性进度**：
  - ✅ 设计 spec（已吸收一轮 cpq-architect 独立评审）：`docs/superpowers/specs/2026-06-17-deletable-driver-default-rows-design.md`（v2，commit `2308de4`）
  - ✅ 实现计划（9 任务，TDD，含完整代码）：`docs/superpowers/plans/2026-06-17-deletable-driver-default-rows.md`（commit `cefba1a`）
  - ❌ 实现：**未开始**。曾起 worktree 跑 Task1（V300 加列）后被用户叫停并**丢弃**（worktree + 分支 + Task1 提交全删，DB 未加列、master 干净）。

## 2. 需求（已与用户确认）

报价单编辑页（Step2）里 driver 自动展开的"默认行"（当前显示 🔗、不可删），改为**可被用户永久删除**：刷新 / 重算 / 加产品后都不回来；适用**所有报价单**的**报价侧**（核价侧不开放）。

## 3. 方案核心（详见 spec，务必通读 spec + plan 再动手）

- 每页签持久化墓碑数组 **`deleted_row_keys` jsonb**（每项 `{effKey, fp}`），加在 `quotation_line_component_data`。
- 前后端**各一份逐字节对拍**的纯函数：`rowFingerprint`（rowKeyFields 值 + driverRow 全字段值的共享规范化串）+ 双命中过滤（effKey **且** fp 都命中才判删）。
- **头号不变量**：effKey 永远基于「完整 driver 展开集」唯一化；**过滤后的子集绝不再算 key / 重排下标**（违反 = AP-54 错位 + editRows 串行）。过滤 = 在每个唯一化点之后按墓碑剔除整行。
- 删除经**专用追加端点** `POST /quotations/{qid}/line-items/{lid}/delete-driver-row`（不混进高频防抖 saveDraft），并就地 `refreshQuoteCardValues` 单行重刷。
- `expansion.rowCount` 语义**不变**，另立 `effectiveRowCount`；`deletedRowKeys` **不进** `driverExpansionKey`。
- snapshot_rows **存全量**、渲染/求值/Excel 期一律过滤（墓碑唯一权威）。
- 换模板复制**清空**墓碑（同模板才拷）。核价侧 `buildCostingCardValues` 传空墓碑（`side==QUOTE` 隔离）。
- 位置型行键（无业务行键、effKey=下标）的组件：**允许删 + fp 二次校验**（用户拍板）。

## 4. 实现计划（9 任务，全部代码已在 plan 里写好）

1. Flyway 加 `deleted_row_keys` 列 + 实体字段
2. 后端纯工具 `DeletedRowKeys`（指纹 + keepMask + parse）+ 单测
3. 前端纯工具 `deletedRows.ts` + **与后端对拍** vitest
4. 后端过滤落点（`buildResolvedRows` / `computeRows` / `buildCardValues`→`buildBaseRowsFromSnapshotRows` / Excel）+ 核价隔离
5. 追加墓碑端点 + restore-all + 单行重刷
6. 复制：同模板拷墓碑 / 换模板清空
7. 前端展开消费侧过滤（`useCardSnapshots`）+ `effectiveRowCount`
8. Step2 删除交互 🔗→✕ + 行对齐用 effectiveRowCount + 详情页 `ReadonlyProductCard` 核对 + service API
9. 合并后集成验证 + 双 E2E + RECORD（主线执行）

> plan 的 self-review 指出要补 2 个单测夹具（撞键删中间剩余 #N 不变 / 删行后源集增 1 行墓碑不误命中）——实现 Task2/3 时一并加。

## 5. 必读的项目约束（踩坑预防）

- **开发流程**：必须用隔离 worktree（`superpowers:using-git-worktrees` / EnterWorktree），默认 `superpowers:subagent-driven-development`（每任务派全新子代理 + spec/质量两阶段评审）。
- **worktree + 共享资源约束（关键）**：后端 dev server(8081) / 前端 Vite(5174) / 远程 DB 都服务**主工作区(master)**，不服务 worktree。所以：
  - worktree 内**只能**做 `./mvnw -o compile`、纯 JUnit 单测（非 `@QuarkusTest`）、`npx tsc --noEmit`、`npx vitest run`。
  - worktree 需要 `node_modules` 时 **symlink 主工作区的**：`ln -s /home/joii/project/cpq/cpq-frontend/node_modules cpq-frontend/node_modules`（gitignored）。
  - **不要**从 worktree 触发 Flyway / 跑 `@QuarkusTest` / curl 8081——会把迁移写进共享 DB 而主工作区无该文件 → 下次重启 Flyway 失配。**Flyway 实跑 + E2E + curl 删行验证全部在 Task9 合并 master 后做**。
- **合并纪律**：master 可能有**其他会话的未提交改动**（如 `docs/RECORD.md`、`ComponentManagement*.tsx`）。合并/提交时**只 `git add` 本次明确改动的文件，严禁 `git add -A`**；改 RECORD.md 用 `git stash push -- docs/RECORD.md → 提交自己条目 → stash pop` 保护他人改动。master 自我方分支基点以来可能前移，merge 前先 `git merge master` 进 worktree 解决再合回。
- **DB 连接**（仅 Task9 合并后用）：`PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db`；登录 `POST /api/cpq/auth/login {"username":"admin","password":"Admin@2026"}`。
- **E2E**（Task9）：`cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list`，须 `1 passed` + 全 Tab `'加载中'=0`；首跑若卡 Step1 antd 下拉是已知 flakiness，复跑。`composite-product-flow.spec.ts` 既有环境失败（RECORD 2026-06-10），与本改动无关但需确认失败点无关。
- **协议级自检**：本特性触及 `QuotationStep2.tsx`/`useDriverExpansions.ts`/`useCardSnapshots.ts`/`CardSnapshotService.java`/`FormulaCalculator.java`/`QuotationService.copy`，按 CLAUDE.md §5 强制双 E2E；不改 `field_type` 枚举故**不触发** AP-44 矩阵。必验：删行后刷新 3 次行数稳定(AP-51)、剩余行输入不串行(AP-54)、加产品路径与重刷路径行为一致、详情页同步过滤(AP-50)。
- **完成收尾**：用户确认后走 `superpowers:finishing-a-development-branch` 合并 master → 清理 worktree；记忆 `cpq-auto-finish-merge-e2e-cleanup` = 评审通过即自动收尾，但本特性含 DB 加列(虽 ADD COLUMN 加性安全)，合并触发 Flyway，按既有流程做。

## 6. 关键文件 file:line 索引（plan 已含，速查用）

- 行键唯一化：前端 `cpq-frontend/src/pages/quotation/useCardSnapshots.ts:110/154`；后端 `cpq-backend/.../FormulaCalculator.java:662/691-747`
- baseRows 过滤落点：`CardSnapshotService.java` `buildResolvedRows:929`(唯一化在 `:944`)、`buildCardValues:468`、`buildBaseRowsFromSnapshotRows:641`、`filterEditRowsToNewBaseRows:981`、`refreshQuoteCardValues:1240`、`buildCostingCardValues:526`(核价,传空)、`buildExcelValues:575/583/609`
- 冻结层写入：`ConfigureSnapshotService.java:122`（存全量，不在此过滤）
- 渲染删除按钮：`QuotationStep2.tsx:2506-2527`（`isDriverBound` 🔗 在 `:2509-2510`）；行对齐 `:1467-1472`
- 复制迁移：`QuotationService.java` `copy(id,templateId)` + `migrateAndCreateComponentData`（约 `:1293`）
- 持久化 DTO：`SaveDraftRequest.java:99-105`（**不含** deletedRowKeys，故用专用端点）

## 7. 下一步（新会话第一步）

1. 读 `docs/RECORD.md`（项目要求开工前必读）+ 本特性 spec + plan。
2. 起隔离 worktree（EnterWorktree），symlink node_modules。
3. 按 plan 走 subagent-driven：Task1→Task8 在 worktree（仅编译/单测/tsc/vitest 验证），Task9 合并后由主线做 Flyway 实跑 + 双 E2E + curl 删行 + RECORD。
4. 每任务 spec 合规 + 代码质量两阶段评审；trivial 任务可控制者亲验。
