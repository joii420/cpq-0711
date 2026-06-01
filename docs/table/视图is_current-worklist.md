# 视图 / SQL 模板 is_current 过滤 — Task 9a 分类 worklist（活库实测）

> 产出自 `docs/table/报价系统版本号-视图is_current过滤-实现计划.md` Task 9a。
> 数据源：活库 `pg_views` / `component_sql_view` / `template_sql_view`（2026-06-02，flyway V279）。**迁移文件含大量被覆盖旧定义，不可作准。**
> 判定纪律：按 SQL 里**真正的 `FROM/JOIN <表>`** + `system_type`/`resource_group_no` 谓词，**不认列名误报**（如 `standard_labor_rate AS unit_price`）、**不靠视图名**。

## 摘要

- 引用串扫描命中 46 个 → 真正 `FROM/JOIN` 这 5 表的 **26 个**（20 个是列名/别名误报，剔除）。
- **IN-scope（需加 is_current）= 18 个**：4 PG 视图 + 13 component_sql_view + 1 template_sql_view（部分）。
- **OUT（核价 PRICING-only / 不读这些表）= 其余**。
- **capacity 无任何视图/模板 `FROM/JOIN`** → 经 Java driver 消费，见计划 Task 9c-2 专项。

## 必需性分级（按表的版本行为）

| 表 | 版本行为 | 过滤必需性 |
|----|----------|-----------|
| `unit_price`(QUOTE) | 多版本保留(is_current 翻转) | **必需** |
| `element_bom_item`(QUOTE) | 多版本保留(uq 含 characteristic) | **必需** |
| `plating_scheme`(全局) | 多版本保留 | **必需** |
| `material_bom_item`(QUOTE) | Q03/Q12 deleteNonCurrent → **只留当前版本** | 防御性(no-op，仍按 §6 统一加) |
| `material_bom`/`element_bom`(主表) | 主表保留历史版本 | 视图若读主表则**必需**(本批 IN-scope 对象均读子表 *_item，未直接读主表) |

---

## IN-scope ① — PG 视图（4，迁移 V280 `DROP+CREATE`）

| 视图 | 读的版本化表 | JOIN 类型 | 注入点 |
|------|-------------|----------|--------|
| `v_composite_child_elements` | element_bom_item (QUOTE) | FROM(inner) | WHERE 加 `AND <ebi>.is_current=TRUE` |
| `v_composite_child_materials` | material_bom_item (QUOTE) | FROM(inner) | WHERE(防御性) |
| `v_composite_child_processes` | unit_price (QUOTE) | FROM(inner) | WHERE 加 `AND <up>.is_current=TRUE` |
| `v_composite_child_weights` | material_bom_item (QUOTE) | FROM(inner) | WHERE(防御性) |

> 这 4 个是报价/组合产品渲染主链路（`ComponentDriverService` 读 `v_composite_child_*`）。全 INNER，is_current 进 WHERE 安全。

## IN-scope ② — component_sql_view（13，迁移 V281 `UPDATE sql_template`）

| sql_view_name | 读的版本化表 | JOIN | 注入点 |
|---------------|-------------|------|--------|
| `composite_child_elements_mirror` | element_bom_item✱ + material_bom_item(多处) | FROM/JOIN inner | 各 ebi/mbi 加 is_current |
| `composite_child_materials_mirror` | material_bom_item | FROM inner | WHERE(防御性) |
| `composite_child_processes_mirror` | material_bom_item | JOIN inner | ON/WHERE(防御性) |
| `composite_child_weights_mirror` | material_bom_item(2处) | FROM inner | WHERE(防御性) |
| `cz_view` | material_bom_item | FROM inner | WHERE(防御性) |
| `gx_view` | unit_price (QUOTE,price=MATERIAL) | FROM inner | **必需** WHERE 加 up.is_current |
| `ys_view` | element_bom_item✱ + material_bom_item + **LEFT JOIN material_bom_item** | 含 1 处 LEFT | ebi 进 WHERE(必需)；**LEFT JOIN 的 mbi 进 ON**(防御性，但仍按规则放 ON) |
| `zcj_bom` | material_bom_item | FROM inner | WHERE(防御性) |
| `zcj_view` | material_bom_item + unit_price (QUOTE) | FROM inner | **必需** up.is_current + mbi(防御) |
| `zpj_view` | material_bom_item | FROM inner | WHERE(防御性) |
| `v12_raw_bom` | material_bom_item | FROM inner | WHERE(防御性) |
| `v12_raw_element_bom` | element_bom_item✱ + material_bom_item | FROM/JOIN inner | ebi **必需** + mbi(防御) |
| `v12_plating_scheme` | plating_scheme (全局) | FROM inner | **必需** ps.is_current |

> ✱ = element_bom_item 多版本保留，**这些是真正必需**的过滤点。
> ⚠️ `ys_view` 是唯一含 LEFT JOIN 版本化表的对象 → 那处 is_current 必须进 ON 子句（防 LEFT→INNER 漏行）。

## IN-scope ③ — template_sql_view（1，部分，迁移 V281）

| sql_view_name | 读的版本化表 | 注入点 |
|---------------|-------------|--------|
| `summary_material` | element_bom_item (QUOTE,2处✱) + unit_price (**PRICING**,4处) | **只给 2 处 ebi system_type='QUOTE' 加 is_current**；unit_price PRICING 4 处**不动** |

---

## OUT-of-scope（本期不改，记录原因）

| 对象 | 原因 |
|------|------|
| component_sql_view: `v12_consumable_prod` `v12_finished_proc` `v12_incoming_proc` `v12_outsource_cost` `v12_packaging` `v12_plating_cost` | 读 unit_price 全部 `system_type='PRICING'`（核价，本期未版本化） |
| template_sql_view: `summary_part` `summary_plating_cost` | 读 unit_price `PRICING`，不读 plating_scheme 表（plating_cost 是列名） |
| PG: `v_c_*`(11个) / `v_c_summary_agg` / `v_costing_summary_full` / `v_q_component_merged` / `v_q_siemens_class1_costs` | **列名误报**：有输出列名 `unit_price`/`is_current`，但**不 `FROM/JOIN` 这 5 表**（如 `standard_labor_rate AS unit_price`） |
| component_sql_view: `v12_depreciation_cost` `v12_energy_aux_cost` `v12_energy_prod_cost` `v12_labor_cost` `v12_tooling_cost` | **列名误报**，不读这 5 表 |
| `capacity` 全部读取方 | 无任何视图/模板 `FROM/JOIN capacity` → 走 Java driver，见计划 Task 9c-2 |

---

## 给实现期（Phase B/C）的执行要点

1. **必需过滤点**（漏了会渲染重复行）：`gx_view` `zcj_view`(unit_price) / `v12_plating_scheme`(plating_scheme) / `summary_material` `ys_view` `v12_raw_element_bom` `composite_child_elements_mirror` `v_composite_child_elements`(element_bom_item) / `v_composite_child_processes`(unit_price)。
2. **LEFT JOIN 唯一处**：`ys_view` 的 `left join material_bom_item` → is_current 进 ON。
3. **防御性 material_bom_item 过滤**：按设计 §6「统一加」执行，但知其为 no-op（deleteNonCurrent 已保证只留当前）。
4. **每改一个**：PG 视图 V280 `DROP CASCADE+CREATE`（重建级联依赖）；config 模板 V281 `UPDATE`；**touch java 重启 + 验证单值非"(共N项)"**。
5. **summary_material 半改**：只动 element_bom_item 的 QUOTE 两处，unit_price PRICING 四处保留原样。
