package com.cpq.component.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD 集成测试：G4 目录级存量引用补救 remapImportedRefsInDirectory。
 *
 * <p>场景：已导入的副本组件(X__imp1)的 formulas 里仍指向 dir 外的源组件(Y原件)，
 * 服务应扫描并重映射为同目录内的副本(Y__imp1)。
 *
 * <p>测试用例：
 * <ul>
 *   <li>TC-G4-1: dryRun=false — cross_tab_ref.source 从 Y原id → Y__imp1 id</li>
 *   <li>TC-G4-2: dryRun=false — component_subtotal.component_code 从 Y原code → Y__imp1 code</li>
 *   <li>TC-G4-3: dryRun=true  — 不写库，只返回清单（formulas 不变）</li>
 *   <li>TC-G4-4: 引用已在目录内 — 不重映射（已正确）</li>
 *   <li>TC-G4-5: 引用目录外但无副本 — 记为 unresolved，不重映射</li>
 *   <li>TC-G4-6: 同 base 多副本 (__imp1/__imp2) — 按 code 升序取第一个</li>
 * </ul>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("G4 目录级存量引用补救 remapImportedRefsInDirectory")
class DirectoryRefRemapServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    ComponentImportService importService;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    /** 测试目录 id（每个 TC 不同，隔离） */
    private UUID dirId;
    /** 目录外源组件 Y 的 id */
    private UUID outerYId;
    /** 目录外源组件 Y 的 code（作为 base） */
    private String outerYCode;
    /** 一个独立辅助目录，放 outer 组件 */
    private UUID outerDirId;

    @BeforeEach
    void setup() throws Exception {
        dirId     = UUID.randomUUID();
        outerYId  = UUID.randomUUID();
        outerDirId = UUID.randomUUID();
        outerYCode = "COMP-G4Y-" + dirId.toString().substring(0, 8);

        utx.begin();
        em.joinTransaction();

        // 测试目录
        em.createNativeQuery(
                "INSERT INTO component_directory(id, name, sort_order, created_at) " +
                "VALUES (:id, 'G4TestDir', 0, NOW())")
                .setParameter("id", dirId)
                .executeUpdate();

        // outer 辅助目录
        em.createNativeQuery(
                "INSERT INTO component_directory(id, name, sort_order, created_at) " +
                "VALUES (:id, 'G4OuterDir', 0, NOW())")
                .setParameter("id", outerDirId)
                .executeUpdate();

        // 目录外源组件 Y（原件，code = outerYCode，不在测试目录里）
        em.createNativeQuery(
                "INSERT INTO component(id, directory_id, name, code, column_count, fields, formulas, " +
                "excel_columns, component_type, status, created_at, updated_at) " +
                "VALUES (:id, :dir, 'Y原件', :code, 0, '[]', '[]', '[]', 'NORMAL', 'ACTIVE', NOW(), NOW())")
                .setParameter("id", outerYId)
                .setParameter("dir", outerDirId)
                .setParameter("code", outerYCode)
                .executeUpdate();

        utx.commit();
    }

    @AfterEach
    void cleanup() throws Exception {
        utx.begin();
        em.joinTransaction();
        // 清理测试目录及其所有组件
        em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                .setParameter("dir", dirId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                .setParameter("id", dirId)
                .executeUpdate();
        // 清理 outer 目录及其组件
        em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                .setParameter("dir", outerDirId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                .setParameter("id", outerDirId)
                .executeUpdate();
        utx.commit();
    }

    // ── TC-G4-1: cross_tab_ref.source 重映射 ───────────────────────────────

    @Test
    @Order(1)
    @DisplayName("TC-G4-1: dryRun=false — cross_tab_ref.source 从 Y原id → Y副本 id")
    void crossTabRef_source_remapped_to_dir_copy() throws Exception {
        // 在测试目录里建 Y__imp1（Y 的副本）
        UUID yImpId = UUID.randomUUID();
        String yImpCode = outerYCode + "__imp1";

        // X__imp1：formulas 里 cross_tab_ref.source = Y原件 id（错误引用）
        String xFormulas = """
                [{"name":"引用Y","expression":[{
                  "type":"cross_tab_ref",
                  "agg":"SUM",
                  "source":"%s",
                  "targetExpr":[{"type":"field","value":"数量","source":"%s"}],
                  "match":[]
                }]}]""".formatted(outerYId, outerYId);
        UUID xImpId = UUID.randomUUID();
        String xImpCode = "COMP-G4X-" + dirId.toString().substring(0, 8) + "__imp1";

        utx.begin();
        em.joinTransaction();
        insertComponent(yImpId, dirId, "Y副本", yImpCode, "[]");
        insertComponent(xImpId, dirId, "X副本", xImpCode, xFormulas);
        utx.commit();

        // 执行补救（dryRun=false，应写库）
        ComponentImportService.DirRemapResult result =
                importService.remapImportedRefsInDirectory(dirId, false);

        assertNotNull(result);
        // X__imp1 应有重映射记录
        ComponentImportService.DirRemapResult.ComponentResult xResult =
                findCompResult(result, xImpCode);
        assertNotNull(xResult, "X副本应在结果中");
        assertFalse(xResult.remapped.isEmpty(), "X副本应有 remapped 条目");
        assertTrue(xResult.unresolved.isEmpty(), "X副本不应有 unresolved");

        // 验证库里 X__imp1 的 formulas 已更新
        String formulasAfter = queryFormulas(xImpId);
        assertFalse(formulasAfter.contains(outerYId.toString()),
                "X副本 formulas 不应再含 Y原件 id，实际: " + formulasAfter);
        assertTrue(formulasAfter.contains(yImpId.toString()),
                "X副本 formulas 应含 Y副本 id，实际: " + formulasAfter);
    }

    // ── TC-G4-2: component_subtotal.component_code 重映射 ─────────────────

    @Test
    @Order(2)
    @DisplayName("TC-G4-2: dryRun=false — component_subtotal.component_code 从 Y原code → Y副本 code")
    void componentSubtotal_code_remapped_to_dir_copy() throws Exception {
        UUID yImpId = UUID.randomUUID();
        String yImpCode = outerYCode + "__imp1";

        // X__imp1：formulas 里 component_subtotal.component_code = Y原code（错误引用）
        String xFormulas = """
                [{"name":"Y小计","expression":[{
                  "type":"component_subtotal",
                  "component_code":"%s",
                  "tab_name":"材料成本"
                }]}]""".formatted(outerYCode);
        UUID xImpId = UUID.randomUUID();
        String xImpCode = "COMP-G4X-" + dirId.toString().substring(0, 8) + "__imp1";

        utx.begin();
        em.joinTransaction();
        insertComponent(yImpId, dirId, "Y副本", yImpCode, "[]");
        insertComponent(xImpId, dirId, "X副本", xImpCode, xFormulas);
        utx.commit();

        ComponentImportService.DirRemapResult result =
                importService.remapImportedRefsInDirectory(dirId, false);

        ComponentImportService.DirRemapResult.ComponentResult xResult =
                findCompResult(result, xImpCode);
        assertNotNull(xResult, "X副本应在结果中");
        assertFalse(xResult.remapped.isEmpty(), "X副本应有 remapped 条目");

        String formulasAfter = queryFormulas(xImpId);
        assertFalse(formulasAfter.contains("\"component_code\":\"" + outerYCode + "\""),
                "X副本 formulas 不应再含 Y原 code，实际: " + formulasAfter);
        assertTrue(formulasAfter.contains(yImpCode),
                "X副本 formulas 应含 Y副本 code，实际: " + formulasAfter);
    }

    // ── TC-G4-3: dryRun=true 不写库 ────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("TC-G4-3: dryRun=true — 只返回清单，不修改数据库")
    void dryRun_true_does_not_modify_db() throws Exception {
        UUID yImpId = UUID.randomUUID();
        String yImpCode = outerYCode + "__imp1";

        String xFormulas = """
                [{"name":"引用Y","expression":[{
                  "type":"cross_tab_ref",
                  "agg":"SUM",
                  "source":"%s",
                  "targetExpr":[],
                  "match":[]
                }]}]""".formatted(outerYId);
        UUID xImpId = UUID.randomUUID();
        String xImpCode = "COMP-G4X-" + dirId.toString().substring(0, 8) + "__imp1";

        utx.begin();
        em.joinTransaction();
        insertComponent(yImpId, dirId, "Y副本", yImpCode, "[]");
        insertComponent(xImpId, dirId, "X副本", xImpCode, xFormulas);
        utx.commit();

        ComponentImportService.DirRemapResult result =
                importService.remapImportedRefsInDirectory(dirId, true);

        // 清单里应有 remapped 条目
        ComponentImportService.DirRemapResult.ComponentResult xResult =
                findCompResult(result, xImpCode);
        assertNotNull(xResult, "X副本应在 dryRun 清单中");
        assertFalse(xResult.remapped.isEmpty(), "dryRun 清单应描述将要重映射的内容");

        // 库里 formulas 不应改变（dryRun）
        String formulasAfter = queryFormulas(xImpId);
        assertTrue(formulasAfter.contains(outerYId.toString()),
                "dryRun=true 时库里 formulas 不应改变，应仍含 Y原件 id，实际: " + formulasAfter);
    }

    // ── TC-G4-4: 引用已在目录内 — 不重映射 ────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("TC-G4-4: X 引用同目录内 Y — 不重映射（已正确）")
    void intra_dir_ref_not_remapped() throws Exception {
        UUID yInDirId = UUID.randomUUID();
        String yInDirCode = "COMP-G4YIN-" + dirId.toString().substring(0, 8);

        // X 引用的是同目录内的 Y（已正确）
        String xFormulas = """
                [{"name":"引用同目录Y","expression":[{
                  "type":"cross_tab_ref",
                  "agg":"SUM",
                  "source":"%s",
                  "targetExpr":[],
                  "match":[]
                }]}]""".formatted(yInDirId);
        UUID xInDirId = UUID.randomUUID();
        String xInDirCode = "COMP-G4XIN-" + dirId.toString().substring(0, 8);

        utx.begin();
        em.joinTransaction();
        insertComponent(yInDirId, dirId, "Y目录内", yInDirCode, "[]");
        insertComponent(xInDirId, dirId, "X目录内", xInDirCode, xFormulas);
        utx.commit();

        ComponentImportService.DirRemapResult result =
                importService.remapImportedRefsInDirectory(dirId, false);

        ComponentImportService.DirRemapResult.ComponentResult xResult =
                findCompResult(result, xInDirCode);
        // X 的引用已指向目录内 Y，不应有任何重映射
        if (xResult != null) {
            assertTrue(xResult.remapped.isEmpty(),
                    "目录内引用不应被重映射，remapped 应为空，实际: " + xResult.remapped);
        }

        // 库里 formulas 保持原样
        String formulasAfter = queryFormulas(xInDirId);
        assertTrue(formulasAfter.contains(yInDirId.toString()),
                "引用目录内 Y，formulas 应保持原样，实际: " + formulasAfter);
    }

    // ── TC-G4-5: 引用 dir 外但无副本 — 记 unresolved ───────────────────────

    @Test
    @Order(5)
    @DisplayName("TC-G4-5: 引用目录外但目录内无副本 — 记为 unresolved，不重映射")
    void unresolvable_ref_recorded_as_unresolved() throws Exception {
        // 没有在测试目录里建 Y 的副本
        String xFormulas = """
                [{"name":"引用外部Y","expression":[{
                  "type":"cross_tab_ref",
                  "agg":"SUM",
                  "source":"%s",
                  "targetExpr":[],
                  "match":[]
                }]}]""".formatted(outerYId);
        UUID xImpId = UUID.randomUUID();
        String xImpCode = "COMP-G4XONLY-" + dirId.toString().substring(0, 8) + "__imp1";

        utx.begin();
        em.joinTransaction();
        insertComponent(xImpId, dirId, "X副本仅有", xImpCode, xFormulas);
        utx.commit();

        ComponentImportService.DirRemapResult result =
                importService.remapImportedRefsInDirectory(dirId, false);

        ComponentImportService.DirRemapResult.ComponentResult xResult =
                findCompResult(result, xImpCode);
        assertNotNull(xResult, "X副本应在结果中");
        assertTrue(xResult.remapped.isEmpty(), "无副本时 remapped 应为空");
        assertFalse(xResult.unresolved.isEmpty(), "无副本时应记录 unresolved");
        assertTrue(xResult.unresolved.get(0).contains(outerYId.toString()),
                "unresolved 应包含 Y原件 id，实际: " + xResult.unresolved.get(0));

        // 库里 formulas 不变
        String formulasAfter = queryFormulas(xImpId);
        assertTrue(formulasAfter.contains(outerYId.toString()),
                "unresolved 时 formulas 不应改变，实际: " + formulasAfter);
    }

    // ── TC-G4-6: 同 base 多副本 — 按 code 升序取第一个 ─────────────────────

    @Test
    @Order(6)
    @DisplayName("TC-G4-6: 同 base 多副本(__imp1/__imp2) — 按 code 升序取第一个")
    void multiple_copies_same_base_picks_first_by_code() throws Exception {
        UUID yImp1Id = UUID.randomUUID();
        UUID yImp2Id = UUID.randomUUID();
        String yImp1Code = outerYCode + "__imp1";
        String yImp2Code = outerYCode + "__imp2";

        String xFormulas = """
                [{"name":"引用Y","expression":[{
                  "type":"cross_tab_ref",
                  "agg":"SUM",
                  "source":"%s",
                  "targetExpr":[],
                  "match":[]
                }]}]""".formatted(outerYId);
        UUID xImpId = UUID.randomUUID();
        String xImpCode = "COMP-G4X-" + dirId.toString().substring(0, 8) + "__imp1";

        utx.begin();
        em.joinTransaction();
        // 建两个副本
        insertComponent(yImp1Id, dirId, "Y副本1", yImp1Code, "[]");
        insertComponent(yImp2Id, dirId, "Y副本2", yImp2Code, "[]");
        insertComponent(xImpId,  dirId, "X副本",  xImpCode,  xFormulas);
        utx.commit();

        ComponentImportService.DirRemapResult result =
                importService.remapImportedRefsInDirectory(dirId, false);

        ComponentImportService.DirRemapResult.ComponentResult xResult =
                findCompResult(result, xImpCode);
        assertNotNull(xResult, "X副本应在结果中");
        assertFalse(xResult.remapped.isEmpty(), "应有重映射");

        String formulasAfter = queryFormulas(xImpId);
        // 按 code 升序，__imp1 < __imp2，所以取 yImp1Id
        assertTrue(formulasAfter.contains(yImp1Id.toString()),
                "应取 code 升序第一个副本(yImp1Id)，实际: " + formulasAfter);
        assertFalse(formulasAfter.contains(yImp2Id.toString()),
                "不应取 yImp2Id，实际: " + formulasAfter);
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────────

    private void insertComponent(UUID id, UUID dir, String name, String code, String formulas) {
        // 用位置参数 (?1…?6) 避免 Hibernate 把 ::jsonb 误识别为命名参数
        em.createNativeQuery(
                "INSERT INTO component(id, directory_id, name, code, column_count, fields, formulas, " +
                "excel_columns, component_type, status, created_at, updated_at) " +
                "VALUES (?1, ?2, ?3, ?4, 0, '[]', CAST(?5 AS jsonb), '[]', 'NORMAL', 'ACTIVE', NOW(), NOW())")
                .setParameter(1, id)
                .setParameter(2, dir)
                .setParameter(3, name)
                .setParameter(4, code)
                .setParameter(5, formulas)
                .executeUpdate();
    }

    private String queryFormulas(UUID componentId) {
        Object raw = em.createNativeQuery(
                "SELECT formulas::text FROM component WHERE id = :id")
                .setParameter("id", componentId)
                .getSingleResult();
        return raw == null ? null : raw.toString();
    }

    private ComponentImportService.DirRemapResult.ComponentResult findCompResult(
            ComponentImportService.DirRemapResult result, String code) {
        if (result == null || result.components == null) return null;
        return result.components.stream()
                .filter(r -> code.equals(r.code))
                .findFirst()
                .orElse(null);
    }
}
