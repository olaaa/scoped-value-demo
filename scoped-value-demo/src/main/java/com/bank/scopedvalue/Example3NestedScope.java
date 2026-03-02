package com.bank.scopedvalue;

import com.bank.scopedvalue.BankingDomain.RequestContext;

/**
 * ПРИМЕР 3: Вложенный scope — переопределение значения ScopedValue.
 *
 * Сценарий: в рамках пользовательского запроса нужно выполнить служебную операцию
 * (системный аудит-лог) от имени системной учётной записи, а не пользователя.
 * После служебной операции контекст пользователя восстанавливается автоматически.
 *
 * Ключевая особенность:
 *   ScopedValue иммутабелен — нельзя сделать KEY.set(newValue).
 *   Единственный способ изменить значение — создать вложенный scope:
 *     ScopedValue.where(KEY, newValue).run(...)
 *   При выходе из вложенного scope внешнее значение восстанавливается автоматически.
 *
 *   С ThreadLocal это выглядело бы так (хрупко!):
 *     RequestContext old = CONTEXT.get();
 *     try { CONTEXT.set(systemCtx); doWork(); }
 *     finally { CONTEXT.set(old); }  // легко забыть в реальном коде
 *
 * Java 25: ScopedValue — финальный API (JEP 487), --enable-preview не нужен.
 */
public class Example3NestedScope {

    static final ScopedValue<RequestContext> REQUEST_CONTEXT = ScopedValue.newInstance();

    public static void main(String[] args) {
        System.out.println("=== Пример 3: Вложенный scope (переопределение значения) ===\n");

        RequestContext userCtx = new RequestContext("user-42", "sess-abc", "192.168.1.1");

        // Внешний scope: контекст реального пользователя
        ScopedValue.where(REQUEST_CONTEXT, userCtx).run(() -> {
            System.out.println("[внешний scope] userId=" + REQUEST_CONTEXT.get().userId()
                    + "  (контекст пользователя)");

            processUserPayment();

            // После выхода из вложенного scope значение восстановлено автоматически.
            // Никакого try/finally, никакого риска забыть restore.
            System.out.println("\n[внешний scope] После служебной операции: userId="
                    + REQUEST_CONTEXT.get().userId()
                    + "  ✅ оригинальный контекст восстановлен");

            recordFinalAudit();
        });

        System.out.println("\n[main] За пределами scope. isBound=" + REQUEST_CONTEXT.isBound());
    }

    private static void processUserPayment() {
        RequestContext ctx = REQUEST_CONTEXT.get();
        System.out.println("[processUserPayment] Обрабатываем платёж. userId=" + ctx.userId()
                + ", ip=" + ctx.ipAddress());
        BankingDomain.simulateIo(20);
        performSystemAudit();
    }

    private static void performSystemAudit() {
        System.out.println("[performSystemAudit] Текущий userId перед вложенным scope: "
                + REQUEST_CONTEXT.get().userId());

        RequestContext systemCtx = new RequestContext("SYSTEM", "internal-audit", "127.0.0.1");

        // Вложенный scope: REQUEST_CONTEXT переопределён ТОЛЬКО внутри этой лямбды.
        // Внешний scope не затрагивается.
        ScopedValue.where(REQUEST_CONTEXT, systemCtx).run(() -> {
            System.out.println("\n  [вложенный scope] userId=" + REQUEST_CONTEXT.get().userId()
                    + "  (системный контекст)");
            writeSystemAuditLog();
            notifyComplianceService();
            System.out.println("  [вложенный scope] Системная операция завершена.\n");
        });

        // Здесь снова виден пользовательский контекст — JVM восстановил автоматически
        System.out.println("[performSystemAudit] После вложенного scope userId="
                + REQUEST_CONTEXT.get().userId() + "  (восстановлен)");
    }

    private static void writeSystemAuditLog() {
        RequestContext ctx = REQUEST_CONTEXT.get();
        System.out.println("  [writeSystemAuditLog] Audit entry created by: "
                + ctx.userId() + " from ip=" + ctx.ipAddress());
        BankingDomain.simulateIo(15);
    }

    private static void notifyComplianceService() {
        RequestContext ctx = REQUEST_CONTEXT.get();
        System.out.println("  [notifyComplianceService] Compliance notified. actor="
                + ctx.userId() + ", session=" + ctx.sessionId());
        BankingDomain.simulateIo(10);
    }

    private static void recordFinalAudit() {
        RequestContext ctx = REQUEST_CONTEXT.get();
        System.out.println("[recordFinalAudit] Финальная запись аудита. userId="
                + ctx.userId() + ", sessionId=" + ctx.sessionId());
    }
}
