package com.cpq.tax.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.tax.dto.*;
import com.cpq.tax.entity.TaxCategory;
import com.cpq.tax.entity.TaxCategoryVatVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
public class TaxCategoryService {
    @Inject EntityManager em;

    public List<TaxCategoryDTO> list(String keyword, String status, LocalDate effectiveOn) {
        StringBuilder jpql = new StringBuilder("1=1");
        java.util.Map<String,Object> params = new java.util.HashMap<>();
        if (keyword != null && !keyword.isBlank()) {
            jpql.append(" AND (lower(code) like :kw OR lower(name) like :kw)");
            params.put("kw", "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (status != null && !status.isBlank()) { jpql.append(" AND status = :status"); params.put("status", status); }
        List<TaxCategory> rows = TaxCategory.list(jpql + " ORDER BY isDefault DESC, code ASC", params);
        if (rows.isEmpty()) return List.of();
        LocalDate date = effectiveOn == null ? LocalDate.now() : effectiveOn;
        List<UUID> ids = rows.stream().map(c -> c.id).toList();
        List<TaxCategoryVatVersion> versions = TaxCategoryVatVersion.list(
                "taxCategoryId in ?1 AND status = 'ACTIVE' AND effectiveFrom <= ?2 "
                        + "AND (effectiveTo IS NULL OR effectiveTo > ?2) ORDER BY effectiveFrom DESC",
                ids, date);
        java.util.Map<UUID, TaxCategoryVatVersion> effective = new java.util.HashMap<>();
        for (TaxCategoryVatVersion v : versions) effective.putIfAbsent(v.taxCategoryId, v);
        return rows.stream().map(c -> {
            TaxCategoryDTO d = TaxCategoryDTO.from(c);
            TaxCategoryVatVersion v = effective.get(c.id);
            if (v != null) { d.effectiveVatRate = v.vatRate; d.effectiveVersionId = v.id; }
            return d;
        }).toList();
    }

    @Transactional
    public TaxCategoryDTO create(TaxCategorySaveRequest r) {
        String code = normalizeCode(r.code);
        if (code == null || r.name == null || r.name.isBlank()) throw new BusinessException(400, "税务分类编码和名称不能为空");
        if (TaxCategory.count("code", code) > 0) throw new BusinessException(409, "税务分类编码已存在");
        TaxCategory c = new TaxCategory(); c.code = code; c.name = r.name.trim(); c.status = status(r.status); c.description = r.description; c.persist();
        return TaxCategoryDTO.from(c);
    }

    @Transactional
    public TaxCategoryDTO update(UUID id, TaxCategorySaveRequest r) {
        TaxCategory c = find(id);
        if (r.name != null && !r.name.isBlank()) c.name = r.name.trim();
        if (r.status != null) {
            String nextStatus = status(r.status);
            if (c.isDefault && "INACTIVE".equals(nextStatus)) {
                throw new BusinessException(409, "默认税务分类不能停用，请先指定新的默认分类");
            }
            c.status = nextStatus;
        }
        if (r.description != null) c.description = r.description;
        return TaxCategoryDTO.from(c);
    }

    @Transactional
    public void setDefault(UUID id) {
        TaxCategory c = find(id);
        if (!"ACTIVE".equals(c.status)) throw new BusinessException(400, "停用分类不能设为默认");
        em.createNativeQuery("UPDATE tax_category SET is_default=false WHERE is_default=true").executeUpdate();
        c.isDefault = true;
    }

    public List<TaxCategoryVatVersionDTO> versions(UUID categoryId) {
        find(categoryId);
        return TaxCategoryVatVersion.<TaxCategoryVatVersion>list("taxCategoryId = ?1 ORDER BY effectiveFrom DESC", categoryId)
                .stream().map(TaxCategoryVatVersionDTO::from).toList();
    }

    @Transactional
    public TaxCategoryVatVersionDTO addVersion(UUID categoryId, TaxCategoryVatVersionSaveRequest r) {
        TaxCategory c = find(categoryId);
        if (!"ACTIVE".equals(c.status)) throw new BusinessException(400, "停用分类不能新增税率版本");
        validateVersion(r);
        if (overlap(categoryId, r.effectiveFrom, r.effectiveTo, null)) throw new BusinessException(409, "TAX_RATE_PERIOD_OVERLAP");
        TaxCategoryVatVersion v = new TaxCategoryVatVersion(); v.taxCategoryId = categoryId; v.vatRate = r.vatRate; v.effectiveFrom = r.effectiveFrom; v.effectiveTo = r.effectiveTo; v.status = status(r.status); v.persist();
        return TaxCategoryVatVersionDTO.from(v);
    }

    @Transactional
    public TaxCategoryVatVersionDTO updateVersion(UUID categoryId, UUID versionId, TaxCategoryVatVersionSaveRequest r) {
        find(categoryId);
        TaxCategoryVatVersion v = TaxCategoryVatVersion.findById(versionId);
        if (v == null || !categoryId.equals(v.taxCategoryId)) throw new BusinessException(404, "税率版本不存在");
        Number used = (Number) em.createNativeQuery("SELECT count(*) FROM quotation_line_item WHERE tax_category_version_id=:id")
                .setParameter("id", versionId).getSingleResult();
        if (used.longValue() > 0) throw new BusinessException(409, "TAX_CATEGORY_VERSION_IN_USE");
        validateVersion(r);
        if (overlap(categoryId, r.effectiveFrom, r.effectiveTo, versionId)) throw new BusinessException(409, "TAX_RATE_PERIOD_OVERLAP");
        v.vatRate = r.vatRate; v.effectiveFrom = r.effectiveFrom; v.effectiveTo = r.effectiveTo; if (r.status != null) v.status = status(r.status);
        return TaxCategoryVatVersionDTO.from(v);
    }

    public TaxCategoryVatVersion resolveVersion(UUID categoryId, LocalDate date) {
        return resolveVersionInternal(categoryId, date == null ? LocalDate.now() : date);
    }

    private TaxCategoryVatVersion resolveVersionInternal(UUID id, LocalDate d) {
        List<TaxCategoryVatVersion> rows = TaxCategoryVatVersion.list("taxCategoryId = ?1 AND status = 'ACTIVE' AND effectiveFrom <= ?2 AND (effectiveTo IS NULL OR effectiveTo > ?2) ORDER BY effectiveFrom DESC", id, d);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public UUID defaultCategoryId() {
        TaxCategory c = TaxCategory.find("isDefault = true AND status = 'ACTIVE'").firstResult();
        return c == null ? null : c.id;
    }

    private TaxCategory find(UUID id) { TaxCategory c = TaxCategory.findById(id); if (c == null) throw new BusinessException(404, "税务分类不存在"); return c; }
    private static String normalizeCode(String s) { return s == null ? null : s.trim().toUpperCase(Locale.ROOT); }
    private static String status(String s) { return s == null || s.isBlank() ? "ACTIVE" : s.trim().toUpperCase(Locale.ROOT); }
    private static void validateVersion(TaxCategoryVatVersionSaveRequest r) {
        if (r.vatRate == null || r.vatRate.signum() < 0 || r.vatRate.compareTo(new BigDecimal("100")) > 0) throw new BusinessException(400, "VAT 税率必须在 0 到 100 之间");
        if (r.effectiveFrom == null || (r.effectiveTo != null && !r.effectiveFrom.isBefore(r.effectiveTo))) throw new BusinessException(400, "VAT 生效区间无效");
    }
    private boolean overlap(UUID id, LocalDate from, LocalDate to, UUID exclude) {
        String sql = "SELECT count(*) FROM tax_category_vat_version WHERE tax_category_id=:id AND status='ACTIVE' AND (:exclude IS NULL OR id<>:exclude) AND effective_from < COALESCE(:to, DATE '9999-12-31') AND COALESCE(effective_to, DATE '9999-12-31') > :from";
        Number n = (Number) em.createNativeQuery(sql).setParameter("id", id).setParameter("from", from).setParameter("to", to).setParameter("exclude", exclude).getSingleResult();
        return n.longValue() > 0;
    }
}
