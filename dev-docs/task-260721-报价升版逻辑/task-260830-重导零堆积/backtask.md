# backtask —— 后端任务分解

> **只按本文件做。** AC 原文在 `需求文档.md` §③，本文件只标编号不复制原文。
> **红线**：任何 `DELETE` / `UPDATE` 命中面不明的操作，先跑 `SELECT count(*)` 量化并报主线，等用户批准（`CLAUDE.md` §3.2）。**子代理没有批准权。**

---

## 核心思路（先读这段再动手）

现有 `VersionedV6Writer.writeVersionedGroup` 的分支结构：

```
1) loadCurrentGroup(is_current=TRUE) → existing
2) if (triggerSame && contentSame)        → 复用版本，不写      ← 目标出口
   if (pendingQuotationId != null)        → pending 影子行 + 升版 ← 现在恒走这里
3) if (triggerSame)                       → 原地更新，版本不变
4) 否则                                    → 升版；existing 非空才 flip
```

**改法不是新增分支，是给 pending 分支的入口加一个条件，让「空组」穿透到分支 4。**

```java
// 改前
if (spec.pendingQuotationId != null) { ... }
// 改后
if (spec.pendingQuotationId != null && !groupNeverExisted(...)) { ... }
```

穿透后分支 4 天然正确：`existing` 为空 ⇒ 不 flip ⇒ 直接 insert `is_current=true`、版本号 `2000`（`nextVersionOf` 在 MAX 为 null 时返回 `"2000"`）。分支 3 也进不去（`existing` 空 ⇒ `triggerSame` 必为 false，原注释已说明）。

⚠️ **不要为「首次落地」另写一条写入路径** —— 那会与分支 4 产生第二份组装逻辑，是 `AP-50` 那类 single-source 反模式的形状。

---

## 任务项

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-1, AC-9, AC-10 | 新增判据方法 `groupNeverExisted(table, groupKey)`：`SELECT EXISTS(SELECT 1 FROM <table> WHERE <groupKey 全等>)` 取反。<br>🚫 **不许复用 `maxNumericVersion() == null` 当判据** —— 它用 `~ '^[0-9]+$'` 过滤非数字版本，某组只含 `'V_DEFAULT'` / `'V1'` 行时 MAX 也是 null 却并非空组（AC-9 专测此点）。<br>⚠️ 判据是**「从未有过任何行」**，不带 `is_current` 条件（AC-10 专测此点）。 |
| **B-2** | AC-1, AC-4 | `writeVersionedGroup` 的 pending 分支入口（`VersionedV6Writer:158`）加 B-1 条件。 |
| **B-3** | AC-1, AC-4 | `writeVersionedGroupsBatch` 的 pending 分支（`:325`）同款。<br>🚫 **禁 N+1**：本方法是批量前缀路径，判据必须**按常量前缀一次查完**（形如 `loadCurrentByPrefix` 的写法：`SELECT <gkCols> FROM <table> WHERE <prefixWhere> GROUP BY <gkCols>` 得到「已存在组」集合），**不许逐组调 B-1**。`CLAUDE.md` §2.2：循环体里出现查询 = 违规。 |
| **B-4** | AC-1, AC-4 | `writeVersionedMasterDetail` 的 pending 分支（`:617`）同款。判据以**主表**组为准，主/子一同走正式写入。 |
| **B-5** | AC-1, AC-4 | `writeVersionedMasterDetails` 的 pending 分支（`:758`）同款，批量前缀路径，禁 N+1 同 B-3。 |
| **B-6** | AC-2, AC-3 | **确认并留证**：`material_customer_map` 与 `material_master` 的写入**不经过** `VersionedV6Writer`（走 `MaterialCustomerMapRepository` / `MaterialMasterRepository` 的 UPSERT），因此 B-2~B-5 不波及它们。<br>产出：两条 grep 证据 + 一次实跑后的 SQL 断言（AC-2）。**若发现任何一条走了 VersionedV6Writer，立即停下报主线** —— 那意味着范围判断有误。 |
| **B-7** | AC-12 | 存量孤儿清理：复用 `PendingHygieneService`（BL-0092）。<br>**三步前置缺一不可**：① 先 `SELECT count(*)` 量化「归属单已不存在」的行数并按表列出；② 说明可恢复性；③ **报主线并等用户批准**后才执行。<br>🚫 不许自行执行任何 DELETE。 |
| **B-8** | AC-13 | PRICING 侧无回归验证：证明核价导入路径 `pendingQuotationId == null` 恒成立，B-2~B-5 的新条件对其为**短路不可达**。产出 A/B 对照证据（改动前后同一份核价 Excel 导入，落库行逐字对比）。 |
| **B-9** | AC-1, AC-4, AC-5, AC-6, AC-7 | 单元/集成测试：覆盖「空组→正式行」「非空组→pending」「重导零写入」「有差异→升版」四条主路径。<br>⚠️ 测试**禁止清库或重置全局状态**（`CLAUDE.md` §3.2「环境销毁」），共享库上不许跑会清库的测试，哪怕写在 `beforeAll` 里。 |
| **B-10** | AC-8 | 并发安全验证：两事务同时对同一全新组走首次落地，断言结果只有 1 行 `is_current=true`。<br>本项**不新增锁代码** —— `advisoryLock(spec.tableName, spec.groupKeyColumns)` 已在方法开头，第二个事务会等第一个提交后重新 `loadCurrentGroup` 见到基线。本项是**证明既有锁在新路径下仍然生效**，不是实现锁。 |
| **B-11** | AC-11 | 删单不回收正式行的验证：走完整删单流程（`cleanupPendingV6Data`），断言首次落地的正式行仍在。<br>本项**不新增代码** —— 现有删单只 DELETE pending 行，天然不碰正式行。本项是**用测试把该行为钉死**，防止后续有人"顺手"给删单加正式行回收。 |

| **B-12** | AC-15, AC-19 | **迁移：5 张表唯一键加 pending 维度**（`uq_material_bom_v6` / `uq_material_bom_item` / `uq_unit_price` / `uq_element_bom_v6` / `uq_element_bom_item`），各加 `COALESCE(pending_quotation_id, '00000000-0000-0000-0000-000000000000'::uuid)`。<br>🚨 **迁移前必须先跑冲突检测**（按新键 `GROUP BY ... HAVING count(*)>1`），非 0 行**立即停下报主线**，不许自动删除或合并冲突行（§3.2 数据销毁红线）。<br>🚨 **写好迁移文件但不许自行在 dev 库执行** —— dev 库是多会话共享的，Flyway 历史被并发篡改会让别人的 8081 起不来。执行时机由主线决定。本仓 2026-08-29 刚发生过子代理未经批准在 dev 库跑迁移的越界事件。<br>迁移号取当前 max+1，**建号前先 `ls` 一遍迁移目录**（版本号是移动靶，多会话并发时会撞号）。 |
| **B-13** | AC-14, AC-17, AC-18 | **版本号内容寻址**：pending 分支决定版本号时，先取该组**最新一版**（`MAX(版本列)`，不论正式还是 pending）的全部行，与本次 `newRows` 做多重集比对（复用现有 `multisetEqual`）：<br>· 逐字相同 → **复用该版本号**<br>· 不同 → 照旧 `MAX+1`<br>⚠️ **只比最新一版，不做全量历史比对**（性能，见 `需求文档.md` §② 已否决项）。AC-18 专门钉死 A→B→A 不复用这一已知代价，**不要"顺手优化"成全量比对**。<br>🚫 批量路径（`writeVersionedGroupsBatch` / `writeVersionedMasterDetails`）必须按常量前缀**一次查完**最新版行集，禁逐组查。 |
| **B-14** | AC-16 | **核价转正去重**：`QuoteBackfillService.executeFlip` 在转正 UPDATE 之前加判定——若该组已存在 `is_current=true AND pending_quotation_id IS NULL` 且**版本号相同、内容逐字相同**的正式行，则 **DELETE 本单 pending 行**而非转正。<br>理由：两张单共用同一版本号后，先后转正会让 key 双双变成 `(..., 版本号, NULL)` 而撞 uq。内容既已在正式货架上，本单草稿无存在价值。<br>⚠️ 本项**推翻了立项初期「核价回填零改动」的判断**，`api.md` 已同步更新。<br>⚠️ 改动须保持 `repair-0727` 的 patch 语义与四路径判定不变（`AP-60`），只在 FLIP 路径内增加去重分支，**不许动 NOOP / REBUILD / OFFLINE 的判据**。 |
| **B-15** | AC-14, AC-16, AC-17, AC-18 | 上述三项的单元/集成测试。**AC-16 必须真跑两单先后核价通过的完整序列**，不许只测单张单。 |

---

## 自检（完成宣告必须附这一行，缺则视为未完成）

```
mvnw test 全绿 ✅；新增判据方法 SQL 条数与 N 无关（附 statement 计数证据）✅；
AC-1/AC-4 实跑 SQL 断言输出 ✅；PRICING 侧 A/B 逐字一致 ✅
```

## 已知陷阱（本仓历史教训，别重踩）

- **`grep` 空结果 ≠ 不存在**：本环境 `grep` 是 `ugrep -I` 别名，会把中文注释多的大源文件静默判为二进制返空。下「无引用 / 无写点」结论前必须用 `/usr/bin/grep -a` 复核（`CLAUDE.md` §5）。
- **worktree 里跑测试**：`mvnw` 在 `cpq-backend/` 不在仓库根；在主仓跑会测错树报假绿。
- **验证要下沉一层取证**：声称「没写行」就去查真实行数/真实 SQL，不能停在调用层日志与返回值上（`RECORD.md` 2026-08-29 规则提议）。
