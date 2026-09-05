package com.cpq.task260903;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A-AC-3 / A-AC-10</b>：客户产品编号落新表，且 {@code sel_product_no} 退役。
 *
 * <p>A-AC-3 原文：「输入编号 {@code T260903-A}」⇒「落 {@code ds_quote_customer_part}
 * （{@code customer_no} + {@code customer_product_no} + {@code material_no}）；
 * 🚫 <b>{@code sel_product_no} 零新增</b>（该表本任务退役）」。
 *
 * <p>A-AC-10 原文：「两个会话<b>同时</b>用同一客户产品编号提交」⇒
 * 「恰好一个成功、另一个返 <b>409</b>；{@code ds_quote_customer_part} 只有 1 行
 * （{@code uq_ds_quote_customer_part} 保证）」。
 */
@QuarkusTest
@DisplayName("A-AC-3/10 客户产品编号映射与并发唯一性")
class CustomerPartMappingTest extends Task260903Base {

    private Map<String, Object> aPart() {
        return newPart("触点", "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "70"),
                        material(RECIPE_B, CONFIG_B, "30")),
                List.of(PROC_1));
    }

    @Test
    @DisplayName("A-AC-3 编号落 ds_quote_customer_part；sel_product_no 零新增")
    void aac3_productNoGoesToNewTableAndSelProductNoRetired() {
        Fx fx = newFixture("aac3");
        String productNo = "T260903-A";

        long spn0 = selProductNo(fx.customerNo());
        long spnGlobal0 = count("SELECT count(*) FROM sel_product_no");
        System.out.println("[A-AC-3] 提交前 sel_product_no 本客户=" + spn0 + " 全表=" + spnGlobal0);

        assertSubmitOk(configure(fx, submitBody(productNo, aPart())), "A-AC-3 提交");
        String partNo = latestLinePartNo(fx);
        assertNewTablesGotRows(partNo, "A-AC-3");

        // ① 落 ds_quote_customer_part，三个字段都要对
        List<Object[]> cps = rows("SELECT customer_no, customer_product_no, material_no "
                + "FROM ds_quote_customer_part WHERE customer_no='" + fx.customerNo() + "'");
        System.out.println("[A-AC-3①] ds_quote_customer_part=" + cps.stream().map(java.util.Arrays::toString).toList());
        assertEquals(1, cps.size(),
                "A-AC-3①：本客户应恰好 1 行 ds_quote_customer_part，实际 " + cps.size() + " 行");
        assertEquals(fx.customerNo(), String.valueOf(cps.get(0)[0]), "A-AC-3①：customer_no 应为本客户");
        assertEquals(productNo, String.valueOf(cps.get(0)[1]),
                "A-AC-3①：customer_product_no 应逐字为输入的 " + productNo + "，实际=" + cps.get(0)[1]);
        assertEquals(partNo, String.valueOf(cps.get(0)[2]),
                "A-AC-3①：material_no 应指向本次铸出的料号 " + partNo + "，实际=" + cps.get(0)[2]);

        // ② sel_product_no 零新增（该表本任务退役）
        long spn1 = selProductNo(fx.customerNo());
        long spnGlobal1 = count("SELECT count(*) FROM sel_product_no");
        System.out.println("[A-AC-3②] 提交后 sel_product_no 本客户=" + spn1 + " 全表=" + spnGlobal1);
        assertEquals(0, spn1,
                "A-AC-3②：sel_product_no 本任务退役 ⇒ 本客户不得有任何行，实际 " + spn1 + " 行 ⇒ 仍在写旧表");
        assertEquals(spnGlobal0, spnGlobal1,
                "A-AC-3②：sel_product_no 全表行数不得变化，" + spnGlobal0 + "→" + spnGlobal1);
    }

    /**
     * <b>A-AC-10</b>（边界）：两个会话<b>同时</b>用同一编号提交 → 恰好 1×成功 + 1×409。
     *
     * <p>🚨 判据用「状态不变量」而不是「谁先谁后」：并发的胜负是不确定的，
     * 断言「第一个 200、第二个 409」会随调度翻脸。真正的不变量是
     * <b>{@code {成功数=1, 409数=1, 落库行数=1}}</b>。
     */
    @Test
    @DisplayName("A-AC-10 并发同编号 → 恰好 1×200 + 1×409，落库只 1 行")
    void aac10_concurrentSameProductNoYieldsExactlyOneWinner() {
        Fx fx = newFixture("aac10");
        String productNo = "T260903-CONCURRENT";

        // 先把会话热起来：否则两个线程各自触发登录，撞到登录限流(429)会伪装成业务失败
        adminSession();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Response> r1 = new AtomicReference<>(), r2 = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();

        Runnable task = () -> {
            try {
                start.await();
                Response r = configure(fx, submitBody(productNo, aPart()));
                (r1.compareAndSet(null, r) ? r1 : r2).set(r);
            } catch (Throwable t) {
                err.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        };
        Thread t1 = new Thread(task, "aac10-a"), t2 = new Thread(task, "aac10-b");
        t1.start(); t2.start();
        start.countDown();
        try {
            assertTrue(done.await(120, TimeUnit.SECONDS), "A-AC-10：两个并发提交应在 120s 内返回");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("A-AC-10 被中断", e);
        }
        assertNotNull(r1.get(), "A-AC-10：第一个响应为空" + (err.get() != null ? "，异常=" + err.get() : ""));
        assertNotNull(r2.get(), "A-AC-10：第二个响应为空" + (err.get() != null ? "，异常=" + err.get() : ""));

        int c1 = r1.get().statusCode(), c2 = r2.get().statusCode();
        System.out.println("[A-AC-10] 两个并发提交的状态码 = " + c1 + " / " + c2);
        System.out.println("[A-AC-10] body1=" + r1.get().asString());
        System.out.println("[A-AC-10] body2=" + r2.get().asString());

        // 🚨 harness 守卫：被鉴权挡下时状态码不是业务码，「一个 200 一个非 200」会照样成立
        assertReachedBusinessLayer(r1.get(), "A-AC-10 并发-1");
        assertReachedBusinessLayer(r2.get(), "A-AC-10 并发-2");

        long ok = List.of(c1, c2).stream().filter(c -> c == 200).count();
        long conflict = List.of(c1, c2).stream().filter(c -> c == 409).count();
        assertEquals(1, ok, "A-AC-10：应恰好 1 个成功，实际 " + ok + " 个（状态码 " + c1 + "/" + c2 + "）");
        assertEquals(1, conflict,
                "A-AC-10：另一个必须返 409（唯一约束冲突要被翻译成业务冲突，"
                        + "返 500 说明约束异常没被接住），实际 " + conflict + " 个（状态码 " + c1 + "/" + c2 + "）");

        long rowsInDb = count("SELECT count(*) FROM ds_quote_customer_part WHERE customer_no='"
                + fx.customerNo() + "' AND customer_product_no='" + productNo + "'");
        System.out.println("[A-AC-10] ds_quote_customer_part 实际落库=" + rowsInDb + " 行");
        assertEquals(1, rowsInDb,
                "A-AC-10：uq_ds_quote_customer_part(customer_no, customer_product_no) 应保证只落 1 行，实际 "
                        + rowsInDb + " 行");
    }
}
