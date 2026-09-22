# activity-tf

Technical documentation is maintained in [TF-Minecraft/docs](https://github.com/TF-Minecraft/docs/tree/main/projects/activity-tf).

Use that project index for setup, configuration, architecture, integration and testing guides. This repository contains the source and project-specific assets.

This repository remains private. Public documentation links do not grant access to its contents.

## TLibs build dependency

TLibs is a versioned Maven `provided` dependency. From this repository, prepare
it once with the shared installer, then build as usual:

```sh
python3 ../tlibs/tools/install-dependency.py --pom pom.xml
mvn clean verify
```

See [TLibs dependency setup](https://github.com/TF-Minecraft/TLibs/blob/v1.1.0/DEPENDENCIES.md)
for public release installation, offline builds and rollback.
Other declared build dependencies still need their usual preparation.

Builds and server runtime require Java 25 and [TLibs 1.1.0](https://github.com/TF-Minecraft/TLibs/releases/tag/v1.1.0).

Other compile-time plugin APIs are fetched from a pinned private ServerAssets commit:
`GH_TOKEN` needs Contents read access to `TF-Minecraft/ServerAssets`; run
`bash .github/scripts/prepare-release.sh` before Maven. The script verifies
`.github/dependencies.sha256`. It uses the authorized inputs validated with this
upgrade, including VehicleFramework 1.1.12, AdvancedCrafting 1.2.1 and full
Cooking/Games JARs for their compile-time APIs. TLibs still downloads separately
from its public versioned release.
