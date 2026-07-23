package com.cpq.elementprice;

import com.cpq.common.exception.BusinessException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for element reference price management (v1 — MANUAL rows only).
 *
 * <p>All queries target element_daily_price with fetch_status='MANUAL'.
 * No writes to element_price / element_price_source / element_price_fetch_rule (v1).
 */
@ApplicationScoped
public class ElementPriceService {

    @Inject
    EntityManager em;

    // -------------------------------------------------------------------------
    // Query: reference price
    // -------------------------------------------------------------------------

    /**
     * Returns the most-recent MANUAL row for the given element whose price_date
     * is ≤ asOfDate. Returns null when no matching row exists.
     *
     * @param elementName element symbol, e.g. "Ag"
     * @param asOfDate    the reference date (inclusive upper bound)
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public ElementReferenceDTO getReference(String elementName, LocalDate asOfDate) {
        if (elementName == null || elementName.isBlank()) {
            throw new BusinessException(400, "元素名称不能为空");
        }
        if (asOfDate == null) {
            asOfDate = LocalDate.now();
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT edp.element_name, edp.raw_price, edp.currency, edp.price_unit, " +
                "       edp.price_date, u.full_name, edp.fetch_error " +
                "FROM element_daily_price edp " +
                "LEFT JOIN \"user\" u ON u.id = edp.manually_filled_by " +
                "WHERE edp.element_name = :elementName " +
                "  AND edp.fetch_status = 'MANUAL' " +
                "  AND edp.price_date <= :asOfDate " +
                "ORDER BY edp.price_date DESC " +
                "LIMIT 1")
                .setParameter("elementName", elementName)
                .setParameter("asOfDate", asOfDate)
                .getResultList();

        if (rows.isEmpty()) {
            return null;
        }
        return mapRow(rows.get(0));
    }

    // -------------------------------------------------------------------------
    // Query: history list
    // -------------------------------------------------------------------------

    /**
     * Lists MANUAL rows for the given element in the date range [from, to],
     * ordered by price_date DESC.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<ElementReferenceDTO> listHistory(String elementName, LocalDate from, LocalDate to,
                                                  int page, int size) {
        if (elementName == null || elementName.isBlank()) {
            throw new BusinessException(400, "元素名称不能为空");
        }
        if (from == null) from = LocalDate.now().minusDays(30);
        if (to == null) to = LocalDate.now();
        if (page < 0) page = 0;
        if (size <= 0 || size > 200) size = 20;

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT edp.element_name, edp.raw_price, edp.currency, edp.price_unit, " +
                "       edp.price_date, u.full_name, edp.fetch_error " +
                "FROM element_daily_price edp " +
                "LEFT JOIN \"user\" u ON u.id = edp.manually_filled_by " +
                "WHERE edp.element_name = :elementName " +
                "  AND edp.fetch_status = 'MANUAL' " +
                "  AND edp.price_date BETWEEN :fromDate AND :toDate " +
                "ORDER BY edp.price_date DESC " +
                "LIMIT :limit OFFSET :offset")
                .setParameter("elementName", elementName)
                .setParameter("fromDate", from)
                .setParameter("toDate", to)
                .setParameter("limit", size)
                .setParameter("offset", (long) page * size)
                .getResultList();

        List<ElementReferenceDTO> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(mapRow(row));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Mutation: upsert manual price
    // -------------------------------------------------------------------------

    /**
     * Inserts or updates a MANUAL price row for the given element on today's date.
     *
     * <p>Uniqueness key: (element_name, source_id=NULL, price_date=today, fetch_status=MANUAL).
     * The unique index on element_daily_price is: uq_element_daily ON (element_name, COALESCE(source_id::TEXT,''), price_date).
     * For MANUAL entries source_id is always NULL, so the effective key is (element_name, '', price_date).
     * We handle this with INSERT ... ON CONFLICT DO UPDATE to implement upsert.
     */
    @Transactional
    public ElementReferenceDTO upsertManual(String elementName, BigDecimal price,
                                             String currency, String unit, String note,
                                             UUID userId) {
        if (elementName == null || elementName.isBlank()) {
            throw new BusinessException(400, "元素名称不能为空");
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "价格必须大于 0");
        }

        LocalDate today = LocalDate.now();

        // Resolve user full_name for the response — single-column query returns List<String>
        @SuppressWarnings("unchecked")
        List<String> userRows = em.createNativeQuery(
                "SELECT full_name FROM \"user\" WHERE id = :userId")
                .setParameter("userId", userId)
                .getResultList();
        String enteredByName = userRows.isEmpty() ? null : userRows.get(0);

        em.createNativeQuery(
                "INSERT INTO element_daily_price " +
                "  (id, element_name, source_id, price_date, raw_price, currency, price_unit, " +
                "   fetch_status, fetch_error, manually_filled_by, " +
                "   created_at, updated_at, created_by, updated_by) " +
                "VALUES " +
                "  (gen_random_uuid(), :elementName, NULL, :priceDate, :price, :currency, :unit, " +
                "   'MANUAL', :note, :userId, NOW(), NOW(), :userId, :userId) " +
                "ON CONFLICT (element_name, COALESCE(source_id::TEXT,''), price_date) " +
                "DO UPDATE SET " +
                "  raw_price          = EXCLUDED.raw_price, " +
                "  currency           = EXCLUDED.currency, " +
                "  price_unit         = EXCLUDED.price_unit, " +
                "  fetch_error        = EXCLUDED.fetch_error, " +
                "  manually_filled_by = EXCLUDED.manually_filled_by, " +
                "  updated_at         = NOW(), " +
                "  updated_by         = EXCLUDED.updated_by")
                .setParameter("elementName", elementName)
                .setParameter("priceDate", today)
                .setParameter("price", price)
                .setParameter("currency", currency != null ? currency : "CNY")
                .setParameter("unit", unit)
                .setParameter("note", note)
                .setParameter("userId", userId)
                .executeUpdate();

        return new ElementReferenceDTO(
                elementName, price,
                currency != null ? currency : "CNY",
                unit, today, enteredByName, note);
    }

    // -------------------------------------------------------------------------
    // Query: available elements
    // -------------------------------------------------------------------------

    /**
     * Returns element symbols (element_code) from the {@code element} master table (status=ACTIVE).
     * This list is used by the frontend for dropdown selection when filling element prices.
     *
     * <p>task-0722 · B7（闭合 BL-0069#5）：{@code mat_bom} 自 2026-06-02 起已停写（AP-53），
     * 原实现读取该已废弃表会返回陈旧/空结果；改读 {@code element} 主表（元素字典，V316 起的现役表）。
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<String> listAvailableElements() {
        @SuppressWarnings("unchecked")
        List<String> names = em.createNativeQuery(
                "SELECT element_code " +
                "FROM element " +
                "WHERE status = 'ACTIVE' " +
                "ORDER BY element_code")
                .getResultList();
        return names;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private ElementReferenceDTO mapRow(Object[] row) {
        // Columns: element_name, raw_price, currency, price_unit, price_date, full_name, fetch_error(note)
        String elementName = (String) row[0];
        BigDecimal price = row[1] != null ? new BigDecimal(row[1].toString()) : null;
        String currency = (String) row[2];
        String unit = (String) row[3];
        // price_date may come back as java.sql.Date or java.time.LocalDate depending on JDBC driver
        LocalDate priceDate = null;
        if (row[4] != null) {
            if (row[4] instanceof java.sql.Date sqlDate) {
                priceDate = sqlDate.toLocalDate();
            } else if (row[4] instanceof java.time.LocalDate ld) {
                priceDate = ld;
            } else {
                priceDate = java.sql.Date.valueOf(row[4].toString()).toLocalDate();
            }
        }
        String enteredByName = (String) row[5];
        String note = (String) row[6];
        return new ElementReferenceDTO(elementName, price, currency, unit, priceDate, enteredByName, note);
    }
}
