import type { ScheduleEvent } from "./types";
export function local(value: string | Date) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", {
      timeZone: "Asia/Shanghai",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    })
      .formatToParts(new Date(value))
      .map((p) => [p.type, p.value]),
  );
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}
export const today = () => local(new Date()).slice(0, 10);
export const stamp = (value: string) =>
  value.length === 16 ? value + ":00+08:00" : value;
export const label = (value: string) =>
  value ? local(value).replace("T", " ") : "未指定";
export function shift(day: string, n: number) {
  const d = new Date(day + "T12:00:00+08:00");
  d.setUTCDate(d.getUTCDate() + n);
  return local(d).slice(0, 10);
}
export function status(e: ScheduleEvent) {
  return e.status === "accepted"
    ? ({ not_started: "待执行", in_progress: "进行中", completed: "已完成" }[
        e.phase
      ] ?? e.phase)
    : ({ pending: "待确认", rejected: "已拒绝", cancelled: "已取消" }[
        e.status
      ] ?? e.status);
}
export const active = (e: ScheduleEvent) =>
  ["pending", "accepted"].includes(e.status) && e.phase !== "completed";
export const inDay = (e: ScheduleEvent, day: string) =>
  new Date(e.start) < new Date(shift(day, 1) + "T00:00:00+08:00") &&
  new Date(e.end) > new Date(day + "T00:00:00+08:00");
