# 年降三 Sheet 落库统一设计（annual_discount 单表化）

- 日期：2026-08-03
- 状态：设计已确认，待写实现计划
- 涉及模块：基础资料 / 主数据（V6 表）、Excel 导入（报价侧）
- 关联文档：`docs/table/报价系统Excel导入落库方案.md` §8 / §15 / §19、`docs/table/报价系统版本号统一升版规则-设计方案.md`

---

## 1. 背景

报价导入的三个 Sheet —— 「来料年降」「组装加工费年降」「年降系数」—— 业务上是同一件事（同一批列：年降顺序、年降系数、单次固定年降值、货币、计价单位、降价次数），只是**年降挂在谁身上**不同。但当前落在两张表、三套语义：

| Sheet | Handler | 目标表 | 写入语义 | 版本化 | pending 隔离 |
|---|---|---|---|---|---|
| 来料年降 | `Q08IncomingAnnualDiscountHandler` | `unit_price`（`price_type=INCOMING_MATERIAL_REDUCTION`） | 组级整组替换 | ✅ | ✅ |
| 组装加工费年降 | `Q15AssemblyAnnualDiscountHandler` | `unit_price`（`price_type=COMPONENT_REDUCTION`） | 组级整组替换 | ✅ | ✅ |
| 年降系数 | `Q19AnnualDiscountHandler` | `annual_discount` | 行级 upsert（空值不覆盖） | ❌ | ❌ |

由此产生四个问题：

1. **建模冗余**：同一批列的读取/写入逻辑写了三遍。
2. **语义不一致**：前两者重导是"整组替换"，第三者是"空值不覆盖"；同一次导入两种行为。
3. **pending 隔离缺口**：`annual_discount` 没有 `pending_quotation_id` 列（V349 只给 7 张版本化表 + 占号表加了），「年降系数」在报价单 pending 期就直落正表并立即对所有报价单生效。
4. **客户维度缺失**：`uq_annual_discount = (biz_type, material_no, discount_strategy, discount_order)` 不含客户，同一销售料号导给不同客户会**静默互相覆盖**。

### 1.1 关键前提：当前三条路径都是「只写不读」

2026-08-03 全量勘察（库 `cpq_db_0724`）：

| 读路径 | 结果 |
|---|---|
| `component_sql_view.sql_template` 提到 `annual_discount` / `REDUCTION` / `年降` | 0 条 |
| PG 视图引用（`mc_view` / `tool_view` 命中的是列名 `cep.unit_price`，非表） | 0 个 |
| 组件 `data_driver_path` / 字段 `basicDataPath` 引用 | 0 个 |
| Java 侧读 `annual_discount` 表（`QuoteTableAxis`/`Capacity` 命中的是 `annual_discount_factor` **列**） | 0 处 |
| 存量 | `unit_price`：`COMPONENT_REDUCTION` 1 行、`INCOMING_MATERIAL_REDUCTION` 0 行；`annual_discount` 0 行 |

前端 `formatPathValue.ts` / `QuotationStep2.tsx` 中的 `INCOMING_ANNUAL_DOWN` 等只是 Sheet 类型的中文显示名，与存储无关。

**结论**：没有下游要改、没有存量要洗，此刻合并的迁移成本接近零，是动手的最佳时机。

## 2. 目标

1. 三个 Sheet 落到**同一张年降表**，用类型字段区分。
2. 写入语义归一（统一组级版本化 + pending 隔离）。
3. 补齐客户维度。
4. 为「年降参与报价/核价计算」预留可被 SQL 视图统一读取的模型（**本期不实现读取端**）。

## 3. 决策摘要

| 决策 | 结论 | 理由 |
|---|---|---|
| 表结构 | 方案 A：单一 `target_no` 泛化目标列，语义由 `discount_type` 决定 | 一个视图即可统一取三类；与 `unit_price.code` 按 `price_type` 变语义的现有惯例一致；加第四种年降只需加枚举值，不动 DDL |
| 表名 | 复用现有 `annual_discount`（0 行 0 读者，就地改造） | 改造成本等于新建，且省掉"旧表怎么办"的决策 |
| 客户维度 | 三类统一加 `customer_no` | 同一销售料号卖不同客户可以有不同年降；现状无客户列属建模缺陷 |
| 名称列 | **不存**，名称由视图 JOIN 主数据取（`material_recipe` / `process_master`） | 与现有「名走 `material_recipe` 兜底」惯例一致；避免主数据改名后年降表变陈旧 |
| 年降系数写入语义 | 从行级 upsert 改为**组级整组替换** | 三者归一的必然代价，且语义更可预期（本次导入的行集即当前状态） |
| 年降顺序 | 三类**统一必填**，Phase 1 拦截 | 它是组内唯一的行区分维度，为空则多行撞唯一键 |
| Handler 数量 | **保留三个** | 编排器按 `sheetName()` 一对一分发；合并要改分发协议，而共同逻辑已抽到共享写入器，重复已消除 |
| 本期范围 | 只做落库统一，不配组件页签 | 年降如何参与计算是独立的业务需求，应单独立项 |

### 3.1 被否决的备选

- **方案 B（每类一个专用列 `input_part_no` / `operation_no`）**：列名自解释，但跨类型统一取数要 `COALESCE`，新增类型要改 DDL。
- **方案 C（全并入 `unit_price`，年降系数也写成 `FINISHED_REDUCTION`）**：零新表、复用现成基建，但年降语义淹没在 40+ 列的大杂烩里，且与"单独存一张表"的意图相反。

## 4. 数据模型

### 4.1 DDL 改造（就地改造 `annual_discount`）

```sql
-- 判别列：现有 biz_type 的 CHECK 已是 (INCOMING, ASSEMBLY, FINISHED)，语义天然对应三个 Sheet
ALTER TABLE annual_discount RENAME COLUMN biz_type TO discount_type;
-- 取值（重建 CHECK）：
--   INCOMING_MATERIAL  ← 来料年降
--   ASSEMBLY_PROCESS   ← 组装加工费年降
--   FINISHED           ← 年降系数（整单级）

-- 新增列
system_type          VARCHAR(10) NOT NULL DEFAULT 'QUOTE'
customer_no          VARCHAR(20)          -- 导入时由系统提供，三类统一
target_no            VARCHAR(30)          -- 语义由 discount_type 决定，见 §4.2
seq_no               INTEGER              -- 项次
version_no           VARCHAR(20) NOT NULL
is_current           BOOLEAN     NOT NULL DEFAULT true
pending_quotation_id UUID
pending_supersedes   UUID[]

-- 删除列（无写入方、无读取方）
discount_strategy    -- 被 discount_type 取代（原恒写死 '来料年降'）
discount_base        -- 建表至今从未写入

-- 保留列
material_no（销售料号，主轴）/ discount_order / discount_ratio
/ fixed_discount_value / currency / unit / discount_times
```

### 4.2 `target_no` 语义

| `discount_type` | `target_no` 存什么 | 示例 |
|---|---|---|
| `INCOMING_MATERIAL` | 材质料号（Excel「投入料号」原样，不 resolve 不铸号） | `AgNi11#-Ⅰ` |
| `ASSEMBLY_PROCESS` | **解析后的真工序编号**（非 Excel 原文，可为空） | `Z100` |
| `FINISHED` | 恒 `NULL`（整单级年降无挂载目标） | `NULL` |

### 4.3 唯一键

与 `uq_unit_price` 同口径：groupKey ∪ 版本列 ∪ 组内行区分列。

```sql
DROP INDEX uq_annual_discount;
CREATE UNIQUE INDEX uq_annual_discount ON annual_discount(
    system_type, discount_type, material_no,
    COALESCE(customer_no, ''), COALESCE(target_no, ''),
    version_no, COALESCE(discount_order, 0));

CREATE INDEX ix_annual_discount_pending ON annual_discount(pending_quotation_id)
    WHERE pending_quotation_id IS NOT NULL;   -- 对齐 V349 给 7 张表建的那批
```

`discount_order` 保持现有的 `NOT NULL`（§3 已决定三类统一必填）；唯一键仍写 `COALESCE(discount_order, 0)`，纯粹为与 `uq_unit_price` 的表达式口径对齐，属防御性写法，不代表该列可空。

### 4.4 版本化语义

```
groupKey  = (system_type, customer_no, discount_type, material_no, target_no)
content   = (discount_order, discount_ratio, fixed_discount_value,
             currency, unit, discount_times, seq_no)
versionTriggerColumns = null   -- 任何内容变化即升版，与 Q08/Q15 现状一致
版本列    = version_no（VARCHAR 存数字，首版 "2000"，升版 max+1）
```

## 5. 列映射对照表

三个 Sheet 的表头差异（实测 `docs/table/报价测试数据/v2/报价系统模板0723.xlsx`）：

| 落库字段 | 来料年降 | 组装加工费年降 | 年降系数 |
|---|---|---|---|
| `discount_type` | `INCOMING_MATERIAL`（常量） | `ASSEMBLY_PROCESS`（常量） | `FINISHED`（常量） |
| `system_type` | `QUOTE`（常量） | `QUOTE`（常量） | `QUOTE`（常量） |
| `customer_no` | `ctx.customerNo` | `ctx.customerNo` | `ctx.customerNo` |
| `material_no` | 销售料号 | 销售料号 | 销售料号 |
| `target_no` | 投入料号（`exact`，必填） | 组装工序（Phase 1 解析，可空） | — （`NULL`） |
| `seq_no` | 项次 | 项次 | — （无此列，`NULL`） |
| `discount_order` | 年降顺序（必填） | 年降顺序（必填） | 年降顺序（必填） |
| `discount_ratio` | 年降系数（%） | 年降系数（%） | 年降系数（%/年） |
| `fixed_discount_value` | 单次固定年降值 | 单次固定年降值 | 单次固定年降金额 |
| `currency` | 货币 | 货币 | 货币 |
| `unit` | 计价单位 | 计价单位 | 计价单位 |
| `discount_times` | 降价次数 | 降价次数 | 降价次数 |
| 投入料号名称 | 不导入（名称 JOIN `material_recipe` 取） | — | — |

### 5.1 共享读取器可以一套 key 吃三个 Sheet

`SheetRow.getStr` 是 `contains` 匹配，因此表头差异不需要按 Sheet 分支：

| 字段 | 读取 key | 命中 |
|---|---|---|
| `discount_ratio` | `getDecimal("年降系数")` | `年降系数（%）` / `年降系数（%/年）` |
| `fixed_discount_value` | `getDecimal("单次固定年降")` | `单次固定年降值` / `单次固定年降金额` |
| `material_no` | `getStr("销售料号","宏丰料号","成品料号")` | 三 Sheet 均为 `销售料号`（兼容旧模板） |
| `discount_order` / `seq_no` / `discount_times` / `currency` / `unit` | `年降顺序` / `项次` / `降价次数` / `货币` / `计价单位` | 三 Sheet 同名 |

只有 `target_no` 需要各 Handler 自己声明。

### 5.2 相对现状的净变化

- **补回此前丢弃的列**：`seq_no`（来料年降 / 组装加工费年降的「项次」此前被丢弃）、`discount_times`（前两者的「降价次数」此前被丢弃）。
- **新增**：`customer_no`（年降系数此前无客户维度）。
- **仍不导入**：来料年降的「投入料号名称」（名称走 JOIN）。

## 6. 写入链路

### 6.1 Handler 结构

保留 `Q08` / `Q15` / `Q19` 三个 `SheetHandler`，各自只声明差异；共同的读列、groupKey 组装、写入器调用抽到新的 `AnnualDiscountWriter`。

| Handler | `discount_type` | `target_no` 来源 | Phase 1 必填校验 |
|---|---|---|---|
| Q08 来料年降 | `INCOMING_MATERIAL` | `row.exact("投入料号")` | 投入料号、年降顺序 |
| Q15 组装加工费年降 | `ASSEMBLY_PROCESS` | `ctx.sharedCache["assemblyProcessNo"]` 解析结果 | 年降顺序（工序仍可空） |
| Q19 年降系数 | `FINISHED` | 恒 `NULL` | 销售料号、年降顺序 |

### 6.2 退役的代码

- `AnnualDiscountRepository`（`upsertOne` / `upsertBatch` / `accDiscount`）整体删除 —— Q19 改走 `VersionedV6Writer` 后无任何调用方。
- `AnnualDiscountBatchUpsertEquivTest`（上述方法的等价护栏测试）一并删除。
- `AnnualDiscount` entity 按新 schema 更新：加 `systemType` / `customerNo` / `targetNo` / `seqNo` / 版本列，删 `discountStrategy` / `discountBase`，`bizType` → `discountType`。

### 6.3 `unit_price` 侧的处置

- `INCOMING_MATERIAL_REDUCTION` / `COMPONENT_REDUCTION` 两个 `price_type` **退役**，迁移中清掉存量（当前 1 行）。
- V297 的 `chk_unit_price_type` CHECK **保留这两个值不动** —— 动 CHECK 要 DROP/ADD constraint，收益为零，留着无害。
- 文档 `报价系统Excel导入落库方案.md` §8 / §15 标注退役并改指新表。

### 6.4 Phase 1 校验（零写库）

- **保留**：Q15 组装工序解析（`ProcessNoResolver` 两段匹配 → 组级作废 → fail-fast，见 repair-0727）。
- **新增**：三类统一的「年降顺序必填」校验。
- **保留**：Q08 投入料号必填、Q19 销售料号必填。

## 7. 登记点清单（7 处，漏一处静默失效）

`annual_discount` 进入「版本化 + pending」体系后必须登记的位置。**除第 1 项外，漏登记都不会报编译错，只在某条路径上静默失效**：

| # | 登记点 | 漏了会怎样 |
|---|---|---|
| 1 | `VersionedV6Writer.ALLOWED_TABLES` | 写入器抛「表未登记白名单」（唯一会吵的一处） |
| 2 | `VersionedV6Writer.SYSTEM_TYPE_SCOPED` | 护栏失效；将来核价侧接入时跨 QUOTE/PRICING 污染版本号 |
| 3 | `QuotePendingRewriter.WHITELIST_TABLES` | SQL 视图读年降表时 pending 行完全不可见，且拿不到 `__v6_id` 锚点 → 该页签无法回填 |
| 4 | `QuoteTableAxis` 新增 `Spec` + 进 `ALL_MANAGED_TABLES` / `SCAN_TABLES` | B5 回填与 pending 转正均由它驱动 → pending 行永远转不了正 |
| 5 | `V6QuotationCommitService.PENDING_TABLES` | 导入记录 → 报价单的 pending 过户漏这张表 → 行成孤儿 |
| 6 | `QuoteImportService.PENDING_TABLES`（`clearPreviousPending` 用，与 #5 同源但各自一份字面量） | 重导时上一次 pending 残留不清 → 行数翻倍 |
| 7 | `QuotationService.cleanupPendingV6Data` | 删报价单时年降 pending 行残留 |

`QuoteTableAxis.Spec` 登记内容：

```java
new Spec("annual_discount", "version_no",
    List.of("system_type", "customer_no", "discount_type", "material_no", "target_no"),  // axis
    List.of("discount_order", "discount_ratio", "fixed_discount_value",
            "currency", "unit", "discount_times", "seq_no"),                              // content
    null);   // 非主从表
```

## 8. 迁移

单支 Flyway 迁移 `V<n>__annual_discount_unify.sql`（**编号动手时再定** —— 共享 Flyway 历史是移动靶，当前库已应用到 V376）：

1. `annual_discount` DDL 改造（§4.1）+ 重建 CHECK + 重建唯一键 + pending 部分索引。
2. 清除 `unit_price` 中 `price_type IN ('INCOMING_MATERIAL_REDUCTION','COMPONENT_REDUCTION')` 的存量（当前 1 行）。

不需要数据迁移：`annual_discount` 空表，`unit_price` 存量 1 行且无读者，重导一次即可恢复。

**纪律**：不得手工 `psql -f`；靠 Quarkus dev 的 `migrate-at-start` 跑（见 CLAUDE.md「修改后强制自检」）。

## 9. 错误处理

全部沿用现有 fail-fast 口径，不发明新语义：

| 场景 | 行为 |
|---|---|
| 投入料号 / 销售料号 / 年降顺序 为空 | Phase 1 记错 → 整单拦截，零写库 |
| 组装工序填了但解析不到 | 按销售料号**整组作废** + 聚合成 1 条错误 → 整单拦截（保持 repair-0727 现状） |
| 同组内年降顺序重复 | 撞 `uq_annual_discount` → 导入报错（fail loud，不静默覆盖） |
| 同 groupKey 并发写 | `pg_advisory_xact_lock` 串行化，与其它 6 张表一致 |

## 10. 测试策略

- **单测**：三个 Handler 的 `discount_type` / `target_no` 映射；`AnnualDiscountWriter` 的 groupKey 组装；Phase 1 年降顺序必填。
- **集成测试**：导入 `报价系统模板0723.xlsx` → 断言三类各自落库正确（`version_no='2000'`、`is_current=true`、列值逐字段对齐 §5）。
- **升版测试**（最关键）：改一个系数重导 → `version_no='2001'` + 旧组 `is_current=false`。这是三者归一后唯一的写入语义。
- **pending 隔离测试**：pending 期导入 → 他单查不到 → commit 转正后可见。**这是 Q19 以前完全没有的能力，必须验。**
- **E2E**：不强制。本次不改字段类型，不触发 AP-44 协议联动；改动的 `QuoteImportService` / handler 不在 CLAUDE.md 强制 E2E 清单内。建议跑一次 `quotation-flow.spec.ts` 冒烟 —— 注意干净 master 上该 spec 本来就恒 3 个失败（夹具单缺产品分类），判回归必须 A/B 对照，勿误归因。
- **后端测试库**：`mvnw test` 走 `test` profile（`10.177.152.12:5432/cpq_db`），与 dev 库不同。

## 11. 明确不做（范围边界）

- **不配组件页签 / SQL 视图 / 模板绑定**：年降如何参与报价与核价计算是独立业务需求，需求尚不存在，应单独立项。
- **不动核价侧**：核价导入没有任何年降 Sheet。
- **不动 `unit_price` 的 CHECK 约束**：两个退役枚举值保留。
- **不做历史数据迁移**：无存量可迁。

## 12. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 7 个登记点漏登记 | 静默失效（pending 不可见 / 转不了正 / 残留孤儿） | 实现计划中逐点列为独立验收项，每项配断言 |
| 「年降系数」写入语义变更 | 若业务真依赖"分多次导入、每次只填部分列"，整组替换会清掉未填列 | 已与需求方确认采纳整组替换；文档同步说明 |
| 「年降顺序」改必填 | 若真实模板存在空值行会被拒导 | 当前存量 1 行且 `discount_order=1`，风险极低；错误信息需明确指出行号与列名 |
| 共享 Flyway 历史并发 | 迁移编号被其它会话抢占 | 动手时重新查 `flyway_schema_history` 最大值 |

## 13. 涉及文件（预估）

**后端**
- `cpq-backend/src/main/resources/db/migration/V<n>__annual_discount_unify.sql`（新增）
- `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q08IncomingAnnualDiscountHandler.java`
- `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q15AssemblyAnnualDiscountHandler.java`
- `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q19AnnualDiscountHandler.java`
- `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/AnnualDiscountWriter.java`（新增）
- `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/QuoteImportValidator.java`（年降顺序必填校验）
- `cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/QuoteImportService.java`（`PENDING_TABLES`）
- `cpq-backend/src/main/java/com/cpq/basicdata/v6/entity/AnnualDiscount.java`
- `cpq-backend/src/main/java/com/cpq/basicdata/v6/repository/AnnualDiscountRepository.java`（删除）
- `cpq-backend/src/main/java/com/cpq/basicdata/v6/versioning/VersionedV6Writer.java`（两处白名单）
- `cpq-backend/src/main/java/com/cpq/datasource/sqlview/QuotePendingRewriter.java`
- `cpq-backend/src/main/java/com/cpq/basicdata/v6/service/V6QuotationCommitService.java`
- `cpq-backend/src/main/java/com/cpq/quotation/service/backfill/QuoteTableAxis.java`
- `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`
- 测试：`Q08...Test` / `Q15...Test` / `Q19` 新增测试；删 `AnnualDiscountBatchUpsertEquivTest`

**文档**
- `docs/table/报价系统Excel导入落库方案.md`（§8 / §15 / §19 重写 + 通用规则补充）
- `docs/RECORD.md`（开发记录）

## 14. 顺带修正的文档漂移

勘察中发现落库方案文档与代码不一致，随本次改造一并修正：

1. §8 / §15 把「项次 → `seq_no`」标为 ✅，但代码从不写（注释注明"seq_no 丢列"）。本次改造后**真的会写**，文档随之成立。
2. §8 顶部「2026-06-17：投入料号为空时按名称匹配 / 自动生成 9 字头料号并登记料号表」已被 task-0717 推翻 —— 现在投入料号恒按材质料号处理，原始码直接当 key，不 resolve、不铸号、不登记 `material_customer_map` / `material_master`。
3. `annual_discount` 无 pending 隔离这一事实，文档全篇未提；本次改造后消除，文档补充说明。
