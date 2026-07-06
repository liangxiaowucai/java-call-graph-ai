package com.adrninistrator.javacg2.platform.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * SPA 路由兜底：拦截 Spring Boot 的 /error 端点，
 * 对于 404（页面未找到）转发到 index.html，让 React Router 处理路由。
 * API 请求（/api/**）不会触发此控制器，因为 API 端点已有明确映射。
 */
@Controller
public class SpaController implements ErrorController {

    @RequestMapping("/error")
    public Object handleError(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (status != null && Integer.parseInt(status.toString()) == HttpStatus.NOT_FOUND.value()) {
            return "forward:/index.html";
        }
        // 其他错误（500 等）走默认 Spring Boot 错误处理
        return "forward:/error/default";
    }
}
