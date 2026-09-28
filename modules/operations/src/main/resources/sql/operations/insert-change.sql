INSERT INTO operations.change_journal (
    change_id, changed_at, category, target_type, target_id, before_state, after_state,
    outcome, change_source, actor_id, correlation_id, trace_id, application_version
) VALUES (
    :changeId, :changedAt, :category, :targetType, :targetId,
    CAST(:beforeState AS jsonb), CAST(:afterState AS jsonb), :outcome, :changeSource,
    :actorId, :correlationId, :traceId, :applicationVersion
)
