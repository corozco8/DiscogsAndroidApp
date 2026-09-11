import asyncio
import json
import os
import tempfile
import time
import unittest
from pathlib import Path

os.environ.setdefault("DISCOGS_TOKEN", "test-token")
os.environ.setdefault("DISCOGS_USERNAME", "test-user")

import inventory_cache


class InventoryCacheRegressionTests(
    unittest.IsolatedAsyncioTestCase
):
    def setUp(self):
        self.original_cache_file = inventory_cache.CACHE_FILE
        self.original_sync_meta_file = inventory_cache.SYNC_META_FILE
        self.original_get_release_metadata = (
            inventory_cache.get_release_metadata
        )
        self.original_get_missing_release_ids = (
            inventory_cache.get_missing_release_ids
        )

        self.temp_dir = tempfile.TemporaryDirectory()
        temp_path = Path(self.temp_dir.name)

        inventory_cache.CACHE_FILE = (
            temp_path / "inventory_cache.json"
        )
        inventory_cache.SYNC_META_FILE = (
            temp_path / "inventory_cache_meta.json"
        )
        inventory_cache._inventory_cache = []
        inventory_cache._last_sync_time = 0
        inventory_cache._mutation_lock = asyncio.Lock()

    def tearDown(self):
        inventory_cache.CACHE_FILE = self.original_cache_file
        inventory_cache.SYNC_META_FILE = (
            self.original_sync_meta_file
        )
        inventory_cache.get_release_metadata = (
            self.original_get_release_metadata
        )
        inventory_cache.get_missing_release_ids = (
            self.original_get_missing_release_ids
        )
        self.temp_dir.cleanup()

    def _write_cache(self, rows):
        inventory_cache.CACHE_FILE.write_text(
            json.dumps(rows),
            encoding="utf-8"
        )

    def test_year_sort_reports_incomplete_metadata(self):
        rows = [
            {
                "listingId": 1,
                "releaseId": 101,
                "artist": "A",
                "title": "One",
                "price": 10.0,
            },
            {
                "listingId": 2,
                "releaseId": 202,
                "artist": "B",
                "title": "Two",
                "price": 20.0,
            },
        ]
        self._write_cache(rows)
        inventory_cache.load_cache()

        def fake_metadata(release_id):
            if release_id == 101:
                return {"year": 1970}
            return None

        inventory_cache.get_release_metadata = fake_metadata
        inventory_cache.get_missing_release_ids = (
            lambda release_ids: [202]
        )

        result = inventory_cache.search_inventory(
            sort_by="year",
            sort_order="asc",
            include_info=True
        )

        self.assertFalse(result["metadataComplete"])
        self.assertEqual(result["missingMetadata"], 1)

    async def test_local_delete_does_not_make_old_cache_fresh(self):
        rows = [
            {
                "listingId": 1,
                "releaseId": 101,
                "artist": "A",
                "title": "One",
                "price": 10.0,
            },
            {
                "listingId": 2,
                "releaseId": 202,
                "artist": "B",
                "title": "Two",
                "price": 20.0,
            },
        ]
        self._write_cache(rows)

        # No remote-sync metadata exists. The safe behavior is to consider
        # this snapshot stale, regardless of the JSON file's mtime.
        inventory_cache.load_cache()
        self.assertTrue(
            inventory_cache.cache_needs_refresh(
                max_age_seconds=3600
            )
        )

        await inventory_cache.remove_listings_from_cache([1])

        self.assertTrue(
            inventory_cache.cache_needs_refresh(
                max_age_seconds=3600
            )
        )

    async def test_targeted_edit_preserves_remote_sync_time(self):
        rows = [
            {
                "listingId": 1,
                "releaseId": 101,
                "artist": "A",
                "title": "One",
                "price": 10.0,
                "condition": "Very Good (VG)",
                "sleeveCondition": "Very Good (VG)",
                "comments": "",
            }
        ]
        self._write_cache(rows)

        old_sync = time.time() - 7200
        inventory_cache.SYNC_META_FILE.write_text(
            json.dumps({"lastRemoteSync": old_sync}),
            encoding="utf-8"
        )

        inventory_cache.load_cache()

        updated = await inventory_cache.update_listing_in_cache(
            1,
            price=30.0,
            condition="Near Mint (NM or M-)",
            sleeve_condition="Near Mint (NM or M-)",
            comments="Updated"
        )

        self.assertTrue(updated)
        self.assertAlmostEqual(
            inventory_cache._last_sync_time,
            old_sync,
            delta=0.001
        )

        saved = json.loads(
            inventory_cache.CACHE_FILE.read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(saved[0]["price"], 30.0)
        self.assertEqual(
            saved[0]["condition"],
            "Near Mint (NM or M-)"
        )


if __name__ == "__main__":
    unittest.main()
