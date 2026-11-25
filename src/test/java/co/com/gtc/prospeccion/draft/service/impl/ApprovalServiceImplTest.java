package co.com.gtc.prospeccion.draft.service.impl;

import static co.com.gtc.prospeccion.draft.service.impl.DraftInboxTest.borrador;
import static co.com.gtc.prospeccion.draft.service.impl.DraftInboxTest.estado;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.DraftStatus;
import co.com.gtc.prospeccion.common.exception.ConflictException;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.draft.persistence.DraftDAO;
import co.com.gtc.prospeccion.security.CurrentUserProvider;
import co.com.gtc.prospeccion.sending.service.SendQueueService;

class ApprovalServiceImplTest {

    private final DraftDAO draftDAO = mock(DraftDAO.class);
    private final DraftSupport support = mock(DraftSupport.class);
    private final SendQueueService queue = mock(SendQueueService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final DateTableResolver dateTable = mock(DateTableResolver.class);
    private final UserET aprobador = new UserET();
    private final DateTableET hoy = new DateTableET();
    private final MasterTableET aprobado = estado(DraftStatus.APPROVED);
    private ApprovalServiceImpl service;

    @BeforeEach
    void preparar() {
        when(currentUser.getCurrentUser()).thenReturn(aprobador);
        when(dateTable.today()).thenReturn(hoy);
        when(support.status(DraftStatus.APPROVED)).thenReturn(aprobado);
        when(draftDAO.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new ApprovalServiceImpl(draftDAO, support, queue, currentUser, dateTable);
    }

    @Test
    void apruebaYEncolaEnEseOrden() {
        DraftET d = borrador(5, 2);
        when(support.find(5)).thenReturn(d);

        service.approve(5);

        assertThat(d.getStatus()).isSameAs(aprobado);
        assertThat(d.getApprovedBy()).isSameAs(aprobador);
        assertThat(d.getApprovedDate()).isSameAs(hoy);
        InOrder orden = inOrder(support, draftDAO, queue);
        orden.verify(support).requireLatestPending(d);
        orden.verify(draftDAO).save(d);
        orden.verify(queue).enqueue(d);
    }

    @Test
    void unaVersionViejaNoSeApruebaNiSeEncola() {
        DraftET viejo = borrador(5, 1);
        when(support.find(5)).thenReturn(viejo);
        doThrow(new ConflictException("lblConflict", "hay una versión más nueva")).when(support).requireLatestPending(viejo);

        assertThatThrownBy(() -> service.approve(5)).isInstanceOf(ConflictException.class);
        verify(queue, never()).enqueue(any());
        verify(draftDAO, never()).save(any());
    }

    @Test
    void siElEncoladoFallaElErrorSaleParaQueSeDeshagaLaAprobacion() {
        DraftET d = borrador(5, 2);
        when(support.find(5)).thenReturn(d);
        when(queue.enqueue(d)).thenThrow(new IllegalStateException("Falta el estado 602/1"));

        // @Transactional deshace la aprobación porque la excepción sale del método.
        assertThatThrownBy(() -> service.approve(5)).isInstanceOf(IllegalStateException.class);
    }
}
