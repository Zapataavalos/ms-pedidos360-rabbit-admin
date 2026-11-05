package cl.duoc.pedidos360.rabbitadmin.exception;

/** RabbitMQ no responde (ni por AMQP ni por la API de administración): se responde 503. */
public class BrokerUnavailableException extends RuntimeException {
    public BrokerUnavailableException(String message) {
        super(message);
    }
}
