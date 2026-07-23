package com.cpq.elementprice.priceimport;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 单行价格导入写入器（task-0722 · B5）。
 *
 * <p><b>🔒 事务边界（§11.3.2）</b>：每行独立 {@code REQUIRES_NEW} 事务，失败行只回滚自己，
 * 不影响其它行入库。调用方（{@link PriceImportService}）不开外层事务，逐行调用本方法。
 *
 * <p>写库：{@code INSERT ... ON CONFLICT (element_name, COALESCE(source_id::TEXT,''), price_date)
 * DO UPDATE}，{@code fetch_status='IMPORT'}。覆盖场景先读旧值以便回显"原值 X → 新值 Y"。
 */
@ApplicationScoped
public class PriceImportRowWriter {

    @Inject
    EntityManager em;

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public PriceImportRowDTO writeRow(int rowNo, String elementCodeRaw, BigDecimal price,
                                       String currencyRaw, String priceUnitRaw,
                                       UUID sourceId, LocalDate priceDate, UUID operatorId) {
        PriceImportRowDTO row = new PriceImportRowDTO();
        row.rowNo = rowNo;
        String elementCode = elementCodeRaw == null ? null : elementCodeRaw.trim();
        row.elementCode = elementCode;
        row.price = price;
        String currency = blankToDefault(currencyRaw, "CNY");
        String priceUnit = blankToDefault(priceUnitRaw, "元/kg");
        row.currency = currency;
        row.priceUnit = priceUnit;

        // ── 行级校验（需求 §11.3.1）──
        if (elementCode == null || elementCode.isBlank()) {
            row.result = "FAILED";
            row.message = "元素符号不能为空";
            return row;
        }
        @SuppressWarnings("unchecked")
        List<String> statusRows = em.createNativeQuery(
                "SELECT status FROM element WHERE element_code = :code")
                .setParameter("code", elementCode)
                .getResultList();
        if (statusRows.isEmpty()) {
            row.result = "FAILED";
            row.message = "元素符号「" + elementCode + "」在元素管理中不存在";
            return row;
        }
        if (!"ACTIVE".equals(statusRows.get(0))) {
            row.result = "FAILED";
            row.message = "元素「" + elementCode + "」已停用，不可导入价格";
            return row;
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            row.result = "FAILED";
            row.message = "单价必须大于 0";
            return row;
        }

        // ── 覆盖场景先读旧值（用于结果回显）──
        @SuppressWarnings("unchecked")
        List<Object> existing = em.createNativeQuery(
                "SELECT raw_price FROM element_daily_price " +
                "WHERE element_name = :en AND source_id = :sid AND price_date = :pd")
                .setParameter("en", elementCode)
                .setParameter("sid", sourceId)
                .setParameter("pd", priceDate)
                .getResultList();
        boolean isUpdate = !existing.isEmpty();
        BigDecimal oldPrice = (isUpdate && existing.get(0) != null) ? new BigDecimal(existing.get(0).toString()) : null;

        em.createNativeQuery(
                "INSERT INTO element_daily_price " +
                "  (id, element_name, source_id, price_date, raw_price, currency, price_unit, " +
                "   fetch_status, created_at, updated_at, created_by, updated_by) " +
                "VALUES " +
                "  (gen_random_uuid(), :en, :sid, :pd, :price, :cur, :unit, " +
                "   'IMPORT', NOW(), NOW(), :uid, :uid) " +
                "ON CONFLICT (element_name, COALESCE(source_id::TEXT,''), price_date) " +
                "DO UPDATE SET " +
                "  raw_price    = EXCLUDED.raw_price, " +
                "  currency     = EXCLUDED.currency, " +
                "  price_unit   = EXCLUDED.price_unit, " +
                "  fetch_status = 'IMPORT', " +
                "  updated_at   = NOW(), " +
                "  updated_by   = EXCLUDED.updated_by")
                .setParameter("en", elementCode)
                .setParameter("sid", sourceId)
                .setParameter("pd", priceDate)
                .setParameter("price", price)
                .setParameter("cur", currency)
                .setParameter("unit", priceUnit)
                .setParameter("uid", operatorId)
                .executeUpdate();

        row.result = isUpdate ? "UPDATED" : "CREATED";
        row.message = isUpdate
                ? "原值 " + fmt(oldPrice) + " → 新值 " + fmt(price)
                : null;
        return row;
    }

    private String blankToDefault(String s, String def) {
        return (s == null || s.isBlank()) ? def : s.trim();
    }

    private String fmt(BigDecimal v) {
        return v == null ? "—" : v.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
