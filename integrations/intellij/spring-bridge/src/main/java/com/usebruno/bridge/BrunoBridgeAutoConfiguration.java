package com.usebruno.bridge;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.*;

/** Explicitly opt in in a development application; never enabled by default. */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration")
@ConditionalOnClass(RequestMappingHandlerAdapter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "bruno.bridge.enabled", havingValue = "true")
public class BrunoBridgeAutoConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    BridgeServer brunoDevelopmentBridge(ConfigurableApplicationContext context, RequestMappingHandlerMapping mapping,
            RequestMappingHandlerAdapter adapter, @Value("${bruno.bridge.port:19090}") int port,
            @Value("${bruno.bridge.token:}") String token) throws Exception {
        return new BridgeServer(context, mapping, adapter, port, token);
    }
}
