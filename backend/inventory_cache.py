import json
import time
from pathlib import Path

from release_metadata_cache import (
    get_release_metadata
)
from discogs_client import fetch_all_inventory


CACHE_FILE = (
    Path(__file__).resolve().parent
    / "inventory_cache.json"
)


_inventory_cache = []
_last_sync_time = 0


def normalize_listing(listing):
    release = listing.get("release") or {}
    price = listing.get("price") or {}

    description = release.get("description", "")

    artist = release.get("artist") or ""
    title = release.get("title") or ""

    if " - " in description:
        parts = description.split(" - ", 1)

        if not artist:
            artist = parts[0]

        if not title:
            title = parts[1]

    return {
        "listingId": listing.get("id"),
        "releaseId": release.get("id", 0),

        "artist": artist or "Unknown Artist",
        "title": title or description or "Unknown Title",

        "year": None,
        "label": None,
        "catalogNumber": None,
        "format": None,

        "condition": listing.get("condition"),
        "sleeveCondition": listing.get(
            "sleeve_condition"
        ),

        # Preserve the seller's existing Discogs listing comments
        # so the Android edit dialog can safely display and resave them.
        "comments": listing.get("comments") or "",

        "price": price.get("value"),
        "currency": price.get("currency"),

        "marketValue": None,
        "recommendedPrice": None,

        "thumbnail": release.get("thumbnail"),

        "reason": None
    }


def load_cache():
    global _inventory_cache
    global _last_sync_time

    if not CACHE_FILE.exists():
        _inventory_cache = []
        _last_sync_time = 0
        return

    with open(
        CACHE_FILE,
        "r",
        encoding="utf-8"
    ) as file:
        _inventory_cache = json.load(file)

    _last_sync_time = CACHE_FILE.stat().st_mtime


def ensure_cache_current():
    # FastAPI and the MCP server run in separate Python processes.
    # Reload the small local JSON cache before every AI inventory
    # search so the MCP process always sees edits/deletions written
    # by the FastAPI process.
    if not CACHE_FILE.exists():
        return

    load_cache()


async def refresh_cache():
    global _inventory_cache
    global _last_sync_time

    raw_listings = await fetch_all_inventory()

    normalized = [
        normalize_listing(listing)
        for listing in raw_listings
    ]

    with open(
        CACHE_FILE,
        "w",
        encoding="utf-8"
    ) as file:
        json.dump(
            normalized,
            file,
            ensure_ascii=False
        )

    _inventory_cache = normalized
    _last_sync_time = time.time()

    print(
        f"Cached {len(_inventory_cache)} listings"
    )

    return len(_inventory_cache)


def get_cache_count():
    return len(_inventory_cache)


def get_release_ids():
    release_ids = {
        listing.get("releaseId")
        for listing in _inventory_cache
        if listing.get("releaseId")
    }

    return sorted(release_ids)


def remove_listings_from_cache(
    listing_ids
):
    """
    Remove deleted Discogs listings from the in-memory cache
    and inventory_cache.json immediately.
    """
    global _inventory_cache
    global _last_sync_time

    ids_to_remove = {
        int(listing_id)
        for listing_id in listing_ids
        if listing_id is not None
    }

    if not ids_to_remove:
        return 0

    before_count = len(_inventory_cache)

    _inventory_cache = [
        listing
        for listing in _inventory_cache
        if (
            int(listing.get("listingId"))
            if listing.get("listingId") is not None
            else -1
        ) not in ids_to_remove
    ]

    removed_count = (
        before_count
        - len(_inventory_cache)
    )

    if removed_count > 0:
        with open(
            CACHE_FILE,
            "w",
            encoding="utf-8"
        ) as file:
            json.dump(
                _inventory_cache,
                file,
                ensure_ascii=False
            )

        _last_sync_time = (
            CACHE_FILE.stat().st_mtime
        )

        print(
            f"Removed {removed_count} deleted "
            f"listing(s) from inventory cache"
        )

    return removed_count


def search_inventory(
    artist: str | None = None,
    title: str | None = None,

    genre: str | None = None,
    style: str | None = None,
    label: str | None = None,

    year: int | None = None,
    min_year: int | None = None,
    max_year: int | None = None,

    format_name: str | None = None,
    catalog_number: str | None = None,

    min_price: float | None = None,
    max_price: float | None = None,

    sort_by: str | None = None,
    sort_order: str = "asc",

    limit: int = 100
):
    ensure_cache_current()

    matches = []

    def contains(
        values,
        query
    ):
        if not query:
            return True

        query = query.casefold()

        return any(
            query in str(value).casefold()
            for value in values
        )

    for listing in _inventory_cache:

        listing_artist = (
            listing.get("artist") or ""
        )

        listing_title = (
            listing.get("title") or ""
        )

        price = listing.get("price")

        release_id = listing.get(
            "releaseId"
        )

        metadata = (
            get_release_metadata(
                release_id
            )
            if release_id
            else None
        ) or {}

        genres = (
            metadata.get("genres") or []
        )

        styles = (
            metadata.get("styles") or []
        )

        labels = (
            metadata.get("labels") or []
        )

        catalog_numbers = (
            metadata.get(
                "catalogNumbers"
            ) or []
        )

        release_year = metadata.get(
            "year"
        )

        release_format = (
            metadata.get("format")
        )

        if artist:
            if (
                artist.casefold()
                not in
                listing_artist.casefold()
            ):
                continue

        if title:
            if (
                title.casefold()
                not in
                listing_title.casefold()
            ):
                continue

        if genre:
            if not contains(
                genres,
                genre
            ):
                continue

        if style:
            if not contains(
                styles,
                style
            ):
                continue

        if label:
            if not contains(
                labels,
                label
            ):
                continue

        if year is not None:
            if release_year != year:
                continue

        if min_year is not None:
            if (
                release_year is None
                or release_year < min_year
            ):
                continue

        if max_year is not None:
            if (
                release_year is None
                or release_year > max_year
            ):
                continue

        if format_name:
            if (
                release_format is None
                or format_name.casefold()
                not in release_format.casefold()
            ):
                continue

        if catalog_number:
            if not contains(
                catalog_numbers,
                catalog_number
            ):
                continue

        if min_price is not None:
            if (
                price is None
                or price < min_price
            ):
                continue

        if max_price is not None:
            if (
                price is None
                or price > max_price
            ):
                continue

        result = dict(listing)

        result["year"] = (
            release_year
        )

        result["label"] = (
            labels[0]
            if labels
            else None
        )

        result["catalogNumber"] = (
            catalog_numbers[0]
            if catalog_numbers
            else None
        )

        result["format"] = (
            release_format
        )

        matches.append(result)

    if sort_by == "price":

        reverse = (
            sort_order == "desc"
        )

        matches.sort(
            key=lambda item: (
                item.get("price")
                if item.get("price")
                is not None
                else (
                    float("-inf")
                    if reverse
                    else float("inf")
                )
            ),
            reverse=reverse
        )

    elif sort_by == "year":

        reverse = (
            sort_order == "desc"
        )

        matches.sort(
            key=lambda item: (
                item.get("year") or 0
            ),
            reverse=reverse
        )

    safe_limit = max(
        1,
        min(limit, 100)
    )

    return matches[:safe_limit]


def get_cache_age_seconds():
    if _last_sync_time == 0:
        return None

    return time.time() - _last_sync_time


def cache_needs_refresh(
    max_age_seconds=900
):
    age = get_cache_age_seconds()

    if age is None:
        return True

    return age > max_age_seconds


load_cache()
