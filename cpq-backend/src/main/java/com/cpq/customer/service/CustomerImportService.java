package com.cpq.customer.service;

import com.cpq.basicdata.entity.ProductCategory;
import com.cpq.common.exception.BusinessException;
import com.cpq.customer.dto.CustomerImportReportDTO;
import com.cpq.customer.entity.Customer;
import com.cpq.customer.entity.CustomerContact;
import com.cpq.customer.exception.CustomerImportException;
import com.cpq.industry.entity.Industry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.*;

@ApplicationScoped
public class CustomerImportService {
    private static final Logger LOG = Logger.getLogger(CustomerImportService.class);
    private static final Set<String> LEVELS = Set.of("DIAMOND", "VIP", "GOLD", "SILVER", "STANDARD");
    public static final List<String> HEADERS = List.of(
            "客户编号", "客户名称", "客户等级", "所属行业", "产品分类", "所属区域", "地址",
            "信用额度", "付款方式", "备注", "联系人姓名", "联系人职务", "联系人电话", "联系人邮箱", "联系人微信");

    @Inject EntityManager em;
    @Inject CustomerService customerService;

    public byte[] generateTemplate() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("客户");
            Row header = sheet.createRow(0);
            CellStyle style = workbook.createCellStyle();
            style.setAlignment(HorizontalAlignment.CENTER);
            for (int i = 0; i < HEADERS.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS.get(i));
                cell.setCellStyle(style);
                sheet.setColumnWidth(i, 18 * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException(500, "生成客户导入模板失败");
        }
    }

    /** Parse and validate first, then persist the entire file in this transaction. */
    @Transactional
    public CustomerImportReportDTO importCustomers(byte[] bytes) {
        List<ParsedRow> rows = parse(bytes);
        CustomerImportReportDTO report = new CustomerImportReportDTO();
        report.totalRows = rows.size();
        if (rows.isEmpty()) return report;

        // Every customer write path uses the same transaction-scoped advisory lock.
        customerService.lockCustomerWrites();
        Map<String, Customer> existingByCode = new HashMap<>();
        for (Customer c : Customer.<Customer>listAll()) {
            existingByCode.putIfAbsent(CustomerService.normalizeIdentity(c.code), c);
        }
        Set<String> seenCodes = new HashSet<>();
        Map<String, Integer> firstCodeRows = new HashMap<>();
        Set<String> reportedCodeDuplicates = new HashSet<>();
        List<CustomerImportReportDTO.ImportError> errors = new ArrayList<>();
        Map<String, UUID> categoryIds = categoryIds();
        Map<String, Industry> industries = industries();
        UUID defaultCategoryId = ProductCategory.requireDefaultId();

        for (ParsedRow row : rows) {
            validateRow(row, existingByCode, seenCodes, categoryIds, industries,
                    firstCodeRows, reportedCodeDuplicates, defaultCategoryId, errors);
        }
        if (!errors.isEmpty()) {
            throw new CustomerImportException(409, "客户导入校验失败，文件未写入", rows.size(), errors);
        }

        // Reserve generated codes only after all user-provided values have passed validation.
        Set<String> reservedCodes = new HashSet<>(existingByCode.keySet());
        reservedCodes.addAll(seenCodes);
        for (ParsedRow row : rows) {
            boolean generated = row.code.isEmpty();
            if (row.code.isEmpty()) {
                row.code = customerService.generateCodeFromReserved(reservedCodes);
            }
            String normalized = CustomerService.normalizeIdentity(row.code);
            // Hand-entered codes were already registered and checked in validateRow;
            // only newly generated values need a second defensive reservation check.
            if (generated && (!seenCodes.add(normalized) || existingByCode.containsKey(normalized))) {
                errors.add(error(row.rowNum, "客户编号", "客户编号已存在: " + row.code));
            }
        }
        if (!errors.isEmpty()) {
            throw new CustomerImportException(409, "客户导入校验失败，文件未写入", rows.size(), errors);
        }

        for (ParsedRow row : rows) {
            Customer customer = new Customer();
            customer.name = row.name;
            customer.code = row.code;
            customer.level = row.level;
            customer.industry = row.industryName;
            customer.industryCode = row.industryCode;
            customer.productCategoryId = row.productCategoryId;
            customer.region = row.region;
            customer.address = row.address;
            customer.creditLimit = row.creditLimit;
            customer.paymentMethod = row.paymentMethod;
            customer.remarks = row.remarks;
            customer.status = "ACTIVE";
            customer.persist();

            CustomerContact contact = new CustomerContact();
            contact.customerId = customer.id;
            contact.name = row.contactName;
            contact.role = row.contactRole;
            contact.phone = row.contactPhone;
            contact.email = row.contactEmail;
            contact.wechat = row.contactWechat;
            contact.isPrimary = true;
            contact.persist();
            report.createdCustomers++;
            report.createdContacts++;
        }
        LOG.infof("Imported customers rows=%d customers=%d contacts=%d", rows.size(),
                report.createdCustomers, report.createdContacts);
        return report;
    }

    private void validateRow(ParsedRow row, Map<String, Customer> existingByCode,
                             Set<String> seenCodes, Map<String, UUID> categoryIds,
                             Map<String, Industry> industries,
                             Map<String, Integer> firstCodeRows,
                             Set<String> reportedCodeDuplicates,
                             UUID defaultCategoryId,
                             List<CustomerImportReportDTO.ImportError> errors) {
        if (row.code.length() > 0 && !row.code.equals(row.code.trim()))
            errors.add(error(row.rowNum, "客户编号", "首尾不能有空格"));
        if (row.code.length() > 50) errors.add(error(row.rowNum, "客户编号", "长度不能超过50个字符"));
        if (!row.code.isEmpty()) {
            String normalized = CustomerService.normalizeIdentity(row.code);
            Integer firstRow = firstCodeRows.putIfAbsent(normalized, row.rowNum);
            if (firstRow != null) {
                if (reportedCodeDuplicates.add(normalized)) {
                    errors.add(error(firstRow, "客户编号", "文件内客户编号重复"));
                }
                errors.add(error(row.rowNum, "客户编号", "文件内客户编号重复"));
            } else {
                seenCodes.add(normalized);
            }
            if (existingByCode.containsKey(normalized)) errors.add(error(row.rowNum, "客户编号", "客户编号已存在"));
        }

        if (row.name.isBlank()) errors.add(error(row.rowNum, "客户名称", "不能为空"));
        else {
            if (!row.name.equals(row.name.trim())) errors.add(error(row.rowNum, "客户名称", "首尾不能有空格"));
            if (row.name.length() > 200) errors.add(error(row.rowNum, "客户名称", "长度不能超过200个字符"));
        }
        String normalizedLevel = row.level.toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(normalizedLevel))
            errors.add(error(row.rowNum, "客户等级", "客户等级不合法: " + row.level));
        else row.level = normalizedLevel;

        if (!row.industry.isBlank()) {
            Industry industry = industries.get(CustomerService.normalizeIdentity(row.industry));
            if (industry == null) errors.add(error(row.rowNum, "所属行业", "行业不存在: " + row.industry));
            else { row.industryName = industry.name; row.industryCode = industry.code; }
        }
        if (row.productCategory.isBlank()) {
            row.productCategoryId = defaultCategoryId;
        } else {
            row.productCategoryId = categoryIds.get(CustomerService.normalizeIdentity(row.productCategory));
            if (row.productCategoryId == null) errors.add(error(row.rowNum, "产品分类", "产品分类不存在: " + row.productCategory));
        }
        if (row.creditLimitRaw != null && !row.creditLimitRaw.isBlank()) {
            try { row.creditLimit = new BigDecimal(row.creditLimitRaw.trim()); }
            catch (NumberFormatException e) { errors.add(error(row.rowNum, "信用额度", "金额格式不合法")); }
        }
        if (row.contactName.isBlank()) errors.add(error(row.rowNum, "联系人姓名", "不能为空"));
        if (row.contactPhone.isBlank() || !row.contactPhone.matches("^\\d{11}$"))
            errors.add(error(row.rowNum, "联系人电话", "必须是11位数字"));
    }

    private Map<String, UUID> categoryIds() {
        Map<String, UUID> out = new HashMap<>();
        for (ProductCategory category : ProductCategory.<ProductCategory>listAll()) {
            if (!"ACTIVE".equalsIgnoreCase(category.status)) continue;
            out.putIfAbsent(CustomerService.normalizeIdentity(category.code), category.id);
            out.putIfAbsent(CustomerService.normalizeIdentity(category.name), category.id);
        }
        return out;
    }

    private Map<String, Industry> industries() {
        Map<String, Industry> out = new HashMap<>();
        for (Industry industry : Industry.<Industry>listAll()) {
            if (!"ACTIVE".equalsIgnoreCase(industry.status)) continue;
            out.putIfAbsent(CustomerService.normalizeIdentity(industry.code), industry);
            out.putIfAbsent(CustomerService.normalizeIdentity(industry.name), industry);
        }
        return out;
    }

    private List<ParsedRow> parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new BusinessException(400, "请上传 .xlsx 文件");
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (!(workbook instanceof XSSFWorkbook)) throw new BusinessException(400, "请上传 .xlsx 文件");
            if (workbook.getNumberOfSheets() == 0) throw new BusinessException(400, "表头不符合模板要求");
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null) throw new BusinessException(400, "表头不符合模板要求");
            for (int i = 0; i < HEADERS.size(); i++) {
                if (!HEADERS.get(i).equals(cell(header.getCell(i))))
                    throw new BusinessException(400, "表头不符合模板要求，请下载新模板");
            }
            for (int i = HEADERS.size(); i < header.getLastCellNum(); i++) {
                if (!cell(header.getCell(i)).isEmpty()) throw new BusinessException(400, "表头不符合模板要求，请下载新模板");
            }
            List<ParsedRow> rows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row source = sheet.getRow(i);
                if (source == null) continue;
                String[] values = new String[HEADERS.size()];
                boolean empty = true;
                for (int c = 0; c < values.length; c++) { values[c] = cell(source.getCell(c)); if (!values[c].isEmpty()) empty = false; }
                if (empty) continue;
                ParsedRow row = new ParsedRow(i + 1, values);
                rows.add(row);
            }
            return rows;
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException(400, "请上传有效的 .xlsx 文件"); }
    }

    private String cell(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue();
        if (cell.getCellType() == CellType.NUMERIC) return new BigDecimal(Double.toString(cell.getNumericCellValue())).stripTrailingZeros().toPlainString();
        if (cell.getCellType() == CellType.BOOLEAN) return Boolean.toString(cell.getBooleanCellValue());
        if (cell.getCellType() == CellType.FORMULA) {
            try { return cell.getStringCellValue(); } catch (Exception ignored) { try { return new BigDecimal(Double.toString(cell.getNumericCellValue())).stripTrailingZeros().toPlainString(); } catch (Exception ignored2) { return ""; } }
        }
        return "";
    }

    private CustomerImportReportDTO.ImportError error(int row, String column, String reason) {
        return new CustomerImportReportDTO.ImportError(row, column, reason);
    }

    private static class ParsedRow {
        int rowNum; String code, name, level, industry, productCategory, region, address, creditLimitRaw, paymentMethod, remarks;
        String contactName, contactRole, contactPhone, contactEmail, contactWechat;
        String industryName, industryCode; UUID productCategoryId; BigDecimal creditLimit;
        ParsedRow(int rowNum, String[] v) {
            this.rowNum = rowNum; code=v[0]; name=v[1]; level=v[2].isBlank()?"STANDARD":v[2]; industry=v[3]; productCategory=v[4]; region=v[5]; address=v[6]; creditLimitRaw=v[7]; paymentMethod=v[8]; remarks=v[9]; contactName=v[10]; contactRole=v[11]; contactPhone=v[12]; contactEmail=v[13]; contactWechat=v[14];
        }
    }
}
