/**
 * SummaryFingerprintPanel — 选配添加·右侧 3D 预览常驻面板 + 底部指纹状态提示条（task-0712 F5，D15；
 * F5 前端协同收口消费缺口2·3a 后更新）。
 *
 * 拆两个导出组件（对应原型两处不同 DOM 位置，非同一容器）：
 * - `Preview3DPanel`：`.detail-right` 常驻 3D 预览框。跟随最近一次操作的材质实时刷新（D3/D15）；
 *   一旦汇总步指纹预览命中已有销售料号，则切换为该料号的 3D（对齐原型 `renderFingerprintDemo`/
 *   `updatePreview` 的 `fingerprintHit` 分支：`✅ 匹配到已有销售料号 SP-xxxx` 时预览框标 "料号3D"）。
 * - `FingerprintStatus`：`.df` 底部指纹提示条。`matched=true` 显示 "✅ 匹配到已有销售料号
 *   {matchedPartNo}，将带出其内容与 3D"；`matched=false` 显示 "🆕 将新建选配产品"。
 *
 * `POST /configure-product/lookup-fingerprint` 已在缺口2(3a) 完成重写：改查与提交端 `configure()
 * → resolvePart` 完全同源的销售侧客户维度指纹（`SalesFingerprintCalculator` + `SalesSignatureRepository`），
 * 不再是旧版"选配落库指纹恒 NULL"的桩实现，保证「预览命中」= 「提交命中」。调用方（`ConfigureProductDrawer`）
 * 在明细表/组合工艺条件变化时防抖调用本端点做确认前实时预览；提交后仍以
 * `ConfigureProductResponse.fingerprintMatched` 的 toast 兜底最终结果。
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
  /** 防抖后的 `/lookup-fingerprint` 请求是否在途。 */
  checking: boolean;
  matched: boolean;
  matchedPartNo?: string;
}

export const FingerprintStatus: React.FC<FingerprintStatusProps> = ({ rowCount, checking, matched, matchedPartNo }) => {
  if (rowCount === 0) {
    return <span style={{ fontSize: 12.5, color: '#c0c4cc' }}>尚未新增材质料号</span>;
  }
  if (checking) {
    return (
      <Tag color="default" style={{ fontSize: 12, padding: '3px 10px', borderRadius: 12 }}>
        校验中…
      </Tag>
    );
  }
  if (matched && matchedPartNo) {
    return (
      <Tag color="green" style={{ fontSize: 12, padding: '3px 10px', borderRadius: 12 }}>
        ✅ 匹配到已有销售料号 {matchedPartNo}，将带出其内容与 3D
      </Tag>
    );
  }
  return (
    <Tag color="blue" style={{ fontSize: 12, padding: '3px 10px', borderRadius: 12 }}>
      🆕 将新建选配产品
    </Tag>
  );
};
