package com.cpq.template.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.template.dto.TemplateComponentDTO;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class TemplateComponentService {

    private static final Logger LOG = Logger.getLogger(TemplateComponentService.class);

    public List<TemplateComponentDTO> listByTemplate(UUID templateId) {
        return TemplateComponent.<TemplateComponent>list("templateId = ?1 ORDER BY sortOrder ASC", templateId)
            .stream()
            .map(TemplateComponentDTO::from)
            .collect(Collectors.toList());
    }

    @Transactional
    public TemplateComponentDTO addComponent(UUID templateId, UUID componentId, String tabName) {
        Template template = Template.findById(templateId);
        if (template == null) {
            throw new BusinessException(404, "Template not found: " + templateId);
        }
        if (!"DRAFT".equals(template.status)) {
            throw new BusinessException("Can only add components to DRAFT templates");
        }

        long maxOrder = TemplateComponent.count("templateId", templateId);

        TemplateComponent tc = new TemplateComponent();
        tc.templateId = templateId;
        tc.componentId = componentId;
        tc.tabName = tabName;
        tc.sortOrder = (int) maxOrder;
        tc.persist();

        LOG.infof("Added component=%s to template=%s", componentId, templateId);
        return TemplateComponentDTO.from(tc);
    }

    @Transactional
    public void removeComponent(UUID templateId, UUID tcId) {
        Template template = Template.findById(templateId);
        if (template == null) {
            throw new BusinessException(404, "Template not found: " + templateId);
        }
        if (!"DRAFT".equals(template.status)) {
            throw new BusinessException("Can only remove components from DRAFT templates");
        }

        TemplateComponent tc = TemplateComponent.findById(tcId);
        if (tc == null || !templateId.equals(tc.templateId)) {
            throw new BusinessException(404, "TemplateComponent not found: " + tcId);
        }
        tc.delete();
        LOG.infof("Removed template_component=%s from template=%s", tcId, templateId);
    }

    @Transactional
    public List<TemplateComponentDTO> reorder(UUID templateId, List<UUID> orderedIds) {
        Template template = Template.findById(templateId);
        if (template == null) {
            throw new BusinessException(404, "Template not found: " + templateId);
        }
        if (!"DRAFT".equals(template.status)) {
            throw new BusinessException("Can only reorder components in DRAFT templates");
        }

        for (int i = 0; i < orderedIds.size(); i++) {
            TemplateComponent tc = TemplateComponent.findById(orderedIds.get(i));
            if (tc != null && templateId.equals(tc.templateId)) {
                tc.sortOrder = i;
            }
        }

        return listByTemplate(templateId);
    }

    @Transactional
    public TemplateComponentDTO updatePresetRows(UUID templateId, UUID tcId, String presetRowsJson) {
        Template template = Template.findById(templateId);
        if (template == null) {
            throw new BusinessException(404, "Template not found: " + templateId);
        }
        if (!"DRAFT".equals(template.status)) {
            throw new BusinessException("Can only edit preset rows in DRAFT templates");
        }

        TemplateComponent tc = TemplateComponent.findById(tcId);
        if (tc == null || !templateId.equals(tc.templateId)) {
            throw new BusinessException(404, "TemplateComponent not found: " + tcId);
        }
        tc.presetRows = presetRowsJson;
        LOG.infof("Updated preset_rows for tc=%s in template=%s", tcId, templateId);
        return TemplateComponentDTO.from(tc);
    }

    @Transactional
    public TemplateComponentDTO updateFormulaAssignments(UUID templateId, UUID tcId, String formulaAssignmentsJson) {
        Template template = Template.findById(templateId);
        if (template == null) {
            throw new BusinessException(404, "Template not found: " + templateId);
        }
        if (!"DRAFT".equals(template.status)) {
            throw new BusinessException("Can only edit formula assignments in DRAFT templates");
        }

        TemplateComponent tc = TemplateComponent.findById(tcId);
        if (tc == null || !templateId.equals(tc.templateId)) {
            throw new BusinessException(404, "TemplateComponent not found: " + tcId);
        }
        tc.formulaAssignments = formulaAssignmentsJson;
        LOG.infof("Updated formula_assignments for tc=%s in template=%s", tcId, templateId);
        return TemplateComponentDTO.from(tc);
    }

    /**
     * V204: 更新模板组件 override 字段 (fields_override / data_driver_path_override).
     * 任一字段传 null 表示清空 (走 component 默认), 传非空 JSON / 字符串表示设置 override.
     * 仅 DRAFT 可改, PUBLISHED 拒绝.
     *
     * <p>典型用途: 同 component 在不同 Tab 字段集不同 (如 "材质" Tab 多 "子件" 列,
     * "选配-材质" 仅 5 个基本列), 或同 component 在不同模板/Tab 用不同 driver
     * (COMPOSITE 模板用 v_composite_child_materials, SIMPLE 用 mat_part_material).
     *
     * @param fieldsOverrideJson 字段定义 JSON 数组 (null = 清空)
     * @param dataDriverPathOverride driver path 字符串 (null/空 = 清空)
     */
    @Transactional
    public TemplateComponentDTO updateOverrides(
            UUID templateId, UUID tcId,
            String fieldsOverrideJson, boolean fieldsOverrideProvided,
            String dataDriverPathOverride, boolean dataDriverPathOverrideProvided) {
        Template template = Template.findById(templateId);
        if (template == null) {
            throw new BusinessException(404, "Template not found: " + templateId);
        }
        if (!"DRAFT".equals(template.status)) {
            throw new BusinessException("Can only edit overrides in DRAFT templates");
        }

        TemplateComponent tc = TemplateComponent.findById(tcId);
        if (tc == null || !templateId.equals(tc.templateId)) {
            throw new BusinessException(404, "TemplateComponent not found: " + tcId);
        }

        // 仅在请求显式带了字段时更新, 让前端可分别 patch 单字段
        if (fieldsOverrideProvided) {
            tc.fieldsOverride = (fieldsOverrideJson == null || fieldsOverrideJson.isBlank())
                    ? null : fieldsOverrideJson;
        }
        if (dataDriverPathOverrideProvided) {
            tc.dataDriverPathOverride = (dataDriverPathOverride == null || dataDriverPathOverride.isBlank())
                    ? null : dataDriverPathOverride;
        }
        LOG.infof("Updated overrides for tc=%s in template=%s (fieldsProvided=%s, driverProvided=%s)",
                tcId, templateId, fieldsOverrideProvided, dataDriverPathOverrideProvided);
        return TemplateComponentDTO.from(tc);
    }
}
