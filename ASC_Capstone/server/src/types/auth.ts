export const ROLE = {
  teacher: "teacher",
  student: "student"
} as const;

export type Role = (typeof ROLE)[keyof typeof ROLE];
