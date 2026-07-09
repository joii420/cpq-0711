package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.entity.UnitPrice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.LocalDate;

/**
 * 统一 unit_price 表 upsert 工具。
 * <p>业务键 = uq_unit_price 索引 (system_type, price_type, COALESCE(cost_type,''), version_no, code,
 * COALESCE(customer_no,''), COALESCE(supplier_no,''), COALESCE(finished_material_no,''),
 * COALESCE(operation_no,''), COALESCE(seq_no,0), COALESCE(effective_date, '1900-01-01'))。
 */
@ApplicationScoped
public class UnitPriceWriter {

    @Inject
    EntityManager em;

    public int upsert(UnitPrice p) {
        String sql =
            "INSERT INTO unit_price (system_type, price_type, version_no, code, name, specification, " +
            "  dimension, finished_material_no, production_no, operation_no, cost_type, seq_no, discount_order, item_seq, plating_scheme_no, " +
            "  pricing_price, cost_ratio, market_ref_price, currency, unit, conversion_rate, " +
            "  recovery_discount, life_qty, life_unit, supplier_no, supplier_name, customer_no, " +
            "  customer_name, data_type, source_url, source_name, fetch_rule, premium_fee, " +
            "  fetched_price, fetch_time, effective_date, expire_date, base_value, " +
            "  is_fluctuate_with_material, material_increase_ratio, material_fixed_increase, " +
            "  defect_rate, created_at, updated_at, updated_by) " +
            "VALUES (:systemType, :priceType, :versionNo, :code, :name, :specification, " +
            "  :dimension, :finishedMaterialNo, :productionNo, :operationNo, :costType, :seqNo, :discountOrder, :itemSeq, :platingSchemeNo, " +
            "  :pricingPrice, :costRatio, :marketRefPrice, :currency, :unit, :conversionRate, " +
            "  :recoveryDiscount, :lifeQty, :lifeUnit, :supplierNo, :supplierName, :customerNo, " +
            "  :customerName, :dataType, :sourceUrl, :sourceName, :fetchRule, :premiumFee, " +
            "  :fetchedPrice, :fetchTime, :effectiveDate, :expireDate, :baseValue, " +
            "  :isFluctuateWithMaterial, :materialIncreaseRatio, :materialFixedIncrease, " +
            "  :defectRate, NOW(), NOW(), :updatedBy) " +
            "ON CONFLICT (system_type, price_type, COALESCE(cost_type,''), version_no, code, " +
            "  COALESCE(customer_no,''), COALESCE(supplier_no,''), COALESCE(finished_material_no,''), " +
            "  COALESCE(operation_no,''), COALESCE(seq_no,0), COALESCE(discount_order,0), " +
            "  COALESCE(item_seq,0), COALESCE(effective_date, DATE '1900-01-01')) " +
            "DO UPDATE SET " +
            "  name                       = COALESCE(EXCLUDED.name, unit_price.name), " +
            "  specification              = COALESCE(EXCLUDED.specification, unit_price.specification), " +
            "  dimension                  = COALESCE(EXCLUDED.dimension, unit_price.dimension), " +
            "  production_no              = COALESCE(EXCLUDED.production_no, unit_price.production_no), " +
            "  plating_scheme_no          = COALESCE(EXCLUDED.plating_scheme_no, unit_price.plating_scheme_no), " +
            "  pricing_price              = EXCLUDED.pricing_price, " +
            "  cost_ratio                 = COALESCE(EXCLUDED.cost_ratio, unit_price.cost_ratio), " +
            "  market_ref_price           = COALESCE(EXCLUDED.market_ref_price, unit_price.market_ref_price), " +
            "  currency                   = COALESCE(EXCLUDED.currency, unit_price.currency), " +
            "  unit                       = COALESCE(EXCLUDED.unit, unit_price.unit), " +
            "  conversion_rate            = COALESCE(EXCLUDED.conversion_rate, unit_price.conversion_rate), " +
            "  recovery_discount          = COALESCE(EXCLUDED.recovery_discount, unit_price.recovery_discount), " +
            "  life_qty                   = COALESCE(EXCLUDED.life_qty, unit_price.life_qty), " +
            "  life_unit                  = COALESCE(EXCLUDED.life_unit, unit_price.life_unit), " +
            "  supplier_name              = COALESCE(EXCLUDED.supplier_name, unit_price.supplier_name), " +
            "  customer_name              = COALESCE(EXCLUDED.customer_name, unit_price.customer_name), " +
            "  data_type                  = COALESCE(EXCLUDED.data_type, unit_price.data_type), " +
            "  source_url                 = COALESCE(EXCLUDED.source_url, unit_price.source_url), " +
            "  source_name                = COALESCE(EXCLUDED.source_name, unit_price.source_name), " +
            "  fetch_rule                 = COALESCE(EXCLUDED.fetch_rule, unit_price.fetch_rule), " +
            "  premium_fee                = COALESCE(EXCLUDED.premium_fee, unit_price.premium_fee), " +
            "  fetched_price              = COALESCE(EXCLUDED.fetched_price, unit_price.fetched_price), " +
            "  fetch_time                 = COALESCE(EXCLUDED.fetch_time, unit_price.fetch_time), " +
            "  expire_date                = COALESCE(EXCLUDED.expire_date, unit_price.expire_date), " +
            "  base_value                 = COALESCE(EXCLUDED.base_value, unit_price.base_value), " +
            "  is_fluctuate_with_material = COALESCE(EXCLUDED.is_fluctuate_with_material, unit_price.is_fluctuate_with_material), " +
            "  material_increase_ratio    = COALESCE(EXCLUDED.material_increase_ratio, unit_price.material_increase_ratio), " +
            "  material_fixed_increase    = COALESCE(EXCLUDED.material_fixed_increase, unit_price.material_fixed_increase), " +
            "  defect_rate                = COALESCE(EXCLUDED.defect_rate, unit_price.defect_rate), " +
            "  updated_at                 = NOW(), " +
            "  updated_by                 = EXCLUDED.updated_by";

        return em.createNativeQuery(sql)
            .setParameter("systemType", p.systemType)
            .setParameter("priceType", p.priceType)
            .setParameter("versionNo", nvl(p.versionNo, "V_DEFAULT"))
            .setParameter("code", p.code)
            .setParameter("name", p.name)
            .setParameter("specification", p.specification)
            .setParameter("dimension", p.dimension)
            .setParameter("finishedMaterialNo", p.finishedMaterialNo)
            .setParameter("productionNo", p.productionNo)
            .setParameter("operationNo", p.operationNo)
            .setParameter("costType", p.costType)
            .setParameter("seqNo", p.seqNo)
            .setParameter("discountOrder", p.discountOrder)
            .setParameter("itemSeq", p.itemSeq)
            .setParameter("platingSchemeNo", p.platingSchemeNo)
            .setParameter("pricingPrice", p.pricingPrice)
            .setParameter("costRatio", p.costRatio)
            .setParameter("marketRefPrice", p.marketRefPrice)
            .setParameter("currency", p.currency)
            .setParameter("unit", p.unit)
            .setParameter("conversionRate", p.conversionRate)
            .setParameter("recoveryDiscount", p.recoveryDiscount)
            .setParameter("lifeQty", p.lifeQty)
            .setParameter("lifeUnit", p.lifeUnit)
            .setParameter("supplierNo", p.supplierNo)
            .setParameter("supplierName", p.supplierName)
            .setParameter("customerNo", p.customerNo)
            .setParameter("customerName", p.customerName)
            .setParameter("dataType", p.dataType)
            .setParameter("sourceUrl", p.sourceUrl)
            .setParameter("sourceName", p.sourceName)
            .setParameter("fetchRule", p.fetchRule)
            .setParameter("premiumFee", p.premiumFee)
            .setParameter("fetchedPrice", p.fetchedPrice)
            .setParameter("fetchTime", p.fetchTime)
            .setParameter("effectiveDate", p.effectiveDate)
            .setParameter("expireDate", p.expireDate)
            .setParameter("baseValue", p.baseValue)
            .setParameter("isFluctuateWithMaterial", p.isFluctuateWithMaterial)
            .setParameter("materialIncreaseRatio", p.materialIncreaseRatio)
            .setParameter("materialFixedIncrease", p.materialFixedIncrease)
            .setParameter("defectRate", p.defectRate)
            .setParameter("updatedBy", p.updatedBy)
            .executeUpdate();
    }

    private static String nvl(String s, String def) {
        return (s == null || s.isBlank()) ? def : s;
    }

    /** 构造小工具：仅塞 system_type / price_type / cost_type / version_no / customer_no / updatedBy。 */
    public static UnitPrice newRow(String systemType, String priceType, String costType,
                                   String versionNo, String customerNo,
                                   java.util.UUID updatedBy) {
        UnitPrice p = new UnitPrice();
        p.systemType = systemType;
        p.priceType = priceType;
        p.costType = costType;
        p.versionNo = versionNo == null ? "V_DEFAULT" : versionNo;
        p.customerNo = customerNo;
        p.updatedBy = updatedBy;
        // pricingPrice 默认 NULL：固定金额费用由 Handler 显式赋值，比例/无固定值费用保持 NULL（D1）。
        p.effectiveDate = LocalDate.now();
        return p;
    }
}
