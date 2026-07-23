package com.cpq.elementprice.source;

import com.cpq.common.exception.BusinessException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 价格源 CRUD 服务（task-0722 · B4，契约见 api.md §1）。
 *
 * <p>不提供物理删除；停用语义三条见 §11.13.1：停用后不可被新策略/新导入选用、历史价格照常显示、
 * 存量策略继续按原样取价（{@code f_customer_element_price} 不过滤源状态，见 V352）。
 */
@ApplicationScoped
public class PriceSourceService {

    @Inject
    EntityManager em;

    /** GET /sources?status=&keyword= — 启用优先 → updated_at 倒序。*/
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<PriceSourceDTO> list(String status, String keyword) {
        boolean hasStatus = status != null && !status.isBlank();
        boolean hasKw = keyword != null && !keyword.isBlank();
        // HQL 不支持 "(status = 'ACTIVE') desc" 布尔表达式直接排序，改用 case when
        String orderBy = " order by case when status = 'ACTIVE' then 0 else 1 end, updatedAt desc";

        List<ElementPriceSource> rows;
        if (hasStatus && hasKw) {
            rows = ElementPriceSource.find(
                    "status = ?1 and (sourceName ilike ?2 or sourceUrl ilike ?2 or description ilike ?2)" + orderBy,
                    status.trim().toUpperCase(), "%" + keyword.trim() + "%").list();
        } else if (hasStatus) {
            rows = ElementPriceSource.find("status = ?1" + orderBy, status.trim().toUpperCase()).list();
        } else if (hasKw) {
            rows = ElementPriceSource.find(
                    "(sourceName ilike ?1 or sourceUrl ilike ?1 or description ilike ?1)" + orderBy,
                    "%" + keyword.trim() + "%").list();
        } else {
            rows = ElementPriceSource.find(orderBy.trim()).list();
        }
        return rows.stream().map(PriceSourceDTO::from).toList();
    }

    @Transactional
    public PriceSourceDTO create(PriceSourceUpsertRequest req, UUID operatorId) {
        String name = trimOrNull(req == null ? null : req.sourceName);
        if (name == null) throw new BusinessException(400, "源名称不能为空");
        String url = req == null ? null : trimOrNull(req.sourceUrl);
        String status = normalizeStatus(req == null ? null : req.status, "ACTIVE");

        assertNoConflict(name, url, null);

        ElementPriceSource e = new ElementPriceSource();
        e.sourceName = name;
        e.sourceUrl = url;
        e.sourceType = "MANUAL";   // 后端固定写死，不接受前端传值（§11.13）
        e.description = req == null ? null : req.description;
        e.status = status;
        e.createdAt = OffsetDateTime.now();
        e.updatedAt = OffsetDateTime.now();
        e.createdBy = operatorId;
        e.updatedBy = operatorId;
        e.persist();
        em.flush();
        return PriceSourceDTO.from(e);
    }

    @Transactional
    public PriceSourceDTO update(UUID id, PriceSourceUpsertRequest req, UUID operatorId) {
        ElementPriceSource e = ElementPriceSource.findById(id);
        if (e == null) throw new NotFoundException("价格源不存在: " + id);

        String name = trimOrNull(req == null ? null : req.sourceName);
        if (name == null) throw new BusinessException(400, "源名称不能为空");
        String url = req == null ? null : trimOrNull(req.sourceUrl);

        assertNoConflict(name, url, id);

        e.sourceName = name;
        e.sourceUrl = url;
        e.description = req == null ? null : req.description;
        if (req != null && req.status != null && !req.status.isBlank()) {
            e.status = normalizeStatus(req.status, e.status);
        }
        e.updatedAt = OffsetDateTime.now();
        e.updatedBy = operatorId;
        em.flush();
        return PriceSourceDTO.from(e);
    }

    /** POST /sources/{id}/status — 停用/启用（不提供物理删除，§11.13.1）。*/
    @Transactional
    public PriceSourceDTO updateStatus(UUID id, String status, UUID operatorId) {
        ElementPriceSource e = ElementPriceSource.findById(id);
        if (e == null) throw new NotFoundException("价格源不存在: " + id);
        e.status = normalizeStatus(status, e.status);
        e.updatedAt = OffsetDateTime.now();
        e.updatedBy = operatorId;
        em.flush();
        return PriceSourceDTO.from(e);
    }

    // ── helpers ──

    private void assertNoConflict(String name, String url, UUID excludeId) {
        String urlKey = url == null ? "" : url;
        List<ElementPriceSource> dup = ElementPriceSource.find(
                "sourceName = ?1 and coalesce(sourceUrl,'') = ?2", name, urlKey).list();
        boolean conflict = dup.stream().anyMatch(d -> excludeId == null || !d.id.equals(excludeId));
        if (conflict) {
            throw new BusinessException(409, "源名称 + 网址 已存在");
        }
    }

    private String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String normalizeStatus(String s, String fallback) {
        if (s == null || s.isBlank()) return fallback;
        String u = s.trim().toUpperCase();
        if (!u.equals("ACTIVE") && !u.equals("DISABLED")) {
            throw new BusinessException(400, "status 必须为 ACTIVE/DISABLED");
        }
        return u;
    }
}
