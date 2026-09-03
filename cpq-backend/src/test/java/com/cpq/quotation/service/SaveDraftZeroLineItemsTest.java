package com.cpq.quotation.service;

import com.cpq.quotation.dto.SaveDraftRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260829 · T-09（AC-11，边界·0 行）—— 0 个产品行的空 DRAFT 单点「保存草稿」。
 *
 * <p><b>验的是什么</b>：{@code 问题说明.md} AC-11 —— 0 产品行的空 DRAFT 单保存必须 HTTP 200、
 * 不抛异常、子表无残留。B-1/B-2 把主循环外提了一次 {@code metaByComponent} 预取 + 一次
 * {@code em.flush()}；0 行时这两处都应是"空转但不出错"的边界形态，而不是因为集合为空
 * 触发 {@code NullPointerException}/{@code IndexOutOfBoundsException} 之类的循环边界 bug。
 *
 * <p><b>fixture 与清理沿用 {@code BatchStage1PersistEquivTest} 的手法</b>：直接 native SQL 建一条
 * 独立的测试报价单（不复用共享库数据，不影响其他测试/真实业务单），测试结束显式删除还原。
 */
@QuarkusTest
@DisplayName("SaveDraftZeroLineItemsTest — repair-260829 T-09(AC-11) 0行空DRAFT单保存")
class SaveDraftZeroLineItemsTest {

    @Inject
    QuotationService quotationService;

    @Inject
    EntityManager em;

    private static final UUID TEST_USER_ID = UUID.fromString("896ed7d9-bf12-4ea7-9ff1-09cb14496311");

    private final List<UUID> createdQuotationIds = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private UUID anyCustomerId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
        assertFalse(rows.isEmpty(), "DB 无任何 customer,无法建 fixture");
        Object o = rows.get(0);
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    /** 建一条 0 行的独立测试报价单(状态 DRAFT,不挂任何 line item)。 */
    @Transactional
    UUID createEmptyDraftQuotation() {
        UUID qid = UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO quotation (id, quotation_number, customer_id, sales_rep_id, name, status, " +
                                "total_amount, original_amount, system_discount_rate, final_discount_rate, " +
                                "tax_rate, tax_amount, is_manually_adjusted, created_at, updated_at) " +
                                "VALUES (:id, :num, :cid, :sid, :name, 'DRAFT', 0, 0, 100, 100, 0, 0, false, NOW(), NOW())")
                .setParameter("id", qid)
                .setParameter("num", "TEST-T09-ZERO-LINES-" + System.nanoTime())
                .setParameter("cid", anyCustomerId())
                .setParameter("sid", TEST_USER_ID)
                .setParameter("name", "repair-260829 T-09 空DRAFT单")
                .executeUpdate();
        createdQuotationIds.add(qid);
        return qid;
    }

    @Transactional
    void cleanupQuotation(UUID qid) {
        em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN " +
                        "(SELECT id FROM quotation_line_item WHERE quotation_id = :qid)")
                .setParameter("qid", qid).executeUpdate();
        em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :qid")
                .setParameter("qid", qid).executeUpdate();
        em.createNativeQuery("DELETE FROM quotation WHERE id = :qid")
                .setParameter("qid", qid).executeUpdate();
    }

    @AfterEach
    void cleanupAll() {
        for (UUID qid : createdQuotationIds) {
            try {
                cleanupQuotation(qid);
            } catch (Exception e) {
                System.err.println("[T-09 cleanup] failed for " + qid + ": " + e.getMessage());
            }
        }
        createdQuotationIds.clear();
    }

    /** 捕获 [stage1-profile] 埋点日志(若存在),用于软性核对 lines=0 —— 埋点是诊断产物,
     *  合并前会被移除(AC-16),因此本断言不强制要求匹配到,只在匹配到时做内容校验。 */
    private List<String> captureStage1ProfileLogs(Runnable action) {
        List<String> captured = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String msg = record.getMessage();
                if (msg != null && msg.contains("[stage1-profile]")) {
                    captured.add(msg);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
        try {
            action.run();
        } finally {
            root.removeHandler(handler);
        }
        return captured;
    }

    @Test
    @DisplayName("AC-11: 0产品行的DRAFT单保存草稿 → 200,不抛异常,子表无残留")
    void saveDraft_zeroLineItems_succeedsWithoutResidualRows() {
        UUID qid = createEmptyDraftQuotation();

        // 前置校验(非空验证的另一侧):确认 fixture 真的是 0 行,不是空跑
        Number preLineCount = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM quotation_line_item WHERE quotation_id = :qid")
                .setParameter("qid", qid).getSingleResult();
        assertEquals(0L, preLineCount.longValue(), "前置状态应确实是 0 行,不能是构造失败导致的空验证");

        SaveDraftRequest req = new SaveDraftRequest();
        req.lineItems = List.of(); // 0 个产品行

        List<String> logs = captureStage1ProfileLogs(() ->
                assertDoesNotThrow(() -> quotationService.saveDraft(qid, req),
                        "0行DRAFT单保存草稿不应抛出任何异常"));

        // 子表无残留
        Number cdCount = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM quotation_line_component_data d " +
                                "JOIN quotation_line_item li ON li.id = d.line_item_id " +
                                "WHERE li.quotation_id = :qid")
                .setParameter("qid", qid).getSingleResult();
        assertEquals(0L, cdCount.longValue(), "0行单保存后 componentData 子表应无残留");

        Number liCount = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM quotation_line_item WHERE quotation_id = :qid")
                .setParameter("qid", qid).getSingleResult();
        assertEquals(0L, liCount.longValue(), "0行单保存后 line_item 仍应为 0 行");

        // 埋点(若存在)软性核对:lines=0
        System.out.println("[T-09] captured [stage1-profile] logs=" + logs);
        Pattern linesPattern = Pattern.compile("lines=(\\d+)");
        for (String line : logs) {
            Matcher m = linesPattern.matcher(line);
            if (m.find()) {
                assertEquals("0", m.group(1), "埋点若打印 lines=N,N 应为 0: " + line);
            }
        }
    }
}
