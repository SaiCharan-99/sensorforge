from ultralytics import YOLOE
m = YOLOE("yoloe-11s-seg-pf.pt")
names = m.names
print(len(names), list(names.values())[:8])
open("pf.names","w",encoding="utf-8").write("\n".join(names[i] for i in range(len(names))))
print(m.export(format="onnx", imgsz=416, opset=17))
