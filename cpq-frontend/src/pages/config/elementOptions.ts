/**
 * 元素字典下拉的共享选项与过滤器（task-260812 交付，task-260901 抽取为共享模块）。
 *
 * ⚠️ **不得回退的既有能力**：`task-260812` 已把元素列改成元素字典下拉 ——
 * 选中显示 `编号 / 符号 / 中文名`，输入这三段中的**任意一段**都能筛到同一项。
 * task-260901 把元素选择从材质抽屉拆到了**两处**：
 *   ① 新建材质抽屉的配方卡片（F-13）
 *   ② 材质编辑抽屉的「元素组成」区（F-4）
 * 两处都必须原样继承本模块，不许改回自由文本、也不许只在一处继承。
 * → 服务 AC-35 ① ②
 *
 * 🚫 反向约束（AC-35 反向断言）：候选是**固定枚举**的选择框（类型 locked/editable/partial、
 * 状态 ACTIVE/INACTIVE）**不得**开 showSearch —— 固定枚举开搜索会让用户以为还有更多选项没列出来。
 */
import type { ElementItem } from '../../services/elementService';

export interface ElementOption {
  /** = elementNo，权威元素链（task-260709 B2） */
  value: string;
  /** '10001 / Ag / 银' —— 显示了几段就要能搜几段 */
  label: string;
  disabled: boolean;
  elementNo: string;
  elementCode: string;
  elementName: string;
}

/**
 * `filterOption` —— 对 elementNo / elementCode / elementName 三字段做不区分大小写的包含匹配。
 * 匹配范围必须覆盖该项在框里**显示出来的全部字段**（`fronttask.md` §0）。
 */
export const filterElementOption = (input: string, option?: ElementOption): boolean => {
  if (!option) return false;
  const kw = input.trim().toLowerCase();
  if (!kw) return true;
  return (
    option.elementNo.toLowerCase().includes(kw) ||
    option.elementCode.toLowerCase().includes(kw) ||
    option.elementName.toLowerCase().includes(kw)
  );
};

/**
 * 按字典构造下拉选项。
 * @param dict        元素主表全量
 * @param selectedNos 同一容器内已被别的行选走的 elementNo（置灰防重复）
 * @param currentNo   当前行已选的 elementNo（自身不置灰；已停用的元素也保留可见）
 */
export function buildElementOptions(
  dict: ElementItem[],
  selectedNos: ReadonlySet<string> = new Set(),
  currentNo?: string | null,
): ElementOption[] {
  return dict
    .filter((e) => e.status === 'ACTIVE' || e.elementNo === currentNo)
    .map((e) => ({
      value: e.elementNo,
      label: `${e.elementNo} / ${e.elementCode} / ${e.elementName}`,
      disabled: selectedNos.has(e.elementNo) && e.elementNo !== currentNo,
      elementNo: e.elementNo,
      elementCode: e.elementCode,
      elementName: e.elementName,
    }));
}

/** 无匹配时的空态文案 —— 不要给一个空白下拉，用户会以为是卡住了（AC-35 ①） */
export const ELEMENT_NOT_FOUND_TEXT = '无匹配的元素，请检查输入或先到「主数据维护 → 元素」维护';
