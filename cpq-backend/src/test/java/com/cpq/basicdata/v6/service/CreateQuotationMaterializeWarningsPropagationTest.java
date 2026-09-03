package com.cpq.basicdata.v6.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260825 大单量导入建单性能 —— 缺口 2（接口层 {@code cardValuesReady}/{@code warnings} 传播）。
 *
 * <p><b>为什么需要单独一个文件</b>：{@code T-9}（{@code EnsureCardValuesPartialBatchRecoveryTest}）
 * 直调 {@link CardSnapshotService#ensureCardValuesDetailed}，验的是"分批容错"这一层本身；本测试
 * 直调编排入口 {@link CreateQuotationMaterializer#materialize}，验的是<b>再往上一层</b>——批失败的
 * 信号有没有正确传播到 {@code CommitResult}（建单接口最终吐给前端的那个对象）。两者验的是同一条
 * 故障不同层级的可观测性，缺一不可。
 *
 * <p><b>机制</b>：与 T-9 同款——用另一条真实 JDBC 连接对目标行加 {@code FOR UPDATE} 且不提交，
 * 把 {@code cpq.ensure-card-values-chunk-size} 调到 3，制造"某一批因拿不到行锁而失败"的真实场景，
 * 然后调 {@link CreateQuotationMaterializer#materialize}（内部依次跑 ①snapshotQuotation
 * ②ensureStructure ③ensureCardValuesDetailed ④ensureExcelValues + fillStatus）。
 *
 * <p><b>读 src/main 的授权范围</b>：主线 2026-08-28 明确解除限制、仅限
 * {@link CreateQuotationMaterializer} 与 {@link V6QuotationCommitService.CommitResult} 两个类，
 * 用于看清 warnings 的实际拼装文案再写断言。读到的关键事实：
 * <ul>
 *   <li>{@code materialize} 在 {@code ensureResult.failedBatches > 0} 时会追加一条<b>格式固定</b>的
 *       warning：{@code "卡片值物化部分未完成：%d 批（共 %d 行）未完成，将在下次打开/轮询时自动补算"}
 *       ——本测试用正则精确解析这条消息里的行数，并与 DB 里真实的缺失行数交叉核对（不是只判断
 *       "非空"，也不是只判断"包含数字"）。</li>
 *   <li>{@code fillStatus} 随后会重新读库判定 {@code cardValuesReady}：只要有一行 quote_card_values
 *       仍为 NULL 或含失败哨兵，就置 {@code false} 并追加第二条泛化 warning。</li>
 *   <li>批失败不会被外层 {@code catch(Exception e)} 吞掉——{@code ensureCardValuesDetailed} 内部
 *       已经按批 try/catch，不向上抛，所以本场景走的是"批失败仍报"这条路径，不是"整体异常"那条路径。</li>
 * </ul>
 */
@QuarkusTest
class CreateQuotationMaterializeWarningsPropagationTest {

    private static final String TAG = "T260825GAP2";

    @Inject EntityManager em;
    @Inject DataSource dataSource;
    @Inject CreateQuotationMaterializer materializer;

    private UUID componentId, templateId, quotationId;
    private final List<UUID> lineIds = new ArrayList<>();

    private static UUID toUUID(Object o) {
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    @AfterEach
    void cleanup() {
        if (quotationId == null && templateId == null && componentId == null) return;
        QuarkusTransaction.requiringNew().run(() -> {
            if (quotationId != null) {
                em.createNativeQuery("DELETE FROM quotation_view_structure WHERE quotation_id = :q")
                        .setParameter("q", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN " +
                        "(SELECT id FROM quotation_line_item WHERE quotation_id = :q)")
                        .setParameter("q", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :q")
                        .setParameter("q", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation WHERE id = :q")
                        .setParameter("q", quotationId).executeUpdate();
            }
            if (templateId != null) {
                em.createNativeQuery("DELETE FROM template_component_snapshot WHERE template_id = :t")
                        .setParameter("t", templateId).executeUpdate();
                em.createNativeQuery("DELETE FROM template_component WHERE template_id = :t")
                        .setParameter("t", templateId).executeUpdate();
                em.createNativeQuery("DELETE FROM template WHERE id = :t")
                        .setParameter("t", templateId).executeUpdate();
            }
            if (componentId != null) {
                em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = :c")
                        .setParameter("c", componentId).executeUpdate();
                em.createNativeQuery("DELETE FROM component WHERE id = :c")
                        .setParameter("c", componentId).executeUpdate();
            }
        });
    }

    /** 9 行报价单,0 行 $view 驱动(WHERE FALSE),quotation_line_component_data 预插空数组快照行 ——
     *  与 EnsureCardValuesPartialBatchRecoveryTest#buildFixture 同款手法(经其验证过对
     *  materialize() 的①②③步均安全)。 */
    private void buildFixture() {
        componentId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO component (id, name, code, fields, formulas, data_driver_path, created_at, updated_at) " +
                    "VALUES (:id, :name, :code, CAST(:fields AS jsonb), CAST('[]' AS jsonb), :ddp, now(), now())")
                    .setParameter("id", componentId)
                    .setParameter("name", TAG + "-驱动组件")
                    .setParameter("code", TAG + "-" + componentId.toString().substring(0, 8))
                    .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                    .setParameter("ddp", "$" + TAG.toLowerCase() + "_view")
                    .executeUpdate();
            em.createNativeQuery("INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, declared_columns, created_at, updated_at) " +
                    "VALUES (:id, :cid, :vn, :tpl, '[]', now(), now())")
                    .setParameter("id", UUID.randomUUID())
                    .setParameter("cid", componentId)
                    .setParameter("vn", TAG.toLowerCase() + "_view")
                    .setParameter("tpl", "SELECT '" + TAG + "-P1'::text AS hf_part_no, 'x'::text AS \"名称\" WHERE FALSE")
                    .executeUpdate();
        });

        templateId = UUID.randomUUID();
        String snapshot = "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + componentId +
                "\",\"componentName\":\"" + TAG + "-驱动组件\",\"componentCode\":\"" + TAG +
                "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + TAG + "页签\",\"sortOrder\":0," +
                "\"fields\":[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}],\"formulas\":[]," +
                "\"data_driver_path\":\"$" + TAG.toLowerCase() + "_view\"}]";
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO template (id, template_series_id, name, template_kind, status, components_snapshot, created_at, updated_at) " +
                    "VALUES (:id, :tsid, :name, 'QUOTATION', 'PUBLISHED', CAST(:snap AS jsonb), now(), now())")
                    .setParameter("id", templateId)
                    .setParameter("tsid", UUID.randomUUID())
                    .setParameter("name", TAG + "-模板")
                    .setParameter("snap", snapshot)
                    .executeUpdate();
            UUID templateComponentId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO template_component (id, template_id, component_id, sort_order, tab_name, created_at) " +
                    "VALUES (:id, :tid, :cid, 0, :tab, now())")
                    .setParameter("id", templateComponentId).setParameter("tid", templateId).setParameter("cid", componentId)
                    .setParameter("tab", TAG + "页签").executeUpdate();
            em.createNativeQuery("INSERT INTO template_component_snapshot (template_id,template_component_id,component_id," +
                    "sort_order,tab_name,component_name,component_code,component_type,fields,formulas," +
                    "element_code_field,element_price_field,element_currency_field) " +
                    "VALUES (:templateId,:templateComponentId,:componentId,0,:tabName," +
                    ":componentName,:componentCode,'NORMAL',CAST(:fields AS jsonb),CAST('[]' AS jsonb),NULL,NULL,NULL)")
                    .setParameter("templateId", templateId).setParameter("templateComponentId", templateComponentId)
                    .setParameter("componentId", componentId).setParameter("tabName", TAG + "页签")
                    .setParameter("componentName", TAG + "-驱动组件").setParameter("componentCode", TAG)
                    .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                    .executeUpdate();
        });

        quotationId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<Object> customers = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
            assertFalse(customers.isEmpty(), "DB 无任何 customer,无法建 fixture");
            UUID customerId = toUUID(customers.get(0));
            @SuppressWarnings("unchecked")
            List<Object> users = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
            assertFalse(users.isEmpty(), "DB 无任何 user,无法建 fixture");
            UUID salesRepId = toUUID(users.get(0));

            em.createNativeQuery("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                    "customer_template_id, tax_rate, tax_amount, created_at, updated_at) " +
                    "VALUES (:id, :qn, :cid, :name, :srid, 'DRAFT', :tid, 0, 0, now(), now())")
                    .setParameter("id", quotationId)
                    .setParameter("qn", TAG + "-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId)
                    .setParameter("name", TAG)
                    .setParameter("srid", salesRepId)
                    .setParameter("tid", templateId)
                    .executeUpdate();

            for (int i = 0; i < 9; i++) {
                UUID lid = UUID.randomUUID();
                em.createNativeQuery("INSERT INTO quotation_line_item (id, quotation_id, template_id, " +
                        "product_part_no_snapshot, sort_order, created_at) VALUES (:id, :qid, :tid, :pn, :so, now())")
                        .setParameter("id", lid).setParameter("qid", quotationId).setParameter("tid", templateId)
                        .setParameter("pn", TAG + "-P" + i).setParameter("so", i)
                        .executeUpdate();
                em.createNativeQuery("INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, " +
                        "row_data, snapshot_rows) VALUES (:id, :lid, :cid, :tab, '[]', '[]')")
                        .setParameter("id", UUID.randomUUID()).setParameter("lid", lid).setParameter("cid", componentId)
                        .setParameter("tab", TAG + "页签").executeUpdate();
                lineIds.add(lid);
            }
        });
    }

    private long countMissing() {
        Number n = (Number) em.createNativeQuery(
                "SELECT count(*) FROM quotation_line_item WHERE quotation_id = :q AND quote_card_values IS NULL")
                .setParameter("q", quotationId).getSingleResult();
        return n.longValue();
    }

    /** 与 EnsureCardValuesPartialBatchRecoveryTest 同款:另一条真实连接加 FOR UPDATE 且不提交。 */
    private Connection lockRowsForUpdate(List<UUID> targetIds) throws Exception {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        Array arr = conn.createArrayOf("uuid", targetIds.toArray());
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM quotation_line_item WHERE id = ANY(?) FOR UPDATE")) {
            ps.setArray(1, arr);
            try (ResultSet rs = ps.executeQuery()) {
                int n = 0;
                while (rs.next()) n++;
                assertEquals(targetIds.size(), n, "FOR UPDATE 应命中全部目标行(非空验证,证明锁真的加在了正确的行上)");
            }
        }
        return conn;
    }

    private void assertLockActuallyHeld(Connection lockConn) throws Exception {
        long pid;
        try (PreparedStatement ps = lockConn.prepareStatement("SELECT pg_backend_pid()");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            pid = rs.getLong(1);
        }
        try (Connection probe = dataSource.getConnection();
             PreparedStatement ps = probe.prepareStatement(
                     "SELECT count(*) FROM pg_locks WHERE pid = ? AND locktype IN ('tuple','transactionid')")) {
            ps.setLong(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getLong(1) > 0, "锁持有连接(pid=" + pid + ")应在 pg_locks 里查到 tuple/transactionid 锁");
            }
        }
    }

    @Test
    @DisplayName("缺口2: 批失败时 materialize() 回填 cardValuesReady=false + warnings 精确指认失败行数")
    void batchFailure_propagatesCardValuesReadyFalse_andWarningsMentionFailedRows() throws Exception {
        buildFixture();
        assertEquals(9, lineIds.size(), "前置数据非空:应建出 9 行 line item");

        List<UUID> lockedIds = lineIds.subList(3, 6);
        String prevChunk = System.getProperty("cpq.ensure-card-values-chunk-size");
        Connection lockConn = null;
        try {
            System.setProperty("cpq.ensure-card-values-chunk-size", "3");
            lockConn = lockRowsForUpdate(lockedIds);
            assertLockActuallyHeld(lockConn);

            V6QuotationCommitService.CommitResult r =
                    new V6QuotationCommitService.CommitResult(quotationId, UUID.randomUUID(), 9);
            // 建单接口的真实默认值(BasicDataImportV6Resource 同步段就是这么初始化的) —— 物化前
            // cardValuesReady 恒为 false/warnings 恒为空,物化完才回填,这是本测试要验的落点。
            assertFalse(r.cardValuesReady, "CommitResult 构造后 cardValuesReady 默认应为 false(物化前)");
            assertTrue(r.warnings.isEmpty(), "CommitResult 构造后 warnings 默认应为空(物化前)");

            materializer.materialize(r);

            long missingAfterCall = countMissing();
            System.out.printf("[gap2] cardValuesReady=%b warnings=%s missingAfterCall=%d%n",
                    r.cardValuesReady, r.warnings, missingAfterCall);

            // 断言1: cardValuesReady 必须为 false(批失败导致仍有行缺失,fillStatus 应权威判定为未就绪)
            assertTrue(missingAfterCall > 0,
                    "应有缺失行(锁生效的直接证据)——若为 0 说明本次实验没触发到被锁的批");
            assertFalse(r.cardValuesReady,
                    "存在缺失行时 cardValuesReady 必须为 false——不许因为'大部分行成功'就报 true");

            // 断言2: warnings 非空,且至少一条精确提到失败的批数/行数(不是只判断非空)
            assertFalse(r.warnings.isEmpty(), "批失败时 warnings 不应为空");
            Pattern p = Pattern.compile("卡片值物化部分未完成：(\\d+) 批（共 (\\d+) 行）未完成");
            String batchWarning = null;
            Matcher matched = null;
            for (String w : r.warnings) {
                Matcher m = p.matcher(w);
                if (m.find()) { batchWarning = w; matched = m; break; }
            }
            assertNotNull(batchWarning,
                    "warnings 里应有一条精确匹配'卡片值物化部分未完成：N 批（共 M 行）未完成'格式的消息" +
                    "(不是只有泛化的'部分行卡片值未就绪'那条,必须能指认失败的批数/行数),实际 warnings=" + r.warnings);
            int warnedBatches = Integer.parseInt(matched.group(1));
            int warnedRows = Integer.parseInt(matched.group(2));
            assertTrue(warnedBatches > 0, "warning 里的批数应 > 0,实际=" + warnedBatches);
            assertTrue(warnedRows > 0, "warning 里的行数应 > 0,实际=" + warnedRows);
            // 交叉核对:warning 里报的行数必须与 DB 里真实缺失行数一致(不是"提到了数字"就算数,数字本身要对)
            assertEquals(missingAfterCall, warnedRows,
                    "warning 里报的失败行数(" + warnedRows + ")必须与 DB 里真实缺失行数(" + missingAfterCall +
                    ")一致——这是'内容能指认失败批'的硬判据,不是宽松的字符串包含检查");

            // 断言3(适应性断言,不假定 ④ensureExcelValues 在锁未释放期间的具体行为——那不是本测试
            // 要覆盖的范围): materialize() 顶层 try/catch 的降级路径必须"二选一都行,但必须有一个"——
            // 要么走到 fillStatus 追加泛化兜底"部分行卡片值未就绪..."(④ 未受锁影响或已容错跳过),
            // 要么因 ④ 撞同一把锁而抛出、被外层 catch 兜住追加"卡片值物化失败: ..."(降级未被吞掉,
            // 只是走了另一条同样合法的降级支路)。两种情况下 r.cardValuesReady 都必须是 false
            // (已在断言1验证过),这里只额外确认"确实有第二条信号,不是只有批次那一条"。
            boolean hasFillStatusWarning = r.warnings.stream().anyMatch(w -> w.contains("部分行卡片值未就绪"));
            boolean hasOuterCatchWarning = r.warnings.stream().anyMatch(w -> w.contains("卡片值物化失败"));
            System.out.printf("[gap2] 降级支路: fillStatus泛化=%b outer-catch=%b%n", hasFillStatusWarning, hasOuterCatchWarning);
            assertTrue(hasFillStatusWarning || hasOuterCatchWarning,
                    "warnings 里除了'批次未完成'那条,还应有 fillStatus 的泛化兜底或外层 catch 的失败消息" +
                    "二者之一(证明降级路径确实完整跑到底,不是只有一条孤立的批次消息),实际 warnings=" + r.warnings);

        } finally {
            if (lockConn != null) {
                try { lockConn.rollback(); } catch (Exception ignore) {}
                try { lockConn.close(); } catch (Exception ignore) {}
            }
            if (prevChunk == null) System.clearProperty("cpq.ensure-card-values-chunk-size");
            else System.setProperty("cpq.ensure-card-values-chunk-size", prevChunk);
        }
    }

    @Test
    @DisplayName("缺口2·对照组: 无锁竞争时 materialize() 应回填 cardValuesReady=true 且 warnings 为空")
    void noContention_cardValuesReadyTrue_noWarnings() {
        buildFixture();
        assertEquals(9, lineIds.size(), "前置数据非空:应建出 9 行 line item");

        V6QuotationCommitService.CommitResult r =
                new V6QuotationCommitService.CommitResult(quotationId, UUID.randomUUID(), 9);
        materializer.materialize(r);

        System.out.printf("[gap2-control] cardValuesReady=%b warnings=%s missing=%d%n",
                r.cardValuesReady, r.warnings, countMissing());

        assertEquals(0, countMissing(), "无锁竞争时应全部 9 行都补齐(非空验证)");
        assertTrue(r.cardValuesReady, "无锁竞争、无失败时 cardValuesReady 应为 true");
        assertTrue(r.warnings.isEmpty(),
                "无锁竞争、无失败时 warnings 应为空,实际=" + r.warnings);
    }
}
