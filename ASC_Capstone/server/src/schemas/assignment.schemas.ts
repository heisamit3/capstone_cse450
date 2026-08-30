import { z } from "zod";

const QUESTION_TYPES = ["mcq", "short_answer", "essay"] as const;

const questionSchema = z.object({
  question_text: z.string().min(3),
  marks: z.number().int().positive(),
  question_type: z.enum(QUESTION_TYPES),
  model_answer: z.string().min(1),
  rubric: z.string().optional(),
});

export const createAssignmentSchema = z.object({
  body: z.object({
    title: z.string().min(3),
    description: z.string().optional(),
    total_marks: z.number().int().positive(),
    questions: z.array(questionSchema).min(1),
  }),
});

export const assignmentIdParamSchema = z.object({
  params: z.object({
    id: z.coerce.number().int().positive(),
  }),
});

export const studentAssignmentQuerySchema = z.object({
  query: z.object({
    status: z.enum(["pending", "completed"]).optional(),
  }),
});
