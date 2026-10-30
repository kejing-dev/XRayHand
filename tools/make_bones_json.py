"""Builds app/src/main/assets/bones.json from the design package (names, groups, facts) and the right-hand GLB
(per-bone mesh bounds in the bone's local frame), and copies the GLBs + rig into the app's assets.
Run: python -I tools/make_bones_json.py <factory apps/xray-hand dir> <app assets dir>"""
import json, shutil, struct, sys, os

src, dst = sys.argv[1], sys.argv[2]
dp = json.load(open(os.path.join(src, "design-package.json"), encoding="utf-8"))
glb = open(os.path.join(src, "assets", "hand_bones_right.glb"), "rb").read()
n = struct.unpack("<I", glb[12:16])[0]
g = json.loads(glb[20:20 + n])
bounds = {}
for node in g["nodes"]:
    if "mesh" not in node:
        continue
    acc = g["accessors"][g["meshes"][node["mesh"]]["primitives"][0]["attributes"]["POSITION"]]
    bounds[node["name"]] = {"min": [round(v, 5) for v in acc["min"]], "max": [round(v, 5) for v in acc["max"]]}
out = []
for b in dp["bones"]["list"]:
    e = {k: b[k] for k in ("id", "group", "zh", "en", "factZh", "factEn")}
    e["subgroup"] = b.get("subgroup") or b.get("row") and ("proximalRow" if b.get("row") == "近排" else "distalRow") or ""
    e["bounds"] = bounds[b["id"]]
    out.append(e)
assert len(out) == 27 and all(e["id"] in bounds for e in out)
json.dump({"bones": out, "mnemonic": dp["bones"]["mnemonic"]}, open(os.path.join(dst, "bones.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
for f in ("hand_bones_right.glb", "hand_bones_left.glb", "hand_rig.json"):
    shutil.copyfile(os.path.join(src, "assets", f), os.path.join(dst, f))
print("bones.json", len(out), "bones")
