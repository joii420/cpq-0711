# 报价系统 unit_price.price_type 细分化 — 设计方案

> 日期：2026-06-08 | 状态：已实现 | 范围：报价系统 Excel 导入 + 选配运行时写入端

## 1. 背景与目标

报价系统 Excel 导入（`system_type=QUOTE`）原本 9 个费用 Sheet 都把 `unit_price.price_type`
写成两个**大类** `MATERIAL` / `COMPONENT`，仅靠 `cost_type` 区分 Sheet 来源。

本次要求把 `price_type` 列**直接改写为 9 个细分值**以区分 Sheet 类型，**大类彻底废弃**
（下游需要"是否材料类"时用细分值前缀如 `INCOMING_MATERIAL_*` 自行判断）。`cost_type`
**保持原样不动**，与细分 `price_type` 并存。

### 细分映射

| Excel Sheet | Handler | 旧 price_type | 新 price_type |
|---|---|---|---|
| 来料固定加工费 | Q06FixedProcessFeeHandler | MATERIAL | `INCOMING_MATERIAL_PROCESS` |
| 来料其他费用 | Q07IncomingOtherFeeHandler | MATERIAL | `INCOMING_MATERIAL_OTHER` |
| 来料年降 | Q08IncomingAnnualDiscountHandler | MATERIAL | `INCOMING_MATERIAL_REDUCTION` |
| 来料回收折扣 | Q09IncomingRecoveryHandler | MATERIAL | `INCOMING_MATERIAL_RECYCLE` |
| 自制加工费 | Q10SelfProcessFeeHandler | MATERIAL | `PROCESS` |
| 成品其他费用 | Q11FinishedOtherFeeHandler | MATERIAL | `FINISHED_MATERIAL_OTHER` |
| 组成件其他费用 | Q13ComponentOtherFeeHandler | COMPONENT | `COMPONENT_OTHER` |
| 组装加工费年降 | Q15AssemblyAnnualDiscountHandler | COMPONENT | `COMPONENT_REDUCTION` |
| 电镀费用（2 条） | Q17PlatingCostHandler | MATERIAL | `PLATING`（电镀加工费 + 电镀材料费两条都写，靠 cost_type 区分） |

`Q01ElementPriceHandler`（元素单价，`price_type=ELEMENT`）**不在范围内，保持不动**。

## 2. 决策记录（来自需求澄清）

1. **存哪**：直接写进 `price_type` 列（不新增列）。
2. **大类去向**：`MATERIAL`/`COMPONENT` 大类彻底废弃，不单独保存。
3. **cost_type**：保持原样，与细分 price_type 并存。
4. **电镀**：拆分的两条记录都写 `PLATING`，靠 cost_type 区分。
5. **范围**：只改导入写入端 + 选配运行时写入端；下游 SQL 视图/公式不动。
6. **存量数据**：清空 `unit_price` 重导（开发/测试数据），不写迁移脚本。
7. **选配运行时**：`ConfigureProductService` 写的"自制加工费"（原 `MATERIAL`）也同步改 `PROCESS`，
   与 Q10 一致。其读端按 `cost_type='自制加工费'` 取数，**不靠 price_type 过滤**，不受影响。
8. **zcj_view**（报价侧唯一受影响下游）：不改，接受断链，后续单独处理（见 §4）。

## 3. 影响面分析（核查结论）

- **核价系统视图零影响**：V255/V257 等按 `price_type='COMPONENT'/'CONSUMABLE'` 过滤的 SQL
  视图全是 `system_type='PRICING'`，与报价数据靠 `system_type` 隔离。
- **报价侧公式/Repository 不硬过滤 price_type**：`TemplateFormulaService`、`UnitPriceRepository`
  调用方均不按 price_type='MATERIAL'/'COMPONENT' 取数。
- **报价侧唯一受影响下游 = V270 `zcj_view`**（`system_type='QUOTE' AND price_type='COMPONENT'`，
  且不按 cost_type 过滤）：其数据来源是 Q13/Q15 导入的 `QUOTE+COMPONENT`。改细分后 zcj_view
  读不到 → 选配组装相关渲染可能断。**按决策 8 暂不处理。**
- **唯一约束 `uq_unit_price`**（含 `price_type` + `COALESCE(cost_type,'')`）：结构不变；
  细分后 price_type 更细，不会新增唯一冲突；电镀两条靠 cost_type 仍可区分。

## 4. 实现

### 4.1 DDL — Flyway `V297__quote_unit_price_type_subdivide.sql`

- 扩列宽：`unit_price.price_type` `VARCHAR(20)` → `VARCHAR(40)`
  （最长 `INCOMING_MATERIAL_REDUCTION` = 27 字符）。
  实测 `varchar(20)→varchar(40)` 是 binary coercible 加长，PG 放行，**无需 DROP 任何引用
  `unit_price` 的真实视图**。
- 重建 CHECK `chk_unit_price_type`：白名单 = 旧 5 个（`ELEMENT/MATERIAL/COMPONENT/PART/CONSUMABLE`，
  核价 PRICING 视图 + 报价 zcj_view 仍用，保留）+ 新 9 个 = 14 个。

### 4.2 代码

- 9 个 `Q*Handler`：`g.put("price_type", ...)` 改为对应细分值（含 Javadoc 注释同步）。
- `ConfigureProductService`：3 处 `gk.put("price_type", "MATERIAL")`（cost_type=自制加工费）→ `PROCESS`。

### 4.3 不改

`Q01ElementPriceHandler`、核价 SQL 视图、`zcj_view`、所有 `cost_type`、报价公式、唯一约束结构。

## 5. 验证

- `tsc`/前端：本次无前端改动。
- Flyway `V297` `success=t` ✅
- 列宽 = 40 ✅；3 个新细分值（含 27 字符最长值）写入成功 ✅；非法值被 CHECK 拒绝（23514）✅
- 后端 API `/api/cpq/components` → 401（auth 正常，非 500）✅
- 选配 E2E `composite-product-flow.spec.ts` 回归（确认 PROCESS 改动不破选配工序渲染；
  zcj_view 断链为已知接受项）。

## 6. 存量清理（按需）

开发/测试环境清空报价侧 unit_price 重导即可：
```sql
DELETE FROM unit_price WHERE system_type='QUOTE';  -- 谨慎：仅开发/测试
```
（不提供自动迁移脚本，按决策 6。）
