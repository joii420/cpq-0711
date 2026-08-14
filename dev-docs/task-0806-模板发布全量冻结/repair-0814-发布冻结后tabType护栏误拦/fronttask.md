# fronttask · repair-0814 发布冻结后 tabType 护栏误拦

> **结论：前端零改动。**
> 按 `CLAUDE.md`「前端/接口零改动的任务也要写 `fronttask.md`」的要求，本文件写的是**「为什么不改」的判定依据 + 回归确认清单 + 二期触发条件**。
> 🚫 不留空槽：下面每一条都是实际 grep / 读码得出的，不是「大概不用改」。

---

## 1. 为什么不改（逐条判定依据）

本次三项后端改动（`问题说明.md` §⑤ D-1/D-2/D-3）对前端的影响面：

| 后端改动 | 前端是否需要配合 | 判定依据 |
|---|---|---|
| **D-1** 护栏判定收窄 + 文案重写 | ❌ 否 | 请求/响应结构不变，仅 400 触发条件收窄 + `message` 文本变化。前端对该文本**无任何匹配逻辑**（§2 第 3 项实测）。原先误拦的场景现在返 200，走的是**既有的保存成功路径**，无新分支 |
| **D-2** publish 新增 400 | ❌ 否 | `TemplateConfiguration.tsx:345` 已有兜底 `message.error(e.message \|\| '发布失败')`，任何后端 400 都能显示。本次不需要结构化 payload（不像 `FORMULA_CYCLE` 那样要开抽屉） |
| **D-3** 渲染期缺 `parent_no` 改抛错 | ❌ 否 | 走的是 `BomTreeRenderService` **既有的失败通道**（与同文件 `failedComponents` 块同款），前端渲染失败的展示路径未变 |

### 关键判定：多行文案让「零前端改动」也能有好提示

`ComponentManagement.tsx:49-62` 的 `showSaveError` 已经按 `msg.includes('\n')` 做分流：

```js
const showSaveError = (msg: string) => {
  if (!msg.includes('\n')) { message.error(msg); return; }      // 单行：3s 自动消失
  notification.error({ …, duration: 0, … });                     // 多行：常驻 + pre-wrap
};
```

因此 D-1 的新文案**写成多行**（见 `api.md` A-1）即可自动获得「常驻 + 可换行 + 可照着改」的提示效果，
**不需要前端加任何代码**。该 helper 的 javadoc 原文写明它就是为「配置员来不及照着去定位」而生的。

组件保存路径确实走这个 helper：`ComponentManagement.tsx:1477`，且该行注释直接点名了 task-0721 这条护栏
（「后端 400（如"组件已被核价模板引用，无法设为 BOM 类型"）须完整展示，不吞成通用「保存失败」」）——
说明这条链路当初就是为展示本护栏而调过的，本次沿用即可。

---

## 2. 回归确认清单（实测证据）

| # | 检查项 | 结果 |
|---|---|---|
| 1 | 前端是否硬编码/匹配旧护栏文案（`一并改成树渲染` / `新建专用组件` / `核价(COSTING)`） | ✅ **零命中**（`grep -ran` 全 `cpq-frontend/src`） |
| 2 | 前端是否按 `message` 文本做错误分支 | ✅ **否**。全仓仅 `ComponentManagement.tsx:50` 一处 `msg.includes('\n')`，那是**排版**判定不是内容判定 |
| 3 | 既有约定是否禁止文本匹配 | ✅ 是，且已成文：`TemplateConfiguration.tsx:335` 注释「**禁止用 message 文本匹配判定**（文案会变，errorType 才是契约）」——本次改文案不违反契约，正因为前端本就不该匹配它 |
| 4 | 组件保存的 400 是否会被吞成通用「保存失败」 | ✅ 不会，`:1477` 用 `err.message` 原文 |
| 5 | publish 的 400 是否有展示路径 | ✅ 有，`TemplateConfiguration.tsx:345` |
| 6 | 是否触发 `CLAUDE.md` 的前端强制自检（`tsc --noEmit` / Vite 200） | **N/A —— 本次未改任何 `.tsx`/`.ts`/`.css`** |
| 7 | 是否触发 E2E | ⚠️ **是**，但**因后端 `ComponentService.java` 在协议级清单里**，不是因为前端。判据与执行方式见 `backtask.md` §6 表注 |

> 上述 1/2 的 grep 命中原文见 `test-report.md` 证据段（本文件只写结论，避免两处各存一份易失步）。

---

## 3. 二期触发条件（什么情况下前端就得动了）

以下任一成立时，本「零改动」结论作废，需要开前端任务：

1. **D-1 / D-2 的错误要做结构化处理**（例如点错误提示直接跳到冲突的那张模板 / 那个页签）
   → 后端需回 `errorType` + 结构化 payload，前端加分支。参照现成范式：`FORMULA_CYCLE` → 环链路抽屉
   （`ComponentManagement.tsx:1462-1468`、`TemplateConfiguration.tsx:336-343`）。
2. **D-3 的渲染失败需要用户可读的定位信息**（现在只是抛 `BusinessException` 走既有失败通道，
   用户看到的是通用渲染失败）→ 若要显示「是哪个组件的 `$view` 缺 `parent_no`」，需要结构化错误 + 前端展示。
3. **护栏要在 UI 上前置**（例如组件管理页的「页签类型」下拉里，把 `BOM` 选项在会被拦的情况下直接置灰 +
   tooltip 说明原因）→ 需要新增一个「该组件能否设为 BOM」的查询端点 + 前端调用。
   ⚠️ 注意此项须遵守 `docs/列表操作规范.md`「**禁止用 `if (...) return null` 隐藏不可用项**」，
   要走「置灰 + hover 显示原因」而非隐藏。

以上三条**本期均不做**，未单独登记 BACKLOG（属「可选增强」，无用户诉求驱动）；
若将来有人提，按此节直接开条目即可。

---

## 4. Task 列表

- [x] 判定前端是否需要改动 → **不需要**，依据见 §1
- [x] 回归确认清单实测 → §2，6 项通过 / 1 项 N/A
- [x] 二期触发条件成文 → §3
- [ ] （执行期）E2E `quotation-flow.spec.ts` 按 `backtask.md` §6 的 A/B 同型对照跑一次，结论入 `test-report.md`
