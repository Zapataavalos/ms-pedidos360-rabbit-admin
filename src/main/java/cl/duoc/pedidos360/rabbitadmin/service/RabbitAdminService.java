package cl.duoc.pedidos360.rabbitadmin.service;

import cl.duoc.pedidos360.rabbitadmin.client.RabbitManagementClient;
import cl.duoc.pedidos360.rabbitadmin.config.RabbitAdminProperties;
import cl.duoc.pedidos360.rabbitadmin.dto.CreateBindingRequest;
import cl.duoc.pedidos360.rabbitadmin.dto.CreateExchangeRequest;
import cl.duoc.pedidos360.rabbitadmin.dto.CreateQueueRequest;
import cl.duoc.pedidos360.rabbitadmin.dto.PublishMessageRequest;
import cl.duoc.pedidos360.rabbitadmin.dto.Views.*;
import cl.duoc.pedidos360.rabbitadmin.exception.BrokerUnavailableException;
import cl.duoc.pedidos360.rabbitadmin.exception.InvalidRequestException;
import cl.duoc.pedidos360.rabbitadmin.exception.ResourceConflictException;
import cl.duoc.pedidos360.rabbitadmin.exception.ResourceNotFoundException;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Toda la administración de RabbitMQ pasa por aquí: crear, consultar y eliminar colas, exchanges y bindings,
 * y publicar mensajes de prueba. Los controladores solo llaman a estos métodos; no conocen RabbitAdmin,
 * RabbitTemplate ni la API HTTP del broker.
 * <ul>
 *   <li>Escrituras: por AMQP con {@link AmqpAdmin} (RabbitAdmin de Spring).</li>
 *   <li>Lecturas: con la API de administración (AMQP no permite listar recursos).</li>
 *   <li>Errores del broker → excepciones de la API: 404 no existe, 409 conflicto, 503 broker caído.</li>
 * </ul>
 */
@Service
public class RabbitAdminService {

    private static final Logger log = LoggerFactory.getLogger(RabbitAdminService.class);

    /** Patrón topic: palabras separadas por punto, donde * y # ocupan una palabra completa. */
    private static final Pattern TOPIC_PATTERN =
            Pattern.compile("^(?:[A-Za-z0-9_-]+|\\*|#)(?:\\.(?:[A-Za-z0-9_-]+|\\*|#))*$");

    private final AmqpAdmin admin;
    private final RabbitTemplate rabbit;
    private final RabbitManagementClient management;
    private final RabbitAdminProperties props;

    public RabbitAdminService(AmqpAdmin admin, RabbitTemplate rabbit, RabbitManagementClient management,
                              RabbitAdminProperties props) {
        this.admin = admin;
        this.rabbit = rabbit;
        this.management = management;
        this.props = props;
    }

    // =================================================================================== Colas

    public List<QueueView> listQueues() {
        return management.queues().stream().map(this::toQueueView)
                .sorted(Comparator.comparing(QueueView::name)).toList();
    }

    public QueueView getQueue(String name) {
        return management.queue(name).map(this::toQueueView)
                .orElseThrow(() -> new ResourceNotFoundException("La cola " + name + " no existe"));
    }

    /**
     * Crea la cola. Con DLQ (opción por defecto) crea además &lt;nombre&gt;.dlq, la enlaza a la DLX y configura
     * la cola principal para que sus mensajes rechazados lleguen ahí.
     */
    public QueueView createQueue(CreateQueueRequest request) {
        String name = request.name();
        return amqp("crear la cola " + name, () -> {
            if (admin.getQueueInfo(name) != null) {
                throw new ResourceConflictException("La cola " + name + " ya existe");
            }
            QueueBuilder builder = request.isDurable() ? QueueBuilder.durable(name) : QueueBuilder.nonDurable(name);
            if (request.isAutoDelete()) {
                builder.autoDelete();
            }
            if (request.messageTtlMs() != null) {
                builder.ttl(request.messageTtlMs().intValue());
            }
            if (request.maxLength() != null) {
                builder.maxLength(request.maxLength());
            }
            String deadLetterQueue = null;
            if (request.withDeadLetterQueue()) {
                deadLetterQueue = name + props.deadLetter().suffix();
                declareDeadLetterQueue(deadLetterQueue);
                builder.deadLetterExchange(props.deadLetter().exchange()).deadLetterRoutingKey(deadLetterQueue);
            }
            Queue queue = builder.build();
            admin.declareQueue(queue);
            log.info("[ADMIN] Cola {} creada{}", name, deadLetterQueue == null ? "" : " con DLQ " + deadLetterQueue);
            return new QueueView(name, "classic", queue.isDurable(), queue.isAutoDelete(), 0, 0, 0, 0, null,
                    queue.getArguments(), deadLetterQueue);
        });
    }

    /**
     * @param ifUnused solo si no tiene consumidores
     * @param ifEmpty  solo si no tiene mensajes
     */
    public void deleteQueue(String name, boolean ifUnused, boolean ifEmpty) {
        guardNotProtected(name, "la cola");
        amqp("eliminar la cola " + name, () -> {
            requireQueue(name);
            admin.deleteQueue(name, ifUnused, ifEmpty);
            log.info("[ADMIN] Cola {} eliminada", name);
            return null;
        });
    }

    /** Elimina todos los mensajes de la cola (por ejemplo, una DLQ ya revisada). */
    public PurgeResult purgeQueue(String name) {
        return amqp("vaciar la cola " + name, () -> {
            requireQueue(name);
            int purged = admin.purgeQueue(name);
            log.info("[ADMIN] Cola {} vaciada: {} mensajes eliminados", name, purged);
            return new PurgeResult(name, purged);
        });
    }

    // =================================================================================== Exchanges

    public List<ExchangeView> listExchanges() {
        return management.exchanges().stream().map(RabbitAdminService::toExchangeView)
                .sorted(Comparator.comparing(ExchangeView::name)).toList();
    }

    public ExchangeView createExchange(CreateExchangeRequest request) {
        if (management.exchange(request.name()).isPresent()) {
            throw new ResourceConflictException("El exchange " + request.name() + " ya existe");
        }
        ExchangeBuilder builder = new ExchangeBuilder(request.name(), request.normalizedType())
                .durable(request.isDurable());
        if (request.isAutoDelete()) {
            builder.autoDelete();
        }
        Exchange exchange = builder.build();
        amqp("crear el exchange " + request.name(), () -> {
            admin.declareExchange(exchange);
            return null;
        });
        log.info("[ADMIN] Exchange {} ({}) creado", exchange.getName(), exchange.getType());
        return new ExchangeView(exchange.getName(), exchange.getType(), exchange.isDurable(), exchange.isAutoDelete(),
                false, false, exchange.getArguments());
    }

    public void deleteExchange(String name) {
        guardNotProtected(name, "el exchange");
        requireExchangeType(name);
        amqp("eliminar el exchange " + name, () -> admin.deleteExchange(name));
        log.info("[ADMIN] Exchange {} eliminado", name);
    }

    // =================================================================================== Bindings

    /** Bindings entre exchanges y colas (sin los implícitos del exchange por defecto), con filtros opcionales. */
    public List<BindingView> listBindings(String exchange, String queue) {
        return management.bindings().stream()
                .map(RabbitAdminService::toBindingView)
                .filter(b -> !b.exchange().isEmpty())
                .filter(b -> exchange == null || b.exchange().equals(exchange))
                .filter(b -> queue == null || b.destination().equals(queue))
                .sorted(Comparator.comparing(BindingView::exchange).thenComparing(BindingView::destination))
                .toList();
    }

    public BindingView createBinding(CreateBindingRequest request) {
        String type = requireExchangeType(request.exchange());
        validateRoutingKey(type, request.routingKey());
        amqp("crear el binding", () -> {
            requireQueue(request.queue());
            return null;
        });
        if (bindingExists(request.exchange(), request.queue(), request.routingKey())) {
            throw new ResourceConflictException("El binding " + describe(request.exchange(), request.routingKey(),
                    request.queue()) + " ya existe");
        }
        amqp("crear el binding", () -> {
            admin.declareBinding(binding(request.exchange(), request.queue(), request.routingKey()));
            return null;
        });
        log.info("[ADMIN] Binding {} creado", describe(request.exchange(), request.routingKey(), request.queue()));
        return new BindingView(request.exchange(), request.queue(), "queue", request.routingKey());
    }

    public void deleteBinding(String exchange, String queue, String routingKey) {
        guardNotProtected(queue, "un binding de la cola");
        if (!bindingExists(exchange, queue, routingKey)) {
            throw new ResourceNotFoundException("El binding " + describe(exchange, routingKey, queue) + " no existe");
        }
        amqp("eliminar el binding", () -> {
            admin.removeBinding(binding(exchange, queue, routingKey));
            return null;
        });
        log.info("[ADMIN] Binding {} eliminado", describe(exchange, routingKey, queue));
    }

    // =================================================================================== Mensajes de prueba

    /**
     * Publica el payload tal cual y espera la confirmación del broker. Informa si el mensaje llegó a alguna cola
     * (si ningún binding coincide con la clave, RabbitMQ lo devuelve y aquí se informa routed=false).
     */
    public PublishResult publish(PublishMessageRequest request) {
        requireExchangeType(request.exchange());
        String messageId = UUID.randomUUID().toString();
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setMessageId(messageId);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message message = new Message(request.payload().getBytes(StandardCharsets.UTF_8), properties);
        CorrelationData correlation = new CorrelationData(messageId);

        amqp("publicar el mensaje", () -> {
            rabbit.send(request.exchange(), request.routingKey(), message, correlation);
            return null;
        });
        CorrelationData.Confirm confirm = awaitConfirm(correlation);
        if (!confirm.ack()) {
            throw new BrokerUnavailableException("RabbitMQ no aceptó el mensaje: " + confirm.reason());
        }
        boolean routed = correlation.getReturned() == null;
        log.info("[ADMIN] Mensaje de prueba {} publicado en {} --{}-- (enrutado: {})", messageId, request.exchange(),
                request.routingKey(), routed);
        String detail = routed ? "El mensaje llegó al menos a una cola"
                : "Ninguna cola está enlazada a " + request.exchange() + " con la clave '" + request.routingKey()
                + "': RabbitMQ descartó el mensaje";
        return new PublishResult(messageId, request.exchange(), request.routingKey(), routed, detail);
    }

    // =================================================================================== Reglas y apoyo

    /**
     * Valida la clave según el tipo de exchange: en topic, * y # deben ocupar una palabra completa; en direct
     * la clave es exacta (sin comodines); fanout ignora la clave.
     */
    static void validateRoutingKey(String exchangeType, String routingKey) {
        boolean wildcard = routingKey.contains("*") || routingKey.contains("#");
        switch (exchangeType) {
            case "topic" -> {
                if (!routingKey.isEmpty() && !TOPIC_PATTERN.matcher(routingKey).matches()) {
                    throw new InvalidRequestException("Patrón topic inválido '" + routingKey + "': usa palabras "
                            + "separadas por '.', donde * (una palabra) y # (cero o más) van solos. Ej.: pedido.*, pedido.#");
                }
            }
            case "direct" -> {
                if (wildcard) {
                    throw new InvalidRequestException("Los comodines * y # solo funcionan en exchanges topic; "
                            + "en un exchange direct la clave debe ser exacta");
                }
                if (routingKey.isEmpty()) {
                    throw new InvalidRequestException("Un exchange direct necesita una clave de enrutamiento");
                }
            }
            case "fanout" -> {
                if (!routingKey.isEmpty()) {
                    throw new InvalidRequestException("Un exchange fanout ignora la clave: envíala vacía (\"\")");
                }
            }
            default -> throw new InvalidRequestException("Los bindings a exchanges de tipo " + exchangeType
                    + " requieren argumentos que esta API no administra");
        }
    }

    private void declareDeadLetterQueue(String deadLetterQueue) {
        DirectExchange dlx = new DirectExchange(props.deadLetter().exchange(), true, false);
        Queue dlq = QueueBuilder.durable(deadLetterQueue).build();
        admin.declareExchange(dlx);
        admin.declareQueue(dlq);
        admin.declareBinding(BindingBuilder.bind(dlq).to(dlx).with(deadLetterQueue));
    }

    private void guardNotProtected(String name, String what) {
        if (props.isProtected(name)) {
            throw new ResourceConflictException("No se puede eliminar " + what + " " + name
                    + ": es parte de la mensajería del sistema");
        }
    }

    private void requireQueue(String name) {
        if (admin.getQueueInfo(name) == null) {
            throw new ResourceNotFoundException("La cola " + name + " no existe");
        }
    }

    /** Devuelve el tipo del exchange o 404 si no existe. */
    private String requireExchangeType(String name) {
        return management.exchange(name)
                .map(e -> String.valueOf(e.get("type")))
                .orElseThrow(() -> new ResourceNotFoundException("El exchange " + name + " no existe"));
    }

    private boolean bindingExists(String exchange, String queue, String routingKey) {
        return listBindings(exchange, queue).stream().anyMatch(b -> b.routingKey().equals(routingKey));
    }

    private static Binding binding(String exchange, String queue, String routingKey) {
        return new Binding(queue, Binding.DestinationType.QUEUE, exchange, routingKey, null);
    }

    private static String describe(String exchange, String routingKey, String queue) {
        return exchange + " --" + routingKey + "--> " + queue;
    }

    private CorrelationData.Confirm awaitConfirm(CorrelationData correlation) {
        try {
            return correlation.getFuture().get(props.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException ex) {
            throw new BrokerUnavailableException("RabbitMQ no confirmó la publicación a tiempo");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrokerUnavailableException("Se interrumpió la espera de la confirmación de RabbitMQ");
        }
    }

    /** Ejecuta una operación AMQP y traduce los errores del broker a errores de la API. */
    private <T> T amqp(String action, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (AmqpConnectException ex) {
            throw new BrokerUnavailableException("RabbitMQ no está disponible para " + action);
        } catch (AmqpException ex) {
            AMQP.Channel.Close close = channelClose(ex);
            if (close == null) {
                throw ex;
            }
            String reason = close.getReplyText();
            throw switch (close.getReplyCode()) {
                case AMQP.NOT_FOUND -> new ResourceNotFoundException("No se pudo " + action + ": " + reason);
                case AMQP.PRECONDITION_FAILED, AMQP.ACCESS_REFUSED, AMQP.RESOURCE_LOCKED ->
                        new ResourceConflictException("RabbitMQ rechazó " + action + ": " + reason);
                default -> ex;
            };
        }
    }

    /** Motivo con que RabbitMQ cerró el canal (404, 406...), si la excepción lo trae. */
    static AMQP.Channel.Close channelClose(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ShutdownSignalException sse && sse.getReason() instanceof AMQP.Channel.Close close) {
                return close;
            }
        }
        return null;
    }

    private QueueView toQueueView(Map<String, Object> q) {
        Map<String, Object> args = map(q.get("arguments"));
        String deadLetterQueue = props.deadLetter().exchange().equals(args.get("x-dead-letter-exchange"))
                ? String.valueOf(args.get("x-dead-letter-routing-key")) : null;
        return new QueueView(str(q, "name"), str(q, "type"), bool(q, "durable"), bool(q, "auto_delete"),
                num(q, "messages"), num(q, "messages_ready"), num(q, "messages_unacknowledged"), num(q, "consumers"),
                str(q, "node"), args, deadLetterQueue);
    }

    private static ExchangeView toExchangeView(Map<String, Object> e) {
        String name = str(e, "name");
        boolean system = name.isEmpty() || name.startsWith("amq.");
        return new ExchangeView(name.isEmpty() ? "(AMQP default)" : name, str(e, "type"), bool(e, "durable"),
                bool(e, "auto_delete"), bool(e, "internal"), system, map(e.get("arguments")));
    }

    private static BindingView toBindingView(Map<String, Object> b) {
        return new BindingView(str(b, "source"), str(b, "destination"), str(b, "destination_type"),
                str(b, "routing_key"));
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }

    private static boolean bool(Map<String, Object> m, String key) {
        return Boolean.TRUE.equals(m.get(key));
    }

    private static long num(Map<String, Object> m, String key) {
        return m.get(key) instanceof Number n ? n.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
