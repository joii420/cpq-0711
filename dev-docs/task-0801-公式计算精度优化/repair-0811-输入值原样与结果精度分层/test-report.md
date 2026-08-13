# 测试报告

- 执行日期：2026-08-11
- 结论：**通过，准入；三视图真实 Playwright 留有环境数据残余风险**
- HEAD：`16ad3b6900837858a8c8292d9ad4ed23c45c2c30`
- 后端 diff SHA-256：`4f7f51c068890f3157bb0edb905549f392836cbf386cc72783cc792df99154bc`
- 前端 diff SHA-256：`187f0b5dd07322b44fec52e09e2e36a23f5bfad1c03a62b58acf11c80ae4568d`

## 1. 执行摘要

| 阶段 | 结果 | 证据 |
|---|---|---|
| 后端编译 | PASS | `./mvnw -q -DskipTests test-compile`，exit 0 |
| 后端精度定向 | PASS | 26 tests：PrecisionPolicy 15、DecimalRequestContract 7、FormulaCalculatorSamePageFieldRef 4；0 failure/error/skip |
| 既有 HTTP 生命周期 | PASS | DraftPrecisionLifecycleHttpTest 4/4；含旧精度保存/重开、事务回滚、dry-run N/2N、快照体积 |
| 前端测试 | PASS | 项目脚本扩大执行为全量：95 files，1151 tests，0 failure |
| TypeScript | PASS | `npx tsc --noEmit -p tsconfig.app.json`，exit 0 |
| 数据库 | PASS | test DB Flyway V385 success；21/21 目标列为 `numeric(26,12)` |
| 本次 P0 HTTP 契约 | PASS | DraftPrecisionLifecycleHttpTest 新增真实 PUT：2xx、DB/GET 原文、公式 number 400、DB 指纹不变 |
| 保存路径 N+1 | PASS | T1/T2、C1/C2/C3 复合键错误路径正确；N=2/2N=4 冻结元数据 SQL 均为 1 |
| 两个 scale 生产路径隔离 | PASS | CardSnapshotService/QuotationService 真实生产入口双向验证 7/8 与 8/7 |

## 2. AC 验收

| AC | 结果 | 证据与缺口 |
|---|---|---|
| AC-1 项次 number 保存 | PASS | 真实 PUT 2xx；DB JSON 保持 `项次:1`；GET 重开成功 |
| AC-2 `"1.2300"`逐字保真 | PASS | 真实 PUT、DB row_data、GET 重开均包含逐字 `1.2300` |
| AC-3 基础数据 12 位 | PASS | 前端输入/快照全量回归及真实保存链验证通过 |
| AC-4 过程 12、结果 9 | PASS（定向） | 链式后端测试证明 A=`1/3` 工作值 `0.333333333333`，下游读取工作值；结果边界/前端公式回归通过 |
| AC-5 公式 number 拒绝零写 | PASS | 真实 PUT 返回 400，响应含公式字段路径，component DB 指纹前后不变 |
| AC-6 产品卡片小计独立变量 | PASS | CardSnapshotService 生产入口以 scale 7/8 分别得到预期值 |
| AC-7 报价总额独立变量 | PASS | QuotationService 生产入口以 scale 8/7 分别得到预期值 |
| AC-8 两 scale 不串用 | PASS | 同一测试双向交换 7/8，两个生产入口各自变化、互不串用 |
| AC-9 零浮点新增链 | PASS（定义范围） | 静态扫描未发现新增精度浮点转换；结构整数 `annualVolume` 的 Number 转换是明确例外 |
| AC-10 中文结构键 | PASS | lossless 测试覆盖 `项次/_项次/序号`；生产 `losslessJson.ts` 三键正确 |
| AC-11 三视图一致 | PASS（自动化）/残余风险 | readonly、comparison、快照等全量自动化通过；真实 Playwright 旧 fixture“西门子”无数据，属环境数据阻塞，不计本次缺陷 |
| AC-12 无 DDL | PASS | diff 无 migration/SQL；DB V385 success；21/21 列 26/12 |

## 3. 关键专项

### 3.1 冻结元数据与 N+1

静态实现使用 `PublishedTemplateReader.allTabsOfMany(templateIds)`，一次 `IN` 查询取快照，并以 `(templateId, componentId)` 建 Map；循环内未查询。设计满足常数 SQL 和复合键方向。

补测已按 `test.md` §4 构造 T1/T2、C1/C2/C3，并执行真实 saveDraft。公式 numeric token 的错误路径稳定为 `lineItems[1].componentData[1].rowData[0].公式值`；Hibernate 统计输出 `N=2 sql=1, 2N=4 sql=1`，证明冻结元数据查询数不随行数增长。

### 3.2 fallback token

保存 payload 生产代码对组件小计使用：

```ts
subtotal: isDecimalString(cd.subtotal) ? normalizeDecimalString(cd.subtotal) : '0'
```

前端 `draftPrecision.test.ts` 对 `null`、`""` 两组均断言序列化后为 string `"0"`；`QuotationWizard` 实际调用 `toDraftComponentSubtotal`，不是孤立 helper。

### 3.3 scale 独立性

前端测试证明 `formatProductCardSubtotal(value, 7)` 与 `formatQuotationTotal(value, 8)` 返回不同结果；静态调用点也分别接入。后端 helper 是两个独立方法，但 scale 是不可注入的编译常量，测试仅验证两者当前均为 9。

`IndependentResultScaleProductionEntryTest` 直接调用 `CardSnapshotService.productCardSubtotalResult` 和 `QuotationService.quotationTotalResult` 两个生产入口，先验证 7/8，再反向验证 8/7，AC-8 已满足。

## 4. 最终判定

原 QA-P0-01、QA-P0-02、QA-P0-03 均已关闭。指定后端复验结果：PrecisionPolicy 15/15、IndependentResultScaleProductionEntry 1/1、DraftPrecisionLifecycleHttp 6/6，0 failure/error/skip。结合前轮前端全量 95 files/1151 tests、TypeScript、数据库 V385/21 列证据，AC-1~AC-12 准入。

唯一残余风险：真实三视图 Playwright 使用的旧“西门子”fixture 当前无数据，无法完成同一真实 DRAFT 的浏览器三视图观察；已有三视图/快照自动化通过，此环境数据问题不记为本次缺陷。

## 5. 2026-08-11 row_index 回归复验

- 修复项：字段感知 `rowData` 校验复用结构整数白名单，`row_index` 无需也不得伪装成冻结业务字段。
- 正向：真实草稿 PUT 携带 `row_index:0` 返回 200，数据库 JSON 保持数字 0。
- 反向：`row_index:0.5` 仍拒绝；未知业务 numeric token 仍拒绝；公式 numeric token 仍返回 400 且数据库指纹不变。
- 回归：`DecimalRequestContractTest`、`DraftPrecisionLifecycleHttpTest`、`PrecisionPolicyTest`、`IndependentResultScaleProductionEntryTest` 共 29 项通过，0 failure/error/skip。
