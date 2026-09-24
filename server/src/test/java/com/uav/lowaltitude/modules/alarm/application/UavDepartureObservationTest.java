package com.uav.lowaltitude.modules.alarm.application;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.*;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.time.AppClock;
class UavDepartureObservationTest {
 final UavEventRepository events=mock(UavEventRepository.class);
 final AccessControlService access=mock(AccessControlService.class);
 final AppClock clock=mock(AppClock.class);
 final AutoSmsService sms=mock(AutoSmsService.class);
 final AutoVoiceService voice=mock(AutoVoiceService.class);
 final PilotDepartureWatch watch=mock(PilotDepartureWatch.class);
 final EventRow event=mock(EventRow.class);
 final AccessDecision scope=mock(AccessDecision.class);
 final UavAdvisoryService service=new UavAdvisoryService(events,null,access,null,null,clock,null,sms,voice,watch,null);
 @BeforeEach void setup(){when(access.require(PermissionCode.ALARM_READ)).thenReturn(scope);when(events.find("event",scope)).thenReturn(event);when(event.state()).thenReturn("CONFIRMED");when(clock.nowMillis()).thenReturn(20000L);}
 void delivered(){AutoSms value=mock(AutoSms.class);when(value.status()).thenReturn("SIMULATED_DELIVERED");when(sms.overview(event,false)).thenReturn(value);when(sms.deliveredAt("event")).thenReturn(10000L);}
 @Test void unverifiedNeverStarts(){when(event.state()).thenReturn("PENDING_VERIFICATION");assertThat(service.observation("event").status()).isEqualTo("NOT_STARTED");verifyNoInteractions(sms,voice,watch);}
 @Test void noDeliveryNeverStarts(){assertThat(service.observation("event").status()).isEqualTo("NOT_STARTED");verifyNoInteractions(watch);}
 @Test void waitsFullThreeSecondsAfterSms(){delivered();when(clock.nowMillis()).thenReturn(12999L);var r=service.observation("event");assertThat(r.status()).isEqualTo("WATCHING");assertThat(r.deadlineAt()).isEqualTo(13000L);verifyNoInteractions(watch);}
 @Test void preservesThreeIndependentPositionResults(){delivered();for(var presence:PilotDepartureWatch.Presence.values()){when(watch.assess("event",10000L,20000L)).thenReturn(presence);assertThat(service.observation("event").presence()).isEqualTo(presence.name());}}
 @Test void phoneUsesItsOwnObservationWindow(){delivered();AutoVoice value=mock(AutoVoice.class);when(value.status()).thenReturn("SIMULATED_PLAYED");when(value.playbackCompletedAt()).thenReturn(15000L);when(voice.overview(event,false)).thenReturn(value);var r=service.observation("event");assertThat(r.channel()).isEqualTo("VOICE");assertThat(r.deadlineAt()).isEqualTo(25000L);assertThat(r.status()).isEqualTo("WATCHING");verifyNoInteractions(watch);}
 @Test void assessmentFailureIsUnknown(){delivered();when(watch.assess("event",10000L,20000L)).thenThrow(new IllegalStateException());assertThat(service.observation("event").presence()).isEqualTo("UNKNOWN");}
 @Test void scopeIsRequiredBeforeReadingNotification(){when(events.find("event",scope)).thenReturn(null);assertThatThrownBy(()->service.observation("event"));verifyNoInteractions(sms,voice,watch);}
}
