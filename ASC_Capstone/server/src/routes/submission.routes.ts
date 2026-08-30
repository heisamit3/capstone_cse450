import type { Prisma } from "@prisma/client";
import { Router } from "express";
import fs from "fs";
import multer from "multer";
import path from "path";
import { HttpError } from "../lib/http-error";
import { prisma } from "../lib/prisma";
import { authenticate, authorize } from "../middleware/auth";
import { validate } from "../middleware/validate";
import {
  createSubmissionGradeSchema,
  createSubmissionSchema,
} from "../schemas/submission.schemas";
import { ROLE } from "../types/auth";

/**
 * Submission Router
 *
 * Base mount path: /api/submissions
 *
 * Responsibilities:
 * - Student submission creation (with image uploads per question)
 * - Student self-read APIs for submitted work
 * - Recording the on-device grading result for a submission
 * - Teacher listing of submissions per assignment
 *
 * Important constraint in current implementation:
 * - Submission creation does NOT create grade rows.
 * - Grade status is derived later from grades table presence.
 */
export const submissionRouter = Router();

/**
 * Upload directory bootstrap:
 * - Ensures uploads/answers exists at server startup.
 * - Images are persisted to local disk.
 */
const uploadsRoot = path.resolve(process.cwd(), "uploads", "answers");
if (!fs.existsSync(uploadsRoot)) {
  fs.mkdirSync(uploadsRoot, { recursive: true });
}

const storage = multer.diskStorage({
  // All answer images are stored in the same folder.
  destination: (_req, _file, cb) => {
    cb(null, uploadsRoot);
  },
  // File name is randomized with timestamp to avoid collisions.
  filename: (_req, file, cb) => {
    const extension = path.extname(file.originalname) || ".jpg";
    const uniqueName = `${Date.now()}-${Math.round(Math.random() * 1e9)}${extension}`;
    cb(null, uniqueName);
  },
});

const upload = multer({
  storage,
  // Reject non-image uploads at middleware level.
  fileFilter: (_req, file, cb) => {
    if (!file.mimetype.startsWith("image/")) {
      cb(new HttpError(400, "Only image uploads are allowed"));
      return;
    }

    cb(null, true);
  },
});

/**
 * Global authentication for all submission routes.
 */
submissionRouter.use(authenticate);

/**
 * POST /
 *
 * Student-only endpoint.
 *
 * Content type:
 * - multipart/form-data
 *
 * Required form fields:
 * 1) assignment_id: number
 * 2) answers: JSON string or object array
 *    [{ question_id: number, answer_text?: string }]
 * 3) one image per question using field name pattern:
 *    image_<question_id>
 *
 * Example:
 * - assignment_id = 12
 * - answers = [{"question_id":41,"answer_text":""},{"question_id":42,"answer_text":""}]
 * - image_41 = <file>
 * - image_42 = <file>
 *
 * Validation performed:
 * - Assignment must exist.
 * - All question_id values must belong to that assignment.
 * - No duplicate answers for same question_id.
 * - Exactly one image per answer question.
 * - Only image MIME types accepted.
 *
 * Persistence:
 * - Creates one submission row.
 * - Creates one answer row per question with answer_image_path.
 * - Does not create a grade row.
 */
submissionRouter.post(
  "/",
  authorize(ROLE.student),
  upload.any(),
  async (req, res, next) => {
    try {
      if (typeof req.body.answers === "string") {
        req.body.answers = JSON.parse(req.body.answers);
      }

      validate(createSubmissionSchema)(req, res, () => undefined);

      const { assignment_id, answers } =
        createSubmissionSchema.shape.body.parse(req.body);
      const files = (req.files as Express.Multer.File[] | undefined) ?? [];

      const assignment = await prisma.assignment.findUnique({
        where: { id: assignment_id },
        include: { questions: true },
      });

      if (!assignment) {
        throw new HttpError(404, "Assignment not found");
      }

      const questionIdSet = new Set(
        assignment.questions.map((question: { id: number }) => question.id),
      );
      const hasInvalidQuestion = answers.some(
        (answer) => !questionIdSet.has(answer.question_id),
      );

      if (hasInvalidQuestion) {
        throw new HttpError(400, "Submission contains invalid question ids");
      }

      const questionIdCounts = new Map<number, number>();
      for (const answer of answers) {
        questionIdCounts.set(
          answer.question_id,
          (questionIdCounts.get(answer.question_id) ?? 0) + 1,
        );
      }

      const duplicatedQuestionId = Array.from(questionIdCounts.entries()).find(
        ([, count]) => count > 1,
      );
      if (duplicatedQuestionId) {
        throw new HttpError(
          400,
          `Duplicate answer for question id ${duplicatedQuestionId[0]}`,
        );
      }

      const filesByQuestionId = new Map<number, Express.Multer.File>();
      for (const file of files) {
        const questionId = Number(file.fieldname.replace("image_", ""));
        if (!Number.isInteger(questionId) || questionId <= 0) {
          throw new HttpError(
            400,
            "Invalid image field. Use image_<question_id>",
          );
        }

        if (filesByQuestionId.has(questionId)) {
          throw new HttpError(
            400,
            `Multiple images provided for question id ${questionId}`,
          );
        }

        filesByQuestionId.set(questionId, file);
      }

      const missingImageForQuestion = answers.find(
        (answer) => !filesByQuestionId.has(answer.question_id),
      );
      if (missingImageForQuestion) {
        throw new HttpError(
          400,
          `Missing image for question id ${missingImageForQuestion.question_id}`,
        );
      }

      const submission = await prisma.$transaction(
        async (tx: Prisma.TransactionClient) => {
          const submission = await tx.submission.create({
            data: {
              assignment_id,
              student_id: req.user!.id,
              answers: {
                create: answers.map((answer) => ({
                  question_id: answer.question_id,
                  answer_text: answer.answer_text,
                  answer_image_path: `/uploads/answers/${filesByQuestionId.get(answer.question_id)!.filename}`,
                })),
              },
            },
            include: {
              answers: true,
            },
          });

          return submission;
        },
      );

      res.status(201).json(submission);
    } catch (error) {
      next(error);
    }
  },
);

/**
 * GET /me
 *
 * Student-only endpoint.
 *
 * Returns all submissions for the authenticated student, newest first.
 * Includes:
 * - assignment summary
 * - answers
 * - grades
 * - derived status:
 *   - "pending" when grades array is empty
 *   - "completed" when grades array has at least one record
 */
submissionRouter.get("/me", authorize(ROLE.student), async (req, res, next) => {
  try {
    const submissions = await prisma.submission.findMany({
      where: { student_id: req.user!.id },
      include: {
        assignment: {
          select: {
            id: true,
            title: true,
            description: true,
            total_marks: true,
          },
        },
        answers: true,
        grades: true,
      },
      orderBy: {
        submitted_at: "desc",
      },
    });

    const response = submissions.map((submission) => ({
      ...submission,
      status: submission.grades.length > 0 ? "completed" : "pending",
    }));

    res.status(200).json(response);
  } catch (error) {
    next(error);
  }
});

/**
 * GET /me/:id
 *
 * Student-only endpoint.
 *
 * Returns one submission owned by the authenticated student.
 * Ownership is enforced in query where clause (id + student_id).
 *
 * Response includes derived status from grades existence.
 */
submissionRouter.get(
  "/me/:id",
  authorize(ROLE.student),
  async (req, res, next) => {
    try {
      const submissionId = Number(req.params.id);

      if (!Number.isInteger(submissionId) || submissionId <= 0) {
        throw new HttpError(400, "Invalid submission id");
      }

      const submission = await prisma.submission.findFirst({
        where: {
          id: submissionId,
          student_id: req.user!.id,
        },
        include: {
          assignment: {
            select: {
              id: true,
              title: true,
              description: true,
              total_marks: true,
            },
          },
          answers: true,
          grades: true,
        },
      });

      if (!submission) {
        throw new HttpError(404, "Submission not found");
      }

      res.status(200).json({
        ...submission,
        status: submission.grades.length > 0 ? "completed" : "pending",
      });
    } catch (error) {
      next(error);
    }
  },
);

/**
 * GET /assignment/:assignmentId
 *
 * Teacher-only endpoint.
 *
 * Returns all submissions for a specific assignment, only if
 * the assignment belongs to the authenticated teacher.
 *
 * Useful for teacher review and grading workflows.
 */
submissionRouter.get(
  "/assignment/:assignmentId",
  authorize(ROLE.teacher),
  async (req, res, next) => {
    try {
      const assignmentId = Number(req.params.assignmentId);

      const assignment = await prisma.assignment.findUnique({
        where: { id: assignmentId },
      });

      if (!assignment) {
        throw new HttpError(404, "Assignment not found");
      }

      if (assignment.teacher_id !== req.user!.id) {
        throw new HttpError(403, "Forbidden");
      }

      const submissions = await prisma.submission.findMany({
        where: { assignment_id: assignmentId },
        include: {
          student: {
            select: {
              id: true,
              email: true,
            },
          },
          answers: true,
          grades: true,
        },
        orderBy: {
          submitted_at: "desc",
        },
      });

      res.status(200).json(submissions);
    } catch (error) {
      next(error);
    }
  },
);

/**
 * POST /:id/grade
 *
 * Student-only endpoint.
 *
 * Records the result produced by the on-device model for one submission.
 * The server performs no grading of its own here; it validates the payload
 * and stores exactly what the phone reports.
 *
 * Request body (JSON, validated by createSubmissionGradeSchema):
 * {
 *   obtained_marks: number,
 *   feedback: string,
 *   confidence: number,                            // 0-1
 *   graded_by?: "local_model" | "frontier_api",    // defaults to "local_model"
 *   answers?: [{ question_id: number, transcription?: string }]
 * }
 *
 * Validation performed:
 * - Submission must exist and belong to the authenticated student.
 * - obtained_marks cannot exceed assignment.total_marks.
 * - Every question_id must belong to this submission's assignment.
 * - No duplicate question_id entries.
 * - Every question_id must already have an answer row on this submission.
 *
 * Persistence (single transaction):
 * - Updates answer_text of each listed answer with its transcription.
 * - Writes exactly ONE grade row per submission. Grade has no unique
 *   constraint on submission_id, so upsert is unavailable: an existing row
 *   is looked up and updated, otherwise a new one is created. Repeating the
 *   call never leaves two grades for one submission.
 *
 * Responses:
 * - 201 when the grade row was created, 200 when an existing one was updated.
 * - 400 validation failure.
 * - 403 submission belongs to another student.
 * - 404 submission does not exist.
 */
submissionRouter.post(
  "/:id/grade",
  authorize(ROLE.student),
  validate(createSubmissionGradeSchema),
  async (req, res, next) => {
    try {
      const { obtained_marks, feedback, confidence, graded_by, answers } =
        createSubmissionGradeSchema.shape.body.parse(req.body);
      const submissionId = Number(req.params.id);

      const submission = await prisma.submission.findUnique({
        where: { id: submissionId },
        include: {
          assignment: {
            select: {
              total_marks: true,
              questions: {
                select: { id: true },
              },
            },
          },
          answers: {
            select: { id: true, question_id: true },
          },
        },
      });

      if (!submission) {
        throw new HttpError(404, "Submission not found");
      }

      // Ownership is checked after existence so a wrong-owner request is
      // distinguishable from a nonexistent submission id.
      if (submission.student_id !== req.user!.id) {
        throw new HttpError(403, "Forbidden");
      }

      if (obtained_marks > submission.assignment.total_marks) {
        throw new HttpError(
          400,
          "Obtained marks cannot exceed assignment total marks",
        );
      }

      const questionIdSet = new Set(
        submission.assignment.questions.map(
          (question: { id: number }) => question.id,
        ),
      );
      const hasInvalidQuestion = answers.some(
        (answer) => !questionIdSet.has(answer.question_id),
      );

      if (hasInvalidQuestion) {
        throw new HttpError(400, "Grade contains invalid question ids");
      }

      const questionIdCounts = new Map<number, number>();
      for (const answer of answers) {
        questionIdCounts.set(
          answer.question_id,
          (questionIdCounts.get(answer.question_id) ?? 0) + 1,
        );
      }

      const duplicatedQuestionId = Array.from(questionIdCounts.entries()).find(
        ([, count]) => count > 1,
      );
      if (duplicatedQuestionId) {
        throw new HttpError(
          400,
          `Duplicate transcription for question id ${duplicatedQuestionId[0]}`,
        );
      }

      const answerIdByQuestionId = new Map<number, number>();
      for (const answer of submission.answers) {
        answerIdByQuestionId.set(answer.question_id, answer.id);
      }

      const missingAnswerForQuestion = answers.find(
        (answer) => !answerIdByQuestionId.has(answer.question_id),
      );
      if (missingAnswerForQuestion) {
        throw new HttpError(
          400,
          `No answer recorded for question id ${missingAnswerForQuestion.question_id}`,
        );
      }

      const { grade, created } = await prisma.$transaction(
        async (tx: Prisma.TransactionClient) => {
          for (const answer of answers) {
            await tx.answer.update({
              where: { id: answerIdByQuestionId.get(answer.question_id)! },
              data: { answer_text: answer.transcription },
            });
          }

          const existingGrade = await tx.grade.findFirst({
            where: { submission_id: submissionId },
          });

          if (existingGrade) {
            const updatedGrade = await tx.grade.update({
              where: { id: existingGrade.id },
              data: {
                obtained_marks,
                feedback,
                confidence,
                graded_by,
              },
            });

            return { grade: updatedGrade, created: false };
          }

          const createdGrade = await tx.grade.create({
            data: {
              assignment_id: submission.assignment_id,
              submission_id: submissionId,
              student_id: req.user!.id,
              obtained_marks,
              feedback,
              confidence,
              graded_by,
            },
          });

          return { grade: createdGrade, created: true };
        },
      );

      res.status(created ? 201 : 200).json(grade);
    } catch (error) {
      next(error);
    }
  },
);
