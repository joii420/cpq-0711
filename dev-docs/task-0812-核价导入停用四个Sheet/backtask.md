# backtask · 核价导入停用四个 Sheet

> 上游依据：本目录 `需求文档.md`（FR / AC 编号以该文件为准）。契约层面无变化，见 `api.md`。

---

## 1. 数据模型变更

**无。** 本任务不新增 / 不修改 / 不删除任何表、列、视图、索引、约束。

- **不写 Flyway 迁移**（需求文档 D-8）。
- 存量数据不动：`unit_price` 的 `ELEMENT`(3行) / `MATERIAL_PRICE`(2行)、`material_version_mgmt`(4行)、`material_customer_map`(20行) 一律保留原样。
- 若开发中发现"必须加迁移才能实现"，说明理解偏了 —— 停手回报主线，不要自行加 V 号（共享 Flyway 历史是移动靶，见 `cpq-shared-flyway-history-churn`）。

---

## 2. 服务与端点清单

| 端点 | 是否改动 | 说明 |
|---|---|---|
| 核价基础数据导入（`PricingBasicDataMaintenanceResource` 下的 import 端点） | **行为收敛，契约不变** | 响应结构不变，`sheetResults` 数组元素由 24 变 20 |
| 核价空模板下载端点 | **行为收敛，契约不变** | 返回 xlsx 由 24 Sheet 变 20 Sheet |

无新增端点、无删除端点、无参数/响应字段增减。**因此 `main-api.md` 无需回写**（在 `test-report.md` 中写明"本次无契约变更"即可，规则 §2.4）。

---

## 3. 业务规则与改动点

### T-B1 `PricingHandlerCatalog.java` —— 摘除 4 个 handler

文件：`cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/PricingHandlerCatalog.java`

1. `all()` 返回列表移除 `p01, p02, p04, p05`，**其余 20 个的相对顺序一字不动**，结果必须逐字等于：

```java
return List.of(p03, p06, p07, p08, p09, p10, p11, p12, p13, p14, p15, p16,
               p17, p18, p19, p20, p21, p22, p23, p24);
```

2. `@Inject P01... p01;` / `p02` / `p04` / `p05` 四个字段：**保留字段声明**，在每个字段末尾加行内注释

```java
@Inject P01ElementPricingPriceHandler p01;   // task-0812 停用：元素核价价格表（不进 all()，恢复=加回上面 List）
```

> 保留 `@Inject` 的理由：`D-2` 要求"恢复成本 = 往列表里加回名字"。CDI 注入未使用的 `@ApplicationScoped` bean 无副作用（这 4 个 handler 构造期无任何 IO / 无 `@PostConstruct`），代价仅一条 IDE unused 提示。

3. 类级 Javadoc 顶部的 "24 个 Sheet Handler 的登记表" 与正文里"全量 24 个"的表述同步更新为 20，并补一句说明：**4 个 Sheet 已于 task-0812 停用，handler 类保留但不登记**。

### T-B2 `PricingImportService.java` —— 摘除 4 个 handler 的调用

文件：`cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/PricingImportService.java`

1. `orderedHandlers()` 移除 `p24` 之后的 `p05`、`p03` 之后的 `p04`、`p07` 之后的 `p01, p02`，**剩余项顺序一字不动**，结果必须逐字等于：

```java
private List<SheetHandler> orderedHandlers() {
    return List.of(p24, p03, p06, p07, p08, p09, p10, p11, p12,
                   p13, p14, p15, p18, p21, p22, p23);
}
```

⚠️ **这是本任务唯一容易出错的地方**：该列表是**多表写入依赖序**（料号 → 关系 → 汇率 → BOM主 → BOM子 → 单价 → 其余），不是编号序。只做删除，**严禁重排、严禁按 P 编号"顺手排整齐"**。

2. 同 T-B1，`@Inject p01/p02/p04/p05` 四个字段保留 + 加 `// task-0812 停用：<Sheet名>` 注释。

3. 循环外的 P16/P17、P19/P20 合并接线（`incomingOtherMerge` / `finishedOtherMerge` 及其 `wb.getSheet(...)` 解析块）**完全不动**。

4. 方法级注释补一句：处理 Sheet 数 = 16（循环）+ 4（两个合并 bean）= **20**。

### T-B3 4 个 Handler 类 —— 零改动（守卫项）

`P01ElementPricingPriceHandler` / `P02MaterialPricingPriceHandler` / `P04PricingVersionHandler` / `P05CustomerMapHandler`：

- **不删文件、不删方法、不改 `sheetName()`、不改 `handle()` 内部一行代码、不加 `@Deprecated`。**
- 交付时 `git diff --stat` 必须**不含**这 4 个文件（AC-12）。

### T-B4 测试收敛

**T-B4a `PricingTemplateServiceTest.java`**（`cpq-backend/src/test/java/com/cpq/basicdata/v6/pricing/`）

该测试是双向守卫，以下位置必须同批改：

| 行（现状） | 现内容 | 改法 |
|---|---|---|
| 27 | Javadoc "24 Sheet 空模板生成" | → 20 Sheet，补一句 task-0812 |
| 68 | 注释 "sheet 数 == handler 数（24）" | → 20 |
| 77 | `assertEquals(24, handlers.size(), "核价 handler 应为 24 个（P01~P24）")` | → `assertEquals(20, ...)`，消息改为"应为 20 个（P01~P24 中 P01/P02/P04/P05 已于 task-0812 停用）" |
| 104-105 | 「未登记进 catalog 的 handler bean」扫描 + `assertEquals(24, beans.size(), ...)` | **这里要当心**：该断言原意是"有 handler bean 却漏登记 catalog"的守卫。停用后 bean 数仍是 24、catalog 是 20，**必须显式排除这 4 个已停用 handler**，不能简单把 24 改成 20（bean 依然存在）。改法：在扫描结果里过滤掉 4 个已停用类名（用一个 `DISABLED_HANDLERS` 常量集合 + 注释说明来源 task-0812），保持"其余 handler 漏登记必红"的守卫效力不降级 |
| 138/139/141/142 | 4 条 `required.put(...)` 必填键 | 删除这 4 行 |
| 166 | "本测试的必填键清单漏了 sheet"断言 | 逻辑不变（遍历的是 catalog，自然只剩 20 个） |

> 改完自检：故意把某个**在用**的 handler（如 p13）从 catalog 里注释掉 → 该测试必须变红。红了才证明守卫仍有效；若仍绿说明守卫被改废了，必须返工。

**T-B4b `PricingVersioningImportE2ETest.java`**

已核查：该文件**未硬编码**这 4 个 Sheet 名，也无 24 的数量断言（仅在第 93-94 行遍历打印 `sheetResults`）。但它构造/读取的测试 Excel 可能含这些页并断言其写入效果 —— **先原样跑一次**，红了再按实际失败点收敛，**不要预防性地改**。

**T-B4c 其余 pricing 测试**

`P01P02PricingPriceVersioningTest` 等直接 `@Inject` handler 并调 `handler.handle(...)` 的测试**保持通过且不修改**——它们正是"落库逻辑已保留"的证据（AC-10 / FR-7）。若这些测试因本次改动变红，说明误删了 handler 逻辑，必须回退。

---

## 4. 事务边界

不变。每个 handler 自带 `@Transactional(REQUIRES_NEW)`（每 Sheet 独立事务，V6 导入非原子），本次只是不再调用其中 4 个，事务模型无任何变化。

`createImportRecord` / `finalizeImportRecord` 的 `REQUIRES_NEW` 边界不动。

---

## 5. 幂等与并发

不变。停用后同一文件连导两次仍不应升版（AC-9），该语义由 `VersionedV6Writer` 的内容比对保证，与本次改动无关 —— 但**必须实测**，因为它是"没误伤其余 20 个 Sheet"的最灵敏探针。

---

## 6. 性能要求

无硬性指标。停用 4 个 Sheet 后导入耗时应**不增加**（预期略降）。

**N+1 自检**：本次是纯删除调用，不新增任何 `for` / `forEach` / `stream()` 循环体，不新增任何 repository 调用、`SqlViewExecutor.execute`、懒加载 getter。交付声明格式：

> `N+1 自检：本次改动 0 处新增循环，仅从两个静态 List 中删除元素，无查库变化 ✅`

---

## 7. 自检项（交付前必跑，写进汇报）

1. `cd cpq-backend && ./mvnw -q compile` → 编译 0 错误
2. **A/B 基线**：先在**停用前**的代码上，用同一份含 24 Sheet 的 Excel、同一个库跑一次导入，记录逐 Sheet 的 `successRows/totalRows/错误数`（AC-8 的对照基线）；再在停用后跑一次比对
3. `./mvnw test -Dtest='P*Test,Pricing*Test'` → 与停用前基线相比**不新增** failure/error（注意 `BL-0151` 存量红，不得以"全绿"为口径）
4. 后端存活自检：`touch` 一个 java 文件触发 Quarkus 重启 → `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components` → 期望 401
5. 模板端点实拉一次，用 POI 或 `unzip -l` 确认 20 个 sheet（AC-1/AC-2）
6. SQL 证据（AC-4~AC-7），导入前后各跑一遍：
```sql
SELECT price_type, count(*) FROM unit_price
 WHERE system_type='PRICING' AND price_type IN ('ELEMENT','MATERIAL_PRICE') GROUP BY price_type;
SELECT count(*), max(updated_at) FROM material_version_mgmt;
SELECT count(*), max(updated_at) FROM material_customer_map;
SELECT material_no, name, spec, dimension, old_material_no, updated_at
  FROM material_master WHERE material_no IN (<导入文件 Sheet5 里的销售料号>);
```
7. `git diff --stat` 确认不含 4 个 Handler 类文件（AC-12）
8. **不需要** Flyway 自检（无迁移）；**不需要** 前端 E2E（后端改动不触碰 `ComponentDriverService` / `FormulaCalculationService` / `TemplateService#refreshSnapshotsByComponent` 等 E2E 强制触发点）

---

## 8. Task 列表（逐项可勾选）

- [ ] **T-B1** `PricingHandlerCatalog.all()` 摘除 4 个 + 保留 `@Inject` 加注释 + 类 Javadoc 24→20
- [ ] **T-B2** `PricingImportService.orderedHandlers()` 摘除 4 个（严格按 §3 给定序列）+ `@Inject` 注释 + 方法注释补"16+4=20"
- [ ] **T-B3** 确认 4 个 Handler 类零改动（`git diff --stat` 验证）
- [ ] **T-B4a** `PricingTemplateServiceTest` 收敛（含 104-105 行守卫的**降级防护**，改完做"故意漏登记必红"验证）
- [ ] **T-B4b** `PricingVersioningImportE2ETest` 原样跑 → 按实际失败点收敛
- [ ] **T-B4c** 确认 `P01P02PricingPriceVersioningTest` 等 handler 级测试仍绿且未被修改
- [ ] **T-B5** 跑完 §7 全部 8 项自检，产出 A/B 对照与 SQL 证据
