/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.client5.http.impl.sync;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.ConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.EntityBuilder;
import org.apache.hc.client5.http.impl.io.ConnectionShutdownException;
import org.apache.hc.client5.http.io.ConnectionRequest;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.methods.HttpExecutionAware;
import org.apache.hc.client5.http.methods.HttpGet;
import org.apache.hc.client5.http.methods.RoutedHttpRequest;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ConnectionReuseStrategy;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.impl.io.HttpRequestExecutor;
import org.apache.hc.core5.http.io.HttpClientConnection;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SuppressWarnings({"boxing","static-access"}) // test code
public class TestMinimalClientExec {

    @Mock
    private HttpRequestExecutor requestExecutor;
    @Mock
    private HttpClientConnectionManager connManager;
    @Mock
    private ConnectionReuseStrategy reuseStrategy;
    @Mock
    private ConnectionKeepAliveStrategy keepAliveStrategy;
    @Mock
    private HttpExecutionAware execAware;
    @Mock
    private ConnectionRequest connRequest;
    @Mock
    private HttpClientConnection managedConn;

    private MinimalClientExec minimalClientExec;
    private HttpHost target;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        minimalClientExec = new MinimalClientExec(
                requestExecutor, connManager, reuseStrategy, keepAliveStrategy);
        target = new HttpHost("foo", 80);

        Mockito.when(connManager.requestConnection(
                Mockito.<HttpRoute>any(), Mockito.any())).thenReturn(connRequest);
        Mockito.when(connRequest.get(
                Mockito.anyLong(), Mockito.<TimeUnit>any())).thenReturn(managedConn);
    }

    @Test
    public void testExecRequestNonPersistentConnection() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = new HttpClientContext();
        final RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(123)
                .setSocketTimeout(234)
                .setConnectionRequestTimeout(345)
                .build();
        context.setRequestConfig(config);
        final RoutedHttpRequest request = RoutedHttpRequest.adapt(new HttpGet("http://bar/test"), route);
        final ClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
        Mockito.when(requestExecutor.execute(
                Mockito.same(request),
                Mockito.<HttpClientConnection>any(),
                Mockito.<HttpClientContext>any())).thenReturn(response);

        final ClassicHttpResponse finalResponse = minimalClientExec.execute(request, context, execAware);
        Mockito.verify(connManager).requestConnection(route, null);
        Mockito.verify(connRequest).get(345, TimeUnit.MILLISECONDS);
        Mockito.verify(execAware, Mockito.times(1)).setCancellable(connRequest);
        Mockito.verify(execAware, Mockito.times(2)).setCancellable(Mockito.<Cancellable>any());
        Mockito.verify(connManager).connect(managedConn, route, 123, context);
        Mockito.verify(connManager).routeComplete(managedConn, route, context);
        Mockito.verify(managedConn).setSocketTimeout(234);
        Mockito.verify(requestExecutor, Mockito.times(1)).execute(request, managedConn, context);
        Mockito.verify(managedConn, Mockito.times(1)).close();
        Mockito.verify(connManager).releaseConnection(managedConn, null, 0, TimeUnit.MILLISECONDS);

        Assert.assertSame(managedConn, context.getConnection());
        Assert.assertNotNull(finalResponse);
        Assert.assertTrue(finalResponse instanceof CloseableHttpResponse);
    }

    @Test
    public void testExecRequestPersistentConnection() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = new HttpClientContext();
        final RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(123)
                .setSocketTimeout(234)
                .setConnectionRequestTimeout(345)
                .build();
        context.setRequestConfig(config);
        final RoutedHttpRequest request = RoutedHttpRequest.adapt(new HttpGet("http://bar/test"), route);
        final ClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");

        Mockito.when(managedConn.isOpen()).thenReturn(Boolean.TRUE);
        Mockito.when(managedConn.isStale()).thenReturn(Boolean.FALSE);
        Mockito.when(requestExecutor.execute(
                Mockito.same(request),
                Mockito.<HttpClientConnection>any(),
                Mockito.<HttpClientContext>any())).thenReturn(response);
        Mockito.when(reuseStrategy.keepAlive(
                Mockito.same(request),
                Mockito.same(response),
                Mockito.<HttpClientContext>any())).thenReturn(Boolean.TRUE);
        Mockito.when(keepAliveStrategy.getKeepAliveDuration(
                Mockito.same(response),
                Mockito.<HttpClientContext>any())).thenReturn(678L);

        final ClassicHttpResponse finalResponse = minimalClientExec.execute(request, context, execAware);
        Mockito.verify(connManager).requestConnection(route, null);
        Mockito.verify(connRequest).get(345, TimeUnit.MILLISECONDS);
        Mockito.verify(requestExecutor, Mockito.times(1)).execute(request, managedConn, context);
        Mockito.verify(connManager).releaseConnection(managedConn, null, 678L, TimeUnit.MILLISECONDS);
        Mockito.verify(managedConn, Mockito.never()).close();

        Assert.assertNotNull(finalResponse);
        Assert.assertTrue(finalResponse instanceof CloseableHttpResponse);
    }

    @Test
    public void testExecRequestConnectionRelease() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = new HttpClientContext();
        final RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(123)
                .setSocketTimeout(234)
                .setConnectionRequestTimeout(345)
                .build();
        context.setRequestConfig(config);
        final RoutedHttpRequest request = RoutedHttpRequest.adapt(new HttpGet("http://bar/test"), route);
        final ClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
        // The entity is streaming
        response.setEntity(EntityBuilder.create()
                .setStream(new ByteArrayInputStream(new byte[]{}))
                .build());

        Mockito.when(managedConn.isOpen()).thenReturn(Boolean.TRUE);
        Mockito.when(managedConn.isStale()).thenReturn(Boolean.FALSE);
        Mockito.when(requestExecutor.execute(
                Mockito.same(request),
                Mockito.<HttpClientConnection>any(),
                Mockito.<HttpClientContext>any())).thenReturn(response);
        Mockito.when(reuseStrategy.keepAlive(
                Mockito.same(request),
                Mockito.same(response),
                Mockito.<HttpClientContext>any())).thenReturn(Boolean.FALSE);

        final ClassicHttpResponse finalResponse = minimalClientExec.execute(request, context, execAware);
        Mockito.verify(connManager).requestConnection(route, null);
        Mockito.verify(connRequest).get(345, TimeUnit.MILLISECONDS);
        Mockito.verify(requestExecutor, Mockito.times(1)).execute(request, managedConn, context);
        Mockito.verify(connManager, Mockito.never()).releaseConnection(
                Mockito.same(managedConn),
                Mockito.any(),
                Mockito.anyInt(),
                Mockito.<TimeUnit>any());
        Mockito.verify(managedConn, Mockito.never()).close();

        Assert.assertNotNull(finalResponse);
        Assert.assertTrue(finalResponse instanceof CloseableHttpResponse);
        finalResponse.close();

        Mockito.verify(connManager, Mockito.times(1)).releaseConnection(
                managedConn, null, 0, TimeUnit.MILLISECONDS);
        Mockito.verify(managedConn, Mockito.times(1)).close();
    }

    @Test
    public void testSocketTimeoutExistingConnection() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = new HttpClientContext();
        final RequestConfig config = RequestConfig.custom().setSocketTimeout(3000).build();
        final RoutedHttpRequest request = RoutedHttpRequest.adapt(new HttpGet("http://bar/test"), route);
        context.setRequestConfig(config);
        final ClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
        Mockito.when(managedConn.isOpen()).thenReturn(true);
        Mockito.when(requestExecutor.execute(
                Mockito.same(request),
                Mockito.<HttpClientConnection>any(),
                Mockito.<HttpClientContext>any())).thenReturn(response);

        minimalClientExec.execute(request, context, execAware);
        Mockito.verify(managedConn).setSocketTimeout(3000);
    }

    @Test
    public void testSocketTimeoutReset() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = new HttpClientContext();
        final RoutedHttpRequest request = RoutedHttpRequest.adapt(new HttpGet("http://bar/test"), route);
        final ClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
        Mockito.when(managedConn.isOpen()).thenReturn(Boolean.TRUE);
        Mockito.when(requestExecutor.execute(
                Mockito.same(request),
                Mockito.<HttpClientConnection>any(),
                Mockito.<HttpClientContext>any())).thenReturn(response);

        minimalClientExec.execute(request, context, execAware);
        Mockito.verify(managedConn, Mockito.never()).setSocketTimeout(Mockito.anyInt());
    }

    @Test(expected=RequestAbortedException.class)
    public void testExecAbortedPriorToConnectionLease() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = new HttpClientContext();
        final RoutedHttpRequest request = RoutedHttpRequest.adapt(new HttpGet("http://bar/test"), route);

        Mockito.when(managedConn.isOpen()).thenReturn(Boolean.FALSE);
        Mockito.when(execAware.isAborted()).thenReturn(Boolean.TRUE);
        try {
            minimalClientExec.execute(request, context, execAware);
        } catch (final IOException ex) {
            Mockito.verify(connRequest, Mockito.times(1)).cancel();
            throw ex;
        }
    }

    @Test(expected=RequestAbortedException.class)
    public void testExecConnectionRequestFailed() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = new HttpClientContext();
        final RoutedHttpRequest request = RoutedHttpRequest.adapt(new HttpGet("http://bar/test"), route);

        Mockito.when(connRequest.get(Mockito.anyInt(), Mockito.<TimeUnit>any()))
                .thenThrow(new ExecutionException("Opppsie", null));
        minimalClientExec.execute(request, context, execAware);
    }

    @Test(expected=InterruptedIOException.class)
    public void testExecConnectionShutDown() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = new HttpClientContext();
        final RoutedHttpRequest request = RoutedHttpRequest.adapt(new HttpGet("http://bar/test"), route);

        Mockito.when(requestExecutor.execute(
                Mockito.<ClassicHttpRequest>any(),
                Mockito.<HttpClientConnection>any(),
                Mockito.<HttpClientContext>any())).thenThrow(new ConnectionShutdownException());

        minimalClientExec.execute(request, context, execAware);
    }

    @Test(expected=RuntimeException.class)
    public void testExecRuntimeException() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = new HttpClientContext();
        final RoutedHttpRequest request = RoutedHttpRequest.adapt(new HttpGet("http://bar/test"), route);

        Mockito.when(requestExecutor.execute(
                Mockito.<ClassicHttpRequest>any(),
                Mockito.<HttpClientConnection>any(),
                Mockito.<HttpClientContext>any())).thenThrow(new RuntimeException("Ka-boom"));

        try {
            minimalClientExec.execute(request, context, execAware);
        } catch (final Exception ex) {
            Mockito.verify(connManager).releaseConnection(managedConn, null, 0, TimeUnit.MILLISECONDS);

            throw ex;
        }
    }

    @Test(expected=HttpException.class)
    public void testExecHttpException() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = new HttpClientContext();
        final RoutedHttpRequest request = RoutedHttpRequest.adapt(new HttpGet("http://bar/test"), route);

        Mockito.when(requestExecutor.execute(
                Mockito.<ClassicHttpRequest>any(),
                Mockito.<HttpClientConnection>any(),
                Mockito.<HttpClientContext>any())).thenThrow(new HttpException("Ka-boom"));

        try {
            minimalClientExec.execute(request, context, execAware);
        } catch (final Exception ex) {
            Mockito.verify(connManager).releaseConnection(managedConn, null, 0, TimeUnit.MILLISECONDS);

            throw ex;
        }
    }

    @Test(expected=IOException.class)
    public void testExecIOException() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = new HttpClientContext();
        final RoutedHttpRequest request = RoutedHttpRequest.adapt(new HttpGet("http://bar/test"), route);

        Mockito.when(requestExecutor.execute(
                Mockito.<ClassicHttpRequest>any(),
                Mockito.<HttpClientConnection>any(),
                Mockito.<HttpClientContext>any())).thenThrow(new IOException("Ka-boom"));

        try {
            minimalClientExec.execute(request, context, execAware);
        } catch (final Exception ex) {
            Mockito.verify(connManager).releaseConnection(managedConn, null, 0, TimeUnit.MILLISECONDS);

            throw ex;
        }
    }

    @Test
    public void absoluteUriIsRewrittenToRelativeBeforeBeingPassedInRequestLine() throws Exception {
        final HttpRoute route = new HttpRoute(target);
        final HttpClientContext context = new HttpClientContext();
        final RoutedHttpRequest request = RoutedHttpRequest.adapt(new HttpGet("http://bar/test"), route);

        final ClassicHttpResponse response = Mockito.mock(ClassicHttpResponse.class);
        Mockito.when(requestExecutor.execute(
                Mockito.<ClassicHttpRequest>any(),
                Mockito.<HttpClientConnection>any(),
                Mockito.<HttpClientContext>any())).thenReturn(response);

        minimalClientExec.execute(request, context, execAware);

        final ArgumentCaptor<ClassicHttpRequest> reqCaptor = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        Mockito.verify(requestExecutor).execute(reqCaptor.capture(), Mockito.<HttpClientConnection>any(), Mockito.<HttpClientContext>any());

        Assert.assertEquals("/test", reqCaptor.getValue().getRequestUri());
    }
}
