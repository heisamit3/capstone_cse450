import { Router } from "express";
import { HttpError } from "../lib/http-error";
import { prisma } from "../lib/prisma";
import { authenticate, authorize } from "../middleware/auth";
import { validate } from "../middleware/validate";
import {
  assignmentIdParamSchema,
  createAssignmentSchema,
  importAssignmentSchema,
  studentAssignmentQuerySchema,
} from "../schemas/assignment.schemas";
import {
  LAYOUT_VERSION,
  fetchTeacherQuestion,
  toImportableLayout,
} from "../services/teacher-worksheet.service";
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
            // Half of the join key. An answer box id means nothing without the
            // question it belongs to, so the client is never handed one alone.
            external_question_id: true,
            questions: {
              select: {
                id: true,
                question_text: true,
                marks: true,
                model_answer: true,
                rubric: true,
                // The half of the join key that lives on the row. The other
                // half is the assignment this response already is - the client
                // matches on the pair, never on the box id alone.
                external_answer_box_id: true,
              },
              orderBy: { id: "asc" },
            },
            // Null for any assignment not created by the import route. The
            // Android client reads its geometry from here and computes none of
            // its own.
            layout: {
              select: {
                page_w_px: true,
                page_h_px: true,
                aruco_dict: true,
                markers: true,
                answer_boxes: true,
                layout_version: true,
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

/**
 * POST /import
 *
 * Teacher-only. Imports one finalized question from the teacher worksheet
 * system and builds the join between his printed page and this database.
 *
 *   his "question"   -> our Assignment  (external_question_id)
 *   his "answer box" -> our Question    (external_answer_box_id)
 *
 * He serves geometry only: page size, bounding boxes, page indices, ArUco
 * marker placement. He serves no model answer and no rubric, and his `points`
 * is the only marks figure that exists at import time. So an import produces
 * gradeable *shells*: every created Question has model_answer = "" and
 * rubric = null, and grading must refuse to run against a blank model answer.
 * The teacher fills those in here afterwards.
 *
 * Request body:
 * {
 *   external_question_id: string,   // his uuid4
 *   title?: string,                 // he has no title field
 *   description?: string
 * }
 *
 * Responses:
 * - 201 assignment + questions + layout, on first import.
 * - 200 the existing assignment, when re-importing a question whose answer box
 *   id set is unchanged. Question rows are left alone (they hold marking data
 *   the teacher typed); only the layout geometry is refreshed.
 * - 409 with `added` / `removed` / `unchanged` id lists, when the box id set
 *   differs from what is stored. Re-linking is refused, never guessed: a
 *   changed id set means boxes were deleted and re-minted on his side, and
 *   silently re-pointing this database's marking data at them would attach the
 *   wrong model answer to the wrong rectangle with no signal.
 * - 400 when his question is not finalized, has no boxes, no page size, a
 *   duplicate box id, or a box with no bbox.
 * - 404 when he does not know that question id.
 * - 502 when he is unreachable or answers with an unrecognised shape.
 */
assignmentRouter.post(
  "/import",
  authorize(ROLE.teacher),
  validate(importAssignmentSchema),
  async (req, res, next) => {
    try {
      const { external_question_id, title, description } =
        importAssignmentSchema.shape.body.parse(req.body);

      const teacherQuestion = await fetchTeacherQuestion(external_question_id);
      const layout = toImportableLayout(teacherQuestion);
      const teacherId = req.user!.id;

      // One transaction: either the assignment, all of its questions and its
      // layout row exist together, or none of them do. A half-imported
      // assignment - questions with no layout, or a layout with no questions -
      // would be indistinguishable from a locally created one and would fail
      // much later, on a phone, in front of a student.
      const outcome = await prisma.$transaction(async (tx) => {
        const existing = await tx.assignment.findUnique({
          where: {
            teacher_id_external_question_id: {
              teacher_id: teacherId,
              external_question_id,
            },
          },
          include: { questions: { orderBy: { id: "asc" } }, layout: true },
        });

        if (existing) {
          const storedIds = new Set(
            existing.questions
              .map((question) => question.external_answer_box_id)
              .filter((id): id is string => id !== null),
          );
          const servedIds = new Set(layout.boxes.map((box) => box.id));

          const added = [...servedIds].filter((id) => !storedIds.has(id));
          const removed = [...storedIds].filter((id) => !servedIds.has(id));

          if (added.length > 0 || removed.length > 0) {
            throw new HttpError(
              409,
              "Re-import refused: the answer box id set for teacher question " +
                external_question_id +
                " no longer matches assignment " +
                existing.id +
                ". Answer box ids are hard-deleted and re-minted on the teacher" +
                " side, so a changed set cannot be re-linked without guessing" +
                " which stored model answer belongs to which new rectangle.",
              {
                assignment_id: existing.id,
                added,
                removed,
                unchanged: [...servedIds].filter((id) => storedIds.has(id)),
              },
            );
          }

          // Same boxes, same links. Refresh the geometry only. Question rows
          // are untouched: they carry model_answer, rubric and marks, which the
          // teacher entered here and which the teacher API cannot supply.
          const refreshed = await tx.layout.upsert({
            where: { assignment_id: existing.id },
            create: {
              assignment_id: existing.id,
              page_w_px: layout.pageWidthPx,
              page_h_px: layout.pageHeightPx,
              aruco_dict: layout.markers.aruco_dict,
              markers: layout.markers,
              answer_boxes: layout.boxes,
              layout_version: LAYOUT_VERSION,
            },
            update: {
              page_w_px: layout.pageWidthPx,
              page_h_px: layout.pageHeightPx,
              aruco_dict: layout.markers.aruco_dict,
              markers: layout.markers,
              answer_boxes: layout.boxes,
              layout_version: LAYOUT_VERSION,
            },
          });

          return {
            created: false,
            assignment: { ...existing, layout: refreshed },
          };
        }

        const assignment = await tx.assignment.create({
          data: {
            teacher_id: teacherId,
            title: title ?? "Imported worksheet " + external_question_id,
            description,
            // His per-box `points` summed. The only marks figure in existence
            // at import time; the teacher edits it here afterwards.
            total_marks: layout.boxes.reduce((sum, box) => sum + box.points, 0),
            external_question_id,
          },
        });

        // Created one at a time, in served array order, so that the ascending
        // question id order this API reads back is the teacher document order.
        // A nested create array would very probably do the same thing, but
        // "very probably" is not a property to build a grading join on.
        for (const box of layout.boxes) {
          await tx.question.create({
            data: {
              assignment_id: assignment.id,
              // Placeholder. His `label` is free text typed by hand, defaults
              // to "", and is NOT the prompt. The prompt can only be guessed
              // positionally out of his TipTap tree, and a wrong guess yields a
              // confidently wrong mark against the wrong question with no
              // signal (audit 2.7) - so nothing is guessed here.
              question_text:
                box.label.trim().length > 0
                  ? box.label.trim()
                  : "Answer box " + box.id,
              marks: box.points,
              // He has no notion of question type. short_answer is this
              // system's neutral default, not a fact about his worksheet.
              question_type: "short_answer",
              // NOT NULL in the schema, and he serves nothing for it. Empty
              // means "not yet supplied"; grading must refuse a blank.
              model_answer: "",
              rubric: null,
              external_answer_box_id: box.id,
            },
          });
        }

        const created = await tx.assignment.findUniqueOrThrow({
          where: { id: assignment.id },
          include: { questions: { orderBy: { id: "asc" } } },
        });

        const layoutRow = await tx.layout.create({
          data: {
            assignment_id: assignment.id,
            page_w_px: layout.pageWidthPx,
            page_h_px: layout.pageHeightPx,
            aruco_dict: layout.markers.aruco_dict,
            markers: layout.markers,
            answer_boxes: layout.boxes,
            layout_version: LAYOUT_VERSION,
          },
        });

        return { created: true, assignment: { ...created, layout: layoutRow } };
      });

      res.status(outcome.created ? 201 : 200).json({
        ...outcome.assignment,
        import: {
          created: outcome.created,
          external_question_id,
          // max(page_index) + 1. Undercounts a trailing page carrying no answer
          // box, because he never serializes page_count (audit 2.2).
          page_count: layout.pageCount,
          marker_source: layout.markers.source,
          // Question ids still carrying no model answer. Non-empty on every
          // first import - that is the expected state, not an error.
          questions_awaiting_marking_data: outcome.assignment.questions
            .filter((question) => question.model_answer.trim().length === 0)
            .map((question) => question.id),
        },
      });
    } catch (error) {
      next(error);
    }
  },
);
