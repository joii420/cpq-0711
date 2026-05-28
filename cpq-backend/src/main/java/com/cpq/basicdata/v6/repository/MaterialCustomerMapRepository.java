package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.entity.MaterialCustomerMap;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.UUID;

@ApplicationScoped
public class MaterialCustomerMapRepository implements PanacheRepositoryBase<MaterialCustomerMap, UUID> {

    @Inject
    EntityManager em;

    /** Upsert by (material_no, customer_no, customer_product_no). */
    public int upsert(String materialNo, String customerNo, String customerName,
                      String customerMaterialName, String customerProductNo,
                      String customerDrawingNo, Integer seqNo, String paymentMethod,
                      String baseCurrency, String quoteCurrency, BigDecimal exchangeRate,
                      UUID updatedBy) {
        String sql =
            "INSERT INTO material_customer_map (material_no, customer_no, customer_name, " +
            "  customer_material_name, customer_product_no, customer_drawing_no, seq_no, " +
            "  payment_method, base_currency, quote_currency, exchange_rate, " +
            "  created_at, updated_at, updated_by) " +
            "VALUES (:materialNo, :customerNo, :customerName, :customerMaterialName, " +
            "  :customerProductNo, :customerDrawingNo, :seqNo, :paymentMethod, " +
            "  :baseCurrency, :quoteCurrency, :exchangeRate, NOW(), NOW(), :updatedBy) " +
            "ON CONFLICT (material_no, customer_no, customer_product_no) DO UPDATE SET " +
            "  customer_name          = COALESCE(EXCLUDED.customer_name,          material_customer_map.customer_name), " +
            "  customer_material_name = COALESCE(EXCLUDED.customer_material_name, material_customer_map.customer_material_name), " +
            "  customer_drawing_no    = COALESCE(EXCLUDED.customer_drawing_no,    material_customer_map.customer_drawing_no), " +
            "  seq_no                 = COALESCE(EXCLUDED.seq_no,                 material_customer_map.seq_no), " +
            "  payment_method         = COALESCE(EXCLUDED.payment_method,         material_customer_map.payment_method), " +
            "  base_currency          = COALESCE(EXCLUDED.base_currency,          material_customer_map.base_currency), " +
            "  quote_currency         = COALESCE(EXCLUDED.quote_currency,         material_customer_map.quote_currency), " +
            "  exchange_rate          = COALESCE(EXCLUDED.exchange_rate,          material_customer_map.exchange_rate), " +
            "  updated_at             = NOW(), " +
            "  updated_by             = EXCLUDED.updated_by";
        return em.createNativeQuery(sql)
            .setParameter("materialNo", materialNo)
            .setParameter("customerNo", customerNo)
            .setParameter("customerName", customerName)
            .setParameter("customerMaterialName", customerMaterialName)
            .setParameter("customerProductNo", customerProductNo)
            .setParameter("customerDrawingNo", customerDrawingNo)
            .setParameter("seqNo", seqNo)
            .setParameter("paymentMethod", paymentMethod)
            .setParameter("baseCurrency", baseCurrency)
            .setParameter("quoteCurrency", quoteCurrency)
            .setParameter("exchangeRate", exchangeRate)
            .setParameter("updatedBy", updatedBy)
            .executeUpdate();
    }
}
