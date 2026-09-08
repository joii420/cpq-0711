# AC-3 亲验证据 —— `extend_column` 只留痕，不参与任何写主表的动作（2026-09-08）

**执行者**：主线亲验　**实例**：8099 @ `42584a44`（`00:54:30` 末次热重载，主代码未变仍含 D-43）　**库**：`cpq_db_0724`

---

## 0 · 夹具：仍是 `取值测试模板1` 的 `材质元素` 页签，不用自造

该组件 13 个字段里有两个不落主表物理列，正好构成 AC-3 的前置：

| 字段 | 类型 | 为什么进 `extend_column` |
|---|---|---|
| `元素小计` | `FORMULA` | 无 `default_source`，是自定义公式列 —— 主表无对应列 |
| `元素单价` | `INPUT_NUMBER` | `sourceNodeKey = FUNC_ELEMENT_PRICE ≠ 锚点节点 ELEMENT_BOM`<br>⇒ `DsSheetBindingResolver:247` 的 `if (!srcNode.equals(anchor[0])) continue;` 把它挡在 `fieldToColumn` 外<br>⇒ `:257` 归入 `extend` |

🔑 **`元素单价` 是本条的关键杠杆**：`元素小计` 是 `FORMULA`，用户改不了；
而 ③ 要求「**只改**自定义列再保存再通过」，必须有一个**可编辑**的 extend 列才做得出实验。

---

## 1 · 断言 ①：值出现在 `_record.extend_column` ✅

```
I単  Ag/00006  extend={"元素小计": "0"}
     Cu/2111410069  extend={"元素单价": "171.368", "元素小计": "0"}
```

---

## 2 · 断言 ②：主表没有新增列，也没有任何一列被这些值写入 ✅

```
ds_quote_element_bom 的列（21 个，含 5 个系统列）:
  id, material_no, material_part_no, item_seq, element_code, content_pct, loss_rate,
  gross_usage, gross_usage_unit, net_usage, net_usage_unit, recovery_discount, recovery_qty,
  version_no, row_fingerprint, source, created_at, created_by, updated_at, updated_by, customer_no
⇒ 无「元素小计」「元素单价」列
```

全列扫（文本化比对，避开 varchar/numeric 类型冲突）：
```
任一业务列取值 = 171.368（extend 里的实值）的行数 = 0
```
逐行主表实值已列出可人工复核，六行的 `pct/loss/gross/net/rec_disc/rec_qty` 无一为 extend 值。

---

## 3 · 断言 ③：这些值不进 `row_fingerprint` ✅

### 实验设计（控制组 + 实验组 + 已有的阳性对照，三者缺一不可）

| 单 | 干预 | 预览判定 | 版本 |
|---|---|---|---|
| **L単（控制组）** | **完全不改** | `UNCHANGED` | `baseV=7 curV=7 tgtV=7` |
| **K単（实验组）** | **只改 `元素单价` → 999.9**，一个主表列都没碰 | `UNCHANGED` | `baseV=7 curV=7 tgtV=7` |
| A2単 / J単（阳性对照，见另两份证据） | 改**主表列**（`损耗率%` / `组成数量`） | `UPGRADED` | 各 +1 版 |

### 🔑 判据的命门：必须证明「改值真的进了 `_record`」

只有 K 的 `UNCHANGED` 说明不了问题 —— **改丢了也会 `UNCHANGED`**。实查：

```
K単  Ag/00006  extend={"元素单价": "999.9", "元素小计": "0"}   element_price=999.900000000000
L単  Ag/00006  extend={"元素小计": "0"}                        （无 元素单价）
```

⇒ `999.9` **确实落进了 `_record` 的两个位置**（`extend_column` 与专列 `element_price`），
而该组仍判 `UNCHANGED`、`version_no` 7→7 未动。
⇒ **extend 的值不参与 `row_fingerprint` 的计算**，断言 ③ 成立。

📌 反过来说：若没有「999.9 已入库」这一枪，L 与 K 都是 `UNCHANGED`、看起来一模一样，
这条判据就退化成「两个都没变 ⇒ 恒真」。**三组（控制/实验/阳性）加这一枪，缺任何一个都证不出。**

---

## 4 · 手法留痕

K単 的 `quote-card-edit` 同样走了改值回读守卫（`[] → 999.9`，`AFTER != 期望值` 即 `exit 1`）。
