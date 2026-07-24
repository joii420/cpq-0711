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

/** Task 5 集成测试：Q04 物料与元素BOM 主从版本化（element_bom_item uq 含 characteristic → 多版本保留）。 */
@QuarkusTest
class Q04ElementBomHandlerTest {

    @Inject Q04ElementBomHandler handler;
    @Inject EntityManager em;

    static final String MAT = "TEST-Q04-MAT";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM element_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM element_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }
    private SheetRow row(int seq, String element, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("投入料号", MAT); m.put("项次", String.valueOf(seq)); m.put("元素", element);
        m.put("组成含量", content); m.put("损耗率", "0.01"); m.put("毛用量", "1"); m.put("毛用量单位", "G"); m.put("净用量", "0.99");
        return new SheetRow(seq, m);
    }
    /** 单位测试专用行：可独立设置毛用量单位 / 净用量单位（null = 该列不出现，"" = 空白单元格）。 */
    private SheetRow rowUnits(int seq, String element, String grossUnit, String netUnit) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("投入料号", MAT); m.put("项次", String.valueOf(seq)); m.put("元素", element);
        m.put("组成含量", "50"); m.put("损耗率", "0.01"); m.put("毛用量", "1"); m.put("净用量", "0.99");
        if (grossUnit != null) m.put("毛用量单位", grossUnit);
        if (netUnit != null) m.put("净用量单位", netUnit);
        return new SheetRow(seq, m);
    }
    private String issueUnit(String element) {
        List<?> r = em.createNativeQuery(
            "SELECT issue_unit FROM element_bom_item WHERE material_no=:m AND component_no=:c AND is_current=true LIMIT 1")
            .setParameter("m", MAT).setParameter("c", element).getResultList();
        return r.isEmpty() ? null : (r.get(0) == null ? null : String.valueOf(r.get(0)));
    }
    private String characteristic() {
        List<?> r = em.createNativeQuery(
            "SELECT characteristic FROM element_bom WHERE material_no=:m AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long childCount(String extra) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM element_bom_item WHERE material_no=:m"
            + (extra == null ? "" : " AND " + extra)).setParameter("m", MAT).getSingleResult()).longValue();
    }

    @Transactional
    @Test void importTwice_idempotent() {
        handler.handle(List.of(row(1, "Ag", "75"), row(2, "Ni", "25")), ctx());
        handler.handle(List.of(row(1, "Ag", "75"), row(2, "Ni", "25")), ctx());
        assertEquals("2000", characteristic());
        assertEquals(2L, childCount("is_current=true"));
        assertEquals(2L, childCount(null));
    }
    @Transactional
    @Test void changeChild_bumps_keepsMultiVersion() {
        handler.handle(List.of(row(1, "Ag", "75"), row(2, "Ni", "25")), ctx());
        handler.handle(List.of(row(1, "Ag", "70"), row(2, "Ni", "30")), ctx());
        assertEquals("2001", characteristic());
        assertEquals(2L, childCount("is_current=true"), "仅新版本生效");
        assertEquals(4L, childCount(null), "旧版本子表保留（uq 含 characteristic）");
    }

    /**
     * 净用量单位非空替换毛用量单位 → issue_unit 真值表（仅 element_bom_item / 报价导入）。
     * 一次导入多元素，每个元素覆盖一种组合，互不串扰（去重键 = 项次+元素）。
     */
    @Transactional
    @Test void issueUnit_netUnitOverridesGrossUnit_truthTable() {
        handler.handle(List.of(
            rowUnits(1, "Ag", "PCS", "KG"),   // 毛非空 + 净非空 → 净优先
            rowUnits(2, "Ni", "PCS", ""),     // 毛非空 + 净空白 → 回退毛
            rowUnits(3, "Cu", "PCS", null),   // 毛非空 + 净列缺失 → 回退毛
            rowUnits(4, "Zn", "", "KG"),      // 毛空白 + 净非空 → 净
            rowUnits(5, "Sn", "", ""),        // 毛空白 + 净空白 → null
            rowUnits(6, "Au", "PCS", "  ")    // 净纯空格 → trim 后空 → 回退毛
        ), ctx());

        assertEquals("KG", issueUnit("Ag"), "净非空时净优先");
        assertEquals("PCS", issueUnit("Ni"), "净空字符串 → 回退毛");
        assertEquals("PCS", issueUnit("Cu"), "净列缺失 → 回退毛");
        assertEquals("KG", issueUnit("Zn"), "毛空净非空 → 净");
        assertNull(issueUnit("Sn"), "两者皆空 → null");
        assertEquals("PCS", issueUnit("Au"), "净纯空格 trim 后视同空 → 回退毛");
    }
}
