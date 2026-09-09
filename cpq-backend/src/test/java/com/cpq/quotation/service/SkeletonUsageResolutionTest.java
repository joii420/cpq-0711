package com.cpq.quotation.service;

import com.cpq.builder.compiler.CompileDialect;
import com.cpq.common.exception.BusinessException;
import com.cpq.component.service.CostingBomTreeConfigService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260909 B-2 / B-3 —— 骨架配置 {@code usage} 的值域与「渲染期按树页签方言分档」解析。
 *
 * <p>纯 JUnit（不启 Quarkus、不连库）：被测的两处都已抽成静态纯函数
 * （{@link CostingBomTreeConfigService#normalizeUsage} /
 * {@link BomTreeRenderService#mapDialectToUsage}），共享库红线下可放心常驻回归。
 *
 * <p>🚨 <b>这组用例防的是一类"不报错的错"</b>：usage 判错不会抛异常、不会少列，
 * 只会让基础核价模板悄悄用上详细核价的树 —— 渲染得出来、数据是别的数据集的。
 */
class SkeletonUsageResolutionTest {

    // ── B-2：值域（读） ────────────────────────────────────────────────────────

    @Test
    void normalize_threeWritableValues_areIdentity() {
        assertEquals("QUOTE", CostingBomTreeConfigService.normalizeUsage("QUOTE"));
        assertEquals("COST_BASIC", CostingBomTreeConfigService.normalizeUsage("COST_BASIC"));
        assertEquals("COST_DETAIL", CostingBomTreeConfigService.normalizeUsage("COST_DETAIL"));
        // 大小写 / 空白容忍（读参数来自 query string）
        assertEquals("COST_DETAIL", CostingBomTreeConfigService.normalizeUsage("  cost_detail "));
    }

    /** {@code COSTING} 是只读兼容别名 ⇒ 读侧等价 {@code COST_BASIC}（api.md §1.1）。 */
    @Test
    void normalize_legacyCostingAlias_mapsToCostBasic() {
        assertEquals("COST_BASIC", CostingBomTreeConfigService.normalizeUsage("COSTING"));
        assertEquals("COST_BASIC", CostingBomTreeConfigService.normalizeUsage("costing"));
    }

    /**
     * 🔑 别名归一必须发生在 <b>{@code findActive} 之前</b>：
     * {@code CostingBomTreeConfig#findActive} 做的是字面量匹配，
     * 传原始 {@code "COSTING"} 进去只会去找 {@code usage='COSTING'} 的行。
     * 本用例把这条不变量钉住 —— 它是"配置明明在列表里、渲染却说未配置"那类故障的唯一防线。
     */
    @Test
    void normalize_isTheSingleEntryPointBeforeFindActive() {
        assertNotEquals("COSTING", CostingBomTreeConfigService.normalizeUsage("COSTING"));
    }

    @Test
    void normalize_rejectsUnknownAndBlank() {
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> CostingBomTreeConfigService.normalizeUsage("PRICING"));
        assertEquals(400, e1.getCode());
        assertTrue(e1.getMessage().contains("COST_BASIC"), e1.getMessage());

        assertThrows(BusinessException.class, () -> CostingBomTreeConfigService.normalizeUsage(null));
        assertThrows(BusinessException.class, () -> CostingBomTreeConfigService.normalizeUsage("   "));
    }

    @Test
    void usageDisplayName_isChineseDatasetName() {
        assertEquals("报价", CostingBomTreeConfigService.usageDisplayName("QUOTE"));
        assertEquals("基础核价", CostingBomTreeConfigService.usageDisplayName("COST_BASIC"));
        assertEquals("基础核价", CostingBomTreeConfigService.usageDisplayName("COSTING"));
        assertEquals("详细核价", CostingBomTreeConfigService.usageDisplayName("COST_DETAIL"));
    }

    // ── B-3：方言 → usage + 三级回落 ──────────────────────────────────────────

    /** 第 1 级：解析到方言 ⇒ 用它，且<b>压过</b>调用方传进来的实参。 */
    @Test
    void level1_dialectWinsOverCallerArgument() {
        assertEquals("COST_BASIC", BomTreeRenderService.mapDialectToUsage("COST_BASIC", "COST_BASIC"));
        assertEquals("COST_DETAIL", BomTreeRenderService.mapDialectToUsage("COST_DETAIL", "COST_BASIC"));
        assertEquals("QUOTE", BomTreeRenderService.mapDialectToUsage("QUOTE", "COST_BASIC"));
        // 反向也要成立：调用方说 QUOTE，树页签是核价方言 ⇒ 以方言为准
        assertEquals("COST_BASIC", BomTreeRenderService.mapDialectToUsage("COST_BASIC", "QUOTE"));
    }

    /** 方言值域与 usage 值域<b>同名同形</b> ⇒ 映射恒等（这条一破，B-3 的"不写对照表"就不成立）。 */
    @Test
    void dialectEnumNamesEqualWritableUsageValues() {
        for (CompileDialect d : CompileDialect.values()) {
            assertTrue(CostingBomTreeConfigService.WRITABLE_USAGES.contains(d.name()),
                    "方言 " + d.name() + " 在 usage 可写值域里找不到同名项，B-3 的恒等映射已被破坏");
        }
        assertEquals(CostingBomTreeConfigService.WRITABLE_USAGES.size(), CompileDialect.values().length);
    }

    /** 第 2 级：解析不到方言（手写视图 / 无树页签）⇒ 回落调用方实参 = 改造前行为。 */
    @Test
    void level2_fallsBackToCallerArgumentWhenDialectUnresolved() {
        assertEquals("COST_BASIC", BomTreeRenderService.mapDialectToUsage(null, "COST_BASIC"));
        assertEquals("COST_BASIC", BomTreeRenderService.mapDialectToUsage("", "COST_BASIC"));
        assertEquals("QUOTE", BomTreeRenderService.mapDialectToUsage("  ", "QUOTE"));
    }

    /**
     * 方言值不可识别（图里存了野值 / 旧值 {@code COSTING}）⇒ 回落实参，<b>不猜</b>。
     * 🚫 特别是不能"看着像核价就当 COST_BASIC" —— 猜错的形态是渲染出另一个数据集的树而不报错。
     */
    @Test
    void unparseableDialect_fallsBackInsteadOfGuessing() {
        assertEquals("QUOTE", BomTreeRenderService.mapDialectToUsage("COSTING", "QUOTE"));
        assertEquals("COST_BASIC", BomTreeRenderService.mapDialectToUsage("GARBAGE", "COST_BASIC"));
    }
}
