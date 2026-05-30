package com.cpq.formula.dataloader;

import java.util.UUID;

/**
 * 当前请求线程的 quotation_id 上下文(ThreadLocal)。
 *
 * <p>用途:让 SQL 视图通过 {@code :quotationId} 占位符自动拿到本次渲染的报价单 id,
 * DataLoader 在 RuntimeContext 里把它绑成 named param。这样所有 mirror 视图(材质/元素/工序/
 * 组合工艺...)都按统一协议靠 `:quotationId + :customerCode + :hfPartNos` 三参数 + 外层
 * {@code hf_part_no = ANY(:hfPartNos)} 自然过滤,不再有任何视图需要单独的 :lineItemId 标量。
 *
 * <p>调用模式(典型场景:ConfigureSnapshotService.snapshotLines / ComponentResource.batchExpand):
 * <pre>
 *   QuotationIdContext.set(quotationId);
 *   try {
 *       componentDriverService.expand(...);
 *   } finally {
 *       QuotationIdContext.clear();
 *   }
 * </pre>
 *
 * <p>DataLoader.loadByPath 读取 {@link #get()},非 null 时把它塞进 RuntimeContext.quotation.id,
 * RuntimeContext.toNamedParams() 自动把它绑到 {@code :quotationId} 占位符。
 *
 * <p>注意事项与 {@link PartVersionContext} 完全一致(HTTP 单线程、finally 必 clear、
 * CompletableFuture 跨线程不自动传播)。
 */
public final class QuotationIdContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private QuotationIdContext() {}

    /** 设置当前线程的 quotationId;传 null 等价于 clear()。 */
    public static void set(UUID quotationId) {
        if (quotationId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(quotationId);
        }
    }

    /** 返回当前线程的 quotationId;未设置时返回 null。 */
    public static UUID get() {
        return CURRENT.get();
    }

    /** 清除当前线程的 quotationId(finally 块必调)。 */
    public static void clear() {
        CURRENT.remove();
    }
}
