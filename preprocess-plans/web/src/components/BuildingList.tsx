"use client";

import { useEffect, useState } from "react";
import { createClient } from "@/lib/supabase/client";
import BuildingRow from "./BuildingRow";

interface Building {
  building_id: string;
  address: string;
  status: string;
  error_message: string | null;
  pre_plan_summary: string | null;
  known_hazards: Record<string, unknown> | null;
  reviewed_status: string | null;
  reviewed_at: string | null;
  created_at: string;
  updated_at: string;
}

export default function BuildingList({
  initialBuildings,
}: {
  initialBuildings: Building[];
}) {
  const [buildings, setBuildings] = useState<Building[]>(initialBuildings);
  const supabase = createClient();

  useEffect(() => {
    const channel = supabase
      .channel("buildings-realtime")
      .on(
        "postgres_changes",
        { event: "*", schema: "public", table: "buildings" },
        (payload) => {
          if (payload.eventType === "INSERT") {
            setBuildings((prev) => [payload.new as Building, ...prev]);
          } else if (payload.eventType === "UPDATE") {
            setBuildings((prev) =>
              prev.map((b) =>
                b.building_id === (payload.new as Building).building_id
                  ? (payload.new as Building)
                  : b
              )
            );
          } else if (payload.eventType === "DELETE") {
            setBuildings((prev) =>
              prev.filter(
                (b) => b.building_id !== (payload.old as Building).building_id
              )
            );
          }
        }
      )
      .subscribe();

    return () => {
      supabase.removeChannel(channel);
    };
  }, [supabase]);

  if (buildings.length === 0) {
    return (
      <div className="text-center text-gray-500 py-12">
        No buildings yet. Upload source documents to get started.
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <h2 className="text-lg font-semibold">Buildings</h2>
      {buildings.map((b) => (
        <BuildingRow key={b.building_id} building={b} />
      ))}
    </div>
  );
}
