package com.cpq.formula.function.business;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/**
 * EXCHANGE(amount, fromCurrency, toCurrency, date?) — 汇率换算。
 *
 * <p>v5.1 §3.2 货币函数。查询 exchange_rate 表获取汇率。
 *
 * <p>签名约定（按 v5.1 §3.2，公式契约优先）：
 * <pre>
 *   EXCHANGE(amount, from, to)
 *   EXCHANGE(amount, from, to, date)
 * </pre>
 *
 * <p>V50 migration 已将 exchange_rate.customer_id 改为可空：
 * NULL 表示全局汇率，非 NULL 表示客户协议汇率。
 * 查询优先匹配客户级（customer_id = ctx.getCustomerId()），fallback 到全局（customer_id IS NULL）。
 * schema 已与 v5.1 §3.2 公式契约对齐。
 */
@ApplicationScoped
public class ExchangeFunction implements FormulaFunction {

    private static final Logger LOG = Logger.getLogger(ExchangeFunction.class);

    /**
     * SQL：先查客户级汇率，再 fallback 到全局（customer_id IS NULL）。
     * V50 schema 对齐：customer_id 可空，NULL 行表示全局汇率。
     * ORDER BY customer_id IS NULL ASC 确保客户级（非 NULL）优先于全局（NULL）。
     * date 参数可选：有则 effective_date <= :date，无则取当前最新。
     */
    private static final String SQL_WITH_DATE =
            "SELECT rate FROM exchange_rate " +
            "WHERE (customer_id = ? OR customer_id IS NULL) " +
            "  AND from_currency = ? AND to_currency = ? " +
            "  AND effective_date <= ? " +
            "ORDER BY customer_id IS NULL ASC, effective_date DESC LIMIT 1";

    private static final String SQL_NO_DATE =
            "SELECT rate FROM exchange_rate " +
            "WHERE (customer_id = ? OR customer_id IS NULL) " +
            "  AND from_currency = ? AND to_currency = ? " +
            "ORDER BY customer_id IS NULL ASC, effective_date DESC LIMIT 1";

    @Inject
    DataSource dataSource;

    @Override
    public String name() {
        return "EXCHANGE";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.size() < 3) {
            return FormulaError.invalidArgs("EXCHANGE", "需要 3-4 个参数：EXCHANGE(amount, from, to[, date])");
        }
        BigDecimal amount = toDecimalStrict(args.get(0));
        if (amount == null) return FormulaError.typeMismatch("Number", typeName(args.get(0)));

        String from = toStr(args.get(1));
        String to   = toStr(args.get(2));
        if (from == null || to == null) {
            return FormulaError.invalidArgs("EXCHANGE", "from/to 货币代码不能为空");
        }

        // 同币种直接返回
        if (from.equalsIgnoreCase(to)) return amount;

        Object dateArg = args.size() >= 4 ? args.get(3) : null;

        // V50 schema 已对齐：customer_id 可空，优先匹配客户级，fallback 全局
        String customerIdStr = ctx != null && ctx.getCustomerId() != null
                               ? ctx.getCustomerId().toString() : null;

        try (Connection conn = dataSource.getConnection()) {
            BigDecimal rate = queryRate(conn, from, to, customerIdStr, dateArg);
            if (rate == null) {
                return new FormulaError(
                    "EXCHANGE 未找到汇率：" + from + " → " + to, "NOT_FOUND");
            }
            return amount.multiply(rate);
        } catch (Exception e) {
            LOG.warnf("EXCHANGE query failed: %s", e.getMessage());
            return new FormulaError("EXCHANGE 查询失败：" + e.getMessage(), "QUERY_ERROR");
        }
    }

    private BigDecimal queryRate(Connection conn, String from, String to,
                                 String customerId, Object dateArg) throws Exception {
        String sql = (dateArg != null) ? SQL_WITH_DATE : SQL_NO_DATE;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            // 参数顺序：customer_id, from_currency, to_currency[, effective_date]
            // customer_id — 若无 ctx 则传 NULL，数据库 OR customer_id IS NULL 兜底全局汇率
            if (customerId != null) {
                ps.setObject(1, java.util.UUID.fromString(customerId));
            } else {
                ps.setNull(1, java.sql.Types.OTHER);
            }
            ps.setString(2, from.toUpperCase());
            ps.setString(3, to.toUpperCase());
            if (dateArg != null) {
                ps.setString(4, dateArg.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBigDecimal(1);
                }
            }
        }
        return null;
    }

    private static BigDecimal toDecimalStrict(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return null;
    }

    private static String toStr(Object v) {
        return v instanceof String s ? s : (v != null ? v.toString() : null);
    }

    private static String typeName(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }
}
