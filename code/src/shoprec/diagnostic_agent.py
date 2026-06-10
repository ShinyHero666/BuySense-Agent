from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any

from .service import CommerceDiscoveryService
from .validation import ValidationError


@dataclass(frozen=True)
class DiagnosticFinding:
    severity: str
    stage: str
    evidence: dict[str, Any]
    hypothesis: str
    next_experiment: str


@dataclass(frozen=True)
class DiagnosticReport:
    request_id: str
    status: str
    summary: str
    findings: tuple[DiagnosticFinding, ...]
    read_only: bool = True

    def to_dict(self) -> dict[str, Any]:
        result = asdict(self)
        result["findings"] = [asdict(item) for item in self.findings]
        return result


class SearchRecDiagnosticAgent:
    """Advanced, read-only Agent that turns an existing pipeline trace into experiments."""

    def __init__(self, service: CommerceDiscoveryService) -> None:
        self.service = service

    @staticmethod
    def diagnose_result(result: dict[str, Any]) -> DiagnosticReport:
        if not isinstance(result, dict):
            raise ValidationError("result", "must be an object")
        trace = result.get("trace")
        if not isinstance(trace, list):
            raise ValidationError("result.trace", "must be an array")
        findings: list[DiagnosticFinding] = []
        total = int(result.get("total", len(result.get("items", []))))
        query = result.get("query_info", {})
        if isinstance(query, dict) and query.get("blocked"):
            findings.append(
                DiagnosticFinding(
                    "info",
                    "QUERY",
                    {"reasons": query.get("reasons", [])},
                    "风险 Query 在召回前被拦截，当前空结果属于安全策略命中。",
                    "回放风险词和相邻正常词，验证误杀率而不是放松安全规则。",
                )
            )
        if total == 0 and not (isinstance(query, dict) and query.get("blocked")):
            reasons = result.get("filter_reasons", {})
            dominant = None
            if isinstance(reasons, dict) and reasons:
                dominant = max(reasons.items(), key=lambda item: item[1])
            findings.append(
                DiagnosticFinding(
                    "high",
                    "FILTER",
                    {"total": total, "dominant_filter_reason": dominant},
                    "候选在召回或硬过滤后耗尽；应先定位数量漏斗，不能直接调排序权重。",
                    "保持同一 Query，逐项移除非安全过滤器，比较召回量和硬约束违反数。",
                )
            )
        for record in trace:
            if not isinstance(record, dict):
                continue
            elapsed = float(record.get("elapsed_ms", 0.0))
            if elapsed >= 50:
                findings.append(
                    DiagnosticFinding(
                        "medium",
                        str(record.get("name", "UNKNOWN")),
                        {"elapsed_ms": elapsed},
                        "该阶段占用较高延迟，需要拆分计算、IO 与候选规模影响。",
                        "固定输入候选，分别压测阶段实现与候选上限，报告 P50/P95 而非单次值。",
                    )
                )
            before = int(record.get("input_count", 0))
            after = int(record.get("output_count", 0))
            if before >= 10 and after == 0:
                findings.append(
                    DiagnosticFinding(
                        "high",
                        str(record.get("name", "UNKNOWN")),
                        {"input_count": before, "output_count": after},
                        "本阶段发生 100% 候选损失，是空结果的直接证据。",
                        "按理由码分层回放，并对每项硬过滤编写边界用例。",
                    )
                )
        items = result.get("items", [])
        if isinstance(items, list) and len(items) >= 3:
            sellers = {item.get("seller_id") for item in items if isinstance(item, dict)}
            models = {item.get("model_name") for item in items if isinstance(item, dict)}
            if len(sellers) == 1 or (None not in models and len(models) == 1):
                findings.append(
                    DiagnosticFinding(
                        "low",
                        "RERANK",
                        {"item_count": len(items), "seller_count": len(sellers), "model_count": len(models)},
                        "列表多样性偏低，可能放大单一供给或单一型号。",
                        "只修改多样性重排参数，观察完成率、硬约束和列表内多样性，避免与召回实验混跑。",
                    )
                )
        if not findings:
            findings.append(
                DiagnosticFinding(
                    "info",
                    "END_TO_END",
                    {"total": total, "trace_stages": len(trace)},
                    "未发现规则可识别的明显异常。",
                    "建立同 Query 的基准回放，再单变量测试召回、工具契约或提示版本。",
                )
            )
        high = sum(item.severity == "high" for item in findings)
        return DiagnosticReport(
            request_id=str(result.get("request_id", "")),
            status="needs_attention" if high else "observed",
            summary=f"基于 {len(trace)} 个阶段生成 {len(findings)} 条只读诊断，其中高优先级 {high} 条。",
            findings=tuple(findings),
        )

    def diagnose_search(self, payload: dict[str, Any]) -> DiagnosticReport:
        # Search is the only operation. This agent exposes no event, shortlist,
        # valuation, order, payment, configuration or deployment write surface.
        return self.diagnose_result(self.service.search(payload))
