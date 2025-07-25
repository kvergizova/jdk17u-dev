/*
 * Copyright (c) 2015, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.internal.net.http;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import jdk.internal.net.http.common.Logger;
import jdk.internal.net.http.common.MinimalFuture;
import jdk.internal.net.http.common.Utils;
import jdk.internal.net.http.frame.SettingsFrame;
import static jdk.internal.net.http.frame.SettingsFrame.INITIAL_WINDOW_SIZE;
import static jdk.internal.net.http.frame.SettingsFrame.ENABLE_PUSH;
import static jdk.internal.net.http.frame.SettingsFrame.HEADER_TABLE_SIZE;
import static jdk.internal.net.http.frame.SettingsFrame.MAX_CONCURRENT_STREAMS;
import static jdk.internal.net.http.frame.SettingsFrame.MAX_FRAME_SIZE;
import static jdk.internal.net.http.frame.SettingsFrame.MAX_HEADER_LIST_SIZE;

/**
 *  Http2 specific aspects of HttpClientImpl
 */
class Http2ClientImpl {

    static final Logger debug =
            Utils.getDebugLogger("Http2ClientImpl"::toString, Utils.DEBUG);

    private final HttpClientImpl client;
    private volatile boolean stopping;

    Http2ClientImpl(HttpClientImpl client) {
        this.client = client;
    }

    /* Map key is "scheme:host:port" */
    private final Map<String,Http2Connection> connections = new ConcurrentHashMap<>();

    private final Set<String> failures = Collections.synchronizedSet(new HashSet<>());

    /**
     * When HTTP/2 requested only. The following describes the aggregate behavior including the
     * calling code. In all cases, the HTTP2 connection cache
     * is checked first for a suitable connection and that is returned if available.
     * If not, a new connection is opened, except in https case when a previous negotiate failed.
     * In that case, we want to continue using http/1.1. When a connection is to be opened and
     * if multiple requests are sent in parallel then each will open a new connection.
     *
     * If negotiation/upgrade succeeds then
     * one connection will be put in the cache and the others will be closed
     * after the initial request completes (not strictly necessary for h2, only for h2c)
     *
     * If negotiate/upgrade fails, then any opened connections remain open (as http/1.1)
     * and will be used and cached in the http/1 cache. Note, this method handles the
     * https failure case only (by completing the CF with an ALPN exception, handled externally)
     * The h2c upgrade is handled externally also.
     *
     * Specific CF behavior of this method.
     * 1. completes with ALPN exception: h2 negotiate failed for first time. failure recorded.
     * 2. completes with other exception: failure not recorded. Caller must handle
     * 3. completes normally with null: no connection in cache for h2c or h2 failed previously
     * 4. completes normally with connection: h2 or h2c connection in cache. Use it.
     */
    CompletableFuture<Http2Connection> getConnectionFor(HttpRequestImpl req,
                                                        Exchange<?> exchange) {
        String key = Http2Connection.keyFor(req);
        boolean pushEnabled = exchange.pushEnabled();

        synchronized (this) {
            Http2Connection connection = connections.get(key);
            if (connection != null) {
                try {
                    if (!connection.isOpen()
                            || !connection.reserveStream(true, pushEnabled)) {
                        if (debug.on())
                            debug.log("removing connection from pool since " +
                                    "it couldn't be reserved for use%s: %s",
                                    pushEnabled ? " with server push enabled" :
                                            "", connection);
                        deleteConnection(connection);
                    } else {
                        // fast path if connection already exists
                        if (debug.on())
                            debug.log("found connection in the pool: %s", connection);
                        return MinimalFuture.completedFuture(connection);
                    }
                } catch (IOException e) {
                    // thrown by connection.reserveStream()
                    return MinimalFuture.failedFuture(e);
                }
            }

            if (!req.secure() || failures.contains(key)) {
                // secure: negotiate failed before. Use http/1.1
                // !secure: no connection available in cache. Attempt upgrade
                if (debug.on()) debug.log("not found in connection pool");
                return MinimalFuture.completedFuture(null);
            }
        }
        return Http2Connection
                .createAsync(req, this, exchange)
                .whenComplete((conn, t) -> {
                    synchronized (Http2ClientImpl.this) {
                        if (conn != null) {
                            try {
                                conn.reserveStream(true, exchange.pushEnabled());
                            } catch (IOException e) {
                                throw new UncheckedIOException(e); // shouldn't happen
                            }
                            offerConnection(conn);
                        } else {
                            Throwable cause = Utils.getCompletionCause(t);
                            if (cause instanceof Http2Connection.ALPNException)
                                failures.add(key);
                        }
                    }
                });
    }

    /*
     * Cache the given connection, if no connection to the same
     * destination exists. If one exists, then we let the initial stream
     * complete but allow it to close itself upon completion.
     * This situation should not arise with https because the request
     * has not been sent as part of the initial alpn negotiation
     */
    boolean offerConnection(Http2Connection c) {
        if (debug.on()) debug.log("offering to the connection pool: %s", c);
        if (!c.isOpen() || c.finalStream()) {
            if (debug.on())
                debug.log("skipping offered closed or closing connection: %s", c);
            return false;
        }

        String key = c.key();
        synchronized(this) {
            if (stopping) {
                if (debug.on()) debug.log("stopping - closing connection: %s", c);
                close(c);
                return false;
            }
            if (!c.isOpen()) {
                if (debug.on())
                    debug.log("skipping offered closed or closing connection: %s", c);
                return false;
            }
            Http2Connection c1 = connections.putIfAbsent(key, c);
            if (c1 != null) {
                if (c.serverPushEnabled() && !c1.serverPushEnabled()) {
                    c1.setFinalStream();
                    connections.remove(key, c1);
                    connections.put(key, c);
                    if (debug.on()) {
                        debug.log("Replacing %s with %s in connection pool", c1, c);
                    }
                    if (c1.shouldClose()) c1.close();
                    return  true;
                } else {
                    c.setFinalStream();
                    if (debug.on())
                        debug.log("existing entry in connection pool for %s", key);
                    return false;
                }
            }
            if (debug.on())
                debug.log("put in the connection pool: %s", c);
            return true;
        }
    }

    void deleteConnection(Http2Connection c) {
        if (debug.on())
            debug.log("removing from the connection pool: %s", c);
        synchronized (this) {
            Http2Connection c1 = connections.get(c.key());
            if (c1 != null && c1.equals(c)) {
                connections.remove(c.key());
                if (debug.on())
                    debug.log("removed from the connection pool: %s", c);
            }
        }
    }

    private EOFException STOPPED;
    void stop() {
        if (debug.on()) debug.log("stopping");
        STOPPED = new EOFException("HTTP/2 client stopped");
        STOPPED.setStackTrace(new StackTraceElement[0]);
        synchronized (this) {stopping = true;}
        do {
            connections.values().forEach(this::close);
        } while (!connections.isEmpty());
    }

    private void close(Http2Connection h2c) {
        // close all streams
        try { h2c.closeAllStreams(); } catch (Throwable t) {}
        // send GOAWAY
        try { h2c.close(); } catch (Throwable t) {}
        // attempt graceful shutdown
        try { h2c.shutdown(STOPPED); } catch (Throwable t) {}
        // double check and close any new streams
        try { h2c.closeAllStreams(); } catch (Throwable t) {}
    }

    HttpClientImpl client() {
        return client;
    }

    /** Returns the client settings as a base64 (url) encoded string */
    String getSettingsString(boolean defaultServerPush) {
        SettingsFrame sf = getClientSettings(defaultServerPush);
        byte[] settings = sf.toByteArray(); // without the header
        Base64.Encoder encoder = Base64.getUrlEncoder()
                                       .withoutPadding();
        return encoder.encodeToString(settings);
    }

    private static final int K = 1024;

    private static int getParameter(String property, int min, int max, int defaultValue) {
        return Utils.getIntegerNetProperty(property, min, max, defaultValue, true);
    }

    // used for the connection window, to have a connection window size
    // bigger than the initial stream window size.
    int getConnectionWindowSize(SettingsFrame clientSettings) {
        // Maximum size is 2^31-1. Don't allow window size to be less
        // than the stream window size. HTTP/2 specify a default of 64 * K -1,
        // but we use 2^26 by default for better performance.
        int streamWindow = clientSettings.getParameter(INITIAL_WINDOW_SIZE);

        // The default is the max between the stream window size
        // and the connection window size.
        int defaultValue = Math.min(Integer.MAX_VALUE,
                Math.max(streamWindow, K*K*32));

        return getParameter(
                "jdk.httpclient.connectionWindowSize",
                streamWindow, Integer.MAX_VALUE, defaultValue);
    }

    /**
     * This method is used to test whether pushes are globally
     * disabled on all connections.
     * @return true if pushes are globally disabled on all connections
     */
    boolean serverPushDisabled() {
        return getParameter(
                "jdk.httpclient.enablepush",
                0, 1, 1) == 0;
    }

    SettingsFrame getClientSettings(boolean defaultServerPush) {
        SettingsFrame frame = new SettingsFrame();
        // default defined for HTTP/2 is 4 K, we use 16 K.
        frame.setParameter(HEADER_TABLE_SIZE, getParameter(
                "jdk.httpclient.hpack.maxheadertablesize",
                0, Integer.MAX_VALUE, 16 * K));
        // O: does not accept push streams. 1: accepts push streams.
        frame.setParameter(ENABLE_PUSH, getParameter(
                "jdk.httpclient.enablepush",
                0, 1, defaultServerPush ? 1 : 0));
        // HTTP/2 recommends to set the number of concurrent streams
        // no lower than 100. We use 100, unless push promises are
        // disabled.
        int initialServerStreams = frame.getParameter(ENABLE_PUSH) == 0
                ? 0 : 100;
        frame.setParameter(MAX_CONCURRENT_STREAMS, getParameter(
                "jdk.httpclient.maxstreams",
                0, Integer.MAX_VALUE, initialServerStreams));
        // Maximum size is 2^31-1. Don't allow window size to be less
        // than the minimum frame size as this is likely to be a
        // configuration error. HTTP/2 specify a default of 64 * K -1,
        // but we use 16 M  for better performance.
        frame.setParameter(INITIAL_WINDOW_SIZE, getParameter(
                "jdk.httpclient.windowsize",
                16 * K, Integer.MAX_VALUE, 16*K*K));
        // HTTP/2 specify a minimum size of 16 K, a maximum size of 2^24-1,
        // and a default of 16 K. We use 16 K as default.
        frame.setParameter(MAX_FRAME_SIZE, getParameter(
                "jdk.httpclient.maxframesize",
                16 * K, 16 * K * K -1, 16 * K));
        // Maximum field section size we're prepared to accept
        // This is the uncompressed name + value size + 32 per field line
        int maxHeaderSize = getParameter(
                "jdk.http.maxHeaderSize",
                Integer.MIN_VALUE, Integer.MAX_VALUE, 384 * K);
        // If the property is <= 0 the value is unlimited
        if (maxHeaderSize <= 0) maxHeaderSize = -1;
        frame.setParameter(MAX_HEADER_LIST_SIZE, maxHeaderSize);
        return frame;
    }
}
