package com.cpq.configure.service;

import com.cpq.basicdata.v6.entity.ProcessMaster;
import com.cpq.configure.dto.CompositeProcessCandidateDTO;
import com.cpq.configure.dto.CompositeProcessDefDTO;
import com.cpq.configure.dto.CompositeProcessUpsertRequest;
import com.cpq.configure.entity.CompositeProcessDef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class CompositeProcessService {

    /** 组合工艺分类值域里代表"组装"的现网实值（DB 实查：无独立 '组合工艺' 枚举）。 */
    private static final String ASSEMBLY_CATEGORY = "ASSEMBLY";

    private static final ObjectMapper OM = new ObjectMapper();

    public List<CompositeProcessDefDTO> listActive() {
        return CompositeProcessDef.<CompositeProcessDef>find(
                "status = 'ACTIVE' ORDER BY sortOrder").list()
            .stream().map(this::toDTO).collect(Collectors.toList());
    }

    /**
     * B6（架构决策 2-2A 定稿）: 选配组合工艺候选 — 收敛到工序库
     * {@code process_master WHERE process_category='ASSEMBLY'}（现网实值，4 行）。
     * 取代旧 {@link #listActive()} 作为 {@code GET /api/cpq/composite-processes} 的候选源；
     * {@code composite_process_def} 停止喂给选配候选（表保留给 v0.4，见架构评审.md 决策2）。
     */
    public List<CompositeProcessCandidateDTO> listAssemblyCandidates() {
        return ProcessMaster.<ProcessMaster>find(
                "processCategory = ?1 ORDER BY processNo", ASSEMBLY_CATEGORY).list()
            .stream().map(this::toCandidateDTO).collect(Collectors.toList());
    }

    private CompositeProcessCandidateDTO toCandidateDTO(ProcessMaster pm) {
        CompositeProcessCandidateDTO dto = new CompositeProcessCandidateDTO();
        dto.code = pm.processNo;
        dto.name = pm.processName;
        dto.currency = pm.standardCurrency;
        dto.unit = pm.standardUnit;
        dto.defectRate = pm.defaultDefectRate;
        return dto;
    }

    public CompositeProcessDefDTO getById(UUID id) {
        CompositeProcessDef d = CompositeProcessDef.findById(id);
        if (d == null) {
            throw new NotFoundException("composite_process_def 不存在: " + id);
        }
        return toDTO(d);
    }

    @Transactional
    public CompositeProcessDefDTO create(CompositeProcessUpsertRequest req) {
        validateUpsert(req, null);
        CompositeProcessDef d = new CompositeProcessDef();
        d.code = req.code.trim();
        d.name = req.name.trim();
        d.icon = req.icon;
        d.description = req.description;
        d.paramSchema = serializeParamSchema(req.paramSchema);
        d.sortOrder = req.sortOrder == null ? 0 : req.sortOrder;
        d.status = req.status == null ? "ACTIVE" : req.status;
        d.createdAt = OffsetDateTime.now();
        d.persist();
        return getById(d.id);
    }

    @Transactional
    public CompositeProcessDefDTO update(UUID id, CompositeProcessUpsertRequest req) {
        validateUpsert(req, id);
        CompositeProcessDef d = CompositeProcessDef.findById(id);
        if (d == null) throw new NotFoundException("composite_process_def 不存在: " + id);
        d.code = req.code.trim();
        d.name = req.name.trim();
        d.icon = req.icon;
        d.description = req.description;
        d.paramSchema = serializeParamSchema(req.paramSchema);
        d.sortOrder = req.sortOrder == null ? d.sortOrder : req.sortOrder;
        d.status = req.status == null ? d.status : req.status;
        d.persist();
        return getById(id);
    }

    @Transactional
    public void deleteSoft(UUID id) {
        CompositeProcessDef d = CompositeProcessDef.findById(id);
        if (d == null) throw new NotFoundException("composite_process_def 不存在: " + id);
        d.status = "INACTIVE";
        d.persist();
    }

    private void validateUpsert(CompositeProcessUpsertRequest req, UUID idForUpdate) {
        if (req == null) throw new IllegalArgumentException("request body 必填");
        if (req.code == null || req.code.isBlank()) throw new IllegalArgumentException("code 必填");
        if (req.name == null || req.name.isBlank()) throw new IllegalArgumentException("name 必填");
        if (req.status != null
                && !List.of("ACTIVE", "INACTIVE").contains(req.status)) {
            throw new IllegalArgumentException("status 必须为 ACTIVE/INACTIVE");
        }

        // code 唯一性
        String trimmed = req.code.trim();
        CompositeProcessDef dup = CompositeProcessDef.find("code = ?1", trimmed).firstResult();
        if (dup != null && (idForUpdate == null || !dup.id.equals(idForUpdate))) {
            throw new IllegalArgumentException("code 已存在: " + trimmed);
        }

        // paramSchema 至少 1 项，字段必填
        if (req.paramSchema == null || req.paramSchema.isEmpty()) {
            throw new IllegalArgumentException("paramSchema 至少 1 项");
        }
        Set<String> paramIds = new HashSet<>();
        for (CompositeProcessUpsertRequest.ParamDef p : req.paramSchema) {
            if (p.id == null || p.id.isBlank()) {
                throw new IllegalArgumentException("paramSchema.id 必填");
            }
            if (p.label == null || p.label.isBlank()) {
                throw new IllegalArgumentException("paramSchema.label 必填: " + p.id);
            }
            if (p.type == null || !List.of("number", "text").contains(p.type)) {
                throw new IllegalArgumentException("paramSchema.type 必须为 number/text: " + p.id);
            }
            if (!paramIds.add(p.id.trim())) {
                throw new IllegalArgumentException("paramSchema.id 重复: " + p.id);
            }
        }
    }

    private String serializeParamSchema(List<CompositeProcessUpsertRequest.ParamDef> schema) {
        try {
            return OM.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("paramSchema 序列化失败", e);
        }
    }

    private CompositeProcessDefDTO toDTO(CompositeProcessDef d) {
        CompositeProcessDefDTO dto = new CompositeProcessDefDTO();
        dto.id = d.id;
        dto.code = d.code;
        dto.name = d.name;
        dto.icon = d.icon;
        dto.description = d.description;
        dto.paramSchema = d.paramSchema;
        dto.sortOrder = d.sortOrder;
        return dto;
    }
}
