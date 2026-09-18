UPDATE configuration.sources
   SET name = :name,
       source_type = :sourceType,
       location = :location,
       enabled = :enabled
 WHERE id = :id
