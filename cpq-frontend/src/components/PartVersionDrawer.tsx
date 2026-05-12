import React, { useEffect, useState } from 'react';
import {
  Drawer, Button, Space, Tag, Typography, Divider, Spin, message,
  Select, Modal, Empty, Descriptions,
} from 'antd';
import {
  ReloadOutlined, ArrowUpOutlined, SwapOutlined, InfoCircleOutlined,
} from '@ant-design/icons';
import {
  partVersionService,
  type PartVersionLog,
  type VersionDecision,
  type DecisionAction,
} from '../services/partVersionService';

const { Text } = Typography;

interface Props {
  open: boolean;
  onClose: () => void;
  customerProductNo: string;
  hfPartNo: string;
  /** 升版/切版本成功后回调 (传新版本号) */
  onApplied?: (newVersion: number) => void;
  /**
   * 模式:
   * - 'manage' (默认): 完整版本管理 — 三路判定 + 升版 + 切换全局激活版本.
   *   适用于"料号版本管理"独立入口页, 用于主数据维护.
   * - 'select': 简化版本选择 — 只列出历史版本, 点击即切换本行报价单使用的版本.
   *   适用于报价单产品卡片内的版本切换. 不显示判定/升版/切换全局激活版本.
   *   升版仅在数据导入时确定, 报价单内仅做"选择已存在的版本".
   */
  mode?: 'manage' | 'select';
  /**
   * select 模式下, 当前 line_item 已锁定的版本号 (高亮显示).
   * manage 模式忽略.
   */
  lockedVersion?: number;
}

const ACTION_LABEL: Record<DecisionAction, string> = {
  NO_CHANGE: '无变更',
  REVERT_TO_HISTORICAL: '切回历史版本',
  NEW_VERSION: '升级到新版本',
};

const ACTION_COLOR: Record<DecisionAction, string> = {
  NO_CHANGE: 'default',
  REVERT_TO_HISTORICAL: 'orange',
  NEW_VERSION: 'green',
};

const PartVersionDrawer: React.FC<Props> = ({
  open, onClose, customerProductNo, hfPartNo, onApplied,
  mode = 'manage', lockedVersion,
}) => {
  const isSelectMode = mode === 'select';
  const [loading, setLoading] = useState(false);
  const [currentVersion, setCurrentVersion] = useState<number>(2000);
  const [history, setHistory] = useState<PartVersionLog[]>([]);
  const [proposing, setProposing] = useState(false);
  const [decision, setDecision] = useState<VersionDecision | null>(null);
  const [switchTarget, setSwitchTarget] = useState<number | undefined>();

  const reload = async () => {
    if (!customerProductNo || !hfPartNo) return;
    setLoading(true);
    try {
      const info = await partVersionService.getVersionInfo(customerProductNo, hfPartNo);
      setCurrentVersion(info.currentVersion);
      setHistory(info.history || []);
      setSwitchTarget(info.currentVersion);
      setDecision(null);
    } catch (e) {
      message.error('加载版本信息失败: ' + (e as Error).message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open && customerProductNo && hfPartNo) reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, customerProductNo, hfPartNo]);

  const handlePropose = async () => {
    setProposing(true);
    try {
      const d = await partVersionService.propose({
        customerProductNo, hfPartNo, rowsByTable: {},
      });
      setDecision(d);
    } catch (e) {
      message.error('判定失败: ' + (e as Error).message);
    } finally {
      setProposing(false);
    }
  };

  const handleApplyBump = () => {
    Modal.confirm({
      title: `升级版本到 v${decision?.proposedVersion}?`,
      content: '该操作将写入 mat_part_version_log 并把当前激活版本号 +1. 已发布报价单不受影响 (锁定旧版本).',
      okText: '确认升版',
      cancelText: '取消',
      onOk: async () => {
        try {
          const r = await partVersionService.apply(customerProductNo, hfPartNo, {
            contentHash: null,
            sourceExcel: 'manual-bump-from-UI',
            diffByTable: null,
          });
          message.success(`升版成功: v${r.newVersion}`);
          onApplied?.(r.newVersion);
          await reload();
        } catch (e) {
          message.error('升版失败: ' + (e as Error).message);
        }
      },
    });
  };

  const handleSwitchVersion = () => {
    if (switchTarget == null || switchTarget === currentVersion) {
      message.info('请选一个不同于当前激活版本的历史版本');
      return;
    }
    Modal.confirm({
      title: `切换激活版本到 v${switchTarget}?`,
      content: '后续新建的报价单将默认使用此版本数据. 已发布报价单不变.',
      okText: '确认切换',
      cancelText: '取消',
      onOk: async () => {
        try {
          const r = await partVersionService.switchVersion(customerProductNo, hfPartNo, switchTarget);
          message.success(`已切换到 v${r.activeVersion}`);
          onApplied?.(r.activeVersion);
          await reload();
        } catch (e) {
          message.error('切换失败: ' + (e as Error).message);
        }
      },
    });
  };

  return (
    <Drawer
      title={
        <Space>
          <span>料号版本管理</span>
          <Tag color="blue">{customerProductNo}</Tag>
          <Tag color="cyan">{hfPartNo}</Tag>
        </Space>
      }
      width={720}
      open={open}
      onClose={onClose}
      extra={<Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>}
    >
      {loading ? (
        <Spin tip="加载中..." />
      ) : (
        <>
          <Descriptions bordered size="small" column={1} style={{ marginBottom: 16 }}>
            <Descriptions.Item label="客户产品编号">{customerProductNo}</Descriptions.Item>
            <Descriptions.Item label="宏丰料号">{hfPartNo}</Descriptions.Item>
            {isSelectMode && lockedVersion != null && (
              <Descriptions.Item label="本行使用版本">
                <Tag color="green" style={{ fontSize: 14 }}>v{lockedVersion}</Tag>
              </Descriptions.Item>
            )}
            <Descriptions.Item label="当前激活版本">
              <Tag color={isSelectMode ? 'default' : 'green'} style={{ fontSize: 14 }}>v{currentVersion}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="历史版本数">{history.length}</Descriptions.Item>
          </Descriptions>

          {!isSelectMode && (
            <>
              <Divider orientation="left">
                <Space>
                  <ArrowUpOutlined />
                  <span>三路判定</span>
                </Space>
              </Divider>
              <Text type="secondary" style={{ fontSize: 12 }}>
                <InfoCircleOutlined /> S2 阶段为占位实现, 默认建议 NEW_VERSION. S3 接入 Excel 解析后会真实判定 NO_CHANGE / REVERT / NEW_VERSION.
              </Text>
              <div style={{ marginTop: 12, marginBottom: 12 }}>
                <Button type="primary" loading={proposing} onClick={handlePropose}>
                  运行判定
                </Button>
              </div>

              {decision && (
                <div style={{ padding: 12, background: '#fafafa', borderRadius: 6, marginBottom: 16 }}>
                  <Space direction="vertical" style={{ width: '100%' }}>
                    <Tag color={ACTION_COLOR[decision.action]} style={{ fontSize: 14 }}>
                      {ACTION_LABEL[decision.action]}
                    </Tag>
                    <Text>建议版本号: <strong>v{decision.proposedVersion}</strong></Text>
                    {decision.matchedHash && (
                      <Text type="secondary" style={{ fontSize: 11, fontFamily: 'Consolas, monospace' }}>
                        匹配指纹: {decision.matchedHash}
                      </Text>
                    )}
                    {decision.action === 'NEW_VERSION' && (
                      <Button type="primary" icon={<ArrowUpOutlined />} onClick={handleApplyBump}>
                        执行升版到 v{decision.proposedVersion}
                      </Button>
                    )}
                  </Space>
                </div>
              )}

              <Divider orientation="left">
                <Space>
                  <SwapOutlined />
                  <span>切换激活版本</span>
                </Space>
              </Divider>
              <Space>
                <Select
                  style={{ width: 180 }}
                  value={switchTarget}
                  onChange={setSwitchTarget}
                  options={[
                    { label: `v${currentVersion} (当前)`, value: currentVersion },
                    ...history
                      .filter(h => h.version !== currentVersion)
                      .map(h => ({ label: `v${h.version}`, value: h.version })),
                  ]}
                />
                <Button icon={<SwapOutlined />} onClick={handleSwitchVersion}
                        disabled={switchTarget == null || switchTarget === currentVersion}>
                  切换到此版本
                </Button>
              </Space>
            </>
          )}

          <Divider orientation="left">
            {isSelectMode ? '选择本行使用的版本' : '历史版本'}
          </Divider>
          {history.length === 0 ? (
            <Empty description="暂无历史版本" />
          ) : (
            <div style={{ maxHeight: 360, overflowY: 'auto' }}>
              {history.map(h => {
                // select 模式高亮 = 本行锁定版本; manage 模式高亮 = 全局激活版本
                const isActive = isSelectMode
                  ? (lockedVersion != null && h.version === lockedVersion)
                  : h.version === currentVersion;
                const canClick = isSelectMode && !isActive;
                return (
                <div key={h.version}
                  onClick={canClick ? () => {
                    onApplied?.(h.version);
                    onClose();
                  } : undefined}
                  style={{
                  padding: 10, marginBottom: 8,
                  border: isActive ? '1px solid #b7eb8f' : '1px solid #f0f0f0',
                  borderRadius: 6,
                  background: isActive ? '#f6ffed' : '#fff',
                  cursor: canClick ? 'pointer' : 'default',
                  transition: 'all 0.2s',
                }}>
                  <Space>
                    <Tag color={isActive ? 'green' : 'default'}>
                      v{h.version}{isActive && isSelectMode ? ' (当前)' : ''}
                    </Tag>
                    {h.sourceExcel && <Text type="secondary" style={{ fontSize: 12 }}>{h.sourceExcel}</Text>}
                    {h.createdAt && (
                      <Text type="secondary" style={{ fontSize: 11 }}>
                        {new Date(h.createdAt).toLocaleString()}
                      </Text>
                    )}
                  </Space>
                  {h.contentHash && (
                    <div style={{ marginTop: 4, fontSize: 10, fontFamily: 'Consolas, monospace', color: '#bbb' }}>
                      {h.contentHash}
                    </div>
                  )}
                  {h.diffSummaryJson && (
                    <div style={{ marginTop: 4, fontSize: 11, color: '#888' }}>
                      diff: {h.diffSummaryJson}
                    </div>
                  )}
                </div>
                );
              })}
            </div>
          )}
        </>
      )}
    </Drawer>
  );
};

export default PartVersionDrawer;
