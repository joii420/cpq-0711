package com.cpq.importexcel.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.importexcel.dto.CreateImportMappingTemplateRequest;
import com.cpq.importexcel.dto.ImportMappingTemplateDTO;
import com.cpq.importexcel.entity.ImportMappingTemplate;
import com.cpq.importexcel.entity.ImportRecord;
import com.cpq.template.entity.Template;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ImportMappingTemplateService {

    private static final Logger LOG = Logger.getLogger(ImportMappingTemplateService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<ImportMappingTemplateDTO> listByExcelTemplate(UUID excelTemplateId) {
        return ImportMappingTemplate.find("excelTemplateId = ?1 ORDER BY createdAt DESC", excelTemplateId)
                .<ImportMappingTemplate>list()
                .stream()
                .map(m -> {
                    ImportMappingTemplateDTO dto = ImportMappingTemplateDTO.from(m);
                    Template tmpl = Template.findById(m.templateId);
                    if (tmpl != null) dto.templateName = tmpl.name;
                    return dto;
                })
                .collect(Collectors.toList());
    }

    public ImportMappingTemplateDTO getById(UUID id) {
        ImportMappingTemplate m = ImportMappingTemplate.findById(id);
        if (m == null) throw new BusinessException(404, "ImportMappingTemplate not found: " + id);
        ImportMappingTemplateDTO dto = ImportMappingTemplateDTO.from(m);
        Template tmpl = Template.findById(m.templateId);
        if (tmpl != null) dto.templateName = tmpl.name;
        return dto;
    }

    @Transactional
    public ImportMappingTemplateDTO create(CreateImportMappingTemplateRequest request, UUID createdBy) {
        long existing = ImportMappingTemplate.count(
                "excelTemplateId = ?1 AND templateId = ?2", request.excelTemplateId, request.templateId);
        if (existing > 0) throw new BusinessException(400, "Mapping template already exists for this excel template + template combination");

        String columnMappings = request.columnMappings != null ? request.columnMappings : "[]";
        validateV2Mappings(columnMappings, request.templateId);

        ImportMappingTemplate m = new ImportMappingTemplate();
        m.name = request.name;
        m.excelTemplateId = request.excelTemplateId;
        m.templateId = request.templateId;
        m.columnMappings = columnMappings;
        m.createdBy = createdBy;
        m.persist();

        LOG.infof("Created ImportMappingTemplate id=%s name=%s", m.id, m.name);
        return getById(m.id);
    }

    @Transactional
    public ImportMappingTemplateDTO update(UUID id, CreateImportMappingTemplateRequest request) {
        ImportMappingTemplate m = ImportMappingTemplate.findById(id);
        if (m == null) throw new BusinessException(404, "ImportMappingTemplate not found: " + id);

        if (request.name != null) m.name = request.name;
        if (request.columnMappings != null) {
            validateV2Mappings(request.columnMappings, m.templateId);
            m.columnMappings = request.columnMappings;
        }

        return getById(id);
    }

    /**
     * Validates v2 column_mappings format:
     * [{ "excel_column": "...", "target_view_column": "A" }, ...]
     * Rules:
     * - target_view_column must exist in the template's excel_view_config
     * - Only PRODUCT_ATTRIBUTE and COMPONENT_FIELD columns can be mapping targets
     */
    private void validateV2Mappings(String columnMappingsJson, UUID templateId) {
        if (columnMappingsJson == null || columnMappingsJson.isBlank() || "[]".equals(columnMappingsJson.trim())) {
            return; // empty is valid
        }

        List<Map<String, Object>> mappings;
        try {
            mappings = MAPPER.readValue(columnMappingsJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new BusinessException(400, "Invalid column_mappings JSON: " + e.getMessage());
        }

        // If no excel_view_config present on template, skip validation
        Template template = Template.findById(templateId);
        if (template == null || template.excelViewConfig == null || template.excelViewConfig.isBlank()) {
            return;
        }

        List<Map<String, Object>> viewColumns;
        try {
            viewColumns = MAPPER.readValue(template.excelViewConfig, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return; // invalid config, skip
        }

        // Build map: col_key -> source_type
        Map<String, String> colKeyToSourceType = new java.util.HashMap<>();
        for (Map<String, Object> col : viewColumns) {
            Object ck = col.get("col_key");
            Object st = col.get("source_type");
            if (ck != null && st != null) {
                colKeyToSourceType.put(ck.toString(), st.toString());
            }
        }

        Set<String> writableTypes = Set.of("PRODUCT_ATTRIBUTE", "COMPONENT_FIELD");
        for (Map<String, Object> mapping : mappings) {
            Object targetCol = mapping.get("target_view_column");
            if (targetCol == null) {
                throw new BusinessException(400, "Each mapping entry must have a target_view_column");
            }
            String target = targetCol.toString();
            if (!colKeyToSourceType.containsKey(target)) {
                throw new BusinessException(400, "target_view_column '" + target + "' not found in template's excel_view_config");
            }
            String sourceType = colKeyToSourceType.get(target);
            if (!writableTypes.contains(sourceType)) {
                throw new BusinessException(400, "Column '" + target + "' has source_type '" + sourceType + "' which cannot be a mapping target. Only PRODUCT_ATTRIBUTE and COMPONENT_FIELD are allowed.");
            }
        }
    }

    @Transactional
    public void delete(UUID id) {
        ImportMappingTemplate m = ImportMappingTemplate.findById(id);
        if (m == null) throw new BusinessException(404, "ImportMappingTemplate not found: " + id);

        long refs = ImportRecord.count("mappingTemplateId = ?1", id);
        if (refs > 0) throw new BusinessException(400, "Cannot delete: mapping template has import records");

        m.delete();
        LOG.infof("Deleted ImportMappingTemplate id=%s", id);
    }
}
