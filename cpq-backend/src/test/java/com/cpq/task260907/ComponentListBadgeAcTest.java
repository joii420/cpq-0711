package com.cpq.task260907;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-3</b> —— F-2「组件列表徽章由页签类型改为数据源名，未绑数据源显示『—』」。
 *
 * <h3>AC 原文（需求文档.md §③ AC-3）</h3>
 * <ul>
 *   <li>① 绑了数据源的组件，列表徽章显示<b>数据源名</b>，不再显示页签类型值；</li>
 *   <li>② 未绑数据源的组件徽章显示 <b>「—」</b>（用户 2026-09-07 裁决）—— 含那些有页签类型的；</li>
 *   <li>③ 列表接口返回数据源名字段，<b>前端不从 {@code tabType} 推导</b>。</li>
 * </ul>
 *
 * <h3>本类覆盖 / 不覆盖</h3>
 * <table>
 *   <tr><td>① ②</td><td>后端契约侧：{@code dataSourceLabel} 的有值/为 null 判据（api.md §3.1）。
 *       徽章上真的画成什么样由 E2E {@code task260907-builder-gaps.spec.ts} 断言。</td></tr>
 *   <tr><td>③</td><td>AC 自带的判据是「前端代码不再引用 {@code comp.tabType}」——
 *       那是<b>代码级</b>判据，本类改验它的<b>可观测后果</b>：
 *       有 {@code tabType} 但没绑数据源的组件，{@code dataSourceLabel} 必须为 {@code null}
 *       （⇒ 前端拿不到可推导的值）。E2E 再验它画出来的是「—」而不是页签类型值。
 *       代码级 grep 由主线亲验承担。</td></tr>
 *   <tr><td>🚧 <b>N+1（api.md §3.3 红线）</b></td>
 *       <td><b>本类不覆盖，判【未验证】。</b> 共享库未装 {@code pg_stat_statements}
 *       （{@code shared_preload_libraries} 为空，装它要改共享 DB 配置 + 重启 ⇒ CLAUDE.md §3.2 环境销毁红线），
 *       测试侧数不到 SQL 条数。⇒ 归后端自检（{@code backend.md}）。本类只把两次调用的耗时打出来供参考，
 *       <b>不据此下结论</b>（耗时受连接池/缓存/并发影响，做判据会是不稳定失败）。</td></tr>
 * </table>
 */
@QuarkusTest
@DisplayName("task-260907 · AC-3 —— 组件列表返回 dataSourceLabel：绑了有值 / 未绑为 null（前端画「—」）")
class ComponentListBadgeAcTest extends Task260907Base {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listComponents(String acRef) {
        long t0 = System.nanoTime();
        Response r = given().get("/api/cpq/components").thenReturn();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertReachedBusinessLayer(r, acRef + " 组件列表");
        assertEquals(200, r.statusCode(), acRef + "：组件列表应 200，实际=" + r.statusCode() + " body=" + r.asString());
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.jsonPath().get("data");
        assertFalse(items == null || items.isEmpty(), acRef + "：组件列表为空 ⇒ 全部徽章断言空跑");
        System.out.println("[" + acRef + "] 组件列表 " + items.size() + " 条，耗时 " + ms
                + "ms（🚧 耗时仅记录，🚫 不作 N+1 判据）");
        return items;
    }

    @Test
    @DisplayName("AC-3①②③：dataSourceLabel 键必须存在（可为 null）；有值的集合 == 库里 builder_version 非空的集合；"
            + "有 tabType 但未绑数据源的组件一律为 null（⇒ 前端只能画「—」，无从推导）")
    void ac3_dataSourceLabelContract() {
        List<Map<String, Object>> items = listComponents("AC-3");

        // ── 阳性对照①：列表里必须同时存在「绑了的」和「没绑的」两侧，否则本条分辨不出契约 ──
        Set<String> boundInDb = new LinkedHashSet<>(strCol(
                "SELECT component_id::text FROM component_sql_view WHERE builder_version IS NOT NULL"));
        long totalComponents = count("SELECT count(*) FROM component");
        System.out.println("[AC-3] 库里 builder_version 非空的组件=" + boundInDb.size()
                + " / 组件总数=" + totalComponents + "（立项时实测 20/222 —— 🚫 移动靶，仅记录）");
        assertFalse(boundInDb.isEmpty(), "AC-3 【未验证】：库里没有任何 builder_version 非空的组件 ⇒ "
                + "「绑了数据源的显示数据源名」这一侧无输入可用，断言会以『全部为 null』的形态空跑。"
                + "这是数据前置缺失，不是通过。");
        assertTrue(boundInDb.size() < totalComponents, "AC-3 【未验证】：所有组件都绑了数据源 ⇒ "
                + "「未绑显示 —」这一侧无输入可用。");

        // ── ①③ 每一项都必须带 dataSourceLabel 键（显式 null，不是缺键）──
        List<String> missingKey = new ArrayList<>();
        for (Map<String, Object> it : items) {
            if (!it.containsKey("dataSourceLabel")) missingKey.add(String.valueOf(it.get("code")));
        }
        assertTrue(missingKey.isEmpty(), "AC-3③/api.md §3.1：组件列表项缺 dataSourceLabel 字段（前 10 个="
                + missingKey.subList(0, Math.min(10, missingKey.size())) + "，共 " + missingKey.size() + " 条）"
                + " ⇒ 前端只能退回用 tabType 推导徽章。");

        // ── 有值 / 为 null 的划分必须与库里的绑定事实一致 ──
        Set<String> labeled = new LinkedHashSet<>();
        Set<String> unlabeled = new LinkedHashSet<>();
        for (Map<String, Object> it : items) {
            String id = String.valueOf(it.get("id"));
            if (it.get("dataSourceLabel") == null) unlabeled.add(id); else labeled.add(id);
        }
        System.out.println("[AC-3] 列表里 dataSourceLabel 有值=" + labeled.size() + " / 为 null=" + unlabeled.size());
        assertFalse(labeled.isEmpty(), "AC-3①：列表里没有任何组件带 dataSourceLabel 值 ⇒ 该字段恒 null，"
                + "徽章会全变「—」。库里明明有 " + boundInDb.size() + " 个绑了数据源的组件。");

        List<String> boundButUnlabeled = boundInDb.stream().filter(unlabeled::contains).toList();
        assertTrue(boundButUnlabeled.isEmpty(), "AC-3①：以下组件在库里绑了数据源（builder_version 非空）"
                + "但列表 dataSourceLabel 为 null =" + boundButUnlabeled);
        List<String> labeledButUnbound = labeled.stream().filter(id -> !boundInDb.contains(id)).toList();
        assertTrue(labeledButUnbound.isEmpty(), "AC-3②：以下组件在库里【没】绑数据源，列表却给了 dataSourceLabel ="
                + labeledButUnbound + " ⇒ 值多半是从 tabType 推导来的（AC-3③ 禁止）。");

        // ── ②③ 关键子集：有 tabType 但没绑数据源的那批，必须为 null（用户 2026-09-07 裁决：显示「—」）──
        Set<String> tabTypedUnbound = new LinkedHashSet<>(strCol(
                "SELECT c.id::text FROM component c LEFT JOIN component_sql_view v ON v.component_id = c.id "
                        + "WHERE c.tab_type IS NOT NULL AND (v.builder_version IS NULL OR v.component_id IS NULL)"));
        System.out.println("[AC-3②] 有页签类型但未绑数据源的组件=" + tabTypedUnbound.size()
                + "（立项时实测 107 —— 🚫 移动靶，仅记录）");
        assertFalse(tabTypedUnbound.isEmpty(), "AC-3② 【未验证】：库里没有「有 tabType 但未绑数据源」的组件 ⇒ "
                + "本条（最容易被『从 tabType 推导』蒙混过关的那一批）无输入可用，断言会空跑。");
        List<String> derived = tabTypedUnbound.stream().filter(labeled::contains).toList();
        assertTrue(derived.isEmpty(), "AC-3②③：以下组件有页签类型、没绑数据源，徽章却拿到了值 =" + derived
                + " ⇒ 值是从 tabType 推导的。用户 2026-09-07 裁决：这批一律显示「—」。");
        System.out.println("[AC-3] ✅ " + tabTypedUnbound.size() + " 个「有页签类型 / 未绑数据源」的组件全部 dataSourceLabel=null");
    }

    @Test
    @DisplayName("AC-3①（正向取值）：新建组件 → 保存取数配置 → 列表徽章立刻拿到该数据源的 label（与下拉里的 label 一致）")
    void ac3_newlyBoundComponentGetsItsSourceLabel() {
        // 用「物料BOM」坐标：它在 availableSources 里的 label 是确定可查的
        List<Object[]> rs = rows("SELECT v.tab_type, coalesce(v.variant_key,''), n.node_key "
                + "FROM semantic_tab_view v JOIN semantic_node n ON n.id=v.anchor_node_id "
                + "WHERE v.dialect='QUOTE' AND v.status='ACTIVE' AND n.physical_table='" + MATERIAL_BOM_TABLE + "' "
                + "ORDER BY (v.tab_type='BOM') DESC LIMIT 1");
        assertFalse(rs.isEmpty(), "AC-3① 前置：找不到物料BOM 坐标");
        String tabType = String.valueOf(rs.get(0)[0]);
        String variantKey = String.valueOf(rs.get(0)[1]);
        String nodeKey = String.valueOf(rs.get(0)[2]);

        // 期望 label：从服务端 availableSources 现取，🚫 不写死「物料BOM」
        String expectedLabel = null;
        for (Map<String, Object> s : availableSources("QUOTE", "AC-3①")) {
            if (tabType.equals(s.get("tabType"))
                    && variantKey.equals(s.get("variantKey") == null ? "" : String.valueOf(s.get("variantKey")))) {
                expectedLabel = String.valueOf(s.get("label"));
            }
        }
        assertFalse(expectedLabel == null || expectedLabel.isBlank(),
                "AC-3① 前置：availableSources 里查不到坐标 " + tabType + "/" + variantKey + " 的 label ⇒ 无期望值可比");

        var cid = createBlankComponent("ac3bind");

        // 绑定前：必须是 null（否则「绑定后有值」证明不了是绑定带来的）
        String before = labelOf(cid.toString(), listComponents("AC-3①绑定前"));
        assertEquals("null", String.valueOf(before), "AC-3① 前置：新建的空白组件 dataSourceLabel 应为 null，实际=" + before);

        Response saved = saveBuilder(cid, cfg("QUOTE", tabType, variantKey,
                colJson(nodeKey, "input_material_no", "投入料号", true, true),
                colJson(nodeKey, "component_qty", "组成数量")));
        assertEquals(200, saved.statusCode(), "AC-3①：取数配置保存应 200，实际=" + saved.statusCode()
                + " body=" + saved.asString());

        String after = labelOf(cid.toString(), listComponents("AC-3①绑定后"));
        System.out.println("[AC-3①] 绑定前=" + before + " 绑定后=" + after + " 期望=" + expectedLabel);
        assertEquals(expectedLabel, after, "AC-3①：绑定数据源后，列表徽章应显示该数据源名（与下拉 label 一致），实际=" + after);
    }

    @Test
    @DisplayName("AC-14（新增 · 模板管理组件卡片徽章的服务端前提）：GET /components 每项都带 dataSourceLabel 键，"
            + "且【不是全部为 null】—— 全 null 就意味着端点没带该字段，是静默失效而不是「数据如此」")
    void ac14_paletteBadgeSourceIsNotAllDashes() {
        List<Map<String, Object>> items = listComponents("AC-14");

        // ① 每项都必须带键（缺键 ⇒ 前端只能退回 tabType 推导 / 或恒画「—」）
        long withKey = items.stream().filter(it -> it.containsKey("dataSourceLabel")).count();
        assertEquals(items.size(), withKey, "AC-14：组件列表里有 " + (items.size() - withKey)
                + " 项缺 dataSourceLabel 键 ⇒ 模板管理的组件卡片徽章拿不到数据源名。");

        // ② 🚨 防空验证：不能全是 null。
        //    「徽章全是 —」在 UI 上和「端点没带该字段」长得一模一样 —— 后者是静默失效，
        //    只看 UI 截图分辨不出来，必须在这一层钉死。
        long nonNull = items.stream().filter(it -> it.get("dataSourceLabel") != null).count();
        long boundInDb = count("SELECT count(*) FROM component_sql_view WHERE builder_version IS NOT NULL");
        System.out.println("[AC-14] 组件 " + items.size() + " 条，dataSourceLabel 非 null 的 " + nonNull
                + " 条（库里 builder_version 非空 " + boundInDb + " 个）");
        assertTrue(nonNull > 0, "AC-14：所有组件的 dataSourceLabel 都是 null ⇒ 模板管理的卡片徽章会全部画成「—」。"
                + "库里明明有 " + boundInDb + " 个组件绑了数据源 ⇒ 这是**端点静默失效**，不是「数据如此」。");
        assertEquals(boundInDb, nonNull, "AC-14：带数据源名的组件数应与库里 builder_version 非空的组件数一致，"
                + "实际 " + nonNull + " vs " + boundInDb);
    }

    private static String labelOf(String componentId, List<Map<String, Object>> items) {
        for (Map<String, Object> it : items) {
            if (componentId.equals(String.valueOf(it.get("id")))) {
                Object v = it.get("dataSourceLabel");
                return v == null ? "null" : String.valueOf(v);
            }
        }
        throw new AssertionError("组件 " + componentId + " 不在列表返回里 ⇒ 断言无对象可验（列表条数=" + items.size() + "）");
    }
}
