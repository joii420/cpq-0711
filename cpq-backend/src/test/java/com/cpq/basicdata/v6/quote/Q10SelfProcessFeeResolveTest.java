package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
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

@QuarkusTest
class Q10SelfProcessFeeResolveTest {

    @Inject Q10SelfProcessFeeHandler handler;
    @Inject EntityManager em;

    static final String CUST = "Q10CUST0617";
    static final String FIN  = "Q10FIN0617";
    static final String NAME = "Q10-耗材-0617";

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE customer_no=:c").setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_name=:n OR material_no LIKE '9%'")
          .setParameter("n", NAME).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow row(String code, String name) {
        return row(code, name, FIN, "OP10");
    }
    private SheetRow row(String code, String name, String finished, String op) {
        Map<String, String> m = new LinkedHashMap<>();
        if (code != null) m.put("投入料号", code);
        if (name != null) m.put("投入料号名称", name);
        if (finished != null) m.put("宏丰料号", finished);
        m.put("工序编号", op); m.put("项次（一级）", "1");
        m.put("值", "12.5"); m.put("货币", "CNY"); m.put("计价单位", "PCS");
        return new SheetRow(1, m);
    }
    private long masterCount(String name) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_master WHERE material_name=:n")
            .setParameter("n", name).getSingleResult()).longValue();
    }
    @SuppressWarnings("unchecked")
    private String upCode() {
        List<Object> rows = em.createNativeQuery("SELECT code FROM unit_price WHERE customer_no=:c AND is_current=TRUE")
            .setParameter("c", CUST).getResultList();
        return rows.stream().findFirst().map(Object::toString).orElse(null);
    }

    @Test
    void emptyCodeWithName_generatesRegistersAndUsesAsCode() {
        SheetImportResult r = handler.handle(List.of(row(null, NAME)), ctx());
        assertEquals(0, r.failedRows);
        assertEquals(1L, masterCount(NAME), "新料件登记进料号表(type=3)");
        String generated = upCode();
        assertNotNull(generated, "生成号回填为 unit_price.code");
        assertTrue(generated.matches("^\\d{4}-\\d{10}$"), "生成号需为报价料号格式(XXXX-YYMMNNNNNN)，实得: " + generated);
    }

    @Test
    void emptyCodeAndEmptyName_fallbackToFinishedMaterialNo() {
        // §10 规则3：投入料号 + 名称都空 → code 兜底为宏丰料号（针对成品整体的自制加工费）
        SheetImportResult r = handler.handle(List.of(row(null, null)), ctx());
        assertEquals(0, r.failedRows, "成品料号有值时兜底落库，不报错");
        assertEquals(1, r.successRows);
        assertEquals(FIN, upCode(), "code 兜底为宏丰料号");
    }

    @Test
    void emptyCodeNameAndFinished_recordsError() {
        // 投入料号 + 名称 + 宏丰料号 全空 → 无从确定 code，拒绝该行
        SheetImportResult r = handler.handle(List.of(row(null, null, null, "OP10")), ctx());
        assertTrue(r.failedRows >= 1);
        assertNull(upCode());
    }

    @Test
    void duplicateNoInputSameFinished_secondRejected() {
        // fail-fast：同一成品多条无投入料号加工费（工序不同）→ 第二条判非法拒绝，避免唯一键塌缩
        SheetImportResult r = handler.handle(
            List.of(row(null, null, FIN, "OP10"), row(null, null, FIN, "OP20")), ctx());
        assertEquals(1, r.successRows, "仅第一条落库");
        assertEquals(1, r.failedRows, "第二条同成品无投入料号行被拒");
        assertEquals(FIN, upCode());
    }
}
