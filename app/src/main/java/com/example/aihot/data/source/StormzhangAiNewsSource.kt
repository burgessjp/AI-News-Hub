package com.example.aihot.data.source

import com.example.aihot.data.StormzhangAiNewsResult

/**
 * stormzhang AI 资讯数据源抽象 —— [com.example.aihot.data.StormzhangAiNewsRepository]
 * (实时)与 [StormzhangAiNewsArchiveRepository](gitcode 归档)的共同接口。
 */
interface StormzhangAiNewsSource {
    suspend fun fetch(): StormzhangAiNewsResult
    suspend fun forceRefresh(): StormzhangAiNewsResult
}
