# 后端任务分解 · task-260902

> 认领人：`cpq-backend` 子代理。契约见 `api.md`，验收标准见 `需求文档.md §3`。
> 🚫 **红线**：本任务全程**不新增迁移、不改 schema、不 DROP 任何对象**。导出端点**只读**，导入端点**只 INSERT**。
> 撞到需要改 schema 的情形 → **停下报主线**，不要自己加迁移（`CLAUDE.md §3.2`）。

---

## B-1 · 材质导出端点 服务的 AC：AC-1、AC-3、AC-4、AC-5、AC-6、AC-7、AC-8、AC-19、**AC-20**、AC-23

| 项 | 内容 |
|---|---|
| 改动文件 | `configure/resource/MaterialRecipeResource.java`（加端点）<br>`configure/service/MaterialRecipeExportService.java`（**新建**） |
| 端点 | `GET /material-recipes/export`，见 `api.md B-1` |
| 权限 | 方法级 `@RoleAllowed({"SYSTEM_ADMIN"})` —— 类级放开了 4 个角色，靠方法级收紧 |

**三个必须做对的点**（做错任何一个，「可回导」就不成立，功能等于白做）：

1. 🚨 **含量 ÷ 100**。库 `default_pct=84` ⇒ 文件写 `0.84`。导入端 `pctInRange(v, BigDecimal.ONE)` 只收 `(0,1]`。
   用 `BigDecimal.divide(new BigDecimal("100"), 12, RoundingMode.HALF_UP).stripTrailingZeros()`；
   ⚠️ `stripTrailingZeros()` 对 `100` 会得到 `1E+2`，写进 Excel 前必须 `.toPlainString()` 或写成 double。
2. 🚨 **前 4 列的表头文字与顺序 = `MaterialRecipeImportService.HEADER`**。
   ✅ **直接引用那个常量，不要重新写一遍字符串字面量** —— 抄一遍就等于埋了一个「将来改一处忘另一处」的雷。
3. 🚨 **只读列必须从第 5 列开始**。`validateHeader` 按位置比对前 4 列，插在前面 → 400。

**AC-19 / AC-20 是这个端点的真正验收**（不是"能下载出文件"）：
- **AC-19**：导出的文件原样回导 ⇒ **零新增**（既有导入的「只增不改」语义生效，说明导出的每一行都被认成了"已存在"）。
- **AC-20**：在导出文件里加一组含量再回导 ⇒ **恰好 +1 组配置**（说明格式被完整识别，且没有误伤已有配置）。
⇒ 自测时**必须真跑一遍这两条回环**，🚫 不许只验证"文件能生成"就报完成。
这两条一旦不过，多半是含量没 ÷100（AC-5）或表头位置错了（AC-4）。

**取数**（🚫 N+1：整个导出 SQL 条数与材质数无关）

```sql
SELECT r.symbol, c.seq, e.element_code, e.default_pct,
       r.code, c.config_no, r.status, r.recipe_type
  FROM material_recipe_element e
  JOIN material_recipe_config  c ON c.id = e.config_id AND c.status = 'ACTIVE'
  JOIN material_recipe         r ON r.id = c.recipe_id
 WHERE (:keyword    IS NULL OR <复用 list() 的 keyword 条件>)
   AND (:recipeType IS NULL OR r.recipe_type = :recipeType)
   AND (:status IS NULL
        OR (:status = 'ACTIVE'   AND r.status = 'ACTIVE')
        OR (:status = 'INACTIVE' AND (r.status IS NULL OR r.status <> 'ACTIVE')))
 ORDER BY r.symbol, c.seq, e.sort_order
```

⚠️ **`status` 的口径不能想当然**：前端 `isActive()` 的定义是「仅 `'ACTIVE'` 算启用，其余含 `undefined` 都算停用」（`MaterialRecipeManagement.tsx:29-31`）。
后端若写成 `r.status = 'INACTIVE'`，`status IS NULL` 的行就会两边都查不到 —— 页面上看得见、导出里没有。

⚠️ **`keyword` 必须复用 `MaterialRecipeService.list()` 里的同一段匹配条件**（抽成共用方法），🚫 不许照着描述重写一套 SQL。
两套 keyword 实现＝两套结果，用户会看到「页面 12 条、导出 9 条」。

---

## B-2 · 工序导出端点 服务的 AC：AC-9、AC-10、AC-11、AC-23

| 项 | 内容 |
|---|---|
| 改动文件 | `basicdata/v6/resource/ProcessMasterResource.java`<br>`basicdata/v6/service/ProcessMasterExportService.java`（**新建**） |
| 端点 | `GET /v6/process-master/export`，见 `api.md B-2` |

- 列名**直接引用 `ProcessMasterImportService.COL_*` 常量**（它们已是 `private static final`，改成 `static final` 包可见或提到共用常量类；**不要复制字面量**）。
- `是否外协` 写 `是`/`否`；`默认不良率` 写原始小数（`0.01`）。
- 复用 `ProcessMasterService.list()` 的筛选条件，只是不传 page/size。

---

## B-3 · 用户导出端点 服务的 AC：AC-12、AC-13、AC-23

| 项 | 内容 |
|---|---|
| 改动文件 | `system/resource/UserResource.java`<br>`system/service/UserExportImportService.java`（**新建**，与 B-4/B-5 同一个类） |

- 角色写**中文标签**；状态写 `启用`/`停用`；创建时间 `yyyy-MM-dd HH:mm:ss`。
- 区域/部门名称：**先一次性把用到的 region/department 查成 `Map<UUID,String>` 再回填**，🚫 逐行查。
- 🚫 导出不含 `id`、不含密码字段。

---

## B-4 · 用户导入模板 服务的 AC：AC-14

- 单 sheet（名 `用户`），6 列，1 行示例数据，`角色`列表头挂批注列出 4 个合法值。
- 参照 `MaterialRecipeImportService.generateTemplate()` 的批注写法。

---

## B-5 · 用户导入 服务的 AC：AC-15、AC-16、AC-17、AC-18、AC-21、AC-24、AC-25、AC-26

| 项 | 内容 |
|---|---|
| 改动文件 | `UserResource.java` + `UserExportImportService.java` + `system/dto/UserImportReportDTO.java`（**新建**） |
| 端点 | `POST /users/import`，见 `api.md B-5` |

**必须做对的点**：

1. 🚨 **密码生成与哈希复用现有创建用户的那一条路径**（`UserService` 里产出 `initialPassword` 的地方），
   🚫 不许新写一套生成器 —— 两套实现意味着两套强度和两套哈希，且以后改密码策略只会改到一边。
2. 🚨 **`initialPassword` 不写日志**。含密码的对象不许进 `Log.info/debug`，异常信息里也不许带。
3. **只 INSERT**。用户名已存在 ⇒ 整行跳过，🚫 不许 UPDATE 任何既有用户的任何字段（AC-16 会逐字段比对）。
4. **部分成功**：某行失败不影响其它行。🚫 不要把整个导入包在一个"全成功才提交"的事务里。
5. **区域/部门匹配不上 ⇒ 软提示，行照常创建**（AC-18）。两表现在都是 0 条，做成硬校验 = 功能不可用。
6. **文件内重复**：同一 `username` 出现多次 ⇒ 取首行，其余跳过并注明（AC-25）。
7. 🚫 **N+1**：一次批量查重（`username in (?)`），批量插入。SQL 条数与行数无关。

---

## 后端自检（`backend.md`，缺一不可，写进汇报）

```bash
cd cpq-backend
./mvnw -q compile                                  # 期望 BUILD SUCCESS
./mvnw test -Dtest='*UserImport*,*Export*'          # 新增用例全绿
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components   # 期望 401
```

⚠️ **`mvnw test` 直接写共享开发库 `cpq_db_0724`**（`CLAUDE.md` 已实证更正）。
🚫 **不许写会清库 / 重置全局状态的测试**（`CLAUDE.md §3.2`「测试也算」）。
用例造数据必须用**本任务专属前缀**（`t260902_`）并在 `@AfterEach` **精确删除自己造的那几行**，🚫 不许 `DELETE FROM "user"` 之类无 WHERE 或命中面不明的语句。

## 汇报要求

- 每个 B-x 报「改了哪些文件 + 服务的 AC + 自检输出原文」。
- 🚫 **不要自己判定 AC 通过** —— AC 由主线亲验。你报的是「已实现 + 自检结果」。
- 撞到需要改 schema / 需要动既有导入逻辑 / 需要改列表接口行为 ⇒ **停下报主线**，不要自行扩范围。
