package com.cpq.configure;

import com.cpq.configure.dto.ConfigureProductRequest;
import com.cpq.configure.dto.ConfigureProductResponse;
import com.cpq.configure.dto.ElementOverride;
import com.cpq.configure.dto.MaterialSelection;
import com.cpq.configure.dto.PartRequest;
import com.cpq.configure.service.ConfigureProductService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260902 · <b>B-13 回归保障</b>（不改功能，只加测试）。
 *
 * <p>覆盖 backtask B-13 的三项 + 本次改动最容易静默回归的两处：
 * <ol>
 *   <li><b>AC-13</b>：单材质（占比 100）落库结果与改造前逐字段等价 —— 这是「不回归」的判据；</li>
 *   <li><b>AC-19</b>：工序换序<b>复用同一料号</b>，且 {@code unit_price.seq_no} 保持<b>第一次</b>的顺序；</li>
 *   <li><b>AC-20</b>：工序重复次数不同（焊两次 ≠ 焊一次）<b>必铸新号</b>
 *       —— 防「顺手在 sorted() 旁加 distinct()」的守卫；</li>
 *   <li><b>B-9 归位后的渲染回归</b>：{@code v_composite_child_materials.material_name}
 *       仍是材质名（{@code AgNi}）而不是料号类型（{@code 零件}）；</li>
 *   <li><b>AC-3 / B-3 / B-4</b>：多材质落 N 行 + {@code material_ratio} + 元素按材质分 N 组。</li>
 * </ol>
 *
 * <h3>🚨 共享库纪律</h3>
 * <ul>
 *   <li>全部用例 {@code @TestTransaction}（跑完回滚），🚫 无提交式夹具；</li>
 *   <li>自建数据一律 {@code T260902-} 前缀，便于收尾污染核对；</li>
 *   <li>材质用 {@link DemoMaterialRecipeFixture} 已有的 demo 材质，🚫 不新建 {@code material_recipe}
 *       （新建会因该夹具是 {@code @BeforeEach @Transactional} 独立事务而<b>真的落库</b>）。</li>
 * </ul>
 */
@QuarkusTest
class ConfigureProductThreeLayerRegressionTest {

    @Inject
    ConfigureProductService service;

    @Inject
    EntityManager em;

    @BeforeEach
    @jakarta.transaction.Transactional
    void seedDemoMaterials() {
        DemoMaterialRecipeFixture.ensureSeeded(em);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    record Seeded(UUID quotationId, String customerCode) {}

    @SuppressWarnings("unchecked")
    Seeded seedQuotation() {
        Object admin = em.createNativeQuery("SELECT id FROM \"user\" WHERE username = 'admin' LIMIT 1")
            .getResultList().stream().findFirst().orElse(null);
        assertNotNull(admin, "前置：admin 用户应存在（V1 seed）");
        UUID customerId = UUID.randomUUID();
        String code = String.valueOf(1000 + Math.abs(customerId.hashCode() % 9000));
        em.createNativeQuery(
                "INSERT INTO customer (id, name, code, level, status, created_at, updated_at) " +
                "VALUES (:id, :nm, :code, 'STANDARD', 'ACTIVE', NOW(), NOW())")
            .setParameter("id", customerId)
            .setParameter("nm", "T260902-回归客户")
            .setParameter("code", code)
            .executeUpdate();
        UUID quotationId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, created_at, updated_at) " +
                "VALUES (:id, :qno, :cid, 'T260902-回归报价单', CAST(:uid AS uuid), 'DRAFT', NOW(), NOW())")
            .setParameter("id", quotationId)
            .setParameter("qno", "T260902-" + quotationId.toString().substring(0, 8))
            .setParameter("cid", customerId)
            .setParameter("uid", admin.toString())
            .executeUpdate();
        em.flush();
        return new Seeded(quotationId, code);
    }

    String productNo() { return "T260902-REG-" + UUID.randomUUID().toString().substring(0, 8); }

    ElementOverride el(String code, String pct) { return new ElementOverride(code, new BigDecimal(pct)); }

    MaterialSelection mat(String recipeCode, String ratio, List<ElementOverride> els) {
        return new MaterialSelection(recipeCode, null, new BigDecimal(ratio), els);
    }

    PartRequest newPart(String name, String spec, String dim, String weight,
                        List<MaterialSelection> materials, List<String> processNos) {
        PartRequest p = new PartRequest();
        p.name = name;
        p.partType = "PART";
        p.partMode = "new";
        p.spec = spec;
        p.dimension = dim;
        p.unitWeightGrams = new BigDecimal(weight);
        p.materials = materials;
        p.processNos = processNos;
        p.quantity = 1;
        return p;
    }

    ConfigureProductRequest req(PartRequest... parts) {
        ConfigureProductRequest r = new ConfigureProductRequest();
        r.customerProductNo = productNo();
        r.customerProductName = "T260902-回归产品";
        r.productType = "SIMPLE";
        r.parts = List.of(parts);
        return r;
    }

    String submit(UUID quotationId, ConfigureProductRequest r) {
        ConfigureProductResponse resp = service.configure(quotationId, r, null);
        return (String) resp.lineItems.get(0).get("productPartNo");
    }

    @SuppressWarnings("unchecked")
    List<Object[]> rows(String sql, Map<String, Object> params) {
        var q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);
        return q.getResultList();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ① AC-13：单材质（占比 100）与改造前等价 —— 不回归判据
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void ac13_singleMaterialRatio100_landsExactlyOneBomRow_equivalentToPreChange() {
        Seeded sq = seedQuotation();

        String pn = submit(sq.quotationId(), req(newPart("T260902-触点", "φ5", "5×3×2", "10",
            List.of(mat("AgNi90", "100", List.of(el("Ag", "90.0"), el("Ni", "10.0")))),
            List.of())));

        // material_bom_item：恰 1 行，material_ratio=100，characteristic=RECIPE
        List<Object[]> bom = rows(
            "SELECT seq_no, component_no, component_usage_type, material_ratio, characteristic " +
            "FROM material_bom_item WHERE system_type='QUOTE' AND customer_no=:cn AND material_no=:mn " +
            "  AND characteristic='RECIPE' AND is_current=true ORDER BY seq_no",
            Map.of("cn", sq.customerCode(), "mn", pn));
        System.out.println("[B-13①] material_bom_item(" + pn + ") = " + bom.stream()
            .map(r -> java.util.Arrays.toString(r)).toList());
        assertEquals(1, bom.size(), "AC-13：单材质应恰落 1 行（与改造前等价）");
        assertEquals(1, bom.get(0)[0]);
        assertEquals("AgNi90", bom.get(0)[1], "component_no=材质料号(recipe.code)");
        assertEquals("AgNi", bom.get(0)[2], "component_usage_type=材质名(recipe.symbol)");
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) bom.get(0)[3]), "material_ratio=100");
        assertEquals("RECIPE", bom.get(0)[4]);

        // material_master：B-9 归位 + B-14 扩列 + B-18 单材质仍留 recipe_id
        Object[] mm = (Object[]) em.createNativeQuery(
                "SELECT material_type, material_name, specification, dimension, unit_weight, " +
                "       (material_recipe_id IS NOT NULL) FROM material_master WHERE material_no=:p")
            .setParameter("p", pn).getSingleResult();
        System.out.println("[B-13①] material_master(" + pn + ") = " + java.util.Arrays.toString(mm));
        assertEquals("零件", mm[0], "B-9：material_type 归位为料号类型");
        assertEquals("T260902-触点", mm[1], "B-14/AC-3①：material_name 必须落库");
        assertEquals("φ5", mm[2], "B-14/AC-3①：specification 必须落库");
        assertEquals("5×3×2", mm[3], "B-14/AC-3①：dimension 必须落库");
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) mm[4]), "unit_weight=10");
        assertEquals(Boolean.TRUE, mm[5], "B-18：单材质时 material_recipe_id 保留（AC-13 等价性）");

        // element_bom_item：1 组，material_part_no = 材质料号
        List<Object[]> ele = rows(
            "SELECT material_part_no, component_no, content FROM element_bom_item " +
            "WHERE system_type='QUOTE' AND customer_no=:cn AND material_no=:mn AND is_current=true " +
            "ORDER BY material_part_no, seq_no",
            Map.of("cn", sq.customerCode(), "mn", pn));
        System.out.println("[B-13①] element_bom_item = " + ele.stream().map(java.util.Arrays::toString).toList());
        assertEquals(2, ele.size(), "单材质 2 个元素 = 2 行");
        assertTrue(ele.stream().allMatch(r -> "AgNi90".equals(r[0])), "元素行归属该材质");

        // 🚨 B-9 连带回归：v_composite_child_materials.material_name 必须仍是材质名，不是「零件」
        List<Object[]> view = rows(
            "SELECT child_hf_part_no, material_name FROM v_composite_child_materials WHERE hf_part_no=:p",
            Map.of("p", pn));
        System.out.println("[B-13① · B-9 渲染回归] v_composite_child_materials(" + pn + ") = "
            + view.stream().map(java.util.Arrays::toString).toList());
        assertEquals(1, view.size(), "视图应返本料号自身 1 行");
        assertEquals("AgNi", view.get(0)[1],
            "🚨 B-9 回归：material_name 的 COALESCE 第二兜底是 mm.material_type（现为「零件」）——"
            + " component_usage_type 一旦为空，页签材质名就会从 AgNi 变成「零件」。实际=" + view.get(0)[1]);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ② AC-3 / B-3 / B-4：多材质 N 行 + 占比 + 元素分 N 组（含 B-15 视图分组）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void ac3_multiMaterial_landsNRowsWithRatio_andElementsGroupedPerMaterial() {
        Seeded sq = seedQuotation();

        String pn = submit(sq.quotationId(), req(newPart("T260902-双材质", "φ8", "8×4×2", "10",
            List.of(mat("AgNi90", "70", List.of(el("Ag", "90.0"), el("Ni", "10.0"))),
                    mat("AgCu85", "30", List.of(el("Ag", "85.0"), el("Cu", "15.0")))),
            List.of())));

        List<Object[]> bom = rows(
            "SELECT seq_no, component_no, component_usage_type, material_ratio FROM material_bom_item " +
            "WHERE system_type='QUOTE' AND customer_no=:cn AND material_no=:mn AND characteristic='RECIPE' " +
            "  AND is_current=true ORDER BY seq_no",
            Map.of("cn", sq.customerCode(), "mn", pn));
        System.out.println("[B-13② B-3] material_bom_item = " + bom.stream().map(java.util.Arrays::toString).toList());
        assertEquals(2, bom.size(), "AC-3②：多材质应落 2 行");
        assertEquals("AgNi90", bom.get(0)[1]);
        assertEquals(0, new BigDecimal("70").compareTo((BigDecimal) bom.get(0)[3]), "material_ratio=70");
        assertEquals("AgCu85", bom.get(1)[1]);
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) bom.get(1)[3]), "material_ratio=30");

        // B-18：多材质时 material_recipe_id 写 NULL（材质权威改为 material_bom_item 的 N 行）
        // ⚠️ 这里不能用 getResultStream().findFirst() —— 单列查询返回的就是 null 值本身，
        //    Optional.findFirst() 碰到 null 元素会 NPE（测出来的是测试自己的 bug，不是产品的）。
        @SuppressWarnings("unchecked")
        List<Object> recipeIdRows = em.createNativeQuery(
                "SELECT material_recipe_id FROM material_master WHERE material_no=:p")
            .setParameter("p", pn).getResultList();
        assertEquals(1, recipeIdRows.size(), "料号应已落 material_master");
        System.out.println("[B-13② B-18] material_recipe_id = " + recipeIdRows.get(0));
        assertNull(recipeIdRows.get(0), "B-18：多材质时 material_master.material_recipe_id 应为 NULL");

        // B-4：element_bom_item 按材质分 2 组（material_part_no 各不同），且各组 seq_no 都从 1 起
        List<Object[]> ele = rows(
            "SELECT material_part_no, seq_no, component_no, content, characteristic FROM element_bom_item " +
            "WHERE system_type='QUOTE' AND customer_no=:cn AND material_no=:mn AND is_current=true " +
            "ORDER BY material_part_no, seq_no",
            Map.of("cn", sq.customerCode(), "mn", pn));
        System.out.println("[B-13② B-4] element_bom_item = " + ele.stream().map(java.util.Arrays::toString).toList());
        assertEquals(4, ele.size(), "2 材质 × 2 元素 = 4 行");
        assertEquals(2, ele.stream().map(r -> r[0]).distinct().count(), "AC-3③：应分成 2 组（material_part_no 各不同）");

        // 🚨 B-15：视图侧两组元素都必须出得来（原视图的 max(characteristic) 缺 material_part_no
        //    维度，某个材质单独升版时会把另一组整组静默吞掉）
        List<Object[]> view = rows(
            "SELECT material_part_no, element_name, composition_pct FROM v_composite_child_elements " +
            "WHERE hf_part_no=:p ORDER BY material_part_no, seq_no", Map.of("p", pn));
        System.out.println("[B-13② B-15] v_composite_child_elements = "
            + view.stream().map(java.util.Arrays::toString).toList());
        assertEquals(4, view.size(), "B-15：两组元素都应出现在视图里（一组都不许被吞）");
        assertEquals(2, view.stream().map(r -> r[0]).distinct().count(),
            "B-15：视图必须暴露 material_part_no 供前端按材质分组");

        // ── 🚨 B-15 的真正证伪：制造「两组版本号不齐」并确认视图不再吞组 ──
        //
        // ⚠️ 先说清楚一件事，免得后人误读：**走 service 提交是造不出这个偏斜的**。
        //    任何材质内容变化都会改变指纹 → 铸出新料号 → 写到另一个 material_no 上，
        //    原料号的两组永远同步升版。所以下面直接用 SQL 把其中一组的版本列推到 2001 ——
        //    这不是「造假」，而是复刻**真实存在的偏斜来源**：element_bom_item 的
        //    characteristic 是 VersionedV6Writer 的版本列、按 groupKey(含 material_part_no)
        //    **各自独立递增**，导入侧（Q04）与跨客户 backfill 都会让同一料号的不同材质组各走各的版本。
        //    评审 P0-5 说的「存量扫描 0 组」正是指：这是潜伏缺陷，本任务第一次给它通电。
        int bumped = em.createNativeQuery(
                "UPDATE element_bom_item SET characteristic = '2001' " +
                "WHERE system_type='QUOTE' AND customer_no=:cn AND material_no=:mn " +
                "  AND material_part_no='AgNi90' AND is_current=true")
            .setParameter("cn", sq.customerCode()).setParameter("mn", pn).executeUpdate();
        em.flush();
        em.clear();
        assertEquals(2, bumped, "构造：应把 AgNi90 那组的 2 行版本列推到 2001");

        List<Object[]> versions = rows(
            "SELECT material_part_no, string_agg(DISTINCT characteristic, ',') FROM element_bom_item " +
            "WHERE system_type='QUOTE' AND customer_no=:cn AND material_no=:mn AND is_current=true " +
            "GROUP BY material_part_no ORDER BY material_part_no",
            Map.of("cn", sq.customerCode(), "mn", pn));
        System.out.println("[B-13② B-15 证伪] 偏斜后各材质组的版本号 = "
            + versions.stream().map(java.util.Arrays::toString).toList());

        List<Object[]> skewed = rows(
            "SELECT material_part_no, element_name FROM v_composite_child_elements " +
            "WHERE hf_part_no=:p ORDER BY material_part_no, seq_no", Map.of("p", pn));
        System.out.println("[B-13② B-15 证伪] 偏斜后视图返回 = "
            + skewed.stream().map(java.util.Arrays::toString).toList());
        assertEquals(4, skewed.size(),
            "🚨 B-15：两组版本号不齐时**两组都必须还在**。修复前 max(characteristic) 跨组取到 2001，"
            + "AgCu85 那组（仍是 2000）会整组从页签消失且无任何报错。实际=" + skewed.size() + " 行");
        assertEquals(2, skewed.stream().map(r -> r[0]).distinct().count(),
            "B-15：两个材质组都应在视图里");

        // ── 还原实验（testing.md：首次 PASS 也可能是空验证）──
        // 把 V403 之前的谓词（max() 子查询**不带** material_part_no 维度）原样跑一遍同一批数据：
        // 若它同样返 4 行，说明本用例根本没验到任何东西；返 2 行才证明修复是必要的、且确实在起作用。
        List<Object[]> preFix = rows(
            "SELECT ebi.material_part_no, ebi.component_no FROM element_bom_item ebi " +
            "WHERE ebi.system_type='QUOTE' AND ebi.hf_part_no IS NOT NULL AND ebi.is_current = true " +
            "  AND ebi.hf_part_no = :p " +
            "  AND ebi.characteristic = (SELECT max(ebi2.characteristic) FROM element_bom_item ebi2 " +
            "        WHERE ebi2.system_type = ebi.system_type AND ebi2.customer_no = ebi.customer_no " +
            "          AND ebi2.material_no = ebi.material_no) " +
            "ORDER BY ebi.material_part_no, ebi.seq_no", Map.of("p", pn));
        System.out.println("[B-13② B-15 还原实验] V403 之前的谓词返回 = "
            + preFix.stream().map(java.util.Arrays::toString).toList());
        assertEquals(2, preFix.size(),
            "还原实验失败：旧谓词也返 4 行 ⇒ 本用例没验到东西（空验证）。"
            + "旧谓词应只返 2 行（AgCu85 那组被 max() 跨组吞掉）。实际=" + preFix.size());
        assertTrue(preFix.stream().allMatch(r -> "AgNi90".equals(r[0])),
            "还原实验：旧谓词只应剩下版本号最大的 AgNi90 那组");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ③ AC-19：工序换序 → 复用同一料号 + unit_price.seq_no 保持第一次的顺序
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void ac19_processReorder_reusesSamePartNo_andUnitPriceSeqKeepsFirstOrder() {
        Seeded sq = seedQuotation();
        assertProcessFixture();

        List<MaterialSelection> mats =
            List.of(mat("AgNi90", "100", List.of(el("Ag", "90.0"), el("Ni", "10.0"))));

        String pn1 = submit(sq.quotationId(), req(newPart("T260902-工序序", "φ5", "5×3×2", "10",
            mats, List.of("Z100", "Z101"))));
        List<Object[]> order1 = unitPriceOrder(sq.customerCode(), pn1);
        System.out.println("[B-13③ 第一次 Z100→Z101] unit_price = " + order1.stream().map(java.util.Arrays::toString).toList());
        assertEquals(List.of("Z100", "Z101"), order1.stream().map(r -> (String) r[0]).toList(),
            "AC-19③：seq_no 按 processNos 原始顺序（🚫 不排序）");

        // 第二次：工序换序（Z101 → Z100），其余完全一致
        ConfigureProductRequest r2 = req(newPart("T260902-工序序", "φ5", "5×3×2", "10",
            List.of(mat("AgNi90", "100", List.of(el("Ag", "90.0"), el("Ni", "10.0")))),
            List.of("Z101", "Z100")));
        ConfigureProductResponse resp2 = service.configure(sq.quotationId(), r2, null);
        String pn2 = (String) resp2.lineItems.get(0).get("productPartNo");

        assertEquals(pn1, pn2, "AC-19①：换个次序仍是同一个产品，应复用同一料号（A0 裁决：顺序不进指纹）");
        assertTrue(resp2.fingerprintMatched, "AC-19：第二次应标注命中复用");
        assertEquals(1, signatureCount(sq.customerCode(), pn1), "AC-19②：sel_part_signature 仍只有 1 条");

        List<Object[]> order2 = unitPriceOrder(sq.customerCode(), pn1);
        System.out.println("[B-13③ 第二次 Z101→Z100 后] unit_price = " + order2.stream().map(java.util.Arrays::toString).toList());
        assertEquals(List.of("Z100", "Z101"), order2.stream().map(r -> (String) r[0]).toList(),
            "AC-19③：seq_no 仍是**第一次**的顺序 —— 命中复用时 resolvePart 直接 return，第二次根本不写库");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ④ AC-20：工序重复次数不同必铸新号 —— 防「顺手 distinct()」
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void ac20_processRepeatedTwice_mintsNewPartNo_guardsAgainstDistinct() {
        Seeded sq = seedQuotation();
        assertProcessFixture();

        String pn1 = submit(sq.quotationId(), req(newPart("T260902-重复工序", "φ5", "5×3×2", "10",
            List.of(mat("AgNi90", "100", List.of(el("Ag", "90.0"), el("Ni", "10.0")))),
            List.of("Z100", "Z101"))));

        // 焊接两次：Z100 → Z101 → Z100
        ConfigureProductRequest r2 = req(newPart("T260902-重复工序", "φ5", "5×3×2", "10",
            List.of(mat("AgNi90", "100", List.of(el("Ag", "90.0"), el("Ni", "10.0")))),
            List.of("Z100", "Z101", "Z100")));
        ConfigureProductResponse resp2 = service.configure(sq.quotationId(), r2, null);
        String pn2 = (String) resp2.lineItems.get(0).get("productPartNo");

        System.out.println("[B-13④] pn1=" + pn1 + " pn2=" + pn2);
        assertNotEquals(pn1, pn2,
            "🚨 AC-20：sort() 不去重 ⇒「焊两次」与「焊一次」是不同料号。"
            + " 本条一旦红，八成是有人在 SalesFingerprintCalculator 的 sorted() 旁顺手加了 distinct()");
        assertFalse(resp2.fingerprintMatched, "AC-20：不应命中复用");

        // 直接对账指纹原文，让失败时能一眼看出是哪一侧被抹平了
        String t1 = signatureText(sq.customerCode(), pn1);
        String t2 = signatureText(sq.customerCode(), pn2);
        System.out.println("[B-13④] sig1=" + t1);
        System.out.println("[B-13④] sig2=" + t2);
        assertTrue(t1.contains("PRC=Z100,Z101"), "第一次 PRC 应为 Z100,Z101，实际=" + t1);
        assertTrue(t2.contains("PRC=Z100,Z100,Z101"),
            "第二次 PRC 应为 Z100,Z100,Z101（排序但不去重），实际=" + t2);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ⑤ AC-15a / AC-15b：占比合计判等必须定点（浮点实现会错误拒绝 AC-15b）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void ac15_ratioSumUsesBigDecimalCompareTo_notDouble() {
        Seeded sq = seedQuotation();

        // AC-15a：33.333333333333 ×2 + 33.333333333334 —— ⚠️ 浮点下**恰好也等于 100**，
        //         它拦不住浮点实现；留着是为了验 12 位小数能原样存进 numeric(24,12)。
        String pnA = submit(sq.quotationId(), req(newPart("T260902-15a", null, null, "10",
            List.of(mat("AgNi90", "33.333333333333", List.of(el("Ag", "90.0"), el("Ni", "10.0"))),
                    mat("AgCu85", "33.333333333333", List.of(el("Ag", "85.0"), el("Cu", "15.0"))),
                    mat("AgNi95", "33.333333333334", List.of(el("Ag", "95.0"), el("Ni", "5.0")))),
            List.of())));
        List<Object[]> ratiosA = rows(
            "SELECT component_no, material_ratio::text FROM material_bom_item " +
            "WHERE system_type='QUOTE' AND customer_no=:cn AND material_no=:mn AND characteristic='RECIPE' " +
            "  AND is_current=true ORDER BY seq_no",
            Map.of("cn", sq.customerCode(), "mn", pnA));
        System.out.println("[B-13⑤ AC-15a] material_ratio 实存 = " + ratiosA.stream().map(java.util.Arrays::toString).toList());
        assertEquals(3, ratiosA.size());
        assertTrue(((String) ratiosA.get(0)[1]).startsWith("33.333333333333"),
            "AC-15a：12 位小数必须原样存进 numeric(24,12)，实际=" + ratiosA.get(0)[1]);

        // 🚨 AC-15b：0.000000000001 + 99.999999999998 + 0.000000000001 —— 浮点下 = 99.99999999999999，
        //    浮点实现会**错误拒绝这个合法输入**。这条才是真正有分辨力的证伪对照组。
        assertEquals(99.99999999999999d,
            0.000000000001d + 99.999999999998d + 0.000000000001d, 0d,
            "前置自证：这组数在 double 下确实不等于 100（否则本用例失去分辨力）");
        String pnB = submit(sq.quotationId(), req(newPart("T260902-15b", null, null, "10",
            List.of(mat("AgNi90", "0.000000000001", List.of(el("Ag", "90.0"), el("Ni", "10.0"))),
                    mat("AgCu85", "99.999999999998", List.of(el("Ag", "85.0"), el("Cu", "15.0"))),
                    mat("AgNi95", "0.000000000001", List.of(el("Ag", "95.0"), el("Ni", "5.0")))),
            List.of())));
        System.out.println("[B-13⑤ AC-15b] 提交通过，料号=" + pnB);
        assertNotNull(pnB, "AC-15b：定点判等下这组合法输入必须通过（浮点实现会在这里红）");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ⑥ AC-4：占比合计 ≠ 100 → 400 + 错误信封带实际合计值
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void ac4_ratioSumNot100_rejectedWithActualSumInMessageAndDetail() {
        Seeded sq = seedQuotation();

        var ex = assertThrows(com.cpq.configure.exception.MaterialRecipeApiException.class,
            () -> service.configure(sq.quotationId(), req(newPart("T260902-占比", null, null, "10",
                List.of(mat("AgNi90", "70", List.of(el("Ag", "90.0"), el("Ni", "10.0"))),
                        mat("AgCu85", "20", List.of(el("Ag", "85.0"), el("Cu", "15.0")))),
                List.of())), null));

        System.out.println("[B-13⑥ AC-4] code=" + ex.getCode() + " bizCode=" + ex.getErrorCode()
            + " message=" + ex.getMessage() + " detail=" + ex.getDetail());
        assertEquals(400, ex.getCode());
        assertEquals("MATERIAL_RATIO_SUM_INVALID", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("90"),
            "AC-4②：提示必须写出**实际合计值** 90%，不是「合计不正确」这种形容词。实际=" + ex.getMessage());
        assertNotNull(ex.getDetail());
        assertEquals("90", ex.getDetail().get("actualSum"), "detail.actualSum 供前端直接显示");
        assertEquals("100", ex.getDetail().get("expected"));
    }

    // ── helper ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    void assertProcessFixture() {
        List<Object> got = em.createNativeQuery(
                "SELECT process_no FROM process_master WHERE process_no IN ('Z100','Z101') ORDER BY process_no")
            .getResultList();
        assertEquals(2, got.size(),
            "前置不成立：fixture基线.md §2 的 Z100 焊接 / Z101 铆接 不齐（实际=" + got + "）—— "
            + "工序是业务自维护的开放主数据，请在「主数据维护 → 工序」补齐后重跑");
    }

    @SuppressWarnings("unchecked")
    List<Object[]> unitPriceOrder(String customerNo, String partNo) {
        return em.createNativeQuery(
                "SELECT operation_no, seq_no FROM unit_price WHERE system_type='QUOTE' AND price_type='PROCESS' " +
                "AND cost_type='自制加工费' AND customer_no=:cn AND code=:mn AND finished_material_no=:mn " +
                "AND is_current=true ORDER BY seq_no")
            .setParameter("cn", customerNo).setParameter("mn", partNo).getResultList();
    }

    long signatureCount(String customerNo, String partNo) {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM sel_part_signature WHERE customer_no=:cn AND quote_part_no=:p")
            .setParameter("cn", customerNo).setParameter("p", partNo).getSingleResult()).longValue();
    }

    String signatureText(String customerNo, String partNo) {
        return (String) em.createNativeQuery(
                "SELECT config_signature_text FROM sel_part_signature WHERE customer_no=:cn AND quote_part_no=:p")
            .setParameter("cn", customerNo).setParameter("p", partNo).getSingleResult();
    }
}
