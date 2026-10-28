package com.loggingx.biz.ai;

import com.loggingx.biz.ai.StackTraces.ParsedError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackTracesTest {

    private static final String JAVA = """
            2026-10-28 10:00:01.123 ERROR [http-nio-8080-exec-3] c.s.o.OrderController - submit failed
            java.lang.IllegalStateException: stock is negative for sku 1029
            \tat com.shop.order.OrderService.reserve(OrderService.java:%d)
            \tat com.shop.order.OrderService.submit(OrderService.java:40)
            \tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885)
            \tat java.base/java.lang.Thread.run(Thread.java:1583)
            """;

    @Test
    void readsJavaTracesAndGroupsThemAcrossEdits() {
        ParsedError error = StackTraces.parse(JAVA.formatted(88));
        assertEquals("java.lang.IllegalStateException", error.errorType());
        assertEquals(4, error.frames().size());
        assertEquals("com.shop.order.OrderService", error.location().className());
        assertEquals("reserve", error.location().function());
        assertEquals("com/shop/order/OrderService.java", error.location().probablePath());
        assertTrue(error.frames().get(2).library(), "Spring is a library");
        assertTrue(error.frames().get(3).library(), "module-prefixed JDK frames are libraries");
        assertEquals("java.lang.Thread", error.frames().get(3).className());

        // A shifted line or another sku is the same error; another method is not.
        assertEquals(error.fingerprint(), StackTraces.parse(JAVA.formatted(91).replace("1029", "77")).fingerprint());
        assertNotEquals(error.fingerprint(), StackTraces.parse(JAVA.formatted(88)
                .replace("OrderService.reserve", "OrderService.release")).fingerprint());
    }

    @Test
    void readsPythonTracesInnermostFirst() {
        ParsedError error = StackTraces.parse("""
                Traceback (most recent call last):
                  File "/usr/local/lib/python3.12/site-packages/flask/app.py", line 1473, in wsgi_app
                    response = self.full_dispatch_request()
                  File "/app/shop/views.py", line 31, in checkout
                    total = price_of(cart)
                  File "/app/shop/pricing.py", line 12, in price_of
                    return sum(item.price for item in cart.items)
                AttributeError: 'NoneType' object has no attribute 'items'
                """);
        assertEquals("AttributeError", error.errorType());
        assertEquals("/app/shop/pricing.py", error.location().file());
        assertEquals(12, error.location().line());
        assertTrue(error.frames().get(2).library());
    }

    @Test
    void readsNodeTracesAndGroupsPlainErrorsByMessage() {
        ParsedError node = StackTraces.parse("""
                TypeError: Cannot read properties of undefined (reading 'id')
                    at getUser (/srv/app/src/users.ts:42:17)
                    at /srv/app/node_modules/express/lib/router/layer.js:95:5
                """);
        assertEquals("TypeError", node.errorType());
        assertEquals("/srv/app/src/users.ts", node.location().file());
        assertFalse(node.location().library());
        assertTrue(node.frames().get(1).library());

        ParsedError plain = StackTraces.parse("2026-10-28 10:00:01 ERROR order 'OD123' timed out after 3000 ms");
        assertNull(plain.location());
        assertEquals(plain.fingerprint(),
                StackTraces.parse("2026-10-28 11:12:13 ERROR order 'OD999' timed out after 5000 ms").fingerprint());
    }

    @Test
    void findsTraceFilesInTheRepository() {
        List<String> files = List.of("README.md", "order/src/main/java/com/shop/order/OrderService.java",
                "test/src/test/java/com/shop/order/OrderServiceTest.java", "shop/pricing.py");
        assertEquals("order/src/main/java/com/shop/order/OrderService.java",
                CodeRepositories.locate(files, "com/shop/order/OrderService.java"));
        assertEquals("shop/pricing.py", CodeRepositories.locate(files, "/app/shop/pricing.py"));
        assertNull(CodeRepositories.locate(files, "/app/shop/missing.py"));
    }
}
