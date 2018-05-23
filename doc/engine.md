### Engine

- Key
唯一标识一些数据的接口。

- EngineKey
实现了Key接口，用做多路复用负载的内存缓存键

- Resource  
一个包装了特定类型的资源接口，并且能够汇集和重用。

- MemoryCache  
内存缓存接口，用于在内存缓存中添加和移除资源，这里的实现类是LruResourceCache，继承了LruCache。存放的是Key和Resource键值对。

- DiskCache  
这里的DiskCache是由InternalCacheDiskCacheFactory创建，其继承自DiskLruCacheFactory，最终DiskCache的实现类是DiskLruCacheWrapper对象。

- ActiveResources  

- ResourceRecycler  
一个回收Resource的辅助类，防止陷入递归，当回收的Resource资源有子资源的时候。

- EngineJob

- Jobs