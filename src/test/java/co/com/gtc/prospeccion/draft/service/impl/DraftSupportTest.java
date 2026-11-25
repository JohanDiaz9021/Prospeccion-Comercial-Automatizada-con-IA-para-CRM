package co.com.gtc.prospeccion.draft.service.impl;

import static co.com.gtc.prospeccion.draft.service.impl.DraftInboxTest.borrador;
import static co.com.gtc.prospeccion.draft.service.impl.DraftInboxTest.estado;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.common.constants.EvidenceFields;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.DraftStatus;
import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.common.exception.ConflictException;
import co.com.gtc.prospeccion.common.exception.NotFoundException;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.draft.dto.response.DraftResponseDTO;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.draft.mapper.DraftMapperImpl;
import co.com.gtc.prospeccion.draft.persistence.DraftDAO;
import co.com.gtc.prospeccion.draft.repository.DraftRepository;
import co.com.gtc.prospeccion.prospect.entities.EvidenceET;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.sending.entities.SendQueueET;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.sending.schedule.Ley2300Calendar;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

class DraftSupportTest {

    private final DraftDAO draftDAO = mock(DraftDAO.class);
    private final DraftRepository draftRepository = mock(DraftRepository.class);
    private final EvidenceRepository evidence = mock(EvidenceRepository.class);
    private final TenantConfigRepository configRepository = mock(TenantConfigRepository.class);
    private final SendQueueRepository queue = mock(SendQueueRepository.class);
    private DraftSupport support;

    static EvidenceET evidencia(String field, String value) {
        EvidenceET e = new EvidenceET();
        e.setPmLeafletContactId(900);
        e.setSource("APOLLO");
        e.setField(field);
        e.setValue(value);
        return e;
    }

    @BeforeEach
    void preparar() {
        TenantConfigET config = new TenantConfigET();
        config.setBaseMessage("ASUNTO: x\n\nBuenos días, {{nombre}}:\n\nEquipo Comercial GTC");
        when(configRepository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.of(config));
        when(evidence.findByPmLeafletContactIdIn(any())).thenReturn(List.of(
                evidencia(EvidenceFields.FIRST_NAME, "Laura"),
                evidencia(EvidenceFields.TITLE, "Directora de Tecnología"),
                evidencia(EvidenceFields.ORGANIZATION_NAME, "Clínica Demo")));
        when(draftRepository.countVersions(any())).thenReturn(List.<Object[]>of(new Object[] { 900, 3L }));
        support = new DraftSupport(draftDAO, draftRepository, new DraftMapperImpl(), evidence, configRepository,
                queue, mock(MasterTableRepository.class), new Ley2300Calendar(new ProspeccionProperties()),
                new ObjectMapper());
    }

    @Test
    void completaContactoTextoAvisosYVersiones() {
        DraftResponseDTO dto = support.describe(borrador(5, 3));

        assertThat(dto.getContactName()).isEqualTo("Laura");
        assertThat(dto.getContactTitle()).isEqualTo("Directora de Tecnología");
        assertThat(dto.getOrganizationName()).isEqualTo("Clínica Demo");
        assertThat(dto.getVersions()).isEqualTo(3);
        assertThat(dto.getBodyText()).startsWith("Buenos días, Laura:");
        assertThat(dto.getWarnings()).contains("TOO_SHORT"); // el borrador de prueba es corto
        assertThat(dto.getStatusCode()).isEqualTo(DraftStatus.PENDING_REVIEW);
        assertThat(dto.getScheduledAt()).isNull();
    }

    @Test
    void siEstaAprobadoMuestraElTurnoEnHoraDeColombia() {
        DraftET d = borrador(5, 3);
        d.setStatus(estado(DraftStatus.APPROVED));
        SendQueueET turno = new SendQueueET();
        turno.setScheduledAt(LocalDateTime.of(2026, 11, 30, 12, 0)); // UTC
        when(queue.findByDraftId(5)).thenReturn(Optional.of(turno));

        assertThat(support.describe(d).getScheduledAt()).isEqualTo(LocalDateTime.of(2026, 11, 30, 7, 0));
    }

    @Test
    void soloLaUltimaVersionPorRevisar() {
        DraftET v2 = borrador(5, 2);
        DraftET v3 = borrador(6, 3);
        when(draftDAO.findLatestByContact(900)).thenReturn(Optional.of(v3));

        assertThatThrownBy(() -> support.requireLatestPending(v2)).isInstanceOf(ConflictException.class);
        assertThatCode(() -> support.requireLatestPending(v3)).doesNotThrowAnyException();

        v3.setStatus(estado(DraftStatus.DISCARDED));
        assertThatThrownBy(() -> support.requireLatestPending(v3))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("lblDraftNotPending"));
    }

    @Test
    void borradorQueNoExiste() {
        when(draftDAO.findById(77)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> support.find(77)).isInstanceOf(NotFoundException.class);
    }
}
