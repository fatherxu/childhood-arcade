#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build the featured / extended game packs and the in-app catalog.

The source trees are the folder-import layouts produced by the ROM organizer:

    <root>/<PACK>/pack.json
    <root>/<PACK>/games/*.nes|*.md|*.sfc
    <root>/<PACK>/covers/*.jpg

Each output pack is a zip that the Android client can unpack and hand to the
existing folder importer (pack.json + games/ + covers/).  Packs are split by
platform + genre and capped near the requested size so they can be downloaded
one at a time from a GitHub release.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import time
import zipfile
from collections import OrderedDict

PLATFORMS = ("FC", "MD", "SFC")
PLATFORM_ORDER = {name: index for index, name in enumerate(PLATFORMS)}
GENRE_ORDER = (
    "动作", "角色扮演", "格斗", "射击", "策略", "益智",
    "体育", "竞速", "冒险", "桌面", "棋牌", "其他",
)
GENRE_SLUG = {
    "动作": "action",
    "角色扮演": "rpg",
    "格斗": "fighting",
    "射击": "shooter",
    "策略": "strategy",
    "益智": "puzzle",
    "体育": "sports",
    "竞速": "racing",
    "冒险": "adventure",
    "桌面": "table",
    "棋牌": "board",
    "其他": "misc",
}
GENRE_LABEL = {"其他": "综合"}
ROM_EXT = {"nes", "gba", "md", "sfc", "smc", "zip"}
IMAGE_EXT = {"jpg", "jpeg", "png", "webp"}
ZIP_DATE = (2020, 1, 1, 0, 0, 0)
MB = 1024 * 1024


class Item(object):
    __slots__ = (
        "tier", "platform", "genre", "group", "title", "aliases", "players",
        "version", "file_rel", "src", "size", "cover_rel", "cover_src",
        "cover_size", "cover_hash", "cover_ext",
    )

    def __init__(self, **kwargs):
        for key in self.__slots__:
            setattr(self, key, kwargs.get(key))


def log(message):
    print(message, flush=True)


def safe_join(base, relative):
    if not relative:
        return None
    path = os.path.abspath(os.path.join(base, relative.replace("/", os.sep)))
    root = os.path.abspath(base) + os.sep
    return path if path.startswith(root) else None


def read_manifest(path):
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
        games = data.get("games") if isinstance(data, dict) else None
        return games if isinstance(games, list) else []
    except Exception as error:
        log("  ! cannot read %s: %s" % (path, error))
        return []


def add_items(items, base, platform, tier, cover_cache):
    for game in read_manifest(os.path.join(base, "pack.json")):
        if not isinstance(game, dict):
            continue
        file_rel = str(game.get("file") or "").replace("\\", "/")
        source = safe_join(base, file_rel)
        if not source or not os.path.isfile(source):
            continue
        extension = os.path.splitext(source)[1].lower().lstrip(".")
        if extension not in ROM_EXT:
            continue
        genre = str(game.get("genre") or "").strip() or "其他"
        title = str(game.get("title") or "").strip()
        group = str(game.get("group") or "").strip()
        if not group:
            group = "g-" + hashlib.sha1((platform + "|" + file_rel).encode("utf-8")).hexdigest()[:16]
        cover_rel = str(game.get("cover") or "").replace("\\", "/")
        cover_src = safe_join(base, cover_rel)
        cover_size = 0
        cover_hash = ""
        cover_ext = ""
        if cover_src and os.path.isfile(cover_src):
            cover_size = os.path.getsize(cover_src)
            cover_ext = os.path.splitext(cover_src)[1].lower().lstrip(".")
            if cover_ext not in IMAGE_EXT or cover_size <= 0 or cover_size > 128 * 1024:
                cover_src = None
                cover_size = 0
                cover_ext = ""
        if cover_src:
            if cover_src not in cover_cache:
                digest = hashlib.sha1()
                with open(cover_src, "rb") as handle:
                    while True:
                        block = handle.read(1024 * 1024)
                        if not block:
                            break
                        digest.update(block)
                cover_cache[cover_src] = digest.hexdigest()
            cover_hash = cover_cache[cover_src]
        size = os.path.getsize(source)
        if size <= 0 or size > 256 * MB:
            continue
        items.append(Item(
            tier=tier,
            platform=platform,
            genre=genre,
            group=group,
            title=title,
            aliases=str(game.get("aliases") or "").strip(),
            players=str(game.get("players") or "").strip(),
            version=str(game.get("version") or "").strip(),
            file_rel=file_rel,
            src=source,
            size=size,
            cover_rel=cover_rel,
            cover_src=cover_src,
            cover_size=cover_size,
            cover_hash=cover_hash,
            cover_ext=cover_ext,
        ))


def load_sources(featured_root, extended_root):
    cover_cache = {}
    featured = []
    for platform in PLATFORMS:
        base = os.path.join(featured_root, platform)
        if not os.path.isfile(os.path.join(base, "pack.json")):
            log("  ! skip missing featured pack: %s" % base)
            continue
        add_items(featured, base, platform, "featured", cover_cache)
    extended = []
    if os.path.isdir(extended_root):
        for chunk in sorted(os.listdir(extended_root)):
            base = os.path.join(extended_root, chunk)
            if not os.path.isfile(os.path.join(base, "pack.json")):
                continue
            platform = chunk.split("-")[0]
            if platform not in PLATFORMS:
                continue
            add_items(extended, base, platform, "extended", cover_cache)
    return featured, extended


def genre_rank(genre):
    try:
        return GENRE_ORDER.index(genre)
    except ValueError:
        return len(GENRE_ORDER)


def build_buckets(featured, extended):
    buckets = OrderedDict()
    for item in featured:
        key = ("featured", "", item.genre)
        buckets.setdefault(key, []).append(item)
    for item in extended:
        key = ("extended", item.platform, item.genre)
        buckets.setdefault(key, []).append(item)
    ordered = sorted(
        buckets.items(),
        key=lambda entry: (
            0 if entry[0][0] == "featured" else 1,
            PLATFORM_ORDER.get(entry[0][1], 99),
            genre_rank(entry[0][2]),
            entry[0][2],
        ),
    )
    return ordered


def group_items(items):
    grouped = OrderedDict()
    for item in sorted(items, key=lambda x: (x.group, x.title, x.file_rel)):
        key = (item.platform, item.group)
        grouped.setdefault(key, []).append(item)
    return list(grouped.values())


def plan_chunks(groups, target_bytes, max_games):
    chunks = []
    current = []
    current_bytes = 0
    current_count = 0
    for group in groups:
        group_bytes = sum(item.size + item.cover_size for item in group)
        if current and (current_bytes + group_bytes > target_bytes
                        or current_count + len(group) > max_games):
            chunks.append(current)
            current = []
            current_bytes = 0
            current_count = 0
        current.extend(group)
        current_bytes += group_bytes
        current_count += len(group)
    if current:
        chunks.append(current)
    return chunks


def genre_slug(genre):
    return GENRE_SLUG.get(genre) or re.sub(r"[^a-z0-9]+", "-", genre.lower()).strip("-") or "misc"


def add_zip_file(archive, source, arcname):
    info = zipfile.ZipInfo(arcname, ZIP_DATE)
    info.external_attr = 0o644 << 16
    info.compress_type = zipfile.ZIP_DEFLATED
    with open(source, "rb") as reader, archive.open(info, "w") as writer:
        shutil.copyfileobj(reader, writer, 1024 * 1024)


def write_pack(path, pack_id, name, tier, platform, platforms, genre, games):
    covers = OrderedDict()
    manifest_games = []
    for item in games:
        entry = OrderedDict()
        entry["title"] = item.title
        if item.aliases:
            entry["aliases"] = item.aliases
        entry["genre"] = item.genre
        if item.players:
            entry["players"] = item.players
        if item.version:
            entry["version"] = item.version
        entry["group"] = item.group
        entry["file"] = item.file_rel
        if item.cover_hash:
            arcname = "covers/pc-%s.%s" % (item.cover_hash[:16], item.cover_ext)
            covers.setdefault(item.cover_hash, (arcname, item.cover_src, item.cover_size))
            entry["cover"] = arcname
        # Keep the original metadata fields when they are useful to the client.
        manifest_games.append(entry)
    manifest = OrderedDict()
    manifest["schema"] = 1
    manifest["id"] = pack_id
    manifest["name"] = name
    manifest["tier"] = tier
    manifest["platform"] = platform
    manifest["platforms"] = platforms
    manifest["genre"] = genre
    manifest["count"] = len(games)
    manifest["games"] = manifest_games
    payload = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    seen = set()
    for item in games:
        if item.file_rel in seen:
            raise RuntimeError("duplicate rom path in pack %s: %s" % (pack_id, item.file_rel))
        seen.add(item.file_rel)
    for arcname, source, _ in covers.values():
        if arcname in seen:
            raise RuntimeError("duplicate cover path in pack %s: %s" % (pack_id, arcname))
        seen.add(arcname)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as archive:
        info = zipfile.ZipInfo("pack.json", ZIP_DATE)
        info.external_attr = 0o644 << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, payload)
        for item in games:
            add_zip_file(archive, item.src, item.file_rel)
        for arcname, source, _ in covers.values():
            add_zip_file(archive, source, arcname)
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while True:
            block = handle.read(1024 * 1024)
            if not block:
                break
            digest.update(block)
    return {
        "size": os.path.getsize(path),
        "sha256": digest.hexdigest(),
        "manifest_bytes": len(payload),
        "extract_bytes": len(payload) + sum(item.size for item in games)
        + sum(cover[2] for cover in covers.values()),
        "rom_bytes": sum(item.size for item in games),
        "count": len(games),
        "cover_count": len(covers),
    }


def pack_prefix(tier, platform, genre):
    if tier == "featured":
        return "featured-" + genre_slug(genre)
    return "ext-%s-%s" % (platform.lower(), genre_slug(genre))


def pack_display_name(tier, platform, genre, part, parts):
    label = GENRE_LABEL.get(genre, genre)
    if tier == "featured":
        base = "精选包 · %s" % label
    else:
        base = "扩展包 · %s · %s" % (platform, label)
    if parts > 1:
        base += " %02d" % part
    return base


def build_bucket(tier, platform, genre, items, out_dir, target_bytes, limit_bytes, max_games):
    groups = group_items(items)
    target = target_bytes
    while True:
        chunks = plan_chunks(groups, target, max_games)
        parts = len(chunks)
        prefix = pack_prefix(tier, platform, genre)
        results = []
        oversized = False
        written = []
        for index, chunk in enumerate(chunks, 1):
            pack_id = prefix if parts == 1 else "%s-%02d" % (prefix, index)
            name = pack_display_name(tier, platform, genre, index, parts)
            platforms = sorted(
                {item.platform for item in chunk}, key=lambda p: PLATFORM_ORDER.get(p, 99))
            pack_platform = platform if tier == "extended" else "/".join(platforms)
            asset = "pack-%s.zip" % pack_id
            path = os.path.join(out_dir, asset)
            info = write_pack(
                path, pack_id, name, tier, pack_platform, platforms, genre, chunk,
            )
            written.append(path)
            if info["size"] > limit_bytes and len(chunk) > 1:
                log("  ! %s is %.1f MB > %.1f MB, re-planning"
                    % (pack_id, info["size"] / MB, limit_bytes / MB))
                oversized = True
                break
            payload = OrderedDict()
            payload["id"] = pack_id
            payload["tier"] = tier
            payload["name"] = name
            payload["platform"] = pack_platform
            payload["platforms"] = platforms
            payload["genre"] = genre
            payload["part"] = index
            payload["parts"] = parts
            payload["count"] = info["count"]
            payload["size"] = info["size"]
            payload["romBytes"] = info["rom_bytes"]
            payload["extractBytes"] = info["extract_bytes"]
            payload["sha256"] = info["sha256"]
            payload["asset"] = asset
            payload["url"] = None  # filled by the caller
            results.append(payload)
            log("  built %-32s %5d games %7.1f MB  %s"
                % (asset, info["count"], info["size"] / MB, payload["sha256"][:12]))
        if not oversized:
            return results
        for path in written:
            try:
                os.remove(path)
            except OSError:
                pass
        target = max(160 * MB, int(target * 0.85))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--featured", default=r"D:\project\game rom\游戏厅包-横版")
    parser.add_argument("--extended", default=r"D:\project\game rom\游戏厅包\全集-横版")
    parser.add_argument("--out", default=r"D:\project\game rom\_packs-output")
    parser.add_argument("--catalog-out",
                        default=r"d:\project\android\childhood-arcade\source\app\src\main\assets\pack-catalog.json")
    parser.add_argument("--base-url",
                        default="https://github.com/fatherxu/childhood-arcade/releases/download/v1.1.0/")
    parser.add_argument("--version", default="2026.11.25.1")
    parser.add_argument("--min-version-code", type=int, default=22)
    parser.add_argument("--target-mb", type=float, default=275)
    parser.add_argument("--limit-mb", type=float, default=300)
    parser.add_argument("--max-games", type=int, default=1500)
    parser.add_argument("--plan-only", action="store_true")
    parser.add_argument("--tier", choices=("featured", "extended", "all"), default="all")
    args = parser.parse_args()

    if not os.path.isdir(args.featured):
        log("featured root not found: %s" % args.featured)
        return 2
    if not os.path.isdir(args.extended):
        log("extended root not found: %s" % args.extended)
        return 2
    os.makedirs(args.out, exist_ok=True)
    pack_dir = os.path.join(args.out, "packs")
    os.makedirs(pack_dir, exist_ok=True)

    log("loading source packs ...")
    featured, extended = load_sources(args.featured, args.extended)
    log("featured games: %d (%.1f MB), extended games: %d (%.1f MB)"
        % (len(featured), sum(i.size for i in featured) / MB,
           len(extended), sum(i.size for i in extended) / MB))

    buckets = build_buckets(featured, extended)
    if args.tier != "all":
        buckets = [entry for entry in buckets if entry[0][0] == args.tier]

    target = int(args.target_mb * MB)
    limit = int(args.limit_mb * MB)
    plan = []
    for (tier, platform, genre), items in buckets:
        groups = group_items(items)
        chunks = plan_chunks(groups, target, args.max_games)
        plan.append({
            "tier": tier,
            "platform": platform or "mixed",
            "genre": genre,
            "games": len(items),
            "bytes": sum(item.size + item.cover_size for item in items),
            "parts": len(chunks),
            "largest_part_games": max(len(chunk) for chunk in chunks),
        })
    with open(os.path.join(args.out, "plan.json"), "w", encoding="utf-8") as handle:
        json.dump(plan, handle, ensure_ascii=False, indent=1)
    log("planned %d buckets, %d parts"
        % (len(plan), sum(entry["parts"] for entry in plan)))
    for entry in plan:
        log("  %-8s %-4s %-6s %5d games %8.1f MB -> %d pack(s)"
            % (entry["tier"], entry["platform"], entry["genre"], entry["games"],
               entry["bytes"] / MB, entry["parts"]))
    if args.plan_only:
        return 0

    packs = []
    for (tier, platform, genre), items in buckets:
        log("packing %s / %s / %s (%d games)" % (tier, platform or "-", genre, len(items)))
        packs.extend(build_bucket(
            tier, platform, genre, items, pack_dir, target, limit, args.max_games))

    featured_packs = [pack for pack in packs if pack["tier"] == "featured"]
    extended_packs = [pack for pack in packs if pack["tier"] == "extended"]
    for pack in packs:
        pack["url"] = args.base_url + pack["asset"]
    catalog = OrderedDict()
    catalog["schema"] = 1
    catalog["version"] = args.version
    catalog["generatedAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    catalog["minVersionCode"] = args.min_version_code
    catalog["baseUrl"] = args.base_url
    catalog["tiers"] = [
        {
            "id": "featured",
            "name": "精选包",
            "note": "精选 %d 款，按游戏类别打包，适合先装" % sum(p["count"] for p in featured_packs),
        },
        {
            "id": "extended",
            "name": "扩展包",
            "note": "全集横版 %d 个版本，按平台和类别拆分，可单独下载"
                    % sum(p["count"] for p in extended_packs),
        },
    ]
    catalog["stats"] = {
        "featuredGames": sum(p["count"] for p in featured_packs),
        "extendedGames": sum(p["count"] for p in extended_packs),
        "featuredBytes": sum(p["size"] for p in featured_packs),
        "extendedBytes": sum(p["size"] for p in extended_packs),
        "packCount": len(packs),
    }
    catalog["packs"] = packs
    catalog_path = os.path.join(args.out, "packs.json")
    with open(catalog_path, "w", encoding="utf-8") as handle:
        json.dump(catalog, handle, ensure_ascii=False, indent=1)
    if args.catalog_out:
        os.makedirs(os.path.dirname(args.catalog_out), exist_ok=True)
        shutil.copyfile(catalog_path, args.catalog_out)
    log("catalog written: %s" % catalog_path)
    log("pack count: %d, featured %.1f MB, extended %.1f MB"
        % (len(packs), catalog["stats"]["featuredBytes"] / MB,
           catalog["stats"]["extendedBytes"] / MB))
    return 0


if __name__ == "__main__":
    sys.exit(main())
