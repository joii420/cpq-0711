package com.cpq.datapath;

import com.cpq.datapath.ast.*;
import com.cpq.datapath.grammar.CpqPathLexer;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.ParseCancellationException;

import java.util.ArrayList;
import java.util.List;

/**
 * CPQ 变量路径解析器入口（v5.1 TECH-1 BNF 实现）。
 *
 * <p>将路径字符串（不含外层 {} ）解析为 {@link PathExpression} AST。
 *
 * <p>语义规则（与 grammar 配合）：
 * <ul>
 *   <li>路径由 DOT 分隔的多个 segment 组成</li>
 *   <li>若最后一个 segment 没有谓词，且前面已有至少一个 segment，则作为 leafField</li>
 *   <li>否则，所有 segment 作为 tableRef（查询返回整行）</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 *   CpqPathParser parser = new CpqPathParser();
 *   PathExpression ast = parser.parse("元素BOM[元素='Ag'].组成含量");
 *   PathExpression ast2 = parser.parse("mat_part[customer_id IN ('u1','u2')].part_no");
 * }</pre>
 *
 * <p>注意：此类为无状态工具类，线程安全（每次 parse 创建独立 ANTLR lexer/parser 实例）。
 */
public class CpqPathParser {

    /**
     * 解析路径字符串，返回 AST。
     *
     * @param path 路径字符串，可带或不带外层花括号
     * @return 解析好的 {@link PathExpression}
     * @throws CpqPathParseException 输入为 null/空，或不符合 BNF 语法
     */
    public PathExpression parse(String path) {
        if (path == null || path.isBlank()) {
            throw new CpqPathParseException("Path must not be null or blank");
        }

        // 剥去外层花括号（如有）
        String input = path.trim();
        if (input.startsWith("{") && input.endsWith("}")) {
            input = input.substring(1, input.length() - 1).trim();
        }

        if (input.isBlank()) {
            throw new CpqPathParseException("Path expression inside braces must not be blank");
        }

        try {
            CharStream chars = CharStreams.fromString(input);
            CpqPathLexer lexer = new CpqPathLexer(chars);
            lexer.removeErrorListeners();
            lexer.addErrorListener(ThrowingErrorListener.INSTANCE);

            CommonTokenStream tokens = new CommonTokenStream(lexer);

            com.cpq.datapath.grammar.CpqPathParser antlrParser =
                    new com.cpq.datapath.grammar.CpqPathParser(tokens);
            antlrParser.removeErrorListeners();
            antlrParser.addErrorListener(ThrowingErrorListener.INSTANCE);
            antlrParser.setErrorHandler(new BailErrorStrategy());

            com.cpq.datapath.grammar.CpqPathParser.PathExprContext ctx = antlrParser.pathExpr();

            // 确认输入已完全消费
            if (tokens.LA(1) != Token.EOF) {
                int pos = tokens.LT(1).getStartIndex();
                throw new CpqPathParseException(
                        "Unexpected token '" + tokens.LT(1).getText() + "' after path", pos);
            }

            return buildPathExpression(ctx);
        } catch (CpqPathParseException e) {
            throw e;
        } catch (ParseCancellationException e) {
            // BailErrorStrategy 抛出的异常，包装为业务异常
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            throw new CpqPathParseException("Parse error: " + msg, -1, e);
        } catch (Exception e) {
            throw new CpqPathParseException("Unexpected parse error: " + e.getMessage(), -1, e);
        }
    }

    // ── AST 构建 ──────────────────────────────────────────────────────────

    /**
     * 从 ANTLR parse tree 构建 PathExpression AST。
     *
     * <p>语义规则：
     * - 若最后一个 segment 无谓词，且已有前置 segment，则末尾 segment 视为 leafField。
     * - 否则所有 segment 均为 tableRef。
     */
    private PathExpression buildPathExpression(com.cpq.datapath.grammar.CpqPathParser.PathExprContext ctx) {
        List<com.cpq.datapath.grammar.CpqPathParser.SegmentContext> segCtxs = ctx.segment();

        List<PathSegment> segments = new ArrayList<>();
        FieldReference leafField = null;

        int total = segCtxs.size();

        // 判断末尾 segment 是否应视为 leafField
        // 条件：total >= 2，且最后一个 segment 没有谓词（没有 filterExpr）
        boolean lastIsField = (total >= 2)
                && (segCtxs.get(total - 1).filterExpr() == null);

        int tableSegCount = lastIsField ? total - 1 : total;

        for (int i = 0; i < tableSegCount; i++) {
            segments.add(buildPathSegment(segCtxs.get(i)));
        }

        if (lastIsField) {
            String fieldName = segCtxs.get(total - 1).identifier().getText();
            leafField = new FieldReference(fieldName);
        }

        return new PathExpression(segments, leafField);
    }

    private PathSegment buildPathSegment(com.cpq.datapath.grammar.CpqPathParser.SegmentContext ctx) {
        String name = ctx.identifier().getText();
        Predicate predicate = null;

        com.cpq.datapath.grammar.CpqPathParser.FilterExprContext filterCtx = ctx.filterExpr();
        if (filterCtx != null) {
            predicate = buildFilterExpr(filterCtx);
        }

        return new PathSegment(name, predicate);
    }

    private Predicate buildFilterExpr(com.cpq.datapath.grammar.CpqPathParser.FilterExprContext ctx) {
        List<com.cpq.datapath.grammar.CpqPathParser.FilterTermContext> terms = ctx.filterTerm();
        if (terms.size() == 1) {
            return buildFilterTerm(terms.get(0));
        }
        // 多个条件 → CompoundPredicate(AND)
        List<Predicate> predicates = new ArrayList<>();
        for (com.cpq.datapath.grammar.CpqPathParser.FilterTermContext term : terms) {
            predicates.add(buildFilterTerm(term));
        }
        return new CompoundPredicate(CompoundPredicate.LogicOp.AND, predicates);
    }

    private Predicate buildFilterTerm(com.cpq.datapath.grammar.CpqPathParser.FilterTermContext ctx) {
        String field = ctx.identifier().getText();
        com.cpq.datapath.grammar.CpqPathParser.OpContext opCtx = ctx.op();
        com.cpq.datapath.grammar.CpqPathParser.OperandContext operandCtx = ctx.operand();

        // 判断操作符类型
        if (opCtx.IN() != null) {
            // IN 谓词
            List<Object> values = buildArrayValues(operandCtx);
            return new InPredicate(field, values);
        } else if (opCtx.LIKE() != null) {
            // LIKE 谓词
            String pattern = extractStringValue(operandCtx.literal().stringLiteral().STRING().getText());
            return new LikePredicate(field, pattern);
        } else {
            // 等值/比较谓词
            EqPredicate.Op op = toEqOp(opCtx);
            Object value = buildOperandValue(operandCtx);
            return new EqPredicate(field, op, value);
        }
    }

    private List<Object> buildArrayValues(com.cpq.datapath.grammar.CpqPathParser.OperandContext ctx) {
        List<Object> values = new ArrayList<>();
        if (ctx.literal() != null && ctx.literal().arrayLiteral() != null) {
            // 数组字面量
            com.cpq.datapath.grammar.CpqPathParser.ArrayLiteralContext arr =
                    ctx.literal().arrayLiteral();
            for (com.cpq.datapath.grammar.CpqPathParser.LiteralContext lit : arr.literal()) {
                values.add(extractLiteralValue(lit));
            }
        } else if (ctx.literal() != null) {
            // 单个字面量（作为单元素集合）
            values.add(extractLiteralValue(ctx.literal()));
        }
        return values;
    }

    private Object buildOperandValue(com.cpq.datapath.grammar.CpqPathParser.OperandContext ctx) {
        if (ctx.literal() != null) {
            return extractLiteralValue(ctx.literal());
        } else if (ctx.variableRef() != null) {
            return "$" + ctx.variableRef().identifier().getText();
        } else if (ctx.pathExpr() != null) {
            // 嵌套路径
            return buildPathExpression(ctx.pathExpr());
        }
        throw new CpqPathParseException("Unknown operand type");
    }

    private Object extractLiteralValue(com.cpq.datapath.grammar.CpqPathParser.LiteralContext ctx) {
        if (ctx.stringLiteral() != null) {
            return extractStringValue(ctx.stringLiteral().STRING().getText());
        } else if (ctx.numberLiteral() != null) {
            String numStr = ctx.numberLiteral().NUMBER().getText();
            // 统一使用 Double 避免 Long vs Double 类型不一致问题；
            // SQL 生成器绑定参数时 JDBC 会自动做类型转换
            return Double.parseDouble(numStr);
        } else if (ctx.booleanLiteral() != null) {
            return ctx.booleanLiteral().TRUE() != null;
        } else if (ctx.arrayLiteral() != null) {
            List<Object> arr = new ArrayList<>();
            for (com.cpq.datapath.grammar.CpqPathParser.LiteralContext lit : ctx.arrayLiteral().literal()) {
                arr.add(extractLiteralValue(lit));
            }
            return arr;
        }
        throw new CpqPathParseException("Unknown literal type");
    }

    /**
     * 去掉单引号外壳并还原 '' 转义。
     */
    private String extractStringValue(String raw) {
        // raw 形如 'some value' 或 'it''s'
        String inner = raw.substring(1, raw.length() - 1);
        return inner.replace("''", "'");
    }

    private EqPredicate.Op toEqOp(com.cpq.datapath.grammar.CpqPathParser.OpContext ctx) {
        if (ctx.EQ()  != null) return EqPredicate.Op.EQ;
        if (ctx.NEQ() != null) return EqPredicate.Op.NEQ;
        if (ctx.GT()  != null) return EqPredicate.Op.GT;
        if (ctx.LT()  != null) return EqPredicate.Op.LT;
        if (ctx.GTE() != null) return EqPredicate.Op.GTE;
        if (ctx.LTE() != null) return EqPredicate.Op.LTE;
        throw new CpqPathParseException("Unknown comparison operator");
    }

    // ── 错误监听器 ────────────────────────────────────────────────────────

    /**
     * 严格模式错误监听器：遇到语法错误立即抛出 {@link CpqPathParseException}。
     */
    private static final class ThrowingErrorListener extends BaseErrorListener {

        static final ThrowingErrorListener INSTANCE = new ThrowingErrorListener();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine,
                                String msg, RecognitionException e) {
            throw new CpqPathParseException(
                    "Syntax error at line " + line + ":" + charPositionInLine + " — " + msg,
                    charPositionInLine);
        }
    }
}
