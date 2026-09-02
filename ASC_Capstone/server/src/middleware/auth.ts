import type { NextFunction, Request, Response } from "express";
import { HttpError } from "../lib/http-error";
import type { Role } from "../types/auth";
import { verifyAccessToken } from "../utils/jwt";

export function authenticate(req: Request, _res: Response, next: NextFunction): void {
  const authHeader = req.header("authorization");

  if (!authHeader?.startsWith("Bearer ")) {
    next(new HttpError(401, "Missing or invalid authorization header"));
    return;
  }

  const token = authHeader.slice(7);

  try {
    const payload = verifyAccessToken(token);
    req.user = {
      id: Number(payload.sub),
      role: payload.role
    };
    next();
  } catch {
    next(new HttpError(401, "Invalid or expired token"));
  }
}

export function authorize(...roles: Role[]) {
  return (req: Request, _res: Response, next: NextFunction): void => {
    if (!req.user) {
      next(new HttpError(401, "Unauthenticated request"));
      return;
    }

    if (!roles.includes(req.user.role)) {
      next(new HttpError(403, "Forbidden"));
      return;
    }

    next();
  };
}
