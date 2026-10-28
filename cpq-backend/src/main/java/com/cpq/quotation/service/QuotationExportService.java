package com.cpq.quotation.service;

import com.cpq.common.NumberFormatUtil;
import com.cpq.common.PrecisionPolicy;
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
        return exportHtml(quotationId, showDiscount, showProcesses, showTabDetails,
                PrecisionPolicy.DISPLAY_SCALE);
    }

    public byte[] exportHtml(UUID quotationId, boolean showDiscount, boolean showProcesses,
                             boolean showTabDetails, int maxDisplayDecimals) {
        QuotationDTO q = quotationService.getById(quotationId);
        LOG.infof("Generating HTML export for quotation id=%s number=%s", quotationId, q.quotationNumber);

        // Build line item view models
        List<Map<String, Object>> lineItemsData = buildLineItemsData(q, maxDisplayDecimals);

        String statusLabel = getStatusLabel(q.status);
        String printTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // Build a data object for the template
        QuotationTemplateData templateData = new QuotationTemplateData(q, statusLabel, maxDisplayDecimals);

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
        return exportExcel(quotationId, showDiscount, includeRawData, PrecisionPolicy.DISPLAY_SCALE);
    }

    public byte[] exportExcel(UUID quotationId, boolean showDiscount, boolean includeRawData,
                              int maxDisplayDecimals) {
        QuotationDTO q = quotationService.getById(quotationId);
        LOG.infof("Generating Excel export for quotation id=%s number=%s", quotationId, q.quotationNumber);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            createSummarySheet(workbook, q, showDiscount, maxDisplayDecimals);
            if (includeRawData) {
                createRawDataSheet(workbook, q, maxDisplayDecimals);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(500, "Failed to generate Excel: " + e.getMessage());
        }
    }

    // ---- Private helpers ----

    private void createSummarySheet(XSSFWorkbook workbook, QuotationDTO q, boolean showDiscount,
                                    int maxDisplayDecimals) {
        Sheet sheet = workbook.createSheet("报价单");
        sheet.setColumnWidth(0, 5000);
        sheet.setColumnWidth(1, 8000);
        sheet.setColumnWidth(2, 5000);
        sheet.setColumnWidth(3, 8000);
        sheet.setColumnWidth(4, 4000);
        sheet.setColumnWidth(5, 5000);
        sheet.setColumnWidth(6, 3500);
        sheet.setColumnWidth(7, 5000);
        sheet.setColumnWidth(8, 5000);
        sheet.setColumnWidth(9, 5000);

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
            createHeaderCell(lineHeader, col++, "单价(元)", headerStyle);
            createHeaderCell(lineHeader, col++, "折扣金额(元)", headerStyle);
            createHeaderCell(lineHeader, col++, "折后单价(元)", headerStyle);
            createHeaderCell(lineHeader, col++, "行合计(元)", headerStyle);
        } else {
            createHeaderCell(lineHeader, col++, "小计(元)", headerStyle);
        }
        createHeaderCell(lineHeader, col++, "VAT(%)", headerStyle);
        createHeaderCell(lineHeader, col++, "未税金额(元)", headerStyle);
        createHeaderCell(lineHeader, col++, "税额(元)", headerStyle);
        createHeaderCell(lineHeader, col++, "含税金额(元)", headerStyle);

        // Line items data
        int lineNum = 1;
        BigDecimal grandTotal = BigDecimal.ZERO;
        BigDecimal untaxedTotal = BigDecimal.ZERO;
        BigDecimal vatTotal = BigDecimal.ZERO;
        BigDecimal taxedTotal = BigDecimal.ZERO;
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
                    // 折扣率(%)：行级 discountRateApplied（非整单 finalDiscountRate）
                    writeDefaultFormattedCell(dataRow.createCell(col++),
                        li.discountRateApplied != null ? li.discountRateApplied : BigDecimal.ZERO);
                    // 单价
                    Cell unitPriceCell = dataRow.createCell(col++);
                    unitPriceCell.setCellStyle(amountStyle);
                    if (li.lineUnitPrice != null) writeComputedAmountCell(unitPriceCell, li.lineUnitPrice, maxDisplayDecimals);
                    // 折扣金额
                    Cell discAmtCell = dataRow.createCell(col++);
                    discAmtCell.setCellStyle(amountStyle);
                    if (li.lineDiscountAmount != null) writeComputedAmountCell(discAmtCell, li.lineDiscountAmount, maxDisplayDecimals);
                    // 折后单价
                    Cell finalUnitCell = dataRow.createCell(col++);
                    finalUnitCell.setCellStyle(amountStyle);
                    if (li.lineFinalPrice != null) writeComputedAmountCell(finalUnitCell, li.lineFinalPrice, maxDisplayDecimals);
                    // 行合计
                    Cell lineTotalCell = dataRow.createCell(col++);
                    lineTotalCell.setCellStyle(amountStyle);
                    BigDecimal lineTotal = li.lineTotalAmount != null ? li.lineTotalAmount
                            : (li.subtotal != null ? li.subtotal : BigDecimal.ZERO);
                    writeComputedAmountCell(lineTotalCell, lineTotal, maxDisplayDecimals);
                    grandTotal = grandTotal.add(lineTotal);
                } else {
                    Cell subtotalCell = dataRow.createCell(col++);
                    subtotalCell.setCellStyle(amountStyle);
                    if (li.subtotal != null) {
                        writeComputedAmountCell(subtotalCell, li.subtotal, maxDisplayDecimals);
                        grandTotal = grandTotal.add(li.subtotal);
                    }
                }
                BigDecimal untaxed = lineAmount(li);
                BigDecimal vat = vatAmount(untaxed, li);
                BigDecimal taxed = untaxed.add(vat);
                writeDefaultFormattedCell(dataRow.createCell(col++), li.vatRate);
                writeComputedAmountCell(dataRow.createCell(col++), untaxed, maxDisplayDecimals);
                writeComputedAmountCell(dataRow.createCell(col++), vat, maxDisplayDecimals);
                writeComputedAmountCell(dataRow.createCell(col++), taxed, maxDisplayDecimals);
                untaxedTotal = untaxedTotal.add(untaxed);
                vatTotal = vatTotal.add(vat);
                taxedTotal = taxedTotal.add(taxed);
            }
        }

        row++; // blank

        // Totals
        Row origRow = sheet.createRow(row++);
        origRow.createCell(col - 2).setCellValue("原价合计:");
        writeComputedAmountCell(origRow.createCell(col - 1),
                q.originalAmount != null ? q.originalAmount : BigDecimal.ZERO, maxDisplayDecimals);

        if (showDiscount) {
            Row discRow = sheet.createRow(row++);
            discRow.createCell(col - 2).setCellValue("折扣率(%):");
            writeDefaultFormattedCell(discRow.createCell(col - 1),
                q.finalDiscountRate != null ? q.finalDiscountRate : new BigDecimal("100"));
        }

        Row totalRow = sheet.createRow(row++);
        Cell totalLabel = totalRow.createCell(col - 2);
        totalLabel.setCellValue("未税报价总金额:");
        totalLabel.setCellStyle(boldStyle);
        Cell totalCell = totalRow.createCell(col - 1);
        writeComputedAmountCell(totalCell, untaxedTotal, maxDisplayDecimals);
        totalCell.setCellStyle(amountStyle);

        Row vatRow = sheet.createRow(row++);
        vatRow.createCell(col - 2).setCellValue("税额合计:");
        writeComputedAmountCell(vatRow.createCell(col - 1), vatTotal, maxDisplayDecimals);

        Row taxedRow = sheet.createRow(row);
        Cell taxedLabel = taxedRow.createCell(col - 2);
        taxedLabel.setCellValue("含税报价总金额:");
        taxedLabel.setCellStyle(boldStyle);
        Cell taxedCell = taxedRow.createCell(col - 1);
        writeComputedAmountCell(taxedCell, taxedTotal, maxDisplayDecimals);
        taxedCell.setCellStyle(amountStyle);
    }

    /** Computed amounts are always exported as role-capped, precision-safe text cells. */
    private static void writeComputedAmountCell(Cell cell, BigDecimal v, int maxDisplayDecimals) {
        cell.setCellValue(NumberFormatUtil.format(v, null, true, maxDisplayDecimals));
    }

    /** Rates retain their pre-task formatting and never inherit the quote-result role cap. */
    private static void writeDefaultFormattedCell(Cell cell, BigDecimal v) {
        cell.setCellValue(NumberFormatUtil.format(v, null, true));
    }

    private void createRawDataSheet(XSSFWorkbook workbook, QuotationDTO q, int maxDisplayDecimals) {
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
                        writeComputedAmountCell(dataRow.createCell(2),
                                cd.subtotal != null ? cd.subtotal : BigDecimal.ZERO, maxDisplayDecimals);
                        dataRow.createCell(3).setCellValue(cd.rowData != null ? cd.rowData : "");
                    }
                }
            }
        }
    }

    private List<Map<String, Object>> buildLineItemsData(QuotationDTO q, int maxDisplayDecimals) {
        if (q.lineItems == null) return List.of();
        return q.lineItems.stream().map(li -> {
            Map<String, Object> m = new HashMap<>();
            m.put("specification", li.snapshot != null && li.snapshot.productSpecification != null ? li.snapshot.productSpecification : "");
            m.put("category", li.snapshot != null && li.snapshot.productCategory != null ? li.snapshot.productCategory : "");
            m.put("attributeValues", li.productAttributeValues != null ? li.productAttributeValues : "");
            m.put("discountRate", li.discountRateApplied != null ? li.discountRateApplied.toString() : "0");
            m.put("subtotal", li.subtotal != null
                    ? NumberFormatUtil.format(li.subtotal, null, true, maxDisplayDecimals) : "0");
            // 行级折扣明细字段（Task6）
            m.put("lineUnitPrice", formatAmount(li.lineUnitPrice, maxDisplayDecimals));
            m.put("lineDiscountAmount", formatAmount(li.lineDiscountAmount, maxDisplayDecimals));
            m.put("lineFinalPrice", formatAmount(li.lineFinalPrice, maxDisplayDecimals));
            BigDecimal lineTotal = li.lineTotalAmount != null ? li.lineTotalAmount
                    : (li.subtotal != null ? li.subtotal : BigDecimal.ZERO);
            m.put("lineTotalAmount", formatAmount(lineTotal, maxDisplayDecimals));
            m.put("vatRate", formatRate(li.vatRate));
            BigDecimal untaxed = lineAmount(li);
            BigDecimal vat = vatAmount(untaxed, li);
            m.put("untaxedAmount", formatAmount(untaxed, maxDisplayDecimals));
            m.put("vatAmount", formatAmount(vat, maxDisplayDecimals));
            m.put("amountWithVat", formatAmount(untaxed.add(vat), maxDisplayDecimals));
            return m;
        }).toList();
    }

    /** The export treats the already discounted line amount as the untaxed base. */
    private static BigDecimal lineAmount(QuotationDTO.LineItemDTO li) {
        if (li == null) return BigDecimal.ZERO;
        if (li.lineTotalAmount != null) return li.lineTotalAmount;
        return li.subtotal != null ? li.subtotal : BigDecimal.ZERO;
    }

    private static BigDecimal vatAmount(BigDecimal untaxed, QuotationDTO.LineItemDTO li) {
        if (untaxed == null || li == null) return BigDecimal.ZERO;
        BigDecimal ratio = li.vatRateRatio;
        if (ratio == null && li.vatRate != null) ratio = li.vatRate.movePointLeft(2);
        return ratio == null ? BigDecimal.ZERO : untaxed.multiply(ratio);
    }

    private static String formatRate(BigDecimal v) {
        return v == null ? "—" : NumberFormatUtil.format(v, null, true) + "%";
    }

    /**
     * 格式化金额字符串：task-0801 B6 起改用 {@link NumberFormatUtil#format} 统一口径
     * （至多 {@link com.cpq.common.PrecisionPolicy#DISPLAY_SCALE} 位、HALF_UP、去尾零），
     * 与卡片/API/Excel 显示同一套精度契约；null 返回 "0"（原固定 2 位 "0.00" 已废弃）。
     */
    private static String formatAmount(BigDecimal v) {
        return formatAmount(v, PrecisionPolicy.DISPLAY_SCALE);
    }

    private static String formatAmount(BigDecimal v, int maxDisplayDecimals) {
        return v == null ? "0" : NumberFormatUtil.format(v, null, true, maxDisplayDecimals);
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
        public final String vatTotal;
        public final String taxedTotal;

        public QuotationTemplateData(QuotationDTO q, String statusLabel) {
            this(q, statusLabel, PrecisionPolicy.DISPLAY_SCALE);
        }

        public QuotationTemplateData(QuotationDTO q, String statusLabel, int maxDisplayDecimals) {
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
            // Display precision is shared by UI/API/export: at most 9 digits, HALF_UP, trim zeros.
            this.originalAmount = formatAmount(q.originalAmount, maxDisplayDecimals);
            this.finalDiscountRate = q.finalDiscountRate != null ? formatAmount(q.finalDiscountRate) : "100";
            this.totalAmount = formatAmount(q.totalAmount, maxDisplayDecimals);
            BigDecimal vat = BigDecimal.ZERO;
            BigDecimal taxed = BigDecimal.ZERO;
            if (q.lineItems != null) {
                for (QuotationDTO.LineItemDTO li : q.lineItems) {
                    BigDecimal untaxed = lineAmount(li);
                    BigDecimal lineVat = vatAmount(untaxed, li);
                    vat = vat.add(lineVat);
                    taxed = taxed.add(untaxed).add(lineVat);
                }
            }
            this.vatTotal = formatAmount(vat, maxDisplayDecimals);
            this.taxedTotal = formatAmount(taxed, maxDisplayDecimals);
        }

        private static String nvl(String s) { return s != null ? s : ""; }
    }
}
