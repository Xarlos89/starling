// Check-in timer rules for the phone that sets one and every phone watching it.

// Grace for two phones whose clocks disagree.
export const DUE_GRACE_MS = 60 * 1000;
export const DUE_MAX_AHEAD_MS = 24 * 60 * 60 * 1000;
export const DUE_WARN_MS = 5 * 60 * 1000;
export const TIMER_CHOICES_MIN = [30, 60, 120, 240, 480];
export const DEFAULT_TIMER_MIN = 60;

// A deadline before the message or more than a day after it is junk.
export function dueFrom(obj) {
  const due = obj?.due;
  if (!Number.isFinite(due) || !Number.isFinite(obj.ts)) return null;
  return due > obj.ts && due <= obj.ts + DUE_MAX_AHEAD_MS ? due : null;
}

export function overdue(rec, now) {
  return !!rec?.due && now >= rec.due + DUE_GRACE_MS;
}

export function warnDue(due, now) {
  return Number.isFinite(due) && now >= due - DUE_WARN_MS && now < due;
}

// A stored timer belongs to the circle identity that armed it.
export function storedTimer(raw, memberId, now) {
  if (!raw || typeof raw !== "object") return null;
  if (!Number.isFinite(raw.due) || typeof raw.member !== "string" || !raw.member) return null;
  if (now > raw.due + DUE_MAX_AHEAD_MS) return null;
  if (memberId && raw.member !== memberId) return null;
  return { due: raw.due, member: raw.member };
}
