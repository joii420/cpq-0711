package com.cpq.component.service;

import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.datasource.sqlview.SpineKeysMacro;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 3 等价护栏：验证 {@link ComponentDriverService#eligibleForQuoteBucket} 闸门精确，
 * 以及 {@link ComponentDriverService#eligibleForBomUnion} 重构（抽 viewHasNoRowDimension helper）
 * 后对同一组件的返回值与内联判定逐位不变。
 *
 * <p>策略：只读真实数据库（@QuarkusTest），不写 DB，不需回滚。
 *
 * <p>白盒断言原则（Bug B 安全闸门）：
 * <ul>
 *   <li>eligible=true 的组件 → 视图确实不含 :lineItemId/quotation_line_item_id/:spineKeys + 非 composite + 非 EXCEL；
 *   <li>eligible=false 的组件 → 命中的闸门标记说明（EXCEL/composite/lineItemId/spineKeys/no_view）；
 *   <li>eligibleForBomUnion 重构护栏：对若干核价组件，重构后返回值 == 手写内联判定（bomRecursiveExpand && viewHasNoRowDimension）。
 * </ul>
 */
@QuarkusTest
class EligibleForQuoteBucketTest {

    @Inject
    ComponentDriverService cds;

    @Inject
    EntityManager em;

    // ────────────────────────────────────────────────────────────
    // Helper: 取库里真实报价模板(8be8cc2c 罗克韦尔)的 driver 组件 ID
    // ────────────────────────────────────────────────────────────

    private static final String QUOTE_TEMPLATE_ID = "8be8cc2c-2c1f-45f7-b401-d0e3b15abca2";

    @Transactional
    List<UUID> loadQuoteDriverComponentIds() {
        @SuppressWarnings("unchecked")
        List<UUID> ids = em.createNativeQuery(
                "SELECT tc.component_id FROM template_component tc " +
                "JOIN component c ON c.id = tc.component_id " +
                "WHERE tc.template_id = :tid AND c.data_driver_path IS NOT NULL AND c.data_driver_path != '' " +
                "ORDER BY tc.sort_order")
                .setParameter("tid", UUID.fromString(QUOTE_TEMPLATE_ID))
                .getResultList();
        return ids;
    }

    @Transactional
    List<UUID> loadAllComponentIds() {
        @SuppressWarnings("unchecked")
        List<UUID> ids = em.createNativeQuery(
                "SELECT id FROM component ORDER BY created_at LIMIT 100")
                .getResultList();
        return ids;
    }

    // ────────────────────────────────────────────────────────────
    // 白盒辅助：内联等价判定（不依赖 helper，独立验证闸门语义）
    // ────────────────────────────────────────────────────────────

    /**
     * 内联等价判定 eligibleForQuoteBucket：不调用 service，直接查 DB 重现闸门逻辑。
     * 用于白盒断言"service 判定 == 内联判定"。
     */
    @Transactional
    boolean inlineEligibleForQuoteBucket(UUID componentId) {
        Component c = Component.findById(componentId);
        if (c == null) return false;
        if ("EXCEL".equals(c.componentType)) return false;               // ① 非 EXCEL
        String path = c.dataDriverPath;
        if (path == null || path.isBlank()) return false;                // ② 路径非空
        if (path.contains("v_composite_child_") || path.contains("composite_child_")) return false; // ③
        // 提取 $view 名（内联 extractSqlViewName 逻辑）
        String viewName = extractViewName(path);
        if (viewName == null) return false;                              // ② $view 形态
        ComponentSqlView v = ComponentSqlView
                .find("componentId = ?1 and sqlViewName = ?2", componentId, viewName).firstResult();
        if (v == null || v.sqlTemplate == null) return false;
        String tpl = v.sqlTemplate;
        if (SpineKeysMacro.containsMacro(tpl)) return false;            // ④ 无 :spineKeys
        if (tpl.contains(":lineItemId") || tpl.contains("quotation_line_item_id")) return false; // ④ 无行维度
        return true;
    }

    /**
     * 内联等价判定 eligibleForBomUnion（重构护栏）：bomRecursiveExpand=true && viewHasNoRowDimension。
     */
    @Transactional
    boolean inlineEligibleForBomUnion(UUID componentId) {
        Component c = Component.findById(componentId);
        if (c == null) return false;
        if (!Boolean.TRUE.equals(c.bomRecursiveExpand)) return false;   // ①
        // 以下复用 viewHasNoRowDimension 相同逻辑
        String path = c.dataDriverPath;
        if (path == null || path.isBlank()) return false;
        if (path.contains("v_composite_child_") || path.contains("composite_child_")) return false;
        String viewName = extractViewName(path);
        if (viewName == null) return false;
        ComponentSqlView v = ComponentSqlView
                .find("componentId = ?1 and sqlViewName = ?2", componentId, viewName).firstResult();
        if (v == null || v.sqlTemplate == null) return false;
        String tpl = v.sqlTemplate;
        if (SpineKeysMacro.containsMacro(tpl)) return false;
        if (tpl.contains(":lineItemId") || tpl.contains("quotation_line_item_id")) return false;
        return true;
    }

    /** 从 driver path 提取视图名（与 ComponentDriverService.extractSqlViewName 逻辑相同，测试侧独立实现）。 */
    private static String extractViewName(String path) {
        if (path == null) return null;
        String s = path.trim();
        if (s.startsWith("$$")) {
            int dot = s.indexOf('.', 2);
            if (dot < 0) return null;
            String rest = s.substring(dot + 1);
            int bracket = rest.indexOf('[');
            return bracket >= 0 ? rest.substring(0, bracket) : rest;
        }
        if (s.startsWith("$")) {
            String rest = s.substring(1);
            int bracket = rest.indexOf('[');
            return bracket >= 0 ? rest.substring(0, bracket) : rest;
        }
        return null;
    }

    // ────────────────────────────────────────────────────────────
    // TC-1：报价主组件应全部判 eligible=true
    // ────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void quoteDriverComponents_shouldAllBeEligible() {
        List<UUID> ids = loadQuoteDriverComponentIds();
        Assumptions.assumeTrue(!ids.isEmpty(),
                "库中无模板 " + QUOTE_TEMPLATE_ID + " 的 driver 组件，跳过");

        int eligibleCount = 0;
        for (UUID cid : ids) {
            boolean result = cds.eligibleForQuoteBucket(cid);
            Component c = Component.findById(cid);
            String name = c != null ? c.name : String.valueOf(cid);
            String path = c != null ? c.dataDriverPath : null;

            // 白盒：service 判定 == 内联判定（断言等价性）
            boolean inlineResult = inlineEligibleForQuoteBucket(cid);
            assertEquals(inlineResult, result,
                    "service 判定与内联判定不一致: comp=" + name + "(" + cid + ")");

            // 断言这些标准报价视图组件应判 eligible=true
            assertTrue(result,
                    "报价主组件 [" + name + "] path=" + path + " 应判 eligible=true，" +
                    "实为 false（视图含 lineItemId/spineKeys/composite？）");
            eligibleCount++;
        }
        System.out.printf("=== TC-1 QUOTE_DRIVER eligible=%d / total=%d ===%n", eligibleCount, ids.size());
    }

    // ────────────────────────────────────────────────────────────
    // TC-2：白盒断言 eligible 组件的视图确实不含行维度标记
    // ────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void eligibleComponents_viewMustHaveNoRowDimension() {
        List<UUID> ids = loadQuoteDriverComponentIds();
        Assumptions.assumeTrue(!ids.isEmpty(), "无 driver 组件，跳过");

        for (UUID cid : ids) {
            if (!cds.eligibleForQuoteBucket(cid)) continue; // 仅校验 eligible 的
            Component c = Component.findById(cid);
            assertNotNull(c, "组件 " + cid + " 应存在");
            assertNotEquals("EXCEL", c.componentType, "eligible 组件不应是 EXCEL 类型: " + c.name);
            String path = c.dataDriverPath;
            assertNotNull(path, "eligible 组件 " + c.name + " 应有 dataDriverPath");
            assertFalse(path.contains("v_composite_child_") || path.contains("composite_child_"),
                    "eligible 组件 " + c.name + " path 不应含 composite_child_");

            String viewName = extractViewName(path);
            assertNotNull(viewName, "eligible 组件 " + c.name + " path 应是 $view 形态");

            ComponentSqlView v = ComponentSqlView
                    .find("componentId = ?1 and sqlViewName = ?2", cid, viewName).firstResult();
            assertNotNull(v, "eligible 组件 " + c.name + " 对应 ComponentSqlView 应存在");
            assertNotNull(v.sqlTemplate, "eligible 组件 " + c.name + " sql_template 不应为 null");
            assertFalse(SpineKeysMacro.containsMacro(v.sqlTemplate),
                    "eligible 组件 " + c.name + " sql_template 不应含 :spineKeys");
            assertFalse(v.sqlTemplate.contains(":lineItemId"),
                    "eligible 组件 " + c.name + " sql_template 不应含 :lineItemId");
            assertFalse(v.sqlTemplate.contains("quotation_line_item_id"),
                    "eligible 组件 " + c.name + " sql_template 不应含 quotation_line_item_id");
        }
        System.out.println("=== TC-2 白盒视图内容校验通过 ===");
    }

    // ────────────────────────────────────────────────────────────
    // TC-3：EXCEL 类型组件应判 eligible=false（闸门① 命中）
    // ────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void excelComponent_shouldBeIneligible() {
        @SuppressWarnings("unchecked")
        List<UUID> excelIds = em.createNativeQuery(
                "SELECT id FROM component WHERE component_type = 'EXCEL' LIMIT 5")
                .getResultList();
        Assumptions.assumeTrue(!excelIds.isEmpty(), "库中无 EXCEL 类型组件，跳过");

        for (UUID cid : excelIds) {
            boolean result = cds.eligibleForQuoteBucket(cid);
            assertFalse(result, "EXCEL 组件 " + cid + " 应判 eligible=false（闸门① 命中）");
            // 白盒：service == 内联
            assertEquals(inlineEligibleForQuoteBucket(cid), result,
                    "service 判定与内联判定不一致: " + cid);
        }
        System.out.println("=== TC-3 EXCEL 组件 eligible=false 通过 (n=" + excelIds.size() + ") ===");
    }

    // ────────────────────────────────────────────────────────────
    // TC-4：composite_child_ 路径组件应判 eligible=false（闸门③ 命中）
    // ────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void compositeChildPath_shouldBeIneligible() {
        @SuppressWarnings("unchecked")
        List<UUID> compIds = em.createNativeQuery(
                "SELECT id FROM component WHERE data_driver_path LIKE '%composite_child%' LIMIT 5")
                .getResultList();
        if (compIds.isEmpty()) {
            System.out.println("=== TC-4 库中无 composite_child 路径组件，用虚拟断言覆盖 ===");
            // 无数据则跳过（Assumptions.skip 不断言失败）
            Assumptions.assumeTrue(false, "库中无 composite_child 路径组件，跳过");
            return;
        }
        for (UUID cid : compIds) {
            boolean result = cds.eligibleForQuoteBucket(cid);
            assertFalse(result, "composite_child 路径组件 " + cid + " 应判 eligible=false（闸门③ 命中）");
        }
        System.out.println("=== TC-4 composite_child 组件 eligible=false 通过 (n=" + compIds.size() + ") ===");
    }

    // ────────────────────────────────────────────────────────────
    // TC-5：无 $view 路径（空路径或普通字符串）组件应判 false（闸门②）
    // ────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void noViewPath_shouldBeIneligible() {
        // 找 data_driver_path 为空或不以 $ 开头的非 EXCEL 组件
        @SuppressWarnings("unchecked")
        List<UUID> noPathIds = em.createNativeQuery(
                "SELECT id FROM component WHERE component_type != 'EXCEL' " +
                "AND (data_driver_path IS NULL OR data_driver_path = '' OR data_driver_path NOT LIKE '$%') " +
                "LIMIT 5")
                .getResultList();
        Assumptions.assumeTrue(!noPathIds.isEmpty(), "库中所有非 EXCEL 组件都有 $view 路径，跳过 TC-5");

        for (UUID cid : noPathIds) {
            boolean result = cds.eligibleForQuoteBucket(cid);
            assertFalse(result, "无 $view 路径组件 " + cid + " 应判 eligible=false（闸门② 命中）");
            assertEquals(inlineEligibleForQuoteBucket(cid), result,
                    "service 判定与内联判定不一致: " + cid);
        }
        System.out.println("=== TC-5 无 $view 路径 eligible=false 通过 (n=" + noPathIds.size() + ") ===");
    }

    // ────────────────────────────────────────────────────────────
    // TC-6：不存在的 componentId 应返回 false（防御）
    // ────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void nullComponent_shouldReturnFalse() {
        UUID nonExistent = UUID.fromString("00000000-dead-beef-0000-000000000000");
        assertFalse(cds.eligibleForQuoteBucket(nonExistent),
                "不存在的 componentId 应返回 false（防御，不抛异常）");
    }

    // ────────────────────────────────────────────────────────────
    // TC-7：eligibleForBomUnion 重构护栏 —— 对所有组件，重构后 == 内联判定
    // ────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void eligibleForBomUnion_refactoredEqualsInline() {
        List<UUID> allIds = loadAllComponentIds();
        Assumptions.assumeTrue(!allIds.isEmpty(), "库中无组件，跳过");

        int checked = 0;
        List<String> mismatches = new ArrayList<>();
        for (UUID cid : allIds) {
            boolean service = cds.eligibleForBomUnion(cid);
            boolean inline  = inlineEligibleForBomUnion(cid);
            if (service != inline) {
                Component c = Component.findById(cid);
                mismatches.add(String.format("comp=%s(%s) service=%b inline=%b",
                        c != null ? c.name : "?", cid, service, inline));
            }
            checked++;
        }
        assertTrue(mismatches.isEmpty(),
                "eligibleForBomUnion 重构后与内联判定不一致（" + mismatches.size() + " 个）:\n"
                + String.join("\n", mismatches));
        System.out.printf("=== TC-7 eligibleForBomUnion 重构护栏: checked=%d, mismatches=0 ===%n", checked);
    }

    // ────────────────────────────────────────────────────────────
    // TC-8：eligibleForBomUnion 对 bom_recursive_expand=true 的核价组件应正确判定
    // ────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void bomRecursiveComponents_eligibleForBomUnionIsCorrect() {
        @SuppressWarnings("unchecked")
        List<UUID> recursiveIds = em.createNativeQuery(
                "SELECT c.id FROM component c " +
                "WHERE c.bom_recursive_expand = true " +
                "AND c.data_driver_path IS NOT NULL AND c.data_driver_path != '' " +
                "LIMIT 10")
                .getResultList();
        Assumptions.assumeTrue(!recursiveIds.isEmpty(), "库中无 bom_recursive_expand=true 组件，跳过");

        for (UUID cid : recursiveIds) {
            boolean service = cds.eligibleForBomUnion(cid);
            boolean inline  = inlineEligibleForBomUnion(cid);
            assertEquals(inline, service,
                    "eligibleForBomUnion 重构对 recursive 组件 " + cid + " 结果不一致");
            // 这些组件 bomRecursiveExpand=true → eligibleForBomUnion 的结果只取决于 viewHasNoRowDimension
            Component c = Component.findById(cid);
            System.out.printf("  [TC-8] comp=%s bom_recursive=true eligible=%b%n",
                    c != null ? c.name : cid, service);
        }
        System.out.println("=== TC-8 BOM recursive 组件 eligibleForBomUnion 重构护栏通过 ===");
    }

    // ────────────────────────────────────────────────────────────
    // TC-9：service 全覆盖对比（全库 100 组件，service == 内联，无例外）
    // ────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void allComponents_quoteBucketEqualsInline() {
        List<UUID> allIds = loadAllComponentIds();
        Assumptions.assumeTrue(!allIds.isEmpty(), "库中无组件，跳过");

        List<String> mismatches = new ArrayList<>();
        int eligible = 0, ineligible = 0;
        for (UUID cid : allIds) {
            boolean service = cds.eligibleForQuoteBucket(cid);
            boolean inline  = inlineEligibleForQuoteBucket(cid);
            if (service != inline) {
                Component c = Component.findById(cid);
                mismatches.add(String.format("comp=%s(%s) service=%b inline=%b",
                        c != null ? c.name : "?", cid, service, inline));
            }
            if (service) eligible++; else ineligible++;
        }
        assertTrue(mismatches.isEmpty(),
                "eligibleForQuoteBucket service 与内联判定不一致（" + mismatches.size() + " 个）:\n"
                + String.join("\n", mismatches));
        System.out.printf("=== TC-9 全库 eligible=%d ineligible=%d mismatches=0 ===%n",
                eligible, ineligible);
    }
}
