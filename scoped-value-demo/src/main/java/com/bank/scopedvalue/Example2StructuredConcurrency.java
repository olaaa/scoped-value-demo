package com.bank.scopedvalue;

import com.bank.scopedvalue.BankingDomain.PaymentVerificationResult;
import com.bank.scopedvalue.BankingDomain.RequestContext;
import com.bank.scopedvalue.BankingDomain.SanctionCheckResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * ПРИМЕР 2: Передача ScopedValue в дочерние виртуальные потоки.
 *
 * Сценарий: клиент инициирует платёж. Перед одобрением нужно параллельно:
 *   - проверить клиента по трём санкционным спискам (OFAC, EU, UN)
 *   - проверить кредитный лимит
 * Все проверки выполняются конкурентно в виртуальных потоках.
 * Результат агрегируется в основном потоке.
 *
 * ВАЖНО: CompletableFuture НЕ наследует привязки ScopedValue автоматически.
 * Поэтому перед отправкой задачи в executor мы явно снимаем значения из
 * текущего scope и оборачиваем лямбду в новый ScopedValue.where(...).
 */
public class Example2StructuredConcurrency {

    static final ScopedValue<RequestContext> REQUEST_CONTEXT = ScopedValue.newInstance();
    static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();

    public static void main(String[] args) throws Exception {
        System.out.println("=== Пример 2: ScopedValue + Virtual Threads + CompletableFuture ===\n");

        RequestContext ctx = new RequestContext("user-99", "sess-xyz789", "10.0.0.5");
        String corrId = "corr-" + System.currentTimeMillis();

        PaymentVerificationResult result = ScopedValue
                .where(REQUEST_CONTEXT, ctx)
                .where(CORRELATION_ID, corrId)
                .call(Example2StructuredConcurrency::runParallelChecks);

        System.out.println("\n[main] === Итоговый результат ===");
        System.out.println("[main] correlationId : " + result.correlationId());
        System.out.println("[main] userId        : " + result.userId());
        System.out.println("[main] approved      : " + result.approved());
        System.out.println("[main] Санкционные проверки:");
        result.sanctionResults().forEach(r ->
                System.out.printf("         %-15s sanctioned=%-5b  %s%n",
                        r.source(), r.isSanctioned(), r.details()));
    }

    /**
     * Оборачивает Supplier так, чтобы в новом потоке были доступны
     * текущие привязки REQUEST_CONTEXT и CORRELATION_ID.
     * Значения захватываются в момент вызова этого метода (в родительском потоке),
     * а ScopedValue.where() выполняется уже в дочернем потоке.
     */
    private static <T> Supplier<T> withCurrentScope(Supplier<T> supplier) {
        RequestContext ctx = REQUEST_CONTEXT.get();
        String corrId = CORRELATION_ID.get();
        return () -> ScopedValue
                .where(REQUEST_CONTEXT, ctx)
                .where(CORRELATION_ID, corrId)
                .call(supplier::get);
    }

    private static PaymentVerificationResult runParallelChecks() throws Exception {
        RequestContext ctx = REQUEST_CONTEXT.get();
        String corrId = CORRELATION_ID.get();

        System.out.println("[runParallelChecks] userId=" + ctx.userId()
                           + ", correlationId=" + corrId);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            CompletableFuture<SanctionCheckResult> ofacFuture = CompletableFuture
                    .supplyAsync(withCurrentScope(() -> checkSanctionList("OFAC",         ctx.userId())), executor);
            CompletableFuture<SanctionCheckResult> euFuture = CompletableFuture
                    .supplyAsync(withCurrentScope(() -> checkSanctionList("EU-SANCTIONS", ctx.userId())), executor);
            CompletableFuture<SanctionCheckResult> unFuture = CompletableFuture
                    .supplyAsync(withCurrentScope(() -> checkSanctionList("UN-LIST",      ctx.userId())), executor);
            CompletableFuture<Boolean> limitFuture = CompletableFuture
                    .supplyAsync(withCurrentScope(() -> checkCreditLimit(ctx.userId())), executor);

            CompletableFuture.allOf(ofacFuture, euFuture, unFuture, limitFuture).join();

            List<SanctionCheckResult> sanctionResults = List.of(
                    ofacFuture.join(),
                    euFuture.join(),
                    unFuture.join()
            );

            boolean sanctionClear = sanctionResults.stream()
                    .noneMatch(SanctionCheckResult::isSanctioned);
            boolean limitOk = limitFuture.join();
            boolean approved = sanctionClear && limitOk;

            return new PaymentVerificationResult(corrId, ctx.userId(), sanctionResults, approved);
        }
    }

    private static SanctionCheckResult checkSanctionList(String listName, String userId) {
        String corrId = CORRELATION_ID.get();
        System.out.println("[" + Thread.currentThread().getName() + "]"
                           + " Проверка " + listName + " userId=" + userId + " corrId=" + corrId);

        BankingDomain.simulateIo(60);

        return new SanctionCheckResult(listName, false,
                "Клиент " + userId + " чист по списку " + listName);
    }

    private static boolean checkCreditLimit(String userId) {
        String corrId = CORRELATION_ID.get();
        System.out.println("[" + Thread.currentThread().getName() + "]"
                           + " Проверка лимита userId=" + userId + " corrId=" + corrId);
        BankingDomain.simulateIo(40);
        System.out.println("[" + Thread.currentThread().getName() + "]"
                           + " Лимит userId=" + userId + " в норме.");
        return true;
    }
}