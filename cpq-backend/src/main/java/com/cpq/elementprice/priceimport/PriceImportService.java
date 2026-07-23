package com.cpq.elementprice.priceimport;

import com.cpq.common.exception.BusinessException;
import com.cpq.elementprice.source.ElementPriceSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * 价格导入编排服务（task-0722 · B5，契约见 api.md §2）。
 *
 * <p><b>🔒 外层不开事务</b>：逐行调用 {@link PriceImportRowWriter#writeRow}（各自
 * {@code REQUIRES_NEW}），保证"部分成功"——失败行不阻断其它行入库（§11.3.2）。
 */
@ApplicationScoped
public class PriceImportService {

    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final String COL_ELEMENT = "元素符号";   // 兼容模板列 "元素符号*"
    private static final String COL_PRICE = "单价";          // 兼容模板列 "单价*"
    private static final String COL_CURRENCY = "货币";
    private static final String COL_UNIT = "计价单位";

    @Inject
    EntityManager em;

    @Inject
    PriceImportRowWriter writer;

    // ──────────────────────────────── 导入 ────────────────────────────────

    public PriceImportResultDTO importPrices(byte[] xlsxBytes, UUID sourceId, LocalDate priceDate, UUID operatorId) {
        long t0 = System.nanoTime();

        // ── 整批级前置校验（§11.3 前置校验部分）：文件为空/非xlsx/>5MB/表头不匹配/源非启用 ──
        if (xlsxBytes == null || xlsxBytes.length == 0) {
            throw new BusinessException(400, "上传文件为空");
        }
        if (xlsxBytes.length > MAX_FILE_SIZE) {
            throw new BusinessException(400, "文件超过 5MB 限制");
        }
        if (sourceId == null) {
            throw new BusinessException(400, "价格源不能为空");
        }
        if (priceDate == null) {
            throw new BusinessException(400, "价格日期不能为空");
        }
        ElementPriceSource src = ElementPriceSource.findById(sourceId);
        if (src == null) {
            throw new BusinessException(400, "价格源不存在: " + sourceId);
        }
        if (!"ACTIVE".equals(src.status)) {
            throw new BusinessException(400, "价格源「" + src.sourceName + "」已停用，不可用于导入");
        }

        PriceImportResultDTO result = new PriceImportResultDTO();
        result.sourceId = sourceId;
        result.sourceName = src.sourceName;
        result.priceDate = priceDate;
        result.operatorName = lookupUserName(operatorId);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsxBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) throw new BusinessException(400, "工作簿不含任何 sheet");

            Row header = sheet.getRow(0);
            if (header == null) throw new BusinessException(400, "首行表头为空");
            LinkedHashMap<String, Integer> colIdx = new LinkedHashMap<>();
            for (Cell c : header) {
                String h = cellStr(c).replace("*", "").trim();
                if (!h.isBlank()) colIdx.putIfAbsent(h, c.getColumnIndex());
            }
            Integer idxElement = colIdx.get(COL_ELEMENT);
            Integer idxPrice = colIdx.get(COL_PRICE);
            if (idxElement == null || idxPrice == null) {
                throw new BusinessException(400, "表头不匹配：需包含 " + COL_ELEMENT + "* / " + COL_PRICE + "*");
            }
            Integer idxCurrency = colIdx.get(COL_CURRENCY);
            Integer idxUnit = colIdx.get(COL_UNIT);

            int lastRow = sheet.getLastRowNum();
            for (int r = 1; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String elementCode = cellStr(row.getCell(idxElement));
                String priceStr = cellStr(row.getCell(idxPrice));
                String currency = idxCurrency == null ? "" : cellStr(row.getCell(idxCurrency));
                String unit = idxUnit == null ? "" : cellStr(row.getCell(idxUnit));

                // 整行空白（模板示例行之外的空行）跳过，不计入统计
                if (elementCode.isBlank() && priceStr.isBlank() && currency.isBlank() && unit.isBlank()) {
                    continue;
                }

                int excelRowNo = r + 1; // 1-based 物理行号
                BigDecimal price = parseDecimal(priceStr);

                PriceImportRowDTO rowResult;
                try {
                    rowResult = writer.writeRow(excelRowNo, elementCode, price, currency, unit,
                            sourceId, priceDate, operatorId);
                } catch (Exception e) {
                    rowResult = new PriceImportRowDTO();
                    rowResult.rowNo = excelRowNo;
                    rowResult.elementCode = elementCode;
                    rowResult.price = price;
                    rowResult.result = "FAILED";
                    rowResult.message = "写入失败: " + e.getMessage();
                }
                result.rows.add(rowResult);
                switch (rowResult.result) {
                    case "CREATED" -> result.createdCount++;
                    case "UPDATED" -> result.updatedCount++;
                    default -> result.failedCount++;
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "文件格式无效或已损坏: " + e.getMessage());
        }

        result.elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        return result;
    }

    // ──────────────────────────────── 模板下载 ────────────────────────────────

    public byte[] generateTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("价格导入");
            Row h = s.createRow(0);
            String[] cols = {"元素符号*", "单价*", "货币", "计价单位"};
            for (int i = 0; i < cols.length; i++) h.createCell(i).setCellValue(cols[i]);

            Row example = s.createRow(1);
            example.createCell(0).setCellValue("Ag");
            example.createCell(1).setCellValue(5820.0);
            example.createCell(2).setCellValue("CNY");
            example.createCell(3).setCellValue("元/kg");

            Row note = s.createRow(3);
            note.createCell(0).setCellValue("填写说明：元素符号须已在「元素管理」中启用；单价必须大于 0；货币/计价单位留空时分别取默认 CNY / 元/kg；价格日期与价格源在导入抽屉上选择，本表不含这两列。");

            for (int i = 0; i < cols.length; i++) s.setColumnWidth(i, 4000);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("生成导入模板失败: " + e.getMessage(), e);
        }
    }

    // ──────────────────────────────── helpers ────────────────────────────────

    private String lookupUserName(UUID userId) {
        if (userId == null) return null;
        @SuppressWarnings("unchecked")
        List<String> rows = em.createNativeQuery("SELECT full_name FROM \"user\" WHERE id = :id")
                .setParameter("id", userId)
                .getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BigDecimal parseDecimal(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 单元格取字符串：STRING 原样、NUMERIC 无科学计数/无多余小数、FORMULA 取缓存结果。*/
    private String cellStr(Cell c) {
        if (c == null) return "";
        switch (c.getCellType()) {
            case STRING:
                return c.getStringCellValue().trim();
            case NUMERIC:
                return numToStr(c.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(c.getBooleanCellValue());
            case FORMULA:
                try {
                    switch (c.getCachedFormulaResultType()) {
                        case STRING: return c.getStringCellValue().trim();
                        case NUMERIC: return numToStr(c.getNumericCellValue());
                        default: return "";
                    }
                } catch (Exception e) {
                    return "";
                }
            default:
                return "";
        }
    }

    private String numToStr(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) return Long.toString((long) d);
        return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
    }
}
