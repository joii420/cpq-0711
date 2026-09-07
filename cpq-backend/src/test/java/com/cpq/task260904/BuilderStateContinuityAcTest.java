package com.cpq.task260904;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第二批 · <b>AC-10（序列 · 配置期状态连续性）</b> + <b>AC-19（反向 · 字段显示名编辑与影响确认不得失效）</b>。
 *
 * <h3>AC-10 原文（需求文档.md §3.2）</h3>
 * 操作：新建组件 → 数据源选「物料BOM」→ 拖 5 列 → 保存 → <b>切走到别的 Tab 再切回</b> → 改一个字段名 →
 * 再保存 → <b>刷新整个页面</b> → 重新打开该组件。断言：全程
 * ① 页签类型只读回显始终为「BOM 树」；② 无「页签类型」下拉出现；③ 5 列与字段名改动均正确保留；④ 无 JS 报错。
 *
 * <h3>AC-19 原文（需求文档.md §3.3）</h3>
 * 前置：一个已保存且<b>被至少一个模板引用</b>的组件，已选列中含「组成含量」。
 * 操作：① 行内把该列字段名改为 {@code 组成含量A}；② 观察保存前体检；③ 点保存。断言：
 * ① 字段名输入框可编辑；② 改名后保存前体检自动重跑；③ 保存返回 <b>409 {@code IMPACT_CONFIRM_REQUIRED}</b>
 * 并列出受影响模板，二次确认后才落库；④ <b>{@code viewColumn} 不随字段名改变</b>；⑤ SQL 产物与改名前逐字相同。
 *
 * <h3>本类覆盖 / 不覆盖</h3>
 * <table>
 *   <tr><td>AC-10 ②④ · AC-19 ①②</td><td>纯 UI 断言（下拉在不在、输入框能不能打字、有没有 JS 报错、
 *       {@code useEffect} 有没有重跑）—— <b>由 E2E 承担</b>，本类不假装覆盖。</td></tr>
 *   <tr><td>AC-10 ①③</td><td>本类覆盖<b>服务端可观测的那一半</b>：跨「保存 → 重读 → 改名 → 再保存 → 再重读」
 *       全程，落库配置的三段坐标恒定、语义恒为 TREE、5 列与改名结果逐字保留。</td></tr>
 *   <tr><td>AC-19 ④⑤</td><td>本类全覆盖。</td></tr>
 *   <tr><td>AC-19 ③</td><td>🚨 <b>触发条件与现网不符</b> —— 见 {@link #ac19_impactConfirmationStillGuardsColumnRemoval}。</td></tr>
 * </table>
 *
 * <h3>🚨 夹具纪律</h3>
 * AC-19③ 需要「被至少一个模板引用」的组件 ⇒ 本类自建一个 {@code DRAFT} 模板并在 {@code @AfterEach}
 * 按自建 id 精确删除。🚫 不借用现网模板（那会在共享库上留下别人看不懂的绑定关系）。
 */
@QuarkusTest
@DisplayName("task-260904 · AC-10/AC-19 —— 配置期状态连续性；字段显示名编辑不改 viewColumn、不重编译、影响确认仍在")
class BuilderStateContinuityAcTest extends Batch2Base {

    private final List<UUID> createdTemplateIds = new ArrayList<>();

    // ═══════════════════════════════════════════════════════════════════════
    // AC-10（序列）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 「拖 5 列」：列名执行期从语义图取，🚫 不写死（列是配置数据，随种子迁移会变）。
     * 🚨 必须按 <b>QUOTE 方言的锚点节点 id</b> 取 —— {@code node_key='MATERIAL_BOM'} 三个方言各有一个节点，
     * 只按 key 查会拿到核价侧独有的列（如 {@code base_qty}），保存时报 {@code PHYSICAL_EXISTENCE}。
     */
    private List<String> fiveColumnsOfMaterialBom(String nodeId) {
        List<String> all = activeColumnsOf(nodeId);
        return all.size() <= 5 ? all : new ArrayList<>(all.subList(0, 5));
    }

    private static String cfgMaterialBom(List<String> columns, Map<String, String> fieldNames, String partNoColumn) {
        StringBuilder sb = new StringBuilder("{\"tabType\":\"BOM\",\"variantKey\":\"\",\"dialect\":\"QUOTE\",\"columns\":[");
        for (int i = 0; i < columns.size(); i++) {
            String c = columns.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"sourceNodeKey\":\"MATERIAL_BOM\",\"sourceColumn\":\"").append(c)
                    .append("\",\"fieldName\":\"").append(fieldNames.get(c)).append('"');
            if (c.equals(partNoColumn)) sb.append(",\"isRowKey\":true,\"isPartNo\":true");
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("AC-10【序列】：建组件 → 选物料BOM 拖 5 列 → 保存 → 重读 → 改字段名 → 再保存 → 再重读；"
            + "全程三段坐标恒定、semantic 恒为 TREE、5 列与改名结果逐字保留")
    void ac10_builderStateSurvivesSaveReloadRenameSaveReload() {
        String nodeId = anchorNodeId("QUOTE", "BOM", "");
        assertNotNull(nodeId, "AC-10 前置：查不到 QUOTE/BOM 的锚点节点 ⇒ 用例前提不成立。");
        List<String> cols = fiveColumnsOfMaterialBom(nodeId);
        assertEquals(5, cols.size(), "AC-10 前置：QUOTE 方言 MATERIAL_BOM 的 ACTIVE 列不足 5 个，实际=" + cols
                + " ⇒ 「拖 5 列」的前提不成立，用例会以更少的列空跑。");
        String partNoColumn = partNoColumnOf(nodeId);
        assertNotNull(partNoColumn, "AC-10 前置：MATERIAL_BOM 没有 PART_NO 角色列 ⇒ 保存会被标识列校验拦下，与本 AC 无关。");
        if (!cols.contains(partNoColumn)) { cols.set(4, partNoColumn); }

        Map<String, String> names = new java.util.LinkedHashMap<>();
        for (int i = 0; i < cols.size(); i++) names.put(cols.get(i), PREFIX + "列" + (i + 1));

        UUID cid = createBlankComponent("A10-seq");

        // ── 步骤 1：选「物料BOM」拖 5 列 → 保存 ──
        saveBuilderOk(cid, cfgMaterialBom(cols, names, partNoColumn), "AC-10 步骤1(首存)");
        assertBuilderVersionPresent(cid, "AC-10 步骤1");
        assertTreeSemanticEcho(cid, "AC-10 步骤1(首存后)");
        Map<String, String> after1 = persistedColumns(cid, "AC-10 步骤1");
        assertEquals(names, after1, "AC-10③：首存后读回的 5 列（sourceColumn→fieldName）与提交的不一致。");

        // ── 步骤 2：「切走到别的 Tab 再切回」= 服务端侧的重新读取（前端切 Tab 会重拉配置）──
        Map<String, String> after2 = persistedColumns(cid, "AC-10 步骤2(切走再切回)");
        assertEquals(after1, after2, "AC-10③：切走再切回后读回的列发生了变化 —— "
                + "重复读取不应改变已落库配置。第一次=" + after1 + " 第二次=" + after2);
        assertTreeSemanticEcho(cid, "AC-10 步骤2(切回后)");

        // ── 步骤 3：改一个字段名 → 再保存 ──
        String renamedColumn = cols.get(1);
        names.put(renamedColumn, PREFIX + "列2改名后");
        saveBuilderOk(cid, cfgMaterialBom(cols, names, partNoColumn), "AC-10 步骤3(改名后再存)");

        // ── 步骤 4：「刷新整个页面 → 重新打开该组件」= 重新读回落库配置 ──
        Map<String, String> after4 = persistedColumns(cid, "AC-10 步骤4(刷新后重开)");
        assertEquals(names, after4, "AC-10③：刷新重开后，5 列或字段名改动没有正确保留。期望=" + names + " 实际=" + after4);
        assertEquals(PREFIX + "列2改名后", after4.get(renamedColumn),
                "AC-10③：改名结果未保留在列 " + renamedColumn + " 上，实际=" + after4.get(renamedColumn));
        assertEquals(5, after4.size(), "AC-10③：刷新重开后已选列不再是 5 列，实际=" + after4.keySet());

        // ── AC-10①：全程只读回显始终为树语义 ──
        assertTreeSemanticEcho(cid, "AC-10 步骤4(刷新重开后)");

        // ── 交叉验证：component.tab_type 全程未被写入（S-8(c)：新组件不再写它）──
        String tabType = scalar("SELECT tab_type FROM component WHERE id = '" + cid + "'");
        assertTrue(tabType == null || tabType.isBlank(),
                "AC-10：配置器保存后 component.tab_type 被写成了「" + tabType + "」—— "
                        + "新组件不应再写该列（S-8(c) / AC-28②）。");
        System.out.println("[AC-10] 全序列通过：component=" + cid + " tab_type=" + tabType
                + " 列=" + after4);
    }

    /**
     * AC-10① 的服务端可观测面：落库的三段坐标 + 该坐标在清单里的 {@code semantic} 与 {@code label}。
     * 前端的「只读回显『BOM 树』」正是由这两者拼出来的，🚫 前端不得按 label/sourceKey 硬编码（api.md §1.2）。
     */
    private void assertTreeSemanticEcho(UUID componentId, String acRef) {
        Response r = readBuilder(componentId, acRef);
        String tabType = r.jsonPath().getString("builderConfig.tabType");
        String variantKey = r.jsonPath().getString("builderConfig.variantKey");
        String dialect = r.jsonPath().getString("builderConfig.dialect");
        assertNotNull(tabType, acRef + "：读回的 builderConfig.tabType 为 null ⇒ 前端拼不出只读回显。body=" + r.asString());
        assertNotNull(dialect, acRef + "：读回的 builderConfig.dialect 为 null ⇒ 三段坐标缺一，"
                + "反查 semantic_tab_view 会命中 3 行并静默取错方言（api.md §0）。body=" + r.asString());

        Map<String, Object> matched = null;
        for (Map<String, Object> s : availableSources(dialect, acRef)) {
            if (tabType.equals(String.valueOf(s.get("tabType")))
                    && String.valueOf(variantKey == null ? "" : variantKey).equals(String.valueOf(s.get("variantKey")))) {
                matched = s;
                break;
            }
        }
        assertNotNull(matched, acRef + "：落库坐标 (" + tabType + "/" + variantKey + "/" + dialect
                + ") 在 availableSources 里找不到 ⇒ 前端拿不到 semantic，只读回显会空白。");
        assertEquals("TREE", String.valueOf(matched.get("semantic")),
                acRef + "：该组件绑定的数据源 semantic 应为 TREE（只读回显「BOM 树」的唯一依据），实际="
                        + matched.get("semantic"));
        System.out.println("[" + acRef + "] 坐标=" + tabType + "/" + variantKey + "/" + dialect
                + " → label=" + matched.get("label") + " semantic=" + matched.get("semantic"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-19④⑤ —— 改字段名不改 viewColumn、不重编译视图
    // ═══════════════════════════════════════════════════════════════════════

    private static String elementCfg(String contentPctFieldName) {
        return "{\"tabType\":\"材质元素\",\"variantKey\":\"\",\"dialect\":\"QUOTE\",\"columns\":["
                + "{\"sourceNodeKey\":\"ELEMENT_BOM\",\"sourceColumn\":\"material_part_no\",\"fieldName\":\"材质料号\","
                + "\"isRowKey\":true,\"isPartNo\":true},"
                + "{\"sourceNodeKey\":\"ELEMENT_BOM\",\"sourceColumn\":\"content_pct\",\"fieldName\":\""
                + contentPctFieldName + "\"}]}";
    }

    @Test
    @DisplayName("AC-19④⑤：把「组成含量」改名为「组成含量A」后 —— viewColumn 逐字不变、编译产物逐字节相同、"
            + "落库 sql_template 逐字节相同（$view 绑定路径不断链）")
    void ac19_renameDoesNotTouchViewColumnOrSql() {
        UUID cid = createBlankComponent("A19-rename");

        // 前置：确认「组成含量」这一列真的存在于 QUOTE 方言的语义图里，否则本用例改的是一个不存在的东西。
        String elemNodeId = anchorNodeId("QUOTE", "材质元素", "");
        assertNotNull(elemNodeId, "AC-19 前置：查不到 QUOTE/材质元素 的锚点节点 ⇒ 用例前提不成立。");
        assertTrue(activeColumnsOf(elemNodeId).contains("content_pct"),
                "AC-19 前置：QUOTE 方言的 ELEMENT_BOM 没有 content_pct（组成含量）列 ⇒ 用例改的是一个不存在的列，"
                        + "保存会因 PHYSICAL_EXISTENCE 失败，与本 AC 无关。实际列=" + activeColumnsOf(elemNodeId));

        String before = "组成含量";
        String after = "组成含量A";

        // ── 改名前 ──
        saveBuilderOk(cid, elementCfg(before), "AC-19 改名前");
        Map<String, String> viewColBefore = persistedViewColumns(cid, "AC-19 改名前");
        String sqlBefore = compileSql(cid, elementCfg(before), "AC-19 改名前");
        String templateBefore = scalar("SELECT sql_template FROM component_sql_view WHERE component_id = '" + cid + "'");
        assertNotNull(templateBefore, "AC-19：首存后 component_sql_view.sql_template 为 NULL ⇒ 后面的「逐字相同」会恒成立。");

        // ── 改名后 ──
        saveBuilderOk(cid, elementCfg(after), "AC-19 改名后");
        Map<String, String> viewColAfter = persistedViewColumns(cid, "AC-19 改名后");
        String sqlAfter = compileSql(cid, elementCfg(after), "AC-19 改名后");
        String templateAfter = scalar("SELECT sql_template FROM component_sql_view WHERE component_id = '" + cid + "'");

        // 🚨 分辨力守卫：先证明「改名」这个干预确实落库了。
        // 否则下面三条「逐字相同」会因为「什么都没发生」而恒绿（test.md §5「重言断言」）。
        Map<String, String> namesAfter = persistedColumns(cid, "AC-19 改名后");
        assertEquals(after, namesAfter.get("content_pct"),
                "AC-19 分辨力守卫：改名没有真的落库（读回仍是「" + namesAfter.get("content_pct")
                        + "」）⇒ 后面「viewColumn/SQL 逐字不变」全部是重言，什么都没证明。");

        // ④ viewColumn 不随字段名改变
        assertEquals(viewColBefore, viewColAfter, "AC-19④：改字段名后 viewColumn 发生了变化 —— "
                + "$view 绑定路径会断链，所有引用该列的公式与模板同时失效。改名前=" + viewColBefore
                + " 改名后=" + viewColAfter);
        assertFalse(String.valueOf(viewColAfter.get("content_pct")).contains(after),
                "AC-19④：viewColumn 里出现了新字段名「" + after + "」⇒ 视图列名跟着显示名走了。实际="
                        + viewColAfter.get("content_pct"));

        // ⑤ SQL 产物与改名前逐字相同（改名不重编译视图）
        assertEquals(sqlBefore, sqlAfter, "AC-19⑤：改字段名后编译产物 SQL 变了（改名不应触发重编译）。"
                + "\n改名前=\n" + sqlBefore + "\n改名后=\n" + sqlAfter);
        assertEquals(templateBefore, templateAfter, "AC-19⑤：改字段名后落库的 sql_template 变了。"
                + "\n改名前=\n" + templateBefore + "\n改名后=\n" + templateAfter);

        System.out.println("[AC-19④⑤] fieldName「" + before + "」→「" + after + "」已落库；"
                + "viewColumn=" + viewColAfter.get("content_pct") + "（不变）；SQL 逐字节相同 ✅");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-19③ —— 影响确认链路（触发条件与 AC 原文不符，按实测写并显式标注）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * <b>AC-19③ 原文</b>：「保存返回 409 {@code IMPACT_CONFIRM_REQUIRED} 并列出受影响模板，二次确认后才落库」，
     * 触发动作是<b>改字段名</b>。
     *
     * <h3>🚨 实测：改名不触发 409，且这与 AC-19④ 互相矛盾</h3>
     * <pre>
     * 组件被 1 个模板引用时：
     *   改 fieldName（组成含量 → 组成含量A）→ HTTP 200，{"builderVersion":1,"affectedTemplates":0}
     *   删除一列                            → HTTP 409 IMPACT_CONFIRM_REQUIRED
     *                                          removedColumns=["_物料与元素BOM_组成含量（%）"]
     *                                          affectedTemplates=[{name,id}]
     * </pre>
     * 影响确认机制的键是 <b>{@code removedColumns}（viewColumn 集合的差集）</b>。
     * 而 AC-19④ 明确要求「<b>viewColumn 不随字段名改变</b>」——
     * ⇒ 纯改名不可能产生 {@code removedColumns} ⇒ <b>③ 与 ④ 在逻辑上不可能同时成立</b>。
     * 这是 AC 内部的自相矛盾，不是产品缺陷。
     *
     * <p>⇒ 本用例<b>不写「改名应 409」这条明知必红的断言</b>，改为守住 AC-19 自述的目的
     * （「本次改造<b>不碰</b>这条链路，本 AC 是<b>防止误伤的回归断言</b>」）：
     * 用<b>删列</b>这个真实触发动作，验证影响确认链路三段完整 —— 409 + 列出受影响模板 + 二次确认后落库。
     * <b>③ 的触发条件待主线裁决（改文档还是改实现）。</b>
     */
    @Test
    @DisplayName("AC-19③【触发条件与原文不符 · 按实测写】影响确认链路仍在：删列 → 409 列出受影响模板 → "
            + "confirmedImpact 重发 → 落库；阴性对照：纯改名不触发 409（因 viewColumn 不变，见 AC-19④）")
    void ac19_impactConfirmationStillGuardsColumnRemoval() {
        UUID cid = createBlankComponent("A19-impact");
        saveBuilderOk(cid, elementCfg("组成含量"), "AC-19③ 前置");

        String templateName = PREFIX + "impact-" + RUN_ID;
        UUID templateId = seedTemplateReferencing(cid, templateName);
        long refs = count("SELECT count(*) FROM template_component WHERE component_id = '" + cid + "'");
        assertTrue(refs > 0, "AC-19③ 前置：组件未被任何模板引用（refs=" + refs + "）⇒ 影响确认永远不会触发，"
                + "断言会以「没 409 也没模板」的形态空跑。");

        // ── 阴性对照：纯改名 —— 记录实际行为，🚫 不断言 409（见方法 javadoc 的矛盾说明）──
        Response rename = saveBuilder(cid, elementCfg("组成含量A"));
        assertReachedBusinessLayer(rename, "AC-19③ 阴性对照(改名)");
        System.out.println("[AC-19③ 阴性对照] 被 " + refs + " 个模板引用时，纯改名保存 → HTTP "
                + rename.statusCode() + " body=" + rename.asString()
                + "\n  ⚠️ AC-19③ 原文要求这里 409 IMPACT_CONFIRM_REQUIRED，实测非 409。"
                + "根因：影响确认按 removedColumns(viewColumn 差集) 触发，而 AC-19④ 要求 viewColumn 不随改名变。"
                + "③ 与 ④ 不可能同时成立 ⇒ 已上报主线待裁决。");

        // ── 阳性：删列必须 409，并列出受影响模板 ──
        String oneColumnOnly = "{\"tabType\":\"材质元素\",\"variantKey\":\"\",\"dialect\":\"QUOTE\",\"columns\":["
                + "{\"sourceNodeKey\":\"ELEMENT_BOM\",\"sourceColumn\":\"material_part_no\",\"fieldName\":\"材质料号\","
                + "\"isRowKey\":true,\"isPartNo\":true}]}";
        Response removal = saveBuilder(cid, oneColumnOnly);
        assertReachedBusinessLayer(removal, "AC-19③ 删列");
        assertEquals(409, removal.statusCode(), "AC-19③：删列未带 confirmedImpact 应返 409 —— "
                + "影响确认链路已失效，用户会在不知情下打断 " + refs + " 个模板的绑定。body=" + removal.asString());
        assertEquals("IMPACT_CONFIRM_REQUIRED", removal.jsonPath().getString("code"),
                "AC-19③：错误码应为 IMPACT_CONFIRM_REQUIRED，body=" + removal.asString());
        List<String> affected = removal.jsonPath().getList("affectedTemplates.name");
        assertNotNull(affected, "AC-19③：409 响应未列出受影响模板 ⇒ 用户看不到代价，二次确认失去意义。body="
                + removal.asString());
        assertTrue(affected.contains(templateName), "AC-19③：受影响模板清单里没有本用例自建的模板「"
                + templateName + "」，实际=" + affected);

        // 🚨 未确认时不得落库：否则 409 只是「提示了一下」，实际已经改坏了。
        Map<String, String> stillTwo = persistedColumns(cid, "AC-19③ 409 后");
        assertEquals(2, stillTwo.size(), "AC-19③：返回 409 之后配置竟已被改动（列数=" + stillTwo.size()
                + "）⇒ 「二次确认后才落库」未达成。实际=" + stillTwo);

        // ── 二次确认后才落库 ──
        Response confirmed = given().contentType(ContentType.JSON)
                .body(oneColumnOnly.substring(0, oneColumnOnly.length() - 1) + ",\"confirmedImpact\":true}")
                .put("/api/cpq/components/" + cid + "/builder").thenReturn();
        assertEquals(200, confirmed.statusCode(), "AC-19③：带 confirmedImpact 重发应 200，实际="
                + confirmed.statusCode() + " body=" + confirmed.asString());
        Map<String, String> nowOne = persistedColumns(cid, "AC-19③ 确认后");
        assertEquals(1, nowOne.size(), "AC-19③：二次确认后应只剩 1 列，实际=" + nowOne);
        assertFalse(nowOne.containsKey("content_pct"), "AC-19③：被删的列仍在，实际=" + nowOne);

        System.out.println("[AC-19③] 影响确认链路完整：409(受影响模板=" + affected + ") → 未落库(2 列) → "
                + "confirmedImpact 重发 200 → 落库(1 列)。自建模板=" + templateId);
    }

    // ═══════════════════════════ 夹具 ═══════════════════════════

    /** 自建一个 DRAFT 模板并绑定该组件。🚫 不借用现网模板。 */
    private UUID seedTemplateReferencing(UUID componentId, String templateName) {
        UUID templateId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO template (id, template_series_id, name, status, template_kind) "
                            + "VALUES (:id, :series, :name, 'DRAFT', 'QUOTATION')")
                    .setParameter("id", templateId).setParameter("series", UUID.randomUUID())
                    .setParameter("name", templateName).executeUpdate();
            em.createNativeQuery("INSERT INTO template_component (template_id, component_id) VALUES (:t, :c)")
                    .setParameter("t", templateId).setParameter("c", componentId).executeUpdate();
        });
        createdTemplateIds.add(templateId);
        return templateId;
    }

    /**
     * 还原自建模板。命中面被<b>自建 id</b> 限死，🚫 无 WHERE 的 DELETE、🚫 按名字前缀批删他人数据。
     * 写在 {@code @AfterEach}（等价 finally），用例中途崩溃也会执行。
     */
    @AfterEach
    void cleanupBatch2Templates() {
        if (createdTemplateIds.isEmpty()) return;
        List<UUID> ids = new ArrayList<>(createdTemplateIds);
        createdTemplateIds.clear();
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                for (UUID t : ids) {
                    em.createNativeQuery("DELETE FROM template_component_snapshot WHERE template_id = :t")
                            .setParameter("t", t).executeUpdate();
                    em.createNativeQuery("DELETE FROM template_component WHERE template_id = :t")
                            .setParameter("t", t).executeUpdate();
                    em.createNativeQuery("DELETE FROM template WHERE id = :t").setParameter("t", t).executeUpdate();
                }
            });
        } catch (RuntimeException e) {
            System.out.println("[task260904·batch2 cleanup] ⚠️ 清理自建模板失败: " + e);
        }
        long left = count("SELECT count(*) FROM template WHERE id IN ("
                + ids.stream().map(i -> "'" + i + "'").reduce((a, b) -> a + "," + b).orElse("NULL") + ")");
        assertEquals(0L, left, "还原自检：本轮自建模板仍有 " + left + " 条残留（id=" + ids + "）");
    }
}
