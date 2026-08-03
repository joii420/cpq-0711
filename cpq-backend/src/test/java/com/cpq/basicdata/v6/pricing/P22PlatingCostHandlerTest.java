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

/** repair-0802：P22 电镀成本 —— code=投入料号(零件)、finished_material_no=销售料号(成品)。 */
@QuarkusTest
class P22PlatingCostHandlerTest {

    @Inject P22PlatingCostHandler handler;
    @Inject EntityManager em;

    static final String SALES_NO = "TEST-P22-SALES";
    static final String INPUT_NO = "TEST-P22-INPUT";

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE finished_material_no=:f OR code=:f")
            .setParameter("f", SALES_NO).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.systemType = "PRICING";
        return c;
    }

    private SheetRow row(int rowNo, String inputNo, String inputName, String proc, String mat) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("生产料号", "PROD-1");
        m.put("销售料号", SALES_NO);
        if (inputNo != null) m.put("投入料号", inputNo);
        if (inputName != null) m.put("投入料号名称", inputName);
        m.put("电镀加工费", proc); m.put("电镀材料费", mat);
        m.put("货币", "CNY"); m.put("计价单位", "PCS"); m.put("不良率（%）", "0.01");
        return new SheetRow(rowNo, m);
    }

    private List<Object[]> currentRows() {
        @SuppressWarnings("unchecked")
        List<Object[]> r = em.createNativeQuery(
            "SELECT code, cost_type, pricing_price, version_no FROM unit_price "
          + "WHERE finished_material_no=:f AND is_current=true ORDER BY code, cost_type")
            .setParameter("f", SALES_NO).getResultList();
        return r;
    }

    @Transactional
    @Test void inputPartNo_landsInCode_salesNoAnchorsGroup() {
        handler.handle(List.of(row(1, INPUT_NO, "投入零件X", "5", "3")), ctx());
        List<Object[]> rows = currentRows();
        assertEquals(2, rows.size(), "一行拆两条 cost_type");
        assertEquals(INPUT_NO, String.valueOf(rows.get(0)[0]), "code = 投入料号");
        assertEquals("2000", String.valueOf(rows.get(0)[3]));
    }

    @Transactional
    @Test void bothColumnsBlank_fallsBackToSalesNo_noError() {
        var result = handler.handle(List.of(row(1, null, null, "5", "3")), ctx());
        assertEquals(0, result.failedRows, "两列皆空是合法输入(非必填)");
        assertEquals(1, result.successRows);
        List<Object[]> rows = currentRows();
        assertEquals(2, rows.size());
        assertEquals(SALES_NO, String.valueOf(rows.get(0)[0]), "code 回退为销售料号");
    }

    @Transactional
    @Test void twoInputParts_shareOneVersionGroup() {
        handler.handle(List.of(row(1, INPUT_NO, null, "5", "3"),
                               row(2, INPUT_NO + "-B", null, "7", "4")), ctx());
        List<Object[]> rows = currentRows();
        assertEquals(4, rows.size(), "2 个投入料号 × 2 个 cost_type，互不覆盖");
        for (Object[] r : rows) assertEquals("2000", String.valueOf(r[3]), "同一销售料号共享一个版本组");
    }

    @Transactional
    @Test void importTwice_isIdempotent_thenBumpsOnChange() {
        handler.handle(List.of(row(1, INPUT_NO, null, "5", "3")), ctx());
        handler.handle(List.of(row(1, INPUT_NO, null, "5", "3")), ctx());
        assertEquals(2, currentRows().size(), "重复导入不翻倍");
        assertEquals("2000", String.valueOf(currentRows().get(0)[3]));

        handler.handle(List.of(row(1, INPUT_NO, null, "9", "3")), ctx());
        for (Object[] r : currentRows()) {
            assertEquals("2001", String.valueOf(r[3]), "任一明细变化 → 整组升版(与 P15 同构)");
        }
    }
}
