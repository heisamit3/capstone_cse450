import { z } from "zod";

const answerInputSchema = z.object({
  question_id: z.coerce.number().int().positive(),
  answer_text: z.string().optional().default(""),
});

export const createSubmissionSchema = z.object({
  body: z.object({
    assignment_id: z.coerce.number().int().positive(),
    answers: z.array(answerInputSchema).min(1),
  }),
});

export const overrideGradeSchema = z.object({
  body: z.object({
    obtained_marks: z.number().int().nonnegative(),
    feedback: z.string().optional(),
  }),
  params: z.object({
    id: z.coerce.number().int().positive(),
  }),
});

const gradedAnswerSchema = z.object({
  question_id: z.coerce.number().int().positive(),
  transcription: z.string().optional().default(""),
});

export const createSubmissionGradeSchema = z.object({
  body: z.object({
    obtained_marks: z.number().int().nonnegative(),
    feedback: z.string(),
    confidence: z.number().min(0).max(1),
    graded_by: z.enum(["local_model", "frontier_api"]).default("local_model"),
    answers: z.array(gradedAnswerSchema).optional().default([]),
  }),
  params: z.object({
    id: z.coerce.number().int().positive(),
  }),
});
