import type { AgentRole } from "./contracts.js";
import { randomUUID } from "node:crypto";

export type ArtifactType =
  | "retrieval_plan"
  | "candidate_set"
  | "peer_handoff"
  | "revision_request"
  | "bundle_proposal"
  | "price_quote"
  | "review_evidence"
  | "cart_draft"
  | "critique"
  | "final_decision";

export interface Artifact<T = unknown> {
  artifactId: string;
  runId: string;
  parentTaskId: string | null;
  type: ArtifactType;
  producer: AgentRole;
  status: "draft" | "verified" | "vetoed";
  payload: T;
  createdAt: string;
}

export class InMemoryArtifactStore {
  readonly #items: Artifact[] = [];
  publish<T>(input: Omit<Artifact<T>, "artifactId" | "createdAt">): Artifact<T> {
    const artifact: Artifact<T> = {
      ...input,
      artifactId: `artifact-${randomUUID()}`,
      createdAt: new Date().toISOString(),
    };
    this.#items.push(artifact as Artifact);
    return artifact;
  }

  list(runId: string): Artifact[] {
    return this.#items.filter((item) => item.runId === runId);
  }
}
