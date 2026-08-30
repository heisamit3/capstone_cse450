/**
 * UNUSED. Retained for a planned server-side grading fallback and as a lexical-overlap
 * baseline for evaluation. Not wired into any route.
 */
import type { Question } from "@prisma/client";
import { env } from "../config/env";

export type GradeResult = {
  obtainedMarks: number;
  confidence: number;
  feedback: string;
  source: "local_model" | "frontier_api";
};

type AnswerInput = {
  questionId: number;
  answerText: string;
};

function tokenize(input: string): string[] {
  return input
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, " ")
    .split(/\s+/)
    .filter(Boolean);
}

function scoreAnswer(modelAnswer: string, studentAnswer: string): { ratio: number } {
  const modelTokens = tokenize(modelAnswer);
  const studentTokens = tokenize(studentAnswer);

  if (!modelTokens.length) {
    return { ratio: 0 };
  }

  const modelSet = new Set(modelTokens);
  const overlap = studentTokens.filter((token) => modelSet.has(token)).length;
  const ratio = Math.min(1, overlap / modelSet.size);

  return { ratio };
}

export function gradeSubmission(questions: Question[], answers: AnswerInput[]): GradeResult {
  let obtainedMarks = 0;
  let confidenceAccumulator = 0;

  for (const question of questions) {
    const submitted = answers.find((answer) => answer.questionId === question.id);
    if (!submitted) {
      continue;
    }

    const { ratio } = scoreAnswer(question.model_answer, submitted.answerText);
    const awarded = Math.round(question.marks * ratio);
    obtainedMarks += awarded;
    confidenceAccumulator += ratio;
  }

  const answeredCount = answers.length || 1;
  const confidence = Number((confidenceAccumulator / answeredCount).toFixed(2));
  const source = confidence >= env.CONFIDENCE_THRESHOLD ? "local_model" : "frontier_api";

  return {
    obtainedMarks,
    confidence,
    source,
    feedback:
      source === "local_model"
        ? "Auto-graded on confidence threshold."
        : "Auto-graded with cloud fallback due to low confidence."
  };
}
