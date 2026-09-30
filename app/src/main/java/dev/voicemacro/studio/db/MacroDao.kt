package dev.voicemacro.studio.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface MacroDao {
    @Insert
    suspend fun insertFlow(flow: Flow)

    @Insert
    suspend fun insertSteps(steps: List<Step>)

    @Insert
    suspend fun insertSlots(slots: List<SlotDefinition>)

    @Query("SELECT * FROM flows WHERE packageName = :packageName ORDER BY createdAt DESC")
    suspend fun getFlowsForPackage(packageName: String): List<Flow>

    @Query("SELECT * FROM flows ORDER BY createdAt DESC")
    suspend fun getAllFlows(): List<Flow>

    @Query("SELECT * FROM steps WHERE flowId = :flowId ORDER BY orderIndex ASC")
    suspend fun getStepsForFlow(flowId: String): List<Step>

    @Query("SELECT * FROM slots WHERE flowId = :flowId")
    suspend fun getSlotsForFlow(flowId: String): List<SlotDefinition>

    @Insert
    suspend fun insertRunLog(log: RunLog)

    @Query("DELETE FROM run_logs")
    suspend fun deleteRunLogs()

    @Query("DELETE FROM flows")
    suspend fun deleteFlows()

    @Transaction
    suspend fun deleteAllLearnedFlows() {
        deleteRunLogs()
        // Foreign-key cascades remove the related steps and slot definitions.
        deleteFlows()
    }

    @Transaction
    suspend fun saveRecordedFlow(flow: Flow, steps: List<Step>, slots: List<SlotDefinition>) {
        insertFlow(flow)
        if (steps.isNotEmpty()) insertSteps(steps)
        if (slots.isNotEmpty()) insertSlots(slots)
    }
}
