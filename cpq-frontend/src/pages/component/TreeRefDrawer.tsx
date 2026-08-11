/**
 * TreeRefDrawer.tsx — task-0803 Task 8 (F-3/F-4/F-7)
 *
 * BOM 页签「父子取值」（PGET / CSUM / CAVG / CMAX / CMIN / CCOUNT）的配置抽屉。
 * 照 `CrossTabRefDrawer.tsx` 的做法：`Drawer` + `placement="right"`（项目规范：抽屉替代弹窗）。
 *
 * 方向（父/子）与聚合方式由触发它的函数按钮决定，本抽屉内**只读展示**（F-4）——
 * 用户不能在抽屉里把 PGET 改成 CSUM，要换函数得关掉重新点另一个按钮，语义上更不容易配错。
 *
 * 目标表达式（targetExpr）编辑区受内层白名单约束（需求 §4.3.5）：
 * 只能引用本页签字段 / 运算符 / 数字 / 括号 / 全局变量 / 树属性——
 * 提供的插入控件本身只产出合法 token，但 `initialTargetExpr`（编辑已有 chip 时回填）
 * 可能携带历史脏数据或非法 token，因此校验必须对"当前 targetExpr 状态"实时判定，
 * 不能只在提交时查一次。
 *
 * F-7 校验**直接复用** Task 6 导出的 `validateTreeRefWhitelist`（见 `formulaSerialize.ts`），
 * 不重新写一套白名单规则——两套规则并存迟早语义漂移。
 */
import React, { useEffect, useState } from 'react';
import { Drawer, Select, Button, Space, InputNumber, Tag, message } from 'antd';
import type { FormulaToken } from './types';
import type { DecimalString } from '../../utils/precision';
import { createFormulaNumberToken } from './formulaNumberToken';
import { validateTreeRefWhitelist } from './formulaSerialize';
import { TREE_REF_FUNC_LABELS, treeAttrChipLabel, treeRefChipLabel, treeRefFuncKey } from './crossTabText';

export interface TreeRefFieldOption {
  name: string;
  label?: string;
}

interface Props {
  open: boolean;
  onClose: () => void;
  /** 由触发的函数按钮决定，只读展示（F-4）：'PARENT' = PGET，'CHILD' = C* 族。 */
  dir: 'PARENT' | 'CHILD';
  /** 由触发的函数按钮决定，只读展示（F-4）：PARENT 恒 'NONE'；CHILD 取 SUM/AVG/MAX/MIN/COUNT。 */
  agg: string;
  /** 本页签（BOM 组件）已有字段，供目标表达式选择——父子取值只能引用"本页签"的列。 */
  availableFields: TreeRefFieldOption[];
  /** 编辑已插入 chip 时回填其 targetExpr；新建时传空数组或不传。 */
  initialTargetExpr?: FormulaToken[];
  onConfirm: (token: FormulaToken) => void;
}

/** F-7 定稿红字（逐字，需求 §11.5.3）。 */
export const TREE_REF_TARGET_EXPR_VIOLATION_TEXT =
  '父子取值的目标表达式内不能再引用跨页签数据或其他父子取值，请改用本页签的列';

/**
 * F-7 保存门禁：目标表达式是否只含白名单内 token。
 *
 * 直接复用 Task 6 的 {@link validateTreeRefWhitelist}（递归扫描全部 token，
 * 能捕获嵌套在任意容器内的违规 token）——构造一个"包裹"当前 targetExpr 的
 * tree_ref token 传入，等价于对草稿状态做与后端保存时完全一致的判定。
 *
 * 导出为独立纯函数，使其可在不渲染 Drawer（本项目未装 jsdom/@testing-library/react，
 * 无法做真实 DOM 渲染断言）的情况下被直接单元测试——与组件内部实际调用路径完全一致，
 * 不是另一套"测试专用"逻辑。
 */
export function checkTreeRefTargetExpr(
  dir: 'PARENT' | 'CHILD',
  agg: string,
  targetExpr: FormulaToken[],
): { canSave: boolean } {
  const wrapped: FormulaToken = { type: 'tree_ref', dir, agg, targetExpr };
  const result = validateTreeRefWhitelist([wrapped]);
  return { canSave: result.valid };
}

const OPERATOR_BUTTONS: Array<{ label: string; value: string; kind: FormulaToken['type'] }> = [
  { label: '+', value: '+', kind: 'operator' },
  { label: '－', value: '-', kind: 'operator' },
  { label: '×', value: '*', kind: 'operator' },
  { label: '÷', value: '/', kind: 'operator' },
  { label: '（', value: '(', kind: 'bracket_open' },
  { label: '）', value: ')', kind: 'bracket_close' },
];

const TREE_ATTR_BUTTONS: Array<{ attr: 'LVL' | 'IS_LEAF' | 'IS_ROOT' }> = [
  { attr: 'LVL' },
  { attr: 'IS_LEAF' },
  { attr: 'IS_ROOT' },
];

/** targetExpr chip 预览文本；非法 token（如误带入的 cross_tab_ref）也要能显示 + 可删，方便用户"点掉它"自纠。 */
function tokenPreviewText(tok: FormulaToken): string {
  switch (tok.type) {
    case 'field': return tok.label || tok.value || '';
    case 'operator': return tok.value === '*' ? '×' : tok.value === '/' ? '÷' : (tok.value || '');
    case 'bracket_open': return '(';
    case 'bracket_close': return ')';
    case 'number': return tok.value || '';
    case 'global_variable': return tok.label || tok.code || '全局变量';
    case 'tree_attr': return treeAttrChipLabel(tok.attr);
    default: return tok.label || tok.value || tok.type;
  }
}

const TreeRefDrawer: React.FC<Props> = ({
  open,
  onClose,
  dir,
  agg,
  availableFields,
  initialTargetExpr,
  onConfirm,
}) => {
  const [targetExpr, setTargetExpr] = useState<FormulaToken[]>(initialTargetExpr ?? []);
  const [fieldSel, setFieldSel] = useState<string | undefined>(undefined);
  const [numInput, setNumInput] = useState<DecimalString | null>(null);

  // 每次打开（或切换到编辑另一个 chip）都用 initialTargetExpr 重置草稿状态。
  useEffect(() => {
    if (open) {
      setTargetExpr(initialTargetExpr ?? []);
      setFieldSel(undefined);
      setNumInput(null);
    }
  }, [open, initialTargetExpr]);

  const funcKey = treeRefFuncKey(dir, agg);
  const funcLabel = TREE_REF_FUNC_LABELS[funcKey] ?? funcKey;

  const appendToken = (tok: FormulaToken) => setTargetExpr((prev) => [...prev, tok]);
  const popToken = () => setTargetExpr((prev) => prev.slice(0, -1));
  const clearExpr = () => setTargetExpr([]);
  const removeAt = (idx: number) => setTargetExpr((prev) => prev.filter((_, i) => i !== idx));

  const { canSave } = checkTreeRefTargetExpr(dir, agg, targetExpr);

  const handleConfirm = () => {
    if (targetExpr.length === 0) {
      message.warning('请先构建目标表达式');
      return;
    }
    if (!canSave) return; // 按钮已 disabled；此处双重防御，防止绕过 UI 直接触发
    onConfirm({ type: 'tree_ref', dir, agg, targetExpr });
    onClose();
  };

  const previewText = treeRefChipLabel(dir, agg, targetExpr);

  return (
    <Drawer
      title={`父子取值配置 · ${funcKey}`}
      placement="right"
      width={720}
      open={open}
      onClose={onClose}
      footer={
        <div style={{ textAlign: 'right' }}>
          <Space>
            <Button onClick={onClose}>取消</Button>
            <Button type="primary" disabled={!canSave} onClick={handleConfirm}>
              确定
            </Button>
          </Space>
        </div>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
        <div>
          <div style={{ fontWeight: 500, marginBottom: 6, fontSize: 13 }}>1. 方向（只读，由所选函数决定）</div>
          <Tag color={dir === 'PARENT' ? 'blue' : 'green'}>
            {dir === 'PARENT' ? '子 → 父' : '父 → 子'}（{funcKey}）
          </Tag>
        </div>

        <div>
          <div style={{ fontWeight: 500, marginBottom: 6, fontSize: 13 }}>2. 聚合方式（只读，由所选函数决定）</div>
          <Tag>{agg === 'NONE' ? '取一个值（无需聚合）' : agg}</Tag>
        </div>

        <div>
          <div style={{ fontWeight: 500, marginBottom: 6, fontSize: 13, display: 'flex', alignItems: 'center', gap: 8 }}>
            3. 目标表达式
            <span style={{ fontWeight: 400, color: '#8c8c8c', fontSize: 12 }}>
              仅可引用本页签的列 / 数字 / 运算符 / 全局变量 / 树属性
            </span>
          </div>

          {/* Chip display of current targetExpr */}
          <div
            style={{
              border: '1px dashed #c0c4cc',
              borderRadius: 4,
              minHeight: 36,
              padding: '4px 6px',
              display: 'flex',
              flexWrap: 'wrap',
              gap: 4,
              alignItems: 'center',
              background: '#f9f9f9',
            }}
          >
            {targetExpr.length === 0 ? (
              <span style={{ color: '#c0c4cc', fontSize: 12, userSelect: 'none' }}>
                使用下方控件构建目标表达式
              </span>
            ) : (
              targetExpr.map((tok, idx) => (
                <Tag
                  key={idx}
                  closable
                  onClose={(e) => {
                    e.preventDefault();
                    removeAt(idx);
                  }}
                  style={{ margin: 0, fontSize: 12 }}
                >
                  {tokenPreviewText(tok)}
                </Tag>
              ))
            )}
          </div>

          {/* F-7：白名单外 token → 行内红字（逐字定稿），与保存按钮 disabled 同步出现 */}
          {!canSave && (
            <div style={{ color: '#ff4d4f', fontSize: 12, marginTop: 6 }}>
              {TREE_REF_TARGET_EXPR_VIOLATION_TEXT}
            </div>
          )}

          {/* Insert controls: 字段 + 树属性 */}
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center', marginTop: 10 }}>
            <span style={{ fontSize: 12, color: '#595959', whiteSpace: 'nowrap' }}>字段：</span>
            <Select
              size="small"
              style={{ width: 160 }}
              placeholder="选择本页签字段"
              value={fieldSel}
              options={availableFields.map((f) => ({ label: f.label || f.name, value: f.name }))}
              onChange={(v) => {
                const f = availableFields.find((x) => x.name === v);
                if (f) appendToken({ type: 'field', value: f.name, label: f.label || f.name });
                setFieldSel(undefined);
              }}
              showSearch
            />
            <span style={{ fontSize: 12, color: '#595959', whiteSpace: 'nowrap', marginLeft: 8 }}>树属性：</span>
            {TREE_ATTR_BUTTONS.map((b) => (
              <Button key={b.attr} size="small" onClick={() => appendToken({ type: 'tree_attr', attr: b.attr })}>
                {treeAttrChipLabel(b.attr)}
              </Button>
            ))}
          </div>

          {/* Insert controls: 运算符 + 数字 */}
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center', marginTop: 8 }}>
            <span style={{ fontSize: 12, color: '#595959', whiteSpace: 'nowrap' }}>运算符：</span>
            {OPERATOR_BUTTONS.map((b) => (
              <Button
                key={b.label}
                size="small"
                style={{ minWidth: 32, padding: '0 6px' }}
                onClick={() => appendToken({ type: b.kind, value: b.value })}
              >
                {b.label}
              </Button>
            ))}
            <span style={{ fontSize: 12, color: '#595959', whiteSpace: 'nowrap', marginLeft: 8 }}>数字：</span>
            <InputNumber<DecimalString> stringMode
              size="small"
              style={{ width: 90 }}
              value={numInput}
              onChange={(v) => setNumInput(v)}
              placeholder="输入数字"
            />
            <Button
              size="small"
              disabled={numInput === null || numInput === undefined}
              onClick={() => {
                const token = createFormulaNumberToken(numInput);
                if (token) {
                  appendToken(token);
                  setNumInput(null);
                }
              }}
            >
              添加
            </Button>
          </div>

          <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
            <Button size="small" danger onClick={popToken} disabled={targetExpr.length === 0}>
              删末
            </Button>
            <Button size="small" danger onClick={clearExpr} disabled={targetExpr.length === 0}>
              清空
            </Button>
          </div>

          {targetExpr.length > 0 && (
            <div style={{ fontSize: 12, color: '#8c8c8c', marginTop: 8 }}>
              chip 预览：<span style={{ color: '#1677ff' }}>{previewText}</span>（{funcLabel}）
            </div>
          )}
        </div>
      </div>
    </Drawer>
  );
};

export default TreeRefDrawer;
