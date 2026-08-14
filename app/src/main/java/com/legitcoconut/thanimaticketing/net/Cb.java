package com.legitcoconut.thanimaticketing.net;

/**
 * One callback shape for every API method, always delivered on the main thread.
 * Exactly one of value and error is non null, so a lambda handles both branches:
 *
 * <pre>
 * Api.getEvents((events, err) -&gt; {
 *     if (err != null) { showError(err); return; }
 *     render(events);
 * });
 * </pre>
 */
public interface Cb<T> {
    void done(T value, String error);
}
