package cl.duoc.pedidos360.rabbitadmin.exception;

/**
 * La operación choca con el estado actual del broker: el recurso ya existe, es un recurso protegido del
 * sistema o RabbitMQ rechazó la precondición (por ejemplo, borrar una cola con mensajes). Se responde 409.
 */
public class ResourceConflictException extends RuntimeException {
    public ResourceConflictException(String message) {
        super(message);
    }
}
