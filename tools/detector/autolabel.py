"""Zero-shot pre-labelling of dataset/images with OWLv2 (google/owlv2-base-patch16-ensemble, Apache-2.0).

Writes dataset/autolabel/result.json (COCO) + images/, the same layout train_unboxing.py expects,
and dataset/autolabel/review.html to eyeball every box. These are DRAFT labels: review and fix
them before training; never report them as ground truth.

    tools/detector/.venv/Scripts/python tools/detector/autolabel.py
"""
import html, json, os, shutil, sys, time

import torch
from PIL import Image
from transformers import Owlv2ForObjectDetection, Owlv2Processor

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "dataset", "images")
OUT = os.path.join(ROOT, "dataset", "autolabel")
MODEL = "google/owlv2-base-patch16-ensemble"

# class → (text queries, score threshold). Thresholds are first guesses; tune after review.
CLASSES = {
    "box": (["a red cardboard phone box", "an open red box tray"], 0.25),
    "lid": (["a red box lid", "the lid of a phone box"], 0.25),
    "seal": (["a transparent seal sticker on a box", "a tamper seal label"], 0.20),
    "envelope": (["a paper envelope", "a small instruction booklet"], 0.22),
    "phone": (["a smartphone", "a phone wrapped in protective film"], 0.25),
    "cable": (["a coiled charging cable", "a usb cable"], 0.20),
    "charger": (["a white phone charger adapter", "a wall power adapter plug"], 0.25),
}


def nms(boxes, scores, iou=0.5):
    keep = []
    order = scores.argsort(descending=True)
    for i in order.tolist():
        b = boxes[i]
        ok = True
        for k in keep:
            c = boxes[k]
            iw = max(0.0, min(b[2], c[2]) - max(b[0], c[0])); ih = max(0.0, min(b[3], c[3]) - max(b[1], c[1]))
            inter = iw * ih
            union = (b[2] - b[0]) * (b[3] - b[1]) + (c[2] - c[0]) * (c[3] - c[1]) - inter
            if union > 0 and inter / union > iou:
                ok = False; break
        if ok: keep.append(i)
    return keep


def main():
    torch.set_num_threads(os.cpu_count() or 4)
    proc = Owlv2Processor.from_pretrained(MODEL)
    model = Owlv2ForObjectDetection.from_pretrained(MODEL).eval()
    names = list(CLASSES)
    queries = [q for n in names for q in CLASSES[n][0]]
    q_cls = [n for n in names for _ in CLASSES[n][0]]

    os.makedirs(os.path.join(OUT, "images"), exist_ok=True)
    files = sorted(f for f in os.listdir(SRC) if f.endswith(".jpg"))
    coco = {"images": [], "annotations": [], "categories": [{"id": i, "name": n} for i, n in enumerate(names)]}
    rows = []
    t0 = time.time()
    for idx, f in enumerate(files):
        im = Image.open(os.path.join(SRC, f)).convert("RGB")
        w, h = im.size
        shutil.copy(os.path.join(SRC, f), os.path.join(OUT, "images", f))
        coco["images"].append({"id": idx, "file_name": f"images/{f}", "width": w, "height": h})
        with torch.no_grad():
            inputs = proc(text=[queries], images=im, return_tensors="pt")
            outputs = model(**inputs)
        # OWLv2 pads to a square; scale boxes back with the padded side.
        side = max(w, h)
        res = proc.post_process_object_detection(outputs, threshold=0.1, target_sizes=torch.tensor([[side, side]]))[0]
        found = []
        for n in names:
            sel = [i for i, l in enumerate(res["labels"].tolist()) if q_cls[l] == n and res["scores"][i] >= CLASSES[n][1]]
            if not sel: continue
            bx = res["boxes"][sel].tolist(); sc = res["scores"][sel]
            for k in nms(bx, sc):
                x0, y0, x1, y1 = [max(0.0, v) for v in bx[k]]
                x1 = min(x1, w); y1 = min(y1, h)
                if x1 - x0 < 8 or y1 - y0 < 8: continue
                coco["annotations"].append({"id": len(coco["annotations"]), "image_id": idx, "category_id": names.index(n),
                                            "bbox": [x0, y0, x1 - x0, y1 - y0], "area": (x1 - x0) * (y1 - y0), "iscrowd": 0,
                                            "score": float(sc[k])})
                found.append((n, float(sc[k]), x0, y0, x1, y1))
        rows.append((f, w, h, found))
        if idx % 10 == 0:
            print(f"{idx + 1}/{len(files)} {f}: {[(n, round(s, 2)) for n, s, *_ in found]} ({time.time() - t0:.0f}s)", flush=True)

    json.dump(coco, open(os.path.join(OUT, "result.json"), "w"))
    colors = {"box": "#e5484d", "lid": "#f76b15", "seal": "#ffb224", "envelope": "#30a46c", "phone": "#0090ff", "cable": "#8e4ec6", "charger": "#d6409f"}
    parts = ["<!doctype html><meta charset=utf-8><title>Autolabel review</title><style>body{font:13px sans-serif;margin:16px}"
             ".c{display:inline-block;margin:6px;vertical-align:top}.f{position:relative;display:inline-block}"
             ".b{position:absolute;border:2px solid;box-sizing:border-box}.t{position:absolute;top:-16px;left:0;color:#fff;font-size:11px;padding:0 3px;white-space:nowrap}</style>"
             "<h1>DRAFT labels (OWLv2 zero-shot), review before training</h1>"]
    scale = 0.5
    for f, w, h, found in rows:
        parts.append(f"<div class=c><div class=f style='width:{w*scale}px;height:{h*scale}px'><img src='images/{html.escape(f)}' width={w*scale}>")
        for n, s, x0, y0, x1, y1 in found:
            c = colors[n]
            parts.append(f"<div class=b style='left:{x0*scale}px;top:{y0*scale}px;width:{(x1-x0)*scale}px;height:{(y1-y0)*scale}px;border-color:{c}'>"
                         f"<span class=t style='background:{c}'>{n} {s:.2f}</span></div>")
        parts.append(f"</div><br>{html.escape(f)}</div>")
    open(os.path.join(OUT, "review.html"), "w", encoding="utf-8").write("".join(parts))
    per = {n: sum(1 for a in coco["annotations"] if a["category_id"] == i) for i, n in enumerate(names)}
    print("done", len(files), "images", per, f"{time.time() - t0:.0f}s")


if __name__ == "__main__":
    sys.exit(main())
