# M2b: Data Source Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement DataSource CRUD (SQL/API types), parameter management, query execution (SQL with read-only connection, API with HTTP client), AES encryption for sensitive headers, test panel, and management UI.

**Architecture:** DataSource and DataSourceParam as Panache entities. Separate `datasource-readonly` Quarkus datasource for SQL execution with PreparedStatement. API execution via `java.net.http.HttpClient`. AES-256 encryption for api_headers sensitive values. JSONPath extraction for API responses.

**Tech Stack:** Java 17, Quarkus multi-datasource, AES-256, java.net.http, JSONPath (Jayway), Apache POI (not needed here), Ant Design Form/Table

---

### Task 1: Flyway V5 — DataSource/DataSourceParam tables
- **Files:** `db/migration/V5__create_datasource_tables.sql`
- **DDL:** DataSource (id, code UNIQUE, name, type ENUM[SQL,API], status, description, sql_query, sql_result_column, api_url, api_method, api_headers JSONB, api_body_template, api_result_path, api_timeout_seconds, created_by FK→User, timestamps) + DataSourceParam (id, datasource_id FK, param_order, param_code, param_name, source_type ENUM[USER_FIELD,SYSTEM_PARAM], system_param_code, is_required, description, timestamp)
- **Constraints:** (datasource_id, param_code) UNIQUE
- **Acceptance:** Tables created

### Task 2: Read-only database connection
- **Files:** Modify `application.properties`
- **Config:** `quarkus.datasource.datasource-readonly.*` with separate user (create in Docker init script or manual SQL: `CREATE USER cpq_readonly PASSWORD '..'; GRANT SELECT ON ALL TABLES IN SCHEMA public TO cpq_readonly; REVOKE SELECT ON "user", operation_log, password_reset_token, notification FROM cpq_readonly;`)
- **Files:** `db/migration/V5b__create_readonly_user.sql` or Docker init script
- **Acceptance:** Read-only user can SELECT from business tables, cannot SELECT from system tables, cannot INSERT/UPDATE/DELETE

### Task 3: DataSource CRUD API
- **Files:** `datasource/entity/DataSource.java`, `datasource/entity/DataSourceParam.java`, `datasource/dto/DataSourceDTO.java`, `datasource/service/DataSourceService.java`, `datasource/resource/DataSourceResource.java`
- **API:**
  - `GET /api/cpq/datasources` — list with type filter, keyword search, pagination
  - `GET /api/cpq/datasources/{id}` — detail with params
  - `POST /api/cpq/datasources` — create with nested params
  - `PUT /api/cpq/datasources/{id}` — update (code/type immutable)
  - `DELETE /api/cpq/datasources/{id}` — delete protection (check Component.fields datasource_binding references)
- **Param management:** Nested CRUD within datasource create/update, param_order auto-maintained
- **System param validation:** SYS_CUSTOMER_ID, SYS_CUSTOMER_LEVEL, SYS_CUSTOMER_REGION, SYS_QUOTE_DATE, SYS_SALES_REP_ID
- **Tests:** CRUD + delete protection + param management
- **Acceptance:** All tests pass

### Task 4: AES encryption for API headers
- **Files:** `datasource/service/EncryptionService.java`
- **Logic:** AES-256 encrypt/decrypt; key from `quarkus.cpq.encryption-key` config; encrypt api_headers sensitive values on save; decrypt on read for execution; mask with `****` in API responses
- **Config:** Add `cpq.encryption-key=...` to application.properties
- **Tests:** Encrypt/decrypt round-trip, masking in DTO
- **Acceptance:** Stored value encrypted in DB, API response shows `****`, execution uses decrypted value

### Task 5: SQL datasource execution service
- **Files:** `datasource/service/SqlExecutionService.java`
- **Logic:** Use `datasource-readonly` AgroalDataSource; PreparedStatement with positional params; SQL validation (only SELECT, no semicolons); 10s timeout (`Statement.setQueryTimeout`); extract first row's `sql_result_column` value
- **Tests:** Valid query returns value, non-SELECT rejected, timeout works
- **Acceptance:** Execute SQL data source with test params returns correct value

### Task 6: API datasource execution service
- **Files:** `datasource/service/ApiExecutionService.java`
- **Logic:** `java.net.http.HttpClient`; replace `{param_code}` placeholders in URL/body; decrypt and set headers; GET/POST based on method; JSONPath extraction (`com.jayway.jsonpath`); timeout from api_timeout_seconds; HTTPS only validation
- **Dependencies:** Add `com.jayway.jsonpath:json-path` to pom.xml
- **Tests:** Mock HTTP server test, placeholder replacement, JSONPath extraction
- **Acceptance:** API datasource test returns extracted value

### Task 7: Datasource test endpoint
- **Files:** Modify `DataSourceResource.java`
- **API:** `POST /api/cpq/datasources/{id}/test` — accepts `{params: {paramCode: value}}`, injects system params if needed, executes SQL or API, returns `{rawResponse, extractedValue, executionTimeMs}`
- **Tests:** Test SQL and API datasources
- **Acceptance:** Test panel returns correct results

### Task 8: Datasource execute endpoint (for quotation use)
- **Files:** Modify `DataSourceResource.java`
- **API:** `POST /api/cpq/datasources/{id}/execute` — same as test but auto-injects system params from current session/quotation context
- **Acceptance:** Execute returns value for use in quotation

### Task 9: Datasource management frontend - list page
- **Files:** `pages/datasource/DataSourceList.tsx`, `services/datasourceService.ts`
- **UI:** Table (code, name, type tag, param count, status, created_at); type filter, search; row actions: edit, test, delete (confirm + protection message)
- **Acceptance:** List loads, filter/search works

### Task 10: Datasource management frontend - edit page
- **Files:** `pages/datasource/DataSourceEdit.tsx`
- **UI:** Three-section form: ① Basic (code/name/type/status/desc) ② Query config (SQL: CodeMirror/textarea + result column; API: URL/method/headers key-value/body template/result path/timeout) ③ Param table (drag sort, code, name, source_type toggle, system_param dropdown, required, desc)
- **Acceptance:** Create/edit SQL and API datasources, params saved correctly

### Task 11: Datasource management frontend - test panel
- **Files:** Modify `DataSourceEdit.tsx`
- **UI:** Embedded test area: param inputs (system params overridable), "Execute Test" button, result display (raw response collapsible, extracted value highlighted, execution time)
- **Acceptance:** Test SQL/API datasources from UI

### Task 12: Operation log integration
- **Modify:** DataSourceService — log create/edit/delete
- **Acceptance:** Logs recorded

---

## Execution Order

```
V5 DDL(1) → Readonly connection(2) → CRUD API(3) + AES(4)
→ SQL execution(5) + API execution(6) → Test endpoint(7) + Execute endpoint(8)
→ Frontend list(9) → Frontend edit(10) → Frontend test(11)
→ OpLog(12)
```

## Verification Checklist
- [ ] Create SQL datasource with params, test returns correct value
- [ ] Create API datasource, Authorization header encrypted in DB, masked in UI
- [ ] SQL: DELETE statement rejected, timeout works, system tables inaccessible
- [ ] API: HTTPS only, timeout works, JSONPath extraction correct
- [ ] Delete datasource blocked when referenced by components
- [ ] Full CRUD from frontend works
