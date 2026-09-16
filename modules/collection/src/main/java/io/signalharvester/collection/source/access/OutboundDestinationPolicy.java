package io.signalharvester.collection.source.access;

import io.micronaut.context.annotation.Context;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Authorizes the complete address set that the HTTP transport may use for one source destination.
 */
@Context
public final class OutboundDestinationPolicy {

    private final OutboundAccessConfiguration.Mode mode;
    private final List<IpCidr> allowedCidrs;

    public OutboundDestinationPolicy(OutboundAccessConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        this.mode = Objects.requireNonNull(configuration.getMode(), "configuration.mode");
        this.allowedCidrs = parseCidrs(configuration.getAllowedCidrs());
    }

    /**
     * Authorizes every resolved address for one destination host.
     *
     * <p>Rejecting the whole set when one answer is blocked prevents the transport from silently
     * selecting another unchecked DNS answer.</p>
     *
     * @param host destination host used by the source URL
     * @param addresses addresses returned by the connection resolver
     */
    public void authorize(String host, List<InetAddress> addresses) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(addresses, "addresses");
        if (addresses.isEmpty()) {
            throw new OutboundAccessDeniedException("External source destination resolved to no addresses");
        }

        for (InetAddress address : addresses) {
            Objects.requireNonNull(address, "address");
            if (!isAllowed(address)) {
                throw new OutboundAccessDeniedException(
                        "External source destination is blocked by outbound access policy: host=" + host
                                + " address=" + address.getHostAddress());
            }
        }
    }

    /** Returns whether one address is permitted under the active operator policy. */
    boolean isAllowed(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (mode == OutboundAccessConfiguration.Mode.TRUSTED_LOCAL) {
            return true;
        }
        if (!isRestrictedAddress(address)) {
            return true;
        }
        return allowedCidrs.stream().anyMatch(cidr -> cidr.contains(address));
    }

    private static boolean isRestrictedAddress(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || isIpv4SharedAddress(address)
                || isIpv6UniqueLocal(address);
    }


    private static boolean isIpv4SharedAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) {
            return false;
        }
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        return first == 100 && second >= 64 && second <= 127;
    }

    private static boolean isIpv6UniqueLocal(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte first = address.getAddress()[0];
        return (first & 0xFE) == 0xFC;
    }

    private static List<IpCidr> parseCidrs(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(IpCidr::parse)
                .toList();
    }
}
