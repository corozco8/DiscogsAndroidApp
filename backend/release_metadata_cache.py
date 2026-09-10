import asyncio
import json
import sys
import time

from pathlib import Path

import httpx

from discogs_client import (
    DISCOGS_API_URL,
    HEADERS
)


METADATA_FILE = (
    Path(__file__).resolve().parent
    / "release_metadata_cache.json"
)

_release_metadata = {}
_metadata_file_mtime = 0.0

_sync_lock = asyncio.Lock()


def _format_formats(formats):
    formatted = []

    for item in formats or []:
        parts = []

        name = item.get("name")

        if name:
            parts.append(name)

        descriptions = (
            item.get("descriptions") or []
        )

        parts.extend(descriptions)

        if parts:
            formatted.append(
                ", ".join(parts)
            )

    if not formatted:
        return None

    return " / ".join(formatted)


def normalize_release_metadata(data):
    labels = data.get("labels") or []

    label_names = []
    catalog_numbers = []

    for label in labels:
        name = label.get("name")
        catno = label.get("catno")

        if name:
            label_names.append(name)

        if catno:
            catalog_numbers.append(catno)

    return {
        "releaseId": data.get("id"),

        "year": data.get("year"),

        "genres": data.get("genres") or [],

        "styles": data.get("styles") or [],

        "labels": label_names,

        "catalogNumbers": catalog_numbers,

        "format": _format_formats(
            data.get("formats") or []
        ),

        "fetchedAt": int(time.time())
    }


def load_release_metadata():
    global _release_metadata
    global _metadata_file_mtime

    if not METADATA_FILE.exists():
        _release_metadata = {}
        _metadata_file_mtime = 0.0
        return

    with open(
        METADATA_FILE,
        "r",
        encoding="utf-8"
    ) as file:
        _release_metadata = json.load(file)

    _metadata_file_mtime = (
        METADATA_FILE.stat().st_mtime
    )


def save_release_metadata():
    global _metadata_file_mtime

    temp_file = METADATA_FILE.with_name(
        METADATA_FILE.name + ".tmp"
    )

    with open(
        temp_file,
        "w",
        encoding="utf-8"
    ) as file:
        json.dump(
            _release_metadata,
            file,
            ensure_ascii=False
        )

    temp_file.replace(
        METADATA_FILE
    )

    _metadata_file_mtime = (
        METADATA_FILE.stat().st_mtime
    )


def ensure_release_metadata_current():
    if not METADATA_FILE.exists():
        return

    modified = (
        METADATA_FILE.stat().st_mtime
    )

    if modified > _metadata_file_mtime:
        load_release_metadata()


def get_release_metadata(
    release_id: int
):
    ensure_release_metadata_current()

    return _release_metadata.get(
        str(release_id)
    )


def get_metadata_count():
    ensure_release_metadata_current()

    return len(_release_metadata)


def get_missing_release_ids(
    release_ids
):
    ensure_release_metadata_current()

    return [
        release_id
        for release_id in release_ids
        if str(release_id)
        not in _release_metadata
    ]


async def sync_missing_release_metadata(
    release_ids,
    limit=None
):
    async with _sync_lock:

        # All releases still missing metadata
        all_missing = get_missing_release_ids(
            release_ids
        )

        total_missing_before = len(
            all_missing
        )

        if limit is not None:
            missing = all_missing[:limit]
        else:
            missing = all_missing

        if not missing:
            return 0

        print(
            f"Metadata sync starting: "
            f"{len(_release_metadata)} cached, "
            f"{total_missing_before} remaining",
            file=sys.stderr
        )

        added = 0

        async with httpx.AsyncClient(
            timeout=30.0,
            headers=HEADERS
        ) as client:

            for index, release_id in enumerate(
                missing,
                start=1
            ):
                try:
                    response = await client.get(
                        f"{DISCOGS_API_URL}"
                        f"/releases/{release_id}"
                    )

                    if response.status_code == 429:
                        retry_after = (
                            response.headers.get(
                                "Retry-After",
                                "2"
                            )
                        )

                        try:
                            delay = float(
                                retry_after
                            )
                        except ValueError:
                            delay = 2.0

                        print(
                            f"Discogs rate limit hit. "
                            f"Waiting {delay} seconds...",
                            file=sys.stderr
                        )

                        await asyncio.sleep(
                            delay
                        )

                        response = await client.get(
                            f"{DISCOGS_API_URL}"
                            f"/releases/{release_id}"
                        )

                    if response.status_code == 404:
                        _release_metadata[
                            str(release_id)
                        ] = {
                            "releaseId": release_id,
                            "unavailable": True,
                            "fetchedAt": int(
                                time.time()
                            )
                        }

                        added += 1

                    else:
                        response.raise_for_status()

                        _release_metadata[
                            str(release_id)
                        ] = (
                            normalize_release_metadata(
                                response.json()
                            )
                        )

                        added += 1

                    # Save and show progress every 10 releases
                    if added % 10 == 0:
                        save_release_metadata()

                        remaining = max(
                            0,
                            total_missing_before
                            - added
                        )

                        print(
                            f"Release metadata: "
                            f"{len(_release_metadata)} cached, "
                            f"{remaining} remaining",
                            file=sys.stderr
                        )

                    # Be gentle with Discogs
                    await asyncio.sleep(1.05)

                except httpx.HTTPStatusError as error:

                    if (
                        error.response.status_code
                        in (401, 403)
                    ):
                        raise

                    print(
                        f"Metadata error for "
                        f"release {release_id}: "
                        f"{error}",
                        file=sys.stderr
                    )

                except httpx.HTTPError as error:
                    print(
                        f"Metadata network error "
                        f"for release {release_id}: "
                        f"{error}",
                        file=sys.stderr
                    )

        # Save whatever is left after the batch
        save_release_metadata()

        remaining = max(
            0,
            total_missing_before - added
        )

        print(
            f"Metadata batch complete: "
            f"{len(_release_metadata)} cached, "
            f"{remaining} remaining",
            file=sys.stderr
        )

        return added


load_release_metadata()