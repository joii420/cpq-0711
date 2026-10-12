package com.cpq.configure.service;

import com.cpq.configure.dto.ElementImportReportDTO;
import com.cpq.configure.exception.MaterialRecipeApiException;
import com.cpq.configure.rules.MaterialRecipeNumbering;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 元素主数据的模板、批量导入与导出；材质导入不复用本服务以保持边界。 */
@ApplicationScoped
public class ElementBulkService {
    public static final List<String> HEADER = List.of("元素符号", "中文名称");
    private static final String SHEET_NAME = "元素";

    @Inject
    EntityManager em;

    public byte[] generateTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet(SHEET_NAME);
            Row header = s.createRow(0);
            for (int i = 0; i < HEADER.size(); i++) header.createCell(i).setCellValue(HEADER.get(i));
            s.createRow(1).createCell(0).setCellValue("Ag");
            s.getRow(1).createCell(1).setCellValue("银");
            for (int i = 0; i < HEADER.size(); i++) s.setColumnWidth(i, 20 * 256);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("生成元素导入模板失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public ElementImportReportDTO importElements(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw MaterialRecipeApiException.badRequest("ELEMENT_IMPORT_FILE_EMPTY", "上传文件为空");
        LinkedHashMap<String, String> incoming = new LinkedHashMap<>();
        int total = 0;
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (wb.getNumberOfSheets() == 0) throw invalid("元素模板必须包含一个工作表");
            Sheet s = wb.getSheetAt(0);
            Row h = s.getRow(0);
            if (h == null || !HEADER.get(0).equals(cell(h.getCell(0))) || !HEADER.get(1).equals(cell(h.getCell(1)))) {
                throw invalid("元素模板表头必须为：元素符号、中文名称");
            }
            List<Map<String, Object>> conflicts = new ArrayList<>();
            Map<String, Integer> firstRows = new LinkedHashMap<>();
            for (int i = 1; i <= s.getLastRowNum(); i++) {
                Row r = s.getRow(i);
                if (r == null) continue;
                String code = cell(r.getCell(0));
                String name = cell(r.getCell(1));
                if (code.isBlank() && name.isBlank()) continue;
                total++;
                if (code.isBlank() || name.isBlank()) throw invalid("第" + (i + 1) + "行元素符号和中文名称不能为空");
                String prior = incoming.putIfAbsent(code, name);
                if (prior == null) {
                    firstRows.put(code, i + 1);
                } else if (!prior.equals(name)) {
                    conflicts.add(Map.of("symbol", code, "rows", List.of(firstRows.get(code), i + 1), "names", List.of(prior, name)));
                }
            }
            if (!conflicts.isEmpty()) {
                throw new MaterialRecipeApiException(400, "ELEMENT_IMPORT_CONFLICT", "同一元素符号存在不同中文名称", Map.of("conflicts", conflicts));
            }
        } catch (MaterialRecipeApiException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("元素文件解析失败: " + e.getMessage());
        }

        ElementImportReportDTO report = new ElementImportReportDTO();
        report.totalRows = total;
        if (incoming.isEmpty()) return report;

        @SuppressWarnings("unchecked")
        List<Object[]> existingRows = em.createNativeQuery(
            "SELECT element_no, element_code, element_name, status FROM element WHERE element_code IN (:codes)")
            .setParameter("codes", incoming.keySet()).getResultList();
        Map<String, Object[]> existing = new LinkedHashMap<>();
        for (Object[] row : existingRows) existing.put(String.valueOf(row[1]), row);

        List<String> updates = new ArrayList<>();
        List<String> updateNames = new ArrayList<>();
        List<String> newCodes = new ArrayList<>();
        for (Map.Entry<String, String> e : incoming.entrySet()) {
            Object[] old = existing.get(e.getKey());
            if (old == null) newCodes.add(e.getKey());
            else if (!e.getValue().equals(String.valueOf(old[2]))) {
                updates.add(e.getKey());
                updateNames.add(e.getValue());
            } else report.unchangedCount++;
        }
        if (!updates.isEmpty()) {
            StringBuilder sql = new StringBuilder("UPDATE element SET element_name = CASE element_code ");
            for (int i = 0; i < updates.size(); i++) sql.append("WHEN :c").append(i).append(" THEN :n").append(i).append(' ');
            sql.append("ELSE element_name END, updated_at = now() WHERE element_code IN (:codes)");
            var q = em.createNativeQuery(sql.toString());
            for (int i = 0; i < updates.size(); i++) { q.setParameter("c" + i, updates.get(i)); q.setParameter("n" + i, updateNames.get(i)); }
            q.setParameter("codes", updates);
            q.executeUpdate();
            report.updatedNameCount = updates.size();
        }
        if (!newCodes.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<String> nos = em.createNativeQuery("SELECT element_no FROM element WHERE element_no ~ '^[0-9]+$'").getResultList();
            String next = MaterialRecipeNumbering.nextElementNo(nos);
            StringBuilder sql = new StringBuilder("INSERT INTO element (element_no, element_code, element_name, status, created_at, updated_at) VALUES ");
            for (int i = 0; i < newCodes.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("(:o").append(i).append(", :c").append(i).append(", :n").append(i).append(", 'ACTIVE', now(), now())");
            }
            sql.append(" ON CONFLICT (element_code) DO NOTHING");
            var q = em.createNativeQuery(sql.toString());
            for (int i = 0; i < newCodes.size(); i++) {
                q.setParameter("o" + i, next);
                q.setParameter("c" + i, newCodes.get(i));
                q.setParameter("n" + i, incoming.get(newCodes.get(i)));
                next = MaterialRecipeNumbering.nextElementNo(List.of(next));
            }
            q.executeUpdate();
            report.createdCount = newCodes.size();
        }
        return report;
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public byte[] exportElements() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("SELECT element_no, element_code, element_name, status FROM element ORDER BY (status = 'ACTIVE') DESC, element_no").getResultList();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet(SHEET_NAME);
            for (int i = 0; i < HEADER.size() + 2; i++) s.setColumnWidth(i, 20 * 256);
            String[] headers = {"元素编号", "元素符号", "中文名称", "状态"};
            Row header = s.createRow(0);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            for (int i = 0; i < rows.size(); i++) {
                Object[] row = rows.get(i); Row r = s.createRow(i + 1);
                for (int j = 0; j < 4; j++) r.createCell(j).setCellValue(row[j] == null ? "" : String.valueOf(row[j]));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(); wb.write(out); return out.toByteArray();
        } catch (Exception e) { throw new RuntimeException("导出元素失败: " + e.getMessage(), e); }
    }

    private MaterialRecipeApiException invalid(String message) {
        return MaterialRecipeApiException.badRequest("ELEMENT_IMPORT_INVALID", message);
    }
    private String cell(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue().trim();
            case NUMERIC -> new BigDecimal(Double.toString(c.getNumericCellValue())).stripTrailingZeros().toPlainString();
            default -> "";
        };
    }
}
