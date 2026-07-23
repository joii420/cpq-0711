import React, { useEffect, useState } from 'react';
import { Card, Form, Select, InputNumber, Button, Space, Tag, Alert, message } from 'antd';
import { HistoryOutlined, CalculatorOutlined, PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import SelectableTable, { runBatch } from '../../../components/SelectableTable';
import type { ToolbarAction } from '../../../components/SelectableTable';
import { elementPriceStrategyService } from '../../../services/elementPriceStrategyService';
import {
  METHOD_LABEL, UNIT_LABEL, GLOBAL_CUSTOMER_NO,
  type PriceSourceDTO, type StrategyBundleDTO, type StrategyDTO, type StrategyUpsertRequest,
  type PriceMethod, type WindowUnit, type SimulateDraft,
} from '../../../types/element-price-strategy';
import { formatMethod, formatWindow } from './strategyFormat';
import StrategyExceptionEditDrawer from './StrategyExceptionEditDrawer';
import StrategySimulateDrawer from './StrategySimulateDrawer';
import StrategyHistoryDrawer from './StrategyHistoryDrawer';

/**
 * 「元素价格策略」Tab 内容（task-0722 · F6.3）
 * 客户级默认策略（1 条）+ 元素级例外（N 条）；取值优先级：元素例外 > 客户默认 > 留空。
 */
interface Props {
  customerNo: string;
  customerLabel: string;
}

const ElementPriceStrategyTab: React.FC<Props> = ({ customerNo, customerLabel }) => {
  const isGlobal = customerNo === GLOBAL_CUSTOMER_NO;

  const [defaultForm] = Form.useForm();
  const method: PriceMethod | undefined = Form.useWatch('method', defaultForm);

  const [bundle, setBundle] = useState<StrategyBundleDTO | null>(null);
  const [sources, setSources] = useState<PriceSourceDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const [exceptionDrawerOpen, setExceptionDrawerOpen] = useState(false);
  const [editingException, setEditingException] = useState<StrategyDTO | null>(null);
  const [simulateOpen, setSimulateOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);

  const refresh = async () => {
    setLoading(true);
    try {
      const [b, s] = await Promise.all([
        elementPriceStrategyService.getStrategyBundle(customerNo),
        elementPriceStrategyService.listSources({ status: 'ACTIVE' }),
      ]);
      setBundle(b);
      setSources(s);
      if (b.default) {
        defaultForm.setFieldsValue({
          sourceId: b.default.sourceId,
          method: b.default.method,
          windowNum: b.default.windowNum ?? undefined,
          windowUnit: b.default.windowUnit ?? 'DAY',
          factor: b.default.factor ?? 1,
          premium: b.default.premium ?? 0,
        });
      } else {
        defaultForm.resetFields();
        defaultForm.setFieldsValue({ method: 'AVG', windowUnit: 'DAY', factor: 1, premium: 0 });
      }
    } catch (e: any) {
      message.error(e?.message ?? '加载策略失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { refresh(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [customerNo]);

  // 取值方式='LATEST' 时窗口两项灰置并清空
  useEffect(() => {
    if (method === 'LATEST') {
      defaultForm.setFieldsValue({ windowNum: undefined, windowUnit: undefined });
    }
  }, [method, defaultForm]);

  const handleSaveDefault = async () => {
    try {
      const values = await defaultForm.validateFields();
      const req: StrategyUpsertRequest = {
        customerNo,
        sourceId: values.sourceId,
        method: values.method,
        factor: values.factor ?? 1,
        premium: values.premium ?? 0,
      };
      if (values.method !== 'LATEST') {
        req.windowNum = values.windowNum;
        req.windowUnit = values.windowUnit;
      }
      setSaving(true);
      await elementPriceStrategyService.saveDefaultStrategy(req);
      message.success('客户级默认策略已保存');
      refresh();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const exceptionActions: ToolbarAction<StrategyDTO>[] = [
    {
      key: 'edit',
      label: '编辑',
      icon: <EditOutlined />,
      enabledWhen: (rows) => (rows.length === 1 ? true : '编辑一次只能选一行'),
      onClick: (rows) => { setEditingException(rows[0]); setExceptionDrawerOpen(true); },
    },
    {
      key: 'delete',
      label: '删除',
      icon: <DeleteOutlined />,
      danger: true,
      enabledWhen: (rows) => rows.length > 0,
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 条元素例外？',
      confirmDescription: '删除后该元素改走「客户级默认策略」取价；删除前的完整配置会记入变更历史，可追溯。',
      onClick: async (rows) => {
        await runBatch(
          rows,
          (r) => elementPriceStrategyService.deleteException(r.id),
          { rowLabel: (r) => `${r.elementCode} ${r.elementName ?? ''}`.trim(), successMsg: `已删除 ${rows.length} 条元素例外` },
        );
        refresh();
      },
    },
  ];

  /** 试算草稿：默认策略取当前表单上（含未保存改动）的值；例外取当前已保存列表 */
  const getSimulateDraft = (): SimulateDraft => {
    const v = defaultForm.getFieldsValue();
    const hasDefault = !!v.sourceId && !!v.method;
    return {
      default: hasDefault ? {
        id: bundle?.default?.id ?? null,
        sourceId: v.sourceId,
        method: v.method,
        windowNum: v.method === 'LATEST' ? null : v.windowNum,
        windowUnit: v.method === 'LATEST' ? null : v.windowUnit,
        factor: v.factor ?? 1,
        premium: v.premium ?? 0,
      } : null,
      exceptions: (bundle?.exceptions ?? []).map((e) => ({
        id: e.id,
        elementCode: e.elementCode!,
        sourceId: e.sourceId,
        method: e.method,
        windowNum: e.windowNum,
        windowUnit: e.windowUnit,
        factor: e.factor,
        premium: e.premium,
      })),
    };
  };

  const exceptionElements = (bundle?.exceptions ?? []).map((e) => ({ elementCode: e.elementCode!, elementName: e.elementName }));

  return (
    <div>
      {isGlobal && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 14 }}
          message="此处配置的是核价成本口径，仅核价单取用；报价单取各客户自己的策略。"
        />
      )}

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14, gap: 12, flexWrap: 'wrap' }}>
        <div style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>
          取值优先级：<b style={{ color: '#722ed1' }}>元素例外</b> &gt; <b style={{ color: '#1677ff' }}>客户默认</b> &gt; 留空（销售手填）
        </div>
        <Space>
          <Button icon={<HistoryOutlined />} onClick={() => setHistoryOpen(true)}>变更历史</Button>
          <Button icon={<CalculatorOutlined />} onClick={() => setSimulateOpen(true)}>策略试算</Button>
        </Space>
      </div>

      {/* 卡片 1：客户级默认策略 */}
      <Card
        size="small"
        loading={loading}
        style={{ marginBottom: 18 }}
        title={
          <Space size={8} wrap>
            <span>客户级默认策略</span>
            {bundle?.default && <Tag color="green">已生效</Tag>}
            {bundle?.default && (
              <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', fontWeight: 400 }}>
                最后变更 {dayjs(bundle.default.updatedAt).format('YYYY-MM-DD HH:mm')} · {bundle.default.updatedByName}
              </span>
            )}
          </Space>
        }
        extra={<Button size="small" type="primary" loading={saving} onClick={handleSaveDefault}>保存</Button>}
      >
        <Form form={defaultForm} layout="vertical">
          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
            <Form.Item name="sourceId" label="价格源" rules={[{ required: true, message: '请选择价格源' }]} tooltip="必填，只能选一个" style={{ width: 220 }}>
              <Select placeholder="请选择价格源" options={sources.map((s) => ({ value: s.id, label: s.sourceName }))} />
            </Form.Item>
            <Form.Item name="method" label="取值方式" rules={[{ required: true, message: '请选择取值方式' }]} style={{ width: 170 }}>
              <Select options={(Object.keys(METHOD_LABEL) as PriceMethod[]).map((m) => ({ value: m, label: METHOD_LABEL[m] }))} />
            </Form.Item>
            <Form.Item label="窗口" required={method !== 'LATEST'} tooltip="取值方式为「最新一条价」时本项灰置" style={{ width: 220 }}>
              <Space.Compact style={{ width: '100%' }}>
                <span style={{ lineHeight: '32px', padding: '0 8px', color: 'rgba(0,0,0,.65)' }}>最近</span>
                <Form.Item name="windowNum" noStyle rules={[{ required: method !== 'LATEST', message: '请填写窗口' }]}>
                  <InputNumber min={1} disabled={method === 'LATEST'} style={{ width: '45%' }} />
                </Form.Item>
                <Form.Item name="windowUnit" noStyle rules={[{ required: method !== 'LATEST', message: '请选择单位' }]}>
                  <Select<WindowUnit>
                    disabled={method === 'LATEST'}
                    style={{ width: '35%' }}
                    options={(Object.keys(UNIT_LABEL) as WindowUnit[]).map((u) => ({ value: u, label: UNIT_LABEL[u] }))}
                  />
                </Form.Item>
              </Space.Compact>
            </Form.Item>
            <Form.Item name="factor" label="系数" tooltip="默认 1" style={{ width: 110 }}>
              <InputNumber style={{ width: '100%' }} min={0} />
            </Form.Item>
            <Form.Item name="premium" label="加价" tooltip="默认 0" style={{ width: 110 }}>
              <InputNumber style={{ width: '100%' }} />
            </Form.Item>
          </div>
          <Alert
            type="info"
            message={
              <span>
                最终单价 = <b>取值结果 × 系数 + 加价</b>（先乘后加）。<br />
                基准日 = <b>报价单的创建日期</b>，不是今天 —— 同一张单以后重开，价格不会自己变。
              </span>
            }
          />
        </Form>
      </Card>

      {/* 卡片 2：元素级例外 */}
      <Card
        size="small"
        title={
          <Space size={8}>
            <span>元素级例外</span>
            <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', fontWeight: 400 }}>对个别元素覆盖上面的默认规则</span>
          </Space>
        }
        extra={
          <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => { setEditingException(null); setExceptionDrawerOpen(true); }}>
            新增例外
          </Button>
        }
      >
        <SelectableTable<StrategyDTO>
          rowKey="id"
          columns={[
            { title: '元素', dataIndex: 'elementCode', render: (v: string, r) => <span>{v} <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>{r.elementName}</span></span> },
            { title: '价格源', dataIndex: 'sourceName' },
            {
              title: '取值方式', dataIndex: 'method',
              render: (v: PriceMethod) => (
                <span style={{ display: 'inline-block', padding: '0 7px', borderRadius: 4, fontSize: 12, color: '#722ed1', background: '#f9f0ff', border: '1px solid #d3adf7' }}>
                  {formatMethod(v)}
                </span>
              ),
            },
            { title: '窗口', dataIndex: 'windowNum', render: (_: unknown, r) => <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>{formatWindow(r.windowNum, r.windowUnit)}</span> },
            { title: '系数', dataIndex: 'factor', align: 'right' as const, render: (v: number) => v.toFixed(2) },
            { title: '加价', dataIndex: 'premium', align: 'right' as const, render: (v: number) => v.toFixed(2) },
            { title: '最后变更时间', dataIndex: 'updatedAt', render: (v: string) => <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>{dayjs(v).format('YYYY-MM-DD HH:mm')}</span> },
            { title: '变更用户', dataIndex: 'updatedByName', render: (v: string) => <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>{v}</span> },
          ]}
          dataSource={bundle?.exceptions ?? []}
          loading={loading}
          pagination={false}
          actions={exceptionActions}
          rowLabel={(r) => `${r.elementCode} ${r.elementName ?? ''}`.trim()}
        />
        <div style={{ marginTop: 10, fontSize: 12, color: 'rgba(0,0,0,.45)' }}>
          未列在此处的元素，一律走上方「客户级默认策略」。
        </div>
      </Card>

      <Alert
        style={{ marginTop: 18 }}
        type="warning"
        showIcon
        message="没配策略的客户：报价单元素单价留空，由销售手填。系统不设全局默认策略，避免「没配却拿到一个不知哪来的价」。"
      />

      <StrategyExceptionEditDrawer
        open={exceptionDrawerOpen}
        customerNo={customerNo}
        editing={editingException}
        sources={sources}
        onClose={() => setExceptionDrawerOpen(false)}
        onSaved={() => { setExceptionDrawerOpen(false); refresh(); }}
      />
      <StrategySimulateDrawer
        open={simulateOpen}
        onClose={() => setSimulateOpen(false)}
        customerNo={customerNo}
        customerLabel={customerLabel}
        getDraft={getSimulateDraft}
      />
      <StrategyHistoryDrawer
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
        customerNo={customerNo}
        customerLabel={customerLabel}
        exceptionElements={exceptionElements}
      />
    </div>
  );
};

export default ElementPriceStrategyTab;
