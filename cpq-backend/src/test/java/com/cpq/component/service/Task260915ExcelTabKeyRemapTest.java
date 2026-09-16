package com.cpq.component.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.cpq.component.dto.ComponentExportBundle;
import com.cpq.component.dto.ImportCommitResult;
import com.cpq.component.entity.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * task-260915 B-11（AC-21）—— EXCEL 组件 {@code excel_columns.tabs[].tabKey} 的跨页签引用重映射。
 *
 * <p><b>缺陷</b>（既有，非本任务引入）：EXCEL 组件的列可以引用兄弟页签
 * （{@code source_type=TAB_JOIN_FORMULA}），被引页签记在 {@code tabs[].tabKey} = 那个组件的 id。
 * 导入建的是全新组件、全新 id，而 {@code excel_columns} 此前原样写入、从不经过重映射 ⇒
 * 新组件仍指向<b>源目录</b>的旧组件。同库表现为「指回源目录」，<b>跨机器搬运则是悬空引用</b>。
 *
 * <p>本类覆盖 AC-21 的两条断言 + 不误伤的三种形态。「改动前 {@code CROSS_DIR > 0}」的阳性对照
 * 由人工 A/B 跑（把 {@code remapExcelColumns} 调用临时摘掉），结果记在回报里。
 */
@QuarkusTest
class Task260915ExcelTabKeyRemapTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Inject ComponentImportService importService;
    @Inject ComponentExportService exportService;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    private final List<UUID> dirsToClean = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        if (dirsToClean.isEmpty()) return;
        utx.begin();
        em.joinTransaction();
        for (UUID dir : dirsToClean) {
            em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id IN "
                    + "(SELECT id FROM component WHERE directory_id = :dir)")
                    .setParameter("dir", dir).executeUpdate();
            em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                    .setParameter("dir", dir).executeUpdate();
            em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                    .setParameter("id", dir).executeUpdate();
        }
        utx.commit();
        dirsToClean.clear();
    }

    @Test
    @DisplayName("AC-21 a/b：包内 tabKey 被重映射到新 id；包外引用与非 id 形态原值不动")
    void excelTabKeysAreRemappedWithoutCollateralDamage() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String siblingOldId = UUID.randomUUID().toString();     // 包内：被引兄弟页签
        String outsiderId = UUID.randomUUID().toString();       // 包外：本包里没有这个 id

        UUID dir = newDirectory(suffix);
        ComponentExportBundle bundle = buildBundle(suffix, siblingOldId, outsiderId);

        // ── 前置断言：夹具里确实存在「指向本包内另一组件」的 tabKey，否则本条空跑 ──
        JsonNode srcCols = bundle.components.get(1).excelColumns;
        assertTrue(srcCols.toString().contains(siblingOldId),
                "夹具没有内嵌指向包内兄弟页签的 tabKey —— 本用例会空跑");

        ImportCommitResult r = importService.commit(dir, bundle, "RENAME", true, true);
        assertEquals(2, r.createdCount);

        String siblingNewId = idOf(r, "T260915-XL-SIB-" + suffix);
        String excelNewId = idOf(r, "T260915-XL-EXC-" + suffix);
        assertNotEquals(siblingOldId, siblingNewId, "导入应分配全新 id，否则本用例没有判别力");

        utx.begin();
        em.joinTransaction();
        String stored;
        try {
            Component c = Component.findById(UUID.fromString(excelNewId));
            stored = c.excelColumns;
        } finally {
            utx.commit();
        }
        System.out.println("[B-11] 导入后 excel_columns = " + stored);

        JsonNode cols = M.readTree(stored);
        assertEquals(siblingNewId, tabKeyAt(cols, 0),
                "断言 a：指向包内兄弟页签的裸 id 未被重映射（这正是 AC-21 要修的缺陷）");
        assertEquals(siblingNewId + ":2", tabKeyAt(cols, 1),
                "断言 a：`<id>:<sortOrder>` 形态应只换 id 段、保留 `:2` 后缀");
        assertEquals(outsiderId, tabKeyAt(cols, 2),
                "断言 b：指向包外的引用被动了 —— 不得清空、不得乱指");
        assertEquals("idx:0", tabKeyAt(cols, 3),
                "断言 b：`idx:<n>` 这种无 id 形态被动了");

        // 全文不得再残留任何旧 id（含旧兄弟 id 出现在别处的情况）
        assertTrue(!stored.contains(siblingOldId),
                "导入后仍残留源库旧 id " + siblingOldId + "：" + stored);

        // 同库复核：新组件引用的组件必须落在**新目录**里（= verdict SQL 的 CROSS_DIR 应为 0）
        // ⚠️ 必须先按 UUID 形状过滤再 ::uuid —— tabKey 里合法地存在 `idx:<n>` 这种无 id 形态，
        //    直接 split_part(...)::uuid 会抛 `invalid input syntax for type uuid: "idx"`（实测撞过）。
        Number crossDir = (Number) em.createNativeQuery(
                "WITH refs AS ("
                + "  SELECT owner.directory_id AS owner_dir, split_part(t->>'tabKey', ':', 1) AS ref_id "
                + "  FROM component owner, "
                + "       LATERAL jsonb_array_elements(owner.excel_columns) col, "
                + "       LATERAL jsonb_array_elements(COALESCE(col->'tabs','[]'::jsonb)) t "
                + "  WHERE owner.directory_id = :dir "
                + "    AND jsonb_typeof(owner.excel_columns) = 'array' "
                + "    AND split_part(t->>'tabKey', ':', 1) ~ "
                + "        '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'"
                + ") "
                + "SELECT count(*) FROM refs JOIN component ref ON ref.id = refs.ref_id::uuid "
                + "WHERE ref.directory_id <> refs.owner_dir")
                .setParameter("dir", dir).getSingleResult();
        System.out.println("[B-11] verdict SQL: CROSS_DIR 行数 = " + crossDir);
        assertEquals(0, crossDir.intValue(), "仍有 tabKey 指向本目录之外的组件（CROSS_DIR > 0）");
    }

    @Test
    @DisplayName("AC-21 a：真实往返（源目录导出→导入新目录）后 verdict SQL 的 CROSS_DIR 为 0")
    void realRoundTripLeavesNoCrossDirReference() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // ── 源目录：SIB + 引用 SIB **真实 id** 的 EXCEL 组件 ──────────────────
        UUID srcDir = newDirectory("rt-src-" + suffix);
        ImportCommitResult seed = importService.commit(srcDir,
                buildBundle(suffix, UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                "RENAME", true, true);
        String sibRealId = idOf(seed, "T260915-XL-SIB-" + suffix);
        String excRealId = idOf(seed, "T260915-XL-EXC-" + suffix);
        // 把 EXCEL 组件的 tabKey 改成指向同目录 SIB 的**真实 id**（模拟用户在配置器里绑好的状态）
        utx.begin();
        em.joinTransaction();
        try {
            Component exc = Component.findById(UUID.fromString(excRealId));
            exc.excelColumns = exc.excelColumns.replaceAll(
                    "\\\"tabKey\\\": ?\\\"[0-9a-fA-F-]{36}(:\\d+)?\\\"",
                    "\\\"tabKey\\\":\\\"" + sibRealId + "\\\"");
        } finally {
            utx.commit();
        }
        // 前置断言：源目录里确实存在「EXCEL 组件 → 同目录另一组件」的引用，否则本条空跑
        Number sameDirBefore = crossDirCount(srcDir, true);
        System.out.println("[B-11 往返] 前置：源目录 SAME_DIR 引用数 = " + sameDirBefore);
        assertTrue(sameDirBefore.intValue() >= 1,
                "源目录没有内嵌指向同目录组件的 tabKey —— 本用例会空跑");

        // ── 导出 → 导入新目录 ────────────────────────────────────────────────
        ComponentExportBundle bundle = exportService.exportDirectory(srcDir);
        UUID dstDir = newDirectory("rt-dst-" + suffix);
        ImportCommitResult r = importService.commit(dstDir, bundle, "RENAME", true, true);
        assertEquals(2, r.createdCount);

        Number crossDir = crossDirCount(dstDir, false);
        System.out.println("[B-11 往返] verdict SQL：新目录 CROSS_DIR 行数 = " + crossDir
                + "（改动前的实现在这里会 > 0：tabKey 仍指向源目录的旧组件）");
        assertEquals(0, crossDir.intValue(),
                "新目录里仍有 tabKey 指向**源目录**的组件（CROSS_DIR > 0）—— 正是 AC-21 要修的缺陷");
    }

    /** verdict SQL：该目录的 EXCEL 组件里，tabKey 指向「同目录(true)/别的目录(false)」的引用条数。 */
    private Number crossDirCount(UUID dir, boolean sameDir) {
        return (Number) em.createNativeQuery(
                "WITH refs AS ("
                + "  SELECT owner.directory_id AS owner_dir, split_part(t->>'tabKey', ':', 1) AS ref_id "
                + "  FROM component owner, "
                + "       LATERAL jsonb_array_elements(owner.excel_columns) col, "
                + "       LATERAL jsonb_array_elements(COALESCE(col->'tabs','[]'::jsonb)) t "
                + "  WHERE owner.directory_id = :dir "
                + "    AND jsonb_typeof(owner.excel_columns) = 'array' "
                + "    AND split_part(t->>'tabKey', ':', 1) ~ "
                + "        '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'"
                + ") "
                + "SELECT count(*) FROM refs JOIN component ref ON ref.id = refs.ref_id::uuid "
                + "WHERE ref.directory_id " + (sameDir ? "=" : "<>") + " refs.owner_dir")
                .setParameter("dir", dir).getSingleResult();
    }

    @Test
    @DisplayName("AC-21 c：既有两路（cross_tab_ref.source / component_subtotal.component_code）行为逐字不变")
    void existingTwoRemapPathsUnchanged() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String siblingOldId = UUID.randomUUID().toString();
        UUID dir = newDirectory("keep-" + suffix);

        ComponentExportBundle bundle = buildBundle(suffix, siblingOldId, UUID.randomUUID().toString());
        // 给 EXCEL 组件挂一条同时用到两种既有 token 的公式
        bundle.components.get(1).formulas = M.readTree("""
            [{"name":"保持不变","expression":[
               {"type":"cross_tab_ref","agg":"SUM","source":"%s","target":"单价","match":[]},
               {"type":"component_subtotal","component_code":"%s","value":"小计"}]}]"""
                .formatted(siblingOldId, "T260915-XL-SIB-" + suffix));

        ImportCommitResult r = importService.commit(dir, bundle, "RENAME", true, true);
        String siblingNewId = idOf(r, "T260915-XL-SIB-" + suffix);
        String siblingNewCode = r.created.stream()
                .filter(ci -> ("T260915-XL-SIB-" + suffix).equals(ci.originalCode))
                .findFirst().orElseThrow().finalCode;

        utx.begin();
        em.joinTransaction();
        String formulas;
        try {
            formulas = ((Component) Component.findById(
                    UUID.fromString(idOf(r, "T260915-XL-EXC-" + suffix)))).formulas;
        } finally {
            utx.commit();
        }
        System.out.println("[B-11] 既有两路 formulas = " + formulas);
        JsonNode tokens = M.readTree(formulas).get(0).get("expression");
        assertEquals(siblingNewId, tokens.get(0).get("source").asText(),
                "既有路①（cross_tab_ref.source）行为变了");
        assertEquals(siblingNewCode, tokens.get(1).get("component_code").asText(),
                "既有路②（component_subtotal.component_code）行为变了");
    }

    // =========================================================================

    private static String tabKeyAt(JsonNode cols, int i) {
        return cols.get(i).get("tabs").get(0).get("tabKey").asText();
    }

    private String idOf(ImportCommitResult r, String originalCode) {
        return r.created.stream().filter(ci -> originalCode.equals(ci.originalCode))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 " + originalCode))
                .componentId;
    }

    /**
     * 两个组件：被引兄弟页签 SIB，以及内嵌 4 种 tabKey 形态的 EXCEL 组件 EXC。
     * 4 种形态分别覆盖：包内裸 id / 包内 {@code <id>:<sortOrder>} / 包外 id / {@code idx:<n>}。
     */
    private ComponentExportBundle buildBundle(String suffix, String siblingOldId, String outsiderId)
            throws Exception {
        ComponentExportBundle.Item sib = new ComponentExportBundle.Item();
        sib.id = siblingOldId;
        sib.code = "T260915-XL-SIB-" + suffix;
        sib.name = "被引兄弟页签";
        sib.componentType = "NORMAL";
        sib.fields = M.readTree("[{\"name\":\"单价\",\"field_type\":\"INPUT_NUMBER\"}]");
        sib.formulas = M.createArrayNode();
        sib.excelColumns = M.createArrayNode();

        ComponentExportBundle.Item exc = new ComponentExportBundle.Item();
        exc.id = UUID.randomUUID().toString();
        exc.code = "T260915-XL-EXC-" + suffix;
        exc.name = "EXCEL 组件";
        exc.componentType = "EXCEL";
        exc.fields = M.createArrayNode();
        exc.formulas = M.createArrayNode();
        exc.excelColumns = M.readTree("""
            [{"col_key":"col_1","title":"包内-裸id","source_type":"TAB_JOIN_FORMULA",
              "tabs":[{"alias":"兄弟","tabKey":"%s","rowKeyFields":[]}]},
             {"col_key":"col_2","title":"包内-id冒号序号","source_type":"TAB_JOIN_FORMULA",
              "tabs":[{"alias":"兄弟","tabKey":"%s:2","rowKeyFields":[]}]},
             {"col_key":"col_3","title":"包外引用","source_type":"TAB_JOIN_FORMULA",
              "tabs":[{"alias":"外部","tabKey":"%s","rowKeyFields":[]}]},
             {"col_key":"col_4","title":"无id形态","source_type":"TAB_JOIN_FORMULA",
              "tabs":[{"alias":"按序号","tabKey":"idx:0","rowKeyFields":[]}]}]"""
                .formatted(siblingOldId, siblingOldId, outsiderId));

        ComponentExportBundle b = new ComponentExportBundle();
        b.bundleVersion = "1.1";
        b.components = new ArrayList<>(List.of(sib, exc));
        return b;
    }

    private UUID newDirectory(String tag) throws Exception {
        UUID dir = UUID.randomUUID();
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("INSERT INTO component_directory(id, name, sort_order, created_at) "
                + "VALUES (:id, :name, 0, NOW())")
                .setParameter("id", dir)
                .setParameter("name", "T260915-XLTAB-" + tag + "-" + dir.toString().substring(0, 8))
                .executeUpdate();
        utx.commit();
        dirsToClean.add(dir);
        return dir;
    }
}
