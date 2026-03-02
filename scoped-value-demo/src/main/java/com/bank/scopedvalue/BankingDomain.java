package com.bank.scopedvalue;

import java.util.List;

/**
 * Общие доменные объекты банковского контекста.
 * Используются во всех трёх примерах.
 *
 * Java 25: record — финальная фича, никаких preview-флагов не нужно.
 */
public final class BankingDomain {

    private BankingDomain() {}

    /**
     * Контекст входящего запроса: кто, откуда, в рамках какой сессии.
     * Передаётся через ScopedValue по всей цепочке вызовов вместо параметра метода.
     */
    public record RequestContext(String userId, String sessionId, String ipAddress) {}

    /**
     * Результат проверки клиента по одному санкционному списку.
     */
    public record SanctionCheckResult(String source, boolean isSanctioned, String details) {}

    /**
     * Итоговый вердикт по платёжному запросу после всех параллельных проверок.
     */
    public record PaymentVerificationResult(
            String correlationId,
            String userId,
            List<SanctionCheckResult> sanctionResults,
            boolean approved
    ) {}

    /**
     * Имитация IO-задержки (запрос к внешнему сервису, чтение из сокета и т.п.).
     * В реальном коде здесь будет socket.read() или HTTP-вызов —
     * и именно здесь произойдёт unmount виртуального потока с carrier-потока.
     */
    public static void simulateIo(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
