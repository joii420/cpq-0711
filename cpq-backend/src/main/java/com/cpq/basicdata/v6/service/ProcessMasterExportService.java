package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.entity.ProcessMaster;
import com.cpq.basicdata.v6.repository.ProcessMasterRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

/**
 * 工序主数据导出服务（task-260902 · B-2，api.md B-2）。
 *
 * <p><b>列名取自 {@link ProcessMasterImportService} 的 {@code COL_*} 常量，不是页面表头</b>：
 * 第 3 列叫「工序类别」而页面叫「工序分类」、第 5 列叫「标准币种」而页面叫「标准货币」——
 * 这个不同名是<b>刻意的</b>，导出要能回导就必须用导入端认识的列名
 * （{@code ProcessMasterImportService} 按<b>列名</b> {@code colIdx.get(COL_NO)} 取列，顺序无关）。
 *
 * <p>两个取值口径：{@code 是否外协} 写 {@code 是}/{@code 否}（🚫 不写 true/false）；
 * {@code 默认不良率} 写<b>原始小数</b>（{@code 0.01}，🚫 不写 {@code 1.00%}）——
 * 导入端 {@code parseDecimal} 认的是小数。
 *
 * <p><b>筛选口径</b>：复用 {@link ProcessMasterRepository#search}（与 {@code GET /v6/process-master}
 * 同一个查询构造），只是<b>不传 page/size</b> ⇒ 导出的是筛选结果全量而非当前页。
 *
 * <p><b>N+1 纪律</b>：恒 1 条 SQL（一次 list()），与工序条数无关。<b>只读</b>，不写库。
 */
@ApplicationScoped
public class ProcessMasterExportService {

    /** 表头 = 导入端列名常量。🚫 不重抄字面量。 */
    static final List<String> HEADER = List.of(
        ProcessMasterImportService.COL_NO,
        ProcessMasterImportService.COL_NAME,
        ProcessMasterImportService.COL_CATEGORY,
        ProcessMasterImportService.COL_OUTSOURCE,
        ProcessMasterImportService.COL_CURRENCY,
        ProcessMasterImportService.COL_UNIT,
        ProcessMasterImportService.COL_DEFECT_RATE);

    /** 与导入模板 sheet 名一致；导入端优先按此名取 sheet，取不到才退回第一个。 */
    static final String SHEET_NAME = ProcessMasterImportService.PREFERRED_SHEET;

    @Inject
    ProcessMasterRepository repository;

    /**
     * 导出「当前筛选结果」的全量工序（不受分页限制）。
     *
     * @param keyword         可空；processNo / processName 模糊匹配（与列表同一套）
     * @param isOutsource     null=全部；true=外协；false=自制（{@code is_outsource IS NULL} 两侧都不出现）
     * @param processCategory 可空；精确匹配
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public byte[] export(String keyword, Boolean isOutsource, String processCategory) {
        // 一次查全（不分页）——SQL 条数恒为 1
        List<ProcessMaster> rows =
            repository.search(keyword, null, null, isOutsource, processCategory).list();
        return toWorkbook(rows);
    }

    private byte[] toWorkbook(List<ProcessMaster> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet(SHEET_NAME);
            Row h = s.createRow(0);
            for (int i = 0; i < HEADER.size(); i++) h.createCell(i).setCellValue(HEADER.get(i));

            // 🚫 N+1 自检：本循环体是纯内存渲染。ProcessMaster 无关联字段，
            //    这里读到的都是已加载的标量列，不会触发任何懒加载查询。
            for (int i = 0; i < rows.size(); i++) {
                ProcessMaster p = rows.get(i);
                Row row = s.createRow(i + 1);
                row.createCell(0).setCellValue(nz(p.processNo));
                row.createCell(1).setCellValue(nz(p.processName));
                row.createCell(2).setCellValue(nz(p.processCategory));
                // 是否外协：是 / 否；NULL 留空（导入端 parseBool 对空值返 null，不会把未知写成 false）
                row.createCell(3).setCellValue(p.isOutsource == null ? "" : (p.isOutsource ? "是" : "否"));
                row.createCell(4).setCellValue(nz(p.standardCurrency));
                row.createCell(5).setCellValue(nz(p.standardUnit));
                setRate(row, 6, p.defaultDefectRate);
            }
            for (int i = 0; i < HEADER.size(); i++) s.setColumnWidth(i, 14 * 256);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("导出工序主数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * 默认不良率：写<b>原始小数</b>数值（0.01），🚫 不加百分号、不 ×100。
     * 列类型 numeric(18,12)，{@code stripTrailingZeros()} 去掉尾随 0；
     * ⚠️ 对整数值它会给出 {@code 1E+1} 这类科学计数法 ⇒ 必须先 {@code toPlainString()} 再 parse。
     */
    private void setRate(Row row, int col, BigDecimal rate) {
        if (rate == null) {
            row.createCell(col).setCellValue("");
            return;
        }
        row.createCell(col).setCellValue(
            Double.parseDouble(rate.stripTrailingZeros().toPlainString()));
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}
