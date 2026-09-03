package com.cpq.basicdata.v6.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * repair-260829（卡片值算早了骨架值锁死）B-4：建单后置物化的进行中标志，内存态。
 *
 * <p><b>解决什么问题</b>：{@code ConfigureSnapshotService#snapshotQuotation}（①步，写
 * {@code quotation_line_component_data.snapshot_rows}）自身不持有
 * {@code QUOTATION_CALCULATION_LOCK_KEY_SQL} 单飞锁（那把锁只在
 * {@code CardSnapshotService} 的③步获取）。前端轮询的自愈判据
 * （{@code quotationService.ts pollMaterializeStatus}）据此误判"①步在跑"为"任务已死"，
 * 提前触发一次 {@code ensure-card-values}，在①步还没写完时算出全空的卡片值并落库——
 * 一旦落库，{@code ensureCardValues} 的 {@code IS NULL} 自愈判据永远不会再选中该行
 * （见 {@code 问题说明.md} ④ 因果链）。
 *
 * <p>本 registry 只回答一个问题："{@link CreateQuotationMaterializer#materialize} 这个
 * 任务此刻是否正在执行"，覆盖①~④全程，与卡片值本身的状态（NULL / 骨架值 / 正常值）
 * 无关——它是给前端自愈判据补上的第二个信号维度，与 B-1 的产物自检（
 * {@code CardSnapshotService} 落库前校验）是两层独立防线，缺一不可（见问题说明.md ⑤）。
 *
 * <p><b>生命周期</b>：{@code @ApplicationScoped} + 内存态 {@link ConcurrentHashMap} 支撑的
 * key set，进程重启即丢失——可接受：进程重启时物化任务本来也会被中断，标志跟着消失，
 * 语义上仍然正确（不会出现"进程都重启了、标志却还残留 true"的悬挂态）。
 *
 * <p><b>消费方</b>：并发会话「修复draft超时问题」在 {@code QuotationResource.materializeStatus}
 * 读取 {@link #isInProgress(UUID)} 并暴露到响应体，修正其自愈判据为
 * {@code pending>0 && inFlight===false && !materializeInProgress}。本类落地后须立刻把
 * 包名/类名/方法签名报主线转告对方，避免签名变更导致返工。
 */
@ApplicationScoped
public class MaterializeRegistry {

    private final Set<UUID> inProgress = ConcurrentHashMap.newKeySet();

    /** 标记 {@code quotationId} 的建单后置物化任务开始执行。 */
    public void begin(UUID quotationId) {
        if (quotationId == null) return;
        inProgress.add(quotationId);
    }

    /** 标记 {@code quotationId} 的建单后置物化任务已结束（正常完成或异常终止均需调用）。 */
    public void end(UUID quotationId) {
        if (quotationId == null) return;
        inProgress.remove(quotationId);
    }

    /** 该报价单当前是否有正在执行的建单后置物化任务。 */
    public boolean isInProgress(UUID quotationId) {
        return quotationId != null && inProgress.contains(quotationId);
    }
}
