package com.cpq.configurator.service;

import com.cpq.common.dto.PageResult;
import com.cpq.configurator.entity.Configurator3DRule;
import com.cpq.configurator.entity.ConfiguratorOption;
import com.cpq.configurator.entity.ConfiguratorOptionValue;
import com.cpq.configurator.entity.ConfiguratorTemplate;
import com.cpq.configurator.entity.ConfiguratorValueReference;
import com.cpq.featurelibrary.entity.FeatureField;
import com.cpq.featurelibrary.entity.FeatureValue;
import com.cpq.partmodel.entity.PartModel;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 3D 选配模板服务。骨架阶段：CRUD + 选项管理；
 * 复杂业务（导入特征库 / 重新拉取差异 / 版本管理 / 约束求值）后续切片实现。
 */
@ApplicationScoped
public class ConfiguratorTemplateService {

    public PageResult<ConfiguratorTemplate> list(int page, int size, String status, String category, String keyword) {
        StringBuilder query = new StringBuilder("1=1");
        if (status != null && !status.isBlank()) query.append(" and status = ?1");
        var pq = ConfiguratorTemplate.find(query.toString(), Sort.by("updatedAt").descending(),
                status != null && !status.isBlank() ? new Object[]{status} : new Object[0]);
        long total = pq.count();
        List<ConfiguratorTemplate> rows = pq.page(Page.of(page, size)).list();
        return new PageResult<>(rows, page, size, total);
    }

    public ConfiguratorTemplate getById(UUID id) {
        ConfiguratorTemplate t = ConfiguratorTemplate.findById(id);
        if (t == null) throw new NotFoundException("Config template not found: " + id);
        return t;
    }

    @Transactional
    public ConfiguratorTemplate create(ConfiguratorTemplate t) {
        t.id = null;
        t.persist();
        return t;
    }

    /**
     * v0.4 修：用 Map 接收 patch，避免 Jackson 把缺失字段默认值（如 status="DRAFT", showPrice=true, version=1）当真值写回。
     * 字段缺失 = 不改；字段为 null = 显式置空（业务上罕用，本方法不支持 null 置空）。
     */
    @Transactional
    public ConfiguratorTemplate update(UUID id, java.util.Map<String, Object> patch) {
        ConfiguratorTemplate t = getById(id);
        if (patch.containsKey("name") && patch.get("name") != null) t.name = patch.get("name").toString();
        if (patch.containsKey("category") && patch.get("category") != null) t.category = patch.get("category").toString();
        if (patch.containsKey("basePartNo") && patch.get("basePartNo") != null) t.basePartNo = patch.get("basePartNo").toString();
        if (patch.containsKey("baseModelId") && patch.get("baseModelId") != null) t.baseModelId = UUID.fromString(patch.get("baseModelId").toString());
        if (patch.containsKey("baseModelVersion") && patch.get("baseModelVersion") != null) t.baseModelVersion = ((Number) patch.get("baseModelVersion")).intValue();
        if (patch.containsKey("description") && patch.get("description") != null) t.description = patch.get("description").toString();
        if (patch.containsKey("showPrice") && patch.get("showPrice") != null) t.showPrice = (Boolean) patch.get("showPrice");
        if (patch.containsKey("metadata") && patch.get("metadata") instanceof java.util.Map) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) patch.get("metadata");
            t.metadata = m;
        }
        if (patch.containsKey("status") && patch.get("status") != null) t.status = patch.get("status").toString();
        return t;
    }

    @Transactional
    public void archive(UUID id) {
        ConfiguratorTemplate t = getById(id);
        t.status = "ARCHIVED";
    }

    @Transactional
    public ConfiguratorTemplate publish(UUID id) {
        ConfiguratorTemplate t = getById(id);
        if (!"DRAFT".equals(t.status)) {
            throw new IllegalStateException("Only DRAFT template can be published, current: " + t.status);
        }
        t.status = "PUBLISHED";
        return t;
    }

    // 选项 + 值
    public List<ConfiguratorOption> listOptions(UUID templateId) {
        return ConfiguratorOption.find("templateId", Sort.by("sortOrder").ascending(), templateId).list();
    }

    public List<ConfiguratorOptionValue> listValues(UUID optionId) {
        return ConfiguratorOptionValue.find("optionId", Sort.by("sortOrder").ascending(), optionId).list();
    }

    @Transactional
    public ConfiguratorOption addOption(UUID templateId, ConfiguratorOption opt) {
        opt.id = null;
        opt.templateId = templateId;
        opt.persist();
        return opt;
    }

    @Transactional
    public ConfiguratorOptionValue addValue(UUID optionId, ConfiguratorOptionValue v) {
        v.id = null;
        v.optionId = optionId;
        v.persist();
        return v;
    }

    /**
     * §18A.4: 从特征库导入字段（快照复制写入 option / option_value）。
     *
     * <p>关键不变量：
     * - PUBLISHED 模板不允许导入（必须先 fork 草稿版本）
     * - 重复 code 跳过（保留模板已定制的内容）
     * - 写入 source_feature_field_id / source_feature_value_id 追溯外键
     */
    @Transactional
    public Map<String, Object> importFeatures(UUID templateId, List<Long> fieldIds) {
        ConfiguratorTemplate t = getById(templateId);
        if ("PUBLISHED".equals(t.status)) {
            throw new IllegalStateException("PUBLISHED template cannot be modified directly. Create draft version first (§13).");
        }

        OffsetDateTime snapshotTs = OffsetDateTime.now();
        int imported = 0, skipped = 0;
        int totalValues = 0;

        // 现有 option codes（去重）
        List<ConfiguratorOption> existingOpts = ConfiguratorOption.list("templateId", templateId);
        java.util.Set<String> existingCodes = new java.util.HashSet<>();
        for (ConfiguratorOption o : existingOpts) existingCodes.add(o.code);

        int sortStart = existingOpts.size();
        int idx = 0;

        for (Long fieldId : fieldIds) {
            FeatureField field = FeatureField.findById(fieldId);
            if (field == null) { skipped++; continue; }
            if (existingCodes.contains(field.code)) { skipped++; continue; }

            // 创建 option（快照复制）
            ConfiguratorOption opt = new ConfiguratorOption();
            opt.templateId = templateId;
            opt.code = field.code;
            opt.label = field.name;
            opt.optionType = "SELECT".equals(field.assignMode) ? "EXCLUSIVE" :
                             "MANUAL".equals(field.assignMode) ? "TEXT" : "NUMERIC";
            opt.dataType = field.dataType;
            opt.assignMode = field.assignMode;
            opt.isRequired = field.isRequired;
            opt.defaultValue = field.defaultValue;
            opt.minValue = field.minValue;
            opt.maxValue = field.maxValue;
            opt.partnoPrefix = field.partnoPrefix;
            opt.partnoSuffix = field.partnoSuffix;
            opt.sortOrder = sortStart + (++idx);
            opt.sourceFeatureFieldId = fieldId;
            opt.sourceFeatureSnapshotAt = snapshotTs;
            opt.persist();
            imported++;

            // 复制 active values
            List<FeatureValue> srcValues = FeatureValue.list("fieldId = ?1 and isActive = true", fieldId);
            int vIdx = 0;
            for (FeatureValue srcVal : srcValues) {
                ConfiguratorOptionValue val = new ConfiguratorOptionValue();
                val.optionId = opt.id;
                val.code = srcVal.code;
                val.label = srcVal.label;
                val.description = srcVal.description;
                val.sortOrder = ++vIdx;
                val.priceDelta = BigDecimal.ZERO;
                val.partnoInclude = srcVal.partnoInclude;
                val.isActive = srcVal.isActive;
                val.sourceFeatureValueId = srcVal.id;
                val.sourceFeatureSnapshotAt = snapshotTs;
                val.localOnly = false;
                val.persist();
                totalValues++;
            }
        }

        Map<String, Object> ret = new HashMap<>();
        ret.put("imported_options", imported);
        ret.put("skipped", skipped);
        ret.put("imported_values", totalValues);
        ret.put("snapshot_at", snapshotTs.toString());
        return ret;
    }

    // ===== Option update / delete =====
    @Transactional
    public ConfiguratorOption updateOption(UUID optionId, Map<String, Object> patch) {
        ConfiguratorOption o = ConfiguratorOption.findById(optionId);
        if (o == null) throw new NotFoundException("Option not found: " + optionId);
        if (patch.containsKey("label") && patch.get("label") != null) o.label = patch.get("label").toString();
        if (patch.containsKey("isRequired") && patch.get("isRequired") != null) o.isRequired = (Boolean) patch.get("isRequired");
        if (patch.containsKey("defaultValue")) o.defaultValue = patch.get("defaultValue") == null ? null : patch.get("defaultValue").toString();
        if (patch.containsKey("minValue")) o.minValue = patch.get("minValue") == null ? null : patch.get("minValue").toString();
        if (patch.containsKey("maxValue")) o.maxValue = patch.get("maxValue") == null ? null : patch.get("maxValue").toString();
        if (patch.containsKey("partnoPrefix")) o.partnoPrefix = patch.get("partnoPrefix") == null ? null : patch.get("partnoPrefix").toString();
        if (patch.containsKey("partnoSuffix")) o.partnoSuffix = patch.get("partnoSuffix") == null ? null : patch.get("partnoSuffix").toString();
        if (patch.containsKey("sortOrder") && patch.get("sortOrder") != null) o.sortOrder = ((Number) patch.get("sortOrder")).intValue();
        return o;
    }

    @Transactional
    public void deleteOption(UUID optionId) {
        ConfiguratorOption.deleteById(optionId);
    }

    // ===== OptionValue update / delete =====
    @Transactional
    public ConfiguratorOptionValue updateOptionValue(UUID valueId, Map<String, Object> patch) {
        ConfiguratorOptionValue v = ConfiguratorOptionValue.findById(valueId);
        if (v == null) throw new NotFoundException("OptionValue not found: " + valueId);
        if (patch.containsKey("label") && patch.get("label") != null) v.label = patch.get("label").toString();
        if (patch.containsKey("description")) v.description = patch.get("description") == null ? null : patch.get("description").toString();
        if (patch.containsKey("priceDelta") && patch.get("priceDelta") != null) {
            v.priceDelta = new java.math.BigDecimal(patch.get("priceDelta").toString());
        }
        if (patch.containsKey("sortOrder") && patch.get("sortOrder") != null) v.sortOrder = ((Number) patch.get("sortOrder")).intValue();
        if (patch.containsKey("partnoInclude") && patch.get("partnoInclude") != null) v.partnoInclude = (Boolean) patch.get("partnoInclude");
        if (patch.containsKey("isActive") && patch.get("isActive") != null) v.isActive = (Boolean) patch.get("isActive");
        return v;
    }

    @Transactional
    public void deleteOptionValue(UUID valueId) {
        ConfiguratorOptionValue.deleteById(valueId);
    }

    // ===== 3D 规则 CRUD =====
    public List<Configurator3DRule> list3DRules(UUID optionValueId) {
        return Configurator3DRule.find("optionValueId", Sort.by("sortOrder").ascending(), optionValueId).list();
    }

    @Transactional
    public Configurator3DRule add3DRule(UUID optionValueId, Configurator3DRule rule) {
        rule.id = null;
        rule.optionValueId = optionValueId;
        rule.persist();
        return rule;
    }

    @Transactional
    public Configurator3DRule update3DRule(UUID ruleId, Map<String, Object> patch) {
        Configurator3DRule r = Configurator3DRule.findById(ruleId);
        if (r == null) throw new NotFoundException("3D rule not found: " + ruleId);
        if (patch.containsKey("action") && patch.get("action") != null) r.action = patch.get("action").toString();
        if (patch.containsKey("targetMesh")) r.targetMesh = patch.get("targetMesh") == null ? null : patch.get("targetMesh").toString();
        if (patch.containsKey("params") && patch.get("params") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) patch.get("params");
            r.params = m;
        }
        if (patch.containsKey("sortOrder") && patch.get("sortOrder") != null) r.sortOrder = ((Number) patch.get("sortOrder")).intValue();
        return r;
    }

    @Transactional
    public void delete3DRule(UUID ruleId) {
        Configurator3DRule.deleteById(ruleId);
    }

    // ===== 业务实体引用 CRUD =====
    public List<ConfiguratorValueReference> listRefs(UUID optionValueId) {
        return ConfiguratorValueReference.find("optionValueId", Sort.by("sortOrder").ascending(), optionValueId).list();
    }

    @Transactional
    public ConfiguratorValueReference addRef(UUID optionValueId, ConfiguratorValueReference r) {
        r.id = null;
        r.optionValueId = optionValueId;
        r.persist();
        return r;
    }

    @Transactional
    public ConfiguratorValueReference updateRef(UUID refId, Map<String, Object> patch) {
        ConfiguratorValueReference r = ConfiguratorValueReference.findById(refId);
        if (r == null) throw new NotFoundException("Reference not found: " + refId);
        if (patch.containsKey("refType") && patch.get("refType") != null) r.refType = patch.get("refType").toString();
        if (patch.containsKey("refCode") && patch.get("refCode") != null) r.refCode = patch.get("refCode").toString();
        if (patch.containsKey("qty")) r.qty = patch.get("qty") == null ? null : patch.get("qty").toString();
        if (patch.containsKey("unit")) r.unit = patch.get("unit") == null ? null : patch.get("unit").toString();
        if (patch.containsKey("note")) r.note = patch.get("note") == null ? null : patch.get("note").toString();
        if (patch.containsKey("sortOrder") && patch.get("sortOrder") != null) r.sortOrder = ((Number) patch.get("sortOrder")).intValue();
        if (patch.containsKey("isActive") && patch.get("isActive") != null) r.isActive = (Boolean) patch.get("isActive");
        return r;
    }

    @Transactional
    public void deleteRef(UUID refId) {
        ConfiguratorValueReference.deleteById(refId);
    }

    /**
     * §7.2: 设置模板的 base 模型（带版本 snapshot）。
     */
    @Transactional
    public ConfiguratorTemplate setBaseModel(UUID templateId, UUID modelId) {
        ConfiguratorTemplate t = getById(templateId);
        PartModel m = PartModel.findById(modelId);
        if (m == null) throw new NotFoundException("Part model not found: " + modelId);
        t.baseModelId = m.id;
        t.baseModelVersion = m.version;
        t.baseModelSnapshotAt = OffsetDateTime.now();
        return t;
    }
}
