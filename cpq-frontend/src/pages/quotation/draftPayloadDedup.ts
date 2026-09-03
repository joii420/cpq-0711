/**
 * 草稿「是否需要再存」去重键(2026-06-26 P0,砍 payload churn 三连发)。
 *
 * 背景:`buildDraftPayload` 的每行 `subtotal` / `quoteExcelValues` 与每个 componentData 的
 * `rowData` / `subtotal` 都是从 `driverExpansions` **重算**的派生值。首存返回后
 * `syncLineItemsFromResponse` 把 4 份快照写回 lineItems → `useSnapAll` 翻转 → `driverExpansions`
 * 从 live 切到 snapshot 模式 → 这些派生字段重算出**字符串不同**(即便语义同源)→ `JSON.stringify(payload)`
 * 变了 → `lastSaveRef` 去重失效 → `pendingSaveRef` 补发 → 再返回新快照 → 再补发……约 3 轮才稳。
 * 结果:一次用户动作实际发 3 个 draft,每个干全套重活。
 *
 * 修法:去重只比**用户输入**,剔除上述随 expansion 翻转而变、不代表用户改了什么的派生字段。
 * 这样首存后的补发(用户输入未变)→ 去重命中 → 不再发第 2、3 次 PUT;用户真改了东西(加产品/改字段)
 * → 用户输入变 → key 变 → 正常再存。后端对这些派生快照本就有「为空才兜底重算」守卫,跳过补发不丢数据。
 *
 * 注:编辑失焦 autosave 已关闭(EDIT_AUTOSAVE_ENABLED=false),autoSaveDraft 仅由导入首存触发,
 * 故剔除 rowData(含用户编辑值)用于去重不会漏存用户编辑——手动「保存草稿」走 handleSaveDraft 全量发。
 */
/**
 * repair-260830：把 payload 降级成「只改单头、不动明细行」的轻量版本。
 *
 * 后端 `QuotationService.saveDraft` 对单头字段是 patch 语义（逐个 `!= null` 才覆盖），
 * 而明细行整块包在 `if (request.lineItems != null)` 里（:420，块止于 :701）——
 * 传 `lineItems: null` 即整块跳过，`validateDraftDecimals` 也在 `lineItems == null` 时直接 return。
 * 已实测：对 1845 行的单发 `{}`，行 id 指纹与 componentData 指纹逐字节不变。
 *
 * 用途：用户只改了单头（客户 / 项目名 / 有效期…）就切步骤时，不该把 1845 行明细全删全建一遍。
 *
 * ⚠️ task-260901 起 `QuotationWizard` **不再调用本函数**：增量协议下「只改单头」表达为
 *   `added/modified/removed` 三个空数组（`api.md §1.2` / AC-4），不再靠 `lineItems: null`。
 *   函数与其单测保留，作为后端兼容期（`backtask.md B-2a` 保留 `lineItems` 字段一个版本周期）的回滚手段。
 */
export function headerOnlyDraftPayload(payload: any): any {
  if (!payload) return payload;
  return { ...payload, lineItems: null };
}

/**
 * 只反映「单头字段」的去重键 —— 剔除 lineItems 后的稳定序列化。
 * 与 {@link lineItemsDedupKey} 配对使用，用来区分「改了单头」和「改了明细」两种脏。
 */
export function headerDedupKey(payload: any): string {
  if (!payload) return '';
  const { lineItems: _omit, ...header } = payload;
  return JSON.stringify(header);
}

/**
 * 只反映「明细行」的去重键 —— 复用 {@link stableDraftDedupKey} 的派生字段剔除口径
 * （id / subtotal / quoteExcelValues / rowData 都不算用户输入），只是丢掉单头部分。
 */
export function lineItemsDedupKey(payload: any): string {
  if (!payload || !Array.isArray(payload.lineItems)) return '';
  return stableDraftDedupKey({ lineItems: payload.lineItems });
}

export function stableDraftDedupKey(payload: any): string {
  if (!payload) return '';
  const stable = {
    ...payload,
    lineItems: Array.isArray(payload.lineItems)
      ? payload.lineItems.map((li: any) => {
          if (!li || typeof li !== 'object') return li;
          // 剔除「服务端回填」字段(非用户输入,不应触发再存):
          //   - id:首存时新行 id=null,首存返回后 syncLineItemsFromResponse 把 DB 生成 uuid 回填进 lineItems
          //     → 下次 payload 的 id 从 null 变 uuid → 去重键变 → pendingSaveRef 补发(实测仍三连发的真凶,2026-06-26)。
          //     id 是服务端身份;后端仍照常收到 id 做 UPSERT,只是不参与「用户是否改了东西」的判定。
          //   - subtotal / quoteExcelValues:随 driverExpansions live→snap 翻转而重算的派生值。
          const { id: _id, subtotal: _s, quoteExcelValues: _q, componentData, ...rest } = li;
          return {
            ...rest,
            componentData: Array.isArray(componentData)
              ? componentData.map((cd: any) => {
                  if (!cd || typeof cd !== 'object') return cd;
                  // 剔除组件级派生字段:rowData(driver 展开+公式结果)/ subtotal
                  const { rowData: _r, subtotal: _cs, ...cdRest } = cd;
                  return cdRest;
                })
              : componentData,
          };
        })
      : payload.lineItems,
  };
  return JSON.stringify(stable);
}
