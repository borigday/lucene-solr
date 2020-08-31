/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.client.solrj.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.ParWork;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.QoSParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.ObjectReleaseTracker;
import org.slf4j.MDC;

/**
 * SolrJ client class to communicate with SolrCloud using Http2SolrClient.
 * Instances of this class communicate with Zookeeper to discover
 * Solr endpoints for SolrCloud collections, and then use the
 * {@link LBHttp2SolrClient} to issue requests.
 *
 * This class assumes the id field for your documents is called
 * 'id' - if this is not the case, you must set the right name
 * with {@link #setIdField(String)}.
 *
 * @lucene.experimental
 * @since solr 8.0
 */
@SuppressWarnings("serial")
public class CloudHttp2SolrClient  extends BaseCloudSolrClient {

  private final ClusterStateProvider stateProvider;
  private final LBHttp2SolrClient lbClient;
  private Http2SolrClient myClient;
  private final boolean clientIsInternal;

  /**
   * Create a new client object that connects to Zookeeper and is always aware
   * of the SolrCloud state. If there is a fully redundant Zookeeper quorum and
   * SolrCloud has enough replicas for every shard in a collection, there is no
   * single point of failure. Updates will be sent to shard leaders by default.
   *
   * @param builder a {@link Http2SolrClient.Builder} with the options used to create the client.
   */
  protected CloudHttp2SolrClient(Builder builder) {
    super(builder.shardLeadersOnly, builder.parallelUpdates, builder.directUpdatesToLeadersOnly);
    this.clientIsInternal = builder.httpClient == null;
    if (builder.stateProvider == null) {
      if ((builder.zkHosts != null && !builder.zkHosts.isEmpty()) && builder.solrUrls != null) {
        cleanupAfterInitError();
        throw new IllegalArgumentException("Both zkHost(s) & solrUrl(s) have been specified. Only specify one.");
      }
      if (builder.zkHosts != null && !builder.zkHosts.isEmpty()) {
        final String zkHostString;
        try {
          zkHostString = ZkClientClusterStateProvider.buildZkHostString(builder.zkHosts, builder.zkChroot);
        } catch (IllegalArgumentException iae) {
          cleanupAfterInitError();
          throw iae;
        }
        this.stateProvider = new ZkClientClusterStateProvider(zkHostString, 40000, 15000);
      } else if (builder.solrUrls != null && !builder.solrUrls.isEmpty()) {
        try {
          this.stateProvider = new Http2ClusterStateProvider(builder.solrUrls, builder.httpClient);
        } catch (Exception e) {
          cleanupAfterInitError();
          ParWork.propegateInterrupt(e);
          throw new RuntimeException("Couldn't initialize a HttpClusterStateProvider (is/are the "
              + "Solr server(s), "  + builder.solrUrls + ", down?)", e);
        }
      } else {
        cleanupAfterInitError();
        throw new IllegalArgumentException("Both zkHosts and solrUrl cannot be null.");
      }
    } else {
      this.stateProvider = builder.stateProvider;
    }
    this.myClient = (builder.httpClient == null) ? new Http2SolrClient.Builder().withHeaders(builder.headers).build() : builder.httpClient;
    this.lbClient = new LBHttp2SolrClient(myClient);
    ObjectReleaseTracker.track(this);
  }

  // called to clean-up objects created by super if there are errors during initialization
  private void cleanupAfterInitError() {
    try {
      super.close(); // super created a ThreadPool ^
    } catch (IOException ignore) {
      // no-op: not much we can do here
    }
  }

  @Override
  protected void doParallelUpdate(Map<String,? extends LBSolrClient.Req> routes,
      NamedList<Throwable> exceptions, NamedList<NamedList> shardResponses) {
    Map<String,Throwable> tsExceptions = new ConcurrentHashMap<>();
    Map<String,NamedList> tsResponses = new ConcurrentHashMap<>();
    for (final Map.Entry<String, ? extends LBSolrClient.Req> entry : routes.entrySet()) {
      final String url = entry.getKey();
      final LBSolrClient.Req lbRequest = entry.getValue();
      lbRequest.request.setBasePath(url);
      try {
        MDC.put("CloudSolrClient.url", url);
        try {
          myClient.request(lbRequest.request, null, new Http2SolrClient.OnComplete() {

            @Override
            public void onSuccess(NamedList result) {
              tsResponses.put(url, result);
            }

            @Override
            public void onFailure(Throwable t) {
              tsExceptions.put(url, t);
            }});
        } catch (IOException e) {
          tsExceptions.put(url, e);
        } catch (SolrServerException e) {
          tsExceptions.put(url, e);
        }

      } finally {
        MDC.remove("CloudSolrClient.url");
      }
    }

    // wait until the async requests we fired off above are done
    myClient.waitForOutstandingRequests();

    exceptions.addAll(tsExceptions);
    shardResponses.addAll(tsResponses);
    if (exceptions.size() > 0) {
      Throwable firstException = exceptions.getVal(0);
      if(firstException instanceof SolrException) {
        SolrException e = (SolrException) firstException;
        throw getRouteException(SolrException.ErrorCode.getErrorCode(e.code()),
            exceptions, routes);
      } else {
        throw getRouteException(SolrException.ErrorCode.SERVER_ERROR,
            exceptions, routes);
      }
    }
  }

  @Override
  public void close() throws IOException {
    try (ParWork closer = new ParWork(this, true, true)) {

      closer.collect(stateProvider);
      closer.collect(lbClient);
      if (clientIsInternal && myClient!=null) {

        closer.collect(myClient);
      }
    }
    super.close();
    assert ObjectReleaseTracker.release(this);
  }

  public LBHttp2SolrClient getLbClient() {
    return lbClient;
  }

  @Override
  public ClusterStateProvider getClusterStateProvider() {
    return stateProvider;
  }

  public Http2SolrClient getHttpClient() {
    return myClient;
  }

  @Override
  protected boolean wasCommError(Throwable rootCause) {
    return false;
  }

  /**
   * Constructs {@link CloudHttp2SolrClient} instances from provided configuration.
   */
  public static class Builder {
    protected Collection<String> zkHosts = new ArrayList<>();
    protected List<String> solrUrls;
    protected String zkChroot;
    protected Http2SolrClient httpClient;
    protected boolean shardLeadersOnly = true;
    protected boolean directUpdatesToLeadersOnly = false;
    protected Map<String,String> headers = new ConcurrentHashMap<>();
    protected boolean parallelUpdates = true; // always
    protected ClusterStateProvider stateProvider;

    /**
     * Provide a series of Solr URLs to be used when configuring {@link CloudHttp2SolrClient} instances.
     * The solr client will use these urls to understand the cluster topology, which solr nodes are active etc.
     *
     * Provided Solr URLs are expected to point to the root Solr path ("http://hostname:8983/solr"); they should not
     * include any collections, cores, or other path components.
     *
     * Usage example:
     *
     * <pre>
     *   final List&lt;String&gt; solrBaseUrls = new ArrayList&lt;String&gt;();
     *   solrBaseUrls.add("http://solr1:8983/solr"); solrBaseUrls.add("http://solr2:8983/solr"); solrBaseUrls.add("http://solr3:8983/solr");
     *   final SolrClient client = new CloudHttp2SolrClient.Builder(solrBaseUrls).build();
     * </pre>
     */
    public Builder(List<String> solrUrls) {
      this.solrUrls = solrUrls;
    }

    /**
     * Provide a series of ZK hosts which will be used when configuring {@link CloudHttp2SolrClient} instances.
     *
     * Usage example when Solr stores data at the ZooKeeper root ('/'):
     *
     * <pre>
     *   final List&lt;String&gt; zkServers = new ArrayList&lt;String&gt;();
     *   zkServers.add("zookeeper1:2181"); zkServers.add("zookeeper2:2181"); zkServers.add("zookeeper3:2181");
     *   final SolrClient client = new CloudHttp2SolrClient.Builder(zkServers, Optional.empty()).build();
     * </pre>
     *
     * Usage example when Solr data is stored in a ZooKeeper chroot:
     *
     *  <pre>
     *    final List&lt;String&gt; zkServers = new ArrayList&lt;String&gt;();
     *    zkServers.add("zookeeper1:2181"); zkServers.add("zookeeper2:2181"); zkServers.add("zookeeper3:2181");
     *    final SolrClient client = new CloudHttp2SolrClient.Builder(zkServers, Optional.of("/solr")).build();
     *  </pre>
     *
     * @param zkHosts a List of at least one ZooKeeper host and port (e.g. "zookeeper1:2181")
     * @param zkChroot the path to the root ZooKeeper node containing Solr data.  Provide {@code java.util.Optional.empty()} if no ZK chroot is used.
     */
    public Builder(List<String> zkHosts, Optional<String> zkChroot) {
      this.zkHosts = zkHosts;
      if (zkChroot.isPresent()) this.zkChroot = zkChroot.get();
    }

    public Builder(ZkStateReader zkStateReader) {
      ZkClientClusterStateProvider stateProvider = new ZkClientClusterStateProvider(zkStateReader, false);
      this.stateProvider = stateProvider;
    }

    /**
     * Tells {@link CloudHttp2SolrClient.Builder} that created clients should send direct updates to shard leaders only.
     *
     * UpdateRequests whose leaders cannot be found will "fail fast" on the client side with a {@link SolrException}
     */
    public Builder sendDirectUpdatesToShardLeadersOnly() {
      directUpdatesToLeadersOnly = true;
      return this;
    }

    //do not set this from an external client
    public Builder markInternalRequest() {
      this.headers.put(QoSParams.REQUEST_SOURCE, QoSParams.INTERNAL);
      return this;
    }

    /**
     * Tells {@link CloudHttp2SolrClient.Builder} that created clients can send updates to any shard replica (shard leaders and non-leaders).
     *
     * Shard leaders are still preferred, but the created clients will fallback to using other replicas if a leader
     * cannot be found.
     */
    public Builder sendDirectUpdatesToAnyShardReplica() {
      directUpdatesToLeadersOnly = false;
      return this;
    }

    public Builder withHttpClient(Http2SolrClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    /**
     * Create a {@link CloudHttp2SolrClient} based on the provided configuration.
     */
    public CloudHttp2SolrClient build() {
      return new CloudHttp2SolrClient(this);
    }

  }
}
