package br.com.vistoria.messenger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"br.com.vistoria.messenger", "br.com.vistoria.core"})
@EntityScan("br.com.vistoria.core")
@EnableJpaRepositories(basePackages = "br.com.vistoria.core", considerNestedRepositories = true)
public class MessengerApplication {
    public static void main(String[] args) {
        SpringApplication.run(MessengerApplication.class, args);
    }
}
