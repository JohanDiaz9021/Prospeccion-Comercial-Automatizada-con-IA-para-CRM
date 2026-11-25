package co.com.gtc.prospeccion.crm.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import co.com.gtc.prospeccion.common.constants.EvidenceFields;
import co.com.gtc.prospeccion.crm.service.CrmExclusionService.Reason;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.prospect.repository.LeafletExtRepository;
import co.com.gtc.prospeccion.search.rules.ProfileExclusions;
import co.com.gtc.prospeccion.suppression.service.SuppressionService;

class CrmExclusionServiceImplTest {

    private final SuppressionService suppression = mock(SuppressionService.class);
    private final EvidenceRepository evidence = mock(EvidenceRepository.class);
    private final LeafletExtRepository leaflets = mock(LeafletExtRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CrmExclusionServiceImpl service = new CrmExclusionServiceImpl(suppression, evidence, leaflets, jdbc);
    private final ProfileExclusions lists = new ProfileExclusions(Set.of("competidor.com"), Set.of("cliente.com"),
            Set.of("miempresa.com"));

    @Test
    void personaNuevaDeEmpresaNuevaSePuedeProspectar() {
        assertThat(service.exclusionReason("Ana@Nueva.example", "nueva.example", lists)).isEmpty();
    }

    @Test
    void laListaDeBloqueoGanaSobreTodo() {
        when(suppression.isSuppressed("ana@nueva.example")).thenReturn(true);

        assertThat(service.exclusionReason("ANA@nueva.example", "nueva.example", lists)).contains(Reason.SUPPRESSED);
    }

    @Test
    void colaboradoresCompetidoresYClientesNoSeProspectanAunqueSeaUnSubdominio() {
        assertThat(service.exclusionReason("yo@miempresa.com", null, lists)).contains(Reason.COLLABORATOR);
        assertThat(service.exclusionReason("ana@ventas.competidor.com", null, lists)).contains(Reason.EXCLUDED_COMPANY);
        assertThat(service.exclusionReason("ana@gmail.com", "https://www.cliente.com", lists))
                .contains(Reason.EXCLUDED_COMPANY);
        // "otrocliente.com" no es subdominio de "cliente.com".
        assertThat(service.exclusionReason("ana@otrocliente.com", null, lists)).isEmpty();
    }

    @Test
    void unaSolaPersonaPorEmpresaYNuncaElMismoCorreoDosVeces() {
        when(evidence.existsByFieldAndValue(EvidenceFields.EMAIL, "ana@nueva.example")).thenReturn(true);
        when(leaflets.existsByCompanyDomain("conocida.example")).thenReturn(true);

        assertThat(service.exclusionReason("ana@nueva.example", "nueva.example", lists)).contains(Reason.EXISTING_CONTACT);
        assertThat(service.exclusionReason("luis@conocida.example", null, lists)).contains(Reason.EXISTING_COMPANY);
        assertThat(service.exclusionReason("luis@x.example", "www.Conocida.example", lists))
                .contains(Reason.EXISTING_COMPANY);
    }

    @Test
    void siElCorreoYaEstaEnElCrmComoProspectoOProveedorNoSeLeEscribe() {
        when(jdbc.queryForObject(CrmExclusionServiceImpl.CRM_EMAIL_SQL, Boolean.class, "ana@cliente-real.example",
                "ana@cliente-real.example")).thenReturn(true);

        assertThat(service.exclusionReason("Ana@Cliente-Real.example", null, lists)).contains(Reason.CRM_CONTACT);
    }

    @Test
    void siAlguienDeLaEmpresaYaEstaEnElCrmNoSeLeEscribeAOtraPersona() {
        when(jdbc.queryForObject(eq(CrmExclusionServiceImpl.CRM_EMAIL_SQL), eq(Boolean.class), anyString(), anyString()))
                .thenReturn(false);
        when(jdbc.queryForObject(CrmExclusionServiceImpl.CRM_DOMAIN_SQL, Boolean.class, "%@proveedor.example",
                "%@proveedor.example")).thenReturn(true);
        when(jdbc.queryForObject(CrmExclusionServiceImpl.CRM_DOMAIN_SQL, Boolean.class, "%@otra.example",
                "%@otra.example")).thenReturn(false);
        when(jdbc.queryForObject(CrmExclusionServiceImpl.CRM_DOMAIN_SQL, Boolean.class, "%@sede.example",
                "%@sede.example")).thenReturn(true);

        assertThat(service.exclusionReason("luis@proveedor.example", null, lists)).contains(Reason.CRM_COMPANY);
        // También por el dominio de la empresa según Apollo, si es distinto al del correo.
        assertThat(service.exclusionReason("luis@otra.example", "www.sede.example", lists)).contains(Reason.CRM_COMPANY);
    }

    @Test
    void lasReglasBaratasVanAntesQueLasConsultasAlCrm() {
        when(suppression.isSuppressed("ana@nueva.example")).thenReturn(true);

        service.exclusionReason("ana@nueva.example", null, lists);

        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), anyString(), anyString());
    }

    @Test
    void losComodinesDeLikeSeBuscanTalCual() {
        assertThat(CrmExclusionServiceImpl.escapeLike("mi_empresa%.co")).isEqualTo("mi!_empresa!%.co");
    }
}
