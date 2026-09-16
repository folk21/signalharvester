package io.signalharvester.collection.source.access;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;

/**
 * Netty resolver that binds outbound authorization to the addresses returned to the HTTP connection path.
 *
 * <p>DNS lookup runs on a Java 21 Virtual Thread rather than a Netty event-loop thread. The complete
 * answer set is authorized before one resolved socket address is returned, so mixed safe/unsafe DNS
 * answers are rejected and the connection uses an address from the validated set.</p>
 */
@Singleton
@Named(OutboundAccessAddressResolverGroup.RESOLVER_GROUP_NAME)
public final class OutboundAccessAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    /** Bean name referenced by the Micronaut HTTP client configuration. */
    public static final String RESOLVER_GROUP_NAME = "signalharvester-external-source-access";

    private final HostAddressResolver hostAddressResolver;
    private final OutboundDestinationPolicy destinationPolicy;

    public OutboundAccessAddressResolverGroup(
            HostAddressResolver hostAddressResolver,
            OutboundDestinationPolicy destinationPolicy) {
        this.hostAddressResolver = Objects.requireNonNull(hostAddressResolver, "hostAddressResolver");
        this.destinationPolicy = Objects.requireNonNull(destinationPolicy, "destinationPolicy");
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        return new PolicyAddressResolver(executor, hostAddressResolver, destinationPolicy);
    }

    private static final class PolicyAddressResolver extends AbstractAddressResolver<InetSocketAddress> {

        private final HostAddressResolver hostAddressResolver;
        private final OutboundDestinationPolicy destinationPolicy;

        private PolicyAddressResolver(
                EventExecutor executor,
                HostAddressResolver hostAddressResolver,
                OutboundDestinationPolicy destinationPolicy) {
            super(executor, InetSocketAddress.class);
            this.hostAddressResolver = hostAddressResolver;
            this.destinationPolicy = destinationPolicy;
        }

        @Override
        protected boolean doIsResolved(InetSocketAddress address) {
            // Force literal and hostname destinations through the same authorization path.
            return false;
        }

        @Override
        protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
            Thread.startVirtualThread(() -> {
                try {
                    promise.trySuccess(resolveAuthorized(address).getFirst());
                } catch (Throwable failure) {
                    promise.tryFailure(failure);
                }
            });
        }

        @Override
        protected void doResolveAll(InetSocketAddress address, Promise<List<InetSocketAddress>> promise) {
            Thread.startVirtualThread(() -> {
                try {
                    promise.trySuccess(resolveAuthorized(address));
                } catch (Throwable failure) {
                    promise.tryFailure(failure);
                }
            });
        }

        private List<InetSocketAddress> resolveAuthorized(InetSocketAddress address) throws UnknownHostException {
            String host = IpCidr.stripIpv6Brackets(address.getHostString());
            List<InetAddress> resolved;
            if (!address.isUnresolved() && address.getAddress() != null) {
                resolved = List.of(address.getAddress());
            } else {
                resolved = hostAddressResolver.resolveAll(host);
            }
            destinationPolicy.authorize(host, resolved);
            return resolved.stream()
                    .map(candidate -> new InetSocketAddress(candidate, address.getPort()))
                    .toList();
        }
    }
}
