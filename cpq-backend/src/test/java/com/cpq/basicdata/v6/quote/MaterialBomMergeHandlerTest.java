package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class MaterialBomMergeHandlerTest {

    @Inject MaterialBomMergeHandler handler;
    @Inject EntityManager em;

    static final String CUST = "TEST-MBM-CUST";
    static final String MAT  = "TEST-MBM-0001";
    static final String CFG  = "CFG-TEST-MBM-9999";

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
    private SheetRow matRow(int rowNo, int seq, String comp, String qty) {
        Map<String, String> m = new HashMap<>();
        m.put("宏丰料号", MAT); m.put("项次", String.valueOf(seq));
        m.put("投入料号", comp); m.put("产出料号类型", "2.非银点类");
        m.put("材料毛重", qty); m.put("重量单位", "KG");
        return new SheetRow(rowNo, m);
    }
    private SheetRow asmRow(int rowNo, int seq, String comp, String qty) {
        Map<String, String> m = new HashMap<>();
        m.put("宏丰料号", MAT); m.put("项次（一级）", String.valueOf(seq));
        m.put("组成件料号", comp); m.put("组成数量", qty); m.put("组成单位", "PCS");
        m.put("组成类型", "零件");   // 三态统一：该列必填，默认零件以保持原测试语义(ASSEMBLY)
        return new SheetRow(rowNo, m);
    }
    private long count(String sql) {
        return ((Number) em.createNativeQuery(sql).setParameter("m", MAT).getSingleResult()).longValue();
    }

    /** 带「组成类型」的组成件行夹具（三态统一）。kind 传 null 表示该列缺失。 */
    private SheetRow asmRowKind(int rowNo, int seq, String comp, String qty, String kind) {
        Map<String, String> m = new HashMap<>();
        m.put("宏丰料号", MAT); m.put("项次（一级）", String.valueOf(seq));
        m.put("组成件料号", comp); m.put("组成数量", qty); m.put("组成单位", "PCS");
        if (kind != null) m.put("组成类型", kind);
        return new SheetRow(rowNo, m);
    }

    /** 可自定义「组成类型」表头名的夹具（测列名容错）。header 传 null 表示不放该列。 */
    private SheetRow asmRowKindHeader(int rowNo, int seq, String comp, String qty, String header, String kind) {
        Map<String, String> m = new HashMap<>();
        m.put("宏丰料号", MAT); m.put("项次（一级）", String.valueOf(seq));
        m.put("组成件料号", comp); m.put("组成数量", qty); m.put("组成单位", "PCS");
        if (header != null) m.put(header, kind);
        return new SheetRow(rowNo, m);
    }

    /**
     * 「说明列 + 真实列」共存、且说明列排在真实列之前的夹具（测列序敏感场景）。
     * 用 LinkedHashMap 保留插入顺序（HashMap 无序，测不出"谁排前面"）。
     */
    private SheetRow asmRowKindDescBeforeReal(int rowNo, int seq, String comp, String qty,
                                               String descHeader, String descVal,
                                               String realHeader, String realVal) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("项次（一级）", String.valueOf(seq));
        m.put("组成件料号", comp); m.put("组成数量", qty); m.put("组成单位", "PCS");
        m.put(descHeader, descVal);   // 说明列先 put（排前面）
        m.put(realHeader, realVal);   // 真实列后 put（排后面）
        return new SheetRow(rowNo, m);
    }

    /** 取当前生效子行的 characteristic（断言唯一行时用）。 */
    private String currentChildCharacteristic() {
        return (String) em.createNativeQuery(
            "SELECT characteristic FROM material_bom_item WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", MAT).getSingleResult();
    }

    @Test
    void sameMaterialInBothSheets_collapsesToOneAssemblyCurrentRow() {
        handler.merge(
            List.of(matRow(1, 1, "TEST-MBM-C1", "0.5"), matRow(2, 2, "TEST-MBM-C2", "1.0")),
            List.of(asmRow(1, 1, "TEST-MBM-C1", "1"),   asmRow(2, 2, "TEST-MBM-C3", "2")),
            ctx());

        assertEquals(1L, count("SELECT count(*) FROM material_bom WHERE material_no=:m AND is_current=TRUE"));
        assertEquals("ASSEMBLY", em.createNativeQuery(
            "SELECT characteristic FROM material_bom WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", MAT).getSingleResult());
        assertEquals(3L, count("SELECT count(*) FROM material_bom_item WHERE material_no=:m AND is_current=TRUE"));
        assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE material_no=:m AND is_current=TRUE AND characteristic IS NULL"));
    }

    @Test
    void materialOnlyThenBoth_flipsNullToHistory() {
        handler.merge(List.of(matRow(1, 1, "TEST-MBM-C1", "0.5")), List.of(), ctx());
        assertEquals(1L, count("SELECT count(*) FROM material_bom WHERE material_no=:m AND is_current=TRUE AND characteristic IS NULL"));

        // repair-2 注：第二次 merge 的组成件行改用一个与物料BOM不同的新码(TEST-MBM-C9)，而非复用
        // "TEST-MBM-C1"。原因：决策 D(architecture-review.md §3.3-2)——组成件料号命中"本次导入
        // 材质料号集"时恒按材质处理(RECIPE)，若仍复用同一码会被判定为材质料号自身重复出现，
        // 不再是"新增真组成件"。本测试关心的是"材质only → 新增真组成件 → 主表重分类为
        // ASSEMBLY + 历史保留"，故用不同码的真组成件保持原测试意图不变。
        handler.merge(List.of(matRow(1, 1, "TEST-MBM-C1", "0.5")), List.of(asmRow(1, 1, "TEST-MBM-C9", "1")), ctx());

        assertEquals(1L, count("SELECT count(*) FROM material_bom WHERE material_no=:m AND is_current=TRUE"));
        assertEquals("ASSEMBLY", em.createNativeQuery(
            "SELECT characteristic FROM material_bom WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", MAT).getSingleResult());
        assertEquals(1L, count("SELECT count(*) FROM material_bom WHERE material_no=:m AND is_current=FALSE AND characteristic IS NULL"));
    }

    // ===== RED: 料号重分类单序列升版 =====

    /** 第二套 fixture：独立料号/客户，与上方测试隔离。 */
    static final String MAT2  = "TESTBOM0615";
    static final String CUST2 = "TST0615";

    @Transactional
    void cleanup2() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no = :m")
          .setParameter("m", MAT2).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no = :m")
          .setParameter("m", MAT2).executeUpdate();
    }

    private ImportContext ctx2() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST2; c.systemType = "QUOTE";
        c.importedBy = null; return c;
    }
    private SheetRow matRow2(int seq, String comp) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("宏丰料号", MAT2); m.put("项次", String.valueOf(seq));
        m.put("投入料号", comp); m.put("投入料号名称", comp + "_NAME");
        m.put("产出料号类型", "1"); m.put("材料毛重", "1.5"); m.put("重量单位", "KG");
        return new SheetRow(seq, m);
    }
    private SheetRow asmRow2(int seq, String comp, String qty) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("宏丰料号", MAT2); m.put("项次（一级）", String.valueOf(seq));
        m.put("组成件料号", comp); m.put("组成数量", qty); m.put("组成单位", "PCS");
        m.put("组成类型", "零件");   // 三态统一：该列必填
        return new SheetRow(seq, m);
    }
    private String currentBomVersion2() {
        List<?> r = em.createNativeQuery(
            "SELECT bom_version FROM material_bom " +
            "WHERE system_type='QUOTE' AND customer_no=:cn AND material_no=:mn AND is_current=true LIMIT 1")
            .setParameter("cn", CUST2).setParameter("mn", MAT2).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long currentMasterCount2() {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom " +
            "WHERE system_type='QUOTE' AND customer_no=:cn AND material_no=:mn AND is_current=true")
            .setParameter("cn", CUST2).setParameter("mn", MAT2).getSingleResult()).longValue();
    }
    private long totalMasterCount2() {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom " +
            "WHERE system_type='QUOTE' AND customer_no=:cn AND material_no=:mn")
            .setParameter("cn", CUST2).setParameter("mn", MAT2).getSingleResult()).longValue();
    }

    /**
     * RED: 同一料号先以「物料BOM」(MATERIAL/characteristic=null) 导入 v2000，
     * 再以「组成件BOM」(ASSEMBLY) 重分类导入——应单序列升版为 v2001。
     *
     * <p>修复前：ASSEMBLY 被视为新分组，nextVersionOf 在空历史中返回 "2000"（BUG）。
     * <p>修复后：masterGk 收敛为 system_type+customer_no+material_no，查全料号历史 max，升为 "2001"。
     */
    @Test
    void reclassify_materialToAssembly_shouldBumpVersion_notReset() {
        cleanup2();

        // 第一次：物料BOM 行，1 个组成件 C1
        handler.merge(List.of(matRow2(1, "C1")), List.of(), ctx2());
        assertEquals("2000", currentBomVersion2(), "首次写入应为 v2000");
        assertEquals(1L, currentMasterCount2(), "首次写入主表应有 1 行 current");

        // 第二次：组成件变化且被分类为 ASSEMBLY（4 个组成件 C1..C4）
        handler.merge(
            List.of(),
            List.of(asmRow2(1, "C1", "2.0"), asmRow2(2, "C2", "3.0"),
                    asmRow2(3, "C3", "1.5"), asmRow2(4, "C4", "0.5")),
            ctx2()
        );

        // 核心断言（修复前第 2、3 条会失败）
        assertEquals(1L, currentMasterCount2(),
            "第二次 merge 后 is_current=true 主行应仅 1 条（单 current）");
        assertEquals("2001", currentBomVersion2(),
            "同料号重分类应单序列升版 v2000→v2001（修复前 ASSEMBLY 新组重置为 v2000=BUG）");
        assertEquals(2L, totalMasterCount2(),
            "旧 MATERIAL v2000 行应 is_current=false 保留（历史不删，total=2）");

        cleanup2();
    }

    @Test
    void cfgPrefixMaterial_rejected() {
        Map<String, String> m = new HashMap<>();
        m.put("宏丰料号", CFG); m.put("项次", "1"); m.put("投入料号", "TEST-MBM-C1"); m.put("材料毛重", "1");
        SheetImportResult r = handler.merge(List.of(new SheetRow(1, m)), List.of(), ctx());
        assertTrue(r.failedRows >= 1);
        assertEquals(0L, ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom WHERE material_no=:c").setParameter("c", CFG).getSingleResult()).longValue());
    }

    @Test
    void materialOnly_writesWeightColumns_notLegacyAndTypeAsLabel() {
        // matRow 默认 产出料号类型="2.非银点类", 材料毛重=qty, 重量单位=KG（净重缺省=null）
        handler.merge(List.of(matRow(1, 1, "TEST-MBM-C1", "0.5")), List.of(), ctx());

        Object[] r = (Object[]) em.createNativeQuery(
            "SELECT rough_weight, net_weight, weight_unit, composition_qty, base_qty, issue_unit, component_usage_type " +
            "FROM material_bom_item WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", MAT).getSingleResult();

        assertEquals(0, new java.math.BigDecimal("0.5").compareTo((java.math.BigDecimal) r[0]), "rough_weight 应=0.5");
        assertNull(r[1], "net_weight 缺省输入应=null");
        assertEquals("KG", r[2], "weight_unit 应=KG");
        assertNull(r[3], "composition_qty 旧字段不再写");
        assertNull(r[4], "base_qty 旧字段不再写");
        assertNull(r[5], "issue_unit 旧字段不再写");
        assertEquals("非银点类", r[6], "component_usage_type 应存汉字");
    }

    // ===== repair-2: 材质料号不入料号表 + characteristic=RECIPE（决策 A/B/C/D）=====

    /** repair-2 专用 component_no 池，避免与上方既有 fixture(TEST-MBM-C1/C2/C3) 的 material_master 写入互相污染。 */
    @Transactional
    void cleanupRepair2Master(String... codes) {
        for (String c : codes) {
            em.createNativeQuery("DELETE FROM material_master WHERE material_no = :c")
              .setParameter("c", c).executeUpdate();
        }
    }

    private ImportContext ctxWithMatSet(String... matNos) {
        ImportContext c = ctx();
        c.sharedCache.put("quoteMaterialNoSet", new java.util.LinkedHashSet<>(List.of(matNos)));
        return c;
    }

    @Test
    void ac123_materialBomRow_isRecipe_rawCode_notRegisteredToMaster() {
        cleanupRepair2Master("R2-MAT-991");
        try {
            handler.merge(List.of(matRow(1, 1, "R2-MAT-991", "0.5")), List.of(), ctx());

            Object[] r = (Object[]) em.createNativeQuery(
                "SELECT component_no, characteristic FROM material_bom_item WHERE material_no=:m AND is_current=TRUE")
                .setParameter("m", MAT).getSingleResult();
            assertEquals("R2-MAT-991", r[0], "AC-3: component_no 应为原始码(未 resolve/铸号)");
            assertEquals("RECIPE", r[1], "AC-2: 材质料号 characteristic 应为 RECIPE");

            long masterCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_master WHERE material_no=:c")
                .setParameter("c", "R2-MAT-991").getSingleResult()).longValue();
            assertEquals(0L, masterCount, "AC-1: 材质料号不应登记 material_master");
        } finally {
            cleanupRepair2Master("R2-MAT-991");
        }
    }

    @Test
    void ac5_assemblySheetRow_hitsMaterialNoSet_isRejected() {
        cleanupRepair2Master("R2-MAT-992");
        try {
            // 三态统一：组成件BOM 里出现的组件码若命中"本次导入材质料号集"，
            // 与「组成类型」值域(零件/外购件)语义矛盾 → 拒导该行。
            // 原 repair-2 行为是静默纠正为 RECIPE；现改为显式报错，防线等价但可见。
            ImportContext c = ctxWithMatSet("R2-MAT-992");
            SheetImportResult r = handler.merge(
                List.of(), List.of(asmRow(1, 1, "R2-MAT-992", "1")), c);

            assertTrue(r.failedRows >= 1, "命中材质料号集的组成件行应被拒导");
            assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE material_no=:m"),
                "拒导行不应落库");

            long masterCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_master WHERE material_no=:c")
                .setParameter("c", "R2-MAT-992").getSingleResult()).longValue();
            assertEquals(0L, masterCount,
                "repair-2 防线仍有效: 材质料号不得登记 material_master(森萨塔跨客户串号根因)");
        } finally {
            cleanupRepair2Master("R2-MAT-992");
        }
    }

    @Test
    void ac6_assemblySheetRow_realComponent_stillAssembly_registeredAndResolved() {
        cleanupRepair2Master("R2-REAL-COMP-1");
        try {
            // 未命中材质料号集(sharedCache 为空) → 维持原真组成件路径：resolve + 登记 master + ASSEMBLY。
            handler.merge(List.of(), List.of(asmRow(1, 1, "R2-REAL-COMP-1", "2")), ctx());

            Object[] r = (Object[]) em.createNativeQuery(
                "SELECT component_no, characteristic FROM material_bom_item WHERE material_no=:m AND is_current=TRUE")
                .setParameter("m", MAT).getSingleResult();
            assertEquals("R2-REAL-COMP-1", r[0]);
            assertEquals("ASSEMBLY", r[1], "AC-6: 真组成件不受影响，仍应为 ASSEMBLY");

            long masterCount = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_master WHERE material_no=:c")
                .setParameter("c", "R2-REAL-COMP-1").getSingleResult()).longValue();
            assertEquals(1L, masterCount, "AC-6: 真组成件仍应登记 material_master");
        } finally {
            cleanupRepair2Master("R2-REAL-COMP-1");
        }
    }

    @Test
    void ac4_mixedRecipeAndAssembly_perComponentCharacteristic_notOverwritten() {
        cleanupRepair2Master("R2-MAT-A", "R2-REAL-COMP-2");
        try {
            ImportContext c = ctxWithMatSet("R2-MAT-A");
            handler.merge(
                List.of(matRow(1, 1, "R2-MAT-A", "0.3")),
                List.of(asmRow(1, 1, "R2-REAL-COMP-2", "5")),
                c);

            // 同一 material_no 下应同时存在 RECIPE(R2-MAT-A) 与 ASSEMBLY(R2-REAL-COMP-2) 两类子行，互不覆盖。
            java.util.List<?> rows = em.createNativeQuery(
                "SELECT component_no, characteristic FROM material_bom_item " +
                "WHERE material_no=:m AND is_current=TRUE ORDER BY component_no")
                .setParameter("m", MAT).getResultList();
            assertEquals(2, rows.size(), "AC-4: 应有 2 行子行(不撞键)");
            Object[] row0 = (Object[]) rows.get(0);
            Object[] row1 = (Object[]) rows.get(1);
            java.util.Map<String, String> byComp = new java.util.LinkedHashMap<>();
            byComp.put((String) row0[0], (String) row0[1]);
            byComp.put((String) row1[0], (String) row1[1]);
            assertEquals("RECIPE", byComp.get("R2-MAT-A"), "AC-4: 材质料号行应为 RECIPE，不被 ASSEMBLY 覆盖");
            assertEquals("ASSEMBLY", byComp.get("R2-REAL-COMP-2"), "AC-4: 真组成件行应为 ASSEMBLY");

            // 主表：含真实 ASSEMBLY 子行 → 主表级 ASSEMBLY(现语义不变)
            Object[] master = (Object[]) em.createNativeQuery(
                "SELECT bom_type, characteristic FROM material_bom WHERE material_no=:m AND is_current=TRUE")
                .setParameter("m", MAT).getSingleResult();
            assertEquals("ASSEMBLY", master[0]);
            assertEquals("ASSEMBLY", master[1]);
        } finally {
            cleanupRepair2Master("R2-MAT-A", "R2-REAL-COMP-2");
        }
    }

    // ===== 三态统一 =====

    @Test
    void componentKind_零件_mapsToAssembly() {
        cleanupRepair2Master("TRI-PART-1");
        try {
            handler.merge(List.of(), List.of(asmRowKind(1, 1, "TRI-PART-1", "1", "零件")), ctx());
            assertEquals("ASSEMBLY", currentChildCharacteristic(), "组成类型=零件 → ASSEMBLY");
        } finally {
            cleanupRepair2Master("TRI-PART-1");
        }
    }

    @Test
    void componentKind_外购件_mapsToOutsourced() {
        cleanupRepair2Master("TRI-OUT-1");
        try {
            handler.merge(List.of(), List.of(asmRowKind(1, 1, "TRI-OUT-1", "1", "外购件")), ctx());
            assertEquals("OUTSOURCED", currentChildCharacteristic(), "组成类型=外购件 → OUTSOURCED");
        } finally {
            cleanupRepair2Master("TRI-OUT-1");
        }
    }

    @Test
    void componentKind_missing_rejectsRow() {
        SheetImportResult r = handler.merge(
            List.of(), List.of(asmRowKind(1, 1, "TRI-MISS-1", "1", null)), ctx());
        assertTrue(r.failedRows >= 1, "组成类型列缺失应拒导");
        assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE material_no=:m"),
            "拒导行不应落库");
    }

    @Test
    void componentKind_illegalValue_rejectsRow() {
        SheetImportResult r = handler.merge(
            List.of(), List.of(asmRowKind(1, 1, "TRI-BAD-1", "1", "半成品")), ctx());
        assertTrue(r.failedRows >= 1, "组成类型非法值应拒导");
        assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE material_no=:m"));
    }

    /**
     * 静默失败回归：仅「组成类型」变化、其余列全同。
     * characteristic 若不在 CHILD_CONTENT，multisetEqual 判"无变化"→ 完全不写库 → 改动静默丢失。
     */
    @Test
    void componentKindChange_partToOutsourced_isDetectedAsContentChange() {
        cleanupRepair2Master("TRI-CHG-1");
        try {
            handler.merge(List.of(), List.of(asmRowKind(1, 1, "TRI-CHG-1", "1", "零件")), ctx());
            assertEquals("ASSEMBLY", currentChildCharacteristic());

            handler.merge(List.of(), List.of(asmRowKind(1, 1, "TRI-CHG-1", "1", "外购件")), ctx());
            assertEquals("OUTSOURCED", currentChildCharacteristic(),
                "characteristic 必须在 CHILD_CONTENT 内，否则内容比较判'无变化'而完全不写库");
        } finally {
            cleanupRepair2Master("TRI-CHG-1");
        }
    }

    /**
     * 只有外购件子行的料号，主表仍应判 ASSEMBLY，
     * 使走主表 characteristic 的 ll_view(来料) 能捞到它。
     */
    @Test
    void outsourcedOnly_masterStillAssembly() {
        cleanupRepair2Master("TRI-OUT-ONLY");
        try {
            handler.merge(List.of(), List.of(asmRowKind(1, 1, "TRI-OUT-ONLY", "1", "外购件")), ctx());

            Object[] master = (Object[]) em.createNativeQuery(
                "SELECT bom_type, characteristic FROM material_bom WHERE material_no=:m AND is_current=TRUE")
                .setParameter("m", MAT).getSingleResult();
            assertEquals("ASSEMBLY", master[0], "纯外购件料号 bom_type 应为 ASSEMBLY");
            assertEquals("ASSEMBLY", master[1], "纯外购件料号主表 characteristic 应为 ASSEMBLY");
        } finally {
            cleanupRepair2Master("TRI-OUT-ONLY");
        }
    }

    // ===== 列名容错：exact 未命中回退 exactOrBracketed（精确名或紧跟括号的后缀名）=====

    @Test
    void componentKind_bracketSuffixHeader_isRecognizedViaFallback() {
        cleanupRepair2Master("TRI-HDR-1");
        try {
            handler.merge(List.of(),
                List.of(asmRowKindHeader(1, 1, "TRI-HDR-1", "1", "组成类型（零件/外购件）", "外购件")),
                ctx());
            assertEquals("OUTSOURCED", currentChildCharacteristic(),
                "括号后缀表头「组成类型（零件/外购件）」应被 exactOrBracketed 识别");
        } finally {
            cleanupRepair2Master("TRI-HDR-1");
        }
    }

    @Test
    void componentKind_fullWidthSpace_isStrippedNotRejected() {
        cleanupRepair2Master("TRI-FWSP-1");
        try {
            handler.merge(List.of(),
                List.of(asmRowKindHeader(1, 1, "TRI-FWSP-1", "1", "组成类型", "零件　")),
                ctx());
            assertEquals("ASSEMBLY", currentChildCharacteristic(),
                "尾随全角空格(U+3000)应被 strip() 清掉，不应被判非法值拒导");
        } finally {
            cleanupRepair2Master("TRI-FWSP-1");
        }
    }

    @Test
    void componentKind_onlyDescriptionColumn_stillRejected() {
        // 只放「组成类型说明」列（无「组成类型」列），验证 exactOrBracketed 不会把说明列误当正主
        // ——「组成类型」后紧跟的是"说"而非括号，天然被排除；这正是当初选用 exact() 的理由得以保留。
        SheetImportResult r = handler.merge(List.of(),
            List.of(asmRowKindHeader(1, 1, "TRI-DESC-1", "1", "组成类型说明", "零件")),
            ctx());
        assertTrue(r.failedRows >= 1, "只放说明列时应仍拒导（不得被回退逻辑误命中）");
        assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE material_no=:m"),
            "拒导行不应落库");
    }

    @Test
    void componentKind_descriptionColumnBeforeRealColumn_doesNotSilentlyMisread() {
        // 更危险的场景：「组成类型说明」(值=零件) 与「组成类型（零件/外购件）」(值=外购件) 同时存在，
        // 且说明列排在真实列**之前**。若回退逻辑用 contains 按列序取首个命中，会静默取到说明列的
        // 「零件」而非真实列的「外购件」——比整表拒导更危险(错误落库而非可见报错)。
        // exactOrBracketed 只认精确名或"基名+左括号"的后缀，说明列("组成类型"+"说")天然不命中，
        // 故无论列序如何，都应正确落到真实列的值 OUTSOURCED。
        cleanupRepair2Master("TRI-ORDER-1");
        try {
            handler.merge(List.of(),
                List.of(asmRowKindDescBeforeReal(1, 1, "TRI-ORDER-1", "1",
                    "组成类型说明", "零件",
                    "组成类型（零件/外购件）", "外购件")),
                ctx());
            assertEquals("OUTSOURCED", currentChildCharacteristic(),
                "说明列排在真实列前也不应被误读；应正确取真实列的值 OUTSOURCED，而非说明列的 零件(ASSEMBLY)");
        } finally {
            cleanupRepair2Master("TRI-ORDER-1");
        }
    }
}
