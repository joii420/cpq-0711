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
        return new SheetRow(rowNo, m);
    }
    private long count(String sql) {
        return ((Number) em.createNativeQuery(sql).setParameter("m", MAT).getSingleResult()).longValue();
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

        handler.merge(List.of(matRow(1, 1, "TEST-MBM-C1", "0.5")), List.of(asmRow(1, 1, "TEST-MBM-C1", "1")), ctx());

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
}
