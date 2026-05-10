package com.cpq.globalvariable;

import com.cpq.common.exception.BusinessException;
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

    // ───────────── 注册表 ─────────────

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<GlobalVariableDefinition> listAll() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT code, name, var_type, source_view, key_columns::text, value_column, " +
                "       label_template, unit, description, sort_order, is_active, updated_at " +
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
                "       label_template, unit, description, sort_order, is_active, updated_at " +
                "FROM global_variable_definition WHERE code = :c")
                .setParameter("c", code)
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
        validateIdentifiers(def);

        // SELECT key1, key2, ..., value
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
        if (!def.isLookup()) {
            throw new BusinessException(400, "SCALAR 类型暂未实现");
        }
        validateIdentifiers(def);
        if (keyValues == null || keyValues.size() != def.keyColumns.size()) {
            throw new BusinessException(400,
                    "key 数量不匹配: 期望 " + def.keyColumns.size() + ", 实得 " + (keyValues == null ? 0 : keyValues.size()));
        }

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

    /**
     * V106 路由: 把"逻辑变量 code" 映射到底层物理表 + version_kind.
     * 三个核价类变量都对应 costing_price_version 当前默认 PUBLISHED 版本下的明细行.
     */
    private static class PhysicalBackend {
        final String physicalTable;       // 实际写入的表 (非视图)
        final String versionKind;         // costing_price_version.version_kind
        PhysicalBackend(String t, String k) { physicalTable = t; versionKind = k; }
    }

    private static final Map<String, PhysicalBackend> PHYSICAL = Map.of(
            "ELEM_PRICE",    new PhysicalBackend("costing_element_price",  "ELEMENT"),
            "MAT_PRICE",     new PhysicalBackend("costing_material_price", "MATERIAL"),
            "EXCHANGE_RATE", new PhysicalBackend("costing_exchange_rate",  "EXCHANGE"));

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
        if (!def.isLookup()) throw new BusinessException(400, "SCALAR 暂未实现");
        validateIdentifiers(def);
        validateKeyValues(def, keyValues);
        if (newValue == null) throw new BusinessException(400, "新值不能为空");

        PhysicalBackend bk = PHYSICAL.get(code);
        if (bk == null) {
            throw new BusinessException(400, "全局变量 " + code + " 未注册物理后端, 暂不支持直接维护");
        }

        UUID versionId = currentDefaultVersionId(bk.versionKind);
        BigDecimal oldValue = readSingleValue(bk.physicalTable, def, keyValues, versionId);

        if (oldValue == null) {
            // INSERT
            insertRow(bk.physicalTable, def, keyValues, newValue, versionId);
            writeChangeLog(code, buildKeyId(keyValues), "INSERT", null, newValue, note, actorId, actorName);
        } else if (oldValue.compareTo(newValue) != 0) {
            // UPDATE
            updateRow(bk.physicalTable, def, keyValues, newValue, versionId);
            writeChangeLog(code, buildKeyId(keyValues), "UPDATE", oldValue, newValue, note, actorId, actorName);
        }
        // 值未变 → 不写日志, 静默 no-op
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
        validateIdentifiers(def);
        validateKeyValues(def, keyValues);
        PhysicalBackend bk = PHYSICAL.get(code);
        if (bk == null) {
            throw new BusinessException(400, "全局变量 " + code + " 未注册物理后端, 暂不支持直接维护");
        }
        UUID versionId = currentDefaultVersionId(bk.versionKind);
        BigDecimal oldValue = readSingleValue(bk.physicalTable, def, keyValues, versionId);
        if (oldValue == null) {
            // 已不存在, 幂等
            return;
        }
        deleteRow(bk.physicalTable, def, keyValues, versionId);
        writeChangeLog(code, buildKeyId(keyValues), "DELETE", oldValue, null, note, actorId, actorName);
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

    private UUID currentDefaultVersionId(String kind) {
        @SuppressWarnings("unchecked")
        List<Object> rs = em.createNativeQuery(
                "SELECT id FROM costing_price_version " +
                "WHERE version_kind = :k AND status = 'PUBLISHED' AND is_default = true " +
                "LIMIT 1")
                .setParameter("k", kind)
                .getResultList();
        if (rs.isEmpty()) {
            throw new BusinessException(409,
                    "没有当前生效的 " + kind + " 默认版本; 请先在系统初始化时建一个 PUBLISHED+is_default 版本");
        }
        Object v = rs.get(0);
        if (v instanceof UUID u) return u;
        return UUID.fromString(v.toString());
    }

    private BigDecimal readSingleValue(String physicalTable, GlobalVariableDefinition def,
                                       Map<String, Object> keyValues, UUID versionId) {
        StringBuilder sql = new StringBuilder("SELECT ").append(def.valueColumn);
        sql.append(" FROM ").append(physicalTable);
        sql.append(" WHERE version_id = :vid");
        Map<String, Object> bind = new HashMap<>();
        bind.put("vid", versionId);
        for (int i = 0; i < def.keyColumns.size(); i++) {
            sql.append(" AND ").append(def.keyColumns.get(i)).append(" = :p").append(i);
            bind.put("p" + i, keyValues.get(def.keyColumns.get(i)));
        }
        sql.append(" LIMIT 1");
        var q = em.createNativeQuery(sql.toString());
        bind.forEach(q::setParameter);
        @SuppressWarnings("unchecked")
        List<Object> rs = q.getResultList();
        if (rs.isEmpty() || rs.get(0) == null) return null;
        Object v = rs.get(0);
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(v.toString());
    }

    private void insertRow(String physicalTable, GlobalVariableDefinition def,
                           Map<String, Object> keyValues, BigDecimal newValue, UUID versionId) {
        // 列清单: version_id + 所有 key 列 + value 列
        StringBuilder cols = new StringBuilder("version_id");
        StringBuilder vals = new StringBuilder(":vid");
        Map<String, Object> bind = new HashMap<>();
        bind.put("vid", versionId);
        for (int i = 0; i < def.keyColumns.size(); i++) {
            cols.append(", ").append(def.keyColumns.get(i));
            vals.append(", :k").append(i);
            bind.put("k" + i, keyValues.get(def.keyColumns.get(i)));
        }
        cols.append(", ").append(def.valueColumn);
        vals.append(", :nv");
        bind.put("nv", newValue);

        String sql = "INSERT INTO " + physicalTable + " (" + cols + ") VALUES (" + vals + ")";
        var q = em.createNativeQuery(sql);
        bind.forEach(q::setParameter);
        q.executeUpdate();
    }

    private void updateRow(String physicalTable, GlobalVariableDefinition def,
                           Map<String, Object> keyValues, BigDecimal newValue, UUID versionId) {
        StringBuilder sql = new StringBuilder("UPDATE ").append(physicalTable);
        sql.append(" SET ").append(def.valueColumn).append(" = :nv");
        sql.append(" WHERE version_id = :vid");
        Map<String, Object> bind = new HashMap<>();
        bind.put("nv", newValue);
        bind.put("vid", versionId);
        for (int i = 0; i < def.keyColumns.size(); i++) {
            sql.append(" AND ").append(def.keyColumns.get(i)).append(" = :p").append(i);
            bind.put("p" + i, keyValues.get(def.keyColumns.get(i)));
        }
        var q = em.createNativeQuery(sql.toString());
        bind.forEach(q::setParameter);
        q.executeUpdate();
    }

    private void deleteRow(String physicalTable, GlobalVariableDefinition def,
                           Map<String, Object> keyValues, UUID versionId) {
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(physicalTable);
        sql.append(" WHERE version_id = :vid");
        Map<String, Object> bind = new HashMap<>();
        bind.put("vid", versionId);
        for (int i = 0; i < def.keyColumns.size(); i++) {
            sql.append(" AND ").append(def.keyColumns.get(i)).append(" = :p").append(i);
            bind.put("p" + i, keyValues.get(def.keyColumns.get(i)));
        }
        var q = em.createNativeQuery(sql.toString());
        bind.forEach(q::setParameter);
        q.executeUpdate();
    }

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

    private static String buildKeyId(Map<String, Object> keyValues) {
        return keyValues.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ";" + b)
                .orElse("");
    }
}
