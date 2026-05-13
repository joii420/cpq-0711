package com.cpq.configure;

import com.cpq.configure.dto.*;
import com.cpq.configure.service.ConfigureProductService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T24 — ConfigureProductService 集成测试 (8 场景).
 *
 * <p>隔离策略:
 * <ul>
 *   <li>{@code @TestTransaction} 标注的 test method 在结束后自动 rollback，不污染 DB。</li>
 *   <li>每个需要 quotation 的用例在自身事务内调用 {@link #seedQuotationId()} 插入临时
 *       customer + quotation 行，rollback 后随事务消失。</li>
 *   <li>material_recipe / composite_process_def 由 V171/V172 提交，在事务外持久，
 *       测试只读；AgNi90(editable), AgCu85(locked), AgCu90(locked), RIVET(ACTIVE)。</li>
 *   <li>countConfiguredMatPart() 仅计数 config_fingerprint IS NOT NULL 的行，
 *       避免与历史导入料号(无 fingerprint)互相干扰。</li>
 * </ul>
 *
 * <p>前提: V164-V174 迁移已全部 success=t；V171 seed 了 12 个 material_recipe；
 *   V172 seed 了 6 个 composite_process_def (含 RIVET)。
 */
@QuarkusTest
class ConfigureProductServiceTest {

    @Inject
    ConfigureProductService service;

    @Inject
    EntityManager em;

    // ── Seed helpers ─────────────────────────────────────────────────────────

    /**
     * 在当前事务内插入 customer + quotation，返回 quotation.id。
     * 依赖 admin 用户 (V1 seed 已提交，持久存在)。
     */
    @SuppressWarnings("unchecked")
    UUID seedQuotationId() {
        List<Object> uRows = em.createNativeQuery(
                "SELECT id FROM \"user\" WHERE username = 'admin' LIMIT 1")
            .getResultList();
        if (uRows.isEmpty()) {
            throw new IllegalStateException("admin user not found — V1 migration must have run");
        }
        UUID adminId = UUID.fromString(uRows.get(0).toString());

        UUID customerId = UUID.randomUUID();
        String custCode = "T24-" + customerId.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO customer (id, name, code, level, status, created_at, updated_at) " +
                "VALUES (:id, 'T24 Test Customer', :code, 'STANDARD', 'ACTIVE', NOW(), NOW())")
            .setParameter("id", customerId)
            .setParameter("code", custCode)
            .executeUpdate();

        UUID quotationId = UUID.randomUUID();
        String qNo = "QT-T24-" + quotationId.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO quotation " +
                "(id, quotation_number, customer_id, name, sales_rep_id, status, created_at, updated_at) " +
                "VALUES (:id, :qno, :cid, 'T24 Test Quotation', :uid, 'DRAFT', NOW(), NOW())")
            .setParameter("id", quotationId)
            .setParameter("qno", qNo)
            .setParameter("cid", customerId)
            .setParameter("uid", adminId)
            .executeUpdate();

        return quotationId;
    }

    /**
     * 在当前事务内插入一个没有 config_fingerprint 的 mat_part (模拟历史导入料号)。
     * 返回插入的 part_no。
     */
    String seedExistingMatPart() {
        String partNo = "T24-EXIST-" + UUID.randomUUID().toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO mat_part (part_no, product_type, created_at, updated_at) " +
                "VALUES (:pn, 'SIMPLE', NOW(), NOW())")
            .setParameter("pn", partNo)
            .executeUpdate();
        return partNo;
    }

    UUID operatorId() {
        return UUID.randomUUID();
    }

    ElementOverride elem(String code, String pct) {
        return new ElementOverride(code, new BigDecimal(pct));
    }

    PartRequest makeCustomPart(String recipeCode, List<ElementOverride> elems, BigDecimal weight) {
        PartRequest p = new PartRequest();
        p.partMode = "custom";
        p.recipeCode = recipeCode;
        p.elements = elems;
        p.processIds = List.of();
        p.unitWeightGrams = weight;
        p.name = "Test";
        return p;
    }

    ConfigureProductRequest simpleCustomReq(String recipeCode,
                                             List<ElementOverride> elems,
                                             BigDecimal weight) {
        ConfigureProductRequest req = new ConfigureProductRequest();
        req.productType = "SIMPLE";
        req.parts = List.of(makeCustomPart(recipeCode, elems, weight));
        return req;
    }

    /** 仅计数 config_fingerprint IS NOT NULL 的行，不受历史导入料号影响。 */
    @SuppressWarnings("unchecked")
    long countConfiguredMatPart() {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM mat_part WHERE config_fingerprint IS NOT NULL")
            .getSingleResult()).longValue();
    }

    @SuppressWarnings("unchecked")
    long countMatBomAssembly() {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM mat_bom WHERE bom_type = 'ASSEMBLY'")
            .getSingleResult()).longValue();
    }

    @SuppressWarnings("unchecked")
    long countMatCompositeProcess() {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM mat_composite_process")
            .getSingleResult()).longValue();
    }

    /** 建一个 SIMPLE custom 产品，返回其 hf_part_no。 */
    String createSimpleAndGetPn(UUID quotationId,
                                 String recipeCode,
                                 List<ElementOverride> elems) {
        ConfigureProductRequest req = simpleCustomReq(recipeCode, elems, new BigDecimal("10.0"));
        ConfigureProductResponse resp = service.configure(quotationId, req, operatorId());
        return (String) resp.lineItems.get(0).get("productPartNo");
    }

    // ── case 1: existing 路径 (复用已有料号) ─────────────────────────────────

    /**
     * case 1: 传 existing hf_part_no → service 直接返回该料号的 lineItem，不新建任何
     * configured mat_part (countConfiguredMatPart 不变)。
     */
    @Test
    @TestTransaction
    void existing_returnsLineItem_noNewMatPart() {
        UUID quotationId = seedQuotationId();
        String knownPn = seedExistingMatPart();

        ConfigureProductRequest req = new ConfigureProductRequest();
        req.productType = "SIMPLE";
        PartRequest pr = new PartRequest();
        pr.partMode = "existing";
        pr.existingHfPartNo = knownPn;
        pr.name = "测试";
        req.parts = List.of(pr);

        long before = countConfiguredMatPart();
        ConfigureProductResponse resp = service.configure(quotationId, req, operatorId());

        assertEquals(1, resp.lineItems.size());
        assertEquals(before, countConfiguredMatPart(),
            "existing 路径不应新增 configured mat_part");
        assertEquals(knownPn, resp.lineItems.get(0).get("productPartNo"));
    }

    // ── case 2: custom 未命中(新建) ──────────────────────────────────────────

    /**
     * case 2: custom 全新配置 → 新建 mat_part (fingerprint 写入) + mat_bom ELEMENT 行。
     * AgNi90 editable: Ag∈[85,95], Ni∈[5,15]。使用非默认比例确保指纹唯一。
     */
    @Test
    @TestTransaction
    void custom_uncached_createsMatPartAndBom() {
        UUID quotationId = seedQuotationId();

        ConfigureProductRequest req = simpleCustomReq("AgNi90", List.of(
            elem("Ag", "91.3"), elem("Ni", "8.7")
        ), new BigDecimal("12.5"));

        long before = countConfiguredMatPart();
        ConfigureProductResponse resp = service.configure(quotationId, req, operatorId());

        assertEquals(1, resp.lineItems.size());
        assertFalse(resp.fingerprintMatched, "首次创建不应命中");
        assertEquals(before + 1, countConfiguredMatPart(), "新增 1 行 configured mat_part");

        String pn = (String) resp.lineItems.get(0).get("productPartNo");
        // AgNi90 的 symbol="AgNi"，前缀为 CFG-AgNi-
        assertTrue(pn.startsWith("CFG-AgNi-"), "应是 CFG-AgNi- 前缀，实际: " + pn);
    }

    // ── case 3: custom 命中复用 ───────────────────────────────────────────────

    /**
     * case 3: 同一事务内先建，再以完全相同的元素配置再次请求 → 命中指纹复用，
     * countConfiguredMatPart 不再增加；unitWeightGrams 不参与指纹。
     */
    @Test
    @TestTransaction
    void custom_cached_reusesHfPartNo() {
        UUID quotationId = seedQuotationId();

        String pn1 = createSimpleAndGetPn(quotationId, "AgNi90", List.of(
            elem("Ag", "92.1"), elem("Ni", "7.9")));

        long before = countConfiguredMatPart();

        // 第 2 次: 同配置，weight 不同
        ConfigureProductRequest req2 = simpleCustomReq("AgNi90", List.of(
            elem("Ag", "92.1"), elem("Ni", "7.9")
        ), new BigDecimal("99.0"));
        ConfigureProductResponse r2 = service.configure(quotationId, req2, operatorId());

        assertEquals(before, countConfiguredMatPart(), "命中不应新增 configured mat_part");
        assertEquals(pn1, r2.lineItems.get(0).get("productPartNo"));
        assertTrue(r2.fingerprintMatched, "应标注 fingerprintMatched=true");
        assertTrue(r2.reusedHfPartNos.contains(pn1),
            "reusedHfPartNos 应含首次创建的料号");
    }

    // ── case 4: 元素含量和 ≠ 100 → IllegalArgumentException ─────────────────

    /**
     * case 4: elements sum = 90 ≠ 100 → validateCustomPart 抛出。
     * validateRequest(req) 通过(SIMPLE, parts.size=1)，之后进入 resolvePart → validateCustomPart。
     * getCustomerIdFromQuotation 在 validateRequest 之后、resolvePart 之前调用，
     * 因此需要有效 quotation；使用 @TestTransaction 在事务内提供。
     */
    @Test
    @TestTransaction
    void custom_sumNot100_throws() {
        UUID quotationId = seedQuotationId();

        ConfigureProductRequest req = simpleCustomReq("AgNi90", List.of(
            elem("Ag", "80.0"), elem("Ni", "10.0")  // sum = 90, not 100
        ), null);

        assertThrows(IllegalArgumentException.class,
            () -> service.configure(quotationId, req, operatorId()),
            "元素含量和 = 90 应抛 IllegalArgumentException");
    }

    // ── case 5: locked 元素被改 → IllegalArgumentException ───────────────────

    /**
     * case 5: AgCu85 的 Ag is_locked=true, default_pct=85.0。
     * 传 Ag=90.0 (≠ 85) → validateCustomPart 抛 "元素已锁定，不可修改"。
     * Cu=10 使 sum=100，让含量和校验通过，仅锁定校验触发。
     */
    @Test
    @TestTransaction
    void custom_lockedElementModified_throws() {
        UUID quotationId = seedQuotationId();

        ConfigureProductRequest req = simpleCustomReq("AgCu85", List.of(
            elem("Ag", "90.0"), elem("Cu", "10.0")  // Ag should be locked at 85.0
        ), null);

        assertThrows(IllegalArgumentException.class,
            () -> service.configure(quotationId, req, operatorId()),
            "修改 locked 元素应抛 IllegalArgumentException");
    }

    // ── case 6: 组合产品全新 ─────────────────────────────────────────────────

    /**
     * case 6: COMPOSITE 两个全新配件 + 组合工艺 RIVET
     * → 3 个新 configured mat_part (父+2子)、2 条 ASSEMBLY bom、1 条 composite_process。
     * AgCu90: Ag=90(locked), Cu=10(locked)，必须传精确值。
     */
    @Test
    @TestTransaction
    void composite_allNew_buildsParentAndChildrenAndAssemblyBom() {
        UUID quotationId = seedQuotationId();

        PartRequest p1 = makeCustomPart("AgNi90", List.of(
            elem("Ag", "93.7"), elem("Ni", "6.3")), new BigDecimal("10.0"));
        p1.name = "配件1";

        PartRequest p2 = makeCustomPart("AgCu90", List.of(
            elem("Ag", "90.0"), elem("Cu", "10.0")), new BigDecimal("11.0"));
        p2.name = "配件2";

        ConfigureProductRequest req = new ConfigureProductRequest();
        req.productType = "COMPOSITE";
        req.parts = List.of(p1, p2);

        CompositeProcessRequest cp = new CompositeProcessRequest();
        cp.defCode = "RIVET";
        cp.participatingPartIndexes = List.of(0, 1);
        cp.params = Map.of("pressure", 5.0, "height", 3.2);
        req.compositeProcesses = List.of(cp);

        long beforeMp  = countConfiguredMatPart();
        long beforeAsm = countMatBomAssembly();
        long beforeCp  = countMatCompositeProcess();

        ConfigureProductResponse resp = service.configure(quotationId, req, operatorId());

        assertEquals(3, resp.lineItems.size(), "1 父 + 2 子 line_items");
        assertEquals(beforeMp  + 3, countConfiguredMatPart(), "3 个新 configured mat_part");
        assertEquals(beforeAsm + 2, countMatBomAssembly(),    "2 ASSEMBLY bom 行");
        assertEquals(beforeCp  + 1, countMatCompositeProcess(), "1 组合工艺行");
    }

    // ── case 7: 组合产品子复用 ───────────────────────────────────────────────

    /**
     * case 7: 同事务内先独立建两个配件，再以相同配置组合
     * → 子配件命中指纹复用，仅新建父 mat_part；reusedHfPartNos 含两个子料号。
     */
    @Test
    @TestTransaction
    void composite_childrenReused_onlyParentCreated() {
        UUID quotationId = seedQuotationId();

        String pn1 = createSimpleAndGetPn(quotationId, "AgNi90",
            List.of(elem("Ag", "94.5"), elem("Ni", "5.5")));
        String pn2 = createSimpleAndGetPn(quotationId, "AgCu90",
            List.of(elem("Ag", "90.0"), elem("Cu", "10.0")));

        PartRequest rp1 = makeCustomPart("AgNi90",
            List.of(elem("Ag", "94.5"), elem("Ni", "5.5")), new BigDecimal("10.0"));
        PartRequest rp2 = makeCustomPart("AgCu90",
            List.of(elem("Ag", "90.0"), elem("Cu", "10.0")), new BigDecimal("11.0"));

        ConfigureProductRequest req = new ConfigureProductRequest();
        req.productType = "COMPOSITE";
        req.parts = List.of(rp1, rp2);
        req.compositeProcesses = List.of();

        long beforeMp = countConfiguredMatPart();
        ConfigureProductResponse resp = service.configure(quotationId, req, operatorId());

        assertEquals(beforeMp + 1, countConfiguredMatPart(),
            "子配件复用时仅 1 个新 configured mat_part(父级)");
        assertTrue(resp.reusedHfPartNos.contains(pn1),
            "pn1 应在 reusedHfPartNos: " + resp.reusedHfPartNos);
        assertTrue(resp.reusedHfPartNos.contains(pn2),
            "pn2 应在 reusedHfPartNos: " + resp.reusedHfPartNos);
    }

    // ── case 8: 组合工艺 participating < 2 → IllegalArgumentException ─────────

    /**
     * case 8: compositeProcesses[0].participatingPartIndexes.size() = 1 < 2
     * → validateRequest 在 getCustomerIdFromQuotation 之前抛出，无需有效 quotation。
     */
    @Test
    void composite_participatingLessThan2_throws() {
        UUID fakeQuotationId = UUID.randomUUID();

        PartRequest p1 = makeCustomPart("AgNi90",
            List.of(elem("Ag", "90.0"), elem("Ni", "10.0")), new BigDecimal("10.0"));
        PartRequest p2 = makeCustomPart("AgCu90",
            List.of(elem("Ag", "90.0"), elem("Cu", "10.0")), new BigDecimal("11.0"));

        ConfigureProductRequest req = new ConfigureProductRequest();
        req.productType = "COMPOSITE";
        req.parts = List.of(p1, p2);

        CompositeProcessRequest cp = new CompositeProcessRequest();
        cp.defCode = "RIVET";
        cp.participatingPartIndexes = List.of(0);  // only 1, requires >= 2
        cp.params = Map.of();
        req.compositeProcesses = List.of(cp);

        assertThrows(IllegalArgumentException.class,
            () -> service.configure(fakeQuotationId, req, operatorId()),
            "participating < 2 应在 validateRequest 中抛 IllegalArgumentException");
    }
}
