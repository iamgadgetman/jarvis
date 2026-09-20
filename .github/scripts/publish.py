#!/usr/bin/env python3
"""Upload a release file to Modrinth or CurseForge.

One file, one platform, one call; the release workflow makes six. Written
against the two upload APIs directly rather than through a publishing
action, because the actions that exist know the mod loaders and nothing
else: none of them can say "paper" or "purpur", and none can reach
dev.bukkit.org, where CurseForge keeps Bukkit plugins.

    publish.py modrinth   --project ID --file JAR --version 0.17.0+paper \
        --name "Jarvis 0.17.0 for Paper" --loaders paper purpur \
        --game-versions 1.21.11..26.2 --deps simple-voice-chat:optional \
        --changelog notes.md

    publish.py curseforge --host dev.bukkit.org --project ID --file JAR \
        --name "Jarvis 0.17.0 for Paper" --game-versions 1.21.11..26.2 \
        --changelog notes.md

    publish.py curseforge --host minecraft.curseforge.com --project ID \
        --file JAR --name "Jarvis 0.17.0 for Fabric" --game-versions 26.3 \
        --extra Fabric "Java 25" Server --deps fabric-api:requiredDependency \
        --changelog notes.md

Tokens come from MODRINTH_TOKEN and CURSEFORGE_TOKEN. --game-versions is
one version or a closed range "low..high", expanded against the platform's
own list of release versions at run time, so a new game version needs no
change here. --dry-run prints what would be sent and touches no network.

A Modrinth version number that already exists is skipped, so a re-run of
the release is safe there; CurseForge offers no such check, and a re-run
uploads the file again.
"""

import argparse
import json
import mimetypes
import os
import re
import sys
import urllib.error
import urllib.request
import uuid

USER_AGENT = "iamgadgetman/jarvis release workflow (github.com/iamgadgetman/jarvis)"
MODRINTH = "https://api.modrinth.com/v2"


# ---------------------------------------------------------------- versions

def parse_version(name):
    """'1.21.11' -> (1, 21, 11); None for anything that is not dotted numbers."""
    if not re.fullmatch(r"\d+(\.\d+)*", name or ""):
        return None
    return tuple(int(p) for p in name.split("."))


def parse_range(spec):
    """'26.3' -> (26,3),(26,3); '1.21.11..26.2' -> (1,21,11),(26,2)."""
    if ".." in spec:
        low, high = spec.split("..", 1)
    else:
        low = high = spec
    lo, hi = parse_version(low.strip()), parse_version(high.strip())
    if lo is None or hi is None:
        raise SystemExit(f"--game-versions wants a version or low..high, not '{spec}'")
    return lo, hi


def in_range(name, lo, hi):
    """Whether a platform's version name falls inside low..high.

    A name with fewer parts than the bounds ('1.21' beside '1.21.11', which
    is how dev.bukkit.org lists them) counts when it is the prefix of a bound
    or lies strictly between them.
    """
    v = parse_version(name)
    if v is None:
        return False
    if lo[:len(v)] == v or hi[:len(v)] == v:
        return True
    return lo <= v <= hi


def select_versions(names, spec):
    lo, hi = parse_range(spec)
    chosen = [n for n in names if in_range(n, lo, hi)]
    if not chosen:
        raise SystemExit(f"no game version on the platform falls in {spec}; it lists: {', '.join(names[:40])}")
    return chosen


# ---------------------------------------------------------------- http

def request(method, url, headers, body=None, content_type=None):
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("User-Agent", USER_AGENT)
    for k, v in headers.items():
        req.add_header(k, v)
    if content_type:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=300) as res:
            raw = res.read()
            return res.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        raise SystemExit(f"{method} {url} -> HTTP {e.code}: {detail[:2000]}")


def multipart(fields, file_field, path):
    """fields: {name: str}; the file goes last under file_field."""
    boundary = "jarvis-" + uuid.uuid4().hex
    out = bytearray()
    for name, value in fields.items():
        out += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n"
                f"Content-Type: application/json\r\n\r\n{value}\r\n").encode()
    filename = os.path.basename(path)
    ctype = mimetypes.guess_type(filename)[0] or "application/java-archive"
    out += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{file_field}\"; filename=\"{filename}\"\r\n"
            f"Content-Type: {ctype}\r\n\r\n").encode()
    with open(path, "rb") as f:
        out += f.read()
    out += f"\r\n--{boundary}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={boundary}"


def read_changelog(path):
    with open(path, encoding="utf-8") as f:
        return f.read().strip()


def parse_deps(specs, allowed):
    deps = []
    for spec in specs or []:
        if ":" not in spec:
            raise SystemExit(f"--deps wants slug:kind, not '{spec}'")
        slug, kind = spec.rsplit(":", 1)
        if kind not in allowed:
            raise SystemExit(f"dependency kind '{kind}' is not one of {', '.join(allowed)}")
        deps.append((slug, kind))
    return deps


# ---------------------------------------------------------------- modrinth

MODRINTH_DEP_KINDS = ("required", "optional", "incompatible", "embedded")


def modrinth(args):
    token = os.environ.get("MODRINTH_TOKEN", "")
    if not token and not args.dry_run:
        raise SystemExit("MODRINTH_TOKEN is not set")
    headers = {"Authorization": token}
    deps = parse_deps(args.deps, MODRINTH_DEP_KINDS)

    if args.dry_run:
        game_versions = [args.game_versions]
        dep_list = [{"project_id": f"<id of {slug}>", "dependency_type": kind} for slug, kind in deps]
    else:
        _, existing = request("GET", f"{MODRINTH}/project/{args.project}/version", headers)
        if any(v.get("version_number") == args.version for v in existing):
            print(f"Modrinth already has version {args.version}; nothing to do")
            return
        _, tags = request("GET", f"{MODRINTH}/tag/game_version", {})
        releases = [t["version"] for t in tags if t.get("version_type") == "release"]
        game_versions = select_versions(releases, args.game_versions)
        dep_list = []
        for slug, kind in deps:
            _, project = request("GET", f"{MODRINTH}/project/{slug}", {})
            dep_list.append({"project_id": project["id"], "dependency_type": kind})

    data = {
        "name": args.name,
        "version_number": args.version,
        "changelog": read_changelog(args.changelog) if args.changelog else "",
        "dependencies": dep_list,
        "game_versions": game_versions,
        "version_type": args.release_type,
        "loaders": args.loaders,
        "featured": False,
        "project_id": args.project,
        "file_parts": ["file"],
        "primary_file": "file",
    }
    print("Modrinth version:", json.dumps({k: v for k, v in data.items() if k != "changelog"}, indent=2))
    if args.dry_run:
        return
    body, ctype = multipart({"data": json.dumps(data)}, "file", args.file)
    _, made = request("POST", f"{MODRINTH}/version", headers, body, ctype)
    print(f"Modrinth: uploaded {os.path.basename(args.file)} as version {made.get('id')}")


# ---------------------------------------------------------------- curseforge

CURSEFORGE_DEP_KINDS = ("requiredDependency", "optionalDependency", "embeddedLibrary", "incompatible", "tool")


def curseforge(args):
    token = os.environ.get("CURSEFORGE_TOKEN", "")
    if not token and not args.dry_run:
        raise SystemExit("CURSEFORGE_TOKEN is not set")
    headers = {"X-Api-Token": token}
    base = f"https://{args.host}/api"
    deps = parse_deps(args.deps, CURSEFORGE_DEP_KINDS)

    if args.dry_run:
        ids = [f"<id of {args.game_versions}>"] + [f"<id of {x}>" for x in args.extra or []]
    else:
        _, versions = request("GET", f"{base}/game/versions", headers)
        by_name = {}
        for v in versions:
            by_name.setdefault(v["name"], v["id"])
        chosen = select_versions([n for n in by_name if parse_version(n)], args.game_versions)
        ids = [by_name[n] for n in chosen]
        for extra in args.extra or []:
            if extra not in by_name:
                near = [n for n in by_name if extra.split()[0].lower() in n.lower()]
                raise SystemExit(f"{args.host} has no game version named '{extra}'; nearest: {', '.join(near[:20])}")
            ids.append(by_name[extra])
        print(f"{args.host}: game versions {chosen} + {args.extra or []}")

    metadata = {
        "changelog": read_changelog(args.changelog) if args.changelog else "",
        "changelogType": "markdown",
        "displayName": args.name,
        "gameVersions": ids,
        "releaseType": args.release_type,
    }
    if deps:
        metadata["relations"] = {"projects": [{"slug": slug, "type": kind} for slug, kind in deps]}
    print(f"{args.host} upload:", json.dumps({k: v for k, v in metadata.items() if k != "changelog"}, indent=2))
    if args.dry_run:
        return
    body, ctype = multipart({"metadata": json.dumps(metadata)}, "file", args.file)
    _, made = request("POST", f"{base}/projects/{args.project}/upload-file", headers, body, ctype)
    print(f"{args.host}: uploaded {os.path.basename(args.file)} as file {made.get('id')}")


# ---------------------------------------------------------------- main

def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="platform", required=True)

    def common(sp):
        sp.add_argument("--project", required=True, help="project id on the platform")
        sp.add_argument("--file", required=True, help="the jar to upload")
        sp.add_argument("--name", required=True, help="display name of the version")
        sp.add_argument("--game-versions", required=True, help="one version, or low..high")
        sp.add_argument("--changelog", help="markdown file for the version's changelog")
        sp.add_argument("--release-type", default="release", choices=("release", "beta", "alpha"))
        sp.add_argument("--deps", nargs="*", help="slug:kind, per platform")
        sp.add_argument("--dry-run", action="store_true", help="print, send nothing")

    m = sub.add_parser("modrinth")
    common(m)
    m.add_argument("--version", required=True, help="version number, unique per project (0.17.0+paper)")
    m.add_argument("--loaders", nargs="+", required=True, help="paper purpur | fabric | neoforge")

    c = sub.add_parser("curseforge")
    common(c)
    c.add_argument("--host", required=True, choices=("minecraft.curseforge.com", "dev.bukkit.org"))
    c.add_argument("--extra", nargs="*", help="other game-version entries by name: Fabric, NeoForge, 'Java 25', Server")

    args = p.parse_args(argv)
    if not args.dry_run and not os.path.isfile(args.file):
        raise SystemExit(f"no such file: {args.file}")
    (modrinth if args.platform == "modrinth" else curseforge)(args)


if __name__ == "__main__":
    main()
