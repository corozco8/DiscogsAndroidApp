import asyncio

from agent_service import run_inventory_agent


async def main():

    result = await run_inventory_agent(
        "Show me Bob Dylan records over $20"
    )

    print(
        result.model_dump_json(
            indent=2
        )
    )


if __name__ == "__main__":
    asyncio.run(main())