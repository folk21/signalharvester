package io.signalharvester.operations.assisted;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

/** Runtime configuration for explicit provider-assisted operational investigation. */
@Context
@ConfigurationProperties("signalharvester.operations.assisted-investigation")
public interface AssistedInvestigationConfiguration {

    /** Provider adapter name. `off` disables direct model invocation while manual export remains available. */
    @Bindable(defaultValue = "off")
    String getProvider();

    /** Full OpenAI-compatible chat-completions endpoint used only when that provider is explicitly selected. */
    @Bindable(defaultValue = "")
    String getEndpoint();

    /** Optional bearer credential. Deployments must inject it through secret-managed environment configuration. */
    @Bindable(defaultValue = "")
    String getApiKey();

    /** Model identifier passed to the configured provider. */
    @Bindable(defaultValue = "")
    String getModel();

    /** Per-invocation HTTP timeout. */
    @NotNull
    @Bindable(defaultValue = "30s")
    Duration getRequestTimeout();

    /** Maximum Health Report characters included in one analysis package. */
    @Min(4_096)
    @Max(200_000)
    @Bindable(defaultValue = "50000")
    int getMaxReportChars();

    /** Maximum provider response bytes accepted before parsing. */
    @Min(1_024)
    @Max(1_000_000)
    @Bindable(defaultValue = "32768")
    int getMaxResponseBytes();


    /** Maximum number of model/tool interaction rounds in one explicit investigation. */
    @Min(1)
    @Max(16)
    @Bindable(defaultValue = "4")
    int getMaxRounds();

    /** Maximum total read-only tool calls in one explicit investigation. */
    @Min(0)
    @Max(64)
    @Bindable(defaultValue = "8")
    int getMaxToolCalls();

    /** Maximum wall-clock duration of one explicit agentic investigation. */
    @NotNull
    @Bindable(defaultValue = "45s")
    Duration getMaxInvestigationDuration();

    /** Maximum sanitized characters returned by any one investigation tool call. */
    @Min(512)
    @Max(100_000)
    @Bindable(defaultValue = "12000")
    int getMaxToolResultChars();

    /** Maximum telemetry lookback accepted by Loki/Prometheus read-only tools. */
    @NotNull
    @Bindable(defaultValue = "30m")
    Duration getMaxTelemetryLookback();

    /** Maximum Loki entries returned by one bounded search. */
    @Min(1)
    @Max(200)
    @Bindable(defaultValue = "50")
    int getMaxLogEntries();

    /** Maximum Tempo search results returned by one bounded search. */
    @Min(1)
    @Max(100)
    @Bindable(defaultValue = "20")
    int getMaxTraceResults();

    /** Optional Loki base URL. Empty disables the Loki investigation tool. */
    @Bindable(defaultValue = "")
    String getLokiBaseUrl();

    /** Optional Tempo base URL. Empty disables Tempo search/trace tools. */
    @Bindable(defaultValue = "")
    String getTempoBaseUrl();

    /** Maximum number of persisted assessments retained. */
    @Min(1)
    @Max(100_000)
    @Bindable(defaultValue = "500")
    int getAssessmentRetentionCount();
}
