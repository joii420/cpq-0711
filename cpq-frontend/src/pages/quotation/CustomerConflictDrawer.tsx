import React, { useMemo } from 'react';
import {
  Alert,
  Button,
  Collapse,
  Drawer,
  Popconfirm,
  Space,
  Typography,
} from 'antd';
import type {
  ConflictFieldDTO,
  CustomerDataConflictDTO,
  Decision,
  Importance,
  ResolutionDTO,
} from '../../types/import-v5';
import DiffRowItem from './DiffRowItem';

const { Text } = Typography;
const { Panel } = Collapse;

interface CustomerConflictDrawerProps {
  open: boolean;
  conflicts: CustomerDataConflictDTO[];
  resolutions: Map<string, ResolutionDTO>;
  onChange: (key: string, res: ResolutionDTO) => void;
  onConfirm: () => void;
  onCancel: () => void;
}

function conflictFieldKey(
  conflict: CustomerDataConflictDTO,
  field: ConflictFieldDTO
): string {
  return `${conflict.tableName}|${conflict.rowKey}|${field.fieldName}`;
}

const TABLE_LABELS: Record<string, string> = {
  customer_pricing: '客户定价',
  customer_discount: '客户折扣',
  customer_rebate: '客户返利',
  customer_lead_time: '客户交期',
};

function tableLabel(name: string): string {
  return TABLE_LABELS[name] ?? name;
}

const CustomerConflictDrawer: React.FC<CustomerConflictDrawerProps> = ({
  open,
  conflicts,
  resolutions,
  onChange,
  onConfirm,
  onCancel,
}) => {
  // 统计所有字段
  const allFields = useMemo(() => {
    const fields: { conflict: CustomerDataConflictDTO; field: ConflictFieldDTO }[] = [];
    conflicts.forEach((c) => c.fields.forEach((f) => fields.push({ conflict: c, field: f })));
    return fields;
  }, [conflicts]);

  const stats = useMemo(() => {
    const counts: Record<Importance, number> = { CRITICAL: 0, IMPORTANT: 0, NORMAL: 0 };
    allFields.forEach(({ field }) => counts[field.importance]++);
    return counts;
  }, [allFields]);

  // 全部采纳新值
  const handleAcceptAll = () => {
    allFields.forEach(({ conflict, field }) => {
      const key = conflictFieldKey(conflict, field);
      const existing = resolutions.get(key);
      onChange(key, {
        type: 'CUSTOMER_CONFLICT',
        tableName: conflict.tableName,
        rowKey: conflict.rowKey,
        fieldName: field.fieldName,
        decision: 'ACCEPT_NEW',
        note: existing?.note ?? '',
        oldValueAtPreview: field.existingValue,
      });
    });
  };

  // 确认禁用判断
  const missingNoteFields = useMemo(() => {
    return allFields.filter(({ conflict, field }) => {
      if (field.importance !== 'CRITICAL') return false;
      const key = conflictFieldKey(conflict, field);
      const res = resolutions.get(key);
      return res?.decision === 'ACCEPT_NEW' && !res.note?.trim();
    });
  }, [allFields, resolutions]);

  const confirmDisabled = missingNoteFields.length > 0;
  const tooltipContent = confirmDisabled
    ? `以下关键字段未填备注：${missingNoteFields.map(({ field }) => field.fieldLabel).join('、')}`
    : '';

  const handleDecisionChange = (
    conflict: CustomerDataConflictDTO,
    field: ConflictFieldDTO,
    decision: Decision
  ) => {
    const key = conflictFieldKey(conflict, field);
    const existing = resolutions.get(key);
    onChange(key, {
      type: 'CUSTOMER_CONFLICT',
      tableName: conflict.tableName,
      rowKey: conflict.rowKey,
      fieldName: field.fieldName,
      decision,
      note: existing?.note ?? '',
      oldValueAtPreview: field.existingValue,
    });
  };

  const handleNoteChange = (
    conflict: CustomerDataConflictDTO,
    field: ConflictFieldDTO,
    note: string
  ) => {
    const key = conflictFieldKey(conflict, field);
    const existing = resolutions.get(key);
    onChange(key, {
      type: 'CUSTOMER_CONFLICT',
      tableName: conflict.tableName,
      rowKey: conflict.rowKey,
      fieldName: field.fieldName,
      decision: existing?.decision ?? 'KEEP_OLD',
      note,
      oldValueAtPreview: field.existingValue,
    });
  };

  const drawerFooter = (
    <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
      <Button onClick={onCancel}>取消</Button>
      <Button
        type="primary"
        disabled={confirmDisabled}
        onClick={onConfirm}
        title={tooltipContent}
      >
        确认导入
      </Button>
    </div>
  );

  return (
    <Drawer
      title="客户数据冲突确认（UI-1）"
      placement="right"
      width={1200}
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
      {/* 冲突总览 */}
      <Alert
        type="error"
        showIcon
        style={{ marginBottom: 16 }}
        message={
          <span>
            检测到 <Text strong>{conflicts.length}</Text> 个客户数据冲突，共{' '}
            <Text strong>{allFields.length}</Text> 个字段需要确认：
            {stats.CRITICAL > 0 && (
              <Text strong style={{ color: '#cf1322' }}>
                {' '}
                关键 {stats.CRITICAL} 项
              </Text>
            )}
            {stats.IMPORTANT > 0 && (
              <Text strong style={{ color: '#d46b08' }}>
                {' '}
                重要 {stats.IMPORTANT} 项
              </Text>
            )}
            {stats.NORMAL > 0 && <Text> 普通 {stats.NORMAL} 项</Text>}
            。
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

      {/* 按"料号 × 表"分组 Collapse */}
      <Collapse defaultActiveKey={conflicts.map((c) => `${c.rowKey}-${c.tableName}`)}>
        {conflicts.map((conflict) => {
          const panelKey = `${conflict.rowKey}-${conflict.tableName}`;
          return (
            <Panel
              key={panelKey}
              header={
                <Space>
                  <Text strong>料号 {conflict.hfPartNo}</Text>
                  <Text type="secondary">×</Text>
                  <Text>{tableLabel(conflict.tableName)}</Text>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    ({conflict.tableName}) — {conflict.fields.length} 个字段冲突
                  </Text>
                </Space>
              }
            >
              {conflict.fields.map((field) => {
                const key = conflictFieldKey(conflict, field);
                const res = resolutions.get(key);
                return (
                  <DiffRowItem
                    key={key}
                    importance={field.importance}
                    affectsCalculation={field.affectsCalculation}
                    fieldLabel={field.fieldLabel}
                    oldValue={field.existingValue}
                    newValue={field.importValue}
                    decision={res?.decision ?? 'KEEP_OLD'}
                    note={res?.note ?? ''}
                    onDecisionChange={(d) => handleDecisionChange(conflict, field, d)}
                    onNoteChange={(n) => handleNoteChange(conflict, field, n)}
                  />
                );
              })}
            </Panel>
          );
        })}
      </Collapse>
    </Drawer>
  );
};

export default CustomerConflictDrawer;
