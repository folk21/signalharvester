SELECT *
FROM operations.change_journal
WHERE changed_at >= :fromInclusive
  AND changed_at <= :toInclusive
ORDER BY changed_at DESC, change_id DESC
LIMIT :limit
