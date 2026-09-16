package com.cpq.task260915;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
 * <b>AC-18（边界）</b> —— RENAME 冲突策略下字段仍保真。
 *
 * <h3>AC 原文（需求文档.md §③ 边界 AC）</h3>
 * <blockquote>
 * 前置：<b>目标目录已存在同 {@code code} 的组件</b>，导入策略选 {@code RENAME}。<br>
 * 断言：新组件获得<b>新 {@code code}</b>，但 <b>8 个字段</b>的值与源<b>完全一致</b>
 * （重命名只影响 {@code code}，<b>不得连带丢字段</b>）。
 * </blockquote>
 *
 * <h3>「8 个字段」= 需求文档 §② 的丢失字段清单</h3>
 * {@code component}：{@code tree_config} / {@code bom_recursive_expand} /
 * {@code element_code_field} / {@code element_price_field} / {@code element_currency_field}；
 * {@code component_sql_view}：{@code builder_config} / {@code builder_version} / {@code status}。
 *
 * <h3>🚨 源不用共享目录，用「已造齐形态的私有副本 M」</h3>
 * 直接拿共享源目录当源，8 个字段里 {@code tree_config} 全 NULL、{@code status} 全 ACTIVE
 * ⇒ 这两项会退化成「NULL==NULL」「ACTIVE==ACTIVE」的恒真断言（testing.md §3 假绿）。
 * ⇒ 本类先把源目录导入成私有副本 <b>M</b>，在 M 上补齐 {@code tree_config} 非空 与
 * 一个 {@code INACTIVE} 视图，<b>再以 M 为源</b>验 RENAME 往返 —— 8 个字段全部有非默认取值。
 *
 * <h3>前置怎么构造成「目标目录已存在同 code 的组件」</h3>
 * 把 M 的包<b>往同一个目标目录 R 导两次</b>：第一次把组件建进 R，第二次的前置就成立了。
 * ⚠️ 附带事实：{@code component_code_key} 是<b>全局唯一索引</b>，所以<b>第一次也会</b>触发 RENAME。
 * 本类只对<b>第二批</b>下 AC-18 的断言，并额外断言两批 code 互不相同 ——
 * 否则「目标目录里已存在同 code」这个前置其实没被真正构造出来。
 */
@QuarkusTest
@DisplayName("task-260915 S-A · AC-18 RENAME 策略下 8 个字段仍保真")
class Ac18RenameFidelityTest extends Task260915Base {

    private static final String TREE_CONFIG_SAMPLE =
            "{\"rootField\":\"料号\",\"parentField\":\"父料号\",\"levels\":[1,2],"
          + "\"options\":{\"expandAll\":false,\"label\":\"RT-SA-260915 AC18 样本\"}}";

    @Test
    @DisplayName("AC-18 目标目录已存在同 code → 第二批获得新 code，8 个字段与源完全一致")
    void ac18_renameKeepsAllEightFields() {
        final String AC = "AC-18";
        UUID src = sourceDirectoryId();

        // ── 准备私有源 M（补齐 tree_config 与 INACTIVE 视图，让 8 个字段都有非默认值）──
        RoundTrip toM = roundTrip(src, "AC18-M", AC);
        UUID dirM = toM.targetDir();

        UUID tcComp = UUID.fromString(String.valueOf(col("SELECT id::text FROM component WHERE directory_id='"
                + dirM + "'::uuid ORDER BY code LIMIT 1").get(0)));
        assertEquals(1, exec("UPDATE component SET tree_config=" + lit(TREE_CONFIG_SAMPLE) + "::jsonb"
                + " WHERE id='" + tcComp + "'::uuid AND directory_id='" + dirM + "'::uuid"),
                AC + "：tree_config 造数未命中 1 行。");
        List<Object> vids = col("SELECT v.id::text FROM component_sql_view v JOIN component c ON c.id=v.component_id"
                + " WHERE c.directory_id='" + dirM + "'::uuid ORDER BY v.sql_view_name LIMIT 1");
        assertFalse(vids.isEmpty(), AC + "：M 里一个视图都没有 ⇒ 视图侧 3 个字段无从验。");
        assertEquals(1, exec("UPDATE component_sql_view SET status='INACTIVE' WHERE id='" + vids.get(0) + "'::uuid"),
                AC + "：status 造数未命中 1 行。");

        // 🚨 防空跑：8 个字段的样本覆盖度必须先自证（都在 M 目录内统计，🚫 无全局计数）
        long nTree = count("SELECT count(*) FROM component WHERE directory_id='" + dirM + "'::uuid AND tree_config IS NOT NULL");
        long nRec = count("SELECT count(*) FROM component WHERE directory_id='" + dirM + "'::uuid AND bom_recursive_expand");
        long nElem = count("SELECT count(*) FROM component WHERE directory_id='" + dirM + "'::uuid AND element_code_field IS NOT NULL");
        long nCfg = count("SELECT count(*) FROM component_sql_view v JOIN component c ON c.id=v.component_id"
                + " WHERE c.directory_id='" + dirM + "'::uuid AND v.builder_config IS NOT NULL");
        long nInact = count("SELECT count(*) FROM component_sql_view v JOIN component c ON c.id=v.component_id"
                + " WHERE c.directory_id='" + dirM + "'::uuid AND v.status='INACTIVE'");
        assertTrue(nTree > 0, AC + "：M 里 tree_config 全为 NULL ⇒ 该字段的断言会退化成 NULL==NULL（恒真）。");
        assertTrue(nRec > 0, AC + "：M 里没有 bom_recursive_expand=true 的组件 ⇒ 该字段只验到 false==false。");
        assertTrue(nElem > 0, AC + "：M 里 element_code_field 全为 NULL ⇒ 元素三列只验到 NULL==NULL。");
        assertTrue(nCfg > 0, AC + "：M 里没有非空 builder_config ⇒ 该字段只验到 NULL==NULL。");
        assertTrue(nInact > 0, AC + "：M 里没有 INACTIVE 视图 ⇒ status 只验到 ACTIVE==ACTIVE。");
        System.out.println("[" + AC + "] M 侧样本覆盖：tree_config=" + nTree + " / recursive=" + nRec
                + " / element_code=" + nElem + " / builder_config=" + nCfg + " / INACTIVE 视图=" + nInact);

        // ── 同一个目标目录 R 导两次 ──
        Bundle pkg = exportBundle(dirM, AC);
        UUID dirR = newDirectory("AC18-R");
        RoundTrip first = importInto(dirM, pkg, dirR, "RENAME", AC + " 第一次导入（构造前置）");
        RoundTrip second = importInto(dirM, pkg, dirR, "RENAME", AC + " 第二次导入（被测）");

        // 前置自证：第二次导入时目标目录里<b>确实已存在同名 code</b>（否则测的不是 AC-18 的场景）
        Set<String> firstCodes = new LinkedHashSet<>(first.finalCodeByOrigCode().values());
        Set<String> secondCodes = new LinkedHashSet<>(second.finalCodeByOrigCode().values());
        assertEquals(firstCodes.size(), first.finalCodeByOrigCode().size(), AC + "：第一批落库 code 有重复。");
        Set<String> overlap = new LinkedHashSet<>(firstCodes);
        overlap.retainAll(secondCodes);
        assertTrue(overlap.isEmpty(), AC + "：两批落库的 code 有重叠 " + overlap
                + " ⇒ 第二批没有获得新 code（AC-18 要求「新组件获得新 code」）。");
        long inR = count("SELECT count(*) FROM component WHERE directory_id='" + dirR + "'::uuid");
        assertEquals(first.dstIdByOrigCode().size() + second.dstIdByOrigCode().size(), inR,
                AC + "：目录 R 内组件数应为两批之和，实际 " + inR + " ⇒ 第二批可能覆盖了第一批而不是新建。");

        // 新 code 与源 code 也必须不同（RENAME 的直接可观测后果）
        for (Map.Entry<String, String> e : second.finalCodeByOrigCode().entrySet()) {
            assertNotNull(e.getValue(), AC + "：组件 " + e.getKey() + " 的 finalCode 为 null。");
            assertFalse(e.getKey().equals(e.getValue()), AC + "：组件 " + e.getKey()
                    + " 在 RENAME 策略下 code 没变（仍为 " + e.getValue() + "）⇒ 前置或策略没生效。");
        }
        System.out.println("[" + AC + "] 第一批 codes=" + firstCodes + "\n[" + AC + "] 第二批 codes=" + secondCodes);

        // ── 断言：第二批的 8 个字段与源 M 完全一致 ──
        Map<String, UUID> mIds = componentIdsByCode(dirM);
        int checked = 0, viewChecked = 0;
        List<Diff> diffs = new ArrayList<>();
        for (Map.Entry<String, UUID> e : second.dstIdByOrigCode().entrySet()) {
            UUID s = mIds.get(e.getKey());
            assertNotNull(s, AC + "：源 M 里找不到 code=" + e.getKey() + " ⇒ 配对基准错了。");
            String key = e.getKey() + " → " + second.finalCodeByOrigCode().get(e.getKey());

            diffs.addAll(diffRow(key, "component", EIGHT_LOST_COMPONENT_FIELDS,
                    rowText("component", EIGHT_LOST_COMPONENT_FIELDS, s),
                    rowText("component", EIGHT_LOST_COMPONENT_FIELDS, e.getValue()), null, null));
            checked++;

            List<String> vcols = new ArrayList<>(EIGHT_LOST_SQLVIEW_FIELDS);
            vcols.add(0, "sql_view_name");
            Map<String, Map<String, String>> sv = viewsOf(s, vcols);
            Map<String, Map<String, String>> dv = viewsOf(e.getValue(), vcols);
            assertEquals(sv.keySet(), dv.keySet(), AC + "：组件 " + key
                    + " 的视图名集合两边不等（源=" + sv.keySet() + " 新=" + dv.keySet() + "）⇒ RENAME 连带丢了视图。");
            for (String vn : sv.keySet()) {
                diffs.addAll(diffRow(key + "/" + vn, "component_sql_view", EIGHT_LOST_SQLVIEW_FIELDS,
                        sv.get(vn), dv.get(vn), null, null));
                viewChecked++;
            }
        }
        assertTrue(checked > 0 && viewChecked > 0, AC + "：组件 " + checked + " 个 / 视图 " + viewChecked
                + " 个 ⇒ 有一侧 0 次循环，断言从未执行（假绿）。");
        assertTrue(diffs.isEmpty(), AC + "：RENAME 之后 8 个字段出现 " + diffs.size() + " 处差异（应为 0）——"
                + " 「重命名只影响 code，不得连带丢字段」被违反：" + fmt(diffs));
        System.out.println("[" + AC + "] ✅ 第二批 " + checked + " 个组件 / " + viewChecked
                + " 个视图，8 个字段与源 M 逐字段一致（含 tree_config 非空、INACTIVE 视图、元素三列）。");
    }
}
