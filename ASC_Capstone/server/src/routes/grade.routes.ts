import { Router } from "express";
import { HttpError } from "../lib/http-error";
import { prisma } from "../lib/prisma";
import { authenticate, authorize } from "../middleware/auth";
import { validate } from "../middleware/validate";
import { overrideGradeSchema } from "../schemas/submission.schemas";
import { ROLE } from "../types/auth";

/**
 * Grade Router
 *
 * Base mount path: /api/grades
 *
 * Responsibilities:
 * - Student can read own grades.
 * - Teacher can read grades for own assignment.
 * - Teacher can override a specific grade.
 */
export const gradeRouter = Router();

/**
 * Global authentication middleware for grade APIs.
 */
gradeRouter.use(authenticate);

/**
 * GET /me
 *
 * Student-only endpoint.
 * Returns all grade rows for authenticated student with assignment/submission context.
 */
gradeRouter.get("/me", authorize(ROLE.student), async (req, res, next) => {
  try {
    const grades = await prisma.grade.findMany({
      where: { student_id: req.user!.id },
      include: {
        assignment: true,
        submission: true,
      },
      orderBy: {
        graded_at: "desc",
      },
    });

    res.status(200).json(grades);
  } catch (error) {
    next(error);
  }
});

/**
 * GET /assignment/:assignmentId
 *
 * Teacher-only endpoint.
 * - Validates assignment existence.
 * - Enforces assignment ownership.
 * - Returns all grade rows for that assignment with student identity summary.
 */
gradeRouter.get(
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

      const grades = await prisma.grade.findMany({
        where: { assignment_id: assignmentId },
        include: {
          student: {
            select: {
              id: true,
              email: true,
            },
          },
        },
        orderBy: {
          graded_at: "desc",
        },
      });

      res.status(200).json(grades);
    } catch (error) {
      next(error);
    }
  },
);

/**
 * PATCH /:id/override
 *
 * Teacher-only endpoint.
 *
 * Allows manual override of a grade row.
 * Validation/constraints:
 * - Grade row must exist.
 * - Grade's assignment must belong to authenticated teacher.
 * - obtained_marks cannot exceed assignment.total_marks.
 *
 * Side effects on updated row:
 * - graded_by set to "teacher_override"
 * - confidence set to null
 */
gradeRouter.patch(
  "/:id/override",
  authorize(ROLE.teacher),
  validate(overrideGradeSchema),
  async (req, res, next) => {
    try {
      const { obtained_marks, feedback } = overrideGradeSchema.shape.body.parse(
        req.body,
      );
      const gradeId = Number(req.params.id);

      const grade = await prisma.grade.findUnique({
        where: { id: gradeId },
        include: {
          assignment: true,
        },
      });

      if (!grade) {
        throw new HttpError(404, "Grade not found");
      }

      if (grade.assignment.teacher_id !== req.user!.id) {
        throw new HttpError(403, "Forbidden");
      }

      if (obtained_marks > grade.assignment.total_marks) {
        throw new HttpError(
          400,
          "Obtained marks cannot exceed assignment total marks",
        );
      }

      const updatedGrade = await prisma.grade.update({
        where: { id: gradeId },
        data: {
          obtained_marks,
          feedback,
          graded_by: "teacher_override",
          confidence: null,
        },
      });

      res.status(200).json(updatedGrade);
    } catch (error) {
      next(error);
    }
  },
);
