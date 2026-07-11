# Step3 行级折扣重设 全链路实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把报价单 Step3「优惠策略」从"前端临时计算、不落库"重做为：折扣来源=产品小计公式里的页签小计(单选)、折扣率手填、按所选页签小计代回公式重算折后小计，并全链路落库(保存/重开/提交/导出)。

**Architecture:** 折后小计 = 用同一产品小计公式，把所选页签小计的列和乘 `(1 − 折扣率/100)` 后重新求值。前端在 `QuotationStep3.tsx` 计算并写入 9 个行字段(payload 早已透传)；后端补全 DB 列/实体/DTO/保存映射，并在提交时用 `ComponentDataEffectiveRows` 的折扣重载**权威重算**每行折后小计、按 `Σ行合计` 重算整单总额；导出 Excel/HTML 体现折扣。

**Tech Stack:** Quarkus 3 + Hibernate Panache + Flyway(PostgreSQL) 后端；React + Ant Design + TypeScript 前端；Apache POI(Excel) + Qute(HTML/PDF)。

**关键背景(实现前必读):**
- Step3 是**半成品**：前端 `QuotationStep2.tsx` `LineItem` 接口(行 208-226)已声明 9 字段，`QuotationWizard.tsx` `buildDraftPayload` 已透传这 9 字段；但后端 `SaveDraftRequest.LineItemDraft` 没接、`quotation_line_item` 没列、saveDraft 没存、`LineItemDTO` 没回读 → 刷新即丢。
- 旧 `calculate-discount`(整单单率 + pricing_strategy)在本页**弃用**，按钮移除。该后端端点可保留(他处不引用 Step3)，本计划不删它。
- 9 字段(严格按前端字段名 / 列名)：`annualVolume/annual_volume`、`discountSource/discount_source`、`discountBaseAmount/discount_base_amount`、`discountRateApplied/discount_rate_applied`、`lineDiscountAmount/line_discount_amount`、`lineUnitPrice/line_unit_price`、`lineFinalPrice/line_final_price`、`lineTotalAmount/line_total_amount`、`discountRuleCode/discount_rule_code`。
- `discountSource` 存**页签的 component_code**(前端 component_subtotal token 的 `component_code`，回退 `value`)；特殊值 `'SUBTOTAL'` = 总金额(默认)。
- 计算口径(已与用户确认)：
  - 原小计 `S0` = 产品小计(单件)。
  - 折扣来源=`SUBTOTAL`：折后小计 `S1 = S0 × (1 − r/100)`。
  - 折扣来源=某页签 code=C：把 C 页签的列和 ×`(1 − r/100)` 后代回产品小计公式重算得 `S1`。
  - `lineDiscountAmount = (S0 − S1) × annualVolume`；`lineTotalAmount = S1 × annualVolume`；`lineFinalPrice = S1`；`lineUnitPrice = S0`；`discountBaseAmount` = 被折基数(SUBTOTAL→S0；页签→该页签小计)。
  - `r=0` 或未选来源 → `S1=S0`，折扣金额=0。

**协议级改动(强制 E2E)：** 本计划改动 `QuotationStep2.tsx`、`QuotationWizard.tsx`、`FormulaCalculator.java`、submit 路径、Flyway 迁移 —— 命中 CLAUDE.md「修改后强制自检」第 5 条，**完成后必须跑 `quotation-flow.spec.ts` 并附截图**。

---

## 文件结构

| 文件 | 职责 | 动作 |
|---|---|---|
| `cpq-backend/.../db/migration/V302__qli_add_step3_discount_columns.sql` | 加 9 列 | Create |
| `cpq-backend/.../quotation/entity/QuotationLineItem.java` | 实体 9 字段 | Modify |
| `cpq-backend/.../quotation/dto/SaveDraftRequest.java` | LineItemDraft 9 字段 | Modify |
| `cpq-backend/.../quotation/dto/QuotationDTO.java` | LineItemDTO 9 字段声明 + from() 映射 | Modify |
| `cpq-backend/.../quotation/service/QuotationService.java` | saveDraft 存 9 字段；submit 权威重算 + Σ总额 | Modify |
| `cpq-backend/.../quotation/service/card/ComponentDataEffectiveRows.java` | 折扣重载(Pass1 缩放页签列和) | Modify |
| `cpq-backend/.../quotation/service/LineDiscountService.java` | 单行折后小计重算 + 写 9 字段 | Create |
| `cpq-backend/.../quotation/service/QuotationExportService.java` | Excel/HTML 体现折扣 | Modify |
| `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` | 拆 `getComponentSubtotals` / `evalProductSubtotalFromSubtotals` | Modify |
| `cpq-frontend/src/pages/quotation/lineDiscount.ts` | 折扣来源抽取 + 折后小计计算 | Create |
| `cpq-frontend/src/pages/quotation/lineDiscount.test.ts` | 单测 | Create |
| `cpq-frontend/src/pages/quotation/QuotationStep3.tsx` | 重做：动态来源 + 手填率 + 重算 + 写字段 | Modify |
| `cpq-frontend/src/pages/quotation/QuotationWizard.tsx` | 给 Step3 传 driverExpansions + customerId | Modify |

---

## Task 1: 后端持久化管道（加列 / 实体 / DTO / 保存 / 回读，无行为变更）

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V302__qli_add_step3_discount_columns.sql`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/entity/QuotationLineItem.java`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/dto/SaveDraftRequest.java`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/dto/QuotationDTO.java`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`

- [ ] **Step 1: 写 Flyway 迁移 V302**（创建文件，内容如下）

```sql
-- V302: Step3 行级折扣字段落库（原 QuotationStep3 注释声称已有实为缺失，此处补全）
ALTER TABLE quotation_line_item
    ADD COLUMN IF NOT EXISTS annual_volume        INTEGER,
    ADD COLUMN IF NOT EXISTS discount_source      VARCHAR(64),
    ADD COLUMN IF NOT EXISTS discount_base_amount NUMERIC(18,4),
    ADD COLUMN IF NOT EXISTS discount_rate_applied NUMERIC(5,2),
    ADD COLUMN IF NOT EXISTS line_discount_amount NUMERIC(18,4),
    ADD COLUMN IF NOT EXISTS line_unit_price      NUMERIC(18,4),
    ADD COLUMN IF NOT EXISTS line_final_price     NUMERIC(18,4),
    ADD COLUMN IF NOT EXISTS line_total_amount    NUMERIC(18,4),
    ADD COLUMN IF NOT EXISTS discount_rule_code   VARCHAR(64);
```

- [ ] **Step 2: 实体加 9 字段** — 在 `QuotationLineItem.java` 行 55 `public Integer sortOrder = 0;` 之后插入：

```java
    // ─── Step3 行级折扣（V302）─────────────────────────────────────────────
    @Column(name = "annual_volume")
    public Integer annualVolume;

    @Column(name = "discount_source", length = 64)
    public String discountSource;

    @Column(name = "discount_base_amount", precision = 18, scale = 4)
    public BigDecimal discountBaseAmount;

    @Column(name = "discount_rate_applied", precision = 5, scale = 2)
    public BigDecimal discountRateApplied;

    @Column(name = "line_discount_amount", precision = 18, scale = 4)
    public BigDecimal lineDiscountAmount;

    @Column(name = "line_unit_price", precision = 18, scale = 4)
    public BigDecimal lineUnitPrice;

    @Column(name = "line_final_price", precision = 18, scale = 4)
    public BigDecimal lineFinalPrice;

    @Column(name = "line_total_amount", precision = 18, scale = 4)
    public BigDecimal lineTotalAmount;

    @Column(name = "discount_rule_code", length = 64)
    public String discountRuleCode;
```

- [ ] **Step 3: SaveDraftRequest.LineItemDraft 加 9 字段** — 在 `compositeProcesses` 声明之后(类 `LineItemDraft` 末尾 `}` 前)插入：

```java
        // ─── Step3 行级折扣（V302；前端 buildDraftPayload 早已透传，后端此前丢弃）───
        public Integer annualVolume;
        public String discountSource;
        public BigDecimal discountBaseAmount;
        public BigDecimal discountRateApplied;
        public BigDecimal lineDiscountAmount;
        public BigDecimal lineUnitPrice;
        public BigDecimal lineFinalPrice;
        public BigDecimal lineTotalAmount;
        public String discountRuleCode;
```

- [ ] **Step 4: saveDraft 存 9 字段** — `QuotationService.java` saveDraft 循环里，`li.persist();`(约行 365) **之前**插入：

```java
                // Step3 行级折扣（V302）：原样落库前端送来的值；submit 时再权威重算覆盖。
                li.annualVolume = liDraft.annualVolume;
                li.discountSource = liDraft.discountSource;
                li.discountBaseAmount = liDraft.discountBaseAmount;
                li.discountRateApplied = liDraft.discountRateApplied;
                li.lineDiscountAmount = liDraft.lineDiscountAmount;
                li.lineUnitPrice = liDraft.lineUnitPrice;
                li.lineFinalPrice = liDraft.lineFinalPrice;
                li.lineTotalAmount = liDraft.lineTotalAmount;
                li.discountRuleCode = liDraft.discountRuleCode;
```

- [ ] **Step 5: LineItemDTO 声明 9 字段** — `QuotationDTO.java` `LineItemDTO` 字段区(约行 187，`finalDiscountRate` 等附近)插入：

```java
    // Step3 行级折扣（V302）
    public Integer annualVolume;
    public String discountSource;
    public java.math.BigDecimal discountBaseAmount;
    public java.math.BigDecimal discountRateApplied;
    public java.math.BigDecimal lineDiscountAmount;
    public java.math.BigDecimal lineUnitPrice;
    public java.math.BigDecimal lineFinalPrice;
    public java.math.BigDecimal lineTotalAmount;
    public String discountRuleCode;
```

- [ ] **Step 6: LineItemDTO.from() 映射 9 字段** — `QuotationDTO.java` `LineItemDTO.from(QuotationLineItem li)` 内 `return dto;` 前插入：

```java
        dto.annualVolume = li.annualVolume;
        dto.discountSource = li.discountSource;
        dto.discountBaseAmount = li.discountBaseAmount;
        dto.discountRateApplied = li.discountRateApplied;
        dto.lineDiscountAmount = li.lineDiscountAmount;
        dto.lineUnitPrice = li.lineUnitPrice;
        dto.lineFinalPrice = li.lineFinalPrice;
        dto.lineTotalAmount = li.lineTotalAmount;
        dto.discountRuleCode = li.discountRuleCode;
```

- [ ] **Step 7: 后端自检** — `touch` 任一 java 文件触发 Quarkus 重启，等 5-7 秒，然后：

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health   # 期望 200
PGPASSWORD=$PGPW psql "$PGCONN" -c "SELECT version, success FROM flyway_schema_history WHERE version='302'"  # success=t
PGPASSWORD=$PGPW psql "$PGCONN" -c "\d quotation_line_item" | grep -E "annual_volume|line_total_amount|discount_source"  # 三列在
```
(DB 连接参数沿用主工作区 dev 配置；不要手工 `psql -f` 跑迁移，让 Quarkus 自动 migrate。)

- [ ] **Step 8: 提交**

```bash
git add cpq-backend/src/main/resources/db/migration/V302__qli_add_step3_discount_columns.sql \
        cpq-backend/src/main/java/com/cpq/quotation/entity/QuotationLineItem.java \
        cpq-backend/src/main/java/com/cpq/quotation/dto/SaveDraftRequest.java \
        cpq-backend/src/main/java/com/cpq/quotation/dto/QuotationDTO.java \
        cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java
git commit -m "feat(step3): persist 9 line-discount columns (V302 + entity/DTO/saveDraft round-trip)"
```

---

## Task 2: 后端折后小计重算（ComponentDataEffectiveRows 折扣重载）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/card/ComponentDataEffectiveRows.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/card/ComponentDataEffectiveRowsDiscountTest.java`

**说明：** Pass1 装配 `componentSubtotals` 时，对被折页签(按 `meta.code` 或 `meta.name` 匹配)的列和乘 `discountScale`，再跑同一 SUBTOTAL 公式 → 折后小计。key 结构无关，最稳。

- [ ] **Step 1: 写失败单测** — 创建 `ComponentDataEffectiveRowsDiscountTest.java`：

```java
package com.cpq.quotation.service.card;

import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.service.FormulaCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComponentDataEffectiveRowsDiscountTest {

    private final FormulaCalculator fc = new FormulaCalculator();
    private final ObjectMapper M = new ObjectMapper();

    // 产品小计公式: [组装加工费.费用] + [其他费用.费用]，两页签费用列和 8 与 2 → S0=10
    private Map<UUID, ComponentDataEffectiveRows.Meta> metas(UUID asm, UUID oth, UUID sub) throws Exception {
        var formulas = M.readTree("[{\"expression\":[" +
            "{\"type\":\"component_subtotal\",\"value\":\"费用\",\"component_code\":\"ASM\",\"tab_name\":\"组装加工费\"}," +
            "{\"type\":\"operator\",\"value\":\"+\"}," +
            "{\"type\":\"component_subtotal\",\"value\":\"费用\",\"component_code\":\"OTH\",\"tab_name\":\"其他费用\"}]}]");
        Map<UUID, ComponentDataEffectiveRows.Meta> m = new HashMap<>();
        m.put(asm, new ComponentDataEffectiveRows.Meta("ASM", "组装加工费", "NORMAL", null));
        m.put(oth, new ComponentDataEffectiveRows.Meta("OTH", "其他费用", "NORMAL", null));
        m.put(sub, new ComponentDataEffectiveRows.Meta("SUB", "报价小计", "SUBTOTAL", formulas));
        return m;
    }

    private QuotationLineComponentData cd(UUID id, int sort, String rowData) {
        QuotationLineComponentData c = new QuotationLineComponentData();
        c.componentId = id; c.sortOrder = sort; c.rowData = rowData; c.subtotal = BigDecimal.ZERO;
        return c;
    }

    @Test
    void discountOnAsmComponent_recomputesSubtotal() throws Exception {
        UUID asm = UUID.randomUUID(), oth = UUID.randomUUID(), sub = UUID.randomUUID();
        var list = List.of(
            cd(asm, 0, "[{\"费用\":8}]"),
            cd(oth, 1, "[{\"费用\":2}]"),
            cd(sub, 2, "[]"));
        // 折 ASM 20% → 8*0.8 + 2 = 8.4
        BigDecimal s1 = ComponentDataEffectiveRows.subtotalWithDiscount(
            list, metas(asm, oth, sub), sub, fc, "ASM", 0.8);
        assertEquals(0, new BigDecimal("8.4000").compareTo(s1));
        // 无折扣 → 10
        BigDecimal s0 = ComponentDataEffectiveRows.subtotalWithDiscount(
            list, metas(asm, oth, sub), sub, fc, null, 1.0);
        assertEquals(0, new BigDecimal("10.0000").compareTo(s0));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=ComponentDataEffectiveRowsDiscountTest`
Expected: 编译失败 `cannot find symbol: method subtotalWithDiscount` 或断言失败。

- [ ] **Step 3: 实现折扣重载** — `ComponentDataEffectiveRows.java`：
  1. 给 `compute` 主体加可选缩放参数（私有重载），在 Pass1 put 前缩放；
  2. 暴露 `subtotalWithDiscount(...)` 取出 SUBTOTAL 页签折后小计。

把 Pass1 的两行 put(行 108-109)所在循环，改为调用一个带缩放的内部方法。最小侵入实现：新增重载 + 私有缩放参数。在类内 `compute(cdList, metaById, extras, fc)`(行 81) **之后**插入：

```java
    /**
     * 折扣重算：对 discountCode 命中的页签（meta.code 或 meta.name 相等）列和乘 discountScale，
     * 再跑 SUBTOTAL 公式，返回该 SUBTOTAL 页签的折后小计。
     * discountCode=null 或 scale=1.0 → 等价无折扣（返原产品小计 S0）。
     *
     * @param subtotalComponentId 目标 SUBTOTAL 组件 id（取其 TabRows.subtotal）
     */
    public static BigDecimal subtotalWithDiscount(
            List<QuotationLineComponentData> cdList,
            Map<UUID, Meta> metaById,
            UUID subtotalComponentId,
            FormulaCalculator fc,
            String discountCode,
            double discountScale) {
        Map<String, CardEffectiveRows.TabRows> tabs =
            computeScaled(cdList, metaById, Map.of(), fc, discountCode, discountScale);
        CardEffectiveRows.TabRows tr = subtotalComponentId != null
            ? tabs.get(subtotalComponentId.toString()) : null;
        return tr != null && tr.subtotal() != null
            ? tr.subtotal().setScale(4, java.math.RoundingMode.HALF_UP)
            : java.math.BigDecimal.ZERO.setScale(4);
    }
```

然后把现有 `compute(cdList, metaById, extras, fc)`(行 81 的 4 参公开方法)的方法体整体抽到新私有方法 `computeScaled(... , String discountCode, double discountScale)`，并让 4 参公开方法委托 `computeScaled(cdList, metaById, extras, fc, null, 1.0)`。在 `computeScaled` 的 Pass1 缩放：把行 107-110 改为：

```java
                for (Map.Entry<String, BigDecimal> e : colSums.entrySet()) {
                    double v = e.getValue().doubleValue();
                    boolean hit = discountCode != null
                        && (discountCode.equals(meta.code) || discountCode.equals(meta.name));
                    if (hit) v = v * discountScale;
                    if (meta.code != null) componentSubtotals.put(meta.code + SUBTOTAL_KEY_SEP + e.getKey(), v);
                    if (meta.name != null) componentSubtotals.put(meta.name + SUBTOTAL_KEY_SEP + e.getKey(), v);
                }
```

(`CardEffectiveRows.TabRows` 的小计访问器若不是 `subtotal()` 记录式，按其真实 getter 调整——实现前先 `grep -n "subtotal" CardEffectiveRows.java` 确认。)

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -q test -Dtest=ComponentDataEffectiveRowsDiscountTest`
Expected: PASS（S1=8.4，S0=10）。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/card/ComponentDataEffectiveRows.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/card/ComponentDataEffectiveRowsDiscountTest.java
git commit -m "feat(step3): ComponentDataEffectiveRows discount overload (scale tab col-sums then re-eval)"
```

---

## Task 3: 后端单行折扣服务 + submit 权威重算 + Σ总额

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/quotation/service/LineDiscountService.java`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`（submit）

**说明：** `LineDiscountService.recompute(li)` 加载该行 component_data + 组件 meta，算 `S0`(无折扣) 与 `S1`(按 discountSource/discountRateApplied 折扣)，写 9 个金额字段；submit 遍历所有行调它并 `q.totalAmount = Σ lineTotalAmount`。

- [ ] **Step 1: 创建 LineDiscountService**

```java
package com.cpq.quotation.service;

import com.cpq.component.entity.Component;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.card.ComponentDataEffectiveRows;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Step3 行级折扣权威重算：以 row_data 为真相源，按 discountSource/discountRateApplied 算折后小计与各金额。 */
@ApplicationScoped
public class LineDiscountService {

    private static final String SUBTOTAL_SOURCE = "SUBTOTAL";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject FormulaCalculator formulaCalculator;

    /** 重算单行并就地写入 9 字段（不 persist，由调用方事务统一提交）。 */
    public void recompute(QuotationLineItem li) {
        List<QuotationLineComponentData> cdList =
            QuotationLineComponentData.list("lineItemId = ?1", li.id);
        Map<UUID, ComponentDataEffectiveRows.Meta> metaById = loadMetas(cdList);
        UUID subtotalCid = findSubtotalComponentId(metaById);

        // 原小计 S0
        BigDecimal s0 = subtotalCid != null
            ? ComponentDataEffectiveRows.subtotalWithDiscount(cdList, metaById, subtotalCid, formulaCalculator, null, 1.0)
            : (li.subtotal != null ? li.subtotal : BigDecimal.ZERO);

        BigDecimal rate = li.discountRateApplied != null ? li.discountRateApplied : BigDecimal.ZERO;
        double scale = 1.0 - rate.doubleValue() / 100.0;
        String source = li.discountSource;

        // 折后小计 S1
        BigDecimal s1;
        BigDecimal base;
        if (source == null || source.isBlank() || SUBTOTAL_SOURCE.equals(source)) {
            s1 = s0.multiply(BigDecimal.valueOf(scale));
            base = s0;
        } else if (subtotalCid != null) {
            s1 = ComponentDataEffectiveRows.subtotalWithDiscount(
                cdList, metaById, subtotalCid, formulaCalculator, source, scale);
            base = componentSubtotalOf(cdList, metaById, source); // 被折页签小计（展示用基数）
        } else {
            s1 = s0; base = BigDecimal.ZERO;
        }
        s1 = s1.setScale(4, RoundingMode.HALF_UP);

        int qty = li.annualVolume != null ? li.annualVolume : 0;
        BigDecimal q = BigDecimal.valueOf(qty);
        li.lineUnitPrice = s0.setScale(4, RoundingMode.HALF_UP);
        li.discountBaseAmount = base.setScale(4, RoundingMode.HALF_UP);
        li.lineFinalPrice = s1;
        li.lineDiscountAmount = s0.subtract(s1).multiply(q).setScale(4, RoundingMode.HALF_UP);
        li.lineTotalAmount = s1.multiply(q).setScale(4, RoundingMode.HALF_UP);
    }

    /** 取被折页签的小计列和（展示用基数）；用 scale=1.0 拿无折扣值，再按 code 找其页签小计。 */
    private BigDecimal componentSubtotalOf(List<QuotationLineComponentData> cdList,
                                           Map<UUID, ComponentDataEffectiveRows.Meta> metaById, String code) {
        for (Map.Entry<UUID, ComponentDataEffectiveRows.Meta> e : metaById.entrySet()) {
            var m = e.getValue();
            if (code.equals(m.code) || code.equals(m.name)) {
                for (QuotationLineComponentData cd : cdList) {
                    if (e.getKey().equals(cd.componentId)) {
                        var sums = ComponentDataEffectiveRows.columnSums(parse(cd.rowData));
                        BigDecimal s = BigDecimal.ZERO;
                        for (BigDecimal v : sums.values()) s = s.add(v);
                        return s;
                    }
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private List<Map<String, Object>> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private UUID findSubtotalComponentId(Map<UUID, ComponentDataEffectiveRows.Meta> metaById) {
        for (Map.Entry<UUID, ComponentDataEffectiveRows.Meta> e : metaById.entrySet()) {
            if ("SUBTOTAL".equals(e.getValue().componentType)) return e.getKey();
        }
        return null;
    }

    private Map<UUID, ComponentDataEffectiveRows.Meta> loadMetas(List<QuotationLineComponentData> cdList) {
        Map<UUID, ComponentDataEffectiveRows.Meta> out = new HashMap<>();
        for (QuotationLineComponentData cd : cdList) {
            if (cd.componentId == null || out.containsKey(cd.componentId)) continue;
            Component c = Component.findById(cd.componentId);
            if (c == null) continue;
            com.fasterxml.jackson.databind.JsonNode formulas = null;
            if (c.formulas != null && !c.formulas.isBlank()) {
                try { formulas = MAPPER.readTree(c.formulas); } catch (Exception ignore) {}
            }
            out.put(cd.componentId,
                new ComponentDataEffectiveRows.Meta(c.code, c.name, c.componentType, formulas));
        }
        return out;
    }
}
```

> 实现前先 `grep -n "public " cpq-backend/.../component/entity/Component.java` 与 `QuotationLineComponentData.java` 确认字段名(`code/name/componentType/formulas`、`lineItemId/componentId/rowData/sortOrder`)，并把 `columnSums` 在 `ComponentDataEffectiveRows` 改为 `public static`（当前是包级 `static`）。若 `Component.formulas` 列名不同则相应调整。

- [ ] **Step 2: 把 `columnSums` 提升为 public static** — `ComponentDataEffectiveRows.java` 行 177 `static Map<String, BigDecimal> columnSums(` → `public static Map<String, BigDecimal> columnSums(`。

- [ ] **Step 3: submit 权威重算 + Σ总额** — `QuotationService.java` 注入服务：类字段区加 `@Inject LineDiscountService lineDiscountService;`。在 `submit(UUID id, UUID userId)` 内、`q.status = "SUBMITTED";`(约行 781) **之前**插入：

```java
        // Step3：提交时权威重算每行折后小计（防前端篡改），整单总额 = Σ行合计。
        BigDecimal lineSum = BigDecimal.ZERO;
        for (QuotationLineItem li : lineItems) {
            if ("PART".equals(li.compositeType)) continue;   // 子件不单独计入整单
            lineDiscountService.recompute(li);
            if (li.lineTotalAmount != null) lineSum = lineSum.add(li.lineTotalAmount);
        }
        q.totalAmount = lineSum.setScale(4, java.math.RoundingMode.HALF_UP);
```

(`lineItems` 变量在 submit 行 694 已加载；若变量名不同按实际调整。)

- [ ] **Step 4: 后端自检** — `touch` java 重启；`curl /q/health` 200。手动冒烟：保存一张含折扣的草稿 → 提交 → `psql -c "SELECT line_total_amount, line_final_price, total_amount ... "` 确认行字段与整单总额写入。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/LineDiscountService.java \
        cpq-backend/src/main/java/com/cpq/quotation/service/card/ComponentDataEffectiveRows.java \
        cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java
git commit -m "feat(step3): authoritative line-discount recompute at submit + total = sum(line totals)"
```

---

## Task 4: 前端产品小计拆分 + lineDiscount 工具 + 单测

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`
- Create: `cpq-frontend/src/pages/quotation/lineDiscount.ts`
- Test: `cpq-frontend/src/pages/quotation/lineDiscount.test.ts`

- [ ] **Step 1: 拆 computeProductSubtotal** — `QuotationStep2.tsx`：把 `computeProductSubtotal`(行 1182) 内"构建 componentSubtotals 的 PASS1 循环"(行 1198-1221)抽成导出函数 `getComponentSubtotals`，把"SUBTOTAL 公式求值 + fallback"(行 1224-1270)抽成导出函数 `evalProductSubtotalFromSubtotals`，`computeProductSubtotal` 改为组合两者（保持原签名/行为不变）。在文件末尾 `export { computeProductSubtotal, computeAllFormulas };`(行 3365) 追加导出 `getComponentSubtotals, evalProductSubtotalFromSubtotals`。

```ts
// 抽出：构建 componentSubtotals（PASS1）——原 computeProductSubtotal 行 1198-1221 逻辑
export function getComponentSubtotals(
  item: LineItem,
  driverExpansions?: import('./useDriverExpansions').DriverExpansionMap,
  customerId?: string,
): Record<string, number> {
  const componentSubtotals: Record<string, number> = {};
  if (!item.componentData) return componentSubtotals;
  const partNo = item.productPartNo;
  const lookupExpansion = (comp: ComponentDataItem) => {
    if (!driverExpansions || !partNo || !comp.componentId) return undefined;
    const lineItemId = (item as any).id || (item as any).tempId || '';
    const k = driverExpansionKey(lineItemId, partNo, comp.componentId, customerId, comp.dataDriverPath, fieldsOverrideHash(comp.fields as any[]));
    return driverExpansions[k];
  };
  for (const comp of item.componentData) {
    if (!comp?.fields || comp.componentType !== 'NORMAL') continue;
    const subtotal = computeTabSubtotal(comp, componentSubtotals, undefined, undefined, partNo, lookupExpansion(comp));
    if (comp.componentId) componentSubtotals[comp.componentId] = subtotal;
    if (comp.componentCode) componentSubtotals[comp.componentCode] = subtotal;
    componentSubtotals[comp.tabName] = subtotal;
  }
  return componentSubtotals;
}

// 抽出：用 componentSubtotals 求产品小计（SUBTOTAL 公式 + fallback）——原行 1224-1270 逻辑
export function evalProductSubtotalFromSubtotals(
  item: LineItem,
  componentSubtotals: Record<string, number>,
): number {
  const productAttrs: Record<string, number> = {};
  for (const attr of item.productAttributes || []) {
    if (attr.field_type === 'NUMBER') {
      const val = parseFloat(item.productAttributeValues?.[attr.name]);
      if (!isNaN(val)) productAttrs[attr.name] = val;
    }
  }
  const subtotalComp = item.componentData?.find(c => c.componentType === 'SUBTOTAL');
  if (subtotalComp?.formulas?.[0]?.expression?.length) {
    try { return evaluateExpression(subtotalComp.formulas[0].expression, {}, componentSubtotals, productAttrs); }
    catch { return 0; }
  }
  if (item.subtotalFormula && item.subtotalFormula.length > 0) {
    try { return evaluateExpression(item.subtotalFormula, {}, componentSubtotals, productAttrs); }
    catch { return 0; }
  }
  let fallbackSum = 0;
  for (const c of item.componentData || []) {
    if (!c?.fields || c.componentType !== 'NORMAL') continue;
    if (!c.fields.some((ff: any) => ff.is_subtotal)) continue;
    const key = c.componentId ?? c.componentCode ?? c.tabName;
    fallbackSum += componentSubtotals[key] ?? 0;
  }
  return fallbackSum;
}
```
`computeProductSubtotal` 内 `precomputedSubtotals` 分支保持不变，无 precomputed 时改为 `const componentSubtotals = getComponentSubtotals(item, driverExpansions, customerId);` 后 `return evalProductSubtotalFromSubtotals(item, componentSubtotals);`。

- [ ] **Step 2: 写 lineDiscount.ts 失败单测先行** — 创建 `lineDiscount.test.ts`（Vitest）：

```ts
import { describe, it, expect } from 'vitest';
import { extractDiscountSources, computeLineDiscount } from './lineDiscount';
import type { LineItem } from './QuotationStep2';

// 两个 NORMAL 页签(组装加工费小计=8, 其他费用小计=2) + SUBTOTAL 公式 = 两者相加
function makeItem(): LineItem {
  const expr = [
    { type: 'component_subtotal', value: 'ASM', component_code: 'ASM', tab_name: '组装加工费', label: '组装加工费.小计' },
    { type: 'operator', value: '+' },
    { type: 'component_subtotal', value: 'OTH', component_code: 'OTH', tab_name: '其他费用', label: '其他费用.小计' },
  ];
  return {
    productId: 'p', productName: 'P', productPartNo: 'PN', templateId: 't', templateName: 'T',
    productAttributeValues: {}, subtotal: 10,
    componentData: [
      { componentId: 'a', componentCode: 'ASM', componentType: 'NORMAL', tabName: '组装加工费',
        fields: [{ key: 'f', name: '费用', field_type: 'INPUT_NUMBER', is_subtotal: true } as any],
        formulas: [], rows: [{ 费用: 8 }], subtotal: 8 },
      { componentId: 'b', componentCode: 'OTH', componentType: 'NORMAL', tabName: '其他费用',
        fields: [{ key: 'f', name: '费用', field_type: 'INPUT_NUMBER', is_subtotal: true } as any],
        formulas: [], rows: [{ 费用: 2 }], subtotal: 2 },
      { componentId: 'c', componentCode: 'SUB', componentType: 'SUBTOTAL', tabName: '报价小计',
        fields: [], formulas: [{ key: 'k', name: 's', expression: expr as any }], rows: [], subtotal: 10 },
    ] as any,
  } as LineItem;
}

describe('extractDiscountSources', () => {
  it('总金额置顶默认 + 公式里每个页签小计', () => {
    const opts = extractDiscountSources(makeItem());
    expect(opts[0]).toEqual({ value: 'SUBTOTAL', label: '总金额' });
    expect(opts.map(o => o.value)).toContain('ASM');
    expect(opts.map(o => o.value)).toContain('OTH');
    expect(opts.find(o => o.value === 'ASM')?.label).toContain('组装加工费');
  });
});

describe('computeLineDiscount', () => {
  it('SUBTOTAL 整单价打折', () => {
    const r = computeLineDiscount(makeItem(), undefined, undefined, 'SUBTOTAL', 20, 100);
    expect(r.original).toBeCloseTo(10);
    expect(r.discounted).toBeCloseTo(8);          // 10*0.8
    expect(r.lineDiscountAmount).toBeCloseTo(200); // (10-8)*100
    expect(r.lineTotalAmount).toBeCloseTo(800);    // 8*100
  });
  it('按页签小计代回公式重算', () => {
    const r = computeLineDiscount(makeItem(), undefined, undefined, 'ASM', 20, 100);
    expect(r.discounted).toBeCloseTo(8.4);          // 8*0.8 + 2
    expect(r.lineDiscountAmount).toBeCloseTo(160);  // (10-8.4)*100
    expect(r.lineTotalAmount).toBeCloseTo(840);     // 8.4*100
    expect(r.discountBaseAmount).toBeCloseTo(8);
  });
  it('率=0 不打折', () => {
    const r = computeLineDiscount(makeItem(), undefined, undefined, 'ASM', 0, 100);
    expect(r.discounted).toBeCloseTo(10);
    expect(r.lineDiscountAmount).toBeCloseTo(0);
  });
});
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/lineDiscount.test.ts`
Expected: FAIL（模块不存在）。

- [ ] **Step 4: 实现 lineDiscount.ts**

```ts
import type { LineItem } from './QuotationStep2';
import { getComponentSubtotals, evalProductSubtotalFromSubtotals } from './QuotationStep2';
import type { DriverExpansionMap } from './useDriverExpansions';

export interface DiscountSourceOption { value: string; label: string; }

/** 折扣来源选项 = 总金额(默认置顶) + 产品小计公式里每个去重的 component_subtotal 页签。 */
export function extractDiscountSources(item: LineItem): DiscountSourceOption[] {
  const opts: DiscountSourceOption[] = [{ value: 'SUBTOTAL', label: '总金额' }];
  const subtotalComp = item.componentData?.find(c => c.componentType === 'SUBTOTAL');
  const expr: any[] = subtotalComp?.formulas?.[0]?.expression ?? item.subtotalFormula ?? [];
  const seen = new Set<string>();
  for (const tok of expr) {
    if (tok?.type !== 'component_subtotal') continue;
    const code = tok.component_code ?? tok.value;
    if (!code || seen.has(code)) continue;
    seen.add(code);
    opts.push({ value: code, label: `${tok.tab_name ?? code}.小计` });
  }
  return opts;
}

export interface LineDiscountResult {
  original: number;       // S0
  discounted: number;     // S1
  discountBaseAmount: number;
  lineDiscountAmount: number;
  lineFinalPrice: number;
  lineTotalAmount: number;
}

/** 按所选来源/折扣率/年用量算折后小计与各金额。source='SUBTOTAL' → 整单价打折；否则代回公式重算。 */
export function computeLineDiscount(
  item: LineItem,
  driverExpansions: DriverExpansionMap | undefined,
  customerId: string | undefined,
  source: string,
  ratePct: number,
  annualVolume: number,
): LineDiscountResult {
  const subs = getComponentSubtotals(item, driverExpansions, customerId);
  const s0 = evalProductSubtotalFromSubtotals(item, subs);
  const r = Math.max(0, Math.min(100, ratePct || 0));
  const scale = 1 - r / 100;
  const qty = annualVolume || 0;

  let s1: number, base: number;
  if (!source || source === 'SUBTOTAL') {
    s1 = s0 * scale; base = s0;
  } else {
    base = subs[source] ?? 0;
    const scaled = { ...subs };
    // 缩放被折页签：componentSubtotals 同值多键(componentId/code/tabName)，按 code 命中后同步缩放其 tabName 键。
    if (scaled[source] !== undefined) scaled[source] = scaled[source] * scale;
    s1 = evalProductSubtotalFromSubtotals(item, scaled);
  }
  const round4 = (n: number) => Math.round(n * 10000) / 10000;
  return {
    original: round4(s0),
    discounted: round4(s1),
    discountBaseAmount: round4(base),
    lineDiscountAmount: round4((s0 - s1) * qty),
    lineFinalPrice: round4(s1),
    lineTotalAmount: round4(s1 * qty),
  };
}
```

> 注：`computeLineDiscount` 缩放 `scaled[source]`（source=component_code），其值即该页签小计。若公式 token 经 `component_code` 解析命中 code 键即可；如发现 `evalProductSubtotalFromSubtotals` 实际按 tabName 解析，则同时缩放 `scaled[tab_name]`——实现时跑单测「按页签小计代回公式重算」用例验证 8.4 即可确认命中。

- [ ] **Step 5: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/lineDiscount.test.ts`
Expected: PASS（3 用例全绿；尤其 8.4 / 840 / 160）。若「按页签」用例失败(s1=10 未缩放)，按 Step4 注释补缩放 `scaled[tok.tab_name]`。

- [ ] **Step 6: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx \
        cpq-frontend/src/pages/quotation/lineDiscount.ts \
        cpq-frontend/src/pages/quotation/lineDiscount.test.ts
git commit -m "feat(step3): split product-subtotal helpers + lineDiscount util (source extract + recompute)"
```

---

## Task 5: 前端 Step3 重做 + 接线

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep3.tsx`
- Modify: `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`

- [ ] **Step 1: Wizard 给 Step3 传 driverExpansions + customerId** — `QuotationWizard.tsx` `<QuotationStep3 ... />`(约行 1361) 增加 props：

```tsx
    <QuotationStep3
      quotationId={quotationId || undefined}
      lineItems={lineItems}
      baseCurrency={quotation?.baseCurrency || 'CNY'}
      driverExpansions={driverExpansions}
      customerId={customerIdValue}
      onUpdate={(updater) => setLineItems(prev => updater(prev))}
    />
```

- [ ] **Step 2: 重做 QuotationStep3.tsx** — 替换关键逻辑：
  - `Props` 增加 `driverExpansions?: DriverExpansionMap; customerId?: string;`。
  - 删除 `DISCOUNT_SOURCE_OPTIONS` 常量、`callBackendCalculate` 函数、`api` 导入、「调用后端阶梯引擎计算」按钮及其说明。
  - `recomputeRow` 改用 `computeLineDiscount`，按所选 `discountSource`/`discountRateApplied`/`annualVolume` 算字段。
  - 折扣来源列改为 `extractDiscountSources(li)` 动态选项，默认 `SUBTOTAL`。
  - 原小计列展示 `li.lineUnitPrice ?? computeLineDiscount(...).original`。

替换 `recomputeRow`(行 54-66) 为：

```tsx
  const recomputeRow = (li: LineItem): Partial<LineItem> => {
    const source = li.discountSource ?? 'SUBTOTAL';
    const rate = li.discountRateApplied ?? 0;
    const qty = li.annualVolume ?? 0;
    const d = computeLineDiscount(li, driverExpansions, customerId, source, rate, qty);
    return {
      lineUnitPrice: d.original,
      discountBaseAmount: d.discountBaseAmount,
      lineDiscountAmount: d.lineDiscountAmount,
      lineFinalPrice: d.lineFinalPrice,
      lineTotalAmount: d.lineTotalAmount,
    };
  };
```

折扣来源列(行 150-163) 改为：

```tsx
    {
      title: '折扣来源',
      key: 'discountSource',
      width: 200,
      render: (_v, li, idx) => (
        <Select
          value={li.discountSource ?? 'SUBTOTAL'}
          onChange={v => patchRow(idx, { discountSource: v })}
          options={extractDiscountSources(li)}
          style={{ width: '100%' }}
          size="small"
        />
      ),
    },
```

原小计列(行 130-135) 改为：

```tsx
    {
      title: '原小计 (单价)',
      key: 'unitPrice',
      width: 130,
      align: 'right',
      render: (_v, li) => formatCurrency(li.lineUnitPrice ?? li.subtotal ?? 0, baseCurrency),
    },
```

顶部 import 增加 `import { computeLineDiscount, extractDiscountSources } from './lineDiscount';`，删除 `import api from '../../services/api';` 与 `CalculatorOutlined`、`Button` 中仅引擎按钮用到的部分(按编译报错清理未用 import)。Alert 说明文案改为：「每行选折扣来源 + 填折扣率 → 自动按产品小计公式重算折后小计 / 折扣金额 / 行合计。折扣来源默认『总金额』，也可选某页签小计单独打折。」

`grandDiscount`(行 223-226) 改为直接累加 `li.lineDiscountAmount`（已含 ×年用量，勿再乘）：

```tsx
  const grandDiscount = visibleItems.reduce((sum, li) => sum + (li.lineDiscountAmount ?? 0), 0);
```

- [ ] **Step 3: 前端编译自检**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json   # 0 错误
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep3.tsx  # 200
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationWizard.tsx  # 200
```
(dev server 服务主工作区代码；worktree 内 tsc 用主工作区 node_modules——若 worktree 无 node_modules，在主工作区 `cpq-frontend` 跑 tsc，或合并后跑。)

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep3.tsx cpq-frontend/src/pages/quotation/QuotationWizard.tsx
git commit -m "feat(step3): rework UI — dynamic discount source, manual rate, formula-based recompute"
```

---

## Task 6: 导出体现折扣（Excel + HTML/Qute）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationExportService.java`
- Modify: Qute 模板（实现前 `grep -rl "lineItems\|totalAmount" cpq-backend/src/main/resources/templates` 定位报价单 HTML/PDF 模板）

- [ ] **Step 1: Excel 行金额改用折后字段** — `QuotationExportService.java` `createSummarySheet`(行 87-167)：
  - 表头(约行 132-135)：`showDiscount` 为真时，在"折扣率"后增列「单价」「折扣金额」「折后单价」「行合计」。
  - 行数据(约行 150-158)：`showDiscount` 为真时填 `li.lineUnitPrice` / `li.lineDiscountAmount` / `li.lineFinalPrice` / `li.lineTotalAmount`（null 安全）。
  - `grandTotal` 累加由 `li.subtotal` 改为 `li.lineTotalAmount != null ? li.lineTotalAmount : li.subtotal`。

```java
                Cell totalCell = dataRow.createCell(col++);
                totalCell.setCellStyle(amountStyle);
                BigDecimal lineTotal = li.lineTotalAmount != null ? li.lineTotalAmount
                                      : (li.subtotal != null ? li.subtotal : BigDecimal.ZERO);
                totalCell.setCellValue(lineTotal.doubleValue());
                grandTotal = grandTotal.add(lineTotal);
```

- [ ] **Step 2: HTML/Qute 模板加折扣列** — 在 `buildLineItemsData(q)`(约行 326-344) 返回的每行 Map 里补 `lineUnitPrice/lineDiscountAmount/lineFinalPrice/lineTotalAmount`(2 位小数字符串)，并在对应 Qute 模板的 lineItems 表格中加列渲染；整单展示 `totalAmount`(已存在)。

- [ ] **Step 3: 导出自检** — 提交一张含折扣报价单后：

```bash
curl -s "http://localhost:8081/api/cpq/quotations/<id>/export/html?showDiscount=true" | grep -o "行合计\|折后单价"   # 列出现
curl -s -o /tmp/q.xlsx -X POST "http://localhost:8081/api/cpq/quotations/<id>/export/excel" -H 'Content-Type: application/json' -d '{"showDiscount":true}' -w "%{http_code}\n"  # 200
```

- [ ] **Step 4: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/QuotationExportService.java cpq-backend/src/main/resources/templates
git commit -m "feat(step3): exports (Excel + HTML) reflect line discount / final price / line total"
```

---

## Task 7: E2E + 全链路验收

- [ ] **Step 1: 合并到 master 让 dev server 生效**（按 CLAUDE.md：dev server 服务已合并代码；E2E 在合并后跑）。先确认全部 task 提交完成。

- [ ] **Step 2: 跑 Playwright E2E**

```bash
cd cpq-frontend
rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: `1 passed`；`'加载中' final count = 0`；8 Tab `'加载中'=0`。

- [ ] **Step 3: 手动全链路冒烟**（用测试数据：苏州西门子 + 报价模板0608 + 10110002）：
  1. Step3 选某页签小计 + 填折扣率 + 年用量 → 折后单价/折扣金额/行合计正确；
  2. 保存草稿 → 刷新/重开 → 9 字段恢复（验证 Task1 回读）；
  3. 提交 → `psql` 查 `total_amount = Σ line_total_amount`（验证 Task3）；
  4. 导出 Excel/HTML → 折扣列正确（验证 Task6）。

- [ ] **Step 4: RECORD.md 追加开发记录**（格式：`[2026-06-21] 报价单Step3优惠策略 - 行级折扣全链路重做 | 涉及文件 | 关键决策`）。

---

## 自检小结（PR 必附）
- TS 0 错误；Step3.tsx / Wizard.tsx → Vite 200；
- 后端 `/q/health` 200；V302 `success=t`；3 列在表；
- 后端单测 `ComponentDataEffectiveRowsDiscountTest` 通过；前端 `lineDiscount.test.ts` 通过；
- E2E `1 passed` + `'加载中' final count = 0`；
- 提交→`total_amount = Σ line_total_amount` 截图；导出折扣列截图。
