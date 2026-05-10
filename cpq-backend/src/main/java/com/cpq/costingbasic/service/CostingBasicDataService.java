package com.cpq.costingbasic.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.costingbasic.dto.*;
import com.cpq.costingbasic.entity.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 核价基础数据服务 —— 元素 / 材料 / 汇率 三种 kind 共用版本管理。
 *
 * 职责：
 *   - 版本主表 CRUD（含派生新草稿、发布、归档、设默认）
 *   - 三种明细的 CRUD（按 versionKind 分发，但不重复写三遍模板代码）
 */
@ApplicationScoped
public class CostingBasicDataService {

    private static final Logger LOG = Logger.getLogger(CostingBasicDataService.class);

    // ──────────────────────────────────────────────────────────────────
    // 版本主表
    // ──────────────────────────────────────────────────────────────────

    public List<CostingPriceVersionDTO> listVersions(String versionKind, String status) {
        validateKind(versionKind);
        StringBuilder hql = new StringBuilder("versionKind = ?1");
        List<Object> params = new ArrayList<>();
        params.add(versionKind);
        if (status != null && !status.isBlank()) {
            hql.append(" AND status = ?2");
            params.add(status);
        }
        hql.append(" ORDER BY isDefault DESC, createdAt DESC");
        List<CostingPriceVersion> rows = CostingPriceVersion.find(hql.toString(), params.toArray()).list();
        return rows.stream().map(v -> CostingPriceVersionDTO.from(v, countRows(v))).collect(Collectors.toList());
    }

    public CostingPriceVersionDTO getVersion(UUID id) {
        CostingPriceVersion v = CostingPriceVersion.findById(id);
        if (v == null) throw new BusinessException(404, "Version not found: " + id);
        return CostingPriceVersionDTO.from(v, countRows(v));
    }

    @Transactional
    public CostingPriceVersionDTO createVersion(CreateVersionRequest req) {
        validateKind(req.versionKind);
        if (req.versionNumber == null || req.versionNumber.isBlank()) {
            throw new BusinessException(400, "versionNumber 不能为空");
        }
        // 唯一性
        long existed = CostingPriceVersion.count("versionKind = ?1 AND versionNumber = ?2",
                req.versionKind, req.versionNumber);
        if (existed > 0) {
            throw new BusinessException(400, "该 kind 下已存在版本号: " + req.versionNumber);
        }

        CostingPriceVersion v = new CostingPriceVersion();
        v.versionKind = req.versionKind;
        v.versionNumber = req.versionNumber;
        v.notes = req.notes;
        v.status = CostingPriceVersion.STATUS_DRAFT;
        v.isDefault = false;
        v.persist();
        LOG.infof("Created costing version kind=%s number=%s id=%s",
                v.versionKind, v.versionNumber, v.id);
        return CostingPriceVersionDTO.from(v, 0L);
    }

    @Transactional
    public CostingPriceVersionDTO updateVersion(UUID id, CreateVersionRequest req) {
        CostingPriceVersion v = CostingPriceVersion.findById(id);
        if (v == null) throw new BusinessException(404, "Version not found: " + id);
        if (!CostingPriceVersion.STATUS_DRAFT.equals(v.status)) {
            throw new BusinessException(400, "仅草稿状态可编辑");
        }
        if (req.versionNumber != null && !req.versionNumber.isBlank()
                && !req.versionNumber.equals(v.versionNumber)) {
            long existed = CostingPriceVersion.count("versionKind = ?1 AND versionNumber = ?2 AND id != ?3",
                    v.versionKind, req.versionNumber, v.id);
            if (existed > 0) {
                throw new BusinessException(400, "该 kind 下已存在版本号: " + req.versionNumber);
            }
            v.versionNumber = req.versionNumber;
        }
        if (req.notes != null) v.notes = req.notes;
        return CostingPriceVersionDTO.from(v, countRows(v));
    }

    @Transactional
    public CostingPriceVersionDTO publishVersion(UUID id) {
        CostingPriceVersion v = CostingPriceVersion.findById(id);
        if (v == null) throw new BusinessException(404, "Version not found: " + id);
        if (!CostingPriceVersion.STATUS_DRAFT.equals(v.status)) {
            throw new BusinessException(400, "仅草稿状态可发布");
        }
        if (countRows(v) == 0L) {
            throw new BusinessException(400, "发布前必须先添加至少一条明细");
        }
        v.status = CostingPriceVersion.STATUS_PUBLISHED;
        v.publishedAt = OffsetDateTime.now();
        return CostingPriceVersionDTO.from(v, countRows(v));
    }

    @Transactional
    public CostingPriceVersionDTO archiveVersion(UUID id) {
        CostingPriceVersion v = CostingPriceVersion.findById(id);
        if (v == null) throw new BusinessException(404, "Version not found: " + id);
        if (!CostingPriceVersion.STATUS_PUBLISHED.equals(v.status)) {
            throw new BusinessException(400, "仅已发布状态可归档");
        }
        v.status = CostingPriceVersion.STATUS_ARCHIVED;
        if (Boolean.TRUE.equals(v.isDefault)) v.isDefault = false; // 归档时自动取消默认
        return CostingPriceVersionDTO.from(v, countRows(v));
    }

    @Transactional
    public CostingPriceVersionDTO setAsDefault(UUID id) {
        CostingPriceVersion v = CostingPriceVersion.findById(id);
        if (v == null) throw new BusinessException(404, "Version not found: " + id);
        if (!CostingPriceVersion.STATUS_PUBLISHED.equals(v.status)) {
            throw new BusinessException(400, "仅已发布状态可设为默认");
        }
        // 同 kind 其他版本取消默认（partial unique 已经强保证，但显式清更直观）
        CostingPriceVersion.update("isDefault = false WHERE versionKind = ?1 AND id != ?2",
                v.versionKind, v.id);
        v.isDefault = true;
        LOG.infof("Set default version kind=%s id=%s", v.versionKind, v.id);
        return CostingPriceVersionDTO.from(v, countRows(v));
    }

    @Transactional
    public void deleteVersion(UUID id) {
        CostingPriceVersion v = CostingPriceVersion.findById(id);
        if (v == null) throw new BusinessException(404, "Version not found: " + id);
        if (!CostingPriceVersion.STATUS_DRAFT.equals(v.status)) {
            throw new BusinessException(400, "仅草稿状态可删除（已发布请先归档）");
        }
        // FK CASCADE 自动清明细
        v.delete();
        LOG.infof("Deleted draft version id=%s", id);
    }

    /** 派生新草稿：复制 source 的明细，状态置为 DRAFT，version_number 自动递增"+1" */
    @Transactional
    public CostingPriceVersionDTO createDraftFrom(UUID sourceId, String newVersionNumber) {
        CostingPriceVersion src = CostingPriceVersion.findById(sourceId);
        if (src == null) throw new BusinessException(404, "Source version not found: " + sourceId);
        if (newVersionNumber == null || newVersionNumber.isBlank()) {
            // 自动 +1
            try {
                long n = Long.parseLong(src.versionNumber);
                newVersionNumber = String.valueOf(n + 1);
            } catch (NumberFormatException e) {
                newVersionNumber = src.versionNumber + "_copy";
            }
        }
        long existed = CostingPriceVersion.count("versionKind = ?1 AND versionNumber = ?2",
                src.versionKind, newVersionNumber);
        if (existed > 0) {
            throw new BusinessException(400, "目标版本号已存在: " + newVersionNumber);
        }

        CostingPriceVersion draft = new CostingPriceVersion();
        draft.versionKind = src.versionKind;
        draft.versionNumber = newVersionNumber;
        draft.status = CostingPriceVersion.STATUS_DRAFT;
        draft.notes = src.notes;
        draft.isDefault = false;
        draft.persist();

        // 复制明细
        switch (src.versionKind) {
            case CostingPriceVersion.KIND_ELEMENT -> {
                List<CostingElementPrice> rows = CostingElementPrice.list("versionId = ?1", src.id);
                for (CostingElementPrice e : rows) {
                    CostingElementPrice c = new CostingElementPrice();
                    c.versionId = draft.id;
                    c.elementCode = e.elementCode;
                    c.costingPrice = e.costingPrice;
                    c.marketRefPrice = e.marketRefPrice;
                    c.sourceUrl = e.sourceUrl;
                    c.sourceName = e.sourceName;
                    c.sourceRule = e.sourceRule;
                    c.currency = e.currency;
                    c.unit = e.unit;
                    c.discountRate = e.discountRate;
                    c.sortOrder = e.sortOrder;
                    c.persist();
                }
            }
            case CostingPriceVersion.KIND_MATERIAL -> {
                List<CostingMaterialPrice> rows = CostingMaterialPrice.list("versionId = ?1", src.id);
                for (CostingMaterialPrice m : rows) {
                    CostingMaterialPrice c = new CostingMaterialPrice();
                    c.versionId = draft.id;
                    c.materialNo = m.materialNo;
                    c.brandName = m.brandName;
                    c.spec = m.spec;
                    c.dimension = m.dimension;
                    c.costingPrice = m.costingPrice;
                    c.marketRefPrice = m.marketRefPrice;
                    c.sourceUrl = m.sourceUrl;
                    c.sourceName = m.sourceName;
                    c.sourceRule = m.sourceRule;
                    c.currency = m.currency;
                    c.unit = m.unit;
                    c.discountRate = m.discountRate;
                    c.sortOrder = m.sortOrder;
                    c.persist();
                }
            }
            case CostingPriceVersion.KIND_EXCHANGE -> {
                List<CostingExchangeRate> rows = CostingExchangeRate.list("versionId = ?1", src.id);
                for (CostingExchangeRate r : rows) {
                    CostingExchangeRate c = new CostingExchangeRate();
                    c.versionId = draft.id;
                    c.fromCurrency = r.fromCurrency;
                    c.toCurrency = r.toCurrency;
                    c.costingRate = r.costingRate;
                    c.marketRate = r.marketRate;
                    c.rateRule = r.rateRule;
                    c.sourceUrl = r.sourceUrl;
                    c.sortOrder = r.sortOrder;
                    c.persist();
                }
            }
        }
        LOG.infof("Created draft from source id=%s newVersion=%s", sourceId, newVersionNumber);
        return CostingPriceVersionDTO.from(draft, countRows(draft));
    }

    // ──────────────────────────────────────────────────────────────────
    // 元素价格明细
    // ──────────────────────────────────────────────────────────────────

    public List<ElementPriceDTO> listElementPrices(UUID versionId) {
        return CostingElementPrice.<CostingElementPrice>list(
                "versionId = ?1 ORDER BY sortOrder ASC, elementCode ASC", versionId)
                .stream().map(ElementPriceDTO::from).collect(Collectors.toList());
    }

    @Transactional
    public ElementPriceDTO createElementPrice(UUID versionId, ElementPriceDTO req) {
        CostingPriceVersion v = requireDraft(versionId, CostingPriceVersion.KIND_ELEMENT);
        if (req.elementCode == null || req.elementCode.isBlank()) {
            throw new BusinessException(400, "elementCode 不能为空");
        }
        long dup = CostingElementPrice.count("versionId = ?1 AND elementCode = ?2",
                v.id, req.elementCode);
        if (dup > 0) throw new BusinessException(400, "元素代码在该版本下已存在: " + req.elementCode);
        CostingElementPrice e = new CostingElementPrice();
        e.versionId = v.id;
        applyElementFields(e, req);
        e.persist();
        return ElementPriceDTO.from(e);
    }

    @Transactional
    public ElementPriceDTO updateElementPrice(UUID id, ElementPriceDTO req) {
        CostingElementPrice e = CostingElementPrice.findById(id);
        if (e == null) throw new BusinessException(404, "ElementPrice not found: " + id);
        requireDraft(e.versionId, CostingPriceVersion.KIND_ELEMENT);
        applyElementFields(e, req);
        return ElementPriceDTO.from(e);
    }

    @Transactional
    public void deleteElementPrice(UUID id) {
        CostingElementPrice e = CostingElementPrice.findById(id);
        if (e == null) throw new BusinessException(404, "ElementPrice not found: " + id);
        requireDraft(e.versionId, CostingPriceVersion.KIND_ELEMENT);
        e.delete();
    }

    private void applyElementFields(CostingElementPrice e, ElementPriceDTO d) {
        if (d.elementCode != null) e.elementCode = d.elementCode;
        if (d.costingPrice != null) e.costingPrice = d.costingPrice;
        e.marketRefPrice = d.marketRefPrice;
        e.sourceUrl = d.sourceUrl;
        e.sourceName = d.sourceName;
        e.sourceRule = d.sourceRule;
        if (d.currency != null) e.currency = d.currency;
        if (d.unit != null) e.unit = d.unit;
        e.discountRate = d.discountRate;
        if (d.sortOrder != null) e.sortOrder = d.sortOrder;
    }

    // ──────────────────────────────────────────────────────────────────
    // 材料价格明细
    // ──────────────────────────────────────────────────────────────────

    public List<MaterialPriceDTO> listMaterialPrices(UUID versionId) {
        return CostingMaterialPrice.<CostingMaterialPrice>list(
                "versionId = ?1 ORDER BY sortOrder ASC, materialNo ASC", versionId)
                .stream().map(MaterialPriceDTO::from).collect(Collectors.toList());
    }

    @Transactional
    public MaterialPriceDTO createMaterialPrice(UUID versionId, MaterialPriceDTO req) {
        CostingPriceVersion v = requireDraft(versionId, CostingPriceVersion.KIND_MATERIAL);
        if (req.materialNo == null || req.materialNo.isBlank()) {
            throw new BusinessException(400, "materialNo 不能为空");
        }
        long dup = CostingMaterialPrice.count("versionId = ?1 AND materialNo = ?2",
                v.id, req.materialNo);
        if (dup > 0) throw new BusinessException(400, "材料料号在该版本下已存在: " + req.materialNo);
        CostingMaterialPrice m = new CostingMaterialPrice();
        m.versionId = v.id;
        applyMaterialFields(m, req);
        m.persist();
        return MaterialPriceDTO.from(m);
    }

    @Transactional
    public MaterialPriceDTO updateMaterialPrice(UUID id, MaterialPriceDTO req) {
        CostingMaterialPrice m = CostingMaterialPrice.findById(id);
        if (m == null) throw new BusinessException(404, "MaterialPrice not found: " + id);
        requireDraft(m.versionId, CostingPriceVersion.KIND_MATERIAL);
        applyMaterialFields(m, req);
        return MaterialPriceDTO.from(m);
    }

    @Transactional
    public void deleteMaterialPrice(UUID id) {
        CostingMaterialPrice m = CostingMaterialPrice.findById(id);
        if (m == null) throw new BusinessException(404, "MaterialPrice not found: " + id);
        requireDraft(m.versionId, CostingPriceVersion.KIND_MATERIAL);
        m.delete();
    }

    private void applyMaterialFields(CostingMaterialPrice m, MaterialPriceDTO d) {
        if (d.materialNo != null) m.materialNo = d.materialNo;
        m.brandName = d.brandName;
        m.spec = d.spec;
        m.dimension = d.dimension;
        if (d.costingPrice != null) m.costingPrice = d.costingPrice;
        m.marketRefPrice = d.marketRefPrice;
        m.sourceUrl = d.sourceUrl;
        m.sourceName = d.sourceName;
        m.sourceRule = d.sourceRule;
        if (d.currency != null) m.currency = d.currency;
        if (d.unit != null) m.unit = d.unit;
        m.discountRate = d.discountRate;
        if (d.sortOrder != null) m.sortOrder = d.sortOrder;
    }

    // ──────────────────────────────────────────────────────────────────
    // 汇率明细
    // ──────────────────────────────────────────────────────────────────

    public List<ExchangeRateDTO> listExchangeRates(UUID versionId) {
        return CostingExchangeRate.<CostingExchangeRate>list(
                "versionId = ?1 ORDER BY sortOrder ASC, fromCurrency ASC, toCurrency ASC", versionId)
                .stream().map(ExchangeRateDTO::from).collect(Collectors.toList());
    }

    @Transactional
    public ExchangeRateDTO createExchangeRate(UUID versionId, ExchangeRateDTO req) {
        CostingPriceVersion v = requireDraft(versionId, CostingPriceVersion.KIND_EXCHANGE);
        if (req.fromCurrency == null || req.toCurrency == null
                || req.fromCurrency.isBlank() || req.toCurrency.isBlank()) {
            throw new BusinessException(400, "fromCurrency / toCurrency 不能为空");
        }
        long dup = CostingExchangeRate.count(
                "versionId = ?1 AND fromCurrency = ?2 AND toCurrency = ?3",
                v.id, req.fromCurrency, req.toCurrency);
        if (dup > 0) throw new BusinessException(400,
                "汇率对在该版本下已存在: " + req.fromCurrency + "→" + req.toCurrency);
        CostingExchangeRate r = new CostingExchangeRate();
        r.versionId = v.id;
        applyExchangeFields(r, req);
        r.persist();
        return ExchangeRateDTO.from(r);
    }

    @Transactional
    public ExchangeRateDTO updateExchangeRate(UUID id, ExchangeRateDTO req) {
        CostingExchangeRate r = CostingExchangeRate.findById(id);
        if (r == null) throw new BusinessException(404, "ExchangeRate not found: " + id);
        requireDraft(r.versionId, CostingPriceVersion.KIND_EXCHANGE);
        applyExchangeFields(r, req);
        return ExchangeRateDTO.from(r);
    }

    @Transactional
    public void deleteExchangeRate(UUID id) {
        CostingExchangeRate r = CostingExchangeRate.findById(id);
        if (r == null) throw new BusinessException(404, "ExchangeRate not found: " + id);
        requireDraft(r.versionId, CostingPriceVersion.KIND_EXCHANGE);
        r.delete();
    }

    private void applyExchangeFields(CostingExchangeRate r, ExchangeRateDTO d) {
        if (d.fromCurrency != null) r.fromCurrency = d.fromCurrency;
        if (d.toCurrency != null) r.toCurrency = d.toCurrency;
        if (d.costingRate != null) r.costingRate = d.costingRate;
        r.marketRate = d.marketRate;
        r.rateRule = d.rateRule;
        r.sourceUrl = d.sourceUrl;
        if (d.sortOrder != null) r.sortOrder = d.sortOrder;
    }

    // ──────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────

    private void validateKind(String kind) {
        if (!Set.of(CostingPriceVersion.KIND_ELEMENT,
                    CostingPriceVersion.KIND_MATERIAL,
                    CostingPriceVersion.KIND_EXCHANGE).contains(kind)) {
            throw new BusinessException(400, "Invalid versionKind: " + kind
                    + ", must be ELEMENT / MATERIAL / EXCHANGE");
        }
    }

    private CostingPriceVersion requireDraft(UUID versionId, String expectedKind) {
        CostingPriceVersion v = CostingPriceVersion.findById(versionId);
        if (v == null) throw new BusinessException(404, "Version not found: " + versionId);
        if (!expectedKind.equals(v.versionKind)) {
            throw new BusinessException(400, "Version kind mismatch: expected " + expectedKind
                    + " but got " + v.versionKind);
        }
        if (!CostingPriceVersion.STATUS_DRAFT.equals(v.status)) {
            throw new BusinessException(400, "仅草稿状态版本可编辑明细（status=" + v.status + "）");
        }
        return v;
    }

    private Long countRows(CostingPriceVersion v) {
        return switch (v.versionKind) {
            case CostingPriceVersion.KIND_ELEMENT  -> CostingElementPrice.count("versionId", v.id);
            case CostingPriceVersion.KIND_MATERIAL -> CostingMaterialPrice.count("versionId", v.id);
            case CostingPriceVersion.KIND_EXCHANGE -> CostingExchangeRate.count("versionId", v.id);
            default -> 0L;
        };
    }
}
