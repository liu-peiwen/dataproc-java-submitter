/*
 * -\-\-
 * Dataproc Java Submitter
 * --
 * Copyright (C) 2016 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */
package com.spotify.dataproc.submitter;

import com.spotify.dataproc.DataprocHadoopRunner;
import com.spotify.dataproc.Job;

import org.jhades.model.ClasspathEntry;
import org.jhades.service.ClasspathScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TODO: document.
 */
public class DataprocLambdaRunner {

  private static final Logger LOG = LoggerFactory.getLogger(DataprocLambdaRunner.class);

  private final ClasspathScanner scanner = new ClasspathScanner();
  private final DataprocHadoopRunner dataproc;

  private DataprocLambdaRunner(DataprocHadoopRunner dataproc) {
    this.dataproc = Objects.requireNonNull(dataproc);
  }

  public static DataprocLambdaRunner forDataproc(DataprocHadoopRunner dataproc) {
    return new DataprocLambdaRunner(dataproc);
  }

  public <T> void runOnCluster(Fn<T> fn) throws IOException {
    LOG.info("Preparing JVM context switch...");

    final Path continuationFilePath = ContinuationEntryPoint.serializeContinuation(fn);

    final List<String> jarPaths = localClasspath().stream()
        .map(entry -> Paths.get(URI.create(entry.getUrl())).toAbsolutePath().toString())
        .collect(Collectors.toList());
    final List<String> jars =
        Stream.concat(
            jarPaths.stream(),
            Stream.of(continuationFilePath.toAbsolutePath().toString()))
            .collect(Collectors.toList());

    LOG.debug("Jars:");
    jars.forEach(LOG::debug);

    final Job job = Job.builder()
        .setMainClass(ContinuationEntryPoint.class.getCanonicalName())
        .setShippedJars(jars.toArray(new String[jars.size()]))
        .createJob();

    dataproc.submit(job);
  }

  private Set<ClasspathEntry> localClasspath() {
    final String name = DataprocLambdaRunner.class.getClassLoader().getClass().getName();

    return scanner.findAllClasspathEntries().stream()
        .filter(entry -> name.equals(entry.getClassLoaderName()))
        .flatMap(DataprocLambdaRunner::jarFileEntriesWithExpandedManifest)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static Stream<ClasspathEntry> jarFileEntriesWithExpandedManifest(ClasspathEntry entry) {
    if (!entry.isJar() || !entry.getUrl().startsWith("file:")) {
      return Stream.empty();
    }

    if (entry.findManifestClasspathEntries().isEmpty()) {
      return Stream.of(entry);
    } else {
      final URI uri = URI.create(entry.getUrl());
      Path path = Paths.get(uri).getParent();
      return Stream.concat(
          Stream.of(entry),
          entry.findManifestClasspathEntries().stream()
              .map(normalizerUsingPath(path)));
    }
  }

  private static UnaryOperator<ClasspathEntry> normalizerUsingPath(Path base) {
    return entry -> new ClasspathEntry(
        entry.getClassLoader(),
        base.resolve(entry.getUrl()).toUri().toString());
  }
}
