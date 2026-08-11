# backtask.md · 公式计算 12 位、显示 9 位

> 后端实现输入。权威需求见 `需求文档.md`，协议见 `api.md`。

## 1. 数据模型变更

### 1.1 Flyway

候选迁移：`V385__task0810_formula_scale12.sql`。创建文件前重新扫描最新版本；若 V385 已占用，使用当时下一空闲版本并先同步本目录四份 Gate A 文档。

21 个列从 `numeric(20,6)` 放大为 `numeric(26,12)`：

| 表 | 列 |
|---|---|
| `quotation` | `total_amount`, `original_amount`, `tax_amount` |
| `quotation_line_item` | `subtotal`, `discount_base_amount`, `line_unit_price`, `line_final_price`, `line_discount_amount`, `line_total_amount` |
| `quotation_line_component_data` | `subtotal` |
| `costing_order` | `total_amount`, `costing_total_amount` |
| `material_price_review` | `warn_diff` |
| `material_price_review_column` | `quote_current`, `quote_adjusted`, `costing_current`, `costing_adjusted`, `diff_current`, `diff_adjusted` |
| `quotation_price_revision` | `quote_total_amount` |
| `material_price_update_job_item` | `diff_value` |

迁移只执行 `ALTER COLUMN TYPE`，不 UPDATE、不重算、不补零。对应实体 `@Column(precision=26, scale=12)` 同步。

配置阈值、费率、元素价格、基础数据字段不改 schema。

### 1.2 JSON/JSONB

- `row_data`, `snapshot_rows`, `quote_card_values`, `costing_card_values`, `quote_excel_values`, `costing_excel_values` 的精度敏感数值新写规范十进制字符串。
- 历史 numeric token 由 Jackson 直接构造 DecimalNode/BigDecimal；禁止 DoubleNode/Double 中转。
- 非 DRAFT 快照禁止因读取或格式适配写回。
- 不做全库 JSONB 回刷。

## 2. 精度基础设施

### 2.1 `PrecisionPolicy`

```java
CALCULATION_SCALE = 12
DISPLAY_SCALE = 9
ROUNDING = HALF_UP
MC = DECIMAL128
```

提供明确边界函数：

- `roundForCalculation(BigDecimal)`：公式节点、持久化工作值，12 位。
- `divide(BigDecimal, BigDecimal)`：12 位、除零返回 0，沿用旧语义。
- `formatForDisplay(BigDecimal)` 或由 `NumberFormatUtil` 负责：最多 9 位、去尾零。
- `toPlainDecimalString(BigDecimal/String)`：仅接受 BigDecimal 或规范字符串，禁止科学计数法；历史 Json numeric token 在调用前已直接成为 BigDecimal。

旧 `round()` 不得继续同时表示计算和显示；迁移所有调用后删除或标记不可用，避免新写点误用。

### 2.2 JSON 精度适配器

集中建立 helper/serializer：

- 精度字段响应统一输出 JSON string。
- 精度字段请求只接受规范 JSON string，并直接绑定 BigDecimal/string；JSON number 使用既有 400 类型校验拒绝。
- 动态 JsonNode 通过字段元数据区分数字与文本；计算结果写 TextNode，null 保持 NullNode。
- ObjectMapper 读改写必须启用 `USE_BIG_DECIMAL_FOR_FLOATS`、exact BigDecimal node factory、plain BigDecimal 输出，复用 RECORD #58 已验证配置。
- 禁止业务服务自行 `Double.parseDouble` 或 `new BigDecimal(double)`。
- 历史持久化 JSON numeric token 的兼容仅位于统一适配器，使用 `decimalValue()` 直接取得 BigDecimal；不得向业务层暴露 `Number`/Double。

## 3. 公式求值点

### 3.1 自写解析器与上下文

`FormulaCalculator`：

- `RowContext.fieldValues/bySource/componentSubtotals/quotationFields/productAttributes/previousRowSubtotal` 的精度值统一为 BigDecimal。
- `RowResult`、跨页签 target expression、SUM/AVG/MAX/MIN、树公式上下文均保持 BigDecimal。
- 数字字面量从原始字符串构造 BigDecimal。
- 公式节点返回前 `roundForCalculation`；同一表达式内部加减乘不逐步 round。
- 删除节点之间 `.doubleValue()` 回落。
- 修复 `tab_name#__amount_total__` 对称查找，闭环 BL-0160。

### 3.2 六个 JEXL 点

逐点检查并补测试：

1. `engine/formula/FormulaCalculationService`
2. `formula/FormulaEngine`
3. `template/service/TemplateFormulaService.rowJexl`
4. `quotation/service/ExcelViewService` 内联公式
5. `quotation/service/TabJoinPlanEvaluator` / `SafeArithmetic`
6. `costing/service/CostingSheetService` 内联公式

要求：

- 引擎使用 DECIMAL128 BigDecimal arithmetic。
- 拼接字面量使用 JEXL BigDecimal 语法或变量绑定，不留下普通 Double 字面量。
- 函数入参和返回值 normalize 为 BigDecimal。
- 求值出口按 12 位节点精度输出 decimal string/BigDecimal。
- 除零、null、非法表达式语义不变。

## 4. 业务服务写点

### 4.1 报价/折扣/汇总

- `LineDiscountService` 五个金额字段用 `roundForCalculation`。
- `QuotationService` 所有 `originalAmount/totalAmount/taxAmount` 写点用 12 位。
- `CardSnapshotService` 组件小计、列小计、产品小计、单头汇总全程 BigDecimal。
- `__amount_total__` 三个登记点使用同一 BigDecimal helper，不再 4 位或 Double。
- `ConfigureSnapshotService`、`ComponentDataEffectiveRows` 对齐同一算法。

### 4.2 核价

- `CostingFreezeService`、`CostingVersionService` 总额用 12 位。
- `CostingSheetService` 公式与汇总结果使用 BigDecimal。
- 报价/核价比较差异用 BigDecimal subtract/abs，不经 double。

### 4.3 价格调整与版本快照

- `MaterialVersionUpgradeService` 的 `q.totalAmount` 从 `setScale(4)` 改为 `roundForCalculation`。
- 版本总额、review current/adjusted/diff、warn/diff_value 写入 12 位列。
- 阈值比较保持配置精度，但比较双方用 BigDecimal。
- 价格升级仅重算既有业务指定的行，不借精度任务扩大重算范围。

## 5. API 与序列化

- 端点、方法、鉴权、错误码不变。
- `API-P1~P4` 的金额、数量、费率、公式值等精度敏感字段新响应输出 decimal string。
- 请求精度字段只接受 decimal string；DTO/反序列化器直接生成 BigDecimal，JSON number 返回现有 400 校验错误。
- 结构整数、分页、计数、排序值保持 JSON number。
- 卡片/Excel 值快照仍是外层 JSON string 字段，其内部精度值也是 decimal string。
- API 返回 12 位工作值，不提前压到 9 位；9 位只在 UI/导出。

## 6. 导出

- `NumberFormatUtil` 计算列默认显示 9 位。
- `QuotationExportService` 的 HTML/PDF/邮件金额统一走 `NumberFormatUtil`。
- `ExcelViewService` 和报价 Excel 导出先按最多 9 位生成显示字符串。
- 所有精度敏感计算值写文本单元格，禁止调用接收 `double` 的 NUMERIC API；基础原始列仍按其既有类型契约处理。
- 不对基础取数列套计算列 9 位规则。

## 7. 事务、幂等与并发

- 不新增查询，不允许 N+1；精度转换在现有内存对象上完成。
- Flyway 放大列为一次性 DDL，禁止手工 `psql -f`。
- 草稿显式保存幂等：相同工作值重复保存，规范字符串和 DB 值不漂移。
- 非 DRAFT 读取零写入；测试通过 SQL/持久化统计证明。
- 保留报价保存的悲观锁和价格调整现有事务边界，不调整提交顺序。
- JSON 规范化必须确定性，防止同值不同字符串导致重复 UPDATE 或快照指纹漂移。

## 8. 性能要求

- 公式复杂度不增加数据库访问。
- Decimal/BigDecimal 改造不得为每个单元格创建 ObjectMapper/JEXL engine；复用现有单例/缓存。
- 同一张基准报价计算耗时相对 master 增幅目标不超过 20%；超过时必须在 test-report 说明数据规模和瓶颈。
- 快照体积增长需记录；decimal string 相比 JSON number 的增长不得触发额外网络分页或字段裁剪。

## 9. 后端测试

- `PrecisionPolicyTest`：12/9 常量、HALF_UP、负数、零、科学计数法禁止。
- `FormulaCalculatorGoldenCasesTest` 和共享 golden：12 位节点值、BL-0160、复杂嵌套。
- 六个 JEXL 点分别有 BigDecimal 字面量/变量/函数/除法测试。
- 21 列 JPA mapping 静态断言 + information_schema 验证。
- JSON serializer/parser：新 string、历史 numeric token 直达 BigDecimal、null、大金额 12 位逐字往返；断言无 DoubleNode/Double。
- 报价/核价/价格调整写点：保存、刷新、重开、版本快照逐值一致。
- 冻结单读取无 UPDATE。
- Excel 精度敏感值全部为文本单元格，内容与 9 位 UI 字符串一致。

## 10. 自检项

- 在 worktree 的 `cpq-backend/` 运行定向测试和 `./mvnw test`，记录 Tests run/Failures/Errors/Skipped，不能只写 BUILD SUCCESS。
- `./mvnw -o compile` 或项目可用的离线编译通过。
- 共享 dev server 已运行则复用，不另起；Java 改完先 compile，再触发热重载。
- Flyway 由 Quarkus 启动执行后查 `flyway_schema_history success=t` 和 21 列 information_schema。
- 目标端点返回 200/401，不能 500。
- 搜索公式/金额链路 `.doubleValue()`、`Double.parseDouble`、`Map<String, Double>`、`setScale(4|6)`，逐个分类。

## 11. Task 列表

- [ ] B1 创建失败测试并锁定当前 6 位/Double/BL-0159/0160 行为。
- [ ] B2 拆分 `PrecisionPolicy` 12 位计算与 9 位显示 API。
- [ ] B3 建历史 numeric token 直达 BigDecimal 的 JSON 适配器，以及 decimal-string-only API serializer/deserializer。
- [ ] B4 `FormulaCalculator` 全上下文 BigDecimal 化。
- [ ] B5 六个 JEXL 求值点逐点加固和测试。
- [ ] B6 报价、折扣、卡片、跨页签、核价汇总写点改 12 位。
- [ ] B7 价格调整/版本/审计派生值改 12 位并吸收 BL-0159。
- [ ] B8 创建并验证 21 列 Flyway/JPA 迁移。
- [ ] B9 API 精度字段请求/响应统一 decimal string，JSON number 使用既有 400 校验拒绝。
- [ ] B10 HTML/PDF/邮件/Excel 统一 9 位，Excel 精度敏感值全部写文本。
- [ ] B11 完成定向、全量、SQL、端点和性能自检并提交精确文件清单。
