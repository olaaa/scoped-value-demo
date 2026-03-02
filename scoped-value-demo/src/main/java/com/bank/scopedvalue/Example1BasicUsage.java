package com.bank.scopedvalue;

import com.bank.scopedvalue.BankingDomain.RequestContext;

/**
 * ПРИМЕР 1: Базовое использование ScopedValue.
 *
 * Сценарий: HTTP-запрос приходит в банковский сервис.
 * Контекст пользователя (userId, sessionId, ip) нужен на всех уровнях
 * цепочки вызовов — без передачи его параметром в каждый метод.
 *
 * Решение до Java 21: ThreadLocal
 *   - нужно вручную вызывать remove() после обработки запроса
 *   - при миллионе виртуальных потоков — миллион копий объекта в heap
 *
 * Решение Java 21+, финальное в Java 25: ScopedValue
 *   - иммутабелен (нельзя изменить внутри scope — только новый вложенный scope)
 *   - автоматически очищается при выходе из scope, никакого remove()
 *   - безопасен при любом количестве виртуальных потоков
 *
 * Java 25: ScopedValue — финальный API (JEP 487), --enable-preview не нужен.
 */
public class Example1BasicUsage {

    // Объявляем ScopedValue как static final — один раз на всё приложение.
    // Тип: ScopedValue<T>, где T — тип хранимого значения.
    // Аналог: private static final ThreadLocal<RequestContext> CONTEXT = new ThreadLocal<>();
    static final ScopedValue<RequestContext> REQUEST_CONTEXT = ScopedValue.newInstance();

    public static void main(String[] args) {
        System.out.println("=== Пример 1: Базовое использование ScopedValue ===\n");

        RequestContext ctx = new RequestContext("user-42", "sess-abc123", "192.168.1.10");

        // ScopedValue.where(KEY, value).run(lambda) — привязываем значение к scope.
        // Значение доступно ТОЛЬКО внутри лямбды run() и всех методов, вызванных из неё.
        // За пределами scope — isBound() == false, get() бросит NoSuchElementException.
        ScopedValue.where(REQUEST_CONTEXT, ctx).run(() -> {
            System.out.println("[main] Вошли в scope. isBound=" + REQUEST_CONTEXT.isBound());
            processPaymentRequest();
            System.out.println("[main] Вернулись. isBound=" + REQUEST_CONTEXT.isBound());
        });

        System.out.println("\n[main] За пределами scope. isBound=" + REQUEST_CONTEXT.isBound());
    }

    private static void processPaymentRequest() {
        // KEY.get() работает на любой глубине вызовов внутри scope — без передачи параметром.
        RequestContext ctx = REQUEST_CONTEXT.get();
        System.out.println("[processPaymentRequest] userId=" + ctx.userId()
                + ", sessionId=" + ctx.sessionId());

        validatePaymentLimits();
        auditLog();
    }

    private static void validatePaymentLimits() {
        RequestContext ctx = REQUEST_CONTEXT.get();
        System.out.println("[validatePaymentLimits] Проверяем лимиты для userId=" + ctx.userId()
                + ", ip=" + ctx.ipAddress());
        BankingDomain.simulateIo(20);
        System.out.println("[validatePaymentLimits] Лимит не превышен.");
    }

    private static void auditLog() {
        RequestContext ctx = REQUEST_CONTEXT.get();
        System.out.println("[auditLog] Операция выполнена: userId=" + ctx.userId()
                + ", sessionId=" + ctx.sessionId());
    }
}
