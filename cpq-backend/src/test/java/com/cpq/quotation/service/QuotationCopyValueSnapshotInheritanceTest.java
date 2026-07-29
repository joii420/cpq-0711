package com.cpq.quotation.service;

import com.cpq.component.entity.Component;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0729 Task3 — {@link QuotationService#copy(UUID, UUID)} 值快照整份继承 + 结构补建
 * + 换模板复制零回归的后端集成验证。
 *
 * <p>对应 backtask.md §三 T1~T4（2026-07-29 随 Task1 返修 {@code 1dd1a66e} 同步更新）：
 * <ul>
 *   <li>T1 同模板复制继承：逐组件 snapshot_rows/row_data 与源单逐一相等；subtotal/original_amount/
 *       total_amount 相等；deleted_tree_nodes 相等；4 份值快照列相等；R1/R2 返修新增的行级折扣明细
 *       （含 annualVolume 年用量 —— 核价总额/行折扣金额的乘数，是根因 E 的同族形态）+ 单据头
 *       taxRate/taxAmount/remarks 等冻结结果列相等。</li>
 *   <li>T2 同模板复制不重建：quote_card_values 与源单逐字节相等 + quote_values_at 仍是 fixture 里
 *       写入的固定过去时间戳（若 refreshQuoteCardValues 被调用，该时间戳必然被刷成"现在"）。</li>
 *   <li>T3 结构补建：复制后 quotation_view_structure 该单存在（ensureStructure 生效）。R3 返修把
 *       ensureStructure 从 QuotationService.copy() 事务内移到了 QuotationResource#copy 的事务提交
 *       之后（copy() 内部事务读不到自己刚 persist 尚未提交的行）——本用例本应经 RestAssured 真走
 *       REST 层（{@code POST /api/cpq/quotations/{id}/copy}）验证，但该环境下登录会话写 Redis 稳定
 *       复现 CONNECTION_CLOSED（未改动的既有基线测试 PermissionTest 同样复现，判定为测试环境预存在
 *       的 Quarkus Redis 客户端问题，非本任务可修复范围），退化为直接复刻 Resource 层的两步调用顺序
 *       + 事务边界（{@code quotationService.copy(...)} 返回即已提交 → 再独立调用
 *       {@code cardSnapshotService.ensureStructure(...)}，中间无共享事务，完整保留 R3 要修正的
 *       "必须等事务提交后才安全调用"前提），详见该测试方法内注释。</li>
 *   <li>T4 换模板复制零回归：snapshot_rows 为 null、row_data 只保留 INPUT 字段、走重建（不继承
 *       源单的值快照 marker，含 R1/R2 新增的行级折扣明细与单据头 taxRate/remarks 等——这些字段
 *       在换模板 fixture 里同样打了 marker，验证"不继承"不是巧合的默认值重合）。</li>
 * </ul>
 *
 * <p>策略：不依赖共享库里的既存报价单/模板（BL-0078 教训 —— 硬编码 id 随迁库集体失效）。每个测试
 * 自建最小 fixture（模板+组件+报价单+行+组件数据），sameTemplate 路径完全不需要真实 driver/$view
 * （值快照整份继承时 refreshQuoteCardValues/refreshCostingCardValues 被显式跳过 —— 这正是 Task1 的
 * 修复点），换模板路径的组件也故意不挂 data_driver_path（分支覆盖不依赖 expand 基础设施是否可用，
 * 只验证 migrateAndCreateComponentData 的确定性行为）。清理策略与 QuoteBomTreeEndToEndTest 一致：
 * {@code QuarkusTransaction.requiringNew()} 真提交 + {@code @AfterEach} 按依赖倒序真 DELETE。
 */
@QuarkusTest
@DisplayName("QuotationCopyValueSnapshotInheritanceTest — copy() 值快照整份继承 / 不重建 / 结构补建 / 换模板零回归")
class QuotationCopyValueSnapshotInheritanceTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String TAG = "T0729COPY";

    @Inject EntityManager em;
    @Inject QuotationService quotationService;
    @Inject CardSnapshotService cardSnapshotService;

    // T1/T2/T3 共享 fixture ids
    private UUID compAId, compTreeId, templateAId, tcAId, tcTreeId, quotationSourceId, lineItemSourceId;
    // T4 独立 fixture ids（换模板路径，刻意与 T1~T3 隔离，避免核价侧牵连）
    private UUID t4CompAId, t4CompTreeId, t4TemplateAId, t4TcAId, t4TcTreeId, t4TemplateBId, t4TcBId,
            t4QuotationSourceId, t4LineItemSourceId;
    // copy() 产出的新单（每个测试各自记录，AfterEach 统一清理）
    private UUID quotationCopyId;

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            deleteQuotationCascade(quotationCopyId);
            deleteQuotationCascade(quotationSourceId);
            deleteQuotationCascade(t4QuotationSourceId);

            if (tcAId != null) em.createNativeQuery("DELETE FROM template_component WHERE id = :id").setParameter("id", tcAId).executeUpdate();
            if (tcTreeId != null) em.createNativeQuery("DELETE FROM template_component WHERE id = :id").setParameter("id", tcTreeId).executeUpdate();
            if (t4TcAId != null) em.createNativeQuery("DELETE FROM template_component WHERE id = :id").setParameter("id", t4TcAId).executeUpdate();
            if (t4TcTreeId != null) em.createNativeQuery("DELETE FROM template_component WHERE id = :id").setParameter("id", t4TcTreeId).executeUpdate();
            if (t4TcBId != null) em.createNativeQuery("DELETE FROM template_component WHERE id = :id").setParameter("id", t4TcBId).executeUpdate();

            if (templateAId != null) em.createNativeQuery("DELETE FROM template WHERE id = :id").setParameter("id", templateAId).executeUpdate();
            if (t4TemplateAId != null) em.createNativeQuery("DELETE FROM template WHERE id = :id").setParameter("id", t4TemplateAId).executeUpdate();
            if (t4TemplateBId != null) em.createNativeQuery("DELETE FROM template WHERE id = :id").setParameter("id", t4TemplateBId).executeUpdate();

            if (compAId != null) em.createNativeQuery("DELETE FROM component WHERE id = :id").setParameter("id", compAId).executeUpdate();
            if (compTreeId != null) em.createNativeQuery("DELETE FROM component WHERE id = :id").setParameter("id", compTreeId).executeUpdate();
            if (t4CompAId != null) em.createNativeQuery("DELETE FROM component WHERE id = :id").setParameter("id", t4CompAId).executeUpdate();
            if (t4CompTreeId != null) em.createNativeQuery("DELETE FROM component WHERE id = :id").setParameter("id", t4CompTreeId).executeUpdate();

            // 兜底：按 TAG 前缀再扫一遍，防止某个 id 因异常提前退出未记录
            em.createNativeQuery("DELETE FROM component WHERE code LIKE :p").setParameter("p", TAG + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM template WHERE name LIKE :p").setParameter("p", TAG + "%").executeUpdate();
        });
    }

    private void deleteQuotationCascade(UUID qid) {
        if (qid == null) return;
        em.createNativeQuery(
                "DELETE FROM quotation_line_component_data WHERE line_item_id IN " +
                "(SELECT id FROM quotation_line_item WHERE quotation_id = :qid)")
                .setParameter("qid", qid).executeUpdate();
        em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :qid")
                .setParameter("qid", qid).executeUpdate();
        em.createNativeQuery("DELETE FROM quotation_view_structure WHERE quotation_id = :qid")
                .setParameter("qid", qid).executeUpdate();
        em.createNativeQuery("DELETE FROM quotation WHERE id = :qid")
                .setParameter("qid", qid).executeUpdate();
    }

    private static UUID toUUID(Object o) {
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }

    // 固定的可辨识 marker，判断"是否被继承过来"用字符串包含判断即可，不依赖 JSON 结构。
    private static final String QCV_MARKER = "QCV_MARKER_T0729";
    private static final String QEV_MARKER = "QEV_MARKER_T0729";
    private static final String CCV_MARKER = "CCV_MARKER_T0729";
    private static final String CEV_MARKER = "CEV_MARKER_T0729";
    private static final String EVS_MARKER = "EVS_MARKER_T0729";

    /** 建 T1/T2/T3 共享 fixture：模板(含核价同源模板) + 平铺组件 + 树组件 + 报价单 + 行 + 组件数据(全字段打 marker)。 */
    private void buildBaseFixture() {
        QuarkusTransaction.requiringNew().run(() -> {
            Component compA = new Component();
            compA.name = TAG + "-投料";
            compA.code = TAG + "-A-" + UUID.randomUUID().toString().substring(0, 8);
            compA.fields = "[{\"name\":\"数量\",\"field_type\":\"INPUT_NUMBER\"},{\"name\":\"备注\",\"field_type\":\"INPUT_TEXT\"}]";
            compA.formulas = "[]";
            compA.persist();
            compAId = compA.id;

            Component compTree = new Component();
            compTree.name = TAG + "-BOM树";
            compTree.code = TAG + "-TREE-" + UUID.randomUUID().toString().substring(0, 8);
            compTree.fields = "[]";
            compTree.formulas = "[]";
            compTree.tabType = "BOM";
            compTree.bomRecursiveExpand = true;
            compTree.persist();
            compTreeId = compTree.id;

            Template tpl = new Template();
            tpl.templateSeriesId = UUID.randomUUID();
            tpl.name = TAG + "-模板A";
            tpl.templateKind = "QUOTATION";
            tpl.status = "DRAFT";
            tpl.createdAt = OffsetDateTime.now();
            tpl.updatedAt = OffsetDateTime.now();
            tpl.persist();
            templateAId = tpl.id;

            TemplateComponent tcA = new TemplateComponent();
            tcA.templateId = tpl.id;
            tcA.componentId = compA.id;
            tcA.tabName = "投料";
            tcA.createdAt = OffsetDateTime.now();
            tcA.persist();
            tcAId = tcA.id;

            TemplateComponent tcTree = new TemplateComponent();
            tcTree.templateId = tpl.id;
            tcTree.componentId = compTree.id;
            tcTree.tabName = "BOM树";
            tcTree.createdAt = OffsetDateTime.now();
            tcTree.persist();
            tcTreeId = tcTree.id;

            try {
                com.fasterxml.jackson.databind.node.ArrayNode snapshot = M.createArrayNode();
                com.fasterxml.jackson.databind.node.ObjectNode aEntry = snapshot.addObject();
                aEntry.put("id", tcA.id.toString());
                aEntry.put("componentId", compA.id.toString());
                aEntry.put("componentName", compA.name);
                aEntry.put("componentCode", compA.code);
                aEntry.put("componentType", "NORMAL");
                aEntry.put("tabName", "投料");
                aEntry.put("sortOrder", 0);
                aEntry.set("fields", M.readTree(compA.fields));
                aEntry.set("formulas", M.readTree(compA.formulas));

                com.fasterxml.jackson.databind.node.ObjectNode treeEntry = snapshot.addObject();
                treeEntry.put("id", tcTree.id.toString());
                treeEntry.put("componentId", compTree.id.toString());
                treeEntry.put("componentName", compTree.name);
                treeEntry.put("componentCode", compTree.code);
                treeEntry.put("componentType", "NORMAL");
                treeEntry.put("tabName", "BOM树");
                treeEntry.put("sortOrder", 1);
                treeEntry.set("fields", M.readTree(compTree.fields));
                treeEntry.set("formulas", M.readTree(compTree.formulas));
                treeEntry.put("tab_type", "BOM");

                tpl.componentsSnapshot = M.writeValueAsString(snapshot);
                tpl.persist();
            } catch (Exception e) {
                throw new RuntimeException("构造 template.components_snapshot 失败", e);
            }

            @SuppressWarnings("unchecked")
            List<Object> customers = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
            assertFalse(customers.isEmpty(), "DB 无任何 customer,无法建报价单 fixture");
            UUID customerId = toUUID(customers.get(0));
            @SuppressWarnings("unchecked")
            List<Object> users = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
            assertFalse(users.isEmpty(), "DB 无任何 user,无法建报价单 fixture");
            UUID salesRepId = toUUID(users.get(0));

            Quotation q = new Quotation();
            q.quotationNumber = TAG + "-SRC-" + UUID.randomUUID().toString().substring(0, 8);
            q.customerId = customerId;
            q.name = TAG + "-源报价单";
            q.salesRepId = salesRepId;
            q.status = "DRAFT";
            q.customerTemplateId = templateAId;
            q.costingCardTemplateId = templateAId; // 同一份模板同时充当核价模板，供 T3 验证 4 份结构齐全
            q.originalAmount = new BigDecimal("1000.5000");
            q.totalAmount = new BigDecimal("950.2500");
            // repair-0729 R2 返修新增的单据头冻结结果列（marker 值，验证 T1 逐一继承）
            q.taxRate = new BigDecimal("13.00");
            q.taxAmount = new BigDecimal("123.4500");
            q.isManuallyAdjusted = true;
            q.discountAdjustmentReason = "header-adj-reason-T0729";
            q.remarks = "remarks-marker-T0729";
            q.persist();
            quotationSourceId = q.id;

            OffsetDateTime cardSnapshotAt = OffsetDateTime.now().minusDays(5).truncatedTo(ChronoUnit.MILLIS);
            OffsetDateTime quoteValuesAt = OffsetDateTime.now().minusDays(3).truncatedTo(ChronoUnit.MILLIS);

            QuotationLineItem li = new QuotationLineItem();
            li.quotationId = q.id;
            li.templateId = templateAId;
            li.productPartNoSnapshot = TAG + "-P1";
            li.sortOrder = 0;
            li.subtotal = new BigDecimal("333.4400");
            li.quoteCardValues = "{\"marker\":\"" + QCV_MARKER + "\"}";
            li.quoteExcelValues = "{\"marker\":\"" + QEV_MARKER + "\"}";
            li.costingCardValues = "{\"marker\":\"" + CCV_MARKER + "\"}";
            li.costingExcelValues = "{\"marker\":\"" + CEV_MARKER + "\"}";
            li.excelViewSnapshot = "{\"marker\":\"" + EVS_MARKER + "\"}";
            li.deletedTreeNodes = "[\"ROOT/PRUNED_NODE\"]";
            li.cardSnapshotAt = cardSnapshotAt;
            li.quoteValuesAt = quoteValuesAt;
            // repair-0729 R1/R2 返修新增的行级冻结结果列（marker 值，验证 T1 逐一继承）。
            // annualVolume 最关键：CostingSubtotalUtil.lineCostingAmount 与 LineDiscountService 的乘数，
            // 漏继承会让核价总额/行折扣金额静默算成 0（根因 E 同族形态）。
            li.annualVolume = 5000;
            li.discountSource = "MANUAL_T0729";
            li.discountBaseAmount = new BigDecimal("800.1000");
            li.discountRateApplied = new BigDecimal("92.50");
            li.lineDiscountAmount = new BigDecimal("60.0700");
            li.lineUnitPrice = new BigDecimal("10.5000");
            li.lineFinalPrice = new BigDecimal("9.7200");
            li.lineTotalAmount = new BigDecimal("340.1200");
            li.discountRuleCode = "RULE_T0729";
            li.isManuallyAdjusted = true;
            li.discountAdjustmentReason = "line-adj-reason-T0729";
            li.persist();
            lineItemSourceId = li.id;

            QuotationLineComponentData cdA = new QuotationLineComponentData();
            cdA.lineItemId = li.id;
            cdA.componentId = compA.id;
            cdA.tabName = "投料";
            cdA.rowData = "[{\"row_index\":0,\"数量\":5,\"备注\":\"marker-remark\",\"__nodeId\":\"flatNode1\"}]";
            cdA.snapshotRows = "[{\"数量\":5,\"__marker\":\"flatSnap1\"}]";
            cdA.subtotal = new BigDecimal("111.2200");
            cdA.deletedRowKeys = "[\"dr-key-1\"]";
            cdA.sortOrder = 0;
            cdA.persist();

            QuotationLineComponentData cdTree = new QuotationLineComponentData();
            cdTree.lineItemId = li.id;
            cdTree.componentId = compTree.id;
            cdTree.tabName = "BOM树";
            cdTree.rowData = "[{\"marker\":\"tree-rowdata-marker\"}]";
            cdTree.snapshotRows = "[{\"__nodeId\":\"ROOT\",\"__hfPartNo\":\"TREEP1\",\"__lvl\":0},"
                    + "{\"__nodeId\":\"ROOT/CHILD\",\"__hfPartNo\":\"TREEP2\",\"__lvl\":1,\"__parentId\":\"ROOT\"}]";
            cdTree.subtotal = new BigDecimal("222.3300");
            cdTree.sortOrder = 1;
            cdTree.persist();
        });
    }

    // -----------------------------------------------------------------------
    // 读取辅助（新事务读，绕过一级缓存，确保读到的是真实落库结果）
    // -----------------------------------------------------------------------

    private record LineItemSnapshot(
            BigDecimal subtotal, String quoteCardValues, String quoteExcelValues,
            String costingCardValues, String costingExcelValues, String cardSnapshotAt,
            String quoteValuesAt, String excelViewSnapshot, String deletedTreeNodes,
            // repair-0729 R1/R2 返修新增的行级冻结结果列
            Integer annualVolume, String discountSource, BigDecimal discountBaseAmount,
            BigDecimal discountRateApplied, BigDecimal lineDiscountAmount, BigDecimal lineUnitPrice,
            BigDecimal lineFinalPrice, BigDecimal lineTotalAmount, String discountRuleCode,
            Boolean isManuallyAdjusted, String discountAdjustmentReason) {}

    private LineItemSnapshot readLineItemSnapshot(UUID lineItemId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT subtotal, quote_card_values::text, quote_excel_values::text, " +
                    "       costing_card_values::text, costing_excel_values::text, card_snapshot_at::text, " +
                    "       quote_values_at::text, excel_view_snapshot::text, deleted_tree_nodes::text, " +
                    "       annual_volume, discount_source, discount_base_amount, discount_rate_applied, " +
                    "       line_discount_amount, line_unit_price, line_final_price, line_total_amount, " +
                    "       discount_rule_code, is_manually_adjusted, discount_adjustment_reason " +
                    "FROM quotation_line_item WHERE id = :id")
                    .setParameter("id", lineItemId).getResultList();
            assertEquals(1, rows.size(), "line item 应存在: " + lineItemId);
            Object[] r = rows.get(0);
            return new LineItemSnapshot(
                    (BigDecimal) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                    (String) r[5], (String) r[6], (String) r[7], (String) r[8],
                    (Integer) r[9], (String) r[10], (BigDecimal) r[11], (BigDecimal) r[12],
                    (BigDecimal) r[13], (BigDecimal) r[14], (BigDecimal) r[15], (BigDecimal) r[16],
                    (String) r[17], (Boolean) r[18], (String) r[19]);
        });
    }

    private record ComponentDataSnapshot(String rowData, String snapshotRows, BigDecimal subtotal, String deletedRowKeys) {}

    private ComponentDataSnapshot readComponentData(UUID lineItemId, UUID componentId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT row_data::text, snapshot_rows::text, subtotal, deleted_row_keys::text " +
                    "FROM quotation_line_component_data WHERE line_item_id = :lid AND component_id = :cid")
                    .setParameter("lid", lineItemId).setParameter("cid", componentId).getResultList();
            assertEquals(1, rows.size(), "组件数据行应恰好 1 条: line=" + lineItemId + " comp=" + componentId);
            Object[] r = rows.get(0);
            return new ComponentDataSnapshot((String) r[0], (String) r[1], (BigDecimal) r[2], (String) r[3]);
        });
    }

    private record QuotationHeaderSnapshot(
            BigDecimal originalAmount, BigDecimal totalAmount, BigDecimal taxRate, BigDecimal taxAmount,
            Boolean isManuallyAdjusted, String discountAdjustmentReason, String remarks) {}

    private QuotationHeaderSnapshot readQuotationHeader(UUID quotationId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT original_amount, total_amount, tax_rate, tax_amount, " +
                    "       is_manually_adjusted, discount_adjustment_reason, remarks " +
                    "FROM quotation WHERE id = :id")
                    .setParameter("id", quotationId).getResultList();
            assertEquals(1, rows.size());
            Object[] r = rows.get(0);
            return new QuotationHeaderSnapshot(
                    (BigDecimal) r[0], (BigDecimal) r[1], (BigDecimal) r[2], (BigDecimal) r[3],
                    (Boolean) r[4], (String) r[5], (String) r[6]);
        });
    }

    private UUID resolveCopyLineItemId(UUID copyQuotationId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Object> rows = em.createNativeQuery(
                    "SELECT id FROM quotation_line_item WHERE quotation_id = :qid ORDER BY sort_order")
                    .setParameter("qid", copyQuotationId).getResultList();
            assertEquals(1, rows.size(), "新单应恰好 1 条行项目");
            return toUUID(rows.get(0));
        });
    }

    // -----------------------------------------------------------------------
    // T1: 同模板复制继承
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("T1: 同模板复制 → 逐组件 snapshot_rows/row_data 与源单逐一相等；subtotal/金额/deleted_tree_nodes/4 份值快照相等")
    void t1_sameTemplateCopy_inheritsValueSnapshots() {
        buildBaseFixture();

        QuotationDTO dto = quotationService.copy(quotationSourceId, null);
        quotationCopyId = dto.id;
        assertNotNull(quotationCopyId);
        assertNotEquals(quotationSourceId, quotationCopyId);

        // 单据头金额继承
        QuotationHeaderSnapshot srcHeader = readQuotationHeader(quotationSourceId);
        QuotationHeaderSnapshot cpyHeader = readQuotationHeader(quotationCopyId);
        assertEquals(0, srcHeader.originalAmount().compareTo(cpyHeader.originalAmount()), "originalAmount 应继承源单");
        assertEquals(0, srcHeader.totalAmount().compareTo(cpyHeader.totalAmount()), "totalAmount 应继承源单");
        assertEquals(0, new BigDecimal("1000.5000").compareTo(cpyHeader.originalAmount()));
        assertEquals(0, new BigDecimal("950.2500").compareTo(cpyHeader.totalAmount()));
        // repair-0729 R2 返修新增：单据头冻结结果列继承
        assertEquals(0, new BigDecimal("13.00").compareTo(cpyHeader.taxRate()), "taxRate(单据头) 应继承源单");
        assertEquals(0, new BigDecimal("123.4500").compareTo(cpyHeader.taxAmount()), "taxAmount(单据头) 应继承源单");
        assertEquals(Boolean.TRUE, cpyHeader.isManuallyAdjusted(), "isManuallyAdjusted(单据头) 应继承源单");
        assertEquals("header-adj-reason-T0729", cpyHeader.discountAdjustmentReason(), "discountAdjustmentReason(单据头) 应继承源单");
        assertEquals("remarks-marker-T0729", cpyHeader.remarks(), "remarks 应继承源单");

        UUID copyLineItemId = resolveCopyLineItemId(quotationCopyId);
        LineItemSnapshot src = readLineItemSnapshot(lineItemSourceId);
        LineItemSnapshot cpy = readLineItemSnapshot(copyLineItemId);

        assertEquals(0, src.subtotal.compareTo(cpy.subtotal), "行 subtotal 应继承");
        assertEquals(0, new BigDecimal("333.4400").compareTo(cpy.subtotal));
        assertEquals(src.quoteCardValues, cpy.quoteCardValues, "quoteCardValues 应逐字节继承");
        assertEquals(src.quoteExcelValues, cpy.quoteExcelValues, "quoteExcelValues 应逐字节继承");
        assertEquals(src.costingCardValues, cpy.costingCardValues, "costingCardValues 应逐字节继承");
        assertEquals(src.costingExcelValues, cpy.costingExcelValues, "costingExcelValues 应逐字节继承");
        assertEquals(src.excelViewSnapshot, cpy.excelViewSnapshot, "excelViewSnapshot 应逐字节继承");
        assertEquals(src.deletedTreeNodes, cpy.deletedTreeNodes, "deletedTreeNodes(BOM 树剪枝墓碑) 应逐字节继承");
        assertTrue(cpy.deletedTreeNodes.contains("ROOT/PRUNED_NODE"));
        assertEquals(src.cardSnapshotAt, cpy.cardSnapshotAt, "cardSnapshotAt 应继承源单冻结时间戳");

        // repair-0729 R1/R2 返修新增：行级冻结结果列继承（annualVolume 最关键，见类注释）
        assertEquals(Integer.valueOf(5000), cpy.annualVolume(),
                "annualVolume(年用量) 必须继承 —— 漏继承会让核价总额/行折扣金额静默算成 0(根因 E 同族)");
        assertEquals("MANUAL_T0729", cpy.discountSource(), "discountSource 应继承");
        assertEquals(0, new BigDecimal("800.1000").compareTo(cpy.discountBaseAmount()), "discountBaseAmount 应继承");
        assertEquals(0, new BigDecimal("92.50").compareTo(cpy.discountRateApplied()), "discountRateApplied 应继承");
        assertEquals(0, new BigDecimal("60.0700").compareTo(cpy.lineDiscountAmount()), "lineDiscountAmount 应继承");
        assertEquals(0, new BigDecimal("10.5000").compareTo(cpy.lineUnitPrice()), "lineUnitPrice 应继承");
        assertEquals(0, new BigDecimal("9.7200").compareTo(cpy.lineFinalPrice()), "lineFinalPrice 应继承");
        assertEquals(0, new BigDecimal("340.1200").compareTo(cpy.lineTotalAmount()), "lineTotalAmount 应继承");
        assertEquals("RULE_T0729", cpy.discountRuleCode(), "discountRuleCode 应继承");
        assertEquals(Boolean.TRUE, cpy.isManuallyAdjusted(), "isManuallyAdjusted(行级) 应继承");
        assertEquals("line-adj-reason-T0729", cpy.discountAdjustmentReason(), "discountAdjustmentReason(行级) 应继承");

        // 逐组件 row_data / snapshot_rows 相等（平铺组件 + 树组件两种 tab_type 都覆盖）
        ComponentDataSnapshot srcA = readComponentData(lineItemSourceId, compAId);
        ComponentDataSnapshot cpyA = readComponentData(copyLineItemId, compAId);
        assertEquals(srcA.rowData, cpyA.rowData, "平铺组件 row_data 应逐字节继承(含 __nodeId 等系统列)");
        assertEquals(srcA.snapshotRows, cpyA.snapshotRows, "平铺组件 snapshot_rows 应逐字节继承");
        assertEquals(0, srcA.subtotal.compareTo(cpyA.subtotal), "平铺组件 subtotal 应继承");
        assertEquals(srcA.deletedRowKeys, cpyA.deletedRowKeys, "driver 默认行墓碑应按 componentId 原样拷贝");

        ComponentDataSnapshot srcTree = readComponentData(lineItemSourceId, compTreeId);
        ComponentDataSnapshot cpyTree = readComponentData(copyLineItemId, compTreeId);
        assertEquals(srcTree.rowData, cpyTree.rowData, "树组件 row_data 应逐字节继承");
        assertEquals(srcTree.snapshotRows, cpyTree.snapshotRows, "树组件 snapshot_rows(BOM spine) 应逐字节继承 —— 根因 B 的验证点");
        assertTrue(cpyTree.snapshotRows.contains("ROOT/CHILD"), "BOM 树 spine 内容应完整（不是空数组）");
        assertEquals(0, srcTree.subtotal.compareTo(cpyTree.subtotal), "树组件 subtotal 应继承");
    }

    // -----------------------------------------------------------------------
    // T2: 同模板复制不重建
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("T2: 同模板复制 → quote_card_values 与源单逐字节相等 + quote_values_at 仍是 fixture 固定时间戳(未被 refresh 刷成\"现在\")")
    void t2_sameTemplateCopy_doesNotTriggerRebuild() {
        buildBaseFixture();

        long blockedBefore = CardSnapshotService.EMPTY_OVERWRITE_BLOCKED_COUNT.get();

        QuotationDTO dto = quotationService.copy(quotationSourceId, null);
        quotationCopyId = dto.id;

        long blockedAfter = CardSnapshotService.EMPTY_OVERWRITE_BLOCKED_COUNT.get();
        assertEquals(blockedBefore, blockedAfter,
                "sameTemplate 路径显式跳过 refreshQuoteCardValues，Task2 的空覆盖护栏代码根本不会执行，计数器不应变化");

        UUID copyLineItemId = resolveCopyLineItemId(quotationCopyId);
        LineItemSnapshot src = readLineItemSnapshot(lineItemSourceId);
        LineItemSnapshot cpy = readLineItemSnapshot(copyLineItemId);

        assertEquals(src.quoteCardValues, cpy.quoteCardValues,
                "quote_card_values 必须与源单逐字节相等 —— 若 refreshQuoteCardValues 被调用，"
                        + "在本 fixture(driver 为空)下会被重新组装成不含 marker 的新内容");
        assertTrue(cpy.quoteCardValues.contains(QCV_MARKER), "继承值必须原样保留 marker，证明不是重算产物");

        // quoteValuesAt 是最直接的"是否被 refresh 碰过"信号：refreshQuoteCardValues 只要真正跑完
        // 就一定会把它刷成调用时刻的 now()，不可能仍等于 fixture 里写死的过去时间戳。
        assertEquals(src.quoteValuesAt, cpy.quoteValuesAt,
                "quote_values_at 必须与源单一致(fixture 固定过去时间戳)，证明 refreshQuoteCardValues 未被调用");
    }

    // -----------------------------------------------------------------------
    // T3: 结构补建
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("T3: 复制后 quotation_view_structure 该单存在（ensureStructure 生效，4 份齐全）")
    void t3_copyEnsuresViewStructure() {
        buildBaseFixture();

        // repair-0729 R3 返修：ensureStructure 已从 QuotationService.copy() 的事务内移到了
        // QuotationResource#copy 在 copy() 事务提交之后独立调用（否则读不到本事务内刚 persist
        // 尚未提交的 Quotation/QuotationLineItem 行）。直接调 quotationService.copy(...) 会绕过
        // Resource 层，结构快照永远不会被补建——本该经 REST 层验证才最贴近生产调用路径。
        //
        // 实际尝试：本用例最初写成 RestAssured 真打 POST /api/cpq/quotations/{id}/copy（先
        // POST /api/cpq/auth/login 拿 CPQ_SESSION，因 QuotationResource 类级 @RoleAllowed 需要
        // 登录态），但该环境下登录会话写入 Redis 时稳定复现 CONNECTION_CLOSED（Vert.x redis 客户端
        // 层，SessionHelper.createSession 内部 hset 阻塞等待超时/连接被关闭）；用裸 socket 直连同一
        // Redis 实例 AUTH+PING 验证网络可达、认证正常，排除防火墙/密码问题。用同一环境跑未改动的既有
        // 基线测试 PermissionTest（login/me 两个用例）复现完全相同的 CONNECTION_CLOSED —— 证明这是
        // 测试环境 Quarkus Redis 客户端连接层面的预存在问题，与本次改动无关，不在本任务"不改实现代码"
        // 的授权范围内定位/修复。
        //
        // 退化方案：直接复刻 QuotationResource#copy 的两步调用顺序与事务边界——先调
        // quotationService.copy(...)（其 @Transactional 在方法返回时已提交），再在一个新事务里
        // （ensureStructure 自身 @Transactional(REQUIRED) 会开自己的事务）调用
        // cardSnapshotService.ensureStructure(...)。这与 Resource 层的代码顺序逐行一致，只是不经过
        // HTTP/认证栈；R3 要修正的正是"事务提交后才能安全调用 ensureStructure"这一点，本写法完整保留
        // 了这个先决条件（两次独立的 @Transactional 方法调用，中间无共享事务），故仍能有效验证 R3。
        QuotationDTO dto = quotationService.copy(quotationSourceId, null);
        quotationCopyId = dto.id;
        cardSnapshotService.ensureStructure(quotationCopyId);

        @SuppressWarnings("unchecked")
        List<Object> kinds = em.createNativeQuery(
                "SELECT view_kind FROM quotation_view_structure WHERE quotation_id = :qid ORDER BY view_kind")
                .setParameter("qid", quotationCopyId).getResultList();
        List<String> kindStrings = kinds.stream().map(Object::toString).sorted().toList();

        assertTrue(kindStrings.contains("QUOTE_CARD"), "复制后应有 QUOTE_CARD 结构快照，实际=" + kindStrings);
        assertTrue(kindStrings.contains("QUOTE_EXCEL"), "复制后应有 QUOTE_EXCEL 结构快照，实际=" + kindStrings);
        // 本 fixture costingCardTemplateId 复用同一份模板 → 应同时补出核价侧 2 份结构快照
        assertTrue(kindStrings.contains("COSTING_CARD"), "复制后应有 COSTING_CARD 结构快照，实际=" + kindStrings);
        assertTrue(kindStrings.contains("COSTING_EXCEL"), "复制后应有 COSTING_EXCEL 结构快照，实际=" + kindStrings);
        assertEquals(4, kindStrings.size(), "4 份结构快照应恰好齐全，实际=" + kindStrings);
    }

    // -----------------------------------------------------------------------
    // T4: 换模板复制零回归
    // -----------------------------------------------------------------------

    /** 建 T4 独立 fixture：源模板 A'(投料+BOM树) + 目标模板 B(只含"投料"，且只声明"数量"为 INPUT 字段)。 */
    private void buildT4Fixture() {
        QuarkusTransaction.requiringNew().run(() -> {
            Component compA = new Component();
            compA.name = TAG + "-T4投料";
            compA.code = TAG + "-T4A-" + UUID.randomUUID().toString().substring(0, 8);
            compA.fields = "[{\"name\":\"数量\",\"field_type\":\"INPUT_NUMBER\"},{\"name\":\"备注\",\"field_type\":\"INPUT_TEXT\"}]";
            compA.formulas = "[]";
            compA.persist();
            t4CompAId = compA.id;

            Component compTree = new Component();
            compTree.name = TAG + "-T4BOM树";
            compTree.code = TAG + "-T4TREE-" + UUID.randomUUID().toString().substring(0, 8);
            compTree.fields = "[]";
            compTree.formulas = "[]";
            compTree.tabType = "BOM";
            compTree.bomRecursiveExpand = true;
            compTree.persist();
            t4CompTreeId = compTree.id;

            Template tplA = new Template();
            tplA.templateSeriesId = UUID.randomUUID();
            tplA.name = TAG + "-T4模板A";
            tplA.templateKind = "QUOTATION";
            tplA.status = "DRAFT";
            tplA.createdAt = OffsetDateTime.now();
            tplA.updatedAt = OffsetDateTime.now();
            tplA.persist();
            t4TemplateAId = tplA.id;

            TemplateComponent tcA = new TemplateComponent();
            tcA.templateId = tplA.id;
            tcA.componentId = compA.id;
            tcA.tabName = "投料";
            tcA.createdAt = OffsetDateTime.now();
            tcA.persist();
            t4TcAId = tcA.id;

            TemplateComponent tcTree = new TemplateComponent();
            tcTree.templateId = tplA.id;
            tcTree.componentId = compTree.id;
            tcTree.tabName = "BOM树";
            tcTree.createdAt = OffsetDateTime.now();
            tcTree.persist();
            t4TcTreeId = tcTree.id;

            // 目标模板 B：只挂"投料"一个页签，且只声明"数量"为 INPUT 字段("备注"不声明 → 换模板迁移应被过滤掉)
            Template tplB = new Template();
            tplB.templateSeriesId = UUID.randomUUID();
            tplB.name = TAG + "-T4模板B";
            tplB.templateKind = "QUOTATION";
            tplB.status = "DRAFT";
            tplB.createdAt = OffsetDateTime.now();
            tplB.updatedAt = OffsetDateTime.now();
            tplB.persist();
            t4TemplateBId = tplB.id;

            TemplateComponent tcB = new TemplateComponent();
            tcB.templateId = tplB.id;
            tcB.componentId = compA.id;
            tcB.tabName = "投料";
            tcB.createdAt = OffsetDateTime.now();
            tcB.persist();
            t4TcBId = tcB.id;

            try {
                com.fasterxml.jackson.databind.node.ArrayNode snapshotA = M.createArrayNode();
                com.fasterxml.jackson.databind.node.ObjectNode aEntry = snapshotA.addObject();
                aEntry.put("id", tcA.id.toString());
                aEntry.put("componentId", compA.id.toString());
                aEntry.put("componentName", compA.name);
                aEntry.put("componentCode", compA.code);
                aEntry.put("componentType", "NORMAL");
                aEntry.put("tabName", "投料");
                aEntry.put("sortOrder", 0);
                aEntry.set("fields", M.readTree(compA.fields));
                aEntry.set("formulas", M.readTree(compA.formulas));

                com.fasterxml.jackson.databind.node.ObjectNode treeEntry = snapshotA.addObject();
                treeEntry.put("id", tcTree.id.toString());
                treeEntry.put("componentId", compTree.id.toString());
                treeEntry.put("componentName", compTree.name);
                treeEntry.put("componentCode", compTree.code);
                treeEntry.put("componentType", "NORMAL");
                treeEntry.put("tabName", "BOM树");
                treeEntry.put("sortOrder", 1);
                treeEntry.set("fields", M.readTree(compTree.fields));
                treeEntry.set("formulas", M.readTree(compTree.formulas));
                treeEntry.put("tab_type", "BOM");

                tplA.componentsSnapshot = M.writeValueAsString(snapshotA);
                tplA.persist();

                com.fasterxml.jackson.databind.node.ArrayNode snapshotB = M.createArrayNode();
                com.fasterxml.jackson.databind.node.ObjectNode bEntry = snapshotB.addObject();
                bEntry.put("id", tcB.id.toString());
                bEntry.put("componentId", compA.id.toString());
                bEntry.put("componentName", compA.name);
                bEntry.put("componentCode", compA.code);
                bEntry.put("componentType", "NORMAL");
                bEntry.put("tabName", "投料");
                bEntry.put("sortOrder", 0);
                // 目标模板只声明"数量"为 INPUT 字段（不含"备注"）—— 换模板迁移必须按此过滤
                bEntry.set("fields", M.readTree("[{\"name\":\"数量\",\"field_type\":\"INPUT_NUMBER\"}]"));
                bEntry.set("formulas", M.readTree("[]"));

                tplB.componentsSnapshot = M.writeValueAsString(snapshotB);
                tplB.persist();
            } catch (Exception e) {
                throw new RuntimeException("构造 template.components_snapshot 失败", e);
            }

            @SuppressWarnings("unchecked")
            List<Object> customers = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
            assertFalse(customers.isEmpty(), "DB 无任何 customer,无法建报价单 fixture");
            UUID customerId = toUUID(customers.get(0));
            @SuppressWarnings("unchecked")
            List<Object> users = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
            assertFalse(users.isEmpty(), "DB 无任何 user,无法建报价单 fixture");
            UUID salesRepId = toUUID(users.get(0));

            Quotation q = new Quotation();
            q.quotationNumber = TAG + "-T4SRC-" + UUID.randomUUID().toString().substring(0, 8);
            q.customerId = customerId;
            q.name = TAG + "-T4源报价单";
            q.salesRepId = salesRepId;
            q.status = "DRAFT";
            q.customerTemplateId = t4TemplateAId;
            // 刻意不设 costingCardTemplateId：换模板路径只关心报价侧，不牵连核价侧（AC-17 白名单纪律）
            q.originalAmount = new BigDecimal("1000.5000");
            q.totalAmount = new BigDecimal("950.2500");
            // repair-0729 R2 返修新增列也打上（T4 专属，前缀区分于 T1 的）marker，验证换模板复制
            // 不继承——若只留默认值/null，"不继承"断言会跟"巧合的默认值重合"混淆，起不到守护作用。
            q.taxRate = new BigDecimal("17.00");
            q.taxAmount = new BigDecimal("555.5500");
            q.isManuallyAdjusted = true;
            q.discountAdjustmentReason = "T4_HEADER_REASON_SHOULD_NOT_COPY";
            q.remarks = "T4_REMARKS_SHOULD_NOT_COPY";
            q.persist();
            t4QuotationSourceId = q.id;

            QuotationLineItem li = new QuotationLineItem();
            li.quotationId = q.id;
            li.templateId = t4TemplateAId;
            li.productPartNoSnapshot = TAG + "-T4P1";
            li.sortOrder = 0;
            li.subtotal = new BigDecimal("333.4400");
            li.quoteCardValues = "{\"marker\":\"" + QCV_MARKER + "\"}";
            li.quoteExcelValues = "{\"marker\":\"" + QEV_MARKER + "\"}";
            // repair-0729 R1/R2 返修新增列也打上 T4 专属 marker，验证换模板复制不继承（见上方注释）
            li.annualVolume = 7777;
            li.discountSource = "T4_SHOULD_NOT_COPY";
            li.discountBaseAmount = new BigDecimal("999.9900");
            li.discountRateApplied = new BigDecimal("88.88");
            li.lineDiscountAmount = new BigDecimal("11.1100");
            li.lineUnitPrice = new BigDecimal("22.2200");
            li.lineFinalPrice = new BigDecimal("33.3300");
            li.lineTotalAmount = new BigDecimal("44.4400");
            li.discountRuleCode = "T4_RULE_SHOULD_NOT_COPY";
            li.isManuallyAdjusted = true;
            li.discountAdjustmentReason = "T4_REASON_SHOULD_NOT_COPY";
            li.persist();
            t4LineItemSourceId = li.id;

            QuotationLineComponentData cdA = new QuotationLineComponentData();
            cdA.lineItemId = li.id;
            cdA.componentId = compA.id;
            cdA.tabName = "投料";
            cdA.rowData = "[{\"row_index\":0,\"数量\":5,\"备注\":\"marker-remark\",\"__nodeId\":\"flatNode1\"}]";
            cdA.snapshotRows = "[{\"数量\":5,\"__marker\":\"flatSnap1\"}]";
            cdA.subtotal = new BigDecimal("111.2200");
            cdA.sortOrder = 0;
            cdA.persist();

            QuotationLineComponentData cdTree = new QuotationLineComponentData();
            cdTree.lineItemId = li.id;
            cdTree.componentId = compTree.id;
            cdTree.tabName = "BOM树";
            cdTree.rowData = "[]";
            cdTree.snapshotRows = "[{\"__nodeId\":\"ROOT\",\"__hfPartNo\":\"TREEP1\",\"__lvl\":0}]";
            cdTree.subtotal = new BigDecimal("222.3300");
            cdTree.sortOrder = 1;
            cdTree.persist();
        });
    }

    @Test
    @DisplayName("T4: 换模板复制 → snapshot_rows 为 null / row_data 只保留 INPUT 字段 / 不继承源单值快照(走重建)")
    void t4_crossTemplateCopy_zeroRegression() {
        buildT4Fixture();

        QuotationDTO dto = quotationService.copy(t4QuotationSourceId, t4TemplateBId);
        quotationCopyId = dto.id;
        assertNotNull(quotationCopyId);

        QuotationHeaderSnapshot cpyHeader = readQuotationHeader(quotationCopyId);
        assertEquals(0, BigDecimal.ZERO.compareTo(cpyHeader.originalAmount()), "换模板复制：originalAmount 应保持占位 ZERO(不继承源单)");
        assertEquals(0, BigDecimal.ZERO.compareTo(cpyHeader.totalAmount()), "换模板复制：totalAmount 应保持占位 ZERO(不继承源单)");
        // repair-0729 R2 返修新增列：换模板复制不得继承（fixture 打了 T4 专属 marker，非默认值巧合）
        assertEquals(0, BigDecimal.ZERO.compareTo(cpyHeader.taxRate()), "换模板复制：taxRate(单据头) 应保持占位 ZERO(不继承源单)");
        assertEquals(0, BigDecimal.ZERO.compareTo(cpyHeader.taxAmount()), "换模板复制：taxAmount(单据头) 应保持占位 ZERO(不继承源单)");
        assertEquals(Boolean.FALSE, cpyHeader.isManuallyAdjusted(), "换模板复制：isManuallyAdjusted(单据头) 应保持默认 false(不继承源单)");
        assertNull(cpyHeader.discountAdjustmentReason(), "换模板复制：discountAdjustmentReason(单据头) 不应继承源单 marker");
        assertNull(cpyHeader.remarks(), "换模板复制：remarks 不应继承源单 marker");

        UUID copyLineItemId = resolveCopyLineItemId(quotationCopyId);
        LineItemSnapshot cpy = readLineItemSnapshot(copyLineItemId);
        assertEquals(0, BigDecimal.ZERO.compareTo(cpy.subtotal), "换模板复制：行 subtotal 应保持占位 ZERO");
        assertFalse(cpy.quoteCardValues != null && cpy.quoteCardValues.contains(QCV_MARKER),
                "换模板复制：quote_card_values 不得原样继承源单 marker(必须走重建，不是值快照继承)");
        assertFalse(cpy.quoteExcelValues != null && cpy.quoteExcelValues.contains(QEV_MARKER),
                "换模板复制：quote_excel_values 不得原样继承源单 marker");

        // repair-0729 R1/R2 返修新增列：换模板复制不得继承（fixture 打了 T4 专属 marker，非默认值巧合）
        assertNull(cpy.annualVolume(), "换模板复制：annualVolume 不应继承(否则核价总额/行折扣金额会用错误乘数)");
        assertNull(cpy.discountSource(), "换模板复制：discountSource 不应继承");
        assertNull(cpy.discountBaseAmount(), "换模板复制：discountBaseAmount 不应继承");
        assertNull(cpy.discountRateApplied(), "换模板复制：discountRateApplied 不应继承");
        assertNull(cpy.lineDiscountAmount(), "换模板复制：lineDiscountAmount 不应继承");
        assertNull(cpy.lineUnitPrice(), "换模板复制：lineUnitPrice 不应继承");
        assertNull(cpy.lineFinalPrice(), "换模板复制：lineFinalPrice 不应继承");
        assertNull(cpy.lineTotalAmount(), "换模板复制：lineTotalAmount 不应继承");
        assertNull(cpy.discountRuleCode(), "换模板复制：discountRuleCode 不应继承");
        assertEquals(Boolean.FALSE, cpy.isManuallyAdjusted(), "换模板复制：isManuallyAdjusted(行级) 应保持默认 false(不继承源单)");
        assertNull(cpy.discountAdjustmentReason(), "换模板复制：discountAdjustmentReason(行级) 不应继承源单 marker");

        // 目标模板只挂了"投料"一个页签 → 新单组件数据应恰好 1 条(BOM树 tab 因目标模板未声明而被丢弃，不是僵尸继承)
        Long compDataCount = QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Object> rows = em.createNativeQuery(
                    "SELECT count(*) FROM quotation_line_component_data WHERE line_item_id = :lid")
                    .setParameter("lid", copyLineItemId).getResultList();
            return ((Number) rows.get(0)).longValue();
        });
        assertEquals(1, compDataCount, "换模板后组件数据应只剩目标模板声明的 1 个页签(投料)，BOM树 tab 不应被继承");

        ComponentDataSnapshot cpyA = readComponentData(copyLineItemId, t4CompAId);
        assertNull(cpyA.snapshotRows, "换模板复制：snapshot_rows 必须为 null(走重建，不继承源单冻结树)");

        com.fasterxml.jackson.databind.JsonNode rowDataArr;
        try {
            rowDataArr = M.readTree(cpyA.rowData);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertTrue(rowDataArr.isArray() && rowDataArr.size() == 1, "row_data 应有 1 行: " + cpyA.rowData);
        com.fasterxml.jackson.databind.JsonNode row0 = rowDataArr.get(0);
        assertTrue(row0.has("数量"), "row_data 应保留目标模板声明的 INPUT 字段「数量」: " + cpyA.rowData);
        assertEquals(5, row0.path("数量").asInt(-1));
        assertFalse(row0.has("备注"), "row_data 不应保留目标模板未声明为 INPUT 的「备注」: " + cpyA.rowData);
        assertFalse(row0.has("__nodeId"), "row_data 不应保留系统列 __nodeId(非 INPUT 字段): " + cpyA.rowData);
    }
}
