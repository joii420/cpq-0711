# api — 基础资料数值列扩至 12 位小数

> 对应 `需求文档.md`。
> **结论：接口契约零改动，但有 1 个端点的响应值格式发生可观测变化。**

---

## 1. 判定：契约层零改动

| 检查项 | 结论 |
|---|---|
| 新增端点 | 无 |
| 删除端点 | 无 |
| 路径 / 方法 / 参数 | 无变化 |
| 请求体 / 响应体**字段名** | 无变化 |
| 请求体 / 响应体**字段类型** | 无变化（精度字段本就是 decimal string，task-0810 FR-8 确立） |
| 鉴权 / 错误码 | 无变化 |

**判定依据**：本任务只改 ① DB 列 `numeric(p,s)` ② JPA `@Column(precision, scale)` ③ 导入 handler 的 scale 常量 ④ `PricingSheetRegistry` 的 scale 镜像。
这四处**都在传输层以下**。DTO 定义、序列化器、端点签名一处未动。

---

## 2. ⚠️ 但有一个端点的响应值格式会变

### 2.1 端点

```
GET /api/cpq/pricing-basic-data/parts/{materialNo}/sheets/{sheetKey}/rows
```

（`PricingBasicDataMaintenanceResource.java:68`）

### 2.2 变化

响应里 DECIMAL 列的值是**按 DB 列 scale 补零的定标字符串**，由 `PricingMaintenanceService:322` 产生：

```java
if (scale != null && val != null) val = scaledString(val, scale);
```

`scale` 取自 `PricingSheetDef.decimalScales` ← 即 `PricingSheetRegistry` 的 16 处 `.scale(col, N)`。
`backtask.md` T4 把这些常量从 `6`/`4`/`8` 改成 `12` 后：

```jsonc
// 现在
{ "pricing_price": "1.230000", "defect_rate": "0.0500" }

// T4 之后
{ "pricing_price": "1.230000000000", "defect_rate": "0.050000000000" }
```

**类型没变**（仍是 decimal string），**数值没变**（`1.230000` ≡ `1.230000000000`），变的是**尾随零位数**。

### 2.3 为什么这不算契约破坏，但仍必须记录

- 从**契约**看：字段仍是"表示十进制数的字符串"，语义完全等价，任何按十进制解析的消费方都不受影响。
- 从**行为**看：直接把字符串**当显示文本**用的消费方会看到差异。

本项目**恰好有一个这样的消费方**——核价料号维护页 `EditableSheetTable.tsx` 用 `String(value)` 原样渲染，会显示 `1.230000000000`。
**处置见 `fronttask.md` §2~§3**（主线推荐 F-A：前端接 `formatDisplayDecimal`）。

### 2.4 写入方向：无变化

`PUT` 保存路径的请求体格式不变。后端 `extractContentRow`（`:569`）仍按 `decimalScales` 归一，只是归一目标从 6 位变 12 位——**这正是本任务要的**（AC-6：维护页保存 12 位不被截）。

---

## 3. 消费方盘点（全工程 grep 确认）

`pricing-basic-data` 在前端只有 2 处命中：

| 文件 | 性质 | 影响 |
|---|---|---|
| `pages/master-data/part-costing/api.ts` | **真实调用** | ⚠️ 受影响，见 `fronttask.md` |
| `pages/quotation/bomTreeLeaf.ts` | **注释里的架构红线**（原文："候选料号列表不调用任何远程端点——不查 pricing-basic-data/lookup"） | ✅ 无影响 |

**报价 / 核价渲染主链路不消费此端点**，走的是 `quoteCardValues` 快照与 `$view` 取数，对列 scale 变化透明。

---

## 4. 其余端点：为什么确认不受影响

| 端点族 | 判定依据 |
|---|---|
| 报价单 CRUD / 卡片值 | 精度值走 `PrecisionPolicy` 的 12 位工作值 + decimal string，已是 task-0810 契约，不读基础资料列 scale |
| 核价单 | 同上 |
| 导入端点（`/import/**`） | 请求是 Excel 文件流，响应是导入结果统计，无精度字段 |
| 导出（Excel / PDF / HTML） | 走 `NumberFormatUtil` / `ExcelViewService`，兜底 `PrecisionPolicy.DISPLAY_SCALE`，不感知列 scale |
| 组件 / 模板管理 | 不涉及基础资料数值列 |

---

## 5. 交付时的接口验证清单

- [ ] `GET /api/cpq/pricing-basic-data/parts/{materialNo}/sheets/{sheetKey}/rows` 返回 200，DECIMAL 字段为 12 位定标字符串
- [ ] 同端点 `PUT` 保存 12 位小数 → 再 `GET` 取回，**逐位相等**（AC-6）
- [ ] 响应中**不出现科学计数法**（`1.23E-6` 这类）——`scaledString` 用 `toPlainString()` 本就防这个，扩到 12 位后仍须实测确认
- [ ] 后端存活自检：`curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/components` → 期望 **401**（不是 404/500）
