package com.peng.ainewshub.data.repo

import com.peng.ainewshub.data.AppException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周刊数据解析回归([WeeklyRepository] 的 JSON → [WeeklyDigest] 契约)。
 *
 * 共同语义(与流水线 weekly_digest.py 产物契约对齐):
 *  - sections 为空视为数据无效 → [AppException.NoData];
 *  - 条目 title/url 为空整条丢弃;板块 title 为空整板块丢弃、summary 为空保留
 *    板块但短评为空串(UI 不渲染该段);
 *  - coverage/期号/周期字段原样透传。
 */
class WeeklyRepositoryTest {

    private fun fullJson(): JSONObject = JSONObject(
        """
        {
          "schemaVersion": 1, "promptVersion": 1,
          "weekStart": "2026-10-12", "weekEnd": "2026-10-18",
          "coverageStart": "2026-10-12", "coverageEnd": "2026-10-17",
          "coverageDays": 6, "issueNo": 4, "generatedAt": 1792831420659,
          "digest": "本周主线是开源模型的代际跃迁。",
          "sections": [
            {
              "title": "模型发布", "summary": "这条线本周快速演进。",
              "items": [
                {"title": "条目一", "url": "https://e.com/1", "source": "hackernews",
                 "date": "2026-10-13", "metrics": "得分 319", "comment": "点评",
                 "breaking": true, "daysSeen": 3},
                {"title": "条目二", "url": "https://e.com/2", "source": "aihot-featured",
                 "date": "2026-10-15", "metrics": "权重 85", "comment": "",
                 "breaking": false, "daysSeen": 1}
              ]
            },
            {
              "title": "开源生态", "summary": "",
              "items": [
                {"title": "条目三", "url": "https://e.com/3", "source": "github-trending",
                 "date": "2026-10-16", "metrics": "", "comment": "",
                 "breaking": false, "daysSeen": 2}
              ]
            }
          ]
        }
        """.trimIndent()
    )

    @Test
    fun `完整字段解析`() {
        val d = WeeklyRepository.parseDigest(fullJson())
        assertEquals("2026-10-12", d.weekStart)
        assertEquals("2026-10-18", d.weekEnd)
        assertEquals(6, d.coverageDays)
        assertEquals(4, d.issueNo)
        assertEquals(1792831420659L, d.generatedAt)
        assertEquals("本周主线是开源模型的代际跃迁。", d.digest)
        assertEquals(2, d.sections.size)
        val s0 = d.sections[0]
        assertEquals("模型发布", s0.title)
        assertEquals("这条线本周快速演进。", s0.summary)
        assertEquals(2, s0.items.size)
        val it0 = s0.items[0]
        assertEquals("条目一", it0.title)
        assertEquals("https://e.com/1", it0.url)
        assertEquals("hackernews", it0.source)
        assertEquals("2026-10-13", it0.date)
        assertEquals(3, it0.daysSeen)
        assertTrue(it0.breaking)
    }

    @Test
    fun `summary 空保留板块短评为空串`() {
        val d = WeeklyRepository.parseDigest(fullJson())
        assertEquals("开源生态", d.sections[1].title)
        assertEquals("", d.sections[1].summary)
    }

    @Test
    fun `sections 为空抛 NoData`() {
        val json = fullJson().put("sections", java.util.ArrayList<Any>())
        try {
            WeeklyRepository.parseDigest(json)
            throw AssertionError("应抛 NoData")
        } catch (e: AppException.NoData) {
            // 预期:空刊视为「尚未生成」空态语义
        }
    }

    @Test
    fun `脏条目与空板块被过滤`() {
        val json = fullJson().put("sections", org.json.JSONArray("""
            [
              {"title": "好板块", "summary": "短评", "items": [
                {"title": "", "url": "https://e.com/x", "source": "s", "date": "d",
                 "metrics": "", "comment": "", "breaking": false, "daysSeen": 1},
                {"title": "无链接", "url": "", "source": "s", "date": "d",
                 "metrics": "", "comment": "", "breaking": false, "daysSeen": 1},
                {"title": "健康条目", "url": "https://e.com/ok", "source": "s", "date": "d",
                 "metrics": "", "comment": "", "breaking": false, "daysSeen": 1}
              ]},
              {"title": "", "summary": "空标题板块", "items": [
                {"title": "t", "url": "https://e.com/y", "source": "s", "date": "d",
                 "metrics": "", "comment": "", "breaking": false, "daysSeen": 1}
              ]}
            ]
        """.trimIndent())
        )
        val d = WeeklyRepository.parseDigest(json)
        assertEquals(1, d.sections.size)           // 空标题板块整块丢弃
        assertEquals(1, d.sections[0].items.size)  // 脏条目丢弃后只剩健康条目
        assertEquals("健康条目", d.sections[0].items[0].title)
    }
}
