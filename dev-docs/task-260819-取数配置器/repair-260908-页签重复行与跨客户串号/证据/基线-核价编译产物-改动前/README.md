# AC-4② 归档基线 · 核价两方言编译产物（改动前）

> 采集时间：2026-09-08 20:33 ｜ 采集方式：**一次性 worktree `ac4-baseline-throwaway`**
> —— 检出 master（迁移保持 V433 以便应用能起来），**只把两个生产文件退回基线提交 `6718f2f0`**：
> `SemanticCompiler.java` / `SqlViewExecutor.java`，然后跑 S-1 的 `-Dcpq.s1.capture=true`。
> 🚫 **没有在工作中的 worktree 上做「临时还原实现」** —— 那会与在跑的代理抢文件。

## 为什么必须这样采

`AC-4②` 要「改动后产物 vs 改动前基线」逐字节比。而：
- 改动前的**代码**：分支上已经提交了 `B-1/B-1b/B-1c`，工作树取不到
- 改动前的**库内 `sql_template`**：`B-6` 于 20:19 已重写 29 个视图，库里也取不到了
  （实测报错原文：「`builder_32ab8212df6c` 的库内 `sql_template` 已含 `:customerCode` ⇒ B-6 已经跑过，
  它不再是『改动前』基线。🚫 拿改动后的基线比改动后的产物，必然全绿 —— 那是假绿，不是通过。」）

⇒ **两个来源都关闭了，只剩「用改动前的代码重新编译一次」这一条路。**

## 自证（🚫 不许跳过，否则拿到的可能是改动后产物冒充基线）

| 检查 | 结果 |
|---|---|
| 基线树 `SemanticCompiler` 里 `CUSTOMER_SCOPE_COLUMN` 出现次数 | **0**（工作树是 12）⇒ 退回确实生效 |
| 32 个产物里含 `customer_no` 的文件数 | **0** |
| 同一视图 `COST_BASIC__主件__-` 基线 vs 实时库 | 基线 `… = ANY(:total_material_no))`<br>实时 `… = ANY(:total_material_no) AND dqm.customer_no = :customerCode)` |

⇒ 判据**既不恒真也不恒假**：基线全 0、对照有值。

## 内容

32 个 `.sql` = `COST_BASIC` 12 个页签 + `COST_DETAIL` 20 个页签的编译产物，
按 `<方言>__<页签类型>__<变体>` 命名。另有 `MANIFEST`（采集时间 + 语义图指纹）。

🔑 **不可重建，请勿删**。
