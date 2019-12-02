/**
 * Copyright 2019 SourceLab.org https://github.com/SourceLabOrg/HttpClientWrapper
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.sourcelab.http.rest;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.NameValuePair;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sourcelab.http.rest.configuration.Configuration;
import org.sourcelab.http.rest.configuration.ProxyConfiguration;
import org.sourcelab.http.rest.configuration.RequestHeader;
import org.sourcelab.http.rest.exceptions.ConnectionException;
import org.sourcelab.http.rest.exceptions.ResultParsingException;
import org.sourcelab.http.rest.handlers.RestResponseHandler;
import org.sourcelab.http.rest.interceptor.RequestContext;
import org.sourcelab.http.rest.interceptor.RequestInterceptor;
import org.sourcelab.http.rest.request.Request;
import org.sourcelab.http.rest.request.RequestMethod;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * RestClient implementation using HTTPClient.
 */
public class HttpClientRestClient implements RestClient {
    private static final Logger logger = LoggerFactory.getLogger(HttpClientRestClient.class);

    /**
     * Default headers included with every request.
     */
    private Collection<RequestHeader> defaultHeaders = new ArrayList<>();

    /**
     * Save a copy of the configuration.
     */
    private Configuration configuration;

    /**
     * Our underlying Http Client.
     */
    private CloseableHttpClient httpClient;

    private HttpClientContext httpClientContext;

    /**
     * To allow for custom modifications to request prior to submitting it.
     */
    private RequestInterceptor requestInterceptor;


    /**
     * Constructor.
     */
    public HttpClientRestClient() {
    }

    /**
     * Initialization method.  This takes in the configuration and sets up the underlying
     * http client appropriately.
     * @param configuration The user defined configuration.
     */
    @Override
    public void init(final Configuration configuration) {
        // Save reference to configuration
        this.configuration = configuration;

        // Load RequestMutator instance from configuration.
        requestInterceptor = configuration.getRequestInterceptor();

        // Load default headers
        if (configuration.getRequestHeaders() != null) {
            defaultHeaders = Collections.unmodifiableCollection(configuration.getRequestHeaders());
        }

        // Create https context builder utility.
        final HttpsContextBuilder httpsContextBuilder = new HttpsContextBuilder(configuration);

        // Setup client builder
        final HttpClientBuilder clientBuilder = HttpClientBuilder.create();
        clientBuilder
            // Define timeout
            .setConnectionTimeToLive(configuration.getRequestTimeoutInSeconds(), TimeUnit.SECONDS)

            // Define SSL Socket Factory instance.
            .setSSLSocketFactory(httpsContextBuilder.createSslSocketFactory());

        // Define our RequestConfigBuilder
        final RequestConfig.Builder requestConfigBuilder = RequestConfig.custom();

        // Define our Credentials Provider
        final CredentialsProvider credsProvider = new BasicCredentialsProvider();

        // Define our context
        httpClientContext = HttpClientContext.create();

        // Define our auth cache
        final AuthCache authCache = new BasicAuthCache();

        // If we have a configured proxy host
        if (configuration.getProxyConfiguration() != null) {
            final ProxyConfiguration proxyConfiguration = configuration.getProxyConfiguration();

            // Define proxy host
            final HttpHost proxyHost = new HttpHost(
                proxyConfiguration.getProxyHost(),
                proxyConfiguration.getProxyPort(),
                proxyConfiguration.getProxyScheme()
            );

            // If we have proxy auth enabled
            if (proxyConfiguration.isProxyAuthenticationEnabled()) {
                // Add proxy credentials
                credsProvider.setCredentials(
                    new AuthScope(proxyConfiguration.getProxyHost(), proxyConfiguration.getProxyPort()),
                    new UsernamePasswordCredentials(proxyConfiguration.getProxyUsername(), proxyConfiguration.getProxyPassword())
                );

                // Preemptive load context with authentication.
                authCache.put(
                    new HttpHost(
                        proxyConfiguration.getProxyHost(),
                        proxyConfiguration.getProxyPort(),
                        proxyConfiguration.getProxyScheme()
                    ), new BasicScheme()
                );
            }

            // Attach Proxy to request config builder
            requestConfigBuilder.setProxy(proxyHost);
        }

        // If BasicAuth credentials are configured.
        if (configuration.getBasicAuthUsername() != null) {
            try {
                // parse ApiHost for Hostname and port.
                final URL apiUrl = new URL(configuration.getApiHost());

                // Add credentials
                credsProvider.setCredentials(
                    new AuthScope(apiUrl.getHost(), apiUrl.getPort()),
                    new UsernamePasswordCredentials(
                        configuration.getBasicAuthUsername(),
                        configuration.getBasicAuthPassword()
                    )
                );

                // Preemptive load context with authentication.
                authCache.put(
                    new HttpHost(apiUrl.getHost(), apiUrl.getPort(), apiUrl.getProtocol()), new BasicScheme()
                );
            } catch (final MalformedURLException exception) {
                throw new RuntimeException(exception.getMessage(), exception);
            }
        }

        // Configure context.
        httpClientContext.setAuthCache(authCache);
        httpClientContext.setCredentialsProvider(credsProvider);

        // Attach Credentials provider to client builder.
        clientBuilder.setDefaultCredentialsProvider(credsProvider);

        // Attach default request config
        clientBuilder.setDefaultRequestConfig(requestConfigBuilder.build());

        // build http client
        httpClient = clientBuilder.build();
    }

    @Override
    public void close() {
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (final IOException e) {
                logger.error("Error closing: {}", e.getMessage(), e);
            }
        }
        httpClient = null;
    }

    /**
     * Make a request against the Pardot API.
     * @param request The request to submit.
     * @return The response, in UTF-8 String format.
     * @throws RestException if something goes wrong.
     */
    @Override
    public RestResponse submitRequest(final Request request) throws RestException {
        final String url = constructApiUrl(request.getApiEndpoint());
        final ResponseHandler<RestResponse> responseHandler = new RestResponseHandler();

        try {
            switch (request.getRequestMethod()) {
                case GET:
                    return submitGetRequest(url, Collections.emptyMap(), responseHandler);
                case POST:
                    return submitPostRequest(url, request.getRequestBody(), responseHandler);
                case PUT:
                    return submitPutRequest(url, request.getRequestBody(), responseHandler);
                case DELETE:
                    return submitDeleteRequest(url, request.getRequestBody(), responseHandler);
                default:
                    throw new IllegalArgumentException("Unknown Request Method: " + request.getRequestMethod());
            }
        } catch (final IOException exception) {
            throw new RestException(exception.getMessage(), exception);
        }
    }

    /**
     * Internal GET method.
     * @param url Url to GET to.
     * @param getParams GET parameters to include in the request
     * @param responseHandler The response Handler to use to parse the response
     * @param <T> The type that ResponseHandler returns.
     * @return Parsed response.
     */
    private <T> T submitGetRequest(final String url, final Map<String, String> getParams, final ResponseHandler<T> responseHandler) throws IOException {
        final RequestContext requestContext = new RequestContext(url, RequestMethod.GET);

        try {
            // Construct URI including our request parameters.
            final URIBuilder uriBuilder = new URIBuilder(url)
                .setCharset(StandardCharsets.UTF_8);

            // Attach submitRequest params
            for (final Map.Entry<String, String> entry : getParams.entrySet()) {
                uriBuilder.setParameter(entry.getKey(), entry.getValue());
            }

            // Build Get Request
            final HttpGet get = new HttpGet(uriBuilder.build());

            // Add headers.
            buildHeaders(get, requestContext);

            logger.debug("Executing request {}", get.getRequestLine());

            // Execute and return
            return httpClient.execute(get, responseHandler, httpClientContext);
        } catch (final ClientProtocolException | SocketException | URISyntaxException | SSLHandshakeException connectionException) {
            // Typically this is a connection or certificate issue.
            throw new ConnectionException(connectionException.getMessage(), connectionException);
        } catch (final IOException ioException) {
            // Typically this is a parse error.
            throw new ResultParsingException(ioException.getMessage(), ioException);
        }
    }

    /**
     * Internal POST method.
     * @param url Url to POST to.
     * @param requestBody POST entity include in the request body
     * @param responseHandler The response Handler to use to parse the response
     * @param <T> The type that ResponseHandler returns.
     * @return Parsed response.
     */
    private <T> T submitPostRequest(final String url, final Object requestBody, final ResponseHandler<T> responseHandler) throws IOException {
        final RequestContext requestContext = new RequestContext(url, RequestMethod.POST);

        try {
            final HttpPost post = new HttpPost(url);

            // Pass headers through interceptor interface
            buildHeaders(post, requestContext);

            // Build request entity
            post.setEntity(
                buildEntity(requestBody, requestContext)
            );
            logger.debug("Executing request {} with {}", post.getRequestLine(), requestBody);

            // Execute and return
            return httpClient.execute(post, responseHandler, httpClientContext);
        } catch (final ClientProtocolException | SocketException | SSLHandshakeException connectionException) {
            // Typically this is a connection issue.
            throw new ConnectionException(connectionException.getMessage(), connectionException);
        } catch (final IOException ioException) {
            // Typically this is a parse error.
            throw new ResultParsingException(ioException.getMessage(), ioException);
        }
    }

    /**
     * Internal PUT method.
     * @param url Url to POST to.
     * @param requestBody POST entity include in the request body
     * @param responseHandler The response Handler to use to parse the response
     * @param <T> The type that ResponseHandler returns.
     * @return Parsed response.
     */
    private <T> T submitPutRequest(final String url, final Object requestBody, final ResponseHandler<T> responseHandler) throws IOException {
        final RequestContext requestContext = new RequestContext(url, RequestMethod.PUT);

        try {
            final HttpPut put = new HttpPut(url);

            // Pass headers through interceptor interface
            buildHeaders(put, requestContext);

            // Build request entity
            put.setEntity(
                buildEntity(requestBody, requestContext)
            );
            logger.debug("Executing request {} with {}", put.getRequestLine(), requestBody);

            // Execute and return
            return httpClient.execute(put, responseHandler, httpClientContext);
        } catch (final ClientProtocolException | SocketException | SSLHandshakeException connectionException) {
            // Typically this is a connection issue.
            throw new ConnectionException(connectionException.getMessage(), connectionException);
        } catch (final IOException ioException) {
            // Typically this is a parse error.
            throw new ResultParsingException(ioException.getMessage(), ioException);
        }
    }

    /**
     * Internal DELETE method.
     * @param url Url to DELETE to.
     * @param requestBody POST entity include in the request body
     * @param responseHandler The response Handler to use to parse the response
     * @param <T> The type that ResponseHandler returns.
     * @return Parsed response.
     */
    private <T> T submitDeleteRequest(final String url, final Object requestBody, final ResponseHandler<T> responseHandler) throws IOException {
        final RequestContext requestContext = new RequestContext(url, RequestMethod.DELETE);

        try {
            final HttpDelete delete = new HttpDelete(url);

            // Pass headers through interceptor interface
            buildHeaders(delete, requestContext);

            // Delete requests have no request body.

            // Execute and return
            return httpClient.execute(delete, responseHandler, httpClientContext);
        } catch (final ClientProtocolException | SocketException | SSLHandshakeException connectionException) {
            // Typically this is a connection issue.
            throw new ConnectionException(connectionException.getMessage(), connectionException);
        } catch (final IOException ioException) {
            // Typically this is a parse error.
            throw new ResultParsingException(ioException.getMessage(), ioException);
        }
    }

    /**
     * Internal helper method for generating URLs w/ the appropriate API host and API version.
     * @param endPoint The end point you want to hit.
     * @return Constructed URL for the end point.
     */
    private String constructApiUrl(final String endPoint) {
        return configuration.getApiHost() + endPoint;
    }

    private HttpEntity buildEntity(final Object parameters, final RequestContext requestContext) throws UnsupportedEncodingException {
        if (parameters instanceof Map) {
            final Map<String, String> parametersMap = (Map<String, String>) parameters;

            // Pass request parameters through interceptor.
            requestInterceptor.modifyRequestParameters(parametersMap, requestContext);

            // Define required auth params
            final List<NameValuePair> params = new ArrayList<>();

            // Attach submitRequest params
            for (Map.Entry<String, String> entry : parametersMap.entrySet()) {
                params.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
            }
            return new UrlEncodedFormEntity(params);
        } else {
            return new StringEntity(parameters.toString());
        }
    }

    private void buildHeaders(final HttpRequestBase requestBase, final RequestContext requestContext) {
        // Pass headers through interceptor interface
        final List<RequestHeader> headers = new ArrayList<>(defaultHeaders);
        requestInterceptor.modifyHeaders(headers, requestContext);
        headers
            .stream()
            .map((entry) -> new BasicHeader(entry.getName(), entry.getValue()))
            .forEach(requestBase::addHeader);
    }
}
