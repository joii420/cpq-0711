# test — 基础资料数值列扩至 12 位小数

> 对应 `需求文档.md` §7 的 AC-1 ~ AC-12。
> **本任务的核心测试是端到端的（T-3 / T-4 / T-5），不是断言列类型。**
> 列类型对了但 handler 常量没改，扩容一样是零效果——而且**静默**，不报错、不失败、只是精度没了。

---

## 0. 测试环境注意

⚠️ **后端自动化测试走 `test` profile → `10.177.152.12:5432/cpq_db`，与 dev 库 `cpq_db_0724` 不是同一个库。**
V386 必须在**两个库都跑过**，否则：
- 只跑 dev 库 → 反射比对测试（T-2）在测试库红
- 只跑测试库 → 手工验证（T-3/T-4）在 dev 库看不到效果

---

## T-1 · 列类型断言（AC-1）

```sql
SELECT table_name||'.'||column_name AS col,
       'numeric('||numeric_precision||','||numeric_scale||')' AS actual
FROM information_schema.columns
WHERE table_schema='public' AND data_type='numeric' AND numeric_scale IS NOT NULL
  AND numeric_scale < 12
  AND table_name IN ('material_bom_item','element_bom_item','unit_price','material_master',
                     'material_recipe_element','plating_scheme','plating_fee','production_energy',
                     'tooling_cost','capacity','auxiliary_energy','electricity_price','labor_rate',
                     'fee_config','exchange_rate','exchange_rate_v6','material_customer_map',
                     'element_daily_price','element_price','element_price_strategy',
                     'element_price_version_item','process_master','production_consumable',
                     'packaging_consumable')
ORDER BY 1;
```

**期望**：返回**空集**（§3.5 排除的列不在上述表内，故不会误报）。
非空即有列漏改——直接列出漏了哪些。

---

## T-2 · 实体 ↔ DB 一致性反射测试（AC-2，长期防漂移）

**新增测试类**，遍历 v6 实体的 `@Column(precision, scale)`，与 `information_schema.columns` 比对。

**必须覆盖的已知漂移**（`需求文档.md` §6.2）：
- `production_energy.unit_price`：实体曾声明 `(18,6)` 而 DB 是 `(24,12)` → 修复后须一致
- `material_bom_item.net_weight` / `.rough_weight`：实体曾 `precision=18` 而 DB `(20,6)` → 修复后须一致

**价值**：本任务正是被"四份 scale 副本 + 实体漂移"坑出来的。这个测试是唯一能长期挡住同类漂移的手段。

---

## T-3 · ⭐ 导入端到端保精度（AC-5，核心用例）

**这是验证本任务是否真的达成目标的唯一硬指标。**

### 步骤

1. 构造测试 Excel，「物料BOM」页签填入 **12 位小数**的材料净重，建议用**末位非零且不可被 double 精确表示**的值，例如：
   ```
   材料净重 = 91.768628123457
   材料毛重 = 2.365432109877
   材质占比 = 0.333333333333
   ```
   ⚠️ **注意 Excel 单元格本身是 IEEE-754 double**（约 15~17 位有效数字）。整数部分占位越多，可用小数位越少。
   `91.768628123457` 共 14 位有效数字，在 double 内可精确往返；**不要用整数部分很大的值做用例**，否则测的是 Excel 的限制而非本系统。

2. 导入。

3. 校验：
   ```sql
   SELECT net_weight, rough_weight, material_ratio,
          length(split_part(net_weight::text,'.',2)) AS net_decimals
   FROM material_bom_item
   WHERE material_no = '<测试料号>' AND is_current = true;
   ```

**期望**：
- `net_decimals = 12`
- 值与 Excel 源**逐位相等**（`91.768628123457` → 存储 `91.768628123457`，不是 `91.768628`）

**对照基线（修复前）**：当前库内实测全部恰好 6 位（`91.768628` / `43.492768` / `107.452720`），是被截断的痕迹。修复后应看到 12 位。

### 覆盖面要求

不能只测净重。**四族各挑至少 1 列**，且**必须覆盖两条 handler 路径**：
- 走 `DecimalScale.at` 的（如 `P01.pricing_price`、`P22.plating_process_fee`）
- 走 `setScale` 的（如 `P09`、`P10`、`P12`）

---

## T-4 · ⭐ 维护页保存端到端保精度（AC-6）

**这是与 T-3 独立的第二条写路径**（`PricingSheetRegistry` → `PricingMaintenanceService`）。
**只测 T-3 会漏掉整个 T4 同步点。**

### 步骤

1. 打开核价料号维护页，任选一个 DECIMAL 列，填入 12 位小数
2. 保存
3. 查库校验小数位 = 12 且逐位相等
4. 重新打开页面，确认**显示**为最多 9 位去尾零（`fronttask.md` F-A 的效果），但**回存值仍是 12 位**

⚠️ **第 4 步是最容易做错的地方**：若前端把格式化后的显示值写回 state 再提交，12 位就在前端丢了，与后端扩列直接冲突。必须验证「显示格式化只发生在渲染边界」。

---

## T-5 · ⭐ 重导不升版（AC-7，验 R-2 + 缺陷 D-1）

### 步骤

1. 记录导入前版本号：
   ```sql
   SELECT DISTINCT bom_version FROM material_bom_item WHERE material_no='<测试料号>';
   ```
2. 用 T-3 那份 **12 位** Excel 导入第一次 → 记录版本号
3. **同一份文件再导一次**
4. 比对版本号

**期望**：第 3 次导入后版本号**不变**（`UNCHANGED`），不产生新版本。

**为什么这条必测**：
- 验 R-2：扩列改变了 existing 的 scale，需实证 `norm()` 的 `stripTrailingZeros()` 确实让 `91.768628000000` 与 `91.768628` 判等
- 验缺陷 D-1：`MaterialBomMergeHandler` 补上 `DecimalScale.at(v, 12)` 后，虚假升版应消失

**修复前的对照**：当前（未修）用 12 位 Excel 重导**每次都会升版**——这本身就是待修缺陷。测试应先复现这个现象作为基线。

---

## T-6 · 存量数据未被改写（AC-10）

### 步骤

1. **迁移前**先 dump 基线：
   ```sql
   SELECT material_no, seq_no, net_weight::text, composition_qty::text
   FROM material_bom_item WHERE is_current = true ORDER BY 1,2;
   ```
2. 跑 V386
3. 再 dump 一次，比对：`stripTrailingZeros` 后**逐值相等**

**期望**：只有尾随零增加（`91.768628` → `91.768628000000`），无任何值被改写。

> ⚠️ 基线**必须在迁移前采集**。迁移后再想比对就没有基线了。

---

## T-7 · 视图重建等价（AC-8）

```sql
SELECT pg_get_viewdef('v_composite_child_elements'::regclass, true);
```

迁移前后各取一次，diff。**期望：除 `composition_pct` 的底层类型外完全等价**（`content` 从 `n(18,6)` → `n(24,12)`，视图列类型跟着变，这是预期的）。

⚠️ 这是 V202 智能视图，属 `docs/三大核心模块基线.md` §5.1 锁定范围。

⚠️ **DDL 后必须重启 Quarkus**，否则 `ImplicitJoinRewriter.tableColumnsCache` 可能缓存了视图消失瞬间的空集 → BNF 路径谓词不注入 → UI 出现「首值（共 N 项）」错乱。
**验证方式**：用一个含 BNF 路径的端点，期望返**单值**而非数组。

---

## T-8 · 显示位数未变（AC-9）

- [ ] `PrecisionPolicy.DISPLAY_SCALE` 仍为 9，`precision.ts DISPLAY_SCALE` 仍为 9
- [ ] 报价单编辑页截图
- [ ] 报价单详情页截图
- [ ] 核价单截图

**期望**：三视图显示位数与迁移前一致（最多 9 位去尾零）。

> 本任务**唯一允许的显示变化**是核价维护页从"6 位补零"变成"最多 9 位去尾零"（`fronttask.md` F-A 的效果）——这是修正，不是回归。

---

## T-9 · 下游计算影响面（验 R-4）

基础值精度提升后，**下游公式结果会变**——这是本任务的目的，但须确认变化范围可控。

- [ ] **已提交 / 已冻结单据**：读快照，**总价必须逐字节不变**
- [ ] **DRAFT 单据**：重新打开时可能因基数变化出现总价漂移。**须实测并记录漂移幅度**，确认量级在预期内（12 位 vs 6 位的差异应在 1e-6 量级，不应出现显著跳变）
- [ ] 若出现显著跳变 → **停下来查根因**，可能踩到别的隐藏截断

---

## T-10 · 回归：E2E 与既有测试

- [ ] 后端全量 `mvnw test`（须在 **worktree 的 `cpq-backend/`** 里跑，不是主仓）
- [ ] 前端 `npx tsc --noEmit` → 0 错误
- [ ] `EditableSheetTable.tsx` → Vite 200

> **E2E 是否强制**：本任务**未触及** `CLAUDE.md`「强制自检」第 5 项点名的那批协议文件（`useDriverExpansions.ts` / `QuotationStep2.tsx` / `ComponentDriverService.java` / `FormulaCalculationService.java` 等），**也不涉及字段类型变动（AP-44 不触发）**——本任务改的是列容量，不是 `field_type`。
> 但**含 Flyway 迁移 + 视图重建**，按第 5 项"模板 snapshot 数据迁移"的精神仍建议跑一次。
> ⚠️ 已知约束：E2E 夹具当前处于失效状态（[[BL-0158]]、`INDEX §0.0`），跑之前先确认夹具可用；不可用则按 task-0801 先例，用"单测全绿 + 三视图人工截图 + 实测精度证据"替代，并**在 `test-report.md` 里显式说明替代理由**。

---

## 测试优先级

| 级别 | 用例 | 理由 |
|---|---|---|
| **P0** | T-3、T-4、T-5 | 端到端，直接判定任务是否达成目标。**漏了这三条，前面全绿也可能是零效果** |
| **P1** | T-1、T-2、T-6、T-7 | 正确性与安全网 |
| **P2** | T-8、T-9、T-10 | 回归与影响面 |
