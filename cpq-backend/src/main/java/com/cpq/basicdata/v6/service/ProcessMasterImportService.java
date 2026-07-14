package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.dto.ProcessMasterImportReportDTO;
import com.cpq.basicdata.v6.dto.ProcessMasterImportReportDTO.SkippedRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 工序主数据批量导入服务（task-0712 · childtask-1 · B1）。
 *
 * <p><b>方案 B 纪律</b>：本服务只写 {@code process_master}，不与核价导入 10 个 P-handler 有任何耦合；
 * 核价导入落 unit_price/capacity/... 时读到的 {@code process_no} 与本表若不相交，只影响维护页「工序名」
 * join 结果（null），不阻断核价导入本身。
 *
 * <p>写入语义（对齐需求 §2 三个已确认的小默认）：
 * <ol>
 *   <li>{@code ON CONFLICT (process_no) DO UPDATE}——{@code process_name} 直接覆盖；选填列
 *       {@code COALESCE(新值, 原值)}，模板留空不清已有值；{@code created_*} 仅新建时写。</li>
 *   <li>同一 xlsx 内同 {@code process_no} 多行 → 首行胜出，其余记 skipped。</li>
 *   <li>{@code process_no} / {@code process_name} 为空 → 该行跳过，不阻断整批（不报 400）。</li>
 * </ol>
 * 400 仅用于"文件本身不可用"（非 xlsx / 缺表头必需列 / 空文件），对齐 {@code MaterialRecipeImportService} 惯例。
 */
@ApplicationScoped
public class ProcessMasterImportService {

    /** 首选 sheet 名；未命中则退回第一个 sheet。 */
    private static final String PREFERRED_SHEET = "工序";

    private static final String COL_NO = "工序编号";
    private static final String COL_NAME = "工序名称";
    private static final String COL_CATEGORY = "工序类别";
    private static final String COL_OUTSOURCE = "是否外协";
    private static final String COL_CURRENCY = "标准币种";
    private static final String COL_UNIT = "标准单位";
    private static final String COL_DEFECT_RATE = "默认不良率";

    @Inject
    EntityManager em;

    // ──────────────────────────────── 导入 ────────────────────────────────

    @Transactional
    public ProcessMasterImportReportDTO importProcesses(byte[] xlsxBytes, UUID operatorId) {
        long t0 = System.nanoTime();
        ProcessMasterImportReportDTO report = new ProcessMasterImportReportDTO();
        if (xlsxBytes == null || xlsxBytes.length == 0) {
            throw new IllegalArgumentException("上传文件为空");
        }

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsxBytes))) {
            Sheet sheet = wb.getSheet(PREFERRED_SHEET);
            if (sheet == null) sheet = wb.getSheetAt(0);
            if (sheet == null) throw new IllegalArgumentException("工作簿不含任何 sheet");

            Row header = sheet.getRow(0);
            if (header == null) throw new IllegalArgumentException("首行表头为空");
            java.util.Map<String, Integer> colIdx = new LinkedHashMap<>();
            for (Cell c : header) {
                String h = cellStr(c);
                if (!h.isBlank()) colIdx.putIfAbsent(h, c.getColumnIndex());
            }
            Integer idxNo = colIdx.get(COL_NO);
            Integer idxName = colIdx.get(COL_NAME);
            if (idxNo == null || idxName == null) {
                throw new IllegalArgumentException("表头缺少必需列: " + COL_NO + "/" + COL_NAME);
            }
            Integer idxCategory = colIdx.get(COL_CATEGORY);
            Integer idxOutsource = colIdx.get(COL_OUTSOURCE);
            Integer idxCurrency = colIdx.get(COL_CURRENCY);
            Integer idxUnit = colIdx.get(COL_UNIT);
            Integer idxDefectRate = colIdx.get(COL_DEFECT_RATE);

            // 解析 + 去重（同码首行胜出）
            LinkedHashMap<String, Row2> byNo = new LinkedHashMap<>();
            int lastRow = sheet.getLastRowNum();
            for (int r = 1; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String no = cellStr(row.getCell(idxNo));
                String name = cellStr(row.getCell(idxName));
                boolean rowBlank = no.isBlank() && name.isBlank()
                    && cellStr(cellOrNull(row, idxCategory)).isBlank()
                    && cellStr(cellOrNull(row, idxOutsource)).isBlank()
                    && cellStr(cellOrNull(row, idxCurrency)).isBlank()
                    && cellStr(cellOrNull(row, idxUnit)).isBlank()
                    && cellStr(cellOrNull(row, idxDefectRate)).isBlank();
                if (rowBlank) continue;   // 空行不计
                report.totalRows++;
                int excelRow = r + 1; // 1-based

                if (no.isBlank()) {
                    report.skipped.add(new SkippedRow(excelRow, "工序编号为空", raw(no, name)));
                    continue;
                }
                if (name.isBlank()) {
                    report.skipped.add(new SkippedRow(excelRow, "工序名称为空", raw(no, name)));
                    continue;
                }
                if (byNo.containsKey(no)) {
                    report.skipped.add(new SkippedRow(excelRow, "重复工序编号，已取首行", raw(no, name)));
                    continue;
                }

                Row2 r2 = new Row2();
                r2.processNo = no;
                r2.processName = name;
                r2.category = cellStr(cellOrNull(row, idxCategory));
                r2.outsource = parseBool(cellStr(cellOrNull(row, idxOutsource)));
                r2.currency = cellStr(cellOrNull(row, idxCurrency));
                r2.unit = cellStr(cellOrNull(row, idxUnit));
                r2.defectRate = parseDecimal(cellStr(cellOrNull(row, idxDefectRate)));
                byNo.put(no, r2);
            }

            if (!byNo.isEmpty()) {
                int[] counts = upsert(new ArrayList<>(byNo.values()), operatorId);
                report.insertedCount = counts[0];
                report.updatedCount = counts[1];
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("工序主数据解析失败: " + e.getMessage(), e);
        }

        report.skippedRowCount = report.skipped.size();
        report.durationMs = (System.nanoTime() - t0) / 1_000_000;
        return report;
    }

    /** 批量 upsert（一次原生 SQL，禁止逐行连库）。返回 [insertedCount, updatedCount]。 */
    private int[] upsert(List<Row2> rows, UUID operatorId) {
        // 先取已存在集合，据此分 inserted/updated（ON CONFLICT 前置判断，零 N+1）
        List<String> nos = new ArrayList<>(rows.size());
        for (Row2 r : rows) nos.add(r.processNo);
        @SuppressWarnings("unchecked")
        List<String> existRaw = em.createNativeQuery("SELECT process_no FROM process_master WHERE process_no IN (:nos)")
            .setParameter("nos", nos)
            .getResultList();
        Set<String> existing = new HashSet<>(existRaw);

        StringBuilder sb = new StringBuilder(
            "INSERT INTO process_master " +
            "(id, process_no, process_name, process_category, is_outsource, " +
            " standard_currency, standard_unit, default_defect_rate, " +
            " created_at, updated_at, created_by, updated_by) VALUES ");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("(gen_random_uuid(), :no").append(i).append(", :name").append(i)
              .append(", :cat").append(i).append(", :out").append(i)
              .append(", :cur").append(i).append(", :unit").append(i)
              .append(", :rate").append(i)
              .append(", NOW(), NOW(), :ub").append(i).append(", :ub2_").append(i).append(")");
        }
        sb.append(" ON CONFLICT (process_no) DO UPDATE SET ")
          .append("process_name        = EXCLUDED.process_name, ")
          .append("process_category    = COALESCE(EXCLUDED.process_category, process_master.process_category), ")
          .append("is_outsource        = COALESCE(EXCLUDED.is_outsource, process_master.is_outsource), ")
          .append("standard_currency   = COALESCE(EXCLUDED.standard_currency, process_master.standard_currency), ")
          .append("standard_unit       = COALESCE(EXCLUDED.standard_unit, process_master.standard_unit), ")
          .append("default_defect_rate = COALESCE(EXCLUDED.default_defect_rate, process_master.default_defect_rate), ")
          .append("updated_by          = EXCLUDED.updated_by, ")
          .append("updated_at          = NOW()");

        Query q = em.createNativeQuery(sb.toString());
        for (int i = 0; i < rows.size(); i++) {
            Row2 r = rows.get(i);
            q.setParameter("no" + i, r.processNo);
            q.setParameter("name" + i, r.processName);
            q.setParameter("cat" + i, blankToNull(r.category));
            q.setParameter("out" + i, r.outsource);
            q.setParameter("cur" + i, blankToNull(r.currency));
            q.setParameter("unit" + i, blankToNull(r.unit));
            q.setParameter("rate" + i, r.defectRate);
            // created_by 仅新建生效（ON CONFLICT 不动 created_*），updated_by 两种情形都用
            q.setParameter("ub" + i, operatorId);      // created_by
            q.setParameter("ub2_" + i, operatorId);    // updated_by
        }
        q.executeUpdate();

        int updated = 0, inserted = 0;
        for (String no : nos) {
            if (existing.contains(no)) updated++; else inserted++;
        }
        return new int[]{inserted, updated};
    }

    // ──────────────────────────────── 干净模板 ────────────────────────────────

    /** 生成干净导入模板：单 sheet「工序」，表头 7 列（前 2 列必填）+ 示例行。 */
    public byte[] generateTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet(PREFERRED_SHEET);
            Row h = s.createRow(0);
            String[] cols = {COL_NO, COL_NAME, COL_CATEGORY, COL_OUTSOURCE, COL_CURRENCY, COL_UNIT, COL_DEFECT_RATE};
            for (int i = 0; i < cols.length; i++) h.createCell(i).setCellValue(cols[i]);

            Row example = s.createRow(1);
            example.createCell(0).setCellValue("Z002");
            example.createCell(1).setCellValue("铣割");
            example.createCell(2).setCellValue("制造");
            example.createCell(3).setCellValue("否");
            example.createCell(4).setCellValue("CNY");
            example.createCell(5).setCellValue("PCS");
            example.createCell(6).setCellValue(0.02);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("生成导入模板失败: " + e.getMessage(), e);
        }
    }

    // ──────────────────────────────── helpers ────────────────────────────────

    private Cell cellOrNull(Row row, Integer idx) {
        return idx == null ? null : row.getCell(idx);
    }

    private String raw(String no, String name) {
        return no + "," + name;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private Boolean parseBool(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        if (v.equals("是") || v.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (v.equals("否") || v.equalsIgnoreCase("false")) return Boolean.FALSE;
        return null;   // 无法识别的选填值忽略，不阻断导入
    }

    private BigDecimal parseDecimal(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new BigDecimal(s.trim()); } catch (Exception e) { return null; }
    }

    /** 单元格取字符串：STRING 原样、NUMERIC 无科学计数/无多余小数（保 code 前导零由文本单元格保证）。 */
    private String cellStr(Cell c) {
        if (c == null) return "";
        switch (c.getCellType()) {
            case STRING:
                return c.getStringCellValue().trim();
            case NUMERIC:
                return numToStr(c.getNumericCellValue());
            case BOOLEAN:
                return c.getBooleanCellValue() ? "是" : "否";
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

    /** 单行解析中间态。 */
    private static class Row2 {
        String processNo;
        String processName;
        String category;
        Boolean outsource;
        String currency;
        String unit;
        BigDecimal defectRate;
    }
}
