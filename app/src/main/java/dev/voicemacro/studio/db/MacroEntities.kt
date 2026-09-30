package dev.voicemacro.studio.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.util.UUID

@Entity(tableName = "flows")
data class Flow(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String?,
    val packageName: String,
    val schemaVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
)

enum class ActionType { CLICK, TYPE, SCROLL, WAIT }

@Entity(
    tableName = "steps",
    primaryKeys = ["flowId", "orderIndex"],
    foreignKeys = [ForeignKey(entity = Flow::class, parentColumns = ["id"], childColumns = ["flowId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("flowId")]
)
data class Step(
    val flowId: String,
    val orderIndex: Int,
    val actionType: ActionType,
    val selectorClassName: String?,
    val selectorText: String?,
    val selectorDesc: String?,
    val selectorViewId: String? = null,
    val selectorContext: String? = null,
    val selectorCenterX: Int? = null,
    val selectorCenterY: Int? = null,
    val slotBinding: String?,
    val expectedPostcondition: String? = null
)

@Entity(
    tableName = "slots",
    primaryKeys = ["flowId", "name"],
    foreignKeys = [ForeignKey(entity = Flow::class, parentColumns = ["id"], childColumns = ["flowId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("flowId")]
)
data class SlotDefinition(
    val flowId: String,
    val name: String,
    val type: String, // String, Int, etc.
    val isRequired: Boolean = true
)

@Entity(
    tableName = "run_logs"
)
data class RunLog(
    @PrimaryKey val runId: String = UUID.randomUUID().toString(),
    val flowId: String?,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val outcome: String? = null, // SUCCESS, FAILED
    val lastVerifiedStep: Int? = null,
    val summary: String? = null
)

class Converters {
    @TypeConverter
    fun fromActionType(value: ActionType): String = value.name

    @TypeConverter
    fun toActionType(value: String): ActionType = ActionType.valueOf(value)
}
