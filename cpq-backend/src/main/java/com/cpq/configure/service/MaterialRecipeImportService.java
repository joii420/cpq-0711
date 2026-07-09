package com.cpq.configure.service;

import com.cpq.configure.dto.MaterialImportReportDTO;
import com.cpq.configure.dto.MaterialImportReportDTO.SkippedRow;
import com.cpq.configure.entity.MaterialRecipe;
import com.cpq.configure.entity.MaterialRecipeElement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 材质库导入服务（task-0708 · B4）。
 *
 * <p><b>锁定语义</b>（backtask.md「零」8 决策）：
 * <ol>
 *   <li>只读 {@code 材质编号} + {@code 材质对应元素} 两 sheet；材质编号以 {@code 材质编号} sheet 为权威映射。</li>
 *   <li>行级校验（材质有编号 / 元素名非纯数字 / 含量∈(0,1]）不过跳过 + 记报告，不中断整单。</li>
 *   <li>材质级 Σ含量≈1（容差 0.02）不过 → 整材质跳过。含量 ×100 存 100 制（下游"和=100"口径）。</li>
 *   <li>按 {@code code}=材质编号 Upsert（文件内覆盖、文件外不动）；元素明细整体重灌（覆盖语义）。</li>
 *   <li>同步元素主表 {@code element}（按符号 upsert，中文名不被符号回退覆盖）。</li>
 *   <li>全批量落库，禁止逐行连库（Hibernate JDBC 批 + tuple-IN 一次性 upsert/delete）。</li>
 * </ol>
 */
@ApplicationScoped
public class MaterialRecipeImportService {

    static final String SHEET_CODE = "材质编号";
    static final String SHEET_ELEM = "材质对应元素";

    private static final BigDecimal SUM_TOLERANCE = new BigDecimal("0.02");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Pattern PURE_NUMBER = Pattern.compile("^\\d+$");

    /** 元素符号→中文字典（与 V316 seed 同源；导入遇未知符号回退=符号）。 */
    private static final Map<String, String> DICT = Map.ofEntries(
        Map.entry("Ag", "银"), Map.entry("Cu", "铜"), Map.entry("Ni", "镍"),
        Map.entry("Al", "铝"), Map.entry("Fe", "铁"), Map.entry("Sn", "锡"),
        Map.entry("Zn", "锌"), Map.entry("Cr", "铬"), Map.entry("Mn", "锰"),
        Map.entry("Si", "硅"), Map.entry("P", "磷"), Map.entry("C", "碳"),
        Map.entry("Be", "铍"), Map.entry("Cd", "镉"), Map.entry("Ce", "铈"),
        Map.entry("In", "铟"), Map.entry("Ir", "铱"), Map.entry("Pt", "铂"),
        Map.entry("Pd", "钯"), Map.entry("W", "钨"), Map.entry("Au", "金"),
        Map.entry("SnO2", "二氧化锡"), Map.entry("ZnO", "氧化锌"), Map.entry("CdO", "氧化镉"),
        Map.entry("WC", "碳化钨"), Map.entry("H70", "黄铜H70"), Map.entry("DC04", "冷轧钢DC04"),
        Map.entry("Ni36", "铁镍合金Ni36"), Map.entry("Ni42", "铁镍合金Ni42"), Map.entry("不锈钢", "不锈钢"));

    @Inject
    EntityManager em;

    // ──────────────────────────────── 导入 ────────────────────────────────

    @Transactional
    public MaterialImportReportDTO importLibrary(byte[] xlsxBytes) {
        long t0 = System.nanoTime();
        MaterialImportReportDTO report = new MaterialImportReportDTO();
        if (xlsxBytes == null || xlsxBytes.length == 0) {
            throw new IllegalArgumentException("上传文件为空");
        }

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsxBytes))) {
            Sheet codeSheet = wb.getSheet(SHEET_CODE);
            Sheet elemSheet = wb.getSheet(SHEET_ELEM);
            if (codeSheet == null) throw new IllegalArgumentException("模板缺少必需 sheet: " + SHEET_CODE);
            if (elemSheet == null) throw new IllegalArgumentException("模板缺少必需 sheet: " + SHEET_ELEM);

            // 1) 材质编号 → 权威映射（首现优先）
            Map<String, String> codeByMaterial = new HashMap<>();
            for (int r = 1; r <= codeSheet.getLastRowNum(); r++) {
                Row row = codeSheet.getRow(r);
                if (row == null) continue;
                String mat = cellStr(row.getCell(0));
                String code = cellStr(row.getCell(1));
                if (mat.isBlank() || code.isBlank()) continue;
                codeByMaterial.putIfAbsent(mat, code);
            }

            // 2) 逐行解析 材质对应元素 + 行级校验，通过的进材质组（组内元素符号去重末值胜）
            LinkedHashMap<String, MatGroup> groups = new LinkedHashMap<>();
            for (int r = 1; r <= elemSheet.getLastRowNum(); r++) {
                Row row = elemSheet.getRow(r);
                if (row == null) continue;
                String mat = cellStr(row.getCell(0));
                String elemName = cellStr(row.getCell(2));
                String contentRaw = cellStr(row.getCell(3));
                String elemNo = cellStr(row.getCell(4));
                if (mat.isBlank() && elemName.isBlank() && contentRaw.isBlank()) continue; // 空行不计
                report.totalRows++;
                int excelRow = r + 1; // 1-based

                String code = mat.isBlank() ? null : codeByMaterial.get(mat);
                if (code == null) {
                    report.skipped.add(new SkippedRow(SHEET_ELEM, excelRow, "材质无对应编号", mat));
                    continue;
                }
                if (elemName.isBlank()) {
                    report.skipped.add(new SkippedRow(SHEET_ELEM, excelRow, "元素名称为空", ""));
                    continue;
                }
                if (PURE_NUMBER.matcher(elemName).matches()) {
                    report.skipped.add(new SkippedRow(SHEET_ELEM, excelRow, "元素名称为纯数字(疑料号误填)", elemName));
                    continue;
                }
                BigDecimal content = parseContent(contentRaw);
                if (content == null
                        || content.compareTo(BigDecimal.ZERO) <= 0
                        || content.compareTo(BigDecimal.ONE) > 0) {
                    report.skipped.add(new SkippedRow(SHEET_ELEM, excelRow, "含量非法", contentRaw));
                    continue;
                }
                groups.computeIfAbsent(code, k -> new MatGroup(code, mat))
                      .putElement(elemName, content, elemNo);
            }

            // 3) 材质级 Σ 校验，收集通过材质
            List<MatGroup> passed = new ArrayList<>();
            for (MatGroup g : groups.values()) {
                if (g.elements.isEmpty()) continue;             // 全元素无效（per-row 已记）
                BigDecimal sum = g.sum();
                if (sum.subtract(BigDecimal.ONE).abs().compareTo(SUM_TOLERANCE) > 0) {
                    report.skipped.add(new SkippedRow(SHEET_ELEM, null,
                        "含量合计≠1(实际" + sum.setScale(2, RoundingMode.HALF_UP) + ")", "code=" + g.code));
                    continue;
                }
                passed.add(g);
            }

            // 4) 落库（批量）
            if (!passed.isEmpty()) {
                report.elementMasterUpserted = syncElementMaster(passed);
                int[] counts = upsertMaterials(passed);
                report.materialsUpserted = counts[0];
                report.elementRowsInserted = counts[1];
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("材质库解析失败: " + e.getMessage(), e);
        }

        report.skippedRowCount = report.skipped.size();
        report.durationMs = (System.nanoTime() - t0) / 1_000_000;
        return report;
    }

    /** 元素主表批量 upsert：一条 tuple-IN INSERT…ON CONFLICT。返回受影响行数（新增+更新）。 */
    private int syncElementMaster(List<MatGroup> passed) {
        LinkedHashMap<String, String> symNo = new LinkedHashMap<>();   // symbol -> elementNo（首个非空胜）
        for (MatGroup g : passed) {
            for (String sym : g.elements.keySet()) {
                String no = g.elementNos.get(sym);
                symNo.merge(sym, no, (old, cur) -> old != null ? old : cur);
                symNo.putIfAbsent(sym, no);
            }
        }
        if (symNo.isEmpty()) return 0;

        List<String> syms = new ArrayList<>(symNo.keySet());
        StringBuilder sb = new StringBuilder("INSERT INTO element (element_code, element_name, element_no) VALUES ");
        for (int i = 0; i < syms.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("(:c").append(i).append(", :n").append(i).append(", CAST(:o").append(i).append(" AS varchar))");
        }
        sb.append(" ON CONFLICT (element_code) DO UPDATE SET ")
          .append("element_no = COALESCE(EXCLUDED.element_no, element.element_no), ")
          // 仅当原名是"符号占位"(name=code)才回填中文，不覆盖已有中文名
          .append("element_name = CASE WHEN element.element_name = element.element_code ")
          .append("THEN EXCLUDED.element_name ELSE element.element_name END, ")
          .append("updated_at = NOW()");

        Query q = em.createNativeQuery(sb.toString());
        for (int i = 0; i < syms.size(); i++) {
            String sym = syms.get(i);
            q.setParameter("c" + i, sym);
            q.setParameter("n" + i, DICT.getOrDefault(sym, sym));
            q.setParameter("o" + i, symNo.get(sym));
        }
        return q.executeUpdate();
    }

    /** material_recipe upsert(按 code) + 元素明细整体重灌。返回 [materialsUpserted, elementRowsInserted]。 */
    private int[] upsertMaterials(List<MatGroup> passed) {
        OffsetDateTime now = OffsetDateTime.now();

        List<String> codes = passed.stream().map(g -> g.code).collect(Collectors.toList());
        Map<String, MaterialRecipe> existing = MaterialRecipe.<MaterialRecipe>find("code in ?1", codes).list()
            .stream().collect(Collectors.toMap(r -> r.code, r -> r, (a, b) -> a));

        List<UUID> recipeIds = new ArrayList<>(passed.size());
        for (MatGroup g : passed) {
            MaterialRecipe r = existing.get(g.code);
            boolean isNew = (r == null);
            if (isNew) {
                r = new MaterialRecipe();
                r.code = g.code;
                r.createdAt = now;
                r.sortOrder = parseSort(g.code);
            }
            // 所有字段设置完再 persist 一次：新建=insert（避免字段未就绪的快照）、已存在=脏更新
            r.symbol = g.symbol;
            r.name = null;                 // 决策#2：导入置 NULL（UI 隐藏、DB 列保留）
            r.specLabel = null;
            r.recipeType = "locked";       // 决策#7：默认标准锁定
            r.status = "ACTIVE";
            r.updatedAt = now;
            r.persist();                   // UUID 预生成，r.id 立即可用
            recipeIds.add(r.id);
        }
        em.flush();                        // 材质先落库，再删旧元素、灌新元素

        // 覆盖语义：本次涉及的 recipe 旧元素整体删除
        em.createNativeQuery("DELETE FROM material_recipe_element WHERE recipe_id IN (:ids)")
          .setParameter("ids", recipeIds)
          .executeUpdate();

        int elementRows = 0;
        int idx = 0;
        for (MatGroup g : passed) {
            UUID rid = recipeIds.get(idx++);
            int seq = 1;
            for (Map.Entry<String, BigDecimal> e : g.elements.entrySet()) {
                MaterialRecipeElement el = new MaterialRecipeElement();
                el.recipeId = rid;
                el.elementCode = e.getKey();
                el.elementName = DICT.getOrDefault(e.getKey(), e.getKey());
                el.defaultPct = e.getValue().multiply(HUNDRED);   // ×100 归一
                el.minPct = null;
                el.maxPct = null;
                el.isLocked = true;
                el.sortOrder = seq++;
                el.createdAt = now;
                el.persist();                                     // Hibernate JDBC batch（batch-size=100）
                elementRows++;
            }
        }
        return new int[]{recipeIds.size(), elementRows};
    }

    // ──────────────────────────────── 干净模板 ────────────────────────────────

    /** 生成两 sheet 空模板（材质编号 / 材质对应元素）+ 示例行 + 含量批注。 */
    public byte[] generateTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet cs = wb.createSheet(SHEET_CODE);
            header(cs, "材质", "材质编号");
            exampleRow(cs, "AgC3", "00002");

            Sheet es = wb.createSheet(SHEET_ELEM);
            header(es, "材质", "材质编号", "元素名称", "含量", "元素编号");
            elemExampleRow(es, 1, "AgC3", "00002", "Ag", 0.97, "10001");
            elemExampleRow(es, 2, "AgC3", "00002", "C", 0.03, "10012");

            // 含量列表头批注
            CreationHelper factory = wb.getCreationHelper();
            Drawing<?> drawing = es.createDrawingPatriarch();
            ClientAnchor anchor = factory.createClientAnchor();
            anchor.setCol1(3); anchor.setCol2(7); anchor.setRow1(0); anchor.setRow2(5);
            Comment comment = drawing.createCellComment(anchor);
            comment.setString(factory.createRichTextString(
                "含量填 0–1 小数，同材质相加=1；仅读取【材质编号】【材质对应元素】两个 sheet。"));
            es.getRow(0).getCell(3).setCellComment(comment);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("生成导入模板失败: " + e.getMessage(), e);
        }
    }

    private void header(Sheet s, String... cols) {
        Row h = s.createRow(0);
        for (int i = 0; i < cols.length; i++) h.createCell(i).setCellValue(cols[i]);
    }

    private void exampleRow(Sheet s, String mat, String code) {
        Row r = s.createRow(1);
        r.createCell(0).setCellValue(mat);
        r.createCell(1).setCellValue(code);
    }

    private void elemExampleRow(Sheet s, int rowIdx, String mat, String code, String elem, double content, String no) {
        Row r = s.createRow(rowIdx);
        r.createCell(0).setCellValue(mat);
        r.createCell(1).setCellValue(code);
        r.createCell(2).setCellValue(elem);
        r.createCell(3).setCellValue(content);
        r.createCell(4).setCellValue(no);
    }

    // ──────────────────────────────── helpers ────────────────────────────────

    private int parseSort(String code) {
        try { return Integer.parseInt(code.trim()); } catch (Exception e) { return 0; }
    }

    private BigDecimal parseContent(String s) {
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
                return Boolean.toString(c.getBooleanCellValue());
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

    /** 材质组：code + 材质名(symbol) + 组内元素（符号去重末值胜，保插入序）。 */
    private static class MatGroup {
        final String code;
        final String symbol;
        final LinkedHashMap<String, BigDecimal> elements = new LinkedHashMap<>();
        final Map<String, String> elementNos = new HashMap<>();

        MatGroup(String code, String symbol) {
            this.code = code;
            this.symbol = symbol;
        }

        void putElement(String sym, BigDecimal content, String no) {
            elements.put(sym, content);                      // 末值胜，保首现位置
            if (no != null && !no.isBlank()) elementNos.put(sym, no);
        }

        BigDecimal sum() {
            BigDecimal s = BigDecimal.ZERO;
            for (BigDecimal v : elements.values()) s = s.add(v);
            return s;
        }
    }
}
