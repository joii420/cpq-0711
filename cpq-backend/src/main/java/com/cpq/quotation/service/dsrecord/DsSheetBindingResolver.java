package com.cpq.quotation.service.dsrecord;

import com.cpq.dataset.registry.DatasetRegistry;
import com.cpq.dataset.registry.QuoteRegistry;
import com.cpq.dataset.registry.SheetDef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 批量解析「页签组件 → {@code ds_quote_*} 带版本表」的绑定（task-260907 第二段 · B-2 / B-6）。
 *
 * <h3>🚫 N+1 硬指标</h3>
 * {@link #resolve} 的 SQL 条数 <b>恒为 3</b>，与组件数无关：
 * <ol>
 *   <li>{@code component} 一次 {@code IN} 查（driver path / 元素单价列 / fields）；</li>
 *   <li>{@code component_sql_view} 一次 {@code IN} 查（builder_config）；</li>
 *   <li>语义图 {@code semantic_tab_view ⋈ semantic_node} 一次全量查（QUOTE 方言只有十几行）。</li>
 * </ol>
 * ⚠️ 刻意<b>不</b>照抄 {@code QuoteBackfillCollector#resolveComponentView} ——
 * 那里在 {@code for (Component c : ...)} 循环体里逐个 {@code ComponentSqlView.find}，是 N+1
 * （该文件受 B-14 保护不能改，故此处新写一份批量实现，不是重构它）。
 *
 * <h3>🚨 不参与的组件<b>必须显式上报</b>（D-33）</h3>
 * 解析不出绑定的组件<b>不是</b>「静默跳过」—— 它们进 {@link Resolution#nonParticipating()}，
 * 由预览层原样呈给财务（{@code api.md} 的 {@code dsBackfill.nonParticipating}）。
 * <p>理由与 {@code AP-60} 判据四同型：实测现网 {@code component_sql_view}
 * <b>156 张手写（{@code builder_config IS NULL}）vs 72 张配置器生成</b>，
 * 68% 走不通语义图这条链。不告知 = 财务以为「核价通过就把这单的数据全升版了」，
 * 而实际上大半个单子根本没参与 —— 与「不写 = 删除不在 diff 模型里」是同一种静默。
 * <p>🚫 <b>刻意不为手写视图造第二条通路</b>（D-33）：S-7 全库清空后这些组件会消失，
 * 为一个即将不存在的形态造通路不划算。且 {@code QuoteBackfillColumnMapper} 那套 pgjdbc 反查
 * 内置了 {@code QuotePendingRewriter.WHITELIST_TABLES} 过滤（全是 V6 老表，零个 {@code ds_quote_*}），
 * 直接复用只会得到空映射。
 *
 * <h3>不参与的原因（{@link NonParticipating#reason()}）</h3>
 * <ul>
 *   <li>{@code data_driver_path} 为空、或不是 {@code $view} 简单形态（{@code $$code.name} 跨组件引用）；</li>
 *   <li>{@code builder_config IS NULL} —— <b>存量手写视图</b>。这类组件走不通语义图这条链，
 *       且 {@code QuoteBackfillColumnMapper} 那套 pgjdbc 反查内置了
 *       {@code QuotePendingRewriter.WHITELIST_TABLES} 过滤（全是 V6 老表，零个 {@code ds_quote_*}），
 *       直接复用只会得到空映射 ⇒ 本轮不兜底，登记在回报里；</li>
 *   <li>{@code dialect} 不是 {@code QUOTE}（核价两套的组件）；</li>
 *   <li>语义图里查不到 {@code (tabType, variantKey, QUOTE)}，或锚点 {@code physical_table}
 *       不是 {@code QuoteRegistry} 里的<b>带版本</b> sheet（免版本三表按 B-13 一律跳过）。</li>
 * </ul>
 */
@ApplicationScoped
public class DsSheetBindingResolver {

    private static final Logger LOG = Logger.getLogger(DsSheetBindingResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 不参与的原因常量（D-33 / api.md dsBackfill.nonParticipating[].reason）──
    /** 组件没配行驱动路径 —— 单行页签 / 纯输入页签，本来就不表征基础数据的行。 */
    public static final String REASON_NO_DRIVER_PATH = "NO_DRIVER_PATH";
    /** {@code $$code.name} 跨组件引用等非简单形态，本轮不处理。 */
    public static final String REASON_UNSUPPORTED_DRIVER_PATH = "UNSUPPORTED_DRIVER_PATH";
    /** 🔴 <b>最常见</b>：存量手写 SQL 视图（{@code builder_config IS NULL}），实测占 156/228。 */
    public static final String REASON_NO_BUILDER_CONFIG = "NO_BUILDER_CONFIG";
    /** {@code builder_config} JSON 解析失败。 */
    public static final String REASON_BUILDER_CONFIG_CORRUPT = "BUILDER_CONFIG_CORRUPT";
    /** 编译方言不是 {@code QUOTE}（核价两套的组件）。 */
    public static final String REASON_NOT_QUOTE_DIALECT = "NOT_QUOTE_DIALECT";
    /** {@code builder_config} 里没有 {@code tabType}。 */
    public static final String REASON_NO_TAB_TYPE = "NO_TAB_TYPE";
    /** 语义图里查不到 {@code (tabType, variantKey, QUOTE)} 的页签视图。 */
    public static final String REASON_TAB_VIEW_NOT_FOUND = "TAB_VIEW_NOT_FOUND";
    /** 锚点是免版本表（主件 / 客户料号 / 电镀方案）—— B-13：这三张本来就不回填。 */
    public static final String REASON_NOT_VERSIONED_SHEET = "NOT_VERSIONED_SHEET";

    /** 一个不参与基础数据升版的组件（D-33）。 */
    public record NonParticipating(UUID componentId, String componentName, String reason) {}

    /**
     * 解析结果。
     *
     * @param bindings        componentId → 绑定（能参与的）
     * @param nonParticipating 不能参与的组件 + 原因，<b>按组件名排序</b>，供预览原样呈现
     */
    public record Resolution(Map<UUID, DsSheetBinding> bindings, List<NonParticipating> nonParticipating) {
        static Resolution empty() { return new Resolution(Map.of(), List.of()); }
    }

    /** 与 {@code QuoteBackfillCollector.SIMPLE_DRIVER_PATH} 同款：只认 {@code $view_name} 简单形态。 */
    private static final Pattern SIMPLE_DRIVER_PATH =
            Pattern.compile("^\\$([a-z_][a-z0-9_]*)(?:\\[[^\\]]*])?$");

    @Inject EntityManager em;
    @Inject QuoteRegistry quoteRegistry;

    /** 锚点物理表名 → 带版本 SheetDef（免版本表不进这张表，B-13 靠 {@code versioned} 判据）。 */
    private Map<String, SheetDef> versionedSheetByTable() {
        Map<String, SheetDef> out = new LinkedHashMap<>();
        for (SheetDef s : ((DatasetRegistry) quoteRegistry).sheets()) {
            if (s.versioned) out.put(s.tableName, s);
        }
        return out;
    }

    /**
     * 便捷入口：只要绑定，不关心谁没参与（{@code _record} 写入侧与价格同步侧用）。
     * <p>🚫 <b>预览侧不要用它</b> —— 预览必须把 {@code nonParticipating} 呈给财务（D-33），
     * 走 {@link #resolveAll}。
     */
    public Map<UUID, DsSheetBinding> resolve(Collection<UUID> componentIds) {
        return resolveAll(componentIds).bindings();
    }

    /**
     * @param componentIds 待解析的组件 id
     * @return 绑定 + 不参与清单。解析不出的组件<b>不会被静默丢掉</b>，一律进
     *         {@link Resolution#nonParticipating()}（D-33）
     */
    public Resolution resolveAll(Collection<UUID> componentIds) {
        Map<UUID, DsSheetBinding> out = new LinkedHashMap<>();
        List<NonParticipating> skipped = new ArrayList<>();
        if (componentIds == null || componentIds.isEmpty()) return Resolution.empty();
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(componentIds));

        // ── ① component（1 条 SQL）────────────────────────────────────────────
        @SuppressWarnings("unchecked")
        List<Object[]> comps = em.createNativeQuery(
                        "SELECT id, data_driver_path, element_price_field, fields, row_key_fields, name " +
                        "FROM component WHERE id IN (:ids)")
                .setParameter("ids", ids).getResultList();
        if (comps.isEmpty()) return Resolution.empty();

        Map<UUID, String> driverPath = new LinkedHashMap<>();
        Map<UUID, String> elementPriceField = new LinkedHashMap<>();
        Map<UUID, List<String>> fieldNames = new LinkedHashMap<>();
        Map<UUID, List<String>> rowKeyFields = new LinkedHashMap<>();
        Map<UUID, String> componentName = new LinkedHashMap<>();
        for (Object[] r : comps) {
            UUID cid = (UUID) r[0];
            driverPath.put(cid, (String) r[1]);
            elementPriceField.put(cid, (String) r[2]);
            fieldNames.put(cid, parseFieldNames((String) r[3]));
            rowKeyFields.put(cid, parseStringArray((String) r[4]));
            componentName.put(cid, (String) r[5]);
        }

        // ── ② component_sql_view（1 条 SQL）──────────────────────────────────
        @SuppressWarnings("unchecked")
        List<Object[]> views = em.createNativeQuery(
                        "SELECT component_id, sql_view_name, builder_config::text " +
                        "FROM component_sql_view " +
                        "WHERE component_id IN (:ids) AND status = 'ACTIVE' AND builder_config IS NOT NULL")
                .setParameter("ids", ids).getResultList();
        Map<String, String> builderByCompAndView = new LinkedHashMap<>();   // "cid|viewName" → builder_config
        for (Object[] r : views) {
            builderByCompAndView.put(r[0] + "|" + r[1], (String) r[2]);
        }

        // ── ③ 语义图：(tab_type, variant_key) → (node_key, physical_table)（1 条 SQL）──
        @SuppressWarnings("unchecked")
        List<Object[]> tabViews = em.createNativeQuery(
                        "SELECT v.tab_type, v.variant_key, n.node_key, n.physical_table, n.grain_columns " +
                        "FROM semantic_tab_view v JOIN semantic_node n ON n.id = v.anchor_node_id " +
                        "WHERE v.dialect = 'QUOTE' AND v.status = 'ACTIVE' AND n.status = 'ACTIVE'")
                .getResultList();
        Map<String, String[]> anchorByTab = new LinkedHashMap<>();          // "tabType|variantKey" → [nodeKey, table]
        Map<String, List<String>> grainByTab = new LinkedHashMap<>();        // → 该节点的粒度列
        for (Object[] r : tabViews) {
            String k = str(r[0]) + "|" + (r[1] == null ? "" : str(r[1]));
            anchorByTab.put(k, new String[]{str(r[2]), str(r[3])});
            grainByTab.put(k, pgTextArray(r[4]));
        }

        Map<String, SheetDef> sheetByTable = versionedSheetByTable();

        // ── ④ 纯内存组装（🚫 循环体内无任何查询：N+1 自检点）────────────────────
        for (UUID cid : ids) {
            String name = componentName.get(cid);
            String path = driverPath.get(cid);
            if (path == null || path.isBlank()) {
                skipped.add(new NonParticipating(cid, name, REASON_NO_DRIVER_PATH));
                continue;
            }
            Matcher m = SIMPLE_DRIVER_PATH.matcher(path.trim());
            if (!m.matches()) {                                             // $$code.name 等形态：本轮不处理
                skipped.add(new NonParticipating(cid, name, REASON_UNSUPPORTED_DRIVER_PATH));
                continue;
            }
            String cfgJson = builderByCompAndView.get(cid + "|" + m.group(1));
            if (cfgJson == null) {                                          // 🔴 手写视图（builder_config IS NULL），实测占多数
                skipped.add(new NonParticipating(cid, name, REASON_NO_BUILDER_CONFIG));
                continue;
            }

            JsonNode cfg;
            try {
                cfg = MAPPER.readTree(cfgJson);
            } catch (Exception e) {
                LOG.warnf("[ds-record] component=%s builder_config 解析失败: %s", cid, e.getMessage());
                skipped.add(new NonParticipating(cid, name, REASON_BUILDER_CONFIG_CORRUPT));
                continue;
            }
            String dialect = cfg.path("dialect").asText("QUOTE");
            // ⚠️ BuilderService#resolveDialect 对缺省/不识别值一律按 QUOTE 处理 —— 这里跟随同一口径，
            //    否则同一个组件「编译时按报价编译、写 _record 时被判非报价」会静默漏写。
            if (dialect != null && !dialect.isBlank()
                    && !"QUOTE".equals(dialect) && !"quote".equalsIgnoreCase(dialect)) {
                skipped.add(new NonParticipating(cid, name, REASON_NOT_QUOTE_DIALECT));
                continue;
            }
            String tabType = cfg.path("tabType").asText(null);
            if (tabType == null || tabType.isBlank()) {
                skipped.add(new NonParticipating(cid, name, REASON_NO_TAB_TYPE));
                continue;
            }
            String variantKey = cfg.path("variantKey").isMissingNode() || cfg.path("variantKey").isNull()
                    ? "" : cfg.path("variantKey").asText("");
            String[] anchor = anchorByTab.get(tabType + "|" + variantKey);
            if (anchor == null) {
                skipped.add(new NonParticipating(cid, name, REASON_TAB_VIEW_NOT_FOUND));
                continue;
            }
            SheetDef sheet = sheetByTable.get(anchor[1]);
            if (sheet == null) {              // 锚点是免版本表（主件/客户料号/电镀方案）→ B-13 不回填
                skipped.add(new NonParticipating(cid, name, REASON_NOT_VERSIONED_SHEET));
                continue;
            }

            Map<String, String> fieldToColumn = new LinkedHashMap<>();
            Map<String, String> viewColumnByField = new LinkedHashMap<>();
            for (JsonNode col : cfg.path("columns")) {
                String srcNode = col.path("sourceNodeKey").asText(null);
                String srcCol = col.path("sourceColumn").asText(null);
                String fieldName = col.path("fieldName").asText(null);
                if (srcNode == null || srcCol == null || fieldName == null || fieldName.isBlank()) continue;
                // 只有落在锚点节点自己身上的列才是主表物理列；其余（LOOKUP 查名 / 价格函数 / 别的节点）
                // 主表对不齐 ⇒ 归 extend_column（B-6 / AC-3）。
                if (!srcNode.equals(anchor[0])) continue;
                if (sheet.column(srcCol) == null) continue;   // 该列不是 ColumnDef（白底名称列等）→ 不落主表
                fieldToColumn.put(fieldName, srcCol);
                String viewCol = col.path("viewColumn").asText(null);
                if (viewCol != null && !viewCol.isBlank()) viewColumnByField.put(fieldName, viewCol);
            }

            List<String> extend = new ArrayList<>();
            for (String fn : fieldNames.getOrDefault(cid, List.of())) {
                if (!fieldToColumn.containsKey(fn)) extend.add(fn);
            }

            // ── 有效粒度列 = 语义节点声明的粒度列 ∩ 本页签**确实表征**的物理列 ──────────────
            List<String> nodeGrain = grainByTab.getOrDefault(tabType + "|" + variantKey, List.of());
            List<String> grain = new ArrayList<>();
            for (String g : nodeGrain) {
                if (sheet.column(g) != null && fieldToColumn.containsValue(g)) grain.add(g);
            }
            if (grain.isEmpty()) {
                // 🚨 P1-1/A（用户裁决前的最低成本措施）：**只告警，不拦截**。
                //    有效粒度列为空 ⇒ 四层锚定的第 ③ 层（粒度列兜底）对本组件**静默失效**。
                //    后果不是报错，是「组每回填一次翻一倍」—— 症状离根因隔两层，
                //    现场会先怀疑回填逻辑，而真因在语义图配置里。
                //    ⚠️ 2026-09-07 实查：13 张带版本表的 grain_columns **全部非空**，
                //       ds 原生模板 12/12 组件的有效粒度列也非空 ⇒ 当下这条不会触发。
                //       但 semantic_node.grain_columns 可经取数配置器写端点改空，
                //       改空之后就只剩这条日志能告诉你发生了什么。
                LOG.warnf("[ds-record] 组件「%s」(%s) 绑定 %s 的**有效粒度列为空** ⇒ 锚定第③层（粒度列兜底）"
                                + "对它失效，用户改值/跨版后该组可能出现「原行保留+追加一行」的膨胀。"
                                + "节点声明的粒度列=%s，本页签表征的物理列=%s（两者交集为空）。"
                                + "排查方向：semantic_node(node_key=%s, dialect=QUOTE).grain_columns "
                                + "与该组件取数配置里勾选的列。",
                        name, cid, sheet.tableName,
                        nodeGrain.isEmpty() ? "(节点未声明)" : nodeGrain,
                        fieldToColumn.values(), anchor[0]);
            }
            out.put(cid, new DsSheetBinding(cid, sheet, anchor[0],
                    Map.copyOf(fieldToColumn), Map.copyOf(viewColumnByField), List.copyOf(extend),
                    elementPriceField.get(cid), rowKeyFields.getOrDefault(cid, List.of()),
                    List.copyOf(grain)));
        }
        skipped.sort(java.util.Comparator.comparing(
                (NonParticipating n) -> n.componentName() == null ? "" : n.componentName()));
        LOG.debugf("[ds-record] 绑定解析：入参组件 %d，命中 %d，不参与 %d（sql=3，与组件数无关）",
                ids.size(), out.size(), skipped.size());
        return new Resolution(out, List.copyOf(skipped));
    }

    /** {@code component.fields} JSONB → 字段名列表（顺序保持）。纯内存。 */
    private static List<String> parseFieldNames(String fieldsJson) {
        List<String> out = new ArrayList<>();
        if (fieldsJson == null || fieldsJson.isBlank()) return out;
        try {
            JsonNode arr = MAPPER.readTree(fieldsJson);
            if (arr != null && arr.isArray()) {
                Set<String> seen = new LinkedHashSet<>();
                for (JsonNode f : arr) {
                    String n = f.path("name").asText(null);
                    if (n != null && !n.isBlank() && seen.add(n)) out.add(n);
                }
            }
        } catch (Exception ignore) {
            // 字段定义读不出来只影响 extend_column 的完整性，不影响主表列映射 —— 安全降级
        }
        return out;
    }

    /** PG {@code text[]} → List（JDBC 可能给 {@code String[]} 或 {@code java.sql.Array}）。 */
    private static List<String> pgTextArray(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        try {
            Object[] arr = (raw instanceof java.sql.Array a) ? (Object[]) a.getArray() : (Object[]) raw;
            for (Object o : arr) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o));
        } catch (Exception ignore) { /* 粒度列读不出只影响兜底锚的可用性，不影响主路径 */ }
        return out;
    }

    /** {@code component.row_key_fields} JSONB（字符串数组）→ List；解析不出返回空表（安全降级）。 */
    private static List<String> parseStringArray(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    String v = n.asText(null);
                    if (v != null && !v.isBlank()) out.add(v);
                }
            }
        } catch (Exception ignore) { /* 行键读不出只影响墓碑剔除的精度，不影响列映射 */ }
        return out;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
