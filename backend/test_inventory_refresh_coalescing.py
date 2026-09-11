import asyncio
import unittest

from inventory_refresh_coordinator import (
    InventoryRefreshCoordinator
)


class InventoryRefreshCoalescingTests(
    unittest.IsolatedAsyncioTestCase
):
    async def test_concurrent_refresh_requests_share_one_download(self):
        calls = 0
        coordinator = InventoryRefreshCoordinator()

        async def fake_refresh_cache():
            nonlocal calls
            calls += 1
            await asyncio.sleep(0.05)
            return 123

        results = await asyncio.gather(
            *[
                coordinator.run(fake_refresh_cache)
                for _ in range(6)
            ]
        )

        self.assertEqual(calls, 1)
        self.assertEqual(results, [123] * 6)

    async def test_cancelled_waiter_does_not_cancel_shared_refresh(self):
        calls = 0
        coordinator = InventoryRefreshCoordinator()
        started = asyncio.Event()
        release = asyncio.Event()

        async def fake_refresh_cache():
            nonlocal calls
            calls += 1
            started.set()
            await release.wait()
            return 77

        first = asyncio.create_task(
            coordinator.run(fake_refresh_cache)
        )
        await started.wait()

        second = asyncio.create_task(
            coordinator.run(fake_refresh_cache)
        )

        first.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await first

        release.set()
        self.assertEqual(await second, 77)
        self.assertEqual(calls, 1)


if __name__ == "__main__":
    unittest.main()
