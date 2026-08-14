package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0814 D-3（原 {@code BL-0169}）—— 树页签 {@code $view} 缺 {@code parent_no} 的显式检出。
 *
 * <p>纯 JUnit（无 {@code @QuarkusTest}）：判据已从 {@code render()} 深层循环里抽成
 * {@link BomTreeRenderService#assertParentNoPresent} 静态方法，四个边界可直接覆盖，
 * 不必为了测一个 if 搭一整套 driver/$view/DB 夹具。
 *
 * <p><b>本组用例的重点是三条"不该拦"</b>：改动把一行 {@code LOG.warnf} 升级成了抛异常，
 * 触发条件一旦被放宽就会误伤正常渲染，比原来的静默更糟。
 */
class BomTreeParentNoGuardTest {

    /** TC-15（AC-11）：树页签 + 有行 + 全部行缺 parent_no = 配置漏列 → 必须抛。 */
    @Test
    void treeTabWithAllRowsMissingParentNo_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> BomTreeRenderService.assertParentNoPresent("COMP-X", true, 5, 5));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("parent_no"), ex.getMessage());
        assertTrue(ex.getMessage().contains("COMP-X"), "须点名组件: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("满屏空行"), "须说清后果: " + ex.getMessage());
    }

    /**
     * TC-16（AC-11 边界）：只有<b>部分</b>行缺 parent_no → 不拦。
     * 那是数据问题（某些料号确实没父件）不是配置问题，拦了就是误伤。
     */
    @Test
    void treeTabWithPartialMissingParentNo_doesNotThrow() {
        assertDoesNotThrow(() -> BomTreeRenderService.assertParentNoPresent("COMP-X", true, 5, 4));
        assertDoesNotThrow(() -> BomTreeRenderService.assertParentNoPresent("COMP-X", true, 5, 1));
        assertDoesNotThrow(() -> BomTreeRenderService.assertParentNoPresent("COMP-X", true, 5, 0));
    }

    /** TC-17（AC-11 边界）：一行都没留下（kept == 0）→ 不拦，那是"无数据"不是"缺列"。 */
    @Test
    void treeTabWithZeroKeptRows_doesNotThrow() {
        assertDoesNotThrow(() -> BomTreeRenderService.assertParentNoPresent("COMP-X", true, 0, 0));
    }

    /** TC-18（回归）：非树页签按 material_no 分桶，与 parent_no 无关 → 任何情况都不拦。 */
    @Test
    void nonTreeTab_neverThrows() {
        assertDoesNotThrow(() -> BomTreeRenderService.assertParentNoPresent("COMP-X", false, 5, 5));
        assertDoesNotThrow(() -> BomTreeRenderService.assertParentNoPresent("COMP-X", false, 0, 0));
    }
}
