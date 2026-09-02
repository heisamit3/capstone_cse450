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

/**
 * Body for POST /api/assignments/import.
 *
 * Everything else - the questions, the geometry, the page size - is fetched
 * from the teacher worksheet system, never posted by the client. `title` and
 * `description` are the only things the teacher API has no equivalent for.
 */
export const importAssignmentSchema = z.object({
  body: z.object({
    // uuid4 minted by the teacher server. Not validated as a uuid on purpose:
    // it is his primary key and nothing here should decide what it may look
    // like. Length-capped to match the VarChar(64) column.
    external_question_id: z.string().min(1).max(64),
    title: z.string().min(3).max(255).optional(),
    description: z.string().optional(),
  }),
});
