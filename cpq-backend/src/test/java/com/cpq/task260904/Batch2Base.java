package com.cpq.task260904;

import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260904 <b>第二批</b>（AC-1 / AC-2 / AC-3 / AC-10 / AC-19 / AC-20 / AC-29）的公共基座。
 *
 * <h3>断言来源</h3>
 * 每条断言指回 {@code dev-docs/task-260904-页签类型收缩/需求文档.md §③} 的 AC 原文，
 * 请求结构指回同目录 {@code api.md §0 / §1 / §2}。
 * <b>🚫 本套用例不读实现代码</b>（不读 {@code cpq-backend/src/main/java/**}、{@code cpq-frontend/src/**}），
 * 只读立项文档、库 schema、库里的配置数据与既有测试代码。
 *
 * <h3>🚨 本批断言的最高纪律：不写死现网数字</h3>
 * 主线 2026-09-06 实测 {@code availableSources} = QUOTE 11 / COST_BASIC 10 / COST_DETAIL 18。
 * <b>这些数字来自共享 dev 库的当前配置数据，不是常量</b>（{@code semantic_tab_view} 随任何一次
 * 语义图种子迁移就会变）。写死它们 = 用例会随业务数据漂移变红，而那个红<b>长得和产品回归一模一样</b>。
 * ⇒ 期望值一律<b>执行期从库里现算</b>（{@code semantic_tab_view} / {@code information_schema.columns}），
 * 断言的是<b>结构不变量</b>（「不含退役值」「dialect 恒等于入参」「sourceKey 唯一」「TREE 恰好 1 个」），
 * 实测数字只<b>打印</b>供报告记录。
 *
 * <h3>🚨 参数名坑（主线 2026-09-06 亲踩）</h3>
 * {@code field-tree} 的方言入参名是 <b>{@code dialect}</b>。传错名字（如 {@code dataset}）会被
 * JAX-RS <b>静默忽略</b>并返回默认方言的结果 —— 用例照样 200、照样有数据、断言照样通过，
 * 但测的是另一个方言。⇒ {@link #assertDialectParamIsHonored} 是这条路径的阳性对照，必须先过。
 */
abstract class Batch2Base extends Task260904Base {

    /** 本次收缩要从<b>新建组件的可选清单</b>里退役的两类页签（需求文档 §①ter）。 */
    protected static final List<String> RETIRED_TAB_TYPES = List.of("零件", "外购件");

    protected static final List<String> DIALECTS = List.of("QUOTE", "COST_BASIC", "COST_DETAIL");

    /**
     * AC-20① 原文点名的系统列（「物理列减去这组系统列」）。
     * <b>逐字取自需求文档 §3.3 AC-20①</b>，🚫 不许为了让用例变绿而往里加东西。
     */
    protected static final Set<String> AC20_SYSTEM_COLUMNS = new LinkedHashSet<>(List.of(
            "id", "version_no", "row_fingerprint", "source", "created_at", "created_by", "updated_at", "updated_by"));

    // ═══════════════════════════ field-tree（api.md §1）═══════════════════════════

    protected Response fieldTree(String tabType, String variantKey, String dialect) {
        Response r = given()
                .queryParam("tabType", tabType)
                .queryParam("variantKey", variantKey == null ? "" : variantKey)
                .queryParam("dialect", dialect)
                .get("/api/cpq/config/semantic-graph/field-tree").thenReturn();
        assertReachedBusinessLayer(r, "field-tree(" + tabType + "/" + variantKey + "/" + dialect + ")");
        return r;
    }

    protected Response fieldTreeOk(String tabType, String variantKey, String dialect, String acRef) {
        Response r = fieldTree(tabType, variantKey, dialect);
        assertEquals(200, r.statusCode(), acRef + "：field-tree(" + tabType + "/" + variantKey + "/" + dialect
                + ") 应 200，实际=" + r.statusCode() + " body=" + r.asString());
        return r;
    }

    /**
     * 某方言里任取一个 ACTIVE 的坐标（用来「进门」拿 {@code availableSources}）。
     * 🚫 不写死 {@code 主件}：坐标值本身是配置数据。
     */
    protected String[] anyActiveCoordinate(String dialect) {
        List<Object[]> rs = rows("SELECT tab_type, coalesce(variant_key,'') FROM semantic_tab_view "
                + "WHERE dialect = '" + dialect + "' AND status = 'ACTIVE' ORDER BY tab_type, variant_key LIMIT 1");
        assertFalse(rs.isEmpty(), "库里 dialect=" + dialect + " 没有任何 ACTIVE 的 semantic_tab_view 行 "
                + "⇒ 本方言下所有断言都会空跑。这是夹具/地基故障，不是 AC 结论。");
        Object[] r = rs.get(0);
        return new String[]{String.valueOf(r[0]), String.valueOf(r[1])};
    }

    /**
     * 取某方言的 {@code availableSources}（api.md §1.2）。
     * <p>🚨 <b>非空保护</b>：清单为空时任何「不含退役值」的断言都会以「通过」的形态空跑
     * （testing.md §3「断言从未执行 = 假绿」）。
     */
    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> availableSources(String dialect, String acRef) {
        String[] coord = anyActiveCoordinate(dialect);
        Response r = fieldTreeOk(coord[0], coord[1], dialect, acRef);
        Object raw = r.jsonPath().get("availableSources");
        assertNotNull(raw, acRef + "：响应里没有 availableSources 字段（api.md §1.2 要求新增它）。body=" + r.asString());
        List<Map<String, Object>> src = (List<Map<String, Object>>) raw;
        assertFalse(src.isEmpty(), acRef + "：dialect=" + dialect + " 的 availableSources 为空 "
                + "⇒ 后续「不含退役值 / semantic 三态」等断言全部空跑。");
        return src;
    }

    /** 库里该方言应当出现在清单里的坐标（= ACTIVE 且 tab_type 不在退役名单）。执行期现算，🚫 不写死。 */
    protected Set<String> expectedSourceCoordinates(String dialect) {
        String retired = "'" + String.join("','", RETIRED_TAB_TYPES) + "'";
        Set<String> out = new LinkedHashSet<>();
        for (Object[] r : rows("SELECT tab_type, coalesce(variant_key,'') FROM semantic_tab_view "
                + "WHERE dialect = '" + dialect + "' AND status = 'ACTIVE' AND tab_type NOT IN (" + retired + ")")) {
            out.add(r[0] + "/" + r[1]);
        }
        return out;
    }

    protected static String coordOf(Map<String, Object> source) {
        return source.get("tabType") + "/" + String.valueOf(source.get("variantKey") == null ? "" : source.get("variantKey"));
    }

    /**
     * 🚨 <b>阳性对照：证明 {@code dialect} 入参真的被消费了。</b>
     *
     * <p>传错参数名会被静默忽略并回落到默认方言 —— 那时「每条的 dialect 恒等于入参」照样成立
     * （因为服务端回显的是它自己用的那个），用例照样绿。所以必须先证明<b>换一个方言，结果确实不同</b>。
     */
    protected void assertDialectParamIsHonored(String acRef) {
        List<Map<String, Object>> q = availableSources("QUOTE", acRef);
        List<Map<String, Object>> c = availableSources("COST_DETAIL", acRef);
        Set<String> qk = new LinkedHashSet<>();
        for (Map<String, Object> s : q) qk.add(String.valueOf(s.get("sourceKey")) + "/" + coordOf(s));
        Set<String> ck = new LinkedHashSet<>();
        for (Map<String, Object> s : c) ck.add(String.valueOf(s.get("sourceKey")) + "/" + coordOf(s));
        assertFalse(qk.equals(ck), acRef + "：QUOTE 与 COST_DETAIL 的 availableSources 完全相同 "
                + "⇒ 🚨 dialect 入参很可能没被消费（参数名不匹配会被 JAX-RS 静默忽略并回落到默认方言）。"
                + "此时所有按方言分支的断言都不可信。QUOTE=" + qk + " COST_DETAIL=" + ck);
        System.out.println("[" + acRef + "] ✅ dialect 入参阳性对照通过：QUOTE(" + qk.size()
                + ") ≠ COST_DETAIL(" + ck.size() + ")");
    }

    // ═══════════════════════════ groups（字段面板）═══════════════════════════

    protected record Group(String groupKey, String groupKind, List<String> sourceColumns) { }

    @SuppressWarnings("unchecked")
    protected List<Group> groupsOf(String tabType, String variantKey, String dialect, String acRef) {
        Response r = fieldTreeOk(tabType, variantKey, dialect, acRef);
        List<Map<String, Object>> raw = (List<Map<String, Object>>) r.jsonPath().get("groups");
        assertNotNull(raw, acRef + "：响应无 groups 字段。body=" + r.asString());
        assertFalse(raw.isEmpty(), acRef + "：" + dialect + "/" + tabType + "/" + variantKey
                + " 的 groups 为空 ⇒ 字段面板断言会空跑。body=" + r.asString());
        List<Group> out = new ArrayList<>();
        for (Map<String, Object> g : raw) {
            List<Map<String, Object>> fs = (List<Map<String, Object>>) g.get("fields");
            List<String> cols = new ArrayList<>();
            if (fs != null) for (Map<String, Object> f : fs) cols.add(String.valueOf(f.get("sourceColumn")));
            out.add(new Group(String.valueOf(g.get("groupKey")), String.valueOf(g.get("groupKind")), cols));
        }
        return out;
    }

    protected static List<Group> mainGroups(List<Group> gs) {
        return gs.stream().filter(g -> "MAIN".equals(g.groupKind())).toList();
    }

    protected static List<Group> priceGroups(List<Group> gs) {
        return gs.stream().filter(g -> "PRICE".equals(g.groupKind())).toList();
    }

    /** 该坐标锚点节点的物理表（AC-20① 要拿它去 {@code information_schema} 算业务列）。 */
    protected String anchorPhysicalTable(String dialect, String tabType, String variantKey) {
        return scalar("SELECT n.physical_table FROM semantic_tab_view v JOIN semantic_node n ON n.id = v.anchor_node_id "
                + "WHERE v.dialect = '" + dialect + "' AND v.tab_type = '" + tabType + "' "
                + "AND coalesce(v.variant_key,'') = '" + (variantKey == null ? "" : variantKey) + "' AND v.status = 'ACTIVE'");
    }

    /**
     * 该坐标锚点节点的 {@code semantic_node.id}。
     *
     * <p>🚨 <b>必须按 id 而不是 {@code node_key} 去查列。</b> 实测 {@code MATERIAL_BOM} / {@code ELEMENT_BOM}
     * 这些 {@code node_key} 在<b>三个方言下各有一个独立节点</b>（列集合并不相同：QUOTE 的
     * {@code ds_quote_material_bom} 有 {@code component_qty}，核价侧的还有 {@code base_qty} 等）。
     * 只按 {@code node_key} 查会跨方言拿到别的表的列 —— 保存时报
     * {@code PHYSICAL_EXISTENCE: 该表在数据库里没有这一列}，那是<b>用例夹具错</b>，不是产品缺陷。
     */
    protected String anchorNodeId(String dialect, String tabType, String variantKey) {
        return scalar("SELECT n.id::text FROM semantic_tab_view v JOIN semantic_node n ON n.id = v.anchor_node_id "
                + "WHERE v.dialect = '" + dialect + "' AND v.tab_type = '" + tabType + "' "
                + "AND coalesce(v.variant_key,'') = '" + (variantKey == null ? "" : variantKey) + "' AND v.status = 'ACTIVE'");
    }

    /** 该锚点节点下的 ACTIVE 列（按 db_column 排序）。 */
    protected List<String> activeColumnsOf(String nodeId) {
        List<String> out = new ArrayList<>();
        for (Object o : col("SELECT db_column FROM semantic_node_column WHERE node_id = '" + nodeId
                + "' AND status = 'ACTIVE' ORDER BY db_column")) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    /** 该锚点节点下带 {@code PART_NO} 角色的列（保存期标识列校验要用）。 */
    protected String partNoColumnOf(String nodeId) {
        return scalar("SELECT db_column FROM semantic_node_column WHERE node_id = '" + nodeId
                + "' AND status = 'ACTIVE' AND 'PART_NO' = ANY(roles) ORDER BY db_column LIMIT 1");
    }

    protected Set<String> physicalColumns(String table) {
        Set<String> out = new LinkedHashSet<>();
        for (Object o : col("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = '" + table + "' ORDER BY ordinal_position")) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    // ═══════════════════════════ compile（api.md §2）═══════════════════════════

    protected Response compileCfg(UUID componentId, String builderConfigJson) {
        Response r = given().contentType(ContentType.JSON).body(builderConfigJson)
                .post("/api/cpq/components/" + componentId + "/builder/compile").thenReturn();
        assertReachedBusinessLayer(r, "compile(" + componentId + ")");
        return r;
    }

    protected String compileSql(UUID componentId, String builderConfigJson, String acRef) {
        Response r = compileCfg(componentId, builderConfigJson);
        assertEquals(200, r.statusCode(), acRef + "：编译应 200，实际=" + r.statusCode() + " body=" + r.asString());
        String sql = r.jsonPath().getString("sql");
        assertNotNull(sql, acRef + "：编译产物里取不到 sql ⇒ 断言会空跑。body=" + r.asString());
        assertFalse(sql.isBlank(), acRef + "：编译产物 sql 为空串 ⇒ 「不含 WITH RECURSIVE」会恒成立。");
        return sql;
    }

    /** {@code GET /components/{id}/builder}：读回已落库的配置（AC-10 状态连续性的观测口）。 */
    protected Response readBuilder(UUID componentId, String acRef) {
        Response r = given().get("/api/cpq/components/" + componentId + "/builder").thenReturn();
        assertReachedBusinessLayer(r, acRef + " readBuilder");
        assertEquals(200, r.statusCode(), acRef + "：读回 builder 配置应 200，实际=" + r.statusCode() + " body=" + r.asString());
        return r;
    }

    /** 从 {@code GET /builder} 响应里取「已选列」的 (sourceColumn → fieldName) 有序映射。 */
    @SuppressWarnings("unchecked")
    protected Map<String, String> persistedColumns(UUID componentId, String acRef) {
        Response r = readBuilder(componentId, acRef);
        List<Map<String, Object>> cols = (List<Map<String, Object>>) r.jsonPath().get("builderConfig.columns");
        assertNotNull(cols, acRef + "：读回的 builderConfig.columns 为 null。body=" + r.asString());
        assertFalse(cols.isEmpty(), acRef + "：读回的已选列为空 ⇒ 「5 列保留」断言会空跑。body=" + r.asString());
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> c : cols) out.put(String.valueOf(c.get("sourceColumn")), String.valueOf(c.get("fieldName")));
        return out;
    }

    /** 从 {@code GET /builder} 响应里取每列的 {@code viewColumn}（AC-19④ 的观测口）。 */
    @SuppressWarnings("unchecked")
    protected Map<String, String> persistedViewColumns(UUID componentId, String acRef) {
        Response r = readBuilder(componentId, acRef);
        List<Map<String, Object>> cols = (List<Map<String, Object>>) r.jsonPath().get("builderConfig.columns");
        assertNotNull(cols, acRef + "：读回的 builderConfig.columns 为 null。body=" + r.asString());
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> c : cols) out.put(String.valueOf(c.get("sourceColumn")), String.valueOf(c.get("viewColumn")));
        assertFalse(out.isEmpty(), acRef + "：viewColumn 映射为空 ⇒ 断言会空跑。");
        return out;
    }

    protected static void assertNoneMatch(List<Map<String, Object>> sources, String key, List<String> forbidden, String msg) {
        List<String> hits = new ArrayList<>();
        for (Map<String, Object> s : sources) {
            Object v = s.get(key);
            if (v != null && forbidden.contains(String.valueOf(v))) hits.add(String.valueOf(s.get("sourceKey")) + "→" + v);
        }
        assertTrue(hits.isEmpty(), msg + " 实际命中=" + hits);
    }
}
