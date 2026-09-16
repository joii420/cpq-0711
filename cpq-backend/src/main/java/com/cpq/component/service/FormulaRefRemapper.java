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
 * <p><b>task-260915 B-11（AC-21）</b>：跨组件引用<b>不止在 formulas 里</b> —— EXCEL 组件的
 * {@code excel_columns} 内嵌 {@code tabs[].tabKey}（指向兄弟页签组件的 id），此前从未经过重映射，
 * 导入后仍是源库的旧 id。见 {@link #remapExcelColumns}。
 *
 * <p><b>本类是导入期跨组件引用重映射的唯一实现点</b>。🚫 再发现别的列内嵌组件引用时，
 * 请在本类加一个入口，不要在别处另起一套 —— 两套实现必然漂移。
 *
 * <p><b>实查（2026-09-15，库 {@code cpq_db_0724}）已确认的覆盖面</b>：把
 * {@code fields / formulas / excel_columns / tree_config / row_key_fields /
 * builder_config / declared_columns / sql_template} 八列里所有 UUID 形状的值拿去比
 * {@code component.id} ——
 * <ul>
 *   <li>{@code excel_columns}：5 处，<b>全部命中</b> component.id ⇒ 跨组件引用（本次新增覆盖）；</li>
 *   <li>{@code formulas}：13 处 UUID，其中 3 处命中 component.id（已覆盖），
 *       其余 10 处是 {@code formulas[].id}（组件内部自引用，归 FormulaIdBinder）；</li>
 *   <li>{@code fields}：6 处 UUID，<b>0 处</b>命中 component.id —— 全是
 *       {@code fields[].formula_id}（组件内部自引用）；</li>
 *   <li>{@code tree_config / row_key_fields / builder_config / declared_columns / sql_template}：
 *       <b>0 处</b> UUID 形状的值。</li>
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
     * task-260915 B-11（AC-21）：重写 {@code excel_columns} JSON 里的<b>跨页签组件引用</b>。
     *
     * <p><b>它修的是什么</b>：EXCEL 组件的列可以引用兄弟页签（{@code source_type=TAB_JOIN_FORMULA}），
     * 被引页签记在 {@code excel_columns[].tabs[].tabKey} 里，值是<b>那个组件的 id</b>。
     * 导入建出的是全新组件、全新 id，而这一列此前原样写入 ⇒ 新组件的 {@code tabKey}
     * 仍指向<b>源目录</b>的旧组件。同库导入表现为「指回源目录」（数据看着还在，实际串了目录）；
     * <b>跨机器搬运（本任务的目标场景）则直接成为悬空引用</b>，目标库压根没有那个 id。
     *
     * <p><b>{@code tabKey} 有两种形状</b>，两种都要认（实查 2026-09-15：库里现存 5 条全是前者）：
     * <ul>
     *   <li>裸 id —— {@code ComponentTabDefService:67}（组件管理上下文）产出；</li>
     *   <li>{@code <id>:<sortOrder>} —— {@code ExcelViewService:1301}（报价/模板上下文）产出。
     *       只替换冒号<b>前</b>那段，后缀原样保留。</li>
     * </ul>
     * {@code "idx:<n>"} 这种无 id 形态命不中 idMap，天然原样保留。
     *
     * <p>🚫 <b>不误伤</b>（AC-21 断言 b）：只有 id 命中 {@code idMap}（= 被引组件也在本包里、
     * 且确实被新建了）才替换；指向<b>包外</b>的引用<b>保持原值</b> ——
     * 不清空、不乱指。包外引用本来就可能是目标库里真实存在的组件。
     *
     * @param excelColumnsJson excel_columns 的 JSON 字符串（顶层数组）
     * @param idMap            旧 componentId → 新 componentId
     * @return 重写后的 JSON；入参为 null/空/非数组/解析失败/无任何替换时返回<b>原值</b>
     */
    public static String remapExcelColumns(String excelColumnsJson, Map<String, String> idMap) {
        if (excelColumnsJson == null || excelColumnsJson.isBlank()) {
            return excelColumnsJson;
        }
        Map<String, String> ids = (idMap != null) ? idMap : Map.of();
        if (ids.isEmpty()) {
            return excelColumnsJson;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(excelColumnsJson);
        } catch (Exception e) {
            LOG.debugf("FormulaRefRemapper: 解析 excelColumnsJson 失败，原样返回: %s", e.getMessage());
            return excelColumnsJson;
        }
        if (!root.isArray()) {
            return excelColumnsJson;
        }
        if (!remapTabKeysRecursive(root, ids)) {
            return excelColumnsJson;   // 一处都没替换 → 原值原样返回（连重新序列化都不做）
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            LOG.warnf("FormulaRefRemapper: 序列化 excel_columns 重写结果失败，原样返回: %s", e.getMessage());
            return excelColumnsJson;
        }
    }

    /**
     * 在任意深度的 JSON 树里找 {@code tabKey} 字符串字段并替换。
     *
     * <p>按<b>键名</b>递归而不是写死 {@code excel_columns[].tabs[]} 这条路径：实查确认
     * {@code excel_columns} 里<b>只有 tabKey 这一个键装组件 id</b>（其余键零 UUID），
     * 所以按键名找既够精确、又不会因为将来嵌套层级变化而静默漏掉。
     *
     * @return true 表示发生了任何替换
     */
    private static boolean remapTabKeysRecursive(JsonNode node, Map<String, String> idMap) {
        boolean changed = false;
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (remapTabKeysRecursive(child, idMap)) changed = true;
            }
        } else if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            JsonNode tabKeyNode = obj.get("tabKey");
            if (tabKeyNode != null && tabKeyNode.isTextual()) {
                String remapped = remapTabKeyValue(tabKeyNode.asText(), idMap);
                if (remapped != null) {
                    obj.put("tabKey", remapped);
                    changed = true;
                }
            }
            for (JsonNode child : obj) {
                if (remapTabKeysRecursive(child, idMap)) changed = true;
            }
        }
        return changed;
    }

    /**
     * 单个 {@code tabKey} 值的替换。
     *
     * @return 替换后的新值；{@code null} 表示<b>不该改</b>（id 不在本包里 / 不是 id 形态）——
     *         调用方据此保持原值不动
     */
    private static String remapTabKeyValue(String value, Map<String, String> idMap) {
        if (value == null || value.isBlank()) return null;
        int colon = value.indexOf(':');
        String idPart = (colon >= 0) ? value.substring(0, colon) : value;
        String suffix = (colon >= 0) ? value.substring(colon) : "";   // ":sortOrder"，原样保留
        String newId = idMap.get(idPart);
        return (newId == null) ? null : newId + suffix;
    }

    /**
     * 递归重映射单个 token（任意深度）。
     *
     * <ul>
     *   <li>含 {@code source} 字段（UUID）的 token → 若命中 idMap 则替换</li>
     *   <li>{@code type=cross_tab_ref} → 额外递归进其 {@code targetExpr} 数组（每个元素可能
     *       又是 cross_tab_ref 或 field token，再次调用本方法）</li>
     *   <li>{@code type=component_subtotal} → 重映射 {@code component_code}</li>
     * </ul>
     *
     * @return true 表示发生了任何替换
     */
    private static boolean remapTokenRecursive(ObjectNode token,
                                               Map<String, String> idMap,
                                               Map<String, String> codeMap) {
        boolean changed = false;

        // ① 任意含 source 字段的 token → 替换 UUID
        JsonNode sourceNode = token.get("source");
        if (sourceNode != null && sourceNode.isTextual()) {
            String oldId = sourceNode.asText();
            String newId = idMap.get(oldId);
            if (newId != null) {
                token.put("source", newId);
                changed = true;
            }
        }

        // ② component_subtotal → 替换 component_code
        String type = token.path("type").asText("");
        if ("component_subtotal".equals(type)) {
            JsonNode codeNode = token.get("component_code");
            if (codeNode != null && codeNode.isTextual()) {
                String oldCode = codeNode.asText();
                String newCode = codeMap.get(oldCode);
                if (newCode != null) {
                    token.put("component_code", newCode);
                    changed = true;
                }
            }
        }

        // ③ cross_tab_ref → 递归处理 targetExpr 数组的每个元素（任意深度嵌套）
        if ("cross_tab_ref".equals(type)) {
            JsonNode targetExprNode = token.get("targetExpr");
            if (targetExprNode != null && targetExprNode.isArray()) {
                ArrayNode targetExpr = (ArrayNode) targetExprNode;
                for (int i = 0; i < targetExpr.size(); i++) {
                    JsonNode elem = targetExpr.get(i);
                    if (!elem.isObject()) continue;
                    if (remapTokenRecursive((ObjectNode) elem, idMap, codeMap)) {
                        changed = true;
                    }
                }
            }
        }

        return changed;
    }

    /**
     * 重写 cross_tab_ref token 内的 source 及 targetExpr（递归，任意深度）。
     *
     * @return true 表示发生了替换
     */
    private static boolean remapCrossTabRefToken(ObjectNode token, Map<String, String> idMap) {
        return remapTokenRecursive(token, idMap, Map.of());
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
