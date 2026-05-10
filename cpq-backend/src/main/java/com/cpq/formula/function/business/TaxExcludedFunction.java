package com.cpq.formula.function.business;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.FormulaFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

/**
 * TAX_EXCLUDED(price, customer_id) — 返回不含税价（price / (1 + tax_rate)）。
 *
 * <p>v5.1 §3.2 货币函数。查询 customer_tax 表获取税率。
 *
 * <p>V50 migration 已移除 customer_tax.tax_type 字段，schema 与公式契约对齐（同 TaxIncludedFunction）。
 */
@ApplicationScoped
public class TaxExcludedFunction implements FormulaFunction {

    private static final Logger LOG = Logger.getLogger(TaxExcludedFunction.class);

    private static final String SQL =
            "SELECT tax_rate FROM customer_tax " +
            "WHERE customer_id = ? AND is_current = true " +
            "ORDER BY effective_date DESC LIMIT 1";

    @Inject
    DataSource dataSource;

    @Override
    public String name() {
        return "TAX_EXCLUDED";
    }

    @Override
    public Object invoke(List<Object> args, EvaluationContext ctx) {
        if (args == null || args.size() < 2) {
            return FormulaError.invalidArgs("TAX_EXCLUDED", "需要 2 个参数：TAX_EXCLUDED(price, customer_id)");
        }
        BigDecimal price = toDecimalStrict(args.get(0));
        if (price == null) return FormulaError.typeMismatch("Number", typeName(args.get(0)));

        String customerIdStr = toStr(args.get(1));
        if (customerIdStr == null || customerIdStr.isBlank()) {
            return FormulaError.invalidArgs("TAX_EXCLUDED", "customer_id 不能为空");
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL)) {
            ps.setObject(1, UUID.fromString(customerIdStr));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new FormulaError("TAX_EXCLUDED 未找到客户税率：customerId=" + customerIdStr, "NOT_FOUND");
                }
                BigDecimal taxRate = rs.getBigDecimal(1);
                BigDecimal divisor = BigDecimal.ONE.add(taxRate);
                if (divisor.compareTo(BigDecimal.ZERO) == 0) {
                    return FormulaError.divisionByZero();
                }
                return price.divide(divisor, 8, RoundingMode.HALF_UP);
            }
        } catch (Exception e) {
            LOG.warnf("TAX_EXCLUDED query failed: %s", e.getMessage());
            return new FormulaError("TAX_EXCLUDED 查询失败：" + e.getMessage(), "QUERY_ERROR");
        }
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
