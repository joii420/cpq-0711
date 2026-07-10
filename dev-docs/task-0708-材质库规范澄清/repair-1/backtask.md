# 后端任务 — 材质 name/symbol 语义修补（task-0708 · repair-1）

> 技术总监下发 · 2026-07-09 · 优先级 P2（task-0708 材质库规范化的小修补）
> 配套：`fronttask.md`｜`api.md`
> **背景**：task-0708 决策#2 曾把 `symbol` UI 标签改为「材质名称」、隐藏 `name`、导入 `name` 置 NULL。本次**修正语义**——`symbol`=化学式、`name`=材质名称，导入时 name 默认=symbol，两者都展示。

---

## 零、语义修正（覆盖 task-0708 决策#2 的对应部分）

| 字段 | 旧（task-0708） | 新（repair-1） |
|---|---|---|
| `symbol` | UI 标签「材质名称」 | **UI 标签「化学式」**（Ag/AgC3 是化学式）|
| `name` | 隐藏、导入置 NULL | **展示为「名称」，可编辑，导入/新建默认 = symbol** |
| `spec_label`(配比) | 隐藏 | **仍隐藏（不变）** |

> DB 列结构不变（name 列 task-0708 就保留着），本次只改**取值 + UI 展示 + 搜索**。

---

## 一、任务拆分

| 任务 | 标题 | 规模 |
|------|------|------|
| RB1 | 导入 name 默认=symbol（改 `r.name = null` → symbol）| XS |
| RB2 | 新建/编辑 name 为空时默认=symbol | XS |
| RB3 | 存量 253 条回填迁移 `name = symbol WHERE name IS NULL` | XS |
| RB4 | 列表搜索关键字加 `name` | XS |

---

## 二、RB1 — 导入 name 默认=symbol

`MaterialRecipeImportService.java`（现约第 259 行 `r.name = null;  // 决策#2：导入置 NULL`）：
```java
r.name = g.symbol;   // repair-1：名称默认=化学式(symbol)
```
> `g.symbol` 即该材质的化学式（Excel「材质」列值，如 Ag）。

**验收**：重导真 `材质库.xlsx` → `material_recipe.name` 与 `symbol` 相同（如 00002 name=AgC3、symbol=AgC3）；253/1 基线不变。

---

## 三、RB2 — 新建/编辑 name 默认=symbol

`MaterialRecipeService.create/update`：入参 `req.name` 为空/空白时，落库 `name = req.symbol`。
```java
String name = (req.name == null || req.name.isBlank()) ? req.symbol : req.name;
```
> 编辑时用户若清空名称 → 回落 symbol；填了则用填的值（名称可编辑，见 fronttask）。

**验收**：POST 不传 name → 落库 name=symbol；传了 name → 用传入值。

---

## 四、RB3 — 存量回填迁移

```sql
-- Vxxx__material_recipe_name_default_symbol.sql（版本号取当前最大 +1，勿手工 psql）
-- task-0708 导入的存量材质 name 为 NULL；repair-1 语义为 name 默认=化学式(symbol)，回填之。
UPDATE material_recipe SET name = symbol, updated_at = NOW()
WHERE name IS NULL;
```
> 只填 NULL 的，不动已有 name（若有人工填过）。

**验收**：迁移 success=t；`SELECT count(*) FROM material_recipe WHERE name IS NULL` = 0；抽查 name=symbol。

---

## 五、RB4 — 列表搜索加 name

`MaterialRecipeService.list(keyword,...)` 的 keyword 命中条件，加 `name ILIKE`：
```sql
WHERE (mr.code ILIKE :kw OR mr.symbol ILIKE :kw OR mr.name ILIKE :kw
       OR EXISTS(... element ...))
```
（现有命中 code/symbol/元素，补 name）

**验收**：`GET /material-recipes?keyword=<某名称>` 能命中该材质。

---

## 六、自检清单
- [ ] RB3 迁移 success=t、`name IS NULL` = 0
- [ ] 重导真文件：name=symbol、253/1 不变
- [ ] `GET /material-recipes?keyword=` 按名称可搜
- [ ] 下游无回归：`ConfigureSearchResource` 的 `COALESCE(mr.name,…)` 现在拿到非空 name（原走 fallback，现直接用 name，语义仍是材质名，正常）
- [ ] 一行「已自检」声明
