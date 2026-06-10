import { runEvaluation } from "./evaluation.js";

const report = await runEvaluation();
console.log(JSON.stringify(report, null, 2));
if (!report.passed) process.exitCode = 1;
