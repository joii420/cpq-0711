package com.cpq.quotation.service.reconcile;

import com.cpq.common.exception.ReconcilePendingException;
import com.cpq.quotation.dto.ReconcileDiffEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

/**
 * task-0806 阶段① B1-1：提交闸门（FR-6 / D7）。
 * 提交前对每个 line item 调 {@link #assertLineSettled}，两个条件都要查
 * （需求文档.md §4.1，backtask.md §4.1）：
 * <ol>
 *   <li><b>在飞写</b>：阶段② 的串行队列里该 lineItem 仍有待处理项 → {@code WRITE_IN_FLIGHT}。
 *       ⚠️ 阶段② 尚未实现（本任务不做），{@link #isWriteInFlight} 当前恒返回 false，
 *       是留给阶段② 接线的占位点，不是遗漏。</li>
 *   <li><b>未落定差异</b>：前端经 API-5 上报且未消解 → {@code RECONCILE_PENDING}。</li>
 * </ol>
 *
 * <p>只读，不开写事务（本类无 {@code @Transactional}）。
 */
@ApplicationScoped
public class SubmitGateService {

    @Inject
    ReconcileDiffStore reconcileDiffStore;

    /**
     * 校验单个 line item 是否已「落定」。
     *
     * @return 该 lineItem 当前的未落定差异（已转换成 {@link SubmitConflictDTO}），无 = 空列表。
     *         调用方负责把多个 lineItem 的结果聚合后统一抛 {@link ReconcilePendingException}。
     * @throws ReconcilePendingException 若该 lineItem 有在飞写（reason=WRITE_IN_FLIGHT，立即抛，不聚合）
     */
    public List<SubmitConflictDTO> assertLineSettled(UUID lineItemId, String productPartNo) {
        if (isWriteInFlight(lineItemId)) {
            throw new ReconcilePendingException("WRITE_IN_FLIGHT", "正在保存，请稍候重试", List.of());
        }
        List<ReconcileDiffEntry> diffs = reconcileDiffStore.getPending(lineItemId);
        if (diffs.isEmpty()) {
            return List.of();
        }
        return diffs.stream()
                .map(d -> new SubmitConflictDTO(
                        lineItemId.toString(), productPartNo, d.tabName, d.rowKey, d.fieldName,
                        d.frontendValue, d.backendValue))
                .toList();
    }

    /** 阶段② 接线点：串行队列非空即返回 true。阶段① 恒 false（队列尚不存在）。 */
    private boolean isWriteInFlight(UUID lineItemId) {
        return false;
    }
}
