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
这里，参考[DecodeHelper类相关方法分析](decode_helper.md)，此时通过decodeHelper拿到的sourceIds就是[GlideUrl,ObjectKey]，如果通过注册的信息找不到此时的key，表明glide本身还不支持这种方式，因此调用结束。显然此时是支持的，接下来是通过helper的getRegisteredResourceClasses获取resourceClass信息，这里大致是glide所支持的资源类信息，也就是能够进行decode的。这里它存放的是[GifDrawable,Bitmap,BitmapDrawable]。因此接下来进入的while循环。