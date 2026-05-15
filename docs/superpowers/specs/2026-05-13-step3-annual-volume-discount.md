# 报价单 Step3「优惠策略」改造 — 年用量阶梯折扣

> **日期**: 2026-05-13
> **状态**: 设计待评审 → 准备实施
> **触发**: 用户原型表头 [产品 / 年用量 / 优惠金额来源 / 可优惠金额基数 / 折扣 / 优惠金额 / 计价单位 / 币种 / 单价 / 优惠后单价 / 总金额]
> **核心目标**: Step3 改成"按行配置 + 阶梯折扣"表格;`年用量` 既驱动阶梯折扣率,又乘进合计总价
> **配套**:
>  - 现有整单折扣字段 `quotation.system_discount_rate / final_discount_rate` 保留不删,V1 不再写入(置 100 兜底)
>  - `docs/PRD.md` v1.3「折扣层级:整单」/ v1.8「步骤间数据刷新」/ v2.0「应付比例语义」 — 本设计在行级补一层
>  - V2 切到 `PricingStrategy` 时本设计的 `DiscountStrategy` 接口已预留扩展点,前端 0 改动

---

## 目录

1. [背景与边界](#1-背景与边界)
2. [核心决策清单(D1–D6)](#2-核心决策清单d1d6)
3. [字段映射(UI 10 列 ↔ 数据来源 ↔ 存储字段)](#3-字段映射)
4. [业务流程与计算流](#4-业务流程与计算流)
5. [数据模型 — V162 DDL](#5-数据模型--v162-ddl)
6. [API 设计](#6-api-设计)
7. [阶梯折扣引擎(可配置化预留)](#7-阶梯折扣引擎)
8. [前端实现路径](#8-前端实现路径)
9. [反模式对照](#9-反模式对照)
10. [实施清单与验收](#10-实施清单与验收)
11. [已知限制与未来扩展](#11-已知限制与未来扩展)

---

## 1. 背景与边界

### 1.1 用户需求

销售在报价单第 3 步(优惠策略),需要按"每条产品行"独立配置:
- 年用量(YoY 用量,既是商务条件也是折扣阶梯的驱动量)
- 折扣作用的成本项(从核价 7 metric 之一选)
- 系统按写死的阶梯算出折扣率
- 该行最终单价 = 原单价 - 优惠金额
- 整单总价 = SUM(年用量 × 优惠后单价)

### 1.2 不动的业务流程

| 模块 | 涉及表 / 文件 | 零侵入承诺 |
|---|---|---|
| Step1/2/4/5 | `QuotationWizard.tsx` 其余步骤 | 不动;Step3 拆出独立组件后,与其他步骤通过 wizard state 同进同出 |
| 模板与组件 | `template / component / template_components_snapshot` | 不动 |
| 核价单 | `costing_summary / v_costing_summary_full` | 只读;基数取数路径全部沿用 `v_costing_summary_full.<metric_col>` |
| Excel 视图 | `costing_template` | 不动 |
| 公式引擎 | `FormulaEngine / ImplicitJoinRewriter / DataLoader` | 不动;基数取数走 `POST /api/cpq/formulas/evaluate` 现有端点 |
| 现有整单折扣 | `quotation.system_discount_rate / final_discount_rate` | 字段保留;V1 不写入(NULL 或 100 兜底);V2 PricingStrategy 上线再决定合并/废弃 |
| 草稿持久化 | `quotation_line_item.subtotal` | 不动;subtotal 仍是 Step2 计算结果,Step3 进入时映射为"单价" |

### 1.3 走出 Step3 时的产出

每条 lineItem 完成填写后,wizard state 多出 8 个字段(annual_volume / discount_source / discount_base_amount / discount_rate_applied / line_discount_amount / line_unit_price / line_final_price / line_total_amount);走到 Step4 时,顶部"商务条款"区显示新版总价 `Σ line_total_amount`;Step5 提交时一次性快照入库。

---

## 2. 核心决策清单(D1–D6)

| # | 主题 | 决策 | 依据 |
|---|---|---|---|
| **D1** | 年用量是否参与最终金额 | **是**;新增列「总金额 = annual_volume × line_final_price」(line_total_amount 落库);`quotation.total_amount = SUM(line_total_amount)`;表格底部"金额汇总"= Σ 同源 | 用户确认 |
| **D2** | "优惠金额来源"下拉项 | 对齐核价 7 metric + 「整单小计 SUBTOTAL」兜底,共 **8 项**;当前料号在 `v_costing_summary_full` 该列为 NULL 时下拉灰显(不可选) | 用户确认 |
| **D3** | 现有整单折扣处理 | **V1 并存但置 100 不使用**;`quotation.system_discount_rate / final_discount_rate` 字段保留,Step3 不写入;V2 PricingStrategy 上线再决定合并/废弃 | 用户确认 |
| **D4** | 折扣引擎实现 | V1 硬编码 `AnnualVolumeStepDiscount` 4 阶梯;接口 `DiscountStrategy` 预留扩展,V2 切 `PricingStrategyDiscount` 读 `pricing_strategy / pricing_rule` 表(已建);切换走 `@LookupIfProperty` + `cpq.discount.strategy` 配置 | PartNoProvider 已验证此模式 |
| **D5** | 用户能否手动覆盖折扣率 | **V1 不允许**;折扣列只读、由引擎算出。原因:可审计、可复算、与"阶梯硬编码"语义一致。V2 PricingStrategy 上线时再开"手动覆盖"开关 | PRD v1.4「无策略兜底 0%」精神 |
| **D6** | 进入 Step3 时单价的刷新 | **强刷**:每次进 Step3 → 用 `line_item.subtotal` 重新写入 `line_unit_price` + 触发基数/折扣/优惠金额连锁重算;若用户已在 Step3 填过年用量,保留 annual_volume + discount_source;反之全空 | PRD v1.8「进入步骤三时强制刷新折扣计算」 |

---

## 3. 字段映射

### 3.1 UI 11 列 ↔ 数据来源 ↔ 存储字段

| # | UI 列 | 类型 | 数据来源 | 存储字段(quotation_line_item) | 备注 |
|---|---|---|---|---|---|
| 1 | 产品 | 只读文本(双行)| 主行 **生产料号** `line_item.productPartNo`(monospace);副行 **品名** `productName`(小字灰显,无值省略);不显示客户料号 / 客户品名 | — | Step1 已有;商务页面以料号识别为主,品名为辅 |
| 2 | 年用量 | **可编辑(整数)** | 用户输入 | `annual_volume` INT | ★新增;空时 = 0,折扣 = 0 |
| 3 | 优惠金额来源 | **可编辑(Select)** | 8 项硬编码下拉 | `discount_source` VARCHAR(32) | ★新增;默认 `PROCESS_FEE`(加工费) |
| 4 | 可优惠金额基数 | 只读派生 | `POST /formulas/evaluate` → `v_costing_summary_full.<metric_col>` 隐式 JOIN by partNo | `discount_base_amount` NUMERIC | ★新增;commit 时快照 |
| 5 | 折扣 | 只读派生 | `DiscountStrategy.compute(annual_volume)` | `discount_rate_applied` NUMERIC(8,4) | ★新增;此处直接是优惠%,如 10 表示减 10% |
| 6 | 优惠金额 | 只读派生 | `discount_base_amount × discount_rate_applied / 100` | `line_discount_amount` NUMERIC | ★新增;commit 时快照(单件优惠额) |
| 7 | 计价单位 | 只读 | `line_item.productAttributeValues['计量单位']` ‖ `mat_part.unit` 兜底 ‖ `'PCS'` | (无需新存,展示派生) | 与 Step2 计价单位一致 |
| 8 | 币种 | 只读 | `quotation.base_currency` | (无需新存) | 整单级 |
| 9 | 单价 | 只读 | `line_item.subtotal`(Step2 出参) | `line_unit_price` NUMERIC | ★新增;commit 时快照,= subtotal |
| 10 | 优惠后单价 | 只读派生 | `line_unit_price - line_discount_amount` | `line_final_price` NUMERIC | ★新增;commit 时快照(单件优惠后价) |
| 11 | 总金额 | 只读派生 | `annual_volume × line_final_price` | `line_total_amount` NUMERIC(18,4) | ★新增;commit 时快照;空年用量 → 0 |

### 3.2 "优惠金额来源" 8 项下拉(对齐 costing_summary_result.metric_code)

| metric_code | 中文标签 | 对应 v_costing_summary_full 列 |
|---|---|---|
| MATERIAL_COST | 材料成本 | `material_cost` |
| PROCESS_FEE | 加工费 | `processing_cost` |
| TOOLING_FEE | 模具工装费 | `tooling_cost` |
| DESIGN_COST | 设计成本 | `design_cost` |
| MANAGEMENT_COST | 管理费 | `management_cost` |
| FINANCE_COST | 财务费 | `finance_cost` |
| PROFIT | 利润 | `profit` |
| SUBTOTAL | 整单小计兜底 | (前端直接读 `line_item.subtotal`,不查后端) |

> **下拉灰显规则**:某料号在 `v_costing_summary_full` 的 metric 列为 NULL → 对应下拉项 `disabled + tooltip "该料号无此项核价数据"`。SUBTOTAL 项永远可选(基数恒为 line_item.subtotal)。

### 3.3 整单总价计算(D1)

```
line_total_amount        =  annual_volume × line_final_price       (行级,落 quotation_line_item)
quotation.total_amount   =  Σ line_total_amount over lineItems     (整单,Step3 表格底部 + Step4 商务条款 + 提交快照)
```

- 任一行 `annual_volume` 为空 / 0 → `line_total_amount = 0`(不阻断保存)
- 任一行 `line_final_price` 为空 → 视为 = line_unit_price(无优惠);`line_total_amount = annual_volume × line_unit_price`
- 行级 `line_total_amount` 单独入库一份(AP-11 快照原则),整单 `quotation.total_amount` 通过 `Quotation.updateTotal()` 由后端聚合,前端 Step3 表格下方 Statistic 卡显示同一值
- 计算执行链:前端 onBlur/onChange → 行级实时算 line_total_amount → 表格底部 Σ 即时刷新;Step3 → Step4 保存草稿时后端再聚合写 quotation.total_amount

---

## 4. 业务流程与计算流

### 4.1 进入 Step3 的初始化(D6)

```
QuotationWizard 推进到 Step3
   ↓
Step3.onMount  → 对每个 lineItem:
   1) line_unit_price ← line_item.subtotal (强刷)
   2) 读已存的 annual_volume / discount_source (有 → 保留;无 → 默认 0 / 'PROCESS_FEE')
   3) 异步并行调 /formulas/evaluate 拉每行的 discount_base_amount
   4) 引擎实时算 discount_rate_applied / line_discount_amount / line_final_price
   ↓
表格渲染完成
```

### 4.2 用户交互回路

```
用户改 annual_volume (数字输入框 onBlur)
   ↓ setLineItems(prev => prev.map(...))  ★函数式(AP-9/AP-10)
引擎算 discount_rate_applied
   ↓
重算 line_discount_amount = base × rate / 100
   ↓
重算 line_final_price = line_unit_price - line_discount_amount
   ↓
重算 line_total_amount = annual_volume × line_final_price       ★新增
   ↓
Step3 底部汇总卡:Σ line_total_amount

用户改 discount_source (Select onChange)
   ↓ setLineItems(prev => prev.map(...))
异步调 /formulas/evaluate (新 metric_col)
   ↓ Promise 回填 discount_base_amount
   ↓
重算优惠金额 / 优惠后单价 / 总金额(三连)
   ↓
若用户在此期间又改了年用量 → 函数式 setState 自动合并,不丢失新输入 (AP-9)
```

### 4.3 进入 Step4 / Step5(校验 + 快照)

| 关卡 | 校验 | 失败处理 |
|---|---|---|
| Step3 → Step4 | 每行 `line_final_price >= 0` | 标红行 + Toast "优惠后单价不能为负" + 阻塞前进 |
| Step3 → Step4 | annual_volume >= 0 整数 | 标红 + 阻塞 |
| Step5 提交 | 后端 `DiscountStrategy.compute()` 复算 + ±0.01 容差 | 不一致 → 400 + 返回服务端值供前端覆盖(对齐 PRD v1.8-patch6) |
| Step5 提交 | 9 个字段 round-trip 入 quotation_line_item | 见 §5.3 SaveDraftRequest 改造 |

---

## 5. 数据模型 — V162 DDL

### 5.1 V162__step3_annual_volume_discount.sql

```sql
-- V162__step3_annual_volume_discount.sql
-- Step3「优惠策略」改造:行级年用量 + 阶梯折扣 + 来源/基数/优惠金额/总金额快照

ALTER TABLE quotation_line_item
  ADD COLUMN annual_volume         INT           NULL,
  ADD COLUMN discount_source       VARCHAR(32)   NULL,
  ADD COLUMN discount_base_amount  NUMERIC(18,4) NULL,
  ADD COLUMN discount_rate_applied NUMERIC(8,4)  NULL,
  ADD COLUMN line_discount_amount  NUMERIC(18,4) NULL,
  ADD COLUMN line_unit_price       NUMERIC(18,4) NULL,
  ADD COLUMN line_final_price      NUMERIC(18,4) NULL,
  ADD COLUMN line_total_amount     NUMERIC(18,4) NULL,
  ADD COLUMN discount_rule_code    VARCHAR(64)   NULL;

COMMENT ON COLUMN quotation_line_item.annual_volume         IS '年用量(用户输入,驱动阶梯折扣)';
COMMENT ON COLUMN quotation_line_item.discount_source       IS '优惠金额来源 metric_code(MATERIAL_COST/PROCESS_FEE/.../SUBTOTAL)';
COMMENT ON COLUMN quotation_line_item.discount_base_amount  IS '可优惠金额基数 — Step3 commit 时快照';
COMMENT ON COLUMN quotation_line_item.discount_rate_applied IS '实际折扣率 %(0-100,V1 由阶梯引擎硬算)';
COMMENT ON COLUMN quotation_line_item.line_discount_amount  IS '单件优惠金额(=base × rate / 100,快照)';
COMMENT ON COLUMN quotation_line_item.line_unit_price       IS '单价 = line_item.subtotal(快照,防 Step2 后续被改)';
COMMENT ON COLUMN quotation_line_item.line_final_price      IS '优惠后单价(=line_unit_price - line_discount_amount,快照)';
COMMENT ON COLUMN quotation_line_item.line_total_amount     IS '行总金额(=annual_volume × line_final_price,快照;Σ即整单total_amount)';
COMMENT ON COLUMN quotation_line_item.discount_rule_code    IS '命中的折扣规则编号(V1 = ANNUAL_VOLUME_STEP_V1)';

CREATE INDEX IF NOT EXISTS idx_qli_discount_source
  ON quotation_line_item(discount_source)
  WHERE discount_source IS NOT NULL;
```

> **不加 CHECK 约束**:`discount_rate_applied ∈ [0,100]` 等校验放在 service 层(AnnualVolumeStepDiscount 输出已可控),DB CHECK 容易在跨版本数据迁移时卡脖子。

### 5.2 不动的字段(防止误删)

```
quotation.system_discount_rate     — 保留,V1 不写
quotation.final_discount_rate      — 保留,V1 不写
quotation.discount_adjustment_reason — 保留,V1 不写
quotation_line_item.discount_rate_snapshot — 提交快照字段,V1 写入 NULL,V2 评估是否复用
```

### 5.3 SaveDraftRequest.LineItemDraft 扩展(AP-2 必修)

```java
// cpq-backend/src/main/java/com/cpq/quotation/dto/SaveDraftRequest.java
public static class LineItemDraft {
    // ... 原有字段不动 ...
    public BigDecimal subtotal;

    // ★ Step3 新增 9 字段
    public Integer    annualVolume;
    public String     discountSource;      // metric_code
    public BigDecimal discountBaseAmount;
    public BigDecimal discountRateApplied;
    public BigDecimal lineDiscountAmount;
    public BigDecimal lineUnitPrice;
    public BigDecimal lineFinalPrice;       // 优惠后单价
    public BigDecimal lineTotalAmount;      // 总金额 = annualVolume × lineFinalPrice
    public String     discountRuleCode;
}
```

`QuotationDTO.LineItemDTO` 同步加 9 字段;`LineItemDTO.from(entity)` 回读;`QuotationService.saveDraft` 写入对应 entity 列。`Quotation.updateTotal()` 改为 `SUM(line_total_amount)`(对齐 D1)。

---

## 6. API 设计

### 6.1 新增端点 — 0 个(YAGNI)

V1 前端硬编码同一份阶梯逻辑,实时算给用户看;后端在 commit 时调 `DiscountStrategy` 复算 + ±0.01 容差校验即可。V2 切 PricingStrategy 后再放出 `POST /api/cpq/quotations/{id}/discount-preview` 端点。

### 6.2 现有端点扩展

| 方法 | 路径 | 改动 |
|---|---|---|
| `PUT` | `/api/cpq/quotations/{id}/draft` | 接收 LineItemDraft.9 新字段,写入 quotation_line_item;`Quotation.updateTotal() = SUM(line_total_amount)` |
| `POST` | `/api/cpq/quotations/{id}/submit` | commit 时 `DiscountStrategy.compute()` 复算 + 校验;入 `*_snapshot` 列;聚合 SUM(line_total_amount) 写 quotation.total_amount |
| `GET` | `/api/cpq/quotations/{id}` | LineItemDTO 返回 9 新字段供前端 Step3 装载 |

---

## 7. 阶梯折扣引擎

### 7.1 接口定义

```java
// cpq-backend/src/main/java/com/cpq/discount/DiscountStrategy.java
public interface DiscountStrategy {
    DiscountResult compute(DiscountContext ctx);

    /** 标识 V1 / V2 / ... 切换时的规则 code,落入 quotation_line_item.discount_rule_code */
    String ruleCode();
}

public class DiscountContext {
    public UUID       quotationId;
    public UUID       customerId;
    public UUID       lineItemId;
    public String     productPartNo;
    public Integer    annualVolume;      // null → 视为 0
    public String     discountSource;    // metric_code
    public BigDecimal baseAmount;        // null → 视为 0
    public BigDecimal unitPrice;
}

public class DiscountResult {
    public BigDecimal rateApplied;       // 0-100
    public BigDecimal discountAmount;    // 单件优惠 = base × rate / 100
    public BigDecimal finalPrice;        // 优惠后单价 = unitPrice - discountAmount
    public BigDecimal totalAmount;       // 行总金额 = annualVolume × finalPrice
    public String     ruleCode;
    public String     description;       // "年用量 100, 折扣 0% (<200 阶段)"
}
```

### 7.2 V1 实现 — AnnualVolumeStepDiscount

```java
@ApplicationScoped
@LookupIfProperty(name = "cpq.discount.strategy", stringValue = "annual_volume_step", lookupIfMissing = true)
public class AnnualVolumeStepDiscount implements DiscountStrategy {

    private static final String RULE_CODE = "ANNUAL_VOLUME_STEP_V1";

    @Override
    public DiscountResult compute(DiscountContext ctx) {
        int qty = ctx.annualVolume == null ? 0 : ctx.annualVolume;
        BigDecimal rate;
        String stage;
        if (qty < 200)       { rate = BigDecimal.ZERO;        stage = "<200";       }
        else if (qty < 500)  { rate = BigDecimal.valueOf(10); stage = "200-499";    }
        else if (qty < 1000) { rate = BigDecimal.valueOf(20); stage = "500-999";    }
        else                 { rate = BigDecimal.valueOf(30); stage = ">=1000";     }

        BigDecimal base = ctx.baseAmount == null ? BigDecimal.ZERO : ctx.baseAmount;
        BigDecimal amount = base.multiply(rate)
                                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

        BigDecimal unitPrice = ctx.unitPrice == null ? BigDecimal.ZERO : ctx.unitPrice;
        BigDecimal finalPrice = unitPrice.subtract(amount);
        if (finalPrice.signum() < 0) finalPrice = BigDecimal.ZERO;   // 不允许负价

        BigDecimal totalAmount = finalPrice.multiply(BigDecimal.valueOf(qty))
                                           .setScale(4, RoundingMode.HALF_UP);

        DiscountResult r = new DiscountResult();
        r.rateApplied = rate;
        r.discountAmount = amount;
        r.finalPrice = finalPrice;
        r.totalAmount = totalAmount;
        r.ruleCode = RULE_CODE;
        r.description = String.format("年用量 %d,折扣 %s%% (%s 阶段)", qty, rate.toPlainString(), stage);
        return r;
    }

    @Override
    public String ruleCode() { return RULE_CODE; }
}
```

### 7.3 V2 切换路径(预留扩展点)

```properties
# application.properties
cpq.discount.strategy=annual_volume_step       # V1
# cpq.discount.strategy=pricing_strategy       # V2(切到 PricingStrategy 表驱动)
```

```java
@ApplicationScoped
@LookupIfProperty(name = "cpq.discount.strategy", stringValue = "pricing_strategy")
public class PricingStrategyDiscount implements DiscountStrategy {
    // 读 customer.pricing_strategy_id → pricing_rule.* → 匹配年用量 / 客户分级 / 品类
}
```

**关键**:前端 0 改动,wizard / 表格 / SaveDraft DTO 全部走通用 8 字段;切换只是后端 service 注入不同的 `DiscountStrategy` bean。

### 7.4 前端实现同一份阶梯(实时性)

为了让用户输入年用量后立即看到折扣率,前端也实现一份:

```ts
// cpq-frontend/src/utils/discountStrategy.ts
export interface DiscountResult {
  rateApplied: number;
  discountAmount: number;   // 单件优惠
  finalPrice: number;       // 优惠后单价
  totalAmount: number;      // 行总金额 = annualVolume × finalPrice
  ruleCode: string;
}

export function computeAnnualVolumeStep(
  annualVolume: number,
  baseAmount: number,
  unitPrice: number,
): DiscountResult {
  let rate = 0;
  if (annualVolume >= 1000)      rate = 30;
  else if (annualVolume >= 500)  rate = 20;
  else if (annualVolume >= 200)  rate = 10;
  else                            rate = 0;
  const amount = +(baseAmount * rate / 100).toFixed(4);
  const finalPrice = Math.max(0, +(unitPrice - amount).toFixed(4));
  const totalAmount = +(annualVolume * finalPrice).toFixed(4);
  return { rateApplied: rate, discountAmount: amount, finalPrice, totalAmount, ruleCode: 'ANNUAL_VOLUME_STEP_V1' };
}
```

> 后端 commit 时复算,前后端误差 > ±0.01 → 400(对齐 PRD v1.8-patch6)。

---

## 8. 前端实现路径

### 8.1 文件清单

| 新建 / 改动 | 文件 | 用途 |
|---|---|---|
| 新建 | `cpq-frontend/src/pages/quotation/QuotationStep3.tsx` | Step3 独立组件(从 Wizard 拆出)|
| 新建 | `cpq-frontend/src/utils/discountStrategy.ts` | V1 阶梯函数 + 类型 |
| 新建 | `cpq-frontend/src/services/discountSourceService.ts` | 8 项 metric 元数据 + `fetchBaseAmount(partNo, source)` 封装 |
| 改动 | `cpq-frontend/src/pages/quotation/QuotationWizard.tsx` | 把 Step3 区域换成 `<QuotationStep3 ... />`;wizard state 新增 8 字段;buildDraftPayload 同步透传 |
| 改动 | `cpq-frontend/src/types/quotation.ts`(若有)| LineItem 类型加 8 字段 |
| 改动 | `cpq-frontend/src/services/quotationService.ts` | LineItemDTO 类型扩展 |

### 8.2 Step3 表格组件结构

```tsx
<QuotationStep3
  lineItems={lineItems}
  baseCurrency={quotation.baseCurrency}
  onUpdate={(updater) => setLineItems(prev => updater(prev))}  // 函数式(AP-10)
>
  <SelectableTable                                              // 列表操作规范
    columns={[产品 / 年用量 / 优惠金额来源 / 可优惠金额基数 / 折扣 / 优惠金额 / 计价单位 / 币种 / 单价 / 优惠后单价 / 总金额]}
    rows={lineItems}
  />
  <Card title="金额汇总">
    <Statistic
      title="所有产品总金额累加"
      value={lineItems.reduce((sum, li) => sum + (li.lineTotalAmount ?? 0), 0)}
      precision={2}
      suffix={baseCurrency}
    />
  </Card>
</QuotationStep3>
```

### 8.3 列编辑模式

- **年用量**:`<InputNumber min={0} step={1} />` 行内编辑,onBlur 触发重算
- **优惠金额来源**:`<Select options={DISCOUNT_SOURCES}>`,onChange 触发异步拉基数 → 重算
- 其余列只读,数值格式化(币种 + 千分位)

### 8.4 异步基数拉取(AP-9 防护)

```ts
async function fetchBaseAmount(partNo: string, source: string): Promise<number | null> {
  if (source === 'SUBTOTAL') return null;  // SUBTOTAL 直接读 line.subtotal
  const metricCol = METRIC_TO_COL[source];   // 例 'PROCESS_FEE' → 'processing_cost'
  const res = await formulaService.evaluate({
    expression: `{v_costing_summary_full.${metricCol}}`,
    partNo,
  });
  return typeof res.value === 'number' ? res.value : null;
}

// 调用时:setLineItems(prev => prev.map(...)) 用函数式合并
// 用户在 await 期间改了年用量 → cur 的最新输入保住
```

### 8.5 wizard state 数据流

```
QuotationWizard.state.lineItems[]   ←  唯一真相
  ├─ Step2 写入: subtotal / row_data / componentData
  └─ Step3 写入: annualVolume / discountSource / discountBaseAmount / discountRateApplied
                  lineDiscountAmount / lineUnitPrice / lineFinalPrice
                  lineTotalAmount / discountRuleCode
                  ↓
QuotationWizard.buildDraftPayload  →  SaveDraftRequest.LineItemDraft (9 + 原有)
                  ↓
后端写库  →  quotation_line_item.<列>
                  ↓
Quotation.updateTotal() = SUM(line_total_amount)
                  ↓
quotation.total_amount  (Step3 表格底部 + Step4 商务条款 + 提交快照 同源)
```

---

## 9. 反模式对照

| AP | 反模式 | 本设计的防护 |
|---|---|---|
| AP-1 | UUID 空串传后端 | 不涉及(全部 INT/DECIMAL/VARCHAR)|
| AP-2 | SaveRequest 丢字段 | `SaveDraftRequest.LineItemDraft` 同步加 9 字段 + `LineItemDTO.from()` 回读 + Step3 装载时填回 |
| AP-3 | 派生靠 lookup 失败 | 9 个值全部入快照,不依赖运行时反查 |
| AP-9 | 异步 enrich 整张盖 | Step3 表格用 `setLineItems(prev => prev.map(...))` 函数式合并;异步基数拉取完成后只覆盖 `discount_base_amount` + 重算下游 5 字段(折扣/优惠金额/优惠后单价/总金额/+ 整单合计),不动用户当前输入的 `annual_volume` |
| AP-10 | mutator 对象式 onUpdate | Step3 所有行编辑(年用量 / 来源)用 `onUpdate(prev => ...)`;与 QuotationStep2 既有 4 mutator 写法一致 |
| AP-11 | WYSIWYG 不一致(只存输入)| 屏幕显示的 6 个派生值(基数/折扣/优惠金额/单价/优惠后单价/总金额)**全部** commit 入 quotation_line_item;不依赖前端运行时 recompute |
| AP-12 | 懒资源 GET 404 | 不涉及(9 字段都是 nullable,GET 时未填的行返 NULL 由前端兜底)|
| AP-18 | Flyway dev mode hot-reload 不重跑 | V162 写完后必须改一处 java 内容触发完整重启 |
| AP-19 | 1:1 FK 错配 | 不涉及(8 字段都是标量)|

### 9.1 新增反模式风险(本次自审)

**RISK-3:Step2 改变 line.subtotal 后,Step3 已经存的 line_unit_price 怎么办?**
- 当前设计:每次进 Step3 强刷 `line_unit_price ← line_item.subtotal`(D6),保留 `annual_volume / discount_source`
- 如果用户走过 Step3 → 又返回 Step2 改了产品 → 再次进 Step3,新的 line_unit_price 会替换旧的;折扣/优惠金额自动重算
- 副作用:用户可能没意识到 line_final_price 变了 → 加 Toast 提示"产品数据变更,优惠后金额已重算"(对齐 PRD v1.8 提示)

**RISK-4:V1 阶梯硬编码 + V2 PricingStrategy 切换时,旧报价单读到的 discount_rule_code = 'ANNUAL_VOLUME_STEP_V1' 不再生效**
- 已规避:`*_snapshot` 列已快照 base/rate/amount/finalPrice,读旧报价单直接展示快照值,**不重新调引擎**;只有新建/编辑时才走当前引擎

---

## 10. 实施清单与验收

### 10.1 后端清单

- [ ] Flyway `V162__step3_annual_volume_discount.sql`(加 9 列 + 1 索引 + 注释)
- [ ] DTO 扩展:`SaveDraftRequest.LineItemDraft` 加 9 字段
- [ ] DTO 扩展:`QuotationDTO.LineItemDTO` 加 9 字段
- [ ] Entity:`QuotationLineItem.java` 加 9 列(`@Column` 映射)
- [ ] Service:`QuotationService.saveDraft` 写 9 字段 + `Quotation.updateTotal() = SUM(line_total_amount)`
- [ ] Service:`QuotationService.submit` commit 时调 `DiscountStrategy.compute()` 复算 + ±0.01 校验
- [ ] Service:新建 `cpq-backend/src/main/java/com/cpq/discount/` 包
  - [ ] `DiscountStrategy.java` 接口
  - [ ] `DiscountContext.java / DiscountResult.java`
  - [ ] `AnnualVolumeStepDiscount.java`(V1 实现)
  - [ ] 单元测试:覆盖 4 阶梯边界(<200 / 200 / 500 / 1000)+ null annualVolume + 负价熔断
- [ ] 集成测试:`QuotationServiceTest` 增加
  - 保存 LineItem 含 9 字段 → 读回字段值完整(round-trip)
  - submit 时 commit 复算 → 与前端值一致(含 lineTotalAmount)
  - 篡改 lineFinalPrice 或 lineTotalAmount 提交 → 400(容差超限)
  - Quotation.total_amount = SUM(line_total_amount) 数值正确(单测 + 多行场景)
  - 改一行的 annualVolume 后 → SUM 重算后 quotation.total_amount 同步更新

### 10.2 前端清单

- [ ] `QuotationStep3.tsx` 新建(SelectableTable + 底部合计 Card)
- [ ] `utils/discountStrategy.ts`(V1 阶梯函数 + 单测)
- [ ] `services/discountSourceService.ts`(8 项元数据 + fetchBaseAmount)
- [ ] `QuotationWizard.tsx` 集成 Step3 组件 + state 加 9 字段 + buildDraftPayload 透传
- [ ] `types/quotation.ts` LineItem 类型扩展
- [ ] tsc --noEmit 0 错 + 改动文件 Vite 200(对齐 CLAUDE.md「修改后强制自检」)
- [ ] 浏览器手测:
  - 进 Step3 → 单价自动 = Step2 subtotal
  - 填年用量 100 → 折扣 0% / 优惠金额 0 / 优惠后单价 = 单价 / 总金额 = 100 × 单价
  - 改年用量 500 → 折扣 20% / 优惠金额 = base × 0.2 / 优惠后单价 = 单价 - 优惠金额 / 总金额 = 500 × 优惠后单价
  - 切换"优惠金额来源" → 基数变 → 优惠金额/优惠后单价/总金额三连联动
  - 同行多次切换 + 输入年用量 → 用户输入不丢(AP-9)
  - 底部「金额汇总」 = Σ 总金额(随任一行年用量/优惠后单价变化即时刷新)
  - 多行场景:行 A 总金额 100 + 行 B 总金额 50 → 底部 = 150
  - 返回 Step2 改产品 subtotal → 再进 Step3 单价自动刷新 + 优惠后单价 + 总金额连锁刷新 + Toast 提示

### 10.3 自检声明模板

```
TS 0 错误 ✅
QuotationStep3.tsx → Vite 200 ✅
后端 PUT /quotations/{id}/draft → 200 (含 9 新字段 round-trip,line_total_amount = annual_volume × line_final_price) ✅
Flyway V162 success=t (含 line_total_amount 列) ✅
AnnualVolumeStepDiscount 单测 4 阶梯 + null + 负价熔断 + totalAmount 计算 全绿 ✅
quotation.total_amount = SUM(line_total_amount) 多行场景验证 ✅
浏览器手测 7 个交互场景全通 ✅
```

---

## 11. 已知限制与未来扩展

| # | 限制 | 计划 |
|---|---|---|
| 1 | V1 折扣引擎硬编码 4 阶梯,业务调整需改代码 + 重启 | V2 切 `PricingStrategy` 表驱动,接口已预留 |
| 2 | 不支持手动覆盖折扣率(D5) | V2 在 Step3 加"手动覆盖"开关 + 备注必填(对齐 PRD v1.2 manualDiscount) |
| 3 | "优惠金额来源" 8 项硬编码,不支持多源叠加(如同时享受材料 + 加工双折扣)| V2 改为多行配置:同一 lineItem 下挂 N 条 `quotation_line_item_discount`(子表)|
| 4 | 整单级折扣 `system_discount_rate` 与行级折扣未合并 | V2 PricingStrategy 上线时统一 |
| 5 | 不支持"按客户分级动态阶梯"(VIP 客户阶梯比标准客户优惠)| V2 PricingStrategy 引擎读 customer.level 决定阶梯 |
| 6 | "优惠后金额"可达 0 但不允许负 — 极端情况"优惠金额 > 单价"被静默截断到 0 | 加 UI 警示:`if (discount > unitPrice) Toast "优惠金额超出单价,已截断到 0"` |
| 7 | 阶梯边界(200/500/1000)硬编码在前后端两份,改一个忘改另一个 → 不一致 | V1 阶梯写在共享配置(`application.properties` 或 V162 seed 一行进 system_config),前后端启动读取 |

---

## 附录 A — 与现有架构的关系图

```
QuotationWizard (5 步,不动框架)
   │
   ├─ Step1 选客户          (不动)
   ├─ Step2 添加产品        (不动;subtotal 输出给 Step3)
   ├─ Step3 优惠策略       ★本设计:列表 + 行级 8 字段
   ├─ Step4 交易条款       (合计公式改为按行求和;UI 无改动)
   └─ Step5 提交审批       (commit 时 DiscountStrategy 复算 + 快照)

后端
   │
   ├─ DiscountStrategy 接口            ★新增
   ├─ AnnualVolumeStepDiscount (V1)    ★新增
   ├─ QuotationLineItem +9 列          ★V162(含 line_total_amount)
   ├─ Quotation.updateTotal()          (公式改为 SUM(line_total_amount))
   └─ SaveDraftRequest.LineItemDraft +9 字段
```

**承诺**:本设计落地后,Step1/2/4/5、模板、组件、核价、Excel 视图、公式引擎、V6 导入 任意 API 输入输出 **字节级不变**。

---

## 附录 B — 维护

- 实施后 RECORD.md 追加条目:`[2026-05-13] Step3 优惠策略改造 | V162 + 9 列(含 line_total_amount) + DiscountStrategy 接口 + AnnualVolumeStepDiscount V1 | 整单总价改 SUM(line_total_amount)`
- **PRD-v3.md** §3.2.3 第三步章节重写 + §9.8 演进史增 v3.1 条目(注:`docs/PRD.md` 已废弃归档,**不写**)
- 反模式.md 若实施过程命中 RISK-3 / RISK-4 → 升级为 AP-NN
