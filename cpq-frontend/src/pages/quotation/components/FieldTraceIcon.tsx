// ============================================================
// components/FieldTraceIcon.tsx
// 字段追溯 ⓘ 图标组件
// - DRAFT：绿色 + tooltip "实时数据"
// - SUBMITTED：黄色 + Popover（懒加载 field-trace API）
// ============================================================

import React, { useState, useCallback } from 'react';
import { Popover, Tooltip } from 'antd';
import { InfoCircleOutlined } from '@ant-design/icons';
import { quotationSnapshotService } from '../../../services/quotationSnapshotService';
import type { FieldTraceDTO } from '../../../types/quotation-snapshot';
import FieldTracePopoverContent from './FieldTracePopover';

interface FieldTraceIconProps {
  /** 报价单 ID */
  quotationId: string;
  /** 字段路径，如 lineItems[0].componentData[1].rowData.unit_price */
  fieldPath: string;
  /** true = DRAFT（绿色实时），false = SUBMITTED（黄色快照） */
  isDraft: boolean;
}

const FieldTraceIcon: React.FC<FieldTraceIconProps> = ({
  quotationId,
  fieldPath,
  isDraft,
}) => {
  const [traceData, setTraceData] = useState<FieldTraceDTO | null>(null);
  const [traceLoading, setTraceLoading] = useState(false);
  const [popoverOpen, setPopoverOpen] = useState(false);

  const loadTrace = useCallback(async () => {
    if (traceData || traceLoading) return;
    setTraceLoading(true);
    try {
      const res = await quotationSnapshotService.getFieldTrace(quotationId, fieldPath);
      setTraceData(res.data);
    } catch {
      // 追溯失败不影响主流程，静默处理
    } finally {
      setTraceLoading(false);
    }
  }, [quotationId, fieldPath, traceData, traceLoading]);

  const handlePopoverOpenChange = (open: boolean) => {
    setPopoverOpen(open);
    if (open && !traceData) {
      loadTrace();
    }
  };

  const iconStyle: React.CSSProperties = {
    color: isDraft ? '#52c41a' : '#faad14',
    cursor: 'pointer',
    fontSize: 13,
    marginLeft: 4,
    flexShrink: 0,
  };

  // DRAFT：简单 Tooltip
  if (isDraft) {
    return (
      <Tooltip title="实时数据（草稿状态）" placement="top">
        <InfoCircleOutlined style={iconStyle} />
      </Tooltip>
    );
  }

  // SUBMITTED：Popover（懒加载）
  return (
    <Popover
      open={popoverOpen}
      onOpenChange={handlePopoverOpenChange}
      trigger="click"
      placement="rightTop"
      content={
        <FieldTracePopoverContent
          trace={traceData}
          loading={traceLoading}
          onClosePopover={() => setPopoverOpen(false)}
        />
      }
      title={
        <span style={{ color: '#faad14', fontSize: 13 }}>
          快照数据来源追溯
        </span>
      }
    >
      <InfoCircleOutlined style={iconStyle} />
    </Popover>
  );
};

export default FieldTraceIcon;
