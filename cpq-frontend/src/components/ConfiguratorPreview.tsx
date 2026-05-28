import React from 'react';
import ModelViewer from './ModelViewer';
import ValveSchematic from './ValveSchematic';
import ValveBabylon3D from './ValveBabylon3D';

interface Props {
  category?: string;
  partNo?: string;      // 用 partNo 推断 category（3D 源文件列表场景）
  glbUrl?: string;
  selectedValues?: Record<string, any>;
  height?: number | string;
  label?: string;
  autoRotate?: boolean;
  cameraControls?: boolean;
  showLabels?: boolean;
  mode?: '2d' | '3d';   // 阀门预览模式：2d=SVG / 3d=Babylon（默认 3d）
}

/**
 * 按 partNo 推断 category（3D 源文件列表场景没有显式 category，但 partNo 有线索）
 */
export function inferCategory(partNo?: string, category?: string): string | undefined {
  if (category) return category;
  if (!partNo) return undefined;
  const p = partNo.toUpperCase();
  if (p.includes('VALVE') || p.includes('BALL-')) return '阀门';
  if (p.includes('CONTACT-STRIP') || p.includes('CTC-')) return '接触片';
  if (p.includes('SPRING')) return '接触簧片';
  if (p.includes('TERM-')) return '端子';
  if (p.includes('MOTOR')) return '电机';
  return undefined;
}

/** 阀门默认配置 — 用于无 selectedValues 时显示默认形态 */
const VALVE_DEFAULTS: Record<string, any> = {
  DN: '50', PN: '25', MATERIAL: 'WCB', CONNECTION: 'FLANGE', DRIVE: 'HANDLE', TEMP_RANGE: '80',
};

/**
 * 智能选配预览路由器
 *
 * - 阀门品类 + 有 selectedValues → 用 ValveSchematic（真正联动 SVG）
 * - 其他品类 / 无 selectedValues → 用 ModelViewer（GLB + 自动旋转）
 *
 * 未来加：接触片 / 端子 / 电机 等品类各自的联动 SVG
 */
const ConfiguratorPreview: React.FC<Props> = ({
  category, partNo, glbUrl, selectedValues, height, label,
  autoRotate, cameraControls, showLabels, mode = '3d',
}) => {
  const cat = inferCategory(partNo, category);
  // 阀门：默认 Babylon 3D（真 mesh 联动），可选 2d=SVG
  if (cat === '阀门') {
    const values = selectedValues && Object.keys(selectedValues).length > 0
      ? selectedValues : VALVE_DEFAULTS;
    if (mode === '2d') {
      return <ValveSchematic selectedValues={values} height={height} showLabels={showLabels} />;
    }
    return <ValveBabylon3D selectedValues={values} height={height} showLabels={showLabels} />;
  }
  // 其他用 model-viewer GLB（兜底）
  return (
    <ModelViewer
      glbUrl={glbUrl} category={cat} height={height} label={label}
      autoRotate={autoRotate} cameraControls={cameraControls}
    />
  );
};

export default ConfiguratorPreview;
