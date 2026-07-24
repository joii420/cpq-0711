package com.cpq.elementprice.pricetable;

import com.cpq.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 元素价格写服务（update-0724 · B4：新建 / 修改 / 删除，契约见 api.md §1~§3）。
 *
 * <p>走 native SQL，与 {@link PriceTableService} 同包同风格（{@code element_daily_price} 无 Panache 实体）。
 * <p>🔒 价格写入与变更历史写入在同一 {@code @Transactional} 方法内完成（需求 §4.3 规则 5，
 * task-0722 §11.22 已在策略侧踩过"只落其一"的坑）。
 * <p>🔒 新建撞键返回 {@code 409}，禁用 {@code INSERT ... ON CONFLICT}（那是导入侧覆盖语义，见
 * {@link com.cpq.elementprice.priceimport.PriceImportRowWriter}）。查重与 INSERT 之间的理论竞态
 * 靠捕获唯一键冲突（SQLState {@code 23505}）兜底转 409，不让 500 冒出去。
 */
@ApplicationScoped
public class PriceMaintenanceService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    @Inject
    PriceTableService priceTableService;

    // ══════════════════════════════ B4.1 新建 ══════════════════════════════

    @Transactional
    public ElementPriceRowDTO create(CreatePriceRequest req, UUID userId) {
        if (req == null) throw new BusinessException(400, "请求体不能为空");
        String elementCode = requireNonBlank(req.elementCode, "elementCode");
        if (req.sourceId == null) throw new BusinessException(400, "sourceId 不能为空");
        if (req.priceDate == null) throw new BusinessException(400, "priceDate 不能为空");
        BigDecimal price = requirePositive(req.price);
        String currency = requireNonBlank(req.currency, "currency");
        String priceUnit = requireNonBlank(req.priceUnit, "priceUnit");

        requireActiveElement(elementCode);
        requireActiveSource(req.sourceId);

        // sourceId 在 create() 里已校验非空（U2），键比对直接 source_id = :s 即可，
        // 等价于 uq_element_daily 的 COALESCE(source_id::text,'') 表达式（两侧均非 NULL）。
        // ⚠️ 不写 COALESCE(:s::text,'')：Hibernate 原生查询对"具名参数紧跟 :: 类型转换"解析有已知坑，
        // 会把 "s::text" 整体当成参数名（"No parameter named ':s' in query ..."）。
        @SuppressWarnings("unchecked")
        List<Object> dup = em.createNativeQuery(
                "SELECT id FROM element_daily_price " +
                "WHERE element_name = :e AND source_id = :s AND price_date = :d")
                .setParameter("e", elementCode)
                .setParameter("s", req.sourceId)
                .setParameter("d", req.priceDate)
                .getResultList();
        if (!dup.isEmpty()) {
            throw new BusinessException(409, "该元素在该源该日期已存在价格，请改用编辑");
        }

        UUID id = UUID.randomUUID();
        try {
            em.createNativeQuery(
                    "INSERT INTO element_daily_price " +
                    "  (id, element_name, source_id, price_date, raw_price, currency, price_unit, " +
                    "   fetch_status, manually_filled_by, created_at, updated_at, created_by, updated_by) " +
                    "VALUES " +
                    "  (:id, :e, :s, :d, :p, :cur, :unit, 'MANUAL', :uid, NOW(), NOW(), :uid, :uid)")
                    .setParameter("id", id)
                    .setParameter("e", elementCode)
                    .setParameter("s", req.sourceId)
                    .setParameter("d", req.priceDate)
                    .setParameter("p", price)
                    .setParameter("cur", currency)
                    .setParameter("unit", priceUnit)
                    .setParameter("uid", userId)
                    .executeUpdate();
        } catch (RuntimeException e) {
            if (isUniqueViolation(e)) {
                throw new BusinessException(409, "该元素在该源该日期已存在价格，请改用编辑");
            }
            throw e;
        }

        writeLog(id, elementCode, req.sourceId, req.priceDate, "CREATE",
                buildSnapshot(price, currency, priceUnit, "MANUAL"), userId);

        return priceTableService.findById(id);
    }

    // ══════════════════════════════ B4.2 修改 ══════════════════════════════

    @Transactional
    public ElementPriceRowDTO update(UUID id, UpdatePriceRequest req, UUID userId) {
        if (id == null) throw new BusinessException(400, "id 不能为空");
        if (req == null) throw new BusinessException(400, "请求体不能为空");
        BigDecimal price = requirePositive(req.price);
        String currency = requireNonBlank(req.currency, "currency");
        String priceUnit = requireNonBlank(req.priceUnit, "priceUnit");

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT element_name, source_id, price_date FROM element_daily_price WHERE id = :id")
                .setParameter("id", id)
                .getResultList();
        if (rows.isEmpty()) throw new BusinessException(404, "价格记录不存在: " + id);
        Object[] r = rows.get(0);
        String elementName = (String) r[0];
        UUID sourceId = r[1] != null ? (UUID) r[1] : null;
        LocalDate priceDate = toLocalDate(r[2]);

        em.createNativeQuery(
                "UPDATE element_daily_price " +
                "SET raw_price = :p, currency = :c, price_unit = :u, " +
                "    fetch_status = 'MANUAL', updated_by = :uid, updated_at = NOW() " +   // ← 无条件翻转，含原本 IMPORT 的行（U3）
                "WHERE id = :id")
                .setParameter("p", price)
                .setParameter("c", currency)
                .setParameter("u", priceUnit)
                .setParameter("uid", userId)
                .setParameter("id", id)
                .executeUpdate();

        writeLog(id, elementName, sourceId, priceDate, "UPDATE",
                buildSnapshot(price, currency, priceUnit, "MANUAL"), userId);

        return priceTableService.findById(id);
    }

    // ══════════════════════════════ B4.3 删除 ══════════════════════════════

    @Transactional
    public void delete(UUID id, UUID userId) {
        if (id == null) throw new BusinessException(400, "id 不能为空");

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT element_name, source_id, price_date, raw_price, currency, price_unit, fetch_status " +
                "FROM element_daily_price WHERE id = :id")
                .setParameter("id", id)
                .getResultList();
        if (rows.isEmpty()) throw new BusinessException(404, "价格记录不存在: " + id);
        Object[] r = rows.get(0);
        String elementName = (String) r[0];
        UUID sourceId = r[1] != null ? (UUID) r[1] : null;
        LocalDate priceDate = toLocalDate(r[2]);
        BigDecimal price = r[3] != null ? new BigDecimal(r[3].toString()) : null;
        String currency = (String) r[4];
        String priceUnit = (String) r[5];
        String fetchStatus = (String) r[6];

        // 顺序关键：先读（已在上方完成）→ 删 → 写日志（用删除前的值），三步同事务
        em.createNativeQuery("DELETE FROM element_daily_price WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();

        writeLog(id, elementName, sourceId, priceDate, "DELETE",
                buildSnapshot(price, currency, priceUnit, fetchStatus), userId);
    }

    // ══════════════════════════════ helpers: 校验 ══════════════════════════════

    private String requireNonBlank(String s, String field) {
        if (s == null || s.isBlank()) throw new BusinessException(400, field + " 不能为空");
        return s.trim();
    }

    private BigDecimal requirePositive(BigDecimal price) {
        if (price == null) throw new BusinessException(400, "price 不能为空");
        if (price.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(400, "price 必须大于 0");
        return price;
    }

    private void requireActiveElement(String elementCode) {
        @SuppressWarnings("unchecked")
        List<String> rows = em.createNativeQuery("SELECT status FROM element WHERE element_code = :c")
                .setParameter("c", elementCode)
                .getResultList();
        if (rows.isEmpty() || !"ACTIVE".equals(rows.get(0))) {
            throw new BusinessException(400, "元素不存在或已停用: " + elementCode);
        }
    }

    private void requireActiveSource(UUID sourceId) {
        @SuppressWarnings("unchecked")
        List<String> rows = em.createNativeQuery("SELECT status FROM element_price_source WHERE id = :id")
                .setParameter("id", sourceId)
                .getResultList();
        if (rows.isEmpty() || !"ACTIVE".equals(rows.get(0))) {
            throw new BusinessException(400, "价格源不存在或已停用");
        }
    }

    /**
     * 判断本次写库异常是否由唯一键冲突（SQLState 23505）引起，沿 cause 链查找
     * {@link org.hibernate.exception.ConstraintViolationException}，不依赖具体驱动/ORM 包装层级
     * （比照 {@code CostingFreezeService#isActiveUniquenessViolation} 的写法）。
     */
    private boolean isUniqueViolation(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve) {
                java.sql.SQLException sqlEx = cve.getSQLException();
                if (sqlEx != null && "23505".equals(sqlEx.getSQLState())) return true;
            }
            t = t.getCause();
        }
        return false;
    }

    // ══════════════════════════════ helpers: 日志写入 ══════════════════════════════

    /**
     * 写变更历史（update-0724 · B4.4）。必须在调用方的同一 {@code @Transactional} 方法内调用，
     * 与价格写入共享同一数据库事务，避免只落其一导致留痕断链。
     */
    private void writeLog(UUID priceId, String elementName, UUID sourceId, LocalDate priceDate,
                           String action, ObjectNode snapshot, UUID userId) {
        ElementDailyPriceLog log = new ElementDailyPriceLog();
        log.priceId = priceId;
        log.elementName = elementName;
        log.sourceId = sourceId;
        log.priceDate = priceDate;
        log.action = action;
        try {
            log.snapshot = MAPPER.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new RuntimeException("序列化价格快照失败: " + e.getMessage(), e);
        }
        log.changedAt = OffsetDateTime.now();
        log.changedBy = userId;
        log.changedByName = lookupUserName(userId);
        log.persist();
    }

    private ObjectNode buildSnapshot(BigDecimal price, String currency, String priceUnit, String fetchStatus) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("price", price);
        node.put("currency", currency);
        node.put("priceUnit", priceUnit);
        node.put("fetchStatus", fetchStatus);
        return node;
    }

    private String lookupUserName(UUID userId) {
        if (userId == null) return null;
        @SuppressWarnings("unchecked")
        List<String> rows = em.createNativeQuery("SELECT full_name FROM \"user\" WHERE id = :id")
                .setParameter("id", userId)
                .getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Date sd) return sd.toLocalDate();
        if (o instanceof LocalDate ld) return ld;
        return LocalDate.parse(o.toString());
    }
}
