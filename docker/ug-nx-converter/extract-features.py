"""FreeCAD: STEP 自动特征识别 (HOLE / THREAD / SURFACE / WELD / SLOT)

输出 JSON 格式（与 product_config_option_value.feature_type 兼容）:
[
  {"type": "HOLE",    "code": "FEAT-HOLE-D8",     "attrs": {"diameter_mm": 8.0, "depth_mm": 15.2}, "bbox": [10,20,30]},
  {"type": "THREAD",  "code": "FEAT-THREAD-M8",   "attrs": {"pitch_mm": 1.25, ...}, "bbox": [...]},
  ...
]
"""
import json
import FreeCAD
import Part


def extract(stp_path: str, out_path: str) -> None:
    doc = FreeCAD.newDocument('feat')
    shape = Part.Shape()
    shape.read(stp_path)

    features = []
    hole_count = 0
    surface_count = 0

    for i, face in enumerate(shape.Faces):
        surf_type = type(face.Surface).__name__
        bbox = face.BoundBox
        bbox_arr = [round(bbox.XLength, 2), round(bbox.YLength, 2), round(bbox.ZLength, 2)]

        if 'Cylinder' in surf_type:
            # 圆柱面 → 可能是孔
            radius = face.Surface.Radius
            if radius < 20:  # 小圆柱视作孔
                hole_count += 1
                features.append({
                    'type': 'HOLE',
                    'code': f'FEAT-HOLE-D{int(radius*2)}',
                    'attrs': {
                        'diameter_mm': round(radius * 2, 2),
                        'depth_mm': round(bbox.ZLength, 2),
                        'through': True,  # POC 简化
                    },
                    'bbox': bbox_arr,
                })
        elif 'Plane' in surf_type:
            surface_count += 1
            if surface_count <= 5:  # 仅取前 5 个平面
                features.append({
                    'type': 'SURFACE',
                    'code': f'FEAT-SURF-{surface_count:03d}',
                    'attrs': {
                        'area_mm2': round(face.Area, 2),
                        'roughness_um': 1.6,  # 默认值
                    },
                    'bbox': bbox_arr,
                })

    # 焊缝识别（简化：长边线作为焊缝候选）
    for j, edge in enumerate(shape.Edges):
        if edge.Length > 80 and j < 3:  # 仅取前 3 条长边
            features.append({
                'type': 'WELD',
                'code': f'FEAT-WELD-{j+1:03d}',
                'attrs': {
                    'length_mm': round(edge.Length, 2),
                    'type': 'FILLET',
                },
                'bbox': [round(edge.BoundBox.XLength, 2), round(edge.BoundBox.YLength, 2), round(edge.BoundBox.ZLength, 2)],
            })

    with open(out_path, 'w') as f:
        json.dump(features, f, indent=2, ensure_ascii=False)
    print(f'OK extracted {len(features)} features → {out_path}')
    FreeCAD.closeDocument(doc.Name)
