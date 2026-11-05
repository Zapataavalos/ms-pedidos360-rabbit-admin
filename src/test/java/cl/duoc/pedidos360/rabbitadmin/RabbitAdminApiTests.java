package cl.duoc.pedidos360.rabbitadmin;

import cl.duoc.pedidos360.rabbitadmin.dto.CreateQueueRequest;
import cl.duoc.pedidos360.rabbitadmin.dto.Views.*;
import cl.duoc.pedidos360.rabbitadmin.exception.BrokerUnavailableException;
import cl.duoc.pedidos360.rabbitadmin.exception.InvalidRequestException;
import cl.duoc.pedidos360.rabbitadmin.exception.ResourceConflictException;
import cl.duoc.pedidos360.rabbitadmin.exception.ResourceNotFoundException;
import cl.duoc.pedidos360.rabbitadmin.service.RabbitAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * API REST del administrador: rutas, verbos, códigos de estado y validación de la entrada.
 * El servicio se simula; su lógica se prueba en RabbitAdminServiceTests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RabbitAdminApiTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RabbitAdminService service;

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ------------------------------------------------------------------ Colas

    @Test
    void listaColas() throws Exception {
        when(service.listQueues()).thenReturn(List.of(new QueueView("pedidos360.documentos.generar", "classic", true,
                false, 2, 2, 0, 1, "rabbit@rabbitmq1", Map.of(), "pedidos360.documentos.generar.dlq")));

        mvc.perform(get("/api/rabbitmq/queues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name", is("pedidos360.documentos.generar")))
                .andExpect(jsonPath("$[0].deadLetterQueue", is("pedidos360.documentos.generar.dlq")))
                .andExpect(jsonPath("$[0].node", is("rabbit@rabbitmq1")));
    }

    @Test
    void creaColaYRespondeCreatedConLocation() throws Exception {
        when(service.createQueue(any())).thenReturn(new QueueView("demo.cola", "classic", true, false, 0, 0, 0, 0,
                null, Map.of("x-dead-letter-exchange", "pedidos360.dlx"), "demo.cola.dlq"));

        postJson("/api/rabbitmq/queues", "{\"name\":\"demo.cola\",\"messageTtlMs\":60000}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/rabbitmq/queues/demo.cola")))
                .andExpect(jsonPath("$.deadLetterQueue", is("demo.cola.dlq")));
        verify(service).createQueue(argThat((CreateQueueRequest r) ->
                r.name().equals("demo.cola") && r.withDeadLetterQueue() && r.isDurable()));
    }

    @Test
    void rechazaColaSinNombre() throws Exception {
        postJson("/api/rabbitmq/queues", "{\"name\":\"  \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name", containsString("es obligatorio")));
        verifyNoInteractions(service);
    }

    @Test
    void rechazaNombresConCaracteresInvalidosOReservados() throws Exception {
        postJson("/api/rabbitmq/queues", "{\"name\":\"cola con espacios\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name", containsString("solo admite letras")));
        postJson("/api/rabbitmq/queues", "{\"name\":\"amq.mi-cola\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name", containsString("amq.")));
        verifyNoInteractions(service);
    }

    @Test
    void rechazaConfiguracionesInvalidas() throws Exception {
        postJson("/api/rabbitmq/queues", "{\"name\":\"demo\",\"messageTtlMs\":-5,\"maxLength\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.messageTtlMs", is("debe ser mayor que 0")))
                .andExpect(jsonPath("$.errors.maxLength", is("debe ser mayor que 0")));
        postJson("/api/rabbitmq/queues", "{\"name\":\"temporal\",\"durable\":false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.deadLetterConfigurationValid", containsString("durable")));
        postJson("/api/rabbitmq/queues", "{\"name\":\"otra.dlq\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.nameNotReservedForDeadLetter", containsString(".dlq")));
        verifyNoInteractions(service);
    }

    @Test
    void cuerpoQueNoEsJsonResponde400() throws Exception {
        postJson("/api/rabbitmq/queues", "esto no es json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("JSON")));
    }

    @Test
    void eliminaColaConPrecondiciones() throws Exception {
        mvc.perform(delete("/api/rabbitmq/queues/demo.cola").param("ifEmpty", "true"))
                .andExpect(status().isNoContent());
        verify(service).deleteQueue("demo.cola", false, true);
    }

    @Test
    void nombreInvalidoEnLaRutaResponde400() throws Exception {
        mvc.perform(delete("/api/rabbitmq/queues/amq.direct"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name", containsString("amq.")));
        verifyNoInteractions(service);
    }

    @Test
    void erroresDelServicioSeTraducenACodigosHttp() throws Exception {
        doThrow(new ResourceNotFoundException("La cola x no existe")).when(service).deleteQueue(eq("x"), anyBoolean(), anyBoolean());
        doThrow(new ResourceConflictException("es parte de la mensajería del sistema"))
                .when(service).deleteQueue(eq("pedidos360.dlx"), anyBoolean(), anyBoolean());
        when(service.listQueues()).thenThrow(new BrokerUnavailableException("RabbitMQ no está disponible"));

        mvc.perform(delete("/api/rabbitmq/queues/x")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("La cola x no existe")));
        mvc.perform(delete("/api/rabbitmq/queues/pedidos360.dlx")).andExpect(status().isConflict());
        mvc.perform(get("/api/rabbitmq/queues")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void vaciaCola() throws Exception {
        when(service.purgeQueue("demo.cola.dlq")).thenReturn(new PurgeResult("demo.cola.dlq", 4));

        mvc.perform(delete("/api/rabbitmq/queues/demo.cola.dlq/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedMessages", is(4)));
    }

    // ------------------------------------------------------------------ Exchanges

    @Test
    void creaExchangeTopicSinImportarMayusculas() throws Exception {
        when(service.createExchange(any())).thenReturn(new ExchangeView("demo.eventos", "topic", true, false, false,
                false, Map.of()));

        postJson("/api/rabbitmq/exchanges", "{\"name\":\"demo.eventos\",\"type\":\"TOPIC\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/rabbitmq/exchanges/demo.eventos")))
                .andExpect(jsonPath("$.type", is("topic")));
    }

    @Test
    void rechazaTipoDeExchangeDesconocido() throws Exception {
        postJson("/api/rabbitmq/exchanges", "{\"name\":\"demo\",\"type\":\"broadcast\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.type", is("debe ser direct, topic, fanout o headers")));
        postJson("/api/rabbitmq/exchanges", "{\"name\":\"demo\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.type", containsString("obligatorio")));
        verifyNoInteractions(service);
    }

    @Test
    void eliminaExchange() throws Exception {
        mvc.perform(delete("/api/rabbitmq/exchanges/demo.eventos")).andExpect(status().isNoContent());
        verify(service).deleteExchange("demo.eventos");
    }

    // ------------------------------------------------------------------ Bindings

    @Test
    void creaBinding() throws Exception {
        when(service.createBinding(any())).thenReturn(new BindingView("demo.eventos", "demo.cola", "queue", "pedido.#"));

        postJson("/api/rabbitmq/bindings", "{\"exchange\":\"demo.eventos\",\"queue\":\"demo.cola\",\"routingKey\":\"pedido.#\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.routingKey", is("pedido.#")));
    }

    @Test
    void rechazaBindingIncompletoOConCaracteresInvalidos() throws Exception {
        postJson("/api/rabbitmq/bindings", "{\"exchange\":\"demo.eventos\",\"routingKey\":\"pedido/creado\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.queue", containsString("obligatoria")))
                .andExpect(jsonPath("$.errors.routingKey", containsString("solo admite")));
        verifyNoInteractions(service);
    }

    @Test
    void errorDeConfiguracionDelBindingResponde400() throws Exception {
        when(service.createBinding(any())).thenThrow(new InvalidRequestException(
                "Los comodines * y # solo funcionan en exchanges topic"));

        postJson("/api/rabbitmq/bindings", "{\"exchange\":\"pedidos360.documentos\",\"queue\":\"demo\",\"routingKey\":\"documento.*\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("topic")));
    }

    @Test
    void eliminaBindingPorParametros() throws Exception {
        mvc.perform(delete("/api/rabbitmq/bindings")
                        .param("exchange", "demo.eventos").param("queue", "demo.cola").param("routingKey", "pedido.#"))
                .andExpect(status().isNoContent());
        verify(service).deleteBinding("demo.eventos", "demo.cola", "pedido.#");

        mvc.perform(delete("/api/rabbitmq/bindings").param("exchange", "demo.eventos"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("queue")));
    }

    // ------------------------------------------------------------------ Mensajes

    @Test
    void publicaMensajeDePrueba() throws Exception {
        when(service.publish(any())).thenReturn(new PublishResult("id-1", "pedidos360.eventos", "pedido.creado", true,
                "El mensaje llegó al menos a una cola"));

        postJson("/api/rabbitmq/messages", "{\"exchange\":\"pedidos360.eventos\",\"routingKey\":\"pedido.creado\",\"payload\":\"{}\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.routed", is(true)));
    }

    @Test
    void noSePublicaConComodinesNiSinPayload() throws Exception {
        postJson("/api/rabbitmq/messages", "{\"exchange\":\"pedidos360.eventos\",\"routingKey\":\"pedido.#\",\"payload\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.routingKey", containsString("comodines")))
                .andExpect(jsonPath("$.errors.payload", containsString("obligatorio")));
        verifyNoInteractions(service);
    }
}
