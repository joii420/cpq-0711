package com.cpq.basicdata.service;

import com.cpq.basicdata.dto.ComparisonTagDTO;
import com.cpq.basicdata.dto.CreateComparisonTagRequest;
import com.cpq.basicdata.entity.ComparisonTag;
import com.cpq.common.exception.BusinessException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ComparisonTagService {

    private static final Logger LOG = Logger.getLogger(ComparisonTagService.class);

    public List<ComparisonTagDTO> list(String status) {
        String hql = (status == null || status.isBlank())
                ? "ORDER BY groupSortOrder, tagSortOrder, label"
                : "status = ?1 ORDER BY groupSortOrder, tagSortOrder, label";
        List<ComparisonTag> rows = (status == null || status.isBlank())
                ? ComparisonTag.find(hql).list()
                : ComparisonTag.find(hql, status).list();
        return rows.stream().map(ComparisonTagDTO::from).collect(Collectors.toList());
    }

    public ComparisonTagDTO getById(UUID id) {
        ComparisonTag t = ComparisonTag.findById(id);
        if (t == null) throw new BusinessException(404, "ComparisonTag not found: " + id);
        return ComparisonTagDTO.from(t);
    }

    @Transactional
    public ComparisonTagDTO create(CreateComparisonTagRequest req) {
        long dup = ComparisonTag.count("code = ?1", req.code);
        if (dup > 0) throw new BusinessException(400, "code already exists: " + req.code);

        ComparisonTag t = new ComparisonTag();
        t.code = req.code;
        t.label = req.label;
        t.groupName = req.groupName;
        if (req.groupSortOrder != null) t.groupSortOrder = req.groupSortOrder;
        if (req.tagSortOrder != null) t.tagSortOrder = req.tagSortOrder;
        if (req.status != null) t.status = req.status;
        t.description = req.description;
        t.isBuiltin = false;
        t.persist();

        LOG.infof("Created comparison tag code=%s id=%s", t.code, t.id);
        return ComparisonTagDTO.from(t);
    }

    @Transactional
    public ComparisonTagDTO update(UUID id, CreateComparisonTagRequest req) {
        ComparisonTag t = ComparisonTag.findById(id);
        if (t == null) throw new BusinessException(404, "ComparisonTag not found: " + id);

        if (req.code != null && !req.code.equals(t.code)) {
            if (Boolean.TRUE.equals(t.isBuiltin)) {
                throw new BusinessException(400, "Cannot change code of builtin tag");
            }
            long dup = ComparisonTag.count("code = ?1 AND id != ?2", req.code, id);
            if (dup > 0) throw new BusinessException(400, "code already exists: " + req.code);
            t.code = req.code;
        }
        if (req.label != null) t.label = req.label;
        if (req.groupName != null) t.groupName = req.groupName;
        if (req.groupSortOrder != null) t.groupSortOrder = req.groupSortOrder;
        if (req.tagSortOrder != null) t.tagSortOrder = req.tagSortOrder;
        if (req.status != null) t.status = req.status;
        if (req.description != null) t.description = req.description;

        return ComparisonTagDTO.from(t);
    }

    @Transactional
    public void delete(UUID id) {
        ComparisonTag t = ComparisonTag.findById(id);
        if (t == null) throw new BusinessException(404, "ComparisonTag not found: " + id);
        if (Boolean.TRUE.equals(t.isBuiltin)) {
            throw new BusinessException(400, "Cannot delete builtin tag (only disable allowed)");
        }
        t.delete();
        LOG.infof("Deleted comparison tag id=%s code=%s", id, t.code);
    }
}
