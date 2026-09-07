package com.cpq.builder.compiler;

import com.cpq.builder.exception.BuilderApiException;
import com.cpq.semanticgraph.entity.*;
import com.cpq.semanticgraph.service.SemanticGraphSnapshot;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260819 <b>B-60</b> 开发自测：{@code COMPILE_TABVIEW_NOT_FOUND} 的报文必须
 * <b>点名合法值域</b>（AC-127⑤），且把「页签类型非法」与「类型合法、只是缺/错变体」分开说。
 *
 * <p>🚨 <b>本类最关键的一条是 {@link #valueDomainComesFromGraphNotFromConstant()}</b>：
 * 图里放一个标准 6 值之外的页签类型，它必须出现在报错清单里；同时另一套数据集独有的页签类型
 * 必须<b>不</b>出现。两条一起才能证明「值域是从图里查的，不是把常量抄了一遍」——
 * 只断言"提示语里有 6 个值"是抓不到写死的（写死时它照样全中）。
 *
 * <p>⚠️ 开发自测不是正式验收用例；纯 JUnit 零查库
 * （{@code application-test.properties} 默认库就是共享开发库 {@code cpq_db_0724}，
 * 本类不建任何连接、不产生任何残留）。
 */
class TabViewNotFoundMessageSelfCheckTest {

    // ---------------- 建图 ----------------

    private static SemanticNode node(String key, String dialect, String table) {
        SemanticNode n = new SemanticNode();
        n.id = UUID.randomUUID();
        n.nodeKey = key;
        n.displayName = key + "(" + dialect + ")";
        n.shortName = key;
        n.nodeKind = "SHEET";
        n.physicalTable = table;
        n.dialect = dialect;
        n.anchorExpr = "t.material_no";
        n.grainColumns = new String[0];
        return n;
    }

    private static SemanticTabView view(String tabType, String variantKey, String label,
                                        String dialect, SemanticNode anchor) {
        SemanticTabView t = new SemanticTabView();
        t.id = UUID.randomUUID();
        t.tabType = tabType;
        t.variantKey = variantKey;
        t.variantLabel = label;
        t.dialect = dialect;
        t.anchorNodeId = anchor.id;
        t.switches = new String[0];
        return t;
    }

    /**
     * 故意做成<b>两套数据集不对称</b>，且报价侧多一个非标准页签类型：
     * <ul>
     *   <li>QUOTE：主件（无变体）、费用类（两个变体）、{@code 自定义页签}（不在标准 6 值里）</li>
     *   <li>COST_BASIC：只有主件</li>
     *   <li>COST_DETAIL：<b>一行都没有</b></li>
     * </ul>
     */
    private static SemanticGraphSnapshot graph() {
        SemanticNode q = node("MAIN", "QUOTE", "ds_quote_material");
        SemanticNode b = node("MAIN", "COST_BASIC", "ds_cost_basic_material");
        return new SemanticGraphSnapshot(1,
                List.of(q, b),
                List.of(), List.of(), List.of(),
                List.of(view("主件", "", null, "QUOTE", q),
                        view("费用类", "INCOMING_FIXED", "来料固定费", "QUOTE", q),
                        view("费用类", "INCOMING_OTHER", "来料其他费", "QUOTE", q),
                        view("自定义页签", "", null, "QUOTE", q),
                        view("主件", "", null, "COST_BASIC", b)),
                List.of(), List.of());
    }

    // =====================================================================
    // AC-127⑤：页签类型非法 → 必须点名该数据集下的合法值域
    // =====================================================================
    @Test
    void illegalTabTypeNamesTheValueDomain() {
        BuilderApiException ex = TabViewNotFound.of(graph(), 400, "不存在的页签", "", "QUOTE");
        System.out.println("---- 非法 tabType (QUOTE) ----\n" + ex.getMessage());
        assertEquals("COMPILE_TABVIEW_NOT_FOUND", ex.getErrorCode());
        assertEquals(400, ex.getCode(), "编译期沿用 400，本次不改契约");
        assertTrue(ex.getMessage().contains("合法的页签类型为"),
                "AC-127⑤：只点名收到的值不算达标，必须点名合法值域\n" + ex.getMessage());
        assertTrue(ex.getMessage().contains("主件"), ex.getMessage());
        assertTrue(ex.getMessage().contains("费用类"), ex.getMessage());
        // 结构化字段同样要给（前端不该去 parse 中文）
        assertEquals(List.of("主件", "费用类", "自定义页签"), ex.getExtra().get("availableTabTypes"));
    }

    /**
     * 🚨 反写死判据：值域必须来自图，不是抄 {@code ALL_TAB_TYPES}。
     * <ul>
     *   <li>图里有、标准 6 值里没有的「自定义页签」→ <b>必须出现</b>（写死时它出不来）；</li>
     *   <li>标准 6 值里有、本数据集图里没有的「零件/外购件/BOM」→ <b>必须不出现</b>（写死时它们会全出来）。</li>
     * </ul>
     */
    @Test
    void valueDomainComesFromGraphNotFromConstant() {
        BuilderApiException quote = TabViewNotFound.of(graph(), 400, "不存在的页签", "", "QUOTE");
        System.out.println("---- 反写死 · QUOTE ----\n" + quote.getMessage());
        assertTrue(quote.getMessage().contains("自定义页签"),
                "图里的非标准页签类型没被列出 = 值域是写死的\n" + quote.getMessage());
        for (String absent : List.of("零件", "外购件", "材质元素", "BOM")) {
            assertFalse(quote.getMessage().contains(absent),
                    "本数据集图里没有「" + absent + "」却被列了出来 = 值域是写死的\n" + quote.getMessage());
        }

        // 同一份图、换个数据集，值域必须跟着变（写死时两边一模一样）
        BuilderApiException basic = TabViewNotFound.of(graph(), 400, "不存在的页签", "", "COST_BASIC");
        System.out.println("---- 反写死 · COST_BASIC ----\n" + basic.getMessage());
        assertEquals(List.of("主件"), basic.getExtra().get("availableTabTypes"),
                "COST_BASIC 图里只有主件");
        assertFalse(basic.getMessage().contains("费用类"),
                "报价侧独有的页签类型泄漏到了核价侧的报错里\n" + basic.getMessage());
    }

    /** 清单顺序 = 前端下拉顺序（ALL_TAB_TYPES 只用于排序，非标准值追加在后面）。 */
    @Test
    void valueDomainKeepsDisplayOrder() {
        BuilderApiException ex = TabViewNotFound.of(graph(), 400, "不存在的页签", "", "QUOTE");
        @SuppressWarnings("unchecked")
        List<String> domain = (List<String>) ex.getExtra().get("availableTabTypes");
        System.out.println("---- 展示顺序 ----\n" + domain);
        assertEquals(List.of("主件", "费用类", "自定义页签"), domain,
                "应按 ALL_TAB_TYPES 顺序（主件在费用类之前），非标准值垫底");
    }

    // =====================================================================
    // 变体区分：类型存在、只是缺/错变体 —— 不能和"类型不存在"长成一个样
    // =====================================================================

    /** 主线实测那一例：{@code GET /field-tree?tabType=费用类} 不传 variantKey。 */
    @Test
    void missingVariantSaysTypeExists() {
        BuilderApiException ex = TabViewNotFound.of(graph(), 404, "费用类", null, "QUOTE");
        System.out.println("---- 费用类 缺 variantKey ----\n" + ex.getMessage());
        assertEquals("COMPILE_TABVIEW_NOT_FOUND", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("页签类型「费用类」在该数据集下是存在的"),
                "必须先说清「类型是存在的」，否则用户会以为费用类没配\n" + ex.getMessage());
        assertTrue(ex.getMessage().contains("必须同时指定变体"), ex.getMessage());
        assertTrue(ex.getMessage().contains("INCOMING_FIXED(来料固定费)"), ex.getMessage());
        assertTrue(ex.getMessage().contains("INCOMING_OTHER(来料其他费)"), ex.getMessage());
        // 🚨 与"类型非法"必须可区分：这一支不得出现"合法的页签类型为"
        assertFalse(ex.getMessage().contains("合法的页签类型为"),
                "变体缺失被说成了页签类型非法 —— 两种失败混为一谈\n" + ex.getMessage());
        assertNotNull(ex.getExtra().get("availableVariants"));
    }

    /** 变体传了但传错。 */
    @Test
    void wrongVariantIsCalledOut() {
        BuilderApiException ex = TabViewNotFound.of(graph(), 404, "费用类", "NO_SUCH", "QUOTE");
        System.out.println("---- 费用类 变体传错 ----\n" + ex.getMessage());
        assertTrue(ex.getMessage().contains("变体「NO_SUCH」不存在"), ex.getMessage());
        assertTrue(ex.getMessage().contains("INCOMING_FIXED(来料固定费)"), ex.getMessage());
    }

    /** 反向：给不分变体的页签类型传了变体。 */
    @Test
    void unexpectedVariantOnPlainTabType() {
        BuilderApiException ex = TabViewNotFound.of(graph(), 404, "主件", "SOMETHING", "QUOTE");
        System.out.println("---- 主件 多传了 variantKey ----\n" + ex.getMessage());
        assertTrue(ex.getMessage().contains("不分变体"), ex.getMessage());
        assertTrue(ex.getMessage().contains("(不带变体)"), ex.getMessage());
    }

    // =====================================================================
    // 该数据集一行页签视图都没有：报"值域为空/种子缺失"，别让用户去试那 6 个值
    // =====================================================================
    @Test
    void emptyDialectSaysSeedMissing() {
        BuilderApiException ex = TabViewNotFound.of(graph(), 400, "主件", "", "COST_DETAIL");
        System.out.println("---- COST_DETAIL 无种子 ----\n" + ex.getMessage());
        assertEquals("COMPILE_TABVIEW_NOT_FOUND", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("一个页签视图都没有"), ex.getMessage());
        assertTrue(ex.getMessage().contains("COST_DETAIL"), ex.getMessage());
        assertEquals(List.of(), ex.getExtra().get("availableTabTypes"));
        assertFalse(ex.getMessage().contains("合法的页签类型为"),
                "值域为空时不该给出一份空清单冒充合法值域\n" + ex.getMessage());
    }

    // =====================================================================
    // 两个真实调用点确实接上了本类（不是只改了 helper 没接线）
    // =====================================================================
    @Test
    void fieldTreeBuilderUsesTheNewMessage() {
        SemanticNode q = node("MAIN", "QUOTE", "ds_quote_material");
        SemanticGraphSnapshot snap = new SemanticGraphSnapshot(1,
                List.of(q), List.of(), List.of(), List.of(),
                List.of(view("主件", "", null, "QUOTE", q),
                        view("费用类", "INCOMING_FIXED", "来料固定费", "QUOTE", q)),
                List.of(), List.of());
        FieldTreeBuilder ftb = new FieldTreeBuilder();

        BuilderApiException bad = assertThrows(BuilderApiException.class,
                () -> ftb.build(snap, CompileDialect.QUOTE, "不存在的页签", "", null));
        System.out.println("---- FieldTreeBuilder 非法 tabType ----\n" + bad.getMessage());
        assertTrue(bad.getMessage().contains("合法的页签类型为"), bad.getMessage());

        BuilderApiException noVariant = assertThrows(BuilderApiException.class,
                () -> ftb.build(snap, CompileDialect.QUOTE, "费用类", null, null));
        System.out.println("---- FieldTreeBuilder 费用类缺变体 ----\n" + noVariant.getMessage());
        assertTrue(noVariant.getMessage().contains("必须同时指定变体"), noVariant.getMessage());
    }
}
