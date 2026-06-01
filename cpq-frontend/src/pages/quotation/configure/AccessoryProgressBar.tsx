import React from 'react';
import { Tag, Tooltip } from 'antd';
import { CheckCircleFilled, EditOutlined, MinusCircleOutlined } from '@ant-design/icons';
import type { PartState } from '../ConfigureProductDrawer';

interface Props {
  parts: PartState[];
  currentIndex: number;
  /** 用户曾推进到的最远配件下标。index < furthestIndex 视为「已完成」可点跳回。 */
  furthestIndex: number;
  onJump: (idx: number) => void;
}

/**
 * chip 标签：已选 existing 料号 → 「料号 (配件N)」；自定义已选材质 → 「材质代码 (配件N)」；
 * 未配置 → 「配件N」。让用户一眼区分每个配件配了什么。
 */
function partChipLabel(p: PartState): string {
  if (p.selectedHfPartNo) return `${p.selectedHfPartNo} (${p.name})`;
  const mat = p.selectedRecipeCode ?? p.selectedRecipeSymbol;
  if (mat) return `${mat} (${p.name})`;
  return p.name;
}

/**
 * 顶部配件进度框（需求 #4）。
 * - 当前配件：高亮、不可点。
 * - 已完成配件（index < furthestIndex）：绿色对勾、可点跳回修改。
 * - 未开始配件（index >= furthestIndex 且非当前）：置灰、不可点。
 */
const AccessoryProgressBar: React.FC<Props> = ({ parts, currentIndex, furthestIndex, onJump }) => {
  if (parts.length <= 1) return null;
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 16 }}>
      {parts.map((p, i) => {
        const isCurrent = i === currentIndex;
        const isDone = i < furthestIndex;
        const clickable = isDone && !isCurrent;
        const icon = isCurrent
          ? <EditOutlined />
          : isDone
            ? <CheckCircleFilled style={{ color: '#52c41a' }} />
            : <MinusCircleOutlined style={{ color: '#bbb' }} />;
        const tip = isCurrent ? '当前配件' : clickable ? '点击跳回此配件修改' : '尚未配置';
        return (
          <Tooltip key={i} title={tip}>
            <Tag
              icon={icon}
              color={isCurrent ? 'processing' : isDone ? 'success' : 'default'}
              onClick={() => { if (clickable) onJump(i); }}
              style={{
                cursor: clickable ? 'pointer' : 'default',
                opacity: !isCurrent && !isDone ? 0.5 : 1,
                padding: '4px 10px',
                fontSize: 13,
              }}
            >
              {partChipLabel(p)}
            </Tag>
          </Tooltip>
        );
      })}
    </div>
  );
};

export default AccessoryProgressBar;
