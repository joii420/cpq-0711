package com.cpq.configure.service;

import com.cpq.configure.dto.MaterialRecipeDTO;
import com.cpq.configure.dto.MaterialRecipeUpsertRequest;
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
 */
@QuarkusTest
public class MaterialRecipeUpdateCodeReadonlyTest {

    @Inject
    MaterialRecipeService service;

    private MaterialRecipeUpsertRequest req(String code, String symbol) {
        MaterialRecipeUpsertRequest r = new MaterialRecipeUpsertRequest();
        r.code = code;
        r.symbol = symbol;
        r.name = null;
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

    @Test
    @TestTransaction
    void update_codeIsReadOnly_ignoresClientProvidedCode() {
        MaterialRecipeDTO created = service.create(req("QATESTRO", "SymOld"));
        UUID id = created.id;

        // 篡改尝试：请求体把 code 改成 HACKED，同时正常改 symbol
        MaterialRecipeDTO updated = service.update(id, req("HACKED", "SymNew"));

        assertEquals("QATESTRO", updated.code, "材质编号只读，不随入参改");
        assertEquals("SymNew", updated.symbol, "其它字段(symbol)正常改并生效");

        // DB 复查：code 仍为原值、HACKED 不存在
        MaterialRecipe fromDb = MaterialRecipe.findById(id);
        assertEquals("QATESTRO", fromDb.code, "DB 里 code 仍为原值");
        assertNull(MaterialRecipe.find("code", "HACKED").firstResult(), "HACKED code 不应落库");
    }
}
