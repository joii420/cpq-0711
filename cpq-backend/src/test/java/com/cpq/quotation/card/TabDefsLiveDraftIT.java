package com.cpq.quotation.card;

import com.cpq.quotation.service.ExcelViewService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归：草稿模板 componentsSnapshot=NULL 时，tabDefsOfTemplate 必须从实时 template_component 关联
 * 构建页签定义（否则构建器报"暂无页签定义数据"）。
 *
 * <p>造数：DRAFT 模板（components_snapshot 不写=NULL）+ 1 个 component（含 fields/row_key_fields）
 * + 1 条 template_component 关联。断言 tabDefsOfTemplate 返回该页签 + alias/tabKey/rowKeyFields/subtotalCols。
 */
@QuarkusTest
class TabDefsLiveDraftIT {

    @Inject ExcelViewService excelViewService;
    @Inject EntityManager em;

    @Test
    @TestTransaction
    void draft_template_tab_defs_built_from_live_components() {
        UUID compId = UUID.randomUUID();
        String code = "ITTD" + compId.toString().replace("-", "").substring(0, 8);
        // component：name/code 必填；fields 含一个 is_subtotal 列；row_key_fields=["料件"]
        em.createNativeQuery("""
                INSERT INTO component (id, name, code, component_type, fields, row_key_fields, created_at, updated_at)
                VALUES (?1, '来料IT', ?2, 'NORMAL',
                        CAST(?3 AS jsonb), CAST(?4 AS jsonb), now(), now())
                """)
          .setParameter(1, compId)
          .setParameter(2, code)
          .setParameter(3, "[{\"name\":\"料件\"},{\"name\":\"金额\",\"is_subtotal\":true}]")
          .setParameter(4, "[\"料件\"]")
          .executeUpdate();

        // template：DRAFT，不写 components_snapshot → NULL
        UUID tmplId = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO template (id, template_series_id, name, status, formulas,
                                      template_sql_views_snapshot, created_at, updated_at)
                VALUES (?1, ?2, 'IT草稿模板-TabDefs', 'DRAFT', '[]', '{}', now(), now())
                """)
          .setParameter(1, tmplId)
          .setParameter(2, UUID.randomUUID())
          .executeUpdate();

        // template_component：tab_name=来料, sort_order=0
        em.createNativeQuery("""
                INSERT INTO template_component (id, template_id, component_id, tab_name, sort_order, created_at)
                VALUES (?1, ?2, ?3, '来料', 0, now())
                """)
          .setParameter(1, UUID.randomUUID())
          .setParameter(2, tmplId)
          .setParameter(3, compId)
          .executeUpdate();

        em.flush();

        List<Map<String, Object>> defs = excelViewService.tabDefsOfTemplate(tmplId);

        assertEquals(1, defs.size(), "草稿模板应从实时关联构建出 1 个页签定义");
        Map<String, Object> def = defs.get(0);
        assertEquals("来料", def.get("alias"));
        assertEquals(compId + ":0", def.get("tabKey"));
        assertEquals(List.of("料件"), def.get("rowKeyFields"));
        @SuppressWarnings("unchecked")
        List<String> detailFields = (List<String>) def.get("detailFields");
        assertTrue(detailFields.contains("料件") && detailFields.contains("金额"), "detailFields 应含 料件/金额");
        @SuppressWarnings("unchecked")
        List<String> subtotalCols = (List<String>) def.get("subtotalCols");
        assertTrue(subtotalCols.contains("金额"), "金额 应识别为小计列");
    }
}
