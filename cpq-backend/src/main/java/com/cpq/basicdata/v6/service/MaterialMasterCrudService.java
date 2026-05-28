package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.dto.CreateMaterialMasterRequest;
import com.cpq.basicdata.v6.dto.MaterialMasterDTO;
import com.cpq.basicdata.v6.entity.MaterialMaster;
import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * V6 料号主数据 CRUD 服务（供「产品管理 → 产品主数据」UI 使用）。
 *
 * <p>与 V6 导入服务（{@code com.cpq.basicdata.v6.quote.*Handler}）共表
 * ({@code material_master})但分职责:导入服务批量写入,本服务面向 UI 人工 CRUD。
 *
 * <p>注意:V6 设计不含 status_code(在产/停产)语义,如需此类状态请走 material_version_mgmt 等专表。
 */
@ApplicationScoped
public class MaterialMasterCrudService {

    public PageResult<MaterialMasterDTO> list(int page, int size, String keyword) {
        StringBuilder where = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (LOWER(materialNo) LIKE :kw OR LOWER(materialName) LIKE :kw)");
            params.put("kw", "%" + keyword.toLowerCase() + "%");
        }
        long total = MaterialMaster.count(where.toString(), params);
        List<MaterialMasterDTO> content = MaterialMaster.<MaterialMaster>find(where.toString(), Sort.by("createdAt").descending(), params)
                .page(Page.of(page, size))
                .list()
                .stream()
                .map(MaterialMasterDTO::from)
                .collect(Collectors.toList());

        return new PageResult<>(content, page, size, total);
    }

    public MaterialMasterDTO getById(UUID id) {
        MaterialMaster e = MaterialMaster.findById(id);
        if (e == null) throw new BusinessException(404, "MaterialMaster not found: " + id);
        return MaterialMasterDTO.from(e);
    }

    @Transactional
    public MaterialMasterDTO create(CreateMaterialMasterRequest req) {
        validateRequired(req);
        long existing = MaterialMaster.count("materialNo = ?1", req.materialNo);
        if (existing > 0) throw new BusinessException(400, "料号已存在: " + req.materialNo);

        MaterialMaster e = new MaterialMaster();
        applyRequest(e, req);
        e.persist();
        return MaterialMasterDTO.from(e);
    }

    @Transactional
    public MaterialMasterDTO update(UUID id, CreateMaterialMasterRequest req) {
        MaterialMaster e = MaterialMaster.findById(id);
        if (e == null) throw new BusinessException(404, "MaterialMaster not found: " + id);
        // material_no 是业务唯一键,改名要校验
        if (req.materialNo != null && !req.materialNo.equals(e.materialNo)) {
            long dup = MaterialMaster.count("materialNo = ?1 AND id != ?2", req.materialNo, id);
            if (dup > 0) throw new BusinessException(400, "料号已存在: " + req.materialNo);
        }
        applyRequest(e, req);
        return MaterialMasterDTO.from(e);
    }

    @Transactional
    public void delete(UUID id) {
        MaterialMaster e = MaterialMaster.findById(id);
        if (e == null) throw new BusinessException(404, "MaterialMaster not found: " + id);
        e.delete();
    }

    // ─────────────── 内部工具 ───────────────

    private void validateRequired(CreateMaterialMasterRequest req) {
        if (req.materialNo == null || req.materialNo.isBlank()) {
            throw new BusinessException(400, "料号不能为空");
        }
    }

    private void applyRequest(MaterialMaster e, CreateMaterialMasterRequest req) {
        e.materialNo = req.materialNo;
        e.materialName = req.materialName;
        e.specification = req.specification;
        e.dimension = req.dimension;
        e.oldMaterialNo = req.oldMaterialNo;
        e.materialType = req.materialType;
        e.usageProperty = req.usageProperty;
        e.unitWeight = req.unitWeight;
        e.standardUnit = req.standardUnit;
    }
}
