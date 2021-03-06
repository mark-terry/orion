/*
 * Copyright 2018 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package net.consensys.orion.http;

import static io.vertx.core.Vertx.vertx;
import static net.consensys.orion.TestUtils.configureJDKTrustStore;
import static net.consensys.orion.TestUtils.generateAndLoadConfiguration;
import static net.consensys.orion.TestUtils.writeClientConnectionServerCertToConfig;
import static net.consensys.orion.TestUtils.writeServerCertToConfig;
import static org.apache.tuweni.net.tls.TLS.certificateHexFingerprint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.consensys.orion.cmd.Orion;
import net.consensys.orion.config.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.CompletionException;
import javax.net.ssl.SSLHandshakeException;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.net.SelfSignedCertificate;
import org.apache.tuweni.concurrent.AsyncResult;
import org.apache.tuweni.concurrent.CompletableAsyncResult;
import org.apache.tuweni.junit.TempDirectory;
import org.apache.tuweni.junit.TempDirectoryExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TempDirectoryExtension.class)
class WhiteListSecurityTest {

  private final static Vertx vertx = vertx();
  private static HttpClient httpClient;
  private static HttpClient httpClientWithUnregisteredCert;
  private static HttpClient httpClientWithImproperCertificate;
  private static HttpClient anotherExampleComClient;
  private static Orion orion;
  private static Config config;
  private static final String TRUST_MODE = "whitelist";

  @BeforeAll
  static void setUp(@TempDirectory final Path tempDir) throws Exception {
    final SelfSignedCertificate serverCertificate = SelfSignedCertificate.create("localhost");
    config = generateAndLoadConfiguration(tempDir, writer -> {
      writer.write("tlsservertrust='" + TRUST_MODE + "'\n");
      writer.write("clientconnectiontls='strict'\n");
      writer.write("clientconnectiontlsservertrust='" + TRUST_MODE + "'\n");
      writeServerCertToConfig(writer, serverCertificate);
      writeClientConnectionServerCertToConfig(writer, serverCertificate);
    });

    configureJDKTrustStore(serverCertificate, tempDir);

    final SelfSignedCertificate clientCertificate = SelfSignedCertificate.create("example.com");
    final String fingerprint = certificateHexFingerprint(Paths.get(clientCertificate.keyCertOptions().getCertPath()));

    Files.write(config.tlsKnownClients(), Arrays.asList("#First line", "example.com " + fingerprint));
    Files.write(config.clientConnectionTlsKnownClients(), Arrays.asList("#First line", "example.com " + fingerprint));
    httpClient = vertx
        .createHttpClient(new HttpClientOptions().setSsl(true).setKeyCertOptions(clientCertificate.keyCertOptions()));

    final SelfSignedCertificate fooCertificate = SelfSignedCertificate.create("foo.bar.baz");
    httpClientWithUnregisteredCert =
        vertx.createHttpClient(new HttpClientOptions().setSsl(true).setKeyCertOptions(fooCertificate.keyCertOptions()));

    final SelfSignedCertificate noCNCert = SelfSignedCertificate.create("");
    httpClientWithImproperCertificate =
        vertx.createHttpClient(new HttpClientOptions().setSsl(true).setKeyCertOptions(noCNCert.keyCertOptions()));

    final SelfSignedCertificate anotherExampleDotComCert = SelfSignedCertificate.create("example.com");
    anotherExampleComClient = vertx.createHttpClient(
        new HttpClientOptions().setSsl(true).setKeyCertOptions(anotherExampleDotComCert.keyCertOptions()));

    orion = new Orion(vertx);
    orion.run(config, false);
  }

  @AfterAll
  static void tearDown() {
    orion.stop();
    vertx.close();
  }

  @Test
  void whitelistedClientSuccessfullyExecuteUpcheck() throws Exception {
    assertUpcheckIsSuccessfulOnPortWhenClientIsWhiteListed(config.nodePort());
    assertUpcheckIsSuccessfulOnPortWhenClientIsWhiteListed(config.clientPort());
  }

  void assertUpcheckIsSuccessfulOnPortWhenClientIsWhiteListed(final int port) throws Exception {
    for (int i = 0; i < 5; i++) {
      final HttpClientRequest req = httpClient.get(port, "localhost", "/upcheck");
      final CompletableAsyncResult<HttpClientResponse> result = AsyncResult.incomplete();
      req.handler(result::complete).exceptionHandler(result::completeExceptionally).end();
      final HttpClientResponse resp = result.get();
      assertEquals(200, resp.statusCode());
    }
  }

  @Test
  void testSameHostnameUnknownCertificate() {
    assertClientWithUnknownCertificateFromKnownHostnameFails(config.nodePort());
    assertClientWithUnknownCertificateFromKnownHostnameFails(config.clientPort());
  }

  void assertClientWithUnknownCertificateFromKnownHostnameFails(final int port) {
    final HttpClientRequest req = anotherExampleComClient.get(port, "localhost", "/upcheck");
    final CompletableAsyncResult<HttpClientResponse> result = AsyncResult.incomplete();
    req.handler(result::complete).exceptionHandler(result::completeExceptionally).end();
    final CompletionException e = assertThrows(CompletionException.class, result::get);
    assertEquals("Received fatal alert: certificate_unknown", e.getCause().getCause().getMessage());
  }

  @Test
  void testUpCheckOnNodePortWithUnregisteredClientCert() throws Exception {
    assertUpCheckWithUnregisteredClientCertFails(config.nodePort());
    assertUpCheckWithUnregisteredClientCertFails(config.clientPort());
  }

  void assertUpCheckWithUnregisteredClientCertFails(final int port) {
    final HttpClientRequest req = httpClientWithUnregisteredCert.get(port, "localhost", "/upcheck");
    final CompletableAsyncResult<HttpClientResponse> result = AsyncResult.incomplete();
    req.handler(result::complete).exceptionHandler(result::completeExceptionally).end();
    final CompletionException e = assertThrows(CompletionException.class, result::get);
    assertEquals("Received fatal alert: certificate_unknown", e.getCause().getCause().getMessage());
  }

  @Test
  void testUpCheckOnNodePortWithInvalidClientCert() {
    assertUpcheckOnPortWithInvalidClientCertFails(config.nodePort());
    assertUpcheckOnPortWithInvalidClientCertFails(config.clientPort());
  }

  void assertUpcheckOnPortWithInvalidClientCertFails(int port) {
    final HttpClientRequest req = httpClientWithImproperCertificate.get(port, "localhost", "/upcheck");
    final CompletableAsyncResult<HttpClientResponse> result = AsyncResult.incomplete();
    req.handler(result::complete).exceptionHandler(result::completeExceptionally).end();
    final CompletionException e = assertThrows(CompletionException.class, result::get);
    assertEquals("Received fatal alert: certificate_unknown", e.getCause().getCause().getMessage());
  }

  @Test
  void testWithoutSSLConfiguration() {
    final CompletableAsyncResult<HttpClientResponse> result = AsyncResult.incomplete();
    final HttpClient insecureClient = vertx.createHttpClient(new HttpClientOptions().setSsl(true));

    {
      final HttpClientRequest req = insecureClient.get(config.nodePort(), "localhost", "/upcheck");
      req.handler(result::complete).exceptionHandler(result::completeExceptionally).end();
      final CompletionException e = assertThrows(CompletionException.class, result::get);
      assertTrue(e.getCause() instanceof SSLHandshakeException);
    }

    {
      final HttpClientRequest req = insecureClient.get(config.clientPort(), "localhost", "/upcheck");
      req.handler(result::complete).exceptionHandler(result::completeExceptionally).end();
      final CompletionException e = assertThrows(CompletionException.class, result::get);
      assertTrue(e.getCause() instanceof SSLHandshakeException);
    }

  }
}
