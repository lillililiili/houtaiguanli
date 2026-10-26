package db.migration;

import java.nio.charset.StandardCharsets;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Additive identity and action ledger. Historical alarms and verdicts are never rewritten. */
public class V202610230001__unified_uav_risk_cases extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (var s=context.getConnection().createStatement()) {
            s.execute("CREATE TABLE uav_risk_case (risk_case_id VARCHAR(36) PRIMARY KEY, root_event_id VARCHAR(36) NOT NULL REFERENCES uav_event(event_id), episode_id VARCHAR(36), airspace_id VARCHAR(36), target_id VARCHAR(36), owner_org_id VARCHAR(36) NOT NULL, district_id VARCHAR(36) NOT NULL, source_mode VARCHAR(8) NOT NULL, superseded_by VARCHAR(36), version BIGINT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL)");
            s.execute("CREATE UNIQUE INDEX uq_uav_risk_episode ON uav_risk_case(episode_id)");
            s.execute("CREATE INDEX ix_uav_risk_scope ON uav_risk_case(owner_org_id,district_id,source_mode,target_id)");
            s.execute("CREATE TABLE uav_risk_case_member (risk_case_id VARCHAR(36) NOT NULL REFERENCES uav_risk_case(risk_case_id), event_id VARCHAR(36) NOT NULL REFERENCES uav_event(event_id), role VARCHAR(16) NOT NULL, created_at BIGINT NOT NULL, PRIMARY KEY(risk_case_id,event_id))");
            s.execute("CREATE INDEX ix_uav_risk_member_event ON uav_risk_case_member(event_id,risk_case_id)");
            s.execute("CREATE TABLE uav_risk_case_verification (verification_id VARCHAR(36) PRIMARY KEY, risk_case_id VARCHAR(36) NOT NULL REFERENCES uav_risk_case(risk_case_id), case_version BIGINT NOT NULL, event_id VARCHAR(36) NOT NULL, event_version BIGINT NOT NULL, conclusion VARCHAR(32) NOT NULL, basis_kind VARCHAR(16) NOT NULL, prediction_id VARCHAR(36), actor_id VARCHAR(36) NOT NULL, note VARCHAR(1000) NOT NULL, created_at BIGINT NOT NULL)");
            s.execute("CREATE UNIQUE INDEX uq_uav_risk_case_verification ON uav_risk_case_verification(risk_case_id,case_version)");
            s.execute("ALTER TABLE uav_risk_case_verification ADD COLUMN basis_json TEXT");
            s.execute("ALTER TABLE uav_risk_case_verification ALTER COLUMN actor_id DROP NOT NULL");
            s.execute("ALTER TABLE uav_risk_case_verification ADD COLUMN trigger_mode VARCHAR(8) NOT NULL DEFAULT 'MANUAL'");
            s.execute("ALTER TABLE uav_risk_case_verification ADD COLUMN automation_run_id VARCHAR(36)");
            s.execute("ALTER TABLE uav_risk_case_verification ADD CONSTRAINT ck_risk_verification_actor CHECK ((trigger_mode='MANUAL' AND actor_id IS NOT NULL AND automation_run_id IS NULL) OR (trigger_mode='AUTO' AND actor_id IS NULL AND automation_run_id IS NOT NULL AND basis_kind='OBSERVED' AND conclusion='CONFIRMED'))");
            s.execute("CREATE TABLE uav_risk_channel (risk_case_id VARCHAR(36) NOT NULL REFERENCES uav_risk_case(risk_case_id), channel VARCHAR(8) NOT NULL, event_id VARCHAR(36) NOT NULL REFERENCES uav_event(event_id), task_scope VARCHAR(36) NOT NULL DEFAULT '', claimed_at BIGINT NOT NULL, PRIMARY KEY(risk_case_id,channel))");
            s.execute("ALTER TABLE disposal_authorization ADD COLUMN risk_case_id VARCHAR(36) REFERENCES uav_risk_case(risk_case_id)");
            s.execute("CREATE INDEX ix_disposal_risk_case ON disposal_authorization(risk_case_id)");
            s.execute("ALTER TABLE uav_auto_sms_task ADD COLUMN risk_content VARCHAR(2000)");
            for(String table:new String[]{"uav_auto_sms_task","uav_auto_voice_task"}) {
                s.execute("ALTER TABLE "+table+" ADD COLUMN risk_scope VARCHAR(36) NOT NULL DEFAULT ''");
                boolean h2=context.getConnection().getMetaData().getDatabaseProductName().equals("H2");
                // H2 reuses the one-column PK index for this outbound FK; release it before changing the PK.
                if(h2){
                    var foreignKeys=new java.util.ArrayList<String>();
                    try(var q=context.getConnection().prepareStatement("SELECT t.constraint_name FROM information_schema.table_constraints t JOIN information_schema.key_column_usage k ON k.constraint_schema=t.constraint_schema AND k.constraint_name=t.constraint_name WHERE lower(t.table_name)=? AND t.constraint_type='FOREIGN KEY' AND t.table_schema=current_schema() AND lower(k.column_name)='event_id'")){
                        q.setString(1,table);try(var r=q.executeQuery()){while(r.next())foreignKeys.add(r.getString(1));}
                    }
                    for(String name:foreignKeys)s.execute("ALTER TABLE "+table+" DROP CONSTRAINT \""+name.replace("\"","\"\"")+"\"");
                }
                String pk=null;
                try(var q=context.getConnection().prepareStatement("SELECT constraint_name FROM information_schema.table_constraints WHERE lower(table_name)=? AND constraint_type='PRIMARY KEY' AND table_schema=current_schema()")) {
                    q.setString(1,table);try(var r=q.executeQuery()){if(r.next())pk=r.getString(1);}
                }
                if(pk==null)throw new IllegalStateException("Missing notification primary key");
                if(h2)
                    s.execute("ALTER TABLE "+table+" DROP PRIMARY KEY");
                else s.execute("ALTER TABLE "+table+" DROP CONSTRAINT \""+pk.replace("\"","\"\"")+"\"");
                s.execute("ALTER TABLE "+table+" ADD PRIMARY KEY(event_id,risk_scope)");
                if(h2)s.execute("ALTER TABLE "+table+" ADD FOREIGN KEY(event_id) REFERENCES uav_event(event_id)");
            }
            s.execute("ALTER TABLE uav_event_advisory ADD COLUMN risk_case_id VARCHAR(36)");
            s.execute("ALTER TABLE uav_event_voice_advisory ADD COLUMN risk_case_id VARCHAR(36)");
            if(context.getConnection().getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
                try(var in=getClass().getResourceAsStream("/db/risk-case-identity.sql")) {
                    if(in==null)throw new IllegalStateException("Missing risk identity migration");
                    s.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));
                }
            }
        }
    }
}
