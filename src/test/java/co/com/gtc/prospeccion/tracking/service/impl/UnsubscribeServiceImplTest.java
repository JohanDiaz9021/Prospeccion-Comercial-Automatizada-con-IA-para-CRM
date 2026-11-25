package co.com.gtc.prospeccion.tracking.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import co.com.gtc.prospeccion.common.constants.EvidenceFields;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.EmailEventType;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SendQueueStatus;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SuppressionReason;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.draft.repository.DraftRepository;
import co.com.gtc.prospeccion.prospect.entities.EvidenceET;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.suppression.service.SuppressionService;
import co.com.gtc.prospeccion.tracking.service.TrackingService;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeService.Result;

class UnsubscribeServiceImplTest {

    private final SendQueueRepository queue = mock(SendQueueRepository.class);
    private final DraftRepository drafts = mock(DraftRepository.class);
    private final EvidenceRepository evidence = mock(EvidenceRepository.class);
    private final SuppressionService suppression = mock(SuppressionService.class);
    private final TrackingService tracking = mock(TrackingService.class);
    private final MasterTableRepository masterTable = mock(MasterTableRepository.class);
    private final UserET aprobador = new UserET();
    private final MasterTableET cancelado = new MasterTableET();
    private UnsubscribeServiceImpl service;

    static SendQueueET envio(int id, String email) {
        SendQueueET q = new SendQueueET();
        q.setId(id);
        q.setDraftId(500 + id);
        q.setRecipientEmail(email);
        return q;
    }

    @BeforeEach
    void preparar() {
        ReflectionTestUtils.setField(cancelado, "code", SendQueueStatus.CANCELLED);
        when(masterTable.findFirstByTableCodeAndCode(SendQueueStatus.TABLECODE, SendQueueStatus.CANCELLED))
                .thenReturn(Optional.of(cancelado));
        when(suppression.suppress(anyString(), anyString(), any(), any())).thenReturn(true);
        service = new UnsubscribeServiceImpl(queue, drafts, evidence, suppression, tracking, masterTable,
                mock(DateTableResolver.class), Clock.fixed(Instant.parse("2026-11-23T20:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void bloqueaRegistraElEventoYCancelaLosOtrosEnvios() {
        SendQueueET q = envio(41, "laura@clinica-demo.example");
        q.setCreatedBy(aprobador);
        when(queue.findById(41)).thenReturn(Optional.of(q));
        SendQueueET otro = envio(42, "laura@clinica-demo.example");
        when(queue.findByRecipientEmailAndStatus_TableCodeAndStatus_Code("laura@clinica-demo.example",
                SendQueueStatus.TABLECODE, SendQueueStatus.QUEUED)).thenReturn(List.of(otro));

        Result r = service.unsubscribe(41).orElseThrow();

        verify(suppression).suppress("laura@clinica-demo.example", SuppressionReason.UNSUBSCRIBE, 41, aprobador);
        verify(tracking).registerEvent(41, EmailEventType.UNSUBSCRIBE, "unsubscribe:41",
                LocalDateTime.of(2026, 11, 23, 20, 0));
        assertThat(otro.getStatus()).isSameAs(cancelado);
        assertThat(r.cancelledSends()).isEqualTo(1);
        assertThat(r.maskedEmail()).isEqualTo("l****@clinica-demo.example");
        assertThat(r.newlySuppressed()).isTrue();
    }

    @Test
    void sinCorreoEnLaColaLoTomaDeLaEvidencia() {
        when(queue.findById(41)).thenReturn(Optional.of(envio(41, null)));
        DraftET d = new DraftET();
        d.setPmLeafletContactId(900);
        when(drafts.findById(541)).thenReturn(Optional.of(d));
        EvidenceET e = new EvidenceET();
        e.setField(EvidenceFields.EMAIL);
        e.setSource("APOLLO");
        e.setValue("Ana@Empresa.example");
        when(evidence.findByPmLeafletContactId(900)).thenReturn(List.of(e));

        assertThat(service.unsubscribe(41)).isPresent();
        verify(suppression).suppress(eq("ana@empresa.example"), anyString(), eq(41), any());
    }

    @Test
    void envioQueNoExisteNoHaceNada() {
        when(queue.findById(99)).thenReturn(Optional.empty());

        assertThat(service.unsubscribe(99)).isEmpty();
        assertThat(service.recipientOf(99)).isEmpty();
        verify(suppression, never()).suppress(any(), any(), any(), any());
    }

    @Test
    void laPaginaSabeSiYaSeHabiaDadoDeBaja() {
        when(queue.findById(41)).thenReturn(Optional.of(envio(41, "laura@clinica-demo.example")));
        when(suppression.isSuppressed("laura@clinica-demo.example")).thenReturn(true);

        assertThat(service.recipientOf(41)).hasValueSatisfying(r -> {
            assertThat(r.alreadyUnsubscribed()).isTrue();
            assertThat(r.maskedEmail()).isEqualTo("l****@clinica-demo.example");
        });
    }

    @Test
    void enmascarar() {
        assertThat(UnsubscribeServiceImpl.mask("a@b.co")).isEqualTo("a****@b.co");
        assertThat(UnsubscribeServiceImpl.mask("sinarroba")).isEqualTo("****");
    }
}
