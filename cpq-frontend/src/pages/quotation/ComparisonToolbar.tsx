/**
 * ComparisonToolbar —— 比对视图工具栏（task-0717）。
 * "+ 新增比对"（readonly 隐藏）/ "差异料号" 开关（排序前置，非过滤）/ 料号子串过滤框。
 * 无导出按钮（本期不做，见 api.md §0.4 / fronttask §6）。
 * 对齐 prototype-比对视图.html「cq-toolbar」。
 */
import React from 'react';
import { Button, Switch, Input, Space } from 'antd';
import { SearchOutlined, PlusOutlined } from '@ant-design/icons';

export interface ComparisonToolbarProps {
  readonly: boolean;
  onlyDiff: boolean;
  onToggleOnlyDiff: (checked: boolean) => void;
  filterText: string;
  onFilterChange: (text: string) => void;
  onAddCompare: () => void;
  totalParts: number;
  totalColumns: number;
}

export const ComparisonToolbar: React.FC<ComparisonToolbarProps> = ({
  readonly, onlyDiff, onToggleOnlyDiff, filterText, onFilterChange, onAddCompare, totalParts, totalColumns,
}) => {
  return (
    <>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, margin: '0 24px 14px', flexWrap: 'wrap' }}>
        {!readonly && (
          <Button type="primary" icon={<PlusOutlined />} onClick={onAddCompare}>
            新增比对
          </Button>
        )}
        <Space size={6}>
          <span>差异料号</span>
          <Switch checked={onlyDiff} onChange={onToggleOnlyDiff} size="small" />
        </Space>
        <Input
          allowClear
          prefix={<SearchOutlined style={{ color: 'rgba(0,0,0,.35)' }} />}
          placeholder="输入销售料号过滤"
          value={filterText}
          onChange={(e) => onFilterChange(e.target.value)}
          style={{ width: 240, marginLeft: 'auto' }}
        />
      </div>
      <div style={{ margin: '0 24px 10px', fontSize: 12.5, color: 'rgba(0,0,0,.45)' }}>
        共 {totalParts} 个销售料号，{totalColumns} 个比对列
        {readonly && '（只读：仅展示当前入口已保存的比对配置）'}
      </div>
    </>
  );
};

export default ComparisonToolbar;
