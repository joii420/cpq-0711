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
import { Drawer, Input, Button, Alert, Typography, Space, Tag, Select, Form, Radio, Spin } from 'antd';
import { componentSqlViewService, type ComponentSqlView, type SqlViewColumn } from '../../services/componentSqlViewService';
import { templateSqlViewService, type TemplateSqlView } from '../../services/templateSqlViewService';
import { ViewColumnPickerBody } from './ViewColumnPickerBody';
import { extractSqlViewName } from './sqlViewPath';

const { Title, Paragraph, Text } = Typography;

interface Props {
  open: boolean;
  onClose: () => void;
  /** 现有路径(编辑模式时回填) */
  initialPath?: string;
  onConfirm: (path: string, label: string) => void;
  /**
   * 当前组件 ID（SQL 视图 Tab 需要，可选）。
   * 未传时 SQL 视图 Tab 不显示本组件 SQL 视图，仅显示 GLOBAL SQL 视图。
   *
   * 注意：ownerContext 优先级高于 componentId。
   * 传了 ownerContext 时本字段被忽略，改用 ownerContext 决定 SQL 视图 Tab 内容。
   */
  componentId?: string;
  /**
   * 路径选择器的 owner 上下文 — 决定 SQL 视图 Tab 显示哪张表的视图 + 允许的路径形态。
   *
   * - COMPONENT：显示本组件 SQL 视图 + 跨组件 GLOBAL 视图（沿用现状）
   * - TEMPLATE：仅显示本模板（template 实体）的 SQL 视图（隔离，不显示 GLOBAL 区域）
   * - 未传（undefined）：沿用旧行为（仅显示 GLOBAL SQL 视图，用于无 componentId 的老调用方）
   */
  ownerContext?:
    | { type: 'COMPONENT'; componentId: string }
    | { type: 'TEMPLATE'; templateId: string };
  /**
   * Tab 默认选中。
   * 未传时若 initialPath 不为空则进 manual Tab，否则进 sql-view Tab。
   */
  defaultTab?: 'sql-view' | 'manual';
  /**
   * 老 PG 直引路径的处理策略（仅对 manual Tab 输入校验生效）。
   * - WARN_WITH_MIGRATION_SUGGEST（默认）：黄色警告，提示迁移到 SQL 视图
   * - BLOCK：红色错误，阻止确认
   * - ALLOW：不做任何提示
   */
  legacyPathPolicy?: 'WARN_WITH_MIGRATION_SUGGEST' | 'BLOCK' | 'ALLOW';
  /**
   * 传入则把可选视图过滤到该 driver 视图并锁定（字段 basic_data_path 用）。
   * 未传时行为与改动前等价（显示全部视图，可自由选择）。
   */
  driverViewPath?: string;
  /**
   * 关闭谓词构造（C2 纯点选体验）。
   * 未传时默认显示谓词输入区（原有行为）。
   */
  disablePredicate?: boolean;
}


const PathPickerDrawer: React.FC<Props> = ({
  open,
  onClose,
  initialPath = '',
  onConfirm,
  componentId,
  ownerContext,
  // defaultTab: Task 6.1 — 手动 Tab 已移除，prop 保留供调用方兼容但不再读取
  // legacyPathPolicy: Task 6.1 — 手动 Tab 已移除，prop 保留供调用方兼容但不再读取
  driverViewPath,
  disablePredicate,
}) => {
  // 解析 effectiveComponentId：ownerContext.COMPONENT 优先，否则回退 componentId prop
  const effectiveComponentId =
    ownerContext?.type === 'COMPONENT'
      ? ownerContext.componentId
      : ownerContext === undefined
      ? componentId
      : undefined; // TEMPLATE 上下文：不用 componentId
  const [pathExpr, setPathExpr] = useState('');

  // SQL 视图 Tab 状态（组件上下文）
  const [sqlViews, setSqlViews] = useState<ComponentSqlView[]>([]);
  const [globalSqlViews, setGlobalSqlViews] = useState<ComponentSqlView[]>([]);
  const [sqlViewsLoading, setSqlViewsLoading] = useState(false);
  const [selectedSqlViewId, setSelectedSqlViewId] = useState<string | undefined>();
  const [selectedSqlColumn, setSelectedSqlColumn] = useState<string | undefined>();
  const [sqlExtraPredicate, setSqlExtraPredicate] = useState('');

  // SQL 视图 Tab 状态（template 实体上下文）— ownerContext.type === 'TEMPLATE' 时使用
  const [tmplSqlViews, setTmplSqlViews] = useState<TemplateSqlView[]>([]);
  const [selectedTmplSqlViewId, setSelectedTmplSqlViewId] = useState<string | undefined>();
  const [selectedTmplSqlColumn, setSelectedTmplSqlColumn] = useState<string | undefined>();
  const [tmplSqlExtraPredicate, setTmplSqlExtraPredicate] = useState('');

  // 抽屉打开时回填 initialPath（Task 6.1: 手动 Tab 已移除，始终停在 sql-view）
  useEffect(() => {
    if (open) {
      setPathExpr(initialPath);
      // 重置 SQL 视图选择状态（组件上下文）
      setSelectedSqlViewId(undefined);
      setSelectedSqlColumn(undefined);
      setSqlExtraPredicate('');
      // 重置 SQL 视图选择状态（template 实体上下文）
      setSelectedTmplSqlViewId(undefined);
      setSelectedTmplSqlColumn(undefined);
      setTmplSqlExtraPredicate('');
    }
  }, [open, initialPath]);

  // SQL 视图：按 ownerContext 分两条路径加载（Task 6.1: 始终加载，无 Tab 切换守卫）
  useEffect(() => {
    if (!open) return;
    setSqlViewsLoading(true);

    if (ownerContext?.type === 'TEMPLATE') {
      // TEMPLATE 上下文：仅加载 template 实体的 SQL 视图
      templateSqlViewService
        .list(ownerContext.templateId)
        .then((res) => setTmplSqlViews((res.data ?? []).filter((v) => v.status === 'ACTIVE')))
        .catch(() => setTmplSqlViews([]))
        .finally(() => setSqlViewsLoading(false));
      // 清空其他上下文视图（避免残留）
      setSqlViews([]);
      setGlobalSqlViews([]);
    } else {
      // COMPONENT 上下文（包括 ownerContext.COMPONENT 和老 componentId prop 调用方）
      const promises: Array<Promise<void>> = [];
      const cid = effectiveComponentId;

      if (cid) {
        promises.push(
          componentSqlViewService
            .list(cid)
            .then((res) => setSqlViews((res.data ?? []).filter((v) => v.status === 'ACTIVE')))
            .catch(() => setSqlViews([])),
        );
      } else {
        setSqlViews([]);
      }

      promises.push(
        componentSqlViewService
          .listGlobal()
          .then((res) => setGlobalSqlViews((res.data ?? []).filter((v) => v.status === 'ACTIVE')))
          .catch(() => setGlobalSqlViews([])),
      );

      // 清空模板上下文视图（避免残留）
      setTmplSqlViews([]);

      Promise.allSettled(promises).finally(() => setSqlViewsLoading(false));
    }
  }, [open, ownerContext, effectiveComponentId]);

  // 后端 declared_columns 是 JSONB —— Quarkus Hibernate 序列化时可能返字符串或已 parse 的数组
  // 防御性 normalize：已是数组 / JSON 字符串 / 空 三种形态兼容
  const parseDeclaredColumns = (raw: unknown): SqlViewColumn[] => {
    if (!raw) return [];
    if (Array.isArray(raw)) return raw as SqlViewColumn[];
    if (typeof raw === 'string') {
      try {
        const parsed = JSON.parse(raw);
        return Array.isArray(parsed) ? parsed : [];
      } catch {
        return [];
      }
    }
    return [];
  };

  // SQL 视图模式（组件上下文）：生成的 BNF path
  const selectedSqlView = [...sqlViews, ...globalSqlViews].find((v) => v.id === selectedSqlViewId);
  const sqlViewColumns: SqlViewColumn[] = parseDeclaredColumns(selectedSqlView?.declaredColumns);
  const generatedSqlPath = (() => {
    if (!selectedSqlView || !selectedSqlColumn) return '';
    const isGlobal = selectedSqlView.scope === 'GLOBAL' && selectedSqlView.componentId !== effectiveComponentId;
    // 跨组件引用使用 componentCode（业务标识符），与后端 SqlViewPathRewriter 协议对齐
    const prefix = isGlobal
      ? `$$${selectedSqlView.componentCode ?? selectedSqlView.componentId}.${selectedSqlView.sqlViewName}`
      : `$${selectedSqlView.sqlViewName}`;
    const predStr = sqlExtraPredicate.trim() ? `[${sqlExtraPredicate.trim()}]` : '';
    return `${prefix}${predStr}.${selectedSqlColumn}`;
  })();

  // SQL 视图模式（template 实体上下文）：生成的 BNF path
  const selectedTmplSqlView = tmplSqlViews.find((v) => v.id === selectedTmplSqlViewId);
  const tmplSqlViewColumns: SqlViewColumn[] = parseDeclaredColumns(selectedTmplSqlView?.declaredColumns);
  const generatedTmplSqlPath = (() => {
    if (!selectedTmplSqlView || !selectedTmplSqlColumn) return '';
    const predStr = tmplSqlExtraPredicate.trim() ? `[${tmplSqlExtraPredicate.trim()}]` : '';
    return `$${selectedTmplSqlView.sqlViewName}${predStr}.${selectedTmplSqlColumn}`;
  })();

  // 生成的路径变化时同步 pathExpr（Task 6.1: 始终在 sql-view，无需 activeTab 守卫）
  useEffect(() => {
    const path =
      ownerContext?.type === 'TEMPLATE'
        ? generatedTmplSqlPath
        : generatedSqlPath;
    if (path) {
      setPathExpr(path);
    }
  }, [generatedSqlPath, generatedTmplSqlPath, ownerContext]);

  const reset = () => {
    setPathExpr('');
    setSelectedSqlViewId(undefined);
    setSelectedSqlColumn(undefined);
    setSqlExtraPredicate('');
    setSelectedTmplSqlViewId(undefined);
    setSelectedTmplSqlColumn(undefined);
    setTmplSqlExtraPredicate('');
  };

  const handleClose = () => {
    reset();
    onClose();
  };

  const handleConfirm = () => {
    const expr = pathExpr.trim();
    if (!expr) return;
    // TEMPLATE 上下文：阻止 $$ 路径（隔离规则）
    if (ownerContext?.type === 'TEMPLATE' && expr.startsWith('$$')) return;
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
            disabled={!pathExpr.trim()}
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

      {/* Task 6.1: 手动输入 Tab 已移除，直接显示 SQL 视图内容 */}
      <Spin spinning={sqlViewsLoading}>
        {ownerContext?.type === 'TEMPLATE' ? (
          // ── TEMPLATE 上下文：仅显示 template 实体的 SQL 视图（隔离设计）──
          <>
            <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
              选择本模板配置拥有的 SQL 视图，生成{' '}
              <Text code>$视图名.列名</Text>{' '}
              格式的 BNF path。模板视图与组件视图完全隔离。
            </Paragraph>

            <Text strong style={{ fontSize: 13 }}>本模板 SQL 视图</Text>
            {tmplSqlViews.length === 0 ? (
              <div style={{ color: '#999', fontSize: 12, margin: '6px 0 12px' }}>
                （本模板尚无 SQL 视图，请在「SQL 视图」Tab 中新建）
              </div>
            ) : (
              <Radio.Group
                value={selectedTmplSqlViewId}
                onChange={(e) => {
                  setSelectedTmplSqlViewId(e.target.value);
                  setSelectedTmplSqlColumn(undefined);
                  setTmplSqlExtraPredicate('');
                }}
                style={{ display: 'block', margin: '6px 0 12px' }}
              >
                <Space direction="vertical" size={4}>
                  {tmplSqlViews.map((v) => (
                    <Radio key={v.id} value={v.id}>
                      <Tag color="success" style={{ marginRight: 4 }}>模板视图</Tag>
                      <Text code style={{ marginRight: 8 }}>${v.sqlViewName}</Text>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        ({parseDeclaredColumns(v.declaredColumns as unknown).length} 列)
                      </Text>
                    </Radio>
                  ))}
                </Space>
              </Radio.Group>
            )}

            {/* 选中视图后：选列 + 额外谓词 */}
            {selectedTmplSqlView && (
              <Form layout="vertical" size="small" style={{ marginTop: 8 }}>
                <Form.Item label="选择列">
                  <Select
                    placeholder="选择要引用的列"
                    value={selectedTmplSqlColumn}
                    onChange={(v) => setSelectedTmplSqlColumn(v)}
                    options={tmplSqlViewColumns.map((c) => ({
                      value: c.name,
                      label: `${c.name} (${c.dataType})`,
                    }))}
                    showSearch
                    optionFilterProp="label"
                  />
                </Form.Item>
                <Form.Item
                  label="额外谓词（可选）"
                  tooltip="在 SQL 视图查询结果之外再加筛选"
                >
                  <Input
                    placeholder="如 bom_type='ELEMENT' 或留空"
                    value={tmplSqlExtraPredicate}
                    onChange={(e) => setTmplSqlExtraPredicate(e.target.value)}
                    style={{ fontFamily: 'Consolas, Monaco, monospace' }}
                  />
                </Form.Item>
              </Form>
            )}

            {/* 生成的路径预览 */}
            {generatedTmplSqlPath && (
              <Alert
                type="success"
                message="生成的 BNF path（本模板视图）"
                description={<Text code style={{ fontSize: 13 }}>{generatedTmplSqlPath}</Text>}
                style={{ marginTop: 8 }}
              />
            )}
          </>
        ) : (
          // ── COMPONENT / 默认 上下文：显示本组件 + 跨组件 GLOBAL 视图 ──
          // driverViewPath 传入时：只列 driver 视图的列，点列即确认（C2 纯点选体验）
          // driverViewPath 未传时：原有行为——Radio 选视图 + 下拉选列 + 谓词输入
          (() => {
            const driverViewName = extractSqlViewName(driverViewPath);
            const allComponentViews = [...sqlViews, ...globalSqlViews];

            if (driverViewName) {
              // C2 模式：过滤到 driver 视图，点列即确认
              const visibleViews = allComponentViews.filter(
                (v) => v.sqlViewName === driverViewName,
              );
              return (
                <>
                  <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
                    字段路径限定在 driver 视图 <Text code>${driverViewName}</Text> 的列，点击列名即选中并确认。
                  </Paragraph>
                  <ViewColumnPickerBody
                    views={visibleViews}
                    selectedViewName={driverViewName}
                    onSelectView={undefined}
                    driverOnly
                    currentPath={pathExpr || undefined}
                    onPick={(path, label) => {
                      onConfirm(path, label);
                      reset();
                      onClose();
                    }}
                  />
                </>
              );
            }

            // 原有行为（未传 driverViewPath）
            return (
              <>
                <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
                  选择本组件或跨组件 GLOBAL 的 SQL 视图，生成 <Text code>$视图名[谓词].列名</Text> 格式的 BNF path。
                </Paragraph>

                {/* 本组件 SQL 视图 */}
                {effectiveComponentId && (
                  <>
                    <Text strong style={{ fontSize: 13 }}>本组件 SQL 视图</Text>
                    {sqlViews.length === 0 ? (
                      <div style={{ color: '#999', fontSize: 12, margin: '6px 0 12px' }}>
                        （本组件尚无 SQL 视图，请在「SQL 视图」Tab 中新建）
                      </div>
                    ) : (
                      <Radio.Group
                        value={selectedSqlViewId}
                        onChange={(e) => {
                          setSelectedSqlViewId(e.target.value);
                          setSelectedSqlColumn(undefined);
                          setSqlExtraPredicate('');
                        }}
                        style={{ display: 'block', margin: '6px 0 12px' }}
                      >
                        <Space direction="vertical" size={4}>
                          {sqlViews.map((v) => (
                            <Radio key={v.id} value={v.id}>
                              <Text code style={{ marginRight: 8 }}>${v.sqlViewName}</Text>
                              <Text type="secondary" style={{ fontSize: 12 }}>
                                ({parseDeclaredColumns(v.declaredColumns).length} 列)
                              </Text>
                            </Radio>
                          ))}
                        </Space>
                      </Radio.Group>
                    )}
                  </>
                )}

                {/* 跨组件 GLOBAL SQL 视图 */}
                <Text strong style={{ fontSize: 13 }}>跨组件 GLOBAL SQL 视图</Text>
                {globalSqlViews.filter((v) => !effectiveComponentId || v.componentId !== effectiveComponentId).length === 0 ? (
                  <div style={{ color: '#999', fontSize: 12, margin: '6px 0 12px' }}>
                    （暂无 scope=GLOBAL 的 SQL 视图）
                  </div>
                ) : (
                  <Radio.Group
                    value={selectedSqlViewId}
                    onChange={(e) => {
                      setSelectedSqlViewId(e.target.value);
                      setSelectedSqlColumn(undefined);
                      setSqlExtraPredicate('');
                    }}
                    style={{ display: 'block', margin: '6px 0 12px' }}
                  >
                    <Space direction="vertical" size={4}>
                      {globalSqlViews
                        .filter((v) => !effectiveComponentId || v.componentId !== effectiveComponentId)
                        .map((v) => (
                          <Radio key={v.id} value={v.id}>
                            <Text code style={{ marginRight: 8 }}>
                              $${v.componentCode ?? v.componentId}.{v.sqlViewName}
                            </Text>
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              ({parseDeclaredColumns(v.declaredColumns).length} 列)
                            </Text>
                          </Radio>
                        ))}
                    </Space>
                  </Radio.Group>
                )}

                {/* 选中 SQL 视图后：选列 + 额外谓词 */}
                {selectedSqlView && (
                  <Form layout="vertical" size="small" style={{ marginTop: 8 }}>
                    <Form.Item label="选择列">
                      <Select
                        placeholder="选择要引用的列"
                        value={selectedSqlColumn}
                        onChange={(v) => setSelectedSqlColumn(v)}
                        options={sqlViewColumns.map((c) => ({
                          value: c.name,
                          label: `${c.name} (${c.dataType})`,
                        }))}
                        showSearch
                        optionFilterProp="label"
                      />
                    </Form.Item>
                    {!disablePredicate && (
                      <Form.Item
                        label="额外谓词（可选）"
                        tooltip="在 SQL 视图查询结果之外再加筛选，如 bom_type='ELEMENT'"
                      >
                        <Input
                          placeholder="如 bom_type='ELEMENT' 或留空"
                          value={sqlExtraPredicate}
                          onChange={(e) => setSqlExtraPredicate(e.target.value)}
                          style={{ fontFamily: 'Consolas, Monaco, monospace' }}
                        />
                      </Form.Item>
                    )}
                  </Form>
                )}

                {/* 生成的路径预览 */}
                {generatedSqlPath && (
                  <Alert
                    type="success"
                    message="生成的 BNF path"
                    description={<Text code style={{ fontSize: 13 }}>{generatedSqlPath}</Text>}
                    style={{ marginTop: 8 }}
                  />
                )}
              </>
            );
          })()
        )}
      </Spin>

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
