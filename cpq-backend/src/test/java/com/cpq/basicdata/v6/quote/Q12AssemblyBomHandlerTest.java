package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Task 5 集成测试：Q12 组成件BOM 主从版本化 + 与 Q03 的 bom_type/characteristic 物理隔离（#5）。 */
@QuarkusTest
class Q12AssemblyBomHandlerTest {

    @Inject Q12AssemblyBomHandler handler;
    @Inject Q03MaterialBomHandler q03;   // 隔离测试用
    @Inject EntityManager em;

    static final String MAT = "TEST-Q12-MAT";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000012");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE 'TEST-Q12-COMP%'").executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }
    private SheetRow row(int seq, String comp, String qty) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("项次（一级）", String.valueOf(seq)); m.put("工序编号", "OP1");
        m.put("项次（二级）", "1"); m.put("组成件料号", comp); m.put("组成数量", qty); m.put("组成单位", "PCS");
        return new SheetRow(seq, m);
    }
    private SheetRow q03Row(String comp) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("投入料号", comp); m.put("产出料号类型", "组成件");
        m.put("项次", "1"); m.put("材料毛重", "1"); m.put("材料净重", "1"); m.put("重量单位", "G");
        return new SheetRow(1, m);
    }
    private String bomVersion() {
        List<?> r = em.createNativeQuery(
            "SELECT bom_version FROM material_bom WHERE material_no=:m AND bom_type='ASSEMBLY' AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }

    @Test void importTwice_idempotent_then_bump() {
        handler.handle(List.of(row(1, "TEST-Q12-COMP-X", "2"), row(2, "TEST-Q12-COMP-Y", "3")), ctx());
        handler.handle(List.of(row(1, "TEST-Q12-COMP-X", "2"), row(2, "TEST-Q12-COMP-Y", "3")), ctx());
        assertEquals("2000", bomVersion());
        long curChild = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom_item WHERE material_no=:m AND characteristic='ASSEMBLY' AND is_current=true")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(2L, curChild);

        handler.handle(List.of(row(1, "TEST-Q12-COMP-X", "2"), row(2, "TEST-Q12-COMP-Y", "9")), ctx());
        assertEquals("2001", bomVersion());
    }

    @Test void q03AndQ12_isolated_byCharacteristic() {
        q03.handle(List.of(q03Row("TEST-Q12-COMP-M")), ctx());          // bom_type=MATERIAL, characteristic=NULL
        handler.handle(List.of(row(1, "TEST-Q12-COMP-A", "2")), ctx()); // bom_type=ASSEMBLY, characteristic=ASSEMBLY
        long masterMaterial = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom WHERE material_no=:m AND bom_type='MATERIAL'")
            .setParameter("m", MAT).getSingleResult()).longValue();
        long masterAssembly = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom WHERE material_no=:m AND bom_type='ASSEMBLY'")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(1L, masterMaterial, "Q03 主表保留");
        assertEquals(1L, masterAssembly, "Q12 主表保留（characteristic 隔离，不撞 uq）");
        long childAssembly = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom_item WHERE material_no=:m AND characteristic='ASSEMBLY'")
            .setParameter("m", MAT).getSingleResult()).longValue();
        long childMaterial = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom_item WHERE material_no=:m AND characteristic IS NULL")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(1L, childAssembly);
        assertEquals(1L, childMaterial);
    }

    /** 裸重复表头行：项次×2(列序: 项次=seq_no一级, 项次=item_seq二级)。用 List 构造器模拟真实模板。 */
    private SheetRow bareRow(int seqOne, int seqTwo, String comp) {
        List<String[]> o = new ArrayList<>();
        o.add(new String[]{"宏丰料号", MAT});
        o.add(new String[]{"项次", String.valueOf(seqOne)});   // 一级 -> seq_no
        o.add(new String[]{"工序编号", "OP1"});
        o.add(new String[]{"组装工序", "Z350"});               // 不导入
        o.add(new String[]{"项次", String.valueOf(seqTwo)});   // 二级 -> item_seq
        o.add(new String[]{"组成件料号", comp});
        o.add(new String[]{"组成数量", "2"});
        o.add(new String[]{"组成单位", "PCS"});
        return new SheetRow(2, o);
    }

    @Test void bareDuplicateHeader_itemSeqFromSecond() {
        handler.handle(List.of(bareRow(3, 7, "TEST-Q12-COMP-B")), ctx());
        Object[] rec = (Object[]) em.createNativeQuery(
            "SELECT seq_no, item_seq FROM material_bom_item " +
            "WHERE material_no=:m AND characteristic='ASSEMBLY' AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getSingleResult();
        assertEquals(3, ((Number) rec[0]).intValue(), "seq_no=第1个项次");
        assertEquals(7, ((Number) rec[1]).intValue(), "item_seq=第2个项次");
    }
}
