# repair-0814 · 验收报告

- **执行时间**：2026-08-14（本地 PDT）
- **环境**：`10.177.152.12:5432/cpq_db_0724`（默认 profile 开发库）；后端 `localhost:8081`（复用已运行 dev server，未新起）；前端 `localhost:5174`（200）
- **执行人**：主线（技术总监）亲执行 + 亲验，未派子代理
- **改动性质**：**零代码改动** —— 无 `.java` / `.tsx` / `.ts` / Flyway 迁移改动；改的是配置数据（3 行 UPDATE）+ 交付物文件（bundle json / 配套 sql）+ 删 2 张测试单
- **对应**：`问题说明.md` §⑥ AC-1~AC-10，`BL-0169`

---

## 1. 执行清单

| # | 动作 | 结果 |
|---|---|---|
| 1 | 备份三个组件行键现值 | `scratchpad/rowkey-backup-before.txt` |
| 2 | 取 AC-6 基线（`expand-driver` × 3） | `expand-before-COMP-023{2,3,4}.json` |
| 3 | `UPDATE component SET row_key_fields …` × 3 | `UPDATE 1` × 3，单事务 COMMIT |
| 4 | AC 逐条验证（含还原实验） | 见 §2 |
| 5 | bundle 按当前库整体重导 + `__delivery` 合并 | `客户组件模板/核价组件/核价组件模板-简易.json`（5 组件，`unboundCount=0`） |
| 6 | 配套 SQL 文件行键注释同步 | `核价组件模板-简易-组件.sql` 三处 |
| 7 | 删 2 张测试 DRAFT 单（走 API，非裸 SQL） | `DELETE /api/cpq/quotations/{id}` → 200 × 2 |

**改动前后值**：

| 组件 | 改前 | 改后 |
|---|---|---|
| `COMP-0232` | `["销售料号","组成料号","material_no","parent_no"]` | `["销售料号","料号"]` |
| `COMP-0233` | `["销售料号","材质料号","元素代码","material_part_no","material_no","component_no"]` | `["销售料号","料号","元素代码"]` |
| `COMP-0234` | `["销售料号","工序编号","material_no"]` | `["销售料号","工序编号"]` |

---

## 2. AC 逐条结果

| AC | 判定 | 证据 |
|---|---|---|
| **AC-1 口径单一（阴性）** | ✅ | `SELECT code,row_key_fields …` → 三条均为纯中文字段名，`material_no`/`parent_no`/`material_part_no`/`component_no` 全部消失 |
| **AC-2 行键项真实存在** | ✅ | 全库口径扫描：这 3 个组件「非字段名」计数 = **0** |
| **AC-3 可比恢复（阳性）** | ✅ | 取后端 `GET /components/{id}/tab-defs` 真实返回值，按前端同款逻辑（`isSubset`/`comparable`/`tabComparable`）计算：**宿主 物料BOM → 源 物料与元素BOM = 可比**，反向亦可比 |
| **AC-4 反向期望仍生效** | ✅ | 同一矩阵：**加工费&组装费 ↔ 物料BOM / 物料与元素BOM 双向均仍判「不可比」**（`{销售料号,工序编号}` 与 `{销售料号,料号}` 互不包含）→ 明细仍置灰、只留总计。`comparable()`/`tabComparable()` 零改动 |
| **AC-5 唯一性不退化** | ✅ | 三条重复组 SQL 重跑：BOM(销售料号,料号)=0 / 元素BOM(销售料号,料号,元素代码)=0 / 加工费(销售料号,工序编号)=0 |
| **AC-6 无副作用** | ✅ | `expand-driver` 改前/改后各取一次，`diff` **逐字节一致 × 3**（COMP-0232 0 行、COMP-0233 4 行、COMP-0234 4 行） |
| **AC-7 其它组件零波及** | ✅ | 全库「含非字段名 key 的组件数」**3 → 0**，「有行键组件总数」仍 **135**（未误伤、未漏改） |
| **AC-8 bundle 同步** | ✅ | 重导后三处 `rowKeyFields` 与 AC-1 逐字一致；`unboundCount=0`；新增 `⚠️行键口径（repair-0814）` 段 |
| **AC-9 强制自检声明** | ✅ | 见 §4 |
| **AC-10 BACKLOG 登记** | ✅ | `BL-0169` → 已完成；`BL-0170` 待开发 P1 |

---

## 3. 还原实验（证明 AC-3/AC-4 的验证脚本有鉴别力）

AC-3/AC-4 用的是自写的判定脚本，**首次 PASS 不足以采信**（记忆 `cpq-agent-tests-stale-server-false-positive`）。因此做了还原实验：

```
1) 把 COMP-0232 临时改回故障值 ["销售料号","组成料号","material_no","parent_no"]
2) 重新 GET tab-defs → 脚本判定：宿主 物料BOM → 源 物料与元素BOM = 不可比 ⛔（故障态复现）
3) 改回修复值 ["销售料号","料号"] → 判定回到 可比 ✅
```

脚本随输入变红/变绿 ⇒ 有鉴别力，AC-3/AC-4 结论可信。

> 脚本口径逐字镜像前端：`isSubset(sub,sup)=sub.every(x=>new Set(sup).has(x))`（`formulaSerialize.ts:1401-1404`）、
> `comparable(a,b)=isSubset(a,b)||isSubset(b,a)`（`:1406-1408`）、
> `tabComparable(self,src)= src.length ? comparable(self,src) : false`（`TabFieldMatrix.tsx:12-15`）。

**完整可比矩阵（修复后，取自后端真实返回）**

| 宿主 → 源 | 物料BOM | 物料与元素BOM | 加工费&组装费 |
|---|---|---|---|
| **物料BOM** | — | ✅ 可比 | ⛔ 置灰 |
| **物料与元素BOM** | ✅ 可比 | — | ⛔ 置灰 |
| **加工费&组装费** | ⛔ 置灰 | ⛔ 置灰 | — |
| **核价小计-简易**（空行键宿主） | ✅ | ✅ | ✅ |
| **核价excel**（空行键宿主） | ✅ | ✅ | ✅ |

---

## 4. 强制自检声明

> **零代码改动** —— 未改任何 `.java` / `.tsx` / `.ts` / Flyway 迁移，故无 `tsc --noEmit`、无 Vite 200、无 Quarkus 重启、无 `flyway_schema_history` 校验项（AC-9 按「显式说明零代码改动」口径达成）。
> 后端存活：`GET /api/cpq/components` → **401**（应用在跑、鉴权正常）✅
> 前端存活：`GET http://localhost:5174/` → **200** ✅
> 数据改动：`UPDATE component … WHERE code IN (…)` → **UPDATE 1 × 3**，单事务 COMMIT ✅
> 取数无副作用：`expand-driver` 改前后 **diff 逐字节一致 × 3** ✅
> 全库回归：含非字段名 key 的组件 **3 → 0**，有行键组件总数恒 **135** ✅
> 单据清理：`DELETE /api/cpq/quotations/{id}` → **200 × 2**，复核该模板名下报价单 **0 张** ✅
> **N+1 自检**：本次零后端代码改动，无新增循环/查库点，不适用 ✅

---

## 5. 已知限定（随裁决保留，非缺陷）

1. **PUBLISHED 模板冻结快照未同步**（裁决 Q3）：`template_component_snapshot` 里 `模板「核价模板-简易」v1`（PUBLISHED `78e79801` / ARCHIVED `4923447a`）仍是修复前的混合值，**待下次发版自然带上**。
   组件管理的连表公式抽屉读 `ComponentTabDefService`（`component` 表实时值，不读快照），故 **AC-3/AC-4 不受此限定影响**。
2. **重建的新单仍会拿到旧快照行键**（Q3+Q4 组合后果，`问题说明.md` §4.5-3）：本轮只保证组件定义层干净。若需要新单立刻干净，须先给模板发新版本。
3. **UI 复选框仍是污染源**（裁决 Q2）：这三个组件的行键复选框现在显示为**未勾选**，谁再去勾一下就会重新变成混合 → `BL-0170`。bundle 的 `__delivery` 已写入显式警告。
4. **未做 Playwright E2E**：本次零前端代码改动，不触发 `CLAUDE.md`「协议级改动必须跑 E2E」的任一条件（未动 `useDriverExpansions.ts` / `QuotationStep2.tsx` / `ComponentDriverService.java` / 模板 snapshot 迁移等）。AC-3/AC-4 以「后端真实返回值 + 前端同款逻辑 + 还原实验」替代。

---

## 6. 执行期发现（已回写 `问题说明.md`）

1. **初版 bundle 并没有配错** —— 它内部自洽（字段名与行键同为「组成料号」/「材质料号」），是库里后来被改名才分叉。原 §⑤「改动 2」按「bundle 配错了」写，已按实测修正为「整体重新导出」。
2. **同一次改名，其它角色字段全跟上了，唯独行键没跟上** —— `part_no_field`/`part_name_field`/`sort_field`/`element_code_field`/`element_price_field` 实测零断链，因为它们在 UI 上是**下拉选字段名**（改名即联动）；行键是**按列名的复选框**，中文项界面上不显示 → 不跟名。这条独立佐证了根因 R2。
3. **组件变更无审计**：`component` 表无 `updated_by`；`operation_log` 只覆盖 `TEMPLATE`(26)/`CUSTOMER`(3)，`COMPONENT` **0 条**。因此只能定位到配置会话时间窗，定位不到操作人。是否补审计**待用户裁决**，未登 BACKLOG。

---

## 7. 复发与二次修复（2026-08-14 同日）

**复发**：用户当天新建目录「核价模板2」导入 `COMP-0241~0244` 后，行键**再次**出现英文项，并反馈「新导入的组件还是有 material_no / parent_no / component_no」。

| 组件 | 复发值 | 二次修复后 |
|---|---|---|
| `COMP-0241` 物料BOM | 销售料号, 料号, **parent_no, material_no** | `["销售料号","料号"]` |
| `COMP-0242` 物料与元素BOM | 销售料号, 料号, 元素代码, **material_no, component_no, material_part_no** | `["销售料号","料号","元素代码"]` |
| `COMP-0243` 加工费&组装费 | 销售料号, 工序编号, **material_no, operation_no** | `["销售料号","工序编号"]` |
| `COMP-0244` 成品其他比例费用 | 销售料号, 项次, 要素, **material_no, cost_type** | `["销售料号","项次","要素"]` |

**决定性实验（排除导入链路）**：用同一份 bundle（`rowKeyFields` 纯中文）导入到临时目录 `ZZ-临时验证-repair0814` →
落库 `COMP-0245~0248` 行键**纯中文**且 `created_at = updated_at`（从未被改过）。实验数据已全部清理（4 组件 + 目录 DELETE 200，残留 0/0）。
配合 `created_at` 全为 `08:33:53`（同一秒批量导入）而 `updated_at` 为 `08:34:06/08:34:47/08:34:58/08:35:08`（各不相同）
⇒ **污染来自导入后的逐个人工勾选，不是导入链路**。根因 R2 确证。

**二次修复结果**：

- 目标值全部经「是否为真实字段名」校验通过（4/4 ✅）
- 全库「含非字段名 key 的组件数」= **0**，有行键组件总数 **139**（较首次的 135 增加 4 个 = 新导入的这批）
- 可比矩阵（取后端 `tab-defs` 真实返回 + 前端同款逻辑）：**物料BOM ↔ 物料与元素BOM 可比 ✅**；
  加工费&组装费、成品其他比例费用 与其余各页签**均仍判不可比 ⛔**（反向期望守住）
- 备份：`scratchpad/rowkey-backup-dir2-before.txt`

**结论对 `BL-0170` 的意义**：污染在**知情状态下 24 小时内复发第二次**，且这次是「全勾」而非「勾一半」——
说明只要 UI 复选框还只读写列名、中文行键还显示为未勾选，**每导入一批组件就会被污染一批**，靠人记住「别去勾」不可行。
`BL-0170` 已由「二期修」升级为**优先修**（详见 BACKLOG 该条状态）。
