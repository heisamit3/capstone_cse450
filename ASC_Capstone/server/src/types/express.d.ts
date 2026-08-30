import type { Role } from "./auth";
import type { Multer } from "multer";

declare global {
  namespace Express {
    interface UserContext {
      id: number;
      role: Role;
    }

    interface Request {
      user?: UserContext;
      files?: Multer.File[];
    }
  }
}

export {};
