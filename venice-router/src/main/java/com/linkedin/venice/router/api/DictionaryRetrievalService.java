package com.linkedin.venice.router.api;

import com.linkedin.security.ssl.access.control.SSLEngineComponentFactory;
import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.compression.CompressorFactory;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.meta.Instance;
import com.linkedin.venice.meta.OnlineInstanceFinder;
import com.linkedin.venice.meta.QueryAction;
import com.linkedin.venice.meta.ReadOnlyStoreRepository;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.StoreDataChangedListener;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.meta.VersionStatus;
import com.linkedin.venice.router.VeniceRouterConfig;
import com.linkedin.venice.router.httpclient.HttpClientUtils;
import com.linkedin.venice.service.AbstractVeniceService;
import com.linkedin.venice.utils.concurrent.VeniceConcurrentHashMap;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.log4j.Logger;

import static org.apache.http.HttpStatus.*;


/**
 * DictionaryRetrievalService runs in a producer-consumer pattern. A thread is created which waits for items to be put
 * in a shared BlockingQueue.
 * There are 2 producers for the store versions to download dictionaries for.
 *  1) Store metadata changed ZK listener.
 *  2) Each failed dictionary fetch request is retried infinitely (till the version is retired).
 *
 * At Router startup, the dictionaries are pre-fetched for currently active versions that require a dictionary. This
 * process is fail-fast and will prevent router start up if any dictionary fetch request fails.
 *
 * When a dictionary is downloaded for a version, it's corresponding version specific compressor is initialized and is
 * maintained by CompressorFactory.
 */
public class DictionaryRetrievalService extends AbstractVeniceService {
  private static final Logger logger = Logger.getLogger(DictionaryRetrievalService.class);
  private static final int DEFAULT_DICTIONARY_DOWNLOAD_INTERNAL_IN_MS = 100;
  private final OnlineInstanceFinder onlineInstanceFinder;
  private final Optional<SSLEngineComponentFactory> sslFactory;
  private final ReadOnlyStoreRepository metadataRepository;
  private final Thread dictionaryRetrieverThread;
  private final ScheduledExecutorService executor;
  private final CloseableHttpAsyncClient httpClient;

  // Shared queue between producer and consumer where topics whose dictionaries have to be downloaded are put in.
  private BlockingQueue<String> dictionaryDownloadCandidates = new LinkedBlockingQueue<>();

  // This map is used as a collection of futures that were created to download dictionaries for each store version.
  // The future's status also acts as an indicator of which dictionaries are currently active in memory.
  //  1) If an entry exists for the topic and it's state is "completed normally", it's dictionary has been downloaded.
  //  2) If an entry exists for the topic and it's state is "completed exceptionally", it's dictionary download failed.
  //     The exception handler of that future is responsible for any retries.
  //  3) If an entry exists for the topic and it's state is "running", the dictionary download is currently in progress.
  //  4) If an entry doesn't exists for the topic, the version is unknown since it could have been retired/new or it
  //     doesn't exist at all.
  private VeniceConcurrentHashMap<String, CompletableFuture<Void>> downloadingDictionaryFutures = new VeniceConcurrentHashMap<>();

  private final int dictionaryRetrievalTimeMs;

  // This is the ZK Listener which acts as the primary producer for finding versions which require a dictionary.
  private final StoreDataChangedListener storeChangeListener = new StoreDataChangedListener() {
    @Override
    public void handleStoreCreated(Store store) {
      dictionaryDownloadCandidates.addAll(store.getVersions().stream()
          .filter(version -> version.getCompressionStrategy() == CompressionStrategy.ZSTD_WITH_DICT && version.getStatus() == VersionStatus.ONLINE)
          .map(Version::kafkaTopicName)
          .collect(Collectors.toList()));
    }

    @Override
    public void handleStoreDeleted(Store store) {
      store.getVersions()
          .forEach(version -> handleVersionRetirement(version.kafkaTopicName(), "Store deleted."));
    }

    @Override
    public void handleStoreChanged(Store store) {
      List<Version> versions = store.getVersions();

      // For new versions, download dictionary.
      dictionaryDownloadCandidates.addAll(versions.stream()
          .filter(version -> version.getCompressionStrategy() == CompressionStrategy.ZSTD_WITH_DICT && version.getStatus() == VersionStatus.ONLINE)
          .filter(version -> !downloadingDictionaryFutures.containsKey(version.kafkaTopicName()))
          .map(Version::kafkaTopicName)
          .collect(Collectors.toList()));

      // For versions that went into non ONLINE states, delete dictionary.
      versions.stream()
          .filter(version -> version.getCompressionStrategy() == CompressionStrategy.ZSTD_WITH_DICT && version.getStatus() != VersionStatus.ONLINE)
          .forEach(version -> handleVersionRetirement(version.kafkaTopicName(), "Version status " + version.getStatus()));

      // For versions that have been retired, delete dictionary.
      downloadingDictionaryFutures.keySet().stream()
          .filter(topic -> Version.parseStoreFromKafkaTopicName(topic).equals(store.getName())) // Those topics which belong to the current store
          .filter(topic -> !store.getVersion(Version.parseVersionFromKafkaTopicName(topic)).isPresent()) // Those topics which are retired
          .forEach(topic -> handleVersionRetirement(topic, "Version retired"));
    }
  };

  /**
   *
   * @param onlineInstanceFinder OnlineInstanceFinder used to identify which storage node needs to be queried
   * @param routerConfig common router configuration
   * @param sslFactory if provided, the request will attempt to use ssl when fetching dictionary from the storage nodes
   */
  public DictionaryRetrievalService(OnlineInstanceFinder onlineInstanceFinder, VeniceRouterConfig routerConfig,
      Optional<SSLEngineComponentFactory> sslFactory, ReadOnlyStoreRepository metadataRepository){
    this.onlineInstanceFinder = onlineInstanceFinder;
    this.sslFactory = sslFactory;
    this.metadataRepository = metadataRepository;

    int maxConnectionsPerRoute = 2;
    int maxConnections = 100;

    // How long of a timeout we allow for a node to respond to a dictionary request
    dictionaryRetrievalTimeMs = routerConfig.getDictionaryRetrievalTimeMs();

    // How long of a timeout we allow for a node to respond to a dictionary request
    int numThreads = routerConfig.getRouterDictionaryProcessingThreads();

    /**
     * Cached dns resolver is empty because we would like to the unhealthy node to be reported correctly
     */
    httpClient = HttpClientUtils.getMinimalHttpClient(1,
        maxConnectionsPerRoute,
        maxConnections,
        dictionaryRetrievalTimeMs,
        dictionaryRetrievalTimeMs,
        sslFactory,
        Optional.empty(),
        Optional.empty()
    );

    // This thread is the consumer and it waits for an item to be put in the "dictionaryDownloadCandidates" queue.
    Runnable runnable = () -> {
      while(true) {
        String kafkaTopic = null;
        try {
          /**
           * In order to avoid retry storm; back off before querying server again.
           */
          kafkaTopic = dictionaryDownloadCandidates.take();
        } catch (InterruptedException e) {
          logger.warn("Thread was interrupted while waiting for a candidate to download dictionary.", e);
          break;
        }

        // If the dictionary has already been downloaded, skip it.
        if (CompressorFactory.versionSpecificCompressorExists(kafkaTopic)) {
          continue;
        }

        // If the dictionary is already being downloaded, skip it.
        if (downloadingDictionaryFutures.containsKey(kafkaTopic)) {
          continue;
        }

        downloadDictionaries(Arrays.asList(kafkaTopic));
      }
    };

    this.dictionaryRetrieverThread = new Thread(runnable);
    executor = Executors.newScheduledThreadPool(numThreads);
  }

  private CompletableFuture<byte[]> getDictionary(String store, int version){
    String kafkaTopic = Version.composeKafkaTopic(store, version);
    Instance instance = getOnlineInstance(kafkaTopic);

    if (instance == null) {
      return CompletableFuture.supplyAsync(() -> {
        throw new VeniceException("No online storage instance for resource: " + kafkaTopic);
      }, executor);
    }

    String instanceUrl = instance.getUrl(sslFactory.isPresent());

    logger.info("Downloading dictionary for resource: " + kafkaTopic + " from: " + instanceUrl);

    final HttpGet get = new HttpGet(instanceUrl + "/" + QueryAction.DICTIONARY.toString().toLowerCase() + "/" + store + "/" + version);
    Future<HttpResponse> responseFuture = httpClient.execute(get, null);

    return CompletableFuture.supplyAsync(() -> {
      VeniceException exception = null;
      try {
        byte[] dictionary = getDictionaryFromResponse(responseFuture.get(dictionaryRetrievalTimeMs, TimeUnit.MILLISECONDS), instanceUrl);
        if (dictionary == null) {
          exception = new VeniceException("Dictionary download for resource: " + kafkaTopic + " from: " + instanceUrl +
              " returned unexpected response.");
        } else {
          return dictionary;
        }
      } catch (InterruptedException e) {
        exception = new VeniceException("Dictionary download for resource: " + kafkaTopic + " from: " + instanceUrl + " was interrupted: " + e.getMessage());
      } catch (ExecutionException e) {
        exception = new VeniceException("ExecutionException encountered when downloading dictionary for resource: " + kafkaTopic + " from: " + instanceUrl + " : " + e.getMessage());
      } catch (TimeoutException e) {
        exception = new VeniceException("Dictionary download for resource: " + kafkaTopic + " from: " + instanceUrl + " timed out : " + e.getMessage());
      }

      logger.warn(exception.getMessage());

      throw exception;
    }, executor);
  }

  private byte[] getDictionaryFromResponse(HttpResponse response, String instanceUrl) {
    try {
      int code = response.getStatusLine().getStatusCode();
      if (code != SC_OK) {
        logger.warn("Dictionary fetch returns " + code + " for " + instanceUrl);
      } else {
        HttpEntity entity = response.getEntity();
        return IOUtils.toByteArray(entity.getContent());
      }
    } catch (IOException e) {
      logger.warn("Dictionary fetch HTTP response error : " + e.getMessage() + " for " + instanceUrl);
    }

    return null;
  }

  private Instance getOnlineInstance(String kafkaTopic) {
    try {
      int partitionCount = onlineInstanceFinder.getNumberOfPartitions(kafkaTopic);
      List<Instance> onlineInstances = new ArrayList<>();
      for (int p = 0; p < partitionCount; p++) {
        onlineInstances.addAll(onlineInstanceFinder.getReadyToServeInstances(kafkaTopic, p));
      }

      if (!onlineInstances.isEmpty()) {
        return onlineInstances.get((int) (Math.random() * onlineInstances.size()));
      }
    } catch (Exception e) {
      logger.warn("Exception caught in getting online instances for resource: " + kafkaTopic + " : " + e.getMessage());
    }

    return null;
  }

  /**
   * At Router start up, we want dictionaries for all active versions to be downloaded. This call is a blocking call and
   * fails fast if there is a failure in fetching the dictionary for any version.
   * @return false if the dictionary download timed out, true otherwise.
   */
  private boolean getAllDictionaries() {
    metadataRepository.refresh();
    List<String> dictionaryDownloadCandidates = metadataRepository.getAllStores().stream()
        .flatMap(store -> store.getVersions().stream())
        .filter(version -> version.getCompressionStrategy() == CompressionStrategy.ZSTD_WITH_DICT && version.getStatus() == VersionStatus.ONLINE)
        .filter(version -> !downloadingDictionaryFutures.containsKey(version.kafkaTopicName()))
        .map(Version::kafkaTopicName)
        .collect(Collectors.toList());

    return downloadDictionaries(dictionaryDownloadCandidates);
  }

  /**
   * This function downloads the dictionaries for the specified resources in a blocking manner.
   * @param dictionaryDownloadTopics A Collection of topics (representing store and version) to download the dictionaries for.
   * @return false if the dictionary download timed out, true otherwise.
   */
  private boolean downloadDictionaries(Collection<String> dictionaryDownloadTopics) {
    String storeTopics = String.join(",", dictionaryDownloadTopics);
    if (storeTopics.isEmpty()) {
      return true;
    }

    List<CompletableFuture<Void>> dictionaryDownloadFutures = dictionaryDownloadTopics.stream()
        .map(topic -> metadataRepository.getStore(Version.parseStoreFromKafkaTopicName(topic)).getVersion(Version.parseVersionFromKafkaTopicName(topic)))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(version -> fetchCompressionDictionary(version))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());

    CompletableFuture<ByteBuffer>[] dictionaryDownloadFutureArray = dictionaryDownloadFutures.toArray(new CompletableFuture[dictionaryDownloadFutures.size()]);

    try {
      logger.info("Beginning dictionary fetch for " + storeTopics);
      CompletableFuture.allOf(dictionaryDownloadFutureArray).get(dictionaryRetrievalTimeMs, TimeUnit.MILLISECONDS);
      logger.info("Dictionary fetch completed for " + storeTopics);
    } catch (Exception e) {
      logger.warn("Dictionary fetch failed. Store topics were: " + storeTopics + " : " + e.getMessage());
      return false;
    }
    return true;
  }

  private CompletableFuture<Void> fetchCompressionDictionary(Version version) {
    String kafkaTopic = version.kafkaTopicName();

    CompletableFuture<Void> dictionaryFuture;
    if (downloadingDictionaryFutures.containsKey(kafkaTopic)) {
      dictionaryFuture = downloadingDictionaryFutures.get(kafkaTopic);
    } else {
      dictionaryFuture = getDictionary(version.getStoreName(), version.getNumber()).handle((dictionary, exception) -> {
        if(exception != null) {
          if (exception instanceof InterruptedException) {
            logger.warn(exception.getMessage() + ". Will not retry dictionary download.");
          } else {
            logger.warn("Exception encountered when asynchronously downloading dictionary for resource: " + kafkaTopic +
                " : " + exception.getMessage());
            downloadingDictionaryFutures.remove(kafkaTopic);

            executor.schedule(() -> dictionaryDownloadCandidates.add(kafkaTopic),
                DEFAULT_DICTIONARY_DOWNLOAD_INTERNAL_IN_MS, TimeUnit.MILLISECONDS);
          }
        } else {
          logger.info("Dictionary downloaded asynchronously for resource: " + kafkaTopic);
          initCompressorFromDictionary(version, dictionary);
        }
        return null;
      });
      downloadingDictionaryFutures.put(kafkaTopic, dictionaryFuture);
    }

    return dictionaryFuture;
  }

  private void initCompressorFromDictionary(Version version, byte[] dictionary) {
    String kafkaTopic = version.kafkaTopicName();
    if (version.getStatus() != VersionStatus.ONLINE || !downloadingDictionaryFutures.containsKey(kafkaTopic)) {
      // Nothing to do since version was retired.
      return;
    }
    CompressionStrategy compressionStrategy = version.getCompressionStrategy();
    CompressorFactory.createVersionSpecificCompressorIfNotExist(compressionStrategy, kafkaTopic, dictionary);
  }

  private void handleVersionRetirement(String kafkaTopic, String exceptionReason) {
    InterruptedException e = new InterruptedException("Dictionary download for resource " + kafkaTopic + " interrupted: " + exceptionReason);
    CompletableFuture<Void> dictionaryFutureForTopic = downloadingDictionaryFutures.remove(kafkaTopic);
    if (dictionaryFutureForTopic != null && !dictionaryFutureForTopic.isDone()) {
      dictionaryFutureForTopic.completeExceptionally(e);
    }
    dictionaryDownloadCandidates.remove(kafkaTopic);
    CompressorFactory.removeVersionSpecificCompressor(kafkaTopic);
  }

  @Override
  public boolean startInner() {
    httpClient.start();
    metadataRepository.registerStoreDataChangedListener(storeChangeListener);
    // Dictionary warmup
    boolean success = getAllDictionaries();
    // If dictionary warm up failed, stop router from starting up
    if (!success) {
      throw new VeniceException("Dictionary warmup failed! Preventing router start up.");
    }
    dictionaryRetrieverThread.start();
    return true;
  }

  @Override
  public void stopInner() throws IOException {
    dictionaryRetrieverThread.interrupt();
    executor.shutdownNow();
    downloadingDictionaryFutures.forEach((topic, future) -> future.completeExceptionally(new InterruptedException("Dictionary download thread stopped")));
    httpClient.close();
  }
}
