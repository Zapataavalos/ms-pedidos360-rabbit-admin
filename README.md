# ms-pedidos360-rabbit-admin

Microservicio **administrador de RabbitMQ** de Pedidos360 (DSY1107 · Desarrollo Cloud Native I): API REST
para crear, consultar y eliminar colas, exchanges y bindings, y para publicar mensajes de prueba. Vive en
la red interna; el frontend y el API Gateway llegan a él a través del BFF, que exige el rol
**ADMINISTRADOR**.

## API

| Método | Ruta | Descripción | Respuesta |
|---|---|---|---|
| GET | `/api/rabbitmq/queues` | Colas con mensajes, consumidores, nodo y DLQ | 200 |
| GET | `/api/rabbitmq/queues/{name}` | Detalle | 200 / 404 |
| POST | `/api/rabbitmq/queues` | Crea la cola (por defecto con su DLQ `<name>.dlq`) | 201 + `Location` / 400 / 409 |
| DELETE | `/api/rabbitmq/queues/{name}?ifUnused=&ifEmpty=` | Elimina | 204 / 404 / 409 |
| DELETE | `/api/rabbitmq/queues/{name}/messages` | Vacía la cola | 200 |
| GET | `/api/rabbitmq/exchanges` | Exchanges (marca los del sistema) | 200 |
| POST | `/api/rabbitmq/exchanges` | Crea direct, topic, fanout o headers | 201 + `Location` / 400 / 409 |
| DELETE | `/api/rabbitmq/exchanges/{name}` | Elimina | 204 / 404 / 409 |
| GET | `/api/rabbitmq/bindings?exchange=&queue=` | Bindings (filtros opcionales) | 200 |
| POST | `/api/rabbitmq/bindings` | Enlaza una cola a un exchange | 201 / 400 / 404 / 409 |
| DELETE | `/api/rabbitmq/bindings?exchange=&queue=&routingKey=` | Elimina el binding (`#` → `%23`) | 204 / 404 |
| POST | `/api/rabbitmq/messages` | Publica un mensaje de prueba e informa si llegó a alguna cola | 202 |

Errores en JSON con detalle por campo: 400 (validación), 404 (no existe), 409 (ya existe, recurso protegido
o precondición rechazada por RabbitMQ), 503 (broker no disponible).

### Ejemplos

```json
POST /api/rabbitmq/queues      {"name": "demo.cola", "messageTtlMs": 60000, "maxLength": 1000}
POST /api/rabbitmq/exchanges   {"name": "demo.eventos", "type": "topic"}
POST /api/rabbitmq/bindings    {"exchange": "demo.eventos", "queue": "demo.cola", "routingKey": "pedido.#"}
POST /api/rabbitmq/messages    {"exchange": "demo.eventos", "routingKey": "pedido.creado", "payload": "{\"x\":1}"}
```

## Validaciones

- Nombres: obligatorios, hasta 200 caracteres, solo letras, números, `.`, `-` y `_`; el prefijo `amq.`
  está reservado por RabbitMQ (DTO con Bean Validation y `@Pattern` en los parámetros de ruta).
- Cola: TTL y largo máximo positivos y acotados; una cola con DLQ debe ser durable y no `autoDelete`; el
  sufijo `.dlq` está reservado.
- Exchange: tipo `direct`, `topic`, `fanout` o `headers`.
- Binding: el exchange y la cola deben existir; `*` y `#` solo en exchanges topic y como palabra completa
  (`pedido.*`, `pedido.#`); un direct necesita clave exacta y un fanout, clave vacía.
- Mensaje de prueba: sin comodines en la clave, payload obligatorio de hasta 64 KB.
- Los exchanges y colas del sistema (`protected-names` en `application.yml`) no se pueden eliminar.

## Diseño

```
web/        QueueController, ExchangeController, BindingController, MessageController (solo HTTP + DTO)
service/    RabbitAdminService: toda la lógica de administración y la traducción de errores del broker
client/     RabbitManagementClient: lectura de la API HTTP de administración, con respaldo entre nodos
config/     RabbitAdminProperties (application.yml), RabbitClientsConfig
dto/        Requests validados y vistas de respuesta (no exponen clases de RabbitMQ)
```

- Escrituras por AMQP con `AmqpAdmin` (RabbitAdmin de Spring); lecturas con la API de administración, porque
  AMQP no permite listar recursos.
- Al publicar se espera la confirmación del broker (publisher confirms) y se informa `routed=false` si
  ningún binding coincidió (publisher returns + `mandatory`).

## Ejecutar en local

```bash
RABBITMQ_ADDRESSES=localhost:5672 RABBITMQ_MANAGEMENT_URLS=http://localhost:15672 \
RABBITMQ_USERNAME=... RABBITMQ_PASSWORD=... ./mvnw spring-boot:run
```

## Pruebas

```bash
./mvnw test
```

- `RabbitAdminApiTests` (19): rutas, verbos, códigos HTTP, `Location` y validación de cada DTO y parámetro.
- `RabbitAdminServiceTests` (21): creación con DLQ, conflictos, recursos protegidos, reglas de claves por
  tipo de exchange, traducción de errores AMQP y publicación con y sin enrutamiento.
