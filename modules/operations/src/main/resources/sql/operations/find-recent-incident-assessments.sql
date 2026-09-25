SELECT *
FROM operations.incident_assessments
ORDER BY created_at DESC, assessment_id DESC
LIMIT :limit
