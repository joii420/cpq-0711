# task-260902 · 测试数据基线（**实查，唯一引用源**）

> 🚨 **本文件是所有 AC / 原型 / 测试用例的 fixture 唯一来源。**
> 立项 A 轮曾因**编造材质名**被评审打回（`评审报告-A轮.md` P1-12），故建此文件。
> **取数时刻：2026-09-02**，命令与原始输出见下。引用前请重跑核对 —— dev 库是共享的，数据会漂移。

---

## 0. 取数命令（可复跑）

```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -tAc "<下方各节的 SQL>"
```

---

## 1. 材质库（`material_recipe`）

| 项 | 实测值 |
|---|---|
| 总数 / ACTIVE | **259 / 258**（2026-09-02 清理 4 条 demo 污染后）|
| 每条材质的 ACTIVE 配置数 | **恰好 1 组**（唯一例外见 §1.2） |
| **0 组配置的 ACTIVE 材质** | ❌ **一条都没有** |
| `allow_custom_content=true` | **0 条** —— 原 4 条为测试污染，已于 2026-09-02 清理 ⇒ **需要该开关的用例必须事务内自建材质** |
| `symbol` 含 `/` 的 | **74 条**（如 `AgNi10/Cu/1008`、`AgZnO12/Cu`）—— 见 P1-7 指纹分隔符风险 |

### 1.1 主力 fixture：双材质场景

```
00006 | AgNi10       | cfg=00006-01 | 元素: Ag=90 / Ni=10
00123 | AgZnO12/Cu   | cfg=00123-01 | 元素: Ag=24.4 / Cu=72.2727 / Zn=3.3273
```

🚫 **`00123` 不叫「紫铜」** —— A 轮文档里的「紫铜」是编造值，全库不存在含「紫铜」的材质（只有 `DCO3镀铜`/`铁镀铜`/`电极丝（铜线）`）。
⚠️ **`00123` 的 symbol 自带 `/`**，正好是 P1-7 指纹分隔符问题的现成用例。

### 1.2 唯一拥有 2 组配置的材质

```
00262 | SnO2 | 00262-01 | 元素: 10004=100      ← element_code 填的是「10004」(Sn 的元素编号)
00262 | SnO2 | 00262-02 | 元素: Sn=100
```

🚨 **这两组含量并不相同**（一组元素码写成 `10004`，另一组写成 `Sn`）——
⇒ **AC-10 需要的「同材质下含量逐字相同的两条配置」在现网不存在**，必须事务内构造。
📌 `00262-01` 的 `10004` 是已知脏数据（`task-260901` 记录：Sn 的元素编号被填进符号列），**不要当成正常样例**。

### 1.3 支持自定义含量的材质 —— **现网 0 条**

```
SELECT count(*) FROM material_recipe WHERE allow_custom_content=true;  →  0
```

原有 `AgCu85 / AgCu90 / AgNi90 / AgNi95` 四条是 `DemoMaterialRecipeFixture` 提交式夹具于 2026-09-02 11:17 种入的污染数据，**已按用户批准清理**（连同 4 config + 8 element + 8 composition 共 24 行，业务引用实查为 0）。

⇒ **AC-21 / AC-22 必须事务内自建 `allow_custom_content=true` 的材质 + 回滚**，🚫 库里没有可直接引用的样本。

---

## 2. 工序（`process_master`）

```
Z100 | 焊接 | 分类=组装 | created=2026-07-28 04:03:47
Z101 | 铆接 | 分类=组装 | created=2026-07-28 04:03:47
```
📌 `TP10`/`TP20`（`created=2026-09-02 11:54`，测试夹具写入）**已按用户批准清理**，业务引用实查为 0（`unit_price` / `material_bom_item` / `quotation_line_process` 三处均 0）。
⚠️ **但没有机制阻止下次 `./mvnw test` 再写入** —— 这正是 `test.md §0` 收尾污染核对存在的理由。

- ✅ **工序是业务在「主数据维护 → 工序」页自行维护的开放主数据**（用户确认，截图见 `素材-工序主数据维护页-20260902.png`），不随迁移交付。
- 🚫 `MRO-*` 那 26 条是 `V4` 带的通用示例（CNC加工/包装入库…），**本库从未灌入**，与触点业务不符，**不搬入**。
- ⚠️ `process_category` 库里是**中文「组装」**，而 `CompositeProcessServiceB6CandidatesTest` 断言英文 `'ASSEMBLY'`。
- 🚨 **本基线不稳定**：`TP10/TP20` 证明 `./mvnw test` 会继续往这张表塞行。

**可用 fixture**：`Z100 焊接` / `Z101 铆接`（两条足以验证 AC-19 顺序与 AC-20 重复）。

---

## 3. 料号（`material_master`）

| 项 | 实测值（2026-09-02） |
|---|---|
| `material_type` 分布 | **零件 1848 / (NULL) 40 / 外购件 1** |
| ⚠️ A 轮文档写的「成品 1」 | 取数当时确有，**现已不存在** —— 该列随共享库漂移，**不要写死计数进 AC** |
| `material_recipe_id` / `config_fingerprint` 非空 | **0 / 0**（custom 路径从未跑通的证据，此结论仍成立） |

### 3.1 外购件（`material_type='外购件'`）

```
TEST-Q13-CODE | 组成件1 | 规格=(空) | 单重=(空)
```
🚫 **不叫 `WG-0001 / 绝缘垫片`**（A 轮 api.md 与原型 5 的编造值）。
⚠️ 唯一一条且字段大面积为空，**列宽/空值处理必须按它设计**（原型不能假设规格、单重有值）。

### 3.2 已有零件候选（AC-11 用）

```
3110520422   | AgNi10/Cu触点     ← 注意品名也含 /
3120011203   | 触头支架总成
S-3120014539 | 主料1
```

---

## 4. BOM（`material_bom_item`）

| 口径 | 分布 |
|---|---|
| **全表** | RECIPE 11095 / ASSEMBLY 49 / (NULL) 1 |
| **`is_current=true`** | ASSEMBLY 37 / RECIPE 21 / (NULL) 1 |
| **`OUTSOURCED`** | **0**（两种口径下都是 0）✅ 该结论稳定 |
| `material_ratio` 非空 | 3 行（总 11131） |

⚠️ **A 轮文档写「ASSEMBLY 29 / RECIPE 15」未标口径** —— 那是当时的 `is_current` 口径值，现已漂移到 37/21。**引用分布数必须带口径与日期**。

---

## 5. 关键唯一索引（影响 AC 可行性）

```sql
uq_mcm_quote_no          UNIQUE (material_no) WHERE system_type='QUOTE'
uq_mcm_quote_cust_prod   UNIQUE (system_type, customer_no, customer_product_no) WHERE system_type='QUOTE' AND customer_product_no IS NOT NULL
uq_mcm_composite         UNIQUE (system_type, material_no, customer_no, customer_product_no) NULLS NOT DISTINCT
uq_material_master_fingerprint  UNIQUE (config_fingerprint) WHERE config_fingerprint IS NOT NULL
```

🚨 **`uq_mcm_quote_no` 是 P0-2 的根据**：QUOTE 域每个销售料号只允许一行 mcm ⇒「编号唯一」与「指纹复用」正面冲突。
🚨 **`uq_material_master_fingerprint` 是 P0-3 的根据**：`config_fingerprint` **有意恒 NULL**（`ConfigureProductService:386` R1 注释），🚫 **不得断言它非空**。

---

## 6. 引用纪律

1. **任何写进 AC / 原型 / 用例的数据，必须在本文件里有出处**；本文件没有的，先查库再写，🚫 不许凭印象编。
2. **计数类数据（多少条材质、多少条工序）不写死进断言** —— 共享库会漂移。改用「≥N」或「取数当日为准」。
3. **污染数据（§1.3 的 4 条材质、§2 的 TP10/TP20）不得作为稳定 fixture**，需要时事务内自建 + 回滚。
4. 引用本文件的数据时**带上取数日期**，便于事后判断是否需要重取。

---

## 7. 🔍 立项期的三次同型判断失误（留痕，防再犯）

三次的形状完全一样：**据一个不完整的观察下了全称结论，没多查一步**。

| # | 我的错误结论 | 实际 | 多查哪一步就能避免 |
|---|---|---|---|
| 1 | 「`MRO-*` 工序号是我编的」 | 是 `V4/V185/V186` 真实交付过的种子，只是本库基线晚于它们、从未灌入 | `grep -ral "MRO-" db/migration/` 一次 |
| 2 | 「测试代理几乎没产出，只建了空目录」 | 目录里有 3 个文件近 70KB —— **未跟踪目录在 `git status` 里折叠成一行** | `ls` 那个目录一次 |
| 3 | **「mcm 不得新增任何行」写进 AC** | 铸号必然写占号行（`customer_product_no=NULL`），禁不掉也不该禁 | `grep -rn "INSERT INTO material_customer_map"` 一次 |

**第 3 次代价最大**：它成了两条 AC 的断言，让后端**白查白改了一轮**，我自己也绕了一圈才发现。

🔑 **共同教训**：写「不得/必须/全部/零」这类**全称断言**前，先把**反例的来源穷举一遍**。
本例中我只想到「B-8 会写 mcm」，没想到「发号器也会写 mcm」——**同一张表可以有多个写入方，各自语义不同**：

| 写入方 | 语义 | `customer_product_no` |
|---|---|---|
| `QuoteMaterialNoAllocator.mintAndRegister` | **占号**（防料号重复分配，串号防线） | **NULL** |
| 导入 `Q02CustomerMapHandler` / `upsertQuote` | 客户料号映射 | 非空 |
| ~~选配 B-8~~ | ~~客户产品编号~~ → **方案甲改为写 `sel_product_no`** | — |

⇒ 断言要落在**能区分这些写入方的判据**上（此处是 `customer_product_no` 是否非空），而不是笼统的表级行数。
