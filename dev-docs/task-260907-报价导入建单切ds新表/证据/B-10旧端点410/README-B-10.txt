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
