# 后端任务文档 — 元素主表管理（task-0709 / BL-0040）

> 技术总监下发 · 2026-07-09 · 优先级 P2（承接 task-0708 材质库规范化）
> 配套：`fronttask.md`（前端）｜`api.md`（接口契约）
> **开工前必读**：本文「零、澄清结论」——元素模型已与业务方逐条锁定，禁止自行改变语义。

---

## 零、澄清结论（已锁定，不可自行更改）

| # | 决策点 | 结论 |
|---|--------|------|
| 1 | **元素主身份** | **`element_no`(元素编号 10001) = 不可改业务主键**；`element_code`(符号 Ag) = 可编辑属性；`element_name`(中文) = 随时可改。（业务方选 B 模型）|
| 2 | **符号锁** | `element_code` **被引用即锁死不可改**。"被引用" = 存在任一 `material_recipe_element` 按 `element_no` 引用了它。未引用时可改符号（须校验唯一）。|
| 3 | **删除模型** | **只软删（停用 status=INACTIVE），永不物理删除**。被引用的也能停用；停用后**不能再被新材质/新导入选用**，但历史材质靠 `element_no` join 照常显示。|
| 4 | **材质存编号** | `material_recipe_element` **加 `element_no` 列**（权威链）+ **保留现有 `element_code`/`element_name` 快照**。因符号锁保证被引用元素符号不变，快照恒与主表一致、永不过期 → **不动选配/定价/渲染的 element_code 读取**（影响面最小）。|
| 5 | **导入联动** | `syncElementMaster` 改**按 `element_no` upsert**；编号已存在→**不回写符号/中文**（尊重人工维护）；新编号→用 Excel 符号+字典中文新建；`material_recipe_element` 落库存 `element_no`。|
| 6 | **不动边界** | **不改** `ConfigureProductService`（选配）/ `costing_element_price` / `element_bom` / 材质管理页 / 材质导入元素明细渲染。它们继续用符号，因符号锁死恒一致。|

---

## 一、任务拆分总览

| 任务 | 标题 | 规模 | 依赖 |
|------|------|------|------|
| B1 | `element` 表升级 element_no 为业务主键（补号 + UNIQUE + NOT NULL）| S | — |
| B2 | `material_recipe_element` 加 `element_no` 列 + 存量回填迁移 | S | B1 |
| B3 | Element 实体/DTO（referencedCount）+ ElementService | M | B1 |
| B4 | Element CRUD 端点（list/create/update/softDelete + 符号锁校验）| M | B3 |
| B5 | 导入 `syncElementMaster` 改按 element_no upsert + material_recipe_element 存 element_no | M | B1,B2 |
| B6 | 测试（符号锁 / 停用 / 导入按编号 upsert 不覆盖符号 / 迁移）| M | B4,B5 |

---

## 二、B1 — `element` 表升级 element_no 为业务主键

### 2.1 现状
`element(id UUID PK, element_code VARCHAR UNIQUE NOT NULL, element_name NOT NULL, element_no VARCHAR NULL, status, ...)`。
`element_no` 当前**可空、无唯一约束**；有 2 个元素（`Au`/`CdO`，seed 来的）`element_no` 为 NULL。

### 2.2 迁移（Flyway，版本号取当前最大 +1，勿手工 psql）
```sql
-- Vxxx__element_no_as_business_key.sql
-- 1) 给无编号的元素补号（Au/CdO 等 seed 元素）。用保留段 90000+ 避免与 Excel 10000 段冲突。
--    子查询按 element_code 排序保证确定性。
WITH missing AS (
  SELECT id, row_number() OVER (ORDER BY element_code) AS rn
  FROM element WHERE element_no IS NULL
)
UPDATE element e SET element_no = (90000 + m.rn)::text
FROM missing m WHERE e.id = m.id;

-- 2) element_no 升为 NOT NULL + UNIQUE 业务主键
ALTER TABLE element ALTER COLUMN element_no SET NOT NULL;
ALTER TABLE element ADD CONSTRAINT uq_element_no UNIQUE (element_no);
-- element_code 保留 UNIQUE（符号仍不可重复；被引用锁在应用层，未引用可改但须唯一）
COMMENT ON COLUMN element.element_no IS '元素编号(业务主键, 不可改); element_code(符号)为可编辑属性,被引用即锁';
```

**验收**：迁移 success=t；`SELECT count(*) FROM element WHERE element_no IS NULL` = 0；`uq_element_no` 存在；Au/CdO 已补号（90001/90002 之类）。

---

## 三、B2 — `material_recipe_element` 加 element_no

### 3.1 迁移
```sql
-- Vyyy__material_recipe_element_add_element_no.sql
ALTER TABLE material_recipe_element ADD COLUMN IF NOT EXISTS element_no VARCHAR(32);
-- 存量回填：按当前 element_code 反查 element 主表拿 element_no
UPDATE material_recipe_element mre
   SET element_no = e.element_no
  FROM element e
 WHERE e.element_code = mre.element_code
   AND mre.element_no IS NULL;
CREATE INDEX IF NOT EXISTS idx_mre_element_no ON material_recipe_element(element_no);
COMMENT ON COLUMN material_recipe_element.element_no IS '权威元素链(→element.element_no); element_code/name 为随符号锁恒一致的快照';
```
> 不设 NOT NULL（历史脏 element_code 可能反查不到；留 NULL 容错）。回填后抽查覆盖率。

**验收**：迁移 success=t；`SELECT count(*) FILTER (WHERE element_no IS NULL) FROM material_recipe_element` 尽量小（记录未匹配的 element_code 供排查）。

---

## 四、B3 — 实体 / DTO / Service

### 4.1 Element 实体
`com.cpq.configure.entity.Element` 已存在（task-0708）。确认字段：id/element_code/element_name/element_no/status/created_at/updated_at。补 `updated_at` 若无。

### 4.2 DTO `ElementDTO`
```java
public UUID id;
public String elementNo;       // 业务主键
public String elementCode;     // 符号
public String elementName;     // 中文
public String status;          // ACTIVE/INACTIVE
public long referencedCount;   // 被引用材质数 = COUNT(material_recipe_element WHERE element_no = this)
public boolean codeLocked;     // referencedCount>0 → true（前端据此禁用符号输入）
public OffsetDateTime createdAt;
public OffsetDateTime updatedAt;
```

### 4.3 `ElementService`
- `list(String keyword)`：单条 SQL，LEFT JOIN 聚合 `material_recipe_element` 算 `referencedCount`（`GROUP BY`，禁 N+1）；`keyword` 命中 `element_no`/`element_code`/`element_name` ILIKE；排序 `ORDER BY (status='ACTIVE') DESC, updated_at DESC, created_at DESC`。`codeLocked = referencedCount>0`。
- `create(req)`：校验 `elementNo` 唯一、`elementCode` 唯一、均非空；status 默认 ACTIVE。
- `update(elementNo, req)`：按 `element_no` 定位；**element_no 不可改**（忽略/拒绝改动）；
  - **符号锁**：若 `referencedCount>0` 且 `req.elementCode != 现值` → 抛 `409「符号已被 N 个材质引用，不可修改」`；未引用时允许改但校验唯一。
  - `element_name`、`status` 随时可改。
- `softDelete(elementNo)`：status→INACTIVE（幂等）；不物理删。
- **停用不阻断历史**：停用只改 status，不动 material_recipe_element。

---

## 五、B4 — CRUD 端点

新增 `com.cpq.configure.resource.ElementResource`，`@Path("/api/cpq/elements")`，鉴权对齐 MaterialRecipeResource（读=多角色，写=SYSTEM_ADMIN）。契约见 `api.md`：
- `GET /elements?keyword=` → `List<ElementDTO>`
- `POST /elements` → 新建
- `PUT /elements/{elementNo}` → 编辑（符号锁在 service 校验）
- `DELETE /elements/{elementNo}` → 停用（204）

**验收**：`GET /elements?keyword=银` 命中；对被引用元素 PUT 改符号返 409；未引用元素可改符号；DELETE 后 status=INACTIVE。

---

## 六、B5 — 导入联动改造

改 `MaterialRecipeImportService.syncElementMaster`（现按 `element_code` ON CONFLICT）：
1. 收集 Excel `(元素编号 element_no, 符号 element_code, 中文)` 三元组，按 **element_no 去重**。
2. upsert 主表**按 element_no**：
   ```sql
   INSERT INTO element(element_no, element_code, element_name)
   VALUES (:no, :code, COALESCE(:dictName, :code))
   ON CONFLICT (element_no) DO NOTHING   -- 编号已存在 → 不回写符号/中文(尊重人工维护)
   ```
   > 用 `DO NOTHING` 而非 `DO UPDATE`：编号已存在的元素，符号/中文保持主表现值不动（决策#5）。
   > 边界：若 Excel 某 element_no 的符号与主表现值不同，**以主表为准**（不覆盖），可记 warning 供人工核对。
3. **material_recipe_element 落库存 element_no**：导入写元素明细时，`element_no` 取该行 Excel 编号；`element_code`/`element_name` 快照仍按现逻辑写（保持不变）。
4. 未在主表命中 element_no 的（Excel 新编号）→ 先插入主表再写材质明细。

**⚠️ AP-53 类协议纪律**：改 import 落 material_recipe_element 属协议链，改完必须用真 `材质库.xlsx` 复跑，断言 253 落库不变 + material_recipe_element.element_no 全部回填。

**验收**：真文件导入后 `material_recipe_element.element_no` 无 NULL；重复导入不覆盖人工改过的符号/中文；253/1 基线不变。

---

## 七、B6 — 测试

放 `cpq-backend/src/test/java/com/cpq/configure/`（worktree 内 `./mvnw test` 亲跑）：
1. **符号锁**：造被引用元素 → PUT 改符号断言 409；未引用元素 → 改符号成功。
2. **停用**：softDelete → status=INACTIVE；被引用元素可停用且 material_recipe_element 不受影响。
3. **referencedCount**：list 返回的被引用数 = 实际 material_recipe_element 计数。
4. **导入按编号 upsert**：先建 element_no=10001 符号=Ag 中文=银 → 导入 Excel(10001 符号写别的) → 断言主表符号/中文**不变**；新编号被新建。
5. **迁移**：B1 补号后无 NULL element_no + uq_element_no 生效；B2 回填后 material_recipe_element.element_no 覆盖率。
6. **导入 253/1 回归**：真文件导入 materialsUpserted=253、material_recipe_element.element_no 全回填。

---

## 八、完成自检清单

- [ ] B1/B2 迁移 Quarkus dev 自动 migrate、success=t；Au/CdO 补号；material_recipe_element.element_no 回填
- [ ] `GET /api/cpq/elements` 返 200/401（非 500）
- [ ] 被引用元素改符号返 409；未引用可改；停用软删
- [ ] 真文件导入：253 不变、material_recipe_element.element_no 全填、二次导入不覆盖人工符号/中文
- [ ] 无回归：材质管理页/材质导入元素明细渲染不变（读快照 element_code）、选配/定价不受影响
- [ ] 一行「已自检」声明（编译/端点/迁移 success=t/导入回归 四项证据）
