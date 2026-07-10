# 接口契约 — 材质 name/symbol 语义修补（task-0708 · repair-1）

> 契约**基本不变**，仅 `name` 取值语义 + 搜索维度调整。Base path `/api/cpq/material-recipes`（沿用）。
> 全部字段结构与 task-0708 `api.md` 一致，本文只列**变化点**。

---

## 一、字段语义修正

| 字段 | task-0708 | repair-1 |
|------|-----------|----------|
| `symbol` | 语义「材质名称」 | **语义「化学式」**（Ag/AgC3）；DB 列名不变 |
| `name` | 导入/新建为 null，UI 隐藏 | **「名称」，默认=symbol**；可编辑；不再为 null |
| `specLabel` | 隐藏 | 仍隐藏（不变）|

---

## 二、GET /material-recipes（列表，搜索维度 +name）

- `keyword` 命中维度：**材质编号(code) / 化学式(symbol) / 名称(name) / 元素符号 / 元素中文名**（原 task-0708 无 name，本次新增）。
- 响应 `MaterialRecipeDTO[]`：`name` 字段**现返回非空值**（存量回填后 = symbol；新导入 = symbol；可被人工编辑）。
```json
{ "code":"00002", "symbol":"AgC3", "name":"AgC3", "specLabel":null, "recipeType":"locked", "status":"ACTIVE", "...":"..." }
```

---

## 三、POST /material-recipes（新建）/ PUT /{id}（编辑）

Body `MaterialRecipeUpsertRequest`（结构不变）：
- `name`：**可选**。传空/空白 → 后端落库 `name = symbol`（默认相同）；传具体值 → 用传入值。
- 前端编辑抽屉现收集 `name`（原 task-0708 恒传 null）。
- `symbol`：语义为化学式，必填不变。
- `specLabel`：前端仍不收集（null）。

```json
{ "code":"00300", "symbol":"AgNi10", "name":"", "recipeType":"locked", "elements":[...] }
// name 留空 → 落库 name="AgNi10"
```

---

## 四、数据迁移（一次性，非接口）
`UPDATE material_recipe SET name = symbol WHERE name IS NULL` —— 回填 task-0708 导入的 253 条存量（name 现为 NULL）。详见 `backtask.md` RB3。

---

## 五、下游影响提示
- `ConfigureSearchResource` / `ConfigureProductService` 里的 `COALESCE(mr.name, mm.material_type, …)`：原 name 为 null 走 fallback，现 name 有值（=化学式或人工名）直接取，**语义仍是"材质名"，无破坏**。
- 选配/定价/渲染其余路径不受影响。
