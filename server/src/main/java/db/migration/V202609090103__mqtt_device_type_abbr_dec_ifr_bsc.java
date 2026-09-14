package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 协议 A/B 附录控制类：诱骗 dec、干扰 ifr、驱鸟炮 bsc。
 * 不加入 oe/cm/isrs。H2 与 PG 生成的约束名不同，按定义文本删除再重建。
 */
public class V202609090103__mqtt_device_type_abbr_dec_ifr_bsc extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (product.contains("postgresql")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        DO $$
                        DECLARE r record;
                        BEGIN
                          FOR r IN
                            SELECT con.conname
                            FROM pg_constraint con
                            JOIN pg_class rel ON rel.oid = con.conrelid
                            WHERE rel.relname = 'mqtt_device_binding'
                              AND con.contype = 'c'
                              AND pg_get_constraintdef(con.oid) LIKE '%device_type_abbr%'
                              AND pg_get_constraintdef(con.oid) LIKE '%radar%'
                              AND pg_get_constraintdef(con.oid) NOT LIKE '%dec%'
                          LOOP
                            EXECUTE format('ALTER TABLE mqtt_device_binding DROP CONSTRAINT %I', r.conname);
                          END LOOP;
                        END $$;
                        """);
            }
        } else {
            List<String> names = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT CONSTRAINT_NAME, CHECK_CLAUSE
                         FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS
                         """)) {
                while (rows.next()) {
                    String clause = String.valueOf(rows.getString("CHECK_CLAUSE")).toLowerCase(Locale.ROOT);
                    if (clause.contains("device_type_abbr") && clause.contains("radar") && !clause.contains("dec")) {
                        names.add(rows.getString("CONSTRAINT_NAME"));
                    }
                }
            }
            try (Statement drop = connection.createStatement()) {
                for (String name : names) {
                    drop.execute("ALTER TABLE mqtt_device_binding DROP CONSTRAINT \"" + name.replace("\"", "") + "\"");
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE mqtt_device_binding
                    ADD CONSTRAINT ck_mqtt_device_type_abbr
                    CHECK (device_type_abbr IN ('radar','5ga','tdoa','aoa','dcd','rid','dec','ifr','bsc'))
                    """);
        }
    }
}
