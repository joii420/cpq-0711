package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.SheetHandler;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * 核价基础数据导入模板生成（task-0728 · B4 / api.md A4）。
 *
 * <p>规则：
 * <ul>
 *   <li>Sheet 名 <b>遍历 {@link PricingHandlerCatalog} 取 {@code sheetName()}</b>，不手写常量数组
 *       —— 与 {@code PricingImportService} 的 {@code wb.getSheet(h.sheetName())} 同源；</li>
 *   <li>Sheet 顺序 = P01 → P24；</li>
 *   <li>第 1 行写 {@link SheetHandler#templateHeaders()}（加粗 + 冻结首行），数据行为空。</li>
 * </ul>
 *
 * <p>某 handler 未声明表头时只建空 sheet 并 {@code Log.warn}：sheet 存在即可被导入识别
 * （0 行数据，不报「缺少 Sheet」），但要提示补齐列名。
 */
@ApplicationScoped
public class PricingTemplateService {

    @Inject PricingHandlerCatalog catalog;

    /** 生成 24 Sheet 空模板 xlsx 字节流。 */
    public byte[] generateTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle headerStyle = headerStyle(wb);
            for (SheetHandler h : catalog.all()) {
                Sheet sheet = wb.createSheet(h.sheetName());
                List<String> headers = h.templateHeaders();
                if (headers == null || headers.isEmpty()) {
                    Log.warnf("[pricing-template] Sheet [%s] 未声明 templateHeaders()，只建空 sheet（待补列名）",
                        h.sheetName());
                    continue;
                }
                Row row = sheet.createRow(0);
                for (int i = 0; i < headers.size(); i++) {
                    Cell c = row.createCell(i);
                    c.setCellValue(headers.get(i));
                    c.setCellStyle(headerStyle);
                }
                sheet.createFreezePane(0, 1);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("生成核价基础数据导入模板失败: " + e.getMessage(), e);
        }
    }

    private static CellStyle headerStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }
}
