package com.cpq.industry.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.dto.Pagination;
import com.cpq.common.exception.BusinessException;
import com.cpq.industry.dto.IndustryDTO;
import com.cpq.industry.dto.IndustryRequest;
import com.cpq.industry.entity.Industry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class IndustryService {

    @Inject
    EntityManager em;

    public PageResult<IndustryDTO> list(int page, int size, String status, String keyword) {
        page = Pagination.clampPage(page);
        size = Pagination.clampSize(size);
        StringBuilder q = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();
        if (status != null && !status.isBlank()) {
            q.append(" AND status = :status");
            params.put("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            q.append(" AND (name LIKE :kw OR code LIKE :kw)");
            params.put("kw", "%" + keyword + "%");
        }
        long total = Industry.count(q.toString(), params);
        List<IndustryDTO> content = Industry.<Industry>find(q + " ORDER BY createdAt DESC", params)
                .page(page, size).list()
                .stream().map(IndustryDTO::from).collect(Collectors.toList());
        return new PageResult<>(content, page, size, total);
    }

    /** 下拉用：只返回 ACTIVE 行业，按名称排序。 */
    public List<IndustryDTO> listActive() {
        return Industry.<Industry>find("status = ?1 ORDER BY name", "ACTIVE")
                .list().stream().map(IndustryDTO::from).collect(Collectors.toList());
    }

    public IndustryDTO getById(UUID id) {
        Industry e = Industry.findById(id);
        if (e == null) throw new BusinessException("行业不存在");
        return IndustryDTO.from(e);
    }

    @Transactional
    public IndustryDTO create(IndustryRequest req) {
        if (Industry.count("code = ?1", req.code) > 0)
            throw new BusinessException("行业编码已存在: " + req.code);
        Industry e = new Industry();
        e.code = req.code.trim();
        e.name = req.name.trim();
        e.status = (req.status == null || req.status.isBlank()) ? "ACTIVE" : req.status;
        e.persist();
        return IndustryDTO.from(e);
    }

    @Transactional
    public IndustryDTO update(UUID id, IndustryRequest req) {
        Industry e = Industry.findById(id);
        if (e == null) throw new BusinessException("行业不存在");
        if (!e.code.equals(req.code) && Industry.count("code = ?1", req.code) > 0)
            throw new BusinessException("行业编码已存在: " + req.code);
        e.code = req.code.trim();
        e.name = req.name.trim();
        if (req.status != null && !req.status.isBlank()) e.status = req.status;
        return IndustryDTO.from(e);
    }

    @Transactional
    public void delete(UUID id) {
        Industry e = Industry.findById(id);
        if (e == null) return;
        // 有客户引用则禁止删除（引用完整性，行业码存于 customer.industry_code 列）。
        // 注意：Customer 实体尚无 industryCode 属性（由 Plan1 Task9 补），故不能走 Panache
        // JPQL（"industry_code = ?1" 按实体属性名解析会抛 SemanticException）——改用 native SQL
        // 按数据库列直接计数，V312 迁移已建好该列，立即可用；风格对齐现役
        // CustomerService#checkNoActiveQuotations 的跨表 native 检查。
        long refs = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM customer WHERE industry_code = :c")
                .setParameter("c", e.code)
                .getSingleResult()).longValue();
        if (refs > 0) throw new BusinessException("该行业已被 " + refs + " 个客户引用，不能删除");
        e.delete();
    }

    @Transactional
    public void batchDelete(List<UUID> ids) {
        for (UUID id : ids) delete(id);
    }
}
