## Glide源码分析（五），EngineJob与DecodeJob代码详细加载过程
在[Glide源码分析（三），Engine加载资源过程](Glide03.md)文中，我们分析到Engine#load的最后一步，创建好了一对EngineJob和DecodeJob，随之调用EngineJob的start方法，启动加载任务。下面分析整个一个执行过程，文中相关情景下的方法特定调用的结果是还是基于一下这段code，虽然是由特殊入口，并不影响我们理解整个框架，反而是一个很好的突破口，理解代码的思想。示例代码如下：

```
       Glide.with(this)
                .load("https://p.upyun.com/docs/cloud/demo.jpg")
                .into(imageView);
```
此时的情景就是加载一个普通的http url对象。下面我们开始分析，加载的起点，也就是从EngineJob#start开始。

### 1. EngineJob#start
```
 public void start(DecodeJob<R> decodeJob) {
    this.decodeJob = decodeJob;
    GlideExecutor executor = decodeJob.willDecodeFromCache()
        ? diskCacheExecutor
        : getActiveSourceExecutor();
    executor.execute(decodeJob);
  }
```
这个方法是根据当前条件，选取一个GlideExecutor，它实现了ExecutorService，然后由它去执行decodeJob。通过decodeJob的willDecodeFromCache方法，决定是使用哪一个。Glide里面，封装了很几种类型的线程池对象，这里无需深究哪个线程池这个细节。显然DecodeJob是一个Runnable对象，最终执行之后，都是触发DecodeJob的run方法。

### 2.DecodeJob#run
```
 @Override
  public void run() {
    // This should be much more fine grained, but since Java's thread pool implementation silently
    // swallows all otherwise fatal exceptions, this will at least make it obvious to developers
    // that something is failing.
    GlideTrace.beginSectionFormat("DecodeJob#run(model=%s)", model);
    // Methods in the try statement can invalidate currentFetcher, so set a local variable here to
    // ensure that the fetcher is cleaned up either way.
    DataFetcher<?> localFetcher = currentFetcher;
    try {
      if (isCancelled) {
        notifyFailed();
        return;
      }
      runWrapped();
    } catch (Throwable t) {
      // Catch Throwable and not Exception to handle OOMs. Throwables are swallowed by our
      // usage of .submit() in GlideExecutor so we're not silently hiding crashes by doing this. We
      // are however ensuring that our callbacks are always notified when a load fails. Without this
      // notification, uncaught throwables never notify the corresponding callbacks, which can cause
      // loads to silently hang forever, a case that's especially bad for users using Futures on
      // background threads.
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(TAG, "DecodeJob threw unexpectedly"
            + ", isCancelled: " + isCancelled
            + ", stage: " + stage, t);
      }
      // When we're encoding we've already notified our callback and it isn't safe to do so again.
      if (stage != Stage.ENCODE) {
        throwables.add(t);
        notifyFailed();
      }
      if (!isCancelled) {
        throw t;
      }
    } finally {
      // Keeping track of the fetcher here and calling cleanup is excessively paranoid, we call
      // close in all cases anyway.
      if (localFetcher != null) {
        localFetcher.cleanup();
      }
      GlideTrace.endSection();
    }
  }
```
执行run方法时候，首先检查isCancelled标志位，它是一个volatile变量，保证了多线程之间的可见性，由此可知道DecodeJob是支持取消的。如果此时，已经被取消，则会调用notifyFailed方法，它里面主要是通过callback回调告知上层调用者，这里就是EngineJob，其二是清理和重置DecodeJob里面的一些资源。另外如果未被取消，如果有任何异常出现，则会进入catch方法块，也会看条件进行一些回调和清理资源，同时在finally代码块中，执行已经结束，localFetcher记录着当前的currentFetcher对象，这个时候会通知DataFetcher的cleanup清理资源，因为DecodeJob是可被复用的，显然第一次运行localFetcher是null，后面我们分析到了这个再具体看看它的用处。下面我们分析重点方法，runWapped这个的执行逻辑。

### 3. DecodeJon#runWapped
```
  private void runWrapped() {
    switch (runReason) {
      case INITIALIZE:
        stage = getNextStage(Stage.INITIALIZE);
        currentGenerator = getNextGenerator();
        runGenerators();
        break;
      case SWITCH_TO_SOURCE_SERVICE:
        runGenerators();
        break;
      case DECODE_DATA:
        decodeFromRetrievedData();
        break;
      default:
        throw new IllegalStateException("Unrecognized run reason: " + runReason);
    }
  }
  
   private Stage getNextStage(Stage current) {
    switch (current) {
      case INITIALIZE:
        return diskCacheStrategy.decodeCachedResource()
            ? Stage.RESOURCE_CACHE : getNextStage(Stage.RESOURCE_CACHE);
      case RESOURCE_CACHE:
        return diskCacheStrategy.decodeCachedData()
            ? Stage.DATA_CACHE : getNextStage(Stage.DATA_CACHE);
      case DATA_CACHE:
        // Skip loading from source if the user opted to only retrieve the resource from cache.
        return onlyRetrieveFromCache ? Stage.FINISHED : Stage.SOURCE;
      case SOURCE:
      case FINISHED:
        return Stage.FINISHED;
      default:
        throw new IllegalArgumentException("Unrecognized stage: " + current);
    }
  }
```
runWrapped中主要是根据runReason获取一个Stage状态对象，这里传入的current是Stage.INITIALIZE，diskCacheStrategy这个值是由RequestOptions传入，默认是DiskCacheStrategy.AUTOMATIC。因此，此时diskCacheStrategy.decodeCachedResource()为true，成员变量stage赋值为Stage.RESOURCE_CACHE。接下来调用getNextGenerator创建一个DataFetcherGenerator对象。

```
 private DataFetcherGenerator getNextGenerator() {
    switch (stage) {
      case RESOURCE_CACHE:
        return new ResourceCacheGenerator(decodeHelper, this);
      case DATA_CACHE:
        return new DataCacheGenerator(decodeHelper, this);
      case SOURCE:
        return new SourceGenerator(decodeHelper, this);
      case FINISHED:
        return null;
      default:
        throw new IllegalStateException("Unrecognized stage: " + stage);
    }
  }
```
getNextGenerator方法的实现很简单，就是根据stage的信息，返回相应的对象，这里我们的stage为Stage.RESOURCE_CACHE，因此此时currentGenerator就是一个ResourceCacheGenerator对象。再接着，在runWrapped方法中，调用了runGenerators，继续运行。

### 4.DecodeJob#runGenerators
```
 private void runGenerators() {
    currentThread = Thread.currentThread();
    startFetchTime = LogTime.getLogTime();
    boolean isStarted = false;
    while (!isCancelled && currentGenerator != null
        && !(isStarted = currentGenerator.startNext())) {
      stage = getNextStage(stage);
      currentGenerator = getNextGenerator();

      if (stage == Stage.SOURCE) {
        reschedule();
        return;
      }
    }
    // We've run out of stages and generators, give up.
    if ((stage == Stage.FINISHED || isCancelled) && !isStarted) {
      notifyFailed();
    }

    // Otherwise a generator started a new load and we expect to be called back in
    // onDataFetcherReady.
  }
```
在runGenerators方法while循环中，先是检查isCancelled状态，一旦isCancelled为true或者isStarted为true，表明任务已经启动，整个循环就会结束。在接下来的判断中，stage状态为完成或者被取消并且任务没有启动，则直接notifyFailed，此次请求就已经失败。下面还是分析while条件，考虑未被取消的情况，DataFetcherGenerator#startNext表示是否成功启动了DataFetcher。  

第一次循环currentGenerator为ResourceCacheGenerator，我们假设没有成功启动。再次进入getNextStage，这里传入的stage为Stage.RESOURCE_CACHE，因此返回下一个状态为Stage.DATA_CACHE，再看getNextGenerator，此时会根据stage返回DataCacheGenerator对象，循环继续。  
第二次循环继续，currentGenerator为DataCacheGenerator，我们假设没有成功启动。再次进入getNextStage，这里传入的stage为Stage.DATA_CACHE，onlyRetrieveFromCache此时是false,因此返回下一个状态为Stage.SOURCE，再看getNextGenerator，此时会根据stage返回SourceGenerator对象，此时发现stage已经是Stage.SOURCE，内部循环被return，请求reschedule方法重新调度。

```
  public void reschedule() {
    runReason = RunReason.SWITCH_TO_SOURCE_SERVICE;
    callback.reschedule(this);
  }
```  
这里将runReason置为RunReason.SWITCH_TO_SOURCE_SERVICE，回调至EngineJob的reschedule中。它的实现如下:

```
  public void reschedule(DecodeJob<?> job) {
    // Even if the job is cancelled here, it still needs to be scheduled so that it can clean itself
    // up.
    getActiveSourceExecutor().execute(job);
  }
```
它的实现比较简单，就是请求source相关的线程池，继续执行这个job。显然DecodeJob的run方法又会再次被触发执行，runReason为RunReason.SWITCH_TO_SOURCE_SERVICE，stage为Stage.SOURCE。再次分析runWrapped中，此时会调用runGenerators继续执行逻辑。此时currentGenerator为SourceGenerator，进入while循环，我们假设调用startNext依然返回false，此时进入循环代码块，getNextStage会返回Stage.FINISHED，stage被置为Stage.FINISHED状态，获取下一个DataFetcherGenerator时会返回null，此时currentGenerator为null，因此while循环结束，加载也就结束。

整的来说，DecodeJob的run方法，会依次从ResourceCacheGenerator->DataCacheGenerator->SourceGenerator这样一个链执行，只要其中一个的startNext方法返回为
true，则不再寻找下一个Generator。现在我们来分析我们此时的具体情况。回到runGenerators第一次while循环的执行时机去。

### 5. ResourceCacheGenerator#startNext
```
 @Override
  public boolean startNext() {
    List<Key> sourceIds = helper.getCacheKeys();
    if (sourceIds.isEmpty()) {
      return false;
    }
    List<Class<?>> resourceClasses = helper.getRegisteredResourceClasses();
    if (resourceClasses.isEmpty()) {
      if (File.class.equals(helper.getTranscodeClass())) {
        return false;
      }
      // TODO(b/73882030): This case gets triggered when it shouldn't. With this assertion it causes
      // all loads to fail. Without this assertion it causes loads to miss the disk cache
      // unnecessarily
      // throw new IllegalStateException(
      //    "Failed to find any load path from " + helper.getModelClass() + " to "
      //        + helper.getTranscodeClass());
    }
    while (modelLoaders == null || !hasNextModelLoader()) {
      resourceClassIndex++;
      if (resourceClassIndex >= resourceClasses.size()) {
        sourceIdIndex++;
        if (sourceIdIndex >= sourceIds.size()) {
          return false;
        }
        resourceClassIndex = 0;
      }

      Key sourceId = sourceIds.get(sourceIdIndex);
      Class<?> resourceClass = resourceClasses.get(resourceClassIndex);
      Transformation<?> transformation = helper.getTransformation(resourceClass);
      // PMD.AvoidInstantiatingObjectsInLoops Each iteration is comparatively expensive anyway,
      // we only run until the first one succeeds, the loop runs for only a limited
      // number of iterations on the order of 10-20 in the worst case.
      currentKey =
          new ResourceCacheKey(// NOPMD AvoidInstantiatingObjectsInLoops
              helper.getArrayPool(),
              sourceId,
              helper.getSignature(),
              helper.getWidth(),
              helper.getHeight(),
              transformation,
              resourceClass,
              helper.getOptions());
      cacheFile = helper.getDiskCache().get(currentKey);
      if (cacheFile != null) {
        sourceKey = sourceId;
        modelLoaders = helper.getModelLoaders(cacheFile);
        modelLoaderIndex = 0;
      }
    }

    loadData = null;
    boolean started = false;
    while (!started && hasNextModelLoader()) {
      ModelLoader<File, ?> modelLoader = modelLoaders.get(modelLoaderIndex++);
      loadData = modelLoader.buildLoadData(cacheFile,
          helper.getWidth(), helper.getHeight(), helper.getOptions());
      if (loadData != null && helper.hasLoadPath(loadData.fetcher.getDataClass())) {
        started = true;
        loadData.fetcher.loadData(helper.getPriority(), this);
      }
    }

    return started;
  }

```
这里，参考[DecodeHelper类相关方法分析](decode_helper.md)，此时通过decodeHelper拿到的sourceIds就是[GlideUrl,ObjectKey]，如果通过注册的信息找不到此时的key，表明glide本身还不支持这种方式，因此调用结束。显然此时是支持的，接下来是通过helper的getRegisteredResourceClasses获取resourceClass信息，这里大致是glide所支持的资源类信息，也就是能够进行decode的。这里它存放的是[GifDrawable,Bitmap,BitmapDrawable]。因此接下来进入第一个的while循环:  

1. 由resourceClasses和sourceIds组成的一个正交关系，迭代每一组。
2. 迭代开始前，若modelLoaders为空或者size为0，则继续迭代进入步骤3，否则循环结束。
3. 循环中，检测是否已经全部迭代完成，如果还有，则进入步骤4，否则循环结束。
4. 对每一组，获取相应的缓存Key对象，根据缓存key去diskcache中查找缓存文件，查找成功，则通过getModelLoaders获取当前的modelLoaders信息，继续执行循环，进入步骤2。

从这里我们可以看出这个while循环的作用就是找到modelLoaders信息，如果没找到有效的，则循环结束，方法块正交组迭代完成之后，startNext方法结束，方法返回false，交给下一个Generator去处理。如果能够找到，则执行下一个while循环。这个循环相对简单一些，就是根据上一个while循环查找到的modelLoaders，进行遍历，只要有一个对应的fetcher能够处理，则startNext返回true，表明此时这个generator已经能够处理本次请求，所以也不会再交给其他的generator对应的fetcher去处理了。  

在我们此时的情景中，ResourceCacheGenerator是无法处理本次请求的，所以，交给下一个Generator去处理，也就是DataCacheGenerator的startNext。

### 6. DataCacheGenerator#startNext
```
 @Override
  public boolean startNext() {
    while (modelLoaders == null || !hasNextModelLoader()) {
      sourceIdIndex++;
      if (sourceIdIndex >= cacheKeys.size()) {
        return false;
      }

      Key sourceId = cacheKeys.get(sourceIdIndex);
      // PMD.AvoidInstantiatingObjectsInLoops The loop iterates a limited number of times
      // and the actions it performs are much more expensive than a single allocation.
      @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
      Key originalKey = new DataCacheKey(sourceId, helper.getSignature());
      cacheFile = helper.getDiskCache().get(originalKey);
      if (cacheFile != null) {
        this.sourceKey = sourceId;
        modelLoaders = helper.getModelLoaders(cacheFile);
        modelLoaderIndex = 0;
      }
    }

    loadData = null;
    boolean started = false;
    while (!started && hasNextModelLoader()) {
      ModelLoader<File, ?> modelLoader = modelLoaders.get(modelLoaderIndex++);
      loadData =
          modelLoader.buildLoadData(cacheFile, helper.getWidth(), helper.getHeight(),
              helper.getOptions());
      if (loadData != null && helper.hasLoadPath(loadData.fetcher.getDataClass())) {
        started = true;
        loadData.fetcher.loadData(helper.getPriority(), this);
      }
    }
    return started;
  }

```
看过了ResourceCacheGenerator的startNext方法之后，这个方法也就很好理解了，唯独区别是这里构造的是DataCacheKey,其实也是它们的区别：

- ResourceCacheGenerator  
DataFetcherGenerator实现类，从包含缩减采样/转换资源数据的缓存文件生成DataFetchers{@link com.bumptech.glide.load.data.DataFetcher}。
- DataCacheGenerator  
DataFetcherGenerator实现类，从包含原始未修改源数据的缓存文件生成DataFetchers{@link com.bumptech.glide.load.data.DataFetcher}。
当第一次从网络加载图片时，缓存文件肯定是不存在的，所以，此时的startNext仍然返回false。因此交给下一个generator去处理，也就是SourceGenerator的startNext，而且我们也会看到，SourceGenerator加载成功之后，是会更新缓存信息的，带着这个问题继续分析。

### 7. SourceGenerator#startNext
```
 @Override
  public boolean startNext() {
    if (dataToCache != null) {
      Object data = dataToCache;
      dataToCache = null;
      cacheData(data);
    }

    if (sourceCacheGenerator != null && sourceCacheGenerator.startNext()) {
      return true;
    }
    sourceCacheGenerator = null;

    loadData = null;
    boolean started = false;
    while (!started && hasNextModelLoader()) {
      loadData = helper.getLoadData().get(loadDataListIndex++);
      if (loadData != null
          && (helper.getDiskCacheStrategy().isDataCacheable(loadData.fetcher.getDataSource())
          || helper.hasLoadPath(loadData.fetcher.getDataClass()))) {
        started = true;
        loadData.fetcher.loadData(helper.getPriority(), this);
      }
    }
    return started;
  }
```
这个方法中，主要负责了两件事情，第一部分是缓存相应的数据，第二部分是请求数据。显然，初次进入方法时，dataToCache为空，因此跳过缓存，直接过渡到while循环中，循环负责取当前model相关(这里是一个http url)的所有支持的modelLoaders,这里helper.getLoadData()的内容如下，在DecodeHelper中有过相关的分析，这里直接给出结果。  
> LoadData -> MultFetcher[HttpUrlFetcher, HttpUrlFecter]
> 
> LoadData -> AssetFileDescriptorLocalUriFetcher

遍历LoadData，这里getDiskCacheStrategy默认返回的DiskCacheStrategy.AUTOMATIC，fetcher.getDataSource的实现如下：  
在MultFetcher的实现

```
    @Override
    public DataSource getDataSource() {
      return fetchers.get(0).getDataSource();
    }
```
在HttpUrlFetcher中的实现

```
 @Override
  public DataSource getDataSource() {
    return DataSource.REMOTE;
  }
```
在AssetFileDescriptorLocalUriFetcher中的实现

```
 @Override
  public DataSource getDataSource() {
    return DataSource.LOCAL;
  }
```
DiskCacheStrategy.AUTOMATIC的isDataCacheable实现

```
   @Override
    public boolean isDataCacheable(DataSource dataSource) {
      return dataSource == DataSource.REMOTE;
    }
```
因此，在这里循环遍历中只有第一个LoadData MultFetcher[HttpUrlFetcher, HttpUrlFecter]是能够满足条件的，这个时候，调用MultFetcher的loadData方法去请求数据，在MultFetcher中，包含了多个fetchers，它们会一个个的一次去请求，一个顺序的队列，只要有一个成功了，则不会执行下一个fetcher。此时请求被触发，最后请求成功或失败会在DataFetcher.DataCallback的回调中得到响应。失败了很简单，一步步告知上层，也就结束了，我们看看成功的情况。即SourceGenerator#onDataReady

### 8. SourceGenerator#onDataReady
```
  @Override
  public void onDataReady(Object data) {
    DiskCacheStrategy diskCacheStrategy = helper.getDiskCacheStrategy();
    if (data != null && diskCacheStrategy.isDataCacheable(loadData.fetcher.getDataSource())) {
      dataToCache = data;
      // We might be being called back on someone else's thread. Before doing anything, we should
      // reschedule to get back onto Glide's thread.
      cb.reschedule();
    } else {
      cb.onDataFetcherReady(loadData.sourceKey, data, loadData.fetcher,
          loadData.fetcher.getDataSource(), originalKey);
    }
  }

```
在这个方法中，先是判断数据是否能够被缓存，如果能，则为成员变量dataToCache赋值，前面已经提到，SourceGenerator的startNext分为两部分，此时的dataToCache便是用于去缓存数据了，反之如果不能被缓存，则直接将结果继续抛给上层调用者去处理即可。这里，我们的loadData.fetcher是MultFetcher[HttpUrlFetcher, HttpUrlFecter],因此if语句条件成立，此时会进行缓存的操作，这个时候会请求DecodeJob Callback的reschedule重新调度，和前面所讲的在DecodeJob的reschedule一致，因此，DecodeJob会被再次调度，run方法也会再次执行，此时只会触发SourceGenerator#startNext再次执行，也就是第一部分缓存逻辑会得到执行，因此此时dataToCache是有数据的。再次回到SourceGenerator#startNext

### 9. SourceGenerator#startNext
```
  @Override
  public boolean startNext() {
    if (dataToCache != null) {
      Object data = dataToCache;
      dataToCache = null;
      cacheData(data);
    }

    if (sourceCacheGenerator != null && sourceCacheGenerator.startNext()) {
      return true;
    }
    sourceCacheGenerator = null;
    ....
  }
```
此时dataToCache非空，进入if语句最后执行cacheData去做缓存数据的操作，同时将dataToCache置空，因为在一次请求过程中，SourceGenerator是复用的，比如熄屏等请求可能被打断，所以是需要维护SourceGenerator中一些成员变量的清理操作的。下面继续分析cacheData的实现。

```
  private void cacheData(Object dataToCache) {
    long startTime = LogTime.getLogTime();
    try {
      Encoder<Object> encoder = helper.getSourceEncoder(dataToCache);
      DataCacheWriter<Object> writer =
          new DataCacheWriter<>(encoder, dataToCache, helper.getOptions());
      originalKey = new DataCacheKey(loadData.sourceKey, helper.getSignature());
      helper.getDiskCache().put(originalKey, writer);
      if (Log.isLoggable(TAG, Log.VERBOSE)) {
        Log.v(TAG, "Finished encoding source to cache"
            + ", key: " + originalKey
            + ", data: " + dataToCache
            + ", encoder: " + encoder
            + ", duration: " + LogTime.getElapsedMillis(startTime));
      }
    } finally {
      loadData.fetcher.cleanup();
    }

    sourceCacheGenerator =
        new DataCacheGenerator(Collections.singletonList(loadData.sourceKey), helper, this);
  }
```
cacheData中主要是两件事情，一是缓存当前的数据，用DataCacheKey的缓存key形式缓存了当前加载的数据，这里的我们可以看到，originalKey是和url相关的。然后是实例化sourceCacheGenerator,这里着重注意下，此时的DataCacheGenerator的FetcherReadyCallback传入的是当前this对象，这里也就是SourceGenerator。之前我一直不明白为什么SourceGenerator也实现了FetcherReadyCallback接口，除此之外DecodeJob也实现了此接口，看到这里，一下就明朗了，它是给这里的sourceCacheGenerator的回调使用的。代码继续分析，将数据缓存至磁盘之后，接下来sourceCacheGenerator非空，因此调用其startNext方法。再次回到DataCacheGenerator的startNext此时由于已经存在缓存，最终能够取到相关数据。最后由它的FetcherReadyCallback回调至SourceGenerator中的onDataFetcherReady方法。其实现如下：

```
  @Override
  public void onDataFetcherReady(Key sourceKey, Object data, DataFetcher<?> fetcher,
      DataSource dataSource, Key attemptedKey) {
    // This data fetcher will be loading from a File and provide the wrong data source, so override
    // with the data source of the original fetcher
    cb.onDataFetcherReady(sourceKey, data, fetcher, loadData.fetcher.getDataSource(), sourceKey);
  }
```
它里面的实现也很简单，就是将结果继续往上层抛，这里的FetcherReadyCallback cb正是由DecodeJob实现的。因此，最后的结果被回调至DecodeJob中进行处理。此时，代码分析部分差不多也就结束了，后面的都是层层回调，由DecodeJon->EngineJob->Target中，最后由Target操作ImageView,将资源渲染到View上面。

### 总结
总的来说，glide加载过程就是由EngineJob触发DecodeJob,DecodeJob中会有ResourceCacheGenerator->DataCacheGenerator->SourceGenerator对应的ModelLoaders与ModelFetchers依次处理，如果是SourceGenerator则还会更新缓存，这三个不是说一定都会有的，如果有缓存存在且能命中，则不会经历SourceGenerator阶段。在DecodeJob中获取到数据之后，则会层层上报，由Fetcher->Generator->DecodeJob->EngineJob->SingleRequest->Target这样一个序列回调，我们知道Android只有主线程才能操作ui，这里线程切换部分是在EngineJob中进行完成的。至此，宏观和微观上我们理清了加载的一个过程，后面我们会分析有关磁盘缓存的和对图片结果处理的一些小的细节。

