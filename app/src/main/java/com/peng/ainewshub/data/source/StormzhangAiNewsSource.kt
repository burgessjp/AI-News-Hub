package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.StormzhangAiNewsResult

/**
 * stormzhang AI 资讯数据源抽象 —— [com.peng.ainewshub.data.StormzhangAiNewsRepository]
 * (实时)与 [StormzhangAiNewsArchiveRepository](gitcode 归档)的共同接口。
 */
interface StormzhangAiNewsSource {
    suspend fun fetch(): StormzhangAiNewsResult
    suspend fun forceRefresh(): StormzhangAiNewsResult
}
