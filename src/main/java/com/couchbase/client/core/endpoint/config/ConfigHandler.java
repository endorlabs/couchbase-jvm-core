/**
 * Copyright (C) 2014 Couchbase, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALING
 * IN THE SOFTWARE.
 */
package com.couchbase.client.core.endpoint.config;

import com.couchbase.client.core.ResponseEvent;
import com.couchbase.client.core.endpoint.AbstractEndpoint;
import com.couchbase.client.core.endpoint.AbstractGenericHandler;
import com.couchbase.client.core.message.CouchbaseResponse;
import com.couchbase.client.core.message.ResponseStatus;
import com.couchbase.client.core.message.config.BucketConfigRequest;
import com.couchbase.client.core.message.config.BucketConfigResponse;
import com.couchbase.client.core.message.config.BucketStreamingRequest;
import com.couchbase.client.core.message.config.BucketStreamingResponse;
import com.couchbase.client.core.message.config.ConfigRequest;
import com.couchbase.client.core.message.config.FlushRequest;
import com.couchbase.client.core.message.config.FlushResponse;
import com.couchbase.client.core.message.config.GetDesignDocumentsRequest;
import com.couchbase.client.core.message.config.GetDesignDocumentsResponse;
import com.lmax.disruptor.RingBuffer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import rx.subjects.BehaviorSubject;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Queue;


/**
 * The {@link ConfigHandler} is responsible for encoding {@link ConfigRequest}s into lower level
 * {@link HttpRequest}s as well as decoding {@link HttpObject}s into
 * {@link CouchbaseResponse}s.
 *
 * @author Michael Nitschinger
 * @since 1.0
 */
public class ConfigHandler extends AbstractGenericHandler<HttpObject, HttpRequest, ConfigRequest> {

    /**
     * Contains the current pending response header if set.
     */
    private HttpResponse responseHeader;

    /**
     * Contains the accumulating buffer for the response content.
     */
    private ByteBuf responseContent;

    /**
     * Represents a observable that sends config chunks if instructed.
     */
    private BehaviorSubject<String> streamingConfigObservable;

    /**
     * Only needed to reset the request for a streaming config.
     */
    private ConfigRequest previousRequest = null;

    /**
     * Creates a new {@link ConfigHandler} with the default queue for requests.
     *
     * @param endpoint the {@link AbstractEndpoint} to coordinate with.
     * @param responseBuffer the {@link RingBuffer} to push responses into.
     */
    public ConfigHandler(AbstractEndpoint endpoint, RingBuffer<ResponseEvent> responseBuffer) {
        super(endpoint, responseBuffer);
    }

    /**
     * Creates a new {@link ConfigHandler} with a custom queue for requests (suitable for tests).
     *
     * @param endpoint the {@link AbstractEndpoint} to coordinate with.
     * @param responseBuffer the {@link RingBuffer} to push responses into.
     * @param queue the queue which holds all outstanding open requests.
     */
    ConfigHandler(AbstractEndpoint endpoint, RingBuffer<ResponseEvent> responseBuffer, Queue<ConfigRequest> queue) {
        super(endpoint, responseBuffer, queue);
    }

    @Override
    protected HttpRequest encodeRequest(final ChannelHandlerContext ctx, final ConfigRequest msg) throws Exception {
        HttpMethod httpMethod;

        if (msg instanceof BucketConfigRequest) {
            httpMethod = HttpMethod.GET;
        } else if (msg instanceof BucketStreamingRequest) {
            httpMethod = HttpMethod.GET;
        } else if(msg instanceof FlushRequest) {
            httpMethod = HttpMethod.POST;
        } else if (msg instanceof GetDesignDocumentsRequest) {
            httpMethod = HttpMethod.GET;
        } else {
            throw new IllegalArgumentException("Unknown incoming ConfigRequest type "
                + msg.getClass());
        }

        HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, httpMethod, msg.path());
        addAuth(ctx, request, msg.bucket(), msg.password());

        return request;
    }

    /**
     * Add basic authentication headers to a {@link HttpRequest}.
     *
     * The given information is Base64 encoded and the authorization header is set appropriately. Since this needs
     * to be done for every request, it is refactored out.
     *
     * @param ctx the handler context.
     * @param request the request where the header should be added.
     * @param user the username for auth.
     * @param password the password for auth.
     */
    private static void addAuth(final ChannelHandlerContext ctx, final HttpRequest request, final String user,
        final String password) {
        ByteBuf raw = ctx.alloc().buffer(user.length() + password.length() + 1);
        raw.writeBytes((user + ":" + password).getBytes(CHARSET));
        ByteBuf encoded = Base64.encode(raw);
        request.headers().add(HttpHeaders.Names.AUTHORIZATION, "Basic " + encoded.toString(CHARSET));
        encoded.release();
        raw.release();
    }

    @Override
    protected CouchbaseResponse decodeResponse(final ChannelHandlerContext ctx, final HttpObject msg) throws Exception {
        ConfigRequest request = currentRequest();
        CouchbaseResponse response = null;

        if (msg instanceof HttpResponse) {
            responseHeader = (HttpResponse) msg;

            if (request instanceof BucketStreamingRequest) {
                response = handleBucketStreamingResponse(ctx, responseHeader);
            }

            if (responseContent != null) {
                responseContent.clear();
            } else {
                responseContent = ctx.alloc().buffer();
            }
        }

        if (msg instanceof HttpContent) {
            responseContent.writeBytes(((HttpContent) msg).content());
            if (streamingConfigObservable != null) {
                if (currentRequest() == null) {
                    currentRequest(previousRequest);
                    previousRequest = null;
                }
                maybePushConfigChunk();
            }
        }

        if (msg instanceof LastHttpContent) {
            if (request instanceof BucketStreamingRequest) {
                if (streamingConfigObservable != null) {
                    streamingConfigObservable.onCompleted();
                    streamingConfigObservable = null;
                }
                return null;
            }

            ResponseStatus status = statusFromCode(responseHeader.getStatus().code());
            String body = responseContent.readableBytes() > 0
                ? responseContent.toString(CHARSET) : responseHeader.getStatus().reasonPhrase();

            if (request instanceof BucketConfigRequest) {
                response = new BucketConfigResponse(body, status);
            } else if (request instanceof GetDesignDocumentsRequest) {
                response = new GetDesignDocumentsResponse(body, status, request);
            } else if (request instanceof FlushRequest) {
                boolean done = responseHeader.getStatus().code() != 201;
                response = new FlushResponse(done, body, status);
            }
        }

        return response;
    }

    /**
     * Decodes a {@link BucketStreamingResponse}.
     *
     * @param ctx the handler context.
     * @param header the received header.
     * @return a initialized {@link CouchbaseResponse}.
     */
    private CouchbaseResponse handleBucketStreamingResponse(final ChannelHandlerContext ctx, final HttpResponse header) {
        SocketAddress addr = ctx.channel().remoteAddress();
        String host = addr instanceof InetSocketAddress ? ((InetSocketAddress) addr).getHostName() : addr.toString();
        ResponseStatus status = statusFromCode(header.getStatus().code());
        if (status.isSuccess()) {
            streamingConfigObservable = BehaviorSubject.create();
        }
        previousRequest = currentRequest();
        return new BucketStreamingResponse(streamingConfigObservable, host, status, currentRequest());
    }

    /**
     * Push a config chunk into the streaming observable.
     */
    private void maybePushConfigChunk() {
        String currentChunk = responseContent.toString(CHARSET);

        int separatorIndex = currentChunk.indexOf("\n\n\n\n");
        if (separatorIndex > 0) {
            String content = currentChunk.substring(0, separatorIndex);
            streamingConfigObservable.onNext(content.trim());
            responseContent.clear();
            responseContent.writeBytes(currentChunk.substring(separatorIndex + 4).getBytes(CHARSET));
        }
    }

    /**
     * Converts a HTTP status code in its appropriate {@link ResponseStatus} representation.
     *
     * @param code the http code.
     * @return the parsed status.
     */
    private static ResponseStatus statusFromCode(int code) {
        ResponseStatus status;
        switch(code) {
            case 200:
            case 201:
                status = ResponseStatus.SUCCESS;
                break;
            case 404:
                status = ResponseStatus.NOT_EXISTS;
                break;
            default:
                status = ResponseStatus.FAILURE;
        }
        return status;
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
        if (streamingConfigObservable != null) {
            streamingConfigObservable.onCompleted();
        }
        super.handlerRemoved(ctx);
    }

}
