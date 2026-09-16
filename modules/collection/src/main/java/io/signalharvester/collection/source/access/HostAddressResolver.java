package io.signalharvester.collection.source.access;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** Internal DNS boundary used by the connection-bound outbound access resolver. */
@FunctionalInterface
public interface HostAddressResolver {

    /** Resolves every address currently advertised for one hostname. */
    List<InetAddress> resolveAll(String host) throws UnknownHostException;
}
