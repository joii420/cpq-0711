package com.cpq.costing.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.costing.dto.CostingTemplateDTO;
import com.cpq.costing.dto.CreateCostingTemplateRequest;
import com.cpq.costing.entity.CostingTemplate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class CostingTemplateService {

    private static final Logger LOG = Logger.getLogger(CostingTemplateService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * V74 起：列表只按 status / linkedTemplateId 过滤（产品分类已移除）。
     * 报价单/核价单 Excel 视图按所选模板反查关联 Excel 模板的入口。
     */
    public List<CostingTemplateDTO> list(String status, UUID linkedTemplateId) {
        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();
        if (status != null && !status.isBlank()) {
            hql.append(" AND status = :status");
            params.put("status", status);
        }
        if (linkedTemplateId != null) {
            hql.append(" AND linkedTemplateId = :linkedTemplateId");
            params.put("linkedTemplateId", linkedTemplateId);
        }
        hql.append(" ORDER BY createdAt DESC");
        List<CostingTemplate> rows = CostingTemplate.find(hql.toString(), params).list();
        return rows.stream().map(CostingTemplateDTO::from).collect(Collectors.toList());
    }

    public CostingTemplateDTO getById(UUID id) {
        CostingTemplate t = CostingTemplate.findById(id);
        if (t == null) throw new BusinessException(404, "CostingTemplate not found: " + id);
        return CostingTemplateDTO.from(t);
    }

    @Transactional
    public CostingTemplateDTO create(CreateCostingTemplateRequest req) {
        // CTPL-COLUMN-FORMULA-06: validate formula column references
        if (req.columns != null) {
            validateFormulaReferences(toJson(req.columns, "[]"));
        }

        CostingTemplate t = new CostingTemplate();
        t.seriesId = req.seriesId != null ? req.seriesId : UUID.randomUUID();
        t.name = req.name;
        if (req.isDefault != null) t.isDefault = req.isDefault;
        if (req.version != null) t.version = req.version;
        t.description = req.description;
        t.columns = toJson(req.columns, "[]");
        t.referencedVariables = toJson(req.referencedVariables, "[]");
        // V73：关联的报价/核价模板 ID（可空，V74 起也是默认归属维度）
        if (req.linkedTemplateId != null) {
            com.cpq.template.entity.Template linked =
                    com.cpq.template.entity.Template.findById(req.linkedTemplateId);
            if (linked == null) {
                throw new BusinessException(400, "Linked template not found: " + req.linkedTemplateId);
            }
            t.linkedTemplateId = linked.id;
        }
        t.persist();

        // V74 起：is_default 唯一性按 linked_template_id 维度（同一个关联模板下最多一个默认）
        if (Boolean.TRUE.equals(t.isDefault) && t.linkedTemplateId != null) {
            clearOtherDefaults(t.linkedTemplateId, t.id);
        }

        LOG.infof("Created costing template id=%s name=%s", t.id, t.name);
        return CostingTemplateDTO.from(t);
    }

    @Transactional
    public CostingTemplateDTO update(UUID id, CreateCostingTemplateRequest req) {
        CostingTemplate t = CostingTemplate.findById(id);
        if (t == null) throw new BusinessException(404, "CostingTemplate not found: " + id);
        if (!"DRAFT".equals(t.status)) {
            throw new BusinessException(400, "Only DRAFT templates can be edited");
        }

        if (req.name != null) t.name = req.name;
        if (req.isDefault != null) t.isDefault = req.isDefault;
        if (req.description != null) t.description = req.description;
        if (req.columns != null) {
            // CTPL-COLUMN-FORMULA-06: validate formula column references before saving
            String columnsJson = toJson(req.columns, "[]");
            validateFormulaReferences(columnsJson);
            t.columns = columnsJson;
        }
        if (req.referencedVariables != null) t.referencedVariables = toJson(req.referencedVariables, "[]");
        // V73：linkedTemplateId 在 update 中也支持改 —— DRAFT 状态下用户可重新指定关联模板
        if (req.linkedTemplateId != null) {
            com.cpq.template.entity.Template linked =
                    com.cpq.template.entity.Template.findById(req.linkedTemplateId);
            if (linked == null) {
                throw new BusinessException(400, "Linked template not found: " + req.linkedTemplateId);
            }
            t.linkedTemplateId = linked.id;
        }

        if (Boolean.TRUE.equals(t.isDefault) && t.linkedTemplateId != null) {
            clearOtherDefaults(t.linkedTemplateId, t.id);
        }
        return CostingTemplateDTO.from(t);
    }

    /**
     * V73：单独 setter，支持设置 / 解除关联。
     * @param id costing_template id
     * @param linkedTemplateId 目标 template.id；传 null 解除关联（DB 写 NULL）
     */
    @Transactional
    public CostingTemplateDTO setLinkedTemplate(UUID id, UUID linkedTemplateId) {
        CostingTemplate t = CostingTemplate.findById(id);
        if (t == null) throw new BusinessException(404, "CostingTemplate not found: " + id);
        if (linkedTemplateId == null) {
            t.linkedTemplateId = null;
            LOG.infof("Cleared linked_template on costing_template id=%s", id);
            return CostingTemplateDTO.from(t);
        }
        com.cpq.template.entity.Template linked =
                com.cpq.template.entity.Template.findById(linkedTemplateId);
        if (linked == null) {
            throw new BusinessException(400, "Linked template not found: " + linkedTemplateId);
        }
        t.linkedTemplateId = linked.id;
        LOG.infof("Set linked_template=%s on costing_template id=%s", linked.id, id);
        return CostingTemplateDTO.from(t);
    }

    @Transactional
    public CostingTemplateDTO publish(UUID id) {
        CostingTemplate t = CostingTemplate.findById(id);
        if (t == null) throw new BusinessException(404, "CostingTemplate not found: " + id);
        if (!"DRAFT".equals(t.status)) throw new BusinessException(400, "Only DRAFT can be published");

        t.status = "PUBLISHED";
        t.publishedAt = OffsetDateTime.now();
        t.version = nextVersion(t.seriesId, t.version);
        return CostingTemplateDTO.from(t);
    }

    @Transactional
    public CostingTemplateDTO archive(UUID id) {
        CostingTemplate t = CostingTemplate.findById(id);
        if (t == null) throw new BusinessException(404, "CostingTemplate not found: " + id);
        t.status = "ARCHIVED";
        if (Boolean.TRUE.equals(t.isDefault)) t.isDefault = false;
        return CostingTemplateDTO.from(t);
    }

    /**
     * 已归档/已发布 → 派生新草稿（同 series；与 template.createNewDraft 语义一致）。
     * 复制 name / columns / referenced_variables / linked_template_id / description；
     * status=DRAFT；is_default=false（避免多份默认）；version 不变（用户 publish 时再升版本）。
     * 同 series 仅允许同时存在一份 DRAFT。
     */
    @Transactional
    public CostingTemplateDTO createNewDraft(UUID sourceId) {
        CostingTemplate source = CostingTemplate.findById(sourceId);
        if (source == null) throw new BusinessException(404, "CostingTemplate not found: " + sourceId);

        CostingTemplate existingDraft = CostingTemplate.<CostingTemplate>find(
                "seriesId = ?1 AND status = 'DRAFT'", source.seriesId).firstResult();
        if (existingDraft != null) {
            throw new BusinessException(400,
                    "该模板系列已存在草稿版本（id=" + existingDraft.id + "），请先发布或删除现有草稿");
        }

        CostingTemplate draft = new CostingTemplate();
        draft.seriesId = source.seriesId;
        draft.name = source.name;
        draft.version = source.version; // publish 时按 series 自动升版本
        draft.status = "DRAFT";
        draft.description = source.description;
        draft.columns = source.columns;
        draft.referencedVariables = source.referencedVariables;
        draft.linkedTemplateId = source.linkedTemplateId;
        // 默认标记不传递 —— 多份"分类默认"会冲突，保持 false 由用户显式重设
        draft.isDefault = false;
        draft.persist();

        LOG.infof("Created new draft id=%s from source=%s", draft.id, sourceId);
        return CostingTemplateDTO.from(draft);
    }

    @Transactional
    public void delete(UUID id) {
        CostingTemplate t = CostingTemplate.findById(id);
        if (t == null) throw new BusinessException(404, "CostingTemplate not found: " + id);
        if (!"DRAFT".equals(t.status))
            throw new BusinessException(400, "Only DRAFT templates can be deleted");
        t.delete();
    }

    /** V74 起：默认 Excel 模板的唯一性维度从 categoryId 改为 linkedTemplateId */
    private void clearOtherDefaults(UUID linkedTemplateId, UUID excludeId) {
        CostingTemplate.update(
                "isDefault = false WHERE linkedTemplateId = ?1 AND id != ?2",
                linkedTemplateId, excludeId);
    }

    private String nextVersion(UUID seriesId, String currentVersion) {
        // 找到该系列最新版本号，递增小版本
        long count = CostingTemplate.count("seriesId = ?1 AND status = 'PUBLISHED'", seriesId);
        long major = 1;
        long minor = (long) count;  // 已发布数量即下一个 minor
        if (currentVersion != null && currentVersion.startsWith("v")) {
            String[] parts = currentVersion.substring(1).split("\\.");
            try {
                if (parts.length >= 1) major = Long.parseLong(parts[0]);
                if (parts.length >= 2) minor = Math.max(minor, Long.parseLong(parts[1]) + 1);
            } catch (Exception ignore) {}
        }
        return "v" + major + "." + minor;
    }

    /**
     * CTPL-COLUMN-FORMULA-06: validate that all column keys referenced in formula
     * expressions (e.g. [C]*[D]+[A]) exist in the columns list.
     *
     * @param columnsJson JSON array of column objects with "col_key" and optional "formula" fields
     * @throws BusinessException 400 if a formula references an undeclared column key
     */
    private static final Pattern FORMULA_REF_PATTERN = Pattern.compile("\\[([A-Za-z][A-Za-z0-9_]*)\\]");

    private void validateFormulaReferences(String columnsJson) {
        if (columnsJson == null || columnsJson.isBlank() || "[]".equals(columnsJson.trim())) {
            return;
        }
        try {
            List<Map<String, Object>> columns = MAPPER.readValue(columnsJson,
                    new TypeReference<List<Map<String, Object>>>() {});

            // Collect all declared keys (canonical field name is col_key — see V96 migration / CostingSheetService)
            Set<String> declaredKeys = new HashSet<>();
            for (Map<String, Object> col : columns) {
                Object key = col.get("col_key");
                if (key != null && !key.toString().isBlank()) {
                    declaredKeys.add(key.toString());
                }
            }

            // Check each formula for undeclared references
            for (Map<String, Object> col : columns) {
                Object colKeyObj = col.get("col_key");
                Object formulaObj = col.get("formula");
                if (formulaObj == null) continue;
                String formula = formulaObj.toString();
                if (formula.isBlank()) continue;

                String colKey = colKeyObj != null ? colKeyObj.toString() : "unknown";
                Matcher m = FORMULA_REF_PATTERN.matcher(formula);
                while (m.find()) {
                    String ref = m.group(1);
                    if (!declaredKeys.contains(ref)) {
                        throw new BusinessException(400,
                                "公式列 " + colKey + " 引用了不存在的列：[" + ref + "]");
                    }
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            LOG.warnf("Failed to parse columns JSON for formula validation: %s", e.getMessage());
            // Non-parseable columns JSON is tolerated — schema validation covers this separately
        }
    }

    private String toJson(Object obj, String dft) {
        if (obj == null) return dft;
        if (obj instanceof String s) return s;
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return dft;
        }
    }
}
