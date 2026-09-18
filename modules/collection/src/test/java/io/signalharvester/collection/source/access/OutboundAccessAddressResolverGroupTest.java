package io.signalharvester.collection.source.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.resolver.AddressResolver;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies DNS authorization is bound to the socket addresses returned to the Netty connection path.
 *
 * <p>Feature: {@code SECURITY.EXTERNAL_SOURCE_ACCESS}.</p>
 */
class OutboundAccessAddressResolverGroupTest {

    /** Return the exact authorized address from the resolver output consumed by Netty. */
    @Test
    void shouldReturnAuthorizedResolutionToConnectionPath() throws Exception {
        InetAddress expected = InetAddress.getByName("8.8.8.8");
        AtomicInteger resolutions = new AtomicInteger();
        HostAddressResolver hostResolver = host -> {
            resolutions.incrementAndGet();
            return List.of(expected);
        };
        OutboundAccessAddressResolverGroup group = new OutboundAccessAddressResolverGroup(
                hostResolver,
                policy(OutboundAccessConfiguration.Mode.SECURE, ""));
        DefaultEventExecutor executor = new DefaultEventExecutor();

        try {
            AddressResolver<InetSocketAddress> resolver = group.getResolver(executor);
            InetSocketAddress resolved = resolver
                    .resolve(InetSocketAddress.createUnresolved("source.test", 443))
                    .get(2, TimeUnit.SECONDS);

            assertEquals(1, resolutions.get());
            assertEquals(expected, resolved.getAddress());
            assertEquals(443, resolved.getPort());
        } finally {
            group.close();
            executor.shutdownGracefully().syncUninterruptibly();
        }
    }

    /** Reject mixed DNS answers before Netty receives any candidate connection address. */
    @Test
    void shouldRejectMixedResolutionBeforeConnectionSelection() throws Exception {
        HostAddressResolver hostResolver = host -> List.of(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("127.0.0.1"));
        OutboundAccessAddressResolverGroup group = new OutboundAccessAddressResolverGroup(
                hostResolver,
                policy(OutboundAccessConfiguration.Mode.SECURE, ""));
        DefaultEventExecutor executor = new DefaultEventExecutor();

        try {
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> group.getResolver(executor)
                            .resolve(InetSocketAddress.createUnresolved("mixed.test", 443))
                            .get(2, TimeUnit.SECONDS));

            assertInstanceOf(OutboundAccessDeniedException.class, failure.getCause());
        } finally {
            group.close();
            executor.shutdownGracefully().syncUninterruptibly();
        }
    }

    /** Reject a hostname whose deterministic DNS answer is private in secure mode. */
    @Test
    void shouldRejectBlockedHostnameResolution() throws Exception {
        HostAddressResolver hostResolver = host -> List.of(InetAddress.getByName("10.0.0.7"));
        OutboundAccessAddressResolverGroup group = new OutboundAccessAddressResolverGroup(
                hostResolver,
                policy(OutboundAccessConfiguration.Mode.SECURE, ""));
        DefaultEventExecutor executor = new DefaultEventExecutor();

        try {
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> group.getResolver(executor)
                            .resolve(InetSocketAddress.createUnresolved("internal.test", 443))
                            .get(2, TimeUnit.SECONDS));

            assertInstanceOf(OutboundAccessDeniedException.class, failure.getCause());
        } finally {
            group.close();
            executor.shutdownGracefully().syncUninterruptibly();
        }
    }

    /** Force already-resolved literal addresses through the same policy path. */
    @Test
    void shouldRevalidateResolvedLiteralAddress() throws Exception {
        HostAddressResolver unused = host -> {
            throw new AssertionError("literal address must not trigger hostname resolution");
        };
        OutboundAccessAddressResolverGroup group = new OutboundAccessAddressResolverGroup(
                unused,
                policy(OutboundAccessConfiguration.Mode.SECURE, ""));
        DefaultEventExecutor executor = new DefaultEventExecutor();

        try {
            InetSocketAddress literal = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 8080);
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> group.getResolver(executor).resolve(literal).get(2, TimeUnit.SECONDS));

            assertInstanceOf(OutboundAccessDeniedException.class, failure.getCause());
        } finally {
            group.close();
            executor.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static OutboundDestinationPolicy policy(OutboundAccessConfiguration.Mode mode, String allowedCidrs) {
        return new OutboundDestinationPolicy(new OutboundAccessConfiguration() {
            @Override
            public Mode getMode() {
                return mode;
            }

            @Override
            public String getAllowedCidrs() {
                return allowedCidrs;
            }
        });
    }
}
