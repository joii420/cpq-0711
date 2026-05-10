package com.cpq.datasource.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.datasource.entity.DataSource;
import com.cpq.datasource.entity.DataSourceParam;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@ApplicationScoped
public class SqlExecutionService {

    private static final Logger LOG = Logger.getLogger(SqlExecutionService.class);

    // Only allow SELECT statements — reject anything with dangerous keywords or semicolons
    private static final Pattern SELECT_ONLY = Pattern.compile(
            "^\\s*SELECT[\\s\\S]+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS = Pattern.compile(
            ";|\\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|EXEC|EXECUTE|CALL|MERGE)\\b",
            Pattern.CASE_INSENSITIVE);

    @Inject
    @io.quarkus.agroal.DataSource("datasource-readonly")
    javax.sql.DataSource readonlyDataSource;

    public String execute(DataSource ds, List<DataSourceParam> params, Map<String, String> paramValues) {
        if (ds.sqlQuery == null || ds.sqlQuery.isBlank()) {
            throw new BusinessException("SQL query is not configured");
        }
        validateSqlQuery(ds.sqlQuery);

        long start = System.currentTimeMillis();
        try (Connection conn = readonlyDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(ds.sqlQuery)) {

            stmt.setQueryTimeout(10);

            // Bind params in ascending order
            if (params != null && !params.isEmpty()) {
                List<DataSourceParam> ordered = params.stream()
                        .sorted((a, b) -> Integer.compare(a.paramOrder, b.paramOrder))
                        .toList();
                for (int i = 0; i < ordered.size(); i++) {
                    DataSourceParam p = ordered.get(i);
                    String value = paramValues != null ? paramValues.get(p.paramCode) : null;
                    stmt.setString(i + 1, value);
                }
            }

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String col = ds.sqlResultColumn;
                    String value = (col != null && !col.isBlank()) ? rs.getString(col) : rs.getString(1);
                    long elapsed = System.currentTimeMillis() - start;
                    LOG.debugf("SQL datasource id=%s executed in %dms", ds.id, elapsed);
                    return value;
                }
            }
            return null;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            LOG.warnf("SQL datasource id=%s execution failed in %dms: %s", ds.id, elapsed, e.getMessage());
            throw new BusinessException("SQL execution failed: " + e.getMessage());
        }
    }

    private void validateSqlQuery(String sql) {
        if (!SELECT_ONLY.matcher(sql).matches()) {
            throw new BusinessException("Only SELECT statements are allowed");
        }
        if (DANGEROUS.matcher(sql).find()) {
            throw new BusinessException("SQL contains disallowed keywords or semicolons");
        }
    }
}
