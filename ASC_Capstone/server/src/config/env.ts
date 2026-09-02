import { config } from "dotenv";
import { z } from "zod";

config();

const envSchema = z.object({
  NODE_ENV: z.enum(["development", "test", "production"]).default("development"),
  PORT: z.coerce.number().int().positive().default(3000),
  DATABASE_URL: z.string().min(1),
  JWT_SECRET: z.string().min(16, "JWT_SECRET must be at least 16 characters"),
  JWT_EXPIRES_IN: z.string().default("1d"),
  CONFIDENCE_THRESHOLD: z.coerce.number().min(0).max(1).default(0.8),
  // Base URL of the teacher worksheet system (v-2.1.1 backend, FastAPI).
  // Read only by the import route; nothing else talks to it.
  TEACHER_API_BASE_URL: z.string().url().default("http://localhost:8000"),
  TEACHER_API_TIMEOUT_MS: z.coerce.number().int().positive().default(10_000),
  STUDENT_SELF_REGISTER: z.string().optional().transform((value) => value !== "false")
});

const parsed = envSchema.safeParse(process.env);

if (!parsed.success) {
  console.error("Invalid environment configuration:");
  console.error(parsed.error.format());
  process.exit(1);
}

export const env = parsed.data;
