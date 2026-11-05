package cl.duoc.pedidos360.rabbitadmin.exception;

/**
 * La solicitud tiene el formato correcto, pero la combinación no es válida para RabbitMQ (por ejemplo, un
 * patrón con comodines en un exchange direct). Se responde 400.
 */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
