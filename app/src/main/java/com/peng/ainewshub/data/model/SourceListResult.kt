package com.peng.ainewshub.data.model

/**
 * 源列表结果的统一抽象 —— 让 UI 层的源列表 ViewModel 基类能用统一接口操作各源不同的 Result 类型。
 *
 * 各源的 Result(如 [TrendingResult] / [HuggingFacePapersResult] /
 * [com.peng.ainewshub.data.source.ProductHuntResult] 等)实现本接口,
 * 暴露列表元素 + 落盘时刻。这样 ViewModel 的「空判 / 取 fetchedAt / 取列表」逻辑可单点实现。
 *
 * 定义在 data 层(各 Result 同层),避免 data → ui 反向依赖。
 */
interface SourceListResult<out T> {
    /** 列表元素(用于空判与 Success 包装)。 */
    val items: List<T>
    /** 数据落盘时刻(命中缓存时是缓存写入时刻,走网络时是当前时刻)。 */
    val fetchedAt: Long
}
