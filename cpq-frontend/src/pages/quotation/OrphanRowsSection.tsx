// V6 导入向导 Step 2 区块 C — 孤儿行处理（内嵌 Section，无 Drawer 壳）
// 对应旧 OrphanRowsDrawer.tsx 的内容主体，直接内嵌于 BasicDataImportV5Wizard Step 2
import React from 'react';
import {
  Alert,
  Button,
  Card,
  Collapse,
  Descriptions,
  Radio,
  Space,
  Tag,
  Typography,
} from 'antd';
import type { OrphanItem, OrphanAction } from '../../types/import-v6';

const { Text } = Typography;
const { Panel } = Collapse;

// Sheet 名称友好标签
const SHEET_LABELS: Record<string, string> = {
  bom: 'BOM',
  process: '工艺',
  fee: '费用',
  plating_fee: '镀层费用',
  plating_plan: '镀层方案',
};

function sheetLabel(key: string): string {
  return SHEET_LABELS[key] ?? key;
}

// 快照字段友好显示（过滤空值和内部字段）
const SNAPSHOT_SKIP_KEYS = new Set(['id', 'created_at', 'updated_at', 'is_current', 'version']);

function renderSnapshot(snapshot: Record<string, any>) {
  const entries = Object.entries(snapshot).filter(
    ([k, v]) => !SNAPSHOT_SKIP_KEYS.has(k) && v !== null && v !== undefined && v !== ''
  );
  if (entries.length === 0) return <Text type="secondary">无快照数据</Text>;
  return (
    <Descriptions size="small" column={2} bordered>
      {entries.map(([k, v]) => (
        <Descriptions.Item key={k} label={k}>
          {String(v)}
        </Descriptions.Item>
      ))}
    </Descriptions>
  );
}

interface Props {
  items: OrphanItem[];
  onChange: (key: string, action: OrphanAction) => void;
}

const OrphanRowsSection: React.FC<Props> = ({ items, onChange }) => {
  if (items.length === 0) {
    return (
      <Alert
        type="success"
        showIcon
        message="无孤儿行"
        description="DB 中无多余行（所有行均被本次 Excel 覆盖）。"
      />
    );
  }

  // 全选丢弃
  function handleDiscardAll() {
    items.forEach((item) => onChange(item.key, 'DISCARD'));
  }

  // 全选创建新
  function handleCreateAll() {
    items.forEach((item) => onChange(item.key, 'CREATE_NEW'));
  }

  // 按 sheetCode 分组
  const grouped = new Map<string, OrphanItem[]>();
  items.forEach((item) => {
    const list = grouped.get(item.sheetCode) ?? [];
    list.push(item);
    grouped.set(item.sheetCode, list);
  });

  const discardCount = items.filter((i) => i.action === 'DISCARD').length;
  const keepCount = items.length - discardCount;

  return (
    <div>
      {/* 说明 */}
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 12 }}
        message={
          <span>
            以下 <Text strong>{items.length}</Text> 行存在于数据库中，但本次 Excel 没有覆盖。请选择处理方式：
          </span>
        }
        description="「丢弃」将物理删除该行数据；「创建新」则以新料号形式保留。默认选中丢弃。"
      />

      {/* 批量操作 */}
      <div style={{ marginBottom: 12 }}>
        <Space>
          <Button size="small" danger onClick={handleDiscardAll}>全选丢弃</Button>
          <Button size="small" onClick={handleCreateAll}>全选创建新</Button>
          <Text type="secondary" style={{ fontSize: 12 }}>
            将丢弃 {discardCount} 条，创建 {keepCount} 条
          </Text>
        </Space>
      </div>

      {/* 按 sheet 分组 Collapse */}
      <Collapse defaultActiveKey={Array.from(grouped.keys())}>
        {Array.from(grouped.entries()).map(([sheet, rows]) => (
          <Panel
            key={sheet}
            header={
              <Space>
                <Text strong>{sheetLabel(sheet)}</Text>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  — {rows.length} 条孤儿行
                </Text>
              </Space>
            }
          >
            <Space direction="vertical" style={{ width: '100%' }} size={10}>
              {rows.map((item) => {
                const dec = item.action;
                const isDiscard = dec === 'DISCARD';
                return (
                  <Card
                    key={item.key}
                    size="small"
                    style={{
                      borderLeft: `4px solid ${isDiscard ? '#ff4d4f' : '#1677ff'}`,
                      background: isDiscard ? '#fff2f0' : '#f0f5ff',
                    }}
                    styles={{ body: { padding: '12px 16px' } }}
                  >
                    <div
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'flex-start',
                        gap: 12,
                      }}
                    >
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <Space style={{ marginBottom: 8 }} wrap>
                          <Tag color="blue">{sheetLabel(item.sheetCode)}</Tag>
                          <Text strong style={{ fontSize: 13 }}>
                            行 {item.rowIndex}
                          </Text>
                          {item.description && (
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              {item.description}
                            </Text>
                          )}
                        </Space>
                        <div style={{ marginTop: 8 }}>
                          {renderSnapshot(item.rowSnapshot)}
                        </div>
                      </div>
                      <div style={{ flexShrink: 0 }}>
                        <Radio.Group
                          value={dec}
                          onChange={(e) => onChange(item.key, e.target.value as OrphanAction)}
                          optionType="button"
                          buttonStyle="solid"
                          size="small"
                        >
                          <Radio.Button
                            value="DISCARD"
                            style={
                              isDiscard
                                ? { background: '#ff4d4f', borderColor: '#ff4d4f', color: '#fff' }
                                : {}
                            }
                          >
                            丢弃
                          </Radio.Button>
                          <Radio.Button
                            value="CREATE_NEW"
                            style={
                              dec === 'CREATE_NEW'
                                ? { background: '#1677ff', borderColor: '#1677ff', color: '#fff' }
                                : {}
                            }
                          >
                            创建新
                          </Radio.Button>
                        </Radio.Group>
                      </div>
                    </div>
                  </Card>
                );
              })}
            </Space>
          </Panel>
        ))}
      </Collapse>
    </div>
  );
};

export default OrphanRowsSection;
