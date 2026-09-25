package io.signalharvester.operations.assisted.tools;

/** Stable allowlisted read-only tool names exposed to assisted-investigation providers. */
public enum InvestigationToolName {
    HEALTH_CONTEXT("health_context"),
    PROMETHEUS_QUERY("prometheus_query"),
    LOKI_SEARCH("loki_search"),
    TEMPO_SEARCH("tempo_search"),
    TEMPO_TRACE("tempo_trace"),
    CHANGE_HISTORY("change_history"),
    CAPACITY_EVIDENCE("capacity_evidence");

    private final String externalName;

    InvestigationToolName(String externalName) {
        this.externalName = externalName;
    }

    public String externalName() {
        return externalName;
    }

    public static InvestigationToolName fromExternalName(String value) {
        for (InvestigationToolName candidate : values()) {
            if (candidate.externalName.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unsupported investigation tool: " + value);
    }
}
