package com.cpq.task260915;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260915 · 分片 S-B · <b>AC-12</b>：导入预览新增「配置器坐标可解析性」报告项。
 *
 * <p><b>AC-12 原文</b>：调用导入预览接口，响应中每个组件带一项配置器坐标解析结果，取值为
 * {@code RESOLVED} / {@code UNRESOLVABLE} / {@code NOT_BUILDER} 之一；
 * {@code 取值配置器测试} 目录的 6 个 builder 组件全部为 {@code RESOLVED}。
 *
 * <h3>本类只打后端真实响应，不验 UI</h3>
 * 主线通知：前端的三态渲染当时是<b>模拟注入</b>验证的，<b>不构成后端契约已实现的证据</b>。
 * 故 AC-12 的把关全在本类的接口层断言上。（antd v6 的 {@code .ant-tooltip-inner} 坑与本类无关 ——
 * 本类不碰任何 DOM。）
 *
 * <h3>两条防空跑设计</h3>
 * <ol>
 *   <li><b>必须有阴性样本</b>：只验"全 RESOLVED"的话，一个恒返回 {@code RESOLVED} 的实现也能全绿。
 *       故 T1 的私有夹具里特意放了一个坐标不存在的组件，要求它被判成 {@code UNRESOLVABLE}，
 *       和一个没有 builder 视图的组件，要求判成 {@code NOT_BUILDER} —— 三态都要被真正区分出来。</li>
 *   <li><b>基准目录的期望值由 DB 现查</b>：AC-12 写的"6 个"是 {@code cpq_db_0724} 的实查值；
 *       {@code cpq_db_test} 里实查为 <b>5 个</b> builder 组件（见回报）。硬编码 6 会得到一条
 *       与实现无关的红。故 T2 用 SQL 现算期望值（{@code test.md §4}：目录 id 与计数都不要硬编码）。</li>
 * </ol>
 */
@QuarkusTest
@TestProfile(Sb260915Profile.class)
@DisplayName("task-260915 S-B · AC-12 预览返回 builderCoord 三态")
class Ac12PreviewBuilderCoordTest extends Sb260915TestBase {

    private static final Set<String> LEGAL = Set.of("RESOLVED", "UNRESOLVABLE", "NOT_BUILDER");

    /** 实查 cpq_db_test：{@code (BOM, '', QUOTE)} 在 semantic_tab_view 里存在且 ACTIVE。 */
    private static final String RESOLVABLE_BUILDER_CONFIG =
            "{\"dialect\":\"QUOTE\",\"tabType\":\"BOM\",\"switches\":null,\"axisScope\":\"CLOSURE\","
                    + "\"variantKey\":\"\",\"priceStrategy\":null,\"builderVersion\":1,\"columns\":[]}";

    /** 故意写一个 semantic_tab_view 里不存在的 tabType —— 阴性样本，证明实现不是恒返回 RESOLVED。 */
    private static final String UNRESOLVABLE_BUILDER_CONFIG =
            "{\"dialect\":\"QUOTE\",\"tabType\":\"RT-SB-260915-不存在的类型\",\"switches\":null,"
                    + "\"axisScope\":\"CLOSURE\",\"variantKey\":\"\",\"priceStrategy\":null,"
                    + "\"builderVersion\":1,\"columns\":[]}";

    // ══════════════════════════════════════════════════════════════════════
    // T1：本片私有夹具 —— 三态都要被区分出来
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-12-a: 预览对三类组件分别给出 RESOLVED / UNRESOLVABLE / NOT_BUILDER，且预览不写库")
    void previewDiscriminatesThreeStates() throws Exception {
        UUID src = createDirectory("AC12SRC");

        String codeResolved = PREFIX + "AC12-RESOLVED";
        String codeUnresolvable = PREFIX + "AC12-UNRESOLVABLE";
        String codeNotBuilderHandwritten = PREFIX + "AC12-HANDWRITTEN";
        String codeNotBuilderNoView = PREFIX + "AC12-NOVIEW";

        UUID c1 = insertSimpleComponent(src, codeResolved, PREFIX + "AC12 可解析");
        insertSqlView(c1, "rt_sb_ac12_v1", "SELECT 1 AS x", RESOLVABLE_BUILDER_CONFIG, 1);

        UUID c2 = insertSimpleComponent(src, codeUnresolvable, PREFIX + "AC12 坐标不存在");
        insertSqlView(c2, "rt_sb_ac12_v2", "SELECT 1 AS x", UNRESOLVABLE_BUILDER_CONFIG, 1);

        UUID c3 = insertSimpleComponent(src, codeNotBuilderHandwritten, PREFIX + "AC12 手写视图");
        insertSqlView(c3, "rt_sb_ac12_v3", "SELECT 1 AS x", null, null);

        insertSimpleComponent(src, codeNotBuilderNoView, PREFIX + "AC12 无视图");

        // 导出 → 得到 1.1 包（预览的输入必须是真实导出产物，不是手搓 JSON）
        Response ex = exportDirectory(src);
        assertEquals(200, ex.statusCode(), "导出应 200，body=" + ex.asString());
        JsonNode bundle = M.readTree(ex.asString());
        assertEquals(4, bundle.path("components").size(),
                "前置：源目录应导出 4 个组件，实际 " + bundle.path("components").size());

        UUID target = createDirectory("AC12DST");
        Response pv = preview(target, bundle.toString(), "?conflictPolicy=RENAME");
        assertEquals(200, pv.statusCode(), "预览应 200，body=" + pv.asString());

        JsonNode data = M.readTree(pv.asString()).path("data");
        Map<String, JsonNode> byCode = new HashMap<>();
        for (JsonNode c : data.path("components")) {
            byCode.put(c.path("code").asText(), c);
        }
        assertEquals(4, byCode.size(), "预览应逐个回报 4 个组件，实际 " + byCode.keySet());

        // 每个组件都必须带 builderCoord，且 status 在三态枚举内
        for (Map.Entry<String, JsonNode> e : byCode.entrySet()) {
            JsonNode coord = e.getValue().path("builderCoord");
            assertTrue(coord.isObject(),
                    "AC-12：每个组件都应带 builderCoord 对象，组件 " + e.getKey() + " 实际=" + coord
                            + "\n（api.md §二：builderCoord 与既有 formulaBinding 并列）");
            String st = coord.path("status").asText(null);
            assertNotNull(st, "组件 " + e.getKey() + " 的 builderCoord.status 为空");
            assertTrue(LEGAL.contains(st),
                    "AC-12：builderCoord.status 只能是 " + LEGAL + "，组件 " + e.getKey() + " 实际=" + st);
        }

        assertEquals("RESOLVED", byCode.get(codeResolved).path("builderCoord").path("status").asText(),
                "坐标 (BOM,'',QUOTE) 在 cpq_db_test 的 semantic_tab_view 里实查存在且 ACTIVE，应判 RESOLVED。"
                        + " 实际=" + byCode.get(codeResolved).path("builderCoord"));

        JsonNode un = byCode.get(codeUnresolvable).path("builderCoord");
        assertEquals("UNRESOLVABLE", un.path("status").asText(),
                "阴性样本：tabType 写的是库里不存在的类型，必须判 UNRESOLVABLE。实际=" + un
                        + "\n → 若这里也是 RESOLVED，说明该字段没有真的去解析（恒返回常量），"
                        + "上面那条 RESOLVED 断言就是假绿");
        assertTrue(un.path("message").isTextual() && !un.path("message").asText().isBlank(),
                "api.md §二：UNRESOLVABLE 时 message 要给人话原因（说明缺哪个坐标），实际=" + un.path("message"));

        assertEquals("NOT_BUILDER", byCode.get(codeNotBuilderHandwritten).path("builderCoord").path("status").asText(),
                "手写视图（builder_config IS NULL）应判 NOT_BUILDER");
        assertEquals("NOT_BUILDER", byCode.get(codeNotBuilderNoView).path("builderCoord").path("status").asText(),
                "没有任何 SQL 视图的组件应判 NOT_BUILDER");

        // api.md §二 硬约束 1：UNRESOLVABLE 不进 blockers、不让 canCommit 变 false
        assertTrue(data.path("canCommit").asBoolean(false),
                "AC-17 口径（api.md §二硬约束 1）：坐标不可解析只报不拦，canCommit 应仍为 true。"
                        + " blockers=" + data.path("blockers"));
        String blockers = data.path("blockers").toString();
        assertTrue(!blockers.contains(codeUnresolvable),
                "坐标不可解析的组件不该出现在 blockers 里，实际 blockers=" + blockers);

        // api.md §二 硬约束 3：预览是只读的
        assertEquals(0L, componentCountIn(target),
                "预览必须只读：目标目录（本片私有）在预览后仍应是 0 个组件，实际 " + componentCountIn(target));
    }

    // ══════════════════════════════════════════════════════════════════════
    // T2：AC-12 原文点名的基准目录「取值配置器测试」（期望值由 DB 现查，不硬编码 6）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-12-b: 基准目录「取值配置器测试」的 builder 组件在预览里全部 RESOLVED（个数由 DB 现算）")
    void baselineDirectoryBuilderComponentsAllResolved() throws Exception {
        UUID baseline = directoryIdByName("取值配置器测试");

        long builderComponents = ((Number) em.createNativeQuery(
                        "SELECT count(DISTINCT c.id) FROM component c "
                                + "JOIN component_sql_view v ON v.component_id = c.id "
                                + "AND v.builder_config IS NOT NULL "
                                // 🚫 不加 status 过滤：导出端不按 status 过滤（AC-20 前置原文），
                                // 这里的期望值必须与导出的实际口径一致，否则会得到一条与实现无关的红。
                                + "WHERE c.directory_id = :dir")
                .setParameter("dir", baseline).getSingleResult()).longValue();

        // 前置：基准目录必须真的有 builder 组件，否则「全部 RESOLVED」是对空集合成立的废话
        assertTrue(builderComponents > 0,
                "前置：基准目录里一个 builder 组件都没有 ⇒ AC-12-b 空跑（allMatch 对空集恒真）。"
                        + "cpq_db_test 实查应为 5 个（AC 原文写的 6 是 cpq_db_0724 的数）");

        Response ex = exportDirectory(baseline);
        assertEquals(200, ex.statusCode(), "导出基准目录应 200，body=" + ex.asString());
        JsonNode bundle = M.readTree(ex.asString());

        UUID target = createDirectory("AC12BASE");
        Response pv = preview(target, bundle.toString(), "?conflictPolicy=RENAME");
        assertEquals(200, pv.statusCode(), "预览应 200，body=" + pv.asString());
        JsonNode data = M.readTree(pv.asString()).path("data");

        long resolved = 0;
        long notBuilder = 0;
        List<String> unresolvable = new java.util.ArrayList<>();
        for (JsonNode c : data.path("components")) {
            String st = c.path("builderCoord").path("status").asText("<缺失>");
            switch (st) {
                case "RESOLVED" -> resolved++;
                case "NOT_BUILDER" -> notBuilder++;
                case "UNRESOLVABLE" -> unresolvable.add(
                        c.path("code").asText() + ":" + c.path("builderCoord").path("message").asText());
                default -> throw new AssertionError(
                        "组件 " + c.path("code").asText() + " 的 builderCoord.status 非法/缺失: " + st);
            }
        }
        System.out.println("[AC-12-b] builder 组件(DB现算)=" + builderComponents
                + " 预览 RESOLVED=" + resolved + " NOT_BUILDER=" + notBuilder
                + " UNRESOLVABLE=" + unresolvable);

        assertTrue(unresolvable.isEmpty(),
                "AC-12：基准目录的 builder 组件应全部 RESOLVED，以下不可解析: " + unresolvable
                        + "\n → 这些坐标在 cpq_db_test 的 semantic_tab_view 里实查是存在的（BOM/主件/材质元素/"
                        + "费用类·INCOMING_OTHER_FEE，均 QUOTE 且 ACTIVE），出现 UNRESOLVABLE 要查解析逻辑，"
                        + "不要改断言");
        assertEquals(builderComponents, resolved,
                "AC-12：RESOLVED 的个数应等于 DB 现算的 builder 组件数 " + builderComponents
                        + "，实际 " + resolved);

        assertEquals(0L, componentCountIn(target), "预览必须只读，目标目录应仍为 0 个组件");
    }
}
