package com.cpq.component.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ComponentExportBundle;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentDirectory;
import com.cpq.component.entity.ComponentSqlView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 组件目录 **导出** 服务(P1,纯只读)。
 *
 * <p>导出某目录**直属**组件(本期不递归子目录)的完整配置 + component_sql_view + 依赖清单。
 * 全程只 SELECT,对任何业务数据零副作用。设计见 docs/PRD-v3.md §5.4.6。
 */
@ApplicationScoped
public class ComponentExportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 导出指定目录直属的全部组件为 bundle。
     *
     * @param directoryId 目录 id
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public ComponentExportBundle exportDirectory(UUID directoryId) {
        ComponentDirectory dir = ComponentDirectory.findById(directoryId);
        if (dir == null) {
            throw new BusinessException(404, "组件目录不存在: " + directoryId);
        }

        List<Component> components = Component.list("directoryId", directoryId);

        ComponentExportBundle bundle = new ComponentExportBundle();
        bundle.exportedAt = OffsetDateTime.now().toString();
        bundle.source = new ComponentExportBundle.Source();
        bundle.source.directoryId = directoryId.toString();
        bundle.source.directoryName = dir.name;

        Set<String> gvars = new LinkedHashSet<>();
        Set<String> datasources = new LinkedHashSet<>();

        List<ComponentExportBundle.Item> items = new ArrayList<>(components.size());
        for (Component c : components) {
            ComponentExportBundle.Item item = new ComponentExportBundle.Item();
            item.id = c.id.toString(); // 原组件 id，供导入端重映射跨组件引用
            item.code = c.code;
            item.name = c.name;
            item.componentType = c.componentType;
            item.columnCount = c.columnCount;
            item.status = c.status;
            item.dataDriverPath = c.dataDriverPath;
            // task-0721 页签类型属性
            item.tabType = c.tabType;
            item.partNoField = c.partNoField;
            item.partNameField = c.partNameField;
            // 行键(多行可编辑组件的行唯一键)：源为空则保持 null，不落空数组
            item.rowKeyFields = (c.rowKeyFields == null || c.rowKeyFields.isBlank())
                    ? null : readJson(c.rowKeyFields);
            item.fields = readJson(c.fields);
            item.formulas = readJson(c.formulas);
            item.excelColumns = readJson(c.excelColumns);

            // 依赖扫描(只读): 从字段 JSON 收集全局变量 / 数据源引用
            scanDependencies(item.fields, gvars, datasources);

            // 该组件的 SQL 视图(组件内唯一,随组件走)
            List<ComponentSqlView> views = ComponentSqlView.list("componentId", c.id);
            List<ComponentExportBundle.SqlView> sqlViews = new ArrayList<>(views.size());
            for (ComponentSqlView v : views) {
                ComponentExportBundle.SqlView sv = new ComponentExportBundle.SqlView();
                sv.sqlViewName = v.sqlViewName;
                sv.sqlTemplate = v.sqlTemplate;
                sv.declaredColumns = readJson(v.declaredColumns);
                sv.requiredVariables = v.requiredVariables == null ? List.of() : List.of(v.requiredVariables);
                sv.scope = v.scope;
                sv.description = v.description;
                sqlViews.add(sv);
            }
            item.sqlViews = sqlViews;
            items.add(item);
        }
        bundle.components = items;

        bundle.dependencies = new ComponentExportBundle.Dependencies();
        bundle.dependencies.globalVariables = new ArrayList<>(gvars);
        bundle.dependencies.datasources = new ArrayList<>(datasources);

        // checksum: 基于 source+components+dependencies 的规范 JSON(不含 checksum 自身)
        bundle.checksum = computeChecksum(bundle);
        return bundle;
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) return MAPPER.createArrayNode();
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            // 容错: 解析失败时退化为字符串节点(不影响导出, 导入端会再校验)
            return MAPPER.getNodeFactory().textNode(raw);
        }
    }

    /** 递归扫描字段 JSON, 收集 global_variable_code 与 GLOBAL_VARIABLE / DATABASE_QUERY / HTTP_API 绑定引用。 */
    private void scanDependencies(JsonNode node, Set<String> gvars, Set<String> datasources) {
        if (node == null) return;
        if (node.isObject()) {
            JsonNode gvc = node.get("global_variable_code");
            if (gvc != null && gvc.isTextual() && !gvc.asText().isBlank()) {
                gvars.add(gvc.asText().trim());
            }
            // datasource_binding / default_source: 带 type + code 的绑定对象
            JsonNode type = node.get("type");
            JsonNode code = node.get("code");
            if (type != null && code != null && code.isTextual() && !code.asText().isBlank()) {
                String t = type.asText();
                String codeVal = code.asText().trim();
                if ("GLOBAL_VARIABLE".equals(t)) {
                    gvars.add(codeVal);
                } else if ("DATABASE_QUERY".equals(t) || "HTTP_API".equals(t)) {
                    datasources.add(codeVal);
                }
            }
            node.fields().forEachRemaining(e -> scanDependencies(e.getValue(), gvars, datasources));
        } else if (node.isArray()) {
            node.forEach(n -> scanDependencies(n, gvars, datasources));
        }
    }

    private String computeChecksum(ComponentExportBundle bundle) {
        try {
            // 用一个临时对象只装入参与校验的部分, 避免把 checksum 自身算进去
            var payload = MAPPER.createObjectNode();
            payload.set("source", MAPPER.valueToTree(bundle.source));
            payload.set("components", MAPPER.valueToTree(bundle.components));
            payload.set("dependencies", MAPPER.valueToTree(bundle.dependencies));
            byte[] bytes = MAPPER.writeValueAsBytes(payload);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // checksum 失败不阻断导出(仅校验用途)
            return null;
        }
    }
}
