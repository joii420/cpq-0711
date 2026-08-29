# test · repair-260829 异步物化事务上下文缺失

> 闸门 A 的**前置产物**（开工前写完）。执行结果另出 `test-report.md`。
> 测试库 = `10.177.152.12:5432/cpq_db`（`mvnw test` 走 `test` profile）；**亲验在 dev 库 `cpq_db_0724`**，两者不同，见 `CLAUDE.md` profile 表。

## 0. 本次测试的特殊前提（务必先读）

🚨 **本缺陷只在「原 HTTP 请求已结束」时触发**（勘察证据 B：A/B/C/D 四组请求存活时全绿，只有 E 组 fire-and-forget 才复现）。

**后果**：常规 `@QuarkusTest` **测不出它** —— 无论用注入调用还是 REST-assured，请求在断言前都不会结束。
**因此本次的核心用例必须是 fire-and-forget 形态**：投递后台任务 → 请求返回 → **再**读结果。

🚫 **写出「注入调 materialize 然后断言」的用例 = 假绿**，它在缺陷存在时也会通过。

## 1. AC 可追溯矩阵

| AC | 用例 | 层级 | 谁执行 |
|---|---|---|---|
| AC-1 | T-01 | 真机（dev 库） | 主线亲验 |
| AC-2 | T-02 | 真机 + SQL | 主线亲验 |
| **AC-3** | **T-03** | **还原实验** | **主线亲验（不可委托）** |
| AC-4 | T-04, T-05 | 集成 | test-engineer |
| AC-5 | T-06 | 真机计时 | test-engineer + 主线复核 |
| AC-6 | T-07 | 集成（md5 对比） | test-engineer |
| AC-7 | T-08 | SQL | test-engineer |
| AC-8 | T-09, T-10, T-11 | 集成 | test-engineer |
| AC-9 | T-12 | 真机序列 | 主线亲验 |
| AC-10 | T-13 | 脚本 + SQL | backend-engineer 执行 / 主线核对 |
| AC-11 | T-14 | 自检 | 主线 |
| —（B-1 前置） | **T-00** | 诊断探针 | backend-engineer |

**双向覆盖自检**：11 条 AC 全部有用例认领 ✅；14 条用例全部指回 AC（T-00 指回 B-1 的前置判定）✅。

## 2. 用例

### T-00　方案丙假设证伪（B-1 的产物，闸门性质）
两组对照，**缺一不可**：默认 executor → 期望 `componentsSnapshot=null`；`cleared(CDI)` executor → 期望 `present(N tabs)`。
**只跑实验组不跑对照组 = 空验证**，结论无效。

### T-01　建单后页面非空（AC-1）
1845 料号 + 「正泰模板1」走完整导入建单流程 → 打开编辑页。
**断言**：≥4 个 driver 页签 `baseRows` 长度 > 0；产品小计 ≠ 0；单头总价 ≠ 0。
**证据**：页面截图 + 至少一个页签的具体数值。

### T-02　组件数据行数（AC-2）
```sql
SELECT count(*) FROM quotation_line_component_data d
  JOIN quotation_line_item li ON li.id = d.line_item_id
 WHERE li.quotation_id = :qid;
-- 断言 = 7380（1845 × 4 driver 组件）
SELECT count(*) FROM quotation_line_component_data d
  JOIN quotation_line_item li ON li.id = d.line_item_id
 WHERE li.quotation_id = :qid AND jsonb_array_length(COALESCE(d.snapshot_rows,'[]'::jsonb)) > 0;
-- 断言 = 7380
```
> 零改动基线此值为 0，判据有区分度（非恒真）。

### T-03　还原实验（AC-3）🚨 **不可省、不可委托** —— 2026-08-29 形态修正
**改为探针层并发多发对照**（原「端到端单发把修复改回去」已实测证伪三次，见 `问题说明.md` AC-3 修正说明）：
一次投递 ≥8 发 fire-and-forget 探针，对比默认 executor 与 `cleared(ThreadContext.CDI)`。
**断言**：默认组出现大量 `componentsSnapshot=NULL`；cleared 组零 NULL。
**已完成**（主线亲跑 8081，各 4 轮 × 10 发）：默认 **37 NULL / 3 present**；cleared **0 NULL / 40 present**。证据 `证据/E6`。
> 端到端单发**不能**用作还原实验：竞态下任务几乎总在原请求结束前被调度到，两组都会成功。

### T-04　① 步空转发出信号（AC-4）
构造「明细行 > 0 **且** driver 组件数 > 0 **且** `comp_data == 0`」的状态，执行物化。
**推荐做法**：直接构造 DB 状态 + 调用 B-4 抽出的守卫方法（独立可测），**不必**依赖 fire-and-forget 复现，也**不必** mock `loadDriverComponents`。
⚠️ **不要用「SQL 视图引用不存在的表」当 fixture** —— 那条路径下 comp_data 行可能仍被创建（只是 `snapshot_rows` 为空），
终态与 T-11 相同而与真实缺陷不同，会让 T-04 与 T-11 互相矛盾。
**断言**：`CommitResult.warnings` 含明确指出「组件数据 0 行」的文案；日志出现 **ERROR** 级记录。
> 零改动基线上 `warnings` 为空、无 ERROR、埋点四步全绿 → **本条在基线上必然失败**，符合阳性判据。

### T-05　信号不误伤正常路径（AC-4 反向）
正常建单（① 步成功）→ 断言 `warnings` **不含**该文案、无 ERROR。

### T-06　性能未劣化（AC-5）
先由 B-6 用请求线程对同规模单跑一次**成功**物化，实测值记为 `BASE`；再跑修复后的建单，记 `[create-quotation-timing]` 总计。
**断言**：总计 ∈ [BASE×0.5, BASE×1.5]。
> 🚫 **`BASE` 不得取 `6689ms`** —— 那是 ① 空转（447ms 什么都没做）的耗时。实测 `S2.snapshotRows=14114ms`（增量路径），修好后总耗时**必然上升**。拿 6689ms 设阈值 = 写一条从第一天就不可达的 AC。

### T-07　其它路径逐位不变（AC-6）
对同一张已物化的单依次跑 `saveDraft` / 加产品 / 从基础刷新 / `ensure-card-values` / 核价侧渲染，
比对 `quote_card_values`、`costing_card_values`、`subtotal`、`total_amount` 的 md5 与改动前相同。
> ⚠️ 用 `git stash` 背靠背对比（`RECORD.md`「值中性验证法」），不要靠历史快照。

### T-08　pending 数据零改动（AC-7）
改动前后各跑一次，比对五张表的三元组：
```sql
SELECT 'material_bom_item' t,
       count(*) FILTER (WHERE is_current) cur,
       count(*) FILTER (WHERE NOT is_current) noncur,
       count(DISTINCT pending_quotation_id) pq
FROM material_bom_item
UNION ALL SELECT 'material_bom', … （其余四张同构）
```
**断言**：逐表三元组完全一致。基线值见 `证据/E2-pending行全时间线.txt`。
⚠️ T-13 新增的步骤①（`UPDATE ... SET quote_card_values=NULL`）只碰 `quotation_line_item` 的两个 JSONB 列，与本条比对的五张 V6 基础资料表无关，互不影响。

### T-09 / T-10 / T-11　边界不误报（AC-8）
- T-09：明细行 0 条的单 → 不写 warnings
- T-10：模板 driver 组件 0 个 → 不写 warnings
- T-11：某组件视图合法返 0 行 → 该组件 comp_data 行**存在**且 `snapshot_rows='[]'`，**不**触发告警
> 🔧 **判据是三元的**（2026-08-29 修正）：`明细行数 > 0` **且** `driver 组件数 > 0` **且** `comp_data == 0`。
> `driver 组件数 > 0` 这一维专为 T-10 而加 —— 少了它，「① 步炸了」（T-04，要报）和「模板挂 0 个 driver 组件」（T-10，不报）
> **终态都是 `comp_data == 0`**，二元判据下 T-10 必然误报。

### T-12　序列（AC-9）
建单 → 打开（确认非空）→ 改一个数值失焦 → 保存草稿 → 切走再切回 → 刷新页面。
**断言**：改动值持久；其余行不变；总价按改动值重算；全程 comp_data 行数恒为 7380。

### T-13　存量修复（AC-10，2026-08-29 三次修正）
**19 张单**（数量固定；A/B 两类构成随实验推进而变，**执行前用 `backtask.md` B-7 的 SQL 重新查一次，不要照抄任何时点的快照数字**——曾先后误记为 21、"17+2"、且漏想 B 类步骤），按 `cd`/`sub_nz` 分两类：
- **A 类·半修复**（`cd>0 且 sub_nz=0`）：先 `UPDATE ... SET quote_card_values=NULL, costing_card_values=NULL` 再重跑 `ensure-card-values`
- **B 类·全空**（`cd=0`）：先 `POST /configure-product/quotations/{id}/refresh-snapshot`（驱动①步展开），**再**重跑 `ensure-card-values`
  > 🔧 **不可只做 `ensure-card-values`**——它只渲染"已有"的 snapshot_rows，B 类从未展开过，跳过 `refresh-snapshot` 会空转、原样返回 200 但状态不变（此为本任务开发期实测发现，非猜测）

逐单断言 comp_data == 明细行数 × driver 组件数、**且 `total_amount ≠ 0`、`li.subtotal` 非零行数 > 0**。
🔧 **金额断言不可省**——只断言 comp_data 非空会让半修复态误判通过。
🔧 **B 类必须同时断言 comp_data 从 0 变为非 0**——只断言"HTTP 200"或"无异常"通不过，`ensure-card-values` 空转同样返回 200。
🚫 `quotation.updated_at` 不可作判据（写 comp_data 不更新主表时间戳，会得出"谁动过"的错误结论）。
🚨 **执行脚本必须在网络层 abort 一切非 GET**（`RECORD.md` 2026-08-28 事故：诊断脚本触发自发 `PUT /draft` 清空 1845 行卡片值，恢复时还撞了 60s reaper）。
🚨 A 类步骤①是写操作，执行前先报影响面数字给主线（`CLAUDE.md §3.2`）。

### T-14　自检证据（AC-11）
后端编译 0 错误 + 相关用例全绿 + `/api/cpq/components` 返 401；
亲验证据 = T-01 截图 + T-02/T-08 的 SQL 原始输出 + T-03 的两次日志对照。

## 3. 不稳定失败的归因纪律

共享 dev 库有并发会话，出现失败先按 `testing.md` 归因，**不要直接判为本次回归**：
对照打**干净 master** 同型跑一次（`RECORD.md`「E2E quotation-flow 干净 master 恒 3 失败」的教训），A/B 同型对比后再下结论。
