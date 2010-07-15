/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.vysper.xmpp.extension.xep0124;

import java.util.LinkedList;
import java.util.Queue;

import javax.servlet.http.HttpServletRequest;

import org.apache.vysper.xml.fragment.Renderer;
import org.apache.vysper.xmpp.protocol.SessionStateHolder;
import org.apache.vysper.xmpp.server.AbstractSessionContext;
import org.apache.vysper.xmpp.server.ServerRuntimeContext;
import org.apache.vysper.xmpp.server.SessionState;
import org.apache.vysper.xmpp.stanza.Stanza;
import org.apache.vysper.xmpp.writer.StanzaWriter;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the session state of a BOSH client
 *
 * @author The Apache MINA Project (dev@mina.apache.org)
 */
public class BoshBackedSessionContext extends AbstractSessionContext implements StanzaWriter {

    private final static Logger LOGGER = LoggerFactory.getLogger(BoshBackedSessionContext.class);

    private final BoshHandler boshHandler;

    private final int inactivity = 60;

    private final int polling = 15;

    private int requests = 2;

    private String boshVersion = "1.9";

    private String contentType = BoshServlet.XML_CONTENT_TYPE;

    private int wait = 60;

    private int hold = 1;

    /*
     * Keeps the suspended HTTP requests (does not respond to them) until the server has an asynchronous message
     * to send to the client. (Comet HTTP Long Polling technique - described in XEP-0124)
     */
    private Queue<HttpServletRequest> requestQueue;

    /*
     * Keeps the asynchronous messages sent from server that cannot be delivered to the client because there are
     * no available HTTP requests to respond to (requestQueue is empty).
     */
    private Queue<Stanza> delayedResponseQueue;

    /**
     * Creates a new context for a session
     * @param boshHandler
     * @param serverRuntimeContext
     */
    public BoshBackedSessionContext(BoshHandler boshHandler, ServerRuntimeContext serverRuntimeContext) {
        super(serverRuntimeContext, new SessionStateHolder());

        // in BOSH we jump directly to the encrypted state
        sessionStateHolder.setState(SessionState.ENCRYPTED);

        this.boshHandler = boshHandler;
        requestQueue = new LinkedList<HttpServletRequest>();
        delayedResponseQueue = new LinkedList<Stanza>();
    }

    public SessionStateHolder getStateHolder() {
        return sessionStateHolder;
    }

    public StanzaWriter getResponseWriter() {
        return this;
    }

    public void setIsReopeningXMLStream() {
        // BOSH does not use XML streams, the BOSH equivalent for reopening an XML stream is to restart the BOSH connection,
        // and this is done in BoshHandler when the client requests it
    }

    /*
     * This method is synchronized on the session object to prevent concurrent writes to the same BOSH client
     */
    synchronized public void write(Stanza stanza) {
        write0(boshHandler.wrapStanza(stanza));
    }

    /**
     * Writes a BOSH response (that is wrapped in a &lt;body/&gt; element) if there are available HTTP requests
     * to respond to, otherwise the response is queued to be sent later (when a HTTP request will be available).
     * <p>
     * (package access)
     * 
     * @param response The BOSH response to write
     */
    void write0(Stanza response) {
        HttpServletRequest req = requestQueue.poll();
        if (req == null) {
            delayedResponseQueue.offer(response);
            return;
        }
        BoshResponse boshResponse = getBoshResponse(response);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("BOSH writing response: {}", new String(boshResponse.getContent()));
        }
        Continuation continuation = ContinuationSupport.getContinuation(req);
        continuation.setAttribute("response", boshResponse);
        continuation.resume();
    }

    public void close() {
        LOGGER.info("session will be closed now");
    }

    public void switchToTLS() {
        // BOSH cannot switch dynamically (because STARTTLS cannot be used with HTTP),
        // SSL can be enabled/disabled in BoshEndpoint#setSSLEnabled()
    }

    /**
     * Setter for the Content-Type header of the HTTP responses sent to the BOSH client associated with this session
     * @param contentType
     */
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    /**
     * Getter for the Content-Type header
     * @return the configured Content-Type
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * Setter for the BOSH 'wait' parameter, the longest time (in seconds) that the connection manager is allowed to
     * wait before responding to any request during the session. The BOSH client can only configure this parameter to
     * a lower value than the default value from this session context.
     * @param wait the BOSH 'wait' parameter
     */
    public void setWait(int wait) {
        this.wait = Math.min(wait, this.wait);
    }

    /**
     * Getter for the BOSH 'wait' parameter
     * @return The BOSH 'wait' parameter
     */
    public int getWait() {
        return wait;
    }

    /**
     * Setter for the BOSH 'hold' parameter, the maximum number of HTTP requests the connection manager is allowed to
     * keep waiting at any one time during the session. The value of this parameter can trigger the modification of
     * the BOSH 'requests' parameter.
     * @param hold
     */
    public void setHold(int hold) {
        this.hold = hold;
        if (hold >= 2) {
            requests = hold + 1;
        }
    }

    /**
     * Getter for the BOSH 'hold' parameter
     * @return the BOSH 'hold' parameter
     */
    public int getHold() {
        return hold;
    }

    /**
     * Setter for the highest version of the BOSH protocol that the connection manager supports, or the version
     * specified by the client in its request, whichever is lower.
     * @param version the BOSH version
     */
    public void setBoshVersion(String version) {
        String[] v = boshVersion.split("\\.");
        int major = Integer.parseInt(v[0]);
        int minor = Integer.parseInt(v[1]);
        v = version.split("\\.");

        if (v.length == 2) {
            int clientMajor = Integer.parseInt(v[0]);
            int clientMinor = Integer.parseInt(v[1]);

            if (clientMajor < major || (clientMajor == major && clientMinor < minor)) {
                boshVersion = version;
            }
        }
    }

    /**
     * Getter for the BOSH protocol version
     * @return the BOSH version
     */
    public String getBoshVersion() {
        return boshVersion;
    }

    /**
     * Getter for the BOSH 'inactivity' parameter, the longest allowable inactivity period (in seconds).
     * @return the BOSH 'inactivity' parameter
     */
    public int getInactivity() {
        return inactivity;
    }

    /**
     * Getter for the BOSH 'polling' parameter, the shortest allowable polling interval (in seconds).
     * @return the BOSH 'polling' parameter
     */
    public int getPolling() {
        return polling;
    }

    /**
     * Getter for the BOSH 'requests' parameter, the limit number of simultaneous requests the client makes.
     * @return the BOSH 'requests' parameter
     */
    public int getRequests() {
        return requests;
    }

    /*
     * A request expires when it stays enqueued in the requestQueue longer than the allowed 'wait' time.
     * The synchronization on the session object ensures that there will be no concurrent writes or other concurrent
     * expirations for the BOSH client while the current request expires.
     */
    synchronized private void requestExpired(Continuation continuation) {
        HttpServletRequest req = (HttpServletRequest) continuation.getAttribute("request");
        if (req == null) {
            LOGGER.warn("Continuation expired without having an associated request!");
            return;
        }
        continuation.setAttribute("response", getBoshResponse(boshHandler.getEmptyResponse()));
        for (;;) {
            HttpServletRequest r = requestQueue.peek();
            if (r == null) {
                break;
            }
            write0(boshHandler.getEmptyResponse());
            if (r == req) {
                break;
            }
        }
    }

    /**
     * Suspends and enqueues an HTTP request to be used later when an asynchronous message needs to be sent from
     * the connection manager to the BOSH client.
     * 
     * @param req the HTTP request
     */
    public void addRequest(HttpServletRequest req) {
        Continuation continuation = ContinuationSupport.getContinuation(req);
        continuation.setTimeout(wait * 1000);
        continuation.suspend();
        continuation.setAttribute("request", req);
        requestQueue.offer(req);

        // listen the continuation to be notified when the request expires
        continuation.addContinuationListener(new ContinuationListener() {

            public void onTimeout(Continuation continuation) {
                requestExpired(continuation);
            }

            public void onComplete(Continuation continuation) {
                // ignore
            }

        });

        // If there are delayed responses waiting to be sent to the BOSH client, then we wrap them all in
        // a <body/> element and send them as a HTTP response to the current HTTP request.
        Stanza delayedResponse;
        Stanza mergedResponse = null;
        while ((delayedResponse = delayedResponseQueue.poll()) != null) {
            mergedResponse = boshHandler.mergeResponses(mergedResponse, delayedResponse);
        }
        if (mergedResponse != null) {
            write0(mergedResponse);
            return;
        }

        // If there are more suspended enqueued requests than it is allowed by the BOSH 'hold' parameter,
        // than we release the oldest one by sending an empty response.
        if (requestQueue.size() > hold) {
            write0(boshHandler.getEmptyResponse());
        }
    }

    private BoshResponse getBoshResponse(Stanza stanza) {
        byte[] content = new Renderer(stanza).getComplete().getBytes();
        return new BoshResponse(contentType, content);
    }

}
