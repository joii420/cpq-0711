package com.cpq.component;

import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.component.service.ComponentService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD: auditBasicDataPaths() — 全库 BASIC_DATA 字段 default_source.path 与
 * component_sql_view.declared_columns 对齐校验。
 *
 * <p>根因背景(Bug1): 某些字段 path 形如 $ll_view._类型，但该视图 declared_columns 只有「类型」
 * (无下划线前缀)，运行期路径解析失败 → 冻进快照时记 #ERROR[QUERY_ERROR]。
 *
 * <p>审计逻辑只读，不修改任何数据。
 */
@QuarkusTest
@DisplayName("BasicDataPathAuditTest — basic-data path↔视图列名对齐审计")
public class BasicDataPathAuditTest {

    @Inject
    ComponentService componentService;

    // -------------------------------------------------------------------
    // T1: path 列名多余下划线前缀 → 可疑项被检出，suggestion 为去掉下划线
    // -------------------------------------------------------------------
    @Test
    @TestTransaction
    @DisplayName("T1: path=$view._类型 但视图列仅有「类型」→ 检出可疑项,suggestion=去掉下划线")
    void pathWithLeadingUnderscore_columnExistsWithoutUnderscore_isDetected() {
        // seed: 组件
        Component comp = new Component();
        comp.name = "审计测试组件-多余下划线";
        comp.code = "AUDIT-TEST-UNDERSCORE-" + System.nanoTime();
        comp.columnCount = 1;
        comp.status = "ACTIVE";
        // field: BASIC_DATA, default_source.path = "$ll_view._类型"
        comp.fields = """
            [{
              "name": "类型字段",
              "field_type": "BASIC_DATA",
              "default_source": {
                "type": "BASIC_DATA",
                "path": "$ll_view._类型"
              }
            }]
            """;
        comp.formulas = "[]";
        comp.excelColumns = "[]";
        comp.persist();

        // seed: 对应 SQL 视图，declared_columns 含「类型」不含「_类型」
        ComponentSqlView view = new ComponentSqlView();
        view.componentId = comp.id;
        view.sqlViewName = "ll_view";
        view.sqlTemplate = "SELECT 1 AS dummy";
        view.declaredColumns = """
            [{"name":"类型","dataType":"text","nullable":true},
             {"name":"hf_part_no","dataType":"text","nullable":false}]
            """;
        view.requiredVariables = new String[0];
        view.scope = "COMPONENT";
        view.status = "ACTIVE";
        view.persist();

        // 执行审计
        List<Map<String, Object>> result = componentService.auditBasicDataPaths();

        // 断言：该字段被检出
        List<Map<String, Object>> matching = result.stream()
            .filter(r -> comp.id.toString().equals(String.valueOf(r.get("componentId"))))
            .filter(r -> "$ll_view._类型".equals(r.get("path")))
            .toList();

        assertFalse(matching.isEmpty(),
            "应检出 path=$ll_view._类型 的可疑项，但结果为空。全部结果: " + result);

        Map<String, Object> item = matching.get(0);
        assertEquals(comp.code, item.get("componentCode"), "componentCode 应对上");
        assertEquals("ll_view", item.get("viewName"), "viewName 应为 ll_view");
        assertEquals("_类型", item.get("columnName"), "columnName 应为 _类型（原始末段）");

        // suggestion 应建议去掉下划线：含「去掉下划线」提示且含修正后的列名「类型」
        Object suggestion = item.get("suggestion");
        assertNotNull(suggestion, "suggestion 不应为 null（有等价列可建议）");
        String suggStr = suggestion.toString();
        assertTrue(suggStr.contains("去掉下划线") || suggStr.contains("$ll_view.类型"),
            "suggestion 应提示去掉下划线（改为无下划线的等价列），实际: " + suggStr);
    }

    // -------------------------------------------------------------------
    // T2: path 列名匹配视图列 → 不出现在可疑列表（防误报）
    // -------------------------------------------------------------------
    @Test
    @TestTransaction
    @DisplayName("T2: path=$view.列名 与视图列精确匹配 → 不出现在可疑列表")
    void pathMatchingDeclaredColumn_isNotFlagged() {
        // seed: 组件，path 与视图列精确匹配
        Component comp = new Component();
        comp.name = "审计测试组件-正常路径";
        comp.code = "AUDIT-TEST-NORMAL-" + System.nanoTime();
        comp.columnCount = 1;
        comp.status = "ACTIVE";
        comp.fields = """
            [{
              "name": "料号",
              "field_type": "BASIC_DATA",
              "default_source": {
                "type": "BASIC_DATA",
                "path": "$mat_view.hf_part_no"
              }
            }]
            """;
        comp.formulas = "[]";
        comp.excelColumns = "[]";
        comp.persist();

        ComponentSqlView view = new ComponentSqlView();
        view.componentId = comp.id;
        view.sqlViewName = "mat_view";
        view.sqlTemplate = "SELECT 1 AS dummy";
        view.declaredColumns = """
            [{"name":"hf_part_no","dataType":"text","nullable":false},
             {"name":"price","dataType":"numeric","nullable":true}]
            """;
        view.requiredVariables = new String[0];
        view.scope = "COMPONENT";
        view.status = "ACTIVE";
        view.persist();

        List<Map<String, Object>> result = componentService.auditBasicDataPaths();

        // 该组件下的 hf_part_no 不应出现在可疑列表
        boolean hasFalsePositive = result.stream()
            .anyMatch(r -> comp.id.toString().equals(String.valueOf(r.get("componentId")))
                       && "$mat_view.hf_part_no".equals(r.get("path")));

        assertFalse(hasFalsePositive,
            "path 精确匹配视图列时不应被报为可疑项");
    }

    // -------------------------------------------------------------------
    // T3: 视图不存在（组件引用了未配置的视图名）→ 检出 viewNotFound 类型的可疑项
    // -------------------------------------------------------------------
    @Test
    @TestTransaction
    @DisplayName("T3: path 引用的视图在 component_sql_view 中不存在 → 检出 viewNotFound 可疑项")
    void pathReferencesNonExistentView_isDetected() {
        Component comp = new Component();
        comp.name = "审计测试组件-视图缺失";
        comp.code = "AUDIT-TEST-NOVIEW-" + System.nanoTime();
        comp.columnCount = 1;
        comp.status = "ACTIVE";
        comp.fields = """
            [{
              "name": "测试字段",
              "field_type": "BASIC_DATA",
              "default_source": {
                "type": "BASIC_DATA",
                "path": "$ghost_view.col"
              }
            }]
            """;
        comp.formulas = "[]";
        comp.excelColumns = "[]";
        comp.persist();

        // 不 seed 对应 SQL 视图

        List<Map<String, Object>> result = componentService.auditBasicDataPaths();

        List<Map<String, Object>> matching = result.stream()
            .filter(r -> comp.id.toString().equals(String.valueOf(r.get("componentId"))))
            .filter(r -> "$ghost_view.col".equals(r.get("path")))
            .toList();

        assertFalse(matching.isEmpty(),
            "引用不存在视图应被检出为可疑项，结果: " + result);

        Map<String, Object> item = matching.get(0);
        assertEquals("viewNotFound", item.get("issueType"),
            "issueType 应为 viewNotFound");
    }

    // -------------------------------------------------------------------
    // T4: path 不是 $view.col 形态（不含$前缀）→ 跳过，不出现在可疑列表
    // -------------------------------------------------------------------
    @Test
    @TestTransaction
    @DisplayName("T4: default_source.path 非 $view.col 形态 → 跳过，不报为可疑项")
    void nonViewPath_isSkipped() {
        Component comp = new Component();
        comp.name = "审计测试组件-非视图路径";
        comp.code = "AUDIT-TEST-NOVIEW-PATH-" + System.nanoTime();
        comp.columnCount = 1;
        comp.status = "ACTIVE";
        // 路径形如 BNF 表名路径（非 $view 形态），或空
        comp.fields = """
            [{
              "name": "普通路径字段",
              "field_type": "BASIC_DATA",
              "default_source": {
                "type": "BASIC_DATA",
                "path": "mat_part.hf_part_no"
              }
            }]
            """;
        comp.formulas = "[]";
        comp.excelColumns = "[]";
        comp.persist();

        List<Map<String, Object>> result = componentService.auditBasicDataPaths();

        boolean flagged = result.stream()
            .anyMatch(r -> comp.id.toString().equals(String.valueOf(r.get("componentId"))));

        assertFalse(flagged, "非 $view.col 形态的 path 不应出现在可疑列表");
    }

    // -------------------------------------------------------------------
    // T5: path 列名缺下划线，视图列有下划线前缀 → suggestion 建议加下划线
    // -------------------------------------------------------------------
    @Test
    @TestTransaction
    @DisplayName("T5: path=$view.类型 但视图列为「_类型」→ 检出，suggestion=加下划线")
    void pathWithoutUnderscore_columnHasUnderscorePrefix_isSuggested() {
        Component comp = new Component();
        comp.name = "审计测试组件-缺下划线";
        comp.code = "AUDIT-TEST-ADD-US-" + System.nanoTime();
        comp.columnCount = 1;
        comp.status = "ACTIVE";
        comp.fields = """
            [{
              "name": "类型字段2",
              "field_type": "BASIC_DATA",
              "default_source": {
                "type": "BASIC_DATA",
                "path": "$x_view.类型"
              }
            }]
            """;
        comp.formulas = "[]";
        comp.excelColumns = "[]";
        comp.persist();

        ComponentSqlView view = new ComponentSqlView();
        view.componentId = comp.id;
        view.sqlViewName = "x_view";
        view.sqlTemplate = "SELECT 1 AS dummy";
        // 视图列是 _类型（带下划线）
        view.declaredColumns = """
            [{"name":"_类型","dataType":"text","nullable":true}]
            """;
        view.requiredVariables = new String[0];
        view.scope = "COMPONENT";
        view.status = "ACTIVE";
        view.persist();

        List<Map<String, Object>> result = componentService.auditBasicDataPaths();

        List<Map<String, Object>> matching = result.stream()
            .filter(r -> comp.id.toString().equals(String.valueOf(r.get("componentId"))))
            .filter(r -> "$x_view.类型".equals(r.get("path")))
            .toList();

        assertFalse(matching.isEmpty(), "path 列名缺下划线时应被检出");
        Object suggestion = matching.get(0).get("suggestion");
        assertNotNull(suggestion, "有等价列（加下划线）时 suggestion 不应为 null");
        assertTrue(suggestion.toString().contains("_类型"),
            "suggestion 应提示加下划线的等价列，实际: " + suggestion);
    }
}
