"""Blender headless: STL → GLB (含 Draco 压缩)

调用方式: blender --background --python stl-to-glb.py -- input.stl output.glb
详见: docs/CAD转换POC-技术验证.md §6.5
"""
import bpy
import sys


def main():
    # 解析 -- 之后的参数
    argv = sys.argv
    if '--' in argv:
        argv = argv[argv.index('--') + 1:]
    stl_path = argv[0]
    glb_path = argv[1]

    # 清空场景
    bpy.ops.wm.read_factory_settings(use_empty=True)
    # 导入 STL
    bpy.ops.import_mesh.stl(filepath=stl_path)

    # 适度简化 mesh（decimate to 50%）— 减小 GLB 体积
    obj = bpy.context.selected_objects[0]
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.modifier_add(type='DECIMATE')
    obj.modifiers['Decimate'].ratio = 0.5
    bpy.ops.object.modifier_apply(modifier='Decimate')

    # 添加默认材质（PBR）
    mat = bpy.data.materials.new(name='cpq-default')
    mat.use_nodes = True
    mat.node_tree.nodes['Principled BSDF'].inputs['Base Color'].default_value = (0.7, 0.7, 0.7, 1.0)
    mat.node_tree.nodes['Principled BSDF'].inputs['Metallic'].default_value = 0.6
    mat.node_tree.nodes['Principled BSDF'].inputs['Roughness'].default_value = 0.3
    obj.data.materials.append(mat)

    # 导出 GLB + Draco 压缩
    bpy.ops.export_scene.gltf(
        filepath=glb_path,
        export_format='GLB',
        export_draco_mesh_compression_enable=True,
        export_draco_mesh_compression_level=6,
        export_draco_position_quantization=14,
        export_draco_normal_quantization=10,
        export_draco_texcoord_quantization=12,
    )
    print(f'OK STL→GLB: {stl_path} → {glb_path}')


if __name__ == '__main__':
    main()
