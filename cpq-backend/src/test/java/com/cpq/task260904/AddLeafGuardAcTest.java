package com.cpq.task260904;

import com.cpq.configure.service.ConfigureSnapshotService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC-06 / TC-07 / TC-08 —— 加叶子的三条准入护栏。
 *
 * <p>覆盖 <b>AC-6（存在性）· AC-7（环检测）· AC-8（反向：既有护栏不得失效）</b>。
 * 错误码契约见 {@code api.md §3.3 / §3.4}，校验顺序见 {@code api.md §3.5}。
 *
 * <h3>🚨 「只判 400」是本类最大的假绿风险</h3>
 * 加叶子端点有 <b>至少 6 个</b> 会返 400 的分支（组件不是树页签 / 宿主不存在 / 宿主是材质或外购件 /
 * 料号不在主数据 / 成环 / 成品）。只断言状态码的话，<b>任何一个别的 400 都能让用例变绿</b>。
 * ⇒ 本类每条用例都同时断言 <b>错误码或文案的特征串</b>，并在断言前用前置查询证明「别的分支不会先命中」。
 *
 * <h3>🚨 「不落任何行」必须真的比对行数</h3>
 * AC-6 / AC-7 都要求「不落任何行」。本类在调用前后各读一次 {@code snapshot_rows} 的数组长度，
 * 逐字比对 —— 只看返回 400 不能证明它没留半行。
 */
@QuarkusTest
@DisplayName("task-260904 · AC-6/7/8 —— 加叶子准入护栏")
class AddLeafGuardAcTest extends Task260904Base {

    @Inject
    ConfigureSnapshotService configureSnapshotService;

    private TreeFx arrange(String label) {
        TreeFx f = buildTreeFixture(label);
        configureSnapshotService.snapshotQuotation(f.quotationId);
        assertSpineMaterialized(f);
        return f;
    }

    /** 树页签 snapshot_rows 的行数（用于「不落任何行」的前后比对）。 */
    private int treeRowCount(TreeFx f) {
        Object v = em.createNativeQuery("SELECT COALESCE(jsonb_array_length(snapshot_rows),0) "
                        + "FROM quotation_line_component_data WHERE line_item_id = :lid AND component_id = :cid")
                .setParameter("lid", f.lineItemId).setParameter("cid", f.treeComponentId)
                .getResultList().stream().findFirst().orElse(null);
        assertNotNull(v, "前置：树组件的 component_data 行应存在（取不到就说明树没物化，后面的比对无意义）");
        return ((Number) v).intValue();
    }

    // ───────────────────────── AC-6 ─────────────────────────

    /**
     * <b>AC-6（主数据不存在则拒绝）</b> 原文：
     * 「料号填一个两表都不存在的值 ⇒ 返回 <b>400</b>，文案明确指出该料号不在物料表/材质表中。
     *  <b>不落任何行</b>（{@code snapshot_rows} 行数保存前后相同）」。
     * 错误码 {@code LEAF_PART_NOT_IN_MASTER}（api.md §3.3）。
     */
    @Test
    @DisplayName("AC-6：料号两表都不存在 → 400 LEAF_PART_NOT_IN_MASTER，且一行都不落")
    void ac6_partNotInMasterRejected() {
        TreeFx f = arrange("A6");
        MasterData md = masterData();

        // 前置：证明它真的两表都查不到（否则这条断言验的不是「不存在分支」）
        assertEquals(0L, count("SELECT count(*) FROM ds_quote_material WHERE material_no = '" + md.partAbsent() + "'"),
                "前置：AC-6 的料号不应在物料表");
        assertEquals(0L, count("SELECT count(*) FROM material_recipe WHERE code = '" + md.partAbsent() + "'"),
                "前置：AC-6 的料号不应在材质表");

        int before = treeRowCount(f);
        Response r = addLeaf(f, f.hostNode(), md.partAbsent());
        assertReachedBusinessLayer(r, "AC-6");

        assertEquals(400, r.statusCode(), "AC-6：应返回 400，实际=" + r.statusCode() + " body=" + r.asString());
        String body = r.asString();
        assertTrue("LEAF_PART_NOT_IN_MASTER".equals(errorCode(r)) || body.contains("LEAF_PART_NOT_IN_MASTER"),
                "AC-6：错误码应为 LEAF_PART_NOT_IN_MASTER（api.md §3.3）—— 🚨 只判 400 的话，"
                        + "「宿主不存在」等别的 400 分支也能让本用例变绿。实际 code=" + errorCode(r) + " body=" + body);
        assertTrue(body.contains(md.partAbsent()),
                "AC-6：文案必须点名该料号 " + md.partAbsent() + "，实际 body=" + body);

        int after = treeRowCount(f);
        assertEquals(before, after, "AC-6：被拒后 snapshot_rows 行数必须不变（不落任何行），before=" + before + " after=" + after);
    }

    // ───────────────────────── AC-7 ─────────────────────────

    /**
     * <b>AC-7（成环则拒绝）</b> 原文：
     * 「前置：树上存在路径 A → B。以 B 为宿主，加叶子料号填 A ⇒ 返回 <b>400</b>，
     *  文案指出会形成环<b>并给出环路径</b>。<b>不落任何行</b>。同法验自环（宿主 = A，料号 = A）同样 400」。
     * 错误码 {@code LEAF_CYCLE_DETECTED}（api.md §3.3）。
     *
     * <p>本 fixture 的 A = {@code p1}（节点 {@code root/p1}），B = {@code p2}（节点 {@code root/p1/p2}）。
     */
    @Test
    @DisplayName("AC-7：真环 + 自环都 → 400 LEAF_CYCLE_DETECTED 且响应给出环路径，一行都不落")
    void ac7_cycleRejected() {
        TreeFx f = arrange("A7");

        // 前置：p1 必须在主数据里 —— 否则会先撞 AC-6 的存在性校验（api.md §3.5：⑤ 早于 ⑥），
        // 那样 400 是「不存在」而不是「成环」，这条 AC 就被冒充通过了。
        assertEquals(1L, count("SELECT count(*) FROM ds_quote_material WHERE material_no = '" + f.p1 + "'"),
                "前置：环检测用的料号必须在主数据里，否则会先被存在性校验拦掉");

        int before = treeRowCount(f);

        // ① 真环：以 root/p1/p2 为宿主，挂 p1 —— p1 是宿主的祖先
        Response cyc = addLeaf(f, f.deepNode(), f.p1);
        assertReachedBusinessLayer(cyc, "AC-7①");
        assertEquals(400, cyc.statusCode(), "AC-7①：真环应被拒 400，实际=" + cyc.statusCode() + " body=" + cyc.asString());
        assertTrue("LEAF_CYCLE_DETECTED".equals(errorCode(cyc)) || cyc.asString().contains("LEAF_CYCLE_DETECTED"),
                "AC-7①：错误码应为 LEAF_CYCLE_DETECTED，实际 code=" + errorCode(cyc) + " body=" + cyc.asString());
        assertTrue(cyc.asString().contains(f.p1) && cyc.asString().contains(f.p2),
                "AC-7①：文案必须给出环路径（形如 " + f.p1 + " → " + f.p2 + " → " + f.p1 + "）—— "
                        + "🚨 只说『会成环』不满足 AC 原文，也无法与别的 400 区分。实际 body=" + cyc.asString());

        // ② 自环：宿主 = root/p1，料号 = p1
        Response self = addLeaf(f, f.hostNode(), f.p1);
        assertReachedBusinessLayer(self, "AC-7②");
        assertEquals(400, self.statusCode(), "AC-7②：自环应被拒 400，实际=" + self.statusCode() + " body=" + self.asString());
        assertTrue("LEAF_CYCLE_DETECTED".equals(errorCode(self)) || self.asString().contains("LEAF_CYCLE_DETECTED"),
                "AC-7②：自环的错误码同样应为 LEAF_CYCLE_DETECTED，实际 code=" + errorCode(self) + " body=" + self.asString());

        assertEquals(before, treeRowCount(f), "AC-7：两次被拒后 snapshot_rows 行数必须不变（不落任何行）");
    }

    // ───────────────────────── AC-8 ─────────────────────────

    /**
     * <b>AC-8（反向 · 既有护栏不得失效）</b> 原文：
     * 「宿主节点是材质节点 ⇒ 加叶子仍返回 400『材质节点不可再添加下级』。同法验外购件宿主」。
     *
     * <p>做法：先各加一个材质叶子 / 外购件叶子（这一步本身也顺带证明 AC-4 / AC-5 的正向路径通），
     * 再以它们为宿主加下级。
     * 🚨 <b>第一步必须先断言成功</b> —— 第一步若失败，后面「以它为宿主」的 400 其实是
     * 「宿主不存在」，那是拿另一个 400 冒充结论。
     */
    @Test
    @DisplayName("AC-8：材质宿主 / 外购件宿主加下级 → 仍 400（既有护栏不得失效）")
    void ac8_materialAndOutsourcedHostStillRejected() {
        TreeFx f = arrange("A8");
        MasterData md = masterData();

        // ── 材质宿主 ──
        String matNodeId = addLeafExpectType(f, f.hostNode(), md.recipePartNo(), "材质", "AC-8 前置①");
        assertNotNull(matNodeId, "AC-8 前置①：材质叶子应加成功并返回 nodeId（不成功则后面的 400 是『宿主不存在』）");
        Response underMaterial = addLeaf(f, matNodeId, md.partNullType());
        assertReachedBusinessLayer(underMaterial, "AC-8①");
        assertEquals(400, underMaterial.statusCode(),
                "AC-8①：材质节点不可再添加下级，实际=" + underMaterial.statusCode() + " body=" + underMaterial.asString());
        assertTrue(underMaterial.asString().contains("材质"),
                "AC-8①：文案应点名『材质』，🚨 只判 400 会被别的分支冒充。实际 body=" + underMaterial.asString());

        // ── 外购件宿主 ──
        String outNodeId = addLeafExpectType(f, f.hostNode(), md.partOutsourced(), "外购件", "AC-8 前置②");
        assertNotNull(outNodeId, "AC-8 前置②：外购件叶子应加成功并返回 nodeId");
        Response underOutsourced = addLeaf(f, outNodeId, md.partNullType());
        assertReachedBusinessLayer(underOutsourced, "AC-8②");
        assertEquals(400, underOutsourced.statusCode(),
                "AC-8②：外购件节点不可再添加下级，实际=" + underOutsourced.statusCode() + " body=" + underOutsourced.asString());
        assertTrue(underOutsourced.asString().contains("外购件"),
                "AC-8②：文案应点名『外购件』。实际 body=" + underOutsourced.asString());
    }
}
