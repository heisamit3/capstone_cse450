import { Router } from "express";
import { HttpError } from "../lib/http-error";
import { prisma } from "../lib/prisma";
import { authenticate, authorize } from "../middleware/auth";
import { validate } from "../middleware/validate";
import {
  assignmentIdParamSchema,
  createAssignmentSchema,
  studentAssignmentQuerySchema,
} from "../schemas/assignment.schemas";
import { ROLE } from "../types/auth";

/**
 * Assignment Router
 *
 * Base mount path: /api/assignments
 *
 * This router is shared by teacher and student roles.
 * All routes require authentication and branch behavior by req.user.role.
 */
export const assignmentRouter = Router();

/**
 * Global middleware for this router:
 * - Ensures JWT exists and is valid.
 * - Populates req.user with id and role.
 */
assignmentRouter.use(authenticate);

/**
 * POST /
 *
 * Teacher-only endpoint to create an assignment with nested questions.
 *
 * Request body (validated by createAssignmentSchema):
 * {
 *   title: string,
 *   description?: string,
 *   total_marks: number,
 *   questions: [
 *     {
 *       question_text: string,
 *       marks: number,
 *       question_type: "mcq" | "short_answer" | "essay",
 *       model_answer: string,
 *       rubric?: string
 *     }
 *   ]
 * }
 *
 * Response:
 * - 201 with created assignment including questions.
 */
assignmentRouter.post(
  "/",
  authorize(ROLE.teacher),
  validate(createAssignmentSchema),
  async (req, res, next) => {
    try {
      const { title, description, total_marks, questions } =
        createAssignmentSchema.shape.body.parse(req.body);

      const assignment = await prisma.assignment.create({
        data: {
          teacher_id: req.user!.id,
          title,
          description,
          total_marks,
          questions: {
            create: questions,
          },
        },
        include: {
          questions: true,
        },
      });

      res.status(201).json(assignment);
    } catch (error) {
      next(error);
    }
  },
);

/**
 * GET /
 *
 * Role-dependent behavior:
 *
 * Student:
 * - Returns all assignments with student-safe question data only (id, question_text).
 * - Adds derived submission_status per assignment:
 *   - "completed": at least one submission exists for this student + assignment.
 *   - "pending": no submission exists.
 * - Supports optional query filter: ?status=pending | completed
 *
 * Teacher:
 * - Returns only assignments created by the authenticated teacher.
 * - Includes full questions relation.
 */
assignmentRouter.get(
  "/",
  validate(studentAssignmentQuerySchema),
  async (req, res, next) => {
    try {
      if (req.user!.role === ROLE.student) {
        const { status } = studentAssignmentQuerySchema.shape.query.parse(
          req.query,
        );

        const assignments = await prisma.assignment.findMany({
          include: {
            questions: {
              select: {
                id: true,
                question_text: true,
              },
            },
            submissions: {
              where: { student_id: req.user!.id },
              select: { id: true },
            },
          },
          orderBy: { created_at: "desc" },
        });

        const withStatus = assignments.map((assignment) => {
          const submission_status =
            assignment.submissions.length > 0 ? "completed" : "pending";

          return {
            id: assignment.id,
            title: assignment.title,
            description: assignment.description,
            total_marks: assignment.total_marks,
            created_at: assignment.created_at,
            updated_at: assignment.updated_at,
            questions: assignment.questions,
            submission_status,
          };
        });

        const filtered = status
          ? withStatus.filter((item) => item.submission_status === status)
          : withStatus;

        res.status(200).json(filtered);
        return;
      }

      const whereClause = { teacher_id: req.user!.id };

      const assignments = await prisma.assignment.findMany({
        where: whereClause,
        include: {
          questions: true,
        },
        orderBy: { created_at: "desc" },
      });

      res.status(200).json(assignments);
    } catch (error) {
      next(error);
    }
  },
);

/**
 * GET /:id
 *
 * Role-dependent behavior:
 *
 * Student:
 * - Returns questions with id, question_text, marks, model_answer and rubric.
 * - model_answer and rubric are exposed here on purpose: the Android client
 *   grades the worksheet on-device and needs something to grade against.
 * - The GET / list endpoint still exposes question_text only.
 *
 * Teacher:
 * - Returns full assignment details + teacher summary.
 * - Enforces ownership: teacher can only read their own assignment.
 *
 * Common errors:
 * - 404 if assignment id does not exist.
 * - 403 for teacher ownership violation.
 */
assignmentRouter.get(
  "/:id",
  validate(assignmentIdParamSchema),
  async (req, res, next) => {
    try {
      const id = Number(req.params.id);

      if (req.user!.role === ROLE.student) {
        const assignment = await prisma.assignment.findUnique({
          where: { id },
          select: {
            id: true,
            title: true,
            description: true,
            total_marks: true,
            created_at: true,
            updated_at: true,
            questions: {
              select: {
                id: true,
                question_text: true,
                marks: true,
                model_answer: true,
                rubric: true,
              },
            },
          },
        });

        if (!assignment) {
          throw new HttpError(404, "Assignment not found");
        }

        res.status(200).json(assignment);
        return;
      }

      const assignment = await prisma.assignment.findUnique({
        where: { id },
        include: {
          questions: true,
          teacher: {
            select: {
              id: true,
              email: true,
            },
          },
        },
      });

      if (!assignment) {
        throw new HttpError(404, "Assignment not found");
      }

      if (
        req.user!.role === ROLE.teacher &&
        assignment.teacher_id !== req.user!.id
      ) {
        throw new HttpError(403, "Forbidden");
      }

      res.status(200).json(assignment);
    } catch (error) {
      next(error);
    }
  },
);
