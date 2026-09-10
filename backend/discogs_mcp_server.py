import sys

from mcp.server import MCPServer

from inventory_cache import (
    search_inventory as search_inventory_cache
)


mcp = MCPServer("Discogs Inventory")


@mcp.tool()
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
) -> dict:
    """
    Search the seller's cached Discogs inventory.
    """

    print(
        "MCP search_inventory:",
        {
            "artist": artist,
            "title": title,
            "genre": genre,
            "style": style,
            "label": label,
            "year": year,
            "min_year": min_year,
            "max_year": max_year,
            "format_name": format_name,
            "catalog_number": catalog_number,
            "min_price": min_price,
            "max_price": max_price,
            "sort_by": sort_by,
            "sort_order": sort_order,
            "limit": limit
        },
        file=sys.stderr
    )

    try:
        results = search_inventory_cache(
            artist=artist,
            title=title,

            genre=genre,
            style=style,
            label=label,

            year=year,
            min_year=min_year,
            max_year=max_year,

            format_name=format_name,
            catalog_number=catalog_number,

            min_price=min_price,
            max_price=max_price,

            sort_by=sort_by,
            sort_order=sort_order,

            limit=limit
        )

        return {
            "status": "OK",
            "message": None,
            "results": results
        }

    except RuntimeError as error:
        return {
            "status": "INDEXING",
            "message": str(error),
            "results": []
        }


if __name__ == "__main__":
    mcp.run(
        transport="stdio"
    )