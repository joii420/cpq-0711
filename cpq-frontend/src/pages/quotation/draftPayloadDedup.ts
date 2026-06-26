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
export function stableDraftDedupKey(payload: any): string {
  if (!payload) return '';
  const stable = {
    ...payload,
    lineItems: Array.isArray(payload.lineItems)
      ? payload.lineItems.map((li: any) => {
          if (!li || typeof li !== 'object') return li;
          // 剔除行级派生字段:subtotal / quoteExcelValues
          const { subtotal: _s, quoteExcelValues: _q, componentData, ...rest } = li;
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
