import { closeSync, existsSync, openSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn, spawnSync } from "node:child_process";
import { createConnection } from "node:net";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const codeRoot = resolve(scriptDir, "..");
const frontendRoot = join(codeRoot, "apps", "commerce-console");
const shellScript = join(scriptDir, "test-frontend-e2e.sh");
const stackScript = join(scriptDir, "run-sar-agent.sh");

const controlPort = process.env.MOYUAN_E2E_CONTROL_PORT ?? "19190";
const dataPort = process.env.MOYUAN_E2E_DATA_PORT ?? "18183";
const baseUrl = `http://127.0.0.1:${controlPort}`;

if (process.platform !== "win32") {
  const result = await run("bash", [shellScript], {
    cwd: frontendRoot,
    env: process.env,
    stdio: "inherit",
  });
  process.exitCode = result;
} else {
  await runWindows();
}

async function runWindows() {
  const gitBash = findGitBash();
  if (!gitBash) {
    throw new Error("Git Bash is required for the offline E2E stack on Windows.");
  }
  for (const [label, rawPort] of [["control-plane", controlPort], ["data-plane", dataPort]]) {
    if (await portIsListening(Number(rawPort))) {
      throw new Error(`E2E ${label} port is already in use: 127.0.0.1:${rawPort}`);
    }
  }

  const logPath = join(tmpdir(), `buysense-e2e-${process.pid}-${Date.now()}.log`);
  const logFd = openSync(logPath, "w");
  let server;
  let cleaned = false;

  const cleanup = () => {
    if (cleaned) return;
    cleaned = true;
    if (server?.pid) {
      spawnSync("taskkill.exe", ["/PID", String(server.pid), "/T", "/F"], {
        stdio: "ignore",
        windowsHide: true,
      });
    }
    stopWindowsListener(controlPort);
    stopWindowsListener(dataPort);
  };
  const stopOnSignal = (signal) => {
    cleanup();
    process.exit(128 + (signal === "SIGINT" ? 2 : 15));
  };
  process.once("SIGINT", stopOnSignal);
  process.once("SIGTERM", stopOnSignal);

  try {
    console.log(`[e2e] Starting an isolated offline stack at ${baseUrl}`);
    server = spawn(gitBash, [toPosixPath(stackScript), "--offline"], {
      cwd: codeRoot,
      env: {
        ...process.env,
        MOYUAN_CONTROL_PORT: controlPort,
        MOYUAN_DATA_PORT: dataPort,
        MOYUAN_AGENT_MODEL_MODE: "modelport",
        MOYUAN_MODELPORT_API_KEY: "must-not-be-used-by-offline-e2e",
      },
      detached: false,
      stdio: ["ignore", logFd, logFd],
      windowsHide: true,
    });
    await waitUntilReady(server, `${baseUrl}/health/ready`, 90_000);

    console.log("[e2e] Running the engineering-workbench browser journeys");
    const playwrightCli = join(frontendRoot, "node_modules", "@playwright", "test", "cli.js");
    const status = await run(process.execPath, [playwrightCli, "test"], {
      cwd: frontendRoot,
      env: {
        ...process.env,
        NO_PROXY: "127.0.0.1,localhost",
        no_proxy: "127.0.0.1,localhost",
        MOYUAN_E2E_BASE_URL: baseUrl,
      },
      stdio: "inherit",
      windowsHide: true,
    });
    if (status !== 0) {
      console.error("Browser journey failed. Server log:");
      console.error(tail(logPath, 120));
    }
    process.exitCode = status;
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    console.error(tail(logPath, 120));
    process.exitCode = 1;
  } finally {
    cleanup();
    process.removeListener("SIGINT", stopOnSignal);
    process.removeListener("SIGTERM", stopOnSignal);
    closeSync(logFd);
    rmSync(logPath, { force: true });
  }
}

function stopWindowsListener(port) {
  const escapedRoot = codeRoot.replaceAll("'", "''");
  const command = [
    `$connection=Get-NetTCPConnection -LocalPort ${port} -State Listen -ErrorAction SilentlyContinue`,
    "if($connection){",
    "$connection.OwningProcess | Sort-Object -Unique | ForEach-Object {",
    "$process=Get-CimInstance Win32_Process -Filter \"ProcessId=$_\" -ErrorAction SilentlyContinue",
    `if($process.CommandLine -like '*${escapedRoot}*'){$_}`,
    "}",
    "}",
  ].join("; ");
  const result = spawnSync(
    "powershell.exe",
    ["-NoProfile", "-NonInteractive", "-Command", command],
    { encoding: "utf8", windowsHide: true },
  );
  for (const rawPid of result.stdout?.split(/\s+/) ?? []) {
    if (!/^\d+$/.test(rawPid)) continue;
    spawnSync("taskkill.exe", ["/PID", rawPid, "/T", "/F"], {
      stdio: "ignore",
      windowsHide: true,
    });
  }
}

function findGitBash() {
  const candidates = [
    process.env.GIT_BASH,
    "C:\\Program Files\\Git\\bin\\bash.exe",
    "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
    process.env.LOCALAPPDATA
      ? join(process.env.LOCALAPPDATA, "Programs", "Git", "bin", "bash.exe")
      : undefined,
  ];
  return candidates.find((candidate) => candidate && existsSync(candidate));
}

function toPosixPath(path) {
  return path.replaceAll("\\", "/");
}

function portIsListening(port) {
  return new Promise((resolvePort) => {
    const socket = createConnection({ host: "127.0.0.1", port });
    socket.setTimeout(500);
    socket.once("connect", () => {
      socket.destroy();
      resolvePort(true);
    });
    const unavailable = () => {
      socket.destroy();
      resolvePort(false);
    };
    socket.once("error", unavailable);
    socket.once("timeout", unavailable);
  });
}

async function waitUntilReady(server, url, timeoutMs) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    if (server.exitCode !== null) {
      throw new Error(`Offline stack exited before readiness with code ${server.exitCode}.`);
    }
    if (await healthy(url, 1_000)) return;
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 250));
  }
  throw new Error(`Offline stack did not become healthy within ${timeoutMs} ms.`);
}

async function healthy(url, timeoutMs) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { signal: controller.signal });
    return response.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(timeout);
  }
}

function run(command, args, options) {
  return new Promise((resolveRun, rejectRun) => {
    const child = spawn(command, args, options);
    child.once("error", rejectRun);
    child.once("exit", (code, signal) => {
      resolveRun(code ?? (signal ? 1 : 0));
    });
  });
}

function tail(path, lines) {
  if (!existsSync(path)) return "(no server log)";
  return readFileSync(path, "utf8").split(/\r?\n/).slice(-lines).join("\n");
}