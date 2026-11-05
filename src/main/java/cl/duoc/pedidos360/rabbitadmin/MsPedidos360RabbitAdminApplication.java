package cl.duoc.pedidos360.rabbitadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MsPedidos360RabbitAdminApplication {

	public static void main(String[] args) {
		SpringApplication.run(MsPedidos360RabbitAdminApplication.class, args);
	}

}
