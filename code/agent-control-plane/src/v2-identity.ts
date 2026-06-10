import { createHmac, timingSafeEqual } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { IdentitySession } from "./generated/contracts-v2.js";
import { CommerceRepository } from "./v2-repository.js";

const COOKIE_NAME = "moyuan_identity";

interface IdentityToken {
  identityId: string;
  sessionId: string;
  expiresAt: string;
}

function cookies(request: IncomingMessage): Map<string, string> {
  const values = new Map<string, string>();
  for (const part of (request.headers.cookie ?? "").split(";")) {
    const index = part.indexOf("=");
    if (index < 1) continue;
    values.set(part.slice(0, index).trim(), part.slice(index + 1).trim());
  }
  return values;
}

function safeEqual(left: string, right: string): boolean {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  return a.length === b.length && timingSafeEqual(a, b);
}

export class IdentityManager {
  readonly #secret: string;

  constructor(
    private readonly repository: CommerceRepository,
    private readonly options: { secureCookie?: boolean; ttlMs?: number } = {},
  ) {
    this.#secret = repository.getOrCreateSetting("identity_signing_secret");
  }

  #sign(payload: string): string {
    return createHmac("sha256", this.#secret).update(payload).digest("base64url");
  }

  #encode(identity: IdentitySession): string {
    const token: IdentityToken = {
      identityId: identity.identityId,
      sessionId: identity.sessionId,
      expiresAt: identity.expiresAt,
    };
    const payload = Buffer.from(JSON.stringify(token), "utf8").toString("base64url");
    return `v1.${payload}.${this.#sign(payload)}`;
  }

  #decode(value: string): IdentityToken | null {
    const [version, payload, signature, extra] = value.split(".");
    if (version !== "v1" || !payload || !signature || extra !== undefined) return null;
    if (!safeEqual(signature, this.#sign(payload))) return null;
    try {
      const parsed = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as Partial<IdentityToken>;
      if (
        typeof parsed.identityId !== "string" ||
        typeof parsed.sessionId !== "string" ||
        typeof parsed.expiresAt !== "string"
      ) return null;
      return parsed as IdentityToken;
    } catch {
      return null;
    }
  }

  ensure(request: IncomingMessage, response: ServerResponse): IdentitySession {
    const raw = cookies(request).get(COOKIE_NAME);
    const token = raw ? this.#decode(raw) : null;
    const existing = token && Date.parse(token.expiresAt) > Date.now()
      ? this.repository.getIdentity(token.identityId, token.sessionId)
      : null;
    if (existing) return existing;

    const ttlMs = this.options.ttlMs ?? 180 * 24 * 60 * 60 * 1_000;
    const identity = this.repository.createIdentity(ttlMs);
    const attributes = [
      `${COOKIE_NAME}=${this.#encode(identity)}`,
      "Path=/",
      "HttpOnly",
      "SameSite=Lax",
      `Max-Age=${Math.floor(ttlMs / 1_000)}`,
      ...(this.options.secureCookie ? ["Secure"] : []),
    ];
    response.setHeader("set-cookie", attributes.join("; "));
    return identity;
  }
}

export function assertSameOrigin(request: IncomingMessage): void {
  const fetchSite = request.headers["sec-fetch-site"];
  if (fetchSite === "cross-site") throw new Error("cross_site_request_rejected");
  const origin = request.headers.origin;
  if (!origin) return;
  const host = request.headers.host;
  if (!host || new URL(origin).host !== host) throw new Error("cross_site_request_rejected");
}
