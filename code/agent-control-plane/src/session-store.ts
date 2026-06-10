import type { CartDraft, SearchAdsRecsReply } from "./contracts.js";

export interface PendingDecision {
  sessionId: string;
  userId: string;
  decision: SearchAdsRecsReply;
  expiresAtMs: number;
}

export interface PendingDecisionStore {
  put(sessionId: string, userId: string, decision: SearchAdsRecsReply): void;
  get(sessionId: string, userId: string): PendingDecision | null;
  delete(sessionId: string): void;
}

export interface CartDraftStore {
  put(draft: CartDraft): void;
  get(draftId: string): CartDraft | null;
}

export class InMemoryPendingDecisionStore implements PendingDecisionStore {
  readonly #items = new Map<string, PendingDecision>();

  constructor(
    private readonly now: () => number = Date.now,
    private readonly ttlMs = 15 * 60 * 1_000,
  ) {}

  put(sessionId: string, userId: string, decision: SearchAdsRecsReply): void {
    this.#items.set(sessionId, {
      sessionId,
      userId,
      decision,
      expiresAtMs: this.now() + this.ttlMs,
    });
  }

  get(sessionId: string, userId: string): PendingDecision | null {
    const item = this.#items.get(sessionId);
    if (!item) return null;
    if (item.expiresAtMs <= this.now() || item.userId !== userId) {
      this.#items.delete(sessionId);
      return null;
    }
    return item;
  }

  delete(sessionId: string): void {
    this.#items.delete(sessionId);
  }
}

export class InMemoryCartDraftStore implements CartDraftStore {
  readonly #items = new Map<string, CartDraft>();

  constructor(private readonly now: () => number = Date.now) {}

  put(draft: CartDraft): void {
    this.#items.set(draft.draftId, draft);
  }

  get(draftId: string): CartDraft | null {
    const draft = this.#items.get(draftId) ?? null;
    if (!draft) return null;
    if (Date.parse(draft.expiresAt) <= this.now()) {
      this.#items.delete(draftId);
      return null;
    }
    return draft;
  }
}
