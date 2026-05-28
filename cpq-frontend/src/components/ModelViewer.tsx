import React from 'react';

// model-viewer Web Component 的 TypeScript 类型声明
// React 19 + jsx: "react-jsx" 下，IntrinsicElements 同时在 global JSX 与 React.JSX 两个命名空间下生效
type ModelViewerElement = React.DetailedHTMLProps<
  React.HTMLAttributes<HTMLElement> & {
    src?: string;
    alt?: string;
    poster?: string;
    'shadow-intensity'?: string;
    // Web Component boolean 属性: 传 '' / true 都视为开,undefined 关
    'camera-controls'?: boolean | string;
    'auto-rotate'?: boolean | string;
    'auto-rotate-delay'?: string;
    'rotation-per-second'?: string;
    'environment-image'?: string;
    'exposure'?: string;
    ar?: boolean | string;
    'ar-modes'?: string;
    loading?: 'lazy' | 'eager' | 'auto';
    reveal?: 'auto' | 'interaction' | 'manual';
    'camera-orbit'?: string;
    'field-of-view'?: string;
    'min-camera-orbit'?: string;
    'max-camera-orbit'?: string;
    'interaction-prompt'?: 'auto' | 'when-focused' | 'none';
  },
  HTMLElement
>;
declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace JSX {
    interface IntrinsicElements {
      'model-viewer': ModelViewerElement;
    }
  }
}
declare module 'react' {
  namespace JSX {
    interface IntrinsicElements {
      'model-viewer': ModelViewerElement;
    }
  }
}

/**
 * 公开 demo GLB（用于 mock URL 兜底）
 * 本地化 (内网无网部署), 源 https://modelviewer.dev/shared-assets/models/
 * 资源放 public/vendor/models/, 构建后位于 dist/vendor/models/
 */
const DEMO_GLBS: Record<string, string> = {
  valve:     '/vendor/models/Astronaut.glb',  // 阀门 demo 用航天员
  contact:   '/vendor/models/RobotExpressive.glb',
  default:   '/vendor/models/NeilArmstrong.glb',
};

function resolveGlbUrl(rawUrl?: string, category?: string): string {
  if (!rawUrl) return DEMO_GLBS[category || 'default'] || DEMO_GLBS.default;
  // mock 协议 cpq-3d-glb:// 用 demo 兜底
  if (rawUrl.startsWith('cpq-3d-glb://') || rawUrl.includes('mock/')) {
    if (rawUrl.toLowerCase().includes('valve') || category === '阀门') return DEMO_GLBS.valve;
    if (rawUrl.toLowerCase().includes('contact') || category === '接触片') return DEMO_GLBS.contact;
    return DEMO_GLBS.default;
  }
  return rawUrl;
}

interface Props {
  glbUrl?: string;
  category?: string;     // 用于 mock URL 兜底选择 demo
  height?: number | string;
  poster?: string;
  autoRotate?: boolean;
  cameraControls?: boolean;
  ar?: boolean;
  background?: string;
  showWatermark?: boolean;  // 显示「Demo Model」水印
  label?: string;          // 左下角文字标签
}

/**
 * 通用 3D 模型预览组件（Google model-viewer Web Component 包装）
 *
 * - 支持 GLB / GLTF 文件
 * - 自动旋转 + 拖拽控制 + AR 选项
 * - 假 URL（cpq-3d-glb://...）会兜底到公开 demo GLB，确保所有「base 模型预览」都能渲染
 */
const ModelViewer: React.FC<Props> = ({
  glbUrl, category, height = 200, poster,
  autoRotate = true, cameraControls = true, ar = false,
  background = 'linear-gradient(135deg,#e8eef5,#f5f7fa)',
  showWatermark = false, label,
}) => {
  const resolvedUrl = resolveGlbUrl(glbUrl, category);
  const isDemo = resolvedUrl !== glbUrl;

  return (
    <div style={{ position: 'relative', width: '100%', height, borderRadius: 6, overflow: 'hidden', background }}>
      <model-viewer
        src={resolvedUrl}
        alt="3D 模型预览"
        camera-controls={cameraControls ? '' : undefined}
        auto-rotate={autoRotate ? '' : undefined}
        rotation-per-second="20deg"
        shadow-intensity="1"
        exposure="1"
        loading="eager"
        ar={ar ? '' : undefined}
        ar-modes="webxr scene-viewer quick-look"
        style={{ width: '100%', height: '100%', background: 'transparent' }}
      />
      {label && (
        <span style={{
          position: 'absolute', bottom: 8, left: 8,
          background: 'rgba(0,21,41,.85)', color: '#fff',
          padding: '2px 8px', borderRadius: 3, fontSize: 10.5,
        }}>{label}</span>
      )}
      {(showWatermark || isDemo) && (
        <span style={{
          position: 'absolute', bottom: 8, right: 8,
          background: 'rgba(250,140,22,.85)', color: '#fff',
          padding: '2px 6px', borderRadius: 3, fontSize: 10,
        }}>
          {isDemo ? '🔶 Demo Model' : '3D Preview'}
        </span>
      )}
    </div>
  );
};

export default ModelViewer;
