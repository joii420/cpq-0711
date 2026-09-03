package com.cpq.configure.service;

import com.cpq.configure.dto.MaterialRecipeDTO;
import com.cpq.configure.dto.MaterialRecipeUpsertRequest;
import com.cpq.configure.entity.Element;
import com.cpq.configure.entity.MaterialRecipe;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-E3：编辑材质时「材质编号(code)只读」——后端契约兜底（api.md §五）。
 * PUT 请求体即使传了不同 code，也不得改动主键+搜索键+下游 join 键。
 *
 * <p>task-260901：新建态请求体由 {@code elements} 换成 {@code configs}（BC-2），
 * 且 {@code code} 改为服务端发号 —— <b>「编号只读」这条语义不变，只是现在连新建都由服务端定</b>。
 */
@QuarkusTest
public class MaterialRecipeUpdateCodeReadonlyTest {

    @Inject
    MaterialRecipeService service;

    private String elementNo(String symbol) {
        Element e = Element.<Element>find("elementCode", symbol).firstResult();
        assertNotNull(e, "前置：element 主表应已有 " + symbol);
        return e.elementNo;
    }

    /** 新建态：configs 一组 Ag100。 */
    private MaterialRecipeUpsertRequest createReq(String symbol) {
        MaterialRecipeUpsertRequest r = new MaterialRecipeUpsertRequest();
        r.symbol = symbol;
        r.name = null;
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

    /** 编辑态：不带 configs，只改材质级字段（并尝试篡改 code）。 */
    private MaterialRecipeUpsertRequest updateReq(String hackedCode, String symbol) {
        MaterialRecipeUpsertRequest r = new MaterialRecipeUpsertRequest();
        r.code = hackedCode;
        r.symbol = symbol;
        r.name = null;
        r.recipeType = "locked";
        r.status = "ACTIVE";
        r.sortOrder = 1;
        return r;
    }

    @Test
    @TestTransaction
    void update_codeIsReadOnly_ignoresClientProvidedCode() {
        MaterialRecipeDTO created = service.create(createReq("UTSymOld"));
        UUID id = created.id;
        String assignedCode = created.code;
        assertNotNull(assignedCode, "新建态由服务端发号");

        // 篡改尝试：请求体把 code 改成 HACKED，同时正常改 symbol
        MaterialRecipeDTO updated = service.update(id, updateReq("HACKED", "UTSymNew"));

        assertEquals(assignedCode, updated.code, "材质编号只读，不随入参改");
        assertEquals("UTSymNew", updated.symbol, "其它字段(symbol)正常改并生效");

        // DB 复查：code 仍为原值、HACKED 不存在
        MaterialRecipe fromDb = MaterialRecipe.findById(id);
        assertEquals(assignedCode, fromDb.code, "DB 里 code 仍为原值");
        assertNull(MaterialRecipe.find("code", "HACKED").firstResult(), "HACKED code 不应落库");
    }

    /**
     * task-260901（api.md §2.1 · 2026-09-02 补）：{@code PUT} 必须接收并落库 {@code sortOrder}
     * 与 {@code status}。
     * <p>🚨 {@code status} 是「把材质改回启用」的<b>唯一入口</b> —— 后端若不接收，
     * 前端发了、不报错、也不生效，是最难查的那类静默失效。
     */
    @Test
    @TestTransaction
    void update_persistsSortOrderAndStatus() {
        MaterialRecipeDTO created = service.create(createReq("UTSym状态"));
        UUID id = created.id;
        assertEquals("ACTIVE", created.status);

        // ① 停用 + 改排序
        MaterialRecipeUpsertRequest off = updateReq(null, "UTSym状态");
        off.status = "INACTIVE";
        off.sortOrder = 777;
        MaterialRecipeDTO disabled = service.update(id, off);
        assertEquals("INACTIVE", disabled.status, "PUT 必须能把材质停用");
        assertEquals(777, disabled.sortOrder, "PUT 必须落库 sortOrder");
        assertEquals("INACTIVE", ((MaterialRecipe) MaterialRecipe.findById(id)).status, "库内已停用");

        // ② 改回启用（这是唯一入口）
        MaterialRecipeUpsertRequest on = updateReq(null, "UTSym状态");
        on.status = "ACTIVE";
        on.sortOrder = 888;
        MaterialRecipeDTO enabled = service.update(id, on);
        assertEquals("ACTIVE", enabled.status, "PUT 必须能把材质改回启用");
        assertEquals(888, enabled.sortOrder);
        MaterialRecipe fromDb = MaterialRecipe.findById(id);
        assertEquals("ACTIVE", fromDb.status, "库内已启用");
        assertEquals(888, fromDb.sortOrder, "库内 sortOrder 已更新");
    }
}
