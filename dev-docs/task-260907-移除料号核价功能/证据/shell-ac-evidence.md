# task-260907 · 非 E2E 类 AC 证据（T-6 / T-7 / T-17）
生成时间：2026-09-07 02:16:38  分支：feat/task-260907-remove-part-costing  HEAD：a81f2c40

## T-6 / AC-6 · 代码物理删除（含反向对照）
```
✅ GONE: cpq-backend/src/main/java/com/cpq/basicdata/v6/maintenance
✅ GONE: cpq-frontend/src/pages/master-data/part-costing
✅ GONE: cpq-frontend/src/pages/master-data/PricingBasicDataImportDrawer.tsx
-- 🔁 反向对照（证明删的是目标，不是删过头）--
✅ EXISTS: cpq-frontend/src/pages/master-data/shared/EditableSheetTable.tsx
✅ EXISTS: cpq-frontend/src/pages/master-data/shared/SheetPartListTab.tsx
✅ EXISTS: cpq-frontend/src/pages/master-data/shared/types.ts
✅ EXISTS: cpq-frontend/src/pages/master-data/shared/sheetApiFactory.ts
✅ EXISTS: cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing
```

## T-7 / AC-7 · 零残留引用（先做还原实验）
扫描范围（AC-7 2026-09-07 修正后，已排除 db/migration）：cpq-backend/src/main/java cpq-backend/src/test/java cpq-frontend/src cpq-frontend/e2e
```
① 还原实验：插哨兵 = 19 行；撤哨兵 = 18 行；差值 = 1（必须 = 1，否则本轮扫描是白测）
② 正式扫描命中数 = 18

-- 按子树拆分 --
  cpq-backend/src/main/java      0
  cpq-backend/src/test/java      0
  cpq-frontend/src               0
  cpq-frontend/e2e               18

-- 逐条清单 --
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts:2: * task-260907「移除主数据维护『料号核价』」· E2E 验收
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts:5: *   dev-docs/task-260907-移除料号核价功能/需求文档.md §③ AC 原文
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts:6: *   dev-docs/task-260907-移除料号核价功能/api.md      §1 删除端点 / §2 反向对照端点
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts:7: *   dev-docs/task-260907-移除料号核价功能/test.md     §0 五种假绿形态 / §2 夹具
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts:47:  '../../dev-docs/task-260907-移除料号核价功能/证据/e2e'
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts:267:test('T-1 / AC-1：/master-data-hub 页签集合逐字等于 6 项，且不含「料号核价」', async ({ page }) => {
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts:273:  expect(tabs.length, 'T-1 前置：一个页签都没读到 ⇒ 下面的「不含料号核价」是空跑').toBeGreaterThan(0);
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts:277:  expect(tabs, 'AC-1：「料号核价」仍在页签里 ⇒ 入口没摘干净').not.toContain('料号核价');
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts:410:    u.includes('/basic-data-import/v6/pricing') || u.includes('/pricing-basic-data'));
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts:433:test('T-4 / AC-4：/pricing-basic-data/* 返 404；同轮次反向对照 /dataset/cost-basic/parts 返 200', async ({ page }) => {
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts:437:  const r1 = await page.request.get('/api/cpq/pricing-basic-data/parts?page=1&size=1');
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts:438:  const r2 = await page.request.get('/api/cpq/pricing-basic-data/sheets');
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts:441:  console.log(`[T-4] GET /pricing-basic-data/parts  -> ${r1.status()}  body=${JSON.stringify(b1)}`);
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts:442:  console.log(`[T-4] GET /pricing-basic-data/sheets -> ${r2.status()}  body=${JSON.stringify(b2)}`);
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts:458:  expect(r1.status(), 'AC-4：GET /pricing-basic-data/parts 应为 404').toBe(404);
cpq-frontend/e2e/task260907-remove-legacy-costing-tab.spec.ts:459:  expect(r2.status(), 'AC-4：GET /pricing-basic-data/sheets 应为 404').toBe(404);
cpq-frontend/e2e/repair0805-three-views.spec.ts:263:  // （17 页签 / 各 1 行 / 188 个整列 '—'），即：这是该料号核价侧压根没有 V6 基础数据的
cpq-frontend/e2e/dataset-maintenance.spec.ts:84:const REMOVED_TAB = '料号核价';
```

## T-17 / AC-17 · 文档回写（含反向对照）
```
main-api.md  'basic-data-import/v6/pricing'  = 0   期望 0
main-api.md  '核价基础数据导入（同步）'小节  = 0   期望 0
🔁 反向对照 'basic-data-import/v6/quote'      = 2   期望 >=1（证明删的是一节不是整章）
BACKLOG.md   'BL-0214'                        = 1   期望 >=1
INDEX.md     'task-260907-移除料号核价功能'   = 1   期望 >=1
RECORD.md    本任务的 [2026-09-07] 条目       = （见下方人工核对）
```

## T-4 / T-5 · 端点 A/B 对照（curl，未鉴权，同一时刻两侧同一请求）
```
master = http://localhost:8081（主工作区，改动前）   本分支 = http://localhost:8099（worktree 冷启动，改动后）
```
请求                                               master(8081)   本分支(8099) 判定
GET /api/cpq/pricing-basic-data/parts?page=1&size=1  401            404            AC-4 端点已下线
GET /api/cpq/pricing-basic-data/sheets               401            404            AC-4 端点已下线
GET /api/cpq/basic-data-import/v6/pricing/template   401            404            AC-5 端点已下线
POST /api/cpq/basic-data-import/v6/pricing           401            405            AC-5 主判据 401→405
POST /api/cpq/basic-data-import/v6/zzz-not-a-path    405            405            🔁阳性对照:405绝对值非证据
GET /api/cpq/dataset/cost-basic/parts?page=0&size=1  401            401            🔁AC-4 反向对照:保留端点仍在
GET /api/cpq/basic-data-import/v6/417b8558-b8dd-4618-a60f-dd5f9fe19503 401            401            🔁AC-5 反向对照:同类保留端点仍在
GET /api/cpq/components                              401            401            🔁进程存活对照

recordId（执行时实查，非写死）= 417b8558-b8dd-4618-a60f-dd5f9fe19503
```

## T-10 / AC-10 · 后端冷启动（临时端口 8099，🚫 未停共享 8081）
```
[coldstart] worktree = /home/joii/project/cpq/.claude/worktrees/task-260907-remove-part-costing
[coldstart] 目标端口 = 8099（🚫 不碰共享 8081）
[coldstart] ① mvnw -q clean（清 target/classes，防陈旧 class 假绿）
[coldstart] ② 冷启动 quarkus:dev → /tmp/claude-1000/-home-joii-project-cpq/be60f6de-651d-4eef-b013-59aed441b1f5/scratchpad/coldstart-8099.log
[coldstart] pid=596554
[coldstart] ③ 等待就绪（最多 300s）
[coldstart] 就绪（第 24s，/api/cpq/components → 401）

═══ AC-10 判据 ═══
  ── Flyway 校验 ──
    [WARNING] The artifact io.quarkus:quarkus-junit5:jar:3.34.3 has been relocated to io.quarkus:quarkus-junit:jar:3.34.3: Update the artifactId in your project build file. Refer to https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.31 for more information.
    [WARNING] The artifact io.quarkus:quarkus-junit5-mockito:jar:3.34.3 has been relocated to io.quarkus:quarkus-junit-mockito:jar:3.34.3: Update the artifactId in your project build file. Refer to https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.31 for more information.
    2026-09-07 02:15:18.945 INFO  [org.fly.cor.int.com.DbValidate] (Quarkus Main Thread) Successfully validated 400 migrations (execution time 00:00.088s)
    2026-09-07 02:15:19.165 INFO  [org.fly.cor.int.com.DbMigrate] (Quarkus Main Thread) Current version of schema "public": 417
    2026-09-07 02:15:19.166 WARN  [org.fly.cor.int.com.DbMigrate] (Quarkus Main Thread) outOfOrder mode is active. Migration of schema "public" may not be reproducible.
    2026-09-07 02:15:19.175 INFO  [org.fly.cor.int.com.DbMigrate] (Quarkus Main Thread) Schema "public" is up to date. No migration necessary.
  ── 禁止出现的三类异常 ──
    UnsatisfiedResolutionException = 0 （期望 0）
    DeploymentException = 0 （期望 0）
    ClassNotFoundException = 0 （期望 0）
  ── 存活探针（🚫 /q/health 返 404，不是健康探针）──
    GET /api/cpq/components -> 401  （期望 401）
    GET /api/cpq/health     -> 200     （期望 200）

[coldstart] 后端仍在运行（pid=596554），供 E2E 使用。收工时： kill 596554
```
