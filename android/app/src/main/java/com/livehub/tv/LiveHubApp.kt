package com.livehub.tv

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.livehub.tv.core.device.DeviceProfile
import com.livehub.tv.core.net.Net
import com.livehub.tv.core.store.Prefs

class LiveHubApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        // 必须在任何 UI/播放代码之前 init：DeviceProfile 的内存判据要在主线程第一次
        // 访问前拿到 Context，否则 lazy 会把「取不到内存」缓存成 MAX_VALUE（即不降级）
        DeviceProfile.init(this)
        Net.onConnectivityCheck { hasNetwork() }
        observeNetworkChanges()
    }

    /**
     * 网络状态一变就让 `Net` 的探针缓存作废。
     *
     * 为什么要监听而不是光靠 TTL：`Net` 把探针结果缓存起来了（低端盒子上
     * `getNetworkCapabilities` 是跨进程调用，四家并发就是四次）。没有这个回调，
     * 用户从飞行模式切回来最多要等一个 TTL 才恢复，中间那一屏仍然是「网络未连接」。
     *
     * `registerDefaultNetworkCallback` 是 API 24 才有的；21–23 退化成只有 TTL。
     * 注册失败（部分 ROM 会抛）不许影响启动——连通性判断本来就是个优化项。
     */
    private fun observeNetworkChanges() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = Net.invalidateConnectivity()
                override fun onLost(network: Network) = Net.invalidateConnectivity()
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) = Net.invalidateConnectivity()
            })
        } catch (e: Exception) {
            // 已注册过 / ROM 不支持 / 没权限：都只影响「多久能发现网恢复了」，不影响正确性
        }
    }

    /**
     * 给 `Net` 用的连通性探针，**判据故意保守**：只有在「系统连一个活动网络都没有」时
     * 才返回 false。因为一旦误判成没网，所有请求都会被 [Net] 直接短路，用户会看到满屏
     * 「网络未连接」——比多花 8 秒超时糟得多。所以 `getNetworkCapabilities` 拿不到时
     * 一律当作有网，宁可慢，不可冤。
     *
     * 它只做一件事：省掉飞行模式下那次注定失败的 8 秒连接超时。
     * 调用频率由 `Net` 的缓存控制，这里**不需要**再自己缓存。
     */
    private fun hasNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm.activeNetwork ?: return false   // 连活动网络都没有 = 确实没网
            val caps = cm.getNetworkCapabilities(net) ?: return true
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected ?: true
        }
    }

    /**
     * 封面加载。三个非默认项都有理由：
     * - crossfade 关掉：卡片焦点切换时几十张图同时淡入，低端盒子会明显掉帧；
     * - 内存缓存 15%：封面是 16:9 大图，默认比例在 1GB 机器上会被系统杀；
     * - 磁盘 64MB 上限：电视盒子存储常常只剩几百 MB，别把缓存当无底洞。
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(false)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.15)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("cover_cache"))
                .maxSizeBytes(64L * 1024 * 1024)
                .build()
        }
        .build()
}
