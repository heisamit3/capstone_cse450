export class HttpError extends Error {
  readonly statusCode: number;
  /**
   * Optional structured payload merged into the JSON error response alongside
   * `error`. Used where naming the problem is not enough and the caller needs
   * the specifics to act - e.g. a refused re-import reporting exactly which
   * answer box ids appeared and disappeared.
   */
  readonly details?: Record<string, unknown>;

  constructor(
    statusCode: number,
    message: string,
    details?: Record<string, unknown>,
  ) {
    super(message);
    this.statusCode = statusCode;
    this.details = details;
  }
}
