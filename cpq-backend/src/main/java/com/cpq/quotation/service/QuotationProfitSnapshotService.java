package com.cpq.quotation.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Resolves profit only for newly-created quotation rows. Persisted row snapshots remain authoritative. */
@ApplicationScoped
public class QuotationProfitSnapshotService {

    @Inject
    EntityManager em;

    /**
     * Resolve all requested material numbers in one SQL statement using the quotation's customer axis.
     * Missing products and products whose profit is NULL both resolve to {@code null}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, BigDecimal> resolveForNewLines(UUID quotationId, Collection<String> materialNos) {
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        if (materialNos != null) {
            for (String materialNo : materialNos) {
                if (materialNo != null && !materialNo.isBlank()) distinct.add(materialNo);
            }
        }
        if (quotationId == null || distinct.isEmpty()) return Map.of();

        List<Object[]> rows = em.createNativeQuery("""
                SELECT m.material_no, m.profit
                  FROM quotation q
                  JOIN customer c ON c.id = q.customer_id
                  JOIN ds_quote_material m ON m.customer_no = c.code
                 WHERE q.id = :quotationId
                   AND m.material_no IN (:materialNos)
                """)
                .setParameter("quotationId", quotationId)
                .setParameter("materialNos", new ArrayList<>(distinct))
                .getResultList();

        Map<String, BigDecimal> result = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) continue;
            result.put(row[0].toString(), decimalOrNull(row[1]));
        }
        return result;
    }

    public BigDecimal resolveForNewLine(UUID quotationId, String materialNo) {
        if (materialNo == null || materialNo.isBlank()) return null;
        return resolveForNewLines(quotationId, List.of(materialNo)).get(materialNo);
    }

    public static BigDecimal decimalOrNull(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        return value == null ? null : new BigDecimal(value.toString());
    }
}
