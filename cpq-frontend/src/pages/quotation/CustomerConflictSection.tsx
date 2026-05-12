// V6 导入向导 Step 2 区块 B — 客户冲突决策（内嵌 Section，无 Drawer 壳）
// 对应旧 CustomerConflictDrawer.tsx 的内容主体，直接内嵌于 BasicDataImportV5Wizard Step 2
import React from 'react';
import {
  Alert,
  Button,
  Collapse,
  Radio,
  Space,
  Typography,
} from 'antd';
import type { CustomerConflictItem, CustomerConflictAction } from '../../types/import-v6';

const { Text } = Typography;
const { Panel } = Collapse;

const ACTION_LABEL: Record<CustomerConflictAction, string> = {
  USE_EXCEL: '用 Excel 值',
  USE_DB: '保留 DB 值',
  SKIP: '跳过',
};

const ACTION_COLOR: Record<CustomerConflictAction, string> = {
  USE_EXCEL: '#389e0d',
  USE_DB: '#0958d9',
  SKIP: '#8c8c8c',
};

interface Props {
  items: CustomerConflictItem[];
  onChange: (key: string, action: CustomerConflictAction) => void;
}

const CustomerConflictSection: React.FC<Props> = ({ items, onChange }) => {
  if (items.length === 0) {
    return (
      <Alert
        type="success"
        showIcon
        message="无客户料号冲突"
        description="本次 Excel 与 DB 中的客户映射数据一致，无需处理。"
      />
    );
  }

  // 全部使用 Excel 值
  function handleAcceptAllExcel() {
    items.forEach((item) => onChange(item.key, 'USE_EXCEL'));
  }

  // 全部保留 DB 值
  function handleKeepAllDb() {
    items.forEach((item) => onChange(item.key, 'USE_DB'));
  }

  return (
    <div>
      {/* 统计说明 */}
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 12 }}
        message={
          <span>
            检测到 <Text strong>{items.length}</Text> 个客户料号冲突，请逐一确认处理方式。
          </span>
        }
      />

      {/* 批量操作 */}
      <div style={{ marginBottom: 12 }}>
        <Space>
          <Button size="small" onClick={handleAcceptAllExcel}>全部用 Excel 值</Button>
          <Button size="small" onClick={handleKeepAllDb}>全部保留 DB 值</Button>
        </Space>
      </div>

      {/* 按冲突条目分组 Collapse */}
      <Collapse defaultActiveKey={items.map((i) => i.key)}>
        {items.map((item) => (
          <Panel
            key={item.key}
            header={
              <Space>
                <Text strong>{item.conflictType}</Text>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {item.primaryKey}
                </Text>
                <Text
                  style={{
                    fontSize: 12,
                    color: ACTION_COLOR[item.action],
                    fontWeight: 500,
                  }}
                >
                  [{ACTION_LABEL[item.action]}]
                </Text>
              </Space>
            }
          >
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'flex-start',
                gap: 12,
              }}
            >
              <div style={{ flex: 1 }}>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {item.description || '（无详细描述）'}
                </Text>
              </div>
              <Radio.Group
                value={item.action}
                onChange={(e) => onChange(item.key, e.target.value as CustomerConflictAction)}
                optionType="button"
                buttonStyle="solid"
                size="small"
              >
                <Radio.Button value="USE_EXCEL">用 Excel 值</Radio.Button>
                <Radio.Button value="USE_DB">保留 DB 值</Radio.Button>
                <Radio.Button value="SKIP">跳过</Radio.Button>
              </Radio.Group>
            </div>
          </Panel>
        ))}
      </Collapse>
    </div>
  );
};

export default CustomerConflictSection;
