// task-0721 F4：报价侧 BOM 树 —— 树上加叶子交互。
//
// 架构红线：候选料号列表本地采集（bomTreeLeaf.ts collectBomLeafCandidates），不调用任何远程端点；
// 类型判定完全由后端完成（api.md §3），前端仅展示候选 + 发起请求 + 回灌返回的 quoteCardValues。
//
// task-260904 F-5（AC-6 / AC-7）：新增两个拒绝态的**结构化展示**（原型图/加叶子拒绝态.html B、C 两屏）：
//   · LEAF_PART_NOT_IN_MASTER —— 必须点名料号 + 指路去建档，🚫 不许只弹一句「添加失败」
//   · LEAF_CYCLE_DETECTED     —— 必须展示环路径，🚫 不许只说「会成环」
// 两者都从一次性 message.error 升级为抽屉内常驻 Alert（错误信息要能被反复读、能抄料号，
// message 三秒就消失了，用户抄不到环路径）。
import React, { useMemo, useState } from 'react';
import { Alert, Button, Drawer, Empty, Input, List, Space, Tag, message } from 'antd';
import type { ApiError } from '../../services/api';
import { quotationService } from '../../services/quotationService';
import type { LineItem } from './QuotationStep2';
import { collectBomLeafCandidates } from './bomTreeLeaf';
// task-260904 F-5：拒绝态的错误码/环路径解析（独立模块——本文件只导出组件，
// 否则 react-refresh/only-export-components 报错，且纯函数放这儿也不好单测）
import { readBizCode, readCyclePath, type LeafRejectionCode } from './bomTreeLeafError';

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

/**
 * task-260904 F-5：加叶子的两个新拒绝态（api.md §3.3）。
 * `backendMessage` 恒保留 —— 契约只保证「文案里点名料号 / 给出环路径」，没保证有结构化字段，
 * 所以后端原文是环路径唯一的**兜底**载体，不能丢。
 */
interface LeafRejection {
  code: LeafRejectionCode;
  /** 本次提交的料号 —— 前端本地已知，不依赖后端回传（AC-6「必须点名料号」因此恒成立） */
  partNo: string;
  /** 结构化环路径（如 ['A','B','A']）。api.md §3.3 未声明该字段，后端给了就用、没给就退回 backendMessage。 */
  cyclePath?: string[];
  backendMessage: string;
}

const BomTreeAddLeafDrawer: React.FC<Props> = ({ item, quotationId, request, onClose, onApplied }) => {
  const [keyword, setKeyword] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [conflictTabs, setConflictTabs] = useState<string[] | null>(null);
  // task-260904 F-5（AC-6/AC-7）：两个新拒绝态常驻展示，选中别的料号 / 关闭抽屉时清掉。
  const [rejection, setRejection] = useState<LeafRejection | null>(null);

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
    setRejection(null);
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
    setRejection(null);
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
        // task-260904 F-5（AC-6/AC-7）：两个新错误码走结构化 Alert（原型 B/C 屏），不再混进 message。
        const bizCode = readBizCode(err);
        if (bizCode === 'LEAF_PART_NOT_IN_MASTER' || bizCode === 'LEAF_CYCLE_DETECTED') {
          setRejection({
            code: bizCode,
            partNo: selected,
            cyclePath: bizCode === 'LEAF_CYCLE_DETECTED' ? readCyclePath(err) : undefined,
            backendMessage: err.message || '',
          });
          return;   // 🚫 不再额外弹 message —— 同一件事弹两遍，用户会以为是两个错误
        }
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
      {/* task-260904 F-5（AC-6）· 原型「加叶子拒绝态.html」B 屏：料号不在主数据。
          料号取本地 selected，所以「点名料号」不依赖后端文案形态。 */}
      {rejection?.code === 'LEAF_PART_NOT_IN_MASTER' && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 12 }}
          message={<>料号「<span style={{ fontFamily: 'monospace' }}>{rejection.partNo}</span>」不存在，无法添加</>}
          description={<>该料号既不在<b>物料表</b>，也不在<b>材质库</b>。请先在基础资料中建档后再添加。</>}
        />
      )}
      {/* task-260904 F-5（AC-7）· 原型 C 屏：成环（C1 真环 / C2 自环）。
          环路径优先用后端结构化 cyclePath；契约只强制「文案给出环路径」，
          所以拿不到结构化字段时**原样展示后端文案**——绝不能退化成一句「会成环」。 */}
      {rejection?.code === 'LEAF_CYCLE_DETECTED' && (() => {
        const path = rejection.cyclePath;
        const selfLoop = !!path && path.length === 2 && path[0] === path[1];
        return (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 12 }}
            message={selfLoop ? '不能把料号挂到它自己下面' : '添加后会形成循环引用'}
            description={path ? (
              <>
                <div>环路径：<span style={{ fontFamily: 'monospace' }}>{path.join(' → ')}</span></div>
                {!selfLoop && path.length >= 3 && (
                  <div style={{ marginTop: 4 }}>
                    料号「{path[0]}」已是宿主节点「{path[path.length - 2]}」的上级，不能再挂到它下面。
                  </div>
                )}
              </>
            ) : (
              <div>{rejection.backendMessage || '该料号挂到此节点下会形成环。'}</div>
            )}
          />
        );
      })()}
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
              // 换一个料号 = 换一次尝试，上一次的拒绝态必须跟着清掉，否则会出现
              //「选的是 A，红框还写着 B 不存在」这种误导。
              onClick={() => { setSelected(c.partNo); setRejection(null); }}
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
