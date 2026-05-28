import React from 'react';
import { Card } from 'antd';

export type StatCardTone = 'primary' | 'purple' | 'orange' | 'success' | 'gray' | 'red';

const TONE_COLOR: Record<StatCardTone, string> = {
  primary: '#1890ff',
  purple:  '#722ed1',
  orange:  '#fa8c16',
  success: '#52c41a',
  gray:    '#909399',
  red:     '#f5222d',
};

interface StatCardProps {
  tone: StatCardTone;
  icon?: React.ReactNode;          // 一个 emoji 或 antd Icon
  label: string;
  value: React.ReactNode;          // 数字 / 字符串 / 自定义节点
  sub?: React.ReactNode;           // 副标题 / 上下浮动
  onClick?: () => void;
  style?: React.CSSProperties;
}

/**
 * 统一统计卡（按原型 v0.4 视觉规范）
 *   - 左侧 3px 色边
 *   - 大 emoji 图标
 *   - label / value / sub 三层文字
 *   - hover 提升阴影
 */
export const StatCard: React.FC<StatCardProps> = ({ tone, icon, label, value, sub, onClick, style }) => {
  const color = TONE_COLOR[tone];
  return (
    <Card
      size="small"
      hoverable={!!onClick}
      onClick={onClick}
      style={{
        borderLeft: `3px solid ${color}`,
        cursor: onClick ? 'pointer' : 'default',
        ...style,
      }}
      styles={{ body: { display: 'flex', alignItems: 'center', gap: 12, padding: '12px 16px' } }}
    >
      {icon && <div style={{ fontSize: 28, lineHeight: 1, flexShrink: 0 }}>{icon}</div>}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 11.5, color: '#909399', marginBottom: 2 }}>{label}</div>
        <div style={{ fontSize: 22, fontWeight: 600, color: '#303133', lineHeight: 1.1 }}>{value}</div>
        {sub && <div style={{ fontSize: 10.5, color: '#909399', marginTop: 2 }}>{sub}</div>}
      </div>
    </Card>
  );
};

export default StatCard;
