package com.cpq.datapath;

import com.cpq.datapath.ast.PathExpression;
import com.cpq.datapath.sql.PathToSqlGenerator;
import com.cpq.datapath.sql.SchemaContext;
import com.cpq.datapath.sql.SqlAndParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PathToSqlGenerator 单元测试（X.2 阶段）
 *
 * <p>已实现场景：单表查询（含 WHERE）、IN、LIKE、中文 Sheet 映射。
 * <p>X.6 待实现场景：多段嵌套路径 → 断言 UnsupportedOperationException。
 */
@DisplayName("PathToSqlGenerator — SQL 编译器单元测试")
class PathToSqlGeneratorTest {

    private CpqPathParser parser;
    private PathToSqlGenerator generator;
    private SchemaContext ctx;

    @BeforeEach
    void setUp() {
        parser    = new CpqPathParser();
        generator = new PathToSqlGenerator();
        ctx = SchemaContext.builder()
                .tableMapping("元素BOM", "mat_bom")
                .tableMapping("来料BOM", "mat_bom")
                .tableMapping("生产料号", "mat_part")
                .columnMapping("元素BOM", "元素",      "element_name")
                .columnMapping("元素BOM", "组成含量",   "composition_pct")
                .columnMapping("元素BOM", "组成含量(%)", "composition_pct")
                .columnMapping("元素BOM", "bom_type",  "bom_type")
                .columnMapping("元素BOM", "hf_part_no", "hf_part_no")
                .columnMapping("生产料号", "单重",       "unit_weight")
                .build();
    }

    // ════════════════════════════════════════════════════════════
    // 已实现场景
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("已实现：单表查询 + WHERE")
    class ImplementedScenarios {

        @Test
        @DisplayName("SQL-01: 单表 + 等值 WHERE")
        void sql01_singleTableWithEqWhere() {
            PathExpression ast = parser.parse("元素BOM[元素='Ag'].组成含量");
            SqlAndParams result = generator.compile(ast, ctx);

            assertEquals("SELECT composition_pct FROM mat_bom WHERE element_name = ?",
                    result.sql());
            assertEquals(List.of("Ag"), result.params());
            assertEquals(List.of("composition_pct"), result.selectedColumns());
        }

        @Test
        @DisplayName("SQL-02: IN 谓词")
        void sql02_inPredicate() {
            PathExpression ast = parser.parse("mat_part[customer_id IN ('u1','u2')]");
            // 英文路径：mat_part 直接是物理表名，customer_id 直接是物理列名
            SchemaContext engCtx = SchemaContext.builder().build(); // 空映射，使用 ASCII 透传
            SqlAndParams result = generator.compile(ast, engCtx);

            assertTrue(result.sql().contains("IN (?, ?)"),
                    "SQL should contain IN (?, ?), actual: " + result.sql());
            assertEquals(List.of("u1", "u2"), result.params());
        }

        @Test
        @DisplayName("SQL-03: LIKE 谓词")
        void sql03_likePredicate() {
            PathExpression ast = parser.parse("mat_part[part_no LIKE '%abc%']");
            SchemaContext engCtx = SchemaContext.builder().build();
            SqlAndParams result = generator.compile(ast, engCtx);

            assertTrue(result.sql().contains("LIKE ?"),
                    "SQL should contain LIKE ?, actual: " + result.sql());
            assertEquals(List.of("%abc%"), result.params());
        }

        @Test
        @DisplayName("SQL-04: 中文 Sheet 名映射到物理表名")
        void sql04_chineseTableMapping() {
            PathExpression ast = parser.parse("元素BOM[bom_type='ELEMENT'].组成含量");
            SqlAndParams result = generator.compile(ast, ctx);

            assertTrue(result.sql().startsWith("SELECT composition_pct FROM mat_bom"),
                    "SQL should map 元素BOM → mat_bom, actual: " + result.sql());
            assertEquals("ELEMENT", result.params().get(0));
        }

        @Test
        @DisplayName("SQL-05: 无谓词单表全列查询")
        void sql05_noPredicateSelectStar() {
            PathExpression ast = parser.parse("mat_part");
            SchemaContext engCtx = SchemaContext.builder().build();
            SqlAndParams result = generator.compile(ast, engCtx);

            assertEquals("SELECT * FROM mat_part", result.sql());
            assertTrue(result.params().isEmpty());
            assertEquals(List.of("*"), result.selectedColumns());
        }

        @Test
        @DisplayName("SQL-06: AND 复合谓词")
        void sql06_andCompoundPredicate() {
            PathExpression ast = parser.parse(
                    "元素BOM[bom_type='ELEMENT' AND hf_part_no='3120012574'].组成含量");
            SqlAndParams result = generator.compile(ast, ctx);

            assertTrue(result.sql().contains("AND"),
                    "SQL should contain AND, actual: " + result.sql());
            assertEquals(2, result.params().size());
            assertEquals("ELEMENT", result.params().get(0));
            assertEquals("3120012574", result.params().get(1));
        }

        @Test
        @DisplayName("SQL-07: toDebugString 正确替换参数")
        void sql07_toDebugString() {
            PathExpression ast = parser.parse("元素BOM[元素='Ag'].组成含量");
            SqlAndParams result = generator.compile(ast, ctx);
            String debug = result.toDebugString();
            assertTrue(debug.contains("'Ag'"), "Debug string should show parameter: " + debug);
        }
    }

    // ════════════════════════════════════════════════════════════
    // SQL 注入安全专项测试
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SQL 注入安全专项（参数化验证）")
    class SqlInjectionSecurity {

        @Test
        @DisplayName("SEC-01: 谓词值含 OR 1=1 注入尝试 — 应作为参数传入而非拼入 SQL")
        void sec01_orInjectionInValue() {
            // 攻击者尝试：mat_part[part_no='x' OR 1=1 --']
            // grammar 会将 "x' OR 1=1 --'" 当作未终止的字符串而拒绝
            // 合法的、已转义的注入场景：值本身含有 OR 字样但作为参数绑定
            PathExpression ast = parser.parse("mat_part[part_no='x'' OR 1=1 --']");
            SchemaContext engCtx = SchemaContext.builder().build();
            SqlAndParams result = generator.compile(ast, engCtx);

            // 关键断言：SQL 模板只含 ?，不含裸露的 OR 1=1
            assertTrue(result.sql().contains("?"),
                    "SQL must use parameterized placeholder, actual: " + result.sql());
            assertFalse(result.sql().contains("OR 1=1"),
                    "Injection string must not appear in SQL template, actual: " + result.sql());
            // 参数值包含原始字符串（经 '' 反转义后含单引号）
            assertEquals(1, result.params().size());
            String paramValue = (String) result.params().get(0);
            assertTrue(paramValue.contains("OR 1=1"),
                    "Injection string should be safely bound as parameter value: " + paramValue);
        }

        @Test
        @DisplayName("SEC-02: 谓词值含 SQL 注释符 -- 应安全绑定为参数")
        void sec02_commentInjectionInValue() {
            // 值包含 -- 注释符号，应完整作为参数绑定，不影响 SQL 结构
            PathExpression ast = parser.parse("mat_part[part_name='silver -- comment']");
            SchemaContext engCtx = SchemaContext.builder().build();
            SqlAndParams result = generator.compile(ast, engCtx);

            assertTrue(result.sql().contains("?"),
                    "SQL must use parameterized placeholder, actual: " + result.sql());
            assertFalse(result.sql().contains("--"),
                    "SQL comment marker must not appear in SQL template, actual: " + result.sql());
            assertEquals("silver -- comment", result.params().get(0));
        }
    }

    // ════════════════════════════════════════════════════════════
    // X.6 待完成场景 — 断言抛 UnsupportedOperationException
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("X.6 待完成场景（断言 UnsupportedOperationException）")
    class X6PendingScenarios {

        @Test
        @DisplayName("X6-01: 多段嵌套路径（2 段）抛 UnsupportedOperationException")
        void x6_01_multiSegmentPath() {
            PathExpression ast = parser.parse("mat_process[hf_part_no='3120012574'].mat_part.part_name");
            SchemaContext engCtx = SchemaContext.builder().build();

            assertThrows(UnsupportedOperationException.class,
                    () -> generator.compile(ast, engCtx),
                    "Multi-segment paths should throw UnsupportedOperationException in X.2");
        }

        @Test
        @DisplayName("X6-02: 3 层嵌套路径抛 UnsupportedOperationException")
        void x6_02_threeLayerPath() {
            PathExpression ast = parser.parse("A[k='v'].B[k='v'].C.field");
            SchemaContext engCtx = SchemaContext.builder().build();

            assertThrows(UnsupportedOperationException.class,
                    () -> generator.compile(ast, engCtx),
                    "3-layer nested paths should throw UnsupportedOperationException in X.2");
        }
    }
}
