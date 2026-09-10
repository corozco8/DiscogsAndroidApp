import asyncio
import httpx
import sys
from pathlib import Path
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel
from agents.mcp import (
    MCPServerManager,
    MCPServerStdio
)

from agent_service import (
    create_inventory_agent,
    run_inventory_agent
)

from inventory_cache import (
    cache_needs_refresh,
    get_cache_count,
    get_release_ids,
    refresh_cache,
    remove_listings_from_cache
)

from release_metadata_cache import (
    get_metadata_count,
    get_missing_release_ids,
    sync_missing_release_metadata
)


BACKEND_DIR = Path(__file__).resolve().parent


async def background_inventory_refresh():
    while True:
        try:
            release_ids = get_release_ids()

            missing_metadata = get_missing_release_ids(
                release_ids
            )

            if missing_metadata:
                print(
                    "Skipping inventory refresh while "
                    f"metadata indexing is in progress "
                    f"({len(missing_metadata)} remaining)."
                )

                await asyncio.sleep(300)
                continue

            if cache_needs_refresh(
                max_age_seconds=3600
            ):
                print(
                    "Inventory cache is stale. "
                    "Refreshing in background..."
                )

                await refresh_cache()

        except asyncio.CancelledError:
            raise

        except Exception as error:
            print(
                f"Background inventory refresh failed: "
                f"{error}"
            )

        await asyncio.sleep(300)


async def background_release_metadata_refresh():
    while True:
        try:
            release_ids = get_release_ids()

            added = (
                await sync_missing_release_metadata(
                    release_ids,
                    limit=30
                )
            )

            if added > 0:
                print(
                    f"Added metadata for "
                    f"{added} releases."
                )

                # Keep working through the
                # missing releases.
                await asyncio.sleep(2)

            else:
                # Everything is currently cached.
                await asyncio.sleep(300)

        except asyncio.CancelledError:
            raise

        except Exception as error:
            print(
                f"Release metadata refresh "
                f"failed: {error}"
            )

            await asyncio.sleep(60)


@asynccontextmanager
async def lifespan(app: FastAPI):

    print(
        f"Starting with "
        f"{get_cache_count()} cached listings"
    )

    # Start inventory refresh task
    refresh_task = asyncio.create_task(
        background_inventory_refresh()
    )

    metadata_task = asyncio.create_task(
        background_release_metadata_refresh()
    )

    # Configure the persistent MCP subprocess
    mcp_server = MCPServerStdio(
        name="Discogs Inventory MCP",

        params={
            "command": sys.executable,
            "args": [
                str(
                    BACKEND_DIR
                    / "discogs_mcp_server.py"
                )
            ],
            "cwd": str(BACKEND_DIR)
        },

        cache_tools_list=True,
        use_structured_content=True,
        client_session_timeout_seconds=15
    )

    try:
        # Connect MCP once for FastAPI's entire lifetime
        async with MCPServerManager(
            [mcp_server],
            strict=True
        ) as mcp_manager:

            print(
                "Discogs MCP server connected."
            )

            # Create the agent once
            app.state.inventory_agent = (
                create_inventory_agent(
                    mcp_manager.active_servers
                )
            )

            print(
                "Discogs AI agent ready."
            )

            yield

    finally:
        refresh_task.cancel()
        metadata_task.cancel()

        for task in (
            refresh_task,
            metadata_task
        ):
            try:
                await task
            except asyncio.CancelledError:
                pass


app = FastAPI(
    lifespan=lifespan
)


class AiSearchRequest(BaseModel):
    query: str


class RemoveInventoryCacheRequest(BaseModel):
    listingIds: list[int]


@app.get("/")
def root():
    return {
        "message": "Discogs AI Backend is running"
    }


@app.get("/api/cache-status")
def cache_status():
    return {
        "cachedItems": get_cache_count()
    }


@app.post("/api/sync-inventory")
async def sync_inventory():
    try:
        count = await refresh_cache()

        return {
            "status": "success",
            "cachedItems": count
        }

    except httpx.HTTPStatusError as error:
        raise HTTPException(
            status_code=502,
            detail=(
                f"Discogs API error: "
                f"{error.response.status_code}"
            )
        )


@app.post("/api/inventory-cache/remove")
def remove_inventory_cache(
    cache_request: RemoveInventoryCacheRequest
):
    try:
        removed = remove_listings_from_cache(
            cache_request.listingIds
        )

        return {
            "status": "success",
            "removed": removed,
            "cachedItems": get_cache_count()
        }

    except Exception as error:
        print(
            "Failed to remove listings from "
            f"inventory cache: {error}"
        )

        raise HTTPException(
            status_code=500,
            detail=str(error)
        )


@app.post("/api/ai-search")
async def ai_search(
    search_request: AiSearchRequest,
    request: Request
):
    if get_cache_count() == 0:
        raise HTTPException(
            status_code=503,
            detail=(
                "Inventory cache is empty. "
                "Sync the inventory first."
            )
        )

    try:
        agent_result = await run_inventory_agent(
            agent=request.app.state.inventory_agent,
            query=search_request.query
        )

        results = agent_result["results"]

        if agent_result["status"] == "INDEXING":
            return {
                "query": search_request.query,
                "summary": agent_result["message"],
                "resultType": "INDEXING",
                "results": []
            }

        return {
            "query": search_request.query,
            "summary": (
                f"Found {len(results)} matching records."
            ),
            "resultType": "RECORD_LIST",
            "results": [
                item.model_dump()
                for item in results
            ]
        }

    except Exception as error:
        print(
            f"AI inventory search failed: "
            f"{error}"
        )

        raise HTTPException(
            status_code=500,
            detail=str(error)
        )


@app.get("/api/metadata-status")
def metadata_status():
    release_ids = get_release_ids()

    missing = get_missing_release_ids(
        release_ids
    )

    return {
        "uniqueReleases": len(release_ids),
        "metadataCached": get_metadata_count(),
        "missingMetadata": len(missing),
        "complete": len(missing) == 0
    }
