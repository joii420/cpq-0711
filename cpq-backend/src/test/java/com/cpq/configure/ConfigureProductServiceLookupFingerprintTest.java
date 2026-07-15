package com.cpq.configure;

import com.cpq.configure.dto.CompositeProcessRequest;
import com.cpq.configure.dto.ConfigureProductRequest;
import com.cpq.configure.dto.ConfigureProductResponse;
import com.cpq.configure.dto.ElementOverride;
import com.cpq.configure.dto.LookupFingerprintRequest;
import com.cpq.configure.dto.LookupFingerprintResponse;
import com.cpq.configure.dto.PartRequest;
import com.cpq.configure.service.ConfigureProductService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0712 缺口2(3a) — {@code POST /lookup-fingerprint} 销售侧客户维度指纹预览集成测试。
 *
 * <p>验收本次实现的三条核心行为：
 * <ul>
 *   <li><b>SIMPLE 同源</b>：对已 {@code configure()} 落库的配置原样再 {@code lookupFingerprint()}，
 *       必须 matched=true 且 matchedPartNo = 提交时铸的报价料号（预览与提交同一套
 *       {@code SalesFingerprintCalculator}/{@code SalesSignatureRepository}，同口径不误导）。</li>
 *   <li><b>COMPOSITE 无副作用</b>：全部子件已存在时父级命中；任一子件未曾选配过（将新建）时
 *       整体早退 matched=false，且全程<b>不 mint、不落库</b>（{@code sel_part_signature} /
 *       {@code material_master} 行数预览前后不变）。</li>
 *   <li><b>预览零副作用</b>：无论命中与否，{@code lookupFingerprint} 调用前后 DB 行数必须相等
 *       （区别于提交端 {@code configure()} 会真正 mint/落库）。</li>
 * </ul>
 *
 * <p>夹具风格与 {@link ConfigureProductServiceSalesFingerprintTest} / {@link
 * ConfigureProductServiceB2LedgerTest} 同款：每个 {@code @TestTransaction} 测试方法自建
 * customer+quotation，方法结束自动回滚，不污染共享 DB。
 */
@QuarkusTest
class ConfigureProductServiceLookupFingerprintTest {

    @Inject
    ConfigureProductService service;

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void seedDemoMaterials() {
        DemoMaterialRecipeFixture.ensureSeeded(em);
    }

    // ── 夹具 helper（同 ConfigureProductServiceB2LedgerTest 口径） ─────────────

    record SeededQuotation(UUID quotationId, String customerCode) {}

    @SuppressWarnings("unchecked")
    SeededQuotation seedQuotation(String suffix) {
        List<Object> uRows = em.createNativeQuery(
                "SELECT id FROM \"user\" WHERE username = 'admin' LIMIT 1")
            .getResultList();
        if (uRows.isEmpty()) {
            throw new IllegalStateException("admin user not found — V1 migration must have run");
        }
        UUID adminId = UUID.fromString(uRows.get(0).toString());

        UUID customerId = UUID.randomUUID();
        String custCode = "LKFP" + suffix + customerId.toString().substring(0, 4);
        em.createNativeQuery(
                "INSERT INTO customer (id, name, code, level, status, created_at, updated_at) " +
                "VALUES (:id, 'LookupFingerprint Test Customer', :code, 'STANDARD', 'ACTIVE', NOW(), NOW())")
            .setParameter("id", customerId)
            .setParameter("code", custCode)
            .executeUpdate();

        UUID quotationId = UUID.randomUUID();
        String qNo = "QT-LKFP-" + quotationId.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO quotation " +
                "(id, quotation_number, customer_id, name, sales_rep_id, status, created_at, updated_at) " +
                "VALUES (:id, :qno, :cid, 'LookupFingerprint Test Quotation', :uid, 'DRAFT', NOW(), NOW())")
            .setParameter("id", quotationId)
            .setParameter("qno", qNo)
            .setParameter("cid", customerId)
            .setParameter("uid", adminId)
            .executeUpdate();

        return new SeededQuotation(quotationId, custCode);
    }

    UUID operatorId() { return UUID.randomUUID(); }

    ElementOverride elem(String code, String pct) {
        return new ElementOverride(code, new BigDecimal(pct));
    }

    PartRequest makeCustomPart(String recipeCode, List<ElementOverride> elems, BigDecimal weight) {
        PartRequest p = new PartRequest();
        p.partMode = "custom";
        p.recipeCode = recipeCode;
        p.elements = elems;
        p.processNos = List.of();
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

    LookupFingerprintRequest lookupSimpleReq(String customerNo, String recipeCode, List<ElementOverride> elems) {
        LookupFingerprintRequest req = new LookupFingerprintRequest();
        req.customerNo = customerNo;
        req.parts = List.of(makeCustomPart(recipeCode, elems, null));
        return req;
    }

    @SuppressWarnings("unchecked")
    long signatureCountFor(String customerNo) {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM sel_part_signature WHERE customer_no=:cn")
            .setParameter("cn", customerNo).getSingleResult()).longValue();
    }

    @SuppressWarnings("unchecked")
    long materialMasterTotalCount() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM material_master")
            .getSingleResult()).longValue();
    }

    // ── SIMPLE ────────────────────────────────────────────────────────────

    @Test
    @TestTransaction
    void simple_afterConfigure_lookupSameConfig_matchesMintedPartNo() {
        SeededQuotation sq = seedQuotation("S1");

        ConfigureProductRequest cReq = simpleCustomReq("AgNi90",
            List.of(elem("Ag", "91.0"), elem("Ni", "9.0")), new BigDecimal("10.0"));
        ConfigureProductResponse cResp = service.configure(sq.quotationId(), cReq, operatorId());
        String mintedPn = (String) cResp.lineItems.get(0).get("productPartNo");
        assertFalse(cResp.fingerprintMatched, "首次选配不应命中复用");

        LookupFingerprintRequest lReq = lookupSimpleReq(sq.customerCode(), "AgNi90",
            List.of(elem("Ag", "91.0"), elem("Ni", "9.0")));

        long sigBefore = signatureCountFor(sq.customerCode());
        long mmBefore = materialMasterTotalCount();

        LookupFingerprintResponse lResp = service.lookupFingerprint(lReq);

        assertTrue(lResp.matched, "同配置预览应命中提交时铸的料号");
        assertEquals(mintedPn, lResp.matchedPartNo);
        assertEquals(mintedPn, lResp.hfPartNo, "hfPartNo 应与 matchedPartNo 同值(兼容字段)");
        assertNotNull(lResp.snapshot, "命中应带快照");

        // 预览零副作用
        assertEquals(sigBefore, signatureCountFor(sq.customerCode()), "预览不应新增 sel_part_signature 行");
        assertEquals(mmBefore, materialMasterTotalCount(), "预览不应新增 material_master 行");
    }

    @Test
    @TestTransaction
    void simple_differentConfig_notMatched_noSideEffect() {
        SeededQuotation sq = seedQuotation("S2");

        ConfigureProductRequest cReq = simpleCustomReq("AgNi90",
            List.of(elem("Ag", "91.0"), elem("Ni", "9.0")), new BigDecimal("10.0"));
        service.configure(sq.quotationId(), cReq, operatorId());

        // 不同配比(90/10 而非 91/9) → 不同指纹, 该客户名下从未选配过
        LookupFingerprintRequest lReq = lookupSimpleReq(sq.customerCode(), "AgNi90",
            List.of(elem("Ag", "90.0"), elem("Ni", "10.0")));

        long sigBefore = signatureCountFor(sq.customerCode());
        long mmBefore = materialMasterTotalCount();

        LookupFingerprintResponse lResp = service.lookupFingerprint(lReq);

        assertFalse(lResp.matched, "不同配置不应命中");
        assertNull(lResp.matchedPartNo);
        assertNull(lResp.hfPartNo);

        assertEquals(sigBefore, signatureCountFor(sq.customerCode()), "预览(未命中)不应新增签名行");
        assertEquals(mmBefore, materialMasterTotalCount(), "预览(未命中)不应新增 material_master 行");
    }

    // ── COMPOSITE ─────────────────────────────────────────────────────────

    @Test
    @TestTransaction
    void composite_allChildrenAlreadyExist_parentMatched_noSideEffect() {
        SeededQuotation sq = seedQuotation("C1");

        PartRequest p0 = makeCustomPart("AgNi90",
            List.of(elem("Ag", "91.0"), elem("Ni", "9.0")), new BigDecimal("5.0"));
        p0.quantity = 1;
        PartRequest p1 = makeCustomPart("AgCu85",
            List.of(elem("Ag", "85.0"), elem("Cu", "15.0")), new BigDecimal("5.0"));
        p1.quantity = 1;

        ConfigureProductRequest cReq = new ConfigureProductRequest();
        cReq.productType = "COMPOSITE";
        cReq.parts = List.of(p0, p1);
        CompositeProcessRequest cp = new CompositeProcessRequest();
        cp.defCode = "MRO-AS-0001"; // ASSEMBLY「总装配」，与 ConfigureProductServiceB2LedgerTest 同款夹具
        cp.participatingPartIndexes = List.of(0, 1);
        cp.params = Map.of();
        cReq.compositeProcesses = List.of(cp);

        ConfigureProductResponse cResp = service.configure(sq.quotationId(), cReq, operatorId());
        assertEquals("COMPOSITE", cResp.productType);
        String parentPn = (String) cResp.lineItems.get(0).get("productPartNo");

        // 预览：同一 customerNo + 同一子件集(recipe+elements+qty) + 同一组合工艺 defCode
        PartRequest lp0 = makeCustomPart("AgNi90", List.of(elem("Ag", "91.0"), elem("Ni", "9.0")), null);
        lp0.quantity = 1;
        PartRequest lp1 = makeCustomPart("AgCu85", List.of(elem("Ag", "85.0"), elem("Cu", "15.0")), null);
        lp1.quantity = 1;

        LookupFingerprintRequest lReq = new LookupFingerprintRequest();
        lReq.customerNo = sq.customerCode();
        lReq.parts = List.of(lp0, lp1);
        CompositeProcessRequest lcp = new CompositeProcessRequest();
        lcp.defCode = "MRO-AS-0001";
        lReq.compositeProcesses = List.of(lcp);

        long sigBefore = signatureCountFor(sq.customerCode());
        long mmBefore = materialMasterTotalCount();

        LookupFingerprintResponse lResp = service.lookupFingerprint(lReq);

        assertTrue(lResp.matched, "全部子件均已存在 + 同组合工艺应命中父级");
        assertEquals(parentPn, lResp.matchedPartNo);

        assertEquals(sigBefore, signatureCountFor(sq.customerCode()), "预览不应新增签名行(不铸子件也不铸父级)");
        assertEquals(mmBefore, materialMasterTotalCount(), "预览不应新增 material_master 行");
    }

    /**
     * 关键场景：组合体一子件（AgCu90）从未被任何 configure() 落过库 —— 提交时会新建它，
     * 故整个组合体也必是新组合。预览必须在算完子件指纹、查无该子件后<b>直接早退</b>，
     * 既不为该子件铸号，也不再往下算/查父级指纹（父级组合从未存在，查了也必 null，早退是等价优化
     * 但更重要的是防止任何后续代码路径误触发 mint）。
     */
    @Test
    @TestTransaction
    void composite_oneChildNeverConfigured_earlyExitNotMatched_noMintNoSideEffect() {
        SeededQuotation sq = seedQuotation("C2");

        // 只把 p0(AgNi90/92:8) 通过 SIMPLE 提交建好；p1(AgCu90) 该客户名下从未选配过
        PartRequest p0cfg = makeCustomPart("AgNi90",
            List.of(elem("Ag", "92.0"), elem("Ni", "8.0")), new BigDecimal("5.0"));
        ConfigureProductRequest cReq0 = new ConfigureProductRequest();
        cReq0.productType = "SIMPLE";
        cReq0.parts = List.of(p0cfg);
        service.configure(sq.quotationId(), cReq0, operatorId());

        // 预览 COMPOSITE: p0 同配置(已存在) + p1(AgCu90, 未知子件)
        PartRequest lp0 = makeCustomPart("AgNi90", List.of(elem("Ag", "92.0"), elem("Ni", "8.0")), null);
        lp0.quantity = 1;
        PartRequest lp1 = makeCustomPart("AgCu90", List.of(elem("Ag", "90.0"), elem("Cu", "10.0")), null);
        lp1.quantity = 1;

        LookupFingerprintRequest lReq = new LookupFingerprintRequest();
        lReq.customerNo = sq.customerCode();
        lReq.parts = List.of(lp0, lp1);

        long sigBefore = signatureCountFor(sq.customerCode());
        long mmBefore = materialMasterTotalCount();

        LookupFingerprintResponse lResp = service.lookupFingerprint(lReq);

        assertFalse(lResp.matched, "任一子件未曾选配过(将新建) → 组合体整体应判定新建, 早退");
        assertNull(lResp.matchedPartNo);
        assertNull(lResp.snapshot);

        assertEquals(sigBefore, signatureCountFor(sq.customerCode()),
            "早退不应新增签名行(不铸子件, 不铸父级)");
        assertEquals(mmBefore, materialMasterTotalCount(),
            "早退不应新增 material_master 行(无 mint)");
    }

    // ── 请求校验 ──────────────────────────────────────────────────────────

    @Test
    @TestTransaction
    void missingCustomerNo_throws() {
        LookupFingerprintRequest req = new LookupFingerprintRequest();
        req.parts = List.of(makeCustomPart("AgNi90",
            List.of(elem("Ag", "91.0"), elem("Ni", "9.0")), null));
        assertThrows(IllegalArgumentException.class, () -> service.lookupFingerprint(req));
    }

    @Test
    @TestTransaction
    void missingParts_throws() {
        LookupFingerprintRequest req = new LookupFingerprintRequest();
        req.customerNo = "ANY";
        assertThrows(IllegalArgumentException.class, () -> service.lookupFingerprint(req));
    }
}
