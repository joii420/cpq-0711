import React from 'react';
import {
  Button,
  Card,
  Col,
  DatePicker,
  Input,
  Row,
  Select,
  Space,
} from 'antd';
import type { Dayjs } from 'dayjs';
import type { ChangeLogSearchParams } from '../../services/changeLogService';

const { Option } = Select;
const { RangePicker } = DatePicker;
const { Search } = Input;

// 数据表选项
const TABLE_OPTIONS = [
  { value: 'mat_fee', label: '费用主档（mat_fee）' },
  { value: 'mat_process', label: '加工工序（mat_process）' },
  { value: 'plating_fee', label: '电镀费用（plating_fee）' },
];

const IMPORTANCE_OPTIONS = [
  { value: 'CRITICAL', label: '关键', color: '#f5222d' },
  { value: 'IMPORTANT', label: '重要', color: '#fa8c16' },
  { value: 'NORMAL', label: '普通', color: '#8c8c8c' },
];

const CHANGE_SOURCE_OPTIONS = [
  { value: 'V5_IMPORT', label: 'V5 导入' },
  { value: 'MANUAL_EDIT', label: '手动编辑' },
  { value: 'SYSTEM_INIT', label: '系统初始化' },
  { value: 'SYNC', label: '数据同步' },
];

// mock 客户列表
const MOCK_CUSTOMERS = [
  { id: 'cust-001', name: '华为技术有限公司' },
  { id: 'cust-002', name: '中兴通讯股份有限公司' },
  { id: 'cust-003', name: '小米科技有限公司' },
];

export interface ChangeLogFiltersValue {
  customerId?: string;
  hfPartNo?: string;
  tableName?: string;
  fieldName?: string;
  importanceList?: string[];
  changeSourceList?: string[];
  timeRange?: [Dayjs, Dayjs] | null;
}

interface ChangeLogFiltersProps {
  value: ChangeLogFiltersValue;
  onChange: (val: ChangeLogFiltersValue) => void;
  onSearch: () => void;
  loading?: boolean;
}

const ChangeLogFilters: React.FC<ChangeLogFiltersProps> = ({
  value,
  onChange,
  onSearch,
  loading,
}) => {
  const update = (patch: Partial<ChangeLogFiltersValue>) =>
    onChange({ ...value, ...patch });

  const handleReset = () => {
    onChange({
      customerId: undefined,
      hfPartNo: '',
      tableName: undefined,
      fieldName: '',
      importanceList: [],
      changeSourceList: [],
      timeRange: null,
    });
  };

  return (
    <Card style={{ marginBottom: 16 }}>
      <Row gutter={[16, 12]}>
        <Col xs={24} sm={12} md={6}>
          <Select
            placeholder="客户"
            allowClear
            style={{ width: '100%' }}
            value={value.customerId}
            onChange={(v) => update({ customerId: v })}
          >
            {MOCK_CUSTOMERS.map((c) => (
              <Option key={c.id} value={c.id}>{c.name}</Option>
            ))}
          </Select>
        </Col>
        <Col xs={24} sm={12} md={6}>
          <Input
            placeholder="料号（HF 料号）"
            allowClear
            value={value.hfPartNo}
            onChange={(e) => update({ hfPartNo: e.target.value })}
          />
        </Col>
        <Col xs={24} sm={12} md={6}>
          <Select
            placeholder="数据表"
            allowClear
            style={{ width: '100%' }}
            value={value.tableName}
            onChange={(v) => update({ tableName: v })}
          >
            {TABLE_OPTIONS.map((o) => (
              <Option key={o.value} value={o.value}>{o.label}</Option>
            ))}
          </Select>
        </Col>
        <Col xs={24} sm={12} md={6}>
          <Input
            placeholder="字段名"
            allowClear
            value={value.fieldName}
            onChange={(e) => update({ fieldName: e.target.value })}
          />
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Select
            mode="multiple"
            placeholder="重要性"
            allowClear
            style={{ width: '100%' }}
            value={value.importanceList}
            onChange={(v) => update({ importanceList: v })}
          >
            {IMPORTANCE_OPTIONS.map((o) => (
              <Option key={o.value} value={o.value}>
                <span style={{ color: o.color }}>{o.label}</span>
              </Option>
            ))}
          </Select>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Select
            mode="multiple"
            placeholder="变更来源"
            allowClear
            style={{ width: '100%' }}
            value={value.changeSourceList}
            onChange={(v) => update({ changeSourceList: v })}
          >
            {CHANGE_SOURCE_OPTIONS.map((o) => (
              <Option key={o.value} value={o.value}>{o.label}</Option>
            ))}
          </Select>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <RangePicker
            style={{ width: '100%' }}
            value={value.timeRange as any}
            onChange={(v) => update({ timeRange: v as any })}
            placeholder={['变更时间（起）', '变更时间（止）']}
          />
        </Col>
        <Col xs={24}>
          <Space>
            <Button type="primary" onClick={onSearch} loading={loading}>
              查询
            </Button>
            <Button onClick={handleReset}>重置</Button>
          </Space>
        </Col>
      </Row>
    </Card>
  );
};

export default ChangeLogFilters;
