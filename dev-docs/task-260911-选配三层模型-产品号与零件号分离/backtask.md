# task-260911 · 后端任务分解

> 🚫 **只标 AC 编号，不复制 AC 原文** —— 原文在 `需求文档.md §⑥`，**开工前必读那一节**。
> 🚨 遇 `CLAUDE.md §3.2` 红线（DROP/TRUNCATE/无 WHERE 的 DELETE/清库）**立即停下报主线**，子代理无批准权。
> 📌 本任务**数据层零不可逆操作**：`D-3` 已裁决存量不迁移 ⇒ 只产生新增行。若你发现自己需要 DELETE 或 UPDATE 存量，先停下。

## 🔑 开工前先看这条：不要从零造父子结构

`ConfigureProductService:2150` 现有代码：

```java
String productPartNo = "COMPOSITE".equals(effectiveType) ? parentHfPartNo : ...
```

⇒ **「父料号 + 子件」的机制已经存在**（`buildCompositeBomRows(parentPartNo, childPartNos, …)` 就是构造 `父 → 子件` 的 BOM 行）。
⇒ **三层模型 ≈ 让 SIMPLE 也走 COMPOSITE 那套父子结构**，而不是新写一套。
🚦 **A0 要裁决的第一件事就是这个**：是复用 COMPOSITE 路径，还是另起一条 SIMPLE 三层路径。**先给主线呈报对比，不要直接选。**

## 任务

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-1, AC-5 | **新建零件路径铸两个号**：`resolvePart`（`:328`，现在返回单个 `String`）要产出**产品料号 P** 与**零件料号 C**。P 走 `QuoteMaterialNoAllocator`（`:108`）新铸；C 按 **B-3** 的复用判定决定新铸还是复用。<br>⚠️ **返回值形状变了** ⇒ 所有调用点要同步，🚫 不要用「返回 P、把 C 塞进成员变量」这种隐式传递 |
| **B-2** | AC-1③④⑤ | **BOM 拆成两层**：现在 `:481-482` 是 `writeMaterialBomGroup(customerCode, hfPartNo, buildRecipeBomRows(hfPartNo, mats))`（一层：料号→材质）。改成：① `P → C` 一行（**优先复用 `buildCompositeBomRows`**）；② `C → 材质` N 行（`buildRecipeBomRows(C, mats)`）；③ 元素行 `buildElementBomRows(**C**, mats)` —— 🔑 **轴从 P 改成 C** |
| **B-3** | AC-4, AC-5 | **零件复用判定**（`D-2`）：品名/规格/尺寸/总重/材质（含占比）**完全相同**的零件 ⇒ **复用已有零件料号**，不重造。<br>🔑 **这等于把指纹口径从「产品层」下移到「零件层」** —— 现有 `SalesFingerprintCalculator` 算的是含客户维度的整体结构。**A0 要裁决**：是改指纹算法，还是在零件层另起一套判定。<br>🚫 **产品料号不参与复用**（AC-5：每次新铸） |
| **B-4** | AC-1②, AC-2② | **客户产品编号绑 P**：`insertSelProductNo`（`:1675`）→ `insertCustomerPart`（`:1689`）的 `material_no` 参数从「那个唯一的号」改成 **P**。🚫 不是 C |
| **B-5** | AC-1⑥, AC-2⑤ | **工序挂 C**（`D-1`）：`writeSelfProcessFeeGroup` 的轴从 P 改成 **C**。<br>📌 `task-260910` 的 `D-4` 定过「SIMPLE 时 `input_material_no` = 料号自身」—— 三层后**语义要重新表述**（轴=C、投入=C 仍成立，但「料号自身」指的是零件了）。**顺手更新那处注释**，🚫 不要留下与新模型矛盾的说明 |
| **B-6** | AC-2 | **已有零件路径只铸 P**：选中已有零件 C0 ⇒ 铸 P、写 `P → C0`，**C0 自己的 BOM 与元素行零新增**（AC-2④） |
| **B-7** | AC-1①, D-5 | `ds_quote_material` 写**两行**（P 与 C）。P 的 `material_type` 按 `D-5` 待确认项（推荐写「零件」，与现状一致不引入新取值） |
| **B-8** | AC-3 | **绑定已有销售料号路径逐字不动**（`task-260910` AC-18 已亲验通过）。⚠️ **回归验证要做**：B-1 改了 `resolvePart` 返回值，别顺手改坏这条早退路径 |
| **B-9** | AC-7 | **COMPOSITE 路径回归**：父/子结构与 `task-260910` AC-4 逐字一致（父轴 2 行 + 子轴各 1 行 + 合计 4 行）。若 A0 裁决「复用 COMPOSITE 路径」，本条是最关键的回归面 |
| **B-10** | AC-8 | **存量不动**（`D-3`）：🚫 不写迁移、不批量 UPDATE。已有的两层料号保持原样 |
| **B-11** | AC-1~AC-8 | **N+1 硬指标**（`backend.md §1`）：铸两个号、写两层 BOM，SQL 条数仍须与料号数/材质数**无关**。🚫 `writeGroup` 不许进 for 循环 |

## 🚦 A0 待裁决（开工前呈报主线，🚫 不要自己选）

1. **复用 COMPOSITE 路径 vs 另起 SIMPLE 三层路径**（见开头）
2. **零件复用判定放哪**：改 `SalesFingerprintCalculator` vs 零件层另起一套
3. **`ds_quote_self_process_fee` 的 `input_material_no` 语义**（`task-260910` D-4 的表述要不要改）
