/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.dataflow.worker.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test the memory monitor will block threads when the server is in a (faked) GC thrashing state.
 */
@RunWith(JUnit4.class)
public class MemoryMonitorTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  static class FakeGCStatsProvider implements MemoryMonitor.GCStatsProvider {
    AtomicBoolean inGCThrashingState = new AtomicBoolean(false);
    long lastCallTimestamp = System.currentTimeMillis();
    long lastGCResult = 0;

    @Override
    public long totalGCTimeMilliseconds() {
      if (inGCThrashingState.get()) {
        long now = System.currentTimeMillis();
        lastGCResult += now - lastCallTimestamp;
        lastCallTimestamp = now;
      }
      return lastGCResult;
    }
  }

  private FakeGCStatsProvider provider;
  private File localDumpFolder;

  @Before
  public void setup() throws IOException {
    provider = new FakeGCStatsProvider();
    localDumpFolder = tempFolder.newFolder();
  }

  @Test(timeout = 1000)
  public void detectGCThrashing() throws InterruptedException {
    // Update every 10ms, never shutdown VM.
    MemoryMonitor monitor =
        MemoryMonitor.forTest(
            provider,
            10,
            0,
            false,
            50.0,
            null,
            localDumpFolder,
            "test-worker",
            null,
            Clock.systemUTC());
    Thread thread = new Thread(monitor);
    thread.start();
    monitor.waitForRunning();
    monitor.waitForResources("Test1");
    provider.inGCThrashingState.set(true);
    monitor.waitForThrashingState(true);
    final Semaphore s = new Semaphore(0);
    new Thread(
            () -> {
              monitor.waitForResources("Test2");
              s.release();
            })
        .start();
    assertFalse(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    provider.inGCThrashingState.set(false);
    monitor.waitForThrashingState(false);
    assertTrue(s.tryAcquire(100, TimeUnit.MILLISECONDS));
    monitor.waitForResources("Test3");
    monitor.stop();
    thread.join();
  }

  @Test
  public void heapDumpOnce() throws Exception {
    MemoryMonitor monitor =
        MemoryMonitor.forTest(
            provider,
            10,
            0,
            false,
            50.0,
            null,
            localDumpFolder,
            "test-worker",
            null,
            Clock.systemUTC());
    File dump1 = monitor.dumpHeap();
    assertNotNull(dump1);
    assertTrue(dump1.exists());
    assertThat(dump1.getParentFile(), Matchers.equalTo(localDumpFolder));
  }

  @Test
  public void heapDumpTwice() throws Exception {
    File folder = tempFolder.newFolder();

    File dump1 = MemoryMonitor.dumpHeap(folder);
    assertNotNull(dump1);
    assertTrue(dump1.exists());
    assertThat(dump1.getParentFile(), Matchers.equalTo(folder));

    File dump2 = MemoryMonitor.dumpHeap(folder);
    assertNotNull(dump2);
    assertTrue(dump2.exists());
    assertThat(dump2.getParentFile(), Matchers.equalTo(folder));
  }

  @Test
  public void uploadToGcs() throws Exception {
    File remoteFolder = tempFolder.newFolder();
    MemoryMonitor monitor =
        MemoryMonitor.forTest(
            provider,
            10,
            0,
            true,
            50.0,
            remoteFolder.getPath(),
            localDumpFolder,
            "test-worker",
            null,
            Clock.systemUTC());

    // Force the monitor to generate a local heap dump
    monitor.dumpHeap();

    // Try to upload the heap dump
    assertTrue(monitor.tryUploadHeapDumpIfItExists());

    File[] files = remoteFolder.listFiles();
    assertThat(files, Matchers.arrayWithSize(1));
    assertThat(files[0].getAbsolutePath(), Matchers.containsString("heap_dump"));
    assertThat(files[0].getAbsolutePath(), Matchers.containsString("hprof"));
  }

  @Test
  public void uploadJfrProfilesOnThrashing() throws Exception {
    File remoteFolder = tempFolder.newFolder();
    MemoryMonitor monitor =
        MemoryMonitor.forTest(
            provider,
            10,
            0,
            true,
            50.0,
            remoteFolder.getPath(),
            localDumpFolder,
            "test-worker",
            Duration.ofMillis(100),
            Clock.fixed(Instant.parse("2022-01-01T01:02:03.000Z"), ZoneOffset.UTC));

    monitor.runJfrProfileOnHeapThrashing().get();

    File[] files = remoteFolder.listFiles();
    assertThat(files, Matchers.arrayWithSize(1));
    assertThat(files[0].getName(), Matchers.startsWith("20220101010203-jfr_profile-test-worker-"));
    assertThat(files[0].getAbsolutePath(), Matchers.endsWith(".jfr"));
  }

  @Test
  public void uploadToGcsDisabled() throws Exception {
    MemoryMonitor monitor =
        MemoryMonitor.forTest(
            provider,
            10,
            0,
            true,
            50.0,
            null,
            localDumpFolder,
            "test-worker",
            null,
            Clock.systemUTC());

    // Force the monitor to generate a local heap dump
    monitor.dumpHeap();

    // Try to upload the heap dump
    assertFalse(monitor.tryUploadHeapDumpIfItExists());
  }

  @Test
  public void disableMemoryMonitor() throws Exception {
    // Update every 10ms, never shutdown VM.
    MemoryMonitor monitor =
        MemoryMonitor.forTest(
            provider,
            10,
            0,
            false,
            50.0,
            null,
            localDumpFolder,
            "test-worker",
            null,
            Clock.systemUTC());
    Thread thread = new Thread(monitor);
    thread.start();

    MemoryMonitor disabledMonitor =
        MemoryMonitor.forTest(
            provider,
            10,
            0,
            true,
            100.0,
            null,
            localDumpFolder,
            "test-worker",
            null,
            Clock.systemUTC());
    Thread disabledMonitorThread = new Thread(disabledMonitor);
    disabledMonitorThread.start();

    // Monitor thread should stop quickly after starting. Wait 10 seconds, and check that monitor
    // thread is not alive.
    disabledMonitorThread.join(10000);
    assertFalse(disabledMonitorThread.isAlive());

    // Enabled monitor thread should still be running.
    assertTrue(thread.isAlive());
    monitor.stop();
    thread.join();
  }
}
