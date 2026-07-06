package com.cpq.basicdata.v6.pricing;

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

/**
 * 20260705：P06 物料BOM 导入时同步登记料号表 material_master。
 * <ul>
 *   <li>父件（宏丰料号）→ 裸登记 material_no，不写名称；</li>
 *   <li>组成料号 → 回填 品名/规格/尺寸，采「仅回填空白」(preserveDescriptive=true)，不覆盖 Sheet5 权威名称；</li>
 *   <li>使用特性不写 material_type/usage_property；</li>
 *   <li>多父件/多 occurrence 的同组成料号按 material_no 去重、首个非空归并。</li>
 * </ul>
 * 规则出处 {@code docs/table/核价系统Excel导入落库方案.md §6}。
 */
@QuarkusTest
class P06MaterialMasterSyncTest {

    @Inject P06MaterialBomHandler handler;
    @Inject EntityManager em;

    static final String PARENT = "P6MM-PARENT";
    static final String C1 = "P6MM-C1";
    static final String C2 = "P6MM-C2";
    static final java.util.UUID UID = java.util.UUID.fromString("00000000-0000-0000-0000-000000060705");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", PARENT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", PARENT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no IN (:a,:b,:c)")
            .setParameter("a", PARENT).setParameter("b", C1).setParameter("c", C2).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "_GLOBAL_"; c.systemType = "PRICING"; c.importedBy = UID; return c;
    }

    /** 一行 BOM 子件，携带 品名/规格/尺寸。 */
    private SheetRow row(int seq, String comp, String name, String spec, String dim) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", PARENT); m.put("项次", String.valueOf(seq)); m.put("组成料号", comp);
        if (name != null) m.put("品名", name);
        if (spec != null) m.put("规格", spec);
        if (dim  != null) m.put("尺寸", dim);
        m.put("使用特性", "1.银点类");
        m.put("组成用量", "1.0");
        return new SheetRow(seq, m);
    }

    private String[] masterDesc(String no) {
        var r = em.createNativeQuery(
            "SELECT material_name, specification, dimension, material_type, usage_property " +
            "FROM material_master WHERE material_no=:n").setParameter("n", no).getResultList();
        if (r.isEmpty()) return null;
        Object[] o = (Object[]) r.get(0);
        return new String[]{ str(o[0]), str(o[1]), str(o[2]), str(o[3]), str(o[4]) };
    }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    @Transactional void seedMaster(String no, String name, String spec, String dim) {
        em.createNativeQuery("INSERT INTO material_master (id, material_no, material_name, specification, dimension, created_at, updated_at) " +
            "VALUES (gen_random_uuid(), :no, :nm, :sp, :dm, NOW(), NOW()) " +
            "ON CONFLICT (material_no) DO UPDATE SET material_name=:nm, specification=:sp, dimension=:dm")
            .setParameter("no", no).setParameter("nm", name).setParameter("sp", spec).setParameter("dm", dim).executeUpdate();
    }

    @Test void component_registered_withDesc_noType() {
        handler.handle(List.of(row(1, C1, "电阻", "0402", "1x0.5")), ctx());
        String[] d = masterDesc(C1);
        assertNotNull(d, "组成料号应登记进 material_master");
        assertEquals("电阻", d[0]);
        assertEquals("0402", d[1]);
        assertEquals("1x0.5", d[2]);
        assertNull(d[3], "material_type 不由使用特性回填");
        assertNull(d[4], "usage_property 不由使用特性回填");
    }

    @Test void parent_registered_bare() {
        handler.handle(List.of(row(1, C1, "电阻", "0402", null)), ctx());
        String[] d = masterDesc(PARENT);
        assertNotNull(d, "父件宏丰料号应裸登记进 material_master");
        assertNull(d[0], "父件不写名称（本 Sheet 无父件名称列）");
    }

    @Test void preserveDescriptive_keepsAuthoritativeName_backfillsBlanks() {
        // Sheet5 already wrote authoritative name, but left specification blank.
        seedMaster(C1, "客户权威名", null, null);
        handler.handle(List.of(row(1, C1, "BOM粗名", "0402", "1x0.5")), ctx());
        String[] d = masterDesc(C1);
        assertEquals("客户权威名", d[0], "已有名称不被 BOM 覆盖");
        assertEquals("0402", d[1], "原空白规格被 BOM 回填");
        assertEquals("1x0.5", d[2], "原空白尺寸被 BOM 回填");
    }

    @Test void multiOccurrence_dedup_firstNonNull() {
        // 同一组成料号在两行出现：第一行名称空、第二行有名称 → 归并取首个非空。
        handler.handle(List.of(
            row(1, C2, null, "SPEC-A", null),
            row(2, C2, "命名B", null, "DIM-B")), ctx());
        String[] d = masterDesc(C2);
        assertNotNull(d);
        assertEquals("命名B", d[0], "名称首个非空（行2）");
        assertEquals("SPEC-A", d[1], "规格首个非空（行1）");
        assertEquals("DIM-B", d[2], "尺寸首个非空（行2）");
    }
}
