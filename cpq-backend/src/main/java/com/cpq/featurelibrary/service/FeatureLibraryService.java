package com.cpq.featurelibrary.service;

import com.cpq.common.dto.PageResult;
import com.cpq.featurelibrary.entity.FeatureField;
import com.cpq.featurelibrary.entity.FeatureGroup;
import com.cpq.featurelibrary.entity.FeatureValue;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 特征库服务（方案 B 快照复制主数据）。
 *
 * <p>骨架阶段：仅 CRUD，业务逻辑（含模板复制语义、ERP 同步）后续切片实现。
 * 详见 docs/3D产品选配方案.md §18A
 */
@ApplicationScoped
public class FeatureLibraryService {

    @Inject
    EntityManager em;

    /**
     * §18A.5 计算模板与特征库当前版本的差异。
     * <p>返回 list of diffs，每项含：option_id / option_code / field_diff（label/default/min/max 等）+ value_diff
     * <p>调用方：模板编辑器「📋 重新拉取」按钮
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> computeRefreshDiff(java.util.UUID templateId) {
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
        // 取模板所有源自特征库的 option
        java.util.List<Object[]> rows = em.createNativeQuery(
            "SELECT pco.id, pco.code, pco.label, pco.default_value, pco.min_value, pco.max_value, " +
            "       pco.source_feature_field_id, pco.source_feature_snapshot_at " +
            "FROM product_config_option pco WHERE pco.template_id = ?1 AND pco.source_feature_field_id IS NOT NULL"
        ).setParameter(1, templateId).getResultList();

        for (Object[] r : rows) {
            java.util.UUID optionId = (java.util.UUID) r[0];
            String optCode = (String) r[1];
            String tplLabel = (String) r[2];
            String tplDefault = (String) r[3];
            String tplMin = (String) r[4];
            String tplMax = (String) r[5];
            Long fieldId = ((Number) r[6]).longValue();

            FeatureField field = FeatureField.findById(fieldId);
            if (field == null) {
                Map<String, Object> diff = new HashMap<>();
                diff.put("option_id", optionId);
                diff.put("option_code", optCode);
                diff.put("type", "SOURCE_DELETED");
                diff.put("note", "特征字段已被删除");
                result.add(diff);
                continue;
            }

            // 字段属性差异
            java.util.List<Map<String, Object>> fieldDiffs = new java.util.ArrayList<>();
            if (!java.util.Objects.equals(tplLabel, field.name)) fieldDiffs.add(Map.of("attr", "label", "tpl", tplLabel, "src", field.name));
            if (!java.util.Objects.equals(tplDefault, field.defaultValue)) fieldDiffs.add(Map.of("attr", "defaultValue", "tpl", tplDefault == null ? "" : tplDefault, "src", field.defaultValue == null ? "" : field.defaultValue));
            if (!java.util.Objects.equals(tplMin, field.minValue)) fieldDiffs.add(Map.of("attr", "minValue", "tpl", tplMin == null ? "" : tplMin, "src", field.minValue == null ? "" : field.minValue));
            if (!java.util.Objects.equals(tplMax, field.maxValue)) fieldDiffs.add(Map.of("attr", "maxValue", "tpl", tplMax == null ? "" : tplMax, "src", field.maxValue == null ? "" : field.maxValue));

            // 取值差异
            java.util.List<Object[]> tplValues = em.createNativeQuery(
                "SELECT code, label, source_feature_value_id FROM product_config_option_value WHERE option_id = ?1"
            ).setParameter(1, optionId).getResultList();
            java.util.Set<String> tplCodes = new java.util.HashSet<>();
            java.util.Map<String, String> tplLabelByCode = new java.util.HashMap<>();
            for (Object[] tv : tplValues) {
                tplCodes.add((String) tv[0]);
                tplLabelByCode.put((String) tv[0], (String) tv[1]);
            }
            java.util.List<FeatureValue> srcValues = FeatureValue.list("fieldId = ?1 and isActive = true", fieldId);
            java.util.Set<String> srcCodes = new java.util.HashSet<>();
            java.util.Map<String, String> srcLabelByCode = new java.util.HashMap<>();
            for (FeatureValue fv : srcValues) {
                srcCodes.add(fv.code);
                srcLabelByCode.put(fv.code, fv.label);
            }
            java.util.List<Map<String, Object>> valueDiffs = new java.util.ArrayList<>();
            for (String c : srcCodes) {
                if (!tplCodes.contains(c)) {
                    valueDiffs.add(Map.of("type", "NEW_IN_SOURCE", "code", c, "src_label", srcLabelByCode.get(c)));
                } else if (!java.util.Objects.equals(tplLabelByCode.get(c), srcLabelByCode.get(c))) {
                    valueDiffs.add(Map.of("type", "LABEL_CHANGED", "code", c, "tpl_label", tplLabelByCode.get(c), "src_label", srcLabelByCode.get(c)));
                }
            }
            for (String c : tplCodes) {
                if (!srcCodes.contains(c)) {
                    valueDiffs.add(Map.of("type", "DELETED_FROM_SOURCE", "code", c, "tpl_label", tplLabelByCode.get(c) == null ? "" : tplLabelByCode.get(c)));
                }
            }

            if (!fieldDiffs.isEmpty() || !valueDiffs.isEmpty()) {
                Map<String, Object> diff = new HashMap<>();
                diff.put("option_id", optionId);
                diff.put("option_code", optCode);
                diff.put("source_field_code", field.code);
                diff.put("source_field_name", field.name);
                diff.put("type", "CHANGED");
                diff.put("field_diffs", fieldDiffs);
                diff.put("value_diffs", valueDiffs);
                result.add(diff);
            }
        }
        return result;
    }

    /**
     * 引用统计：每个 group 被多少个 product_config_template 引用（通过 option.source_feature_field_id）。
     */
    @SuppressWarnings("unchecked")
    public Map<Long, Integer> countTemplateRefsByGroup() {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT cff.group_id, COUNT(DISTINCT pco.template_id) " +
            "FROM cpq_feature_field cff " +
            "JOIN product_config_option pco ON pco.source_feature_field_id = cff.id " +
            "GROUP BY cff.group_id"
        ).getResultList();
        Map<Long, Integer> ret = new HashMap<>();
        for (Object[] r : rows) {
            ret.put(((Number) r[0]).longValue(), ((Number) r[1]).intValue());
        }
        return ret;
    }

    // ===== Group CRUD =====

    public PageResult<FeatureGroup> listGroups(int page, int size, String status, String category, String keyword) {
        StringBuilder query = new StringBuilder("1=1");
        java.util.List<Object> params = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            params.add(status);
            query.append(" and status = ?").append(params.size());
        }
        if (category != null && !category.isBlank()) {
            params.add(category);
            query.append(" and category = ?").append(params.size());
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.toLowerCase() + "%";
            params.add(like);
            int p1 = params.size();
            params.add(like);
            int p2 = params.size();
            query.append(" and (lower(code) like ?").append(p1).append(" or lower(name) like ?").append(p2).append(")");
        }
        var pq = FeatureGroup.find(query.toString(), Sort.by("updatedAt").descending(), params.toArray());
        long total = pq.count();
        List<FeatureGroup> rows = pq.page(Page.of(page, size)).list();
        return new PageResult<>(rows, page, size, total);
    }

    public FeatureGroup getGroup(Long id) {
        FeatureGroup g = FeatureGroup.findById(id);
        if (g == null) throw new NotFoundException("Feature group not found: " + id);
        return g;
    }

    @Transactional
    public FeatureGroup createGroup(FeatureGroup g) {
        g.id = null;  // 让 IDENTITY 生成
        g.persist();
        return g;
    }

    @Transactional
    public FeatureGroup updateGroup(Long id, FeatureGroup patch) {
        FeatureGroup g = getGroup(id);
        if (patch.name != null) g.name = patch.name;
        if (patch.description != null) g.description = patch.description;
        if (patch.category != null) g.category = patch.category;
        if (patch.status != null) g.status = patch.status;
        return g;
    }

    @Transactional
    public void archiveGroup(Long id) {
        FeatureGroup g = getGroup(id);
        g.status = "ARCHIVED";
    }

    // ===== Field CRUD =====

    public List<FeatureField> listFields(Long groupId) {
        return FeatureField.find("groupId", Sort.by("sortOrder").ascending(), groupId).list();
    }

    @Transactional
    public FeatureField createField(Long groupId, FeatureField f) {
        f.id = null;
        f.groupId = groupId;
        f.persist();
        return f;
    }

    @Transactional
    public FeatureField updateField(Long fieldId, FeatureField patch) {
        FeatureField f = FeatureField.findById(fieldId);
        if (f == null) throw new NotFoundException("Feature field not found: " + fieldId);
        if (patch.name != null) f.name = patch.name;
        if (patch.dataType != null) f.dataType = patch.dataType;
        if (patch.assignMode != null) f.assignMode = patch.assignMode;
        if (patch.isRequired != null) f.isRequired = patch.isRequired;
        if (patch.defaultValue != null) f.defaultValue = patch.defaultValue;
        if (patch.minValue != null) f.minValue = patch.minValue;
        if (patch.maxValue != null) f.maxValue = patch.maxValue;
        if (patch.partnoPrefix != null) f.partnoPrefix = patch.partnoPrefix;
        if (patch.partnoSuffix != null) f.partnoSuffix = patch.partnoSuffix;
        if (patch.sortOrder != null) f.sortOrder = patch.sortOrder;
        return f;
    }

    @Transactional
    public void deleteField(Long fieldId) {
        FeatureField.deleteById(fieldId);
    }

    // ===== Value CRUD =====

    public List<FeatureValue> listValues(Long fieldId) {
        return FeatureValue.find("fieldId", Sort.by("sortOrder").ascending(), fieldId).list();
    }

    @Transactional
    public FeatureValue createValue(Long fieldId, FeatureValue v) {
        v.id = null;
        v.fieldId = fieldId;
        v.persist();
        return v;
    }

    @Transactional
    public FeatureValue updateValue(Long valueId, FeatureValue patch) {
        FeatureValue v = FeatureValue.findById(valueId);
        if (v == null) throw new NotFoundException("Feature value not found: " + valueId);
        if (patch.label != null) v.label = patch.label;
        if (patch.description != null) v.description = patch.description;
        if (patch.sortOrder != null) v.sortOrder = patch.sortOrder;
        if (patch.partnoInclude != null) v.partnoInclude = patch.partnoInclude;
        if (patch.isActive != null) v.isActive = patch.isActive;
        return v;
    }

    @Transactional
    public void deleteValue(Long valueId) {
        FeatureValue.deleteById(valueId);
    }
}
