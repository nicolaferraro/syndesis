/*
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.connector.odata;

import java.net.URI;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.camel.util.ObjectHelper;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.olingo.client.api.http.HttpClientFactory;
import org.apache.olingo.commons.api.http.HttpMethod;
import io.syndesis.connector.support.util.KeyStoreHelper;

@SuppressWarnings("PMD")
public class ODataUtil implements ODataConstants {

    public static class ODataHttpClientFactory implements HttpClientFactory {

        private final Map<String, Object> options;

        public ODataHttpClientFactory(Map<String, Object> options) {
            this.options = options;
        }

        @Override
        public HttpClient create(HttpMethod method, URI uri) {
            try {
                return createHttpClient(options);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @SuppressWarnings( "deprecation" )
        @Override
        public void close(HttpClient httpClient) {
            httpClient.getConnectionManager().shutdown();
        }

    }

    /**
     * @param url
     * @return whether url is an ssl (https) url or not.
     */
    public static boolean isServiceSSL(String url) {
        if (url == null) {
            return false;
        }

        HttpGet httpGet = new HttpGet(url);
        String scheme = httpGet.getURI().getScheme();
        return scheme != null && scheme.equals("https");
    }

    private static KeyStore createKeyStore(Map<String, Object> options) throws Exception {
        String certContent = (String) options.get(SERVER_CERTIFICATE);
        return KeyStoreHelper.createKeyStoreWithCustomCertificate("odata", certContent);
    }

    public static SSLContext createSSLContext(Map<String, Object> options) throws Exception {
        String serviceUrl = (String) options.get(SERVICE_URI);
        if (! isServiceSSL(serviceUrl)) {
            return null;
        }

        KeyStore keyStore = createKeyStore(options);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
        return sslContext;
    }

    private static CredentialsProvider createCredentialProvider(Map<String, Object> options) {
        String basicUser = (String) options.get(BASIC_USER_NAME);
        String basicPswd = (String) options.get(BASIC_PASSWORD);

        if (ObjectHelper.isEmpty(basicUser)) {
            return null;
        }

        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(basicUser, basicPswd));
        return credentialsProvider;
    }

    /**
     * Creates a new {@link HttpClientBuilder} for the given options.
     *
     * @param options
     *
     * @return the new http client builder
     *
     * @throws Exception
     */
    public static HttpClientBuilder createHttpClientBuilder(Map<String, Object> options)
                                                       throws Exception {
        HttpClientBuilder builder = HttpClientBuilder.create();

        SSLContext sslContext = createSSLContext(options);
        if (sslContext != null) {
            // Skip verifying hostname
            HostnameVerifier allowAllHosts = new NoopHostnameVerifier();
            builder.setSSLContext(sslContext);
            builder.setSSLHostnameVerifier(allowAllHosts);
        }

        CredentialsProvider credentialsProvider = createCredentialProvider(options);
        if (credentialsProvider != null) {
            builder.setDefaultCredentialsProvider(credentialsProvider).build();
        }

        return builder;
    }

    /**
     * Creates a new {@link HttpClientBuilder} for the given options.
     *
     * @param options
     *
     * @return the new http client builder
     *
     * @throws Exception
     */
    public static HttpAsyncClientBuilder createHttpAsyncClientBuilder(Map<String, Object> options)
                                                       throws Exception {
        HttpAsyncClientBuilder builder = HttpAsyncClientBuilder.create();

        SSLContext sslContext = createSSLContext(options);
        if (sslContext != null) {
            // Skip verifying hostname
            HostnameVerifier allowAllHosts = new NoopHostnameVerifier();
            builder.setSSLContext(sslContext);
            builder.setSSLHostnameVerifier(allowAllHosts);
        }

        CredentialsProvider credentialsProvider = createCredentialProvider(options);
        if (credentialsProvider != null) {
            builder.setDefaultCredentialsProvider(credentialsProvider).build();
        }

        return builder;
    }

    /**
     * Creates a new {@link CloseableHttpClient} for the given options.
     * @param options
     *
     * @return the new http(s) client
     *
     * @throws Exception
     */
    public static CloseableHttpClient createHttpClient(Map<String, Object> options)
                                                                   throws Exception {
        return createHttpClientBuilder(options).build();
    }

    public static HttpClientFactory newHttpFactory(Map<String, Object> options) {
        return new ODataHttpClientFactory(options);
    }

    /**
     * Remove the slashes at the end of the given string
     *
     * @param path
     * @return string sans slashes
     */
    public static String removeEndSlashes(String path) {
        return Optional.ofNullable(path)
            .filter(str -> str.length() != 0)
            .map(str -> StringUtils.stripEnd(path, FORWARD_SLASH))
            .orElse(path);
    }
}
