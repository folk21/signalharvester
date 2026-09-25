package io.signalharvester.operations.application;

import io.signalharvester.operations.api.OperationalChangeRecord;
import io.signalharvester.operations.model.HealthAnomaly;
import io.signalharvester.operations.model.HealthSnapshot;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Renders a bounded human/LLM-readable Markdown report from persisted operational evidence. */
@Singleton
public final class HealthReportRenderer {
    private static final int MAX_INLINE_VALUE_LENGTH = 512;

    /** Renders one snapshot, previous-state comparison, and referenced changes without raw telemetry. */
    public String render(
            HealthSnapshot snapshot,
            HealthSnapshot previous,
            List<OperationalChangeRecord> recentChanges) {
        StringBuilder report = new StringBuilder(6144);
        report.append("# SignalHarvester Health Report\n\n")
                .append("Generated: ").append(snapshot.generatedAt()).append("\n\n")
                .append("Window: ").append(snapshot.windowStartedAt()).append(" → ")
                .append(snapshot.windowEndedAt()).append("\n\n")
                .append("Application version: `").append(inline(snapshot.applicationVersion())).append("`\n\n")
                .append("Status: **").append(snapshot.overallStatus()).append("**\n\n")
                .append("Health score: **").append(snapshot.healthScore()).append("/100**\n\n")
                .append("Policy: `").append(inline(snapshot.policyVersion())).append("`\n\n")
                .append("Evidence complete: **").append(snapshot.evidenceComplete()).append("**\n\n");

        appendPreviousComparison(report, snapshot, previous);
        appendMap(report, "Signals", snapshot.signalValues());
        appendMap(report, "Components", snapshot.componentStatuses());
        appendAnomalies(report, snapshot.anomalyDetails());

        report.append("## Recent operational changes\n\n");
        if (recentChanges.isEmpty()) {
            report.append("- No referenced recent changes.\n\n");
        } else {
            for (OperationalChangeRecord change : recentChanges) {
                report.append("- ").append(change.changedAt())
                        .append(" — `").append(change.id()).append("` — ")
                        .append(change.category())
                        .append(" ").append(change.targetType())
                        .append(" `").append(inline(change.targetId())).append("`")
                        .append(" — ").append(change.outcome())
                        .append(" via ").append(change.source())
                        .append(" by `").append(inline(change.actorId())).append("`\n")
                        .append("  - before: ").append(formatMap(change.beforeState())).append("\n")
                        .append("  - after: ").append(formatMap(change.afterState())).append("\n");
                if (!change.correlationId().isBlank()) {
                    report.append("  - correlation: `").append(inline(change.correlationId())).append("`\n");
                }
                if (!change.traceId().isBlank()) {
                    report.append("  - trace: `").append(inline(change.traceId())).append("`\n");
                }
            }
            report.append('\n');
        }

        report.append("## Uncertainty\n\n");
        if (snapshot.unknownReasons().isEmpty()) {
            report.append("- No explicit unknown-state reasons.\n");
        } else {
            snapshot.unknownReasons().forEach(value -> report.append("- `").append(inline(value)).append("`\n"));
        }
        return report.toString();
    }

    private static void appendPreviousComparison(
            StringBuilder report,
            HealthSnapshot snapshot,
            HealthSnapshot previous) {
        report.append("## Change from previous snapshot\n\n");
        if (previous == null) {
            report.append("- No earlier persisted snapshot is available.\n\n");
            return;
        }
        report.append("- Previous generated: ").append(previous.generatedAt()).append("\n")
                .append("- Status: ").append(previous.overallStatus()).append(" → ")
                .append(snapshot.overallStatus()).append("\n")
                .append("- Health score delta: ").append(snapshot.healthScore() - previous.healthScore()).append("\n");
        TreeSet<String> keys = new TreeSet<>(previous.signalValues().keySet());
        keys.retainAll(snapshot.signalValues().keySet());
        if (keys.isEmpty()) {
            report.append("- No comparable signal values.\n\n");
            return;
        }
        report.append("- Signal deltas:\n");
        for (String key : keys) {
            double delta = snapshot.signalValues().get(key) - previous.signalValues().get(key);
            if (Double.isFinite(delta)) {
                report.append("  - `").append(inline(key)).append("`: ").append(delta).append("\n");
            }
        }
        report.append('\n');
    }

    private static void appendAnomalies(StringBuilder report, List<HealthAnomaly> anomalies) {
        report.append("## Anomalies\n\n");
        if (anomalies.isEmpty()) {
            report.append("- None produced by the current policy.\n\n");
            return;
        }
        for (HealthAnomaly anomaly : anomalies) {
            report.append("- `").append(inline(anomaly.signal())).append("` — **")
                    .append(anomaly.severity()).append("** via `")
                    .append(inline(anomaly.detector())).append("`\n")
                    .append("  - observed: ").append(anomaly.observedValue()).append("\n")
                    .append("  - reference: ").append(anomaly.referenceValue() == null ? "n/a" : anomaly.referenceValue()).append("\n")
                    .append("  - anomaly score: ").append(anomaly.anomalyScore()).append("\n")
                    .append("  - evidence: ").append(inline(anomaly.evidence())).append("\n");
        }
        report.append('\n');
    }

    private static void appendMap(StringBuilder report, String title, Map<?, ?> values) {
        report.append("## ").append(title).append("\n\n");
        if (values.isEmpty()) {
            report.append("- No values captured.\n\n");
            return;
        }
        values.entrySet().stream()
                .sorted(java.util.Comparator.comparing(entry -> entry.getKey().toString()))
                .forEach(entry -> report.append("- `").append(inline(entry.getKey().toString())).append("`: ")
                        .append(inline(String.valueOf(entry.getValue()))).append("\n"));
        report.append('\n');
    }

    private static String formatMap(Map<String, String> values) {
        if (values.isEmpty()) {
            return "`{}`";
        }
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> inline(entry.getKey()) + "=" + inline(entry.getValue()))
                .collect(Collectors.joining(", ", "`{", "}`"));
    }

    private static String inline(String value) {
        String normalized = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
                .replace('`', '\'');
        if (normalized.length() <= MAX_INLINE_VALUE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_INLINE_VALUE_LENGTH) + "…";
    }
}
