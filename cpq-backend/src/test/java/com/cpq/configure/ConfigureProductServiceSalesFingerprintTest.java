package com.cpq.configure;

import com.cpq.configure.dto.ConfigureProductRequest;
import com.cpq.configure.dto.ConfigureProductResponse;
import com.cpq.configure.dto.ElementOverride;
import com.cpq.configure.dto.PartRequest;
import com.cpq.configure.service.ConfigureProductService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 选配 Plan 3b (T7A) — ConfigureProductService 销售侧客户维度指纹发号集成测试。
 *
 * <p>3b 把 custom SIMPLE / COMPOSITE 父级的发号从 {@code partNoProvider}(CFG- 前缀 +
 * 生产侧全局指纹 {@code material_master.config_fingerprint} 全局唯一) 换成
 * {@code QuoteMaterialNoAllocator.mintAndRegister}(报价料号格式
 * {@code {4位客户码}-{yyMM}{6位流水}}，正则 {@code ^\d{4}-\d{6,}$})，复用判定换成销售侧
 * 客户维度指纹({@code sel_part_signature} 表，按 customer_no+structure_version+
 * config_fingerprint 唯一)。本类补两条现役 {@link ConfigureProductServiceTest}
 * 未覆盖的核心规则专项断言：
 * <ul>
 *   <li><b>R1（跨客户不撞库）</b>：两个不同客户各自对同一 recipe+elements 选配一次
 *       → 各得不同报价料号；material_master.config_fingerprint 均为 NULL（防跨客户撞
 *       生产侧全局唯一索引 uq_material_master_fingerprint）；无唯一冲突异常。</li>
 *   <li><b>R3（同客户复用不重复落库，守 AP-51）</b>：同一客户对同一 recipe+elements
 *       选配两次 → 第二次命中复用同一报价料号；material_master / element_bom_item
 *       该料号行数第二次前后不变（幂等，不重复落库/累加）。</li>
 * </ul>
 *
 * <p><b>隔离策略</b>：与 {@link ConfigureProductServiceTest} 同款 —— 每个 {@code @TestTransaction}
 * 测试方法自建 customer + quotation，方法结束自动 rollback，不污染 DB，无需 {@code @AfterEach}
 * 手工清理（R1 的两个不同客户 / R3 的两次 configure 调用都在同一个测试事务内，Postgres
 * 同一事务能看到自己未提交的写入，语义与真实跨请求场景一致，因为 configure() 的
 * {@code @Transactional} 默认 REQUIRED 会 join 测试事务）。
 */
@QuarkusTest
class ConfigureProductServiceSalesFingerprintTest {

    private static final String PREFIX = "T7ASF_";

    @Inject
    ConfigureProductService service;

    @Inject
    EntityManager em;

    // ── Seed helpers（与 ConfigureProductServiceTest 同款，独立复制避免跨测试类耦合）───────

    @SuppressWarnings("unchecked")
    UUID seedQuotationForNewCustomer(String custCodeSuffix) {
        List<Object> uRows = em.createNativeQuery(
                "SELECT id FROM \"user\" WHERE username = 'admin' LIMIT 1")
            .getResultList();
        if (uRows.isEmpty()) {
            throw new IllegalStateException("admin user not found — V1 migration must have run");
        }
        UUID adminId = UUID.fromString(uRows.get(0).toString());

        UUID customerId = UUID.randomUUID();
        String custCode = PREFIX + custCodeSuffix + "_" + customerId.toString().substring(0, 6);
        em.createNativeQuery(
                "INSERT INTO customer (id, name, code, level, status, created_at, updated_at) " +
                "VALUES (:id, 'T7ASF Test Customer', :code, 'STANDARD', 'ACTIVE', NOW(), NOW())")
            .setParameter("id", customerId)
            .setParameter("code", custCode)
            .executeUpdate();

        UUID quotationId = UUID.randomUUID();
        String qNo = "QT-T7ASF-" + quotationId.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO quotation " +
                "(id, quotation_number, customer_id, name, sales_rep_id, status, created_at, updated_at) " +
                "VALUES (:id, :qno, :cid, 'T7ASF Test Quotation', :uid, 'DRAFT', NOW(), NOW())")
            .setParameter("id", quotationId)
            .setParameter("qno", qNo)
            .setParameter("cid", customerId)
            .setParameter("uid", adminId)
            .executeUpdate();

        return quotationId;
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

    ConfigureProductRequest simpleCustomReq(String recipeCode, List<ElementOverride> elems, BigDecimal weight) {
        ConfigureProductRequest req = new ConfigureProductRequest();
        req.productType = "SIMPLE";
        req.parts = List.of(makeCustomPart(recipeCode, elems, weight));
        return req;
    }

    @SuppressWarnings("unchecked")
    long countMatPartByNo(String materialNo) {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM material_master WHERE material_no = :mn")
            .setParameter("mn", materialNo).getSingleResult()).longValue();
    }

    @SuppressWarnings("unchecked")
    Object matPartFingerprint(String materialNo) {
        return em.createNativeQuery(
                "SELECT config_fingerprint FROM material_master WHERE material_no = :mn")
            .setParameter("mn", materialNo).getSingleResult();
    }

    @SuppressWarnings("unchecked")
    long countElementBomByNo(String materialNo) {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM element_bom_item WHERE material_no = :mn")
            .setParameter("mn", materialNo).getSingleResult()).longValue();
    }

    // ── R1: 跨客户不撞库 ──────────────────────────────────────────────────────

    /**
     * 两个不同客户各自对同一 recipe+elements 选配一次：
     * <ul>
     *   <li>各得不同的 quotePartNo（销售发号内嵌客户四位码，天然不同）；</li>
     *   <li>两行 material_master 的 config_fingerprint 均 NULL（R1，防跨客户撞
     *       生产侧全局唯一索引 uq_material_master_fingerprint）；</li>
     *   <li>两次 configure() 都不抛异常（若沿用生产侧全局指纹发号，第二次会因唯一键冲突 500）。</li>
     * </ul>
     */
    @Test
    @TestTransaction
    void r1_differentCustomers_sameConfig_mintDifferentQuotePartNos_fingerprintNull() {
        UUID qA = seedQuotationForNewCustomer("A");
        UUID qB = seedQuotationForNewCustomer("B");

        ConfigureProductRequest reqA = simpleCustomReq("AgNi90", List.of(
            elem("Ag", "91.0"), elem("Ni", "9.0")), new BigDecimal("10.0"));
        ConfigureProductRequest reqB = simpleCustomReq("AgNi90", List.of(
            elem("Ag", "91.0"), elem("Ni", "9.0")), new BigDecimal("10.0"));

        ConfigureProductResponse respA = service.configure(qA, reqA, operatorId());
        ConfigureProductResponse respB = service.configure(qB, reqB, operatorId());

        assertEquals(1, respA.lineItems.size());
        assertEquals(1, respB.lineItems.size());

        String pnA = (String) respA.lineItems.get(0).get("productPartNo");
        String pnB = (String) respB.lineItems.get(0).get("productPartNo");

        assertNotEquals(pnA, pnB, "R1: 不同客户同配置应各自铸不同报价料号");
        assertTrue(pnA.matches("\\d{4}-\\d{6,}"), "pnA 应是销售发号格式 NNNN-YYMMNNNNNN，实际: " + pnA);
        assertTrue(pnB.matches("\\d{4}-\\d{6,}"), "pnB 应是销售发号格式 NNNN-YYMMNNNNNN，实际: " + pnB);

        assertEquals(1, countMatPartByNo(pnA), "pnA 应恰好建 1 行 material_master");
        assertEquals(1, countMatPartByNo(pnB), "pnB 应恰好建 1 行 material_master");

        assertNull(matPartFingerprint(pnA),
            "R1: 选配落库 config_fingerprint 应为 NULL（防跨客户撞全局唯一索引）: " + pnA);
        assertNull(matPartFingerprint(pnB),
            "R1: 选配落库 config_fingerprint 应为 NULL（防跨客户撞全局唯一索引）: " + pnB);
    }

    // ── R3: 同客户复用不重复落库 ─────────────────────────────────────────────

    /**
     * 同一客户对同一 recipe+elements 选配两次（第二次 unitWeightGrams 不同，不参与指纹）：
     * <ul>
     *   <li>第二次命中复用，返回与第一次相同的 quotePartNo；</li>
     *   <li>material_master / element_bom_item 该料号行数第二次前后不变（幂等，守 AP-51，
     *       不重复落库/累加）。</li>
     * </ul>
     */
    @Test
    @TestTransaction
    void r3_sameCustomer_sameConfig_reusesSameQuotePartNo_noDuplicateRows() {
        UUID q = seedQuotationForNewCustomer("C");

        ConfigureProductRequest req1 = simpleCustomReq("AgNi90", List.of(
            elem("Ag", "92.5"), elem("Ni", "7.5")), new BigDecimal("10.0"));
        ConfigureProductResponse resp1 = service.configure(q, req1, operatorId());
        String pn1 = (String) resp1.lineItems.get(0).get("productPartNo");
        assertFalse(resp1.fingerprintMatched, "首次选配不应命中复用");
        assertTrue(pn1.matches("\\d{4}-\\d{6,}"), "应是销售发号格式 NNNN-YYMMNNNNNN，实际: " + pn1);

        long mpCountAfterFirst = countMatPartByNo(pn1);
        long bomCountAfterFirst = countElementBomByNo(pn1);
        assertEquals(1, mpCountAfterFirst);

        // 同客户同配置再次选配（weight 不同，不参与指纹）
        ConfigureProductRequest req2 = simpleCustomReq("AgNi90", List.of(
            elem("Ag", "92.5"), elem("Ni", "7.5")), new BigDecimal("55.0"));
        ConfigureProductResponse resp2 = service.configure(q, req2, operatorId());
        String pn2 = (String) resp2.lineItems.get(0).get("productPartNo");

        assertEquals(pn1, pn2, "R3: 同客户同配置应复用同一报价料号");
        assertTrue(resp2.fingerprintMatched, "第二次应标注命中复用");
        assertTrue(resp2.reusedHfPartNos.contains(pn1),
            "reusedHfPartNos 应含首次创建的料号: " + resp2.reusedHfPartNos);

        assertEquals(mpCountAfterFirst, countMatPartByNo(pn1),
            "R3: material_master 该料号行数不应因第二次选配增加（幂等，守 AP-51）");
        assertEquals(bomCountAfterFirst, countElementBomByNo(pn1),
            "R3: element_bom_item 该料号行数不应因第二次选配增加（幂等）");
    }
}
