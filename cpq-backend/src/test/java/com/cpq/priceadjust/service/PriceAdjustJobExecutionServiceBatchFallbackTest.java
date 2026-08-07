package com.cpq.priceadjust.service;

import com.cpq.priceadjust.entity.MaterialPriceUpdateJobItem;
import com.cpq.quotation.service.CardSnapshotService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-0806 · FR-5（守卫 2）/ AC-4 单测 + {@code templateHasTreeTab} 门槛回归。
 *
 * <p>构造三个 line item，分属三个不同的核价模板（天然落入不同分组，不依赖日期差异）：
 * <ul>
 *   <li>模板 A（含树页签）：挂一个 {@code bom_recursive_expand=true} 的 driver 组件，其
 *       {@code $view} sql_template 语法错误（查不存在的表），{@code render()} 对该分组必然抛异常；</li>
 *   <li>模板 B（含树页签）：挂一个 {@code bom_recursive_expand=true} 的 driver 组件，其
 *       {@code $view} sql_template 语法合法但恒返回 0 行（{@code WHERE false}），
 *       {@code render()} 对该分组必然成功；</li>
 *   <li>模板 C（<b>不含树页签</b>）：无任何 driver 组件——{@code templateHasTreeTab(C)==false}。</li>
 * </ul>
 *
 * <p>预期：
 * <ol>
 *   <li>模板 A 那个 line item 结果 map 里<b>没有</b>条目（FR-5 回退逐项，交给
 *       {@code upgrade()} 默认路径自己再 render 一次并按现有逻辑精确 FAILED）；</li>
 *   <li>模板 B 那个 line item <b>有</b>条目（该分组批量成功，未被模板 A 的失败拖累）；</li>
 *   <li>模板 C 那个 line item <b>没有</b>条目（2026-08-07 亲验补丁：{@code templateHasTreeTab}
 *       门槛——不含树页签的模板老路径本就不调用 {@code render()}，批量路径必须对齐，否则
 *       {@code precomputedBaseRows} 从"恒 null"变成"非 null 的空结果"，与老路径分叉进
 *       {@code buildCostingCardValues} 不同代码分支）。</li>
 * </ol>
 * 整个 {@code precomputeBatch} 调用全程不抛异常。
 *
 * <p>🔒 <b>踩坑记录</b>：测试方法本身<b>不能</b>整体包一层 {@code @Transactional}——那样会与
 * {@code precomputeBatch} 内部分组级 {@code @Transactional(REQUIRES_NEW)}（本任务为隔离"一个分组
 * SQL 异常导致整个物理事务 aborted"而引入，见该方法 Javadoc）撞车：fixture 数据还在测试方法的外层
 * 事务里、尚未提交，REQUIRES_NEW 开的新事务（新物理连接）看不见这些未提交的行，会导致"必然失败的
 * 组件"读不到自己的 {@code component_sql_view} 行而被判定成 0 driver 组件、render() 不再抛异常，
 * 测试产生假阴性。改为 fixture 落在独立的 {@code @Transactional} 方法里先提交，再在无事务的测试
 * 方法体调用 {@code precomputeBatch}（该方法作为 CDI bean 外部调用，自行管理事务，读到已提交数据）。
 *
 * <p>自建测试数据（模板/组件/报价单/行/job），测后自行清理；customer/user 复用库内现存任意一条
 * （只读引用，不新建也不修改）。
 */
@QuarkusTest
class PriceAdjustJobExecutionServiceBatchFallbackTest {

    @Inject
    PriceAdjustJobExecutionService executionService;
    @Inject
    EntityManager em;

    private UUID templateAId, templateBId, templateCId;
    private UUID componentAId, componentBId;
    private UUID quotationAId, quotationBId, quotationCId;
    private UUID lineItemAId, lineItemBId, lineItemCId;
    private UUID jobId;

    @AfterEach
    @Transactional
    void cleanup() {
        if (jobId != null) {
            em.createNativeQuery("DELETE FROM material_price_update_job WHERE id = :id")
                .setParameter("id", jobId).executeUpdate(); // cascades job_item
        }
        for (UUID liId : List.of(lineItemAId, lineItemBId, lineItemCId)) {
            if (liId != null) em.createNativeQuery("DELETE FROM quotation_line_item WHERE id = :id")
                .setParameter("id", liId).executeUpdate();
        }
        for (UUID qId : List.of(quotationAId, quotationBId, quotationCId)) {
            if (qId != null) em.createNativeQuery("DELETE FROM quotation WHERE id = :id")
                .setParameter("id", qId).executeUpdate();
        }
        for (UUID tId : List.of(templateAId, templateBId, templateCId)) {
            if (tId != null) em.createNativeQuery("DELETE FROM template WHERE id = :id")
                .setParameter("id", tId).executeUpdate(); // cascades template_component
        }
        for (UUID cId : List.of(componentAId, componentBId)) {
            if (cId != null) em.createNativeQuery("DELETE FROM component WHERE id = :id")
                .setParameter("id", cId).executeUpdate(); // cascades component_sql_view
        }
    }

    @Test
    void groupFailureIsolation_andNoTreeTabTemplateIsSkipped() {
        seedFixture(); // 独立事务，方法返回时已提交

        List<MaterialPriceUpdateJobItem> items = loadItems(jobId);
        assertEquals(3, items.size());

        // ---- 执行：不应抛异常 ----
        Map<UUID, CardSnapshotService.PrecomputedTreeRows> result = executionService.precomputeBatch(jobId, items);

        assertNotNull(result, "precomputeBatch 不应返回 null");
        assertFalse(result.containsKey(lineItemAId),
            "模板 A（含树页签）分组 render() 必然失败 -> 不应写入预渲染结果（回退逐项，交给 upgrade() 默认路径）");
        assertTrue(result.containsKey(lineItemBId),
            "模板 B（含树页签）分组应正常批量成功，不应被模板 A 的失败拖累");
        assertFalse(result.containsKey(lineItemCId),
            "模板 C 不含树页签 -> 不应参与批量预渲染（对齐老路径 templateHasTreeTab 门槛，2026-08-07 亲验补丁）");
    }

    @Transactional
    List<MaterialPriceUpdateJobItem> loadItems(UUID jobId) {
        return MaterialPriceUpdateJobItem.list("jobId", jobId);
    }

    @Transactional
    void seedFixture() {
        UUID customerId = firstExisting("customer");
        UUID salesRepId = firstExisting("\"user\"");

        // ---- 模板 A（含树页签）：1 个必然失败的 driver 组件 ----
        templateAId = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO template (id, template_series_id, name) VALUES (:id, :sid, 'BF测试模板A')")
            .setParameter("id", templateAId).setParameter("sid", UUID.randomUUID()).executeUpdate();
        componentAId = UUID.randomUUID();
        String viewNameA = "bf_broken_view_" + componentAId.toString().replace("-", "");
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas, data_driver_path, bom_recursive_expand) " +
                "VALUES (:id, 'BF测试组件A', :code, '[]', '[]', :ddp, true)")
            .setParameter("id", componentAId).setParameter("code", "TEST-BF-A-" + componentAId)
            .setParameter("ddp", "$" + viewNameA).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template) " +
                "VALUES (:id, :cid, :vn, :tpl)")
            .setParameter("id", UUID.randomUUID()).setParameter("cid", componentAId).setParameter("vn", viewNameA)
            .setParameter("tpl", "SELECT * FROM this_table_definitely_does_not_exist_bf_test")
            .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO template_component (id, template_id, component_id, sort_order) VALUES (:id, :tid, :cid, 0)")
            .setParameter("id", UUID.randomUUID()).setParameter("tid", templateAId).setParameter("cid", componentAId)
            .executeUpdate();

        // ---- 模板 B（含树页签）：1 个合法但恒 0 行的 driver 组件（render() 必然成功） ----
        templateBId = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO template (id, template_series_id, name) VALUES (:id, :sid, 'BF测试模板B')")
            .setParameter("id", templateBId).setParameter("sid", UUID.randomUUID()).executeUpdate();
        componentBId = UUID.randomUUID();
        String viewNameB = "bf_empty_view_" + componentBId.toString().replace("-", "");
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas, data_driver_path, bom_recursive_expand) " +
                "VALUES (:id, 'BF测试组件B', :code, '[]', '[]', :ddp, true)")
            .setParameter("id", componentBId).setParameter("code", "TEST-BF-B-" + componentBId)
            .setParameter("ddp", "$" + viewNameB).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template) " +
                "VALUES (:id, :cid, :vn, :tpl)")
            .setParameter("id", UUID.randomUUID()).setParameter("cid", componentBId).setParameter("vn", viewNameB)
            .setParameter("tpl", "SELECT 'x'::text AS material_no, NULL::text AS parent_no WHERE false")
            .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO template_component (id, template_id, component_id, sort_order) VALUES (:id, :tid, :cid, 0)")
            .setParameter("id", UUID.randomUUID()).setParameter("tid", templateBId).setParameter("cid", componentBId)
            .executeUpdate();

        // ---- 模板 C（不含树页签）：无任何 driver 组件 ----
        templateCId = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO template (id, template_series_id, name) VALUES (:id, :sid, 'BF测试模板C')")
            .setParameter("id", templateCId).setParameter("sid", UUID.randomUUID()).executeUpdate();

        // ---- 三张最小报价单 + 各一行，costing_card_template_id 分别指向 A / B / C ----
        quotationAId = UUID.randomUUID();
        quotationBId = UUID.randomUUID();
        quotationCId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now();
        for (UUID[] pair : List.of(
                new UUID[]{quotationAId, templateAId},
                new UUID[]{quotationBId, templateBId},
                new UUID[]{quotationCId, templateCId})) {
            em.createNativeQuery(
                    "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                    "costing_card_template_id, created_at) " +
                    "VALUES (:id, :qn, :cust, 'BF测试报价单', :rep, 'DRAFT', :tmpl, :createdAt)")
                .setParameter("id", pair[0])
                .setParameter("qn", "TEST-BF-" + pair[0])
                .setParameter("cust", customerId)
                .setParameter("rep", salesRepId)
                .setParameter("tmpl", pair[1])
                .setParameter("createdAt", createdAt)
                .executeUpdate();
        }
        lineItemAId = UUID.randomUUID();
        lineItemBId = UUID.randomUUID();
        lineItemCId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot) VALUES (:id, :qid, 'BF-PART-A')")
            .setParameter("id", lineItemAId).setParameter("qid", quotationAId).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot) VALUES (:id, :qid, 'BF-PART-B')")
            .setParameter("id", lineItemBId).setParameter("qid", quotationBId).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot) VALUES (:id, :qid, 'BF-PART-C')")
            .setParameter("id", lineItemCId).setParameter("qid", quotationCId).executeUpdate();

        // ---- job + 三条 item ----
        jobId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO material_price_update_job (id, customer_no, status) VALUES (:id, 'TEST-BF', 'RUNNING')")
            .setParameter("id", jobId).executeUpdate();
        for (UUID[] triple : List.of(
                new UUID[]{quotationAId, lineItemAId}, // materialNo 用固定字符串区分，见下方循环体
                new UUID[]{quotationBId, lineItemBId},
                new UUID[]{quotationCId, lineItemCId})) {
            em.createNativeQuery(
                    "INSERT INTO material_price_update_job_item (id, job_id, quotation_id, material_no, line_item_id, status) " +
                    "VALUES (:id, :jid, :qid, 'BF-PART', :liid, 'WAITING')")
                .setParameter("id", UUID.randomUUID()).setParameter("jid", jobId).setParameter("qid", triple[0])
                .setParameter("liid", triple[1]).executeUpdate();
        }
    }

    private UUID firstExisting(String table) {
        Object id = em.createNativeQuery("SELECT id FROM " + table + " LIMIT 1").getSingleResult();
        return (UUID) id;
    }
}
