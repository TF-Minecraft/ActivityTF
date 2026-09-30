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


if __name__ == "__main__":
    unittest.main()
