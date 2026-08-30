package com.cpq.quotation.service;

import com.cpq.basicdata.v6.service.MaterializeRegistry;
import com.cpq.common.exception.BusinessException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260829（卡片值算早了骨架值锁死）· T-16（AC-8 边界，B-1b 金额路径不得静默通过）。
 *
 * <p><b>为什么单独一个文件</b>：{@code CardSnapshotMaterializeInProgressGuardTest}（T-15）验的是
 * B-1b 守卫本身（{@code ensureCardValuesDetailed} 命中时返回 {@code WARMING_IN_PROGRESS}）；
 * 本文件验<b>再往上一层</b>——{@link QuotationService#submit} 是否真的把这个信号翻译成了
 * 提交侧的硬失败（409），而不是放行去冻结一份用缺失卡片值算出来的金额。两层缺一不可：只测
 * 前者，测不出"submit 判断分支写漏了/写反了"这类回归（正是主线复审抓到的那类"动作对了但
 * 信号没发对"的坑）。
 *
 * <p><b>fixture 选择 0 行明细</b>：{@code submit} 内部有提交闸门(reconcile 差异)/行键唯一性/
 * 快照收集/审批路由等一长串前置逻辑，真实驱动组件+多行明细会引入大量与本条无关的失败面。
 * 0 行明细时上述逐行循环全部空跑、不产生冲突，可以干净地把测试焦点收窄到
 * "{@code registry.begin(qid)} 状态下 submit 是否 409"这一件事上，不需要验证 submit 全流程
 * 的正确性(那不是本条 AC 的范围)。
 */
@QuarkusTest
class QuotationSubmitMaterializeInProgressGuardTest {

    @Inject EntityManager em;
    @Inject QuotationService quotationService;
    @Inject MaterializeRegistry registry;

    private UUID quotationId;

    private static UUID toUUID(Object o) {
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    /**
     * submit() 一旦真的走通(如 T-16-b 对照组,不带 registry.begin 时不会被 B-1b 拦),会派生一整串
     * 引用 quotation.id 的子表行(审批/核价单/组件SQL快照闭包等)——实测首跑时 costing_order 的
     * FK 约束挡住了简单的两表清理,报 information_schema 查出的全部 9 个 FK 子表,统一清理,
     * 避免在共享 test 库留下孤儿行。
     */
    @AfterEach
    void cleanup() {
        if (quotationId != null) registry.end(quotationId); // 保险丝
        if (quotationId == null) return;
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM costing_sheet_drop WHERE quotation_id = :q").setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM import_record WHERE quotation_id = :q").setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_approval WHERE quotation_id = :q").setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_component_sql_snapshot WHERE quotation_id = :q").setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_withdraw_request WHERE quotation_id = :q").setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_view_structure WHERE quotation_id = :q").setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM costing_order WHERE quotation_id = :q").setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_price_revision WHERE quotation_id = :q").setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM material_price_update_job_item WHERE quotation_id = :q").setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_item_snapshot WHERE line_item_id IN " +
                    "(SELECT id FROM quotation_line_item WHERE quotation_id = :q)").setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN " +
                    "(SELECT id FROM quotation_line_item WHERE quotation_id = :q)").setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :q").setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation WHERE id = :q").setParameter("q", quotationId).executeUpdate();
        });
    }

    private UUID buildZeroLineDraftQuotation(String tag) {
        UUID qid = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            UUID customerId = toUUID(em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList().get(0));
            UUID salesRepId = toUUID(em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList().get(0));
            em.createNativeQuery("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                    "tax_rate, tax_amount, created_at, updated_at) " +
                    "VALUES (:id, :qn, :cid, :name, :srid, 'DRAFT', 0, 0, now(), now())")
                    .setParameter("id", qid).setParameter("qn", tag + "-" + qid.toString().substring(0, 8))
                    .setParameter("cid", customerId).setParameter("name", tag).setParameter("srid", salesRepId)
                    .executeUpdate();
        });
        return qid;
    }

    @Test
    @DisplayName("T-16(AC-8边界): 物化进行中(registry.begin) 提交必须409,文案含'重算',不得静默放行冻结")
    void submitWhileMaterializing_mustReturn409NotSilentlyPass() {
        quotationId = buildZeroLineDraftQuotation("T260829T16");

        registry.begin(quotationId);
        assertTrue(registry.isInProgress(quotationId), "前置条件确认: registry 应标记为进行中");

        BusinessException ex = assertThrows(BusinessException.class, () -> quotationService.submit(quotationId),
                "物化进行中时提交应抛 BusinessException(409),不应静默放行");
        assertEquals(409, ex.getCode(), "状态码应为409,实际=" + ex.getCode() + " msg=" + ex.getMessage());
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("重算"),
                "文案应提示'正在重算',实际=" + ex.getMessage());

        System.out.printf("[T-16] submit while materializing → 409: %s%n", ex.getMessage());
    }

    @Test
    @DisplayName("T-16-b(对照组): registry 未标记进行中时,同一份0行草稿可以正常提交(非409)")
    void submitWithoutMaterializing_doesNotGet409ForThisReason() {
        quotationId = buildZeroLineDraftQuotation("T260829T16B");
        assertFalse(registry.isInProgress(quotationId), "前置条件确认: registry 未标记进行中");

        // 不 begin,直接提交 —— 不应因为 B-1b 报409(可能因为其他原因失败,但不应是"正在重算"这条)。
        try {
            quotationService.submit(quotationId);
        } catch (BusinessException ex) {
            assertFalse(ex.getMessage() != null && ex.getMessage().contains("重算"),
                    "未处于物化中时,不应报'正在重算'这条409,实际=" + ex.getMessage());
        }
    }
}
