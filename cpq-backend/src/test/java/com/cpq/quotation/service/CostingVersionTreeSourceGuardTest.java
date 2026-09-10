package com.cpq.quotation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * repair-260910 B-3 —— 核价树版本候选「取数来源」的<b>接线守卫</b>（AC-9 / AC-11 / AC-15）。
 *
 * <h3>为什么写成源码级断言而不是发 HTTP</h3>
 * 本次 bug 的形态是<b>接线接到了一张已经不再接收数据的表上</b>：
 * {@code listVersionOptions} 的树分支硬编码查 V6 老表 {@code material_bom_item}，
 * 而核价数据早已迁到 {@code ds_cost_*}。
 * <b>这种故障在行为层是「返回空列表」，与「这个料号确实没有别的版本」长得一模一样</b> ——
 * 任何「调接口看返回」的用例，在共享库数据漂移后都会退化成假绿
 * （{@code task-260909} 已经栽过一次：反射直调守不住接线）。
 * ⇒ 把「不许再出现那张表名」「非树分支没被顺手改掉」「PENDING 校验还在」写成对<b>源码文本</b>的
 * 断言，它们在数据怎么漂移的情况下都成立。
 *
 * <p>真实链路的 200/400/403 与候选值正确性由主线在真机亲验（AC-3/4/5/8/9），两者互补不互替。
 *
 * <h3>环境纪律</h3>
 * 本类是<b>纯 JUnit</b>（无 {@code @QuarkusTest}、不注入 {@code EntityManager}、不连库、不建夹具），
 * 因此不触碰共享库，{@code CLAUDE.md §3.2}「测试也算」的清库风险面为零。
 */
@DisplayName("repair-260910 · 核价树版本候选取数来源接线守卫")
class CostingVersionTreeSourceGuardTest {

    /** 已停止接收核价数据的 V6 老表 —— 树分支<b>不得再</b>出现它。 */
    private static final String RETIRED_TABLE = "material_bom_item";

    private static final String TARGET = "quotation/service/CostingVersionService.java";

    // ════════════════════════════════════════════════════════════════════
    // 一、纯函数：方言 → BOM 全版本视图（AC-10 的判据内核）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-10：COST_BASIC / COST_DETAIL 各查各的全版本视图，🚫 detail 不许回落 basic")
    void dialectMapsToItsOwnAllVersionView() {
        assertEquals("v_ds_cost_basic_material_bom_all",
                CostingVersionService.treeVersionViewOfDialect("COST_BASIC"));
        assertEquals("v_ds_cost_detail_material_bom_all",
                CostingVersionService.treeVersionViewOfDialect("COST_DETAIL"));
        // 阳性对照：两者必须<b>不同</b>——若将来有人把 detail 接回 basic，上面两条单独看仍会"像是通过"
        assertTrue(!CostingVersionService.treeVersionViewOfDialect("COST_BASIC")
                        .equals(CostingVersionService.treeVersionViewOfDialect("COST_DETAIL")),
                "COST_DETAIL 与 COST_BASIC 解析到了同一张视图 ⇒ 明细核价悄悄用了基础核价的版本列表（AC-10 禁止的回落）");
    }

    /**
     * 🚨 {@code CompileDialect.parse(null)} 对 null/空<b>静默返回 QUOTE</b>（"不传 = 报价侧"）。
     * 若本方法直接透传给它，「解析不到方言」就会被悄悄变成「报价侧」——一个我们答不上来的问题
     * 被当成答上了。故三种"答不上来"必须一律 null（调用方返回空候选，不回落任何视图）。
     */
    @Test
    @DisplayName("AC-10 反向：解析不到数据集时返回 null（空候选），🚫 不许静默回落成某一侧")
    void unresolvableDialectYieldsNoViewInsteadOfSilentFallback() {
        assertNull(CostingVersionService.treeVersionViewOfDialect(null), "null 方言必须 = 无候选");
        assertNull(CostingVersionService.treeVersionViewOfDialect("   "), "空白方言必须 = 无候选");
        assertNull(CostingVersionService.treeVersionViewOfDialect("COSTING"),
                "COSTING 已随 V6 作废，必须 = 无候选（🚫 不许猜成 COST_BASIC）");
        assertNull(CostingVersionService.treeVersionViewOfDialect("NOT_A_DIALECT"),
                "不可识别方言必须 = 无候选，且不得抛异常（AC-10：🚫 不许 500）");
        assertNull(CostingVersionService.treeVersionViewOfDialect("QUOTE"),
                "报价方言在核价版本端点上无候选");
    }

    /** 返回值恒为常量字面量 ⇒ 调用方把它拼进 SQL 是安全的（无外部输入进 SQL 文本）。 */
    @Test
    @DisplayName("拼接安全：解析结果只能是白名单里的两个视图名之一")
    void resolvedViewNameIsAlwaysAWhitelistedLiteral() {
        for (String raw : new String[]{"COST_BASIC", "cost_basic", "COST_DETAIL", " cost_detail ",
                "QUOTE", null, "", "x'; DROP TABLE component; --"}) {
            String v = CostingVersionService.treeVersionViewOfDialect(raw);
            assertTrue(v == null
                            || "v_ds_cost_basic_material_bom_all".equals(v)
                            || "v_ds_cost_detail_material_bom_all".equals(v),
                    "方言「" + raw + "」解析出了白名单之外的视图名：" + v);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 二、源码级接线断言
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("B-1：树分支不得再出现 V6 老表 material_bom_item（注释豁免）")
    void retiredV6TableIsGoneFromExecutableCode() throws IOException {
        List<String> lines = sourceLines();
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (!raw.contains(RETIRED_TABLE)) continue;
            String t = raw.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue; // 注释里的历史说明豁免
            hits.add((i + 1) + ": " + t);
        }
        if (!hits.isEmpty()) {
            fail("CostingVersionService 的<b>可执行代码</b>里仍有 " + hits.size() + " 处引用已停止接收核价数据的 "
                    + RETIRED_TABLE + "（候选列表会恒空且不报错）：\n      " + String.join("\n      ", hits));
        }
    }

    @Test
    @DisplayName("B-1：树分支按 production_no 查数据集全版本视图（🚫 不许按 component_no）")
    void treeBranchQueriesItsOwnBomByProductionNo() throws IOException {
        String src = source();
        assertTrue(src.contains("treeVersionViewOf(componentId)"),
                "树分支必须经方言解析拿视图名（与骨架配置同源），🚫 不许把物理表名写死");
        assertTrue(src.contains("\" WHERE production_no = :p AND version_no IS NOT NULL\""),
                "候选查询必须按 production_no（该料号自己那张清单）过滤 —— "
                        + "按 component_no 查会给出它自己清单里并不存在的版本（AC-4 / E-6，repair-0590 前科）");
    }

    @Test
    @DisplayName("AC-11 门禁：非树分支（ds_cost_* 专用查询 + 通用 $view 扫描）未被改动")
    void nonTreeBranchesUntouched() throws IOException {
        String src = source();
        assertTrue(src.contains("String dsCostBase = tree ? null : dsCostBaseTableOf(componentId);"),
                "非树分支的数据集解析入口被改动了 —— AC-11 要求非树组件版本切换逐位不变");
        assertTrue(src.contains("} else if (dsCostBase != null) {"),
                "非树 ds_cost_* 专用查询分支不见了");
        assertTrue(src.contains("+ \" UNION \"") && src.contains("+ \"_history\""),
                "非树分支的「主表 UNION _history」查询被改动了");
        assertTrue(src.contains("BomTreeVarsContext.Mode.LIST"),
                "兜底的通用 $view 扫描分支（Mode.LIST）不见了");
    }

    @Test
    @DisplayName("AC-9 门禁：switchVersion 的 PENDING 状态校验与 403 文案逐字仍在")
    void pendingStateGuardStillInPlace() throws IOException {
        String src = source();
        assertTrue(src.contains("if (!\"PENDING\".equals(co.status)) {"),
                "PENDING 状态校验被改动/删除了（AC-9）");
        assertTrue(src.contains("throw new BusinessException(403, \"仅待核价(PENDING)可切换版本，当前状态=\" + co.status);"),
                "403 文案被改动了（AC-9 要求既有行为不得改变）");
    }

    @Test
    @DisplayName("B-2 / AC-8：叶子守卫存在，且排在 PENDING 校验之后、override upsert 之前")
    void leafGuardExistsAndSitsAfterPendingCheckBeforeUpsert() throws IOException {
        String src = source();
        int pending = src.indexOf("throw new BusinessException(403, \"仅待核价(PENDING)可切换版本");
        int guard = src.indexOf("没有自己的 BOM");
        int upsert = src.indexOf("CostingOrderVersionOverride ov = CostingOrderVersionOverride.find(coid, req.componentId, req.partNo);");
        assertTrue(pending > 0, "定位不到 403 PENDING 校验");
        assertTrue(guard > 0, "叶子守卫（400「没有自己的 BOM」）不存在 —— AC-8 会红");
        assertTrue(upsert > 0, "定位不到 override upsert");
        assertTrue(pending < guard, "叶子守卫排到了 PENDING 校验之前 ⇒ 非 PENDING 单会先收到 400 而不是 403（AC-9 会红）");
        assertTrue(guard < upsert, "叶子守卫排到了 override upsert 之后 ⇒ AC-8「不新增 override 行」失去第一道防线");
    }

    // ════════════════════════════════════════════════════════════════════
    // 工具（含阳性对照：扫描器必须证明自己真的读到了目标文件）
    // ════════════════════════════════════════════════════════════════════

    private static String source() throws IOException {
        return String.join("\n", sourceLines());
    }

    private static List<String> sourceLines() throws IOException {
        File root = locateBackendMainJava();
        assertNotNull(root, "定位不到 cpq-backend/src/main/java/com/cpq —— 这是 harness 故障，不是 AC 结论");
        Path p = root.toPath().resolve(TARGET);
        assertTrue(Files.isRegularFile(p), "读不到目标源文件：" + p);
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        // 🚨 阳性对照（testing.md §4.4）：一个读到空文件的扫描器，结果与「全部通过」长得一模一样。
        assertTrue(lines.size() > 300, "只读到 " + lines.size() + " 行 ⇒ 文件不对/没读到，此时的绿是空验证");
        assertTrue(String.join("\n", lines).contains("public VersionOptionsResponseDTO listVersionOptions("),
                "读到的文件里没有 listVersionOptions ⇒ 扫描的不是目标文件");
        return lines;
    }

    private static File locateBackendMainJava() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParentFile()) {
            File c1 = new File(dir, "src/main/java/com/cpq");
            if (c1.isDirectory()) return c1;
            File c2 = new File(dir, "cpq-backend/src/main/java/com/cpq");
            if (c2.isDirectory()) return c2;
        }
        return null;
    }
}
