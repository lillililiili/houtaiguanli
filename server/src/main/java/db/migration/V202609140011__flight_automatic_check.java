package db.migration;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 保留旧人工核实历史；新增系统检查结论，实际起飞状态始终为 UNKNOWN。 */
public class V202609140011__flight_automatic_check extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        var names=new ArrayList<String>();
        boolean pg=connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql");
        String query=pg?"""
            SELECT conname AS name, pg_get_constraintdef(oid) AS clause FROM pg_constraint
            WHERE conrelid='flight_plan_verification'::regclass AND contype='c'
            """:"""
            SELECT c.CONSTRAINT_NAME AS name,c.CHECK_CLAUSE AS clause
            FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS c JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS t
              ON c.CONSTRAINT_CATALOG=t.CONSTRAINT_CATALOG AND c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA
              AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME
            WHERE LOWER(t.TABLE_NAME)='flight_plan_verification' AND t.TABLE_SCHEMA=SCHEMA()
            """;
        try(var statement=connection.createStatement();var rs=statement.executeQuery(query)) {
            while(rs.next())if(rs.getString("clause").toLowerCase(Locale.ROOT).contains("conclusion"))names.add(rs.getString("name"));
        }
        try(Statement s=connection.createStatement()) {
            for(String name:names)s.execute("ALTER TABLE flight_plan_verification DROP CONSTRAINT \""+name.replace("\"","\"\"")+"\"");
            s.execute("""
                ALTER TABLE flight_plan_verification ADD CONSTRAINT ck_flight_check_conclusion
                CHECK (conclusion IN ('NOT_TAKEN_OFF','DEVICE_ABNORMAL','AUTO_DEVICE_ABNORMAL','SUSPECTED_NOT_TAKEN_OFF','CHECK_INCOMPLETE'))
                """);
            s.execute("""
                ALTER TABLE flight_plan_verification ADD CONSTRAINT ck_flight_check_takeoff
                CHECK ((conclusion='NOT_TAKEN_OFF' AND takeoff_status='NOT_TAKEN_OFF') OR
                  (conclusion IN ('DEVICE_ABNORMAL','AUTO_DEVICE_ABNORMAL','SUSPECTED_NOT_TAKEN_OFF','CHECK_INCOMPLETE') AND takeoff_status='UNKNOWN'))
                """);
        }
    }
}
