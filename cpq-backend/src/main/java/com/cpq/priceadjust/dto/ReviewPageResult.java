package com.cpq.priceadjust.dto;

import com.cpq.common.dto.PageResult;

import java.util.List;

/**
 * task-260920 api.md §1.1 —— 审核列表分页结果 + 两个未计算计数。
 * <ul>
 *   <li>{@code notComputedTotal}：当前筛选条件下、审核状态为「待处理」且预算为 QUEUED / COMPUTING 的总数（不是本页）；</li>
 *   <li>{@code excludedByNotComputed}：仅 {@code breachedOnly=true} 时有意义 —— 因未计算而未参与「只看标红」的待处理行数；其余恒 0。</li>
 * </ul>
 */
public class ReviewPageResult<T> extends PageResult<T> {

    private final long notComputedTotal;
    private final long excludedByNotComputed;

    public ReviewPageResult(List<T> content, int page, int size, long totalElements,
                            long notComputedTotal, long excludedByNotComputed) {
        super(content, page, size, totalElements);
        this.notComputedTotal = notComputedTotal;
        this.excludedByNotComputed = excludedByNotComputed;
    }

    public long getNotComputedTotal() { return notComputedTotal; }
    public long getExcludedByNotComputed() { return excludedByNotComputed; }
}
