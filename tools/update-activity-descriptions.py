#!/usr/bin/env python3
"""Prepare audited activity lore without rewriting settings or YAML comments.

Requires PyYAML. Writes a separate output file; never edits the input in place.
"""
import argparse
import json
import re
from pathlib import Path

import yaml


ACTIONS = {
    "vote": ["&7Cast &a%every% &7votes for the server.", "&7Links: &f/vote"],
    "playtime": ["&7Play actively for &a%every% &7minutes.", "&7Idle minutes do not count when AFK checks are enabled."],
    "geiger": ["&7Collect &a%every% &7arcane sources with a Trace Detector."],
    "archaeology_find": ["&7Recover &a%every% &7archaeological finds."],
    "instrument": ["&7Play &a%every% &7notes with an instrument."],
    "market_sale": ["&7Earn &a%every% &7Denar selling to a Market Block."],
    "casino_win": ["&7Win &a%every% &7Denar in card-game profit.", "&7Counts profit above your stake, before tax."],
    "ic_chat": ["&7Send &a%every% &7substantial in-character messages.", "&7Short or repetitive messages do not count."],
    "injured": ["&7Receive &a%every% &7roleplay injuries."],
    "furniture_place": ["&7Place &a%every% &7pieces of furniture."],
    "vehicle_build": ["&7Finish &a%every% &7vehicles at a construction station."],
    "battle_joined": ["&7Participate in &a%every% &7faction battles.", "&7Credit is awarded when the battle ends."],
    "advcraft_item": ["&7Complete &a%every% &7Advanced Crafting items or alloys."],
    "cook_dish": ["&7Finish &a%every% &7dishes at cooking pots, stations or boards.", "&7Trough feed counts toward its own activity."],
    "profession_upgrade": ["&7Purchase &a%every% &7profession upgrades."],
    "animal_universal_feed": ["&7Fill a trough with wheat or vegetables.", "&7Collect feed &a%every% &7times with an empty-hand right click."],
}

# Recipe names and station locations, checked against Main's active recipes.
# Count completed recipes, not output stack sizes. Bulk aliases retain the
# configured station-actions credit; the updater never changes those values.
STATIONS = {
    "ingot-station": "Blast Furnace",
    "tool-station": "Crafting Table",
    "block-station": "Stonecutter",
    "forester-station": "Fletching Table",
    "alchemy-station": "Brewing Stand",
    "instrument-station": "Jukebox",
    "research-station": "Cartography Table",
    "medicine-station": "Medicine Station",
    "engineer-station": "Engineer Station",
    "fishing-station": "Fishing Station",
    "magic-station": "Magic Station",
    "animal-station": "Animal Station",
}
RECIPE_NAMES = {
    "iron-axe": "Iron Hatchet",
    "minor-health": "Minor Health Potion",
    "r-rare-research-paper": "Unknown Research Paper",
    "r-bronze": "Research Bronze",
    "antibiotics": "Willow-Bark Tincture",
    "fuel": "Arcane Fuel",
    "fishing-rod": "Basic Fishing Rod",
    "llama-egg": "Llama Spawn Egg",
}


def description(activity_id, activity):
    if activity_id in ACTIONS:
        lines = ACTIONS[activity_id]
    elif activity.get("profession"):
        name = activity["profession"].replace("_", " ").title()
        lines = [f"&7Earn &a%every% &7{name} profession XP."]
    elif activity.get("station"):
        station, recipe = activity["station"].split("/", 1)
        name = RECIPE_NAMES.get(recipe, recipe.replace("-", " ").title())
        lines = [f"&7Complete the {name} recipe &a%every% &7times.",
                 f"&7Use a {STATIONS[station]} and claim queued crafts."]
        if activity_id == "ingot_coal" and "ingot-station/coal-64" in activity.get("station-actions", {}):
            lines = ["&7Complete &a%every% &7standard Coal crafts at a Blast Furnace.",
                     "&7The bulk Coal recipe also counts toward this task."]
    elif activity.get("craft"):
        name = activity["craft"].removeprefix("v.").replace("_", " ").title()
        lines = [f"&7Craft &a%every% &7{name} items."]
    else:
        raise ValueError(f"No audited description for {activity_id}")
    return [*lines, "&7Earn &a%points% &7activity points per completion.", "&7"]


def update(source):
    config = yaml.safe_load(source)
    activities = config["activities"]
    section = re.search(r"(?ms)^activities:\s*\n(.*?)(?=^[^\s#]|\Z)", source)
    if section is None:
        raise ValueError("Cannot locate activities section")
    body = section[1]
    headers = list(re.finditer(r"(?m)^  ([a-zA-Z0-9_-]+):[^\n]*\n", body))
    if {m[1] for m in headers} != set(activities):
        raise ValueError("Activity indentation is unsupported; expected two spaces")
    for index in reversed(range(len(headers))):
        header = headers[index]
        end = headers[index + 1].start() if index + 1 < len(headers) else len(body)
        block = body[header.end():end]
        # Remove only the active description field and its active list lines.
        block = re.sub(r"(?m)^    description:[^\n]*\n(?:^      -[^\n]*\n)*", "", block)
        lore = "    description:\n" + "".join(
            "      - " + json.dumps(line, ensure_ascii=False) + "\n"
            for line in description(header[1], activities[header[1]]))
        display = re.search(r"(?m)^    display:[^\n]*\n", block)
        insert = display.end() if display else 0
        block = block[:insert] + lore + block[insert:]
        body = body[:header.end()] + block + body[end:]
    result = source[:section.start(1)] + body + source[section.end(1):]
    # Verify that no setting outside activity descriptions changed.
    updated = yaml.safe_load(result)
    for activity in updated["activities"].values():
        activity.pop("description", None)
    for activity in config["activities"].values():
        activity.pop("description", None)
    if updated != config:
        raise ValueError("Unexpected non-description change")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve():
        parser.error("Input and output must be separate files")
    args.output.write_text(update(args.input.read_text(encoding="utf-8")), encoding="utf-8")
