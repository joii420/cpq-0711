package com.cpq.template.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.template.dto.TemplateComparisonResult;
import com.cpq.template.dto.TemplateComparisonResult.*;
import com.cpq.template.entity.Template;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.*;

@ApplicationScoped
public class TemplateComparisonService {

    private static final Logger LOG = Logger.getLogger(TemplateComparisonService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public TemplateComparisonResult compare(UUID templateAId, UUID templateBId) {
        Template templateA = Template.findById(templateAId);
        if (templateA == null) {
            throw new BusinessException(404, "Template A not found: " + templateAId);
        }
        Template templateB = Template.findById(templateBId);
        if (templateB == null) {
            throw new BusinessException(404, "Template B not found: " + templateBId);
        }

        TemplateComparisonResult result = new TemplateComparisonResult();
        result.templateAId = templateA.id;
        result.templateBId = templateB.id;
        result.templateAName = templateA.name;
        result.templateBName = templateB.name;
        result.templateAVersion = templateA.version;
        result.templateBVersion = templateB.version;

        // Metadata diff
        result.metadata = compareMetadata(templateA, templateB);

        // Product attributes diff
        result.productAttributes = compareProductAttributes(templateA.productAttributes, templateB.productAttributes);

        // Components diff
        result.components = compareComponents(templateA.componentsSnapshot, templateB.componentsSnapshot);

        // Stats
        result.stats = computeStats(result);

        LOG.infof("Compared templates A=%s B=%s totalDiffs=%d", templateAId, templateBId, result.stats.totalDiffs);
        return result;
    }

    private MetadataDiff compareMetadata(Template a, Template b) {
        MetadataDiff diff = new MetadataDiff();
        diff.name = new FieldChange(a.name, b.name);
        diff.version = new FieldChange(a.version, b.version);
        diff.category = new FieldChange(a.category, b.category);
        diff.description = new FieldChange(a.description, b.description);
        return diff;
    }

    private AttributesDiff compareProductAttributes(String jsonA, String jsonB) {
        List<Map<String, Object>> attrsA = parseJsonArray(jsonA);
        List<Map<String, Object>> attrsB = parseJsonArray(jsonB);

        AttributesDiff diff = new AttributesDiff();
        diff.added = new ArrayList<>();
        diff.removed = new ArrayList<>();
        diff.modified = new ArrayList<>();

        Map<String, Map<String, Object>> mapA = indexByName(attrsA);
        Map<String, Map<String, Object>> mapB = indexByName(attrsB);

        // Added in B
        for (Map.Entry<String, Map<String, Object>> entry : mapB.entrySet()) {
            if (!mapA.containsKey(entry.getKey())) {
                diff.added.add(entry.getValue());
            }
        }

        // Removed from A
        for (Map.Entry<String, Map<String, Object>> entry : mapA.entrySet()) {
            if (!mapB.containsKey(entry.getKey())) {
                diff.removed.add(entry.getValue());
            }
        }

        // Modified (present in both, different)
        for (Map.Entry<String, Map<String, Object>> entryA : mapA.entrySet()) {
            Map<String, Object> bVal = mapB.get(entryA.getKey());
            if (bVal != null && !entryA.getValue().equals(bVal)) {
                AttributeChange change = new AttributeChange();
                change.fieldName = entryA.getKey();
                change.valueA = entryA.getValue();
                change.valueB = bVal;
                diff.modified.add(change);
            }
        }

        return diff;
    }

    private ComponentsDiff compareComponents(String snapshotA, String snapshotB) {
        List<Map<String, Object>> compsA = parseJsonArray(snapshotA);
        List<Map<String, Object>> compsB = parseJsonArray(snapshotB);

        ComponentsDiff diff = new ComponentsDiff();
        diff.addedTabs = new ArrayList<>();
        diff.removedTabs = new ArrayList<>();
        diff.modifiedTabs = new ArrayList<>();

        Map<String, Map<String, Object>> mapA = indexByTabName(compsA);
        Map<String, Map<String, Object>> mapB = indexByTabName(compsB);

        // Added tabs
        for (Map.Entry<String, Map<String, Object>> entry : mapB.entrySet()) {
            if (!mapA.containsKey(entry.getKey())) {
                diff.addedTabs.add(entry.getValue());
            }
        }

        // Removed tabs
        for (Map.Entry<String, Map<String, Object>> entry : mapA.entrySet()) {
            if (!mapB.containsKey(entry.getKey())) {
                diff.removedTabs.add(entry.getValue());
            }
        }

        // Modified tabs
        for (Map.Entry<String, Map<String, Object>> entryA : mapA.entrySet()) {
            Map<String, Object> compB = mapB.get(entryA.getKey());
            if (compB != null) {
                TabChange tabChange = compareTab(entryA.getKey(), entryA.getValue(), compB);
                if (!tabChange.fieldChanges.isEmpty() || !tabChange.addedFields.isEmpty() || !tabChange.removedFields.isEmpty()) {
                    diff.modifiedTabs.add(tabChange);
                }
            }
        }

        return diff;
    }

    private TabChange compareTab(String tabName, Map<String, Object> compA, Map<String, Object> compB) {
        TabChange change = new TabChange();
        change.tabName = tabName;
        change.componentId = String.valueOf(compA.getOrDefault("componentId", ""));
        change.fieldChanges = new ArrayList<>();
        change.addedFields = new ArrayList<>();
        change.removedFields = new ArrayList<>();

        List<Map<String, Object>> fieldsA = getFields(compA);
        List<Map<String, Object>> fieldsB = getFields(compB);

        Map<String, Map<String, Object>> fieldMapA = indexByName(fieldsA);
        Map<String, Map<String, Object>> fieldMapB = indexByName(fieldsB);

        // Added fields
        for (String name : fieldMapB.keySet()) {
            if (!fieldMapA.containsKey(name)) {
                change.addedFields.add(name);
            }
        }

        // Removed fields
        for (String name : fieldMapA.keySet()) {
            if (!fieldMapB.containsKey(name)) {
                change.removedFields.add(name);
            }
        }

        // Modified fields (non-structural: field_type, label, etc.)
        for (Map.Entry<String, Map<String, Object>> entryA : fieldMapA.entrySet()) {
            Map<String, Object> fB = fieldMapB.get(entryA.getKey());
            if (fB != null && !entryA.getValue().equals(fB)) {
                FieldChange fc = new FieldChange(entryA.getValue(), fB);
                // Label it with field name
                change.fieldChanges.add(fc);
            }
        }

        return change;
    }

    private Stats computeStats(TemplateComparisonResult result) {
        Stats stats = new Stats();

        int metadataChanges = 0;
        if (result.metadata.name.changed) metadataChanges++;
        if (result.metadata.version.changed) metadataChanges++;
        if (result.metadata.category.changed) metadataChanges++;
        if (result.metadata.description.changed) metadataChanges++;

        int attrAdded = result.productAttributes.added.size();
        int attrRemoved = result.productAttributes.removed.size();
        int attrModified = result.productAttributes.modified.size();

        int compAdded = result.components.addedTabs.size();
        int compRemoved = result.components.removedTabs.size();
        int compModified = result.components.modifiedTabs.size();

        stats.added = attrAdded + compAdded;
        stats.removed = attrRemoved + compRemoved;
        stats.modified = metadataChanges + attrModified + compModified;
        stats.totalDiffs = stats.added + stats.removed + stats.modified;

        // Similarity: ratio of unchanged elements
        int totalElements = stats.totalDiffs
            + result.productAttributes.added.size() + result.productAttributes.removed.size()
            + result.components.addedTabs.size() + result.components.removedTabs.size();

        if (stats.totalDiffs == 0) {
            stats.similarityPercent = 100.0;
        } else {
            // Simple: (total - diffs) / total * 100
            int denominator = stats.totalDiffs + Math.max(
                result.productAttributes.added.size() + result.components.addedTabs.size(),
                result.productAttributes.removed.size() + result.components.removedTabs.size()
            );
            if (denominator > 0) {
                stats.similarityPercent = Math.max(0, (1.0 - (double) stats.totalDiffs / (denominator + stats.totalDiffs)) * 100.0);
            } else {
                stats.similarityPercent = 0.0;
            }
        }
        stats.similarityPercent = Math.round(stats.similarityPercent * 10.0) / 10.0;

        return stats;
    }

    // ---- Helpers ----

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Map<String, Object>> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, Map<String, Object>> indexByName(List<Map<String, Object>> items) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String name = String.valueOf(item.getOrDefault("name", ""));
            if (!name.isEmpty()) {
                map.put(name, item);
            }
        }
        return map;
    }

    private Map<String, Map<String, Object>> indexByTabName(List<Map<String, Object>> items) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String tabName = String.valueOf(item.getOrDefault("tabName", ""));
            if (!tabName.isEmpty()) {
                map.put(tabName, item);
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getFields(Map<String, Object> comp) {
        Object fields = comp.get("fields");
        if (fields instanceof List) {
            return (List<Map<String, Object>>) fields;
        }
        return new ArrayList<>();
    }
}
