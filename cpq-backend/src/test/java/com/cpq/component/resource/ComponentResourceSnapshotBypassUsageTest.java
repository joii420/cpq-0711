package com.cpq.component.resource;

import com.cpq.component.dto.BatchExpandDriverRequest;
import com.cpq.component.dto.BatchExpandDriverRequest.Task;
import com.cpq.component.dto.BatchExpandDriverResponse;
import com.cpq.component.dto.BatchExpandDriverResponse.Result;
import com.cpq.component.dto.ExpandDriverResponse;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-1 回归门禁：{@code POST /api/cpq/components/batch-expand} 带 {@code lineItemId} 时，
 * {@code ComponentResource} 的两处"窥探快照"旁路点（Phase 1 · doBatchExpandPhases:~306，
 * Phase 2 不可合桶单跑 · runSingleTask:~446）必须按 {@code task.usage} 门控——只有
 * {@code usage=QUOTE} 才允许命中 {@code quotation_line_component_data.snapshot_rows}；
 * {@code usage=COSTING}/非法值/缺省一律不得读到该快照（该表只由报价侧
 * {@code ConfigureSnapshotService}/{@code QuotationTreeService} 写，COSTING 读到即会带出
 * 报价侧 pending 数据 + {@code __v6_id} 锚点，破 AC-17）。
 *
 * <p><b>为什么 T4 的既有 4 个测试类覆盖不到这条路径</b>：它们全部用全新合成 componentId，
 * 从不预置 {@code snapshot_rows}——快照永远 miss，走不到旁路判定分支本身。本测试专门
 * <b>预置</b>一条含 {@code __v6_id} 的快照夹具，先证明快照确实写进去了（前置非空断言），
 * 再验证 usage 门控生效。
 *
 * <p><b>如何同时命中两个旁路点</b>：每个场景的 batch 只含<b>单个</b> task。
 * {@code doBatchExpandPhases} 的 Phase 2 合桶要求 {@code idxs.size() >= 2}——单 task 桶
 * 必然 {@code canMerge=false}，走 {@code runSingleTask}（旁路点 2）。而 {@code usage=QUOTE}
 * 的 task 在 Phase 1（旁路点 1）就会因快照命中而 {@code continue}，根本不会进入 Phase 2。
 * 因此：QUOTE 场景验证旁路点 1，COSTING/非法/缺省三个场景验证旁路点 2。
 *
 * <p>夹具：自建 component + component_sql_view（sql_template 指向白名单表 {@code unit_price}，
 * 与 {@code QuotePendingSqlTextAssertionTest} 同一模式，保证 COSTING 侧真实展开必然成功不报错）
 * + 自建 quotation（DRAFT）+ quotation_line_item + 一条预置 {@code snapshot_rows} 含
 * {@code __v6_id} 的 {@code quotation_line_component_data}。全部在 {@code @AfterEach} 清理
 * 干净，不残留共享库 {@code cpq_db}（T4 技术总监补过的坑，本测试同规格处理）。
 */
@QuarkusTest
class ComponentResourceSnapshotBypassUsageTest {

    private static final String TEST_COMPONENT_CODE = "TEST-BUG1-SNAPSHOT-BYPASS";
    private static final String TEST_VIEW_NAME = "test_bug1_snapshot_bypass_view";

    @Inject
    ComponentResource resource;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    private UUID componentId;
    private UUID quotationId;
    private UUID lineItemId;
    private UUID componentDataId;
    private String v6IdValue;

    @BeforeEach
    void seed() throws Exception {
        utx.begin();
        em.joinTransaction();

        // 防御性清理：上一次失败的运行可能残留同名夹具。
        em.createNativeQuery("DELETE FROM component_sql_view WHERE sql_view_name = :n")
                .setParameter("n", TEST_VIEW_NAME).executeUpdate();
        em.createNativeQuery("DELETE FROM component WHERE code = :c")
                .setParameter("c", TEST_COMPONENT_CODE).executeUpdate();

        // 组件 + SQL 视图（同 QuotePendingSqlTextAssertionTest 模式：指向白名单表 unit_price，
        // customer_no = :customerCode 在 customerId=null 时恒不匹配 → 0 行但不报错，
        // 保证 COSTING 侧真实展开必然成功，返回状态 OK 而不是 ERROR）。
        componentId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas, status, component_type, " +
                "  data_driver_path, excel_columns, column_count, bom_recursive_expand, created_at, updated_at) " +
                "VALUES (:id, :name, :code, '[]'::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', " +
                "  :dp, '[]'::jsonb, 0, false, NOW(), NOW())")
                .setParameter("id", componentId)
                .setParameter("name", "BUG-1 快照旁路 usage 门控测试夹具")
                .setParameter("code", TEST_COMPONENT_CODE)
                .setParameter("dp", "$" + TEST_VIEW_NAME)
                .executeUpdate();

        em.createNativeQuery(
                "INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, scope, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :cid, :vn, :tpl, 'COMPONENT', 'ACTIVE', NOW(), NOW())")
                .setParameter("cid", componentId)
                .setParameter("vn", TEST_VIEW_NAME)
                .setParameter("tpl",
                        "-- BUG-1 fixture: 单一白名单表(unit_price), 不含 :lineItemId, 保证桶必为单 task\n" +
                        "SELECT up.id, up.code AS _code, up.is_current AS _is_current\n" +
                        "FROM unit_price up\n" +
                        "WHERE up.system_type = 'QUOTE' AND up.customer_no = :customerCode")
                .executeUpdate();

        // 自建 DRAFT 报价单 + 行项目（FK 链路必须真实存在：quotation_line_component_data.line_item_id
        // → quotation_line_item.id → quotation.id）。customer/user 用库内任一现存记录，不硬编码具体
        // 锚点 id（T4 已吸取"硬编码不在库的锚单"教训）。
        Object[] custAndUser = (Object[]) em.createNativeQuery(
                "SELECT (SELECT id FROM customer LIMIT 1), (SELECT id FROM \"user\" LIMIT 1)")
                .getSingleResult();
        UUID customerId = (UUID) custAndUser[0];
        UUID salesRepId = (UUID) custAndUser[1];
        assertNotNull(customerId, "库内无任何 customer 记录，无法自建 quotation 夹具（基础数据缺失）");
        assertNotNull(salesRepId, "库内无任何 user 记录，无法自建 quotation 夹具（基础数据缺失）");

        quotationId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation (id, quotation_number, customer_id, sales_rep_id, name, status, created_at, updated_at) " +
                "VALUES (:id, :num, :cid, :sid, :name, 'DRAFT', NOW(), NOW())")
                .setParameter("id", quotationId)
                .setParameter("num", "TEST-BUG1-" + System.nanoTime())
                .setParameter("cid", customerId)
                .setParameter("sid", salesRepId)
                .setParameter("name", "BUG-1 快照旁路测试单")
                .executeUpdate();

        lineItemId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, sort_order, created_at) " +
                "VALUES (:id, :qid, 0, NOW())")
                .setParameter("id", lineItemId)
                .setParameter("qid", quotationId)
                .executeUpdate();

        // 核心夹具：预置一条含 __v6_id 的冻结快照（模拟报价侧 ConfigureSnapshotService 已物化的状态）。
        v6IdValue = UUID.randomUUID().toString();
        componentDataId = UUID.randomUUID();
        String snapshotJson = "[{\"driverRow\":{\"__v6_id\":\"" + v6IdValue + "\",\"hf_part_no\":\"BUG1-FIXTURE-PART\"}," +
                "\"basicDataValues\":{}}]";
        em.createNativeQuery(
                "INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, row_data, " +
                "  snapshot_rows, subtotal, sort_order, created_at) " +
                "VALUES (:id, :lid, :cid, 'TestTab', '[]'::jsonb, CAST(:snap AS jsonb), 0, 0, NOW())")
                .setParameter("id", componentDataId)
                .setParameter("lid", lineItemId)
                .setParameter("cid", componentId)
                .setParameter("snap", snapshotJson)
                .executeUpdate();

        utx.commit();

        // 前置非空断言（任务要求）：先证明快照夹具确实写进去了，排除"快照本来就 miss 所以测试空转通过"
        // 这条 T4 曾经真实漏掉的路径——不加这条，下面 COSTING 场景"不含 __v6_id"的断言即便旁路点根本
        // 没被本测试触达也会通过，等于什么都没检查。
        @SuppressWarnings("unchecked")
        List<Object> raw = em.createNativeQuery(
                "SELECT snapshot_rows::text FROM quotation_line_component_data WHERE id = :id")
                .setParameter("id", componentDataId)
                .getResultList();
        assertFalse(raw.isEmpty(), "夹具写入后应能查到该行——INSERT 本身失败或未提交");
        String persisted = (String) raw.get(0);
        assertNotNull(persisted, "夹具 snapshot_rows 落库后不应为 NULL");
        assertTrue(persisted.contains("__v6_id") && persisted.contains(v6IdValue),
                "夹具 snapshot_rows 落库内容应含本次生成的 __v6_id 值，实际：" + persisted);
    }

    @AfterEach
    void cleanup() {
        try {
            utx.begin();
            em.joinTransaction();
            if (componentDataId != null) {
                em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE id = :id")
                        .setParameter("id", componentDataId).executeUpdate();
            }
            if (lineItemId != null) {
                em.createNativeQuery("DELETE FROM quotation_line_item WHERE id = :id")
                        .setParameter("id", lineItemId).executeUpdate();
            }
            if (quotationId != null) {
                em.createNativeQuery("DELETE FROM quotation WHERE id = :id")
                        .setParameter("id", quotationId).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM component_sql_view WHERE sql_view_name = :n")
                    .setParameter("n", TEST_VIEW_NAME).executeUpdate();
            em.createNativeQuery("DELETE FROM component WHERE code = :c")
                    .setParameter("c", TEST_COMPONENT_CODE).executeUpdate();
            utx.commit();
        } catch (Exception e) {
            // 最佳努力清理；即便失败，本测试对真实业务表的唯一足迹是这几条随机 UUID 主键的行，
            // 不会被任何真实报价单/核价单查询命中。
            try { utx.rollback(); } catch (Exception ignore) { /* no-op */ }
        }
    }

    private Task buildTask(String usage) {
        Task t = new Task();
        t.componentId = componentId;
        t.customerId = null;
        t.partNo = null;
        t.partVersion = null;
        t.lineItemId = lineItemId;
        t.quotationId = quotationId;
        t.usage = usage;
        return t;
    }

    private Result runSingleTaskBatch(String usage) {
        BatchExpandDriverRequest req = new BatchExpandDriverRequest();
        req.tasks = new ArrayList<>();
        req.tasks.add(buildTask(usage));
        BatchExpandDriverResponse resp = resource.batchExpand(req).getData();
        assertEquals(1, resp.results.size());
        return resp.results.get(0);
    }

    private static boolean anyRowContainsV6Id(ExpandDriverResponse data) {
        if (data == null || data.rows == null) return false;
        for (ExpandDriverResponse.Row row : data.rows) {
            if (row.driverRow != null && row.driverRow.containsKey("__v6_id")) return true;
        }
        return false;
    }

    // ═══════════════════════ QUOTE：应命中快照（旁路点 1，既有优化不被误伤）═══════════════════════

    @Test
    void quoteUsage_hitsSnapshot_returnsV6IdRow() {
        Result r = runSingleTaskBatch("QUOTE");

        assertEquals("OK", r.status, "QUOTE usage 应正常返回，实际 error=" + r.error);
        assertNotNull(r.data, "QUOTE usage 命中快照后 data 不应为 null");
        assertEquals("snapshot", r.data.driverPath,
                "QUOTE usage 应经旁路点 1 命中 tryReadSnapshot（driverPath 固定标记为 \"snapshot\"），实际="
                        + r.data.driverPath);
        assertTrue(anyRowContainsV6Id(r.data),
                "QUOTE usage 命中快照后应能读到夹具里的 __v6_id 行，实际 rows=" + r.data.rows);
        String gotV6Id = (String) r.data.rows.get(0).driverRow.get("__v6_id");
        assertEquals(v6IdValue, gotV6Id, "QUOTE usage 读到的 __v6_id 应等于本次夹具写入的值");
    }

    // ═══════════════════ COSTING / 非法值 / 缺省：绝不能命中快照（旁路点 2）═══════════════════

    @Test
    void costingUsage_bypassesSnapshot_noV6IdLeak() {
        Result r = runSingleTaskBatch("COSTING");
        assertBypassedSnapshot(r, "COSTING");
    }

    @Test
    void garbageUsage_bypassesSnapshot_noV6IdLeak() {
        Result r = runSingleTaskBatch("XXX");
        assertBypassedSnapshot(r, "非法值 XXX");
    }

    @Test
    void absentUsage_bypassesSnapshot_noV6IdLeak() {
        Result r = runSingleTaskBatch(null);
        assertBypassedSnapshot(r, "缺省(null)");
    }

    private void assertBypassedSnapshot(Result r, String scenarioLabel) {
        assertEquals("OK", r.status,
                scenarioLabel + " usage 应正常返回 OK（走真实展开，不应因旁路收紧而报错），实际 error=" + r.error);
        assertNotNull(r.data, scenarioLabel + " usage 的 data 不应为 null");
        assertNotEqualsSnapshotMarker(r.data.driverPath, scenarioLabel);
        assertFalse(anyRowContainsV6Id(r.data),
                scenarioLabel + " usage 绝不应读到快照夹具里的 __v6_id 行（会破 AC-17），实际 rows=" + r.data.rows);
    }

    private void assertNotEqualsSnapshotMarker(String driverPath, String scenarioLabel) {
        assertFalse("snapshot".equals(driverPath),
                scenarioLabel + " usage 的 driverPath 不应等于 \"snapshot\""
                        + "（说明仍走了 tryReadSnapshot/expandWithSnapshot 旁路，旁路点未被 usage 门控），实际="
                        + driverPath);
    }
}
