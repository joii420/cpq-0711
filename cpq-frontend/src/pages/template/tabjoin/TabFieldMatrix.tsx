import React from 'react';
import { Tag, Tooltip, Typography, Space, Button } from 'antd';
import type { TabDef } from '../../../services/tabJoinFormulaService';

const { Text } = Typography;

// ──────────────────────────────────────────────
// 工具函数：解析表达式中的明细令牌，得到当前锁定行键类签名
// 令牌格式：明细 [alias.field]，列总计 [alias.field(总计)]，页签总计 [alias(总计)]
// ──────────────────────────────────────────────
export function parseActiveRowKeySig(
  expression: string,
  tabDefs: TabDef[],
): string | null {
  const TOKEN_RE = /\[([^\[\]]+)\]/g;
  let match: RegExpExecArray | null;
  const detailAliases: string[] = [];

  TOKEN_RE.lastIndex = 0;
  while ((match = TOKEN_RE.exec(expression)) !== null) {
    const body = match[1].trim();
    // 跳过总计令牌（结尾含 (总计)）
    if (body.endsWith('(总计)')) continue;
    // 明细令牌：alias.field
    if (body.includes('.')) {
      const alias = body.slice(0, body.indexOf('.'));
      detailAliases.push(alias);
    }
  }

  if (detailAliases.length === 0) return null;

  // 取第一个明细令牌对应的 alias，查其 rowKeyFields 作为锁定签名
  const firstAlias = detailAliases[0];
  const def = tabDefs.find((d) => d.alias === firstAlias);
  if (!def || !def.rowKeyFields.length) return null;

  return def.rowKeyFields.join('+');
}

// ──────────────────────────────────────────────
// Props
// ──────────────────────────────────────────────
interface Props {
  tabDefs: TabDef[];
  expression: string;
  onInsert: (token: string) => void;
  onClearExpression?: () => void;
}

// ──────────────────────────────────────────────
// 主组件
// ──────────────────────────────────────────────
const TabFieldMatrix: React.FC<Props> = ({ tabDefs, expression, onInsert, onClearExpression }) => {
  const activeSig = parseActiveRowKeySig(expression, tabDefs);

  if (tabDefs.length === 0) {
    return (
      <div style={{ padding: '12px 0', color: '#8a909a', fontSize: 12 }}>
        暂无页签定义数据（请检查模板配置）
      </div>
    );
  }

  return (
    <div>
      {/* 锁定状态条 */}
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
        <span>
          当前行键锁定：
          <Text strong style={{ color: activeSig ? '#722ed1' : '#8a909a' }}>
            {activeSig
              ? `已锁定行键类 [${activeSig}]（仅同类页签明细可选；其它页签仅总计可用）`
              : '未锁定（任意页签明细可选）'}
          </Text>
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
          const defSig = def.rowKeyFields.join('+');
          const sameClass = activeSig === null || defSig === activeSig;
          const rkActive = activeSig !== null && defSig === activeSig;

          return (
            <div
              key={def.tabKey ?? def.alias}
              style={{
                display: 'flex',
                alignItems: 'stretch',
                borderBottom: idx < tabDefs.length - 1 ? '1px solid #e5e7eb' : 'none',
                background: sameClass ? '#fff' : '#fafafa',
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
                <span style={{ fontWeight: 600, fontSize: 13 }}>{def.alias}</span>
                <span
                  style={{
                    fontSize: 11,
                    background: rkActive ? '#722ed1' : '#f9f0ff',
                    color: rkActive ? '#fff' : '#722ed1',
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
                    const disabled = !sameClass;
                    const tooltipTitle = disabled
                      ? `行键 [${defSig}] 与已锁定类 [${activeSig}] 不同，明细不可逐行对齐；可改用其总计字段`
                      : '';
                    return (
                      <Tooltip key={f} title={tooltipTitle}>
                        <Tag
                          style={{
                            cursor: disabled ? 'not-allowed' : 'pointer',
                            color: disabled ? '#bfbfbf' : undefined,
                            borderColor: disabled ? '#f0f0f0' : undefined,
                            background: disabled ? '#fafafa' : '#fff',
                            margin: 0,
                            fontSize: 12,
                            padding: '3px 9px',
                            borderStyle: 'solid',
                            userSelect: 'none',
                          }}
                          onClick={
                            disabled
                              ? undefined
                              : () => onInsert(`[${def.alias}.${f}]`)
                          }
                        >
                          {f}
                        </Tag>
                      </Tooltip>
                    );
                  })}
                </Space>

                {/* 小计列总计组（始终可点，虚线绿色样式） */}
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
                    <span style={{ fontSize: 11, color: '#8a909a' }}>小计列总计</span>
                    {def.subtotalCols.map((f) => (
                      <Tag
                        key={f}
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
                        onClick={() => onInsert(`[${def.alias}.${f}(总计)]`)}
                      >
                        {f}(总计)
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
                    onClick={() => onInsert(`[${def.alias}(总计)]`)}
                  >
                    {def.alias}(总计)
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
