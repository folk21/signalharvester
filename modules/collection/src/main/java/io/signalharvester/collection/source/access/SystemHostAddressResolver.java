package io.signalharvester.collection.source.access;

import jakarta.inject.Singleton;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/** Resolves source hostnames through the JVM system resolver. */
@Singleton
public final class SystemHostAddressResolver implements HostAddressResolver {

    @Override
    public List<InetAddress> resolveAll(String host) throws UnknownHostException {
        return List.copyOf(Arrays.asList(InetAddress.getAllByName(host)));
    }
}
