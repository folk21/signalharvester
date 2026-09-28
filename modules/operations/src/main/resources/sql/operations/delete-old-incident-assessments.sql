DELETE FROM operations.incident_assessments
WHERE assessment_id IN (
    SELECT assessment_id
    FROM operations.incident_assessments
    ORDER BY created_at DESC, assessment_id DESC
    OFFSET :keepCount
)
