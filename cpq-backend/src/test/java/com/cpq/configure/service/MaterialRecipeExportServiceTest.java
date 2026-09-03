package com.cpq.configure.service;

import com.cpq.configure.dto.MaterialImportReportDTO;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 材质库导出测试（task-260902 · B-1）。
 *
 * <p><b>本测试的重点不是「能生成文件」，而是「导出的文件能被导入端原样吃回去」</b>——
 * 也就是 AC-19（原样回导零新增）与 AC-20（加一组回导恰好 +1 组）这两条回环。
 * 表头位置或含量口径任一错了，这两条就必然红。
 *
 * <p>造数一律用本任务专属前缀 {@code T260902测试材质}；{@code @TestTransaction}
 * 让每个用例独立事务并<b>回滚</b>，不在共享开发库留残留。
 * 🚫 没有任何清库 / 无 WHERE 的删除语句。
 */
@QuarkusTest
public class MaterialRecipeExportServiceTest {

    private static final String MAT = "T260902测试材质";

    @Inject
    MaterialRecipeExportService exportService;

    @Inject
    MaterialRecipeImportService importService;

    // ──────────────────────────── 夹具 ────────────────────────────

    /** 造一个只属于本用例的材质：1 组含量（Ag 0.9 / Cu 0.1）。 */
    private void seed() throws Exception {
        MaterialImportReportDTO r = importService.importLibrary(build4Col(new String[][]{
            {MAT, "1", "Ag", "0.9"},
            {MAT, "1", "Cu", "0.1"},
        }));
        assertEquals(1, r.configsCreated, "夹具应新建 1 组配置");
    }

    private byte[] build4Col(String[][] rows) throws Exception {
        try (Workbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            Sheet s = wb.createSheet(MaterialRecipeImportService.SHEET_NAME);
            Row h = s.createRow(0);
            for (int i = 0; i < MaterialRecipeImportService.HEADER.size(); i++) {
                h.createCell(i).setCellValue(MaterialRecipeImportService.HEADER.get(i));
            }
            for (int i = 0; i < rows.length; i++) {
                Row r = s.createRow(i + 1);
                for (int j = 0; j < 4; j++) r.createCell(j).setCellValue(rows[i][j]);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private Sheet read(byte[] xlsx) throws Exception {
        Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx));
        return wb.getSheetAt(0);
    }

    private String str(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> new BigDecimal(Double.toString(c.getNumericCellValue()))
                                .stripTrailingZeros().toPlainString();
            default -> "";
        };
    }

    // ──────────────────────────── 用例 ────────────────────────────

    /** AC-4 / AC-6：前 4 列逐字＝导入端 HEADER（位置不可换），只读参考列从第 5 列起。 */
    @Test
    @TestTransaction
    void exportHeaderIsImportCompatible() throws Exception {
        seed();
        Sheet s = read(exportService.export(MAT, null, null));
        Row h = s.getRow(0);

        for (int i = 0; i < MaterialRecipeImportService.HEADER.size(); i++) {
            assertEquals(MaterialRecipeImportService.HEADER.get(i), str(h.getCell(i)),
                "第 " + (i + 1) + " 列表头必须与导入端 HEADER 逐字相同（导入端按位置比对）");
        }
        assertEquals("材质编号", str(h.getCell(4)));
        assertEquals("含量配置编号", str(h.getCell(5)));
        assertEquals("状态", str(h.getCell(6)));
        assertEquals("含量类型", str(h.getCell(7)));
    }

    /** AC-5：含量写 0–1 小数（库 90 ⇒ 文件 0.9），且全表 0 &lt; 值 ≤ 1。 */
    @Test
    @TestTransaction
    void contentIsDividedByHundred() throws Exception {
        seed();
        Sheet s = read(exportService.export(MAT, null, null));
        assertEquals(2, s.getLastRowNum(), "夹具材质应导出 2 行数据");

        List<String> contents = new ArrayList<>();
        for (int r = 1; r <= s.getLastRowNum(); r++) {
            String v = str(s.getRow(r).getCell(3));
            contents.add(v);
            BigDecimal bd = new BigDecimal(v);
            assertTrue(bd.compareTo(BigDecimal.ZERO) > 0 && bd.compareTo(BigDecimal.ONE) <= 0,
                "含量必须落在 (0,1]，实际=" + v + "（照搬库值 90/10 会让回导每行都被判含量非法）");
        }
        assertTrue(contents.contains("0.9"), "库 default_pct=90 ⇒ 文件必须写 0.9，实际=" + contents);
        assertTrue(contents.contains("0.1"), "库 default_pct=10 ⇒ 文件必须写 0.1，实际=" + contents);
    }

    /** AC-19：导出文件<b>一个字节不改</b>回导 ⇒ 新建材质 0、新建配置 0、该组被判重复跳过。 */
    @Test
    @TestTransaction
    void roundTripIsIdempotent() throws Exception {
        seed();
        byte[] exported = exportService.export(MAT, null, null);

        MaterialImportReportDTO back = importService.importLibrary(exported);

        assertEquals(0, back.configsCreated,
            "原样回导必须零新增配置（非 0 ⇒ 表头位置或含量口径不对）");
        assertEquals(0, back.elementRowsInserted, "原样回导必须零新增元素行");
        assertEquals(1, back.configsSkippedAsDuplicate, "文件里的那 1 组应被判「已存在」跳过");
        assertTrue(back.skipped.isEmpty(),
            "不应有行级跳过（含量非法即出现在这里）：" + back.skipped);
        assertEquals(2, back.totalRows, "只读参考列不该被当成数据行");
    }

    /** AC-20：在导出文件里追加一组含量再回导 ⇒ 恰好 +1 组；再导出得 4 行、组号 1/1/2/2。 */
    @Test
    @TestTransaction
    void roundTripWithOneAddedGroup() throws Exception {
        seed();
        byte[] exported = exportService.export(MAT, null, null);

        // 在导出文件末尾追加「组号 2」的两行（Ag 0.8 / Cu 0.2），其余原样不动
        byte[] edited;
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(exported))) {
            Sheet s = wb.getSheetAt(0);
            int next = s.getLastRowNum() + 1;
            String[][] add = {{MAT, "2", "Ag", "0.8"}, {MAT, "2", "Cu", "0.2"}};
            for (String[] a : add) {
                Row r = s.createRow(next++);
                for (int j = 0; j < 4; j++) r.createCell(j).setCellValue(a[j]);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            edited = bos.toByteArray();
        }

        MaterialImportReportDTO back = importService.importLibrary(edited);
        assertEquals(1, back.configsCreated, "只应新增 1 组（原有那组仍要被判重复）");
        assertEquals(1, back.configsSkippedAsDuplicate, "原有那组必须被判重复，不能误伤成新增");

        Sheet s2 = read(exportService.export(MAT, null, null));
        assertEquals(4, s2.getLastRowNum(), "两组共 4 行");
        List<String> seqs = new ArrayList<>();
        for (int r = 1; r <= s2.getLastRowNum(); r++) seqs.add(str(s2.getRow(r).getCell(1)));
        assertEquals(List.of("1", "1", "2", "2"), seqs, "按 symbol, seq, sort_order 排序");
    }

    /**
     * AC-7 口径：{@code status=INACTIVE} 走的是「<b>不等于 ACTIVE</b>」而非「等于 'INACTIVE'」——
     * 后者会让 {@code status IS NULL} 的行两边都查不到（页面看得见、导出里没有）。
     * 夹具材质是 ACTIVE，所以筛「停用」必须查不到它、筛「启用」必须查得到。
     */
    @Test
    @TestTransaction
    void statusFilterMatchesFrontendIsActive() throws Exception {
        seed();
        assertEquals(2, read(exportService.export(MAT, null, "ACTIVE")).getLastRowNum());
        assertEquals(0, read(exportService.export(MAT, null, "INACTIVE")).getLastRowNum(),
            "ACTIVE 材质不该出现在「停用」筛选的导出里");
    }

    /** keyword 复用列表的匹配规则：按元素符号也能搜到（列表侧同样支持元素维度）。 */
    @Test
    @TestTransaction
    void keywordReusesListSemantics() throws Exception {
        seed();
        Sheet byName = read(exportService.export(MAT, null, null));
        assertEquals(2, byName.getLastRowNum());

        // recipeType 精确相等：导入自动建的材质是 locked，筛 editable 应为空
        assertEquals(0, read(exportService.export(MAT, "editable", null)).getLastRowNum());
        assertEquals(2, read(exportService.export(MAT, "locked", null)).getLastRowNum());
    }

    /** AC-23 后端兜底：筛不到任何数据时仍返 200 + 只有表头的 xlsx，不报错。 */
    @Test
    @TestTransaction
    void emptyResultStillProducesHeaderOnlyWorkbook() throws Exception {
        Sheet s = read(exportService.export("zzz不存在zzz260902", null, null));
        assertEquals(0, s.getLastRowNum(), "只有表头行");
        assertEquals("材质", str(s.getRow(0).getCell(0)));
    }
}
