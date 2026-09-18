CREATE UNIQUE INDEX uk_response_plan_current_binding ON airspace_response_plan_binding(airspace_id) WHERE ended_at IS NULL;
