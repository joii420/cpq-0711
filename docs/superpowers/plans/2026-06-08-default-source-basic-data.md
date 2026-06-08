# 默认值来源支持「基础数据」类型(可编辑快照)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让组件字段的「默认值来源」(`default_source`)支持配置 `BASIC_DATA` 类型(`$view.列`),把基础数据查出的值作为**可编辑快照**预填进报价单单元格,中文视图列原生支持,组件无需 `data_driver_path`。

**Architecture:** `default_source` 新增 `BASIC_DATA` 子类型。后端在无-driver「虚拟单行」展开分支里,先把字段 default_source 引用的 `$view` **整行**(中文 key)merge 进 virtualRow,再复用 `evaluatePath` 短路按列名取值(绕开 `$view` 单列路径的 ASCII 校验)。解析值落 `basicDataValues[{path}]`;前端在 expansion 到达后把空单元格回填进 `editRows`(快照),之后即普通可编辑 INPUT。

**Tech Stack:** Java 17 / Quarkus(`ComponentDriverService`、`FormulaCalculator`)+ React/TS(`DefaultSourceEditor`、`QuotationStep2`、`types.ts`)+ Playwright E2E。

**关联文档:** spec `docs/superpowers/specs/2026-06-08-default-source-basic-data-design.md`;反模式 AP-44 / AP-53;记忆 `cpq-chinese-identifiers-need-ascii-alias`。

---

## File Structure

| 文件 | 职责 | 改动类型 |
|---|---|---|
| `cpq-frontend/src/pages/component/types.ts` | `DefaultSource.type` 加 `BASIC_DATA` | Modify |
| `cpq-frontend/src/services/dataSourceResolverService.ts` | `RESOLVER_TYPE_LABEL` 加 `BASIC_DATA` 标签 | Modify |
| `cpq-frontend/src/pages/component/DefaultSourceEditor.tsx` | 新增「基础数据」单选项 + 路径输入 + submit/init 分支 | Modify |
| `cpq-backend/.../component/service/ComponentDriverService.java` | `parseBasicDataPaths` 加 BASIC_DATA 分支 + 虚拟行 merge `$view` 整行 + 两个 helper | Modify |
| `cpq-backend/.../quotation/service/FormulaCalculator.java` | 两处 default_source 消费点加 `BASIC_DATA` 分支 | Modify |
| `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` | 公式输入链加 BASIC_DATA 分支 + 新增快照回填 effect | Modify |
| `cpq-frontend/src/pages/quotation/usePathFormulaCache.ts` | 显式不收集 BASIC_DATA(注释 guard) | Modify |
| `cpq-backend/.../test/.../ComponentDriverServiceHelpersTest.java` | helper 纯函数单测 | Create |

---

## Task 1: 数据模型 — `DefaultSource` 加 `BASIC_DATA`

**Files:**
- Modify: `cpq-frontend/src/pages/component/types.ts:103-115`

- [ ] **Step 1: 改类型联合 + path 注释**

把:
```ts
export interface DefaultSource {
  type: 'GLOBAL_VARIABLE' | 'BNF_PATH' | 'HTTP_API';
```
改为:
```ts
export interface DefaultSource {
  type: 'GLOBAL_VARIABLE' | 'BNF_PATH' | 'HTTP_API' | 'BASIC_DATA';
```
并把 `path?` 的注释:
```ts
  /** BNF_PATH: BNF 路径字符串 */
  path?: string;
```
改为:
```ts
  /** BNF_PATH / BASIC_DATA: 路径字符串。BASIC_DATA 时为 "$view.列" 形态(如 "$cp_view.品名"),支持中文列 */
  path?: string;
```

- [ ] **Step 2: 编译验证**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/src/pages/component/types.ts
git commit -m "feat(default-source): DefaultSource.type 增加 BASIC_DATA"
```

---

## Task 2: 配置 UI — `DefaultSourceEditor` 新增「基础数据」选项

**Files:**
- Modify: `cpq-frontend/src/services/dataSourceResolverService.ts`(RESOLVER_TYPE_LABEL)
- Modify: `cpq-frontend/src/pages/component/DefaultSourceEditor.tsx:90-95, 116-128, 283-292`

- [ ] **Step 1: 加类型标签**

在 `cpq-frontend/src/services/dataSourceResolverService.ts` 的 `RESOLVER_TYPE_LABEL` 对象里加一项(与现有项同风格):
```ts
  BASIC_DATA: '基础数据',
```

- [ ] **Step 2: 把 BASIC_DATA 加进可选类型列表**

`DefaultSourceEditor.tsx` 第 91-95 行,把:
```ts
    dataSourceResolverService.listTypes()
      .then((types) => setAvailableTypes(
        types.filter((t) => t !== 'DATABASE_QUERY')
      ))
      .catch(() => {/* 用默认 */});
```
改为(追加 BASIC_DATA,它非 resolver 注册类型,是 default_source 专属):
```ts
    dataSourceResolverService.listTypes()
      .then((types) => setAvailableTypes(
        [...types.filter((t) => t !== 'DATABASE_QUERY'), 'BASIC_DATA']
      ))
      .catch(() => {/* 用默认 */});
```
并把第 54-56 行的初始默认数组也加上 `'BASIC_DATA'`:
```ts
  const [availableTypes, setAvailableTypes] = useState<string[]>(
    ['GLOBAL_VARIABLE', 'BNF_PATH', 'HTTP_API', 'BASIC_DATA']
  );
```

- [ ] **Step 3: submit() 加 BASIC_DATA 分支**

`DefaultSourceEditor.tsx` 第 116-120 行 BNF_PATH 分支之后,加:
```ts
    if (type === 'BASIC_DATA') {
      if (!bnfPath.trim()) return;
      onConfirm({ type: 'BASIC_DATA', path: bnfPath.trim() });
      return;
    }
```
(复用 `bnfPath` state;init useEffect 第 78 行 `setBnfPath(value.path || '')` 已对任意带 path 的 value 生效,无需改。)

- [ ] **Step 4: 渲染路径输入块**

第 283-292 行 BNF_PATH 的 `{type === 'BNF_PATH' && (...)}` 块之后,加:
```tsx
      {type === 'BASIC_DATA' && (
        <Form.Item
          label="基础数据路径"
          required
          extra="同 BASIC_DATA 字段路径语法, 必须 $视图引用(支持中文列), 如 $cp_view.品名"
        >
          <Input
            value={bnfPath}
            onChange={(e) => setBnfPath(e.target.value)}
            placeholder="$cp_view.品名"
            style={{ fontFamily: 'Consolas, Monaco, monospace' }}
          />
        </Form.Item>
      )}
```

- [ ] **Step 5: 编译 + Vite 200 验证**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/DefaultSourceEditor.tsx
```
Expected: tsc 0 错误;curl `200`

- [ ] **Step 6: Commit**

```bash
git add cpq-frontend/src/services/dataSourceResolverService.ts cpq-frontend/src/pages/component/DefaultSourceEditor.tsx
git commit -m "feat(default-source): DefaultSourceEditor 增加「基础数据」类型配置项"
```

---

## Task 3: 后端 — `parseBasicDataPaths` 收集 default_source.BASIC_DATA 路径

**Files:**
- Modify: `cpq-backend/.../component/service/ComponentDriverService.java:835-841`

- [ ] **Step 1: 在 default_source.BNF_PATH 采集分支旁加 BASIC_DATA**

第 836-841 行:
```java
                Object ds = f.get("default_source");
                if (ds instanceof Map<?, ?> dsMap) {
                    if ("BNF_PATH".equals(String.valueOf(dsMap.get("type")))) {
                        addPathIfPresent(out, dsMap.get("path"));
                    }
                }
```
改为:
```java
                Object ds = f.get("default_source");
                if (ds instanceof Map<?, ?> dsMap) {
                    String dsType = String.valueOf(dsMap.get("type"));
                    // BNF_PATH / BASIC_DATA 都把 path 纳入逐行求值。BASIC_DATA 的 $view 整行已在
                    // 无-driver 虚拟行分支 merge 进 driverRow → evaluatePath 短路按中文列名取值。
                    if ("BNF_PATH".equals(dsType) || "BASIC_DATA".equals(dsType)) {
                        addPathIfPresent(out, dsMap.get("path"));
                    }
                }
```

- [ ] **Step 2: 触发后端重启并健康检查**

Run:
```bash
touch cpq-backend/src/main/java/com/cpq/component/service/ComponentDriverService.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/cpq/components
```
Expected: `401`(鉴权正常,非 500)

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/component/service/ComponentDriverService.java
git commit -m "feat(default-source): parseBasicDataPaths 采集 default_source.BASIC_DATA 路径"
```

---

## Task 4: 后端核心 — 虚拟行 merge `$view` 整行(中文安全解析)+ helper 单测

**Files:**
- Modify: `cpq-backend/.../component/service/ComponentDriverService.java`(虚拟行分支 ~304;新增两个私有 helper)
- Create: `cpq-backend/src/test/java/com/cpq/component/ComponentDriverServiceHelpersTest.java`

- [ ] **Step 1: 写 helper 纯函数失败测试**

新建 `cpq-backend/src/test/java/com/cpq/component/ComponentDriverServiceHelpersTest.java`:
```java
package com.cpq.component;

import com.cpq.component.service.ComponentDriverService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** §default-source BASIC_DATA: viewBasePath / parseBasicDataDefaultViewBases 纯函数单测。 */
class ComponentDriverServiceHelpersTest {

    private static Object invoke(String name, Class<?> argType, Object arg) throws Exception {
        Method m = ComponentDriverService.class.getDeclaredMethod(name, argType);
        m.setAccessible(true);
        return m.invoke(null, arg);
    }

    @Test
    void viewBasePath_stripsChineseLeafAndBraces() throws Exception {
        assertEquals("$cp_view", invoke("viewBasePath", String.class, "$cp_view.品名"));
        assertEquals("$cp_view", invoke("viewBasePath", String.class, "{$cp_view.品名}"));
        assertEquals("$cp_view[a=1]", invoke("viewBasePath", String.class, "$cp_view[a=1].规格"));
        assertNull(invoke("viewBasePath", String.class, "mat_part.unit_weight")); // 非 $ 视图返 null
    }

    @Test
    @SuppressWarnings("unchecked")
    void parseBasicDataDefaultViewBases_dedupesByView() throws Exception {
        String fields = "[" +
            "{\"name\":\"品名\",\"field_type\":\"INPUT_TEXT\",\"default_source\":{\"type\":\"BASIC_DATA\",\"path\":\"$cp_view.品名\"}}," +
            "{\"name\":\"规格\",\"field_type\":\"INPUT_TEXT\",\"default_source\":{\"type\":\"BASIC_DATA\",\"path\":\"$cp_view.规格\"}}," +
            "{\"name\":\"汇率\",\"field_type\":\"INPUT_NUMBER\",\"default_source\":{\"type\":\"BNF_PATH\",\"path\":\"$x.y\"}}" +
            "]";
        List<String> bases = (List<String>) invoke("parseBasicDataDefaultViewBases", String.class, fields);
        assertEquals(List.of("$cp_view"), bases); // 两个 BASIC_DATA 同视图去重;BNF_PATH 不收
    }
}
```

- [ ] **Step 2: 运行确认失败(方法不存在)**

Run: `cd cpq-backend && ./mvnw -q -Dtest=ComponentDriverServiceHelpersTest test`
Expected: 编译失败 / `NoSuchMethodException`(helper 未定义)

- [ ] **Step 3: 加两个 helper 方法**

在 `ComponentDriverService.java` 的 `parseBasicDataPaths` 方法附近(同为 private static 工具区)加:
```java
    /** 从 "$cp_view.品名" / "{$cp_view.品名}" 提取视图基路径 "$cp_view"(去花括号 + 去叶列, 保留谓词);非 $ 视图返 null。 */
    private static String viewBasePath(String fullPath) {
        if (fullPath == null) return null;
        String s = fullPath.trim();
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length() - 1).trim();
        if (!s.startsWith("$")) return null;
        int depth = 0, lastDot = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[' || c == '(') depth++;
            else if (c == ']' || c == ')') depth--;
            else if (c == '.' && depth == 0) lastDot = i;
        }
        return lastDot < 0 ? s : s.substring(0, lastDot);
    }

    /** 收集 default_source.type=BASIC_DATA 字段引用的去重 $view 基路径(用于无-driver 虚拟行整行 merge)。 */
    @SuppressWarnings("unchecked")
    private static List<String> parseBasicDataDefaultViewBases(String fieldsJson) {
        List<String> out = new ArrayList<>();
        if (fieldsJson == null || fieldsJson.isBlank()) return out;
        try {
            List<Map<String, Object>> fields = MAPPER.readValue(fieldsJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> f : fields) {
                Object ds = f.get("default_source");
                if (!(ds instanceof Map<?, ?> dsMap)) continue;
                if (!"BASIC_DATA".equals(String.valueOf(dsMap.get("type")))) continue;
                Object pathObj = dsMap.get("path");
                if (pathObj == null) continue;
                String base = viewBasePath(String.valueOf(pathObj));
                if (base != null && !out.contains(base)) out.add(base);
            }
        } catch (Exception e) {
            LOG.warnf("parse BASIC_DATA default view bases failed: %s", e.getMessage());
        }
        return out;
    }
```

- [ ] **Step 4: 运行确认 helper 测试通过**

Run: `cd cpq-backend && ./mvnw -q -Dtest=ComponentDriverServiceHelpersTest test`
Expected: BUILD SUCCESS,2 测试通过

- [ ] **Step 5: 在虚拟行分支注入 merge**

`ComponentDriverService.java` 第 304 行(`if (customerId != null) { virtualRow.put("customer_id", customerId); }` 之后、`ExpandDriverResponse.Row row = ...` 之前)插入:
```java
            // default_source.BASIC_DATA: 先把引用的 $view 整行(中文 key 安全)merge 进 virtualRow,
            // 使下方 evaluatePath 短路命中 driverRow.get(中文列), 绕开 $view 单列路径的 ASCII 校验。
            for (String viewBase : parseBasicDataDefaultViewBases(effectiveFieldsJson)) {
                try {
                    List<Map<String, Object>> vrows = dataLoader.loadByPath(viewBase, null, partNo, customerId).get();
                    if (vrows != null && !vrows.isEmpty() && vrows.get(0) != null) {
                        for (Map.Entry<String, Object> e : vrows.get(0).entrySet()) {
                            virtualRow.putIfAbsent(e.getKey(), e.getValue());
                        }
                    }
                } catch (Exception ex) {
                    LOG.warnf("[default-source BASIC_DATA] merge view row failed base=%s partNo=%s: %s",
                            viewBase, partNo, ex.getMessage());
                }
            }
```

- [ ] **Step 6: 后端重启 + 健康检查**

Run:
```bash
touch cpq-backend/src/main/java/com/cpq/component/service/ComponentDriverService.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/cpq/components
```
Expected: `401`(非 500)

- [ ] **Step 7: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/component/service/ComponentDriverService.java cpq-backend/src/test/java/com/cpq/component/ComponentDriverServiceHelpersTest.java
git commit -m "feat(default-source): 无driver虚拟行 merge \$view 整行, BASIC_DATA 默认值中文列安全解析"
```

---

## Task 5: 后端 — `FormulaCalculator` 两处 default_source 消费点加 BASIC_DATA 分支

**Files:**
- Modify: `cpq-backend/.../quotation/service/FormulaCalculator.java:505-516, 576-586`

- [ ] **Step 1: collectFieldValues 分支(INPUT_NUMBER)**

第 510-516 行 BNF_PATH 分支:
```java
                    } else if ("BNF_PATH".equals(dsType)) {
                        String path = ds.path("path").asText("");
                        if (!path.isEmpty()) {
                            Object v = lookupBdv(basicDataValues, bnfDriverLookupKey(path));
                            if (nonEmpty(v)) resolved = v;
                        }
                    }
```
把条件改为同时接受 BASIC_DATA(取值键一致,均为 `{path}`):
```java
                    } else if ("BNF_PATH".equals(dsType) || "BASIC_DATA".equals(dsType)) {
                        String path = ds.path("path").asText("");
                        if (!path.isEmpty()) {
                            Object v = lookupBdv(basicDataValues, bnfDriverLookupKey(path));
                            if (nonEmpty(v)) resolved = v;
                        }
                    }
```

- [ ] **Step 2: resolveRowByFieldName 分支(INPUT_*)**

第 580-586 行同款 BNF_PATH 分支:
```java
                        } else if ("BNF_PATH".equals(dsType)) {
                            String p = ds.path("path").asText("");
                            if (!p.isEmpty()) {
                                Object g = lookupBdv(basicDataValues, bnfDriverLookupKey(p));
                                if (nonEmpty(g)) v = g;
                            }
                        }
```
改为:
```java
                        } else if ("BNF_PATH".equals(dsType) || "BASIC_DATA".equals(dsType)) {
                            String p = ds.path("path").asText("");
                            if (!p.isEmpty()) {
                                Object g = lookupBdv(basicDataValues, bnfDriverLookupKey(p));
                                if (nonEmpty(g)) v = g;
                            }
                        }
```

- [ ] **Step 3: 后端重启 + 健康检查**

Run:
```bash
touch cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/cpq/components
```
Expected: `401`

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java
git commit -m "feat(default-source): FormulaCalculator 两处 default_source 消费点支持 BASIC_DATA"
```

---

## Task 6: 前端 — `QuotationStep2` 公式输入链加 BASIC_DATA 分支

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx:546-557`

- [ ] **Step 1: INPUT_NUMBER 公式输入兜底链加 BASIC_DATA**

第 546-557 行 BNF_PATH 分支(注意:BASIC_DATA 只读 basicDataValues,不走 pathCache):
```ts
          } else if (ds.type === 'BNF_PATH' && ds.path) {
            const lookupKey = bnfDriverLookupKey(ds.path);
            if (Object.prototype.hasOwnProperty.call(basicDataValues, lookupKey)) {
              const v = basicDataValues[lookupKey];
              if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
            }
            if (resolved === undefined && partNo) {
              const cache = pathCache ?? (getGlobalPathCache() as Record<string, any>);
              const v = cache[`${partNo}::${ds.path}`];
              if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
            }
          }
```
其后加:
```ts
          } else if (ds.type === 'BASIC_DATA' && ds.path) {
            // BASIC_DATA 只吃行级 basicDataValues(整行通路, 中文安全), 不走 pathCache(单列 ASCII 会失败)
            const lookupKey = bnfDriverLookupKey(ds.path);
            if (Object.prototype.hasOwnProperty.call(basicDataValues, lookupKey)) {
              const v = basicDataValues[lookupKey];
              if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
            }
          }
```

- [ ] **Step 2: 编译 + Vite 200**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx
```
Expected: tsc 0 错误;curl `200`

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(default-source): QuotationStep2 公式输入链支持 default_source.BASIC_DATA"
```

---

## Task 7: 前端核心 — 快照回填 effect(把 BASIC_DATA 默认值写进 editRows)

> ⚠️ **本任务风险最高**(AP-31/AP-38/AP-54 雷区:多入口 / 死循环 / 行索引串位)。务必用 `patchRowField`(functional update),并用 `bakedRef` 防重复回填,完成后**必须跑 E2E**(Task 9)。

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`(ProductCard 内,`patchRowField` 定义之后 ~1128 行)

- [ ] **Step 1: 在 ProductCard 内加 bakedRef + 回填 effect**

在 `patchRowField` 的 `useCallback(...)` 之后插入:
```tsx
  // ── 快照回填:default_source.type=BASIC_DATA 的空 INPUT 单元格,首次拿到 expansion 时
  //    把解析值写进 editRows(快照语义);写一次即非空 → 不再触发。bakedRef 防"清空后又回填"。
  const bakedRef = useRef<Set<string>>(new Set());
  useEffect(() => {
    if (!customerId || !item.productPartNo || !item.componentData) return;
    // 与 prune effect(~1014)同款 lineItemId 计算, 保证算出的 expansion key 与渲染侧一致
    const lineItemId = (item as any).id || (item as any).tempId || '';
    item.componentData.forEach((comp, ci) => {
      if (!comp.componentId || !Array.isArray(comp.fields)) return;
      const expKey = driverExpansionKey(
        lineItemId, item.productPartNo, comp.componentId, customerId,
        comp.dataDriverPath, fieldsOverrideHash(comp.fields as any[]),
      );
      const exp = driverExpansions?.[expKey];
      if (!exp || exp.rowCount <= 0) return;
      const inputFields = (comp.fields as any[]).filter(
        (f) => (f.field_type === 'INPUT_TEXT' || f.field_type === 'INPUT_NUMBER')
          && f.default_source?.type === 'BASIC_DATA' && f.default_source?.path,
      );
      if (inputFields.length === 0) return;
      for (let ri = 0; ri < exp.rowCount; ri++) {
        const bdv = exp.rows[ri]?.basicDataValues;
        if (!bdv) continue;
        const curRow = comp.rows?.[ri] || {};
        for (const f of inputFields) {
          const key = f.name || f.key || '';
          if (!key) continue;
          const guard = `${ci}-${ri}-${key}`;
          if (bakedRef.current.has(guard)) continue;
          const cur = curRow[key];
          const isEmpty = cur === undefined || cur === null || cur === '';
          if (!isEmpty) { bakedRef.current.add(guard); continue; } // 已有值(含历史快照)→ 标记不再处理
          const lk = bnfDriverLookupKey(f.default_source.path);
          const v = Object.prototype.hasOwnProperty.call(bdv, lk) ? bdv[lk] : undefined;
          if (v == null || (Array.isArray(v) && v.length === 0)) continue; // 没解出值 → 留待下次 expansion
          bakedRef.current.add(guard);
          patchRowField(ci, ri, key, typeof v === 'object' ? String(v) : v);
        }
      }
    });
    // 依赖:expansion map 引用 + item 行数据;patchRowField 稳定
  }, [driverExpansions, item, customerId, patchRowField]);
```

- [ ] **Step 2: 确认 import 齐全**

确认文件顶部第 7 行已 import `driverExpansionKey, bnfDriverLookupKey, fieldsOverrideHash`(已存在),`useRef/useEffect/useCallback` 已在第 1 行 import(已存在)。无需新增 import。

- [ ] **Step 3: 编译 + Vite 200**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx
```
Expected: tsc 0 错误;curl `200`

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(default-source): QuotationStep2 快照回填 effect — BASIC_DATA 默认值写入可编辑行"
```

---

## Task 8: 前端 — `usePathFormulaCache` 显式不收集 BASIC_DATA(guard 注释)

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/usePathFormulaCache.ts:99-102, 169-172`

- [ ] **Step 1: 两处 BNF_PATH 采集旁加 guard 注释**

第 99-102 行(fingerprint)与第 169-172 行(tasks)的 `default_source?.type === 'BNF_PATH'` 块**保持不变**,各自上方补一行注释,防后人误把 BASIC_DATA 也收进来:
```ts
              // 注意: 不收集 default_source.type==='BASIC_DATA' —— $view.中文列 作单列路径会撞
              //   SqlViewExecutor ASCII 校验(PATH_PATTERN/SQL_IDENT)失败; BASIC_DATA 默认值只走
              //   后端 expand 整行通路(ComponentDriverService 虚拟行 merge), 不进 batch-evaluate。
```

- [ ] **Step 2: 编译 + Vite 200**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/usePathFormulaCache.ts
```
Expected: tsc 0 错误;curl `200`

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/src/pages/quotation/usePathFormulaCache.ts
git commit -m "docs(default-source): usePathFormulaCache 标注 BASIC_DATA 不走 pathCache"
```

---

## Task 9: 验收 — E2E + COMP-0027 手动验证 + 自检

**Files:** 无代码改动(验证 + 配置 + 截图)

- [ ] **Step 1: 跑现有 E2E 回归(协议级文件强制)**

Run:
```bash
cd cpq-frontend
rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: `1 passed`;日志含 `'加载中' final count = 0`;全 8 Tab `'加载中'=0`

- [ ] **Step 2: 把 COMP-0027 的 6 字段 default_source 改为 BASIC_DATA 类型**

在「组件管理」打开 COMP-0027,对 品名/规格/尺寸/材质/汇率/单重 逐个把「默认值来源」改为「基础数据」、路径填 `$cp_view.品名`(/规格/尺寸/材质/汇率/单重)。保存。

DB 旁路校验(确认配置已落库):
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db -t -A -c "
SELECT f->>'name', f->'default_source'->>'type', f->'default_source'->>'path'
FROM component c, jsonb_array_elements(c.fields::jsonb) f
WHERE c.code='COMP-0027' AND f->'default_source'->>'type'='BASIC_DATA';"
```
Expected: 6 行,type 均为 `BASIC_DATA`,path 形如 `$cp_view.品名`

- [ ] **Step 3: 真实报价单手动验收**

1. 新建/打开一张报价单,选客户 `8000137`,加产品料号 `3120018220`(主料1)。
2. 进「产品」Tab → 品名/规格/尺寸/材质/汇率/单重 **自动带出** cp_view 的值(品名=主料1、汇率=7.12 等;cp_view 中为空的列显示空属正常)。
3. 手动改写「品名」单元格 → 可编辑、改写生效。
4. 刷新报价单(F5)→ 已带出/已改写的值**稳定不变**(快照)。
5. F12 Network 确认无 `$cp_view.品名` 发往 `/formulas/batch-evaluate`(BASIC_DATA 不走 pathCache)。

- [ ] **Step 4: 写「已自检」声明**

形如:
> TS 0 错误 ✅;DefaultSourceEditor / QuotationStep2 / usePathFormulaCache → Vite 200 ✅;后端 /api/cpq/components → 401 ✅;ComponentDriverServiceHelpersTest 2 passed ✅;E2E `1 passed` + `'加载中'=0` ✅;COMP-0027 报价单品名/汇率自动带出且可改写、刷新稳定 ✅

- [ ] **Step 5: 更新 RECORD.md(多 Agent 共享记忆)**

在 `docs/RECORD.md` 追加一行:
```
[2026-06-08] 默认值来源 - default_source 新增 BASIC_DATA 类型(可编辑快照) | ComponentDriverService/FormulaCalculator/DefaultSourceEditor/QuotationStep2/types.ts | 无driver虚拟行 merge $view 整行绕开单列 ASCII 校验, 中文列安全; 快照回填 editRows; 详见 specs/2026-06-08 + plans/2026-06-08
```

- [ ] **Step 6: Commit**

```bash
git add docs/RECORD.md
git commit -m "docs(record): 默认值来源支持 BASIC_DATA 类型(可编辑快照)登记"
```

---

## 自检覆盖对照(spec → task)

| spec 章节 | 落地 task |
|---|---|
| §3.1 数据模型 | Task 1 |
| §3.2 配置 UI | Task 2 |
| §3.3 中文安全解析(整行 merge)| Task 3 + Task 4 |
| §3.4 快照 + 可编辑 | Task 7 |
| §4 协议点 #1 types/editor | Task 1, 2 |
| §4 协议点 #5 usePathFormulaCache 不收集 | Task 8 |
| §4 协议点 #6 ComponentDriverService | Task 3, 4 |
| §4 协议点 #7 FormulaCalculator | Task 5 |
| §4 协议点 #8 QuotationStep2 公式链 + 回填 effect | Task 6, 7 |
| §4 协议点 #2/#3/#4(泛型透传,仅验证)| Task 9 E2E 覆盖 |
| §5 测试 | Task 4(单测)+ Task 9(E2E + 手动)|

## 已知限制(文档化,非缺陷)

- 本期 merge 仅在**无-driver 虚拟行分支**生效。若一个**有 driver** 的组件给字段配 default_source.BASIC_DATA 且引用与 driver **不同**的视图,该默认值不会解出(driver 行无此列)。COMP-0027 是无-driver 场景,不受影响。如未来需要,再在 driver 分支补同款 merge。
- 快照回填仅在"单元格为空"时触发一次/会话(bakedRef 守卫)。用户清空已保存的值后,同会话内不会再自动回填(符合快照语义)。
