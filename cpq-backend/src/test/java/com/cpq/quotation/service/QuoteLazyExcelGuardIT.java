package com.cpq.quotation.service;

import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260918 B-5 (AC-12) — the quote-side <b>lazy</b> Excel bootstrap in
 * {@link CardSnapshotService#snapshotQuoteSideOnly(QuotationLineItem, Quotation)}
 * (the {@code computeExcel && quoteExcelValues == null && isCardValuesUsable(...)} guard).
 *
 * <p>Two independent layers keep an unusable-card-values line from getting a quote_excel_values
 * computed from row_data:
 * <ol>
 *   <li>the guard in {@code snapshotQuoteSideOnly} (skips the line);</li>
 *   <li>{@link CardSnapshotService#buildQuoteExcelValuesStrict} returns {@code null} for unusable
 *       card values (the caller then does not assign).</li>
 * </ol>
 * {@link #lazyPath_unusableCardValues_keepsQuoteExcelValuesNull} checks the end-to-end outcome;
 * {@link #strictBuilder_returnsNull_forEveryUnusableForm} pins layer 2 on its own, with a positive
 * control proving the fixture WOULD produce a non-empty row from row_data.
 *
 * <p>Fixture: DRAFT template whose {@code components_snapshot} is NULL ⇒
 * {@code buildCardValues} returns {@code null} ⇒ the line's quote_card_values stays NULL when the
 * lazy path reaches the Excel step. The line has component data (row_data) so the old code path
 * would have written a non-empty {@code {"rows":[...]}}.
 * Data is committed to cpq_db_test for the test only; {@link #cleanup} deletes exactly these ids.
 */
@QuarkusTest
class QuoteLazyExcelGuardIT {

    private static final String COLUMNS = "["
        + "{\"col_key\":\"c_fixed\",\"title\":\"固定值\",\"source_type\":\"FIXED_VALUE\",\"fixed_value\":\"F\"},"
        + "{\"col_key\":\"c_field\",\"title\":\"材料成本字段\",\"source_type\":\"COMPONENT_FIELD\",\"field_key\":\"材料成本\"}"
        + "]";
    private static final String ROW_DATA = "[{\"销售料号\":\"P1\",\"材料成本\":\"0.000066807\"}]";

    @Inject CardSnapshotService cardSnapshotService;
    @Inject EntityManager em;

    private UUID customerId;
    private UUID templateId;
    private UUID quotationId;
    private UUID lineId;

    @BeforeEach
    void seed() {
        customerId = UUID.randomUUID();
        templateId = UUID.randomUUID();
        quotationId = UUID.randomUUID();
        lineId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            UUID salesRepId = (UUID) em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getSingleResult();
            em.createNativeQuery("INSERT INTO customer (id, code, name) VALUES (?1, ?2, 'RP260918 lazy cust')")
                .setParameter(1, customerId)
                .setParameter(2, "RP0918L-" + customerId.toString().substring(0, 8))
                .executeUpdate();
            // components_snapshot deliberately NULL → buildCardValues returns null
            em.createNativeQuery("""
                    INSERT INTO template (id, template_series_id, name, status, formulas,
                      template_sql_views_snapshot, excel_view_config, components_snapshot, created_at, updated_at)
                    VALUES (?1, ?2, 'RP260918 lazy tmpl', 'DRAFT', '[]', '{}', CAST(?3 AS jsonb), NULL, now(), now())
                    """)
                .setParameter(1, templateId)
                .setParameter(2, UUID.randomUUID())
                .setParameter(3, COLUMNS)
                .executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status,
                      total_amount, original_amount, system_discount_rate, final_discount_rate, tax_rate, tax_amount,
                      customer_template_id, created_at, updated_at)
                    VALUES (?1, ?2, ?3, 'RP260918 lazy quote', ?4, 'DRAFT', 0, 0, 100, 100, 0, 0, ?5, now(), now())
                    """)
                .setParameter(1, quotationId)
                .setParameter(2, "RP0918L-" + quotationId)
                .setParameter(3, customerId)
                .setParameter(4, salesRepId)
                .setParameter(5, templateId)
                .executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO quotation_line_item (id, quotation_id, template_id, product_attribute_values,
                      composite_type, subtotal, sort_order, quote_card_values, quote_excel_values, created_at)
                    VALUES (?1, ?2, ?3, '{}', 'SIMPLE', 0, 0, NULL, NULL, now())
                    """)
                .setParameter(1, lineId)
                .setParameter(2, quotationId)
                .setParameter(3, templateId)
                .executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, row_data,
                      subtotal, sort_order, created_at)
                    VALUES (?1, ?2, ?3, '物料', CAST(?4 AS jsonb), 0, 0, now())
                    """)
                .setParameter(1, UUID.randomUUID())
                .setParameter(2, lineId)
                .setParameter(3, UUID.randomUUID())
                .setParameter(4, ROW_DATA)
                .executeUpdate();
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id = ?1")
                .setParameter(1, lineId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_item WHERE id = ?1").setParameter(1, lineId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation WHERE id = ?1").setParameter(1, quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM template WHERE id = ?1").setParameter(1, templateId).executeUpdate();
            em.createNativeQuery("DELETE FROM customer WHERE id = ?1").setParameter(1, customerId).executeUpdate();
        });
    }

    private String[] readValues() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object[] r = (Object[]) em.createNativeQuery(
                    "SELECT quote_card_values::text, quote_excel_values::text FROM quotation_line_item WHERE id = ?1")
                .setParameter(1, lineId).getSingleResult();
            return new String[] { r[0] == null ? null : r[0].toString(), r[1] == null ? null : r[1].toString() };
        });
    }

    @Test
    void lazyPath_unusableCardValues_keepsQuoteExcelValuesNull() {
        QuarkusTransaction.requiringNew().run(() -> {
            QuotationLineItem li = QuotationLineItem.findById(lineId);
            Quotation q = Quotation.findById(quotationId);
            assertNotNull(li);
            assertNotNull(q);
            assertNull(li.quoteExcelValues, "precondition: quote_excel_values starts NULL (lazy step is reached)");
            cardSnapshotService.snapshotQuoteSideOnly(li, q);   // computeExcel = true
        });

        String[] v = readValues();
        System.out.println("[repair-260918 lazy] after snapshotQuoteSideOnly: quote_card_values=" + v[0]
            + " | quote_excel_values=" + v[1]);
        // precondition: the path really ran with UNUSABLE card values (otherwise the assertion below is vacuous)
        assertFalse(com.cpq.quotation.service.card.CardEffectiveRows.isCardValuesUsable(v[0]),
            "precondition: card values after the lazy path must be unusable, got " + v[0]);
        assertNotEquals("{\"rows\": []}", v[1], "must not write an empty rows object (would block the bootstrap forever)");
        assertNull(v[1], "unusable card values → quote_excel_values must stay NULL (no row_data values), got " + v[1]);
    }

    @Test
    void strictBuilder_returnsNull_forEveryUnusableForm() {
        QuarkusTransaction.requiringNew().run(() -> {
            QuotationLineItem li = QuotationLineItem.findById(lineId);
            // positive control: the non-strict (legacy / costing) builder DOES produce a row from row_data,
            // so a null from the strict builder is meaningful, not an empty fixture.
            String legacy = cardSnapshotService.buildExcelValues(li, templateId, customerId, null);
            System.out.println("[repair-260918 lazy] legacy buildExcelValues(null card values) = " + legacy);
            assertNotNull(legacy);
            assertTrue(legacy.contains("\"c_fixed\"") && legacy.contains("0.000066807"),
                "positive control: legacy builder should read row_data, got " + legacy);

            for (String form : Arrays.asList(null, "", "{\"tabs\":[],\"__cardValueFailed\":true}",
                    "{\"tabs\":[]}", "{}", "not json")) {
                assertNull(cardSnapshotService.buildQuoteExcelValuesStrict(li, templateId, customerId, form),
                    "strict builder must return null for unusable card values: " + form);
            }
        });
    }
}
