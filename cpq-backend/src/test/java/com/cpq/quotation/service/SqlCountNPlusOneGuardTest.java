package com.cpq.quotation.service;

import com.cpq.configure.service.ConfigureSnapshotService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260825 大单量导入建单性能 —— T-1（AC-3 · N+1 硬指标 · 覆盖 D-1 + D-3 两处）。
 *
 * <p><b>为什么不直接调用两处新批量方法</b>：D-1 的批量方法名未知（旧 {@code loadRowDataByComp}
 * 已被删除，新方法为实现细节，本任务禁止读 {@code cpq-backend/src/main/}）；D-3 的
 * {@code loadFrozenQuoteTabs} 同样是私有实现细节。api.md 承诺本次"零 API/契约变更"，且既有测试
 * （{@code LazyQuoteBucketEquivTest} / {@code ConfigureSnapshotEmptyOverwriteGuardTest} /
 * {@code CardValuesBatchPersistEquivTest}）已证明以下两个 PUBLIC 入口签名稳定不变：
 * <ul>
 *   <li>D-1 所在步骤①：{@link ConfigureSnapshotService#snapshotQuotation(UUID, boolean)}</li>
 *   <li>D-3 所在步骤③ Pass1 循环本体：{@link CardSnapshotService#snapshotNewLinesCardValues}</li>
 * </ul>
 * 故本测试通过这两个公开入口 + Hibernate {@link Statistics} 按表名过滤 SELECT 执行次数
 * （已用 {@code ScratchStatsProbeTest} 经验证：{@code Statistics.getQueries()} 对裸
 * {@code em.createNativeQuery(sql)} 同样按查询文本记录执行次数，不受"日志不打印"盲区影响——
 * 见问题说明.md §④「不要用『日志里没看到』证明『不存在』」），黑盒断言两处的 SQL 读取条数
 * 是否随 N 增长，不依赖任何内部方法名。
 */
@QuarkusTest
class SqlCountNPlusOneGuardTest {

    private static final String TAG = "T260825N1";

    @Inject EntityManager em;
    @Inject ConfigureSnapshotService configureSnapshotService;
    @Inject CardSnapshotService cardSnapshotService;

    /** D-1 测试用：需要手工清理的 id（snapshotQuotation 内部自行管理事务，不受 @TestTransaction 保护）。 */
    private final List<UUID> d1QuotationIds = new ArrayList<>();
    private final List<UUID> d1LineItemIds = new ArrayList<>();
    private UUID d1TemplateId, d1ComponentId, d1ViewId, d1TcId;

    @AfterEach
    void cleanupD1() {
        if (d1QuotationIds.isEmpty() && d1TemplateId == null) return;
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID qid : d1QuotationIds) {
                em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN " +
                        "(SELECT id FROM quotation_line_item WHERE quotation_id = :q)")
                        .setParameter("q", qid).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :q")
                        .setParameter("q", qid).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation WHERE id = :q")
                        .setParameter("q", qid).executeUpdate();
            }
            if (d1TcId != null) em.createNativeQuery("DELETE FROM template_component WHERE id = :id").setParameter("id", d1TcId).executeUpdate();
            if (d1TemplateId != null) em.createNativeQuery("DELETE FROM template WHERE id = :id").setParameter("id", d1TemplateId).executeUpdate();
            if (d1ViewId != null) em.createNativeQuery("DELETE FROM component_sql_view WHERE id = :id").setParameter("id", d1ViewId).executeUpdate();
            if (d1ComponentId != null) em.createNativeQuery("DELETE FROM component WHERE id = :id").setParameter("id", d1ComponentId).executeUpdate();
            em.createNativeQuery("DELETE FROM component WHERE code LIKE :p").setParameter("p", TAG + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM template WHERE name LIKE :p").setParameter("p", TAG + "%").executeUpdate();
        });
    }

    private static UUID toUUID(Object o) {
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    // ------------------------------------------------------------------
    // 共享 fixture 构造：1 个 0 行 $view 驱动组件 + 1 个模板（componentsSnapshot 含该组件）
    // ------------------------------------------------------------------

    private UUID[] buildComponentAndTemplate(String tag) {
        UUID[] ids = new UUID[3]; // [0]=componentId [1]=viewId [2]=templateId
        QuarkusTransaction.requiringNew().run(() -> {
            try {
                UUID componentId = UUID.randomUUID();
                em.createNativeQuery("INSERT INTO component (id, name, code, fields, formulas, data_driver_path, created_at, updated_at) " +
                        "VALUES (:id, :name, :code, CAST(:fields AS jsonb), CAST('[]' AS jsonb), :ddp, now(), now())")
                        .setParameter("id", componentId)
                        .setParameter("name", tag + "-驱动组件")
                        .setParameter("code", tag + "-" + componentId.toString().substring(0, 8))
                        .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                        .setParameter("ddp", "$" + tag.toLowerCase() + "_view")
                        .executeUpdate();

                UUID viewId = UUID.randomUUID();
                em.createNativeQuery("INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, declared_columns, created_at, updated_at) " +
                        "VALUES (:id, :cid, :vn, :tpl, '[]', now(), now())")
                        .setParameter("id", viewId)
                        .setParameter("cid", componentId)
                        .setParameter("vn", tag.toLowerCase() + "_view")
                        .setParameter("tpl", "SELECT '" + tag + "-P1'::text AS hf_part_no, 'x'::text AS \"名称\" WHERE FALSE")
                        .executeUpdate();

                UUID templateId = UUID.randomUUID();
                String snapshot = "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + componentId +
                        "\",\"componentName\":\"" + tag + "-驱动组件\",\"componentCode\":\"" + tag +
                        "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + tag + "页签\",\"sortOrder\":0," +
                        "\"fields\":[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}],\"formulas\":[]," +
                        "\"data_driver_path\":\"$" + tag.toLowerCase() + "_view\"}]";
                em.createNativeQuery("INSERT INTO template (id, template_series_id, name, template_kind, status, components_snapshot, created_at, updated_at) " +
                        "VALUES (:id, :tsid, :name, 'QUOTATION', 'DRAFT', CAST(:snap AS jsonb), now(), now())")
                        .setParameter("id", templateId)
                        .setParameter("tsid", UUID.randomUUID())
                        .setParameter("name", tag + "-模板")
                        .setParameter("snap", snapshot)
                        .executeUpdate();

                UUID tcId = UUID.randomUUID();
                em.createNativeQuery("INSERT INTO template_component (id, template_id, component_id, tab_name, created_at) " +
                        "VALUES (:id, :tid, :cid, :tab, now())")
                        .setParameter("id", tcId).setParameter("tid", templateId).setParameter("cid", componentId)
                        .setParameter("tab", tag + "页签").executeUpdate();

                ids[0] = componentId; ids[1] = viewId; ids[2] = templateId;
                d1ComponentId = componentId; d1ViewId = viewId; d1TemplateId = templateId; d1TcId = tcId;
            } catch (Exception e) {
                throw new RuntimeException("构造组件/模板 fixture 失败", e);
            }
        });
        return ids;
    }

    /** 建 1 张含 n 行 line item 的报价单（不预插 quotation_line_component_data —— 首建单场景本就没有）。 */
    private UUID[] buildQuotationLines(UUID templateId, int n, String tag) {
        UUID quotationId = UUID.randomUUID();
        List<UUID> lineIds = new ArrayList<>();
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
                    .setParameter("qn", tag + "-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId)
                    .setParameter("name", tag + "-N=" + n)
                    .setParameter("srid", salesRepId)
                    .setParameter("tid", templateId)
                    .executeUpdate();

            for (int i = 0; i < n; i++) {
                UUID lid = UUID.randomUUID();
                em.createNativeQuery("INSERT INTO quotation_line_item (id, quotation_id, template_id, " +
                        "product_part_no_snapshot, sort_order, created_at) VALUES (:id, :qid, :tid, :pn, :so, now())")
                        .setParameter("id", lid).setParameter("qid", quotationId).setParameter("tid", templateId)
                        .setParameter("pn", tag + "-P" + i).setParameter("so", i)
                        .executeUpdate();
                lineIds.add(lid);
            }
        });
        UUID[] out = new UUID[1 + n];
        out[0] = quotationId;
        for (int i = 0; i < n; i++) out[i + 1] = lineIds.get(i);
        return out;
    }

    private Statistics stats() {
        Statistics st = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        st.setStatisticsEnabled(true);
        return st;
    }

    /** 按表名过滤,只统计 SELECT 语句执行次数(不计 INSERT/UPDATE 写)。 */
    /**
     * 汇总口径：本表可能被多条**语义不同**的合法批量查询各命中一次（如既有的
     * snapshot_rows 整单 IN 查询 + 新增的 row_data 整单 IN 查询），这属于「各自一次」，
     * 不构成 N+1。真正判定 N+1 的是 {@link #maxSelectReads}——**同一条查询语句文本**
     * 的执行次数是否随 N 增长（每行一条 = 该文本的 execCount 等于 N）。
     */
    private long sumSelectReads(Statistics st, String tableName) {
        long total = 0;
        for (String q : st.getQueries()) {
            String lower = q.toLowerCase();
            if (lower.startsWith("select") && lower.contains(tableName)) {
                long c = st.getQueryStatistics(q).getExecutionCount();
                total += c;
                System.out.printf("  [match table=%s] exec=%d sql=%s%n", tableName, c, q);
            }
        }
        return total;
    }

    /** 同一条查询文本的最大执行次数——N+1 的真判据：若某条 SELECT 逐行调用,其 execCount == N。 */
    private long maxSelectReads(Statistics st, String tableName) {
        long max = 0;
        for (String q : st.getQueries()) {
            String lower = q.toLowerCase();
            if (lower.startsWith("select") && lower.contains(tableName)) {
                max = Math.max(max, st.getQueryStatistics(q).getExecutionCount());
            }
        }
        return max;
    }

    // ==================================================================
    // D-1：quotation_line_component_data 读取条数(经 snapshotQuotation)
    // ==================================================================

    @Test
    @DisplayName("T-1a(AC-3·D-1): snapshotQuotation 对 quotation_line_component_data 的 SELECT 次数与行数 N 无关")
    void d1_readCount_independentOfN() {
        UUID[] ct = buildComponentAndTemplate(TAG + "D1");
        UUID templateId = ct[2];

        UUID[] q5 = buildQuotationLines(templateId, 5, TAG + "D1N5");
        UUID qid5 = q5[0];
        d1QuotationIds.add(qid5);
        assertEquals(5, q5.length - 1, "前置数据非空:应建出 5 行 line item");

        Statistics st = stats();
        st.clear();
        configureSnapshotService.snapshotQuotation(qid5, false);
        long d1_5sum = sumSelectReads(st, "quotation_line_component_data");
        long d1_5max = maxSelectReads(st, "quotation_line_component_data");

        UUID[] q50 = buildQuotationLines(templateId, 50, TAG + "D1N50");
        UUID qid50 = q50[0];
        d1QuotationIds.add(qid50);
        assertEquals(50, q50.length - 1, "前置数据非空:应建出 50 行 line item");

        st.clear();
        configureSnapshotService.snapshotQuotation(qid50, false);
        long d1_50sum = sumSelectReads(st, "quotation_line_component_data");
        long d1_50max = maxSelectReads(st, "quotation_line_component_data");

        System.out.printf("[T-1a D-1] N=5 sum=%d max=%d | N=50 sum=%d max=%d | 判据:同一SQL文本的max执行次数<=ceil(N/200)且不随N增长%n",
                d1_5sum, d1_5max, d1_50sum, d1_50max);

        assertTrue(d1_5sum > 0, "N=5 应实际命中 quotation_line_component_data 上的 SELECT(非空验证,不是空跑)");
        assertTrue(d1_50sum > 0, "N=50 应实际命中 quotation_line_component_data 上的 SELECT(非空验证,不是空跑)");
        // sum 允许 >1:同一批次内可能有多条语义不同的合法批量查询各命中一次(如既有 snapshot_rows 整单IN + 新增 row_data 整单IN),
        // 这不是 N+1;真正的 N+1 判据是"单条 SQL 文本"的执行次数是否等于 N(逐行调用的直接证据)。
        assertTrue(d1_5max <= 1, "N=5 时同一条 SELECT 文本执行次数应<=ceil(5/200)=1(不逐行调用),实测max=" + d1_5max);
        assertTrue(d1_50max <= 1, "N=50 时同一条 SELECT 文本执行次数应<=ceil(50/200)=1(不逐行调用),实测max=" + d1_50max);
        assertEquals(d1_5sum, d1_50sum, "N从5增至50,quotation_line_component_data读取总条数不应增长(常数条,与N无关)——区分『常数条』与『每行一条』的真判据");
        assertEquals(d1_5max, d1_50max, "N从5增至50,单条SQL文本的执行次数不应增长");
    }

    // ==================================================================
    // D-3：quotation_view_structure 读取条数(经 CardSnapshotService.ensureCardValues —— 即问题说明.md
    // 调用链图的「步骤③」,真正含 D-3 缺陷的 @Transactional 入口)
    //
    // 注:最初尝试直接调用内层 snapshotNewLinesCardValues,但实测该调用会命中
    // "TemplateRenderScope 未打开"降级分支,直接短路掉 loadFrozenQuoteTabs——观测到的
    // quotation_view_structure 读数恒为 0,与 fix 是否生效无关(典型假绿:断言从未真正执行到)。
    // ensureCardValues 是 EnsureCardValuesTest 已验证过的、会正确管理这层 scope 的公开入口,改用它。
    // ==================================================================

    @Test
    @TestTransaction
    @DisplayName("T-1b(AC-3·D-3): ensureCardValues 对 quotation_view_structure 的 SELECT 次数与行数 N 无关")
    void d3_readCount_independentOfN() {
        UUID[] ct = buildComponentAndTemplateInline(TAG + "D3");
        UUID templateId = ct[2];

        long d3_5 = measureD3(templateId, 5, TAG + "D3N5");
        long d3_50 = measureD3(templateId, 50, TAG + "D3N50");

        System.out.printf("[T-1b D-3] N=5 reads=%d | N=50 reads=%d | 预期两者均<=4 且不随N增长%n", d3_5, d3_50);

        assertTrue(d3_5 <= 4, "N=5 时 quotation_view_structure 读取条数应<=4(四类view_kind各一次),实测=" + d3_5);
        assertTrue(d3_50 <= 4, "N=50 时 quotation_view_structure 读取条数应<=4,实测=" + d3_50);
        assertEquals(d3_5, d3_50, "N从5增至50,读取条数不应增长(常数条,与N无关)");
    }

    private long measureD3(UUID templateId, int n, String tag) {
        UUID quotationId = UUID.randomUUID();
        List<UUID> lineIds = new ArrayList<>();

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
                .setParameter("qn", tag + "-" + quotationId.toString().substring(0, 8))
                .setParameter("cid", customerId)
                .setParameter("name", tag + "-N=" + n)
                .setParameter("srid", salesRepId)
                .setParameter("tid", templateId)
                .executeUpdate();

        for (int i = 0; i < n; i++) {
            UUID lid = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO quotation_line_item (id, quotation_id, template_id, " +
                    "product_part_no_snapshot, sort_order, created_at) VALUES (:id, :qid, :tid, :pn, :so, now())")
                    .setParameter("id", lid).setParameter("qid", quotationId).setParameter("tid", templateId)
                    .setParameter("pn", tag + "-P" + i).setParameter("so", i)
                    .executeUpdate();
            // 与既有 ConfigureSnapshotEmptyOverwriteGuardTest 同款:插入非 NULL 的空数组快照行,
            // 避免触发"整行重expand"分支,让 buildCardValues 走正常渲染路径。
            em.createNativeQuery("INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, " +
                    "row_data, snapshot_rows) VALUES (:id, :lid, :cid, :tab, '[]', '[]')")
                    .setParameter("id", UUID.randomUUID()).setParameter("lid", lid).setParameter("cid", d3ComponentId)
                    .setParameter("tab", tag + "页签").executeUpdate();
            lineIds.add(lid);
        }
        assertEquals(n, lineIds.size(), "前置数据非空:应建出 " + n + " 行 line item");

        Statistics st = stats();
        st.clear();
        int filled = cardSnapshotService.ensureCardValues(quotationId);
        assertEquals(n, filled, "ensureCardValues 应把全部 " + n + " 行的缺失卡片值补齐(前置:新建单卡片值全NULL)");
        if (System.getProperty("t1.debugAllQueries") != null) {
            for (String q : st.getQueries()) {
                System.out.printf("  [ALL] exec=%d sql=%s%n", st.getQueryStatistics(q).getExecutionCount(), q);
            }
        }
        return sumSelectReads(st, "quotation_view_structure");
    }

    private UUID d3ComponentId;

    private UUID[] buildComponentAndTemplateInline(String tag) {
        UUID componentId = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO component (id, name, code, fields, formulas, data_driver_path, created_at, updated_at) " +
                "VALUES (:id, :name, :code, CAST(:fields AS jsonb), CAST('[]' AS jsonb), :ddp, now(), now())")
                .setParameter("id", componentId)
                .setParameter("name", tag + "-驱动组件")
                .setParameter("code", tag + "-" + componentId.toString().substring(0, 8))
                .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                .setParameter("ddp", "$" + tag.toLowerCase() + "_view")
                .executeUpdate();
        d3ComponentId = componentId;

        UUID viewId = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, declared_columns, created_at, updated_at) " +
                "VALUES (:id, :cid, :vn, :tpl, '[]', now(), now())")
                .setParameter("id", viewId)
                .setParameter("cid", componentId)
                .setParameter("vn", tag.toLowerCase() + "_view")
                .setParameter("tpl", "SELECT '" + tag + "-P1'::text AS hf_part_no, 'x'::text AS \"名称\" WHERE FALSE")
                .executeUpdate();

        UUID templateId = UUID.randomUUID();
        String snapshot = "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + componentId +
                "\",\"componentName\":\"" + tag + "-驱动组件\",\"componentCode\":\"" + tag +
                "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + tag + "页签\",\"sortOrder\":0," +
                "\"fields\":[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}],\"formulas\":[]," +
                "\"data_driver_path\":\"$" + tag.toLowerCase() + "_view\"}]";
        em.createNativeQuery("INSERT INTO template (id, template_series_id, name, template_kind, status, components_snapshot, created_at, updated_at) " +
                "VALUES (:id, :tsid, :name, 'QUOTATION', 'DRAFT', CAST(:snap AS jsonb), now(), now())")
                .setParameter("id", templateId)
                .setParameter("tsid", UUID.randomUUID())
                .setParameter("name", tag + "-模板")
                .setParameter("snap", snapshot)
                .executeUpdate();

        UUID tcId = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO template_component (id, template_id, component_id, tab_name, created_at) " +
                "VALUES (:id, :tid, :cid, :tab, now())")
                .setParameter("id", tcId).setParameter("tid", templateId).setParameter("cid", componentId)
                .setParameter("tab", tag + "页签").executeUpdate();

        return new UUID[]{componentId, viewId, templateId};
    }

    // ==================================================================
    // T-6（AC-8 · 边界）：空集合 / 单行 / 全 NULL row_data 不抛异常
    //
    // 说明:AC-8/C-2 提到的"IN 列表含 null 元素"是内部实现细节(内部收集的 lineItemId 集合
    // 什么情况下会混入 null 元素),黑盒测试拿不到这个输入面,本任务不读实现代码故无法验证该子项，
    // 已在回报中明确列为"未验证"。以下只覆盖黑盒可达的三个边界：0 行 / 1 行 / 全 NULL row_data
    // (本文件所有 fixture 天生就是"全 NULL row_data 的新建单"——从未写过 row_data)。
    // ==================================================================

    @Test
    @DisplayName("T-6a(AC-8): snapshotQuotation 对 0 行报价单不抛异常")
    void d1_edgeCase_zeroLines_doesNotThrow() {
        UUID[] ct = buildComponentAndTemplate(TAG + "D1E0");
        UUID templateId = ct[2];
        UUID[] q0 = buildQuotationLines(templateId, 0, TAG + "D1E0N0");
        UUID qid0 = q0[0];
        d1QuotationIds.add(qid0);
        assertEquals(0, q0.length - 1, "本用例故意构造 0 行,验证空集合分支");

        assertDoesNotThrow(() -> configureSnapshotService.snapshotQuotation(qid0, false),
                "0 行报价单 snapshotQuotation 不应抛异常");
    }

    @Test
    @DisplayName("T-6b(AC-8): snapshotQuotation 对 1 行(单行退化) + row_data 全 NULL 的新建单不抛异常")
    void d1_edgeCase_oneLine_allNullRowData_doesNotThrow() {
        UUID[] ct = buildComponentAndTemplate(TAG + "D1E1");
        UUID templateId = ct[2];
        UUID[] q1 = buildQuotationLines(templateId, 1, TAG + "D1E1N1");
        UUID qid1 = q1[0];
        d1QuotationIds.add(qid1);
        assertEquals(1, q1.length - 1, "前置数据非空:应建出 1 行 line item");

        assertDoesNotThrow(() -> configureSnapshotService.snapshotQuotation(qid1, false),
                "1 行(row_data 从未写过,全 NULL)snapshotQuotation 不应抛异常");

        // 断言结果非空:该行确实产出了 quotation_line_component_data(不是空跑)
        Number cnt = (Number) em.createNativeQuery(
                "SELECT count(*) FROM quotation_line_component_data d JOIN quotation_line_item li ON li.id=d.line_item_id " +
                "WHERE li.quotation_id = :q")
                .setParameter("q", qid1).getSingleResult();
        System.out.printf("[T-6b] qid=%s 落库 component_data 行数=%d%n", qid1, cnt);
        assertTrue(cnt.longValue() > 0, "1 行退化场景应产出至少 1 条 quotation_line_component_data(非空验证)");
    }

    @Test
    @TestTransaction
    @DisplayName("T-6c(AC-8): ensureCardValues 对 0 行 / 1 行报价单不抛异常,返回值与实际缺失行数一致")
    void d3_edgeCase_zeroAndOneLine_doesNotThrow() {
        UUID[] ct = buildComponentAndTemplateInline(TAG + "D3E");
        UUID templateId = ct[2];

        // 0 行
        UUID qid0 = UUID.randomUUID();
        @SuppressWarnings("unchecked")
        List<Object> customers = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
        UUID customerId = toUUID(customers.get(0));
        @SuppressWarnings("unchecked")
        List<Object> users = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
        UUID salesRepId = toUUID(users.get(0));
        em.createNativeQuery("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                "customer_template_id, tax_rate, tax_amount, created_at, updated_at) " +
                "VALUES (:id, :qn, :cid, :name, :srid, 'DRAFT', :tid, 0, 0, now(), now())")
                .setParameter("id", qid0).setParameter("qn", TAG + "D3E0-" + qid0.toString().substring(0, 8))
                .setParameter("cid", customerId).setParameter("name", TAG + "D3E0")
                .setParameter("srid", salesRepId).setParameter("tid", templateId).executeUpdate();

        int filled0 = assertDoesNotThrow(() -> cardSnapshotService.ensureCardValues(qid0),
                "0 行报价单 ensureCardValues 不应抛异常");
        assertEquals(0, filled0, "0 行报价单应补算 0 行");

        // 1 行
        long d3_1 = measureD3(templateId, 1, TAG + "D3E1");
        System.out.printf("[T-6c] N=1 quotation_view_structure reads=%d%n", d3_1);
        assertTrue(d3_1 <= 4, "N=1 时读取条数应<=4,实测=" + d3_1);
    }
}
