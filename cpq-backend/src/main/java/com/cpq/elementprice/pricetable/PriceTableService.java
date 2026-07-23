package com.cpq.elementprice.pricetable;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 价格表查询服务（task-0722 · B6，契约见 api.md §3）+ B7.1 各源最新价。
 */
@ApplicationScoped
public class PriceTableService {

    private static final long MAX_MATRIX_SPAN_DAYS = 90;

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

        StringBuilder where = new StringBuilder(" WHERE edp.price_date BETWEEN :from AND :to ");
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
                "       edp.raw_price, edp.currency, edp.price_unit, u.full_name, edp.updated_at" +
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
        return d;
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
