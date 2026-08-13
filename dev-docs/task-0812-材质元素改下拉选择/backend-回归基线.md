# 后端回归基线 — task-0812 材质元素改下拉选择

> 采集时间：2026-08-12（改动前）。库：`10.177.152.12:5432/cpq_db_0724`（8081 dev server 实测确认，见 §3）。
> 用途：本任务后端零改动，本文件作为「合并前后对比」的基线快照，供测试期/主线亲验核对「没被误伤」。

## 1. §1 判定复核结论（逐条给证据）

| 判定项 | 结论 | 证据 |
|---|---|---|
| `ElementService.list()` 返回全状态、含 `elementNo`/`elementCode`/`elementName`/`status` | **成立** | `ElementService.java:37-74` SQL 无 `status='ACTIVE'` 过滤条件；`ElementDTO.java` 四字段齐全 |
| `ElementResource` 与 `MaterialRecipeResource` 读权限一致 | **成立** | 见 §2 原文对照 |
| `MaterialRecipeUpsertRequest.ElementUpsert` 字段集合（前端不需新增字段） | **成立** | `MaterialRecipeUpsertRequest.java:18-29`：`elementCode/elementName/defaultPct/minPct/maxPct/isLocked/sortOrder`，无 `elementNo` |
| `MaterialRecipeService.insertElement()` 不写 `element_no`（§4 缺口） | **成立** | 见 §4 |

## 2. 权限原文对照

`ElementResource.java:26-28`
```java
@Path("/api/cpq/elements")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class ElementResource {
```

`MaterialRecipeResource.java:36-38`
```java
@Path("/api/cpq/material-recipes")
@Produces(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class MaterialRecipeResource {
```

类级 `@RoleAllowed` 集合逐字相同（顺序也相同）。**结论成立**：能打开材质抽屉（读 `MaterialRecipeResource`）的角色必然能读 `ElementResource`。

## 3. 活体验证（8081 dev server，改动前）

```
$ curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/elements
401   # 未带 token，鉴权正常、应用在跑
```

用 `admin` / `Admin@2026` 登录（session cookie）后实测：

```
$ curl -s --noproxy '*' -b cookies.txt http://localhost:8081/api/cpq/elements
count = 37
statuses = {'ACTIVE'}
```

响应前 2 条（字段齐全，含 `elementNo`/`elementCode`/`elementName`/`status`/`referencedCount`/`codeLocked`）：
```json
{"id":"d75812a8-3b87-4362-a419-0e8a5b72e77f","elementNo":"10001","elementCode":"Ag","elementName":"银","status":"ACTIVE","referencedCount":138,"codeLocked":true,"createdAt":"2026-07-25T02:54:18.096001Z","updatedAt":"2026-07-25T02:54:18.096001Z","lastModifiedAt":"2026-08-10T01:51:12.738489Z"}
{"id":"b1c39e76-b9d2-43c2-a1d0-4425c4f63889","elementNo":"10005","elementCode":"Ni","elementName":"镍","status":"ACTIVE","referencedCount":65,"codeLocked":true,"createdAt":"2026-07-25T02:54:18.096001Z","updatedAt":"2026-07-25T02:54:18.096001Z","lastModifiedAt":"2026-08-05T02:29:29.706157Z"}
```

`referencedCount=138`（Ag / 10001）与 §4 中直接 SQL 算出的 `ref_by_element_no=138` 一致 — API 与 DB 口径对上。

## 4. 数据库基线快照（改动前）

库：`PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724`

### 4.1 `element` 表
```sql
SELECT count(*) AS total, count(*) FILTER (WHERE status='ACTIVE') AS active FROM element;
-- total=37, active=37
```

### 4.2 `material_recipe_element` 表
```sql
SELECT count(*) AS total, count(element_no) AS with_element_no,
       count(*) - count(element_no) AS null_element_no
FROM material_recipe_element;
-- total=621, with_element_no=619, null_element_no=2
```

### 4.3 `flyway_schema_history`
```sql
SELECT max(version::numeric) FROM flyway_schema_history;
-- max=385
```
> 合并后必须仍为 385（本任务不新增迁移）。

### 4.4 材质 `00005`（AC-7 正常材质，3 元素）
```
id=d6e44a1e-8cae-4fba-8582-17d2a28408ca, code=00005, symbol=AgNi25C2, recipe_type=locked, status=ACTIVE
```
`material_recipe_element` 全字段快照：

| element_code | element_name | default_pct | min_pct | max_pct | is_locked | sort_order | element_no |
|---|---|---|---|---|---|---|---|
| C | 碳 | 1.0000 | (空) | (空) | t | 1 | 10012 |
| Ni | 镍 | 23.0000 | (空) | (空) | t | 2 | 10005 |
| Ag | 银 | 76.0000 | (空) | (空) | t | 3 | 10001 |

### 4.5 材质 `992`（AC-8 脏数据，1 元素）
```
id=38218ebe-cc36-493e-83cc-42508e18734e, code=992, symbol=AgNi11#-Ⅰ, recipe_type=locked, status=ACTIVE
```
`material_recipe_element` 全字段快照：

| element_code | element_name | default_pct | min_pct | max_pct | is_locked | sort_order | element_no |
|---|---|---|---|---|---|---|---|
| 10001 | Ag | 100.0000 | (空) | (空) | t | 1 | (NULL) |

与需求文档 §1 描述的脏数据完全一致：`element_code='10001'` 是元素编号误填进符号列，`element_no` 为 NULL。

## 5. §4 缺口的实际影响量化

`MaterialRecipeService.java:668-682` `insertElement()` 原文：
```java
private void insertElement(UUID recipeId,
                           MaterialRecipeUpsertRequest.ElementUpsert e,
                           int seq) {
    MaterialRecipeElement el = new MaterialRecipeElement();
    el.recipeId = recipeId;
    el.elementCode = e.elementCode.trim();
    el.elementName = e.elementName == null ? e.elementCode.trim() : e.elementName.trim();
    el.defaultPct = e.defaultPct;
    el.minPct = e.minPct;
    el.maxPct = e.maxPct;
    el.isLocked = e.isLocked != null && e.isLocked;
    el.sortOrder = e.sortOrder == null ? seq : e.sortOrder;
    el.createdAt = OffsetDateTime.now();
    el.persist();
}
```
**确认：从未对 `el.elementNo` 赋值**，字段恒为实体默认值（NULL）。§4 判定成立。

**实际差值（用 SQL 精确算出）**：
```sql
SELECT e.element_no, e.element_code, e.element_name,
       COUNT(DISTINCT mre.id)  AS ref_by_element_no,
       COUNT(DISTINCT mre2.id) AS ref_by_element_code
FROM element e
LEFT JOIN material_recipe_element mre  ON mre.element_no   = e.element_no
LEFT JOIN material_recipe_element mre2 ON mre2.element_code = e.element_code
WHERE e.element_no IN ('10001','10004')
GROUP BY e.element_no, e.element_code, e.element_name;
```
结果：
| element_no | element_code | ref_by_element_no | ref_by_element_code |
|---|---|---|---|
| 10001 | Ag | 138 | 138 |
| 10004 | Sn | 18 | 18 |

**发现一个 backtask.md 未提及的细节，补充说明**：两种 join 口径（按 `element_no` vs 按 `element_code`）算出的 `referencedCount` **当前完全相等**。原因是：这 2 行 NULL 行本身 `element_code` 就是脏值（`'10001'`/`'10004'`，即误填的元素编号，不是真符号 `'Ag'`/`'Sn'`），无论按哪个字段 JOIN 都匹配不上任何 `element` 行 —— **它们当前对任何元素的 `referencedCount` 都不产生贡献，也不产生错误贡献**。也就是说，`referencedCount` 目前的「少算」现象在数值上恰好是 0（因为这 2 行本来就是废行，不该被计入任何元素）。真正的少算风险只会在这 2 行被 D4 逻辑重选修正之后才出现：修正后 `element_code` 会变成合法值（如 `Ag`），但因为 `insertElement()` 依旧不写 `element_no`，修正后的行仍然不会被 `referencedCount` 计入 —— 这才是 backtask.md §4 描述的「未来副作用」，与 BL-0163 的登记口径一致，只是发生时点在「脏数据被前端修正保存之后」而非现在。

**额外验证发现的连带影响（backtask.md 未明确写出，建议主线知悉）**：`MaterialRecipeService.update()`（`:628-657`）的 elements 更新策略是**先 `MaterialRecipeElement.delete("recipeId", id)` 全删，再逐行 `insertElement()` 重建**（`:649-654`），**与本次改动是否勾选/修改任何字段无关**。这意味着：
- **任何**材质（不只是脏数据行）只要走一次「打开编辑抽屉 → 保存」，其 `material_recipe_element` 所有行的 `element_no` 都会被清空为 NULL —— 例如 §4.4 中材质 `00005` 当前 3 行都有 `element_no`（10012/10005/10001），保存一次后会全部变 NULL。
- 这是**既有行为，非本任务引入**（`insertElement()`/`update()` 代码本次不改一行）。
- **AC-7 的字段对比清单本身已经排除了 `element_no`**（只比对 `element_code/element_name/default_pct/min_pct/max_pct/is_locked/sort_order`），所以 AC-7 测试**不会因此误报失败**——但测试期如果多此一举去对比 `element_no`，会看到看似「回归」的 NULL 化，**这不是本任务的 bug**，是 BL-0163 已知缺口在任意编辑路径上的自然放大。建议测试报告里显式提一句，避免被误判为新缺陷。

## 6. N+1 自检

不涉及后端代码改动，无新增循环、无新增查库。本文件的 SQL 全部是一次性人工执行的诊断查询，非应用运行时代码路径。

> N+1 自检：本次后端零改动，无新增循环与查库 ✅

## 7. 结论

- backtask.md §1 四条判定：**全部成立**，证据见 §1-§2。
- backtask.md §4 已知缺口：**成立**，且用 SQL 精确量化后发现影响比字面描述更细致（见 §5 两点补充），建议测试报告引用本文件 §5 避免误判。
- 8081 活体验证：鉴权正常（401）、登录后接口返回 37 条 / 全 ACTIVE / 字段齐全，与 DB 直查一致。
- 本任务后端**无需任何代码改动**，上述结论支持 backtask.md 的判定。
