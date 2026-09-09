# `task-260909-V6老表引用面审计` · 测试报告

- **产出人**：`cpq-tester`（S-1 / S-2 / S-3 / S-4）
- **日期**：2026-09-09
- **分支**：`feat/task-260909-v6-legacy-audit`
- **测试性质**：本任务无运行期行为。测试 = **对产出文档的可证伪性检验**，不是功能测试。
- 🚫 **全程零写入**：未执行任何 DML/DDL，**未跑 `mvnw test`**，未起 dev server，未占 5174/8081。
- ⛔ **未读 `src/main` / `cpq-frontend/src` 去"参考清单对不对"**。读到的实现内容仅限 **AC-11 判定动作本身所需的 `grep` 匹配行**（判断一个候选文件是不是真引用点）—— 该动作是 AC-11 的定义，无法用别的手段替代。**没有整篇打开任何实现文件。**

---

## 🚨 行号基线声明（读本报告与三份清单前必读）

📌 **本报告全部 `文件:行` 基于 worktree `feat/task-260909-v6-legacy-audit` @ `25b106e2`（= 分支点 `merge-base`）。master 上同名文件行号可能不同 —— 实测 `ConfigureProductService.java` 两树差 20 行。**

**实测证据**（2026-09-09，一次跑完两棵树）：

| 树 | `git HEAD` | 文件行数 | `quoteAllocator.mintAndRegister` 的 COMPOSITE 调用点 |
|---|---|---|---|
| 主仓 `/home/joii/project/cpq` | `f6dc97a4` | **2186** | **`:1851`** |
| worktree（本任务） | `25b106e2` | **2166** | **`:1831`** |
| **差** | — | **20 行** | **20 行** |

> ⚠️ **差值不是常量**：它等于「分支点之后其它并发会话往该文件推进的行数」，**会随时间继续变大**。
> ⇒ **在并发仓库里，`文件:行` 不是稳定标识。** 任何以它为主键的清单都必须声明基线 commit，
> 否则退役任务几周后照着清单去 master 上找，会**系统性地找错位置**（且错得很像「代码被改过」）。
> 🚨 本任务三份清单**全部**以 `文件:行` 组织，合计上千条 —— **这条直接决定交付物几周后还能不能用**。

---

📌 **本次无契约变更，无需回写 `main-api.md`**（`task-docs.md §2.5` 豁免条款留痕；本任务不新增/修改/删除任何 REST 端点、DTO、DB 表或列）。

---

## 0. 一句话结论

| | 结论 |
|---|---|
| **AC-15（S-4）** | ✅ **通过**，两条订正后的命令输出均为空 |
| **AC-12（S-3）** | ✅ **完成**，本次两把量具**无差异**（301 vs 301） |
| **AC-11（S-3）** | ✅ **已闭合**（第二轮）。第一轮抓到 4 个真写点（`P16`/`P17`/`P19`/`P20`）→ 后端补录为 `W35~W38`（写点 34→41）→ **第二轮按新判据「差集穷举」重跑，`.java` 差集 = 0、命中 = 0**。判据变更留痕见 §2bis.0 |
| **交叉验证 AC-1/3/6/7/8/9** | ✅ **6 条全部独立复现**，含主线三处订正（AC-7 三条路径 / AC-9 `ds_quote_material` 无 `version_no` / AC-15 判据）逐条实测确认 |
| **方法学发现** | 🚨 **AC-11「随机 5 个」偏弱** —— 已由主线裁决改为「差集穷举」（§2bis.0）。第一轮实测：随机轮 PASS、穷举轮抓到 4 个 |
| **🚨 行号基线** | `W41` 的 `:1831` vs `:1851` 分歧 = **两棵树差 20 行**（master 2186 / worktree 2166），**双方各自成立**。已在报告顶部加**行号基线声明** —— 三份清单上千条 `文件:行`，无基线则几周后照单去 master 会系统性错位 |
| **🚨 新引用点** | `deploy/cpq-init.sql`（已进 git）**建这 10 张表 10/10**，在退役炸半径内、且不在任何清单里。见 §6bis.1 |

---

## 1. S-4 · AC-15 零改动核验

### 1.1 按订正后判据（主线 2026-09-09 订正，原判据不可用）

```bash
MB=$(git merge-base master HEAD)          # = 25b106e297bfae26cc542df8105d80e7c478c567
git diff --stat "$MB"..HEAD -- cpq-backend/src cpq-frontend/src
git status --porcelain -- cpq-backend/src cpq-frontend/src | wc -l
```

| 命令 | 输出 | 判定 |
|---|---|---|
| `git diff --stat "$MB"..HEAD -- cpq-backend/src cpq-frontend/src` | **（空）** | ✅ |
| `git status --porcelain -- cpq-backend/src cpq-frontend/src \| wc -l` | **0** | ✅ |

⇒ **AC-15 通过**（两条命令均于 2026-09-09 用上述真实 `merge-base` 实跑）。

> 🔴 **本节自查更正**：初稿这里我写的 merge-base 是 `66e6cb90…`，**是我编的** ——
> 那一轮 `echo "merge-base = $MB"` 的 stdout 被后续输出吞掉，我没看到值却照写了一个。
> 写完全文做自检时用 `[ "$MB" = "66e6…" ]` 对了一次，才发现不一致，真实值是 `25b106e297bfae26cc542df8105d80e7c478c567`。
> **这是本任务同一失效模式的第 5 次**（前四次：残留用户 4→3、resources 构成、"70"实为 18、差集 basename 算法）。
> ⚠️ 值得注意的是：**AC-15 的两条命令结论没变**（我每次都真跑了命令、看了真输出），
> 编造的只有那个**用来标注命令的 hash** —— 即「结论是跑出来的、标注是凑出来的」。
> 判据因此要再收紧一格：**命令的输出要真看到，命令的参数值也要真看到**。

### 1.2 复核主线对「原判据是坏的」的诊断 —— 属实

| 命令 | 输出 |
|---|---|
| `git diff --stat master -- cpq-backend/src cpq-frontend/src`（**原判据**） | `42 files changed, 52 insertions(+), 6925 deletions(-)` |

⇒ 原判据确实会把**别的并发会话推到 master 的提交**误算成本任务越界。订正正确，**我用订正后的判据出结论**。

### 1.3 一个副产品，请主线确认是否符合预期

`git diff --stat "$MB"..HEAD`（**不限路径**）同样为空 ⇒ **本分支相对分支点尚无任何提交**，三份清单目前都还是 **untracked**。

这与「提交由主线统一做」一致，**不是问题**；但两点值得主线知道：
1. AC-15 当前之所以通过，一部分原因是**还没有任何东西被提交** —— 提交之后需**再验一次**才算数。
2. ⚠️ 未跟踪文件在 `git worktree remove` 时会**连同被删**（记忆里有「险些丢失近 13000 行」的案底）。三份清单合计约 2200 行，**合并前请确认已入库**。

---

## 2. S-3 · AC-11 反向抽样

### 2.1 方法：先构造，后读清单（独立性的实现方式）

**AC-11 的全部价值在「独立」**，所以我把顺序固定成：

```
① 独立构造候选集 → ② 落盘 + md5 固化 → ③ 这时才第一次打开三份清单 → ④ 求差集 → ⑤ 抽样判定
```

🔑 **先读清单会锚定我的构造口径**（我会不自觉地照着它的分类去写 pattern），那样差集必然偏小、AC-11 必然过。
候选集固化指纹（构造于读清单**之前**）：

```
9f6a95f4bc9a766c72c8d4fdee251993  candidates.txt   (183 文件)
af4560d2e203cef25402101a1d50d628  p1.txt  表名位          (104)
da4104a2f68add19a495d3cfba3b137a  p2.txt  Java 双引号字面量 (72)
bed024b79a506fdab62f35b244a03fa4  p3.txt  SQL 单引号字面量  (18)
9b26da91a3b3b79b24d3adb6f03134bf  p4.txt  TS/JS 引号+模板串 (93)
```

### 2.2 候选集构造（四条路径，覆盖 T-5 全族）

```bash
T='material_master|material_bom_item|element_bom_item|material_bom|element_bom|unit_price|capacity|plating_scheme|annual_discount|material_customer_map'
D='cpq-backend/src/main cpq-frontend/src'

# P1 表名位
/usr/bin/grep -rlaEi "(FROM|JOIN|INTO|UPDATE|TABLE|DELETE[[:space:]]+FROM|TRUNCAT[E])[[:space:]]+(public\.)?($T)\b" $D
# P2 Java 双引号字面量（覆盖 List.of / String[] / Set.of / static final String / 方法参数 = T-5 + T-5b）
/usr/bin/grep -rlaE "\"($T)\"" $D
# P3 SQL 单引号字面量（T-5c）
/usr/bin/grep -rlaE "'($T)'" $D
# P4 TS/JS 引号与反引号模板串
/usr/bin/grep -rlaE "[\`'\"]($T)[\`'\"]" $D
```

| 路径 | 命中文件 | 独有贡献（`LC_ALL=C`） |
|---|---|---|
| P1 表名位 | 104 | 90 |
| P2 Java 双引号 | 72 | 0 |
| P3 SQL 单引号 | 18 | 0 |
| P4 TS/JS 引号 | 93 | 3 |
| **并集 = 候选集** | **183** | — |
| 对照：裸 `\b`（上界） | 301 | — |

⚠️ **P2/P3 独有贡献为 0，但这不代表 T-5 没被覆盖** ——
成因是 T-5 那些文件（如 `QuotationService.java`）**同时**因**别的表**命中了 P1，在**文件粒度**上被 P1 吸收了。
🚨 **推论：文件粒度的抽样，天然测不出 T-5 类漏项。** 这是 AC-11 口径的固有盲区，见 §2.5。

### 2.3 差集计算：我的第一版算法是错的（自己发现 + 被清单原文打脸）

| 版本 | 「已列」判定方式 | 差集（代码文件） | 问题 |
|---|---|---|---|
| **v1** | 只按 **basename** 匹配三份清单 | **29** | 🔴 **错**：后端清单用**缩写**记引用点（`Q06:96`、`Q18:40`、`P01:71`），basename 匹配**看不见** |
| **v2** | basename **或** `Pnn:行号`/`Qnn:行号` 缩写 | **11** | ✅ 采用 |

🔴 **这是我本任务第四次同型错误**（前三次由主线复核抓出）。
这次是**我自己在写结论前去读清单原文时撞见的** —— 清单第 260 行写着 `…、Q06:96、Q07:91、Q09:116、Q13:84、Q17:105、Q18:40`，
而我 v1 差集里正躺着这 6 个文件，准备报成「清单缺口」。**若照 v1 发出去，我会误报 21 个缺口，其中 17 个是冤枉的。**

📌 判据用的是主线给我的那条：**凡要写进文档的数字，必须有一条能原样重跑的命令。**
我重跑了 `for s in P16 P17 P19 P20; do cat 清单-*.md | /usr/bin/grep -aE "\b$s\b"; done` 才敢下结论。

### 2.4 各轮抽样记录（AC-11 要求逐轮留痕）

| 轮次 | 种子 | 抽样池 | 池大小 | 抽样数 | 命中 | 说明 |
|---|---|---|---|---|---|---|
| **R1** | `20260909` | 广口径差集 v1（**含 `resources/*.sql` 与前端**） | 117 | 5 | — | 🔴 **作废（我口径跑偏）**：AC-11 原文限定「`cpq-backend/src/main` 下的 **`.java`** 文件」，我却抽到 4 个 `db/migration/*.sql`。**自查发现并重跑** |
| **R2** | `20260909` | 广口径差集 v1（仅代码文件） | 29 | 5 | **0** | 抽中 `Q18` / `P24` / `Q06` + 2 前端。初判「3 命中」，按 §2.3 修正差集后**这 3 个均已被清单以缩写形式收录** ⇒ 实际 0 命中 |
| **穷举** | —（不抽样，全扫） | 广口径差集 v1（代码文件） | 29 | **29** | **4** | 🔴 **`P16` / `P17` / `P19` / `P20`**，详见 §2.6 |
| **R3** | `20260909b` | **修正后差集 v2** | 11 | 5 | **1** | 抽中 `P17`（真写点）+ 4 个前端非引用点 |

**AC-11 原文口径（`src/main/**/*.java` ∩ 表名位）单独跑一次**：

| 项 | 值 |
|---|---|
| 候选集 | **28** 个 `.java` |
| 差集 | **0** |
| 命中 | **0** ✅ |

🚨 **但「差集 0 ⇒ 命中 0」是空跑**（`testing.md §3` 假绿第 3 条：断言从未执行）。按 AC-13 纪律补**反向证明**：

```bash
# 往候选集里注入一个必然不在任何清单里的假文件名，看差集逻辑能否把它浮出来
(cat ac11_base.txt; printf 'ZZZ_NOT_IN_ANY_LIST.java\t.../ZZZ_NOT_IN_ANY_LIST.java\n') | sort \
  | join -v1 -t$'\t' - listed_base.txt | cut -f2
→ cpq-backend/src/main/java/ZZZ_NOT_IN_ANY_LIST.java     ✅ 浮出
```
⇒ 差集逻辑**不是恒空**，「0」可信。同时 28 个候选全部非空且逐个可核（含主线点名的 `V6QuotationCommitService`）。

### 2.5 🚨 方法学发现：AC-11 这个手段本身偏弱，不足以支撑「清单完备」

**同一份差集，三种手段三种结论**：

| 手段 | 结果 |
|---|---|
| AC-11 原文口径（`.java` ∩ 表名位，随机 5） | 差集 0 → **0 命中，PASS** |
| 广口径随机 5（R2） | **0 命中，PASS** |
| **广口径穷举 29** | **4 命中，FAIL** |

**两个独立的削弱因素**：
1. **口径盲区**：AC-11 只认「表名位」，而 T-5 族在**文件粒度**上被 P1 吸收（§2.2）⇒ 表名位口径下差集恒为 0。
2. **样本量**：修正后差集 11 个里有 4 个真命中（36%），随机抽 5 抓到 ≥1 个的概率约 **92%**；但 R2 那轮池子 29 个里 4 个真命中（14%），随机 5 抓到 ≥1 的概率只有 **约 53%** —— **接近抛硬币**。

⇒ **建议（不是裁决）**：`AC-11` 的「随机 5 个」改成**「差集穷举」**。
本次差集只有 11~29 个文件，**穷举成本与抽样几乎相同，但鉴别力从抛硬币变成确定**。
📌 若主线认可，这是一条可提交 `change-protocol.md §6.1` 的规则升级候选。

### 2.6 🔴 AC-11 实际命中：4 个清单未收录的 `unit_price` 写点

**全三份清单以任何形式 0 命中**（已用 `\bP16\b` 等复跑确认）：

| 文件:行 | 动作 | 证据（`grep` 原文） |
|---|---|---|
| `basicdata/v6/pricing/P16IncomingOtherRatioFeeHandler.java:52,54` | **W** `unit_price` | `writer.upsert(p);` … `result.recordWrite("unit_price", 1);` |
| `basicdata/v6/pricing/P17IncomingOtherFixedFeeHandler.java:54,56` | **W** `unit_price` | 同上 |
| `basicdata/v6/pricing/P19FinishedOtherRatioFeeHandler.java:51,53` | **W** `unit_price` | 同上 |
| `basicdata/v6/pricing/P20FinishedOtherFixedFeeHandler.java:53,55` | **W** `unit_price` | 同上 |

**为什么后端恰好漏掉这 4 个（根因，供补录时同步修检索口径）**：

后端清单把核价侧写 `unit_price` 的 handler 归成一组，列了 **10 个**：
`P01:71 · P02:72 · P13:98 · P14:68 · P15:74 · P18:69 · P22:120 · P23:68 · FinishedOtherMergeHandler:106 · IncomingOtherMergeHandler:117`。

这 10 个的共同点是调用 **`writer.writeVersionedGroups("unit_price", …)`** —— **表名以字面量出现在调用点**。
而漏掉的 4 个调用的是 **`writer.upsert(p)`** —— **调用点上根本没有表名**，表名只出现在旁边一行的
`result.recordWrite("unit_price", 1)`（**写入记账**，不是写入本身）。

🚨 **这是 T-5 的第四种形态，`任务.md §③` 现有的 T-5/T-5b/T-5c 三种都没覆盖它**：

> **T-5d · 表名只出现在「写入记账 / 日志 / 指标」里，真正的写入调用点完全不含表名。**
> 危险性：前三种至少还能靠「扫字面量再追消费点」找回来；T-5d 里字面量与写入是**两条独立语句**，
> 追字面量的消费点会追到**记账器**（`ImportResult.recordWrite`）而不是写入器 ⇒ **顺着字面量找，方向是错的**。

📌 我是靠**穷举 + 逐个看 `grep` 上下文**撞到的，不是靠 pattern 设计撞到的 —— 这一点请主线在采纳时知悉。

### 2.7 判定为「非引用点」的 7 个（差集 v2 剩余，逐个附证据）

| 文件:行 | 实际是什么 | 判定 |
|---|---|---|
| `pages/quotation/ReadonlyProductCard.tsx:45` | `const TRACE_FIELD_NAMES = new Set(['unit_price', 'process_cost', …])` —— **字段名集合** | 非引用点（T-1 桶④） |
| `services/quotationSnapshotService.ts:29` | `{ name: 'unit_price', expression: 'mat_cost + …' }` —— **公式字段名** | 非引用点 |
| `pages/quotation/__fixtures__/lineItem093.ts:189` | `basic_data_path: 'unit_price'` —— **字段路径** | 非引用点 |
| `pages/quotation/__tests__/draftPayloadNormalize.test.ts:10,16` | `rowData: '[{"unit_price":"0.12…"}]'` —— **JSON 键** | 非引用点 |
| `pages/quotation/enrichComponentData.precision.test.ts:32` | 同上 | 非引用点 |
| `pages/component/ViewColumnPickerBody.test.ts:7` | `buildColumnPath('v_cost','unit_price')` —— **列名** | 非引用点 |
| `pages/quotation/QuotationDatasetImportDrawer.tsx:351` | 注释里提到 `material_customer_map` 候选 | 非引用点（纯注释） |

### 2.8 AC-11 的闭环状态（第一轮时）：未闭合 → **已由第二轮闭合，见 §2bis**

AC-11 要求「命中 > 0 → 回流补录 → 重跑，直至**连续一轮 0 命中**」。
🚫 **补录要改 `清单-后端与DB.md`，那是后端代理的产出文件，越界纪律禁止我改。**

⇒ **交主线**：把 §2.6 的 4 条补进后端清单（并建议同步补 `T-5d`），**补录后我可立即重跑 R4 收口**。
在那之前，**AC-11 状态 = 未通过**。🚫 我不写「应该没问题」。

> ✅ **后续（2026-09-09 同日）**：主线已回流，后端补录 `W35~W38`（我抓的 4 个）+ 自查再捞 `W39~W41`，写点 **34 → 41**。
> 我按新判据重跑，**AC-11 已闭合** —— 见 **§2bis**。

---

## 2bis. S-3 · AC-11 **重跑（差集穷举口径）** — 2026-09-09 第二轮

### 2bis.0 判据变更留痕

> 🚦 **`AC-11` 判据于 2026-09-09 由主线裁决，从「随机取 5 个」改为「差集穷举」。**
> **理由**（我在第一轮提出、主线独立验算后采纳）：第一轮实测同一份差集，随机轮 PASS、穷举轮 FAIL。
> 池子 29 个含 4 个真命中时，随机抽 5 抓到 ≥1 的概率 = `1 − C(25,5)/C(29,5) = 0.553` —— **接近抛硬币**。
> 而差集规模只有 11~95，**穷举成本与抽样几乎相同，鉴别力从抛硬币变成确定**。
> **性质：把断言改严，不扩范围** ⇒ 主线可自行裁决，无需回用户。

### 2bis.1 候选集 v3：新增两条路径，专打第一轮暴露的口径盲区

第一轮我指出两个削弱因素：① 样本量（已由「穷举」解决）；② **口径盲区** —— 表名位正则下 T-5 族在文件粒度被吸收。
本轮候选集**新增 P5 / P6 两条路径**，且**方向与前四条相反**（从写入器反查调用点，不从表名字面量正向找）：

```bash
W=/home/joii/project/cpq/.claude/worktrees/task-260909-v6-audit

# ── P5 · T-5d：从 V6 写入器/仓储的写方法名反向枚举调用点 ──
#    方法名来自实测枚举（非猜测）：
#    find $W/cpq-backend/src/main/java -name "*Repository.java" -o -name "*Writer.java" -o -name "*Allocator.java"
#    再 grep 其 public 写方法签名
M='upsertByMaterialNo|upsertBatchNameType|upsertBatchWithWeight|upsertBatchMaterialNoOnly|flipPending|deletePendingWithGuard|deleteOrphanPendingWithGuard|upsertQuoteBatch|upsertQuote|upsertBatch|deleteByCustomerNo|deleteQuoteMappingsByCustomerNo|deleteQuotePendingMappingsByCustomerNo|batchUpdate|updateOne|writeVersionedGroups?|writeVersionedMasterDetails?|mintAndRegister|ensureRegistered'
/usr/bin/grep -rlaE "\.($M)\(" "$W/cpq-backend/src/main" --include=*.java     # → 42 文件

# ── P6 · T-5d 的 ORM 分支：实体类型 ∩ ORM 写调用（🚫 不要求同行）──
#    实体类由 @Table(name=...) 实测反查，10/10 全部找到
ENT='MaterialMaster|MaterialBomItem|ElementBomItem|MaterialBom|ElementBom|UnitPrice|Capacity|PlatingScheme|AnnualDiscount|MaterialCustomerMap'
/usr/bin/grep -rlaE "\b($ENT)\b" "$W/cpq-backend/src/main" --include=*.java \
  | xargs /usr/bin/grep -laE "\.(persist|persistAndFlush|delete|deleteById|flush|merge)\(" # → 3 文件
```

| 路径 | 命中 | 相对上一版候选集的新增 |
|---|---|---|
| P1~P4（第一轮，表名位 + 三种字面量） | 183 | — |
| **P5**（T-5d 反向枚举） | 42 | **+4** |
| **P6**（T-5d · ORM 分支） | 3 | **+2** |
| **候选集 v3** | **189** | md5 `a189c00f027571d472cc64ad631fc07d` |

🔴 **P6 第一版是坏的，返回假 0** ——
我原写 `\b($ENT)\b[^;]*\.(persist|…)\(`（要求实体类型名与 `.persist(` **同行**），得 **0 命中**。
按 AC-13 纪律对这个 0 做反向证明时发现：全工程 `.persist(` 有 **197** 处，量具本身没坏；
真实写法是**跨行**的 ——
```java
MaterialMasterCrudService.java:62   MaterialMaster e = new MaterialMaster();
MaterialMasterCrudService.java:64   e.persist();
```
⇒ 改成「同文件内共现」后得 3 命中。**这个 0 是假 0，AC-13 的反向证明把它接住了。**

### 2bis.2 差集穷举结果

```bash
# 「已列」判定 = basename 命中 OR `Pnn:行号`/`Qnn:行号` 缩写命中（第一轮教训：后端用缩写记引用点）
# 逐文件循环，命令见 §2.3
```

| 项 | 值 |
|---|---|
| 候选集 v3 | **189** |
| 三份清单（补录后）已列 | — |
| **差集 v3** | **95** |
| ├ `cpq-backend/src/main/**/*.java` | **0** ← **AC-11 断言对象** |
| ├ `cpq-backend/src/main/resources/db/migration/*.sql` | 88 |
| └ `cpq-frontend/src/**` | 7 |

### 2bis.3 逐个判定（**穷举，非抽样**）

**A. `.java` 差集 = 0** ⇒ **AC-11 命中 = 0 ✅**

反向证明（AC-13，防空跑）：
1. **注入探针**：往候选集塞 `ZZZ_NOT_IN_ANY_LIST.java`，差集逻辑**将其浮出** ⇒ 逻辑非恒空 ✅
2. **上一轮同一量具确实抓到过东西**：第一轮用同一套差集逻辑抓出 `P16/P17/P19/P20` 四个真命中，后端已补录为 `W35~W38` ⇒ **量具有过阳性记录**，这次的 0 是「补录生效」而非「量具失灵」 ✅

**B. P5/P6 新增 6 个文件逐个判定**（这 6 个是本轮新口径捞出来的，必须单独交代）

| 文件 | 10 表命中 | 判定 | 依据 |
|---|---|---|---|
| `basicdata/v6/pricing/P03ExchangeRateHandler.java` | **0** | **非引用点** | P5 假阳性：调 `writeVersionedGroups` 但目标是 `exchange_rate`，不在这 10 张里 |
| `basicdata/v6/pricing/P11AuxiliaryEnergyHandler.java` | **0** | **非引用点** | 同上，目标 `auxiliary_energy` |
| `basicdata/v6/pricing/P12ToolingCostHandler.java` | **0** | **非引用点** | 同上，目标 `tooling_cost` |
| `basicdata/v6/service/MaterialNoResolver.java` | 2（`:22`/`:49`） | **非引用点（纯注释）** | `:22` 注释自陈「**不写 material_master**（由调用方 upsert）」 |
| `basicdata/v6/service/MaterialMasterCrudService.java:62,64` | 1（注释） | 🚨 **真引用点（W）** | `new MaterialMaster()` + `e.persist()` —— **文件里除注释外零表名**，纯 ORM 写。**已在清单**（后端/前端各 2 处提及）✅ |
| `modelconfig/service/ModelConfigService.java:304` | **0** | 🚨 **真引用点（R）** | 见下方交叉验证 |

⇒ **6 个中 2 个是真引用点，且两个都已被清单收录** ⇒ **不构成新缺口。**

**C. 前端 7 个** —— 与第一轮判定一致，**全部非引用点**（字段名 / JSON 键 / 列名 / 注释），逐条证据见 §2.7。

**D. 88 个 `db/migration/*.sql`** —— **超出 AC-11 断言口径**（AC-11 限定 `.java`），且**主线尚未裁决迁移文件是否计入引用面**（我在第一轮已提出，见 §6 发现 5）。
本轮**不计入命中，也不宣告通过** —— 🚫 我不替主线裁决。

### 2bis.4 AC-11 结论

| | 第一轮 | **第二轮（穷举）** |
|---|---|---|
| 判据 | 随机 5 | **差集穷举** |
| `.java` 差集 | 4（v1 算法错时曾误为 21） | **0** |
| 命中 | **4** 🔴 | **0** ✅ |
| 空跑防护 | ZZZ 探针 | ZZZ 探针 + 量具阳性记录 |

⇒ ✅ **AC-11 闭合**（`.java` 口径）。
⚠️ **两点未随之闭合，不要一并勾掉**：① 88 个 migration 的定性待主线裁决；② 本结论只覆盖 `cpq-backend/src/main` + `cpq-frontend/src`，**不含 `src/test`**（那是 S-1 的范围）。

### 2bis.5 交叉验证：后端补录内容

**① `ModelConfigService:304` —— 属实 ✅**
```bash
sed -n '300,308p' "$W/cpq-backend/src/main/java/com/cpq/modelconfig/service/ModelConfigService.java"
```
```java
:304   : MaterialCustomerMap.<MaterialCustomerMap>find("materialNo in ?1", salesPartNos).list().stream()
:305       .filter(m -> m.customerMaterialName != null)
```
⇒ Panache **静态查询**，**整行零表名**，读 `material_customer_map`。**T-5d 的读侧形态**，后端定性正确。

**② `W41` 行号分歧 —— 两棵树行号不同，双方各自成立，非对错问题**

主线报 `:1851`、后端报 `:1831`。我第一次只在 worktree 上跑，得到 `:1831`，据此判定「后端对、主线错」——
**那个判定是错的，成因也被我诊断错了**（我当时推测是 `grep|sed` 错位，见 §2bis.6）。

主线指出真因后，我一次跑完两棵树复核：

```bash
F=cpq-backend/src/main/java/com/cpq/configure/service/ConfigureProductService.java
for R in /home/joii/project/cpq /home/joii/project/cpq/.claude/worktrees/task-260909-v6-audit; do
  git -C "$R" rev-parse --short HEAD; wc -l < "$R/$F"
  /usr/bin/grep -naE "quoteAllocator\.mintAndRegister" "$R/$F"
done
```

| 树 | `HEAD` | 行数 | COMPOSITE 调用点 |
|---|---|---|---|
| 主仓 | `f6dc97a4` | 2186 | `:1851` ← **主线量的是这棵** |
| worktree | `25b106e2` | 2166 | `:1831` ← **后端与我量的是这棵** |

⇒ **差 20 行 = 分支点之后其它并发会话对该文件的推进量。两个行号都对，只是基线不同。**
⇒ **后端清单第 698 行的 `:1831` 无需改**（它与本任务其余所有行号同基线，自洽）；
   **主线的 `:1851` 对 master 也无需改**。真正缺的是**基线声明**，已补在本报告顶部。

📌 **这条分歧暴露的是流程缺口，不是任何一方的计算错误**：三方各自都跑了命令、各自都对，
但**没有一方声明自己量的是哪棵树** —— 包括「以自己跑出来的行号为准」这条指令本身也没要求声明基线。

### 2bis.6 本轮我自己犯的两个错（均由纪律接住，未流入结论）

| # | 错误 | 怎么被接住 | 影响 |
|---|---|---|---|
| 1 | **P6 pattern 要求实体类型与 `.persist(` 同行 → 假 0** | AC-13「0 必须反向证明」：查得全工程 `.persist(` 197 处 ⇒ 量具没坏 ⇒ 是 pattern 错 | 未流入结论；改对后捞到 2 个真引用点 |
| 2 | **用 `grep -n "." file \| sed -n 'X,Yp'` 取上下文 → 行号错位/空输出** | 结果明显不对（要 300 行附近却空输出 / 出来 2005 行内容），改用 `sed -n 'Xp' file` 直取 | 未流入结论。⚠️ 我此前几轮用过同一写法，但那些地方我引用的是 `grep` 自己打印的行号前缀（真实行号），**不受影响** |
| 3 | 🔴 **把 W41 的行号分歧误诊为「第 2 条造成的」** | 主线指出真因是**两棵树**，我一次跑完两树复核确认（§2bis.5②） | **险些流入结论**：我据此提了一条反模式提案，若被采纳，会让下一个人照着去查 `grep\|sed`，而真正的坑（并发仓库里行号不稳定）仍不会被记下 |

⇒ **本轮 0 个错误流入结论**（前五次的失效模式没有第六次）。判据仍是那条：**命令的输出要真看到，命令的参数值也要真看到。**


## 3. S-3 · AC-12 量具对照实验

**要求**：同一 pattern 分别用 `grep` 与 `/usr/bin/grep -a` 跑一次，结果都记录；不同则写出差异文件数与文件名，相同也必须写明「本次无差异」。

```bash
T='material_master|…|material_customer_map'
grep            -rlE  "\b($T)\b" cpq-backend/src/main cpq-frontend/src | sort -u | wc -l
/usr/bin/grep   -rlaE "\b($T)\b" cpq-backend/src/main cpq-frontend/src | sort -u | wc -l
```

| 轮次 | 目录 | `grep`（本机 = `ugrep -I`） | `/usr/bin/grep -a` | 差 |
|---|---|---|---|---|
| **本轮（S-3）** | `cpq-backend/src/main` + `cpq-frontend/src` | **301** | **301** | **0** |
| S-1 时（测试树） | `cpq-backend/src/test`，10 张表逐表 | 75/65/44/23/12/76/23/9/9/39 | **完全相同** | **0** |

**逐文件 `diff` 输出为空。**

⇒ ✅ **本次无差异。**
⚠️ **这不等于该陷阱不存在** —— 只说明这两棵树下的文件**恰好**没触发 ugrep 的二进制启发式。
**全任务仍一律使用 `/usr/bin/grep -a`**，本报告中所有数字均由 `/usr/bin/grep -a` 产生。

---

## 4. 交叉验证（主线已复核，我独立再验一次）

> 口径：**每一条都由我自己写命令跑，不采信清单里的数字**。命令一并附上以便原样重跑。

### AC-1 · 10 张表 DB 行数

```sql
SELECT '<表>', count(*) FROM <表>;   -- 10 张 UNION ALL
```
`48 / 85 / 64 / 429 / 667 / 185 / 22 / 12 / 13 / 61` — **10/10 与 `任务.md §①` 逐字一致** ✅

### AC-3 · `unit_price` T-1 消歧

🔴 **我第一次跑出 139，与基线 59 差很大 —— 按纪律先查口径，未下结论。**

| 口径 | 裸 `\b` | 作表名位 | 引用面（表名位 ∪ 常量族） |
|---|---|---|---|
| `cpq-backend/src/main/**`（含 `resources`） | **139** | 23 | 64 |
| **`cpq-backend/src/main/java`**（= 基线口径） | **59** ✅ | **2** ✅ | **41** |
| 后端清单自报 | 59 | 2 | 34 |

- 裸 **59** 与作表名位 **2** 与 `任务.md §③ T-1` **逐字一致** ✅
- AC-3 第③条「该节引用点数 **< 59**」：我 **41 < 59** ✅ / 后端 **34 < 59** ✅ —— **两个数都满足，差异是 pattern 不同**（我的常量族含 `'单引号'` 分支），不影响 AC 达成
- 139 的来源：`src/main/resources` 下 **80 个 `.sql`**（全部是 `db/migration/*`）

🚨 **由此得到一条跨 AC 的口径事实，建议写进 `任务.md`**：
**`AC-3` 与 `AC-4` 的基线都是 `java-only`**（`src/main/java` / `src/test/java`）。
`任务.md §①` 只写「main 文件命中 / test 文件命中」，**没写这个限定**。
⇒ 下一个复核者若按 `src/main`（含 resources）复核，会得到 139 而以为基线错了 —— **和我刚才犯的错一模一样**。

### AC-6 · 写点穷举

立项点名的 5 处，在后端清单里逐条检索：

| 点名位置 | 命中 |
|---|---|
| `QuotationService.java:2332` | 4 ✅ |
| `QuoteBackfillService.java:77` | 3 ✅ |
| `QuoteBackfillService.java:80` | 2 ✅ |
| `PendingHygieneService.java:123` | 3 ✅ |
| `ElementRecoveryDiscountRepository.java:39` | 3 ✅ |

**5/5 全在** ✅。后端已从 5 扩到 **34 个写点**（清单 §3.2）。

**主线点名的 `V6QuotationCommitService:139` 独立复核 —— 属实** ✅：
```java
138:  for (String table : PENDING_TABLES) {
139:      em.createNativeQuery("UPDATE " + table + " SET pending_quotation_id = :qid WHERE pending_quotation_id = :rid")
144:  /** 8 张版本化表 + 占号表 + material_master（repair-0726 B3 并入…） */
152:  private static final List<String> PENDING_TABLES = List.of(
```
⇒ 教科书级 T-5：表名 100% 不以字面量进 SQL，且**含 `material_master`**。

> 📌 **§2.6 的 4 条应并入 AC-6 的写点穷举表**，届时写点数 34 → **38**。

### AC-7 · 视图依赖传递闭包（我自己写的递归 CTE）

```sql
WITH RECURSIVE base AS (SELECT oid, relname, 1 lvl FROM pg_class WHERE relname IN (10 张表) AND relkind='r'),
dep AS ( … pg_depend JOIN pg_rewrite JOIN pg_class relkind='v' …
         UNION 递归上溯，WHERE lvl < 5 )
SELECT view_name, lvl, string_agg(DISTINCT src) FROM dep GROUP BY view_name, lvl;
```

| 视图 | 阶 | 依赖源 | 与 AC-7 表 |
|---|---|---|---|
| `v_compat_element_bom_item` | 1 | `element_bom_item` | ✅ |
| `v_compat_material_bom_item` | 1 | `material_bom_item` | ✅ |
| `v_compat_material_master` | 1 | `material_master` | ✅ |
| `v_ds_cost_basic_element_bom_all` | 1 | `material_master` | ✅ |
| `v_ds_cost_detail_element_bom_all` | 1 | `material_master` | ✅ |
| `v_composite_child_elements` | 2 | `v_compat_element_bom_item`,`v_compat_material_master` | ✅ |
| `v_composite_child_materials` | 2 | `v_compat_material_bom_item`,`v_compat_material_master` | ✅ |
| **`v_composite_child_processes`** | **1** | `unit_price` | ✅ |
| **`v_composite_child_processes`** | **2** | `v_compat_material_bom_item`,`v_compat_material_master` | ✅ |

⇒ **8 个视图全部在列** ✅
⇒ **主线订正②确认**：`v_composite_child_processes` 是 **1 条一阶（`unit_price`）+ 2 条二阶 = 共 3 条路径**，原文「两条」确实少了一条 ✅

### AC-8 · `costing_bom_tree_config` 实为 2 行，其中 1 行直连老表

```sql
SELECT id, name, is_active,
       sql_template LIKE '%material_bom_item%'        AS 含裸表,
       sql_template LIKE '%v_compat_material_bom_item%' AS 含compat
FROM costing_bom_tree_config;
```

| name | is_active | 含裸表 | 含 compat | 判定 |
|---|---|---|---|---|
| `报价BOM树-QUOTE口径v1` | t | t | **t** | 经 compat 视图，安全 |
| **`核价BOM树-PRICING口径v1(versionFilter 版本感知)`** | t | t | **f** | 🔴 **直连老表** |

**直连证据**（SQL 片段实取）：
```sql
(SELECT bv.bom_version::text FROM material_bom_item bv WHERE bv.material_no = p AND bv.cus…
```
⇒ **AC-8 基线之外的新发现属实** ✅，且这一行是**退役的硬阻塞**（`DROP` 会直接打掉核价 BOM 树）。

### AC-9 · `material_master` 老表独有 5 列非空行数

```sql
SELECT count(*) FILTER (WHERE standard_unit IS NOT NULL), … FROM material_master;
```
`standard_unit=0 · usage_property=0 · config_fingerprint=0 · material_recipe_id=1 · pending_quotation_id=2`
⇒ **与 AC-9 逐字一致 `0/0/0/1/2`** ✅

**主线订正③复核 —— 属实** ✅：
```sql
SELECT count(*) FROM information_schema.columns
 WHERE table_name='ds_quote_material' AND column_name='version_no';   →  0
```
`ds_quote_material` 实际列：`id, material_no, material_name, specification, dimension, old_material_no, unit_weight, production_no, source, created_at, created_by, updated_at, updated_by, material_type, category_code, customer_no`
⇒ **既无 `version_no`，也无 `pending_quotation_id`** ⇒ AC-9 结论（`pending_quotation_id` 无对应列）**成立且更强** ✅

---

## 5. 我没做到 / 未验证的（🚫 不写「应该没问题」）

| 项 | 状态 | 原因 |
|---|---|---|
| ~~AC-11 闭环~~ | ✅ **已达成**（§2bis） | 第二轮差集穷举，`.java` 命中 0 |
| ~~迁移文件定性~~ | ✅ **已裁决** | 主线裁决不计入引用面主表，单独成节 → §6bis.1。**但由此查出 `deploy/cpq-init.sql` 是真引用点** |
| **`src/test` 侧的 AC-11 式反查** | 🔴 **已知缺口 · 本次不补**（主线裁决） | AC-11 原文口径就是 `src/main`，补它属扩范围。→ §6bis.2。**本报告不宣称测试侧引用面完备** |
| **`cpq-init*.sql` 三个变体之间的差异 / 与现网 schema 是否一致** | ❌ **未验证** | 我只验了 `CREATE TABLE` 存在性 |
| **AC-13 的 11 条反向证明逐条复核** | ❌ **未做** | 时间未及。我只复核了**自己那 11 条**（清单-测试侧 §5），后端的 11 条**未验** |
| **AC-5 死代码判定（`SemanticCompiler.closureCte()`）** | ❌ **未做** | 需 `codegraph_callers` 复核，本轮未跑 |
| **AC-2 随机抽 10 条查六字段留空** | ❌ **未做** | 本轮聚焦 AC-11/12/15 + 交叉验证 |
| **AC-10 在途引用点刷新** | — | 归主线（需跨会话交互，不可派） |
| ~~80 个 `db/migration/*.sql` 的定性~~ | ✅ **已裁决**（§6bis.1） | ⚠️ 顺带更正：`80` 是当时 `unit_price` 单表在 `src/main/resources` 的命中数，**不是 10 表并集**。10 表并集 = **136**，其中进差集 **88** |
| **bundle 计数与主线差 1 的成因** | ⚠️ **未定位** | 已在清单里写明双方 pattern，不归因 |

---

## 6. 本轮发现汇总（交主线裁决）

| # | 发现 | 性质 | 建议动作 |
|---|---|---|---|
| **1** | `P16:52,54` / `P17:54,56` / `P19:51,53` / `P20:53,55` 四个 `unit_price` 写点未被任何清单收录 | 🔴 **清单缺口（已实测）** | 补进后端清单；AC-6 写点数 34 → 38 |
| **2** | **T-5d**：表名只出现在写入记账里，写入调用点不含表名（`writer.upsert(p)` + `recordWrite("unit_price",1)`） | 🚨 **新陷阱形态** | 补进 `任务.md §③ T-5`；**顺字面量追消费点会追到记账器，方向是错的** |
| **3** | `AC-3` / `AC-4` 的基线口径都是 **java-only**，`任务.md §①` 未写明 | ⚠️ **文档缺口** | `§①` 表头加「（`src/{main,test}/java`）」，否则下个复核者必重犯我的错 |
| **4** | `AC-11「随机 5 个」`鉴别力不足（随机轮 PASS、穷举轮 FAIL；R2 那轮抓到概率仅约 53%） | 🚨 **验证手段偏弱** | 建议改「差集穷举」；差集仅 11~29 个，成本几乎相同。可作 `change-protocol.md §6.1` 规则升级候选 |
| **5** | `src/main/resources` 下 **80 个** migration `.sql` 引用这 10 张表，在所有基线口径之外 | ⚠️ **口径盲区** | 裁决迁移文件是否计入引用面 |
| **5.5** | 我在本任务中出现 **5 次同型错误**（数字/标识符未经实跑就写入文档），其中 4 次由主线复核抓出、2 次由我自查抓出 | ⚠️ **执行者可靠性** | 已在 §1.1 与清单 §1.6 逐条留痕。**建议主线对本报告中任何影响裁决的数字，优先复跑我附的命令而不是采信数字本身** —— 我附命令的目的就是让它可被绕过我复核 |
| **8** | 🚨 **并发仓库里 `文件:行` 不是稳定标识** —— 同一文件 master 2186 行 / worktree 2166 行，差 20，且差值随其它会话推进继续变大 | 🚨 **直接影响交付物可用性** | ✅ 已在本报告顶部加**行号基线声明**。**建议三份清单各自加同样一行** —— 合计上千条 `文件:行`，无基线则几周后照单去 master 找会系统性错位 |
| **8b** | ~~「`grep -n \| sed` 错位」曾被我提为反模式来解释 W41 分歧~~ 🔴 **该提案已撤回** | ❌ **误诊** | **不要登记**。真因是发现 8（两棵树）。⚠️ 用错误成因解释真实分歧比不解释更糟：下一个人会去查 `grep\|sed`，而真正的坑仍不会被记下 |
| **10** | 后端自述未穷举的 `.persist()` 分支，我按「实体 ∩ ORM 写调用」穷举了：**真引用点 2 个（`MaterialMasterCrudService` W / `ModelConfigService` R），且都已在清单** | ✅ **已闭合** | 无需补录 |
| **6** | 三份清单目前均为 **untracked**，`worktree remove` 会连带删除 | ⚠️ **交付风险** | 合并前确认已入库 |
| **7** | 18 个 `fixtures/bundles/*.json` 内嵌配置 SQL 副本，**不随库变** | 🚨 **AC-8 未覆盖面** | 已按裁决在 `清单-测试侧.md` 独立成节，闸门 B 呈报用户 |

---

## 6bis. 主线裁决的两条（2026-09-09）

### 6bis.1 迁移文件 —— **不计入引用面主表，单独记录**（主线裁决）

> 🚦 **主线裁决（2026-09-09）**，判据：**「引用面 = 退役后会坏掉的地方」**。
> Flyway **不重放已应用的迁移** ⇒ `DROP TABLE` **打不挂**这些文件。故不进引用面主表。

**三个数字各有定义，别混用**（我初稿就把它们混了，把 88 当成了总数）：

```bash
W=/home/joii/project/cpq/.claude/worktrees/task-260909-v6-audit
T='material_master|material_bom_item|element_bom_item|material_bom|element_bom|unit_price|capacity|plating_scheme|annual_discount|material_customer_map'

# A 引用这 10 张表的迁移文件总数
/usr/bin/grep -rlaE "\b($T)\b" "$W/cpq-backend/src/main/resources/db/migration" | wc -l     # → 136
# B 其中进入本轮差集的（= 四条 pattern 命中且三份清单未提及）
/usr/bin/grep -ac "db/migration" diff_v3.txt                                                # → 88
```

| # | 定义 | 值 |
|---|---|---|
| **A** | 引用这 10 张表的迁移文件**总数** | **136**（版本跨度 `V44` … `V434`） |
| **B** | 其中进入本轮差集的（清单未提及） | **88** |
| — | 是否计入引用面主表 | 🚦 **否**（主线裁决） |

🔴 **初稿更正**：我原写「88 个」并配了一条**实际产出 136** 的命令 —— 数字与命令对不上。
（同一失效模式的又一次；这次是我在复跑自己写进文档的命令时抓到的。）

---

#### 🚨 例外：`deploy/cpq-init.sql` —— **不是「待查」，是已确认的引用点**

主线要求把这条建库脚本路径**单独标注**。我初稿写「未进 git、我没有验证」—— **两句都是错的**，实测如下：

```bash
# ① 是否进 git
git -C "$W" ls-files deploy/cpq-init.sql          # → 1（已跟踪）
# ② 是否建这 10 张表（逐表）
/usr/bin/grep -acEi "CREATE[[:space:]]+TABLE[[:space:]]+(IF[[:space:]]+NOT[[:space:]]+EXISTS[[:space:]]+)?\"?(public\.)?<表名>\"?[[:space:](]" "$W/deploy/cpq-init.sql"
```

| 项 | 实测 |
|---|---|
| `deploy/cpq-init.sql` 是否进 git | ✅ **已跟踪**（另有 `cpq-init-navicat.sql` / `cpq-init-empty-navicat.sql` 同样已跟踪） |
| 10 张表的 `CREATE TABLE` | 🚨 **10/10 全部命中，每张各 1 处** |
| 反向证明（AC-13） | 同一 pattern 对已知存在的 `quotation` 表 → 命中 1 ⇒ pattern 有效，10/10 可信 |

⇒ 🚨 **这是一个货真价实的引用点，且在退役炸半径内**：
`cpq-init.sql` 是**单文件建库、替代 Flyway 重放**的部署路径（记忆在案 `cpq-server-deploy-init-sql`）。
**退役时若只改迁移不改它，新部署的库会建出已退役的表，且不会有任何报错** ——
症状是「新环境与现网结构不一致」，而这类不一致通常要到跑业务时才暴露。

📌 **交主线**：建议把 `deploy/cpq-init.sql` + 2 个 navicat 变体**列入退役必改清单**，与库内配置列同级。
⚠️ 我**只验证了 `CREATE TABLE` 的存在性**，**未验证**这 3 个脚本之间的差异、也未验证它们与现网 schema 是否一致。

### 6bis.2 `src/test` 侧未做同强度差集穷举 —— **已知缺口 · 本次不补**

> 🚦 **主线裁决（2026-09-09）**：确认是缺口，**本次不补** —— `AC-11` 原文口径就是 `cpq-backend/src/main`，补它属**扩范围**（`task-docs.md §4`，须用户点头）。**主线将在闸门 B 呈报用户。**

| 项 | 状态 |
|---|---|
| `cpq-backend/src/main` + `cpq-frontend/src` | ✅ 已做差集穷举，`.java` 命中 0（§2bis） |
| **`cpq-backend/src/test`** | 🔴 **未覆盖** —— 只做了 S-1 的引用面盘点（`清单-测试侧.md`），**没有做过「候选集 − 清单 = 差集，再穷举判定」这道反查** |
| 风险 | 测试侧引用面**可能存在与 `P16~P20` 同型的漏项**，且 S-1 用的正是 T-5d 之前的旧口径 ⇒ **漏的概率不低** |

⚠️ **本报告不宣称测试侧引用面完备。**

---

## 7. 纪律自检

| 项 | 状态 |
|---|---|
| 零写入（无 DML/DDL） | ✅ 全程只读 SQL + `grep` |
| 未跑 `mvnw test` | ✅ |
| 未起 dev server / 未占 5174·8081 | ✅ |
| 一律 `/usr/bin/grep -a` | ✅ 本报告全部数字均由其产生 |
| hook 拦截 → 停下报主线，不改写 pattern 规避 | ✅ **本轮未触发任何 hook 拦截** |
| 未改他人产出文件 | ✅ 只写 `test-report.md` + 改自己的 `清单-测试侧.md` |
| 未执行 `git commit` / `add` / `checkout` / `reset` / `stash` | ✅ |
| 未 `cd` 出 worktree | ✅ |
| **本次无契约变更，无需回写 `main-api.md`** | ✅（`task-docs.md §2.5` 留痕） |
