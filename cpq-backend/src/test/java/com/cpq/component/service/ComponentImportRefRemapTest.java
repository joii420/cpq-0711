package com.cpq.component.service;

import com.cpq.component.dto.ComponentExportBundle;
import com.cpq.component.dto.ImportCommitResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD 集成测试：验证 ComponentImportService.commit 的两遍重映射逻辑。
 *
 * <p>G3 Bug2：导入后新副本的 formulas 里的跨组件引用必须指向<b>同批次新副本</b>，
 * 而不是 bundle 里的原组件 id/code。
 *
 * <p>测试场景：
 * <ul>
 *   <li>TC-1：A 引用 B（cross_tab_ref.source = B原id）→ 导入后 A副本.formulas 指向 B副本新id</li>
 *   <li>TC-2：A 引用 B（component_subtotal.component_code = B原code）→ 导入后 A副本 指向 B副本 finalCode</li>
 *   <li>TC-3：RENAME 策略下 B 被重命名为 B__imp1 → A副本 component_subtotal.code 指向 B__imp1</li>
 *   <li>TC-4：老 bundle（Item.id=null）→ UUID 类引用保持原样（codeMap 仍可映射）</li>
 *   <li>TC-5：SKIP 策略跳过 B → A副本 formulas 保留原引用（B未导入，无法重映射）</li>
 * </ul>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ComponentImportService — G3 两遍重映射")
class ComponentImportRefRemapTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    ComponentImportService importService;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    /** 每次测试用独立目录，避免 code 冲突 */
    private UUID targetDirId;

    @BeforeEach
    void setupDirectory() throws Exception {
        targetDirId = UUID.randomUUID();
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO component_directory(id, name, sort_order, created_at) " +
                "VALUES (:id, 'ImportRefRemapTestDir', 0, NOW())")
                .setParameter("id", targetDirId)
                .executeUpdate();
        utx.commit();
    }

    @AfterEach
    void cleanup() throws Exception {
        utx.begin();
        em.joinTransaction();
        // 清理本测试创建的组件 + 目录（用 directory_id 定位）
        em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id IN " +
                "(SELECT id FROM component WHERE directory_id = :dir)")
                .setParameter("dir", targetDirId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                .setParameter("dir", targetDirId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                .setParameter("id", targetDirId)
                .executeUpdate();
        utx.commit();
    }

    // ── TC-1：cross_tab_ref.source 重映射到新副本 id ──────────────────────────

    @Test
    @Order(1)
    @DisplayName("TC-1: cross_tab_ref.source 应重映射到新副本 id")
    void crossTabRef_source_remapped_to_new_component_id() throws Exception {
        // 构造 bundle 原始 id
        String origIdA = UUID.randomUUID().toString();
        String origIdB = UUID.randomUUID().toString();

        // B 的 formulas 为空
        // A 的 formulas 引用 B 的原始 id（cross_tab_ref.source）
        String aFormulas = """
                [{"name":"引用B","expression":[{
                  "type":"cross_tab_ref",
                  "agg":"SUM",
                  "source":"%s",
                  "sourceLabel":"B组件",
                  "targetExpr":[{"type":"field","value":"数量","source":"%s"}],
                  "match":[]
                }]}]""".formatted(origIdB, origIdB);

        ComponentExportBundle bundle = buildBundle(
                item(origIdA, "COMP-G3TC1-A-" + targetDirId.toString().substring(0, 8),
                     "组件A", aFormulas),
                item(origIdB, "COMP-G3TC1-B-" + targetDirId.toString().substring(0, 8),
                     "组件B", "[]")
        );

        // 执行 commit（ignoreMissingDeps=true，测试环境无真实依赖）
        ImportCommitResult result = importService.commit(targetDirId, bundle, "RENAME", true);

        assertEquals(2, result.createdCount, "应创建 2 个组件");

        // 找到 A副本 和 B副本 的新 id
        String newIdA = findComponentIdByOrigCode(result, "COMP-G3TC1-A-" + targetDirId.toString().substring(0, 8));
        String newIdB = findComponentIdByOrigCode(result, "COMP-G3TC1-B-" + targetDirId.toString().substring(0, 8));
        assertNotNull(newIdA, "A副本应在结果中");
        assertNotNull(newIdB, "B副本应在结果中");

        // 查询 A副本 的 formulas
        String aFormulasAfter = queryFormulas(newIdA);

        // 断言：cross_tab_ref.source 已指向 B副本 新id，不再是 B原始 id
        assertFalse(aFormulasAfter.contains(origIdB),
                "A副本 formulas 不应再包含 B原始 id [" + origIdB + "]，实际: " + aFormulasAfter);
        assertTrue(aFormulasAfter.contains(newIdB),
                "A副本 formulas 应包含 B副本 新id [" + newIdB + "]，实际: " + aFormulasAfter);
    }

    // ── TC-2：component_subtotal.component_code 重映射到新副本 finalCode ───────

    @Test
    @Order(2)
    @DisplayName("TC-2: component_subtotal.component_code 应重映射到新副本 finalCode")
    void componentSubtotal_code_remapped_to_new_component_code() throws Exception {
        String origIdA = UUID.randomUUID().toString();
        String origIdB = UUID.randomUUID().toString();
        String codeA = "COMP-G3TC2-A-" + targetDirId.toString().substring(0, 8);
        String codeB = "COMP-G3TC2-B-" + targetDirId.toString().substring(0, 8);

        // A 的 formulas 里 component_subtotal 引用 B的原 code
        String aFormulas = """
                [{"name":"B小计","expression":[{
                  "type":"component_subtotal",
                  "component_code":"%s",
                  "tab_name":"材料成本"
                }]}]""".formatted(codeB);

        ComponentExportBundle bundle = buildBundle(
                item(origIdA, codeA, "组件A", aFormulas),
                item(origIdB, codeB, "组件B", "[]")
        );

        ImportCommitResult result = importService.commit(targetDirId, bundle, "RENAME", true);
        assertEquals(2, result.createdCount);

        String newIdA = findComponentIdByOrigCode(result, codeA);
        assertNotNull(newIdA);

        String aFormulasAfter = queryFormulas(newIdA);

        // codeB 就是 finalCode（无冲突，不重命名）
        assertTrue(aFormulasAfter.contains(codeB),
                "component_subtotal.code 应仍含 codeB（无冲突场景，finalCode=原code），实际: " + aFormulasAfter);
        // 验证 JSON 结构没有损坏：仍包含 component_subtotal type
        assertTrue(aFormulasAfter.contains("component_subtotal"),
                "formulas 结构应完整保留 type=component_subtotal");
    }

    // ── TC-3：RENAME 场景 B 被重命名 → A副本 component_subtotal.code 指向重命名后 code ─

    @Test
    @Order(3)
    @DisplayName("TC-3: RENAME策略下B被重命名，A副本component_subtotal.code应指向新code")
    void componentSubtotal_code_remapped_to_renamed_code() throws Exception {
        String origIdA = UUID.randomUUID().toString();
        String origIdB = UUID.randomUUID().toString();

        // 先在 DB 插入一个同 code 的组件，使 B 产生冲突 → RENAME
        String conflictCode = "COMP-G3TC3-CONFLICT-" + targetDirId.toString().substring(0, 8);
        utx.begin();
        em.joinTransaction();
        // 插入一个同 code 组件（不在测试目录里，只为占 code）
        UUID dummyDirId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO component_directory(id, name, sort_order, created_at) " +
                "VALUES (:id, 'DummyDir', 0, NOW())")
                .setParameter("id", dummyDirId)
                .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO component(id, directory_id, name, code, column_count, fields, formulas, excel_columns, component_type, status, created_at, updated_at) " +
                "VALUES (:id, :dir, 'Conflict组件', :code, 0, '[]', '[]', '[]', 'NORMAL', 'ACTIVE', NOW(), NOW())")
                .setParameter("id", UUID.randomUUID())
                .setParameter("dir", dummyDirId)
                .setParameter("code", conflictCode)
                .executeUpdate();
        utx.commit();

        try {
            String codeA = "COMP-G3TC3-A-" + targetDirId.toString().substring(0, 8);
            // A 引用 B（原 code = conflictCode）
            String aFormulas = """
                    [{"name":"B小计","expression":[{
                      "type":"component_subtotal",
                      "component_code":"%s",
                      "tab_name":"材料成本"
                    }]}]""".formatted(conflictCode);

            ComponentExportBundle bundle = buildBundle(
                    item(origIdA, codeA, "组件A", aFormulas),
                    item(origIdB, conflictCode, "组件B", "[]")
            );

            // RENAME 策略：B 与已有组件冲突 → 被重命名为 conflictCode__imp1
            ImportCommitResult result = importService.commit(targetDirId, bundle, "RENAME", true);
            assertEquals(2, result.createdCount, "A 和 B（重命名后）都应被创建");

            // 找到 B 副本的 finalCode
            String finalCodeB = result.created.stream()
                    .filter(ci -> conflictCode.equals(ci.originalCode))
                    .map(ci -> ci.finalCode)
                    .findFirst()
                    .orElse(null);
            assertNotNull(finalCodeB, "B副本应在结果中");
            assertTrue(finalCodeB.contains("__imp"), "B应被重命名，finalCode 应含 __imp，实际: " + finalCodeB);

            // 查询 A副本 formulas
            String newIdA = findComponentIdByOrigCode(result, codeA);
            String aFormulasAfter = queryFormulas(newIdA);

            // 断言：A副本 component_subtotal.code 指向 B副本的 finalCode（重命名后）
            assertFalse(aFormulasAfter.contains("\"component_code\":\"" + conflictCode + "\""),
                    "A副本 formulas 不应再引用 B原始 code [" + conflictCode + "]，实际: " + aFormulasAfter);
            assertTrue(aFormulasAfter.contains(finalCodeB),
                    "A副本 formulas 应引用 B副本 finalCode [" + finalCodeB + "]，实际: " + aFormulasAfter);

        } finally {
            // 清理冲突组件 + 占位目录
            utx.begin();
            em.joinTransaction();
            em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                    .setParameter("dir", dummyDirId)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                    .setParameter("id", dummyDirId)
                    .executeUpdate();
            utx.commit();
        }
    }

    // ── TC-4：老 bundle（Item.id=null）→ UUID 类引用保持原样，code 仍映射 ─────

    @Test
    @Order(4)
    @DisplayName("TC-4: 老bundle Item.id=null时UUID引用保持原样，codeMap仍有效")
    void oldBundle_nullItemId_uuidRefUnchanged_codeRefStillMapped() throws Exception {
        String origIdB = UUID.randomUUID().toString(); // 模拟老 bundle 里 A 公式里写的是某个 UUID
        String codeA = "COMP-G3TC4-A-" + targetDirId.toString().substring(0, 8);
        String codeB = "COMP-G3TC4-B-" + targetDirId.toString().substring(0, 8);

        // A 同时引用 cross_tab_ref(source=origIdB) 和 component_subtotal(code=codeB)
        String aFormulas = """
                [{"name":"双引用","expression":[
                  {"type":"cross_tab_ref","agg":"SUM","source":"%s","targetExpr":[],"match":[]},
                  {"type":"component_subtotal","component_code":"%s","tab_name":"材料成本"}
                ]}]""".formatted(origIdB, codeB);

        // 老 bundle：id=null（不设 item.id）
        ComponentExportBundle.Item itemA = new ComponentExportBundle.Item();
        itemA.id = null; // 老 bundle，无 id
        itemA.code = codeA;
        itemA.name = "老A";
        itemA.componentType = "NORMAL";
        itemA.columnCount = 0;
        itemA.status = "ACTIVE";
        itemA.fields = MAPPER.createArrayNode();
        itemA.formulas = MAPPER.readTree(aFormulas);
        itemA.excelColumns = MAPPER.createArrayNode();

        ComponentExportBundle.Item itemB = new ComponentExportBundle.Item();
        itemB.id = null; // 老 bundle，无 id
        itemB.code = codeB;
        itemB.name = "老B";
        itemB.componentType = "NORMAL";
        itemB.columnCount = 0;
        itemB.status = "ACTIVE";
        itemB.fields = MAPPER.createArrayNode();
        itemB.formulas = MAPPER.createArrayNode();
        itemB.excelColumns = MAPPER.createArrayNode();

        ComponentExportBundle bundle = buildBundle(itemA, itemB);

        ImportCommitResult result = importService.commit(targetDirId, bundle, "RENAME", true);
        assertEquals(2, result.createdCount);

        String newIdA = findComponentIdByOrigCode(result, codeA);
        String aFormulasAfter = queryFormulas(newIdA);

        // UUID 类引用（cross_tab_ref.source）：id=null → idMap 无条目 → 原样保留
        assertTrue(aFormulasAfter.contains(origIdB),
                "老bundle id=null时，cross_tab_ref.source 应原样保留，实际: " + aFormulasAfter);

        // code 类引用（component_subtotal）：codeMap 仍可映射（code 始终收集）
        // codeB 无冲突 → finalCode == codeB，code 映射到自身，保持不变
        assertTrue(aFormulasAfter.contains(codeB),
                "component_subtotal.code 应包含 codeB（无冲突，finalCode=codeB），实际: " + aFormulasAfter);
    }

    // ── TC-5：SKIP 策略跳过 B → A副本 formulas 保留原引用 ────────────────────

    @Test
    @Order(5)
    @DisplayName("TC-5: SKIP策略跳过B时，A副本formulas不变（B未导入）")
    void skipPolicy_skippedComponent_formulaRefUnchanged() throws Exception {
        String origIdA = UUID.randomUUID().toString();
        String origIdB = UUID.randomUUID().toString();
        String codeA = "COMP-G3TC5-A-" + targetDirId.toString().substring(0, 8);
        String codeB = "COMP-G3TC5-SKIP-" + targetDirId.toString().substring(0, 8);

        // 先在 DB 插入同 code 的组件，使 B 冲突 → SKIP 策略跳过
        utx.begin();
        em.joinTransaction();
        UUID dummyDirId2 = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO component_directory(id, name, sort_order, created_at) " +
                "VALUES (:id, 'DummyDir2', 0, NOW())")
                .setParameter("id", dummyDirId2)
                .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO component(id, directory_id, name, code, column_count, fields, formulas, excel_columns, component_type, status, created_at, updated_at) " +
                "VALUES (:id, :dir, 'ExistingB', :code, 0, '[]', '[]', '[]', 'NORMAL', 'ACTIVE', NOW(), NOW())")
                .setParameter("id", UUID.randomUUID())
                .setParameter("dir", dummyDirId2)
                .setParameter("code", codeB)
                .executeUpdate();
        utx.commit();

        try {
            // A 的 formulas 引用 B（cross_tab_ref + component_subtotal）
            String aFormulas = """
                    [{"name":"引用B","expression":[
                      {"type":"cross_tab_ref","agg":"SUM","source":"%s","targetExpr":[],"match":[]},
                      {"type":"component_subtotal","component_code":"%s","tab_name":"材料"}
                    ]}]""".formatted(origIdB, codeB);

            ComponentExportBundle bundle = buildBundle(
                    item(origIdA, codeA, "组件A", aFormulas),
                    item(origIdB, codeB, "组件B", "[]")
            );

            // SKIP 策略：B 冲突 → 跳过
            ImportCommitResult result = importService.commit(targetDirId, bundle, "SKIP", true);
            assertEquals(1, result.createdCount, "只应创建 A（B 被 SKIP）");
            assertEquals(1, result.skippedCount, "B 应被跳过");

            String newIdA = findComponentIdByOrigCode(result, codeA);
            String aFormulasAfter = queryFormulas(newIdA);

            // B 被 SKIP，不在 idMap 中 → cross_tab_ref.source 保持原 origIdB
            assertTrue(aFormulasAfter.contains(origIdB),
                    "B被SKIP，A副本 cross_tab_ref.source 应保持原 origIdB，实际: " + aFormulasAfter);
            // B 被 SKIP，不在 codeMap 中 → component_subtotal.code 保持原 codeB
            assertTrue(aFormulasAfter.contains(codeB),
                    "B被SKIP，A副本 component_subtotal.code 应保持原 codeB，实际: " + aFormulasAfter);

        } finally {
            utx.begin();
            em.joinTransaction();
            em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                    .setParameter("dir", dummyDirId2)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                    .setParameter("id", dummyDirId2)
                    .executeUpdate();
            utx.commit();
        }
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────────

    /** 构造只含 formulas 的简单 bundle item（有 id） */
    private ComponentExportBundle.Item item(String id, String code, String name,
                                             String formulasJson) throws Exception {
        ComponentExportBundle.Item it = new ComponentExportBundle.Item();
        it.id = id;
        it.code = code;
        it.name = name;
        it.componentType = "NORMAL";
        it.columnCount = 0;
        it.status = "ACTIVE";
        it.fields = MAPPER.createArrayNode();
        it.formulas = MAPPER.readTree(formulasJson);
        it.excelColumns = MAPPER.createArrayNode();
        return it;
    }

    /** 构造 bundle（不含 source/dependencies/checksum，commit 服务端不校验这些） */
    private ComponentExportBundle buildBundle(ComponentExportBundle.Item... items) {
        ComponentExportBundle bundle = new ComponentExportBundle();
        bundle.components = List.of(items);
        return bundle;
    }

    /** 从 ImportCommitResult 里找指定 originalCode 对应的 componentId */
    private String findComponentIdByOrigCode(ImportCommitResult result, String origCode) {
        return result.created.stream()
                .filter(ci -> origCode.equals(ci.originalCode))
                .map(ci -> ci.componentId)
                .findFirst()
                .orElse(null);
    }

    /** 查询数据库中指定组件 id 的 formulas 字段值 */
    private String queryFormulas(String componentId) {
        Object raw = em.createNativeQuery(
                "SELECT formulas::text FROM component WHERE id = :id")
                .setParameter("id", UUID.fromString(componentId))
                .getSingleResult();
        return raw == null ? null : raw.toString();
    }
}
