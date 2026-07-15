package com.cpq.configure;

import com.cpq.configure.dto.*;
import com.cpq.configure.service.ConfigureProductService;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.entity.QuotationLineProcess;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
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

    /**
     * 电镀（表面处理类），process_master 现役工序，供 processNos 测试用。
     * task-0712 缺口1: 标识锚点已切 process_master.process_no（不再是 process(V4) 表的 UUID）。
     */
    @SuppressWarnings("unchecked")
    String seedProcessNo() {
        List<Object> rows = em.createNativeQuery(
                "SELECT process_no FROM process_master WHERE process_no = 'MRO-LP-0001' LIMIT 1")
            .getResultList();
        if (rows.isEmpty()) throw new IllegalStateException("process_master MRO-LP-0001 not found — V267 migration must have run");
        return rows.get(0).toString();
    }

    /**
     * 孤儿工序编号: process_master 存在但 process(V4) 表无对应 code(F2 实证, TP10/TP20)。
     * task-0712 缺口1 修复前, 这类工序在选配前端会被防御式禁选; 修复后应可正常选中落库。
     */
    @SuppressWarnings("unchecked")
    String seedOrphanProcessNo() {
        List<Object> rows = em.createNativeQuery(
                "SELECT process_no FROM process_master WHERE process_no = 'TP10' " +
                "AND NOT EXISTS (SELECT 1 FROM process p WHERE p.code = process_master.process_no) LIMIT 1")
            .getResultList();
        if (rows.isEmpty()) throw new IllegalStateException(
                "process_master 孤儿工序 TP10 不存在(或已被 process(V4) 收录) — 夹具前提不成立");
        return rows.get(0).toString();
    }

    ElementOverride elem(String code, String pct) {
        return new ElementOverride(code, new BigDecimal(pct));
    }

    PartRequest makeCustomPart(String recipeCode, List<ElementOverride> elems, BigDecimal weight, List<String> processNos) {
        PartRequest p = new PartRequest();
        p.partMode = "custom";
        p.recipeCode = recipeCode;
        p.elements = elems;
        p.processNos = processNos != null ? processNos : List.of();
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

    /** task-0712 缺口1: quotation_line_process 行(process_no + process_id), 按 line_item_id 查。 */
    @SuppressWarnings("unchecked")
    List<Object[]> quotationLineProcessRows(UUID lineItemId) {
        return em.createNativeQuery(
                "SELECT process_no, process_id FROM quotation_line_process WHERE line_item_id = :lid")
            .setParameter("lid", lineItemId).getResultList();
    }

    /** task-0712 缺口1: unit_price PROCESS 行的 operation_no 集合(校验 = 选中的 process_no)。 */
    @SuppressWarnings("unchecked")
    List<String> unitPriceOperationNos(String customerNo, String materialNo) {
        List<Object> rows = em.createNativeQuery(
                "SELECT operation_no FROM unit_price WHERE system_type='QUOTE' AND price_type='PROCESS' " +
                "AND cost_type='自制加工费' AND customer_no=:cn AND code=:mn AND finished_material_no=:mn " +
                "AND is_current=true ORDER BY seq_no")
            .setParameter("cn", customerNo).setParameter("mn", materialNo).getResultList();
        return rows.stream().map(Object::toString).collect(java.util.stream.Collectors.toList());
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
            List.of(elem("Ag", "91.1"), elem("Ni", "8.9")), new BigDecimal("12.5"), List.of(seedProcessNo())));

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
        String processNo = seedProcessNo();

        ConfigureProductRequest req1 = new ConfigureProductRequest();
        req1.productType = "SIMPLE";
        req1.parts = List.of(makeCustomPart("AgNi95",
            List.of(elem("Ag", "93.0"), elem("Ni", "7.0")), new BigDecimal("9.0"), List.of(processNo)));
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
            List.of(elem("Ag", "93.0"), elem("Ni", "7.0")), new BigDecimal("99.0"), List.of(processNo)));
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
        // B6（架构决策 2-2A）: defCode = process_master.process_no（ASSEMBLY「总装配」），
        // 不再是 composite_process_def.code。
        cp.defCode = "MRO-AS-0001";
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

        // B6（架构决策 2-2A）五处标识一致（后端四处）: capacity.process_no 必须 = 选中的
        // process_master.process_no（非旧 composite_process_def.code），process_name 读 process_master。
        @SuppressWarnings("unchecked")
        List<Object[]> capRow = em.createNativeQuery(
                "SELECT process_no, process_name, currency FROM capacity " +
                "WHERE system_type='QUOTE' AND material_no=:mn " +
                "AND resource_group_no='QUOTE_ASSEMBLY' AND is_current=true")
            .setParameter("mn", parentPn).getResultList();
        assertEquals(1, capRow.size());
        assertEquals("MRO-AS-0001", capRow.get(0)[0], "capacity.process_no 应 = 选中的 process_master.process_no");
        assertEquals("总装配", capRow.get(0)[1], "capacity.process_name 应读自 process_master（不再读 composite_process_def）");
        assertEquals("CNY", capRow.get(0)[2], "process_master ASSEMBLY 现网 currency 空 → 落库兜底 CNY");

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

        // 指纹 COMBO=<childPn>:2 + CPROC=<process_no>（B6: CPROC token 值 = process_no）
        String sigText = signatureTextFor(sq.customerCode(), parentPn);
        assertNotNull(sigText);
        assertTrue(sigText.contains("COMBO=" + childPn + ":2"),
            "指纹应含 COMBO=" + childPn + ":2，实际: " + sigText);
        assertTrue(sigText.contains("CPROC=MRO-AS-0001"),
            "B6: 指纹 CPROC token 应为 process_master.process_no，实际: " + sigText);

        // quotation_line_composite_process.def_code（B6 后端第四处锚点）应与 capacity.process_no /
        // 指纹 CPROC 同一值 = process_master.process_no（五处一致，AP-44 精神）。
        UUID parentLineItemId = (UUID) resp.lineItems.get(0).get("id");
        String qlcpDefCode = (String) em.createNativeQuery(
                "SELECT def_code FROM quotation_line_composite_process WHERE line_item_id=:lid")
            .setParameter("lid", parentLineItemId).getSingleResult();
        assertEquals("MRO-AS-0001", qlcpDefCode,
            "quotation_line_composite_process.def_code 应 = process_master.process_no（五处一致）");
    }

    // ── task-0712 缺口1: 工序 id 契约修复(方案A) — process_no 全链贯通 ─────────────

    /**
     * SIMPLE 选含孤儿工序 TP10（process_master 存在但 process(V4) 无对应 code，F2 实证）：
     * 契约修复前，这类工序在选配前端会被防御式禁选（因 processIds 走 process(V4) UUID 无法解析）；
     * 修复后 processNos 直锚 process_master，应可正常选中落库，且写入 quotation_line_process.process_no
     * （process_id 恒 NULL，V336 加法式变体不再靠 process(V4) 转译）。
     */
    @Test
    @TestTransaction
    void simple_orphanProcessNoTP10_writesProcessNoWithNullProcessId_andUnitPriceOperationNo() {
        SeededQuotation sq = seedQuotation();
        String orphanNo = seedOrphanProcessNo();
        assertEquals("TP10", orphanNo);

        ConfigureProductRequest req = new ConfigureProductRequest();
        req.productType = "SIMPLE";
        req.parts = List.of(makeCustomPart("AgNi90",
            List.of(elem("Ag", "91.1"), elem("Ni", "8.9")), new BigDecimal("12.5"), List.of(orphanNo)));

        ConfigureProductResponse resp = service.configure(sq.quotationId(), req, operatorId());
        assertEquals("SIMPLE", resp.productType);
        UUID lineItemId = (UUID) resp.lineItems.get(0).get("id");
        String pn = (String) resp.lineItems.get(0).get("productPartNo");

        // ④ unit_price.operation_no = process_no（孤儿工序照常直取，不再经 process(V4) 查表）
        assertEquals(List.of("TP10"), unitPriceOperationNos(sq.customerCode(), pn),
            "unit_price.operation_no 应 = 选中的 process_no（含孤儿）");

        // quotation_line_process: process_no='TP10' 落值，process_id 为 NULL（新写路径不再填该列）
        List<Object[]> qlpRows = quotationLineProcessRows(lineItemId);
        assertEquals(1, qlpRows.size(), "quotation_line_process 恰 1 行");
        assertEquals("TP10", qlpRows.get(0)[0], "process_no 应落值为选中的孤儿工序编号");
        assertNull(qlpRows.get(0)[1], "process_id 应为 NULL（V336 加法式变体新写路径不填旧列）");

        // 指纹 PRC=TP10（孤儿工序参与销售指纹时同样直取 process_no，值域不变）
        String sigText = signatureTextFor(sq.customerCode(), pn);
        assertNotNull(sigText);
        assertTrue(sigText.contains("PRC=TP10"), "指纹 PRC token 应为选中的 process_no，实际: " + sigText);

        // ProcessDTO 回显(读回路径): 实体 → DTO 映射应透传 process_no（取代旧 processId UUID）
        QuotationLineProcess entity = QuotationLineProcess.find("lineItemId", lineItemId).firstResult();
        assertNotNull(entity);
        QuotationDTO.ProcessDTO dto = QuotationDTO.ProcessDTO.from(entity);
        assertEquals("TP10", dto.processNo, "ProcessDTO.processNo 应正确回显选中的工序编号");
    }

    /**
     * COMPOSITE 子件各自选独立工序：insertProcessUnitPriceV6 的 operation_no 应 = 子件各自选中的
     * process_no（与 SIMPLE 路径 insertProcessSimpleUnitPriceV6 同口径，均直取不再经 process(V4)）。
     */
    @Test
    @TestTransaction
    void composite_childProcessNos_unitPriceOperationNoEqualsProcessNo() {
        SeededQuotation sq = seedQuotation();
        String processNo = seedProcessNo();

        PartRequest p1 = makeCustomPart("AgCu85",
            List.of(elem("Ag", "85.0"), elem("Cu", "15.0")), new BigDecimal("5.0"), List.of(processNo));
        p1.quantity = 1;
        PartRequest p2 = makeCustomPart("AgNi90",
            List.of(elem("Ag", "91.1"), elem("Ni", "8.9")), new BigDecimal("3.0"), List.of(processNo));
        p2.quantity = 1;

        ConfigureProductRequest req = new ConfigureProductRequest();
        req.productType = "COMPOSITE";
        req.parts = List.of(p1, p2);

        CompositeProcessRequest cp = new CompositeProcessRequest();
        cp.defCode = "MRO-AS-0001";
        cp.participatingPartIndexes = List.of(0, 1);
        cp.params = Map.of();
        req.compositeProcesses = List.of(cp);

        ConfigureProductResponse resp = service.configure(sq.quotationId(), req, operatorId());
        assertEquals("COMPOSITE", resp.productType);
        assertEquals(3, resp.lineItems.size(), "1 父 + 2 子 line_items");

        String parentPn = (String) resp.lineItems.get(0).get("productPartNo");
        String child1Pn = (String) resp.lineItems.get(1).get("productPartNo");
        String child2Pn = (String) resp.lineItems.get(2).get("productPartNo");

        // insertProcessUnitPriceV6（COMPOSITE 专属：group key finished_material_no=父 COMBO 料号）
        // 的 operation_no 应 = 子件各自选中的 process_no。
        List<Object> parentLinkedOps1 = em.createNativeQuery(
                "SELECT operation_no FROM unit_price WHERE system_type='QUOTE' AND price_type='PROCESS' " +
                "AND cost_type='自制加工费' AND customer_no=:cn AND code=:code AND finished_material_no=:fmn " +
                "AND is_current=true ORDER BY seq_no")
            .setParameter("cn", sq.customerCode()).setParameter("code", child1Pn).setParameter("fmn", parentPn)
            .getResultList();
        assertEquals(List.of(processNo), parentLinkedOps1,
            "insertProcessUnitPriceV6: 子件1(父链接组) operation_no 应 = process_no");
        List<Object> parentLinkedOps2 = em.createNativeQuery(
                "SELECT operation_no FROM unit_price WHERE system_type='QUOTE' AND price_type='PROCESS' " +
                "AND cost_type='自制加工费' AND customer_no=:cn AND code=:code AND finished_material_no=:fmn " +
                "AND is_current=true ORDER BY seq_no")
            .setParameter("cn", sq.customerCode()).setParameter("code", child2Pn).setParameter("fmn", parentPn)
            .getResultList();
        assertEquals(List.of(processNo), parentLinkedOps2,
            "insertProcessUnitPriceV6: 子件2(父链接组) operation_no 应 = process_no");

        assertEquals(List.of(processNo), unitPriceOperationNos(sq.customerCode(), child1Pn),
            "子件1 unit_price.operation_no 应 = 选中的 process_no");
        assertEquals(List.of(processNo), unitPriceOperationNos(sq.customerCode(), child2Pn),
            "子件2 unit_price.operation_no 应 = 选中的 process_no");

        UUID child1LineItemId = (UUID) resp.lineItems.get(1).get("id");
        UUID child2LineItemId = (UUID) resp.lineItems.get(2).get("id");
        List<Object[]> child1Qlp = quotationLineProcessRows(child1LineItemId);
        List<Object[]> child2Qlp = quotationLineProcessRows(child2LineItemId);
        assertEquals(1, child1Qlp.size());
        assertEquals(processNo, child1Qlp.get(0)[0]);
        assertNull(child1Qlp.get(0)[1], "process_id 应为 NULL");
        assertEquals(1, child2Qlp.size());
        assertEquals(processNo, child2Qlp.get(0)[0]);
        assertNull(child2Qlp.get(0)[1], "process_id 应为 NULL");
    }

    /**
     * 幂等复用：同客户同配置（含工序 processNos）重复提交，quotation_line_process 不重复累加
     * （每次 configure 走 insertQuotationLineProcesses 先删后插，天然幂等；此处断言资产未累加）。
     */
    @Test
    @TestTransaction
    void simple_resubmitSameConfig_quotationLineProcessNotAccumulated() {
        SeededQuotation sq = seedQuotation();
        String processNo = seedProcessNo();

        ConfigureProductRequest req = new ConfigureProductRequest();
        req.productType = "SIMPLE";
        req.parts = List.of(makeCustomPart("AgNi95",
            List.of(elem("Ag", "93.0"), elem("Ni", "7.0")), new BigDecimal("9.0"), List.of(processNo)));
        ConfigureProductResponse r1 = service.configure(sq.quotationId(), req, operatorId());
        UUID lineItemId1 = (UUID) r1.lineItems.get(0).get("id");
        assertEquals(1, quotationLineProcessRows(lineItemId1).size());

        // 命中复用时 configure 不会再走 buildLineItems(仅返回已有 hfPartNo)，
        // 这里改用同 lineItemId 二次调 insertQuotationLineProcesses 的真实调用路径:
        // 直接验证同一 lineItemId 重新走一次 configure 的等价行为(covered by resolvePart 幂等)，
        // 断言表内该 lineItem 的工序行数仍恰为 1(先删后插不会累加)。
        ConfigureProductResponse r2 = service.configure(sq.quotationId(), req, operatorId());
        UUID lineItemId2 = (UUID) r2.lineItems.get(0).get("id");
        assertTrue(r2.fingerprintMatched);
        // 命中复用路径不会重建 line_items 表(buildLineItems 仍执行，为新报价行发新 id)，
        // 新行同样应恰好写 1 条 quotation_line_process。
        assertEquals(1, quotationLineProcessRows(lineItemId2).size(),
            "复用命中后新报价行 quotation_line_process 仍应恰 1 条，不因指纹复用而重复累加/残留");
    }

    /**
     * V336 迁移验证: quotation_line_process_process_no_fkey 应拒绝不存在于 process_master 的
     * process_no（DB 级最后一道防线, 兜底 Java 层 fail-fast 校验之外的直接 SQL 写入路径）。
     */
    @Test
    @TestTransaction
    void quotationLineProcess_fkRejectsNonexistentProcessNo() {
        SeededQuotation sq = seedQuotation();
        UUID lineItemId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item " +
                "(id, quotation_id, product_part_no_snapshot, composite_type, sort_order, created_at) " +
                "VALUES (:id, :q, 'FK-TEST-PN', 'SIMPLE', 0, NOW())")
            .setParameter("id", lineItemId)
            .setParameter("q", sq.quotationId())
            .executeUpdate();

        assertThrows(PersistenceException.class, () -> {
            em.createNativeQuery(
                    "INSERT INTO quotation_line_process (id, line_item_id, process_no) " +
                    "VALUES (gen_random_uuid(), :lid, :pn)")
                .setParameter("lid", lineItemId)
                // ≤20 字符(process_no 列宽), 确保触发的是 FK 违反而非 varchar 长度截断错误
                .setParameter("pn", "ZZ-NOTREAL01")
                .executeUpdate();
        }, "process_no 未命中 process_master 应被 FK 拒绝(quotation_line_process_process_no_fkey)");
    }

    /**
     * V336 迁移 backfill 验证: 存量 162 行(F5 实证)迁移后 process_no 应全部有值，无 NULL 残留。
     * 只读断言，不依赖本类其它测试写入的数据(@TestTransaction 保证不污染共享 DB)。
     */
    @Test
    @TestTransaction
    void migration_backfilledRows_haveNoNullProcessNo() {
        long nullCount = count(
            "SELECT COUNT(*) FROM quotation_line_process WHERE process_id IS NOT NULL AND process_no IS NULL",
            Map.of());
        assertEquals(0, nullCount,
            "V336 backfill 后, 存量含 process_id 的行不应再有 process_no 为 NULL 的残留");
    }
}
