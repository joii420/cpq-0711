package com.cpq.builder.service;

import com.cpq.builder.compiler.*;
import com.cpq.builder.dto.BuilderDTOs;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260819 B-48 开发自测：{@code /preview} 路径能把编译产物里的宏与占位符全部消化掉
 * （阻塞 AC-117 / AC-119 的那条 {@code syntax error at or near ":"}）。
 *
 * <p>纯 JUnit 零查库；不起 Quarkus（树上有 #2 未应用的 V409/V410，起实例会灌进共享库）。
 */
class PreviewPlaceholderSelfCheckTest {

    /** 编译一份带 :versionFilter 的核价产物 —— 走**真实编译器**，夹具见 V9PreviewFixture。 */
    private static CompileResult compileCostBasic() {
        return V9PreviewFixture.costBasicWithVersionFilter();
    }

    /** 复现「修之前」：编译产物直接发给 PG 会剩下 :versionFilter / :total_material_no。 */
    @Test
    void compiledSqlHasPlaceholdersThatBareJdbcCannotHandle() {
        BuilderService svc = new BuilderService();
        CompileResult r = compileCostBasic();
        String left = svc.detectUnboundPlaceholders(r.sql);
        System.out.println("---- 编译产物里的占位符（未处理时就是 syntax error 的来源）----\n" + left);
        assertNotNull(left, "编译产物本来就带占位符，这正是预览必须自己消化的东西");
        assertTrue(left.contains("versionFilter"), left);
        assertTrue(left.contains("total_material_no"), left);
    }

    /**
     * 修之后：**走 preview() 真正用的那条通道** {@code buildPreviewSql}，一个占位符都不许剩
     * （AC-117/AC-119 的前置）。
     *
     * <p>🔑 这里刻意调 {@code buildPreviewSql} 而不是它内部的三个小方法：只测零件的话，
     * 「preview() 忘了调宏展开」这种装配缺陷会全绿通过——我第一版就是这样，证伪实验才抓出来。
     */
    @Test
    void previewPipelineLeavesNoPlaceholder() {
        BuilderService svc = new BuilderService();
        CompileResult r = compileCostBasic();

        BuilderDTOs.PreviewRequest req = new BuilderDTOs.PreviewRequest();
        req.tabType = "主件"; req.variantKey = ""; req.dialect = "COST_BASIC";
        req.columns = new ArrayList<>(r.effectiveColumns);
        req.partNo = "3120014539";

        String withPart = svc.buildPreviewSql(r, req);
        System.out.println("---- 预览产物（指定料号）----\n" + withPart);
        assertNull(svc.detectUnboundPlaceholders(withPart), "仍有占位符");
        assertFalse(withPart.contains(":versionFilter"), "宏没展开：\n" + withPart);
        assertFalse(withPart.contains(":__vf"), "expandForValidation 不该引入新占位符");
        assertTrue(withPart.contains("(vdcbma.is_current)"), "版本谓词应退化为『看当前版本』：\n" + withPart);
        assertTrue(withPart.contains("ARRAY['3120014539']::text[]"), withPart);

        // 未指定料号 → 用锚点表的轴值子查询（AC-117 基准是整表），而不是空数组恒假
        req.partNo = null;
        String noPart = svc.buildPreviewSql(r, req);
        System.out.println("---- 预览产物（未指定料号）----\n" + noPart);
        assertNull(svc.detectUnboundPlaceholders(noPart));
        assertTrue(noPart.contains("ARRAY(SELECT DISTINCT production_no::text FROM v_ds_cost_basic_material_all)"), noPart);
        assertFalse(noPart.contains("ARRAY[]::text[]"), "空数组恒假 ⇒ 核价预览永远 0 行：\n" + noPart);
    }

    /** 兜底体检不能把字符串字面量里的冒号误判成占位符（否则合法料号会触发假 500）。 */
    @Test
    void quotedLiteralsWithColonAreNotFlagged() {
        BuilderService svc = new BuilderService();
        assertNull(svc.detectUnboundPlaceholders(
                "SELECT * FROM t WHERE part = 'AB:CD' AND x = ANY(ARRAY['E:F','G''H:I']::text[])"));
        // 真的漏了占位符仍要报出来
        assertEquals("total_material_no", svc.detectUnboundPlaceholders(
                "SELECT * FROM t WHERE part = 'AB:CD' AND x = ANY(:total_material_no)"));
        // ::text 类型转换不是占位符
        assertNull(svc.detectUnboundPlaceholders("SELECT version_no::text FROM t"));
    }

    /** 核价侧绝不能复用 V6 的 QUOTE 闭包（销售料号 vs 生产料号，两套模型）。 */
    @Test
    void costingDoesNotFallIntoV6QuoteClosure() {
        BuilderService svc = new BuilderService();   // bomTreeRenderService 为 null
        CompileResult r = compileCostBasic();
        BuilderDTOs.PreviewRequest req = new BuilderDTOs.PreviewRequest();
        req.tabType = "主件"; req.variantKey = ""; req.dialect = "COST_BASIC"; req.partNo = "X";
        req.columns = new ArrayList<>(r.effectiveColumns);
        // 若核价分支没有提前返回，就会走到 QUOTE 分支去调 bomTreeRenderService（此处为 null）→ NPE
        assertDoesNotThrow(() -> svc.buildPreviewSql(r, req));
    }
}
