"""FreeCAD: STEP → STL (供 worker.py 通过 freecad --console 调用)"""
import sys
import FreeCAD
import Part
import Mesh


def convert(stp_path: str, stl_path: str) -> None:
    """加载 STEP → tessellation → 导出 STL"""
    doc = FreeCAD.newDocument('cpq')
    shape = Part.Shape()
    shape.read(stp_path)
    Part.show(shape)
    obj = doc.Objects[0]

    # Tessellation 精度: angular 0.1 rad, linear 0.1 mm
    mesh = Mesh.Mesh()
    mesh.addFacets(obj.Shape.tessellate(0.1))
    mesh.write(stl_path)
    FreeCAD.closeDocument(doc.Name)
    print(f'OK STEP→STL: {stp_path} → {stl_path}')
