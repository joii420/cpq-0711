# 后端任务 · 核价导入数据版本升级与版本维护

> 负责人：cpq-backend｜优先级：P0｜前端：无改动（见 fronttask.md）｜接口契约：不变（见 api.md）
> **权威规则**：`版本升级规则文档.md` §五（轴/值逐表定稿）＋ `需求说明.md` §11（决策 C1–C12）。本文件是其后端落地版，冲突以规则文档为准。
> **料号语义基线**：task-0708 / V315（master 已交付）：`material_no`=销售料号、`production_no`=生产料号（描述列）、`material_part_no`=材质料号。**严禁**再引入 `sales_part_no`（已被 V315 反做删除）。

---

## 0. 一句话目标

把核价侧"导入即按销售料号升版"补全并统一：**每个 sheet 独立管版本、销售料号为总轴、(sheet,销售料号) 整组 multiset 比对（顺序无关）、任一值变→整组系统自增升版 + is_current 翻转**；顺带修复 `production_energy` 表结构根因。清空重导两个测试文件后数据正确落库、升版正确。

---

## 1. 升版模型（实现必须逐条对齐，细节见规则文档 §5.0）

1. **甲·系统自增**：忽略 Excel 版本列，`VersionedV6Writer` 内容指纹比对：首版 `2000`、内容变 `max+1`、旧组 `is_current=false`、新组 `is_current=true`。
2. **A·取最新**：不复现历史；「核价版本」P04 版本包不纳入。
3. **销售料号=版本总轴**；`production_no` 仅描述，**不进 groupKey、不进 content**。
4. **每 sheet 独立版本**；版本组 = `(sheet/price_type[, 固定cost_type], 销售料号锚)`。
5. **粗粒度整组**：一个 (sheet,销售料号) 的整批行为一组；子维度（来料料号/要素/工序/项次/模具号）入 **content**，**不进 groupKey**。
6. **顺序无关**：`VersionedV6Writer.multisetEqual` 已按 tally 比对，天然满足；**验收要专测"仅打乱行序重导→不升版"**。
7. **无触发列**：核价一律 `versionTriggerColumns=null`（= content 全量触发）。含 `capacity`/`labor_rate` 去掉现有触发列。

---

## 2. DB 变更（一支新 Flyway 迁移）

> 迁移号：起始建议 **V323**（V322 已被未跟踪孤儿占用）。**落地前先 `SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5` 取真实空号避让并发**（见记忆 `cpq-shared-flyway-history-churn`）。放进 `db/migration/` 后 touch 一个 java 文件让 Quarkus `migrate-at-start`；**禁止手工 `psql -f`**。

### 2.1 `production_energy` 表结构重构（根因，规则文档 §5.2）
```sql
ALTER TABLE production_energy ADD COLUMN IF NOT EXISTS price_type   VARCHAR(24);   -- ENERGY / DEPRECIATION
ALTER TABLE production_energy ADD COLUMN IF NOT EXISTS system_type  VARCHAR(16) DEFAULT 'PRICING';
ALTER TABLE production_energy ADD COLUMN IF NOT EXISTS unit_price   NUMERIC;       -- 合并后单价
-- ✅ 存量口径已定稿（需求说明 C14，2026-07-10）：当前为测试环境，采【甲·清空重导】，
--    不写存量拆分逻辑。加列后直接清表，再由核价 6.0 测试文件重导重建（折旧/能耗各写各行）。
TRUNCATE TABLE production_energy;
-- 清表后 DROP 两旧列：
ALTER TABLE production_energy DROP COLUMN IF EXISTS depreciation_unit_price;
ALTER TABLE production_energy DROP COLUMN IF EXISTS energy_unit_price;
-- 重建唯一键：加入 price_type（同料号同工序，折旧/能耗各一行）
```
> `production_energy.unit_price` 列与同名表 `unit_price` 不同命名空间，语义="单价（按 price_type 区分）"。核价树/`$view` 若曾读 `energy_unit_price`/`depreciation_unit_price`，**必须同步改读 `unit_price`+`price_type` 过滤**（grep 全工程 + 视图，见 §6 风险 3）。

### 2.2 `tooling_cost` 加版本列
```sql
ALTER TABLE tooling_cost ADD COLUMN IF NOT EXISTS calc_version VARCHAR(32);
ALTER TABLE tooling_cost ADD COLUMN IF NOT EXISTS system_type  VARCHAR(16) DEFAULT 'PRICING';
```

### 2.3 其余专用表补 `system_type`（满足批量写入器"跨组恒定前缀"约束）
```sql
ALTER TABLE labor_rate       ADD COLUMN IF NOT EXISTS system_type VARCHAR(16) DEFAULT 'PRICING';
ALTER TABLE auxiliary_energy ADD COLUMN IF NOT EXISTS system_type VARCHAR(16) DEFAULT 'PRICING';
-- exchange_rate_v6：全局无 material_no，见 §5 P03 处理（可不加，用逐组入口）
```
> `labor_rate`(version_no)、`auxiliary_energy`(calc_version)、`exchange_rate_v6`(version_no) 已有版本列 + is_current，仅需接写入器。

---

## 3. 写入器 `VersionedV6Writer` 改造

1. `ALLOWED_TABLES` 登记新表：`labor_rate, production_energy, auxiliary_energy, tooling_cost, exchange_rate_v6`。
2. `SYSTEM_TYPE_SCOPED` 增：`production_energy, auxiliary_energy, tooling_cost, labor_rate`（其 groupKey 必须含 `system_type`，写入器护栏会校验）。
3. `exchange_rate_v6` 不入 SYSTEM_TYPE_SCOPED（全局、无 material_no）；用逐组 `writeVersionedGroup`（表仅数行）或以 `base_currency` 为常量前缀，实现择一并注释。
4. 不改比对/升版核心算法（multiset/三分支/advisory lock 全部复用）。

---

## 4. 逐 handler 改造（核价 P* handler）

> 原则：**先 grep 每个 handler 现有写库路径**（多数费用类现走 `UnitPriceWriter` 覆盖式 upsert、`version_no` 恒 `V_DEFAULT`、无 is_current），改为构造 groupKey/content → `writer.writeVersionedGroups(...)`。groupKey/content **逐字对齐规则文档 §5.1**。`production_no` 作为**每行描述列**随 content 一起写、但**不列入 contentColumns**（不参与比对；参考 P06 `masterContent` 手法或写入器"写入但不比对"约定）。

### 4.1 A 组 · 全局（系统自增，无销售料号）
- **P01 元素核价价格 / P02 材料核价价格** → `unit_price`：groupKey=`(system_type, price_type, cost_type, code)`（code=元素代码/材料料号）；content 见 §5.1 A 组；忽略 Excel「价格版本」列。
- **P03 汇率** → `exchange_rate_v6`：groupKey=`(base_currency, target_currency)`；content=`rate, ref_rate, ref_fetch_rule, ref_source_url`；忽略「汇率版本」。
- **P21 电镀方案**：已版本化，保持现状（仅确认忽略 Excel 版本、系统自增，无需改）。

### 4.2 B 组 · unit_price 费用类（groupKey=`system_type, price_type[, 固定cost_type], 销售料号锚`）
- **销售料号锚按 price_type 识别**：来料类 P15/16/17 → `finished_material_no`；其余 P13/14/18/19/20/22/23 → `code`。
- **子维度入 content**：`code(来料料号)/operation_no/seq_no/cost_type(动态要素)/defect_rate` 等。
- P13/P14/P15/P18/P22/P23：各自一 sheet 一 price_type，按 §5.1 B 组逐行构组、`writeVersionedGroups`。
- ⚠️ **P16+P17 合并写、P19+P20 合并写**（关键，见 §6 风险 1）：两 sheet 同 price_type（INCOMING_OTHER / FINISHED_OTHER）→ 对同一销售料号是**同一个版本组**。**必须先把两 sheet 的行按 (price_type,销售料号) 合并成一组，再一次 `writeVersionedGroups`**（参考 `MaterialBomMergeHandler` 的合并再写模式）；**严禁**两 handler 各自独立 `writeVersionedGroups`（会互相覆盖组内行、双升版）。

### 4.3 C 组 · 专用表
- **P08 产能** `capacity`：**去掉 `VERSION_TRIGGER`**（改传 null），金额变也升版；groupKey 现状保留 `(system_type, material_no, resource_group_no)`。
- **P08 工时单价** `labor_rate`：**独立版本化**（不再借 capacity 版本号 upsert）。groupKey=`(system_type, material_no)`；content=`process_no, standard_labor_rate, currency, unit`；工序整批一组，`writeVersionedGroups`。
- **P09 折旧 + P10 能耗** `production_energy`（重构后）：两 sheet 各自 `price_type=DEPRECIATION`/`ENERGY`、**各写各行**；groupKey=`(system_type, material_no, price_type)`；content=`process_no, unit_price, currency, unit`；各自独立 `writeVersionedGroups`（重构后不再共用行，**无需**合并、无需 COALESCE 护栏）。
- **P11 辅助能耗** `auxiliary_energy`：groupKey=`(system_type, material_no)`；content=`process_no, non_production_energy_price, currency, unit`。
- **P12 模具工装** `tooling_cost`：groupKey=`(system_type, material_no)`；content=`process_no, seq_no, tooling_no, tooling_unit_cost, tool_life, cycle_output, tooling_unit_price, currency, unit, is_effective`；模具明细整批一组。

---

## 5. 中文列名/标识符

- groupKey/content 列名一律用**英文物理列名**（`VersionedV6Writer.safeIdent` 只允许 `[a-z_][a-z0-9_]*`）；读 Excel 中文列头用 `row.getStr("中文列名", 回退...)`（见记忆 `cpq-chinese-identifiers-need-ascii-alias`）。

---

## 6. 关键护栏 & 风险（实现/自检必读）

1. **P16+P17 / P19+P20 合并写**（§4.2）：不合并 = 组内行互相覆盖 + 双升版。合并键 `(price_type, 销售料号锚)`。
2. **is_current 唯一性**（记忆 `v6-child-multiversion-iscurrent-audit-scope`）：groupKey 从旧口径切到 (销售料号[,price_type]) 后，`is_current` FLIP 范围随之变；**穷举** handler + 任何直接 SQL 两侧，确认按新 groupKey 翻转，不产生同组多 current。
3. **production_energy 列合并的下游**：grep 全工程 + PG 视图/`component_sql_view` 里 `energy_unit_price` / `depreciation_unit_price` 的读取点，全部改成读 `unit_price` + `price_type` 过滤；schema DDL（DROP 列/改视图）后**必须 touch java 重启 Quarkus**（记忆/ CLAUDE.md「视图 DDL 后重启」）。
4. **顺序无关**：`multisetEqual` 已保证；但**构组时不要按行序生成受序影响的 key**；验收专测打乱行序重导不升版。
5. **批量写入器常量前缀**：新表 groupKey 必含 `system_type`（§2.3 已加列）；否则 `writeVersionedGroups` 抛"要求跨组恒定 groupKey 列"。
6. **禁 for 循环嵌套查库**（需求 §5）：一律走 `writeVersionedGroups`/`writeVersionedMasterDetails` 集合化入口，DB 往返与 group 数无关。
7. **production_no 不参与比对**：只写值、不进 contentColumns，否则生产料号一变就误升版（生产:销售=1:N）。
8. **exchange_rate_v6 前缀**：无 material_no，按 §3.4 择逐组入口或 base_currency 前缀。

---

## 7. 自检（交付前逐条跑，附证据）

1. **迁移**：`SELECT version,success FROM flyway_schema_history WHERE version='323'`（或实际号）→ success=t；`\d production_energy` 有 `price_type`+`unit_price`、无两旧单价列；`\d tooling_cost` 有版本列；4 表有 `system_type`。
2. **后端存活**：`curl -s -o /dev/null -w '%{http_code}\n' --noproxy '*' http://localhost:8081/api/cpq/components` → 401（非 500）。
3. **核价重导**（测试文件 `docs/table/核价测试数据/核价系统功能基础数据功能结构所需字段（6.0版） .xlsx`）：清空 → `POST /v6/pricing` → `sheets[]` 全成功、failedRows=0。抽查：
   - 各表 `material_no`=销售料号、`production_no`=生产料号；
   - `production_energy` 同料号同工序有 DEPRECIATION + ENERGY 两行、`unit_price` 各自正确；
   - `labor_rate/auxiliary_energy/tooling_cost` 有 is_current 行、版本=2000。
4. **升版正确**：同文件重导第 2 次 → 版本仍 2000、`is_current` 唯一（不累加、不多 current，不因未变而升版）。
5. **值变升版**：改某销售料号一处费用/单价重导 → 该 (sheet,销售料号) 组升 2001、旧行 is_current=false、新行 true；同料号其他 sheet 不动。
6. **顺序无关**：仅打乱某 sheet 行序重导 → **不升版**（版本、is_current 不变）。
7. **报价侧未受影响**：报价重导（`报价系统功能基础数据功能结构所需字段V3.xlsx`）仍 failedRows=0（回归）。

> 交付"完成"必须附一行自检声明（flyway success=t ✅ / 核价 failedRows=0 ✅ / production_energy 两 price_type 行 ✅ / 重导不升版 ✅ / 值变升版 is_current 唯一 ✅ / 乱序不升版 ✅）。无此声明=未完成。

---

## 8. 交付物清单

- [ ] Flyway 迁移：production_energy 重构（price_type+合并 unit_price+拆存量+改唯一键）、tooling_cost 加版本列、4 表加 system_type
- [ ] `VersionedV6Writer`：ALLOWED_TABLES / SYSTEM_TYPE_SCOPED 登记新表
- [ ] P01/P02/P03 → 系统自增（忽略 Excel 版本）
- [ ] P13/P14/P15/P18/P22/P23 unit_price 费用类 → writeVersionedGroups
- [ ] P16+P17、P19+P20 合并写为单版本组
- [ ] P08 capacity 去触发列 + labor_rate 独立版本化
- [ ] P09/P10 production_energy 重构后各自独立版本化
- [ ] P11 auxiliary_energy、P12 tooling_cost 版本化
- [ ] production_energy 列合并的下游视图/代码同步 + 重启 Quarkus
- [ ] `核价落库方案.md` §9/§10 及版本章节同步纠正（去 sales_part_no、production_energy 新结构）
- [ ] 自检证据（§7）齐全
