package com.cpq.datasource.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.datasource.dto.CreateDataSourceRequest;
import com.cpq.datasource.dto.DataSourceDTO;
import com.cpq.datasource.dto.DataSourceTestRequest;
import com.cpq.datasource.dto.DataSourceTestResult;
import com.cpq.datasource.entity.DataSource;
import com.cpq.datasource.entity.DataSourceParam;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class DataSourceService {

    private static final Logger LOG = Logger.getLogger(DataSourceService.class);

    @Inject
    EntityManager em;

    @Inject
    SqlExecutionService sqlExecutionService;

    @Inject
    ApiExecutionService apiExecutionService;

    @Inject
    EncryptionService encryptionService;

    private static final Pattern SENSITIVE_KEY_PATTERN =
            Pattern.compile("(?i)(auth|key|token|secret|bearer)");

    public PageResult<DataSourceDTO> list(int page, int size, String type, String keyword) {
        page = com.cpq.common.dto.Pagination.clampPage(page);
        size = com.cpq.common.dto.Pagination.clampSize(size);
        StringBuilder where = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (type != null && !type.isBlank()) {
            where.append(" AND type = :type");
            params.put("type", type);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (name LIKE :kw OR code LIKE :kw)");
            params.put("kw", "%" + keyword + "%");
        }

        long total = DataSource.count(where.toString(), params);
        List<DataSourceDTO> content = DataSource
                .find(where + " ORDER BY createdAt DESC", params)
                .page(page, size)
                .<DataSource>list()
                .stream()
                .map(DataSourceDTO::from)
                .collect(Collectors.toList());

        LOG.debugf("list datasources page=%d size=%d total=%d", page, size, total);
        return new PageResult<>(content, page, size, total);
    }

    public DataSourceDTO getById(UUID id) {
        DataSource ds = DataSource.findById(id);
        if (ds == null) {
            throw new BusinessException(404, "DataSource not found: " + id);
        }
        List<DataSourceParam> params = DataSourceParam.<DataSourceParam>list(
                "datasourceId = ?1 ORDER BY paramOrder ASC", id);
        return DataSourceDTO.from(ds, params);
    }

    @Transactional
    public DataSourceDTO create(CreateDataSourceRequest request) {
        // Check unique code
        long codeCount = DataSource.count("code", request.code);
        if (codeCount > 0) {
            throw new BusinessException("DataSource code already exists: " + request.code);
        }

        DataSource ds = new DataSource();
        ds.code = request.code;
        ds.name = request.name;
        ds.type = request.type;
        ds.status = request.status != null ? request.status : "ACTIVE";
        ds.description = request.description;
        ds.sqlQuery = request.sqlQuery;
        ds.sqlResultColumn = request.sqlResultColumn;
        ds.apiUrl = request.apiUrl;
        ds.apiMethod = request.apiMethod;
        ds.apiHeaders = encryptSensitiveHeaders(request.apiHeaders != null ? request.apiHeaders : "[]");
        ds.apiBodyTemplate = request.apiBodyTemplate;
        ds.apiResultPath = request.apiResultPath;
        ds.apiTimeoutSeconds = request.apiTimeoutSeconds != null ? request.apiTimeoutSeconds : 5;
        ds.persist();

        saveParams(ds.id, request.params);

        List<DataSourceParam> params = DataSourceParam.<DataSourceParam>list(
                "datasourceId = ?1 ORDER BY paramOrder ASC", ds.id);
        LOG.infof("Created datasource code=%s type=%s params=%d", ds.code, ds.type, params.size());
        return DataSourceDTO.from(ds, params);
    }

    @Transactional
    public DataSourceDTO update(UUID id, CreateDataSourceRequest request) {
        DataSource ds = DataSource.findById(id);
        if (ds == null) {
            throw new BusinessException(404, "DataSource not found: " + id);
        }

        // code and type are immutable after creation — ignore changes
        ds.name = request.name != null ? request.name : ds.name;
        ds.status = request.status != null ? request.status : ds.status;
        ds.description = request.description;
        ds.sqlQuery = request.sqlQuery;
        ds.sqlResultColumn = request.sqlResultColumn;
        ds.apiUrl = request.apiUrl;
        ds.apiMethod = request.apiMethod;
        ds.apiHeaders = request.apiHeaders != null
                ? encryptSensitiveHeaders(request.apiHeaders)
                : ds.apiHeaders;
        ds.apiBodyTemplate = request.apiBodyTemplate;
        ds.apiResultPath = request.apiResultPath;
        if (request.apiTimeoutSeconds != null) {
            ds.apiTimeoutSeconds = request.apiTimeoutSeconds;
        }

        // Delete existing params and re-insert
        DataSourceParam.delete("datasourceId", id);
        saveParams(id, request.params);

        List<DataSourceParam> params = DataSourceParam.<DataSourceParam>list(
                "datasourceId = ?1 ORDER BY paramOrder ASC", id);
        LOG.infof("Updated datasource id=%s code=%s params=%d", id, ds.code, params.size());
        return DataSourceDTO.from(ds, params);
    }

    @Transactional
    public void delete(UUID id) {
        DataSource ds = DataSource.findById(id);
        if (ds == null) {
            throw new BusinessException(404, "DataSource not found: " + id);
        }

        // Check if any component references this datasource (skip if component table doesn't exist yet)
        checkNoComponentReferences(id);

        DataSourceParam.delete("datasourceId", id);
        ds.delete();
        LOG.infof("Deleted datasource id=%s code=%s", id, ds.code);
    }

    public DataSourceTestResult test(UUID id, Map<String, String> testParams) {
        DataSource ds = DataSource.findById(id);
        if (ds == null) {
            throw new BusinessException(404, "DataSource not found: " + id);
        }
        List<DataSourceParam> params = DataSourceParam.<DataSourceParam>list(
                "datasourceId = ?1 ORDER BY paramOrder ASC", id);

        return executeInternal(ds, params, testParams);
    }

    public DataSourceTestResult execute(UUID id, Map<String, String> execParams) {
        return test(id, execParams);
    }

    private DataSourceTestResult executeInternal(DataSource ds, List<DataSourceParam> params,
                                                  Map<String, String> paramValues) {
        long start = System.currentTimeMillis();
        try {
            String result;
            String rawResponse;

            if ("SQL".equals(ds.type)) {
                result = sqlExecutionService.execute(ds, params, paramValues);
                rawResponse = result;
            } else if ("API".equals(ds.type)) {
                result = apiExecutionService.execute(ds, params, paramValues);
                rawResponse = result;
            } else {
                throw new BusinessException("Unsupported datasource type: " + ds.type);
            }

            long elapsed = System.currentTimeMillis() - start;
            return DataSourceTestResult.ok(rawResponse, result, elapsed);

        } catch (BusinessException e) {
            long elapsed = System.currentTimeMillis() - start;
            return DataSourceTestResult.error(e.getMessage(), elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return DataSourceTestResult.error("Unexpected error: " + e.getMessage(), elapsed);
        }
    }

    private void saveParams(UUID datasourceId, List<CreateDataSourceRequest.ParamRequest> paramRequests) {
        if (paramRequests == null || paramRequests.isEmpty()) return;
        for (int i = 0; i < paramRequests.size(); i++) {
            CreateDataSourceRequest.ParamRequest pr = paramRequests.get(i);
            DataSourceParam p = new DataSourceParam();
            p.datasourceId = datasourceId;
            p.paramOrder = i + 1;
            p.paramCode = pr.paramCode;
            p.paramName = pr.paramName;
            p.sourceType = pr.sourceType;
            p.systemParamCode = pr.systemParamCode;
            p.isRequired = pr.isRequired != null ? pr.isRequired : true;
            p.description = pr.description;
            p.persist();
        }
    }

    /**
     * Encrypt values of sensitive headers (Authorization, API-Key, etc.) before persisting.
     *
     * <p>Supports both {@code {"key":"...", "value":"..."}} and
     * {@code {"name":"...", "value":"..."}} field naming conventions.
     * Already-encrypted values (ENC: prefix) are left unchanged.
     *
     * <p>v4: switched from regex to Jackson parsing so that field-order, escaped chars,
     * and nested whitespace no longer cause silent skips (T4 P0 finding).
     */
    @SuppressWarnings("unchecked")
    String encryptSensitiveHeaders(String apiHeadersJson) {
        if (apiHeadersJson == null || apiHeadersJson.isBlank()) return apiHeadersJson;
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            List<Map<String, Object>> entries = mapper.readValue(
                    apiHeadersJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> entry : entries) {
                Object name = entry.get("key");
                if (name == null) name = entry.get("name");
                Object val = entry.get("value");
                if (name == null || val == null) continue;
                String valStr = val.toString();
                if (isSensitiveHeader(name.toString()) && !valStr.startsWith("ENC:")) {
                    entry.put("value", encryptionService.encrypt(valStr));
                }
            }
            return mapper.writeValueAsString(entries);
        } catch (Exception e) {
            LOG.warnf("Failed to parse api_headers JSON, storing as-is (no encryption applied): %s", e.getMessage());
            return apiHeadersJson;
        }
    }

    private boolean isSensitiveHeader(String key) {
        if (key == null) return false;
        return SENSITIVE_KEY_PATTERN.matcher(key).find();
    }

    private void checkNoComponentReferences(UUID datasourceId) {
        try {
            Long tableExists = (Long) em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'component'"
            ).getSingleResult();
            if (tableExists == null || tableExists == 0) {
                return; // Component table doesn't exist yet, skip check
            }
            // TODO: add component reference check once component table schema is finalized
        } catch (Exception e) {
            LOG.debugf("Component reference check skipped for datasourceId=%s: %s", datasourceId, e.getMessage());
        }
    }
}
