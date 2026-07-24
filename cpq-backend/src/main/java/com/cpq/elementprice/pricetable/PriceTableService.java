package com.cpq.elementprice.pricetable;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 价格表查询服务（task-0722 · B6，契约见 api.md §3）+ B7.1 各源最新价 + update-0724 · B5 变更历史。
 */
@ApplicationScoped
public class PriceTableService {

    private static final long MAX_MATRIX_SPAN_DAYS = 90;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    // ──────────────────────────────── 3.1 明细 ────────────────────────────────

    @Transactional(Transactional.TxType.SUPPORTS)
    public PageResult<ElementPriceRowDTO> listDetail(UUID sourceId, LocalDate from, LocalDate to,
                                                       String keyword, int page, int size) {
        LocalDate effFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate effTo = to != null ? to : LocalDate.now();
        if (page < 0) page = 0;
        if (size <= 0 || size > 200) size = 20;
        boolean hasKw = keyword != null && !keyword.isBlank();

        // U-裁决(技术总监 2026-07-23 补)：排除 v1 存量脏数据（source_id IS NULL，
        // 当年 upsertManual 恒写 NULL 源，需求 §6「不展示于新入口」）。
        // 新建/编辑要求 sourceId 必填（U2），故新数据永不会命中该条件；仅过滤历史脏行。
        StringBuilder where = new StringBuilder(" WHERE edp.price_date BETWEEN :from AND :to AND edp.source_id IS NOT NULL ");
        if (sourceId != null) where.append(" AND edp.source_id = :sourceId ");
        if (hasKw) where.append(" AND (edp.element_name ILIKE :kw OR e.element_name ILIKE :kw) ");

        String fromClause =
                " FROM element_daily_price edp " +
                " LEFT JOIN element e ON e.element_code = edp.element_name " +
                " LEFT JOIN element_price_source s ON s.id = edp.source_id " +
                " LEFT JOIN \"user\" u ON u.id = edp.updated_by ";

        Query countQ = em.createNativeQuery("SELECT COUNT(*)" + fromClause + where);
        bindCommon(countQ, sourceId, effFrom, effTo, hasKw, keyword);
        long total = ((Number) countQ.getSingleResult()).longValue();

        Query dataQ = em.createNativeQuery(
                "SELECT edp.element_name, e.element_name, edp.price_date, edp.source_id, s.source_name, s.status, " +
                "       edp.raw_price, edp.currency, edp.price_unit, u.full_name, edp.updated_at, " +
                "       edp.id, edp.fetch_status" +          // ← update-0724 · B3：新增两列追加到末尾，前面列顺序一格不动
                fromClause + where +
                " ORDER BY edp.price_date DESC, edp.element_name ASC" +
                " LIMIT :limit OFFSET :offset");
        bindCommon(dataQ, sourceId, effFrom, effTo, hasKw, keyword);
        dataQ.setParameter("limit", size);
        dataQ.setParameter("offset", (long) page * size);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = dataQ.getResultList();
        List<ElementPriceRowDTO> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) out.add(mapDetailRow(r));
        return new PageResult<>(out, page, size, total);
    }

    private void bindCommon(Query q, UUID sourceId, LocalDate from, LocalDate to, boolean hasKw, String keyword) {
        q.setParameter("from", from);
        q.setParameter("to", to);
        if (sourceId != null) q.setParameter("sourceId", sourceId);
        if (hasKw) q.setParameter("kw", "%" + keyword.trim() + "%");
    }

    private ElementPriceRowDTO mapDetailRow(Object[] r) {
        ElementPriceRowDTO d = new ElementPriceRowDTO();
        d.elementCode = (String) r[0];
        d.elementName = r[1] != null ? (String) r[1] : d.elementCode;
        d.priceDate = toLocalDate(r[2]);
        d.sourceId = r[3] != null ? (UUID) r[3] : null;
        d.sourceName = (String) r[4];
        d.sourceStatus = (String) r[5];
        d.price = r[6] != null ? new BigDecimal(r[6].toString()) : null;
        d.currency = (String) r[7];
        d.priceUnit = (String) r[8];
        d.operatorName = (String) r[9];
        d.updatedAt = toOffsetDateTime(r[10]);
        d.id = r[11] != null ? (UUID) r[11] : null;       // ← update-0724 · B3：末尾追加下标，不动前面
        d.fetchStatus = (String) r[12];
        return d;
    }

    // ──────────────────────────────── update-0724 · B4：按 id 取单行 ────────────────────────────────

    /** 按 id 精确取单行完整 DTO（供 {@link PriceMaintenanceService} 写操作后返回结果用）。不存在返回 null。*/
    @Transactional(Transactional.TxType.SUPPORTS)
    public ElementPriceRowDTO findById(UUID id) {
        String fromClause =
                " FROM element_daily_price edp " +
                " LEFT JOIN element e ON e.element_code = edp.element_name " +
                " LEFT JOIN element_price_source s ON s.id = edp.source_id " +
                " LEFT JOIN \"user\" u ON u.id = edp.updated_by ";
        Query q = em.createNativeQuery(
                "SELECT edp.element_name, e.element_name, edp.price_date, edp.source_id, s.source_name, s.status, " +
                "       edp.raw_price, edp.currency, edp.price_unit, u.full_name, edp.updated_at, " +
                "       edp.id, edp.fetch_status" +
                fromClause + " WHERE edp.id = :id");
        q.setParameter("id", id);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        return rows.isEmpty() ? null : mapDetailRow(rows.get(0));
    }

    // ──────────────────────────────── 3.2 矩阵 ────────────────────────────────

    @Transactional(Transactional.TxType.SUPPORTS)
    public PriceMatrixDTO matrix(UUID sourceId, LocalDate from, LocalDate to, String keyword) {
        if (sourceId == null) {
            throw new BusinessException(400, "矩阵视图必须指定 sourceId");
        }
        LocalDate effFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate effTo = to != null ? to : LocalDate.now();
        if (effFrom.isAfter(effTo)) {
            throw new BusinessException(400, "起始日期不能晚于结束日期");
        }
        long spanDays = java.time.temporal.ChronoUnit.DAYS.between(effFrom, effTo);
        if (spanDays > MAX_MATRIX_SPAN_DAYS) {
            throw new BusinessException(400, "矩阵视图日期跨度最长 90 天，请收窄区间");
        }

        boolean hasKw = keyword != null && !keyword.isBlank();
        StringBuilder sql = new StringBuilder(
                "SELECT edp.element_name, e.element_name, edp.price_date, edp.raw_price " +
                "FROM element_daily_price edp " +
                "LEFT JOIN element e ON e.element_code = edp.element_name " +
                "LEFT JOIN element_price_source s ON s.id = edp.source_id " +
                "WHERE edp.source_id = :sourceId AND edp.price_date BETWEEN :from AND :to ");
        if (hasKw) sql.append(" AND (edp.element_name ILIKE :kw OR e.element_name ILIKE :kw) ");
        sql.append(" ORDER BY edp.element_name, edp.price_date");

        Query q = em.createNativeQuery(sql.toString());
        q.setParameter("sourceId", sourceId);
        q.setParameter("from", effFrom);
        q.setParameter("to", effTo);
        if (hasKw) q.setParameter("kw", "%" + keyword.trim() + "%");

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        PriceMatrixDTO dto = new PriceMatrixDTO();
        dto.sourceId = sourceId;
        dto.sourceName = lookupSourceName(sourceId);

        // dates = 请求区间 [effFrom, effTo] 内的每一天（升序、稠密），不是"只含有数据的那些天"。
        // 缺失天由下方 prices 初始化为 null 兜底（api.md §3.2 / backtask B6，2026-07-23 稀疏→稠密返修）。
        List<LocalDate> denseDates = new ArrayList<>();
        for (LocalDate d = effFrom; !d.isAfter(effTo); d = d.plusDays(1)) denseDates.add(d);
        dto.dates = denseDates;
        Map<LocalDate, Integer> dateIdx = new LinkedHashMap<>();
        int idx = 0;
        for (LocalDate d : dto.dates) dateIdx.put(d, idx++);

        LinkedHashMap<String, PriceMatrixRowDTO> byElement = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String code = (String) r[0];
            String cnName = r[1] != null ? (String) r[1] : code;
            LocalDate d = toLocalDate(r[2]);
            BigDecimal price = r[3] != null ? new BigDecimal(r[3].toString()) : null;

            PriceMatrixRowDTO mr = byElement.computeIfAbsent(code, k -> {
                PriceMatrixRowDTO nr = new PriceMatrixRowDTO();
                nr.elementCode = code;
                nr.elementName = cnName;
                for (int i = 0; i < dto.dates.size(); i++) nr.prices.add(null);
                return nr;
            });
            Integer i = dateIdx.get(d);
            if (i != null) mr.prices.set(i, price);
        }
        dto.rows = new ArrayList<>(byElement.values());
        return dto;
    }

    private String lookupSourceName(UUID sourceId) {
        @SuppressWarnings("unchecked")
        List<String> names = em.createNativeQuery("SELECT source_name FROM element_price_source WHERE id = :id")
                .setParameter("id", sourceId).getResultList();
        return names.isEmpty() ? null : names.get(0);
    }

    // ──────────────────────────────── 3.3 导出 ────────────────────────────────

    public byte[] exportDetail(UUID sourceId, LocalDate from, LocalDate to, String keyword) {
        // 导出内容 = 当前筛选的全量结果（不分页）
        PageResult<ElementPriceRowDTO> full = listDetail(sourceId, from, to, keyword, 0, Integer.MAX_VALUE);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("价格明细");
            Row h = sheet.createRow(0);
            String[] cols = {"元素符号", "中文名", "价格日期", "价格源", "单价", "货币", "计价单位", "录入人", "录入时间"};
            for (int i = 0; i < cols.length; i++) h.createCell(i).setCellValue(cols[i]);
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            int r = 1;
            for (ElementPriceRowDTO d : full.getContent()) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(nvl(d.elementCode));
                row.createCell(1).setCellValue(nvl(d.elementName));
                row.createCell(2).setCellValue(d.priceDate != null ? d.priceDate.toString() : "");
                row.createCell(3).setCellValue(nvl(d.sourceName));
                if (d.price != null) row.createCell(4).setCellValue(d.price.doubleValue());
                row.createCell(5).setCellValue(nvl(d.currency));
                row.createCell(6).setCellValue(nvl(d.priceUnit));
                row.createCell(7).setCellValue(nvl(d.operatorName));
                row.createCell(8).setCellValue(d.updatedAt != null ? d.updatedAt.format(dtf) : "");
            }
            for (int i = 0; i < cols.length; i++) sheet.setColumnWidth(i, 3800);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("导出价格明细失败: " + e.getMessage(), e);
        }
    }

    public byte[] exportMatrix(UUID sourceId, LocalDate from, LocalDate to, String keyword) {
        PriceMatrixDTO m = matrix(sourceId, from, to, keyword);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("价格矩阵");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("元素符号");
            h.createCell(1).setCellValue("中文名");
            for (int i = 0; i < m.dates.size(); i++) {
                h.createCell(2 + i).setCellValue(m.dates.get(i).toString());
            }
            int r = 1;
            for (PriceMatrixRowDTO row : m.rows) {
                Row xr = sheet.createRow(r++);
                xr.createCell(0).setCellValue(nvl(row.elementCode));
                xr.createCell(1).setCellValue(nvl(row.elementName));
                for (int i = 0; i < row.prices.size(); i++) {
                    BigDecimal p = row.prices.get(i);
                    if (p != null) xr.createCell(2 + i).setCellValue(p.doubleValue());
                }
            }
            sheet.setColumnWidth(0, 3000);
            sheet.setColumnWidth(1, 3000);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("导出价格矩阵失败: " + e.getMessage(), e);
        }
    }

    // ──────────────────────────────── B7.1 各源最新价 ────────────────────────────────

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<ElementLatestPriceDTO> latestBySource(String elementCode) {
        if (elementCode == null || elementCode.isBlank()) {
            throw new BusinessException(400, "elementCode 不能为空");
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT DISTINCT ON (edp.source_id) " +
                "  edp.source_id, s.source_name, s.status, edp.raw_price, edp.currency, edp.price_unit, edp.price_date " +
                "FROM element_daily_price edp " +
                "JOIN element_price_source s ON s.id = edp.source_id " +
                "WHERE edp.element_name = :code " +
                "ORDER BY edp.source_id, edp.price_date DESC")
                .setParameter("code", elementCode.trim())
                .getResultList();

        List<ElementLatestPriceDTO> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            ElementLatestPriceDTO d = new ElementLatestPriceDTO();
            d.sourceId = (UUID) r[0];
            d.sourceName = (String) r[1];
            d.sourceStatus = (String) r[2];
            d.price = r[3] != null ? new BigDecimal(r[3].toString()) : null;
            d.currency = (String) r[4];
            d.priceUnit = (String) r[5];
            d.priceDate = toLocalDate(r[6]);
            out.add(d);
        }
        // 启用优先 → 源名称排序（便于阅读；需求未强制具体顺序）
        out.sort((a, b) -> {
            boolean aActive = "ACTIVE".equals(a.sourceStatus);
            boolean bActive = "ACTIVE".equals(b.sourceStatus);
            if (aActive != bActive) return aActive ? -1 : 1;
            String an = a.sourceName == null ? "" : a.sourceName;
            String bn = b.sourceName == null ? "" : b.sourceName;
            return an.compareTo(bn);
        });
        return out;
    }

    // ──────────────────────────────── update-0724 · B5 变更历史 ────────────────────────────────

    /**
     * 变更历史查询（契约见 api.md §5）。算法比照 {@code StrategyService.listHistory}，
     * 但价格日志无客户维度，不能照抄"全表 load"——先用 SQL 把候选集收敛到匹配筛选条件的
     * "价格身份"，再取这些身份的完整时间线（diff 需要完整时间线，不能先按 changed_at 截断），
     * 窗口过滤留到拍平后再做（与策略侧一致）。
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public PageResult<PriceHistoryDTO> listHistory(UUID sourceId, LocalDate from, LocalDate to,
                                                     String keyword, int page, int size) {
        if (page < 0) page = 0;
        if (size <= 0 || size > 200) size = 20;
        boolean hasKw = keyword != null && !keyword.isBlank();

        // to 是 date 但 changed_at 是 timestamptz：用 changed_at < (to+1天) 表达"含当天"，不用 <= to（会漏当天 00:00 之后的记录）。
        OffsetDateTime fromTs = from != null ? from.atStartOfDay().atOffset(ZoneOffset.UTC) : null;
        OffsetDateTime toExclusiveTs = to != null ? to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC) : null;

        StringBuilder hitWhere = new StringBuilder(" WHERE 1=1 ");
        if (sourceId != null) hitWhere.append(" AND l2.source_id = :sourceId ");
        if (fromTs != null) hitWhere.append(" AND l2.changed_at >= :fromTs ");
        if (toExclusiveTs != null) hitWhere.append(" AND l2.changed_at < :toExclusiveTs ");
        if (hasKw) hitWhere.append(" AND (l2.element_name ILIKE :kw OR e.element_name ILIKE :kw) ");

        String sql =
                "SELECT l.id, l.element_name, l.source_id, l.price_date, l.action, l.snapshot::text, " +
                "       l.changed_at, l.changed_by_name " +
                "FROM element_daily_price_log l " +
                "JOIN ( " +
                "  SELECT DISTINCT l2.element_name, COALESCE(l2.source_id::text,'') AS sid, l2.price_date " +
                "  FROM element_daily_price_log l2 " +
                "  LEFT JOIN element e ON e.element_code = l2.element_name " +
                hitWhere +
                ") hit " +
                "  ON hit.element_name = l.element_name " +
                " AND hit.sid = COALESCE(l.source_id::text,'') " +
                " AND hit.price_date = l.price_date " +
                "ORDER BY l.element_name, COALESCE(l.source_id::text,''), l.price_date, l.changed_at ASC";

        Query q = em.createNativeQuery(sql);
        if (sourceId != null) q.setParameter("sourceId", sourceId);
        if (fromTs != null) q.setParameter("fromTs", fromTs);
        if (toExclusiveTs != null) q.setParameter("toExclusiveTs", toExclusiveTs);
        if (hasKw) q.setParameter("kw", "%" + keyword.trim() + "%");

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        // 分组：按"价格身份" = (element_name, COALESCE(source_id,''), price_date)；
        // SQL 已按该键 + changed_at ASC 排序，同组行在结果集中连续，无需再排序。
        LinkedHashMap<String, List<Object[]>> groups = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String elementCode = (String) r[1];
            UUID sid = r[2] != null ? (UUID) r[2] : null;
            LocalDate pd = toLocalDate(r[3]);
            String gk = elementCode + "||" + (sid != null ? sid.toString() : "") + "||" + pd;
            groups.computeIfAbsent(gk, k -> new ArrayList<>()).add(r);
        }

        List<String> codes = rows.stream().map(r -> (String) r[1]).distinct().collect(Collectors.toList());
        List<UUID> sourceIds = rows.stream().map(r -> r[2] != null ? (UUID) r[2] : null)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<String, String> elementNames = loadElementNamesForCodes(codes);
        Map<UUID, String> sourceNames = loadSourceNamesForIds(sourceIds);

        List<PriceHistoryDTO> diffed = new ArrayList<>();
        for (List<Object[]> group : groups.values()) {
            JsonNode prevSnap = null;
            for (Object[] r : group) {
                UUID id = (UUID) r[0];
                String elementCode = (String) r[1];
                UUID sid = r[2] != null ? (UUID) r[2] : null;
                LocalDate priceDate = toLocalDate(r[3]);
                String action = (String) r[4];
                String snapshotText = (String) r[5];
                OffsetDateTime changedAt = toOffsetDateTime(r[6]);
                String changedByName = (String) r[7];

                JsonNode snap = parseSnapshot(snapshotText);
                PriceHistoryDTO dto = new PriceHistoryDTO();
                dto.id = id;
                dto.changedAt = changedAt;
                dto.changedByName = changedByName;
                dto.action = action;
                dto.elementCode = elementCode;
                dto.elementName = elementNames.getOrDefault(elementCode, elementCode);
                dto.sourceId = sid;
                dto.sourceName = sid != null ? sourceNames.get(sid) : null;
                dto.priceDate = priceDate;
                dto.targetLabel = buildHistoryTargetLabel(elementCode, dto.elementName, dto.sourceName, priceDate);
                dto.snapshot = snap;
                dto.changes = ("UPDATE".equals(action) && prevSnap != null)
                        ? diffPriceSnapshots(prevSnap, snap) : new ArrayList<>();
                diffed.add(dto);

                prevSnap = "DELETE".equals(action) ? null : snap;
            }
        }

        List<PriceHistoryDTO> filtered = diffed.stream()
                .filter(d -> fromTs == null || !d.changedAt.isBefore(fromTs))
                .filter(d -> toExclusiveTs == null || d.changedAt.isBefore(toExclusiveTs))
                .sorted((a, b) -> b.changedAt.compareTo(a.changedAt))
                .collect(Collectors.toList());

        long total = filtered.size();
        int fromIdx = Math.min(page * size, filtered.size());
        int toIdx = Math.min(fromIdx + size, filtered.size());
        return new PageResult<>(filtered.subList(fromIdx, toIdx), page, size, total);
    }

    private String buildHistoryTargetLabel(String elementCode, String elementName, String sourceName, LocalDate priceDate) {
        StringBuilder sb = new StringBuilder();
        sb.append(elementCode);
        if (elementName != null && !elementName.equals(elementCode)) sb.append(" ").append(elementName);
        sb.append(" · ").append(sourceName != null ? sourceName : "—");
        sb.append(" · ").append(priceDate != null ? priceDate.toString() : "—");
        return sb.toString();
    }

    private List<PriceChangeDTO> diffPriceSnapshots(JsonNode prev, JsonNode curr) {
        List<PriceChangeDTO> changes = new ArrayList<>();
        String prevPrice = decimalOrNull(prev, "price");
        String currPrice = decimalOrNull(curr, "price");
        if (!Objects.equals(prevPrice, currPrice)) {
            changes.add(new PriceChangeDTO("price", "单价", prevPrice, currPrice));
        }
        String prevCur = textOrNull(prev, "currency");
        String currCur = textOrNull(curr, "currency");
        if (!Objects.equals(prevCur, currCur)) {
            changes.add(new PriceChangeDTO("currency", "货币", prevCur, currCur));
        }
        String prevUnit = textOrNull(prev, "priceUnit");
        String currUnit = textOrNull(curr, "priceUnit");
        if (!Objects.equals(prevUnit, currUnit)) {
            changes.add(new PriceChangeDTO("priceUnit", "计价单位", prevUnit, currUnit));
        }
        String prevStatus = textOrNull(prev, "fetchStatus");
        String currStatus = textOrNull(curr, "fetchStatus");
        if (!Objects.equals(prevStatus, currStatus)) {
            changes.add(new PriceChangeDTO("fetchStatus", "数据来源", fetchStatusLabel(prevStatus), fetchStatusLabel(currStatus)));
        }
        return changes;
    }

    private String fetchStatusLabel(String s) {
        if (s == null) return null;
        return switch (s) {
            case "MANUAL" -> "手工";
            case "IMPORT" -> "导入";
            case "SUCCESS" -> "自动抓取成功";
            case "FAILED" -> "自动抓取失败";
            default -> s;
        };
    }

    private String textOrNull(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private String decimalOrNull(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        try {
            return new BigDecimal(v.asText()).setScale(4, RoundingMode.HALF_UP).toPlainString();
        } catch (Exception e) {
            return v.asText();
        }
    }

    private JsonNode parseSnapshot(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private Map<String, String> loadElementNamesForCodes(List<String> codes) {
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

    private Map<UUID, String> loadSourceNamesForIds(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, source_name FROM element_price_source WHERE id IN (:ids)")
                .setParameter("ids", ids)
                .getResultList();
        Map<UUID, String> out = new HashMap<>();
        for (Object[] r : rows) out.put((UUID) r[0], (String) r[1]);
        return out;
    }

    // ──────────────────────────────── helpers ────────────────────────────────

    private String nvl(String s) { return s == null ? "" : s; }

    private LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Date sd) return sd.toLocalDate();
        if (o instanceof LocalDate ld) return ld;
        return LocalDate.parse(o.toString());
    }

    private java.time.OffsetDateTime toOffsetDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof java.time.OffsetDateTime odt) return odt;
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        if (o instanceof java.time.Instant i) return i.atOffset(java.time.ZoneOffset.UTC);
        return null;
    }
}
