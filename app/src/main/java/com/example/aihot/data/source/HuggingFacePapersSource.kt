package com.example.aihot.data.source

import com.example.aihot.data.HuggingFacePapersResult

/**
 * HuggingFace Trending Papers 数据源抽象 ——
 * [com.example.aihot.data.HuggingFacePapersRepository](实时)与
 * [HuggingFacePapersArchiveRepository](gitcode 归档)的共同接口。
 */
interface HuggingFacePapersSource {
    suspend fun fetch(): HuggingFacePapersResult
    suspend fun forceRefresh(): HuggingFacePapersResult
}
