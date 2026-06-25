/**
 * 报价单草稿的本地缓存(localStorage)读写工具(2026-06-26 抽出)。
 *
 * 草稿本地缓存只是「后端不可用时的兜底备份」,后端 saveDraft/getById 才是真相源。草稿体量大(含 4 份
 * 快照)时 JSON 可达数 MB,超 localStorage ~5MB 配额 → setItem 抛 QuotaExceededError。
 *
 * 必须吞掉这个异常,否则:
 *   ① 写缓存失败会冒泡到 loadQuotation 的「后端失败」catch → 用陈旧/空缓存 applyQuotationData 覆盖
 *      已正确渲染的内容 → 整页空白 → 用户再点保存 → 全删全建落空 → 真实丢数据;
 *   ② 手动保存时弹 'exceeded the quota' 错误打扰用户(后端其实已保存成功)。
 * 写不进就清掉旧值并跳过(避免遗留陈旧缓存日后被兜底误恢复);刷新仍从后端拿全量,数据不丢。
 */
export function draftCacheKey(quotationId: string): string {
  return `cpq-draft-${quotationId}`;
}

/** 安全写草稿本地缓存:超配额/任何异常都吞掉并清掉旧值,绝不抛出。 */
export function safeSetLocalDraft(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    // 多半是 QuotaExceededError:草稿过大写不进本地缓存 → 清掉旧值并跳过(后端为真相源)
    try { localStorage.removeItem(key); } catch { /* ignore */ }
  }
}
