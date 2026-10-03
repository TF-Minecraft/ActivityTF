# ActivityTF

> Daily activities and weekly rewards for TF-Minecraft.

ActivityTF gives players a rotating set of things to do across the server. Each day's activity menu contains seven tasks to reveal, with progress feeding into a weekly reward bar. It brings exploration, crafting, roleplay, and community participation together in one place.

Tasks only start earning activity credit once revealed, giving players a reason to check their daily selection before heading out.

## Features

- **Personal daily task menus** — a fresh selection of activities, with progress displays and descriptions for revealed tasks.
- **Weekly reward milestones** — activity points unlock claimable rewards from weighted reward pools.
- **Daily reveal rewards** — players can claim a separate reward after revealing all of their daily tasks.
- **Activities across the server** — supported integrations track actions such as voting, cooking, archaeology, instrument playing, market sales, and vehicle building.
- **Varied progression limits** — daily caps and a voting share of the daily allowance shape how points are earned.
- **Task rerolls** — eligible players can redraw their daily selection within the server's limits.

Available activities depend on the gameplay plugins and activity definitions in use.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/ActivityTF/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

[Activity descriptions and placeholders](https://github.com/TF-Minecraft/Docs/blob/main/projects/ActivityTF/ACTIVITY-LORE.md)

## Tests

With Java 21 and the pinned plugin dependencies installed (see the build workflow), run:

```sh
mvn -B --no-transfer-progress clean verify
```

JUnit 5 tests cover configuration loading, commands, the GUI, plugin hooks,
listeners and activity progress, using proxy and Objenesis fixtures for server
and plugin APIs. CI runs the same command on every push and pull request to `main` and
uploads the Surefire reports; no coverage gate is enforced. The suite does not
start a live Paper server.

The description update tool has its own Python tests, which CI does not run:

```sh
python3 -m unittest discover -s tools -p 'test_*.py'
```

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
