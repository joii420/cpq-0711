# backtask · repair-0808 —— 后端零改动的判定依据

> 规则要求：即使不改也要写清「为什么不改」+ 回归确认清单 + 二期触发条件（`CLAUDE.md`「宁可写细，不可留空槽」）。

---

## 1. 结论

**本次后端零改动。** 后端不是缺陷方，而是**对拍基准**。

---

## 2. 判定依据（逐条取证）

| # | 判据 | 证据 |
|---|---|---|
| B-1 | 后端建图已是列粒度（repair-0803 已修） | `CrossTabComponentOrder.buildComponentDeps` :182；调用点 `CardSnapshotService.java:2178`、`ConfigureSnapshotService.java:1294`、`TemplateService.java:1004` |
| B-2 | 后端对本报障单据算得**全对** | `cpq_db_0724` 查 `quotation_line_item.quote_card_values`（`QT-20260807-0146`）：`物料.subtotalByColumn = {材料成本 131.9016582451963, 材料损耗成本 5.525608362054, 原材料成本 847.90558141593, 材料价格 150.45243204895976, 铆钉额外费用 111.5175, 回收成本 0}`，`报价.subtotal = 137.5310666072503` = `quotation.total_amount` |
| B-3 | 后端对该模板**不报环** | 该模板已 `PUBLISHED`（`template.status`），发布期 `TemplateService.validateCrossTabRefs`（走 `buildComponentDeps`）通过；若后端仍是页签粒度，发布就会被拦 |
| B-4 | 前端用同一份 structure 复算，仅改组件求值序即与 B-2 逐值相同 | 见 `test-report.md` 的 A/B（诊断阶段已跑通：强制正确序 → 6 键全部命中后端值） |
| B-5 | 无表 / 视图 / Flyway / 端点变更 | 改动仅前端 2 个 `.ts(x)` 文件 |

---

## 3. 后端回归确认清单（主线亲验时执行，非本次开发内容）

- [ ] **不需要** `touch` java 重启 —— 本次无 java 改动
- [ ] **不需要** Flyway 校验 —— 本次无迁移文件
- [ ] 合并后 `curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/components` → `401`（应用在跑、鉴权正常），确认没有因合并误伤后端
- [ ] 报障单据打开一次后，`quote_card_values.subtotalByColumn` 与修复前**逐值不变**（前端改动不得反向污染后端快照）：
  ```sql
  SELECT t->>'tabName', t->'subtotalByColumn'
  FROM quotation_line_item li, jsonb_array_elements(li.quote_card_values->'tabs') t
  WHERE li.quotation_id='6d014a9a-fe27-432a-bce9-7f6c86c50775' AND t->>'tabName'='物料';
  ```
  > ⚠️ 这一条是**真正的后端风险点**：前端算序改变后，`saveDraft` payload 里的 `subtotal` 会从 0.103826 变成 137.531067。
  > 期望即如此（向后端对齐），但必须确认后端 `subtotalByColumn` 本身不被改写、`quotation.total_amount` 收敛到 `137.531067` 而非再次分叉。

---

## 4. 二期触发条件（出现以下任一情况，本文件作废，需新开后端任务）

1. 对拍中发现**后端**某条建图规则也是错的（例如 `is_tab_total` 判定、`refToCid` 三键解析在多实例 `__impN` 场景下与前端不一致）→ 改后端而非迁就前端。
2. 决定把建图规则**下沉为两端共享的单一来源**（如后端下发算好的页签序，前端直接消费）→ 属架构改造，需过 `cpq-architect`，另立 `task-`。
3. `BL-0059`（cross_tab 公式列真值不落 `row_data` 致按列折扣失效）排期时，会动 `ComponentDataEffectiveRows`，届时需重新评估本次算序改动与它的叠加效应。
