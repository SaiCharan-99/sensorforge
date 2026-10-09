import json, copy, sys, os
out = sys.argv[1]
STACK = "sf-perception-test-v1"

def P(id, type, t, track=None, cls=None, conf=0.9, **kw):
    p = {"id": id, "type": type, "t": t, "confidence": conf}
    if track:
        p["entity"] = {"track_id": track, "cls": cls}
    p.update(kw)
    return p

base = {
    "clip_id": "base", "perception_stack": STACK, "duration_s": 20.0,
    "tracks": [
        {"track_id": "t1", "cls": "box", "identity_id": "i1", "t_start": 0, "t_end": 20},
        {"track_id": "t2", "cls": "charger", "identity_id": "i2", "t_start": 1, "t_end": 20},
        {"track_id": "t3", "cls": "adapter", "identity_id": "i3", "t_start": 1, "t_end": 20, "code": "AD-42"},
    ],
    "primitives": [
        P("p1", "state_change", 2.0, "t1", "box", state="open", evidence_frames=[60]),
        P("p2", "exits_container", 5.0, "t2", "charger", container="box", evidence_frames=[150]),
        P("p3", "exits_container", 7.0, "t3", "adapter", container="box", evidence_frames=[210]),
        P("p4", "code_read", 9.0, "t3", "adapter", code="AD-42", evidence_frames=[270]),
        P("p5", "region_empty", 12.0, zone="box_interior", evidence_frames=[360]),
    ],
    "observability": [
        {"target": "box", "intervals": [[0, 20]]},
        {"target": "charger", "intervals": [[0, 20]]},
        {"target": "adapter", "intervals": [[0, 20]]},
        {"target": "zone:box_interior", "intervals": [[0, 20]]},
    ],
}

def scenario(name, desc, verdict, reasons, mutate=lambda t: None, expected_stack=STACK):
    t = copy.deepcopy(base)
    t["clip_id"] = name
    mutate(t)
    doc = {"name": name, "description": desc, "label": "FIXTURE (synthetic golden timeline, not a live recording)",
           "spec": "specs/packcheck.spec.json", "expected_perception_stack": expected_stack,
           "timeline": t, "expected": {"verdict": verdict, "reasons": reasons}}
    with open(os.path.join(out, name + ".json"), "w", newline="\n") as f:
        json.dump(doc, f, indent=2)
        f.write("\n")

def prim(t, id): return next(p for p in t["primitives"] if p["id"] == id)
def drop(t, *ids): t["primitives"] = [p for p in t["primitives"] if p["id"] not in ids]
def obs(t, target, iv): next(o for o in t["observability"] if o["target"] == target)["intervals"] = iv

scenario("01_valid_order_a", "Correct kit, charger then adapter (T-B02).", "PASS", [])
scenario("02_valid_order_b", "Correct kit, adapter then charger (ANY_ORDER).", "PASS", [],
         lambda t: (prim(t, "p2").update(t=7.0), prim(t, "p3").update(t=5.0)))

def absent(t):
    drop(t, "p3", "p4")
    t["tracks"] = [x for x in t["tracks"] if x["track_id"] != "t3"]
    obs(t, "adapter", [])
scenario("03_required_absent", "Adapter never leaves the box (T-B01). Label step unseen because the adapter never appears.",
         "FAIL", ["REQUIRED_ABSENT:adapter_out", "REQUIRED_NOT_OBSERVABLE:adapter_label"], absent)
scenario("04_required_not_observable", "Box only in view for 0-6 s; adapter exit never seen.",
         "UNVERIFIED", ["REQUIRED_NOT_OBSERVABLE:adapter_out"], lambda t: (drop(t, "p3"), obs(t, "box", [[0, 6]])))
scenario("05_camera_unstable_during_step", "Camera unstable 6.5-7.5 s covers the adapter exit.",
         "UNVERIFIED", ["REQUIRED_NOT_OBSERVABLE:adapter_out"],
         lambda t: t["primitives"].append(P("u1", "camera_unstable", 6.5, t_end=7.5)))
scenario("06_order_violated", "Charger taken out before the box is opened.",
         "FAIL", ["ORDER_VIOLATED:box_opened->charger_out"], lambda t: prim(t, "p1").update(t=6.0))
scenario("07_code_mismatch", "Adapter label reads the wrong code.",
         "FAIL", ["CODE_MISMATCH:adapter_label"], lambda t: prim(t, "p4").update(code="AD-99"))

def mismatch(t):
    t["tracks"].append({"track_id": "t4", "cls": "adapter", "identity_id": "i4", "t_start": 8, "t_end": 20, "code": "AD-42"})
    prim(t, "p4")["entity"] = {"track_id": "t4", "cls": "adapter"}
scenario("08_same_identity_mismatch", "Label read on a different adapter than the one taken out.",
         "FAIL", ["IDENTITY_MISMATCH:adapter_out=adapter_label", "PROHIBITED_OBSERVED:adapter"], mismatch)

def unproven(t):
    mismatch(t)
    t["primitives"].append(P("u2", "identity_unresolved", 8.0, "t4", "adapter", other_track_id="t3"))
scenario("09_identity_unproven", "Adapter leaves view and a similar one returns (T-B05).",
         "UNVERIFIED", ["IDENTITY_UNPROVEN:adapter_out=adapter_label", "PROHIBITED_UNDETERMINED:adapter"], unproven)

def extra(t, link):
    t["tracks"].append({"track_id": "t5", "cls": "adapter", "identity_id": "i5", "t_start": 10, "t_end": 20})
    t["primitives"].append(P("a5", "appears", 10.0, "t5", "adapter"))
    if link:
        t["primitives"].append(P("u3", "identity_unresolved", 10.0, "t5", "adapter", other_track_id="t3"))
scenario("10_max_instances_exceeded", "A second, distinct adapter appears (duplicate).",
         "FAIL", ["PROHIBITED_OBSERVED:adapter"], lambda t: extra(t, False))
scenario("11_max_instances_undetermined", "A second adapter track that may be the first one.",
         "UNVERIFIED", ["PROHIBITED_UNDETERMINED:adapter"], lambda t: extra(t, True))
scenario("12_unexplained_change", "Significant scene change not tied to any step.",
         "UNVERIFIED", ["UNEXPLAINED_CHANGE:x1"], lambda t: t["primitives"].append(P("x1", "unexplained_change", 10.0)))
scenario("13_perception_stack_mismatch", "Timeline produced by a different perception stack than the pack expects.",
         "UNVERIFIED", ["PERCEPTION_STACK_MISMATCH:" + STACK], expected_stack="sf-perception-other")

def impostor(t):
    t["tracks"].append({"track_id": "t6", "cls": "impostor_cable", "identity_id": "i6", "t_start": 4, "t_end": 20})
    t["primitives"].append(P("a6", "appears", 4.0, "t6", "impostor_cable", conf=0.85))
scenario("14_forbidden_impostor", "Look-alike impostor cable seen (T-B03).",
         "FAIL", ["PROHIBITED_OBSERVED:impostor_cable"], impostor)
print("ok")
