package com.chat.cloudability.ibm.adapter.in.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import com.chat.cloudability.ibm.domain.model.Entities.AccessLevel;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Porteiro da area administrativa.
 *
 * <p>Tudo sob {@code /api/admin} exige alcada de administrador. O resto da API —
 * conversar, listar modulos, ver a base — e aberto a qualquer alcada.
 *
 * <p>A checagem existe desde ja, mesmo sem autenticacao, para que a fronteira
 * esteja desenhada no codigo e nao so na interface: quando
 * {@link AccessResolver} passar a ler um usuario autenticado, este interceptor
 * comeca a barrar de verdade sem uma linha de alteracao.
 */
@Component
public class AdminAccessInterceptor implements HandlerInterceptor {

    private final AccessResolver access;

    public AdminAccessInterceptor(AccessResolver access) {
        this.access = access;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) throws Exception {
        // O preflight nao carrega os cabecalhos da requisicao real: barra-lo aqui
        // faria o navegador reportar erro de CORS em vez do 403 que vem depois.
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        if (access.resolve(request) == AccessLevel.ADMIN) {
            return true;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"message\":\"Esta operacao exige alcada de administrador\"}");
        return false;
    }
}
