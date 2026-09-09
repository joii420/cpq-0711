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

    /** TC-18（回归）：非树页签与 parent_no 无关 → 任何情况都不拦。 */
    @Test
    void nonTreeTab_neverThrows() {
        assertDoesNotThrow(() -> BomTreeRenderService.assertParentNoPresent("COMP-X", false, 5, 5));
        assertDoesNotThrow(() -> BomTreeRenderService.assertParentNoPresent("COMP-X", false, 0, 0));
    }

    // ── task-260909 B-1：判据由「值为 NULL」改成「列不存在」 ───────────────────────────
    //
    // 🚨 这两组用例是本次修复的**唯一自动化门禁**。上面 TC-15~18 只吃计数，
    //    换判据后计数怎么来的它们看不见 —— 也就是说：把 lacksParentNoColumn 改回
    //    `get("parent_no") == null`，上面四个用例照样全绿。真正能红的只有下面这两组。

    /**
     * 🔴 <b>缺陷复现用例</b>：全是<b>根行</b>（{@code parent_no} 列存在、值全为 NULL）——
     * 这是取数配置器自 2026-09-07 起生成的树页签 SQL 的<b>正常形态</b>（根分支恒 NULL），
     * 闭包无边时 100% 的行都是它 ⇒ 旧判据必然把它误判成"缺列"并整卡红框。
     * 新判据必须判成「列在」。
     */
    @Test
    void rootRowsWithNullParentNo_areNotCountedAsMissingColumn() {
        java.util.Map<String, Object> rootRow = new java.util.HashMap<>();
        rootRow.put("material_no", "300001");
        rootRow.put("parent_no", null);          // ← 列在、值为 NULL（根行）
        rootRow.put("hf_part_no", "300001");

        assertFalse(BomTreeRenderService.lacksParentNoColumn(rootRow),
                "根行的 parent_no 是合法 NULL，不能算作『视图没输出这一列』");

        // 端到端语义：5 行全是根行 ⇒ missingParentCol = 0 ⇒ 守卫不抛
        int kept = 5, missingParentCol = 0;
        for (int i = 0; i < kept; i++) {
            if (BomTreeRenderService.lacksParentNoColumn(rootRow)) missingParentCol++;
        }
        assertEquals(0, missingParentCol);
        assertDoesNotThrow(() -> BomTreeRenderService.assertParentNoPresent("COMP-X", true, 5, 0));
    }

    /** 真·缺列（视图压根没输出 parent_no）→ 判成缺列，守卫仍然抛 400。护栏没被放宽。 */
    @Test
    void missingParentNoColumn_stillThrows() {
        java.util.Map<String, Object> noColRow = new java.util.HashMap<>();
        noColRow.put("material_no", "300001");
        noColRow.put("hf_part_no", "300001");    // ← 就是没有 parent_no 这个键

        assertTrue(BomTreeRenderService.lacksParentNoColumn(noColRow));
        assertTrue(BomTreeRenderService.lacksParentNoColumn(null));

        int counted = 0;
        for (int i = 0; i < 5; i++) {
            if (BomTreeRenderService.lacksParentNoColumn(noColRow)) counted++;
        }
        final int missingParentCol = counted;
        assertEquals(5, missingParentCol);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> BomTreeRenderService.assertParentNoPresent("COMP-X", true, 5, missingParentCol));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("parent_no"), ex.getMessage());
    }

    // ── task-260909 B-6：非树页签行归属键 hf_part_no（material_no 仅存量回落） ──────────

    /** ① 只有 hf_part_no（配置器产的 41/42 个视图都是这个形态）→ 按 hf_part_no 分桶。 */
    @Test
    void flatBucketKey_prefersHfPartNo() {
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("hf_part_no", "300013");
        row.put("element_code", "Cu");
        assertEquals("300013", BomTreeRenderService.flatBucketKey(row));

        // 两个键都在时，hf_part_no 优先（不是"谁非空取谁"的随机顺序）
        java.util.Map<String, Object> both = new java.util.HashMap<>();
        both.put("hf_part_no", "300013");
        both.put("material_no", "S0001");
        assertEquals("300013", BomTreeRenderService.flatBucketKey(both));
    }

    /** ② 只有 material_no（全库唯一 1 个非配置器视图）→ 回落 material_no，存量保护。 */
    @Test
    void flatBucketKey_fallsBackToMaterialNo() {
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("material_no", "S0001");
        assertEquals("S0001", BomTreeRenderService.flatBucketKey(row));

        // hf_part_no 键在但值为 NULL / 空串 / 纯空白时同样回落（「键存在」≠「有值」）
        for (Object blank : new Object[]{null, "", "   ", "\t"}) {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("hf_part_no", blank);
            m.put("material_no", "S0001");
            assertEquals("S0001", BomTreeRenderService.flatBucketKey(m),
                    "hf_part_no=[" + blank + "] 属于『没有值』，必须回落 material_no");
        }
    }

    /** ③ 两者皆无 → null，该行落选且不计入 kept（AC-15③）。 */
    @Test
    void flatBucketKey_returnsNullWhenNeitherKeyPresent() {
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("unit_price", "1.23");
        assertNull(BomTreeRenderService.flatBucketKey(row));
        assertNull(BomTreeRenderService.flatBucketKey(new java.util.HashMap<>()));
        assertNull(BomTreeRenderService.flatBucketKey(null));

        // 键在但值全为空白 ⇒ 同样落选。🚫 绝不能返回空串：那会让所有空行挤进同一个 "" 假桶，
        // kept 看着是满的、行却一条都配不上卡片料号（静默落选，守卫也不会响）。
        java.util.Map<String, Object> blanks = new java.util.HashMap<>();
        blanks.put("hf_part_no", "");
        blanks.put("material_no", "   ");
        assertNull(BomTreeRenderService.flatBucketKey(blanks));
    }
}
