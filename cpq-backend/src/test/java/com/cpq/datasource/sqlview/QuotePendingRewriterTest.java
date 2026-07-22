package com.cpq.datasource.sqlview;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.postgresql.jdbc.PgResultSetMetaData;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0721 B3 自测：QuotePendingRewriter 对真实 component_sql_view 模板的改写正确性。
 *
 * <p>覆盖 B0 POC 声称的场景：中文别名 / 单表 JOIN 非白名单表 / 裸 boolean is_current 谓词 /
 * pgjdbc 基表元数据可追踪锚点列。用真实项目模板（pf_view / z2）而非人造字符串，
 * 与技术总监 B0 POC 方法论一致（"真库 + 项目真实视图模板"）。
 */
@QuarkusTest
class QuotePendingRewriterTest {

    @Inject DataSource dataSource;

    /** pf_view：单表 unit_price + LEFT JOIN 非白名单表 process_master，别名 up/pm，中文别名列。 */
    private static final String PF_VIEW =
        "SELECT\n" +
        "  up.finished_material_no AS hf_part_no,\n" +
        "  up.finished_material_no AS _销售料号,\n" +
        "  up.seq_no AS _项次,\n" +
        "  COALESCE(pm.process_name, up.operation_no) AS _要素名称,\n" +
        "  up.pricing_price AS _单价,\n" +
        "  up.unit AS _单位\n" +
        "FROM unit_price up\n" +
        "  LEFT JOIN process_master pm ON pm.process_no = up.operation_no\n" +
        "WHERE up.system_type = 'QUOTE' AND up.price_type = 'PROCESS' AND up.is_current = true\n" +
        "  AND up.customer_no = :customerCode\n" +
        "ORDER BY up.seq_no";

    /** z2：material_bom_item + 2 个非白名单 LEFT JOIN，裸 boolean is_current（无 "= true"）。 */
    private static final String Z2_VIEW =
        "select \n" +
        "mbt.material_no hf_part_no,\n" +
        "mbt.seq_no 序号,\n" +
        "mbt.component_no 子料号,\n" +
        "coalesce(mm.material_name, mr.name) 子料号名称,\n" +
        "mbt.composition_qty 数量,\n" +
        "mbt.issue_unit 单位\n" +
        " from material_bom_item  mbt\n" +
        " left join material_master mm on mm.material_no = mbt.component_no\n" +
        "left join material_recipe mr on mr.code = mbt.component_no\n" +
        "where mbt.system_type = 'PRICING'\n" +
        "  and mbt.customer_no = '_GLOBAL_'\n" +
        "  and mbt.is_current\n" +
        "\torder by mbt.seq_no";

    /** 无白名单表命中的模板（纯维表 lookup）：安全降级，不注入锚点。 */
    private static final String NO_WHITELIST_VIEW =
        "SELECT code, name FROM process_master WHERE code = :code";

    /** CTE 同名遮蔽：定义了名为 unit_price 的 CTE，其内部 FROM unit_price 才是真表，外层引用 CTE 名不应被替换。 */
    private static final String CTE_SHADOW_VIEW =
        "WITH unit_price AS (SELECT code, pricing_price FROM unit_price WHERE is_current = true) " +
        "SELECT * FROM unit_price";

    @Test
    void pfView_singleTable_anchorInjected_baseTableTracked() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            QuotePendingRewriter.Result r = QuotePendingRewriter.rewrite(PF_VIEW, conn);
            assertTrue(r.anchorInjected, "单表 unit_price 模板应成功注入锚点");
            assertEquals("unit_price", r.primaryTable);
            assertEquals("up", r.primaryAlias);
            assertTrue(r.touchedTables.contains("unit_price"));
            assertFalse(r.touchedTables.contains("process_master"), "非白名单表不应被替换");
            assertTrue(r.sql.contains("__v6_id"), "改写后 SQL 应含锚点列");
            assertTrue(r.sql.contains("pending_quotation_id"), "改写后 SQL 应含 pending 列引用");

            runAndVerifyAnchor(conn, r, "unit_price");
        }
    }

    @Test
    void z2View_bareBooleanIsCurrent_multiJoin_anchorInjected() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            QuotePendingRewriter.Result r = QuotePendingRewriter.rewrite(Z2_VIEW, conn);
            assertTrue(r.anchorInjected, "material_bom_item + 裸 boolean is_current 模板应成功注入锚点");
            assertEquals("material_bom_item", r.primaryTable);
            assertEquals("mbt", r.primaryAlias);
            assertFalse(r.touchedTables.contains("material_master"), "非白名单 JOIN 表不应被替换");
            assertFalse(r.touchedTables.contains("material_recipe"), "非白名单 JOIN 表不应被替换");

            runAndVerifyAnchor(conn, r, "material_bom_item");
        }
    }

    @Test
    void noWhitelistTable_safeDegrade_noAnchor() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            QuotePendingRewriter.Result r = QuotePendingRewriter.rewrite(NO_WHITELIST_VIEW, conn);
            assertFalse(r.anchorInjected, "无白名单表命中应安全降级，不注入锚点");
            assertNull(r.primaryTable);
            assertEquals(NO_WHITELIST_VIEW, r.sql, "无命中时 SQL 应原样返回");
        }
    }

    /** 单表 capacity 的真实模板（组合工艺组件 zh_view）：单表 + 裸 boolean is_current，无 JOIN。 */
    private static final String ZH_VIEW =
        "select\n" +
        "material_no hf_part_no,\n" +
        "seq_no 序号,\n" +
        "process_no 工艺代码,\n" +
        "default_defect_rate 不良率\n" +
        "from capacity where is_current and system_type = 'QUOTE'";

    @Test
    void zhView_capacityTable_anchorInjected() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            QuotePendingRewriter.Result r = QuotePendingRewriter.rewrite(ZH_VIEW, conn);
            assertTrue(r.anchorInjected);
            assertEquals("capacity", r.primaryTable);
            runAndVerifyAnchor(conn, r, "capacity");
        }
    }

    /** UNION ALL 真实模板（其他费用组件 qt_view）：两分支都是 unit_price，列位置不天然对齐 → 安全降级。 */
    private static final String UNION_VIEW =
        "SELECT code hf_part_no, seq_no 项次, cost_type 要素, pricing_price 费用\n" +
        "FROM unit_price up\n" +
        "WHERE system_type = 'QUOTE' AND price_type = 'FINISHED_MATERIAL_OTHER' AND is_current = true\n" +
        "UNION ALL\n" +
        "SELECT finished_material_no hf_part_no, seq_no 项次, cost_type 要素, pricing_price 费用\n" +
        "FROM unit_price up\n" +
        "WHERE system_type = 'QUOTE' AND price_type = 'INCOMING_MATERIAL_OTHER' AND is_current = true";

    @Test
    void unionAll_safeDegrade_noAnchor_butTablesStillTouched() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            QuotePendingRewriter.Result r = QuotePendingRewriter.rewrite(UNION_VIEW, conn);
            assertFalse(r.anchorInjected, "UNION ALL 多分支列位置不天然对齐，应安全降级不注入锚点");
            // 表替换（pending 可见性）仍应对两个分支都生效，即使不参与回填。
            assertTrue(r.sql.contains("pending_quotation_id"), "UNION 两分支的 unit_price 仍应做表替换以保 pending 可见性");
            // LIMIT 0 应能正常执行（两分支列数仍然对齐，未被破坏）。
            try (PreparedStatement ps = conn.prepareStatement(
                    ("SELECT * FROM (" + r.sql + ") _outer LIMIT 0").replaceAll("(?<!:):pq\\b",
                        "'" + UUID.randomUUID() + "'::uuid"));
                 ResultSet rs = ps.executeQuery()) {
                assertNotNull(rs.getMetaData());
            }
        }
    }

    /** GROUP BY 聚合真实模板（电镀成本组件 dj_view）：一行对应多条源行，裸 id 引用非法且无意义 → 安全降级。 */
    private static final String GROUP_BY_VIEW =
        "select up.code, max(up.pricing_price) AS fee\n" +
        "from unit_price up\n" +
        "where up.system_type='PRICING' and up.is_current = true\n" +
        "group by up.code";

    @Test
    void groupBy_aggregation_safeDegrade_noAnchor() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            QuotePendingRewriter.Result r = QuotePendingRewriter.rewrite(GROUP_BY_VIEW, conn);
            assertFalse(r.anchorInjected, "GROUP BY 聚合结果一行对应多源行，应安全降级不注入锚点");
            try (PreparedStatement ps = conn.prepareStatement(
                    ("SELECT * FROM (" + r.sql + ") _outer LIMIT 0").replaceAll("(?<!:):pq\\b",
                        "'" + UUID.randomUUID() + "'::uuid"));
                 ResultSet rs = ps.executeQuery()) {
                assertNotNull(rs.getMetaData(), "GROUP BY 场景表替换后仍应能正常执行（只是不含锚点）");
            }
        }
    }

    @Inject QuoteViewValidationService validationService;

    /**
     * 端到端回归：对当前共享 DB 里全部 ACTIVE component_sql_view + template_sql_view 跑一遍完整校验
     * （与 {@link QuoteViewValidationService#onStartup} 逐字同一实现）。
     *
     * <p>本用例即"启动期硬校验"的可重复自动化验证——若此用例失败，真实 Quarkus 启动也会失败
     * （fail-fast，AC-15）。总数随共享 DB 数据演进浮动，只硬断言 {@code failed==0}。
     */
    @Test
    void allActiveViews_passStartupStyleValidation() {
        QuoteViewValidationService.Snapshot s = validationService.runValidation();
        assertEquals(0, s.failed, () -> "存在改写/校验失败的视图: " + s.failures.stream()
            .map(f -> f.component + "/" + f.view + ": " + f.reason)
            .reduce("", (a, b) -> a + "\n" + b));
        assertTrue(s.total > 0, "校验范围不应为空（至少应命中若干白名单主位视图）");
    }

    @Test
    void cteShadowsRealTable_notReplaced() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            QuotePendingRewriter.Result r = QuotePendingRewriter.rewrite(CTE_SHADOW_VIEW, conn);
            // CTE 内部真实 "FROM unit_price"（depth>0）会被替换；外层 "FROM unit_price"（引用 CTE 名，depth==0）
            // 因命中 cteNames 被跳过 —— 故主位判定应落在 CTE 内部（depth>0 fallback）或判定为 null（视深度扫描顺序）。
            // 关键断言：外层"FROM unit_price"（CTE 引用）字面量必须原样保留，不能被替换成子查询形式。
            assertTrue(r.sql.contains("SELECT * FROM unit_price"),
                "外层对 CTE 名 unit_price 的引用不应被替换成子查询");
        }
    }

    /** 锚点注入后：LIMIT 0 执行 + pgjdbc 基表元数据校验 __v6_id 的 base table/column = (table, id)。 */
    private void runAndVerifyAnchor(Connection conn, QuotePendingRewriter.Result r, String expectedBaseTable) throws Exception {
        String probeSql = "SELECT * FROM (" + r.sql + ") _outer LIMIT 0";
        // 绑定 :customerCode / :pq 等命名占位符为字面量占位（LIMIT 0 不实际取数据，仅验证语法+元数据）。
        String bound = probeSql
            .replaceAll(":pq\\b", "'" + UUID.randomUUID() + "'::uuid")
            .replaceAll(":customerCode\\b", "'__PROBE__'");
        try (PreparedStatement ps = conn.prepareStatement(bound);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            boolean found = false;
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                if ("__v6_id".equalsIgnoreCase(meta.getColumnLabel(i))) {
                    found = true;
                    if (meta instanceof PgResultSetMetaData pgMeta) {
                        String baseTable = pgMeta.getBaseTableName(i);
                        String baseColumn = pgMeta.getBaseColumnName(i);
                        assertEquals(expectedBaseTable, baseTable, "__v6_id 的 pgjdbc 基表应=主位表");
                        assertEquals("id", baseColumn, "__v6_id 的 pgjdbc 基列应=id");
                    }
                }
            }
            assertTrue(found, "LIMIT 0 结果集应含 __v6_id 列");
        }
    }
}
