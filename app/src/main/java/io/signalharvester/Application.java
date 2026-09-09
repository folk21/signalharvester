package io.signalharvester;

import io.micronaut.runtime.Micronaut;

/**
 * Starts the SignalHarvester Micronaut backend and serves as the application composition root.
 */
public final class Application {

    private Application() {
    }

    /**
     * Starts the Micronaut application context and configured server runtime.
     *
     * @param args command-line arguments passed to Micronaut
     */
    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
