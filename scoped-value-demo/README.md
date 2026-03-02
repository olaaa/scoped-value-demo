# ScopedValue Demo — Java 25

Практические примеры использования `ScopedValue` в банковском контексте.  
Все примеры написаны на **Java 25**, используют только финальные API — без `--enable-preview`.

---

## Зачем ScopedValue?

В многопоточных приложениях часто нужно передавать контекст запроса (userId, correlationId, sessionId) через всю цепочку вызовов без явной передачи параметром в каждый метод.

До Java 21 для этого использовался `ThreadLocal`. С виртуальными потоками он работает, но создаёт проблемы:

| | `ThreadLocal` | `ScopedValue` |
|---|---|---|
| Изменяемость | мутабелен, можно перезаписать | иммутабелен внутри scope |
| Очистка | нужен явный `remove()` | автоматически при выходе из scope |
| Миллион ВП | миллион копий объекта в heap | одно значение, читается из контекста |
| Дочерние потоки | `InheritableThreadLocal` — хрупко | наследуется автоматически |

`ScopedValue` (JEP 487) стал финальным API в **Java 25**.

---

## Требования

- **Java 25** (финальный API, `--enable-preview` не нужен)
- **IntelliJ IDEA** 2024.1+

---

## Структура проекта

```
scoped-value-demo/
├── scoped-value-demo.iml
├── .idea/
│   ├── misc.xml                        # SDK Java 25
│   ├── compiler.xml                    # target 25, без --enable-preview
│   └── runConfigurations/              # готовые Run-конфигурации
└── src/main/java/com/bank/scopedvalue/
    ├── BankingDomain.java              # общие record-классы домена
    ├── Example1BasicUsage.java         # базовый ScopedValue по цепочке вызовов
    ├── Example2StructuredConcurrency.java  # ScopedValue в дочерних виртуальных потоках
    ├── Example3NestedScope.java        # вложенный scope / переопределение значения
    └── Main.java                       # запускает все три примера
```

---

## Примеры

### Example 1 — Базовое использование

Контекст входящего запроса (userId, sessionId, ip) доступен на всех уровнях цепочки вызовов без передачи параметром.

```java
static final ScopedValue<RequestContext> REQUEST_CONTEXT = ScopedValue.newInstance();

ScopedValue.where(REQUEST_CONTEXT, ctx).run(() -> {
    processPaymentRequest();   // ctx доступен здесь
    validatePaymentLimits();   // и здесь — без параметра
    auditLog();                // и здесь
});
// за пределами scope — REQUEST_CONTEXT.isBound() == false
```

**Банковский сценарий:** обработка входящего платёжного запроса — контекст пользователя проходит через валидацию лимитов и запись в аудит-лог без явной передачи.

---

### Example 2 — ScopedValue в дочерних виртуальных потоках

Параллельная проверка клиента по санкционным спискам и кредитному лимиту. `ScopedValue` автоматически виден во всех дочерних виртуальных потоках.

```java
// Запускаем 4 проверки параллельно в виртуальных потоках
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var ofacFuture  = CompletableFuture.supplyAsync(() -> checkSanctionList("OFAC", userId), executor);
    var euFuture    = CompletableFuture.supplyAsync(() -> checkSanctionList("EU",   userId), executor);
    var unFuture    = CompletableFuture.supplyAsync(() -> checkSanctionList("UN",   userId), executor);
    var limitFuture = CompletableFuture.supplyAsync(() -> checkCreditLimit(userId),           executor);

    CompletableFuture.allOf(ofacFuture, euFuture, unFuture, limitFuture).join();
    // CORRELATION_ID.get() работает внутри каждого из виртуальных потоков
}
```

**Банковский сценарий:** верификация платежа — одновременная проверка по спискам OFAC, EU Sanctions, UN и проверка кредитного лимита.

---

### Example 3 — Вложенный scope

Переопределение значения `ScopedValue` внутри вложенного scope. Внешний scope не затрагивается, значение восстанавливается автоматически.

```java
// Внешний scope: контекст пользователя
ScopedValue.where(REQUEST_CONTEXT, userCtx).run(() -> {
    System.out.println(REQUEST_CONTEXT.get().userId()); // user-42

    // Вложенный scope: системный контекст — только для служебной операции
    ScopedValue.where(REQUEST_CONTEXT, systemCtx).run(() -> {
        System.out.println(REQUEST_CONTEXT.get().userId()); // SYSTEM
        writeSystemAuditLog();
    });

    System.out.println(REQUEST_CONTEXT.get().userId()); // user-42 — восстановлен автоматически
});
```

**Банковский сценарий:** запись системного аудит-лога от имени `SYSTEM` внутри пользовательского запроса — без риска «забыть» восстановить контекст (в отличие от `ThreadLocal` с try/finally).

---

## Запуск

Открой папку `scoped-value-demo` как проект в IntelliJ IDEA. В выпадающем списке Run-конфигураций доступны:

| Конфигурация | Что запускает |
|---|---|
| `Main (все примеры)` | все три примера последовательно |
| `Example1 - Basic ScopedValue` | только Example 1 |
| `Example2 - StructuredConcurrency` | только Example 2 |
| `Example3 - Nested Scope` | только Example 3 |

Или из терминала:

```bash
javac -d out --source-path src/main/java \
  src/main/java/com/bank/scopedvalue/*.java

java -cp out com.bank.scopedvalue.Main
```

---

## Ключевые API

| API | JEP | Статус в Java 25 |
|---|---|---|
| `ScopedValue` | JEP 487 | ✅ Финальный |
| `Thread.ofVirtual()` | JEP 444 | ✅ Финальный |
| `Executors.newVirtualThreadPerTaskExecutor()` | JEP 444 | ✅ Финальный |
| `StructuredTaskScope` | JEP 505 | ⚠️ Preview (не используется в этом проекте) |

---

## Связанные темы

- [JEP 487 — Scoped Values](https://openjdk.org/jeps/487)
- [JEP 444 — Virtual Threads](https://openjdk.org/jeps/444)
- [JEP 505 — Structured Concurrency (Preview)](https://openjdk.org/jeps/505)
