package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0721（2026-07-21 补录）— {@code QuotationTreeService.extractMaterialNoByField} 单测。
 *
 * <p>纯 JUnit（无 DB/CDI，手工 new + 直接赋 package-private 字段，同包内合法）：验证料号列解析
 * 严格依据 {@code CompMeta.partNoField} 显式取值，不再按"字段名含料号"这类启发式猜测。
 */
class QuotationTreeServicePartNoFieldTest {

    private static final ObjectMapper M = new ObjectMapper();

    private QuotationTreeService newService() {
        QuotationTreeService svc = new QuotationTreeService();
        svc.formulaCalculator = new FormulaCalculator();
        return svc;
    }

    private JsonNode j(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private QuotationTreeService.CompMeta compMeta(String partNoField, String fieldsJson) {
        return compMeta(partNoField, null, fieldsJson);
    }

    private QuotationTreeService.CompMeta compMeta(String partNoField, String partNameField, String fieldsJson) {
        QuotationTreeService.CompMeta cm = new QuotationTreeService.CompMeta();
        cm.partNoField = partNoField;
        cm.partNameField = partNameField;
        cm.fields = fieldsJson;
        return cm;
    }

    @Test
    void directDriverRowKeyMatch_resolvesByPartNoField() {
        // partNoField="料号"，driverRow 恰好也用 "料号" 作 key(直读命中)
        QuotationTreeService svc = newService();
        String row = "{\"driverRow\":{\"料号\":\"AgNi11#\",\"含量\":0.138},\"basicDataValues\":{}}";
        QuotationTreeService.CompMeta cm = compMeta("料号", "[]");
        assertEquals("AgNi11#", svc.extractMaterialNoByField(j(row), cm));
    }

    @Test
    void fieldNamedMaterialButNotConfiguredAsPartNoField_isIgnored() {
        // 2026-07-21 裁决核心：即便 driverRow 里有个字段名"看起来像料号"(material_no)，
        // 若 partNoField 配的是另一个字段名(且该名在 driverRow 里查不到)，不应该"退而求其次"猜 material_no。
        QuotationTreeService svc = newService();
        String row = "{\"driverRow\":{\"material_no\":\"SHOULD-NOT-BE-PICKED\",\"其他列\":\"x\"},\"basicDataValues\":{}}";
        QuotationTreeService.CompMeta cm = compMeta("料号", "[]"); // partNoField="料号"，driverRow 里没有这个 key
        assertNull(svc.extractMaterialNoByField(j(row), cm),
                "partNoField 指定的字段名在行里查不到时应返回 null，不应啟发式回退猜别的列");
    }

    @Test
    void missingPartNoField_returnsNull() {
        QuotationTreeService svc = newService();
        String row = "{\"driverRow\":{\"material_no\":\"X\"},\"basicDataValues\":{}}";
        QuotationTreeService.CompMeta cm = compMeta(null, "[]"); // 未配置 partNoField(如树页签)
        assertNull(svc.extractMaterialNoByField(j(row), cm));
    }

    @Test
    void blankPartNoField_returnsNull() {
        QuotationTreeService svc = newService();
        String row = "{\"driverRow\":{\"料号\":\"X\"},\"basicDataValues\":{}}";
        QuotationTreeService.CompMeta cm = compMeta("  ", "[]");
        assertNull(svc.extractMaterialNoByField(j(row), cm));
    }

    @Test
    void nullRow_returnsNull() {
        QuotationTreeService svc = newService();
        QuotationTreeService.CompMeta cm = compMeta("料号", "[]");
        assertNull(svc.extractMaterialNoByField(null, cm));
    }

    // ── 2026-07-23 修订：匹配标识放宽为"料号列 或 名称列" ──────────────────────────

    @Test
    @DisplayName("2026-07-23 放宽：partNoField 未配置(null)，回落 partNameField 取值(如「外购件/费用」类页签)")
    void nameFieldFallback_resolvesByPartNameField_whenPartNoFieldNull() {
        // 场景对齐委托方截图：「料件=组成件1」「要素=材料费」，无料号列，只有「料件名称」列
        QuotationTreeService svc = newService();
        String row = "{\"driverRow\":{\"料件名称\":\"组成件1\",\"要素\":\"材料费\"},\"basicDataValues\":{}}";
        QuotationTreeService.CompMeta cm = compMeta(null, "料件名称", "[]");
        assertEquals("组成件1", svc.extractMaterialNoByField(j(row), cm),
                "partNoField 未配置时应回落 partNameField 取值");
    }

    @Test
    @DisplayName("2026-07-23 放宽：partNoField 是空白字符串(非 null)，同样回落 partNameField")
    void nameFieldFallback_resolvesByPartNameField_whenPartNoFieldBlank() {
        QuotationTreeService svc = newService();
        String row = "{\"driverRow\":{\"料件名称\":\"组成件2\"},\"basicDataValues\":{}}";
        QuotationTreeService.CompMeta cm = compMeta("   ", "料件名称", "[]");
        assertEquals("组成件2", svc.extractMaterialNoByField(j(row), cm));
    }

    @Test
    @DisplayName("2026-07-23 放宽：两者都配置时 partNoField 优先（料号列优先，名称列兜底）")
    void partNoFieldTakesPriorityOverPartNameField_whenBothConfigured() {
        QuotationTreeService svc = newService();
        String row = "{\"driverRow\":{\"料号\":\"P001\",\"料件名称\":\"组成件3\"},\"basicDataValues\":{}}";
        QuotationTreeService.CompMeta cm = compMeta("料号", "料件名称", "[]");
        assertEquals("P001", svc.extractMaterialNoByField(j(row), cm),
                "两者都配置且料号列取值成功时，应优先用料号列的值，不应误用名称列");
    }

    @Test
    @DisplayName("2026-07-23 放宽：两者都缺失(null)才最终返回 null（防御分支，理论已被保存期 400 拦住）")
    void bothFieldsMissing_returnsNull() {
        QuotationTreeService svc = newService();
        String row = "{\"driverRow\":{\"料号\":\"P001\"},\"basicDataValues\":{}}";
        QuotationTreeService.CompMeta cm = compMeta(null, null, "[]");
        assertNull(svc.extractMaterialNoByField(j(row), cm));
    }
}
