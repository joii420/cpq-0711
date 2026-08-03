package com.cpq.priceadjust.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * task-0729 B4 · 预算计算（异步）+ 比对算法。
 *
 * <p>本类在 B3 阶段先落一个可编译的骨架（{@link #onVersionGenerated} 目前只记日志），
 * 真正的「待办池成员判定（B4.1）+ dryRun 预算（B4.2）+ 比对差异/着色算法（B4.3）」
 * 在 B4 独立工作块里实现，以保持 B3/B4 各自可独立提交、独立验证（coordinator 分块要求）。
 */
@ApplicationScoped
public class PriceAdjustBudgetService {

    private static final Logger LOG = Logger.getLogger(PriceAdjustBudgetService.class);

    /**
     * 版本生成后台队列入口（E14-3）。由 {@link PriceAdjustVersionGenerationService
     * #generateVersionAndEnqueueBudget} 在事务提交后经 {@code ManagedExecutor.runAsync} 调用，
     * 运行在无请求上下文的线程，故需 {@code @ActivateRequestContext}。
     */
    @ActivateRequestContext
    public void onVersionGenerated(UUID versionId) {
        LOG.infof("[price-adjust-budget] onVersionGenerated versionId=%s (B4 未实现，占位)", versionId);
        // TODO(B4)：待办池成员判定（含 D5 无活单料号指针自动推进 + 反例外）
        //           + dryRun 预算（MaterialVersionUpgradeService.upgrade(dryRun=true)）
        //           + 比对差异/着色算法（移植 comparisonMapping.ts getColumnValue/computeDiff/classifyDiff）
    }
}
