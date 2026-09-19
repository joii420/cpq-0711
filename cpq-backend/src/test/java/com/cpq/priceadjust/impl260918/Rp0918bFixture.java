package com.cpq.priceadjust.impl260918;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.UUID;

/**
 * repair-260918 backend self-test fixture (private, prefix {@code RP0918B}). Creates in {@code cpq_db_test}:
 * one customer + enabled price-adjust strategy (element Ag, SPECIFIED scope, never due for the scheduler) +
 * one PENDING version (Ag 3000 → 3100.123456789) + two quotation copies of the 1845-line
 * {@code QT-20260909-0629}: BIG (all lines) and SMALL (its first 12 lines) — same template, same component
 * structure, same frozen view structure. Every row it creates gets a fresh id; {@link #destroy} deletes exactly
 * those rows (plus revisions / jobs / reviews / refs hanging off them).
 */
final class Rp0918bFixture {

    static final String SOURCE_QUOTATION = "QT-20260909-0629";

    final String tag = UUID.randomUUID().toString().substring(0, 6);
    final String customerCode = "RP0918B-" + tag;
    final UUID customerId = UUID.randomUUID();
    final UUID strategyId = UUID.randomUUID();
    final UUID versionId = UUID.randomUUID();
    final UUID qBig = UUID.randomUUID();
    final UUID qSmall = UUID.randomUUID();
    final String versionNo = "V26091899";

    /** @return {@code null} when the source quotation is not present in this database. */
    static Rp0918bFixture create(EntityManager em) {
        Rp0918bFixture f = new Rp0918bFixture();
        boolean ok = QuarkusTransaction.requiringNew().timeout(600).call(() -> f.insert(em));
        return ok ? f : null;
    }

    private boolean insert(EntityManager em) {
        List<?> src = em.createNativeQuery("SELECT id FROM quotation WHERE quotation_number = :n")
            .setParameter("n", SOURCE_QUOTATION).getResultList();
        if (src.isEmpty()) return false;
        UUID srcId = (UUID) src.get(0);

        exec(em, "INSERT INTO customer(id, name, code) VALUES (:id, :name, :code)",
            "id", customerId, "name", "RP0918B 自测客户 " + tag, "code", customerCode);
        exec(em, "INSERT INTO customer_price_adjust_strategy(id, customer_no, enabled, cycle_type, cycle_day_of_month, material_scope_mode) " +
                 "VALUES (:id, :c, true, 'MONTHLY_DAY', NULL, 'SPECIFIED')", "id", strategyId, "c", customerCode);
        exec(em, "INSERT INTO customer_price_adjust_element(id, strategy_id, element_code, created_at) VALUES (gen_random_uuid(), :s, 'Ag', now())",
            "s", strategyId);
        exec(em, "INSERT INTO element_price_version(id, customer_no, version_no, base_date, status, trigger_type) " +
                 "VALUES (:id, :c, :vn, current_date, 'PENDING', 'MANUAL')", "id", versionId, "c", customerCode, "vn", versionNo);
        exec(em, "INSERT INTO element_price_version_item(version_id, element_code, current_price, previous_price, change_rate, currency) " +
                 "VALUES (:v, 'Ag', 3100.123456789, 3000, 0.033374485596, 'CNY')", "v", versionId);

        copyQuotation(em, srcId, qBig, "RP0918B-BIG-" + tag, Integer.MAX_VALUE);
        copyQuotation(em, srcId, qSmall, "RP0918B-SMALL-" + tag, 12);
        return true;
    }

    private void copyQuotation(EntityManager em, UUID srcId, UUID newId, String number, int maxLines) {
        String salt = newId.toString();
        exec(em, "INSERT INTO quotation SELECT (jsonb_populate_record(CAST(NULL AS quotation), to_jsonb(q) || jsonb_build_object(" +
                 " 'id', CAST(:nid AS text), 'quotation_number', CAST(:no AS text), 'customer_id', CAST(:cid AS text)," +
                 " 'name', CAST(:no AS text), 'source_quotation_id', NULL))).* FROM quotation q WHERE q.id = :src",
            "nid", newId.toString(), "no", number, "cid", customerId.toString(), "src", srcId);
        String lineFilter = "li.id IN (SELECT x.id FROM quotation_line_item x WHERE x.quotation_id = :src ORDER BY x.sort_order, x.id LIMIT :lim)";
        exec(em, "INSERT INTO quotation_line_item SELECT (jsonb_populate_record(CAST(NULL AS quotation_line_item), to_jsonb(li) || jsonb_build_object(" +
                 " 'id', CAST(md5(CAST(li.id AS text) || :salt) AS uuid), 'quotation_id', CAST(:nid AS text), 'parent_line_item_id', NULL))).*" +
                 "  FROM quotation_line_item li WHERE " + lineFilter,
            "salt", salt, "nid", newId.toString(), "src", srcId, "lim", maxLines);
        exec(em, "INSERT INTO quotation_line_component_data SELECT (jsonb_populate_record(CAST(NULL AS quotation_line_component_data), to_jsonb(cd) || jsonb_build_object(" +
                 " 'id', gen_random_uuid(), 'line_item_id', CAST(md5(CAST(cd.line_item_id AS text) || :salt) AS uuid)))).*" +
                 "  FROM quotation_line_component_data cd JOIN quotation_line_item li ON li.id = cd.line_item_id WHERE " + lineFilter,
            "salt", salt, "src", srcId, "lim", maxLines);
        exec(em, "INSERT INTO quotation_view_structure(id, quotation_id, view_kind, structure, created_at) " +
                 "SELECT gen_random_uuid(), :nid, s.view_kind, s.structure, now() FROM quotation_view_structure s WHERE s.quotation_id = :src",
            "nid", newId, "src", srcId);
    }

    void destroy(EntityManager em) {
        QuarkusTransaction.requiringNew().timeout(600).run(() -> {
            exec(em, "DELETE FROM material_price_update_job_item WHERE quotation_id IN (:a, :b)", "a", qBig, "b", qSmall);
            exec(em, "DELETE FROM material_price_update_job WHERE customer_no = :c", "c", customerCode);
            exec(em, "DELETE FROM material_price_review_column WHERE review_id IN (SELECT id FROM material_price_review WHERE customer_no = :c)",
                "c", customerCode);
            exec(em, "DELETE FROM material_price_review WHERE customer_no = :c", "c", customerCode);
            exec(em, "DELETE FROM material_price_version_ref WHERE customer_no = :c", "c", customerCode);
            exec(em, "DELETE FROM quotation WHERE id IN (:a, :b)", "a", qBig, "b", qSmall); // cascades lines / component data / structures / revisions
            exec(em, "DELETE FROM element_price_version_item WHERE version_id = :v", "v", versionId);
            exec(em, "DELETE FROM element_price_version WHERE id = :v", "v", versionId);
            exec(em, "DELETE FROM customer_price_adjust_material WHERE strategy_id = :s", "s", strategyId);
            exec(em, "DELETE FROM customer_price_adjust_element WHERE strategy_id = :s", "s", strategyId);
            exec(em, "DELETE FROM customer_price_adjust_strategy WHERE id = :s", "s", strategyId);
            exec(em, "DELETE FROM customer WHERE id = :id", "id", customerId);
        });
    }

    static int exec(EntityManager em, String sql, Object... kv) {
        var q = em.createNativeQuery(sql);
        for (int i = 0; i < kv.length; i += 2) q.setParameter((String) kv[i], kv[i + 1]);
        return q.executeUpdate();
    }
}
