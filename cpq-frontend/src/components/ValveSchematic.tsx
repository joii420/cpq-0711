import React from 'react';

/**
 * 阀门选配联动 SVG 示意图（v0.4 §3.5 3D 规则可视化）
 *
 * 根据 selectedValues 动态变化：
 *   - DN: 整体缩放 + 底部尺寸标签
 *   - PN: 顶部压力标签 + 配色加深
 *   - MATERIAL: 阀体颜色（WCB 深灰 / 304 银亮 / 316 银+蓝调 / 黄铜 金）
 *   - CONNECTION: 两侧连接器形状（FLANGE 圆盘 / THREAD 螺纹圆柱 / WELD 斜切）
 *   - DRIVE: 顶部驱动器（HANDLE T 形 / PNEUMATIC 气缸 / ELECTRIC 方盒）
 *   - TEMP_RANGE: 阀体温度标签 + > 150℃ 时红色温度图标
 */

interface Props {
  selectedValues: Record<string, any>;
  height?: number | string;
  showLabels?: boolean;
  background?: string;
}

// 材质 → 颜色
const MATERIAL_COLORS: Record<string, { fill: string; stroke: string; label: string }> = {
  'WCB':   { fill: '#5a6168', stroke: '#3a4148', label: '铸钢 WCB' },
  '304':   { fill: '#c8d3da', stroke: '#7f8b91', label: '不锈钢 304' },
  '316':   { fill: '#a8c5d6', stroke: '#5a7a8e', label: '不锈钢 316' },
  '黄铜':  { fill: '#d4a017', stroke: '#8b6a0c', label: '黄铜' },
};

// DN → 缩放比例（DN15=0.6 → DN100=1.4）
const DN_SCALE: Record<string, number> = {
  '15': 0.55, '20': 0.65, '25': 0.75, '32': 0.85, '40': 0.95,
  '50': 1.05, '65': 1.15, '80': 1.25, '100': 1.4,
};

const ValveSchematic: React.FC<Props> = ({
  selectedValues, height = 460, showLabels = true,
  background = 'linear-gradient(135deg,#e8eef5,#f5f7fa)',
}) => {
  const dn = String(selectedValues['DN'] ?? '25');
  const pn = String(selectedValues['PN'] ?? '16');
  const mat = String(selectedValues['MATERIAL'] ?? 'WCB');
  const conn = String(selectedValues['CONNECTION'] ?? 'FLANGE');
  const drv = String(selectedValues['DRIVE'] ?? 'HANDLE');
  const temp = Number(selectedValues['TEMP_RANGE'] ?? 80);

  const matColor = MATERIAL_COLORS[mat] || MATERIAL_COLORS['WCB'];
  const scale = DN_SCALE[dn] || 1;
  const highTemp = temp > 150;

  // 连接器
  const ConnectorLeft = () => {
    if (conn === 'FLANGE') {
      return (
        <g>
          <rect x="20" y="115" width="40" height="70" fill={matColor.fill} stroke={matColor.stroke} strokeWidth="1.5" />
          <ellipse cx="40" cy="150" rx="6" ry="35" fill="none" stroke={matColor.stroke} strokeWidth="1" />
          {[-25, -15, 15, 25].map((dy, i) => (
            <circle key={i} cx="40" cy={150 + dy} r="3" fill="#333" />
          ))}
        </g>
      );
    }
    if (conn === 'THREAD') {
      return (
        <g>
          <rect x="30" y="135" width="30" height="30" fill={matColor.fill} stroke={matColor.stroke} strokeWidth="1.5" />
          {[0, 1, 2, 3, 4, 5].map(i => (
            <line key={i} x1="30" y1={138 + i * 5} x2="60" y2={138 + i * 5} stroke={matColor.stroke} strokeWidth="0.8" />
          ))}
        </g>
      );
    }
    // WELD
    return (
      <g>
        <polygon points="20,135 60,140 60,160 20,165" fill={matColor.fill} stroke={matColor.stroke} strokeWidth="1.5" />
        <path d="M 25 142 L 32 148 L 38 142 L 45 148 L 52 142" stroke="#ff9500" strokeWidth="1.5" fill="none" />
      </g>
    );
  };
  const ConnectorRight = () => (
    <g transform="translate(280,0) scale(-1,1) translate(-80,0)">
      <ConnectorLeft />
    </g>
  );

  // 驱动器
  const Drive = () => {
    if (drv === 'HANDLE') {
      return (
        <g>
          <rect x="135" y="50" width="30" height="60" rx="3" fill={matColor.fill} stroke={matColor.stroke} strokeWidth="1.5" />
          {/* T 形手柄 */}
          <rect x="100" y="35" width="100" height="12" rx="6" fill="#c8102e" stroke="#7a0a1f" strokeWidth="1.2" />
          <circle cx="150" cy="41" r="4" fill="#fff" />
        </g>
      );
    }
    if (drv === 'PNEUMATIC') {
      return (
        <g>
          <rect x="115" y="20" width="70" height="90" rx="6" fill="#4a90a4" stroke="#2c5d70" strokeWidth="1.5" />
          <ellipse cx="150" cy="22" rx="35" ry="6" fill="#5fadb8" />
          <text x="150" y="65" textAnchor="middle" fill="#fff" fontSize="11" fontWeight="bold">气缸</text>
          {/* 进气管 */}
          <rect x="180" y="40" width="20" height="6" fill="#888" />
          <circle cx="200" cy="43" r="3" fill="#fff" stroke="#888" strokeWidth="1" />
        </g>
      );
    }
    // ELECTRIC
    return (
      <g>
        <rect x="110" y="20" width="80" height="80" rx="4" fill="#2c3e50" stroke="#1a2530" strokeWidth="1.5" />
        <rect x="118" y="28" width="64" height="40" rx="2" fill="#16a085" />
        <text x="150" y="54" textAnchor="middle" fill="#fff" fontSize="12" fontWeight="bold">M</text>
        {/* LED */}
        <circle cx="125" cy="82" r="3" fill="#52c41a">
          <animate attributeName="opacity" values="1;0.3;1" dur="1.5s" repeatCount="indefinite" />
        </circle>
        <circle cx="135" cy="82" r="3" fill="#fa8c16" />
        <text x="170" y="86" fill="#fff" fontSize="9">24V DC</text>
      </g>
    );
  };

  return (
    <div style={{ width: '100%', height, background, borderRadius: 6, position: 'relative', overflow: 'hidden' }}>
      <svg viewBox="0 0 300 260" style={{ width: '100%', height: '100%' }}>
        <g transform={`translate(150,130) scale(${scale}) translate(-150,-130)`}>
          {/* 阀体本体 */}
          <circle cx="150" cy="150" r="50" fill={matColor.fill} stroke={matColor.stroke} strokeWidth="2" />
          <circle cx="150" cy="150" r="50" fill="url(#metalShine)" opacity="0.3" />
          {/* 阀座 */}
          <rect x="120" y="148" width="60" height="4" fill={matColor.stroke} />

          {/* 左右连接器 */}
          <ConnectorLeft />
          <ConnectorRight />

          {/* 阀杆 */}
          <rect x="145" y="100" width="10" height="50" fill={matColor.stroke} />

          {/* 顶部驱动器 */}
          <Drive />

          {/* 高温警告 */}
          {highTemp && (
            <g transform="translate(85,170)">
              <circle r="11" fill="#fff1f0" stroke="#cf1322" strokeWidth="1.5" />
              <text textAnchor="middle" y="4" fontSize="12" fill="#cf1322">🌡</text>
            </g>
          )}

          {/* 介质标签 */}
          <text x="150" y="225" textAnchor="middle" fontSize="9" fill="#666">⇨ 介质流向 ⇨</text>
        </g>

        {/* 渐变定义 */}
        <defs>
          <linearGradient id="metalShine" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#fff" stopOpacity="0.6" />
            <stop offset="50%" stopColor="#fff" stopOpacity="0.1" />
            <stop offset="100%" stopColor="#000" stopOpacity="0.2" />
          </linearGradient>
        </defs>
      </svg>

      {/* 标签层 */}
      {showLabels && (
        <>
          <div style={{ position: 'absolute', top: 8, left: 8, fontSize: 11, color: '#0050b3',
                        background: 'rgba(255,255,255,.85)', padding: '3px 8px', borderRadius: 3, fontWeight: 600 }}>
            🔧 DN{dn} · PN{pn}
          </div>
          <div style={{ position: 'absolute', top: 8, right: 8, fontSize: 11,
                        background: matColor.stroke, color: '#fff',
                        padding: '3px 8px', borderRadius: 3, fontWeight: 600 }}>
            {matColor.label}
          </div>
          <div style={{ position: 'absolute', bottom: 8, left: 8, fontSize: 11, color: '#666',
                        background: 'rgba(255,255,255,.85)', padding: '3px 8px', borderRadius: 3 }}>
            {conn === 'FLANGE' ? '法兰连接' : conn === 'THREAD' ? '螺纹连接' : '焊接连接'} ·
            {' '}{drv === 'HANDLE' ? '手动' : drv === 'PNEUMATIC' ? '气动' : '电动'}
          </div>
          <div style={{ position: 'absolute', bottom: 8, right: 8, fontSize: 11,
                        background: highTemp ? '#fff1f0' : 'rgba(255,255,255,.85)',
                        color: highTemp ? '#cf1322' : '#666',
                        padding: '3px 8px', borderRadius: 3,
                        border: highTemp ? '1px solid #ffa39e' : 'none' }}>
            🌡 {temp}℃ {highTemp && '⚠'}
          </div>
        </>
      )}
    </div>
  );
};

export default ValveSchematic;
