## DecodeJob结构

- DecodeHelper  
将DecodeJob中的部分属性，交由DecodeHelper进行管理和操作，使代码更加清晰。
- DiskCacheProvider  
内部返回了一个DiskCache的对象，具体的默认实现是DiskLruCacheWrapper。关于DiskCache详细结构，可以参考此文[DiskCache结构](disk_cache.md)。
- DeferredEncodeManager  
???????
- ReleaseManager  
负责指示何时将作业安全地清理并返回池中。
- Key  
额外的签名信息，默认是EmptySignature。详细介绍->[Key结构](key.md)。
- EngineKey  
用于多路复用负载的内存缓存键
- DiskCacheStrategy  
媒体的可用缓存策略集。
- Callback
资源加载结果的回调，成功、失败或者重新执行。
- Stage  
解码数据阶段状态信息，总共有五种。
- RunReason  
执行job的原因，总共有三种。
- DataSource  
指示一些检索到的数据的来源。目前有五种：  
LOCAL:表示数据可能是从设备本地获取的。  
REMOTE:表示数据是从设备以外的远程源检索的。  
DATA_DISK_CACHE:表示数据是从设备高速缓存未经修改而检索的。  
RESOURCE_DISK_CACHE:表示数据是从设备缓存中的修改内容中检索的。  
MEMORY_CACHE:表示数据是从内存缓存中检索的。
- DataFetcher
延迟检索能够用作资源加载的数据。详细参考[数据加载结构DataFetcher与ModelLoader](datafetcher_and_modelloader.md)。
- DataFetcherGenerator
