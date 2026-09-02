import type { ZodTypeAny } from "zod";
import type { NextFunction, Request, Response } from "express";

export function validate(schema: ZodTypeAny) {
  return (req: Request, _res: Response, next: NextFunction): void => {
    schema.parse({
      body: req.body,
      params: req.params,
      query: req.query
    });

    next();
  };
}
