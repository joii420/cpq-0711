package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.service.PartTypeInferenceService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * update-0723 B3 重构：物料BOM 单表三态。历史双参 {@code merge(materialRows, assemblyRows, ctx)}
 * 已废弃（组成件BOM sheet 删除），改为单参 {@code merge(rows, ctx)}，类型改由
 * {@link PartTypeInferenceService.TypeIndex}（ctx.sharedCache "partTypeIndex"）推断，
 * 不再由"调用方把行放进哪个 List"决定。
 *
 * <p>本测试类的 {@link #ctx()} 不设置 partTypeIndex（沿用 handler 内 null 安全兜底 = 默认零件
 * ASSEMBLY），等价覆盖旧「组成件BOM」语义；材质 RECIPE 场景改用真实存在于 {@code material_recipe}
 * 的料号 991/992（与 `报价系统模板0723.xlsx` 黄金样例一致，见 update-0723 验收）。
 */
@QuarkusTest
class MaterialBomMergeHandlerTest {

    @Inject MaterialBomMergeHandler handler;
    @Inject PartTypeInferenceService typeInferenceService;
    @Inject EntityManager em;

    static final String CUST = "TEST-MBM-CUST";
    static final String MAT  = "TEST-MBM-0001";
    static final String CFG  = "CFG-TEST-MBM-9999";
    /** 真实存在于 material_recipe 的材质料号（见 dev-docs 黄金样例，task-0708 材质库规范化落库）。 */
    static final String RECIPE_991 = "991";
    static final String RECIPE_992 = "992";

    @Transactional
    void cleanup() {
        for (String t : List.of("material_bom_item", "material_bom")) {
            em.createNativeQuery("DELETE FROM " + t + " WHERE material_no IN (:a,:b)")
              .setParameter("a", MAT).setParameter("b", CFG).executeUpdate();
        }
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null;
        return c;
    }

    /**
     * 建一个"空权威 sheet"的真实 TypeIndex（仍会跑 material_recipe/material_master 库内兜底查询）。
     * 用于需要真正命中 991/992 材质库内兜底的用例——ctx() 不带 partTypeIndex 时 handler 走
     * 纯内存 null 安全兜底（恒 ASSEMBLY，无法查库），与"库内兜底应识别材质"这一验收点不等价。
     */
    private ImportContext ctxWithRealIndex() {
        ImportContext c = ctx();
        c.sharedCache.put("partTypeIndex", typeInferenceService.buildIndex(Map.of()));
        return c;
    }

    /** ASSEMBLY 权威集：注入「自制加工费」行，使 comp 被推断为零件（不依赖默认兜底，显式建索引）。 */
    private void withAssemblyType(ImportContext ctx, String comp) {
        Map<String, String> m = new HashMap<>();
        m.put("销售料号", "ANY"); m.put("投入料号", comp);
        Map<String, List<SheetRow>> sheets = Map.of("自制加工费", List.of(new SheetRow(1, m)));
        ctx.sharedCache.put("partTypeIndex", typeInferenceService.buildIndex(sheets));
    }

    /** OUTSOURCED 权威集：注入「组成件其他费用」行。 */
    private void withOutsourcedType(ImportContext ctx, String comp) {
        Map<String, String> m = new HashMap<>();
        m.put("销售料号", "ANY"); m.put("组成件料号", comp);
        Map<String, List<SheetRow>> sheets = Map.of("组成件其他费用", List.of(new SheetRow(1, m)));
        ctx.sharedCache.put("partTypeIndex", typeInferenceService.buildIndex(sheets));
    }

    /** 单表物料BOM行：投入料号列 + 可选名称列。 */
    private SheetRow bomRow(int rowNo, int seq, String materialNo, String rawNo, String rawName) {
        Map<String, String> m = new HashMap<>();
        m.put("销售料号", materialNo); m.put("项次", String.valueOf(seq));
        if (rawNo != null) m.put("投入料号", rawNo);
        if (rawName != null) m.put("投入料号名称", rawName);
        m.put("产出料号类型", "2.非银点类");
        m.put("材料毛重", "0.5"); m.put("重量单位", "KG");
        return new SheetRow(rowNo, m);
    }

    private long count(String sql) {
        return ((Number) em.createNativeQuery(sql).setParameter("m", MAT).getSingleResult()).longValue();
    }

    private String currentChildCharacteristic(String componentNo) {
        return (String) em.createNativeQuery(
            "SELECT characteristic FROM material_bom_item WHERE material_no=:m AND component_no=:c AND is_current=TRUE")
            .setParameter("m", MAT).setParameter("c", componentNo).getSingleResult();
    }

    @Transactional
    @Test
    void recipeRow_rawCode_notRegisteredToMaster_characteristicRecipe() {
        cleanupMasterRow(RECIPE_991);
        try {
            SheetImportResult r = handler.merge(List.of(bomRow(1, 1, MAT, RECIPE_991, null)), ctxWithRealIndex());
            assertEquals(0, r.failedRows);

            Object[] row = (Object[]) em.createNativeQuery(
                "SELECT component_no, characteristic, issue_unit FROM material_bom_item WHERE material_no=:m AND is_current=TRUE")
                .setParameter("m", MAT).getSingleResult();
            assertEquals(RECIPE_991, row[0], "材质：原始码直接落库");
            assertEquals("RECIPE", row[1]);
            assertEquals("KG", row[2], "issue_unit（U5）：材质沿用重量单位");

            long masterCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_master WHERE material_no=:c")
                .setParameter("c", RECIPE_991).getSingleResult()).longValue();
            assertEquals(0L, masterCount, "材质料号不应登记 material_master（repair-2 决策 A/B）");
        } finally {
            cleanupMasterRow(RECIPE_991);
        }
    }

    @Transactional
    @Test
    void recipeRow_viaAuthoritativeSheet_unknownCode_rejected() {
        // 显式建"材质权威集"命中该 token，但 material_recipe 库内查无 → 应报「未找到材质」。
        ImportContext ctx = ctx();
        Map<String, String> elemRow = new HashMap<>();
        elemRow.put("销售料号", MAT); elemRow.put("材质料号", "GHOST-RECIPE-CODE");
        Map<String, List<SheetRow>> sheets = Map.of("物料与元素BOM", List.of(new SheetRow(1, elemRow)));
        ctx.sharedCache.put("partTypeIndex", typeInferenceService.buildIndex(sheets));

        SheetImportResult r = handler.merge(
            List.of(bomRow(1, 1, MAT, "GHOST-RECIPE-CODE", null)), ctx);
        assertTrue(r.failedRows >= 1, "材质定型但 material_recipe 查无应拒导(U2)");
        assertTrue(r.errors.get(0).message.contains("未找到材质"));
        assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE material_no=:m"));
    }

    @Transactional
    @Test
    void assemblyRow_defaultFallback_registeredAsAssembly_issueUnitPcs() {
        cleanupMasterRow("TEST-MBM-C1");
        try {
            // 不建 partTypeIndex → handler 内 null 安全兜底 = 默认零件 ASSEMBLY（等价旧"组成件BOM"语义）。
            SheetImportResult r = handler.merge(List.of(bomRow(1, 1, MAT, "TEST-MBM-C1", null)), ctx());
            assertEquals(0, r.failedRows);

            Object[] row = (Object[]) em.createNativeQuery(
                "SELECT component_no, characteristic, issue_unit FROM material_bom_item WHERE material_no=:m AND is_current=TRUE")
                .setParameter("m", MAT).getSingleResult();
            assertEquals("TEST-MBM-C1", row[0]);
            assertEquals("ASSEMBLY", row[1]);
            assertEquals("PCS", row[2], "issue_unit（U5）：零件兜底 PCS（组成单位列已删除）");

            String materialType = (String) em.createNativeQuery(
                "SELECT material_type FROM material_master WHERE material_no=:c")
                .setParameter("c", "TEST-MBM-C1").getSingleResult();
            assertEquals("零件", materialType, "B6：零件登记 material_type=零件");
        } finally {
            cleanupMasterRow("TEST-MBM-C1");
        }
    }

    @Transactional
    @Test
    void outsourcedRow_viaAuthoritativeSheet_registeredAsOutsourced() {
        cleanupMasterRow("TEST-MBM-OUT1");
        try {
            ImportContext ctx = ctx();
            withOutsourcedType(ctx, "TEST-MBM-OUT1");
            SheetImportResult r = handler.merge(List.of(bomRow(1, 1, MAT, "TEST-MBM-OUT1", null)), ctx);
            assertEquals(0, r.failedRows);

            assertEquals("OUTSOURCED", currentChildCharacteristic("TEST-MBM-OUT1"));
            String materialType = (String) em.createNativeQuery(
                "SELECT material_type FROM material_master WHERE material_no=:c")
                .setParameter("c", "TEST-MBM-OUT1").getSingleResult();
            assertEquals("外购件", materialType, "B6：外购件登记 material_type=外购件");
        } finally {
            cleanupMasterRow("TEST-MBM-OUT1");
        }
    }

    @Transactional
    @Test
    void compositionQty_isMapped() {
        cleanupMasterRow("TEST-MBM-QTY1");
        try {
            Map<String, String> m = new HashMap<>();
            m.put("销售料号", MAT); m.put("项次", "1");
            m.put("投入料号", "TEST-MBM-QTY1"); m.put("组成数量", "3");
            SheetImportResult r = handler.merge(List.of(new SheetRow(1, m)), ctx());
            assertEquals(0, r.failedRows);
            Object v = em.createNativeQuery(
                "SELECT composition_qty FROM material_bom_item WHERE material_no=:m AND component_no=:c AND is_current=TRUE")
                .setParameter("m", MAT).setParameter("c", "TEST-MBM-QTY1").getSingleResult();
            assertEquals(0, new java.math.BigDecimal("3").compareTo((java.math.BigDecimal) v));
        } finally {
            cleanupMasterRow("TEST-MBM-QTY1");
        }
    }

    @Transactional
    @Test
    void b4_operationNo_backfilledFromSharedCache() {
        cleanupMasterRow("TEST-MBM-OP1");
        try {
            ImportContext ctx = ctx();
            ctx.sharedCache.put("selfProcessOperationNo",
                Map.of(Arrays.asList(MAT, "TEST-MBM-OP1"), "Z380"));
            SheetImportResult r = handler.merge(List.of(bomRow(1, 1, MAT, "TEST-MBM-OP1", null)), ctx);
            assertEquals(0, r.failedRows);
            Object opNo = em.createNativeQuery(
                "SELECT operation_no FROM material_bom_item WHERE material_no=:m AND component_no=:c AND is_current=TRUE")
                .setParameter("m", MAT).setParameter("c", "TEST-MBM-OP1").getSingleResult();
            assertEquals("Z380", opNo, "B4：ASSEMBLY 子行应按(销售料号,投入料号)命中自制加工费工序编号反填");
        } finally {
            cleanupMasterRow("TEST-MBM-OP1");
        }
    }

    @Transactional
    @Test
    void cfgPrefixMaterial_rejected() {
        SheetImportResult r = handler.merge(
            List.of(bomRow(1, 1, CFG, "TEST-MBM-C1", null)), ctx());
        assertTrue(r.failedRows >= 1);
        assertEquals(0L, ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom WHERE material_no=:c").setParameter("c", CFG).getSingleResult()).longValue());
    }

    @Transactional
    @Test
    void bothCodeAndNameBlank_rejected() {
        SheetImportResult r = handler.merge(List.of(bomRow(1, 1, MAT, null, null)), ctx());
        assertTrue(r.failedRows >= 1);
        assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE material_no=:m"));
    }

    @Transactional
    @Test
    void mixedRecipeAndAssembly_perComponentCharacteristic_masterBecomesAssembly() {
        cleanupMasterRow("TEST-MBM-MIX1");
        try {
            SheetImportResult r = handler.merge(List.of(
                bomRow(1, 1, MAT, RECIPE_991, null),
                bomRow(2, 2, MAT, "TEST-MBM-MIX1", null)), ctxWithRealIndex());
            assertEquals(0, r.failedRows);

            java.util.List<?> rows = em.createNativeQuery(
                "SELECT component_no, characteristic FROM material_bom_item " +
                "WHERE material_no=:m AND is_current=TRUE ORDER BY component_no")
                .setParameter("m", MAT).getResultList();
            assertEquals(2, rows.size());

            Object[] master = (Object[]) em.createNativeQuery(
                "SELECT bom_type, characteristic FROM material_bom WHERE material_no=:m AND is_current=TRUE")
                .setParameter("m", MAT).getSingleResult();
            assertEquals("ASSEMBLY", master[0], "含非 RECIPE 子行 → 主表 bom_type=ASSEMBLY");
            assertEquals("ASSEMBLY", master[1]);
        } finally {
            cleanupMasterRow("TEST-MBM-MIX1");
        }
    }

    @Transactional
    @Test
    void recipeOnly_masterBomTypeMaterial_characteristicNull() {
        SheetImportResult r = handler.merge(List.of(bomRow(1, 1, MAT, RECIPE_991, null)), ctxWithRealIndex());
        assertEquals(0, r.failedRows);
        Object[] master = (Object[]) em.createNativeQuery(
            "SELECT bom_type, characteristic FROM material_bom WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", MAT).getSingleResult();
        assertEquals("MATERIAL", master[0]);
        assertNull(master[1]);
    }

    // ===== 单序列升版（RECIPE → 混入零件后应单序列升版，不重置版本号） =====

    static final String MAT2  = "TESTBOM0723";
    static final String CUST2 = "TST0723";

    @Transactional
    void cleanup2() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no = :m")
          .setParameter("m", MAT2).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no = :m")
          .setParameter("m", MAT2).executeUpdate();
    }

    private ImportContext ctx2() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST2; c.systemType = "QUOTE"; c.importedBy = null;
        c.sharedCache.put("partTypeIndex", typeInferenceService.buildIndex(Map.of()));
        return c;
    }

    private String currentBomVersion2() {
        List<?> r = em.createNativeQuery(
            "SELECT bom_version FROM material_bom " +
            "WHERE system_type='QUOTE' AND customer_no=:cn AND material_no=:mn AND is_current=true LIMIT 1")
            .setParameter("cn", CUST2).setParameter("mn", MAT2).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }

    @Transactional
    @Test
    void reclassify_recipeToAssembly_shouldBumpVersion_notReset() {
        cleanupMasterRow("TESTBOM0723-C1");
        try {
            cleanup2();
            // 第一次：纯材质行
            handler.merge(List.of(bomRow(1, 1, MAT2, RECIPE_991, null)), ctx2());
            assertEquals("2000", currentBomVersion2(), "首次写入应为 v2000");

            // 第二次：新增零件子行（默认兜底 ASSEMBLY）
            handler.merge(List.of(
                bomRow(1, 1, MAT2, RECIPE_991, null),
                bomRow(2, 2, MAT2, "TESTBOM0723-C1", null)), ctx2());

            assertEquals("2001", currentBomVersion2(), "同料号重分类应单序列升版 v2000→v2001");
            long total = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_bom WHERE system_type='QUOTE' AND customer_no=:cn AND material_no=:mn")
                .setParameter("cn", CUST2).setParameter("mn", MAT2).getSingleResult()).longValue();
            assertEquals(2L, total, "旧版本应 is_current=false 保留（历史不删）");
        } finally {
            cleanupMasterRow("TESTBOM0723-C1");
            cleanup2();
        }
    }

    @Transactional
    void cleanupMasterRow(String... codes) {
        for (String c : codes) {
            em.createNativeQuery("DELETE FROM material_master WHERE material_no = :c")
              .setParameter("c", c).executeUpdate();
            em.createNativeQuery("DELETE FROM material_customer_map WHERE material_no = :c")
              .setParameter("c", c).executeUpdate();
            em.createNativeQuery("DELETE FROM pending_material_master_staging WHERE material_no = :c")
              .setParameter("c", c).executeUpdate();
        }
    }
}
