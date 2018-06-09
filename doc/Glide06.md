## Glide源码分析（六），缓存架构、存取命中分析
分析Glide缓存策略，我们还得从之前分析的Engine#load方法入手，这个方法中，展示了缓存读取的一些策略，我们继续贴上这块代码。
### Engine#load
```
public <R> LoadStatus load(
      GlideContext glideContext,
      Object model,
      Key signature,
      int width,
      int height,
      Class<?> resourceClass,
      Class<R> transcodeClass,
      Priority priority,
      DiskCacheStrategy diskCacheStrategy,
      Map<Class<?>, Transformation<?>> transformations,
      boolean isTransformationRequired,
      boolean isScaleOnlyOrNoTransform,
      Options options,
      boolean isMemoryCacheable,
      boolean useUnlimitedSourceExecutorPool,
      boolean useAnimationPool,
      boolean onlyRetrieveFromCache,
      ResourceCallback cb) {
    Util.assertMainThread();
    long startTime = VERBOSE_IS_LOGGABLE ? LogTime.getLogTime() : 0;

    EngineKey key = keyFactory.buildKey(model, signature, width, height, transformations,
        resourceClass, transcodeClass, options);

    EngineResource<?> active = loadFromActiveResources(key, isMemoryCacheable);
    if (active != null) {
      cb.onResourceReady(active, DataSource.MEMORY_CACHE);
      if (VERBOSE_IS_LOGGABLE) {
        logWithTimeAndKey("Loaded resource from active resources", startTime, key);
      }
      return null;
    }

    EngineResource<?> cached = loadFromCache(key, isMemoryCacheable);
    if (cached != null) {
      cb.onResourceReady(cached, DataSource.MEMORY_CACHE);
      if (VERBOSE_IS_LOGGABLE) {
        logWithTimeAndKey("Loaded resource from cache", startTime, key);
      }
      return null;
    }

    EngineJob<?> current = jobs.get(key, onlyRetrieveFromCache);
    if (current != null) {
      current.addCallback(cb);
      if (VERBOSE_IS_LOGGABLE) {
        logWithTimeAndKey("Added to existing load", startTime, key);
      }
      return new LoadStatus(cb, current);
    }

    EngineJob<R> engineJob =
        engineJobFactory.build(
            key,
            isMemoryCacheable,
            useUnlimitedSourceExecutorPool,
            useAnimationPool,
            onlyRetrieveFromCache);

    DecodeJob<R> decodeJob =
        decodeJobFactory.build(
            glideContext,
            model,
            key,
            signature,
            width,
            height,
            resourceClass,
            transcodeClass,
            priority,
            diskCacheStrategy,
            transformations,
            isTransformationRequired,
            isScaleOnlyOrNoTransform,
            onlyRetrieveFromCache,
            options,
            engineJob);

    jobs.put(key, engineJob);

    engineJob.addCallback(cb);
    engineJob.start(decodeJob);

    if (VERBOSE_IS_LOGGABLE) {
      logWithTimeAndKey("Started new load", startTime, key);
    }
    return new LoadStatus(cb, engineJob);
  }
```
涉及到的缓存类型如下：
### 内存和磁盘各自的两种缓存
1. ActiveResources缓存和MemoryCache，MemoryCache我们很好理解，就是Resouce在内存中的缓存，ActiveResources是什么意思呢，其实我们可以这样理解，类似多级缓存的概念，当然这里不是特别的适合，ActiveResources缓存和MemoryCache是同时存在的。ActiveResources缓存存放的是所有未被clear的Request请求到的Resource，这部分Resource会存放至ActiveResources缓存中，当Request被clear的时候，会把这部分在ActiveResources缓存中的Resource移动至MemoryCache中去，只有MemoryCache中能够命中，则这部分resource又会从MemoryCache移至ActiveResources缓存中去，到这里，相信大家能够明白ActiveResources了，其实相当于是对内存缓存再次做了一层，能够有效的提高访问速度，避免过多的操作MemoryCache，因为我们知道，MemoryCache中存放的缓存可能很多，这样的话，直接在上面做一层ActiveResources缓存显得就很有必要了。
2. DiskCache，磁盘缓存比较简单，其中也分为ResourceCacheKey与DataCacheKey，一个是已经decode过的可以之间供Target给到View去渲染的，另一个是还未decode过的，缓存的是源数据。磁盘缓存的保存是在第一次请求网络成功时候，会刷新磁盘缓存，此时处理的是源数据，至于是否会缓存decode过后的数据，取决于DiskCacheStrategy的策略。

结合前面所有文章，这里我再次简要梳理下资源加载的过程。
### 简要资源加载全过程
1. 检查ActiveResources缓存中能否命中，若命中，则请求完成，通知Target渲染对应的View。若未命中，则进入Step2。
2. 检查MemoryCache缓存能否命中，若命中，则请求完成，通知Target渲染对应的View。若未命中，则进入Step３。
3. 构造或复用已有的EngineJob与DecodeJob，开始资源的加载，加载过程是ResourceCacheGenerator -> DataCacheGenerator -> SourceGenerator优先级顺序，不管哪种方式取到了数据，最终都会回调至DecodeJob中处理，区别在于SourceGenerator会更新磁盘缓存，此时的是DataCacheKey类型的缓存。进入步骤４。
４. DecodeJob回调中，一方面通过decodeFromData从DataFetcher中decode取到的原数据，转换为View能够展示的Resource，比如Drawable或Bitmap等，同时根据缓存策略，取决是否会构建ResourceCacheKey类型的缓存。decode这一步就已经结束，接下来会进行线程切换，最终切换到EngineJob的handleResultOnMainThread方法中，在这个方法中，会根据resource资源，构建一个非常重要的角色EngineResource，它是用来存放至ActiveResources缓存和MemoryCache中的，这里往ActiveResources缓存中put资源就是在此时回调至Engine的onEngineJobComplete中完成的。接下来就是回调至SingleRequest中的onResourceReady中去更新Target中View的渲染资源了。至此，全过程就已经结束。

### 内存缓存的要点
相信到这里，有同学已经意识到，这里并没有更新MemoryCache呢，难道此时不正是应该更新到内存缓存中去吗？这里什么时候一个资源才会put至MemoryCache呢，回到ActiveResources缓存中存放的EngineResource，它内部维护了一个计数，当计数减为0的时候，会触发一个callback，它里面的实现就是将EngineResource从ActiveResources缓存移动至MemoryCache，也就是put到MemoryCache的时机，为什么是这样呢？通过我仔细的细节分析，每一个加载的SingleRequest中有一个对应的EngineResource的引用，SingleRequest是与生命周期绑定的，当所属的请求上下文被onDestroy是，会通过其对应的RequestManager取消其所有的Request对象，而在Request的clear中则会调用Resource的recycle方法。此时就是EngineResource的recycle方法，因此，当生命周期onDestory被触发时，对应EngineResource计数会减为0，也就触发将EngineResource从ActiveResources缓存移动至MemoryCache。此时ActiveResources缓存会失效，同时我们可以看到MemoryCache命中时，恰恰会进行一个反向的操作，将EngineResource从MemoryCache重新移动至ActiveResources缓存。这里相信大家更明白了，为什么这里做了一个类似内存的二级缓存，也是Glide处于一种优化的考虑吧。下面我们再来分析下磁盘缓存DataCacheKey命中的情况。

###  磁盘缓存的命中
在[Glide源码分析（五），EngineJob与DecodeJob代码详细加载过程](Glide0５.md)一文中，我们看到资源加载成功缓存到磁盘上是在SourceGenerator＃cacheData方法中进行的，我们来看其具体实现。

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
这段代码逻辑相关比较好理解，根据loadData中的sourceKey以及签名信息，构造一个DataChcheKey类型的对象，而后将其put至磁盘缓存中，其中sourceKey就是我们加载资源的GlideUrl对象("https://p.upyun.com/docs/cloud/demo.jpg")　。磁盘缓存的具体实现我们已经了解，默认是由DiskLruCacheWrapper实现，具体功能就是将数据写入预先设置的缓存目录的文件下，以文件的方式存放。在分析D加载资源的详细过程中，我们知道Engine#load会先在内存中查找是否有缓存命中，否则会启动DecodeJob，在它中总共有三个DataFetchGenerator，这里和磁盘缓存相关的就是DataCacheGenerator，具体逻辑是在其DataCacheGenerator#startNext方法中。
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
我们假定内存缓存以及在激活的资源池中均没有命中，则此时会根据GlideUrl[https://p.upyun.com/docs/cloud/demo.jpg] 以它和签名组成的DataCacheKey，从DiskCache中去寻找这个缓存文件，DiskLruCacheWrapper#get方法实现如下：

```
@Override
 public File get(Key key) {
   String safeKey = safeKeyGenerator.getSafeKey(key);
   if (Log.isLoggable(TAG, Log.VERBOSE)) {
     Log.v(TAG, "Get: Obtained: " + safeKey + " for for Key: " + key);
   }
   File result = null;
   try {
     // It is possible that the there will be a put in between these two gets. If so that shouldn't
     // be a problem because we will always put the same value at the same key so our input streams
     // will still represent the same data.
     final DiskLruCache.Value value = getDiskCache().get(safeKey);
     if (value != null) {
       result = value.getFile(0);
     }
   } catch (IOException e) {
     if (Log.isLoggable(TAG, Log.WARN)) {
       Log.w(TAG, "Unable to get from disk cache", e);
     }
   }
   return result;
 }

```
可以看到，真正去根据key获取文件信息实际上是由getDiskCache().get方法去实现的，这里我们需要分析getDiskCache()的实现，也就是操作磁盘文件的类了。
```
private synchronized DiskLruCache getDiskCache() throws IOException {
   if (diskLruCache == null) {
     diskLruCache = DiskLruCache.open(directory, APP_VERSION, VALUE_COUNT, maxSize);
   }
   return diskLruCache;
 }
```
getDiskCache的实现也很明确，就是调用DiskLruCache的静态open方法，创建一个diskLruCache单例对象，方法入参directory表示缓存目录，maxSize缓存最大大小。open的实现如下：
```
public static DiskLruCache open(File directory, int appVersion, int valueCount, long maxSize)
      throws IOException {
    ...
    // Prefer to pick up where we left off.
    DiskLruCache cache = new DiskLruCache(directory, appVersion, valueCount, maxSize);
    if (cache.journalFile.exists()) {
      try {
        cache.readJournal();
        cache.processJournal();
        return cache;
      } catch (IOException journalIsCorrupt) {
        System.out
            .println("DiskLruCache "
                + directory
                + " is corrupt: "
                + journalIsCorrupt.getMessage()
                + ", removing");
        cache.delete();
      }
    }

    // Create a new empty cache.
    directory.mkdirs();
    cache = new DiskLruCache(directory, appVersion, valueCount, maxSize);
    cache.rebuildJournal();
    return cache;
  }
```
我们分析最简单的情况，如果在磁盘中有缓存文件了，显然此时if语句journalFile文件是存在的，因此，接下来调用readJournal根据缓存key将索引信息读入lruEntries中，每一个缓存key对应有一个Entry信息。Entry中保存缓存文件索引的是cleanFiles，cleanFiles虽然是一个File数组，但是目前glide对于这个数据的size是恒为１的，也就是缓存key,Entry,文件是一个一一对应的关系，这里glide用数组提供了将来一种可扩展性的预留实现。这样磁盘缓存索引也就建立完成。下面继续看DiskLruCache#get的实现
```
public synchronized Value get(String key) throws IOException {
   checkNotClosed();
   Entry entry = lruEntries.get(key);
   if (entry == null) {
     return null;
   }

   if (!entry.readable) {
     return null;
   }

   ...
   return new Value(key, entry.sequenceNumber, entry.cleanFiles, entry.lengths);
 }
```
还是分析简单的情况，这里就是在Entry索引中根据key信息查找，而后将结果返个DiskLruCacheWrapper，这里我们看到有entry.cleanFiles，。
entry.cleanFiles也就是对应在DataCacheGenerator中cacheFile的实例。因此整个在磁盘cache中查找文件的过程也就比较清楚了。再次看DataCacheGenerator中的startNext，此时cacheFile能够命中，因此会触发对应的modelLoader去从缓存中加载数据。

### 总结
这里我们介绍了内存缓存，ActiveResources与MemoryCache的命中情况分析，以及DiskCache的DataCacheKey的命中分析，DiskCach还有一个关于ResourceCacheKey的情况，相应的代码在ResourceCacheGenerator中，我们这里不再研究，也是一样的思路。这里再强调几点，DataCacheKey中缓存的是DataFetcher拉取的源数据，也就是原始的数据，ResourceCacheKey则是基于原始数据，做的一层更精细的缓存，从它们的构造方法中我们可以看到。
```
key =
    new ResourceCacheKey(
        decodeHelper.getArrayPool(),
        currentSourceKey,
        signature,
        width,
        height,
        appliedTransformation,
        resourceSubClass,
        options);

// DataCacheKey
key = new DataCacheKey(currentSourceKey, signature);
```
正如我们简单的例子，这里DataCacheKey只有网络的url决定，也即是一个数据流对象，不同的decode可以来扩展它，ResourceCacheKey就是这样一种缓存。至此，对于Glide的缓存架构我们就分析完了，整个系列差不多也接近尾声了，后面文章中，我会整理一些大纲的总线，供大家自己研读。
