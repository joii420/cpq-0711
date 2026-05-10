import React, { useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Collapse,
  Descriptions,
  Drawer,
  Popconfirm,
  Radio,
  Space,
  Tag,
  Typography,
} from 'antd';
import type { OrphanRowDTO, ResolutionDTO } from '../../types/import-v5';

const { Text } = Typography;
const { Panel } = Collapse;

// 表名友好标签
const TABLE_LABELS: Record<string, string> = {
  mat_fee: '费用明细',
  mat_process: '加工工序',
};

function tableLabel(name: string): string {
  return TABLE_LABELS[name] ?? name;
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

// 孤儿行唯一键
function orphanKey(o: OrphanRowDTO): string {
  return `${o.tableName}|${o.rowKey}`;
}

// ────────────────────────────────────────────────
// Props
// ────────────────────────────────────────────────
interface OrphanRowsDrawerProps {
  open: boolean;
  orphans: OrphanRowDTO[];
  onClose: () => void;
  onConfirm: (resolutions: ResolutionDTO[]) => void;
}

const OrphanRowsDrawer: React.FC<OrphanRowsDrawerProps> = ({
  open,
  orphans,
  onClose,
  onConfirm,
}) => {
  // 每条孤儿的决策：key -> 'DELETE_ORPHAN' | 'KEEP_ORPHAN'
  // 默认全部 DELETE_ORPHAN（孤儿行通常是脏数据，业务默认删除）
  const [decisions, setDecisions] = useState<Map<string, 'DELETE_ORPHAN' | 'KEEP_ORPHAN'>>(
    new Map()
  );

  // 按 partNo 分组
  const grouped = useMemo(() => {
    const map = new Map<string, OrphanRowDTO[]>();
    orphans.forEach((o) => {
      const list = map.get(o.partNo) ?? [];
      list.push(o);
      map.set(o.partNo, list);
    });
    return map;
  }, [orphans]);

  function getDecision(o: OrphanRowDTO): 'DELETE_ORPHAN' | 'KEEP_ORPHAN' {
    return decisions.get(orphanKey(o)) ?? 'DELETE_ORPHAN';
  }

  function setDecision(o: OrphanRowDTO, d: 'DELETE_ORPHAN' | 'KEEP_ORPHAN') {
    setDecisions((prev) => {
      const next = new Map(prev);
      next.set(orphanKey(o), d);
      return next;
    });
  }

  // 全选删除
  function handleSelectAllDelete() {
    const next = new Map<string, 'DELETE_ORPHAN' | 'KEEP_ORPHAN'>();
    orphans.forEach((o) => next.set(orphanKey(o), 'DELETE_ORPHAN'));
    setDecisions(next);
  }

  // 全选保留
  function handleSelectAllKeep() {
    const next = new Map<string, 'DELETE_ORPHAN' | 'KEEP_ORPHAN'>();
    orphans.forEach((o) => next.set(orphanKey(o), 'KEEP_ORPHAN'));
    setDecisions(next);
  }

  // 统计（依赖 decisions 状态）
  const { deleteCount, keepCount } = useMemo(() => {
    const del = orphans.filter((o) => getDecision(o) === 'DELETE_ORPHAN').length;
    return { deleteCount: del, keepCount: orphans.length - del };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orphans, decisions]);

  // 提交：构建 ResolutionDTO 数组
  function handleConfirm() {
    const resolutions: ResolutionDTO[] = orphans.map((o) => ({
      type: 'ORPHAN_ROW' as const,
      tableName: o.tableName,
      rowKey: o.rowKey,
      fieldName: null,
      decision: getDecision(o),
      note: null,
    }));
    onConfirm(resolutions);
  }

  const drawerFooter = (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
      <Space>
        <Button size="small" danger onClick={handleSelectAllDelete}>
          全选删除
        </Button>
        <Button size="small" onClick={handleSelectAllKeep}>
          全选保留
        </Button>
        <Text type="secondary" style={{ fontSize: 12 }}>
          将删除 {deleteCount} 条，保留 {keepCount} 条
        </Text>
      </Space>
      <Space>
        <Button onClick={onClose}>取消</Button>
        <Button type="primary" onClick={handleConfirm}>
          确认处理
        </Button>
      </Space>
    </div>
  );

  return (
    <Drawer
      title="孤儿行处理（UI-3）"
      placement="right"
      width={960}
      open={open}
      onClose={onClose}
      closeIcon={
        <Popconfirm
          title="未保存的决策将丢失，确认关闭？"
          onConfirm={onClose}
          okText="确认关闭"
          cancelText="继续编辑"
          placement="bottomLeft"
        >
          <span>×</span>
        </Popconfirm>
      }
      footer={drawerFooter}
      destroyOnClose
    >
      {/* 说明 */}
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
        message={
          <span>
            以下 <Text strong>{orphans.length}</Text> 行存在于数据库中，但本次 Excel 没有覆盖。请选择处理方式：
          </span>
        }
        description="「删除」将物理删除该行数据；「保留」则跳过不处理，保持现状。默认选中删除（推荐清理孤儿数据）。"
      />

      {/* 按料号分组 Collapse */}
      <Collapse defaultActiveKey={Array.from(grouped.keys())}>
        {Array.from(grouped.entries()).map(([partNo, rows]) => (
          <Panel
            key={partNo}
            header={
              <Space>
                <Text strong>料号 {partNo}</Text>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  — {rows.length} 条孤儿行
                </Text>
              </Space>
            }
          >
            <Space direction="vertical" style={{ width: '100%' }} size={12}>
              {rows.map((o) => {
                const key = orphanKey(o);
                const dec = getDecision(o);
                return (
                  <Card
                    key={key}
                    size="small"
                    style={{
                      borderLeft: `4px solid ${dec === 'DELETE_ORPHAN' ? '#ff4d4f' : '#1677ff'}`,
                      background: dec === 'DELETE_ORPHAN' ? '#fff2f0' : '#f0f5ff',
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
                          <Tag color={o.tableName === 'mat_fee' ? 'orange' : 'blue'}>
                            {tableLabel(o.tableName)}
                          </Tag>
                          <Text strong style={{ fontSize: 13 }}>
                            {o.displayLabel}
                          </Text>
                        </Space>
                        <div style={{ marginTop: 8 }}>{renderSnapshot(o.rowSnapshot)}</div>
                      </div>
                      <div style={{ flexShrink: 0 }}>
                        <Radio.Group
                          value={dec}
                          onChange={(e) => setDecision(o, e.target.value)}
                          optionType="button"
                          buttonStyle="solid"
                          size="small"
                        >
                          <Radio.Button
                            value="DELETE_ORPHAN"
                            style={
                              dec === 'DELETE_ORPHAN'
                                ? { background: '#ff4d4f', borderColor: '#ff4d4f', color: '#fff' }
                                : {}
                            }
                          >
                            删除
                          </Radio.Button>
                          <Radio.Button
                            value="KEEP_ORPHAN"
                            style={
                              dec === 'KEEP_ORPHAN'
                                ? { background: '#1677ff', borderColor: '#1677ff', color: '#fff' }
                                : {}
                            }
                          >
                            保留
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
    </Drawer>
  );
};

export default OrphanRowsDrawer;
