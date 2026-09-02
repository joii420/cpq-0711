package com.cpq.configure.service;

import com.cpq.configure.dto.MaterialRecipeDTO;
import com.cpq.configure.entity.MaterialRecipe;
import com.cpq.configure.entity.MaterialRecipeComposition;
import com.cpq.configure.entity.MaterialRecipeConfig;
import com.cpq.configure.entity.MaterialRecipeElement;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * material_recipe 列表改造测试（task-0708 · B3）：全状态 + 关键字四路匹配 + 排序 + 时间字段。
 */
@QuarkusTest
public class MaterialRecipeListSearchTest {

    @Inject
    MaterialRecipeService service;

    private UUID seedRecipe(String code, String symbol, String status) {
        MaterialRecipe r = new MaterialRecipe();
        r.code = code;
        r.symbol = symbol;
        r.name = null;
        r.recipeType = "locked";
        r.status = status;
        r.sortOrder = 0;
        r.createdAt = OffsetDateTime.now();
        r.updatedAt = OffsetDateTime.now();
        r.persist();
        return r.id;
    }

    /**
     * task-260901：元素行归属改成「配置」，材质的元素<b>组成</b>另有一张表。
     * 列表搜索按元素符号/中文名命中的来源已从 material_recipe_element 换成
     * material_recipe_composition（BC-2b：0 配置的材质也要能搜到），故这里两张表都种。
     */
    private void seedElement(UUID recipeId, String code, String name, String pct) {
        MaterialRecipeConfig cfg = new MaterialRecipeConfig();
        cfg.recipeId = recipeId;
        cfg.seq = 1;
        cfg.configNo = "LT-" + recipeId.toString().substring(0, 8) + "-01";
        cfg.status = "ACTIVE";
        cfg.sortOrder = 1;
        cfg.createdAt = OffsetDateTime.now();
        cfg.updatedAt = OffsetDateTime.now();
        cfg.persist();

        MaterialRecipeElement e = new MaterialRecipeElement();
        e.configId = cfg.id;
        e.elementNo = code;
        e.elementCode = code;
        e.elementName = name;
        e.defaultPct = new BigDecimal(pct);
        e.isLocked = true;
        e.sortOrder = 1;
        e.createdAt = OffsetDateTime.now();
        e.persist();

        MaterialRecipeComposition c = new MaterialRecipeComposition();
        c.recipeId = recipeId;
        c.elementNo = code;
        c.elementCode = code;
        c.elementName = name;
        c.sortOrder = 1;
        c.createdAt = OffsetDateTime.now();
        c.persist();
    }

    @Test
    @TestTransaction
    void list_returnsAllStatuses_activeBeforeInactive_withTimestamps() {
        seedRecipe("LTACT01", "AgActive", "ACTIVE");
        seedRecipe("LTINA01", "AgInactive", "INACTIVE");

        List<MaterialRecipeDTO> all = service.list(null, false);
        List<String> codes = all.stream().map(d -> d.code).collect(Collectors.toList());
        assertTrue(codes.contains("LTACT01"), "启用项在列表");
        assertTrue(codes.contains("LTINA01"), "停用项也在列表(全状态)");
        assertTrue(codes.indexOf("LTACT01") < codes.indexOf("LTINA01"), "启用优先于停用");

        MaterialRecipeDTO act = all.stream().filter(d -> "LTACT01".equals(d.code)).findFirst().orElseThrow();
        assertNotNull(act.createdAt, "createdAt 非空");
        assertNotNull(act.updatedAt, "updatedAt 非空");
    }

    @Test
    @TestTransaction
    void list_keyword_matchesCode_symbol_elementCode_elementName() {
        UUID id = seedRecipe("LTKW999", "AgKwSymbol", "ACTIVE");
        seedElement(id, "Ag", "银", "100");

        assertTrue(hasCode(service.list("LTKW999", false), "LTKW999"), "按材质编号命中");
        assertTrue(hasCode(service.list("AgKwSymbol", false), "LTKW999"), "按材质名称命中");
        assertTrue(hasCode(service.list("银", false), "LTKW999"), "按元素中文名命中");
        assertTrue(hasCode(service.list("Ag", false), "LTKW999"), "按元素符号命中");
        assertFalse(hasCode(service.list("ZZ_NO_MATCH_KW", false), "LTKW999"), "无关关键字不命中");
    }

    private boolean hasCode(List<MaterialRecipeDTO> list, String code) {
        return list.stream().anyMatch(d -> code.equals(d.code));
    }
}
