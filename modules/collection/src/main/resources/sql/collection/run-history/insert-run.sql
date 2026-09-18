INSERT INTO collection.collection_runs (
    collection_run_id, monitoring_profile_id, information_category,
    started_at, finished_at, status
) VALUES (:collectionRunId, :monitoringProfileId, :informationCategory, :startedAt, :finishedAt, :status)
