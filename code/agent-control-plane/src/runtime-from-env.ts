import {
  ModelPortPiRuntimeFactory,
  ReplayPiRuntimeFactory,
  type PiRuntimeFactory,
} from "./pi-runtime.js";

export function runtimeFromEnvironment(
  environment: NodeJS.ProcessEnv = process.env,
): PiRuntimeFactory {
  const mode = environment.MOYUAN_AGENT_MODEL_MODE ?? "replay";
  if (mode === "modelport") return new ModelPortPiRuntimeFactory(environment);
  if (mode === "replay") return new ReplayPiRuntimeFactory();
  throw new Error("MOYUAN_AGENT_MODEL_MODE must be replay or modelport");
}
