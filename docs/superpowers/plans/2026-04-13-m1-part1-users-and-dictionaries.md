# M1 Part 1: Users & Dictionaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Region/Department dictionary CRUD and User management CRUD with role assignment, building the foundation for authentication and authorization in Part 2.

**Architecture:** Panache Active Record entities mapped to existing Flyway tables. Three-layer pattern: Entity (Panache) → Service → Resource. All APIs return `ApiResponse<T>` wrapper. Validation via Hibernate Validator annotations.

**Tech Stack:** Java 17, Quarkus 3.34.3, Hibernate ORM Panache, PostgreSQL 16, BCrypt (jBCrypt), REST-assured tests

---

## File Structure

```
cpq-backend/src/main/java/com/cpq/
  system/
    entity/Region.java
    entity/Department.java
    dto/RegionDTO.java
    dto/DepartmentDTO.java
    service/RegionService.java
    service/DepartmentService.java
    resource/RegionResource.java
    resource/DepartmentResource.java
    entity/User.java
    dto/UserDTO.java
    dto/CreateUserRequest.java
    dto/UpdateUserRequest.java
    service/UserService.java
    resource/UserResource.java
    service/OperationLogService.java
    entity/OperationLog.java

cpq-backend/src/test/java/com/cpq/
  system/
    resource/RegionResourceTest.java
    resource/DepartmentResourceTest.java
    resource/UserResourceTest.java
```

---

### Task 1: Region Entity + CRUD API

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/system/entity/Region.java`
- Create: `cpq-backend/src/main/java/com/cpq/system/dto/RegionDTO.java`
- Create: `cpq-backend/src/main/java/com/cpq/system/service/RegionService.java`
- Create: `cpq-backend/src/main/java/com/cpq/system/resource/RegionResource.java`
- Create: `cpq-backend/src/test/java/com/cpq/system/resource/RegionResourceTest.java`

- [ ] **Step 1: Write failing tests for Region CRUD**

Create `cpq-backend/src/test/java/com/cpq/system/resource/RegionResourceTest.java`:

```java
package com.cpq.system.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.*;

@QuarkusTest
class RegionResourceTest {

    @Test
    void listRegions() {
        RestAssured.given()
            .when().get("/api/cpq/regions")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.content.size()", greaterThanOrEqualTo(4));
    }

    @Test
    void createRegion() {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {"code":"SOUTHWEST","name":"西南","sortOrder":5}
                """)
            .when().post("/api/cpq/regions")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.code", equalTo("SOUTHWEST"))
                .body("data.name", equalTo("西南"));
    }

    @Test
    void createRegionDuplicateCodeFails() {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {"code":"SOUTH_CHINA","name":"重复华南","sortOrder":99}
                """)
            .when().post("/api/cpq/regions")
            .then()
                .statusCode(400);
    }

    @Test
    void updateRegion() {
        // First get an existing region
        String id = RestAssured.given()
            .when().get("/api/cpq/regions")
            .then().extract()
            .jsonPath().getString("data.content[0].id");

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {"name":"华南地区","sortOrder":1}
                """)
            .when().put("/api/cpq/regions/" + id)
            .then()
                .statusCode(200)
                .body("data.name", equalTo("华南地区"));
    }

    @Test
    void disableRegion() {
        // Create a region to disable (no users linked)
        String id = RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {"code":"TEST_DISABLE","name":"待停用","sortOrder":99}
                """)
            .when().post("/api/cpq/regions")
            .then().extract()
            .jsonPath().getString("data.id");

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {"status":"DISABLED"}
                """)
            .when().patch("/api/cpq/regions/" + id)
            .then()
                .statusCode(200)
                .body("data.status", equalTo("DISABLED"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw test -Dtest=RegionResourceTest
```

Expected: FAIL — endpoints don't exist yet.

- [ ] **Step 3: Create Region entity**

Create `cpq-backend/src/main/java/com/cpq/system/entity/Region.java`:

```java
package com.cpq.system.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "region")
public class Region extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public UUID id;

    @Column(nullable = false, unique = true, length = 50)
    public String code;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(name = "sort_order")
    public Integer sortOrder = 0;

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
```

- [ ] **Step 4: Create RegionDTO**

Create `cpq-backend/src/main/java/com/cpq/system/dto/RegionDTO.java`:

```java
package com.cpq.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public class RegionDTO {

    public UUID id;

    @NotBlank(message = "区域编码不能为空")
    @Size(max = 50)
    public String code;

    @NotBlank(message = "区域名称不能为空")
    @Size(max = 100)
    public String name;

    public Integer sortOrder;
    public String status;
    public OffsetDateTime createdAt;

    public static RegionDTO from(com.cpq.system.entity.Region entity) {
        RegionDTO dto = new RegionDTO();
        dto.id = entity.id;
        dto.code = entity.code;
        dto.name = entity.name;
        dto.sortOrder = entity.sortOrder;
        dto.status = entity.status;
        dto.createdAt = entity.createdAt;
        return dto;
    }
}
```

- [ ] **Step 5: Create RegionService**

Create `cpq-backend/src/main/java/com/cpq/system/service/RegionService.java`:

```java
package com.cpq.system.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.system.dto.RegionDTO;
import com.cpq.system.entity.Region;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class RegionService {

    public PageResult<RegionDTO> list(int page, int size) {
        long total = Region.count("status != 'DISABLED' OR 1=1");
        List<RegionDTO> content = Region.find("ORDER BY sortOrder ASC, createdAt ASC")
                .page(page, size)
                .list()
                .stream()
                .map(e -> RegionDTO.from((Region) e))
                .toList();
        return new PageResult<>(content, page, size, Region.count());
    }

    @Transactional
    public RegionDTO create(RegionDTO dto) {
        if (Region.find("code", dto.code).firstResult() != null) {
            throw new BusinessException("区域编码已存在: " + dto.code);
        }
        Region entity = new Region();
        entity.code = dto.code;
        entity.name = dto.name;
        entity.sortOrder = dto.sortOrder != null ? dto.sortOrder : 0;
        entity.persist();
        return RegionDTO.from(entity);
    }

    @Transactional
    public RegionDTO update(UUID id, RegionDTO dto) {
        Region entity = Region.findById(id);
        if (entity == null) throw new BusinessException(404, "区域不存在");
        if (dto.name != null) entity.name = dto.name;
        if (dto.sortOrder != null) entity.sortOrder = dto.sortOrder;
        return RegionDTO.from(entity);
    }

    @Transactional
    public RegionDTO updateStatus(UUID id, String status) {
        Region entity = Region.findById(id);
        if (entity == null) throw new BusinessException(404, "区域不存在");
        if ("DISABLED".equals(status)) {
            long linkedUsers = com.cpq.system.entity.User.count("regionId = ?1 AND status = 'ACTIVE'", id);
            if (linkedUsers > 0) {
                throw new BusinessException("该区域有 " + linkedUsers + " 个在用用户，无法停用");
            }
        }
        entity.status = status;
        return RegionDTO.from(entity);
    }
}
```

- [ ] **Step 6: Create RegionResource**

Create `cpq-backend/src/main/java/com/cpq/system/resource/RegionResource.java`:

```java
package com.cpq.system.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.system.dto.RegionDTO;
import com.cpq.system.service.RegionService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;

@Path("/api/cpq/regions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RegionResource {

    @Inject
    RegionService regionService;

    @GET
    public ApiResponse<PageResult<RegionDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size) {
        return ApiResponse.success(regionService.list(page, size));
    }

    @POST
    public ApiResponse<RegionDTO> create(@Valid RegionDTO dto) {
        return ApiResponse.success(regionService.create(dto));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<RegionDTO> update(@PathParam("id") UUID id, RegionDTO dto) {
        return ApiResponse.success(regionService.update(id, dto));
    }

    @PATCH
    @Path("/{id}")
    public ApiResponse<RegionDTO> updateStatus(@PathParam("id") UUID id, RegionDTO dto) {
        return ApiResponse.success(regionService.updateStatus(id, dto.status));
    }
}
```

- [ ] **Step 7: Create User entity stub (needed by RegionService)**

Create `cpq-backend/src/main/java/com/cpq/system/entity/User.java`:

```java
package com.cpq.system.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "\"user\"")
public class User extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public UUID id;

    @Column(nullable = false, unique = true, length = 100)
    public String username;

    @Column(name = "full_name", nullable = false, length = 200)
    public String fullName;

    @Column(nullable = false, unique = true, length = 200)
    public String email;

    @Column(name = "password_hash", nullable = false)
    public String passwordHash;

    @Column(nullable = false, length = 30)
    public String role;

    @Column(name = "region_id")
    public UUID regionId;

    @Column(name = "department_id")
    public UUID departmentId;

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

    @Column(name = "is_first_login", nullable = false)
    public Boolean isFirstLogin = true;

    @Column(name = "initial_password_expires_at")
    public OffsetDateTime initialPasswordExpiresAt;

    @Column(name = "failed_login_attempts", nullable = false)
    public Integer failedLoginAttempts = 0;

    @Column(name = "locked_until")
    public OffsetDateTime lockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (updatedAt == null) updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
```

- [ ] **Step 8: Run tests to verify they pass**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw test -Dtest=RegionResourceTest
```

Expected: All 5 tests pass.

- [ ] **Step 9: Commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-backend/src/
git commit -m "feat(system): add Region CRUD API with tests"
```

---

### Task 2: Department Entity + CRUD API

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/system/entity/Department.java`
- Create: `cpq-backend/src/main/java/com/cpq/system/dto/DepartmentDTO.java`
- Create: `cpq-backend/src/main/java/com/cpq/system/service/DepartmentService.java`
- Create: `cpq-backend/src/main/java/com/cpq/system/resource/DepartmentResource.java`
- Create: `cpq-backend/src/test/java/com/cpq/system/resource/DepartmentResourceTest.java`

- [ ] **Step 1: Write failing tests for Department CRUD**

Create `cpq-backend/src/test/java/com/cpq/system/resource/DepartmentResourceTest.java`:

```java
package com.cpq.system.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.*;

@QuarkusTest
class DepartmentResourceTest {

    @Test
    void listDepartments() {
        RestAssured.given()
            .when().get("/api/cpq/departments")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.content.size()", greaterThanOrEqualTo(3));
    }

    @Test
    void createDepartment() {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {"code":"SALES_DEPT_4","name":"销售四部","sortOrder":4}
                """)
            .when().post("/api/cpq/departments")
            .then()
                .statusCode(200)
                .body("data.code", equalTo("SALES_DEPT_4"))
                .body("data.name", equalTo("销售四部"));
    }

    @Test
    void createDepartmentDuplicateCodeFails() {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {"code":"SALES_DEPT_1","name":"重复部门","sortOrder":99}
                """)
            .when().post("/api/cpq/departments")
            .then()
                .statusCode(400);
    }

    @Test
    void disableDepartment() {
        String id = RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {"code":"DEPT_TO_DISABLE","name":"待停用部门","sortOrder":99}
                """)
            .when().post("/api/cpq/departments")
            .then().extract()
            .jsonPath().getString("data.id");

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {"status":"DISABLED"}
                """)
            .when().patch("/api/cpq/departments/" + id)
            .then()
                .statusCode(200)
                .body("data.status", equalTo("DISABLED"));
    }
}
```

- [ ] **Step 2: Run tests — expect failure**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw test -Dtest=DepartmentResourceTest
```

- [ ] **Step 3: Create Department entity**

Create `cpq-backend/src/main/java/com/cpq/system/entity/Department.java`:

```java
package com.cpq.system.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "department")
public class Department extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public UUID id;

    @Column(nullable = false, unique = true, length = 50)
    public String code;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(name = "sort_order")
    public Integer sortOrder = 0;

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
```

- [ ] **Step 4: Create DepartmentDTO**

Create `cpq-backend/src/main/java/com/cpq/system/dto/DepartmentDTO.java`:

```java
package com.cpq.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public class DepartmentDTO {

    public UUID id;

    @NotBlank(message = "部门编码不能为空")
    @Size(max = 50)
    public String code;

    @NotBlank(message = "部门名称不能为空")
    @Size(max = 100)
    public String name;

    public Integer sortOrder;
    public String status;
    public OffsetDateTime createdAt;

    public static DepartmentDTO from(Department entity) {
        DepartmentDTO dto = new DepartmentDTO();
        dto.id = entity.id;
        dto.code = entity.code;
        dto.name = entity.name;
        dto.sortOrder = entity.sortOrder;
        dto.status = entity.status;
        dto.createdAt = entity.createdAt;
        return dto;
    }
}
```

Note: The `from()` method references `Department` entity directly — make sure the import is `com.cpq.system.entity.Department`.

- [ ] **Step 5: Create DepartmentService**

Create `cpq-backend/src/main/java/com/cpq/system/service/DepartmentService.java`:

```java
package com.cpq.system.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.system.dto.DepartmentDTO;
import com.cpq.system.entity.Department;
import com.cpq.system.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class DepartmentService {

    public PageResult<DepartmentDTO> list(int page, int size) {
        List<DepartmentDTO> content = Department.find("ORDER BY sortOrder ASC, createdAt ASC")
                .page(page, size)
                .list()
                .stream()
                .map(e -> DepartmentDTO.from((Department) e))
                .toList();
        return new PageResult<>(content, page, size, Department.count());
    }

    @Transactional
    public DepartmentDTO create(DepartmentDTO dto) {
        if (Department.find("code", dto.code).firstResult() != null) {
            throw new BusinessException("部门编码已存在: " + dto.code);
        }
        Department entity = new Department();
        entity.code = dto.code;
        entity.name = dto.name;
        entity.sortOrder = dto.sortOrder != null ? dto.sortOrder : 0;
        entity.persist();
        return DepartmentDTO.from(entity);
    }

    @Transactional
    public DepartmentDTO update(UUID id, DepartmentDTO dto) {
        Department entity = Department.findById(id);
        if (entity == null) throw new BusinessException(404, "部门不存在");
        if (dto.name != null) entity.name = dto.name;
        if (dto.sortOrder != null) entity.sortOrder = dto.sortOrder;
        return DepartmentDTO.from(entity);
    }

    @Transactional
    public DepartmentDTO updateStatus(UUID id, String status) {
        Department entity = Department.findById(id);
        if (entity == null) throw new BusinessException(404, "部门不存在");
        if ("DISABLED".equals(status)) {
            long linkedUsers = User.count("departmentId = ?1 AND status = 'ACTIVE'", id);
            if (linkedUsers > 0) {
                throw new BusinessException("该部门有 " + linkedUsers + " 个在用用户，无法停用");
            }
        }
        entity.status = status;
        return DepartmentDTO.from(entity);
    }
}
```

- [ ] **Step 6: Create DepartmentResource**

Create `cpq-backend/src/main/java/com/cpq/system/resource/DepartmentResource.java`:

```java
package com.cpq.system.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.system.dto.DepartmentDTO;
import com.cpq.system.service.DepartmentService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;

@Path("/api/cpq/departments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DepartmentResource {

    @Inject
    DepartmentService departmentService;

    @GET
    public ApiResponse<PageResult<DepartmentDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size) {
        return ApiResponse.success(departmentService.list(page, size));
    }

    @POST
    public ApiResponse<DepartmentDTO> create(@Valid DepartmentDTO dto) {
        return ApiResponse.success(departmentService.create(dto));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<DepartmentDTO> update(@PathParam("id") UUID id, DepartmentDTO dto) {
        return ApiResponse.success(departmentService.update(id, dto));
    }

    @PATCH
    @Path("/{id}")
    public ApiResponse<DepartmentDTO> updateStatus(@PathParam("id") UUID id, DepartmentDTO dto) {
        return ApiResponse.success(departmentService.updateStatus(id, dto.status));
    }
}
```

- [ ] **Step 7: Run tests — expect pass**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw test -Dtest=DepartmentResourceTest
```

Expected: All 4 tests pass.

- [ ] **Step 8: Commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-backend/src/
git commit -m "feat(system): add Department CRUD API with tests"
```

---

### Task 3: OperationLog Entity + Service

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/system/entity/OperationLog.java`
- Create: `cpq-backend/src/main/java/com/cpq/system/service/OperationLogService.java`

- [ ] **Step 1: Create OperationLog entity**

Create `cpq-backend/src/main/java/com/cpq/system/entity/OperationLog.java`:

```java
package com.cpq.system.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "operation_log")
public class OperationLog extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public UUID id;

    @Column(name = "operator_id", nullable = false)
    public UUID operatorId;

    @Column(name = "operation_type", nullable = false, length = 50)
    public String operationType;

    @Column(name = "target_type", nullable = false, length = 50)
    public String targetType;

    @Column(name = "target_id")
    public UUID targetId;

    @Column
    public String summary;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
```

- [ ] **Step 2: Create OperationLogService**

Create `cpq-backend/src/main/java/com/cpq/system/service/OperationLogService.java`:

```java
package com.cpq.system.service;

import com.cpq.system.entity.OperationLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.UUID;

@ApplicationScoped
public class OperationLogService {

    @Transactional
    public void log(UUID operatorId, String operationType, String targetType, UUID targetId, String summary) {
        OperationLog entry = new OperationLog();
        entry.operatorId = operatorId;
        entry.operationType = operationType;
        entry.targetType = targetType;
        entry.targetId = targetId;
        entry.summary = summary;
        entry.persist();
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-backend/src/main/java/com/cpq/system/entity/OperationLog.java cpq-backend/src/main/java/com/cpq/system/service/OperationLogService.java
git commit -m "feat(system): add OperationLog entity and service"
```

---

### Task 4: User CRUD API + BCrypt Password

**Files:**
- Modify: `cpq-backend/pom.xml` (add jBCrypt dependency)
- Create: `cpq-backend/src/main/java/com/cpq/system/dto/UserDTO.java`
- Create: `cpq-backend/src/main/java/com/cpq/system/dto/CreateUserRequest.java`
- Create: `cpq-backend/src/main/java/com/cpq/system/dto/UpdateUserRequest.java`
- Create: `cpq-backend/src/main/java/com/cpq/system/service/UserService.java`
- Create: `cpq-backend/src/main/java/com/cpq/system/resource/UserResource.java`
- Create: `cpq-backend/src/test/java/com/cpq/system/resource/UserResourceTest.java`

- [ ] **Step 1: Add jBCrypt dependency to pom.xml**

Add to `cpq-backend/pom.xml` in the `<dependencies>` section:

```xml
    <dependency>
      <groupId>org.mindrot</groupId>
      <artifactId>jbcrypt</artifactId>
      <version>0.4</version>
    </dependency>
```

- [ ] **Step 2: Write failing tests**

Create `cpq-backend/src/test/java/com/cpq/system/resource/UserResourceTest.java`:

```java
package com.cpq.system.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.*;

@QuarkusTest
class UserResourceTest {

    @Test
    void listUsers() {
        RestAssured.given()
            .when().get("/api/cpq/users")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.content.size()", greaterThanOrEqualTo(1));
    }

    @Test
    void createUser() {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "username": "testuser1",
                  "fullName": "测试用户",
                  "email": "test1@cpq.com",
                  "role": "SALES_REP"
                }
                """)
            .when().post("/api/cpq/users")
            .then()
                .statusCode(200)
                .body("data.username", equalTo("testuser1"))
                .body("data.role", equalTo("SALES_REP"))
                .body("data.isFirstLogin", equalTo(true))
                .body("data.initialPassword", notNullValue());
    }

    @Test
    void createUserDuplicateUsernameFails() {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "username": "admin",
                  "fullName": "重复管理员",
                  "email": "dup@cpq.com",
                  "role": "SYSTEM_ADMIN"
                }
                """)
            .when().post("/api/cpq/users")
            .then()
                .statusCode(400);
    }

    @Test
    void updateUser() {
        // Get admin user ID
        String id = RestAssured.given()
            .when().get("/api/cpq/users")
            .then().extract()
            .jsonPath().getString("data.content.find { it.username == 'admin' }.id");

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {"fullName": "超级管理员"}
                """)
            .when().put("/api/cpq/users/" + id)
            .then()
                .statusCode(200)
                .body("data.fullName", equalTo("超级管理员"));
    }

    @Test
    void disableLastAdminFails() {
        String id = RestAssured.given()
            .when().get("/api/cpq/users")
            .then().extract()
            .jsonPath().getString("data.content.find { it.username == 'admin' }.id");

        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {"status": "INACTIVE"}
                """)
            .when().patch("/api/cpq/users/" + id)
            .then()
                .statusCode(400)
                .body("message", containsString("管理员"));
    }
}
```

- [ ] **Step 3: Run tests — expect failure**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw test -Dtest=UserResourceTest
```

- [ ] **Step 4: Create UserDTO**

Create `cpq-backend/src/main/java/com/cpq/system/dto/UserDTO.java`:

```java
package com.cpq.system.dto;

import com.cpq.system.entity.User;
import java.time.OffsetDateTime;
import java.util.UUID;

public class UserDTO {

    public UUID id;
    public String username;
    public String fullName;
    public String email;
    public String role;
    public UUID regionId;
    public UUID departmentId;
    public String status;
    public Boolean isFirstLogin;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    // Only set on create response, not persisted
    public String initialPassword;

    public static UserDTO from(User entity) {
        UserDTO dto = new UserDTO();
        dto.id = entity.id;
        dto.username = entity.username;
        dto.fullName = entity.fullName;
        dto.email = entity.email;
        dto.role = entity.role;
        dto.regionId = entity.regionId;
        dto.departmentId = entity.departmentId;
        dto.status = entity.status;
        dto.isFirstLogin = entity.isFirstLogin;
        dto.createdAt = entity.createdAt;
        dto.updatedAt = entity.updatedAt;
        return dto;
    }
}
```

- [ ] **Step 5: Create CreateUserRequest**

Create `cpq-backend/src/main/java/com/cpq/system/dto/CreateUserRequest.java`:

```java
package com.cpq.system.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public class CreateUserRequest {

    @NotBlank(message = "用户名不能为空")
    public String username;

    @NotBlank(message = "姓名不能为空")
    public String fullName;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    public String email;

    @NotBlank(message = "角色不能为空")
    @Pattern(regexp = "SALES_REP|SALES_MANAGER|PRICING_MANAGER|SYSTEM_ADMIN", message = "无效的角色")
    public String role;

    public UUID regionId;
    public UUID departmentId;
}
```

- [ ] **Step 6: Create UpdateUserRequest**

Create `cpq-backend/src/main/java/com/cpq/system/dto/UpdateUserRequest.java`:

```java
package com.cpq.system.dto;

import java.util.UUID;

public class UpdateUserRequest {

    public String fullName;
    public String email;
    public String role;
    public UUID regionId;
    public UUID departmentId;
    public String status;
}
```

- [ ] **Step 7: Create UserService**

Create `cpq-backend/src/main/java/com/cpq/system/service/UserService.java`:

```java
package com.cpq.system.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.system.dto.CreateUserRequest;
import com.cpq.system.dto.UpdateUserRequest;
import com.cpq.system.dto.UserDTO;
import com.cpq.system.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.mindrot.jbcrypt.BCrypt;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class UserService {

    @Inject
    OperationLogService operationLogService;

    public PageResult<UserDTO> list(int page, int size, String role, String status, String keyword) {
        StringBuilder query = new StringBuilder("1=1");
        java.util.Map<String, Object> params = new java.util.HashMap<>();

        if (role != null && !role.isBlank()) {
            query.append(" AND role = :role");
            params.put("role", role);
        }
        if (status != null && !status.isBlank()) {
            query.append(" AND status = :status");
            params.put("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.append(" AND (username LIKE :kw OR fullName LIKE :kw OR email LIKE :kw)");
            params.put("kw", "%" + keyword + "%");
        }

        long total = User.count(query.toString(), params);
        List<UserDTO> content = User.find(query + " ORDER BY createdAt DESC", params)
                .page(page, size)
                .list()
                .stream()
                .map(e -> UserDTO.from((User) e))
                .toList();
        return new PageResult<>(content, page, size, total);
    }

    @Transactional
    public UserDTO create(CreateUserRequest req) {
        if (User.find("username", req.username).firstResult() != null) {
            throw new BusinessException("用户名已存在: " + req.username);
        }
        if (User.find("email", req.email).firstResult() != null) {
            throw new BusinessException("邮箱已存在: " + req.email);
        }

        String initialPassword = generatePassword(10);
        String hash = BCrypt.hashpw(initialPassword, BCrypt.gensalt(12));

        User user = new User();
        user.username = req.username;
        user.fullName = req.fullName;
        user.email = req.email;
        user.passwordHash = hash;
        user.role = req.role;
        user.regionId = req.regionId;
        user.departmentId = req.departmentId;
        user.isFirstLogin = true;
        user.initialPasswordExpiresAt = OffsetDateTime.now().plusHours(24);
        user.persist();

        UserDTO dto = UserDTO.from(user);
        dto.initialPassword = initialPassword;
        return dto;
    }

    @Transactional
    public UserDTO update(UUID id, UpdateUserRequest req) {
        User user = User.findById(id);
        if (user == null) throw new BusinessException(404, "用户不存在");

        if (req.fullName != null) user.fullName = req.fullName;
        if (req.email != null) {
            User existing = User.find("email = ?1 AND id != ?2", req.email, id).firstResult();
            if (existing != null) throw new BusinessException("邮箱已被使用: " + req.email);
            user.email = req.email;
        }
        if (req.role != null) {
            if ("SYSTEM_ADMIN".equals(user.role) && !"SYSTEM_ADMIN".equals(req.role)) {
                long adminCount = User.count("role = 'SYSTEM_ADMIN' AND status = 'ACTIVE'");
                if (adminCount <= 1) {
                    throw new BusinessException("系统中必须至少保留一个系统管理员");
                }
            }
            user.role = req.role;
        }
        if (req.regionId != null) user.regionId = req.regionId;
        if (req.departmentId != null) user.departmentId = req.departmentId;

        return UserDTO.from(user);
    }

    @Transactional
    public UserDTO updateStatus(UUID id, String status) {
        User user = User.findById(id);
        if (user == null) throw new BusinessException(404, "用户不存在");

        if ("INACTIVE".equals(status) && "SYSTEM_ADMIN".equals(user.role)) {
            long adminCount = User.count("role = 'SYSTEM_ADMIN' AND status = 'ACTIVE'");
            if (adminCount <= 1) {
                throw new BusinessException("系统中必须至少保留一个有效的系统管理员账号");
            }
        }
        user.status = status;
        return UserDTO.from(user);
    }

    @Transactional
    public String resetPassword(UUID id) {
        User user = User.findById(id);
        if (user == null) throw new BusinessException(404, "用户不存在");

        String newPassword = generatePassword(10);
        user.passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(12));
        user.isFirstLogin = true;
        user.initialPasswordExpiresAt = OffsetDateTime.now().plusHours(24);
        return newPassword;
    }

    private String generatePassword(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
```

- [ ] **Step 8: Create UserResource**

Create `cpq-backend/src/main/java/com/cpq/system/resource/UserResource.java`:

```java
package com.cpq.system.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.system.dto.CreateUserRequest;
import com.cpq.system.dto.UpdateUserRequest;
import com.cpq.system.dto.UserDTO;
import com.cpq.system.service.UserService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource {

    @Inject
    UserService userService;

    @GET
    public ApiResponse<PageResult<UserDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("role") String role,
            @QueryParam("status") String status,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(userService.list(page, size, role, status, keyword));
    }

    @POST
    public ApiResponse<UserDTO> create(@Valid CreateUserRequest request) {
        return ApiResponse.success(userService.create(request));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<UserDTO> update(@PathParam("id") UUID id, UpdateUserRequest request) {
        return ApiResponse.success(userService.update(id, request));
    }

    @PATCH
    @Path("/{id}")
    public ApiResponse<UserDTO> updateStatus(@PathParam("id") UUID id, UpdateUserRequest request) {
        return ApiResponse.success(userService.updateStatus(id, request.status));
    }

    @POST
    @Path("/{id}/reset-password")
    public ApiResponse<Map<String, String>> resetPassword(@PathParam("id") UUID id) {
        String newPassword = userService.resetPassword(id);
        return ApiResponse.success(Map.of("initialPassword", newPassword));
    }
}
```

- [ ] **Step 9: Run all tests**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw test -Dtest=UserResourceTest
```

Expected: All 5 tests pass.

- [ ] **Step 10: Run full test suite to ensure no regressions**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw test
```

Expected: All tests pass (HealthResourceTest + RegionResourceTest + DepartmentResourceTest + UserResourceTest).

- [ ] **Step 11: Commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-backend/
git commit -m "feat(system): add User CRUD API with BCrypt passwords and tests"
```

---

## Self-Review Results

**1. Spec coverage:**
- Region CRUD (list/create/update/disable + disable protection): Task 1 ✅
- Department CRUD (list/create/update/disable + disable protection): Task 2 ✅
- OperationLog infrastructure: Task 3 ✅
- User CRUD (list/create/update/disable + last admin protection + reset password): Task 4 ✅
- User entity with all fields (isFirstLogin, initialPasswordExpiresAt, failedLoginAttempts, lockedUntil): Task 1 Step 7 ✅
- BCrypt password hashing (salt rounds 12): Task 4 ✅
- Auto-generate initial password on user creation: Task 4 ✅
- Region/Department dictionary referenced by User: Task 4 ✅

**2. Placeholder scan:** No TBD/TODO found. All steps have complete code.

**3. Type consistency:** `RegionDTO.from(Region)`, `DepartmentDTO.from(Department)`, `UserDTO.from(User)` all consistent. `BusinessException` used uniformly across all services. `ApiResponse.success()` wrapper consistent across all resources.
