from ultralytics import YOLOE
m = YOLOE("yoloe-11s-seg.pt")
# class id (what the specs use) -> text prompt (what YOLOE is asked to find). Order = output channel order.
vocab = [("adapter","power adapter plug"),("box","cardboard box"),("cable","usb charging cable"),("charger","phone charger"),
         ("envelope","paper envelope"),("papers","sheets of paper"),("phone","smartphone"),("seal","sticker seal")]
prompts = [p for _, p in vocab]
m.set_classes(prompts, m.get_text_pe(prompts))
print([c for c,_ in vocab])
print(m.export(format="onnx", imgsz=416, opset=17))
