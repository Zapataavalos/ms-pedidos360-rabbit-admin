package cl.duoc.pedidos360.rabbitadmin.dto;

/** Reglas de nombres compartidas por los DTO y los parámetros de ruta. */
public final class ResourceNames {

    /** Letras, números, punto, guion y guion bajo; "amq." está reservado por RabbitMQ. */
    public static final String NAME_REGEX = "^(?!amq\\.)[A-Za-z0-9._-]+$";
    public static final String NAME_MESSAGE =
            "solo admite letras, números, '.', '-' y '_', y no puede comenzar con 'amq.' (prefijo reservado)";
    public static final int NAME_MAX = 200;

    /** Clave de enrutamiento (vacía, palabra o palabras separadas por punto; * y # solo en topic). */
    public static final String ROUTING_KEY_REGEX = "^[A-Za-z0-9._*#-]*$";
    public static final String ROUTING_KEY_MESSAGE = "solo admite letras, números, '.', '-', '_', '*' y '#'";
    public static final int ROUTING_KEY_MAX = 255;

    private ResourceNames() {
    }
}
