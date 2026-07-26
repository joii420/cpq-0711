package com.cpq.datasource.sqlview;

import java.util.UUID;

/**
 * 报价侧 pending 可见域（task-0725 根因 1）。
 *
 * <p>语义：作用域「打开」⟺ 当前线程正在渲染【报价侧】【非冻结】报价单 ⟹ 允许 pending 感知改写。
 *
 * <p><b>AC-17 保障机制 = 核价侧任何入口一律不调用 open()</b>（靠"不调用"而非运行时判断，
 * 可 grep 穷举、并由 T4 的白名单单测机器化保证）。禁止在
 * CardSnapshotService#precomputeCostingDriverUnion(:767) / #buildCostingCardValues(:1152) /
 * #snapshotNewLinesCardValues(:483) / CostingVersionService(:354) /
 * 核价侧 render 调用点（CardSnapshotService:501、:841、CostingVersionService:209）链路上调用 open()。
 *
 * <p><b>AC-10 保障机制 = open() 内建冻结判定</b>：冻结态（{@code SUBMITTED}/{@code APPROVED}/
 * {@code PUBLISHED}） ⟹ 存 null ⟹ 下游 quotationId 保持 null，与修复前逐位相同。冻结判定的
 * 状态字面量与 {@link SqlViewRuntimeContext.Snapshot#isQuotationFrozen()} 保持一致（故意不复用
 * 该方法本身 —— 构造一个 {@code Snapshot} 需要满足 ownerType 互斥约束，本类只需要三个状态字符串
 * 的比较，独立实现更简单、不引入耦合）。
 *
 * <p><b>既存的另一条 pending 通路（勿混淆）</b>：{@code FormulaEvaluateResource:119-120} 已把真实
 * quotationId + quotationStatus 塞进 {@link SqlViewRuntimeContext}（前端 {@code useLinkedExcelRows.ts:275}
 * 在发），即联动 Excel 公式求值路径靠运行时 status 判定、不经本类。本期不统一两者语义（见需求说明 §8-13）。
 *
 * <p><b>调用约定</b>：{@link #open} 必须与 {@link #restore} 成对出现在 try/finally 中，且
 * finally 必须无条件调用 {@link #restore}（含异常路径），否则 ThreadLocal 会在线程池复用场景
 * 泄漏到下一个请求。<b>本类故意不提供 public {@code clear()}</b>——嵌套场景下谁误用
 * {@code clear()} 代替 {@code restore(prev)} 都会静默丢掉外层作用域（例如 P1 主战场内部嵌套调用
 * P2/P3 的场景）。
 */
public final class QuotePendingScope {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private QuotePendingScope() {}

    /**
     * 打开（或维持关闭）当前线程的 pending 可见域。
     *
     * <p>{@code quotationId==null} 或 {@code status ∈ {SUBMITTED,APPROVED,PUBLISHED}} → 存 null
     * （等价不打开，AC-10）。否则存 {@code quotationId}（打开，允许 pending 感知改写）。
     *
     * @param quotationId     当前报价单 ID；null = 无报价单上下文（如核价侧）
     * @param quotationStatus 当前报价单状态；用于冻结判定，不落入下游 SqlViewRuntimeContext
     *                        （下游恒传 null，见 ComponentDriverService 调用点注释）
     * @return 调用前的旧值（供 {@link #restore} 还原，支持嵌套 open）
     */
    public static UUID open(UUID quotationId, String quotationStatus) {
        UUID prev = CURRENT.get();
        UUID next = (quotationId != null && !isFrozen(quotationStatus)) ? quotationId : null;
        if (next == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(next);
        }
        return prev;
    }

    /** 恢复 {@link #open} 返回的旧值。调用方 finally 必须调用（含异常路径）。 */
    public static void restore(UUID prev) {
        if (prev == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(prev);
        }
    }

    /** 当前 pending 归属；null = 不改写。<b>已内建冻结判定，消费方不得再判 frozen。</b> */
    public static UUID pendingOwner() {
        return CURRENT.get();
    }

    /**
     * 缓存维度标签：开 → {@code ":pq<qid无横杠>"}；关 → {@code ""}（保核价 key 逐字不变）。
     *
     * <p>⚠️ 不可与 {@code ComponentDriverService.qidTag}（{@code ":q<qid>"}）合并复用——报价侧与
     * 核价侧的 {@code _qid} 是<b>同一个值</b>，qidTag 无法区分两侧；本标签由「作用域是否打开」
     * 独立驱动，与 qidTag 是否非空无关。
     */
    public static String cacheTag() {
        UUID cur = CURRENT.get();
        return cur != null ? ":pq" + cur.toString().replace("-", "") : "";
    }

    /** 冻结态判定，状态字面量与 {@link SqlViewRuntimeContext.Snapshot#isQuotationFrozen()} 保持一致。 */
    private static boolean isFrozen(String status) {
        return status != null
                && ("APPROVED".equals(status) || "PUBLISHED".equals(status) || "SUBMITTED".equals(status));
    }
}
