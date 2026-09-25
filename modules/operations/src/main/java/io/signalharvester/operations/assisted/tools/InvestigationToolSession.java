package io.signalharvester.operations.assisted.tools;

import java.util.List;

/** Per-investigation capability object that enforces tool authorization and budgets. */
public interface InvestigationToolSession {
    List<InvestigationToolDefinition> definitions();
    InvestigationToolResult execute(InvestigationToolRequest request);
    List<String> discoveredEvidenceReferences();
    int toolCallCount();
}
