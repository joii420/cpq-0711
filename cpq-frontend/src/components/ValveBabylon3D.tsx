import React, { useEffect, useRef } from 'react';

/**
 * 阀门程序化 3D 模型（Babylon.js）— 真 3D 效果（旋转/光照/阴影）
 *
 * 11 个具名 mesh，可被 product_config_3d_rule.target_mesh 直接引用：
 *   mesh_body            — 阀体球
 *   mesh_flange_left     — 左法兰
 *   mesh_flange_right    — 右法兰
 *   mesh_thread_left     — 左螺纹（FLANGE 隐藏 / THREAD 显示）
 *   mesh_thread_right    — 右螺纹
 *   mesh_weld_left       — 左焊缝（WELD 显示）
 *   mesh_weld_right      — 右焊缝
 *   mesh_stem            — 阀杆
 *   mesh_handle          — 手柄（HANDLE 显示）
 *   mesh_pneumatic       — 气缸（PNEUMATIC 显示）
 *   mesh_electric        — 电控盒（ELECTRIC 显示）
 *   mesh_pipe            — 流向管道
 */

export const VALVE_MESHES = [
  { name: 'mesh_body',          desc: '阀体（中央球体）' },
  { name: 'mesh_stem',          desc: '阀杆' },
  { name: 'mesh_flange_left',   desc: '左法兰盘' },
  { name: 'mesh_flange_right',  desc: '右法兰盘' },
  { name: 'mesh_thread_left',   desc: '左螺纹接头' },
  { name: 'mesh_thread_right',  desc: '右螺纹接头' },
  { name: 'mesh_weld_left',     desc: '左焊缝' },
  { name: 'mesh_weld_right',    desc: '右焊缝' },
  { name: 'mesh_handle',        desc: '手动 T 形手柄' },
  { name: 'mesh_pneumatic',     desc: '气动执行器' },
  { name: 'mesh_electric',      desc: '电动执行器' },
  { name: 'mesh_pipe',          desc: '流向管道' },
];

const MATERIAL_COLORS: Record<string, string> = {
  'WCB':  '#5a6168', '304':  '#c8d3da', '316':  '#a8c5d6', '黄铜': '#d4a017',
};

const DN_SCALE: Record<string, number> = {
  '15': 0.55, '20': 0.65, '25': 0.75, '32': 0.85, '40': 0.95,
  '50': 1.05, '65': 1.15, '80': 1.25, '100': 1.4,
};

declare global { interface Window { BABYLON?: any; } }

let babylonLoading: Promise<void> | null = null;
function loadBabylon(): Promise<void> {
  if (window.BABYLON?.MeshBuilder) return Promise.resolve();
  if (babylonLoading) return babylonLoading;
  babylonLoading = new Promise((resolve, reject) => {
    const s = document.createElement('script');
    // 本地化 (内网无网部署), 资源放 public/vendor/, 构建后位于 dist/vendor/
    s.src = '/vendor/babylon.js';
    s.onload = () => resolve();
    s.onerror = () => reject(new Error('Babylon local load failed'));
    document.head.appendChild(s);
  });
  return babylonLoading;
}

interface Props {
  selectedValues: Record<string, any>;
  height?: number | string;
  showLabels?: boolean;
  background?: string;
}

const ValveBabylon3D: React.FC<Props> = ({
  selectedValues, height = 460, showLabels = true,
  background = 'linear-gradient(135deg,#e8eef5,#f5f7fa)',
}) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const sceneRef = useRef<any>(null);
  const meshesRef = useRef<Record<string, any>>({});

  // 初始化 Babylon scene
  useEffect(() => {
    let cancelled = false;
    let engine: any;
    (async () => {
      await loadBabylon();
      if (cancelled || !canvasRef.current) return;
      const B = window.BABYLON!;

      engine = new B.Engine(canvasRef.current, true, { preserveDrawingBuffer: true, stencil: true });
      const scene = new B.Scene(engine);
      scene.clearColor = new B.Color4(0, 0, 0, 0);

      // 相机
      const camera = new B.ArcRotateCamera('cam', Math.PI / 4, Math.PI / 3, 8,
        new B.Vector3(0, 0, 0), scene);
      camera.attachControl(canvasRef.current, true);
      camera.lowerRadiusLimit = 4;
      camera.upperRadiusLimit = 16;
      camera.useAutoRotationBehavior = true;
      camera.autoRotationBehavior.idleRotationSpeed = 0.3;

      // 灯光
      const hLight = new B.HemisphericLight('hLight', new B.Vector3(0, 1, 0), scene);
      hLight.intensity = 0.8;
      const dLight = new B.DirectionalLight('dLight', new B.Vector3(-0.5, -1, -0.3), scene);
      dLight.intensity = 0.6;

      // ===== 构造阀门 mesh =====
      const meshes: Record<string, any> = {};

      // 阀体（中央球体）
      const body = B.MeshBuilder.CreateSphere('mesh_body', { diameter: 2.2, segments: 32 }, scene);
      meshes.mesh_body = body;

      // 阀杆（垂直圆柱）
      const stem = B.MeshBuilder.CreateCylinder('mesh_stem',
        { height: 1.4, diameter: 0.25 }, scene);
      stem.position.y = 1.4;
      meshes.mesh_stem = stem;

      // 左右法兰
      const flangeLeft = B.MeshBuilder.CreateCylinder('mesh_flange_left',
        { height: 0.4, diameter: 1.8 }, scene);
      flangeLeft.rotation.z = Math.PI / 2;
      flangeLeft.position.x = -1.4;
      meshes.mesh_flange_left = flangeLeft;

      const flangeRight = flangeLeft.clone('mesh_flange_right');
      flangeRight.position.x = 1.4;
      meshes.mesh_flange_right = flangeRight;

      // 左右螺纹接头（默认隐藏）
      const threadLeft = B.MeshBuilder.CreateCylinder('mesh_thread_left',
        { height: 0.8, diameter: 0.8 }, scene);
      threadLeft.rotation.z = Math.PI / 2;
      threadLeft.position.x = -1.7;
      threadLeft.setEnabled(false);
      meshes.mesh_thread_left = threadLeft;

      const threadRight = threadLeft.clone('mesh_thread_right');
      threadRight.position.x = 1.7;
      threadRight.setEnabled(false);
      meshes.mesh_thread_right = threadRight;

      // 左右焊缝（默认隐藏）— 用细环表示
      const weldLeft = B.MeshBuilder.CreateTorus('mesh_weld_left',
        { diameter: 1.4, thickness: 0.15 }, scene);
      weldLeft.rotation.z = Math.PI / 2;
      weldLeft.position.x = -1.2;
      weldLeft.setEnabled(false);
      meshes.mesh_weld_left = weldLeft;

      const weldRight = weldLeft.clone('mesh_weld_right');
      weldRight.position.x = 1.2;
      weldRight.setEnabled(false);
      meshes.mesh_weld_right = weldRight;

      // 手柄（默认显）
      const handleArm = B.MeshBuilder.CreateBox('mesh_handle',
        { width: 2.2, height: 0.18, depth: 0.18 }, scene);
      handleArm.position.y = 2.25;
      meshes.mesh_handle = handleArm;

      // 气动气缸（默认隐藏）
      const pneumatic = B.MeshBuilder.CreateCylinder('mesh_pneumatic',
        { height: 1.6, diameter: 1.1, tessellation: 24 }, scene);
      pneumatic.position.y = 2.5;
      pneumatic.setEnabled(false);
      meshes.mesh_pneumatic = pneumatic;

      // 电控盒（默认隐藏）
      const electric = B.MeshBuilder.CreateBox('mesh_electric',
        { width: 1.4, height: 1.2, depth: 1.0 }, scene);
      electric.position.y = 2.5;
      electric.setEnabled(false);
      meshes.mesh_electric = electric;

      // 流向管道（细圆柱穿过）
      const pipe = B.MeshBuilder.CreateCylinder('mesh_pipe',
        { height: 6, diameter: 0.4 }, scene);
      pipe.rotation.z = Math.PI / 2;
      meshes.mesh_pipe = pipe;

      // 材质（默认 PBR 金属）
      const matBody = new B.PBRMaterial('matBody', scene);
      matBody.albedoColor = B.Color3.FromHexString('#5a6168');
      matBody.metallic = 0.8;
      matBody.roughness = 0.3;
      body.material = matBody;
      stem.material = matBody;
      flangeLeft.material = matBody;
      flangeRight.material = matBody;
      threadLeft.material = matBody;
      threadRight.material = matBody;
      pipe.material = matBody;

      // 手柄红色
      const matHandle = new B.PBRMaterial('matHandle', scene);
      matHandle.albedoColor = B.Color3.FromHexString('#c8102e');
      matHandle.metallic = 0.2;
      matHandle.roughness = 0.4;
      handleArm.material = matHandle;

      // 气缸蓝色
      const matPneu = new B.PBRMaterial('matPneu', scene);
      matPneu.albedoColor = B.Color3.FromHexString('#4a90a4');
      matPneu.metallic = 0.5;
      matPneu.roughness = 0.4;
      pneumatic.material = matPneu;

      // 电控盒黑色
      const matEle = new B.PBRMaterial('matEle', scene);
      matEle.albedoColor = B.Color3.FromHexString('#2c3e50');
      matEle.metallic = 0.6;
      matEle.roughness = 0.5;
      electric.material = matEle;

      // 焊缝橙色
      const matWeld = new B.PBRMaterial('matWeld', scene);
      matWeld.albedoColor = B.Color3.FromHexString('#ff9500');
      matWeld.metallic = 0.3;
      matWeld.roughness = 0.7;
      weldLeft.material = matWeld;
      weldRight.material = matWeld;

      meshesRef.current = meshes;
      sceneRef.current = scene;

      engine.runRenderLoop(() => scene.render());
      const onResize = () => engine.resize();
      window.addEventListener('resize', onResize);
    })();
    return () => {
      cancelled = true;
      if (engine) engine.dispose();
    };
  }, []);

  // 应用 selectedValues 联动
  useEffect(() => {
    if (!sceneRef.current || !meshesRef.current.mesh_body) return;
    const B = window.BABYLON!;
    const meshes = meshesRef.current;
    const sv = selectedValues;

    const dn = String(sv['DN'] ?? '25');
    const mat = String(sv['MATERIAL'] ?? 'WCB');
    const conn = String(sv['CONNECTION'] ?? 'FLANGE');
    const drv = String(sv['DRIVE'] ?? 'HANDLE');

    // DN 缩放整个根
    const scale = DN_SCALE[dn] || 1;
    [meshes.mesh_body, meshes.mesh_stem, meshes.mesh_flange_left, meshes.mesh_flange_right,
     meshes.mesh_thread_left, meshes.mesh_thread_right, meshes.mesh_weld_left, meshes.mesh_weld_right,
     meshes.mesh_handle, meshes.mesh_pneumatic, meshes.mesh_electric, meshes.mesh_pipe,
    ].forEach((m: any) => { if (m) m.scaling = new B.Vector3(scale, scale, scale); });

    // 材质颜色（替换阀体 + 法兰 + 阀杆 + 管道）
    const color = B.Color3.FromHexString(MATERIAL_COLORS[mat] || MATERIAL_COLORS['WCB']);
    ['mesh_body', 'mesh_stem', 'mesh_flange_left', 'mesh_flange_right',
     'mesh_thread_left', 'mesh_thread_right', 'mesh_pipe'].forEach(n => {
      const m = meshes[n];
      if (m?.material) {
        m.material.albedoColor = color;
        // 黄铜不锈钢做差异
        if (mat === '黄铜') m.material.metallic = 0.4;
        else if (mat.includes('316')) { m.material.metallic = 0.9; m.material.roughness = 0.15; }
        else if (mat.includes('304')) { m.material.metallic = 0.85; m.material.roughness = 0.2; }
      }
    });

    // 连接方式：法兰 / 螺纹 / 焊接 三选一
    meshes.mesh_flange_left?.setEnabled(conn === 'FLANGE');
    meshes.mesh_flange_right?.setEnabled(conn === 'FLANGE');
    meshes.mesh_thread_left?.setEnabled(conn === 'THREAD');
    meshes.mesh_thread_right?.setEnabled(conn === 'THREAD');
    meshes.mesh_weld_left?.setEnabled(conn === 'WELD');
    meshes.mesh_weld_right?.setEnabled(conn === 'WELD');

    // 驱动方式：手柄 / 气动 / 电动 三选一
    meshes.mesh_handle?.setEnabled(drv === 'HANDLE');
    meshes.mesh_pneumatic?.setEnabled(drv === 'PNEUMATIC');
    meshes.mesh_electric?.setEnabled(drv === 'ELECTRIC');
  }, [selectedValues]);

  return (
    <div style={{ position: 'relative', width: '100%', height, background, borderRadius: 6, overflow: 'hidden' }}>
      <canvas ref={canvasRef} style={{ width: '100%', height: '100%', outline: 'none' }} />
      {showLabels && (
        <>
          <div style={{ position: 'absolute', top: 8, left: 8, fontSize: 11, color: '#0050b3',
                        background: 'rgba(255,255,255,.9)', padding: '3px 8px', borderRadius: 3, fontWeight: 600 }}>
            🔧 DN{selectedValues['DN'] ?? '25'} · PN{selectedValues['PN'] ?? '16'} · {MATERIAL_COLORS[String(selectedValues['MATERIAL'] ?? 'WCB')] ? selectedValues['MATERIAL'] : 'WCB'}
          </div>
          <div style={{ position: 'absolute', bottom: 8, left: 8, fontSize: 11, color: '#666',
                        background: 'rgba(255,255,255,.85)', padding: '3px 8px', borderRadius: 3 }}>
            ⚙️ 拖拽旋转 · 滚轮缩放 · 11 mesh 真 3D
          </div>
          {Number(selectedValues['TEMP_RANGE'] ?? 80) > 150 && (
            <div style={{ position: 'absolute', bottom: 8, right: 8, fontSize: 11, padding: '3px 8px',
                          borderRadius: 3, color: '#cf1322', background: '#fff1f0', border: '1px solid #ffa39e' }}>
              🌡 高温 {selectedValues['TEMP_RANGE']}℃
            </div>
          )}
        </>
      )}
    </div>
  );
};

export default ValveBabylon3D;
