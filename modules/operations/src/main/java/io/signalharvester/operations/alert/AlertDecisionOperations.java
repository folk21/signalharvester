package io.signalharvester.operations.alert;

import java.util.List;

/** Internal application boundary for application-owned human-attention alert history. */
public interface AlertDecisionOperations {
    List<HumanAttentionAlert> recentAlerts(int limit);
}
