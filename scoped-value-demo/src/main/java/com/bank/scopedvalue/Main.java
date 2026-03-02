package com.bank.scopedvalue;

/**
 * Точка входа: запускает все три примера последовательно.
 * Можно также запускать каждый Example*.main() по отдельности
 * через соответствующие Run-конфигурации в IDEA.
 *
 * Требования: Java 25 LTS.
 * ScopedValue (JEP 487) и StructuredTaskScope (JEP 505) — финальные API в Java 25.
 * --enable-preview не нужен.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Example1BasicUsage.main(args);

        System.out.println("\n" + "=".repeat(60) + "\n");

        Example2StructuredConcurrency.main(args);

        System.out.println("\n" + "=".repeat(60) + "\n");

        Example3NestedScope.main(args);
    }
}
