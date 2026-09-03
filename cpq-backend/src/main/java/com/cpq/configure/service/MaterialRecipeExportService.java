package com.cpq.configure.service;

import com.cpq.configure.rules.MaterialRecipeRules;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 材质库导出服务（task-260902 · B-1，api.md B-1）。
 *
 * <p><b>唯一目标是「可回导」</b>：导出的 xlsx 必须能被
 * {@link MaterialRecipeImportService} 原样吃回去。为此有三条不可动摇的约束：
 * <ol>
 *   <li><b>前 4 列 == {@link MaterialRecipeImportService#HEADER}</b>（位置与文字都不能变）——
 *       导入端 {@code assertHeader} 按<b>位置</b>逐列比对，错一位即 400 {@code IMPORT_HEADER_INVALID}。
 *       这里<b>直接引用那个常量</b>，🚫 不重抄字符串字面量（抄一遍＝埋一个「将来改一处忘另一处」的雷）。</li>
 *   <li><b>含量写 0–1 小数</b>：库里 {@code default_pct} 是 100 制（84），导入端
 *       {@code pctInRange(v, 1)} 只收 {@code (0,1]} 并在落库时 {@code ×100}。
 *       ⇒ 导出必须 {@code ÷100}（84 → 0.84），否则回导时<b>每一行</b>都被判「含量非法」。</li>
 *   <li><b>只读参考列从第 5 列起</b>（材质编号 / 含量配置编号 / 状态 / 含量类型）——
 *       导入端只读前 4 列，第 5 列往后天然被忽略；插到前面则表头位置错位 → 400。</li>
 * </ol>
 *
 * <p><b>N+1 纪律</b>：整个导出<b>恒 1 条 SQL</b>（一条三表 JOIN 取全部行），
 * 与材质数 / 配置数 / 元素行数无关。🚫 不许「先查材质列表再逐材质查配置」。
 *
 * <p><b>只读</b>：本服务不执行任何写操作。
 */
@ApplicationScoped
public class MaterialRecipeExportService {

    /** 只读参考列（第 5 列起，回导时被导入端忽略）。 */
    static final List<String> READONLY_HEADER = List.of("材质编号", "含量配置编号", "状态", "含量类型");

    /** 导出 sheet 名沿用导入模板的 sheet 名；导入端读第一个工作表、不依赖 sheet 名。 */
    static final String SHEET_NAME = MaterialRecipeImportService.SHEET_NAME;

    /** {@code recipe_type} → 中文标签（与前端 {@code recipeTypeTag} 同表）。查无回退原值。 */
    private static final Map<String, String> TYPE_LABEL = Map.of(
        "locked", "标准锁定",
        "editable", "含量可调",
        "partial", "部分可调");

    @Inject
    EntityManager em;

    /**
     * 导出「当前筛选结果」的全量材质含量行（不受分页限制）。
     *
     * @param keyword    可空；与 {@code GET /material-recipes} 同一套匹配规则
     *                   （复用 {@link MaterialRecipeService#keywordPredicate}，🚫 不另写）
     * @param recipeType 可空；locked / editable / partial，对 {@code recipe_type} 精确相等
     * @param status     可空；ACTIVE / INACTIVE。⚠️ 口径与前端 {@code isActive()} 一致 ——
     *                   <b>仅 'ACTIVE' 算启用，其余（含 NULL）都算停用</b>。
     *                   写成 {@code status = 'INACTIVE'} 会让 {@code status IS NULL} 的行两边都查不到。
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public byte[] export(String keyword, String recipeType, String status) {
        return toWorkbook(query(keyword, recipeType, status));
    }

    // ──────────────────────────────── 取数（恒 1 条 SQL）────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Object[]> query(String keyword, String recipeType, String status) {
        boolean hasKw = keyword != null && !keyword.isBlank();
        String type = (recipeType == null || recipeType.isBlank()) ? null : recipeType.trim();
        String st = (status == null || status.isBlank()) ? null : status.trim();

        StringBuilder sql = new StringBuilder(
            "SELECT r.symbol, c.seq, e.element_code, e.default_pct, " +
            "       r.code, c.config_no, r.status, r.recipe_type " +
            "  FROM material_recipe_element e " +
            "  JOIN material_recipe_config  c ON c.id = e.config_id AND c.status = 'ACTIVE' " +
            "  JOIN material_recipe         r ON r.id = c.recipe_id " +
            " WHERE 1 = 1 ");
        if (hasKw) {
            sql.append(" AND ").append(MaterialRecipeService.keywordPredicate("r")).append(' ');
        }
        if (type != null) {
            sql.append(" AND r.recipe_type = :recipeType ");
        }
        if (st != null) {
            // 仅 'ACTIVE' 算启用；其余（含 NULL）都算停用 —— 与前端 isActive() 逐字对齐
            sql.append("ACTIVE".equals(st)
                ? " AND r.status = 'ACTIVE' "
                : " AND (r.status IS NULL OR r.status <> 'ACTIVE') ");
        }
        sql.append(" ORDER BY r.symbol, c.seq, e.sort_order");

        var q = em.createNativeQuery(sql.toString());
        if (hasKw) q.setParameter("kw", "%" + keyword.trim() + "%");
        if (type != null) q.setParameter("recipeType", type);
        return (List<Object[]>) q.getResultList();
    }

    // ──────────────────────────────── 出表 ────────────────────────────────

    private byte[] toWorkbook(List<Object[]> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet(SHEET_NAME);

            // 表头：前 4 列直接引用导入端常量（🚫 不重抄字面量），只读列从第 5 列起
            List<String> header = new ArrayList<>(MaterialRecipeImportService.HEADER);
            header.addAll(READONLY_HEADER);
            Row h = s.createRow(0);
            for (int i = 0; i < header.size(); i++) h.createCell(i).setCellValue(header.get(i));

            // 🚫 N+1 自检：本循环体是纯内存渲染，无任何查库 / 无懒加载 getter
            for (int i = 0; i < rows.size(); i++) {
                Object[] r = rows.get(i);
                Row row = s.createRow(i + 1);
                row.createCell(0).setCellValue(str(r[0]));                       // 材质 = symbol
                row.createCell(1).setCellValue(str(r[1]));                       // 组号 = config.seq
                row.createCell(2).setCellValue(str(r[2]));                       // 元素符号
                setContent(row, 3, r[3]);                                        // 含量 = default_pct ÷ 100
                row.createCell(4).setCellValue(str(r[4]));                       // 材质编号（只读）
                row.createCell(5).setCellValue(str(r[5]));                       // 含量配置编号（只读）
                row.createCell(6).setCellValue("ACTIVE".equals(str(r[6])) ? "启用" : "停用");
                row.createCell(7).setCellValue(TYPE_LABEL.getOrDefault(str(r[7]), str(r[7])));
            }
            for (int i = 0; i < header.size(); i++) s.setColumnWidth(i, 14 * 256);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("导出材质库失败: " + e.getMessage(), e);
        }
    }

    /**
     * 含量单元格：{@code default_pct ÷ 100} 写成数值（与导入模板 {@code exampleRow} 同为 NUMERIC）。
     *
     * <p>⚠️ 除法用<b>无 scale 的 {@link BigDecimal#divide(BigDecimal)}</b>：除以 100 一定是有限小数，
     * 不会抛 ArithmeticException，且<b>逐位无损</b>。指定 scale 12 反而会把 {@code default_pct} 尾部
     * 第 11~12 位小数（列类型是 numeric(16,12)）四舍五入掉，回导后与原配置<b>内容不等</b>
     * → 被当成新配置插进去，AC-19 的「零新增」就破了。
     *
     * <p>⚠️ {@code stripTrailingZeros()} 对 100 会得到 {@code 1E+2} —— 所以走数值写入前先
     * {@code toPlainString()} 再 parse，绝不把科学计数法写进单元格。
     */
    private void setContent(Row row, int col, Object raw) {
        BigDecimal pct = raw == null ? null : new BigDecimal(raw.toString());
        if (pct == null) {
            row.createCell(col).setCellValue("");
            return;
        }
        String plain = pct.divide(MaterialRecipeRules.HUNDRED)
                          .stripTrailingZeros()
                          .toPlainString();
        row.createCell(col).setCellValue(Double.parseDouble(plain));
    }

    private String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
