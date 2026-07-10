package com.cpq.configure.service;

import com.cpq.configure.dto.MaterialRecipeDTO;
import com.cpq.configure.dto.MaterialRecipeUpsertRequest;
import com.cpq.configure.entity.MaterialRecipe;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0708 · repair-1：材质 name/symbol 语义修补（RB1 导入 name=symbol / RB2 新建编辑默认 / RB4 搜索加 name）。
 */
@QuarkusTest
public class MaterialNameSymbolRepairTest {

    @Inject
    MaterialRecipeService service;

    @Inject
    MaterialRecipeImportService importService;

    private MaterialRecipeUpsertRequest req(String code, String symbol, String name) {
        MaterialRecipeUpsertRequest r = new MaterialRecipeUpsertRequest();
        r.code = code;
        r.symbol = symbol;
        r.name = name;
        r.recipeType = "locked";
        r.status = "ACTIVE";
        r.sortOrder = 1;
        MaterialRecipeUpsertRequest.ElementUpsert e = new MaterialRecipeUpsertRequest.ElementUpsert();
        e.elementCode = "Ag";
        e.elementName = "银";
        e.defaultPct = new BigDecimal("100");
        e.isLocked = true;
        e.sortOrder = 1;
        r.elements = List.of(e);
        return r;
    }

    // ── RB2：新建/编辑 name 为空默认=symbol；填了用填入 ──

    @Test
    @TestTransaction
    void create_nameEmpty_defaultsToSymbol_nameProvided_kept() {
        MaterialRecipeDTO d1 = service.create(req("RBC001", "SymA", null));
        assertEquals("SymA", d1.name, "name 空 → 默认=symbol");

        MaterialRecipeDTO d2 = service.create(req("RBC002", "SymB", "自定义名"));
        assertEquals("自定义名", d2.name, "name 提供 → 用提供值");

        // 编辑清空名称 → 回落 symbol
        MaterialRecipeDTO d3 = service.update(d2.id, req("RBC002", "SymB", "  "));
        assertEquals("SymB", d3.name, "编辑清空名称 → 回落 symbol");
    }

    // ── RB4：列表搜索命中 name ──

    @Test
    @TestTransaction
    void list_keyword_matchesName() {
        service.create(req("RB4001", "SymX", "独特材质名ZZZ"));
        assertTrue(service.list("独特材质名ZZZ", false).stream().anyMatch(x -> "RB4001".equals(x.code)),
            "按名称(name)搜索应命中");
    }

    // ── RB1：导入 name=symbol ──

    @Test
    @TestTransaction
    void import_setsNameEqualsSymbol() throws Exception {
        byte[] xlsx = buildWorkbook("SymImp", "RB1001", "Ag", 1.0, "10001");
        importService.importLibrary(xlsx);
        MaterialRecipe r = MaterialRecipe.<MaterialRecipe>find("code", "RB1001").firstResult();
        assertNotNull(r);
        assertEquals("SymImp", r.symbol);
        assertEquals(r.symbol, r.name, "导入 name 默认=symbol");
    }

    /** 单材质单元素 workbook（Σ=1）。 */
    private byte[] buildWorkbook(String mat, String code, String elem, double content, String elemNo) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet cs = wb.createSheet("材质编号");
            Row ch = cs.createRow(0);
            ch.createCell(0).setCellValue("材质");
            ch.createCell(1).setCellValue("材质编号");
            Row cr = cs.createRow(1);
            cr.createCell(0).setCellValue(mat);
            cr.createCell(1).setCellValue(code);

            Sheet es = wb.createSheet("材质对应元素");
            Row eh = es.createRow(0);
            String[] hdr = {"材质", "材质编号", "元素名称", "含量", "元素编号"};
            for (int i = 0; i < hdr.length; i++) eh.createCell(i).setCellValue(hdr[i]);
            Row er = es.createRow(1);
            er.createCell(0).setCellValue(mat);
            er.createCell(1).setCellValue(code);
            er.createCell(2).setCellValue(elem);
            er.createCell(3).setCellValue(content);
            er.createCell(4).setCellValue(elemNo);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }
}
