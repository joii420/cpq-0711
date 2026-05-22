package com.cpq.quotation.refdata;

import com.cpq.common.exception.BusinessException;
import com.cpq.globalvariable.GlobalVariableDefinition;
import com.cpq.globalvariable.GlobalVariableService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * V212: 全局变量全表数据加载器 (ADR-002 决策点 4 — 独立渲染路径).
 *
 * <p>职责: 给定 globalVariableCode, 加载完整的全表行数据 (rows[]) 用于前端"引用数据"Tab 展示.
 *
 * <p>隔离边界:
 * <ul>
 *   <li>不调用 ComponentDriverService (不进 driver/enrich 链路)</li>
 *   <li>不调用 useDriverExpansions (纯后端服务)</li>
 *   <li>不复用 enrichComponentData 机制</li>
 *   <li>GV 全表渲染无 lineItem 维度 / 无 RuntimeContext / 无谓词过滤</li>
 * </ul>
 *
 * <p>安全: source_view 经白名单正则 {@code ^[a-z][a-z0-9_]*$} 校验防 SQL 注入.
 * view 名在注册时由管理 UI 控制, 此处 defense-in-depth.
 */
@ApplicationScoped
public class GlobalVariableDataLoader {

    private static final Logger LOG = Logger.getLogger(GlobalVariableDataLoader.class);

    /**
     * 白名单正则: 合法视图/表名 (小写字母开头, 仅含小写字母/数字/下划线).
     * 与 GlobalVariableService.isIdent 对齐但更严格 (要求小写开头, 防止 system catalog 表名).
     */
    private static final Pattern VIEW_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    @Inject
    EntityManager em;

    @Inject
    GlobalVariableService globalVariableService;

    /**
     * 加载指定全局变量的全表行数据.
     *
     * <p>根据 def.varType:
     * <ul>
     *   <li>LOOKUP_TABLE + COSTING_VIEW: SELECT * FROM {source_view} LIMIT 500</li>
     *   <li>LOOKUP_TABLE + KV_TABLE: 从 global_variable_value 读所有行</li>
     *   <li>SCALAR: 返单行 [{value: resolvedValue}]</li>
     * </ul>
     *
     * @param globalVariableCode V104 global_variable_definition.code
     * @return 行列表; 每行是列名→值的 Map (LinkedHashMap 保持列顺序)
     * @throws BusinessException 400 当 code 不存在 / source_view 不合法
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<Map<String, Object>> loadAllRows(String globalVariableCode) {
        GlobalVariableDefinition def = globalVariableService.getByCode(globalVariableCode)
                .orElseThrow(() -> new BusinessException(400, "全局变量不存在: " + globalVariableCode));

        if (!def.isLookup()) {
            // SCALAR: 返单行 {value: ...}
            return loadScalarRow(def);
        }

        if (def.isKvTable()) {
            // KV_TABLE: 从 global_variable_value 单表读所有行
            return loadFromKvTable(def);
        }

        // COSTING_VIEW: SELECT * FROM source_view (经白名单校验)
        return loadFromCostingView(def);
    }

    /**
     * SCALAR 类型: 调 GlobalVariableService.resolveValue 取单值, 包成单行返回.
     */
    private List<Map<String, Object>> loadScalarRow(GlobalVariableDefinition def) {
        try {
            BigDecimal value = globalVariableService.resolveValue(def.code, Map.of());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("value", value);
            return List.of(row);
        } catch (Exception e) {
            LOG.warnf("[GlobalVariableDataLoader] SCALAR load failed for code=%s: %s", def.code, e.getMessage());
            return List.of();
        }
    }

    /**
     * KV_TABLE 模式: 从 global_variable_value 单表读所有行, 按 key_id 顺序.
     * 结果每行: key_columns 中各列 + valueColumn → value_number.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadFromKvTable(GlobalVariableDefinition def) {
        try {
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT key_id, key_values::text, value_number, value_text " +
                    "FROM global_variable_value WHERE var_code = :c ORDER BY key_id LIMIT 500")
                    .setParameter("c", def.code)
                    .getResultList();

            List<Map<String, Object>> out = new ArrayList<>(rows.size());
            for (Object[] r : rows) {
                Map<String, Object> row = new LinkedHashMap<>();
                // key_id 是便于检索的编码字段; 同时展开 key_values JSONB 便于前端渲染列
                row.put("key_id", r[0]);
                // 尝试展开 key_values JSON 字段到顶层 (让前端列表对齐 key_columns 顺序)
                String keyValuesJson = r[1] != null ? r[1].toString() : "{}";
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> kv = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(keyValuesJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
                    row.putAll(kv);
                } catch (Exception ignore) {
                    // 无法展开时保留 key_id 原值
                }
                // value_column
                String valueCol = def.valueColumn != null ? def.valueColumn : "value";
                Object value = r[2] != null ? r[2] : r[3];
                row.put(valueCol, value);
                out.add(row);
            }
            return out;
        } catch (Exception e) {
            LOG.warnf("[GlobalVariableDataLoader] KV_TABLE load failed for code=%s: %s", def.code, e.getMessage());
            return List.of();
        }
    }

    /**
     * COSTING_VIEW 模式: SELECT * FROM {source_view} LIMIT 500.
     *
     * <p>source_view 经 VIEW_NAME_PATTERN 白名单校验防 SQL 注入.
     * 结果行用 Hibernate Object[] 映射 (无法用 ALIAS_TO_ENTITY_MAP, 改用 metadata 推导列名).
     *
     * <p>注: Hibernate 原生查询 Object[] 无法自动携带列名. 改用
     * jakarta.persistence.Tuple 接口获取列名。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadFromCostingView(GlobalVariableDefinition def) {
        if (def.sourceView == null || def.sourceView.isBlank()) {
            LOG.warnf("[GlobalVariableDataLoader] COSTING_VIEW code=%s has no source_view", def.code);
            return List.of();
        }

        // 白名单校验 source_view 防 SQL 注入
        if (!VIEW_NAME_PATTERN.matcher(def.sourceView).matches()) {
            throw new BusinessException(500, "非法 source_view 名称: " + def.sourceView);
        }

        try {
            // 使用 Tuple 获取列名
            List<jakarta.persistence.Tuple> tuples = em.createNativeQuery(
                    "SELECT * FROM " + def.sourceView + " LIMIT 500",
                    jakarta.persistence.Tuple.class)
                    .getResultList();

            List<Map<String, Object>> out = new ArrayList<>(tuples.size());
            for (jakarta.persistence.Tuple tuple : tuples) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (jakarta.persistence.TupleElement<?> elem : tuple.getElements()) {
                    row.put(elem.getAlias(), tuple.get(elem));
                }
                out.add(row);
            }
            return out;
        } catch (Exception e) {
            LOG.warnf("[GlobalVariableDataLoader] COSTING_VIEW load failed for code=%s view=%s: %s",
                    def.code, def.sourceView, e.getMessage());
            return List.of();
        }
    }

    /**
     * 构建列顺序列表 (key_columns + [value_column]), 用于前端渲染列头.
     * SCALAR 类型固定返回 ["value"].
     */
    public List<String> buildColumns(GlobalVariableDefinition def) {
        if (!def.isLookup()) {
            return List.of("value");
        }
        List<String> cols = new ArrayList<>();
        if (def.keyColumns != null) {
            cols.addAll(def.keyColumns);
        }
        if (def.valueColumn != null && !def.valueColumn.isBlank()) {
            cols.add(def.valueColumn);
        }
        return cols;
    }
}
