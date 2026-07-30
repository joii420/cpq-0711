package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Task 3 集成测试：Q09 来料回收折扣 → unit_price 版本化（幂等 + 升版）。 */
@QuarkusTest
class Q09IncomingRecoveryHandlerTest {

    @Inject Q09IncomingRecoveryHandler handler;
    @Inject EntityManager em;

    static final String CODE = "TEST-Q09-CODE";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000009");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE code=:c").setParameter("c", CODE).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }
    private SheetRow row(String ratio) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("投入料号", CODE); m.put("宏丰料号", "TEST-Q09-FMN"); m.put("回收折扣", ratio);
        return new SheetRow(1, m);
    }

    /** task-0730：带 项次 / 值 / 货币 / 计价单位 四个新列的行。任一入参传 null 即该列缺省（不写入 Map）。 */
    private SheetRow row(int rowNo, String seq, String ratio, String value, String currency, String unit) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", "TEST-Q09-FMN");
        m.put("投入料号", CODE);
        if (seq != null) m.put("项次", seq);
        if (ratio != null) m.put("回收折扣（%）", ratio);
        if (value != null) m.put("值", value);
        if (currency != null) m.put("货币", currency);
        if (unit != null) m.put("计价单位", unit);
        return new SheetRow(rowNo, m);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> currentRows() {
        return em.createNativeQuery(
            "SELECT seq_no, cost_ratio, pricing_price, currency, unit FROM unit_price " +
            "WHERE code=:c AND is_current=true ORDER BY COALESCE(seq_no, 0)")
            .setParameter("c", CODE).getResultList();
    }
    private String version() {
        List<?> r = em.createNativeQuery(
            "SELECT version_no FROM unit_price WHERE code=:c AND is_current=true LIMIT 1")
            .setParameter("c", CODE).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long total() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM unit_price WHERE code=:c")
            .setParameter("c", CODE).getSingleResult()).longValue();
    }

    @Transactional
    @Test void importTwice_idempotent() {
        handler.handle(List.of(row("0.5")), ctx());
        handler.handle(List.of(row("0.5")), ctx());
        assertEquals("2000", version());
        assertEquals(1L, total());
    }
    @Transactional
    @Test void changeValue_bumps() {
        handler.handle(List.of(row("0.5")), ctx());
        handler.handle(List.of(row("0.7")), ctx());
        assertEquals("2001", version());
        assertEquals(2L, total());
    }

    // ==================== task-0730：项次 / 值 / 货币 / 计价单位 ====================

    /** 值与回收折扣（%）并存：两列同时落库，货币/计价单位一并写入，项次为空落 NULL。 */
    @Transactional
    @Test void valueAndRatio_coexist() {
        var r = handler.handle(List.of(row(1, null, "20", "3.5", "CNY", "KG")), ctx());
        assertEquals(0, r.failedRows, "并存不应报错");
        List<Object[]> rows = currentRows();
        assertEquals(1, rows.size());
        assertNull(rows.get(0)[0], "项次为空 → seq_no NULL（不补号）");
        assertEquals(0, new BigDecimal("20").compareTo((BigDecimal) rows.get(0)[1]), "cost_ratio");
        assertEquals(0, new BigDecimal("3.5").compareTo((BigDecimal) rows.get(0)[2]), "pricing_price");
        assertEquals("CNY", rows.get(0)[3]);
        assertEquals("KG", rows.get(0)[4]);
    }

    /** 只填「值」不填折扣%（反之亦然）合法——必填其一，不是两者都要。 */
    @Transactional
    @Test void valueOnly_or_ratioOnly_accepted() {
        assertEquals(0, handler.handle(List.of(row(1, null, null, "3.5", null, null)), ctx()).failedRows);
        assertEquals(1, currentRows().size());
        cleanup();
        assertEquals(0, handler.handle(List.of(row(1, null, "20", null, null, null)), ctx()).failedRows);
        assertEquals(1, currentRows().size());
    }

    /** 值与折扣%同时为空 → 拒绝该行，不写库。 */
    @Transactional
    @Test void bothEmpty_rejected() {
        var r = handler.handle(List.of(row(1, "1", null, null, "CNY", "KG")), ctx());
        assertEquals(1, r.failedRows);
        assertEquals(0L, total(), "被拒行不得落库");
    }

    /**
     * 核心：项次为 NULL 的重复行走 upsert（末值胜），不抛唯一键冲突。
     * 去重键 COALESCE(seq_no,0) → 两行同键 → 只落最后一行的值。
     */
    @Transactional
    @Test void nullSeqNo_duplicateRows_lastWins() {
        var r = handler.handle(List.of(
            row(1, null, "20", null, "CNY", "KG"),
            row(2, null, "35", null, "USD", "PCS")), ctx());
        assertEquals(0, r.failedRows, "重复项次必须 upsert 覆盖，不得撞唯一键报错");
        List<Object[]> rows = currentRows();
        assertEquals(1, rows.size(), "同键只落一条");
        assertEquals(0, new BigDecimal("35").compareTo((BigDecimal) rows.get(0)[1]), "末值胜");
        assertEquals("USD", rows.get(0)[3], "末值胜（货币一并覆盖）");
    }

    /** NULL 与 0 视为同一去重键——精确镜像 uq_unit_price 的 COALESCE(seq_no,0)。 */
    @Transactional
    @Test void nullSeqNo_and_zero_collideAsSameKey() {
        var r = handler.handle(List.of(
            row(1, null, "20", null, null, null),
            row(2, "0", "35", null, null, null)), ctx());
        assertEquals(0, r.failedRows);
        assertEquals(1, currentRows().size(), "NULL 与 0 必须同键，否则仍会撞 uq");
    }

    /** 项次不同 → 各自成行，同一 (成品, 投入料号) 下可存多条。 */
    @Transactional
    @Test void differentSeqNo_bothStored() {
        var r = handler.handle(List.of(
            row(1, "1", "20", null, null, null),
            row(2, "2", "35", null, null, null)), ctx());
        assertEquals(0, r.failedRows);
        List<Object[]> rows = currentRows();
        assertEquals(2, rows.size());
        assertEquals(1, ((Number) rows.get(0)[0]).intValue());
        assertEquals(2, ((Number) rows.get(1)[0]).intValue());
    }

    /** 仅「值」变化也必须触发升版（pricing_price 已在 CONTENT 内，不会被静默吞掉）。 */
    @Transactional
    @Test void changePricingPriceOnly_bumps() {
        handler.handle(List.of(row(1, null, "20", "3.5", "CNY", "KG")), ctx());
        assertEquals("2000", version());
        handler.handle(List.of(row(1, null, "20", "9.9", "CNY", "KG")), ctx());
        assertEquals("2001", version(), "只改「值」必须升版");
    }
}
