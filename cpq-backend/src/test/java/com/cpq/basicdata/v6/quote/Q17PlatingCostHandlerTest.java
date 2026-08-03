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

/** Task 3 集成测试：Q17 电镀费用（一行拆加工费/材料费两组 + 忽略 Excel 版本号 + 跳过电镀方案行）。 */
@QuarkusTest
class Q17PlatingCostHandlerTest {

    @Inject Q17PlatingCostHandler handler;
    @Inject EntityManager em;

    static final String SALES_NO = "TEST-Q17-SALES";     // 销售料号 → finished_material_no
    static final String INPUT_NO = "TEST-Q17-INPUT";     // 投入料号 → code
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000017");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE code IN (:a,:b) OR finished_material_no=:a")
            .setParameter("a", SALES_NO).setParameter("b", INPUT_NO).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }

    /** inputNo/inputName 传 null 表示该列不存在（模拟老模板/空单元格）。 */
    private SheetRow row(String inputNo, String inputName, String process, String material,
                         String excelVersion, String platingSchemeNo) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", SALES_NO);
        if (inputNo != null) m.put("投入料号", inputNo);
        if (inputName != null) m.put("投入料号名称", inputName);
        if (platingSchemeNo != null) m.put("电镀方案编号", platingSchemeNo);
        if (excelVersion != null) m.put("版本编号", excelVersion);
        m.put("电镀加工费", process); m.put("电镀材料费", material);
        m.put("货币", "CNY"); m.put("计价单位", "PCS"); m.put("不良率", "0.01");
        return new SheetRow(1, m);
    }

    private String version(String code, String costType) {
        List<?> r = em.createNativeQuery(
            "SELECT version_no FROM unit_price WHERE code=:c AND cost_type=:ct AND is_current=true LIMIT 1")
            .setParameter("c", code).setParameter("ct", costType).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long total() {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE code IN (:a,:b)")
            .setParameter("a", SALES_NO).setParameter("b", INPUT_NO).getSingleResult()).longValue();
    }
    private String fmnOf(String code) {
        List<?> r = em.createNativeQuery(
            "SELECT finished_material_no FROM unit_price WHERE code=:c AND is_current=true LIMIT 1")
            .setParameter("c", code).getResultList();
        return r.isEmpty() || r.get(0) == null ? null : String.valueOf(r.get(0));
    }

    @Transactional
    @Test void importTwice_idempotent_twoCostTypes_ignoreExcelVersion() {
        handler.handle(List.of(row(null, null, "5", "3", "V99", null)), ctx());
        handler.handle(List.of(row(null, null, "5", "3", "V99", null)), ctx());
        assertEquals("2000", version(SALES_NO, "电镀加工费"), "version 系统生成, 忽略 Excel 'V99'");
        assertEquals("2000", version(SALES_NO, "电镀材料费"));
        assertEquals(2L, total(), "一行拆两条, 导两遍不翻倍");
    }

    @Transactional
    @Test void changeOneFee_bumpsOnlyThatCostType() {
        handler.handle(List.of(row(null, null, "5", "3", null, null)), ctx());
        handler.handle(List.of(row(null, null, "9", "3", null, null)), ctx());
        assertEquals("2001", version(SALES_NO, "电镀加工费"), "加工费升版");
        assertEquals("2000", version(SALES_NO, "电镀材料费"), "材料费不变");
        assertEquals(3L, total(), "加工费 2 行(2000下线+2001生效) + 材料费 1 行");
    }

    @Transactional
    @Test void platingSchemeNo_skipsRow() {
        handler.handle(List.of(row(null, null, "5", "3", null, "SCHEME-1")), ctx());
        assertEquals(0L, total(), "有电镀方案编号 → 整行跳过, 不写 unit_price");
    }

    @Transactional
    @Test void inputPartNo_landsInCode_salesNoLandsInFinishedMaterialNo() {
        handler.handle(List.of(row(INPUT_NO, "投入零件X", "5", "3", null, null)), ctx());
        assertEquals("2000", version(INPUT_NO, "电镀加工费"), "code = 投入料号");
        assertEquals(SALES_NO, fmnOf(INPUT_NO), "finished_material_no = 销售料号");
        assertNull(version(SALES_NO, "电镀加工费"), "销售料号不再写进 code");
        assertEquals(2L, total(), "一行拆两条");
    }

    @Transactional
    @Test void bothColumnsBlank_fallsBackToSalesNo_noError() {
        var result = handler.handle(List.of(row(null, null, "5", "3", null, null)), ctx());
        assertEquals(0, result.failedRows, "两列皆空是合法输入(非必填)，不得报错");
        assertEquals(1, result.successRows);
        assertEquals("2000", version(SALES_NO, "电镀加工费"), "code 回退为销售料号");
        assertEquals(SALES_NO, fmnOf(SALES_NO), "finished_material_no 仍写销售料号");
    }

    @Transactional
    @Test void sameSalesNo_twoInputParts_coexistWithoutOverwrite() {
        Map<String, String> m2 = new LinkedHashMap<>();
        m2.put("宏丰料号", SALES_NO); m2.put("投入料号", INPUT_NO + "-B");
        m2.put("电镀加工费", "7"); m2.put("电镀材料费", "4");
        m2.put("货币", "CNY"); m2.put("计价单位", "PCS"); m2.put("不良率", "0.01");
        handler.handle(List.of(row(INPUT_NO, null, "5", "3", null, null), new SheetRow(2, m2)), ctx());

        long n = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE finished_material_no=:f AND is_current=true")
            .setParameter("f", SALES_NO).getSingleResult()).longValue();
        assertEquals(4L, n, "两个投入料号 × 两个 cost_type = 4 行，互不覆盖");
        em.createNativeQuery("DELETE FROM unit_price WHERE code=:c")
            .setParameter("c", INPUT_NO + "-B").executeUpdate();
    }
}
