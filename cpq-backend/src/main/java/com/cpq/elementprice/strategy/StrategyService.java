package com.cpq.elementprice.strategy;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 价格策略服务（task-0722 · B8 CRUD + 历史写入 / B9 历史查询与差异 / B10 试算）。
 *
 * <p>🔒 {@code customerNo} 全链路 String，{@code _GLOBAL_} 原样穿透，任何一层不转 UUID（§11.11.4）。
 * <p>🔴 5 条写入路径（默认新建/修改、例外新建/修改/删除）每条都在**同一事务**内调 {@link #writeLog}。
 */
@ApplicationScoped
public class StrategyService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> METHODS = List.of("LATEST", "AVG", "MAX", "MIN");
    private static final List<String> UNITS = List.of("DAY", "WEEK", "MONTH", "YEAR");
    public static final String GLOBAL_CUSTOMER = "_GLOBAL_";

    @Inject
    EntityManager em;

    // ══════════════════════════════ B8: 读取 ══════════════════════════════

    @Transactional(Transactional.TxType.SUPPORTS)
    public StrategyBundleDTO getBundle(String customerNo) {
        String cust = requireCustomerNo(customerNo);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT s.id, s.element_code, e.element_name, s.source_id, src.source_name, " +
                "       s.method, s.window_num, s.window_unit, s.factor, s.premium, s.updated_at, u.full_name " +
                "FROM element_price_strategy s " +
                "LEFT JOIN element_price_source src ON src.id = s.source_id " +
                "LEFT JOIN element e ON e.element_code = s.element_code " +
                "LEFT JOIN \"user\" u ON u.id = s.updated_by " +
                "WHERE s.customer_no = :cust AND s.status = 'ACTIVE' " +
                "ORDER BY (s.element_code IS NULL) DESC, s.element_code ASC")
                .setParameter("cust", cust)
                .getResultList();

        StrategyBundleDTO bundle = new StrategyBundleDTO();
        bundle.customerNo = cust;
        for (Object[] r : rows) {
            StrategyDTO d = mapStrategyRow(r);
            if (d.elementCode == null) {
                bundle.default_ = d;
            } else {
                bundle.exceptions.add(d);
            }
        }
        return bundle;
    }

    private StrategyDTO mapStrategyRow(Object[] r) {
        StrategyDTO d = new StrategyDTO();
        d.id = (UUID) r[0];
        d.elementCode = (String) r[1];
        d.elementName = (String) r[2];
        d.sourceId = (UUID) r[3];
        d.sourceName = (String) r[4];
        d.method = (String) r[5];
        d.windowNum = r[6] != null ? ((Number) r[6]).intValue() : null;
        d.windowUnit = (String) r[7];
        d.factor = r[8] != null ? new BigDecimal(r[8].toString()) : null;
        d.premium = r[9] != null ? new BigDecimal(r[9].toString()) : null;
        d.updatedAt = toOffsetDateTime(r[10]);
        d.updatedByName = (String) r[11];
        return d;
    }

    // ══════════════════════════════ B8: 写入路径 1/2 — 默认策略 ══════════════════════════════

    @Transactional
    public StrategyDTO saveDefault(StrategyUpsertRequest req, UUID userId) {
        Validated v = validateForWrite(req, false);
        ElementPriceStrategy existing = ElementPriceStrategy
                .find("customerNo = ?1 and elementCode is null", v.customerNo)
                .firstResult();
        String action;
        ElementPriceStrategy s;
        if (existing != null) {
            s = existing;
            action = "UPDATE";
        } else {
            s = new ElementPriceStrategy();
            s.customerNo = v.customerNo;
            s.elementCode = null;
            s.status = "ACTIVE";
            s.createdAt = OffsetDateTime.now();
            s.createdBy = userId;
            action = "CREATE";
        }
        applyValidated(s, v);
        s.updatedAt = OffsetDateTime.now();
        s.updatedBy = userId;
        s.persist();
        em.flush();

        writeLog(action, s, v.sourceName, userId);
        return toDTO(s, v.sourceName, null, lookupUserName(userId));
    }

    // ══════════════════════════════ B8: 写入路径 3 — 例外新建 ══════════════════════════════

    @Transactional
    public StrategyDTO createException(StrategyUpsertRequest req, UUID userId) {
        Validated v = validateForWrite(req, true);
        boolean conflict = ElementPriceStrategy
                .find("customerNo = ?1 and elementCode = ?2", v.customerNo, v.elementCode)
                .firstResultOptional().isPresent();
        if (conflict) {
            throw new BusinessException(409, "该客户的元素「" + v.elementCode + "」已存在例外配置");
        }
        ElementPriceStrategy s = new ElementPriceStrategy();
        s.customerNo = v.customerNo;
        s.elementCode = v.elementCode;
        s.status = "ACTIVE";
        s.createdAt = OffsetDateTime.now();
        s.createdBy = userId;
        applyValidated(s, v);
        s.updatedAt = OffsetDateTime.now();
        s.updatedBy = userId;
        s.persist();
        em.flush();

        writeLog("CREATE", s, v.sourceName, userId);
        return toDTO(s, v.sourceName, lookupElementName(v.elementCode), lookupUserName(userId));
    }

    // ══════════════════════════════ B8: 写入路径 4 — 例外修改 ══════════════════════════════

    @Transactional
    public StrategyDTO updateException(UUID id, StrategyUpsertRequest req, UUID userId) {
        ElementPriceStrategy s = ElementPriceStrategy.findById(id);
        if (s == null || s.elementCode == null) throw new NotFoundException("元素例外配置不存在: " + id);

        Validated v = validateForWrite(req, true);
        if (!v.elementCode.equals(s.elementCode) || !v.customerNo.equals(s.customerNo)) {
            boolean conflict = ElementPriceStrategy
                    .find("customerNo = ?1 and elementCode = ?2 and id <> ?3", v.customerNo, v.elementCode, id)
                    .firstResultOptional().isPresent();
            if (conflict) {
                throw new BusinessException(409, "该客户的元素「" + v.elementCode + "」已存在例外配置");
            }
        }
        s.customerNo = v.customerNo;
        s.elementCode = v.elementCode;
        applyValidated(s, v);
        s.updatedAt = OffsetDateTime.now();
        s.updatedBy = userId;
        s.persist();
        em.flush();

        writeLog("UPDATE", s, v.sourceName, userId);
        return toDTO(s, v.sourceName, lookupElementName(v.elementCode), lookupUserName(userId));
    }

    // ══════════════════════════════ B8: 写入路径 5 — 例外删除 ══════════════════════════════

    @Transactional
    public void deleteException(UUID id, UUID userId) {
        ElementPriceStrategy s = ElementPriceStrategy.findById(id);
        if (s == null || s.elementCode == null) throw new NotFoundException("元素例外配置不存在: " + id);

        String sourceName = lookupSourceName(s.sourceId);
        // DELETE 存删除前快照，必须在 delete() 之前写 log（同事务）
        writeLog("DELETE", s, sourceName, userId);
        s.delete();
    }

    // ══════════════════════════════ B8 helpers: 校验 + 应用 + log ══════════════════════════════

    private static class Validated {
        String customerNo;
        String elementCode;
        UUID sourceId;
        String sourceName;
        String method;
        Integer windowNum;
        String windowUnit;
        BigDecimal factor;
        BigDecimal premium;
    }

    private Validated validateForWrite(StrategyUpsertRequest req, boolean requireElementCode) {
        if (req == null) throw new BusinessException(400, "请求体不能为空");
        Validated v = new Validated();
        v.customerNo = requireCustomerNo(req.customerNo);

        if (requireElementCode) {
            if (req.elementCode == null || req.elementCode.isBlank()) {
                throw new BusinessException(400, "elementCode 不能为空");
            }
            v.elementCode = req.elementCode.trim();
        }

        if (req.sourceId == null) throw new BusinessException(400, "sourceId 不能为空");
        ElementPriceSourceLite src = lookupSource(req.sourceId);
        if (src == null) throw new BusinessException(400, "价格源不存在: " + req.sourceId);
        if (!"ACTIVE".equals(src.status)) throw new BusinessException(400, "价格源「" + src.name + "」已停用，不可用于策略");
        v.sourceId = req.sourceId;
        v.sourceName = src.name;

        if (req.method == null || !METHODS.contains(req.method.trim().toUpperCase())) {
            throw new BusinessException(400, "method 仅支持 LATEST/AVG/MAX/MIN");
        }
        v.method = req.method.trim().toUpperCase();

        if ("LATEST".equals(v.method)) {
            if (req.windowNum != null || (req.windowUnit != null && !req.windowUnit.isBlank())) {
                throw new BusinessException(400, "取值方式为「最新一条价」时不可填窗口");
            }
            v.windowNum = null;
            v.windowUnit = null;
        } else {
            if (req.windowNum == null || req.windowNum <= 0) {
                throw new BusinessException(400, "窗口数值必须大于 0");
            }
            if (req.windowUnit == null || !UNITS.contains(req.windowUnit.trim().toUpperCase())) {
                throw new BusinessException(400, "窗口单位仅支持 DAY/WEEK/MONTH/YEAR");
            }
            v.windowNum = req.windowNum;
            v.windowUnit = req.windowUnit.trim().toUpperCase();
        }

        v.factor = req.factor != null ? req.factor : BigDecimal.ONE;
        if (v.factor.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(400, "系数必须大于 0");
        v.premium = req.premium != null ? req.premium : BigDecimal.ZERO;
        return v;
    }

    private void applyValidated(ElementPriceStrategy s, Validated v) {
        s.sourceId = v.sourceId;
        s.method = v.method;
        s.windowNum = v.windowNum;
        s.windowUnit = v.windowUnit;
        s.factor = v.factor;
        s.premium = v.premium;
    }

    private String requireCustomerNo(String customerNo) {
        if (customerNo == null || customerNo.isBlank()) {
            throw new BusinessException(400, "customerNo 不能为空");
        }
        String cust = customerNo.trim();
        if (!GLOBAL_CUSTOMER.equals(cust)) {
            @SuppressWarnings("unchecked")
            List<Object> exists = em.createNativeQuery("SELECT 1 FROM customer WHERE code = :code")
                    .setParameter("code", cust).getResultList();
            if (exists.isEmpty()) {
                throw new BusinessException(400, "客户不存在: " + cust);
            }
        }
        return cust;
    }

    /**
     * 写变更历史（task-0722 §11.14F）。必须在调用方的同一 {@code @Transactional} 方法内调用，
     * 与策略写入共享同一数据库事务，避免只落其一导致留痕断链。
     */
    private void writeLog(String action, ElementPriceStrategy s, String sourceName, UUID userId) {
        ElementPriceStrategyLog log = new ElementPriceStrategyLog();
        log.strategyId = s.id;
        log.customerNo = s.customerNo;
        log.elementCode = s.elementCode;
        log.action = action;
        log.snapshot = buildSnapshotJson(s, sourceName);
        log.changedAt = OffsetDateTime.now();
        log.changedBy = userId;
        log.changedByName = lookupUserName(userId);
        log.persist();
    }

    private String buildSnapshotJson(ElementPriceStrategy s, String sourceName) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("sourceId", s.sourceId != null ? s.sourceId.toString() : null);
        node.put("sourceName", sourceName);
        node.put("method", s.method);
        if (s.windowNum != null) node.put("windowNum", s.windowNum); else node.putNull("windowNum");
        node.put("windowUnit", s.windowUnit);
        node.put("factor", s.factor);
        node.put("premium", s.premium);
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("序列化策略快照失败: " + e.getMessage(), e);
        }
    }

    private StrategyDTO toDTO(ElementPriceStrategy s, String sourceName, String elementName, String updatedByName) {
        StrategyDTO d = new StrategyDTO();
        d.id = s.id;
        d.elementCode = s.elementCode;
        d.elementName = elementName;
        d.sourceId = s.sourceId;
        d.sourceName = sourceName;
        d.method = s.method;
        d.windowNum = s.windowNum;
        d.windowUnit = s.windowUnit;
        d.factor = s.factor;
        d.premium = s.premium;
        d.updatedAt = s.updatedAt;
        d.updatedByName = updatedByName;
        return d;
    }

    // ══════════════════════════════ B10: 策略试算 ══════════════════════════════

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<SimulateRowDTO> simulate(SimulateRequest req) {
        if (req == null) throw new BusinessException(400, "请求体不能为空");
        String cust = requireCustomerNoForSimulate(req.customerNo);
        LocalDate baseDate = req.baseDate != null ? req.baseDate : LocalDate.now();

        Map<UUID, String> sourceNames = loadAllSourceNames();

        EffStrategy defaultEff = null;
        Map<String, EffStrategy> exceptionMap = new HashMap<>();

        if (req.draft != null) {
            if (req.draft.default_ != null) {
                defaultEff = fromDraft(req.draft.default_, null, "DEFAULT", sourceNames);
            }
            if (req.draft.exceptions != null) {
                for (StrategyUpsertRequest x : req.draft.exceptions) {
                    if (x.elementCode == null || x.elementCode.isBlank()) continue;
                    String code = x.elementCode.trim();
                    exceptionMap.put(code, fromDraft(x, code, "EXCEPTION", sourceNames));
                }
            }
        } else {
            ElementPriceStrategy defRow = ElementPriceStrategy
                    .find("customerNo = ?1 and elementCode is null and status = 'ACTIVE'", cust)
                    .firstResult();
            if (defRow != null) defaultEff = fromEntity(defRow, "DEFAULT", sourceNames);
            List<ElementPriceStrategy> excRows = ElementPriceStrategy
                    .find("customerNo = ?1 and elementCode is not null and status = 'ACTIVE'", cust)
                    .list();
            for (ElementPriceStrategy x : excRows) {
                exceptionMap.put(x.elementCode, fromEntity(x, "EXCEPTION", sourceNames));
            }
        }

        @SuppressWarnings("unchecked")
        List<Object[]> elements = em.createNativeQuery(
                "SELECT element_code, element_name FROM element WHERE status = 'ACTIVE' ORDER BY element_code")
                .getResultList();

        List<SimulateRowDTO> out = new ArrayList<>();
        for (Object[] e : elements) {
            String code = (String) e[0];
            String name = (String) e[1];
            EffStrategy eff = exceptionMap.containsKey(code) ? exceptionMap.get(code) : defaultEff;
            if (eff == null) continue; // 未配到策略的元素不返回

            SimulateRowDTO row = new SimulateRowDTO();
            row.elementCode = code;
            row.elementName = name;
            row.hitRule = eff.hitRule;
            row.sourceName = eff.sourceName;
            row.method = eff.method;
            row.factor = eff.factor;
            row.premium = eff.premium;

            ComputeResult cr = computeRawValue(code, eff, baseDate);
            row.rawValue = cr.rawValue;
            row.sampleDays = cr.sampleDays;
            row.hasPrice = cr.rawValue != null;
            row.finalPrice = cr.rawValue != null
                    ? cr.rawValue.multiply(eff.factor).add(eff.premium).setScale(4, RoundingMode.HALF_UP)
                    : null;
            out.add(row);
        }
        return out;
    }

    private String requireCustomerNoForSimulate(String customerNo) {
        // 试算只读，不做客户存在性校验（未配置/不存在的客户直接返回空更符合"预览"语义）
        if (customerNo == null || customerNo.isBlank()) throw new BusinessException(400, "customerNo 不能为空");
        return customerNo.trim();
    }

    private static class EffStrategy {
        String hitRule;
        UUID sourceId;
        String sourceName;
        String method;
        Integer windowNum;
        String windowUnit;
        BigDecimal factor;
        BigDecimal premium;
    }

    private static class ComputeResult {
        BigDecimal rawValue;
        int sampleDays;
        ComputeResult(BigDecimal v, int d) { rawValue = v; sampleDays = d; }
    }

    private EffStrategy fromEntity(ElementPriceStrategy s, String hitRule, Map<UUID, String> sourceNames) {
        EffStrategy eff = new EffStrategy();
        eff.hitRule = hitRule;
        eff.sourceId = s.sourceId;
        eff.sourceName = sourceNames.get(s.sourceId);
        eff.method = s.method;
        eff.windowNum = s.windowNum;
        eff.windowUnit = s.windowUnit;
        eff.factor = s.factor != null ? s.factor : BigDecimal.ONE;
        eff.premium = s.premium != null ? s.premium : BigDecimal.ZERO;
        return eff;
    }

    private EffStrategy fromDraft(StrategyUpsertRequest req, String elementCode, String hitRule,
                                   Map<UUID, String> sourceNames) {
        if (req.method == null || !METHODS.contains(req.method.trim().toUpperCase())) {
            throw new BusinessException(400, "试算草稿 method 仅支持 LATEST/AVG/MAX/MIN"
                    + (elementCode != null ? "（元素 " + elementCode + "）" : "（客户级默认）"));
        }
        EffStrategy eff = new EffStrategy();
        eff.hitRule = hitRule;
        eff.sourceId = req.sourceId;
        eff.sourceName = req.sourceId != null ? sourceNames.get(req.sourceId) : null;
        eff.method = req.method.trim().toUpperCase();
        if (!"LATEST".equals(eff.method)) {
            eff.windowNum = req.windowNum;
            eff.windowUnit = req.windowUnit != null ? req.windowUnit.trim().toUpperCase() : null;
        }
        eff.factor = req.factor != null ? req.factor : BigDecimal.ONE;
        eff.premium = req.premium != null ? req.premium : BigDecimal.ZERO;
        return eff;
    }

    private ComputeResult computeRawValue(String elementCode, EffStrategy eff, LocalDate baseDate) {
        if (eff.sourceId == null) return new ComputeResult(null, 0);
        if ("LATEST".equals(eff.method)) {
            @SuppressWarnings("unchecked")
            List<Object> rows = em.createNativeQuery(
                    "SELECT raw_price FROM element_daily_price " +
                    "WHERE element_name = :en AND source_id = :sid AND raw_price IS NOT NULL AND price_date <= :bd " +
                    "ORDER BY price_date DESC LIMIT 1")
                    .setParameter("en", elementCode).setParameter("sid", eff.sourceId).setParameter("bd", baseDate)
                    .getResultList();
            if (rows.isEmpty() || rows.get(0) == null) return new ComputeResult(null, 0);
            return new ComputeResult(new BigDecimal(rows.get(0).toString()), 1);
        }
        LocalDate winFrom = computeWinFrom(baseDate, eff.windowNum, eff.windowUnit);
        String aggFn = "AVG".equals(eff.method) ? "AVG" : ("MAX".equals(eff.method) ? "MAX" : "MIN");
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT " + aggFn + "(raw_price), COUNT(raw_price) FROM element_daily_price " +
                "WHERE element_name = :en AND source_id = :sid AND raw_price IS NOT NULL " +
                "  AND price_date BETWEEN :wf AND :bd")
                .setParameter("en", elementCode).setParameter("sid", eff.sourceId)
                .setParameter("wf", winFrom).setParameter("bd", baseDate)
                .getResultList();
        Object[] r = rows.get(0);
        BigDecimal val = r[0] != null ? new BigDecimal(r[0].toString()) : null;
        int sampleDays = r[1] != null ? ((Number) r[1]).intValue() : 0;
        return new ComputeResult(val, sampleDays);
    }

    private LocalDate computeWinFrom(LocalDate baseDate, Integer windowNum, String windowUnit) {
        if (windowNum == null || windowUnit == null) return baseDate;
        return switch (windowUnit) {
            case "DAY" -> baseDate.minusDays(windowNum);
            case "WEEK" -> baseDate.minusWeeks(windowNum);
            case "MONTH" -> baseDate.minusMonths(windowNum);
            case "YEAR" -> baseDate.minusYears(windowNum);
            default -> baseDate;
        };
    }

    // ══════════════════════════════ B9: 历史查询 + 差异计算 ══════════════════════════════

    @Transactional(Transactional.TxType.SUPPORTS)
    public PageResult<StrategyHistoryDTO> history(String customerNo, String elementCodeFilter,
                                                    OffsetDateTime from, OffsetDateTime to, String changedBy,
                                                    int page, int size) {
        String cust = requireCustomerNoForSimulate(customerNo);
        if (page < 0) page = 0;
        if (size <= 0 || size > 200) size = 20;

        // 全量取该客户所有 log（按 group 顺序求 diff 需要完整时间线，不能先分页）
        List<ElementPriceStrategyLog> all = ElementPriceStrategyLog
                .find("customerNo = ?1 order by elementCode asc nulls first, changedAt asc", cust)
                .list();

        Map<String, String> elementNames = loadElementNames(all);

        LinkedHashMap<String, List<ElementPriceStrategyLog>> groups = new LinkedHashMap<>();
        for (ElementPriceStrategyLog l : all) {
            String gk = l.elementCode == null ? "" : l.elementCode;
            groups.computeIfAbsent(gk, k -> new ArrayList<>()).add(l);
        }

        List<StrategyHistoryDTO> diffed = new ArrayList<>();
        for (List<ElementPriceStrategyLog> group : groups.values()) {
            JsonNode prevSnap = null;
            for (ElementPriceStrategyLog l : group) {
                JsonNode snap = parseSnapshot(l.snapshot);
                StrategyHistoryDTO dto = new StrategyHistoryDTO();
                dto.id = l.id;
                dto.changedAt = l.changedAt;
                dto.changedByName = l.changedByName;
                dto.elementCode = l.elementCode;
                dto.action = l.action;
                dto.snapshot = snap;
                dto.targetLabel = buildTargetLabel(l.elementCode, elementNames);
                dto.changes = ("UPDATE".equals(l.action) && prevSnap != null)
                        ? diffSnapshots(prevSnap, snap) : new ArrayList<>();
                diffed.add(dto);
                prevSnap = "DELETE".equals(l.action) ? null : snap;
            }
        }

        List<StrategyHistoryDTO> filtered = diffed.stream()
                .filter(d -> matchElementFilter(d, elementCodeFilter))
                .filter(d -> from == null || !d.changedAt.isBefore(from))
                .filter(d -> to == null || !d.changedAt.isAfter(to))
                .filter(d -> changedBy == null || changedBy.isBlank()
                        || (d.changedByName != null && d.changedByName.contains(changedBy.trim())))
                .sorted((a, b) -> b.changedAt.compareTo(a.changedAt))
                .collect(Collectors.toList());

        long total = filtered.size();
        int fromIdx = Math.min(page * size, filtered.size());
        int toIdx = Math.min(fromIdx + size, filtered.size());
        return new PageResult<>(filtered.subList(fromIdx, toIdx), page, size, total);
    }

    private boolean matchElementFilter(StrategyHistoryDTO d, String filter) {
        if (filter == null || filter.isBlank()) return true;
        if ("__DEFAULT__".equals(filter)) return d.elementCode == null;
        return filter.equals(d.elementCode);
    }

    private String buildTargetLabel(String elementCode, Map<String, String> elementNames) {
        if (elementCode == null) return "客户级默认策略";
        String name = elementNames.get(elementCode);
        return "元素例外 · " + elementCode + (name != null ? " " + name : "");
    }

    private Map<String, String> loadElementNames(List<ElementPriceStrategyLog> logs) {
        List<String> codes = logs.stream().map(l -> l.elementCode).filter(Objects::nonNull).distinct().toList();
        if (codes.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT element_code, element_name FROM element WHERE element_code IN (:codes)")
                .setParameter("codes", codes)
                .getResultList();
        Map<String, String> out = new HashMap<>();
        for (Object[] r : rows) out.put((String) r[0], (String) r[1]);
        return out;
    }

    private JsonNode parseSnapshot(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private List<StrategyChangeDTO> diffSnapshots(JsonNode prev, JsonNode curr) {
        List<StrategyChangeDTO> changes = new ArrayList<>();
        String prevSourceName = textOrNull(prev, "sourceName");
        String currSourceName = textOrNull(curr, "sourceName");
        if (!Objects.equals(prevSourceName, currSourceName)) {
            changes.add(new StrategyChangeDTO("sourceName", "价格源", prevSourceName, currSourceName));
        }
        String prevMethod = textOrNull(prev, "method");
        String currMethod = textOrNull(curr, "method");
        if (!Objects.equals(prevMethod, currMethod)) {
            changes.add(new StrategyChangeDTO("method", "取值方式", methodLabel(prevMethod), methodLabel(currMethod)));
        }
        String prevWindow = windowLabel(prev);
        String currWindow = windowLabel(curr);
        if (!Objects.equals(prevWindow, currWindow)) {
            changes.add(new StrategyChangeDTO("window", "窗口", prevWindow, currWindow));
        }
        String prevFactor = decimalOrNull(prev, "factor");
        String currFactor = decimalOrNull(curr, "factor");
        if (!Objects.equals(prevFactor, currFactor)) {
            changes.add(new StrategyChangeDTO("factor", "系数", prevFactor, currFactor));
        }
        String prevPremium = decimalOrNull(prev, "premium");
        String currPremium = decimalOrNull(curr, "premium");
        if (!Objects.equals(prevPremium, currPremium)) {
            changes.add(new StrategyChangeDTO("premium", "加价", prevPremium, currPremium));
        }
        return changes;
    }

    private String methodLabel(String m) {
        if (m == null) return null;
        return switch (m) {
            case "LATEST" -> "最新一条价";
            case "AVG" -> "窗口内平均值";
            case "MAX" -> "窗口内最高值";
            case "MIN" -> "窗口内最低值";
            default -> m;
        };
    }

    private String windowLabel(JsonNode n) {
        JsonNode wn = n.get("windowNum");
        JsonNode wu = n.get("windowUnit");
        if (wn == null || wn.isNull() || wu == null || wu.isNull()) return "—";
        String unitCn = switch (wu.asText()) {
            case "DAY" -> "天";
            case "WEEK" -> "周";
            case "MONTH" -> "月";
            case "YEAR" -> "年";
            default -> wu.asText();
        };
        return wn.asInt() + unitCn;
    }

    private String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private String decimalOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        try {
            return new BigDecimal(v.asText()).setScale(2, RoundingMode.HALF_UP).toPlainString();
        } catch (Exception e) {
            return v.asText();
        }
    }

    // ══════════════════════════════ 共用查询 helpers ══════════════════════════════

    private static class ElementPriceSourceLite {
        String name;
        String status;
    }

    private ElementPriceSourceLite lookupSource(UUID id) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT source_name, status FROM element_price_source WHERE id = :id")
                .setParameter("id", id).getResultList();
        if (rows.isEmpty()) return null;
        ElementPriceSourceLite s = new ElementPriceSourceLite();
        s.name = (String) rows.get(0)[0];
        s.status = (String) rows.get(0)[1];
        return s;
    }

    private String lookupSourceName(UUID id) {
        if (id == null) return null;
        @SuppressWarnings("unchecked")
        List<String> rows = em.createNativeQuery("SELECT source_name FROM element_price_source WHERE id = :id")
                .setParameter("id", id).getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<UUID, String> loadAllSourceNames() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("SELECT id, source_name FROM element_price_source").getResultList();
        Map<UUID, String> m = new HashMap<>();
        for (Object[] r : rows) m.put((UUID) r[0], (String) r[1]);
        return m;
    }

    private String lookupElementName(String elementCode) {
        if (elementCode == null) return null;
        @SuppressWarnings("unchecked")
        List<String> rows = em.createNativeQuery("SELECT element_name FROM element WHERE element_code = :c")
                .setParameter("c", elementCode).getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String lookupUserName(UUID userId) {
        if (userId == null) return null;
        @SuppressWarnings("unchecked")
        List<String> rows = em.createNativeQuery("SELECT full_name FROM \"user\" WHERE id = :id")
                .setParameter("id", userId).getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private OffsetDateTime toOffsetDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof OffsetDateTime odt) return odt;
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        if (o instanceof java.time.Instant i) return i.atOffset(java.time.ZoneOffset.UTC);
        return null;
    }
}
