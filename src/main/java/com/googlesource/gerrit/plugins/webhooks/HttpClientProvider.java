// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.webhooks;

import com.google.inject.Inject;
import com.google.inject.Provider;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.http.HttpResponse;
import org.apache.http.client.ServiceUnavailableRetryStrategy;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Provides an HTTP client with SSL capabilities. */
class HttpClientProvider implements Provider<CloseableHttpClient> {
  private static final Logger log = LoggerFactory.getLogger(HttpClientProvider.class);
  private static final int CONNECTIONS_PER_ROUTE = 100;
  // Up to 2 target instances with the max number of connections per host:
  private static final int MAX_CONNECTIONS = 2 * CONNECTIONS_PER_ROUTE;
  private static final int ERROR_CODES = 500;
  private static final int MAX_CONNECTION_INACTIVITY = 10000;

  private final Configuration cfg;
  private final SSLConnectionSocketFactory sslSocketFactory;

  @Inject
  HttpClientProvider(Configuration cfg) {
    this.cfg = cfg;
    this.sslSocketFactory = buildSslSocketFactory();
  }

  @Override
  public CloseableHttpClient get() {
    return HttpClients.custom()
        .setSSLSocketFactory(sslSocketFactory)
        .setConnectionManager(customConnectionManager())
        .setDefaultRequestConfig(customRequestConfig())
        .setServiceUnavailableRetryStrategy(customServiceUnavailRetryStrategy())
        .build();
  }

  private RequestConfig customRequestConfig() {
    return RequestConfig.custom()
        .setConnectTimeout(cfg.getConnectionTimeout())
        .setSocketTimeout(cfg.getSocketTimeout())
        .setConnectionRequestTimeout(cfg.getConnectionTimeout())
        .build();
  }

  private ServiceUnavailableRetryStrategy customServiceUnavailRetryStrategy() {
    return new ServiceUnavailableRetryStrategy() {
      @Override
      public boolean retryRequest(HttpResponse response, int executionCount, HttpContext context) {
        if (executionCount > cfg.getMaxTries()) {
          return false;
        }
        if (response.getStatusLine().getStatusCode() >= ERROR_CODES) {
          logRetry(response.getStatusLine().getReasonPhrase(), context);
          return true;
        }
        return false;
      }

      @Override
      public long getRetryInterval() {
        return cfg.getRetryInterval();
      }
    };
  }

  private void logRetry(String cause, HttpContext context) {
    if (log.isDebugEnabled()) {
      log.debug(
          "Retrying request caused by '"
              + cause
              + "', request: '"
              + context.getAttribute("http.request")
              + "'");
    }
  }

  private HttpClientConnectionManager customConnectionManager() {
    Registry<ConnectionSocketFactory> socketFactoryRegistry =
        RegistryBuilder.<ConnectionSocketFactory>create()
            .register("https", sslSocketFactory)
            .register("http", PlainConnectionSocketFactory.INSTANCE)
            .build();
    PoolingHttpClientConnectionManager connManager =
        new PoolingHttpClientConnectionManager(socketFactoryRegistry);
    connManager.setDefaultMaxPerRoute(CONNECTIONS_PER_ROUTE);
    connManager.setMaxTotal(MAX_CONNECTIONS);
    connManager.setValidateAfterInactivity(MAX_CONNECTION_INACTIVITY);
    return connManager;
  }

  private SSLConnectionSocketFactory buildSslSocketFactory() {
    return new SSLConnectionSocketFactory(buildSslContext(), NoopHostnameVerifier.INSTANCE);
  }

  private SSLContext buildSslContext() {
    try {
      TrustManager[] trustAllCerts = new TrustManager[] {new DummyX509TrustManager()};
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trustAllCerts, null);
      return context;
    } catch (KeyManagementException | NoSuchAlgorithmException e) {
      log.warn("Error building SSLContext object", e);
      return null;
    }
  }

  private static class DummyX509TrustManager implements X509TrustManager {
    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }

    @Override
    public void checkClientTrusted(X509Certificate[] certs, String authType) {
      // no check
    }

    @Override
    public void checkServerTrusted(X509Certificate[] certs, String authType) {
      // no check
    }
  }
}