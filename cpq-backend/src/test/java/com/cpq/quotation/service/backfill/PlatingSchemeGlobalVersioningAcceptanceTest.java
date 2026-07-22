package com.cpq.quotation.service.backfill;

import com.cpq.component.dto.RuntimeContext;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.datasource.sqlview.SqlViewExecutor;
import com.cpq.datasource.sqlview.SqlViewRuntimeContext;
import com.cpq.quotation.dto.backfill.BackfillGroupDTO;
import com.cpq.quotation.dto.backfill.BackfillPreviewDTO;
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
 * task-0721 报价数据版本升级 · AC-18 {@code plating_scheme} 全局升版专项验收。
 *
 * <p>{@code plating_scheme} 无 {@code customer_no} 列（表结构已核实），轴 = {@code scheme_no}，
 * 全局共享、全局升版是<b>接受设计</b>（非缺陷，需求方 2026-07-21 确认）。本文件验证四点：
 * ①延迟（未审核前现役方案不受影响，他单渲染仍见旧方案）；②生效（核价通过后按 scheme_no
 * 全局升版，新版本对<b>任意</b>报价单可见，不按客户/单据隔离）；③冻结（已提交报价单读
 * snapshot 不受影响）；④预览标注 {@code isGlobalShared=true}。
 *
 * <p>本文件走 <b>FLIP 路径</b>（无 snapshot 表征），与 {@code QuoteBackfillFlipRouteTest} 同构，
 * 不受 {@code QuoteBackfillCollector.asUuid} 已知缺陷影响（该缺陷只在 CHANGE/DELETE 路径读取
 * {@code driverRow.__v6_id} 时触发，FLIP 路径由 Phase C 直接扫描 DB pending 行，不经过
 * driverRow/JsonNode 解析）。
 */
@QuarkusTest
class PlatingSchemeGlobalVersioningAcceptanceTest {

    private static final String TAG = "T0721PS";

    @Inject QuoteBackfillService backfillService;
    @Inject QuoteBackfillPreviewService previewService;
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

    private void writeComponentData(UUID lineItemId, UUID componentId, String tabName, String snapshotRowsJson) {
        em.createNativeQuery(
                "INSERT INTO quotation_line_component_data " +
                "(id, line_item_id, component_id, tab_name, snapshot_rows, row_data, deleted_row_keys, created_at) " +
                "VALUES (:id, :lid, :cid, :tab, CAST(:sr AS jsonb), '[]', '[]', now())")
            .setParameter("id", UUID.randomUUID()).setParameter("lid", lineItemId).setParameter("cid", componentId)
            .setParameter("tab", tabName).setParameter("sr", snapshotRowsJson)
            .executeUpdate();
    }

    /** 插一条 plating_scheme 行，返回其 id。 */
    private UUID insertScheme(String schemeNo, String schemeVersion, BigDecimal thickness,
                               boolean isCurrent, UUID pendingQid, UUID supersedes) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO plating_scheme (id, system_type, scheme_no, scheme_version, seq_no, plating_element, " +
                "  plating_method, surface_area, plating_thickness, element_usage, is_current, pending_quotation_id, " +
                "  pending_supersedes, created_at, updated_at) " +
                "VALUES (:id, 'QUOTE', :sn, :sv, 1, 'Ni', 'ELECTRO', 10.0, :thk, 5.0, :cur, :pq, " +
                "  CASE WHEN CAST(:sup AS uuid) IS NULL THEN NULL ELSE ARRAY[CAST(:sup AS uuid)] END, now(), now())")
            .setParameter("id", id).setParameter("sn", schemeNo).setParameter("sv", schemeVersion)
            .setParameter("thk", thickness).setParameter("cur", isCurrent).setParameter("pq", pendingQid)
            .setParameter("sup", supersedes)
            .executeUpdate();
        return id;
    }

    private BigDecimal currentThickness(String schemeNo) {
        List<Object> rows = em.createNativeQuery(
                "SELECT plating_thickness FROM plating_scheme WHERE scheme_no = :sn AND is_current = true")
            .setParameter("sn", schemeNo).getResultList();
        return rows.isEmpty() ? null : (BigDecimal) rows.get(0);
    }

    private UUID newSchemeComponent() {
        Component c = new Component();
        c.name = TAG + "-电镀方案";
        c.code = TAG + "-" + UUID.randomUUID().toString().substring(0, 8);
        c.fields = "[]";
        c.formulas = "[]";
        c.tabType = "零件";
        c.partNoField = "hf_part_no";
        c.dataDriverPath = "$" + (TAG + "_scheme").toLowerCase() + UUID.randomUUID().toString().substring(0, 4);
        c.persist();

        ComponentSqlView view = new ComponentSqlView();
        view.componentId = c.id;
        view.sqlViewName = c.dataDriverPath.substring(1);
        view.sqlTemplate =
            "SELECT\n" +
            "  ps.scheme_no AS hf_part_no,\n" +
            "  ps.seq_no AS _序号,\n" +
            "  ps.plating_thickness AS _厚度\n" +
            "FROM plating_scheme ps\n" +
            "WHERE ps.system_type = 'QUOTE' AND ps.is_current = true\n" +
            "ORDER BY ps.seq_no";
        view.declaredColumns = "[]";
        view.persist();
        em.flush();
        return c.id;
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-18 ①延迟：未审核前现役方案不受影响，他单渲染仍见旧方案
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void delay_officialSchemeUnaffectedBeforeApproval_otherQuotationSeesOldValue() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String schemeNo = "PS1" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        UUID componentId = newSchemeComponent();
        UUID q1 = newQuotation(customerId, salesRepId, "DRAFT");
        UUID q2 = newQuotation(customerId, salesRepId, "DRAFT");

        UUID officialRow = insertScheme(schemeNo, "2000", new BigDecimal("5.000"), true, null, null);
        insertScheme(schemeNo, "2001", new BigDecimal("9.900"), false, q1, officialRow);

        assertEquals(0, new BigDecimal("5.000").compareTo(currentThickness(schemeNo)),
            "导入未审核前，现役方案值不应变化");

        SqlViewRuntimeContext.set(componentId, null, q2, "DRAFT");
        try {
            List<Map<String, Object>> rowsQ2 = sqlViewExecutor.executeAllRows(
                "$" + view(componentId), new RuntimeContext(), List.of(schemeNo));
            assertEquals(1, rowsQ2.size());
            assertEquals(0, new BigDecimal("5.000").compareTo((BigDecimal) rowsQ2.get(0).get("_厚度")),
                "他单(Q2)在 Q1 审核通过前应只看到旧官方方案值");
        } finally {
            SqlViewRuntimeContext.clear();
        }
    }

    private String view(UUID componentId) {
        Object r = em.createNativeQuery("SELECT sql_view_name FROM component_sql_view WHERE component_id = :cid")
            .setParameter("cid", componentId).getSingleResult();
        return (String) r;
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-18 ②生效：核价通过后按 scheme_no 全局升版，新版本对任意报价单可见（非按客户隔离）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void accept_globalUpgradeVisibleToUnrelatedQuotation() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String schemeNo = "PS2" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        UUID componentId = newSchemeComponent();
        UUID q1 = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID q2 = newQuotation(customerId, salesRepId, "DRAFT"); // 与 q1 无关的另一张单

        UUID officialRow = insertScheme(schemeNo, "2000", new BigDecimal("5.000"), true, null, null);
        insertScheme(schemeNo, "2001", new BigDecimal("9.900"), false, q1, officialRow);

        QuoteBackfillService.Summary summary = backfillService.execute(q1, finance);
        assertTrue(summary.versionedGroups >= 1, "应识别到至少 1 个 FLIP 组(plating_scheme)");

        assertEquals(0, new BigDecimal("9.900").compareTo(currentThickness(schemeNo)),
            "通过后应全局生效为新值 9.900");

        // 与 Q1 完全无关的 Q2，读取该 $view 也应看到新值（全局共享，非按客户/单据隔离，AC-18 接受设计）
        SqlViewRuntimeContext.set(componentId, null, q2, "DRAFT");
        try {
            List<Map<String, Object>> rowsQ2 = sqlViewExecutor.executeAllRows(
                "$" + view(componentId), new RuntimeContext(), List.of(schemeNo));
            assertEquals(1, rowsQ2.size());
            assertEquals(0, new BigDecimal("9.900").compareTo((BigDecimal) rowsQ2.get(0).get("_厚度")),
                "与 Q1 无关的 Q2 也应看到全局升版后的新值（AC-18 全局共享是接受设计）");
        } finally {
            SqlViewRuntimeContext.clear();
        }

        // 单列 native 查询 Hibernate 返回裸标量(非 Object[])
        Boolean oldRow = (Boolean) em.createNativeQuery("SELECT is_current FROM plating_scheme WHERE id = :id")
            .setParameter("id", officialRow).getSingleResult();
        assertEquals(Boolean.FALSE, oldRow, "旧版本应降 is_current=false（留存可查）");
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-18 ③冻结：已提交报价单读 snapshot 不受他单全局升版影响
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void freeze_approvedQuotationSnapshotUnaffectedByGlobalUpgrade() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        UUID finance = financeUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String schemeNo = "PS3" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        UUID componentId = newSchemeComponent();

        // Q0：已审核（冻结），snapshot 写死旧值 5.000
        UUID q0 = newQuotation(customerId, salesRepId, "APPROVED");
        UUID q0Line = newLineItem(q0, "DUMMY-PRODUCT");
        String q0Snapshot = "[{\"driverRow\":{\"hf_part_no\":\"" + schemeNo + "\",\"_序号\":1,\"_厚度\":5.000}}]";
        writeComponentData(q0Line, componentId, "电镀方案", q0Snapshot);
        String q0Before = readSnapshotRows(q0Line, componentId);

        UUID q1 = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID officialRow = insertScheme(schemeNo, "2000", new BigDecimal("5.000"), true, null, null);
        insertScheme(schemeNo, "2001", new BigDecimal("12.345"), false, q1, officialRow);
        backfillService.execute(q1, finance);
        assertEquals(0, new BigDecimal("12.345").compareTo(currentThickness(schemeNo)));

        String q0After = readSnapshotRows(q0Line, componentId);
        assertEquals(q0Before, q0After, "已审核报价单 Q0 的 snapshot_rows 不应因他单全局升版而改变（AC-18 冻结）");
    }

    private String readSnapshotRows(UUID lineId, UUID componentId) {
        List<Object> r = em.createNativeQuery(
                "SELECT snapshot_rows::text FROM quotation_line_component_data " +
                "WHERE line_item_id = :lid AND component_id = :cid")
            .setParameter("lid", lineId).setParameter("cid", componentId).getResultList();
        return r.isEmpty() ? null : (String) r.get(0);
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-18 ④预览标注 isGlobalShared=true
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void preview_marksPlatingSchemeGroupAsGlobalShared() {
        UUID customerId = resolveCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        String schemeNo = "PS4" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        UUID q1 = newQuotation(customerId, salesRepId, "SUBMITTED");
        UUID officialRow = insertScheme(schemeNo, "2000", new BigDecimal("5.000"), true, null, null);
        insertScheme(schemeNo, "2001", new BigDecimal("9.900"), false, q1, officialRow);

        BackfillPreviewDTO preview = previewService.preview(q1);
        BackfillGroupDTO schemeGroup = preview.groups.stream()
            .filter(g -> "plating_scheme".equals(g.table)).findFirst().orElse(null);
        assertNotNull(schemeGroup, "预览应包含 plating_scheme 组，实际 groups=" + preview.groups.size());
        assertTrue(schemeGroup.isGlobalShared, "plating_scheme 组预览应标注 isGlobalShared=true（AC-18 信息性提示）");
    }
}
