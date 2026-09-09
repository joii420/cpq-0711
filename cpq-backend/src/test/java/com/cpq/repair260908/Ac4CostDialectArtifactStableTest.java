package com.cpq.repair260908;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-4</b>（无副作用）—— 问题说明 ⑥ 节原文：
 *
 * <blockquote>
 * 改动前后各编译一次，{@code COST_BASIC} + {@code COST_DETAIL} 两方言全部页签，
 * 编译产物 <b>逐字节相同</b>（{@code md5sum} 背靠背比对，diff 为空）。
 * </blockquote>
 *
 * <h3>🔴 已发现的 AC 冲突（报主线，🚫 我不自行改 AC）</h3>
 * <b>AC-4 与 AC-16 / B-1b 直接矛盾。</b>
 * B-1b 明写要给 3 个 {@code COST_BASIC} 视图的 {@code NARROW} 桥子查询追加
 * {@code AND <桥别名>.customer_no = :customerCode}，那 3 个产物<b>按设计就会变</b>。
 * ⇒ 「COST_BASIC 全部页签逐字节相同」是<b>不可能达成</b>的。
 * <p>本类按<b>可执行的最强读法</b>断言，并把冲突写进失败信息：
 * <ul>
 *   <li>{@code COST_DETAIL}：<b>无条件逐字节相同</b>（该方言没有任何桥引用带客户列的表）；</li>
 *   <li>{@code COST_BASIC}：逐字节相同，<b>唯一允许的差异</b>是桥子查询里新增的那一段客户谓词 ——
 *       用「把新增谓词抹掉后必须与基线逐字节相同」来断言，任何其他字节变化仍然报红。</li>
 * </ul>
 *
 * <h3>🚨 判红之前必须先排除并发写入（2026-09-08 主线交办）</h3>
 * {@code SemanticGraphLoader} 读 {@code semantic_node_column} <b>无 ORDER BY</b>，编译器按 PG 堆顺序遍历
 * ⇒ 任何对该表的 UPDATE 都会重排字段、改变产物 md5。实测 V433 只 UPDATE 了 11 行 {@code roles}，
 * 就让 11 个核价数据源字段错位。本类在<b>取基线前</b>与<b>比对后</b>各取一次
 * {@link #graphFingerprint()}，不一致就按<b>并发写入</b>归因并要求重跑，
 * 🚫 <b>不得报成「核价回归」</b>。
 * <p>⚠️ 残余风险（不许当成已消除）：PG 的 {@code synchronize_seqscans} 可能让顺序扫从中途页开始 ⇒
 * 指纹一致时两次 {@code listAll()} 仍可能不同序。<b>根治只能靠实现侧补 ORDER BY</b>（并发线在做）。
 * 在那之前，AC-4 的「逐字节」本质上是一个<b>不稳定判据</b>，本类会在失败信息里点名这一点。
 *
 * <h3>基线从哪来（两条腿，缺一条就判「未验证」，🚫 不许空对空）</h3>
 * <ol>
 *   <li><b>实测腿</b>：库里 3 个 {@code COST_BASIC} 的 {@code builder_*} 视图，
 *       基线 = 现存 {@code sql_template}（B-6 尚未跑，故它就是「改动前」产物）。
 *       附<b>基线新鲜度守卫</b>：若它已含客户谓词，说明 B-6 已经跑过，基线不再是「改动前」⇒ 硬失败。</li>
 *   <li><b>归档腿</b>：库里<b>一个 COST_DETAIL 的 builder_* 视图都没有</b>（2026-09-08 实测：
 *       28 个 = QUOTE 25 + COST_BASIC 3）。若只用实测腿，COST_DETAIL 覆盖 = <b>0</b>，
 *       断言空跑。⇒ 用 {@link #baselineDir()} 下的归档基线，
 *       由 {@code -Dcpq.s1.capture=true} 在<b>改动前</b>的代码上跑一次生成。
 *       归档缺失 ⇒ 硬失败判「未验证」，🚫 不许当通过。</li>
 * </ol>
 */
@QuarkusTest
class Ac4CostDialectArtifactStableTest extends S1CompileTestBase {

    private static final String CAPTURE_FLAG = "cpq.s1.capture";
    private static final String MANIFEST = "_manifest.txt";

    /** B-1b 允许出现的<b>唯一</b>新增片段形态（用于把它抹掉后再比对）。 */
    private static final java.util.regex.Pattern ALLOWED_BRIDGE_DELTA = java.util.regex.Pattern.compile(
            "\\s+AND\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\.\\s*customer_no\\s*=\\s*:customerCode");

    // ═══════════════ 量具自证：diff 比较器（test.md §3 强制） ═══════════════

    @Test
    @DisplayName("量具自证: md5 比较器先证会报红、再证会报空 —— 🚫 不许拿两个空串比出『相同』")
    void gauge_md5ComparatorRedThenGreen() {
        List<ViewRow> cost = costBasicViews();
        assertFalse(cost.isEmpty(), notReady("AC-4/量具",
                "库里找不到 COST_BASIC 的 builder_* 视图 —— 比较器没有作用对象", "取数配置器"));
        String base = cost.get(0).storedSql();
        assertNotNull(base, "量具: 基线 sql_template 为 null");
        assertFalse(base.isBlank(), "量具🚨: 基线为空串 —— 空对空恒相等，是零证据不是弱证据");

        assertEquals(md5(base), md5(base), "量具: 同一段文本两次 md5 应相等");
        String mutated = base.replaceFirst("SELECT", "SELECT ");   // 只多一个空格
        assertTrue(!mutated.equals(base), "量具: 变异体没生成");
        assertTrue(!md5(base).equals(md5(mutated)),
                "量具: 只差一个空格，md5 就必须不同 —— 比较器认不出 1 字节差异就不能用");
        System.out.println("[量具] 基线 md5=" + md5(base) + "，改 1 字节后 md5=" + md5(mutated));
    }

    // ═══════════════ AC-4 · 实测腿（COST_BASIC 的 3 个真实视图） ═══════════════

    @Test
    @DisplayName("AC-4①: COST_BASIC 3 个真实视图 —— 抹掉 B-1b 新增的桥客户谓词后，必须与库里基线逐字节相同")
    void ac4a_costBasicOnlyBridgeDeltaAllowed() {
        String fp0 = graphFingerprint();
        List<ViewRow> cost = costBasicViews();
        assertEquals(AC16_VIEWS.size(), cost.size(),
                "AC-4①: 应恰有 " + AC16_VIEWS.size() + " 个 COST_BASIC 的 builder_* 视图（AC-16 点名的那 3 个），实得="
                        + cost.stream().map(ViewRow::viewName).toList()
                        + "\n  ⚠️ 数量变了说明并发线动过 component_sql_view，先弄清再判。");

        List<String> err = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        for (ViewRow v : cost) {
            String baseline = v.storedSql();
            assertNotNull(baseline, notReady("AC-4①", v.viewName() + " 的 sql_template 为空", null));

            // 🚨 基线新鲜度守卫：基线必须还是「改动前」的
            assertFalse(baseline.contains(":customerCode"), notReady("AC-4①",
                    v.viewName() + " 的库内 sql_template 已含 :customerCode ⇒ B-6 已经跑过，"
                            + "它不再是『改动前』基线。"
                            + "\n  🚫 拿改动后的基线比改动后的产物，必然全绿 —— 那是假绿，不是通过。"
                            + "\n  处置：改用归档基线（-D" + CAPTURE_FLAG + "=true 需在 B-6 之前跑），或本条判『未验证』。",
                    null));

            String now = recompile(v);
            String stripped = ALLOWED_BRIDGE_DELTA.matcher(now).replaceAll("");
            report.put(v.viewName(), "基线 md5=" + md5(baseline) + " / 抹除后 md5=" + md5(stripped)
                    + " / 现产物 md5=" + md5(now));
            if (!md5(baseline).equals(md5(stripped))) {
                err.add("\n  " + v.viewName() + ": 抹掉桥客户谓词后仍与基线不同 ⇒ 出现了 B-1b 之外的产物变化。"
                        + "\n    基线=\n" + baseline + "\n    现产物=\n" + now
                        + "\n    抹除后=\n" + stripped);
            }
            // 反向：现产物必须真的与基线不同（3 个视图都有桥，B-1b 后必然变）——
            // 若完全没变，说明 B-1b 没生效，而 AC-16 会独立报红；这里只提示，不重复判红。
            if (md5(baseline).equals(md5(now))) {
                System.out.println("[AC-4①] ⚠️ " + v.viewName() + " 产物与基线完全一致 ⇒ B-1b 可能未生效，"
                        + "以 Ac16CostBasicBridgeTest 的结论为准");
            }
        }
        dump("AC-4① COST_BASIC md5 对照", report);
        assertGraphStable("AC-4①", fp0, "COST_BASIC 3 个视图");
        assertEquals("", String.join("", err), "AC-4① 不符：" + String.join("", err)
                + "\n\n  🔴 报主线：AC-4 原文写「COST_BASIC 全部页签逐字节相同」，"
                + "与 AC-16/B-1b「桥子查询要加客户谓词」直接矛盾。本条按『唯一允许差异 = 桥谓词』断言。");
    }

    // ═══════════════ AC-4 · 归档腿（COST_DETAIL 覆盖，否则空跑） ═══════════════

    @Test
    @DisplayName("AC-4②: COST_BASIC + COST_DETAIL 全部页签（含库里没有 builder_* 的 COST_DETAIL）与归档基线逐字节比对")
    void ac4b_bothCostDialectsAgainstArchivedBaseline() {
        String fp0 = graphFingerprint();
        List<Object[]> tabs = costTabViews();
        assertFalse(tabs.isEmpty(), notReady("AC-4②",
                "semantic_tab_view 里没有 COST_BASIC / COST_DETAIL 的 ACTIVE 页签 —— 断言会空跑", null));

        boolean capture = Boolean.getBoolean(CAPTURE_FLAG);
        Path dir = baselineDir();
        Map<String, String> now = new LinkedHashMap<>();
        List<String> detailCovered = new ArrayList<>();

        String componentId = pickAnyComponentId();
        for (Object[] t : tabs) {
            String dialect = String.valueOf(t[0]);
            String tabType = String.valueOf(t[1]);
            String variant = t[2] == null ? "" : String.valueOf(t[2]);
            String cfg = synthConfig(dialect, tabType, variant, String.valueOf(t[3]));
            if (cfg == null) {
                continue;   // 该页签锚点无列声明，跳过并在下方用覆盖数兜底
            }
            Response r = RestAssured.given().cookie("CPQ_SESSION", session())
                    .contentType(ContentType.JSON).body(cfg)
                    .when().post("/api/cpq/components/{cid}/builder/compile", componentId);
            assertEquals(200, r.statusCode(), notReady("AC-4②",
                    "合成配置编译 " + dialect + "/" + tabType + "/" + variant + " 返 " + r.statusCode()
                            + "，body=" + trunc(r.asString()), null));
            String sql = r.jsonPath().getString("sql");
            assertNotNull(sql, "AC-4②: 编译响应缺 sql。body=" + trunc(r.asString()));
            String key = dialect + "__" + tabType + "__" + (variant.isEmpty() ? "-" : variant);
            now.put(key, sql);
            if ("COST_DETAIL".equals(dialect)) {
                detailCovered.add(key);
            }
        }

        // 🚨 空跑防护：COST_DETAIL 必须真的被编译到，否则 AC-4 的「两方言」只兑现了一半
        assertFalse(detailCovered.isEmpty(), notReady("AC-4②",
                "一个 COST_DETAIL 页签都没编译成功 —— AC-4 的『两方言』只覆盖了 COST_BASIC，"
                        + "另一半是空跑。库里没有 COST_DETAIL 的 builder_* 视图，本条正是为补这个洞而设。", null));
        System.out.println("[AC-4②] 本轮编译 " + now.size() + " 个页签，其中 COST_DETAIL "
                + detailCovered.size() + " 个：" + detailCovered);

        if (capture) {
            writeBaseline(dir, now, fp0);
            System.out.println("[AC-4② capture] 基线已写入 " + dir + "（" + now.size() + " 个产物）");
            System.out.println("  ⚠️ capture 模式只采集不比对 —— 本条本轮判『未验证』，"
                    + "请在改动后以默认模式再跑一次才算验过。");
            assertTrue(false, notReady("AC-4②",
                    "本轮以 -D" + CAPTURE_FLAG + "=true 运行，只采集了基线、没有做比对", null));
        }

        assertTrue(Files.isDirectory(dir), notReady("AC-4②",
                "归档基线目录不存在：" + dir
                        + "\n  采集方式：在**改动前**的代码上跑 `./mvnw test -Dtest=Ac4CostDialectArtifactStableTest -D"
                        + CAPTURE_FLAG + "=true`"
                        + "\n  🚫 没有基线时本条一律判『未验证』——"
                        + "绝不允许退化成「没有基线 ⇒ 没有差异 ⇒ 通过」（那正是两个空文件比出相同的翻版）。", null));

        Map<String, String> baseline = readBaseline(dir);
        assertFalse(baseline.isEmpty(), notReady("AC-4②",
                "归档基线目录 " + dir + " 里一个产物文件都没有 —— 空对空的比对是零证据", null));

        String baseFp = baseline.remove(MANIFEST);
        assertNotNull(baseFp, notReady("AC-4②", "归档基线缺 " + MANIFEST + "（语义图指纹），无法判断基线是否可比", null));
        if (!baseFp.strip().equals(fp0.strip())) {
            assertTrue(false, concurrentGraphWrite("AC-4②", baseFp.strip(), fp0.strip(),
                    "基线是在另一份语义图状态下采集的 ⇒ 逐字节比对本来就不该相等。请重新采集基线。"));
        }

        List<String> err = new ArrayList<>();
        for (Map.Entry<String, String> e : baseline.entrySet()) {
            String cur = now.get(e.getKey());
            if (cur == null) {
                err.add("\n  " + e.getKey() + ": 基线里有、本轮没编出来 —— 页签消失或编译失败");
                continue;
            }
            if (!md5(e.getValue()).equals(md5(cur))) {
                err.add("\n  " + e.getKey() + ": 产物变了。基线 md5=" + md5(e.getValue())
                        + " 现 md5=" + md5(cur)
                        + "\n    基线=\n" + e.getValue() + "\n    现产物=\n" + cur);
            }
        }
        for (String k : now.keySet()) {
            if (!baseline.containsKey(k)) {
                err.add("\n  " + k + ": 本轮有、基线里没有 —— 基线过期，请重新采集");
            }
        }
        assertGraphStable("AC-4②", fp0, "COST_BASIC + COST_DETAIL 全页签");
        assertEquals("", String.join("", err), "AC-4② 核价两方言产物发生变化（共 " + err.size() + " 处）："
                + String.join("", err)
                + "\n\n  ⚠️ 判红前请确认三点："
                + "\n   ① 语义图指纹已核对一致（上面已断言）；"
                + "\n   ② 变化的若是 3 个 COST_BASIC 的桥视图，属 B-1b 设计内，应由 AC-4① 那条判；"
                + "\n   ③ 若只是 SELECT 列顺序变了而内容集合相同 ⇒ 极可能是 semantic_node_column 堆序漂移"
                + "（synchronize_seqscans），归因为并发/实现侧未加 ORDER BY，🚫 不是本次改动的核价回归。");
    }

    // ═══════════════ 证伪设计（test.md §4）═══════════════
    //  · 把 B-1b 的桥谓词注释掉 ⇒ AC-4① 仍绿（它只允许、不强制该差异），
    //    但 Ac16CostBasicBridgeTest 报红 —— 两条分工明确，不重复判。
    //  · 把 B-1 的谓词误发到锚点无 customer_no 的 COST_* 上 ⇒ AC-4① / AC-4② 立刻报红
    //    （产物多出 `dcbm.customer_no = :customerCode`，抹除规则只抹「AND <别名>.customer_no = :customerCode」，
    //     抹完仍与基线不同；且真实执行会报 column does not exist）。
    //  · 人为把归档基线里任一文件改 1 字节 ⇒ AC-4② 报红（比较器活性，已由量具自证覆盖）。

    // ═══════════════ 内部工具 ═══════════════

    private void assertGraphStable(String ac, String before, String scope) {
        String after = graphFingerprint();
        if (!before.equals(after)) {
            assertTrue(false, concurrentGraphWrite(ac, before, after, "作用域=" + scope));
        }
    }

    private List<ViewRow> costBasicViews() {
        return builderViews().stream().filter(v -> "COST_BASIC".equals(v.dialect())).toList();
    }

    /** {@code (dialect, tab_type, variant_key, anchor_node_id)}，仅两个核价方言。 */
    private List<Object[]> costTabViews() {
        return rowList("SELECT dialect, tab_type, coalesce(variant_key,''), anchor_node_id::text "
                + "FROM semantic_tab_view WHERE status='ACTIVE' AND dialect IN ('COST_BASIC','COST_DETAIL') "
                + "ORDER BY dialect, tab_type, coalesce(variant_key,'')");
    }

    /**
     * 合成一份<b>确定性</b>的 builder_config：锚点节点的<b>全部 ACTIVE 列</b>，
     * 按 {@code (sort_order NULLS LAST, db_column)} 排序。
     *
     * <p>🔑 显式列举并排序，是为了让产物<b>不依赖</b> {@code semantic_node_column} 的 PG 堆顺序
     * —— 那正是 V433 造成字段错位的机制。
     */
    private String synthConfig(String dialect, String tabType, String variant, String anchorNodeId) {
        if (anchorNodeId == null || "null".equals(anchorNodeId)) {
            return null;
        }
        List<Object[]> cols = rowList("SELECT c.db_column, n.node_key FROM semantic_node_column c "
                + "JOIN semantic_node n ON n.id = c.node_id "
                + "WHERE c.node_id = CAST(?1 AS uuid) AND c.status='ACTIVE' "
                + "ORDER BY c.sort_order NULLS LAST, c.db_column", anchorNodeId);
        if (cols.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{\"builderVersion\":1,\"dialect\":\"").append(dialect)
                .append("\",\"tabType\":\"").append(esc(tabType)).append("\",\"variantKey\":")
                .append(variant.isEmpty() ? "null" : "\"" + esc(variant) + "\"")
                .append(",\"priceStrategy\":null,\"columns\":[");
        for (int i = 0; i < cols.size(); i++) {
            String db = String.valueOf(cols.get(i)[0]);
            String nodeKey = String.valueOf(cols.get(i)[1]);
            sb.append(i == 0 ? "" : ",")
                    .append("{\"sourceNodeKey\":\"").append(esc(nodeKey))
                    .append("\",\"sourceColumn\":\"").append(esc(db))
                    .append("\",\"fieldName\":\"").append(esc(db))
                    .append("\",\"fieldType\":\"BASIC_DATA\",\"isAmount\":false,\"inSubtotal\":false}");
        }
        return sb.append("]}").toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** compile 端点需要一个 componentId；随便借一个既有的（<b>只读</b>，不改它）。 */
    private String pickAnyComponentId() {
        String id = scalarStr("SELECT component_id::text FROM component_sql_view "
                + "WHERE sql_view_name LIKE 'builder\\_%' ORDER BY sql_view_name LIMIT 1");
        assertNotNull(id, notReady("AC-4②", "找不到任何可借用的 componentId", null));
        return id;
    }

    private static void writeBaseline(Path dir, Map<String, String> artifacts, String fingerprint) {
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, String> e : artifacts.entrySet()) {
                Files.writeString(dir.resolve(e.getKey() + ".sql"), e.getValue(), StandardCharsets.UTF_8);
            }
            Files.writeString(dir.resolve(MANIFEST),
                    "# repair-260908 S-1 · AC-4 归档基线（改动前编译产物）\n"
                            + "# 采集时间: " + java.time.LocalDateTime.now() + "\n"
                            + "# 语义图指纹（semantic_node_column|node|edge|tab_view，按 ctid 排序）:\n"
                            + fingerprint + "\n", StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new AssertionError("写归档基线失败：" + dir, ex);
        }
    }

    /** 读归档基线；{@code _manifest.txt} 以特殊 key 返回指纹行。 */
    private static Map<String, String> readBaseline(Path dir) {
        Map<String, String> out = new LinkedHashMap<>();
        try (var s = Files.list(dir)) {
            for (Path p : s.sorted().toList()) {
                String fn = p.getFileName().toString();
                if (fn.equals(MANIFEST)) {
                    String txt = Files.readString(p, StandardCharsets.UTF_8);
                    String fp = txt.lines().filter(l -> !l.startsWith("#") && !l.isBlank())
                            .findFirst().orElse(null);
                    if (fp != null) {
                        out.put(MANIFEST, fp);
                    }
                } else if (fn.endsWith(".sql")) {
                    out.put(fn.substring(0, fn.length() - 4), Files.readString(p, StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            throw new AssertionError("读归档基线失败：" + dir, e);
        }
        return out;
    }
}
