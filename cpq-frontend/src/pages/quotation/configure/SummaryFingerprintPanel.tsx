/**
 * SummaryFingerprintPanel — 选配添加·右侧 3D 预览常驻面板 + 底部指纹状态提示条（task-0712 F5，D15）。
 *
 * 拆两个导出组件（对应原型两处不同 DOM 位置，非同一容器）：
 * - `Preview3DPanel`：`.detail-right` 常驻 3D 预览框，跟随最近一次操作的材质实时刷新（D3/D15）。
 * - `FingerprintStatus`：`.df` 底部指纹提示条。
 *
 * ⚠️ 已验证的架构限制（未按原型 demo 字面实现"确认前实时命中/新建"切换，非遗漏，是证据支持的
 * 有意简化，详见开发记录）：`POST /configure-product/lookup-fingerprint` 后端实现明确注释
 * "3b 后选配 custom/COMPOSITE 落库的 config_fingerprint 一律为 NULL，故本端点对新选配报价料号
 * 恒返 matched=false"（`ConfigureProductService.java` lookupFingerprint 方法头注 + TODO(3a)），
 * 即该端点对本功能新建的材质组合结构性永远返回"未命中"，据此预览无参考价值、反而可能误导
 * （让用户误以为"确认新建"是权威判定）。真正的客户维度去重在 `POST .../configure-product/
 * quotations/{id}` 提交时通过 `sel_part_signature` 生效（后端文档承认"P2 仅失去实时复用提示，
 * 提交时去重仍生效"，属已知可接受限制）。故本组件只做诚实的中性提示，真实新建/复用结果通过
 * 提交后 `ConfigureProductResponse.fingerprintMatched` 的 toast 呈现（见 ConfigureProductDrawer）。
 */
import React, { useEffect, useState } from 'react';
import { Tag } from 'antd';
import type { ModelSubjectType, ModelConfigDTO } from '../../../types/modelConfig';

export type PreviewMode = 'material' | 'salespart' | null;

interface Preview3DPanelProps {
  mode: PreviewMode;
  materialLabel: string;
  materialCode: string | null;
  loading: boolean;
  modelData: ModelConfigDTO | null;
}

export const Preview3DPanel: React.FC<Preview3DPanelProps> = ({ mode, materialLabel, materialCode, loading, modelData }) => {
  const [zoomHint, setZoomHint] = useState(false);

  useEffect(() => {
    setZoomHint(false);
  }, [mode, materialCode]);

  const subjectTypeLabel: ModelSubjectType | null = mode === 'material' ? 'MATERIAL' : mode === 'salespart' ? 'SALES_PART' : null;

  let boxContent: React.ReactNode;
  let capContent: React.ReactNode;

  if (!mode) {
    boxContent = <span style={{ fontSize: 30 }}>🧊</span>;
    capContent = <span style={{ color: '#909399' }}>请新增材质料号后预览 3D</span>;
  } else if (loading) {
    boxContent = <span style={{ fontSize: 30 }}>🧊</span>;
    capContent = <span style={{ color: '#909399' }}>加载中…</span>;
  } else if (modelData) {
    boxContent = (
      <>
        <span
          style={{
            position: 'absolute',
            top: 8,
            left: 8,
            padding: '2px 8px',
            borderRadius: 10,
            fontSize: 11,
            background: 'rgba(255,255,255,.85)',
            color: '#606266',
          }}
        >
          {subjectTypeLabel === 'MATERIAL' ? '材质3D' : '料号3D'}
        </span>
        {!modelData.thumbnailUrl && <span style={{ fontSize: 34 }}>🧊</span>}
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            setZoomHint(true);
            window.setTimeout(() => setZoomHint(false), 2000);
          }}
          style={{
            position: 'absolute', top: 8, right: 8, padding: '3px 9px', fontSize: 12,
            background: '#fff', border: '1px solid #dcdfe6', borderRadius: 4, cursor: 'pointer', color: '#606266',
          }}
        >
          ⤢ 交互查看
        </button>
        <div
          style={{
            position: 'absolute', left: 10, right: 10, bottom: 10, background: 'rgba(0,0,0,.72)', color: '#fff',
            fontSize: 12, padding: '7px 10px', borderRadius: 4, opacity: zoomHint ? 1 : 0, pointerEvents: 'none',
            transition: 'opacity .2s', textAlign: 'center',
          }}
        >
          （可旋转 3D 模型，增强项）
        </div>
      </>
    );
    capContent = (
      <>
        {subjectTypeLabel === 'MATERIAL' ? `材质: ${materialLabel || materialCode}` : `销售料号: ${materialCode}`}
        <br />
        模型：{modelData.label || '—'}
      </>
    );
  } else {
    boxContent = <span style={{ fontSize: 30 }}>🚫</span>;
    capContent = (
      <>
        {subjectTypeLabel === 'MATERIAL' ? `材质: ${materialLabel || materialCode}` : `销售料号: ${materialCode}`}
        <br />
        <span style={{ color: '#909399' }}>未配置 3D 模型</span>
      </>
    );
  }

  return (
    <div style={{ border: '1px solid #e4e7ed', borderRadius: 8, overflow: 'hidden', background: '#fff' }}>
      <div
        style={{
          aspectRatio: '1/1',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          position: 'relative',
          color: '#7a8aa8',
          // loading 态强制素色背景，避免切换材质时旧缩略图在"加载中…"文案后方闪现（AP-31 精神：
          // loading 态必须有独立、不含陈旧数据的渲染分支，对齐 AddProductModal renderPreviewBox 同款处理）。
          background: !mode || loading
            ? '#fafafa'
            : modelData?.thumbnailUrl
              ? `url(${modelData.thumbnailUrl}) center/cover no-repeat`
              : 'linear-gradient(135deg,#e6f0ff,#dfe7f5)',
        }}
      >
        {boxContent}
      </div>
      <div style={{ padding: '10px 12px', fontSize: 12.5, color: '#606266', borderTop: '1px solid #f0f0f0', lineHeight: 1.6 }}>
        {capContent}
      </div>
    </div>
  );
};

interface FingerprintStatusProps {
  rowCount: number;
}

export const FingerprintStatus: React.FC<FingerprintStatusProps> = ({ rowCount }) => {
  if (rowCount === 0) {
    return <span style={{ fontSize: 12.5, color: '#c0c4cc' }}>尚未新增材质料号</span>;
  }
  return (
    <Tag color="blue" style={{ fontSize: 12, padding: '3px 10px', borderRadius: 12 }}>
      ℹ️ 确认加入后系统将自动判定新建 / 复用已有销售料号
    </Tag>
  );
};
