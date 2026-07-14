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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0712 B2 — 选配落库改造（等价导入落库）DB 断言自测。
 *
 * <p>覆盖 backtask B2.5 严格验收的六处齐全 / 渲染基线(AP-53) / 幂等 / Σqty 判定 / 单去重子件：
 * <ul>
 *   <li>SIMPLE：{@code material_master}/{@code material_bom}(头)/{@code material_bom_item}/
 *       {@code element_bom}(头)/{@code element_bom_item}/{@code unit_price}(PROCESS/自制加工费) 六处齐全，
 *       且 {@code v_composite_child_materials}/{@code v_composite_child_elements}（选配-材质/选配-元素含量
 *       渲染基线的物理 PG 视图，见 V322/V246）仍能按 hf_part_no 命中正确行。</li>
 *   <li>COMPOSITE 单行 qty=2（D12/D17）：Σqty 兜底裁决为 COMPOSITE、1 个去重子件
 *       composition_qty=2（非展开 2 行）、父 material_master(COMPOSITE) + capacity 行、
 *       {@code sel_part_signature.config_signature_text} 含 {@code COMBO=<childPn>:2}。</li>
 *   <li>幂等：同客户同配置重复提交，六表均不重复累加，{@code sel_part_signature} 不新增。</li>
 * </ul>
 *
 * <p>本类与 {@link ConfigureProductServiceTest} 用同款夹具风格（各测试方法自建 customer+quotation，
 * {@code @TestTransaction} 结束自动回滚，不污染共享 DB）。
 */
@QuarkusTest
class ConfigureProductServiceB2LedgerTest {

    @Inject
    ConfigureProductService service;

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void seedDemoMaterials() {
        DemoMaterialRecipeFixture.ensureSeeded(em);
    }

    // ── 夹具 helper（同 ConfigureProductServiceTest 口径，本类独立持有 customerCode 供 SQL 断言用） ──

    record SeededQuotation(UUID quotationId, String customerCode) {}

    @SuppressWarnings("unchecked")
    SeededQuotation seedQuotation() {
        List<Object> uRows = em.createNativeQuery(
                "SELECT id FROM \"user\" WHERE username = 'admin' LIMIT 1")
            .getResultList();
        if (uRows.isEmpty()) {
            throw new IllegalStateException("admin user not found — V1 migration must have run");
        }
        UUID adminId = UUID.fromString(uRows.get(0).toString());

        UUID customerId = UUID.randomUUID();
        String custCode = "B2L-" + customerId.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO customer (id, name, code, level, status, created_at, updated_at) " +
                "VALUES (:id, 'B2 Ledger Test Customer', :code, 'STANDARD', 'ACTIVE', NOW(), NOW())")
            .setParameter("id", customerId)
            .setParameter("code", custCode)
            .executeUpdate();

        UUID quotationId = UUID.randomUUID();
        String qNo = "QT-B2L-" + quotationId.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO quotation " +
                "(id, quotation_number, customer_id, name, sales_rep_id, status, created_at, updated_at) " +
                "VALUES (:id, :qno, :cid, 'B2 Ledger Test Quotation', :uid, 'DRAFT', NOW(), NOW())")
            .setParameter("id", quotationId)
            .setParameter("qno", qNo)
            .setParameter("cid", customerId)
            .setParameter("uid", adminId)
            .executeUpdate();

        return new SeededQuotation(quotationId, custCode);
    }

    /** 电镀（表面处理类），V4 seed 持久存在，供 processIds 测试用。 */
    @SuppressWarnings("unchecked")
    UUID seedProcessId() {
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM process WHERE code = 'MRO-LP-0001' LIMIT 1")
            .getResultList();
        if (rows.isEmpty()) throw new IllegalStateException("process MRO-LP-0001 not found — V4 migration must have run");
        return UUID.fromString(rows.get(0).toString());
    }

    ElementOverride elem(String code, String pct) {
        return new ElementOverride(code, new BigDecimal(pct));
    }

    PartRequest makeCustomPart(String recipeCode, List<ElementOverride> elems, BigDecimal weight, List<UUID> processIds) {
        PartRequest p = new PartRequest();
        p.partMode = "custom";
        p.recipeCode = recipeCode;
        p.elements = elems;
        p.processIds = processIds != null ? processIds : List.of();
        p.unitWeightGrams = weight;
        p.name = "Test";
        return p;
    }

    UUID operatorId() { return UUID.randomUUID(); }

    // ── SQL 断言 helper ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    long count(String sql, Map<String, Object> params) {
        var q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);
        return ((Number) q.getSingleResult()).longValue();
    }

    long materialBomHeaderCount(String customerNo, String materialNo) {
        return count(
            "SELECT COUNT(*) FROM material_bom WHERE system_type='QUOTE' AND customer_no=:cn " +
            "AND material_no=:mn AND bom_type='MATERIAL' AND characteristic IS NULL AND is_current=true",
            Map.of("cn", customerNo, "mn", materialNo));
    }

    long materialBomItemSelfRowCount(String customerNo, String materialNo) {
        return count(
            "SELECT COUNT(*) FROM material_bom_item WHERE system_type='QUOTE' AND customer_no=:cn " +
            "AND material_no=:mn AND characteristic IS NULL AND is_current=true",
            Map.of("cn", customerNo, "mn", materialNo));
    }

    long elementBomHeaderCount(String customerNo, String materialNo, String materialPartNo) {
        return count(
            "SELECT COUNT(*) FROM element_bom WHERE system_type='QUOTE' AND customer_no=:cn " +
            "AND material_no=:mn AND material_part_no=:mpn AND bom_type='MATERIAL' AND is_current=true",
            Map.of("cn", customerNo, "mn", materialNo, "mpn", materialPartNo));
    }

    long elementBomItemCount(String customerNo, String materialNo, String materialPartNo) {
        return count(
            "SELECT COUNT(*) FROM element_bom_item WHERE system_type='QUOTE' AND customer_no=:cn " +
            "AND material_no=:mn AND material_part_no=:mpn AND is_current=true",
            Map.of("cn", customerNo, "mn", materialNo, "mpn", materialPartNo));
    }

    long unitPriceProcessCount(String customerNo, String materialNo) {
        return count(
            "SELECT COUNT(*) FROM unit_price WHERE system_type='QUOTE' AND price_type='PROCESS' " +
            "AND cost_type='自制加工费' AND customer_no=:cn AND code=:mn AND finished_material_no=:mn " +
            "AND is_current=true",
            Map.of("cn", customerNo, "mn", materialNo));
    }

    long materialMasterCount(String materialNo, String materialType) {
        return count(
            "SELECT COUNT(*) FROM material_master WHERE material_no=:mn AND material_type=:mt",
            Map.of("mn", materialNo, "mt", materialType));
    }

    /** 渲染基线(AP-53): v_composite_child_materials（选配-材质 mirror 的物理视图，V322 终态）。 */
    @SuppressWarnings("unchecked")
    List<Object[]> queryChildMaterialsView(String hfPartNo) {
        return em.createNativeQuery(
                "SELECT child_hf_part_no, material_name FROM v_composite_child_materials WHERE hf_part_no = :p")
            .setParameter("p", hfPartNo).getResultList();
    }

    /** 渲染基线(AP-53): v_composite_child_elements（选配-元素含量 mirror 的物理视图，V246 终态）。 */
    @SuppressWarnings("unchecked")
    List<Object[]> queryChildElementsView(String hfPartNo) {
        return em.createNativeQuery(
                "SELECT element_name, composition_pct FROM v_composite_child_elements WHERE hf_part_no = :p ORDER BY seq_no")
            .setParameter("p", hfPartNo).getResultList();
    }

    @SuppressWarnings("unchecked")
    String signatureTextFor(String customerNo, String quotePartNo) {
        List<Object> r = em.createNativeQuery(
                "SELECT config_signature_text FROM sel_part_signature WHERE customer_no=:cn AND quote_part_no=:p")
            .setParameter("cn", customerNo).setParameter("p", quotePartNo).getResultList();
        return r.isEmpty() ? null : (String) r.get(0);
    }

    @SuppressWarnings("unchecked")
    long signatureCountFor(String customerNo) {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM sel_part_signature WHERE customer_no=:cn")
            .setParameter("cn", customerNo).getSingleResult()).longValue();
    }

    // ── SIMPLE：六处齐全 + 渲染基线 ──────────────────────────────────────────

    @Test
    @TestTransaction
    void simple_sixTablesComplete_andMirrorViewsRenderCorrectly() {
        SeededQuotation sq = seedQuotation();

        ConfigureProductRequest req = new ConfigureProductRequest();
        req.productType = "SIMPLE";
        req.parts = List.of(makeCustomPart("AgNi90",
            List.of(elem("Ag", "91.1"), elem("Ni", "8.9")), new BigDecimal("12.5"), List.of(seedProcessId())));

        ConfigureProductResponse resp = service.configure(sq.quotationId(), req, operatorId());
        assertEquals("SIMPLE", resp.productType);
        String pn = (String) resp.lineItems.get(0).get("productPartNo");

        // ① material_master
        assertEquals(1, materialMasterCount(pn, "AgNi"), "① material_master 身份行");

        // ② material_bom(头) + material_bom_item(子)
        assertEquals(1, materialBomHeaderCount(sq.customerCode(), pn), "② material_bom 头表行");
        assertEquals(1, materialBomItemSelfRowCount(sq.customerCode(), pn), "② material_bom_item 自指子行");
        @SuppressWarnings("unchecked")
        List<Object[]> bomItemRows = em.createNativeQuery(
                "SELECT seq_no, component_no, component_usage_type FROM material_bom_item " +
                "WHERE system_type='QUOTE' AND customer_no=:cn AND material_no=:mn " +
                "AND characteristic IS NULL AND is_current=true")
            .setParameter("cn", sq.customerCode()).setParameter("mn", pn).getResultList();
        assertEquals(1, bomItemRows.size());
        Object[] bomRow = bomItemRows.get(0);
        assertEquals(1, ((Number) bomRow[0]).intValue(), "seq_no=1");
        assertEquals(pn, bomRow[1], "component_no 自指=partNo");
        assertEquals("AgNi", bomRow[2], "component_usage_type=recipe.symbol");

        // ③ element_bom(头) + element_bom_item(子)
        assertEquals(1, elementBomHeaderCount(sq.customerCode(), pn, "AgNi90"), "③ element_bom 头表行");
        assertEquals(2, elementBomItemCount(sq.customerCode(), pn, "AgNi90"), "③ element_bom_item 2 个元素");

        // ④ unit_price(PROCESS/自制加工费)
        assertEquals(1, unitPriceProcessCount(sq.customerCode(), pn), "④ unit_price PROCESS 行");

        // ⑤+⑥ 渲染基线(AP-53)：两个镜像视图对应的物理 PG 视图仍正确渲染
        List<Object[]> matView = queryChildMaterialsView(pn);
        assertEquals(1, matView.size(), "v_composite_child_materials 应返回本料号自身 1 行: " + matView);
        assertEquals(pn, matView.get(0)[0], "child_hf_part_no 自指");
        assertEquals("AgNi", matView.get(0)[1], "material_name 列取 component_usage_type=recipe.symbol");

        List<Object[]> eleView = queryChildElementsView(pn);
        assertEquals(2, eleView.size(), "v_composite_child_elements 应返回 2 个元素行: " + eleView);
        assertTrue(eleView.stream().anyMatch(r -> "Ag".equals(r[0]) && new BigDecimal("91.1").compareTo((BigDecimal) r[1]) == 0));
        assertTrue(eleView.stream().anyMatch(r -> "Ni".equals(r[0]) && new BigDecimal("8.9").compareTo((BigDecimal) r[1]) == 0));
    }

    // ── 幂等：同客户同配置重复提交，六表不重复累加 ────────────────────────────

    @Test
    @TestTransaction
    void simple_resubmitSameConfig_idempotent_noDuplicateLedgerRows() {
        SeededQuotation sq = seedQuotation();
        UUID processId = seedProcessId();

        ConfigureProductRequest req1 = new ConfigureProductRequest();
        req1.productType = "SIMPLE";
        req1.parts = List.of(makeCustomPart("AgNi95",
            List.of(elem("Ag", "93.0"), elem("Ni", "7.0")), new BigDecimal("9.0"), List.of(processId)));
        ConfigureProductResponse r1 = service.configure(sq.quotationId(), req1, operatorId());
        String pn1 = (String) r1.lineItems.get(0).get("productPartNo");
        assertFalse(r1.fingerprintMatched);

        long sigBefore = signatureCountFor(sq.customerCode());
        long bomHeaderBefore = materialBomHeaderCount(sq.customerCode(), pn1);
        long bomItemBefore = materialBomItemSelfRowCount(sq.customerCode(), pn1);
        long eleHeaderBefore = elementBomHeaderCount(sq.customerCode(), pn1, "AgNi95");
        long eleItemBefore = elementBomItemCount(sq.customerCode(), pn1, "AgNi95");
        long priceBefore = unitPriceProcessCount(sq.customerCode(), pn1);

        // 同配置（元素含量完全一致，weight 不同——weight 不参与指纹）二次提交
        ConfigureProductRequest req2 = new ConfigureProductRequest();
        req2.productType = "SIMPLE";
        req2.parts = List.of(makeCustomPart("AgNi95",
            List.of(elem("Ag", "93.0"), elem("Ni", "7.0")), new BigDecimal("99.0"), List.of(processId)));
        ConfigureProductResponse r2 = service.configure(sq.quotationId(), req2, operatorId());
        String pn2 = (String) r2.lineItems.get(0).get("productPartNo");

        assertEquals(pn1, pn2, "同配置应复用同一报价料号");
        assertTrue(r2.fingerprintMatched);
        assertEquals(sigBefore, signatureCountFor(sq.customerCode()), "sel_part_signature 不应新增");
        assertEquals(bomHeaderBefore, materialBomHeaderCount(sq.customerCode(), pn1), "material_bom 头表不应重复累加");
        assertEquals(bomItemBefore, materialBomItemSelfRowCount(sq.customerCode(), pn1), "material_bom_item 不应重复累加");
        assertEquals(eleHeaderBefore, elementBomHeaderCount(sq.customerCode(), pn1, "AgNi95"), "element_bom 头表不应重复累加");
        assertEquals(eleItemBefore, elementBomItemCount(sq.customerCode(), pn1, "AgNi95"), "element_bom_item 不应重复累加");
        assertEquals(priceBefore, unitPriceProcessCount(sq.customerCode(), pn1), "unit_price 不应重复累加");
    }

    // ── COMPOSITE 单行 qty>=2（D12/D17）：Σqty 判定 + 1 个去重子件 composition_qty=qty ──

    @Test
    @TestTransaction
    void singlePartQty2_becomesComposite_oneDedupChild_compositionQtyEqualsQty() {
        SeededQuotation sq = seedQuotation();

        PartRequest p1 = makeCustomPart("AgCu85",
            List.of(elem("Ag", "85.0"), elem("Cu", "15.0")), new BigDecimal("5.0"), null);
        p1.quantity = 2;  // D12/D17: 单行 qty>=2

        ConfigureProductRequest req = new ConfigureProductRequest();
        req.productType = "SIMPLE";  // 前端可能仍声明 SIMPLE，后端须按 Σqty 兜底裁决为 COMPOSITE
        req.parts = List.of(p1);

        CompositeProcessRequest cp = new CompositeProcessRequest();
        cp.defCode = "RIVET";
        cp.participatingPartIndexes = List.of(0);  // 单去重子件，放开后允许
        cp.params = Map.of();
        req.compositeProcesses = List.of(cp);

        ConfigureProductResponse resp = service.configure(sq.quotationId(), req, operatorId());

        assertEquals("COMPOSITE", resp.productType, "Σqty=2 应裁决为 COMPOSITE（不盲信前端 SIMPLE 声明）");
        assertEquals(2, resp.lineItems.size(), "1 父 + 1 子 line_items");

        String parentPn = (String) resp.lineItems.get(0).get("productPartNo");
        assertEquals("COMPOSITE", resp.lineItems.get(0).get("compositeType"));
        String childPn = (String) resp.lineItems.get(1).get("productPartNo");

        // 父 material_master(COMPOSITE)
        assertEquals(1, materialMasterCount(parentPn, "COMPOSITE"), "父料号 material_type=COMPOSITE");

        // capacity 组装加工费行
        long capCount = count(
            "SELECT COUNT(*) FROM capacity WHERE system_type='QUOTE' AND material_no=:mn " +
            "AND resource_group_no='QUOTE_ASSEMBLY' AND is_current=true",
            Map.of("mn", parentPn));
        assertEquals(1, capCount, "capacity 组装加工费行");

        // material_bom_item(ASSEMBLY) 恰 1 行，composition_qty=2（非展开 2 行）
        @SuppressWarnings("unchecked")
        List<Object[]> asmRows = em.createNativeQuery(
                "SELECT component_no, composition_qty FROM material_bom_item " +
                "WHERE system_type='QUOTE' AND customer_no=:cn AND material_no=:mn " +
                "AND characteristic='ASSEMBLY' AND is_current=true")
            .setParameter("cn", sq.customerCode()).setParameter("mn", parentPn).getResultList();
        assertEquals(1, asmRows.size(), "去重子件恰 1 行，非展开成 2 行");
        assertEquals(childPn, asmRows.get(0)[0]);
        assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) asmRows.get(0)[1]), "composition_qty=qty=2");

        // 子件自身完整落库（同 SIMPLE 六处齐全的前 3 处，子件无独立 processIds 故不测 unit_price）
        assertEquals(1, materialMasterCount(childPn, "AgCu"), "子件 material_master");
        assertEquals(1, materialBomHeaderCount(sq.customerCode(), childPn), "子件 material_bom 头表");
        assertEquals(1, elementBomHeaderCount(sq.customerCode(), childPn, "AgCu85"), "子件 element_bom 头表");

        // 指纹 COMBO=<childPn>:2
        String sigText = signatureTextFor(sq.customerCode(), parentPn);
        assertNotNull(sigText);
        assertTrue(sigText.contains("COMBO=" + childPn + ":2"),
            "指纹应含 COMBO=" + childPn + ":2，实际: " + sigText);
    }
}
