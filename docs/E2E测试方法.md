# CPQ E2E 测试方法（Playwright 标杆 SOP）

> **2026-05-19 立项**：上一轮"修复后 API 验证通过但 UI 实际渲染坏"的事故（同 cid 多 fields_override cache 串号 + 0 行 driver 鬼魂行加载中）暴露了 API 层验证的盲区。本文为前端协议级改动 / 模板 schema 变更 / driver expand 链路改动的**强制 E2E 验证标杆**。
>
> **TL;DR — 三句话**：
> 1. 前端协议改动（useDriverExpansions / QuotationStep2 / QuotationWizard / ReadonlyProductCard / 字段类型枚举）后必须跑 `cpq-frontend/e2e/quotation-flow.spec.ts` 且 8 Tab 截图 + `'加载中'` 计数 = 0。
> 2. API/DB 验证通过 ≠ UI 渲染正确 — 同 componentId 多实例、0 行 driver、LIST_FORMULA 候选匹配等只在浏览器渲染层暴露。
> 3. 复测前清空 `e2e/screenshots/qf-*.png`，复测后对比"修复前/后"截图。

---

## 一、环境与前置

### 1.1 必备运行态

| 组件 | 命令 / 地址 | 验证 |
|---|---|---|
| 后端 Quarkus | dev mode 运行在 `http://localhost:8081` | `curl /api/cpq/components` → 401（非 500） |
| 前端 Vite | dev server 在 `http://localhost:5174` | `curl /` → 200 |
| 数据库 | PostgreSQL `10.177.152.12:5432/cpq_db` | Quarkus 401 验证（绕过直连 psql 拒绝） |
| Playwright | `@playwright/test 1.59+`（已装在 `cpq-frontend/package.json`） | `npx playwright --version` |
| Chromium | `~/AppData/Local/ms-playwright/chromium-*`（Windows） | 已下载 |

### 1.2 种子账号（V68 落地）

```
admin / Admin@2026         # SYSTEM_ADMIN（推荐 E2E 用，权限最大）
alice / Admin@2026         # SALES_REP（验证 RBAC 拒绝）
bob   / Admin@2026         # SALES_MANAGER
```

### 1.3 项目已有 fixtures（**复用，禁止重新发明**）

`cpq-frontend/e2e/fixtures/auth.ts`：

```ts
import { loginAsAdmin, loginAsAlice, loginAsBob, isBackendUp } from './fixtures/auth';

test.beforeAll(async () => { backendUp = await isBackendUp(); });
test.beforeEach(async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
});
```

预存 storageState 在 `cpq-frontend/e2e/.auth/admin.json`，避免反复 UI 登录被 Redis rate limiter 拦截（30 次/分/IP）。

---

## 二、选择器约定（**踩过坑的速查**）

### 2.1 输入框 placeholder 不是万能钥匙

| 控件类型 | placeholder 在哪 | 鲁棒 selector |
|---|---|---|
| 原生 `<input>` | `input[placeholder=...]` 属性上 ✅ | `input[placeholder="输入报价单名称"]` |
| Antd `<Select showSearch>` | 在 `<span class="ant-select-selection-placeholder">` 内部文本，**初始 DOM 可能空字符串** ❌ | 用 Form.Item label 关系（见 2.2） |
| Antd `<DatePicker>` | placeholder 在隐藏 input | `input[placeholder="Select date"]` 可用 |
| Antd `<Input>` 受控 | 同原生 `<input>` | 同上 |

### 2.2 Antd Form Select 鲁棒选择器（label-based）

**禁止**用 `.ant-select-selector` 序号 nth(N) — 选完客户后会多出 3 个 select，序号会变。

**推荐**：用 `.ant-form-item filter has label` 关系定位：

```ts
async function selectByLabel(page: Page, label: string, search: string, optionText?: string) {
  const item = page.locator('.ant-form-item').filter({
    has: page.locator('label', { hasText: label }),
  }).first();
  await item.locator('.ant-select').first().click();
  await page.waitForTimeout(300);
  if (search) {
    await page.keyboard.type(search, { delay: 60 });
    await page.waitForTimeout(900);  // 等远程 onSearch 响应
  }
  await page.locator('.ant-select-item-option').filter({ hasText: optionText || search }).first().click();
  await page.waitForTimeout(400);
}
```

### 2.3 业务组件 selector 速查（CPQ 报价单 wizard）

| 业务对象 | 选择器 | 备注 |
|---|---|---|
| Step 切换条 | `.ant-steps-item-title:has-text("选择客户")` | 5 步固定 |
| 产品视图切换器 | `.ant-segmented-item:has-text("产品卡片")` | Antd Segmented Control |
| 产品卡片内 Tab | `button.qt-tab-btn` | 自定义按钮，**不是 .ant-tabs** |
| 产品卡片表格行 | `.qt-cost-table tr` | **不是 .ant-table-row** |
| 选配抽屉 | `.ant-drawer-content` 或 `.ant-drawer` | Antd Drawer 标准 |
| 抽屉内"下一步" | `page.locator('.ant-drawer button:has-text("下一步")').last()` | 主页面也有同名按钮，必须用 last/visible |
| 选配抽屉步骤 | `.ant-steps-item-title:has-text("料号匹配")` | P1-P5 |
| 料号搜索结果卡片 | `.ant-drawer .ant-list-item` filter '料号' | List.Item 渲染 |
| 工序"+ 添加"按钮 | `.ant-drawer .ant-list-item filter 工序名 → button:has-text("添加")` | 不是 checkbox |
| 选配完成红错 | `.ant-message-error` | partMode null 等 |
| 产品小计条 | `text=产品小计` | 底部 SUBTOTAL 不作为 Tab |

### 2.4 千万别用的 selector

- `.ant-tabs-tab` 配产品卡片内 Tab — **CPQ 用自定义 `qt-tab-btn` 不用 Antd Tabs**
- `.ant-segmented-item` 配产品卡片内 Tab — 那是顶部视图切换器
- `[role="tab"]` 在 CPQ 报价单页 — 因为 `qt-tab-btn` 是 `<button>` 没 ARIA role
- `:nth(0)` 单 Tab 维度 — 选完客户后 select 序号变动
- `input[placeholder="搜索并选择客户"]` — Antd Select 的 placeholder 在 span 不在 input

---

## 三、中文 UTF-8 编码踩坑（**必读**）

PowerShell `Invoke-WebRequest -Body $json` 默认用 .NET 默认编码（Windows 中文 GBK）发请求，含中文字段（如"工序"/"材质"）时 Quarkus Jackson 反序列化失败 → **400 Bad Request**。

**修法（强制 UTF-8）**：

```powershell
# 写文件用 UTF-8 (no BOM)
[System.IO.File]::WriteAllText($tmpFile, $reqJson, [System.Text.UTF8Encoding]::new($false))
# 用 curl.exe --data-binary 发, 显式 charset=utf-8
curl.exe -X POST 'http://localhost:8081/api/cpq/...' `
  -H 'Content-Type: application/json; charset=utf-8' `
  -H "Cookie: CPQ_SESSION=$($cookie.Value)" `
  --data-binary "@$tmpFile"
```

不要直接 `Invoke-WebRequest -Body $reqJson` 发含中文 payload —— 调试 API 时会浪费 30 分钟猜 400 原因。

Playwright 不涉及此问题（浏览器 fetch 默认 UTF-8）。

---

## 四、E2E 脚本骨架（报价单流程模板）

### 4.1 playwright.config.ts（参考 `cpq-frontend/e2e/playwright.config.ts`）

```ts
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'report' }]],
  use: {
    baseURL: 'http://localhost:5174',
    headless: true,
    viewport: { width: 1600, height: 1000 },
    locale: 'zh-CN',
    screenshot: 'only-on-failure',  // 显式 shot() 仍生效
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
```

### 4.2 截图工具（每步必拍）

```ts
import { fileURLToPath } from 'url';
const __dirnameLocal = path.dirname(fileURLToPath(import.meta.url));
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

let shotIdx = 0;
async function shot(page, name) {
  const file = path.join(SHOT_DIR, `qf-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}
```

ESM 项目 `__dirname` 用 `fileURLToPath(import.meta.url)` 兜底，**不要直接用 `__dirname`** — 会 `ReferenceError`。

### 4.3 "加载中" 计数（核心验证指标）

```ts
async function countLoading(page, tag) {
  const c = await page.locator('text=加载中').count();
  console.log(`[${tag}] 'text=加载中' count = ${c}`);
  return c;
}
```

**断言**：业务流程任何 happy-path 截图，`'加载中'` 计数应该 = 0。出现非 0 = 走到了"加载中…"永久占位（AP-31/37/38 任一根因）。

### 4.4 报价单完整流程模板（**SIMPLE / COMPOSITE 双 spec**）

CPQ 报价单两套 E2E 标杆 spec, 双形态测试矩阵:

| spec | 产品形态 | 关键流程 |
|---|---|---|
| `cpq-frontend/e2e/quotation-flow.spec.ts` | SIMPLE 独立产品 | 1 配件 existing 3120012574 + 3 工序 |
| `cpq-frontend/e2e/composite-product-flow.spec.ts` | COMPOSITE 组合产品 | 2 配件 (1 existing + 1 custom AgCu90) + 3 工序 × 2 + 组合工艺 RIVET |

**字段类型变动 / 双轨字段改动必须跑两个 spec** (详见 [同模板双轨支持组合产品.md](./同模板双轨支持组合产品.md))。

复用 `cpq-frontend/e2e/quotation-flow.spec.ts`（已验证版），按业务流程剪裁/扩展：

```
1) loginAsAdmin
2) goto /quotations/new
3) selectByLabel(客户, 罗克韦尔)
4) input[placeholder="请填写报价单名称"].fill('E2E-test-{timestamp}')
   ⚠ 注意 placeholder 是"请填写报价单名称"不是"输入报价单名称"
5) selectByLabel(产品分类, 默认分类)
6) selectByLabel(报价模板, 组合产品 v1.10)
7) 下一步 → Step2 报价产品
8) 点 "+ 添加产品" → dropdown 选 "选配添加"
9) 抽屉内选 "独立产品" → 抽屉内"下一步"
10) 料号输入 "3120012574" → 等 1.5s → 点 `.ant-list-item filter '3120012574'`
11) 抽屉内"下一步" × 2 (P2 材质自动锁定 + P3 工序)
12) 工序 `.ant-list-item filter '总装配'` → 内嵌 `button:has-text("添加")`
13) 重复 12 选 "部件装配"
14) 抽屉内 "下一步" × N 到 P5 完成选配
15) 点 "确认添加"
16) 等 networkidle + 3s
17) 滚动到 "产品 1" 卡片
18) 统计 `button.qt-tab-btn` 应 = 8（SUBTOTAL 不渲染为 Tab）
19) 逐 Tab 点击 + 截图 + countLoading
20) 终极断言: 全程 '加载中' = 0
```

### 4.5 逐 Tab 切换验证（**B1 修复后必须做**）

```ts
const tabs = page.locator('button.qt-tab-btn');
const tabNames = ['材质','工序','元素含量','组合工艺','选配-材质','选配-工序列表','选配-元素含量','选配-组合工艺'];
for (const name of tabNames) {
  // 精确匹配避免 "选配-工序列表" 含 "工序" 子串误命中
  const tab = tabs.filter({ hasText: new RegExp(`^${name}$`) }).first();
  if (!await tab.isVisible().catch(() => false)) continue;
  await tab.click();
  await page.waitForTimeout(2200);  // 等切换 + driver expand
  await shot(page, `tab-${name}`);
  const loading = await countLoading(page, `tab-${name}`);
  expect(loading, `${name} Tab 不应有'加载中'`).toBe(0);
}
```

### 4.6 `console.warn` 三段式调试方法论（**复杂渲染 bug 定位**）

> **2026-05-19 B3 修复（LIST_FORMULA 4 个连锁 bug）沉淀的标准调试 SOP**。报价单某 Tab 渲染异常时，单凭截图无法定位深层根因，必须从"数据是否到 → 中间状态 → 最终求值"三层串调试。

#### 调试三段标签约定

| 标签 | 作用 | 在哪里加 |
|---|---|---|
| `[LF-FIND]` / `[XX-FIND]` | **数据是否到达** — 字段类型 / hook 状态 / 配置加载完成？ | useMemo / useEffect 完成后 |
| `[LF-DEBUG]` / `[XX-DEBUG]` | **中间派生** — effectiveRow 包装 / row[字段] 实际值 / 候选匹配结果 | render 内部派生 |
| `[LF-EVAL]` / `[XX-EVAL]` | **最终求值** — condition / 公式 / 选中的 branch / 渲染产物 | 最终 return JSX 之前 |

XX 是业务领域前缀（LF=LIST_FORMULA / DS=DATA_SOURCE / BD=BASIC_DATA / FORMULA / DRIVER 等）。

#### Step 1: 在源码加临时 console.warn（**三段都加**）

```tsx
// FIND 段: 验证配置加载到位
console.warn('[LF-FIND]', activeComponent.tabName,
  'fieldsTypes', activeComponent.fields.map(f => `${f.name}:${f.field_type}`),
  'lfFieldFound', !!listFormulaField,
  'configTplId', listFormulaField?.list_formula_config?.config_template_id,
  'lfStateLoaded', !!lfState?.template,
  'lfCategory', lfCategory?.code,
  'lfItemsCount', lfItems.length);

// DEBUG 段: 验证 effectiveRow 包装 + row[字段] 填充
console.warn('[LF-DEBUG]', activeComponent.tabName, 'row', i,
  'isDriverBound', isDriverBound,
  'rowBdvKeys', rowBdv ? Object.keys(rowBdv) : null,
  'rawRowKeys', Object.keys(rawRow),
  'candidates', candidates,
  'matchedLfItem', lfItem?.code || 'NONE',
  'row[工序代码]', baseRow['工序代码']);

// EVAL 段: 验证 condition / chosenFormula / 渲染产物
console.warn('[LF-EVAL]', listFormulaItem.code,
  'row[工序代码]', rowFieldValues['工序代码'],
  'condHits', branchHitResults,
  'chosenFormula', chosenFormula);
```

#### Step 2: E2E 脚本抓 console.warn

```ts
const lfDebug: string[] = [];
page.on('console', (m) => {
  const text = m.text();
  if (m.type() === 'error') consoleErrors.push(text);
  // ⚠️ 用前缀匹配 — 别写 `'[LF-FIND]'` 完整字符串, 会漏 `[LF-FIND-Y]` 之类带后缀的
  if (text.includes('[LF-')) lfDebug.push(text);
});

// 测试末尾输出
console.log(`\n=== LF-DEBUG / LF-RENDER 共 ${lfDebug.length} 条 ===`);
lfDebug.forEach(e => console.log('  🟡 ' + e.slice(0, 350)));
```

#### Step 3: 按三段串行定位

跑 E2E 看 lfDebug 输出，按顺序判断：

1. **第一层 (LF-FIND)** — 数据是否到？
   - lfFieldFound = false → 字段类型识别问题（AP-37 协议传播）
   - lfStateLoaded = false → hook 没加载 / prop drilling 漏传（AP-41）
   - lfItemsCount = 0 → config_template_id 不对 / 端点错
2. **第二层 (LF-DEBUG)** — 中间派生对吗？
   - matchedLfItem = NONE → 候选匹配逻辑错（rowBdv keys / rawRow keys 没覆盖到 item.code）
   - row[字段] = null → spread 反向覆盖（AP-42）
3. **第三层 (LF-EVAL)** — 求值对吗？
   - condHits 全 false → condition 语法 / 字段值类型问题
   - chosenFormula != null 但渲染 "—" → formula 求值抛错（AP-43 require / catch 吃错误）

#### Step 4: 修完删 console.warn

调试用的 `console.warn` 必须在 PR 提交前**全部删干净**。前文"改成 console.debug 保留"是过时建议，已撤销 — 因为 `console.debug` 在 Vite dev/build 默认仍会执行 + 进 bundle, 不能起到"调试时显示生产时静默"的效果。生产环境的可观测性应该走专门的日志库 (如 `loglevel`) 或后端日志，不要在前端 console 留任何调试痕迹。

#### 反面教训

- **不要在 catch 块里静默吃错误** — `catch {}` → render fallback "—" 让 AP-43 这种隐藏 bug 跑了几个版本。至少 `catch (err) { console.warn('eval failed:', err); ... }`
- **不要只在出问题的 Tab 加 log** — 加 if 过滤后**对照组**（其他 Tab）数据是关键的，三段都打才能对比出哪段断了
- **不要漏抓 console** — Playwright 默认不抓 console.log，必须显式 `page.on('console', ...)`
- **不要用全字符串匹配过滤** — `text.includes('[LF-FIND]')` 不匹配 `[LF-FIND-Y]`；用前缀 `text.includes('[LF-')` 通吃

---

## 五、复测协议（"修复前 / 后"对比强制）

**每次前端协议级改动后必须做**：

1. **清旧截图**: `Remove-Item e2e\screenshots\qf-*.png`
2. **跑 E2E**: `npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list`
3. **看输出**:
   - 每 Tab 一行: `[Tab '工序'] rows=X, '加载中'=Y`
   - `'加载中' final count = 0`（否则失败）
   - `console.error 总数: N` — antd deprecated 警告可忽略，但 `<form> form` hydration error / 业务报错必修
4. **看截图**: 至少打开 qf-19/qf-20 (确认添加后) + qf-21~28 (8 个 Tab) 9 张
5. **写报告**: 修复前/后对比表（参考 RECORD.md 2026-05-19 案例）

---

## 六、复杂多 Tab 模板验证矩阵

模板 v1.10 这种 9 个组件（含 SUBTOTAL）× 多种 driver 模式 × 多种字段类型的场景，必须矩阵覆盖：

| 组件类型 | 子类 | driver_path | 典型字段 | 验证重点 |
|---|---|---|---|---|
| 产品级 | 标准 | `''`（空） | BASIC_DATA path=v_part_material_recipe.* | AP-29 虚拟单行 + 5 字段子集场景 |
| 视图 driver | 父子聚合 | `v_composite_child_*` | BASIC_DATA + DATA_SOURCE.GLOBAL_VARIABLE | Fix 2 公式 token 取值 + driver row 多列 |
| 表 driver | 1:N | `mat_process` / `mat_bom[...]` | BASIC_DATA path=表.列 + LIST_FORMULA | 同 cid 多实例 cache key 维度 |
| 0 行 driver | 独立产品的组合工艺等 | `mat_composite_process` | BASIC_DATA | AP-38 鬼魂行加载中 |
| SUBTOTAL | 总成本 | `''` | FORMULA | 不渲染为 Tab，显示在底部小计条 |

**同 componentId 多实例必须**：模板里同 cid 同 driverPath 但不同 fields_override 的两个 Tab（AP-37 第 ⑨ 处） → 验证两个 Tab 都能渲染完整数据，不互相覆盖。

---

## 七、Bug 分类清单（按可观察症状）

| 症状 | 可能根因 | 验证手段 |
|---|---|---|
| 整个 Tab "加载中..." | AP-29 / AP-31 / AP-37 第 9 处 / AP-38 | F12 Network 看 batch-expand 请求/响应 |
| 部分列"加载中..." | AP-31 第 4 类（DATA_SOURCE 渲染 fallback 缺）/ AP-37 协议传播漏 | 单 cell 渲染分支 review |
| 数据显示但公式不算 | AP-37 ⑦（computeAllFormulas 缺 field_type 分支）/ Fix 2 同源 | 公式 token 引用的字段 type |
| 字段渲染对了但 LIST_FORMULA cell 空白 | AP-41 ProductCard 漏 prop / AP-42 spread null 反向覆盖 / AP-43 require() 抛错 | LF-FIND / LF-DEBUG / LF-EVAL 三段式（详见 §4.6） |
| 同 cid 多 Tab 配置乱串 / 一个 Tab 配置丢失 | AP-40 H1 firstResult() 反向污染 | 调 admin endpoint 查每个 Tab 的 fields_override 是否独立 |
| 字段类型升级后 PUBLISHED 模板渲染不变 | AP-39 V109 散字段残留 / AP-40 同 cid 反向污染 | 直接 GET 模板 API 看 snapshot.fields |
| "X (共 N 项)" | AP-22 BASIC_DATA cell 缺四级回退链 | 渲染分支检查 |
| 报价单某视图功能正常另一视图失效 | AP-41 ProductCard prop drilling 漏传 | grep `<ProductCard` 对比所有 callsite props |
| 老 V109 散字段残留显示异常 | V193 漏清理 PUBLISHED 模板 snapshot | snapshot JSON 直接 GET 看 |

---

## 八、自检 checklist（**前端协议级 PR 必读**）

PR 涉及以下文件 / 主题时，**必须跑 E2E 并附截图证据**：

- [ ] `cpq-frontend/src/pages/quotation/useDriverExpansions.ts`（cache key / fingerprint / **fieldsOverrideHash** / **双轨 compositeType 切换 2026-05-20**）
- [ ] `cpq-frontend/src/pages/quotation/usePathFormulaCache.ts`（路径采集）
- [ ] `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（渲染分支 / computeAllFormulas / normalizeFieldType / **报价单+核价单两个 ProductCard prop 必须同步** / **双轨 isCompositeItem 参数 2026-05-20**）
- [ ] `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`（enrich / loadQuotation / onConfigureConfirm / **双轨字段透传**）
- [ ] `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx`（详情页 enrich 同源 / **双轨字段透传**）
- [ ] `cpq-frontend/src/pages/quotation/BulkImportPartsDrawer.tsx`（builder mapper / **双轨字段透传**）
- [ ] `cpq-frontend/src/pages/component/types.ts`（FieldItem 联合类型 / FormulaToken / **basic_data_path_composite + dataDriverPathComposite**）
- [ ] `cpq-frontend/src/pages/template/OverridesDrawer.tsx`（**双轨字段透传 toFieldItems + cleanFields**）
- [ ] `cpq-backend/src/main/java/com/cpq/component/service/ComponentDriverService.java`（expand / parseBasicDataPaths / parseGvarDefaultTasks）
- [ ] `cpq-backend/src/main/java/com/cpq/template/service/TemplateService.java`（**refreshSnapshotsByComponent 按 sortOrder 精确匹配 tc，AP-40** / **deleteTemplateComponentsBySortOrder + patchTemplateComponentCompositeOverrides admin endpoints**）
- [ ] `cpq-backend/src/main/java/com/cpq/engine/formula/FormulaCalculationService.java`（token case 分支）
- [ ] 模板 snapshot 数据迁移（Flyway V*）— 必须复测 multi-Tab 模板渲染
- [ ] **任何字段类型变动 / 新增** — 详见 `docs/组件管理字段配置指南.md §十一 字段类型联动性矩阵` 17 处 + `docs/反模式.md AP-44`
- [ ] **同模板双轨改动 (SIMPLE/COMPOSITE 共用)** — 详见 [`docs/archive/同模板双轨支持组合产品.md`](./同模板双轨支持组合产品.md) + `docs/反模式.md AP-45`

**PR 描述必须含**：
1. 修复前截图（至少 qf-19 确认添加后 + 出问题的 Tab）
2. 修复后截图（同样位置）
3. `'加载中' final count = 0` 输出行
4. E2E `1 passed (Xs)` 输出行

---

## 九、常见命令速查（PowerShell on Windows）

```powershell
# 跑 E2E（清旧截图先）
Set-Location D:\a-joii\project\CPQ-superpowers-v2\dev\cpq-frontend
Remove-Item e2e\screenshots\qf-*.png -ErrorAction SilentlyContinue
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list

# 单测某个 spec（不指定 config 走默认 playwright.config.ts，覆盖整个 e2e/）
npx playwright test --config=e2e/playwright.config.ts e2e/quot-draft-auto-03.spec.ts

# 看 trace（失败后）
npx playwright show-trace test-results/.../trace.zip

# 看 HTML 报告
npx playwright show-report cpq-frontend/e2e/report

# DOM dump（写一个临时 spec 调试 selector）
# 参考 cpq-frontend/e2e/dump-dom.spec.ts
```

---

## 十、何时**不需要**跑 E2E

- 纯后端逻辑修改（API contract 不变）
- 后端 SQL 性能优化（不影响业务输出）
- 文档 / 注释更新
- 重命名（无业务行为变化）
- 单元测试新增

**但凡涉及上面"自检 checklist"的文件，无论修改大小，都跑一遍。**

---

## 十一、历史命中事故（教训沉淀）

| 日期 | 事故 | E2E 暴露的 / API 漏掉的 |
|---|---|---|
| 2026-05-19 | 同 cid 多 fields_override cache 串号 (B1) | API batch-expand 单 task 测不到 |
| 2026-05-19 | 0 行 driver autoSave 鬼魂行加载中 (B4) | 后端 expand 返 rowCount=0 正常，前端渲染加载中 |
| 2026-05-19 | computeAllFormulas 缺 DATA_SOURCE 分支 (Fix 2) | API 看 basicDataValues 有 @gvar:* 但前端公式 token 取不到 |
| 2026-05-19 | "选配-工序列表" 老 V109 global_variable_code 散字段残留 (AP-39) | V193 清理只动当前 component.fields，PUBLISHED 模板 snapshot 未清 |
| 2026-05-19 | H1 `refreshSnapshotsByComponent` 同 cid 多 tc firstResult() 反向污染 (AP-40) | API 查模板 snapshot 才发现两个 Tab fields 完全一样, 实际应独立 |
| 2026-05-19 | 报价单视图 `ProductCard` 漏传 `configTemplates` prop (AP-41) | 核价单视图正常 → 必须**两个视图都跑** E2E |
| 2026-05-19 | effectiveRow `{...lfItem, ...rawRow}` 中 rawRow null 字段反向覆盖 lfItem (AP-42) | LF-DEBUG log row['工序代码']=null 才暴露; 截图只看到空白 |
| 2026-05-19 | LIST_FORMULA 渲染分支 `require()` 在 Vite ESM 抛错 → catch → "—" (AP-43) | LF-EVAL log chosenFormula='50' 算对了但渲染 "—" — catch 静默吞错误 |
| 2026-05-20 | LIST_FORMULA BNF lookup 拿不到 React state cache → 渲染 0 (AP-46) | API 看 expand-driver basicDataValues 字段有 key 但 value=null (driver 注入 seq_no 不匹配 Sn 行); 前端 pathCacheState 必须显式入参才能首渲染对 |
| 2026-05-20 | LIST_FORMULA 单轨 formula 在 COMPOSITE 视角下查不到 mat_bom → 渲染 0 (AP-47) | API 直接 batch-evaluate 单值正确; cell render 时 partNo=CFG-COMBO-XXX 才暴露; 加 formula_composite 双轨解决 |
| 2026-05-20 | `data_driver_path_composite` 只存 snapshot entry, publish/refresh 抹掉 (AP-48) | admin patch-composite 设值正确, 用户触发 refresh 才丢; 加 V205 tc 列 + 4 处协议传播解决 |

---

## 十二、2026-05-20 新增 E2E spec 速览

### `yield-rate-bnf-formula.spec.ts` — LIST_FORMULA BNF 路径 + 双轨验证

**场景**: 罗克韦尔 + v1.14 模板 + 独立产品 3120012574 + MRO-AS-0001, 验证成材率公式 `{mat_bom[element_name='Sn'].net_qty}*15` 渲染 = 19.3203。

**触发跑 spec 的代码变动**:
- 改 `formulaEngine.ts:evaluateListFormulaString` (入参 / fallback 链)
- 改 `usePathFormulaCache.collectListFormulaBnfPaths`
- 改 LIST_FORMULA 字段配置 (含 BNF path)

**强断言**: `expect(numValue).toBeCloseTo(19.32, 1)`

### `multi-product-flow.spec.ts` — 同模板 SIMPLE + COMPOSITE 多产品

**场景**: 罗克韦尔 + v1.10 模板 + 产品1(独立 3120012574 + 总装配/部件装配) + 产品2(组合 3120012574+AgCu90 + 总装配/部件装配/电镀 + 铆接), 切到产品 1/产品 2 各 8 Tab 截图 + 统计渲染。

**触发跑 spec 的代码变动**:
- LIST_FORMULA formula_composite 改动 (AP-47)
- driver_path_composite 改动 (AP-48 / V205)
- createNewDraft 合并机制
- v1.10 模板双轨字段补全

**软断言** (expect.soft 收集所有问题再一起报): 每个产品所有 Tab 无 "加载中" / "#ERROR" / "(共 N 项)" 兜底显示。

**自检截图**: `mpf-XX-pN-tab-{name}.png` 共 16+ 张, PR 必含。

### `master-data-viewer.spec.ts` — 主数据查看页

**场景**: 登录 → `/master-data/viewer` → 下拉切 4 张核心表 (mat_part/mat_bom/mat_process/mat_composite_process) + 系统字段开关验证。

**触发跑 spec 的代码变动**:
- `MasterDataTableViewerPage.tsx` 改动
- `TableRegistry.java` 新增 / 删除注册表
- master-data API 列发现逻辑

**强断言**:
- 4 表切换都能渲染表头
- mat_part 隐藏系统字段时列数 < 显示时列数 (确保黑名单过滤生效)

### 协议改动到 E2E 触发对照表

| 协议改动 | 强制跑的 spec |
|---------|-------------|
| LIST_FORMULA 公式 / BNF path / formula_composite | `yield-rate-bnf-formula.spec.ts` + `multi-product-flow.spec.ts` |
| `basic_data_path_composite` / `data_driver_path_composite` | `composite-product-flow.spec.ts` + `multi-product-flow.spec.ts` |
| `template_component` schema 变动 (V205 等) | 全部 quotation-* spec |
| `mat_part` 列变动 / TableRegistry 注册 | `master-data-viewer.spec.ts` |
| `createNewDraft` 合并机制 | `multi-product-flow.spec.ts` + 手工建新版本 + 渲染 |

---

## 附录：本文档维护

- 新增字段类型 / 协议传播 → 同步更新 §2.3 selector 速查 + §8 自检清单
- 新 E2E 流程（如 Excel 导入 / 批量报价）→ 仿照 §4.4 写 spec.ts 模板 + §6 矩阵补充
- 新踩坑 → §7 Bug 分类清单加一行
- 文档版本：v1.0（2026-05-19 首版）
