import { z } from "zod";
import { env } from "../config/env";
import { HttpError } from "../lib/http-error";

/**
 * Teacher Worksheet Service
 *
 * The only place in this codebase that talks to the teacher worksheet system
 * (v-2.1.1 backend, FastAPI, unauthenticated). That system owns the printed
 * page: canvas size, ArUco markers, and the bounding box of every answer box.
 * It owns no marking data at all - no model answer, no rubric, no marks.
 * Those live here, in Postgres, and are filled in after import.
 *
 * Vocabulary, once, because the two systems disagree:
 *
 *   teacher "question"   == our Assignment  (Assignment.external_question_id)
 *   teacher "answer box" == our Question    (Question.external_answer_box_id)
 *
 * See INTEGRATION_AUDIT.md for the evidence behind every claim below.
 */

/** Shape served by GET /api/questions/{id}. Key set VERIFIED over HTTP, audit 2.1. */
const answerBoxSchema = z.object({
  id: z.string().min(1),
  label: z.string().default(""),
  points: z.number().int().default(1),
  // null while the question is a draft; an int quad once finalized.
  bbox: z.array(z.number()).length(4).nullable().default(null),
  page_index: z.number().int().nullable().default(null),
});

/**
 * The marker contract, if the teacher API ever serves one.
 *
 * It does not today: searching the live response for any key matching
 * /marker|aruco/ returns zero hits (audit 2.3). This schema exists so that the
 * day he adds it, resolveMarkers starts preferring the served value with no
 * other change. render_finalized_question already computes exactly this and
 * throws it away after writing the PDF.
 */
const servedMarkersSchema = z.object({
  aruco_dict: z.string().min(1),
  marker_size_px: z.number().int().positive(),
  marker_margin_px: z.number().int().nonnegative(),
  centres: z.record(z.string(), z.array(z.number()).length(2)),
});

export const teacherQuestionSchema = z
  .object({
    question_id: z.string().min(1),
    state: z.string(),
    physical_page: z.string().default("A4"),
    dpi: z.number().int().positive(),
    content: z.unknown().nullable().default(null),
    answer_boxes: z.array(answerBoxSchema),
    page_w_px: z.number().int().positive().nullable().default(null),
    page_h_px: z.number().int().positive().nullable().default(null),
    // Serialized as null even when the DB row holds a number - _question_to_out
    // never passes it (audit 2.2). Never trust it; infer from page_index.
    page_count: z.number().int().nullable().default(null),
    derived_from: z.string().nullable().default(null),
    created_at: z.string().default(""),
    finalized_at: z.string().nullable().default(null),
    // Absent today. See servedMarkersSchema.
    markers: servedMarkersSchema.nullish(),
  })
  // He may add fields (order_index, page_count, markers) without telling us.
  // Unknown keys must not break the import.
  .passthrough();

export type TeacherQuestion = z.infer<typeof teacherQuestionSchema>;
export type TeacherAnswerBox = z.infer<typeof answerBoxSchema>;

/** Current shape of Layout.markers / Layout.answer_boxes. Bump both together. */
export const LAYOUT_VERSION = 1;

export type MarkerContract = {
  aruco_dict: string;
  marker_size_px: number;
  marker_margin_px: number;
  /** Marker id (as a string key) -> [x, y] centre in canonical page pixels. */
  centres: Record<string, number[]>;
  source: "served" | "computed_from_constants";
};

/**
 * TEMPORARY. Delete this function the moment the teacher API serves markers.
 *
 * The teacher API does not publish the marker geometry, so the four centres are
 * recomputed here from constants copied out of his config.py. That makes them a
 * convention, not a contract: ARUCO_DICT, MARKER_SIZE_PX and MARKER_MARGIN_PX
 * are all pydantic-settings fields with env_file=".env" on his side, so any
 * deployment can change them and nothing anywhere would say so. A mismatch does
 * not fail loudly - detection still reports 4/4 markers and a "homography"
 * transform, and every crop is silently displaced by roughly the size of the
 * change (audit 2.3, run J: 11 px for a 10 px constant change, 990 px for
 * getting the marker order wrong).
 *
 * It is computed HERE, once, at import, and stored on the layout row - never on
 * the phone. The Android client must never compute geometry: if these constants
 * are wrong we want one wrong row we can find and fix, not a wrong assumption
 * baked into every installed APK.
 *
 * Migration path, in order:
 *   today       markers = TEMPORARY_markerCentresFromTeacherConstants(...)
 *   he adds it  markers = served ?? TEMPORARY_markerCentresFromTeacherConstants(...)
 *   after that  markers = served                    <- delete this function
 *
 * resolveMarkers is already written as the middle step.
 */
export function TEMPORARY_markerCentresFromTeacherConstants(
  pageWidthPx: number,
  pageHeightPx: number,
): MarkerContract {
  // Copied verbatim from v-2.1.1/backend/config.py. Byte-identical to the same
  // three constants in mobile_Extract (audit 1.3).
  const ARUCO_DICT = "DICT_4X4_50";
  const MARKER_SIZE_PX = 60;
  const MARKER_MARGIN_PX = 40;

  // His get_marker_positions uses integer floor division: m + s // 2. A 60 px
  // marker drawn at x=40 spans pixels 40..99, true centre 69.5, and he reports
  // 70. Be bug-compatible on purpose - matching him costs nothing, being
  // "correct" costs 0.66 px of disagreement with the thing we register against
  // (audit 2.3, run J).
  const half = Math.floor(MARKER_SIZE_PX / 2);
  const near = MARKER_MARGIN_PX + half;
  const right = pageWidthPx - MARKER_MARGIN_PX - half;
  const bottom = pageHeightPx - MARKER_MARGIN_PX - half;

  return {
    aruco_dict: ARUCO_DICT,
    marker_size_px: MARKER_SIZE_PX,
    marker_margin_px: MARKER_MARGIN_PX,
    // ROW-MAJOR: 0 top-left, 1 top-right, 2 BOTTOM-left, 3 bottom-right.
    // Not clockwise. Assuming clockwise mirrors the homography and costs
    // 990 px while still reporting 4/4 and "homography" (audit 2.3, run J).
    centres: {
      "0": [near, near],
      "1": [right, near],
      "2": [near, bottom],
      "3": [right, bottom],
    },
    source: "computed_from_constants",
  };
}

/**
 * Prefers the marker contract the teacher API served; falls back to computing
 * it from his constants. Logs which branch ran, every time, so that the day the
 * fallback stops being used it is visible in the log rather than inferred.
 */
export function resolveMarkers(
  question: TeacherQuestion,
  pageWidthPx: number,
  pageHeightPx: number,
): MarkerContract {
  if (question.markers) {
    console.info(
      "[import] markers: SERVED by teacher API for question " +
        question.question_id +
        " (dict=" + question.markers.aruco_dict +
        ", size=" + question.markers.marker_size_px +
        ", margin=" + question.markers.marker_margin_px + ")" +
        " -- TEMPORARY_markerCentresFromTeacherConstants can now be deleted",
    );
    return { ...question.markers, source: "served" };
  }

  const computed = TEMPORARY_markerCentresFromTeacherConstants(
    pageWidthPx,
    pageHeightPx,
  );
  console.warn(
    "[import] markers: COMPUTED FROM HARDCODED CONSTANTS for question " +
      question.question_id +
      " (dict=" + computed.aruco_dict +
      ", size=" + computed.marker_size_px +
      ", margin=" + computed.marker_margin_px +
      ", page=" + pageWidthPx + "x" + pageHeightPx + ")" +
      " -- the teacher API served no markers field; these values are unchecked" +
      " and a mismatch is silent",
  );
  return computed;
}

/**
 * Fetches one question from the teacher worksheet system.
 *
 * Throws HttpError with a status this API can return directly:
 * - 404 when the teacher server does not know that question id
 * - 502 when it is unreachable, times out, or answers with something that is
 *   not the shape audited in INTEGRATION_AUDIT.md 2.1
 */
export async function fetchTeacherQuestion(
  externalQuestionId: string,
): Promise<TeacherQuestion> {
  const base = env.TEACHER_API_BASE_URL.replace(/\/+$/, "");
  const url = base + "/api/questions/" + encodeURIComponent(externalQuestionId);

  let response: Response;
  try {
    response = await fetch(url, {
      headers: { accept: "application/json" },
      signal: AbortSignal.timeout(env.TEACHER_API_TIMEOUT_MS),
    });
  } catch (error) {
    throw new HttpError(
      502,
      "Teacher worksheet service unreachable at " +
        url +
        ": " +
        (error as Error).message,
    );
  }

  if (response.status === 404) {
    throw new HttpError(
      404,
      "Teacher worksheet service has no question " + externalQuestionId,
    );
  }

  if (!response.ok) {
    throw new HttpError(
      502,
      "Teacher worksheet service returned " +
        response.status +
        " for question " +
        externalQuestionId,
    );
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new HttpError(
      502,
      "Teacher worksheet service returned a non-JSON body for question " +
        externalQuestionId,
    );
  }

  const parsed = teacherQuestionSchema.safeParse(body);
  if (!parsed.success) {
    throw new HttpError(
      502,
      "Teacher worksheet service returned an unrecognised question shape: " +
        parsed.error.issues
          .map((issue) => issue.path.join(".") + ": " + issue.message)
          .join("; "),
    );
  }

  return parsed.data;
}

/** One answer box, validated and with its array position captured. */
export type ImportableBox = TeacherAnswerBox & {
  bbox: number[];
  page_index: number;
  order_index: number;
};

/**
 * Everything the import route needs, derived from one served question and
 * validated as a set. Import is refused rather than half-done.
 */
export type ImportableLayout = {
  pageWidthPx: number;
  pageHeightPx: number;
  markers: MarkerContract;
  /** Served array order preserved; this IS the ordering (audit 2.4). */
  boxes: ImportableBox[];
  /** max(page_index) + 1. Undercounts a trailing page with no box (audit 2.2). */
  pageCount: number;
};

/**
 * Validates that a served question can actually be imported, and normalises it.
 *
 * Refuses (400) rather than importing something partial:
 * - a draft, whose bbox values are all null and whose box ids are still
 *   churning on every autosave (audit 2.5)
 * - a question with no answer boxes - there would be nothing to grade
 * - a missing page size, which every marker centre is computed from
 * - a duplicate box id inside one question
 * - any box with a null bbox or null page_index
 */
export function toImportableLayout(question: TeacherQuestion): ImportableLayout {
  if (question.state !== "finalized") {
    throw new HttpError(
      400,
      "Teacher question " +
        question.question_id +
        ' is "' +
        question.state +
        '", not "finalized". Only a finalized question has fixed geometry and' +
        " stable answer box ids.",
    );
  }

  if (question.answer_boxes.length === 0) {
    throw new HttpError(
      400,
      "Teacher question " +
        question.question_id +
        " has no answer boxes; there would be nothing to grade.",
    );
  }

  if (question.page_w_px === null || question.page_h_px === null) {
    throw new HttpError(
      400,
      "Teacher question " +
        question.question_id +
        " has no page size; marker geometry cannot be established.",
    );
  }

  const seen = new Set<string>();
  const boxes: ImportableBox[] = question.answer_boxes.map((box, index) => {
    if (seen.has(box.id)) {
      throw new HttpError(
        400,
        "Teacher question " +
          question.question_id +
          ' serves answer box id "' +
          box.id +
          '" more than once.',
      );
    }
    seen.add(box.id);

    if (box.bbox === null || box.page_index === null) {
      throw new HttpError(
        400,
        'Answer box "' +
          box.id +
          '" has no bbox or page_index; it cannot be located on the page.',
      );
    }

    return {
      ...box,
      bbox: box.bbox,
      page_index: box.page_index,
      // Array position, captured at ingest and stored. His AnswerBoxOut omits
      // order_index, but the relation is order_by=AnswerBox.order_index, so
      // array position IS order_index and IS document order (audit 2.4,
      // VERIFIED run D). Capture it now rather than re-deriving it later from
      // an array whose order every layer in between must have preserved.
      order_index: index,
    };
  });

  return {
    pageWidthPx: question.page_w_px,
    pageHeightPx: question.page_h_px,
    markers: resolveMarkers(question, question.page_w_px, question.page_h_px),
    boxes,
    pageCount: Math.max(...boxes.map((box) => box.page_index)) + 1,
  };
}
