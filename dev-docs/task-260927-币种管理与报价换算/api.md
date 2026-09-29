# 接口契约

本文件是本任务新增/修改接口的局部契约；实现完成后按项目规则回写 `dev-docs/main-api.md`。

## 1. 币种管理

### `GET /api/cpq/currencies`

查询启用/停用币种列表。

响应 `data`：

```json
[
  {
    "code": "USD",
    "name": "美元",
    "symbol": "$",
    "enabled": true,
    "isBase": false,
    "sortOrder": 20
  }
]
```

### `POST /api/cpq/currencies`

新增币种。请求字段：`code`、`name`、`symbol`、`sortOrder`。`code` 按大写 ISO 代码保存；`CNY` 只能存在一条且不可删除、不可停用。

### `PUT /api/cpq/currencies/{code}`

修改名称、符号、排序和启用状态。被报价单或客户使用的币种不得删除；停用后不能被新客户/新报价单选择，但历史报价单仍可查看。

### `DELETE /api/cpq/currencies/{code}`

仅允许删除从未被客户、报价单、汇率记录引用的非 CNY 币种；否则返回 `409 CURRENCY_IN_USE`。

## 2. 汇率历史

### `GET /api/cpq/currencies/{code}/rates`

查询目标币种到 CNY 的汇率历史。支持 `fromDate`、`toDate`、`status`。

响应中的 `rate`、`displayRate` 均为十进制定数字符串；`displayRate` 固定 12 位。

```json
{
  "currency": "USD",
  "items": [
    {
      "rate": "7.200000000000",
      "effectiveDate": "2026-09-27",
      "source": "ECB",
      "status": "ACTIVE",
      "fetchedAt": "2026-09-27T10:00:00Z"
    }
  ]
}
```

### `POST /api/cpq/currencies/{code}/rates`

手工新增汇率。请求：`rate`、`effectiveDate`、`source`。汇率必须大于 0；同一币种同一生效日期允许保留多条更新记录，按 `updatedAt` 和记录 ID 的倒序确定当天最后有效记录。

### `POST /api/cpq/currencies/{code}/rates/import`

导入一个目标币种的汇率历史文件。multipart 字段：`file`。Excel 列：`生效日期*`、`汇率*`、`来源`；目标币种由 URL 中的 `{code}` 决定，汇率方向为 `{code} -> CNY`。

- 先完整校验文件，再写入；任何行存在格式错误或汇率小于等于 0 时，整批拒绝并返回行号和原因。同一生效日期的导入记录作为一次新的历史更新保留。
- 成功后返回新增记录数、失败记录数和逐行结果；汇率存储和计算保留 12 位小数。
- 导入只新增汇率历史，不改写历史报价单或报价单汇率快照。

当天有效记录规则：按币种和生效日期分组，取最后更新时间的记录；策略窗口计算在此去重后的每日有效记录上执行。

## 3. 客户

### 既有客户新增/编辑接口增加字段 `defaultCurrency`

响应 DTO 增加 `defaultCurrency`；创建时默认 `CNY`。编辑时只影响客户配置，不回写任何已有报价单。

## 3.1 客户汇率策略

### `GET /api/cpq/customers/{customerNo}/exchange-rate-strategy`

查询客户唯一的一套汇率策略。该策略适用于该客户的全部目标币种；策略不包含价格源、系数和加价。

### `PUT /api/cpq/customers/{customerNo}/exchange-rate-strategy`

保存取值方式和参数：

- 窗口方式：`method` 为 `LATEST`、`AVG`、`MAX`、`MIN`，配合 `windowNum`、`windowUnit`；`LATEST` 不需要窗口。
- 日期方式：`method` 为 `SPECIFIC_DATE`、`PREVIOUS_MONTH_DAY`、`MONTH_DAY`、`PREVIOUS_MONTH_END`。
- `SPECIFIC_DATE` 传 `specificDate`（固定 `YYYY-MM-DD`）；`PREVIOUS_MONTH_DAY`、`MONTH_DAY` 传 `dayOfMonth`（1~31）；`PREVIOUS_MONTH_END` 不需要额外参数。
- 日期方式统一以报价单创建日为基准。`MONTH_DAY` 在创建日尚未到达所选日时取上月同日；目标月份没有该日时跳过该月继续向前找。命中的具体日期没有当天有效汇率直接返回错误，不向前补取其他日期。

示例：创建日 `2026-09-10`、`MONTH_DAY=15` 时解析为 `2026-08-15`；创建日 `2026-09-20` 时解析为 `2026-09-15`；选择第 31 日而目标月份为 2 月时跳过 2 月，继续查找上一个存在 31 日的月份。

### `POST /api/cpq/customers/{customerNo}/exchange-rate-strategy/simulate`

请求：`currency`、`baseDate`，以及待试算策略参数。响应返回命中规则、解析后的目标日期（或窗口）、当天最后有效汇率、参与记录数和最终汇率；日期规则还返回 `skippedMonths`。缺少命中日期有效汇率返回 `409 QUOTATION_RATE_NOT_FOUND`。

## 4. 元素价格导入

### 既有元素价格导入接口增加批次字段 `currency`

`POST /api/cpq/element-price/import` 的 multipart 增加必填字段 `currency`。

- 一个文件只能使用一个批次币种。
- Excel 模板不再读取行内货币作为业务币种；存在旧模板货币列时只做兼容校验，不允许与批次币种冲突。
- 新行保存 `sourcePrice`、`sourceCurrency`、`sourceRate`、`cnyPrice`。
- 响应行同时返回源价格和 CNY 基准价格。

## 5. 报价单币种与汇率快照

报价单创建接口在同一事务内完成：读取客户默认币种、按客户汇率策略取目标币种汇率（无策略时按创建日之前最新一条）、保存报价单币种和汇率策略快照。缺少汇率时整个报价单创建失败，不留下半成品。

报价单创建后不提供币种切换和汇率刷新接口；已有报价单只读取自身快照。

## 6. 报价/核价/比对响应扩展

报价单头部统一返回：`currency`、`currencyName`、`exchangeRate`、`exchangeRateDate`、`exchangeRateSource`、`exchangeRateCapturedAt`。

核价侧产品卡片及小计返回（双币种展示仅适用于核价侧）：

- `cnySubtotal`
- `foreignSubtotal`
- `foreignCurrency`
- `exchangeRate`

报价侧产品卡片仅展示报价单币种金额与小计；内部保留的 CNY 元素基准价用于计算，不产生报价侧 CNY 小计展示行。

核价公式输入仍只使用 CNY；核价响应额外返回由 CNY 小计换算得到的 `foreignSubtotal`。比对响应的核价侧金额使用该外币小计，不允许把核价人民币值直接与报价外币值比较。

## 6.1 公式标签

组件管理公式编辑器增加 `[汇率]` 标签。运行时读取报价单汇率快照中的目标币种 → CNY 数值字符串，保留 12 位精度；标签只有在用户显式放入公式时才参与计算，不自动改写已有公式。报价单缺少汇率快照时，显式使用该标签的公式返回明确错误，不按 1 处理。

## 7. 错误码

`CURRENCY_NOT_FOUND`、`CURRENCY_DISABLED`、`CURRENCY_IN_USE`、`INVALID_EXCHANGE_RATE`、`QUOTATION_RATE_NOT_FOUND`、`EXCHANGE_RATE_STRATEGY_INVALID`、`EXCHANGE_RATE_STRATEGY_SIMULATION_FAILED`。
