package com.cpq.quotation.task260901;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.cpq.quotation.task260901.Task260901Support.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260901 · C. 版本指纹（需求文档 §③ C / api.md §4）—— T-11 / T-12 / T-13 / T-14
 *
 * <p>🚫 用例从 AC 原文与 {@code api.md} 契约派生，不读实现代码。
 *
 * <p>🔑 <b>T-13 是全套里最容易被漏掉的一条</b>（test.md §2）：若「后端自算的派生数据」也递增版本号，
 * 用户什么都不做也会被反复要求刷新，且这个 bug 在单人测试时会被误认成「偶发」。
 * 本类给它配了非空守卫 —— 先证明 {@code ensure-card-values} <b>真的干了活</b>
 * （某行卡片值由 NULL 变非 NULL），再断言版本号没动；否则「版本号没变」可能只是因为它什么都没做。
 */
@QuarkusTest
@TestProfile(Task260901RbacOffProfile.class)   // 见该类注释：不关 RBAC 则全包 401
@DisplayName("task-260901 版本指纹（AC-11/12/13/14）")
class Task260901VersionFingerprintHttpTest {

    @Inject
    EntityManager em;

    private final List<Task260901HttpFixture> fixtures = new ArrayList<>();

    @AfterEach
    void cleanup() {
        fixtures.forEach(Task260901HttpFixture::cleanup);
        fixtures.clear();
    }

    private Task260901HttpFixture fixture(String label) {
        Task260901HttpFixture f = Task260901HttpFixture.create(em, label);
        fixtures.add(f);
        return f;
    }

    private <T> T read(java.util.function.Supplier<T> s) {
        return QuarkusTransaction.requiringNew().call(() -> { em.clear(); return s.get(); });
    }

    // ══════════════════════════════════════════════════════════════════
    // T-11 / AC-11（单点；2026-09-01 基准点已改）
    // AC 原文：「**以「发出保存请求前一刻」的版本号为基准 N**（不是「打开单据时」的版本号）。
    //           保存一次成功后，响应里的 userDataVersion 为 N+1，库中 quotation.user_data_version
    //           也为 N+1。」
    //
    // 📌 为什么基准点是「保存前一刻」：用户改完格子失焦会先触发 quote-card-edit，而 AC-14 要求
    //    该端点也 +1。若以「打开时」为基准，保存后实为 N+2，AC-11 与 AC-14 自相矛盾。
    //    本方法因此在**紧贴 PUT 之前**再读一次库版本作为 N，并顺带验证 api.md §3
    //    「GET 响应根部新增 userDataVersion」这条契约。
    // ══════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("T-11/AC-11：以「保存请求前一刻」的版本为 N → 保存成功 → 响应与库均为 N+1")
    void ac11_saveIncrementsUserDataVersionByOne() {
        Task260901HttpFixture f = fixture("ac11");

        // ① api.md §3 的契约：GET 响应根部必须带 userDataVersion，且与库一致
        JsonNode opened = ok(getQuotation(f.quotationId), "AC-11 的 GET /quotations/{id}");
        assertTrue(opened.has("userDataVersion"),
                "api.md §3：GET /quotations/{id} 响应根部必须新增 userDataVersion。响应字段缺失");
        long openedVersion = opened.path("userDataVersion").asLong(-1);
        assertTrue(openedVersion >= 0, "AC-11：userDataVersion 应为非负整数，实际 " + openedVersion);
        assertEquals(openedVersion, read(() -> userDataVersion(em, f.quotationId)),
                "AC-11：GET 返回的版本号应与库一致");

        // ② AC-11 的基准 N = **发出保存请求前一刻**的库版本
        long n = read(() -> userDataVersion(em, f.quotationId));
        System.out.println("[T-11] 打开时版本=" + openedVersion + "，保存前一刻 N=" + n);

        Response r = putDraft(f.quotationId, draftBody(n, null, "[]",
                "[" + modifiedLine(f.lineAId, f.quoteTemplateId, 0, f.componentId,
                        Task260901HttpFixture.TAB_NAME, Task260901HttpFixture.ROW_DATA_A_LAST_DIGIT_DIFF) + "]",
                "[]"));
        JsonNode data = ok(r, "AC-11 的 PUT /draft");

        assertTrue(data.has("userDataVersion"),
                "api.md §1.3：PUT /draft 响应必须回传 userDataVersion。响应：" + r.asString());
        assertEquals(n + 1, data.path("userDataVersion").asLong(-1),
                "AC-11：保存成功后响应版本号应为「保存前一刻」N+1（N=" + n + "）。响应：" + r.asString());
        assertEquals(n + 1, read(() -> userDataVersion(em, f.quotationId)),
                "AC-11：库中 user_data_version 应为 N+1");
    }

    // ══════════════════════════════════════════════════════════════════
    // T-12 / AC-12（边界·并发）—— API 侧
    // AC 原文：「A 保存成功（版本→N+1）。B 随后保存，收到 HTTP 409，响应 reason 为 STALE_VERSION」
    // （弹窗形态部分在 E2E task260901-version-conflict.spec.ts，对照 原型图/冲突提示.html）
    // ══════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("T-12/AC-12：陈旧 baseVersion 的保存返回 409 + reason=STALE_VERSION，且不落库")
    void ac12_staleBaseVersionIsRejectedWith409() {
        Task260901HttpFixture f = fixture("ac12");
        long n = read(() -> userDataVersion(em, f.quotationId));

        // ── A 保存成功（版本 → N+1）──
        JsonNode a = ok(putDraft(f.quotationId, draftBody(n, "\"projectName\":\"AC12-A\"", "[]", "[]", "[]")),
                "AC-12 会话 A 的 PUT /draft");
        long nAfterA = read(() -> userDataVersion(em, f.quotationId));
        // 🚨 分辨力守卫：A 必须真的推进了版本号，否则后面的 409 无从谈起
        assertEquals(n + 1, nAfterA, "AC-12 前置：会话 A 的保存必须让版本号 +1，否则冲突根本构造不出来");
        assertEquals(nAfterA, a.path("userDataVersion").asLong(-1), "AC-12 前置：A 的响应版本号应与库一致");

        String rowDataBefore = read(() -> rowDataText(em, f.cdAId));

        // ── B 仍持有 N，保存 → 必须 409 ──
        Response b = putDraft(f.quotationId, draftBody(n, null, "[]",
                "[" + modifiedLine(f.lineAId, f.quoteTemplateId, 0, f.componentId,
                        Task260901HttpFixture.TAB_NAME, Task260901HttpFixture.ROW_DATA_A_LAST_DIGIT_DIFF) + "]",
                "[]"));
        assertEquals(409, b.statusCode(),
                "AC-12：陈旧 baseVersion 的保存必须返回 409，实际 " + b.statusCode() + "，响应：" + b.asString());
        JsonNode body = json(b);
        assertEquals("STALE_VERSION", body.path("data").path("reason").asText(null),
                "AC-12 / api.md §1.4：409 响应 data.reason 必须是 STALE_VERSION。响应：" + b.asString());
        assertTrue(body.path("message").asText("").contains("这张报价单已被他人修改"),
                "api.md §1.4：409 message 应为「这张报价单已被他人修改」。响应：" + b.asString());
        assertEquals(nAfterA, body.path("data").path("currentVersion").asLong(-1),
                "api.md §1.4：409 响应应带 currentVersion（供前端提示）。响应：" + b.asString());

        // ── 被拒的保存必须真的没落库（否则 409 只是个提示，数据照样被覆盖）──
        assertEquals(rowDataBefore, read(() -> rowDataText(em, f.cdAId)),
                "AC-12：409 的保存不得写库。row_data 变了 = 并发覆盖仍在发生");
        assertEquals(nAfterA, read(() -> userDataVersion(em, f.quotationId)),
                "AC-12：被拒的保存不得递增版本号");
    }

    // ══════════════════════════════════════════════════════════════════
    // T-13 / AC-13（反向·关键）
    // AC 原文：「打开报价单后不做任何编辑，等待后台 ensure-card-values 跑完。此时库中 user_data_version
    //           保持不变。随后正常编辑并保存，不得出现 409。」
    // 📌 「跑完」在这里用**可留存的 DB 判据**取证（与 AC-8 同口径）：先把某行卡片值置 NULL，
    //    调用后断言它变回非 NULL —— 而不是去 grep 共享 dev server 的终端日志。
    // 本条防的是最大的设计陷阱：若版本号覆盖后端自算的派生数据，用户什么都没做也会被反复要求刷新。
    // ══════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("T-13/AC-13：ensure-card-values 真的补算了行，但 user_data_version 不变，随后保存不 409")
    void ac13_derivedWritesDoNotBumpUserDataVersion() {
        Task260901HttpFixture f = fixture("ac13");

        // 造出「确实有活要干」的前置：把 A 行卡片值置 NULL（这正是 ensureCardValues 的 IS NULL 谓词入口）
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE quotation_line_item SET quote_card_values=NULL, costing_card_values=NULL WHERE id=?1")
                        .setParameter(1, f.lineAId).executeUpdate());
        assertNull(read(() -> cardValues(em, f.lineAId, "quote_card_values")), "前置：A 行卡片值应已置 NULL");

        long n = read(() -> userDataVersion(em, f.quotationId));

        Response ensure = postEmpty(f.quotationId, "ensure-card-values");
        assertEquals(200, ensure.statusCode(),
                "AC-13：ensure-card-values 应返回 200，实际 " + ensure.statusCode() + "，响应：" + ensure.asString());

        // 🚨 非空守卫：必须先证明它**真的干了活**，否则「版本号没变」可能只是因为它什么都没做
        String afterCv = read(() -> cardValues(em, f.lineAId, "quote_card_values"));
        assertNotNull(afterCv,
                "AC-13 前置：ensure-card-values 必须真的把 A 行卡片值补回来了，否则本用例没有分辨力" +
                "（「什么都没做」当然不会改版本号）");

        assertEquals(n, read(() -> userDataVersion(em, f.quotationId)),
                "🚨 AC-13 / api.md §4.2：ensureCardValues 写的是系统自算的派生数据，**绝不能**递增 user_data_version。" +
                "递增会形成「保存 → 重算 → 必冲突 → 刷新 → 保存」的死循环。");

        // ── api.md §4.2 还列了 ensure-excel-values / snapshotQuotation 等同类写入方 ──
        //    AC-13 原文只点名 ensure-card-values，这一段是**契约级补充**，同型不变量。
        Response excel = postEmpty(f.quotationId, "ensure-excel-values");
        if (excel.statusCode() == 200) {
            assertEquals(n, read(() -> userDataVersion(em, f.quotationId)),
                    "api.md §4.2：ensureExcelValues 同样不得递增 user_data_version");
        } else {
            System.out.println("[T-13] ensure-excel-values 返回 " + excel.statusCode() + " —— 该端点的同型断言本轮未验证");
        }

        // ── 随后正常编辑并保存，必须 200（不 409）──
        Response save = putDraft(f.quotationId, draftBody(n, null, "[]",
                "[" + modifiedLine(f.lineAId, f.quoteTemplateId, 0, f.componentId,
                        Task260901HttpFixture.TAB_NAME, Task260901HttpFixture.ROW_DATA_A_LAST_DIGIT_DIFF) + "]",
                "[]"));
        assertEquals(200, save.statusCode(),
                "AC-13：后台重算之后，用户拿着打开时的版本号 " + n + " 保存必须成功，不得 409。响应：" + save.asString());
    }

    // ══════════════════════════════════════════════════════════════════
    // T-14 / AC-14（反向）
    // AC 原文：「通过 PUT /line-items/{id}/quote-card-edit 改一个单元格后，响应中带回新的 userDataVersion，
    //           前端更新本地版本号；随后点『保存草稿』不出现 409。」
    // ══════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("T-14/AC-14：quote-card-edit 递增版本号并回传，用回传值保存不 409；用旧值保存必须 409")
    void ac14_quoteCardEditReturnsNewVersion() {
        Task260901HttpFixture f = fixture("ac14");

        // 先让卡片值就位（quote-card-edit 作用于已渲染的卡片值）
        postEmpty(f.quotationId, "ensure-card-values");

        long n = read(() -> userDataVersion(em, f.quotationId));
        String editBody = "{\"componentId\":\"" + f.componentId + "\",\"rowKey\":\"AgNi11#-Ⅰ\"," +
                "\"fieldName\":\"材料净重\",\"value\":\"1234\"}";
        Response edit = putQuoteCardEdit(f.lineAId, editBody);
        assertEquals(200, edit.statusCode(),
                "AC-14 前置：quote-card-edit 应返回 200，实际 " + edit.statusCode() + "，响应：" + edit.asString());
        JsonNode data = json(edit).path("data");
        assertTrue(data.has("userDataVersion"),
                "AC-14 / api.md §2：quote-card-edit 响应必须新增 userDataVersion 字段。响应：" + edit.asString());
        long returned = data.path("userDataVersion").asLong(-1);
        long inDb = read(() -> userDataVersion(em, f.quotationId));
        assertEquals(inDb, returned, "AC-14：回传的版本号必须与库一致");
        assertEquals(n + 1, inDb,
                "AC-14 / api.md §4.1：quote-card-edit 写的是用户数据（row_data），必须递增版本号（" + n + " → 期望 " + (n + 1) + "）");

        // ── 用回传的新版本号保存：必须 200 ──
        Response okSave = putDraft(f.quotationId, draftBody(returned, "\"projectName\":\"AC14\"", "[]", "[]", "[]"));
        assertEquals(200, okSave.statusCode(),
                "AC-14：拿 quote-card-edit 回传的版本号保存必须成功。响应：" + okSave.asString());

        // ── 对照组：用编辑之前的旧版本号保存必须 409。
        //    没有这条，「不出现 409」可能只是因为**根本没有版本校验** —— 那 AC-12 也就白做了。
        Response staleSave = putDraft(f.quotationId, draftBody(n, "\"projectName\":\"AC14-stale\"", "[]", "[]", "[]"));
        assertEquals(409, staleSave.statusCode(),
                "AC-14 对照组：拿编辑之前的旧版本号 " + n + " 保存必须 409（否则说明版本校验根本没生效，" +
                "本用例的『不 409』毫无分辨力）。响应：" + staleSave.asString());
    }
}
