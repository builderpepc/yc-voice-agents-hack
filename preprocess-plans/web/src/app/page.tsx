import { createClient } from "@/lib/supabase/server";
import BuildingList from "@/components/BuildingList";
import UploadForm from "@/components/UploadForm";

export default async function Home() {
  const supabase = await createClient();

  const { data: buildings } = await supabase
    .from("buildings")
    .select("building_id, address, status, error_message, pre_plan_summary, known_hazards, reviewed_status, reviewed_at, created_at, updated_at")
    .order("created_at", { ascending: false });

  return (
    <div className="min-h-screen bg-gray-950 text-white">
      <header className="border-b border-gray-800 px-6 py-4 flex items-center justify-between">
        <h1 className="text-lg font-bold">FireSight Admin</h1>
      </header>

      <main className="max-w-5xl mx-auto px-6 py-8 space-y-8">
        <UploadForm />
        <BuildingList initialBuildings={buildings ?? []} />
      </main>
    </div>
  );
}
