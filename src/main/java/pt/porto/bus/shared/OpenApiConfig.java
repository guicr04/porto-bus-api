package pt.porto.bus.shared;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The published contract at /v3/api-docs.
 *
 * <p>springdoc describes models with its own (Jackson 2) mapper, which knows
 * nothing about the SNAKE_CASE strategy the API serialises with. Without this
 * the document would advertise `arrivalMinutes` for a field that is sent as
 * `arrival_minutes`.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

  @Bean
  ModelResolver snakeCaseModelResolver() {
    return new ModelResolver(Json.mapper().copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
  }

  @Bean
  OpenAPI openApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Porto Bus API")
                .version("0.2.0")
                .description("A personal wrapper around Porto's STCP bus system. See the README for behaviour."));
  }
}
