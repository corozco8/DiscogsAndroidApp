import asyncio
from collections.abc import Awaitable, Callable
from typing import TypeVar


T = TypeVar("T")


class InventoryRefreshCoordinator:
    """Run at most one full inventory refresh at a time.

    Concurrent callers share the same task instead of queueing duplicate
    downloads. Cancellation of one waiter does not cancel the shared refresh.
    """

    def __init__(self):
        self._task: asyncio.Task | None = None
        self._guard = asyncio.Lock()

    async def run(
        self,
        refresh_factory: Callable[[], Awaitable[T]]
    ) -> T:
        async with self._guard:
            if self._task is None or self._task.done():
                self._task = asyncio.create_task(
                    refresh_factory()
                )

            task = self._task

        try:
            return await asyncio.shield(task)

        finally:
            if task.done():
                async with self._guard:
                    if self._task is task:
                        self._task = None
