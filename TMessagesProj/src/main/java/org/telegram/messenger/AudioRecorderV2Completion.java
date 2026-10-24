package org.telegram.messenger;

import java.util.function.Consumer;

/** Holds finalized audio until the schedule picker decides its fate. Used on the UI thread. */
final class AudioRecorderV2Completion<T> {
    private final Consumer<T> onCompleted;
    private boolean waitingForDecision;
    private boolean completed;
    private boolean delivered;
    private boolean cancelled;
    private T result;

    AudioRecorderV2Completion(Consumer<T> onCompleted) {
        this.onCompleted = onCompleted;
    }

    /** Returns true when encoding has already completed or this recording was cancelled. */
    boolean request(boolean waitForDecision) {
        if (cancelled || delivered) return true;
        waitingForDecision = waitForDecision;
        deliverIfReady();
        return completed;
    }

    void complete(T result) {
        if (cancelled || completed) return;
        completed = true;
        this.result = result;
        deliverIfReady();
    }

    void cancel() {
        cancelled = true;
        result = null;
    }

    private void deliverIfReady() {
        if (!completed || waitingForDecision || cancelled || delivered) return;
        delivered = true;
        T value = result;
        result = null;
        onCompleted.accept(value);
    }
}
