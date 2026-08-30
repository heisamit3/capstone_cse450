import bcrypt from "bcrypt";
import { Router } from "express";
import rateLimit from "express-rate-limit";
import { env } from "../config/env";
import { HttpError } from "../lib/http-error";
import { prisma } from "../lib/prisma";
import { validate } from "../middleware/validate";
import { loginSchema, registerSchema } from "../schemas/auth.schemas";
import { ROLE } from "../types/auth";
import { signAccessToken } from "../utils/jwt";

/**
 * Auth Router
 *
 * Base mount path: /api/auth
 *
 * Goals:
 * 1) Register a new user (teacher or student)
 * 2) Authenticate an existing user and return a JWT access token
 *
 * Notes:
 * - Passwords are never stored directly; they are hashed with bcrypt.
 * - Login has rate limiting to reduce brute-force attacks.
 * - Returned token should be sent as: Authorization: Bearer <token>
 */
export const authRouter = Router();

/**
 * Login limiter:
 * - Window: 15 minutes
 * - Max attempts per IP in window: 10
 *
 * If threshold is exceeded, request is rejected before handler executes.
 */
const loginLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 10,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "Too many login attempts. Try again later." },
});

/**
 * POST /register
 *
 * Public endpoint.
 *
 * Expected body:
 * {
 *   email: string (valid email),
 *   password: string (minimum length enforced by schema),
 *   role: "teacher" | "student"
 * }
 *
 * Flow:
 * 1) Validate body with Zod schema.
 * 2) Optionally block student self-registration via env flag.
 * 3) Hash password.
 * 4) Create user row.
 * 5) Sign JWT and return user + token.
 */
authRouter.post(
  "/register",
  validate(registerSchema),
  async (req, res, next) => {
    try {
      const { email, password, role } = registerSchema.shape.body.parse(
        req.body,
      );

      if (!env.STUDENT_SELF_REGISTER && role === ROLE.student) {
        throw new HttpError(403, "Student self registration is disabled");
      }

      const passwordHash = await bcrypt.hash(password, 10);

      const user = await prisma.user.create({
        data: {
          email,
          password_hash: passwordHash,
          role,
        },
      });

      const token = signAccessToken(user.id, user.role);

      res.status(201).json({
        user: {
          id: user.id,
          email: user.email,
          role: user.role,
        },
        token,
      });
    } catch (error) {
      next(error);
    }
  },
);

/**
 * POST /login
 *
 * Public endpoint with rate limiting.
 *
 * Expected body:
 * {
 *   email: string,
 *   password: string
 * }
 *
 * Flow:
 * 1) Validate body.
 * 2) Fetch user by email.
 * 3) Compare plain password with stored bcrypt hash.
 * 4) On success, sign and return JWT token.
 *
 * Security behavior:
 * - Uses same "Invalid credentials" message for unknown email and wrong password
 *   to avoid account enumeration clues.
 */
authRouter.post(
  "/login",
  loginLimiter,
  validate(loginSchema),
  async (req, res, next) => {
    try {
      const { email, password } = loginSchema.shape.body.parse(req.body);

      const user = await prisma.user.findUnique({ where: { email } });
      if (!user) {
        throw new HttpError(401, "Invalid credentials");
      }

      const isValid = await bcrypt.compare(password, user.password_hash);
      if (!isValid) {
        throw new HttpError(401, "Invalid credentials");
      }

      const token = signAccessToken(user.id, user.role);

      res.status(200).json({
        user: {
          id: user.id,
          email: user.email,
          role: user.role,
        },
        token,
      });
    } catch (error) {
      next(error);
    }
  },
);
