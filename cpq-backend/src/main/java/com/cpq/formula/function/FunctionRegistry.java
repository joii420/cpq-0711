package com.cpq.formula.function;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaError;
import com.cpq.formula.function.array.ContainsFunction;
import com.cpq.formula.function.array.InFunction;
import com.cpq.formula.function.business.ExchangeFunction;
import com.cpq.formula.function.business.TaxExcludedFunction;
import com.cpq.formula.function.business.TaxIncludedFunction;
import com.cpq.formula.function.business.ElementPriceFunction;
import com.cpq.formula.function.business.PremiumPriceFunction;
import com.cpq.formula.function.conditional.IfErrorFunction;
import com.cpq.formula.function.conditional.IfFunction;
import com.cpq.formula.function.lookup.ExistsFunction;
import com.cpq.formula.function.lookup.LookupFunction;
import com.cpq.formula.function.math.AbsFunction;
import com.cpq.formula.function.math.CeilFunction;
import com.cpq.formula.function.math.FloorFunction;
import com.cpq.formula.function.math.MaxFunction;
import com.cpq.formula.function.math.MinFunction;
import com.cpq.formula.function.math.RoundFunction;
import com.cpq.formula.function.aggregate.AvgFunction;
import com.cpq.formula.function.aggregate.CountFunction;
import com.cpq.formula.function.aggregate.SumFunction;
import com.cpq.formula.function.type.BoolFunction;
import com.cpq.formula.function.type.NumFunction;
import com.cpq.formula.function.type.StrFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 函数注册表：管理所有 FormulaFunction 实现。
 *
 * <p>在 ApplicationScoped 单例中维护函数名 → 实现的映射。
 * FormulaEngine 通过此注册表查找并调用函数。
 *
 * <p>业务函数（EXCHANGE/TAX_INCLUDED/TAX_EXCLUDED/ELEMENT_PRICE/PREMIUM_PRICE）
 * 需要 JDBC DataSource 访问，通过构造注入传入。
 */
@ApplicationScoped
public class FunctionRegistry {

    private static final Logger LOG = Logger.getLogger(FunctionRegistry.class);

    private final Map<String, FormulaFunction> functions = new HashMap<>();

    @Inject
    public FunctionRegistry(
            ExchangeFunction exchangeFunction,
            TaxIncludedFunction taxIncludedFunction,
            TaxExcludedFunction taxExcludedFunction,
            ElementPriceFunction elementPriceFunction,
            PremiumPriceFunction premiumPriceFunction,
            LookupFunction lookupFunction,
            ExistsFunction existsFunction) {

        // 类型转换
        register(new NumFunction());
        register(new StrFunction());
        register(new BoolFunction());

        // 数学
        register(new RoundFunction());
        register(new CeilFunction());
        register(new FloorFunction());
        register(new MaxFunction());
        register(new MinFunction());
        register(new AbsFunction());

        // 聚合（数据库查询版）
        register(new SumFunction());
        register(new AvgFunction());
        register(new CountFunction());

        // 查找（注入版，需要 DataLoader）
        register(lookupFunction);
        register(existsFunction);

        // 业务
        register(exchangeFunction);
        register(taxIncludedFunction);
        register(taxExcludedFunction);
        register(elementPriceFunction);
        register(premiumPriceFunction);

        // 条件
        register(new IfFunction());
        register(new IfErrorFunction());

        // 数组
        register(new InFunction());
        register(new ContainsFunction());

        LOG.infof("FunctionRegistry initialized with %d functions", functions.size());
    }

    private void register(FormulaFunction fn) {
        functions.put(fn.name().toUpperCase(), fn);
    }

    /**
     * 调用指定名称的函数。
     *
     * @param name 函数名（大小写不敏感）
     * @param args 已求值参数列表
     * @param ctx  求值上下文
     * @return 计算结果，或 FormulaError（函数未找到时也返回 FormulaError）
     */
    public Object invoke(String name, List<Object> args, EvaluationContext ctx) {
        if (name == null || name.isBlank()) {
            return FormulaError.invalidArgs("(unknown)", "函数名为空");
        }
        FormulaFunction fn = functions.get(name.toUpperCase());
        if (fn == null) {
            return new FormulaError("未知函数：" + name, "UNKNOWN_FUNCTION");
        }
        try {
            return fn.invoke(args, ctx);
        } catch (Exception e) {
            LOG.warnf("Function %s threw exception: %s", name, e.getMessage());
            return new FormulaError("函数 " + name + " 执行异常：" + e.getMessage(), "RUNTIME_ERROR");
        }
    }

    /** 检查函数是否已注册（供单元测试验证）。 */
    public boolean contains(String name) {
        return name != null && functions.containsKey(name.toUpperCase());
    }

    public int size() {
        return functions.size();
    }
}
