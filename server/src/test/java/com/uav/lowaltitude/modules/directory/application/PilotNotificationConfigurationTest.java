package com.uav.lowaltitude.modules.directory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.Contact;
import com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository;
import com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository.PilotPlanAssociation;
import com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository.SettingRow;
import com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository.SubjectRow;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.integrationconfig.application.RealtimeNotificationTransport;
import com.uav.lowaltitude.platform.time.AppClock;

@ExtendWith(MockitoExtension.class)
class PilotNotificationConfigurationTest {
    @Mock DirectoryRepository repository;
    @Mock RealtimeNotificationTransport transport;
    private NotificationDirectoryService service;
    private final String event=UUID.randomUUID().toString(),evaluation=UUID.randomUUID().toString(),planId=UUID.randomUUID().toString();
    private static final long NOW=Instant.parse("2026-10-17T03:04:05Z").toEpochMilli();

    @BeforeEach void setup() {
        service=new NotificationDirectoryService(repository,new AppClock(Clock.fixed(Instant.ofEpochMilli(NOW),ZoneOffset.UTC)),
                new MockEnvironment(),mock(HandoffChannelPort.class));
        ReflectionTestUtils.setField(service,"simulator",transport);
    }

    @ParameterizedTest @ValueSource(strings={"ADVISORY_SMS","ADVISORY_VOICE"})
    void unknownAssociationOrUnreadablePlanIsNotAProvenMissingPilot(String purpose) {
        assertThat(service.cannotNotifyPilotFromConfiguration(purpose,event)).isFalse();
        when(repository.currentPilotPlanAssociation(event)).thenReturn(new PilotPlanAssociation(evaluation,null,false));
        assertThat(service.cannotNotifyPilotFromConfiguration(purpose,event)).isFalse();
        when(repository.currentPilotPlanAssociation(event)).thenReturn(new PilotPlanAssociation(evaluation,planId,false));
        assertThat(service.cannotNotifyPilotFromConfiguration(purpose,event)).isFalse();
        verify(repository,never()).latestEventEvaluation(anyString());
        verify(repository,never()).eventPlan(anyString(),anyString());
    }

    @ParameterizedTest @ValueSource(strings={"ADVISORY_SMS","ADVISORY_VOICE"})
    void onlyExplicitNoPlanOrMatchedPlanWithoutPilotIsUnnotifiable(String purpose) {
        when(repository.currentPilotPlanAssociation(event)).thenReturn(new PilotPlanAssociation(evaluation,null,true));
        assertThat(service.cannotNotifyPilotFromConfiguration(purpose,event)).isTrue();
        when(repository.currentPilotPlanAssociation(event)).thenReturn(new PilotPlanAssociation(evaluation,planId,false));
        when(repository.subjectInternal(planId)).thenReturn(mock(SubjectRow.class));
        assertThat(service.cannotNotifyPilotFromConfiguration(purpose,event)).isTrue();
        verify(repository,never()).latestEventEvaluation(anyString());
        verify(repository,never()).eventPlan(anyString(),anyString());
    }

    @Test void brokenPilotRelationshipIsUnknownInsteadOfAnAbsentPilot() {
        when(repository.currentPilotPlanAssociation(event)).thenReturn(new PilotPlanAssociation(evaluation,planId,false));
        var plan=mock(SubjectRow.class);
        when(repository.subjectInternal(planId)).thenReturn(plan);
        when(plan.pilotId()).thenReturn("pilot");
        assertThat(service.cannotNotifyPilotFromConfiguration("ADVISORY_SMS",event)).isFalse();
        var contact=mock(Contact.class);
        when(repository.contact("pilot")).thenReturn(contact);
        when(plan.operatorOrgId()).thenReturn("org");
        when(contact.orgId()).thenReturn("another-org");
        assertThat(service.cannotNotifyPilotFromConfiguration("ADVISORY_SMS",event)).isFalse();
        when(contact.orgId()).thenReturn("org");
        when(contact.roles()).thenReturn(List.of("OTHER"));
        assertThat(service.cannotNotifyPilotFromConfiguration("ADVISORY_SMS",event)).isFalse();
    }

    @Test void receiverAvailabilityIsNotUsedAsAConfigurationException() {
        SettingRow setting=configured();
        when(setting.enabled()).thenReturn(true);
        when(setting.channelType()).thenReturn("API");
        assertThat(service.cannotNotifyPilotFromConfiguration("ADVISORY_VOICE",event)).isFalse();
        verifyNoInteractions(transport);
    }

    @Test void onlyExplicitDisabledAbsentOrExpiredChannelIsConfigurationEvidence() {
        SettingRow setting=configured();
        assertThat(service.cannotNotifyPilotFromConfiguration("ADVISORY_VOICE",event)).isTrue();
        when(setting.enabled()).thenReturn(true);
        when(setting.channelType()).thenReturn("NONE");
        assertThat(service.cannotNotifyPilotFromConfiguration("ADVISORY_VOICE",event)).isTrue();
        when(setting.channelType()).thenReturn("API");
        when(setting.validUntil()).thenReturn(NOW);
        assertThat(service.cannotNotifyPilotFromConfiguration("ADVISORY_VOICE",event)).isTrue();
        verifyNoInteractions(transport);
    }

    private SettingRow configured() {
        when(repository.currentPilotPlanAssociation(event)).thenReturn(new PilotPlanAssociation(evaluation,planId,false));
        var plan=mock(SubjectRow.class);
        when(repository.subjectInternal(planId)).thenReturn(plan);
        when(plan.pilotId()).thenReturn("pilot"); when(plan.operatorOrgId()).thenReturn("org");
        var contact=mock(Contact.class);
        when(repository.contact("pilot")).thenReturn(contact);
        when(contact.orgId()).thenReturn("org"); when(contact.roles()).thenReturn(List.of("PILOT"));
        when(contact.enabled()).thenReturn(true); when(contact.validUntil()).thenReturn(null);
        when(contact.phone()).thenReturn("13800000000");
        when(contact.verifiedAt()).thenReturn(NOW-1); when(contact.verificationBasis()).thenReturn("test verified");
        var setting=mock(SettingRow.class); when(repository.setting("advisory-voice")).thenReturn(setting);
        lenient().when(setting.validUntil()).thenReturn(null);
        return setting;
    }
}
