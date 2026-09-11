import asyncio
import json
import sys
from pathlib import Path

from agents.mcp import (
    MCPServerManager,
    MCPServerStdio
)

from agent_service import (
    create_inventory_agent,
    run_inventory_agent
)


BACKEND_DIR = Path(__file__).resolve().parent


async def main():
    mcp_server = MCPServerStdio(
        name="Discogs Inventory MCP Test",
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

    async with MCPServerManager(
        [mcp_server],
        strict=True
    ) as manager:
        agent = create_inventory_agent(
            manager.active_servers
        )

        result = await run_inventory_agent(
            agent=agent,
            query=(
                "Show me Bob Dylan records "
                "over $20"
            )
        )

        serializable = dict(result)
        serializable["results"] = [
            item.model_dump()
            for item in result["results"]
        ]

        print(
            json.dumps(
                serializable,
                indent=2
            )
        )


if __name__ == "__main__":
    asyncio.run(main())
