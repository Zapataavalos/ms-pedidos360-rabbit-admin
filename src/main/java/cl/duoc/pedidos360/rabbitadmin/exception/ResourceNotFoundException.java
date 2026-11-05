package cl.duoc.pedidos360.rabbitadmin.exception;

/** La cola, el exchange o el binding no existe: se responde 404. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
