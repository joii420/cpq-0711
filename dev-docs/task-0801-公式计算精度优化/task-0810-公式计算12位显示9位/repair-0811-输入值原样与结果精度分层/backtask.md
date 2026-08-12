# 后端任务

## 1. 改动范围

- `PrecisionPolicy.java`：增加 `FORMULA_RESULT_SCALE=9`、`PRODUCT_CARD_SUBTOTAL_SCALE=9`、`QUOTATION_TOTAL_SCALE=9` 及对应 round/helper。注释说明未来系统参数覆盖，当前常量是默认值。
- `DecimalRequestValidator.java`：停止对未知语义的整个 `rowData`使用全局 number 拒绝；提供按字段语义校验的能力。
- `QuotationResource.validateDraftDecimals`：批量取得报价所绑定的冻结组件字段元数据，按 `field_type`校验 rowData；不得逐组件/逐行查库。
- `FormulaCalculator`、`CardSnapshotService`、`ComponentDataEffectiveRows`：输入值仅在计算入口转 BigDecimal；公式结果、小计分别应用对应结果边界。
- `QuotationService` 及价格调整/提交等总额写点：统一使用 `QUOTATION_TOTAL_SCALE` 结果 helper，避免继续直接调用通用 calculation round。

## 2. 数据与事务

- 无 DDL、无 Flyway。
- 保存草稿校验必须在写事务前完成；失败零部分写。
- 已发布/冻结单据以冻结字段定义判定，不读取活组件造成语义漂移。
- 字段元数据一次批量加载并按 componentId 建 Map，SQL 数量相对行数 N/2N 恒定。

## 3. 校验规则

- `INPUT_TEXT`：不做十进制校验。
- `INPUT_NUMBER`：接受规范 decimal string；对结构整数字段兼容安全整数 number。若历史/带入输入为 JSON numeric token，必须从原始 token 无损进入 BigDecimal/文本，不经过 Double。
- `FORMULA` / `*_FORMULA`：新请求只接受 decimal string/null，按 9 位结果边界。
- 元数据缺失：返回包含 componentId、字段名和 JSON 路径的 400，不默认把全部 number 当输入放行。

## 4. 自检

- `DecimalRequestValidatorTest`：输入/公式/未知字段三类。
- `DraftPrecisionLifecycleHttpTest`：真实 HTTP 保存 `项次`、`1.2300`、公式 number 拒绝与零写。
- `PrecisionPolicyTest`：四个 scale 和隔离 helper。
- 产品小计、报价总额所有写点定向测试；前后端黄金用例对拍。
- SQL 计数 N/2N；V385 21 列 schema 回归。

## 5. 任务清单

- [x] B1 定义分层精度常量/helper及未来参数注释
- [x] B2 实现字段元数据批量分类
- [x] B3 改造 draft 动态 JSON 校验
- [x] B4 输入值无损进入公式引擎且不改写源值
- [x] B5 公式结果统一 9 位
- [x] B6 产品卡片小计接独立变量
- [x] B7 报价总金额所有写点接独立变量
- [x] B8 补 HTTP、单元、SQL 数量与 schema 回归测试
- [x] B9 完成后端定向及同轮基线验证
