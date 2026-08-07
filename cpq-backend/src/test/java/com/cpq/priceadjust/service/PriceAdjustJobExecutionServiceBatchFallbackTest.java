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
 * task-0806 · FR-5（守卫 2）/ AC-4 单测：批量预渲染<b>某一分组</b>失败时，只有该分组不写入预渲染结果
 * （回退逐项），<b>不影响其它分组</b>——{@link PriceAdjustJobExecutionService#precomputeBatch} 整体
 * 不抛异常、不把"批量失败"升级成"整批 job 失败"。
 *
 * <p>构造两个 line item，分属两个不同的核价模板（天然落入不同分组，不依赖日期差异）：
 * <ul>
 *   <li>模板 A：挂一个 driver 组件，其 {@code $view} sql_template 语法错误（查不存在的表），
 *       {@code render()} 对该分组必然抛异常；</li>
 *   <li>模板 B：无任何 driver 组件，{@code render()} 对该分组必然成功（返回空 baseRows）。</li>
 * </ul>
 *
 * <p>预期：{@code precomputeBatch} 返回的 map 里，模板 A 那个 line item <b>没有</b>条目（回退逐项，
 * 交给 {@code upgrade()} 默认路径自己再 render 一次并按现有逻辑精确 FAILED），模板 B 那个 line item
 * <b>有</b>条目（该分组批量成功，未被拖累）；整个方法调用不抛异常。
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

    private UUID templateAId, templateBId, componentAId;
    private UUID quotationAId, quotationBId, lineItemAId, lineItemBId;
    private UUID jobId;

    @AfterEach
    @Transactional
    void cleanup() {
        if (jobId != null) {
            em.createNativeQuery("DELETE FROM material_price_update_job WHERE id = :id")
                .setParameter("id", jobId).executeUpdate(); // cascades job_item
        }
        if (lineItemAId != null) em.createNativeQuery("DELETE FROM quotation_line_item WHERE id = :id")
            .setParameter("id", lineItemAId).executeUpdate();
        if (lineItemBId != null) em.createNativeQuery("DELETE FROM quotation_line_item WHERE id = :id")
            .setParameter("id", lineItemBId).executeUpdate();
        if (quotationAId != null) em.createNativeQuery("DELETE FROM quotation WHERE id = :id")
            .setParameter("id", quotationAId).executeUpdate();
        if (quotationBId != null) em.createNativeQuery("DELETE FROM quotation WHERE id = :id")
            .setParameter("id", quotationBId).executeUpdate();
        if (templateAId != null) em.createNativeQuery("DELETE FROM template WHERE id = :id")
            .setParameter("id", templateAId).executeUpdate(); // cascades template_component
        if (templateBId != null) em.createNativeQuery("DELETE FROM template WHERE id = :id")
            .setParameter("id", templateBId).executeUpdate();
        if (componentAId != null) em.createNativeQuery("DELETE FROM component WHERE id = :id")
            .setParameter("id", componentAId).executeUpdate(); // cascades component_sql_view
    }

    @Test
    void oneGroupRenderFailure_doesNotAffectOtherGroup_andDoesNotThrow() {
        seedFixture(); // 独立事务，方法返回时已提交

        List<MaterialPriceUpdateJobItem> items = loadItems(jobId);
        assertEquals(2, items.size());

        // ---- 执行：不应抛异常 ----
        Map<UUID, CardSnapshotService.PrecomputedTreeRows> result = executionService.precomputeBatch(jobId, items);

        assertNotNull(result, "precomputeBatch 不应返回 null");
        assertFalse(result.containsKey(lineItemAId),
            "模板 A 分组 render() 必然失败 -> 不应写入预渲染结果（回退逐项，交给 upgrade() 默认路径）");
        assertTrue(result.containsKey(lineItemBId),
            "模板 B 分组应正常批量成功，不应被模板 A 的失败拖累");
    }

    @Transactional
    List<MaterialPriceUpdateJobItem> loadItems(UUID jobId) {
        return MaterialPriceUpdateJobItem.list("jobId", jobId);
    }

    @Transactional
    void seedFixture() {
        UUID customerId = firstExisting("customer");
        UUID salesRepId = firstExisting("\"user\"");

        // ---- 模板 A：1 个必然失败的 driver 组件 ----
        templateAId = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO template (id, template_series_id, name) VALUES (:id, :sid, 'BF测试模板A')")
            .setParameter("id", templateAId).setParameter("sid", UUID.randomUUID()).executeUpdate();
        componentAId = UUID.randomUUID();
        String viewName = "bf_broken_view_" + componentAId.toString().replace("-", "");
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas, data_driver_path) " +
                "VALUES (:id, 'BF测试组件A', :code, '[]', '[]', :ddp)")
            .setParameter("id", componentAId).setParameter("code", "TEST-BF-A-" + componentAId)
            .setParameter("ddp", "$" + viewName).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template) " +
                "VALUES (:id, :cid, :vn, :tpl)")
            .setParameter("id", UUID.randomUUID()).setParameter("cid", componentAId).setParameter("vn", viewName)
            .setParameter("tpl", "SELECT * FROM this_table_definitely_does_not_exist_bf_test")
            .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO template_component (id, template_id, component_id, sort_order) VALUES (:id, :tid, :cid, 0)")
            .setParameter("id", UUID.randomUUID()).setParameter("tid", templateAId).setParameter("cid", componentAId)
            .executeUpdate();

        // ---- 模板 B：无 driver 组件（空模板，render() 必然成功） ----
        templateBId = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO template (id, template_series_id, name) VALUES (:id, :sid, 'BF测试模板B')")
            .setParameter("id", templateBId).setParameter("sid", UUID.randomUUID()).executeUpdate();

        // ---- 两张最小报价单 + 各一行，costing_card_template_id 分别指向 A / B ----
        quotationAId = UUID.randomUUID();
        quotationBId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now();
        for (UUID[] pair : List.of(new UUID[]{quotationAId, templateAId}, new UUID[]{quotationBId, templateBId})) {
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
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot) VALUES (:id, :qid, 'BF-PART-A')")
            .setParameter("id", lineItemAId).setParameter("qid", quotationAId).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot) VALUES (:id, :qid, 'BF-PART-B')")
            .setParameter("id", lineItemBId).setParameter("qid", quotationBId).executeUpdate();

        // ---- job + 两条 item ----
        jobId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO material_price_update_job (id, customer_no, status) VALUES (:id, 'TEST-BF', 'RUNNING')")
            .setParameter("id", jobId).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO material_price_update_job_item (id, job_id, quotation_id, material_no, line_item_id, status) " +
                "VALUES (:id, :jid, :qid, 'BF-PART-A', :liid, 'WAITING')")
            .setParameter("id", UUID.randomUUID()).setParameter("jid", jobId).setParameter("qid", quotationAId)
            .setParameter("liid", lineItemAId).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO material_price_update_job_item (id, job_id, quotation_id, material_no, line_item_id, status) " +
                "VALUES (:id, :jid, :qid, 'BF-PART-B', :liid, 'WAITING')")
            .setParameter("id", UUID.randomUUID()).setParameter("jid", jobId).setParameter("qid", quotationBId)
            .setParameter("liid", lineItemBId).executeUpdate();
    }

    private UUID firstExisting(String table) {
        Object id = em.createNativeQuery("SELECT id FROM " + table + " LIMIT 1").getSingleResult();
        return (UUID) id;
    }
}
