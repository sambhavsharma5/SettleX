package com.settlex.exception;

/**
 * Thrown when an engine is invoked with a strategy it does not implement. Keeps the
 * {@code SettlementEngine} contract honest instead of silently degrading.
 */
public class UnsupportedStrategyException extends RuntimeException {

    private final String engine;
    private final String requested;

    public UnsupportedStrategyException(String engine, String requested, String supported) {
        super("Engine '" + engine + "' does not implement strategy '" + requested
                + "'; it implements " + supported);
        this.engine = engine;
        this.requested = requested;
    }

    public String engine() {
        return engine;
    }

    public String requested() {
        return requested;
    }
}