package com.cpq.priceadjust.service;

import com.cpq.datasource.sqlview.PriceBaseDateUtil;
import com.cpq.priceadjust.dto.UpgradeResult;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJob;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJobItem;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.BatchSafetyLevel;
import com.cpq.quotation.service.BomTreeRenderService;
import com.cpq.quotation.service.CardSnapshotService;
import com.cpq.quotation.service.DriverBatchSafetyAuditor;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * task-0729 B5 · 异步更新任务执行（api.md §3）。
 *
 * <p>参照既有 {@code QuoteImportService} 的既定模式：{@code ManagedExecutor.runAsync} 触发
 * （由 {@link PriceAdjustReviewService#approve} 在同步事务提交后调用）+ 逐条
 * {@code @Transactional(REQUIRES_NEW)} 独立提交（一单失败不回滚全批，backtask B5）。
 *
 * <p>三种非成功态语义（api.md §3.3）：
 * <ul>
 *   <li>FAILED —— 数据问题（含 S0 的 SUBTOTAL_MISMATCH），需人工处理后重试</li>
 *   <li>CONFLICT —— 重算期间该行被改动（row_version 不匹配），直接重试即可</li>
 *   <li>STALE —— 所属版本已被新版取代（B3 生成新版时已把未完成 job_item 提前置 STALE，
 *       本类执行期遇到 STALE 项直接跳过不处理，终态不可重试）</li>
 * </ul>
 *
 * <p>🔒 指针推进纪律（§11.6.3.2）：本类只更新 job/job_item 状态，绝不回退
 * {@code material_price_version_ref}——个别单失败不回退整个料号的指针。
 */
@ApplicationScoped
public class PriceAdjustJobExecutionService {

    private static final Logger LOG = Logger.getLogger(PriceAdjustJobExecutionService.class);

    @Inject MaterialVersionUpgradeService materialVersionUpgradeService;
    @Inject PriceAdjustNotificationService notificationService;
    @Inject BomTreeRenderService bomTreeRenderService;
    @Inject DriverBatchSafetyAuditor safetyAuditor;
    @Inject CardSnapshotService cardSnapshotService;
    @Inject CurrentPeriodRevisionWriter revisionWriter;

    /**
     * task-0806 · FR-1（方案 B）+ FR-4/FR-5/FR-6/FR-7：逐项循环<b>之前</b>按
     * {@code (costingCardTemplateId, 取价基准日)} 分组批量预渲染核价树，结果按 {@code lineItemId}
     * 分发给各 {@link #executeItem}；预渲染本身只读、不参与 item 事务（需求文档 §4 事务边界）。
     */
    public void executeJob(UUID jobId) {
        runJob(jobId, false);
    }

    /**
     * repair-260918 · 批次执行主体（「通过」与「批量重试」共用）。
     *
     * <p><b>B-4④ 分组路径（方案丁）</b>：待执行明细按 {@code quotationId} 分组（保持首次出现顺序），组内逐条执行
     * （每条仍是独立 {@code REQUIRES_NEW} + {@code @ActivateRequestContext}，{@code precomputed} 照旧透传），
     * 升版时本期快照延后（{@link CurrentRevisionDeferral}）；组内全部完成后若有 SUCCESS，在
     * {@link #writeGroupRevisionInNewTx} 独立事务里写一次本期版本记录、一次并入本组全部成功料号。
     * 写失败 ⇒ 本组 SUCCESS 明细改 FAILED / {@code REVISION_WRITE_FAILED}（B-4⑤）。
     *
     * <p><b>B-5</b>：每条明细执行前先用独立短事务置 RUNNING 并提交 —— 进度抽屉看得到「执行中」。
     *
     * <p><b>B-10 兜底</b>：整体 {@code try/finally}，任何 {@code Throwable}（含 {@code Error}）都不许让批次停在
     * RUNNING：{@code finally} 里把本次负责的明细中仍为 WAITING / RUNNING 的按 B-6 口径标 FAILED，再汇总批次。
     *
     * @param includeFailed {@code true} = 批量重试（B-13：重跑 FAILED + CONFLICT，STALE 不动）
     */
    void runJob(UUID jobId, boolean includeFailed) {
        List<UUID> ownedItemIds = null;
        UUID versionId = null;
        GroupState current = null;
        Throwable failure = null;
        try {
            List<MaterialPriceUpdateJobItem> items = includeFailed ? loadRetryItems(jobId) : loadWaitingItems(jobId);
            ownedItemIds = new ArrayList<>(items.size());
            for (MaterialPriceUpdateJobItem it : items) ownedItemIds.add(it.id);
            LOG.infof("[price-adjust-job] executeJob jobId=%s items=%d retry=%b", jobId, items.size(), includeFailed);
            versionId = loadJobVersionId(jobId);
            Map<UUID, CardSnapshotService.PrecomputedTreeRows> precomputedByLineItem = precomputeBatch(jobId, items);

            // 纯内存分组（保持首次出现顺序），无查库。
            Map<UUID, List<MaterialPriceUpdateJobItem>> byQuotation = new LinkedHashMap<>();
            for (MaterialPriceUpdateJobItem it : items) {
                byQuotation.computeIfAbsent(it.quotationId, k -> new ArrayList<>()).add(it);
            }

            for (Map.Entry<UUID, List<MaterialPriceUpdateJobItem>> g : byQuotation.entrySet()) {
                current = new GroupState(g.getKey());
                // 每条明细一次 markItemRunning + 一次 executeItem（各自独立事务）—— 这是「逐条独立提交、
                // 一单失败不回滚全批」的既定执行单位（task-0729 B5），不是查询 N+1；组内读库条数与整单行数无关。
                for (MaterialPriceUpdateJobItem item : g.getValue()) {
                    markItemRunning(item.id);
                    CardSnapshotService.PrecomputedTreeRows precomputed =
                        item.lineItemId != null ? precomputedByLineItem.get(item.lineItemId) : null;
                    String status;
                    try {
                        status = CurrentRevisionDeferral.callDeferred(() -> executeItem(item.id, precomputed));
                    } catch (Exception e) {
                        LOG.errorf(e, "[price-adjust-job] jobId=%s item=%s 执行异常", jobId, item.id);
                        String[] f = PriceAdjustFailureTranslator.forJobItem(e);
                        markItemFailed(item.id, f[0], f[1]);
                        status = MaterialPriceUpdateJobItem.FAILED;
                    }
                    if (MaterialPriceUpdateJobItem.SUCCESS.equals(status)) {
                        current.successItemIds.add(item.id);
                        current.materialNos.add(item.materialNo);
                    }
                }
                completeGroup(jobId, versionId, current);
                current = null;
            }
        } catch (Throwable t) {
            failure = t;
            LOG.errorf(t, "[price-adjust-job] jobId=%s 执行入口异常（%s），收尾后批次不会停在 RUNNING",
                jobId, PriceAdjustFailureTranslator.rootCauseText(t));
            throw t;
        } finally {
            closeOut(jobId, ownedItemIds, versionId, current, failure);
        }
    }

    /** 一张报价单在本次执行里的成功明细（纯内存）。 */
    private static final class GroupState {
        final UUID quotationId;
        final List<UUID> successItemIds = new ArrayList<>();
        final List<String> materialNos = new ArrayList<>();
        boolean revisionHandled;

        GroupState(UUID quotationId) {
            this.quotationId = quotationId;
        }
    }

    /** B-4④⑤：本组有 SUCCESS ⇒ 独立事务写一次本期版本记录；写失败 ⇒ 本组 SUCCESS 明细改 REVISION_WRITE_FAILED。 */
    private void completeGroup(UUID jobId, UUID versionId, GroupState gs) {
        gs.revisionHandled = true;
        if (gs.successItemIds.isEmpty() || versionId == null) return;
        try {
            writeGroupRevisionInNewTx(gs.quotationId, versionId, gs.materialNos);
        } catch (Exception e) {
            LOG.errorf(e, "[price-adjust-job] jobId=%s quotation=%s 写本期版本记录失败，本组 %d 条成功明细改 %s",
                jobId, gs.quotationId, gs.successItemIds.size(), PriceAdjustFailureTranslator.REVISION_WRITE_FAILED);
            markRevisionWriteFailed(gs.successItemIds);
        }
    }

    /**
     * B-4④：分组路径写本期快照的独立事务。writer 是 REQUIRED → 加入本方法新开的事务；这张单的各行此前已各自提交，
     * 本事务读到的是全部升版后的状态。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void writeGroupRevisionInNewTx(UUID quotationId, UUID versionId, List<String> materialNos) {
        revisionWriter.write(quotationId, versionId, materialNos);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void markRevisionWriteFailed(List<UUID> itemIds) {
        MaterialPriceUpdateJobItem.markRevisionWriteFailed(itemIds,
            PriceAdjustFailureTranslator.REVISION_WRITE_FAILED, PriceAdjustFailureTranslator.MSG_REVISION_WRITE_FAILED);
    }

    /** B-5：明细执行前置 RUNNING，独立短事务提交。 */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void markItemRunning(UUID itemId) {
        MaterialPriceUpdateJobItem.markRunning(itemId);
    }

    /**
     * B-10：执行入口收尾。每一步各自兜住异常 —— 收尾自身失败只记日志（批次若仍停在 RUNNING，由下次启动的
     * {@link PriceAdjustStartupRecovery} 收尾）。
     */
    private void closeOut(UUID jobId, List<UUID> ownedItemIds, UUID versionId, GroupState pending, Throwable failure) {
        if (pending != null && !pending.revisionHandled) {
            try {
                completeGroup(jobId, versionId, pending);
            } catch (Throwable t) {
                LOG.errorf(t, "[price-adjust-job] jobId=%s 收尾时补写本期版本记录失败", jobId);
            }
        }
        try {
            String[] f = failure != null
                ? PriceAdjustFailureTranslator.forJobItem(failure)
                : new String[]{PriceAdjustFailureTranslator.UNEXPECTED_ERROR, "执行未完成（批次提前结束），本行未更新，可重试"};
            int n = failUnfinishedItems(jobId, ownedItemIds, f[0], f[1]);
            if (n > 0) {
                LOG.errorf("[price-adjust-job] jobId=%s 收尾：%d 条未完成明细置 FAILED（%s: %s）", jobId, n, f[0], f[1]);
            }
        } catch (Throwable t) {
            LOG.errorf(t, "[price-adjust-job] jobId=%s 收尾时标记未完成明细失败", jobId);
        }
        try {
            finalizeJob(jobId);
        } catch (Throwable t) {
            LOG.errorf(t, "[price-adjust-job] jobId=%s 收尾时汇总批次失败", jobId);
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    int failUnfinishedItems(UUID jobId, List<UUID> ownedItemIds, String errorCode, String errorMessage) {
        return MaterialPriceUpdateJobItem.failUnfinished(List.of(jobId), ownedItemIds, errorCode, errorMessage);
    }

    @Transactional
    UUID loadJobVersionId(UUID jobId) {
        MaterialPriceUpdateJob job = MaterialPriceUpdateJob.findById(jobId);
        return job != null ? job.versionId : null;
    }

    /**
     * task-0806 · 分组键：{@code (costingCardTemplateId, priceBaseDate)}（需求文档 D-3/§5.3）。
     * 🔒 日期口径必须与 {@link PriceBaseDateUtil#deriveFrom} 同源，不得另写一份（AP-52）。
     */
    private static final class GroupKey {
        final UUID templateId;
        final LocalDate priceBaseDate;

        GroupKey(UUID templateId, LocalDate priceBaseDate) {
            this.templateId = templateId;
            this.priceBaseDate = priceBaseDate;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GroupKey)) return false;
            GroupKey other = (GroupKey) o;
            return Objects.equals(templateId, other.templateId) && Objects.equals(priceBaseDate, other.priceBaseDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(templateId, priceBaseDate);
        }
    }

    /**
     * task-0806 · 批量预渲染主体（方案 B）。
     *
     * <p>整体只读、不参与任何 item 事务；任一层级失败都<b>软回退</b>（不写入对应 item 的预渲染
     * 结果，留空 = 该 item 走 {@link MaterialVersionUpgradeService#upgrade(UUID, UUID, boolean)}
     * 三参默认路径，内部按原逻辑各自调用一次 {@code render()}）——FR-5 守卫 2 的核心：
     * 批量预渲染的任何异常都不得让整批 job 失败，只能让"批量"这个优化本身失效，退化为改造前的
     * 逐项慢路径（但仍然<b>正确</b>）。
     *
     * <p>三层"软失败"边界，由粗到细：
     * <ol>
     *   <li>整个方法级 try/catch：批量取行/取单据本身异常（如 DB 抖动）→ 空 map，全部逐项；</li>
     *   <li>每个分组独立 {@code @Transactional(REQUIRES_NEW)} + try/catch：FR-6 customerId 唯一性
     *       断言失败 / {@code render()} 本身抛异常（FR-5，如注入的必然失败 driver 组件）→ 该分组不
     *       写入，组内全部 item 逐项；</li>
     *   <li>FR-4 守卫 1 前置闸门：分组内任一 driver 组件判定为 {@link BatchSafetyLevel#PER_LINE_ITEM}
     *       → 直接跳过批量渲染（不算异常，是正常的保守分流），该组照样逐项；</li>
     *   <li>{@link CardSnapshotService#templateHasTreeTab} 前置闸门（2026-08-07 亲验补丁）：模板
     *       不含树页签 → 直接跳过（不算异常），该组照样逐项——老路径 {@code refreshCostingCardValuesForLine}
     *       对这类模板恒不调用 {@code render()}（{@code precomputedBaseRows} 恒 null，走
     *       {@code buildCostingCardValues} 的旧引擎分支），批量路径必须与它对齐，否则会在"未来有人建一个
     *       不含树页签的核价模板"时让两条路径分叉进 {@code buildCostingCardValues} 的不同代码分支。</li>
     * </ol>
     *
     * <p>🚨 <b>分组级 {@code REQUIRES_NEW} 是必需的，不是可选的美化</b>——实测（AC-4 单测）发现：
     * 若各分组共用同一个外层事务，一个分组因真实 SQL 异常（如注入的必然失败 driver 组件）失败后，
     * PostgreSQL 会把<b>整个物理事务</b>标记为 aborted（"current transaction is aborted, commands
     * ignored until end of transaction block"）——哪怕 Java 层 {@code catch} 住了异常，同一事务里
     * 后续分组的任何 SQL 都会连带失败。必须让每个分组在<b>独立事务</b>（{@link #renderGroupInNewTx}）
     * 里执行，失败时只回滚它自己，物理连接不同，不会连累其它分组。
     */
    @Transactional
    Map<UUID, CardSnapshotService.PrecomputedTreeRows> precomputeBatch(UUID jobId, List<MaterialPriceUpdateJobItem> items) {
        Map<UUID, CardSnapshotService.PrecomputedTreeRows> result = new LinkedHashMap<>();
        Map<GroupKey, List<QuotationLineItem>> groups;
        Map<UUID, Quotation> quotationById;
        try {
            List<UUID> lineItemIds = new ArrayList<>();
            for (MaterialPriceUpdateJobItem item : items) {
                if (item.lineItemId != null) lineItemIds.add(item.lineItemId);
            }
            if (lineItemIds.isEmpty()) return result;

            // N+1 纪律：各一条 IN 批量查询，不逐 item 单查。
            List<QuotationLineItem> lineItems = QuotationLineItem.list("id in ?1", lineItemIds);
            if (lineItems.isEmpty()) return result;
            Set<UUID> quotationIds = new LinkedHashSet<>();
            for (QuotationLineItem li : lineItems) {
                if (li.quotationId != null) quotationIds.add(li.quotationId);
            }
            if (quotationIds.isEmpty()) return result;
            List<Quotation> quotations = Quotation.list("id in ?1", new ArrayList<>(quotationIds));
            quotationById = new LinkedHashMap<>();
            for (Quotation q : quotations) quotationById.put(q.id, q);

            // 分组：(costingCardTemplateId, priceBaseDate)。无核价模板的行本就不需要预渲染
            // （upgrade() S5 对 costingCardTemplateId==null 直接跳过核价侧重算），不入组。
            groups = new LinkedHashMap<>();
            for (QuotationLineItem li : lineItems) {
                Quotation q = quotationById.get(li.quotationId);
                if (q == null || q.costingCardTemplateId == null) continue;
                LocalDate priceBaseDate = PriceBaseDateUtil.deriveFrom(q.createdAt);
                GroupKey key = new GroupKey(q.costingCardTemplateId, priceBaseDate);
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(li);
            }
        } catch (Exception ex) {
            LOG.warnf(ex, "[price-adjust-job] jobId=%s 批量预渲染取数/分组阶段异常，全部回退逐项渲染: %s", jobId, ex.getMessage());
            return new LinkedHashMap<>();
        }

        for (Map.Entry<GroupKey, List<QuotationLineItem>> e : groups.entrySet()) {
            GroupKey key = e.getKey();
            List<QuotationLineItem> groupItems = e.getValue();
            try {
                Map<UUID, Map<String, ArrayNode>> rendered = renderGroupInNewTx(jobId, key, groupItems, quotationById);
                if (rendered == null) {
                    // FR-4 守卫 1 命中 PER_LINE_ITEM：正常分流，不是异常，不写日志噪音（renderGroupInNewTx 内已 WARN）。
                    continue;
                }
                for (QuotationLineItem li : groupItems) {
                    result.put(li.id, new CardSnapshotService.PrecomputedTreeRows(rendered.get(li.id)));
                }
            } catch (Exception ex) {
                // FR-5 守卫 2：批量预渲染分组失败（含 FR-6 customerId 唯一性断言、render() 本身抛异常）
                // -> 该组不写入任何 item 的预渲染结果，各 item 走 upgrade() 默认路径（内部各自逐项调用
                // render()），让 FAILED 精确落到出问题的单个 item，不拖累整组 / 整批。该分组自己的
                // REQUIRES_NEW 事务已随异常回滚，不影响本方法继续处理其它分组。
                LOG.warnf(ex, "[price-adjust-job] jobId=%s 批量预渲染分组 (template=%s, priceBaseDate=%s, items=%d) " +
                        "失败，回退逐项渲染（FR-5）: %s",
                    jobId, key.templateId, key.priceBaseDate, groupItems.size(), ex.getMessage());
            }
        }
        LOG.infof("[price-adjust-job] jobId=%s 批量预渲染完成：%d/%d 个 line item 命中预渲染结果",
            jobId, result.size(), items.size());
        return result;
    }

    /**
     * 单分组渲染，独立事务（见 {@link #precomputeBatch} 的 REQUIRES_NEW 必要性说明）。
     *
     * @return 该分组的 {@code render()} 结果；{@code null} = 正常跳过、不是异常（FR-4 守卫命中
     *         PER_LINE_ITEM，或本分组模板不含树页签——见下方门槛说明）；抛异常 = FR-6 断言失败或
     *         {@code render()} 本身失败，由调用方（{@link #precomputeBatch}）捕获并软回退。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    Map<UUID, Map<String, ArrayNode>> renderGroupInNewTx(UUID jobId, GroupKey key, List<QuotationLineItem> groupItems,
                                                          Map<UUID, Quotation> quotationById) {
        // 🔒 亲验反馈补丁（2026-08-07）：老路径 refreshCostingCardValuesForLine 在调用 render() 前
        // 有一道 CardSnapshotService#templateHasTreeTab 门槛——不含树页签的核价模板走
        // expandFlatDriverBaseRows 旧引擎，precomputedBaseRows 恒为 null。批量路径若无条件对所有
        // 模板都跑 render() 并把结果当 precomputed 喂给 buildCostingCardValues，会让"不含树页签的
        // 模板"额外多算出一份 render() 结果、且下游据此走了"precomputedBaseRows != null"分支——
        // 与老路径分叉进 buildCostingCardValues 的不同代码分支，产出可能不同。当前库两个在用核价
        // 模板都含树页签（不可达），但这正是守卫纪律要防的"未来有人建一个不含树页签的核价模板"那类
        // 潜伏分叉，必须堵死：不含树页签的模板整组不批量，直接跳过（items 走 upgrade() 默认路径，
        // 其内部 templateHasTreeTab 判定为 false 时 precomputed 恒为 null，与改造前逐位一致）。
        if (!cardSnapshotService.templateHasTreeTab(key.templateId)) {
            LOG.infof("[price-adjust-job] jobId=%s 分组 (template=%s, priceBaseDate=%s, items=%d) " +
                    "模板不含树页签，不参与批量预渲染（与老路径 templateHasTreeTab 门槛对齐）",
                jobId, key.templateId, key.priceBaseDate, groupItems.size());
            return null;
        }

        // FR-6：分组内 customerId 必须唯一，不唯一直接抛错（被 precomputeBatch 的 catch 接住 -> 软回退逐项）。
        // 🔒 复用 precomputeBatch 已批量加载的 quotationById（N+1 纪律）——quotationById 里的 Quotation
        // 是外层事务加载的托管实体，本方法只读它们的标量字段（customerId），不触发懒加载，跨事务读安全。
        UUID groupCustomerId = null;
        boolean first = true;
        for (QuotationLineItem li : groupItems) {
            Quotation q = quotationById.get(li.quotationId);
            UUID cid = q != null ? q.customerId : null;
            if (first) {
                groupCustomerId = cid;
                first = false;
            } else if (!Objects.equals(groupCustomerId, cid)) {
                throw new IllegalStateException(String.format(
                    "分组 (template=%s, priceBaseDate=%s) 内 customerId 不唯一（%s vs %s）—— " +
                    "render() 只取分组首个 line item 的 customerId，跨客户会静默串号，拒绝批量渲染",
                    key.templateId, key.priceBaseDate, groupCustomerId, cid));
            }
        }

        // FR-4 守卫 1：分组内任一 driver 组件不安全（PER_LINE_ITEM）-> 整组不批量，逐项兜底。
        BatchSafetyLevel level = safetyAuditor.worstLevelForTemplate(key.templateId);
        if (level == BatchSafetyLevel.PER_LINE_ITEM) {
            LOG.warnf("[price-adjust-job] jobId=%s 分组 (template=%s, priceBaseDate=%s, items=%d) " +
                    "含 PER_LINE_ITEM 组件，不批量渲染，逐项走默认路径",
                jobId, key.templateId, key.priceBaseDate, groupItems.size());
            return null;
        }

        return bomTreeRenderService.render(key.templateId, groupItems);
    }

    @Transactional
    List<MaterialPriceUpdateJobItem> loadWaitingItems(UUID jobId) {
        return MaterialPriceUpdateJobItem.list(
            "jobId = ?1 and status in (?2, ?3)", jobId, MaterialPriceUpdateJobItem.WAITING, MaterialPriceUpdateJobItem.CONFLICT);
    }

    /**
     * 单条明细：找目标版本 + 调用真实升版（dryRun=false），逐条独立事务提交。
     *
     * <p>task-0729 debug（2026-08-03）真根因修复：{@code @ActivateRequestContext} 从
     * {@link #executeJob} 下移到本方法——原先挂在 {@code executeJob} 上时，request-scoped bean
     * （如 {@code DataLoader}）在嵌套进本方法的 {@code @Transactional(REQUIRES_NEW)} 后无法解析，
     * 实测在每个 job item 上 100% 抛 {@code ContextNotActiveException}（272 次/34 项批次），
     * 被 {@code BomTreeRenderService} §④ 逐组件 catch 静默吞掉，导致「物料与元素BOM」等全部
     * driver 组件页签清零、却仍报 {@code SUCCESS}。挂在本方法（即 REQUIRES_NEW 事务边界本身）
     * 上后，request context 与该事务同生命周期，验证通过。
     *
     * <p>🔒 task-0806：{@code @ActivateRequestContext}/{@code @Transactional(REQUIRES_NEW)} 的挂载
     * 位置原样不动（需求文档硬约束 4）——本次只新增 {@code precomputed} 参数，未改注解、未改事务边界。
     */
    @ActivateRequestContext
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void executeItem(UUID itemId) {
        executeItem(itemId, null);
    }

    /**
     * task-0806 · FR-1 载体：接受批量预渲染好的核价树结果，透传给
     * {@link MaterialVersionUpgradeService#upgrade(UUID, UUID, boolean, CardSnapshotService.PrecomputedTreeRows)}。
     * {@code precomputed == null}（未命中批量预渲染 / 单条重试）时行为与改造前的
     * {@link #executeItem(UUID)} 逐位一致。
     */
    @ActivateRequestContext
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    String executeItem(UUID itemId, CardSnapshotService.PrecomputedTreeRows precomputed) {
        // repair-260918：返回本条执行后的状态（分组路径据此收集本组 SUCCESS 料号，不再回查库）。
        MaterialPriceUpdateJobItem item = MaterialPriceUpdateJobItem.findById(itemId);
        if (item == null) return null;
        if (MaterialPriceUpdateJobItem.STALE.equals(item.status)) return item.status; // 终态不处理

        item.status = MaterialPriceUpdateJobItem.RUNNING;
        item.updatedAt = OffsetDateTime.now();
        item.persist();

        MaterialPriceUpdateJob job = MaterialPriceUpdateJob.findById(item.jobId);
        if (job == null || job.versionId == null) {
            item.status = MaterialPriceUpdateJobItem.FAILED;
            item.errorCode = "JOB_NOT_FOUND";
            item.errorMessage = "job 或 versionId 缺失";
            item.persist();
            return item.status;
        }
        if (item.lineItemId == null) {
            item.status = MaterialPriceUpdateJobItem.FAILED;
            item.errorCode = "LINE_ITEM_MISSING";
            item.errorMessage = "line item 缺失";
            item.persist();
            return item.status;
        }

        UpgradeResult ur = materialVersionUpgradeService.upgrade(item.lineItemId, job.versionId, false, precomputed);
        // ---- 方向3 T2：L3 口径守卫告警落库（非 dryRun 路径）----
        // 🔒 warn_* 与 error_* 正交：status 仍是 SUCCESS，只是顺带检出前后端算值分叉、**未阻断**。
        //    刻意不复用 errorCode —— 本类语义是「errorCode 非空 = 非成功态」，复用会产出
        //    「status=SUCCESS 却带 errorCode」的行，把屏 7 的「可重试」判定带偏。
        //    差异值复用既有 diffValue 列（语义相同，不新增）。
        item.warnCode = ur.warnCode;
        item.warnMessage = ur.warnMessage;
        if (ur.warnCode != null) {
            if (ur.diffValue != null) item.diffValue = ur.diffValue;
            LOG.warnf("[price-adjust-job] item=%s material=%s L3 守卫告警 %s diff=%s（不阻断升版）",
                item.id, item.materialNo, ur.warnCode, ur.diffValue);
        }
        switch (ur.status) {
            // repair-0807 FR-4：SKIPPED 独立终态，不再并入 SUCCESS（api.md §2.2）。errorCode 保持
            // null——本模块既定语义是「errorCode 非空 = 非成功态需人工处理」，SKIPPED 是设计内的
            // "不处理"，占用 errorCode 会把屏 7 的可重试判定带偏。
            case SUCCESS -> {
                item.status = MaterialPriceUpdateJobItem.SUCCESS;
                item.errorCode = null;
                item.errorMessage = ur.message;
            }
            case SKIPPED -> {
                item.status = MaterialPriceUpdateJobItem.SKIPPED;
                item.errorCode = null;
                item.errorMessage = ur.message;
            }
            case CONFLICT -> {
                item.status = MaterialPriceUpdateJobItem.CONFLICT;
                item.errorCode = "ROW_VERSION_CONFLICT";
                item.errorMessage = ur.message;
                item.retryCount = item.retryCount + 1;
            }
            case FAILED -> {
                item.status = MaterialPriceUpdateJobItem.FAILED;
                item.errorCode = ur.errorCode;
                item.errorMessage = ur.message;
                item.diffValue = ur.diffValue;
            }
        }
        item.updatedAt = OffsetDateTime.now();
        item.persist();
        return item.status;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void markItemFailed(UUID itemId, String errorCode, String message) {
        MaterialPriceUpdateJobItem item = MaterialPriceUpdateJobItem.findById(itemId);
        if (item == null) return;
        item.status = MaterialPriceUpdateJobItem.FAILED;
        item.errorCode = errorCode;
        item.errorMessage = message;
        item.updatedAt = OffsetDateTime.now();
        item.persist();
    }

    /** 汇总批次状态：全部 SUCCESS→SUCCESS；有成功有非成功→PARTIAL；全部非成功→FAILED。 */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void finalizeJob(UUID jobId) {
        MaterialPriceUpdateJob job = MaterialPriceUpdateJob.findById(jobId);
        if (job == null) return;
        List<MaterialPriceUpdateJobItem> all = MaterialPriceUpdateJobItem.listByJob(jobId);
        // 🔒 计数口径唯一实现见 MaterialPriceUpdateJob#recountFrom —— supersede 转 STALE 后也走它，
        //    两处各写一份迟早漂（本方法原先是唯一重算点，被取代的 job 永远走不到这里）。
        job.recountFrom(all);
        job.finishedAt = OffsetDateTime.now();
        job.persist();
        LOG.infof("[price-adjust-job] jobId=%s finalized status=%s total=%d success=%d failed=%d conflict=%d stale=%d",
            jobId, job.status, job.totalCount, job.successCount, job.failedCount, job.conflictCount, job.staleCount);

        // task-0729 B11：批次终态落库后通知（触发财务 + 受影响报价单销售负责人）。非阻断——
        // 通知失败绝不能让批次 finalize 结果回滚（同 PriceReconciler 接入 saveDraft 的既定手法）。
        try {
            notificationService.notifyJobCompletion(jobId);
        } catch (Exception e) {
            LOG.warnf(e, "[price-adjust-job] jobId=%s 通知发送失败（不影响批次结果）", jobId);
        }
    }

    // -------------------------------------------------------------------------
    // §3.4/§3.5 重试
    // -------------------------------------------------------------------------

    /**
     * §3.4 批量重试。repair-260918 B-13：重新执行本批 FAILED + CONFLICT 明细（STALE 不动），与接口注释一致
     * （原先只捞 WAITING / CONFLICT，失败明细从不被重跑）；执行走 B-4 分组路径、B-5 执行中可见、B-10 兜底。
     */
    public void retryJob(UUID jobId) {
        markJobRunning(jobId);
        runJob(jobId, true);
    }

    /** B-13：批量重试的执行范围 = WAITING（理论上收尾后不再有）+ FAILED + CONFLICT；STALE / SKIPPED / SUCCESS 不动。 */
    @Transactional
    List<MaterialPriceUpdateJobItem> loadRetryItems(UUID jobId) {
        return MaterialPriceUpdateJobItem.list(
            "jobId = ?1 and status in (?2, ?3, ?4)", jobId,
            MaterialPriceUpdateJobItem.WAITING, MaterialPriceUpdateJobItem.FAILED, MaterialPriceUpdateJobItem.CONFLICT);
    }

    @Transactional
    void markJobRunning(UUID jobId) {
        MaterialPriceUpdateJob job = MaterialPriceUpdateJob.findById(jobId);
        if (job == null) return;
        job.status = MaterialPriceUpdateJob.RUNNING;
        job.finishedAt = null;
        job.persist();
    }

    public void retryJobItem(UUID itemId) {
        MaterialPriceUpdateJobItem item = loadItem(itemId);
        if (item == null) {
            throw new com.cpq.common.exception.BusinessException(404, "job item 不存在: " + itemId);
        }
        if (MaterialPriceUpdateJobItem.STALE.equals(item.status)) {
            throw new com.cpq.common.exception.BusinessException(409, "所属版本已被取代，STALE 项不可重试");
        }
        // repair-260918：单条重试 = 完整路径（本期快照在 upgrade() 自己的事务里写，不延后）。
        // B-5 执行前置 RUNNING；B-6 异常时按可读口径落库（原先异常时明细停在旧状态、批次不重算）；
        // finally 照常 finalizeJob 重新汇总批次。
        try {
            markItemRunning(itemId);
            executeItem(itemId);
        } catch (Throwable t) {
            LOG.errorf(t, "[price-adjust-job] retryJobItem item=%s 执行异常", itemId);
            String[] f = PriceAdjustFailureTranslator.forJobItem(t);
            try {
                markItemFailed(itemId, f[0], f[1]);
            } catch (Throwable x) {
                LOG.errorf(x, "[price-adjust-job] retryJobItem item=%s 落库失败状态时再次异常", itemId);
            }
            if (t instanceof Error) throw (Error) t;
        } finally {
            finalizeJob(item.jobId);
        }
    }

    @Transactional
    MaterialPriceUpdateJobItem loadItem(UUID itemId) {
        return MaterialPriceUpdateJobItem.findById(itemId);
    }
}
