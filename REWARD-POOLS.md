# Named reward pools

Install the rebuilt Activity jar and restart the server. After that, configuration
changes can be applied with `/activity reload`.

Example (replace the sample rewards with your own):

```yaml
rewards:
  multiplier: 1
  drops:
    drop_1: pool_prologue
    drop_2: pool_prologue
    drop_3: pool_end
  pools:
    pool_prologue:
      - weight: 65
        display: "#50d990x2 #7f7d80Ignitium"
        items:
          - item: m.materials.ignitium
            amount: 2
    pool_end:
      - weight: 1
        display: "Netherite Ingot"
        items:
          - item: NETHERITE_INGOT
            amount: 1

daily-reward:
  groups:
    vip: pool_end
    default: pool_prologue
```

Groups use the existing `group.<name>` permissions, with the first matching group
winning. Daily rewards draw once; milestone rewards draw `multiplier` times.
`drop_N` selects the Nth configured milestone, so only define drops that exist.

Named pools use the `pool_` prefix and are case-insensitive. Lists directly under
`rewards.pool_prologue`, etc. also work; definitions under `rewards.pools` take
precedence. The legacy value `pool` still uses `rewards.pool`. Missing or empty
pools pay nothing, warn, and leave the claim available. An omitted default pool
does not inherit the example rewards bundled inside the jar. If a claim spans
several milestones and any required pool is empty, fix it before claiming.

# Universal feed and fishing rods

Universal feed is now tracked through Cooking's trough completion event. In an
existing Activity config, remove `station: animal-station/universal-feed` from
`activities.animal_universal_feed`. Keep that activity ID. The default threshold
is two completed feed batches for one point, capped at one completion per day.
Adding vegetables alone does not count: collect the feed from the full trough.

This requires the accompanying Cooking source change in `TroughHandler.java`,
which emits `DishCookedEvent` with method `trough` after handing over the feed.
The local Cooking build is blocked by mismatched InteractibleFurniture, TLibs,
SimpleFactions, and RPCharacters APIs; no updated Cooking jar was produced.

The Activity jar includes PR #48's fishing fix. Restart after replacing the jar;
`/activity reload` does not replace Java code. The basic rod uses
`station: fishing-station/fishing-rod` and counts when the completed craft is
collected from the station queue, including recipes with `output-item: false`.

Validation: Activity's 741 automated tests pass. No live-server reproduction was
performed.
