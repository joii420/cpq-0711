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
import java.util.UUID;

import static com.cpq.quotation.task260901.Task260901Support.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260901 · A. 增量协议 + F. 反向 AC —— T-2 / T-3 / T-4 / T-17 / T-20 / T-24
 *
 * <p>🚫 用例从 AC 原文与 {@code api.md} 契约派生，不读实现代码。
 *
 * <p>🔑 <b>为什么 AC-3 / AC-20 的权威判据在这里而不在 E2E</b>：dev 库 {@code cpq_db_0724} 的
 * {@code quotation_line_process} / {@code quotation_line_composite_process} /
 * {@code quotation_line_item_snapshot} <b>全库 0 行</b>（2026-09-01 只读 SQL 实测），
 * 在那上面断言「删除后为 0」「保存后条数一致」全是 {@code 0 == 0} 的空跑。
 * 本类的夹具刻意给行挂上工序与行快照，前置计数 &gt; 0，断言才有分辨力。
 */
@QuarkusTest
@TestProfile(Task260901RbacOffProfile.class)   // 见该类注释：不关 RBAC 则全包 401
@DisplayName("task-260901 增量协议（AC-2/3/4/17/20/24）")
class Task260901IncrementalProtocolHttpTest {

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
    // T-2 / AC-2（单点）
    // AC 原文：「added 长度为 1、该元素 id 为 null；modified 与 removed 为空。
    //           保存成功后该行在库中存在，且响应回传了它的新 id。」
    // 认领方式见 api.md §1.3：响应原样回传请求里的 tempId，前端按 tempId 认领新 id。
    // ══════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("T-2/AC-2：added 一行（id=null + tempId）→ 落库成功，响应按 tempId 回传新 id")
    void ac2_addedLineIsInsertedAndNewIdReturned() {
        Task260901HttpFixture f = fixture("ac2");
        long before = read(() -> lineCount(em, f.quotationId));
        assertEquals(2L, before, "前置：夹具应有 2 行");

        String tempId = "tmp-" + UUID.randomUUID();
        long v0 = read(() -> userDataVersion(em, f.quotationId));
        String body = draftBody(v0, null,
                "[" + addedLine(tempId, f.productBId, f.quoteTemplateId, 2, f.componentId,
                        Task260901HttpFixture.TAB_NAME, Task260901HttpFixture.ROW_DATA_B, "T260901-NEW") + "]",
                "[]", "[]");
        Response r = putDraft(f.quotationId, body);
        JsonNode data = ok(r, "AC-2 的 PUT /draft");

        assertEquals(before + 1, read(() -> lineCount(em, f.quotationId)),
                "AC-2：保存后库中应恰好多 1 行");

        JsonNode lines = data.path("lineItems");
        assertTrue(lines.isArray() && lines.size() >= 1,
                "AC-2：响应应回传新增的那一行。响应：" + r.asString());
        JsonNode added = null;
        for (JsonNode n : lines) {
            if (tempId.equals(n.path("tempId").asText(null))) { added = n; break; }
        }
        // 🚫 刻意不写「只有一行时就当作它」的兜底：那会让「tempId 根本没回传」也照样绿，
        //    而 tempId 正是新行认领 DB id 的唯一手段（api.md §1.3 收敛表）。
        assertNotNull(added,
                "AC-2 / api.md §1.3：响应必须原样回传 added 行的 tempId=" + tempId + " 供前端认领新 id。"
                        + "响应：" + r.asString());
        String newId = added.path("id").asText(null);
        assertNotNull(newId, "AC-2：响应回传的新行必须带非空 id。响应：" + r.asString());
        assertNotEquals("null", newId, "AC-2：新行 id 不能是字符串 \"null\"");

        long exists = read(() -> count(em,
                "SELECT count(*) FROM quotation_line_item WHERE id=?1 AND quotation_id=?2",
                UUID.fromString(newId), f.quotationId));
        assertEquals(1L, exists, "AC-2：响应回传的 id 必须真的在库里，且属于本单。id=" + newId);
    }

    // ══════════════════════════════════════════════════════════════════
    // T-3 / AC-3（单点）—— 🔑 **本方法是 AC-3 的权威判据**（用户 2026-09-01 裁决）
    // AC 原文（2026-09-01 修正版）：「removed 数组为该行 id 的单元素数组；added/modified 为空。
    //   保存后库中该行的 quotation_line_item 与 quotation_line_component_data 记录均已删除
    //   （各 0 行），且**未列入 removed 的其它行一行未少**。」
    //   原文里的另外两张表（quotation_line_process / quotation_line_item_snapshot）在 dev 库
    //   全库 0 行，E2E 层断言是 0==0 的空跑 —— 判据因此移到这里：本类夹具**主动为待删行插入**
    //   工序 ×2 与行快照 ×1，前置计数 >0，「删除后为 0」才真的有分辨力。
    // ══════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("T-3/AC-3：removed 一行 → 该行与其全部子表记录归零（前置计数均 >0），且未列入 removed 的行一条不少")
    void ac3_removedLineAndAllFourSubTablesGoToZero() {
        Task260901HttpFixture f = fixture("ac3");

        // 🚨 非空守卫：每张子表保存前都必须 >0，否则「删除后为 0」是 0==0 的空跑
        long liBefore   = read(() -> count(em, "SELECT count(*) FROM quotation_line_item WHERE id=?1", f.lineAId));
        long procBefore = read(() -> processCount(em, f.lineAId));
        long compBefore = read(() -> compositeProcessCount(em, f.lineAId));
        long cdBefore   = read(() -> componentDataCount(em, f.lineAId));
        long snapBefore = read(() -> lineSnapshotCount(em, f.lineAId));
        System.out.println("[T-3] 待删行前置计数 process=" + procBefore + " composite=" + compBefore
                + " componentData=" + cdBefore + " snapshot=" + snapBefore
                + "；使用的真实工序编号 = " + f.usedProcessNos);
        assertEquals(1L, liBefore, "前置：待删行必须存在");
        assertTrue(procBefore > 0, "前置：quotation_line_process 必须 >0，否则 AC-3 对该表的断言空跑（实际 " + procBefore + "）");
        assertTrue(compBefore > 0, "前置：quotation_line_composite_process 必须 >0（实际 " + compBefore + "）");
        assertTrue(cdBefore > 0, "前置：quotation_line_component_data 必须 >0（实际 " + cdBefore + "）");
        assertTrue(snapBefore > 0, "前置：quotation_line_item_snapshot 必须 >0（实际 " + snapBefore + "）");

        // 未列入 removed 的 B 行的子表基线 —— 反向判据的比较基准
        long bProcBefore = read(() -> processCount(em, f.lineBId));
        long bCompBefore = read(() -> compositeProcessCount(em, f.lineBId));
        long bCdBefore   = read(() -> componentDataCount(em, f.lineBId));
        long bSnapBefore = read(() -> lineSnapshotCount(em, f.lineBId));
        assertTrue(bProcBefore > 0 && bCompBefore > 0 && bCdBefore > 0 && bSnapBefore > 0,
                "前置：B 行的四类子表都必须 >0，否则「一条不少」的反向断言空跑");

        long v0 = read(() -> userDataVersion(em, f.quotationId));
        Response r = putDraft(f.quotationId,
                draftBody(v0, null, "[]", "[]", "[\"" + f.lineAId + "\"]"));
        ok(r, "AC-3 的 PUT /draft");

        assertEquals(0L, read(() -> count(em, "SELECT count(*) FROM quotation_line_item WHERE id=?1", f.lineAId)),
                "AC-3：quotation_line_item 应为 0 行");
        assertEquals(0L, read(() -> processCount(em, f.lineAId)),
                "AC-3：quotation_line_process 应为 0 行（前置 " + procBefore + " 行）");
        assertEquals(0L, read(() -> compositeProcessCount(em, f.lineAId)),
                "AC-3：quotation_line_composite_process 应为 0 行（前置 " + compBefore + " 行）");
        assertEquals(0L, read(() -> componentDataCount(em, f.lineAId)),
                "AC-3：quotation_line_component_data 应为 0 行（前置 " + cdBefore + " 行）");
        assertEquals(0L, read(() -> lineSnapshotCount(em, f.lineAId)),
                "AC-3：quotation_line_item_snapshot 应为 0 行（前置 " + snapBefore + " 行）");

        // ── 反向判据：没进 removed 的 B 行必须整行整子表都在 ──
        // 🔑 这一段才是本用例真正的分辨力所在。四张子表的 line_item_id 外键**全部是
        //    ON DELETE CASCADE**（实测 pg_constraint），所以「A 行删掉后它的子表归零」是
        //    schema 保证的、应用层写不写删除都成立；上面那几条只能证明「A 行确实被删了」。
        //    能被写坏的是删除的**范围** —— 原语义是「payload 里没出现的行 = 删」，
        //    增量协议下若没改干净，B 行会连同它的子表一起被 cascade 掉。
        assertEquals(1L, read(() -> count(em, "SELECT count(*) FROM quotation_line_item WHERE id=?1", f.lineBId)),
                "AC-3：未列入 removed 的 B 行不得被删 —— 显式删除语义的关键");
        assertEquals(bProcBefore, read(() -> processCount(em, f.lineBId)), "AC-3：B 行工序一条不少");
        assertEquals(bCompBefore, read(() -> compositeProcessCount(em, f.lineBId)), "AC-3：B 行组合工艺一条不少");
        assertEquals(bCdBefore, read(() -> componentDataCount(em, f.lineBId)), "AC-3：B 行 componentData 一条不少");
        assertEquals(bSnapBefore, read(() -> lineSnapshotCount(em, f.lineBId)), "AC-3：B 行行快照一条不少");
    }

    // ══════════════════════════════════════════════════════════════════
    // T-4 / AC-4（单点）
    // AC 原文：「三个数组全为空，projectName 字段为该值。保存后库中 project_name 等于该值，
    //           且 quotation_line_item 表零行被更新。」
    // ══════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("T-4/AC-4：三数组全空只改单头 → project_name 落库，且没有任何 line item 被 UPDATE")
    void ac4_headerOnlySaveDoesNotTouchAnyLineItem() {
        Task260901HttpFixture f = fixture("ac4");

        String xminA = read(() -> xminOfLineItem(em, f.lineAId));
        String xminB = read(() -> xminOfLineItem(em, f.lineBId));
        String cdXminA = read(() -> xminOfComponentData(em, f.cdAId));
        assertNotNull(xminA, "前置：应能读到 A 行 xmin");
        assertNotNull(xminB, "前置：应能读到 B 行 xmin");

        String stamp = "AC4-" + System.currentTimeMillis();
        long v0 = read(() -> userDataVersion(em, f.quotationId));
        ok(putDraft(f.quotationId,
                draftBody(v0, "\"projectName\":\"" + stamp + "\"", "[]", "[]", "[]")),
                "AC-4 的 PUT /draft");

        Object pn = read(() -> scalar(em, "SELECT project_name FROM quotation WHERE id=?1", f.quotationId));
        assertEquals(stamp, pn == null ? null : pn.toString(), "AC-4：库中 project_name 应等于新值");

        assertEquals(xminA, read(() -> xminOfLineItem(em, f.lineAId)),
                "AC-4：只改单头时 A 行不得被 UPDATE（xmin 变了）");
        assertEquals(xminB, read(() -> xminOfLineItem(em, f.lineBId)),
                "AC-4：只改单头时 B 行不得被 UPDATE（xmin 变了）");
        assertEquals(cdXminA, read(() -> xminOfComponentData(em, f.cdAId)),
                "AC-4：只改单头时 componentData 也不得被 UPDATE");
    }

    // ══════════════════════════════════════════════════════════════════
    // T-17 / AC-17（序列·关键）
    // AC 原文（2026-09-01 修正版）：「新增 1 个产品 → 保存（响应回传其新 id）→ 立即再修改这个新产品的
    //   一个格子 → 再次保存。第二次保存的 modified 数组中该行 id 为**第一次保存返回的 DB id**（不是 null），
    //   且**该 line_item id 在库中唯一存在**（SELECT count(*) FROM quotation_line_item WHERE id = ?
    //   恒为 1），未出现重复插入。」
    //   📌 原判据「该产品只有 1 行」在 dev 库不可判定：CUST-0004 的 1845 个映射料号已被基准单用满，
    //      空闲料号为 0，任何新增都必然与既有行同料号。
    // 本条防的是 ④ 的主要风险：回填从「按下标」改「按 id」若没改对，新行拿不到 id → 重复插入。
    // ══════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("T-17/AC-17：新增 → 保存拿 id → 以该 id 走 modified 再保存 → 不重复插入")
    void ac17_newRowClaimsDbIdAndSecondSaveDoesNotDuplicate() {
        Task260901HttpFixture f = fixture("ac17");
        long before = read(() -> lineCount(em, f.quotationId));

        // ① 新增
        String tempId = "tmp-" + UUID.randomUUID();
        long v0 = read(() -> userDataVersion(em, f.quotationId));
        Response r1 = putDraft(f.quotationId, draftBody(v0, null,
                "[" + addedLine(tempId, f.productBId, f.quoteTemplateId, 2, f.componentId,
                        Task260901HttpFixture.TAB_NAME, Task260901HttpFixture.ROW_DATA_B, "T260901-SEQ") + "]",
                "[]", "[]"));
        JsonNode d1 = ok(r1, "AC-17 第一次 PUT /draft");
        assertEquals(before + 1, read(() -> lineCount(em, f.quotationId)), "AC-17：第一次保存后应多 1 行");

        String newId = null;
        for (JsonNode n : d1.path("lineItems")) {
            if (tempId.equals(n.path("tempId").asText(null))) { newId = n.path("id").asText(null); break; }
        }
        // 🚫 不写「只有一行就当作它」的兜底 —— 那会让「tempId 没回传」也照样绿，
        //    而前端正是靠 tempId 认领新 id 的（api.md §1.3 收敛表）。
        assertNotNull(newId,
                "AC-17：第一次保存必须按 tempId=" + tempId + " 回传新行的 DB id"
                        + "（否则第二次保存只能再发 id=null → 重复插入）。响应：" + r1.asString());
        final UUID newUuid = UUID.fromString(newId);
        assertEquals(1L, read(() -> count(em, "SELECT count(*) FROM quotation_line_item WHERE id=?1", newUuid)),
                "AC-17：回传的 id 必须在库中存在");

        // ② 用第一次返回的 DB id 走 modified 再保存
        long v1 = read(() -> userDataVersion(em, f.quotationId));
        Response r2 = putDraft(f.quotationId, draftBody(v1, null, "[]",
                "[" + modifiedLine(newUuid, f.quoteTemplateId, 2, f.componentId,
                        Task260901HttpFixture.TAB_NAME, Task260901HttpFixture.ROW_DATA_A_LAST_DIGIT_DIFF) + "]",
                "[]"));
        ok(r2, "AC-17 第二次 PUT /draft");

        // 🔑 AC-17（2026-09-01 修正版）的核心判据：该 line_item id 在库中**唯一存在**
        assertEquals(1L, read(() -> count(em, "SELECT count(*) FROM quotation_line_item WHERE id=?1", newUuid)),
                "AC-17：该 line_item id (" + newUuid + ") 在库中必须唯一存在（count(*) WHERE id=? 恒为 1）");
        assertEquals(before + 1, read(() -> lineCount(em, f.quotationId)),
                "AC-17：第二次保存后行数应仍是 " + (before + 1) + "（变成 " + (before + 2) + " = 重复插入）");
        String rd = read(() -> {
            Object v = scalar(em,
                    "SELECT row_data::text FROM quotation_line_component_data WHERE line_item_id=?1 LIMIT 1", newUuid);
            return v == null ? null : v.toString();
        });
        assertNotNull(rd, "AC-17：新行的 componentData 必须存在");
        assertTrue(rd.contains("1000.00000000001"),
                "AC-17：第二次保存的改动必须落到**同一行**上，实际 row_data = " + rd);
    }

    // ══════════════════════════════════════════════════════════════════
    // T-20 / AC-20（反向）
    // AC 原文：「保存后，quotation_line_process（工序）、quotation_line_composite_process
    //           （选配-组合工艺）的记录数与内容和保存前一致（未改动的行不丢工序）。」
    // ══════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("T-20/AC-20：改 A 行后，A、B 两行的工序与组合工艺的条数和内容都不变（真实 payload）")
    void ac20_processRowsSurviveIncrementalSave() {
        Task260901HttpFixture f = fixture("ac20");
        ProcState before = procState(f);
        assertNonVacuous(before, f);

        // 🔑 用**真实 payload**：前端 buildDraftPayload 对 modified 行会同时带 processNos 与
        //    compositeProcesses（主线核实 QuotationWizard.tsx:1245/:1247）。早先只发 componentData
        //    是我的夹具不真实，据此判「工序丢失」是错的判断，已撤回。
        long v0 = read(() -> userDataVersion(em, f.quotationId));
        ok(putDraft(f.quotationId, draftBody(v0, null, "[]",
                "[" + modifiedLineWithProcesses(f.lineAId, f.quoteTemplateId, 0, f.componentId,
                        Task260901HttpFixture.TAB_NAME, Task260901HttpFixture.ROW_DATA_A_LAST_DIGIT_DIFF,
                        f.usedProcessNos, List.of("T260901-ASM-01", "T260901-ASM-02")) + "]",
                "[]")), "AC-20 的 PUT /draft");

        ProcState after = procState(f);
        System.out.println("[T-20][真实 payload] " + before.diff(after));
        assertProcessesIntact(before, after, "AC-20（真实 payload）");
    }

    /**
     * T-20 的**对照组**：同样改 A 行，但 payload 里**显式带上 processNos**。
     *
     * <p>与 {@link #ac20_processRowsSurviveIncrementalSave} 配对，用来把「工序没了」的根因二分：
     * <ul>
     *   <li>两条都红 ⇒ 无论带不带 processNos 都清空，是无条件删除；</li>
     *   <li>只有不带的那条红 ⇒ 后端把「payload 缺 processNos」当成了「用户清空了工序」，
     *       那么真正的问题在于**前端 diff payload 到底带不带这个字段**（带 = 安全，不带 = 每次保存都丢工序）。</li>
     * </ul>
     * 没有这条对照，报给主线的只有「工序没了」，落不到可执行的根因上。
     */
    /**
     * 🚨 <b>脆弱契约存档</b>（非 AC，主线 2026-09-01 要求登记）：
     * {@code modified} 行的 payload 是<b>整行权威</b>——缺席的字段视为「用户清空了它」。
     *
     * <p>本方法把这条语义钉成可执行的断言：payload 里不带 {@code processNos} /
     * {@code compositeProcesses}，该行的工序与组合工艺就<b>被清空</b>。
     *
     * <p><b>为什么它值得一条用例</b>：将来任何一条前端路径（新入口、重构、条件分支）
     * 忘了带这两个字段，用户改一个格子就会静默丢掉整行工序 —— 不报错、不变红、无告警。
     * 这条用例变红的那天，就是有人动了这个契约的那天。
     * 🚫 它<b>不是</b>产品缺陷的证据：真实 payload 两者都带（见 {@link #ac20_processRowsSurviveIncrementalSave}）。
     */
    @Test
    @DisplayName("T-20 语义存档：modified 行缺 processNos/compositeProcesses ⇒ 该行工序被清空（整行权威）")
    void ac20b_omittedProcessFieldsAreTreatedAsCleared() {
        Task260901HttpFixture f = fixture("ac20b");
        ProcState before = procState(f);
        assertNonVacuous(before, f);

        long v0 = read(() -> userDataVersion(em, f.quotationId));
        ok(putDraft(f.quotationId, draftBody(v0, null, "[]",
                "[" + modifiedLine(f.lineAId, f.quoteTemplateId, 0, f.componentId,
                        Task260901HttpFixture.TAB_NAME, Task260901HttpFixture.ROW_DATA_A_LAST_DIGIT_DIFF) + "]",
                "[]")), "语义存档用例的 PUT /draft");

        ProcState after = procState(f);
        System.out.println("[T-20][缺字段 → 整行权威] " + before.diff(after));

        assertEquals(0L, after.procA(), "整行权威：payload 缺 processNos ⇒ 该行工序被清空");
        assertEquals(0L, after.compA(), "整行权威：payload 缺 compositeProcesses ⇒ 该行组合工艺被清空");
        // 未出现在 payload 里的 B 行完全不受影响 —— 清空范围严格限于被提交的那一行
        assertEquals(before.procB(), after.procB(), "未提交的 B 行工序不得受影响");
        assertEquals(before.compB(), after.compB(), "未提交的 B 行组合工艺不得受影响");
    }

    /** A/B 两行的工序与组合工艺快照。 */
    private record ProcState(long procA, long procB, long compA, long compB,
                             String fpA, String fpB, String cfpA, String cfpB) {
        String diff(ProcState after) {
            return "process A " + procA + "→" + after.procA + "，process B " + procB + "→" + after.procB
                 + "；composite A " + compA + "→" + after.compA + "，composite B " + compB + "→" + after.compB;
        }
    }

    private ProcState procState(Task260901HttpFixture f) {
        return read(() -> new ProcState(
                processCount(em, f.lineAId), processCount(em, f.lineBId),
                compositeProcessCount(em, f.lineAId), compositeProcessCount(em, f.lineBId),
                processFingerprint(em, f.lineAId), processFingerprint(em, f.lineBId),
                compositeProcessFingerprint(em, f.lineAId), compositeProcessFingerprint(em, f.lineBId)));
    }

    /** 🚨 非空守卫 —— dev 库上这两张表原本全库 0 行，没有这条守卫本组用例就是 0==0。 */
    private void assertNonVacuous(ProcState s, Task260901HttpFixture f) {
        System.out.println("[T-20] 前置 process A/B = " + s.procA + "/" + s.procB
                + "，composite A/B = " + s.compA + "/" + s.compB + "；工序编号 = " + f.usedProcessNos);
        assertTrue(s.procA > 0, "前置：A 行工序必须 >0，否则 AC-20 空跑（实际 " + s.procA + "）");
        assertTrue(s.procB > 0, "前置：B 行工序必须 >0（实际 " + s.procB + "）");
        assertTrue(s.compA > 0, "前置：A 行组合工艺必须 >0（实际 " + s.compA + "）");
        assertTrue(s.compB > 0, "前置：B 行组合工艺必须 >0（实际 " + s.compB + "）");
        assertNotEquals("<empty>", s.fpA, "前置：A 行工序指纹不应为空");
        assertNotEquals("<empty>", s.cfpA, "前置：A 行组合工艺指纹不应为空");
    }

    private void assertProcessesIntact(ProcState before, ProcState after, String label) {
        // 未改动的 B 行先断言 —— AC-20 括号原文「未改动的行不丢工序」问的就是它
        assertEquals(before.procB, after.procB, label + "：未改动的 B 行不得丢工序");
        assertEquals(before.fpB, after.fpB, label + "：B 行工序内容指纹必须不变");
        assertEquals(before.compB, after.compB, label + "：未改动的 B 行不得丢组合工艺");
        assertEquals(before.cfpB, after.cfpB, label + "：B 行组合工艺内容指纹必须不变");
        // 被改的 A 行：本次 payload 改的是 componentData，工序不该受牵连
        assertEquals(before.procA, after.procA, label + "：被改的 A 行也不得丢工序");
        assertEquals(before.fpA, after.fpA, label + "：A 行工序内容指纹必须不变");
        assertEquals(before.compA, after.compA, label + "：被改的 A 行也不得丢组合工艺");
        assertEquals(before.cfpA, after.cfpA, label + "：A 行组合工艺内容指纹必须不变");
    }

    // ══════════════════════════════════════════════════════════════════
    // T-24 / AC-24（边界）
    // AC 原文：「0 行的空单点保存：不报错，added/modified/removed 全空或不发请求，页面无异常。」
    // 后端侧的可验证部分：三数组全空的请求必须 200 且不产生任何行。
    // ══════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("T-24/AC-24：0 行空单三数组全空保存 → 200 且仍是 0 行")
    void ac24_emptyQuotationSaveSucceeds() {
        Task260901HttpFixture f = fixture("ac24");

        // 把夹具清成 0 行（定点 DELETE，只命中本夹具自建的行）
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM quotation_line_process WHERE line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id=?1)")
                    .setParameter(1, f.quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_composite_process WHERE line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id=?1)")
                    .setParameter(1, f.quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_item_snapshot WHERE line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id=?1)")
                    .setParameter(1, f.quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id=?1)")
                    .setParameter(1, f.quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id=?1")
                    .setParameter(1, f.quotationId).executeUpdate();
        });
        assertEquals(0L, read(() -> lineCount(em, f.quotationId)), "前置：该单必须已是 0 行");

        long v0 = read(() -> userDataVersion(em, f.quotationId));
        Response r = putDraft(f.quotationId, draftBody(v0, null, "[]", "[]", "[]"));
        ok(r, "AC-24 的 PUT /draft");
        assertEquals(0L, read(() -> lineCount(em, f.quotationId)), "AC-24：空单保存后仍应是 0 行");
    }
}
