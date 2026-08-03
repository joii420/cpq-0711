package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** repair-0802：电镀费用 sheet 进入 Phase 1 预校验（投入料号/名称非必填）。 */
@QuarkusTest
class QuoteImportValidatorPlatingTest {

    @Inject QuoteImportValidator validator;

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE";
        return c;
    }

    private SheetRow platingRow(int rowNo, String salesNo, String inputNo, String inputName) {
        Map<String, String> m = new LinkedHashMap<>();
        if (salesNo != null) m.put("宏丰料号", salesNo);
        if (inputNo != null) m.put("投入料号", inputNo);
        if (inputName != null) m.put("投入料号名称", inputName);
        m.put("电镀加工费", "5"); m.put("电镀材料费", "3");
        return new SheetRow(rowNo, m);
    }

    /**
     * 「物料与元素BOM」是 RECIPE 类型的权威来源（PartTypeInferenceService.buildIndex
     * 用它的「材质料号」「材质料号名称」两列建 recipeTokens）。测试里塞一行，把某个名称登记为材质，
     * 才能让电镀费用行引用同名时被 infer 判成 RECIPE —— 否则兜底类型是 ASSEMBLY
     * （PartTypeInferenceService.java:177 `new InferResult(ASSEMBLY, Source.DEFAULT)`），
     * 走铸号路径而不是材质反查，不会产生「未找到材质」。
     */
    private SheetRow recipeSeedRow(String recipeName) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", "S-PLATE-1");
        m.put("材质料号名称", recipeName);
        return new SheetRow(1, m);
    }

    private SheetImportResult run(List<SheetRow> rows, List<SheetRow> elementBomRows) {
        Map<String, List<SheetRow>> sheets = new LinkedHashMap<>();
        sheets.put("电镀费用", rows);
        sheets.put("物料与元素BOM", elementBomRows);
        return validator.validate(sheets, ctx()).bySheet.get("电镀费用");
    }

    private SheetImportResult run(List<SheetRow> rows) { return run(rows, List.of()); }

    @Test void bothColumnsBlank_isNotAnError() {
        SheetImportResult r = run(List.of(platingRow(1, "S-PLATE-1", null, null)));
        assertEquals(0, r.failedRows, "投入料号与名称均非必填，两列皆空不得报错");
        assertEquals(1, r.successRows);
    }

    @Test void salesNoBlank_isAnError() {
        SheetImportResult r = run(List.of(platingRow(1, null, "P-1", null)));
        assertEquals(1, r.failedRows, "销售料号仍必填");
    }

    @Test void inputNamePresent_butRecipeNotFound_isAnError() {
        // 名称经「物料与元素BOM」登记为材质(RECIPE)，但 material_recipe 表中查无此名 → Phase 1 拦截
        String ghost = "__repair0802虚构材质__";
        SheetImportResult r = run(List.of(platingRow(1, "S-PLATE-1", null, ghost)),
                                  List.of(recipeSeedRow(ghost)));
        assertEquals(1, r.failedRows, "只填名称且材质查无 → Phase 1 拦截");
        assertTrue(r.errors.get(0).message.contains("未找到材质"), "错误文案应指明未找到材质，实际=" + r.errors.get(0).message);
    }

    @Test void inputNameOnly_nonRecipe_passesPhase1() {
        // 未被任何权威 sheet 登记的名称 → 兜底 ASSEMBLY(零件) → 走铸号路径，
        // Phase 1 只做只读预判、不实际铸号，故不报错。
        SheetImportResult r = run(List.of(platingRow(1, "S-PLATE-1", null, "某个没登记过的零件名")));
        assertEquals(0, r.failedRows, "零件类只填名称 → Phase 1 放行，Phase 2 铸号");
        assertEquals(1, r.successRows);
    }

    @Test void inputNoPresent_passes() {
        SheetImportResult r = run(List.of(platingRow(1, "S-PLATE-1", "P-1", "随便什么名")));
        assertEquals(0, r.failedRows, "有码即通过，不做名称反查");
        assertEquals(1, r.successRows);
    }
}
