import type { NextFunction, Request, Response } from "express";
import { ZodError } from "zod";
import { HttpError } from "../lib/http-error";

function isPrismaUniqueConstraintError(err: unknown): boolean {
  if (!err || typeof err !== "object") {
    return false;
  }

  const code = "code" in err ? (err as { code?: unknown }).code : undefined;
  return code === "P2002";
}

export function errorHandler(err: unknown, _req: Request, res: Response, _next: NextFunction): void {
  if (err instanceof HttpError) {
    res.status(err.statusCode).json({ error: err.message });
    return;
  }

  if (err instanceof ZodError) {
    res.status(400).json({
      error: "Validation failed",
      issues: err.issues
    });
    return;
  }

  if (isPrismaUniqueConstraintError(err)) {
    res.status(409).json({ error: "Duplicate value violates unique constraint" });
    return;
  }

  console.error(err);
  res.status(500).json({ error: "Internal server error" });
}
