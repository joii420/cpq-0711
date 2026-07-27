package com.cpq.quotation.service.backfill;

import com.cpq.component.dto.RuntimeContext;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.datasource.sqlview.SqlViewExecutor;
import com.cpq.datasource.sqlview.SqlViewRuntimeContext;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * repair-0727 · cpq-tester 独立验证 · AC-R3 树锚点 + 传导（需求说明 §4 AC-R3，test-cases.md §4，
 * 对应 BE-R3-07~10）。
 *
 * <p><b>为什么这是独立于既有测试的新覆盖</b>：现有 {@code QuotePendingRewriterTest}/
 * {@code QuoteBackfillColumnMapperTest} 的 set-op 用例（{@code unionAll_bothBranchesWhitelisted_*}/
 * {@code resolvesCpViewSetOpBranchColumns}）只验证到"改写后的 SQL 文本 + LIMIT 0 元数据探测"这一层，
 * 从未<b>真正执行</b>过改写后的 SQL 拿到业务数据行——即没有证明"锚点真的能流进一次真实查询返回的
 * 行里"。本文件用 {@link SqlViewExecutor#executeAllRows} 走真实执行路径（与
 * {@code QuoteBackfillFlatAcceptanceTest#otherQuotationIsolation_...} 同一技术），验证：
 * <ol>
 *   <li>{@link #realSetOpTreeViewExecution_anchorFlowsIntoActualRows}：两分支 UNION ALL 的树视图
 *       （边行 material_bom_item + 根行 material_master，与真实 {@code bom_view} 同构）执行后，
 *       边行返回的每一行 {@code __v6_id} 都是真实存在的 material_bom_item.id，根行 {@code __v6_id}
 *       为 null（占位对齐，不是"忘了填"）。</li>
 *   <li>{@link #treeDeleteWithPartialCoverage_untouchedSiblingsSurvive}：树页签只表征组内 1 个节点
 *       并将其墓碑删除（走 {@code deleted_tree_nodes}），其余未表征的兄弟节点必须原样保留——
 *       这是 D1 场景在树页签上的对照（{@code QuoteBackfillMasterChildAcceptanceTest} 现有的
 *       {@code treeManualLeaf_recognizedAndBackfilled} 是"从零建组"场景，从未验证过树页签的删除
 *       传导对兄弟节点的影响）。</li>
 *   <li>{@link #treeChangePropagates_untouchedSiblingsUnaffected}：树页签改一个节点的值，验证新值
 *       正确落库，兄弟节点不受影响。</li>
 * </ol>
 */
@QuarkusTest
class QuoteBackfillTreeAnchorPropagationTest {

    private static final String TAG = "REPAIR0727TREE";

    @Inject QuoteBackfillService backfillService;
    @Inject SqlViewExecutor sqlViewExecutor;
    @Inject EntityManager em;

    @SuppressWarnings("unchecked")
    private UUID resolveCustomerId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    @SuppressWarnings("unchecked")
    private UUID resolveUserId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    @SuppressWarnings("unchecked")
    private UUID financeUserId() {
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM \"user\" WHERE role = 'PRICING_MANAGER' LIMIT 1").getResultList();
        if (!rows.isEmpty()) return UUID.fromString(rows.get(0).toString());
        UUID fid = UUID.randomUUID();
        String suffix = fid.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, role, password_hash, created_at, updated_at) " +
                "VALUES(:id, :un, 'Test Finance', :email, 'PRICING_MANAGER', 'hash', now(), now())")
            .setParameter("id", fid).setParameter("un", "test_finance_" + suffix)
            .setParameter("email", "test_finance_" + suffix + "@test.invalid")
            .executeUpdate();
        return fid;
    }

    private String customerCodeOf(UUID customerId) {
        return (String) em.createNativeQuery("SELECT code FROM customer WHERE id = :cid")
            .setParameter("cid", customerId).getSingleResult();
    }

    private UUID newQuotation(UUID customerId, UUID salesRepId, String status) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                " tax_rate, tax_amount, created_at, updated_at) " +
                "VALUES (:id, :qn, :cid, :name, :srid, :status, 0, 0, now(), now())")
            .setParameter("id", id).setParameter("qn", TAG + "-" + id.toString().substring(0, 8))
            .setParameter("cid", customerId).setParameter("name", TAG + "-quotation")
            .setParameter("srid", salesRepId).setParameter("status", status)
            .executeUpdate();
        return id;
    }

    private UUID newLineItem(UUID quotationId, String productPartNo) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot, sort_order, created_at) " +
                "VALUES (:id, :qid, :pn, 0, now())")
            .setParameter("id", id).setParameter("qid", quotationId).setParameter("pn", productPartNo)
            .executeUpdate();
        return id;
    }

    private void setDeletedTreeNodes(UUID lineItemId, String json) {
        em.createNativeQuery("UPDATE quotation_line_item SET deleted_tree_nodes = CAST(:j AS jsonb) WHERE id = :id")
            .setParameter("j", json).setParameter("id", lineItemId).executeUpdate();
    }

    /** 真实 bom_view 形状：边行 material_bom_item(白名单) UNION ALL 根行 material_master(非白名单)。 */
    private static final String BOM_VIEW_SQL =
        "SELECT\n" +
        "  mbi.component_no AS material_no, mbi.material_no AS parent_no,\n" +
        "  mbi.composition_qty AS _组成数量, mbi.issue_unit AS _组成单位\n" +
        "FROM material_bom_item mbi\n" +
        "WHERE mbi.system_type = 'QUOTE' AND mbi.is_current AND mbi.component_no = ANY(:total_material_no)\n" +
        "UNION ALL\n" +
        "SELECT mm.material_no, NULL::text, NULL::numeric, mm.standard_unit\n" +
        "FROM material_master mm\n" +
        "WHERE mm.material_no = ANY(:total_material_no)\n" +
        "  AND NOT EXISTS (SELECT 1 FROM material_bom_item x WHERE x.component_no = mm.material_no\n" +
        "                  AND x.system_type = 'QUOTE' AND x.is_current)";

    private UUID newTreeBomViewComponent(String suffix) {
        Component c = new Component();
        c.name = TAG + "-树视图-" + suffix;
        c.code = TAG + "-TV-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6);
        c.fields = "[]";
        c.formulas = "[]";
        c.tabType = "BOM";
        c.bomRecursiveExpand = true;
        c.dataDriverPath = "$" + (TAG + "_tv_" + suffix).toLowerCase();
        c.persist();

        ComponentSqlView view = new ComponentSqlView();
        view.componentId = c.id;
        view.sqlViewName = (TAG + "_tv_" + suffix).toLowerCase();
        view.sqlTemplate = BOM_VIEW_SQL;
        view.declaredColumns = "[]";
        view.persist();
        em.flush();
        return c.id;
    }

    private UUID insertPendingBomItem(UUID quotationId, String customerNo, String rootNo, String componentNo,
                                       String characteristic, BigDecimal compositionQty, BigDecimal baseQty,
                                       String issueUnit) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO material_bom_item (id, system_type, customer_no, material_no, component_no, " +
                "  characteristic, composition_qty, base_qty, issue_unit, bom_version, is_current, " +
                "  pending_quotation_id, created_at, updated_at) " +
                "VALUES (:id, 'QUOTE', :cn, :mn, :comp, :ch, :cq, :bq, :iu, '2001', false, :pq, now(), now())")
            .setParameter("id", id).setParameter("cn", customerNo).setParameter("mn", rootNo)
            .setParameter("comp", componentNo).setParameter("ch", characteristic)
            .setParameter("cq", compositionQty).setParameter("bq", baseQty).setParameter("iu", issueUnit)
            .setParameter("pq", quotationId)
            .executeUpdate();
        return id;
    }

    /** 现役 material_bom_item 行（走 is_current=true，供真实 SQL 执行测试用；不挂 pending）。 */
    private UUID insertCurrentBomItem(String customerNo, String rootNo, String componentNo, String characteristic,
                                       BigDecimal compositionQty, String issueUnit) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO material_bom_item (id, system_type, customer_no, material_no, component_no, " +
                "  characteristic, composition_qty, issue_unit, bom_version, is_current, created_at, updated_at) " +
                "VALUES (:id, 'QUOTE', :cn, :mn, :comp, :ch, :cq, :iu, '2000', true, now(), now())")
            .setParameter("id", id).setParameter("cn", customerNo).setParameter("mn", rootNo)
            .setParameter("comp", componentNo).setParameter("ch", characteristic)
            .setParameter("cq", compositionQty).setParameter("iu", issueUnit)
            .executeUpdate();
        return id;
    }

    private void writeComponentData(UUID lineItemId, UUID componentId, String tabName, String snapshotRowsJson) {
        em.createNativeQuery(
                "INSERT INTO quotation_line_component_data " +
                "(id, line_item_id, component_id, tab_name, snapshot_rows, row_data, deleted_row_keys, sort_order, created_at) " +
                "VALUES (:id, :lid, :cid, :tab, CAST(:sr AS jsonb), '[]'::jsonb, '[]'::jsonb, 0, now())")
            .setParameter("id", UUID.randomUUID()).setParameter("lid", lineItemId).setParameter("cid", componentId)
            .setParameter("tab", tabName).setParameter("sr", snapshotRowsJson)
            .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> currentBomItemsFull(String customerNo, String rootMaterialNo) {
        return em.createNativeQuery(
                "SELECT component_no, characteristic, composition_qty FROM material_bom_item " +
                "WHERE system_type = 'QUOTE' AND customer_no = :cn AND material_no = :mn AND is_current = true " +
                "ORDER BY component_no")
            .setParameter("cn", customerNo).setParameter("mn", rootMaterialNo)
            .getResultList();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 1：真实执行两分支 UNION ALL 树视图，验证锚点真的流进返回行（不只是 SQL 文本层面）。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void realSetOpTreeViewExecution_anchorFlowsIntoActualRows() {
        UUID customerId = resolveCustomerId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String rootNo = "TREX" + UUID.randomUUID().toString().substring(0, 7).toUpperCase();
        String childNo = "TREXC" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String leafNo = "TREXL" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(); // 无 BOM 分解，走根行分支

        UUID componentId = newTreeBomViewComponent("EXEC");
        UUID edgeRowId = insertCurrentBomItem(customerNo, rootNo, childNo, "RECIPE", new BigDecimal("1.5"), "g/PCS");

        // leafNo 在 material_master 有记录但没有 BOM 分解——走根行分支。
        UUID existing = (UUID) em.createNativeQuery("SELECT id FROM material_master WHERE material_no = :mn")
            .setParameter("mn", leafNo).getResultList().stream().findFirst().orElse(null);
        if (existing == null) {
            em.createNativeQuery(
                    "INSERT INTO material_master (id, material_no, material_name, standard_unit, created_at, updated_at) " +
                    "VALUES (:id, :mn, :name, 'EA', now(), now())")
                .setParameter("id", UUID.randomUUID()).setParameter("mn", leafNo).setParameter("name", TAG + "-leaf")
                .executeUpdate();
        }

        UUID quotationId = newQuotation(customerId, resolveUserId(), "DRAFT");
        SqlViewRuntimeContext.set(componentId, null, quotationId, "DRAFT");
        com.cpq.datasource.sqlview.BomTreeVarsContext.set(new com.cpq.datasource.sqlview.BomTreeVarsContext.Vars(
            null, List.of(rootNo, childNo, leafNo)));
        try {
            List<Map<String, Object>> rows = sqlViewExecutor.executeAllRows(
                "$" + (TAG + "_tv_EXEC").toLowerCase(), buildCtx(customerNo), null);

            var edgeRow = rows.stream().filter(r -> childNo.equals(r.get("material_no"))).findFirst().orElse(null);
            assertNotNull(edgeRow, "边行分支应返回 childNo 这一行");
            assertNotNull(edgeRow.get("__v6_id"), "边行(material_bom_item 白名单分支) __v6_id 不应为 null");
            assertEquals(edgeRowId.toString(), String.valueOf(edgeRow.get("__v6_id")),
                "边行 __v6_id 应真实等于 material_bom_item.id，而不是随便一个非空占位");

            var rootRow = rows.stream().filter(r -> leafNo.equals(r.get("material_no"))).findFirst().orElse(null);
            assertNotNull(rootRow, "根行分支应返回 leafNo 这一行（无 BOM 分解，NOT EXISTS 命中）");
            assertNull(rootRow.get("__v6_id"), "根行(material_master 非白名单分支) __v6_id 应为 null 占位，不是真实值");
        } finally {
            SqlViewRuntimeContext.clear();
            com.cpq.datasource.sqlview.BomTreeVarsContext.clear();
        }
    }

    private RuntimeContext buildCtx(String customerCode) {
        RuntimeContext ctx = new RuntimeContext();
        ctx.quotation = new RuntimeContext.QuotationContext();
        ctx.quotation.customerCode = customerCode;
        return ctx;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 2：树页签墓碑删除组内 1 个节点（deleted_tree_nodes），其余未表征的兄弟节点原样保留。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void treeDeleteWithPartialCoverage_untouchedSiblingsSurvive() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String rootNo = "TRDL" + UUID.randomUUID().toString().substring(0, 7).toUpperCase();
        String recipe1 = "TRDLR1" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        String recipe2 = "TRDLR2" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        String outsourced = "TRDLO" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();

        UUID componentId = newTreeBomViewComponent("DEL");
        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID lineItemId = newLineItem(quotationId, rootNo);

        insertPendingBomItem(quotationId, customerNo, rootNo, recipe1, "RECIPE",
            new BigDecimal("1.0"), new BigDecimal("0.5"), "g/PCS");
        insertPendingBomItem(quotationId, customerNo, rootNo, recipe2, "RECIPE",
            new BigDecimal("2.0"), new BigDecimal("0.7"), "g/PCS");
        UUID outId = insertPendingBomItem(quotationId, customerNo, rootNo, outsourced, "OUTSOURCED",
            new BigDecimal("3.0"), new BigDecimal("9.9"), "EA");

        // 树 snapshot_rows：只表征 outsourced 这 1 个节点（collectTreeRows 按 driverRow.__v6_id 识别 CHANGE）。
        String nodeId = rootNo + "/" + outsourced;
        String snapshotRows = "[{\"driverRow\":{\"material_no\":\"" + outsourced + "\",\"parent_no\":\"" + rootNo +
            "\",\"_组成数量\":3.0,\"_组成单位\":\"EA\",\"__v6_id\":\"" + outId + "\"},\"__nodeId\":\"" + nodeId + "\"}]";
        writeComponentData(lineItemId, componentId, "BOM树", snapshotRows);

        // 通过 deleted_tree_nodes 命中该节点。
        setDeletedTreeNodes(lineItemId, "[\"" + nodeId + "\"]");

        QuoteBackfillService.Summary summary = backfillService.execute(quotationId, finance);
        assertEquals(1, summary.deletedRows, "应识别到 1 行删除，实际=" + summary.deletedRows);

        List<Object[]> items = currentBomItemsFull(customerNo, rootNo);
        assertEquals(2, items.size(), "★树页签核心断言：未被表征的 recipe1/recipe2 应原样保留，删除节点后剩 2 行而非 0 行");
        Map<String, Object[]> byComp = new java.util.HashMap<>();
        for (Object[] r : items) byComp.put((String) r[0], r);
        assertNotNull(byComp.get(recipe1), "recipe1 应存活");
        assertNotNull(byComp.get(recipe2), "recipe2 应存活");
        assertNull(byComp.get(outsourced), "outsourced 应已被删除");

        // 本 fixture 3 行全部以"本单 pending"身份直接插入，没有独立的官方前身行（该组首次创建场景，
        // 同 BackfillPreviewFidelityTest#deleteWithPartialCoverage_... 的口径）——outId 只是一条
        // pending 暂存行，会随 QuoteBackfillService.cleanupPending 统一物理清理，这是正确行为，
        // 不是 bug；AC-7"物理保留"语义只适用于有官方前身(is_current=true,pending_quotation_id=NULL)
        // 被取代的场景。
        long stillExists = ((Number) em.createNativeQuery("SELECT count(*) FROM material_bom_item WHERE id = :id")
            .setParameter("id", outId).getSingleResult()).longValue();
        assertEquals(0L, stillExists, "纯 pending 基底(无官方前身)的墓碑行应随 cleanupPending 一并清理");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 用例 3：树页签改一个节点的值，验证新值落库、兄弟节点不受影响。
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void treeChangePropagates_untouchedSiblingsUnaffected() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String customerNo = customerCodeOf(customerId);
        String rootNo = "TRCH" + UUID.randomUUID().toString().substring(0, 7).toUpperCase();
        String recipe1 = "TRCHR1" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        String assembly = "TRCHASM" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();

        UUID componentId = newTreeBomViewComponent("CHG");
        UUID quotationId = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID lineItemId = newLineItem(quotationId, rootNo);

        insertPendingBomItem(quotationId, customerNo, rootNo, recipe1, "RECIPE",
            new BigDecimal("1.0"), new BigDecimal("0.5"), "g/PCS");
        UUID asmId = insertPendingBomItem(quotationId, customerNo, rootNo, assembly, "ASSEMBLY",
            new BigDecimal("2.0"), new BigDecimal("1.1"), "EA");

        String nodeId = rootNo + "/" + assembly;
        // 树里把「组成数量」从 2.0 改成 3.5。
        String snapshotRows = "[{\"driverRow\":{\"material_no\":\"" + assembly + "\",\"parent_no\":\"" + rootNo +
            "\",\"_组成数量\":3.5,\"_组成单位\":\"EA\",\"__v6_id\":\"" + asmId + "\"},\"__nodeId\":\"" + nodeId + "\"}]";
        writeComponentData(lineItemId, componentId, "BOM树", snapshotRows);

        QuoteBackfillService.Summary summary = backfillService.execute(quotationId, finance);
        assertEquals(1, summary.changedRows, "应识别到 1 行改值");

        List<Object[]> items = currentBomItemsFull(customerNo, rootNo);
        assertEquals(2, items.size(), "recipe1(未表征)+assembly(改值) 应共 2 行");
        Map<String, Object[]> byComp = new java.util.HashMap<>();
        for (Object[] r : items) byComp.put((String) r[0], r);
        assertNotNull(byComp.get(recipe1), "recipe1 应存活（树里从未提及）");
        assertEquals(0, new BigDecimal("1.0").compareTo((BigDecimal) byComp.get(recipe1)[2]), "recipe1 值不应被改动");
        assertEquals(0, new BigDecimal("3.5").compareTo((BigDecimal) byComp.get(assembly)[2]), "assembly 应取树里改后的新值 3.5");
    }
}
