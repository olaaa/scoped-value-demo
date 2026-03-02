package com.bank.scopedvalue.fetch_by_urls;

import com.bank.scopedvalue.BankingDomain;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ЗАДАЧА: Выполнить запросы конкурентно к нескольким URL
 * и сконкатенировать результаты в main-потоке.
 * <p>
 * Банковский сценарий: параллельная проверка клиента по санкционным спискам
 * из нескольких независимых источников.
 * <p>
 * Java 25 — только финальные API, без --enable-preview:
 * - Executors.newVirtualThreadPerTaskExecutor()  финальный с Java 21
 * - CompletableFuture                            финальный
 * - HttpClient                                   финальный с Java 11
 * - ScopedValue                                  финальный с Java 25
 * <p>
 * Почему не StructuredTaskScope?
 * StructuredTaskScope (JEP 505) — preview в Java 25.
 * Используем CompletableFuture + VirtualThread executor — production-ready альтернатива.
 * <p>
 * Два варианта в одном классе:
 * main()          — имитация через Thread.sleep (без реальной сети, запускается сразу)
 * realHttpDemo()  — реальные HTTP-запросы через HttpClient (требует сеть)
 */
@SuppressWarnings("ALL")
public class Example4ConcurrentUrls {

    // Correlation ID доступен во всех виртуальных потоках через ScopedValue
    static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();

    // Санкционные источники — несколько независимых URL
    private static final List<String> SANCTION_URLS = List.of(
            "https://sanctions-api-1.bank.internal/check",
            "https://sanctions-api-2.bank.internal/check",
            "https://ofac-mirror.bank.internal/check"
    );

    public static void main(String[] args) throws Exception {
        System.out.println("=== Параллельные запросы к нескольким URL (Java 25) ===\n");

        String corrId = "corr-" + System.currentTimeMillis();

        // Привязываем correlation ID через ScopedValue.
        // Все дочерние виртуальные потоки увидят его без передачи параметром.
        String aggregated = ScopedValue
                .where(CORRELATION_ID, corrId)
                .call(() -> fetchAllAndAggregate(SANCTION_URLS, "client-42"));
        String aggregated2 = ScopedValue
                .where(CORRELATION_ID, corrId)
                .call(Example4ConcurrentUrls::realHttpDemo);

        System.out.println("\n[main] Агрегированный результат:");
        System.out.println(aggregated);
        System.out.println("\n[main] Агрегированный результат realHttpDemo:");
        System.out.println(aggregated2);
    }

    // -------------------------------------------------------------------------
    // ВАРИАНТ 1 (запускается в main): имитация HTTP через sleep
    // -------------------------------------------------------------------------
    private static String fetchAllAndAggregate(List<String> urls, String clientId) throws Exception {
        String corrId = CORRELATION_ID.get();
        System.out.println("[fetchAllAndAggregate] Запускаем " + urls.size()
                           + " параллельных запросов. corrId=" + corrId);

        // newVirtualThreadPerTaskExecutor() — каждая задача получает свой виртуальный поток.
        // try-with-resources: executor.close() ждёт завершения всех задач перед выходом,
        // аналогично StructuredTaskScope.join() — lifetime задач ограничен блоком.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // Запускаем запрос к каждому URL в отдельном виртуальном потоке.
            // ScopedValue CORRELATION_ID виден внутри каждой лямбды автоматически.
            List<CompletableFuture<String>> futures = urls.stream()
                    .map(url -> CompletableFuture.supplyAsync(() -> {
                        return ScopedValue.where(CORRELATION_ID, corrId).call(() -> {
                            return simulateFetch(url, clientId);
                        });
                    }, executor))
                    .toList();

            // Ждём завершения ВСЕХ запросов.
            // allOf() не возвращает результаты — нужно вызвать join() на каждом future.
            CompletableFuture.allOf(futures.toArray(value -> new CompletableFuture[value])).join();

            // Собираем результаты в main-потоке.
            return futures.stream()
                    .map(CompletableFuture::join)
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + "; " + b);
        }
        // Здесь executor.close() уже вызван — все виртуальные потоки завершены.
        // Результат агрегирован и готов к использованию в вызывающем потоке.
    }

    private static String simulateFetch(String url, String clientId) {
        // Этот метод выполняется в дочернем виртуальном потоке.
        // CORRELATION_ID.get() работает — поток запущен внутри ScopedValue scope.
        String corrId = CORRELATION_ID.get();
        System.out.println("[" + Thread.currentThread().getName() + "]"
                           + " → " + url + " clientId=" + clientId + " corrId=" + corrId);

        // Имитация сетевого IO.
        // При реальном socket.read() здесь произошёл бы unmount виртуального потока:
        // carrier-поток освободился бы для другой задачи.
        BankingDomain.simulateIo(80);

        String source = url.replaceAll("https://([^/]+)/.*", "$1");
        System.out.println("[" + Thread.currentThread().getName() + "]"
                           + " ← " + source + " OK");
        return source + ": client " + clientId + " is CLEAR";
    }

    // -------------------------------------------------------------------------
    // ВАРИАНТ 2: реальные HTTP-запросы через java.net.http.HttpClient (Java 11+)
    // Вызывай вручную: Example4ConcurrentUrls.realHttpDemo()
    // -------------------------------------------------------------------------
    static String realHttpDemo() {
        List<String> publicUrls = List.of(
                "https://httpbin.org/delay/1",
                "https://httpbin.org/delay/1",
                "https://httpbin.org/delay/1"
        );

        String corrId = "corr-real-" + System.currentTimeMillis();

        String result = ScopedValue
                .where(CORRELATION_ID, corrId)
                .call(() -> fetchAllHttp(publicUrls));

        System.out.println("[realHttpDemo] Результат:\n" + result);
        return result;
    }

    private static String fetchAllHttp(List<String> urls) {
        HttpClient client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String corrId = CORRELATION_ID.get();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<String>> futures = urls.stream()
                    .map(url -> CompletableFuture.supplyAsync(() -> {
                        return ScopedValue.where(CORRELATION_ID, corrId).call(() -> {
                            try {
                                HttpRequest request = HttpRequest.newBuilder()
                                        .uri(URI.create(url))
                                        .header("X-Correlation-Id", CORRELATION_ID.get())
                                        .GET()
                                        .build();

                                HttpResponse<String> response = client.send(
                                        request, HttpResponse.BodyHandlers.ofString());

                                return url + " → HTTP " + response.statusCode();
                            } catch (Exception e) {
                                return url + " → ERROR: " + e.getMessage();
                            }
                        });
                    }, executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            return futures.stream()
                    .map(CompletableFuture::join)
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + "; " + b);
        }
    }
}