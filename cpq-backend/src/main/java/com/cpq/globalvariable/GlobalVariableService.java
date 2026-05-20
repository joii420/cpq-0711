package com.cpq.globalvariable;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.formula.resource.FormulaEvalCache;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * V104 全局变量服务. 三类职责:
 *
 * <ol>
 *   <li>注册表 CRUD (P1 仅 read; 后续阶段补 write)</li>
 *   <li>给定 code 把 token 编译成 BNF path 字符串 (供公式 resolver 复用 path token 流水线)</li>
 *   <li>给定 code+key 直接返值 (P1 后端 evaluate 用; 前端公式选择器列出可选 key 用)</li>
 * </ol>
 *
 * <p>view 名/列名都是白名单 (来自 global_variable_definition 行), 不接受外部传入,
 * 因此原生 SQL 拼接是安全的; key 值走参数绑定。
 */
@ApplicationScoped
public class GlobalVariableService {

    private static final Logger LOG = Logger.getLogger(GlobalVariableService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    @Inject
    ComponentDriverService componentDriverService;

    // ───────────── 注册表 ─────────────

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<GlobalVariableDefinition> listAll() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT code, name, var_type, source_view, key_columns::text, value_column, " +
                "       label_template, unit, description, sort_order, is_active, updated_at, " +
                "       value_source_type, visibility " +
                "FROM global_variable_definition " +
                "WHERE is_active = true " +
                "ORDER BY sort_order, code")
                .getResultList();
        List<GlobalVariableDefinition> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) out.add(rowToDef(r));
        return out;
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<GlobalVariableDefinition> getByCode(String code) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT code, name, var_type, source_view, key_columns::text, value_column, " +
                "       label_template, unit, description, sort_order, is_active, updated_at, " +
                "       value_source_type, visibility " +
                "FROM global_variable_definition WHERE code = :c")
                .setParameter("c", code)
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rowToDef(rows.get(0)));
    }

    /**
     * 按中文业务名（name 字段）查找变量定义。
     * 用于 @变量名 解析中按中文名匹配（Stage 2 TemplateFormulaService 调用）。
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<GlobalVariableDefinition> getByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT code, name, var_type, source_view, key_columns::text, value_column, " +
                "       label_template, unit, description, sort_order, is_active, updated_at, " +
                "       value_source_type, visibility " +
                "FROM global_variable_definition WHERE name = :n AND is_active = true LIMIT 1")
                .setParameter("n", name)
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rowToDef(rows.get(0)));
    }

    private GlobalVariableDefinition rowToDef(Object[] r) {
        GlobalVariableDefinition d = new GlobalVariableDefinition();
        d.code         = (String) r[0];
        d.name         = (String) r[1];
        d.varType      = (String) r[2];
        d.sourceView   = (String) r[3];
        try {
            d.keyColumns = MAPPER.readValue((String) r[4], new TypeReference<List<String>>() {});
        } catch (Exception e) {
            d.keyColumns = List.of();
        }
        d.valueColumn  = (String) r[5];
        d.labelTemplate = (String) r[6];
        d.unit          = (String) r[7];
        d.description   = (String) r[8];
        d.sortOrder     = r[9]  != null ? ((Number) r[9]).intValue() : 0;
        d.isActive      = r[10] != null ? (Boolean) r[10] : Boolean.TRUE;
        d.updatedAt     = r[11] instanceof java.sql.Timestamp ts
                ? ts.toInstant().atOffset(ZoneOffset.UTC) : null;
        d.valueSourceType = r.length > 12 && r[12] != null ? (String) r[12] : "KV_TABLE";
        d.visibility      = r.length > 13 && r[13] != null ? (String) r[13] : "PUBLIC";
        return d;
    }

    // ───────────── 候选 key 列表 (供 UI 公式选择器) ─────────────

    /**
     * 列出当前 global var 的全部候选 key. 返回 List<Map> 含每键列原值 + 拼接显示标签。
     * 返回行数受 limit 截断 (默认 1000)。
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<Map<String, Object>> listKeys(String code, int limit) {
        GlobalVariableDefinition def = getByCode(code)
                .orElseThrow(() -> new BusinessException(404, "全局变量未注册: " + code));
        if (!def.isLookup()) {
            throw new BusinessException(400, "SCALAR 类型变量无 key 列表");
        }
        if (def.keyColumns.isEmpty()) {
            return List.of();
        }
        if (def.isKvTable()) {
            return listKeysFromKvTable(def, limit);
        }
        // COSTING_VIEW: 沿用历史路径查 source_view
        validateIdentifiers(def);
        return listKeysFromView(def, limit);
    }

    /** V188: KV_TABLE 模式 — 从 global_variable_value 单表查候选 key */
    private List<Map<String, Object>> listKeysFromKvTable(GlobalVariableDefinition def, int limit) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT key_id, key_values::text, value_number, value_text " +
                "FROM global_variable_value WHERE var_code = :c ORDER BY key_id LIMIT :lim")
                .setParameter("c", def.code)
                .setParameter("lim", limit)
                .getResultList();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            Map<String, Object> keyValues;
            try {
                keyValues = MAPPER.readValue((String) r[1], new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                keyValues = Map.of();
            }
            item.put("key_values", keyValues);
            item.put("value", r[2] != null ? r[2] : r[3]);
            item.put("label", buildLabel(def, keyValues));
            out.add(item);
        }
        return out;
    }

    /** COSTING_VIEW 模式 — 历史路径, 查 source_view */
    private List<Map<String, Object>> listKeysFromView(GlobalVariableDefinition def, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < def.keyColumns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(def.keyColumns.get(i));
        }
        sql.append(", ").append(def.valueColumn);
        sql.append(" FROM ").append(def.sourceView);
        sql.append(" ORDER BY ");
        for (int i = 0; i < def.keyColumns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(def.keyColumns.get(i));
        }
        sql.append(" LIMIT :lim");

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql.toString())
                .setParameter("lim", limit)
                .getResultList();

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            Map<String, Object> keyValues = new LinkedHashMap<>();
            for (int i = 0; i < def.keyColumns.size(); i++) {
                keyValues.put(def.keyColumns.get(i), r[i]);
            }
            item.put("key_values", keyValues);
            item.put("value", r[def.keyColumns.size()]);
            item.put("label", buildLabel(def, keyValues));
            out.add(item);
        }
        return out;
    }

    private String buildLabel(GlobalVariableDefinition def, Map<String, Object> keyValues) {
        // Simple: join all key values with ":"
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object v : keyValues.values()) {
            if (!first) sb.append(":");
            sb.append(v == null ? "" : v.toString());
            first = false;
        }
        return sb.toString();
    }

    // ───────────── 取值 (后端 evaluate 流水线核心) ─────────────

    /**
     * 给定 code + key 解出标量值. 静态调用入口 (后端 calculate API 用)。
     *
     * @param code      变量 code
     * @param keyValues key 列名 → 字符串值 (单键 size=1, 复合键>1)
     * @return 数值, 无匹配行返 null (调用方按业务决定 0 还是异常)
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public BigDecimal resolveValue(String code, Map<String, Object> keyValues) {
        GlobalVariableDefinition def = getByCode(code)
                .orElseThrow(() -> new BusinessException(404, "全局变量未注册: " + code));
        // G1: SCALAR 直接查单表 key_id='_' (核价 3 张都是 LOOKUP, 不会触达 SCALAR 分支)
        if (!def.isLookup()) {
            return readKvValue(code, "_");
        }
        if (keyValues == null || keyValues.size() != def.keyColumns.size()) {
            throw new BusinessException(400,
                    "key 数量不匹配: 期望 " + def.keyColumns.size() + ", 实得 " + (keyValues == null ? 0 : keyValues.size()));
        }
        return def.isKvTable()
                ? resolveValueFromKvTable(def, keyValues)
                : resolveValueFromView(def, keyValues);
    }

    /** V188: KV_TABLE 模式 — 查 global_variable_value 单表 */
    private BigDecimal resolveValueFromKvTable(GlobalVariableDefinition def,
                                               Map<String, Object> keyValues) {
        String keyId = buildKeyIdForKvTable(def, keyValues);
        @SuppressWarnings("unchecked")
        List<Object> rs = em.createNativeQuery(
                "SELECT value_number FROM global_variable_value " +
                "WHERE var_code = :c AND key_id = :k LIMIT 1")
                .setParameter("c", def.code)
                .setParameter("k", keyId)
                .getResultList();
        if (rs.isEmpty() || rs.get(0) == null) return null;
        Object v = rs.get(0);
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }

    /** COSTING_VIEW 模式 — 历史路径, 查 source_view */
    private BigDecimal resolveValueFromView(GlobalVariableDefinition def,
                                            Map<String, Object> keyValues) {
        validateIdentifiers(def);
        StringBuilder sql = new StringBuilder("SELECT ").append(def.valueColumn);
        sql.append(" FROM ").append(def.sourceView);
        sql.append(" WHERE ");
        Map<String, Object> bind = new HashMap<>();
        for (int i = 0; i < def.keyColumns.size(); i++) {
            String col = def.keyColumns.get(i);
            if (!keyValues.containsKey(col)) {
                throw new BusinessException(400, "缺少 key 列: " + col);
            }
            if (i > 0) sql.append(" AND ");
            sql.append(col).append(" = :p").append(i);
            bind.put("p" + i, keyValues.get(col));
        }
        sql.append(" LIMIT 1");

        var query = em.createNativeQuery(sql.toString());
        bind.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object> rs = query.getResultList();
        if (rs.isEmpty() || rs.get(0) == null) return null;
        Object v = rs.get(0);
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }

    /**
     * KV_TABLE 单表的 key_id 编码: 单 key 用值本身; 复合 key 按 keyColumns 顺序拼 ':'.
     * 与 ETL 阶段 V188 写入的 key_id 形态对齐 (单 key=process_code 直接存 'Z350').
     */
    private static String buildKeyIdForKvTable(GlobalVariableDefinition def,
                                               Map<String, Object> keyValues) {
        if (def.keyColumns.size() == 1) {
            Object v = keyValues.get(def.keyColumns.get(0));
            return v == null ? "" : v.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < def.keyColumns.size(); i++) {
            if (i > 0) sb.append(':');
            Object v = keyValues.get(def.keyColumns.get(i));
            sb.append(v == null ? "" : v.toString());
        }
        return sb.toString();
    }

    // ───────────── 编译 token → BNF path (公式 resolver 复用 path 流水线) ─────────────

    /**
     * 把 global_variable token 编译成 BNF path 字符串.
     *
     * @param token 必含 code; 可选 key_values (静态) 或 key_field_refs (动态, 已替换为字面值)
     * @return BNF path 形如 v_costing_element_price[element_code='Cu'].costing_price
     */
    public String compileToBnfPath(Map<String, Object> token, Map<String, Object> rowData) {
        String code = (String) token.get("code");
        if (code == null || code.isBlank()) {
            throw new BusinessException(400, "global_variable token 缺少 code");
        }
        GlobalVariableDefinition def = getByCode(code)
                .orElseThrow(() -> new BusinessException(404, "全局变量未注册: " + code));
        if (!def.isLookup()) {
            throw new BusinessException(400, "SCALAR 类型暂未实现 BNF 编译");
        }
        // V188: KV_TABLE 模式不再编译成 BNF path — 路径求值层无法直接读单表;
        // 公式应改成在 evaluate 阶段调 resolveValue. 这里返 null, 上游识别 KV_TABLE 走新路径.
        if (def.isKvTable()) {
            return null;
        }
        validateIdentifiers(def);

        // 解析 key: 静态优先, 动态 fallback
        @SuppressWarnings("unchecked")
        Map<String, Object> staticKeys = (Map<String, Object>) token.get("key_values");
        @SuppressWarnings("unchecked")
        Map<String, Object> dynRefs    = (Map<String, Object>) token.get("key_field_refs");

        StringBuilder sb = new StringBuilder(def.sourceView);
        sb.append('[');
        for (int i = 0; i < def.keyColumns.size(); i++) {
            String col = def.keyColumns.get(i);
            Object v = null;
            if (staticKeys != null && staticKeys.containsKey(col)) {
                v = staticKeys.get(col);
            } else if (dynRefs != null && dynRefs.containsKey(col) && rowData != null) {
                v = rowData.get(dynRefs.get(col));
            }
            if (v == null) {
                throw new BusinessException(400, "global_variable[" + code + "] 缺少 key: " + col);
            }
            if (i > 0) sb.append(" AND ");
            sb.append(col).append("=").append(literal(v));
        }
        sb.append("].");
        sb.append(def.valueColumn);
        return sb.toString();
    }

    private static String literal(Object v) {
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return "'" + String.valueOf(v).replace("'", "''") + "'";
    }

    /**
     * 校验 def 的标识符全部是合法物理名 (字母/数字/下划线), 防注入.
     * 表/列名来自注册表行, 入库时由管理 UI/API 校验, 此处兜底。
     */
    private static void validateIdentifiers(GlobalVariableDefinition def) {
        if (!isIdent(def.sourceView)) {
            throw new BusinessException(500, "非法 source_view: " + def.sourceView);
        }
        if (!isIdent(def.valueColumn)) {
            throw new BusinessException(500, "非法 value_column: " + def.valueColumn);
        }
        for (String col : def.keyColumns) {
            if (!isIdent(col)) {
                throw new BusinessException(500, "非法 key_column: " + col);
            }
        }
    }

    private static boolean isIdent(String s) {
        if (s == null || s.isBlank()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    // ───────────── V106: CRUD + 变更日志 (P2) ─────────────

    // V188: PHYSICAL Map 已删除 — 所有 KV_TABLE 变量统一走 global_variable_value 单表;
    // 核价 3 张 (ELEM_PRICE/MAT_PRICE/EXCHANGE_RATE) 标记 COSTING_VIEW 拒绝写,
    // 维护数据请到核价模块页面.

    /**
     * 创建或更新一条全局变量明细行 (写入当前默认 PUBLISHED 版本).
     * 已存在 → UPDATE 取值列; 不存在 → INSERT.
     * 同步落一条变更日志 (action=INSERT/UPDATE).
     *
     * @param code        变量 code
     * @param keyValues   key 列 → 字面值
     * @param newValue    新取值
     * @param note        变更说明 (可空)
     * @param actorId     操作用户 id (可空)
     * @param actorName   操作用户姓名 (可空)
     */
    @Transactional
    public BigDecimal upsertEntry(String code, Map<String, Object> keyValues, BigDecimal newValue,
                                  String note, UUID actorId, String actorName) {
        GlobalVariableDefinition def = getByCode(code)
                .orElseThrow(() -> new BusinessException(404, "未注册: " + code));
        if (newValue == null) throw new BusinessException(400, "新值不能为空");
        if (def.isCostingView()) {
            throw new BusinessException(400, "核价价格(" + code + ")请到核价模块页面维护, 全局变量管理不接受写入");
        }
        // G1: SCALAR 形态 — keyValues 期望为空; 单表 key_id='_' 占位
        if (!def.isLookup()) {
            keyValues = keyValues == null ? Map.of() : keyValues;
        } else {
            validateKeyValues(def, keyValues);
        }
        // KV_TABLE 模式 — 统一写 global_variable_value 单表
        String keyId = def.isLookup() ? buildKeyIdForKvTable(def, keyValues) : "_";
        BigDecimal oldValue = readKvValue(code, keyId);

        boolean changed = false;
        if (oldValue == null) {
            insertKvRow(code, keyId, keyValues, newValue);
            writeChangeLog(code, buildKeyId(keyValues), "INSERT", null, newValue, note, actorId, actorName);
            changed = true;
        } else if (oldValue.compareTo(newValue) != 0) {
            updateKvRow(code, keyId, newValue);
            writeChangeLog(code, buildKeyId(keyValues), "UPDATE", oldValue, newValue, note, actorId, actorName);
            changed = true;
        }
        if (changed) invalidateDependentCaches(code);
        return newValue;
    }

    /**
     * 删除一条明细行. 同步落 DELETE 日志.
     */
    @Transactional
    public void deleteEntry(String code, Map<String, Object> keyValues,
                            String note, UUID actorId, String actorName) {
        GlobalVariableDefinition def = getByCode(code)
                .orElseThrow(() -> new BusinessException(404, "未注册: " + code));
        if (def.isCostingView()) {
            throw new BusinessException(400, "核价价格(" + code + ")请到核价模块页面维护, 全局变量管理不接受删除");
        }
        if (def.isLookup()) {
            validateKeyValues(def, keyValues);
        } else {
            keyValues = keyValues == null ? Map.of() : keyValues;
        }
        String keyId = def.isLookup() ? buildKeyIdForKvTable(def, keyValues) : "_";
        BigDecimal oldValue = readKvValue(code, keyId);
        if (oldValue == null) return;  // 幂等
        deleteKvRow(code, keyId);
        writeChangeLog(code, buildKeyId(keyValues), "DELETE", oldValue, null, note, actorId, actorName);
        invalidateDependentCaches(code);
    }

    // ───────────── V188: KV_TABLE 单表 CRUD helpers ─────────────

    private BigDecimal readKvValue(String code, String keyId) {
        @SuppressWarnings("unchecked")
        List<Object> rs = em.createNativeQuery(
                "SELECT value_number FROM global_variable_value " +
                "WHERE var_code = :c AND key_id = :k LIMIT 1")
                .setParameter("c", code).setParameter("k", keyId)
                .getResultList();
        if (rs.isEmpty() || rs.get(0) == null) return null;
        Object v = rs.get(0);
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }

    private void insertKvRow(String code, String keyId, Map<String, Object> keyValues, BigDecimal newValue) {
        String keyValuesJson;
        try {
            keyValuesJson = MAPPER.writeValueAsString(keyValues);
        } catch (Exception e) {
            keyValuesJson = "{}";
        }
        em.createNativeQuery(
                "INSERT INTO global_variable_value (var_code, key_id, key_values, value_number) " +
                "VALUES (:c, :k, CAST(:kv AS jsonb), :v)")
                .setParameter("c", code).setParameter("k", keyId)
                .setParameter("kv", keyValuesJson).setParameter("v", newValue)
                .executeUpdate();
    }

    private void updateKvRow(String code, String keyId, BigDecimal newValue) {
        em.createNativeQuery(
                "UPDATE global_variable_value SET value_number = :v, updated_at = NOW() " +
                "WHERE var_code = :c AND key_id = :k")
                .setParameter("c", code).setParameter("k", keyId).setParameter("v", newValue)
                .executeUpdate();
    }

    private void deleteKvRow(String code, String keyId) {
        em.createNativeQuery(
                "DELETE FROM global_variable_value WHERE var_code = :c AND key_id = :k")
                .setParameter("c", code).setParameter("k", keyId)
                .executeUpdate();
    }

    /**
     * 全局变量条目写入后, 失效所有依赖该值的求值缓存.
     *
     * <p>触发场景: upsertEntry / deleteEntry 成功后. 不失效会让 30s 内 / 重启前的
     * 公式求值 + driver expand 继续命中旧值, UI placeholder / 列单价仍显示旧默认.
     *
     * <p>当前粗粒度 evictAll — 任意全局变量变更清空全部公式 / driver 缓存. 量级:
     * FormulaEvalCache 上限 10000 条目, expandCache 上限 N 万 (Caffeine 默认). 后续若
     * 需要细化, 可按 code → expression 反向索引精准失效, 但 30s TTL 下粗粒度损失有限.
     */
    private void invalidateDependentCaches(String code) {
        try {
            FormulaEvalCache.evictAll();
            componentDriverService.evictAll();
            LOG.infof("[gvar cache evict] triggered by code=%s — formula+expand caches cleared", code);
        } catch (Exception e) {
            // 缓存失效失败不应阻断业务写入; 仅记录警告 (业务 TX 已提交)
            LOG.warnf("[gvar cache evict] failed to evict caches after code=%s: %s", code, e.getMessage());
        }
    }

    /**
     * 列变更日志, 按 var_code (可空 = 全部) 过滤, 默认按 changed_at DESC.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<Map<String, Object>> listChangeLog(String code, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, var_code, key_id, action, old_value, new_value, " +
                "       changed_by, changed_by_name, note, changed_at " +
                "FROM global_variable_change_log");
        Map<String, Object> bind = new HashMap<>();
        if (code != null && !code.isBlank()) {
            sql.append(" WHERE var_code = :c");
            bind.put("c", code);
        }
        sql.append(" ORDER BY changed_at DESC LIMIT :lim");
        bind.put("lim", Math.min(limit, 500));

        var query = em.createNativeQuery(sql.toString());
        bind.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0] != null ? r[0].toString() : null);
            m.put("varCode", r[1]);
            m.put("keyId", r[2]);
            m.put("action", r[3]);
            m.put("oldValue", r[4]);
            m.put("newValue", r[5]);
            m.put("changedBy", r[6] != null ? r[6].toString() : null);
            m.put("changedByName", r[7]);
            m.put("note", r[8]);
            m.put("changedAt", r[9] != null ? r[9].toString() : null);
            out.add(m);
        }
        return out;
    }

    // ── 私有工具 ─────────────────────────────────────────────

    private void validateKeyValues(GlobalVariableDefinition def, Map<String, Object> keyValues) {
        if (keyValues == null || keyValues.size() != def.keyColumns.size()) {
            throw new BusinessException(400,
                    "key 数量不匹配: 期望 " + def.keyColumns.size() + ", 实得 " + (keyValues == null ? 0 : keyValues.size()));
        }
        for (String col : def.keyColumns) {
            if (!keyValues.containsKey(col) || keyValues.get(col) == null) {
                throw new BusinessException(400, "缺少 key 列: " + col);
            }
        }
    }

    // V188: 旧的 versioned/flat 双形态 SQL helper (currentDefaultVersionId / readSingleValue /
    // insertRow / updateRow / deleteRow) 已删除 — 全部由 KV_TABLE 路径 (readKvValue /
    // insertKvRow / updateKvRow / deleteKvRow) 替代; 核价 3 张通过 isCostingView 分支拒绝写.

    private void writeChangeLog(String code, String keyId, String action,
                                BigDecimal oldValue, BigDecimal newValue,
                                String note, UUID actorId, String actorName) {
        em.createNativeQuery(
                "INSERT INTO global_variable_change_log " +
                "  (var_code, key_id, action, old_value, new_value, changed_by, changed_by_name, note) " +
                "VALUES (:code, :key, :act, :ov, :nv, :uid, :uname, :note)")
                .setParameter("code", code)
                .setParameter("key", keyId)
                .setParameter("act", action)
                .setParameter("ov", oldValue)
                .setParameter("nv", newValue)
                .setParameter("uid", actorId)
                .setParameter("uname", actorName)
                .setParameter("note", note)
                .executeUpdate();
    }

    /**
     * G1: 新建全局变量定义 (PRICING_MANAGER+). 仅 KV_TABLE + PUBLIC, 自动生成: code 唯一,
     * value_source_type=KV_TABLE, visibility=PUBLIC. 不接受核价 3 张占位的 COSTING_VIEW
     * 形态创建 — 那些只能由 V104 / Flyway 初始化.
     */
    @Transactional
    public GlobalVariableDefinition createDefinition(GlobalVariableDefinition req) {
        if (req == null || req.code == null || req.code.isBlank()) {
            throw new BusinessException(400, "code 必填");
        }
        if (req.name == null || req.name.isBlank()) {
            throw new BusinessException(400, "name 必填");
        }
        if (!"LOOKUP_TABLE".equals(req.varType) && !"SCALAR".equals(req.varType)) {
            throw new BusinessException(400, "var_type 必填且 ∈ {LOOKUP_TABLE, SCALAR}");
        }
        if ("LOOKUP_TABLE".equals(req.varType)
                && (req.keyColumns == null || req.keyColumns.isEmpty())) {
            throw new BusinessException(400, "LOOKUP_TABLE 必须提供 keyColumns");
        }
        // code 唯一性检查
        if (getByCode(req.code).isPresent()) {
            throw new BusinessException(409, "变量已存在: " + req.code);
        }
        // 强制 KV_TABLE + PUBLIC (新建变量都进单表; 核价 3 张占位仅可由 Flyway 初始化)
        String keyColsJson;
        try {
            keyColsJson = MAPPER.writeValueAsString(
                    req.keyColumns == null ? List.of() : req.keyColumns);
        } catch (Exception e) {
            keyColsJson = "[]";
        }
        em.createNativeQuery(
                "INSERT INTO global_variable_definition " +
                "  (code, name, var_type, source_view, key_columns, value_column, " +
                "   label_template, unit, description, sort_order, is_active, " +
                "   value_source_type, visibility) " +
                "VALUES (:c, :n, :vt, NULL, CAST(:kc AS jsonb), 'value_number', " +
                "        :lt, :u, :d, COALESCE(:so, 100), true, 'KV_TABLE', 'PUBLIC')")
                .setParameter("c",  req.code)
                .setParameter("n",  req.name)
                .setParameter("vt", req.varType)
                .setParameter("kc", keyColsJson)
                .setParameter("lt", req.labelTemplate)
                .setParameter("u",  req.unit)
                .setParameter("d",  req.description)
                .setParameter("so", req.sortOrder)
                .executeUpdate();
        return getByCode(req.code).orElseThrow();
    }

    /**
     * G1: 删除全局变量定义 + 级联 KV 值 + 变更日志保留 (审计).
     * 核价变量 (COSTING_VIEW) 拒绝删除.
     */
    @Transactional
    public void deleteDefinition(String code) {
        GlobalVariableDefinition def = getByCode(code)
                .orElseThrow(() -> new BusinessException(404, "未注册: " + code));
        if (def.isCostingView()) {
            throw new BusinessException(400, "核价变量(" + code + ")不可删除 — 影响核价模块业务");
        }
        // global_variable_value 的 FK ON DELETE CASCADE 自动清值
        em.createNativeQuery("DELETE FROM global_variable_definition WHERE code = :c")
                .setParameter("c", code)
                .executeUpdate();
        invalidateDependentCaches(code);
    }

    private static String buildKeyId(Map<String, Object> keyValues) {
        if (keyValues == null || keyValues.isEmpty()) return "_";  // G1: SCALAR 占位
        return keyValues.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ";" + b)
                .orElse("");
    }
}
