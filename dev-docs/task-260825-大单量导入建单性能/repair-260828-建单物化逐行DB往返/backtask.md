# backtask · repair-260828 建单物化逐行 DB 往返

> 立项文档：`问题说明.md`（AC 原文在 ⑥，本文件只标编号不复制原文）
> 🚫 **落地顺序是硬约束：B-2 → B-3 → B-4 → B-5**。B-丙（B-2/B-3）解除一级缓存依赖，是 C-丙（B-4/B-5）能安全 detach 的前置条件；顺序调换会让 detach 读到旧 `subtotal`。B-1 与顺序无关，可先做。

**改动文件（全部集中在一个类，无迁移、无 schema 改动）**：`cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java`

---

## 任务分解

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-4, AC-10, AC-11 | `preloadComponentDataByLine`（`:911-914`）补全返回 Map：`groupingBy` 之后，对入参 `lineIds` 中未出现的 id 逐个 `putIfAbsent(id, List.of())`，使「预载过但无 componentData」返回**空列表而非 null**。🔒 **`assignQuoteCardValues`（`:806-808`）的 `preloadedCd != null ? … : …list("lineItemId", li.id)` fallback 原样保留**，单行写路径依赖它 |
| **B-2** | AC-1, AC-2, AC-9 | `snapshotNewLinesCardValuesCore` 的 **Pass2c 删除** `recomputeDraftHeaderTotals(q.id)` 调用；改由两个公开入口各自负责：`ensureCardValuesDetailed` 在**批循环结束后**调一次、`snapshotNewLinesCardValues`（单行入口）在调完 Core 后调一次。🔒 Core 不再承担整单级工作（同 B-16 对 `render` 的处理纪律）|
| **B-3** | AC-1, AC-9 | `recomputeDraftHeaderTotals`（`:886-905`）把 `QuotationLineItem.list("quotationId", q.id)`（整单完整实体，实测 2912 kB）换成**只读 `subtotal` 的聚合查询**。⚠️ **必须同步重写 `:882-886` 那段「故意不写聚合」的注释**——它描述的前提在新调用位置已不成立，留着会让后人改回去。注释要写清：为什么在新位置聚合是安全的（各批已 REQUIRES_NEW 提交） |
| **B-4** | AC-1, AC-2, AC-5, AC-6, AC-7, AC-8, AC-12 | ③ 段落库改原生批量 UPDATE。`snapshotNewLinesCardValuesCore` 的 **Pass2 赋值之前**，把本批 `QuotationLineItem` + `QuotationLineComponentData` 全部 `em.detach(...)`；赋值作用于游离对象、不触发脏检查；随后用 `em.unwrap(Session.class).doWork(conn -> …)` + `PreparedStatement.addBatch()/executeBatch()` 写固定列集。<br>**`quotation_line_item`**：`quote_card_values`(jsonb) / `costing_card_values`(jsonb) / `subtotal` / `quote_values_at` / `card_snapshot_at`；**`quotation_line_component_data`**：`subtotal`（第二条语句，本批无 cd 时不发）|
| **B-5** | AC-3, AC-5, AC-8, AC-12 | ④ 段同款改造：`ensureExcelValuesBatch`（`:1140-1185`）去掉 `managed.persist()` 的脏检查落库路径，改 detach + 原生批量 UPDATE，固定列集 = `quote_excel_values`(jsonb) / `costing_excel_values`(jsonb) |
| **B-6** | AC-5 | 埋点：在 ③④ 各自的批方法里记录 `executeBatch()` 的**调用次数与影响行数**，格式对齐既有风格 `[perf] 段名 quotation=… rows=N batches=M updates=K`。这是 AC-5 的唯一可复核依据（`pg_stat_statements` 未装，已实测确认） |
| **B-7** | AC-13, AC-14 | 后端强制自检 + N+1 自检声明（`backend.md §2.1 / §1`）|

---

## 🔒 硬约束（违反即打回，不是建议）

1. **`@DynamicUpdate` 一个字都不许动。** 它治的是 `3a69ca97` 修复的数据丢失事故（A/B 实测 OFF 0/8 → ON 8/8）。本次是**绕开它、不是削弱它**——注解对其余所有写路径继续生效。
2. **detach 必须在赋值之前，不许改成「先赋托管实体、写完再 `em.clear()`」。** clear 之前任何查询触发 `flush-before-query`，就会先发出 N 条 UPDATE，改动完全失效**且不报错**。
3. **`assignQuoteCardValues` 一行不改。** 它是方向3 T1 的唯一写入口收敛点：`CostingSubtotalUtil` 唯一口径、「不覆盖的三种情况」、`SubtotalOverrideCounter` 计数，全部原样。本次只换落库机制，不换「算什么值」。
4. **task-260825 的成果全部保留**：异步化、600s 事务包装（B-15）、10s `lock_timeout`（B-28）、批失败不连坐（B-28）、B-30 确定性排序、`IS NULL` 自愈谓词。B-4 改完后，「某批失败 → 该批行仍为 NULL → 下次按 `IS NULL` 补算」这条语义必须仍然成立（AC-7）。
5. **JSONB 绑定用参数化**（`setObject(i, json, Types.OTHER)` 或 `?::jsonb`），🚫 **不许字符串拼 SQL**。
6. **不写迁移、不动 schema。** 本次是纯代码改动。
7. **红线（`CLAUDE.md` §3.2）**：本任务不涉及任何 DROP / TRUNCATE / 无 WHERE 的 UPDATE·DELETE。**若开发中发现需要动数据，停下报主线，不许自行执行。**

## N+1 自检要点（B-7 交付物，`backend.md §1`）

逐个过本次改动涉及的循环体，写明判定结论：

- `snapshotNewLinesCardValuesCore` Pass1 的 `for (QuotationLineItem li : lines)`
- 同方法 Pass2 的 `for (QuotationLineItem li : lines)`
- `assignQuoteCardValues` 内的 `for (cd : cds)`
- `ensureExcelValuesBatch` 内的 `for (QuotationLineItem managed : lines)`
- `ensureCardValuesDetailed` 的批循环

声明格式示例：`批量化验证：N=300 与 N=1845 两次运行，quotation_line_component_data 的 SELECT 恒为 1 条/批 ✅`

## 自检清单（B-7，收工前逐条勾）

- [ ] 强制重启 Quarkus（touch 一个 java 文件），启动无错误日志
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/components` → **401**（不是 500）
- [ ] **无 Flyway 迁移**——在汇报里显式声明「本次无迁移」，不要留空让人猜
- [ ] N+1 自检声明已写（上面 5 个循环体逐个过）
- [ ] `[create-quotation-timing]` / `[ensure-cardvalues-batch]` / `[lazy-excel]` 三处既有埋点**仍正常输出**（B-4/B-5 改了落库路径，别把埋点改丢了）
- [ ] 「完成」宣告带 `CLAUDE.md` §6.1 要求的自检声明行
