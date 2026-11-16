package com.smartaudits.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web entry boundary of URL acquisition (ACQ-OSIV-BOUNDARY-01).
 *
 * <p>Open EntityManager in View stays enabled for every existing endpoint: several of them read
 * lazy associations outside a transaction. With it, a request thread has an EntityManager bound
 * from handler entry and, once that EntityManager has run a query, keeps its pool connection
 * until the response ends. Acquisition must not start on such a thread, and
 * {@code IngestaUrlService} rejects it (PV-13).
 *
 * <p>This class registers the same {@link OpenEntityManagerInViewInterceptor} Spring Boot would
 * register, for every path except {@link #RUTAS}. Defining the bean makes Spring Boot's own
 * registration back off, so there is exactly one. A request under {@link #RUTAS} therefore never
 * gets a request-scoped EntityManager: nothing is opened for it, so nothing is unbound or closed
 * on its behalf. Its handler reaches {@code IngestaUrlService} with no thread-bound JPA/JDBC
 * resource, and each repository call it makes before or after acquisition is self-contained.
 *
 * <p>The exclusion only makes the PV-13 guard satisfiable. It does not replace it: a handler
 * under {@link #RUTAS} that opens a transaction or binds a resource is still rejected, and a
 * handler mapped anywhere else still gets Open EntityManager in View and is rejected.
 *
 * <p>No handler is mapped under {@link #RUTAS} here, and authentication of the namespace is the
 * unchanged {@code anyRequest().authenticated()} rule. {@code spring.jpa.open-in-view=false}
 * keeps its meaning: nothing is registered for any path.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "spring.jpa", name = "open-in-view", havingValue = "true", matchIfMissing = true)
public class FronteraAdquisicionWebConfig {

    /** Reserved request namespace of future URL-acquisition handlers. */
    public static final String PREFIJO = "/adquisicion";

    /** Requests that never get a request-scoped EntityManager. */
    public static final String RUTAS = PREFIJO + "/**";

    @Bean
    public OpenEntityManagerInViewInterceptor openEntityManagerInViewInterceptor() {
        return new OpenEntityManagerInViewInterceptor();
    }

    @Bean
    public WebMvcConfigurer openEntityManagerInViewInterceptorConfigurer(OpenEntityManagerInViewInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addWebRequestInterceptor(interceptor).excludePathPatterns(RUTAS);
            }
        };
    }
}
