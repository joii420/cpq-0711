package com.cpq.configure;

import com.cpq.configure.dto.*;
import com.cpq.configure.service.ConfigureProductService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
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
 *   <li>countConfiguredMatPart() 仅计数 material_master.config_fingerprint IS NOT NULL 的行，
 *       避免与历史导入料号(无 fingerprint)互相干扰。V6 单写后料号身份表从 mat_part 切为 material_master。
 *       <b>选配 Plan 3b (R1) 后失效为「恒 0」判据</b>：custom/COMPOSITE 落库的 config_fingerprint
 *       一律为 NULL（防跨客户撞生产侧全局唯一索引），仅保留给 case1/case3 的「不应新增」类断言
 *       （0 == 0 场景仍成立）；「本次新增了 N 行」类断言改用下方 {@link #matPartExists} /
 *       {@link #countAllMatPart}（按料号存在性 / 总行数判断，不再依赖 fingerprint 维度）。</li>
 *   <li>countMatBomAssembly() 查 material_bom_item(characteristic='ASSEMBLY')，替代原 mat_bom(bom_type='ASSEMBLY')。</li>
 *   <li>countMatCompositeProcess() 查 capacity(resource_group_no='QUOTE_ASSEMBLY')，替代原 mat_composite_process。</li>
 *   <li>seedExistingMatPart() 向 material_master 插入已知料号，满足 existing 路径存在性校验（读 material_master）。</li>
 * </ul>
 *
 * <p>前提: V164-V174 迁移已全部 success=t；V171 seed 了 12 个 material_recipe；
 *   V172 seed 了 6 个 composite_process_def (含 RIVET)。
 *
 * <p><b>选配 Plan 3b (2026-07-08)</b>：发号从 partNoProvider(CFG- 前缀) 换成
 * {@code QuoteMaterialNoAllocator.mintAndRegister} → 报价料号格式
 * {@code {4位客户码}-{yyMM}{6位流水}}（正则 {@code ^\d{4}-\d{6,}$}）；复用判定从生产侧全局指纹
 * 换成销售侧客户维度指纹({@code sel_part_signature})；选配落库的
 * {@code material_master.config_fingerprint} 一律 NULL(R1)。R1/R3 的跨客户 + 复用专项断言见
 * {@link ConfigureProductServiceSalesFingerprintTest}。
 */
@QuarkusTest
class ConfigureProductServiceTest {

    @Inject
    ConfigureProductService service;

    @Inject
    EntityManager em;

    /**
     * task-0708：V318 删了 V171 的 demo 材质 seed，这里幂等补种本类依赖的
     * AgNi90/AgCu85/AgCu90/AgNi95（独立事务提交，测试方法只读、可见）。
     */
    @BeforeEach
    @Transactional
    void seedDemoMaterials() {
        DemoMaterialRecipeFixture.ensureSeeded(em);
    }

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
     * 在当前事务内插入一个 material_master 行 (模拟已有料号,无 config_fingerprint 表示历史导入,
     * 有 config_fingerprint 也可用于模拟已完成选配的料号)。
     * existing 路径存在性校验读 material_master.material_no，因此夹具改插此表。
     * 返回插入的 material_no。
     */
    String seedExistingMatPart() {
        String partNo = "T24-EXIST-" + UUID.randomUUID().toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO material_master (material_no, material_type, created_at, updated_at) " +
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

    /**
     * 仅计数 material_master 中 config_fingerprint IS NOT NULL 的行。
     * V6 单写后，选配料号的身份表从 mat_part 切换为 material_master；
     * config_fingerprint IS NOT NULL 仍是区分「选配自定义料号」与「历史导入料号」的标志。
     */
    @SuppressWarnings("unchecked")
    long countConfiguredMatPart() {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM material_master WHERE config_fingerprint IS NOT NULL")
            .getSingleResult()).longValue();
    }

    /**
     * 选配 Plan 3b (R1) 后 config_fingerprint 恒为 NULL，material_master 总行数(不分 fingerprint)
     * 才能反映「本次新增了几行」。用于 custom/composite 新建场景的 delta 断言。
     */
    @SuppressWarnings("unchecked")
    long countAllMatPart() {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM material_master")
            .getSingleResult()).longValue();
    }

    /** 按料号存在性判断 material_master 行是否已建（R1 后不能再靠 fingerprint IS NOT NULL 判定新建行）。 */
    @SuppressWarnings("unchecked")
    boolean matPartExists(String materialNo) {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM material_master WHERE material_no = :mn")
            .setParameter("mn", materialNo)
            .getSingleResult()).longValue() > 0;
    }

    /** 读取指定料号的 config_fingerprint（用于 R1 断言：选配落库恒为 NULL）。 */
    @SuppressWarnings("unchecked")
    Object matPartFingerprint(String materialNo) {
        return em.createNativeQuery(
                "SELECT config_fingerprint FROM material_master WHERE material_no = :mn")
            .setParameter("mn", materialNo)
            .getSingleResult();
    }

    /**
     * 计数 material_bom_item 中 characteristic = 'ASSEMBLY' 的行。
     * V6 单写后，COMPOSITE 组合产品的子件 BOM 写到 material_bom_item(characteristic='ASSEMBLY')，
     * 替代原 mat_bom(bom_type='ASSEMBLY')。
     */
    @SuppressWarnings("unchecked")
    long countMatBomAssembly() {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM material_bom_item WHERE characteristic = 'ASSEMBLY'")
            .getSingleResult()).longValue();
    }

    /**
     * 计数 capacity 中 resource_group_no = 'QUOTE_ASSEMBLY' 的行。
     * V6 单写后，组合工艺写到 capacity(resource_group_no='QUOTE_ASSEMBLY')，
     * 替代原 mat_composite_process。
     */
    @SuppressWarnings("unchecked")
    long countMatCompositeProcess() {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM capacity WHERE resource_group_no = 'QUOTE_ASSEMBLY'")
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
     * configured material_master 行 (countConfiguredMatPart 不变)。
     * V6 单写后：存在性校验读 material_master.material_no，
     * 夹具改为向 material_master 插入已知料号（无 config_fingerprint，模拟历史导入）。
     */
    @Test
    @TestTransaction
    void existing_returnsLineItem_noNewMatPart() {
        UUID quotationId = seedQuotationId();
        String knownPn = seedExistingMatPart(); // 向 material_master 插入已知料号

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
            "existing 路径不应新增 configured material_master 行");
        assertEquals(knownPn, resp.lineItems.get(0).get("productPartNo"));
    }

    // ── case 2: custom 未命中(新建) ──────────────────────────────────────────

    /**
     * case 2: custom 全新配置 → 新建 material_master (料号身份行) + element_bom_item 元素行。
     * V6 单写后：料号身份写 material_master，元素配比写 element_bom_item，不再写 mat_part / mat_bom。
     * AgNi90 editable: Ag∈[85,95], Ni∈[5,15]。使用非默认比例确保销售指纹唯一（不复用历史行）。
     *
     * <p>选配 Plan 3b (R1) 后：发号从 CFG- 前缀换成 {@code QuoteMaterialNoAllocator.mintAndRegister}
     * (格式 {@code {4位客户码}-{yyMM}{6位流水}})；落库的 config_fingerprint 恒为 NULL，
     * 「新增 1 行」改按料号存在性 + material_master 总行数 delta 验证（不再依赖 fingerprint 维度）。
     */
    @Test
    @TestTransaction
    void custom_uncached_createsMatPartAndBom() {
        UUID quotationId = seedQuotationId();

        ConfigureProductRequest req = simpleCustomReq("AgNi90", List.of(
            elem("Ag", "91.3"), elem("Ni", "8.7")
        ), new BigDecimal("12.5"));

        long beforeAll = countAllMatPart();
        ConfigureProductResponse resp = service.configure(quotationId, req, operatorId());

        assertEquals(1, resp.lineItems.size());
        assertFalse(resp.fingerprintMatched, "首次创建不应命中");
        assertEquals(beforeAll + 1, countAllMatPart(),
            "新增 1 行 material_master（R1 后不再靠 config_fingerprint 计数，改用总行数 delta）");

        String pn = (String) resp.lineItems.get(0).get("productPartNo");
        // 选配 Plan 3b (R1): 报价料号格式 = {4位客户码}-{yyMM}{6位流水}，不再是 CFG-AgNi- 前缀
        assertTrue(pn.matches("\\d{4}-\\d{6,}"), "应是销售发号格式 NNNN-YYMMNNNNNN，实际: " + pn);
        assertTrue(matPartExists(pn), "custom 未命中应新建 material_master 行: " + pn);
        assertNull(matPartFingerprint(pn), "R1: 选配落库 config_fingerprint 应为 NULL");
    }

    // ── case 3: custom 命中复用 ───────────────────────────────────────────────

    /**
     * case 3: 同一事务内先建，再以完全相同的元素配置再次请求 → 命中指纹复用，
     * countConfiguredMatPart 不再增加；unitWeightGrams 不参与指纹。
     * V6 单写后：指纹复用读 material_master.config_fingerprint，复用时不新增 material_master 行。
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

        assertEquals(before, countConfiguredMatPart(),
            "命中指纹不应新增 configured material_master 行");
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
     * → 3 个新 configured material_master (父+2子)、2 条 material_bom_item(ASSEMBLY) 行、
     *   1 条 capacity(QUOTE_ASSEMBLY) 行。
     * V6 单写后：料号身份写 material_master；ASSEMBLY 子件 BOM 写 material_bom_item(characteristic='ASSEMBLY')；
     * 组合工艺写 capacity(resource_group_no='QUOTE_ASSEMBLY')。
     * 注意：必须选用两个在测试环境中均不存在销售指纹(同客户维度)的 recipe+比例组合，确保两子件均全新创建。
     *   p1 = AgNi90(Ag=93.7, Ni=6.3) — 可编辑，选非默认比例
     *   p2 = AgNi95(Ag=96.0, Ni=4.0) — 可编辑(Ag∈[90,98], Ni∈[2,10])，DB 无 AgNi95 配置行
     * 原 AgCu90 全元素锁定，指纹唯一且 DB 已有历史行，会命中复用导致 p2 不新建，故改用 AgNi95。
     *
     * <p>选配 Plan 3b (R1) 后：落库的 config_fingerprint 恒为 NULL，「3 个新建」改按
     * material_master 总行数 delta + 逐个料号存在性验证（不再依赖 fingerprint 维度）。
     */
    @Test
    @TestTransaction
    void composite_allNew_buildsParentAndChildrenAndAssemblyBom() {
        UUID quotationId = seedQuotationId();

        PartRequest p1 = makeCustomPart("AgNi90", List.of(
            elem("Ag", "93.7"), elem("Ni", "6.3")), new BigDecimal("10.0"));
        p1.name = "配件1";

        // AgNi95: Ag∈[90,98], Ni∈[2,10]，全部可编辑，DB 无历史配置行，确保 p2 全新
        PartRequest p2 = makeCustomPart("AgNi95", List.of(
            elem("Ag", "96.0"), elem("Ni", "4.0")), new BigDecimal("11.0"));
        p2.name = "配件2";

        ConfigureProductRequest req = new ConfigureProductRequest();
        req.productType = "COMPOSITE";
        req.parts = List.of(p1, p2);

        CompositeProcessRequest cp = new CompositeProcessRequest();
        cp.defCode = "RIVET";
        cp.participatingPartIndexes = List.of(0, 1);
        cp.params = Map.of("pressure", 5.0, "height", 3.2);
        req.compositeProcesses = List.of(cp);

        long beforeMp  = countAllMatPart();
        long beforeAsm = countMatBomAssembly();
        long beforeCp  = countMatCompositeProcess();

        ConfigureProductResponse resp = service.configure(quotationId, req, operatorId());

        assertEquals(3, resp.lineItems.size(), "1 父 + 2 子 line_items");
        assertEquals(beforeMp  + 3, countAllMatPart(),
            "3 行新 material_master (父+2子；R1 后按总行数 delta 判断，不再靠 config_fingerprint)");
        for (Map<String, Object> li : resp.lineItems) {
            String pn = (String) li.get("productPartNo");
            assertTrue(matPartExists(pn), "line item 料号应已建 material_master 行: " + pn);
            assertNull(matPartFingerprint(pn), "R1: 选配落库 config_fingerprint 应为 NULL: " + pn);
        }
        assertEquals(beforeAsm + 2, countMatBomAssembly(),
            "2 行 material_bom_item(characteristic='ASSEMBLY') 子件清单");
        assertEquals(beforeCp  + 1, countMatCompositeProcess(),
            "1 行 capacity(resource_group_no='QUOTE_ASSEMBLY') 组合工艺");
    }

    // ── case 7: 组合产品子复用 ───────────────────────────────────────────────

    /**
     * case 7: 同事务内先独立建两个配件，再以相同配置组合
     * → 子配件命中销售侧客户维度指纹复用，仅新建父 material_master；reusedHfPartNos 含两个子料号。
     * V6 单写后：子件复用时 material_master 不新增；仅 COMPOSITE 父级新建 1 行 material_master。
     *
     * <p>选配 Plan 3b (R1) 后：落库的 config_fingerprint 恒为 NULL，「仅 1 个新建」改按
     * material_master 总行数 delta 验证（不再依赖 fingerprint 维度）。
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

        long beforeMp = countAllMatPart();
        ConfigureProductResponse resp = service.configure(quotationId, req, operatorId());

        assertEquals(beforeMp + 1, countAllMatPart(),
            "子配件复用时仅 1 个新 material_master (父级；R1 后按总行数 delta 判断)");
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
