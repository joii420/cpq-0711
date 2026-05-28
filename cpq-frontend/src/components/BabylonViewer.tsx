import React, { useEffect, useRef } from 'react';

/**
 * Babylon.js 真 3D Viewer（可选升级 — 支持运行时 mesh 显隐 / 材质替换）
 *
 * 当前实现：动态加载 Babylon CDN，挂载到 canvas，提供 setMeshVisibility / replaceMaterial 接口。
 * 用途：将来选配联动需要真实 mesh 操作时启用（model-viewer 不支持运行时 mesh ops）。
 *
 * 用法：
 *   <BabylonViewer glbUrl="..." height={460}
 *                  meshOps={[{ meshName: 'mesh_body_304', visible: true, baseColor: '#c8d3da' }]} />
 */
declare global {
  interface Window {
    BABYLON?: any;
  }
}

interface MeshOp {
  meshName: string;
  visible?: boolean;
  baseColor?: string;
  metallic?: number;
  roughness?: number;
}

interface Props {
  glbUrl: string;
  height?: number | string;
  meshOps?: MeshOp[];          // 运行时 mesh 操作
  autoRotate?: boolean;
  onLoaded?: (meshNames: string[]) => void;
}

// 本地化 (内网无网部署), 资源放 public/vendor/, 构建后位于 dist/vendor/
const BABYLON_CDN = '/vendor/babylon.js';
const LOADERS_CDN = '/vendor/babylonjs.loaders.min.js';

let babylonLoading: Promise<void> | null = null;
function loadBabylon(): Promise<void> {
  if (window.BABYLON?.SceneLoader) return Promise.resolve();
  if (babylonLoading) return babylonLoading;
  babylonLoading = new Promise((resolve, reject) => {
    const s1 = document.createElement('script');
    s1.src = BABYLON_CDN;
    s1.onload = () => {
      const s2 = document.createElement('script');
      s2.src = LOADERS_CDN;
      s2.onload = () => resolve();
      s2.onerror = () => reject(new Error('babylon loaders failed'));
      document.head.appendChild(s2);
    };
    s1.onerror = () => reject(new Error('babylon core failed'));
    document.head.appendChild(s1);
  });
  return babylonLoading;
}

const BabylonViewer: React.FC<Props> = ({ glbUrl, height = 460, meshOps, autoRotate, onLoaded }) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const engineRef = useRef<any>(null);
  const sceneRef = useRef<any>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      await loadBabylon();
      if (cancelled || !canvasRef.current) return;
      const B = window.BABYLON!;
      const engine = new B.Engine(canvasRef.current, true, { preserveDrawingBuffer: true, stencil: true });
      const scene = new B.Scene(engine);
      scene.clearColor = new B.Color4(0.95, 0.96, 0.98, 1);
      // 相机
      const camera = new B.ArcRotateCamera('cam', Math.PI / 4, Math.PI / 3, 5, B.Vector3.Zero(), scene);
      camera.attachControl(canvasRef.current, true);
      if (autoRotate) {
        camera.useAutoRotationBehavior = true;
      }
      // 灯光
      new B.HemisphericLight('hLight', new B.Vector3(0, 1, 0), scene);
      const dLight = new B.DirectionalLight('dLight', new B.Vector3(-0.5, -1, -0.5), scene);
      dLight.intensity = 0.8;

      // 加载 GLB（fallback to demo if 假 URL）
      const url = glbUrl?.startsWith('cpq-3d-glb://') || glbUrl?.includes('mock/')
        ? '/vendor/models/Astronaut.glb'
        : glbUrl;
      try {
        const result = await B.SceneLoader.ImportMeshAsync('', '', url, scene);
        const names = result.meshes.map((m: any) => m.name);
        if (onLoaded) onLoaded(names);
        // 自适应 zoom
        scene.createDefaultCameraOrLight(false, true, true);
      } catch (e) {
        console.error('Babylon load error:', e);
      }

      engineRef.current = engine;
      sceneRef.current = scene;
      engine.runRenderLoop(() => scene.render());
      const onResize = () => engine.resize();
      window.addEventListener('resize', onResize);
      return () => { window.removeEventListener('resize', onResize); };
    })();
    return () => {
      cancelled = true;
      if (engineRef.current) { engineRef.current.dispose(); engineRef.current = null; }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [glbUrl]);

  // 应用 mesh 操作
  useEffect(() => {
    if (!sceneRef.current || !meshOps) return;
    const scene = sceneRef.current;
    for (const op of meshOps) {
      const mesh = scene.getMeshByName(op.meshName);
      if (!mesh) continue;
      if (op.visible !== undefined) mesh.setEnabled(op.visible);
      if (op.baseColor && mesh.material) {
        const B = window.BABYLON;
        const c = B.Color3.FromHexString(op.baseColor);
        if (mesh.material.albedoColor) mesh.material.albedoColor = c;
        else if (mesh.material.diffuseColor) mesh.material.diffuseColor = c;
      }
    }
  }, [meshOps]);

  return (
    <canvas ref={canvasRef}
            style={{ width: '100%', height, borderRadius: 6, background: 'linear-gradient(135deg,#e8eef5,#f5f7fa)' }} />
  );
};

export default BabylonViewer;
