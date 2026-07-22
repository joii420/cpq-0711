package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        QuotationTreeService.CompMeta cm = new QuotationTreeService.CompMeta();
        cm.partNoField = partNoField;
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
}
