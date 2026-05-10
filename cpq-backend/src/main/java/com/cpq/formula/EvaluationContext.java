package com.cpq.formula;

import com.cpq.datapath.sql.SchemaContext;
import com.cpq.formula.dataloader.DataLoader;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 公式求值上下文，贯穿单次 evaluate 调用的生命周期。
 *
 * <p>包含：
 * <ul>
 *   <li>customer_id — 当前客户 UUID（业务函数如 TAX_INCLUDED 使用）</li>
 *   <li>partNo — 当前料号（路径查询时注入 :partNo 参数）</li>
 *   <li>dataLoader — 请求级 DataLoader（同路径 dedupe）</li>
 *   <li>schemaContext — 路径解析用 Schema 映射</li>
 *   <li>bindings — 行级变量（如 row_data 中的字段值）</li>
 * </ul>
 */
public class EvaluationContext {

    private final UUID customerId;
    private final String partNo;
    private final DataLoader dataLoader;
    private final SchemaContext schemaContext;
    private final Map<String, Object> bindings;
    /** Y1.5 行驱动行(可空) — 字段路径求值时自动隐式 JOIN 注入这些列。 */
    private final Map<String, Object> driverRow;

    private EvaluationContext(Builder builder) {
        this.customerId    = builder.customerId;
        this.partNo        = builder.partNo;
        this.dataLoader    = builder.dataLoader;
        this.schemaContext = builder.schemaContext != null
                             ? builder.schemaContext
                             : SchemaContext.defaultContext();
        // 不用 Map.copyOf(immutable):driver 行常含 null 值(可空列),copyOf 会 NPE
        this.bindings      = builder.bindings.isEmpty()
                             ? Map.of()
                             : Collections.unmodifiableMap(new LinkedHashMap<>(builder.bindings));
        this.driverRow     = builder.driverRow == null
                             ? null
                             : Collections.unmodifiableMap(new LinkedHashMap<>(builder.driverRow));
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getPartNo() {
        return partNo;
    }

    public DataLoader getDataLoader() {
        return dataLoader;
    }

    public SchemaContext getSchemaContext() {
        return schemaContext;
    }

    /** 按名称获取行级变量（来自 row_data 或手动绑定）。 */
    public Object getBinding(String name) {
        return bindings.get(name);
    }

    public Map<String, Object> getBindings() {
        return bindings;
    }

    /** Y1.5 行驱动行(可空)。 */
    public Map<String, Object> getDriverRow() {
        return driverRow;
    }

    // ── Builder ───────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID customerId;
        private String partNo;
        private DataLoader dataLoader;
        private SchemaContext schemaContext;
        private final Map<String, Object> bindings = new HashMap<>();
        private Map<String, Object> driverRow;

        public Builder customerId(UUID customerId) {
            this.customerId = customerId;
            return this;
        }

        public Builder partNo(String partNo) {
            this.partNo = partNo;
            return this;
        }

        public Builder dataLoader(DataLoader dataLoader) {
            this.dataLoader = dataLoader;
            return this;
        }

        public Builder schemaContext(SchemaContext schemaContext) {
            this.schemaContext = schemaContext;
            return this;
        }

        public Builder binding(String name, Object value) {
            this.bindings.put(name, value);
            return this;
        }

        public Builder bindings(Map<String, Object> bindings) {
            if (bindings != null) this.bindings.putAll(bindings);
            return this;
        }

        /** Y1.5: 设置 driver 行(用于字段路径隐式 JOIN)。 */
        public Builder driverRow(Map<String, Object> driverRow) {
            this.driverRow = driverRow;
            return this;
        }

        public EvaluationContext build() {
            return new EvaluationContext(this);
        }
    }
}
