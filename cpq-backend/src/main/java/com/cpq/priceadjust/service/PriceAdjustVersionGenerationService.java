package com.cpq.priceadjust.service;

import com.cpq.priceadjust.entity.CustomerPriceAdjustElement;
import com.cpq.priceadjust.entity.CustomerPriceAdjustStrategy;
import com.cpq.priceadjust.entity.ElementPriceVersion;
import com.cpq.priceadjust.entity.ElementPriceVersionItem;
import com.cpq.priceadjust.entity.MaterialPriceReview;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJob;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJobItem;
import com.cpq.priceadjust.exception.PendingVersionExistsException;
import com.cpq.priceadjust.exception.StrategyNoElementsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * task-0729 B3 · 定时扫描 + 版本生成（单一入口 {@link #generateVersion}，定时/手动共用）。
 *
 * <p>版本号 = V + YYMMDD + 两位当日流水（按客户+日期独立计数，不用自增整数，硬约束 8）。
 * 幂等键 = UNIQUE(customer_no, scheduled_slot)：同一周期点的重复扫描直接返回既有版本，
 * 服务重启错过时刻时下次扫描自然补跑（backtask B3）。
 *
 * <p>🔒 与手动生成走完全相同的代码路径（验收 #5 / #67③）——服务端只凭 confirmSupersede 决定
 * 是否放行「作废旧 PENDING 版本」，不分第二条生成逻辑。
 */
@ApplicationScoped
public class PriceAdjustVersionGenerationService {

    private static final Logger LOG = Logger.getLogger(PriceAdjustVersionGenerationService.class);
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    @Inject EntityManager em;
    @Inject PriceAdjustBudgetService budgetService;
    @Inject ManagedExecutor managedExecutor;

    public static class GenerateResult {
        public UUID versionId;
        public String versionNo;
        public LocalDate baseDate;
        public int itemCount;
        /** 无独立「预算批次」表；沿用 versionId 作为该次异步预算计算的关联键（api.md 1.11 响应字段）。 */
        public UUID budgetJobId;
        public String budgetStatus;
        /** true=命中幂等键直接返回既有版本（未新建、未重新入队预算） */
        public boolean alreadyExisted;
    }

    /**
     * 供 Resource 层 / 定时任务调用的对外入口：先同步生成版本（毫秒级），
     * 事务提交后再把预算计算投递后台队列（E14-3）。
     *
     * <p>🔒 async 派发必须放在 {@link #generateVersion} 之外——它是 {@code @Transactional}，
     * 若在其内部调用 {@code managedExecutor.runAsync}，异步线程可能在外层事务提交前就跑起来，
     * 读不到刚插入的 version/items（沿用 QuoteImportService 的既有模式：processImport 也是从
     * 非事务的 resource 层触发 runAsync，而不是在事务方法内部）。
     */
    public GenerateResult generateVersionAndEnqueueBudget(
            String customerNo, boolean confirmSupersede, String triggerType, OffsetDateTime scheduledSlot) {
        GenerateResult r = generateVersion(customerNo, confirmSupersede, triggerType, scheduledSlot);
        if (!r.alreadyExisted) {
            UUID versionId = r.versionId;
            managedExecutor.runAsync(() -> budgetService.onVersionGenerated(versionId));
        }
        return r;
    }

    @Transactional
    public GenerateResult generateVersion(
            String customerNo, boolean confirmSupersede, String triggerType, OffsetDateTime scheduledSlot) {
        if (customerNo == null || customerNo.isBlank()) {
            throw new com.cpq.common.exception.BusinessException(400, "customerNo 不能为空");
        }

        // 幂等 + 天然补跑：同一 (customer_no, scheduled_slot) 已处理过 → 直接返回既有版本，不重复生成。
        if (scheduledSlot != null) {
            ElementPriceVersion existing =
                ElementPriceVersion.find("customerNo = ?1 and scheduledSlot = ?2", customerNo, scheduledSlot)
                    .firstResult();
            if (existing != null) {
                GenerateResult r = new GenerateResult();
                r.versionId = existing.id;
                r.versionNo = existing.versionNo;
                r.baseDate = existing.baseDate;
                r.itemCount = (int) ElementPriceVersionItem.count("versionId", existing.id);
                r.budgetJobId = existing.id;
                r.budgetStatus = "QUEUED";
                r.alreadyExisted = true;
                return r;
            }
        }

        CustomerPriceAdjustStrategy strategy = CustomerPriceAdjustStrategy.findByCustomerNo(customerNo);
        if (strategy == null) {
            throw new com.cpq.common.exception.BusinessException(404, "客户 " + customerNo + " 未配置调价策略");
        }

        List<CustomerPriceAdjustElement> elements = CustomerPriceAdjustElement.listByStrategy(strategy.id);
        if (elements.isEmpty()) {
            throw new StrategyNoElementsException(customerNo);
        }
        List<String> elementCodes = elements.stream().map(e -> e.elementCode).distinct().toList();

        LocalDate baseDate = LocalDate.now();

        // 已有 PENDING 版本 → 手动触发需 confirmSupersede；定时触发调用方恒传 true（直接供后续供后接受）。
        ElementPriceVersion pending = ElementPriceVersion.findPending(customerNo);
        if (pending != null && !confirmSupersede) {
            long pendingCount = MaterialPriceReview.count(
                "versionId = ?1 and status = ?2", pending.id, MaterialPriceReview.STATUS_PENDING);
            long approvedCount = MaterialPriceReview.count(
                "versionId = ?1 and status = ?2", pending.id, MaterialPriceReview.STATUS_APPROVED);
            throw new PendingVersionExistsException(pending.versionNo, pendingCount, approvedCount);
        }

        // 上一版（不分状态：PENDING 或 SUPERSEDED 均可），用于涨跌幅 + 无价沿用（E2/§11.3.2.1）
        ElementPriceVersion previous = ElementPriceVersion.findLatest(customerNo);
        Map<String, ElementPriceVersionItem> prevByCode = new HashMap<>();
        if (previous != null) {
            for (ElementPriceVersionItem it : ElementPriceVersionItem.listByVersion(previous.id)) {
                prevByCode.put(it.elementCode, it);
            }
        }

        // 当期实时价：f_customer_element_price（task-0722 既有函数，签名不动）
        Map<String, Object[]> currentPrices = loadCurrentPrices(customerNo, baseDate, elementCodes);

        // 先在内存里逐元素算完，E14-10 判定通过后才真正写库（避免半途失败留半截版本）
        List<ElementPriceVersionItem> newItems = new ArrayList<>();
        boolean anyPriced = false;
        for (String code : elementCodes) {
            ElementPriceVersionItem item = new ElementPriceVersionItem();
            item.elementCode = code;
            Object[] cur = currentPrices.get(code);
            ElementPriceVersionItem prev = prevByCode.get(code);
            item.previousPrice = prev != null ? prev.currentPrice : null;

            if (cur != null && cur[0] != null) {
                item.currentPrice = (BigDecimal) cur[0];
                item.currency = (String) cur[1];
                item.priceUnit = (String) cur[2];
                item.noPrice = false;
                item.inheritedFromPrevious = false;
            } else if (prev != null && prev.currentPrice != null) {
                // 本期无价但有上一版价 → 沿用上一版价（§11.3.2.1）
                item.currentPrice = prev.currentPrice;
                item.currency = prev.currency;
                item.priceUnit = prev.priceUnit;
                item.noPrice = true;
                item.inheritedFromPrevious = true;
            } else {
                // 本期无价且从无历史价 → 彻底无价
                item.currentPrice = null;
                item.currency = null;
                item.priceUnit = null;
                item.noPrice = true;
                item.inheritedFromPrevious = false;
            }

            if (item.currentPrice != null) {
                anyPriced = true;
                if (item.previousPrice != null && item.previousPrice.signum() != 0) {
                    item.changeRate = item.currentPrice.subtract(item.previousPrice)
                        .divide(item.previousPrice, 6, RoundingMode.HALF_UP);
                }
            }
            newItems.add(item);
        }

        if (!anyPriced) {
            // E14-10：勾选元素全部既无本期价也无历史价 → 不生成任何版本
            throw new StrategyNoElementsException(customerNo);
        }

        // 通过校验，正式写库：先失效旧 PENDING（若存在）
        if (pending != null) {
            List<MaterialPriceUpdateJob> pendingJobs = MaterialPriceUpdateJob.list("versionId", pending.id);
            List<UUID> staleJobIds = pendingJobs.stream().map(j -> j.id).toList();
            pending.status = ElementPriceVersion.STATUS_SUPERSEDED;
            pending.persist();
            MaterialPriceUpdateJobItem.staleAllUnfinishedByJobIds(staleJobIds);
        }

        String versionNo = nextVersionNo(customerNo, baseDate);
        ElementPriceVersion version = new ElementPriceVersion();
        version.customerNo = customerNo;
        version.versionNo = versionNo;
        version.baseDate = baseDate;
        version.status = ElementPriceVersion.STATUS_PENDING;
        version.triggerType = triggerType;
        version.scheduledSlot = scheduledSlot;
        version.persist();

        for (ElementPriceVersionItem item : newItems) {
            item.versionId = version.id;
            item.persist();
        }

        LOG.infof("[price-adjust] generated version customer=%s versionNo=%s items=%d triggerType=%s",
            customerNo, versionNo, newItems.size(), triggerType);

        GenerateResult result = new GenerateResult();
        result.versionId = version.id;
        result.versionNo = version.versionNo;
        result.baseDate = version.baseDate;
        result.itemCount = newItems.size();
        result.budgetJobId = version.id;
        result.budgetStatus = "QUEUED";
        result.alreadyExisted = false;
        return result;
    }

    // -------------------------------------------------------------------------

    private Map<String, Object[]> loadCurrentPrices(String customerNo, LocalDate baseDate, List<String> elementCodes) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT element_code, unit_price, currency, price_unit " +
                "FROM f_customer_element_price(:cno, :bd) WHERE element_code = ANY(:codes)")
            .setParameter("cno", customerNo)
            .setParameter("bd", baseDate)
            .setParameter("codes", elementCodes.toArray(new String[0]))
            .getResultList();
        Map<String, Object[]> out = new HashMap<>();
        for (Object[] r : rows) {
            out.put((String) r[0], new Object[]{r[1], r[2], r[3]});
        }
        return out;
    }

    /** 版本号 = V + YYMMDD + 两位当日流水，按客户+日期独立计数（硬约束 8：不用自增整数）。 */
    private String nextVersionNo(String customerNo, LocalDate baseDate) {
        String prefix = "V" + baseDate.format(YYMMDD);
        @SuppressWarnings("unchecked")
        List<String> existing = em.createNativeQuery(
                "SELECT version_no FROM element_price_version WHERE customer_no = :cno AND version_no LIKE :prefix")
            .setParameter("cno", customerNo)
            .setParameter("prefix", prefix + "%")
            .getResultList();
        int maxSeq = 0;
        for (String vn : existing) {
            if (vn != null && vn.length() == prefix.length() + 2) {
                try {
                    int seq = Integer.parseInt(vn.substring(prefix.length()));
                    if (seq > maxSeq) maxSeq = seq;
                } catch (NumberFormatException ignore) { /* 忽略非两位数字尾缀的历史脏数据 */ }
            }
        }
        return prefix + String.format("%02d", maxSeq + 1);
    }
}
