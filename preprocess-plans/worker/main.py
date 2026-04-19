import asyncio
import logging

from supabase import acreate_client

from config import SUPABASE_SERVICE_KEY, SUPABASE_URL
from pipeline import process_building

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

# Track buildings already being processed to avoid duplicates
_in_progress: set[str] = set()


async def poll_pending(supabase):
    """Poll for pending buildings every 5 seconds as the primary mechanism."""
    while True:
        try:
            result = (
                await supabase.table("buildings")
                .select("building_id")
                .eq("status", "pending")
                .execute()
            )
            for row in result.data:
                bid = row["building_id"]
                if bid not in _in_progress:
                    _in_progress.add(bid)
                    logger.info(f"Picked up building {bid}")
                    asyncio.create_task(run_pipeline(supabase, bid))
        except Exception as e:
            logger.error(f"Poll error: {e}")

        await asyncio.sleep(5)


async def run_pipeline(supabase, building_id: str):
    try:
        await process_building(supabase, building_id)
    finally:
        _in_progress.discard(building_id)


async def main():
    supabase = await acreate_client(SUPABASE_URL, SUPABASE_SERVICE_KEY)

    # Recover orphaned rows from prior crashes
    orphaned = (
        await supabase.table("buildings")
        .select("building_id")
        .eq("status", "processing")
        .execute()
    )
    for row in orphaned.data:
        logger.info(f"Recovering orphaned building {row['building_id']}")
        await supabase.table("buildings").update({"status": "pending"}).eq(
            "building_id", row["building_id"]
        ).execute()

    logger.info("Worker polling for pending buildings every 5s")
    await poll_pending(supabase)


if __name__ == "__main__":
    asyncio.run(main())
