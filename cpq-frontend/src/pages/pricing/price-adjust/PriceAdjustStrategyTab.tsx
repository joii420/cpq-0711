import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Card, Form, Switch, Radio, Select, InputNumber, TimePicker, Button, Space, Alert, Popconfirm, message,
} from 'antd';
import dayjs from 'dayjs';
import type { ApiError } from '../../../services/api';
import { priceAdjustService } from '../../../services/priceAdjustService';
import { extractErrorPayload } from './errorPayload';
import MaterialRangeMatrix from './MaterialRangeMatrix';
import ElementMatrix from './ElementMatrix';
import ComparisonColumnPanel from './ComparisonColumnPanel';
import VersionTrailPanel, { type VersionTrailPanelHandle } from './VersionTrailPanel';
import ChangeLogDrawer from './ChangeLogDrawer';
import type {
  PriceAdjustStrategyDTO, CycleType, MaterialScopeMode, StrategySaveRequest,
  MaterialRowDTO, ElementRowDTO, RemovalNeedsConfirmPayload, UnselectNeedsConfirmPayload,
} from '../../../types/price-adjust';

export interface PriceAdjustStrategyTabProps {
  customerNo: string;
  customerLabel: string;
}

const CYCLE_OPTIONS: { value: CycleType; label: string }[] = [
  { value: 'DAILY', label: '每天' },
  { value: 'WEEKLY', label: '每周' },
  { value: 'MONTHLY_DAY', label: '每月某日' },
  { value: 'MONTHLY_NTH_WEEK', label: '每月第几周' },
];
const WEEKDAY_OPTIONS = [
  { value: 1, label: '星期一' }, { value: 2, label: '星期二' }, { value: 3, label: '星期三' },
  { value: 4, label: '星期四' }, { value: 5, label: '星期五' }, { value: 6, label: '星期六' }, { value: 7, label: '星期日' },
];
const NTH_WEEK_OPTIONS = [1, 2, 3, 4, 5].map((n) => ({ value: n, label: `第${n}周` }));

type PendingConfirm =
  | { kind: 'materials'; payload: RemovalNeedsConfirmPayload }
  | { kind: 'elements'; payload: UnselectNeedsConfirmPayload };

/**
 * 屏 1 · 价格调整策略 Tab（fronttask §1，落位：定价管理 → 定价策略 → 选中客户 → 第 3 个 Tab）。
 *
 * 保存编排（api.md 未给出"一次保存全部"的端点，三个 PUT 各自独立且各有二次确认语义，
 * 以下为前端的编排决策，非 api.md 明文规定）：
 *   底部一个「保存」按钮 → 顺序调用 §1.2 策略字段 → （若 materialScopeMode=SPECIFIED）§1.4 料号清单
 *   → §1.6 元素清单；任一步遇 409 需二次确认则中断整条链，弹受控 Popconfirm，确认后从头重跑
 *   （前两步均幂等/无副作用变化，重跑代价可接受）。
 *   比对列配置（§1.4 fronttask 编号，api.md §1.9/1.10）走独立即时保存（ComparisonColumnPanel 内部），
 *   不纳入这个保存链 —— fronttask 明确其为"唯一写入口"，属独立子配置单元。
 *   「立即生成一次」（§1.11）同样独立于保存链，由 VersionTrailPanel 自行处理。
 *
 * 🔒 一律 Drawer/Popconfirm，不用 Modal（本任务硬约束 17，唯一例外是屏 5，不在本次交付范围）。
 */
const PriceAdjustStrategyTab: React.FC<PriceAdjustStrategyTabProps> = ({ customerNo, customerLabel }) => {
  const [form] = Form.useForm<StrategySaveRequest>();
  const cycleType: CycleType | undefined = Form.useWatch('cycleType', form);
  const materialScopeMode: MaterialScopeMode | undefined = Form.useWatch('materialScopeMode', form);

  const [strategy, setStrategy] = useState<PriceAdjustStrategyDTO | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const [selectedMaterials, setSelectedMaterials] = useState<Map<string, MaterialRowDTO>>(new Map());
  const [selectedElements, setSelectedElements] = useState<Map<string, ElementRowDTO>>(new Map());
  const [materialsSeeded, setMaterialsSeeded] = useState(false);
  const [elementsSeeded, setElementsSeeded] = useState(false);

  const [historyOpen, setHistoryOpen] = useState(false);
  const [pendingConfirm, setPendingConfirm] = useState<PendingConfirm | null>(null);

  const versionPanelRef = useRef<VersionTrailPanelHandle>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const s = await priceAdjustService.getStrategy(customerNo);
      setStrategy(s);
      form.setFieldsValue({
        // 🔒 策略**不存在**时默认关闭（业务方要求「客户价格调整策略默认为关闭」）。
        // 后端 StrategyDTO.notExists() 把 enabled 留 null 交给前端定，此处就是那个"定"的地方 ——
        // 原来填 true 会让后端实体/DB 的默认值被完全绕过（新客户打开页面即显示开启，一保存就落 true）。
        // ⚠️ 只动冒号右边的"不存在分支"，三元结构不能改：已存在的策略必须继续回显它自己的真实状态。
        enabled: s.exists ? s.enabled : false,
        cycleType: s.exists ? s.cycleType : 'MONTHLY_DAY',
        cycleWeekday: s.cycleWeekday ?? undefined,
        cycleDayOfMonth: s.cycleDayOfMonth ?? 1,
        cycleNthWeek: s.cycleNthWeek ?? undefined,
        executeTime: (s.executeTime as any) ?? '18:00',
        materialScopeMode: s.exists ? s.materialScopeMode : 'ALL',
        costDiffThreshold: s.exists ? s.costDiffThreshold : 0,
      } as any);
    } catch (e: any) {
      message.error(e?.message || '加载调价策略失败');
    } finally {
      setLoading(false);
    }
  }, [customerNo, form]);

  useEffect(() => {
    setSelectedMaterials(new Map());
    setSelectedElements(new Map());
    setMaterialsSeeded(false);
    setElementsSeeded(false);
    setPendingConfirm(null);
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [customerNo]);

  // 种入指定料号矩阵的初始选中集合（一次大分页取全量已选，避免跨页丢失，见 MaterialRangeMatrix 顶部注释）
  useEffect(() => {
    if (!strategy || materialsSeeded) return;
    if (strategy.materialScopeMode !== 'SPECIFIED') { setMaterialsSeeded(true); return; }
    (async () => {
      try {
        const res = await priceAdjustService.getMaterials(customerNo, { page: 1, size: 5000, selectedOnly: true });
        setSelectedMaterials(new Map((res.content || []).map((r) => [r.materialNo, r])));
      } catch {
        // 静默失败：矩阵仍可正常浏览/勾选，只是初始未预选（后端未就绪时的预期状态）
      } finally {
        setMaterialsSeeded(true);
      }
    })();
  }, [strategy, materialsSeeded, customerNo]);

  // 种入参与调价元素矩阵的初始选中集合（无 selectedOnly 查询参数，退化为一次大分页取全量后本地过滤 selected）
  useEffect(() => {
    if (!strategy || elementsSeeded) return;
    (async () => {
      try {
        const res = await priceAdjustService.getElements(customerNo, { page: 1, size: 2000, includeDisabled: true });
        setSelectedElements(new Map((res.content || []).filter((r) => r.selected).map((r) => [r.elementCode, r])));
      } catch {
        // 同上
      } finally {
        setElementsSeeded(true);
      }
    })();
  }, [strategy, elementsSeeded, customerNo]);

  const buildStrategyPayload = (values: any): StrategySaveRequest => ({
    enabled: !!values.enabled,
    cycleType: values.cycleType,
    cycleWeekday: (values.cycleType === 'WEEKLY' || values.cycleType === 'MONTHLY_NTH_WEEK') ? values.cycleWeekday : null,
    cycleDayOfMonth: values.cycleType === 'MONTHLY_DAY' ? values.cycleDayOfMonth : null,
    cycleNthWeek: values.cycleType === 'MONTHLY_NTH_WEEK' ? values.cycleNthWeek : null,
    executeTime: typeof values.executeTime === 'string' ? values.executeTime : values.executeTime?.format?.('HH:mm'),
    materialScopeMode: values.materialScopeMode,
    costDiffThreshold: values.costDiffThreshold ?? 0,
  });

  const runSave = async (confirm: { materials?: boolean; elements?: boolean } = {}) => {
    let values: any;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    setSaving(true);
    try {
      const res = await priceAdjustService.saveStrategy(customerNo, buildStrategyPayload(values));

      if (values.materialScopeMode === 'SPECIFIED') {
        try {
          await priceAdjustService.saveMaterials(customerNo, {
            materialNos: Array.from(selectedMaterials.keys()),
            confirmRemoval: !!confirm.materials,
          });
        } catch (e: unknown) {
          const err = e as ApiError;
          const payload = extractErrorPayload<RemovalNeedsConfirmPayload>(e);
          if (err.httpStatus === 409 && payload?.code === 'REMOVAL_NEEDS_CONFIRM') {
            setPendingConfirm({ kind: 'materials', payload });
            return;
          }
          throw e;
        }
      }

      try {
        await priceAdjustService.saveElements(customerNo, {
          elementCodes: Array.from(selectedElements.keys()),
          confirmUnselect: !!confirm.elements,
        });
      } catch (e: unknown) {
        const err = e as ApiError;
        const payload = extractErrorPayload<UnselectNeedsConfirmPayload>(e);
        if (err.httpStatus === 409 && payload?.code === 'UNSELECT_NEEDS_CONFIRM') {
          setPendingConfirm({ kind: 'elements', payload });
          return;
        }
        throw e;
      }

      setPendingConfirm(null);
      if (res?.affectedReviewCount != null && res.affectedReviewCount > 0) {
        message.success(`已保存，触发 ${res.affectedReviewCount} 个待处理料号的预算重算`);
      } else {
        message.success('已保存');
      }
      await load();
      versionPanelRef.current?.reload();
    } catch (e: unknown) {
      message.error((e as ApiError)?.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleSaveClick = () => runSave({});
  const handleCancel = () => load();

  const confirmDescription = pendingConfirm?.kind === 'materials'
    ? (
      <span>
        移出 <b>{pendingConfirm.payload.removedMaterialNos.length}</b> 个料号：其「待处理」审核记录（{pendingConfirm.payload.pendingReviewCount} 条）将作废退出待办池，
        且这些料号在存量单上的元素单价列将解锁为可编辑（{pendingConfirm.payload.unlockedQuotationCount} 张单），确定继续保存？
      </span>
    )
    : pendingConfirm?.kind === 'elements'
      ? (
        <span>
          该元素（{pendingConfirm.payload.removedElementCodes.join('、')}）将退出调价机制，
          <b>{pendingConfirm.payload.unlockedQuotationCount}</b> 张存量单上它的单价列将解锁为可编辑，销售可能改动，确定继续保存？
        </span>
      )
      : null;

  const handleConfirmYes = () => {
    if (!pendingConfirm) return;
    const kind = pendingConfirm.kind;
    runSave({ materials: kind === 'materials' ? true : undefined, elements: kind === 'elements' ? true : undefined });
  };

  return (
    <div>
      <Card
        loading={loading && !strategy}
        style={{ border: '1px solid #f0f0f0', boxShadow: 'none' }}
        title={<span>调价策略 · {customerLabel}</span>}
        extra={
          <Space>
            {strategy?.updatedAt && (
              <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>
                最后变更 {dayjs(strategy.updatedAt).format('YYYY-MM-DD HH:mm')} · {strategy.updatedBy}
              </span>
            )}
            <Button size="small" onClick={() => setHistoryOpen(true)}>🕘 变更历史</Button>
          </Space>
        }
      >
        {/* enabled 默认 false：与上方 load() 的"不存在分支"是同一个默认值的两个落点
            （initialValues 管首帧、load 管拉到策略后的回填），两处必须一致，否则会出现
            "刚打开是关的、请求回来跳成开的"这种闪烁。业务方要求默认关闭。 */}
        <Form form={form} layout="vertical" initialValues={{ enabled: false, cycleType: 'MONTHLY_DAY', materialScopeMode: 'ALL', costDiffThreshold: 0 }}>
          <div style={{ display: 'flex', gap: 32, flexWrap: 'wrap' }}>
            <Form.Item name="enabled" label="启用状态" valuePropName="checked" style={{ minWidth: 320 }}
              extra="停用后不再自动生成版本；已生成版本与已生效价格不受影响。">
              {/* 🔒 与 ElementMatrix 的元素停用标签**刻意用不同的词**，二者指的不是一回事：
                  这里是「本客户的调价策略开不开」→ 开启/关闭；
                  ElementMatrix 里那个标签说的是「元素在主数据中被停用」，不得跟着改成"关闭"
                  —— 元素不是被关闭而是被停用，无差别替换会让那个标签读不通。
                  （措辞刻意避开该标签的原文，好让"改文案时 grep 一遍"的审计不出现噪音命中） */}
              <Switch checkedChildren="开启" unCheckedChildren="关闭" />
            </Form.Item>
            <Form.Item name="costDiffThreshold" label="成本差额预警线" rules={[{ required: true, message: '请输入成本差额预警线' }]}
              style={{ minWidth: 260 }} extra="报价侧成本 − 核价侧成本 < 该值即标红。金额（元），默认 0，只提醒不阻断。">
              <InputNumber precision={2} style={{ width: 160 }} addonAfter="元" />
            </Form.Item>
          </div>

          <h4>调整周期</h4>
          <Form.Item name="cycleType" label="周期类型" rules={[{ required: true }]}>
            <Radio.Group options={CYCLE_OPTIONS} optionType="button" />
          </Form.Item>
          <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap' }}>
            {cycleType === 'WEEKLY' && (
              <Form.Item name="cycleWeekday" label="星期几" rules={[{ required: true, message: '请选择星期几' }]} style={{ width: 160 }}>
                <Select options={WEEKDAY_OPTIONS} />
              </Form.Item>
            )}
            {cycleType === 'MONTHLY_DAY' && (
              <Form.Item name="cycleDayOfMonth" label="每月第几号" rules={[{ required: true, message: '请输入日期' }]}
                extra="当月无该日（如 31 号遇小月）自动顺延至月末。" style={{ width: 200 }}>
                <InputNumber min={1} max={31} style={{ width: '100%' }} addonAfter="号" />
              </Form.Item>
            )}
            {cycleType === 'MONTHLY_NTH_WEEK' && (
              <>
                <Form.Item name="cycleNthWeek" label="第几周" rules={[{ required: true, message: '请选择第几周' }]} style={{ width: 140 }}>
                  <Select options={NTH_WEEK_OPTIONS} />
                </Form.Item>
                <Form.Item name="cycleWeekday" label="星期几" rules={[{ required: true, message: '请选择星期几' }]}
                  extra="当月不足该周数时按当月最后一个该星期几执行。" style={{ width: 160 }}>
                  <Select options={WEEKDAY_OPTIONS} />
                </Form.Item>
              </>
            )}
            <Form.Item name="executeTime" label="执行时刻" rules={[{ required: true, message: '请选择执行时刻' }]}
              extra="建议设在当日价格导入完成之后（LATEST 会自动回溯，不会断价）。" style={{ width: 160 }}
              getValueProps={(v) => ({ value: v ? dayjs(v, 'HH:mm') : undefined })}
              normalize={(v) => (v ? v.format('HH:mm') : v)}
            >
              <TimePicker format="HH:mm" style={{ width: '100%' }} />
            </Form.Item>
          </div>

          <h4>影响范围（只约束"哪些报价单要跟随调价"，不影响新单取价）</h4>
          <Form.Item name="materialScopeMode" label="料号范围" rules={[{ required: true }]}>
            <Radio.Group>
              <Radio value="ALL">所有料号</Radio>
              <Radio value="SPECIFIED">指定料号</Radio>
            </Radio.Group>
          </Form.Item>

          {materialScopeMode === 'ALL' && (
            <Alert type="info" showIcon style={{ marginBottom: 12 }}
              message="已选择「所有料号」—— 该客户全部销售料号的报价单都会进入影响范围，无需逐个勾选。新增料号自动纳入，不必回来维护清单。" />
          )}
          {materialScopeMode === 'SPECIFIED' && (
            <MaterialRangeMatrix customerNo={customerNo} selected={selectedMaterials} onChange={setSelectedMaterials} />
          )}

          <h4 style={{ marginTop: 20 }}>比对列配置（按客户 × 模板系列各一份 · 决定审核时看哪些差异）</h4>
          <ComparisonColumnPanel customerNo={customerNo} />

          <h4 style={{ marginTop: 20 }}>参与调价的元素</h4>
          <ElementMatrix customerNo={customerNo} selected={selectedElements} onChange={setSelectedElements} />

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 20, paddingTop: 16, borderTop: '1px solid #f0f0f0' }}>
            <Button onClick={handleCancel}>取消</Button>
            <Popconfirm
              open={!!pendingConfirm}
              title="需要二次确认"
              description={confirmDescription}
              onConfirm={handleConfirmYes}
              onCancel={() => setPendingConfirm(null)}
              okText="确认保存"
              cancelText="取消"
            >
              <Button type="primary" loading={saving} disabled={!!pendingConfirm} onClick={handleSaveClick}>保存</Button>
            </Popconfirm>
          </div>
        </Form>
      </Card>

      <VersionTrailPanel
        ref={versionPanelRef}
        customerNo={customerNo}
        customerLabel={customerLabel}
        latestVersionNo={strategy?.latestVersionNo ?? null}
        onGenerated={load}
      />

      <ChangeLogDrawer open={historyOpen} onClose={() => setHistoryOpen(false)} customerNo={customerNo} customerLabel={customerLabel} />
    </div>
  );
};

export default PriceAdjustStrategyTab;
