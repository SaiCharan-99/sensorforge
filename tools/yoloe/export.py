from ultralytics import YOLOE
m = YOLOE("yoloe-11s-seg.pt")
names = ["adapter","box","cable","charger","envelope","impostor_cable","papers","phone","seal"]
m.set_classes(names, m.get_text_pe(names))
print(m.export(format="onnx", opset=17, imgsz=320))
