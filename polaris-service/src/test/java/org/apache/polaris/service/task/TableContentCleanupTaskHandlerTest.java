/*
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
 */
package org.apache.polaris.service.task;

import static org.assertj.core.api.Assertions.assertThatPredicate;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StatisticsFile;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.io.FileIO;
import org.apache.polaris.core.PolarisCallContext;
import org.apache.polaris.core.PolarisDefaultDiagServiceImpl;
import org.apache.polaris.core.context.CallContext;
import org.apache.polaris.core.context.RealmContext;
import org.apache.polaris.core.entity.AsyncTaskType;
import org.apache.polaris.core.entity.TaskEntity;
import org.apache.polaris.service.persistence.InMemoryPolarisMetaStoreManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TableContentCleanupTaskHandlerTest {
  private InMemoryPolarisMetaStoreManagerFactory metaStoreManagerFactory;
  private RealmContext realmContext;
  private static final Logger LOGGER =
      LoggerFactory.getLogger(TableContentCleanupTaskHandlerTest.class);

  @BeforeEach
  void setUp() {
    metaStoreManagerFactory = new InMemoryPolarisMetaStoreManagerFactory();
    realmContext = () -> "realmName";
  }

  @Test
  public void testCleanup() throws IOException {
    PolarisCallContext polarisCallContext =
        new PolarisCallContext(
            metaStoreManagerFactory.getOrCreateSessionSupplier(realmContext).get(),
            new PolarisDefaultDiagServiceImpl());
    try (CallContext callCtx = CallContext.of(realmContext, polarisCallContext)) {
      CallContext.setCurrentContext(callCtx);
      FileIO fileIO =
          new InMemoryFileIO() {
            @Override
            public void close() {
              // no-op
            }
          };
      TableIdentifier tableIdentifier =
          TableIdentifier.of(Namespace.of("db1", "schema1"), "table1");
      ExecutorService executorService = Executors.newCachedThreadPool();
      TableContentCleanupTaskHandler handler =
          new TableContentCleanupTaskHandler((task) -> fileIO, executorService);

      long snapshotId1 = 100L;
      ManifestFile manifestFile1 =
          TaskTestUtils.manifestFile(
              fileIO, "manifest1.avro", snapshotId1, "dataFile1.parquet", "dataFile2.parquet");
      ManifestFile manifestFile2 =
          TaskTestUtils.manifestFile(
              fileIO, "manifest2.avro", snapshotId1, "dataFile3.parquet", "dataFile4.parquet");
      Snapshot snapshot =
          TaskTestUtils.newSnapshot(
              fileIO, "manifestList.avro", 1, snapshotId1, 99L, manifestFile1, manifestFile2);
      StatisticsFile statisticsFile1 =
          TaskTestUtils.writeStatsFile(
              snapshot.snapshotId(),
              snapshot.sequenceNumber(),
              "/metadata/" + UUID.randomUUID() + ".stats",
              fileIO);
      String firstMetadataFile = "v1-295495059.metadata.json";
      TableMetadata firstMetadata =
          TaskTestUtils.writeTableMetadata(
              fileIO, firstMetadataFile, List.of(statisticsFile1), snapshot);
      assertThat(TaskUtils.exists(firstMetadataFile, fileIO)).isTrue();

      ManifestFile manifestFile3 =
          TaskTestUtils.manifestFile(
              fileIO, "manifest3.avro", snapshot.snapshotId() + 1, "dataFile5.parquet");
      Snapshot snapshot2 =
          TaskTestUtils.newSnapshot(
              fileIO,
              "manifestList2.avro",
              snapshot.sequenceNumber() + 1,
              snapshot.snapshotId() + 1,
              snapshot.snapshotId(),
              manifestFile1,
              manifestFile3); // exclude manifest2 from the new snapshot
      StatisticsFile statisticsFile2 =
          TaskTestUtils.writeStatsFile(
              snapshot2.snapshotId(),
              snapshot2.sequenceNumber(),
              "/metadata/" + UUID.randomUUID() + ".stats",
              fileIO);
      String secondMetadataFile = "v1-295495060.metadata.json";
      TableMetadata secondMetadata =
          TaskTestUtils.writeTableMetadata(
              fileIO,
              secondMetadataFile,
              firstMetadata,
              firstMetadataFile,
              List.of(statisticsFile2),
              snapshot2);
      assertThat(TaskUtils.exists(firstMetadataFile, fileIO)).isTrue();
      assertThat(TaskUtils.exists(secondMetadataFile, fileIO)).isTrue();

      List<String> cleanupFiles =
          Stream.concat(
                  secondMetadata.previousFiles().stream()
                      .map(TableMetadata.MetadataLogEntry::file)
                      .filter(file -> TaskUtils.exists(file, fileIO)),
                  secondMetadata.statisticsFiles().stream()
                      .map(StatisticsFile::path)
                      .filter(file -> TaskUtils.exists(file, fileIO)))
              .toList();

      TaskEntity task =
          new TaskEntity.Builder()
              .withTaskType(AsyncTaskType.TABLE_CONTENT_CLEANUP)
              .withData(
                  new TableContentCleanupTaskHandler.TableContentCleanupTask(
                      tableIdentifier, cleanupFiles))
              .setName(UUID.randomUUID().toString())
              .build();

      assertThatPredicate(handler::canHandleTask).accepts(task);
      assertThatPredicate(handler::handleTask).accepts(task);

      assertThatPredicate((String file) -> TaskUtils.exists(file, fileIO))
          .rejects(firstMetadataFile);
      assertThatPredicate((String file) -> TaskUtils.exists(file, fileIO))
          .rejects(statisticsFile1.path());
      assertThatPredicate((String file) -> TaskUtils.exists(file, fileIO))
          .rejects(statisticsFile2.path());
    }
  }

  @Test
  public void testCleanupIfFileNotExist() throws IOException {
    PolarisCallContext polarisCallContext =
        new PolarisCallContext(
            metaStoreManagerFactory.getOrCreateSessionSupplier(realmContext).get(),
            new PolarisDefaultDiagServiceImpl());
    try (CallContext callCtx = CallContext.of(realmContext, polarisCallContext)) {
      CallContext.setCurrentContext(callCtx);
      FileIO fileIO = new InMemoryFileIO();
      TableIdentifier tableIdentifier =
          TableIdentifier.of(Namespace.of("db1", "schema1"), "table1");
      TableContentCleanupTaskHandler handler =
          new TableContentCleanupTaskHandler((task) -> fileIO, Executors.newCachedThreadPool());
      long snapshotId = 100L;
      ManifestFile manifestFile =
          TaskTestUtils.manifestFile(
              fileIO, "manifest1.avro", snapshotId, "dataFile1.parquet", "dataFile2.parquet");
      TestSnapshot snapshot =
          TaskTestUtils.newSnapshot(fileIO, "manifestList.avro", 1, snapshotId, 99L, manifestFile);
      String metadataFile = "v1-49494949.metadata.json";
      StatisticsFile statisticsFile =
          TaskTestUtils.writeStatsFile(
              snapshot.snapshotId(),
              snapshot.sequenceNumber(),
              "/metadata/" + UUID.randomUUID() + ".stats",
              fileIO);
      TaskTestUtils.writeTableMetadata(fileIO, metadataFile, List.of(statisticsFile), snapshot);

      fileIO.deleteFile(statisticsFile.path());
      assertThat(TaskUtils.exists(statisticsFile.path(), fileIO)).isFalse();

      TaskEntity task =
          new TaskEntity.Builder()
              .withTaskType(AsyncTaskType.TABLE_CONTENT_CLEANUP)
              .withData(
                  new TableContentCleanupTaskHandler.TableContentCleanupTask(
                      tableIdentifier, List.of(statisticsFile.path())))
              .setName(UUID.randomUUID().toString())
              .build();
      assertThatPredicate(handler::canHandleTask).accepts(task);
      assertThatPredicate(handler::handleTask).accepts(task);
    }
  }

  @Test
  public void testCleanupWithRetries() throws IOException {
    PolarisCallContext polarisCallContext =
        new PolarisCallContext(
            metaStoreManagerFactory.getOrCreateSessionSupplier(realmContext).get(),
            new PolarisDefaultDiagServiceImpl());
    try (CallContext callCtx = CallContext.of(realmContext, polarisCallContext)) {
      CallContext.setCurrentContext(callCtx);
      Map<String, AtomicInteger> retryCounter = new HashMap<>();
      FileIO fileIO =
          new InMemoryFileIO() {
            @Override
            public void close() {
              // no-op
            }

            @Override
            public void deleteFile(String location) {
              int attempts =
                  retryCounter
                      .computeIfAbsent(location, k -> new AtomicInteger(0))
                      .incrementAndGet();
              LOGGER.info("Attempt {} to delete file: {}", attempts, location);
              if (attempts < 3) {
                LOGGER.warn("Simulating failure for retry {}", attempts);
                throw new RuntimeException("Simulating failure to test retries");
              } else {
                // Succeed on the third attempt
                LOGGER.info("Actually delete file: {}", location);
                super.deleteFile(location);
              }
            }
          };
      TableIdentifier tableIdentifier =
          TableIdentifier.of(Namespace.of("db1", "schema1"), "table1");
      TableContentCleanupTaskHandler handler =
          new TableContentCleanupTaskHandler((task) -> fileIO, Executors.newCachedThreadPool());
      long snapshotId = 100L;
      ManifestFile manifestFile =
          TaskTestUtils.manifestFile(
              fileIO, "manifest1.avro", snapshotId, "dataFile1.parquet", "dataFile2.parquet");
      TestSnapshot snapshot =
          TaskTestUtils.newSnapshot(fileIO, "manifestList.avro", 1, snapshotId, 99L, manifestFile);
      String metadataFile = "v1-49494949.metadata.json";
      StatisticsFile statisticsFile =
          TaskTestUtils.writeStatsFile(
              snapshot.snapshotId(),
              snapshot.sequenceNumber(),
              "/metadata/" + UUID.randomUUID() + ".stats",
              fileIO);
      TaskTestUtils.writeTableMetadata(fileIO, metadataFile, List.of(statisticsFile), snapshot);
      assertThat(TaskUtils.exists(statisticsFile.path(), fileIO)).isTrue();

      TaskEntity task =
          new TaskEntity.Builder()
              .withTaskType(AsyncTaskType.TABLE_CONTENT_CLEANUP)
              .withData(
                  new TableContentCleanupTaskHandler.TableContentCleanupTask(
                      tableIdentifier, List.of(statisticsFile.path())))
              .setName(UUID.randomUUID().toString())
              .build();

      CompletableFuture<Void> future =
          CompletableFuture.runAsync(
              () -> {
                assertThatPredicate(handler::canHandleTask).accepts(task);
                handler.handleTask(task); // this will schedule the batch deletion
              });

      // Wait for all async tasks to finish
      future.join();

      // Check if the file was successfully deleted after retries
      assertThat(TaskUtils.exists(statisticsFile.path(), fileIO)).isFalse();

      // Ensure that retries happened as expected
      assertThat(retryCounter.containsKey(statisticsFile.path())).isTrue();
      assertThat(retryCounter.get(statisticsFile.path()).get()).isEqualTo(3);
    }
  }
}
