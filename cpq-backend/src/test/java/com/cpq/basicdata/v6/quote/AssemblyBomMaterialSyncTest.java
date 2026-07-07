package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** §12 组成件BOM：料号表同步(type=3) + 工序回填 + §3/§12 交叉料件（决策 #5/#6, B1）。 */
@QuarkusTest
class AssemblyBomMaterialSyncTest {

    @Inject MaterialBomMergeHandler handler;
    @Inject EntityManager em;

    static final String CUST = "TSTASM0615";
    static final String MAT  = "TESTASM0615";
    static final String PROC_NO = "TESTASM-PROC-01";
    static final String PROC_NAME = "组装A_TESTASM_UNIQUE";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE '9%' OR material_name LIKE 'ASM-%' OR material_no IN ('ASM-EXIST','ASM-NEW','ASM-CROSS','ASM-PB','ASM-PM')").executeUpdate();
        em.createNativeQuery("DELETE FROM process_master WHERE process_no=:p").setParameter("p", PROC_NO).executeUpdate();
    }
    @Transactional
    void seedProcess() {
        em.createNativeQuery("INSERT INTO process_master (id, process_no, process_name, created_at, updated_at) " +
            "VALUES (gen_random_uuid(), :p, :n, NOW(), NOW())").setParameter("p", PROC_NO).setParameter("n", PROC_NAME).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); seedProcess(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow matRow(int seq, String comp, String name, String usageType) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("项次", String.valueOf(seq));
        m.put("投入料号", comp); m.put("投入料号名称", name);
        m.put("产出料号类型", usageType); m.put("材料毛重", "1.0"); m.put("重量单位", "KG");
        return new SheetRow(seq, m);
    }
    private SheetRow asmRow(int seq, String comp, String name, String opNo, String procName) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("项次（一级）", String.valueOf(seq));
        if (comp != null) m.put("组成件料号", comp);
        if (name != null) m.put("组成件名称", name);
        if (opNo != null) m.put("工序编号", opNo);
        if (procName != null) m.put("组装工序", procName);
        m.put("组成数量", "1"); m.put("组成单位", "PCS");
        return new SheetRow(seq, m);
    }
    private String typeOf(String no) {
        var r = em.createNativeQuery("SELECT material_type FROM material_master WHERE material_no=:n")
            .setParameter("n", no).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private String opNoOf(String compNo) {
        var r = em.createNativeQuery(
            "SELECT operation_no FROM material_bom_item WHERE material_no=:m AND component_no=:c AND is_current=TRUE")
            .setParameter("m", MAT).setParameter("c", compNo).getResultList();
        return r.isEmpty() ? null : (r.get(0) == null ? null : String.valueOf(r.get(0)));
    }

    @Test
    void newComponent_materialTypeIsAssemblyLabel() {
        handler.merge(List.of(), List.of(asmRow(1, "ASM-NEW", "ASM-N1", "OP1", null)), ctx());
        assertEquals("组成件", typeOf("ASM-NEW"), "§12 新料件 material_type 固定汉字「组成件」（决策 Q5-A）");
    }

    @Test
    void existingComponent_keepsOriginalType() {
        em_upsertMaster("ASM-EXIST", "ASM-E1", "1");
        handler.merge(List.of(), List.of(asmRow(1, "ASM-EXIST", "ASM-E1", "OP1", null)), ctx());
        assertEquals("1", typeOf("ASM-EXIST"), "已存在保留原 type，不被改成「组成件」（决策 #6）");
    }

    @Test
    void crossing_materialKeepsSection3ChineseType() {
        handler.merge(
            List.of(matRow(1, "ASM-CROSS", "ASM-C1", "2.非银点类")),
            List.of(asmRow(1, "ASM-CROSS", "ASM-C1", "OP1", null)),
            ctx());
        assertEquals("非银点类", typeOf("ASM-CROSS"), "交叉料件保留 §3 汉字类型（物料BOM 先写，组成件 preserve 不覆盖）");
    }

    @Test
    void processBackfill_hit() {
        handler.merge(List.of(), List.of(asmRow(1, "ASM-PB", "ASM-PB1", null, PROC_NAME)), ctx());
        assertEquals(PROC_NO, opNoOf("ASM-PB"), "工序编号空+组装工序匹配→回填 process_no");
    }

    @Test
    void processBackfill_miss_leavesEmpty() {
        handler.merge(List.of(), List.of(asmRow(1, "ASM-PM", "ASM-PM1", null, "不存在工序ZZZ")), ctx());
        assertNull(opNoOf("ASM-PM"), "查不到→operation_no 留空，行照常导入（决策 #5）");
    }

    @Test
    void emptyComponentNo_withName_generates() {
        handler.merge(List.of(), List.of(asmRow(1, null, "ASM-GEN", "OP1", null)), ctx());
        String generatedNo = (String) em.createNativeQuery(
                "SELECT material_no FROM material_master WHERE material_name=:n")
            .setParameter("n", "ASM-GEN").getSingleResult();
        assertTrue(generatedNo.matches("^\\d{4}-\\d{10}$"), "料号空+名称→生成报价料号(XXXX-YYMMNNNNNN)，实得: " + generatedNo);
        assertEquals("组成件", typeOf(generatedNo), "生成料号对应 type=组成件");
    }

    @Transactional
    void em_upsertMaster(String no, String name, String type) {
        em.createNativeQuery("INSERT INTO material_master (id, material_no, material_name, material_type, created_at, updated_at) " +
            "VALUES (gen_random_uuid(), :no, :nm, :tp, NOW(), NOW()) ON CONFLICT (material_no) DO UPDATE SET material_type=:tp")
            .setParameter("no", no).setParameter("nm", name).setParameter("tp", type).executeUpdate();
    }
}
