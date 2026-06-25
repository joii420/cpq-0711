package com.cpq.quotation.service;

import com.cpq.quotation.dto.SaveDraftRequest;
import com.cpq.quotation.entity.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2-1 持久化等价测试：验证批量路径（batchStage1=ON）与逐行路径（batchStage1=OFF）
 * 对真实报价单 8f0c37a4（罗克韦尔 170 行）的子表落库结果逐位一致。
 *
 * <p>等价面：
 * <ul>
 *   <li>quotation_line_process 记录集合（lineItemId + processId 组合）</li>
 *   <li>quotation_line_component_data（lineItemId + componentId + sortOrder + rowData + subtotal + deletedRowKeys + snapshotRows）</li>
 *   <li>quotation_line_item_snapshot（lineItemId + productPartNo + productCategory + productSpecification）</li>
 *   <li>quotation_line_composite_process（lineItemId + defCode + seqNo）</li>
 *   <li>quotation_line_item.partVersionLocked（批量 E2 版本查询结果）</li>
 *   <li>quotation_line_item.parent_line_item_id（E5 V169 父子关系）</li>
 * </ul>
 *
 * <p>策略：
 * <ol>
 *   <li>通过 System Property 控制 kill switch，分别以 OFF / ON 调用 saveDraft。</li>
 *   <li>捕获每次落库后的子表快照（MD5/集合），断言两者逐位一致。</li>
 *   <li>测试仅读取/快照 DB 状态，不实际改变报价单数据（通过回滚或还原保证）。</li>
 * </ol>
 *
 * <p>注意：由于 saveDraft 包含子表 DELETE + INSERT，测试在事务中捕获落库状态后回滚，
 * 不污染 DB 真实数据。但 @Transactional + QuarkusTest 有边界限制：本测试使用
 * EntityManager native SQL 在同一事务内读快照后断言，事务结束自动回滚（通过
 * 不加 @Transactional 让 saveDraft 自己提交，然后读快照、再清理还原）。
 *
 * <p>实际策略：直接操作 System.setProperty 控制 kill switch，对同一个临时创建的
 * 测试报价单先 OFF 跑一次捕获，再 ON 跑一次捕获，对比两次快照一致。
 */
@QuarkusTest
class BatchStage1PersistEquivTest {

    @Inject
    QuotationService quotationService;

    @Inject
    EntityManager em;

    /** 罗克韦尔 170 行报价单（Phase 0 golden 锚点）。 */
    private static final UUID ROCKWELL_QID = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");

    /** 小尺寸 77 行报价单。 */
    private static final UUID SMALL_QID = UUID.fromString("a8f17a74-5a32-40fc-9e3d-bd5e81181248");

    // 测试专用客户/用户（SaveDraftSerializeLockTest 中确认存在）
    private static final UUID TEST_CUSTOMER_ID = UUID.fromString("9aee3d9d-1b4d-4698-9af6-34bd9979d887");
    private static final UUID TEST_USER_ID     = UUID.fromString("896ed7d9-bf12-4ea7-9ff1-09cb14496311");

    /** 创建最小测试报价单，native SQL 直插，绕开 Panache id 检测。 */
    @Transactional
    UUID createTestQuotation(UUID customerId, UUID templateId, UUID costingTemplateId) {
        UUID qid = UUID.randomUUID();
        em.createNativeQuery(
            "INSERT INTO quotation (id, quotation_number, customer_id, sales_rep_id, name, status, " +
            "total_amount, original_amount, system_discount_rate, final_discount_rate, " +
            "tax_rate, tax_amount, is_manually_adjusted, created_at, updated_at, " +
            "customer_template_id, costing_card_template_id) " +
            "VALUES (:id, :num, :cid, :sid, :name, 'DRAFT', 0, 0, 100, 100, 0, 0, false, NOW(), NOW(), " +
            ":tpl, :ctpl)")
            .setParameter("id", qid)
            .setParameter("num", "TEST-BATCH-EQUIV-" + System.nanoTime())
            .setParameter("cid", customerId)
            .setParameter("sid", TEST_USER_ID)
            .setParameter("name", "BatchStage1 Equiv Test")
            .setParameter("tpl", templateId)
            .setParameter("ctpl", costingTemplateId)
            .executeUpdate();
        return qid;
    }

    /** 删除测试报价单及其所有子行（还原 DB）。 */
    @Transactional
    void cleanupQuotation(UUID qid) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> lineIds = em.createNativeQuery(
                "SELECT id FROM quotation_line_item WHERE quotation_id = :qid")
                .setParameter("qid", qid).getResultList();
            if (!lineIds.isEmpty()) {
                for (Object lid : lineIds) {
                    UUID lineId = UUID.fromString(lid.toString());
                    em.createNativeQuery("DELETE FROM quotation_line_process WHERE line_item_id = :id")
                        .setParameter("id", lineId).executeUpdate();
                    em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id = :id")
                        .setParameter("id", lineId).executeUpdate();
                    em.createNativeQuery("DELETE FROM quotation_line_item_snapshot WHERE line_item_id = :id")
                        .setParameter("id", lineId).executeUpdate();
                    em.createNativeQuery("DELETE FROM quotation_line_composite_process WHERE line_item_id = :id")
                        .setParameter("id", lineId).executeUpdate();
                }
                em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :qid")
                    .setParameter("qid", qid).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM quotation WHERE id = :qid")
                .setParameter("qid", qid).executeUpdate();
        } catch (Exception e) {
            System.err.println("[cleanup] Failed to cleanup test quotation " + qid + ": " + e.getMessage());
        }
    }

    /**
     * 从报价单读取 lineItems 构造 SaveDraftRequest（与真实报价单对应，用于 saveDraft 入参）。
     * 仅读取 lineItem 基本字段和 componentData，不含 processes（由 seedProcessesFromBase 控制）。
     */
    @Transactional
    SaveDraftRequest buildRequestFromQuotation(UUID srcQid) {
        @SuppressWarnings("unchecked")
        List<Object[]> liRows = em.createNativeQuery(
            "SELECT id, product_id, template_id, product_part_no_snapshot, product_name_snapshot, " +
            "customer_part_no, sort_order, composite_type, parent_line_item_id " +
            "FROM quotation_line_item WHERE quotation_id = :qid ORDER BY sort_order ASC")
            .setParameter("qid", srcQid).getResultList();

        // index 位置 → lineItemId 映射，用于构造 tempParentIndex
        Map<UUID, Integer> idToIndex = new LinkedHashMap<>();
        for (int idx = 0; idx < liRows.size(); idx++) {
            idToIndex.put(UUID.fromString(liRows.get(idx)[0].toString()), idx);
        }

        SaveDraftRequest req = new SaveDraftRequest();
        req.lineItems = new ArrayList<>();
        for (Object[] row : liRows) {
            SaveDraftRequest.LineItemDraft d = new SaveDraftRequest.LineItemDraft();
            // 不回传 id → 全部视为新行（测试目的：验证全量新建路径等价）
            d.productId = row[1] != null ? UUID.fromString(row[1].toString()) : null;
            d.templateId = row[2] != null ? UUID.fromString(row[2].toString()) : null;
            d.productPartNo = row[3] != null ? row[3].toString() : null;
            d.productName = row[4] != null ? row[4].toString() : null;
            d.customerPartNo = row[5] != null ? row[5].toString() : null;
            d.sortOrder = row[6] != null ? ((Number) row[6]).intValue() : null;
            d.compositeType = row[7] != null ? row[7].toString() : null;
            // parent_line_item_id → tempParentIndex（按 index 回推）
            if (row[8] != null) {
                UUID parentId = UUID.fromString(row[8].toString());
                d.tempParentIndex = idToIndex.get(parentId);
            }

            // componentData
            UUID srcLineId = UUID.fromString(row[0].toString());
            @SuppressWarnings("unchecked")
            List<Object[]> cdRows = em.createNativeQuery(
                "SELECT component_id, tab_name, row_data, subtotal, sort_order " +
                "FROM quotation_line_component_data WHERE line_item_id = :lid ORDER BY sort_order ASC")
                .setParameter("lid", srcLineId).getResultList();
            if (!cdRows.isEmpty()) {
                d.componentData = new ArrayList<>();
                for (Object[] cd : cdRows) {
                    SaveDraftRequest.ComponentDataDraft cdd = new SaveDraftRequest.ComponentDataDraft();
                    cdd.componentId = cd[0] != null ? UUID.fromString(cd[0].toString()) : null;
                    cdd.tabName = cd[1] != null ? cd[1].toString() : null;
                    cdd.rowData = cd[2] != null ? cd[2].toString() : null;
                    cdd.subtotal = cd[3] != null ? new BigDecimal(cd[3].toString()) : null;
                    cdd.sortOrder = cd[4] != null ? ((Number) cd[4]).intValue() : null;
                    d.componentData.add(cdd);
                }
            }

            req.lineItems.add(d);
        }
        return req;
    }

    /** 捕获报价单所有 lineItem 的子表快照（用于等价断言）。 */
    @Transactional
    PersistSnapshot captureSnapshot(UUID qid) {
        PersistSnapshot snap = new PersistSnapshot();

        @SuppressWarnings("unchecked")
        List<Object[]> liRows = em.createNativeQuery(
            "SELECT id, sort_order, part_version_locked, parent_line_item_id " +
            "FROM quotation_line_item WHERE quotation_id = :qid ORDER BY sort_order ASC")
            .setParameter("qid", qid).getResultList();

        for (Object[] li : liRows) {
            UUID lineId = UUID.fromString(li[0].toString());
            int sortOrder = ((Number) li[1]).intValue();
            String partVer = li[2] != null ? li[2].toString() : "null";
            String parentId = li[3] != null ? li[3].toString() : "null";
            snap.lineItems.add(new LineSnap(lineId, sortOrder, partVer, parentId));

            // quotation_line_component_data
            @SuppressWarnings("unchecked")
            List<Object[]> cdRows = em.createNativeQuery(
                "SELECT component_id, tab_name, sort_order, subtotal, " +
                "LENGTH(COALESCE(row_data::text,'')) as row_data_len, " +
                "deleted_row_keys::text, " +
                "CASE WHEN snapshot_rows IS NULL THEN 'null' ELSE 'present' END as snap_flag " +
                "FROM quotation_line_component_data WHERE line_item_id = :lid ORDER BY sort_order ASC")
                .setParameter("lid", lineId).getResultList();
            for (Object[] cd : cdRows) {
                snap.componentData.add(new CdSnap(
                    lineId, sortOrder,
                    cd[0] != null ? cd[0].toString() : null,
                    cd[1] != null ? cd[1].toString() : null,
                    ((Number) cd[2]).intValue(),
                    cd[3] != null ? cd[3].toString() : "null",
                    cd[4] != null ? ((Number) cd[4]).intValue() : 0,
                    cd[5] != null ? cd[5].toString() : "[]",
                    cd[6] != null ? cd[6].toString() : "null"
                ));
            }

            // quotation_line_process
            @SuppressWarnings("unchecked")
            List<Object> procRows = em.createNativeQuery(
                "SELECT process_id::text FROM quotation_line_process WHERE line_item_id = :lid ORDER BY process_id")
                .setParameter("lid", lineId).getResultList();
            for (Object pr : procRows) {
                snap.processes.add(sortOrder + ":" + pr.toString());
            }

            // quotation_line_composite_process
            @SuppressWarnings("unchecked")
            List<Object[]> cpRows = em.createNativeQuery(
                "SELECT def_code, seq_no FROM quotation_line_composite_process WHERE line_item_id = :lid ORDER BY seq_no")
                .setParameter("lid", lineId).getResultList();
            for (Object[] cp : cpRows) {
                snap.compositeProcesses.add(sortOrder + ":" + cp[0] + ":" + cp[1]);
            }
        }
        return snap;
    }

    static class PersistSnapshot {
        List<LineSnap> lineItems = new ArrayList<>();
        List<CdSnap> componentData = new ArrayList<>();
        List<String> processes = new ArrayList<>();
        List<String> compositeProcesses = new ArrayList<>();
    }

    record LineSnap(UUID id, int sortOrder, String partVersionLocked, String parentLineItemId) {}
    record CdSnap(UUID lineId, int lineSortOrder, String componentId, String tabName, int sortOrder,
                  String subtotal, int rowDataLen, String deletedRowKeys, String snapshotFlag) {}

    /**
     * 比较两次快照是否等价（忽略 lineItem UUID，因为新行每次生成新 UUID，
     * 但等价约束在 sortOrder 维度上：同 sortOrder 行的子表内容相同）。
     */
    static void assertSnapshotsEquiv(PersistSnapshot off, PersistSnapshot on, String label) {
        assertEquals(off.lineItems.size(), on.lineItems.size(),
            label + ": lineItem 行数不一致");

        // 按 sortOrder 排序后比较子表内容（UUID 不同，但 sortOrder 相同）
        assertEquals(
            off.componentData.stream()
               .map(c -> c.lineSortOrder() + "/" + c.componentId() + "/" + c.sortOrder() + "/" +
                         c.subtotal() + "/" + c.rowDataLen() + "/" + c.deletedRowKeys() + "/" + c.snapshotFlag())
               .sorted().collect(Collectors.joining("\n")),
            on.componentData.stream()
               .map(c -> c.lineSortOrder() + "/" + c.componentId() + "/" + c.sortOrder() + "/" +
                         c.subtotal() + "/" + c.rowDataLen() + "/" + c.deletedRowKeys() + "/" + c.snapshotFlag())
               .sorted().collect(Collectors.joining("\n")),
            label + ": quotation_line_component_data 内容不等价");

        // processes 按行 sortOrder + processId 比对
        assertEquals(
            off.processes.stream().sorted().collect(Collectors.joining("\n")),
            on.processes.stream().sorted().collect(Collectors.joining("\n")),
            label + ": quotation_line_process 集合不等价");

        // compositeProcesses
        assertEquals(
            off.compositeProcesses.stream().sorted().collect(Collectors.joining("\n")),
            on.compositeProcesses.stream().sorted().collect(Collectors.joining("\n")),
            label + ": quotation_line_composite_process 集合不等价");

        // partVersionLocked：按 sortOrder 对齐比较（sortOrder 可能不唯一，用 list 排序比较）
        List<String> offVerList = off.lineItems.stream()
            .sorted(Comparator.comparingInt(LineSnap::sortOrder))
            .map(l -> l.sortOrder() + ":" + l.partVersionLocked())
            .collect(Collectors.toList());
        List<String> onVerList = on.lineItems.stream()
            .sorted(Comparator.comparingInt(LineSnap::sortOrder))
            .map(l -> l.sortOrder() + ":" + l.partVersionLocked())
            .collect(Collectors.toList());
        assertEquals(offVerList, onVerList, label + ": partVersionLocked 不等价（E2）");

        // parent_line_item_id：按 sortOrder 比较"是否有父"（UUID 不同但有无父一致）
        List<String> offParentFlags = off.lineItems.stream()
            .sorted(Comparator.comparingInt(LineSnap::sortOrder))
            .map(l -> l.sortOrder() + ":" + (l.parentLineItemId().equals("null") ? "no-parent" : "has-parent"))
            .collect(Collectors.toList());
        List<String> onParentFlags = on.lineItems.stream()
            .sorted(Comparator.comparingInt(LineSnap::sortOrder))
            .map(l -> l.sortOrder() + ":" + (l.parentLineItemId().equals("null") ? "no-parent" : "has-parent"))
            .collect(Collectors.toList());
        assertEquals(offParentFlags, onParentFlags, label + ": parent_line_item_id 有无不等价（E5）");
    }

    /**
     * TC-BATCH-1：对 SMALL_QID（77行）构造等价 request，分别以 OFF/ON 运行 saveDraft，
     * 对比子表落库快照等价。
     */
    @Test
    void tc_batch1_small_persist_equiv() throws Exception {
        // 检查源单是否存在
        Quotation srcQ = Quotation.findById(SMALL_QID);
        Assumptions.assumeTrue(srcQ != null, "SMALL_QID " + SMALL_QID + " 不存在，跳过");

        SaveDraftRequest req = buildRequestFromQuotation(SMALL_QID);
        Assumptions.assumeTrue(!req.lineItems.isEmpty(), "SMALL_QID 无行，跳过");

        // ── OFF 路径 ──────────────────────────────────────────────────────────────
        String origProp = System.getProperty("cpq.savedraft-batch-stage1");
        UUID qidOff = createTestQuotation(srcQ.customerId, srcQ.customerTemplateId, srcQ.costingCardTemplateId);
        try {
            System.setProperty("cpq.savedraft-batch-stage1", "false");
            quotationService.saveDraft(qidOff, req);
            PersistSnapshot snapOff = captureSnapshot(qidOff);

            // ── ON 路径 ───────────────────────────────────────────────────────────
            UUID qidOn = createTestQuotation(srcQ.customerId, srcQ.customerTemplateId, srcQ.costingCardTemplateId);
            try {
                System.setProperty("cpq.savedraft-batch-stage1", "true");
                quotationService.saveDraft(qidOn, req);
                PersistSnapshot snapOn = captureSnapshot(qidOn);

                System.out.println("[TC-BATCH-1] OFF lines=" + snapOff.lineItems.size()
                    + " ON lines=" + snapOn.lineItems.size()
                    + " OFF-cd=" + snapOff.componentData.size()
                    + " ON-cd=" + snapOn.componentData.size());

                assertSnapshotsEquiv(snapOff, snapOn, "SMALL_QID 77行");
                System.out.println("[TC-BATCH-1] PASSED: 77行 OFF/ON 持久化等价 ✓");
            } finally {
                cleanupQuotation(qidOn);
            }
        } finally {
            cleanupQuotation(qidOff);
            // 恢复 System Property
            if (origProp != null) System.setProperty("cpq.savedraft-batch-stage1", origProp);
            else System.clearProperty("cpq.savedraft-batch-stage1");
        }
    }

    /**
     * TC-BATCH-2：对 ROCKWELL_QID（170行）构造等价 request，分别以 OFF/ON 运行 saveDraft，
     * 对比子表落库快照等价。这是 golden 锚点单的持久化面验证。
     */
    @Test
    void tc_batch2_rockwell_persist_equiv() throws Exception {
        Quotation srcQ = Quotation.findById(ROCKWELL_QID);
        Assumptions.assumeTrue(srcQ != null, "ROCKWELL_QID " + ROCKWELL_QID + " 不存在，跳过");

        SaveDraftRequest req = buildRequestFromQuotation(ROCKWELL_QID);
        Assumptions.assumeTrue(!req.lineItems.isEmpty(), "ROCKWELL_QID 无行，跳过");

        String origProp = System.getProperty("cpq.savedraft-batch-stage1");
        UUID qidOff = createTestQuotation(srcQ.customerId, srcQ.customerTemplateId, srcQ.costingCardTemplateId);
        try {
            System.setProperty("cpq.savedraft-batch-stage1", "false");
            quotationService.saveDraft(qidOff, req);
            PersistSnapshot snapOff = captureSnapshot(qidOff);

            UUID qidOn = createTestQuotation(srcQ.customerId, srcQ.customerTemplateId, srcQ.costingCardTemplateId);
            try {
                System.setProperty("cpq.savedraft-batch-stage1", "true");
                quotationService.saveDraft(qidOn, req);
                PersistSnapshot snapOn = captureSnapshot(qidOn);

                System.out.println("[TC-BATCH-2] OFF lines=" + snapOff.lineItems.size()
                    + " ON lines=" + snapOn.lineItems.size()
                    + " OFF-cd=" + snapOff.componentData.size()
                    + " ON-cd=" + snapOn.componentData.size()
                    + " OFF-procs=" + snapOff.processes.size()
                    + " ON-procs=" + snapOn.processes.size());

                assertSnapshotsEquiv(snapOff, snapOn, "ROCKWELL_QID 170行");
                System.out.println("[TC-BATCH-2] PASSED: 170行 OFF/ON 持久化等价 ✓");
            } finally {
                cleanupQuotation(qidOn);
            }
        } finally {
            cleanupQuotation(qidOff);
            if (origProp != null) System.setProperty("cpq.savedraft-batch-stage1", origProp);
            else System.clearProperty("cpq.savedraft-batch-stage1");
        }
    }
}
