package com.cpq.priceadjust.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.priceadjust.dto.*;
import com.cpq.priceadjust.entity.*;
import com.cpq.priceadjust.exception.ElementUnselectNeedsConfirmException;
import com.cpq.priceadjust.exception.MaterialRemovalNeedsConfirmException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * task-0729 策略 CRUD 补交（api.md §1.1~§1.7）——backtask 划分遗漏，2026-08-03 由 coordinator
 * 发现并补派。屏 1 的策略保存 / 料号矩阵 / 元素矩阵 / 变更历史的唯一后端实现。
 *
 * <p>🔒 异步派发纪律（同 B3/B5 既定模式）：预算重算走 {@code managedExecutor.runAsync}，
 * 派发必须放在写库事务提交之后——本类每个写方法都拆成 {@code @Transactional} 内部方法 +
 * 非事务对外入口两段，内部方法返回待派发清单，外层提交后再 runAsync。
 */
@ApplicationScoped
public class PriceAdjustStrategyService {

    private static final Logger LOG = Logger.getLogger(PriceAdjustStrategyService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<String> VALID_CYCLE_TYPES = Set.of("DAILY", "WEEKLY", "MONTHLY_DAY", "MONTHLY_NTH_WEEK");
    private static final Set<String> VALID_SCOPE_MODES = Set.of("ALL", "SPECIFIED");

    @Inject EntityManager em;
    @Inject PriceAdjustBudgetService budgetService;
    @Inject ManagedExecutor managedExecutor;

    private record ReviewRef(UUID versionId, String materialNo) {}

    // =========================================================================
    // §1.1 GET 策略主体
    // =========================================================================

    public StrategyDTO getStrategy(String customerNo) {
        validateCustomerNo(customerNo);
        CustomerPriceAdjustStrategy s = CustomerPriceAdjustStrategy.findByCustomerNo(customerNo);
        if (s == null) return StrategyDTO.notExists(customerNo);
        return toDto(s);
    }

    private StrategyDTO toDto(CustomerPriceAdjustStrategy s) {
        StrategyDTO dto = new StrategyDTO();
        dto.exists = true;
        dto.customerNo = s.customerNo;
        dto.enabled = s.enabled;
        dto.cycleType = s.cycleType;
        dto.cycleWeekday = s.cycleWeekday;
        dto.cycleDayOfMonth = s.cycleDayOfMonth;
        dto.cycleNthWeek = s.cycleNthWeek;
        dto.executeTime = s.executeTime != null ? s.executeTime.format(HHMM) : null;
        dto.materialScopeMode = s.materialScopeMode;
        dto.costDiffThreshold = s.costDiffThreshold;
        ElementPriceVersion latest = ElementPriceVersion.findLatest(s.customerNo);
        dto.latestVersionNo = latest != null ? latest.versionNo : null;
        ElementPriceVersion pending = ElementPriceVersion.findPending(s.customerNo);
        dto.pendingVersionNo = pending != null ? pending.versionNo : null;
        dto.materialCount = (int) CustomerPriceAdjustMaterial.count("strategyId", s.id);
        dto.elementCount = (int) CustomerPriceAdjustElement.count("strategyId", s.id);
        dto.hasComparisonConfig = ComparisonColumnConfig.count("customerNo", s.customerNo) > 0;
        dto.updatedAt = s.updatedAt;
        dto.updatedBy = resolveUserName(s.updatedBy);
        return dto;
    }

    // =========================================================================
    // §1.2 PUT 策略主体
    // =========================================================================

    public StrategyDTO putStrategy(String customerNo, PutStrategyRequest req, UUID actorId) {
        PutStrategyOutcome outcome = doPutStrategy(customerNo, req, actorId);
        dispatchRecompute(customerNo, outcome.toRecompute);
        return outcome.dto;
    }

    private static final class PutStrategyOutcome {
        StrategyDTO dto;
        List<ReviewRef> toRecompute = List.of();
    }

    @Transactional
    PutStrategyOutcome doPutStrategy(String customerNo, PutStrategyRequest req, UUID actorId) {
        validateCustomerNo(customerNo);
        if (req == null) throw new BusinessException(400, "请求体不能为空");
        validateCycleType(req.cycleType);
        validateScopeMode(req.materialScopeMode);

        CustomerPriceAdjustStrategy s = CustomerPriceAdjustStrategy.findByCustomerNo(customerNo);
        boolean isNew = s == null;
        String beforeJson = isNew ? null : serializeStrategy(s);

        boolean triggerChanged = false;
        if (!isNew) {
            boolean enabledChanged = !Objects.equals(s.enabled, req.enabled);
            boolean thresholdChanged = !bigDecimalEquals(s.costDiffThreshold, req.costDiffThreshold);
            triggerChanged = enabledChanged || thresholdChanged;
        }

        if (isNew) {
            s = new CustomerPriceAdjustStrategy();
            s.customerNo = customerNo;
            s.createdBy = actorId;
        }
        s.enabled = req.enabled != null ? req.enabled : Boolean.TRUE;
        s.cycleType = req.cycleType;
        s.cycleWeekday = req.cycleWeekday;
        s.cycleDayOfMonth = req.cycleDayOfMonth;
        s.cycleNthWeek = req.cycleNthWeek;
        s.executeTime = parseExecuteTime(req.executeTime);
        s.materialScopeMode = req.materialScopeMode != null ? req.materialScopeMode : "ALL";
        s.costDiffThreshold = req.costDiffThreshold != null ? req.costDiffThreshold : BigDecimal.ZERO;
        s.updatedAt = OffsetDateTime.now();
        s.updatedBy = actorId;
        s.persist();

        String afterJson = serializeStrategy(s);
        writeAuditLog(s, CustomerPriceAdjustStrategyLog.CHANGE_TYPE_STRATEGY, "策略配置变更", beforeJson, afterJson, actorId);

        PutStrategyOutcome outcome = new PutStrategyOutcome();
        StrategyDTO dto = toDto(s);
        if (!isNew && triggerChanged) {
            // 🔒 costDiffThreshold 或 enabled 变化 → 触发该客户全部「待处理」料号预算重算（§1.2 服务端行为3）。
            outcome.toRecompute = markPendingForRecompute(customerNo);
        }
        dto.budgetRecomputeTriggered = !outcome.toRecompute.isEmpty();
        dto.affectedReviewCount = outcome.toRecompute.size();
        outcome.dto = dto;
        return outcome;
    }

    // =========================================================================
    // §1.3 GET 指定料号矩阵
    // =========================================================================

    public PageResult<MaterialRowDTO> getMaterials(String customerNo, int page, int size,
            String customerPartNo, String customerMaterialName, String materialNo, String materialName,
            boolean selectedOnly) {
        validateCustomerNo(customerNo);
        page = Math.max(page, 1);
        size = Math.max(size, 1);

        CustomerPriceAdjustStrategy s = CustomerPriceAdjustStrategy.findByCustomerNo(customerNo);
        Set<String> selectedSet = s != null
            ? CustomerPriceAdjustMaterial.listByStrategy(s.id).stream().map(m -> m.materialNo).collect(java.util.stream.Collectors.toSet())
            : Set.of();

        StringBuilder where = new StringBuilder("mcm.system_type = 'QUOTE' AND mcm.customer_no = :cno");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cno", customerNo);
        if (notBlank(customerPartNo)) {
            where.append(" AND mcm.customer_product_no ILIKE :cpn");
            params.put("cpn", "%" + customerPartNo + "%");
        }
        if (notBlank(customerMaterialName)) {
            where.append(" AND mcm.customer_material_name ILIKE :cmn");
            params.put("cmn", "%" + customerMaterialName + "%");
        }
        if (notBlank(materialNo)) {
            where.append(" AND mcm.material_no ILIKE :mno");
            params.put("mno", "%" + materialNo + "%");
        }
        if (notBlank(materialName)) {
            where.append(" AND mm.material_name ILIKE :mname");
            params.put("mname", "%" + materialName + "%");
        }
        if (selectedOnly) {
            if (selectedSet.isEmpty()) {
                where.append(" AND 1=0");
            } else {
                where.append(" AND mcm.material_no = ANY(:sel)");
                params.put("sel", selectedSet.toArray(new String[0]));
            }
        }

        String from = "FROM material_customer_map mcm LEFT JOIN material_master mm ON mm.material_no = mcm.material_no WHERE " + where;
        var countQuery = em.createNativeQuery("SELECT count(*) " + from);
        params.forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        var dataQuery = em.createNativeQuery(
            "SELECT mcm.material_no, mm.material_name, mcm.customer_product_no, mcm.customer_material_name " +
            from + " ORDER BY mcm.material_no LIMIT :lim OFFSET :off");
        params.forEach(dataQuery::setParameter);
        dataQuery.setParameter("lim", size);
        dataQuery.setParameter("off", (long) (page - 1) * size);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = dataQuery.getResultList();
        List<MaterialRowDTO> content = new ArrayList<>();
        for (Object[] r : rows) {
            MaterialRowDTO dto = new MaterialRowDTO();
            dto.materialNo = (String) r[0];
            dto.materialName = (String) r[1];
            dto.customerPartNo = (String) r[2];
            dto.customerMaterialName = (String) r[3];
            dto.selected = selectedSet.contains(dto.materialNo);
            content.add(dto);
        }
        return new PageResult<>(content, page, size, total);
    }

    // =========================================================================
    // §1.4 PUT 指定料号矩阵
    // =========================================================================

    public void putMaterials(String customerNo, PutMaterialsRequest req, UUID actorId) {
        List<ReviewRef> toRecompute = doPutMaterials(customerNo, req, actorId);
        dispatchRecompute(customerNo, toRecompute);
    }

    @Transactional
    List<ReviewRef> doPutMaterials(String customerNo, PutMaterialsRequest req, UUID actorId) {
        validateCustomerNo(customerNo);
        if (req == null || req.materialNos == null) throw new BusinessException(400, "materialNos 不能为空");

        CustomerPriceAdjustStrategy s = findOrCreateStrategy(customerNo, actorId);
        Set<String> existing = CustomerPriceAdjustMaterial.listByStrategy(s.id).stream()
            .map(m -> m.materialNo).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> incoming = new LinkedHashSet<>(req.materialNos);
        Set<String> removed = new LinkedHashSet<>(existing);
        removed.removeAll(incoming);
        Set<String> added = new LinkedHashSet<>(incoming);
        added.removeAll(existing);

        if (!removed.isEmpty() && !req.confirmRemoval) {
            long pendingReviewCount = MaterialPriceReview.count(
                "customerNo = ?1 and materialNo in ?2 and status = ?3",
                customerNo, new ArrayList<>(removed), MaterialPriceReview.STATUS_PENDING);
            long unlockedQuotationCount = countUnlockedQuotationsForMaterials(customerNo, removed);
            throw new MaterialRemovalNeedsConfirmException(new ArrayList<>(removed), pendingReviewCount, unlockedQuotationCount);
        }

        if (!removed.isEmpty()) {
            // 🔒 移出料号的「待处理」审核记录置已作废；「已通过」的既成事实不回滚（§1.4 服务端行为3）。
            MaterialPriceReview.update("status = ?1 where customerNo = ?2 and materialNo in ?3 and status = ?4",
                MaterialPriceReview.STATUS_VOIDED, customerNo, new ArrayList<>(removed), MaterialPriceReview.STATUS_PENDING);
            for (String code : removed) {
                CustomerPriceAdjustMaterial.delete("strategyId = ?1 and materialNo = ?2", s.id, code);
            }
        }
        for (String code : added) {
            CustomerPriceAdjustMaterial m = new CustomerPriceAdjustMaterial();
            m.strategyId = s.id;
            m.materialNo = code;
            m.persist();
        }

        writeAuditLog(s, CustomerPriceAdjustStrategyLog.CHANGE_TYPE_MATERIAL_SCOPE,
            "指定料号 " + existing.size() + " → " + incoming.size(),
            writeJson(existing), writeJson(incoming), actorId);

        // 🔒 新增料号：本期有价格变动则纳入待办池并算预算，无变动不进（同裁决39，复用 B4 processMaterial 的
        // pool-entry 判定，不重复实现）——只对新增料号处理，不重算既有未受影响的料号（§1.4 服务端行为4）。
        if (added.isEmpty()) return List.of();
        ElementPriceVersion pending = ElementPriceVersion.findPending(customerNo);
        if (pending == null) return List.of(); // 尚无待处理版本，交下次版本生成时自然纳入范围
        List<ReviewRef> toRecompute = new ArrayList<>();
        for (String code : added) toRecompute.add(new ReviewRef(pending.id, code));
        return toRecompute;
    }

    private long countUnlockedQuotationsForMaterials(String customerNo, Set<String> materialNos) {
        if (materialNos.isEmpty()) return 0;
        return ((Number) em.createNativeQuery(
                "SELECT count(DISTINCT q.id) FROM quotation q JOIN customer c ON c.id = q.customer_id " +
                "JOIN quotation_line_item li ON li.quotation_id = q.id " +
                "WHERE c.code = :cno AND q.status = ANY(:statuses) AND li.product_part_no_snapshot = ANY(:mnos)")
            .setParameter("cno", customerNo)
            .setParameter("statuses", MaterialVersionUpgradeService.ACTIVE_STATUSES.toArray(new String[0]))
            .setParameter("mnos", materialNos.toArray(new String[0]))
            .getSingleResult()).longValue();
    }

    // =========================================================================
    // §1.5 GET 参与调价元素矩阵（pivot：元素 × 最近10个版本）
    // =========================================================================

    public ElementsMatrixResponse getElements(String customerNo, int page, int size, String keyword, Boolean includeDisabled) {
        validateCustomerNo(customerNo);
        page = Math.max(page, 1);
        size = Math.max(size, 1);
        boolean includeDisabledFlag = includeDisabled == null || includeDisabled;

        CustomerPriceAdjustStrategy s = CustomerPriceAdjustStrategy.findByCustomerNo(customerNo);
        Set<String> selectedSet = s != null
            ? CustomerPriceAdjustElement.listByStrategy(s.id).stream().map(e -> e.elementCode).collect(java.util.stream.Collectors.toSet())
            : Set.of();

        @SuppressWarnings("unchecked")
        List<ElementPriceVersion> versions = ElementPriceVersion.find(
                "customerNo = ?1 order by createdAt desc", customerNo)
            .page(Page.of(0, 10)).list();

        List<VersionColumnDTO> versionColumns = new ArrayList<>();
        for (ElementPriceVersion v : versions) {
            VersionColumnDTO vc = new VersionColumnDTO();
            vc.versionId = v.id;
            vc.versionNo = v.versionNo;
            vc.status = v.status;
            vc.baseDate = v.baseDate;
            versionColumns.add(vc);
        }
        List<UUID> versionIds = versions.stream().map(v -> v.id).toList();

        // 🔒 一次 pivot 查完（性能硬约束 §11.2.4）：一条 IN 查询取回全部版本×元素，禁止逐元素查10次。
        Map<UUID, Map<String, ElementPriceVersionItem>> byVersion = new HashMap<>();
        if (!versionIds.isEmpty()) {
            List<ElementPriceVersionItem> items = ElementPriceVersionItem.list("versionId in ?1", versionIds);
            for (ElementPriceVersionItem it : items) {
                byVersion.computeIfAbsent(it.versionId, k -> new HashMap<>()).put(it.elementCode, it);
            }
        }

        StringBuilder where = new StringBuilder("1=1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (notBlank(keyword)) {
            where.append(" AND (element_code ILIKE :kw OR element_name ILIKE :kw OR element_no ILIKE :kw)");
            params.put("kw", "%" + keyword + "%");
        }
        if (!includeDisabledFlag) {
            where.append(" AND status = 'ACTIVE'");
        }
        var countQuery = em.createNativeQuery("SELECT count(*) FROM element WHERE " + where);
        params.forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        var dataQuery = em.createNativeQuery(
            "SELECT element_code, element_name, element_no, status FROM element WHERE " + where +
            " ORDER BY element_code LIMIT :lim OFFSET :off");
        params.forEach(dataQuery::setParameter);
        dataQuery.setParameter("lim", size);
        dataQuery.setParameter("off", (long) (page - 1) * size);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = dataQuery.getResultList();
        List<ElementRowDTO> content = new ArrayList<>();
        for (Object[] r : rows) {
            ElementRowDTO dto = new ElementRowDTO();
            dto.elementCode = (String) r[0];
            dto.elementName = (String) r[1];
            dto.elementNo = (String) r[2];
            dto.elementEnabled = "ACTIVE".equals(r[3]);
            dto.selected = selectedSet.contains(dto.elementCode);
            List<ElementPriceCellDTO> prices = new ArrayList<>();
            for (ElementPriceVersion v : versions) {
                ElementPriceVersionItem item = byVersion.getOrDefault(v.id, Map.of()).get(dto.elementCode);
                if (item == null) {
                    prices.add(ElementPriceCellDTO.notInList());
                } else if (item.currentPrice == null) {
                    prices.add(ElementPriceCellDTO.noPrice());
                } else {
                    prices.add(ElementPriceCellDTO.normal(item.currentPrice, item.changeRate));
                }
            }
            dto.prices = prices;
            content.add(dto);
        }

        ElementsMatrixResponse resp = new ElementsMatrixResponse();
        resp.versionColumns = versionColumns;
        resp.content = content;
        resp.page = page;
        resp.size = size;
        resp.totalElements = total;
        resp.totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return resp;
    }

    // =========================================================================
    // §1.6 PUT 参与调价元素矩阵
    // =========================================================================

    public void putElements(String customerNo, PutElementsRequest req, UUID actorId) {
        List<ReviewRef> toRecompute = doPutElements(customerNo, req, actorId);
        dispatchRecompute(customerNo, toRecompute);
    }

    @Transactional
    List<ReviewRef> doPutElements(String customerNo, PutElementsRequest req, UUID actorId) {
        validateCustomerNo(customerNo);
        if (req == null || req.elementCodes == null) throw new BusinessException(400, "elementCodes 不能为空");

        CustomerPriceAdjustStrategy s = findOrCreateStrategy(customerNo, actorId);
        Set<String> existing = CustomerPriceAdjustElement.listByStrategy(s.id).stream()
            .map(e -> e.elementCode).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> incoming = new LinkedHashSet<>(req.elementCodes);
        Set<String> removed = new LinkedHashSet<>(existing);
        removed.removeAll(incoming);
        Set<String> added = new LinkedHashSet<>(incoming);
        added.removeAll(existing);

        if (!removed.isEmpty() && !req.confirmUnselect) {
            long unlockedQuotationCount = countUnlockedQuotationsForElements(customerNo, removed);
            throw new ElementUnselectNeedsConfirmException(new ArrayList<>(removed), unlockedQuotationCount);
        }

        // 🔒 禁止服务端自动移出已停用元素（§1.6 服务端行为2）——本方法只按前端传入的 elementCodes
        // 全量覆盖，从不读 element.status 做任何过滤/剔除。
        for (String code : removed) {
            CustomerPriceAdjustElement.delete("strategyId = ?1 and elementCode = ?2", s.id, code);
        }
        for (String code : added) {
            CustomerPriceAdjustElement e = new CustomerPriceAdjustElement();
            e.strategyId = s.id;
            e.elementCode = code;
            e.persist();
        }

        writeAuditLog(s, CustomerPriceAdjustStrategyLog.CHANGE_TYPE_ELEMENT_LIST,
            "参与调价元素 " + existing.size() + " → " + incoming.size(),
            writeJson(existing), writeJson(incoming), actorId);

        if (removed.isEmpty() && added.isEmpty()) return List.of();
        // 元素清单变化影响该客户全部待处理料号的取价结果（比单个料号变更影响面更广，与§1.2同口径）。
        return markPendingForRecompute(customerNo);
    }

    /**
     * 近似查询（如实标注，非精确判定）：扫描该客户活单组件数据的 row_data 文本，命中元素码字面量
     * 视为"可能受影响"。精确判定需按 {@code component.element_code_field} 定位具体字段（同
     * {@code PriceReconciler.prefetch} 的做法），该等价复杂度的下钻本次不重复实现——此数字只用于
     * 二次确认弹窗的预估提示，不影响任何实际写库判定（写库判定仍按 elementCodes 全量覆盖）。
     */
    private long countUnlockedQuotationsForElements(String customerNo, Set<String> elementCodes) {
        if (elementCodes.isEmpty()) return 0;
        List<String> patterns = elementCodes.stream().map(c -> "%\"" + c + "\"%").toList();
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
                "SELECT DISTINCT q.id FROM quotation q JOIN customer c ON c.id = q.customer_id " +
                "JOIN quotation_line_item li ON li.quotation_id = q.id " +
                "JOIN quotation_line_component_data qlcd ON qlcd.line_item_id = li.id " +
                "WHERE c.code = :cno AND q.status = ANY(:statuses) AND qlcd.row_data::text ILIKE ANY(:patterns)")
            .setParameter("cno", customerNo)
            .setParameter("statuses", MaterialVersionUpgradeService.ACTIVE_STATUSES.toArray(new String[0]))
            .setParameter("patterns", patterns.toArray(new String[0]))
            .getResultList();
        return rows.size();
    }

    // =========================================================================
    // §1.7 GET 变更历史
    // =========================================================================

    public PageResult<StrategyLogDTO> getLogs(String customerNo, int page, int size) {
        validateCustomerNo(customerNo);
        page = Math.max(page, 1);
        size = Math.max(size, 1);
        CustomerPriceAdjustStrategy s = CustomerPriceAdjustStrategy.findByCustomerNo(customerNo);
        if (s == null) return new PageResult<>(List.of(), page, size, 0);

        long total = CustomerPriceAdjustStrategyLog.count("strategyId", s.id);
        @SuppressWarnings("unchecked")
        List<CustomerPriceAdjustStrategyLog> rows = CustomerPriceAdjustStrategyLog.find(
                "strategyId = ?1 order by changedAt desc", s.id)
            .page(Page.of(page - 1, size)).list();
        List<StrategyLogDTO> content = rows.stream().map(this::toLogDto).toList();
        return new PageResult<>(content, page, size, total);
    }

    private StrategyLogDTO toLogDto(CustomerPriceAdjustStrategyLog log) {
        StrategyLogDTO dto = new StrategyLogDTO();
        dto.id = log.id;
        dto.changedAt = log.changedAt;
        dto.changedBy = log.changedByName;
        dto.changeType = log.changeType;
        dto.summary = log.summary;
        dto.beforeSnapshot = parseJson(log.beforeSnapshot);
        dto.afterSnapshot = parseJson(log.afterSnapshot);
        return dto;
    }

    // =========================================================================
    // 共享辅助
    // =========================================================================

    private CustomerPriceAdjustStrategy findOrCreateStrategy(String customerNo, UUID actorId) {
        CustomerPriceAdjustStrategy s = CustomerPriceAdjustStrategy.findByCustomerNo(customerNo);
        if (s == null) {
            s = new CustomerPriceAdjustStrategy();
            s.customerNo = customerNo;
            s.createdBy = actorId;
            s.updatedBy = actorId;
            s.persist();
        }
        return s;
    }

    /** 该客户全部 PENDING 料号标 QUEUED（即时 UI 反馈，同 B6 既定手法）并返回待异步派发清单。 */
    private List<ReviewRef> markPendingForRecompute(String customerNo) {
        List<MaterialPriceReview> pending = MaterialPriceReview.list(
            "customerNo = ?1 and status = ?2", customerNo, MaterialPriceReview.STATUS_PENDING);
        List<ReviewRef> refs = new ArrayList<>();
        for (MaterialPriceReview r : pending) {
            r.budgetStatus = MaterialPriceReview.BUDGET_QUEUED;
            r.persist();
            refs.add(new ReviewRef(r.versionId, r.materialNo));
        }
        return refs;
    }

    /** 🔒 async 派发必须放在写库事务之外（同 B3/B5 既定模式），此方法本身非 @Transactional。 */
    private void dispatchRecompute(String customerNo, List<ReviewRef> refs) {
        if (refs.isEmpty()) return;
        CustomerPriceAdjustStrategy s = CustomerPriceAdjustStrategy.findByCustomerNo(customerNo);
        BigDecimal threshold = s != null ? s.costDiffThreshold : BigDecimal.ZERO;
        for (ReviewRef ref : refs) {
            managedExecutor.runAsync(() -> budgetService.processMaterial(ref.versionId(), customerNo, threshold, ref.materialNo()));
        }
    }

    private void writeAuditLog(CustomerPriceAdjustStrategy s, String changeType, String summary,
            String beforeJson, String afterJson, UUID actorId) {
        CustomerPriceAdjustStrategyLog log = new CustomerPriceAdjustStrategyLog();
        log.strategyId = s.id;
        log.customerNo = s.customerNo;
        log.changeType = changeType;
        log.summary = summary;
        log.beforeSnapshot = beforeJson;
        log.afterSnapshot = afterJson;
        log.stampActor(actorId); // 🔒 changedBy + changedByName 一起落（#54：另一个写点曾只写一半）
        log.persist();
    }

    /** 委托到唯一实现，避免同一口径在包内出现第二份（见 CustomerPriceAdjustStrategyLog#resolveUserName）。 */
    private String resolveUserName(UUID id) {
        return CustomerPriceAdjustStrategyLog.resolveUserName(id);
    }

    private String serializeStrategy(CustomerPriceAdjustStrategy s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.enabled);
        m.put("cycleType", s.cycleType);
        m.put("cycleWeekday", s.cycleWeekday);
        m.put("cycleDayOfMonth", s.cycleDayOfMonth);
        m.put("cycleNthWeek", s.cycleNthWeek);
        m.put("executeTime", s.executeTime != null ? s.executeTime.format(HHMM) : null);
        m.put("materialScopeMode", s.materialScopeMode);
        m.put("costDiffThreshold", s.costDiffThreshold);
        return writeJson(m);
    }

    private String writeJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            LOG.warnf("[price-adjust-strategy] JSON 序列化失败: %s", e.getMessage());
            return null;
        }
    }

    private JsonNode parseJson(String json) {
        if (json == null) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalTime parseExecuteTime(String s) {
        if (s == null || s.isBlank()) return LocalTime.of(9, 0);
        try {
            return LocalTime.parse(s, HHMM);
        } catch (Exception e) {
            throw new BusinessException(400, "executeTime 格式非法，应为 HH:mm: " + s);
        }
    }

    private boolean bigDecimalEquals(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return a == b;
        return a.compareTo(b) == 0;
    }

    private void validateCustomerNo(String customerNo) {
        if (customerNo == null || customerNo.isBlank()) throw new BusinessException(400, "customerNo 不能为空");
    }

    private void validateCycleType(String v) {
        if (v == null || !VALID_CYCLE_TYPES.contains(v)) throw new BusinessException(400, "cycleType 非法: " + v);
    }

    private void validateScopeMode(String v) {
        if (v != null && !VALID_SCOPE_MODES.contains(v)) throw new BusinessException(400, "materialScopeMode 非法: " + v);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
