export interface User {
  id: number;
  username: string;
  name: string;
  role: "admin" | "member";
  team_id: number | null;
  active: number;
  is_leader?: boolean;
}
export interface Team {
  id: number;
  name: string;
  description: string;
  leader_id: number | null;
}
export interface Room {
  id: number;
  name: string;
  capacity: number;
}
export interface Check {
  label: string;
  done: boolean;
}
export interface HistoryEntry {
  action: string;
  actor_name: string;
  created_at: string;
  snapshot: ScheduleEvent;
}
export interface AuditEntry {
  action: string;
  actor_name: string;
  created_at: string;
}
export interface ChangeRequest {
  status: string;
  start: string;
  end: string;
  reason: string;
  response?: string;
}
export interface ScheduleEvent {
  id: number;
  title: string;
  target_id: number;
  creator_id: number;
  target_name: string;
  creator_name: string;
  start: string;
  end: string;
  location: string;
  notes: string;
  status: string;
  phase: string;
  source: string;
  original_text: string;
  updated_at: string;
  priority: string;
  organize_team_id: number | null;
  buffer_before: number;
  buffer_after: number;
  travel_minutes: number;
  checklist: Check[];
  completion_report: string;
  reschedule_request: ChangeRequest | null;
  draft_id: number | null;
}
export interface Notice {
  id: number;
  title: string;
  body: string;
  is_read: number;
  created_at: string;
  event_id: number | null;
}
export interface Workspace {
  user: User;
  users: User[];
  teams: Team[];
  rooms: Room[];
  events: ScheduleEvent[];
  notifications: Notice[];
  unread_count: number;
  ai_mode: string;
}
export interface Draft extends Partial<ScheduleEvent> {
  knowledge_query_id?: string;
  knowledge_assistance?: import("./knowledge").KnowledgeReply;
  action?: "create" | "update";
  original_draft_id?: number | null;
  planning?: CheckResult;
  reference_candidates?: EventReference[];
  complete: boolean;
  engine: string;
  warnings: string[];
  questions: string[];
  evidence: Record<string, string>;
  candidates: { id: number; name: string }[];
}
export interface EventReference {
  id: number;
  title: string;
  target_name: string;
  start: string;
  end: string;
  location: string;
  pending_reschedule: boolean;
}
export interface Plan {
  start: string;
  end: string;
  location: string;
  rank: number;
  score: number;
  team_busy_count: number;
  delay_minutes: number;
  reason: string;
  reasons: string[];
}
export interface SchedulingPolicy {
  weekdays: number[];
  work_start: string;
  work_end: string;
  step_minutes: number;
  horizon_days: number;
  holidays: string[];
  workdays: string[];
}
export interface Conflict {
  event_id: number | null;
  kind: string;
  reason: string;
  title: string;
  start: string;
  end: string;
  target_name: string;
  location: string;
}
export interface CheckResult {
  conflicts: Conflict[];
  suggestions: Plan[];
  warnings: string[];
  team_busy: { id: number; name: string }[];
}
