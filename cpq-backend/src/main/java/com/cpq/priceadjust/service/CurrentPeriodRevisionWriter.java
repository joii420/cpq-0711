package com.cpq.priceadjust.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * repair-260918 B-1 / B-4 · writes the whole-quotation revision snapshots ({@code quotation_price_revision})
 * with a single {@code INSERT … SELECT … ON CONFLICT} statement per write — the snapshot JSON is assembled
 * inside the database and never travels to the JVM.
 *
 * <p>Replaces the former {@code MaterialVersionUpgradeService.buildWholeQuotationSnapshot}, which loaded every
 * line item and then ran one {@code SELECT … FROM quotation_line_component_data WHERE line_item_id = ?} per line
 * (1200-line quotation = 2 × 1200 queries per upgrade, the root cause of the 60 s transaction timeouts).
 *
 * <p>Assembly semantics are identical to the old Java implementation ({@code putJsonOrNull}):
 * <ul>
 *   <li>key = line item id (text);</li>
 *   <li>line card value SQL NULL ⇒ JSON {@code null};</li>
 *   <li>line without any component data ⇒ {@code {}};</li>
 *   <li>{@code component_id} NULL ⇒ key {@code "null"}; component {@code snapshot_rows} NULL ⇒ JSON {@code null}.</li>
 * </ul>
 *
 * <p>🔒 Both methods are {@code @Transactional(REQUIRED)} — they <b>join the caller's transaction</b>. On the single
 * upgrade path this is what lets the snapshot see the rows the same transaction has just upgraded and the initial
 * revision it has just inserted (v1 design with REQUIRES_NEW wrote stale prices and duplicate revision numbers).
 * Do not change this to REQUIRES_NEW.
 */
@ApplicationScoped
public class CurrentPeriodRevisionWriter {

    private static final Logger LOG = Logger.getLogger(CurrentPeriodRevisionWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    /** Snapshot aggregation + next revision number (same rule as the former nextRevisionNo: R + yyMMdd + 2-digit daily sequence). */
    private static final String AGG_CTES =
        "WITH l AS (" +
        "  SELECT id, quote_card_values, costing_card_values FROM quotation_line_item WHERE quotation_id = :qid" +
        "), m AS (" +
        "  SELECT cd.line_item_id," +
        "         jsonb_object_agg(COALESCE(CAST(cd.component_id AS text), 'null'), cd.snapshot_rows) AS comp" +
        "    FROM quotation_line_component_data cd JOIN l ON l.id = cd.line_item_id" +
        "   GROUP BY cd.line_item_id" +
        "), agg AS (" +
        "  SELECT COALESCE(jsonb_object_agg(CAST(l.id AS text), l.quote_card_values), CAST('{}' AS jsonb)) AS qcv," +
        "         COALESCE(jsonb_object_agg(CAST(l.id AS text), l.costing_card_values), CAST('{}' AS jsonb)) AS ccv," +
        "         COALESCE(jsonb_object_agg(CAST(l.id AS text), COALESCE(m.comp, CAST('{}' AS jsonb))), CAST('{}' AS jsonb)) AS sr" +
        "    FROM l LEFT JOIN m ON m.line_item_id = l.id" +
        "), seq AS (" +
        "  SELECT COALESCE(MAX(CAST(substring(revision_no FROM 8) AS int)), 0) + 1 AS n" +
        "    FROM quotation_price_revision" +
        "   WHERE quotation_id = :qid AND revision_no ~ :pattern" +
        ") ";

    private static final String REVISION_NO_EXPR =
        ":prefix || lpad(CAST(seq.n AS text), GREATEST(2, length(CAST(seq.n AS text))), '0')";

    private static final String INSERT_COLUMNS =
        "INSERT INTO quotation_price_revision AS r " +
        "  (quotation_id, revision_no, based_version_id, sealed, upgraded_material_nos," +
        "   quote_card_values, costing_card_values, snapshot_rows, quote_total_amount," +
        "   first_effective_at, last_updated_at, created_at) ";

    /**
     * Current-period revision (本期 R), keyed by {@code UNIQUE(quotation_id, based_version_id)}:
     * insert when absent, otherwise overwrite the three snapshot columns with the current whole-quotation state
     * (E11-5), append the material numbers de-duplicated while keeping the existing order, refresh
     * {@code quote_total_amount}, {@code sealed=true}, {@code last_updated_at}. {@code revision_no} and
     * {@code first_effective_at} of an existing row are never touched.
     */
    private static final String CURRENT_SQL = AGG_CTES + INSERT_COLUMNS +
        "SELECT CAST(:qid AS uuid), " + REVISION_NO_EXPR + ", CAST(:vid AS uuid), true, CAST(:mnos AS jsonb)," +
        "       agg.qcv, agg.ccv, agg.sr," +
        "       (SELECT q.total_amount FROM quotation q WHERE q.id = :qid), :now, :now, :now" +
        "  FROM agg, seq " +
        "ON CONFLICT (quotation_id, based_version_id) DO UPDATE SET" +
        "  quote_card_values = EXCLUDED.quote_card_values," +
        "  costing_card_values = EXCLUDED.costing_card_values," +
        "  snapshot_rows = EXCLUDED.snapshot_rows," +
        "  upgraded_material_nos = (" +
        "    SELECT COALESCE(jsonb_agg(to_jsonb(u.v) ORDER BY u.o), CAST('[]' AS jsonb)) FROM (" +
        "      SELECT x.v, MIN(x.o) AS o FROM (" +
        "        SELECT e.v, e.o FROM jsonb_array_elements_text(r.upgraded_material_nos) WITH ORDINALITY AS e(v, o)" +
        "        UNION ALL" +
        "        SELECT n.v, n.o + 1000000000 FROM jsonb_array_elements_text(EXCLUDED.upgraded_material_nos) WITH ORDINALITY AS n(v, o)" +
        "      ) x GROUP BY x.v" +
        "    ) u)," +
        "  quote_total_amount = EXCLUDED.quote_total_amount," +
        "  sealed = true," +
        "  last_updated_at = EXCLUDED.last_updated_at";

    /**
     * Initial revision (初版, {@code based_version_id IS NULL}): materialise + seal the placeholder created by
     * saveDraft, or create it sealed when absent (legacy quotations). An already sealed initial revision is
     * never touched ({@code WHERE r.sealed = false}).
     */
    private static final String INITIAL_SQL = AGG_CTES + INSERT_COLUMNS +
        "SELECT q.id, " + REVISION_NO_EXPR + ", CAST(NULL AS uuid), true, CAST('[]' AS jsonb)," +
        "       agg.qcv, agg.ccv, agg.sr, q.total_amount, COALESCE(q.created_at, :now), :now, :now" +
        "  FROM agg, seq, quotation q WHERE q.id = :qid " +
        "ON CONFLICT (quotation_id) WHERE based_version_id IS NULL DO UPDATE SET" +
        "  quote_card_values = EXCLUDED.quote_card_values," +
        "  costing_card_values = EXCLUDED.costing_card_values," +
        "  snapshot_rows = EXCLUDED.snapshot_rows," +
        "  quote_total_amount = EXCLUDED.quote_total_amount," +
        "  sealed = true," +
        "  last_updated_at = EXCLUDED.last_updated_at" +
        "  WHERE r.sealed = false";

    @Inject
    EntityManager em;

    /**
     * Writes (inserts or merges into) the current-period revision of {@code quotationId} for
     * {@code targetVersionId}, merging {@code materialNos} into {@code upgraded_material_nos}.
     *
     * <p>🔒 {@code REQUIRED}: joins the caller's transaction (see class javadoc). 🚫 never REQUIRES_NEW.
     */
    @Transactional(Transactional.TxType.REQUIRED)
    public void write(UUID quotationId, UUID targetVersionId, Collection<String> materialNos) {
        if (quotationId == null || targetVersionId == null) {
            throw new IllegalArgumentException("quotationId/targetVersionId 不能为空");
        }
        long t0 = System.nanoTime();
        // Make the rows / card values this transaction has just changed visible to the statement below.
        em.flush();
        String prefix = revisionPrefix();
        em.createNativeQuery(CURRENT_SQL)
            .setParameter("qid", quotationId)
            .setParameter("vid", targetVersionId)
            .setParameter("mnos", toJsonArray(materialNos))
            .setParameter("prefix", prefix)
            .setParameter("pattern", "^" + prefix + "[0-9]{2}$")
            .setParameter("now", OffsetDateTime.now())
            .executeUpdate();
        LOG.infof("[perf] revision-write quotation=%s kind=CURRENT ms=%d", quotationId, (System.nanoTime() - t0) / 1_000_000);
    }

    /**
     * Seals the initial revision of {@code quotationId} with the current (pre-upgrade) whole-quotation state,
     * unless it is already sealed.
     *
     * @return {@code true} when the snapshot statement ran (first upgrade of this quotation)
     */
    @Transactional(Transactional.TxType.REQUIRED)
    public boolean sealInitialIfNeeded(UUID quotationId) {
        if (quotationId == null) return false;
        // Cheap guard: an already sealed initial revision must not pay for the whole-quotation aggregation.
        @SuppressWarnings("unchecked")
        List<Object> sealed = em.createNativeQuery(
                "SELECT sealed FROM quotation_price_revision WHERE quotation_id = :qid AND based_version_id IS NULL")
            .setParameter("qid", quotationId)
            .getResultList();
        if (!sealed.isEmpty() && Boolean.TRUE.equals(sealed.get(0))) {
            return false;
        }
        long t0 = System.nanoTime();
        em.flush();
        String prefix = revisionPrefix();
        int n = em.createNativeQuery(INITIAL_SQL)
            .setParameter("qid", quotationId)
            .setParameter("prefix", prefix)
            .setParameter("pattern", "^" + prefix + "[0-9]{2}$")
            .setParameter("now", OffsetDateTime.now())
            .executeUpdate();
        LOG.infof("[perf] revision-write quotation=%s kind=INITIAL ms=%d", quotationId, (System.nanoTime() - t0) / 1_000_000);
        return n > 0;
    }

    /** {@code R} + application-time-zone {@code yyMMdd} (computed in Java, never by the DB time zone). */
    static String revisionPrefix() {
        return "R" + LocalDate.now().format(YYMMDD);
    }

    /** De-duplicated (first occurrence order kept), null/blank dropped — same filter as the former mergeMaterialNo. */
    static String toJsonArray(Collection<String> materialNos) {
        List<String> clean = new ArrayList<>();
        if (materialNos != null) {
            for (String m : new LinkedHashSet<>(materialNos)) {
                if (m != null && !m.isBlank()) clean.add(m);
            }
        }
        try {
            return MAPPER.writeValueAsString(clean);
        } catch (Exception e) {
            throw new IllegalStateException("序列化料号列表失败: " + e.getMessage(), e);
        }
    }
}
