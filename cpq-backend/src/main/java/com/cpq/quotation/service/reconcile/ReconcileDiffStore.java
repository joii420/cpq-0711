package com.cpq.quotation.service.reconcile;

import com.cpq.quotation.dto.ReconcileDiffEntry;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * task-0806 阶段① B1-3：对账差异状态，**进程内 Map**（backtask.md §4.1 / D8 单实例够用）。
 *
 * <p>🚨 已知限制（写进 test-report.md）：多实例部署下本存储**不共享**——A 实例上报的差异，
 * B 实例处理的提交请求看不到，闸门形同虚设。转多实例时需要外置（Redis / 表），
 * 随 D8 一起重评，见 需求文档.md §4.1。
 *
 * <p>key = lineItemId。value = 最近一次上报的差异清单（非空）。
 * 消解条件①（本类已实现）：下一轮对账上报 {@code diffs: []} → 清空该 lineItem 的条目。
 * 消解条件②（{@code quote_card_values} 整份重建时清空）**本批次未接线**——
 * 阶段① 范围只做 API-3 闸门 + API-5 埋点 + 本 Map，不改 {@code CardSnapshotService}
 * （backtask.md §9 阶段① 任务列表未含该项）。
 */
@ApplicationScoped
public class ReconcileDiffStore {

    /** 每条记录：本轮上报的原始差异 + 上报时间，供 assertLineSettled 取值构造 409 conflicts。 */
    public record Entry(List<ReconcileDiffEntry> diffs, Instant reportedAt) {
    }

    private final Map<UUID, Entry> pending = new ConcurrentHashMap<>();

    /**
     * 记录一轮对账上报。
     * @param diffs 为 null 或空 = 本轮无差异 → 消解条件①，清空该 lineItem 的 pending 条目。
     */
    public void report(UUID lineItemId, List<ReconcileDiffEntry> diffs, Instant reconciledAt) {
        Instant at = reconciledAt != null ? reconciledAt : Instant.now();
        if (diffs == null || diffs.isEmpty()) {
            pending.remove(lineItemId);
            return;
        }
        pending.put(lineItemId, new Entry(diffs, at));
    }

    /** 取该 lineItem 当前未落定的差异（无 = 空列表，不是 null）。 */
    public List<ReconcileDiffEntry> getPending(UUID lineItemId) {
        Entry e = pending.get(lineItemId);
        return e == null ? List.of() : e.diffs();
    }

    public boolean hasPending(UUID lineItemId) {
        Entry e = pending.get(lineItemId);
        return e != null && !e.diffs().isEmpty();
    }

    /** 手工清空（诊断/测试用；正常消解走 {@link #report} 上报空数组）。 */
    public void clear(UUID lineItemId) {
        pending.remove(lineItemId);
    }
}
