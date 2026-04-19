"use client";

import { useState } from "react";
import { createClient } from "@/lib/supabase/client";

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

const STATUS_STYLES: Record<string, { label: string; color: string }> = {
  pending: { label: "Pending", color: "text-yellow-400" },
  processing: { label: "Processing", color: "text-blue-400" },
  pending_review: { label: "Pending Review", color: "text-purple-400" },
  ready: { label: "Ready", color: "text-green-400" },
  failed: { label: "Failed", color: "text-red-400" },
  rejected: { label: "Rejected", color: "text-red-400" },
};

export default function BuildingRow({ building }: { building: Building }) {
  const [expanded, setExpanded] = useState(false);
  const [approving, setApproving] = useState(false);
  const supabase = createClient();
  const status = STATUS_STYLES[building.status] ?? { label: building.status, color: "text-gray-400" };

  async function handleRetry() {
    await supabase
      .from("buildings")
      .update({
        status: "pending",
        error_message: null,
        reviewed_status: "pending_review",
      })
      .eq("building_id", building.building_id);
  }

  async function handleReview(approved: boolean) {
    setApproving(true);
    await supabase
      .from("buildings")
      .update({
        status: approved ? "ready" : "rejected",
        reviewed_status: approved ? "approved" : "rejected",
        reviewed_at: new Date().toISOString(),
        reviewed_by: null,
        error_message: approved ? null : "Rejected by admin review",
      })
      .eq("building_id", building.building_id);
    setApproving(false);
  }

  const hazards = building.known_hazards as {
    hazards?: Array<{
      id: string;
      type: string;
      name: string;
      location: string;
      confidence: string;
      mitigation_notes: string;
    }>;
    summary?: string;
  } | null;

  return (
    <div className="bg-gray-900 rounded-lg p-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={() => setExpanded(!expanded)}
            className="text-gray-400 hover:text-white text-sm"
          >
            {expanded ? "v" : ">"}
          </button>
          <div>
            <p className="font-medium">{building.address}</p>
            <p className="text-sm text-gray-500">
              {new Date(building.created_at).toLocaleDateString()}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <span className={`text-sm font-medium ${status.color}`}>
            {status.label}
          </span>

          {(building.status === "failed" || building.status === "rejected") && (
            <button
              onClick={handleRetry}
              className="text-sm px-3 py-1 bg-gray-800 hover:bg-gray-700 rounded"
            >
              Retry
            </button>
          )}
        </div>
      </div>

      {building.status === "failed" && building.error_message && (
        <p className="mt-2 text-sm text-red-400 bg-red-950 rounded px-3 py-2">
          {building.error_message}
        </p>
      )}

      {expanded && building.pre_plan_summary && (
        <div className="mt-4 space-y-4">
          <div>
            <h3 className="text-sm font-semibold text-gray-400 mb-1">
              Pre-Plan Summary
            </h3>
            <p className="text-sm whitespace-pre-wrap bg-gray-800 rounded p-3">
              {building.pre_plan_summary}
            </p>
          </div>

          {hazards?.hazards && hazards.hazards.length > 0 && (
            <div>
              <h3 className="text-sm font-semibold text-gray-400 mb-1">
                Known Hazards ({hazards.hazards.length})
              </h3>
              <div className="space-y-2">
                {hazards.hazards.map((h) => (
                  <div
                    key={h.id}
                    className="bg-gray-800 rounded p-3 text-sm space-y-1"
                  >
                    <div className="flex items-center gap-2">
                      <span className="font-medium">{h.name}</span>
                      <span className="text-xs px-1.5 py-0.5 rounded bg-gray-700 text-gray-300">
                        {h.type}
                      </span>
                      <span
                        className={`text-xs px-1.5 py-0.5 rounded ${
                          h.confidence === "high"
                            ? "bg-green-900 text-green-300"
                            : h.confidence === "medium"
                              ? "bg-yellow-900 text-yellow-300"
                              : "bg-red-900 text-red-300"
                        }`}
                      >
                        {h.confidence}
                      </span>
                    </div>
                    <p className="text-gray-400">Location: {h.location}</p>
                    <p className="text-gray-400">{h.mitigation_notes}</p>
                  </div>
                ))}
              </div>
            </div>
          )}

          {hazards?.hazards?.length === 0 && (
            <p className="text-sm text-gray-500">
              {hazards.summary ?? "No hazards identified."}
            </p>
          )}

          {building.status === "pending_review" && (
            <div className="flex gap-3 pt-2 border-t border-gray-800">
              <button
                onClick={() => handleReview(true)}
                disabled={approving}
                className="px-4 py-2 bg-green-700 hover:bg-green-600 disabled:opacity-50 rounded font-medium text-sm"
              >
                Approve
              </button>
              <button
                onClick={() => handleReview(false)}
                disabled={approving}
                className="px-4 py-2 bg-red-800 hover:bg-red-700 disabled:opacity-50 rounded font-medium text-sm"
              >
                Reject
              </button>
            </div>
          )}

          {building.reviewed_at && (
            <p className="text-xs text-gray-500">
              Reviewed {new Date(building.reviewed_at).toLocaleString()} — {building.reviewed_status}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
