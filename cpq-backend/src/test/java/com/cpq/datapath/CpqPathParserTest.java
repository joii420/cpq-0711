package com.cpq.datapath;

import com.cpq.datapath.ast.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CpqPathParser 单元测试（X.2 阶段）
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>简单路径（5 个）</li>
 *   <li>中文 Sheet（3 个）</li>
 *   <li>单 predicate（=、IN、LIKE 各 2 个）</li>
 *   <li>嵌套（2 层 3 个，3 层 2 个）</li>
 *   <li>边界（空白、特殊字符、大小写敏感各 2 个）</li>
 *   <li>错误用例（5 个）</li>
 * </ul>
 */
@DisplayName("CpqPathParser — 路径解析器单元测试")
class CpqPathParserTest {

    private CpqPathParser parser;

    @BeforeEach
    void setUp() {
        parser = new CpqPathParser();
    }

    // ════════════════════════════════════════════════════════════
    // 简单路径（5 个）
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("简单路径")
    class SimplePaths {

        @Test
        @DisplayName("SP-01: 纯表名（无字段无谓词）")
        void sp01_tableOnly() {
            PathExpression ast = parser.parse("mat_part");
            assertEquals(1, ast.getSegments().size());
            assertEquals("mat_part", ast.getPrimarySegment().getName());
            assertFalse(ast.getPrimarySegment().hasPredicate());
            assertNull(ast.getLeafField());
        }

        @Test
        @DisplayName("SP-02: 表名.字段名（英文）")
        void sp02_tableAndField() {
            PathExpression ast = parser.parse("mat_part.part_no");
            assertEquals(1, ast.getSegments().size());
            assertEquals("mat_part", ast.getPrimarySegment().getName());
            assertNotNull(ast.getLeafField());
            assertEquals("part_no", ast.getLeafField().getName());
        }

        @Test
        @DisplayName("SP-03: 带下划线的长表名")
        void sp03_longTableName() {
            PathExpression ast = parser.parse("mat_customer_part_mapping.hf_part_no");
            assertEquals("mat_customer_part_mapping", ast.getPrimarySegment().getName());
            assertEquals("hf_part_no", ast.getLeafField().getName());
        }

        @Test
        @DisplayName("SP-04: 带外层大括号的路径")
        void sp04_withBraces() {
            PathExpression ast = parser.parse("{mat_part.part_no}");
            assertEquals("mat_part", ast.getPrimarySegment().getName());
            assertEquals("part_no", ast.getLeafField().getName());
        }

        @Test
        @DisplayName("SP-05: 只有表名+谓词，无末尾字段")
        void sp05_tableWithPredicateNoField() {
            PathExpression ast = parser.parse("mat_bom[bom_type='ELEMENT']");
            assertEquals(1, ast.getSegments().size());
            assertTrue(ast.getPrimarySegment().hasPredicate());
            assertNull(ast.getLeafField());
        }
    }

    // ════════════════════════════════════════════════════════════
    // 中文 Sheet 名（3 个）
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("中文 Sheet 名")
    class ChineseSheetNames {

        @Test
        @DisplayName("CN-01: 纯中文 Sheet 名 + 字段")
        void cn01_chineseSheetAndField() {
            PathExpression ast = parser.parse("元素BOM.组成含量");
            assertEquals("元素BOM", ast.getPrimarySegment().getName());
            assertEquals("组成含量", ast.getLeafField().getName());
        }

        @Test
        @DisplayName("CN-02: 中文 Sheet 名 + 等值谓词 + 中文字段")
        void cn02_chineseSheetWithPredicate() {
            PathExpression ast = parser.parse("元素BOM[元素='Ag'].组成含量");
            PathSegment seg = ast.getPrimarySegment();
            assertEquals("元素BOM", seg.getName());

            EqPredicate pred = assertInstanceOf(EqPredicate.class, seg.getPredicate());
            assertEquals("元素", pred.getField());
            assertEquals("Ag", pred.getValue());

            assertEquals("组成含量", ast.getLeafField().getName());
        }

        @Test
        @DisplayName("CN-03: 中文字段名含括号百分号（v5.1 示例）")
        void cn03_chineseFieldWithParenAndPercent() {
            PathExpression ast = parser.parse("元素BOM[元素='Ag'].组成含量(%)");
            assertEquals("组成含量(%)", ast.getLeafField().getName());
        }
    }

    // ════════════════════════════════════════════════════════════
    // 单 predicate：= 谓词（2 个）
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("等值谓词（EQ）")
    class EqPredicates {

        @Test
        @DisplayName("EQ-01: 英文字段等值谓词")
        void eq01_englishEq() {
            PathExpression ast = parser.parse("mat_bom[bom_type='ELEMENT'].element_name");
            EqPredicate pred = assertInstanceOf(EqPredicate.class,
                    ast.getPrimarySegment().getPredicate());
            assertEquals("bom_type", pred.getField());
            assertEquals(EqPredicate.Op.EQ, pred.getOp());
            assertEquals("ELEMENT", pred.getValue());
        }

        @Test
        @DisplayName("EQ-02: 数字等值谓词")
        void eq02_numericEq() {
            PathExpression ast = parser.parse("mat_bom[seq_no=1].input_material_no");
            EqPredicate pred = assertInstanceOf(EqPredicate.class,
                    ast.getPrimarySegment().getPredicate());
            assertEquals("seq_no", pred.getField());
            assertInstanceOf(Number.class, pred.getValue());
            assertEquals(1, ((Number) pred.getValue()).intValue());
        }
    }

    // ════════════════════════════════════════════════════════════
    // IN 谓词（2 个）
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IN 谓词")
    class InPredicates {

        @Test
        @DisplayName("IN-01: 字符串 IN 列表")
        void in01_stringIn() {
            PathExpression ast = parser.parse("mat_part[customer_id IN ('uuid1','uuid2')]");
            InPredicate pred = assertInstanceOf(InPredicate.class,
                    ast.getPrimarySegment().getPredicate());
            assertEquals("customer_id", pred.getField());
            assertEquals(List.of("uuid1", "uuid2"), pred.getValues());
        }

        @Test
        @DisplayName("IN-02: 单元素 IN 列表")
        void in02_singleElementIn() {
            PathExpression ast = parser.parse("mat_part[part_no IN ('3120012574')].part_name");
            InPredicate pred = assertInstanceOf(InPredicate.class,
                    ast.getPrimarySegment().getPredicate());
            assertEquals(List.of("3120012574"), pred.getValues());
            assertEquals("part_name", ast.getLeafField().getName());
        }
    }

    // ════════════════════════════════════════════════════════════
    // LIKE 谓词（2 个）
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("LIKE 谓词")
    class LikePredicates {

        @Test
        @DisplayName("LIKE-01: 前缀通配符")
        void like01_prefixWildcard() {
            PathExpression ast = parser.parse("mat_part[part_no LIKE '%abc%']");
            LikePredicate pred = assertInstanceOf(LikePredicate.class,
                    ast.getPrimarySegment().getPredicate());
            assertEquals("part_no", pred.getField());
            assertEquals("%abc%", pred.getPattern());
        }

        @Test
        @DisplayName("LIKE-02: 后缀通配符 + 字段")
        void like02_suffixWildcard() {
            PathExpression ast = parser.parse("mat_part[part_name LIKE '银%'].part_no");
            LikePredicate pred = assertInstanceOf(LikePredicate.class,
                    ast.getPrimarySegment().getPredicate());
            assertEquals("part_name", pred.getField());
            assertEquals("银%", pred.getPattern());
            assertEquals("part_no", ast.getLeafField().getName());
        }
    }

    // ════════════════════════════════════════════════════════════
    // 嵌套路径（2 层 3 个，3 层 2 个）
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("嵌套路径")
    class NestedPaths {

        @Test
        @DisplayName("NEST-01: 2 层（两个 segment + 末尾字段）")
        void nest01_twoSegments() {
            PathExpression ast = parser.parse("mat_process[hf_part_no='3120012574'].mat_part.part_name");
            assertEquals(2, ast.getSegments().size());
            assertEquals("mat_process", ast.getSegments().get(0).getName());
            assertEquals("mat_part", ast.getSegments().get(1).getName());
            assertEquals("part_name", ast.getLeafField().getName());
        }

        @Test
        @DisplayName("NEST-02: 2 层，两段都有谓词")
        void nest02_twoSegmentsWithPredicates() {
            PathExpression ast = parser.parse(
                    "mat_bom[bom_type='ELEMENT'].mat_part[status_code='Y'].unit_weight");
            assertEquals(2, ast.getSegments().size());
            assertTrue(ast.getSegments().get(0).hasPredicate());
            assertTrue(ast.getSegments().get(1).hasPredicate());
            assertEquals("unit_weight", ast.getLeafField().getName());
        }

        @Test
        @DisplayName("NEST-03: 2 层中文嵌套")
        void nest03_chineseNestedTwoLayers() {
            PathExpression ast = parser.parse("元素BOM[元素='Ag'].生产料号.单重");
            assertEquals(2, ast.getSegments().size());
            assertEquals("元素BOM", ast.getSegments().get(0).getName());
            assertEquals("生产料号", ast.getSegments().get(1).getName());
            assertEquals("单重", ast.getLeafField().getName());
        }

        @Test
        @DisplayName("NEST-04: 3 层（3 段）")
        void nest04_threeSegments() {
            PathExpression ast = parser.parse(
                    "A[k='v'].B[k='v'].C.field");
            assertEquals(3, ast.getSegments().size());
            assertEquals("A", ast.getSegments().get(0).getName());
            assertEquals("B", ast.getSegments().get(1).getName());
            assertEquals("C", ast.getSegments().get(2).getName());
            assertEquals("field", ast.getLeafField().getName());
        }

        @Test
        @DisplayName("NEST-05: 3 层全中文嵌套（v5.1 §3.1 示例变体）")
        void nest05_threeLayerAllChinese() {
            PathExpression ast = parser.parse(
                    "组成件BOM[组成件料号='C001'].元素BOM[元素='Ag'].组成含量");
            assertEquals(2, ast.getSegments().size());
            assertEquals("组成件BOM", ast.getSegments().get(0).getName());
            assertEquals("元素BOM", ast.getSegments().get(1).getName());
            assertEquals("组成含量", ast.getLeafField().getName());
        }
    }

    // ════════════════════════════════════════════════════════════
    // 边界场景
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("边界场景")
    class BoundaryScenarios {

        @Test
        @DisplayName("BOUND-01: 谓词前后有额外空白")
        void bound01_extraWhitespace() {
            PathExpression ast = parser.parse("mat_part [ part_no = '3120012574' ] . part_name");
            assertEquals("mat_part", ast.getPrimarySegment().getName());
            EqPredicate pred = assertInstanceOf(EqPredicate.class,
                    ast.getPrimarySegment().getPredicate());
            assertEquals("3120012574", pred.getValue());
            assertEquals("part_name", ast.getLeafField().getName());
        }

        @Test
        @DisplayName("BOUND-02: 值内含单引号转义（'' 转义为 '）")
        void bound02_singleQuoteEscape() {
            PathExpression ast = parser.parse("mat_part[part_name='it''s a test']");
            EqPredicate pred = assertInstanceOf(EqPredicate.class,
                    ast.getPrimarySegment().getPredicate());
            assertEquals("it's a test", pred.getValue());
        }

        @Test
        @DisplayName("BOUND-03: 大小写敏感 — mat_part 不等于 Mat_Part")
        void bound03_caseSensitive_lower() {
            PathExpression ast1 = parser.parse("mat_part.part_no");
            PathExpression ast2 = parser.parse("Mat_Part.part_no");
            assertNotEquals(
                    ast1.getPrimarySegment().getName(),
                    ast2.getPrimarySegment().getName(),
                    "Identifiers should be case-sensitive"
            );
        }

        @Test
        @DisplayName("BOUND-04: 大小写敏感 — ELEMENT 不等于 element")
        void bound04_caseSensitive_value() {
            PathExpression ast1 = parser.parse("mat_bom[bom_type='ELEMENT']");
            PathExpression ast2 = parser.parse("mat_bom[bom_type='element']");
            EqPredicate p1 = (EqPredicate) ast1.getPrimarySegment().getPredicate();
            EqPredicate p2 = (EqPredicate) ast2.getPrimarySegment().getPredicate();
            assertNotEquals(p1.getValue(), p2.getValue());
        }

        @Test
        @DisplayName("BOUND-05: AND 复合谓词")
        void bound05_andCompoundPredicate() {
            PathExpression ast = parser.parse(
                    "mat_bom[bom_type='ELEMENT' AND hf_part_no='3120012574'].element_name");
            CompoundPredicate pred = assertInstanceOf(CompoundPredicate.class,
                    ast.getPrimarySegment().getPredicate());
            assertEquals(2, pred.getTerms().size());
        }

        @Test
        @DisplayName("BOUND-06: IN 多个值")
        void bound06_inMultipleValues() {
            PathExpression ast = parser.parse(
                    "mat_part[customer_id IN ('u1','u2','u3','u4')]");
            InPredicate pred = assertInstanceOf(InPredicate.class,
                    ast.getPrimarySegment().getPredicate());
            assertEquals(4, pred.getValues().size());
        }
    }

    // ════════════════════════════════════════════════════════════
    // 错误用例（5 个）
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("错误用例（必须抛 CpqPathParseException）")
    class ErrorCases {

        @Test
        @DisplayName("ERR-01: 空字符串抛异常")
        void err01_emptyString() {
            assertThrows(CpqPathParseException.class, () -> parser.parse(""));
        }

        @Test
        @DisplayName("ERR-02: null 抛异常")
        void err02_nullInput() {
            assertThrows(CpqPathParseException.class, () -> parser.parse(null));
        }

        @Test
        @DisplayName("ERR-03: 未闭合方括号")
        void err03_unclosedBracket() {
            assertThrows(CpqPathParseException.class, () -> parser.parse("mat_part[part_no='X'"));
        }

        @Test
        @DisplayName("ERR-04: 纯空白大括号")
        void err04_bracesOnly() {
            assertThrows(CpqPathParseException.class, () -> parser.parse("{}"));
        }

        @Test
        @DisplayName("ERR-05: 多余的点（尾随 .）")
        void err05_trailingDot() {
            assertThrows(CpqPathParseException.class, () -> parser.parse("mat_part."));
        }
    }

    // ════════════════════════════════════════════════════════════
    // 额外覆盖（AST 结构验证）
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AST 结构验证")
    class AstStructureTests {

        @Test
        @DisplayName("AST-01: PathExpression.toString() 正确表示路径")
        void ast01_toStringSimple() {
            PathExpression ast = parser.parse("mat_part.part_no");
            assertEquals("mat_part.part_no", ast.toString());
        }

        @Test
        @DisplayName("AST-02: 带谓词的 toString")
        void ast02_toStringWithPredicate() {
            PathExpression ast = parser.parse("元素BOM[元素='Ag'].组成含量");
            String str = ast.toString();
            assertTrue(str.contains("元素BOM"));
            assertTrue(str.contains("元素"));
            assertTrue(str.contains("Ag"));
        }

        @Test
        @DisplayName("AST-03: equals/hashCode 一致性")
        void ast03_equalsAndHashCode() {
            PathExpression ast1 = parser.parse("mat_part.part_no");
            PathExpression ast2 = parser.parse("mat_part.part_no");
            assertEquals(ast1, ast2);
            assertEquals(ast1.hashCode(), ast2.hashCode());
        }

        @Test
        @DisplayName("AST-04: isSingleSegment 标志")
        void ast04_isSingleSegment() {
            PathExpression single = parser.parse("mat_part.part_no");
            PathExpression multi  = parser.parse("A.B.field");
            assertTrue(single.isSingleSegment());
            assertFalse(multi.isSingleSegment());
        }

        @Test
        @DisplayName("AST-05: NEQ/GTE 比较运算符解析")
        void ast05_comparisonOps() {
            PathExpression astNeq = parser.parse("mat_part[unit_weight!=0]");
            EqPredicate neq = (EqPredicate) astNeq.getPrimarySegment().getPredicate();
            assertEquals(EqPredicate.Op.NEQ, neq.getOp());

            PathExpression astGte = parser.parse("mat_bom[composition_pct>=10]");
            EqPredicate gte = (EqPredicate) astGte.getPrimarySegment().getPredicate();
            assertEquals(EqPredicate.Op.GTE, gte.getOp());
            assertInstanceOf(Number.class, gte.getValue());
            assertEquals(10, ((Number) gte.getValue()).intValue());
        }

        @Test
        @DisplayName("AST-06: GT/LT/LTE 比较运算符解析（BNF 完整覆盖）")
        void ast06_gtLtLteOps() {
            // GT
            PathExpression astGt = parser.parse("mat_bom[composition_pct>5]");
            EqPredicate gt = (EqPredicate) astGt.getPrimarySegment().getPredicate();
            assertEquals(EqPredicate.Op.GT, gt.getOp());
            assertEquals(5, ((Number) gt.getValue()).intValue());

            // LT
            PathExpression astLt = parser.parse("mat_part[unit_weight<0.01]");
            EqPredicate lt = (EqPredicate) astLt.getPrimarySegment().getPredicate();
            assertEquals(EqPredicate.Op.LT, lt.getOp());

            // LTE
            PathExpression astLte = parser.parse("mat_part[unit_weight<=0.01]");
            EqPredicate lte = (EqPredicate) astLte.getPrimarySegment().getPredicate();
            assertEquals(EqPredicate.Op.LTE, lte.getOp());
        }
    }

    // ════════════════════════════════════════════════════════════
    // 补充错误用例（仅空白、缺谓词右值）
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("补充错误用例")
    class SupplementaryErrorCases {

        @Test
        @DisplayName("SERR-01: 仅空白字符串抛异常")
        void serr01_blankOnlyString() {
            assertThrows(CpqPathParseException.class, () -> parser.parse("   "));
        }

        @Test
        @DisplayName("SERR-02: 花括号内仅空白抛异常")
        void serr02_bracesWithBlankContent() {
            assertThrows(CpqPathParseException.class, () -> parser.parse("{   }"));
        }
    }
}
