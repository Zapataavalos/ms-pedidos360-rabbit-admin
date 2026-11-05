package cl.duoc.pedidos360.rabbitadmin.client;

import cl.duoc.pedidos360.rabbitadmin.exception.BrokerUnavailableException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lectura de la API HTTP de administración de RabbitMQ (/api/queues, /api/exchanges, /api/bindings).
 * Conoce los nodos del clúster: si uno no responde, consulta el siguiente. Solo la usa RabbitAdminService.
 */
public class RabbitManagementClient {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() {
    };

    private final List<RestClient> nodes;
    private final String virtualHost;

    public RabbitManagementClient(List<RestClient> nodes, String virtualHost) {
        this.nodes = nodes;
        this.virtualHost = virtualHost;
    }

    public List<Map<String, Object>> queues() {
        return list("/api/queues/{vhost}");
    }

    public Optional<Map<String, Object>> queue(String name) {
        return find("/api/queues/{vhost}/{name}", name);
    }

    public List<Map<String, Object>> exchanges() {
        return list("/api/exchanges/{vhost}");
    }

    public Optional<Map<String, Object>> exchange(String name) {
        return find("/api/exchanges/{vhost}/{name}", name);
    }

    public List<Map<String, Object>> bindings() {
        return list("/api/bindings/{vhost}");
    }

    private List<Map<String, Object>> list(String path) {
        List<Map<String, Object>> result = onAnyNode(rest -> rest.get().uri(path, virtualHost).retrieve().body(LIST));
        return result == null ? List.of() : result;
    }

    private Optional<Map<String, Object>> find(String path, String name) {
        try {
            return Optional.ofNullable(onAnyNode(rest -> rest.get().uri(path, virtualHost, name).retrieve().body(OBJECT)));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    /** Ejecuta la consulta en el primer nodo que responda. */
    private <T> T onAnyNode(java.util.function.Function<RestClient, T> call) {
        ResourceAccessException last = null;
        for (RestClient node : nodes) {
            try {
                return call.apply(node);
            } catch (ResourceAccessException ex) {
                last = ex; // nodo caído o inalcanzable: se prueba el siguiente
            }
        }
        throw new BrokerUnavailableException("La API de administración de RabbitMQ no responde en ningún nodo"
                + (last == null ? "" : ": " + last.getMessage()));
    }
}
