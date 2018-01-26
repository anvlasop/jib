/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.crepecake.builder;

import com.google.cloud.tools.crepecake.Timer;
import com.google.cloud.tools.crepecake.cache.Cache;
import com.google.cloud.tools.crepecake.cache.CacheChecker;
import com.google.cloud.tools.crepecake.cache.CacheWriter;
import com.google.cloud.tools.crepecake.cache.CachedLayer;
import com.google.cloud.tools.crepecake.http.Authorization;
import com.google.cloud.tools.crepecake.image.DescriptorDigest;
import com.google.cloud.tools.crepecake.image.DuplicateLayerException;
import com.google.cloud.tools.crepecake.image.LayerPropertyNotFoundException;
import com.google.cloud.tools.crepecake.registry.RegistryClient;
import com.google.cloud.tools.crepecake.registry.RegistryException;
import com.google.common.io.CountingOutputStream;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** Pulls and caches a single base image layer. */
class PullAndCacheBaseImageLayerStep implements Callable<CachedLayer> {

  private static final String DESCRIPTION = "Pulling base image layer %s";

  private final BuildConfiguration buildConfiguration;
  private final Cache cache;
  private final DescriptorDigest layerDigest;
  private final Future<Authorization> pullAuthorizationFuture;

  PullAndCacheBaseImageLayerStep(
      BuildConfiguration buildConfiguration,
      Cache cache,
      DescriptorDigest layerDigest,
      Future<Authorization> pullAuthorizationFuture) {
    this.buildConfiguration = buildConfiguration;
    this.cache = cache;
    this.layerDigest = layerDigest;
    this.pullAuthorizationFuture = pullAuthorizationFuture;
  }

  /** Depends on {@code pullAuthorizationFuture}. */
  @Override
  public CachedLayer call()
      throws IOException, RegistryException, LayerPropertyNotFoundException,
          DuplicateLayerException, ExecutionException, InterruptedException {
    try (Timer ignored =
        new Timer(buildConfiguration.getBuildLogger(), String.format(DESCRIPTION, layerDigest))) {
      RegistryClient registryClient =
          new RegistryClient(
              pullAuthorizationFuture.get(),
              buildConfiguration.getBaseImageServerUrl(),
              buildConfiguration.getBaseImageName());

      // Checks if the layer already exists in the cache.
      CachedLayer cachedLayer = new CacheChecker(cache).getLayer(layerDigest);
      if (cachedLayer != null) {
        return cachedLayer;
      }

      CacheWriter cacheWriter = new CacheWriter(cache);
      CountingOutputStream layerOutputStream = cacheWriter.getLayerOutputStream(layerDigest);
      registryClient.pullBlob(layerDigest, layerOutputStream);
      return cacheWriter.getCachedLayer(layerDigest, layerOutputStream);
    }
  }
}