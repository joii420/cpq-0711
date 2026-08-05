package com.cpq.priceadjust.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0729 验收 #6 · 裁决 39（§11.5.5 补丁 1）回归测试：
 * <b>相关元素价与「该料号版本指针当前指向的那一版」逐个相同的料号，不进待办池</b>
 * （版本照常生成、版本明细照常有记录、指针照常推进）。
 *
 * <p>🔒 四个必测方向（缺一个都会让实现走偏）：
 * <ol>
 *   <li><b>正向</b>：0 变动 → 不进池 + 指针推进到新版；</li>
 *   <li><b>反向对照</b>：同一对版本、价格确有变动 → <b>照常进池</b>
 *       （防止把"0 变动不进池"错写成"该版所有料号都不进池"）；</li>
 *   <li><b>相关性精度</b>：变动的元素该料号根本没用到 → 仍然不进池（"相关元素"不是"版本全部元素"）；</li>
 *   <li><b>无指针边界</b>：{@code previousVersionId == null}（料号从未有过指针）→
 *       没有可比基准，<b>必须进池</b>，不得把"没得比"当成"无变化"。</li>
 * </ol>
 *
 * <p>自建 customer + component + quotation + 冻结结构 + line_item + component_data +
 * 两个 element_price_version 全套数据（客户号带 {@code ZZ6-D39-} 前缀，测后 {@code @AfterEach} 清理），
 * 不依赖也不污染 {@code CUST-0001} / {@code CUST-0729-QA} 等他人测试域。
 *
 * <p>⚠️ fixture 必须**提交**后才能调 {@code processMaterial} —— 后者是
 * {@code @Transactional(REQUIRES_NEW)}，会挂起当前事务另开一个，看不见未提交数据。故全部
 * 建/删/断言读都走 {@link QuarkusTransaction#requiringNew()}，测试方法本身不加 {@code @Transactional}。
 */
@QuarkusTest
class PriceAdjustBudgetServiceDecision39Test {

    /** 该料号的行里只有 Ag —— 「相关元素」= {Ag}，与版本明细里是否还有别的元素无关。 */
    private static final String MATERIAL_NO = "ZZ6-D39-MAT";

    @Inject
    PriceAdjustBudgetService budgetService;
    @Inject
    EntityManager em;

    private String customerNo;
    private UUID customerId, componentId, quotationId, lineItemId;

    @BeforeEach
    void initIds() {
        customerNo = "ZZ6-D39-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            exec("DELETE FROM material_price_review_column WHERE review_id IN "
                + "(SELECT id FROM material_price_review WHERE customer_no=:c)", "c", customerNo);
            exec("DELETE FROM material_price_review WHERE customer_no=:c", "c", customerNo);
            exec("DELETE FROM material_price_version_ref WHERE customer_no=:c", "c", customerNo);
            exec("DELETE FROM element_price_version_item WHERE version_id IN "
                + "(SELECT id FROM element_price_version WHERE customer_no=:c)", "c", customerNo);
            exec("DELETE FROM element_price_version WHERE customer_no=:c", "c", customerNo);
            if (lineItemId != null) {
                exec("DELETE FROM quotation_line_component_data WHERE line_item_id=:id", "id", lineItemId);
                exec("DELETE FROM quotation_line_item WHERE id=:id", "id", lineItemId);
            }
            if (quotationId != null) {
                exec("DELETE FROM quotation_view_structure WHERE quotation_id=:id", "id", quotationId);
                exec("DELETE FROM quotation WHERE id=:id", "id", quotationId);
            }
            if (componentId != null) exec("DELETE FROM component WHERE id=:id", "id", componentId);
            if (customerId != null) exec("DELETE FROM customer WHERE id=:id", "id", customerId);
        });
    }

    // =========================================================================
    // ① 正向：0 变动 → 不进池，但指针照常推进（验收 #6 核心断言）
    // =========================================================================

    @Test
    void zeroChange_doesNotEnterPool_butPointerStillAdvances() {
        setUpBaseFixture();
        UUID vPrev = createVersion("ZZ6V-PREV", "SUPERSEDED", prices("Ag", "5450.000000"));
        UUID vNew = createVersion("ZZ6V-NEW", "PENDING", prices("Ag", "5450.000000"));
        setPointer(vPrev);

        boolean entered = budgetService.processMaterial(vNew, customerNo, BigDecimal.ZERO, MATERIAL_NO);

        assertFalse(entered, "相关元素价逐个相同 → 不应进待办池（裁决 39）");
        assertEquals(0L, reviewCount(vNew),
            "🔒 验收 #6 核心断言：0 变动料号在 V-new 的 material_price_review 里必须一条都没有");
        assertEquals(vNew, pointerVersionId(),
            "🔒 不进池 ≠ 不推进指针 —— 指针必须照常推进到本期版本");
        assertEquals(1L, versionCount(vNew), "🔒 版本本身照常生成（『仍生成版本，但 0 变动料号不进池』）");
        assertEquals(0, comparePrices(vNew, "Ag", "5450.000000"),
            "🔒 V-new 的明细里 Ag 仍有记录且与上一版同价（版本明细里仍有记录可查）");
    }

    // =========================================================================
    // ② 反向对照：价格确有变动 → 照常进池
    //    （防止实现走极端，把"0 变动不进池"错写成"该版所有料号都不进池"）
    // =========================================================================

    @Test
    void priceChanged_entersPoolAsUsual() {
        setUpBaseFixture();
        UUID vPrev = createVersion("ZZ6V-PREV", "SUPERSEDED", prices("Ag", "5450.000000"));
        UUID vNew = createVersion("ZZ6V-NEW", "PENDING", prices("Ag", "5999.000000"));
        setPointer(vPrev);

        boolean entered = budgetService.processMaterial(vNew, customerNo, BigDecimal.ZERO, MATERIAL_NO);

        assertTrue(entered, "🔒 反向对照：相关元素价确有变动的料号必须照常进池");
        assertEquals(1L, reviewCount(vNew), "🔒 反向对照：material_price_review 必须有该料号这一条");
        assertEquals(vPrev, reviewPreviousVersionId(vNew),
            "review.previous_version_id 应记录进池时指针指向的那一版（锚点=指针版本，非『上一个 V 版本』）");
    }

    // =========================================================================
    // ③ 相关性精度：变动的元素该料号根本没用到 → 仍不进池
    //    证明「相关元素」取的是料号行里真实出现的元素，不是版本的全部元素
    // =========================================================================

    @Test
    void unrelatedElementChanged_stillDoesNotEnterPool() {
        setUpBaseFixture(); // 料号行里只有 Ag
        UUID vPrev = createVersion("ZZ6V-PREV", "SUPERSEDED",
            prices("Ag", "5450.000000", "Cu", "70.000000"));
        UUID vNew = createVersion("ZZ6V-NEW", "PENDING",
            prices("Ag", "5450.000000", "Cu", "88.000000")); // 只有 Cu 变了，该料号没用 Cu
        setPointer(vPrev);

        boolean entered = budgetService.processMaterial(vNew, customerNo, BigDecimal.ZERO, MATERIAL_NO);

        assertFalse(entered, "变动的 Cu 不在该料号的相关元素集合里 → 无事可审，不进池");
        assertEquals(0L, reviewCount(vNew), "只有无关元素变动时不应建 review 行");
        assertEquals(vNew, pointerVersionId(), "指针照常推进");
    }

    // =========================================================================
    // ④ 无指针边界：previousVersionId == null → 必须进池（不得当成"无变化"）
    // =========================================================================

    @Test
    void noPointerYet_mustEnterPool() {
        setUpBaseFixture();
        createVersion("ZZ6V-PREV", "SUPERSEDED", prices("Ag", "5450.000000"));
        UUID vNew = createVersion("ZZ6V-NEW", "PENDING", prices("Ag", "5450.000000"));
        // 🔒 故意不建 material_price_version_ref —— 该料号从未有过指针

        boolean entered = budgetService.processMaterial(vNew, customerNo, BigDecimal.ZERO, MATERIAL_NO);

        assertTrue(entered,
            "🔒 previousVersionId == null 没有可比基准，必须进池 —— 不能把『没得比』当成『无变化』");
        assertEquals(1L, reviewCount(vNew), "首次纳入的料号必须建 review 行");
    }

    // =========================================================================
    // fixture
    // =========================================================================

    /**
     * customer + component（配齐元素角色字段）+ quotation（DRAFT=活单）+ 冻结结构 QUOTE_CARD +
     * line_item（product_part_no_snapshot={@link #MATERIAL_NO}）+ component_data
     * （snapshot_rows 里一条 driverRow 元素=Ag）。
     */
    private void setUpBaseFixture() {
        QuarkusTransaction.requiringNew().run(() -> {
            customerId = UUID.randomUUID();
            exec("INSERT INTO customer (id, code, name) VALUES (:id,:code,:name)",
                "id", customerId, "code", customerNo, "name", "裁决39测试客户");

            componentId = UUID.randomUUID();
            // fields 必须真的定义「元素」字段：S3a/collectMaterialElementCodes 靠
            // FormulaCalculator.resolveRowByFieldName 按字段定义解析 driverRow，不是靠猜列名。
            exec("INSERT INTO component (id, name, code, fields, formulas, element_code_field, "
                + "element_price_field, element_currency_field) "
                + "VALUES (:id,'D39测试组件',:code,CAST(:fields AS jsonb),'[]','元素','元素单价',NULL)",
                "id", componentId, "code", "TEST-D39-" + componentId,
                "fields", "[{\"name\":\"元素\",\"field_type\":\"INPUT_TEXT\"}]");

            UUID anyUserId = (UUID) em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getSingleResult();
            quotationId = UUID.randomUUID();
            exec("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, "
                + "created_at, updated_at) VALUES (:id,:no,:cust,'D39测试单',:rep,'DRAFT',now(),now())",
                "id", quotationId, "no", "TEST-D39-" + quotationId, "cust", customerId, "rep", anyUserId);

            // 冻结结构：locatePriceBearingComponents 只认 quotation_view_structure.structure.tabs
            exec("INSERT INTO quotation_view_structure (quotation_id, view_kind, structure) "
                + "VALUES (:qid,'QUOTE_CARD',CAST(:s AS jsonb))",
                "qid", quotationId, "s", "{\"tabs\":[{\"componentId\":\"" + componentId + "\","
                    + "\"componentCode\":\"TEST-D39\",\"tabName\":\"材料成本\"}]}");

            lineItemId = UUID.randomUUID();
            exec("INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot, subtotal, created_at) "
                + "VALUES (:id,:qid,:mno,0,now())",
                "id", lineItemId, "qid", quotationId, "mno", MATERIAL_NO);

            exec("INSERT INTO quotation_line_component_data "
                + "(id, line_item_id, component_id, snapshot_rows, row_data, row_version, created_at) "
                + "VALUES (gen_random_uuid(), :lid, :cid, CAST(:sr AS jsonb), '[]', 0, now())",
                "lid", lineItemId, "cid", componentId,
                "sr", "[{\"driverRow\":{\"元素\":\"Ag\",\"元素单价\":5450}}]");
        });
    }

    private UUID createVersion(String versionNo, String status, Map<String, String> pricesByCode) {
        UUID vid = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            exec("INSERT INTO element_price_version (id, customer_no, version_no, base_date, status, "
                + "trigger_type, created_at) VALUES (:id,:c,:vn,CURRENT_DATE,:st,'MANUAL',now())",
                "id", vid, "c", customerNo, "vn", versionNo, "st", status);
            for (Map.Entry<String, String> e : pricesByCode.entrySet()) {
                exec("INSERT INTO element_price_version_item (version_id, element_code, current_price, currency) "
                    + "VALUES (:vid,:code,CAST(:p AS numeric),'CNY')",
                    "vid", vid, "code", e.getKey(), "p", e.getValue());
            }
        });
        return vid;
    }

    private void setPointer(UUID versionId) {
        QuarkusTransaction.requiringNew().run(() ->
            exec("INSERT INTO material_price_version_ref (customer_no, material_no, version_id, updated_at) "
                + "VALUES (:c,:m,:v,now())", "c", customerNo, "m", MATERIAL_NO, "v", versionId));
    }

    private static Map<String, String> prices(String... codeThenPrice) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < codeThenPrice.length; i += 2) out.put(codeThenPrice[i], codeThenPrice[i + 1]);
        return out;
    }

    // =========================================================================
    // 断言读（都在独立事务里读已提交数据）
    // =========================================================================

    private long reviewCount(UUID versionId) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_price_review WHERE version_id=:v AND material_no=:m")
            .setParameter("v", versionId).setParameter("m", MATERIAL_NO).getSingleResult()).longValue());
    }

    private long versionCount(UUID versionId) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM element_price_version WHERE id=:v")
            .setParameter("v", versionId).getSingleResult()).longValue());
    }

    private UUID pointerVersionId() {
        return QuarkusTransaction.requiringNew().call(() -> (UUID) em.createNativeQuery(
                "SELECT version_id FROM material_price_version_ref WHERE customer_no=:c AND material_no=:m")
            .setParameter("c", customerNo).setParameter("m", MATERIAL_NO).getSingleResult());
    }

    private UUID reviewPreviousVersionId(UUID versionId) {
        return QuarkusTransaction.requiringNew().call(() -> (UUID) em.createNativeQuery(
                "SELECT previous_version_id FROM material_price_review WHERE version_id=:v AND material_no=:m")
            .setParameter("v", versionId).setParameter("m", MATERIAL_NO).getSingleResult());
    }

    /** V-new 明细里某元素的价与期望值比较（BigDecimal.compareTo 语义，忽略 scale）。 */
    private int comparePrices(UUID versionId, String elementCode, String expected) {
        BigDecimal actual = QuarkusTransaction.requiringNew().call(() -> (BigDecimal) em.createNativeQuery(
                "SELECT current_price FROM element_price_version_item WHERE version_id=:v AND element_code=:e")
            .setParameter("v", versionId).setParameter("e", elementCode).getSingleResult());
        assertNotNull(actual, "V-new 明细里应有 " + elementCode + " 的记录");
        return actual.compareTo(new BigDecimal(expected));
    }

    private void exec(String sql, Object... nameValuePairs) {
        var q = em.createNativeQuery(sql);
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            q.setParameter((String) nameValuePairs[i], nameValuePairs[i + 1]);
        }
        q.executeUpdate();
    }
}
