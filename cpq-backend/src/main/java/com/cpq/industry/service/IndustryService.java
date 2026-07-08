package com.cpq.industry.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.dto.Pagination;
import com.cpq.common.exception.BusinessException;
import com.cpq.industry.dto.IndustryDTO;
import com.cpq.industry.dto.IndustryRequest;
import com.cpq.industry.entity.Industry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class IndustryService {

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
        // 有客户引用则禁止删除（引用完整性，行业码存于 customer.industry_code）
        long refs = com.cpq.customer.entity.Customer.count("industry_code = ?1", e.code);
        if (refs > 0) throw new BusinessException("该行业已被 " + refs + " 个客户引用，不能删除");
        e.delete();
    }

    @Transactional
    public void batchDelete(List<UUID> ids) {
        for (UUID id : ids) delete(id);
    }
}
