package com.uav.lowaltitude.platform.api;

import java.sql.SQLException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import com.uav.lowaltitude.platform.audit.AuditService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OrganizationWriteGuardErrorTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(mock(AuditService.class));

    @ParameterizedTest
    @CsvSource({"P7101,ORG_DELETED,409", "P7102,ORG_DELETE_BUSY,409", "P7103,INVALID_ORGANIZATION,400"})
    void returnsStableBusinessErrorWithoutLeakingDatabaseDetails(String state, String code, int status) {
        var error = new DataIntegrityViolationException("internal SQL", new SQLException("internal org id and table", state));
        var response = handler.handleUnknown(error, new MockHttpServletRequest("POST", "/api/v1/users"));
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().getError().getCode()).isEqualTo(code);
        assertThat(response.getBody().getError().getMessage()).doesNotContain("internal", "SQL", "table");
    }

    @Test void unrelatedDatabaseErrorIsNotMisclassifiedByItsText() {
        var error = new DataIntegrityViolationException("ORG_DELETED", new SQLException("P7101 ORG_DELETED", "23505"));
        var response = handler.handleUnknown(error, new MockHttpServletRequest("POST", "/api/v1/users"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getError().getCode()).isEqualTo("INTERNAL_ERROR");
    }
}
