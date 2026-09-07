# 后端任务分解 · task-260907 报价侧加客户维度

> 后端只按本文做。🚫 不 `git commit`（由主线提交）· 🚫 遇 `CLAUDE.md` §3.2 不可逆操作停下报告。
> **本任务的完整背景与四条夹击约束见 `交接说明-customer_no.md`，动手前必读。**

---

## 🚨 开工前必读的三条（顺序不能反）

### ① 🚨 本任务一律连**隔离库**起服务，🚫 绝不连共享库 `cpq_db_0724`

**隔离库已就绪（主线 2026-09-07 建）**：`cpq_t260907_custdim`
```
10.177.152.12:5432/cpq_t260907_custdim   凭据同 dev：postgres / joii5231
由 cpq_db_0724 全量 pg_dump 克隆，restore 错误 0 条
292 张表 / flyway 顶版 422 / ds_quote_material 49 行  ← 与共享库当时状态一致
```

🚨 **必须用环境变量覆盖，🚫 不要用 `-Dquarkus.datasource.jdbc.url`**：
```bash
cd cpq-backend && \
DB_HOST=10.177.152.12 DB_PORT=5432 DB_NAME=cpq_t260907_custdim \
DB_USERNAME=postgres DB_PASSWORD=joii5231 \
  ./mvnw quarkus:dev -Dquarkus.http.port=<你自己的端口>
```

🚨 **为什么 `-D` 那种写法是错的（2026-09-07 实证，主线先前派工给错了）**：
配置里有**两个**数据源，都默认指向共享库：
```
application.properties:24  quarkus.datasource.jdbc.url=...${DB_NAME:cpq_db_0724}
                     :40  quarkus.datasource."datasource-readonly".jdbc.url=...${DB_NAME:cpq_db_0724}
```
`-Dquarkus.datasource.jdbc.url=...` **只覆盖第一个** ⇒ **`datasource-readonly` 池仍然连着共享库**，
而 `SqlViewExecutor` 走的正是它 ⇒ 一切经 `$view` / SQL 视图执行取到的证据，**读的都是共享库**。
环境变量走 `${DB_NAME:...}` 占位符，**两个数据源一起被覆盖**（实测：冷启动 7 条连接全在隔离库、0 条在共享库）。
✅ **起完先自证连对了库**：查 `flyway_schema_history` 顶版应为 **423 或更高**。
**若仍是 422 ⇒ 你没连过去，参数没生效，立刻停下** —— 这一步不做，后面所有「已验证」都可能是在共享库上得出的。

🚨 **为什么非隔离库不可**（主线 2026-09-07 实证，不是保守起见）：
`V423` 给 28 张 `ds_quote_*` 加 `customer_no` 并**收紧 `NOT NULL`**。而 master 的代码里：
```
DatasetSchemaSelfCheck.java:116   if (!exp.contains(col)) problems.add(table + " 多出未声明的列: " + col);
DatasetSchemaSelfCheck.java:73    throw new IllegalStateException(...)
```
是**双向**比对 —— **DB 多一列同样抛**。共享库当时有 **12 条活连接 / 6 个并发会话**。
⇒ `V423` 一旦落共享库，**所有会话的后端下次启动全部失败**，不是本分支挂，是所有人挂。
⇒ 这也是为什么 **🚫 不许把 V423/V424 单独推 master 来「解阻塞」** —— 那正是下面 ② 禁止的中间态。

### ①a 🚨 **建迁移文件的那一刻就要查有没有服务在跑**（比 ①b 更早的时点）

```bash
# 在 worktree 里【新建/修改任何迁移文件之前】跑这个。判据是 cwd，不是命令行文本。
W=/home/joii/project/cpq/.claude/worktrees/task-260907-customer-dim
PIDS=""
for p in $(pgrep -x java); do
  c=$(readlink -f /proc/$p/cwd 2>/dev/null)
  case "$c" in "$W"|"$W"/*) PIDS="$PIDS $p";; esac
done
if [ -n "$PIDS" ]; then
  echo "🚨 本 worktree 有服务在跑（PID:$PIDS）—— 迁移文件与运行中的服务同时存在 = 随时可能被热重载落库"
  echo "   ⇒ 先按端口精确停掉它（ss -lptn \"sport = :<port>\"），再建迁移文件"
else
  echo "✅ 本 worktree 无运行中的服务，可以建迁移文件"
fi
```

🚨 **判据必须是 `cwd`，🚫 不能是命令行子串匹配**（2026-09-07 实证，主线第一版就是错的）：
原版用 `index($0, w)` 匹配整条命令行，**命中了两个根本不属于本 worktree 的进程** ——
测试代理的 scratch 隔离副本，它们的命令行里只是**提到**了这个路径：
```
-Dcustdim.evidenceDir=<worktree>/dev-docs/...     ← 命中在这
SRC=<worktree>                                     ← 命中在这
实际 cwd = <scratch>/iso/cpq-backend               ← 根本不读本 worktree 的 migration 目录
```
🚨 **这个假阳性比漏放更危险**，因为**守卫诱导的动作是「按端口停掉它」** ——
照做就会去杀别人正在跑的测试进程。后端代理没有照做，改用 cwd 判据复核后才判清。
（这是本文档里第三次记同一件事：**守卫的假阳性和假阴性都要验**。）

🔑 **为什么必须在这个时点、而不只是「起服务前」**（并发会话 2026-09-07 实证，**差一个 HTTP 请求就炸**）：

对方的子代理**照规矩做了**「起服务前跑差集」，也**确实没有起服务**。但它还是差点把 `V423` 落进共享库 ——
**服务是更早起的、一直在跑**，它只是改了个注释触发热重载，而 dev 模式 `migrate-at-start=true`，**重载即迁移**。
🚨 **它没有「起服务」这个动作，所以安全闸根本不会被触发**，整条链路上没有任何环节会提醒它。

**没炸是运气不是设计**：Quarkus dev 的热重载是**请求触发**的，它改完注释只跑了独立进程的 `mvnw compile`，
全程没有 HTTP 请求打进那个端口，随即 kill 了服务 ⇒ 重载从未发生。
**只要当时有任何一个请求（包括别人开着的浏览器 tab）打进去，`V423` 就落库了。**

⇒ **判据：危险的不是「起服务」这个动作，是「迁移文件与运行中的服务同时存在」这个状态。**
**前者是事件，后者是状态 —— 用事件去守状态必然漏。**
①b 那个「起服务前比对」守的是事件，所以它**结构上**盖不住这个形态；①a 守的是状态，两个都要。

> 📌 主线 2026-09-07 按此自查过一次：worktree 里躺着 `V423`~`V427` 五个迁移，**零 java 进程**；
> 8081 那个长跑进程 `cwd = /home/joii/project/cpq/cpq-backend`（**主仓**，不是 worktree）⇒ 其 classpath 看不到这些文件，当时安全。

⚠️ **同一条判据在合并日的推论**：主仓 8081 长跑进程会在 master 的迁移目录变化后**热重载即迁移**。
⇒ 合并日**不要依赖热重载**，显式重启 8081，让 Flyway 走冷启动路径
（冷启动的顺序安全性已实证，见下方「合并日操作」；**热重载的顺序未验证**）。

### ①c 🚨 差集必须查 **classpath**，不是源码树（2026-09-07 并发会话真实事故）

```bash
# Flyway 解析的是 classpath，不是 src/。两个都要查，且以 classpath 为准。
W=/home/joii/project/cpq/.claude/worktrees/task-260907-customer-dim
SRC=$W/cpq-backend/src/main/resources/db/migration
CLS=$W/cpq-backend/target/classes/db/migration
echo "--- classpath 有而源码树没有的（= 会被静默应用的幽灵）---"
comm -13 <(ls "$SRC" | sort) <(ls "$CLS" 2>/dev/null | sort)
```

🚨 **并发会话 2026-09-07 09:31:05 的真实事故**：用户按批准删掉了源文件、该会话也复查过
「共享库顶版 422 / V423 记录 0」—— **两项都属实**。但 **`target/classes/db/migration/` 里的编译产物没被清**，
后端代理跑 `mvnw test` 时 Flyway 从残留产物把它落进了共享库，且它**不在 master** ⇒ 孤儿迁移。

⇒ 📌 **判据提炼（对方自己的话，照抄）**：
> **我查的是代理指标（源文件在不在），不是那个东西本身（classpath 里有没有）。**

这与本文档里「数字对上 ≠ 内容对上」「判据落在恒为 1 的维度上是零证据」是**同一族** ——
**今天这一族至少咬了双方五次，每次换一个伪装。**

### 🔴 改号（renumber）流程 —— 本坑的最高危形态

**整体改号时，旧号文件仍留在 `target/classes/db/migration/`，与新号一起被应用**（源码树看起来完全正常）。
且旧号与新号**内容一模一样**，重复执行的症状五花八门（撞唯一键 / 重复插入 / 无害但污染历史）。

⇒ **改号必须按此序，🚫 不许靠「记得清理」**：
```bash
rm -rf "$CLS"                      # ① 先清 classpath 产物
git mv <旧号> <新号>  …            # ② 再改号
(cd cpq-backend && ./mvnw -o -q clean test-compile)   # ③ 重编
comm -13 <(ls "$SRC"|sort) <(ls "$CLS"|sort)          # ④ 差集必须为空
```

### ①b 兜底守卫：起任何**连共享库**的服务前先比对（不能替代 ①）

```bash
# 仓库根目录执行。检查不过【根本不会】走到启动那一支。
M=cpq-backend/src/main/resources/db/migration
DIFF=$(comm -23 \
  <(ls "$M"                              | grep -oE '^V[0-9]+' | sort -u) \
  <(git ls-tree master --name-only "$M/" | grep -oE  'V[0-9]+' | sort -u))
#                                    ↑ 尾斜杠不能省，见下方「两个坑」

if [ -n "$DIFF" ]; then
  echo "🚨 工作区有 master 上没有的迁移：$DIFF"
  echo "   起服务会把它们自动落进所连的库（migrate-at-start=true）"
  echo "   ⇒ 停下报主线，不要连共享库起服务"
else
  (cd cpq-backend && ./mvnw quarkus:dev)
fi
```

🚨 **两个坑，方向相反，必须同时躲开**（本条守卫 2026-09-07 一天内被两次证伪，各由一方发现）：

**坑 1 · 漏放（左边）**：左边写 `git ls-tree HEAD` 会**看不见未跟踪文件**。
`V423`/`V424` 当时正是未跟踪状态，A/B 实测：旧守卫返回**空**（放行启动），新守卫抓到 `V423 V424`。
**Flyway 读的是文件系统，不是 git** —— 一个只看已提交内容的守卫，防不住「还没提交」这个最常见的形态。

**坑 2 · 恒拦（右边）**：`git ls-tree` 对**尾斜杠敏感**，右边省掉 `/` 会静默退化：
```
git ls-tree master --name-only .../db/migration    → 1 行（目录条目本身），grep 命中 0
git ls-tree master --name-only .../db/migration/   → 402 行，grep 命中 401
```
⇒ 右边恒为空集 ⇒ **左边所有迁移全被判成差集**。实测 `DIFF` = **405 条**（不是应有的 4 条）⇒ **每次都拦、服务永远起不来**。
🚨 **假阳性的危害不亚于漏放**：一个「每次都拦」的守卫，第二天就会被人直接注释掉 —— 然后两个坑一起回来。

> 📌 **坑 2 是主线自己引入的，且原因很具体**：临时验证时敲的是带尾斜杠的 `"$M/"`，
> **落盘进本文档的却是不带斜杠的 `"$M"`** —— **验证的与落盘的不是同一段代码**。
> 由 `产品管理客户过滤` 会话按「先看它在正常场景下会不会误伤」跑出来。
> ⇒ **守卫改动后，除了验「该拦的拦住」，还必须验「不该拦的别拦」。**

🔑 **左边必须是 `ls`（文件系统），🚫 不是 `git ls-tree HEAD`** ——
本条 2026-09-07 由主线实证修正：**Flyway 读的是文件系统，不是 git**。
原版守卫用 `git ls-tree HEAD` 作左边，而 `V423`/`V424` 当时是**未跟踪文件**
（从未 commit、从未落库）⇒ **它们会从守卫底下整个溜过去，守卫报「无差异」然后照常启动**。
**一个只看已提交内容的守卫，防不住「还没提交」这个最常见的形态。**

🔑 **为什么把检查和启动写成一条命令，而不是写一条纪律**（由 `产品管理客户过滤` 会话指出，主线采纳）：
**写下来不等于会被执行** —— 尤其当这个失败模式的特征就是**当事人不知道自己触发了它**
（起服务是你知道的动作，落库是你看不见的副作用）。纪律要求你**记得**，而你连自己踩了都不知道。
⇒ 把检查与启动做成**同一个不可分离的动作**，跳过检查需要额外动作才做得到。

⚠️ **必须用 `if/else`，🚫 不要写成 `[ -n "$DIFF" ] && echo "停手"`** ——
本项目实证教训（`RECORD.md` 2026-09-06 提议④）：曾写过 `[ -e "$DST" ] && echo "已存在，停手"`，
**打印了「停手」却继续执行**，覆盖了未读过的文件。**红线守卫必须是控制流，不是打印。**

> 📌 该守卫已由对方做过证伪实验 **4/4**：基线换成 `HEAD~40` 时拦下的差集正好是
> **`V418 V419 V420 V421`** —— 等于用今天真实的事故迁移验证了它抓得住（该实验用的是已提交的迁移，
> 所以**没能暴露上面那个「未跟踪文件」的洞** —— 证伪实验只能证明它抓得住你想到的那一类）。

🚨 **背景**：
```
application.properties:67  quarkus.flyway.migrate-at-start=true
                     :80  %prod.…=false        ← 只有 prod 关；dtz/jh/test/test2 全是 true
```
⇒ **起一次 Quarkus 服务 = 把工作区里目标库没有的迁移自动落进那个库。**

2026-09-07 当天已同型事故 **5 次**（V416~V421）。其中一次是子代理起临时服务自检时无意落的 ——
**它回报「未手工执行任何迁移 SQL」属实，它不知道自己落了库。**

⇒ 🚫 **「落库就推 master」不可执行**（人不知道自己落了库）。**可执行的是「连隔离库」+「起服务之前先比对」**——
因为「起服务」是你**知道自己在做**的动作。

### ② 🔴 DDL 与轴模型**必须同一批合并**，不许中间态

#### 合并日操作（主线 2026-09-07 预先实证，合并那天照做即可，🚫 不要重新推导）

**按序执行，每步都有判据。🚫 不要跳步，尤其不要跳第 1 步和第 2 步。**

| # | 动作 | 判据 / 为什么 |
|---|---|---|
| **1** | 🚨 **先把 worktree 的改动全部提交** | 2026-09-07 实查：**16 个已改文件 + 13 个未跟踪**（含 5 个迁移、6 个测试类），而分支只比 master 多 1 个提交。子代理按纪律不 `commit`，**提交责任隐式落到主线**，而「跑了测试」「看了 diff」「查了分支」三项常规前置检查**都发现不了代码没进 git**。⚠️ `git worktree remove` 会**连未跟踪文件一起删**（本项目实证近 13000 行险些丢失） |
| **2** | 🚨 **合并前广播全部并发会话** | 合并后共享库会有 `customer_no`，而 `DatasetSchemaSelfCheck:116` 是**双向**比对 ⇒ **任何仍在跑旧代码的 worktree，下次启动会撞「多出未声明的列: customer_no」**。广播内容：「重启后端之前先把 master 合进你的 worktree」。用 `ListAgents` 取当时的活跃会话 |
| **2.5** | 🚨 **撞号检查（四个维度，缺一即漏）** | 见下方脚本。非空就停下重编号，**不要合** |
| **3** | 合并到 master | 文件交集先查一遍。⚠️ **已知与 `核价回填` 会话有文本级冲突**（非语义冲突，两组常量互不相干、开关维度不同）：`SheetDef.java`（我加 `CUSTOMER_COLUMNS`，它加 `RECORD_COLUMNS`/`SOURCE_QUOTATION_COLUMN`）· `QuoteRegistry.java`（我传 `customerScoped=true`，它覆写 `quoteRecordEnabled()`）· `DatasetRegistry.java` · `DatasetSchemaSelfCheck.java`。**双方已约定我先合、它后合并由它解冲突**（它还要走亲验，本就在我后面） |
| **4** | 🚨 **显式重启主仓 8081，🚫 不要依赖热重载** | 主仓 8081 是**长跑进程**（`cwd=/home/joii/project/cpq/cpq-backend`），master 迁移目录一变就会**热重载即迁移**。**已实证的是冷启动下 Flyway 先于自检的顺序，热重载的顺序未验证** ⇒ 走已验证的那条路 |
| **5** | 验共享库迁移 | `SELECT version,success FROM flyway_schema_history WHERE version::int >= 423` → **423~427 全部 `success=t`** |
| **6** | 验自检通过 | 启动日志出现 `[dataset] Registry↔DDL 自检通过：N 张表 / M 列`。**没出现 = 自检抛了，服务没起来** |
| **7** | 验业务端点 | `curl --noproxy '*'` 打 `/api/cpq/components` → **401**（不是 500、不是连不上） |
| **8** | 通知阻塞方 | **三个会话都要发**，且必须带下面五样（缺一它们就得自己再查一遍） |

#### 第 8 步的通知内容（`核价回填` 会话明确要求，其余两个同样适用）

| # | 内容 | 为什么它需要 |
|---|---|---|
| 1 | 合进 master 的**提交 hash** | 它 `git merge master` 前核一眼 |
| 2 | **实际**落库的迁移号区间 | 🚨 **给真实值，不给预期值** —— 号今天已经是移动靶（`423~428` → `425~429`，且从 6 个变 5 个），对方明说「不想再按预期值去核」 |
| 3 | 冷启动后 8081 的实际状态 | `/api/cpq/components` → **401** |
| 4 | `flyway_schema_history` 里该区间**逐条 `success` 的原始输出** | 只说「全成功」它没法复核 |
| 5 | `[dataset] Registry↔DDL 自检通过：N 张表 / M 列` 那行启动日志 | **没有这行 = 自检抛了、服务没起来**；它据此判断该等我还是可以合 |

🚨 **广播里必须显式写这条顺序**（三个会话都受影响）：
> **先 `git merge master` 解冲突，然后才重启后端。**
> 反过来会报「多出未声明的列: customer_no」—— 而那是个**看起来像自己实现坏了**的假红，
> 对方会花一整轮去查一个根本不存在的缺陷。

> 📌 **一条不要误设的前提**：`核价回填` 的 `S-7 全库清空` 属 §3.2，需其用户当次批准，**尚未发生**。
> 🚫 不要按「旧单已清空」来排任何验收前提。（本任务的 AC-2 亲验用自造夹具 `MZ-` 前缀，不依赖库干净。）

#### 第 2.5 步的撞号检查脚本（四个维度）

```bash
cd /home/joii/project/cpq
export PGPASSWORD=joii5231
for v in $(git diff master...HEAD --name-only -- cpq-backend/src/main/resources/db/migration/ \
           | grep -oE 'V[0-9]+' | sort -u); do
  m=$(git ls-tree master --name-only cpq-backend/src/main/resources/db/migration/ | grep -c "${v}__")
  d=$(psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -tA \
      -c "SELECT count(*) FROM flyway_schema_history WHERE version='${v#V}'")
  [ "$m" != "0" -o "$d" != "0" ] && echo "🚨 $v 已被占用 (master=$m, db=$d)"
done
# ③ 别人还没提交的工作区（🚨 前两个维度会把这些号报成「空闲」）
for w in /home/joii/project/cpq/.claude/worktrees/*/; do
  ls $w/cpq-backend/src/main/resources/db/migration/ 2>/dev/null | grep -oE '^V[0-9]+' | sort -u \
    | sed "s|^|  $(basename $w): |"
done
# ④ classpath 幽灵（见 §①c）
comm -13 <(ls "$SRC"|sort) <(ls "$CLS" 2>/dev/null|sort)
```

**四个维度各挡一类，🚫 不能互相替代**：

| 维度 | 挡什么 | 实证 |
|---|---|---|
| `master` 文件 | 已合并的占号 | 09:31 事故后 `V423` 就在这里 |
| 共享库 `flyway_schema_history` | 已落库的占号 | 同上 |
| **其它 worktree** | **别人还没提交的占号** | 实测 `task-260907-quote-import` 已占 `V424`，而前两个维度都报「空闲」 |
| **`target/classes`** | **改号后旧号的幽灵** | 见 §①c，09:31 事故的直接根因 |

> 🔑 **一个防护手段可能同时降低另一类风险的可见性**（`产品管理客户过滤` 会话 2026-09-07 归纳）：
> 本任务用隔离库避开了「28 张表加列打挂所有人」，但**隔离库不含共享库的 flyway 历史**
> （它是共享库在 `V422` 时刻的克隆，而对方的 `V423` 是 09:31:05 落的，在克隆之后）
> ⇒ **本地怎么跑都不会撞号，合并那一刻才爆。**
> 这不是说隔离库错了，而是说**它挡不住撞号 —— 撞号只能靠合并前比对上面四个维度**。

> ⚠️ **第 4 步与第 2 步的顺序不能换**：先重启会让共享库立刻有 `customer_no`，此时还没广播的会话一重启就挂。


**✅ 已证实：启动顺序是安全的，合并后不存在「代码要列、库还没列」的窗口。**
判据不是 `DatasetSchemaSelfCheck` 那句自称「早于 StartupEvent，顺序是安全的」的 javadoc
（设计期注释不能当判据），而是**实证**：隔离库克隆自 V422（当时**没有** `customer_no`），
用**要求该列**的分支代码起服务 —— 结果 `flyway_schema_history` 423/424 `success=true`，
且 `pg_stat_activity` 上出现 **5 idle + 1 active** 的连接池
⇒ **Flyway 先跑完，自检才跑，并且通过**。若顺序反了，应用会在自检处抛 `IllegalStateException` 起不来。

**⚠️ 真正的协调成本在别处**：合并 + 有人重启使共享库迁移之后，
**其它会话仍在跑旧代码的 worktree，下次重启会撞 `多出未声明的列: customer_no`**
（`DatasetSchemaSelfCheck:116` 的双向比对，方向反过来）。
⇒ 合并前必须**广播**给全部并发会话：「本次合并后，重启后端之前先把 master 合进你的 worktree」。
合并时点的活跃会话可用 `ListAgents` 取；当天已知 6 个。


**不允许「先加列、轴模型下一轮再改」。** 中间那个状态最危险：列已经在、值也在填，而删除仍按单列轴走 ——
**看起来一切正常，实际每次导入都在删别的客户的数据。**

⇒ 这是**结构上不让那个状态出现**，强于「发现轴还是单列就停下」那种事后检测。

### ③ 夹具前缀用 `T260907C-`

多线并发，前缀撞了的危害不是脏数据是**互删**（两边都按 `LIKE 'T260907%'` 清理，谁先跑谁把对方删了，
**症状是随机挂且极像业务回归**）。已占用：`T260907B-` / `T260907Q-` / `T260907T-` / `T260907M-`。
🚫 清理一律用主键或完整名精确删。

---

## 🔢 迁移改号方案（2026-09-07 事故后与并发会话协调的结果，落地前必读）

**起因**：并发会话的 `V423__task260907_customer_element_price_node.sql` 于 09:31:05 落入共享库并已固化
（checksum 已写入 `flyway_schema_history`，且文件已恢复进 master ⇒ 不可再动）。⇒ **本任务六个迁移整体改号。**

### 最终号段（双方已确认）

| 号 | 归属 |
|---|---|
| `V423` | 并发会话（已固化） |
| `V424` | 并发会话的清理迁移（删第二条 PRICE 边 + 节点，按 `node_key` 删） |
| **`V425` ~ `V429`** | **本任务的五个** |

### 映射（🚫 注意是 5 个不是 6 个）

```
V423 ds_quote_customer_no          → V425     DDL，后面几条都依赖它，必须排最前
V424 func_customer_element_price   → 🗑 删除，不改号
V425 element_price_candidate_ds    → V426     ⚠️ 只保留 candidate_materials 那段
V426 quote_tree_customer_param     → V427
V427 quote_tree_compat_view        → V428
V428 element_compat_view_customer  → V429
```

### 为什么砍掉两个东西（不是为了少两个文件）

- 原 `V424` 整条是 **`A0-5` 已裁掉的「换 PRICE 边」方案**
- 原 `V425` 有**一半**是专门撤销 `V424` 的（改回边指向 / 恢复 `material_no` 键 / 改回 AUX 挂载 / 删节点）

⇒ 这些文件**从没进过 master**，此刻是唯一能干净砍掉的时机。
🔑 **真正的理由是：迁移历史是给后人读的叙事。** 往里塞一对自我抵消的操作，
等于在历史里**埋一个假的决策点** —— 下一个人做考古时会以为那是个真实发生过的选择。

✅ 砍完本任务**一条 `semantic_*` 语句都不剩**，语义图完全归并发会话处置，两边不再交叉。

> 📌 **砍「恢复 `material_no` 键」那段的依据是实查，不是推理**：共享库上那条边的 2 个键
> （`element_code` seq=0 / `material_no` seq=1）**从没被删过** —— 删它的是**本任务自己的 `V424`**，
> 而它从未在共享库执行。**查状态，不要推动作。**

### 🔴 执行四步，🚫 不许靠「记得清理」

```bash
W=/home/joii/project/cpq/.claude/worktrees/task-260907-customer-dim
SRC=$W/cpq-backend/src/main/resources/db/migration
CLS=$W/cpq-backend/target/classes/db/migration

rm -rf "$CLS"                                   # ① 先清 classpath 产物
git mv "$SRC/V423__..." "$SRC/V425__..."  …     # ② 改号（git mv 保留历史）
(cd $W/cpq-backend && ./mvnw -o -q clean test-compile)   # ③ 重编
comm -13 <(ls "$SRC"|sort) <(ls "$CLS"|sort)    # ④ 差集必须为空
```

🚨 **第 ① 步是本方案最高危的一处**：改号后旧号文件仍留在 `target/classes/db/migration/`，
**与新号一起被应用**，而源码树看起来完全正常。且旧号与新号**内容一模一样**
⇒ `ON CONFLICT DO NOTHING` 会吃掉大部分冲突，剩下的表现是**零散、不成规律**的，比孤儿迁移难查得多。

### ⚠️ 隔离库必须重建

`cpq_t260907_custdim` 已应用旧号 423~428，改号后 Flyway 会判定它们是孤儿 ⇒ 启动即挂。
⇒ 改号后**重新从共享库 `pg_dump` 克隆一个新库**（约 1 分钟，296 MB），🚫 不要在旧库上跑 `repair`。

### 时序（🚫 不可提前）

**等并发会话的 `V424` 清理落库并通知**，再改号 → 重建隔离库 → 亲验 AC-2 → 广播 → 合并。
🚫 **不在共享库仍有两条 PRICE 边时合并** —— 两个问题叠在一起后，谁都说不清是谁引起的。

---

## B-1 · 所有 `ds_quote_*` 业务表及其镜像表加 `customer_no`

**服务的 AC**：AC-2 · AC-3

### 列定义（🚫 不要自己定，照抄既有那张）

```
customer_no   character varying(20)   NOT NULL   无默认值
```
出处：`ds_quote_customer_part.customer_no` 实查。取值口径 = `customer.code`。

### 存量回填（用户裁决）

> 原话：「统一给罗克韦尔的客户号，测试数据不计较数据真实，重点是业务逻辑准确」

**统一 `CUST-0001`**（罗克韦尔）。

🔑 **裁决的重点在后半句**：回填值**不承担业务正确性**，它只是让隔离逻辑**能被验证**的载体。
⇒ 🚫 **不要写任何「校验 `customer_no` 值是否业务合理」的逻辑**（如反查料号该属于哪个客户）。要保证的是**机制**。

### 🚫 用动态扫，不要写静态 `ALTER TABLE` 清单

```sql
SELECT table_name FROM information_schema.tables t
WHERE t.table_name LIKE 'ds_quote_%'
  AND NOT EXISTS(SELECT 1 FROM information_schema.columns c
                 WHERE c.table_name=t.table_name AND c.column_name='customer_no')
```
⚠️ **13 张 `ds_quote_*_record` 已带该列**（`核价回填` 会话的 V420 建的）⇒ 静态清单会撞「列已存在」。
📌 参考量级：2026-09-07 实测缺该列 **28 张**（15 主表 + 13 `_history`）。**🚫 不要把这个数字写进代码或 AC。**

### 🔴 加列不是跑个 ALTER 就完了 —— 四条夹击约束

| # | 约束 | 源码 | 不做的后果 |
|---|---|---|---|
| ① | 列集**双向**比对 | `DatasetSchemaSelfCheck.java:112-118` | Registry 不同步声明 ⇒ **后端起不来** |
| ② | 🚨 **但不能声明成 `ColumnDef`** | `DatasetSheetParser.java:72-77` | `persistedColumns()` 每列必须在 Excel 表头出现，而 `customer_no` 来自**选客户下拉不来自 Excel** ⇒ **15 张 sheet 整份拒收** |
| ③ | 出路：**静态系统列** | `SheetDef.java:20-27` + `expectedTableColumns():106-113` | 进自检、不进 `persistedColumns()` ⇒ 正是所需形状 |
| ④ | 写入点要显式加 | `VersionedGroupWriter.java:264-267`（`insertAll`）· `:244-245`（`archive`） | 否则**列建了、值永远 NULL** |

**实现形状**：
1. `SheetDef` 加第四组静态常量（如 `CUSTOMER_COLUMNS`）
2. 🚨 **必须只对报价侧条件生效** —— `SheetDef` **三套数据集共用**，无条件追加会让核价两套自检也要求这列 ⇒ **核价侧当场起不来**
3. `expectedTableColumns()` / `expectedHistoryColumns()` 按报价侧条件追加
4. `insertAll` 与 `archive` 两处显式带上它

> ⚠️ **④ 的失败形态**：列建好了、自检过了、导入也不报错，**只是值恒为 NULL**。
> 届时 AC-2① 会红 —— **红得对，但排查方向极易跑偏到 DDL 上**，真因在两个写入点的列清单里。

---

## B-2 · `SheetDef.axisColumn` 由单列改复合

**服务的 AC**：AC-2 · AC-4

```java
SheetDef.java:30   public final String axisColumn;   // ← 单列 String，要改
```

🚨 **判据要落在签名层，不要落在点名清单上**：让**新增调用方编译期就必须提供** `customer_no`。

> 📌 **为什么**：主线「找调用方」这件事**连错三次，错法各不相同** ——
> ① 按类名 grep 漏查使用引用（DI 项目里定义引用 ≠ 使用引用）
> ② 漏查未来调用方（穷举当下 ≠ 穷举未来）
> ③ 没查「引用」是不是「调用」（把锁键工具当成写入方）
> **⇒ 清单这个形式本身不可靠。**

---

## B-3 · `VersionedGroupWriter` 的整组删除按复合轴执行

**服务的 AC**：AC-2（本任务**最重要**的一条）

```java
VersionedGroupWriter.java:206
  "DELETE FROM " + table + " WHERE " + axisCol + " IN (:axes)"
```

🚨 **失败形态是静默删数据，不是撞键报错**（主线曾判反，已实测更正）：
```
ds_quote_* 的 UNIQUE 约束：pg_indexes UNIQUE 非 pkey → 3 条，全在【免版本表】
                          （🚫 别用 pg_constraint contype='u' 查，它返 0——那 3 条是 CREATE UNIQUE INDEX 建的）
13 张带版本表：只有 PRIMARY KEY (id)，无任何业务唯一索引
```
⇒ 隔离**根本不靠 DB 约束**，靠整组删除 + 重插。
**只加列不扩轴 ⇒ 客户 A 导入料号 X 会把客户 B 的料号 X 整组删掉，不报错、不撞键、不留痕。**

---

## B-4 · 每一条写入路径都要能提供 `customer_no`

**服务的 AC**：AC-4

| 调用方 | `customerNo` 引用数 | 客户来源 |
|---|---|---|
| `DatasetImportService` | 0 | 导入时选客户（`报价导入切ds新表` 的 `QuotationImportService` 提供） |
| `DatasetMaintenanceService` | 0 | 🚚 **前端选择器已移出本任务**（`task-260907-产品管理客户过滤` 承接）。本任务只需**让它能接收并透传** |
| `SelDsQuoteWriter` | 5 | 选配链路 |
| `ConfigureProductService` | 55 | 选配主链路，**间接**调用（经 `SelDsQuoteWriter`）。⚠️ `:1205-1265` 有**明确标注的豁免点**至今仍写 V6 表，**不是纯 ds_ 路径**，算影响面时别按「已全切新表」这个前提 |
| （未来）`核价回填` 的 S-4 | — | 报价单客户（`quotation.customer_id → customer_no`） |

### 🔴 「直接调用写入器的有几个」—— 主线在这条上连错四次，最终结论如下

**判据不是类名 grep，是「有没有对写入器*实例*的方法调用」**（后端代理 2026-09-07 独立复核）：

```
DatasetImportService:143        versionedWriter.writeGroups(...)
DatasetMaintenanceService:708   writer.writeGroup(...)
SelDsQuoteWriter:229            versionedWriter.writeGroup(...)
SelDsQuoteWriter:314            versionedWriter.writeGroup(...)
```
⇒ **直接调用方 = 3 个类 / 4 个调用点。**

**四次错的形态各不相同，所以值得逐条留碑**：

| # | 错法 | 为什么 grep 骗过了我 |
|---|---|---|
| ① | 类名 grep 漏了 `ConfigureProductService` | 把**使用点**和**定义点**混为一谈 |
| ② | 漏了并发会话的未来调用方（`核价回填` S-4） | 只扫了当前代码，没扫在途任务 |
| ③ | 把 `DatasetGroupLock` 算成调用方 | 它是 **36 行的锁键工具**，反过来是 `VersionedGroupWriter:140` 调它 |
| ④ | 把 `ConfigureProductService` 算成**直接**调用方 | 它 `@Inject VersionedV6Writer`（`:79`，写 **V6** 表）+ `@Inject SelDsQuoteWriter`（`:83`）。它对 `VersionedGroupWriter` 的引用数 **= 0**，是**间接**调用方 |

🚫 **`DatasetCustomerPartService` 已从表中删除** —— 它**根本不是写入方**：
类 javadoc 自述「本类不含任何 INSERT/UPDATE/DELETE，不引用 `VersionedGroupWriter`」，grep 证实无 writer 注入 ⇒ **只读**。
先前把它列为「自带 `customerNo` 的调用方」，是把「文件里 `customerNo` 出现 22 次」当成了「它是写入方」。

⚠️ **但 ④ 不改变 `B-4` 对 `ConfigureProductService` 的要求** —— 它仍必须能提供并透传 `customerNo`，
只是「直接调 writer」这个描述是错的。**「谁直接调」与「谁要负责传参」是两个问题，别用一个答案回答两个。**

> 📌 **静态常量引用不算调用方**：`DatasetImportService:126`、`SelDsQuoteWriter:66/67` 命中的是
> `VersionedGroupWriter.SOURCE_*` 常量，实际调的是 `plainWriter.upsert`。测试源码 6 处命中全是注释/DisplayName 文本。

---

## B-6 · `uq_ds_quote_material` 扩成复合

**服务的 AC**：AC-2

```
现状：uq_ds_quote_material  USING btree (material_no)          ← 单列
目标：                       (customer_no, material_no)
```
⚠️ 另两条唯一索引：`uq_ds_quote_customer_part(customer_no, customer_product_no)` **已含客户，不动**；
`uq_ds_quote_plating_scheme(scheme_no, scheme_version, item_seq)` 与客户无关，**不动**。

---

## B-7 · `costing_bom_tree_config` 的 QUOTE 递归换读新表 + 接客户隔离

**服务的 AC**：AC-1

现状（实测）：
```
usage=QUOTE    读 V6 裸表 material_bom_item=t  读兼容视图=f  读新表=f  带 versionFilter=f
usage=COSTING  同上                                                    带 versionFilter=t
```

### 🚦 B-7a · 修 `_cust`（2026-09-07 用户裁决：**修，只改 QUOTE 那条**）

⚠️ **本节推翻了本文件先前写的「`_cust` 是非本任务的架构问题，🚫 不要顺手改它，那是扩范围」。**
那条边界是主线立项时写的，**写错了**：`_cust` 恰恰就是递归的客户隔离本身，
不改则 AC-1 的断言①②③**在任何实现下都不可能成立**，B-7 的「接客户隔离」也是空话。
子代理已用自造夹具实证：同一料号挂两客户时，两张不同客户的报价单**都**拿到 `CUST-0001` 的子树
（没有串客户，但也没有按报价单的客户选）。

模板现状（`usage='QUOTE' AND is_active`，实查）：
```sql
WITH RECURSIVE bom AS (
  SELECT ...,
    (SELECT bc.customer_no FROM material_bom_item bc
      WHERE bc.material_no=p AND bc.system_type='QUOTE' AND bc.is_current
      ORDER BY bc.customer_no LIMIT 1) AS _cust      -- ← 取 customer_no 最小的，与本单客户无关
  FROM unnest(:production_part_nos) AS p
  UNION ALL
  SELECT ..., b._cust
  FROM material_bom_item ch JOIN bom b ON ch.material_no=b.material_no AND ch.customer_no=b._cust
) CYCLE material_no SET is_cyc USING cyc_path
```

**四个触点（主线已数清，实现时逐条勾）**：

| # | 触点 | 做什么 |
|---|---|---|
| ① | `costing_bom_tree_config` 的 **QUOTE** 那条 `sql_template` | 把那个相关子查询整体换成绑定参数 `:customerCode`（迁移改数据，不是改代码） |
| ② | `BomTreeRenderService.queryRecursive`（`:610`） | 它自己做具名参数→`?` 的 mask+有序替换（现支持 `:production_part_nos` / `:pq` / `:__vfPart` / `:__vfVer`），**要把 `:customerCode` 加进这套协议并绑值** |
| ③ | 客户码从哪来 | **不必新查** —— `BomTreeRenderService:286~293` 已解出 `ctxCustomerId`（task-0729 真根因修复时加的，整单只查一次）。UUID→code 的解析有现成先例：`SqlViewExecutor:418~438` 的 `customerCodeCache` / `queryCustomerCode` |
| ④ | `CostingTreeSqlValidator`（`:46` / `:55`） | 探针只把 `:production_part_nos` 替成 `ARRAY[]::text[]`，**新模板里的 `:customerCode` 不加桩会让校验直接失败**（保存树配置时报错）。加桩即可 |

🚫 **`usage='COSTING'` 那条一个字不动** —— 它的客户语义是 `customer_no IN (客户, '_GLOBAL_')`，
与报价侧不同轴，带 `versionFilter`，动它需要另验核价树渲染无回归。**本期不碰**。

✅ **AC-1 原文保留不动**，本期真正达成。

### 🚦 B-7c · 落地方式改为 **追加 V427**（2026-09-07 主线定，B-7b 的执行细则）

⚠️ **B-7b 原写「换表并进 V426 的同一条 UPDATE」，现在做不到了** —— `V426` 已应用到隔离库
（`flyway_schema_history` 426 `success=t`），改写它 = checksum 失配，隔离库下次启动直接挂。
这与 `V425` 走追加式是同一条理由。

**V427 一条 `UPDATE` 写入完整的最终模板**（`_cust` 参数化 + 4 处表名一起），不是在 V426 上打补丁：
- 语义上仍是「一次 UPDATE 带两个改动」，**并且顺带解决了「谁后落谁赢」** —— 两个改动由一个人
  写进同一条语句，不存在静默互相覆盖
- 全新库跑 `V426→V427`、已应用库只跑 `V427`，**两条路径收敛同态**

**V427 的三件事**（与 B-7b 一致）：
1. QUOTE 模板 4 处 `material_bom_item` → `v_compat_material_bom_item`（连同 `_cust` 一起写全）
2. 兼容视图 ds_ 分支按下面的 **(c) 形态**改
3. 回填口径改：能从 `cust_scope` 推出客户的按推出来的填，推不出的才落 `CUST-0001`

#### (c) 形态：`EXISTS` 只过滤，`customer_no` 取权威列（并发会话提出，主线验证后采纳）

```sql
-- 现状（扇出源）：JOIN 既过滤又提供 customer_no
FROM ds_quote_material_bom b
  JOIN cust_scope cs ON cs.material_no::text = b.material_no::text
-- (c)：EXISTS 只过滤；输出列的 cs.customer_no 换成 b.customer_no
FROM ds_quote_material_bom b
WHERE EXISTS (SELECT 1 FROM cust_scope cs WHERE cs.material_no::text = b.material_no::text)
```
⚠️ **视图原有的 `NOT EXISTS` 去重子句必须保留**，且其中的客户比对也要换成 `b.customer_no`。

**为什么 (c) 优于「给 JOIN 补 `AND cs.customer_no = b.customer_no`」**：
后者是**掉行**（把不一致的行剔掉，实测 12 行 → **7 行**，掉 42%）；
(c) 让**扇出结构上消失**（不是把它压回 1），**零掉行**。

#### ✅ 判据：**11 行逐行比对**（🚫 不要跑 89 段 SQL 回归）

消费 `v_compat_material_bom_item` 的存量组件 SQL 有 **89 段**（并发会话实查；主线先前转述的 135 是二手数字，已更正）。
但**它们按视图名文本引用** ⇒ 改视图体不改任何一段 SQL 文本 ⇒ 「编译产物逐字节相同」**是结构上保证的恒真判据，零证据**。
（这条是主线提的，被并发会话当场证伪 —— 与本文档里那条「恒为 1 的维度」是同型错误，同一天犯了两次。）

**真正的影响面有上界**：视图 11166 行 − V6 裸表 11155 行 = **ds_ 分支净增 11 行**，
第 2 条只可能动这 11 行的客户号，动不到另外 11155 行。⇒ **逐行核这 11 行即可**：

| 期望改后客户号 | 父件 | 子件 | seq |
|---|---|---|---|
| `C1` | TEST-Q13-CODE | TEST-Q13-CODE | 1 |
| `CUST-0001` | 0526-2609000004 | 00006 | 1 |
| `CUST-0001` | 0526-2609000004 | 00230 | 2 |
| `CUST-0001` | 0526-2609000005 | 0526-2609000004 | 1 |
| `CUST-0001` | 0526-2609000005 | TEST-Q13-CODE | 2 |
| `CUST-0001` | 0526-2609000005 | 0526-2609000004 | 3 |
| `CUST-0001` | 0526-2609000005 | TEST-Q13-CODE | 4 |
| `CUST-0004` | S0001 | S0002 | 1 |
| `CUST-0004` | S0001 | S0003 | 2 |
| `CUST-0004` | S0002 | 992 | 1 |
| `CUST-0004` | T260907-M1 | 00006 | 1 |

**这张表 = 改动前的现状，也 = 期望的改后状态**（主线已算过：回填按 `cust_scope` 推之后，
`b.customer_no` 恰好等于视图现在合成的值 ⇒ **11 行零变化**）。
⇒ 落完重跑，**11 行都在 + 客户号逐行一致 = 零影响**；有出入就把那几行拎出来报主线。
⚠️ 数字会随共享库并发写而漂，**判据是「逐行一致」不是「11 这个数」**。

#### ✅ 一个自动闭合的风险（后端代理复核结论，主线采纳）

`QuotePendingRewriter.WHITELIST_TABLES` 实为 **10 项**（8 张物理版本化表 + `v_compat_material_bom_item`
/ `v_compat_element_bom_item` 两张兼容视图），**不是 8 项**。且：
- `ds_quote_material_bom` / `ds_quote_element_bom` **既无 `pending_quotation_id` 也无 `is_current`**，
  而改写器 `:365`/`:371-372` **无条件**生成 `t.pending_quotation_id = :pq` ⇒ 把 ds_ 裸表加进白名单会直接
  `column does not exist` 运行时报错。**「加白名单」结构上做不到，不是该不该的问题。**
- **换成 `v_compat_material_bom_item` 时，该名字已在白名单** ⇒ 改写命中，V6 那半边照常带出 pending 行
  ⇒ **pending 可见性自动保住，无需动白名单。**

⇒ 先前记的「11070 行 pending 不可见」这个风险，**只在「换 ds_ 裸表」路线下成立；走兼容视图路线自动闭合**。
🚫 仍然不要动白名单（并发会话的 AC-16 判据依赖它不含 ds_ 表）。

---

### 🚦 B-7b · 换兼容视图 + 客户权威归位（2026-09-07 用户裁决 A0-6）

**三条判据主线已独立复核（在隔离库上亲跑，🚫 不是采信并发会话的汇报）**：

| 判据 | 实测 |
|---|---|
| 严格超集 | 11166 vs 11155；`material_bom_item EXCEPT 视图` = **0 行** |
| 存量零回归 | 全量 QUOTE 料号跑闭包，**34 行 vs 34 行、diff 完全为空** |
| **ds_ 能否展开** | 老表口径 **8 行全是根行（展不开）**；视图口径 **25 行、含 17 条非根行** |
| 性能 | 0.184ms → 3.85ms（隔离库 TIMING OFF 取 3 次最好），不构成否决 |

**要做三件事，且必须在同一批**：

1. **换表**：QUOTE 模板 4 处 `material_bom_item` → `v_compat_material_bom_item`，
   **并进 `V426` 的同一条 UPDATE**（并发会话也改这一行 `sql_template`，分开落必然静默互相覆盖）
2. **兼容视图 ds_ 分支改用 `b.customer_no`**，不再 `JOIN cust_scope` 合成客户号
3. **回填口径改**：能从 `cust_scope` 推出客户的按推出来的填，推不出的才落 `CUST-0001`
   （🚫 **追加新迁移**，不改写已应用的 `V423`）

🚨 **为什么 2 和 3 必须一起做**（缺一即坏）：
- 只做 2：表里 70/71 行都是 `CUST-0001`（V423 统一回填）⇒ **`CUST-0004` 的树直接空掉**（19 个映射只剩 1）
- 只做 3：视图仍靠 `cust_scope` 合成 ⇒ 隔离照样被抹平

🚨 **缺陷本身**：视图 ds_ 分支是
`FROM ds_quote_material_bom b JOIN cust_scope cs ON cs.material_no = b.material_no` —— **JOIN 无客户条件**
⇒ 料号挂两客户时每行发两遍，**与该行自己的 `customer_no` 无关** ⇒ AC-1 要验的隔离被反向抹平。

⚠️ **写 AC-1 夹具前必读这条**：现网 `max(count DISTINCT customer_no) per material = 1` ⇒ **扇出恒为 1**
🚨 **但这句话必须限定维度**（2026-09-07 实证更正）：它在 **`ds_quote_material_bom` 上成立**，
在 **`cust_scope` 上不成立** —— `cust_scope` 是两支 UNION（客户料号表直给 + 父件的客户传给子件），
实测 `TEST-Q13-CODE` 就有 **2 个**候选客户（`C1` + `CUST-0001`）。
**这正是 V427 落地后 11 行变 10 行的成因** —— 不限定维度就会以为哪里做错了而乱改。
⇒ **任何用现网数据的等价性验证都必然通过**（主线那个「34 行 diff 为空」也是）。
它证明的是「**单客户下**等价」。**判据落在一个恒为 1 的维度上时，它不是弱证据，是零证据。**
⇒ **夹具必须自己造出扇出 ≥ 2 的场景**：同料号、两个 `customer_no`、子件不同。
🚫 **不能指望回填制造出来** —— 回填只能给每条现有行赋一个客户，`ds_quote_material` 一个料号就一行，
**拆不成两行**。

⚠️ **改共享视图要先跟并发会话对齐**：`v_compat_material_bom_item` 被 `task-260903` 的
**135 段组件 SQL** 消费。主线已把裁决与影响面同步给 `报价数据导入切ds新表` 会话，
**拿到对方确认前不要落**。

---

### 💡 一条待验证的近路（🚫 不是结论，先做三条排除）

`v_compat_material_bom_item` 对 `ds_` 独有的 **23 个** BOM 料号 **23/23 全覆盖**（主线实测）。
`task-260903` 已为 **135 段组件 SQL** 做过同一替换并验过等价性。

🚨 **三条必须先排除的风险**：
1. 该配置**报价 + 核价共用**，核价那条带 `versionFilter` 而兼容视图新表侧**无版本列** ⇒ 大概率不能照搬
   （⚠️ 有了 B-7a 后这条更要留意：**只改 QUOTE 那条**，两条已经分家，别再顺手对齐）
2. `task-260903` 那次替换的**已知行为变更是行序会变**，而**递归对行序更敏感**（树的兄弟节点顺序）⇒ 必须单独验
3. 兼容视图新表侧 `pending_quotation_id` 恒 NULL、`is_current` 恒 true ⇒ 若递归里有 pending/版本谓词，**语义会变**

🚨 **另一个已发现的风险（子代理报，未处置）**：`QuotePendingRewriter.WHITELIST_TABLES`（`:53`，8 张版本化表）
**不含 `ds_quote_material_bom`** ⇒ 递归若直接改读新表，本单 pending 影子行将不可见（实测 11070 行）。
换表前先说清怎么办，🚫 不许默默换过去。

## 自检（回报里贴命令原始输出）

- 起服务前跑**迁移号差集**（见开工前必读①），非空先回报
- `mvnw -o clean test-compile` 通过
- 冷启动无 ERROR；`DatasetSchemaSelfCheck` 启动期自检通过（**报价侧要求该列、核价两套不要求**）
- 业务端点 200/401（**不要 500**）；迁移 `success = true`
- **N+1 自检**：说清每个改动路径的 SQL 条数是否与 N 无关
- 🚨 **核价两套回归**：同一份夹具在导入与维护端保存两条路上，改动前后**逐行 md5 相同**（AC-6）

## 🚫 红线

- 共享 dev 库**不许跑清库型测试**；夹具用完按主键删净，给「残留=0」证据
- 🚫 不对共享库跑 `flyway repair`；🚫 不用 `-Dquarkus.flyway.validate-on-migrate=false` 长期绕过
- 🚫 不 `pkill -f "quarkus:dev"`（会误伤他人进程），按端口精确定位 PID
- 🚫 绝不 cd 回主仓跑 `mvnw`；worktree 里跑 `quarkus:dev` 与 `mvnw` **共用 `target/`** ⇒ 用隔离副本，拷完 `diff -rq`

---

## B-8 · 🆕 元素价格策略闭环：给 `f_material_element_price` 的候选料号集补 `ds_` 支

**服务的 AC**：AC-8（用户 2026-09-07 裁决「加上，让元素价格策略闭环」）

### 🚦 2026-09-07 方案改判：**不换 PRICE 边，改补候选集**（用户裁决）

⚠️ **本节整体推翻了先前的「加 `FUNC_CUSTOMER_ELEMENT_PRICE` 节点 + 改指 PRICE 边」方案。**

🚦 **落地方式 = 新增 `V425`，🚫 不要改写 `V424`**（主线 2026-09-07 定，理由如下）：
- `V424` 已应用到隔离库 `cpq_t260907_custdim`（`flyway_schema_history` 423/424 均 `success=true`，
  实测已建出 `FUNC_CUSTOMER_ELEMENT_PRICE` 节点并把 `PRICE` 边改指过去）
- 改写它 = checksum 失配，隔离库下次启动直接挂；且违反项目规则
  「**schema 变更一律新建迁移脚本，不改历史脚本**」（`backend.md §4`，改名/移动也被 hook 拦）
- 走追加式的**决定性好处**：全新库（master）跑 `V424→V425`、已应用库（隔离库）只跑 `V425`，
  **两条路径收敛到同一状态** —— 这是改写做不到的

**`V425` 要做三件事**（缺一不可，前两件是撤销 `V424`）：
1. 把 `PRICE` 边**改回**指向 `FUNC_ELEMENT_PRICE`，并把 `QUOTE/材质元素` 上的 AUX 挂载一并改回
2. 删除 `FUNC_CUSTOMER_ELEMENT_PRICE` 节点及其 3 个 `semantic_node_column`
   （⚠️ `DELETE` 必须带精确 `WHERE node_key=...`，并在回报里给出**删除前后行数**）
3. 给 `f_material_element_price` 的 `candidate_materials` 补 `ds_` 支（下面「做什么」一节）

✅ **收敛自证**：`V425` 跑完后，隔离库里
`SELECT count(*) FROM semantic_node WHERE node_key='FUNC_CUSTOMER_ELEMENT_PRICE'` 必须 = **0**，
且 `QUOTE/材质元素` 的 `groupKind='PRICE'` 组数必须 = **1**、`groupKey` 回到 `FUNC_ELEMENT_PRICE`。

原因是主线实测发现原方案有**真回归**：

```
老函数 f_material_element_price(customer, date) 是 UNION 的两支：
  versioned : material_price_version_ref → element_price_version_item   ← 按【料号】钉的版本价
  realtime  : candidate_materials CROSS JOIN f_customer_element_price   ← 客户级实时价，纯扇出
```
⇒ 改指 `f_customer_element_price` 等于**丢掉 versioned 分支**。

🚨 **先前那句「实测 `material_price_version_ref` 全库仅 4 行，`CUST-0004` 为 0 ⇒ 现网零损失」是错的** ——
它**只抽了 CUST-0004 一个客户，就下了全网的全称结论**。主线改用全客户扫后实测：

```sql
SELECT c.code, (SELECT count(*) FROM (
    SELECT element_code FROM f_material_element_price(c.code, CURRENT_DATE)
    GROUP BY element_code HAVING count(DISTINCT unit_price) > 1) z) AS 逐料号有价差的元素数
FROM customer c;
→ CUST-0002 = 1 · CUST-0729-QA = 1 · 其余全 0
```
`CUST-0002` 有 1 个料号钉了 `Ag = 3500`，客户级实时价是 `3000`
⇒ 换边会让它**静默从 3500 掉到 3000**，且 **21 个存量视图**（实查 `component_sql_view` 引用
`FUNC_ELEMENT_PRICE`，全部是 QUOTE/材质元素）**全部**走这条路。

### ✅ 真正的缺口在别处（实查）

```sql
-- f_material_element_price 三参重载里的 candidate_materials：只读 V6 两张表
SELECT material_no FROM material_bom_item  WHERE customer_no IN (p_customer_no,'_GLOBAL_') AND (...)
UNION
SELECT material_no FROM element_bom_item   WHERE customer_no IN (p_customer_no,'_GLOBAL_') AND (...)
```
⇒ `ds_` 独有的料号不在候选集里，`realtime` 分支的 `CROSS JOIN` 就带不出它们，**恒 0 行**。

**ds_ 独有料号实测 = 8 个**（⚠️ 只作记账不进判据；且注意 `UNION`/`EXCEPT` 同级左结合，必须加括号，
主线首次算成 1872 就是漏了括号）：
```sql
SELECT count(*) FROM (
  (SELECT material_no FROM ds_quote_material_bom UNION SELECT material_no FROM ds_quote_element_bom)
  EXCEPT
  (SELECT material_no FROM material_bom_item UNION SELECT material_no FROM element_bom_item)) z;
```

### 做什么

给三参重载 `f_material_element_price(text, date, uuid)` 的 `candidate_materials` **补两条 UNION 支**，
读 `ds_quote_material_bom` / `ds_quote_element_bom`，按 **B-1 新加的 `customer_no`** 过滤。

**这个方案的好处（逐条都要在回报里自证）**：
- ✅ versioned（钉版本价）分支**原样保留** —— `CUST-0002` 的 `Ag=3500` 不变
- ✅ 语义图**一条边都不动** ⇒ 21 个存量视图零影响；`FUNC_ELEMENT_PRICE` 节点、PRICE 边、AUX 挂载全不动
- ✅ QUOTE/材质元素 的 **PRICE 组数恒为 1**，不重现 `取数配置器补齐 AC-30`
- ✅ `Sec34PriceStrategyTest` 那 4 条断言**不会红**（它们钉的正是现有边与节点）
- ✅ 是**纯加法**：只往候选集里加料号 ⇒ 已有料号的返回值逐行不变

### 自证要求（🚫 不许只说「加了」）

1. **A/B 值中性证据**：用**多重集双向差集 `EXCEPT ALL`** 逐客户对拍改动前后的函数输出，
   判据是「**只在旧**」逐客户全为 **0**（纯加法：零丢失、零改值）。

   🚨 **🚫 不要用 `md5(string_agg(..., ',' ORDER BY 1,2))` —— 那是主线写错的坏判据**（2026-09-07 实证）：
   聚合函数内 `ORDER BY` 里的 `1,2` 是**常量、不是列序号**（序号语义只在查询级 `ORDER BY` 成立）
   ⇒ 排序键恒定 ⇒ 输出序 = 执行计划顺序。给 `candidate_materials` 加 UNION 支会改变计划，
   于是**行数不变、md5 却变**，把纯行序变化误报成真回归（实测 CUST-0002 / CUST-0004 各报一次假阳性）。
   非要用 md5 的话写成 `ORDER BY s`（对拼好的整串排序）。
2. **闭环证据**：那 8 个 ds_ 独有料号中，**至少 1 个**在改动后能取到非空单价，贴出料号 + 元素 + 值
3. ⚠️ **数据依赖三层**（缺任一层该元素整行不出现，不是返 0）：
   `element` 表 ACTIVE + 该客户有 `element_price_strategy` + `element_daily_price` 有行情。
   `CUST-0004` 下实际能算出价的只有 5 个元素：`Ag 3000 / Cu 101.14 / Ni 105 / Zn 24.17 / 白银 12345`（全 CNY/kg）
   ⇒ **验收挑这 5 个里的**，否则会拿到「空但不是 bug」的结果

### 🚫 红线

- 🚫 **不新增 `FUNC_CUSTOMER_ELEMENT_PRICE` 语义节点**，🚫 不动任何 `semantic_edge`
- 🚫 不改 `f_material_element_price` 的**签名**与**返回列**（21 个存量视图按现有列名取数）
- 🚫 不改 `f_customer_element_price` 本身（它是被 `realtime` 分支调用的下游）
- ⚠️ `candidate_materials` 现有两支带 `is_current = true OR pending_quotation_id = :pq` 的**双半边**语义
  （`repair-260830`：一半管正式行、一半管本单 pending 影子行）。
  新加的 ds_ 支要说清**它对应的是哪半边** —— ds_ 表若无 `pending_quotation_id`，就直说没有、并说明后果
