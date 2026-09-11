import ast
import json

from agents import Agent, Runner, ModelSettings
from agents.items import ToolCallOutputItem
from openai.types.shared import Reasoning
from pydantic import BaseModel

class InventoryResult(BaseModel):
    listingId: int | None = None
    releaseId: int

    artist: str
    title: str

    year: int | None = None
    label: str | None = None
    catalogNumber: str | None = None
    format: str | None = None

    condition: str | None = None
    sleeveCondition: str | None = None
    comments: str = ""

    price: float | None = None
    currency: str | None = None

    marketValue: float | None = None
    recommendedPrice: float | None = None

    thumbnail: str | None = None
    reason: str | None = None


class InventoryAgentResponse(BaseModel):
    summary: str
    resultType: str
    results: list[InventoryResult]


def create_inventory_agent(
    mcp_servers
) -> Agent:

    return Agent(
        name="Discogs Inventory Agent",

        model="gpt-5.6-luna",

        instructions="""
You translate natural-language Discogs inventory
questions into exactly one search_inventory MCP
tool call.

Use the semantic field that the user actually means.

Never substitute artist or title searches for
genre, style, or label searches.

Examples:

"Show me Beatles records over $20"
artist = "Beatles"
min_price = 20

"Show me my five most valuable jazz records"
genre = "Jazz"
sort_by = "price"
sort_order = "desc"
limit = 5

"Show me Blue Note records"
label = "Blue Note"

"Show me Soul-Jazz records"
style = "Soul-Jazz"

"Show me jazz records from the 1960s"
genre = "Jazz"
min_year = 1960
max_year = 1969

"Show me my ten most expensive Blue Note records"
label = "Blue Note"
sort_by = "price"
sort_order = "desc"
limit = 10

"Show me Beatles records from 1967"
artist = "Beatles"
year = 1967

"Show me my most expensive LPs"
format_name = "LP"
sort_by = "price"
sort_order = "desc"

Always use the MCP tool.

Do not invent inventory data.
""",

        mcp_servers=mcp_servers,

        tool_use_behavior="stop_on_first_tool",

        model_settings=ModelSettings(
            tool_choice="required",
            reasoning=Reasoning(
                effort="none"
            ),
            verbosity="low"
        )
    )


async def run_inventory_agent(
    agent: Agent,
    query: str
):
    result = await Runner.run(
        agent,
        query
    )

    tool_output = None

    for item in reversed(result.new_items):
        if isinstance(
            item,
            ToolCallOutputItem
        ):
            tool_output = item.output
            break

    if tool_output is None:
        raise ValueError(
            "The agent did not return an MCP tool result."
        )

    print(
        "MCP TOOL OUTPUT TYPE:",
        type(tool_output).__name__
    )

    #
    # Step 1:
    # MCP currently gives us something like:
    #
    # {
    #     "type": "text",
    #     "text": "{ \"status\": \"OK\", ... }"
    # }
    #
    if (
        isinstance(tool_output, dict)
        and tool_output.get("type") == "text"
        and "text" in tool_output
    ):
        tool_output = tool_output["text"]

    #
    # Step 2:
    # Convert text into a Python object.
    #
    if isinstance(tool_output, str):
        try:
            data = json.loads(
                tool_output
            )

        except json.JSONDecodeError:
            try:
                data = ast.literal_eval(
                    tool_output
                )

            except (
                ValueError,
                SyntaxError
            ) as error:
                raise ValueError(
                    "Could not parse MCP tool output: "
                    f"{tool_output!r}"
                ) from error

    elif isinstance(tool_output, dict):
        data = tool_output

    else:
        raise ValueError(
            "Unexpected MCP output type: "
            f"{type(tool_output).__name__}"
        )

    #
    # Step 3:
    # Handle any optional MCP wrapper.
    #
    if (
        isinstance(data, dict)
        and "result" in data
        and "status" not in data
    ):
        data = data["result"]

        if isinstance(data, str):
            try:
                data = json.loads(data)

            except json.JSONDecodeError:
                data = ast.literal_eval(data)

    if not isinstance(data, dict):
        raise ValueError(
            "Unexpected MCP response format: "
            f"{data!r}"
        )

    #
    # Step 4:
    # Extract our actual MCP response.
    #
    status = data.get(
        "status",
        "OK"
    )

    message = data.get(
        "message"
    )

    results_data = data.get(
        "results",
        []
    )

    total_matches = data.get(
        "totalMatches"
    )

    truncated = bool(
        data.get(
            "truncated",
            False
        )
    )

    metadata_complete = bool(
        data.get(
            "metadataComplete",
            True
        )
    )

    missing_metadata = int(
        data.get(
            "missingMetadata",
            0
        ) or 0
    )

    results = [
        InventoryResult.model_validate(
            item
        )
        for item in results_data
    ]

    print(
        f"Parsed MCP response: "
        f"status={status}, "
        f"results={len(results)}"
    )

    return {
        "status": status,
        "message": message,
        "results": results,
        "totalMatches": (
            int(total_matches)
            if total_matches is not None
            else len(results)
        ),
        "truncated": truncated,
        "metadataComplete": metadata_complete,
        "missingMetadata": missing_metadata
    }