/**
 * PathPickerDrawer
 *
 * BNF 路径选择器(简版,Phase A2)— 用户在公式编辑器点"插入物理表路径"按钮时弹出。
 *
 * 工作流:
 *   1. 用户输入 BNF 路径字符串(如 mat_part.unit_weight 或 元素BOM[元素='Ag'].组成含量)
 *   2. 失焦时调后端 /formulas/evaluate 用空数据上下文做语法校验
 *      - 解析失败 → 红色错误提示
 *      - 解析成功 → 绿色 ✓
 *   3. 确认后回调插入 path token
 *
 * 完整版(v2):树形选物理表 → 谓词字段 → 操作符 → 值输入 → 目标字段。
 * 当前简版仅支持文本输入 + 后端语法校验。
 */
import React, { useEffect, useState } from 'react';
import { Drawer, Input, Button, Alert, Typography, Space, Tag, Tabs, Select, Form, Empty, Segmented } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, DatabaseOutlined, EditOutlined } from '@ant-design/icons';
import { formulaService } from '../../services/formulaService';
import { basicDataConfigService, type BasicDataSheet, type BasicDataAttribute } from '../../services/basicDataConfigService';

const { Title, Paragraph, Text } = Typography;

interface Props {
  open: boolean;
  onClose: () => void;
  /** 现有路径(编辑模式时回填) */
  initialPath?: string;
  onConfirm: (path: string, label: string) => void;
  /**
   * V79: 默认按哪种模板类型过滤可选 sheet。
   * 'QUOTATION'（报价模板专用 + BOTH） / 'COSTING'（核价 + BOTH） / 'ALL'（全部）
   * 缺省 'ALL'。用户在抽屉顶部 Segmented 也可以临时切换。
   */
  defaultTemplateKind?: 'QUOTATION' | 'COSTING' | 'ALL';
}

const codeStyle: React.CSSProperties = {
  background: '#f6f8fa',
  border: '1px solid #e1e4e8',
  borderRadius: 4,
  padding: '8px 10px',
  fontFamily: 'Consolas, Monaco, monospace',
  fontSize: 12,
  whiteSpace: 'pre',
  marginTop: 6,
  marginBottom: 12,
};

const EXAMPLES = [
  { label: 'mat_part 当前料号单重', expr: 'mat_part.unit_weight' },
  { label: '元素 BOM Ag 含量', expr: "mat_bom[bom_type='ELEMENT', element_name='Ag'].composition_pct" },
  { label: '组装加工费值', expr: "mat_fee[fee_type='ASSEMBLY_PROCESS'].fee_value" },
  { label: '电镀加工费', expr: 'plating_fee.plating_process_fee' },
  { label: '中文 sheet 名(等价)', expr: "元素BOM[元素='Ag'].组成含量" },
];

const PathPickerDrawer: React.FC<Props> = ({ open, onClose, initialPath = '', onConfirm, defaultTemplateKind = 'ALL' }) => {
  const [pathExpr, setPathExpr] = useState('');
  const [validateState, setValidateState] = useState<'idle' | 'validating' | 'ok' | 'error'>('idle');
  const [validateMsg, setValidateMsg] = useState('');
  const [activeTab, setActiveTab] = useState<'visual' | 'manual'>('visual');

  // V79: 模板类型过滤切换器（QUOTATION / COSTING / ALL）
  const [templateKind, setTemplateKind] = useState<'QUOTATION' | 'COSTING' | 'ALL'>(defaultTemplateKind);

  // 「从基础数据选」模式状态
  const [sheets, setSheets] = useState<BasicDataSheet[]>([]);
  const [selectedSheetId, setSelectedSheetId] = useState<string | undefined>();
  const [attrs, setAttrs] = useState<BasicDataAttribute[]>([]);
  const [selectedAttrId, setSelectedAttrId] = useState<string | undefined>();
  const [extraPredicate, setExtraPredicate] = useState(''); // 用户额外加的谓词,如 element_name='Ag'

  // 抽屉打开时回填 initialPath
  useEffect(() => {
    if (open) {
      setPathExpr(initialPath);
      setValidateState('idle');
      setValidateMsg('');
      setActiveTab(initialPath ? 'manual' : 'visual'); // 已有路径默认进手动 tab
      setSelectedSheetId(undefined);
      setSelectedAttrId(undefined);
      setExtraPredicate('');
      setTemplateKind(defaultTemplateKind);
    }
  }, [open, initialPath, defaultTemplateKind]);

  // 加载有 target_table 的 sheets，按 templateKind 过滤（'ALL' 不传 → 后端返全部）
  useEffect(() => {
    if (!open) return;
    const params: any = { status: 'ACTIVE' };
    if (templateKind !== 'ALL') params.templateKind = templateKind;
    basicDataConfigService.listSheets(params)
      .then((res) => {
        const list = (res.data || []).filter((s) => s.targetTable);
        setSheets(list);
        // 切换 kind 后清掉已选 sheet（避免选到已被过滤掉的项）
        if (selectedSheetId && !list.find(s => s.id === selectedSheetId)) {
          setSelectedSheetId(undefined);
          setSelectedAttrId(undefined);
        }
      })
      .catch(() => setSheets([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, templateKind]);

  // 选 sheet 后加载该 sheet 的 attributes
  useEffect(() => {
    if (!selectedSheetId) {
      setAttrs([]);
      setSelectedAttrId(undefined);
      return;
    }
    basicDataConfigService.listAttributes(selectedSheetId, 'ACTIVE')
      .then((res) => setAttrs(res.data || []))
      .catch(() => setAttrs([]));
    setSelectedAttrId(undefined);
  }, [selectedSheetId]);

  // 拼接 BNF 路径 — sheet.targetTable + 谓词 + 字段 variableCode
  const selectedSheet = sheets.find((s) => s.id === selectedSheetId);
  const selectedAttr = attrs.find((a) => a.id === selectedAttrId);
  const generatedPath = (() => {
    if (!selectedSheet?.targetTable || !selectedAttr) return '';
    const disc = selectedSheet.targetDiscriminator;
    const discPredicates: string[] = [];
    if (disc && typeof disc === 'object') {
      for (const [k, v] of Object.entries(disc)) {
        discPredicates.push(`${k}='${String(v)}'`);
      }
    }
    if (extraPredicate.trim()) discPredicates.push(extraPredicate.trim());
    const predStr = discPredicates.length > 0 ? `[${discPredicates.join(', ')}]` : '';
    return `${selectedSheet.targetTable}${predStr}.${selectedAttr.variableCode}`;
  })();

  // 选中视觉模式生成的路径 → 同步到 pathExpr 输入框
  useEffect(() => {
    if (activeTab === 'visual' && generatedPath) {
      setPathExpr(generatedPath);
      setValidateState('idle');
    }
  }, [generatedPath, activeTab]);

  const reset = () => {
    setPathExpr('');
    setValidateState('idle');
    setValidateMsg('');
    setSelectedSheetId(undefined);
    setSelectedAttrId(undefined);
    setExtraPredicate('');
  };

  const handleClose = () => {
    reset();
    onClose();
  };

  // 失焦校验:用空数据上下文调后端,只验语法是否能解析
  // 后端无 partNo + 无 customerId 时,带谓词的查询可能查不到行,但语法解析失败一定能识别
  const handleValidate = async () => {
    const expr = pathExpr.trim();
    if (!expr) {
      setValidateState('idle');
      return;
    }
    setValidateState('validating');
    try {
      const res = await formulaService.evaluate({ expression: `{${expr}}` });
      const data = res.data;
      if (data.success) {
        setValidateState('ok');
        setValidateMsg('语法正确');
      } else if (data.errorType === 'PARSE_ERROR') {
        setValidateState('error');
        setValidateMsg(`语法错误:${data.error ?? '未知'}`);
      } else {
        // EVAL_ERROR 通常是空上下文导致的查询失败,语法本身没错
        setValidateState('ok');
        setValidateMsg('语法正确(运行时根据当前料号/客户上下文查询数据)');
      }
    } catch (e: any) {
      setValidateState('error');
      setValidateMsg(`校验失败:${e?.message ?? '网络错误'}`);
    }
  };

  const handleConfirm = () => {
    const expr = pathExpr.trim();
    if (!expr) return;
    if (validateState === 'error') return;
    // 显示标签从路径末尾字段名提取
    const segments = expr.split('.');
    const last = segments[segments.length - 1] || expr;
    const cleaned = last.replace(/\[.*?\]/g, '');
    onConfirm(expr, cleaned);
    reset();
    onClose();
  };

  return (
    <Drawer
      title="插入物理表路径(BNF)"
      placement="right"
      width={560}
      open={open}
      onClose={handleClose}
      footer={
        <div style={{ textAlign: 'right' }}>
          <Button onClick={handleClose} style={{ marginRight: 8 }}>取消</Button>
          <Button
            type="primary"
            disabled={!pathExpr.trim() || validateState === 'error' || validateState === 'validating'}
            onClick={handleConfirm}
          >
            插入
          </Button>
        </div>
      }
    >
      <Alert
        type="info"
        showIcon
        message="BNF 路径直接引用基础数据导入的物理表"
        description="不用预建数据源,公式运行时根据当前报价单的客户/料号上下文自动注入 customer_id / hf_part_no 谓词。"
        style={{ marginBottom: 12 }}
      />

      <Tabs
        activeKey={activeTab}
        onChange={(k) => setActiveTab(k as 'visual' | 'manual')}
        items={[
          {
            key: 'visual',
            label: <span><DatabaseOutlined /> 从基础数据选</span>,
            children: (
              <>
                <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
                  从已配置的基础数据 Sheet 中选择,不用懂物理表英文名。
                </Paragraph>
                {/* V79: 模板类型切换器 — 控制 Sheet 列表只显示对应模板可用的 sheet */}
                <div style={{ marginBottom: 12 }}>
                  <span style={{ fontSize: 12, color: '#666', marginRight: 8 }}>模板范围：</span>
                  <Segmented
                    size="small"
                    value={templateKind}
                    onChange={(v) => setTemplateKind(v as any)}
                    options={[
                      { label: '报价模板', value: 'QUOTATION' },
                      { label: '核价模板', value: 'COSTING' },
                      { label: '全部', value: 'ALL' },
                    ]}
                  />
                  <span style={{ fontSize: 11, color: '#999', marginLeft: 8 }}>
                    {templateKind === 'QUOTATION' ? '只列报价模板可用 + 通用 sheet'
                      : templateKind === 'COSTING' ? '只列核价模板可用 + 通用 sheet'
                      : '不过滤'}
                  </span>
                </div>
                <Form layout="vertical" size="small">
                  <Form.Item label="基础数据 Sheet">
                    <Select
                      placeholder={`选择 Sheet（${sheets.length} 项匹配）`}
                      value={selectedSheetId}
                      onChange={setSelectedSheetId}
                      showSearch
                      optionFilterProp="label"
                      options={sheets.map((s) => ({
                        value: s.id,
                        label: `${s.sheetName} → ${s.targetTable ?? ''}`,
                      }))}
                    />
                  </Form.Item>
                  <Form.Item label="字段">
                    <Select
                      placeholder={selectedSheetId ? '选择字段' : '请先选 Sheet'}
                      value={selectedAttrId}
                      onChange={setSelectedAttrId}
                      disabled={!selectedSheetId}
                      showSearch
                      optionFilterProp="label"
                      options={attrs.map((a) => ({
                        value: a.id,
                        label: `${a.variableLabel} (${a.variableCode}) [列 ${a.columnLetter}]`,
                      }))}
                    />
                  </Form.Item>
                  <Form.Item
                    label="额外谓词(可选)"
                    tooltip="在 Sheet 自带的 discriminator 之外再加筛选条件,如 element_name='Ag'"
                  >
                    <Input
                      placeholder="如 element_name='Ag' 或 留空"
                      value={extraPredicate}
                      onChange={(e) => setExtraPredicate(e.target.value)}
                      style={{ fontFamily: 'Consolas, Monaco, monospace' }}
                    />
                  </Form.Item>
                </Form>

                {generatedPath && (
                  <Alert
                    type="success"
                    message="生成的 BNF 路径"
                    description={<Text code style={{ fontSize: 13 }}>{generatedPath}</Text>}
                    style={{ marginBottom: 12 }}
                  />
                )}

                {sheets.length === 0 && (
                  <Empty description="暂无配好 target_table 的 Sheet — 请先到「基础数据配置」页面设置 Sheet 的目标物理表" />
                )}

                {selectedSheet && (
                  <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 12 }}>
                    <Tag color="cyan">{selectedSheet.targetTable}</Tag>
                    {selectedSheet.targetDiscriminator && Object.keys(selectedSheet.targetDiscriminator).length > 0 && (
                      <Tag color="purple">
                        {Object.entries(selectedSheet.targetDiscriminator)
                          .map(([k, v]) => `${k}=${v}`).join(', ')}
                      </Tag>
                    )}
                    系统会自动注入 <Tag color="blue">customer_id</Tag>(客户级表)
                    <Tag color="green">hf_part_no</Tag>(料号级表)等上下文谓词
                  </Paragraph>
                )}
              </>
            ),
          },
          {
            key: 'manual',
            label: <span><EditOutlined /> 手动输入 BNF</span>,
            children: (
              <>
                <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 12 }}>
                  熟悉 BNF 语法时直接输入路径,失焦自动校验。
                </Paragraph>
                <Title level={5}>路径表达式</Title>
                <Input.TextArea
                  rows={2}
                  placeholder="例如: mat_part.unit_weight"
                  value={pathExpr}
                  onChange={(e) => { setPathExpr(e.target.value); setValidateState('idle'); }}
                  onBlur={handleValidate}
                  style={{ fontFamily: 'Consolas, Monaco, monospace' }}
                />

                <div style={{ marginTop: 8, minHeight: 24 }}>
                  {validateState === 'validating' && <Text type="secondary">正在校验…</Text>}
                  {validateState === 'ok' && (
                    <Space>
                      <CheckCircleOutlined style={{ color: '#52c41a' }} />
                      <Text type="success">{validateMsg}</Text>
                    </Space>
                  )}
                  {validateState === 'error' && (
                    <Space>
                      <CloseCircleOutlined style={{ color: '#ff4d4f' }} />
                      <Text type="danger">{validateMsg}</Text>
                    </Space>
                  )}
                </div>

                <Title level={5} style={{ marginTop: 24 }}>语法</Title>
                <Paragraph>
                  <Text code>表名.字段</Text> — 简单引用<br/>
                  <Text code>表名[字段=&apos;值&apos;].字段</Text> — 带谓词<br/>
                  <Text code>表名[字段 IN (&apos;v1&apos;,&apos;v2&apos;)].字段</Text> — IN 谓词<br/>
                  <Text code>表名[字段 LIKE &apos;%xx%&apos;].字段</Text> — LIKE 谓词<br/>
                  <Text code>表名[a=&apos;x&apos; AND b=&apos;y&apos;].字段</Text> — AND 多条件<br/>
                  最多 3 层嵌套:<Text code>A[k=&apos;v&apos;].B[k=&apos;v&apos;].C.field</Text>
                </Paragraph>

                <Title level={5}>常用例子(点击复制到上方)</Title>
                <Space direction="vertical" style={{ width: '100%' }} size={4}>
                  {EXAMPLES.map((ex, i) => (
                    <div
                      key={i}
                      style={{ cursor: 'pointer', padding: '8px 10px', border: '1px solid #f0f0f0', borderRadius: 4 }}
                      onClick={() => {
                        setPathExpr(ex.expr);
                        setValidateState('idle');
                        setTimeout(handleValidate, 100);
                      }}
                    >
                      <Text strong style={{ fontSize: 12 }}>{ex.label}</Text>
                      <div style={codeStyle}>{ex.expr}</div>
                    </div>
                  ))}
                </Space>
              </>
            ),
          },
        ]}
      />

      {/* 通用底部:校验状态 + 上下文说明 */}
      <Title level={5} style={{ marginTop: 16 }}>上下文自动注入</Title>
      <Paragraph type="secondary" style={{ fontSize: 12 }}>
        报价单运行时会自动加这些谓词,公式里 <Text strong>不用写</Text>:
        <br/>
        <Tag color="blue">customer_id</Tag> ← 报价单当前客户(查 mat_process / mat_fee / plating_fee)<br/>
        <Tag color="green">hf_part_no</Tag> ← 当前产品行料号(查 mat_part / mat_bom 等)
      </Paragraph>
    </Drawer>
  );
};

export default PathPickerDrawer;
