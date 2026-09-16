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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

        // ── task-260915 B-10（AC-20）：组件 SQL 视图**一次批量取回**，循环里只做内存分发 ──────
        // 改动前是 `ComponentSqlView.list("componentId", c.id)` 写在下面的组件循环里 ——
        // 导出 86 个组件 = 86 次查库（既有缺陷，非本次引入，用户裁决本期一并修掉）。
        //
        // 🔑 **排序口径**：显式 `order by sqlViewName`。理由 ——
        //   改动前的 `list("componentId", id)` 没有 ORDER BY，PG 返回序未定义（实际是堆序），
        //   即「改动前根本没有可依赖的顺序」；批量化后必须自己定一个**确定性**的序，否则
        //   导出 JSON 里 sqlViews 数组的顺序会随执行计划漂移，AC-14 的二次往返逐字段比较会随机红。
        //   选 sqlViewName 而不是 id：id 是随机 UUID，跨库导出同一份配置会得到不同顺序；
        //   sqlViewName 在 (component_id, sql_view_name) 上有唯一约束 ⇒ 组件内唯一且稳定。
        //   ⚠️ 实查 2026-09-15（cpq_db_0724 与 cpq_db_test 两库）：每个组件的视图数 max = 1，
        //   多视图组件 0 个 ⇒ 本次换序对现存数据**不产生任何可观测差异**（AC-20 的不回归断言）。
        Map<UUID, List<ComponentSqlView>> viewsByComponentId = new HashMap<>();
        if (!components.isEmpty()) {
            List<UUID> componentIds = new ArrayList<>(components.size());
            for (Component c : components) componentIds.add(c.id);   // 纯内存
            // 🚫 componentIds 为空时不发这条查询：`in ()` 在 PG 上是语法错误。
            List<ComponentSqlView> allViews = ComponentSqlView.list(
                    "componentId in ?1 order by sqlViewName", componentIds);
            for (ComponentSqlView v : allViews) {                    // 纯内存分组，无查库
                viewsByComponentId.computeIfAbsent(v.componentId, k -> new ArrayList<>()).add(v);
            }
        }

        ComponentExportBundle bundle = new ComponentExportBundle();
        bundle.exportedAt = OffsetDateTime.now().toString();
        bundle.source = new ComponentExportBundle.Source();
        bundle.source.directoryId = directoryId.toString();
        bundle.source.directoryName = dir.name;

        Set<String> gvars = new LinkedHashSet<>();
        Set<String> datasources = new LinkedHashSet<>();
        // task-0805 R1：逐组件跑只读公式绑定扫描，最后汇总进 bundle.bindingReport。
        List<FormulaBindingInspector.Report> bindingReports = new ArrayList<>(components.size());

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
            // task-0722 行排序列
            item.sortField = c.sortField;
            // 行键(多行可编辑组件的行唯一键)：源为空则保持 null，不落空数组
            item.rowKeyFields = (c.rowKeyFields == null || c.rowKeyFields.isBlank())
                    ? null : readJson(c.rowKeyFields);
            // task-260915 B-2：此前丢失的 5 个组件级字段。
            // treeConfig 与 rowKeyFields 同样处理空值：源为空/空串 → 保持 null，不落成空对象/空数组
            // （readJson 对 null/blank 会返回空 ArrayNode，直接用会把「源是 NULL」写成「源是 []」）。
            item.treeConfig = (c.treeConfig == null || c.treeConfig.isBlank())
                    ? null : readJson(c.treeConfig);
            item.bomRecursiveExpand = c.bomRecursiveExpand;
            item.elementCodeField = c.elementCodeField;
            item.elementPriceField = c.elementPriceField;
            item.elementCurrencyField = c.elementCurrencyField;
            item.fields = readJson(c.fields);
            item.formulas = readJson(c.formulas);
            item.excelColumns = readJson(c.excelColumns);

            // 依赖扫描(只读): 从字段 JSON 收集全局变量 / 数据源引用
            scanDependencies(item.fields, gvars, datasources);

            // task-0805 R1：只读公式绑定扫描(在 item.fields/item.formulas 的深拷贝上跑,
            // 不改这两个 JsonNode 本身——它们随后原样进入 bundle.components)。
            bindingReports.add(FormulaBindingInspector.inspect(item.code, item.name, item.fields, item.formulas));

            // 该组件的 SQL 视图(组件内唯一,随组件走)。
            // 🚫 不要退回 `ComponentSqlView.list("componentId", c.id)` —— 那是 B-10 修掉的 N+1。
            List<ComponentSqlView> views = viewsByComponentId.getOrDefault(c.id, List.of());
            List<ComponentExportBundle.SqlView> sqlViews = new ArrayList<>(views.size());
            for (ComponentSqlView v : views) {
                ComponentExportBundle.SqlView sv = new ComponentExportBundle.SqlView();
                sv.sqlViewName = v.sqlViewName;
                sv.sqlTemplate = v.sqlTemplate;
                sv.declaredColumns = readJson(v.declaredColumns);
                sv.requiredVariables = v.requiredVariables == null ? List.of() : List.of(v.requiredVariables);
                sv.scope = v.scope;
                sv.description = v.description;
                // task-260915 B-2：此前丢失的 3 个视图级字段。builderConfig 同 treeConfig 的空值口径
                // （源为空 → null），且**原样透传 JSONB**，不裁剪/不规范化/不重排键序（AC-14 二次往返逐字段相等）。
                sv.builderConfig = (v.builderConfig == null || v.builderConfig.isBlank())
                        ? null : readJson(v.builderConfig);
                sv.builderVersion = v.builderVersion;
                sv.status = v.status;
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

        // task-0805 R1：汇总公式绑定报告；顶层字段，晚于 checksum 赋值以显式表明二者无关
        // （computeChecksum 的 payload 只装 source/components/dependencies，不含 bindingReport）。
        bundle.bindingReport = toBindingReport(FormulaBindingInspector.merge(bindingReports));
        return bundle;
    }

    /** FormulaBindingInspector.Report → 导出 bundle 顶层字段的 DTO 形状(§2.1 契约)。 */
    private ComponentExportBundle.BindingReport toBindingReport(FormulaBindingInspector.Report report) {
        ComponentExportBundle.BindingReport br = new ComponentExportBundle.BindingReport();
        br.unboundCount = report.unboundCount;
        br.totalFormulaRefs = report.totalFormulaRefs;
        br.items = new ArrayList<>(report.items.size());
        for (FormulaBindingInspector.Item it : report.items) {
            ComponentExportBundle.BindingReportItem out = new ComponentExportBundle.BindingReportItem();
            out.componentCode = it.componentCode;
            out.componentName = it.componentName;
            out.fieldName = it.fieldName;
            out.resolvedFormulaId = it.resolvedFormulaId;
            out.resolvedFormulaName = it.resolvedFormulaName;
            out.status = it.status;
            out.message = it.message;
            br.items.add(out);
        }
        return br;
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
