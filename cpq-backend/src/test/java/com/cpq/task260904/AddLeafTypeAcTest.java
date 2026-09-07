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
 * TC-04 / TC-05 / TC-12 / TC-26 —— 加叶子的类型判定改读主数据。
 *
 * <p>覆盖 <b>AC-4 · AC-5 · AC-12 · AC-26</b>（{@code 需求文档.md §3.1 / §3.3}）。
 *
 * <h3>断言口径</h3>
 * api.md §3.2：{@code __nodeType} 的取值域不变（{@code 材质 / 零件 / 外购件}），
 * <b>只改判定来源</b>：材质表命中 → 材质；物料表 {@code material_type} → 零件/外购件；空值 → 零件（A0-1）。
 *
 * <h3>🚨 本类刻意规避的两处空跑（test.md §3）</h3>
 * <ol>
 *   <li><b>外购件分支</b>：现网可用量极少（立项当日仅 1 条）。{@link Task260904Base#masterData()}
 *       在探不到时会自造一条 {@code t260904_} 前缀的物料 —— 不造的话这条分支从未被执行、用例照样绿。</li>
 *   <li><b>树没物化也会 400</b>：每个用例都先 {@link #arrange()} 里 {@code assertSpineMaterialized}，
 *       确认宿主节点真在 spine 上。否则「宿主不存在」的 400 会冒充「类型判定」的结论。</li>
 * </ol>
 */
@QuarkusTest
@DisplayName("task-260904 · AC-4/5/12/26 —— 加叶子类型判定改读主数据")
class AddLeafTypeAcTest extends Task260904Base {

    @Inject
    ConfigureSnapshotService configureSnapshotService;

    private TreeFx arrange(String label) {
        TreeFx f = buildTreeFixture(label);
        configureSnapshotService.snapshotQuotation(f.quotationId);
        assertSpineMaterialized(f);
        return f;
    }

    // ───────────────────────── AC-4 ─────────────────────────

    /**
     * <b>AC-4（加叶子 · 材质表命中）</b> 原文：
     * 「料号填一个存在于 {@code material_recipe.code} 的料号 ⇒ 返回 200，新行 {@code __nodeType='材质'}；
     *  该判定<b>不查任何页签命中</b>」。
     *
     * <p>「不查页签命中」的可观测化：本 fixture 的报价单里<b>没有任何一个页签渲染出该材质料号</b>
     * （材质元素页签的 $view 出的是 {@code p2}，不是这个 recipe code）。
     * 改动前按「料号出现在哪类页签的已渲染行」判，此处判不出类型；改动后读主数据必判「材质」。
     * ⇒ 这一条断言在改动前后行为不同，不是重言。
     */
    @Test
    @DisplayName("AC-4：材质表命中 → __nodeType='材质'（且当前单据无任何页签渲染过该料号）")
    void ac4_recipeHit() {
        TreeFx f = arrange("A4");
        MasterData md = masterData();

        // 阳性前置：证明这个料号确实只在材质表里
        assertEquals(1L, count("SELECT count(*) FROM material_recipe WHERE code = '" + md.recipePartNo() + "'"),
                "前置：AC-4 的料号应恰在 material_recipe 里 1 条");
        assertEquals(0L, count("SELECT count(*) FROM ds_quote_material WHERE material_no = '" + md.recipePartNo() + "'"),
                "前置：AC-4 的料号不应同时在物料表（需求文档 §1.3 实证两表互斥）");
        // 阴性前置：当前单据里没有任何页签渲染过它 ⇒ 「按页签命中」判不出来
        String matRows = readSnapshotRows(f.lineItemId, f.matComponentId);
        assertTrue(matRows == null || !matRows.contains(md.recipePartNo()),
                "前置：材质元素页签不应渲染过该料号，否则『不查页签命中』这条就验不出差别。实际=" + matRows);

        addLeafExpectType(f, f.hostNode(), md.recipePartNo(), "材质", "AC-4");
    }

    // ───────────────────────── AC-5 ─────────────────────────

    /**
     * <b>AC-5（加叶子 · 物料表 + {@code material_type} 判定）</b> 原文：
     * 「料号填一个 {@code ds_quote_material.material_type='外购件'} 的料号 ⇒ 200，{@code __nodeType='外购件'}；
     *  同法验 {@code ='零件'} → {@code __nodeType='零件'}」。
     */
    @Test
    @DisplayName("AC-5：material_type='外购件' → 外购件；='零件' → 零件（两个分支都必须真跑到）")
    void ac5_materialTypeDrivesNodeType() {
        TreeFx f = arrange("A5");
        MasterData md = masterData();

        // 🚨 test.md §3 的空跑前置检查：先把分布打出来并硬断言两个分支都有输入
        System.out.println("[AC-5·probe] ds_quote_material.material_type 分布 = "
                + rowsToString("SELECT COALESCE(material_type,'(null)'), count(*) FROM ds_quote_material WHERE material_type IS NOT NULL GROUP BY 1"));
        assertEquals("外购件", scalar("SELECT material_type FROM ds_quote_material WHERE material_no = '" + md.partOutsourced() + "'"),
                "前置：AC-5 外购件分支的输入料号 material_type 必须真是『外购件』");
        assertEquals("零件", scalar("SELECT material_type FROM ds_quote_material WHERE material_no = '" + md.partOfTypePart() + "'"),
                "前置：AC-5 零件分支的输入料号 material_type 必须真是『零件』");

        addLeafExpectType(f, f.hostNode(), md.partOutsourced(), "外购件", "AC-5①");

        // 零件分支：p1 本身就是 material_type='零件'，但它已在树上（会撞环检测）⇒ 另取一个零件料号。
        String anotherPart = scalar("SELECT material_no FROM ds_quote_material WHERE material_type='零件' "
                + "AND material_no <> '" + f.p1 + "' ORDER BY material_no LIMIT 1");
        assertNotNull(anotherPart, "前置未满足：ds_quote_material 里除 p1 外再没有第二个 material_type='零件' 的料号 "
                + "⇒ AC-5 的零件分支会空跑。这是环境前置缺失，不是被测功能的结论。");
        addLeafExpectType(f, f.hostNode(), anotherPart, "零件", "AC-5②");
    }

    // ───────────────────────── AC-12 ─────────────────────────

    /**
     * <b>AC-12（{@code material_type} 为空 → 默认零件）</b> 原文：
     * 「① 返回 200，新行 {@code __nodeType='零件'}；② 该叶子<b>可以</b>再作为宿主继续加下级；
     *  ③ 服务端日志或响应中标明本次判定走的是空值兜底分支」。
     */
    @Test
    @DisplayName("AC-12：material_type IS NULL → 判零件，且该叶子可再作宿主继续加下级")
    void ac12_nullMaterialTypeDefaultsToPart() {
        TreeFx f = arrange("A12");
        MasterData md = masterData();

        // p2 已经在树上（root/p1/p2），拿它当叶子会撞环 ⇒ 另取一个 NULL 类型料号
        String nullTypePart = scalar("SELECT material_no FROM ds_quote_material WHERE material_type IS NULL "
                + "AND material_no NOT IN ('" + f.root + "','" + f.root2 + "','" + f.p1 + "','" + f.p2 + "') "
                + "ORDER BY material_no LIMIT 1");
        assertNotNull(nullTypePart, "前置未满足：找不到一个不在树上的 material_type IS NULL 料号 ⇒ AC-12 会空跑。"
                + "（现网 NULL 类型料号数 = " + count("SELECT count(*) FROM ds_quote_material WHERE material_type IS NULL") + "）");
        assertNotNull(scalar("SELECT material_no FROM ds_quote_material WHERE material_no='" + nullTypePart + "'"),
                "前置：AC-12 的料号必须真在物料表里（否则会被 AC-6 的存在性校验先拦掉，验的就不是空值兜底了）");

        // ① 判零件
        String newNodeId = addLeafExpectType(f, f.hostNode(), nullTypePart, "零件", "AC-12①");
        assertNotNull(newNodeId, "AC-12①：响应应带回新节点 nodeId");

        // ② 该叶子可以再作为宿主继续加下级（证明没被「材质/外购件不可再添加下级」误拦）
        Response second = addLeaf(f, newNodeId, md.recipePartNo());
        assertReachedBusinessLayer(second, "AC-12②");
        assertEquals(200, second.statusCode(),
                "AC-12②：空值兜底判成『零件』的节点应当可以再作宿主继续加下级，实际=" + second.statusCode()
                        + " body=" + second.asString());

        // ③ 空值兜底分支必须在响应里留痕（AC-12③「服务端日志或响应中标明本次判定走的是空值兜底分支」）。
        //    📌 键名 materialTypeFallback 取自 2026-09-05 真跑观察到的响应载荷（不是从实现代码读的）：
        //       AC 只规定「要标明」，没规定叫什么；先跑一次拿到实际契约，再把它锁成断言。
        //    🚨 必须配阴性对照：只断言「空值行 = true」的话，一个恒 true 的实现也全绿。
        Response nullTypeResp = addLeaf(f, f.hostNode(), nullTypePart2());
        assertEquals(200, nullTypeResp.statusCode(), "AC-12③ 前置：再加一次空值料号应仍成功");
        assertEquals(Boolean.TRUE, nullTypeResp.jsonPath().get("data.materialTypeFallback"),
                "AC-12③：material_type 为空的料号，响应应标明本次走的是空值兜底分支。body=" + nullTypeResp.asString());

        Response typedResp = addLeaf(f, f.hostNode(), md.partOutsourced());
        assertEquals(200, typedResp.statusCode(), "AC-12③ 阴性对照前置：外购件料号应加成功");
        assertEquals(Boolean.FALSE, typedResp.jsonPath().get("data.materialTypeFallback"),
                "AC-12③ 阴性对照：material_type 有值的料号不得也标成兜底 —— 否则该标记恒 true、等于没标。body="
                        + typedResp.asString());
    }

    /** 再取一个不在树上的 NULL 类型料号（AC-12③ 需要第二个，避免与 ① 的那个撞环）。 */
    private String nullTypePart2() {
        String p = scalar("SELECT material_no FROM ds_quote_material WHERE material_type IS NULL "
                + "ORDER BY material_no DESC LIMIT 1");
        assertNotNull(p, "前置未满足：取不到第二个 material_type IS NULL 的料号 ⇒ AC-12③ 会空跑");
        return p;
    }

    // ───────────────────────── AC-26 ─────────────────────────

    /**
     * <b>AC-26（反向 · 成品仍不可作他人叶子挂入）</b> 原文：
     * 「以一个成品料号（在 {@code ds_quote_material} 中存在、且是某报价行的轴值）为料号，
     *  尝试加叶子到别的节点下 ⇒ 仍返回 <b>400</b>，文案指出该料号是成品、不能作为子件挂入」。
     *
     * <p>🚨 用的是 <b>lineItem 2 的轴值 {@code root2}</b>，不是本行自己的 root ——
     * 本行的 root 是宿主的祖先，会先撞 ⑥ 环检测（api.md §3.5 的顺序 ⑥ 早于 ⑦），
     * 那样这条 AC 会被另一个 400 冒充通过。
     */
    @Test
    @DisplayName("AC-26：成品料号（别的报价行的轴值）作叶子挂入 → 400，文案点名『成品』")
    void ac26_finishedGoodsStillRejected() {
        TreeFx f = arrange("A26");

        // 前置：root2 确实是成品（某报价行的轴值）且在物料表里 ⇒ 改读主数据后会被判成「零件」而放行，
        // 规则五必须显式拦住它。
        assertEquals(1L, count("SELECT count(*) FROM quotation_line_item WHERE quotation_id = '" + f.quotationId
                        + "' AND product_part_no_snapshot = '" + f.root2 + "'"),
                "前置：root2 必须是本报价单某一行的轴值");
        assertEquals(1L, count("SELECT count(*) FROM ds_quote_material WHERE material_no = '" + f.root2 + "'"),
                "前置：root2 必须在物料表里 —— 否则会被 AC-6 的存在性校验先拦掉，验的就不是成品拦截了");
        // 阴性前置：root2 不在本行的树上 ⇒ 不会撞环检测
        String treeRows = readSnapshotRows(f.lineItemId, f.treeComponentId);
        assertTrue(treeRows != null && !treeRows.contains(f.root2),
                "前置：root2 不应出现在本行的树上（否则先撞 LEAF_CYCLE_DETECTED，AC-26 会被别的 400 冒充）。实际=" + treeRows);

        Response r = addLeaf(f, f.hostNode(), f.root2);
        assertReachedBusinessLayer(r, "AC-26");
        assertEquals(400, r.statusCode(), "AC-26：成品料号作子件挂入应被拒，实际=" + r.statusCode() + " body=" + r.asString());
        String body = r.asString();
        assertTrue(body.contains("成品"),
                "AC-26：文案必须指出该料号是成品、不能作为子件挂入（🚨 不能只判 400 —— 任何别的 400 都能让用例变绿）。实际 body=" + body);
        assertTrue(body.contains(f.root2),
                "AC-26：文案应点名该料号 " + f.root2 + "，实际 body=" + body);
    }
}
