# 交接文档 — 导入 & 进入报价单 性能优化(2026-06-23)

> 用途:新会话据此**接续实现**两条慢链路的性能优化。本会话已完成"诊断 + 一批已落地优化 + 一份经两轮独立评审的设计方案",**下一步是按设计方案 r2 实现剩余优化项**。

---

## 一、一句话现状

- 用户痛点:**V6 报价基础数据导入** 慢(~50–75s)、**导入后进入报价单(首存快照)** 慢(~47s)。
- 根因(已确诊):远程 DB(`10.177.152.12`,~8–13ms/往返)上**逐行/逐组件 SQL 往返过多**;CPU 非瓶颈。
- 本会话已落地几项优化并合并 master;**剩余优化项已写成设计方案 r2(经两轮独立评审),等实现。**

---

## 二、🎯 下一步要做的事(核心)

**按设计方案实现剩余优化项**:
📄 `docs/superpowers/specs/2026-06-23-import-and-firstsave-perf-optimization-design.md`(**先完整读这份**,它是实现依据,已经两轮独立评审 + r2 修订收敛)。

**实现顺序(方案 §7)**:
1. **先测准往返基线**:用 PG `pg_stat_statements.calls` 前后增量(不要用应用层 counter),对同一份 Excel 实测当前导入/首存往返数,把方案 §2 的估算换成实测。
2. **P1-A(首选,最低风险,~8–15s)**:8 个 handler 仍逐行 `upsertByMaterialNo`,改批量。**必须按三类合并方向分开实现**:
   - 类① name/type(Q04/06/07/08/09/10/13,`preserveDescriptive=true`)→ 首个非空胜,复用现成 `MaterialMasterRepository.upsertBatchNameType`。
   - 类② Q18(unit_weight)→ **末值非空胜**(因非描述列恒 `COALESCE(EXCLUDED,existing)`,与 preserveDescriptive 无关),新增 `upsertBatchWithWeight`。
   - 类③ Q02(仅 material_no)→ 新增 `upsertBatchMaterialNoOnly`。
   - 参照已落地的 `MaterialBomMergeHandler.accMaterialMaster` + `upsertBatchNameType`(本会话 #2,已带等价测试 `MaterialMasterBatchUpsertEquivTest`)。
3. **P1-C3**(首存 gvar 批量,两侧受益):`GlobalVariableService.resolveValue` 加批量,**KvTable 路径用单列 `key_id IN`(无需 NULL 特判)/ View 路径用元组 IN + NULL 逐行回落,两路分开**。
4. **P1-Q19**(年降逐行 INSERT 批量)。
5. **P2-C4**(首存最大杠杆,÷N):核价侧跨行 partSet 并集 + 每组件一次 `expandMulti`。**走 architect**,严守 AP-37 配对 / AP-51 行数 / 整条分发链可变引用只读 / composite-agg 逐料号回退。
6. P2-D / P2-Q05;P3-B / P3-C1 视实测决定。

> ⚠️ 设计方案里 **P0(`reWriteBatchedInserts`/`statement-batch-size`)已被二轮评审证伪、降级为收益≈0 的附带项**,**不要把它当免费基线或用于校准**。导入提速主体是 P1-A/Q19/Q05 的"逐行→单条多值"改造本身。

---

## 三、🚫 硬约束(违反即作废,必须先读对应记忆)

1. **禁止并行化** expand / 公式求值 / 快照求值层 —— 2026-06-22 已实证竞态、已回滚(master 934c463)。见记忆 `cpq-expand-layer-not-threadsafe`。expand 返回进程级 Caffeine 缓存的**可变对象引用**。
2. **不改变任何业务落库/计算结果**(行数/列值/版本/is_current/公式/料号生成,逐位一致)。
3. **首存不走异步**(用户已排除);**不改 `pg_advisory_xact_lock` 粒度**(语义变更)。
4. 触碰 expand/snapshot 必须保留**防御性深拷贝** + AP-37/45/50/51/52/53 既有保护。

**开工前必读记忆**(`/home/joii/.claude/projects/.../memory/`):
- `cpq-import-firstsave-perf-bottlenecks`(瓶颈清单总览,指向本设计文档)
- `cpq-expand-layer-not-threadsafe`(禁并行)
- `cpq-savedraft-incremental-snapshot`(首存禁异步 + 增量快照机制现状)
- `cpq-concurrent-sessions-and-worktree` / `cpq-worktree-maven-test-tree` / `cpq-worktree-subagent-commits-to-master`(并发/worktree 纪律)
- `cpq-deliver-agents-overreport`(派 agent 必主线亲验)

---

## 四、本会话已完成(已合并 master,勿重复)

master 当前 `a35ef44`(含本会话 perf 工作 + 另一并发会话的 saveDraft 增量/并行回滚)。本会话提交:
- `fix(v6)`:`component_usage_type` `varchar(20)→100`(V303 + MaterialBomItem/ElementBomItem 实体)——修"产出料号类型长规格(如 `AgNi10-(QSn6.5-0.1)-H65铆接件` 26字符)撑爆"。
- **#1** `VersionedV6Writer.insertRowsBatched`:子行/组行批量 INSERT(按列集签名分组 + 500 行分块)。
- **#2** `MaterialBomMergeHandler` material_master 逐行 upsert → `upsertBatchNameType` 批量(+ `MaterialMasterBatchUpsertEquivTest` 等价测试)。
- **#3** 报价导入异步化:`processImport`(`@ActivateRequestContext` 后台线程)+ 前端 `pollImportResult` 轮询 + 进度条(`updateProgress` 写 `import_record.metadata`,前端 Ant `Progress`)。
- 进度写优化为单条 native UPDATE。

> 注:本会话曾给 `MaterialBomMergeHandler` 加过临时"超长列诊断"日志,**已丢弃**(varchar 问题已修)。若将来要永久版可重加。

---

## 五、⚙️ 环境与工作流

- **后端 dev server**:`http://localhost:8081`(Quarkus dev,改 .java 自动热重载;健康查 `curl .../api/cpq/components` 期望 **401**=正常)。
- **前端 dev server**:`http://localhost:5174`(Vite;改后 `curl .../src/<path>` 期望 200)。
- **远程 DB**:`psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db`,密码 `joii5231`(`PGPASSWORD=joii5231`)。
- **dev server / DB / node_modules 共享**(勿在 worktree 另起 server/装依赖;worktree 跑前端 tsc 需软链主树 `node_modules`)。
- **工作流(CLAUDE.md 强制)**:新功能用**隔离 worktree 分支**(`superpowers:using-git-worktrees`);默认 **subagent-driven**;完成后用户确认 → 自动收尾合并 master + 清理 worktree。
- **后端测试**:`cd cpq-backend && ./mvnw -q -o test -Dtest=XxxTest`(mvnw 在 `cpq-backend/`,**worktree 里跑要在 worktree 的 cpq-backend**;`@QuarkusTest` 连同一个 `cpq_db`,测试数据自清理)。
- **Flyway 坑**:worktree 从旧 HEAD 分支时,若共享 DB 已应用某迁移(如 V303)而 worktree 缺该文件 → 启动校验失败;同步 worktree 到 master 即可(V303 已在 master)。
- **提交纪律**:并发会话同分支可能交错,**只 `git add` 本次明确改动文件,严禁 `git add -A`**;主树有大量其它会话的未提交改动,**勿动**。

---

## 六、🔒 等价性验证铁律(方案 §4,每项必带)

1. **A/B 等价单测**:逐行老路径 vs 批量新路径,落库逐位相同(模板 `MaterialMasterBatchUpsertEquivTest`)。
2. **前后置 DB md5 diff**:同一份 Excel 改动前后,逐表 `md5(array_agg(... ORDER BY 稳定键))` 全等(目标表清单见方案 §4-2)。
3. **连跑两次自比对**:同输入两遍 md5 一致(防非确定性,尤其 P2-C4)。
4. **既有测试全绿**:`MaterialBomMergeHandlerTest`/`AssemblyBomMaterialSyncTest`/`MaterialNoImportIdempotencyTest`/`FormulaCalculatorTest`/`SnapshotReconcileTest`/Versioned 套件/E2E `quotation-flow`。
5. 任一项对账不过 = 该项回滚,不合入。**派 agent 实现后主线必须亲验测试/DB/E2E(agent 会虚报完成)。**

---

## 七、未尽事项 / 提醒

- 本会话**未**回写 `docs/RECORD.md`(可选,要的话按格式追加本会话 perf 改动)。
- 设计方案 §2 的往返量级是**估算**,实现前先用 `pg_stat_statements` 测准真实基线(方案 §4-0)。
- P2-C4 改 `CardSnapshotService` 属核价侧(editRows 恒空、与报价隔离),相对安全;但仍走 architect + 全套对账。
