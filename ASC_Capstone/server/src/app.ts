import cors from "cors";
import express from "express";
import helmet from "helmet";
import path from "path";
import { assignmentRouter } from "./routes/assignment.routes";
import { authRouter } from "./routes/auth.routes";
import { gradeRouter } from "./routes/grade.routes";
import { healthRouter } from "./routes/health.routes";
import { submissionRouter } from "./routes/submission.routes";
import { errorHandler } from "./middleware/error-handler";
import { notFoundHandler } from "./middleware/not-found";

export const app = express();

app.use(helmet());
app.use(cors());
app.use(express.json({ limit: "2mb" }));
app.use("/uploads", express.static(path.resolve(process.cwd(), "uploads")));

app.use("/api", healthRouter);
app.use("/api/auth", authRouter);
app.use("/api/assignments", assignmentRouter);
app.use("/api/submissions", submissionRouter);
app.use("/api/grades", gradeRouter);

app.use(notFoundHandler);
app.use(errorHandler);
