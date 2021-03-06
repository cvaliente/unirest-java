/**
 * The MIT License
 *
 * Copyright for portions of OpenUnirest/uniresr-java are held by Mashape (c) 2013 as part of Kong/unirest-java.
 * All other copyright for OpenUnirest/unirest-java are held by OpenUnirest (c) 2018.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package unirest;

import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.nio.client.HttpAsyncClient;
import org.apache.http.nio.reactor.IOReactorException;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

import static unirest.Util.tryCast;

class ApacheAsyncClient extends BaseApacheClient implements AsyncClient {

    private final HttpAsyncClient client;
    private final AsyncIdleConnectionMonitorThread syncMonitor;
    private final PoolingNHttpClientConnectionManager manager;
    private Config config;

    ApacheAsyncClient(Config config) {
        this.config = config;
        try {
            manager = new PoolingNHttpClientConnectionManager(new DefaultConnectingIOReactor());
            manager.setMaxTotal(config.getMaxConnections());
            manager.setDefaultMaxPerRoute(config.getMaxPerRoutes());

            HttpAsyncClientBuilder ab = HttpAsyncClientBuilder.create()
                    .setDefaultRequestConfig(getRequestConfig(config))
                    .setConnectionManager(manager)
                    .setDefaultCredentialsProvider(config.getProxyCreds())
                    .useSystemProperties();

            if(config.useSystemProperties()){
                ab.useSystemProperties();
            }
            if (!config.getFollowRedirects()) {
                ab.setRedirectStrategy(new NoRedirects());
            }
            if (!config.getEnabledCookieManagement()) {
                ab.disableCookieManagement();
            }
            config.getInterceptors().forEach(ab::addInterceptorFirst);

            CloseableHttpAsyncClient build = ab.build();
            build.start();
            syncMonitor = new AsyncIdleConnectionMonitorThread(manager);
            syncMonitor.tryStart();
            client = build;
        } catch (IOReactorException e) {
            throw new UnirestConfigException(e);
        }
    }

    ApacheAsyncClient(HttpAsyncClient client,
                      Config config,
                      PoolingNHttpClientConnectionManager manager,
                      AsyncIdleConnectionMonitorThread monitor) {
        Objects.requireNonNull(client, "Client may not be null");
        this.config = config;
        this.client = client;
        this.syncMonitor = monitor;
        this.manager = manager;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> request(
            HttpRequest request,
            Function<RawResponse, HttpResponse<T>> transformer,
            CompletableFuture<HttpResponse<T>> callback) {

        Objects.requireNonNull(callback);

        HttpUriRequest requestObj = new RequestPrep(request, true).prepare();

        client.execute(requestObj, new FutureCallback<org.apache.http.HttpResponse>() {
                    @Override
                    public void completed(org.apache.http.HttpResponse httpResponse) {
                        callback.complete(transformer.apply(new ApacheResponse(httpResponse, config)));
                    }

                    @Override
                    public void failed(Exception e) {
                        callback.completeExceptionally(e);
                    }

                    @Override
                    public void cancelled() {
                        callback.completeExceptionally(new UnirestException("canceled"));
                    }
                });
        return callback;
    }

    @Override
    public boolean isRunning() {
        return tryCast(client, CloseableHttpAsyncClient.class)
                .map(CloseableHttpAsyncClient::isRunning)
                .orElse(true);
    }

    @Override
    public HttpAsyncClient getClient() {
        return client;
    }

    @Override
    public Stream<Exception> close() {
        return Util.collectExceptions(Util.tryCast(client, CloseableHttpAsyncClient.class)
                        .filter(c -> c.isRunning())
                        .map(c -> Util.tryDo(c, d -> d.close()))
                        .filter(c -> c.isPresent())
                        .map(c -> c.get()),
                Util.tryDo(manager, m -> m.shutdown()),
                Util.tryDo(syncMonitor, m -> m.interrupt()));
    }
}
