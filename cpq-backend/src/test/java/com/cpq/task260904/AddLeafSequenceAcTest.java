package com.cpq.task260904;

import com.cpq.configure.service.ConfigureSnapshotService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TC-11 —— <b>AC-11（序列 · 加叶子 → 保存 → 重开）</b>。
 *
 * <p>AC 原文（{@code 需求文档.md §3.2}）：
 * 「加一个材质叶子 → <b>保存草稿</b> → 关闭报价单 → 重新打开 → 查看该节点。
 *  断言：节点类型显示「材质」，与加叶子时一致；{@code snapshot_rows} 中该行 {@code __nodeType} <b>逐字不变</b>」。
 *
 * <h3>🚨 「保存草稿」必须走真实 {@code PUT /quotations/{id}/draft}，🚫 不能用 snapshotQuotation 直调</h3>
 * 2026-09-05 主线 A/B 归因（同一报价行、同一宿主、真实端点，本分支与干净 master 各跑两个动作）：
 * <pre>
 *   addLeaf → PUT /draft        → 手工叶子**存活**（本分支 = 干净 master）
 *   addLeaf → refresh-snapshot  → 手工叶子**消失**（本分支 = 干净 master）
 * </pre>
 * 根因：{@code snapshotQuotation} 的树页签 Pass-2 用 {@code BomTreeRenderService.render()} 结果
 * <b>整体覆盖</b> {@code snapshot_rows}，不与既有手工行合并；而生产 {@code saveDraft} 走
 * {@code preservedSnapshots} + {@code skipRowsWithSnapshot}，整行复用旧快照。
 * <b>两条路径本来就不同</b> —— 本用例上一版用 {@code snapshotQuotation} 当「保存草稿」的等价动作是<b>选错了</b>，
 * 那个红是夹具口径问题、不是被测缺陷。此处已按主线裁决改为真实 {@code PUT /draft}。
 *
 * <h3>🚨 baseVersion 必须紧贴 PUT 之前现读</h3>
 * 前面的 add-leaf 已经把 {@code quotation.user_data_version} 顶上去了；拿「打开时」的版本号提交会返
 * <b>409 {@code STALE_VERSION}</b>。🚫 <b>不要把那个 409 读成「手工叶子保不住」</b> —— 它是版本号取错了。
 *
 * <h3>🚨 saveDraft 是「全量重建」语义</h3>
 * 某 {@code componentId} 不在本次请求里就不会被保留/重建。⇒ 请求必须把<b>树页签也带上</b>
 * （{@code rowData} 传 {@code "[]"} 占位，真实值由 {@code preservedSnapshots} 回填），
 * 漏传的话手工叶子会因为「压根没提交这个页签」而消失，那同样是夹具问题冒充缺陷。
 *
 * <h3>为什么必须是序列用例</h3>
 * 判定来源从「页签命中」改成「读主数据」之后，风险是<b>保存/重开时把手工叶子的 {@code __nodeType} 冲掉</b>
 * （重算时那个料号并不在任何页签的渲染行里）。只验加叶子当场返回的 {@code nodeType} 抓不到这个 ——
 * 它在第一次调用里就返回了，保存之后才丢。
 */
@QuarkusTest
@DisplayName("task-260904 · AC-11 —— 加材质叶子 → 保存 → 重开，__nodeType 逐字不变")
class AddLeafSequenceAcTest extends Task260904Base {

    private static final ObjectMapper M = new ObjectMapper();

    @Inject
    ConfigureSnapshotService configureSnapshotService;

    @Test
    @DisplayName("AC-11：材质叶子的 __nodeType 在重新物化 + 重新打开后逐字不变")
    void ac11_nodeTypeSurvivesReopen() {
        TreeFx f = buildTreeFixture("A11");
        configureSnapshotService.snapshotQuotation(f.quotationId);
        assertSpineMaterialized(f);
        MasterData md = masterData();

        // ① 加材质叶子
        String nodeId = addLeafExpectType(f, f.hostNode(), md.recipePartNo(), "材质", "AC-11①");
        assertNotNull(nodeId, "AC-11①：应返回新节点 nodeId");

        String typeAfterAdd = nodeTypeOf(f, nodeId);
        assertEquals("材质", typeAfterAdd,
                "AC-11①：落库的 __nodeType 应与响应一致（响应说材质、库里却是别的 = 契约与落库不一致）");

        // ② 真实「保存草稿」：PUT /api/cpq/quotations/{id}/draft
        Response saved = saveDraft(f);
        assertReachedBusinessLayer(saved, "AC-11②（保存草稿）");
        assertEquals(200, saved.statusCode(),
                "AC-11②：保存草稿应成功，实际=" + saved.statusCode() + " body=" + saved.asString()
                        + (saved.statusCode() == 409
                        ? " ⇒ 409 是 baseVersion 取旧了（add-leaf 已把版本号顶上去），不是「手工叶子保不住」"
                        : ""));

        // ③ 真正走一次「重新打开报价单」的读接口，确认整个读路径没炸
        Response open = given().get(TREE_BASE + f.quotationId).thenReturn();
        assertReachedBusinessLayer(open, "AC-11③（重新打开报价单）");
        assertEquals(200, open.statusCode(),
                "AC-11③：重新打开报价单应 200，实际=" + open.statusCode() + " body=" + open.asString());

        // ④ 逐字比对
        String typeAfterReopen = nodeTypeOf(f, nodeId);
        assertEquals(typeAfterAdd, typeAfterReopen,
                "AC-11④：保存草稿 + 重新打开后，节点 " + nodeId + " 的 __nodeType 必须逐字不变。"
                        + "加叶子时=" + typeAfterAdd + "，重开后=" + typeAfterReopen
                        + " ⇒ 保存/重开把手工叶子的类型冲掉了（该料号不在任何页签的渲染行里，"
                        + "『按页签命中』的老判据在这里判不出类型）。");
        System.out.println("[AC-11] nodeId=" + nodeId + " __nodeType 加叶子时=" + typeAfterAdd
                + " 保存并重开后=" + typeAfterReopen + " ✅ 逐字一致");
    }

    /**
     * 真实保存草稿。
     * <p>请求体形状照既有的 {@code com.cpq.quotation.task260901.Task260901Support#draftBody / modifiedLine}
     * （{@code baseVersion} + {@code added/modified/removed}，{@code rowData} 是 JSON <b>字符串</b>）。
     * <p>🚨 {@code baseVersion} 紧贴 PUT 之前现读；万一仍撞 409（并发会话也在写这张单）再现读重试一次，
     * 两次都 409 才算失败 —— 并在失败信息里点明这是版本冲突而不是 AC 结论。
     */
    private Response saveDraft(TreeFx f) {
        Response first = putDraftOnce(f);
        if (first.statusCode() != 409) return first;
        System.out.println("[AC-11②] 首次保存 409（版本被顶）⇒ 现读版本号重试一次。body=" + first.asString());
        return putDraftOnce(f);
    }

    private Response putDraftOnce(TreeFx f) {
        String v = scalar("SELECT user_data_version FROM quotation WHERE id = '" + f.quotationId + "'");
        assertNotNull(v, "AC-11②：取不到 quotation.user_data_version ⇒ 提交必然 409，断言会被冲突掩盖");
        long baseVersion = Long.parseLong(v);
        // 🚨 树页签必须一并提交（saveDraft 全量重建语义），否则手工叶子会因「没提交这个页签」而消失
        String body = "{\"baseVersion\":" + baseVersion + ",\"added\":[],\"removed\":[],\"modified\":["
                + "{\"id\":\"" + f.lineItemId + "\",\"templateId\":\"" + f.templateId + "\",\"sortOrder\":0,"
                + "\"compositeType\":\"SIMPLE\",\"componentData\":["
                + "{\"componentId\":\"" + f.treeComponentId + "\",\"tabName\":\"BOM树\",\"rowData\":\"[]\",\"sortOrder\":0},"
                + "{\"componentId\":\"" + f.matComponentId + "\",\"tabName\":\"材质元素\",\"rowData\":\"[]\",\"sortOrder\":1}"
                + "]}]}";
        Response r = given().contentType(io.restassured.http.ContentType.JSON).body(body)
                .put(TREE_BASE + f.quotationId + "/draft").thenReturn();
        System.out.println("[AC-11②] PUT /draft baseVersion=" + baseVersion + " → " + r.statusCode());
        return r;
    }

    /** 从落库的 {@code snapshot_rows} 里取指定 {@code __nodeId} 的 {@code __nodeType}。 */
    private String nodeTypeOf(TreeFx f, String nodeId) {
        String json = readSnapshotRows(f.lineItemId, f.treeComponentId);
        assertNotNull(json, "取不到树组件的 snapshot_rows ⇒ 断言会空跑");
        try {
            JsonNode rows = M.readTree(json);
            assertTrue(rows.isArray() && rows.size() > 0,
                    "snapshot_rows 应是非空数组（空数组会让下面的查找恒返 null、断言空跑）。实际=" + json);
            for (JsonNode row : rows) {
                if (nodeId.equals(row.path("__nodeId").asText(null))) {
                    JsonNode t = row.path("__nodeType");
                    return t.isNull() || t.isMissingNode() ? null : t.asText();
                }
            }
            fail("节点 " + nodeId + " 已从 snapshot_rows 里消失 —— 手工叶子在重新物化后没保住。实际=" + json);
            return null;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("解析 snapshot_rows 失败: " + json, e);
        }
    }
}
