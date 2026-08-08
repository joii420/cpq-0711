package com.cpq.datasource.sqlview;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * task-0806 · 取价基准日的<b>唯一推导实现</b>（需求文档 §5.3 / backtask §3.1）。
 *
 * <p>口径：{@code quotation.created_at} 的 {@code LocalDate}；{@code createdAt} 为
 * {@code null} 时回退当天。与 {@link SqlViewExecutor#queryQuotationDate} 原有的内联逻辑
 * 逐位等价——该方法已改为委派本类（见其实现），本类是<b>唯一实现</b>，其余调用方
 * （如 {@code PriceAdjustJobExecutionService} 的批量预渲染分组键）一律复用本类，
 * <b>不得另写一份日期推导</b>（AP-52：语义错配 + 契约不对齐，两套口径是本项目反复出事的根因）。
 */
public final class PriceBaseDateUtil {

    private PriceBaseDateUtil() {
    }

    /**
     * @param createdAt 报价单的 {@code created_at}（可为 {@code null}）
     * @return {@code createdAt.toLocalDate()}；{@code createdAt == null} 时回退 {@link LocalDate#now()}
     */
    public static LocalDate deriveFrom(OffsetDateTime createdAt) {
        return createdAt != null ? createdAt.toLocalDate() : LocalDate.now();
    }
}
