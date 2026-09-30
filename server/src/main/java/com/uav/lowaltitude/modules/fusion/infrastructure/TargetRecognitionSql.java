package com.uav.lowaltitude.modules.fusion.infrastructure;

/** Shared current recognition projection. Aliases are internal SQL identifiers, never request input. */
public final class TargetRecognitionSql {
    private TargetRecognitionSql() { }

    public static String type(String target, String selection) {
        // A missing/unknown fused conclusion must never fall back to the first sensor's category.
        // Legacy targets without fusion retain their recorded category; historical facts are not backfilled.
        return "(CASE WHEN " + target + ".unified=TRUE AND " + selection + ".target_id IS NOT NULL"
                + " AND EXISTS (SELECT 1 FROM target_latest_state recognition_state WHERE recognition_state.target_id="
                + target + ".target_id AND recognition_state.observed_at>" + selection + ".selected_at) THEN 'UNKNOWN'"
                + " WHEN " + selection + ".target_id IS NOT NULL THEN COALESCE(" + selection
                + ".class_code,'UNKNOWN') WHEN " + target + ".unified=TRUE THEN 'UNKNOWN' ELSE COALESCE("
                + target + ".object_type_code,'UNKNOWN') END)";
    }

    public static String revision(String target, String selection) {
        return "COALESCE(" + selection + ".version," + target + ".version,0)";
    }
}
