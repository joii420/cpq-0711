package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** P06 物料BOM (PRICING) 主从版本化：2000 起、内容变升版、is_current 唯一、与 QUOTE 独立。 */
@QuarkusTest
class P06MaterialBomHandlerTest {

    @Inject P06MaterialBomHandler handler;
    @Inject EntityManager em;

    static final String MAT = "TEST-P06-MAT";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000006");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "_GLOBAL_"; c.systemType = "PRICING"; c.importedBy = UID; return c;
    }
    private SheetRow row(int seq, String qty) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("组成料号", "COMP" + seq); m.put("项次", String.valueOf(seq));
        m.put("组成用量", qty); m.put("损耗率", "0.01"); m.put("不良率", "0.02");
        return new SheetRow(seq, m);
    }

    /** 可指定「计算类型」的行夹具（三态统一）。 */
    private SheetRow rowCalc(int seq, String qty, String calcType) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("组成料号", "COMP" + seq); m.put("项次", String.valueOf(seq));
        m.put("组成用量", qty); m.put("损耗率", "0.01"); m.put("不良率", "0.02");
        m.put("计算类型", calcType);
        return new SheetRow(seq, m);
    }

    /** 取当前生效子行的 (component_no, characteristic)，按 component_no 排序。 */
    private List<Object[]> currentChildren() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
            "SELECT component_no, characteristic FROM material_bom_item " +
            "WHERE material_no=:m AND system_type='PRICING' AND is_current=true ORDER BY component_no")
            .setParameter("m", MAT).getResultList();
        return rows;
    }

    private long childCurrentCount() {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom_item WHERE material_no=:m AND system_type='PRICING' AND is_current=true")
            .setParameter("m", MAT).getSingleResult()).longValue();
    }

    @Transactional void forceChildCharacteristicNull() {
        em.createNativeQuery(
            "UPDATE material_bom_item SET characteristic = NULL " +
            "WHERE material_no=:m AND system_type='PRICING'")
            .setParameter("m", MAT).executeUpdate();
    }

    private String version() {
        List<?> r = em.createNativeQuery(
            "SELECT bom_version FROM material_bom WHERE material_no=:m AND system_type='PRICING' AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long masterCount(String extra) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_bom WHERE material_no=:m AND system_type='PRICING'"
            + (extra == null ? "" : " AND " + extra)).setParameter("m", MAT).getSingleResult()).longValue();
    }

    @Test void firstImport_isVersion2000_notV1() {
        handler.handle(List.of(row(1, "1.0"), row(2, "2.0")), ctx());
        assertEquals("2000", version(), "首版应为 2000，不是 V1");
        assertEquals(1L, masterCount("is_current=true"));
    }
    @Test void changeContent_bumpsTo2001_currentUnique() {
        handler.handle(List.of(row(1, "1.0")), ctx());
        handler.handle(List.of(row(1, "9.0")), ctx());   // 用量变化 → 升版
        assertEquals("2001", version());
        assertEquals(1L, masterCount("is_current=true"), "仅一条当前生效");
        assertEquals(2L, masterCount(null), "两版本保留 2000/2001");
    }
    @Test void sameContent_reusesVersion() {
        handler.handle(List.of(row(1, "1.0")), ctx());
        handler.handle(List.of(row(1, "1.0")), ctx());
        assertEquals("2000", version());
        assertEquals(1L, masterCount(null));
    }

    // ===== 三态统一 =====

    @Test void elementRow_isRecipe_materialRow_isAssembly() {
        handler.handle(List.of(rowCalc(1, "1.0", "元素"), rowCalc(2, "2.0", "材料")), ctx());

        List<Object[]> rows = currentChildren();
        assertEquals(2, rows.size());
        assertEquals("COMP1", rows.get(0)[0]);
        assertEquals("RECIPE", rows.get(0)[1], "calc_type='元素' → characteristic=RECIPE");
        assertEquals("COMP2", rows.get(1)[0]);
        assertEquals("ASSEMBLY", rows.get(1)[1], "calc_type='材料' → characteristic=ASSEMBLY");
    }

    @Test void nullCalcType_defaultsToAssembly() {
        handler.handle(List.of(row(1, "1.0")), ctx());   // row() 不带「计算类型」
        assertEquals("ASSEMBLY", currentChildren().get(0)[1], "calc_type 缺省按 ASSEMBLY 处理");
    }

    /**
     * 风险 B 回归：模拟"存量 characteristic=NULL 的行遇到新代码"。
     * childGk 若仍含 characteristic=null，flip 匹配不到已有值的行 → 双 current。
     */
    @Test void legacyNullCharacteristicRows_areFlipped_noDoubleCurrent() {
        handler.handle(List.of(rowCalc(1, "1.0", "材料")), ctx());
        assertEquals(1L, childCurrentCount());

        forceChildCharacteristicNull();          // 退回迁移前状态
        handler.handle(List.of(rowCalc(1, "1.0", "材料")), ctx());

        assertEquals(1L, childCurrentCount(),
            "风险B: 旧 NULL 行必须被 flip 下线，不能与新行并存为双 current");
        assertEquals("ASSEMBLY", currentChildren().get(0)[1]);
    }
}
