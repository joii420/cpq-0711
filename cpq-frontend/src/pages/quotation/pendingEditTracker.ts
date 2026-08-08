/**
 * task-0806 D15（阶段①）：「前端提交前必须先完成一次对账上报再发 submit」的前端串行保证。
 *
 * 背景：阶段① `assertLineSettled` 的「在飞写」条件恒 false（前端仍 await 编辑请求，物理上不
 * 存在真正的在飞写），唯一生效的是 RECONCILE_PENDING —— 而后端消解 RECONCILE_PENDING 的判据
 * 是「该行最近一次 reconcile-report 上报」。`reconcileReport` 本身是 fire-and-forget（D1：不
 * 阻塞用户打字），但**提交动作**必须等这最后一次上报真正落地，否则后端判定用的还是上一轮的
 * 陈旧对账结果 —— 提交闸门形同虚设。
 *
 * 实现：极小的模块级 in-flight 追踪器，被编辑链路（`QuotationStep2.tsx#handleSnapshotCellEdit`）
 * 同步注册，被提交链路（`QuotationWizard.tsx#handleSubmit`）在真正调 submit 前 await 排空。
 * 用模块级单例而非逐层 prop drilling：ProductCard 深嵌在 QuotationStep2 内部，若要把"是否有
 * 在飞编辑"这件事逐层透传给 QuotationWizard 需要三层 prop（AP-41 同类风险），模块级追踪器
 * 从根上避免"漏传一层就静默失效"。
 *
 * 时序前提（成立原因见 QuotationStep2.tsx handleSnapshotCellEdit 调用处注释）：单元格失焦
 * （blur）在浏览器事件顺序上先于「提交」按钮的 click 触发，故 trackPendingEdit 的同步注册
 * 必定先于 handleSubmit 执行，waitForPendingEdits 才能等到它。
 */

const pending = new Set<Promise<unknown>>();

/** 编辑链路调用：把一次编辑的整条异步链（含内部 reconcile-report 上报）注册为在飞。 */
export function trackPendingEdit(p: Promise<unknown>): void {
  pending.add(p);
  const clear = () => { pending.delete(p); };
  p.then(clear, clear);
}

/** 提交链路调用：排空当前所有在飞编辑（含其内部对账上报），不关心成功/失败（D1：只记录）。 */
export async function waitForPendingEdits(): Promise<void> {
  if (pending.size === 0) return;
  await Promise.allSettled(Array.from(pending));
}
