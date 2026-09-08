package com.cpq.dataset.quotation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.jboss.logging.Logger;

/**
 * 生产 / 销毁 {@link QuotationImportExecutor} 限定的专用 {@code ManagedExecutor}
 * （task-260907 · B-1）。
 *
 * <p>构造参数 {@code cleared(ThreadContext.CDI).propagated(ThreadContext.NONE)} 与
 * {@code MaterializeExecutorProducer} 逐位一致 —— 那是 {@code repair-260829} 在 8081 真实并发
 * 环境背靠背验证过的构造（40/40 vs 默认 executor 的 3/40）。🚫 不自行调整这两个参数。
 *
 * <p>用 CDI producer + qualifier 而不是 static 字段：① 容器管理生命周期，避免多份实例；
 * ② {@link #dispose} 在应用停止时显式 {@code shutdown()}，避免线程池泄漏
 * （dev 模式热重载场景尤其明显）。
 */
@ApplicationScoped
public class QuotationImportExecutorProducer {

    private static final Logger LOG = Logger.getLogger(QuotationImportExecutorProducer.class);

    @Produces
    @ApplicationScoped
    @QuotationImportExecutor
    public ManagedExecutor produce() {
        return ManagedExecutor.builder()
                .cleared(ThreadContext.CDI)
                .propagated(ThreadContext.NONE)
                .build();
    }

    public void dispose(@Disposes @QuotationImportExecutor ManagedExecutor executor) {
        executor.shutdown();
        LOG.debug("[task-260907] QuotationImportExecutor 已随应用生命周期关闭");
    }
}
