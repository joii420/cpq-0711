package com.cpq.configure.dto;

import com.cpq.configure.SalesFingerprintCalculator.EnabledParam;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 选配 Plan 3b — 销售侧客户维度上下文 (T3)。
 *
 * <p>在 {@code ConfigureProductService#configure} 入口一次性组装，向下传给
 * {@code resolvePart} 供 T4/T5 消费（本类本身不做落库/发号决策）。
 *
 * <p>承载:
 * <ul>
 *   <li>{@code customerNo} — 客户编码（= customerCode），传入 {@link com.cpq.configure.SalesFingerprintCalculator}</li>
 *   <li>{@code yyMm} — 当前年月（yyMM），供 T4/T5 发号使用</li>
 *   <li>{@code structureVersion} — 与 {@code SalesFingerprintCalculator.STRUCTURE_VERSION} 一致 ("v1")</li>
 *   <li>每个 {@link PartRequest} 对应的 {@link EnabledParam} 投影（按对象引用索引，
 *       {@link PartRequest} 无 {@code equals}/{@code hashCode}，用 {@link IdentityHashMap} 避免错配）</li>
 * </ul>
 */
public class SalesConfigContext {

    public final String customerNo;
    public final String yyMm;
    public final String structureVersion;
    private final Map<PartRequest, List<EnabledParam>> enabledParamsByPart;

    public SalesConfigContext(String customerNo, String yyMm, String structureVersion,
                               Map<PartRequest, List<EnabledParam>> enabledParamsByPart) {
        this.customerNo = customerNo;
        this.yyMm = yyMm;
        this.structureVersion = structureVersion;
        this.enabledParamsByPart = enabledParamsByPart != null
            ? enabledParamsByPart : new IdentityHashMap<>();
    }

    /** 返回该 part 的 EnabledParam 投影；缺失（如 existing 模式未投影）返回空 List。 */
    public List<EnabledParam> enabledParamsFor(PartRequest pr) {
        return enabledParamsByPart.getOrDefault(pr, List.of());
    }
}
