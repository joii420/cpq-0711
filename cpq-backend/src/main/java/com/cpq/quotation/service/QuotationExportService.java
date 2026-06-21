package com.cpq.quotation.service;

import com.cpq.common.NumberFormatUtil;
import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.dto.QuotationDTO;
import io.quarkus.qute.Template;
import io.quarkus.qute.Location;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class QuotationExportService {

    private static final Logger LOG = Logger.getLogger(QuotationExportService.class);

    @Inject
    QuotationService quotationService;

    @Inject
    @Location("quotation-pdf.html")
    Template quotationPdfTemplate;

    /**
     * Export quotation as HTML (for browser print-to-PDF).
     * Returns HTML bytes that the frontend can open in a new window for printing.
     */
    public byte[] exportHtml(UUID quotationId, boolean showDiscount, boolean showProcesses, boolean showTabDetails) {
        QuotationDTO q = quotationService.getById(quotationId);
        LOG.infof("Generating HTML export for quotation id=%s number=%s", quotationId, q.quotationNumber);

        // Build line item view models
        List<Map<String, Object>> lineItemsData = buildLineItemsData(q);

        String statusLabel = getStatusLabel(q.status);
        String printTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // Build a data object for the template
        QuotationTemplateData templateData = new QuotationTemplateData(q, statusLabel);

        String html = quotationPdfTemplate
                .data("quotation", templateData)
                .data("lineItems", lineItemsData)
                .data("showDiscount", showDiscount)
                .data("printTime", printTime)
                .render();

        return html.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Export quotation as Excel (xlsx).
     */
    public byte[] exportExcel(UUID quotationId, boolean showDiscount, boolean includeRawData) {
        QuotationDTO q = quotationService.getById(quotationId);
        LOG.infof("Generating Excel export for quotation id=%s number=%s", quotationId, q.quotationNumber);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            createSummarySheet(workbook, q, showDiscount);
            if (includeRawData) {
                createRawDataSheet(workbook, q);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(500, "Failed to generate Excel: " + e.getMessage());
        }
    }

    // ---- Private helpers ----

    private void createSummarySheet(XSSFWorkbook workbook, QuotationDTO q, boolean showDiscount) {
        Sheet sheet = workbook.createSheet("报价单");
        sheet.setColumnWidth(0, 5000);
        sheet.setColumnWidth(1, 8000);
        sheet.setColumnWidth(2, 5000);
        sheet.setColumnWidth(3, 8000);
        sheet.setColumnWidth(4, 4000);
        sheet.setColumnWidth(5, 5000);

        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle boldStyle = createBoldStyle(workbook);
        CellStyle normalStyle = createNormalStyle(workbook);
        CellStyle amountStyle = createAmountStyle(workbook);

        int row = 0;

        // Title
        Row titleRow = sheet.createRow(row++);
        titleRow.setHeightInPoints(28);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("报价单");
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));

        row++; // blank

        // Basic info
        writeInfoRow(sheet, row++, "报价单号:", q.quotationNumber, "报价日期:", q.createdAt != null ? q.createdAt.toLocalDate().toString() : "", boldStyle, normalStyle);
        writeInfoRow(sheet, row++, "客户名称:", q.snapshotCustomerName, "有效期至:", q.expiryDate != null ? q.expiryDate.toString() : "", boldStyle, normalStyle);
        writeInfoRow(sheet, row++, "联系人:", q.contactName, "联系电话:", q.contactPhone, boldStyle, normalStyle);
        writeInfoRow(sheet, row++, "项目名称:", q.projectName, "联系邮箱:", q.contactEmail, boldStyle, normalStyle);
        if (q.paymentTerms != null) {
            writeInfoRow(sheet, row++, "付款条款:", q.paymentTerms, "交货周期:", q.deliveryCycle != null ? q.deliveryCycle + " 天" : "", boldStyle, normalStyle);
        }

        row++; // blank

        // Line items header
        Row lineHeader = sheet.createRow(row++);
        int col = 0;
        createHeaderCell(lineHeader, col++, "序号", headerStyle);
        createHeaderCell(lineHeader, col++, "产品规格", headerStyle);
        createHeaderCell(lineHeader, col++, "产品分类", headerStyle);
        createHeaderCell(lineHeader, col++, "属性值", headerStyle);
        if (showDiscount) {
            createHeaderCell(lineHeader, col++, "折扣率(%)", headerStyle);
        }
        createHeaderCell(lineHeader, col++, "小计(元)", headerStyle);

        // Line items data
        int lineNum = 1;
        BigDecimal grandTotal = BigDecimal.ZERO;
        if (q.lineItems != null) {
            for (QuotationDTO.LineItemDTO li : q.lineItems) {
                Row dataRow = sheet.createRow(row++);
                col = 0;
                String spec = li.snapshot != null ? (li.snapshot.productSpecification != null ? li.snapshot.productSpecification : "") : "";
                String cat = li.snapshot != null ? (li.snapshot.productCategory != null ? li.snapshot.productCategory : "") : "";
                dataRow.createCell(col++).setCellValue(lineNum++);
                dataRow.createCell(col++).setCellValue(spec);
                dataRow.createCell(col++).setCellValue(cat);
                dataRow.createCell(col++).setCellValue(li.productAttributeValues != null ? li.productAttributeValues : "");
                if (showDiscount) {
                    dataRow.createCell(col++).setCellValue(li.finalDiscountRate != null ? li.finalDiscountRate.doubleValue() : 100.0);
                }
                Cell subtotalCell = dataRow.createCell(col++);
                subtotalCell.setCellStyle(amountStyle);
                if (li.subtotal != null) {
                    subtotalCell.setCellValue(li.subtotal.doubleValue());
                    grandTotal = grandTotal.add(li.subtotal);
                }
            }
        }

        row++; // blank

        // Totals
        Row origRow = sheet.createRow(row++);
        origRow.createCell(col - 2).setCellValue("原价合计:");
        origRow.createCell(col - 1).setCellValue(q.originalAmount != null ? q.originalAmount.doubleValue() : 0);

        if (showDiscount) {
            Row discRow = sheet.createRow(row++);
            discRow.createCell(col - 2).setCellValue("折扣率(%):");
            discRow.createCell(col - 1).setCellValue(q.finalDiscountRate != null ? q.finalDiscountRate.doubleValue() : 100.0);
        }

        Row totalRow = sheet.createRow(row++);
        Cell totalLabel = totalRow.createCell(col - 2);
        totalLabel.setCellValue("报价总金额:");
        totalLabel.setCellStyle(boldStyle);
        Cell totalCell = totalRow.createCell(col - 1);
        totalCell.setCellValue(q.totalAmount != null ? q.totalAmount.doubleValue() : 0);
        totalCell.setCellStyle(amountStyle);
    }

    private void createRawDataSheet(XSSFWorkbook workbook, QuotationDTO q) {
        Sheet sheet = workbook.createSheet("原始数据");
        sheet.setColumnWidth(0, 5000);
        sheet.setColumnWidth(1, 5000);
        sheet.setColumnWidth(2, 5000);
        sheet.setColumnWidth(3, 12000);

        CellStyle headerStyle = createHeaderStyle(workbook);

        Row header = sheet.createRow(0);
        createHeaderCell(header, 0, "行项目ID", headerStyle);
        createHeaderCell(header, 1, "组件Tab", headerStyle);
        createHeaderCell(header, 2, "组件小计", headerStyle);
        createHeaderCell(header, 3, "行数据(JSON)", headerStyle);

        int row = 1;
        if (q.lineItems != null) {
            for (QuotationDTO.LineItemDTO li : q.lineItems) {
                if (li.componentData != null) {
                    for (QuotationDTO.ComponentDataDTO cd : li.componentData) {
                        Row dataRow = sheet.createRow(row++);
                        dataRow.createCell(0).setCellValue(li.id != null ? li.id.toString() : "");
                        dataRow.createCell(1).setCellValue(cd.tabName != null ? cd.tabName : "");
                        dataRow.createCell(2).setCellValue(cd.subtotal != null ? cd.subtotal.doubleValue() : 0);
                        dataRow.createCell(3).setCellValue(cd.rowData != null ? cd.rowData : "");
                    }
                }
            }
        }
    }

    private List<Map<String, Object>> buildLineItemsData(QuotationDTO q) {
        if (q.lineItems == null) return List.of();
        return q.lineItems.stream().map(li -> {
            Map<String, Object> m = new HashMap<>();
            m.put("specification", li.snapshot != null && li.snapshot.productSpecification != null ? li.snapshot.productSpecification : "");
            m.put("category", li.snapshot != null && li.snapshot.productCategory != null ? li.snapshot.productCategory : "");
            m.put("attributeValues", li.productAttributeValues != null ? li.productAttributeValues : "");
            m.put("discountRate", li.finalDiscountRate != null ? li.finalDiscountRate.toString() : "100");
            m.put("subtotal", li.subtotal != null ? NumberFormatUtil.format(li.subtotal, null, true) : "0");
            return m;
        }).toList();
    }

    private String getStatusLabel(String status) {
        if (status == null) return "";
        return switch (status) {
            case "DRAFT" -> "草稿";
            case "SUBMITTED" -> "已提交";
            case "APPROVED" -> "已批准";
            case "SENT" -> "已发送";
            case "ACCEPTED" -> "已接受";
            case "REJECTED" -> "已驳回";
            case "EXPIRED" -> "已过期";
            default -> status;
        };
    }

    private void writeInfoRow(Sheet sheet, int rowNum, String label1, String val1, String label2, String val2,
                               CellStyle labelStyle, CellStyle valStyle) {
        Row row = sheet.createRow(rowNum);
        Cell c0 = row.createCell(0); c0.setCellValue(label1 != null ? label1 : ""); c0.setCellStyle(labelStyle);
        Cell c1 = row.createCell(1); c1.setCellValue(val1 != null ? val1 : ""); c1.setCellStyle(valStyle);
        Cell c2 = row.createCell(3); c2.setCellValue(label2 != null ? label2 : ""); c2.setCellStyle(labelStyle);
        Cell c3 = row.createCell(4); c3.setCellValue(val2 != null ? val2 : ""); c3.setCellStyle(valStyle);
    }

    private void createHeaderCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private CellStyle createTitleStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private CellStyle createHeaderStyle(XSSFWorkbook wb) {
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

    private CellStyle createBoldStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private CellStyle createNormalStyle(XSSFWorkbook wb) {
        return wb.createCellStyle();
    }

    private CellStyle createAmountStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0.00"));
        style.setAlignment(HorizontalAlignment.RIGHT);
        return style;
    }

    /**
     * Simple view model for Qute template data binding.
     */
    public static class QuotationTemplateData {
        public final String quotationNumber;
        public final String createdAt;
        public final String expiryDate;
        public final String statusLabel;
        public final String projectName;
        public final String quoteType;
        public final String snapshotCustomerName;
        public final String snapshotCustomerLevel;
        public final String snapshotCustomerRegion;
        public final String contactName;
        public final String contactPhone;
        public final String contactEmail;
        public final String paymentTerms;
        public final String deliveryCycle;
        public final String originalAmount;
        public final String finalDiscountRate;
        public final String totalAmount;

        public QuotationTemplateData(QuotationDTO q, String statusLabel) {
            this.quotationNumber = nvl(q.quotationNumber);
            this.createdAt = q.createdAt != null ? q.createdAt.toLocalDate().toString() : "";
            this.expiryDate = q.expiryDate != null ? q.expiryDate.toString() : "";
            this.statusLabel = statusLabel;
            this.projectName = nvl(q.projectName);
            this.quoteType = nvl(q.quoteType);
            this.snapshotCustomerName = nvl(q.snapshotCustomerName);
            this.snapshotCustomerLevel = nvl(q.snapshotCustomerLevel);
            this.snapshotCustomerRegion = nvl(q.snapshotCustomerRegion);
            this.contactName = nvl(q.contactName);
            this.contactPhone = nvl(q.contactPhone);
            this.contactEmail = nvl(q.contactEmail);
            this.paymentTerms = nvl(q.paymentTerms);
            this.deliveryCycle = q.deliveryCycle != null ? q.deliveryCycle.toString() : "";
            this.originalAmount = q.originalAmount != null ? q.originalAmount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() : "0.00";
            this.finalDiscountRate = q.finalDiscountRate != null ? q.finalDiscountRate.toPlainString() : "100";
            this.totalAmount = q.totalAmount != null ? q.totalAmount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() : "0.00";
        }

        private static String nvl(String s) { return s != null ? s : ""; }
    }
}
