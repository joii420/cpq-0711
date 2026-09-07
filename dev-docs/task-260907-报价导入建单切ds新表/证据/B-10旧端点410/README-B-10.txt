task-260907 · B-10 旧端点返 410（S-6 / AC-14）—— 代码已完成，运行时验证【待你合并 V422 后再做】

── 改了什么 ────────────────────────────────────────────────────────────
BasicDataImportV6Resource.java（唯一改动文件，+57/-83）
  POST /quote                  → public Response importQuote()      { return goneResponse(); }
  POST /quote/create-quotation → public Response createQuotation()  { return goneResponse(); }
  新增 private static Response goneResponse()：
      Response.status(GONE).entity(ApiResponse.error(410, "报价基础数据导入已迁移至『导入报价数据』"))

✅ GET /{recordId} 方法体【一字未动】（diff 里只出现在 javadoc/注释中）
✅ 🚫 未删任何 Service / Handler（N-7 / D-13）；QuoteImportService、17 个 Q*Handler 全在

── 两个设计决定 ────────────────────────────────────────────────────────
① **方法体清空到一行**，不保留任何参数校验。
   AC-14 断言是「返回 410，**且不建单**，quotation 行数不变」——
   若写成「先校验参数再返 410」，参数合法时仍可能走进建单路径。
   把方法签名改成无参 + 单行 return，是从结构上排除这条路径，而不是靠阅读确认。
② **保留 4 个已无调用方的 @Inject**（quoteService / commitService / materializer / 两个 executor），
   并就地写明理由：N-7 要求「只摘 HTTP 入口不删实现」，保留注入点让这条决定在代码里可见；
   且本类正被另一条任务线并发编辑过，压小改动面可避开合并冲突。
   它们都是 @ApplicationScoped 且无 @Startup ⇒ 不被调用就不实例化，保留零运行时代价。

── 自检 ────────────────────────────────────────────────────────────────
mvnw -o compile 通过（BUILD SUCCESS）

── ⏸ 未做：运行时验证（curl 两个端点应返 410、quotation 行数不变）───────
按主线「起服务前先跑差集、非空就先合 master」的指示：
  差集② 本 worktree 有 / master 无 / 共享库无 = [422]  ⇒ 非空
  ⇒ 起一次 Quarkus dev 就会把 V422 自动落进共享库（migrate-at-start=true）
  ⇒ 🚫 本轮不起服务。V422 合进 master 后再补这段验证。

════════════════════════════════════════════════════════════════════════
【运行时验证】V422 合 master 后补做  2026-09-07 06:2x  端口 8098
🚦 无任何 flyway 绕过参数；起服务前安全闸双空（孤儿[] / worktree有master无[]）
════════════════════════════════════════════════════════════════════════

── 结构证据（比 curl 更强：证明「到不了建单路径」而不只是「这次没走」）──
javap -p target/classes：
  public jakarta.ws.rs.core.Response importQuote();          ← 零参
  public jakarta.ws.rs.core.Response createQuotation();      ← 零参
  private static jakarta.ws.rs.core.Response goneResponse();
  public ApiResponse<Map<String,Object>> getResult(java.util.UUID);   ← 保留
⇒ 两个方法【零参数】：无论请求体是什么，都读不到、也到不了任何业务分支。

方法体各只有一行：
  public Response importQuote()     { return goneResponse(); }
  public Response createQuotation() { return goneResponse(); }

字节码级复核（注释不进字节码，比 grep 可靠）：
  javap -c 里对 CreateQuotationMaterializer / V6QuotationCommitService / QuoteImportService
  的 invoke 命中 = 0  ⇒ 类内对这三个服务零调用。
  （源码 grep 曾命中 materializer.materialize 1 次，查证为一条注释；
    且那条注释是被我的改动搞陈旧的——它还指着已不存在的 :177——已一并修正。）

── 运行时（请求体是【真实存在的 id】，不是随便造一个会被拒的）──────────
基线                              quotation=272  line_item=11302
POST /v6/quote/create-quotation   HTTP=410
  body {"code":410,"message":"报价基础数据导入已迁移至『导入报价数据』"}
  （请求体 importRecordId=2e428139-…(V6/QUOTE/SUCCESS)、customerId=正泰、
    customerTemplateId=正泰模板1 —— 全是库里真实存在的行）
POST /v6/quote（multipart 真 xlsx）HTTP=410  同 body
GET  /v6/{recordId}               HTTP=200  systemType=QUOTE status=SUCCESS
                                            file=1800笔产品订单.xlsx   ← ✅ 保留
事后                              quotation=272  line_item=11302   ← 逐个不变 ✅
未登录                            两个端点均 401（RoleFilter 先于方法体）

── 回归 ────────────────────────────────────────────────────────────────
新链路三端点仍工作：导入 SUCCESS → 建单 200
   quotationId=c5b8a55d-… quotationNumber=QT-20260907-0517 lineItemsCount=3
B-15 跨客户拒收仍生效，且 errors 带 value="CUST-0001"（B-16 也没被打断）
AC-15 结构层 sheets 与基线逐字段相同 : True

── V422 落库 ───────────────────────────────────────────────────────────
Migrating schema "public" to version "422 - task260907 quote ds template seed"
Successfully applied 1 migration to schema "public", now at version v422 (632ms)
flyway_schema_history: 422 | success=t
落库后复核：该 series 模板数=2（v1.0/v1.1）| v1.1 页签=14 | COMP-2254 存在=1
自检块三条守卫在真实数据上全过（迁移成功本身即证明没 RAISE）
