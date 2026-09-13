package com.chat.cloudability.ibm.adapter.in.web;

import org.springframework.stereotype.Component;

import com.chat.cloudability.ibm.config.AppProperties;
import com.chat.cloudability.ibm.domain.model.Entities.AccessLevel;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Descobre com que alcada uma requisicao chega.
 *
 * <p><strong>Aqui ainda nao ha autenticacao.</strong> Enquanto
 * {@code app.access.auth-enabled} for falso, a alcada vem do cabecalho
 * {@code X-Access-Level} — util para exercitar as duas visoes durante o
 * desenvolvimento — e, na falta dele, de {@code app.access.default-level}, hoje
 * {@code admin}. Ou seja: qualquer um que alcance a API e administrador.
 *
 * <p>Isso e deliberado e provisorio. O ponto importante e que a decisao mora num
 * lugar so: quando o login entrar, e este metodo que passa a ler o usuario
 * autenticado, e nem o dominio nem os controllers precisam mudar.
 */
@Component
public class AccessResolver {

    static final String HEADER = "X-Access-Level";

    private final AppProperties props;

    public AccessResolver(AppProperties props) {
        this.props = props;
    }

    public AccessLevel resolve(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && !header.isBlank()) {
            return AccessLevel.of(header);
        }
        return AccessLevel.of(props.access().defaultLevel());
    }

    /** Se ja existe login de verdade. Falso hoje; a interface avisa a usuaria. */
    public boolean authEnabled() {
        return props.access().authEnabled();
    }
}
