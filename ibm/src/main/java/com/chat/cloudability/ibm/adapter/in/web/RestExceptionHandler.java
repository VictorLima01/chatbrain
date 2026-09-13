package com.chat.cloudability.ibm.adapter.in.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduz as falhas do dominio em respostas HTTP legiveis.
 *
 * <p>Sem isto, um "modulo desconhecido" chega na interface como um 500 com pagina
 * de erro do servidor. O dominio sinaliza o problema com a excecao padrao do
 * Java; e o adaptador que decide qual codigo HTTP isso vira.
 */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    /** Entrada invalida: modulo inexistente, formato nao suportado, campo faltando. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", message(e)));
    }

    /** Ambiente incompleto: chave da Anthropic ausente, storage fora do ar. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        log.error("Falha de estado: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", message(e)));
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
