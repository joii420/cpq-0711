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
}
