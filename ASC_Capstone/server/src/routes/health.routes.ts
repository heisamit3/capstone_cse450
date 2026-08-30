import { Router } from "express";

/**
 * Health Router
 *
 * Base mount path: /api
 *
 * This endpoint is intentionally lightweight and unauthenticated.
 * It is used by infrastructure or clients to quickly verify that
 * the HTTP server process is running and reachable.
 */
export const healthRouter = Router();

/**
 * GET /health
 *
 * Response: { status: "ok" }
 */
healthRouter.get("/health", (_req, res) => {
  res.status(200).json({ status: "ok" });
});
