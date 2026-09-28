SELECT *
FROM operations.change_journal
ORDER BY changed_at DESC, change_id DESC
LIMIT :limit
