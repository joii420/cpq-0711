package com.cpq.component.service;

import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD (C3): 组件保存时对 default_source.path 列名做软校验。
 *
 * <p>规则：path 形如 {@code $viewName.col} 时，若该组件对应视图 declared_columns
 * 不含 {@code col}，则产生告警（warn log + 返回 warning 列表）；
 * <strong>不阻断保存</strong>（不抛 BusinessException）。
 *
 * <p>校验入口：{@link ComponentService#warnDefaultSourcePaths(java.util.UUID, java.util.List)}
 * 该方法在 create/update 保存链中被调用，返回 warning 字符串列表（供测试断言）；
 * 调用方忽略返回值（只记 LOG），不因此中断保存。
 */
@QuarkusTest
@DisplayName("C3 — 组件保存 default_source.path 列名软校验")
class ComponentSaveDefaultSourcePathWarnTest {

    @Inject
    ComponentService componentService;

    // -------------------------------------------------------------------
    // T1: path 列名不存在于视图 declared_columns → 产生告警，不抛异常
    // -------------------------------------------------------------------
    @Test
    @TestTransaction
    @DisplayName("T1: path=$view.notexist 视图无该列 → warning 非空，保存不阻断")
    void columnNotInView_generatesWarning_doesNotThrow() {
        // 准备组件（需先 persist 以获取 componentId，再调 warnDefaultSourcePaths）
        Component comp = new Component();
        comp.name  = "C3测试-列名不存在";
        comp.code  = "C3-WARN-MISMATCH-" + System.nanoTime();
        comp.columnCount = 1;
        comp.status = "ACTIVE";
        comp.fields = "[]";
        comp.formulas = "[]";
        comp.excelColumns = "[]";
        comp.persist();

        // 准备视图：declared_columns 只有 "hf_part_no"，不含 "notexist"
        ComponentSqlView view = new ComponentSqlView();
        view.componentId = comp.id;
        view.sqlViewName = "c3_test_view";
        view.sqlTemplate = "SELECT 1";
        view.declaredColumns = """
            [{"name":"hf_part_no","dataType":"text","nullable":false}]
            """;
        view.requiredVariables = new String[0];
        view.scope = "COMPONENT";
        view.status = "ACTIVE";
        view.persist();

        // 待校验的 fields：path 指向不存在的列 "notexist"
        List<Map<String, Object>> fields = List.of(
            Map.of(
                "name", "测试字段",
                "field_type", "BASIC_DATA",
                "default_source", Map.of(
                    "type", "BASIC_DATA",
                    "path", "$c3_test_view.notexist"
                )
            )
        );

        // 调用：不应抛异常（软校验）
        List<String> warnings = assertDoesNotThrow(
            () -> componentService.warnDefaultSourcePaths(comp.id, fields),
            "path 列名配错不应抛异常（软校验，不阻断保存）"
        );

        // 应产生至少 1 条告警
        assertFalse(warnings.isEmpty(),
            "列名不在视图 declared_columns 中，应产生告警，实际 warnings=" + warnings);

        // 告警内容应含视图名或列名，便于定位问题
        String allWarnings = String.join(" ", warnings);
        assertTrue(
            allWarnings.contains("c3_test_view") || allWarnings.contains("notexist"),
            "告警应包含视图名或列名以便定位，实际: " + allWarnings
        );
    }

    // -------------------------------------------------------------------
    // T2: path 列名与视图列精确匹配 → 无告警
    // -------------------------------------------------------------------
    @Test
    @TestTransaction
    @DisplayName("T2: path=$view.hf_part_no 列名匹配 → warnings 为空")
    void columnMatchesView_noWarning() {
        Component comp = new Component();
        comp.name  = "C3测试-列名匹配";
        comp.code  = "C3-WARN-MATCH-" + System.nanoTime();
        comp.columnCount = 1;
        comp.status = "ACTIVE";
        comp.fields = "[]";
        comp.formulas = "[]";
        comp.excelColumns = "[]";
        comp.persist();

        ComponentSqlView view = new ComponentSqlView();
        view.componentId = comp.id;
        view.sqlViewName = "c3_match_view";
        view.sqlTemplate = "SELECT 1";
        view.declaredColumns = """
            [{"name":"hf_part_no","dataType":"text","nullable":false},
             {"name":"price","dataType":"numeric","nullable":true}]
            """;
        view.requiredVariables = new String[0];
        view.scope = "COMPONENT";
        view.status = "ACTIVE";
        view.persist();

        List<Map<String, Object>> fields = List.of(
            Map.of(
                "name", "料号",
                "field_type", "BASIC_DATA",
                "default_source", Map.of(
                    "type", "BASIC_DATA",
                    "path", "$c3_match_view.hf_part_no"
                )
            )
        );

        List<String> warnings = componentService.warnDefaultSourcePaths(comp.id, fields);

        assertTrue(warnings.isEmpty(),
            "path 精确匹配视图列时不应产生告警，实际 warnings=" + warnings);
    }

    // -------------------------------------------------------------------
    // T3: path 非 $view.col 形态（BNF 路径） → 跳过，无告警
    // -------------------------------------------------------------------
    @Test
    @TestTransaction
    @DisplayName("T3: path 非 $view.col 形态 → 跳过，warnings 为空")
    void nonViewPath_skipped_noWarning() {
        Component comp = new Component();
        comp.name  = "C3测试-非视图路径";
        comp.code  = "C3-WARN-BNF-" + System.nanoTime();
        comp.columnCount = 1;
        comp.status = "ACTIVE";
        comp.fields = "[]";
        comp.formulas = "[]";
        comp.excelColumns = "[]";
        comp.persist();

        // BNF 路径形态，不含 $ 前缀
        List<Map<String, Object>> fields = List.of(
            Map.of(
                "name", "BNF字段",
                "field_type", "BASIC_DATA",
                "default_source", Map.of(
                    "type", "BASIC_DATA",
                    "path", "mat_part.hf_part_no"
                )
            )
        );

        List<String> warnings = componentService.warnDefaultSourcePaths(comp.id, fields);

        assertTrue(warnings.isEmpty(),
            "非 $view.col 形态的 path 不应产生告警，实际 warnings=" + warnings);
    }

    // -------------------------------------------------------------------
    // T4: 视图不存在于 component_sql_view → 产生 viewNotFound 告警，不抛异常
    // -------------------------------------------------------------------
    @Test
    @TestTransaction
    @DisplayName("T4: path 引用的视图不存在 → warning 非空（viewNotFound），不阻断")
    void viewNotFound_generatesWarning_doesNotThrow() {
        Component comp = new Component();
        comp.name  = "C3测试-视图不存在";
        comp.code  = "C3-WARN-NOVIEW-" + System.nanoTime();
        comp.columnCount = 1;
        comp.status = "ACTIVE";
        comp.fields = "[]";
        comp.formulas = "[]";
        comp.excelColumns = "[]";
        comp.persist();

        // 不创建对应视图
        List<Map<String, Object>> fields = List.of(
            Map.of(
                "name", "幽灵视图字段",
                "field_type", "BASIC_DATA",
                "default_source", Map.of(
                    "type", "BASIC_DATA",
                    "path", "$ghost_c3_view.col"
                )
            )
        );

        List<String> warnings = assertDoesNotThrow(
            () -> componentService.warnDefaultSourcePaths(comp.id, fields),
            "视图不存在不应抛异常（软校验）"
        );

        assertFalse(warnings.isEmpty(),
            "引用不存在视图应产生告警，实际 warnings=" + warnings);
    }

    // -------------------------------------------------------------------
    // T5: fields 中无 default_source → 无告警（正常字段不受影响）
    // -------------------------------------------------------------------
    @Test
    @TestTransaction
    @DisplayName("T5: 字段无 default_source → warnings 为空")
    void fieldWithoutDefaultSource_noWarning() {
        Component comp = new Component();
        comp.name  = "C3测试-无defaultSource";
        comp.code  = "C3-WARN-NODS-" + System.nanoTime();
        comp.columnCount = 1;
        comp.status = "ACTIVE";
        comp.fields = "[]";
        comp.formulas = "[]";
        comp.excelColumns = "[]";
        comp.persist();

        List<Map<String, Object>> fields = List.of(
            Map.of(
                "name", "固定值字段",
                "field_type", "FIXED_VALUE",
                "value", "100"
            )
        );

        List<String> warnings = componentService.warnDefaultSourcePaths(comp.id, fields);

        assertTrue(warnings.isEmpty(),
            "无 default_source 的字段不应产生告警，实际 warnings=" + warnings);
    }
}
