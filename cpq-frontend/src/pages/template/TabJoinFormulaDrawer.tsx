import React, { useState, useEffect, useRef } from 'react';
import { Drawer, Button, Input, Space, message, Typography } from 'antd';
import { tabJoinFormulaService, type TabDef } from '../../services/tabJoinFormulaService';

const { Text } = Typography;

interface Props {
  open: boolean;
  templateId: string;
  column: any;
  onClose: () => void;
  onSave: (patch: any) => void;
}

const FUNCS = ['SUM', 'AVG', 'MIN', 'MAX', 'COUNT'];
const OPS = ['+', '-', '*', '/', '(', ')'];

const TabJoinFormulaDrawer: React.FC<Props> = ({ open, templateId, column, onClose, onSave }) => {
  const [expression, setExpression] = useState<string>(column?.expression ?? '');
  const [tabDefs, setTabDefs] = useState<TabDef[]>([]);
  const exprRef = useRef<any>(null);

  // 列切换时重置表达式
  useEffect(() => {
    setExpression(column?.expression ?? '');
  }, [column]);

  // Drawer 打开时拉页签定义
  useEffect(() => {
    if (!open) return;
    tabJoinFormulaService
      .tabDefs(templateId)
      .then((res: any) => {
        // api 拦截器返回 {code, message, data}，需手动解包 .data
        setTabDefs(Array.isArray(res?.data) ? res.data : []);
      })
      .catch(() => setTabDefs([]));
  }, [open, templateId]);

  /** 在光标处插入文本，caretOffsetFromEnd 控制插入后光标左偏（用于 fn() 光标落在括号内） */
  const insertAtCursor = (text: string, caretOffsetFromEnd = 0) => {
    const el: HTMLTextAreaElement | undefined =
      exprRef.current?.resizableTextArea?.textArea ?? exprRef.current;
    const start = el?.selectionStart ?? expression.length;
    const end = el?.selectionEnd ?? start;
    const next = expression.slice(0, start) + text + expression.slice(end);
    setExpression(next);
    const pos = start + text.length - caretOffsetFromEnd;
    requestAnimationFrame(() => {
      try {
        el?.focus();
        el?.setSelectionRange(pos, pos);
      } catch {
        // 忽略 focus 失败（如 SSR / 测试环境）
      }
    });
  };

  const save = () => {
    const expr = expression.trim();
    if (!expr) {
      message.error('表达式不能为空');
      return;
    }
    // 从表达式中解析出引用的页签 alias（形如 [alias.field] 或 [alias(总计)]）
    const refAliases = Array.from(
      new Set(
        (expr.match(/\[([^\[\]]+)\]/g) || []).map((t) => {
          const body = t.slice(1, -1).replace(/\(总计\)$/, '');
          return body.includes('.') ? body.slice(0, body.indexOf('.')) : body;
        }),
      ),
    );
    const tabs = refAliases
      .map((a) => tabDefs.find((d) => d.alias === a))
      .filter(Boolean)
      .map((d: any) => ({ alias: d.alias, tabKey: d.tabKey, rowKeyFields: d.rowKeyFields }));

    onSave({ source_type: 'TAB_JOIN_FORMULA', expression: expr, tabs });
  };

  return (
    <Drawer
      title="配置页签连表公式"
      width={1100}
      placement="right"
      open={open}
      onClose={onClose}
      destroyOnClose
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" onClick={save}>
            保存
          </Button>
        </Space>
      }
    >
      {/* 公式表达式 */}
      <Text strong>公式表达式</Text>
      <div style={{ color: '#8a909a', fontSize: 12, marginBottom: 6 }}>
        列来源：页签连表公式 · 单卡片单值 · 行键自动对齐(全外连·缺补0) · 明细默认按对齐行求和
      </div>
      <Input.TextArea
        ref={exprRef}
        rows={2}
        value={expression}
        onChange={(e) => setExpression(e.target.value)}
        placeholder="例：[投料.金额] * [加工.工时] + [回料(总计)]"
        style={{ fontFamily: 'SF Mono, Consolas, Monaco, monospace', marginTop: 4 }}
      />

      {/* 运算符 + 函数工具条 */}
      <Space style={{ marginTop: 10 }} wrap>
        <Text type="secondary" style={{ fontSize: 12 }}>
          运算符
        </Text>
        {OPS.map((op) => (
          <Button key={op} size="small" style={{ fontFamily: 'monospace', fontWeight: 600 }}
            onClick={() => insertAtCursor(op)}>
            {op}
          </Button>
        ))}
        <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
          函数
        </Text>
        {FUNCS.map((fn) => (
          <Button
            key={fn}
            size="small"
            style={{ color: '#fa8c16', borderColor: '#ffd591' }}
            onClick={() => insertAtCursor(`${fn}()`, 1)}
          >
            {fn}
          </Button>
        ))}
      </Space>

      {/* 规则提示 */}
      <div
        style={{
          marginTop: 10,
          padding: '8px 12px',
          background: '#fffbe6',
          border: '1px solid #ffe58f',
          borderRadius: 6,
          fontSize: 12,
          color: '#874d00',
          lineHeight: 1.7,
        }}
      >
        明细字段默认按对齐行自动求和；套 <code style={{ background: '#fff', border: '1px solid #ffe58f', borderRadius: 3, padding: '0 4px' }}>AVG/MIN/MAX/COUNT</code> 改聚合方式。
        按顶层 +/- 拆项：含裸明细的项逐行求和，纯标量/总计项算一次。
        引用格式：<code style={{ background: '#fff', border: '1px solid #ffe58f', borderRadius: 3, padding: '0 4px' }}>[页签别名.字段名]</code> 或{' '}
        <code style={{ background: '#fff', border: '1px solid #ffe58f', borderRadius: 3, padding: '0 4px' }}>[页签别名(总计)]</code>。
      </div>

      {/* Task12: 页签字段矩阵 + 置灰锁定 在此渲染 */}
      {/* Task13: 样本卡片选择 + 试算 在此渲染 */}
    </Drawer>
  );
};

export default TabJoinFormulaDrawer;
