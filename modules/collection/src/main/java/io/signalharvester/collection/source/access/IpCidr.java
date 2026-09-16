package io.signalharvester.collection.source.access;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** Represents one validated IPv4 or IPv6 CIDR network used by outbound-access allow rules. */
final class IpCidr {

    private final byte[] network;
    private final int prefixLength;

    private IpCidr(byte[] network, int prefixLength) {
        this.network = network;
        this.prefixLength = prefixLength;
    }

    /** Parses one literal-address CIDR rule without performing DNS resolution. */
    static IpCidr parse(String value) {
        String candidate = value == null ? "" : value.trim();
        int slash = candidate.indexOf('/');
        if (slash <= 0 || slash == candidate.length() - 1) {
            throw new IllegalArgumentException("Outbound access CIDR must use address/prefix notation: " + candidate);
        }

        String addressText = stripIpv6Brackets(candidate.substring(0, slash));
        if (!isLiteralAddress(addressText)) {
            throw new IllegalArgumentException("Outbound access CIDR must use a literal IP address: " + candidate);
        }

        try {
            byte[] address = InetAddress.getByName(addressText).getAddress();
            int bits = address.length * Byte.SIZE;
            int prefix = Integer.parseInt(candidate.substring(slash + 1));
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("Outbound access CIDR prefix is out of range: " + candidate);
            }
            return new IpCidr(mask(address, prefix), prefix);
        } catch (UnknownHostException | NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid outbound access CIDR: " + candidate, failure);
        }
    }

    /** Returns whether the supplied address belongs to this network. */
    boolean contains(InetAddress address) {
        byte[] candidate = address.getAddress();
        return candidate.length == network.length && Arrays.equals(mask(candidate, prefixLength), network);
    }

    static boolean isLiteralAddress(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String candidate = stripIpv6Brackets(host);
        if (candidate.indexOf(':') >= 0) {
            return candidate.matches("[0-9A-Fa-f:.%]+") && candidate.chars().anyMatch(ch -> ch == ':');
        }
        return candidate.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}");
    }

    static String stripIpv6Brackets(String host) {
        if (host != null && host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static byte[] mask(byte[] address, int prefixLength) {
        byte[] masked = address.clone();
        int fullBytes = prefixLength / Byte.SIZE;
        int remainingBits = prefixLength % Byte.SIZE;
        if (fullBytes < masked.length && remainingBits > 0) {
            int bitMask = 0xFF << (Byte.SIZE - remainingBits);
            masked[fullBytes] = (byte) (masked[fullBytes] & bitMask);
            fullBytes++;
        }
        Arrays.fill(masked, fullBytes, masked.length, (byte) 0);
        return masked;
    }
}
