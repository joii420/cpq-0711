package com.cpq.basicdata.v6.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.jboss.logging.Logger;

/**
 * 生产 / 销毁 {@link MaterializeExecutor} 限定的专用 {@code ManagedExecutor}（repair-260829 B-2）。
 *
 * <p>动机与实测依据见 {@link MaterializeExecutor} 的 javadoc。构造参数
 * {@code cleared(ThreadContext.CDI).propagated(ThreadContext.NONE)} 与主线在 8081 真实并发环境
 * 验证过（40/40 成功）的构造逐位一致，**不自行调整**这两个参数。
 *
 * <p>用 CDI producer + qualifier（而不是探针里临时用过的 static 字段写法）：
 * ① 交给容器管理生命周期，避免多份 executor 实例；② {@link #dispose} 在应用停止时显式
 * {@code shutdown()}，避免线程池随应用生命周期泄漏（dev 模式热重载场景尤其明显）。
 */
@ApplicationScoped
public class MaterializeExecutorProducer {

    private static final Logger LOG = Logger.getLogger(MaterializeExecutorProducer.class);

    @Produces
    @ApplicationScoped
    @MaterializeExecutor
    public ManagedExecutor produce() {
        return ManagedExecutor.builder()
                .cleared(ThreadContext.CDI)
                .propagated(ThreadContext.NONE)
                .build();
    }

    public void dispose(@Disposes @MaterializeExecutor ManagedExecutor executor) {
        executor.shutdown();
        LOG.debug("[repair-260829] MaterializeExecutor 已随应用生命周期关闭");
    }
}
