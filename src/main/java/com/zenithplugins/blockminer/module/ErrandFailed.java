package com.zenithplugins.blockminer.module;

/** An errand couldn't finish. Either it's worth trying again, or the bot needs a person to step in. */
final class ErrandFailed extends RuntimeException {

    private final boolean worthRetrying;

    private ErrandFailed(String message, boolean worthRetrying) {
        super(message);
        this.worthRetrying = worthRetrying;
    }

    /** Something that may well work next time, like a path that couldn't be found. */
    static ErrandFailed retry(String message) {
        return new ErrandFailed(message, true);
    }

    /** Something only a person can fix, like the ender chest having no empty shulker boxes left. */
    static ErrandFailed stop(String message) {
        return new ErrandFailed(message, false);
    }

    boolean worthRetrying() {
        return worthRetrying;
    }
}
