package com.example.smsgateway.data



import androidx.room.Dao

import androidx.room.Insert

import androidx.room.OnConflictStrategy

import androidx.room.Query

import androidx.room.Update



@Dao

interface ClubAutoMessageDao {

    @Query("SELECT * FROM club_auto_message_rules ORDER BY id DESC")

    suspend fun listRules(): List<ClubAutoMessageRuleEntity>



    @Query("SELECT * FROM club_auto_message_rules WHERE enabled = 1 ORDER BY id ASC")

    suspend fun listEnabledRules(): List<ClubAutoMessageRuleEntity>



    @Query("SELECT * FROM club_auto_message_rules WHERE id = :id LIMIT 1")

    suspend fun findRuleById(id: Long): ClubAutoMessageRuleEntity?



    @Insert

    suspend fun insertRule(rule: ClubAutoMessageRuleEntity): Long



    @Update

    suspend fun updateRule(rule: ClubAutoMessageRuleEntity)



    @Query("DELETE FROM club_auto_message_rules WHERE id = :id")

    suspend fun deleteRule(id: Long)



    @Query("DELETE FROM club_auto_message_sends WHERE ruleId = :ruleId")

    suspend fun deleteSendsForRule(ruleId: Long)



    @Query(

        """

        SELECT * FROM club_auto_message_rules

        WHERE linkedSerialGroupId = :groupId

        ORDER BY id ASC

        """,

    )

    suspend fun listByLinkedGroup(groupId: Long): List<ClubAutoMessageRuleEntity>



    @Query(

        """

        SELECT * FROM club_auto_message_rules

        WHERE linkedSerialGroupId = :groupId AND autoSource = :autoSource

        LIMIT 1

        """,

    )

    suspend fun findByLinkedGroupAndSource(

        groupId: Long,

        autoSource: String,

    ): ClubAutoMessageRuleEntity?



    @Insert(onConflict = OnConflictStrategy.IGNORE)

    suspend fun insertSend(send: ClubAutoMessageSendEntity): Long



    /** Lifetime check: this serial already received this rule on this channel. */

    @Query(

        """

        SELECT COUNT(*) FROM club_auto_message_sends

        WHERE ruleId = :ruleId

          AND serial = :serial

          AND channel = :channel

        """,

    )

    suspend fun countSendBySerial(

        ruleId: Long,

        serial: String,

        channel: String,

    ): Int



    @Query(

        """

        SELECT COUNT(DISTINCT serial) FROM club_auto_message_sends

        WHERE ruleId = :ruleId

          AND sentDateJalali = :sentDateJalali

        """,

    )

    suspend fun countDistinctSerialsSentOnDate(

        ruleId: Long,

        sentDateJalali: String,

    ): Int



    @Query(

        """

        SELECT COUNT(*) FROM club_auto_message_sends

        WHERE ruleId = :ruleId

        """,

    )

    suspend fun countSendsForRule(ruleId: Long): Int



    @Query(

        """

        SELECT * FROM club_auto_message_sends

        WHERE ruleId = :ruleId

        ORDER BY sentAt DESC

        LIMIT :limit OFFSET :offset

        """,

    )

    suspend fun listSendsForRule(

        ruleId: Long,

        limit: Int,

        offset: Int,

    ): List<ClubAutoMessageSendEntity>



    @Query(

        """

        UPDATE club_auto_message_rules

        SET lastRunAt = :lastRunAt,

            lastRunDateJalali = :lastRunDateJalali,

            lastSuccessCount = :lastSuccessCount,

            lastFailCount = :lastFailCount,

            runStatus = :runStatus,

            runStartedAt = :runStartedAt,

            runTotalCount = :runTotalCount,

            runProcessedCount = :runProcessedCount,

            runSentCount = :runSentCount,

            runFailCount = :runFailCount,

            updatedAt = :updatedAt

        WHERE id = :id

        """,

    )

    suspend fun updateLastRun(

        id: Long,

        lastRunAt: Long,

        lastRunDateJalali: String,

        lastSuccessCount: Int,

        lastFailCount: Int,

        runStatus: String,

        runStartedAt: Long?,

        runTotalCount: Int,

        runProcessedCount: Int,

        runSentCount: Int,

        runFailCount: Int,

        updatedAt: Long,

    )



    @Query(

        """

        UPDATE club_auto_message_rules

        SET runStatus = :runStatus,

            runStartedAt = :runStartedAt,

            runTotalCount = :runTotalCount,

            runProcessedCount = :runProcessedCount,

            runSentCount = :runSentCount,

            runFailCount = :runFailCount,

            updatedAt = :updatedAt

        WHERE id = :id

        """,

    )

    suspend fun updateRunProgress(

        id: Long,

        runStatus: String,

        runStartedAt: Long?,

        runTotalCount: Int,

        runProcessedCount: Int,

        runSentCount: Int,

        runFailCount: Int,

        updatedAt: Long,

    )

}


