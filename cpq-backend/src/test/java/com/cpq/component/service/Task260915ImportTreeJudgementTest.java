package com.cpq.component.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ComponentExportBundle;
import com.cpq.component.dto.ImportCommitResult;
import com.cpq.component.dto.ImportPreviewResult;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * task-260915 B-4/B-5 —— 导入端树身份判定改双判据后的行为与<b>前提验证</b>。
 *
 * <p>本类的头号任务是把 {@code backtask.md} B-4 标为「未验证」的那条前提<b>做成可复跑的实测</b>：
 * 第一遍循环 {@code persist()} 的 {@link ComponentSqlView}，在第三遍
 * {@code TabSemanticResolver#isTreeTabBatch} 内部的 Panache 查询里<b>查不查得到</b>
 * （同事务 Hibernate auto-flush）。查不到的话整个 B-4 方案不成立。
 */
@QuarkusTest
class Task260915ImportTreeJudgementTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Inject ComponentImportService importService;
    @Inject ComponentExportService exportService;
    @Inject TabSemanticResolver tabSemanticResolver;
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

    // =========================================================================
    // B-4 前提：同事务内 persist 的 ComponentSqlView，对后续 Panache 查询可见吗？
    // =========================================================================

    @Test
    @DisplayName("B-4 前提（直接探针）：同事务内 persist 的 ComponentSqlView，对 isTreeTabBatch 的查询可见")
    void persistedSqlViewIsVisibleToBatchResolverInSameTx() throws Exception {
        utx.begin();
        em.joinTransaction();
        try {
            UUID dir = insertDirectory("T260915-probe");

            Component c = new Component();
            c.code = "T260915-PROBE-" + UUID.randomUUID().toString().substring(0, 8);
            c.name = "auto-flush 探针";
            c.componentType = "NORMAL";
            c.columnCount = 0;
            c.status = "ACTIVE";
            c.tabType = null;                 // 关键：tab_type 为 NULL，分支② 必然判「非树」
            c.fields = "[]";
            c.formulas = "[]";
            c.excelColumns = "[]";
            c.directoryId = dir;
            c.persist();

            ComponentSqlView v = new ComponentSqlView();
            v.componentId = c.id;
            v.sqlViewName = "builder_probe_" + c.id.toString().replace("-", "").substring(0, 8);
            v.sqlTemplate = "SELECT 1";
            v.declaredColumns = "[]";
            v.requiredVariables = new String[0];
            v.scope = "COMPONENT";
            v.status = "ACTIVE";
            v.builderConfig = "{\"tabType\":\"BOM\",\"variantKey\":\"\",\"dialect\":\"QUOTE\"}";
            v.builderVersion = 1;
            v.persist();
            // 🚫 这里**故意不调 em.flush()** —— 要验的正是「不显式 flush 也查得到」。

            // 分支① 的原始输出：能不能从 builder_config 解析出 semantic
            Map<UUID, String> sem = tabSemanticResolver.builderSemantics(List.of(c.id));
            System.out.println("[B-4 probe] builderSemantics -> " + sem);
            assertTrue(sem.containsKey(c.id),
                    "同事务内 persist 的 ComponentSqlView 对 builderSemantics 的查询不可见 —— "
                    + "auto-flush 前提不成立，B-4 方案需要调整（显式 flush 或调整循环结构）");
            assertEquals(TabSemanticResolver.SEMANTIC_TREE, sem.get(c.id));

            // 双判据合流后的结论
            Map<UUID, String> probeInput = new LinkedHashMap<>();
            probeInput.put(c.id, null);   // 🚫 不能用 Map.of —— 它不接受 null 值
            Map<UUID, Boolean> flags = tabSemanticResolver.isTreeTabBatch(probeInput);
            System.out.println("[B-4 probe] isTreeTabBatch(tabType=null) -> " + flags);
            assertEquals(Boolean.TRUE, flags.get(c.id),
                    "tab_type 为 NULL，若判成非树说明分支① 没生效");
        } finally {
            utx.rollback();   // 探针不留残留：整段回滚，测试库零写入
        }
    }

    @Test
    @DisplayName("B-4 前提（端到端）：1.1 包里 builder 树页签 + tree_ref 公式，导入成功（AC-8 的后端切片）")
    void importBuilderTreeTabWithTreeRefSucceeds() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID dir = newDirectory("e2e-" + suffix);

        ComponentExportBundle bundle = buildBuilderTreeBundle(suffix, "1.1", true);
        ImportCommitResult r = importService.commit(dir, bundle, "RENAME", true, true);

        assertEquals(1, r.createdCount);
        String newId = r.created.get(0).componentId;
        System.out.println("[B-4 e2e] created componentId=" + newId
                + " finalCode=" + r.created.get(0).finalCode);

        // 落库确认：builder_config / builder_version 真的恢复了
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT v.builder_config::text, v.builder_version, v.status, c.tab_type, c.bom_recursive_expand "
                + "FROM component_sql_view v JOIN component c ON c.id = v.component_id "
                + "WHERE c.id = :id")
                .setParameter("id", UUID.fromString(newId)).getSingleResult();
        System.out.println("[B-4 e2e] builder_config=" + row[0] + " builder_version=" + row[1]
                + " view.status=" + row[2] + " component.tab_type=" + row[3]
                + " bom_recursive_expand=" + row[4]);
        assertNotNull(row[0], "builder_config 未落库");
        assertEquals(1, ((Number) row[1]).intValue());
        assertNull(row[3], "本夹具的 component.tab_type 应为 NULL —— 树身份只能来自分支①");
    }

    @Test
    @DisplayName("B-5（AC-11）：1.0 老包 + builder 树页签 + tree_ref → 报错含「旧格式」与「重新导出」两层信息")
    void legacyBundleTreeRefGivesLocatableError() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID dir = newDirectory("legacy-" + suffix);

        // 1.0 老包：视图名仍是 builder_*，但不带 builderConfig/builderVersion（正是用户手上那份包的形态）
        ComponentExportBundle bundle = buildBuilderTreeBundle(suffix, "1.0", false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> importService.commit(dir, bundle, "RENAME", true, true));
        System.out.println("[B-5] message = " + ex.getMessage());
        assertTrue(ex.getMessage().contains("旧格式"), "报错未点明包是旧格式");
        assertTrue(ex.getMessage().contains("重新导出"), "报错未给出「在源库升级后重新导出」的出路");
        assertTrue(ex.getMessage().contains("bundleVersion 1.0"), "报错未点名具体包版本");
    }

    @Test
    @DisplayName("B-5（AC-10）：1.0 老包里不含树 token 的组件照常导入成功，不整包拒绝")
    void legacyBundleWithoutTreeTokenStillImports() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID dir = newDirectory("legacy-ok-" + suffix);

        ComponentExportBundle bundle = buildBuilderTreeBundle(suffix, "1.0", false);
        bundle.components.get(0).formulas = M.createArrayNode();   // 去掉 tree_ref
        bundle.components.get(0).fields = M.readTree(
                "[{\"name\":\"材料成本\",\"field_type\":\"INPUT_NUMBER\"}]");

        ImportCommitResult r = importService.commit(dir, bundle, "RENAME", true, true);
        assertEquals(1, r.createdCount);
        System.out.println("[B-5 AC-10] 老包导入成功, createdCount=" + r.createdCount);
    }

    @Test
    @DisplayName("B-6（AC-12/AC-17）：预览给出 builderCoord；坐标解析不到时不进 blockers、不改 canCommit")
    void previewReportsBuilderCoordWithoutBlocking() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID dir = newDirectory("preview-" + suffix);

        // ① 坐标合法 → RESOLVED
        ImportPreviewResult ok = importService.preview(dir,
                buildBuilderTreeBundle(suffix, "1.1", true), "RENAME");
        System.out.println("[B-6] RESOLVED 用例 status=" + ok.components.get(0).builderCoord.status
                + " canCommit=" + ok.canCommit);
        assertEquals("RESOLVED", ok.components.get(0).builderCoord.status);

        // ② 坐标在本库不存在 → UNRESOLVABLE，但**不阻断**
        ComponentExportBundle bad = buildBuilderTreeBundle(suffix + "b", "1.1", true);
        bad.components.get(0).sqlViews.get(0).builderConfig = M.readTree(
                "{\"tabType\":\"不存在的类型\",\"variantKey\":\"\",\"dialect\":\"QUOTE\"}");
        ImportPreviewResult bad1 = importService.preview(dir, bad, "RENAME");
        ImportPreviewResult.BuilderCoord bc = bad1.components.get(0).builderCoord;
        System.out.println("[B-6] UNRESOLVABLE 用例 status=" + bc.status + " message=" + bc.message
                + " canCommit=" + bad1.canCommit + " blockers=" + bad1.blockers);
        assertEquals("UNRESOLVABLE", bc.status);
        assertNotNull(bc.message);
        assertTrue(bad1.canCommit, "坐标解析不到不得让 canCommit 变 false（AC-17）");
        assertTrue(bad1.blockers.stream().noneMatch(b -> b.contains("不存在的类型")),
                "坐标解析不到不得进 blockers（AC-17）");

        // ③ 1.0 老包（无 builderConfig）→ NOT_BUILDER
        ImportPreviewResult legacy = importService.preview(dir,
                buildBuilderTreeBundle(suffix + "c", "1.0", false), "RENAME");
        System.out.println("[B-6] 老包 status=" + legacy.components.get(0).builderCoord.status);
        assertEquals("NOT_BUILDER", legacy.components.get(0).builderCoord.status);
    }

    // =========================================================================
    // AC-13：树身份判定环节的 SQL 条数与组件数无关
    // =========================================================================

    @Test
    @DisplayName("AC-13：isTreeTabBatch 在 5 个组件与 10 个组件下发出的 SQL 条数完全相等且 ≤2")
    void treeJudgementSqlCountIsConstant() throws Exception {
        utx.begin();
        em.joinTransaction();
        try {
            UUID dir = insertDirectory("T260915-n1");
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                ids.add(persistBuilderComponent(dir, i));
            }
            em.flush();   // 先把夹具本身的 INSERT 落掉，免得把 INSERT 的语句数算进判定环节

            org.hibernate.stat.Statistics stat = em.getEntityManagerFactory()
                    .unwrap(org.hibernate.SessionFactory.class).getStatistics();
            stat.setStatisticsEnabled(true);

            long before5 = stat.getPrepareStatementCount();
            tabSemanticResolver.isTreeTabBatch(tabTypeMap(ids.subList(0, 5)));
            long sql5 = stat.getPrepareStatementCount() - before5;

            long before10 = stat.getPrepareStatementCount();
            tabSemanticResolver.isTreeTabBatch(tabTypeMap(ids));
            long sql10 = stat.getPrepareStatementCount() - before10;

            System.out.println("[AC-13] 树身份判定环节 SQL 条数: N=5 -> " + sql5 + " ; N=10 -> " + sql10);
            assertEquals(sql5, sql10, "SQL 条数随组件数变化 = N+1");
            assertTrue(sql10 <= 2, "树身份判定环节发出了 " + sql10 + " 条 SQL，超过 TabSemanticResolver 承诺的 ≤2");
            assertTrue(sql10 >= 1, "一条 SQL 都没发，说明这次测量没测到东西（空验证）");
        } finally {
            utx.rollback();
        }
    }

    // =========================================================================

    private Map<UUID, String> tabTypeMap(List<UUID> ids) {
        Map<UUID, String> m = new LinkedHashMap<>();
        for (UUID id : ids) m.put(id, null);
        return m;
    }

    private UUID persistBuilderComponent(UUID dir, int i) {
        Component c = new Component();
        c.code = "T260915-N1-" + UUID.randomUUID().toString().substring(0, 8);
        c.name = "N+1 夹具" + i;
        c.componentType = "NORMAL";
        c.columnCount = 0;
        c.status = "ACTIVE";
        c.fields = "[]";
        c.formulas = "[]";
        c.excelColumns = "[]";
        c.directoryId = dir;
        c.persist();

        ComponentSqlView v = new ComponentSqlView();
        v.componentId = c.id;
        v.sqlViewName = "builder_n1_" + c.id.toString().replace("-", "").substring(0, 8);
        v.sqlTemplate = "SELECT 1";
        v.declaredColumns = "[]";
        v.requiredVariables = new String[0];
        v.scope = "COMPONENT";
        v.status = "ACTIVE";
        v.builderConfig = "{\"tabType\":\"BOM\",\"variantKey\":\"\",\"dialect\":\"QUOTE\"}";
        v.builderVersion = 1;
        v.persist();
        return c.id;
    }

    private UUID insertDirectory(String tag) {
        UUID dir = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO component_directory(id, name, sort_order, created_at) "
                + "VALUES (:id, :name, 0, NOW())")
                .setParameter("id", dir)
                .setParameter("name", tag + "-" + dir.toString().substring(0, 8))
                .executeUpdate();
        return dir;
    }

    private UUID newDirectory(String tag) throws Exception {
        utx.begin();
        em.joinTransaction();
        UUID dir = insertDirectory("T260915-" + tag);
        utx.commit();
        dirsToClean.add(dir);
        return dir;
    }

    /**
     * 造一个「取数配置器建的 BOM 树页签 + 含 tree_ref 公式」的单组件包。
     *
     * @param withBuilderConfig true = 1.1 形态（带 builder_config/builder_version）；
     *                          false = 1.0 形态（视图名仍是 builder_*，但没有配置器信息 —— 本缺陷的现场）
     */
    private ComponentExportBundle buildBuilderTreeBundle(String suffix, String version,
                                                          boolean withBuilderConfig) throws Exception {
        ComponentExportBundle.SqlView sv = new ComponentExportBundle.SqlView();
        sv.sqlViewName = "builder_t260915" + suffix.replace("-", "");
        sv.sqlTemplate = "SELECT 1";
        sv.declaredColumns = M.createArrayNode();
        sv.requiredVariables = List.of();
        sv.scope = "COMPONENT";
        if (withBuilderConfig) {
            sv.builderConfig = M.readTree(
                    "{\"tabType\":\"BOM\",\"variantKey\":\"\",\"dialect\":\"QUOTE\",\"builderVersion\":1,\"columns\":[]}");
            sv.builderVersion = 1;
            sv.status = "ACTIVE";
        }

        ComponentExportBundle.Item it = new ComponentExportBundle.Item();
        it.id = UUID.randomUUID().toString();
        it.code = "T260915-TREE-" + suffix;
        it.name = "物料";
        it.componentType = "NORMAL";
        it.columnCount = 2;
        it.status = "ACTIVE";
        it.tabType = null;                          // 关键：新模型下 component.tab_type 天然为 NULL
        it.dataDriverPath = "$" + sv.sqlViewName;
        it.fields = M.readTree("[{\"name\":\"材料成本\",\"field_type\":\"INPUT_NUMBER\"},"
                + "{\"name\":\"零件材料成本\",\"field_type\":\"FORMULA\",\"formula_name\":\"零件材料成本\"}]");
        it.formulas = M.readTree("[{\"name\":\"零件材料成本\",\"expression\":["
                + "{\"type\":\"tree_ref\",\"agg\":\"SUM\",\"dir\":\"CHILD\","
                + "\"targetExpr\":[{\"type\":\"field\",\"value\":\"材料成本\"}]}]}]");
        it.excelColumns = M.createArrayNode();
        it.sqlViews = new ArrayList<>(List.of(sv));

        ComponentExportBundle bundle = new ComponentExportBundle();
        bundle.bundleVersion = version;
        bundle.components = new ArrayList<>(List.of(it));
        return bundle;
    }

    /** 顺带守住「导出端真的把 8 个字段都写出来了」这条（B-2 的最小切片）。 */
    @Test
    @DisplayName("B-2：导出包的 bundleVersion 为 1.1，且 8 个新字段全部出现在导出产物里")
    void exportCarriesAllEightFields() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID dir = newDirectory("export-" + suffix);

        ComponentExportBundle in = buildBuilderTreeBundle(suffix, "1.1", true);
        in.components.get(0).bomRecursiveExpand = Boolean.TRUE;
        in.components.get(0).elementCodeField = "元素编号";
        in.components.get(0).elementPriceField = "元素单价";
        in.components.get(0).elementCurrencyField = "币种";
        in.components.get(0).treeConfig = M.readTree("{\"idField\":\"料号\",\"parentField\":\"父料号\"}");
        in.components.get(0).sqlViews.get(0).status = "INACTIVE";
        // ⚠️ 这里必须去掉 tree_ref 公式：builderSemantics 只认 status='ACTIVE' 的 builder 视图，
        //    INACTIVE 视图查不到 ⇒ 分支① 不成立 ⇒ 回退分支②(tab_type=null) ⇒ 判非树 ⇒ 闸② 400。
        //    这是 TabSemanticResolver 的既有行为（本次未改动），不是本夹具要验的东西。
        in.components.get(0).formulas = M.createArrayNode();
        in.components.get(0).fields = M.readTree(
                "[{\"name\":\"材料成本\",\"field_type\":\"INPUT_NUMBER\"}]");
        importService.commit(dir, in, "RENAME", true, true);

        ComponentExportBundle out = exportService.exportDirectory(dir);
        ComponentExportBundle.Item o = out.components.get(0);
        ComponentExportBundle.SqlView ov = o.sqlViews.get(0);
        System.out.println("[B-2] bundleVersion=" + out.bundleVersion
                + " bomRecursiveExpand=" + o.bomRecursiveExpand
                + " elementCodeField=" + o.elementCodeField
                + " elementPriceField=" + o.elementPriceField
                + " elementCurrencyField=" + o.elementCurrencyField
                + " treeConfig=" + o.treeConfig
                + " view.status=" + ov.status
                + " builderVersion=" + ov.builderVersion
                + " builderConfig=" + ov.builderConfig);

        assertEquals("1.1", out.bundleVersion);
        assertEquals(Boolean.TRUE, o.bomRecursiveExpand);
        assertEquals("元素编号", o.elementCodeField);
        assertEquals("元素单价", o.elementPriceField);
        assertEquals("币种", o.elementCurrencyField);
        assertEquals("料号", o.treeConfig.path("idField").asText());
        assertEquals("INACTIVE", ov.status, "源视图 status=INACTIVE 被悄悄改成 ACTIVE");
        assertEquals(1, ov.builderVersion);
        assertEquals("BOM", ov.builderConfig.path("tabType").asText());
        assertFalse(out.components.isEmpty());
    }
}
