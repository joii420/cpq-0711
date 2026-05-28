package com.cpq.partmodel.service;

import com.cpq.common.dto.PageResult;
import com.cpq.partmodel.entity.PartModel;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.util.List;
import java.util.UUID;

/**
 * 3D 模型注册服务。骨架阶段：CRUD；
 * 转换流水线（UG NX .prt + .stp → FreeCAD → Blender → GLB Draco）后续切片实现。
 */
@ApplicationScoped
public class PartModelService {

    public PageResult<PartModel> list(int page, int size, String partNo, Boolean isCurrent) {
        StringBuilder query = new StringBuilder("1=1");
        java.util.List<Object> params = new java.util.ArrayList<>();
        if (partNo != null && !partNo.isBlank()) {
            params.add(partNo);
            query.append(" and partNo = ?").append(params.size());
        }
        if (isCurrent != null) {
            params.add(isCurrent);
            query.append(" and isCurrent = ?").append(params.size());
        }
        var pq = PartModel.find(query.toString(), Sort.by("uploadedAt").descending(), params.toArray());
        long total = pq.count();
        List<PartModel> rows = pq.page(Page.of(page, size)).list();
        return new PageResult<>(rows, page, size, total);
    }

    public PartModel getById(UUID id) {
        PartModel m = PartModel.findById(id);
        if (m == null) throw new NotFoundException("Part model not found: " + id);
        return m;
    }

    @Transactional
    public PartModel register(PartModel m) {
        m.id = null;
        // 若是新当前版本，把同 part_no 其他版本 isCurrent 置 false
        if (Boolean.TRUE.equals(m.isCurrent)) {
            PartModel.update("isCurrent = false where partNo = ?1 and isCurrent = true", m.partNo);
        }
        m.persist();
        return m;
    }

    @Transactional
    public PartModel setCurrent(UUID id) {
        PartModel m = getById(id);
        PartModel.update("isCurrent = false where partNo = ?1 and isCurrent = true", m.partNo);
        m.isCurrent = true;
        return m;
    }
}
