import React from 'react';
import { Tag, Tooltip, Typography, Space, Button } from 'antd';
import type { TabDef } from '../../../services/tabJoinFormulaService';
import { comparable } from '../../component/formulaSerialize';

const { Text } = Typography;

// ──────────────────────────────────────────────
// 宿主可比判定（v4-D/v4-M 新机制；已废除 parseActiveRowKeySig"首令牌锁签名"旧机制）
// ──────────────────────────────────────────────
/** 宿主可比：source 行键与宿主 selfRowKeyFields 集合包含(⊆/⊇);空行键 source 不可比(只留总计) */
export function tabComparable(selfRowKeyFields: string[], sourceRowKeyFields: string[]): boolean {
  if (!sourceRowKeyFields.length) return false;
  return comparable(selfRowKeyFields ?? [], sourceRowKeyFields);
}

// ──────────────────────────────────────────────
// Props
// ──────────────────────────────────────────────
interface Props {
  tabDefs: TabDef[];
  expression: string;
  onInsert: (token: string) => void;
  onClearExpression?: () => void;
  selfRowKeyFields?: string[];
}

// ──────────────────────────────────────────────
// 主组件
// ──────────────────────────────────────────────
const TabFieldMatrix: React.FC<Props> = ({ tabDefs, expression, onInsert, onClearExpression, selfRowKeyFields }) => {
  if (tabDefs.length === 0) {
    return (
      <div style={{ padding: '12px 0', color: '#8a909a', fontSize: 12 }}>
        暂无页签定义数据（请检查模板配置）
      </div>
    );
  }

  return (
    <div>
      {/* 宿主行键状态条 */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          marginBottom: 6,
          fontSize: 12,
          color: '#8a909a',
        }}
      >
        <span style={{ fontSize: 12, color: '#8a909a' }}>
          宿主行键 <Text strong style={{ color: '#722ed1' }}>[{(selfRowKeyFields ?? []).join(' + ') || '—'}]</Text>
          ；明细点击即插；更细页签明细需自行套 SUM/AVG 等聚合函数（或写 SUM(宿主列 * 细页签列) 行级聚合），否则一行命中多行报错；不可比页签仅整页签小计可用。
        </span>
        {onClearExpression && (
          <Button size="small" onClick={onClearExpression}>
            清空表达式
          </Button>
        )}
      </div>

      {/* 矩阵区域 */}
      <div
        style={{
          border: '1px solid #e5e7eb',
          borderRadius: 8,
          overflow: 'hidden',
          marginTop: 4,
        }}
      >
        {tabDefs.map((def, idx) => {
          const selfRKF = selfRowKeyFields ?? [];
          const isComparable = tabComparable(selfRKF, def.rowKeyFields ?? []);
          const sourceFiner = isComparable && (def.rowKeyFields ?? []).length > selfRKF.length;
          // 公式标识用「页签名称」优先（更可读），名称缺失回退编号；解析侧名称优先、编号兜底
          const ref = def.componentName || def.alias;

          return (
            <div
              key={def.tabKey ?? def.alias}
              style={{
                display: 'flex',
                alignItems: 'stretch',
                borderBottom: idx < tabDefs.length - 1 ? '1px solid #e5e7eb' : 'none',
                background: isComparable ? '#fff' : '#fafafa',
              }}
            >
              {/* 左侧：页签名 + 行键徽标 */}
              <div
                style={{
                  flexShrink: 0,
                  width: 160,
                  background: '#f7f9fc',
                  borderRight: '1px solid #e5e7eb',
                  padding: '10px 12px',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 5,
                  justifyContent: 'center',
                }}
              >
                <span style={{ fontWeight: 600, fontSize: 13, lineHeight: 1.3 }}>
                  {def.componentName || def.alias}
                  {def.componentName && def.alias && def.alias !== def.componentName && (
                    <span style={{ fontWeight: 400, fontSize: 11, color: '#8a909a' }}>
                      {' '}[{def.alias}]
                    </span>
                  )}
                </span>
                <span
                  style={{
                    fontSize: 11,
                    background: '#f9f0ff',
                    color: '#722ed1',
                    border: '1px solid #efdbff',
                    borderRadius: 4,
                    padding: '1px 6px',
                    alignSelf: 'flex-start',
                    whiteSpace: 'nowrap',
                  }}
                >
                  行键: {def.rowKeyFields.join(' + ')}
                </span>
              </div>

              {/* 右侧：三组字段 chip */}
              <div
                style={{
                  flex: 1,
                  padding: '9px 11px',
                  display: 'flex',
                  gap: 7,
                  flexWrap: 'wrap',
                  alignItems: 'flex-start',
                }}
              >
                {/* 明细组 */}
                <Space wrap style={{ gap: 6 }}>
                  <span style={{ fontSize: 11, color: '#8a909a' }}>明细</span>
                  {def.detailFields.map((f) => {
                    if (!isComparable) {
                      return (
                        <Tooltip key={f} title={`行键 [${(def.rowKeyFields ?? []).join('+')}] 与宿主 [${selfRKF.join('+')}] 不可比；可改用「${ref}(总计)」`}>
                          <Tag style={{ cursor: 'not-allowed', color: '#bfbfbf', background: '#fafafa',
                            borderColor: '#f0f0f0', margin: 0, fontSize: 12, padding: '3px 9px', userSelect: 'none' }}>{f}</Tag>
                        </Tooltip>
                      );
                    }
                    if (sourceFiner) {
                      // 细页签明细：点击直接裸插 [别名.列]，是否聚合由用户用工具条函数按钮自决
                      // （蓝边 + hover 提示作轻量引导，不强制、不挡操作）
                      return (
                        <Tooltip key={f} title={`「${ref}」行键较宿主细，引用其明细一般需套 SUM/AVG 等聚合函数（点上方函数按钮），否则一行命中多行将报错`}>
                          <Tag onClick={() => onInsert(`[${ref}.${f}]`)}
                            style={{ cursor: 'pointer', background: '#fff', borderColor: '#91caff',
                              margin: 0, fontSize: 12, padding: '3px 9px', borderStyle: 'solid', userSelect: 'none' }}>{f}</Tag>
                        </Tooltip>
                      );
                    }
                    return (
                      <Tag key={f} onClick={() => onInsert(`[${ref}.${f}]`)}
                        style={{ cursor: 'pointer', background: '#fff', borderColor: '#91caff', margin: 0, fontSize: 12,
                          padding: '3px 9px', borderStyle: 'solid', userSelect: 'none' }}>{f}</Tag>
                    );
                  })}
                </Space>

                {/* 小计列（组件小计，标量引用；插入 [alias.col]，序列化为 component_subtotal） */}
                {def.subtotalCols.length > 0 && (
                  <Space
                    wrap
                    style={{
                      gap: 6,
                      marginLeft: 10,
                      paddingLeft: 10,
                      borderLeft: '1px dashed #e5e7eb',
                    }}
                  >
                    <span style={{ fontSize: 11, color: '#8a909a' }}>小计列</span>
                    {def.subtotalCols.map((f) => (
                      <Tag
                        key={f}
                        style={{
                          cursor: 'pointer',
                          color: '#fa8c16',
                          borderColor: '#ffd591',
                          borderStyle: 'dashed',
                          background: '#fff',
                          margin: 0,
                          fontSize: 12,
                          padding: '3px 9px',
                          userSelect: 'none',
                        }}
                        onClick={() => onInsert(`[${ref}.${f}]`)}
                      >
                        {f}(小计)
                      </Tag>
                    ))}
                  </Space>
                )}

                {/* 页签总计（始终可点，虚线绿色样式） */}
                <Space
                  wrap
                  style={{
                    gap: 6,
                    marginLeft: 10,
                    paddingLeft: 10,
                    borderLeft: '1px dashed #e5e7eb',
                  }}
                >
                  <span style={{ fontSize: 11, color: '#8a909a' }}>页签总计</span>
                  <Tag
                    style={{
                      cursor: 'pointer',
                      color: '#389e0d',
                      borderColor: '#b7eb8f',
                      borderStyle: 'dashed',
                      background: '#fff',
                      margin: 0,
                      fontSize: 12,
                      padding: '3px 9px',
                      userSelect: 'none',
                    }}
                    onClick={() => onInsert(`[${ref}(总计)]`)}
                  >
                    {ref}(总计)
                  </Tag>
                </Space>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default TabFieldMatrix;
