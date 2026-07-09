# 后端任务文档 — 材质库规范化与导入（task-0708）

> 技术总监下发 · 2026-07-09 · 优先级 P0
> 配套文档：`fronttask.md`（前端）｜`api.md`（接口契约）
> **开工前必读**：本文「零、澄清结论（已锁定）」——8 个歧义点全部拍板，禁止自行改变语义。

---

## 零、澄清结论（已锁定，不可自行更改）

| # | 决策点 | 结论 |
|---|--------|------|
| 1 | **导入源 sheet** | **只读 `材质对应元素` + `材质编号` 两个 sheet**，其余 10 个 sheet（含 `材质对应料号` + 9 张隐藏草稿表）**一律忽略**。`材质编号` sheet 是"材质→材质编号"权威映射；`材质对应元素` 列 = `材质 \| 材质编号 \| 元素名称 \| 含量 \| 元素编号`。 |
| 2 | **字段映射** | `材质编号(00001)` → `material_recipe.code`（不可编辑主键 + 搜索键）；`材质(Ag)` → `material_recipe.symbol`（UI 标签改叫「材质名称」）；`name`/`spec_label` 导入置 NULL、管理 UI 隐藏，**DB 列保留**（下游 `ConfigureSearchResource`/`ConfigureProductService` 仍 `COALESCE(mr.name…)` 引用，删列会报错）。 |
| 3 | **含量口径** | Excel 含量是 0–1 小数（同材质相加=1）。**导入 ×100 归一存 100 制**（0.97→`default_pct=97.00`），与全系统 `composition_pct` 100 制一致（见反模式 V135 ×100 误伤史）。 |
| 4 | **料号关联** | 本期**完全不做**：不读 `材质对应料号`、不写任何"材质↔料号"绑定、隐藏关联料号 tab。**保留现有绑定表/字段/接口不动**（`material_master.material_recipe_id`、`/material-recipes/{id}/parts` 等照常存在）。料号功能后期再议。 |
| 5 | **元素主表** | **新增轻量元素主表 `element`**（符号 PK / 中文名 / 元素编号 / 状态）。seed 一份中文字典，导入时按符号 upsert 同步。**本期不做元素管理 UI**（元素 CRUD 后期再议）。 |
| 6 | **导入校验** | **严格校验 + 跳过 + 报告**：合规行落库，不合规行跳过并在返回报告里逐条列明原因。那 9 个纯数字"元素"（191/206/223/258/301/304/316/430/721）按脏数据跳过。**任一行不合规不影响整单**。 |
| 7 | **材质类型** | 导入/新建默认 `locked`（标准锁定）、全元素 `is_locked=true` 无 min/max；**三类型枚举保留不删**，编辑抽屉仍可改类型/填 min/max。选配后端逻辑**零改动**。 |
| 8 | **存量/覆盖语义** | **按材质编号增量 Upsert（合并）**：文件里有的材质→以文件为准（覆盖字段+重灌元素）；**文件里没有的材质→保持不动**。**不是清空重导**。另加**一条一次性迁移删除 12 条 demo seed 材质**（`AgCu85` 等符号 code），清理旧数据。 |

---

## 一、任务拆分总览

| 任务 | 标题 | 规模 | 依赖 |
|------|------|------|------|
| B1 | 元素主表 `element`：迁移 + 实体 + 中文字典 seed | S | — |
| B2 | 一次性迁移：删除 12 条 demo seed 材质 | XS | — |
| B3 | `material_recipe` 列表查询改造：全状态 + 关键字搜索 + 排序 + DTO 补时间字段 | S | — |
| B4 | 材质库导入服务：POI 解析 + 校验 + ×100 + Upsert + 元素主表同步 + 批量落库 | L | B1 |
| B5 | 导入接口 + 干净模板下载接口 | S | B4 |
| B6 | 测试：校验规则 / ×100 / Upsert 语义 / 性能（1000 行 <3s） | M | B4,B5 |

---

## 二、B1 — 元素主表 `element`

### 2.1 建表迁移（Flyway，版本号取当前最大 +1，勿手工 psql）
```sql
-- Vxxx__element_master.sql
CREATE TABLE IF NOT EXISTS element (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    element_code  VARCHAR(32)  NOT NULL UNIQUE,   -- 元素符号 Ag/Cu/SnO2/H70…(定价 join 键)
    element_name  VARCHAR(64)  NOT NULL,          -- 中文名(字典已知则中文,未知回退=符号)
    element_no    VARCHAR(32),                    -- Excel 元素编号 10001(内部字典号,当前无消费方,留存备用)
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_element_status CHECK (status IN ('ACTIVE','INACTIVE'))
);
COMMENT ON TABLE element IS '元素/组成项字典(符号=定价join键, 中文名展示, 元素编号留存)';
```
> ⚠️ `element_code` 存**符号**（Excel「元素名称」列），不是数字编号——必须与 `costing_element_price.element_code`（Ag/Cu/Ni/Au）对齐，否则定价接不上。

### 2.2 实体
`com.cpq.configure.entity.Element`（PanacheEntityBase，字段镜像上表）。

### 2.3 中文字典 seed（同一迁移内 `INSERT … ON CONFLICT (element_code) DO NOTHING`）
覆盖 Excel 37 种「元素」中可命名的约 28 种；纯数字 9 个不 seed（脏数据）：

| 符号 | 中文 | 符号 | 中文 | 符号 | 中文 |
|---|---|---|---|---|---|
| Ag | 银 | Cu | 铜 | Ni | 镍 |
| Al | 铝 | Fe | 铁 | Sn | 锡 |
| Zn | 锌 | Cr | 铬 | Mn | 锰 |
| Si | 硅 | P | 磷 | C | 碳 |
| Be | 铍 | Cd | 镉 | Ce | 铈 |
| In | 铟 | Ir | 铱 | Pt | 铂 |
| Pd | 钯 | W | 钨 | Au | 金 |
| SnO2 | 二氧化锡 | ZnO | 氧化锌 | CdO | 氧化镉 |
| WC | 碳化钨 | H70 | 黄铜H70 | DC04 | 冷轧钢DC04 |
| Ni36 | 铁镍合金Ni36 | Ni42 | 铁镍合金Ni42 | 不锈钢 | 不锈钢 |

> 导入时若遇字典外新符号：以 `element_name=符号` upsert 进 `element`（不阻断），后续可人工补中文。

**验收**：迁移 success=t；`SELECT count(*) FROM element` ≥ 28；`element_code='Ag'` 的 `element_name='银'`。

---

## 三、B2 — 一次性删除 demo seed 材质

### 3.1 迁移
```sql
-- Vyyy__purge_demo_material_recipes.sql
-- 清理 V171 注入的 12 条 demo 材质(code 为符号如 AgCu85),为真实材质库导入让路。
-- FK 安全: material_recipe_element ON DELETE CASCADE; material_master/mat_part.material_recipe_id ON DELETE SET NULL。
DELETE FROM material_recipe
WHERE code IN ('AgCu85','AgCu90','AgNi90','AgNi95','AgSnO2','AgSnO2b',
               'AgCdO','AgW60','AgW72','CuCr','AgPd','AuAg');
```
> 只删这 12 个已知 seed code，**不 truncate 全表**（防误删他人手工数据）。当前 DB 该批 0 料号绑定，删除零副作用。

**验收**：迁移 success=t；`SELECT count(*) FROM material_recipe WHERE code LIKE 'Ag%' OR code IN ('CuCr','AuAg')` = 0。

---

## 四、B3 — 列表查询改造

### 4.1 DTO 补字段
`MaterialRecipeDTO` 增加：
```java
public java.time.OffsetDateTime createdAt;
public java.time.OffsetDateTime updatedAt;
```
`toDTOLite` 映射时填充。

### 4.2 列表查询：全状态 + 关键字 + 排序
改 `MaterialRecipeService.listActive(...)`（方法名可保留或改 `listAll`，Resource 对应调整）：

- **返回全状态**（ACTIVE + INACTIVE）——现状 `status='ACTIVE'` 过滤要去掉，否则停用项从列表消失、排序"启用/禁用优先"无意义。
- **关键字搜索** `keyword`（可空）：命中以下任一即返回该材质
  - `code`（材质编号）ILIKE `%kw%`
  - `symbol`（材质名称）ILIKE `%kw%`
  - 该材质**任一元素**的 `element_code` 或 `element_name` ILIKE `%kw%`（子查询 `EXISTS (SELECT 1 FROM material_recipe_element e WHERE e.recipe_id=mr.id AND (e.element_code ILIKE :kw OR e.element_name ILIKE :kw))`）
- **排序**：`ORDER BY (status='ACTIVE') DESC, updated_at DESC, created_at DESC`
  （启用优先 → 修改时间倒序 → 创建时间倒序）
- 单条 SQL 完成，禁止 N+1。

### 4.3 Resource 签名
```java
@GET
public List<MaterialRecipeDTO> list(
    @QueryParam("keyword") String keyword,
    @QueryParam("withCount") @DefaultValue("false") boolean withCount) { … }
```
> `withCount`（绑定料号数）本期前端不再展示该列（见 fronttask F1），后端保留兼容，可不传。

**验收**：`GET /material-recipes?keyword=Ag` 返回含 Ag 元素/名称/编号的材质；停用项出现在启用项之后；时间字段非空。

---

## 五、B4 — 材质库导入服务（核心）

新增 `com.cpq.configure.service.MaterialRecipeImportService`（或并入 `MaterialRecipeService`），`@Transactional` 单事务。

### 5.1 解析（Apache POI，已有依赖）
1. 打开 workbook，**按名取 sheet**：`材质编号`、`材质对应元素`。缺任一 → 整单 400「模板缺少必需 sheet: xxx」。
2. 读 `材质编号` sheet → 构建权威映射 `Map<材质, 材质编号> codeByMaterial`。
3. 读 `材质对应元素` sheet → 逐行 `(材质, 元素名称, 含量, 元素编号)`；材质编号以 `codeByMaterial` 为准（不信该 sheet 内可能是 VLOOKUP 公式的列）。
4. **流式读**（`XSSFWorkbook` 或 streaming reader），一次读进内存后**批量处理**，禁止边读边逐行连库。

### 5.2 行级校验（不合规 → 跳过 + 记报告，不抛异常中断）
逐行校验，任一不过则该**元素行**跳过并记 `SkippedRow{sheet,row,reason,raw}`：
- 材质非空 且 在 `codeByMaterial` 中有编号（否则 reason=`材质无对应编号`）
- `元素名称` 非空、**非纯数字**（正则 `^\d+$` 命中 → reason=`元素名称为纯数字(疑料号误填)`）
- `含量` 可解析为数值、且 `0 < 含量 ≤ 1`（否则 reason=`含量非法`）

### 5.3 材质级校验（按材质编号分组，聚合存活元素行）
- 分组后计算 `Σ含量`；若 `|Σ - 1| > 0.02`（容差可配）→ **整个材质跳过**，记 `SkippedRow{reason=含量合计≠1(实际X.XX),code}`，其存活元素行一并作废。
- 同材质编号在文件内出现多"材质名称"不一致 → 取首个，记 warning。

### 5.4 落库（Upsert，批量）
对**通过材质级校验**的材质集合：

1. **元素主表同步**：收集所有出现的 `(元素符号, 元素编号)`；对每个符号
   `INSERT INTO element(element_code, element_name, element_no) VALUES(:sym, COALESCE(:dictName,:sym), :no)
    ON CONFLICT (element_code) DO UPDATE SET element_no=EXCLUDED.element_no, updated_at=NOW()`
   （`dictName` 来自 B1 字典的内存查表；不覆盖已有中文名——用 `DO UPDATE SET element_name = CASE WHEN element.element_name = element.element_code THEN EXCLUDED.element_name ELSE element.element_name END` 语义，即仅当原来是"符号占位"才回填中文）
2. **material_recipe upsert**（按 `code` = 材质编号）：
   `INSERT … (code, symbol, name, spec_label, recipe_type, status, sort_order)
    VALUES(:code, :material, NULL, NULL, 'locked', 'ACTIVE', :seq)
    ON CONFLICT (code) DO UPDATE SET symbol=EXCLUDED.symbol, recipe_type='locked', status='ACTIVE', updated_at=NOW()`
   拿回 `recipe_id`。
3. **元素明细全量重灌**（覆盖语义）：对本次涉及的 `recipe_id`
   `DELETE FROM material_recipe_element WHERE recipe_id IN (:ids)` → 再**批量** insert：
   `element_code=元素符号, element_name=(字典中文/符号), default_pct=含量×100, min_pct=NULL, max_pct=NULL, is_locked=true, sort_order=组内序号`
   > `default_pct` 精度 DECIMAL(8,4)，×100 后如 97.0000；同材质 Σdefault_pct 应=100（满足下游"和=100"约束）。
4. **文件外材质不动**：只处理文件中出现的 code，绝不 DELETE/TRUNCATE 其他材质。

### 5.5 性能纪律（硬性）
- **禁止嵌套 for 循环逐行连库**（需求第 5 节）。所有 insert/upsert 走**批量**（JDBC batch 或多值 `INSERT … VALUES (...),(...),…` 分片，如每 500 行一批）。
- DELETE 用 `IN (:ids)` 一次删，不逐 recipe 删。
- 元素主表 upsert 去重后批量。
- **目标：1000 行 < 3s**（本文件 655 行）。B6 用计时断言守住。

### 5.6 返回报告 DTO
```java
public class MaterialImportReportDTO {
    public int totalRows;            // 材质对应元素 数据行数
    public int materialsUpserted;    // 落库材质数(新增+覆盖)
    public int elementRowsInserted;  // 落库元素明细行数
    public int elementMasterUpserted;// 元素主表新增/更新数
    public int skippedRowCount;
    public List<SkippedRow> skipped; // {sheet,row,reason,raw}
    public long durationMs;
}
```

**验收**：见 api.md 示例；用真实 `材质库.xlsx` 跑，`materialsUpserted≈254`、`skipped` 含 9 个纯数字元素、`durationMs<3000`。

---

## 六、B5 — 接口

### 6.1 导入
`POST /api/cpq/material-recipes/import`，`@Consumes(MULTIPART_FORM_DATA)`，part 名 `file`（.xlsx）。`@RoleAllowed({"SYSTEM_ADMIN"})`（与现有写操作一致）。返回 `MaterialImportReportDTO`。

### 6.2 干净模板下载
`GET /api/cpq/material-recipes/import/template`，`@Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")`。POI 现生成**两 sheet 空模板**：
- `材质编号`：表头 `材质 | 材质编号`
- `材质对应元素`：表头 `材质 | 材质编号 | 元素名称 | 含量 | 元素编号`
- 可附 1 行示例 + 批注说明"含量填 0–1 小数、同材质相加=1"。
- **不要**直接吐这份 12-sheet 脏原始文件。

契约细节见 `api.md`。

---

## 七、B6 — 测试

放 `cpq-backend/src/test/java/com/cpq/configure/`（worktree 内 `cd cpq-backend && ./mvnw test` 亲跑，勿在主仓跑）：

1. **校验单测**：构造含①纯数字元素②含量>1③某材质Σ≠1④材质无编号 的小 workbook（POI 内存生成），断言各自被跳过且 reason 正确、合规材质正常入库。
2. **×100 单测**：含量 0.97/0.03 → `default_pct` 97.00/3.00，Σ=100。
3. **Upsert 语义单测**：先导入 A（含材质 00001），再导入 B（含 00001 改名 + 新增 00002 + **不含** 00001 之外的手工材质 ZZ999）→ 断言 00001 被覆盖、00002 新增、**手工材质 ZZ999 仍在**（文件外不动）。
4. **元素主表同步单测**：新符号入 `element`；已有中文名不被符号覆盖。
5. **性能测试**：生成 1000 行 workbook，`assert durationMs < 3000`。
6. **迁移自检**：B1/B2 迁移 `flyway_schema_history` success=t。

---

## 八、完成自检清单（提交前逐条打勾）

- [ ] B1/B2 迁移经 Quarkus dev 自动 migrate（**勿手工 psql -f**），`flyway_schema_history` success=t
- [ ] `touch` 一个 java 文件触发重启，`GET /api/cpq/material-recipes` 返 200/401（非 500）
- [ ] 真实 `材质库.xlsx` 导入：报告 `materialsUpserted≈254`、`durationMs<3000`、纯数字元素在 skipped
- [ ] `GET /material-recipes?keyword=银` 命中（中文元素名搜索通）
- [ ] 停用某材质后仍在列表、排在启用项之后
- [ ] 二次导入同文件：无重复、Σdefault_pct 稳定=100（不累加）、文件外材质不动
- [ ] 下游未受损：`ConfigureSearchResource`/选配材质 Tab 仍正常（name 走 COALESCE 回退不报错）
- [ ] 一行「已自检」声明（TS/编译/端点/迁移四项证据）
