package com.cpq.component.resource;

import com.cpq.component.dto.BatchExpandDriverRequest;
import com.cpq.component.dto.BatchExpandDriverRequest.Task;
import com.cpq.component.dto.BatchExpandDriverResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * batch-expand 合桶等价护栏:证明 {@code cpq.batch-expand-bucket} flag <b>ON(bucket-merge)</b> 与
 * <b>OFF(逐 task expand)</b> 对同一组 task 产出 <b>逐位相同</b> 的 results[].data。
 *
 * <p>用真实报价(customer)模板的非 :lineItemId $view driver 组件 × 真实行 partNo 构造 task(无 lineItemId/override
 * → hasContext=false,两侧都走 live expand,不触 snapshot,纯比合桶 vs 逐查)。只读不写。
 * <p>flag 通过 System.setProperty 切换(batchExpand 调用时读取)。
 */
@QuarkusTest
class BatchExpandBucketEquivTest {

    @Inject ComponentResource resource;
    @Inject EntityManager em;
    static final ObjectMapper M = new ObjectMapper();
    static final String FLAG = "cpq.batch-expand-bucket";
    private String prev;

    @AfterEach
    void restore() {
        if (prev == null) System.clearProperty(FLAG); else System.setProperty(FLAG, prev);
    }

    record QPick(UUID quotationId, UUID customerId, UUID customerTemplateId) {}

    @Transactional
    QPick pick() {
        @SuppressWarnings("unchecked")
        List<Object[]> rs = em.createNativeQuery(
            "SELECT q.id, q.customer_id, q.customer_template_id FROM quotation q " +
            "JOIN quotation_line_item li ON li.quotation_id = q.id " +
            "WHERE q.customer_template_id IS NOT NULL AND li.product_part_no_snapshot IS NOT NULL " +
            "GROUP BY q.id, q.customer_id, q.customer_template_id HAVING count(li.id) >= 3 " +
            "ORDER BY count(li.id) ASC LIMIT 1").getResultList();
        if (rs.isEmpty()) return null;
        Object[] r = rs.get(0);
        return new QPick((UUID) r[0], (UUID) r[1], (UUID) r[2]);
    }

    @SuppressWarnings("unchecked")
    @Transactional
    List<UUID> reportDriverComps(UUID templateId) {
        return em.createNativeQuery(
            "SELECT DISTINCT c.id FROM template_component tc JOIN component c ON c.id = tc.component_id " +
            "LEFT JOIN component_sql_view sv ON sv.component_id = c.id " +
            "WHERE tc.template_id = :t AND c.data_driver_path LIKE '$%' " +
            "  AND (sv.sql_template IS NULL OR sv.sql_template NOT LIKE '%:lineItemId%')")
            .setParameter("t", templateId).getResultList();
    }

    @SuppressWarnings("unchecked")
    @Transactional
    List<String> partNos(UUID quotationId, int k) {
        return em.createNativeQuery(
            "SELECT DISTINCT product_part_no_snapshot FROM quotation_line_item " +
            "WHERE quotation_id = :q AND product_part_no_snapshot IS NOT NULL LIMIT :k")
            .setParameter("q", quotationId).setParameter("k", k).getResultList();
    }

    @Test
    void bucketOnEqualsOff() throws Exception {
        prev = System.getProperty(FLAG);
        QPick p = pick();
        Assumptions.assumeTrue(p != null, "无 ≥3 行的报价(customer 模板)单,跳过");
        List<UUID> comps = reportDriverComps(p.customerTemplateId());
        List<String> parts = partNos(p.quotationId(), 6);
        Assumptions.assumeTrue(!comps.isEmpty() && parts.size() >= 2, "无非 :lineItemId driver 组件或行不足,跳过");

        // 构造 task:每个报价 driver 组件 × 每个 partNo(无 lineItemId/override → 纯 live expand 比合桶)
        BatchExpandDriverRequest req = new BatchExpandDriverRequest();
        req.tasks = new ArrayList<>();
        for (UUID cid : comps) for (String pn : parts) {
            Task t = new Task();
            t.componentId = cid; t.customerId = p.customerId(); t.partNo = pn;
            t.quotationId = p.quotationId();
            req.tasks.add(t);
        }

        System.setProperty(FLAG, "false");
        BatchExpandDriverResponse off = resource.batchExpand(req).getData();
        System.setProperty(FLAG, "true");
        BatchExpandDriverResponse on = resource.batchExpand(req).getData();

        assertEquals(off.results.size(), on.results.size());
        int nonEmpty = 0;
        for (int i = 0; i < off.results.size(); i++) {
            var ro = off.results.get(i);
            var rn = on.results.get(i);
            assertEquals(ro.status, rn.status, "task " + i + " status 应一致");
            String a = ro.data == null ? "null" : M.writeValueAsString(ro.data);
            String b = rn.data == null ? "null" : M.writeValueAsString(rn.data);
            assertEquals(a, b, "task " + i + " (key=" + ro.key + ") 合桶 ON 应与 OFF 逐位一致");
            if (ro.data != null && ro.data.rowCount > 0) nonEmpty++;
        }
        assertTrue(nonEmpty > 0, "至少一个 task 应有非空行(否则测试空转)");
        System.out.println("=== BUCKET-EQUIV tasks=" + req.tasks.size() + " nonEmptyRows=" + nonEmpty
            + " comps=" + comps.size() + " parts=" + parts.size() + " ===");
    }
}
