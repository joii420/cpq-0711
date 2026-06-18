package com.cpq.component.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * 组件导入跨组件引用重映射：纯静态工具，无 CDI/注入。
 *
 * <p>formulas 是 JSON 数组，元素形如 {@code {name, expression:[token...]}}。
 * 三类跨组件引用 token 的字段含义（以代码为准）：
 * <ul>
 *   <li>{@code cross_tab_ref}：{@code source}（被引用组件 UUID）、
 *       {@code targetExpr}（数组，每个元素也可有 {@code source}）</li>
 *   <li>{@code component_subtotal}：{@code component_code}（被引用组件 code）</li>
 * </ul>
 *
 * <p>供 G3（导入提交）和 G4（存量补救）复用。
 */
public final class FormulaRefRemapper {

    private FormulaRefRemapper() {}

    private static final Logger LOG = Logger.getLogger(FormulaRefRemapper.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 重写 formulas JSON 字符串中的跨组件引用。
     *
     * @param formulasJson formulas 的 JSON 字符串（顶层数组）
     * @param idMap        旧 componentId UUID → 新 componentId UUID 的映射
     * @param codeMap      旧 component code → 新 component code 的映射
     * @return 重写后的 JSON 字符串；formulasJson 为 null/空/非数组/解析失败时返回原值
     */
    public static String remap(String formulasJson,
                                Map<String, String> idMap,
                                Map<String, String> codeMap) {
        if (formulasJson == null || formulasJson.isBlank()) {
            return formulasJson;
        }

        // null map 当空 map 处理
        Map<String, String> ids = (idMap != null) ? idMap : Map.of();
        Map<String, String> codes = (codeMap != null) ? codeMap : Map.of();

        // 两个 map 都为空时，无需做任何变换，直接返回原值
        if (ids.isEmpty() && codes.isEmpty()) {
            return formulasJson;
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(formulasJson);
        } catch (Exception e) {
            LOG.debugf("FormulaRefRemapper: 解析 formulasJson 失败，原样返回: %s", e.getMessage());
            return formulasJson;
        }

        if (!root.isArray()) {
            return formulasJson;
        }

        boolean changed = false;
        ArrayNode formulas = (ArrayNode) root;

        for (int fi = 0; fi < formulas.size(); fi++) {
            JsonNode formulaNode = formulas.get(fi);
            if (!formulaNode.isObject()) continue;

            JsonNode exprNode = formulaNode.path("expression");
            if (!exprNode.isArray()) continue;

            ArrayNode expression = (ArrayNode) exprNode;
            for (int ti = 0; ti < expression.size(); ti++) {
                JsonNode tokenNode = expression.get(ti);
                if (!tokenNode.isObject()) continue;

                String type = tokenNode.path("type").asText("");
                if ("cross_tab_ref".equals(type)) {
                    boolean tokenChanged = remapCrossTabRefToken((ObjectNode) tokenNode, ids);
                    if (tokenChanged) changed = true;
                } else if ("component_subtotal".equals(type)) {
                    boolean tokenChanged = remapComponentSubtotalToken((ObjectNode) tokenNode, codes);
                    if (tokenChanged) changed = true;
                }
            }
        }

        if (!changed) {
            return formulasJson;
        }

        try {
            return MAPPER.writeValueAsString(formulas);
        } catch (Exception e) {
            LOG.warnf("FormulaRefRemapper: 序列化重写结果失败，原样返回: %s", e.getMessage());
            return formulasJson;
        }
    }

    /**
     * 重写 cross_tab_ref token 内的 source 及 targetExpr[].source。
     *
     * @return true 表示发生了替换
     */
    private static boolean remapCrossTabRefToken(ObjectNode token, Map<String, String> idMap) {
        boolean changed = false;

        // ① 顶层 source
        JsonNode sourceNode = token.get("source");
        if (sourceNode != null && sourceNode.isTextual()) {
            String oldId = sourceNode.asText();
            String newId = idMap.get(oldId);
            if (newId != null) {
                token.put("source", newId);
                changed = true;
            }
        }

        // ② targetExpr 数组内每个元素的 source
        JsonNode targetExprNode = token.get("targetExpr");
        if (targetExprNode != null && targetExprNode.isArray()) {
            ArrayNode targetExpr = (ArrayNode) targetExprNode;
            for (int i = 0; i < targetExpr.size(); i++) {
                JsonNode elem = targetExpr.get(i);
                if (!elem.isObject()) continue;
                JsonNode elemSource = elem.get("source");
                if (elemSource != null && elemSource.isTextual()) {
                    String oldId = elemSource.asText();
                    String newId = idMap.get(oldId);
                    if (newId != null) {
                        ((ObjectNode) elem).put("source", newId);
                        changed = true;
                    }
                }
            }
        }

        return changed;
    }

    /**
     * 重写 component_subtotal token 内的 component_code。
     *
     * @return true 表示发生了替换
     */
    private static boolean remapComponentSubtotalToken(ObjectNode token, Map<String, String> codeMap) {
        JsonNode codeNode = token.get("component_code");
        if (codeNode != null && codeNode.isTextual()) {
            String oldCode = codeNode.asText();
            String newCode = codeMap.get(oldCode);
            if (newCode != null) {
                token.put("component_code", newCode);
                return true;
            }
        }
        return false;
    }
}
