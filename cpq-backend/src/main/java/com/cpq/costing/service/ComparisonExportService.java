package com.cpq.costing.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.costing.dto.ComparisonExportRequest;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 比对视图 Excel 导出 —— 只按前端传来的已算好模型写值+填色，不重算任何路径/公式。
 * 布局：表头(料号 | 口径 | 各 tag 标签)，每个料号两行(报价/核价)，料号列纵向合并，差异格填充底色。
 */
@ApplicationScoped
public class ComparisonExportService {

    public byte[] export(ComparisonExportRequest req) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("比对");
            CellStyle headerStyle = headerStyle(wb);
            CellStyle highlightStyle = highlightStyle(wb);

            List<ComparisonExportRequest.Column> cols = req.columns != null ? req.columns : List.of();
            List<ComparisonExportRequest.Row> rows = req.rows != null ? req.rows : List.of();

            sheet.setColumnWidth(0, 6000);
            sheet.setColumnWidth(1, 2600);
            for (int i = 0; i < cols.size(); i++) sheet.setColumnWidth(2 + i, 4200);

            Row header = sheet.createRow(0);
            createCell(header, 0, "料号", headerStyle);
            createCell(header, 1, "口径", headerStyle);
            for (int i = 0; i < cols.size(); i++) {
                ComparisonExportRequest.Column col = cols.get(i);
                String label = col.label != null ? col.label : col.tag;
                if (col.groupName != null && !col.groupName.isEmpty()) label = "[" + col.groupName + "] " + label;
                createCell(header, 2 + i, label, headerStyle);
            }

            int r = 1;
            for (ComparisonExportRequest.Row row : rows) {
                Row reportRow = sheet.createRow(r);
                Row costingRow = sheet.createRow(r + 1);

                String partLabel = row.partNo != null ? row.partNo : "";
                if ("QUOTE_ONLY".equals(row.presence)) {
                    partLabel += " (仅报价)";
                } else if ("COSTING_ONLY".equals(row.presence)) {
                    partLabel += " (仅核价)";
                }
                // BOTH / null / 未知 presence: 不加后缀
                reportRow.createCell(0).setCellValue(partLabel);
                sheet.addMergedRegion(new CellRangeAddress(r, r + 1, 0, 0));

                reportRow.createCell(1).setCellValue("报价");
                costingRow.createCell(1).setCellValue("核价");

                Map<String, ComparisonExportRequest.Cell> cells = row.cells != null ? row.cells : Map.of();
                for (int i = 0; i < cols.size(); i++) {
                    ComparisonExportRequest.Cell cell = cells.get(cols.get(i).tag);
                    int c = 2 + i;
                    Object qv = cell != null ? cell.quote : null;
                    Object cv = cell != null ? cell.costing : null;
                    boolean hl = cell != null && cell.highlighted;
                    writeValue(reportRow.createCell(c), qv, hl ? highlightStyle : null);
                    writeValue(costingRow.createCell(c), cv, hl ? highlightStyle : null);
                }
                r += 2;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(500, "导出比对 Excel 失败: " + e.getMessage());
        }
    }

    private void writeValue(Cell cell, Object v, CellStyle style) {
        if (v instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (v != null) {
            String s = String.valueOf(v);
            try {
                cell.setCellValue(Double.parseDouble(s.trim()));
            } catch (NumberFormatException ex) {
                cell.setCellValue(s);
            }
        }
        if (style != null) cell.setCellStyle(style);
    }

    private void createCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        if (style != null) cell.setCellStyle(style);
    }

    private CellStyle headerStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle highlightStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
