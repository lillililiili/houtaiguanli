package db.migration;

import java.util.ArrayList;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 短信未知结果独立于明确失败；不改写历史状态或送达记录。 */
public class V202609220001__automatic_sms_unknown_result extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        boolean pg=connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql");
        String query=pg?"""
            SELECT conname AS name, pg_get_constraintdef(oid) AS clause FROM pg_constraint
            WHERE conrelid='uav_auto_sms_task'::regclass AND contype='c'
            """:"""
            SELECT c.CONSTRAINT_NAME AS name,c.CHECK_CLAUSE AS clause
            FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS c JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS t
              ON c.CONSTRAINT_CATALOG=t.CONSTRAINT_CATALOG AND c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA
              AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME
            WHERE LOWER(t.TABLE_NAME)='uav_auto_sms_task' AND t.TABLE_SCHEMA=SCHEMA()
            """;
        var names=new ArrayList<String>();
        try(var statement=connection.createStatement();var rs=statement.executeQuery(query)) {
            while(rs.next()) {
                String clause=rs.getString("clause").toLowerCase(Locale.ROOT);
                if(clause.contains("status")&&clause.contains("waiting")&&clause.contains("unavailable"))names.add(rs.getString("name"));
            }
        }
        if(names.size()!=1)throw new IllegalStateException("Expected one automatic SMS status constraint");
        try(var statement=connection.createStatement()) {
            for(String name:names)statement.execute("ALTER TABLE uav_auto_sms_task DROP CONSTRAINT \""+name.replace("\"","\"\"")+"\"");
            statement.execute("ALTER TABLE uav_auto_sms_task ADD CONSTRAINT ck_auto_sms_result_status CHECK (status IN ('WAITING','SENDING','SIMULATED_DELIVERED','FAILED','UNKNOWN','UNAVAILABLE','BLOCKED'))");
        }
    }
}
