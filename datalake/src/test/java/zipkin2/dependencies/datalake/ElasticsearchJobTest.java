/*
 * Copyright 2016-2019 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.dependencies.datalake;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import zipkin2.dependencies.datalake.ElasticsearchJob;

import org.assertj.core.api.Assertions;
import org.elasticsearch.hadoop.EsHadoopException;
import org.junit.Rule;
import org.junit.Test;

import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START;
import static okhttp3.tls.internal.TlsUtil.localhost;
import static org.apache.commons.net.util.Base64.encodeBase64String;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;

public class ElasticsearchJobTest {
  @Rule public MockWebServer es = new MockWebServer();

  @Test
  public void buildHttps() {
    ElasticsearchJob job =
        ElasticsearchJob.builder().hosts("https://foobar").build();
    assertThat(job.conf.get("es.nodes")).isEqualTo("foobar:443");
    assertThat(job.conf.get("es.net.ssl")).isEqualTo("true");
  }

  @Test
  public void buildAuth() {
    ElasticsearchJob job =
        ElasticsearchJob.builder().username("foo").password("bar").build();
    assertThat(job.conf.get("es.net.http.auth.user")).isEqualTo("foo");
    assertThat(job.conf.get("es.net.http.auth.pass")).isEqualTo("bar");
  }

  @Test
  public void authWorks() throws Exception {
    es.enqueue(new MockResponse()); // let the HEAD request pass, so we can trap the header value
    es.enqueue(new MockResponse().setSocketPolicy(DISCONNECT_AT_START)); // kill the job
    ElasticsearchJob job =
        ElasticsearchJob.builder()
            .username("foo")
            .password("bar")
            .hosts(es.url("").toString())
            .build();

    try {
      job.run();
      failBecauseExceptionWasNotThrown(EsHadoopException.class);
    } catch (EsHadoopException e) {
      // this is ok as we aren't trying to emulate the whole server
    }
    assertThat(es.takeRequest().getHeader("Authorization"))
        .isEqualTo("Basic " + encodeBase64String("foo:bar" .getBytes("UTF-8")).trim());
  }

  @Test
  public void authWorksWithSsl() throws Exception {
    es.useHttps(localhost().sslSocketFactory(), false);

    es.enqueue(new MockResponse()); // let the HEAD request pass, so we can trap the header value
    es.enqueue(new MockResponse().setSocketPolicy(DISCONNECT_AT_START)); // kill the job

    ElasticsearchJob.Builder builder =
        ElasticsearchJob.builder()
            .username("foo")
            .password("bar")
            .hosts(es.url("").toString());

    // temporarily hack-in self-signed until https://github.com/openzipkin/zipkin/issues/1683
    builder.sparkProperties.put("es.net.ssl.cert.allow.self.signed", "true");

    ElasticsearchJob job = builder.build();

    try {
      job.run();
      failBecauseExceptionWasNotThrown(EsHadoopException.class);
    } catch (EsHadoopException e) {
      // this is ok as we aren't trying to emulate the whole server
    }
    assertThat(es.takeRequest().getHeader("Authorization"))
        .isEqualTo("Basic " + encodeBase64String("foo:bar" .getBytes("UTF-8")).trim());
  }

  @Test
  public void parseHosts_default() {
    Assertions.assertThat(ElasticsearchJob.parseHosts("1.1.1.1")).isEqualTo("1.1.1.1");
  }

  @Test
  public void parseHosts_commaDelimits() {
    Assertions.assertThat(ElasticsearchJob.parseHosts("1.1.1.1:9200,2.2.2.2:9200")).isEqualTo("1.1.1.1:9200,2.2.2.2:9200");
  }

  @Test
  public void parseHosts_http_defaultPort() {
    Assertions.assertThat(ElasticsearchJob.parseHosts("http://1.1.1.1")).isEqualTo("1.1.1.1:80");
  }

  @Test
  public void parseHosts_https_defaultPort() {
    Assertions.assertThat(ElasticsearchJob.parseHosts("https://1.1.1.1")).isEqualTo("1.1.1.1:443");
  }

  @Test
  public void javaSslOptsRedirected() {
    System.setProperty("javax.net.ssl.keyStore", "keystore.jks");
    System.setProperty("javax.net.ssl.keyStorePassword", "superSecret");
    System.setProperty("javax.net.ssl.trustStore", "truststore.jks");
    System.setProperty("javax.net.ssl.trustStorePassword", "secretSuper");

    ElasticsearchJob job = ElasticsearchJob.builder().build();

    assertThat(job.conf.get("es.net.ssl.keystore.location")).isEqualTo("file:keystore.jks");
    assertThat(job.conf.get("es.net.ssl.keystore.pass")).isEqualTo("superSecret");
    assertThat(job.conf.get("es.net.ssl.truststore.location")).isEqualTo("file:truststore.jks");
    assertThat(job.conf.get("es.net.ssl.truststore.pass")).isEqualTo("secretSuper");

    System.clearProperty("javax.net.ssl.keyStore");
    System.clearProperty("javax.net.ssl.keyStorePassword");
    System.clearProperty("javax.net.ssl.trustStore");
    System.clearProperty("javax.net.ssl.trustStorePassword");
  }
}
