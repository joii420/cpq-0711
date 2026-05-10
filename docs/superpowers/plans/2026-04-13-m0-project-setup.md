# M0: Project Setup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Set up a runnable full-stack skeleton (Quarkus backend + React frontend) with database, Flyway migrations, API conventions, and dev tooling so all subsequent modules build incrementally on this foundation.

**Architecture:** Quarkus 3.23.3 monolith with RESTEasy Reactive serving REST APIs under `/api/cpq/`. React 18 SPA (Vite + TypeScript) proxied to the Quarkus backend in dev mode. PostgreSQL 16 via Docker Compose. Flyway manages schema migrations. Backend uses a three-layer `resource → service → repository` pattern, organized by business module packages.

**Tech Stack:** Java 17, Quarkus 3.23.3, PostgreSQL 16, Flyway, React 18, Vite, TypeScript, Ant Design 5.x, Zustand, Axios, React Router v6, Docker Compose

---

## File Structure

```
cpq-backend/                              # Quarkus Maven project root
  pom.xml
  docker-compose.yml
  src/main/resources/
    application.properties
    db/migration/
      V1__create_base_tables.sql
  src/main/java/com/cpq/
    CpqApplication.java                   # (Quarkus generates, may be empty)
    common/
      dto/ApiResponse.java                # Unified API response wrapper
      dto/PageRequest.java                # Unified pagination params
      dto/PageResult.java                 # Unified pagination result
      exception/BusinessException.java    # Business logic exception
      exception/GlobalExceptionMapper.java# RESTEasy exception handler
    health/
      HealthResource.java                 # GET /api/cpq/health
    customer/                             # Module package (empty placeholder)
    product/
    quotation/
    template/
    component/
    pricing/
    approval/
    notification/
    datasource/
    system/
  src/test/java/com/cpq/
    health/
      HealthResourceTest.java

cpq-frontend/                             # React Vite project root
  package.json
  vite.config.ts
  tsconfig.json
  tsconfig.app.json
  tsconfig.node.json
  index.html
  src/
    main.tsx
    App.tsx
    vite-env.d.ts
    router/
      index.tsx                           # React Router config
    layouts/
      MainLayout.tsx                      # Sidebar + content area
    pages/
      Login.tsx                           # Placeholder login page
      Dashboard.tsx                       # Placeholder dashboard
    services/
      api.ts                              # Axios instance + interceptors
    stores/
      authStore.ts                        # Auth state (placeholder)
    styles/
      global.css                          # Global styles + Ant Design theme
```

---

### Task 1: Initialize Git Repository

**Files:**
- Create: `.gitignore`
- Create: `README.md`

- [ ] **Step 1: Initialize git repo**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev
git init
```

- [ ] **Step 2: Create .gitignore**

Create `.gitignore`:

```gitignore
# Java / Maven
cpq-backend/target/
*.class
*.jar
*.war
*.log
.mvn/wrapper/maven-wrapper.jar

# Quarkus
cpq-backend/.quarkus/

# IDE
.idea/
*.iml
.vscode/
.settings/
.classpath
.project
*.swp
*.swo

# Node / Frontend
cpq-frontend/node_modules/
cpq-frontend/dist/
cpq-frontend/.tsbuildinfo

# OS
.DS_Store
Thumbs.db
Desktop.ini

# Environment
.env
.env.local
.env.*.local

# Docker
docker-compose.override.yml
```

- [ ] **Step 3: Create README.md**

Create `README.md`:

```markdown
# CPQ Quotation System

Configure, Price, Quote system for manufacturing/industrial components.

## Tech Stack

- **Backend:** Java 17 + Quarkus 3.23.3
- **Frontend:** React 18 + TypeScript + Ant Design 5.x
- **Database:** PostgreSQL 16
- **Build:** Maven (backend), Vite (frontend)

## Quick Start

### Prerequisites

- Java 17+
- Node.js 24+
- Docker & Docker Compose

### Start Database

docker-compose -f cpq-backend/docker-compose.yml up -d

### Start Backend (dev mode)

cd cpq-backend
./mvnw quarkus:dev

### Start Frontend (dev mode)

cd cpq-frontend
npm install
npm run dev

Backend API: http://localhost:8080/api/cpq/
Frontend: http://localhost:5173/
```

- [ ] **Step 4: Initial commit**

```bash
git add .gitignore README.md
git commit -m "chore: initialize git repository"
```

---

### Task 2: Create Quarkus Backend Project

**Files:**
- Create: `cpq-backend/pom.xml`
- Create: `cpq-backend/src/main/resources/application.properties`
- Create: `cpq-backend/.mvn/wrapper/` (Maven Wrapper)

- [ ] **Step 1: Generate Quarkus project with Maven**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev
mvn io.quarkus.platform:quarkus-maven-plugin:3.23.3:create \
  -DprojectGroupId=com.cpq \
  -DprojectArtifactId=cpq-backend \
  -Dextensions="rest-jackson,hibernate-orm-panache,jdbc-postgresql,flyway,quarkus-security,quarkus-mailer,quarkus-scheduler,hibernate-validator" \
  -DnoCode
```

If the interactive generator asks questions, accept defaults. This creates `cpq-backend/` with Maven Wrapper included.

- [ ] **Step 2: Verify pom.xml has all required dependencies**

Open `cpq-backend/pom.xml` and verify these extensions are present. If any are missing from the generator, add them manually in the `<dependencies>` section:

```xml
    <!-- REST API -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-rest-jackson</artifactId>
    </dependency>

    <!-- ORM -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-hibernate-orm-panache</artifactId>
    </dependency>

    <!-- Database -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-jdbc-postgresql</artifactId>
    </dependency>

    <!-- Migration -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-flyway</artifactId>
    </dependency>

    <!-- Security -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-security</artifactId>
    </dependency>

    <!-- Mail -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-mailer</artifactId>
    </dependency>

    <!-- Scheduler -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-scheduler</artifactId>
    </dependency>

    <!-- Validation -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-hibernate-validator</artifactId>
    </dependency>

    <!-- Testing -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-junit5</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>io.rest-assured</groupId>
      <artifactId>rest-assured</artifactId>
      <scope>test</scope>
    </dependency>
```

Also verify `<maven.compiler.source>17</maven.compiler.source>` and `<maven.compiler.target>17</maven.compiler.target>` in properties.

- [ ] **Step 3: Configure application.properties**

Replace the contents of `cpq-backend/src/main/resources/application.properties`:

```properties
# ===========================================
# CPQ Application Configuration
# ===========================================

# --- HTTP ---
quarkus.http.port=8080
quarkus.http.cors=true
quarkus.http.cors.origins=http://localhost:5173
quarkus.http.cors.methods=GET,POST,PUT,DELETE,PATCH,OPTIONS
quarkus.http.cors.headers=Content-Type,Authorization,Accept
quarkus.http.cors.exposed-headers=Content-Disposition
quarkus.http.cors.access-control-allow-credentials=true

# --- Datasource (primary) ---
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=cpq_user
quarkus.datasource.password=cpq_password
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/cpq_db
quarkus.datasource.jdbc.max-size=20

# --- Hibernate ORM ---
quarkus.hibernate-orm.database.generation=none
quarkus.hibernate-orm.log.sql=true
quarkus.hibernate-orm.physical-naming-strategy=org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy

# --- Flyway ---
quarkus.flyway.migrate-at-start=true
quarkus.flyway.locations=db/migration

# --- Mailer (dev mode: mock) ---
quarkus.mailer.from=noreply@cpq-system.com
quarkus.mailer.mock=true

# --- Scheduler ---
quarkus.scheduler.cron-type=unix

# --- Logging ---
quarkus.log.console.format=%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p [%c{3.}] (%t) %s%e%n
quarkus.log.level=INFO
quarkus.log.category."com.cpq".level=DEBUG
quarkus.log.category."org.hibernate.SQL".level=DEBUG
```

- [ ] **Step 4: Verify Quarkus compiles**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-backend/
git commit -m "chore: initialize Quarkus 3.23.3 backend project with core dependencies"
```

---

### Task 3: Docker Compose for PostgreSQL

**Files:**
- Create: `cpq-backend/docker-compose.yml`

- [ ] **Step 1: Create docker-compose.yml**

Create `cpq-backend/docker-compose.yml`:

```yaml
version: "3.8"

services:
  postgres:
    image: postgres:16-alpine
    container_name: cpq-postgres
    environment:
      POSTGRES_DB: cpq_db
      POSTGRES_USER: cpq_user
      POSTGRES_PASSWORD: cpq_password
    ports:
      - "5432:5432"
    volumes:
      - cpq-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U cpq_user -d cpq_db"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  cpq-pgdata:
```

- [ ] **Step 2: Start PostgreSQL**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend
docker-compose up -d
```

Expected: container `cpq-postgres` starts and becomes healthy.

- [ ] **Step 3: Verify database connectivity**

```bash
docker exec cpq-postgres psql -U cpq_user -d cpq_db -c "SELECT version();"
```

Expected: PostgreSQL 16.x version string.

- [ ] **Step 4: Commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-backend/docker-compose.yml
git commit -m "chore: add Docker Compose for PostgreSQL 16"
```

---

### Task 4: Flyway Initial Migration (Base Tables)

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V1__create_base_tables.sql`

- [ ] **Step 1: Create the initial migration script**

Create `cpq-backend/src/main/resources/db/migration/V1__create_base_tables.sql`:

```sql
-- ===========================================
-- V1: Base tables for CPQ system
-- User, Region, Department, OperationLog,
-- PasswordResetToken, Notification
-- ===========================================

-- --- Region dictionary ---
CREATE TABLE region (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    sort_order INTEGER DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_region_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- --- Department dictionary ---
CREATE TABLE department (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    sort_order INTEGER DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_department_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- --- User ---
CREATE TABLE "user" (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(100) NOT NULL UNIQUE,
    full_name VARCHAR(200) NOT NULL,
    email VARCHAR(200) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL,
    region_id UUID REFERENCES region(id),
    department_id UUID REFERENCES department(id),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    is_first_login BOOLEAN NOT NULL DEFAULT true,
    initial_password_expires_at TIMESTAMP WITH TIME ZONE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_user_role CHECK (role IN ('SALES_REP', 'SALES_MANAGER', 'PRICING_MANAGER', 'SYSTEM_ADMIN')),
    CONSTRAINT chk_user_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_user_role ON "user"(role);
CREATE INDEX idx_user_status ON "user"(status);
CREATE INDEX idx_user_region ON "user"(region_id);
CREATE INDEX idx_user_department ON "user"(department_id);

-- --- Password Reset Token ---
CREATE TABLE password_reset_token (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES "user"(id),
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_prt_user_expires ON password_reset_token(user_id, expires_at);

-- --- Operation Log ---
CREATE TABLE operation_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id UUID NOT NULL REFERENCES "user"(id),
    operation_type VARCHAR(50) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id UUID,
    summary TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_oplog_operator ON operation_log(operator_id);
CREATE INDEX idx_oplog_type ON operation_log(operation_type);
CREATE INDEX idx_oplog_created ON operation_log(created_at);

-- --- Notification ---
CREATE TABLE notification (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id UUID NOT NULL REFERENCES "user"(id),
    type VARCHAR(50) NOT NULL,
    title VARCHAR(500) NOT NULL,
    content TEXT,
    link VARCHAR(500),
    related_type VARCHAR(50),
    related_id UUID,
    is_read BOOLEAN NOT NULL DEFAULT false,
    read_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_notification_type CHECK (type IN (
        'APPROVAL_SUBMITTED', 'APPROVAL_APPROVED', 'APPROVAL_REJECTED',
        'APPROVAL_REMINDER', 'PASSWORD_RESET', 'ROLE_CHANGED', 'SYSTEM'
    ))
);

CREATE INDEX idx_notification_recipient_read ON notification(recipient_id, is_read);
CREATE INDEX idx_notification_created ON notification(created_at);

-- --- Seed data: Regions ---
INSERT INTO region (code, name, sort_order) VALUES
    ('SOUTH_CHINA', '华南', 1),
    ('EAST_CHINA', '华东', 2),
    ('NORTH_CHINA', '华北', 3),
    ('CENTRAL_CHINA', '华中', 4);

-- --- Seed data: Departments ---
INSERT INTO department (code, name, sort_order) VALUES
    ('SALES_DEPT_1', '销售一部', 1),
    ('SALES_DEPT_2', '销售二部', 2),
    ('SALES_DEPT_3', '销售三部', 3);

-- --- Seed data: Default system admin ---
-- Password: Admin@2026 (bcrypt hash with 12 rounds)
-- This hash is pre-generated; in production replace with actual bcrypt output
INSERT INTO "user" (username, full_name, email, password_hash, role, status, is_first_login)
VALUES (
    'admin',
    '系统管理员',
    'admin@cpq-system.com',
    '$2a$12$LJ3m4ys3Gp2v8J5rN3k5aOQZI6x5RmCqGn7B.Y4e2V6dX1wK8S2qC',
    'SYSTEM_ADMIN',
    'ACTIVE',
    true
);
```

- [ ] **Step 2: Start Quarkus in dev mode to run migration**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw quarkus:dev
```

Expected: Flyway runs `V1__create_base_tables.sql` successfully. Look for log line:
`Flyway - Migrating schema "public" to version "1 - create base tables"`

Stop the dev server with `q` after confirming.

- [ ] **Step 3: Verify tables exist**

```bash
docker exec cpq-postgres psql -U cpq_user -d cpq_db -c "\dt"
```

Expected: Tables `region`, `department`, `user`, `password_reset_token`, `operation_log`, `notification`, plus Flyway's `flyway_schema_history`.

- [ ] **Step 4: Verify seed data**

```bash
docker exec cpq-postgres psql -U cpq_user -d cpq_db -c "SELECT code, name FROM region ORDER BY sort_order;"
docker exec cpq-postgres psql -U cpq_user -d cpq_db -c "SELECT username, role FROM \"user\";"
```

Expected: 4 regions + 1 admin user.

- [ ] **Step 5: Commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-backend/src/main/resources/db/migration/V1__create_base_tables.sql
git commit -m "feat(db): add Flyway V1 migration with base tables and seed data"
```

---

### Task 5: Backend Layered Architecture + Common DTOs

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/common/dto/ApiResponse.java`
- Create: `cpq-backend/src/main/java/com/cpq/common/dto/PageRequest.java`
- Create: `cpq-backend/src/main/java/com/cpq/common/dto/PageResult.java`
- Create: `cpq-backend/src/main/java/com/cpq/common/exception/BusinessException.java`
- Create: `cpq-backend/src/main/java/com/cpq/common/exception/GlobalExceptionMapper.java`

- [ ] **Step 1: Create ApiResponse wrapper**

Create `cpq-backend/src/main/java/com/cpq/common/dto/ApiResponse.java`:

```java
package com.cpq.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;

    private ApiResponse() {}

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = 200;
        response.message = "success";
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = code;
        response.message = message;
        return response;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }
}
```

- [ ] **Step 2: Create PageRequest**

Create `cpq-backend/src/main/java/com/cpq/common/dto/PageRequest.java`:

```java
package com.cpq.common.dto;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;

public class PageRequest {

    @QueryParam("page")
    @DefaultValue("0")
    public int page;

    @QueryParam("size")
    @DefaultValue("20")
    public int size;

    @QueryParam("sort")
    @DefaultValue("createdAt,desc")
    public String sort;

    public int getOffset() {
        return page * size;
    }

    public String getSortField() {
        String[] parts = sort.split(",");
        return parts[0];
    }

    public String getSortDirection() {
        String[] parts = sort.split(",");
        return parts.length > 1 ? parts[1] : "desc";
    }
}
```

- [ ] **Step 3: Create PageResult**

Create `cpq-backend/src/main/java/com/cpq/common/dto/PageResult.java`:

```java
package com.cpq.common.dto;

import java.util.List;

public class PageResult<T> {

    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    public PageResult(List<T> content, int page, int size, long totalElements) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
    }

    public List<T> getContent() { return content; }
    public int getPage() { return page; }
    public int getSize() { return size; }
    public long getTotalElements() { return totalElements; }
    public int getTotalPages() { return totalPages; }
}
```

- [ ] **Step 4: Create BusinessException**

Create `cpq-backend/src/main/java/com/cpq/common/exception/BusinessException.java`:

```java
package com.cpq.common.exception;

public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        this(400, message);
    }

    public int getCode() { return code; }
}
```

- [ ] **Step 5: Create GlobalExceptionMapper**

Create `cpq-backend/src/main/java/com/cpq/common/exception/GlobalExceptionMapper.java`:

```java
package com.cpq.common.exception;

import com.cpq.common.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.jboss.logging.Logger;

public class GlobalExceptionMapper {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionMapper.class);

    @ServerExceptionMapper
    public Response handleBusinessException(BusinessException e) {
        LOG.warnf("Business error: %s", e.getMessage());
        return Response.status(e.getCode())
                .entity(ApiResponse.error(e.getCode(), e.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        LOG.warnf("Validation error: %s", message);
        return Response.status(400)
                .entity(ApiResponse.error(400, message))
                .build();
    }

    @ServerExceptionMapper
    public Response handleGenericException(Exception e) {
        LOG.errorf(e, "Unexpected error");
        return Response.status(500)
                .entity(ApiResponse.error(500, "Internal server error"))
                .build();
    }
}
```

- [ ] **Step 6: Create module package placeholders**

Create empty `package-info.java` files in each module package so the structure exists. Create these files:

`cpq-backend/src/main/java/com/cpq/customer/package-info.java`:
```java
/**
 * Customer management module.
 */
package com.cpq.customer;
```

`cpq-backend/src/main/java/com/cpq/product/package-info.java`:
```java
/**
 * Product management module.
 */
package com.cpq.product;
```

`cpq-backend/src/main/java/com/cpq/quotation/package-info.java`:
```java
/**
 * Quotation (quote) management module.
 */
package com.cpq.quotation;
```

`cpq-backend/src/main/java/com/cpq/template/package-info.java`:
```java
/**
 * Product card template module.
 */
package com.cpq.template;
```

`cpq-backend/src/main/java/com/cpq/component/package-info.java`:
```java
/**
 * Component (tab component) management module.
 */
package com.cpq.component;
```

`cpq-backend/src/main/java/com/cpq/pricing/package-info.java`:
```java
/**
 * Pricing strategy module.
 */
package com.cpq.pricing;
```

`cpq-backend/src/main/java/com/cpq/approval/package-info.java`:
```java
/**
 * Approval workflow module.
 */
package com.cpq.approval;
```

`cpq-backend/src/main/java/com/cpq/notification/package-info.java`:
```java
/**
 * Notification module.
 */
package com.cpq.notification;
```

`cpq-backend/src/main/java/com/cpq/datasource/package-info.java`:
```java
/**
 * Data source management module.
 */
package com.cpq.datasource;
```

`cpq-backend/src/main/java/com/cpq/system/package-info.java`:
```java
/**
 * System administration module.
 */
package com.cpq.system;
```

- [ ] **Step 7: Verify compilation**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 8: Commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-backend/src/main/java/com/cpq/
git commit -m "feat: add backend layered architecture, common DTOs, and exception handling"
```

---

### Task 6: Health Endpoint + Test

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/health/HealthResource.java`
- Create: `cpq-backend/src/test/java/com/cpq/health/HealthResourceTest.java`

- [ ] **Step 1: Write the failing test**

Create `cpq-backend/src/test/java/com/cpq/health/HealthResourceTest.java`:

```java
package com.cpq.health;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class HealthResourceTest {

    @Test
    void healthEndpointReturnsOk() {
        RestAssured.given()
            .when()
                .get("/api/cpq/health")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.status", equalTo("UP"))
                .body("data.service", equalTo("CPQ Quotation System"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw test -pl . -Dtest=HealthResourceTest
```

Expected: FAIL — 404 because `/api/cpq/health` doesn't exist yet.

- [ ] **Step 3: Implement HealthResource**

Create `cpq-backend/src/main/java/com/cpq/health/HealthResource.java`:

```java
package com.cpq.health;

import com.cpq.common.dto.ApiResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/api/cpq/health")
@Produces(MediaType.APPLICATION_JSON)
public class HealthResource {

    @GET
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(Map.of(
            "status", "UP",
            "service", "CPQ Quotation System"
        ));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw test -pl . -Dtest=HealthResourceTest
```

Expected: `BUILD SUCCESS`, 1 test passed.

- [ ] **Step 5: Commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-backend/src/main/java/com/cpq/health/ cpq-backend/src/test/java/com/cpq/health/
git commit -m "feat(health): add health endpoint with test"
```

---

### Task 7: Initialize React Frontend Project

**Files:**
- Create: `cpq-frontend/` (entire Vite project)

- [ ] **Step 1: Create Vite React TypeScript project**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev
npm create vite@latest cpq-frontend -- --template react-ts
```

- [ ] **Step 2: Install dependencies**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-frontend
npm install
npm install antd @ant-design/icons
npm install zustand
npm install axios
npm install react-router-dom
npm install dayjs
```

- [ ] **Step 3: Configure Vite proxy**

Replace `cpq-frontend/vite.config.ts`:

```typescript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
```

- [ ] **Step 4: Verify frontend starts**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-frontend
npm run dev
```

Expected: Vite dev server starts on `http://localhost:5173/`. Stop with Ctrl+C after confirming.

- [ ] **Step 5: Commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-frontend/
git commit -m "chore: initialize React 18 + TypeScript frontend with Vite"
```

---

### Task 8: Frontend Infrastructure (Router, Layout, Axios, Store, Styles)

**Files:**
- Create: `cpq-frontend/src/services/api.ts`
- Create: `cpq-frontend/src/stores/authStore.ts`
- Create: `cpq-frontend/src/router/index.tsx`
- Create: `cpq-frontend/src/layouts/MainLayout.tsx`
- Create: `cpq-frontend/src/pages/Login.tsx`
- Create: `cpq-frontend/src/pages/Dashboard.tsx`
- Create: `cpq-frontend/src/styles/global.css`
- Modify: `cpq-frontend/src/App.tsx`
- Modify: `cpq-frontend/src/main.tsx`

- [ ] **Step 1: Create Axios instance with interceptors**

Create `cpq-frontend/src/services/api.ts`:

```typescript
import axios from 'axios';

const api = axios.create({
  baseURL: '/api/cpq',
  timeout: 30000,
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json',
  },
});

api.interceptors.response.use(
  (response) => {
    return response.data;
  },
  (error) => {
    if (error.response?.status === 401) {
      window.location.href = '/login';
    }
    const message = error.response?.data?.message || 'Network error';
    return Promise.reject(new Error(message));
  }
);

export default api;
```

- [ ] **Step 2: Create auth store placeholder**

Create `cpq-frontend/src/stores/authStore.ts`:

```typescript
import { create } from 'zustand';

interface User {
  id: string;
  username: string;
  fullName: string;
  role: 'SALES_REP' | 'SALES_MANAGER' | 'PRICING_MANAGER' | 'SYSTEM_ADMIN';
}

interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  setUser: (user: User | null) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  isAuthenticated: false,
  setUser: (user) => set({ user, isAuthenticated: !!user }),
  logout: () => set({ user: null, isAuthenticated: false }),
}));
```

- [ ] **Step 3: Create global styles**

Create `cpq-frontend/src/styles/global.css`:

```css
body {
  margin: 0;
  padding: 0;
  font-family: 'PingFang SC', 'Segoe UI', -apple-system, BlinkMacSystemFont, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

#root {
  min-height: 100vh;
}
```

- [ ] **Step 4: Create MainLayout**

Create `cpq-frontend/src/layouts/MainLayout.tsx`:

```tsx
import React from 'react';
import { Layout, Menu, Avatar, Dropdown, Badge } from 'antd';
import {
  UserOutlined,
  TeamOutlined,
  ShoppingOutlined,
  FileTextOutlined,
  SettingOutlined,
  BellOutlined,
  LogoutOutlined,
  DashboardOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../stores/authStore';

const { Sider, Header, Content } = Layout;

const menuItems = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '工作台' },
  { key: '/customers', icon: <TeamOutlined />, label: '客户管理' },
  { key: '/products', icon: <ShoppingOutlined />, label: '产品管理' },
  { key: '/quotations', icon: <FileTextOutlined />, label: '报价中心' },
  { key: '/system', icon: <SettingOutlined />, label: '系统管理' },
];

const MainLayout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuthStore();

  const userMenu = {
    items: [
      { key: 'profile', label: '修改密码', icon: <UserOutlined /> },
      { type: 'divider' as const },
      { key: 'logout', label: '退出登录', icon: <LogoutOutlined />, danger: true },
    ],
    onClick: ({ key }: { key: string }) => {
      if (key === 'logout') {
        logout();
        navigate('/login');
      }
    },
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={220} theme="dark">
        <div style={{ height: 64, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <span style={{ color: '#fff', fontSize: 18, fontWeight: 600 }}>CPQ 报价系统</span>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header style={{ background: '#fff', padding: '0 24px', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 16 }}>
          <Badge count={0} size="small">
            <BellOutlined style={{ fontSize: 18, cursor: 'pointer' }} />
          </Badge>
          <Dropdown menu={userMenu} placement="bottomRight">
            <span style={{ cursor: 'pointer' }}>
              <Avatar size="small" icon={<UserOutlined />} style={{ marginRight: 8 }} />
              {user?.fullName || 'User'}
            </span>
          </Dropdown>
        </Header>
        <Content style={{ margin: 24, padding: 24, background: '#fff', borderRadius: 8, minHeight: 280 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
};

export default MainLayout;
```

- [ ] **Step 5: Create placeholder pages**

Create `cpq-frontend/src/pages/Login.tsx`:

```tsx
import React from 'react';
import { Card, Typography } from 'antd';

const Login: React.FC = () => {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)' }}>
      <Card style={{ width: 400, textAlign: 'center' }}>
        <Typography.Title level={3}>CPQ 报价系统</Typography.Title>
        <Typography.Text type="secondary">Login page - to be implemented in M1</Typography.Text>
      </Card>
    </div>
  );
};

export default Login;
```

Create `cpq-frontend/src/pages/Dashboard.tsx`:

```tsx
import React from 'react';
import { Typography } from 'antd';

const Dashboard: React.FC = () => {
  return (
    <div>
      <Typography.Title level={4}>工作台</Typography.Title>
      <Typography.Text type="secondary">Dashboard - to be implemented in M4b</Typography.Text>
    </div>
  );
};

export default Dashboard;
```

- [ ] **Step 6: Create router config**

Create `cpq-frontend/src/router/index.tsx`:

```tsx
import React from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import MainLayout from '../layouts/MainLayout';
import Login from '../pages/Login';
import Dashboard from '../pages/Dashboard';

const router = createBrowserRouter([
  {
    path: '/login',
    element: <Login />,
  },
  {
    path: '/',
    element: <MainLayout />,
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard', element: <Dashboard /> },
    ],
  },
]);

export default router;
```

- [ ] **Step 7: Update App.tsx**

Replace `cpq-frontend/src/App.tsx`:

```tsx
import React from 'react';
import { RouterProvider } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import router from './router';

const App: React.FC = () => {
  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#667eea',
        },
      }}
    >
      <RouterProvider router={router} />
    </ConfigProvider>
  );
};

export default App;
```

- [ ] **Step 8: Update main.tsx**

Replace `cpq-frontend/src/main.tsx`:

```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './styles/global.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
```

- [ ] **Step 9: Delete Vite boilerplate files**

Delete these files generated by Vite that are no longer needed:

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-frontend
rm -f src/App.css src/index.css src/assets/react.svg public/vite.svg
```

- [ ] **Step 10: Verify frontend starts and renders**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-frontend
npm run dev
```

Open `http://localhost:5173/` in browser. Expected: Main layout with sidebar ("CPQ 报价系统" title, menu items) and "工作台" dashboard page.

Open `http://localhost:5173/login`. Expected: Login placeholder card with gradient background.

Stop dev server after confirming.

- [ ] **Step 11: Commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-frontend/src/
git commit -m "feat(frontend): add router, layout, axios, auth store, and placeholder pages"
```

---

### Task 9: Frontend-Backend Integration Verification

**Files:**
- Modify: `cpq-frontend/src/pages/Dashboard.tsx`

This task verifies the full-stack connection works: React → Vite proxy → Quarkus → response rendered.

- [ ] **Step 1: Update Dashboard to call health API**

Replace `cpq-frontend/src/pages/Dashboard.tsx`:

```tsx
import React, { useEffect, useState } from 'react';
import { Typography, Card, Spin, Alert } from 'antd';
import { CheckCircleOutlined } from '@ant-design/icons';
import api from '../services/api';

interface HealthData {
  code: number;
  data: {
    status: string;
    service: string;
  };
}

const Dashboard: React.FC = () => {
  const [health, setHealth] = useState<HealthData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.get('/health')
      .then((res: any) => {
        setHealth(res);
        setLoading(false);
      })
      .catch((err: Error) => {
        setError(err.message);
        setLoading(false);
      });
  }, []);

  return (
    <div>
      <Typography.Title level={4}>工作台</Typography.Title>
      <Card title="System Health" style={{ maxWidth: 400 }}>
        {loading && <Spin />}
        {error && <Alert type="error" message={error} />}
        {health && (
          <div>
            <p><CheckCircleOutlined style={{ color: '#48bb78', marginRight: 8 }} />Status: {health.data.status}</p>
            <p>Service: {health.data.service}</p>
          </div>
        )}
      </Card>
    </div>
  );
};

export default Dashboard;
```

- [ ] **Step 2: Start both backend and frontend**

Terminal 1 (backend):
```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw quarkus:dev
```

Terminal 2 (frontend):
```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-frontend
npm run dev
```

- [ ] **Step 3: Verify integration**

Open `http://localhost:5173/dashboard` in browser.

Expected: Dashboard page shows "System Health" card with:
- Status: UP (green checkmark)
- Service: CPQ Quotation System

This confirms: React → Vite proxy (:5173/api → :8080/api) → Quarkus health endpoint → JSON response → rendered in React.

- [ ] **Step 4: Verify cookie handling**

Open browser DevTools → Network tab. Click the `/api/cpq/health` request. Verify the request is sent to the Vite proxy (not directly to port 8080).

- [ ] **Step 5: Commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-frontend/src/pages/Dashboard.tsx
git commit -m "feat: verify frontend-backend integration via health endpoint"
```

---

## Self-Review Results

**1. Spec coverage:**
- Task 1: Git init ✅ (0.9 implied)
- Task 2: Quarkus init + dependencies ✅ (0.1, 0.2)
- Task 3: Docker Compose ✅ (0.8)
- Task 4: Flyway migration ✅ (0.3)
- Task 5: Layered architecture + DTOs ✅ (0.4, 0.5)
- Task 6: Health endpoint ✅ (0.1 verification)
- Task 7: React init ✅ (0.6)
- Task 8: Frontend infra ✅ (0.7)
- Task 9: Integration verification ✅ (0.9)

All M0 spec items covered. No gaps.

**2. Placeholder scan:** No TBD/TODO/implement-later patterns found. All steps have complete code.

**3. Type consistency:** `ApiResponse<T>` used consistently in HealthResource and HealthResourceTest. Package names `com.cpq.*` consistent throughout. Frontend `api.ts` baseURL `/api/cpq` matches backend `@Path("/api/cpq/health")`.
