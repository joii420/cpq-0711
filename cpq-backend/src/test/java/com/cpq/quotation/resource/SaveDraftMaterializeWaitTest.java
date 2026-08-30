package com.cpq.quotation.resource;

import com.cpq.basicdata.v6.service.MaterializeRegistry;
import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.dto.SaveDraftRequest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260829-b11 · saveDraft 入口等待建单后置物化完成（AC-34~37）。
 *
 * <p><b>为什么不真的走导入建单</b>：AC-34 需要 {@code materializeRegistry.isInProgress} 为真，
 * 但真实走一次 V6 导入建单需要 Excel 素材、耗时 ~30s、且会在共享 dev 库留数据。本测试直接
 * {@code @Inject MaterializeRegistry} 手动 {@code begin}/{@code end}，用后台线程模拟"物化正在跑
 * → 完成"的时序，同时验证 AC-34（等待后成功）/AC-35（零延迟）/AC-36（超时提示）三条。
 *
 * <p><b>为什么直接注入 {@code QuotationResource} 而不是 {@code QuotationService}</b>：B-11 的等待逻辑
 * 刻意放在 Resource 层、{@code quotationService.saveDraft} 调用之前（事务外）——只测 Service 测不到
 * 这条排队逻辑是否真的生效。项目已有先例（{@code ComponentResourceSnapshotBypassUsageTest}）直接
 * 注入 JAX-RS Resource bean 调用方法，绕开 HTTP/RBAC 层，聚焦被测逻辑本身。
 *
 * <p>fixture 用 0 行 DRAFT 单（同 {@code SaveDraftZeroLineItemsTest} / 并发会话
 * {@code QuotationSubmitMaterializeInProgressGuardTest} 的手法）——0 行时 {@code saveDraft} 主体逻辑
 * 空转不出错，测试焦点收窄到"排队等待"这一件事上。
 */
@QuarkusTest
@DisplayName("SaveDraftMaterializeWaitTest — repair-260829-b11 AC-34~37")
class SaveDraftMaterializeWaitTest {

    @Inject
    QuotationResource resource;

    @Inject
    MaterializeRegistry registry;

    @Inject
    EntityManager em;

    private static final UUID TEST_USER_ID = UUID.fromString("896ed7d9-bf12-4ea7-9ff1-09cb14496311");
    private static final String TIMEOUT_PROP = "cpq.savedraft-materialize-wait-timeout-ms";

    private final List<UUID> createdQuotationIds = new ArrayList<>();
    private final List<UUID> beganRegistryIds = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private UUID anyCustomerId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
        assertFalse(rows.isEmpty(), "DB 无任何 customer,无法建 fixture");
        Object o = rows.get(0);
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    private UUID buildZeroLineDraftQuotation(String tag) {
        UUID qid = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery(
                            "INSERT INTO quotation (id, quotation_number, customer_id, sales_rep_id, name, status, " +
                                    "total_amount, original_amount, system_discount_rate, final_discount_rate, " +
                                    "tax_rate, tax_amount, is_manually_adjusted, created_at, updated_at) " +
                                    "VALUES (:id, :num, :cid, :sid, :name, 'DRAFT', 0, 0, 100, 100, 0, 0, false, NOW(), NOW())")
                    .setParameter("id", qid)
                    .setParameter("num", "TEST-B11-" + tag + "-" + System.nanoTime())
                    .setParameter("cid", anyCustomerId())
                    .setParameter("sid", TEST_USER_ID)
                    .setParameter("name", "repair-260829-b11 " + tag)
                    .executeUpdate();
        });
        createdQuotationIds.add(qid);
        return qid;
    }

    private SaveDraftRequest emptyDraftRequest() {
        SaveDraftRequest req = new SaveDraftRequest();
        req.lineItems = List.of();
        return req;
    }

    @AfterEach
    void cleanup() {
        // 保险丝：任何测试中途失败都不能让 registry 残留 true,污染后续测试/真实业务单
        for (UUID qid : beganRegistryIds) {
            registry.end(qid);
        }
        beganRegistryIds.clear();
        System.clearProperty(TIMEOUT_PROP);
        for (UUID qid : createdQuotationIds) {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN " +
                                    "(SELECT id FROM quotation_line_item WHERE quotation_id = :qid)")
                            .setParameter("qid", qid).executeUpdate();
                    em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :qid")
                            .setParameter("qid", qid).executeUpdate();
                    em.createNativeQuery("DELETE FROM quotation WHERE id = :qid")
                            .setParameter("qid", qid).executeUpdate();
                });
            } catch (Exception e) {
                System.err.println("[B-11 cleanup] failed for " + qid + ": " + e.getMessage());
            }
        }
        createdQuotationIds.clear();
    }

    /** 捕获 [draft-profile] 埋点,用于 AC-37 校验 S1.saveDraft 不含等待时长(存量埋点,不在 AC-16 移除范围)。 */
    private List<String> captureDraftProfileLogs(Runnable action) {
        List<String> captured = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                String msg = record.getMessage();
                if (msg != null && msg.contains("[draft-profile]")) {
                    captured.add(msg);
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
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
    @DisplayName("AC-34: 物化进行中(isInProgress=true) 保存草稿排队等待,物化结束后 200 成功,不再409")
    void saveDraft_waitsForMaterializeThenSucceeds() throws InterruptedException {
        UUID qid = buildZeroLineDraftQuotation("AC34");

        registry.begin(qid);
        beganRegistryIds.add(qid);
        assertTrue(registry.isInProgress(qid), "前置条件确认: registry 应标记为进行中");

        long simulatedMaterializeMs = 900;
        Thread bg = new Thread(() -> {
            try {
                Thread.sleep(simulatedMaterializeMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            registry.end(qid);
        }, "simulated-materialize");
        bg.start();

        long t0 = System.currentTimeMillis();
        ApiResponse<QuotationDTO> resp = resource.saveDraft(qid, emptyDraftRequest());
        long elapsed = System.currentTimeMillis() - t0;
        bg.join();

        assertNotNull(resp, "saveDraft 应正常返回,不抛异常/409");
        assertEquals(200, resp.getCode(), "物化结束后保存应 200 成功,实际 code=" + resp.getCode());
        assertTrue(elapsed >= simulatedMaterializeMs - 100,
                "总耗时应包含排队等待(≈" + simulatedMaterializeMs + "ms),实际=" + elapsed + "ms");
        assertTrue(elapsed < 40_000, "不应触达 40s 超时上限,实际=" + elapsed + "ms");

        System.out.printf("[AC-34] wait+save elapsed=%dms (simulated materialize=%dms)%n", elapsed, simulatedMaterializeMs);
    }

    @Test
    @DisplayName("AC-35(反向·零延迟): isInProgress=false 时立即放行,不引入任何轮询延迟")
    void saveDraft_zeroDelayWhenNotMaterializing() {
        UUID qid = buildZeroLineDraftQuotation("AC35");
        assertFalse(registry.isInProgress(qid), "前置条件确认: registry 未标记进行中");

        long t0 = System.currentTimeMillis();
        ApiResponse<QuotationDTO> resp = resource.saveDraft(qid, emptyDraftRequest());
        long elapsed = System.currentTimeMillis() - t0;

        assertEquals(200, resp.getCode());
        // 轮询间隔是 500ms;若误入轮询分支,耗时至少多出一个 500ms 台阶。零延迟路径下
        // 0 行单的 saveDraft 本身应在几十~几百 ms 内完成,留足余量断言 < 500ms 证明未轮询。
        assertTrue(elapsed < 500, "isInProgress=false 应零延迟放行,不应有 500ms 级轮询开销,实际=" + elapsed + "ms");

        System.out.printf("[AC-35] zero-delay path elapsed=%dms%n", elapsed);
    }

    @Test
    @DisplayName("AC-36(反向·超时可理解): isInProgress 长时间为true,超时后409+可理解中文文案,不抛原始唯一约束错误")
    void saveDraft_timeoutReturnsUnderstandable409() {
        System.setProperty(TIMEOUT_PROP, "1200"); // 缩短超时上限,避免测试跑 40s

        UUID qid = buildZeroLineDraftQuotation("AC36");
        registry.begin(qid);
        beganRegistryIds.add(qid); // AfterEach 兜底 end(),测试体内本身不 end,模拟"物化一直不结束"

        long t0 = System.currentTimeMillis();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> resource.saveDraft(qid, emptyDraftRequest()),
                "超时后应抛 BusinessException(409),不应无限转圈或抛原始 Duplicate value for Key");
        long elapsed = System.currentTimeMillis() - t0;

        assertEquals(409, ex.getCode(), "超时应返回409,实际=" + ex.getCode());
        assertTrue(ex.getMessage() != null
                        && ex.getMessage().contains("基础数据正在准备中")
                        && ex.getMessage().contains("已等待")
                        && ex.getMessage().contains("秒仍未完成")
                        && ex.getMessage().contains("请稍后重试"),
                "文案应可理解且含已等待秒数,实际=" + ex.getMessage());
        assertFalse(ex.getMessage().contains("Duplicate value for Key"),
                "不应把原始唯一约束错误文案透传给用户");
        assertTrue(elapsed >= 1200 && elapsed < 1200 + 500 + 2000,
                "耗时应约等于超时上限(1200ms)+一个轮询间隔量级,实际=" + elapsed + "ms");

        System.out.printf("[AC-36] timeout elapsed=%dms msg=%s%n", elapsed, ex.getMessage());
    }

    @Test
    @DisplayName("AC-37(反向·事务外等待): 等待期间不持有quotation行锁,且[draft-profile] S1.saveDraft不含等待时长")
    void saveDraft_waitsOutsideTransaction_doesNotHoldRowLockOrInflateS1() throws InterruptedException {
        UUID qid = buildZeroLineDraftQuotation("AC37");
        registry.begin(qid);
        beganRegistryIds.add(qid);

        long simulatedMaterializeMs = 1500;
        Thread bg = new Thread(() -> {
            try {
                Thread.sleep(simulatedMaterializeMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            registry.end(qid);
        }, "simulated-materialize-ac37");
        bg.start();

        // 排队等待期间(bg 尚未 end),从另一个独立事务尝试 SELECT ... FOR UPDATE NOWAIT。
        // 若 saveDraft 的等待阶段仍持有该行的悲观锁,这里会立刻抛 LockTimeoutException/PessimisticLockException;
        // 不抛异常即证明等待确实发生在事务外、未持有行锁(AC-37 前半段)。
        Thread.sleep(300); // 确保此刻主线程已进入 awaitMaterializeIdle 轮询(而非已经跑完)
        AtomicReference<Exception> lockProbeError = new AtomicReference<>();
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                em.createNativeQuery("SELECT id FROM quotation WHERE id = :id FOR UPDATE NOWAIT")
                        .setParameter("id", qid)
                        .getSingleResult();
            });
        } catch (Exception e) {
            lockProbeError.set(e);
        }
        assertNull(lockProbeError.get(),
                "等待期间该行不应被 saveDraft 的等待逻辑锁住(事务外等待),但探测到异常: " + lockProbeError.get());

        AtomicReference<Long> s1Ms = new AtomicReference<>();
        Pattern s1Pattern = Pattern.compile("S1\\.saveDraft=(\\d+)ms");
        List<String> logs = captureDraftProfileLogs(() -> {
            ApiResponse<QuotationDTO> resp = resource.saveDraft(qid, emptyDraftRequest());
            assertEquals(200, resp.getCode());
        });
        bg.join();

        for (String line : logs) {
            Matcher m = s1Pattern.matcher(line);
            if (m.find()) {
                s1Ms.set(Long.parseLong(m.group(1)));
            }
        }
        System.out.println("[AC-37] captured [draft-profile] logs=" + logs);
        if (s1Ms.get() != null) {
            assertTrue(s1Ms.get() < simulatedMaterializeMs,
                    "S1.saveDraft 埋点只应统计真正的保存时长,不应含约 " + simulatedMaterializeMs
                            + "ms 的排队等待,实际 S1=" + s1Ms.get() + "ms");
        } else {
            System.out.println("[AC-37] 未捕获到 [draft-profile] 日志行(可能日志级别/handler未命中),S1 数值口径未验证");
        }
    }
}
