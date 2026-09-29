package com.cpq.tax.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.customer.entity.Customer;
import com.cpq.quotation.entity.Quotation;
import com.cpq.tax.entity.TaxCategory;
import com.cpq.tax.entity.TaxCategoryVatVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** Resolves VAT only for a newly-created quotation row; callers persist the returned immutable snapshot. */
@ApplicationScoped
public class VatSnapshotService {
    @Inject EntityManager em;
    @Inject TaxCategoryService categories;

    public Snapshot resolveForNewLine(UUID quotationId, String materialNo) {
        Quotation q = Quotation.findById(quotationId);
        if (q == null) throw new BusinessException(404, "报价单不存在");
        Customer customer = Customer.findById(q.customerId);
        String customerNo = customer == null ? null : customer.code;
        LocalDate quoteDate = q.createdAt == null ? LocalDate.now() : q.createdAt.toLocalDate();

        // Product category is the formal source. Query once by the composite V6 business key.
        Object[] product = null;
        if (customerNo != null && materialNo != null && !materialNo.isBlank()) {
            var rows = em.createNativeQuery("SELECT tax_category_id FROM ds_quote_material WHERE customer_no=:c AND material_no=:m")
                    .setParameter("c", customerNo).setParameter("m", materialNo).getResultList();
            if (!rows.isEmpty() && rows.get(0) != null) product = new Object[]{rows.get(0)};
        }
        UUID categoryId = product == null || product[0] == null ? null : (UUID) product[0];
        if (categoryId != null) {
            TaxCategory category = TaxCategory.findById(categoryId);
            TaxCategoryVatVersion v = category == null ? null : categories.resolveVersion(categoryId, quoteDate);
            if (category != null && "ACTIVE".equals(category.status) && v != null) return snapshot(category, v, "PRODUCT_CATEGORY");
        }
        if (q.defaultVatRate != null) return new Snapshot(null, null, q.defaultVatRate, q.defaultVatRate.movePointLeft(2), "QUOTATION_DEFAULT", null, OffsetDateTime.now());
        UUID defaultId = categories.defaultCategoryId();
        if (defaultId == null) throw new BusinessException(409, "DEFAULT_TAX_CATEGORY_NOT_CONFIGURED");
        TaxCategory defaultCategory = TaxCategory.findById(defaultId);
        TaxCategoryVatVersion v = categories.resolveVersion(defaultId, quoteDate);
        if (defaultCategory == null || v == null) throw new BusinessException(409, "DEFAULT_TAX_CATEGORY_NO_EFFECTIVE_VERSION");
        return snapshot(defaultCategory, v, "DEFAULT_CATEGORY");
    }

    /** Batch variant used by import materialization; SQL count is independent of candidate row count. */
    public Map<String, Snapshot> resolveForNewLines(UUID quotationId, List<String> materialNos) {
        Quotation q = Quotation.findById(quotationId);
        if (q == null) throw new BusinessException(404, "报价单不存在");
        Customer customer = Customer.findById(q.customerId);
        String customerNo = customer == null ? null : customer.code;
        LocalDate date = q.createdAt == null ? LocalDate.now() : q.createdAt.toLocalDate();
        Snapshot fallback = resolveForProduct(customerNo, null, date, q.defaultVatRate);
        Map<String, Snapshot> out = new HashMap<>();
        if (customerNo == null || materialNos == null || materialNos.isEmpty()) return out;
        List<Object[]> rows = em.createNativeQuery("""
                SELECT m.material_no, c.id, v.id, v.vat_rate, v.effective_from
                  FROM ds_quote_material m
                  LEFT JOIN tax_category c ON c.id=m.tax_category_id AND c.status='ACTIVE'
                  LEFT JOIN tax_category_vat_version v ON v.tax_category_id=c.id AND v.status='ACTIVE'
                    AND v.effective_from <= :d AND (v.effective_to IS NULL OR v.effective_to > :d)
                 WHERE m.customer_no=:customerNo AND m.material_no IN (:materials)
                """).setParameter("customerNo", customerNo).setParameter("materials", materialNos).setParameter("d", date).getResultList();
        for (Object[] r : rows) {
            if (r[0] == null) continue;
            Snapshot s = r[1] == null || r[2] == null ? fallback : new Snapshot(
                uuidValue(r[1]), uuidValue(r[2]), decimalValue(r[3]), decimalValue(r[3]).movePointLeft(2),
                "PRODUCT_CATEGORY", dateValue(r[4]), OffsetDateTime.now());
            out.put(r[0].toString(), s);
        }
        for (String material : materialNos) out.putIfAbsent(material, fallback);
        return out;
    }

    public Snapshot resolveForProduct(String customerNo, String materialNo, LocalDate quoteDate, BigDecimal quotationDefaultRate) {
        if (customerNo != null && materialNo != null) {
            var rows = em.createNativeQuery("SELECT tax_category_id FROM ds_quote_material WHERE customer_no=:c AND material_no=:m")
                    .setParameter("c", customerNo).setParameter("m", materialNo).getResultList();
            if (!rows.isEmpty() && rows.get(0) != null) {
                UUID id = (UUID) rows.get(0);
                TaxCategory c = TaxCategory.findById(id);
                TaxCategoryVatVersion v = c == null ? null : categories.resolveVersion(id, quoteDate);
                if (c != null && v != null && "ACTIVE".equals(c.status)) return snapshot(c, v, "PRODUCT_CATEGORY");
            }
        }
        if (quotationDefaultRate != null) return new Snapshot(null, null, quotationDefaultRate, quotationDefaultRate.movePointLeft(2), "QUOTATION_DEFAULT", null, OffsetDateTime.now());
        UUID id = categories.defaultCategoryId();
        TaxCategory c = id == null ? null : TaxCategory.findById(id);
        TaxCategoryVatVersion v = id == null ? null : categories.resolveVersion(id, quoteDate);
        if (c == null || v == null) throw new BusinessException(409, "DEFAULT_TAX_CATEGORY_NO_EFFECTIVE_VERSION");
        return snapshot(c, v, "DEFAULT_CATEGORY");
    }

    private static Snapshot snapshot(TaxCategory c, TaxCategoryVatVersion v, String source) {
        return new Snapshot(c.id, v.id, v.vatRate, v.vatRate.movePointLeft(2), source, v.effectiveFrom, OffsetDateTime.now());
    }

    private static UUID uuidValue(Object value) {
        if (value instanceof UUID uuid) return uuid;
        return value == null ? null : UUID.fromString(value.toString());
    }

    private static BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal bd) return bd;
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private static LocalDate dateValue(Object value) {
        if (value instanceof LocalDate d) return d;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        return value == null ? null : LocalDate.parse(value.toString());
    }

    public record Snapshot(UUID taxCategoryId, UUID taxCategoryVersionId, BigDecimal vatRate,
                           BigDecimal vatRateRatio, String source, LocalDate effectiveFrom,
                           OffsetDateTime resolvedAt) {}
}
