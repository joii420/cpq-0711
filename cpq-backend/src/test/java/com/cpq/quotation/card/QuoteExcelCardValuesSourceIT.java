package com.cpq.quotation.card;

import com.cpq.quotation.service.CardSnapshotService;
import com.cpq.quotation.service.ExcelViewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260918 (BL-0304): the quote side of every "backend computes Excel column values" entry
 * reads the line's own {@code quote_card_values} (authoritative card values), never
 * {@code quotation_line_component_data.row_data}.
 *
 * <p>Fixture principle: the card values and row_data are deliberately DIFFERENT for every
 * referenced column, so an assertion can tell which source was read. Card-values JSON mirrors the
 * production shape (decimal strings; baseRows / editRows / formulaResults / resolvedRows /
 * subtotal / subtotalByColumn), copied from QT-20260916-0881's real structure (AP-59).
 *
 * <p>Tabs (all in one DRAFT template carrying components_snapshot, so unit conversion is active):
 * <ul>
 *   <li>物料 (MAT, sortOrder 0): card 材料成本 rows 1.5 + 0.478941064 = 1.978941064, 回收成本 0;
 *       row_data 材料成本 0.000066807 + 0.000802284, 回收成本 0.3</li>
 *   <li>组装 (ASM, sortOrder 1): 加工费 unit_source_field=计价单位, card row 39.54034733 KPCS
 *       ⇒ converted 0.03954034733; row_data 加工费 99</li>
 *   <li>产品小计 (SUB, SUBTOTAL, sortOrder 2): card subtotal 1.804589425; row_data subtotal 39.5</li>
 * </ul>
 *
 * <p>Data lives in the test DB (cpq_db_test) only for the duration of each test; {@link #cleanup}
 * deletes exactly the ids created here.
 */
@QuarkusTest
@TestProfile(com.cpq.quotation.task260910.Task260910StatsProfile.class) // Hibernate statistics for the SQL-count test
class QuoteExcelCardValuesSourceIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Fixed text from api.md — must match verbatim. */
    private static final String UNAVAILABLE_MSG = "样本卡片的卡片值不可用（尚未计算或计算失败），无法试算";

    private static final UUID MAT = UUID.fromString("26091801-0000-4000-8000-000000000001");
    private static final UUID ASM = UUID.fromString("26091801-0000-4000-8000-000000000002");
    private static final UUID SUB = UUID.fromString("26091801-0000-4000-8000-000000000003");

    @Inject ExcelViewService excelViewService;
    @Inject CardSnapshotService cardSnapshotService;
    @Inject EntityManager em;

    private UUID customerId;
    private UUID templateId;
    private UUID quotationId;
    private final List<UUID> lineIds = new ArrayList<>();

    // ---------------------------------------------------------------- columns under test

    private static final String TAB_MAT = "{\"alias\":\"物料\",\"tabKey\":\"" + MAT + "\",\"rowKeyFields\":[\"销售料号\",\"料号\"]}";
    private static final String TAB_ASM = "{\"alias\":\"组装\",\"tabKey\":\"" + ASM + "\",\"rowKeyFields\":[\"销售料号\"]}";
    private static final String TAB_SUB = "{\"alias\":\"产品小计\",\"tabKey\":\"" + SUB + "\",\"rowKeyFields\":[]}";

    private static final String COLUMNS = "["
        + "{\"col_key\":\"c_mat\",\"title\":\"材料成本\",\"source_type\":\"TAB_JOIN_FORMULA\",\"expression\":\"[物料.材料成本]\",\"tabs\":[" + TAB_MAT + "]},"
        + "{\"col_key\":\"c_mat_sub\",\"title\":\"材料成本小计\",\"source_type\":\"TAB_JOIN_FORMULA\",\"expression\":\"[物料.材料成本(小计)]\",\"tabs\":[" + TAB_MAT + "]},"
        + "{\"col_key\":\"c_rec\",\"title\":\"回收价格\",\"source_type\":\"TAB_JOIN_FORMULA\",\"expression\":\"[物料.回收成本]\",\"tabs\":[" + TAB_MAT + "]},"
        + "{\"col_key\":\"c_asm\",\"title\":\"组装加工费\",\"source_type\":\"TAB_JOIN_FORMULA\",\"expression\":\"[组装.加工费]\",\"tabs\":[" + TAB_ASM + "]},"
        + "{\"col_key\":\"c_total\",\"title\":\"产品单价\",\"source_type\":\"TAB_JOIN_FORMULA\",\"expression\":\"[产品小计(总计)]\",\"tabs\":[" + TAB_SUB + "]},"
        + "{\"col_key\":\"c_card\",\"title\":\"卡片公式\",\"source_type\":\"CARD_FORMULA\",\"formula\":\"=[产品小计.小计]\","
        +   "\"refs\":{\"产品小计.小计\":{\"tab\":\"" + SUB + ":2\",\"field\":\"__subtotal__\"}}},"
        + "{\"col_key\":\"c_fixed\",\"title\":\"固定值\",\"source_type\":\"FIXED_VALUE\",\"fixed_value\":\"F\"}"
        + "]";

    private static final String COMPONENTS_SNAPSHOT = "["
        + "{\"componentId\":\"" + MAT + "\",\"sortOrder\":0,\"componentType\":\"NORMAL\",\"fields\":["
        +   "{\"name\":\"销售料号\"},{\"name\":\"料号\"},{\"name\":\"材料成本\"},{\"name\":\"回收成本\"}]},"
        + "{\"componentId\":\"" + ASM + "\",\"sortOrder\":1,\"componentType\":\"NORMAL\",\"fields\":["
        +   "{\"name\":\"销售料号\"},{\"name\":\"加工费\",\"unit_source_field\":\"计价单位\"},{\"name\":\"计价单位\"}]},"
        + "{\"componentId\":\"" + SUB + "\",\"sortOrder\":2,\"componentType\":\"SUBTOTAL\",\"fields\":[]}"
        + "]";

    /** Production-shaped card values; {@code matRow1} lets a test vary 材料成本 of the first 物料 row. */
    private static String cardValues(String matRow1, String matSubtotal) {
        return "{\"tabs\":["
            + "{\"tabName\":\"物料\",\"componentId\":\"" + MAT + "\",\"componentType\":\"NORMAL\","
            +   "\"baseRows\":[{\"driverRow\":{\"销售料号\":\"P1\",\"料号\":\"M1\"},\"basicDataValues\":{}},"
            +               "{\"driverRow\":{\"销售料号\":\"P1\",\"料号\":\"M2\"},\"basicDataValues\":{}}],"
            +   "\"editRows\":[],"
            +   "\"formulaResults\":[{\"rowKey\":\"P1||M1\",\"values\":{\"材料成本\":\"" + matRow1 + "\",\"回收成本\":\"0\"}},"
            +                     "{\"rowKey\":\"P1||M2\",\"values\":{\"材料成本\":\"0.478941064\",\"回收成本\":\"0\"}}],"
            +   "\"resolvedRows\":[{\"销售料号\":\"P1\",\"料号\":\"M1\",\"材料成本\":\"" + matRow1 + "\",\"回收成本\":\"0\"},"
            +                   "{\"销售料号\":\"P1\",\"料号\":\"M2\",\"材料成本\":\"0.478941064\",\"回收成本\":\"0\"}],"
            +   "\"subtotal\":\"" + matSubtotal + "\","
            +   "\"subtotalByColumn\":{\"材料成本\":\"" + matSubtotal + "\",\"回收成本\":\"0\"}},"
            + "{\"tabName\":\"组装加工费\",\"componentId\":\"" + ASM + "\",\"componentType\":\"NORMAL\","
            +   "\"baseRows\":[{\"driverRow\":{\"销售料号\":\"P1\"},\"basicDataValues\":{}}],"
            +   "\"editRows\":[],"
            +   "\"formulaResults\":[{\"rowKey\":\"P1\",\"values\":{}}],"
            +   "\"resolvedRows\":[{\"销售料号\":\"P1\",\"加工费\":\"39.54034733\",\"计价单位\":\"KPCS\"}],"
            +   "\"subtotal\":\"0.03954034733\",\"subtotalByColumn\":{\"加工费\":\"0.03954034733\"}},"
            + "{\"tabName\":\"产品小计\",\"componentId\":\"" + SUB + "\",\"componentType\":\"SUBTOTAL\","
            +   "\"baseRows\":[],\"editRows\":[],\"formulaResults\":[],\"resolvedRows\":[],"
            +   "\"subtotal\":\"1.804589425\"}"
            + "]}";
    }

    private static final String CARD_OK = cardValues("1.5", "1.978941064");

    /** row_data per tab — every referenced number differs from the card values. */
    private static final String ROW_MAT = "[{\"销售料号\":\"P1\",\"料号\":\"M1\",\"材料成本\":\"0.000066807\",\"回收成本\":\"0.3\"},"
        + "{\"销售料号\":\"P1\",\"料号\":\"M2\",\"材料成本\":\"0.000802284\",\"回收成本\":\"0\"}]";
    private static final String ROW_ASM = "[{\"销售料号\":\"P1\",\"加工费\":\"99\",\"计价单位\":\"KPCS\"}]";
    private static final String ROW_SUB = "[]";

    // ---------------------------------------------------------------- fixture

    @BeforeEach
    void seed() {
        lineIds.clear();
        customerId = UUID.randomUUID();
        templateId = UUID.randomUUID();
        quotationId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            UUID salesRepId = (UUID) em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getSingleResult();
            em.createNativeQuery("INSERT INTO customer (id, code, name) VALUES (?1, ?2, 'RP260918 cust')")
                .setParameter(1, customerId)
                .setParameter(2, "RP0918-" + customerId.toString().substring(0, 8))
                .executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO template (id, template_series_id, name, status, formulas,
                      template_sql_views_snapshot, excel_view_config, components_snapshot, created_at, updated_at)
                    VALUES (?1, ?2, 'RP260918 tmpl', 'DRAFT', '[]', '{}',
                      CAST(?3 AS jsonb), CAST(?4 AS jsonb), now(), now())
                    """)
                .setParameter(1, templateId)
                .setParameter(2, UUID.randomUUID())
                .setParameter(3, COLUMNS)
                .setParameter(4, COMPONENTS_SNAPSHOT)
                .executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status,
                      total_amount, original_amount, system_discount_rate, final_discount_rate, tax_rate, tax_amount,
                      customer_template_id, created_at, updated_at)
                    VALUES (?1, ?2, ?3, 'RP260918 quote', ?4, 'DRAFT', 0, 0, 100, 100, 0, 0, ?5, now(), now())
                    """)
                .setParameter(1, quotationId)
                .setParameter(2, "RP0918-" + quotationId)
                .setParameter(3, customerId)
                .setParameter(4, salesRepId)
                .setParameter(5, templateId)
                .executeUpdate();
        });
    }

    /** Adds a line (sort order = current count) with the given quote_card_values (null → SQL NULL). */
    private UUID addLine(String quoteCardValues) {
        UUID lineId = UUID.randomUUID();
        int sort = lineIds.size();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                    INSERT INTO quotation_line_item (id, quotation_id, template_id, product_attribute_values,
                      composite_type, subtotal, sort_order, quote_card_values, created_at)
                    VALUES (?1, ?2, ?3, '{}', 'SIMPLE', 0, ?4, CAST(?5 AS jsonb), now())
                    """)
                .setParameter(1, lineId)
                .setParameter(2, quotationId)
                .setParameter(3, templateId)
                .setParameter(4, sort)
                .setParameter(5, quoteCardValues)
                .executeUpdate();
            insertCd(lineId, MAT, "物料", ROW_MAT, "0.000869091", 0);
            insertCd(lineId, ASM, "组装加工费", ROW_ASM, "99", 1);
            insertCd(lineId, SUB, "产品小计", ROW_SUB, "39.5", 2);
        });
        lineIds.add(lineId);
        return lineId;
    }

    private void insertCd(UUID lineId, UUID compId, String tab, String rows, String subtotal, int sort) {
        em.createNativeQuery("""
                INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, row_data,
                  subtotal, sort_order, created_at)
                VALUES (?1, ?2, ?3, ?4, CAST(?5 AS jsonb), CAST(?6 AS numeric), ?7, now())
                """)
            .setParameter(1, UUID.randomUUID())
            .setParameter(2, lineId)
            .setParameter(3, compId)
            .setParameter(4, tab)
            .setParameter(5, rows)
            .setParameter(6, subtotal)
            .setParameter(7, sort)
            .executeUpdate();
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID id : lineIds) {
                em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id = ?1")
                    .setParameter(1, id).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_item WHERE id = ?1")
                    .setParameter(1, id).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM quotation WHERE id = ?1").setParameter(1, quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM template WHERE id = ?1").setParameter(1, templateId).executeUpdate();
            em.createNativeQuery("DELETE FROM customer WHERE id = ?1").setParameter(1, customerId).executeUpdate();
        });
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> excelViewRows() {
        Map<String, Object> view = QuarkusTransaction.requiringNew().call(() -> excelViewService.getExcelView(quotationId));
        return (List<Map<String, Object>>) view.get("rows");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dryRunRows() throws Exception {
        List<Map<String, Object>> cols = MAPPER.readValue(COLUMNS, List.class);
        Map<String, Object> out = QuarkusTransaction.requiringNew().call(() ->
            excelViewService.dryRun(quotationId, cols, templateId));
        return (List<Map<String, Object>>) out.get("rows");
    }

    private static void assertDec(String expected, Object actual, String what) {
        assertNotNull(actual, what + " must not be null");
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())),
            what + ": expected " + expected + " got " + actual);
    }

    /** Values expected from CARD_OK (and what row_data would have produced, for contrast). */
    private static void assertCardOkRow(Map<String, Object> row, String label) {
        assertDec("1.978941064", row.get("c_mat"), label + " c_mat (row_data would give 0.000869091)");
        assertDec("1.978941064", row.get("c_mat_sub"), label + " c_mat_sub");
        // E-5: a true 0 stays 0, not null (row_data would give 0.3)
        assertDec("0", row.get("c_rec"), label + " c_rec");
        // unit conversion applied through components_snapshot (row_data would give 99 unconverted)
        assertDec("0.03954034733", row.get("c_asm"), label + " c_asm");
        assertDec("1.804589425", row.get("c_total"), label + " c_total (row_data would give 39.5)");
        assertDec("1.804589425", row.get("c_card"), label + " c_card (row_data would give 39.5)");
        assertEquals("F", row.get("c_fixed"), label + " c_fixed");
    }

    private static void assertBlankedRow(Map<String, Object> row, String label) {
        for (String k : List.of("c_mat", "c_mat_sub", "c_rec", "c_asm", "c_total", "c_card")) {
            assertTrue(row.containsKey(k), label + " must still carry column " + k);
            assertNull(row.get(k), label + " " + k + " must be null (no fallback to row_data), got " + row.get(k));
        }
        assertEquals("F", row.get("c_fixed"), label + " other column types keep original logic");
    }

    private static final String[] UNUSABLE_FORMS = {
        null,                                          // NULL
        "{\"tabs\":[],\"__cardValueFailed\":true}",     // failure sentinel
        "{\"tabs\":[]}",                               // tabs empty
        "{}",                                          // tabs missing
        "{\"tabs\":{\"x\":1}}",                        // tabs not an array
        "\"not-an-object\"",                           // root not an object
    };

    // ---------------------------------------------------------------- tests

    @Test
    void getExcelView_readsCardValues_notRowData() {
        addLine(CARD_OK);
        List<Map<String, Object>> rows = excelViewRows();
        assertEquals(1, rows.size());
        assertCardOkRow(rows.get(0), "getExcelView");
        assertEquals(lineIds.get(0).toString(), rows.get(0).get("_lineItemId"));
    }

    @Test
    void getExcelView_unusableLinesBlank_otherLinesUnaffected() {
        addLine(CARD_OK);
        for (String form : UNUSABLE_FORMS) addLine(form);
        addLine(CARD_OK);

        List<Map<String, Object>> rows = excelViewRows();
        assertEquals(UNUSABLE_FORMS.length + 2, rows.size());
        assertCardOkRow(rows.get(0), "line 1 (usable)");
        for (int i = 0; i < UNUSABLE_FORMS.length; i++) {
            assertBlankedRow(rows.get(i + 1), "unusable form #" + i + " (" + UNUSABLE_FORMS[i] + ")");
        }
        assertCardOkRow(rows.get(rows.size() - 1), "last line (usable)");
    }

    @Test
    void dryRun_readsCardValues_andBlanksUnusableLines() throws Exception {
        addLine(CARD_OK);
        addLine(null);
        addLine("{\"tabs\":[],\"__cardValueFailed\":true}");
        List<Map<String, Object>> rows = dryRunRows();
        assertEquals(3, rows.size());
        assertCardOkRow(rows.get(0), "dryRun line 1");
        assertBlankedRow(rows.get(1), "dryRun NULL line");
        assertBlankedRow(rows.get(2), "dryRun sentinel line");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dryRunTabFormula_sourcePriority_andFixedErrorText() throws Exception {
        UUID usable = addLine(CARD_OK);
        UUID unusable = addLine(null);
        List<Map<String, Object>> cols = MAPPER.readValue(COLUMNS, List.class);
        Map<String, Object> cMat = cols.get(0);
        Map<String, Object> cTotal = cols.get(4);

        // ② no request card values → the line's own quote_card_values
        Map<String, Object> r1 = QuarkusTransaction.requiringNew().call(() ->
            excelViewService.dryRunTabFormula(usable, cMat, null));
        assertDec("1.978941064", r1.get("value"), "line card values");
        assertEquals(List.of(), r1.get("errors"));
        Map<String, Object> r1b = QuarkusTransaction.requiringNew().call(() ->
            excelViewService.dryRunTabFormula(usable, cTotal, null));
        assertDec("1.804589425", r1b.get("value"), "line card values (总计)");

        // ① usable request card values take priority over the line's
        String other = cardValues("5", "5.478941064");
        Map<String, Object> r2 = QuarkusTransaction.requiringNew().call(() ->
            excelViewService.dryRunTabFormula(usable, cMat, other));
        assertDec("5.478941064", r2.get("value"), "request card values win");

        // unusable request card values are ignored → fall to the line's
        Map<String, Object> r3 = QuarkusTransaction.requiringNew().call(() ->
            excelViewService.dryRunTabFormula(usable, cMat, "{\"tabs\":[]}"));
        assertDec("1.978941064", r3.get("value"), "unusable request → line card values");

        // ③ neither usable → value null + the fixed text, exactly one error
        Map<String, Object> r4 = QuarkusTransaction.requiringNew().call(() ->
            excelViewService.dryRunTabFormula(unusable, cMat, null));
        assertTrue(r4.containsKey("value"));
        assertNull(r4.get("value"), "no row_data fallback");
        assertEquals(List.of(UNAVAILABLE_MSG), r4.get("errors"));

        // request usable rescues an unusable line
        Map<String, Object> r5 = QuarkusTransaction.requiringNew().call(() ->
            excelViewService.dryRunTabFormula(unusable, cMat, CARD_OK));
        assertDec("1.978941064", r5.get("value"), "request card values on unusable line");
    }

    @Test
    void ensureExcelValues_skipsUnusableLines_andWritesCardValuesForUsable() throws Exception {
        UUID ok = addLine(CARD_OK);
        UUID nul = addLine(null);
        UUID failed = addLine("{\"tabs\":[],\"__cardValueFailed\":true}");
        UUID empty = addLine("{\"tabs\":[]}");

        cardSnapshotService.ensureExcelValues(quotationId);

        Map<UUID, String> stored = readQuoteExcelValues();
        assertNull(stored.get(nul), "NULL card values → quote_excel_values stays NULL (not {\"rows\":[]}, not row_data)");
        assertNull(stored.get(failed), "failure sentinel → quote_excel_values stays NULL");
        assertNull(stored.get(empty), "empty tabs → quote_excel_values stays NULL");
        assertNotNull(stored.get(ok), "usable card values → written");
        JsonNode row0 = MAPPER.readTree(stored.get(ok)).path("rows").path(0);
        assertDec("1.978941064", row0.path("c_mat").asText(), "stored c_mat");
        assertDec("0", row0.path("c_rec").asText(), "stored c_rec");
        assertDec("0.03954034733", row0.path("c_asm").asText(), "stored c_asm");
        assertDec("1.804589425", row0.path("c_total").asText(), "stored c_total");

        // self-heal: once card values become usable, the next bootstrap writes the line
        QuarkusTransaction.requiringNew().run(() ->
            em.createNativeQuery("UPDATE quotation_line_item SET quote_card_values = CAST(?1 AS jsonb) WHERE id = ?2")
                .setParameter(1, CARD_OK).setParameter(2, nul).executeUpdate());
        cardSnapshotService.ensureExcelValues(quotationId);
        Map<UUID, String> after = readQuoteExcelValues();
        assertNotNull(after.get(nul), "healed line is written on the next bootstrap");
        assertDec("1.804589425", MAPPER.readTree(after.get(nul)).path("rows").path(0).path("c_total").asText(),
            "healed line c_total");
        assertNull(after.get(failed), "still-unusable line still NULL");
        assertEquals(stored.get(ok), after.get(ok), "already-written line untouched");
    }

    private Map<UUID, String> readQuoteExcelValues() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Map<UUID, String> m = new LinkedHashMap<>();
            for (UUID id : lineIds) {
                Object v = em.createNativeQuery("SELECT quote_excel_values::text FROM quotation_line_item WHERE id = ?1")
                    .setParameter(1, id).getSingleResult();
                m.put(id, v == null ? null : v.toString());
            }
            return m;
        });
    }

    // ---------------------------------------------------------------- B-6 / AC-7: SQL count independent of N

    @FunctionalInterface
    private interface Action { void run() throws Exception; }

    private long preparedStatements(Action a) throws Exception {
        org.hibernate.stat.Statistics st =
            em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        st.clear();
        a.run();
        return st.getPrepareStatementCount();
    }

    @Test
    void sqlCount_isIndependentOfLineCount() throws Exception {
        addLine(CARD_OK);
        excelViewRows();   // warm-up (caches, first-time metadata)
        dryRunRows();
        long view1 = preparedStatements(this::excelViewRows);
        long dry1 = preparedStatements(this::dryRunRows);

        addLine(CARD_OK);
        addLine(null);     // an unusable line must not add queries either
        addLine("{\"tabs\":[],\"__cardValueFailed\":true}");
        addLine(CARD_OK);
        excelViewRows();
        dryRunRows();
        long view5 = preparedStatements(this::excelViewRows);
        long dry5 = preparedStatements(this::dryRunRows);

        System.out.printf("[perf] repair-260918 getExcelView N=1 sql=%d | N=5 sql=%d ; dryRun N=1 sql=%d | N=5 sql=%d%n",
            view1, view5, dry1, dry5);
        // guard against a vacuous 0 == 0 (statistics not active)
        assertTrue(view1 > 0 && dry1 > 0, "statistics captured no statements — the count comparison would be vacuous");
        assertEquals(view1, view5, "getExcelView SQL count must not grow with the number of lines");
        assertEquals(dry1, dry5, "dryRun SQL count must not grow with the number of lines");
        assertEquals(5, excelViewRows().size());
    }
}
