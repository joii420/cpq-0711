import React, { useEffect, useState } from 'react';
import { Drawer, Button, Table, Tag, Typography, message, Alert, Popconfirm, Space, Empty } from 'antd';
import { CheckCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  componentService,
  type ConsolidateItem,
  type ConsolidateResult,
  type ConsolidateStatus,
} from '../../services/componentService';

const { Text } = Typography;

interface Props {
  open: boolean;
  directoryId: string | null;
  directoryName?: string;
  onClose: () => void;
  /** 固化写库成功后回调（用于刷新组件树，清除卡片上的「待绑定」标记）。 */
  onConsolidated?: () => void;
}

const STATUS_TAG: Record<ConsolidateStatus, { color: string; text: string }> = {
  CONSOLIDATED: { color: 'green', text: '将固化' },
  UNRESOLVABLE: { color: 'red', text: '无法解析' },
  ERROR: { color: 'orange', text: '处理出错' },
};

/**
 * task-0805 F3/R4：一键固化公式绑定 —— 目录级 dryRun 清单 → 用户核对 → 确认写库。
 *
 * 端点 `POST /api/cpq/admin/formula-binding/consolidate` 仅 `@RoleAllowed({"SYSTEM_ADMIN"})`，
 * 而组件管理页允许 `SALES_MANAGER` 进入 —— 非管理员点固化会拿到 403，本抽屉需给出可读提示，不能白屏。
 */
const FormulaBindingConsolidateDrawer: React.FC<Props> = ({ open, directoryId, directoryName, onClose, onConsolidated }) => {
  const [loading, setLoading] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [result, setResult] = useState<ConsolidateResult | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const runDryRun = async (dirId: string) => {
    setLoading(true);
    setErrorMsg(null);
    try {
      const r = await componentService.consolidateFormulaBinding({ dryRun: true, directoryId: dirId });
      setResult(r);
    } catch (e: any) {
      setResult(null);
      setErrorMsg(e?.httpStatus === 403
        ? '需要系统管理员权限才能执行固化操作，请联系管理员后重试。'
        : (e?.message ?? '预览失败'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open && directoryId) {
      runDryRun(directoryId);
    } else {
      setResult(null);
      setErrorMsg(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, directoryId]);

  const handleClose = () => {
    setResult(null);
    setErrorMsg(null);
    onClose();
  };

  // dryRun 响应体的 componentsUpdated 恒为 0（后端只在真正 UPDATE 时才计数）——
  // 预览阶段"会影响多少组件/字段"必须从 items 按 status 自行统计，不能读 componentsUpdated。
  const items: ConsolidateItem[] = result?.items ?? [];
  const consolidatedItems = items.filter((i) => i.status === 'CONSOLIDATED');
  const unresolvableFieldCount = items.filter((i) => i.status === 'UNRESOLVABLE').length;
  const errorFieldCount = items.filter((i) => i.status === 'ERROR').length;
  const distinctComponentsToConsolidate = new Set(consolidatedItems.map((i) => i.componentCode)).size;
  const hasItems = items.length > 0;
  const confirmDisabled = !hasItems || !!errorMsg || loading || distinctComponentsToConsolidate === 0;

  const doConsolidate = async () => {
    if (!directoryId) return;
    setConfirming(true);
    try {
      const r = await componentService.consolidateFormulaBinding({ dryRun: false, directoryId });
      message.success(`已固化 ${r.componentsUpdated} 个组件的公式绑定`);
      onConsolidated?.();
      handleClose();
    } catch (e: any) {
      setErrorMsg(e?.httpStatus === 403
        ? '需要系统管理员权限才能执行固化操作，请联系管理员后重试。'
        : (e?.message ?? '固化失败'));
    } finally {
      setConfirming(false);
    }
  };

  const columns: ColumnsType<ConsolidateItem> = [
    {
      title: '组件', dataIndex: 'componentCode', width: 170,
      render: (v: string, r) => <span>{v}{r.componentName ? <Text type="secondary"> ({r.componentName})</Text> : null}</span>,
    },
    { title: '字段', dataIndex: 'fieldName', width: 160, render: (v?: string) => v ?? '—' },
    {
      title: '将绑到的公式', dataIndex: 'resolvedFormulaName',
      render: (v: string | null | undefined, r) => v ?? (r.message ? <Text type="danger">{r.message}</Text> : '—'),
    },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: (s: ConsolidateStatus) => { const t = STATUS_TAG[s] || { color: 'default', text: s }; return <Tag color={t.color}>{t.text}</Tag>; },
    },
  ];

  return (
    <Drawer
      title={`固化公式绑定：${directoryName ?? ''}`}
      placement="right"
      width={720}
      open={open}
      onClose={handleClose}
      destroyOnClose
      footer={
        <Space style={{ float: 'right' }}>
          <Button onClick={handleClose}>关闭</Button>
          <Popconfirm
            title={`确认固化 ${distinctComponentsToConsolidate} 个组件、共 ${consolidatedItems.length} 处字段的公式绑定？`}
            description="该操作会写库且不可撤销；固化的是字段今天实际在用的那条公式，算出来的钱不会变。"
            okText="确认固化"
            okButtonProps={{ danger: true }}
            cancelText="取消"
            disabled={confirmDisabled}
            onConfirm={doConsolidate}
          >
            <Button type="primary" icon={<CheckCircleOutlined />} loading={confirming} disabled={confirmDisabled}>
              确认固化
            </Button>
          </Popconfirm>
        </Space>
      }
    >
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        {errorMsg && (
          <Alert
            type="error"
            showIcon
            message={errorMsg}
            action={
              directoryId
                ? <Button size="small" icon={<ReloadOutlined />} onClick={() => runDryRun(directoryId)}>重试</Button>
                : undefined
            }
          />
        )}

        {!errorMsg && !loading && result && !hasItems && (
          <Empty description="该目录下没有需要固化的公式绑定" />
        )}

        {!errorMsg && hasItems && distinctComponentsToConsolidate === 0 && (
          <Alert
            type="warning"
            showIcon
            message="预览中的字段均无法自动解析，固化无法解决"
            description="请先在组件详情的字段配置中手工选择公式，或联系模板作者补充公式名称后再重试固化。"
          />
        )}

        {!errorMsg && hasItems && (
          <>
            <Alert
              type="info"
              showIcon
              message={
                `预览：${consolidatedItems.length} 处字段可自动解析并固化(涉及 ${distinctComponentsToConsolidate} 个组件)`
                + `；${unresolvableFieldCount} 处仍无法解析`
                + (errorFieldCount > 0 ? `；${errorFieldCount} 处处理出错` : '')
                + '。'
              }
            />
            <Table
              size="small"
              rowKey={(r, i) => `${r.componentCode}-${r.fieldName ?? i}`}
              loading={loading}
              columns={columns}
              dataSource={items}
              pagination={false}
              scroll={{ y: 420 }}
            />
          </>
        )}
      </Space>
    </Drawer>
  );
};

export default FormulaBindingConsolidateDrawer;
