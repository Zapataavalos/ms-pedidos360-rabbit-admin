package cl.duoc.pedidos360.rabbitadmin.config;

import cl.duoc.pedidos360.rabbitadmin.client.RabbitManagementClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * Clientes hacia RabbitMQ:
 * <ul>
 *   <li>AMQP (RabbitAdmin / RabbitTemplate, autoconfigurados por Spring Boot): crear y eliminar recursos, publicar.</li>
 *   <li>HTTP (API de administración, puerto 15672): listar colas, exchanges y bindings, que AMQP no permite.</li>
 * </ul>
 */
@Configuration
public class RabbitClientsConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitClientsConfig.class);

    /** Un RestClient por nodo, con autenticación básica. */
    @Bean
    RabbitManagementClient rabbitManagementClient(RestClient.Builder builder, RabbitAdminProperties props) {
        RabbitAdminProperties.Management management = props.management();
        List<RestClient> nodes = management.urls().stream()
                .map(url -> builder.clone()
                        .baseUrl(url)
                        .requestFactory(requestFactory())
                        .defaultHeaders(h -> h.setBasicAuth(management.username(), management.password()))
                        .build())
                .toList();
        return new RabbitManagementClient(nodes, management.virtualHost());
    }

    /** Los mensajes de prueba que no llegan a ninguna cola quedan registrados. */
    @Bean
    RabbitTemplateCustomizer returnsLogger() {
        return template -> template.setReturnsCallback(returned -> log.warn(
                "[NO ENTREGADO] {} --{}--> ninguna cola recibió el mensaje de prueba ({})",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }
}
