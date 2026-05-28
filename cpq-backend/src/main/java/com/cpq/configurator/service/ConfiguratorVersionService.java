package com.cpq.configurator.service;

import com.cpq.configurator.entity.ConfiguratorOption;
import com.cpq.configurator.entity.ConfiguratorOptionValue;
import com.cpq.configurator.entity.ConfiguratorTemplate;
import com.cpq.configurator.entity.ConfiguratorTemplateVersion;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * §13 选配模板版本管理 — 创建快照 / diff 对比 / 回滚
 */
@ApplicationScoped
public class ConfiguratorVersionService {

    public List<ConfiguratorTemplateVersion> listVersions(UUID templateId) {
        return ConfiguratorTemplateVersion.find("templateId", Sort.by("version").descending(), templateId).list();
    }

    /** 创建当前模板的版本快照（用于发布前 / 回滚备份） */
    @Transactional
    public ConfiguratorTemplateVersion createSnapshot(UUID templateId, String label, String changeSummary) {
        ConfiguratorTemplate t = ConfiguratorTemplate.findById(templateId);
        if (t == null) throw new NotFoundException("Template not found: " + templateId);

        // 取最大版本号 + 1
        Integer maxV = ConfiguratorTemplateVersion.find("templateId", templateId).list().stream()
            .map(o -> ((ConfiguratorTemplateVersion) o).version)
            .max(Integer::compareTo).orElse(0);

        // 完整快照
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("code", t.code);
        snapshot.put("name", t.name);
        snapshot.put("category", t.category);
        snapshot.put("description", t.description);
        snapshot.put("showPrice", t.showPrice);
        snapshot.put("metadata", t.metadata);
        snapshot.put("baseModelId", t.baseModelId);
        snapshot.put("baseModelVersion", t.baseModelVersion);

        List<ConfiguratorOption> opts = ConfiguratorOption.list("templateId", templateId);
        List<Map<String, Object>> optSnap = new ArrayList<>();
        for (ConfiguratorOption o : opts) {
            Map<String, Object> os = new HashMap<>();
            os.put("code", o.code); os.put("label", o.label); os.put("optionType", o.optionType);
            os.put("isRequired", o.isRequired); os.put("defaultValue", o.defaultValue);
            os.put("sortOrder", o.sortOrder);
            os.put("partnoPrefix", o.partnoPrefix); os.put("partnoSuffix", o.partnoSuffix);
            // 取值
            List<ConfiguratorOptionValue> vals = ConfiguratorOptionValue.list("optionId", o.id);
            List<Map<String, Object>> vSnap = new ArrayList<>();
            for (ConfiguratorOptionValue v : vals) {
                Map<String, Object> vs = new HashMap<>();
                vs.put("code", v.code); vs.put("label", v.label); vs.put("priceDelta", v.priceDelta);
                vs.put("sortOrder", v.sortOrder); vs.put("partnoInclude", v.partnoInclude);
                vs.put("isActive", v.isActive); vs.put("featureType", v.featureType);
                vs.put("subModelPartNo", v.subModelPartNo); vs.put("attachMode", v.attachMode);
                vSnap.add(vs);
            }
            os.put("values", vSnap);
            optSnap.add(os);
        }
        snapshot.put("options", optSnap);

        ConfiguratorTemplateVersion v = new ConfiguratorTemplateVersion();
        v.templateId = templateId;
        v.version = maxV + 1;
        v.label = label != null ? label : ("v" + (maxV + 1));
        v.status = t.status;
        v.snapshot = snapshot;
        v.changeSummary = changeSummary;
        v.persist();
        return v;
    }

    /** Diff 算法：对比两个版本的字段级差异 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> diffVersions(UUID v1Id, UUID v2Id) {
        ConfiguratorTemplateVersion v1 = ConfiguratorTemplateVersion.findById(v1Id);
        ConfiguratorTemplateVersion v2 = ConfiguratorTemplateVersion.findById(v2Id);
        if (v1 == null || v2 == null) throw new NotFoundException("Version not found");

        List<Map<String, Object>> changes = new ArrayList<>();
        // 顶层字段
        for (String key : List.of("name", "category", "description", "showPrice")) {
            Object a = v1.snapshot.get(key), b = v2.snapshot.get(key);
            if (!Objects.equals(a, b)) changes.add(Map.of("level", "template", "field", key,
                "v1", a == null ? "" : a, "v2", b == null ? "" : b));
        }
        // option diff
        List<Map<String, Object>> opts1 = (List<Map<String, Object>>) v1.snapshot.get("options");
        List<Map<String, Object>> opts2 = (List<Map<String, Object>>) v2.snapshot.get("options");
        Map<String, Map<String, Object>> opts1Map = new HashMap<>();
        Map<String, Map<String, Object>> opts2Map = new HashMap<>();
        if (opts1 != null) opts1.forEach(o -> opts1Map.put((String) o.get("code"), o));
        if (opts2 != null) opts2.forEach(o -> opts2Map.put((String) o.get("code"), o));
        Set<String> allOptCodes = new HashSet<>();
        allOptCodes.addAll(opts1Map.keySet());
        allOptCodes.addAll(opts2Map.keySet());
        for (String code : allOptCodes) {
            Map<String, Object> o1 = opts1Map.get(code), o2 = opts2Map.get(code);
            if (o1 == null) changes.add(Map.of("level", "option", "type", "ADDED", "code", code, "label", o2.get("label")));
            else if (o2 == null) changes.add(Map.of("level", "option", "type", "REMOVED", "code", code, "label", o1.get("label")));
            else {
                for (String k : List.of("label", "isRequired", "defaultValue", "sortOrder")) {
                    if (!Objects.equals(o1.get(k), o2.get(k))) changes.add(Map.of("level", "option", "type", "CHANGED",
                        "code", code, "field", k, "v1", o1.get(k) == null ? "" : o1.get(k), "v2", o2.get(k) == null ? "" : o2.get(k)));
                }
            }
        }

        Map<String, Object> ret = new HashMap<>();
        ret.put("v1", Map.of("id", v1.id, "version", v1.version, "label", v1.label == null ? "" : v1.label));
        ret.put("v2", Map.of("id", v2.id, "version", v2.version, "label", v2.label == null ? "" : v2.label));
        ret.put("changes", changes);
        ret.put("changeCount", changes.size());
        return ret;
    }

    /** 回滚到指定版本（创建新版本快照 + 应用 snapshot 到模板） */
    @Transactional
    public Map<String, Object> rollback(UUID templateId, UUID versionId) {
        ConfiguratorTemplate t = ConfiguratorTemplate.findById(templateId);
        if (t == null) throw new NotFoundException("Template not found");
        ConfiguratorTemplateVersion target = ConfiguratorTemplateVersion.findById(versionId);
        if (target == null || !target.templateId.equals(templateId))
            throw new NotFoundException("Version not found for this template");
        // 1. 先备份当前
        createSnapshot(templateId, "rollback-backup-" + System.currentTimeMillis(), "回滚前自动备份");
        // 2. 应用 snapshot 到模板
        if (target.snapshot.get("name") instanceof String) t.name = (String) target.snapshot.get("name");
        if (target.snapshot.get("category") instanceof String) t.category = (String) target.snapshot.get("category");
        if (target.snapshot.get("description") instanceof String) t.description = (String) target.snapshot.get("description");
        if (target.snapshot.get("showPrice") instanceof Boolean) t.showPrice = (Boolean) target.snapshot.get("showPrice");
        t.updatedAt = OffsetDateTime.now();
        Map<String, Object> ret = new HashMap<>();
        ret.put("status", "rolled_back");
        ret.put("rolled_back_to_version", target.version);
        ret.put("note", "已恢复模板顶层字段；选项/取值/3D规则的回滚需进一步切片实现（涉及级联删除现有 child 后重建）");
        return ret;
    }
}
