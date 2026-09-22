#!/usr/bin/env bash
set -euo pipefail
: "${GH_TOKEN:?Set DEPS_TOKEN with Contents read access to TF-Minecraft/ServerAssets}"
ref=6a368f76c89e856fa73299d17e2a40c5a1e2f20a
mkdir -p libs
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/27e9906f1377/VotingPlugin-7.0.jar?ref=$ref" > "libs/VotingPlugin-7.0.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/14850d745437/MMOCore-1.13.1.jar?ref=$ref" > "libs/MMOCore-1.13.1.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/8ff714bd3f48/MMOItems-6.10.1-20250521.175300-22.jar?ref=$ref" > "libs/MMOItems-6.10.1.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/a3f86a50d382/MythicLib-dist-1.7.1.jar?ref=$ref" > "libs/MythicLib-dist-1.7.1.jar"
bash .github/scripts/install-local-dependencies.sh "$@"
