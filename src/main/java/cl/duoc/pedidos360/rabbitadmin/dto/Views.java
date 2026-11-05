package cl.duoc.pedidos360.rabbitadmin.dto;

import java.util.Map;

/** Respuestas de la API. No exponen clases de RabbitMQ: solo datos. */
public final class Views {

    private Views() {
    }

    /**
     * @param deadLetterQueue DLQ asociada (si la cola envía sus rechazos a la DLX del sistema), o null
     */
    public record QueueView(String name, String type, boolean durable, boolean autoDelete, long messages,
                            long messagesReady, long messagesUnacknowledged, long consumers, String node,
                            Map<String, Object> arguments, String deadLetterQueue) {
    }

    public record ExchangeView(String name, String type, boolean durable, boolean autoDelete, boolean internal,
                               boolean system, Map<String, Object> arguments) {
    }

    public record BindingView(String exchange, String destination, String destinationType, String routingKey) {
    }

    public record PurgeResult(String queue, int purgedMessages) {
    }

    /**
     * @param routed false si el exchange no tenía ningún binding para la clave (el mensaje se descartó)
     */
    public record PublishResult(String messageId, String exchange, String routingKey, boolean routed, String detail) {
    }
}
