package com.cpq.quotation.service;

import com.cpq.quotation.entity.QuotationLineItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * 报价单快照 per-line worker —— 在专用并行池的 worker 线程上执行单行快照。
 *
 * <p><b>并发正确性核心</b>:每个方法标 {@code @ActivateRequestContext},在 worker 线程上激活
 * <b>全新、独立</b>的 CDI request context。由此每个 worker 自动获得:
 * <ul>
 *   <li>自己的 {@code @RequestScoped DataLoader}(独立 resultCache,与主请求/别的 worker 无共享竞争);</li>
 *   <li>活跃的 Hibernate session,供 Panache {@code findById};</li>
 *   <li>内层 {@code @Transactional}(snapshotLineValues / writeSnapshot REQUIRES_NEW)按本线程绑定事务。</li>
 * </ul>
 * 范式同 {@code QuoteImportService}(@ActivateRequestContext 让 request-scoped EM 在后台线程可用)。
 *
 * <p>方法<b>绝不抛出</b>(顶层 try/catch 降级),单行失败不连坐其它 worker。
 */
@ApplicationScoped
public class LineSnapshotWorker {

    private static final Logger LOG = Logger.getLogger(LineSnapshotWorker.class);

    @Inject
    CardSnapshotService cardSnapshotService;

    /**
     * 核价侧单行(屏障 2):激活独立 request context → 调 {@link CardSnapshotService#snapshotLineValues}
     * (其内部 {@code @Transactional} 按 id 重载并写 4 份卡片值)。
     * 用 stub 仅承载 id,避免在事务外做 Panache 读。
     */
    @ActivateRequestContext
    public void snapshotOneLineValues(UUID lineItemId) {
        if (lineItemId == null) return;
        try {
            QuotationLineItem stub = new QuotationLineItem();
            stub.id = lineItemId;
            cardSnapshotService.snapshotLineValues(stub);
        } catch (Exception e) {
            LOG.warnf("[snapshot-par] costing line=%s failed(降级): %s", lineItemId, e.getMessage());
        }
    }
}
