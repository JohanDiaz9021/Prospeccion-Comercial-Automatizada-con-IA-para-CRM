package co.com.gtc.prospeccion.integration.mailbox;

/**
 * El buzón no aceptó el correo. Permanente (dirección inválida o rechazada) no se reintenta; temporal (servidor
 * caído, tiempo agotado, límite del proveedor) se reintenta más tarde. El mensaje no lleva el correo de la persona.
 */
public class MailboxSendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean permanent;

    public MailboxSendException(String message, boolean permanent, Throwable cause) {
        super(message, cause);
        this.permanent = permanent;
    }

    public boolean isPermanent() {
        return permanent;
    }
}
