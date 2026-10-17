export interface Citation {
  id: number;
  version_id: number;
  document_id: number;
  title: string;
  version: number;
  locator: string;
  base_name: string;
  excerpt?: string;
  content?: string;
}
export interface KnowledgeSuggestion {
  kind: "duration" | "checklist" | "notes";
  value: string | number;
  source_id: number;
  quote: string;
}
export interface KnowledgeReply {
  query_id: string;
  mode: string;
  message: string;
  warning?: string;
  statements: { text: string; source_id: number; quote: string }[];
  suggestions: KnowledgeSuggestion[];
  citations: Citation[];
}
export interface KnowledgeBase {
  id: number;
  name: string;
  description: string;
  scope: string;
  team_id: number | null;
  revision: number;
  document_count: number;
  user_ids?: number[];
}
export interface KnowledgeDocument {
  id: number;
  base_id: number;
  title: string;
  base_name: string;
  revision: number;
  published_version_id: number | null;
  version: number | null;
  status: string | null;
  chunk_count: number | null;
  job_status: string | null;
  error: string | null;
}
export interface KnowledgeVersion {
  id: number;
  version: number;
  filename: string;
  status: string;
  chunk_count: number;
  space_id: string;
  effective_from: string;
  effective_until: string | null;
  external_allowed: boolean;
  job_status: string;
  error: string;
  attempts: number;
}
