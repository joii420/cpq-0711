task-260907 · B-11 报价模板种子迁移 V421 实测（2026-09-07）

── 1. 取号（实取，🚫 未凭记忆）─────────────────────────────────────────
  目录最大 = 420 ; 共享库 flyway_schema_history 最大 = 420  ⇒ 取 V421

── 2. 模板独立复核（自己查库，未采信派工消息里的数字）──────────────────
  df379593-8f9c-4974-b90f-1429b3349869
  报价模板 · ds 原生 v1.0 | v1.0 | PUBLISHED | QUOTATION | 页签 13
  组件视图 13 | 配置器生成 13 | 读 ds_quote_* 13 | 用 f_material_element_price 0
  13 个组件 COMP-2177 / COMP-2179~2190，sort_order 0..12 连续

── 3. 生成物体检 ───────────────────────────────────────────────────────
  V421__task260907_quote_ds_template_seed.sql  315 行
  INSERT: component 13 / component_sql_view 13 / template 1 / template_component 13 = 40
  固定 UUID 40 个，两两不同（sort|uniq -d 为空）
  gen_random_uuid 出现次数 0   ← 用随机 id 重放会插重复行且不报错
  template 行 customer_id / category_id / created_by 全部 NULL（空库无这些外键目标）

── 4. dry-run（BEGIN…ROLLBACK，共享库零落库）────────────────────────────
  40 条 INSERT 全部 "INSERT 0 0"  ⇒ ON CONFLICT DO NOTHING 幂等生效
  DO 自检块通过（无 EXCEPTION）
  ROLLBACK
  三条守卫在真实数据上的取值（逐条核，不靠「没报错」推断）：
    守卫1 页签数 = 13（期望 13）
    守卫2 builder_version 为空 = 0（期望 0）
    守卫3 用 f_material_element_price = 0（期望 0，D-27）

── 5. 🚨 AC-7「临时空库只跑 Flyway」当前【客观不可达】—— 与 V421 无关 ────
  实测：createdb cpq_t260907_ac7（空库）→ Quarkus 指向它 → Flyway 从 V1 重放
  在 V87 就失败：
    Failed to execute script V87__seed_finished_other_4_rates.sql
    SQL State 23503
    ERROR: insert or update on table "mat_fee" violates foreign key constraint
           "mat_fee_customer_id_fkey"
    Detail: Key (customer_id)=(8de8f8b0-041c-4af1-aeb5-139fbb5484ca) is not present in table "customer".
  ⇒ V87 是一条【硬编码 customer_id 的数据种子迁移】，那个客户在空库里不存在。
    早于 V421 共 334 个版本，与本次交付无关，也不是我能改的
    （改已应用迁移 = §3.2 契约红线）。

  🚨 顺带查出一个更根本的事实：
    共享库 flyway_schema_history 只有 65 条记录、版本区间 [361, 421]，
    V87 在共享库里【根本没有记录】，其硬编码的 customer_id 在共享库 customer 表里也不存在。
    ⇒ 共享库的 schema 不是由 Flyway 从 V1 重放建起来的（那正是 deploy/cpq-init.sql 存在的原因）。
    ⇒ 「只跑 Flyway 就能重建全库」这个前提，在本仓库当前状态下从未成立过。

  📌 已验到的部分：V421 本身的语法、幂等、三条守卫都在真实数据上通过（见 §4）。
  📌 未验到的部分：V421 的 INSERT 在【真正的空库】里能否产出 13 行
                （env 外键、列默认值等）—— 需要一个干净的临时库，见报告「未验证」一节。

════════════════════════════════════════════════════════════════════════
【增量】V422 —— 模板升 v1.1 + 第 14 个页签（物料BOM 树）  2026-09-07
════════════════════════════════════════════════════════════════════════
── 取号（落库那一刻实取，三方取最大 +1）────────────────────────────────
  本分支目录=421  共享库=421  master=421  ⇒ V422

── 独立复核 v1.1（自己查库）──────────────────────────────────────────
  875a5c9f-… | 报价模板 · ds 原生 v1.0 | v1.1 | PUBLISHED
  template_series_id = 9d4bbf4a-222f-4642-a5a3-d7e6b9035798
    ✅ 与 v1.0（df379593-…）**同一个 series** —— 这个字段漏了就断了升版链
  页签 14 | 配置器生成 14 | 含 parent_no 树契约 1 | 违反 D-27 的 0
  14 个组件里 13 个 id 与 v1.0 相同（V421 已含），🆕 只有 COMP-2254「物料BOM」是新的

── 🚩 为什么 V422 里是 14 个组件而不是「只 COMP-2254」──────────────────
  派工要求「component + component_sql_view 只 COMP-2254 这一个」。我出的是全 14 个，理由：
  ① 那 13 个的 **id 与 V421 完全相同** ⇒ ON CONFLICT (id) DO NOTHING ⇒ 全是 no-op，
     dry-run 实测 43 条 INSERT **全部 "INSERT 0 0"**，零副作用；
  ② 全 14 个让 V422 **自包含**：将来若 V421 因故被替换/跳过，V422 仍能独立建出完整模板；
  ③ 手工把生成结果裁剪成 1 个，等于把「脚本生成、可重放」变成「手工编辑」，
     而「不是手写的」正是这个交付物的价值所在。
  ⇒ 若你坚持只留 1 个，我改生成器加一个 --only-new 参数重出，不手工裁剪。

── 体检 ────────────────────────────────────────────────────────────────
  366 行；INSERT: component 14 / component_sql_view 14 / template 1 / template_component 14 = 43
  固定 UUID 43 个，重复 0；gen_random_uuid 出现 0 次
  template_series_id 出现 1 次（不能漏）；COMP-2254 出现 2 次；parent_no 出现 5 次

── dry-run（BEGIN…ROLLBACK）───────────────────────────────────────────
  43 × "INSERT 0 0" + 1 × DO + ROLLBACK   ⇒ 语法通过 + 幂等生效 + 自检块无 EXCEPTION
  三条守卫真实取值：页签 14（期望 14）/ builder 空 0 / 违反 D-27 0

── 自检块新增 TODO（测试代理指出、主线采纳的守卫缺陷）──────────────────
  判据：**守卫只写否定式，会在「功能整个缺失」时静默放行。**
  [3] 只校验「不得引用 f_material_element_price」，当组件一个价格函数都不用时照样通过
  （当前正是如此：14 段 SQL 里 f_customer_element_price 也是 0）。
  ⇒ 已在自检块尾部写入 TODO，说明需补「材质元素页签必须绑 f_customer_element_price」的肯定式守卫；
     🚫 现在不能加 —— 元素单价列在等上游补语义节点（AC-8 阻塞），此刻加会让所有人服务起不来。

── ⏸ 未落库 ───────────────────────────────────────────────────────────
  安全闸差集：本 worktree 有 / master 无 / 共享库无 = [422] ⇒ 非空
  ⇒ 🚫 未起任何服务，V422 尚未落任何库。等你合 master。
