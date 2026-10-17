package com.uav.lowaltitude.modules.target.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.SqlProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Verifies actual JDBC dispatch and bound target IDs without loading an application or querying history. */
class TargetBearingQuerySelectionTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void emptyBearingSelectionRunsOnlyTheThreeOtherSummaryQueries() throws Exception {
        var result = repository().summaries(List.of(UUID.randomUUID().toString()), List.of());
        var statements = statements(3);

        assertThat(result).hasSize(1);
        assertThat(statements).extracting(s -> ((SqlProvider) s).getSql())
                .noneMatch(sql -> sql.contains("source_observation"));
    }

    @Test
    void mixedSelectionBindsOnlyTheBearingTargetsWithoutRestrictingOtherSummaries() throws Exception {
        String located = UUID.randomUUID().toString(), direction = UUID.randomUUID().toString();
        repository().summaries(List.of(located, direction), List.of(direction));
        var statements = statements(4);

        for (int i = 0; i < 3; i++) {
            PreparedStatement statement = bind(statements.get(i));
            verify(statement).setString(1, located);
            verify(statement).setString(2, direction);
        }
        PreparedStatement bearing = bind(statements.get(3));
        assertThat(((SqlProvider) statements.get(3)).getSql()).contains("source_observation");
        verify(bearing).setString(1, direction);
        verify(bearing, never()).setString(anyInt(), eq(located));
    }

    @Test
    void originalOverloadStillQueriesBearingForAllRequestedTargets() throws Exception {
        String target = UUID.randomUUID().toString();
        repository().summaries(List.of(target));

        var statements = statements(4);
        assertThat(((SqlProvider) statements.get(3)).getSql()).contains("source_observation");
        verify(bind(statements.get(3))).setString(1, target);
    }

    @Test
    void emptyTargetPageDispatchesNoSql() throws Exception {
        assertThat(repository().summaries(List.of(), List.of())).isEmpty();
        verifyNoInteractions(jdbc);
    }

    private TargetReadRepository repository() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        return new TargetReadRepository(jdbc, source, new ObjectMapper());
    }

    private List<PreparedStatementCreator> statements(int count) {
        var creators = ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbc, times(count)).query(creators.capture(), any(RowCallbackHandler.class));
        return creators.getAllValues();
    }

    private PreparedStatement bind(PreparedStatementCreator creator) throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        creator.createPreparedStatement(connection);
        return statement;
    }
}
