package com.cpq.dataset.versioning;

import com.cpq.dataset.registry.ColumnDef;
import com.cpq.dataset.registry.QuoteRegistry;
import com.cpq.dataset.registry.SheetDef;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlainTableWriterTest {

    @Test
    void skipsBindParameterForGeneratedVatColumn() {
        SheetDef material = new QuoteRegistry().sheets().stream()
                .filter(s -> "ds_quote_material".equals(s.tableName))
                .findFirst()
                .orElseThrow();

        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(Boolean.TRUE));
        List<String> boundNames = new ArrayList<>();
        doAnswer(invocation -> {
            boundNames.add(invocation.getArgument(0, String.class));
            return query;
        }).when(query).setParameter(anyString(), any());

        PlainTableWriter writer = new PlainTableWriter();
        writer.em = em;

        Map<String, Object> row = new LinkedHashMap<>();
        for (ColumnDef column : material.persistedColumns()) {
            row.put(column.name, "material_no".equals(column.name) ? "M-TEST" : null);
        }

        PlainTableWriter.UpsertResult result = writer.upsert(
                material, "CUST-TEST", List.of(row), "IMPORT", "test");

        assertEquals(1, result.inserted());
        assertEquals(0, result.updated());
        assertFalse(boundNames.contains("p0_9"), "tax_category_id is a SQL expression, not a bind parameter");
        assertTrue(boundNames.contains("p0_10"), "source must retain the column slot after tax_category_id");
        assertTrue(boundNames.contains("p0_14"), "customer_no must remain the final bind parameter");
    }
}
