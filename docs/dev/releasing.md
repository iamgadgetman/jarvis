# Releasing

One version, three files, one workflow. `release.yml` builds the Paper
plugin, the Fabric mod and the NeoForge mod, checks that each jar carries
the version being released, publishes a GitHub release with the matching
CHANGELOG section as its notes, and then, where the secrets exist, uploads
the files to Modrinth and CurseForge.

## Cutting a release

1. Set the version in `pom.xml` (and the three module poms, which inherit
   it by number), `jarvis-fabric/gradle.properties` and
   `jarvis-neoforge/gradle.properties`, and add the `## vX.Y.Z` section to
   `CHANGELOG.md`. The workflow refuses to release if the jars and the
   version disagree.
2. Merge to `main`.
3. Either push the tag (`git tag -a vX.Y.Z -m "..." && git push origin vX.Y.Z`)
   or open Actions, pick **release**, **Run workflow** on `main`, and type
   the version. The second creates the tag itself on the current commit,
   for anyone whose credentials cannot push tags.

Re-running the workflow for a version that is already released updates
the GitHub release's assets in place and skips Modrinth versions that
exist. CurseForge has no check by name, so a re-run uploads its files
again; delete the duplicates on the site.

## Modrinth

One project, all three files. Modrinth lists a project under both plugins
and mods when its versions carry both kinds of loader, which is how Simple
Voice Chat is published. Version numbers are `X.Y.Z+paper`, `+fabric` and
`+neoforge`, because Modrinth wants them unique within a project.

Secrets:

| Secret | Where it comes from |
|---|---|
| `MODRINTH_ID` | The project page, bottom of the sidebar: **Project ID** (a short string, not the slug) |
| `MODRINTH_TOKEN` | Your Modrinth settings, **PATs**, a token with the **Create versions** and **Read projects** scopes and an expiry you will remember |

The page text lives in `docs/modrinth.md`; it is not uploaded by the
workflow.

## CurseForge

Two projects, because CurseForge keeps Bukkit plugins on
[dev.bukkit.org](https://dev.bukkit.org) and mods on
[minecraft.curseforge.com](https://www.curseforge.com/minecraft), with
separate upload endpoints. Create a Bukkit plugin project for the Paper
jar and a Minecraft mod project for the Fabric and NeoForge jars; the mod
project is marked Fabric and NeoForge, Java 25, server side.

Secrets:

| Secret | Where it comes from |
|---|---|
| `CURSEFORGE_TOKEN` | Your CurseForge account, **API Tokens** (legacy.curseforge.com/account/api-tokens). One token serves both sites |
| `CURSEFORGE_PLUGIN_ID` | The Bukkit project's page, **About Project**, the numeric **Project ID** |
| `CURSEFORGE_MOD_ID` | The mod project's page, the same place |

Game versions are matched against each site's own list at run time by
range (`1.21.11..26.2` for the plugin, `26.3` for the mods), so a new
Minecraft version needs no change here as long as the range still says
what is supported. A version the site does not list yet makes the step
fail with the list it does have.

## The uploader

`.github/scripts/publish.py` does the talking, one file per call, and has
a `--dry-run` that prints what it would send. `python3 -m unittest` in
that folder runs its tests. It is plain Python with no dependencies, so
the same command works from a laptop with the tokens in the environment,
which is how to publish an old release by hand.
