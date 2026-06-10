package com.cpq.component.service;

import com.cpq.component.entity.Component;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 组件级页签定义服务（呼应 ExcelViewService.tabDefsOfTemplate 的模板级实现）。
 *
 * <p>决策 A：同目录组件即页签集。给定一个组件，可引用的页签 = 同目录下的其他组件（ACTIVE）。
 * 返回 shape 与模板级 tabDefs 一致，使 {@code TabJoinFormulaDrawer} 无需改动即可消费。
 *
 * <p>别名约定（呼应历史教训「中文标识符需 ASCII 别名」）：
 * alias = component.code（ASCII、稳定、唯一），displayLabel/componentName = component.name（中文展示名）。
 * 与模板级用 tabName 不同——组件上下文无每模板页签名，code 是天然的 ASCII 别名。
 *
 * <p>self 标记：返回同目录全部组件（含请求组件自身），请求组件那条标记 {@code self=true}，
 * 便于抽屉置灰/锁定自身（与行键锁定行为一致）。
 */
@ApplicationScoped
public class ComponentTabDefService {

    private static final Logger LOG = Logger.getLogger(ComponentTabDefService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 加载组件 → 取其 directoryId → 查同目录 ACTIVE 组件（按 code 排序保证稳定序）→ 构建 tabDefs。
     */
    public List<Map<String, Object>> tabDefsForComponent(UUID componentId) {
        if (componentId == null) return List.of();
        Component self = Component.findById(componentId);
        if (self == null || self.directoryId == null) return List.of();
        List<Component> siblings = Component.list(
            "directoryId = ?1 AND status = ?2 ORDER BY code ASC",
            self.directoryId, "ACTIVE");
        return componentsToTabDefs(siblings, componentId);
    }

    /**
     * 纯转换：组件列表 → tabDefs 列表。不依赖 DB / Panache，便于单测。
     *
     * <p>每条 tabDef 的键与模板级 {@code ExcelViewService.parseTabDefs} 一致：
     * alias / tabKey / componentId / componentName / componentType / sortOrder /
     * rowKeyFields / detailFields / subtotalCols，外加组件级特有的 {@code self} 标记。
     *
     * @param comps  同目录组件列表（已过滤/排序）
     * @param selfId 请求组件 id，匹配到的那条 tabDef 标记 self=true（可为 null）
     */
    public static List<Map<String, Object>> componentsToTabDefs(List<Component> comps, UUID selfId) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (comps == null) return result;
        int sortOrder = 0;
        for (Component c : comps) {
            if (c == null) continue;
            String componentId = c.id != null ? c.id.toString() : null;
            // alias = code（ASCII 稳定别名）；componentName = name（中文展示）
            String alias = c.code;
            // 组件上下文无模板 sortOrder，用稳定列表序；tabKey 用 componentId 保证唯一
            String tabKey = componentId != null ? componentId : ("idx:" + sortOrder);

            List<String> rowKeyFields = parseStringList(c.rowKeyFields);

            List<String> detailFields = new ArrayList<>();
            List<String> subtotalCols = new ArrayList<>();
            List<Map<String, Object>> fields = parseJsonArray(c.fields);
            for (Map<String, Object> fm : fields) {
                Object nameObj = fm.get("name");
                if (nameObj == null) continue;
                String fieldName = nameObj.toString();
                detailFields.add(fieldName);
                Object isSubtotal = fm.get("is_subtotal");
                if (Boolean.TRUE.equals(isSubtotal) || "true".equals(String.valueOf(isSubtotal))) {
                    subtotalCols.add(fieldName);
                }
            }

            Map<String, Object> def = new LinkedHashMap<>();
            def.put("alias", alias);
            def.put("tabKey", tabKey);
            def.put("componentId", componentId);
            def.put("componentName", c.name);
            def.put("componentType", c.componentType);
            def.put("sortOrder", sortOrder);
            def.put("rowKeyFields", rowKeyFields);
            def.put("detailFields", detailFields);
            def.put("subtotalCols", subtotalCols);
            if (selfId != null && c.id != null && selfId.equals(c.id)) {
                def.put("self", Boolean.TRUE);
            }
            result.add(def);
            sortOrder++;
        }
        return result;
    }

    private static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            LOG.warnf("[ComponentTabDef] failed to parse string list: %s", e.getMessage());
            return List.of();
        }
    }

    private static List<Map<String, Object>> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            LOG.warnf("[ComponentTabDef] failed to parse fields array: %s", e.getMessage());
            return List.of();
        }
    }
}
