package com.cpq.task260915;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

import org.junit.jupiter.api.AfterEach;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260915 · 分片 <b>S-B</b>（契约与兼容：AC-7 / AC-9 / AC-10 / AC-11 / AC-12 / AC-13）公共夹具。
 *
 * <h3>🧩 分片隔离口径（{@code test.md §3 S-B}，写死不许改）</h3>
 * <ul>
 *   <li><b>造数前缀</b>：本片建的组件目录一律 {@code RT-SB-260915-*}。别片（S-A）用
 *       {@code RT-SA-260915-*}，<b>本片不读也不写</b>。</li>
 *   <li><b>只断言自己建的那批</b>：所有 SQL 断言必须带 {@code directory_id} 限定。
 *       🚫 禁止 {@code SELECT count(*) FROM component} 这类全局计数 —— 别的测试员造一条数据
 *       就会把本片打红，而且红得像业务回归。</li>
 *   <li><b>清理</b>：{@link #cleanupOwnedDirectories()} 是 {@code @AfterEach}，无论用例成败都执行，
 *       只删本实例 {@link #ownedDirs} 里登记过的目录及其下组件/视图。</li>
 *   <li>🚫 <b>一律不许清库 / TRUNCATE / DROP</b>（{@code CLAUDE.md §3.2} 环境销毁红线，
 *       测试也算，写在 {@code beforeAll} 里同样算）。</li>
 * </ul>
 *
 * <h3>库</h3>
 * {@code mvnw test} 默认 profile → {@code cpq_db_test}（实查 {@code application-test.properties:32}）。
 * 🚫 不许用 {@code DB_NAME=} 覆盖打回共享开发库 {@code cpq_db_0724}。
 */
abstract class Sb260915TestBase {

    protected static final ObjectMapper M = new ObjectMapper();

    /** 本片造数前缀（分片纪律，硬编码不许改）。 */
    protected static final String PREFIX = "RT-SB-260915-";

    protected static final String EXPORT = "/api/cpq/component-directories/{id}/export";
    protected static final String PREVIEW = "/api/cpq/component-directories/{id}/import";
    protected static final String COMMIT = "/api/cpq/component-directories/{id}/import/commit";

    @Inject
    protected EntityManager em;

    @Inject
    protected UserTransaction utx;

    /** 本测试实例建过的目录 id —— 清理只认这个列表，绝不按名字模糊删。 */
    private final List<UUID> ownedDirs = new ArrayList<>();

    // ────────────────────────────────────────────────────────────────────
    // 造数
    // ────────────────────────────────────────────────────────────────────

    /** 建一个本片专属目录，名字形如 {@code RT-SB-260915-AC9-3f2a1b8c}。 */
    protected UUID createDirectory(String tag) {
        UUID id = UUID.randomUUID();
        String name = PREFIX + tag + "-" + id.toString().substring(0, 8);
        inTx(() -> em.createNativeQuery(
                        "INSERT INTO component_directory(id, name, sort_order, created_at) "
                                + "VALUES (:id, :name, 0, NOW())")
                .setParameter("id", id)
                .setParameter("name", name)
                .executeUpdate());
        ownedDirs.add(id);
        return id;
    }

    /** 最小可导出组件（无视图、无公式）。 */
    protected UUID insertSimpleComponent(UUID dirId, String code, String name) {
        return insertComponent(dirId, code, name, "[]", "[]", null, false, null, null, null);
    }

    /**
     * 插组件。{@code treeConfigJson} 传 null 表示该列为 NULL（不是空串 —— AC-2 要求
     * NULL 保持 NULL，本片虽不验往返，但造数不许先把语义弄脏）。
     */
    protected UUID insertComponent(UUID dirId, String code, String name,
                                   String fieldsJson, String formulasJson,
                                   String treeConfigJson, boolean bomRecursiveExpand,
                                   String elementCodeField, String elementPriceField,
                                   String elementCurrencyField) {
        UUID id = UUID.randomUUID();
        inTx(() -> em.createNativeQuery(
                        "INSERT INTO component(id, directory_id, name, code, column_count, fields, formulas, "
                                + "excel_columns, component_type, status, tree_config, bom_recursive_expand, "
                                + "element_code_field, element_price_field, element_currency_field, "
                                + "created_at, updated_at) VALUES "
                                + "(:id, :dir, :name, :code, 0, CAST(:fields AS jsonb), CAST(:formulas AS jsonb), "
                                + "'[]', 'NORMAL', 'ACTIVE', CAST(:tree AS jsonb), :rec, CAST(:ecf AS varchar), CAST(:epf AS varchar), CAST(:ecur AS varchar), "
                                + "NOW(), NOW())")
                .setParameter("id", id)
                .setParameter("dir", dirId)
                .setParameter("name", name)
                .setParameter("code", code)
                .setParameter("fields", fieldsJson)
                .setParameter("formulas", formulasJson)
                .setParameter("tree", treeConfigJson)
                .setParameter("rec", bomRecursiveExpand)
                .setParameter("ecf", elementCodeField)
                .setParameter("epf", elementPriceField)
                .setParameter("ecur", elementCurrencyField)
                .executeUpdate());
        return id;
    }

    /**
     * 给组件挂一条 SQL 视图。{@code builderConfigJson == null} ⇒ 手写视图（导入预览应报
     * {@code NOT_BUILDER}）；非 null ⇒ 取数配置器视图，{@code builder_version} 一并写
     * （两列成对写入：{@code builder_version IS NOT NULL} 是 builder 判据，缺它分支①永不成立）。
     */
    protected UUID insertSqlView(UUID componentId, String viewName, String sqlTemplate,
                                 String builderConfigJson, Integer builderVersion) {
        UUID id = UUID.randomUUID();
        inTx(() -> em.createNativeQuery(
                        "INSERT INTO component_sql_view(id, component_id, sql_view_name, sql_template, "
                                + "declared_columns, required_variables, scope, status, builder_config, "
                                + "builder_version, created_at, updated_at) VALUES "
                                + "(:id, :cid, :vn, :tpl, '[]', '{}', 'COMPONENT', 'ACTIVE', "
                                + "CAST(:bc AS jsonb), CAST(:bv AS integer), NOW(), NOW())")
                .setParameter("id", id)
                .setParameter("cid", componentId)
                .setParameter("vn", viewName)
                .setParameter("tpl", sqlTemplate)
                .setParameter("bc", builderConfigJson)
                .setParameter("bv", builderVersion)
                .executeUpdate());
        return id;
    }

    // ────────────────────────────────────────────────────────────────────
    // 清理（分片纪律：finally 里清掉自己造的那批）
    // ────────────────────────────────────────────────────────────────────

    @AfterEach
    void cleanupOwnedDirectories() {
        for (UUID dir : ownedDirs) {
            try {
                inTx(() -> {
                    em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id IN "
                                    + "(SELECT id FROM component WHERE directory_id = :dir)")
                            .setParameter("dir", dir).executeUpdate();
                    em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                            .setParameter("dir", dir).executeUpdate();
                    em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                            .setParameter("id", dir).executeUpdate();
                    return 0;
                });
            } catch (RuntimeException e) {
                // 清理失败不许吞掉：留在库里的脏目录必须进 test-report.md 的待回收清单。
                System.err.println("[RT-SB-260915] ⚠️ 目录清理失败，需人工回收: " + dir + " —— " + e);
            }
        }
        ownedDirs.clear();
    }

    // ────────────────────────────────────────────────────────────────────
    // 查询（一律带 directory_id 限定，禁止全局计数）
    // ────────────────────────────────────────────────────────────────────

    protected long componentCountIn(UUID dirId) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM component WHERE directory_id = :dir")
                .setParameter("dir", dirId).getSingleResult()).longValue();
    }

    /** 按目录名查 id —— {@code test.md §4} 要求「开跑前按名字查真实 id，不要硬编码」。 */
    protected UUID directoryIdByName(String name) {
        List<?> rows = em.createNativeQuery("SELECT id FROM component_directory WHERE name = :n")
                .setParameter("n", name).getResultList();
        assertTrue(rows.size() == 1,
                "基准目录「" + name + "」在 cpq_db_test 里应恰好 1 个，实际 " + rows.size()
                        + " 个 —— 环境与 test.md §4 记载不符，停下来报主线，不要改断言绕过");
        Object o = rows.get(0);
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    // ────────────────────────────────────────────────────────────────────
    // 端点
    // ────────────────────────────────────────────────────────────────────

    protected Response exportDirectory(UUID dirId) {
        return given().when().get(EXPORT, dirId).then().extract().response();
    }

    protected Response preview(UUID dirId, String bundleJson, String query) {
        return given().contentType(ContentType.JSON).body(bundleJson)
                .when().post(PREVIEW + query, dirId).then().extract().response();
    }

    protected Response commit(UUID dirId, String bundleJson, String query) {
        return given().contentType(ContentType.JSON).body(bundleJson)
                .when().post(COMMIT + query, dirId).then().extract().response();
    }

    // ────────────────────────────────────────────────────────────────────
    // 老包素材（AC-10 / AC-11 / AC-13 的输入）
    // ────────────────────────────────────────────────────────────────────

    /** 素材绝对路径：从 {@code user.dir}（surefire 下 = cpq-backend/）逐级上溯找 worktree 根。 */
    protected static Path legacyBundlePath() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++, cur = cur.getParent()) {
            Path p = cur.resolve("dev-docs")
                    .resolve("task-260915-组件导出导入往返保真")
                    .resolve("素材")
                    .resolve("用户原始导出包-bundleVersion1.0.json");
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException("找不到老包素材 用户原始导出包-bundleVersion1.0.json"
                + "（从 " + Path.of("").toAbsolutePath() + " 上溯 6 级）"
                + " —— AC-10/AC-11 没有输入就会空跑，必须停下来报主线，不要跳过");
    }

    protected static ObjectNode loadLegacyBundle() {
        try {
            JsonNode n = M.readTree(Files.readString(legacyBundlePath()));
            return (ObjectNode) n;
        } catch (Exception e) {
            throw new IllegalStateException("老包素材读取失败: " + e, e);
        }
    }

    // ────────────────────────────────────────────────────────────────────

    protected interface TxBody {
        int run() throws Exception;
    }

    protected void inTx(TxBody body) {
        try {
            utx.begin();
            em.joinTransaction();
            body.run();
            utx.commit();
        } catch (Exception e) {
            try {
                utx.rollback();
            } catch (Exception ignored) {
                // rollback 失败不覆盖原始异常
            }
            throw new IllegalStateException("造数/清理事务失败: " + e, e);
        }
    }
}
