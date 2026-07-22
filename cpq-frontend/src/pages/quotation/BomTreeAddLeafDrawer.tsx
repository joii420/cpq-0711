// task-0721 F4：报价侧 BOM 树 —— 树上加叶子交互。
//
// 架构红线：候选料号列表本地采集（bomTreeLeaf.ts collectBomLeafCandidates），不调用任何远程端点；
// 类型判定完全由后端完成（api.md §3），前端仅展示候选 + 发起请求 + 回灌返回的 quoteCardValues。
import React, { useMemo, useState } from 'react';
import { Alert, Button, Drawer, Empty, Input, List, Space, Tag, message } from 'antd';
import type { ApiError } from '../../services/api';
import { quotationService } from '../../services/quotationService';
import type { LineItem } from './QuotationStep2';
import { collectBomLeafCandidates } from './bomTreeLeaf';

export interface BomTreeAddLeafRequest {
  componentId: string;
  hostNodeId: string;
  /** 打开抽屉时前端本地已知的宿主节点类型（用于识别「数据漂移」场景，见 handleConfirm 400 分支） */
  hostNodeType?: string | null;
}

interface Props {
  item: LineItem;
  quotationId?: string;
  request: BomTreeAddLeafRequest | null;
  onClose: () => void;
  /** 成功后用整单 quoteCardValues 直接回灌（不二次拉取），见 api.md §3 */
  onApplied: (quoteCardValues: string) => void;
}

const BomTreeAddLeafDrawer: React.FC<Props> = ({ item, quotationId, request, onClose, onApplied }) => {
  const [keyword, setKeyword] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [conflictTabs, setConflictTabs] = useState<string[] | null>(null);

  const candidates = useMemo(() => (request ? collectBomLeafCandidates(item) : []), [request, item]);
  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    if (!kw) return candidates;
    return candidates.filter(
      (c) => c.partNo.toLowerCase().includes(kw) || c.sourceTabName.toLowerCase().includes(kw),
    );
  }, [candidates, keyword]);

  const reset = () => {
    setKeyword('');
    setSelected(null);
    setConflictTabs(null);
  };

  const handleClose = () => {
    reset();
    onClose();
  };

  const handleConfirm = async () => {
    if (!request || !selected) return;
    const lineItemId = (item as any).id || (item as any).tempId;
    if (!quotationId || !lineItemId) {
      message.warning('请先保存报价单后再新增叶子料号');
      return;
    }
    setSubmitting(true);
    setConflictTabs(null);
    try {
      const res = await quotationService.addTreeLeaf(quotationId, lineItemId, {
        componentId: request.componentId,
        hostNodeId: request.hostNodeId,
        partNo: selected,
      });
      const data = (res as any)?.data;
      if (data?.quoteCardValues) onApplied(data.quoteCardValues);
      message.success('已新增叶子料号');
      handleClose();
    } catch (e: unknown) {
      const err = e as ApiError;
      if (err.httpStatus === 409) {
        // 多页签冲突：展示 conflictTabs，提示用户先修正基础数据（api.md §3；仅命中不同类型页签才会 409）
        const tabs = (err.payload as any)?.conflictTabs;
        setConflictTabs(Array.isArray(tabs) ? tabs : []);
        message.warning(err.message || '该料号同时出现在多个不同类型的页签，请先修正基础数据');
      } else if (err.httpStatus === 400) {
        // 400 有三种独立场景，各自文案不合并展示（均取后端原文，不同场景不拼成一句话）：
        //   ① 宿主为材质/外购件 —— 理论上「+」已置灰，仍触发说明数据在预览期间发生了漂移
        //   ② 料号命中「主件」页签 —— 成品不能作为他人叶子挂入
        //   ③ 零命中 —— 该料号不是有效的报价产品
        // 识别①：api.md §3 给出的固定文案含"不可再添加下级"，与②③措辞（成品料号/不是有效的报价产品）
        // 不会混淆，用此子串精确匹配（非猜测——是契约里给定的错误文案），仅①追加"刷新"建议。
        const isHostDriftCase = (err.message || '').includes('不可再添加下级');
        message.error(
          isHostDriftCase
            ? `${err.message}（数据可能已变化，建议刷新报价单后重试）`
            : (err.message || '无法新增该叶子料号'),
        );
      } else {
        message.error(err.message || '新增失败');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Drawer
      title="新增叶子料号"
      placement="right"
      width={480}
      open={!!request}
      onClose={handleClose}
      destroyOnClose
      extra={
        <Space>
          <Button onClick={handleClose}>取消</Button>
          <Button type="primary" disabled={!selected} loading={submitting} onClick={handleConfirm}>
            确认新增
          </Button>
        </Space>
      }
    >
      <div style={{ marginBottom: 12, fontSize: 12, color: '#8c8c8c' }}>
        候选料号来自当前报价单各页签已渲染的行（本地去重，无需远程搜索）。选中后类型由系统按料号所在页签自动判定，
        新叶子的业务列留空，需手动填写。
        {request?.hostNodeType && <span>（宿主节点类型：{request.hostNodeType}）</span>}
      </div>
      {conflictTabs && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="该料号同时出现在多个不同类型的页签"
          description={
            <div>
              命中页签：{conflictTabs.length > 0 ? conflictTabs.join('、') : '（后端未返回明细）'}
              <div style={{ marginTop: 4 }}>请先修正基础数据配置，确保一个料号只归属一种业务类型。</div>
            </div>
          }
        />
      )}
      <Input.Search
        placeholder="按料号 / 页签名称过滤"
        allowClear
        value={keyword}
        onChange={(e) => setKeyword(e.target.value)}
        style={{ marginBottom: 12 }}
      />
      {filtered.length === 0 ? (
        <Empty description={candidates.length === 0 ? '当前报价单尚无可用料号（各页签暂无渲染行）' : '无匹配结果'} />
      ) : (
        <List
          size="small"
          bordered
          dataSource={filtered}
          style={{ maxHeight: 480, overflow: 'auto' }}
          renderItem={(c) => (
            <List.Item
              onClick={() => setSelected(c.partNo)}
              style={{
                cursor: 'pointer',
                background: selected === c.partNo ? '#e6f4ff' : undefined,
              }}
            >
              <Space>
                <span style={{ fontFamily: 'monospace' }}>{c.partNo}</span>
                <Tag>{c.sourceTabName}</Tag>
              </Space>
            </List.Item>
          )}
        />
      )}
    </Drawer>
  );
};

export default BomTreeAddLeafDrawer;
