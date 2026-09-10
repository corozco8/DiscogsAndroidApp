import os

import httpx
from pathlib import Path
from dotenv import load_dotenv

ENV_PATH = Path(__file__).resolve().parent / ".env"
load_dotenv(dotenv_path=ENV_PATH, override=True)
DISCOGS_TOKEN = os.getenv("DISCOGS_TOKEN")

load_dotenv()

DISCOGS_API_URL = "https://api.discogs.com"
DISCOGS_TOKEN = os.getenv("DISCOGS_TOKEN")
DISCOGS_USERNAME = os.getenv("DISCOGS_USERNAME")


if not DISCOGS_TOKEN:
    raise RuntimeError("DISCOGS_TOKEN is missing from .env")

if not DISCOGS_USERNAME:
    raise RuntimeError("DISCOGS_USERNAME is missing from .env")


HEADERS = {
    "Authorization": f"Discogs token={DISCOGS_TOKEN}",
    "User-Agent": "DiscogsAIInventorySearch/0.1"
}


async def get_inventory_page(
    page: int = 1,
    per_page: int = 100
):
    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.get(
            f"{DISCOGS_API_URL}/users/{DISCOGS_USERNAME}/inventory",
            headers=HEADERS,
            params={
                "status": "For Sale",
                "page": page,
                "per_page": per_page
            }
        )

        response.raise_for_status()
        return response.json()


async def fetch_all_inventory():
    all_listings = []
    page = 1

    async with httpx.AsyncClient(
        timeout=30.0,
        headers=HEADERS
    ) as client:

        while True:
            print(f"Fetching inventory page {page}...")

            response = await client.get(
                f"{DISCOGS_API_URL}/users/{DISCOGS_USERNAME}/inventory",
                params={
                    "status": "For Sale",
                    "page": page,
                    "per_page": 100
                }
            )

            response.raise_for_status()

            data = response.json()

            listings = data.get("listings", [])
            all_listings.extend(listings)

            pagination = data.get("pagination") or {}

            current_page = pagination.get("page", page)
            total_pages = pagination.get("pages", page)

            print(
                f"Page {current_page} of {total_pages} "
                f"- {len(all_listings)} listings loaded"
            )

            if current_page >= total_pages:
                break

            page += 1

    print(
        f"Inventory sync complete: "
        f"{len(all_listings)} listings"
    )

    return all_listings