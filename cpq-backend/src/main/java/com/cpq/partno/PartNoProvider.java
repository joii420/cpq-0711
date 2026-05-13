package com.cpq.partno;

/**
 * 料号生成 Provider 抽象.
 *
 * <p>V1 实现:{@link AutoAllocatePartNoProvider} — 本地按 part_no_sequence 表自增分配 CFG-{symbol}-{6位流水}.
 * <p>V2 实现(预留):{@code ExternalApiPartNoProvider} — 调外部 ERP/PLM API 拿料号.
 *
 * <p>切换方式:{@code application.properties} 改 {@code cpq.partno.provider=auto|external}.
 */
@FunctionalInterface
public interface PartNoProvider {

    /**
     * 生成新的全局唯一 hf_part_no.
     *
     * @param context 命名上下文(symbol / productType / operatorId);不可为 null,symbol 不可为空白
     * @return 全局唯一 hf_part_no,例如 "CFG-AgCu-000001"
     * @throws PartNoProvisionException 申请失败(序列冲突 / DB 故障 / 外部 API 错误)
     * @throws IllegalArgumentException context 为 null 或 symbol 空白
     */
    String apply(PartNoContext context);
}
