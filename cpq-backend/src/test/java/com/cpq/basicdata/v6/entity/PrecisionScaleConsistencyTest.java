package com.cpq.basicdata.v6.entity;

import com.cpq.configure.entity.MaterialRecipeElement;
import com.cpq.elementprice.strategy.ElementPriceStrategy;
import com.cpq.priceadjust.entity.ElementPriceVersionItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-0813 · T6：实体 {@code @Column(precision, scale)} ↔ DB {@code information_schema.columns}
 * 一致性反射测试（AC-2，长期防漂移）。
 *
 * <p>本任务的教训：scale 常量在代码里有多份独立副本（DB 列 / JPA 实体 / 导入 handler 字面量 /
 * {@code PricingSheetRegistry} 声明式镜像），漏改任一处都是静默失效——不报错、不失败，只是精度悄悄
 * 变短。本测试只堵其中一处漂移面（JPA 实体声明 vs DB 实际列类型），但价值是长期的：以后任何人改列
 * 精度忘了同步实体，这里会立刻 fail，而不是像 {@code ProductionEnergy.unit_price} 那样漂移了很久
 * 才被人工勘察发现（见 {@code 需求文档.md} §6.2）。
 *
 * <p><b>覆盖范围</b>：{@code v6/entity} 包下全部 22 个 {@code @Entity} 类 + 3 个非 v6 包实体
 * （{@link ElementPriceStrategy} / {@link MaterialRecipeElement} / {@link ElementPriceVersionItem}，
 * 均在 task-0813 需求文档 §3 目标类型表内）。
 *
 * <p><b>已知不在覆盖范围的 4 张表</b>：{@code element_daily_price} / {@code element_price} /
 * {@code plating_fee} / {@code exchange_rate}（非 v6）——这 4 张表在本代码库里<b>没有对应的 JPA
 * 实体</b>，全部只用 {@code EntityManager.createNativeQuery} 原生 SQL 访问（见
 * {@code PriceTableService} / {@code StrategyService}）。这是代码库现状，不是本测试遗漏；DB 列类型
 * 本身仍由 V386 迁移扩容，只是没有实体侧可供反射比对。
 *
 * <p>⚠️ 走 {@code test} profile（{@code 10.177.152.12:5432/cpq_db}），与 dev 库 {@code cpq_db_0724}
 * <b>不是同一个库</b>；本测试依赖 V386 迁移已在该库跑过——{@code quarkus.flyway.migrate-at-start=true}
 * （见 {@code application-test.properties}），Quarkus 测试启动时会自动触发，无需手工处理。
 */
@QuarkusTest
class PrecisionScaleConsistencyTest {

    @Inject
    EntityManager em;

    /** task-0813 §3 目标类型表覆盖的全部实体类（v6/entity 22 个 + 非 v6 3 个，逐一列出以避免反射扫包引入误差）。 */
    private static final List<Class<?>> ENTITIES = List.of(
        AnnualDiscount.class, AuxiliaryEnergy.class, Capacity.class, ElectricityPrice.class,
        ElementBom.class, ElementBomItem.class, Equipment.class, ExchangeRateV6.class,
        FeeConfig.class, LaborRate.class, MaterialBom.class, MaterialBomItem.class,
        MaterialCustomerMap.class, MaterialMaster.class, MaterialVersionMgmt.class,
        PackagingConsumable.class, PlatingScheme.class, ProcessMaster.class,
        ProductionConsumable.class, ProductionEnergy.class, ResourceGroup.class, ToolingCost.class,
        UnitPrice.class,
        ElementPriceStrategy.class, MaterialRecipeElement.class, ElementPriceVersionItem.class);

    private record Expectation(Class<?> entityClass, String fieldName, String table, String column,
                                int precision, int scale) {}

    @Test
    @DisplayName("task-0813 AC-2: 实体 @Column(precision,scale) 与 DB information_schema.columns 逐列一致")
    void entityPrecisionScaleMatchesDatabase() {
        List<Expectation> expectations = collectExpectations();
        // 防呆：反射收集逻辑本身若失效（比如 ENTITIES 列表被误清空），也不能让本测试假绿通过。
        assertTrue(expectations.size() >= 70,
            "预期至少 70+ 个带 precision/scale 声明的字段参与比对，实际只收集到 "
                + expectations.size() + " 个——反射收集逻辑本身可能有问题，请先排查再看比对结果");

        Map<String, int[]> dbByKey = fetchDbPrecisionScale(expectations);

        List<String> mismatches = new ArrayList<>();
        for (Expectation e : expectations) {
            String key = e.table() + "." + e.column();
            int[] db = dbByKey.get(key);
            if (db == null) {
                mismatches.add(String.format(
                    "%s#%s -> DB 列 %s 不存在（information_schema 未查到，检查表名/列名拼写或迁移是否已跑）",
                    e.entityClass().getSimpleName(), e.fieldName(), key));
                continue;
            }
            if (db[0] != e.precision() || db[1] != e.scale()) {
                mismatches.add(String.format(
                    "%s#%s -> DB %s: 实体声明 precision=%d,scale=%d，DB 实际 precision=%d,scale=%d",
                    e.entityClass().getSimpleName(), e.fieldName(), key,
                    e.precision(), e.scale(), db[0], db[1]));
            }
        }

        assertTrue(mismatches.isEmpty(),
            "实体 ↔ DB precision/scale 漂移（共 " + mismatches.size() + " 处）：\n"
                + String.join("\n", mismatches));
    }

    /**
     * task-0813 §6.2 已知漂移的定点回归：{@code ProductionEnergy.unit_price} 此前实体声明
     * {@code precision=18,scale=6}，落后于 DB 早已是的 {@code numeric(24,12)}。本测试单独钉住
     * 这两个具体字段，防止未来有人"顺手"改回旧值而被主测试的整体断言淹没在长列表里不易察觉。
     */
    @Test
    @DisplayName("task-0813 §6.2 定点回归: ProductionEnergy.unit_price / MaterialBomItem.{rough,net}Weight")
    void knownDriftColumnsStayFixed() throws NoSuchFieldException {
        Column unitPrice = ProductionEnergy.class.getDeclaredField("unitPrice").getAnnotation(Column.class);
        assertEquals(24, unitPrice.precision(), "ProductionEnergy.unitPrice precision 应为 24");
        assertEquals(12, unitPrice.scale(), "ProductionEnergy.unitPrice scale 应为 12（此前落后声明为 6）");

        Column roughWeight = MaterialBomItem.class.getDeclaredField("roughWeight").getAnnotation(Column.class);
        assertEquals(26, roughWeight.precision(), "MaterialBomItem.roughWeight precision 应为 26（此前落后声明为 18）");
        assertEquals(12, roughWeight.scale());

        Column netWeight = MaterialBomItem.class.getDeclaredField("netWeight").getAnnotation(Column.class);
        assertEquals(26, netWeight.precision(), "MaterialBomItem.netWeight precision 应为 26（此前落后声明为 18）");
        assertEquals(12, netWeight.scale());
    }

    private List<Expectation> collectExpectations() {
        List<Expectation> result = new ArrayList<>();
        for (Class<?> entityClass : ENTITIES) {
            Table tableAnn = entityClass.getAnnotation(Table.class);
            if (tableAnn == null || tableAnn.name().isBlank()) {
                throw new IllegalStateException(
                    entityClass.getName() + " 缺少 @Table(name=...)，无法定位对应 DB 表");
            }
            String table = tableAnn.name();
            for (Field field : entityClass.getDeclaredFields()) {
                Column col = field.getAnnotation(Column.class);
                if (col == null || col.precision() == 0) {
                    // precision()==0 是 jakarta.persistence.Column 的默认值，代表该字段没有显式
                    // 声明 precision/scale（不在本次 task-0813 §3 目标类型表范围内，跳过）。
                    continue;
                }
                String columnName = col.name().isBlank() ? field.getName() : col.name();
                result.add(new Expectation(entityClass, field.getName(), table, columnName,
                    col.precision(), col.scale()));
            }
        }
        return result;
    }

    /** 单条 SQL 批量取回所有涉及表的列元数据，避免按字段逐条查库（CLAUDE.md 严禁 N+1）。 */
    @SuppressWarnings("unchecked")
    private Map<String, int[]> fetchDbPrecisionScale(List<Expectation> expectations) {
        List<String> tables = expectations.stream().map(Expectation::table).distinct().toList();
        List<Object[]> rows = em.createNativeQuery(
                "SELECT table_name, column_name, numeric_precision, numeric_scale "
                    + "FROM information_schema.columns "
                    + "WHERE table_schema = current_schema() AND table_name = ANY(:tables)")
            .setParameter("tables", tables.toArray(new String[0]))
            .getResultList();
        Map<String, int[]> byKey = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String key = r[0] + "." + r[1];
            Integer precision = r[2] == null ? null : ((Number) r[2]).intValue();
            Integer scale = r[3] == null ? null : ((Number) r[3]).intValue();
            byKey.put(key, new int[]{precision == null ? -1 : precision, scale == null ? -1 : scale});
        }
        return byKey;
    }
}
