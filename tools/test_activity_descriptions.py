import importlib.util
from pathlib import Path
import unittest

import yaml

spec = importlib.util.spec_from_file_location(
    "lore", Path(__file__).with_name("update-activity-descriptions.py"))
lore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lore)


class MigrationTest(unittest.TestCase):
    def test_preserves_commented_activities_settings_and_custom_rewards(self):
        source = '''reset:
  hour: 3
activities:
  vote:
    display: "Voting"
    description: "Old explanation"
    every: 2
    points: 7
    daily-cap: 0
  # furniture_place:
  #   description: "Keep this comment"
  #   every: 99
  profession_forager:
    description:
      - "Typo 2x50"
      - ""
    profession: forager
    every: 250
rewards:
  multiplier: 2
'''
        updated = lore.update(source)
        self.assertEqual(lore.update(updated), updated)
        comments = lambda text: [line for line in text.splitlines() if line.lstrip().startswith("#")]
        self.assertEqual(comments(source), comments(updated))
        parsed = yaml.safe_load(updated)
        self.assertEqual(parsed["activities"]["vote"]["every"], 2)
        self.assertEqual(parsed["activities"]["vote"]["points"], 7)
        self.assertEqual(parsed["activities"]["vote"]["daily-cap"], 0)
        self.assertIn("%every%", parsed["activities"]["profession_forager"]["description"][0])
        self.assertEqual(parsed["rewards"], {"multiplier": 2})

    def test_rejects_unknown_activity_instead_of_misdescribing_it(self):
        with self.assertRaisesRegex(ValueError, "No audited description"):
            lore.update("activities:\n  custom:\n    every: 1\n")

    def test_accepts_supported_station_formats(self):
        for station in [" TOOL-STATION / IRON-PICKAXE ", "tool-station"]:
            with self.subTest(station=station):
                source = "activities:\n  custom:\n    station: " + repr(station) + "\n    every: 2\n"
                description = yaml.safe_load(lore.update(source))["activities"]["custom"]["description"]
                self.assertIn("Crafting Table", " ".join(description))
                self.assertIn("%every%", " ".join(description))

    def test_matches_wording_to_amounts(self):
        source = '''activities:
  vote:
    every: 1
    points: 1
  injured:
    every: 2
    points: 5
  engineer:
    station: engineer-station/fuel
    every: 1
'''
        parsed = yaml.safe_load(lore.update(source))["activities"]
        self.assertEqual(parsed["vote"]["description"][0], "&7Cast &a%every% &7vote for the server.")
        self.assertEqual(parsed["vote"]["description"][2], "&7Earn &a%points% &7activity point per completion.")
        self.assertEqual(parsed["injured"]["description"][0], "&7Receive &a%every% &7roleplay injuries.")
        self.assertEqual(parsed["injured"]["description"][1], "&7Earn &a%points% &7activity points per completion.")
        self.assertEqual(parsed["engineer"]["description"][:2], [
            "&7Complete the Arcane Fuel recipe &a%every% &7time.",
            "&7Use an Engineer Station and claim queued crafts."])

    def test_replaces_complete_yaml_values_and_keeps_comments(self):
        for value in ['"Old"', '\n    - "Old"\n    # Keep this comment\n    - "Another"',
                      '|\n      Old multiline\n      explanation', '>\n      Old folded\n      explanation']:
            with self.subTest(value=value):
                source = 'activities:\n  vote:\n    display: Voting\n    description: ' + value + '\n    every: 2\n'
                updated = lore.update(source)
                parsed = yaml.safe_load(updated)["activities"]["vote"]
                self.assertEqual(parsed["every"], 2)
                self.assertNotIn("Old", updated)
                if "# Keep this comment" in source:
                    self.assertIn("# Keep this comment", updated)


if __name__ == "__main__":
    unittest.main()
