package com.cpq.datasource.sqlview;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 启动时把 {@code information_schema.tables/views} 中业务相关的表/视图同步到
 * {@code bnf_table_meta} —— 让 PathPicker 第二 Tab 无需基础数据配置补登记即可看见
 * DBA 通过 Flyway 新增的视图（方案 §10.1 阶段 1）。
 *
 * <p>纳入扫描范围（前缀 / 命名匹配）：
 * <ul>
 *   <li>{@code mat_*} — 主数据表</li>
 *   <li>{@code v_*} — 物理视图（v_q_* / v_c_* / v_part_* / v_costing_*）</li>
 *   <li>{@code costing_*} — 核价主线表</li>
 *   <li>{@code quotation*} / {@code template*} — 报价 / 模板核心表（picker_visible=false 默认）</li>
 *   <li>{@code global_variable_value / global_variable_definition} — 全局变量</li>
 * </ul>
 *
 * <p>幂等：已有行不覆盖运营配置的 {@code template_kind} / {@code picker_visible}，
 * 仅刷新 {@code last_synced}。
 */
@ApplicationScoped
public class BnfTableMetaSyncer {

    private static final Logger LOG = Logger.getLogger(BnfTableMetaSyncer.class);

    private static final String SCAN_SQL =
            "SELECT table_name, table_type " +
            "FROM information_schema.tables " +
            "WHERE table_schema = 'public' AND ( " +
            "  table_name LIKE 'mat_%' " +
            "  OR table_name LIKE 'v\\_%' ESCAPE '\\' " +
            "  OR table_name LIKE 'costing\\_%' ESCAPE '\\' " +
            "  OR table_name IN ('global_variable_value', 'global_variable_definition') " +
            ") " +
            "ORDER BY table_name";

    private static final String UPSERT_SQL =
            "INSERT INTO bnf_table_meta (table_name, is_view, template_kind, display_name, picker_visible, last_synced) " +
            "VALUES (?, ?, ?, ?, true, now()) " +
            "ON CONFLICT (table_name) DO UPDATE SET " +
            "  is_view = EXCLUDED.is_view, " +
            "  last_synced = now()";

    @Inject
    DataSource dataSource;

    public void onStartup(@Observes StartupEvent ev) {
        try {
            int n = sync();
            LOG.infof("[BnfTableMetaSyncer] startup sync OK: %d entries upserted", n);
        } catch (Exception e) {
            // 启动失败不应阻断 app，仅记日志
            LOG.warnf("[BnfTableMetaSyncer] startup sync failed (non-fatal): %s", e.getMessage());
        }
    }

    @Transactional
    public int sync() throws Exception {
        int count = 0;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement scan = conn.prepareStatement(SCAN_SQL);
             ResultSet rs = scan.executeQuery();
             PreparedStatement upsert = conn.prepareStatement(UPSERT_SQL)) {

            while (rs.next()) {
                String tableName = rs.getString("table_name");
                String tableType = rs.getString("table_type");
                boolean isView = "VIEW".equalsIgnoreCase(tableType);
                String templateKind = guessTemplateKind(tableName);
                upsert.setString(1, tableName);
                upsert.setBoolean(2, isView);
                upsert.setString(3, templateKind);
                upsert.setString(4, tableName);
                upsert.addBatch();
                count++;
            }
            upsert.executeBatch();
        }
        return count;
    }

    /** 启发式猜测 template_kind。运营可在 bnf_table_meta 后续手工调整。 */
    private String guessTemplateKind(String tableName) {
        if (tableName.startsWith("v_q_") || tableName.startsWith("mat_")) return "QUOTATION";
        if (tableName.startsWith("v_c_") || tableName.startsWith("costing_")) return "COSTING";
        return "ALL";
    }
}
