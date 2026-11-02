package com.smartaudits.v2.acquisition.capture.bootstrap;

import com.smartaudits.service.AuditoriaService;
import com.smartaudits.service.motor.AnalizadorLegal;
import com.smartaudits.service.url.IngestaUrlService;
import com.smartaudits.v2.acquisition.capture.CapturaException;
import com.smartaudits.v2.acquisition.capture.CapturaUrlConfig;
import com.smartaudits.v2.acquisition.capture.TransporteHtml;
import com.smartaudits.v2.acquisition.capture.TransporteHttpNoDisponible;
import com.smartaudits.v2.acquisition.capture.TransporteHttpReal;
import com.smartaudits.v2.acquisition.capture.dns.ResolvedorDnsDirecto;
import com.smartaudits.v2.acquisition.capture.http.ClienteHttpFijado;
import com.smartaudits.v2.acquisition.capture.http.ConectorDirecto;
import com.smartaudits.v2.acquisition.capture.http.LimitesHttp;
import com.smartaudits.v2.acquisition.capture.http.MonitorDeConexiones;
import com.smartaudits.v2.acquisition.destination.DestinationPolicy;
import com.smartaudits.v2.acquisition.destination.HostResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.SSLContext;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class TransporteBootstrapTest {
    private static final String DNS = "app.acquisition.url.dns.";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CapturaUrlConfig.class)
            .withInitializer(context -> new ClassPathBeanDefinitionScanner((BeanDefinitionRegistry) context.getBeanFactory())
                    .scan(ConfiguracionDnsTransporte.class.getPackageName()));

    @Test
    void missingDnsHasNoImplicitServerAndCannotBePrepared() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TransporteHtml.class);
            var dns = context.getBean(ConfiguracionDnsTransporte.class);
            assertThat(dns.serverIp()).isNull();
            assertThat(dns.port()).isEqualTo(53);
            assertThat(dns.timeout()).isEqualTo(Duration.ofSeconds(2));
            assertThatThrownBy(() -> PreparacionTransporte.preparar(dns))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("no hay DNS por defecto");
        });
    }

    @ParameterizedTest
    @CsvSource({"192.0.2.53,c0000235", "10.0.0.53,0a000035", "127.0.0.1,7f000001",
            "2001:db8::53,20010db8000000000000000000000053",
            "2001:0DB8:0000:0000:0000:0000:0000:0053,20010db8000000000000000000000053",
            "::1,00000000000000000000000000000001"})
    void literalBindingAndPreparationMakeNoLookupOrConnection(String ip, String hex) throws Exception {
        var observado = MonitorDeConexiones.durante(() -> {
            runner.withPropertyValues(DNS + "server-ip=" + ip).run(context -> {
                assertThat(context).hasNotFailed();
                var dns = context.getBean(ConfiguracionDnsTransporte.class);
                assertThat(dns.direccion()).containsExactly(HexFormat.of().parseHex(hex));
                assertThat(PreparacionTransporte.preparar(dns)).isExactlyInstanceOf(TransporteHttpReal.class);
            });
            return true;
        });
        assertThat(observado.error()).isNull();
        assertThat(observado.eventos()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "localhost", "dns.example.invalid", "https://192.0.2.53", "192.0.2.53:53",
            "192.0.2.53/32", "127.1", "2130706433", "0x7f000001", "192.000.2.53", "256.0.0.1",
            "[2001:db8::53]", "fe80::1%eth0", "::ffff:192.0.2.53", "::ffff:c000:235", "2001:db8:::53",
            "2001:db8", "2001:db8::xyz", "0.0.0.0", "::", "224.0.0.1", "ff02::1", "255.255.255.255"})
    void invalidDnsFailsStartupEvenWhileTransportIsDisabled(String ip) {
        runner.withPropertyValues(DNS + "server-ip=" + ip).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(DNS + "server-ip");
        });
    }

    @Test
    void whitespaceIsNotNormalizedIntoAnAddress() {
        for (String ip : new String[]{" 192.0.2.53", "192.0.2.53 ", "2001:db8::53\n"}) {
            assertThatThrownBy(() -> new ConfiguracionDnsTransporte(ip, 53, Duration.ofSeconds(2)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"port=0", "port=65536", "port=abc", "timeout=0ms", "timeout=-1s",
            "timeout=999ns", "timeout=10001ms", "timeout=invalid", "server-ipp=192.0.2.53"})
    void invalidOrUnknownDnsSettingsFailStartup(String propiedad) {
        runner.withPropertyValues(DNS + "server-ip=192.0.2.53", DNS + propiedad)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @CsvSource({"1,1ms", "65535,10s"})
    void validPortAndTimeoutBoundariesBind(int port, String timeout) {
        runner.withPropertyValues(DNS + "server-ip=192.0.2.53", DNS + "port=" + port, DNS + "timeout=" + timeout)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ConfiguracionDnsTransporte.class).port()).isEqualTo(port);
                });
    }

    @Test
    void compositionUsesOnlyTheExistingPolicyResolverAndClient() {
        var transporte = PreparacionTransporte.preparar(new ConfiguracionDnsTransporte("192.0.2.53", 5353, Duration.ofMillis(750)));
        Object politica = ReflectionTestUtils.getField(transporte, "politica");
        assertThat(politica).isExactlyInstanceOf(DestinationPolicy.class);
        Object resolver = ReflectionTestUtils.getField(politica, "resolver");
        assertThat(resolver).isExactlyInstanceOf(ResolvedorDnsDirecto.class);
        var servidor = (InetSocketAddress) ReflectionTestUtils.getField(resolver, "servidor");
        assertThat(servidor.getAddress().getAddress()).containsExactly((byte) 192, (byte) 0, (byte) 2, (byte) 53);
        assertThat(servidor.getPort()).isEqualTo(5353);
        assertThat(ReflectionTestUtils.getField(resolver, "timeout")).isEqualTo(Duration.ofMillis(750));
        Object cliente = ReflectionTestUtils.getField(transporte, "cliente");
        assertThat(cliente).isExactlyInstanceOf(ClienteHttpFijado.class);
        assertThat(ReflectionTestUtils.getField(cliente, "conector")).isExactlyInstanceOf(ConectorDirecto.class);
        assertThat(((ClienteHttpFijado) cliente).limites()).isEqualTo(LimitesHttp.porDefecto());
        assertThat(((Clock) ReflectionTestUtils.getField(transporte, "reloj")).getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void standardTlsContextFailureHasNoFallback() {
        try (var tls = mockStatic(SSLContext.class)) {
            tls.when(SSLContext::getDefault).thenThrow(new NoSuchAlgorithmException("test"));
            assertThatThrownBy(() -> PreparacionTransporte.preparar(
                    new ConfiguracionDnsTransporte("192.0.2.53", 53, Duration.ofSeconds(2))))
                    .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(NoSuchAlgorithmException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dnsConfigurationCannotEnableIngestionAndExplicitActivationStillFails(boolean activar) throws Exception {
        var observado = MonitorDeConexiones.durante(() -> {
            runner.withPropertyValues(DNS + "server-ip=192.0.2.53", CapturaUrlConfig.PROPIEDAD + "=" + activar)
                    .withBean(AuditoriaService.class, () -> mock(AuditoriaService.class))
                    .withBean(AnalizadorLegal.class, () -> mock(AnalizadorLegal.class))
                    .withBean(com.smartaudits.service.AuditQuotaService.class,
                            () -> mock(com.smartaudits.service.AuditQuotaService.class))
                    .withBean(IngestaUrlService.class)
                    .run(context -> {
                        if (activar) {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("R-EGR bloqueante");
                            return;
                        }
                        assertThat(context).hasNotFailed().hasSingleBean(TransporteHtml.class);
                        assertThat(context.getBean(TransporteHtml.class)).isExactlyInstanceOf(TransporteHttpNoDisponible.class);
                        for (Class<?> tipo : new Class<?>[]{TransporteHttpReal.class, HostResolver.class, DestinationPolicy.class,
                                ClienteHttpFijado.class, ConectorDirecto.class, PreparacionTransporte.class}) {
                            assertThat(context.getBeansOfType(tipo)).isEmpty();
                        }
                        assertThatThrownBy(() -> context.getBean(IngestaUrlService.class).capturar("https://www.ejemplo.es/"))
                                .isInstanceOfSatisfying(CapturaException.class,
                                        e -> assertThat(e.motivo()).isEqualTo(CapturaException.Motivo.NO_AUTORIZADA));
                    });
            return true;
        });
        assertThat(observado.error()).isNull();
        assertThat(observado.eventos()).isEmpty();
        assertThat(Modifier.isPublic(PreparacionTransporte.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(PreparacionTransporte.class
                .getDeclaredMethod("preparar", ConfiguracionDnsTransporte.class).getModifiers())).isFalse();
    }
}
