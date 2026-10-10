from ultralytics import YOLOE
names = """box;cardboard box;package;envelope;paper;sheets of paper;notebook;book;pen;pencil;marker;scissors;tape;stapler;ruler;sticky note;folder;
phone;smartphone;tablet;laptop;keyboard;mouse;monitor;remote control;headphones;earbuds;earphones;speaker;camera;watch;power bank;battery;
charger;phone charger;power adapter;plug;power strip;usb cable;charging cable;cable;wire;usb drive;sd card;sim card;sim tray;
bottle;water bottle;cup;mug;glass;can;jar;lid;bowl;plate;spoon;fork;knife;food;fruit;apple;banana;orange;snack;packet;pill bottle;medicine;tablet strip;syringe;
bag;backpack;wallet;key;keys;glasses;sunglasses;hat;shoe;sock;shirt;towel;toy;ball;card;credit card;id card;coin;banknote;
tool;screwdriver;hammer;wrench;pliers;battery pack;bulb;lamp;clock;candle;plant;flower;vase;comb;brush;razor;soap;toothbrush;toothpaste;tube;spray bottle;
sticker;label;seal;tag;barcode;qr code;bag clip;rubber band;string;paper clip;hand;person;chair;table;box lid;tray;container;basket;bin""".replace("\n","").split(";")
names=[n.strip() for n in names if n.strip()]
names=list(dict.fromkeys(names))
print(len(names))
open("common.names","w").write("\n".join(names))
m = YOLOE("yoloe-11s-seg.pt")
m.set_classes(names, m.get_text_pe(names))
print(m.export(format="onnx", imgsz=416, opset=17))
