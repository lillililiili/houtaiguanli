package com.uav.lowaltitude.platform.report;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static com.uav.lowaltitude.platform.report.BusinessReportSource.*;

class FormalReportDatasetTest {
    @Test void summaryDetailsAndExportSizedPageShareFormalFilter() {
        String pg=System.getenv("ACCEPTANCE_TEST_PG_URL");
        if(pg!=null && !pg.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/acceptance_test_[a-z0-9_]+"))
            throw new IllegalArgumentException("Only disposable acceptance_test_* database is allowed");
        var datasource=pg==null?new DriverManagerDataSource("jdbc:h2:mem:formal_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""):
            new DriverManagerDataSource(pg,System.getenv("POSTGRES_TEST_USER"),System.getenv("POSTGRES_TEST_PASSWORD"));
        var jdbc=new JdbcTemplate(datasource);
        var reader=new ReportDatasetReader(jdbc);
        var dataset=new Dataset("SELECT '1' AS id,'real' AS label,CAST(0 AS BIGINT) AS at_ms,'OPEN' AS state,'UAV' AS kind,'HIGH' AS severity,'region' AS region,'live' AS source_mode,'' AS related,'' AS result,'' AS note"
            +" UNION ALL SELECT '2','demo',0,'OPEN','UAV','HIGH','region','mock','','',''"
            +" UNION ALL SELECT '3','replay',0,'OPEN','UAV','HIGH','region','replay','','',''",Map.of());
        var summary=reader.summarize(dataset,new Range(LocalDate.of(1970,1,1),LocalDate.of(1970,1,1)),"targets","targets","first seen",false,List.of(new Dimension("kind","kind")));
        assertThat(summary.total()).isEqualTo(1);
        assertThat(summary.sources()).containsExactly(new Count("live",1));
        assertThat(summary.days()).containsExactly(new Day("1970-01-01",1));
        for(int size:List.of(1,50,50000)) {
            var page=reader.details(dataset,1,size);
            assertThat(page.total()).isEqualTo(summary.total());
            assertThat(page.items()).extracting(Row::sourceMode).containsExactly("live");
        }
        assertThat(reader.details(dataset,2,1).items()).isEmpty();
    }
}
