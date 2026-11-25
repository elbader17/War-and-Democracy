"""Build the V2 workshop models in Minecraft's 16-unit block coordinate system."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources"
MODELS = ROOT / "assets/modserver/models"
PALETTE = {
    "iron": "create:block/industrial_iron_block", "brass": "create:block/brass_block",
    "panel": "create:block/brass_casing", "base": "create:block/andesite_casing",
    "copper": "minecraft:block/cut_copper", "wood": "minecraft:block/stripped_spruce_log",
    "dark": "minecraft:block/polished_deepslate", "glass": "minecraft:block/sea_lantern",
    "white": "minecraft:block/quartz_block_top", "red": "minecraft:block/red_terracotta",
    "blue": "minecraft:block/oxidized_copper", "particle": "create:block/andesite_casing",
}
TIERS = ["watchtower", "bastion", "skyguard", "aegis"]


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def bar(a, b, material):
    faces = {face: {"texture": "#" + material} for face in ("north", "south", "east", "west", "up", "down")}
    return {"from": a, "to": b, "faces": faces}


def model(path, shapes, display=False):
    data = {"parent": "minecraft:block/block", "textures": PALETTE, "elements": shapes}
    if display:
        data["display"] = {"gui": {"rotation": [26, 148, 0], "scale": [.67, .67, .67]},
                           "ground": {"scale": [.35, .35, .35]}, "fixed": {"scale": [.7, .7, .7]}}
    save(MODELS / (path + ".json"), data)


def pedestal(tier):
    metal = "iron" if tier in TIERS[2:] else "base"
    parts = [bar([1, 0, 1], [15, 2, 15], "dark"), bar([2, 2, 2], [14, 5, 14], metal),
             bar([3, 4, 3], [13, 6, 13], "brass"), bar([4, 6, 4], [12, 7, 12], "iron")]
    for x in (1, 12):
        for z in (1, 12):
            parts.extend([bar([x, 1, z], [x+3, 4, z+3], "iron"),
                          bar([x+1, 4, z+1], [x+2, 4.6, z+2], "brass")])
    for x in (4, 7, 10):
        parts.append(bar([x, 2.7, 1.7], [x+1.1, 3.4, 2.1], "dark"))
    return parts


def mount(tier):
    parts = [bar([3, 7, 3], [13, 8.5, 13], "iron"), bar([4, 8.5, 4], [12, 9, 12], "copper")]
    for x in (3, 11):
        parts.extend([bar([x, 8, 5], [x+2, 13, 10], "panel"),
                      bar([x-.25, 10.5, 6.5], [x+2.25, 13.5, 9.5], "brass")])
    if tier != "watchtower":
        parts.extend([bar([1.5, 8.5, 4], [3, 11, 10], "wood"),
                      bar([1.4, 8.4, 5], [3.1, 11.1, 6], "brass")])
    if tier in ("skyguard", "aegis"):
        # Outboard trunnions carry the weight of the launcher rack.
        for x in (1.6, 12.4):
            parts.extend([bar([x, 7.5, 3.8], [x+2, 9, 12], "iron"),
                          bar([x+.25, 8, 6.7], [x+1.75, 12, 9.3], "copper")])
    return parts


def carriage(tier):
    parts = [bar([5, 10, 4], [11, 14, 10], "iron")]
    if tier == "watchtower":
        parts.extend([bar([6.5, 11, 9], [9.5, 13.5, 17], "iron"),
                      bar([6, 10.8, 14], [10, 13.8, 15.5], "copper"),
                      bar([7, 14, 5], [9, 15.5, 8], "brass"), bar([7.4, 14.3, 7.9], [8.6, 15.1, 8.2], "glass")])
    elif tier == "bastion":
        parts.extend([bar([5, 10.3, 8], [11, 14.7, 14], "copper"), bar([5.6, 11, 13], [10.4, 14, 19], "iron"),
                      bar([5, 10.5, 17], [11, 14.5, 19], "brass"), bar([6.6, 11.4, 19], [9.4, 13.7, 19.2], "dark")])
        for x in (4.1, 10.6):
            parts.append(bar([x, 10, 3], [x+1.3, 11.5, 13], "brass"))
    else:
        for x in (3, 9):
            for y in ([11] if tier == "skyguard" else [10, 14.5]):
                parts.extend([bar([x, y, 2], [x+4, y+3.7, 15], "blue" if tier == "aegis" else "copper"),
                              bar([x-.24, y-.2, 4], [x+4.24, y+3.9, 5], "brass"),
                              bar([x-.24, y-.2, 12], [x+4.24, y+3.9, 13], "iron"),
                              bar([x+.45, y+.35, 15], [x+3.55, y+3.35, 15.3], "dark"),
                              bar([x+1.25, y+1, 15.3], [x+2.75, y+2.7, 16], "white")])
    if tier in ("skyguard", "aegis"):
        for x in (2.5, 12.8):
            parts.extend([bar([x, 10.4, 1.5], [x+.65, 11.2, 16.7], "iron"),
                          bar([x, 11.2, 6.1], [x+.65, 14.8, 6.9], "brass")])
    if tier == "aegis":
        # A raised rangefinder leaves the front and rear of all four cells visible.
        parts.extend([bar([6.2, 17, 4], [9.8, 19.8, 7], "iron"),
                      bar([5.6, 19, 4.3], [10.4, 20.8, 6.2], "brass"),
                      bar([6.2, 19.2, 6.2], [9.8, 20.5, 6.5], "glass")])
    return parts


def drive_wheel():
    parts = [bar([12.5, 7, 7], [14.5, 9, 9], "brass")]
    for lo, hi in [([13, 4, 6], [14, 5, 10]), ([13, 11, 6], [14, 12, 10]),
                   ([13, 6, 4], [14, 10, 5]), ([13, 6, 11], [14, 10, 12]),
                   ([13, 5, 5], [14, 6, 6]), ([13, 5, 10], [14, 6, 11]),
                   ([13, 10, 5], [14, 11, 6]), ([13, 10, 10], [14, 11, 11]),
                   ([13, 7.6, 5], [14, 8.4, 11]), ([13, 5, 7.6], [14, 11, 8.4])]:
        parts.append(bar(lo, hi, "copper"))
    return parts


def breech(tier):
    if tier == "watchtower":
        return [bar([9, 12, 4.5], [11.5, 13, 7.5], "brass"), bar([11, 13, 4.5], [12, 14.5, 5.5], "wood")]
    if tier == "bastion":
        return [bar([5.2, 10.4, 2.5], [10.8, 13.7, 4.4], "brass"), bar([6, 11, 1.7], [10, 13, 2.6], "iron")]
    parts = []
    for x in (3, 9):
        parts.append(bar([x+.2, 10.7, 1.3], [x+3.8, 14.4, 2.2], "brass"))
        if tier == "aegis":
            parts.append(bar([x+.2, 15.2, 1.3], [x+3.8, 18.8, 2.2], "iron"))
    return parts


def projectile(kind):
    if kind == "brass_slug":
        return [bar([6, 6, 4], [10, 10, 12], "brass"), bar([6.7, 6.7, 12], [9.3, 9.3, 14], "iron")]
    if kind == "survey_key":
        return [bar([7, 6, 2], [9, 8, 12], "brass"), bar([8, 6, 3], [11, 8, 4], "brass"),
                bar([5, 5, 10], [11, 9, 14], "copper"), bar([6.5, 9, 11], [9.5, 9.5, 13], "glass")]
    shell = {"skyrocket": "copper", "interceptor": "blue", "flare_cartridge": "red"}[kind]
    parts = [bar([6.5, 6.5, 2], [9.5, 9.5, 11.5], shell), bar([6.25, 6.25, 4], [9.75, 9.75, 5], "brass"),
             bar([6.8, 6.8, 11.5], [9.2, 9.2, 14], "white"), bar([7.4, 7.4, 14], [8.6, 8.6, 15.5], "iron"),
             bar([7.1, 7.1, 1], [8.9, 8.9, 2], "dark")]
    if kind != "flare_cartridge":
        parts.extend([bar([4.5, 7.5, 2.5], [11.5, 8.5, 5.5], "iron"),
                      bar([7.5, 4.5, 2.5], [8.5, 11.5, 5.5], "iron")])
    return parts


def rack_scale(shapes, tier):
    size = {"watchtower": 1, "bastion": 1.16, "skyguard": 1.42, "aegis": 1.54}[tier]
    for shape in shapes:
        for key in ("from", "to"):
            shape[key] = [round(pivot + (value - pivot) * size, 3)
                          for pivot, value in zip((8, 12, 8), shape[key])]
    return shapes


for tier in TIERS:
    moving_head = rack_scale(carriage(tier), tier)
    reload_slide = rack_scale(breech(tier), tier)
    model("block/" + tier, pedestal(tier))
    model("block/defense/" + tier + "_mount", mount(tier))
    model("block/defense/" + tier + "_carriage", moving_head)
    model("block/defense/" + tier + "_breech", reload_slide)
    model("item/" + tier, pedestal(tier) + mount(tier) + moving_head + reload_slide + drive_wheel(), display=True)
    save(ROOT / f"assets/modserver/blockstates/{tier}.json", {"variants": {"": {"model": "modserver:block/" + tier}}})
model("block/defense/drive_wheel", drive_wheel())
for name in ("skyrocket", "interceptor", "brass_slug", "flare_cartridge", "survey_key"):
    model("item/" + name, projectile(name), display=True)
