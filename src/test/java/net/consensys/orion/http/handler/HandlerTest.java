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
package net.consensys.orion.http.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.consensys.orion.cmd.Orion;
import net.consensys.orion.config.Config;
import net.consensys.orion.enclave.Enclave;
import net.consensys.orion.enclave.EncryptedPayload;
import net.consensys.orion.enclave.PrivacyGroupPayload;
import net.consensys.orion.enclave.QueryPrivacyGroupPayload;
import net.consensys.orion.exception.OrionErrorCode;
import net.consensys.orion.helpers.StubEnclave;
import net.consensys.orion.http.server.HttpContentType;
import net.consensys.orion.network.PersistentNetworkNodes;
import net.consensys.orion.payload.DistributePayloadManager;
import net.consensys.orion.storage.EncryptedPayloadStorage;
import net.consensys.orion.storage.PrivacyGroupStorage;
import net.consensys.orion.storage.QueryPrivacyGroupStorage;
import net.consensys.orion.storage.Sha512_256StorageKeyBuilder;
import net.consensys.orion.storage.Storage;
import net.consensys.orion.storage.StorageKeyBuilder;
import net.consensys.orion.storage.StorageUtils;
import net.consensys.orion.utils.Serializer;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.concurrent.AsyncCompletion;
import org.apache.tuweni.concurrent.CompletableAsyncCompletion;
import org.apache.tuweni.crypto.sodium.Box;
import org.apache.tuweni.junit.TempDirectory;
import org.apache.tuweni.junit.TempDirectoryExtension;
import org.apache.tuweni.kv.KeyValueStore;
import org.apache.tuweni.kv.MapKeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TempDirectoryExtension.class)
public abstract class HandlerTest {

  static final String RETRIEVE_PRIVACY_GROUP = "/retrievePrivacyGroup";
  static final String DELETE_PRIVACY_GROUP = "/deletePrivacyGroup";
  static final String PUSH_PRIVACY_GROUP = "/pushPrivacyGroup";
  static final String CREATE_PRIVACY_GROUP = "/createPrivacyGroup";

  // http client
  protected final OkHttpClient httpClient = new OkHttpClient();
  protected String nodeBaseUrl;
  protected String clientBaseUrl;

  // these are re-built between tests
  protected PersistentNetworkNodes networkNodes;
  protected Config config;
  protected Enclave enclave;

  protected Box.PublicKey senderKey;

  private Vertx vertx;
  private Integer nodeHTTPServerPort;
  private HttpServer nodeHttpServer;
  private Integer clientHTTPServerPort;
  private HttpServer clientHttpServer;

  private KeyValueStore<Bytes, Bytes> storage;
  protected Storage<EncryptedPayload> payloadStorage;
  protected Storage<QueryPrivacyGroupPayload> queryPrivacyGroupStorage;
  protected Storage<PrivacyGroupPayload> privacyGroupStorage;
  protected DistributePayloadManager distributePayloadManager;

  @BeforeEach
  void setUp(@TempDirectory final Path tempDir) throws Exception {
    // Setup ports for Public and Private API Servers
    setupPorts();

    // Initialize the base HTTP url in two forms: String and OkHttp's HttpUrl object to allow for simpler composition
    // of complex URLs with path parameters, query strings, etc.
    final HttpUrl nodeHTTP = new HttpUrl.Builder().scheme("http").host("localhost").port(nodeHTTPServerPort).build();
    nodeBaseUrl = nodeHTTP.toString();

    // orion dependencies, reset them all between tests
    config = Config.load("tls='off'\nworkdir=\"" + tempDir + "\"" + "\nnodeurl=\"http://10.62.12.12:98876\"");
    storage = MapKeyValueStore.open();
    senderKey = Box.PublicKey.fromBytes(new byte[32]);
    networkNodes =
        new PersistentNetworkNodes(config, new Box.PublicKey[] {senderKey}, StorageUtils.convertToPubKeyStore(storage));
    enclave = buildEnclave(tempDir);

    // create our vertx object
    vertx = Vertx.vertx();
    final StorageKeyBuilder keyBuilder = new Sha512_256StorageKeyBuilder();
    payloadStorage = new EncryptedPayloadStorage(storage, keyBuilder);
    queryPrivacyGroupStorage = new QueryPrivacyGroupStorage(storage, enclave);
    privacyGroupStorage = new PrivacyGroupStorage(storage, enclave);
    distributePayloadManager = new DistributePayloadManager(
        vertx,
        config,
        enclave,
        payloadStorage,
        privacyGroupStorage,
        queryPrivacyGroupStorage,
        networkNodes);
    final Router publicRouter = Router.router(vertx);
    final Router privateRouter = Router.router(vertx);
    Orion.configureRoutes(
        vertx,
        networkNodes,
        enclave,
        payloadStorage,
        privacyGroupStorage,
        queryPrivacyGroupStorage,
        distributePayloadManager,
        publicRouter,
        privateRouter,
        config);

    setupNodeServer(publicRouter);
    setupClientServer(privateRouter);
  }

  private void setupNodeServer(final Router router) throws Exception {
    final HttpServerOptions publicServerOptions = new HttpServerOptions();
    publicServerOptions.setPort(nodeHTTPServerPort);

    final CompletableAsyncCompletion completion = AsyncCompletion.incomplete();
    nodeHttpServer = vertx.createHttpServer(publicServerOptions).requestHandler(router::accept).listen(result -> {
      if (result.succeeded()) {
        completion.complete();
      } else {
        completion.completeExceptionally(result.cause());
      }
    });
    completion.join();
  }

  private void setupClientServer(final Router router) throws Exception {
    final HttpUrl clientHTTP =
        new HttpUrl.Builder().scheme("http").host("localhost").port(clientHTTPServerPort).build();
    clientBaseUrl = clientHTTP.toString();

    final HttpServerOptions privateServerOptions = new HttpServerOptions();
    privateServerOptions.setPort(clientHTTPServerPort);

    final CompletableAsyncCompletion completion = AsyncCompletion.incomplete();
    clientHttpServer = vertx.createHttpServer(privateServerOptions).requestHandler(router::accept).listen(result -> {
      if (result.succeeded()) {
        completion.complete();
      } else {
        completion.completeExceptionally(result.cause());
      }
    });
    completion.join();
  }

  private void setupPorts() throws IOException {
    // get a free httpServerPort for Public API
    final ServerSocket socket1 = new ServerSocket(0);
    nodeHTTPServerPort = socket1.getLocalPort();

    // get a free httpServerPort for Private API
    final ServerSocket socket2 = new ServerSocket(0);
    clientHTTPServerPort = socket2.getLocalPort();

    socket1.close();
    socket2.close();
  }

  @AfterEach
  void tearDown() throws Exception {
    nodeHttpServer.close();
    clientHttpServer.close();
    storage.close();
    vertx.close();
  }

  protected Enclave buildEnclave(final Path tempDir) {
    return new StubEnclave();
  }

  Request buildPrivateAPIRequest(final String path, final HttpContentType contentType, final Object payload) {
    return buildPostRequest(clientBaseUrl, path, contentType, Serializer.serialize(contentType, payload));
  }

  Request buildPublicAPIRequest(final String path, final HttpContentType contentType, final Object payload) {
    return buildPostRequest(nodeBaseUrl, path, contentType, Serializer.serialize(contentType, payload));
  }

  private Request buildPostRequest(
      final String baseurl,
      String path,
      final HttpContentType contentType,
      final byte[] payload) {
    final RequestBody body = RequestBody.create(MediaType.parse(contentType.httpHeaderValue), payload);

    if (path.startsWith("/")) {
      path = path.substring(1, path.length());
    }

    return new Request.Builder().post(body).url(baseurl + path).build();
  }

  void assertError(final OrionErrorCode expected, final Response actual) throws IOException {
    assertEquals(String.format("{\"error\":\"%s\"}", expected.code()), actual.body().string());
  }
}
