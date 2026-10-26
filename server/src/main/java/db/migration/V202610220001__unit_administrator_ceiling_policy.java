package db.migration;

import java.util.ArrayList;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Refresh existing administrators' sessions for the new policy; grants and personnel bindings stay intact. */
public class V202610220001__unit_administrator_ceiling_policy extends BaseJavaMigration {
    @Override public Integer getChecksum() { return 2026102201; }

    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        var affected = new ArrayList<String[]>();
        try (var query = connection.prepareStatement("""
                SELECT d.org_id,u.user_id FROM app_org_delegation d
                JOIN app_org o ON o.org_id=d.org_id AND o.enabled=TRUE AND o.deleted_at IS NULL
                JOIN app_user u ON u.user_id=d.administrator_user_id AND u.org_id=d.org_id
                JOIN app_role r ON r.role_code=u.role_code AND (r.owner_org_id IS NULL OR r.owner_org_id=d.org_id)
                WHERE u.status='ACTIVE' AND u.scope_mode='ASSIGNED' AND u.scope_org_rule='OWN_ORG'
                  AND u.role_code NOT IN ('ROLE-ADMIN','ROLE-BACKEND','ROLE-AUTOMATION')
                  AND u.user_id<>'automation-rule-runner'
                """); var rows = query.executeQuery()) {
            while (rows.next()) affected.add(new String[]{rows.getString(1), rows.getString(2)});
        }
        long now = System.currentTimeMillis();
        try (var user = connection.prepareStatement("UPDATE app_user SET permission_version=permission_version+1 WHERE user_id=?");
             var session = connection.prepareStatement("UPDATE app_session SET expire_at=0 WHERE user_id=? AND expire_at>0");
             var preview = connection.prepareStatement("UPDATE app_org_delegation_preview SET consumed=TRUE WHERE org_id=? AND consumed=FALSE");
             var audit = connection.prepareStatement("""
                     INSERT INTO audit_log(audit_id,user_id,account,action,object_type,object_id,detail,occurred_at,ip,module_code,result)
                     VALUES(?,NULL,'SYSTEM','unit_admin_permission_policy_changed','user',?,?,?,'','users','SUCCESS')
                     """)) {
            for (String[] row : affected) {
                user.setString(1,row[1]); user.executeUpdate();
                session.setString(1,row[1]); session.executeUpdate();
                preview.setString(1,row[0]); preview.executeUpdate();
                audit.setString(1,UUID.randomUUID().toString()); audit.setString(2,row[1]);
                audit.setString(3,"{\"policy\":\"UNIT_CEILING_V1\",\"note\":\"单位管理员继承单位明确开放的权限；原角色及人员绑定保留\"}");
                audit.setLong(4,now); audit.executeUpdate();
            }
        }
    }
}
