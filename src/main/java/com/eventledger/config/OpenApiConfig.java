package com.eventledger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI eventLedgerOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Event Ledger API")
                        .version("1.0.0")
                        .description("""
                                Financial transaction event processing API.

                                **Capabilities:**
                                - **Idempotent** — duplicate `eventId` submissions are safe; the original event is returned with HTTP 200
                                - **Out-of-order tolerance** — events are always listed and balanced in chronological order by `eventTimestamp`
                                - **Concurrency-safe** — simultaneous POSTs with the same `eventId` are handled correctly
                                - **Paginated** event listing
                                - **H2 in-memory** database — no external setup required
                                """)
                        .contact(new Contact()
                                .name("Event Ledger Team")
                                .email("api@eventledger.com"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")));
    }
}
