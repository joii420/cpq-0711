# backtask · repair-0807 更正任务价格丢失与版本错乱

- 对应需求文档：`./需求文档.md`（FR/AC 编号以其为准）
- 接口契约：`./api.md`
- 分支：`fix/repair-0807-price-update-loss`
- 角色：`cpq-backend`

> 🔒 **改之前先读**：`MaterialVersionUpgradeService` 类头 javadoc（`:32-74` 八步 S0~S8 说明、`:80-109` MAPPER 三项配置硬约束）与 `PriceReconciler` 的 `reconcileRows` javadoc（`:199-213`「清值与撤锁必须同进同出」）。本次的核心动作就是**把 `PriceReconciler` 已经修好的口径补到升版侧**，不是发明新口径。

---

## 1. 数据模型变更

### T0 · Flyway `V384__repair0807_job_item_skipped.sql`

```sql
-- repair-0807 FR-4：SKIPPED 成为 job item 独立终态（原先被并进 SUCCESS，静默掩盖"整单没被更新"）
ALTER TABLE material_price_update_job_item DROP CONSTRAINT chk_mpuji_status;
ALTER TABLE material_price_update_job_item ADD CONSTRAINT chk_mpuji_status
  CHECK (status::text = ANY (ARRAY[
    'WAITING','RUNNING','SUCCESS','FAILED','CONFLICT','STALE','SKIPPED'
  ]::text[]));

ALTER TABLE material_price_update_job
  ADD COLUMN IF NOT EXISTS skipped_count integer NOT NULL DEFAULT 0;
```

- **不回填存量**：历史被静默跳过的 item 现在记的是 `SUCCESS`，不重跑无从判定，属 `BL-0148` 存量范围。
- ⚠️ 共享 dev server 看不到 worktree 里的迁移文件（任务平台规则 §7）：验证时把该文件 copy 到主仓触发一次，**合并前删掉副本**。
- 自检：`SELECT version, success FROM flyway_schema_history WHERE version='384'` → `success=t`。

---

## 2. 服务改动

### T1 · `MaterialVersionUpgradeService.upgradeComponentRows` —— S3a/S3b 写锁标记（FR-1）

方法签名加一个 `String versionLabel` 参数（由 `upgrade()` 一次查出后传入，**不得在循环里查 `ElementPriceVersion`** —— N+1）。

- S3a（`snapshot_rows` 的 `driverRow`，现 `:563-566`）写完价格/货币后追加：
  ```java
  driverRow.put("__priceLocked", true);
  driverRow.put("__priceVersion", versionLabel);
  ```
- S3b（`row_data` 手动行，现 `:588-591`）同样追加。
- `versionLabel` 取 `ElementPriceVersion.findById(targetVersionId).versionNo`；查不到时退化为 `targetVersionId.toString()`（与 `PriceReconciler:222-224` 同款兜底，不新造字面）。

### T2 · S4a/S4b 由「删键」改「覆盖」（FR-2）

**S4b**（`row_data` 非手动行，现 `:592-601`）三分支重写，逐条对齐 `PriceReconciler.reconcileRows:277-299`：

| 情形 | 动作 |
|---|---|
| 元素 ∉ 本版明细（`versionPrices.get(ec) == null`） | **一个字节都不碰**（沿用现状的 `continue`） |
| 元素 ∈ 明细且 `ep.price != null` | `put(价格键)` + `put(货币键)`（货币字段已配且 `ep.currency != null` 才写）+ `put(__priceLocked)` + `put(__priceVersion)` |
| 元素 ∈ 明细但 `ep.price == null` | `remove(价格键)` + `remove(货币键)` + **`remove(__priceLocked)` + `remove(__priceVersion)`** |

🚨 第三行的两个 `remove` 是 AC-5 的全部内容：**只删值不删锁 = 只读的空格 = 死格**（销售既拿不到系统价也填不进去）。`PriceReconciler:293-298` 有同款注释，照抄语义即可。

**S4a**（`cleanEditRowOverrides`，现 `:412-424`）同口径改：命中的 editRow 由 `values.remove(价格键/货币键)` 改为写入本版价 + 货币。

> 📌 **实现提示（省一次踩坑）**：`buildCardValues` 对 `editRows` 传的是 `null`，editRows 是在 S5 里**从 `row_data` 回种**的（`seedEditRowsFromRowData`）。所以 S4a 单独改**不足以**修好症状 —— 真正决定卡片值的是 S4b 改完的 `row_data`。两处都要改，但验证要盯 `row_data`。

### T3 · 缺冻结结构自愈（FR-3）

`upgrade()` 现 `:233-242`：

```java
JsonNode frozenTabs = loadFrozenQuoteTabsNative(q.id);
List<UpgradeResult.PriceBearingComponent> priceBearing = locatePriceBearingComponents(frozenTabs);

// repair-0807 FR-3：冻结结构缺失（复制单未保存过 / 历史单）→ 先补建再重试一次。
// 现网实测 33 张活跃 DRAFT 单无 QUOTE_CARD 结构，它们在每次价格更正里都被静默跳过。
if (priceBearing.isEmpty()) {
    boolean rebuilt = false;
    try {
        cardSnapshotService.ensureStructure(q.id);
        rebuilt = true;
    } catch (Exception e) {
        LOG.warnf("[b0-upgrade] li=%s 冻结结构补建失败: %s", lineItemId, e.getMessage());
    }
    if (rebuilt) {
        frozenTabs = loadFrozenQuoteTabsNative(q.id);
        priceBearing = locatePriceBearingComponents(frozenTabs);
    }
    if (priceBearing.isEmpty()) {
        result.status = UpgradeResult.Status.SKIPPED;
        result.message = rebuilt
            ? "冻结结构已补建，但仍无接价格策略的组件（三角色字段未配齐），无可升版内容"
            : "冻结结构缺失且补建失败，无法定位价格承载组件";
        return result;
    }
}
```

⚠️ `ensureStructure` 自带 `@Transactional`（REQUIRED）+ 内部全 try/catch。它会加入 `upgrade()` 的当前事务，`upsertStructure` 的写入随本事务提交/回滚 —— **dryRun 路径下补建也会被一起回滚**，这是对的（预算试算不该有副作用），不要为它单开 REQUIRES_NEW。

### T4 · SKIPPED 独立终态（FR-4）

- `MaterialPriceUpdateJobItem`：加 `public static final String SKIPPED = "SKIPPED";`
- `MaterialPriceUpdateJob`：加 `skippedCount` 字段（映射 `skipped_count`）；`recountFrom` 增加 `case SKIPPED -> skipped++`，并按下表定批次状态：

  | 条件 | 批次状态 |
  |---|---|
  | `failed==0 && conflict==0 && stale==0 && skipped==0` | `SUCCESS` |
  | `success==0 && stale==items.size()` | `STALE` |
  | `success==0 && skipped==0` | `FAILED` |
  | 其余（含"全成功但有跳过"） | `PARTIAL` |

  🔒 **"有跳过就不许报 SUCCESS"** 是 FR-4 的要害 —— 本次缺陷的传播路径就是"32/32 成功"骗过了财务。
- `PriceAdjustJobExecutionService.executeItem:335-353` 的 `switch`：把 `case SUCCESS, SKIPPED ->` 拆开，`SKIPPED` 单独分支写 `item.status = SKIPPED; item.errorCode = null; item.errorMessage = ur.message;`。

> ⚠️ `PriceAdjustBudgetService.runDryRunSnapshot:452-454` 也判了 `status == SUCCESS || status == SKIPPED`，那里是"试算取值"语义，**保持不变**（跳过的单没有调整后值可取，本就该走同一分支）。改 `executeItem` 时别顺手把这里也改了。

### T5 · `detail()` 的调整后小计与元素影响（FR-5 / FR-6）

新增包内可见方法（供本类调用，模式抄 `PriceAdjustBudgetService.runDryRunSnapshot`）：

```java
@ActivateRequestContext
@Transactional(Transactional.TxType.REQUIRES_NEW)
BigDecimal dryRunAdjustedSubtotal(UUID lineItemId, UUID targetVersionId,
                                   Map<String, ElementPrice> overridePrices) { ... }
```

- **两层 REQUIRES_NEW 都要补 `@ActivateRequestContext`** —— `upgrade(dryRun=true)` → S5 → `BomTreeRenderService.render()` → `DataLoader`（`@RequestScoped`）。`PriceAdjustBudgetService:437-447` 的 javadoc 记了这个教训（漏补时 272 次/34 项全抛 `ContextNotActiveException` 且被静默吞掉），照做。
- `overridePrices == null` → `upgrade()` 内部 S1 照常从库读版本明细（**三参路径逐位不变**）；非 null → 用传入的 map。为此给 `MaterialVersionUpgradeService` 加一个包内可见重载，把 S1 的 `loadVersionPrices(targetVersionId)` 参数化，默认行为零变化。

`detail()` 组装逻辑：

1. 定位 `isBasis` 的那一行 line item（`r.basisQuotationId` → 该单下 `productPartNoSnapshot = r.materialNo` 的行）。找不到 → 全部 `adjustedComputed=false`，不报错。
2. `adjustedTotal = dryRunAdjustedSubtotal(basisLineItemId, r.versionId, null)`；`qr.quoteSubtotalAdjusted = adjustedTotal`、`qr.adjustedComputed = true`（**仅该行**）。
3. `dto.unitPriceImpactTotal = adjustedTotal − basisLi.subtotal`。
4. 逐元素影响（FR-6 / D-5）：
   - `versionItems.size() == 1` → 该元素 `unitPriceImpact = unitPriceImpactTotal`；
   - `> 1` → 对每个元素 X 构造 `overridePrices`（X 用 `currentPrice`，其余元素用 `previousPrice`；`previousPrice` 为 null 的元素直接不放进 map = 不动该元素），跑一次 dryRun，`impact_X = subtotal_X − basisLi.subtotal`。
   - `usageQty = impact ÷ (currentPrice − previousPrice)`，`RoundingMode.HALF_UP` 保 6 位；分母为 0 / 任一侧 null → `usageQty = null`。
5. `Σ impact` 与 `unitPriceImpactTotal` 差 > 0.01 → `LOG.warnf("[price-adjust-review] review=%s 元素影响明细合计 %s 与整体 Δ %s 不等（卡片对价格非线性）", ...)`；**页面数值仍取整体 Δ**（D-6）。
6. `dto.elementImpactTotal` 改取 `unitPriceImpactTotal`（不再取 `col-default` 的 `diffAdjusted`）。现 `:202` 那行删掉。

### T6 · N+1 批量化（FR-7）

`detail():208-223` 与 `impact():262-276`：

```java
List<Object[]> rows = findAllActiveLines(customerNo, materialNo);   // 已是一条 SQL
List<UUID> qids = rows.stream().map(r -> (UUID) r[0]).distinct().toList();
List<UUID> lids = rows.stream().map(r -> (UUID) r[1]).distinct().toList();
Map<UUID, Quotation> qMap = Quotation.<Quotation>list("id in ?1", qids)
        .stream().collect(toMap(x -> x.id, x -> x));
Map<UUID, QuotationLineItem> liMap = QuotationLineItem.<QuotationLineItem>list("id in ?1", lids)
        .stream().collect(toMap(x -> x.id, x -> x));
```

- 空集合守卫：`qids` / `lids` 为空时**不发 SQL**（Panache 的 `in ?1` 传空集在 PG 上会生成 `in ()` 语法错）。
- `impact()` 的多 review 循环：把所有 review 的 `findAllActiveLines` 结果先汇总，再一次批量查（不要每个 review 各查一次）。
- 收尾加一行证据日志：`LOG.infof("[perf] review-detail N=%d sql=%d", rows.size(), 2)`（AC-13）。

---

## 3. 事务边界与并发

| 位置 | 边界 | 说明 |
|---|---|---|
| `upgrade()` | `@Transactional`（REQUIRED，不变） | S3/S4 的 `component_data` 写回自带 `row_version` 乐观锁，冲突 → `setRollbackOnly` + 返回 `CONFLICT`。本次新增的写键不改这个机制 |
| `ensureStructure`（T3） | 加入 `upgrade()` 当前事务 | dryRun 时随整体回滚，符合"预算无副作用" |
| `dryRunAdjustedSubtotal`（T5） | `REQUIRES_NEW` + `@ActivateRequestContext` | 每次独立回滚；多次调用串行，互不影响；**绝不可与 GET 请求共用事务**（`setRollbackOnly` 会毒化整个请求） |
| `executeItem` | `REQUIRES_NEW` + `@ActivateRequestContext`（不变） | 挂载位置原样不动（task-0806 硬约束 4） |

---

## 4. 性能要求

- `upgrade()` 单项耗时**不得因本次改动上升超过 5%**（新增的只是 jsonb 内存写键 + 最多 1 次 `ElementPriceVersion` 查询 + 缺结构时 1 次 `ensureStructure`）。对照基线：task-0806 实测 18 项 / 29.24s。
- `detail()` 从纯查询变为含 dryRun：单元素版本 ≤ 3s、三元素版本 ≤ 8s。超出则在 `test-report.md` 如实记录并提请裁决，**不许偷偷砍掉逐元素试算**。
- 🚫 **N+1 自检（交付前必跑，写进 test-report）**：逐个检查本次新增/改动的 `for` / `forEach` / `stream()` 循环体，确认里面没有 repository 调用、没有 `SqlViewExecutor.execute`、没有触发懒加载的关联 getter。声明格式：`N+1 自检：本次改动 N 处循环，均为纯内存运算，无查库 ✅`。

---

## 5. 自检清单（交付前逐项打勾，缺一不得报完成）

- [ ] `V384` → `SELECT version, success FROM flyway_schema_history WHERE version='384'` 返 `success=t`
- [ ] `touch` 一个 java 触发 Quarkus 重启 → `curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/price-adjust/reviews` 返 401（不是 500）
- [ ] `psql` 直查断言 AC-1 / AC-2 / AC-3 的三份 jsonb（**不听转述**）：`snapshot_rows` 的 `__priceVersion`、`row_data` 的价格键、`quote_card_values.resolvedRows` 的价格
- [ ] AC-6 场景：造一张无 `quotation_view_structure` 的活单跑 job，验结构已补建 + 价格已更新
- [ ] AC-7 场景：`SKIPPED` 落库且批次 `status='PARTIAL'`、`skipped_count>=1`
- [ ] 后端测试在 **worktree 内**的 `cpq-backend/` 跑 `./mvnw test`（跑主仓 = 测错树 = 假绿）
- [ ] N+1 自检声明一行
- [ ] `git branch --contains <commit>` 确认提交落在 `fix/repair-0807-price-update-loss` 而非 master

---

## 6. Task 列表

- [ ] **T0** Flyway V384（SKIPPED 约束 + skipped_count）
- [ ] **T1** S3a/S3b 写 `__priceLocked` / `__priceVersion`（FR-1）
- [ ] **T2** S4a/S4b 删键改覆盖 + 删值必撤锁（FR-2）
- [ ] **T3** 缺冻结结构 `ensureStructure` 自愈 + SKIPPED 消息区分两态（FR-3）
- [ ] **T4** `SKIPPED` 独立终态 + `skippedCount` + 批次 `PARTIAL` 判定（FR-4）
- [ ] **T5** `detail()` 判断依据单 dryRun 试算 + 逐元素影响 + 合计口径修正（FR-5/FR-6）
- [ ] **T6** `detail()` / `impact()` N+1 批量化 + perf 日志（FR-7）
- [ ] **T7** 单测：S4b 三分支（覆盖 / 不碰 / 删值必撤锁）、`recountFrom` 的 PARTIAL 判定、`usageQty` 除零守卫
