package com.cpq.basicdata.v6.quote;

/**
 * Phase 2 写入阶段兜底异常（update-0723 Task B7 §8.3）。
 *
 * <p>Phase 1（{@link QuoteImportValidator}）已做全量校验，理论上 Phase 2 不应再产生业务错误；
 * 若某 handler 仍 {@code recordError}（如 DB 唯一键冲突、跨客户串号等意外/竞态场景），
 * {@link QuoteImportService#writeAll} 会把它转换为本异常并向外抛出，触发外层单一事务整体回滚
 * （U6/U7：有错全部回滚，全部通过才导入）。
 */
public class QuoteImportWriteFailedException extends RuntimeException {
    public QuoteImportWriteFailedException(String message) {
        super(message);
    }
}
