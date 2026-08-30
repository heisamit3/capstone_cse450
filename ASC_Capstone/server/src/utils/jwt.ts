import jwt from "jsonwebtoken";
import { env } from "../config/env";
import type { Role } from "../types/auth";

type AuthPayload = {
  sub: string;
  role: Role;
};

export function signAccessToken(userId: number, role: Role): string {
  const payload: AuthPayload = {
    sub: String(userId),
    role
  };

  return jwt.sign(payload, env.JWT_SECRET, {
    expiresIn: env.JWT_EXPIRES_IN as jwt.SignOptions["expiresIn"]
  });
}

export function verifyAccessToken(token: string): AuthPayload {
  return jwt.verify(token, env.JWT_SECRET) as AuthPayload;
}
