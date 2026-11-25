package co.com.gtc.prospeccion.tenantconfig.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.common.exception.ConflictException;
import co.com.gtc.prospeccion.common.exception.NotFoundException;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.security.CurrentUserProvider;
import co.com.gtc.prospeccion.tenantconfig.dto.TenantConfigDTO;
import co.com.gtc.prospeccion.tenantconfig.dto.TenantConfigVersionDTO;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.mapper.TenantConfigMapper;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

class TenantConfigServiceImplTest {

    private final TenantConfigRepository repository = mock(TenantConfigRepository.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final DateTableResolver dateTable = mock(DateTableResolver.class);
    private final UserET user = entity(UserET.class, 7);
    private final DateTableET today = entity(DateTableET.class, 320);
    private final List<TenantConfigET> saved = new ArrayList<>();
    private TenantConfigServiceImpl service;

    @BeforeEach
    void preparar() {
        service = new TenantConfigServiceImpl(repository, Mappers.getMapper(TenantConfigMapper.class), currentUser,
                dateTable, new ObjectMapper());
        when(currentUser.getCurrentUser()).thenReturn(user);
        when(dateTable.today()).thenReturn(today);
        when(repository.saveAndFlush(any(TenantConfigET.class))).thenAnswer(inv -> {
            TenantConfigET et = inv.getArgument(0);
            if (et.getId() == null) {
                et.setId(100 + saved.size());
            }
            saved.add(et);
            return et;
        });
    }

    @Test
    void sinConfiguracionDevuelveValoresPorDefecto() {
        when(repository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.empty());

        TenantConfigDTO dto = service.get();

        assertThat(dto.getId()).isNull();
        assertThat(dto.getScoreThreshold()).isEqualTo(60);
        assertThat(dto.getProfileOfInterest()).isNull();
    }

    @Test
    void devuelveLaVersionVigenteSinElBuzon() {
        TenantConfigET vigente = vigente(5);
        when(repository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.of(vigente));

        TenantConfigDTO dto = service.get();

        assertThat(dto.getId()).isEqualTo(5);
        assertThat(dto.getScoreThreshold()).isEqualTo(70);
        assertThat(dto.getProfileOfInterest()).isEqualTo("{\"version\": 1}");
    }

    @Test
    void laPrimeraConfiguracionQuedaVigenteConAuditoria() {
        when(repository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.empty());

        TenantConfigDTO dto = service.update(request(null, "{\"version\": 1}", "Buenos días, {{nombre}}:"));

        assertThat(saved).hasSize(1);
        TenantConfigET nueva = saved.get(0);
        assertThat(nueva.getFlgActive()).isTrue();
        assertThat(nueva.getCreatedBy()).isSameAs(user);
        assertThat(nueva.getCreatedDate()).isSameAs(today);
        assertThat(nueva.getScoreThreshold()).isEqualTo(65);
        assertThat(dto.getId()).isEqualTo(nueva.getId());
    }

    @Test
    void guardarCreaUnaVersionNuevaYApagaLaAnteriorPrimero() {
        TenantConfigET anterior = vigente(5);
        anterior.setMailboxSsmKey("/config/gtcproject/prospeccion/mailbox/gtc");
        when(repository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.of(anterior));

        TenantConfigDTO dto = service.update(request(5, "{\"version\": 2}", "Hola"));

        assertThat(anterior.getFlgActive()).isFalse();
        assertThat(anterior.getLastUpdatedBy()).isSameAs(user);
        assertThat(anterior.getLastUpdatedDate()).isSameAs(today);
        TenantConfigET nueva = saved.get(1);
        assertThat(nueva.getFlgActive()).isTrue();
        assertThat(nueva.getProfileOfInterest()).isEqualTo("{\"version\": 2}");
        // El buzón no viaja al frontend: se conserva de la versión anterior.
        assertThat(nueva.getMailboxSsmKey()).isEqualTo("/config/gtcproject/prospeccion/mailbox/gtc");
        assertThat(dto.getId()).isEqualTo(nueva.getId()).isNotEqualTo(5);
        // La anterior se escribe antes de insertar la nueva (la base solo admite una vigente).
        InOrder orden = inOrder(repository);
        orden.verify(repository).saveAndFlush(anterior);
        orden.verify(repository).saveAndFlush(nueva);
    }

    @Test
    void siOtraPersonaGuardoAntesRespondeConflicto() {
        when(repository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.of(vigente(6)));

        assertThatThrownBy(() -> service.update(request(5, "{}", null))).isInstanceOf(ConflictException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void siDosGuardanAlMismoTiempoLaBaseLoDetecta() {
        when(repository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(TenantConfigET.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for UK_LA_TENANTCONFIG_IDX01"));

        assertThatThrownBy(() -> service.update(request(null, "{}", null))).isInstanceOf(ConflictException.class);
    }

    @Test
    void rechazaPerfilQueNoEsJson() {
        assertThatThrownBy(() -> service.update(request(null, "esto no es json", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("lblProfileInvalid"));
        assertThatThrownBy(() -> service.update(request(null, "[1, 2]", null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rechazaMarcasValidarSinResolver() {
        assertThatThrownBy(() -> service.update(request(null, "{\"name\": \"[validar] Servicio\"}", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("lblPendingValidation"));
        assertThatThrownBy(() -> service.update(request(null, null, "Cordialmente,\n[VALIDAR] Nombre")))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void elHistorialTraeQuienCuandoYLoPrincipalDeCadaVersion() {
        TenantConfigET nueva = vigente(9);
        nueva.setProfileOfInterest("{\"version\": 3, \"company\": {\"name\": \"GTC\"}}");
        nueva.setBaseMessage("Hola");
        nueva.setDailySendLimit(30);
        nueva.setCreatedBy(usuario(7, "  Laura ", "Gómez", "laura.gomez"));
        nueva.setCreatedDate(fecha(LocalDate.of(2026, 11, 24)));
        TenantConfigET vieja = vigente(4);
        vieja.setFlgActive(false);
        vieja.setProfileOfInterest("no era json");
        vieja.setCreatedBy(usuario(8, null, " ", "robot"));
        vieja.setCreatedDate(fecha(LocalDate.of(2026, 11, 20)));
        when(repository.findAllByOrderByIdDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(nueva, vieja), PageRequest.of(0, 20), 2));

        Page<TenantConfigVersionDTO> page = service.versions(PageRequest.of(0, 20, Sort.by("scoreThreshold")));

        TenantConfigVersionDTO primera = page.getContent().get(0);
        assertThat(primera.getId()).isEqualTo(9);
        assertThat(primera.isActive()).isTrue();
        assertThat(primera.getCreatedBy()).isEqualTo("Laura Gómez");
        assertThat(primera.getCreatedDate()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(primera.getProfileVersion()).isEqualTo(3);
        assertThat(primera.getCompanyName()).isEqualTo("GTC");
        assertThat(primera.getDailySendLimit()).isEqualTo(30);
        assertThat(primera.isHasBaseMessage()).isTrue();
        // Una versión vieja con perfil dañado no rompe el historial; sin nombre se muestra el login.
        TenantConfigVersionDTO segunda = page.getContent().get(1);
        assertThat(segunda.isActive()).isFalse();
        assertThat(segunda.getProfileVersion()).isNull();
        assertThat(segunda.getCompanyName()).isNull();
        assertThat(segunda.getCreatedBy()).isEqualTo("robot");
        assertThat(segunda.isHasBaseMessage()).isFalse();
    }

    @Test
    void elHistorialIgnoraElOrdenDelClienteYLimitaElTamano() {
        when(repository.findAllByOrderByIdDesc(any(Pageable.class))).thenReturn(Page.empty());

        service.versions(PageRequest.of(2, 500, Sort.by("profileOfInterest")));

        ArgumentCaptor<Pageable> pedido = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAllByOrderByIdDesc(pedido.capture());
        assertThat(pedido.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pedido.getValue().getPageSize()).isEqualTo(TenantConfigServiceImpl.MAX_PAGE_SIZE);
        assertThat(pedido.getValue().getSort().isUnsorted()).isTrue();
    }

    @Test
    void unaVersionDelHistorialSeDevuelveCompleta() {
        TenantConfigET vieja = vigente(4);
        vieja.setFlgActive(false);
        vieja.setBaseMessage("Mensaje viejo");
        when(repository.findById(4)).thenReturn(Optional.of(vieja));
        when(repository.findById(99)).thenReturn(Optional.empty());

        TenantConfigDTO dto = service.version(4);

        assertThat(dto.getId()).isEqualTo(4);
        assertThat(dto.getBaseMessage()).isEqualTo("Mensaje viejo");
        assertThat(dto.getProfileOfInterest()).isEqualTo("{\"version\": 1}");
        assertThatThrownBy(() -> service.version(99)).isInstanceOf(NotFoundException.class);
    }

    private static UserET usuario(int id, String name, String lastName, String login) {
        UserET user = entity(UserET.class, id);
        ReflectionTestUtils.setField(user, "name", name);
        ReflectionTestUtils.setField(user, "lastName", lastName);
        ReflectionTestUtils.setField(user, "login", login);
        return user;
    }

    private static DateTableET fecha(LocalDate date) {
        DateTableET dateTable = entity(DateTableET.class, 1);
        ReflectionTestUtils.setField(dateTable, "date", date);
        return dateTable;
    }

    private static TenantConfigDTO request(Integer id, String profile, String baseMessage) {
        TenantConfigDTO dto = new TenantConfigDTO();
        dto.setId(id);
        dto.setProfileOfInterest(profile);
        dto.setBaseMessage(baseMessage);
        dto.setScoreThreshold(65);
        dto.setDailySendLimit(30);
        dto.setMinSpacingSeconds(120);
        dto.setCompanyLinkedinUrl("https://www.linkedin.com/company/ejemplo");
        return dto;
    }

    private static TenantConfigET vigente(int id) {
        TenantConfigET et = new TenantConfigET();
        et.setId(id);
        et.setProfileOfInterest("{\"version\": 1}");
        et.setScoreThreshold(70);
        et.setFlgActive(true);
        return et;
    }

    private static <T> T entity(Class<T> type, int id) {
        try {
            T value = type.getDeclaredConstructor().newInstance();
            ReflectionTestUtils.setField(value, "id", id);
            return value;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
