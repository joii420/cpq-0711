package com.cpq.quotation.service;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260825 大单量导入建单性能 —— T-9（AC-12 · 部分失败可恢复）+ 覆盖 B-28 的核心断言
 * （"被堵批之后的批次照常完成"，主线 2026-08-28 消息原话）。
 *
 * <p><b>机制</b>：{@link CardSnapshotService#ensureCardValuesDetailed} 按 chunk 分批，每批
 * {@code REQUIRES_NEW} 独立事务，批事务开头 {@code SET LOCAL lock_timeout='10s'}——拿不到行锁快速
 * 失败、不中止整体、继续下一批（主线原话）。本测试用<b>另一条真实 JDBC 连接</b>（经
 * {@link DataSource} 直接拿，物理上与被测服务用的连接池连接不同）对目标行加
 * {@code SELECT ... FOR UPDATE} 且不提交，制造"某批拿不到锁"的真实场景，而不是猜测/mock。
 *
 * <p><b>chunk 收窄</b>：用 {@code cpq.ensure-card-values-chunk-size} 系统属性把 chunk 调到 3
 * （主线确认此参数存在且可配，对标 {@code GetByIdBatchEquivTest}/{@code RowDataBatchWriteEquivTest}
 * 已验证过的 {@code System.setProperty} + finally 恢复范式），让 9 行的小夹具也能分出 3 批。
 *
 * <p><b>断言不依赖"精确知道哪几行落在哪一批"</b>（分批分组的具体实现细节不可读）：
 * 只锁 9 行里连续的 3 行（按 sort_order，最贴近"顺序分块"的合理假设），断言用更宽松、更稳健的
 * 不变量——"0 &lt; failedRows &lt; N"（证明是<b>部分</b>失败，不是全部跟着一起挂，也不是完全没受影响）、
 * "重跑后 0 缺失 且 已完成行的值不被重算/不变"。
 *
 * <p>🔴 <b>2026-08-28 主线更正（读了 {@code EnsureResult} javadoc 原文后确认，非本测试猜测）</b>：
 * {@code EnsureResult.computed} 语义 = {@code missing.size()}（本次识别出的"需要补算"行数/<b>尝试数</b>），
 * 与既有薄包装 {@code ensureCardValues(UUID,boolean)} 的返回值语义逐位相同、<b>不随批失败扣减</b>——
 * 这是 B-28 之前就有的既有语义，不是本次改动引入。本测试首版曾断言 {@code computed == N - failedRows}
 * （把"尝试数"误当"落库成功数"），首跑时被真实并发实验暴露为错误断言（{@code computed} 恒为 9，
 * 不随 {@code failedRows} 变化）——<b>问对了问题、找错了证人</b>。现拆成两条：
 * <ul>
 *   <li><b>契约断言</b>：{@code computed == 9}（尝试数，恒定，与批是否失败无关）</li>
 *   <li><b>事实断言（AC-12 真正的判据）</b>：{@code count(quote_card_values IS NOT NULL) == 9 - failedRows}
 *       —— 这才是"被堵批之后的批次照常完成、已提交批的结果被保留"的直接证据，比契约断言更值钱：
 *       将来谁改了 {@code computed} 的语义，这条依然守得住。</li>
 * </ul>
 *
 * <p><b>时间成本</b>：单个用例约 10~15s（等待 {@code lock_timeout=10s} 真实触发），已知且必要，
 * 与主线本人做受控实验的时间量级一致。
 */
@QuarkusTest
class EnsureCardValuesPartialBatchRecoveryTest {

    private static final String TAG = "T260825T9";

    @Inject EntityManager em;
    @Inject CardSnapshotService cardSnapshotService;
    @Inject DataSource dataSource;

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

    /** 9 行报价单,0 行 $view 驱动(WHERE FALSE),quotation_line_component_data 预插空数组快照行 —— 对标
     *  SqlCountNPlusOneGuardTest#measureD3 的安全 fixture 手法(避免触发"整行重expand"分支)。 */
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
            // ensureCardValuesDetailed -> PublishedTemplateReader.allTabsOf 的"模板快照损坏"守卫要求
            // template_component_snapshot 行数与 components_snapshot jsonb 数组长度恒相等。
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

    /** 真正落库(非 NULL)的行数——AC-12"已提交批保留"防线的真判据,不依赖 EnsureResult.computed 的字面语义。 */
    private long countLanded() {
        Number n = (Number) em.createNativeQuery(
                "SELECT count(*) FROM quotation_line_item WHERE quotation_id = :q AND quote_card_values IS NOT NULL")
                .setParameter("q", quotationId).getSingleResult();
        return n.longValue();
    }

    private Map<UUID, String> snapshotQuoteCardValues() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, quote_card_values::text FROM quotation_line_item WHERE quotation_id = :q ORDER BY sort_order")
                .setParameter("q", quotationId).getResultList();
        Map<UUID, String> out = new LinkedHashMap<>();
        for (Object[] r : rows) out.put((UUID) r[0], (String) r[1]);
        return out;
    }

    /** 用独立 JDBC 连接对目标行加 FOR UPDATE 且不提交 —— 真实持锁,非 mock。返回持锁中的 Connection,调用方负责最终 rollback。 */
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

    /** 复核锁确实持有(主线提供的验证手法) —— 查 pg_locks 是否有该 pid 的 tuple/transactionid 锁。 */
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
                long lockCount = rs.getLong(1);
                assertTrue(lockCount > 0, "锁持有连接(pid=" + pid + ")应在 pg_locks 里查到 tuple/transactionid 锁," +
                        "实测=" + lockCount + "——不满足则后续'部分失败'实验是空跑,不是真锁");
            }
        }
    }

    @Test
    @DisplayName("T-9(AC-12 · B-28): 单批因拿不到行锁而失败时,后续批次照常完成 + 重跑只补未完成行,已完成行不变")
    void partialBatchFailure_laterBatchesStillComplete_rerunOnlyFillsMissing() throws Exception {
        buildFixture();
        assertEquals(9, lineIds.size(), "前置数据非空:应建出 9 行 line item");
        assertEquals(9, countMissing(), "前置:9 行应全部缺失卡片值(新建单)");

        // 锁定连续 3 行(按 sort_order 3,4,5 —— 最贴近"顺序分块"的合理假设,断言本身不依赖这个假设精确成立)
        List<UUID> lockedIds = lineIds.subList(3, 6);
        String prevChunk = System.getProperty("cpq.ensure-card-values-chunk-size");
        Connection lockConn = null;
        try {
            System.setProperty("cpq.ensure-card-values-chunk-size", "3");

            lockConn = lockRowsForUpdate(lockedIds);
            assertLockActuallyHeld(lockConn);

            long t0 = System.currentTimeMillis();
            CardSnapshotService.EnsureResult result =
                    cardSnapshotService.ensureCardValuesDetailed(quotationId, false);
            long elapsed = System.currentTimeMillis() - t0;

            System.out.printf("[T-9] 第一次 ensureCardValuesDetailed: computed=%d failedBatches=%d failedRows=%d elapsed=%dms%n",
                    result.computed, result.failedBatches, result.failedRows, elapsed);

            // 核心断言 1(B-28):部分失败,不是全部跟着一起挂,也不是"锁根本没起作用"
            assertTrue(result.failedRows > 0,
                    "应有失败行(锁生效的直接证据) —— 若为 0 说明本次实验没触发到被锁的批,需要调整锁定位置/chunk 大小");
            assertTrue(result.failedRows < 9,
                    "不应全部 9 行都失败 —— 若=9 说明锁的影响面扩散到了全部批次(与'部分失败'的前提矛盾)");
            assertTrue(result.failedBatches > 0, "failedBatches 应 > 0,与 failedRows > 0 一致");

            // 契约断言(主线 2026-08-28 更正,读了 EnsureResult 的 javadoc 原文后确认):
            // computed 语义 = missing.size(),即"本次识别出的需要补算行数"(尝试数),与既有薄包装
            // ensureCardValues(UUID,boolean) 的返回值语义逐位相同,不扣减失败行——这是 B-28 之前就有的
            // 既有语义,不是本次改动引入。不能拿它当"落库成功数"用。
            assertEquals(9, result.computed,
                    "computed 是尝试数(missing.size()),不随批失败扣减,应恒等于本次识别出的缺失行总数 9");

            // 事实断言(主线原话"这才是 AC-12『已提交批保留』防线的真判据"):
            // 真正落库(quote_card_values 非 NULL)的行数才是"已提交批被保留"的直接证据,
            // 且必须等于 9 - failedRows —— 这是本测试真正要守的不变量,比①更值钱:
            // 将来谁改了 computed 的语义,②依然守得住。
            long landedAfterFirst = countLanded();
            assertEquals(9 - result.failedRows, landedAfterFirst,
                    "真正落库(quote_card_values 非 NULL)的行数应等于 9 - failedRows —— " +
                    "证明被堵批之后的批次照常完成、已提交批的结果被保留,而不是整个 ensureCardValuesDetailed " +
                    "因中途一批失败就提前中止(旧的 all-or-nothing 行为)");

            // DB 状态复核:缺失行数应等于 failedRows(不多不少)
            long missingAfterFirst = countMissing();
            assertEquals(result.failedRows, missingAfterFirst,
                    "DB 内缺失(quote_card_values IS NULL)行数应等于 failedRows");

            // 记录已成功行的值,供第二次调用后比对"不变"
            Map<UUID, String> beforeSecond = snapshotQuoteCardValues();
            long succeededCountBefore = beforeSecond.values().stream().filter(v -> v != null).count();
            assertEquals(landedAfterFirst, succeededCountBefore,
                    "两种口径(SQL count / 查回来的 map)算出的已落库行数应一致(非空验证,互相校准)");

            // 释放锁,让被堵的批次可以重跑成功
            lockConn.rollback();
            lockConn.close();
            lockConn = null;

            CardSnapshotService.EnsureResult result2 =
                    cardSnapshotService.ensureCardValuesDetailed(quotationId, false);
            System.out.printf("[T-9] 第二次(释放锁后)ensureCardValuesDetailed: computed=%d failedBatches=%d failedRows=%d%n",
                    result2.computed, result2.failedBatches, result2.failedRows);

            assertEquals(missingAfterFirst, result2.computed,
                    "第二次调用的 computed(=尝试数)只应等于第一次遗留的缺失行数(自愈,不重复尝试已完成行)");
            assertEquals(0, result2.failedBatches, "锁已释放,第二次调用不应再有失败批");
            assertEquals(0, countMissing(), "第二次调用后应 0 行缺失");
            assertEquals(9, countLanded(), "第二次调用后全部 9 行都应真正落库(非空验证,事实口径收尾)");

            // 已完成行的值必须逐位不变(证明"已提交批保留,不被重算/不被覆盖")
            Map<UUID, String> afterSecond = snapshotQuoteCardValues();
            for (UUID lid : beforeSecond.keySet()) {
                String before = beforeSecond.get(lid);
                if (before == null) continue; // 这行本来就是第一次失败的,预期会变(从 NULL 变为有值)
                String after = afterSecond.get(lid);
                assertEquals(before, after,
                        "line=" + lid + " 第一次已成功落库的 quote_card_values 在第二次调用后必须逐字节不变" +
                        "(证明不重算已完成行,只补未完成行)");
            }

        } finally {
            if (lockConn != null) {
                try { lockConn.rollback(); } catch (Exception ignore) {}
                try { lockConn.close(); } catch (Exception ignore) {}
            }
            if (prevChunk == null) System.clearProperty("cpq.ensure-card-values-chunk-size");
            else System.setProperty("cpq.ensure-card-values-chunk-size", prevChunk);
        }
    }
}
