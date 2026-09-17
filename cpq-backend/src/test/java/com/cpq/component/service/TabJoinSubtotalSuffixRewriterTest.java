package com.cpq.component.service;

import com.cpq.component.dto.ComponentExportBundle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** repair-260916 B-4: pure rewrite rules (问题说明 5.4) + version helpers. No database. */
class TabJoinSubtotalSuffixRewriterTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static ComponentExportBundle bundle(String res) throws Exception {
        try (InputStream is = TabJoinSubtotalSuffixRewriterTest.class.getClassLoader().getResourceAsStream(res)) {
            assertNotNull(is, res);
            return M.readValue(is, ComponentExportBundle.class);
        }
    }

    private static Map<String, Set<String>> subtotalMap(ComponentExportBundle b) {
        Map<String, Set<String>> m = new HashMap<>();
        for (var it : b.components) m.put(it.id, TabJoinSubtotalSuffixRewriter.subtotalColumnsOf(it.fields));
        return m;
    }

    private static List<String> expressions(JsonNode cols) {
        List<String> out = new ArrayList<>();
        cols.forEach(c -> out.add(c.path("expression").asText()));
        return out;
    }

    @Test
    void realLegacyBundles_rewriteEx1Columns() throws Exception {
        for (String res : List.of("repair-260916/bundle-v1.0.json", "repair-260916/bundle-v1.1.json")) {
            ComponentExportBundle b = bundle(res);
            var ex1 = b.components.stream().filter(i -> "COMP-0011".equals(i.code)).findFirst().orElseThrow();
            JsonNode before = ex1.excelColumns.deepCopy();
            assertEquals(List.of("[物料.材料成本]", "[物料.回收成本]", "[产品小计(总计)]"), expressions(before),
                "precondition: fixture holds the legacy text (" + res + ")");

            JsonNode cols = ex1.excelColumns.deepCopy();
            var r = TabJoinSubtotalSuffixRewriter.rewrite(cols, subtotalMap(b));
            System.out.println("[B-4] " + res + " rewritten=" + r.rewrittenColumns + " -> " + expressions(cols));
            assertEquals(2, r.rewrittenColumns);
            assertTrue(r.notices.isEmpty(), r.notices.toString());
            assertEquals(List.of("[物料.材料成本(小计)]", "[物料.回收成本(小计)]", "[产品小计(总计)]"), expressions(cols));

            // only "expression" changed; other keys / order preserved
            for (int i = 0; i < cols.size(); i++) {
                var a = (com.fasterxml.jackson.databind.node.ObjectNode) before.get(i).deepCopy();
                var c = (com.fasterxml.jackson.databind.node.ObjectNode) cols.get(i).deepCopy();
                a.remove("expression"); c.remove("expression");
                assertEquals(a, c, "non-expression keys changed at index " + i);
                assertEquals(new ArrayList<>(iter(before.get(i).fieldNames())), new ArrayList<>(iter(cols.get(i).fieldNames())));
            }

            // idempotent
            var r2 = TabJoinSubtotalSuffixRewriter.rewrite(cols, subtotalMap(b));
            assertEquals(0, r2.rewrittenColumns);
            assertEquals(List.of("[物料.材料成本(小计)]", "[物料.回收成本(小计)]", "[产品小计(总计)]"), expressions(cols));
        }
    }

    private static List<String> iter(Iterator<String> it) {
        List<String> l = new ArrayList<>(); it.forEachRemaining(l::add); return l;
    }

    @Test
    void tabKeyForms_unknownComponent_idx_and_otherSuffixes() throws Exception {
        JsonNode cols = M.readTree("""
          [{"col_key":"c1","source_type":"TAB_JOIN_FORMULA","expression":"[A.金额] * [A.数量] + [A.金额(总计)] + SUM([A.金额])",
            "tabs":[{"alias":"A","tabKey":"id-a:3","rowKeyFields":["k"]}]},
           {"col_key":"c2","source_type":"TAB_JOIN_FORMULA","expression":"[A.金额] + [B.金额]",
            "tabs":[{"alias":"A","tabKey":"id-a","rowKeyFields":["k"]},{"alias":"B","tabKey":"id-missing","rowKeyFields":["k"]}]},
           {"col_key":"c3","source_type":"TAB_JOIN_FORMULA","expression":"[C.金额]",
            "tabs":[{"alias":"C","tabKey":"idx:0","rowKeyFields":[]}]},
           {"col_key":"c4","source_type":"COMPONENT_FIELD","expression":"[A.金额]",
            "tabs":[{"alias":"A","tabKey":"id-a","rowKeyFields":["k"]}]},
           {"col_key":"c5","source_type":"TAB_JOIN_FORMULA","expression":"[A.金额(小计)]",
            "tabs":[{"alias":"A","tabKey":"id-a","rowKeyFields":["k"]}]}]""");
        Map<String, Set<String>> subs = Map.of("id-a", Set.of("金额"));
        var r = TabJoinSubtotalSuffixRewriter.rewrite(cols, subs);
        assertEquals(1, r.rewrittenColumns);
        assertEquals("[A.金额(小计)] * [A.数量] + [A.金额(总计)] + SUM([A.金额(小计)])", cols.get(0).get("expression").asText(),
            "literal replace also hits the bare ref inside SUM(), per 5.4 (no regex, no context)");
        assertEquals("[A.金额] + [B.金额]", cols.get(1).get("expression").asText(), "unknown component → whole column unchanged");
        assertEquals(1, r.notices.size());
        assertTrue(r.notices.get(0).contains("id-missing"));
        assertEquals("[C.金额]", cols.get(2).get("expression").asText());
        assertEquals("[A.金额]", cols.get(3).get("expression").asText(), "non TAB_JOIN_FORMULA untouched");
        assertEquals("[A.金额(小计)]", cols.get(4).get("expression").asText(), "already new notation untouched");
    }

    @Test
    void subtotalColumnsOf_acceptsBooleanAndStringTrue() throws Exception {
        JsonNode f = M.readTree("""
          [{"name":"a","is_subtotal":true},{"name":"b","is_subtotal":"true"},{"name":"c","is_subtotal":false},{"name":"d"}]""");
        assertEquals(Set.of("a", "b"), TabJoinSubtotalSuffixRewriter.subtotalColumnsOf(f));
        assertTrue(TabJoinSubtotalSuffixRewriter.subtotalColumnsOf(null).isEmpty());
    }

    @Test
    void versionLowerThan() {
        assertTrue(TabJoinSubtotalSuffixRewriter.versionLowerThan(null, 1, 2));
        assertTrue(TabJoinSubtotalSuffixRewriter.versionLowerThan("", 1, 2));
        assertTrue(TabJoinSubtotalSuffixRewriter.versionLowerThan("1.0", 1, 2));
        assertTrue(TabJoinSubtotalSuffixRewriter.versionLowerThan("1.1", 1, 2));
        assertFalse(TabJoinSubtotalSuffixRewriter.versionLowerThan("1.2", 1, 2));
        assertFalse(TabJoinSubtotalSuffixRewriter.versionLowerThan("1.10", 1, 2));
        assertFalse(TabJoinSubtotalSuffixRewriter.versionLowerThan("2.0", 1, 2));
        assertTrue(TabJoinSubtotalSuffixRewriter.versionLowerThan("garbage", 1, 2));
        // legacy-hint threshold: below 1.1
        assertTrue(TabJoinSubtotalSuffixRewriter.versionLowerThan("1.0", 1, 1));
        assertFalse(TabJoinSubtotalSuffixRewriter.versionLowerThan("1.1", 1, 1));
        assertFalse(TabJoinSubtotalSuffixRewriter.versionLowerThan("1.2", 1, 1));
    }

    @Test
    void bundleVersionConstantsInSync() {
        assertEquals("1.2", new ComponentExportBundle().bundleVersion);
        assertEquals(new ComponentExportBundle().bundleVersion, ComponentImportService.BUNDLE_VERSION_CURRENT);
    }
}
