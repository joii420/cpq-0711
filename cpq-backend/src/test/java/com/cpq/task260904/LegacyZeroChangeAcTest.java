package com.cpq.task260904;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TC-13 / TC-14 / TC-15 / TC-25 —— <b>存量零变化</b>。
 *
 * <p>覆盖 <b>AC-13（历史冻结单据零回归）· AC-14（27 个含树模板的渲染路由不变）·
 * AC-15（存量未配组件不受影响）· AC-25（存量 109 个组件行为零变化）</b>。
 *
 * <h3>🚨 基线在改动前采集，🚫 不许重采</h3>
 * 见 {@code dev-docs/task-260904-页签类型收缩/证据/baseline/README.md}：
 * 采集时分支 == master（{@code git rev-list --count master..HEAD} = 0，工作区干净），
 * <b>实现一行都还没写</b>。改完才想起取基线就没有对照了（test.md §3）。
 *
 * <h3>🚨 共享库并发写入 ⇒ 失败必须先做 A/B 归因</h3>
 * 立项当日实测同一天内 {@code ds_quote_material} 45→71。本类的失败信息一律打印
 * <b>具体差异行</b>，并提示先对照干净 master 归因，🚫 不许直接归因「本次引入」（test.md §4 / R-9）。
 *
 * <h3>🚨 「删除」也在盯（2026-09-05 补的覆盖缺口）</h3>
 * {@link #ac25_legacyTabTypeNotRewritten} 原来只遍历「当前行」去查基线 ——
 * 组件被删掉时那一行压根不进循环，用例照样绿。现已补上反方向断言：
 * 基线里有 {@code tab_type} 而当前不存在 ⇒ 硬失败，并在信息里给出归因顺序
 * （先排除「别的会话的测试残留被清理」，再考虑「本次改动弄坏了存量」）。
 *
 * <h3>为什么用两个 md5，不是一个</h3>
 * {@code treeOrderMd5}（{@code __nodeId} 序列的 md5）贴的是 AC-25② 的「<b>树行数与树序</b>逐字相同」；
 * 整段 {@code md5(snapshot_rows)} 会被任何一个业务值变化打破，分不清「树结构变了」还是
 * 「某个单价被别的会话改了」。两个都比，失败时对照读。
 */
@QuarkusTest
@DisplayName("task-260904 · AC-13/14/15/25 —— 存量零变化（对照改动前基线逐字比对）")
class LegacyZeroChangeAcTest extends Task260904Base {

    /**
     * <b>已登记豁免的「消失项」</b> —— 基线里有 {@code tab_type}、后来被<b>别的会话</b>删掉的组件。
     *
     * <p>2026-09-05 实证：{@code task-260819} 清理自己的 {@code SQLVB-TEST-*} 测试残留时，
     * 连带删掉了 55 个在本任务基线里的组件，其中 <b>2 个带 {@code tab_type}</b>：
     * <ul>
     *   <li>{@code 46b02fae-…} / {@code COMP-0785} / 材质元素 —— 删除前实测其 name 为
     *       {@code SQLVB-TEST-fee-846f3056-…}（2026-09-03 建），<b>直接确证</b>是 260819 的测试产物；</li>
     *   <li>{@code 2214b8f7-…} / {@code COMP-0347} / 材质元素 —— ⚠️ 删除时已不在库里，
     *       <b>无法读回 name 直接确证</b>。旁证：它在 {@code template_component} /
     *       {@code template_component_snapshot} / {@code quotation_line_component_data}
     *       三张表里引用数<b>均为 0</b>（与 COMP-0785 完全同型），即从未被任何模板或单据用过。</li>
     * </ul>
     *
     * <p>🚫 <b>这个白名单只装「已查明来源的两个 id」，不是放宽判据</b>：
     * 任何别的存量组件消失仍然硬失败。加新条目必须附上同等强度的来源证据。
     */
    private static final java.util.Set<String> KNOWN_RESIDUE_DELETIONS = java.util.Set.of(
            "2214b8f7-623c-4f7b-9bd9-bbbeeec1a3b3",   // COMP-0347（材质元素）
            "46b02fae-6ccb-449e-95bc-8545d665f88c");  // COMP-0785（材质元素，= SQLVB-TEST-fee-846f3056）

    /** 基线一行：component_data 的一条记录。 */
    private record Baseline(String lineItemId, String componentId, String tabType, String rowCount,
                            String treeOrderMd5, String snapshotMd5, String rowDataMd5) {
    }

    // ═══════════════════════ 基线读取 ═══════════════════════

    private Map<String, Baseline> loadComponentDataBaseline() throws IOException {
        File f = locateBaseline("baseline-component-data.tsv.gz");
        assertNotNull(f, "改动前基线 baseline-component-data.tsv.gz 找不到 ⇒ 本类的全部断言都没有对照物。"
                + "这是 harness 故障，不是 AC 结论。基线目录见 证据/baseline/README.md");
        Map<String, Baseline> m = new LinkedHashMap<>();
        try (InputStream in = new GZIPInputStream(new FileInputStream(f));
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] c = line.split("\\|", -1);
                if (c.length < 8) continue;
                m.put(c[0], new Baseline(c[1], c[2], c[3], c[4], c[5], c[6], c[7]));
            }
        }
        // 🚨 阳性对照：基线读空的话，下面所有「逐字相同」都会以 0 条比对通过（纯空验证）
        assertTrue(m.size() > 1000, "基线只读到 " + m.size() + " 条 ⇒ 读取器没生效，此时全绿是空验证。");
        System.out.println("[baseline] component_data 基线 " + m.size() + " 条，采集时间="
                + readCapturedAt());
        return m;
    }

    /** psql 的 {@code f/t} 与 JDBC 的 {@code false/true} 归一到 {@code true/false}。 */
    private static String normBool(String v) {
        if (v == null) return "(null)";
        String t = v.trim().toLowerCase();
        if ("t".equals(t) || "true".equals(t)) return "true";
        if ("f".equals(t) || "false".equals(t)) return "false";
        return t;
    }

    private String readCapturedAt() {
        File f = locateBaseline("baseline-captured-at.txt");
        if (f == null) return "(未知)";
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "(读取失败)";
        }
    }

    /** 当前库里的同一批列（口径与基线采集 SQL 逐字一致）。 */
    private Map<String, Baseline> currentComponentData(String tabTypeFilter) {
        String where = tabTypeFilter == null ? "" : " AND c.tab_type " + tabTypeFilter;
        List<Object[]> rs = rows("SELECT d.id::text, d.line_item_id::text, d.component_id::text, "
                + " COALESCE(c.tab_type,'(null)'), COALESCE(jsonb_array_length(d.snapshot_rows),-1)::text, "
                + " CASE WHEN c.tab_type='BOM' THEN COALESCE(md5((SELECT string_agg(e->>'__nodeId','>' ORDER BY ord) "
                + "   FROM jsonb_array_elements(d.snapshot_rows) WITH ORDINALITY t(e,ord))),'(null)') ELSE '(n/a)' END, "
                + " COALESCE(md5(d.snapshot_rows::text),'(null)'), COALESCE(md5(d.row_data::text),'(null)') "
                + "FROM quotation_line_component_data d JOIN component c ON c.id = d.component_id "
                + "WHERE 1=1" + where + " ORDER BY d.id");
        Map<String, Baseline> m = new LinkedHashMap<>();
        for (Object[] r : rs) {
            m.put(String.valueOf(r[0]), new Baseline(String.valueOf(r[1]), String.valueOf(r[2]),
                    String.valueOf(r[3]), String.valueOf(r[4]), String.valueOf(r[5]),
                    String.valueOf(r[6]), String.valueOf(r[7])));
        }
        return m;
    }

    /**
     * 比对一批 component_data 行。
     *
     * @param compareTreeOrder true = 比 rowCount + treeOrderMd5（AC-25② 的「树行数与树序」）
     * @param compareContent   true = 比 md5(snapshot_rows) + md5(row_data)（逐字相同）
     */
    private void diffAndAssert(String acRef, Map<String, Baseline> base, Map<String, Baseline> now,
                               String tabTypeWanted, boolean compareTreeOrder, boolean compareContent) {
        List<String> diffs = new ArrayList<>();
        int compared = 0, missing = 0, added = 0;

        for (Map.Entry<String, Baseline> e : base.entrySet()) {
            Baseline b = e.getValue();
            if (tabTypeWanted != null && !tabTypeWanted.equals(b.tabType())) continue;
            Baseline n = now.get(e.getKey());
            if (n == null) { missing++; continue; }
            compared++;
            if (compareTreeOrder) {
                if (!b.rowCount().equals(n.rowCount())) {
                    diffs.add("id=" + e.getKey() + " 树行数 " + b.rowCount() + " → " + n.rowCount());
                } else if (!b.treeOrderMd5().equals(n.treeOrderMd5())) {
                    diffs.add("id=" + e.getKey() + " 树序 md5 " + b.treeOrderMd5() + " → " + n.treeOrderMd5());
                }
            }
            if (compareContent) {
                if (!b.snapshotMd5().equals(n.snapshotMd5())) {
                    diffs.add("id=" + e.getKey() + " snapshot_rows md5 " + b.snapshotMd5() + " → " + n.snapshotMd5());
                }
                if (!b.rowDataMd5().equals(n.rowDataMd5())) {
                    diffs.add("id=" + e.getKey() + " row_data md5 " + b.rowDataMd5() + " → " + n.rowDataMd5());
                }
            }
        }
        for (String id : now.keySet()) {
            Baseline n = now.get(id);
            if (tabTypeWanted != null && !tabTypeWanted.equals(n.tabType())) continue;
            if (!base.containsKey(id)) added++;
        }

        System.out.println("[" + acRef + "] tab_type=" + tabTypeWanted + " 比对 " + compared + " 行；"
                + "基线有而现在没有 " + missing + " 行；现在有而基线没有 " + added + " 行；差异 " + diffs.size() + " 处");
        assertTrue(compared > 0, acRef + "：一行都没比到 ⇒ 断言从未执行（假绿）。"
                + "基线里 tab_type=" + tabTypeWanted + " 的行数=" + base.values().stream()
                .filter(b -> tabTypeWanted == null || tabTypeWanted.equals(b.tabType())).count());

        if (!diffs.isEmpty()) {
            fail(acRef + "：与改动前基线不一致，共 " + diffs.size() + " 处（比对 " + compared + " 行）。"
                    + "\n⚠️ 归因纪律（test.md R-9）：共享库有并发写入，🚫 不许直接归因『本次引入』——"
                    + "请先对照干净 master 跑同一比对做 A/B。"
                    + "\n基线采集于 " + readCapturedAt()
                    + "\n差异明细（最多列 40 条）：\n  " + String.join("\n  ", diffs.subList(0, Math.min(40, diffs.size()))));
        }
    }

    // ═══════════════════════ AC-25 ═══════════════════════

    /**
     * <b>AC-25②</b>：21 个 BOM 树组件<b>仍走树渲染管线</b>（双判据走分支②回退 {@code tab_type='BOM'}），
     * 树行数与树序与改动前<b>逐字相同</b>。
     */
    @Test
    @DisplayName("AC-25②：21 个存量 BOM 树组件的树行数与树序逐字不变")
    void ac25_legacyTreeComponentsUnchanged() throws IOException {
        Map<String, Baseline> base = loadComponentDataBaseline();
        Map<String, Baseline> now = currentComponentData("= 'BOM'");
        diffAndAssert("AC-25②", base, now, "BOM", true, true);
    }

    /** <b>AC-25③</b>：零件 / 外购件组件的渲染结果与改动前逐字相同。 */
    @Test
    @DisplayName("AC-25③：存量零件 / 外购件组件的渲染结果逐字不变")
    void ac25_legacyPartAndOutsourcedUnchanged() throws IOException {
        Map<String, Baseline> base = loadComponentDataBaseline();
        Map<String, Baseline> now = currentComponentData("IN ('零件','外购件')");
        diffAndAssert("AC-25③(零件)", base, now, "零件", false, true);
        diffAndAssert("AC-25③(外购件)", base, now, "外购件", false, true);
    }

    /**
     * <b>AC-25④</b>：这 109 个组件的 {@code tab_type} 值<b>一个都没被改写</b>；
     * 同时核对 {@code bom_recursive_expand}（AC-21 的写入源改了，🚫 但不得顺手改存量的值）。
     */
    @Test
    @DisplayName("AC-25④：存量组件的 tab_type / bom_recursive_expand 一个都没被改写")
    void ac25_legacyTabTypeNotRewritten() throws IOException {
        File f = locateBaseline("baseline-component-tabtype.tsv");
        assertNotNull(f, "改动前基线 baseline-component-tabtype.tsv 找不到");
        Map<String, String[]> base = new LinkedHashMap<>();
        for (String line : java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] c = line.split("\\|", -1);
            if (c.length < 4) continue;
            base.put(c[0], new String[]{c[1], c[2], c[3]});
        }
        assertTrue(base.size() > 100, "tab_type 基线只读到 " + base.size() + " 条 ⇒ 空验证");

        List<String> diffs = new ArrayList<>();
        int compared = 0;
        java.util.Set<String> stillPresent = new java.util.HashSet<>();
        for (Object[] r : rows("SELECT id::text, code, COALESCE(tab_type,'(null)'), bom_recursive_expand::text FROM component")) {
            String[] b = base.get(String.valueOf(r[0]));
            if (b == null) continue;   // 本次或别的会话新建的组件，不在基线里
            stillPresent.add(String.valueOf(r[0]));
            compared++;
            if (!b[1].equals(String.valueOf(r[2]))) {
                diffs.add("component " + r[1] + " tab_type " + b[1] + " → " + r[2]);
            }
            // 🚨 口径归一：基线是 psql -At 导出的 f/t，JDBC 侧是 false/true。
            //    不归一的话 223 个组件会全部"差异"，而那个红长得和真回归一模一样。
            if (!normBool(b[2]).equals(normBool(String.valueOf(r[3])))) {
                diffs.add("component " + r[1] + " bom_recursive_expand " + b[2] + " → " + r[3]);
            }
        }
        // 🚨 反方向：基线里有、现在没了 —— 「删除」也是变化，AC-25 说的是**行为零变化**。
        //    2026-09-05 实测本用例的覆盖缺口：原来只遍历「当前行」去查基线，
        //    组件被删掉时那一行压根不进循环，用例照样绿（我自己在 test-report 里报过这条）。
        List<String> vanished = new ArrayList<>();
        List<String> vanishedKnownResidue = new ArrayList<>();
        for (Map.Entry<String, String[]> e : base.entrySet()) {
            if ("(null)".equals(e.getValue()[1])) continue;      // 只盯「存量已配组件」
            if (stillPresent.contains(e.getKey())) continue;
            String line = "component " + e.getValue()[0] + "（id=" + e.getKey() + "，基线 tab_type="
                    + e.getValue()[1] + "）在库里已不存在";
            (KNOWN_RESIDUE_DELETIONS.contains(e.getKey()) ? vanishedKnownResidue : vanished).add(line);
        }
        if (!vanishedKnownResidue.isEmpty()) {
            System.out.println("[AC-25④] 已登记豁免的消失项（别的会话的测试残留，非本次改动）："
                    + vanishedKnownResidue);
        }

        System.out.println("[AC-25④] 比对 " + compared + " 个存量组件，差异 " + diffs.size() + " 处；"
                + "基线里有 tab_type 而现已消失 " + vanished.size() + " 个");
        assertTrue(compared > 100, "AC-25④：只比到 " + compared + " 个组件 ⇒ 断言几乎没执行");
        assertTrue(diffs.isEmpty(), "AC-25④：存量组件的 tab_type / bom_recursive_expand 被改写了（§1.35 硬约束③："
                + "列永久保留、新组件不再写它、存量继续读）。差异：\n  " + String.join("\n  ", diffs));
        assertTrue(vanished.isEmpty(),
                "AC-25④：基线里的存量已配组件有 " + vanished.size() + " 个在库里消失了 —— **删除也是变化**：\n  "
                        + String.join("\n  ", vanished)
                        + "\n⚠️ 归因顺序：① 先确认是不是**别的会话的测试残留**被清理了"
                        + "（2026-09-05 实测 task-260819 清 SQLVB-TEST-* 时连带删掉 55 个基线组件，其中 2 个带 tab_type，"
                        + "那两个已登记在 KNOWN_RESIDUE_DELETIONS 里并附了来源证据）；"
                        + "② 排除之后才考虑「本次改动删了存量组件」——那属于弄坏存量，必须停下报告。"
                        + "\n🚫 不要靠往 KNOWN_RESIDUE_DELETIONS 里加 id 把红消掉，除非能给出同等强度的来源证据。");
    }

    /**
     * <b>AC-25①</b>：109 个存量组件<b>全部能正常打开</b>「取数配置」Tab，不出现 404、不白屏 ——
     * 因为 {@code semantic_tab_view} 45 行全部保持 ACTIVE（S-4）。
     *
     * <p>服务端可观测化：逐个 {@code GET /components/{id}} 应 200，
     * 且以该组件的 {@code tab_type} 调 {@code GET /config/semantic-graph/field-tree} 不得 404
     * （api.md §1.4 的 {@code COMPILE_TABVIEW_NOT_FOUND} 正是「打开配置页 404」的成因）。
     */
    @Test
    @DisplayName("AC-25①：109 个存量组件逐个能打开 + semantic_tab_view 45 行全部保持 ACTIVE")
    void ac25_allLegacyComponentsStillOpenable() {
        List<Object[]> legacy = rows("SELECT id::text, code, tab_type FROM component WHERE tab_type IS NOT NULL ORDER BY code");
        assertFalse(legacy.isEmpty(), "前置未满足：库里一个有 tab_type 的存量组件都没有 ⇒ AC-25① 会空跑");
        System.out.println("[AC-25①] 存量已配组件 " + legacy.size() + " 个（立项时实测 109）");

        List<String> failures = new ArrayList<>();
        for (Object[] r : legacy) {
            String id = String.valueOf(r[0]);
            String code = String.valueOf(r[1]);
            Response detail = given().get("/api/cpq/components/" + id).thenReturn();
            assertReachedBusinessLayer(detail, "AC-25① 打开组件 " + code);
            if (detail.statusCode() != 200) {
                failures.add(code + " GET /components/" + id + " → " + detail.statusCode() + " " + detail.asString());
            }
        }
        assertTrue(failures.isEmpty(), "AC-25①：存量组件详情打不开：\n  " + String.join("\n  ", failures));

        // S-4 的直接断言：45 行一行不动、全部保持 ACTIVE（停用会让存量组件打开配置页 404）
        long total = count("SELECT count(*) FROM semantic_tab_view");
        long inactive = count("SELECT count(*) FROM semantic_tab_view WHERE status <> 'ACTIVE'");
        System.out.println("[AC-25①] semantic_tab_view 共 " + total + " 行，非 ACTIVE " + inactive + " 行");
        assertTrue(total >= 45, "AC-25①：semantic_tab_view 只剩 " + total + " 行（立项实测 45）⇒ 有行被删了");
        assertEquals(0L, inactive,
                "AC-25①：semantic_tab_view 有 " + inactive + " 行被置成非 ACTIVE。S-4（v3 改法）要求"
                        + "「45 行一行不动、全部保持 ACTIVE」—— 停用会让存量的 30 个零件/外购件组件打开配置页 404。");

        // 每个 ACTIVE 页签视图的取数入口都要可达（field-tree 不得 404）
        // ⚠️ 用 semantic_tab_view 自己的 tab_type 词表探，🚫 不用 component.tab_type ——
        //    两张表的词表并不相同（component 是 'BOM'，semantic_tab_view 是 'BOM 树'），
        //    拿 component 的值去探会得到一个与 S-4 无关的 404（2026-09-05 首次真跑实测踩到）。
        List<Object[]> views = rows("SELECT DISTINCT tab_type, COALESCE(variant_key,'') FROM semantic_tab_view "
                + "WHERE dialect = 'QUOTE' AND status = 'ACTIVE' ORDER BY 1,2");
        assertFalse(views.isEmpty(), "AC-25①：QUOTE 方言下一个 ACTIVE 页签视图都没有 ⇒ 断言会空跑");
        List<String> ftFailures = new ArrayList<>();
        for (Object[] v : views) {
            Response ft = given().queryParam("tabType", String.valueOf(v[0]))
                    .queryParam("variantKey", String.valueOf(v[1]))
                    .get("/api/cpq/config/semantic-graph/field-tree").thenReturn();
            assertReachedBusinessLayer(ft, "AC-25① field-tree(" + v[0] + "/" + v[1] + ")");
            if (ft.statusCode() != 200) {
                ftFailures.add(v[0] + "/" + v[1] + " → " + ft.statusCode() + " " + ft.asString());
            }
        }
        System.out.println("[AC-25①] 探过 " + views.size() + " 个 QUOTE 页签视图入口，失败 " + ftFailures.size() + " 个");
        assertTrue(ftFailures.isEmpty(), "AC-25①：以下取数入口打不开：\n  " + String.join("\n  ", ftFailures));
    }

    /**
     * <b>AC-25②（补强）· 存量组件仍全部走分支②</b>。
     *
     * <p>🚨 <b>本条刻意不断言「全库 builder_version 非空 = 0」</b>：那是 2026-09-05 23:34 的一次快照，
     * 当天就被 {@code task-260819} 的测试残留推到了 29 行（材质元素 25 / 费用类 4）。
     * 把移动靶写进断言，红了也说明不了任何事。
     * ⇒ 改成<b>现场探测取值 + 断言分流结果</b>：
     * 「{@code tab_type} 非空<b>且</b> {@code builder_version} 为 NULL」的那批 = 真·存量，
     * 它们必须仍走分支②，即 {@code tab_type='BOM'} 的每一个 {@code bom_recursive_expand} 都是 true、
     * 其余每一个都是 false（需求文档 §4.2⑥ 实证的 1:1 绑定零例外）。
     */
    @Test
    @DisplayName("AC-25②补强：真·存量组件（有 tab_type 且无 builder_version）仍全部走分支②")
    void ac25_legacyComponentsStillOnBranchTwo() {
        long branch1Inputs = count("SELECT count(*) FROM component_sql_view WHERE builder_version IS NOT NULL");
        System.out.println("[AC-25②补强] 当次全库 builder_version 非空行数 = " + branch1Inputs
                + "（🚫 移动靶，只记录不断言；立项时 0，2026-09-05 因 task-260819 测试残留涨到 29）");

        List<Object[]> legacy = rows("SELECT c.code, c.tab_type, c.bom_recursive_expand::text "
                + "FROM component c LEFT JOIN component_sql_view v ON v.component_id = c.id "
                + "WHERE c.tab_type IS NOT NULL AND v.builder_version IS NULL ORDER BY c.code");
        System.out.println("[AC-25②补强] 真·存量已配组件 = " + legacy.size() + " 个"
                + "（立项实测 109；⚠️ 该数字同样是移动靶，只作非空守卫用）");
        // 🚨 非空守卫：这批为空的话，下面的循环 0 次、断言从未执行（四类假绿之首）
        assertTrue(legacy.size() >= 100,
                "AC-25②补强：真·存量组件只剩 " + legacy.size() + " 个（立项实测 109）⇒ 存量集合被清空或判据取错了，"
                        + "此时循环 0 次、断言从未执行。");

        List<String> violations = new ArrayList<>();
        int treeCount = 0;
        for (Object[] r : legacy) {
            String code = String.valueOf(r[0]);
            String tabType = String.valueOf(r[1]);
            boolean expand = "true".equals(normBool(String.valueOf(r[2])));
            if ("BOM".equals(tabType)) {
                treeCount++;
                if (!expand) violations.add(code + " tab_type=BOM 但 bom_recursive_expand=false ⇒ 没走分支②，存量树渲染当场失效");
            } else if (expand) {
                violations.add(code + " tab_type=" + tabType + " 却 bom_recursive_expand=true ⇒ 被误判成树");
            }
        }
        System.out.println("[AC-25②补强] 其中 tab_type='BOM' 的存量树组件 = " + treeCount + " 个（立项实测 21），违例 "
                + violations.size() + " 处");
        // 🚨 双向非空守卫：树组件为 0 的话，「BOM → true」那一侧一次都没跑到
        assertTrue(treeCount > 0, "AC-25②补强：一个存量 BOM 树组件都没有 ⇒ 分支②的树分流从未被验证（空跑）");
        assertTrue(violations.isEmpty(), "AC-25②补强：存量组件的分流结果被改变了（§1.35 硬约束②：存量行为零变化）：\n  "
                + String.join("\n  ", violations));
    }

    // ═══════════════════════ AC-13 ═══════════════════════

    /**
     * <b>AC-13（历史冻结单据零回归）</b>：5 张已用含 BOM 树模板的在途报价单，
     * 导出 {@code snapshot_rows} 与 {@code quote_card_values}，改动前后 <b>md5 逐字相同</b>。
     */
    @Test
    @DisplayName("AC-13：5 张在途单的 snapshot_rows 与 quote_card_values 逐字不变")
    void ac13_inflightQuotationsUnchanged() throws IOException {
        File f = locateBaseline("baseline-inflight-cardvalues.tsv.gz");
        assertNotNull(f, "改动前基线 baseline-inflight-cardvalues.tsv.gz 找不到");

        Map<String, String[]> base = new LinkedHashMap<>();
        try (InputStream in = new GZIPInputStream(new FileInputStream(f));
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] c = line.split("\\|", -1);
                if (c.length < 4) continue;
                base.put(c[0], new String[]{c[1], c[2], c[3]});
            }
        }
        assertTrue(base.size() > 100, "在途单基线只读到 " + base.size() + " 行 ⇒ 空验证");
        System.out.println("[AC-13] 在途单基线 " + base.size() + " 条报价行（5 张单）");

        // 一次查回全部（🚫 不要每行一条 SQL —— 9225 次往返会让本用例跑上好几分钟）
        Map<String, String[]> now = new LinkedHashMap<>();
        for (Object[] r : rows("SELECT li.id::text, COALESCE(md5(li.quote_card_values::text),'(null)'), "
                + "COALESCE(md5(li.costing_card_values::text),'(null)') FROM quotation_line_item li")) {
            now.put(String.valueOf(r[0]), new String[]{String.valueOf(r[1]), String.valueOf(r[2])});
        }

        List<String> diffs = new ArrayList<>();
        int compared = 0, missing = 0;
        for (Map.Entry<String, String[]> e : base.entrySet()) {
            String[] c = now.get(e.getKey());
            if (c == null) { missing++; continue; }
            compared++;
            if (!e.getValue()[1].equals(c[0])) {
                diffs.add("lineItem=" + e.getKey() + "(" + e.getValue()[0] + ") quote_card_values md5 "
                        + e.getValue()[1] + " → " + c[0]);
            }
            if (!e.getValue()[2].equals(c[1])) {
                diffs.add("lineItem=" + e.getKey() + "(" + e.getValue()[0] + ") costing_card_values md5 "
                        + e.getValue()[2] + " → " + c[1]);
            }
        }
        System.out.println("[AC-13] 比对 " + compared + " 条报价行；基线有而现在没有 " + missing + " 条；差异 " + diffs.size() + " 处");
        assertTrue(compared > 100, "AC-13：只比到 " + compared + " 条 ⇒ 断言几乎没执行");
        assertEquals(0, missing, "AC-13：基线里的报价行有 " + missing + " 条在库里消失了 —— 在途单的行被删了？");
        if (!diffs.isEmpty()) {
            fail("AC-13：在途单的冻结值与改动前不一致，共 " + diffs.size() + " 处。"
                    + "\n⚠️ 先做 A/B 归因（共享库有并发写入，可能是别的会话动了这 5 张单）。基线采集于 " + readCapturedAt()
                    + "\n差异明细（最多 40 条）：\n  " + String.join("\n  ", diffs.subList(0, Math.min(40, diffs.size()))));
        }
    }

    // ═══════════════════════ AC-14 ═══════════════════════

    /**
     * <b>AC-14（27 个含树模板的渲染路由不变）</b>：
     * {@code template_component_snapshot.tab_type='BOM'} 的 27 个模板，各取一张单打开，
     * 树页签仍走树渲染（按树序渲染），<b>行数与改动前一致</b>。
     *
     * <p>可观测化：逐模板取其报价行上的树组件 component_data，比对树行数 + 树序 md5；
     * 并核对 {@code template_component_snapshot} 的 {@code tab_type} 快照值一个没变
     * （冻结快照的语义就是「当时的样子」，A0-3）。
     */
    @Test
    @DisplayName("AC-14：含 BOM 树的模板 —— 快照 tab_type 不变 + 其单据的树行数/树序不变")
    void ac14_templatesWithTreeTabUnchanged() throws IOException {
        // ① 冻结快照的 tab_type 一个都没变
        File f = locateBaseline("baseline-template-snapshot-tabtype.tsv");
        assertNotNull(f, "改动前基线 baseline-template-snapshot-tabtype.tsv 找不到");
        Map<String, String> base = new LinkedHashMap<>();
        for (String line : java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] c = line.split("\\|", -1);
            if (c.length < 3) continue;
            base.put(c[0] + "|" + c[1], c[2]);
        }
        assertTrue(base.size() > 50, "模板快照基线只读到 " + base.size() + " 行 ⇒ 空验证");

        long bomTemplates = base.entrySet().stream().filter(e -> "BOM".equals(e.getValue()))
                .map(e -> e.getKey().split("\\|")[0]).distinct().count();
        System.out.println("[AC-14] 基线里含 BOM 树页签的模板数 = " + bomTemplates + "（立项时实测 27）");
        assertTrue(bomTemplates > 0, "AC-14：基线里一个含 BOM 树的模板都没有 ⇒ 断言会空跑");

        List<String> diffs = new ArrayList<>();
        int compared = 0;
        for (Object[] r : rows("SELECT template_id::text, component_id::text, COALESCE(tab_type,'(null)') "
                + "FROM template_component_snapshot")) {
            String k = r[0] + "|" + r[1];
            String b = base.get(k);
            if (b == null) continue;
            compared++;
            if (!b.equals(String.valueOf(r[2]))) {
                diffs.add("template=" + r[0] + " component=" + r[1] + " 快照 tab_type " + b + " → " + r[2]);
            }
        }
        assertTrue(compared > 50, "AC-14：只比到 " + compared + " 行模板快照 ⇒ 断言几乎没执行");
        assertTrue(diffs.isEmpty(), "AC-14：template_component_snapshot 的 tab_type 冻结值被改写了（A0-3：114 行历史值保留）。"
                + "差异：\n  " + String.join("\n  ", diffs));

        // ② 这些模板下的单据，树行数与树序不变（与 AC-25② 同源基线，此处按模板维度再核一遍）
        Map<String, Baseline> cdBase = loadComponentDataBaseline();
        Map<String, Baseline> cdNow = currentComponentData("= 'BOM'");
        diffAndAssert("AC-14②", cdBase, cdNow, "BOM", true, false);
    }

    // ═══════════════════════ AC-15 ═══════════════════════

    /**
     * <b>AC-15（存量未配组件不受影响）</b>：114 个 {@code tab_type} 为空的存量组件，
     * 其所属模板的报价单渲染逐字不变；组件保存时不因缺页签类型而报错。
     *
     * <p>「组件保存不因缺页签类型报错」用<b>自建的空 tabType 组件</b>验证（{@code PUT /components/{id}}
     * 只带 fields，不带 tabType）—— 🚫 不去存盘一个存量组件：那会改动共享库里别人的数据（testing.md §4.3）。
     */
    @Test
    @DisplayName("AC-15：未配 tab_type 的存量组件渲染逐字不变 + 新组件缺 tabType 仍可保存")
    void ac15_unconfiguredComponentsUnaffected() throws IOException {
        Map<String, Baseline> base = loadComponentDataBaseline();
        Map<String, Baseline> now = currentComponentData("IS NULL");
        diffAndAssert("AC-15①", base, now, "(null)", false, true);

        // ② 保存期校验：不带 tabType 的组件应能正常保存（B-11 调整后不得反过来变成必填）
        java.util.UUID id = createBlankComponent("A15-SAVE");
        Response save = given().contentType(io.restassured.http.ContentType.JSON)
                .body("{\"fields\":[{\"name\":\"" + PREFIX + "手填列\",\"field_type\":\"INPUT_TEXT\"}]}")
                .put("/api/cpq/components/" + id).thenReturn();
        assertReachedBusinessLayer(save, "AC-15②");
        assertEquals(200, save.statusCode(),
                "AC-15②：缺页签类型的组件保存不应报错，实际=" + save.statusCode() + " body=" + save.asString());
        assertEquals(null, scalar("SELECT tab_type FROM component WHERE id = '" + id + "'"),
                "AC-15②：保存不应给组件凭空补一个 tab_type");
    }
}
