/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/
package io.openlineage.client.transports.gcplineage;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.api.gax.rpc.FixedHeaderProvider;
import com.google.api.gax.rpc.HeaderProvider;
import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.datacatalog.lineage.v1.LineageSettings;
import com.google.cloud.datacatalog.lineage.v1.ProcessOpenLineageRunEventRequest;
import com.google.cloud.datacatalog.lineage.v1.ProcessOpenLineageRunEventResponse;
import com.google.cloud.datalineage.producerclient.helpers.OpenLineageHelper;
import com.google.cloud.datalineage.producerclient.v1.AsyncLineageClient;
import com.google.cloud.datalineage.producerclient.v1.AsyncLineageProducerClient;
import com.google.cloud.datalineage.producerclient.v1.AsyncLineageProducerClientSettings;
import com.google.cloud.datalineage.producerclient.v1.SyncLineageClient;
import com.google.cloud.datalineage.producerclient.v1.SyncLineageProducerClient;
import com.google.cloud.datalineage.producerclient.v1.SyncLineageProducerClientSettings;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Struct;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.openlineage.client.OpenLineage;
import io.openlineage.client.OpenLineageClientException;
import io.openlineage.client.OpenLineageClientUtils;
import io.openlineage.client.transports.Transport;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GcpLineageTransport extends Transport {

  private final ProducerClientWrapper producerClientWrapper;

  public GcpLineageTransport(@NonNull GcpLineageTransportConfig config) throws IOException {
    this(new ProducerClientWrapper(config));
  }

  protected GcpLineageTransport(@NonNull ProducerClientWrapper client) throws IOException {
    this.producerClientWrapper = client;
  }

  @Override
  public void emit(OpenLineage.@NonNull RunEvent runEvent) {
    producerClientWrapper.emitEvent(runEvent);
  }

  @Override
  public void emit(OpenLineage.@NonNull DatasetEvent datasetEvent) {
    producerClientWrapper.emitEvent(datasetEvent);
  }

  @Override
  public void emit(OpenLineage.@NonNull JobEvent jobEvent) {
    producerClientWrapper.emitEvent(jobEvent);
  }

  @Override
  public void close() {
    producerClientWrapper.close();
  }

  @Getter
  @RequiredArgsConstructor
  private enum Integration {
    SPARK("io.openlineage.spark.agent.EventEmitter"),
    FLINK("io.openlineage.flink.client.EventEmitter"),
    HIVE("io.openlineage.hive.client.EventEmitter");

    private final String emitterClassName;
    private static final Map<String, Integration> CLASS_TO_INTEGRATION;

    static {
      Map<String, Integration> map = new HashMap<>();
      for (Integration integration : values()) {
        map.put(integration.emitterClassName, integration);
      }
      CLASS_TO_INTEGRATION = Collections.unmodifiableMap(map);
    }

    public String getVersion() {
      try {
        Properties properties = new Properties();
        Class<?> emitterClass = Class.forName(emitterClassName);
        InputStream is = emitterClass.getResourceAsStream("version.properties");
        properties.load(is);
        return properties.getProperty("version");
      } catch (IOException | ClassNotFoundException exception) {
        return "main";
      }
    }

    public static Optional<Integration> fromClassName(String className) {
      return Optional.ofNullable(CLASS_TO_INTEGRATION.get(className));
    }
  }

  static class ProducerClientWrapper implements Closeable {
    private final SyncLineageClient syncLineageClient;
    private final AsyncLineageClient asyncLineageClient;
    private final String parent;

    protected ProducerClientWrapper(GcpLineageTransportConfig config) throws IOException {
      LineageSettings settings;
      if (GcpLineageTransportConfig.Mode.sync == config.getMode()) {
        settings = createSyncSettings(config);
        syncLineageClient =
            SyncLineageProducerClient.create((SyncLineageProducerClientSettings) settings);
        asyncLineageClient = null;
      } else {
        syncLineageClient = null;
        settings = createAsyncSettings(config);
        asyncLineageClient =
            AsyncLineageProducerClient.create((AsyncLineageProducerClientSettings) settings);
      }
      this.parent = getParent(config, settings);
    }

    protected ProducerClientWrapper(GcpLineageTransportConfig config, SyncLineageClient client)
        throws IOException {
      this.syncLineageClient = client;
      this.parent = getParent(config, createAsyncSettings(config));
      this.asyncLineageClient = null;
    }

    protected ProducerClientWrapper(GcpLineageTransportConfig config, AsyncLineageClient client)
        throws IOException {
      this.asyncLineageClient = client;
      this.parent = getParent(config, createSyncSettings(config));
      this.syncLineageClient = null;
    }

    public <T extends OpenLineage.BaseEvent> void emitEvent(T event) {
      final String eventJson = OpenLineageClientUtils.toJson(event);
      try {
        Struct openLineageStruct = OpenLineageHelper.jsonToStruct(eventJson);
        ProcessOpenLineageRunEventRequest request =
            ProcessOpenLineageRunEventRequest.newBuilder()
                .setParent(parent)
                .setOpenLineage(openLineageStruct)
                .build();
        if (syncLineageClient != null) {
          syncLineageClient.processOpenLineageRunEvent(request);
        } else {
          handleRequestAsync(request);
        }
      } catch (Exception e) {
        log.error("Failed to emit lineage event: {}", eventJson, e);
        throw new OpenLineageClientException(e);
      }
    }

    private void handleRequestAsync(ProcessOpenLineageRunEventRequest request) {
      ApiFuture<ProcessOpenLineageRunEventResponse> future =
          asyncLineageClient.processOpenLineageRunEvent(request);
      ApiFutureCallback<ProcessOpenLineageRunEventResponse> callback =
          new ApiFutureCallback<ProcessOpenLineageRunEventResponse>() {
            @Override
            public void onFailure(Throwable t) {
              log.error("Failed to collect a lineage event: {}", OpenLineageClientUtils.toJson(request.getOpenLineage()), t);
            }

            @Override
            public void onSuccess(ProcessOpenLineageRunEventResponse result) {
              log.debug("Event sent successfully: {}", OpenLineageClientUtils.toJson(request.getOpenLineage()));
            }
          };
      ApiFutures.addCallback(future, callback, MoreExecutors.directExecutor());
    }

    private String getParent(GcpLineageTransportConfig config, LineageSettings settings)
        throws IOException {
      return String.format(
          "projects/%s/locations/%s",
          getProjectId(config, settings),
          config.getLocation() != null ? config.getLocation() : "us");
    }

    private static SyncLineageProducerClientSettings createSyncSettings(
        GcpLineageTransportConfig config) throws IOException {
      SyncLineageProducerClientSettings.Builder builder =
          SyncLineageProducerClientSettings.newBuilder();
      return createSettings(config, builder).build();
    }

    private static AsyncLineageProducerClientSettings createAsyncSettings(
        GcpLineageTransportConfig config) throws IOException {
      AsyncLineageProducerClientSettings.Builder builder =
          AsyncLineageProducerClientSettings.newBuilder();
      return createSettings(config, builder).build();
    }

    private static <T extends LineageSettings.Builder> T createSettings(
        GcpLineageTransportConfig config, T builder) throws IOException {
      if (config.getEndpoint() != null) {
        builder.setEndpoint(config.getEndpoint());
      }
      if (config.getProjectId() != null) {
        builder.setQuotaProjectId(config.getProjectId());
      }
      if (config.getCredentialsFile() != null) {
        File file = new File(config.getCredentialsFile());
        try (InputStream credentialsStream = Files.newInputStream(file.toPath())) {
          GoogleCredentials googleCredentials = GoogleCredentials.fromStream(credentialsStream);
          builder.setCredentialsProvider(FixedCredentialsProvider.create(googleCredentials));
        }
      }
      Optional<Integration> integration = getIntegration();
      // set up user agent
      StringBuilder userAgent = new StringBuilder();
      userAgent.append("openlineage");
      integration.ifPresent(
          agent ->
              userAgent.append(
                  String.format("-%s/%s", agent.name().toLowerCase(), agent.getVersion())));
      HeaderProvider headerProvider =
          FixedHeaderProvider.create("user-agent", userAgent.toString());
      builder.setHeaderProvider(headerProvider);
      // set up billing metadata
      InstantiatingGrpcChannelProvider.Builder transportProvider =
          builder.getTransportChannelProvider() instanceof InstantiatingGrpcChannelProvider
              ? ((InstantiatingGrpcChannelProvider) builder.getTransportChannelProvider())
                  .toBuilder()
              : null;
      if (integration.isPresent() && transportProvider != null) {
        Metadata metadata = new Metadata();
        Metadata.Key<byte[]> headerKey =
            Metadata.Key.of("x-goog-ext-512598505-bin", Metadata.BINARY_BYTE_MARSHALLER);
        byte[] headerValue =
            ("\n\u0008DATAPROC\u0012\u0004" + integration.get().name().toUpperCase())
                .getBytes(StandardCharsets.UTF_8);
        metadata.put(headerKey, headerValue);
        transportProvider.setChannelConfigurator(
            managedChannelBuilder ->
                managedChannelBuilder.intercept(
                    MetadataUtils.newAttachHeadersInterceptor(metadata)));
        builder.setTransportChannelProvider(transportProvider.build());
      }
      return builder;
    }

    private static String getProjectId(GcpLineageTransportConfig config, LineageSettings settings)
        throws IOException {
      if (config.getProjectId() != null) {
        return config.getProjectId();
      }
      Credentials credentials = settings.getCredentialsProvider().getCredentials();
      if (credentials instanceof ServiceAccountCredentials) {
        ServiceAccountCredentials serviceAccountCredentials =
            (ServiceAccountCredentials) credentials;
        return serviceAccountCredentials.getProjectId();
      }
      if (credentials instanceof GoogleCredentials) {
        GoogleCredentials googleCredentials = (GoogleCredentials) credentials;
        return googleCredentials.getQuotaProjectId();
      }
      return settings.getQuotaProjectId();
    }

    /**
     * Checks if the current method was called by any of the EventEmitter classes and returns the
     * corresponding integration name.
     *
     * @return Optional containing the platform name ("spark", "flink", or "hive"), or empty if not
     *     called by a known EventEmitter
     */
    public static Optional<Integration> getIntegration() {
      return Arrays.stream(Thread.currentThread().getStackTrace())
          .skip(2)
          .map(StackTraceElement::getClassName)
          .map(Integration::fromClassName)
          .filter(Optional::isPresent)
          .map(Optional::get)
          .findFirst();
    }

    @Override
    public void close() {
      try {
        if (syncLineageClient != null) {
          syncLineageClient.close();
        }
        if (asyncLineageClient != null) {
          asyncLineageClient.close();
        }
      } catch (Exception e) {
        throw new OpenLineageClientException("Exception while closing the resource", e);
      }
    }
  }
}
