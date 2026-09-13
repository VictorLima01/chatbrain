package com.chat.cloudability.ibm.adapter.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chat.cloudability.ibm.adapter.in.web.dto.WebDtos;
import com.chat.cloudability.ibm.domain.port.in.ManageModulesPortIn;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Adaptador de entrada: leitura dos modulos e da alcada de quem chama.
 *
 * <p>Qualquer alcada lista os modulos — e o seletor da interface. O endpoint de
 * alcada existe para a interface saber se mostra ou nao a area administrativa;
 * ele descreve o estado atual, incluindo o fato de a autenticacao ainda nao
 * existir.
 */
@RestController
@RequestMapping("/api")
public class ModuleController {

    private final ManageModulesPortIn modules;
    private final AccessResolver access;

    public ModuleController(ManageModulesPortIn modules, AccessResolver access) {
        this.modules = modules;
        this.access = access;
    }

    @GetMapping("/modules")
    public List<WebDtos.ModuleResponse> list() {
        return modules.list().stream().map(WebDtos.ModuleResponse::from).toList();
    }

    @GetMapping("/access")
    public Map<String, Object> access(HttpServletRequest request) {
        return Map.of(
                "level", access.resolve(request).wireValue(),
                "authEnabled", access.authEnabled());
    }
}
