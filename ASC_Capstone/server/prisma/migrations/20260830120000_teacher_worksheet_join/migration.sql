-- Join to the teacher worksheet system (v-2.1.1 backend, GET /api/questions/{id}).
-- His "question" is our assignment; his "answer box" is our question.

-- AlterTable
ALTER TABLE "assignments" ADD COLUMN "external_question_id" VARCHAR(64);

-- AlterTable
ALTER TABLE "questions" ADD COLUMN "external_answer_box_id" VARCHAR(64);

-- CreateTable
CREATE TABLE "layouts" (
    "id" SERIAL NOT NULL,
    "assignment_id" INTEGER NOT NULL,
    "page_w_px" INTEGER NOT NULL,
    "page_h_px" INTEGER NOT NULL,
    "aruco_dict" VARCHAR(64) NOT NULL,
    "markers" JSONB NOT NULL,
    "answer_boxes" JSONB NOT NULL,
    "layout_version" INTEGER NOT NULL DEFAULT 1,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "layouts_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "layouts_assignment_id_key" ON "layouts"("assignment_id");

-- CreateIndex
-- Scoped to the teacher: two teachers may import the same external question.
-- Postgres treats NULLs as distinct, so locally created assignments are free.
CREATE UNIQUE INDEX "assignments_teacher_id_external_question_id_key" ON "assignments"("teacher_id", "external_question_id");

-- CreateIndex
-- The join key is the PAIR (assignment, box id), never the bare box id.
CREATE UNIQUE INDEX "questions_assignment_id_external_answer_box_id_key" ON "questions"("assignment_id", "external_answer_box_id");

-- AddForeignKey
ALTER TABLE "layouts" ADD CONSTRAINT "layouts_assignment_id_fkey" FOREIGN KEY ("assignment_id") REFERENCES "assignments"("id") ON DELETE CASCADE ON UPDATE CASCADE;
