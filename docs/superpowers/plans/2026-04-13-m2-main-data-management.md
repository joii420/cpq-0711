# M2: Main Data Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement Customer management (with multi-contact), Product management (with Excel import), and Process/ProductProcess seed data and selection UI.

**Architecture:** Same Panache entity → Service → Resource pattern from M1. Customer has one-to-many CustomerContact. Product has JSONB tags field. Process data is pre-seeded, not user-editable. Excel import via Apache POI.

**Tech Stack:** Java 17, Quarkus, Panache, Apache POI, PostgreSQL JSONB, Ant Design Table/Drawer/Upload

---

### Task 1: Flyway V2 — Customer/CustomerContact tables
- **Files:** `db/migration/V2__create_customer_tables.sql`
- **DDL:** Customer (id, name, code UNIQUE, level ENUM, industry, region, address, accumulated_amount, credit_limit, payment_method, remarks, status, version, timestamps) + CustomerContact (id, customer_id FK, name, role, phone, email, wechat, is_primary, timestamp)
- **Constraints:** code auto-generate via SEQUENCE (CUST-XXXX), at least one is_primary per customer (application-level)
- **Acceptance:** Tables created, no errors

### Task 2: Customer CRUD API
- **Files:** `customer/entity/Customer.java`, `customer/entity/CustomerContact.java`, `customer/dto/CustomerDTO.java`, `customer/dto/ContactDTO.java`, `customer/service/CustomerService.java`, `customer/resource/CustomerResource.java`
- **API endpoints:**
  - `GET /api/cpq/customers` — list with search (name/code/contact), level filter, pagination
  - `POST /api/cpq/customers` — create with contacts array
  - `PUT /api/cpq/customers/{id}` — update
  - `DELETE /api/cpq/customers/{id}` — soft delete (ACTIVE→INACTIVE), delete protection (check DRAFT/SUBMITTED/APPROVED quotations)
  - `DELETE /api/cpq/customers/batch` — batch soft delete
- **Business rules:** Optimistic lock (version field), auto-generate customer code, accumulated_amount read-only
- **Tests:** `CustomerResourceTest.java` — CRUD + search + delete protection + batch delete
- **Acceptance:** All tests pass, code auto-generation works

### Task 3: CustomerContact API
- **Files:** `customer/resource/CustomerContactResource.java`, `customer/service/CustomerContactService.java`
- **API endpoints:**
  - `GET /api/cpq/customers/{id}/contacts`
  - `POST /api/cpq/customers/{id}/contacts`
  - `PUT /api/cpq/customers/{customerId}/contacts/{contactId}`
  - `DELETE /api/cpq/customers/{customerId}/contacts/{contactId}` — prevent deleting last primary contact
  - `PUT /api/cpq/customers/{customerId}/contacts/{contactId}/set-primary`
- **Validation:** Phone 11-digit regex, at least one primary contact
- **Tests:** Contact CRUD + primary switch + last primary protection
- **Acceptance:** All tests pass

### Task 4: Customer management frontend
- **Files:** `pages/customer/CustomerManagement.tsx`, `services/customerService.ts`
- **UI:** List page with category tabs (全部/活跃/VIP/潜在/不活跃), search, pagination, batch delete, level badges; Detail/Edit Drawer: basic info + contact management (table with add/edit/delete/primary toggle) + business info + stats panel (read-only)
- **Acceptance:** CRUD works in browser, contact management works, search/filter works

### Task 5: Flyway V3 — Product table
- **Files:** `db/migration/V3__create_product_table.sql`
- **DDL:** Product (id, name, sku UNIQUE, category ENUM, specification, status, tags JSONB, external_id, last_synced_at, timestamps)
- **Acceptance:** Table created

### Task 6: Product CRUD API
- **Files:** `product/entity/Product.java`, `product/dto/ProductDTO.java`, `product/service/ProductService.java`, `product/resource/ProductResource.java`
- **API endpoints:**
  - `GET /api/cpq/products` — list with category filter, status filter, keyword search, pagination
  - `POST /api/cpq/products`
  - `PUT /api/cpq/products/{id}`
  - `DELETE /api/cpq/products/{id}` — soft delete with quotation check
- **Tests:** `ProductResourceTest.java`
- **Acceptance:** All tests pass

### Task 7: Product Excel import API
- **Files:** `product/service/ProductImportService.java`, modify `ProductResource.java`
- **API:** `POST /api/cpq/products/import` (multipart file upload)
- **Logic:** Apache POI parse; max 5000 rows; SKU duplicate → skip; return report {added, skipped, failed}
- **Tests:** Import with valid/invalid/duplicate data
- **Acceptance:** Upload 100-row Excel, correct report

### Task 8: Product management frontend
- **Files:** `pages/product/ProductManagement.tsx`, `services/productService.ts`
- **UI:** List with category/status filters, search, pagination; Create/Edit Drawer (name/SKU/category/spec/status/tags multi-select/remarks); Excel import button → upload modal → result report
- **Acceptance:** CRUD + import works in browser

### Task 9: Flyway V4 — Process/ProductProcess tables
- **Files:** `db/migration/V4__create_process_tables.sql`
- **DDL:** Process (id, code UNIQUE, name, description, category ENUM, is_required, sort_order, status, timestamp) + ProductProcess (id, product_id FK, process_id FK, timestamp)
- **Seed data:** 6 categories × 3-8 processes each = ~30 rows, codes MRO-XX-XXXX
- **Acceptance:** Tables + seed data created

### Task 10: Process API
- **Files:** `product/entity/Process.java`, `product/entity/ProductProcess.java`, `product/service/ProcessService.java`, `product/resource/ProcessResource.java`
- **API:**
  - `GET /api/cpq/processes` — list all by category
  - `GET /api/cpq/products/{id}/processes` — get product's bound processes
  - `POST /api/cpq/products/{id}/processes` — bind processes (array of process_ids)
  - `DELETE /api/cpq/products/{id}/processes` — unbind all
- **Tests:** List + bind/unbind
- **Acceptance:** All tests pass

### Task 11: Process selection frontend (Module 4)
- **Files:** `pages/product/ProcessSelection.tsx`
- **UI:** Left sidebar: 6 category tabs; Center: grid cards (name/code/desc/required badge/checkbox); Right: selected panel (categorized list, counts, remove, save/reset); Required processes disabled
- **Acceptance:** Select/deselect processes, required cannot be unchecked, save persists

### Task 12: Operation log integration
- **Modify:** CustomerService, ProductService — add OperationLogService calls on create/edit/delete
- **Acceptance:** Logs appear in operation_log table after CRUD operations

---

## Execution Order

```
V2 DDL(1) → Customer API(2) + Contact API(3) → Customer Frontend(4)
V3 DDL(5) → Product API(6) + Import(7) → Product Frontend(8)
V4 DDL(9) → Process API(10) → Process Frontend(11)
→ OpLog Integration(12)
```

## Verification Checklist
- [ ] Create customer with multiple contacts, set primary
- [ ] Search/filter customers by level/keyword
- [ ] Batch delete customers (soft delete)
- [ ] Delete protection when quotation exists (logic-only, no quotations yet)
- [ ] Create/edit/import products, Excel import report correct
- [ ] Process selection page: 6 categories, required processes locked
- [ ] Operation logs recorded for all CRUD operations
