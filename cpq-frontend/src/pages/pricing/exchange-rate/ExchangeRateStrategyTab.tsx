import React, { useEffect, useState } from 'react';
import { Alert, Button, Card, DatePicker, Drawer, Form, InputNumber, Select, Space, Tag, Typography, message } from 'antd';
import { CalculatorOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { currencyService } from '../../../services/currencyService';
import type { CurrencyDTO, ExchangeRateMethod, ExchangeRateStrategyDTO, ExchangeRateWindowUnit, ExchangeRateSimulationResult } from '../../../types/currency';

const { Text } = Typography;
const METHOD_OPTIONS: Array<{ value: ExchangeRateMethod; label: string }> = [
  { value: 'LATEST', label: '最新一条汇率' },
  { value: 'AVG', label: '平均汇率' },
  { value: 'MAX', label: '最高汇率' },
  { value: 'MIN', label: '最低汇率' },
  { value: 'SPECIFIC_DATE', label: '指定日期' },
  { value: 'PREVIOUS_MONTH_DAY', label: '上月某天' },
  { value: 'MONTH_DAY', label: '每月' },
  { value: 'PREVIOUS_MONTH_END', label: '上月最后一天' },
];
const UNITS: Array<{ value: ExchangeRateWindowUnit; label: string }> = [
  { value: 'DAY', label: '天' }, { value: 'WEEK', label: '周' }, { value: 'MONTH', label: '月' }, { value: 'YEAR', label: '年' },
];

interface Props { customerNo: string; customerLabel: string; }

const ExchangeRateStrategyTab: React.FC<Props> = ({ customerNo, customerLabel }) => {
  const [form] = Form.useForm();
  const method = Form.useWatch('method', form) as ExchangeRateMethod | undefined;
  const [currencies, setCurrencies] = useState<CurrencyDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [trialOpen, setTrialOpen] = useState(false);
  const [trial, setTrial] = useState<ExchangeRateSimulationResult | null>(null);
  const [trialLoading, setTrialLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const [currencyRes, strategy] = await Promise.all([currencyService.list({ enabled: true }), currencyService.getStrategy(customerNo)]);
      setCurrencies(currencyRes.data ?? []);
      if (strategy) {
        form.setFieldsValue({
          ...strategy,
          specificDate: strategy.specificDate ? dayjs(strategy.specificDate) : undefined,
        });
      } else {
        form.setFieldsValue({ method: 'LATEST' });
      }
    } catch (e: any) { message.error(e?.message ?? '加载汇率策略失败'); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [customerNo]);

  useEffect(() => {
    if (method === 'LATEST') form.setFieldsValue({ windowNum: undefined, windowUnit: undefined });
    if (method === 'PREVIOUS_MONTH_END') form.setFieldsValue({ dayOfMonth: undefined, specificDate: undefined });
    if (method === 'SPECIFIC_DATE') form.setFieldsValue({ dayOfMonth: undefined });
  }, [method, form]);

  const buildPayload = (): Omit<ExchangeRateStrategyDTO, 'customerNo' | 'id' | 'version' | 'updatedAt' | 'updatedByName'> => {
    const v = form.getFieldsValue();
    return {
      method: v.method,
      windowNum: ['AVG', 'MAX', 'MIN'].includes(v.method) ? v.windowNum : null,
      windowUnit: ['AVG', 'MAX', 'MIN'].includes(v.method) ? v.windowUnit : null,
      specificDate: v.method === 'SPECIFIC_DATE' && v.specificDate ? v.specificDate.format('YYYY-MM-DD') : null,
      dayOfMonth: ['PREVIOUS_MONTH_DAY', 'MONTH_DAY'].includes(v.method) ? v.dayOfMonth : null,
    };
  };

  const save = async () => {
    try { await form.validateFields(); setSaving(true); await currencyService.saveStrategy(customerNo, buildPayload()); message.success('汇率策略已保存，仅对后续新报价单生效'); }
    catch (e: any) { if (!e?.errorFields) message.error(e?.response?.data?.message ?? e?.message ?? '保存失败'); }
    finally { setSaving(false); }
  };

  const simulate = async () => {
    try {
      await form.validateFields();
      const targetCurrency = currencies.find(c => !c.isBase)?.code;
      if (!targetCurrency) { message.warning('请先维护目标币种'); return; }
      setTrialLoading(true); setTrialOpen(true);
      const result = await currencyService.simulate(customerNo, { ...buildPayload(), currency: targetCurrency, baseDate: dayjs().format('YYYY-MM-DD') });
      setTrial(result);
    } catch (e: any) { if (!e?.errorFields) setTrial({ method: method ?? 'LATEST', error: e?.response?.data?.message ?? e?.message ?? '试算失败' }); }
    finally { setTrialLoading(false); }
  };

  const dateMethod = method === 'SPECIFIC_DATE' || method === 'PREVIOUS_MONTH_DAY' || method === 'MONTH_DAY' || method === 'PREVIOUS_MONTH_END';
  return <div>
    <Alert type="info" showIcon message="一套客户汇率策略适用于全部目标币种；报价单创建时取一次并固化快照，策略变更不重算已有报价单。" style={{ marginBottom: 16 }} />
    <Card size="small" loading={loading} title={<Space><span>客户级汇率策略</span><Tag color="blue">{customerLabel}</Tag></Space>} extra={<Space><Button icon={<CalculatorOutlined />} onClick={simulate}>策略试算</Button><Button type="primary" loading={saving} onClick={save}>保存</Button></Space>}>
      <Form form={form} layout="vertical">
        <Space align="start" wrap size={16}>
          <Form.Item name="method" label="取值方式" rules={[{ required: true, message: '请选择取值方式' }]} style={{ width: 180 }}>
            <Select options={METHOD_OPTIONS} />
          </Form.Item>
          {['AVG', 'MAX', 'MIN'].includes(method ?? '') && <Form.Item label="窗口" required style={{ width: 260 }}>
            <Space.Compact style={{ width: '100%' }}>
              <Form.Item name="windowNum" noStyle rules={[{ required: true, message: '请输入窗口数' }]}><InputNumber min={1} precision={0} style={{ width: '52%' }} placeholder="窗口数" /></Form.Item>
              <Form.Item name="windowUnit" noStyle rules={[{ required: true, message: '请选择单位' }]}><Select style={{ width: '48%' }} options={UNITS} /></Form.Item>
            </Space.Compact>
          </Form.Item>}
          {method === 'LATEST' && <div style={{ paddingTop: 30, color: 'rgba(0,0,0,.45)' }}>无需填写，取目标日期当天最后更新的一条</div>}
          {method === 'SPECIFIC_DATE' && <Form.Item name="specificDate" label="日期" rules={[{ required: true, message: '请选择日期' }]}>
            <DatePicker disabledDate={(d) => d.isAfter(dayjs(), 'day')} />
          </Form.Item>}
          {(method === 'PREVIOUS_MONTH_DAY' || method === 'MONTH_DAY') && <Form.Item name="dayOfMonth" label="日期" rules={[{ required: true, message: '请输入 1~31 日' }]}>
            <InputNumber min={1} max={31} precision={0} addonAfter="日" />
          </Form.Item>}
          {method === 'PREVIOUS_MONTH_END' && <div style={{ paddingTop: 30, color: 'rgba(0,0,0,.45)' }}>无需填写，取上月最后一天</div>}
        </Space>
        {dateMethod && <Text type="secondary" style={{ display: 'block' }}>日期以报价单创建日为基准；短月没有该日时跳过该月继续向前找，命中日期无汇率则创建失败。</Text>}
      </Form>
    </Card>
    <Drawer title="汇率策略试算" open={trialOpen} onClose={() => setTrialOpen(false)} size="default">
      {trialLoading ? <div style={{ padding: 32, textAlign: 'center' }}>正在试算…</div> : trial?.error ? <Alert type="error" message={trial.error} /> : trial ? <Space direction="vertical" style={{ width: '100%' }}>
        <div>取值方式：{METHOD_OPTIONS.find(x => x.value === trial.method)?.label ?? trial.method}</div>
        {(trial.targetDate || trial.effectiveDate) && <div>解析目标日期：<b>{trial.targetDate || trial.effectiveDate}</b></div>}
        {trial.skippedMonths?.length ? <div>跳过月份：{trial.skippedMonths.join('、')}</div> : null}
        <div>当天有效汇率：{trial.matchedRate ?? trial.rate ?? '—'}</div>
        <div>参与记录数：{trial.participantCount ?? 0}</div>
        <div>最终汇率：<b>{trial.finalRate ?? trial.rate ?? '—'}</b></div>
        {trial.dailyRates?.length ? <Card size="small" title="窗口每日有效汇率">{trial.dailyRates.map(item => <div key={item.effectiveDate}>{item.effectiveDate}：{item.rate}</div>)}</Card> : null}
      </Space> : <Text type="secondary">填写策略后点击“策略试算”。</Text>}
    </Drawer>
  </div>;
};

export default ExchangeRateStrategyTab;
