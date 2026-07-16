# backtask.md — 后端任务（update-071501：三模板统一到「客户产品分类」轴）

> 依据：`优化需求说明.md §9`（澄清备案）+ `api.md`。**本次是"换轴"，工作量集中在字段改名 + 客户加字段 + 迁移**，无复杂新逻辑。
> 开发前必读：`CLAUDE.md`「修改后强制自检」；`docs/方案制定前必读.md`；本目录 `api.md`。
> 全程在 worktree 分支内开发，复用主工作区已运行的 8081 dev server 自检（不另起 server）。

---

## 0. 现状锚点（改动前先读这些真实位置）

| 对象 | 文件 | 关键行 |
|---|---|---|
| 选配模板实体 | `cpq-backend/src/main/java/com/cpq/seltemplate/entity/SelTemplate.java` | `industry_code` UNIQUE @L12 |
| 选配模板 Service | `.../seltemplate/service/SelTemplateService.java` | `getByIndustry` @L30；`upsert` @L52 |
| 选配模板 DTO | `.../seltemplate/dto/SelTemplateDTO.java` | `industryCode` 字段 |
| upsert 请求 | `.../seltemplate/dto/SelTemplateUpsertRequest.java` | `industryCode` 字段 |
| 有效模板 Service | `.../seltemplate/service/EffectiveTemplateService.java` | `DEFAULT_INDUSTRY="__DEFAULT__"` @L17；`getEffective` @L28（读 `customer.industryCode`） |
| 有效模板 DTO | `.../seltemplate/dto/EffectiveTemplateDTO.java` | `resolvedIndustryCode` 字段 |
| 选配模板 Resource | `.../seltemplate/resource/SelTemplateResource.java` | `/effective` @L28（入参 customerNo，不动） |
| 客户实体 | `.../customer/entity/Customer.java` | 加 `product_category_id` |
| 客户 Service | `.../customer/service/CustomerService.java` | `create` @L94（赋值区 @L112-124）；`update` @L150（@L155-163） |
| 客户 DTO / Request | `.../customer/dto/CustomerDTO.java` / `CreateCustomerRequest.java` | 加 `productCategoryId` |
| 报价模板匹配 | `.../template/service/TemplateService.java` | `matchCustomerQuoteTemplate` @L504（**不改**，仅入参来源变） |
| 产品分类实体 | `.../basicdata/entity/ProductCategory.java` | `code` UNIQUE / `name` |
| 选配落库（回归点） | `.../configure/service/ConfigureProductService.java` | `effectiveEnabledTypes` @L430 调 `getEffective`（**不改功能，需回归**） |
| Flyway 迁移目录 | `cpq-backend/src/main/resources/db/migration/` | 当前最大 **V336**（V327→V330 跳号） |

---

## B1. 数据库迁移（Flyway）⚠️ 版本号进场时先探再定

> **R1 共享 Flyway（移动靶）**：进场先 `ls db/migration | grep -oE '^V[0-9]+' | sort -n | tail` 看当前最大号，用其**后续号**，勿写死；不改已应用迁移的号/名（见记忆 `cpq-shared-flyway-history-churn`）。下文用 `V{N}` / `V{N+1}` 占位。
> **不要**手工 `psql -f`；文件放进 `db/migration/` 后 `touch` 一个 java 文件让 Quarkus dev 自动 `migrate-at-start`。

### B1.0 前置：保障"默认分类"存在（同一迁移或独立迁移最先跑）
```sql
-- 幂等 seed：product_category 无 name='默认分类' 则插入一条（code 用约定值 DEFAULT）
INSERT INTO product_category (id, code, name, status, sort_order, created_at, updated_at)
SELECT gen_random_uuid(), 'DEFAULT', '默认分类', 'ACTIVE', 0, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM product_category WHERE name = '默认分类');
```

### B1.1 `V{N}__customer_add_product_category.sql` — 客户加字段 + backfill
```sql
-- 1) 加列（先可空，便于 backfill）
ALTER TABLE customer ADD COLUMN IF NOT EXISTS product_category_id UUID;

-- 2) backfill：所有现有客户刷成"默认分类"（D3 杜绝空值）
UPDATE customer
   SET product_category_id = (SELECT id FROM product_category WHERE name = '默认分类' LIMIT 1)
 WHERE product_category_id IS NULL;

-- 3) 置非空约束（backfill 后）
ALTER TABLE customer ALTER COLUMN product_category_id SET NOT NULL;
-- 软引用：按项目惯例不加物理 FK（应用层校验）
```

### B1.2 `V{N+1}__sel_template_switch_to_product_category.sql` — 选配模板换轴 + 清空
```sql
-- 存量为测试数据，清空重配（D12：避免多行业模板撞"默认分类"唯一约束）
DELETE FROM sel_template_item_value;
DELETE FROM sel_template_item;
DELETE FROM sel_template;

-- 换轴：删旧 industry_code，加 product_category_id（UNIQUE，一产品分类一套）
ALTER TABLE sel_template DROP COLUMN industry_code;
ALTER TABLE sel_template ADD COLUMN product_category_id UUID NOT NULL;
ALTER TABLE sel_template ADD CONSTRAINT sel_template_product_category_uk UNIQUE (product_category_id);
```
> 若 `industry_code` 上有 UNIQUE 约束/索引名，DROP COLUMN 会一并去除；如报约束依赖，先 `DROP CONSTRAINT`。
> **DDL 后必须 `touch` 一个 java 文件强制 Quarkus 重启**（不止跑 Flyway）——见 CLAUDE.md「视图 DROP CASCADE/重建后必须重启」同理，清进程级缓存。

### B1.3 自检
```bash
PGPASSWORD=... psql ... -c "SELECT version, success FROM flyway_schema_history WHERE version IN ('{N}','{N+1}')"  -- success=t
PGPASSWORD=... psql ... -c "SELECT count(*) FROM customer WHERE product_category_id IS NULL"                       -- 期望 0
PGPASSWORD=... psql ... -c "\d sel_template"                                                                        -- 有 product_category_id + UNIQUE，无 industry_code
```

---

## B2. 客户实体/服务/DTO 加 `productCategoryId`

### B2.1 `Customer.java`
```java
@Column(name = "product_category_id")
public UUID productCategoryId;
```

### B2.2 `CreateCustomerRequest.java`
- 加 `public UUID productCategoryId;`（校验交后端兜底，不强制 @NotNull，避免存量兼容问题；见 B2.4）。

### B2.3 `CustomerDTO.java`
- 加 `public UUID productCategoryId;`；`from(...)` 里 `dto.productCategoryId = c.productCategoryId;`。
- **不加** `productCategoryName`（D5；前端映射）。

### B2.4 `CustomerService.create`（@L112-124 赋值区）
```java
customer.productCategoryId = request.productCategoryId != null
        ? request.productCategoryId
        : resolveDefaultCategoryId();   // 兜底"默认分类"，保证非空(D3)
```
新增私有方法（缓存/直查均可，低频）：
```java
private UUID resolveDefaultCategoryId() {
    ProductCategory pc = ProductCategory.find("name", "默认分类").firstResult();
    if (pc == null) throw new BusinessException(500, "系统缺少「默认分类」，请先在产品分类维护中创建");
    return pc.id;
}
```

### B2.5 `CustomerService.update`（@L155-163）
```java
if (request.productCategoryId != null) customer.productCategoryId = request.productCategoryId;
```
> **不改** list()（D5 不筛选、不展示，无需 join；保持无 N+1）。

---

## B3. 选配模板换轴（`industryCode → productCategoryId`）

> 全部机械改名，语义不变。**改完 grep 全工程确认无 `industryCode` 残留于 seltemplate 包**。

### B3.1 `SelTemplate.java`
```java
@Column(name = "product_category_id", nullable = false, unique = true)
public UUID productCategoryId;   // 原 String industryCode 删除
```

### B3.2 `SelTemplateUpsertRequest.java`
- `industryCode(String)` → `productCategoryId(UUID)`。

### B3.3 `SelTemplateDTO.java`
- `industryCode` → `productCategoryId(UUID)`；`from(t)` 同步。

### B3.4 `SelTemplateService.java`
- `getByIndustry(String industryCode)` → `getByCategory(UUID productCategoryId)`：`SelTemplate.find("productCategoryId", productCategoryId)`。
- `upsert`（@L52-58）：`SelTemplate.find("productCategoryId", req.productCategoryId)`；新建时 `t.productCategoryId = req.productCategoryId`。
- 其余（items/values 全量替换、delete）不变。

### B3.5 `SelTemplateResource.java`
- `/effective` **不动**（入参 customerNo）；其余 CRUD 透传，无需改逻辑。

---

## B4. EffectiveTemplateService 换轴（含兜底链 D10）

### `EffectiveTemplateService.getEffective(customerNo)`
```java
Customer customer = Customer.find("code", customerNo).firstResult();
UUID categoryId = customer == null ? null : customer.productCategoryId;

SelTemplateDTO tpl = null;
if (categoryId != null) {
    tpl = selTemplateService.getByCategory(categoryId);
    if (tpl != null) { out.resolvedCategoryId = categoryId; out.usedDefault = false; }
}
if (tpl == null) {                                   // 兜底"默认分类"（原 __DEFAULT__ 替身）
    UUID defId = resolveDefaultCategoryId();
    tpl = selTemplateService.getByCategory(defId);
    if (tpl != null) { out.resolvedCategoryId = defId; out.usedDefault = true; }
}
if (tpl == null) { out.hasTemplate = false; return out; }  // 报错提示（前端）
...
```
- 删除 `public static final String DEFAULT_INDUSTRY="__DEFAULT__";`。
- `EffectiveTemplateDTO`：`resolvedIndustryCode → resolvedCategoryId(UUID)`；`usedDefault`/`hasTemplate`/`templateId`/`params` 不变。
- `resolveDefaultCategoryId()` 逻辑同 B2.4（可抽公用工具，或各自实现）。

---

## B5. 报价单创建 commit — categoryId 一致性审计（api.md §5，2026-07-16 订正）

> **实测**：`ImportSessionService.commit()` 里 `categoryId` 是死字段——模板 id 由前端在调 commit **前**用 `match-customer-quote`/核价 list 预匹配好塞进请求体，commit **不匹配模板**。故不做"覆盖模板/重匹配"（越界 + 破坏 MIXED 手选语义）。"以客户为准"由前端只读锁定保证（fronttask F3）。

- 在 `ImportSessionService.commit()` 取 `session.customerId` 后，查一次 `Customer`，仅做防御性审计（不改任何持久化）：
  ```java
  Customer customer = Customer.findById(session.customerId);
  if (customer != null && req.categoryId != null
          && !req.categoryId.equals(customer.productCategoryId)) {
      LOG.warnf("commit categoryId mismatch: frontend=%s authoritative=%s customerId=%s",
                req.categoryId, customer.productCategoryId, session.customerId);
  }
  ```
- 平行路径 `basic-data-import/v6/quote`（`V6QuotationCommitService`）的 `categoryId` 同为死字段，前端未接，**不改**。
- 产品分类**不持久化到 quotation**；固化 `customer_template_id`/`costing_card_template_id`。

---

## B6. 回归红线（不改功能，必须验）

1. **选配落库**：`ConfigureProductService.effectiveEnabledTypes`(@L430) 消费 `getEffective` 返回的 DTO。
   - grep `resolvedIndustryCode` 全工程 → 若 ConfigureProductService 或他处引用，同步改 `resolvedCategoryId`。
   - 回归：选配抽屉候选值（材质/工序/元素 enabled 参数）按客户产品分类正确带出；选配落库（等价导入）不回归。
2. **报价/核价模板匹配**：`matchCustomerQuoteTemplate` 不改；回归报价单创建"客户专属→通用兜底→NONE"分支正常。
3. **行业维度**：`customer.industryCode`、报价单 `snapshot_customer_industry` 零改动（D8）。

---

## B7. 后端自检清单（宣告完成必附）

- [ ] `touch` java 强制重启 → 等 5-7s；`curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components` → 401。
- [ ] Flyway：`flyway_schema_history` 两条迁移 success=t；`customer.product_category_id` 0 空值；`sel_template` 有 `product_category_id` UNIQUE、无 `industry_code`。
- [ ] `GET /api/cpq/sel-templates/effective?customerNo=<有分类客户>` 返 `resolvedCategoryId` + `hasTemplate`。
- [ ] `POST /api/cpq/customers`（不传 productCategoryId）→ 落库为"默认分类" id（兜底生效）。
- [ ] grep 全工程无残留 `industryCode`（seltemplate 包）/ `resolvedIndustryCode` / `getByIndustry` / `DEFAULT_INDUSTRY`。
- [ ] N+1 审查：客户 list、选配模板 list、模板匹配无逐行查库。
- [ ] 完成宣告含一行「已自检」声明（编译/接口/Flyway 三态）。

> ⚠️ grep 用 `/usr/bin/grep -a`（本环境 grep=ugrep -I 会对中文注释多的大源文件静默返空，见记忆 `cpq-grep-ugrep-binary-pitfall`）。
