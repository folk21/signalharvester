SELECT collection_run_id, monitoring_profile_id, information_category,
       started_at, finished_at, status
  FROM collection.collection_runs
 WHERE collection_run_id = :collectionRunId
