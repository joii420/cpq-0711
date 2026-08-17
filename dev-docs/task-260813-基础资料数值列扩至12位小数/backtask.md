# backtask — 基础资料数值列扩至 12 位小数

> 对应 `需求文档.md`。**闸门 A 通过后**才可建 worktree 开工。
> 后端是本任务的全部改动面（前端/接口零改动，判定见 `fronttask.md` / `api.md`）。

---

## Task 划分（建议按此顺序，每个 Task 派全新子代理）

| Task | 内容 | 依赖 | 规模 |
|---|---|---|---|
| **T1** | Flyway 迁移 `V386`（85 列 ALTER + 视图重建） | — | S |
| **T2** | JPA 实体 `@Column` 同步（约 86 处） | T1 | M |
| **T3** | 导入 handler 硬编码 scale 同步（约 28 处） | T1 | M |
| **T4** | `PricingSheetRegistry` 声明式镜像同步（16 处） | T1 | S |
| **T5** | `MaterialBomMergeHandler` 补 scale 归一（缺陷 D-1） | T1 | S |
| **T6** | 精度一致性反射测试（AC-2 长期防漂移） | T2 | S |
| **T7** | 部署脚本同步（`cpq-init-empty-navicat.sql` + `0813-dbupdate.sql`） | T1 | S |

> T2/T3/T4 可并行，但**都依赖 T1 先定型**。T3 与 T4 是同一 scale 的两份副本，**必须同批评审**，防止只改一边。

---

## T1 · Flyway 迁移 V386

**文件**：`cpq-backend/src/main/resources/db/migration/V386__task0813_basicdata_scale12.sql`

**范式**：抄 `V385__task0810_formula_scale12.sql`——**只 `ALTER TYPE`，不 UPDATE、不重算、不补零**。

### 视图依赖处置（唯一需要特殊处理的列）

`element_bom_item.content` 被 `v_composite_child_elements` 引用（`ebi.content AS composition_pct`）。
PG 会拒绝直接 ALTER，必须 DROP → ALTER → CREATE。

**原视图定义**（`pg_get_viewdef` 取出，重建须逐字节等价）：

```sql
CREATE VIEW v_composite_child_elements AS
 SELECT ebi.hf_part_no,
    ebi.material_no AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    0 AS child_seq,
    ebi.seq_no,
    ebi.component_no AS element_name,
    ebi.content AS composition_pct,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM element_bom_item ebi
     LEFT JOIN material_master mm ON mm.material_no::text = ebi.material_no::text
     LEFT JOIN customer c ON c.code::text = ebi.customer_no::text
  WHERE ebi.system_type::text = 'QUOTE'::text
    AND ebi.hf_part_no IS NOT NULL
    AND ebi.is_current = true
    AND ebi.characteristic::text = ((
        SELECT max(ebi2.characteristic::text) AS max
          FROM element_bom_item ebi2
         WHERE ebi2.system_type::text = ebi.system_type::text
           AND ebi2.customer_no::text = ebi.customer_no::text
           AND ebi2.material_no::text = ebi.material_no::text));
```

⚠️ **用 `DROP VIEW`（不加 CASCADE）**。如果它报"还有其他对象依赖"，说明勘察遗漏，**停下来重新扫 `pg_depend`，不要顺手加 CASCADE**。
⚠️ 这是 V202 智能视图，属 `docs/三大核心模块基线.md` §5.1 锁定范围。重建后须 `pg_get_viewdef` 前后 diff（AC-8）。
⚠️ `composition_pct` 会跟着从 `n(18,6)` 变 `n(24,12)`——这是预期的。

### 迁移正文

以下 SQL 由 `information_schema` 直接生成（非手写），85 列：

```sql
-- task-0813: basic data numeric columns retain 12 decimal places.
-- Widen only; historical values are neither recalculated nor rewritten.
-- Integer capacity preserved: new_precision = old_precision - old_scale + 12.

DROP VIEW v_composite_child_elements;

ALTER TABLE auxiliary_energy
    ALTER COLUMN conversion_rate TYPE numeric(24,12),
    ALTER COLUMN non_production_energy_price TYPE numeric(24,12),
    ALTER COLUMN total_hours TYPE numeric(24,12),
    ALTER COLUMN working_hours TYPE numeric(24,12);

ALTER TABLE capacity
    ALTER COLUMN cost_ratio TYPE numeric(18,12),
    ALTER COLUMN default_defect_rate TYPE numeric(18,12),
    ALTER COLUMN fixed_cost TYPE numeric(24,12),
    ALTER COLUMN fixed_lead_time TYPE numeric(24,12),
    ALTER COLUMN variable_time TYPE numeric(24,12),
    ALTER COLUMN variable_time_batch TYPE numeric(24,12);

ALTER TABLE electricity_price
    ALTER COLUMN price TYPE numeric(24,12);

ALTER TABLE element_bom_item
    ALTER COLUMN base_qty TYPE numeric(24,12),
    ALTER COLUMN component_lead_time TYPE numeric(24,12),
    ALTER COLUMN composition_qty TYPE numeric(24,12),
    ALTER COLUMN content TYPE numeric(24,12),
    ALTER COLUMN defect_rate TYPE numeric(18,12),
    ALTER COLUMN fixed_scrap TYPE numeric(24,12),
    ALTER COLUMN lower_limit_pct TYPE numeric(18,12),
    ALTER COLUMN recovery_discount TYPE numeric(18,12),
    ALTER COLUMN scrap_batch TYPE numeric(24,12),
    ALTER COLUMN scrap_rate TYPE numeric(18,12),
    ALTER COLUMN upper_limit_pct TYPE numeric(18,12);

ALTER TABLE element_daily_price
    ALTER COLUMN raw_close TYPE numeric(26,12),
    ALTER COLUMN raw_high TYPE numeric(26,12),
    ALTER COLUMN raw_low TYPE numeric(26,12),
    ALTER COLUMN raw_open TYPE numeric(26,12),
    ALTER COLUMN raw_price TYPE numeric(26,12);

ALTER TABLE element_price
    ALTER COLUMN premium_price TYPE numeric(26,12);

ALTER TABLE element_price_strategy
    ALTER COLUMN premium TYPE numeric(26,12);

ALTER TABLE element_price_version_item
    ALTER COLUMN change_rate TYPE numeric(18,12),
    ALTER COLUMN current_price TYPE numeric(26,12),
    ALTER COLUMN previous_price TYPE numeric(26,12);

ALTER TABLE exchange_rate
    ALTER COLUMN rate TYPE numeric(24,12);

ALTER TABLE exchange_rate_v6
    ALTER COLUMN rate TYPE numeric(22,12),
    ALTER COLUMN ref_rate TYPE numeric(22,12);

ALTER TABLE fee_config
    ALTER COLUMN ratio TYPE numeric(18,12),
    ALTER COLUMN value TYPE numeric(24,12);

ALTER TABLE labor_rate
    ALTER COLUMN standard_labor_rate TYPE numeric(24,12);

ALTER TABLE material_bom_item
    ALTER COLUMN base_qty TYPE numeric(24,12),
    ALTER COLUMN component_lead_time TYPE numeric(24,12),
    ALTER COLUMN composition_qty TYPE numeric(24,12),
    ALTER COLUMN defect_rate TYPE numeric(18,12),
    ALTER COLUMN fixed_scrap TYPE numeric(24,12),
    ALTER COLUMN lower_limit_pct TYPE numeric(18,12),
    ALTER COLUMN material_ratio TYPE numeric(24,12),
    ALTER COLUMN net_weight TYPE numeric(26,12),
    ALTER COLUMN recovery_discount TYPE numeric(18,12),
    ALTER COLUMN rough_weight TYPE numeric(26,12),
    ALTER COLUMN scrap_batch TYPE numeric(24,12),
    ALTER COLUMN scrap_rate TYPE numeric(18,12),
    ALTER COLUMN upper_limit_pct TYPE numeric(18,12);

ALTER TABLE material_customer_map
    ALTER COLUMN exchange_rate TYPE numeric(22,12);

ALTER TABLE material_master
    ALTER COLUMN unit_weight TYPE numeric(24,12);

ALTER TABLE material_recipe_element
    ALTER COLUMN default_pct TYPE numeric(16,12),
    ALTER COLUMN max_pct TYPE numeric(16,12),
    ALTER COLUMN min_pct TYPE numeric(16,12);

ALTER TABLE packaging_consumable
    ALTER COLUMN usage_qty TYPE numeric(24,12);

ALTER TABLE plating_fee
    ALTER COLUMN defect_rate TYPE numeric(18,12),
    ALTER COLUMN plating_material_fee TYPE numeric(26,12),
    ALTER COLUMN plating_process_fee TYPE numeric(26,12);

ALTER TABLE plating_scheme
    ALTER COLUMN density TYPE numeric(24,12),
    ALTER COLUMN element_usage TYPE numeric(24,12),
    ALTER COLUMN plating_area TYPE numeric(24,12),
    ALTER COLUMN plating_thickness TYPE numeric(24,12),
    ALTER COLUMN surface_area TYPE numeric(24,12);

ALTER TABLE process_master
    ALTER COLUMN default_defect_rate TYPE numeric(18,12);

ALTER TABLE production_consumable
    ALTER COLUMN usage_qty TYPE numeric(24,12);

ALTER TABLE production_energy
    ALTER COLUMN batch_size TYPE numeric(24,12),
    ALTER COLUMN conversion_rate TYPE numeric(24,12),
    ALTER COLUMN round_step TYPE numeric(24,12),
    ALTER COLUMN working_hours TYPE numeric(24,12);

ALTER TABLE tooling_cost
    ALTER COLUMN conversion_rate TYPE numeric(24,12),
    ALTER COLUMN cycle_output TYPE numeric(24,12),
    ALTER COLUMN tooling_unit_cost TYPE numeric(24,12),
    ALTER COLUMN tooling_unit_price TYPE numeric(22,12);

ALTER TABLE unit_price
    ALTER COLUMN base_value TYPE numeric(24,12),
    ALTER COLUMN conversion_rate TYPE numeric(24,12),
    ALTER COLUMN cost_ratio TYPE numeric(18,12),
    ALTER COLUMN defect_rate TYPE numeric(18,12),
    ALTER COLUMN fetched_price TYPE numeric(24,12),
    ALTER COLUMN market_ref_price TYPE numeric(24,12),
    ALTER COLUMN material_fixed_increase TYPE numeric(24,12),
    ALTER COLUMN material_increase_ratio TYPE numeric(18,12),
    ALTER COLUMN premium_fee TYPE numeric(24,12),
    ALTER COLUMN pricing_price TYPE numeric(24,12),
    ALTER COLUMN recovery_discount TYPE numeric(18,12);

-- 重建视图（定义见本文档上方，须逐字节等价）
CREATE VIEW v_composite_child_elements AS ...;
```

> `production_energy.unit_price` **不在本迁移内**——DB 已是 `numeric(24,12)`，只是实体声明落后（T2 处理）。

### T1 自检

1. ❌ **禁止手工 `psql -f V386.sql`**（`CLAUDE.md` 明令）。放进 `db/migration/` 后 `touch` 一个 java 文件让 Quarkus dev 自己跑 Flyway。
2. `SELECT version, success FROM flyway_schema_history WHERE version='386'` → `success=t`
3. **DDL 后必须重启 Quarkus**（视图重建触发进程级缓存问题，见 `需求文档.md` §5.1）
4. 逐列断言 85 列类型（AC-1）
5. 迁移前后存量值 `stripTrailingZeros` 相等（AC-10）——**迁移前先 dump 一份基线**

---

## T2 · JPA 实体 `@Column` 同步

**范围**：`cpq-backend/src/main/java/com/cpq/basicdata/v6/entity/*.java`，约 86 处。

已知清单（grep `precision *=.*scale *=` 得到），按文件：
`AuxiliaryEnergy` / `Capacity` / `ElectricityPrice` / `ElementBomItem` / `ExchangeRateV6` / `FeeConfig` / `LaborRate` / `MaterialBomItem` / `MaterialCustomerMap` / `MaterialMaster` / `PackagingConsumable` / `PlatingScheme` / `ProcessMaster` / `ProductionConsumable` / `ProductionEnergy` / `ToolingCost` / `UnitPrice` + 非 v6 包的 `ElementDailyPrice` / `ElementPrice` / `ElementPriceStrategy` / `ElementPriceVersionItem` / `PlatingFee` / `MaterialRecipeElement` / `ExchangeRate`

**顺带修的漂移**（`需求文档.md` §6.2）：
- `ProductionEnergy.java:65` `unit_price` → `precision=24, scale=12`（DB 早就是这个，实体落后）
- `MaterialBomItem.java:63,66` `rough_weight` / `net_weight` `precision=18` → 目标 `26`

⚠️ **不要改 §3.5 排除清单里的列**（折扣率/税率/会计口径）。

---

## T3 · 导入 handler 硬编码 scale 同步

**范围**：约 28 处 `DecimalScale.at(x, N)` / `setScale(N)`。

已定位清单：

| 文件 | 行 | 现状 |
|---|---|---|
| `P01ElementPricingPriceHandler` | 50,51,57 | `pricing_price` 6 / `market_ref_price` 6 / `recovery_discount` 4 |
| `P02MaterialPricingPriceHandler` | 51,52,58 | 同上 |
| `P08CapacityHandler` | 85 | `standard_labor_rate` 6 |
| `P09EquipmentDepreciationHandler` | 44 | `setScale(6)` |
| `P10ProductionEnergyHandler` | 43 | `setScale(6)` |
| `P11AuxiliaryEnergyHandler` | 50 | `non_production_energy_price` 6 |
| `P12ToolingCostHandler` | 113 | `setScale(8)` |
| `P13ProductionConsumableHandler` | 53 | 耗材成本单价 6 |
| `P14PackagingConsumableHandler` | 50 | 包装成本单价 6 |
| `P15IncomingProcessFeeHandler` | 54,58 | 加工费 6 / `defect_rate` 4 |
| `P18SelfProcessAssemblyFeeHandler` | 50,54 | 加工费 6 / `defect_rate` 4 |
| `P22PlatingCostHandler` | 78,79,82 | 加工费 6 / 材料费 6 / `defect_rate` 4 |
| `P23OutsourceProcessFeeHandler` | 50 | 外加工费用 6 |
| `FinishedOtherMergeHandler` | 63,81 | `cost_ratio` 4 / 费用 6 |
| `IncomingOtherMergeHandler` | 71,91 | `cost_ratio` 4 / 费用 6 |

**改法**：字面量 `6` / `4` / `8` → `12`。

> 💡 **建议同时消除字面量**：这批数字散在 15 个文件里、与 T4 的 16 处重复，是本次问题的结构性来源。
> 可考虑抽一个 `V6ColumnScale` 常量类（或直接让 handler 读 `PricingSheetDef.decimalScales`），把两份副本收敛成一份。
> **但这是重构，会扩大改动面**——建议**本期先直接改字面量把功能做对**，收敛动作单独登记 BACKLOG。请在闸门 A 一并裁决。

---

## T4 · `PricingSheetRegistry` 声明式镜像同步

**文件**：`v6/maintenance/PricingSheetRegistry.java`，16 处 `.scale(col, N)`。

已定位行号：`45, 59, 73, 88, 105, 120, 135, 149, 175-176, 204, 238, 252, 266, 279, 296`

涉及列：`pricing_price` / `defect_rate` / `cost_ratio` / `composition_qty` / `base_qty` / `scrap_rate` / `fixed_scrap` / `content` / `standard_labor_rate` / `unit_price` / `non_production_energy_price` / `tooling_unit_cost` / `cycle_output` / `tooling_unit_price`

**消费方**：`PricingMaintenanceService:569`（`extractContentRow`）+ `:843`（`scaledString`）——即**维护页手工保存**路径。

⚠️ **这是与 T3 独立的第二条写路径。只改 T3 不改 T4，维护页保存仍会截断，且静默无错。**

---

## T5 · `MaterialBomMergeHandler` 补 scale 归一（缺陷 D-1）

**文件**：`v6/quote/MaterialBomMergeHandler.java:167-168`

现状（无归一，全精度进 `CHILD_CONTENT`）：
```java
c.put("rough_weight", row.getDecimal("材料毛重", "毛重"));
c.put("net_weight",   row.getDecimal("材料净重", "净重"));
```

改为：
```java
c.put("rough_weight", DecimalScale.at(row.getDecimal("材料毛重", "毛重"), 12));
c.put("net_weight",   DecimalScale.at(row.getDecimal("材料净重", "净重"), 12));
```

**同文件其余进 `CHILD_CONTENT`（第 59-66 行）的 decimal 列也须一并检查**：
`composition_qty` / `base_qty` / `scrap_rate` / `defect_rate` / `material_ratio` —— 确认是否同样缺归一。

> 扩到 12 位只是把阈值抬高，**Excel 若给 13 位仍会复发虚假升版**。归一动作本身不能省。

---

## T6 · 精度一致性反射测试（AC-2，长期防漂移）

新增测试：遍历 v6 实体的 `@Column(precision, scale)`，与 `information_schema.columns` 比对，不一致即 fail。

**价值**：本任务本身就是被"实体 ↔ DB 漂移"和"四份 scale 副本"坑出来的。这个测试能长期挡住同类漂移。

⚠️ 测试走 `test` profile（`10.177.152.12:5432/cpq_db`，**与 dev 库 `cpq_db_0724` 不同**）——须确认 V386 也在测试库跑过，否则测试会红。

---

## T7 · 部署脚本同步

1. `deploy/cpq-init-empty-navicat.sql` 同步至 V386
2. 新增 `deploy/0813-dbupdate.sql`（V385 → V386 内网增量），参照 2026-08-09 的 `deploy/0809-dbupdate.sql` 范式
3. 增量脚本里**标注锁表提示**：`ALTER COLUMN TYPE` 会重写表，生产库需评估窗口

---

## 🚫 N+1 自检声明（交付时必须填）

本任务改动以 DDL + 常量为主，**预期不新增任何循环查库**。
交付时按 `CLAUDE.md` 格式声明，例如：
> `N+1 自检：本次改动 0 处新增循环；T5 的 handler 改动在既有行循环内，仅做内存 setScale，无查库 ✅`

---

## 交付自检清单

- [ ] V386 `success=t`，85 列类型逐列断言通过
- [ ] DDL 后已重启 Quarkus（视图重建缓存问题）
- [ ] `pg_get_viewdef` 前后 diff 等价
- [ ] 实体 ↔ DB 反射比对测试通过（含 `production_energy.unit_price` 漂移已修）
- [ ] T3 的 28 处 + T4 的 16 处 grep 输出附在 PR 里逐条对照
- [ ] **AC-5 端到端**：12 位 Excel 导入 → 库内 12 位且逐位相等
- [ ] **AC-6 端到端**：维护页保存 12 位 → 库内 12 位
- [ ] **AC-7**：同文件连导两次，版本号不变
- [ ] 后端 `curl` 自检返 401（非 500）
- [ ] N+1 自检声明
- [ ] 报价/核价/详情三视图截图，确认显示仍 9 位（AC-9）
