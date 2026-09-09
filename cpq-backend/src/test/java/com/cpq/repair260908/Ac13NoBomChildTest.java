package com.cpq.repair260908;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260908 · <b>S-3 / AC-13</b>：无 BOM 子件的成品。
 *
 * <h3>AC-13 原文（{@code 问题说明.md §⑥ 边界类}，逐字）</h3>
 * <blockquote>
 * 前置：选一个<b>无 BOM 子件</b>的成品料号 ｜ 操作：打开其卡片 ｜
 * 断言：「产品」页签仍为 <b>1 行</b>；B 族页签返 <b>0 行且不报错</b>
 * （不得因为 0 行触发 {@code assertParentNoPresent} 之类的硬拦）。
 * </blockquote>
 *
 * <h3>造数（分片前缀 {@code R260908S3_}，私有写）</h3>
 * 库里现有料号都挂着 BOM/元素/加工费数据，找不到一个干净的「无子件成品」；
 * 且拿别人的料号做边界测试会与并发线抢数据。⇒ <b>自造一个只在 {@code ds_quote_material} 里存在的料号</b>：
 * 没有 {@code ds_quote_material_bom} / {@code ds_quote_element_bom} /
 * {@code ds_quote_self_process_fee} 行，闭包 = 它自己。
 * 🚨 造数前先断言「本轮命名空间是干净的」——否则「我造的行出现了」会被上轮残留冒充。
 *
 * <h3>⚠️ 对 AC-13 措辞的一处质疑（🚫 我没有改 AC，按原文测 + 如实报回）</h3>
 * BOM 页签的视图是<b>树形契约</b>（{@code UNION ALL} 根分支：闭包里无父边的成品自身，
 * {@code parent_no} 恒 {@code NULL}）。无子件成品<b>按设计就会命中根分支</b> ⇒ 它的 BOM 页签是
 * <b>1 行根节点</b>，不是 0 行。⇒ 「B 族页签返 0 行」对 <b>材质元素 / 加工费</b> 成立，
 * 对 <b>BOM 树页签</b>不成立；后者的正确期望是「<b>只有根行、无子行、不报错</b>」。
 * 本类按这个拆分断言，并把差异原样报给主线裁决。
 *
 * <h3>🚨 证伪设计（{@code test.md §4}）</h3>
 * AC-13 防的是「0 行触发硬拦」。把渲染层对空结果的兜底改成抛异常
 * （例如恢复 {@code assertParentNoPresent} 式的硬阻断），
 * {@link #t02_leafPart_bFamilyEmpty_noHardBlock} 立刻变红 —— 它同时断言
 * <b>HTTP 200</b>、<b>无 {@code __renderError} 占位</b>、<b>主件仍有 1 行</b>，
 * 三者任一被硬拦打断都会红。
 * <p>反向（防止「因为什么都没渲染出来所以看起来没报错」这种空跑）：
 * {@link #t01_leafPart_mainTabStillOneRow} 先要求主件页签<b>非空且恰 1 行、料号就是我造的那个</b>，
 * 证明这套夹具真的走到了渲染，🚫 不是「整单没渲染 ⇒ 全 0 行 ⇒ 断言空跑」。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ac13NoBomChildTest extends S3FixtureBase {

    /** 自造的无子件成品料号（17 字符，{@code ds_quote_material.material_no} 上限 128）。 */
    private static final String LEAF = PREFIX + RUN_ID + "L";

    /**
     * 🚨 必须 {@code static}：JUnit 默认<b>每个测试方法一个新实例</b>，实例字段会被重置，
     * 于是第二个用例会再造一次夹具、撞上自己的「本轮命名空间必须干净」自检 ——
     * 报出来是「构造自检失败」，看起来像残留没清干净（2026-09-09 实测踩过）。
     */
    private static Fixture fixture;

    /** 渲染那一次的 HTTP 码（整套夹具只渲染一次，两个用例共用同一份证据）。 */
    private static int renderStatus;

    private void ensureFixture() {
        if (fixture != null) {
            return;
        }
        // 构造自检：本轮命名空间必须干净
        assertEquals(0L, scalarLong("SELECT count(*) FROM ds_quote_material WHERE material_no = ?1", LEAF),
                "构造自检：ds_quote_material 里已存在 " + LEAF + " ⇒ 行数断言会被残留冒充");

        exec("INSERT INTO ds_quote_material (material_no, material_name, specification, dimension, "
                + "old_material_no, unit_weight, production_no, source, material_type, category_code, customer_no) "
                + "VALUES (?1, ?2, 'S3-SPEC', 'S3-DIM', NULL, 1.000000000000, ?3, 'IMPORT', '零件', '000000', ?4)",
                LEAF, PREFIX + "无子件成品", LEAF + "P", CUSTOMER);

        // 夹具自检（阳性 + 阴性两面都要，否则「0 行」可能只是料号根本没落库）
        assertEquals(1L, scalarLong("SELECT count(*) FROM ds_quote_material "
                + "WHERE material_no = ?1 AND customer_no = ?2", LEAF, CUSTOMER),
                "夹具自检：ds_quote_material 应恰有 1 行我的成品（这是「主件 1 行」的数据来源）");
        assertEquals(0L, scalarLong("SELECT count(*) FROM ds_quote_material_bom "
                + "WHERE material_no = ?1 OR input_material_no = ?1", LEAF),
                "夹具自检：我的成品必须无任何 BOM 边（AC-13 的前置就是『无子件』）");
        assertEquals(0L, scalarLong("SELECT count(*) FROM ds_quote_element_bom WHERE material_no = ?1", LEAF),
                "夹具自检：我的成品不应有材质元素行");
        assertEquals(0L, scalarLong("SELECT count(*) FROM ds_quote_self_process_fee WHERE material_no = ?1", LEAF),
                "夹具自检：我的成品不应有加工费行");

        fixture = createFixture("A13", "SELF", KEEP, LEAF, COMP_PRODUCT, VIEW_PRODUCT);
        renderStatus = render(fixture);
        assertMineWasUsed(fixture);
    }

    /** AC-13 前半：主件（产品）页签仍为 <b>1 行</b>，且就是这个成品自己。 */
    @Test
    @Order(1)
    void t01_leafPart_mainTabStillOneRow() {
        ensureFixture();
        List<String> parts = renderedPartNos(fixture, fixture.componentId());
        System.out.println("[AC-13] 主件页签 → " + parts.size() + " 行 " + parts);
        assertEquals(1, parts.size(), "AC-13：无子件成品的主件页签应恰 1 行，实际 " + parts.size() + " 行 " + parts);
        assertEquals(LEAF, parts.get(0), "主件那一行应当就是我造的成品料号");
    }

    /**
     * AC-13 后半：B 族页签「0 行且不报错」，不得触发硬拦。
     *
     * <p>拆三条断言，因为三种失败长得不一样：
     * <ol>
     *   <li><b>不报错</b> —— 整单 HTTP 200（已在 {@code ensureFixture} 里断言），
     *       且渲染结果里<b>没有 {@code __renderError} 占位</b>（R-1 那次的故障形态就是
     *       「HTTP 200 + 整页占位行」，只看状态码抓不到）；</li>
     *   <li><b>材质元素 / 加工费 = 0 行</b>；</li>
     *   <li><b>BOM 树页签</b>：只有根行（{@code parent_no} 为 {@code NULL}）、无子行 ——
     *       见类注释里对 AC 措辞的质疑。</li>
     * </ol>
     */
    @Test
    @Order(2)
    void t02_leafPart_bFamilyEmpty_noHardBlock() {
        ensureFixture();
        assertEquals(200, renderStatus,
                "AC-13：无子件成品的报价单渲染应 200 —— 非 200 即『0 行触发了硬拦』");
        assertFalse(hasRenderError(fixture),
                "AC-13：渲染结果里出现了 __renderError 占位 —— 属于『0 行把渲染打断了』，"
                + "HTTP 200 这个信号对它恒为真、抓不到（R-1 实证）");

        long element = renderedRowCount(fixture, COMP_ELEMENT);
        long fee = renderedRowCount(fixture, COMP_FEE);
        long bom = renderedRowCount(fixture, COMP_BOM);
        System.out.println("[AC-13] B 族页签 → 材质元素=" + element + " 加工费=" + fee + " BOM=" + bom
                + "（-1 表示该页签没有落 componentData 行）");

        assertTrue(element <= 0, "AC-13：无子件成品的『材质元素』页签应 0 行，实际 " + element);
        assertTrue(fee <= 0, "AC-13：无子件成品的『加工费』页签应 0 行，实际 " + fee);

        List<String> bomParents = strList("SELECT r->'driverRow'->>'parent_no' "
                + "FROM quotation_line_component_data d JOIN quotation_line_item l ON l.id = d.line_item_id, "
                + "     jsonb_array_elements(d.snapshot_rows) r "
                + "WHERE l.quotation_id = CAST(?1 AS uuid) AND d.component_id = CAST(?2 AS uuid)",
                fixture.quotationId(), COMP_BOM);
        System.out.println("[AC-13] BOM 树页签各行的 parent_no = " + bomParents);
        assertTrue(bomParents.stream().allMatch(p -> p == null || p.isBlank()),
                "AC-13：无子件成品的 BOM 树页签不应出现任何带父的子行，实际 parent_no 清单 = " + bomParents);
        assertTrue(bomParents.size() <= 1,
                "AC-13：无子件成品的 BOM 树页签最多只有一行根节点，实际 " + bomParents.size() + " 行");
    }

    /** 清理 + 残留核验。 */
    @Test
    @Order(99)
    void t99_cleanup() {
        // 🚨 不用裸 try/finally：destroyAll 抛异常时，finally 里的断言会「顶替」掉原异常
        // （2026-09-09 实测：外键挡住删除，报出来的却是「清理未净」，真正的原因被吞了）。
        RuntimeException destroyErr = null;
        try {
            destroyAll();
        } catch (RuntimeException e) {
            destroyErr = e;
            System.out.println("[S-3·cleanup] destroyAll 抛异常：" + e);
        }
        try {
            assertResidueFree();
        } catch (AssertionError ae) {
            if (destroyErr != null) {
                ae.addSuppressed(destroyErr);
            }
            throw ae;
        }
        if (destroyErr != null) {
            throw new AssertionError("清理过程抛异常（残留核验虽为 0，仍须查明）", destroyErr);
        }
    }
}
