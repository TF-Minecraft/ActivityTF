#!/usr/bin/env python3
"""Prepare audited activity lore without rewriting settings or YAML comments.

Requires PyYAML. Writes a separate output file; never edits the input in place.
"""
import argparse
import json
import re
from pathlib import Path

import yaml


# {one|many} picks the singular form when the amount it follows is exactly 1.
ACTIONS = {
    "vote": ["&7Cast &a%every% &7{vote|votes} for the server.", "&7Links: &f/vote"],
    "playtime": ["&7Play actively for &a%every% &7{minute|minutes}.", "&7Idle minutes do not count when AFK checks are enabled."],
    "geiger": ["&7Collect &a%every% &7arcane {source|sources} with a Trace Detector."],
    "archaeology_find": ["&7Recover &a%every% &7archaeological {find|finds}."],
    "instrument": ["&7Play &a%every% &7{note|notes} with an instrument."],
    "market_sale": ["&7Earn &a%every% &7Denar selling to a Market Block."],
    "casino_win": ["&7Win &a%every% &7Denar in card-game profit.", "&7Counts profit above your stake, before tax."],
    "ic_chat": ["&7Send &a%every% &7substantial in-character {message|messages}.", "&7Short or repetitive messages do not count."],
    "injured": ["&7Receive &a%every% &7roleplay {injury|injuries}."],
    "furniture_place": ["&7Place &a%every% &7{piece|pieces} of furniture."],
    "vehicle_build": ["&7Finish &a%every% &7{vehicle|vehicles} at a construction station."],
    "battle_joined": ["&7Participate in &a%every% &7faction {battle|battles}.", "&7Credit is awarded when the battle ends."],
    "advcraft_item": ["&7Complete &a%every% &7Advanced Crafting {item or alloy|items or alloys}."],
    "cook_dish": ["&7Finish &a%every% &7{dish|dishes} at cooking pots, stations or boards.", "&7Trough feed counts toward its own activity."],
    "profession_upgrade": ["&7Purchase &a%every% &7profession {upgrade|upgrades}."],
    "brew_ingredient": ["&7Add &a%every% &7{ingredient|ingredients} to a brewing cauldron."],
    "brew_bottle": ["&7Fill &a%every% &7{bottle|bottles} from a brewing cauldron", "&7after at least a minute of cooking."],
    "brew_age": ["&7Take &a%every% &7{brew|brews} aged at least a year out of a barrel."],
    "brew_drink": ["&7Drink &a%every% &7finished {brew|brews}."],
    "animal_universal_feed": ["&7Fill a trough with wheat or vegetables.", "&7Collect feed &a%every% &7{time|times} with an empty-hand right click."],
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


def counted(line, amount):
    return re.sub(r"\{([^|{}]*)\|([^|{}]*)\}", lambda m: m[1] if amount == 1 else m[2], line)


def article(name):
    return "an" if name[:1].lower() in "aeiou" else "a"


def description(activity_id, activity):
    if activity_id in ACTIONS:
        lines = ACTIONS[activity_id]
    elif activity.get("profession"):
        name = activity["profession"].replace("_", " ").title()
        lines = [f"&7Earn &a%every% &7{name} profession XP."]
    elif activity.get("station"):
        station, separator, recipe = activity["station"].strip().lower().partition("/")
        station, recipe = station.strip(), recipe.strip()
        if station not in STATIONS:
            raise ValueError(f"No audited station location for {station}")
        if separator:
            name = RECIPE_NAMES.get(recipe, recipe.replace("-", " ").title())
            lines = [f"&7Complete the {name} recipe &a%every% &7{{time|times}}.",
                     f"&7Use {article(STATIONS[station])} {STATIONS[station]} and claim queued crafts."]
        else:
            lines = [f"&7Complete &a%every% &7{{recipe|recipes}} at {article(STATIONS[station])} {STATIONS[station]}.",
                     "&7Claim queued crafts to receive credit."]
        if activity_id == "ingot_coal" and "ingot-station/coal-64" in activity.get("station-actions", {}):
            lines = ["&7Complete &a%every% &7standard Coal {craft|crafts} at a Blast Furnace.",
                     "&7The bulk Coal recipe also counts toward this task."]
    elif activity.get("craft"):
        name = activity["craft"].removeprefix("v.").replace("_", " ").title()
        lines = [f"&7Craft &a%every% &7{name} {{item|items}}."]
    else:
        raise ValueError(f"No audited description for {activity_id}")
    # Defaults match ActivityConfiguration: every 1, points 0.
    lines = [counted(line, activity.get("every", 1)) for line in lines]
    points = counted("&7Earn &a%points% &7activity {point|points} per completion.", activity.get("points", 0))
    return [*lines, points, "&7"]


def remove_description(block):
    node = yaml.compose(block)
    for key, value in node.value:
        if key.value != "description":
            continue
        start = block.rfind("\n", 0, key.start_mark.index) + 1
        end = value.end_mark.index
        last_start = block.rfind("\n", 0, end) + 1
        if block[last_start:end].strip():
            newline = block.find("\n", end)
            end = len(block) if newline < 0 else newline + 1
        else:
            end = last_start
        comments = "".join(line for line in block[start:end].splitlines(keepends=True)
                           if line.lstrip().startswith("#"))
        return block[:start] + comments + block[end:]
    return block


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
        # Parsed node marks cover strings, block scalars and either list indent.
        block = remove_description(block)
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
