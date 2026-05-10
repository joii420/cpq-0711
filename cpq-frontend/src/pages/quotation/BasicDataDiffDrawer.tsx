import React, { useMemo } from 'react';
import {
  Alert,
  Button,
  Collapse,
  Drawer,
  Popconfirm,
  Space,
  Table,
  Tooltip,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type {
  BasicDataDiffDTO,
  Decision,
  Importance,
  ResolutionDTO,
} from '../../types/import-v5';
import DiffRowItem from './DiffRowItem';

const { Text } = Typography;
const { Panel } = Collapse;

interface BasicDataDiffDrawerProps {
  open: boolean;
  diffs: BasicDataDiffDTO[];
  resolutions: Map<string, ResolutionDTO>;
  onChange: (key: string, res: ResolutionDTO) => void;
  onConfirm: () => void;
  onCancel: () => void;
}

function resKey(d: BasicDataDiffDTO): string {
  return `${d.tableName}|${d.rowKey}|${d.fieldName}`;
}

const TABLE_LABELS: Record<string, string> = {
  mat_part: '物料清单',
  process_cost: '工序成本',
  customer_pricing: '客户定价',
  base_material: '基础物料',
  labor_rate: '人工费率',
};

function tableLabel(name: string): string {
  return TABLE_LABELS[name] ?? name;
}

const BasicDataDiffDrawer: React.FC<BasicDataDiffDrawerProps> = ({
  open,
  diffs,
  resolutions,
  onChange,
  onConfirm,
  onCancel,
}) => {
  // 统计
  const stats = useMemo(() => {
    const counts: Record<Importance, number> = { CRITICAL: 0, IMPORTANT: 0, NORMAL: 0 };
    diffs.forEach((d) => counts[d.importance]++);
    return counts;
  }, [diffs]);

  // 按 tableName 分组
  const grouped = useMemo(() => {
    const map = new Map<string, BasicDataDiffDTO[]>();
    diffs.forEach((d) => {
      const list = map.get(d.tableName) ?? [];
      list.push(d);
      map.set(d.tableName, list);
    });
    return map;
  }, [diffs]);

  // 全部采纳新值
  const handleAcceptAll = () => {
    diffs.forEach((d) => {
      const key = resKey(d);
      const existing = resolutions.get(key);
      onChange(key, {
        type: 'BASIC_DIFF',
        tableName: d.tableName,
        rowKey: d.rowKey,
        fieldName: d.fieldName,
        decision: 'ACCEPT_NEW',
        note: existing?.note ?? '',
        oldValueAtPreview: d.oldValue,
      });
    });
  };

  // 下一步禁用判断
  const missingNoteFields = useMemo(() => {
    return diffs.filter((d) => {
      if (d.importance !== 'CRITICAL') return false;
      const key = resKey(d);
      const res = resolutions.get(key);
      return res?.decision === 'ACCEPT_NEW' && !res.note?.trim();
    });
  }, [diffs, resolutions]);

  const nextDisabled = missingNoteFields.length > 0;
  const tooltipContent = nextDisabled
    ? `以下关键字段未填备注：${missingNoteFields.map((d) => d.fieldLabel).join('、')}`
    : '';

  const handleDecisionChange = (diff: BasicDataDiffDTO, decision: Decision) => {
    const key = resKey(diff);
    const existing = resolutions.get(key);
    onChange(key, {
      type: 'BASIC_DIFF',
      tableName: diff.tableName,
      rowKey: diff.rowKey,
      fieldName: diff.fieldName,
      decision,
      note: existing?.note ?? '',
      oldValueAtPreview: diff.oldValue,
    });
  };

  const handleNoteChange = (diff: BasicDataDiffDTO, note: string) => {
    const key = resKey(diff);
    const existing = resolutions.get(key);
    onChange(key, {
      type: 'BASIC_DIFF',
      tableName: diff.tableName,
      rowKey: diff.rowKey,
      fieldName: diff.fieldName,
      decision: existing?.decision ?? 'KEEP_OLD',
      note,
      oldValueAtPreview: diff.oldValue,
    });
  };

  // Table 列（字段数 <= 5 时单列，> 5 时两列布局用 DiffRowItem 渲染）
  const renderTableGroup = (tableDiffs: BasicDataDiffDTO[]) => {
    if (tableDiffs.length > 5) {
      // 两列网格
      const columns: ColumnsType<BasicDataDiffDTO> = [
        {
          title: '',
          key: 'item',
          render: (_, record) => {
            const key = resKey(record);
            const res = resolutions.get(key);
            return (
              <DiffRowItem
                importance={record.importance}
                affectsCalculation={record.affectsCalculation}
                fieldLabel={record.fieldLabel}
                oldValue={record.oldValue}
                newValue={record.newValue}
                decision={res?.decision ?? 'KEEP_OLD'}
                note={res?.note ?? ''}
                onDecisionChange={(d) => handleDecisionChange(record, d)}
                onNoteChange={(n) => handleNoteChange(record, n)}
              />
            );
          },
        },
      ];
      // 拆成 2 列
      const pairs: [BasicDataDiffDTO, BasicDataDiffDTO | null][] = [];
      for (let i = 0; i < tableDiffs.length; i += 2) {
        pairs.push([tableDiffs[i], tableDiffs[i + 1] ?? null]);
      }
      return (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: '1fr 1fr',
            gap: 8,
          }}
        >
          {tableDiffs.map((d) => {
            const key = resKey(d);
            const res = resolutions.get(key);
            return (
              <DiffRowItem
                key={key}
                importance={d.importance}
                affectsCalculation={d.affectsCalculation}
                fieldLabel={d.fieldLabel}
                oldValue={d.oldValue}
                newValue={d.newValue}
                decision={res?.decision ?? 'KEEP_OLD'}
                note={res?.note ?? ''}
                onDecisionChange={(dec) => handleDecisionChange(d, dec)}
                onNoteChange={(n) => handleNoteChange(d, n)}
              />
            );
          })}
        </div>
      );
    }

    return tableDiffs.map((d) => {
      const key = resKey(d);
      const res = resolutions.get(key);
      return (
        <DiffRowItem
          key={key}
          importance={d.importance}
          affectsCalculation={d.affectsCalculation}
          fieldLabel={d.fieldLabel}
          oldValue={d.oldValue}
          newValue={d.newValue}
          decision={res?.decision ?? 'KEEP_OLD'}
          note={res?.note ?? ''}
          onDecisionChange={(dec) => handleDecisionChange(d, dec)}
          onNoteChange={(n) => handleNoteChange(d, n)}
        />
      );
    });
  };

  const drawerFooter = (
    <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
      <Button onClick={onCancel}>取消</Button>
      <Tooltip title={tooltipContent}>
        <Button
          type="primary"
          disabled={nextDisabled}
          onClick={onConfirm}
        >
          下一步
        </Button>
      </Tooltip>
    </div>
  );

  return (
    <Drawer
      title="基础数据差异确认（UI-2）"
      placement="right"
      width={960}
      open={open}
      onClose={onCancel}
      closeIcon={
        <Popconfirm
          title="未保存的决策将丢失，确认关闭？"
          onConfirm={onCancel}
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
      {/* 差异总览 */}
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
        message={
          <span>
            共检测到 <Text strong>{diffs.length}</Text> 条基础数据差异：
            {stats.CRITICAL > 0 && (
              <Text strong style={{ color: '#cf1322' }}>
                {' '}
                关键 {stats.CRITICAL} 条
              </Text>
            )}
            {stats.IMPORTANT > 0 && (
              <Text strong style={{ color: '#d46b08' }}>
                {' '}
                重要 {stats.IMPORTANT} 条
              </Text>
            )}
            {stats.NORMAL > 0 && (
              <Text>
                {' '}
                普通 {stats.NORMAL} 条
              </Text>
            )}
            。请逐条确认处理方式。
          </span>
        }
      />

      {/* 一键全部采纳 */}
      <div style={{ marginBottom: 16 }}>
        <Button onClick={handleAcceptAll}>全部采纳新值</Button>
        {stats.CRITICAL > 0 && (
          <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
            （关键字段仍需补充变更备注）
          </Text>
        )}
      </div>

      {/* 按表分组 Collapse */}
      <Collapse defaultActiveKey={Array.from(grouped.keys())}>
        {Array.from(grouped.entries()).map(([tableName, tableDiffs]) => (
          <Panel
            key={tableName}
            header={
              <Space>
                <Text strong>{tableLabel(tableName)}</Text>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  ({tableName}) — {tableDiffs.length} 条差异
                </Text>
              </Space>
            }
          >
            {renderTableGroup(tableDiffs)}
          </Panel>
        ))}
      </Collapse>
    </Drawer>
  );
};

export default BasicDataDiffDrawer;
