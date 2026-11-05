package cl.duoc.pedidos360.rabbitadmin;

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
import cl.duoc.pedidos360.rabbitadmin.service.RabbitAdminService;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Lógica del administrador con el broker simulado (AmqpAdmin, RabbitTemplate y la API HTTP). */
class RabbitAdminServiceTests {

    AmqpAdmin admin;
    RabbitTemplate rabbit;
    RabbitManagementClient management;
    RabbitAdminService service;

    @BeforeEach
    void setUp() {
        admin = mock(AmqpAdmin.class);
        rabbit = mock(RabbitTemplate.class);
        management = mock(RabbitManagementClient.class);
        RabbitAdminProperties props = new RabbitAdminProperties(
                new RabbitAdminProperties.Management(List.of("http://rabbitmq1:15672"), "guest", "guest", "/"),
                new RabbitAdminProperties.DeadLetter("pedidos360.dlx", ".dlq"),
                Set.of("pedidos360.eventos", "pedidos360.documentos.generar"),
                Duration.ofSeconds(1));
        service = new RabbitAdminService(admin, rabbit, management, props);
    }

    private static CreateQueueRequest queue(String name) {
        return new CreateQueueRequest(name, null, null, 60_000L, 100, null);
    }

    private static AmqpIOException channelClosed(int code, String text) {
        AMQP.Channel.Close close = new AMQP.Channel.Close.Builder().replyCode(code).replyText(text).build();
        return new AmqpIOException(new IOException(new ShutdownSignalException(false, false, close, null)));
    }

    // ------------------------------------------------------------------ Colas

    @Test
    void creaColaConSuDlqEnlazadaALaDlx() {
        QueueView created = service.createQueue(queue("demo.cola"));

        ArgumentCaptor<Queue> queues = ArgumentCaptor.forClass(Queue.class);
        verify(admin, times(2)).declareQueue(queues.capture());
        Queue dlq = queues.getAllValues().get(0);
        Queue main = queues.getAllValues().get(1);
        assertThat(dlq.getName()).isEqualTo("demo.cola.dlq");
        assertThat(main.getName()).isEqualTo("demo.cola");
        assertThat(main.getArguments())
                .containsEntry("x-dead-letter-exchange", "pedidos360.dlx")
                .containsEntry("x-dead-letter-routing-key", "demo.cola.dlq")
                .containsEntry("x-message-ttl", 60_000)
                .containsEntry("x-max-length", 100L);
        verify(admin).declareExchange(argThat(e -> e.getName().equals("pedidos360.dlx") && e.getType().equals("direct")));
        verify(admin).declareBinding(argThat(b -> b.getExchange().equals("pedidos360.dlx")
                && b.getDestination().equals("demo.cola.dlq") && b.getRoutingKey().equals("demo.cola.dlq")));
        assertThat(created.deadLetterQueue()).isEqualTo("demo.cola.dlq");
    }

    @Test
    void creaColaSinDlqSiSeIndica() {
        service.createQueue(new CreateQueueRequest("demo.simple", null, null, null, null, false));

        verify(admin).declareQueue(argThat(q -> q.getName().equals("demo.simple") && q.getArguments().isEmpty()));
        verify(admin, never()).declareExchange(any());
    }

    @Test
    void noCreaUnaColaQueYaExiste() {
        when(admin.getQueueInfo("demo.cola")).thenReturn(new QueueInformation("demo.cola", 0, 0));

        assertThatThrownBy(() -> service.createQueue(queue("demo.cola")))
                .isInstanceOf(ResourceConflictException.class).hasMessageContaining("ya existe");
        verify(admin, never()).declareQueue(any());
    }

    @Test
    void conElBrokerCaidoResponde503() {
        when(admin.getQueueInfo(anyString())).thenThrow(new AmqpConnectException(new ConnectException("refused")));

        assertThatThrownBy(() -> service.createQueue(queue("demo.cola")))
                .isInstanceOf(BrokerUnavailableException.class);
    }

    @Test
    void noEliminaRecursosProtegidosDelSistema() {
        assertThatThrownBy(() -> service.deleteQueue("pedidos360.documentos.generar", false, false))
                .isInstanceOf(ResourceConflictException.class).hasMessageContaining("mensajería del sistema");
        verifyNoInteractions(admin);
    }

    @Test
    void eliminarColaInexistenteResponde404() {
        assertThatThrownBy(() -> service.deleteQueue("no.existe", false, false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void precondicionRechazadaPorRabbitMqEsUnConflicto() {
        when(admin.getQueueInfo("demo.cola")).thenReturn(new QueueInformation("demo.cola", 3, 0));
        doThrow(channelClosed(AMQP.PRECONDITION_FAILED, "PRECONDITION_FAILED - queue 'demo.cola' not empty"))
                .when(admin).deleteQueue("demo.cola", false, true);

        assertThatThrownBy(() -> service.deleteQueue("demo.cola", false, true))
                .isInstanceOf(ResourceConflictException.class).hasMessageContaining("not empty");
    }

    @Test
    void vaciaUnaCola() {
        when(admin.getQueueInfo("demo.cola.dlq")).thenReturn(new QueueInformation("demo.cola.dlq", 4, 0));
        when(admin.purgeQueue("demo.cola.dlq")).thenReturn(4);

        assertThat(service.purgeQueue("demo.cola.dlq").purgedMessages()).isEqualTo(4);
    }

    @Test
    void listaColasDesdeLaApiDeAdministracion() {
        when(management.queues()).thenReturn(List.of(Map.of("name", "pedidos360.documentos.generar", "type", "classic",
                "durable", true, "messages", 2, "consumers", 1, "node", "rabbit@rabbitmq2",
                "arguments", Map.of("x-dead-letter-exchange", "pedidos360.dlx",
                        "x-dead-letter-routing-key", "pedidos360.documentos.generar.dlq"))));

        assertThat(service.listQueues()).singleElement().satisfies(q -> {
            assertThat(q.messages()).isEqualTo(2);
            assertThat(q.node()).isEqualTo("rabbit@rabbitmq2");
            assertThat(q.deadLetterQueue()).isEqualTo("pedidos360.documentos.generar.dlq");
        });
    }

    // ------------------------------------------------------------------ Exchanges

    @Test
    void creaExchangeDelTipoPedido() {
        when(management.exchange("demo.eventos")).thenReturn(Optional.empty());

        ExchangeView created = service.createExchange(new CreateExchangeRequest("demo.eventos", "Topic", null, null));

        verify(admin).declareExchange(argThat(e -> e.getName().equals("demo.eventos") && e.getType().equals("topic")
                && e.isDurable()));
        assertThat(created.type()).isEqualTo("topic");
    }

    @Test
    void noCreaUnExchangeQueYaExiste() {
        when(management.exchange("pedidos360.eventos")).thenReturn(Optional.of(Map.of("type", "topic")));

        assertThatThrownBy(() -> service.createExchange(new CreateExchangeRequest("pedidos360.eventos", "topic", null, null)))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void eliminarExchangeInexistenteResponde404() {
        when(management.exchange("no.existe")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteExchange("no.existe")).isInstanceOf(ResourceNotFoundException.class);
        verify(admin, never()).deleteExchange(anyString());
    }

    // ------------------------------------------------------------------ Bindings

    @Test
    void creaBindingTopicConPatron() {
        when(management.exchange("demo.eventos")).thenReturn(Optional.of(Map.of("type", "topic")));
        when(admin.getQueueInfo("demo.cola")).thenReturn(new QueueInformation("demo.cola", 0, 0));

        service.createBinding(new CreateBindingRequest("demo.eventos", "demo.cola", "pedido.#"));

        verify(admin).declareBinding(argThat(b -> b.getExchange().equals("demo.eventos")
                && b.getDestination().equals("demo.cola") && b.getRoutingKey().equals("pedido.#")));
    }

    @Test
    void validaLaClaveSegunElTipoDeExchange() {
        when(management.exchange("demo.directo")).thenReturn(Optional.of(Map.of("type", "direct")));
        when(management.exchange("demo.eventos")).thenReturn(Optional.of(Map.of("type", "topic")));
        when(management.exchange("demo.fanout")).thenReturn(Optional.of(Map.of("type", "fanout")));

        assertThatThrownBy(() -> service.createBinding(new CreateBindingRequest("demo.directo", "q", "documento.*")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("solo funcionan en exchanges topic");
        assertThatThrownBy(() -> service.createBinding(new CreateBindingRequest("demo.eventos", "q", "pedido.#x")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("Patrón topic inválido");
        assertThatThrownBy(() -> service.createBinding(new CreateBindingRequest("demo.fanout", "q", "algo")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("fanout");
        verify(admin, never()).declareBinding(any());
    }

    @Test
    void bindingHaciaUnaColaInexistenteResponde404() {
        when(management.exchange("demo.eventos")).thenReturn(Optional.of(Map.of("type", "topic")));

        assertThatThrownBy(() -> service.createBinding(new CreateBindingRequest("demo.eventos", "no.existe", "pedido.#")))
                .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining("no.existe");
    }

    @Test
    void eliminaUnBindingExistente() {
        when(management.bindings()).thenReturn(List.of(
                Map.of("source", "demo.eventos", "destination", "demo.cola", "destination_type", "queue",
                        "routing_key", "pedido.#")));

        service.deleteBinding("demo.eventos", "demo.cola", "pedido.#");

        verify(admin).removeBinding(argThat(b -> b.getRoutingKey().equals("pedido.#")));
        assertThatThrownBy(() -> service.deleteBinding("demo.eventos", "demo.cola", "otra.clave"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listaBindingsSinLosImplicitosDelExchangePorDefecto() {
        when(management.bindings()).thenReturn(List.of(
                Map.of("source", "", "destination", "demo.cola", "destination_type", "queue", "routing_key", "demo.cola"),
                Map.of("source", "pedidos360.eventos", "destination", "pedidos360.notificaciones.pedido",
                        "destination_type", "queue", "routing_key", "pedido.#")));

        assertThat(service.listBindings(null, null)).extracting(BindingView::exchange)
                .containsExactly("pedidos360.eventos");
    }

    // ------------------------------------------------------------------ Mensajes de prueba

    @Test
    void publicaYInformaQueElMensajeLlegoAUnaCola() {
        when(management.exchange("pedidos360.eventos")).thenReturn(Optional.of(Map.of("type", "topic")));
        doAnswer(inv -> {
            inv.getArgument(3, CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        PublishResult result = service.publish(new PublishMessageRequest("pedidos360.eventos", "pedido.creado", "{\"x\":1}"));

        assertThat(result.routed()).isTrue();
        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq("pedidos360.eventos"), eq("pedido.creado"), message.capture(), any(CorrelationData.class));
        assertThat(new String(message.getValue().getBody())).isEqualTo("{\"x\":1}");
        assertThat(message.getValue().getMessageProperties().getContentType()).isEqualTo("application/json");
    }

    @Test
    void informaCuandoNingunaColaRecibioElMensaje() {
        when(management.exchange("pedidos360.eventos")).thenReturn(Optional.of(Map.of("type", "topic")));
        doAnswer(inv -> {
            CorrelationData correlation = inv.getArgument(3, CorrelationData.class);
            correlation.setReturned(new ReturnedMessage(inv.getArgument(2, Message.class), 312, "NO_ROUTE",
                    "pedidos360.eventos", "sin.destino"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        PublishResult result = service.publish(new PublishMessageRequest("pedidos360.eventos", "sin.destino", "{}"));

        assertThat(result.routed()).isFalse();
        assertThat(result.detail()).contains("descartó");
    }

    @Test
    void sinConfirmacionDelBrokerResponde503() {
        when(management.exchange("pedidos360.eventos")).thenReturn(Optional.of(Map.of("type", "topic")));

        assertThatThrownBy(() -> service.publish(new PublishMessageRequest("pedidos360.eventos", "pedido.creado", "{}")))
                .isInstanceOf(BrokerUnavailableException.class).hasMessageContaining("no confirmó");
    }

    @Test
    void publicarEnUnExchangeInexistenteResponde404() {
        when(management.exchange("no.existe")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.publish(new PublishMessageRequest("no.existe", "x", "{}")))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(rabbit);
    }
}
