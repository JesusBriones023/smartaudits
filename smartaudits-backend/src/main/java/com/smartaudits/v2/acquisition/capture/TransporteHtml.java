package com.smartaudits.v2.acquisition.capture;

import com.smartaudits.v2.acquisition.destination.DestinationUrl;

/**
 * Obtains the HTML of one destination. A real implementation must meet ADR-0002 section 17 before
 * it may exist: evaluate {@code DestinationPolicy} for the URL and for every redirect, connect only
 * to an approved address of that evaluation (pinned IP, Host/SNI/certificate of the hostname, TLS
 * fully validated), use no inherited proxy and no hidden resolution, and bound time, size and
 * redirects. It returns a {@link CapturaHtml} or throws {@link CapturaException}; it never returns
 * {@code null}.
 */
public interface TransporteHtml {

    CapturaHtml capturar(DestinationUrl url);
}
