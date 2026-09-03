package com.cpq.configure.service;

import com.cpq.configure.dto.MaterialRecipeDTO;
import com.cpq.configure.dto.MaterialRecipeUpsertRequest;
import com.cpq.configure.entity.Element;
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
 *
 * <p>task-260901：请求体与导入格式都变了（configs / 单表 4 列），<b>三条语义断言不变</b>。
 */
@QuarkusTest
public class MaterialNameSymbolRepairTest {

    @Inject
    MaterialRecipeService service;

    @Inject
    MaterialRecipeImportService importService;

    private String elementNo(String symbol) {
        Element e = Element.<Element>find("elementCode", symbol).firstResult();
        assertNotNull(e, "前置：element 主表应已有 " + symbol);
        return e.elementNo;
    }

    private MaterialRecipeUpsertRequest createReq(String symbol, String name) {
        MaterialRecipeUpsertRequest r = new MaterialRecipeUpsertRequest();
        r.symbol = symbol;
        r.name = name;
        r.recipeType = "locked";
        r.status = "ACTIVE";
        r.sortOrder = 1;
        MaterialRecipeUpsertRequest.ElementUpsert e = new MaterialRecipeUpsertRequest.ElementUpsert();
        e.elementNo = elementNo("Ag");
        e.defaultPct = new BigDecimal("100");
        MaterialRecipeUpsertRequest.ConfigUpsert g = new MaterialRecipeUpsertRequest.ConfigUpsert();
        g.elements = List.of(e);
        r.configs = List.of(g);
        return r;
    }

    private MaterialRecipeUpsertRequest updateReq(String symbol, String name) {
        MaterialRecipeUpsertRequest r = new MaterialRecipeUpsertRequest();
        r.symbol = symbol;
        r.name = name;
        r.recipeType = "locked";
        r.status = "ACTIVE";
        r.sortOrder = 1;
        return r;
    }

    // ── RB2：新建/编辑 name 为空默认=symbol；填了用填入 ──

    @Test
    @TestTransaction
    void create_nameEmpty_defaultsToSymbol_nameProvided_kept() {
        MaterialRecipeDTO d1 = service.create(createReq("UTSymA", null));
        assertEquals("UTSymA", d1.name, "name 空 → 默认=symbol");

        MaterialRecipeDTO d2 = service.create(createReq("UTSymB", "自定义名"));
        assertEquals("自定义名", d2.name, "name 提供 → 用提供值");

        // 编辑清空名称 → 回落 symbol
        MaterialRecipeDTO d3 = service.update(d2.id, updateReq("UTSymB", "  "));
        assertEquals("UTSymB", d3.name, "编辑清空名称 → 回落 symbol");
    }

    // ── RB4：列表搜索命中 name ──

    @Test
    @TestTransaction
    void list_keyword_matchesName() {
        MaterialRecipeDTO d = service.create(createReq("UTSymX", "独特材质名ZZZ"));
        assertTrue(service.list("独特材质名ZZZ", false).stream().anyMatch(x -> d.code.equals(x.code)),
            "按名称(name)搜索应命中");
    }

    // ── RB1：导入 name=symbol ──

    @Test
    @TestTransaction
    void import_setsNameEqualsSymbol() throws Exception {
        importService.importLibrary(buildWorkbook("UTSymImp", "Ag", "1"));
        MaterialRecipe r = MaterialRecipe.<MaterialRecipe>find("symbol", "UTSymImp").firstResult();
        assertNotNull(r);
        assertEquals("UTSymImp", r.symbol);
        assertEquals(r.symbol, r.name, "导入 name 默认=symbol");
    }

    /** 单材质单元素 workbook（Σ=1，新格式单 sheet 4 列）。 */
    private byte[] buildWorkbook(String mat, String elem, String content) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("材质含量");
            Row h = s.createRow(0);
            String[] hdr = {"材质", "组号", "元素符号", "含量"};
            for (int i = 0; i < hdr.length; i++) h.createCell(i).setCellValue(hdr[i]);
            Row r = s.createRow(1);
            r.createCell(0).setCellValue(mat);
            r.createCell(1).setCellValue("1");
            r.createCell(2).setCellValue(elem);
            r.createCell(3).setCellValue(content);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }
}
