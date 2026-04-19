"use client";

import { useState, useRef } from "react";
import { createClient } from "@/lib/supabase/client";
import { useRouter } from "next/navigation";

const MAX_FILES = 20;
const MAX_TOTAL_BYTES = 100 * 1024 * 1024; // 100MB
const ACCEPTED_TYPES = [
  "application/pdf",
  "image/jpeg",
  "image/png",
  "image/webp",
];

export default function UploadForm() {
  const [address, setAddress] = useState("");
  const [files, setFiles] = useState<File[]>([]);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState("");
  const fileInput = useRef<HTMLInputElement>(null);
  const supabase = createClient();
  const router = useRouter();

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const selected = Array.from(e.target.files ?? []);
    setError("");

    const invalid = selected.filter((f) => !ACCEPTED_TYPES.includes(f.type));
    if (invalid.length > 0) {
      setError(`Unsupported file types: ${invalid.map((f) => f.name).join(", ")}. Use PDF, JPG, PNG, or WebP.`);
      return;
    }

    if (selected.length > MAX_FILES) {
      setError(`Maximum ${MAX_FILES} files per building.`);
      return;
    }

    const totalSize = selected.reduce((sum, f) => sum + f.size, 0);
    if (totalSize > MAX_TOTAL_BYTES) {
      setError(`Total size (${(totalSize / 1024 / 1024).toFixed(1)}MB) exceeds ${MAX_TOTAL_BYTES / 1024 / 1024}MB limit.`);
      return;
    }

    setFiles(selected);
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!address.trim() || files.length === 0) return;

    setUploading(true);
    setError("");

    try {
      // 1. Insert building row
      const { data: building, error: insertError } = await supabase
        .from("buildings")
        .insert({
          address: address.trim(),
          status: "pending",
          created_by: null,
        })
        .select("building_id")
        .single();

      if (insertError) throw insertError;
      const buildingId = building.building_id;

      // 2. Upload files and insert site_documents
      for (const file of files) {
        const docId = crypto.randomUUID();
        const ext = file.name.split(".").pop() ?? "bin";
        const storagePath = `${buildingId}/${docId}.${ext}`;

        const { error: uploadError } = await supabase.storage
          .from("site-documents")
          .upload(storagePath, file);

        if (uploadError) throw uploadError;

        const { error: docError } = await supabase
          .from("site_documents")
          .insert({
            id: docId,
            building_id: buildingId,
            file_name: file.name,
            file_type: file.type,
            file_size_bytes: file.size,
            storage_path: storagePath,
            uploaded_by: null,
          });

        if (docError) throw docError;
      }

      // Reset form
      setAddress("");
      setFiles([]);
      if (fileInput.current) fileInput.current.value = "";
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setUploading(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="bg-gray-900 rounded-lg p-6 space-y-4">
      <h2 className="text-lg font-semibold">Add Building</h2>

      {error && <p className="text-red-400 text-sm">{error}</p>}

      <input
        type="text"
        placeholder="Building address"
        value={address}
        onChange={(e) => setAddress(e.target.value)}
        required
        className="w-full px-3 py-2 bg-gray-800 rounded border border-gray-700 focus:border-orange-500 focus:outline-none"
      />

      <div>
        <input
          ref={fileInput}
          type="file"
          multiple
          accept=".pdf,.jpg,.jpeg,.png,.webp"
          onChange={handleFileChange}
          className="text-sm text-gray-400 file:mr-4 file:py-2 file:px-4 file:rounded file:border-0 file:bg-gray-800 file:text-white hover:file:bg-gray-700"
        />
        {files.length > 0 && (
          <p className="text-sm text-gray-400 mt-1">
            {files.length} file{files.length !== 1 ? "s" : ""} selected (
            {(files.reduce((s, f) => s + f.size, 0) / 1024 / 1024).toFixed(1)}MB)
          </p>
        )}
      </div>

      <button
        type="submit"
        disabled={uploading || !address.trim() || files.length === 0}
        className="px-4 py-2 bg-orange-600 hover:bg-orange-700 disabled:opacity-50 rounded font-medium"
      >
        {uploading ? "Uploading..." : "Upload & Process"}
      </button>
    </form>
  );
}
