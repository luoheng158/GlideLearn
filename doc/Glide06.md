## Glide源码分析（六），资源加载成功更新缓存过程
在Engine#load方法中我们知道glide读取资源时候的顺序，缓存策略优先级如下：

１．激活资源池是否存在，若命中则返回结果，否则检测内存缓存。
2. 内存缓存是否存在，若命中则返回结果，否则检测　
在内存缓存命中相对简单一些，我们只需知道，更新内存缓存的时机，至于读取内存缓存的时机我们其实已经分析过了，就是，首先是检测激活池中的资源，其次是内存缓存，再次
### １. 磁盘缓存的命中
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

### 2. 内存缓存的命中
内存缓存命中相对简单一些，我们只需知道，更新内存缓存的时机，至于读取内存缓存的时机我们其实已经分析过了，就是在Engine#load方法中，首先是检测激活池中的资源，其次是内存缓存，再次
