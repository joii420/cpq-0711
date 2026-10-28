# 后端任务：报价结果按角色显示精度

## 结论

后端只对服务端生成的报价输出文本应用角色显示上限。JSON 查询/保存响应、数据库值、快照与计算仍保持原精度。Resource 从当前会话读取角色并显式把 4/9 位上限传入导出服务，服务层不自行读取 HTTP 上下文。

## 任务分解

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| B-1 | AC-1、AC-2、AC-3、AC-6 | 增加报价结果显示策略纯函数：`SALES_REP`、`SALES_MANAGER` → 4，`PRICING_MANAGER`、`SYSTEM_ADMIN` → 9。为 `NumberFormatUtil.format` 增加可选最大显示位数重载；仅计算值取显式位数/9/角色上限最小值，旧签名与非计算值行为保持不变。 |
| B-2 | AC-2、AC-5、AC-10 | `QuotationResource` 从 `SessionHelper.getCurrentUserRoleOrFallback(request)` 读取当前角色，将显示上限传给 HTML/PDF、报价 Excel、Excel 视图导出和发送邮件链路；权限注解、状态码与请求体不变。 |
| B-3 | AC-3、AC-5、AC-6、AC-9 | `QuotationExportService` 的 HTML/PDF 打印源与报价 Excel 对计算金额使用传入上限；税率、折扣率、原始数据 JSON 等非计算/原始值维持现状。报价 Excel 继续写精度安全的文本单元格。 |
| B-4 | AC-3、AC-5、AC-9 | `ExcelViewService.exportExcelView` 对 `FORMULA`、`CARD_FORMULA`、`TAB_JOIN_FORMULA`、`EXCEL_FORMULA` 的已计算结果应用角色上限；字段显式位数较小时优先，原始/取数列继续原精度。 |
| B-5 | AC-5、AC-10 | `CostingSheetResource` 以当前会话角色调用 `ComparisonExportService`；比对 Excel 的报价值、核价值使用同一上限，前端请求体不新增角色字段，禁止信任客户端自报角色。 |
| B-6 | AC-5、AC-10 | `QuotationEmailService` 接收 Resource 显式传入的显示上限，并用同一上限生成 HTML 附件及可选 Excel 附件；邮件发送权限、收件人、状态流转不变。 |
| B-7 | AC-7、AC-8 | 角色格式化仍只位于只读输出边界，不修改 `QuotationDTO`、实体、快照、对账与历史单读取口径；同一报价换角色导出不得产生数据库写入（发送邮件既有 `SENT` 状态流转除外）。S-FLOW 若暴露既有纯单头保存会无条件重算头金额，只允许增加与角色无关的最小保真守卫：无行变化且非折扣变化时不触碰头金额，真实行/折扣变化仍按既有口径重算。 |
| B-8 | AC-1、AC-2、AC-3、AC-5、AC-6、AC-7、AC-8、AC-9、AC-10、AC-11 | 补角色策略、格式化器、HTML/Excel/邮件/比对导出和 HTTP 鉴权测试；执行后端定向与相关回归，测试完成后按实际契约回写 `dev-docs/main-api.md`。 |

## 接口与会话传播

- `QuotationResource` 已持有 `SessionHelper`，需补 `@Context HttpServerRequest request` 或等价请求上下文注入；不得从请求 body/query 接收角色。
- `CostingSheetResource` 同样由会话解析角色；`ComparisonExportRequest` 保持精度值请求体，不增加显示位数字段。
- 建议服务签名显式接收 `int maxDisplayDecimals`；为现有服务级测试保留默认 9 位重载时，生产 Resource 必须调用带上限版本。
- RBAC 开启且无会话时仍由现有过滤器/`SessionHelper` 返回 401；RBAC 关闭的测试回退角色沿用现有 `SALES_MANAGER`，因此输出默认验证为 4 位，需在 9 位场景建立明确的管理员会话。

## 精度与字段分类

- `NumberFormatUtil` 的旧签名仍最多 9 位，保证报价外调用方零行为变化。
- 新上限仅在 `isComputed=true` 时参与；`isComputed=false` 的原始/取数值继续保留其显式位数或原精度。
- 报价导出中的行金额、单价、折扣金额、折后单价、小计、未税金额、税额、含税金额及总额是计算结果。
- VAT 税率、汇率、利润快照、属性值、原始行 JSON 不套角色上限。折扣率字段保持现有输入/业务口径，不因本任务统一改写。
- 全部舍入为 `HALF_UP`，去无意义尾零，禁止经 `double` 或 POI 数值通道丢精度。

## 数据库、性能与兼容性

- 不改数据库 schema，不新增 Flyway 迁移，不更新历史报价。
- 角色解析每个请求一次；不得在产品行、组件行或 Excel 单元格循环内读取会话或查询用户。
- 不新增查询和 N+1；导出读取报价的 SQL 数量与改造前一致。
- 服务端输出因角色变化属于同一端点的展示契约变化；JSON 响应结构和 decimal string 契约不变。

## 不做

- 不修改 `PrecisionPolicy.CALCULATION_SCALE`、数据库 numeric scale 或公式引擎。
- 不给普通报价 JSON 响应追加显示值字段。
- 不把 4 位文本写回 DTO、实体、JSONB、缓存或快照。
- 不新增角色/权限，不扩大任何查看、导出或发送权限。
- 不打包、不部署、不修改 Dockerfile。

## 后端自检

- 执行 `NumberFormatUtil`、角色策略、`QuotationOutputPrecisionHttpContractTest`、`ExcelViewServicePrecisionTest`、`ComparisonExportServiceTest`、`QuotationEmailPrecisionTest` 等定向测试。
- 解析生成的 HTML 和 xlsx，逐格断言 4/9 位、显式位数、去尾零、负数边界及原始列不变。
- 以带会话/无会话请求验证四角色映射和 401；对保存前后 DB/GET decimal string 做逐字比较。
- 执行后端构建和相关报价回归，记录测试数量、返回码和实际输出到 `test-report.md`。
