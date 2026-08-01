# 后端任务文档 — 公式计算精度优化（task-0801）

> 配套：`需求说明.md`（需求与澄清结论，冲突时以其 §11 为准）、`api.md`（精度契约）、`fronttask.md`（前端任务）
> 分支：`feat/task-0801-formula-precision`（worktree 隔离开发，禁止在主工作区/master 上改）
> 定稿：2026-08-01

---

## 0. 开工前必读（30 分钟，不可跳过）

| # | 文档 | 为什么 |
|---|------|--------|
| 1 | 本目录 `需求说明.md` §4.3 + §11 | 精度规则与全部澄清结论 |
| 2 | 本目录 `api.md` §1 / §5 | 精度契约 + 黄金用例（你的单测直接照抄） |
| 3 | `docs/方案制定前必读.md` §四 自检清单 | 强制自检 7 步 |
| 4 | `docs/RECORD.md` 2026-06-21 两条小数记录 | 本任务**推翻**的历史口径，理解它为什么曾被定成 4 位 |
| 5 | `CLAUDE.md`「修改后强制自检」 | 后端改动的自检命令与 Flyway 纪律 |

**本任务不触发 AP-44**（不改 `field_type` 枚举、不加字段类型），但**触发 E2E 强制项**（改了 `FormulaCalculationService.java` 等 CLAUDE.md 列出的触发文件）。

---

## 1. 核心设计：两条链路的精确边界

**务必先理解这张表再动手，它决定了"哪里必须改、哪里不许改"。**

| | 链路一（安全区） | 链路二（危险区） |
|---|---|---|
| **范围** | 单元格计算 → 列小计 → 页签小计 → **产品小计（单件级）** | **产品小计 → ×年用量 → 行合计 → Σ 整单总额 → 核价汇总** |
| **金额量级** | ≤ 10⁶（百万级） | 10⁸~10⁹（亿级，年用量几十万件） |
| **有效数字消耗** | ≤ 12 位（double 有 15~17 位，余量 3~5 位） | **15 位（已达 double 极限）** |
| **承载类型** | 跨层可继续用 `Double` / `Map<String,Double>` | **必须全程 `BigDecimal`，禁止 `.doubleValue()`** |
| **运算要求** | **单次求值与累加必须十进制精确**（内部用 BigDecimal 算完再存回 double） | 全程 BigDecimal |
| **改造策略** | 改**运算方式**，不改**承载类型** | 保持 BigDecimal，只去掉中间截断 |

> ⚠️ **不要把 `Map<String, Double> componentSubtotals` 改成 `Map<String, BigDecimal>`。**
> codegraph 实测该改动波及 **161 个符号**，而页签/产品小计属链路一，double 承载余量充足，
> 收益与风险严重不匹配。正确做法见 Task B4-2：**累加过程用 BigDecimal，存回 map 时才转 double**。

---

## 2. 任务清单总览

| Task | 名称 | 规模 | 依赖 |
|------|------|------|------|
| B1 | 精度策略基础设施（`PrecisionPolicy`） | S | 无 |
| B2 | 主引擎十进制化（`FormulaCalculator.ArithParser`） | M | B1 |
| B3 | JEXL 引擎十进制化（6 个求值点） | M | B1 |
| B4 | 去中间截断 + 封堵 double 漏点 | M | B2/B3 |
| B5 | 呈现边界规整为 6 位 | S | B1 |
| B6 | 导出改 6 位（含 POI 限制处理） | S | B1 |
| B7 | Flyway 迁移 12 个金额列 | S | 无 |
| B8 | 单元测试（黄金用例 + 链路基线） | M | B1~B6 |
| B9 | 自检与交付证据 | S | 全部 |

**建议顺序**：B1 → B7（先落 DDL，避免后面反复重启）→ B2 → B3 → B4 → B5 → B6 → B8 → B9。

---

## Task B1：精度策略基础设施

### 目标
把散落全工程的精度决策收敛到**一个类**，杜绝以后再出现"某处改了某处没改"。

### 新建文件
`cpq-backend/src/main/java/com/cpq/common/PrecisionPolicy.java`

### 必须提供的 API

```java
public final class PrecisionPolicy {
    /** 呈现精度：落库 / API / 显示 / 导出 四个边界统一规整到 6 位。 */
    public static final int DISPLAY_SCALE = 6;
    /** 除法中间精度：无限小数（1/3）的落点，远高于呈现精度以避免中间损失。 */
    public static final int DIVISION_SCALE = 12;
    /** 统一舍入方式。 */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    /** 计算用 MathContext（34 位有效数字，等价 DECIMAL128）。 */
    public static final MathContext MC = MathContext.DECIMAL128;

    /** 规整到呈现精度（null 安全）。 */
    public static BigDecimal round(BigDecimal v);
    /** 精确除法：除数为 0 返回 ZERO（与现有 NaN/Infinite→0 语义一致，见 api.md G-9）。 */
    public static BigDecimal divide(BigDecimal a, BigDecimal b);
    /** double → BigDecimal 的唯一入口：必须走 valueOf（最短十进制还原），禁止 new BigDecimal(double)。 */
    public static BigDecimal of(double d);
    public static BigDecimal of(Object v);   // Number / String / null 统一转换，无法解析返回 ZERO
    /** BigDecimal 精确累加。 */
    public static BigDecimal sum(Iterable<BigDecimal> values);
}
```

### 要点与坑

1. **`of(double)` 必须用 `BigDecimal.valueOf(d)`，绝不能用 `new BigDecimal(d)`**
   —— 后者会把 `0.1` 变成 `0.1000000000000000055511151231257827…`，误差当场引入。
2. `round()` 只在**呈现边界**调用；计算过程中调用 = 违反本任务目的，评审会打回。
3. `divide()` 必须带 `MC` 或显式 scale，否则 `1/3` 抛 `ArithmeticException`（风险 R-4）。
4. 类必须 `final` + 私有构造，纯静态工具。

### 同步改造
- `com/cpq/common/NumberFormatUtil.java`：`COMPUTED_FALLBACK` 改为引用 `PrecisionPolicy.DISPLAY_SCALE`，
  **不再自持字面量 4**；同步更新类注释（现注释描述的是被推翻的 4 位策略，见 `需求说明.md` §9.1）。

### 验收
- [ ] `PrecisionPolicy` 单测覆盖：`of(0.1)` = `0.1`；`divide(1,3)` 不抛异常且 ≥12 位；`round(1.0000005)` = `1.000001`
- [ ] 全工程 `grep "new BigDecimal("` 逐点确认无 `new BigDecimal(<double 变量>)` 用法

---

## Task B2：主引擎十进制化（`FormulaCalculator.ArithParser`）

### 目标
把卡片值/快照的服务端主算引擎从 `double` 递归下降解析器改成 `BigDecimal`。

### 涉及文件
`cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java`

### 改动点

| 行 | 现状 | 改成 |
|----|------|------|
| `:1961-2030` `ArithParser` 类 | `double parse/expr/term/factor/number` | 全部改 `BigDecimal`；`*` 用 `multiply(MC)`；`/` 走 `PrecisionPolicy.divide()` |
| `:42` `ZERO4 = BigDecimal.ZERO.setScale(4)` | 带 scale 的零 | `BigDecimal.ZERO`（不带 scale，避免把 scale 传染给整条链） |
| `:85-87` `double result = parse(); … setScale(4)` | double + 4 位截断 | 直接返回 `BigDecimal`，**不 setScale** |
| `:573` `computeTabSubtotal` 返回前 `setScale(4)` | 中间截断 | 去掉（链路一内部，结果由调用方在边界规整） |
| `:627` `out.put(sf, BigDecimal.valueOf(sum).setScale(4))` | 同上 | 去掉 `setScale`；`sum` 改 BigDecimal 累加 |
| `:867` `double val = evaluateExpression(expr, ctx).doubleValue()` | 求值后落回 double | **保留 double 承载（链路一）**，但改为 `PrecisionPolicy` 转换；`results`/`ctx.fieldValues` 的 `Map<String,Double>` 类型**不改** |

### 要点与坑

1. **除零语义不能变**：现有 `Double.isNaN(result) || isInfinite(result) → ZERO4`。
   改 BigDecimal 后除零会抛 `ArithmeticException`，必须在 `PrecisionPolicy.divide()` 内捕获并返回 `ZERO`，
   保持"除零 = 0 不报错"的既有语义（`api.md` G-9）。
2. **一元负号 / 运算符优先级不得改变**（`api.md` G-12/G-13）——
   只换数值类型，**不重写解析逻辑**。改完对照原实现逐行 diff。
3. **全角运算符转换保留**：`×→*`、`÷→/`（`api.md` G-14）。
4. `ZERO4` 改名为 `ZERO` 时注意它在文件内的所有引用点（grep 确认全改）。
5. `RowContext.quotationFields` / `productAttributes` 的 `Map<String,Double>` **保持不变**（链路一，见 §1）。

### 验收
- [ ] `api.md` G-1、G-2、G-3、G-4、G-9、G-12、G-13、G-14 单测全绿
- [ ] `FormulaCalculatorCrossTabTest` / `FormulaCycleDetectionTest` 原有用例全绿（无语义漂移）

---

## Task B3：JEXL 引擎十进制化（6 个求值点）

### 目标
JEXL **默认把数字字面量解析成 `Double`**，仅设置 `MathContext` 不生效（风险 R-3）。
必须**同时**做两件事：① 引擎配 BigDecimal 算术 ② 表达式里的数字字面量加 `B` 后缀。

### 6 个求值点（一个都不能漏）

| # | 文件 | 行 | 用途 |
|---|------|-----|------|
| 1 | `engine/formula/FormulaCalculationService.java` | `:25` 建引擎 / `:223` 求值 | 报价卡片行公式 |
| 2 | `formula/FormulaEngine.java` | `:58/:65` 建引擎 / `:113`、`:289` 求值 | 通用引擎 + 函数库 + `$view` |
| 3 | `template/service/TemplateFormulaService.java` | `:104` `rowJexl` | 模板 Excel 视图公式 |
| 4 | `quotation/service/ExcelViewService.java` | `:625-627` | 报价单 Excel 视图 |
| 5 | `quotation/service/tabjoin/TabJoinPlanEvaluator.java` | `:76-77`、`:200` | 页签连表公式 |
| 6 | `costing/service/CostingSheetService.java` | `:275-276` | 核价表公式 |

### 实现方式

**Step 1 — 在 `PrecisionPolicy` 旁新增 JEXL 工厂**（避免 6 处各写一遍）：

```java
// 建议放 com/cpq/common/DecimalJexl.java
public static JexlEngine newEngine() {
    return new JexlBuilder()
        .strict(false).silent(true).cache(512)
        .arithmetic(new JexlArithmetic(false, PrecisionPolicy.MC, PrecisionPolicy.DIVISION_SCALE))
        .create();
}
```
> JEXL 版本已确认为 **commons-jexl3 3.3**，`JexlArithmetic(boolean, MathContext, int)` 与 `B` 后缀字面量均可用。

**Step 2 — 表达式拼接处给数字字面量加 `B` 后缀**：

| 文件 | 拼接函数 | 改法 |
|------|---------|------|
| `FormulaCalculationService` | `toNumericString()`（`:238`附近）、`buildJexlExpression` 内 `subtotal.toPlainString()` / `gvVal.toPlainString()` | 返回值追加 `"B"` |
| `TemplateFormulaService` | `toNumericLiteral()` | 同上 |
| `ExcelViewService` / `TabJoinPlanEvaluator` / `CostingSheetService` / `FormulaEngine` | 各自的变量替换处 | 同上 |

**Step 3 — 求值结果处理**：`evaluate()` 返回的 `Object` 现在会是 `BigDecimal`，
原有 `if (result instanceof Number) new BigDecimal(result.toString())` 要改为**优先直接强转 BigDecimal**，
避免多一次字符串往返；且**去掉 `.setScale(4)`**（如 `FormulaCalculationService:227`）。

### 要点与坑

1. **`B` 后缀必须加在纯数字字面量上**，不能加在已经是标识符/函数名的位置 —— 拼接函数是唯一入口，逐个核对；
2. **加完必须验证**：写一条断言 `evaluate("0.1B+0.2B")` 返回 `BigDecimal("0.3")`；
   若只改了 `MathContext` 没加后缀，这条会得 `0.30000000000000004`，**即为 R-3 未修复**；
3. `FormulaEngine:289` 是**函数参数**的求值（`rewriteFunctionsForJexl`），同样要走新引擎；
4. `formula/function/**` 下的函数实现（`RoundFunction` / `FloorFunction` / `CeilFunction` /
   `TaxExcludedFunction`）**已是 BigDecimal，语义不改**；但 `TaxExcludedFunction:69` 的
   `divide(divisor, 8, HALF_UP)` 保持 8 位（业务口径，不属本次范围，**不要动**）；
5. `IF / COALESCE / NULLIF / ABS` 的行内改写逻辑**不改语义**，只换算术类型。

### 验收
- [ ] 6 个求值点**逐点**各有一条 `0.1+0.2 = 0.3` 断言（6 条，缺一不可）
- [ ] 各求值点原有单测全绿
- [ ] grep 确认无遗漏：`grep -rn "JexlBuilder" cpq-backend/src/main/java` 命中数 = 6，且全部走 `DecimalJexl.newEngine()`

---

## Task B4：去中间截断 + 封堵 double 漏点

### B4-1 去掉中间截断（链路一 + 链路二）

| 文件:行 | 现状 | 改法 |
|---------|------|------|
| `card/ComponentDataEffectiveRows.java:145-146` | `setScale(4)` / `ZERO.setScale(4)` | 去 setScale，返回原值 / `BigDecimal.ZERO` |
| `CostingSubtotalUtil.java:47` | `unit.multiply(qty).setScale(4)` | 去 setScale（**链路二起点，必须保精度**） |
| `LineDiscountService.java:76/107/113/115/116` | 5 处 `setScale(4)` | **全部去掉**；`:99` 的 `divide(e0, 8, HALF_UP)` 提到 `DIVISION_SCALE`(12) |
| `CostingFreezeService.java:166` | `total.setScale(4)` | 去掉（落库前由 B5 统一规整） |
| `CostingVersionService.java:444` | `total.setScale(4)` | 同上 |
| `QuotationService.java:664/706/2385/1943` | `divide(new BigDecimal("100"), 4, HALF_UP)` | scale 改 `DIVISION_SCALE`(12)，落库前由 B5 规整 |
| `QuotationService.java:850` | `lineSum.setScale(4)` | 改 `PrecisionPolicy.round()`（这是落库边界，规整到 6 而非去掉） |

### B4-2 封堵 double 漏点（**本任务最容易漏的地方**）

**已定位的漏点**：

| 文件:行 | 问题 | 改法 |
|---------|------|------|
| `CardSnapshotService.java:2985-2995` | `colSum += d` 是 **double 累加**，几十行累加后再 `setScale(4)` | 改为 `BigDecimal colSum = ZERO; colSum = colSum.add(PrecisionPolicy.of(val));`，**累加过程精确**；存回 map 时才 `.doubleValue()`（map 类型不改，见 §1） |
| `CardSnapshotService.java:3002-3004` | `totalSum` 同上 + `.setScale(4).doubleValue()` | 同上；去掉 `setScale(4)` |
| `FormulaCalculator.java:867` | `evaluateExpression(...).doubleValue()` | 保留（链路一承载），但确保求值内部已精确 |

**强制审计动作**（不做 = 验收不通过）：

```bash
# 1. 全工程 doubleValue 审计 —— 逐点判定属链路一还是链路二
/usr/bin/grep -rn -a "doubleValue()" cpq-backend/src/main/java --include=*.java

# 2. 残留 setScale(4) 审计 —— 改完后应只剩「明确不属本次范围」的点
/usr/bin/grep -rn -a "setScale(4" cpq-backend/src/main/java --include=*.java

# 3. double 累加审计 —— 找 "+=" 作用在金额变量上的写法
/usr/bin/grep -rn -a -E "(Sum|Total|Amount|subtotal)\s*\+=" cpq-backend/src/main/java --include=*.java
```

**交付时必须附上这三条命令的输出**，并对每一条命中给出"改了 / 不属本次范围（写明理由）"的结论。

### 明确**不改**的点（避免过度改造）

| 文件:行 | 理由 |
|---------|------|
| `formula/function/math/RoundFunction.java:38` | `ROUND(x, n)` 是**用户显式指定位数**的业务函数，不是系统精度 |
| `FloorFunction:30` / `CeilFunction:30` | 同上，语义即取整 |
| `formula/function/business/TaxExcludedFunction.java:69` | 8 位是既定业务口径 |
| `basicdata/v6/pricing/P09/P10/P12Handler` | **导入侧写库精度**，属基础资料（取数值，类别 B），不动 |
| `elementprice/**`（`StrategyService` / `PriceTableService` / `PriceImportRowWriter`） | 元素价格 = 取数值（类别 B），不动 |
| `MaterialRecipeImportService:150` | 校验提示文案的显示位数，与计算无关 |
| `CostingSheetService:172/194` | 占比/百分比展示（`… + "%"`），非金额计算 |
| `configure/FingerprintCalculator` 等指纹类 | 指纹哈希，改精度会导致全量指纹失效 |
| 6 个费率列相关代码 | 输入值（类别 C），保持 2 位 |

---

## Task B5：呈现边界规整为 6 位

### 目标
计算过程不截断后，必须在**四个边界**兜住，否则 JSON 里会冒出 `0.30000000000000004` 这类尾巴。

### 改动点

| 边界 | 文件 | 改法 |
|------|------|------|
| **落库** | `QuotationService`（`totalAmount`/`originalAmount` 写入处 `:664/706/850/1943/2385`）、`LineDiscountService`（9 个字段赋值处）、`CostingFreezeService:166`、`CostingVersionService:444`、`CardSnapshotService`（写 `subtotal` / JSONB 数值处） | 赋值前统一 `PrecisionPolicy.round(v)` |
| **API 返回** | `QuotationDTO.from()`、`loadLineItems()`、核价 DTO 组装处 | 同上；**注意 JSONB 内数值要按字段类型区分**（计算列规整、取数列保持原值，见 `api.md` §3.3） |
| **显示格式化** | `common/NumberFormatUtil.java` | `COMPUTED_FALLBACK` → 引用 `PrecisionPolicy.DISPLAY_SCALE`（=6）；注释同步更新 |
| **Excel 视图** | `quotation/service/ExcelViewService.java:934` | `COMPUTED_FALLBACK_DECIMALS` 4 → 引用 `PrecisionPolicy.DISPLAY_SCALE` |

### 坑

- **JSONB 一刀切规整会压坏取数列**：`row_data` / `snapshot_rows` 里同时存计算列与取数列，
  必须按 `field_type` 判定（`api.md` §1.1 类别 A/B）。一刀切 = 违反 AC-8，验收打回。
- 规整**只在写出瞬间做一次**，不要在中途反复 round（round 两次不等于 round 一次，会引入二次舍入偏差）。

---

## Task B6：导出改 6 位

### 改动点

| 文件:行 | 现状 | 改法 |
|---------|------|------|
| `QuotationExportService.java:264` | `setScale(2)` | `PrecisionPolicy.round()` + 去尾零（复用 `NumberFormatUtil.format`） |
| `QuotationExportService.java:380` | `originalAmount` 2 位，默认 `"0.00"` | 同上；默认值改 `"0"` |
| `QuotationExportService.java:382` | `totalAmount` 2 位，默认 `"0.00"` | 同上 |
| `ExcelViewService.java:920-924` | POI 数值 + 格式串 | 格式串按 6 位（`numberStyleFor(..., 6)`），显示口径与 UI 一致 |

### ⚠️ 已知限制：Excel 单元格只有 15 位有效数字

`ExcelViewService:924` 是 `cell.setCellValue(num.doubleValue())` —— POI 数值单元格底层是 IEEE754 double，
**Excel 本身只保证 15 位有效数字**。亿级金额（9 位整数）+ 6 位小数 = 15 位，**正好触顶**。

**处理约定**：金额类单元格写入前判断有效数字位数，
- ≤ 15 位 → 照常写数值（保留 Excel 的可计算性）；
- \> 15 位 → **写字符串**（`setCellValue(String)`）并在该列加注释说明，宁可失去可计算性也不静默丢精度。

实现时把这个判断收进一个 helper，**不要散落**。PDF / HTML 导出是字符串渲染，无此限制。

---

## Task B7：Flyway 迁移 12 个金额列

### 迁移文件
`cpq-backend/src/main/resources/db/migration/V<下一个可用号>__widen_amount_columns_to_scale6.sql`

> ⚠️ **版本号是移动靶**：共享库的 `flyway_schema_history` 被多会话并发写（见记忆 `cpq-shared-flyway-history-churn`）。
> 建号前先查 `SELECT max(version) FROM flyway_schema_history;`，且**迁移一旦应用就禁止改名改号**。

### SQL

```sql
-- 12 个金额列：numeric(18,4) → numeric(20,6)
-- 放大转换，存量值自动补零，无数据丢失。整数位由 14 位保持为 14 位。
ALTER TABLE quotation                     ALTER COLUMN total_amount          TYPE numeric(20,6);
ALTER TABLE quotation                     ALTER COLUMN original_amount       TYPE numeric(20,6);
ALTER TABLE quotation                     ALTER COLUMN tax_amount            TYPE numeric(20,6);
ALTER TABLE quotation_line_item           ALTER COLUMN subtotal              TYPE numeric(20,6);
ALTER TABLE quotation_line_item           ALTER COLUMN discount_base_amount  TYPE numeric(20,6);
ALTER TABLE quotation_line_item           ALTER COLUMN line_unit_price       TYPE numeric(20,6);
ALTER TABLE quotation_line_item           ALTER COLUMN line_final_price      TYPE numeric(20,6);
ALTER TABLE quotation_line_item           ALTER COLUMN line_discount_amount  TYPE numeric(20,6);
ALTER TABLE quotation_line_item           ALTER COLUMN line_total_amount     TYPE numeric(20,6);
ALTER TABLE quotation_line_component_data ALTER COLUMN subtotal              TYPE numeric(20,6);
ALTER TABLE costing_order                 ALTER COLUMN total_amount          TYPE numeric(20,6);
ALTER TABLE costing_order                 ALTER COLUMN costing_total_amount  TYPE numeric(20,6);
```

### 同步改 JPA 实体注解（漏改会导致 Hibernate 校验/截断不一致）

| 实体 | 字段 |
|------|------|
| `quotation/entity/Quotation.java:66/78`（`total_amount` / `original_amount`）+ `tax_amount` | `precision = 20, scale = 6` |
| `quotation/entity/QuotationLineItem.java` | `subtotal` + 5 个 `line_*` / `discount_base_amount` |
| `quotation/entity/QuotationLineComponentData.java` | `subtotal` |
| `costing_order` 对应实体 | `total_amount` / `costing_total_amount` |

### 纪律（CLAUDE.md 强制）
- **禁止手工 `psql -f`**，让 Quarkus dev mode `migrate-at-start` 自动跑；
- worktree 内的迁移文件共享 dev server 看不到 —— 验证方式见记忆 `cpq-worktree-flyway-migration-verify`：
  临时 copy 到主仓跑，**合并前删掉副本**；
- 迁移后 `touch` 一个 java 文件强制重启 Quarkus。

### 验收
```sql
SELECT version, success FROM flyway_schema_history WHERE description LIKE '%scale6%';  -- success = t
SELECT table_name, column_name, numeric_precision, numeric_scale
FROM information_schema.columns
WHERE (table_name, column_name) IN (...12 组...) ;  -- 全部 20 / 6
```

---

## Task B8：单元测试

### 必须覆盖

| 组 | 内容 | 对应验收 |
|----|------|---------|
| **T1 黄金用例** | `api.md` §5.2 的 G-1 ~ G-14，**逐条**写断言 | AC-11/12 |
| **T2 求值点覆盖** | 7 个求值点（6 JEXL + 1 ArithParser）**各一条** `0.1+0.2=0.3` | R-1/R-3 |
| **T3 链路基线** | 构造 6 层嵌套：元素行 → 列小计 → 页签合计 → 产品小计 → 行合计(×500000) → 整单总额(20 行)，断言 = 一次性十进制精确结果 | AC-13 |
| **T4 亿级精度** | 单价 `123.456789` × 年用量 `800000` = `98765431.2`；20 行累加到亿级后第 6 位仍正确 | AC-14 |
| **T5 常量锁定** | 断言 `PrecisionPolicy.DISPLAY_SCALE == 6`、`NumberFormatUtil` 与 `ExcelViewService` 均引用它 | AC-16 |
| **T6 类别隔离** | 取数列（8 位小数）经过一次完整算值流程后**仍是 8 位**；费率仍 2 位 | AC-8/AC-9 |
| **T7 语义不变** | 除零 → 0 不抛异常；null 参与运算按 0；全角运算符；一元负号；运算符优先级 | R-5 |

### 纪律
- 后端测试走 `test` profile（`10.177.152.12:5432/cpq_db`，**与 dev 库不同**，写集成测试注意）；
- **必须在 worktree 的 `cpq-backend` 目录跑**（`mvnw` 在 `cpq-backend/` 不在根），
  子代理 cd 到主仓跑会测错树报假绿（记忆 `cpq-worktree-maven-test-tree`）。

---

## Task B9：自检与交付证据

### 强制自检命令（逐条跑，输出贴进交付说明）

```bash
# 1. 编译 + 全量单测（必须在 worktree 的 cpq-backend 下）
cd <worktree>/cpq-backend && ./mvnw -q test

# 2. 触发 Quarkus 重启（schema DDL 后必须）
touch src/main/java/com/cpq/common/PrecisionPolicy.java && sleep 7

# 3. 后端存活（注意：/q/health 返 404 不是健康探针；业务端点返 401 才是正常）
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components   # 期望 401

# 4. Flyway
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 \
  -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"

# 5. 三条审计命令（Task B4-2），输出逐条给结论
```

### 交付说明必须包含

1. **11 个求值点的逐点勾选表**（7 后端 + 前端 3 由前端交付，本文档只管后端 7 个）；
2. **三条审计命令的完整输出** + 每条命中的"改了 / 不属范围（理由）"结论；
3. 单测全绿输出；
4. Flyway `success = t` 的 SQL 结果；
5. 一行「已自检」声明（CLAUDE.md 硬性要求），例如：
   > "后端单测 187 passed ✅；`/api/cpq/components` → 401（鉴权正常）✅；V<NN> success=t ✅；
   > `doubleValue()` 审计 23 处命中，链路二 0 残留 ✅"

### ⚠️ 禁止事项
- 禁止 `git add -A`，只 add 本次明确改动的文件；
- 禁止在 worktree 内另起 dev server / 重装依赖；
- 禁止宣布"完成"时缺少上述证据 —— 无证据的完成视为未完成。

---

## 3. 与前端的协作点

| 事项 | 约定 |
|------|------|
| 精度常量 | 前后端各持一份，值必须都是 6；以 `api.md` §5.1 为准 |
| 黄金用例 | 前后端**各自实现**、**共用同一份期望值**（`api.md` §5.2）；任一端不符即缺陷 |
| 联调 | 同一张单，前端显示值 / API 响应值 / DB 落库值 / 导出文件值**四处逐字节相同**（AC-15） |
| 接口结构 | **不变**，前端无需改任何请求体；后端不得擅自改字段名或把数值改成字符串 |
| 阻塞沟通 | 若发现某个数值必须改成字符串传输才能保精度，**先与技术总监确认**，不得单方面改契约 |
