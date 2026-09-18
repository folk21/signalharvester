CREATE INDEX idx_results_analyzed_items_browse_order
    ON results.analyzed_items (analyzed_at DESC, monitoring_profile_id, normalized_item_id);

CREATE INDEX idx_results_analyzed_items_search
    ON results.analyzed_items
    USING GIN (to_tsvector('simple', COALESCE(title, '') || ' ' || normalized_content));
