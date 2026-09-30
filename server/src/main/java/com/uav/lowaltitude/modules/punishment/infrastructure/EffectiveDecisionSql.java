package com.uav.lowaltitude.modules.punishment.infrastructure;

/** Shared by case projections and reporting. Aliases c/doc/p preserve the established report semantics. */
public final class EffectiveDecisionSql {
    private EffectiveDecisionSql() { }

    public static final String JOINS = """
            LEFT JOIN penalty_decision_document doc ON doc.case_id=c.case_id AND doc.status='ISSUED' AND doc.template_version NOT LIKE 'demo%'
              AND NOT EXISTS (SELECT 1 FROM penalty_decision_document newer
                WHERE newer.case_id=doc.case_id AND newer.status='ISSUED'
                  AND (newer.issued_at,newer.document_id) > (doc.issued_at,doc.document_id))
            LEFT JOIN penalty_discretion p ON p.discretion_id=doc.discretion_id AND p.status='CONFIRMED'
              AND EXISTS (SELECT 1 FROM penalty_rule pr WHERE pr.rule_code=p.rule_code AND pr.schema_status='CONFIRMED')
            """;
}
